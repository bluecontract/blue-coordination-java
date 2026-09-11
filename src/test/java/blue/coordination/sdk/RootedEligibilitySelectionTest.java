package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import blue.language.processor.closure.ClosureProcessResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Actual rooted selection remains exact while repeated physical comparisons are disposable. */
final class RootedEligibilitySelectionTest {
    @Test void rejectedUnchangedHeadsAreMemoizedWithoutHidingTheNextLiveInput() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.startYaml(RootedSdkFixture.resource("source.yaml") + """
                      reject:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request: {}
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $appendChange:
                              op: remove
                              path: /does-not-exist
                          - $return: true
                    """, "rcp2/source");
            var original = source.snapshot().exact().json();
            var history = f.history(source);
            for (int n = 1; n <= 8; n++) {
                var entry = f.append(source, "rcp2/source", "reject", n, "{}");
                var reference = RootedCalculationFixture.materializedReference(f.control.capture(source.id(), entry.blueId(), null));
                var rejected = f.blue.processing().processNext(source).entry(entry);
                assertEquals(EntryDisposition.REJECTED, rejected.disposition());
                assertExact(reference, f.blue.advanced().closureExecution(rejected.closures().get(0).closureId()).orElseThrow());
                assertEquals(original, source.snapshot().exact().json());
                assertEquals(history, f.history(source));
            }
            var next = f.append(source, "rcp2/source", "tick", 20, "{}");
            // when
            assertEquals(next.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            long compared = comparisons(f);
            for (int n = 0; n < 4; n++) assertEquals(next.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            // then
            assertTrue(compared > 0L, "The real classifier was reached before a cached audit");
            assertEquals(compared, comparisons(f), "Unchanged history must not reopen/classify every past root");
            assertEquals(history, f.history(source));
            var reference = RootedCalculationFixture.materializedReference(f.control.capture(source.id(), next.blueId(), null));
            var applied = f.blue.processing().processNext(source).entry(next);
            assertEquals(EntryDisposition.APPLIED, applied.disposition());
            assertExact(reference, f.blue.advanced().closureExecution(applied.closures().get(0).closureId()).orElseThrow());
            assertEquals(1L, source.snapshot().longAt("/counter"));
            assertEquals(history.size() + 1, f.history(source).size());
            assertTrue(f.control.nextLiveInput(source.id()).isEmpty());
        }
    }

