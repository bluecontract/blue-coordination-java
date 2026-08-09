package blue.coordination.basic.engine;

import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Coordinates autonomous Process Embedded document sessions.
 *
 * <p>Each child initializes and processes its source Timeline history once.
 * A parent stores a link and consumes the child's exact revision stream under
 * its own cursor. Attachment creates a synchronous historical catch-up barrier
 * through the attachment entry's source-order cutoff.</p>
 */
public final class EmbeddedGraphCoordinator {
    public static final class State {
        private final Map<String, CatchUpPlan> plans;
        private final Map<String, Long> detachedCursors;

        private State(
                Map<String, CatchUpPlan> plans,
                Map<String, Long> detachedCursors) {
            this.plans = plans;
            this.detachedCursors = detachedCursors;
        }
    }

    public interface EngineAccess {
        InMemoryDocumentStore documents();

        DocumentSession admitEmbedded(
                EmbeddedOccurrence occurrence,
                CatchUpCause cause,
                EnvironmentFrontier cutoff,
                ExternalOrderKey cutoffOrderKey);

        List<ExactTimelineEntry> journalEntriesThrough(
                DocumentSession child,
                EnvironmentFrontier cutoff);

        boolean routesTo(DocumentId documentId, ExactTimelineEntry entry);

        ProcessOutcome processTarget(
                DocumentSession session,
                ExactTimelineEntry entry);

        ExactTimelineEntry appendInternalRevision(
                DocumentSession parent,
                EmbeddedLink link,
                DocumentRevision childRevision);

        ProcessOutcome materializeEmbeddedRevision(
                DocumentSession parent,
                EmbeddedLink link,
                DocumentRevision childRevision);

        boolean hasRevisionApplicationReceipt(String key);

        void commitRevisionApplicationReceipt(String key);

        void inject(BasicCoordinationEngine.FailurePoint point);

        EngineMetrics metrics();
    }

    private final EngineAccess engine;
    private final Map<DocumentId, List<EmbeddedLink>> linksByChild =
            new LinkedHashMap<>();
    private final Map<String, CatchUpPlan> plans = new LinkedHashMap<>();
    private final Map<String, Long> detachedCursors = new LinkedHashMap<>();

    public EmbeddedGraphCoordinator(EngineAccess engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Activates children already present in a newly admitted child session. */
    public synchronized void synchronizeAdmission(
            DocumentSession session,
            CatchUpCause inheritedCause,
            EnvironmentFrontier cutoff,
            ExternalOrderKey cutoffOrderKey) {
        Objects.requireNonNull(session, "session");
        List<EmbeddedOccurrence> occurrences =
                session.layout().directOccurrences();
        if (occurrences.isEmpty()) {
            return;
        }
        if (inheritedCause == null || cutoff == null
                || cutoffOrderKey == null) {
            throw new IllegalStateException(
                    "Process Embedded children require an explicit admission "
                            + "cause and catch-up cutoff");
        }
        for (EmbeddedOccurrence occurrence : occurrences) {
            CatchUpCause nestedCause = new CatchUpCause(
                    session.documentId(),
                    inheritedCause.attachmentEntryBlueId(),
                    occurrence.scopePath(),
                    inheritedCause.attachmentTimestampMicros());
            activate(
                    session,
                    occurrence,
                    nestedCause,
                    cutoff,
                    cutoffOrderKey);
        }
    }

    /**
     * Publishes the exact committed revision to existing parents first, then
     * discovers children introduced by that revision. This preserves epoch
     * order when one child event itself attaches another child.
     */
    public synchronized void afterCommit(
            ProcessOutcome outcome,
            ExactTimelineEntry causalEntry) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(causalEntry, "causalEntry");
        propagateRevision(outcome.revision());
        refreshDirectLinks(
                outcome.session(),
                outcome.beforeLayout(),
                outcome.afterLayout(),
                causalEntry);
    }

    public synchronized List<CatchUpPlan> plans() {
        return Collections.unmodifiableList(new ArrayList<>(plans.values()));
    }

