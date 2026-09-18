package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.coordination.sdk.ActivationPolicy;
import blue.language.processor.closure.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static org.junit.jupiter.api.Assertions.*;

final class LogicalGraphIndexesTest {
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(1024 * 1024, 4096, 512 * 1024, 4096, 32);
    private static final Bytes EVIDENCE = new Bytes(new byte[] {5});
    private static final String POLICY = "sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35";
    private static final String BLUE = "8M3d43KXskYr7rrdiaXPiPHmypFEjtU4uUyECtU7Tiyx";

    @Test void distinctSourcesSharingOneTargetRetainEveryOccurrenceMembershipInEitherOrder() {
        // given
        for (boolean reverse : List.of(false, true)) {
            var store = new LogicalRecordMapTest.Store(); var bytes = new DocumentSessionStorageTest.Bytes();
            var indexes = new StoredOccurrenceIndexes(bytes, LIMITS); var left = row("a", "same-target"); var right = row("b", "same-target");
            var a = store.attempt(); var b = store.attempt(); var ca = new LogicalRecordContext(a); var cb = new LogicalRecordContext(b);
            // when
            indexes.selectLogical(indexes.openLogical(ca).replaceSources(List.of(DocumentId.of("a")), List.of(left)).inventory());
            indexes.selectLogical(indexes.openLogical(cb).replaceSources(List.of(DocumentId.of("b")), List.of(right)).inventory());
            ca.flush(); cb.flush(); var pa = a.prepare("a", List.of(), EVIDENCE); var pb = b.prepare("b", List.of(), EVIDENCE);
            // then
            assertTrue(store.publish(reverse ? pb : pa)); assertTrue(store.publish(reverse ? pa : pb));
            try (var cold = store.attempt()) {
                var restored = indexes.openLogical(new LogicalRecordContext(cold));
                var actual = indexes.touching(restored, DocumentId.of("same-target"));
                assertEquals(Set.of(left.occurrenceIdentity(), right.occurrenceIdentity()), actual.stream().map(ManagedOccurrenceBinding::occurrenceIdentity)
                        .collect(java.util.stream.Collectors.toSet()));
                assertEquals(left.bindingIdentity(), indexes.find(restored, DocumentId.of("a"), "/child").orElseThrow().bindingIdentity());
                assertEquals(right.bindingIdentity(), indexes.sourceRows(restored, DocumentId.of("b"), true).get(0).bindingIdentity());
            }
        }
    }

    @Test void realRootResultsPersistTopologyComponentProofsAndSubscriptionsIndependently() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var a = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var b = f.start(resource("source.yaml").replace("rcp2/source", "independent"), "independent", ActivationPolicy.fromNow());
            assertEquals(blue.coordination.sdk.ProcessingStageResult.Disposition.COMPLETED,
                    f.blue.processing().processStage(a, f.append(a, "rcp2/source", "tick")).disposition());
            assertEquals(blue.coordination.sdk.ProcessingStageResult.Disposition.COMPLETED,
                    f.blue.processing().processStage(b, f.append(b, "independent", "tick")).disposition());
            var resultA = f.engine.documents().require(a.id()).rootedView().result();
            var resultB = f.engine.documents().require(b.id()).rootedView().result();
            // when
            for (boolean reverse : List.of(false, true)) {
                var store = new LogicalRecordMapTest.Store();
                var pa = graph(store, f.bytes, resultA, "a"); var pb = graph(store, f.bytes, resultB, "b");
                assertTrue(store.publish(reverse ? pb : pa)); assertTrue(store.publish(reverse ? pa : pb));
                // then
                try (var attempt = store.attempt()) {
                    var context = new LogicalRecordContext(attempt); var bytes = f.bytes.copy();
                    var topology = new StoredTopologyIndexes(bytes, LIMITS); var graph = topology.openLogical(context);
                    var components = new StoredComponentStateIndexes(bytes, LIMITS, 256); var proofs = components.openLogical(context);
                    var subscriptions = new StoredSubscriptionIndexes(bytes, LIMITS); var states = subscriptions.openLogical(context);
                    for (var result : List.of(resultA, resultB)) for (var id : RootedResultScope.members(result)) {
                        assertEquals(List.of(id), topology.component(graph, id).orElseThrow().members());
                        var expected = RootedResultScope.components(result);
                        var proof = components.forDocument(proofs, id, expected).orElseThrow();
                        assertArrayEquals(components.exactRow(expected.get(0)), components.exactRow(proof));
                        var actual = subscriptions.statesFor(states, id);
                        var expectedStates = f.engine.documents().storedState().closureSubscriptions().statesFor(id);
                        assertEquals(expectedStates.size(), actual.size()); assertFalse(actual.isEmpty());
                        var codec = new StoreIndexCodecs(bytes, LIMITS).subscriptions;
                        for (int i = 0; i < actual.size(); i++) assertArrayEquals(codec.encode(expectedStates.get(i)), codec.encode(actual.get(i)));
                    }
                    assertTrue(topology.component(graph, DocumentId.of("absent")).isEmpty());
                }
            }
        }
    }

    private static Publication graph(LogicalRecordMapTest.Store store, DocumentSessionStorageTest.Bytes bytes,
            ClosureProcessResult result, String identity) {
        var attempt = store.attempt(); var context = new LogicalRecordContext(attempt);
        var topology = new StoredTopologyIndexes(bytes, LIMITS);
        topology.selectLogical(topology.openLogical(context).replaceOwnedResult(result));
        var components = new StoredComponentStateIndexes(bytes, LIMITS, 256);
        components.selectLogical(components.openLogical(context).replaceAffected(RootedResultScope.members(result), RootedResultScope.components(result)));
        var subscriptions = new StoredSubscriptionIndexes(bytes, LIMITS);
        var owners = new LinkedHashMap<DocumentId, Long>();
        RootedResultScope.members(result).forEach(id -> owners.put(id, result.platformCommitCompanion().expectedInputGraphGeneration()));
        subscriptions.selectLogical(subscriptions.openLogical(context).applyOwned(result, owners));
        context.flush(); return attempt.prepare(identity, List.of(), EVIDENCE);
    }

    private static ManagedOccurrenceBinding row(String source, String target) {
        return ManagedOccurrenceBinding.derived(POLICY, new blue.language.processor.closure.DocumentId(source), ScopeAddress.embedded("/child", 1),
                new blue.language.processor.closure.DocumentId(target), BLUE, true, null);
    }
}
