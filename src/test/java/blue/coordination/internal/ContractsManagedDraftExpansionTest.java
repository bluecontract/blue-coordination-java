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
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final DocumentId DESCENDANT = DocumentId.of(
            "managed-expansion-descendant");
    private static final DocumentId EXTERNAL = DocumentId.of(
            "managed-expansion-external-source");

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
            assertEquals(1L, outcome.automaticRetryCount());
            assertEquals(ProcessorStatus.SUCCESS,
                    outcome.attempt().processResult().status());
            assertEquals(Set.of(HOST, runtimeDocumentId),
                    Set.copyOf(outcome.publicationMembers()));
            assertEquals(List.of(HOST), outcome.members());
            DocumentSnapshot child = engine.document(runtimeDocumentId);
            assertEquals(0L, child.epoch());
            assertEquals(SessionStatus.READY, child.status());
            assertEquals("arbitrary-authored-content",
                    child.current().copyNode().get("/documentId"));
            assertEquals(child.current().blueId(), engine.document(HOST)
                    .current().canonicalBlueIdAt("/orders/order-1"));
            assertTrue(engine.documents().metrics().counter(
                    AutomaticOccurrenceResolutionCoordinator.RETRIES) > 0L);
            assertEquals(0L, engine.documents().metrics().counter(
                    ContractsClosureExecutionMetricsObserver
                            .UNRELATED_COMPONENT_FINALIZATIONS));
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
                    outcome.publicationMembers()));
            assertEquals(List.of(HOST), outcome.members());
            assertEquals(1, outcome.managedSurfaceEvidence()
                    .resolvedOccurrences().size());
            ManagedSurfacePublicationEvidence.ResolvedOccurrence
                    resolution = outcome.managedSurfaceEvidence()
                            .resolvedOccurrences().get(0);
            assertEquals(
                    ManagedOccurrenceResolver.TargetKind.CURRENT_EXISTING,
                    resolution.targetKind());
            assertEquals(EXISTING.value(),
                    resolution.occurrence().targetDocumentId().value());
            assertEquals("/orders/order-1",
                    resolution.occurrence().sourcePath());
            assertTrue(resolution.occurrence().active());
            assertTrue(resolution.authoredInitial() == null);
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
            ContractsClosureAdapter.FrozenBatch originalBatch = adapter
                    .capture(entry);
            ContractsClosureAdapter.CohortInvocation originalInvocation =
                    originalBatch.invocations().get(0);
            adapter.onPublicationFailurePoint(point -> {
                throw new IllegalStateException("lost expanded response");
            });

            // when
            assertThrows(RuntimeException.class, () -> adapter
                    .executeAndPublish(originalBatch, originalInvocation));

            // then
            assertTrue(engine.documents().find(childId).isPresent());
            assertEquals(2, engine.history(HOST).size());
            assertEquals(1, engine.history(childId).size());
            assertEquals(1, engine.documents().publicationSnapshot()
                    .closurePublicationReceipts().size());
            ContractsClosurePublicationReceipt committedReceipt = engine
                    .documents().publicationSnapshot()
                    .closurePublicationReceipts().values().iterator().next();
            assertEquals(1L, committedReceipt.automaticRetryCount());
            assertEquals(1, committedReceipt.managedSurfaceEvidence()
                    .resolvedOccurrences().size());
            ManagedSurfacePublicationEvidence.ResolvedOccurrence retained =
                    committedReceipt.managedSurfaceEvidence()
                            .resolvedOccurrences().get(0);
            assertEquals(
                    ManagedOccurrenceResolver.TargetKind.NEW_AUTHORED,
                    retained.targetKind());
            assertEquals(childId.value(),
                    retained.occurrence().targetDocumentId().value());
            assertEquals(authored.blueId(),
                    retained.authoredInitial().blueId());
            long retries = engine.documents().metrics().counter(
                    AutomaticOccurrenceResolutionCoordinator.RETRIES);

            adapter.onPublicationFailurePoint(ignored -> { });
            ContractsClosureAdapter.CohortOutcome replay = adapter
                    .executeAndPublish(
                            originalBatch,
                            originalInvocation);
            assertTrue(replay.replayed());
            assertTrue(replay.published());
            assertEquals(committedReceipt.managedSurfaceEvidence(),
                    replay.managedSurfaceEvidence());

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

    @Test
    void existingTargetExpansionOpensOnlyItsForwardManagedClosure() {
        // given

        try (DefaultCoordinationEngine engine = contractsEngine()) {
            String timelineId = "managed/automatic-forward";
            engine.registerTimeline(timelineId, ACTOR);
            engine.authorizeContractsPublicRoots(Set.of(
                    HOST, EXISTING, DESCENDANT));
            new Contracts10ScenarioBuilder(engine)
                    .document(EXISTING, edgeSource(EXISTING))
                    .document(DESCENDANT, leaf(DESCENDANT))
                    .processEmbeddedPath(
                            EXISTING, "/child", DESCENDANT)
                    .publicRoot(EXISTING)
                    .publicRoot(DESCENDANT)
                    .expectedComponent(DESCENDANT)
                    .expectedComponent(EXISTING)
                    .admitTo(engine);
            admitHost(engine, timelineId);
            DocumentSnapshot existingBefore = engine.document(EXISTING);
            DocumentSnapshot descendantBefore = engine.document(DESCENDANT);
            TimelineEntry entry = engine.append(
                    engine.timeline(timelineId, ACTOR),
                    Operation.exact(
                                    "createOrder",
                                    "ownerChannel",
                                    engine.referenceRequest(
                                            "order",
                                            existingBefore.current()))
                            .targeting(engine.document(HOST).current(), true));
            ContractsClosureAdapter adapter = engine
                    .contractsClosureAdapter();
            ContractsClosureAdapter.FrozenBatch batch = adapter.capture(entry);

            // when
            ContractsClosureAdapter.CohortOutcome outcome = adapter
                    .executeAndPublish(batch, batch.invocations().get(0));

            // then
            assertTrue(outcome.published());
            assertEquals(Set.of(HOST, EXISTING, DESCENDANT),
                    Set.copyOf(outcome.publicationMembers()));
            assertEquals(List.of(HOST), outcome.members());
            assertEquals(existingBefore.current().blueId(),
                    engine.document(EXISTING).current().blueId());
            assertEquals(existingBefore.epoch(),
                    engine.document(EXISTING).epoch());
            assertEquals(descendantBefore.current().blueId(),
                    engine.document(DESCENDANT).current().blueId());
            assertEquals(descendantBefore.epoch(),
                    engine.document(DESCENDANT).epoch());
            assertEquals(existingBefore.current().blueId(),
                    engine.document(HOST).current()
                            .canonicalBlueIdAt("/orders/order-1"));
            assertEquals(1L, engine.documents().occurrenceInventory()
                    .activeRows().stream()
                    .filter(row -> row.sourceDocumentId().value().equals(
                            EXISTING.value())
                            && row.targetDocumentId().value().equals(
                                    DESCENDANT.value()))
                    .count());
        }
    }

    @Test
    void externalActiveIncomingBoundaryRemainsOutsideForwardRetry() {
        // given

        try (DefaultCoordinationEngine engine = contractsEngine()) {
            String timelineId = "managed/automatic-incoming-boundary";
            engine.registerTimeline(timelineId, ACTOR);
            engine.authorizeContractsPublicRoots(Set.of(
                    HOST, EXISTING, EXTERNAL));
            new Contracts10ScenarioBuilder(engine)
                    .document(EXTERNAL, edgeSource(EXTERNAL))
                    .document(EXISTING, leaf(EXISTING))
                    .processEmbeddedPath(EXTERNAL, "/child", EXISTING)
                    .publicRoot(EXTERNAL)
                    .publicRoot(EXISTING)
                    .expectedComponent(EXISTING)
                    .expectedComponent(EXTERNAL)
                    .admitTo(engine);
            admitHost(engine, timelineId);
            TimelineEntry entry = engine.append(
                    engine.timeline(timelineId, ACTOR),
                    Operation.exact(
                                    "createOrder",
                                    "ownerChannel",
                                    engine.referenceRequest(
                                            "order",
                                            engine.document(EXISTING)
                                                    .current()))
                            .targeting(engine.document(HOST).current(), true));
            ContractsClosureAdapter adapter = engine
                    .contractsClosureAdapter();
            ContractsClosureAdapter.FrozenBatch batch = adapter.capture(entry);
            InMemoryDocumentStore.PublicationSnapshot before = engine
                    .documents().publicationSnapshot();
            InMemoryDocumentStore.DocumentHead externalBefore = before
                    .documentHeads().get(EXTERNAL);
            ManagedOccurrenceBinding externalIncoming = before
                    .occurrenceInventory().row(EXTERNAL, "/child");
            EngineMetrics.MetricsSnapshot metricsBefore = engine
                    .engineMetrics().snapshot();

            // when
            ContractsClosureAdapter.CohortOutcome outcome = adapter
                    .executeAndPublish(batch, batch.invocations().get(0));
            EngineMetrics.MetricsSnapshot metricsAfter = engine
                    .engineMetrics().snapshot();

            // then
            assertTrue(outcome.published());
            assertEquals(1L, outcome.automaticRetryCount());
            assertEquals(List.of(HOST), outcome.members());
            assertEquals(Set.of(HOST, EXISTING),
                    Set.copyOf(outcome.publicationMembers()));
            InMemoryDocumentStore.PublicationSnapshot after = engine
                    .documents().publicationSnapshot();
            assertEquals(externalBefore, after.documentHeads().get(EXTERNAL));
            assertSameOccurrence(
                    externalIncoming,
                    after.occurrenceInventory().row(EXTERNAL, "/child"));
            ManagedOccurrenceBinding hostToExisting = after
                    .occurrenceInventory().row(HOST, "/orders/order-1");
            assertTrue(hostToExisting.active());
            assertEquals(EXISTING.value(),
                    hostToExisting.targetDocumentId().value());
            assertEquals(2, after.occurrenceInventory().activeRows().size());
            assertEquals(1L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.DOCUMENT_OPENS));
            assertEquals(0L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.UNRELATED_DOCUMENT_OPENS));
            assertEquals(2L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.COMPONENT_STATES_READ));
        }
    }

    @Test
    void activeOccurrenceRetargetsAcrossLineagesAtomicallyAndReplaysAfterRestart() {
        // given

        try (DefaultCoordinationEngine engine = contractsEngine()) {
            String timelineId = "managed/automatic-active-rebind";
            engine.registerTimeline(timelineId, ACTOR);
            engine.authorizeContractsPublicRoots(Set.of(HOST));
            new Contracts10ScenarioBuilder(engine)
                    .document(HOST, retargetHostDocument(timelineId))
                    .document(EXISTING, leaf(EXISTING))
                    .document(DESCENDANT, leaf(DESCENDANT))
                    .processEmbeddedPath(HOST, "/peer", EXISTING)
                    .publicRoot(HOST)
                    .expectedComponent(DESCENDANT)
                    .expectedComponent(EXISTING)
                    .expectedComponent(HOST)
                    .admitTo(engine);
            Timeline timeline = engine.timeline(timelineId, ACTOR);
            ContractsClosureAdapter adapter = engine
                    .contractsClosureAdapter();
            ManagedOccurrenceBinding initial = engine.documents()
                    .occurrenceInventory().row(HOST, "/peer");
            InMemoryDocumentStore.PublicationSnapshot before = engine
                    .documents().publicationSnapshot();
            int hostHistoryBefore = engine.history(HOST).size();
            int existingHistoryBefore = engine.history(EXISTING).size();
            int descendantHistoryBefore = engine.history(DESCENDANT).size();
            int routeRowsBefore = engine.routeRowCount();
            TimelineEntry toDescendant = engine.append(
                    timeline,
                    Operation.exact(
                                    "retarget",
                                    "ownerChannel",
                                    engine.referenceRequest(
                                            "peer",
                                            engine.document(DESCENDANT)
                                                    .current()))
                            .targeting(engine.document(HOST).current(), true));
            ContractsClosureAdapter.FrozenBatch batch = adapter.capture(
                    toDescendant);
            ContractsClosureAdapter.CohortInvocation invocation = batch
                    .invocations().get(0);
            String publicationIdentity = adapter.publicationIdentityFor(
                    batch, invocation);

            // The first store attempt fails before its atomic swap.
            adapter.onStoreFailurePoint(point -> {
                if (point == MultiDocumentPublicationTransaction.FailurePoint
                        .BEFORE_SWAP) {
                    throw new IllegalStateException("before-swap");
                }
            });

            // when
            IllegalStateException injected = assertThrows(
                    IllegalStateException.class,
                    () -> adapter.executeAndPublish(batch, invocation));

            // then
            // No document, occurrence, topology, receipt, or route state
            // escapes the rejected transaction.
            assertEquals("before-swap", injected.getMessage());
            InMemoryDocumentStore.PublicationSnapshot rolledBack = engine
                    .documents().publicationSnapshot();
            assertEquals(before.documentHeads(), rolledBack.documentHeads());
            assertEquals(before.occurrenceInventory().rows(), rolledBack
                    .occurrenceInventory().rows());
            assertEquals(before.occurrenceInventoryGeneration(), rolledBack
                    .occurrenceInventoryGeneration());
            assertEquals(before.componentIndexGeneration(), rolledBack
                    .componentIndexGeneration());
            assertEquals(before.componentStates(), rolledBack
                    .componentStates());
            assertEquals(before.closurePublicationReceipts(), rolledBack
                    .closurePublicationReceipts());
            assertEquals(before.graphGenerations().require(HOST), rolledBack
                    .graphGenerations().require(HOST));
            assertEquals(before.graphGenerations().require(EXISTING),
                    rolledBack.graphGenerations().require(EXISTING));
            assertEquals(before.graphGenerations().require(DESCENDANT),
                    rolledBack.graphGenerations().require(DESCENDANT));
            assertEquals(routeRowsBefore, engine.routeRowCount());
            assertEquals(hostHistoryBefore, engine.history(HOST).size());
            assertEquals(existingHistoryBefore,
                    engine.history(EXISTING).size());
            assertEquals(descendantHistoryBefore,
                    engine.history(DESCENDANT).size());

            // when: the exact same frozen operation is retried.
            adapter.onStoreFailurePoint(ignored -> { });
            ContractsClosureAdapter.CohortOutcome rebound = adapter
                    .executeAndPublish(batch, invocation);

            // then: the typed current-C demand expands only the publication
            // lane, and Contracts emits one identity-bearing REBIND.
            assertTrue(rebound.published());
            assertFalse(rebound.replayed());
            assertEquals(1L, rebound.automaticRetryCount());
            assertEquals(List.of(EXISTING, HOST), rebound.members());
            assertEquals(Set.of(HOST, EXISTING, DESCENDANT),
                    Set.copyOf(rebound.publicationMembers()));
            assertEquals(publicationIdentity, rebound.publicationIdentity());
            assertEquals(ProcessorStatus.SUCCESS,
                    rebound.attempt().processResult().status());
            List<GraphChange> graphChanges = rebound.attempt().processResult()
                    .graphChanges().stream()
                    .filter(change -> change.changeKind()
                            == GraphChange.Kind.REBIND
                            && change.sourceDocumentId().value().equals(
                                    HOST.value())
                            && change.sourcePath().equals("/peer"))
                    .toList();
            assertEquals(1, graphChanges.size());
            GraphChange graphChange = graphChanges.get(0);
            assertEquals(0L, graphChange.graphChangeOrdinal());
            assertEquals(EXISTING.value(),
                    graphChange.beforeTargetDocumentId().value());
            assertEquals(initial.expectedTargetBlueId(),
                    graphChange.beforeTargetBlueId());
            assertEquals(initial.activationGeneration(),
                    graphChange.beforeActivationGeneration());
            assertEquals(initial.occurrenceIdentity(),
                    graphChange.beforeOccurrenceIdentity());
            assertEquals(initial.bindingIdentity(),
                    graphChange.beforeBindingIdentity());
            assertEquals(DESCENDANT.value(),
                    graphChange.afterTargetDocumentId().value());
            assertEquals(engine.document(DESCENDANT).current().blueId(),
                    graphChange.afterTargetBlueId());
            assertEquals(initial.activationGeneration() + 1L,
                    graphChange.afterActivationGeneration());
            assertNotEquals(initial.occurrenceIdentity(),
                    graphChange.afterOccurrenceIdentity());
            assertNotEquals(initial.bindingIdentity(),
                    graphChange.afterBindingIdentity());

            InMemoryDocumentStore.PublicationSnapshot after = engine
                    .documents().publicationSnapshot();
            ManagedOccurrenceBinding current = after.occurrenceInventory()
                    .row(HOST, "/peer");
            assertTrue(current.active());
            assertNull(current.pendingHistoricalEpoch());
            assertEquals(DESCENDANT.value(),
                    current.targetDocumentId().value());
            assertEquals(engine.document(DESCENDANT).current().blueId(),
                    current.expectedTargetBlueId());
            assertEquals(initial.activationGeneration() + 1L,
                    current.activationGeneration());
            assertEquals(graphChange.afterOccurrenceIdentity(),
                    current.occurrenceIdentity());
            assertEquals(graphChange.afterBindingIdentity(),
                    current.bindingIdentity());
            assertEquals(before.occurrenceInventoryGeneration() + 1L,
                    after.occurrenceInventoryGeneration());
            assertEquals(before.componentIndexGeneration() + 1L,
                    after.componentIndexGeneration());
            assertEquals(before.requireHead(HOST).epoch() + 1L,
                    after.requireHead(HOST).epoch());
            assertEquals(before.requireHead(EXISTING),
                    after.requireHead(EXISTING));
            assertEquals(before.requireHead(DESCENDANT),
                    after.requireHead(DESCENDANT));
            long resultGraphGeneration = rebound.attempt().processResult()
                    .graphGeneration();
            assertEquals(before.graphGenerations().require(HOST) + 1L,
                    resultGraphGeneration);
            assertEquals(resultGraphGeneration,
                    after.graphGenerations().require(HOST));
            assertEquals(resultGraphGeneration,
                    after.graphGenerations().require(EXISTING));
            assertEquals(resultGraphGeneration,
                    after.graphGenerations().require(DESCENDANT));
            assertEquals(1L, after.occurrenceInventory().rows().stream()
                    .filter(row -> row.sourceDocumentId().value().equals(
                            HOST.value())
                            && row.sourceAddress().path().equals("/peer"))
                    .count());
            ProcessEmbeddedComponentIndex index = after.componentIndex();
            assertEquals(List.of(DESCENDANT, HOST),
                    index.cohort(HOST).members());
            assertEquals(List.of(EXISTING),
                    index.cohort(EXISTING).members());
            assertEquals(List.of(index.component(DESCENDANT)),
                    index.targets(index.component(HOST)));
            assertTrue(index.sources(index.component(EXISTING)).isEmpty());
            assertEquals(0L, engine.documents().metrics().counter(
                    "temporal.catchUpBarriersCreated"));
            assertEquals(0L, engine.documents().metrics().counter(
                    "catchUp.childEntriesProcessed"));
            assertTrue(engine.history(EXISTING).stream().noneMatch(
                    revision -> revision.kind()
                            == blue.coordination.api.DocumentRevision.Kind
                                    .CATCH_UP_COMPLETED));
            assertTrue(engine.history(DESCENDANT).stream().noneMatch(
                    revision -> revision.kind()
                            == blue.coordination.api.DocumentRevision.Kind
                                    .CATCH_UP_COMPLETED));

            // when: a route-publish response is lost after a second atomic
            // retarget commits, and the runtime is rebuilt from durable stores.
            TimelineEntry backToExisting = engine.append(
                    timeline,
                    Operation.exact(
                                    "retarget",
                                    "ownerChannel",
                                    engine.referenceRequest(
                                            "peer",
                                            engine.document(EXISTING)
                                                    .current())));
            ContractsClosureAdapter.FrozenBatch secondBatch = adapter.capture(
                    backToExisting);
            assertFalse(secondBatch.invocations().isEmpty());
            ContractsClosureAdapter.CohortInvocation secondInvocation =
                    secondBatch.invocations().get(0);
            String secondPublicationIdentity = adapter.publicationIdentityFor(
                    secondBatch, secondInvocation);
            adapter.onPublicationFailurePoint(point -> {
                if (point == ContractsClosureAdapter.PublicationFailurePoint
                        .AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH) {
                    throw new IllegalStateException("route-publish");
                }
            });
            assertThrows(IllegalStateException.class, () -> adapter
                    .executeAndPublish(secondBatch, secondInvocation));
            InMemoryDocumentStore.PublicationSnapshot committed = engine
                    .documents().publicationSnapshot();
            ManagedOccurrenceBinding committedBack = committed
                    .occurrenceInventory().row(HOST, "/peer");
            assertEquals(EXISTING.value(),
                    committedBack.targetDocumentId().value());
            assertEquals(current.activationGeneration() + 1L,
                    committedBack.activationGeneration());
            assertTrue(committed.closurePublicationReceipts().containsKey(
                    secondPublicationIdentity));
            int historyAfterCommit = engine.history(HOST).size();
            long inventoryGenerationAfterCommit = committed
                    .occurrenceInventoryGeneration();
            int receiptCountAfterCommit = committed
                    .closurePublicationReceipts().size();

            adapter.onPublicationFailurePoint(ignored -> { });
            engine.restartFromStores();
            ContractsClosureAdapter.FrozenBatch recoveredBatch = adapter
                    .capture(backToExisting);
            ContractsClosureAdapter.CohortInvocation recoveredInvocation =
                    recoveredBatch.invocations().get(0);
            assertEquals(secondPublicationIdentity,
                    adapter.publicationIdentityFor(
                            recoveredBatch, recoveredInvocation));
            ContractsClosureAdapter.CohortOutcome replay = adapter
                    .executeAndPublish(recoveredBatch, recoveredInvocation);

            // then: the durable base-lane identity replays exactly once.
            assertTrue(replay.replayed());
            assertTrue(replay.published());
            assertEquals(secondPublicationIdentity,
                    replay.publicationIdentity());
            assertEquals(historyAfterCommit, engine.history(HOST).size());
            assertEquals(existingHistoryBefore,
                    engine.history(EXISTING).size());
            assertEquals(descendantHistoryBefore,
                    engine.history(DESCENDANT).size());
            assertEquals(inventoryGenerationAfterCommit,
                    engine.documents().publicationSnapshot()
                            .occurrenceInventoryGeneration());
            assertEquals(receiptCountAfterCommit,
                    engine.documents().publicationSnapshot()
                            .closurePublicationReceipts().size());
            assertEquals(committedBack, engine.documents()
                    .occurrenceInventory().row(HOST, "/peer"));
            assertTrue(engine.contractsClosureAdapter()
                    .lastExecutionEvidence().isPresent());
        }
    }

    private static DefaultCoordinationEngine admittedHost(
            String timelineId) {
        DefaultCoordinationEngine engine = contractsEngine();
        engine.registerTimeline(timelineId, ACTOR);
        engine.authorizeContractsPublicRoots(Set.of(HOST));
        admitHost(engine, timelineId);
        return engine;
    }

    private static void admitHost(
            DefaultCoordinationEngine engine,
            String timelineId) {
        new Contracts10ScenarioBuilder(engine)
                .document(HOST, hostDocument(timelineId))
                .publicRoot(HOST)
                .expectedComponent(HOST)
                .admitTo(engine);
    }

    private static DefaultCoordinationEngine contractsEngine() {
        BundledContracts10Release.Manifest release =
                BundledContracts10Release.manifest();
        return DefaultCoordinationEngine.createContracts10Sdk(
                release.blueLanguageSpecification(),
                release.contractsSpecification());
    }

    private static long metricDelta(
            EngineMetrics.MetricsSnapshot before,
            EngineMetrics.MetricsSnapshot after,
            String name) {
        return Math.subtractExact(
                after.counters().getOrDefault(name, 0L),
                before.counters().getOrDefault(name, 0L));
    }

    private static void assertSameOccurrence(
            ManagedOccurrenceBinding expected,
            ManagedOccurrenceBinding actual) {
        assertEquals(expected.occurrenceIdentity(),
                actual.occurrenceIdentity());
        assertEquals(expected.bindingIdentity(), actual.bindingIdentity());
        assertEquals(expected.bindingPolicyIdentity(),
                actual.bindingPolicyIdentity());
        assertEquals(expected.sourceDocumentId().value(),
                actual.sourceDocumentId().value());
        assertEquals(expected.sourceAddress(), actual.sourceAddress());
        assertEquals(expected.targetDocumentId().value(),
                actual.targetDocumentId().value());
        assertEquals(expected.expectedTargetBlueId(),
                actual.expectedTargetBlueId());
        assertEquals(expected.active(), actual.active());
        assertEquals(expected.pendingHistoricalEpoch(),
                actual.pendingHistoricalEpoch());
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

    private static String retargetHostDocument(String timelineId) {
        return """
                documentId: managed-expansion-host
                peer: {}
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  retarget:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      peer: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /peer
                              val: {$binding: event/message/request/peer}
                          - $return: true
                """.formatted(timelineId);
    }

    private static String edgeSource(DocumentId documentId) {
        return """
                documentId: %s
                child: {}
                """.formatted(documentId.value());
    }

    private static String leaf(DocumentId documentId) {
        return """
                documentId: %s
                state: current
                """.formatted(documentId.value());
    }
}