    public synchronized List<EmbeddedLink> linksForChild(DocumentId childId) {
        return Collections.unmodifiableList(new ArrayList<>(
                linksByChild.getOrDefault(childId, List.of())));
    }

    public synchronized State snapshot() {
        Map<String, CatchUpPlan> copy = new LinkedHashMap<>();
        plans.forEach((id, plan) -> copy.put(
                id, plan.copyWith(plan.link().copy())));
        return new State(
                copy, new LinkedHashMap<>(detachedCursors));
    }

    public synchronized void restore(State state) {
        Objects.requireNonNull(state, "state");
        linksByChild.clear();
        plans.clear();
        detachedCursors.clear();
        detachedCursors.putAll(state.detachedCursors);
        for (DocumentSession session : engine.documents().sessions()) {
            session.linksByPath().values().forEach(this::registerReverseLink);
        }
        state.plans.forEach((id, saved) -> {
            EmbeddedLink restored = engine.documents()
                    .require(saved.link().parentDocumentId())
                    .linksByPath()
                    .get(saved.link().occurrencePath());
            if (restored != null
                    && restored.childDocumentId().equals(
                    saved.link().childDocumentId())) {
                plans.put(id, saved.copyWith(restored));
            }
        });
    }

    private void refreshDirectLinks(
            DocumentSession parent,
            EmbeddedOnlyLayout before,
            EmbeddedOnlyLayout after,
            ExactTimelineEntry causalEntry) {
        Map<String, EmbeddedOccurrence> oldByPath = byPath(
                before.directOccurrences());
        Map<String, EmbeddedOccurrence> newByPath = byPath(
                after.directOccurrences());

        for (String removedPath : difference(
                oldByPath.keySet(), newByPath.keySet())) {
            detach(parent, removedPath);
        }
        for (Map.Entry<String, EmbeddedOccurrence> item
                : newByPath.entrySet()) {
            EmbeddedOccurrence old = oldByPath.get(item.getKey());
            EmbeddedOccurrence current = item.getValue();
            if (old == null) {
                activate(parent, current, new CatchUpCause(
                        parent.documentId(),
                        causalEntry.blueId(),
                        current.scopePath(),
                        causalEntry.timestampMicros()),
                        causalEntry.appendFrontier(),
                        causalEntry.sourceOrderKey());
            } else if (!old.childDocumentId().equals(
                    current.childDocumentId())) {
                detach(parent, item.getKey());
                activate(parent, current, new CatchUpCause(
                        parent.documentId(),
                        causalEntry.blueId(),
                        current.scopePath(),
                        causalEntry.timestampMicros()),
                        causalEntry.appendFrontier(),
                        causalEntry.sourceOrderKey());
            }
        }
    }

