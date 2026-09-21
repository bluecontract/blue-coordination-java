package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import java.util.*;

/** Exact live dependencies that prevent the currently supported independent retirement. */
final class InstanceRetirementProjection {
    private InstanceRetirementProjection() { }
    static List<String> blocks(InMemoryDocumentStore.StoreState state, DocumentId owner,
            List<RootedSourceDiscoveryCoordinator.Pending> sourceWork,
            java.util.function.Predicate<blue.coordination.api.ManagedOccurrenceCatchUpPlan> witnessedHistory, Set<DocumentId> retainedIncoming) {
        var reasons = new ArrayList<String>();
        var component = state.componentIndex().component(owner);
        if (component.cyclic() || !component.members().equals(List.of(owner)))
            reasons.add("ACTIVE_COMPONENT owners=" + component.members());
        var sources = state.componentIndex().directSources(owner).stream().filter(source -> !retainedIncoming.contains(source)).toList();
        if (!sources.isEmpty()) reasons.add("ACTIVE_DEPENDENT_OWNERS owners=" + sources);
        var joins = state.componentIndex().pendingJoinRootsFor(owner);
        if (!joins.isEmpty()) reasons.add("PENDING_JOIN_OWNERS owners=" + joins);
        var plans = state.catchUpPlans();
        var selected = new LinkedHashSet<blue.coordination.api.ManagedOccurrenceCatchUpPlan>();
        selected.addAll(plans.plansForConsumer(owner).plans()); selected.addAll(plans.plansForSource(owner).plans());
        for (var plan : selected) if (!plan.status().terminal() && !witnessedHistory.test(plan))
            reasons.add("UNRESOLVED_PLAN identity=" + plan.planIdentity() + " occurrence=" + plan.targetOccurrenceIdentity());
        var session = Objects.requireNonNull(state.sessionIndex().get(owner));
        for (var occurrence : RootedLocalHistory.pending(session.rootedView().snapshot(), owner))
            reasons.add("PENDING_LOCAL_HISTORY occurrence=" + occurrence.occurrenceIdentity());
        for (var pending : sourceWork) reasons.add("PENDING_SOURCE_WORK identity=" + pending.key() + " source=" + pending.source()
                + " requestingOwners=" + pending.invocation().rootedEvidence().context().entryOwners());
        return List.copyOf(reasons);
    }
}
