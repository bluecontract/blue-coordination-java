package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RootedJoinEligibilityTest {
    @Test void publicationRollbackCannotExposeAJoinLocatorAndRetryRetiresItAfterTheRealJoin() throws Exception {
        try (var f = new Fixture()) {
            var a = f.start("A"); var b = f.start("B");
            f.attach(a, "b", b); f.settle(a);
            var join = f.append(b, "attach", "edge: a\nsource: {blueId: " + f.authored.get(a.id()) + "}");
            var before = List.of(f.history(a), f.history(b));
            var index = f.engine.documents().occurrenceResolutionSnapshot().componentIndex();
            var control = new RootedCalculationFixture(f.engine);
            control.failPublicationAt("AFTER_TOPOLOGY_STAGED");
            assertThrows(RuntimeException.class, () -> f.blue.processing().processNext(b));
            control.clearPublicationFailure();
            assertSame(index, f.engine.documents().occurrenceResolutionSnapshot().componentIndex());
            assertEquals(before, List.of(f.history(a), f.history(b)));
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(b).entry(join).disposition());
            assertEquals(List.of(b.id()), f.engine.documents().occurrenceResolutionSnapshot().componentIndex().pendingJoinRootsFor(a.id()));
            var expected = RootedJoinEligibility.capture(f.engine.documents()).stream().filter(fence -> fence.owners().contains(a.id())).toList();
            assertEquals(expected, RootedJoinEligibility.captureForRoot(f.engine.documents(), a.id()));
            f.settle(b);
            for (var id : List.of(a.id(), b.id())) {
                assertTrue(f.engine.documents().occurrenceResolutionSnapshot().componentIndex().pendingJoinRootsFor(id).isEmpty());
                assertTrue(RootedJoinCandidateIndex.fromSessions(f.engine.documents().sessions()).rootsFor(id).isEmpty());
            }
            CoordinationTestControl.attach(f.engine).restartFromStores();
            assertTrue(RootedJoinEligibility.captureForRoot(f.engine.documents(), a.id()).isEmpty());
            var tick = f.append(a, "touch", "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(a).entry(tick).disposition());
        }
    }

    @Test void locatorReplacementRetiresOldMembershipWithoutChangingThePriorSnapshot() {
        var a = DocumentId.of("a"); var b = DocumentId.of("b"); var x = DocumentId.of("x");
        var first = RootedJoinCandidateIndex.empty().replaceRoot(b, Set.of(a, b, x));
        assertSame(first, first.replaceRoot(b, Set.of(x, a, b)));
        var replaced = first.replaceRoot(b, Set.of(a, b));
        assertEquals(List.of(b), first.rootsFor(x));
        assertTrue(replaced.rootsFor(x).isEmpty(), "A retired path must not leave a stale candidate");
        assertEquals(List.of(b), replaced.rootsFor(a));
        var retired = replaced.replaceRoot(b, Set.of());
        assertTrue(retired.rootsFor(a).isEmpty());
        assertTrue(retired.rootsFor(b).isEmpty());
    }


    @Test void oneWayPendingHistoryDoesNotBlockItsIndependentSource() throws Exception {
        try (var f = new Fixture()) {
            var a = f.start("A"); var b = f.start("B");
            f.attach(a, "b", b);
            assertFalse(f.blue.advanced().auditManagedDocumentReadiness(a.id()).orElseThrow().ready());
            assertTrue(RootedJoinEligibility.captureForRoot(f.engine.documents(), b.id()).isEmpty());
            var tick = f.append(b, "touch", "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(b).entry(tick).disposition());
            assertFalse(f.blue.advanced().auditManagedDocumentReadiness(a.id()).orElseThrow().ready());
            var sourceHistory = f.history(b);
            f.settle(a);
            assertEquals(sourceHistory, f.history(b));
            CoordinationTestControl.attach(f.engine).restartFromStores();
            assertEquals(sourceHistory, f.history(b));
        }
    }

    @Test void unavailableRelatedJoinBlocksOnlyItsExactCycleAcrossRestart() throws Exception {
        try (var f = new Fixture()) {
            var a = f.start("A"); var b = f.start("B"); var c = f.start("C");
            f.attach(a, "b", b); f.settle(a);
            var joined = f.attach(b, "a", a);
            var work = new RootedCalculationFixture(f.engine).registeredOwnedHistory(b.id());
            var before = List.of(f.history(a), f.history(b));
            var aHead = f.engine.documents().require(a.id()).currentRepresentation().blueId();
            var boundary = f.engine.auditTimelineEntry(joined.blueId()).orElseThrow().sourceOrderKey();
            var fences = RootedJoinEligibility.captureForRoot(f.engine.documents(), a.id());
            assertTrue(RootedJoinEligibility.blocks(fences, Set.of(a.id()), boundary), "The creating-entry equality belongs to the join barrier");
            var earlier = f.engine.auditTimelineEntries().get(0).sourceOrderKey();
            assertTrue(earlier.compareTo(boundary) < 0);
            assertFalse(RootedJoinEligibility.blocks(fences, Set.of(a.id()), earlier), "Earlier source prerequisites remain eligible");
            assertTrue(RootedJoinEligibility.captureForRoot(f.engine.documents(), c.id()).isEmpty());
            new RootedCalculationFixture(f.engine).deferRegisteredOwnedHistory(work);
            for (boolean restart : List.of(false, true)) {
                if (restart) CoordinationTestControl.attach(f.engine).restartFromStores();
                assertTrue(new RootedCheckpointDriver(f.engine.documents(), f.engine.contractsClosureAdapter())
                        .select(a.id(), f.engine.auditTimelineEntries()).blocked());
                var noOvertake = f.blue.processing().processNext(a);
                assertTrue(noOvertake.blocked());
                assertTrue(noOvertake.entries().isEmpty());
                assertTrue(noOvertake.managedEpochApplications().isEmpty());
                assertEquals(before, List.of(f.history(a), f.history(b)));
                assertEquals(aHead, f.engine.documents().require(a.id()).currentRepresentation().blueId());
                assertTrue(RootedJoinEligibility.captureForRoot(f.engine.documents(), c.id()).isEmpty(), "Unrelated unavailable barrier is not a point read");
                var tick = f.append(c, "touch", "{}");
                assertNotNull(new RootedCheckpointDriver(f.engine.documents(), f.engine.contractsClosureAdapter())
                        .select(c.id(), f.engine.auditTimelineEntries()).live());
                assertEquals(blue.coordination.api.ProcessingSelection.Kind.JOURNAL, f.blue.advanced().auditNextProcessingSelection().kind());
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().drainJournal(new blue.coordination.sdk.DrainBudget(1, 1)).entry(tick).disposition());
                assertEquals(before, List.of(f.history(a), f.history(b)));
                var retained = f.engine.documents().catchUpPlan(work.planIdentity()).orElseThrow();
                assertEquals(work.sourceEpoch(), retained.nextSourceEpoch());
                assertEquals(work.targetOccurrenceIdentity(), retained.targetOccurrenceIdentity());
            }
        }
    }

    @Test void sideBranchesAndIncomingObserversAreNotTerminalCycleMembers() throws Exception {
        try (var f = new Fixture()) {
            var a = f.start("A"); var b = f.start("B"); var c = f.start("C"); var observer = f.start("Observer");
            f.attach(a, "c", c); f.settle(a);
            f.attach(a, "b", b); f.settle(a);
            f.attach(observer, "c", c); f.settle(observer);
            f.attach(b, "a", a);
            var fences = RootedJoinEligibility.captureForRoot(f.engine.documents(), a.id());
            assertFalse(fences.isEmpty());
            assertTrue(fences.stream().allMatch(fence -> !fence.owners().contains(c.id()) && !fence.owners().contains(observer.id())));
            assertTrue(RootedJoinEligibility.captureForRoot(f.engine.documents(), c.id()).isEmpty());
            assertTrue(RootedJoinEligibility.captureForRoot(f.engine.documents(), observer.id()).isEmpty());
            var tick = f.append(c, "touch", "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(c).entry(tick).disposition());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Map<String, String> exact = new LinkedHashMap<>();
        final Map<DocumentId, String> authored = new LinkedHashMap<>();
        final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds()
                .exactNodeProvider(id -> Optional.ofNullable(exact.get(id))).build();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        final blue.coordination.sdk.TimelineHandle timeline = blue.timelines().register("rooted/join-eligibility", "alice");

        DocumentHandle start(String name) throws Exception {
            String template;
            try (var in = getClass().getResourceAsStream("/rooted/node-graph.template.json")) {
                template = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            }
            var source = template.replace("<NODE>", name).replace("<NAMESPACE>", "join-eligibility").replace("<TIMELINE>", "rooted/join-eligibility");
            var value = blue.values().yaml(source);
            exact.put(value.blueId(), value.json());
            var handle = blue.documents().admitStaticProcessEmbedded(source, ActivationPolicy.fromNow()).document("root");
            authored.put(handle.id(), value.blueId());
            return handle;
        }

        EntryHandle append(DocumentHandle target, String operation, String request) {
            return blue.operations().on(target).from(timeline).call(operation).through("owner").requestYaml(request).submit();
        }

        EntryHandle attach(DocumentHandle owner, String path, DocumentHandle source) {
            var entry = append(owner, "attach", "edge: " + path + "\nsource: {blueId: " + authored.get(source.id()) + "}");
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(owner).entry(entry).disposition());
            return entry;
        }

        void settle(DocumentHandle owner) {
            for (int i = 0; i < 24; i++) {
                var result = blue.processing().processNext(owner);
                assertFalse(result.blocked(), result.diagnostic().toString());
                if (result.quiescent()) return;
            }
            fail("Bounded actual fixture did not settle");
        }

        List<String> history(DocumentHandle handle) {
            return blue.advanced().auditManagedEpochs(handle.id()).stream().map(receipt -> receipt.receiptIdentity()).toList();
        }
        @Override public void close() { blue.close(); }
    }
}
