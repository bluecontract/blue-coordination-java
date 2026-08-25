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
    private static final DocumentId EXISTING = DocumentId.of(
            "managed-expansion-existing");

    @Test
    void atomicAppendPublishesOrRollsBackEntryAndPlanTogether() {
        // given

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

            // when
            engine.failOnceAt(DefaultCoordinationEngine.FailurePoint
                    .AFTER_MANAGED_DRAFT_PLAN_REGISTERED);

            // then
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
        // given

        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/preflight")) {
            Timeline timeline = engine.timeline("managed/preflight", ACTOR);
            ExactValue target = engine.document(HOST).current();
            ExactValue draft = draft(engine);
            ExactValue request = engine.referenceRequest("order", draft);

            // when
            Operation operation = Operation.exact(
                    "createOrder", "ownerChannel", request)
                    .targeting(target, true);

            // then
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
        // given

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

            // when
            ContractsClosureAdapter.FrozenBatch captured = engine
                    .contractsClosureAdapter().capture(entry);

            // then
            assertTrue(captured.invocations().isEmpty());
            assertTrue(engine.contractsClosureAdapter()
                    .hasManagedDraftPlan(entry.blueId()));
            ProcessingDrainReceipt drained = engine.drain();
            assertEquals(List.of(entry), drained.processedEntries());
            assertTrue(drained.contractsAttemptsFor(
                    entry.blueId()).isEmpty());
            assertTrue(engine.documents().find(DRAFT).isEmpty());
            assertFalse(engine.contractsClosureAdapter()
                    .hasManagedDraftPlan(entry.blueId()));
        }
    }

    @Test
    void terminalNonCommitLeavesEveryManagedDraftAbsent() {
        // given

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

            // when
            ContractsClosureAdapter.CohortOutcome outcome = adapter
                    .executeAndPublish(batch, invocation);

            // then
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
            assertTrue(adapter.hasManagedDraftPlan(entry.blueId()));

            ProcessingDrainReceipt terminal = engine.drain();
            assertEquals(List.of(entry), terminal.processedEntries());
            assertFalse(adapter.hasManagedDraftPlan(entry.blueId()));
        }
    }

    @Test
    void virtualRollbackReceiptSurvivesLaterAdmissionOfSameLineage() {
        // given

        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/rollback-retry")) {
            Timeline timeline = engine.timeline(
                    "managed/rollback-retry", ACTOR);
            ExactValue draft = draft(engine);
            ExactValue request = engine.referenceRequest("order", draft);
            ExactValue target = engine.document(HOST).current();
            TimelineEntry rejectedEntry = engine.append(
                    timeline,
                    Operation.exact(
                            "rejectCreate", "ownerChannel", request)
                            .targeting(target, true),
                    plan(HOST, target, draft, "order",
                            "/orders/order-1"));

            // when
            ProcessingDrainReceipt rejected = engine.drain();

            // then
            assertEquals(List.of(rejectedEntry), rejected.processedEntries());
            assertEquals(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    rejected.contractsAttemptsFor(rejectedEntry.blueId())
                            .get(0).attempt().processResult().status());
            assertTrue(engine.documents().find(DRAFT).isEmpty());

            target = engine.document(HOST).current();
            TimelineEntry retryEntry = engine.append(
                    timeline,
                    Operation.exact(
                            "createOrder", "ownerChannel", request)
                            .targeting(target, true),
                    plan(HOST, target, draft, "order",
                            "/orders/order-1"));
            ProcessingDrainReceipt retry = engine.drain();

            assertEquals(List.of(retryEntry), retry.processedEntries());
            assertEquals(ProcessorStatus.SUCCESS,
                    retry.contractsAttemptsFor(retryEntry.blueId())
                            .get(0).attempt().processResult().status());
            assertEquals(SessionStatus.READY,
                    engine.document(DRAFT).status());
            assertEquals(engine.document(DRAFT).current().blueId(),
                    engine.document(HOST).current()
                            .canonicalBlueIdAt("/orders/order-1"));
        }
    }

    @Test
    void managedDraftPlanSurvivesFailedPublicationAndClearsAfterRetry() {
        // given

        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/retry")) {
            Timeline timeline = engine.timeline("managed/retry", ACTOR);
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

            // when
            adapter.onPublicationFailurePoint(point -> {
                throw new IllegalStateException("route publication failed");
            });

            // then
            assertThrows(RuntimeException.class, engine::drain);
            assertTrue(adapter.hasManagedDraftPlan(entry.blueId()));
            assertTrue(engine.documents().find(DRAFT).isPresent());

            adapter.onPublicationFailurePoint(ignored -> { });
            ProcessingDrainReceipt retried = engine.drain();
            assertEquals(List.of(entry), retried.processedEntries());
            assertFalse(adapter.hasManagedDraftPlan(entry.blueId()));
            assertTrue(engine.documents().find(DRAFT).isPresent());
        }
    }

    @Test
    void offSurfaceManagedDraftPlanClearsWhenJournalMarksEntryTerminal() {
        // given

        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/active")) {
            Timeline offSurface = engine.registerTimeline(
                    "managed/off-surface", ACTOR);
            ExactValue target = engine.document(HOST).current();
            ExactValue draft = draft(engine);
            ExactValue request = engine.referenceRequest("order", draft);
            TimelineEntry entry = engine.append(
                    offSurface,
                    Operation.exact(
                            "createOrder", "ownerChannel", request)
                            .targeting(target, true),
                    plan(HOST, target, draft, "order",
                            "/orders/order-1"));

            // when
            ContractsClosureAdapter adapter = engine
                    .contractsClosureAdapter();

            // then
            assertTrue(adapter.hasManagedDraftPlan(entry.blueId()));

            ProcessingDrainReceipt terminal = engine.drain();

            assertEquals(List.of(entry), terminal.processedEntries());
            assertTrue(terminal.contractsAttemptsFor(
                    entry.blueId()).isEmpty());
            assertFalse(adapter.hasManagedDraftPlan(entry.blueId()));
            assertTrue(engine.documents().find(DRAFT).isEmpty());
        }
    }

    @Test
    void oneProcessAtomicallyPublishesExistingAndNewManagedDocuments() {
        // given

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

            // when
            ContractsClosureAdapter.CohortOutcome outcome = adapter
                    .executeAndPublish(batch, batch.invocations().get(0));

            // then
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

    @Test
    void typedDemandAutomaticallyCreatesOneExactAuthoredChild() {
        // given

        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/automatic-new")) {
            Timeline timeline = engine.timeline(
                    "managed/automatic-new", ACTOR);
            ExactValue target = engine.document(HOST).current();
            ExactValue draft = engine.exactValue("""
                    documentId: arbitrary-authored-content
                    state: automatic-draft
                    """);
            DocumentId runtimeDocumentId = DocumentId.of(draft.blueId());
            ExactValue request = engine.referenceRequest("order", draft);
            TimelineEntry entry = engine.append(
                    timeline,
                    Operation.exact(
                            "createOrder", "ownerChannel", request)
                            .targeting(target, true));
            ContractsClosureAdapter adapter = engine
                    .contractsClosureAdapter();
            ContractsClosureAdapter.FrozenBatch batch = adapter.capture(entry);

            // when
            ContractsClosureAdapter.CohortOutcome outcome = adapter
                    .executeAndPublish(batch, batch.invocations().get(0));

            // then
            assertTrue(outcome.published());
            assertEquals(ProcessorStatus.SUCCESS,
                    outcome.attempt().processResult().status());
            assertEquals(Set.of(HOST, runtimeDocumentId),
                    Set.copyOf(outcome.members()));
            DocumentSnapshot child = engine.document(runtimeDocumentId);
            assertEquals(0L, child.epoch());
            assertEquals(SessionStatus.READY, child.status());
            assertEquals("arbitrary-authored-content",
                    child.current().copyNode().get("/documentId"));
            assertEquals(child.current().blueId(), engine.document(HOST)
                    .current().canonicalBlueIdAt("/orders/order-1"));
            assertTrue(engine.documents().metrics().counter(
                    AutomaticOccurrenceResolutionCoordinator.RETRIES) > 0L);
        }
    }

    @Test
    void typedDemandReusesOneExistingCurrentLineageWithoutInitialization() {
        // given

        try (DefaultCoordinationEngine engine = contractsEngine()) {
            String timelineId = "managed/automatic-current";
            engine.registerTimeline(timelineId, ACTOR);
            engine.authorizeContractsPublicRoots(Set.of(HOST, EXISTING));
            new Contracts10ScenarioBuilder(engine)
                    .document(HOST, hostDocument(timelineId))
                    .document(EXISTING, """
                            documentId: managed-expansion-existing
                            state: reusable-current
                            """)
                    .publicRoot(HOST)
                    .publicRoot(EXISTING)
                    .expectedComponent(EXISTING)
                    .expectedComponent(HOST)
                    .admitTo(engine);
            DocumentSnapshot existingBefore = engine.document(EXISTING);
            ExactValue request = engine.referenceRequest(
                    "order", existingBefore.current());
            TimelineEntry entry = engine.append(
                    engine.timeline(timelineId, ACTOR),
                    Operation.exact(
                            "createOrder", "ownerChannel", request)
                            .targeting(engine.document(HOST).current(), true));
            ContractsClosureAdapter adapter = engine
                    .contractsClosureAdapter();
            ContractsClosureAdapter.FrozenBatch batch = adapter.capture(entry);

            // when
            ContractsClosureAdapter.CohortOutcome outcome = adapter
                    .executeAndPublish(batch, batch.invocations().get(0));

            // then
            assertTrue(outcome.published());
            assertEquals(Set.of(HOST, EXISTING), Set.copyOf(
                    outcome.members()));
            DocumentSnapshot existingAfter = engine.document(EXISTING);
            assertEquals(existingBefore.epoch(), existingAfter.epoch());
            assertEquals(existingBefore.current().blueId(),
                    existingAfter.current().blueId());
            assertEquals(existingAfter.current().blueId(),
                    engine.document(HOST).current()
                            .canonicalBlueIdAt("/orders/order-1"));
            assertEquals(2, engine.documents().publicationSnapshot()
                    .documentHeads().size());
            assertTrue(engine.documents().metrics().counter(
                    AutomaticOccurrenceResolutionCoordinator.RETRIES) > 0L);
        }
    }

    @Test
    void expandedReceiptReplaysAfterStoreCommitAndBaseLaneRecapture() {
        // given

        try (DefaultCoordinationEngine engine = admittedHost(
                "managed/automatic-replay")) {
            Timeline timeline = engine.timeline(
                    "managed/automatic-replay", ACTOR);
            ExactValue authored = engine.exactValue("""
                    state: one-initialization-only
                    """);
            DocumentId childId = DocumentId.of(authored.blueId());
            TimelineEntry entry = engine.append(
                    timeline,
                    Operation.exact(
                                    "createOrder",
                                    "ownerChannel",
                                    engine.referenceRequest("order", authored))
                            .targeting(engine.document(HOST).current(), true));
            ContractsClosureAdapter adapter = engine
                    .contractsClosureAdapter();
            adapter.onPublicationFailurePoint(point -> {
                throw new IllegalStateException("lost expanded response");
            });

            // when
            assertThrows(RuntimeException.class, engine::drain);

            // then
            assertTrue(engine.documents().find(childId).isPresent());
            assertEquals(2, engine.history(HOST).size());
            assertEquals(1, engine.history(childId).size());
            assertEquals(1, engine.documents().publicationSnapshot()
                    .closurePublicationReceipts().size());
            long retries = engine.documents().metrics().counter(
                    AutomaticOccurrenceResolutionCoordinator.RETRIES);

            adapter.onPublicationFailurePoint(ignored -> { });
            ProcessingDrainReceipt recovered = engine.drain();

            assertEquals(List.of(entry), recovered.processedEntries());
            assertTrue(recovered.quiescent());
            assertEquals(2, engine.history(HOST).size());
            assertEquals(1, engine.history(childId).size());
            assertEquals(1, engine.documents().publicationSnapshot()
                    .closurePublicationReceipts().size());
            assertEquals(retries, engine.documents().metrics().counter(
                    AutomaticOccurrenceResolutionCoordinator.RETRIES));
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
