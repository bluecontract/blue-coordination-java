package blue.coordination.sdk;

import blue.coordination.api.DocumentInstanceRetirement.Status;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public lifecycle preparation, exact host packets and fresh-runtime history reads. */
final class LogicalInstanceRetirementTest {
    @Test void independentRetirementFencesOldWorkAndPreservesOtherOwnersAndHistory() throws Exception {
        // given
        var account = new LogicalInstanceHistoryTest.Account();
        var ids = account.transact(scope -> {
            var blue = scope.coordination(); blue.timelines().register("rcp2/source", "alice");
            var yaml = resource();
            var a = blue.documents().admitStaticProcessEmbedded(yaml, ActivationPolicy.importFullHistory()).document("root");
            var b = blue.documents().admitStaticProcessEmbedded(yaml.replace("RCP2 Source", "Independent B"),
                    ActivationPolicy.importFullHistory()).document("root");
            return List.of(a.id(), b.id());
        });
        var positions = account.transact(scope -> ids.stream().map(scope::instancePosition).toList());
        Publication oldA, independentB;
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(positions.get(0), scope.instancePosition(ids.get(0))); scope.stage();
            oldA = attempt.prepare("old-a", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
        }
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(positions.get(1), scope.instancePosition(ids.get(1))); scope.stage();
            independentB = attempt.prepare("independent-b", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
        }
        // when
        var decision = account.transact(scope -> scope.retireInstance(positions.get(0).instance()));
        // then
        assertEquals(Status.PREPARED, decision.status());
        assertEquals(positions.get(0), decision.retainedPosition());
        assertFalse(account.records.publish(oldA), "A1's previously prepared work cannot survive retirement");
        assertTrue(account.records.publish(independentB), "B must not acquire A's lifecycle condition");
        account.transact(scope -> {
            assertTrue(scope.documentHandle(ids.get(0)).isEmpty());
            assertEquals(positions.get(1), scope.instancePosition(ids.get(1)));
            assertEquals(0, ((Number) scope.retainedRevision(positions.get(0)).after().scalarAt("/counter")).intValue());
            assertEquals(Set.of(ids.get(1)), Set.copyOf(scope.documentIds()));
            return null;
        });
    }