    private void activate(
            DocumentSession parent,
            EmbeddedOccurrence occurrence,
            CatchUpCause cause,
            EnvironmentFrontier cutoff,
            ExternalOrderKey cutoffOrderKey) {
        if (occurrence.activationMode() == ActivationMode.PASSIVE_SNAPSHOT) {
            engine.metrics().increment("embedding.passiveSnapshots");
            return;
        }
        if (wouldCreateCycle(parent.documentId(), occurrence.childDocumentId())) {
            parent.markBlocked();
            throw new IllegalStateException(
                    "Embedding cycle: " + parent.documentId() + " -> "
                            + occurrence.childDocumentId());
        }

        DocumentSession existing = engine.documents()
                .find(occurrence.childDocumentId())
                .orElse(null);
        DocumentSession child = existing != null
                ? existing
                : engine.admitEmbedded(
                        occurrence, cause, cutoff, cutoffOrderKey);
        if (existing != null) {
            engine.metrics().increment("embedding.childSessionsReused");
            engine.metrics().increment("sessionsReused");
        }
        if (!child.authoredInitialBlueId().equals(
                occurrence.suppliedState().blueId())) {
            parent.markBlocked();
            throw new IllegalStateException(
                    "Embedded document " + occurrence.childDocumentId()
                            + " must be supplied in its exact original initial "
                            + "state. Expected " + child.authoredInitialBlueId()
                            + ", received "
                            + occurrence.suppliedState().blueId());
        }

        long reattachmentCursor = detachedCursors.getOrDefault(
                relationshipKey(
                        parent.documentId(),
                        occurrence.scopePath(),
                        child.documentId()),
                -1L);
        EmbeddedLink link = new EmbeddedLink(
                parent.documentId(),
                occurrence.scopePath(),
                child.documentId(),
                occurrence.activationMode(),
                cause,
                cutoff,
                cutoffOrderKey,
                reattachmentCursor);
        parent.putLink(link);
        registerReverseLink(link);
        parent.markCatchingUp();

        CatchUpPlan plan = new CatchUpPlan(planId(link), link);
        plans.put(plan.planId(), plan);
        try {
            plan.beginReplay();
            applyExistingRevisionsThrough(parent, child, link, cutoff, plan);
            if (existing == null) {
                ensureChildHistoryThrough(child, link, cutoff);
            }
            applyExistingRevisionsThrough(parent, child, link, cutoff, plan);
            if (reattachmentCursor >= 0L
                    && link.appliedChildEpoch() == reattachmentCursor) {
                engine.materializeEmbeddedRevision(
                        parent,
                        link,
                        child.revision(reattachmentCursor));
                engine.metrics().increment(
                        "embedding.reattachmentMaterializations");
            }
            plan.complete();
            markReadyWhenAllPlansComplete(parent, cutoffOrderKey);
            // A reused child may already have revisions after the attachment
            // cutoff. They are delivered only after historical catch-up closes.
            applyAvailableLiveRevisions(parent, child, link, plan);
            engine.metrics().increment("catchUp.plansCompleted");
        } catch (RuntimeException failure) {
            plan.block(failure.getMessage() == null
                    ? failure.getClass().getName()
                    : failure.getMessage());
            parent.markBlocked();
            engine.metrics().increment("catchUp.plansBlocked");
            throw failure;
        }
    }

    private void ensureChildHistoryThrough(
            DocumentSession child,
            EmbeddedLink link,
            EnvironmentFrontier cutoff) {
        long historyStarted = System.nanoTime();
        List<ExactTimelineEntry> historicalEntries =
                engine.journalEntriesThrough(child, cutoff);
        engine.metrics().addNanos(
                "embedded.historyRead",
                System.nanoTime() - historyStarted);
        if (link.activationMode() == ActivationMode.BIRTH_AT_ATTACHMENT) {
            for (ExactTimelineEntry entry : historicalEntries) {
                if (engine.routesTo(child.documentId(), entry)) {
                    throw new IllegalStateException(
                            "Birth-at-attachment child has historical entries: "
                                    + child.documentId());
                }
            }
            return;
        }
        if (link.activationMode() == ActivationMode.IMPORT_FROM_FRONTIER) {
            throw new UnsupportedOperationException(
                    "IMPORT_FROM_FRONTIER requires persisted cursor and "
                            + "completeness evidence; it is never inferred");
        }
        long replayStarted = System.nanoTime();
        for (ExactTimelineEntry entry : historicalEntries) {
            if (child.hasTerminalEntry(entry.blueId())
                    || !engine.routesTo(child.documentId(), entry)) {
                continue;
            }
            engine.processTarget(child, entry.withCatchUpCause(link.cause()));
            engine.metrics().increment("catchUp.childEntriesProcessed");
            engine.metrics().increment("childHistoricalProcessCalls");
        }
        engine.metrics().addNanos(
                "embedded.childReplay",
                System.nanoTime() - replayStarted);
    }

