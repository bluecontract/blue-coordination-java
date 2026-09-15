package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ManagedDocumentStepContinuation;
import blue.language.processor.ManagedDocumentStepRuntime;
import blue.language.processor.ManagedExternalDeliveryClassification;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.DirectLogicalDelivery;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedScopeKey;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real Language classification, not a stubbed processor or publication path. */
final class BlueRuntimeEligibilityCacheTest {
    @Test void repeatedActualComparisonHitsAndExplicitCacheResetReclassifies() throws Exception {
        // given
        try (var fixture = new Fixture()) {
            var runtime = fixture.engine.runtime();
            var input = fixture.input;
            // when
            var first = runtime.eligibleRootDeliveries(input.snapshot(), input.directDeliveries(), fixture.entry);
            long compared = runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS);
            var second = runtime.eligibleRootDeliveries(input.snapshot(), input.directDeliveries(), fixture.entry);
            // then
            assertFalse(first.isEmpty());
            assertEquals(first, second);
            assertTrue(compared > 0L);
            assertEquals(compared, runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS));
            runtime.clearSnapshotCaches();
            assertEquals(first, runtime.eligibleRootDeliveries(input.snapshot(), input.directDeliveries(), fixture.entry));
            assertEquals(compared + input.directDeliveries().size(),
                    runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS));
            runtime.close();
            assertThrows(IllegalStateException.class, () ->
                    runtime.eligibleRootDeliveries(input.snapshot(), input.directDeliveries(), fixture.entry));
        }
    }

    @Test void actualInvalidChannelExceptionIsRetriedRatherThanStoredAsIneligible() throws Exception {
        // given
        try (var fixture = new Fixture()) {
            var runtime = fixture.engine.runtime();
            var input = fixture.input;
            var delivery = input.directDeliveries().get(0);
            var invalid = List.of(new DirectLogicalDelivery(delivery.targetScope(), "not-an-active-channel",
                    delivery.logicalDeliveryKey(), delivery.rawOccurrenceOrder()));
            long before = runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS);
            // when
            for (int n = 0; n < 2; n++) assertThrows(InvalidExecutionEvidenceException.class, () ->
                    runtime.eligibleRootDeliveries(input.snapshot(), invalid, fixture.entry));
            // then
            assertEquals(before + 2L, runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS));
            assertFalse(runtime.eligibleRootDeliveries(input.snapshot(), input.directDeliveries(), fixture.entry).isEmpty());
            assertEquals(0L, fixture.engine.auditDocument(fixture.root).epoch());
        }
    }

    @Test void malformedBodyCannotReuseAValidMaskThroughItsOldAssertedHead() throws Exception {
        // given
        try (var fixture = new Fixture()) {
            var runtime = fixture.engine.runtime();
            var input = fixture.input;
            assertFalse(runtime.eligibleRootDeliveries(input.snapshot(), input.directDeliveries(), fixture.entry).isEmpty());
            var target = input.directDeliveries().get(0);
            var original = input.snapshot().managedDocument(target.targetDocumentId());
            Node malformed = original.document();
            assertNotNull(malformed.getContracts().getProperties().remove(target.channelKey()));
            var changed = new ManagedDocumentSnapshot(original.documentId(), original.blueId(), malformed,
                    original.initialized(), original.terminated(), original.publicRoot(), original.epoch(), original.componentGeneration());
            var prior = input.snapshot();
            var documents = prior.managedDocuments().stream().map(value ->
                    value.documentId().equals(changed.documentId()) ? changed : value).toList();
            var bad = new AffectedClosureSnapshot(prior.closureIdentity(), prior.graphGeneration(), documents,
                    prior.occurrences(), prior.occurrenceBindingSetIdentity(), prior.components(), prior.publicRootDocumentIds());
            long before = runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS);
            // when
            assertThrows(RuntimeException.class, () -> runtime.eligibleRootDeliveries(bad, input.directDeliveries(), fixture.entry));
            // then
            assertEquals(original.blueId(), bad.managedDocument(original.documentId()).blueId());
            assertEquals(original.epoch(), bad.managedDocument(original.documentId()).epoch());
            assertEquals(before + 1L, runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS));
            assertFalse(runtime.eligibleRootDeliveries(input.snapshot(), input.directDeliveries(), fixture.entry).isEmpty());
            assertEquals(before + 1L, runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS));
        }
    }

    @Test void priorSingleDeliveryHitCannotSubsidizeASecondCallSharedComparisonMeter() throws Exception {
        // given
        try (var fixture = new Fixture();
             var language = BlueLanguage.builder().nodeProvider(fixture.engine.runtime().nodeProvider()).build();
             var contracts = BlueContracts.builder(language.processing()).build()) {
            var event = ExactEventIdentityEvidence.verify(contracts.runtimeAccess(), fixture.entry.exactEvent().copyNode(),
                    fixture.entry.blueId(), null);
            var processor = fixture.engine.runtime().documentProcessor();
            var first = List.of(fixture.input.directDeliveries().get(0));
            long gas = processor.withCapturedConfiguration(() -> {
                try (var comparison = new ManagedDocumentStepRuntime(processor)) {
                    var selected = comparison.classifyExternalDelivery(fixture.input.snapshot()
                                    .managedDocument(first.get(0).targetDocumentId()).document(), first.get(0).channelKey(),
                            event, GasChargeContext.empty());
                    assertEquals(ManagedExternalDeliveryClassification.State.ACCEPTED_NEW, selected.state());
                    assertTrue(selected.handlerMatched());
                    return comparison.totalGas();
                }
            });
            assertTrue(gas > 0L);
            var cache = new RootedEligibilityCache();
            var calls = new AtomicInteger();
            assertEquals(first, cache.select(fixture.input.snapshot(), first, fixture.entry,
                    () -> compare(fixture, first, event, gas, calls)));
            var both = List.of(first.get(0), first.get(0));
            // when
            for (int retry = 0; retry < 2; retry++) assertThrows(GasLimitExceededException.class, () ->
                    cache.select(fixture.input.snapshot(), both, fixture.entry,
                            () -> compare(fixture, both, event, gas, calls)));
            // then
            assertEquals(5, calls.get(), "Each failed two-delivery call must re-run its first charged comparison");
            assertEquals(1, cache.entryCount(), "Neither exhausted call may retain a partial result");
            assertEquals(first, cache.select(fixture.input.snapshot(), first, fixture.entry,
                    () -> compare(fixture, first, event, gas, calls)));
            assertEquals(5, calls.get());
        }
    }

    @Test void missingTargetAndEventRetainOriginalValidationRatherThanKeyCaptureFailures() throws Exception {
        // given
        try (var fixture = new Fixture()) {
            var runtime = fixture.engine.runtime();
            var input = fixture.input;
            var missing = List.of(new DirectLogicalDelivery(ManagedScopeKey.root(
                    new blue.language.processor.closure.DocumentId("missing-target")), "owner", "delivery", 0));
            assertNull(input.snapshot().managedDocument(missing.get(0).targetDocumentId()));
            long comparisons = runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS);
            // when
            assertThrows(NullPointerException.class, () ->
                    runtime.eligibleRootDeliveries(input.snapshot(), missing, fixture.entry));
            // then
            assertEquals(comparisons + 1L, runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS),
                    "The original classifier, not the structural capture, must encounter the missing target");
            assertThrows(NullPointerException.class, () ->
                    runtime.eligibleRootDeliveries(input.snapshot(), input.directDeliveries(), null));
            assertEquals(comparisons + 1L, runtime.metrics().counter(BlueRuntime.ROOT_ELIGIBILITY_COMPARISONS),
                    "Original exact-event validation must still precede root comparison");
        }
    }

    private static boolean[] compare(Fixture fixture, List<DirectLogicalDelivery> deliveries,
            ExactEventIdentityEvidence event, long gas, AtomicInteger calls) {
        var processor = fixture.engine.runtime().documentProcessor();
        return processor.withCapturedConfiguration(() -> {
            try (var comparison = new ManagedDocumentStepRuntime(processor, gas, Map.of(), NO_EFFECTS)) {
                boolean[] mask = new boolean[deliveries.size()];
                for (int index = 0; index < deliveries.size(); index++) {
                    var delivery = deliveries.get(index);
                    calls.incrementAndGet();
                    var selected = comparison.classifyExternalDelivery(fixture.input.snapshot()
                                    .managedDocument(delivery.targetDocumentId()).document(), delivery.channelKey(),
                            event, GasChargeContext.empty());
                    mask[index] = selected.state() == ManagedExternalDeliveryClassification.State.ACCEPTED_NEW
                            && selected.handlerMatched();
                }
                return mask;
            }
        });
    }

    private static final ManagedDocumentStepContinuation NO_EFFECTS = new ManagedDocumentStepContinuation() {
        @Override public void afterPatch(String scope, Node document, FrozenJsonPatch patch, List<DocumentUpdateOccurrence> updates) {
            fail("Read-only comparison cannot apply a patch");
        }
        @Override public void onApplicationEvent(String scope, String contract, ExactEventIdentityEvidence event) {
            fail("Read-only comparison cannot emit an event");
        }
        @Override public void onTerminationRequested(String scope, String cause, String reason) {
            fail("Read-only comparison cannot terminate a document");
        }
    };

    private static final class Fixture implements AutoCloseable {
        final BlueCoordination blue;
        final DefaultCoordinationEngine engine;
        final blue.coordination.api.DocumentId root;
        final TimelineEntry entry;
        final ClosureInvocationInput input;

        Fixture() throws Exception {
            blue = BlueCoordination.builder().contentDerivedDocumentIds()
                    .release(BundledContracts10Release.manifest().blueLanguageSpecification(),
                            BundledContracts10Release.manifest().contractsSpecification()).build();
            engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            var timeline = blue.timelines().register("rcp2/source", "alice");
            String yaml;
            try (var resource = getClass().getResourceAsStream("/rooted/source.yaml")) {
                if (resource == null) throw new IllegalStateException("Missing actual rooted source fixture");
                yaml = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
            var document = blue.documents().admitStaticProcessEmbedded(yaml, ActivationPolicy.importFullHistory()).document("root");
            root = document.id();
            var handle = blue.events().from(timeline).exact(blue.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: rcp2/source
                    timestamp: 100
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                    message:
                      type: Coordination/Operation Request
                      document:
                        blueId: %s
                      requireExactDocumentVersion: false
                      operation: tick
                      channel: owner
                      request: {}
                    """.formatted(document.snapshot().blueId()))).submit();
            entry = engine.auditTimelineEntry(handle.blueId()).orElseThrow();
            input = engine.contractsClosureAdapter().captureRoot(root, entry).invocations().get(0).input();
        }

        @Override public void close() { blue.close(); }
    }
}
