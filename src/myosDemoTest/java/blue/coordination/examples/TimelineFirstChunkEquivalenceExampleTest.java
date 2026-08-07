package blue.coordination.examples;

import blue.coordination.examples.documents.NestedTopologyDocuments;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;
import blue.coordination.examples.support.MyOsManagedEmbedding;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Chunk boundaries must not affect observable Coordination semantics. */
final class TimelineFirstChunkEquivalenceExampleTest {

    @Test
    void shouldProduceIdenticalResultsAtChunkSizesOneTwoAndOneTwentyEight() {
        // given
        List<Integer> chunkSizes = List.of(1, 2, 128);

        // when
        List<Outcome> outcomes = chunkSizes.stream()
                .map(TimelineFirstChunkEquivalenceExampleTest::run)
                .toList();

        // then
        assertEquals(outcomes.get(0).semantic(), outcomes.get(1).semantic());
        assertEquals(outcomes.get(0).semantic(), outcomes.get(2).semantic());
        assertEquals(List.of(1, 1, 1), outcomes.get(0).observedChunks());
        assertEquals(List.of(2, 1), outcomes.get(1).observedChunks());
        assertEquals(List.of(3), outcomes.get(2).observedChunks());
    }

    private static Outcome run(int maximumRootsPerChunk) {
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "chunk-equivalence-" + maximumRootsPerChunk)) {
            demo.addDocument("emb2", NestedTopologyDocuments.EMB2);
            MyOsDemoTimeline alice = demo.timeline(
                    "examples/nested/alice",
                    MyOsDemoActor.principal("alice"));
            demo.process(demo.append(alice, increment(2)),
                    maximumRootsPerChunk);
            demo.addDocument(
                    "emb1",
                    NestedTopologyDocuments.emb1Linking(
                            demo.document("emb2").initialBlueId()),
                    List.of(MyOsManagedEmbedding.at("/emb2", "emb2")));
            demo.addDocument(
                    "root",
                    NestedTopologyDocuments.rootLinking(
                            demo.document("emb1").initialBlueId()),
                    List.of(MyOsManagedEmbedding.at("/emb1", "emb1")));
            MyOsDemoDispatch dispatch = demo.process(
                    demo.append(alice, increment(3)),
                    maximumRootsPerChunk);

            Map<String, String> rootBlueIds = new LinkedHashMap<>();
            Map<String, Long> epochs = new LinkedHashMap<>();
            Map<String, String> frontiers = new LinkedHashMap<>();
            Map<String, Long> gasByDocument = new LinkedHashMap<>();
            Map<String, String> subscriptionDigests = new LinkedHashMap<>();
            Map<String, List<String>> rootEvents = new LinkedHashMap<>();
            Map<String, String> deliveryStates = new LinkedHashMap<>();
            Map<String, String> commitIdentities = new LinkedHashMap<>();
            for (String key : List.of("emb2", "emb1", "root")) {
                rootBlueIds.put(key, demo.currentRootBlueId(key));
                MyOsDemoResult result = dispatch.require(key);
                var session = demo.environment().engine().session(
                        demo.document(key).sessionId());
                epochs.put(key, session.currentEpoch());
                frontiers.put(key, session.committedFrontier().toString());
                gasByDocument.put(
                        key,
                        result.delivery().transition().platformResult()
                                .processResult().totalGas());
                subscriptionDigests.put(
                        key, session.subscriptions().digest());
                rootEvents.put(
                        key,
                        result.delivery().transition().commitPlan()
                                .rootOutboxEventBlueIds());
                deliveryStates.put(
                        key,
                        result.delivery().commitOutcome().status().name());
                commitIdentities.put(
                        key,
                        result.delivery().commitOutcome()
                                .transitionIdentity());
            }
            SemanticOutcome semantic = new SemanticOutcome(
                    dispatch.entry().blueId(),
                    rootBlueIds,
                    epochs,
                    frontiers,
                    gasByDocument,
                    subscriptionDigests,
                    rootEvents,
                    deliveryStates,
                    commitIdentities,
                    demo.value("emb2", "/counter"),
                    demo.value("emb1", "/emb2/counter"),
                    demo.value("root", "/emb1/emb2/counter"));
            return new Outcome(semantic, dispatch.chunkSizes());
        }
    }

    private static MyOsDemoOperation increment(int amount) {
        return MyOsDemoOperation.operation("increment")
                .through("ownerChannel")
                .request("amount: " + amount)
                .build();
    }

    private record Outcome(
            SemanticOutcome semantic,
            List<Integer> observedChunks) {
        private Outcome {
            observedChunks = List.copyOf(observedChunks);
        }
    }

    private record SemanticOutcome(
            String entryBlueId,
            Map<String, String> rootBlueIds,
            Map<String, Long> epochs,
            Map<String, String> frontiers,
            Map<String, Long> gasByDocument,
            Map<String, String> subscriptionDigests,
            Map<String, List<String>> rootEvents,
            Map<String, String> deliveryStates,
            Map<String, String> commitIdentities,
            Object emb2Value,
            Object emb1Value,
            Object rootValue) {
        private SemanticOutcome {
            rootBlueIds = Map.copyOf(rootBlueIds);
            epochs = Map.copyOf(epochs);
            frontiers = Map.copyOf(frontiers);
            gasByDocument = Map.copyOf(gasByDocument);
            subscriptionDigests = Map.copyOf(subscriptionDigests);
            rootEvents = Map.copyOf(rootEvents);
            deliveryStates = Map.copyOf(deliveryStates);
            commitIdentities = Map.copyOf(commitIdentities);
        }
    }
}
