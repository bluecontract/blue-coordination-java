package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.internal.CoordinationTestControl;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded standalone SDK diagnostic; fixtures are read from the unchanged maintained regression. */
final class RootedManagedDraftRejectionTest {
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
        var hostId = DocumentId.of("rooted-rejected-birth-host");
        var childId = DocumentId.of("rooted-rejected-birth-child");
        String timelineId = "rooted/rejected-birth";
        String hostYaml = resource("host.yaml");
        String childYaml = resource("child.yaml");
        try (var blue = BlueCoordination.inMemory()) {
            var timeline = blue.timelines().register(timelineId, "alice");
            var host = blue.documents().admit(ManagedDocument.yaml(hostId, hostYaml).publicRoot().fromNow());
            var child = blue.documents().draft(childId, blue.values().yaml(childYaml));
            var wrong = blue.values().yaml(childYaml.replace("state: draft", "state: altered"));
            String before = host.exact().json();
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
            var replay = blue.processing().process(host, rejected.entry()).entry(rejected.entry());
            assertEquals(rejected.disposition(), replay.disposition());
            assertEquals(rejected.stats(), replay.stats());
            assertEquals(rejected.closures().get(0).resourceDemands(), replay.closures().get(0).resourceDemands());
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertTrue(blue.processing().drain(new DrainBudget(1, 1)).quiescent());
            var afterRestart = blue.processing().process(host, rejected.entry()).entry(rejected.entry());
            assertEquals(EntryDisposition.REJECTED, afterRestart.disposition());
            assertEquals(before, host.exact().json());
            var valid = blue.operations().on(host).from(timeline).call("validCreate").through("ownerChannel")
                    .request(r -> r.managed("order", child)).expectOccurrence("/orders/expected", child).execute();
            assertEquals(EntryDisposition.APPLIED, valid.disposition());
            assertEquals(1, blue.documents().require(childId).snapshot().longAt("/initializationCount"));
            assertEquals(2, blue.advanced().auditTimelineEntries().size());
            assertTrue(blue.processing().drain(new DrainBudget(1, 1)).quiescent());
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertTrue(blue.processing().drain(new DrainBudget(1, 1)).quiescent());
            assertEquals(1, blue.documents().require(childId).snapshot().longAt("/initializationCount"));
            return new Evidence(host.exact().json(), blue.documents().require(childId).exact().json(),
                    receipts(host), receipts(blue.documents().require(childId)),
                    blue.advanced().auditTimelineEntries().stream().map(entry -> entry.blueId()).toList());
        }
    }

    private record Evidence(String host, String child, List<String> hostReceipts,
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
