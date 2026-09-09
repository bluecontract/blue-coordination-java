package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.TimelineEntry;
import blue.language.processor.ExternalOrderKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Stops an attachment before freezing an incomplete source view for a possible cyclic join. */
final class RootedJoinPrerequisites {
    private RootedJoinPrerequisites() { }

    static ManagedOccurrenceResolver.Resolution requireEarlierSourceWork(
            ContractsClosureAdapter.CohortInvocation current, ManagedOccurrenceResolver.Resolution resolution,
            InMemoryDocumentStore documents, ContractsClosureAdapter adapter, List<TimelineEntry> entries) {
        if (!resolution.complete()) return resolution;
        ExternalOrderKey boundary = current.rootedEvidence().historicalOrigin() == null
                ? RootedAttachmentCapture.logicalBoundary(current.input(), documents.catchUpPlansSnapshot())
                : current.rootedEvidence().historicalOrigin().logicalBoundary();
        Set<DocumentId> owners = new LinkedHashSet<>(current.rootedEvidence().context().entryOwners().stream()
                .map(ContractsClosureAdapter::coordinationId).toList());
        var resolved = new ArrayList<ManagedOccurrenceResolver.ResolvedOccurrence>();
        var missing = new ArrayList<>(resolution.unresolvedDemands());
        for (var occurrence : resolution.resolvedOccurrences()) {
            DocumentId source = occurrence.targetDocumentId();
            if (occurrence.targetKind() != ManagedOccurrenceResolver.TargetKind.NEW_AUTHORED
                    && !owners.contains(source) && documents.find(source).isPresent()
                    && returnsToOwner(source, owners, boundary, documents)) {
                var selected = documents.require(source).rootedViewBefore(boundary);
                String pending = pendingBefore(source, selected, boundary, documents);
                if (pending != null) {
                    missing.add(new ManagedOccurrenceResolver.UnresolvedDemand(occurrence.demand(),
                            ManagedOccurrenceResolver.ResolutionStatus.UNPROVEN_MANAGED_HISTORY, pending));
                    continue;
                }
                var next = new RootedCheckpointDriver(documents, adapter).select(source, entries);
                ExternalOrderKey order = next.live() != null ? next.live().entry().sourceOrderKey()
                        : null;
                if (order != null && order.compareTo(boundary) < 0) {
                    missing.add(new ManagedOccurrenceResolver.UnresolvedDemand(occurrence.demand(),
                            ManagedOccurrenceResolver.ResolutionStatus.UNPROVEN_MANAGED_HISTORY,
                            "Late cyclic join requires earlier source-owned work before its frozen anchor: source="
                                    + source + " requiredOrder=" + order + " attachmentOrder=" + boundary));
                    continue;
                }
            }
            resolved.add(occurrence);
        }
        return new ManagedOccurrenceResolver.Resolution(resolution.demands(), resolved,
                resolution.resolvedExactNodes(), missing, resolution.resolvedSelectorPaths());
    }

    /** Pending work is anchored by its occurrence and activation, not by an old receipt's timestamp. */
    private static String pendingBefore(DocumentId source, RootedDocumentView view,
            ExternalOrderKey boundary, InMemoryDocumentStore documents) {
        var snapshot = view.snapshot();
        var sourceId = ContractsClosureAdapter.closureId(source);
        var owners = snapshot.components().stream().filter(component ->
                component.orderedMemberDocumentIds().contains(sourceId)).findFirst().orElseThrow()
                .orderedMemberDocumentIds();
        var reachable = new LinkedHashSet<blue.language.processor.closure.DocumentId>();
        var queue = new ArrayDeque<blue.language.processor.closure.DocumentId>();
        queue.add(sourceId);
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (!reachable.add(current)) continue;
            snapshot.occurrences().stream().filter(row -> row.active() && row.sourceDocumentId().equals(current))
                    .forEach(row -> queue.addLast(row.targetDocumentId()));
        }
        for (var row : snapshot.occurrences()) {
            if (row.active() || row.pendingHistoricalEpoch() == null || !reachable.contains(row.sourceDocumentId())) continue;
            String anchor = " source=" + source + " selectedView=" + snapshot.closureIdentity()
                    + " occurrence=" + row.occurrenceIdentity() + " generation=" + row.activationGeneration()
                    + " attachmentOrder=" + boundary;
            if (!owners.contains(row.sourceDocumentId())) {
                if (view.logicalBoundary() == null) return "UNPROVEN_SOURCE_LOCAL_HISTORY_ORIGIN" + anchor;
                if (view.logicalBoundary().compareTo(boundary) < 0)
                    return "EARLIER_SOURCE_LOCAL_HISTORY_REQUIRED" + anchor + " origin=" + view.logicalBoundary();
                continue;
            }
            var consumer = ContractsClosureAdapter.coordinationId(row.sourceDocumentId());
            var matching = documents.catchUpPlans(consumer).stream().filter(plan ->
                    plan.targetOccurrenceIdentity().equals(row.occurrenceIdentity())
                            && plan.activationGeneration() == row.activationGeneration()
                            && plan.consumerDocumentId().equals(consumer)
                            && plan.sourceDocumentId().value().equals(row.targetDocumentId().value())
                            && plan.targetPath().equals(row.sourcePath())
                            && plan.nextSourceEpoch() == row.pendingHistoricalEpoch() + 1L).toList();
            if (matching.size() != 1) return "UNPROVEN_SOURCE_HISTORY_POSITION" + anchor;
            var plan = matching.get(0);
            var barrier = documents.catchUpBarrier(plan.barrierIdentity()).orElseThrow();
            if (!barrier.consumerDocumentId().equals(consumer) || !barrier.planIdentities().contains(plan.planIdentity()))
                return "UNPROVEN_SOURCE_HISTORY_BARRIER" + anchor;
            if (barrier.causeOrder().compareTo(boundary) >= 0) continue;
            if (plan.status() != ManagedCatchUpStatus.COMPLETE
                    && plan.status() != ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED) {
                return "EARLIER_SOURCE_HISTORY_UNAVAILABLE" + anchor + " barrier=" + barrier.barrierIdentity()
                        + " origin=" + barrier.causeOrder() + " status=" + plan.status();
            }
        }
        return null;
    }

    /** Reads authenticated pre-boundary forward graphs only; incoming indexes and newer heads are excluded. */
    private static boolean returnsToOwner(DocumentId source, Set<DocumentId> owners,
            ExternalOrderKey boundary, InMemoryDocumentStore documents) {
        var pending = new ArrayDeque<DocumentId>();
        var visited = new LinkedHashSet<DocumentId>();
        pending.add(source);
        while (!pending.isEmpty()) {
            DocumentId current = pending.removeFirst();
            if (owners.contains(current)) return true;
            if (!visited.add(current)) continue;
            var session = documents.find(current).orElse(null);
            if (session == null || session.rootedView() == null) continue;
            var view = session.rootedViewBefore(boundary);
            view.snapshot().occurrences().stream().filter(row -> row.active()
                    && row.sourceDocumentId().value().equals(current.value()))
                    .forEach(row -> pending.addLast(ContractsClosureAdapter.coordinationId(row.targetDocumentId())));
        }
        return false;
    }
}
