package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ProcessingSelection;
import blue.coordination.internal.CoordinationTestControl;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded standalone SDK diagnostic; fixtures are read from the unchanged maintained regression. */
final class RootedManagedDraftRejectionTest {
    private static final SdkStorageCodec CODEC = new SdkStorageCodec(new Object(), 32 * 1024 * 1024);
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void wrongExactBirthRejectsWithoutPublishingAndSurvivesRestart(boolean submit) throws Exception {
        // given
        boolean submitOnly = submit;
        // when
        var evidence = run(submitOnly, "wrongExactState");
        // then
        assertEquals(2, evidence.entries().size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zeroMatches", "wrongPath"})
    void unusedDeclaredBirthRetainsActualCompletedGasAndNoEffects(String operation) throws Exception {
        // given
        String selected = operation;
        // when
        var evidence = run(false, selected);
        // then
        assertEquals(2, evidence.entries().size());
    }

    @org.junit.jupiter.api.Test
    void freshRuntimeReplayMatchesSubmitAndExecuteExactly() throws Exception {
        // given
        var first = run(false, "wrongExactState");
        // when
        var fresh = run(true, "wrongExactState");
        // then
        assertEquals(first, fresh);
    }

    private static Evidence run(boolean submit, String operation) throws Exception {
        return run(submit, operation, new ResidentCheckpoints());
    }

    static Evidence run(boolean submit, String operation, RuntimeCheckpoints checkpoints) throws Exception {
        var hostId = DocumentId.of("rooted-rejected-birth-host");
        var childId = DocumentId.of("rooted-rejected-birth-child");
        String timelineId = "rooted/rejected-birth";
        String hostYaml = resource("host.yaml");
        String childYaml = resource("child.yaml");
        try (checkpoints) {
            var blue = checkpoints.current();
            var timeline = blue.timelines().register(timelineId, "alice");
            var host = blue.documents().admit(ManagedDocument.yaml(hostId, hostYaml).publicRoot().fromNow());
            var child = blue.documents().draft(childId, blue.values().yaml(childYaml));
            var wrong = blue.values().yaml(childYaml.replace("state: draft", "state: altered"));
            String before = host.exact().json();
            String originalTargetBlueId = host.snapshot().blueId();
            var history = host.history().stream().map(r -> r.managedEpochReceipt().orElseThrow().receiptIdentity()).toList();
            var call = blue.operations().on(host).from(timeline).call(operation).through("ownerChannel")
                    .request(r -> { r.managed("order", child); if (operation.equals("wrongExactState")) r.exact("wrong", wrong); })
                    .expectOccurrence("/orders/expected", child);
            EntryResult rejected;
            if (submit) {
                var entry = call.submit();
                rejected = blue.processing().drain(new DrainBudget(1, 1)).entry(entry);
            } else rejected = call.execute();
            System.out.println("rejected submit=" + submit + " disposition=" + rejected.disposition());
            assertEquals(EntryDisposition.REJECTED, rejected.disposition());
            assertEquals("MANAGED_OCCURRENCE_BINDING_MISSING", rejected.diagnostic().code());
            assertEquals(1, rejected.closures().size());
            assertEquals(1, rejected.closures().get(0).processorAttemptCount());
            if (operation.equals("wrongExactState")) {
                assertEquals(ProcessingStats.zero(), rejected.stats());
                assertTrue(rejected.closures().get(0).resourceDemands().stream().anyMatch(demand ->
                        demand.managedResolutionStatus().orElse(null) == ClosureResult.ManagedResolutionStatus.REJECTED_MANAGED_DECLARATION));
            } else {
                assertTrue(rejected.stats().gas() > 0);
                assertEquals(1, rejected.stats().documentsOpened());
                assertEquals(List.of(hostId), rejected.stats().documentStepOrder());
                assertEquals(rejected.stats().gas(), rejected.stats().counters().values().stream().reduce(0L, Math::addExact));
                assertEquals(0, rejected.stats().counter("processor.processorMarkerWritten"));
                assertEquals(0, rejected.stats().counter("processor.rootEventRecorded"));
            }
            assertEquals(0, rejected.stats().committedTransitions());
            assertTrue(rejected.publicEvents().isEmpty());
            assertTrue(rejected.closures().get(0).changes().isEmpty());
            assertEquals(before, host.exact().json());
            assertEquals(history, host.history().stream().map(r -> r.managedEpochReceipt().orElseThrow().receiptIdentity()).toList());
            assertThrows(blue.coordination.api.CoordinationException.class, () -> blue.documents().require(childId));
            assertTrue(blue.processing().drain(new DrainBudget(1, 1)).quiescent());
            assertTrue(host.snapshot().ready());
            byte[] rejectedBytes = CODEC.encode(rejected);
            var replay = blue.processing().process(host, rejected.entry()).entry(rejected.entry());
            assertEquals(rejected.disposition(), replay.disposition());
            assertEquals(rejected.stats(), replay.stats());
            assertEquals(rejected.closures().get(0).resourceDemands(), replay.closures().get(0).resourceDemands());
            assertArrayEquals(rejectedBytes, CODEC.encode(replay));
            var restarted = checkpoints.restart();
            var restartedHost = restarted.documents().require(hostId);
            assertNoRunnableWork(restarted);
            var retainedRejection = retainedResult(restarted, rejected.entry().blueId(), rejectedBytes);
            var afterRestart = restarted.processing().process(restartedHost, retainedRejection.entry())
                    .entry(retainedRejection.entry());
            assertEquals(EntryDisposition.REJECTED, afterRestart.disposition());
            assertArrayEquals(rejectedBytes, CODEC.encode(afterRestart));
            assertEquals(before, restartedHost.exact().json());
            assertEquals(history, receipts(restartedHost));
            assertThrows(blue.coordination.api.CoordinationException.class, () -> restarted.documents().require(childId));
            assertTrue(restartedHost.snapshot().ready());
            // A draft is an owner-bound declaration, not a retained admitted child.
            // Recreate that exact declaration under the new owner; do not admit it.
            var restartedChild = restarted.documents().draft(childId, restarted.values().yaml(childYaml));
            var restartedTimeline = restarted.runtimeForStorage().storedMaps().timelines().get(timelineId);
            assertNotNull(restartedTimeline, "Reopening retains the original Timeline registration");
            var valid = restarted.operations().on(restartedHost).from(restartedTimeline).call("validCreate").through("ownerChannel")
                    .request(r -> r.managed("order", restartedChild)).expectOccurrence("/orders/expected", restartedChild).execute();
            assertEquals(EntryDisposition.APPLIED, valid.disposition());
            assertEquals(1, restarted.documents().require(childId).snapshot().longAt("/initializationCount"));
            assertEquals(2, restarted.advanced().auditTimelineEntries().size());
            assertNoRunnableWork(restarted);
            var completed = evidence(restarted, hostId, childId);
            assertOldTargetIsStaleWithoutChangingRejection(restarted, restartedHost,
                    rejected.entry().blueId(), rejectedBytes, originalTargetBlueId);
            assertEquals(completed, evidence(restarted, hostId, childId), "Old rejection cannot publish after the child exists");
            byte[] validBytes = CODEC.encode(valid);
            var finalOwner = checkpoints.restart();
            assertNoRunnableWork(finalOwner);
            var finalHost = finalOwner.documents().require(hostId);
            assertOldTargetIsStaleWithoutChangingRejection(finalOwner, finalHost,
                    rejected.entry().blueId(), rejectedBytes, originalTargetBlueId);
            retainedResult(finalOwner, valid.entry().blueId(), validBytes);
            assertEquals(1, finalOwner.documents().require(childId).snapshot().longAt("/initializationCount"));
            assertEquals(completed, evidence(finalOwner, hostId, childId));
            return evidence(finalOwner, hostId, childId);
        }
    }

    interface RuntimeCheckpoints extends AutoCloseable {
        BlueCoordination current();
        BlueCoordination restart();
        @Override void close();
    }

    private static final class ResidentCheckpoints implements RuntimeCheckpoints {
        private final BlueCoordination blue = BlueCoordination.inMemory();
        @Override public BlueCoordination current() { return blue; }
        @Override public BlueCoordination restart() {
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            return blue;
        }
        @Override public void close() { blue.close(); }
    }

    private static EntryResult retainedResult(BlueCoordination blue, String entryId, byte[] expected) {
        var retained = blue.runtimeForStorage().storedMaps().results().get(entryId);
        assertNotNull(retained);
        assertArrayEquals(expected, CODEC.encode(retained));
        return retained;
    }

    private static void assertOldTargetIsStaleWithoutChangingRejection(BlueCoordination blue, DocumentHandle host,
            String entryId, byte[] expected, String originalTargetBlueId) {
        var retained = retainedResult(blue, entryId, expected);
        assertEquals(EntryDisposition.REJECTED, retained.disposition());
        String currentBlueId = host.snapshot().blueId();
        assertNotEquals(originalTargetBlueId, currentBlueId);
        // Like RootedRetargetInputTest, process evaluates the supplied input
        // against the current forward view; it is not a historical-result lookup.
        // The old exact-version target is stale, while its stored rejection is exact.
        var replayed = blue.processing().process(host, retained.entry()).entry(retained.entry());
        assertEquals(EntryDisposition.STALE, replayed.disposition());
        assertEquals("STALE_TARGET_DOCUMENT", replayed.diagnostic().code());
        assertEquals(Map.of("documentId", host.id().value(), "expectedBlueId", originalTargetBlueId,
                "currentBlueId", currentBlueId), replayed.diagnostic().details());
        assertEquals(ProcessingStats.zero(), replayed.stats());
        assertTrue(replayed.closures().isEmpty());
        assertTrue(replayed.publicEvents().isEmpty());
        retainedResult(blue, entryId, expected);
    }

    private static void assertNoRunnableWork(BlueCoordination blue) {
        var idle = blue.processing().drain(new DrainBudget(1, 1));
        assertTrue(idle.quiescent());
        assertEquals(0, idle.stats().gas());
        assertEquals(0, idle.stats().committedTransitions());
        assertTrue(idle.managedEpochApplicationAttempts().isEmpty());
        assertTrue(idle.rootedRetainedApplications().isEmpty());
        assertEquals(ProcessingSelection.Kind.NONE, blue.advanced().auditNextProcessingSelection().kind());
    }

    private static Evidence evidence(BlueCoordination blue, DocumentId hostId, DocumentId childId) {
        var host = blue.documents().require(hostId);
        var child = blue.documents().require(childId);
        return new Evidence(host.exact().json(), child.exact().json(), receipts(host), receipts(child),
                blue.advanced().auditTimelineEntries().stream().map(entry -> entry.blueId()).toList());
    }

    record Evidence(String host, String child, List<String> hostReceipts,
            List<String> childReceipts, List<String> entries) { }

    private static List<String> receipts(DocumentHandle document) {
        return document.history().stream().map(revision -> revision.managedEpochReceipt().orElseThrow().receiptIdentity()).toList();
    }

    private static String resource(String name) throws java.io.IOException {
        try (var in = RootedManagedDraftRejectionTest.class.getResourceAsStream("/rooted-managed-rejections/" + name)) {
            if (in == null) throw new java.io.IOException("Missing rejection fixture " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