    @Test void newLiveDependencyInvalidatesAnAlreadyPreparedRetirement() throws Exception {
        // given
        var account = new LogicalInstanceHistoryTest.Account();
        var position = account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            var root = scope.coordination().documents().admitStaticProcessEmbedded(resource(), ActivationPolicy.importFullHistory()).document("root");
            account.exact.put(root.snapshot().blueId(), root.snapshot().exact().json());
            return scope.instancePosition(root.id());
        });
        Publication retirement;
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(Status.PREPARED, scope.retireInstance(position.instance()).status()); scope.stage();
            retirement = attempt.prepare("before-new-dependent", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
        }
        String parent = RootedSdkFixture.resource("parent.yaml");
        // when
        var dependent = account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/parent", "alice");
            var root = scope.documentHandle(position.instance().documentId()).orElseThrow();
            return scope.coordination().documents().admitStaticProcessEmbedded(parent + "\nchild: {blueId: "
                    + root.snapshot().blueId() + "}\n", ActivationPolicy.importFullHistory()).document("root").id();
        });
        // then
        assertFalse(account.records.publish(retirement), "A new incoming dependency must conflict with the selected absence predicate");
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            var blocked = scope.retireInstance(position.instance());
            assertEquals(Status.BLOCKED_POLICY_REQUIRED, blocked.status());
            assertTrue(blocked.blockers().stream().anyMatch(reason -> reason.contains(dependent.value())), blocked.blockers().toString());
            scope.stage();
            assertTrue(attempt.prepare("blocked-dependent", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE).mutations().isEmpty());
        }
    }

    @Test void stoppedSourceRequestBlocksItsSourceAndConflictsWithPriorRetirementSelection() throws Exception {
        // given
        var account = new LogicalInstanceHistoryTest.Account();
        String parent = RootedSdkFixture.resource("parent.yaml");
        var ids = account.transact(scope -> {
            var blue = scope.coordination(); blue.timelines().register("rcp2/source", "alice"); blue.timelines().register("rcp2/parent", "alice");
            var value = blue.values().yaml(resource()); account.exact.put(value.blueId(), value.json());
            var a = blue.documents().admitStaticProcessEmbedded(resource(), ActivationPolicy.importFullHistory()).document("root");
            var b = blue.documents().admitStaticProcessEmbedded(parent, ActivationPolicy.importFullHistory()).document("root");
            var c = blue.documents().admitStaticProcessEmbedded(resource().replace("RCP2 Source", "Independent C"),
                    ActivationPolicy.importFullHistory()).document("root");
            account.append(scope, a, "rcp2/source", "owner", "tick", "{}");
            account.append(scope, b, "rcp2/parent", "owner", "attach", "child: {blueId: " + value.blueId() + "}");
            return List.of(a.id(), b.id(), c.id());
        });
        var positions = account.transact(scope -> ids.stream().map(scope::instancePosition).toList());
        Publication priorA, unrelatedC;
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(Status.PREPARED, scope.retireInstance(positions.get(0).instance()).status()); scope.stage();
            priorA = attempt.prepare("before-source-request", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
        }
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(Status.PREPARED, scope.retireInstance(positions.get(2).instance()).status()); scope.stage();
            unrelatedC = attempt.prepare("unrelated-source-request", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
        }
        // when
        account.transact(scope -> {
            var b = scope.documentHandle(ids.get(1)).orElseThrow();
            var stopped = scope.coordination().processing().processNextStage(b);
            assertEquals(ProcessingStageResult.Disposition.WAITING, stopped.disposition(), stopped.diagnostic().toString());
            var requests = scope.coordination().advanced().sourceHistoryPrerequisites(b);
            assertEquals(1, requests.size());
            assertEquals(ids.get(0), requests.get(0).sourceDocumentId());
            assertEquals(blue.coordination.api.SourceHistoryPrerequisite.Kind.LIVE, requests.get(0).kind());
            assertEquals(0, b.snapshot().epoch(), "No active dependency edge has been published");
            return null;
        });
        // then
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            var blocked = scope.retireInstance(positions.get(0).instance());
            assertEquals(Status.BLOCKED_POLICY_REQUIRED, blocked.status(), "An unresolved B request requires A's live authority");
            assertTrue(blocked.blockers().stream().anyMatch(reason -> reason.startsWith("PENDING_SOURCE_WORK")));
            scope.stage();
            assertTrue(attempt.prepare("blocked-source-request", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE).mutations().isEmpty());
        }
        assertFalse(account.records.publish(priorA), "New source-owned request must invalidate selected source-work absence");
        assertTrue(account.records.publish(unrelatedC), "B's request for A must not conflict with independent C retirement");
    }

    @Test void unpublishedRetirementAndMixedOwnerFailureLeaveActiveAuthorityIntact() throws Exception {
        // given
        var account = new LogicalInstanceHistoryTest.Account();
        var position = account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            var id = scope.coordination().documents().admitStaticProcessEmbedded(resource(),
                    ActivationPolicy.importFullHistory()).document("root").id();
            return scope.instancePosition(id);
        });
        // when
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(Status.PREPARED, scope.retireInstance(position.instance()).status()); scope.stage();
            // The host abandons this complete prepared packet.
        }
        // then
        assertEquals(position, account.transact(scope -> scope.instancePosition(position.instance().documentId())));
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            scope.documentHandle(position.instance().documentId()).orElseThrow();
            assertThrows(IllegalStateException.class, () -> scope.retireInstance(position.instance()));
            assertThrows(RuntimeException.class, scope::stage);
            assertThrows(IllegalStateException.class, () -> attempt.prepare("mixed", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE));
        }
        assertEquals(position, account.transact(scope -> scope.instancePosition(position.instance().documentId())));
    }
    private static String resource() {
        try { return RootedSdkFixture.resource("source.yaml"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
