package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source-owned processing retains its original execution observer independently of the requester. */
final class LogicalSourceInstanceReplayTest {
    @Test void sameSourceRequestWaitAdvancesToCommittedColdOutcome() throws Exception {
        // given
        ExactBlueValue missing;
        try (var preparation = BlueCoordination.inMemory()) { missing = preparation.values().yaml("7"); }
        var account = new LogicalInstanceHistoryTest.Account();
        String source = RootedSdkFixture.resource("source.yaml").replace("counter: 0", "counter: {blueId: " + missing.blueId() + "}");
        String parent = RootedSdkFixture.resource("parent.yaml");
        var ids = account.transact(scope -> {
            var blue = scope.coordination(); blue.timelines().register("rcp2/source", "alice"); blue.timelines().register("rcp2/parent", "alice");
            var value = blue.values().yaml(source); account.exact.put(value.blueId(), value.json());
            var a = blue.documents().admitStaticProcessEmbedded(source, ActivationPolicy.importFullHistory()).document("root");
            var b = blue.documents().admitStaticProcessEmbedded(parent, ActivationPolicy.importFullHistory()).document("root");
            account.append(scope, a, "rcp2/source", "owner", "tick", "{}");
            account.append(scope, b, "rcp2/parent", "owner", "attach", "child: {blueId: " + value.blueId() + "}");
            return java.util.List.of(a.id(), b.id());
        });
        var request = account.transact(scope -> {
            var b = scope.documentHandle(ids.get(1)).orElseThrow();
            assertEquals(ProcessingStageResult.Disposition.WAITING, scope.coordination().processing().processNextStage(b).disposition());
            var requests = scope.coordination().advanced().sourceHistoryRequests(b);
            assertEquals(1, requests.size()); return requests.get(0);
        });
        assertEquals(blue.coordination.api.SourceHistoryPrerequisite.Kind.LIVE, request.prerequisite().kind());
        account.transact(scope -> {
            var advanced = scope.coordination().advanced();
            var waiting = advanced.selectSourceHistoryStage(request).execute();
            assertTrue(waiting.committable()); assertEquals(0, waiting.result().processing().orElseThrow().committedProcessTransitions());
            var recorded = advanced.sourceHistoryProcessingResult(request).orElseThrow();
            assertEquals(EntryDisposition.NEEDS_RESOURCES, recorded.entries().get(0).disposition());
            assertTrue(recorded.entries().get(0).closures().stream().flatMap(c -> c.resourceDemands().stream())
                    .anyMatch(demand -> missing.blueId().equals(demand.blueId())));
            assertEquals(0, scope.documentHandle(ids.get(0)).orElseThrow().snapshot().epoch());
            return null;
        });
        // when
        account.exact.put(missing.blueId(), missing.json());
        account.transact(scope -> {
            var advanced = scope.coordination().advanced();
            assertEquals(request, advanced.sourceHistoryRequests(scope.documentHandle(ids.get(1)).orElseThrow()).get(0),
                    "Provider arrival must resume the identical authenticated descriptor/context");
            assertEquals(1, advanced.selectSourceHistoryStage(request).execute().result().processing().orElseThrow().committedProcessTransitions());
            assertEquals(1, advanced.sourceHistoryProcessingResult(request).orElseThrow().stats().committedTransitions(),
                    "A committed source outcome must replace its earlier ordinary resource wait");
            return null;
        });
        // then
        account.transact(scope -> {
            var advanced = scope.coordination().advanced();
            var recorded = advanced.sourceHistoryProcessingResult(request).orElseThrow();
            assertTrue(recorded.entries().get(0).applied());
            assertEquals(1, recorded.stats().committedTransitions());
            assertTrue(advanced.processSourceHistoryPrerequisite(request).replayed());
            assertEquals(recorded, advanced.sourceHistoryProcessingResult(request).orElseThrow());
            assertTrue(scope.coordination().processing().recordedResult(request.sourceInstance(), request.prerequisite().entryBlueId()).orElseThrow().applied());
            assertEquals(8, scope.documentHandle(ids.get(0)).orElseThrow().snapshot().longAt("/counter"));
            assertEquals(0, scope.documentHandle(ids.get(1)).orElseThrow().snapshot().epoch());
            return null;
        });
    }

