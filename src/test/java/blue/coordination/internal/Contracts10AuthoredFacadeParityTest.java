package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.closure.AdmissionCause;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FinalizedComponentEvidence;
import blue.language.processor.closure.FinalizedDocumentEvidence;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Characterizes authored-document parity with the expert admission seam. */
final class Contracts10AuthoredFacadeParityTest {
    private static final String LANGUAGE_SPEC = sha('a');
    private static final String CONTRACTS_SPEC = sha('b');
    private static final String ADMISSION_LABEL = "phase10-authored-parity";
    private static final String ADMISSION_POLICY =
            "contracts-top-level-admission-v1";

    @Test
    void authoredDocumentsDeriveTheExactLowLevelAdmissionIdentity() {
        // given

        DocumentId a = DocumentId.of("phase10-authored-a");
        DocumentId b = DocumentId.of("phase10-authored-b");

        try (CoordinationEngine publicEngine = engine(Set.of(a))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder.Scenario authored =
                    new Contracts10ScenarioBuilder(engine)
                            .document(a, document("a"))
                            .document(b, document("b"))
                            .processEmbeddedPath(a, "/peer", b)
                            .processEmbeddedPath(b, "/peer", a)
                            .publicRoot(a)
                            // Test oracle only; it is not copied into the
                            // invocation or accepted as caller graph evidence.
                            .expectedComponent(a, b)
                            .admissionLabel(ADMISSION_LABEL)
                            .scenario();

            ClosureInvocationInput facadeInput = authored.admission();

            // when
            ClosureInvocationInput lowLevelInput = lowLevelAdmission(
                    engine, authored, a, b);

            // then
            assertEquals(ClosureInvocationInput.Operation.ADMIT_CLOSURE,
                    facadeInput.operation());
            assertEquals(facadeInput.operation(), lowLevelInput.operation());
            assertEquals(facadeInput.invocationIdentity(),
                    lowLevelInput.invocationIdentity());
            assertEquals(facadeInput.snapshot().closureIdentity(),
                    lowLevelInput.snapshot().closureIdentity());
            assertEquals(facadeInput.snapshot().occurrenceBindingSetIdentity(),
                    lowLevelInput.snapshot().occurrenceBindingSetIdentity());
            assertEquals(documentEvidence(facadeInput.snapshot()),
                    documentEvidence(lowLevelInput.snapshot()));
            assertEquals(occurrenceEvidence(facadeInput.snapshot()),
                    occurrenceEvidence(lowLevelInput.snapshot()));
            assertEquals(componentEvidence(facadeInput.snapshot()),
                    componentEvidence(lowLevelInput.snapshot()));
            assertEquals(facadeInput.snapshot().publicRootDocumentIds(),
                    lowLevelInput.snapshot().publicRootDocumentIds());
            assertEquals(facadeInput.cause().causeIdentity(),
                    lowLevelInput.cause().causeIdentity());
            assertEquals(facadeInput.executionPolicy().identity(),
                    lowLevelInput.executionPolicy().identity());
            assertEquals(environmentEvidence(facadeInput.environment()),
                    environmentEvidence(lowLevelInput.environment()));
            assertEquals(facadeInput.directDeliverySnapshotIdentity(),
                    lowLevelInput.directDeliverySnapshotIdentity());
            assertTrue(facadeInput.directDeliveries().isEmpty());
            assertTrue(lowLevelInput.directDeliveries().isEmpty());
            assertNull(facadeInput.admissionCandidate());
            assertNull(lowLevelInput.admissionCandidate());
            assertEquals(List.of(List.of(closureId(a), closureId(b))),
                    lowLevelInput.snapshot().components().stream()
                            .map(ComponentSnapshot::orderedMemberDocumentIds)
                            .toList());
        }
    }

