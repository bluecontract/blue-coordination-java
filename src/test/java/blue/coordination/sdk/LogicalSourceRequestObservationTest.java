package blue.coordination.sdk;

import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.SourceHistoryRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native context propagation preserves descriptor-only lookup and refreshed observation semantics. */
final class LogicalSourceRequestObservationTest {
    @Test void unknownOrMismatchedDescriptorResultIsEmptyWithoutPoisoningKnownLookup() throws Exception {
        // given
        var fixture = fixture(); var original = fixture.request().prerequisite();
        // when
        fixture.account().transact(scope -> {
            var advanced = scope.coordination().advanced();
            advanced.processSourceHistoryPrerequisite(fixture.request());
            // then
            assertTrue(advanced.sourceHistoryProcessingResult(copy(original, "sha256:" + "0".repeat(64), original.journalRevision())).isEmpty());
            assertTrue(advanced.sourceHistoryProcessingResult(copy(original, original.selectionIdentity(), original.journalRevision() + 1)).isEmpty());
            assertTrue(advanced.sourceHistoryProcessingResult(original).orElseThrow().entries().get(0).applied());
            return null;
        });
    }
    @Test void refreshedObservationRegistersExactNextDescriptorForOriginalReconciliation() throws Exception {
        // given
        var fixture = fixture();
        fixture.account().transact(scope -> { scope.coordination().advanced().processSourceHistoryPrerequisite(fixture.request()); return null; });
        // when
        var next = fixture.account().transact(scope -> {
            var observed = scope.coordination().advanced().observeSourceHistoryPrerequisite(fixture.request());
            assertEquals(blue.coordination.api.SourceHistoryPrerequisiteObservation.Status.PENDING, observed.status());
            return observed.pending().orElseThrow();
        });
        // then
        assertNotEquals(fixture.request().prerequisite(), next);
        fixture.account().transact(scope -> {
            var advanced = scope.coordination().advanced();
            assertEquals(1, advanced.processSourceHistoryPrerequisite(next).processing().orElseThrow().committedProcessTransitions());
            var request = new SourceHistoryRequest(next, fixture.request().requestingInstances(), fixture.request().sourceInstance());
            assertTrue(advanced.sourceHistoryProcessingResult(request).orElseThrow().entries().get(0).applied());
            return null;
        });
    }
    private static SourceHistoryPrerequisite copy(SourceHistoryPrerequisite original, String identity, long journal) {
        return new SourceHistoryPrerequisite(identity, original.requestingRoot(), original.requestingInvocationIdentity(), original.demandIdentity(),
                original.sourceDocumentId(), original.authoredBlueId(), original.cutoffExclusive(), original.kind(), original.sourceEpoch(),
                original.sourceBlueId(), original.workIdentity(), original.entryBlueId(), journal, original.routeGeneration(),
                original.sourceSurfaceIdentity(), original.diagnostic());
    }
    private static Fixture fixture() throws Exception {
        var account = new LogicalInstanceHistoryTest.Account();
        String source = RootedSdkFixture.resource("source.yaml"), parent = RootedSdkFixture.resource("parent.yaml");
        var id = account.transact(scope -> {
            var blue = scope.coordination(); blue.timelines().register("rcp2/source", "alice"); blue.timelines().register("rcp2/parent", "alice");
            var value = blue.values().yaml(source); account.exact.put(value.blueId(), value.json());
            var a = blue.documents().admitStaticProcessEmbedded(source, ActivationPolicy.importFullHistory()).document("root");
            var b = blue.documents().admitStaticProcessEmbedded(parent, ActivationPolicy.importFullHistory()).document("root");
            account.append(scope, a, "rcp2/source", "owner", "tick", "{}");
            account.append(scope, a, "rcp2/source", "owner", "tick", "{}");
            account.append(scope, b, "rcp2/parent", "owner", "attach", "child: {blueId: " + value.blueId() + "}");
            return b.id();
        });
        var request = account.transact(scope -> {
            var b = scope.documentHandle(id).orElseThrow();
            assertEquals(ProcessingStageResult.Disposition.WAITING, scope.coordination().processing().processNextStage(b).disposition());
            return scope.coordination().advanced().sourceHistoryRequests(b).get(0);
        });
        return new Fixture(account, request);
    }
    private record Fixture(LogicalInstanceHistoryTest.Account account, SourceHistoryRequest request) { }
}