    @Test void genuineSameEpochCheckpointViewsInvalidateEligibilityWithoutPublishingTheBorrowedSource() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.startYaml(RootedSdkFixture.resource("source.yaml") + """
                      emitUnmatched:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request: {}
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: RCP2/Unmatched
                          - $return: true
                    """, "rcp2/source");
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of("child", f.retain(source)));
            var sourceBefore = source.snapshot().exact().json();
            var histories = List.of(f.history(source), f.history(parent));
            long epoch = parent.snapshot().epoch();
            String previous = parent.snapshot().blueId();
            // when
            for (long timestamp : List.of(100L, 200L)) {
                var entry = f.append(source, "rcp2/source", "emitUnmatched", timestamp, "{}");
                assertEquals(entry.blueId(), f.control.nextLiveInput(parent.id()).orElseThrow());
                long compared = comparisons(f);
                assertEquals(entry.blueId(), f.control.nextLiveInput(parent.id()).orElseThrow());
                assertEquals(compared, comparisons(f));
                var reference = RootedCalculationFixture.materializedReference(f.control.capture(parent.id(), entry.blueId(), null));
                var result = f.blue.processing().processNext(parent).entry(entry);
                // then
                assertEquals(EntryDisposition.APPLIED, result.disposition());
                assertExact(reference, f.blue.advanced().closureExecution(result.closures().get(0).closureId()).orElseThrow());
                assertEquals(epoch, parent.snapshot().epoch(), "This must be a real same-numbered-epoch representation transition");
                assertNotEquals(previous, parent.snapshot().blueId());
                previous = parent.snapshot().blueId();
                assertTrue(f.control.nextLiveInput(parent.id()).isEmpty());
                assertTrue(comparisons(f) > compared, "The new exact checkpoint representation must be reclassified");
                assertEquals(sourceBefore, source.snapshot().exact().json());
                assertEquals(histories, List.of(f.history(source), f.history(parent)));
            }
        }
    }

    @Test void gasFailureEvictionAndPublicationRollbackKeepExactHistoryAndLaterInputAcrossRestart() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            var entry = f.append(source, "rcp2/source", "tick", 100, "{}");
            var calibration = RootedCalculationFixture.materializedReference(f.control.capture(source.id(), entry.blueId(), null));
            assertTrue(calibration.commits());
            assertTrue(calibration.totalGas() > 1L);
            var policy = ContractsExecutionPolicy.exactSharedGas(calibration.totalGas() - 1L, "eligibility-g-minus-one");
            var reference = RootedCalculationFixture.materializedReference(f.control.capture(source.id(), entry.blueId(), policy));
            var original = source.snapshot().exact().json();
            var history = f.history(source);
            assertEquals(entry.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            long warm = comparisons(f);
            assertEquals(entry.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            assertEquals(warm, comparisons(f));
            f.control.evictSnapshotCaches();
            assertEquals(entry.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            assertTrue(comparisons(f) > warm);
            // when
            var failed = f.blue.advanced().process(source, entry, policy).entry(entry);
            // then
            assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED, failed.disposition());
            assertExact(reference, f.blue.advanced().closureExecution(failed.closures().get(0).closureId()).orElseThrow());
            assertEquals(original, source.snapshot().exact().json());
            assertEquals(history, f.history(source));
            assertTrue(f.control.nextLiveInput(source.id()).isEmpty(), "A cached positive classification is not terminal authority");
            var later = f.append(source, "rcp2/source", "tick", 200, "{}");
            assertEquals(later.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            var nextReference = RootedCalculationFixture.materializedReference(f.control.capture(source.id(), later.blueId(), null));
            f.control.failPublicationAt("BEFORE_SWAP");
            assertThrows(RuntimeException.class, () -> f.blue.processing().processNext(source));
            f.control.clearPublicationFailure();
            assertEquals(original, source.snapshot().exact().json());
            assertEquals(history, f.history(source));
            f.control.evictSnapshotCaches();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(later.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            var retried = f.blue.processing().processNext(source).entry(later);
            assertEquals(EntryDisposition.APPLIED, retried.disposition());
            assertExact(nextReference, f.blue.advanced().closureExecution(retried.closures().get(0).closureId()).orElseThrow());
            var completed = f.history(source);
            var completeBody = source.snapshot().exact().json();
            assertEquals(history.size() + 1, completed.size());
            f.control.evictSnapshotCaches();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(f.control.nextLiveInput(source.id()).isEmpty());
            assertEquals(completed, f.history(source));
            assertEquals(completeBody, source.snapshot().exact().json());
        }
    }

    private static long comparisons(RootedSdkFixture f) {
        return CoordinationTestControl.attach(f.blue.advanced().rawEngine()).metricsSnapshot().counters()
                .getOrDefault("rooted.eligibilityComparisons", 0L);
    }

    private static void assertExact(ClosureProcessResult reference, ClosureProcessResult actual) {
        assertEquals(reference.status(), actual.status());
        assertEquals(reference.totalGas(), actual.totalGas());
        assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
        assertEquals(reference.rejectedCharge() == null ? null : reference.rejectedCharge().rejectedChargeIdentity(),
                actual.rejectedCharge() == null ? null : actual.rejectedCharge().rejectedChargeIdentity());
        assertEquals(reference.inputClosureIdentity(), actual.inputClosureIdentity());
        assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
        assertEquals(reference.rollbackToInput(), actual.rollbackToInput());
        assertEquals(reference.publicEventsIdentity(), actual.publicEventsIdentity());
        assertEquals(reference.checkpointWritesIdentity(), actual.checkpointWritesIdentity());
    }
}