    private static ClosureInvocationInput lowLevelAdmission(
            DefaultCoordinationEngine engine,
            Contracts10ScenarioBuilder.Scenario authored,
            DocumentId a,
            DocumentId b) {
        ClosureEnvironment environment = engine
                .contractsClosureAdmissionAdapter().environment();
        List<ManagedOccurrenceBinding> rows = List.of(
                binding(environment, authored, b, "/peer", a),
                binding(environment, authored, a, "/peer", b));
        List<blue.language.processor.closure.DocumentId> documentIds =
                List.of(closureId(b), closureId(a));
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                documentIds, rows);
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = new LinkedHashMap<>();
        generations.put(closureId(b), 1L);
        generations.put(closureId(a), 1L);
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node> bodies =
                new LinkedHashMap<>();
        bodies.put(closureId(b), authored.document(b));
        bodies.put(closureId(a), authored.document(a));
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph, generations, bodies, rows));

        ArrayList<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (FinalizedDocumentEvidence document
                : finalized.documents().values()) {
            documents.add(new ManagedDocumentSnapshot(
                    document.documentId(),
                    document.blueId(),
                    document.document(),
                    false,
                    false,
                    document.documentId().equals(closureId(a)),
                    0L,
                    document.componentGeneration()));
        }
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        1L,
                        documents,
                        finalized.finalizedGraph().bindings(),
                        finalized.components().stream()
                                .map(FinalizedComponentEvidence::component)
                                .toList(),
                        List.of(closureId(a)));
        AdmissionCause cause = ClosureEvidenceFactory.admissionCause(
                AdmissionKind.TOP_LEVEL_ADMISSION,
                ADMISSION_LABEL,
                null,
                null,
                ADMISSION_POLICY);
        ExecutionPolicy policy = engine.contractsClosureAdmissionAdapter()
                .executionPolicy();
        return ClosureEvidenceFactory.admitClosure(
                snapshot, cause, null, policy, environment);
    }

    private static ManagedOccurrenceBinding binding(
            ClosureEnvironment environment,
            Contracts10ScenarioBuilder.Scenario authored,
            DocumentId source,
            String path,
            DocumentId target) {
        Node exactReference = NodePathEditor.getOrNull(
                authored.document(source), path);
        assertNotNull(exactReference);
        assertEquals(authored.blueId(target), exactReference.getBlueId());
        return ManagedOccurrenceBinding.derived(
                environment.managedBindingPolicyIdentity(),
                closureId(source),
                ScopeAddress.embedded(path, 1L),
                closureId(target),
                exactReference.getBlueId(),
                true,
                null);
    }

    private static List<List<Object>> documentEvidence(
            AffectedClosureSnapshot snapshot) {
        return snapshot.managedDocuments().stream()
                .map(document -> List.<Object>of(
                        document.documentId(),
                        document.blueId(),
                        document.initialized(),
                        document.terminated(),
                        document.publicRoot(),
                        document.epoch(),
                        document.componentGeneration()))
                .toList();
    }

    private static List<List<Object>> occurrenceEvidence(
            AffectedClosureSnapshot snapshot) {
        return snapshot.occurrences().stream()
                .map(row -> List.<Object>of(
                        row.occurrenceIdentity(),
                        row.bindingIdentity(),
                        row.sourceDocumentId(),
                        row.sourceAddress().path(),
                        row.sourceAddress().activationGeneration(),
                        row.targetDocumentId(),
                        row.expectedTargetBlueId(),
                        row.active()))
                .toList();
    }

    private static List<List<Object>> componentEvidence(
            AffectedClosureSnapshot snapshot) {
        return snapshot.components().stream()
                .map(component -> List.<Object>of(
                        component.componentIdentity(),
                        component.componentStateIdentity(),
                        component.componentGeneration(),
                        component.kind(),
                        component.orderedMemberDocumentIds(),
                        component.orderedMemberBlueIds(),
                        component.masterBlueId(),
                        component.cyclicProofIdentity()))
                .toList();
    }

    private static List<Object> environmentEvidence(
            ClosureEnvironment environment) {
        return List.of(
                environment.blueLanguageSpecificationIdentity(),
                environment.contractsSpecificationIdentity(),
                environment.runtimeRegistryIdentity(),
                environment.gasManifestIdentity(),
                environment.managedDocumentIdentityPolicyIdentity(),
                environment.managedBindingPolicyIdentity(),
                environment.exactNodeProviderDomainIdentity(),
                environment.externalOrderPolicyIdentity(),
                environment.portableLimitPolicyIdentity(),
                environment.cyclicFinalizerIdentity(),
                environment.cyclicProofVerifierIdentity());
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

    private static blue.language.processor.closure.DocumentId closureId(
            DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    private static String sha(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }
}
