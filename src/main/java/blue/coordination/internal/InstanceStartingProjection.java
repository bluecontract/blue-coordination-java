package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import java.util.*;

/** Starts only an independent owner from its real archived Contracts representation. */
final class InstanceStartingProjection {
    private InstanceStartingProjection() { }
    static List<String> blocks(DocumentSession session) {
        var owner = session.documentId(); var snapshot = session.rootedView().snapshot();
        var reasons = new ArrayList<String>();
        var component = snapshot.components().stream().filter(row -> row.orderedMemberDocumentIds()
                .contains(ContractsClosureAdapter.closureId(owner))).findFirst().orElseThrow();
        if (component.kind() == blue.language.processor.closure.ComponentKind.CYCLIC || component.orderedMemberDocumentIds().size() != 1)
            reasons.add("STARTING_CYCLIC_COMPONENT owners=" + component.orderedMemberDocumentIds());
        var joins = RootedJoinEligibility.candidateMembers(snapshot, owner);
        if (!joins.isEmpty()) reasons.add("STARTING_PENDING_JOIN owners=" + joins);
        for (var row : RootedLocalHistory.pending(snapshot, owner)) reasons.add("STARTING_PENDING_HISTORY occurrence=" + row.occurrenceIdentity());
        return List.copyOf(reasons);
    }
}
