package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureWorkOccurrence;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.FinalizedComponentEvidence;
import blue.language.processor.closure.FinalizedDocumentEvidence;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.TentativeFinalization;
import blue.language.processor.closure.WorkKind;
import blue.language.processor.util.ProcessorContractConstants;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public Contracts admission proofs for initialization and topology. */
final class ContractsPublicInitializationTopologyTest {
    private static final DocumentId A = DocumentId.of("init-topology-a");
    private static final DocumentId B = DocumentId.of("init-topology-b");
    private static final DocumentId C = DocumentId.of("init-topology-c");
    private static final List<DocumentId> MEMBERS = List.of(A, B, C);
    private static final Set<String> DYNAMIC_PATHS = Set.of(
            "/reciprocal", "/members/b", "/members/c");
    private static final String ADMISSION_POLICY =
            "contracts-top-level-admission-v1";

    @Test
    void staticThreeMemberCycleInitializesOnceInCanonicalOrderAndPublishes()
            throws Exception {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder builder = staticRing(
                    engine, false);

            // when
            ContractsClosureAdmissionReceipt admitted = builder
                    .admitTo(publicEngine).admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertEquals(MEMBERS, admitted.documentIds());
            ClosureProcessResult result = admitted.attempt().processResult();
            assertEquals(ProcessorStatus.SUCCESS, result.status());
            assertTrue(result.commits());
            assertEquals(1, result.resultingComponents().size());
            assertEquals(ComponentKind.CYCLIC,
                    result.resultingComponents().get(0).kind());
            assertEquals(closureMemberValues(), result.resultingComponents()
                    .get(0).orderedMemberDocumentIds().stream()
                    .map(blue.language.processor.closure.DocumentId::value)
                    .toList());
            for (DocumentId member : MEMBERS) {
                assertEquals(1L, integer(
                        publicEngine.document(member).current().copyNode(),
                        "/initializationCount"));
                assertTrue(hasInitializedMarker(
                        publicEngine.document(member).current().copyNode()));
            }

            ClosureImplementationEvidence evidence = engine
                    .contractsClosureAdmissionAdapter()
                    .lastExecutionEvidence().orElseThrow();
            assertTrue(evidence.complete());
            assertEquals(List.of(
                            WorkKind.INITIALIZATION, WorkKind.LIFECYCLE,
                            WorkKind.INITIALIZATION, WorkKind.LIFECYCLE,
                            WorkKind.INITIALIZATION, WorkKind.LIFECYCLE),
                    evidence.workTrace().stream()
                            .map(ClosureWorkOccurrence::kind)
                            .toList());
            assertEquals(List.of(
                            A.value(), A.value(),
                            B.value(), B.value(),
                            C.value(), C.value()),
                    workTargets(evidence));
            evidence.documentStepTrace().forEach(step -> {
                assertEquals(step.targetDocumentId(),
                        step.executionRootDocumentId());
                assertEquals("/", step.scopePath());
                assertTrue(step.ambientContainingDocumentIds().isEmpty());
            });
            assertEquals(1L, evidence.tentativeFinalizations().stream()
                    .filter(finalization -> finalization.boundary().kind()
                            == TentativeFinalization.Boundary.Kind
                                    .INITIALIZATION_BATCH)
                    .count());
            assertEquals(0L, publicEngine.metrics().journalEntryCount(),
                    "ADMIT_CLOSURE initialization is not a Timeline Entry");
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P6.static-three-member-admission",
                        engine,
                        result,
                        null,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "admissionPublicationIdentity",
                                admitted.publicationIdentity(),
                                "timelineEntryCount",
                                publicEngine.metrics().journalEntryCount(),
                                "workTargets", workTargets(evidence)));
            }
        }
    }

    @Test
    void staticInitializationOrderAndIdentitiesIgnoreInputPermutation()
            throws Exception {
        // given
        Variant declared = Variant.DECLARED;
        Variant reversed = Variant.REVERSED;

        // when
        StaticOrderEvidence declaredEvidence = runStaticOrder(declared);
        StaticOrderEvidence reversedEvidence = runStaticOrder(reversed);

        // then
        assertEquals(declaredEvidence, reversedEvidence);
    }

    @Test
    void dynamicTopologyPatchInsideCycleFailsAtManagedBindingBoundary()
            throws Exception {
        // given
        DynamicFailureEvidence declared = runDynamic(Variant.DECLARED);

        // when
        DynamicFailureEvidence reversed = runDynamic(Variant.REVERSED);

        // then
        assertEquals(declared, reversed,
                "document and occurrence input order must not affect "
                        + "the deterministic dynamic-topology boundary");
    }

    private static StaticOrderEvidence runStaticOrder(Variant variant)
            throws Exception {
        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ContractsClosureAdmissionReceipt admitted = staticRing(
                    engine, false, variant)
                    .admitTo(publicEngine).admissionReceipt();
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            ClosureProcessResult result = admitted.attempt().processResult();
            ClosureImplementationEvidence evidence = engine
                    .contractsClosureAdmissionAdapter()
                    .lastExecutionEvidence().orElseThrow();
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P6.static-order-" + variant.name(),
                        engine,
                        result,
                        null,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "admissionPublicationIdentity",
                                admitted.publicationIdentity(),
                                "workTargets", workTargets(evidence)));
            }
            return new StaticOrderEvidence(
                    result.outputClosureIdentity(),
                    result.resultingComponents().get(0)
                            .componentStateIdentity(),
                    result.resultingDocuments().stream()
                            .map(ResultingDocument::afterBlueId)
                            .toList(),
                    workTargets(evidence),
                    evidence.workTrace().stream()
                            .map(ClosureWorkOccurrence::kind)
                            .toList());
        }
    }

    @Test
    void laterMemberInitializationFailureRollsBackEveryMarkerAndPublication()
            throws Exception {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ClosureInvocationInput input = staticRing(engine, true)
                    .admission();

            // when
            ContractsClosureAdmissionReceipt rejected = publicEngine
                    .admitContractsClosure(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .NOT_PUBLISHED,
                    rejected.publicationOutcome());
            assertTrue(rejected.attempt().isComplete());
            ClosureProcessResult result = rejected.attempt().processResult();
            assertEquals(ProcessorStatus.RUNTIME_FATAL,
                    result.status());
            assertFalse(result.commits());
            assertTrue(result.rollbackToInput());
            assertEquals(result.inputClosureIdentity(),
                    result.outputClosureIdentity());
            assertTrue(result.publicEvents().isEmpty());
            for (ResultingDocument document : result.resultingDocuments()) {
                assertFalse(document.initialized());
                assertEquals(document.beforeBlueId(), document.afterBlueId());
                assertFalse(hasInitializedMarker(document.document()));
            }

            ClosureImplementationEvidence evidence = engine
                    .contractsClosureAdmissionAdapter()
                    .lastExecutionEvidence().orElseThrow();
            assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                    result.diagnostic().category());
            assertNull(evidence.nonConformanceCode());
            assertEquals(List.of(
                            A.value(), A.value(),
                            B.value(), B.value(),
                            C.value(), C.value()),
                    workTargets(evidence));
            assertFalse(evidence.tentativeFinalizations().isEmpty(),
                    "earlier initialization work must finalize tentatively "
                            + "before the later runtime failure");
            assertTrue(evidence.complete());

            assertEquals(0, engine.documentCount());
            InMemoryDocumentStore.PublicationSnapshot publication = engine
                    .documents().publicationSnapshot();
            assertTrue(publication.documentHeads().isEmpty());
            assertTrue(publication.occurrenceInventory().rows().isEmpty());
            assertTrue(publication.componentIndex().documents().isEmpty());
            assertTrue(publication.componentStates().isEmpty());
            assertTrue(publication.closureSubscriptions().states().isEmpty());
            assertTrue(publication.outbox().isEmpty());
            assertTrue(publication.checkpointEvidence().isEmpty());
            assertTrue(publication.publicationReceipts().isEmpty());
            assertTrue(publication.admissionReceipts().isEmpty());
            assertTrue(publication.closurePublicationReceipts().isEmpty());
            assertTrue(publication.graphGenerations().documents().isEmpty());
            assertEquals(0L,
                    publication.occurrenceInventoryGeneration());
            assertEquals(0L, publication.componentIndexGeneration());
            assertEquals(0, engine.routeRowCount());
            assertEquals(0L, publicEngine.metrics().journalEntryCount());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P6.late-initialization-failure",
                        engine,
                        result,
                        null,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "inputInvocationIdentity",
                                input.invocationIdentity(),
                                "publicationOutcome",
                                rejected.publicationOutcome(),
                                "durableDocumentCount",
                                engine.documentCount()));
            }
        }
    }

    @Test
    void cClo08BoundedCompatibilityPreservesHistoricalFailClosedEvidence()
            throws Exception {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            ClosureInvocationInput input = cClo08ShapedAdmission(engine);

            // then
            assertEquals(1L, input.snapshot().occurrences().stream()
                    .filter(ManagedOccurrenceBinding::active).count());
            assertEquals(1L, input.snapshot().occurrences().stream()
                    .filter(binding -> !binding.active()).count());
            assertEquals(2, input.snapshot().components().size());
            assertTrue(input.snapshot().components().stream()
                    .allMatch(component -> component.kind()
                            == ComponentKind.ACYCLIC));

            /*
             * This package-private bounded-compatibility lane preserves the
             * historical C-CLO-08 fail-closed evidence. It does not exercise
             * normal public admission: the public lane runs the complete
             * lifecycle queue and reaches the distinct managed-occurrence
             * binding boundary covered by the dynamic-topology proofs.
             */
            ContractsClosureAdmissionReceipt unavailable = engine
                    .contractsClosureAdmissionAdapter()
                    .admitAndPublishBoundedCompatibility(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            ExternalOrderKey.of(List.of(
                                    BigInteger.ZERO,
                                    "contracts-admission",
                                    input.invocationIdentity())));

            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .NOT_PUBLISHED,
                    unavailable.publicationOutcome());
            assertTrue(unavailable.publicationIdentity().startsWith(
                    "coordination-contracts-closure-admission-bounded-"
                            + "compatibility-v1:sha256:"));
            assertTrue(unavailable.attempt().isComplete());
            ClosureProcessResult result = unavailable.attempt()
                    .processResult();
            assertEquals(ProcessorStatus.RUNTIME_FATAL,
                    result.status());
            assertFalse(result.commits());
            assertTrue(result.rollbackToInput());
            assertTrue(result.publicEvents().isEmpty());
            assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                    result.diagnostic().category());
            ClosureImplementationEvidence evidence = engine
                    .contractsClosureAdmissionAdapter()
                    .lastExecutionEvidence().orElseThrow();
            assertNull(evidence.nonConformanceCode());
            assertEquals(List.of(A.value(), A.value()),
                    workTargets(evidence));
            assertTrue(evidence.complete());
            assertEquals(0, engine.documentCount());
            assertTrue(engine.documents().publicationSnapshot()
                    .admissionReceipts().isEmpty());
            assertEquals(0, engine.routeRowCount());
            assertEquals(0L, publicEngine.metrics().journalEntryCount(),
                    "ADMIT_CLOSURE must not fabricate a Timeline Entry");
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P6.c-clo-08-bounded-compatibility",
                        engine,
                        result,
                        null,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "inputInvocationIdentity",
                                input.invocationIdentity(),
                                "publicationOutcome",
                                unavailable.publicationOutcome(),
                                "activeInputOccurrences", 1,
                                "inactiveInputOccurrences", 1,
                                "admissionLane",
                                "BOUNDED_COMPATIBILITY",
                                "publicApiExercised", false,
                                "timelineEntryCount",
                                publicEngine.metrics().journalEntryCount()));
            }
        }
    }

    private static DynamicFailureEvidence runDynamic(Variant variant)
            throws Exception {
        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ClosureInvocationInput input = dynamicAdmission(engine, variant);
            assertEquals(DYNAMIC_PATHS, input.snapshot().occurrences().stream()
                    .filter(binding -> !binding.active())
                    .map(ManagedOccurrenceBinding::sourcePath)
                    .collect(java.util.stream.Collectors.toSet()));
            assertEquals(4L, input.snapshot().occurrences().stream()
                    .filter(ManagedOccurrenceBinding::active).count());
            assertEquals(1, input.snapshot().components().size());
            assertEquals(ComponentKind.CYCLIC,
                    input.snapshot().components().get(0).kind());

            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .NOT_PUBLISHED,
                    admitted.publicationOutcome());
            assertTrue(admitted.attempt().isComplete());
            ClosureProcessResult result = admitted.attempt().processResult();
            assertEquals(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    result.status());
            assertFalse(result.commits());
            assertTrue(result.rollbackToInput());
            assertEquals(result.inputClosureIdentity(),
                    result.outputClosureIdentity());
            assertEquals(ProcessorErrorCategory
                            .ManagedOccurrenceBindingMissing,
                    result.diagnostic().category());
            assertTrue(result.graphChanges().isEmpty());
            assertTrue(result.publicEvents().isEmpty());
            for (ResultingDocument document : result.resultingDocuments()) {
                assertFalse(document.initialized());
                assertEquals(document.beforeBlueId(), document.afterBlueId());
                assertFalse(hasInitializedMarker(document.document()));
            }

            ClosureImplementationEvidence evidence = engine
                    .contractsClosureAdmissionAdapter()
                    .lastExecutionEvidence().orElseThrow();
            assertTrue(evidence.complete());
            assertNull(evidence.nonConformanceCode());
            assertEquals(List.of(A.value(), A.value()),
                    workTargets(evidence));
            assertFalse(evidence.tentativeFinalizations().isEmpty(),
                    "the first topology patch was staged before the "
                            + "managed-occurrence binding boundary rejected "
                            + "it");
            assertEquals(0, engine.documentCount());
            assertTrue(engine.documents().publicationSnapshot()
                    .admissionReceipts().isEmpty());
            assertEquals(0, engine.routeRowCount());
            assertEquals(0L, publicEngine.metrics().journalEntryCount());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P6.dynamic-topology-" + variant.name(),
                        engine,
                        result,
                        null,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "inputInvocationIdentity",
                                input.invocationIdentity(),
                                "publicationOutcome",
                                admitted.publicationOutcome(),
                                "inactiveInputPaths", DYNAMIC_PATHS.stream()
                                        .sorted()
                                        .toList(),
                                "durableDocumentCount",
                                engine.documentCount()));
            }

            return new DynamicFailureEvidence(
                    input.invocationIdentity(),
                    result.outputClosureIdentity(),
                    result.status(),
                    result.diagnostic().category(),
                    workTargets(evidence),
                    evidence.workTrace().stream()
                            .map(ClosureWorkOccurrence::kind)
                            .toList());
        }
    }

    private static Contracts10ScenarioBuilder staticRing(
            DefaultCoordinationEngine engine,
            boolean failOnLastMember) {
        return staticRing(engine, failOnLastMember, Variant.DECLARED);
    }

    private static Contracts10ScenarioBuilder staticRing(
            DefaultCoordinationEngine engine,
            boolean failOnLastMember,
            Variant variant) {
        Contracts10ScenarioBuilder builder =
                new Contracts10ScenarioBuilder(engine);
        if (variant == Variant.DECLARED) {
            builder.document(A, initializationDocument(A, false))
                    .document(B, initializationDocument(B, false))
                    .document(C, initializationDocument(
                            C, failOnLastMember));
        } else {
            builder.document(C, initializationDocument(
                            C, failOnLastMember))
                    .document(B, initializationDocument(B, false))
                    .document(A, initializationDocument(A, false))
                    .occurrenceOrder(
                            Contracts10ScenarioBuilder.OccurrenceOrder
                                    .REVERSED);
        }
        return builder
                .processEmbeddedPath(A, "/b", B)
                .processEmbeddedPath(B, "/c", C)
                .processEmbeddedPath(C, "/a", A)
                .publicRoot(A)
                .expectedComponent(A, B, C)
                .admissionLabel("coordination-static-three-init-ring");
    }

    private static ClosureInvocationInput dynamicAdmission(
            DefaultCoordinationEngine engine,
            Variant variant) {
        Contracts10ScenarioBuilder builder = new Contracts10ScenarioBuilder(
                engine);
        if (variant == Variant.DECLARED) {
            builder.document(A, dynamicSource())
                    .document(B, initializationDocument(B, false))
                    .document(C, initializationDocument(C, false));
        } else {
            builder.document(C, initializationDocument(C, false))
                    .document(B, initializationDocument(B, false))
                    .document(A, dynamicSource())
                    .occurrenceOrder(
                            Contracts10ScenarioBuilder.OccurrenceOrder
                                    .REVERSED);
        }
        Contracts10ScenarioBuilder.Scenario full = builder
                .processEmbeddedPath(A, "/seeds/b", B)
                .processEmbeddedPath(A, "/seeds/c", C)
                .processEmbeddedPath(B, "/back/a", A)
                .processEmbeddedPath(C, "/back/a", A)
                .processEmbeddedPath(A, "/reciprocal", B)
                .processEmbeddedCollectionMember(
                        A, "/members", "b", B)
                .processEmbeddedCollectionMember(
                        A, "/members", "c", C)
                .publicRoot(A)
                .expectedComponent(A, B, C)
                .admissionLabel("coordination-init-activation-in-cycle")
                .scenario();
        return withInactivePaths(
                engine,
                full,
                variant == Variant.DECLARED
                        ? MEMBERS
                        : List.of(C, B, A),
                DYNAMIC_PATHS,
                A,
                "coordination-init-activation-in-cycle");
    }

    private static ClosureInvocationInput cClo08ShapedAdmission(
            DefaultCoordinationEngine engine) {
        Contracts10ScenarioBuilder.Scenario full =
                new Contracts10ScenarioBuilder(engine)
                        .document(A, cClo08Source())
                        .document(B, initializationDocument(B, false))
                        .processEmbeddedPath(A, "/b", B)
                        .processEmbeddedPath(B, "/a", A)
                        .publicRoot(A)
                        .expectedComponent(A, B)
                        .admissionLabel("c-clo-08-public-composition")
                        .scenario();
        return withInactivePaths(
                engine,
                full,
                List.of(A, B),
                Set.of("/b"),
                A,
                "c-clo-08-public-composition");
    }

    private static ClosureInvocationInput withInactivePaths(
            DefaultCoordinationEngine engine,
            Contracts10ScenarioBuilder.Scenario full,
            List<DocumentId> members,
            Set<String> inactivePaths,
            DocumentId publicRoot,
            String label) {
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                bodies = new LinkedHashMap<>();
        for (DocumentId member : members) {
            Node body = full.authoredDocument(member);
            if (member.equals(publicRoot)) {
                removeInitialDynamicValues(body, inactivePaths);
            }
            bodies.put(closureId(member), body);
        }

        List<ManagedOccurrenceBinding> provisionalRows = full.bindings()
                .stream()
                .map(binding -> ManagedOccurrenceBinding.derived(
                        binding.bindingPolicyIdentity(),
                        binding.sourceDocumentId(),
                        binding.sourceAddress(),
                        binding.targetDocumentId(),
                        binding.expectedTargetBlueId(),
                        !isInactive(binding, publicRoot, inactivePaths),
                        null))
                .sorted()
                .toList();
        List<blue.language.processor.closure.DocumentId> closureMembers =
                members.stream().map(
                        ContractsPublicInitializationTopologyTest::closureId)
                        .toList();
        ManagedDocumentGraph provisionalGraph =
                ManagedDocumentGraph.fromBindings(
                        closureMembers, provisionalRows);
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = new LinkedHashMap<>();
        closureMembers.forEach(member -> generations.put(member, 1L));
        ComponentFinalizationKernel kernel =
                new ComponentFinalizationKernel();
        ComponentFinalizationResult preliminary = kernel
                .finalizeComponents(new ComponentFinalizationInput(
                        provisionalGraph,
                        generations,
                        bodies,
                        provisionalRows));

        List<ManagedOccurrenceBinding> exactRows = provisionalRows.stream()
                .map(binding -> ManagedOccurrenceBinding.derived(
                        binding.bindingPolicyIdentity(),
                        binding.sourceDocumentId(),
                        binding.sourceAddress(),
                        binding.targetDocumentId(),
                        preliminary.document(binding.targetDocumentId())
                                .blueId(),
                        binding.active(),
                        null))
                .sorted()
                .toList();
        ManagedDocumentGraph exactGraph = ManagedDocumentGraph.fromBindings(
                closureMembers, exactRows);
        ComponentFinalizationResult exact = kernel.finalizeComponents(
                new ComponentFinalizationInput(
                        exactGraph, generations, bodies, exactRows));

        ArrayList<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (FinalizedDocumentEvidence document
                : exact.documents().values()) {
            documents.add(new ManagedDocumentSnapshot(
                    document.documentId(),
                    document.blueId(),
                    document.document(),
                    false,
                    false,
                    document.documentId().equals(closureId(publicRoot)),
                    0L,
                    document.componentGeneration()));
        }
        List<ManagedOccurrenceBinding> rows = exact.finalizedGraph()
                .bindings();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        1L,
                        documents,
                        rows,
                        exact.components().stream()
                                .map(FinalizedComponentEvidence::component)
                                .toList(),
                        List.of(closureId(publicRoot)));
        return ClosureEvidenceFactory.admitClosure(
                snapshot,
                ClosureEvidenceFactory.admissionCause(
                        AdmissionKind.TOP_LEVEL_ADMISSION,
                        label,
                        null,
                        null,
                        ADMISSION_POLICY),
                null,
                engine.contractsClosureAdmissionAdapter().executionPolicy(),
                engine.contractsClosureAdmissionAdapter().environment());
    }

    private static boolean isInactive(
            ManagedOccurrenceBinding binding,
            DocumentId source,
            Set<String> inactivePaths) {
        return binding.sourceDocumentId().value().equals(source.value())
                && inactivePaths.contains(binding.sourcePath());
    }

    private static void removeInitialDynamicValues(
            Node body,
            Set<String> inactivePaths) {
        if (inactivePaths.contains("/reciprocal")) {
            body.getProperties().remove("reciprocal");
        }
        if (inactivePaths.stream().anyMatch(
                path -> path.startsWith("/members/"))) {
            body.getProperties().remove("members");
        }
        if (inactivePaths.contains("/b")) {
            body.getProperties().remove("b");
        }
    }

    private static String initializationDocument(
            DocumentId documentId,
            boolean failAtRuntime) {
        if (failAtRuntime) {
            return """
                    documentId: %s
                    initializationCount: 0
                    contracts:
                      lifecycle:
                        type:
                          blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo
                        order: 0
                      initialize:
                        type: Coordination/Sequential Workflow
                        channel: lifecycle
                        event:
                          type:
                            blueId: Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C
                        order: 0
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange:
                                  op: replace
                                  path: /initializationCount
                                  val: {$add: [$document: /initializationCount, 1]}
                              - $appendChange:
                                  op: remove
                                  path: /missing-runtime-failure-target
                              - $return: true
                    """.formatted(documentId.value());
        }
        return """
                documentId: %s
                initializationCount: 0
                contracts:
                  lifecycle:
                    type:
                      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo
                    order: 0
                  initialize:
                    type: Coordination/Sequential Workflow
                    channel: lifecycle
                    event:
                      type:
                        blueId: Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C
                    order: 0
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /initializationCount
                              val: {$add: [$document: /initializationCount, 1]}
                          - $return: true
                """.formatted(documentId.value());
    }

    private static String dynamicSource() {
        return """
                documentId: init-topology-a
                contracts:
                  lifecycle:
                    type:
                      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo
                    order: 0
                  initializeTopology:
                    type: Coordination/Sequential Workflow
                    channel: lifecycle
                    event:
                      type:
                        blueId: Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C
                    order: 0
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /members
                              val:
                                b: {$document: /seeds/b}
                                c: {$document: /seeds/c}
                          - $appendChange:
                              op: add
                              path: /reciprocal
                              val: {$document: /seeds/b}
                          - $return: true
                """;
    }

    private static String cClo08Source() {
        return """
                documentId: init-topology-a
                contracts:
                  lifecycle:
                    type:
                      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo
                    order: 0
                  onInit:
                    type: Coordination/Sequential Workflow
                    channel: lifecycle
                    event:
                      type:
                        blueId: Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C
                    order: 0
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: c-clo-08-public-event-is-not-a-patch
                          - $return: true
                """;
    }

    private static boolean hasInitializedMarker(Node document) {
        Node contracts = document.getContracts();
        return contracts != null
                && contracts.getProperties() != null
                && contracts.getProperties().containsKey(
                        ProcessorContractConstants.KEY_INITIALIZED);
    }

    private static long integer(Node document, String path) {
        Node value = blue.language.model.NodePathEditor.getOrNull(
                document, path);
        assertNotNull(value, path);
        Object scalar = value.getValue();
        if (scalar instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        return ((Number) scalar).longValue();
    }

    private static List<String> workTargets(
            ClosureImplementationEvidence evidence) {
        return evidence.workTrace().stream()
                .map(work -> work.targetDocumentId().value())
                .toList();
    }

    private static List<String> closureMemberValues() {
        return MEMBERS.stream().map(DocumentId::value).toList();
    }

    private static blue.language.processor.closure.DocumentId closureId(
            DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    private static CoordinationEngine engine(Set<DocumentId> roots) {
        return CoordinationEngine.inMemoryContracts10(
                BundledContracts10Release.configuration(roots));
    }

    private enum Variant {
        DECLARED,
        REVERSED
    }

    private record StaticOrderEvidence(
            String outputClosureIdentity,
            String componentStateIdentity,
            List<String> documentBlueIds,
            List<String> workTargets,
            List<WorkKind> workKinds) {
    }

    private record DynamicFailureEvidence(
            String invocationIdentity,
            String outputClosureIdentity,
            ProcessorStatus status,
            ProcessorErrorCategory diagnosticCategory,
            List<String> workTargets,
            List<WorkKind> workKinds) {
    }
}
