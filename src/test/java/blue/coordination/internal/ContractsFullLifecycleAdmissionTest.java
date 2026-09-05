package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.SessionStatus;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coordination integration proofs for complete lifecycle admission. */
final class ContractsFullLifecycleAdmissionTest {
    private static final DocumentId A =
            DocumentId.of("full-lifecycle-admission-a");
    private static final DocumentId B =
            DocumentId.of("full-lifecycle-admission-b");

    @Test
    void publicRootInitializationEventReactsLocallyAndPublishes()
            throws Exception {
        // given
        try (CoordinationEngine publicEngine = engine()) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder scenario =
                    new Contracts10ScenarioBuilder(engine)
                            .document(A, localEventRoot(false))
                            .publicRoot(A)
                            .expectedComponent(A)
                            .admissionLabel(
                                    "coordination-full-lifecycle-local-event");

            // when
            ContractsClosureAdmissionReceipt admitted = scenario
                    .admitTo(publicEngine).admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            ClosureProcessResult result = admitted.attempt().processResult();
            assertEquals(ProcessorStatus.SUCCESS, result.status());
            assertEquals(1L, integer(
                    publicEngine.document(A).current().copyNode(),
                    "/reactionCount"));
            assertEquals(1, result.publicEvents().size());
            assertEquals("Phase2/Initialization X",
                    result.publicEvents().get(0).event()
                            .getAsText("/kind"));
        }
    }

    @Test
    void twoEqualInitializationEventValuesRemainTwoOccurrences()
            throws Exception {
        // given
        try (CoordinationEngine publicEngine = engine()) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder scenario =
                    new Contracts10ScenarioBuilder(engine)
                            .document(A, localEventRoot(true))
                            .publicRoot(A)
                            .expectedComponent(A)
                            .admissionLabel(
                                    "coordination-full-lifecycle-duplicates");

            // when
            ContractsClosureAdmissionReceipt admitted = scenario
                    .admitTo(publicEngine).admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            ClosureProcessResult result = admitted.attempt().processResult();
            assertEquals(ProcessorStatus.SUCCESS, result.status());
            assertEquals(2L, integer(
                    publicEngine.document(A).current().copyNode(),
                    "/reactionCount"));
            assertEquals(2, result.publicEvents().size());
            PublicEventOccurrence first = result.publicEvents().get(0);
            PublicEventOccurrence second = result.publicEvents().get(1);
            assertEquals(0L, first.eventOccurrenceOrdinal());
            assertEquals(1L, second.eventOccurrenceOrdinal());
            assertEquals(first.eventBlueId(), second.eventBlueId());
            assertNotEquals(first.eventOccurrenceIdentity(),
                    second.eventOccurrenceIdentity());
            assertEquals(result.publicEvents(),
                    engine.documents().publicationSnapshot().outbox());
        }
    }

    @Test
    void nonPublicInitializationEventReachesContainingPublicRoot()
            throws Exception {
        // given
        try (CoordinationEngine publicEngine = engine()) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder scenario = containingRoot(engine)
                    .admissionLabel(
                            "coordination-full-lifecycle-private-delivery");

            // when
            ContractsClosureAdmissionReceipt admitted = scenario
                    .admitTo(publicEngine).admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertEquals(1L, integer(
                    publicEngine.document(A).current().copyNode(),
                    "/observedChildEvents"));
            assertEquals(SessionStatus.READY,
                    publicEngine.document(B).status());
        }
    }

    @Test
    void nonPublicInitializationEventIsNotAutomaticallyPublic()
            throws Exception {
        // given
        try (CoordinationEngine publicEngine = engine()) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder scenario = containingRoot(engine)
                    .admissionLabel(
                            "coordination-full-lifecycle-private-outbox");

            // when
            ContractsClosureAdmissionReceipt admitted = scenario
                    .admitTo(publicEngine).admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            ClosureProcessResult result = admitted.attempt().processResult();
            assertTrue(result.publicEvents().isEmpty());
            assertTrue(engine.documents().publicationSnapshot()
                    .outbox().isEmpty());
        }
    }

    @Test
    void finiteInitializationCycleReachesQuiescence() throws Exception {
        // given
        try (CoordinationEngine publicEngine = engine()) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder scenario =
                    new Contracts10ScenarioBuilder(engine)
                            .document(A, finiteA())
                            .document(B, finiteB())
                            .processEmbeddedPath(A, "/b", B)
                            .processEmbeddedPath(B, "/a", A)
                            .publicRoot(A)
                            .expectedComponent(A, B)
                            .admissionLabel(
                                    "coordination-full-lifecycle-finite-cycle");

            // when
            ContractsClosureAdmissionReceipt admitted = scenario
                    .admitTo(publicEngine).admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            ClosureProcessResult result = admitted.attempt().processResult();
            assertEquals(ProcessorStatus.SUCCESS, result.status());
            assertTrue(result.commits());
            assertEquals("done", text(
                    publicEngine.document(A).current().copyNode(), "/phase"));
            assertEquals("relayed", text(
                    publicEngine.document(B).current().copyNode(), "/phase"));
            assertEquals(1, result.publicEvents().size(),
                    "only public A's X event enters the public outbox");
            assertNull(result.rejectedCharge(),
                    "the finite A-X-B-Y-A route must quiesce");
        }
    }

    @Test
    void infiniteInitializationCycleReachesExactGasBoundaryAndPublishesNothing()
            throws Exception {
        // given
        try (CoordinationEngine publicEngine = engine()) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder scenario =
                    new Contracts10ScenarioBuilder(engine)
                            .document(A, loopingA())
                            .document(B, loopingB())
                            .processEmbeddedPath(A, "/b", B)
                            .processEmbeddedPath(B, "/a", A)
                            .publicRoot(A)
                            .expectedComponent(A, B)
                            .admissionLabel(
                                    "coordination-full-lifecycle-gas-cycle");
            long exactLimit = engine.contractsClosureAdmissionAdapter()
                    .executionPolicy().sharedLimit();

            // when
            ContractsClosureAdmissionReceipt rejected = scenario
                    .admitTo(publicEngine).admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .NOT_PUBLISHED,
                    rejected.publicationOutcome());
            ClosureProcessResult result = rejected.attempt().processResult();
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    result.status());
            assertFalse(result.commits());
            assertTrue(result.rollbackToInput());
            assertNotNull(result.rejectedCharge());
            assertEquals(RejectedCharge.ApplicableCap.Kind.SHARED,
                    result.rejectedCharge().applicableCap().kind());
            assertEquals(exactLimit,
                    result.totalGas()
                            + result.rejectedCharge()
                                    .remainingBeforeCharge());
            assertTrue(result.rejectedCharge().subtotal()
                    > result.rejectedCharge().remainingBeforeCharge());
            assertTrue(result.publicEvents().isEmpty());
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
        }
    }

    @Test
    void admissionWritesNoExternalTimelineEntry() throws Exception {
        // given
        try (CoordinationEngine publicEngine = engine()) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder scenario =
                    new Contracts10ScenarioBuilder(engine)
                            .document(A, quietRoot())
                            .publicRoot(A)
                            .expectedComponent(A)
                            .admissionLabel(
                                    "coordination-full-lifecycle-no-entry");

            // when
            ContractsClosureAdmissionReceipt admitted = scenario
                    .admitTo(publicEngine).admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertEquals(0L, publicEngine.metrics().journalEntryCount());
            DocumentRevision initialization =
                    publicEngine.history(A).get(0);
            assertEquals(DocumentRevision.Kind.INITIALIZATION,
                    initialization.kind());
            assertTrue(initialization.sourceEntry().isEmpty());
        }
    }

    @Test
    void admissionWritesNoTimelineCheckpoint() throws Exception {
        // given
        try (CoordinationEngine publicEngine = engine()) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder scenario =
                    new Contracts10ScenarioBuilder(engine)
                            .document(A, quietRoot())
                            .publicRoot(A)
                            .expectedComponent(A)
                            .admissionLabel(
                                    "coordination-full-lifecycle-no-checkpoint");

            // when
            ContractsClosureAdmissionReceipt admitted = scenario
                    .admitTo(publicEngine).admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            ClosureProcessResult result = admitted.attempt().processResult();
            assertTrue(result.checkpointWrites().isEmpty());
            assertTrue(engine.documents().publicationSnapshot()
                    .checkpointEvidence().isEmpty());
            assertNull(NodePathEditor.getOrNull(
                    publicEngine.document(A).current().copyNode(),
                    "/contracts/checkpoint"));
        }
    }

    private static Contracts10ScenarioBuilder containingRoot(
            DefaultCoordinationEngine engine) {
        return new Contracts10ScenarioBuilder(engine)
                .document(A, containingA())
                .document(B, emittingB())
                .processEmbeddedPath(A, "/b", B)
                .publicRoot(A)
                .expectedComponent(B)
                .expectedComponent(A);
    }

    private static String localEventRoot(boolean duplicate) {
        String emissions = duplicate
                ? """
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Phase2/Initialization X
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Phase2/Initialization X
                        """
                : """
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Phase2/Initialization X
                        """;
        return """
                documentId: %s
                reactionCount: 0
                contracts:
                  lifecycle:
                    type:
                      blueId: %s
                    order: 0
                  initializedEvent:
                    type:
                      blueId: %s
                    event:
                      type: Coordination/Event
                      kind: Phase2/Initialization X
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
                %s
                          - $return: true
                  react:
                    type: Coordination/Sequential Workflow
                    channel: initializedEvent
                    event:
                      type: Coordination/Event
                      kind: Phase2/Initialization X
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /reactionCount
                              val: {$add: [{$document: /reactionCount}, 1]}
                          - $return: true
                """.formatted(
                A.value(),
                RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL,
                RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL,
                emissions.indent(8).stripTrailing());
    }

    private static String containingA() {
        return """
                documentId: %s
                observedChildEvents: 0
                contracts:
                  fromB:
                    type:
                      blueId: %s
                    sourcePath: /b
                    event:
                      type: Coordination/Event
                      kind: Phase2/Child Initialized
                  observeB:
                    type: Coordination/Sequential Workflow
                    channel: fromB
                    event:
                      type: Coordination/Event
                      kind: Phase2/Child Initialized
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedChildEvents
                              val: {$add: [{$document: /observedChildEvents}, 1]}
                          - $return: true
                """.formatted(A.value(), RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String emittingB() {
        return """
                documentId: %s
                contracts:
                  lifecycle:
                    type:
                      blueId: %s
                    order: 0
                  emit:
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
                              kind: Phase2/Child Initialized
                          - $return: true
                """.formatted(
                B.value(), RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL);
    }

    private static String finiteA() {
        return cycleA(false);
    }

    private static String loopingA() {
        return cycleA(true);
    }

    private static String cycleA(boolean loop) {
        String returnEvent = loop
                ? """
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Phase2/Cycle X
                        """
                : "";
        return """
                documentId: %s
                phase: initial
                contracts:
                  lifecycle:
                    type:
                      blueId: %s
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
                              path: /phase
                              val: started
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Phase2/Cycle X
                          - $return: true
                  fromB:
                    type:
                      blueId: %s
                    sourcePath: /b
                    event:
                      type: Coordination/Event
                      kind: Phase2/Cycle Y
                  finish:
                    type: Coordination/Sequential Workflow
                    channel: fromB
                    event:
                      type: Coordination/Event
                      kind: Phase2/Cycle Y
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: done
                %s
                          - $return: true
                """.formatted(
                A.value(),
                RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                returnEvent.indent(8).stripTrailing());
    }

    private static String finiteB() {
        return cycleB(false);
    }

    private static String loopingB() {
        return cycleB(true);
    }

    private static String cycleB(boolean loop) {
        String loopState = loop
                ? ""
                : """
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: relayed
                        """;
        return """
                documentId: %s
                phase: initial
                contracts:
                  fromA:
                    type:
                      blueId: %s
                    sourcePath: /a
                    event:
                      type: Coordination/Event
                      kind: Phase2/Cycle X
                  relay:
                    type: Coordination/Sequential Workflow
                    channel: fromA
                    event:
                      type: Coordination/Event
                      kind: Phase2/Cycle X
                    steps:
                      - type: Coordination/Compute
                        do:
                %s
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Phase2/Cycle Y
                          - $return: true
                """.formatted(
                B.value(),
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                loopState.indent(8).stripTrailing());
    }

    private static String quietRoot() {
        return """
                documentId: %s
                initializationCount: 0
                contracts:
                  lifecycle:
                    type:
                      blueId: %s
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
                              val: 1
                          - $return: true
                """.formatted(
                A.value(), RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL);
    }

    private static long integer(Node document, String path) {
        Node value = NodePathEditor.getOrNull(document, path);
        assertNotNull(value, path);
        Object scalar = value.getValue();
        if (scalar instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        return ((Number) scalar).longValue();
    }

    private static String text(Node document, String path) {
        return document.getAsText(path);
    }

    private static CoordinationEngine engine() {
        return CoordinationEngine.inMemoryContracts10(
                BundledContracts10Release.configuration(Set.of(A)));
    }
}
