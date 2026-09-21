package blue.coordination.sdk;

import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecords.Publication;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Aggregate legacy execution is explicitly bounded; rooted owners do not inherit an account generation. */
final class LogicalAggregateLifecycleGuardTest {
    @Test void preparedAggregateAbsenceConflictsWithConcurrentRetirementButRootedBRemainsIndependent() throws Exception {
        // given
        var account = new LogicalInstanceHistoryTest.Account();
        String source = RootedSdkFixture.resource("source.yaml");
        var positions = account.transact(scope -> {
            var blue = scope.coordination(); blue.timelines().register("rcp2/source", "alice");
            var a = blue.documents().admitStaticProcessEmbedded(source, ActivationPolicy.importFullHistory()).document("root");
            var b = blue.documents().admitStaticProcessEmbedded(source.replace("RCP2 Source", "Aggregate Independent B"),
                    ActivationPolicy.importFullHistory()).document("root");
            return List.of(scope.instancePosition(a.id()), scope.instancePosition(b.id()));
        });
        Publication aggregate, independent;
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertTrue(scope.coordination().processing().drain().quiescent());
            scope.stage(); aggregate = attempt.prepare("aggregate-before-retirement", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
        }
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            scope.coordination().processing().processNextStage(scope.documentHandle(positions.get(1).instance().documentId()).orElseThrow());
            scope.stage(); independent = attempt.prepare("rooted-b-before-retirement", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
        }
        // when
        account.transact(scope -> scope.retireInstance(positions.get(0).instance()));
        // then
        assertFalse(account.records.publish(aggregate), "Aggregate retirement-absence predicate must detect the new marker");
        assertTrue(account.records.publish(independent), "Rooted B cannot inherit the aggregate lifecycle predicate");
        var replacement = new DocumentInstanceRef(positions.get(0).instance().documentId(), "aggregate-replacement");
        account.transact(scope -> scope.startInstance(replacement, positions.get(0)));
        var before = java.util.Map.copyOf(account.records.data);
        for (int operation = 0; operation < 3; operation++) {
            int selected = operation;
            try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
                var blue = scope.coordination();
                var failure = assertThrows(RuntimeException.class, () -> {
                    if (selected == 0) blue.processing().drain();
                    else if (selected == 1) blue.processing().drainJournal(DrainBudget.unlimited());
                    else blue.operations().on(scope.documentHandle(replacement.documentId()).orElseThrow())
                            .from(scope.timelineHandle("rcp2/source").orElseThrow()).call("tick").through("owner").requestYaml("{}").execute();
                });
                assertTrue(failure.getMessage().contains("INSTANCE_SCOPED_PROCESSING_REQUIRED"), failure.toString());
                assertThrows(RuntimeException.class, scope::stage);
                assertThrows(IllegalStateException.class, () -> attempt.prepare("unsupported-aggregate", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE));
            }
            assertEquals(before, account.records.data, "Rejected aggregate operation cannot publish an append or processing mutation");
        }
        account.transact(scope -> {
            assertEquals(0, ((Number) scope.retainedRevision(positions.get(0)).after().scalarAt("/counter")).intValue());
            var a = scope.documentHandle(replacement.documentId()).orElseThrow();
            var entry = account.append(scope, a, "rcp2/source", "owner", "tick", "{}");
            assertTrue(scope.coordination().processing().processStage(a, entry).entry(entry).applied());
            assertEquals(1, a.snapshot().longAt("/counter"));
            return null;
        });
    }
}
