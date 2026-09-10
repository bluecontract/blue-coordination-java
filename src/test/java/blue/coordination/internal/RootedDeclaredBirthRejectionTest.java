package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.sdk.*;
import blue.language.processor.closure.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated authority and recovery probe using only actual processor-emitted demands. */
final class RootedDeclaredBirthRejectionTest {

    @org.junit.jupiter.api.Test
    void rejectsForgedDecisionAndStalePublicationWithoutSemanticWrites() throws Exception {
        // given
        try (var s = new Scenario()) {
            var before = s.engine.documents().publicationSnapshot();
            // when
            var outcome = s.adapter.executeAndPublish(s.batch, s.invocation);
            var decision = outcome.rejectedBirth();
            // then
            assertNotNull(decision);
            var after = s.engine.documents().publicationSnapshot();
            assertEquals(before.documentHeads(), after.documentHeads());
            assertEquals(before.closurePublicationReceipts(), after.closurePublicationReceipts());
            assertEquals(before.outbox(), after.outbox());
            assertEquals(before.checkpointEvidence(), after.checkpointEvidence());
            assertEquals(before.occurrenceInventory().rows(), after.occurrenceInventory().rows());
            assertEquals(before.componentStates(), after.componentStates());
            var issue = outcome.unresolvedDemands().get(0);
            var actual = (ManagedOccurrenceEvidenceDemand) issue.demand();
            var forged = ManagedOccurrenceEvidenceDemand.derived(actual.logicalCauseIdentity(),
                    actual.inputClosureIdentity(), actual.inputGraphGeneration(), actual.sourceDocumentId(),
                    actual.sourcePath(), actual.processEmbeddedDeclarationIdentity(), actual.suppliedValueBlueId(),
                    actual.demandOrdinal(), actual.suppliedExactValue().orElseThrow());
            assertEquals(actual.demandIdentity(), forged.demandIdentity());
            var forgedIssue = new ManagedOccurrenceResolver.UnresolvedDemand(forged, issue.status(), issue.diagnostic());
            assertThrows(IllegalArgumentException.class, () -> RootedDeclaredBirthRejection.capture(s.invocation,
                    s.invocation, ClosureAttemptResult.needsResources(List.of(forged)), List.of(forgedIssue), 0));
            assertThrows(IllegalArgumentException.class, () -> RootedDeclaredBirthRejection.capture(s.invocation,
                    s.invocation, outcome.attempt(), List.of(issue, issue), 0));
            assertThrows(IllegalArgumentException.class, () -> decision.requireOutcome(List.of(DocumentId.of("outsider")),
                    outcome.publicationMembers(), outcome.attempt(), false, outcome.publicationIdentity(), 0,
                    ManagedSurfacePublicationEvidence.empty(), outcome.unresolvedDemands()));
            assertThrows(IllegalArgumentException.class, () -> decision.requireOutcome(outcome.members(),
                    outcome.publicationMembers(), outcome.attempt(), true, outcome.publicationIdentity(), 0,
                    ManagedSurfacePublicationEvidence.empty(), outcome.unresolvedDemands()));
            assertThrows(IllegalArgumentException.class, () -> decision.requireOutcome(outcome.members(),
                    outcome.publicationMembers(), outcome.attempt(), false, "sha256:" + "0".repeat(64), 0,
                    ManagedSurfacePublicationEvidence.empty(), outcome.unresolvedDemands()));
            var state = new ContractsRootFeederWindow.DurableState();
            assertSame(decision, state.rejectBirth(decision, s.engine.documents()));
            assertSame(decision, state.copy().rejectedBirth(s.invocation));
            var other = s.submit("wrongExactState");
            var otherInput = s.adapter.captureRoot(s.host.id(), s.engine.auditTimelineEntry(other.blueId()).orElseThrow())
                    .invocations().get(0);
            assertThrows(IllegalArgumentException.class, () -> decision.requireSameObligation(otherInput));
            assertNull(state.rejectedBirth(otherInput));
            s.blue.processing().process(s.host, other);
            var valid = s.blue.operations().on(s.host).from(s.timeline).call("validCreate").through("ownerChannel")
                    .request(r -> r.managed("order", s.draft)).expectOccurrence("/orders/expected", s.draft).execute();
            assertEquals(EntryDisposition.APPLIED, valid.disposition());
            assertThrows(RuntimeException.class, () -> decision.requireCurrentFences(s.engine.documents()));
            System.out.println("PASS no semantic publication; recomputed-demand forgery; duplicates; wrong owner/publication/key/cause; copied feeder record; stale fence");
        }
    }

