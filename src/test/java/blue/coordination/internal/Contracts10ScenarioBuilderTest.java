package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused characterization of the test-only Contracts 1.0 scenario seam. */
final class Contracts10ScenarioBuilderTest {
    private static final String LANGUAGE_SPEC = sha('a');
    private static final String CONTRACTS_SPEC = sha('b');

    @Test
    void authorsThreeMemberRingWithCanonicalBindingsAndVerifiedProof() {
        DocumentId a = DocumentId.of("builder-ring-a");
        DocumentId b = DocumentId.of("builder-ring-b");
        DocumentId c = DocumentId.of("builder-ring-c");
        try (CoordinationEngine publicEngine = engine(Set.of(a))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder builder =
                    new Contracts10ScenarioBuilder(engine)
                            .document(c, document("c"))
                            .document(a, document("a"))
                            .document(b, document("b"))
                            .processEmbeddedPath(c, "/a", a)
                            .processEmbeddedPath(a, "/b", b)
                            .processEmbeddedPath(b, "/c", c)
                            .publicRoot(a)
                            .expectedComponent(a, b, c)
                            .occurrenceOrder(
                                    Contracts10ScenarioBuilder.OccurrenceOrder
                                            .REVERSED);

            Contracts10ScenarioBuilder.Scenario scenario =
                    builder.scenario();
            assertEquals(List.of(List.of(a, b, c)),
                    scenario.expectedComponents());
            assertEquals(scenario.expectedComponents(),
                    scenario.componentMembers());
            assertEquals(1, scenario.components().size());
            ComponentSnapshot component = scenario.components().get(0);
            assertEquals(ComponentKind.CYCLIC, component.kind());
            assertEquals(3, component.completeCyclicProof()
                    .declaredPlaceholderSet().size());
            assertEquals(component.masterBlueId(),
                    scenario.independentlyVerifiedMaster(a));
            assertEquals(component.masterBlueId(),
                    scenario.independentlyVerifiedMaster(b));
            assertEquals(component.masterBlueId(),
                    scenario.independentlyVerifiedMaster(c));
            assertCanonicalBindings(scenario.bindings());
            assertExactReference(scenario, a, "/b", b);
            assertExactReference(scenario, b, "/c", c);
            assertExactReference(scenario, c, "/a", a);

            Contracts10ScenarioBuilder.ScenarioRuntime admitted =
                    builder.admitTo(publicEngine);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.admissionReceipt().publicationOutcome());
            assertEquals(scenario.admission().invocationIdentity(),
                    admitted.scenario().admission().invocationIdentity());
        }
    }

    @Test
    void authorsCollectionBackedSharedAnchorAsOneFiveMemberComponent() {
        TopologyIds ids = TopologyIds.sharedAnchor("builder-collection");
        try (CoordinationEngine publicEngine = engine(Set.of(ids.a()))) {
            Contracts10ScenarioBuilder.Scenario scenario = sharedAnchor(
                    (DefaultCoordinationEngine) publicEngine,
                    ids,
                    false,
                    Contracts10ScenarioBuilder.ReferenceRepresentation
                            .REFERENCE_ONLY).scenario();

            assertEquals(List.of(List.of(
                    ids.a(), ids.b1(), ids.b2(), ids.c1(), ids.c2())),
                    scenario.componentMembers());
            assertEquals(Map.of(
                            ids.a(), List.of(ids.b1(), ids.b2()),
                            ids.b1(), List.of(ids.c1()),
                            ids.b2(), List.of(ids.c2()),
                            ids.c1(), List.of(ids.a()),
                            ids.c2(), List.of(ids.a())),
                    scenario.adjacency());
            Node collectionPath = NodePathEditor.getOrNull(
                    scenario.document(ids.a()),
                    "/contracts/embedded/collectionPaths/0");
            assertNotNull(collectionPath);
            assertEquals("/branches", collectionPath.getValue());
            assertExactReference(
                    scenario, ids.a(), "/branches/b1", ids.b1());
            assertExactReference(
                    scenario, ids.a(), "/branches/b2", ids.b2());
            assertEquals(
                    scenario.components().get(0).masterBlueId(),
                    scenario.independentlyVerifiedMaster(ids.c2()));
        }
    }

    @Test
    void insertionAndOccurrenceOrderPreserveMaterializedReferenceParity() {
        TopologyIds ids = TopologyIds.sharedAnchor("builder-parity");
        try (CoordinationEngine publicEngine = engine(Set.of(ids.a()))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder.Scenario reference = sharedAnchor(
                    engine,
                    ids,
                    false,
                    Contracts10ScenarioBuilder.ReferenceRepresentation
                            .REFERENCE_ONLY).scenario();
            Contracts10ScenarioBuilder.Scenario materialized = sharedAnchor(
                    engine,
                    ids,
                    true,
                    Contracts10ScenarioBuilder.ReferenceRepresentation
                            .MATERIALIZED).scenario();

            assertEquals(reference.componentMembers(),
                    materialized.componentMembers());
            assertEquals(reference.blueIds(), materialized.blueIds());
            assertEquals(componentStateIdentities(reference),
                    componentStateIdentities(materialized));
            assertEquals(proofIdentities(reference),
                    proofIdentities(materialized));
            assertEquals(bindingIdentities(reference),
                    bindingIdentities(materialized));
            assertEquals(reference.independentlyVerifiedMaster(ids.a()),
                    materialized.independentlyVerifiedMaster(ids.a()));
            assertTrue(NodePathEditor.getOrNull(
                    reference.document(ids.a()), "/branches/b1")
                    .isReferenceOnly());
            assertTrue(NodePathEditor.getOrNull(
                    materialized.document(ids.a()), "/branches/b1")
                    .isReferenceOnly());
            Node completeTarget = NodePathEditor.getOrNull(
                    materialized.authoredDocument(ids.a()),
                    "/branches/b1");
            assertFalse(completeTarget.isReferenceOnly());
            assertNotNull(completeTarget.getBlueId());
            assertNotNull(NodePathEditor.getOrNull(
                    completeTarget, "/documentId"));
        }
    }

    @Test
    void literalPartitionKeepsTwoDisjointCyclesDistinct() {
        DocumentId a1 = DocumentId.of("builder-disjoint-a1");
        DocumentId b1 = DocumentId.of("builder-disjoint-b1");
        DocumentId a2 = DocumentId.of("builder-disjoint-a2");
        DocumentId b2 = DocumentId.of("builder-disjoint-b2");
        try (CoordinationEngine publicEngine = engine(Set.of(a1, a2))) {
            Contracts10ScenarioBuilder.Scenario scenario =
                    new Contracts10ScenarioBuilder(
                            (DefaultCoordinationEngine) publicEngine)
                            .document(b2, document("b2"))
                            .document(a1, document("a1"))
                            .document(b1, document("b1"))
                            .document(a2, document("a2"))
                            .processEmbeddedPath(a2, "/b", b2)
                            .processEmbeddedPath(b1, "/a", a1)
                            .processEmbeddedPath(a1, "/b", b1)
                            .processEmbeddedPath(b2, "/a", a2)
                            .publicRoot(a2)
                            .publicRoot(a1)
                            .expectedComponent(a1, b1)
                            .expectedComponent(a2, b2)
                            .scenario();

            assertEquals(List.of(
                            List.of(a1, b1),
                            List.of(a2, b2)),
                    scenario.componentMembers());
            assertNotEquals(
                    scenario.independentlyVerifiedMaster(a1),
                    scenario.independentlyVerifiedMaster(a2));
        }
    }

    @Test
    void rejectsIncompleteEdgesOverlapsAndRuntimeDerivedPartitionClaims() {
        DocumentId a = DocumentId.of("builder-invalid-a");
        DocumentId b = DocumentId.of("builder-invalid-b");
        DocumentId missing = DocumentId.of("builder-invalid-missing");
        try (CoordinationEngine publicEngine = engine(Set.of(a))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            assertThrows(IllegalArgumentException.class,
                    () -> new Contracts10ScenarioBuilder(engine)
                            .document(a, document("a"))
                            .processEmbeddedPath(a, "/b", missing)
                            .publicRoot(a)
                            .expectedComponent(a)
                            .scenario());
            assertThrows(IllegalArgumentException.class,
                    () -> new Contracts10ScenarioBuilder(engine)
                            .document(a, document("a"))
                            .document(b, document("b"))
                            .processEmbeddedPath(a, "/peer", b)
                            .processEmbeddedPath(a, "/peer/child", b)
                            .publicRoot(a)
                            .expectedComponent(a)
                            .expectedComponent(b)
                            .scenario());
            assertThrows(IllegalArgumentException.class,
                    () -> new Contracts10ScenarioBuilder(engine)
                            .document(a, document("a"))
                            .document(b, document("b"))
                            .processEmbeddedPath(a, "/b", b)
                            .processEmbeddedPath(b, "/a", a)
                            .publicRoot(a)
                            .expectedComponent(a)
                            .expectedComponent(b)
                            .scenario());
            assertThrows(IllegalStateException.class,
                    () -> new Contracts10ScenarioBuilder(engine)
                            .document(a, document("a"))
                            .publicRoot(a)
                            .scenario());
        }
    }

    private static Contracts10ScenarioBuilder sharedAnchor(
            DefaultCoordinationEngine engine,
            TopologyIds ids,
            boolean reversed,
            Contracts10ScenarioBuilder.ReferenceRepresentation
                    representation) {
        Contracts10ScenarioBuilder builder =
                new Contracts10ScenarioBuilder(engine);
        List<Map.Entry<DocumentId, String>> documents = new ArrayList<>(
                List.of(
                        Map.entry(ids.a(), "a"),
                        Map.entry(ids.b1(), "b1"),
                        Map.entry(ids.b2(), "b2"),
                        Map.entry(ids.c1(), "c1"),
                        Map.entry(ids.c2(), "c2")));
        if (reversed) {
            java.util.Collections.reverse(documents);
        }
        for (Map.Entry<DocumentId, String> document : documents) {
            builder.document(
                    document.getKey(), document(document.getValue()));
        }
        if (reversed) {
            builder.processEmbeddedPath(ids.c2(), "/root", ids.a())
                    .processEmbeddedPath(ids.b2(), "/child", ids.c2())
                    .processEmbeddedCollectionMember(
                            ids.a(), "/branches", "b2", ids.b2())
                    .processEmbeddedPath(ids.c1(), "/root", ids.a())
                    .processEmbeddedPath(ids.b1(), "/child", ids.c1())
                    .processEmbeddedCollectionMember(
                            ids.a(), "/branches", "b1", ids.b1())
                    .occurrenceOrder(
                            Contracts10ScenarioBuilder.OccurrenceOrder
                                    .REVERSED);
        } else {
            builder.processEmbeddedCollectionMember(
                            ids.a(), "/branches", "b1", ids.b1())
                    .processEmbeddedPath(ids.b1(), "/child", ids.c1())
                    .processEmbeddedPath(ids.c1(), "/root", ids.a())
                    .processEmbeddedCollectionMember(
                            ids.a(), "/branches", "b2", ids.b2())
                    .processEmbeddedPath(ids.b2(), "/child", ids.c2())
                    .processEmbeddedPath(ids.c2(), "/root", ids.a());
        }
        return builder.publicRoot(ids.a())
                .expectedComponent(
                        ids.a(), ids.b1(), ids.b2(), ids.c1(), ids.c2())
                .representation(representation);
    }

    private static void assertExactReference(
            Contracts10ScenarioBuilder.Scenario scenario,
            DocumentId source,
            String path,
            DocumentId target) {
        Node reference = NodePathEditor.getOrNull(
                scenario.document(source), path);
        assertNotNull(reference);
        assertTrue(reference.isReferenceOnly());
        assertEquals(scenario.blueId(target), reference.getBlueId());
    }

    private static void assertCanonicalBindings(
            List<ManagedOccurrenceBinding> bindings) {
        ArrayList<ManagedOccurrenceBinding> sorted =
                new ArrayList<>(bindings);
        sorted.sort(null);
        assertEquals(bindingIdentities(bindings), bindingIdentities(sorted));
        for (ManagedOccurrenceBinding binding : bindings) {
            ManagedOccurrenceBinding verified =
                    ManagedOccurrenceBinding.verified(
                            binding.occurrenceIdentity(),
                            binding.bindingIdentity(),
                            binding.bindingPolicyIdentity(),
                            binding.sourceDocumentId(),
                            binding.sourceAddress(),
                            binding.targetDocumentId(),
                            binding.expectedTargetBlueId(),
                            binding.active(),
                            binding.pendingHistoricalEpoch());
            assertEquals(binding.occurrenceIdentity(),
                    verified.occurrenceIdentity());
            assertEquals(binding.bindingIdentity(),
                    verified.bindingIdentity());
        }
    }

    private static List<String> componentStateIdentities(
            Contracts10ScenarioBuilder.Scenario scenario) {
        return scenario.components().stream()
                .map(ComponentSnapshot::componentStateIdentity)
                .toList();
    }

    private static List<String> proofIdentities(
            Contracts10ScenarioBuilder.Scenario scenario) {
        return scenario.components().stream()
                .map(ComponentSnapshot::cyclicProofIdentity)
                .toList();
    }

    private static List<String> bindingIdentities(
            Contracts10ScenarioBuilder.Scenario scenario) {
        return bindingIdentities(scenario.bindings());
    }

    private static List<String> bindingIdentities(
            List<ManagedOccurrenceBinding> bindings) {
        return bindings.stream()
                .map(binding -> binding.occurrenceIdentity()
                        + ":" + binding.bindingIdentity())
                .toList();
    }

    private static Node document(String marker) {
        return new Node().properties(
                "marker", new Node().value(marker),
                "phase", new Node().value("initial"));
    }

    private static CoordinationEngine engine(Set<DocumentId> publicRoots) {
        return CoordinationEngine.inMemoryContracts10(
                new Contracts10Configuration(
                        LANGUAGE_SPEC,
                        CONTRACTS_SPEC,
                        publicRoots));
    }

    private static String sha(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }

    private record TopologyIds(
            DocumentId a,
            DocumentId b1,
            DocumentId b2,
            DocumentId c1,
            DocumentId c2) {
        private static TopologyIds sharedAnchor(String prefix) {
            return new TopologyIds(
                    DocumentId.of(prefix + "-a"),
                    DocumentId.of(prefix + "-b1"),
                    DocumentId.of(prefix + "-b2"),
                    DocumentId.of(prefix + "-c1"),
                    DocumentId.of(prefix + "-c2"));
        }
    }
}
