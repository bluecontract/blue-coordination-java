package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationPreparedDelivery;
import blue.coordination.processor.CoordinationSemanticDemandBoundary;
import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, mutation-free plan bound to one exact session epoch and event. */
public final class CoordinationProcessingPlan {

    private final ManagedDocumentSnapshot session;
    private final Node rootReference;
    private final Node eventReference;
    private final CoordinationPreparedDelivery preparedDelivery;
    private final CoordinationFragmentInventory rootInventory;
    private final CoordinationFragmentInventory eventInventory;
    private final Set<String> requiredSeedBlueIds;
    private final List<String> preferredPrefetchBlueIds;
    private final CoordinationSemanticDemandBoundary demandBoundary;
    private final String planIdentity;
    private final PrefetchPolicy prefetchPolicy;

    public CoordinationProcessingPlan(
            ManagedDocumentSnapshot session,
            Node rootReference,
            Node eventReference,
            CoordinationPreparedDelivery preparedDelivery,
            CoordinationFragmentInventory rootInventory,
            CoordinationFragmentInventory eventInventory,
            Collection<String> requiredSeedBlueIds,
            Collection<String> preferredPrefetchBlueIds,
            CoordinationSemanticDemandBoundary demandBoundary,
            String planIdentity,
            PrefetchPolicy prefetchPolicy) {
        this.session = Objects.requireNonNull(session, "session");
        this.rootReference = requireReference(rootReference, "rootReference");
        this.eventReference = requireReference(eventReference, "eventReference");
        this.preparedDelivery = Objects.requireNonNull(
                preparedDelivery, "preparedDelivery");
        this.rootInventory = Objects.requireNonNull(
                rootInventory, "rootInventory");
        this.eventInventory = Objects.requireNonNull(
                eventInventory, "eventInventory");
        this.requiredSeedBlueIds = immutableSet(
                requiredSeedBlueIds, "requiredSeedBlueIds");
        this.preferredPrefetchBlueIds = immutableList(
                preferredPrefetchBlueIds, "preferredPrefetchBlueIds");
        this.demandBoundary = Objects.requireNonNull(
                demandBoundary, "demandBoundary");
        this.planIdentity = requireText(planIdentity, "planIdentity");
        this.prefetchPolicy = Objects.requireNonNull(
                prefetchPolicy, "prefetchPolicy");
        if (!session.currentRootBlueId().equals(rootReference.getBlueId())
                || !rootInventory.rootBlueId().equals(rootReference.getBlueId())
                || !eventInventory.rootBlueId().equals(eventReference.getBlueId())) {
            throw new IllegalArgumentException(
                    "Plan references do not match their session/inventories");
        }
        if (!this.requiredSeedBlueIds.contains(rootReference.getBlueId())
                || !this.requiredSeedBlueIds.contains(eventReference.getBlueId())) {
            throw new IllegalArgumentException(
                    "Plan seed closure must include Root and event");
        }
    }

    public ManagedDocumentSnapshot session() { return session; }
    public Node rootReference() { return rootReference.clone(); }
    public Node eventReference() { return eventReference.clone(); }
    public CoordinationPreparedDelivery preparedDelivery() {
        return preparedDelivery;
    }
    public CoordinationFragmentInventory rootInventory() {
        return rootInventory;
    }
    public CoordinationFragmentInventory eventInventory() {
        return eventInventory;
    }
    public Set<String> requiredSeedBlueIds() { return requiredSeedBlueIds; }
    public List<String> preferredPrefetchBlueIds() {
        return preferredPrefetchBlueIds;
    }
    public CoordinationSemanticDemandBoundary demandBoundary() {
        return demandBoundary;
    }
    public String planIdentity() { return planIdentity; }
    public PrefetchPolicy prefetchPolicy() { return prefetchPolicy; }

    private static Node requireReference(Node value, String label) {
        Node checked = Objects.requireNonNull(value, label).clone();
        if (!checked.isReferenceOnly()) {
            throw new IllegalArgumentException(label + " must be a pure reference");
        }
        return checked;
    }

    private static Set<String> immutableSet(
            Collection<String> source,
            String label) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(immutableList(source, label)));
    }

    private static List<String> immutableList(
            Collection<String> source,
            String label) {
        List<String> result = new ArrayList<String>(
                Objects.requireNonNull(source, label));
        for (String value : result) requireText(value, label + " entry");
        return Collections.unmodifiableList(result);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }
}
