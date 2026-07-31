package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.VerifiedExecutionEvidence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable result of exact indexed delivery planning.
 *
 * <p>The contained plan and evidence are bound to the exact Root and event;
 * neither value is Blue content or an additional semantic PROCESS input.
 * Diagnostic fields explain the physical scope and resource closure without
 * exposing mutable runtime contracts.</p>
 */
public final class CoordinationPreparedDelivery {

    private final Node rootReference;
    private final Node eventReference;
    private final VerifiedExecutionEvidence evidence;
    private final ExternalDeliveryPlan deliveryPlan;
    private final String deliveryPlanIdentity;
    private final String subscriptionSnapshotIdentity;
    private final List<String> preselectedOccurrenceOrder;
    private final List<CoordinationDeliveryDiagnostic> sourceDeliveries;
    private final Map<String, List<String>> selectedScopeChainIdentities;
    private final Set<String> requiredSeedFragmentIdentities;
    private final List<String> prefetchIdentities;
    private final CoordinationSemanticDemandBoundary demandBoundary;

    CoordinationPreparedDelivery(
            String rootBlueId,
            String eventBlueId,
            VerifiedExecutionEvidence evidence,
            ExternalDeliveryPlan deliveryPlan,
            String deliveryPlanIdentity,
            String subscriptionSnapshotIdentity,
            Collection<String> preselectedOccurrenceOrder,
            Collection<CoordinationDeliveryDiagnostic> sourceDeliveries,
            Map<String, ? extends Collection<String>>
                    selectedScopeChainIdentities,
            Collection<String> requiredSeedFragmentIdentities,
            Collection<String> prefetchIdentities,
            CoordinationSemanticDemandBoundary demandBoundary) {
        this.rootReference = new Node().blueId(
                requireText(rootBlueId, "rootBlueId"));
        this.eventReference = new Node().blueId(
                requireText(eventBlueId, "eventBlueId"));
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.deliveryPlan = Objects.requireNonNull(
                deliveryPlan, "deliveryPlan");
        this.deliveryPlanIdentity = requireText(
                deliveryPlanIdentity, "deliveryPlanIdentity");
        this.subscriptionSnapshotIdentity = requireText(
                subscriptionSnapshotIdentity,
                "subscriptionSnapshotIdentity");
        this.preselectedOccurrenceOrder = immutableText(
                preselectedOccurrenceOrder,
                "preselected occurrence");
        this.sourceDeliveries = immutableDiagnostics(sourceDeliveries);
        this.selectedScopeChainIdentities =
                immutableScopeChains(selectedScopeChainIdentities);
        this.requiredSeedFragmentIdentities =
                immutableTextSet(
                        requiredSeedFragmentIdentities,
                        "required seed fragment identity");
        this.prefetchIdentities = immutableText(
                prefetchIdentities, "prefetch identity");
        this.demandBoundary = Objects.requireNonNull(
                demandBoundary, "demandBoundary");
        validateBindings(rootBlueId, eventBlueId);
    }

    public Node rootReference() {
        return rootReference.clone();
    }

    public Node eventReference() {
        return eventReference.clone();
    }

    public VerifiedExecutionEvidence evidence() {
        return evidence;
    }

    public ExternalDeliveryPlan deliveryPlan() {
        return deliveryPlan;
    }

    public String deliveryPlanIdentity() {
        return deliveryPlanIdentity;
    }

    public String subscriptionSnapshotIdentity() {
        return subscriptionSnapshotIdentity;
    }

    public List<String> preselectedOccurrenceOrder() {
        return preselectedOccurrenceOrder;
    }

    public List<CoordinationDeliveryDiagnostic> sourceDeliveries() {
        return sourceDeliveries;
    }

    public Map<String, List<String>> selectedScopeChainIdentities() {
        return selectedScopeChainIdentities;
    }

    public Set<String> requiredSeedFragmentIdentities() {
        return requiredSeedFragmentIdentities;
    }

    public List<String> prefetchIdentities() {
        return prefetchIdentities;
    }

    public CoordinationSemanticDemandBoundary demandBoundary() {
        return demandBoundary;
    }

    private void validateBindings(
            String rootBlueId,
            String eventBlueId) {
        if (!rootBlueId.equals(evidence.rootBlueId())
                || !eventBlueId.equals(evidence.eventBlueId())) {
            throw new IllegalArgumentException(
                    "Prepared delivery evidence does not bind to its exact "
                            + "Root and event");
        }
        if (!requiredSeedFragmentIdentities.contains(rootBlueId)
                || !requiredSeedFragmentIdentities.contains(eventBlueId)) {
            throw new IllegalArgumentException(
                    "Prepared delivery seed closure omits its Root or event");
        }
        if (preselectedOccurrenceOrder.size()
                != sourceDeliveries.size()) {
            throw new IllegalArgumentException(
                    "Prepared delivery occurrence and diagnostic counts "
                            + "disagree");
        }
        for (int index = 0;
                index < preselectedOccurrenceOrder.size();
                index++) {
            if (!preselectedOccurrenceOrder.get(index).equals(
                    sourceDeliveries.get(index).occurrenceKey())) {
                throw new IllegalArgumentException(
                        "Prepared delivery diagnostics are not in canonical "
                                + "occurrence order");
            }
        }
    }

    private static List<CoordinationDeliveryDiagnostic>
    immutableDiagnostics(
            Collection<CoordinationDeliveryDiagnostic> source) {
        Objects.requireNonNull(source, "sourceDeliveries");
        return Collections.unmodifiableList(
                new ArrayList<>(source));
    }

    private static Map<String, List<String>> immutableScopeChains(
            Map<String, ? extends Collection<String>> source) {
        Objects.requireNonNull(
                source, "selectedScopeChainIdentities");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Collection<String>>
                entry : source.entrySet()) {
            copy.put(
                    requireText(entry.getKey(), "scope path"),
                    immutableText(
                            entry.getValue(),
                            "scope chain identity"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Set<String> immutableTextSet(
            Collection<String> source,
            String label) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(
                        immutableText(source, label)));
    }

    private static List<String> immutableText(
            Collection<String> source,
            String label) {
        Objects.requireNonNull(source, label + " collection");
        List<String> copy = new ArrayList<>(source.size());
        for (String value : source) {
            copy.add(requireText(value, label));
        }
        return Collections.unmodifiableList(copy);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return value;
    }
}
