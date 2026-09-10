package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.DocumentTransitionEvidence;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ResultingDocument;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Publication proofs for Contracts-owned checkpoint finalization churn. */
final class ContractsCheckpointSettlementPublicationTest {
    private static final DocumentId DRIVER =
            DocumentId.of("checkpoint-event-driver");
    private static final DocumentId SOURCE =
            DocumentId.of("checkpoint-event-source");
    private static final String TIMELINE = "checkpoint/events";
    private static final long EVENT_TIME = 1_900_000_000_100_001L;

    @Test
    void directTargetCannotUseIndirectRepresentationRebind() {
        // given
        try (CoordinationEngine publicEngine = engine(Set.of(DRIVER))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ContractsClosureAdmissionReceipt admitted =
                    new Contracts10ScenarioBuilder(engine)
                            .document(DRIVER, driverRoot())
                            .publicRoot(DRIVER)
                            .expectedComponent(DRIVER)
                            .admissionLabel("checkpoint-duplicate-events")
                            .admitTo(publicEngine).admissionReceipt();
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            String driverBefore = publicEngine.document(DRIVER).blueId();

            Timeline timeline = publicEngine.registerTimeline(
                    TIMELINE, "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("emit", "ownerChannel", "{}"),
                    EVENT_TIME);
            ContractsClosureAdapter.CohortInvocation invocation = engine
                    .contractsClosureAdapter()
                    .capture(entry)
                    .invocations().get(0);
            ContractsClosureAdapter.CapturedDocument capturedBefore =
                    invocation.documents().get(DRIVER);
            assertEquals(List.of(DRIVER),
                    invocation.publicationIdentityMembers());

            // when
            ProcessingDrainReceipt drained = publicEngine.drain();

            // then
            assertTrue(drained.quiescent());
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(1L, publicEngine.document(DRIVER).epoch());
            assertNotEquals(driverBefore, publicEngine.document(DRIVER).blueId(),
                    "the exact checkpoint field changes the direct Root body");

            DocumentRevision driver = publicEngine.history(DRIVER).get(1);
            assertEquals(DocumentRevision.Kind.TIMELINE_ENTRY, driver.kind());
            assertNotEquals(
                    driver.before().orElseThrow().blueId(),
                    driver.after().blueId());
            assertEquals(2, driver.emittedEvents().size());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            driver.emittedEvents().get(0)),
                    DirectBlueIdCalculator.calculateBlueId(
                            driver.emittedEvents().get(1)));

