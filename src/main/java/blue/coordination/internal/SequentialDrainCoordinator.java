package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationEngine.DrainBudget;
import blue.coordination.api.DocumentDispatchOutcome;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.TimelineEntry;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

final class SequentialDrainCoordinator {
    private final BlueRuntime runtime;
    private final WholeObjectStore objects;
    private final WholeRequestEntryFactory entryFactory;
    private final InMemoryTimelineJournal journal;
    private final OperationRouteIndex routes;
    private final DocumentTransitionProcessor processor;
    private final InMemoryDocumentStore documents;
    private final EngineMetrics metrics;
    private final LongSupplier applicationTimestamp;
    private final Consumer<DefaultCoordinationEngine.FailurePoint>
            failureInjector;
    private Consumer<TransitionTrace> transitionObserver;
    private final RecoveryState recoveryState;
    private final Map<String, EmbeddedEpochCursor> cursors;
    private final Map<DocumentId, EmbeddedAdmissionEvidence>
            embeddedAdmissions;
    private final Map<OccurrenceKey, EmbeddedAdmissionEvidence>
            exactEmbeddedAdmissions;
    private final Map<String, CatchUpBarrier> barriers;
    private final Map<DocumentId, String> openBarrierByParent;
    private final Map<OccurrenceKey, Long> activationGenerations;
    private final Map<String, EntryFrame> openFrames;
    private final Set<String> rejectedExternalEntryIds;
    private final Map<DocumentId, PendingTopLevelAdmission>
            pendingTopLevelAdmissions;
    private final Map<String, List<InternalProcessOutcome>> outcomesByEntry =
            new LinkedHashMap<>();
    private final Map<DocumentId, String> embeddedInputHeadByParent;
    private final Map<String, EmbeddedEpochInput> committedEmbeddedInputs;
    private final List<PublicationCheckpoint> publicationCheckpoints =
            new ArrayList<>();
    private ProcessEmbeddedGraphSnapshot graph =
            ProcessEmbeddedGraphSnapshot.empty();
    private ExternalOrderKey processedThrough;
    private long committedTransitionSequence;
    private boolean settlingBarrierTree;
    private DrainBudget activeBudget;
    private long drainSequenceBefore;
    private final Set<String> drainSelectedEntries = new LinkedHashSet<>();
    private Map<String, List<DocumentDispatchOutcome>> drainOutcomes;

    SequentialDrainCoordinator(
            BlueRuntime runtime,
            WholeObjectStore objects,
            WholeRequestEntryFactory entryFactory,
            InMemoryTimelineJournal journal,
            OperationRouteIndex routes,
            DocumentTransitionProcessor processor,
            InMemoryDocumentStore documents,
            EngineMetrics metrics,
            LongSupplier applicationTimestamp,
            Consumer<DefaultCoordinationEngine.FailurePoint> failureInjector) {
        this(
                runtime,
                objects,
                entryFactory,
                journal,
                routes,
                processor,
                documents,
                metrics,
                applicationTimestamp,
                failureInjector,
                new RecoveryState());
    }