    private void applyExistingRevisionsThrough(
            DocumentSession parent,
            DocumentSession child,
            EmbeddedLink link,
            EnvironmentFrontier cutoff,
            CatchUpPlan plan) {
        List<DocumentRevision> eligible = new ArrayList<>();
        for (DocumentRevision revision : child.revisionsAfter(
                link.appliedChildEpoch())) {
            if (!eligibleThrough(revision, cutoff)) {
                break;
            }
            String receipt = revisionReceipt(link, revision);
            if (engine.hasRevisionApplicationReceipt(receipt)) {
                throw new IllegalStateException(
                        "Revision receipt is ahead of attachment cursor: "
                                + receipt);
            }
            eligible.add(revision);
        }
        for (DocumentRevision revision : eligible) {
            String receipt = revisionReceipt(link, revision);
            applyToParent(parent, link, revision);
            engine.inject(BasicCoordinationEngine.FailurePoint
                    .AFTER_APPLYING_CHILD_REVISION);
            plan.markApplied(revision.epoch());
            engine.commitRevisionApplicationReceipt(receipt);
        }
    }

    private void applyAvailableLiveRevisions(
            DocumentSession parent,
            DocumentSession child,
            EmbeddedLink link,
            CatchUpPlan plan) {
        for (DocumentRevision revision : child.revisionsAfter(
                link.appliedChildEpoch())) {
            String receipt = revisionReceipt(link, revision);
            applyToParent(parent, link, revision);
            engine.inject(BasicCoordinationEngine.FailurePoint
                    .AFTER_APPLYING_CHILD_REVISION);
            plan.markApplied(revision.epoch());
            engine.commitRevisionApplicationReceipt(receipt);
            engine.metrics().increment("catchUp.liveBacklogApplications");
        }
    }

    private void propagateRevision(DocumentRevision childRevision) {
        List<EmbeddedLink> links = new ArrayList<>(
                linksByChild.getOrDefault(
                        childRevision.documentId(), List.of()));
        links.sort(Comparator
                .comparing((EmbeddedLink link) ->
                        link.parentDocumentId().value())
                .thenComparing(EmbeddedLink::occurrencePath));
        for (EmbeddedLink link : links) {
            if (childRevision.epoch() <= link.appliedChildEpoch()) {
                continue;
            }
            if (childRevision.epoch() != link.appliedChildEpoch() + 1L) {
                throw new IllegalStateException(
                        "Parent link missed child revision "
                                + link.childDocumentId() + " epoch "
                                + childRevision.epoch());
            }
            CatchUpPlan plan = plans.get(planId(link));
            if (plan != null
                    && plan.status() != CatchUpPlan.Status.COMPLETE
                    && !eligibleThrough(childRevision, link.cutoff())) {
                // The revision is live-after-cutoff and waits behind the barrier.
                continue;
            }
            DocumentSession parent = engine.documents().require(
                    link.parentDocumentId());
            String receipt = revisionReceipt(link, childRevision);
            applyToParent(parent, link, childRevision);
            engine.inject(BasicCoordinationEngine.FailurePoint
                    .AFTER_APPLYING_CHILD_REVISION);
            if (plan != null) {
                plan.markApplied(childRevision.epoch());
            } else {
                link.markApplied(childRevision.epoch());
            }
            engine.commitRevisionApplicationReceipt(receipt);
            if (parent.status() == SessionStatus.CATCHING_UP
                    && allPlansComplete(parent)) {
                parent.markReady(link.cutoffOrderKey());
            }
        }
    }

    private void applyToParent(
            DocumentSession parent,
            EmbeddedLink link,
            DocumentRevision revision) {
        long started = System.nanoTime();
        if (!parent.layout()
                .routingSurface()
                .deliversEmbeddedRevisionEvents()) {
            engine.materializeEmbeddedRevision(parent, link, revision);
            engine.metrics().increment("catchUp.parentRevisionApplications");
            engine.metrics().increment("childRevisionApplications");
            engine.metrics().addNanos(
                    "embedded.parentApply",
                    System.nanoTime() - started);
            return;
        }
        ExactTimelineEntry internal = engine.appendInternalRevision(
                parent, link, revision);
        engine.processTarget(parent, internal);
        engine.metrics().increment("catchUp.parentRevisionApplications");
        engine.metrics().increment("childRevisionApplications");
        engine.metrics().addNanos(
                "embedded.parentApply",
                System.nanoTime() - started);
    }

