package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.AffectedClosureSnapshot;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable locator only: exact retained snapshots and plans still prove join eligibility. */
final class RootedJoinCandidateIndex {
    private final PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> membersByRoot;
    private final PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> rootsByMember;

    private RootedJoinCandidateIndex(
            PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> membersByRoot,
            PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> rootsByMember) {
        this.membersByRoot = membersByRoot;
        this.rootsByMember = rootsByMember;
    }

    static RootedJoinCandidateIndex empty() {
        return new RootedJoinCandidateIndex(PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER),
                PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER));
    }

    List<DocumentId> rootsFor(DocumentId member) { return bucket(rootsByMember, member).keys(); }

    RootedJoinCandidateIndex replace(Collection<DocumentId> owners, AffectedClosureSnapshot snapshot) {
        var changed = this;
        for (var owner : owners) changed = changed.replaceRoot(owner,
                RootedJoinEligibility.candidateMembers(snapshot, owner));
        return changed;
    }

    /** Used for a complete store reconstruction, never during point selection. */
    static RootedJoinCandidateIndex fromSessions(Collection<DocumentSession> sessions) {
        var index = empty();
        for (var session : sessions) if (session.rootedView() != null)
            index = index.replaceRoot(session.documentId(),
                    RootedJoinEligibility.candidateMembers(session.rootedView().snapshot(), session.documentId()));
        return index;
    }

    RootedJoinCandidateIndex replaceRoot(DocumentId root, Set<DocumentId> members) {
        var prior = bucket(membersByRoot, root);
        if (new LinkedHashSet<>(prior.keys()).equals(members)) return this;
        var reverse = rootsByMember;
        for (var member : prior.keys()) if (!members.contains(member)) {
            var roots = bucket(reverse, member).remove(root).map();
            reverse = roots.isEmpty() ? reverse.remove(member).map() : reverse.put(member, roots).map();
        }
        var selected = PersistentOrderedMap.<DocumentId, Boolean>empty(EmbeddingBinding.DOCUMENT_ORDER);
        for (var member : members) {
            selected = selected.put(member, true).map();
            if (!prior.containsKey(member))
                reverse = reverse.put(member, bucket(reverse, member).put(root, true).map()).map();
        }
        var forward = members.isEmpty() ? membersByRoot.remove(root).map() : membersByRoot.put(root, selected).map();
        return new RootedJoinCandidateIndex(forward, reverse);
    }

    private static PersistentOrderedMap<DocumentId, Boolean> bucket(
            PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> index, DocumentId key) {
        var bucket = index.get(key);
        return bucket == null ? PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER) : bucket;
    }
}
