package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentDispatchOutcome;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicMemberFinalization;
import blue.language.identity.CyclicSetFinalization;
import blue.language.model.Node;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FinalizedComponentEvidence;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public append/drain acceptance for cyclic rollback and Root isolation. */
final class ContractsPublicLoopAndIsolationTest {
    private static final DocumentId A = DocumentId.of("loop-a");
    private static final DocumentId B = DocumentId.of("loop-b");
    private static final String SHA_A = sha('a');
    private static final String SHA_B = sha('b');
    private static final long FIRST_EVENT_TIME = 1_800_000_000_000_001L;

    @Test
    void sameEventLoopRollbackIsIdenticalAcrossFreshEngineRuns() {
        // given

        LoopEvidence first = runLoopAttempt();

        // when
        LoopEvidence secondRun = runLoopAttempt();

        // then
        assertEquals(first, secondRun);
        assertTrue(first.admittedGasEntries() > 0);
        assertTrue(first.rejectedWorkOrdinal() > 0L);
        assertEquals("SHARED", first.applicableCap());
    }

    @Test
    void disconnectedPublicRootsCommitAndRollbackWithoutCrossRootOvertake() {
        // given

        DocumentId success = DocumentId.of("a-success");
        DocumentId failure = DocumentId.of("z-failure");
        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(success, failure));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            disconnectedRootsAdmission(
                                    engine, success, failure),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());

            Timeline timeline = publicEngine.registerTimeline(
                    "shared/isolation", "alice");
            TimelineEntry first = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("advance", "ownerChannel", "amount: 1"),
                    FIRST_EVENT_TIME);
            TimelineEntry second = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("advance", "ownerChannel", "amount: 2"),
                    FIRST_EVENT_TIME + 1L);

            ProcessingDrainReceipt drained = publicEngine.drain();

            assertTrue(drained.quiescent());
            assertFalse(drained.paused());
            assertEquals(List.of(first, second), drained.processedEntries());
            assertEquals(2L, drained.committedProcessTransitions());
            assertEquals(List.of(success), drained.outcomesFor(first.blueId())
                    .stream()
                    .map(DocumentDispatchOutcome::documentId)
                    .toList());
            assertEquals(List.of(success), drained.outcomesFor(second.blueId())
                    .stream()
                    .map(DocumentDispatchOutcome::documentId)
                    .toList());
            assertEquals(List.of(1L, 2L), drained.outcomes().stream()
                    .map(outcome -> outcome.revision()
                            .rootApplicationOrder())
                    .toList());
            assertEquals(BigInteger.valueOf(3L), publicEngine
                    .document(success).valueAt("/counter").copyNode()
                    .getValue());
            assertEquals(2L, publicEngine.document(success).epoch());
            assertEquals(BigInteger.ZERO, publicEngine.document(failure)
                    .valueAt("/counter").copyNode().getValue());
            assertEquals(0L, publicEngine.document(failure).epoch());

            List<ContractsClosurePublicationReceipt> failedReceipts = engine
                    .documents().publicationSnapshot()
                    .closurePublicationReceipts().values().stream()
                    .filter(receipt -> receipt.documentIds()
                            .equals(List.of(failure)))
                    .toList();
            assertEquals(2, failedReceipts.size());
            assertTrue(failedReceipts.stream().allMatch(receipt ->
                    receipt.attempt().processResult().status()
                            == ProcessorStatus.RUNTIME_FATAL
                            && receipt.attempt().processResult()
                                    .rollbackToInput()));
            assertEquals(1, failedReceipts.stream()
                    .map(receipt -> receipt.attempt().processResult()
                            .diagnostic().category())
                    .distinct()
                    .count());
        }
    }

    private static LoopEvidence runLoopAttempt() {
        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));
        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            loopAdmission(engine),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            String beforeA = publicEngine.document(A).blueId();
            String beforeB = publicEngine.document(B).blueId();

            Timeline timeline = publicEngine.registerTimeline(
                    "loop/alice", "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("startLoop", "source", "{}"),
                    FIRST_EVENT_TIME);
            assertEquals(1, publicEngine.routeTargetCount(entry));
            ProcessingDrainReceipt drained = publicEngine.drain();

            assertTrue(drained.quiescent());
            assertFalse(drained.paused());
            assertEquals(List.of(entry), drained.processedEntries());
            assertTrue(drained.outcomes().isEmpty());
            assertEquals(0L, drained.committedProcessTransitions());
            assertEquals(beforeA, publicEngine.document(A).blueId());
            assertEquals(beforeB, publicEngine.document(B).blueId());
            assertEquals(0L, publicEngine.document(A).epoch());
            assertEquals(0L, publicEngine.document(B).epoch());

            List<ContractsClosurePublicationReceipt> durableReceipts = engine
                    .documents().publicationSnapshot()
                    .closurePublicationReceipts().values().stream()
                    .filter(receipt -> receipt.documentIds()
                            .equals(List.of(A, B)))
                    .toList();
            assertEquals(1, durableReceipts.size());
            ClosureAttemptResult durableAttempt = durableReceipts.get(0)
                    .attempt();
            ClosureProcessResult result = durableAttempt.processResult();
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    result.status());
            assertTrue(result.rollbackToInput());
            assertEquals(result.inputClosureIdentity(),
                    result.outputClosureIdentity());
            assertNotNull(result.rejectedWorkOccurrence());
            assertNotNull(result.rejectedCharge());

            // A gas failure is a durable non-commit feeder disposition. The
            // second engine run above proves determinism, not durable retry.
            ProcessingDrainReceipt afterTerminalFailure = publicEngine.drain();
            assertTrue(afterTerminalFailure.quiescent());
            assertFalse(afterTerminalFailure.paused());
            assertTrue(afterTerminalFailure.processedEntries().isEmpty());
            assertTrue(afterTerminalFailure.outcomes().isEmpty());
            assertEquals(0L,
                    afterTerminalFailure.committedProcessTransitions());
            assertEquals(1, publicEngine.metrics().journalEntryCount());
            assertEquals(beforeA, publicEngine.document(A).blueId());
            assertEquals(beforeB, publicEngine.document(B).blueId());

            return new LoopEvidence(
                    entry.blueId(),
                    result.invocationIdentity(),
                    result.inputClosureIdentity(),
                    result.outputClosureIdentity(),
                    result.totalGas(),
                    result.gasTrace().size(),
                    result.gasTraceIdentity(),
                    result.rejectedWorkOccurrence().ordinal(),
                    result.rejectedWorkOccurrence().workIdentity(),
                    result.rejectedCharge().rejectedChargeIdentity(),
                    result.rejectedCharge().counter(),
                    result.rejectedCharge().remainingBeforeCharge(),
                    result.rejectedCharge().applicableCap().kind().name(),
                    beforeA,
                    beforeB);
        }
    }

    private static ClosureInvocationInput loopAdmission(
            DefaultCoordinationEngine engine) {
        Node placeholderA = engine.exactValue(loopDocument(
                A, "/b", "fromB"))
                .copyNode()
                .properties("b", new Node().blueId("this#1"));
        Node placeholderB = engine.exactValue(loopDocument(
                B, "/a", "fromA"))
                .copyNode()
                .properties("a", new Node().blueId("this#0"));
        return cyclicAdmission(
                engine, A, B, placeholderA, placeholderB, List.of(A));
    }

    private static String loopDocument(
            DocumentId documentId,
            String peerPath,
            String channelKey) {
        String externalContracts = documentId.equals(A)
                ? """
                  source:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: loop/alice
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  startLoop:
                    type: Coordination/Sequential Workflow Operation
                    channel: source
                    request: {}
                    steps:
                      - type: Coordination/Trigger Event
                        event:
                          type: Coordination/Event
                          kind: LOOP
                  """.indent(2).stripTrailing()
                : "";
        return """
                documentId: %s
                memberIdentity: %s
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - %s
                  %s:
                    type:
                      blueId: %s
                    sourcePath: %s
                  onPeerLoop:
                    type: Coordination/Sequential Workflow
                    channel: %s
                    steps:
                      - type: Coordination/Trigger Event
                        event:
                          type: Coordination/Event
                          kind: LOOP
                %s
                """.formatted(
                documentId.value(),
                documentId.value(),
                RuntimeBlueIds.PROCESS_EMBEDDED,
                peerPath,
                channelKey,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                peerPath,
                channelKey,
                externalContracts);
    }

    private static ClosureInvocationInput disconnectedRootsAdmission(
            DefaultCoordinationEngine engine,
            DocumentId success,
            DocumentId failure) {
        ExactValue exactSuccess = engine.exactValue(successDocument(success));
        ExactValue exactFailure = engine.exactValue(failureDocument(failure));
        blue.language.processor.closure.DocumentId closureSuccess =
                new blue.language.processor.closure.DocumentId(
                        success.value());
        blue.language.processor.closure.DocumentId closureFailure =
                new blue.language.processor.closure.DocumentId(
                        failure.value());
        List<blue.language.processor.closure.DocumentId> ids = List.of(
                closureSuccess, closureFailure);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                ids, List.of());
        Map<blue.language.processor.closure.DocumentId, Long> generations =
                new LinkedHashMap<>();
        generations.put(closureSuccess, 1L);
        generations.put(closureFailure, 1L);
        Map<blue.language.processor.closure.DocumentId, Node> bodies =
                new LinkedHashMap<>();
        bodies.put(closureSuccess, exactSuccess.copyNode());
        bodies.put(closureFailure, exactFailure.copyNode());
        ComponentFinalizationResult exact = new ComponentFinalizationKernel()
                .finalizeComponents(new ComponentFinalizationInput(
                        graph, generations, bodies, List.of()));
        List<ManagedDocumentSnapshot> documents = List.of(
                managedSnapshot(exact, closureSuccess, true),
                managedSnapshot(exact, closureFailure, true));
        return admissionInput(
                engine,
                documents,
                exact.finalizedGraph().bindings(),
                exact.components(),
                List.of(closureSuccess, closureFailure),
                "coordination-public-root-isolation");
    }

    private static String successDocument(DocumentId documentId) {
        return """
                documentId: %s
                counter: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: shared/isolation
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  advance:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val:
                                $add:
                                  - $document: /counter
                                  - $binding: event/message/request/amount
                          - $return: true
                """.formatted(documentId.value());
    }

    private static String failureDocument(DocumentId documentId) {
        return """
                documentId: %s
                counter: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: shared/isolation
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  advance:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Trigger Event
                """.formatted(documentId.value());
    }

    private static ClosureInvocationInput cyclicAdmission(
            DefaultCoordinationEngine engine,
            DocumentId first,
            DocumentId second,
            Node placeholderA,
            Node placeholderB,
            List<DocumentId> publicRoots) {
        ContractsClosureAdmissionAdapter admission = engine
                .contractsClosureAdmissionAdapter();
        ClosureEnvironment environment = admission.environment();
        CyclicSetFinalization language = new CircularSetIdentityCalculator()
                .finalizeCyclicSet(Arrays.asList(placeholderA, placeholderB));
        List<String> canonicalBlueIds = language.membersInCanonicalOrder()
                .stream()
                .map(CyclicMemberFinalization::finalBlueId)
                .toList();
        Node bodyA = language.membersInInputOrder().get(0)
                .canonicalMemberBody();
        Node bodyB = language.membersInInputOrder().get(1)
                .canonicalMemberBody();
        materializeCanonicalReferences(bodyA, canonicalBlueIds);
        materializeCanonicalReferences(bodyB, canonicalBlueIds);
        String blueA = language.membersInInputOrder().get(0).finalBlueId();
        String blueB = language.membersInInputOrder().get(1).finalBlueId();
        blue.language.processor.closure.DocumentId closureA =
                new blue.language.processor.closure.DocumentId(first.value());
        blue.language.processor.closure.DocumentId closureB =
                new blue.language.processor.closure.DocumentId(second.value());
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>(List.of(
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        closureA,
                        ScopeAddress.embedded("/b", 1L),
                        closureB,
                        blueB,
                        true,
                        null),
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        closureB,
                        ScopeAddress.embedded("/a", 1L),
                        closureA,
                        blueA,
                        true,
                        null)));
        bindings.sort(null);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                List.of(closureA, closureB), bindings);
        Map<blue.language.processor.closure.DocumentId, Long> generations =
                new LinkedHashMap<>();
        generations.put(closureA, 1L);
        generations.put(closureB, 1L);
        Map<blue.language.processor.closure.DocumentId, Node> bodies =
                new LinkedHashMap<>();
        bodies.put(closureA, bodyA);
        bodies.put(closureB, bodyB);
        ComponentFinalizationResult exact = new ComponentFinalizationKernel()
                .finalizeComponents(new ComponentFinalizationInput(
                        graph, generations, bodies, bindings));
        List<ManagedDocumentSnapshot> documents = List.of(
                managedSnapshot(exact, closureA, publicRoots.contains(first)),
                managedSnapshot(exact, closureB, publicRoots.contains(second)));
        List<blue.language.processor.closure.DocumentId> closureRoots =
                publicRoots.stream()
                        .map(root -> new blue.language.processor.closure
                                .DocumentId(root.value()))
                        .toList();
        return admissionInput(
                engine,
                documents,
                exact.finalizedGraph().bindings(),
                exact.components(),
                closureRoots,
                "coordination-public-loop");
    }

    private static ManagedDocumentSnapshot managedSnapshot(
            ComponentFinalizationResult exact,
            blue.language.processor.closure.DocumentId documentId,
            boolean publicRoot) {
        return new ManagedDocumentSnapshot(
                documentId,
                exact.document(documentId).blueId(),
                exact.document(documentId).document(),
                false,
                false,
                publicRoot,
                0L,
                1L);
    }

    private static ClosureInvocationInput admissionInput(
            DefaultCoordinationEngine engine,
            List<ManagedDocumentSnapshot> documents,
            List<ManagedOccurrenceBinding> bindings,
            List<FinalizedComponentEvidence> components,
            List<blue.language.processor.closure.DocumentId> publicRoots,
            String causeIdentity) {
        ContractsClosureAdmissionAdapter admission = engine
                .contractsClosureAdmissionAdapter();
        ClosureEnvironment environment = admission.environment();
        ExecutionPolicy policy = admission.executionPolicy();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        1L,
                        documents,
                        bindings,
                        components.stream()
                                .map(FinalizedComponentEvidence::component)
                                .toList(),
                        publicRoots);
        return ClosureEvidenceFactory.admitClosure(
                snapshot,
                ClosureEvidenceFactory.admissionCause(
                        AdmissionKind.TOP_LEVEL_ADMISSION,
                        causeIdentity,
                        null,
                        null,
                        "contracts-top-level-admission-v1"),
                null,
                policy,
                environment);
    }

    private static void materializeCanonicalReferences(
            Node node,
            List<String> memberBlueIds) {
        if (node == null) {
            return;
        }
        String blueId = node.getBlueId();
        if (blueId != null && blueId.startsWith("this#")) {
            node.blueId(memberBlueIds.get(
                    Integer.parseInt(blueId.substring(5))));
        }
        materializeCanonicalReferences(node.getType(), memberBlueIds);
        materializeCanonicalReferences(node.getItemType(), memberBlueIds);
        materializeCanonicalReferences(node.getKeyType(), memberBlueIds);
        materializeCanonicalReferences(node.getValueType(), memberBlueIds);
        materializeCanonicalReferences(node.getBlue(), memberBlueIds);
        materializeCanonicalReferences(node.getContracts(), memberBlueIds);
        if (node.getItems() != null) {
            node.getItems().forEach(item -> materializeCanonicalReferences(
                    item, memberBlueIds));
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(child ->
                    materializeCanonicalReferences(child, memberBlueIds));
        }
    }

    private static String sha(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }

    private record LoopEvidence(
            String entryBlueId,
            String invocationIdentity,
            String inputClosureIdentity,
            String outputClosureIdentity,
            long totalGas,
            int admittedGasEntries,
            String gasTraceIdentity,
            long rejectedWorkOrdinal,
            String rejectedWorkIdentity,
            String rejectedChargeIdentity,
            String rejectedCounter,
            long remainingBeforeRejectedCharge,
            String applicableCap,
            String beforeA,
            String beforeB) {
    }
}