    private static boolean eligibleThrough(
            DocumentRevision revision,
            EnvironmentFrontier cutoff) {
        return revision.kind() == RevisionKind.INITIALIZATION
                || revision.sourceEntry()
                .map(cutoff::includes)
                .orElse(true);
    }

    private void markReadyWhenAllPlansComplete(
            DocumentSession parent,
            ExternalOrderKey cutoffOrderKey) {
        if (allPlansComplete(parent)) {
            parent.markReady(cutoffOrderKey);
        }
    }

    private boolean allPlansComplete(DocumentSession parent) {
        for (EmbeddedLink link : parent.linksByPath().values()) {
            CatchUpPlan plan = plans.get(planId(link));
            if (plan != null && plan.status() != CatchUpPlan.Status.COMPLETE) {
                return false;
            }
        }
        return true;
    }

    private void registerReverseLink(EmbeddedLink link) {
        List<EmbeddedLink> links = linksByChild.computeIfAbsent(
                link.childDocumentId(), ignored -> new ArrayList<>());
        boolean duplicate = links.stream().anyMatch(existing ->
                existing.parentDocumentId().equals(link.parentDocumentId())
                        && existing.occurrencePath().equals(
                        link.occurrencePath()));
        if (!duplicate) {
            links.add(link);
        }
    }

    private void detach(DocumentSession parent, String occurrencePath) {
        EmbeddedLink link = parent.linksByPath().get(occurrencePath);
        if (link == null) {
            return;
        }
        parent.removeLink(occurrencePath);
        detachedCursors.put(
                relationshipKey(
                        link.parentDocumentId(),
                        link.occurrencePath(),
                        link.childDocumentId()),
                link.appliedChildEpoch());
        List<EmbeddedLink> childLinks = linksByChild.get(
                link.childDocumentId());
        if (childLinks != null) {
            childLinks.removeIf(candidate ->
                    candidate.parentDocumentId().equals(parent.documentId())
                            && candidate.occurrencePath().equals(
                            occurrencePath));
            if (childLinks.isEmpty()) {
                linksByChild.remove(link.childDocumentId());
            }
        }
        plans.remove(planId(link));
        engine.metrics().increment("embedding.linksRemoved");
    }

    private boolean wouldCreateCycle(DocumentId parent, DocumentId child) {
        if (parent.equals(child)) {
            return true;
        }
        Set<DocumentId> visited = new LinkedHashSet<>();
        List<DocumentId> pending = new ArrayList<>();
        pending.add(child);
        while (!pending.isEmpty()) {
            DocumentId current = pending.remove(pending.size() - 1);
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(parent)) {
                return true;
            }
            engine.documents().find(current).ifPresent(session ->
                    session.linksByPath().values().forEach(link ->
                            pending.add(link.childDocumentId())));
        }
        return false;
    }

    private static Map<String, EmbeddedOccurrence> byPath(
            List<EmbeddedOccurrence> occurrences) {
        Map<String, EmbeddedOccurrence> result = new LinkedHashMap<>();
        for (EmbeddedOccurrence occurrence : occurrences) {
            EmbeddedOccurrence duplicate = result.putIfAbsent(
                    occurrence.scopePath(), occurrence);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate direct occurrence path "
                                + occurrence.scopePath());
            }
        }
        return result;
    }

    private static Set<String> difference(
            Set<String> left,
            Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static String planId(EmbeddedLink link) {
        return link.parentDocumentId().value() + "|"
                + link.occurrencePath() + "|"
                + link.cause().attachmentEntryBlueId();
    }

    private static String revisionReceipt(
            EmbeddedLink link,
            DocumentRevision revision) {
        return link.parentDocumentId().value() + "|"
                + link.occurrencePath() + "|"
                + link.childDocumentId().value() + "|"
                + revision.epoch();
    }

    private static String relationshipKey(
            DocumentId parent,
            String path,
            DocumentId child) {
        return parent.value() + "|" + path + "|" + child.value();
    }
}