    private SequentialDrainCoordinator(
            BlueRuntime runtime,
            WholeObjectStore objects,
            WholeRequestEntryFactory entryFactory,
            InMemoryTimelineJournal journal,
            OperationRouteIndex routes,
            DocumentTransitionProcessor processor,
            InMemoryDocumentStore documents,
            EngineMetrics metrics,
            LongSupplier applicationTimestamp,
            Consumer<DefaultCoordinationEngine.FailurePoint> failureInjector,
            RecoveryState recoveryState) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.entryFactory = Objects.requireNonNull(
                entryFactory, "entryFactory");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.applicationTimestamp = Objects.requireNonNull(
                applicationTimestamp, "applicationTimestamp");
        this.failureInjector = Objects.requireNonNull(
                failureInjector, "failureInjector");
        this.recoveryState = Objects.requireNonNull(
                recoveryState, "recoveryState");
        this.cursors = recoveryState.cursors;
        this.embeddedAdmissions = recoveryState.embeddedAdmissions;
        this.exactEmbeddedAdmissions = recoveryState.exactEmbeddedAdmissions;
        this.barriers = recoveryState.barriers;
        this.openBarrierByParent = recoveryState.openBarrierByParent;
        this.activationGenerations = recoveryState.activationGenerations;
        this.openFrames = recoveryState.openFrames;
        this.rejectedExternalEntryIds =
                recoveryState.rejectedExternalEntryIds;
        this.pendingTopLevelAdmissions =
                recoveryState.pendingTopLevelAdmissions;
        this.embeddedInputHeadByParent =
                recoveryState.embeddedInputHeadByParent;
        this.committedEmbeddedInputs = recoveryState.committedEmbeddedInputs;
        this.graph = recoveryState.graph;
        this.processedThrough = recoveryState.processedThrough;
        this.committedTransitionSequence =
                recoveryState.committedTransitionSequence;
        validateRecoveredState();
    }

    synchronized SequentialDrainCoordinator restartFromStores(
            Consumer<DefaultCoordinationEngine.FailurePoint>
                    restartedFailureInjector) {
        if (!publicationCheckpoints.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot restart inside a publication savepoint");
        }
        metrics.increment("temporal.coordinatorRestarts");
        return new SequentialDrainCoordinator(
                runtime,
                objects,
                entryFactory,
                journal,
                routes,
                processor,
                documents,
                metrics,
                applicationTimestamp,
                restartedFailureInjector,
                recoveryState);
    }

    synchronized void observeTransitions(Consumer<TransitionTrace> observer) {
        transitionObserver = Objects.requireNonNull(observer, "observer");
    }
    synchronized ProcessingDrainReceipt drain(
            ExternalOrderKey inclusiveCutoff, DrainBudget budget) {
        long started = System.nanoTime();
        long sequenceBefore = committedTransitionSequence;
        List<TimelineEntry> processed = new ArrayList<>();
        Map<String, List<DocumentDispatchOutcome>> publicOutcomes =
                new LinkedHashMap<>();
        boolean prerequisitesReady = false;
        boolean paused = false;
        if (activeBudget != null) {
            throw new IllegalStateException("A drain is already active");
        }
        DrainBudget checkedBudget = Objects.requireNonNull(budget, "budget");
        activeBudget = checkedBudget.maxCommittedProcessTransitions()
                == Long.MAX_VALUE && checkedBudget.maxSelectedEntries()
                == Long.MAX_VALUE ? null : checkedBudget;
        drainSequenceBefore = sequenceBefore;
        drainOutcomes = publicOutcomes;
        try {
            completePendingAdmissionInitializations();
            prerequisitesReady = settleOpenBarriers()
                    && resumeTopLevelAdmissions();
            while (prerequisitesReady) {
                Optional<TimelineEntry> next = journal.nextExternal(
                        processedThrough, inclusiveCutoff);
                if (next.isEmpty()) {
                    break;
                }
                TimelineEntry entry = next.orElseThrow();
                List<InternalProcessOutcome> outcomes;
                try {
                    outcomes = processEntry(entry);
                } catch (DeferredWorkException deferred) {
                    prerequisitesReady = false;
                    break;
                } catch (RejectableExternalEntryException rejected) {
                    rejectExternalEntry(entry, rejected);
                    throw rejected.failure();
                }
                outcomesByEntry.put(entry.blueId(), List.copyOf(outcomes));
                processedThrough = entry.sourceOrderKey();
                recoveryState.processedThrough = processedThrough;
                openFrames.remove(entry.blueId());
                processed.add(entry);
                metrics.increment("temporal.externalEntriesCompleted");
                completePendingAdmissionInitializations();
                prerequisitesReady = settleOpenBarriers()
                        && resumeTopLevelAdmissions();
            }
        } catch (DrainPausedException ignored) {
            paused = true;
            metrics.increment("temporal.drainsPaused");
        } finally {
            activeBudget = null;
            drainOutcomes = null;
            drainSelectedEntries.clear();
        }
        long elapsed = System.nanoTime() - started;
        metrics.addNanos("temporal.drain", elapsed);
        boolean quiescent = !paused
                && journal.nextExternal(processedThrough, inclusiveCutoff)
                        .isEmpty() && openBarrierByParent.isEmpty()
                && pendingTopLevelAdmissions.isEmpty()
                && prerequisitesReady;
        return new ProcessingDrainReceipt(
                processed,
                publicOutcomes,
                processedThrough,
                quiescent,
                paused,
                committedTransitionSequence - sequenceBefore,
                elapsed);
    }

    private void rejectExternalEntry(
            TimelineEntry entry,
            RejectableExternalEntryException rejected) {
        if (!entry.blueId().equals(rejected.entryBlueId())) {
            throw new IllegalStateException(
                    "Rejected-entry identity does not match selected entry");
        }
        EntryFrame frame = openFrames.get(entry.blueId());
        if (frame == null || frame.directTargets().size() != 1) {
            return;
        }
        if (!rejectedExternalEntryIds.add(entry.blueId())) {
            throw new IllegalStateException(
                    "External entry was already rejected " + entry.blueId());
        }
        processedThrough = entry.sourceOrderKey();
        recoveryState.processedThrough = processedThrough;
        openFrames.remove(entry.blueId());
        outcomesByEntry.remove(entry.blueId());
        metrics.increment("temporal.externalEntriesRejected");
    }

    synchronized void admitTopLevel(
            DocumentSession session,
            CoordinationEngine.AdmissionPolicy policy,
            ExternalOrderKey verifiedFrontier) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(policy, "policy");
        ExternalOrderKey cutoff = journal.latestExternalOrder();
        if (cutoff == null) {
            cutoff = session.readyThrough();
        }
        ExternalOrderKey after = switch (policy) {
            case FULL_HISTORY -> null;
            case FROM_FRONTIER -> Objects.requireNonNull(
                    verifiedFrontier,
                    "verifiedFrontier is required for FROM_FRONTIER");
            case FROM_NOW -> cutoff;
        };
        if (policy != CoordinationEngine.AdmissionPolicy.FROM_NOW) {
            pendingTopLevelAdmissions.put(session.documentId(),
                    new PendingTopLevelAdmission(after, cutoff));
            session.markCatchingUp();
        }
        if (!session.layout().directOccurrences().isEmpty()) {
            TransitionCause cause = TransitionCause.admission(session, cutoff);
            GraphDelta delta = previewGraphDelta(
                    session,
                    session.layout().directOccurrences(),
                    cause);
            publishGraph(session, delta, cause);
            if (policy == CoordinationEngine.AdmissionPolicy.FROM_NOW) {
                if (!settleBarrierFor(session.documentId())) {
                    return;
                }
            } else {
                completeAdmissionInitializationBarrier(
                        session.documentId());
            }
        } else {
            metrics.timed("temporal.graphPublication",
                    session::markGraphPublished);
        }
        if (policy != CoordinationEngine.AdmissionPolicy.FROM_NOW
                && !resumeTopLevelAdmissions()) {
            return;
        }
        publishReadyIfSynchronized(session, cutoff);
    }

    synchronized boolean retainFailedAdmission(DocumentId documentId) {
        DocumentId id = Objects.requireNonNull(documentId, "documentId");
        DocumentSession session = documents.find(id).orElse(null);
        boolean recoverable = session != null
                && (session.status() == SessionStatus.BLOCKED
                || session.epoch() > 0L
                || openBarrierByParent.containsKey(id)
                || !graph.children(id).isEmpty());
        if (!recoverable) {
            pendingTopLevelAdmissions.remove(id);
        }
        return recoverable;
    }

    synchronized void configureEmbeddedAdmission(
            DocumentId documentId,
            ActivationMode mode,
            ExternalOrderKey verifiedCompleteThrough) {
        Objects.requireNonNull(documentId, "documentId");
        validateAdmission(mode, verifiedCompleteThrough);
        if (!graph.parents(documentId).isEmpty()) {
            throw new IllegalStateException(
                    "Cannot change admission evidence for an active child "
                            + documentId);
        }
        embeddedAdmissions.put(documentId, new EmbeddedAdmissionEvidence(
                documentId, null, null, mode, verifiedCompleteThrough,
                "local-journal-complete|" + documentId + "|"
                        + verifiedCompleteThrough, null));
    }

    synchronized void configureEmbeddedAdmission(
            DocumentId parentDocumentId,
            String absoluteChildPath,
            DocumentId childDocumentId,
            String admittedStateBlueId,
            Long admittedEpoch,
            ActivationMode mode,
            ExternalOrderKey verifiedCompleteThrough,
            String completenessProofIdentity,
            String expectedAttachmentEntryBlueId) {
        OccurrenceKey key = new OccurrenceKey(
                Objects.requireNonNull(parentDocumentId, "parentDocumentId"),
                requireAbsolutePath(absoluteChildPath));
        DocumentId child = Objects.requireNonNull(
                childDocumentId, "childDocumentId");
        String state = requireText(admittedStateBlueId, "admittedStateBlueId");
        String proof = requireText(
                completenessProofIdentity, "completenessProofIdentity");
        String entry = requireText(expectedAttachmentEntryBlueId,
                "expectedAttachmentEntryBlueId");
        if (admittedEpoch != null && admittedEpoch < 0L) {
            throw new IllegalArgumentException(
                    "admittedEpoch must be non-negative");
        }
        validateAdmission(mode, verifiedCompleteThrough);
        if (journal.byBlueId(entry).isEmpty()) {
            throw new IllegalArgumentException(
                    "Attachment entry has no exact retained journal evidence");
        }
        if (graph.children(parentDocumentId).stream().anyMatch(binding ->
                binding.absolutePath().equals(absoluteChildPath))) {
            throw new IllegalStateException(
                    "Cannot configure admission for an active occurrence " + key);
        }
        EmbeddedAdmissionEvidence plan = new EmbeddedAdmissionEvidence(
                child, state, admittedEpoch, mode, verifiedCompleteThrough,
                proof, entry);
        if (exactEmbeddedAdmissions.putIfAbsent(key, plan) != null) {
            throw new IllegalStateException(
                    "Admission plan already exists for " + key);
        }
    }

    private void validateAdmission(
            ActivationMode mode,
            ExternalOrderKey verifiedCompleteThrough) {
        Objects.requireNonNull(mode, "mode");
        boolean requiresFrontier = mode == ActivationMode.IMPORT_FROM_FRONTIER
                || mode == ActivationMode.ATTACH_CURRENT_STATE;
        if (requiresFrontier != (verifiedCompleteThrough != null)) {
            throw new IllegalArgumentException(mode + (requiresFrontier
                    ? " requires verified completeness evidence"
                    : " does not accept a completeness frontier"));
        }
        if (verifiedCompleteThrough != null
                && !journal.containsExternalOrder(verifiedCompleteThrough)) {
            throw new IllegalArgumentException(
                    "Completeness frontier has no exact retained journal evidence");
        }
    }

    private static String requireAbsolutePath(String value) {
        String path = requireText(value, "absoluteChildPath");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "absoluteChildPath must be a JSON Pointer");
        }
        return path;
    }

    private static String requireText(String value, String label) {
        String text = Objects.requireNonNull(value, label);
        if (text.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return text;
    }

    synchronized ExternalOrderKey processedThrough() {
        return processedThrough;
    }

    synchronized ProcessEmbeddedGraphSnapshot graphSnapshot() {
        return graph;
    }

    synchronized String applicationReadinessFailure(DocumentSession session) {
        Objects.requireNonNull(session, "session");
        if (!session.isLocallyReady()) {
            return "session status/epoch publication is incomplete: status="
                    + session.status() + ", epoch=" + session.epoch()
                    + ", readyEpoch=" + session.readyEpoch()
                    + ", graphPublishedEpoch="
                    + session.graphPublishedEpoch();
        }
        return synchronizationFailure(session);
    }

    private String synchronizationFailure(DocumentSession session) {
        DocumentId documentId = session.documentId();
        if (openBarrierByParent.containsKey(documentId)) {
            return "an embedded catch-up barrier remains open";
        }
        if (pendingTopLevelAdmissions.containsKey(documentId)) {
            return "top-level historical admission remains pending";
        }
        List<EmbeddingBinding> bindings = graph.children(documentId);
        if (bindings.isEmpty()
                && session.layout().directOccurrences().isEmpty()) {
            return null;
        }
        Map<String, EmbeddedOccurrence> occurrences = byPath(
                session.layout().directOccurrences());
        if (occurrences.size() != bindings.size()) {
            return "published graph does not match current Process Embedded "
                    + "occurrence count";
        }
        for (EmbeddingBinding binding : bindings) {
            EmbeddedOccurrence occurrence = occurrences.get(
                    binding.absolutePath());
            if (occurrence == null || !occurrence.childDocumentId().equals(
                    binding.childDocumentId())) {
                return "published graph does not match current occurrence at "
                        + binding.absolutePath();
            }
            EmbeddedEpochCursor cursor = cursors.get(binding.bindingId());
            if (cursor == null) {
                return "missing embedded epoch cursor for "
                        + binding.bindingId();
            }
            DocumentSession child = documents.find(
                    binding.childDocumentId()).orElse(null);
            if (child == null) {
                return "missing managed child " + binding.childDocumentId();
            }
            if (cursor.appliedChildEpoch() != child.epoch()) {
                return "parent cursor " + cursor.appliedChildEpoch()
                        + " is behind child epoch " + child.epoch()
                        + " at " + binding.absolutePath();
            }
            String expected = child.revision(
                    cursor.appliedChildEpoch()).after().blueId();
            String actual = session.currentRevision().after()
                    .canonicalBlueIdAt(binding.absolutePath());
            if (!expected.equals(actual)) {
                return "parent state/cursor mismatch at "
                        + binding.absolutePath();
            }
        }
        return null;
    }

    synchronized List<EmbeddingBinding> bindingsForParent(DocumentId parent) {
        return graph.children(parent);
    }

    synchronized EmbeddedEpochCursor cursor(String bindingId) {
        EmbeddedEpochCursor cursor = cursors.get(bindingId);
        if (cursor == null) {
            throw new IllegalArgumentException(
                    "Unknown embedded cursor " + bindingId);
        }
        return cursor;
    }

    synchronized List<CatchUpBarrier> barrierEvidence() {
        return barriers.values().stream()
                .map(CatchUpBarrier::copy)
                .toList();
    }

    synchronized Set<String> effectiveTimelineIds(DocumentId documentId) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectTimelineIds(documentId, result, new LinkedHashSet<>());
        return Collections.unmodifiableSet(result);
    }

    private void selectEntry(TimelineEntry entry) {
        String id = entry.blueId();
        if (activeBudget == null) {
            return;
        }
        if (!drainSelectedEntries.contains(id) && drainSelectedEntries.size()
                >= activeBudget.maxSelectedEntries()) {
            throw DrainPausedException.INSTANCE;
        }
        drainSelectedEntries.add(id);
    }

    private void requireTransitionBudget() {
        if (activeBudget != null && committedTransitionSequence
                - drainSequenceBefore
                >= activeBudget.maxCommittedProcessTransitions()) {
            throw DrainPausedException.INSTANCE;
        }
    }

    private void recordDrainOutcome(
            String entryBlueId, InternalProcessOutcome outcome) {
        if (drainOutcomes != null) {
            drainOutcomes.computeIfAbsent(entryBlueId,
                    ignored -> new ArrayList<>()).add(publicOutcome(outcome));
        }
    }

    private List<InternalProcessOutcome> processEntry(TimelineEntry entry) {
        EntryFrame frame = openFrames.computeIfAbsent(
                entry.blueId(), ignored -> new EntryFrame(
                        entry,
                        graph,
                        new LinkedHashSet<>(routes.route(entry))));
        return processEntryFrame(frame, null);
    }

    private List<InternalProcessOutcome> processEntryFrame(
            EntryFrame frame,
            Set<DocumentId> admittedSubtree) {
        selectEntry(frame.entry());
        List<InternalProcessOutcome> outcomes = new ArrayList<>();
        ExternalOrderKey frameCutoff = exclusiveAfter(
                frame.entry().sourceOrderKey());
        for (DocumentId documentId : frame.graph()
                .childFirstClosure(frame.directTargets())) {
            if (admittedSubtree != null
                    && !admittedSubtree.contains(documentId)) {
                continue;
            }
            applyPendingChildren(
                    documentId, frame.graph(), outcomes, frameCutoff);
            if (frame.directTargets().contains(documentId)) {
                processExternalDocument(
                        documents.require(documentId),
                        frame.entry(),
                        outcomes,
                        null);
            }
        }
        return outcomes;
    }

    private long processHistoricalTargets(
            TimelineEntry entry,
            Set<DocumentId> directTargets,
            List<InternalProcessOutcome> outcomes,
            ExternalOrderKey catchUpCutoff) {
        selectEntry(entry);
        ProcessEmbeddedGraphSnapshot snapshot = graph;
        long processedTargets = 0L;
        List<DocumentId> childFirst = snapshot
                .childFirstClosure(directTargets).stream()
                .filter(directTargets::contains)
                .toList();
        for (DocumentId documentId : childFirst) {
            applyPendingChildren(
                    documentId,
                    snapshot,
                    outcomes,
                    exclusiveAfter(entry.sourceOrderKey()));
            DocumentSession session = documents.require(documentId);
            boolean alreadyProcessed = session.hasTerminalEntry(
                    entry.blueId());
            processExternalDocument(
                    session, entry, outcomes, catchUpCutoff);
            if (!alreadyProcessed) {
                processedTargets++;
            }
        }
        return processedTargets;
    }

    private void processExternalDocument(
            DocumentSession session,
            TimelineEntry entry,
            List<InternalProcessOutcome> outcomes,
            ExternalOrderKey catchUpCutoff) {
        TransitionCause externalCause = catchUpCutoff == null
                ? TransitionCause.external(entry)
                : TransitionCause.external(entry, catchUpCutoff);
        if (session.hasTerminalEntry(entry.blueId())) {
            metrics.increment("temporal.externalProcessDeduplicated");
            DocumentRevision committed = session.revisionForEntry(
                    entry.blueId()).orElseThrow(() ->
                    new IllegalStateException(
                            "Committed entry receipt has no revision "
                                    + entry.blueId()));
            String retryCounter = session.graphPublishedEpoch()
                    < session.epoch()
                    ? "temporal.parentProcessRerunsOnGraphRetry"
                    : graph.parents(session.documentId()).isEmpty()
                            ? null
                            : "temporal.childProcessRerunsOnParentRetry";
            long processCallsBefore = metrics.counter(
                    "process.externalInvocationsStarted");
            try {
                reconcileCommittedTransition(session, externalCause);
            } finally {
                if (retryCounter != null) {
                    metrics.add(retryCounter, metrics.counter(
                            "process.externalInvocationsStarted")
                            - processCallsBefore);
                }
            }
            outcomes.add(new InternalProcessOutcome(
                    session,
                    committed,
                    session.layout(),
                    session.layout(),
                    0L));
            return;
        }
        if (session.status() == SessionStatus.CATCHING_UP) {
            requireBarrierReady(session.documentId());
        }
        if (session.status() == SessionStatus.BLOCKED
                || session.status() == SessionStatus.TERMINATED) {
            throw new IllegalStateException(
                    "Document " + session.documentId()
                            + " cannot process external work while "
                            + session.status());
        }
        requireTransitionBudget();
        EngineMetrics.MetricsSnapshot traceBefore = transitionObserver == null
                ? null : metrics.snapshot();
        long traceStarted = traceBefore == null ? 0L : System.nanoTime();
        DocumentTransitionProcessor.Prepared prepared =
                processor.prepare(session, entry);
        TransitionCause cause = externalCause;
        GraphDelta delta = graphDelta(
                session,
                prepared.beforeLayout().directOccurrences(),
                prepared.afterLayout().directOccurrences(),
                cause);
        boolean routePublicationRequired = routesChanged(
                prepared.beforeLayout(), session.activeSubscriptions(),
                prepared.afterLayout(), prepared.activeSubscriptionsAfter());
        try {
            preflightGraphDelta(delta, cause);
        } catch (InvalidAdmissionEvidenceException invalid) {
            throw RejectableExternalEntryException.forEntry(entry, invalid);
        }
        failureInjector.accept(DefaultCoordinationEngine.FailurePoint
                .BEFORE_COMMIT_VALIDATION);
        InternalProcessOutcome outcome = metrics.timed(
                "process.commitReadinessPublication",
                () -> processor.commit(prepared));
        markContainingDocumentsCatchingUp(session.documentId());
        if (!graph.parents(session.documentId()).isEmpty()) {
            metrics.increment("temporal.childEpochsCommitted");
        }
        committedTransitionSequence = Math.addExact(
                committedTransitionSequence, 1L);
        recoveryState.committedTransitionSequence =
                committedTransitionSequence;
        recordDrainOutcome(entry.blueId(), outcome);
        metrics.increment("deliveryReceiptsCommitted");
        publishCommittedTransition(
                session, delta, cause, routePublicationRequired);
        publishReadyIfSynchronized(session, cause.cutoff());
        failureInjector.accept(DefaultCoordinationEngine.FailurePoint
                .AFTER_STATE_SWAP_BEFORE_RETURN);
        outcomes.add(outcome);
        metrics.increment("temporal.externalProcessCalls");
        requireBarrierReady(session.documentId());
        trace(outcome.session(), outcome.revision(), traceBefore, traceStarted);
    }

    private void publishReadyIfSynchronized(
            DocumentSession session,
            ExternalOrderKey cutoff) {
        long started = System.nanoTime();
        String failure = synchronizationFailure(session);
        if (failure != null) {
            session.markCatchingUp();
            metrics.increment("temporal.readyPublicationDeferred");
            metrics.addNanos("process.commitReadinessPublication",
                    System.nanoTime() - started);
            return;
        }
        session.markReady(cutoff);
        metrics.addNanos("process.commitReadinessPublication",
                System.nanoTime() - started);
    }
    private void markContainingDocumentsCatchingUp(DocumentId childId) {
        List<EmbeddingBinding> parents = graph.parents(childId);
        if (parents.isEmpty()) {
            return;
        }
        DocumentSession child = documents.require(childId);
        for (EmbeddingBinding binding : parents) {
            EmbeddedEpochCursor cursor = cursors.get(binding.bindingId());
            if (cursor == null || cursor.appliedChildEpoch() >= child.epoch()) {
                continue;
            }
            DocumentSession parent = documents.require(
                    binding.parentDocumentId());
            if (parent.status() == SessionStatus.BLOCKED
                    || parent.status() == SessionStatus.TERMINATED) {
                metrics.increment("temporal.parentLagRetainedWhileNonReady");
                continue;
            }
            parent.markCatchingUp();
            metrics.increment("temporal.parentsMarkedBehind");
        }
    }
    private void requireBarrierReady(DocumentId parent) {
        if (!settleBarrierFor(parent)) {
            throw DeferredWorkException.INSTANCE;
        }
    }

    private void applyPendingChildren(
            DocumentId parentId,
            ProcessEmbeddedGraphSnapshot snapshot,
            List<InternalProcessOutcome> outcomes) {
        applyPendingChildren(parentId, snapshot, outcomes, null);
    }

    private void applyPendingChildren(
            DocumentId parentId,
            ProcessEmbeddedGraphSnapshot snapshot,
            List<InternalProcessOutcome> outcomes,
            ExternalOrderKey cutoffExclusive) {
        for (EmbeddingBinding binding : snapshot.children(parentId)) {
            if (!graph.containsBinding(binding.bindingId())) {
                continue;
            }
            applyAllAvailable(binding, outcomes, cutoffExclusive);
        }
    }

    private void applyAllAvailable(
            EmbeddingBinding binding,
            List<InternalProcessOutcome> outcomes,
            ExternalOrderKey cutoffExclusive) {
        DocumentSession child = documents.require(
                binding.childDocumentId());
        while (true) {
            EmbeddedEpochCursor cursor = cursors.get(binding.bindingId());
            if (cursor == null || cursor.appliedChildEpoch() >= child.epoch()) {
                return;
            }
            DocumentRevision revision = child.revision(
                    cursor.appliedChildEpoch() + 1L);
            if (cutoffExclusive != null
                    && revision.kind() != DocumentRevision.Kind.INITIALIZATION
                    && revision.sourceOrderKey().map(order ->
                            order.compareTo(cutoffExclusive) >= 0)
                    .orElse(false)) {
                return;
            }
            applyChildRevision(binding, revision, outcomes);
        }
    }

    private void applyChildRevision(
            EmbeddingBinding binding,
            DocumentRevision childRevision,
            List<InternalProcessOutcome> outcomes) {
        DocumentSession parent = documents.require(
                binding.parentDocumentId());
        String inputId = embeddedTransitionReceiptId(
                binding.bindingId(), childRevision.epoch());
        EmbeddedEpochCursor current = cursors.get(binding.bindingId());
        if (parent.hasTransitionReceipt(inputId)) {
            EmbeddedEpochInput committedInput = committedEmbeddedInputs.get(
                    inputId);
            requireCommittedEmbeddedInput(
                    parent, binding, childRevision, committedInput, inputId);
            TransitionCause cause = TransitionCause.embedded(committedInput);
            reconcileCommittedTransition(parent, cause);
            recordCursor(binding.bindingId());
            cursors.put(binding.bindingId(),
                    current.advanceTo(childRevision.epoch()));
            publishReadyIfSynchronized(parent, cause.cutoff());
            metrics.increment("temporal.embeddedCommitCompanionRecoveries");
            return;
        }
        requireTransitionBudget();
        EngineMetrics.MetricsSnapshot traceBefore = transitionObserver == null
                ? null : metrics.snapshot();
        long traceStarted = traceBefore == null ? 0L : System.nanoTime();
        long inputStarted = System.nanoTime();
        EmbeddedEpochInput input = EmbeddedEpochInput.create(
                entryFactory,
                objects,
                binding,
                childRevision,
                applicationTimestamp.getAsLong(),
                embeddedInputHeadByParent.get(
                        binding.parentDocumentId()));
        if (!inputId.equals(input.inputId())) {
            throw new IllegalStateException(
                    "Embedded input identity does not match its receipt");
        }
        long inputNanos = System.nanoTime() - inputStarted;
        metrics.addNanos("process.embeddedInputPreparation", inputNanos);
        metrics.addNanos("process.hostBeforeFrozen", inputNanos);
        DocumentTransitionProcessor.PreparedEmbedded prepared =
                processor.prepareEmbedded(parent, input);
        TransitionCause cause = TransitionCause.embedded(input);
        GraphDelta delta = graphDelta(
                parent,
                prepared.beforeLayout().directOccurrences(),
                prepared.afterLayout().directOccurrences(),
                cause);
        boolean routePublicationRequired = routesChanged(
                prepared.beforeLayout(), parent.activeSubscriptions(),
                prepared.afterLayout(), prepared.activeSubscriptionsAfter());
        preflightGraphDelta(delta, cause);
        InternalProcessOutcome outcome = metrics.timed(
                "process.commitReadinessPublication",
                () -> processor.commitEmbedded(prepared));
        EmbeddedEpochInput duplicate = committedEmbeddedInputs.putIfAbsent(
                inputId, input);
        if (duplicate != null) {
            throw new IllegalStateException(
                    "Duplicate committed embedded input evidence " + inputId);
        }
        if (childRevision.kind() == DocumentRevision.Kind.INITIALIZATION) {
            metrics.increment("temporal.initializationEpochApplications");
            metrics.add("temporal.initializationEventOccurrencesForwarded",
                    childRevision.emittedEvents().size());
        }
        markContainingDocumentsCatchingUp(parent.documentId());
        committedTransitionSequence = Math.addExact(
                committedTransitionSequence, 1L);
        recoveryState.committedTransitionSequence =
                committedTransitionSequence;
        recordDrainOutcome(cause.entryBlueId(), outcome);
        metrics.increment("revisionApplicationReceiptsCommitted");
        recordEmbeddedInputHead(parent.documentId());
        embeddedInputHeadByParent.put(
                parent.documentId(), input.exactEvent().blueId());
        publishCommittedTransition(
                parent, delta, cause, routePublicationRequired);
        failureInjector.accept(DefaultCoordinationEngine.FailurePoint
                .AFTER_APPLYING_CHILD_REVISION);
        recordCursor(binding.bindingId());
        cursors.put(binding.bindingId(),
                current.advanceTo(childRevision.epoch()));
        publishReadyIfSynchronized(parent, cause.cutoff());
        outcomes.add(outcome);
        metrics.increment("temporal.parentEpochApplications");
        metrics.increment("catchUp.parentRevisionApplications");
        metrics.increment("childRevisionApplications");
        trace(outcome.session(), outcome.revision(), traceBefore, traceStarted);
    }

    private static void requireCommittedEmbeddedInput(
            DocumentSession parent,
            EmbeddingBinding binding,
            DocumentRevision childRevision,
            EmbeddedEpochInput committedInput,
            String inputId) {
        if (committedInput == null) {
            throw new IllegalStateException(
                    "Committed embedded receipt has no retained input evidence "
                            + inputId);
        }
        String expectedBefore = childRevision.before()
                .map(value -> value.blueId())
                .orElse(binding.admittedChildBlueId());
        ExternalOrderKey expectedOrder = childRevision.sourceOrderKey()
                .orElse(binding.attachmentOrder());
        String expectedOriginalEntry = childRevision.causalEntryBlueId()
                .orElse(null);
        if (!inputId.equals(committedInput.inputId())
                || !binding.equals(committedInput.binding())
                || committedInput.fromChildEpoch()
                        != childRevision.epoch() - 1L
                || committedInput.toChildEpoch() != childRevision.epoch()
                || !expectedBefore.equals(
                        committedInput.beforeChildBlueId())
                || !childRevision.after().blueId().equals(
                        committedInput.afterChildBlueId())
                || !expectedOrder.equals(committedInput.sourceOrder())
                || !Objects.equals(expectedOriginalEntry,
                        committedInput.originalEntryBlueId())) {
            throw new IllegalStateException(
                    "Committed embedded input evidence does not match receipt "
                            + inputId);
        }
        DocumentRevision committed = parent.currentRevision();
        String expectedCausalEntry = expectedOriginalEntry == null
                ? binding.attachmentEntryBlueId()
                : expectedOriginalEntry;
        DocumentRevision.CatchUpCause catchUpCause = committed.catchUpCause()
                .orElseThrow(() -> new IllegalStateException(
                        "Committed embedded revision has no catch-up cause "
                                + inputId));
        if (committed.kind()
                    != DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION
                || !expectedOrder.equals(
                        committed.sourceOrderKey().orElse(null))
                || !expectedCausalEntry.equals(
                        committed.causalEntryBlueId().orElse(null))
                || !binding.parentDocumentId().equals(
                        catchUpCause.parentDocumentId())
                || !binding.attachmentEntryBlueId().equals(
                        catchUpCause.attachmentEntryBlueId())
                || !binding.absolutePath().equals(
                        catchUpCause.occurrencePath())
                || binding.attachmentTimestampMicros()
                        != catchUpCause.attachmentTimestampMicros()) {
            throw new IllegalStateException(
                    "Committed parent revision does not match embedded receipt "
                            + inputId);
        }
    }

    private GraphDelta previewGraphDelta(
            DocumentSession parent,
            List<EmbeddedOccurrence> afterOccurrences,
            TransitionCause cause) {
        Map<String, EmbeddedOccurrence> after = byPath(afterOccurrences);
        List<EmbeddingBinding> retained = new ArrayList<>();
        List<EmbeddingBinding> removed = new ArrayList<>();
        List<ProposedBinding> additions = new ArrayList<>();
        List<OccurrenceKey> admissionsToConsume = new ArrayList<>();
        Map<String, EmbeddingBinding> currentByPath = new LinkedHashMap<>();
        graph.children(parent.documentId()).forEach(binding ->
                currentByPath.put(binding.absolutePath(), binding));

        for (EmbeddingBinding current : currentByPath.values()) {
            EmbeddedOccurrence occurrence = after.get(current.absolutePath());
            if (occurrence != null && occurrence.childDocumentId().equals(
                    current.childDocumentId())) {
                retained.add(current);
                after.remove(current.absolutePath());
            } else {
                removed.add(current);
            }
        }
        for (EmbeddedOccurrence occurrence : after.values().stream()
                .sorted(Comparator.comparing(
                        EmbeddedOccurrence::scopePath,
                        EmbeddingBinding.TEXT_ORDER))
                .toList()) {
            OccurrenceKey key = new OccurrenceKey(
                    parent.documentId(), occurrence.scopePath());
            EmbeddedAdmissionEvidence exact = exactEmbeddedAdmissions.get(key);
            EmbeddedAdmissionEvidence admission = exact != null
                    ? exact
                    : embeddedAdmissions.getOrDefault(
                    occurrence.childDocumentId(),
                    EmbeddedAdmissionEvidence.fullHistory(
                            occurrence.childDocumentId()));
            admission.requireMatch(occurrence, cause);
            if (exact != null) {
                admissionsToConsume.add(key);
            }
            ActivationMode mode = admission.mode();
            if (mode == ActivationMode.PASSIVE_SNAPSHOT) {
                metrics.increment("embedding.passiveSnapshots");
                continue;
            }
            long generation = Math.addExact(
                    activationGenerations.getOrDefault(key, 0L), 1L);
            String bindingId = bindingId(
                    parent.documentId(), occurrence.scopePath(), generation);
            EmbeddingBinding binding = new EmbeddingBinding(
                    bindingId,
                    parent.documentId(),
                    occurrence.scopePath(),
                    occurrence.childDocumentId(),
                    generation,
                    mode,
                    admission.verifiedCompleteThrough(),
                    occurrence.suppliedState().blueId(),
                    admission.admittedEpoch(),
                    admission.completenessProofIdentity(),
                    cause.entryBlueId(),
                    cause.order());
            retained.add(binding);
            additions.add(new ProposedBinding(
                    binding, occurrence, mode, key));
        }
        retained.sort(EmbeddingBinding.WITHIN_PARENT_ORDER);
        ProcessEmbeddedGraphSnapshot proposed = graph.reconcileParent(
                parent.documentId(), retained, metrics);
        return new GraphDelta(
                proposed,
                List.copyOf(removed),
                List.copyOf(additions),
                List.copyOf(admissionsToConsume));
    }

    private GraphDelta graphDelta(
            DocumentSession parent,
            List<EmbeddedOccurrence> before,
            List<EmbeddedOccurrence> after,
            TransitionCause cause) {
        metrics.increment("temporal.graphIdentityChecks");
        if (sameTopology(before, after)) {
            metrics.increment("temporal.graphIdentityMatches");
            return new GraphDelta(graph, List.of(), List.of(), List.of());
        }
        metrics.increment("temporal.graphDeltaPreviews");
        return previewGraphDelta(parent, after, cause);
    }

    private boolean sameTopology(
            List<EmbeddedOccurrence> before,
            List<EmbeddedOccurrence> after) {
        if (before.size() != after.size()) {
            return false;
        }
        for (int index = 0; index < before.size(); index++) {
            metrics.increment("temporal.graphIdentityOccurrencesCompared");
            EmbeddedOccurrence left = before.get(index);
            EmbeddedOccurrence right = after.get(index);
            if (!left.scopePath().equals(right.scopePath())
                    || !left.childDocumentId().equals(
                    right.childDocumentId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean routesChanged(
            EmbeddedOnlyLayout before,
            List<SubscriptionDelta.Entry> beforeSubscriptions,
            EmbeddedOnlyLayout after,
            List<SubscriptionDelta.Entry> afterSubscriptions) {
        boolean sameSurface = before.plan() == after.plan()
                || before.routingSurface().definitions().equals(
                after.routingSurface().definitions())
                && before.routingSurface().deliversEmbeddedRevisionEvents()
                == after.routingSurface().deliversEmbeddedRevisionEvents();
        return !sameSurface || !beforeSubscriptions.equals(afterSubscriptions);
    }

    private void preflightGraphDelta(
            GraphDelta delta,
            TransitionCause cause) {
        Map<DocumentId, String> newChildStates = new LinkedHashMap<>();
        Map<DocumentId, Long> existingChildEpochs = new LinkedHashMap<>();
        for (ProposedBinding proposed : delta.additions()) {
            EmbeddingBinding binding = proposed.binding();
            DocumentSession child = documents.find(
                    binding.childDocumentId()).orElse(null);
            if (child == null) {
                String priorState = newChildStates.putIfAbsent(
                        binding.childDocumentId(),
                        binding.admittedChildBlueId());
                if (priorState != null && !priorState.equals(
                        binding.admittedChildBlueId())) {
                    throw new InvalidAdmissionEvidenceException(
                            "conflicting supplied states for new child "
                                    + binding.childDocumentId());
                }
                if (binding.activationMode()
                        == ActivationMode.ATTACH_CURRENT_STATE) {
                    throw new IllegalStateException(
                            "ATTACH_CURRENT_STATE requires an existing managed child");
                }
                if (binding.admittedChildEpoch() != null) {
                    throw new IllegalStateException(
                            "Exact admitted epoch requires an existing child");
                }
                continue;
            }
            if (binding.activationMode()
                    == ActivationMode.BIRTH_AT_ATTACHMENT) {
                throw new IllegalStateException(
                        "BIRTH_AT_ATTACHMENT requires a new child DocumentId");
            }
            long suppliedEpoch = child.resolveAdmissionEpoch(
                    binding.admittedChildBlueId(),
                    binding.admittedChildEpoch());
            Long priorEpoch = existingChildEpochs.putIfAbsent(
                    binding.childDocumentId(), suppliedEpoch);
            if (priorEpoch != null && priorEpoch != suppliedEpoch) {
                throw new InvalidAdmissionEvidenceException(
                        "conflicting admitted epochs for child "
                                + binding.childDocumentId());
            }
            if (binding.activationMode()
                    == ActivationMode.ATTACH_CURRENT_STATE
                    && suppliedEpoch != child.epoch()) {
                throw new IllegalStateException(
                        "ATTACH_CURRENT_STATE requires the current child epoch");
            }
            if (binding.activationMode()
                    == ActivationMode.ATTACH_CURRENT_STATE
                    && binding.establishedFrontier().compareTo(
                            cause.order()) < 0) {
                throw new IllegalStateException(
                        "ATTACH_CURRENT_STATE completeness evidence is behind "
                                + "the attachment cutoff");
            }
            if (suppliedEpoch >= 0L) {
                DocumentRevision supplied = child.revision(suppliedEpoch);
                if (supplied.sourceOrderKey()
                        .map(order -> order.compareTo(cause.order()) >= 0)
                        .orElse(false)) {
                    throw new IllegalStateException(
                            "Supplied child state is ahead of attachment cutoff "
                                    + cause.order());
                }
            }
        }
    }

    private void reconcileCommittedTransition(
            DocumentSession session,
            TransitionCause cause) {
        GraphDelta delta = previewGraphDelta(
                session,
                session.layout().directOccurrences(),
                cause);
        preflightGraphDelta(delta, cause);
        markContainingDocumentsCatchingUp(session.documentId());
        publishCommittedTransition(session, delta, cause, true);
        publishReadyIfSynchronized(session, cause.cutoff());
    }

    private void publishCommittedTransition(
            DocumentSession session,
            GraphDelta delta,
            TransitionCause cause,
            boolean routesChanged) {
        if (routesChanged) {
            routes.replace(
                    session.documentId(),
                    session.layout().routingSurface(),
                    session.activeSubscriptions());
        } else {
            metrics.increment("routing.surfacePublicationsSkipped");
        }
        publishGraph(session, delta, cause);
    }

    private void publishGraph(
            DocumentSession session,
            GraphDelta delta,
            TransitionCause cause) {
        metrics.timed("temporal.graphPublication", () -> {
            publishGraphDeltaAtomically(session, delta, cause);
            session.markGraphPublished();
        });
    }
    private void publishGraphDeltaAtomically(
            DocumentSession parent,
            GraphDelta delta,
            TransitionCause cause) {
        if (delta.proposed() == graph
                && delta.admissionsToConsume().isEmpty()) {
            metrics.increment("temporal.graphSnapshotsReused");
            return;
        }
        try {
            runPublicationAtomically(() ->
                    publishGraphDelta(parent, delta, cause));
        } finally {
            metrics.add("embedding.exactAdmissionPlansConsumed",
                    delta.admissionsToConsume().stream().filter(key ->
                            !exactEmbeddedAdmissions.containsKey(key)).count());
        }
    }

    private void runPublicationAtomically(Runnable publication) {
        PublicationCheckpoint checkpoint = new PublicationCheckpoint(
                committedTransitionSequence,
                graph,
                objects.mark());
        publicationCheckpoints.add(checkpoint);
        try {
            publication.run();
            objects.commit(checkpoint.objectMark());
        } catch (RuntimeException failure) {
            if (committedTransitionSequence
                    == checkpoint.committedTransitionSequence()) {
                try {
                    rollbackPublication(checkpoint);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            } else {
                objects.commit(checkpoint.objectMark());
            }
            throw failure;
        } finally {
            PublicationCheckpoint removed = publicationCheckpoints.remove(
                    publicationCheckpoints.size() - 1);
            if (removed != checkpoint) {
                throw new IllegalStateException(
                        "Publication checkpoint stack is corrupted");
            }
        }
    }

    private void rollbackPublication(PublicationCheckpoint checkpoint) {
        for (DocumentId childId : checkpoint.newChildIds()) {
            DocumentSession child = documents.find(childId).orElse(null);
            if (child != null && child.epoch() != 0L) {
                throw new IllegalStateException(
                        "Cannot roll back committed child " + childId);
            }
        }
        graph = checkpoint.graphBefore();
        recoveryState.graph = graph;
        restoreEntries(cursors, checkpoint.cursorsBefore());
        restoreEntries(
                activationGenerations,
                checkpoint.activationGenerationsBefore());
        exactEmbeddedAdmissions.putAll(checkpoint.consumedAdmissions());
        restoreEntries(
                openBarrierByParent,
                checkpoint.openBarriersBefore());
        restoreEntries(
                embeddedInputHeadByParent,
                checkpoint.inputHeadsBefore());
        checkpoint.barriersBefore().forEach((id, prior) -> {
            if (prior.present()) {
                barriers.put(id, prior.value().copy());
            } else {
                barriers.remove(id);
            }
        });
        checkpoint.sessionProgressBefore().forEach((documentId, progress) ->
                documents.find(documentId).ifPresent(session ->
                        session.restoreCoordinationState(
                                progress.status(),
                                progress.readyThrough(),
                                progress.readyEpoch(),
                                progress.graphPublishedEpoch())));
        for (DocumentId childId : checkpoint.newChildIds()) {
            DocumentSession child = documents.find(childId).orElse(null);
            if (child == null) {
                continue;
            }
            routes.remove(childId);
            documents.remove(childId);
        }
        objects.rollbackTo(checkpoint.objectMark());
    }

    private static <K, V> void restoreEntries(
            Map<K, V> target,
            Map<K, Prior<V>> before) {
        before.forEach((key, prior) -> {
            if (prior.present()) {
                target.put(key, prior.value());
            } else {
                target.remove(key);
            }
        });
    }

    private void recordCursor(String bindingId) {
        publicationCheckpoints.forEach(checkpoint ->
                checkpoint.recordCursor(bindingId, cursors));
    }

    private void recordActivationGeneration(OccurrenceKey occurrence) {
        publicationCheckpoints.forEach(checkpoint ->
                checkpoint.recordActivationGeneration(
                        occurrence, activationGenerations));
    }

    private void consumeExactAdmission(OccurrenceKey occurrence) {
        EmbeddedAdmissionEvidence plan = Objects.requireNonNull(
                exactEmbeddedAdmissions.remove(occurrence),
                "exact admission plan");
        publicationCheckpoints.forEach(checkpoint ->
                checkpoint.recordConsumedAdmission(occurrence, plan));
    }

    private void recordBarrier(String barrierId) {
        publicationCheckpoints.forEach(checkpoint ->
                checkpoint.recordBarrier(barrierId, barriers));
    }

    private void recordOpenBarrier(DocumentId parent) {
        publicationCheckpoints.forEach(checkpoint ->
                checkpoint.recordOpenBarrier(parent, openBarrierByParent));
    }

    private void recordEmbeddedInputHead(DocumentId parent) {
        publicationCheckpoints.forEach(checkpoint ->
                checkpoint.recordInputHead(parent, embeddedInputHeadByParent));
    }

    private void recordSessionProgress(DocumentSession session) {
        publicationCheckpoints.forEach(checkpoint ->
                checkpoint.recordSessionProgress(session));
    }

    private void recordNewChild(DocumentId childId) {
        publicationCheckpoints.forEach(checkpoint ->
                checkpoint.recordNewChild(childId));
    }

    private void publishGraphDelta(
            DocumentSession parent,
            GraphDelta delta,
            TransitionCause cause) {
        if (delta.requiresSynchronization()
                && parent.status() != SessionStatus.CATCHING_UP) {
            recordSessionProgress(parent);
            parent.markCatchingUp();
        }
        for (OccurrenceKey key : delta.admissionsToConsume()) {
            consumeExactAdmission(key);
        }
        if (delta.proposed() == graph) {
            metrics.increment("temporal.graphSnapshotsReused");
            return;
        }
        for (EmbeddingBinding removed : delta.removed()) {
            recordCursor(removed.bindingId());
            cursors.remove(removed.bindingId());
            metrics.increment("embedding.bindingsRetired");
        }
        graph = delta.proposed();
        recoveryState.graph = graph;
        metrics.increment("temporal.graphReconciliations");
        metrics.increment("temporal.graphGenerationsPublished");
        if (delta.additions().isEmpty()) {
            return;
        }
        CatchUpBarrier barrier = createOrExtendBarrier(
                parent.documentId(), cause);
        List<AdmittedChild> admittedChildren = new ArrayList<>();
        for (ProposedBinding proposed : delta.additions()) {
            recordActivationGeneration(proposed.occurrenceKey());
            activationGenerations.put(
                    proposed.occurrenceKey(),
                    proposed.binding().activationGeneration());
            recordBarrier(barrier.barrierId());
            barrier.extend(proposed.binding().bindingId());
            admittedChildren.add(admitOrReuseChild(
                    proposed, cause, barrier));
        }
        for (AdmittedChild admitted : admittedChildren) {
            publishNestedChild(admitted, cause, barrier);
        }
    }

    private AdmittedChild admitOrReuseChild(
            ProposedBinding proposed,
            TransitionCause cause,
            CatchUpBarrier parentBarrier) {
        EmbeddingBinding binding = proposed.binding();
        DocumentSession child = documents.find(
                binding.childDocumentId()).orElse(null);
        long suppliedEpoch;
        boolean created = false;
        if (child == null) {
            EngineMetrics.MetricsSnapshot traceBefore = transitionObserver == null
                    ? null : metrics.snapshot();
            long traceStarted = traceBefore == null ? 0L : System.nanoTime();
            child = processor.admitExact(
                    binding.childDocumentId(),
                    proposed.occurrence().suppliedState(),
                    childAdmissionFrontier(binding),
                    catchUpCause(binding, cause.timestampMicros()));
            long commitStarted = System.nanoTime();
            documents.insert(child);
            recordNewChild(child.documentId());
            routes.replace(child.documentId(),
                    child.layout().routingSurface(),
                    child.activeSubscriptions());
            suppliedEpoch = -1L;
            created = true;
            metrics.increment("embedding.childSessionsCreated");
            metrics.increment("sessionsCreated");
            metrics.addNanos("process.commitReadinessPublication",
                    System.nanoTime() - commitStarted);
            if (child.layout().directOccurrences().isEmpty()) {
                metrics.timed("temporal.graphPublication",
                        child::markGraphPublished);
            }
            failureInjector.accept(DefaultCoordinationEngine.FailurePoint
                    .AFTER_STAGING_CHILD_SESSION);
            trace(child, child.revision(0L), traceBefore, traceStarted);
        } else {
            try {
                suppliedEpoch = child.resolveAdmissionEpoch(
                        binding.admittedChildBlueId(),
                        binding.admittedChildEpoch());
            } catch (IllegalStateException invalidAdmission) {
                recordBarrier(parentBarrier.barrierId());
                parentBarrier.block(invalidAdmission.getMessage());
                throw invalidAdmission;
            }
            metrics.increment("embedding.childSessionsReused");
        }
        recordCursor(binding.bindingId());
        cursors.put(binding.bindingId(), new EmbeddedEpochCursor(
                binding.bindingId(), suppliedEpoch));
        initializeHistoricalProgress(parentBarrier, binding, child,
                suppliedEpoch, proposed.mode());
        return new AdmittedChild(proposed, child, created);
    }

    private void publishNestedChild(
            AdmittedChild admitted,
            TransitionCause cause,
            CatchUpBarrier parentBarrier) {
        DocumentSession child = admitted.child();
        EmbeddingBinding binding = admitted.proposed().binding();
        if (admitted.created()
                && !child.layout().directOccurrences().isEmpty()) {
            runPublicationAtomically(() -> {
                TransitionCause nestedCause =
                        TransitionCause.nestedAdmission(binding, cause);
                GraphDelta nested = previewGraphDelta(
                        child,
                        child.layout().directOccurrences(),
                        nestedCause);
                publishGraph(child, nested, nestedCause);
                String nestedBarrierId = openBarrierByParent.get(
                        child.documentId());
                if (nestedBarrierId != null) {
                    recordBarrier(parentBarrier.barrierId());
                    parentBarrier.addNestedBarrier(nestedBarrierId);
                }
            });
        }
    }

    private void initializeHistoricalProgress(
            CatchUpBarrier barrier,
            EmbeddingBinding binding,
            DocumentSession child,
            long suppliedEpoch,
            ActivationMode mode) {
        ExternalOrderKey progress = null;
        if (mode == ActivationMode.BIRTH_AT_ATTACHMENT) {
            progress = binding.attachmentOrder();
        } else if (mode == ActivationMode.IMPORT_FROM_FRONTIER) {
            progress = binding.establishedFrontier();
        } else if (mode == ActivationMode.ATTACH_CURRENT_STATE) {
            if (suppliedEpoch != child.epoch()) {
                throw new IllegalStateException(
                        "ATTACH_CURRENT_STATE requires the current child epoch");
            }
            progress = barrier.cutoffExclusive();
        } else if (suppliedEpoch >= 0L) {
            progress = child.revision(suppliedEpoch)
                    .sourceOrderKey().orElse(null);
        }
        if (progress != null) {
            recordBarrier(barrier.barrierId());
            barrier.recordProgress(binding.bindingId(), progress);
        }
    }

    private static ExternalOrderKey childAdmissionFrontier(
            EmbeddingBinding binding) {
        return switch (binding.activationMode()) {
            case BIRTH_AT_ATTACHMENT -> binding.attachmentOrder();
            case IMPORT_FROM_FRONTIER, ATTACH_CURRENT_STATE ->
                    binding.establishedFrontier();
            case IMPORT_FULL_HISTORY ->
                    DocumentTransitionProcessor.fullHistoryFrontier(
                            binding.childDocumentId());
            case PASSIVE_SNAPSHOT -> throw new IllegalStateException(
                    "Passive snapshots do not create child sessions");
        };
    }

    private CatchUpBarrier createOrExtendBarrier(
            DocumentId parent,
            TransitionCause cause) {
        String existingId = openBarrierByParent.get(parent);
        if (existingId != null) {
            CatchUpBarrier existing = barriers.get(existingId);
            if (existing != null
                    && existing.status() != CatchUpBarrier.Status.COMPLETE
                    && existing.status() != CatchUpBarrier.Status.BLOCKED) {
                metrics.increment("temporal.catchUpBarriersExtended");
                return existing;
            }
        }
        String id = parent.value() + "|" + cause.entryBlueId();
        CatchUpBarrier barrier = new CatchUpBarrier(
                id, parent, cause.entryBlueId(), cause.cutoff());
        recordBarrier(id);
        barriers.put(id, barrier);
        recordOpenBarrier(parent);
        openBarrierByParent.put(parent, id);
        registerWithContainingBarriers(barrier);
        metrics.increment("temporal.catchUpBarriersCreated");
        return barrier;
    }

    private void registerWithContainingBarriers(CatchUpBarrier nested) {
        for (String candidateId : List.copyOf(
                openBarrierByParent.values())) {
            if (candidateId.equals(nested.barrierId())) {
                continue;
            }
            CatchUpBarrier candidate = barriers.get(candidateId);
            boolean containsParent = candidate != null
                    && barrierBindings(candidate).stream().anyMatch(binding ->
                    binding.childDocumentId().equals(
                            nested.parentDocumentId()));
            if (containsParent) {
                recordBarrier(candidateId);
                candidate.addNestedBarrier(nested.barrierId());
            }
        }
    }

    private boolean settleOpenBarriers() {
        boolean ready = true;
        Set<String> nested = new LinkedHashSet<>();
        barriers.values().forEach(barrier ->
                nested.addAll(barrier.nestedBarrierIds()));
        for (String barrierId : List.copyOf(openBarrierByParent.values())) {
            if (!nested.contains(barrierId)) {
                ready &= settleBarrier(barrierId);
            }
        }
        return ready;
    }

    private void completePendingAdmissionInitializations() {
        for (DocumentId documentId : new ArrayList<>(
                pendingTopLevelAdmissions.keySet())) {
            completeAdmissionInitializationBarrier(documentId);
        }
    }

    private void completeAdmissionInitializationBarrier(DocumentId rootId) {
        String barrierId = openBarrierByParent.get(rootId);
        if (barrierId == null) {
            return;
        }
        CatchUpBarrier rootBarrier = barriers.get(barrierId);
        if (rootBarrier == null || !rootBarrier.attachmentEntryBlueId().equals(
                documents.require(rootId).revision(0L).causalEntryBlueId()
                        .orElseThrow())) {
            return;
        }
        List<InternalProcessOutcome> ignoredOutcomes = new ArrayList<>();
        List<BarrierLevel> tree = initializeBarrierTree(
                barrierId, ignoredOutcomes);
        for (BarrierLevel level : tree) {
            CatchUpBarrier barrier = level.barrier();
            if (barrier.status() == CatchUpBarrier.Status.BLOCKED) {
                throw new IllegalStateException(barrier.diagnostic());
            }
            if (barrier.status() != CatchUpBarrier.Status.COMPLETE) {
                recordBarrier(barrier.barrierId());
                barrier.complete();
                metrics.increment("temporal.catchUpBarriersCompleted");
            }
            recordOpenBarrier(barrier.parentDocumentId());
            openBarrierByParent.remove(
                    barrier.parentDocumentId(), barrier.barrierId());
            DocumentSession parent = documents.require(
                    barrier.parentDocumentId());
            recordSessionProgress(parent);
            if (parent.documentId().equals(rootId)) {
                parent.markCatchingUp();
            } else {
                publishReadyIfSynchronized(parent, parent.readyThrough());
            }
        }
    }

    private boolean settleBarrierFor(DocumentId parent) {
        String barrierId = openBarrierByParent.get(parent);
        return barrierId == null
                || settlingBarrierTree
                || settleBarrier(barrierId);
    }

    private boolean settleBarrier(String barrierId) {
        CatchUpBarrier barrier = barriers.get(barrierId);
        if (barrier == null || barrier.status() == CatchUpBarrier.Status.COMPLETE) {
            return true;
        }
        if (barrier.status() == CatchUpBarrier.Status.BLOCKED) {
            throw new IllegalStateException(barrier.diagnostic());
        }
        DocumentSession parent = documents.require(
                barrier.parentDocumentId());
        List<InternalProcessOutcome> ignoredOutcomes = new ArrayList<>();
        boolean previousSettlement = settlingBarrierTree;
        settlingBarrierTree = true;
        try {
            while (true) {
                List<BarrierLevel> tree = initializeBarrierTree(
                        barrierId, ignoredOutcomes);
                List<BarrierCandidate> candidates = new ArrayList<>();
                for (BarrierLevel level : tree) {
                    if (level.barrier().status()
                            != CatchUpBarrier.Status.COMPLETE) {
                        candidates.addAll(candidates(level));
                    }
                }
                if (tree.stream().anyMatch(level -> level.barrier().status()
                        == CatchUpBarrier.Status.DEFERRED)) {
                    markTreeCatchingUp(tree);
                    metrics.increment("temporal.catchUpDeferrals");
                    return false;
                }
                Optional<BarrierCandidate> next = candidates.stream()
                        .min(BarrierCandidate.ORDER);
                if (next.isEmpty()) {
                    completeBarrierTree(tree);
                    return true;
                }
                BarrierCandidate candidate = next.orElseThrow();
                if (candidate.revision() != null) {
                    applyChildRevision(
                            candidate.binding(),
                            candidate.revision(),
                            ignoredOutcomes);
                } else {
                    TimelineEntry entry = candidate.entry();
                    Set<DocumentId> targets = new LinkedHashSet<>();
                    List<BarrierCandidate> sameEntry = candidates.stream()
                            .filter(item -> item.entry() != null
                                    && item.entry().blueId().equals(
                                    entry.blueId()))
                            .toList();
                    sameEntry.forEach(item -> targets.add(
                            item.binding().childDocumentId()));
                    long processedTargets = processHistoricalTargets(
                            entry,
                            targets,
                            ignoredOutcomes,
                            barrier.cutoffExclusive());
                    metrics.add("catchUp.childEntriesProcessed",
                            processedTargets);
                    metrics.add("childHistoricalProcessCalls",
                            processedTargets);
                    for (BarrierCandidate item : sameEntry) {
                        CatchUpBarrier current = barriers.get(
                                item.barrierId());
                        recordBarrier(item.barrierId());
                        current.recordProgress(
                                item.binding().bindingId(),
                                entry.sourceOrderKey());
                    }
                    metrics.increment("temporal.historicalEntriesReplayed");
                }
            }
        } catch (RuntimeException failure) {
            recordSessionProgress(parent);
            if (barrier.status() == CatchUpBarrier.Status.BLOCKED) {
                parent.markBlocked();
            } else {
                parent.markCatchingUp();
            }
            metrics.increment("temporal.catchUpContinuations");
            throw failure;
        } finally {
            settlingBarrierTree = previousSettlement;
        }
    }

    private List<BarrierLevel> initializeBarrierTree(
            String rootBarrierId,
            List<InternalProcessOutcome> outcomes) {
        while (true) {
            List<BarrierLevel> tree = barrierTree(rootBarrierId);
            boolean advanced = false;
            for (BarrierLevel level : tree) {
                CatchUpBarrier barrier = level.barrier();
                if (barrier.status() == CatchUpBarrier.Status.COMPLETE) {
                    continue;
                }
                if (barrier.status() == CatchUpBarrier.Status.BLOCKED) {
                    throw new IllegalStateException(barrier.diagnostic());
                }
                if (barrier.status() == CatchUpBarrier.Status.DEFERRED) {
                    recordBarrier(barrier.barrierId());
                    barrier.reopen();
                }
                for (EmbeddingBinding binding : barrierBindings(barrier)) {
                    EmbeddedEpochCursor cursor = cursors.get(
                            binding.bindingId());
                    if (cursor != null && cursor.appliedChildEpoch() == -1L) {
                        applyChildRevision(
                                binding,
                                documents.require(binding.childDocumentId())
                                        .revision(0L),
                                outcomes);
                        advanced = true;
                    }
                }
            }
            for (BarrierLevel level : tree) {
                CatchUpBarrier barrier = level.barrier();
                if (barrier.status() == CatchUpBarrier.Status.COMPLETE) {
                    continue;
                }
                for (EmbeddingBinding binding : barrierBindings(barrier)) {
                    EmbeddedEpochCursor cursor = cursors.get(
                            binding.bindingId());
                    DocumentSession child = documents.require(
                            binding.childDocumentId());
                    if (cursor != null && cursor.appliedChildEpoch()
                            < child.epoch()) {
                        DocumentRevision revision = child.revision(
                                cursor.appliedChildEpoch() + 1L);
                        if (revision.causalEntryBlueId().filter(
                                barrier.attachmentEntryBlueId()::equals)
                                .isPresent()) {
                            applyChildRevision(binding, revision, outcomes);
                            advanced = true;
                        }
                    }
                }
            }
            if (!advanced) {
                return tree;
            }
        }
    }

    private void trace(
            DocumentSession session,
            DocumentRevision revision,
            EngineMetrics.MetricsSnapshot before,
            long started) {
        if (before == null) {
            return;
        }
        long totalNanos = System.nanoTime() - started;
        transitionObserver.accept(new TransitionTrace(
                session.documentId(), revision, before, metrics.snapshot(),
                totalNanos));
    }
    private List<BarrierCandidate> candidates(BarrierLevel level) {
        CatchUpBarrier barrier = level.barrier();
        List<BarrierCandidate> candidates = new ArrayList<>();
        for (EmbeddingBinding binding : barrierBindings(barrier)) {
            EmbeddedEpochCursor cursor = cursors.get(binding.bindingId());
            DocumentSession child = documents.require(
                    binding.childDocumentId());
            if (cursor.appliedChildEpoch() < child.epoch()) {
                DocumentRevision revision = child.revision(
                        cursor.appliedChildEpoch() + 1L);
                ExternalOrderKey order = revision.sourceOrderKey()
                        .orElse(binding.attachmentOrder());
                if (revision.kind() == DocumentRevision.Kind.INITIALIZATION
                        || order.compareTo(barrier.cutoffExclusive()) < 0) {
                    candidates.add(BarrierCandidate.revision(
                            barrier.barrierId(), level.depth(), binding,
                            revision, order));
                    continue;
                }
            }
            nextHistoricalCandidate(
                    barrier, level.depth(), binding).ifPresent(
                    candidates::add);
        }
        return candidates;
    }

    private Optional<BarrierCandidate> nextHistoricalCandidate(
            CatchUpBarrier barrier,
            int depth,
            EmbeddingBinding binding) {
        ActivationMode mode = binding.activationMode();
        if (mode == ActivationMode.ATTACH_CURRENT_STATE) {
            return Optional.empty();
        }
        ExternalOrderKey after = barrier.progress(binding.bindingId());
        HistoricalStep step = journal.nextHistoricalStep(
                after,
                barrier.cutoffExclusive(),
                binding.attachmentEntryBlueId(),
                candidate -> routes.routesTo(
                        binding.childDocumentId(), candidate),
                routes.generation(),
                graph.generation(),
                () -> sourceSurfaceIdentity(binding));
        if (step instanceof HistoricalStep.EligibleEntry eligible) {
            return Optional.of(BarrierCandidate.history(
                    barrier.barrierId(), depth, binding, eligible.entry()));
        }
        if (step instanceof HistoricalStep.Complete complete) {
            recordBarrier(barrier.barrierId());
            barrier.recordCompletenessEvidence(
                    binding.bindingId(), complete.evidence());
            return Optional.empty();
        }
        if (step instanceof HistoricalStep.CompleteEmpty empty) {
            recordBarrier(barrier.barrierId());
            barrier.recordCompletenessEvidence(
                    binding.bindingId(), empty.evidence());
            return Optional.empty();
        }
        if (step instanceof HistoricalStep.Unavailable unavailable) {
            recordBarrier(barrier.barrierId());
            barrier.defer(unavailable.diagnostic());
            return Optional.empty();
        }
        HistoricalStep.InvalidEvidence invalid =
                (HistoricalStep.InvalidEvidence) step;
        recordBarrier(barrier.barrierId());
        barrier.block(invalid.diagnostic());
        throw new IllegalStateException(
                "Historical source evidence is invalid: "
                        + invalid.diagnostic());
    }

    private List<BarrierLevel> barrierTree(String rootBarrierId) {
        List<BarrierLevel> result = new ArrayList<>();
        collectBarrierTree(
                rootBarrierId,
                0,
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                result);
        return List.copyOf(result);
    }

    private void collectBarrierTree(
            String barrierId,
            int depth,
            Set<String> visited,
            Set<String> active,
            List<BarrierLevel> result) {
        if (visited.contains(barrierId)) {
            return;
        }
        if (!active.add(barrierId)) {
            throw new IllegalStateException(
                    "Nested catch-up barrier cycle " + barrierId);
        }
        CatchUpBarrier barrier = barriers.get(barrierId);
        if (barrier == null) {
            throw new IllegalStateException(
                    "Missing nested catch-up barrier " + barrierId);
        }
        barrier.nestedBarrierIds().stream()
                .sorted(EmbeddingBinding.TEXT_ORDER).forEach(nested ->
                collectBarrierTree(
                        nested, depth + 1, visited, active, result));
        active.remove(barrierId);
        visited.add(barrierId);
        result.add(new BarrierLevel(barrier, depth));
    }

    private void markTreeCatchingUp(List<BarrierLevel> tree) {
        for (BarrierLevel level : tree) {
            if (level.barrier().status() == CatchUpBarrier.Status.COMPLETE) {
                continue;
            }
            DocumentSession session = documents.require(
                    level.barrier().parentDocumentId());
            recordSessionProgress(session);
            session.markCatchingUp();
        }
    }

    private void completeBarrierTree(List<BarrierLevel> tree) {
        for (BarrierLevel level : tree) {
            CatchUpBarrier barrier = level.barrier();
            if (barrier.status() == CatchUpBarrier.Status.COMPLETE) {
                continue;
            }
            verifyBarrierComplete(barrier);
            recordBarrier(barrier.barrierId());
            barrier.complete();
            recordOpenBarrier(barrier.parentDocumentId());
            openBarrierByParent.remove(barrier.parentDocumentId());
            DocumentSession parent = documents.require(
                    barrier.parentDocumentId());
            recordSessionProgress(parent);
            if (pendingTopLevelAdmissions.containsKey(
                    parent.documentId())) {
                parent.markCatchingUp();
            } else {
                publishReadyIfSynchronized(parent, barrier.cutoffExclusive());
            }
            metrics.increment("temporal.catchUpBarriersCompleted");
        }
    }

    private void verifyBarrierComplete(CatchUpBarrier barrier) {
        for (String nested : barrier.nestedBarrierIds()) {
            CatchUpBarrier prerequisite = barriers.get(nested);
            if (prerequisite == null
                    || prerequisite.status() != CatchUpBarrier.Status.COMPLETE) {
                throw new IllegalStateException(
                        "Nested barrier remains incomplete: " + nested);
            }
        }
        for (EmbeddingBinding binding : barrierBindings(barrier)) {
            EmbeddedEpochCursor cursor = cursors.get(binding.bindingId());
            if (cursor == null) {
                throw new IllegalStateException(
                        "Missing cursor for " + binding.bindingId());
            }
            DocumentSession child = documents.require(
                    binding.childDocumentId());
            for (DocumentRevision revision : child.revisionsAfter(
                    cursor.appliedChildEpoch())) {
                if (revision.sourceOrderKey().map(order ->
                        order.compareTo(barrier.cutoffExclusive()) < 0)
                        .orElse(revision.kind()
                                == DocumentRevision.Kind.INITIALIZATION)) {
                    throw new IllegalStateException(
                            "Parent cursor is behind eligible child epoch "
                                    + revision.epoch());
                }
            }
            if (binding.activationMode()
                    != ActivationMode.ATTACH_CURRENT_STATE) {
                CompletenessEvidence evidence =
                        barrier.completenessEvidence(binding.bindingId());
                if (evidence == null || !evidence.isCurrentFor(
                        journal.revision(),
                        routes.generation(),
                        graph.generation(),
                        barrier.cutoffExclusive(),
                        sourceSurfaceIdentity(binding))) {
                    throw new IllegalStateException(
                            "Historical completeness evidence is stale for "
                                    + binding.bindingId());
                }
            }
            String actualChild = documents.require(
                    barrier.parentDocumentId())
                    .currentRevision().after().canonicalBlueIdAt(
                            binding.absolutePath());
            String expectedChild = child.revision(
                    cursor.appliedChildEpoch()).after().blueId();
            if (!expectedChild.equals(actualChild)) {
                throw new IllegalStateException(
                        "Parent child state is behind its persisted cursor at "
                                + binding.absolutePath());
            }
        }
    }

    String sourceSurfaceIdentity(EmbeddingBinding binding) {
        DocumentSession child = documents.require(
                binding.childDocumentId());
        return SourceSurfaceIdentity.calculate(
                binding,
                child.activeSubscriptions(),
                child.layout().routingSurface());
    }

    private boolean resumeTopLevelAdmissions() {
        boolean complete = true;
        for (Map.Entry<DocumentId, PendingTopLevelAdmission> item
                : new ArrayList<>(pendingTopLevelAdmissions.entrySet())) {
            if (openBarrierByParent.containsKey(item.getKey())) {
                complete = false;
                continue;
            }
            DocumentSession session = documents.require(item.getKey());
            if (session.status() == SessionStatus.BLOCKED) {
                throw new IllegalStateException(
                        "Top-level historical admission is blocked for "
                                + session.documentId());
            }
            session.markCatchingUp();
            PendingTopLevelAdmission admission = item.getValue();
            try {
                advanceTopLevelHistory(session, admission);
            } catch (DeferredWorkException deferred) {
                complete = false;
                continue;
            }
            pendingTopLevelAdmissions.remove(item.getKey());
            markTopLevelSubtreeReady(
                    session.documentId(), admission.cutoffInclusive());
        }
        return complete;
    }

    private void advanceTopLevelHistory(
            DocumentSession session,
            PendingTopLevelAdmission initialAdmission) {
        PendingTopLevelAdmission admission = Objects.requireNonNull(
                initialAdmission, "initialAdmission");
        ExternalOrderKey cursor = admission.afterExclusive();
        ExternalOrderKey cutoffExclusive = exclusiveAfter(
                admission.cutoffInclusive());
        metrics.increment("temporal.topLevelHistoricalWindowsOpened");
        while (true) {
            ProcessEmbeddedGraphSnapshot entryGraph = graph;
            Set<DocumentId> admittedSubtree = subtree(
                    entryGraph, session.documentId());
            HistoricalStep step = journal.nextHistoricalStep(
                    cursor,
                    cutoffExclusive,
                    "<top-level-admission>",
                    entry -> routes.route(entry).stream()
                            .anyMatch(admittedSubtree::contains),
                    routes.generation(),
                    entryGraph.generation(),
                    () -> "top-level|" + session.documentId() + "|"
                            + session.currentRevision().after().blueId());
            if (step instanceof HistoricalStep.Complete
                    || step instanceof HistoricalStep.CompleteEmpty) {
                return;
            }
            if (step instanceof HistoricalStep.Unavailable) {
                metrics.increment("temporal.topLevelHistoryDeferred");
                throw DeferredWorkException.INSTANCE;
            }
            if (step instanceof HistoricalStep.InvalidEvidence invalid) {
                session.markBlocked();
                throw new IllegalStateException(
                        "Historical source evidence is invalid: "
                                + invalid.diagnostic());
            }
            TimelineEntry entry = ((HistoricalStep.EligibleEntry) step)
                    .entry();
            LinkedHashSet<DocumentId> directTargets = new LinkedHashSet<>();
            routes.route(entry).stream()
                    .filter(admittedSubtree::contains)
                    .forEach(directTargets::add);
            if (directTargets.isEmpty()) {
                throw new IllegalStateException(
                        "Historical route changed while freezing entry "
                                + entry.blueId());
            }
            processEntryFrame(
                    new EntryFrame(entry, entryGraph, directTargets),
                    admittedSubtree);
            cursor = entry.sourceOrderKey();
            admission = admission.advanceTo(cursor);
            pendingTopLevelAdmissions.put(
                    session.documentId(), admission);
            metrics.increment("temporal.historicalEntriesReplayed");
        }
    }

    private void markTopLevelSubtreeReady(
            DocumentId rootId,
            ExternalOrderKey cutoffInclusive) {
        List<DocumentId> subtree = new ArrayList<>(subtree(graph, rootId));
        subtree.remove(rootId);
        subtree.stream().sorted(EmbeddingBinding.DOCUMENT_ORDER)
                .forEach(documentId ->
                publishReadyIfSynchronized(
                        documents.require(documentId), cutoffInclusive));
        publishReadyIfSynchronized(
                documents.require(rootId), cutoffInclusive);
    }

    private static Set<DocumentId> subtree(
            ProcessEmbeddedGraphSnapshot snapshot,
            DocumentId root) {
        LinkedHashSet<DocumentId> result = new LinkedHashSet<>();
        collectSubtree(snapshot, root, result);
        return Collections.unmodifiableSet(result);
    }

    private static void collectSubtree(
            ProcessEmbeddedGraphSnapshot snapshot,
            DocumentId documentId,
            Set<DocumentId> result) {
        if (!result.add(documentId)) {
            return;
        }
        for (EmbeddingBinding binding : snapshot.children(documentId)) {
            collectSubtree(snapshot, binding.childDocumentId(), result);
        }
    }

    private static ExternalOrderKey exclusiveAfter(
            ExternalOrderKey inclusive) {
        List<Object> components = new ArrayList<>(inclusive.components());
        components.add("");
        return ExternalOrderKey.of(components);
    }

    private List<EmbeddingBinding> barrierBindings(CatchUpBarrier barrier) {
        return barrier.bindingIds().stream()
                .map(graph::binding)
                .sorted(EmbeddingBinding.WITHIN_PARENT_ORDER)
                .toList();
    }

    private void collectTimelineIds(
            DocumentId documentId,
            Set<String> result,
            Set<DocumentId> visited) {
        if (!visited.add(documentId)) {
            return;
        }
        DocumentSession session = documents.require(documentId);
        result.addAll(session.layout().routingSurface().externalTimelineIds());
        for (EmbeddingBinding child : graph.children(documentId)) {
            collectTimelineIds(child.childDocumentId(), result, visited);
        }
    }

    private void validateRecoveredState() {
        if (committedTransitionSequence < 0L) {
            throw new IllegalStateException(
                    "Committed transition sequence is negative");
        }
        for (EmbeddingBinding binding : graph.bindings()) {
            DocumentSession parent = documents.require(
                    binding.parentDocumentId());
            DocumentSession child = documents.require(
                    binding.childDocumentId());
            EmbeddedEpochCursor cursor = cursors.get(binding.bindingId());
            if (cursor == null
                    || !cursor.bindingId().equals(binding.bindingId())
                    || cursor.appliedChildEpoch() < -1L
                    || cursor.appliedChildEpoch() > child.epoch()) {
                throw new IllegalStateException(
                        "Invalid recovered cursor for "
                                + binding.bindingId());
            }
            boolean exactOccurrence = parent.layout().directOccurrences()
                    .stream().anyMatch(occurrence ->
                            occurrence.scopePath().equals(
                                    binding.absolutePath())
                                    && occurrence.childDocumentId().equals(
                                    binding.childDocumentId()));
            if (!exactOccurrence) {
                throw new IllegalStateException(
                        "Recovered graph is not represented by parent state at "
                                + binding.absolutePath());
            }
        }
        openBarrierByParent.forEach((parent, barrierId) -> {
            CatchUpBarrier barrier = barriers.get(barrierId);
            if (barrier == null
                    || !barrier.parentDocumentId().equals(parent)
                    || barrier.status() == CatchUpBarrier.Status.COMPLETE) {
                throw new IllegalStateException(
                        "Invalid recovered open barrier " + barrierId);
            }
        });
        barriers.values().forEach(barrier ->
                barrier.nestedBarrierIds().forEach(nested -> {
                    if (!barriers.containsKey(nested)) {
                        throw new IllegalStateException(
                                "Missing recovered nested barrier " + nested);
                    }
                }));
        openFrames.forEach((entryBlueId, frame) -> {
            TimelineEntry canonical = journal.requireCanonical(frame.entry());
            if (!entryBlueId.equals(canonical.blueId())
                    || processedThrough != null
                    && canonical.sourceOrderKey().compareTo(
                            processedThrough) <= 0) {
                throw new IllegalStateException(
                        "Invalid recovered entry frame " + entryBlueId);
            }
        });
        pendingTopLevelAdmissions.forEach((documentId, admission) -> {
            DocumentSession session = documents.find(documentId).orElseThrow(
                    () -> new IllegalStateException(
                            "Pending admission has no document " + documentId));
            if (session.status() == SessionStatus.PENDING_INITIALIZATION
                    || session.status() == SessionStatus.TERMINATED) {
                throw new IllegalStateException(
                        "Pending admission has invalid status "
                                + session.status() + " for " + documentId);
            }
            if (admission.afterExclusive() != null
                    && admission.afterExclusive().compareTo(
                    admission.cutoffInclusive()) > 0) {
                throw new IllegalStateException(
                        "Pending admission cursor exceeds its cutoff for "
                                + documentId);
            }
            if (session.readyThrough().compareTo(
                    admission.cutoffInclusive()) > 0) {
                throw new IllegalStateException(
                        "Pending admission document is ahead of its cutoff for "
                                + documentId);
            }
        });
        for (DocumentSession session : documents.sessions()) {
            reconcileRecoveredReadiness(session);
        }
    }

    private void reconcileRecoveredReadiness(DocumentSession session) {
        if (session.status() != SessionStatus.READY) {
            return;
        }
        String failure = applicationReadinessFailure(session);
        if (failure == null) {
            return;
        }
        session.markCatchingUp();
        metrics.increment("temporal.recoveredReadinessRepairs");
    }

    private static DocumentRevision.CatchUpCause catchUpCause(
            EmbeddingBinding binding,
            long timestampMicros) {
        return new DocumentRevision.CatchUpCause(
                binding.parentDocumentId(),
                binding.attachmentEntryBlueId(),
                binding.absolutePath(),
                Math.max(1L, timestampMicros));
    }

    static String bindingId(
            DocumentId parentDocumentId,
            String absolutePath,
            long activationGeneration) {
        String parent = Objects.requireNonNull(
                parentDocumentId, "parentDocumentId").value();
        String path = Objects.requireNonNull(absolutePath, "absolutePath");
        if (path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "absolutePath must be a non-blank JSON Pointer");
        }
        if (activationGeneration < 1L) {
            throw new IllegalArgumentException(
                    "activationGeneration must be positive");
        }
        String generation = Long.toString(activationGeneration);
        return "binding:v1:p" + parent.length() + ":" + parent
                + "o" + path.length() + ":" + path
                + "g" + generation.length() + ":" + generation;
    }

    static String embeddedTransitionReceiptId(
            String bindingId,
            long childEpoch) {
        String binding = Objects.requireNonNull(bindingId, "bindingId");
        if (binding.isBlank()) {
            throw new IllegalArgumentException("bindingId must not be blank");
        }
        if (childEpoch < 0L) {
            throw new IllegalArgumentException(
                    "childEpoch must be non-negative");
        }
        return "embedded|" + binding + "|" + childEpoch;
    }

    private static Map<String, EmbeddedOccurrence> byPath(
            List<EmbeddedOccurrence> occurrences) {
        Map<String, EmbeddedOccurrence> result = new LinkedHashMap<>();
        for (EmbeddedOccurrence occurrence : occurrences) {
            EmbeddedOccurrence duplicate = result.putIfAbsent(
                    occurrence.scopePath(), occurrence);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate Process Embedded occurrence "
                                + occurrence.scopePath());
            }
        }
        return result;
    }

    private static DocumentDispatchOutcome publicOutcome(
            InternalProcessOutcome outcome) {
        return new DocumentDispatchOutcome(
                outcome.session().documentId(),
                outcome.revision(),
                outcome.totalNanos());
    }

    private record EntryFrame(
            TimelineEntry entry,
            ProcessEmbeddedGraphSnapshot graph,
            Set<DocumentId> directTargets) {
        private EntryFrame {
            entry = Objects.requireNonNull(entry, "entry");
            graph = Objects.requireNonNull(graph, "graph");
            directTargets = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(
                            directTargets, "directTargets")));
        }
    }

    private record OccurrenceKey(DocumentId parent, String path) {
        private OccurrenceKey {
            parent = Objects.requireNonNull(parent, "parent");
            path = Objects.requireNonNull(path, "path");
        }
    }

    private record ProposedBinding(
            EmbeddingBinding binding,
            EmbeddedOccurrence occurrence,
            ActivationMode mode,
            OccurrenceKey occurrenceKey) {
    }

    private record AdmittedChild(
            ProposedBinding proposed,
            DocumentSession child,
            boolean created) {
        private AdmittedChild {
            proposed = Objects.requireNonNull(proposed, "proposed");
            child = Objects.requireNonNull(child, "child");
        }
    }

    private record EmbeddedAdmissionEvidence(
            DocumentId childDocumentId,
            String admittedStateBlueId,
            Long admittedEpoch,
            ActivationMode mode,
            ExternalOrderKey verifiedCompleteThrough,
            String completenessProofIdentity,
            String expectedAttachmentEntryBlueId) {
        private EmbeddedAdmissionEvidence {
            childDocumentId = Objects.requireNonNull(
                    childDocumentId, "childDocumentId");
            mode = Objects.requireNonNull(mode, "mode");
            completenessProofIdentity = requireText(
                    completenessProofIdentity, "completenessProofIdentity");
        }

        private void requireMatch(
                EmbeddedOccurrence occurrence,
                TransitionCause cause) {
            if (!childDocumentId.equals(occurrence.childDocumentId())
                    || admittedStateBlueId != null
                    && !admittedStateBlueId.equals(
                    occurrence.suppliedState().blueId())
                    || expectedAttachmentEntryBlueId != null
                    && !expectedAttachmentEntryBlueId.equals(
                    cause.entryBlueId())) {
                throw new IllegalStateException(
                        "Attachment does not match exact admission evidence");
            }
        }

        private static EmbeddedAdmissionEvidence fullHistory(
                DocumentId documentId) {
            return new EmbeddedAdmissionEvidence(
                    documentId,
                    null,
                    null,
                    ActivationMode.IMPORT_FULL_HISTORY,
                    null,
                    "local-journal-full-history|" + documentId,
                    null);
        }
    }

    private record GraphDelta(
            ProcessEmbeddedGraphSnapshot proposed,
            List<EmbeddingBinding> removed,
            List<ProposedBinding> additions,
            List<OccurrenceKey> admissionsToConsume) {
        private boolean requiresSynchronization() {
            return !additions.isEmpty();
        }
    }

    record TransitionTrace(
            DocumentId documentId,
            DocumentRevision revision,
            EngineMetrics.MetricsSnapshot before,
            EngineMetrics.MetricsSnapshot after,
            long totalNanos) { }

    private record BarrierLevel(CatchUpBarrier barrier, int depth) {
        private BarrierLevel {
            barrier = Objects.requireNonNull(barrier, "barrier");
            if (depth < 0) {
                throw new IllegalArgumentException(
                        "barrier depth must be non-negative");
            }
        }
    }

    private record Prior<T>(boolean present, T value) {
    }

    private static final class DeferredWorkException
            extends RuntimeException {
        private static final DeferredWorkException INSTANCE =
                new DeferredWorkException();

        private DeferredWorkException() {
            super(null, null, false, false);
        }
    }

    private static final class RejectableExternalEntryException
            extends RuntimeException {
        private final String entryBlueId;
        private final InvalidAdmissionEvidenceException failure;

        private RejectableExternalEntryException(
                String entryBlueId,
                InvalidAdmissionEvidenceException failure) {
            super(null, failure, false, false);
            this.entryBlueId = requireText(entryBlueId, "entryBlueId");
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        private static RejectableExternalEntryException forEntry(
                TimelineEntry entry,
                InvalidAdmissionEvidenceException failure) {
            return new RejectableExternalEntryException(
                    Objects.requireNonNull(entry, "entry").blueId(), failure);
        }

        private String entryBlueId() {
            return entryBlueId;
        }

        private InvalidAdmissionEvidenceException failure() {
            return failure;
        }
    }

    private static final class DrainPausedException extends RuntimeException {
        private static final DrainPausedException INSTANCE =
                new DrainPausedException();
        private DrainPausedException() {
            super(null, null, false, false);
        }
    }

    private record SessionProgress(
            SessionStatus status,
            ExternalOrderKey readyThrough,
            long readyEpoch,
            long graphPublishedEpoch) {
        private SessionProgress {
            status = Objects.requireNonNull(status, "status");
            readyThrough = Objects.requireNonNull(
                    readyThrough, "readyThrough");
            if (readyEpoch < 0L || graphPublishedEpoch < -1L) {
                throw new IllegalArgumentException(
                        "Invalid session publication evidence");
            }
        }
    }

    private record PendingTopLevelAdmission(
            ExternalOrderKey afterExclusive,
            ExternalOrderKey cutoffInclusive) {
        private PendingTopLevelAdmission {
            cutoffInclusive = Objects.requireNonNull(
                    cutoffInclusive, "cutoffInclusive");
            if (afterExclusive != null
                    && afterExclusive.compareTo(cutoffInclusive) > 0) {
                throw new IllegalArgumentException(
                        "admission cursor cannot exceed its cutoff");
            }
        }

        private PendingTopLevelAdmission advanceTo(
                ExternalOrderKey nextAfterExclusive) {
            ExternalOrderKey next = Objects.requireNonNull(
                    nextAfterExclusive, "nextAfterExclusive");
            if (afterExclusive != null
                    && next.compareTo(afterExclusive) <= 0) {
                throw new IllegalStateException(
                        "top-level admission cursor must increase");
            }
            return new PendingTopLevelAdmission(next, cutoffInclusive);
        }
    }

    private static final class RecoveryState {
        private final Map<String, EmbeddedEpochCursor> cursors =
                new LinkedHashMap<>();
        private final Map<DocumentId, EmbeddedAdmissionEvidence>
                embeddedAdmissions = new LinkedHashMap<>();
        private final Map<String, CatchUpBarrier> barriers =
                new LinkedHashMap<>();
        private final Map<DocumentId, String> openBarrierByParent =
                new LinkedHashMap<>();
        private final Map<OccurrenceKey, Long> activationGenerations =
                new LinkedHashMap<>();
        private final Map<OccurrenceKey, EmbeddedAdmissionEvidence>
                exactEmbeddedAdmissions = new LinkedHashMap<>();
        private final Map<String, EntryFrame> openFrames =
                new LinkedHashMap<>();
        private final Set<String> rejectedExternalEntryIds =
                new LinkedHashSet<>();
        private final Map<DocumentId, PendingTopLevelAdmission>
                pendingTopLevelAdmissions = new LinkedHashMap<>();
        private final Map<DocumentId, String> embeddedInputHeadByParent =
                new LinkedHashMap<>();
        private final Map<String, EmbeddedEpochInput> committedEmbeddedInputs =
                new LinkedHashMap<>();
        private ProcessEmbeddedGraphSnapshot graph =
                ProcessEmbeddedGraphSnapshot.empty();
        private ExternalOrderKey processedThrough;
        private long committedTransitionSequence;
    }

    private static final class PublicationCheckpoint {
        private final long committedTransitionSequence;
        private final ProcessEmbeddedGraphSnapshot graphBefore;
        private final WholeObjectStore.Mark objectMark;
        private final Map<String, Prior<EmbeddedEpochCursor>> cursorsBefore =
                new LinkedHashMap<>();
        private final Map<OccurrenceKey, Prior<Long>>
                activationGenerationsBefore = new LinkedHashMap<>();
        private final Map<OccurrenceKey, EmbeddedAdmissionEvidence>
                consumedAdmissions = new LinkedHashMap<>();
        private final Map<String, Prior<CatchUpBarrier>> barriersBefore =
                new LinkedHashMap<>();
        private final Map<DocumentId, Prior<String>> openBarriersBefore =
                new LinkedHashMap<>();
        private final Map<DocumentId, Prior<String>> inputHeadsBefore =
                new LinkedHashMap<>();
        private final Map<DocumentId, SessionProgress>
                sessionProgressBefore = new LinkedHashMap<>();
        private final Set<DocumentId> newChildIds = new LinkedHashSet<>();

        private PublicationCheckpoint(
                long committedTransitionSequence,
                ProcessEmbeddedGraphSnapshot graphBefore,
                WholeObjectStore.Mark objectMark) {
            this.committedTransitionSequence = committedTransitionSequence;
            this.graphBefore = Objects.requireNonNull(
                    graphBefore, "graphBefore");
            this.objectMark = Objects.requireNonNull(
                    objectMark, "objectMark");
        }

        private long committedTransitionSequence() {
            return committedTransitionSequence;
        }

        private ProcessEmbeddedGraphSnapshot graphBefore() {
            return graphBefore;
        }

        private WholeObjectStore.Mark objectMark() {
            return objectMark;
        }

        private Map<String, Prior<EmbeddedEpochCursor>> cursorsBefore() {
            return cursorsBefore;
        }

        private Map<OccurrenceKey, Prior<Long>>
                activationGenerationsBefore() {
            return activationGenerationsBefore;
        }

        private Map<OccurrenceKey, EmbeddedAdmissionEvidence>
                consumedAdmissions() {
            return consumedAdmissions;
        }

        private Map<String, Prior<CatchUpBarrier>> barriersBefore() {
            return barriersBefore;
        }

        private Map<DocumentId, Prior<String>> openBarriersBefore() {
            return openBarriersBefore;
        }

        private Map<DocumentId, Prior<String>> inputHeadsBefore() {
            return inputHeadsBefore;
        }

        private Map<DocumentId, SessionProgress> sessionProgressBefore() {
            return sessionProgressBefore;
        }

        private Set<DocumentId> newChildIds() {
            return newChildIds;
        }

        private void recordCursor(
                String bindingId,
                Map<String, EmbeddedEpochCursor> current) {
            recordPrior(cursorsBefore, bindingId, current);
        }

        private void recordActivationGeneration(
                OccurrenceKey occurrence,
                Map<OccurrenceKey, Long> current) {
            recordPrior(
                    activationGenerationsBefore, occurrence, current);
        }

        private void recordConsumedAdmission(
                OccurrenceKey occurrence,
                EmbeddedAdmissionEvidence plan) {
            consumedAdmissions.putIfAbsent(occurrence, plan);
        }

        private void recordBarrier(
                String barrierId,
                Map<String, CatchUpBarrier> current) {
            if (barriersBefore.containsKey(barrierId)) {
                return;
            }
            CatchUpBarrier barrier = current.get(barrierId);
            barriersBefore.put(
                    barrierId,
                    new Prior<>(barrier != null,
                            barrier == null ? null : barrier.copy()));
        }

        private void recordOpenBarrier(
                DocumentId parent,
                Map<DocumentId, String> current) {
            recordPrior(openBarriersBefore, parent, current);
        }

        private void recordInputHead(
                DocumentId parent,
                Map<DocumentId, String> current) {
            recordPrior(inputHeadsBefore, parent, current);
        }

        private void recordSessionProgress(DocumentSession session) {
            sessionProgressBefore.putIfAbsent(
                    session.documentId(),
                    new SessionProgress(
                            session.status(),
                            session.readyThrough(),
                            session.readyEpoch(),
                            session.graphPublishedEpoch()));
        }

        private void recordNewChild(DocumentId childId) {
            newChildIds.add(Objects.requireNonNull(childId, "childId"));
        }

        private static <K, V> void recordPrior(
                Map<K, Prior<V>> recorded,
                K key,
                Map<K, V> current) {
            if (recorded.containsKey(key)) {
                return;
            }
            boolean present = current.containsKey(key);
            recorded.put(key, new Prior<>(
                    present, present ? current.get(key) : null));
        }
    }

    private record TransitionCause(
            String entryBlueId,
            ExternalOrderKey order,
            ExternalOrderKey cutoff,
            long timestampMicros) {
        private TransitionCause {
            entryBlueId = Objects.requireNonNull(
                    entryBlueId, "entryBlueId");
            order = Objects.requireNonNull(order, "order");
            cutoff = Objects.requireNonNull(cutoff, "cutoff");
            if (timestampMicros <= 0L) {
                throw new IllegalArgumentException(
                        "timestampMicros must be positive");
            }
        }

        static TransitionCause external(TimelineEntry entry) {
            return external(entry, entry.sourceOrderKey());
        }

        static TransitionCause external(
                TimelineEntry entry,
                ExternalOrderKey cutoff) {
            return new TransitionCause(
                    entry.blueId(),
                    entry.sourceOrderKey(),
                    Objects.requireNonNull(cutoff, "cutoff"),
                    entry.timestampMicros());
        }

        static TransitionCause embedded(EmbeddedEpochInput input) {
            return new TransitionCause(
                    input.originalEntryBlueId() == null
                            ? input.inputId()
                            : input.originalEntryBlueId(),
                    input.sourceOrder(),
                    input.sourceOrder(),
                    input.applicationTimestampMicros());
        }

        static TransitionCause admission(
                DocumentSession session,
                ExternalOrderKey cutoff) {
            return new TransitionCause(
                    session.revision(0L).causalEntryBlueId().orElseThrow(),
                    cutoff,
                    cutoff,
                    timestamp(cutoff));
        }

        static TransitionCause nestedAdmission(
                EmbeddingBinding binding,
                TransitionCause outer) {
            return new TransitionCause(
                    binding.attachmentEntryBlueId(),
                    binding.attachmentOrder(),
                    outer.cutoff(),
                    outer.timestampMicros());
        }

        private static long timestamp(ExternalOrderKey key) {
            if (!key.components().isEmpty()
                    && key.components().get(0) instanceof BigInteger integer) {
                try {
                    return Math.max(1L, integer.longValueExact());
                } catch (ArithmeticException ignored) {
                    return 1L;
                }
            }
            return 1L;
        }
    }

    record BarrierCandidate(
            String barrierId,
            int depth,
            EmbeddingBinding binding,
            DocumentRevision revision,
            TimelineEntry entry,
            ExternalOrderKey order) {
        static final Comparator<BarrierCandidate> ORDER = Comparator
                .comparing(BarrierCandidate::order)
                .thenComparing(Comparator.comparingInt(
                        BarrierCandidate::depth).reversed())
                .thenComparing(candidate ->
                        candidate.revision() == null ? 1 : 0)
                .thenComparing(c -> c.binding().parentDocumentId().value(),
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparing(c -> c.binding().absolutePath(),
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparing(c -> c.binding().childDocumentId().value(),
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparingLong(c -> c.binding().activationGeneration())
                .thenComparing(candidate -> candidate.revision() == null
                        ? candidate.entry().blueId() : "", EmbeddingBinding.TEXT_ORDER)
                .thenComparingLong(candidate -> candidate.revision() == null
                        ? 0L : candidate.revision().epoch());

        static BarrierCandidate revision(
                String barrierId,
                int depth,
                EmbeddingBinding binding,
                DocumentRevision revision,
                ExternalOrderKey order) {
            return new BarrierCandidate(
                    barrierId, depth, binding, revision, null, order);
        }

        static BarrierCandidate history(
                String barrierId,
                int depth,
                EmbeddingBinding binding,
                TimelineEntry entry) {
            return new BarrierCandidate(
                    barrierId,
                    depth,
                    binding,
                    null,
                    entry,
                    entry.sourceOrderKey());
        }
    }
}