    @org.junit.jupiter.api.Test
    void responseLossAfterFeederDecisionSurvivesRestartWithoutSecondProcessing() throws Exception {
        // given
        try (var s = new Scenario()) {
            var before = s.engine.documents().publicationSnapshot();
            // when
            s.adapter.onPublicationFailurePoint(point -> { throw new IllegalStateException("lost rejection response"); });
            // then
            assertThrows(IllegalStateException.class, () -> s.adapter.executeAndPublish(s.batch, s.invocation));
            s.adapter.onPublicationFailurePoint(ignored -> { });
            s.engine.restartFromStores();
            var replay = s.adapter.executeAndPublish(s.batch, s.invocation);
            assertTrue(replay.replayed());
            assertNotNull(replay.rejectedBirth());
            assertFalse(replay.attempt().isComplete());
            assertEquals(before.documentHeads(), s.engine.documents().publicationSnapshot().documentHeads());
            assertEquals(before.closurePublicationReceipts(), s.engine.documents().publicationSnapshot().closurePublicationReceipts());
            assertTrue(s.blue.processing().drain(new DrainBudget(1, 1)).quiescent());
            System.out.println("PASS response loss after feeder decision survives restart without processor receipt or repeated work");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zeroMatches", "wrongPath"})
    void completedRejectionRequiresCapturedPlanAndForbidsSemanticStaging(String operation) throws Exception {
        // given
        try (var s = new Scenario(operation)) {
            var before = s.engine.documents().publicationSnapshot();
            // when
            var outcome = s.adapter.executeAndPublish(s.batch, s.invocation);
            var receipt = s.engine.documents().closurePublicationReceipt(outcome.publicationIdentity()).orElseThrow();
            var plan = s.invocation.managedDraftPlan();
            var copied = new ContractsManagedDraftPlan(plan.targetDocumentId(), plan.targetEpoch(), plan.targetBlueId(),
                    plan.drafts(), plan.managedRequestFields(), plan.expectedOccurrences());
            // then
            assertTrue(outcome.attempt().isComplete());
            assertTrue(outcome.attempt().processResult().commits());
            assertFalse(outcome.published());
            assertFalse(receipt.commits());
            assertSame(plan, receipt.rejectedDraftPlan());
            assertNotNull(receipt.rootedTerminalEvidence());
            assertThrows(IllegalArgumentException.class, () -> new ContractsClosurePublicationReceipt(
                    receipt.publicationIdentity(), receipt.documentIds(), receipt.attempt(), receipt.automaticRetryCount(),
                    receipt.managedSurfaceEvidence(), copied, receipt.rootedTerminalEvidence()));
            assertThrows(IllegalArgumentException.class, () -> receipt.rootedTerminalEvidence().requireRejectedDraftPlan(
                    plan, receipt.attempt().processResult(), "sha256:" + "0".repeat(64)));
            var state = s.engine.documents().publicationSnapshot();
            var transaction = s.engine.documents().beginAtomicPublication(receipt.publicationIdentity(),
                    state.occurrenceInventoryGeneration(), state.componentIndexGeneration());
            s.invocation.documents().forEach((id, captured) -> {
                transaction.expectHead(id, captured.head().epoch(), captured.head().blueId());
                transaction.expectGraphGeneration(id, captured.graphGeneration());
            });
            transaction.stageManagedExpansionInput(s.invocation.input());
            transaction.stageClosurePublicationReceipt(receipt);
            transaction.stageComponentStates(receipt.attempt().processResult().resultingComponents());
            assertThrows(IllegalStateException.class, transaction::commit);
            assertEquals(before.documentHeads(), s.engine.documents().publicationSnapshot().documentHeads());
            assertEquals(before.outbox(), s.engine.documents().publicationSnapshot().outbox());
        }
    }

    private static final class Scenario implements AutoCloseable {
        final BlueCoordination blue = BlueCoordination.inMemory();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        final ContractsClosureAdapter adapter = engine.contractsClosureAdapter();
        final TimelineHandle timeline;
        final DocumentHandle host;
        final ManagedDocumentDraft draft;
        final ExactBlueValue wrong;
        final ContractsClosureAdapter.FrozenBatch batch;
        final ContractsClosureAdapter.CohortInvocation invocation;
        Scenario() throws Exception { this("wrongExactState"); }

        Scenario(String operation) throws Exception {
            var hostId = DocumentId.of("rooted-rejected-birth-host");
            var childId = DocumentId.of("rooted-rejected-birth-child");
            String timelineId = "rooted/rejected-birth";
            String hostYaml = resource("host.yaml");
            String childYaml = resource("child.yaml");
            timeline = blue.timelines().register(timelineId, "alice");
            host = blue.documents().admit(ManagedDocument.yaml(hostId, hostYaml).publicRoot().fromNow());
            draft = blue.documents().draft(childId, blue.values().yaml(childYaml));
            wrong = blue.values().yaml(childYaml.replace("state: draft", "state: altered"));
            var entry = submit(operation);
            batch = adapter.captureRoot(hostId, engine.auditTimelineEntry(entry.blueId()).orElseThrow());
            invocation = batch.invocations().get(0);
        }
        EntryHandle submit(String operation) {
            return blue.operations().on(host).from(timeline).call(operation).through("ownerChannel")
                    .request(r -> { r.managed("order", draft); if (operation.equals("wrongExactState")) r.exact("wrong", wrong); })
                    .expectOccurrence("/orders/expected", draft).submit();
        }
        @Override public void close() { blue.close(); }
    }

    private static String resource(String name) throws java.io.IOException {
        try (var in = RootedDeclaredBirthRejectionTest.class.getResourceAsStream("/rooted-managed-rejections/" + name)) {
            if (in == null) throw new java.io.IOException("Missing rejection fixture " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
