package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Exact peer prefixes for a new terminal operation, never substitutes for LIVE calculation. */
final class RootedTerminalPeerAcquisition {
    private RootedTerminalPeerAcquisition() { }

    static Optional<Map<DocumentId, RootedLocalHistory.Step>> select(RootedLocalHistory.Step original,
            List<RootedLocalHistory.Step> peers, RootedJoinEligibility.Fence fence,
            InMemoryDocumentStore documents, Consumer<RootedLocalHistory.Step> verifyJoin) {
        var selected = new LinkedHashMap<DocumentId, RootedLocalHistory.Step>();
        var input = original.invocation().input().snapshot();
        var calculating = new LinkedHashSet<>(input.publicRootDocumentIds());
        var pending = new ArrayDeque<>(calculating);
        while (!pending.isEmpty()) {
            var source = pending.removeFirst();
            input.occurrences().stream().filter(row -> row.active() && row.sourceDocumentId().equals(source))
                    .forEach(row -> { if (calculating.add(row.targetDocumentId())) pending.addLast(row.targetDocumentId()); });
        }
        requirePrefix(original, fence, documents, verifyJoin);
        for (var peer : peers) {
            var owners = peer.invocation().rootedEvidence().context().entryOwners();
            if (owners.equals(original.invocation().rootedEvidence().context().entryOwners())) continue;
            requirePrefix(peer, fence, documents, verifyJoin);
            // A calculating dependency owes its own local work. The frozen source and
            // pending consumer are authenticated by the ordinary registered capturer.
            if (owners.stream().anyMatch(calculating::contains)
                    || owners.stream().anyMatch(id -> id.value().equals(fence.terminal().work().sourceDocumentId().value())
                            || id.value().equals(fence.terminal().work().consumerDocumentId().value()))) {
                if (owners.stream().anyMatch(member -> !samePosition(original, peer, member, documents))) return Optional.empty();
                continue;
            }
            for (var member : owners) {
                if (!input.contains(member) || !fence.owners().contains(ContractsClosureAdapter.coordinationId(member)))
                    throw ContractsClosureAdapter.stale("Terminal peer is outside the original complete join evidence");
                var proof = peer.capturedState().view().retainedSnapshot();
                var oldRows = input.occurrences().stream().filter(row -> row.sourceDocumentId().equals(member))
                        .map(row -> row.occurrenceIdentity()).collect(java.util.stream.Collectors.toSet());
                var newRows = proof.occurrences().stream().filter(row -> row.sourceDocumentId().equals(member)).toList();
                // A different endpoint/occurrence inventory needs further causal evidence,
                // not the fixed-inventory witness-selection factory. Absence explicitly
                // keeps this candidate blocked even when coarse CAS coordinates match.
                if (!oldRows.equals(newRows.stream().map(row -> row.occurrenceIdentity())
                        .collect(java.util.stream.Collectors.toSet()))
                        || newRows.stream().anyMatch(row -> !input.contains(row.targetDocumentId()))
                        || input.occurrences().stream().anyMatch(row -> !row.active() && row.pendingHistoricalEpoch() != null
                                && calculating.contains(row.sourceDocumentId()) && row.targetDocumentId().equals(member)))
                    return Optional.empty();
                if (samePosition(original, peer, member, documents)) continue;
                var id = ContractsClosureAdapter.coordinationId(member);
                if (selected.putIfAbsent(id, peer) != null)
                    throw ContractsClosureAdapter.stale("Terminal peer has competing independent prefix proofs");
            }
        }
        return Optional.of(Map.copyOf(selected));
    }

    private static boolean samePosition(RootedLocalHistory.Step original, RootedLocalHistory.Step peer,
            blue.language.processor.closure.DocumentId member, InMemoryDocumentStore documents) {
        var input = original.invocation().input().snapshot();
        var proof = peer.capturedState().view().retainedSnapshot();
        var before = input.managedDocument(member);
        var after = proof.managedDocument(member);
        var captured = original.invocation().documents().get(ContractsClosureAdapter.coordinationId(member));
        return before != null && after != null && captured != null
                && before.epoch() == after.epoch() && before.blueId().equals(after.blueId())
                && before.componentGeneration() == after.componentGeneration()
                && before.initialized() == after.initialized() && before.terminated() == after.terminated()
                && blue.language.model.NodeWireForm.get(before.document()).equals(blue.language.model.NodeWireForm.get(after.document()))
                && ManagedOccurrenceInventory.sameRows(input.occurrences().stream().filter(row -> row.sourceDocumentId().equals(member)).toList(),
                        proof.occurrences().stream().filter(row -> row.sourceDocumentId().equals(member)).toList())
                && captured.graphGeneration() == documents.graphGeneration(ContractsClosureAdapter.coordinationId(member));
    }

    private static void requirePrefix(RootedLocalHistory.Step step, RootedJoinEligibility.Fence fence,
            InMemoryDocumentStore documents, Consumer<RootedLocalHistory.Step> verifyJoin) {
        if (fence.terminal() == null || !RootedJoinScheduling.matches(fence.terminal().work(), step, fence))
            throw ContractsClosureAdapter.stale("Peer does not perform the registered terminal cause");
        step.requireCurrentInput(documents);
        verifyJoin.accept(step);
        var view = step.capturedState().view();
        var original = RootedTerminalEvidence.originalLocalCause(view, documents);
        var owners = step.invocation().rootedEvidence().context().entryOwners();
        if (original == null || !original.causeIdentity().equals(fence.causeIdentity())
                || !original.sourceOrder().equals(fence.boundary()) || !fence.boundary().equals(view.logicalBoundary())
                || !view.result().commits() || !new LinkedHashSet<>(view.result().rootedProjection().ownedDocumentIds())
                        .equals(new LinkedHashSet<>(owners)))
            throw ContractsClosureAdapter.stale("Terminal peer is not an independently completed same-cause prefix");
        for (var member : owners) {
            var id = ContractsClosureAdapter.coordinationId(member);
            var session = documents.require(id);
            if (session.rootedView() != view)
                throw ContractsClosureAdapter.stale("Terminal peer publication changed before capture");
            view.requirePublishedHead(id, session.epoch(), session.currentRepresentation().blueId());
            session.rootedPublicationPrefix(view);
        }
    }
}
