package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import blue.coordination.api.ExactValue;

import blue.coordination.api.DocumentRevision;

import blue.coordination.api.DocumentId;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.PlatformCommitCompanion;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            TimelineEntry.CatchUpCause cause) {
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
            TimelineEntry.CatchUpCause cause) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(admissionFrontier, "admissionFrontier");
        ExactValue authoredExact = objects.put(
                runtime.cache(snapshot), "authored-document");
        DocumentIdentityReader.verifyOptionalDocumentId(
                authoredExact, documentId);

        DocumentProcessingResult initialized = metrics.timed(
                "documentStart.contractsInitialize",
                () -> runtime.initialize(snapshot));
        requireSuccess("initialize " + documentId, initialized);
        ExactValue initializedExact = objects.put(
                initialized.document(), "initialized-document");
        EmbeddedOnlyLayout layout = layoutBuilder.build(initializedExact);

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
        long hostBeforeFrozenStarted = System.nanoTime();
        failureInjector.accept(
                DefaultCoordinationEngine.FailurePoint.BEFORE_FROZEN_PROCESS);
        metrics.increment("process.concreteOwnershipRootInputs");
        metrics.increment("process.referenceOnlyEventInputs");
        metrics.addNanos("process.hostBeforeFrozen",
                System.nanoTime() - hostBeforeFrozenStarted);
        long frozenStarted = System.nanoTime();
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
        if (!entry.processorManaged()
                && processed.diagnostic() != null
                && processed.diagnostic().message().contains(
                "enters embedded scope")) {
            metrics.increment(
                    "layout.externalAutonomousChildMutationsRejected");
            throw new IllegalStateException(
                    "External parent operation attempted to mutate autonomous "
                            + "child: "
                            + processed.diagnostic().message());
        }
        requireSuccess("process " + session.documentId(), processed);
        failureInjector.accept(DefaultCoordinationEngine.FailurePoint
                .AFTER_FROZEN_BEFORE_STAGE);

        long hostAfterFrozenStarted = System.nanoTime();
        ExactValue processedAfter = layoutBuilder.restoreAutonomousChildren(
                processed.document(), beforeLayout, entry.processorManaged());
        EmbeddedOnlyLayout afterLayout = layoutBuilder.rebuild(
                processedAfter, beforeLayout);
        // Revision history and API reads always retain the fully materialized
        // semantic Root. The provider may independently expose an identity-
        // equivalent shell with autonomous children represented by references.
        ExactValue after = afterLayout.semanticRoot();

        PlatformCommitCompanion companion = platform.commitCompanion();
        verifyCommitCompanion(
                companion,
                beforeLayout.processingFrozen().blueId(),
                entry,
                expectedEpoch,
                processed);
        SubscriptionDelta delta = companion.subscriptionDelta();
        List<SubscriptionDelta.Entry> activeSubscriptionsAfter = metrics.timed(
                "process.applyCommitCompanionDelta",
                () -> applyCommitCompanionDelta(
                        session.activeSubscriptions(),
                        delta,
                        beforeLayout,
                        afterLayout,
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
        DocumentRevision.Kind kind = entry.processorManaged()
                ? DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION
                : DocumentRevision.Kind.TIMELINE_ENTRY;
        DocumentRevision revision = new DocumentRevision(
                session.documentId(),
                Math.addExact(session.epoch(), 1L),
                session.nextApplicationOrder(),
                kind,
                prepared.before(),
                prepared.after(),
                entry,
                entry.catchUpCause(),
                prepared.emittedEvents(),
                prepared.processingGas());
        session.commit(
                revision,
                prepared.afterLayout(),
                entry.processorManaged() ? null : entry.sourceOrderKey());
        session.replaceActiveSubscriptions(
                prepared.activeSubscriptionsAfter());
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
        Objects.requireNonNull(companion, "companion");
        if (!expectedProcessingRootBlueId.equals(
                companion.expectedRootBlueId())) {
            throw new IllegalStateException(
                    "Frozen PROCESS companion is bound to "
                            + companion.expectedRootBlueId()
                            + " instead of PROCESS ownership Root "
                            + expectedProcessingRootBlueId);
        }
        if (!entry.blueId().equals(companion.eventBlueId())
                || expectedEpoch != companion.expectedRootRevision()
                || !entry.journalOrderKey().equals(
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
        List<String> boundaries = autonomousBoundaries(layout);
        for (SubscriptionDelta.Entry entry : entries) {
            if (!owned(entry.scopePath(), boundaries)) {
                throw new IllegalStateException(
                        "PROCESS ownership projection leaked autonomous scope "
                                + entry.scopePath() + "/" + entry.channelKey());
            }
        }
    }

    private static List<SubscriptionDelta.Entry> applyCommitCompanionDelta(
            List<SubscriptionDelta.Entry> previous,
            SubscriptionDelta delta,
            EmbeddedOnlyLayout beforeLayout,
            EmbeddedOnlyLayout afterLayout,
            EngineMetrics metrics) {
        Objects.requireNonNull(delta, "delta");
        List<String> boundaries = new ArrayList<>(
                autonomousBoundaries(beforeLayout));
        for (String boundary : autonomousBoundaries(afterLayout)) {
            if (!boundaries.contains(boundary)) {
                boundaries.add(boundary);
            }
        }

        Map<OccurrenceKey, SubscriptionDelta.Entry> active = indexByOccurrence(
                previous, "active");
        Map<OccurrenceKey, SubscriptionDelta.Entry> ownedAdditions =
                new LinkedHashMap<>();
        for (SubscriptionDelta.Entry entry : delta.added()) {
            if (owned(entry.scopePath(), boundaries)) {
                ownedAdditions.put(occurrenceKey(entry), entry);
            }
        }
        for (SubscriptionDelta.Entry entry : delta.removed()) {
            if (!owned(entry.scopePath(), boundaries)) {
                continue;
            }
            SubscriptionDelta.Entry replacement = ownedAdditions.remove(
                    occurrenceKey(entry));
            if (replacement == null || !sameRoute(entry, replacement)) {
                throw new IllegalStateException(
                        "The compact lane freezes parent routing at admission: "
                                + entry.scopePath() + "/" + entry.channelKey());
            }
        }
        if (!ownedAdditions.isEmpty()) {
            throw new IllegalStateException(
                    "The compact lane gained dynamic parent subscriptions: "
                            + ownedAdditions.keySet());
        }

        // Membership is frozen in this compact lane. The companion can still
        // conservatively retire/re-add an unchanged route using invocation-
        // local dependency identities that the next exact-Root verifier rejects.
        // The paired delta proves membership; retain the established interval.
        long ignoredAutonomous = 0L;
        long retainedReplacements = 0L;
        for (SubscriptionDelta.Entry entry : delta.removed()) {
            if (!owned(entry.scopePath(), boundaries)) {
                ignoredAutonomous++;
                continue;
            }
            SubscriptionDelta.Entry established = active.get(
                    occurrenceKey(entry));
            if (established == null || !sameRoute(established, entry)) {
                throw new IllegalStateException(
                        "Frozen companion retired an inactive or different route at "
                                + entry.scopePath() + "/" + entry.channelKey());
            }
            retainedReplacements++;
        }
        for (SubscriptionDelta.Entry entry : delta.added()) {
            if (!owned(entry.scopePath(), boundaries)) {
                ignoredAutonomous++;
            }
        }

        List<SubscriptionDelta.Entry> result = new SubscriptionDelta(
                new ArrayList<>(active.values()), List.of()).added();
        metrics.increment("process.commitCompanionDeltasApplied");
        metrics.add("process.companionReplacementsRetained",
                retainedReplacements);
        metrics.add("process.autonomousSubscriptionDeltaEntriesIgnored",
                ignoredAutonomous);
        metrics.add("process.subscriptionIntervalsReused",
                previous.size());
        return result;
    }

    private static boolean owned(
            String scopePath,
            List<String> autonomousBoundaries) {
        for (String boundary : autonomousBoundaries) {
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

    private static boolean sameRoute(
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

    /** Applies a committed child revision without replaying its source event. */
    public InternalProcessOutcome materializeEmbeddedRevision(
            DocumentSession parent,
            EmbeddedLink link,
            DocumentRevision childRevision) {
        long started = System.nanoTime();
        EmbeddedOnlyLayout beforeLayout = parent.layout();
        ExactValue before = parent.currentRevision().after();
        EmbeddedOnlyLayout afterLayout = layoutBuilder.replaceEmbeddedState(
                beforeLayout, link.occurrencePath(), childRevision.after());
        ExactValue after = afterLayout.semanticRoot();
        DocumentRevision revision = new DocumentRevision(
                parent.documentId(),
                Math.addExact(parent.epoch(), 1L),
                parent.nextApplicationOrder(),
                DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                before,
                after,
                null,
                link.cause(),
                childRevision.emittedEvents(),
                0L);
        parent.commit(revision, afterLayout, null);
        metrics.increment("process.documentRevisionsCommitted");
        return new InternalProcessOutcome(parent, revision, beforeLayout, afterLayout,
                System.nanoTime() - started);
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

    private static List<String> autonomousBoundaries(
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
