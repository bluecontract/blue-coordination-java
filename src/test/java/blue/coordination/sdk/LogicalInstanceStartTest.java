package blue.coordination.sdk;

import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.DocumentInstanceStart.Status;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real archived starting basis followed by fresh cold execution selection. */
final class LogicalInstanceStartTest {
    @Test void explicitStartingBasisRestoresOnlyTheNewExecutionAndRetainsTheOriginalHistory() {
        // given
        var account = new LogicalInstanceHistoryTest.Account();
        var basis = admit(account);
        var advanced = account.transact(scope -> {
            var root = scope.documentHandle(basis.instance().documentId()).orElseThrow();
            var entry = account.append(scope, root, "rcp2/source", "owner", "tick", "{}");
            assertTrue(scope.coordination().processing().processNextStage(root).entry(entry).applied());
            return scope.instancePosition(root.id());
        });
        // when
        account.transact(scope -> scope.retireInstance(basis.instance()));
        var next = new DocumentInstanceRef(basis.instance().documentId(), "second-execution");
        assertEquals(Status.PREPARED, account.transact(scope -> scope.startInstance(next, basis)).status());
        // then
        account.transact(scope -> {
            assertEquals(0, scope.documentHandle(next.documentId()).orElseThrow().snapshot().longAt("/counter"));
            assertEquals(next, scope.instancePosition(next.documentId()).instance());
            assertEquals(basis.invocationIdentity(), scope.instancePosition(next.documentId()).invocationIdentity());
            assertEquals(1, ((Number) scope.retainedRevision(advanced).after().scalarAt("/counter")).intValue());
            return null;
        });
    }

