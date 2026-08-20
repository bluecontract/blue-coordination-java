package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentSnapshot;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused mixed existing/new Contracts publication coverage. */
final class ContractsManagedDraftExpansionTest {
    private static final String ACTOR = "alice";
    private static final DocumentId HOST = DocumentId.of(
            "managed-expansion-host");
    private static final DocumentId DRAFT = DocumentId.of(
            "managed-expansion-draft");

    @Test
    void atomicAppendPublishesOrRollsBackEntryAndPlanTogether() {
        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/atomic")) {
            Timeline timeline = engine.timeline("managed/atomic", ACTOR);
            ExactValue target = engine.document(HOST).current();
            ExactValue draft = draft(engine);
            ExactValue request = engine.referenceRequest("draft", draft);
            Operation operation = Operation.exact(
                    "create", "ownerChannel", request)
                    .targeting(target, true);
            ContractsManagedDraftPlan plan = plan(
                    HOST,
                    target,
                    draft,
                    "draft",
                    "/orders/draft");
            long clockBefore = engine.logicalClockMicros();

            engine.failOnceAt(DefaultCoordinationEngine.FailurePoint
                    .AFTER_MANAGED_DRAFT_PLAN_REGISTERED);
            assertThrows(
                    DefaultCoordinationEngine.InjectedFailureException.class,
                    () -> engine.append(timeline, operation, plan));
            assertEquals(clockBefore, engine.logicalClockMicros());

            TimelineEntry appended = engine.append(
                    timeline, operation, plan);
            assertEquals(1L, appended.globalSequence());
            assertTrue(engine.contractsClosureAdapter()
                    .hasManagedDraftPlan(appended.blueId()));
        }
    }

    @Test
    void undeclaredManagedOccurrenceFailsBeforeConsumingJournalSequence() {
        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/preflight")) {
            Timeline timeline = engine.timeline("managed/preflight", ACTOR);
            ExactValue target = engine.document(HOST).current();
            ExactValue draft = draft(engine);
            ExactValue request = engine.referenceRequest("order", draft);
            Operation operation = Operation.exact(
                    "createOrder", "ownerChannel", request)
                    .targeting(target, true);

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> engine.append(
                            timeline,
                            operation,
                            plan(HOST, target, draft, "order",
                                    "/not-declared/order-1")));

            assertTrue(failure.getMessage().contains(
                    "must match exactly one effective Process Embedded"));
            TimelineEntry appended = engine.append(
                    timeline,
                    operation,
                    plan(HOST, target, draft, "order",
                            "/orders/order-1"));
            assertEquals(1L, appended.globalSequence());
            assertEquals(1L, appended.timelineSequence());
        }
    }

    @Test
    void emptyDirectSelectionRemainsOrdinaryAndCreatesNoDraftSession() {
        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/no-selection")) {
            Timeline timeline = engine.timeline(
                    "managed/no-selection", ACTOR);
            ExactValue target = engine.document(HOST).current();
            ExactValue draft = draft(engine);
            ExactValue request = engine.referenceRequest("draft", draft);
            TimelineEntry entry = engine.append(
                    timeline,
                    Operation.exact("missing", "missingChannel", request)
                            .targeting(target, true),
                    plan(HOST, target, draft, "draft",
                            "/orders/unselected"));

            ContractsClosureAdapter.FrozenBatch captured = engine
                    .contractsClosureAdapter().capture(entry);
            assertTrue(captured.invocations().isEmpty());
            ProcessingDrainReceipt drained = engine.drain();
            assertEquals(List.of(entry), drained.processedEntries());
            assertTrue(drained.contractsAttemptsFor(
                    entry.blueId()).isEmpty());
            assertTrue(engine.documents().find(DRAFT).isEmpty());
        }
    }

    @Test
    void terminalNonCommitLeavesEveryManagedDraftAbsent() {
        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/reject")) {
            Timeline timeline = engine.timeline(
                    "managed/reject", ACTOR);
            ExactValue target = engine.document(HOST).current();
            ExactValue draft = draft(engine);
            ExactValue request = engine.referenceRequest("order", draft);
            TimelineEntry entry = engine.append(
                    timeline,
                    Operation.exact(
                            "rejectCreate", "ownerChannel", request)
                            .targeting(target, true),
                    plan(HOST, target, draft, "order",
                            "/orders/rejected"));
            ContractsClosureAdapter adapter = engine
                    .contractsClosureAdapter();
            ContractsClosureAdapter.FrozenBatch batch = adapter.capture(entry);
            ContractsClosureAdapter.CohortInvocation invocation = batch
                    .invocations().get(0);
            InMemoryDocumentStore.DocumentHead before = engine.documents()
                    .publicationSnapshot().requireHead(HOST);

            ContractsClosureAdapter.CohortOutcome outcome = adapter
                    .executeAndPublish(batch, invocation);

            assertTrue(outcome.attempt().isComplete());
            assertEquals(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    outcome.attempt().processResult().status());
            assertEquals(ProcessorErrorCategory
                            .ManagedOccurrenceBindingMissing,
                    outcome.attempt().processResult().diagnostic()
                            .category());
            assertFalse(outcome.attempt().processResult().commits());
            assertFalse(outcome.published());
            assertEquals(Set.of(HOST, DRAFT),
                    Set.copyOf(outcome.members()));
            assertTrue(engine.documents().find(DRAFT).isEmpty());
            assertEquals(before, engine.documents().publicationSnapshot()
                    .requireHead(HOST));
            assertTrue(engine.documents().occurrenceInventory().rows()
                    .stream().noneMatch(row -> row.targetDocumentId().value()
                            .equals(DRAFT.value())));
            ContractsClosurePublicationReceipt receipt = engine.documents()
                    .closurePublicationReceipt(outcome.publicationIdentity())
                    .orElseThrow();
            assertEquals(Set.of(HOST, DRAFT),
                    Set.copyOf(receipt.documentIds()));

            ContractsClosureAdapter.CohortOutcome replay = adapter
                    .executeAndPublish(batch, invocation);
            assertTrue(replay.replayed());
            assertTrue(engine.documents().find(DRAFT).isEmpty());
        }
    }

    @Test
    void oneProcessAtomicallyPublishesExistingAndNewManagedDocuments() {
        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/success")) {
            Timeline timeline = engine.timeline(
                    "managed/success", ACTOR);
            ExactValue target = engine.document(HOST).current();
            ExactValue draft = draft(engine);
            ExactValue request = engine.referenceRequest("order", draft);
            TimelineEntry entry = engine.append(
                    timeline,
                    Operation.exact(
                            "createOrder", "ownerChannel", request)
                            .targeting(target, true),
                    plan(HOST, target, draft, "order",
                            "/orders/order-1"));
            ContractsClosureAdapter adapter = engine
                    .contractsClosureAdapter();
            ContractsClosureAdapter.FrozenBatch batch = adapter.capture(entry);

            ContractsClosureAdapter.CohortOutcome outcome = adapter
                    .executeAndPublish(batch, batch.invocations().get(0));

            assertTrue(outcome.published());
            assertTrue(outcome.attempt().processResult().commits());
            assertEquals(Set.of(HOST, DRAFT), Set.copyOf(outcome.members()));
            DocumentSnapshot host = engine.document(HOST);
            DocumentSnapshot child = engine.document(DRAFT);
            assertEquals(1L, host.epoch());
            assertEquals(0L, child.epoch());
            assertEquals(SessionStatus.READY, child.status());
            assertNotEquals(draft.blueId(), child.current().blueId());
            assertEquals(child.current().blueId(), host.current()
                    .canonicalBlueIdAt("/orders/order-1"));
            assertEquals(1L, engine.documents().occurrenceInventory()
                    .activeRows().stream()
                    .filter(row -> row.sourceDocumentId().value().equals(
                            HOST.value())
                            && row.targetDocumentId().value().equals(
                                    DRAFT.value()))
                    .count());
        }
    }

    private static DefaultCoordinationEngine admittedHost(
            String timelineId) {
        DefaultCoordinationEngine engine = contractsEngine();
        engine.registerTimeline(timelineId, ACTOR);
        engine.authorizeContractsPublicRoots(Set.of(HOST));
        new Contracts10ScenarioBuilder(engine)
                .document(HOST, hostDocument(timelineId))
                .publicRoot(HOST)
                .expectedComponent(HOST)
                .admitTo(engine);
        return engine;
    }

    private static DefaultCoordinationEngine contractsEngine() {
        BundledContracts10Release.Manifest release =
                BundledContracts10Release.manifest();
        return DefaultCoordinationEngine.createContracts10Sdk(
                release.blueLanguageSpecification(),
                release.contractsSpecification());
    }

    private static ExactValue draft(DefaultCoordinationEngine engine) {
        return engine.exactValue("""
                documentId: managed-expansion-draft
                state: draft
                """);
    }

    private static ContractsManagedDraftPlan plan(
            DocumentId targetDocumentId,
            ExactValue target,
            ExactValue draft,
            String requestField,
            String occurrencePath) {
        return new ContractsManagedDraftPlan(
                targetDocumentId,
                0L,
                target.blueId(),
                Map.of(DRAFT, new ContractsManagedDraftPlan.ManagedDraft(
                        DRAFT, draft, null)),
                Map.of(requestField, DRAFT),
                List.of(new ContractsManagedDraftPlan.ExpectedOccurrence(
                        occurrencePath,
                        DRAFT,
                        ActivationMode.BIRTH_AT_ATTACHMENT)));
    }

    private static String hostDocument(String timelineId) {
        return """
                documentId: managed-expansion-host
                orders: {}
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /orders
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  createOrder:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      order: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /orders/order-1
                              val: {$binding: event/message/request/order}
                          - $return: true
                  rejectCreate:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      order: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $return: true
                """.formatted(timelineId);
    }
}
