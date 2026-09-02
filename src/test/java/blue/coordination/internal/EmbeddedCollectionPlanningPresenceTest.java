package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact collection-presence behavior at Coordination's layout boundary. */
final class EmbeddedCollectionPlanningPresenceTest {

    @Test
    void absentAndPresentEmptyCollectionsBothPlanZeroOccurrences() {
        // given
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        ExactValue absent = objects.put(
                root("absent", null), "absent-collection-root");
        ExactValue empty = objects.put(
                root("empty", emptyObject()), "empty-collection-root");

        try (BlueRuntime runtime = BlueRuntime.create(objects, metrics)) {
            EmbeddedOnlyLayoutBuilder layouts =
                    new EmbeddedOnlyLayoutBuilder(runtime, objects, metrics);

            // when
            EmbeddedOnlyLayout absentLayout = layouts.build(absent);
            EmbeddedOnlyLayout emptyLayout = layouts.build(empty);

            // then
            assertEquals(List.of(), absentLayout.boundaries());
            assertEquals(List.of(), emptyLayout.boundaries());
            assertEquals(1, absentLayout.physicalObjectCount());
            assertEquals(1, emptyLayout.physicalObjectCount());
            assertNotEquals(absentLayout.rootBlueId(), emptyLayout.rootBlueId(),
                    "present exact {} must remain content even with zero members");
            assertEquals(List.of("/games"), absentLayout.plan()
                    .rulesByScope().get("/").collectionAbsolutePaths());
            assertEquals(List.of("/games"), emptyLayout.plan()
                    .rulesByScope().get("/").collectionAbsolutePaths());
        }
    }

    @Test
    void unavailableCollectionEvidenceBlocksInsteadOfPlanningZero() {
        // given
        String unavailableBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().properties("providerFixture",
                        new Node().value("unavailable-collection")));
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        ExactValue referenced = objects.put(
                root("unavailable", new Node().blueId(unavailableBlueId)),
                "unavailable-collection-root");
        NodeProvider unavailable = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return List.of();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                return unavailableBlueId.equals(blueId)
                        ? NodeProviderResult.unavailable(
                                "collection provider offline")
                        : NodeProviderResult.notFound();
            }
        };

        try (BlueRuntime runtime = BlueRuntime.create(
                objects, metrics, unavailable)) {
            EmbeddedOnlyLayoutBuilder layouts =
                    new EmbeddedOnlyLayoutBuilder(runtime, objects, metrics);

            // when
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> layouts.build(referenced));

            // then
            ExecutionEvidenceUnavailableException incomplete =
                    assertInstanceOf(
                            ExecutionEvidenceUnavailableException.class,
                            failure);
            assertEquals(List.of(unavailableBlueId),
                    incomplete.requiredExactBlueIds());
            assertTrue(incomplete.getMessage().contains(
                    "collection provider offline"));
        }
    }

    @Test
    void presentWrongKindCollectionFailsInsteadOfPlanningZero() {
        // given
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        ExactValue scalar = objects.put(
                root("wrong-kind", new Node().value("not-an-object")),
                "wrong-kind-collection-root");

        try (BlueRuntime runtime = BlueRuntime.create(objects, metrics)) {
            EmbeddedOnlyLayoutBuilder layouts =
                    new EmbeddedOnlyLayoutBuilder(runtime, objects, metrics);

            // when
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> layouts.build(scalar));

            // then
            assertTrue(failure.getMessage().contains(
                    "Embedded collection must be an object"));
        }
    }

    private static ExactValue root(String documentId, Node collection) {
        Node root = new Node()
                .properties("documentId", new Node().value(documentId))
                .contracts(new Node().properties(
                        "embedded",
                        new Node()
                                .type(new Node().blueId(
                                        RuntimeBlueIds.PROCESS_EMBEDDED))
                                .properties(
                                        "collectionPaths",
                                        new Node().items(List.of(
                                                new Node().value(
                                                        "/games"))))));
        if (collection != null) {
            root.properties("games", collection);
        }
        return ExactValue.verified(root);
    }

    private static Node emptyObject() {
        return new Node().properties(Collections.emptyMap());
    }
}
