package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochApplicationWork;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Causal prerequisites between a registered terminal and the original receiving-root calculation. */
final class RootedJoinScheduling {
    private RootedJoinScheduling() { }

    static RootedCheckpointDriver.Selection select(DocumentId root, RootedCheckpointDriver.Selection selected,
            List<RootedJoinEligibility.Fence> fences, InMemoryDocumentStore documents,
            Function<DocumentId, RootedCheckpointDriver.Selection> raw,
            java.util.function.BiPredicate<DocumentId, blue.language.processor.ExternalOrderKey> completeThrough) {
        if (selected.live() != null && RootedJoinPeerPrefixes.blocks(selected.live(), fences, documents, raw))
            return blocked(selected);
        for (var fence : fences) {
            var terminal = fence.terminal();
            if (terminal == null && localTerminal(selected.localHistorical(), fence)) return blocked(selected);
            if (terminal == null || !(selected.historical() != null
                    && selected.historical().workIdentity().equals(terminal.work().workIdentity())
                    || matches(terminal.work(), selected.localHistorical(), fence))) continue;
            var roots = new LinkedHashSet<DocumentId>();
            for (var member : terminal.interiorOwners()) {
                var snapshot = documents.require(member).rootedView().snapshot();
                var owners = snapshot.components().stream().filter(component -> component.orderedMemberDocumentIds()
                        .contains(ContractsClosureAdapter.closureId(member))).findFirst().orElseThrow().orderedMemberDocumentIds()
                        .stream().map(ContractsClosureAdapter::coordinationId).toList();
                if (!terminal.interiorOwners().containsAll(owners)) return blocked(selected);
                roots.add(owners.stream().min(EmbeddingBinding.DOCUMENT_ORDER).orElseThrow());
            }
            boolean prerequisite = false;
            boolean localTerminal = false;
            var ready = new ArrayList<RootedLocalHistory.Step>();
            for (var receiver : roots) {
                var next = raw.apply(receiver);
                if (next.blocked()) {
                    // An unavailable earlier prefix is not proof that this receiver has no
                    // original-cause delivery. A genuine NO_MATCH returns an unblocked selection.
                    prerequisite |= !completeThrough.test(receiver, fence.boundary());
                } else if (next.live() != null && next.live().entry().sourceOrderKey().compareTo(fence.boundary()) <= 0) {
                    prerequisite = true;
                } else if (next.localHistorical() != null && next.localHistorical().anchor().sourceOrderKey().compareTo(fence.boundary()) <= 0) {
                    if (matches(terminal.work(), next.localHistorical(), fence)) {
                        localTerminal = true;
                        if (currentHeads(next.localHistorical(), fence.owners(), documents)) ready.add(next.localHistorical());
                    } else prerequisite = true;
                } else if (next.historical() != null && documents.catchUpBarrier(next.historical().barrierIdentity())
                        .orElseThrow().causeOrder().compareTo(fence.boundary()) <= 0) {
                    prerequisite = true;
                }
            }
            if (prerequisite) return blocked(selected);
            if (!ready.isEmpty()) {
                ready.sort(java.util.Comparator.comparing(RootedLocalHistory.Step::sourceOrder)
                        .thenComparing(RootedLocalHistory.Step::root, EmbeddingBinding.DOCUMENT_ORDER));
                var chosen = ready.get(0);
                // Point handles in the same verified entry SCC identify this one
                // calculation. Eventual acquired owners are not alternate entrypoints.
                if (!chosen.invocation().rootedEvidence().context().entryOwners()
                        .contains(ContractsClosureAdapter.closureId(root))) return blocked(selected);
                // Both coordinates are retained: the actual local input executes, and the
                // registered work is settled by the ordinary atomic application publisher.
                return new RootedCheckpointDriver.Selection(null, terminal.work(), terminal.excludedConsumers(), false, chosen);
            }
            if (localTerminal) return blocked(selected);
        }
        return selected;
    }

    private static RootedCheckpointDriver.Selection blocked(RootedCheckpointDriver.Selection selected) {
        return new RootedCheckpointDriver.Selection(null, null, selected.excludedConsumers(), true);
    }

    private static boolean localTerminal(RootedLocalHistory.Step local, RootedJoinEligibility.Fence fence) {
        if (local == null || !local.anchor().sourceOrderKey().equals(fence.boundary())
                || !local.target().occurrenceIdentity().equals(fence.occurrence().occurrenceIdentity())
                || local.target().activationGeneration() != fence.occurrence().activationGeneration()) return false;
        var cause = local.invocation().input().cause();
        if (cause instanceof blue.language.processor.closure.ManagedRepresentationCause representation) return representation.terminalPositionReached();
        return cause instanceof blue.language.processor.closure.ManagedRevisionCause revision
                && revision.successorRepresentationCause().isEmpty()
                && revision.toEpoch() == local.invocation().input().snapshot().managedDocument(revision.childDocumentId()).epoch();
    }

    static boolean matches(ManagedEpochApplicationWork registered, RootedLocalHistory.Step local,
            RootedJoinEligibility.Fence fence) {
        if (local == null || !local.anchor().sourceOrderKey().equals(fence.boundary())) return false;
        var work = local.work();
        return work.consumerDocumentId().equals(registered.consumerDocumentId())
                && work.sourceDocumentId().equals(registered.sourceDocumentId())
                && work.sourceReceiptIdentity().equals(registered.sourceReceiptIdentity())
                && work.sourceEpoch() == registered.sourceEpoch()
                && work.targetOccurrenceIdentity().equals(registered.targetOccurrenceIdentity())
                && work.targetPath().equals(registered.targetPath()) && work.activationGeneration() == registered.activationGeneration()
                && work.representationCause().map(value -> value.causeIdentity()).equals(registered.representationCause().map(value -> value.causeIdentity()))
                && work.successorRepresentationCause().map(value -> value.causeIdentity()).equals(registered.successorRepresentationCause().map(value -> value.causeIdentity()));
    }

    /** Selection filter only; the real result still passes every complete original owner/CAS guard. */
    private static boolean currentHeads(RootedLocalHistory.Step local, Set<DocumentId> owners, InMemoryDocumentStore documents) {
        local.requireCurrentInput(documents);
        for (var owner : owners) {
            var captured = local.invocation().documents().get(owner);
            var session = documents.require(owner);
            if (captured == null || captured.head().epoch() != session.epoch()
                    || !captured.head().blueId().equals(session.currentRepresentation().blueId())
                    || captured.graphGeneration() != documents.graphGeneration(owner)) return false;
        }
        return true;
    }
}