            ClosureProcessResult result = onlyProcessResult(engine);
            ResultingDocument contractsDriver = resulting(result, DRIVER);
            assertFalse(result.checkpointWrites().isEmpty());
            assertEquals(0L, contractsDriver.epoch(),
                    "checkpoint-only finalization retains Contracts work epoch");
            assertNotEquals(
                    contractsDriver.beforeBlueId(),
                    contractsDriver.afterBlueId());
            List<blue.language.processor.closure.ManagedRootEventOccurrence>
                    duplicates = transition(result, DRIVER)
                            .emittedRootEvents();
            assertEquals(2, duplicates.size());
            assertEquals(
                    duplicates.get(0).eventBlueId(),
                    duplicates.get(1).eventBlueId());
            assertNotEquals(
                    duplicates.get(0).occurrenceIdentity(),
                    duplicates.get(1).occurrenceIdentity());
            assertTrue(ContractsClosureAdapter
                    .isVerifiedCheckpointSettlementChange(
                            result,
                            DRIVER,
                            capturedBefore.head(),
                            contractsDriver,
                            transition(result, DRIVER)));
        }
    }

    @Test
    void indirectCheckpointSettlementPublishesOrdinaryRevisionAndPreservesDuplicateEvents() {
        // given
        try (CoordinationEngine publicEngine = engine(Set.of(DRIVER, SOURCE))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            admitIndirectFixture(publicEngine, engine);
            Timeline timeline = publicEngine.registerTimeline(
                    TIMELINE, "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("emitMatched", "ownerChannel", "{}"),
                    EVENT_TIME);
            ContractsClosureAdapter.CohortInvocation invocation = engine
                    .contractsClosureAdapter()
                    .capture(entry)
                    .invocations().get(0);
            ContractsClosureAdapter.CapturedDocument sourceBefore = invocation
                    .documents().get(SOURCE);
            int sourceHistoryBefore = publicEngine.history(SOURCE).size();
            assertEquals(List.of(DRIVER),
                    invocation.publicationIdentityMembers());
            assertTrue(invocation.members().contains(SOURCE));

            // when
            ProcessingDrainReceipt drained = publicEngine.drain();

            // then
            assertTrue(drained.quiescent());
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(sourceBefore.head().epoch() + 1L,
                    publicEngine.document(SOURCE).epoch());
            assertEquals(sourceHistoryBefore + 1,
                    publicEngine.history(SOURCE).size());
            DocumentRevision revision = publicEngine.history(SOURCE)
                    .get(sourceHistoryBefore);
            assertEquals(DocumentRevision.Kind.TIMELINE_ENTRY,
                    revision.kind());
            assertNotEquals(
                    revision.before().orElseThrow().blueId(),
                    revision.after().blueId());
            assertEquals(2, revision.emittedEvents().size());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            revision.emittedEvents().get(0)),
                    DirectBlueIdCalculator.calculateBlueId(
                            revision.emittedEvents().get(1)));

            ClosureProcessResult result = onlyProcessResult(engine);
            ResultingDocument contractsSource = resulting(result, SOURCE);
            ManagedDocumentTransitionReceipt transition = transition(
                    result, SOURCE);
            assertEquals(sourceBefore.head().epoch(), contractsSource.epoch(),
                    "checkpoint settlement retains the Contracts work epoch");
            assertNotEquals(
                    contractsSource.beforeBlueId(),
                    contractsSource.afterBlueId());
            assertTrue(ContractsClosureAdapter
                    .isVerifiedCheckpointSettlementChange(
                            result,
                            SOURCE,
                            sourceBefore.head(),
                            contractsSource,
                            transition));
            assertEquals(2, transition.emittedRootEvents().size());
            assertEquals(
                    transition.emittedRootEvents().get(0).eventBlueId(),
                    transition.emittedRootEvents().get(1).eventBlueId());
            assertNotEquals(
                    transition.emittedRootEvents().get(0)
                            .occurrenceIdentity(),
                    transition.emittedRootEvents().get(1)
                            .occurrenceIdentity());
        }
    }

    @Test
    void eventlessIndirectComponentRepresentationRebindRetainsCoordinationEpoch() {
        // given
        try (CoordinationEngine publicEngine = engine(Set.of(DRIVER, SOURCE))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            admitIndirectFixture(publicEngine, engine);
            Timeline timeline = publicEngine.registerTimeline(
                    TIMELINE, "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("emitUnmatched", "ownerChannel", "{}"),
                    EVENT_TIME);
            ContractsClosureAdapter.CohortInvocation invocation = engine
                    .contractsClosureAdapter()
                    .capture(entry)
                    .invocations().get(0);
            ContractsClosureAdapter.CapturedDocument sourceBefore = invocation
                    .documents().get(SOURCE);
            int sourceHistoryBefore = publicEngine.history(SOURCE).size();
            int sourceReceiptsBefore = engine.documents()
                    .managedEpochReceipts(SOURCE).size();
            String representationBefore = engine.documents().require(SOURCE)
                    .currentRepresentation().blueId();
            assertEquals(List.of(DRIVER),
                    invocation.publicationIdentityMembers());

            // when
            ProcessingDrainReceipt drained = publicEngine.drain();

            // then
            assertTrue(drained.quiescent());
            assertEquals(sourceBefore.head().epoch(),
                    publicEngine.document(SOURCE).epoch());
            assertEquals(sourceHistoryBefore,
                    publicEngine.history(SOURCE).size());
            assertEquals(sourceReceiptsBefore, engine.documents()
                    .managedEpochReceipts(SOURCE).size());
            assertNotEquals(representationBefore, engine.documents()
                    .require(SOURCE).currentRepresentation().blueId());

            ClosureProcessResult result = onlyProcessResult(engine);
            ResultingDocument contractsSource = resulting(result, SOURCE);
            ManagedDocumentTransitionReceipt transition = transition(
                    result, SOURCE);
            assertEquals(sourceBefore.head().epoch(), contractsSource.epoch());
            assertNotEquals(
                    contractsSource.beforeBlueId(),
                    contractsSource.afterBlueId());
            assertTrue(transition.emittedRootEvents().isEmpty());
            assertFalse(ContractsClosureAdapter
                    .isVerifiedCheckpointSettlementChange(
                            result,
                            SOURCE,
                            sourceBefore.head(),
                            contractsSource,
                            transition));
        }
    }

    @Test
    void malformedIndirectCheckpointSettlementPublishesNothing()
            throws ReflectiveOperationException {
        // given
        try (CoordinationEngine publicEngine = engine(Set.of(DRIVER, SOURCE))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            admitIndirectFixture(publicEngine, engine);
            Timeline timeline = publicEngine.registerTimeline(
                    TIMELINE, "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("emitMatched", "ownerChannel", "{}"),
                    EVENT_TIME);
            ContractsClosureAdapter adapter = engine
                    .contractsClosureAdapter();
            ContractsClosureAdapter.FrozenBatch batch = adapter.capture(entry);
            ContractsClosureAdapter.CohortInvocation invocation = batch
                    .invocations().get(0);
            InMemoryDocumentStore.PublicationSnapshot before = engine
                    .documents().publicationSnapshot();
            int driverHistoryBefore = publicEngine.history(DRIVER).size();
            int sourceHistoryBefore = publicEngine.history(SOURCE).size();
            int routeTargetsBefore = publicEngine.routeTargetCount(entry);

            Field contractsField = ContractsClosureAdapter.class
                    .getDeclaredField("contracts");
            contractsField.setAccessible(true);
            BlueClosureContracts contracts = (BlueClosureContracts)
                    contractsField.get(adapter);
            ClosureAttemptResult attempt = contracts.processClosure(
                    invocation.input());
            assertTrue(attempt.isComplete());
            ClosureProcessResult result = attempt.processResult();
            ResultingDocument contractsSource = resulting(result, SOURCE);
            ManagedDocumentTransitionReceipt transition = transition(
                    result, SOURCE);
            ContractsClosureAdapter.CapturedDocument sourceBefore = invocation
                    .documents().get(SOURCE);
            assertTrue(ContractsClosureAdapter
                    .isVerifiedCheckpointSettlementChange(
                            result,
                            SOURCE,
                            sourceBefore.head(),
                            contractsSource,
                            transition));
            ManagedSurfacePublicationEvidence surface =
                    ManagedSurfacePublicationEvidence.committed(
                            invocation, result);

            DocumentTransitionEvidence sourceEvidence = result
                    .documentTransitionEvidence().stream()
                    .filter(evidence -> evidence.documentId().value().equals(
                            SOURCE.value()))
                    .findFirst()
                    .orElseThrow();
            Field afterBoundary = DocumentTransitionEvidence.class
                    .getDeclaredField("afterDocumentBlueId");
            afterBoundary.setAccessible(true);
            afterBoundary.set(sourceEvidence, contractsSource.afterBlueId());
            assertFalse(ContractsClosureAdapter
                    .isVerifiedCheckpointSettlementChange(
                            result,
                            SOURCE,
                            sourceBefore.head(),
                            contractsSource,
                            transition));

            ContractsClosurePublicationReceipt receipt =
                    new ContractsClosurePublicationReceipt(
                            adapter.publicationIdentityFor(batch, invocation),
                            invocation.members(),
                            attempt,
                            0L,
                            surface);
            Method publish = ContractsClosureAdapter.class.getDeclaredMethod(
                    "publish",
                    ContractsClosureAdapter.FrozenBatch.class,
                    ContractsClosureAdapter.CohortInvocation.class,
                    ContractsClosurePublicationReceipt.class);
            publish.setAccessible(true);

            // when
            InvocationTargetException rejected = assertThrows(
                    InvocationTargetException.class,
                    () -> publish.invoke(adapter, batch, invocation, receipt));

            // then
            assertTrue(rejected.getCause() instanceof IllegalStateException);
            assertTrue(rejected.getCause().getMessage().contains(
                    "Process receipt result epoch/head transition is not "
                            + "fully staged"));
            assertEquals(before, engine.documents().publicationSnapshot());
            assertEquals(driverHistoryBefore,
                    publicEngine.history(DRIVER).size());
            assertEquals(sourceHistoryBefore,
                    publicEngine.history(SOURCE).size());
            assertEquals(routeTargetsBefore,
                    publicEngine.routeTargetCount(entry));
        }
    }

    @Test
    void sameEpochWorkStateChangeIsRejectedEvenWithCheckpointEvidence() {
        // given
        DocumentId documentId = DocumentId.of("checkpoint-state-change");
        try (CoordinationEngine publicEngine = engine(Set.of(documentId))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            new Contracts10ScenarioBuilder(engine)
                    .document(documentId, stateChangingRoot(documentId))
                    .publicRoot(documentId)
                    .expectedComponent(documentId)
                    .admissionLabel("checkpoint-state-change")
                    .admitTo(publicEngine);
            Timeline timeline = publicEngine.registerTimeline(
                    TIMELINE, "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("increment", "ownerChannel", "{}"),
                    EVENT_TIME);
            ContractsClosureAdapter.CapturedDocument before = engine
                    .contractsClosureAdapter()
                    .capture(entry)
                    .invocations().get(0)
                    .documents().get(documentId);

            // when
            publicEngine.drain();
            ClosureProcessResult result = onlyProcessResult(engine);
            ResultingDocument actual = resulting(result, documentId);
            assertFalse(result.checkpointWrites().isEmpty());
            assertTrue(result.documentTransitionEvidence().stream()
                    .filter(evidence -> evidence.documentId().value().equals(
                            documentId.value()))
                    .anyMatch(evidence -> !sameBoundary(evidence)));
            assertEquals(before.head().epoch() + 1L, actual.epoch());

            ResultingDocument forgedSameEpoch = new ResultingDocument(
                    actual.documentId(),
                    actual.beforeBlueId(),
                    actual.afterBlueId(),
                    actual.document(),
                    actual.initialized(),
                    actual.terminated(),
                    actual.publicRoot(),
                    before.head().epoch(),
                    actual.componentGeneration(),
                    actual.componentIdentity(),
                    actual.componentStateIdentity(),
                    actual.memberIndex());
            ManagedDocumentTransitionReceipt transition = transition(
                    result, documentId);

            // then
            assertThrows(
                    ContractsClosureAdapter.ProjectionUnavailableException.class,
                    () -> ContractsClosureAdapter.requiresDocumentPublication(
                            result, before, forgedSameEpoch, transition));
        }
    }

    private static boolean sameBoundary(DocumentTransitionEvidence evidence) {
        return evidence.beforeDocumentBlueId().equals(
                evidence.afterDocumentBlueId());
    }

    private static ClosureProcessResult onlyProcessResult(
            DefaultCoordinationEngine engine) {
        List<ContractsClosurePublicationReceipt> receipts = engine.documents()
                .publicationSnapshot()
                .closurePublicationReceipts()
                .values().stream()
                .toList();
        assertEquals(1, receipts.size());
        return receipts.get(0).attempt().processResult();
    }

    private static ResultingDocument resulting(
            ClosureProcessResult result,
            DocumentId documentId) {
        return result.resultingDocuments().stream()
                .filter(document -> document.documentId().value().equals(
                        documentId.value()))
                .findFirst()
                .orElseThrow();
    }

    private static ManagedDocumentTransitionReceipt transition(
            ClosureProcessResult result,
            DocumentId documentId) {
        return result.managedTransitionReceipts().stream()
                .filter(receipt -> receipt.documentId().value().equals(
                        documentId.value()))
                .findFirst()
                .orElseThrow();
    }

    private static CoordinationEngine engine(Set<DocumentId> publicRoots) {
        return CoordinationEngine.inMemoryContracts10(
                LegacyContracts10TestProfile.configuration(publicRoots));
    }

    private static void admitIndirectFixture(
            CoordinationEngine publicEngine,
            DefaultCoordinationEngine engine) {
        ContractsClosureAdmissionReceipt admitted =
                new Contracts10ScenarioBuilder(engine)
                        .document(DRIVER, indirectDriverRoot())
                        .document(SOURCE, indirectSourceRoot())
                        .processEmbeddedPath(SOURCE, "/peer", DRIVER)
                        .publicRoot(DRIVER)
                        .publicRoot(SOURCE)
                        .expectedComponent(DRIVER)
                        .expectedComponent(SOURCE)
                        .admissionLabel("indirect-checkpoint-settlement")
                        .admitTo(publicEngine).admissionReceipt();
        assertEquals(
                ContractsClosureAdmissionReceipt.PublicationOutcome.PUBLISHED,
                admitted.publicationOutcome());
    }

    private static String driverRoot() {
        return """
                documentId: %s
                phase: stable
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  emit:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Checkpoint/Equal Event
                              payload: equal
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Checkpoint/Equal Event
                              payload: equal
                          - $return: true
                """.formatted(DRIVER.value(), TIMELINE);
    }

    private static String stateChangingRoot(DocumentId documentId) {
        return """
                documentId: %s
                counter: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: 1
                          - $return: true
                """.formatted(documentId.value(), TIMELINE);
    }

    private static String indirectDriverRoot() {
        return """
                documentId: %s
                phase: stable
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  emitMatched:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Checkpoint/Matched Trigger
                          - $return: true
                  emitUnmatched:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Checkpoint/Unmatched Trigger
                          - $return: true
                """.formatted(DRIVER.value(), TIMELINE);
    }

    private static String indirectSourceRoot() {
        return """
                documentId: %s
                phase: stable
                contracts:
                  triggerFromDriver:
                    type: Embedded Node Channel
                    sourcePath: /peer
                    event:
                      type: Coordination/Event
                      kind: Checkpoint/Matched Trigger
                  emitDuplicates:
                    type: Coordination/Sequential Workflow
                    channel: triggerFromDriver
                    event:
                      type: Coordination/Event
                      kind: Checkpoint/Matched Trigger
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Checkpoint/Equal Indirect Event
                              payload: equal
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Checkpoint/Equal Indirect Event
                              payload: equal
                          - $return: true
                """.formatted(SOURCE.value());
    }
}