    @Test void oldAcceptedEntryRemainsEligibleForTheNewDeclaredHistoryReplay() {
        // given
        var account = new LogicalInstanceHistoryTest.Account();
        var basis = admit(account);
        String entryId = account.transact(scope -> {
            var root = scope.documentHandle(basis.instance().documentId()).orElseThrow();
            var entry = account.append(scope, root, "rcp2/source", "owner", "tick", "{}");
            assertTrue(scope.coordination().processing().processNextStage(root).entry(entry).applied());
            return entry.blueId();
        });
        // when
        account.transact(scope -> scope.retireInstance(basis.instance()));
        var next = new DocumentInstanceRef(basis.instance().documentId(), "replay-execution");
        assertEquals(Status.PREPARED, account.transact(scope -> scope.startInstance(next, basis)).status());
        var canonicalBefore = canonicalReceipts(account);
        account.transact(scope -> {
            var root = scope.documentHandle(next.documentId()).orElseThrow();
            var stage = scope.coordination().processing().processNextStage(root);
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, stage.disposition(), "A1 completion cannot suppress A2's eligible old accepted input");
            assertEquals(List.of(entryId), stage.entries().stream().map(row -> row.entry().blueId()).toList());
            assertEquals(1, root.snapshot().longAt("/counter"));
            return null;
        });
        // then
        assertEquals(canonicalBefore, canonicalReceipts(account), "Reexecution must retain the original canonical receipt rows byte-for-byte");
        account.transact(scope -> {
            var root = scope.documentHandle(next.documentId()).orElseThrow();
            assertEquals(1, root.snapshot().longAt("/counter"));
            assertTrue(scope.coordination().processing().recordedResult(basis.instance(), entryId).orElseThrow().applied());
            assertTrue(scope.coordination().processing().recordedResult(next, entryId).orElseThrow().applied());
            assertTrue(scope.coordination().processing().originalResult(entryId).orElseThrow().applied());
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, scope.coordination().processing().processNextStage(root).disposition());
            return null;
        });
    }

    @Test void originalAdmissionAndClosureOutcomesRemainOriginalAfterReplacementAdvances() {
        // given
        var account = new LogicalInstanceHistoryTest.Account(); var basis = admit(account);
        var publication = account.transact(scope -> {
            var root = scope.documentHandle(basis.instance().documentId()).orElseThrow();
            var entry = account.append(scope, root, "rcp2/source", "owner", "tick", "{}");
            var result = scope.coordination().processing().processNextStage(root).entry(entry);
            assertTrue(result.applied()); return result.closures().get(0).closureId();
        });
        var originalResult = account.transact(scope -> scope.coordination().advanced().closureExecution(publication).orElseThrow().outputClosureIdentity());
        account.transact(scope -> {
            var replay = scope.coordination().documents().admitStaticProcessEmbedded(resource(), ActivationPolicy.importFullHistory()).document("root");
            assertEquals(1, replay.snapshot().longAt("/counter"), "Ordinary replay keeps the original active instance's live state");
            return null;
        });
        // when
        account.transact(scope -> scope.retireInstance(basis.instance()));
        account.transact(scope -> scope.startInstance(new DocumentInstanceRef(basis.instance().documentId(), "original-command-control"), basis));
        account.transact(scope -> {
            var root = scope.documentHandle(basis.instance().documentId()).orElseThrow();
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, scope.coordination().processing().processNextStage(root).disposition());
            account.append(scope, root, "rcp2/source", "owner", "tick", "{}");
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, scope.coordination().processing().processNextStage(root).disposition());
            assertEquals(2, root.snapshot().longAt("/counter")); return null;
        });
        // then
        account.transact(scope -> {
            var original = scope.coordination().documents().admitStaticProcessEmbedded(resource(), ActivationPolicy.importFullHistory());
            var handle = original.document("root");
            assertEquals(0, handle.snapshot().longAt("/counter"), "Retired original admission returns its archived outcome, never A2");
            assertEquals(1, handle.history().size());
            assertThrows(IllegalArgumentException.class, () -> scope.coordination().processing().processNextStage(handle));
            assertEquals(2, scope.documentHandle(basis.instance().documentId()).orElseThrow().snapshot().longAt("/counter"));
            assertEquals(originalResult, scope.coordination().advanced().closureExecution(publication).orElseThrow().outputClosureIdentity());
            return null;
        });
    }

    @Test void originalZeroAttemptDiagnosticDoesNotReauditTheReplacementHead() {
        // given
        var account = new LogicalInstanceHistoryTest.Account(); var basis = admit(account);
        String rejected = account.transact(scope -> {
            var root = scope.documentHandle(basis.instance().documentId()).orElseThrow();
            var entry = scope.coordination().operations().on(root).from(scope.timelineHandle("rcp2/source").orElseThrow())
                    .call("missing-operation").through("owner").requestYaml("{}").submit();
            var result = scope.coordination().processing().processStage(root, entry).entry(entry);
            assertEquals("OPERATION_NOT_FOUND", result.diagnostic().code());
            return entry.blueId();
        });
        // when
        account.transact(scope -> scope.retireInstance(basis.instance()));
        var next = new DocumentInstanceRef(basis.instance().documentId(), "diagnostic-replacement");
        account.transact(scope -> scope.startInstance(next, basis));
        account.transact(scope -> {
            var root = scope.documentHandle(next.documentId()).orElseThrow();
            // Exact accepted entries are reused; original command ownership does not filter A2 eligibility.
            scope.coordination().processing().selectStage(root, rejected).execute();
            var tick = scope.coordination().operations().on(root).from(scope.timelineHandle("rcp2/source").orElseThrow())
                    .call("tick").through("owner").requestYaml("{}").submit();
            assertTrue(scope.coordination().processing().processStage(root, tick).entry(tick).applied());
            assertEquals(1, root.snapshot().longAt("/counter")); return null;
        });
        // then
        account.transact(scope -> {
            assertEquals("OPERATION_NOT_FOUND", scope.coordination().processing().originalResult(rejected).orElseThrow().diagnostic().code());
            assertEquals("OPERATION_NOT_FOUND", scope.coordination().processing().recordedResult(basis.instance(), rejected).orElseThrow().diagnostic().code());
            return null;
        });
    }

    @Test void missingOriginalInstanceMetadataPoisonsRecordedResultRead() {
        // given
        var account = new LogicalInstanceHistoryTest.Account(); var basis = admit(account);
        String entry = account.transact(scope -> {
            var root = scope.documentHandle(basis.instance().documentId()).orElseThrow();
            var accepted = account.append(scope, root, "rcp2/source", "owner", "tick", "{}");
            scope.coordination().processing().processNextStage(root); return accepted.blueId();
        });
        // when
        account.records.data.replaceAll((key, value) -> key.family() == blue.coordination.api.storage.CoordinationRecords.Family.INSTANCE_IDENTITY
                ? new blue.coordination.api.storage.CoordinationRecords.Value(value.revision() + 1, null) : value);
        // then
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertThrows(RuntimeException.class, () -> scope.coordination().processing().originalResult(entry));
            assertThrows(RuntimeException.class, scope::stage);
            assertThrows(RuntimeException.class, () -> attempt.prepare("poisoned-original-result", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE));
        }
    }

    @Test void abandonedAndCompetingStartsDoNotReuseAuthority() {
        // given
        var account = new LogicalInstanceHistoryTest.Account(); var basis = admit(account);
        account.transact(scope -> scope.retireInstance(basis.instance()));
        var next = new DocumentInstanceRef(basis.instance().documentId(), "prepared-start");
        // when
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(Status.PREPARED, scope.startInstance(next, basis).status()); // Discard before stage/publication.
        }
        // then
        account.transact(scope -> { assertTrue(scope.documentHandle(next.documentId()).isEmpty()); return null; });
        try (var first = account.records.attempt(); var firstScope = account.open(first);
                var second = account.records.attempt(); var secondScope = account.open(second)) {
            assertEquals(Status.PREPARED, firstScope.startInstance(next, basis).status());
            assertEquals(Status.PREPARED, secondScope.startInstance(new DocumentInstanceRef(next.documentId(), "competing-start"), basis).status());
            firstScope.stage(); secondScope.stage();
            assertTrue(account.records.publish(first.prepare("winning-start", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE)));
            assertFalse(account.records.publish(second.prepare("losing-start", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE)));
        }
        account.transact(scope -> scope.retireInstance(next));
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertThrows(RuntimeException.class, () -> scope.startInstance(next, basis));
            assertThrows(RuntimeException.class, scope::stage);
        }
    }

    private static Map<blue.coordination.api.storage.CoordinationRecords.Key, blue.coordination.api.storage.CoordinationRecords.Value> canonicalReceipts(LogicalInstanceHistoryTest.Account account) {
        var result = new TreeMap<blue.coordination.api.storage.CoordinationRecords.Key, blue.coordination.api.storage.CoordinationRecords.Value>();
        account.records.data.forEach((key, value) -> {
            if ((key.family() == blue.coordination.api.storage.CoordinationRecords.Family.CLOSURE
                    || key.family() == blue.coordination.api.storage.CoordinationRecords.Family.ADMISSION)
                    && new String(key.scope().copy(), java.nio.charset.StandardCharsets.UTF_8).contains("runtime/1")) result.put(key, value);
        });
        assertFalse(result.isEmpty()); return result;
    }

    private static blue.coordination.api.DocumentInstancePosition admit(LogicalInstanceHistoryTest.Account account) {
        return account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            var root = scope.coordination().documents().admitStaticProcessEmbedded(resource(), ActivationPolicy.importFullHistory()).document("root");
            return scope.instancePosition(root.id());
        });
    }
    private static String resource() {
        try { return RootedSdkFixture.resource("source.yaml"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