    @Test void sourcePrerequisiteOutcomeIsRecordedForItsActualSourceInstance() throws Exception {
        // given
        var account = new LogicalInstanceHistoryTest.Account();
        String source = RootedSdkFixture.resource("source.yaml"), parent = RootedSdkFixture.resource("parent.yaml");
        var positions = account.transact(scope -> {
            var blue = scope.coordination();
            blue.timelines().register("rcp2/source", "alice"); blue.timelines().register("rcp2/parent", "alice");
            var value = blue.values().yaml(source); account.exact.put(value.blueId(), value.json());
            var a = blue.documents().admitStaticProcessEmbedded(source, ActivationPolicy.importFullHistory()).document("root");
            var b = blue.documents().admitStaticProcessEmbedded(parent, ActivationPolicy.importFullHistory()).document("root");
            account.append(scope, a, "rcp2/source", "owner", "tick", "{}");
            account.append(scope, b, "rcp2/parent", "owner", "attach", "child: {blueId: " + value.blueId() + "}");
            return java.util.List.of(scope.instancePosition(a.id()), scope.instancePosition(b.id()));
        });
        var request = account.transact(scope -> {
            var b = scope.documentHandle(positions.get(1).instance().documentId()).orElseThrow();
            assertEquals(ProcessingStageResult.Disposition.WAITING, scope.coordination().processing().processNextStage(b).disposition());
            var selected = scope.coordination().advanced().sourceHistoryPrerequisites(b);
            assertEquals(1, selected.size());
            assertEquals(blue.coordination.api.SourceHistoryPrerequisite.Kind.LIVE, selected.get(0).kind());
            var requests = scope.coordination().advanced().sourceHistoryRequests(b);
            assertEquals(selected.get(0), requests.get(0).prerequisite());
            return requests.get(0);
        });
        // when
        account.transact(scope -> {
            var advanced = scope.coordination().advanced();
            assertEquals(1, advanced.processSourceHistoryPrerequisite(request).processing().orElseThrow().committedProcessTransitions());
            assertTrue(advanced.sourceHistoryProcessingResult(request).orElseThrow().entries().get(0).applied());
            return null;
        });
        // then
        account.transact(scope -> {
            assertTrue(scope.coordination().processing().recordedResult(positions.get(0).instance(), request.prerequisite().entryBlueId())
                    .orElseThrow(() -> new AssertionError("Source execution lacks its original instance result")).applied());
            assertTrue(scope.coordination().processing().recordedResult(positions.get(1).instance(), request.prerequisite().entryBlueId()).isEmpty(),
                    "Waiting requester does not own the source execution outcome");
            return null;
        });
        account.transact(scope -> {
            var b = scope.documentHandle(positions.get(1).instance().documentId()).orElseThrow();
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, scope.coordination().processing().processNextStage(b).disposition());
            assertTrue(scope.coordination().advanced().sourceHistoryRequests(b).isEmpty());
            return null;
        });
        assertEquals(blue.coordination.api.DocumentInstanceRetirement.Status.PREPARED,
                account.transact(scope -> scope.retireInstance(positions.get(0).instance())).status());
        var replacement = new blue.coordination.api.DocumentInstanceRef(positions.get(0).instance().documentId(), "new-source-observer");
        account.transact(scope -> scope.startInstance(replacement, positions.get(0)));
        account.transact(scope -> {
            var a = scope.documentHandle(replacement.documentId()).orElseThrow();
            assertEquals(0, a.snapshot().longAt("/counter"));
            assertTrue(scope.coordination().processing().processNextStage(a).entries().get(0).applied());
            return null;
        });
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            var advanced = scope.coordination().advanced();
            assertTrue(advanced.processSourceHistoryPrerequisite(request.prerequisite()).replayed());
            assertTrue(advanced.selectSourceHistoryStage(request).execute().result().replayed());
            assertTrue(advanced.sourceHistoryProcessingResult(request).orElseThrow().entries().get(0).applied());
            assertTrue(scope.coordination().processing().recordedResult(request.sourceInstance(), request.prerequisite().entryBlueId()).isPresent());
            scope.stage(); var packet = attempt.prepare("source-original-reconciliation", java.util.List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
            assertTrue(packet.points().stream().noneMatch(point ->
                    (point.key().family() == blue.coordination.api.storage.CoordinationRecords.Family.INSTANCE_BINDING
                            || point.key().family() == blue.coordination.api.storage.CoordinationRecords.Family.SESSION)
                            && new String(point.key().key().copy(), java.nio.charset.StandardCharsets.UTF_8).equals(replacement.documentId().value())),
                    "Original source reconciliation must not select replacement authority");
        }
        var forged = new blue.coordination.api.SourceHistoryRequest(request.prerequisite(), request.requestingInstances(), replacement);
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertThrows(RuntimeException.class, () -> scope.coordination().advanced().processSourceHistoryPrerequisite(forged));
            assertThrows(RuntimeException.class, scope::stage);
            assertThrows(IllegalStateException.class, () -> attempt.prepare("forged-source-context", java.util.List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE));
        }
    }
}
