package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production regression coverage for authored Contracts 1.0 compilation. */
final class Contracts10AuthoredClosureCompilerTest {
    @Test
    void compilesMissingPathAndCollectionValuesIntoVerifiedCyclicAdmission() {
        DocumentId a = DocumentId.of("compiler-ring-a");
        DocumentId b = DocumentId.of("compiler-ring-b");
        try (CoordinationEngine publicEngine = engine(Set.of(a))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10AuthoredClosureCompiler compiler =
                    new Contracts10AuthoredClosureCompiler(engine);

            Contracts10AuthoredClosureCompiler.CompiledClosure compiled =
                    compiler.compile(request(
                            List.of(
                                    document(a, collectionDocument("a")),
                                    document(b, pathDocument("b", "/back"))),
                            aliases("a", a, "b", b),
                            List.of(
                                    binding("a", "/peers/b", "b"),
                                    binding("b", "/back", "a")),
                            Set.of(a)));

            assertEquals(ClosureInvocationInput.Operation.ADMIT_CLOSURE,
                    compiled.invocation().operation());
            assertEquals(List.of(List.of(a, b)),
                    compiled.componentMembers());
            assertEquals(ComponentKind.CYCLIC,
                    compiled.components().get(0).kind());
            assertNotNull(compiled.components().get(0)
                    .completeCyclicProof());
            assertEquals(2, compiled.components().get(0)
                    .completeCyclicProof()
                    .declaredPlaceholderSet().size());
            assertEquals(compiled.components().get(0).masterBlueId(),
                    compiled.independentlyVerifiedMaster(a));
            assertEquals(Map.of(
                            a, List.of(b),
                            b, List.of(a)),
                    compiled.adjacency());
            assertCanonical(compiled.bindings());
            assertPreliminaryReference(
                    compiled.authoredDocument(a), "/peers/b", b);
            assertPreliminaryReference(
                    compiled.authoredDocument(b), "/back", a);

            Contracts10AuthoredClosureCompiler.ActivationInputs activation =
                    compiled.activationInputs();
            ContractsClosureAdmissionReceipt receipt = publicEngine
                    .admitContractsClosure(
                            compiled.invocation(),
                            activation.policy(),
                            activation.verifiedFrontier());
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    receipt.publicationOutcome());
            assertEquals(List.of(a, b), receipt.documentIds());
        }
    }

    @Test
    void derivesTwoDisjointComponentsWithoutCallerPartitionEvidence() {
        DocumentId a1 = DocumentId.of("compiler-disjoint-a1");
        DocumentId b1 = DocumentId.of("compiler-disjoint-b1");
        DocumentId a2 = DocumentId.of("compiler-disjoint-a2");
        DocumentId b2 = DocumentId.of("compiler-disjoint-b2");
        try (CoordinationEngine publicEngine = engine(Set.of(a1, a2))) {
            Contracts10AuthoredClosureCompiler compiler =
                    new Contracts10AuthoredClosureCompiler(
                            (DefaultCoordinationEngine) publicEngine);
            Contracts10AuthoredClosureCompiler.CompiledClosure compiled =
                    compiler.compile(request(
                            List.of(
                                    document(b2, pathDocument("b2", "/a")),
                                    document(a1, pathDocument("a1", "/b")),
                                    document(b1, pathDocument("b1", "/a")),
                                    document(a2, pathDocument("a2", "/b"))),
                            aliases(
                                    "a1", a1,
                                    "b1", b1,
                                    "a2", a2,
                                    "b2", b2),
                            List.of(
                                    binding("a2", "/b", "b2"),
                                    binding("b1", "/a", "a1"),
                                    binding("a1", "/b", "b1"),
                                    binding("b2", "/a", "a2")),
                            Set.of(a1, a2)));

            assertEquals(List.of(
                            List.of(a1, b1),
                            List.of(a2, b2)),
                    compiled.componentMembers());
            assertEquals(2, compiled.components().size());
            assertTrue(compiled.components().stream().allMatch(
                    component -> component.kind() == ComponentKind.CYCLIC));
        }
    }

    @Test
    void rejectsBindingOutsideEffectiveProcessEmbeddedCatalog() {
        DocumentId a = DocumentId.of("compiler-undeclared-a");
        DocumentId b = DocumentId.of("compiler-undeclared-b");
        try (CoordinationEngine publicEngine = engine(Set.of(a))) {
            Contracts10AuthoredClosureCompiler compiler =
                    new Contracts10AuthoredClosureCompiler(
                            (DefaultCoordinationEngine) publicEngine);
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> compiler.compile(request(
                            List.of(
                                    document(a, pathDocument(
                                            "a", "/declared")),
                                    document(b, plainDocument("b"))),
                            aliases("a", a, "b", b),
                            List.of(binding("a", "/wrong", "b")),
                            Set.of(a))));

            assertTrue(failure.getMessage().contains(
                    "not declared by the effective Process Embedded"));
        }
    }

    @Test
    void rejectsUnboundConcreteProcessEmbeddedOccurrence() {
        DocumentId a = DocumentId.of("compiler-unbound-a");
        try (CoordinationEngine publicEngine = engine(Set.of(a))) {
            Contracts10AuthoredClosureCompiler compiler =
                    new Contracts10AuthoredClosureCompiler(
                            (DefaultCoordinationEngine) publicEngine);
            String authored = """
                    marker: a
                    peer:
                      marker: unmanaged
                    contracts:
                      embedded:
                        type:
                          blueId: %s
                        paths:
                          - /peer
                    """.formatted(RuntimeBlueIds.PROCESS_EMBEDDED);

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> compiler.compile(request(
                            List.of(document(a, authored)),
                            Map.of("a", a),
                            List.of(),
                            Set.of(a))));

            assertTrue(failure.getMessage().contains(
                    "without managed bindings"));
        }
    }

    @Test
    void rejectsMaterializedValueThatIsNotTheBoundTarget() {
        DocumentId a = DocumentId.of("compiler-wrong-target-a");
        DocumentId b = DocumentId.of("compiler-wrong-target-b");
        try (CoordinationEngine publicEngine = engine(Set.of(a))) {
            Contracts10AuthoredClosureCompiler compiler =
                    new Contracts10AuthoredClosureCompiler(
                            (DefaultCoordinationEngine) publicEngine);
            String authoredA = """
                    marker: a
                    peer: definitely-not-b
                    contracts:
                      embedded:
                        type:
                          blueId: %s
                        paths:
                          - /peer
                    """.formatted(RuntimeBlueIds.PROCESS_EMBEDDED);

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> compiler.compile(request(
                            List.of(
                                    document(a, authoredA),
                                    document(b, plainDocument("b"))),
                            aliases("a", a, "b", b),
                            List.of(binding("a", "/peer", "b")),
                            Set.of(a))));

            assertTrue(failure.getMessage().contains(
                    "materialized state for the wrong target"),
                    failure::getMessage);
        }
    }

    @Test
    void rejectsDuplicateManagedIdentityAndOverlappingOccurrences() {
        DocumentId a = DocumentId.of("compiler-invalid-a");
        DocumentId b = DocumentId.of("compiler-invalid-b");
        try (CoordinationEngine publicEngine = engine(Set.of(a))) {
            Contracts10AuthoredClosureCompiler compiler =
                    new Contracts10AuthoredClosureCompiler(
                            (DefaultCoordinationEngine) publicEngine);
            assertThrows(IllegalArgumentException.class,
                    () -> compiler.compile(request(
                            List.of(
                                    document(a, plainDocument("first")),
                                    document(a, plainDocument("second"))),
                            Map.of("a", a),
                            List.of(),
                            Set.of(a))));
            assertThrows(IllegalArgumentException.class,
                    () -> compiler.compile(request(
                            List.of(
                                    document(a, collectionDocument("a")),
                                    document(b, plainDocument("b"))),
                            aliases("a", a, "b", b),
                            List.of(
                                    binding("a", "/peers", "b"),
                                    binding("a", "/peers/b", "b")),
                            Set.of(a))));
        }
    }

    private static Contracts10AuthoredClosureCompiler.CompilationRequest
            request(
                    List<Contracts10AuthoredClosureCompiler.AuthoredDocument>
                            documents,
                    Map<String, DocumentId> aliases,
                    List<Contracts10AuthoredClosureCompiler.OccurrenceBinding>
                            occurrences,
                    Set<DocumentId> roots) {
        return new Contracts10AuthoredClosureCompiler.CompilationRequest(
                documents,
                aliases,
                occurrences,
                roots,
                Contracts10AuthoredClosureCompiler.ActivationInputs
                        .fromNow(),
                "contracts10-authored-compiler-test");
    }

    private static Contracts10AuthoredClosureCompiler.AuthoredDocument
            document(DocumentId documentId, String yaml) {
        return new Contracts10AuthoredClosureCompiler.AuthoredDocument(
                documentId, yaml);
    }

    private static Contracts10AuthoredClosureCompiler.OccurrenceBinding
            binding(String source, String path, String target) {
        return new Contracts10AuthoredClosureCompiler.OccurrenceBinding(
                source, path, target);
    }

    private static Map<String, DocumentId> aliases(
            Object... alternatingAliasAndId) {
        LinkedHashMap<String, DocumentId> result = new LinkedHashMap<>();
        for (int index = 0;
                index < alternatingAliasAndId.length;
                index += 2) {
            result.put(
                    (String) alternatingAliasAndId[index],
                    (DocumentId) alternatingAliasAndId[index + 1]);
        }
        return result;
    }

    private static String plainDocument(String marker) {
        return "marker: " + marker;
    }

    private static String pathDocument(String marker, String path) {
        return """
                marker: %s
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - %s
                """.formatted(
                        marker, RuntimeBlueIds.PROCESS_EMBEDDED, path);
    }

    private static String collectionDocument(String marker) {
        return """
                marker: %s
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    collectionPaths:
                      - /peers
                """.formatted(marker, RuntimeBlueIds.PROCESS_EMBEDDED);
    }

    private static void assertPreliminaryReference(
            Node source,
            String path,
            DocumentId target) {
        Node reference = NodePathEditor.getOrNull(source, path);
        assertNotNull(reference);
        assertTrue(reference.isReferenceOnly());
        assertEquals(preliminaryBlueId(target), reference.getBlueId());
    }

    private static void assertCanonical(
            List<ManagedOccurrenceBinding> bindings) {
        for (int index = 1; index < bindings.size(); index++) {
            assertTrue(bindings.get(index - 1).compareTo(
                    bindings.get(index)) < 0);
        }
    }

    private static String preliminaryBlueId(DocumentId target) {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().value("scenario-target:" + target.value()));
    }

    private static CoordinationEngine engine(Set<DocumentId> publicRoots) {
        return DefaultCoordinationEngine.createContracts10(
                BundledContracts10Release.configuration(publicRoots));
    }
}
