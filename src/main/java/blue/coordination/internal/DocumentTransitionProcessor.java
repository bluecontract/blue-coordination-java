package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import blue.coordination.api.ExactValue;

import blue.coordination.api.DocumentRevision;

import blue.coordination.api.DocumentId;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PlatformCommitCompanion;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** One frozen call and one staged semantic revision per selected Root. */
final class DocumentTransitionProcessor {
    private final BlueRuntime runtime;
    private final WholeObjectStore objects;
    private final EmbeddedOnlyLayoutBuilder layoutBuilder;
    private final EngineMetrics metrics;
    private final Consumer<DefaultCoordinationEngine.FailurePoint> failureInjector;

    public DocumentTransitionProcessor(
            BlueRuntime runtime,
            WholeObjectStore objects,
            EmbeddedOnlyLayoutBuilder layoutBuilder,
            EngineMetrics metrics,
            Consumer<DefaultCoordinationEngine.FailurePoint> failureInjector) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.layoutBuilder = Objects.requireNonNull(
                layoutBuilder, "layoutBuilder");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.failureInjector = Objects.requireNonNull(
                failureInjector, "failureInjector");
    }

    public DocumentSession admit(
            DocumentId documentId,
            String authoredYaml,
            ExternalOrderKey admissionFrontier) {
        Objects.requireNonNull(authoredYaml, "authoredYaml");
        Node source = metrics.timed(
                "documentStart.parseSource",
                () -> runtime.parseSourceYaml(authoredYaml));
        Node preprocessed = metrics.timed(
                "documentStart.preprocess",
                () -> runtime.preprocess(source));
        ResolvedSnapshot snapshot = metrics.timed(
                "documentStart.resolve",
                () -> runtime.cache(runtime.resolveToSnapshot(preprocessed)));
        return admitSnapshot(
                Objects.requireNonNull(documentId, "documentId"),
                snapshot,
                admissionFrontier,
                null);
    }

    /** Admits one already exact immutable child without YAML or clone churn. */
    public DocumentSession admitExact(
            DocumentId documentId,
            ExactValue authoredExact,
            ExternalOrderKey admissionFrontier,
            DocumentRevision.CatchUpCause cause) {
        Objects.requireNonNull(authoredExact, "authoredExact");
        ResolvedSnapshot snapshot = authoredExact.snapshot().orElseGet(() ->
                metrics.timed(
                        "documentStart.loadExactSnapshot",
                        () -> {
                            metrics.increment(
                                    "documentStart.referenceOnlySnapshotLoads");
                            return runtime.cache(runtime.loadExactSnapshot(
                                    authoredExact.blueId()));
                        }));
        return admitSnapshot(
                Objects.requireNonNull(documentId, "documentId"),
                snapshot,
                admissionFrontier,
                cause);
    }

    private DocumentSession admitSnapshot(
            DocumentId documentId,
            ResolvedSnapshot snapshot,
            ExternalOrderKey admissionFrontier,
            DocumentRevision.CatchUpCause cause) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(admissionFrontier, "admissionFrontier");
        ExactValue authoredExact = objects.put(
                runtime.cache(snapshot), "authored-document");
        DocumentIdentityReader.verifyOptionalDocumentId(
                authoredExact, documentId);

        // Freeze Process Embedded ownership before INITIALIZE. Otherwise the
        // frozen Contracts 1.0 initializer recursively initializes an inline
        // descendant as owned state, and Coordination cannot give that child
        // its own epoch-0 session without initializing it twice.
        EmbeddedOnlyLayout authoredLayout = layoutBuilder.build(authoredExact);
        ResolvedSnapshot initializationInput;
        if (authoredLayout.directOccurrences().isEmpty()) {
            initializationInput = snapshot;
        } else {
            ResolvedSnapshot ownership =
                    runtime.resolveToSnapshotPreservingPaths(
                            authoredLayout.processingFrozen().toNode(),
                            authoredLayout.directOccurrences().stream()
                                    .map(EmbeddedOccurrence::scopePath)
                                    .toList());
            initializationInput =
                    layoutBuilder.isolateManagedInitializationScopes(
                            ownership, authoredLayout);
        }
        // The frozen initialization marker references this exact input. Keep
        // the body available to later PROCESS validation and replay.
        objects.put(initializationInput, "initialization-input");
        DocumentProcessingResult initialized = metrics.timed(
                "documentStart.contractsInitialize",
                () -> runtime.initialize(initializationInput));
        requireSuccess("initialize " + documentId, initialized);
        ExactValue initializedExact = authoredLayout.directOccurrences()
                .isEmpty()
                ? objects.put(
                        initialized.document(), "initialized-document")
                : layoutBuilder.restoreAuthoredChildrenAfterInitialization(
                        initialized.document(), authoredLayout);
        EmbeddedOnlyLayout layout = layoutBuilder.rebuild(
                initializedExact, authoredLayout);

        metrics.increment("documentStart.initialSubscriptionProjections");
        List<SubscriptionDelta.Entry> ownedSubscriptions = metrics.timed(
                "documentStart.projectInitialOwnedSubscriptions",
                () -> runtime.projectInitialOwnedSubscriptions(
                        layout.processingFrozen(),
                        0L,
                        admissionFrontier));
        requireOwnedSubscriptionSurface(ownedSubscriptions, layout);
        CheckpointDomainEvidence.retainAll(ownedSubscriptions, objects);

        DocumentRevision initializationRevision = new DocumentRevision(
                documentId,
                0L,
                0L,
                DocumentRevision.Kind.INITIALIZATION,
                authoredExact,
                initializedExact,
                null,
                admissionFrontier,
                cause == null
                        ? "admission|" + documentId.value()
                        : cause.attachmentEntryBlueId(),
                cause,
                initialized.events(),
                initialized.totalGas());
        DocumentSession session = new DocumentSession(
                documentId,
                authoredExact,
                layout,
                ownedSubscriptions,
                admissionFrontier,
                initializationRevision);
        metrics.increment("documentStart.sessionsInitialized");
        metrics.increment("preparedRuntimeCompilations");
        return session;
    }

    /** Performs one pure frozen invocation without mutating the session. */
    public Prepared prepare(
            DocumentSession session,
            TimelineEntry entry) {
        long started = System.nanoTime();
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(entry, "entry");
        if (session.hasTerminalEntry(entry.blueId())) {
            metrics.increment("process.duplicateEntriesSkipped");
            throw new IllegalStateException(
                    "Entry already processed by " + session.documentId());
        }

        long expectedEpoch = session.epoch();
        EmbeddedOnlyLayout beforeLayout = session.layout();
        ExactValue before = session.currentRevision().after();
        failureInjector.accept(
                DefaultCoordinationEngine.FailurePoint.BEFORE_FROZEN_PROCESS);
        metrics.increment("process.concreteOwnershipRootInputs");
        metrics.increment("process.referenceOnlyEventInputs");
        long frozenStarted = System.nanoTime();
        metrics.addNanos("process.hostBeforeFrozen", frozenStarted - started);
        PlatformProcessingResult platform = runtime.process(
                beforeLayout.processingFrozen().toNode(),
                entry.blueId(),
                expectedEpoch,
                entry.journalOrderKey(),
                session.activeSubscriptions());
        long frozenNanos = System.nanoTime() - frozenStarted;
        metrics.addNanos("process.frozenContractsOnce", frozenNanos);
        metrics.addNanos("process.frozen", frozenNanos);
        metrics.increment("process.frozenContractsInvocations");
        metrics.increment("frozenProcessCalls");
        DocumentProcessingResult processed = platform.processResult();
        if (processed.diagnostic() != null
                && processed.diagnostic().message().contains(
                "enters embedded scope")) {
            metrics.increment(
                    "layout.externalManagedChildMutationsRejected");
            throw new IllegalStateException(
                    "External parent operation attempted to mutate managed "
                            + "child: "
                            + processed.diagnostic().message());
        }
        requireSuccess("process " + session.documentId(), processed);
        failureInjector.accept(DefaultCoordinationEngine.FailurePoint
                .AFTER_FROZEN_BEFORE_STAGE);

        long hostAfterFrozenStarted = System.nanoTime();
        ExactValue processedAfter = layoutBuilder.restoreManagedChildren(
                processed.document(), beforeLayout, false);
        EmbeddedOnlyLayout afterLayout = layoutBuilder.rebuild(
                processedAfter, beforeLayout);
        // Revision history and API reads always retain the fully materialized
        // semantic Root. The provider may independently expose an identity-
        // equivalent shell with managed children represented by references.
        ExactValue after = afterLayout.semanticRoot();

        PlatformCommitCompanion companion = platform.commitCompanion();
        verifyCommitCompanion(
                companion,
                beforeLayout.processingFrozen().blueId(),
                entry,
                expectedEpoch,
                processed);
        List<SubscriptionDelta.Entry> activeSubscriptionsAfter = metrics.timed(
                "process.applyCommitCompanionDelta",
                () -> applySubscriptionTransition(
                        session.activeSubscriptions(),
                        companion.subscriptionDelta(),
                        beforeLayout,
                        afterLayout,
                        Math.addExact(expectedEpoch, 1L),
                        entry.journalOrderKey(),
                        metrics));
        CheckpointDomainEvidence.retainAll(activeSubscriptionsAfter, objects);
        metrics.addNanos("process.hostAfterFrozen",
                System.nanoTime() - hostAfterFrozenStarted);
        return new Prepared(
                session,
                expectedEpoch,
                entry,
                before,
                after,
                beforeLayout,
                afterLayout,
                activeSubscriptionsAfter,
                processed.events(),
                processed.totalGas(),
                System.nanoTime() - started);
    }

    /** Commits a previously prepared transition at the exact expected epoch. */
    public InternalProcessOutcome commit(Prepared prepared) {
        Objects.requireNonNull(prepared, "prepared");
        DocumentSession session = prepared.session();
        if (session.epoch() != prepared.expectedEpoch()) {
            throw new IllegalStateException(
                    "Session changed after preparation: "
                            + session.documentId());
        }
        TimelineEntry entry = prepared.entry();
        DocumentRevision revision = new DocumentRevision(
                session.documentId(),
                Math.addExact(session.epoch(), 1L),
                session.nextApplicationOrder(),
                DocumentRevision.Kind.TIMELINE_ENTRY,
                prepared.before(),
                prepared.after(),
                entry,
                null,
                prepared.emittedEvents(),
                prepared.processingGas());
        session.commit(
                revision,
                prepared.afterLayout(),
                null,
                prepared.activeSubscriptionsAfter(),
                "external|" + entry.blueId());
        metrics.increment("process.routingSurfaceReused");
        metrics.increment("process.documentRevisionsCommitted");
        return new InternalProcessOutcome(
                session,
                revision,
                prepared.beforeLayout(),
                prepared.afterLayout(),
                prepared.preparationNanos());
    }
    private static void verifyCommitCompanion(
            PlatformCommitCompanion companion,
            String expectedProcessingRootBlueId,
            TimelineEntry entry,
            long expectedEpoch,
            DocumentProcessingResult processed) {
        verifyCommitCompanion(
                companion,
                expectedProcessingRootBlueId,
                entry.blueId(),
                entry.journalOrderKey(),
                expectedEpoch,
                processed);
    }

    private static void verifyCommitCompanion(
            PlatformCommitCompanion companion,
            String expectedProcessingRootBlueId,
            String expectedEventBlueId,
            ExternalOrderKey expectedEventOrder,
            long expectedEpoch,
            DocumentProcessingResult processed) {
        Objects.requireNonNull(companion, "companion");
        if (!expectedProcessingRootBlueId.equals(
                companion.expectedRootBlueId())) {
            throw new IllegalStateException(
                    "Frozen PROCESS companion is bound to "
                            + companion.expectedRootBlueId()
                            + " instead of PROCESS ownership Root "
                            + expectedProcessingRootBlueId);
        }
        if (!expectedEventBlueId.equals(companion.eventBlueId())
                || expectedEpoch != companion.expectedRootRevision()
                || !expectedEventOrder.equals(
                    companion.eventOrderKey())) {
            throw new IllegalStateException(
                    "Frozen PROCESS companion does not match the selected "
                            + "Root/event/revision tuple");
        }
        long expectedResultingRevision = processed.commits()
                ? Math.addExact(expectedEpoch, 1L)
                : expectedEpoch;
        if (companion.resultingRootRevision()
                != expectedResultingRevision
                || companion.commitsRootAndOutbox() != processed.commits()) {
            throw new IllegalStateException(
                    "Frozen PROCESS companion has invalid commit metadata");
        }
    }

    private static void requireOwnedSubscriptionSurface(
            List<SubscriptionDelta.Entry> entries,
            EmbeddedOnlyLayout layout) {
        List<String> boundaries = managedBoundaries(layout);
        for (SubscriptionDelta.Entry entry : entries) {
            if (!owned(entry.scopePath(), boundaries)) {
                throw new IllegalStateException(
                        "PROCESS ownership projection leaked managed scope "
                                + entry.scopePath() + "/" + entry.channelKey());
            }
        }
    }

    static List<SubscriptionDelta.Entry> applyCommitCompanionDelta(
            List<SubscriptionDelta.Entry> previous,
            SubscriptionDelta delta,
            EmbeddedOnlyLayout beforeLayout,
            EmbeddedOnlyLayout afterLayout,
            long resultingRootRevision,
            ExternalOrderKey transitionOrder,
            EngineMetrics metrics) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(beforeLayout, "beforeLayout");
        Objects.requireNonNull(afterLayout, "afterLayout");
        if (resultingRootRevision <= 0L) {
            throw new IllegalArgumentException(
                    "resultingRootRevision must be positive");
        }
        Objects.requireNonNull(transitionOrder, "transitionOrder");
        Objects.requireNonNull(metrics, "metrics");
        return applyActiveSubscriptionDelta(
                previous,
                delta,
                managedBoundaries(beforeLayout),
                managedBoundaries(afterLayout),
                resultingRootRevision,
                transitionOrder,
                metrics);
    }

    private List<SubscriptionDelta.Entry> applySubscriptionTransition(
            List<SubscriptionDelta.Entry> previous,
            SubscriptionDelta companionDelta,
            EmbeddedOnlyLayout beforeLayout,
            EmbeddedOnlyLayout afterLayout,
            long resultingRootRevision,
            ExternalOrderKey transitionOrder,
            EngineMetrics targetMetrics) {
        SubscriptionDelta effective = companionDelta;
        if (beforeLayout.plan() != afterLayout.plan()) {
            effective = runtime.projectSubscriptionUpdate(
                    afterLayout.processingFrozen(),
                    previous,
                    changedSurfacePointers(companionDelta),
                    resultingRootRevision,
                    transitionOrder);
            requireSameOwnedTransition(
                    companionDelta, effective, beforeLayout, afterLayout);
            targetMetrics.increment(
                    "process.incrementalSubscriptionReprojections");
            return applyActiveSubscriptionDelta(
                    previous,
                    effective,
                    managedBoundaries(beforeLayout),
                    managedBoundaries(afterLayout),
                    resultingRootRevision,
                    transitionOrder,
                    targetMetrics,
                    true);
        }
        return applyCommitCompanionDelta(
                previous, effective, beforeLayout, afterLayout,
                resultingRootRevision, transitionOrder, targetMetrics);
    }

    /** Narrow re-projection inputs derived from the frozen companion. */
    private static Set<String> changedSurfacePointers(
            SubscriptionDelta delta) {
        Set<String> result = new LinkedHashSet<>();
        result.add(JsonPointer.append(JsonPointer.ROOT, "type"));
        delta.added().forEach(entry -> result.add(contractPointer(entry)));
        delta.removed().forEach(entry -> result.add(contractPointer(entry)));
        if (result.size() == 1) {
            result.add(JsonPointer.append(JsonPointer.ROOT, "contracts"));
        }
        return result;
    }

    private static String contractPointer(SubscriptionDelta.Entry entry) {
        return JsonPointer.append(
                JsonPointer.append(entry.scopePath(), "contracts"),
                entry.channelKey());
    }

    private static void requireSameOwnedTransition(
            SubscriptionDelta companion,
            SubscriptionDelta projected,
            EmbeddedOnlyLayout before,
            EmbeddedOnlyLayout after) {
        if (!ownedKeys(companion.added(), managedBoundaries(after)).equals(
                ownedKeys(projected.added(), managedBoundaries(after)))
                || !ownedKeys(
                companion.removed(), managedBoundaries(before)).equals(
                ownedKeys(projected.removed(), managedBoundaries(before)))) {
            throw new InvalidExecutionEvidenceException(
                    "Incremental subscription projection disagrees with "
                            + "the frozen commit companion");
        }
    }

    private static Set<OccurrenceKey> ownedKeys(
            List<SubscriptionDelta.Entry> entries,
            List<String> boundaries) {
        Set<OccurrenceKey> result = new LinkedHashSet<>();
        entries.stream().filter(entry -> owned(entry.scopePath(), boundaries))
                .map(DocumentTransitionProcessor::occurrenceKey)
                .forEach(result::add);
        return result;
    }

    /**
     * Applies one frozen commit companion to the retained active interval set.
     * Boundary lists are supplied separately so the evidence algorithm remains
     * independently testable without constructing a complete document layout.
     */
    static List<SubscriptionDelta.Entry> applyActiveSubscriptionDelta(
            List<SubscriptionDelta.Entry> previous,
            SubscriptionDelta delta,
            List<String> beforeBoundaries,
            List<String> afterBoundaries,
            long resultingRootRevision,
            ExternalOrderKey transitionOrder,
            EngineMetrics metrics) {
        return applyActiveSubscriptionDelta(
                previous, delta, beforeBoundaries, afterBoundaries,
                resultingRootRevision, transitionOrder, metrics, false);
    }

    private static List<SubscriptionDelta.Entry> applyActiveSubscriptionDelta(
            List<SubscriptionDelta.Entry> previous,
            SubscriptionDelta delta,
            List<String> beforeBoundaries,
            List<String> afterBoundaries,
            long resultingRootRevision,
            ExternalOrderKey transitionOrder,
            EngineMetrics metrics,
            boolean refreshStableHeaders) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(beforeBoundaries, "beforeBoundaries");
        Objects.requireNonNull(afterBoundaries, "afterBoundaries");
        if (resultingRootRevision <= 0L) {
            throw new IllegalArgumentException(
                    "resultingRootRevision must be positive");
        }
        Objects.requireNonNull(transitionOrder, "transitionOrder");
        Objects.requireNonNull(metrics, "metrics");

        Map<OccurrenceKey, SubscriptionDelta.Entry> active = indexByOccurrence(
                previous, "active");
        for (SubscriptionDelta.Entry entry : active.values()) {
            if (!entry.isActiveInterval()) {
                throw new IllegalStateException(
                        "Retained subscription is not active at "
                                + describe(entry));
            }
            if (!owned(entry.scopePath(), beforeBoundaries)) {
                throw new IllegalStateException(
                        "Retained subscription escaped the prior PROCESS "
                                + "ownership surface at " + describe(entry));
            }
        }

        long ignoredEmbedded = 0L;
        long removed = 0L;
        Set<OccurrenceKey> validRemovals = new LinkedHashSet<>();
        Map<OccurrenceKey, SubscriptionDelta.Entry> removedEstablished =
                new LinkedHashMap<>();
        for (SubscriptionDelta.Entry entry : delta.removed()) {
            if (!owned(entry.scopePath(), beforeBoundaries)) {
                ignoredEmbedded++;
                continue;
            }
            OccurrenceKey key = occurrenceKey(entry);
            SubscriptionDelta.Entry established = active.get(key);
            if (established == null) {
                throw new InvalidExecutionEvidenceException(
                        "Commit companion retired an inactive subscription at "
                                + describe(entry));
            }
            requireRetirementEvidence(
                    established, entry, resultingRootRevision);
            active.remove(key);
            validRemovals.add(key);
            removedEstablished.put(key, established);
            removed++;
        }

        long added = 0L;
        long retainedReplacements = 0L;
        for (SubscriptionDelta.Entry entry : delta.added()) {
            if (!owned(entry.scopePath(), afterBoundaries)) {
                ignoredEmbedded++;
                continue;
            }
            requireActivationEvidence(
                    entry, resultingRootRevision, transitionOrder);
            OccurrenceKey key = occurrenceKey(entry);
            SubscriptionDelta.Entry established = removedEstablished.get(key);
            boolean stable = established != null
                    && sameStableSubscriptionIdentity(established, entry);
            SubscriptionDelta.Entry activated = entry;
            if (stable) {
                activated = refreshStableHeaders
                        ? refreshHeaderPreservingInterval(established, entry)
                        : established;
            }
            if (active.putIfAbsent(key, activated) != null) {
                throw new InvalidExecutionEvidenceException(
                        "Commit companion activated an already active "
                                + "subscription at " + describe(entry));
            }
            if (stable) {
                removed--;
                retainedReplacements++;
            } else {
                added++;
            }
        }

        for (Map.Entry<OccurrenceKey, SubscriptionDelta.Entry> retained
                : new ArrayList<>(active.entrySet())) {
            if (!owned(retained.getValue().scopePath(), afterBoundaries)) {
                if (!validRemovals.contains(retained.getKey())) {
                    throw new InvalidExecutionEvidenceException(
                            "Commit companion did not retire subscription that "
                                    + "left the PROCESS ownership surface at "
                                    + describe(retained.getValue()));
                }
                active.remove(retained.getKey());
            }
        }

        List<SubscriptionDelta.Entry> result = new SubscriptionDelta(
                new ArrayList<>(active.values()), List.of()).added();
        metrics.increment("process.commitCompanionDeltasApplied");
        metrics.add("process.dynamicSubscriptionsAdded", added);
        metrics.add("process.dynamicSubscriptionsRemoved", removed);
        metrics.add("process.dynamicSubscriptionsReplaced",
                dynamicReplacementCount(
                        delta.added(), validRemovals, retainedReplacements));
        metrics.add("process.companionReplacementsRetained",
                retainedReplacements);
        metrics.add("process.managedSubscriptionDeltaEntriesIgnored",
                ignoredEmbedded);
        metrics.add("process.subscriptionIntervalsReused",
                result.size() - added);
        return result;
    }

    private static void requireRetirementEvidence(
            SubscriptionDelta.Entry established,
            SubscriptionDelta.Entry retired,
            long resultingRootRevision) {
        if (!sameSubscriptionSnapshot(established, retired)
                || !Objects.equals(
                established.activationRootRevision(),
                retired.activationRootRevision())
                || !Objects.equals(
                established.startAfterExternalOrderKey(),
                retired.startAfterExternalOrderKey())
                || !Objects.equals(
                retired.endAtRootRevision(), resultingRootRevision)) {
            throw new InvalidExecutionEvidenceException(
                    "Commit companion supplied invalid retirement evidence at "
                            + describe(retired));
        }
    }

    private static void requireActivationEvidence(
            SubscriptionDelta.Entry activated,
            long resultingRootRevision,
            ExternalOrderKey transitionOrder) {
        if (!activated.isActiveInterval()
                || !Objects.equals(
                activated.activationRootRevision(), resultingRootRevision)
                || !Objects.equals(
                activated.startAfterExternalOrderKey(), transitionOrder)) {
            throw new InvalidExecutionEvidenceException(
                    "Commit companion supplied invalid activation evidence at "
                            + describe(activated));
        }
    }

    private static long dynamicReplacementCount(
            List<SubscriptionDelta.Entry> additions,
            Set<OccurrenceKey> removals,
            long retainedReplacements) {
        long paired = additions.stream()
                .map(DocumentTransitionProcessor::occurrenceKey)
                .filter(removals::contains)
                .count();
        return Math.subtractExact(paired, retainedReplacements);
    }

    private static String describe(SubscriptionDelta.Entry entry) {
        return entry.scopePath() + "/" + entry.channelKey();
    }

    private static boolean owned(
            String scopePath,
            List<String> managedBoundaries) {
        for (String boundary : managedBoundaries) {
            if (scopePath.equals(boundary)
                    || scopePath.startsWith(boundary + "/")) {
                return false;
            }
        }
        return true;
    }

    private static Map<OccurrenceKey, SubscriptionDelta.Entry> indexByOccurrence(
            List<SubscriptionDelta.Entry> entries,
            String label) {
        Map<OccurrenceKey, SubscriptionDelta.Entry> result = new LinkedHashMap<>();
        for (SubscriptionDelta.Entry entry : entries) {
            if (result.put(occurrenceKey(entry), entry) != null) {
                throw new IllegalStateException(
                        "Duplicate " + label + " subscription occurrence at "
                                + entry.scopePath() + "/" + entry.channelKey());
            }
        }
        return result;
    }

    private static boolean sameSubscriptionSnapshot(
            SubscriptionDelta.Entry left,
            SubscriptionDelta.Entry right) {
        return left.scopePath().equals(right.scopePath())
                && left.channelKey().equals(right.channelKey())
                && left.effectiveTypeBlueId().equals(
                right.effectiveTypeBlueId())
                && left.sourceContributionNodeBlueIds().equals(
                right.sourceContributionNodeBlueIds())
                && left.order() == right.order()
                && left.subscriptionKeys().equals(right.subscriptionKeys())
                && left.checkpointDomainBlueId().equals(
                right.checkpointDomainBlueId())
                && left.dependencies().equals(right.dependencies());
    }

    /**
     * The frozen companion may conservatively retire and re-add an unchanged
     * route because processor-owned checkpoint/dependency headers changed in
     * the invocation-local result. Those headers are re-established after a
     * real authored-contract change; otherwise the already verified interval
     * remains the exact input expected by the next Root invocation.
     */
    private static boolean sameStableSubscriptionIdentity(
            SubscriptionDelta.Entry left,
            SubscriptionDelta.Entry right) {
        return left.scopePath().equals(right.scopePath())
                && left.channelKey().equals(right.channelKey())
                && left.effectiveTypeBlueId().equals(
                right.effectiveTypeBlueId())
                && left.sourceContributionNodeBlueIds().equals(
                right.sourceContributionNodeBlueIds())
                && left.order() == right.order()
                && left.subscriptionKeys().equals(right.subscriptionKeys());
    }

    private static SubscriptionDelta.Entry refreshHeaderPreservingInterval(
            SubscriptionDelta.Entry established,
            SubscriptionDelta.Entry refreshed) {
        return new SubscriptionDelta.Entry(
                refreshed.scopePath(),
                refreshed.channelKey(),
                refreshed.effectiveTypeBlueId(),
                refreshed.sourceContributionNodeBlueIds(),
                refreshed.order(),
                refreshed.subscriptionKeys(),
                refreshed.checkpointDomainBlueId(),
                refreshed.dependencies(),
                established.activationRootRevision(),
                established.startAfterExternalOrderKey(),
                null);
    }

    private static OccurrenceKey occurrenceKey(
            SubscriptionDelta.Entry entry) {
        return new OccurrenceKey(entry.scopePath(), entry.channelKey());
    }

    private record OccurrenceKey(String scopePath, String channelKey) {
        private OccurrenceKey {
            scopePath = Objects.requireNonNull(scopePath, "scopePath");
            channelKey = Objects.requireNonNull(channelKey, "channelKey");
        }
    }

    public static ExternalOrderKey fullHistoryFrontier(DocumentId documentId) {
        return ExternalOrderKey.of(List.of(
                BigInteger.valueOf(Long.MIN_VALUE),
                "full-history",
                documentId.value()));
    }

    /**
     * Performs one frozen parent transition from the old child state using an
     * exact processor-owned embedded epoch input. No parent state is changed
     * before the frozen processor returns a successful commit companion.
     */
    public PreparedEmbedded prepareEmbedded(
            DocumentSession parent,
            EmbeddedEpochInput input) {
        long started = System.nanoTime();
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(input, "input");
        if (!input.binding().parentDocumentId().equals(parent.documentId())) {
            throw new IllegalArgumentException(
                    "Embedded input belongs to another parent");
        }
        if (parent.hasTransitionReceipt(input.inputId())) {
            throw new IllegalStateException(
                    "Embedded epoch is already committed: "
                            + input.inputId());
        }
        EmbeddedOnlyLayout beforeLayout = parent.layout();
        ExactValue before = parent.currentRevision().after();
        String actualBefore = before.canonicalBlueIdAt(
                input.binding().absolutePath());
        if (!input.beforeChildBlueId().equals(actualBefore)) {
            throw new IllegalStateException(
                    "Parent " + parent.documentId() + " contains child "
                            + actualBefore + " at "
                            + input.binding().absolutePath()
                            + " instead of expected "
                            + input.beforeChildBlueId());
        }

        long expectedEpoch = parent.epoch();
        failureInjector.accept(
                DefaultCoordinationEngine.FailurePoint.BEFORE_FROZEN_PROCESS);
        metrics.increment("process.concreteOwnershipRootInputs");
        metrics.increment("process.referenceOnlyEventInputs");
        long frozenStarted = System.nanoTime();
        metrics.addNanos("process.hostBeforeFrozen", frozenStarted - started);
        PlatformProcessingResult platform = runtime.process(
                beforeLayout.processingFrozen().toNode(),
                input.exactEvent().blueId(),
                expectedEpoch,
                input.applicationOrder(),
                parent.activeSubscriptions());
        long frozenNanos = System.nanoTime() - frozenStarted;
        metrics.addNanos("process.embeddedFrozen", frozenNanos);
        metrics.addNanos("process.frozen", frozenNanos);
        metrics.increment("process.embeddedEpochProcessCalls");
        // Preserve the aggregate PROCESS counter used by the public
        // diagnostics: an embedded-epoch application is a real frozen
        // Contracts invocation, not host-side materialization.
        metrics.increment("process.frozenContractsInvocations");
        metrics.increment("frozenProcessCalls");
        DocumentProcessingResult processed = platform.processResult();
        requireSuccess("apply embedded epoch to " + parent.documentId(),
                processed);
        failureInjector.accept(DefaultCoordinationEngine.FailurePoint
                .AFTER_FROZEN_BEFORE_STAGE);

        long hostAfterFrozenStarted = System.nanoTime();
        ExactValue processedAfter = layoutBuilder.restoreManagedChildren(
                processed.document(), beforeLayout, true);
        EmbeddedOnlyLayout afterLayout = layoutBuilder.rebuild(
                processedAfter, beforeLayout);
        ExactValue after = afterLayout.semanticRoot();
        String actualAfter = after.canonicalBlueIdAt(
                input.binding().absolutePath());
        if (!input.afterChildBlueId().equals(actualAfter)) {
            throw new IllegalStateException(
                    "Embedded epoch processor did not replace "
                            + input.binding().absolutePath() + " with "
                            + input.afterChildBlueId());
        }

        PlatformCommitCompanion companion = platform.commitCompanion();
        verifyCommitCompanion(
                companion,
                beforeLayout.processingFrozen().blueId(),
                input.exactEvent().blueId(),
                input.applicationOrder(),
                expectedEpoch,
                processed);
        List<SubscriptionDelta.Entry> activeSubscriptionsAfter = metrics.timed(
                "process.applyCommitCompanionDelta", () ->
                applySubscriptionTransition(
                        parent.activeSubscriptions(),
                        companion.subscriptionDelta(),
                        beforeLayout,
                        afterLayout,
                        Math.addExact(expectedEpoch, 1L),
                        input.applicationOrder(),
                        metrics));
        CheckpointDomainEvidence.retainAll(
                activeSubscriptionsAfter, objects);
        metrics.addNanos("process.hostAfterFrozen",
                System.nanoTime() - hostAfterFrozenStarted);
        return new PreparedEmbedded(
                parent,
                expectedEpoch,
                input,
                before,
                after,
                beforeLayout,
                afterLayout,
                activeSubscriptionsAfter,
                processed.events(),
                processed.totalGas(),
                System.nanoTime() - started);
    }

    /** Atomically publishes parent state, subscriptions, and idempotency receipt. */
    public InternalProcessOutcome commitEmbedded(PreparedEmbedded prepared) {
        Objects.requireNonNull(prepared, "prepared");
        DocumentSession parent = prepared.parent();
        if (parent.epoch() != prepared.expectedEpoch()) {
            throw new IllegalStateException(
                    "Parent changed after embedded preparation: "
                            + parent.documentId());
        }
        DocumentRevision revision = new DocumentRevision(
                parent.documentId(),
                Math.addExact(parent.epoch(), 1L),
                parent.nextApplicationOrder(),
                DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                prepared.before(),
                prepared.after(),
                null,
                prepared.input().sourceOrder(),
                prepared.input().originalEntryBlueId() == null
                        ? prepared.input().binding().attachmentEntryBlueId()
                        : prepared.input().originalEntryBlueId(),
                null,
                prepared.emittedEvents(),
                prepared.processingGas());
        parent.commit(
                revision,
                prepared.afterLayout(),
                null,
                prepared.activeSubscriptionsAfter(),
                prepared.input().inputId());
        metrics.increment("process.documentRevisionsCommitted");
        metrics.increment("embedding.parentEpochApplications");
        return new InternalProcessOutcome(
                parent,
                revision,
                prepared.beforeLayout(),
                prepared.afterLayout(),
                prepared.preparationNanos());
    }

    public static ExternalOrderKey admissionFrontier(DocumentId documentId) {
        return ExternalOrderKey.of(List.of(
                BigInteger.ZERO,
                "admission",
                documentId.value()));
    }

    /** Pure uncommitted outcome of exactly one frozen Contracts invocation. */
    public record Prepared(
            DocumentSession session,
            long expectedEpoch,
            TimelineEntry entry,
            ExactValue before,
            ExactValue after,
            EmbeddedOnlyLayout beforeLayout,
            EmbeddedOnlyLayout afterLayout,
            List<SubscriptionDelta.Entry> activeSubscriptionsAfter,
            List<Node> emittedEvents,
            long processingGas,
            long preparationNanos) {
        public Prepared {
            session = Objects.requireNonNull(session, "session");
            entry = Objects.requireNonNull(entry, "entry");
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            beforeLayout = Objects.requireNonNull(beforeLayout, "beforeLayout");
            afterLayout = Objects.requireNonNull(afterLayout, "afterLayout");
            activeSubscriptionsAfter = List.copyOf(Objects.requireNonNull(
                    activeSubscriptionsAfter, "activeSubscriptionsAfter"));
            if (processingGas < 0L || preparationNanos < 0L) {
                throw new IllegalArgumentException(
                        "processing metrics must be non-negative");
            }
            List<Node> copy = new ArrayList<>();
            for (Node event : Objects.requireNonNull(
                    emittedEvents, "emittedEvents")) {
                copy.add(Objects.requireNonNull(event, "event").clone());
            }
            emittedEvents = Collections.unmodifiableList(copy);
        }

        @Override
        public List<Node> emittedEvents() {
            List<Node> copy = new ArrayList<>(emittedEvents.size());
            emittedEvents.forEach(event -> copy.add(event.clone()));
            return Collections.unmodifiableList(copy);
        }
    }

    /** Pure uncommitted parent outcome for one processor-owned child epoch. */
    public record PreparedEmbedded(
            DocumentSession parent,
            long expectedEpoch,
            EmbeddedEpochInput input,
            ExactValue before,
            ExactValue after,
            EmbeddedOnlyLayout beforeLayout,
            EmbeddedOnlyLayout afterLayout,
            List<SubscriptionDelta.Entry> activeSubscriptionsAfter,
            List<Node> emittedEvents,
            long processingGas,
            long preparationNanos) {
        public PreparedEmbedded {
            parent = Objects.requireNonNull(parent, "parent");
            input = Objects.requireNonNull(input, "input");
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            beforeLayout = Objects.requireNonNull(
                    beforeLayout, "beforeLayout");
            afterLayout = Objects.requireNonNull(afterLayout, "afterLayout");
            activeSubscriptionsAfter = List.copyOf(Objects.requireNonNull(
                    activeSubscriptionsAfter, "activeSubscriptionsAfter"));
            emittedEvents = copyEvents(emittedEvents);
            if (processingGas < 0L || preparationNanos < 0L) {
                throw new IllegalArgumentException(
                        "processing metrics must be non-negative");
            }
        }

        @Override
        public List<Node> emittedEvents() {
            return copyEvents(emittedEvents);
        }
    }

    private static List<Node> copyEvents(List<Node> source) {
        List<Node> result = new ArrayList<>();
        for (Node event : Objects.requireNonNull(source, "source")) {
            result.add(Objects.requireNonNull(event, "event").clone());
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> managedBoundaries(
            EmbeddedOnlyLayout layout) {
        return layout.boundaries().stream()
                .map(EmbeddedBoundary::childScopePath)
                .distinct()
                .sorted()
                .toList();
    }

    private static void requireSuccess(
            String operation,
            DocumentProcessingResult result) {
        if (result.status() == ProcessorStatus.SUCCESS && result.commits()) {
            return;
        }
        String diagnostic = result.diagnostic() == null
                ? ""
                : ": " + result.diagnostic().message();
        throw new IllegalStateException(
                operation + " failed with " + result.status() + diagnostic);
    }
}
