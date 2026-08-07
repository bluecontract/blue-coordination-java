package blue.coordination.processor;

import blue.coordination.engine.CoordinationProcessingEngine
        .AdmittedPlanningAuthority;
import blue.coordination.processor.delivery.CoordinationCurrentRootDeliveryPlanDeriver;
import blue.coordination.processor.delivery.CoordinationIndexedDeliveryEngine;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.SubscriptionDelta;
import blue.language.provider.NodeProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit host choices for supplying Coordination delivery evidence.
 *
 * <p>{@link CoordinationProcessors} installs only Coordination runtime
 * semantics. A host that intentionally accepts a whole-current-Root scan may
 * opt into that compatibility architecture here. Indexed hosts can instead
 * create a persistence-neutral subscription projection and supply their own
 * exact, revision-bound delivery evidence.</p>
 */
public final class CoordinationDeliveryPlanning {
    private CoordinationDeliveryPlanning() {
    }

    /**
     * Installs the deterministic whole-current-Root compatibility deriver.
     *
     * @param contracts configured Contracts service
     * @param rootRevision exact managed/indexed Root revision
     * @param eventOrderKey incoming event's exact total order
     * @param completeActiveIntervals complete retained active surface
     * @return deterministic whole-current-Root deriver
     */
    public static ExternalDeliveryPlanDeriver
    currentRootCompatibilityDeriver(
            BlueContracts contracts,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> completeActiveIntervals) {
        return CoordinationCurrentRootDeliveryPlanDeriver.forContracts(
                Objects.requireNonNull(contracts, "contracts"),
                rootRevision,
                Objects.requireNonNull(eventOrderKey, "eventOrderKey"),
                Objects.requireNonNull(
                        completeActiveIntervals,
                        "completeActiveIntervals"));
    }

    /**
     * Prepares the explicit whole-current-Root compatibility lane.
     *
     * <p>The public Contracts compatibility deriver remains authoritative for
     * the complete active surface. Coordination then asks the public indexed
     * evaluator for the same selected occurrences so that the returned value
     * carries the exact diagnostics, selected scope chains, seed closure,
     * prefetch set, and semantic-demand boundary used by the indexed lane. A
     * disagreement between the two public Language results fails closed.</p>
     *
     * @param processor processor whose Coordination registrations bind the
     *                  persisted snapshot
     * @param contracts configured public Contracts service
     * @param root exact current Root
     * @param event exact incoming event
     * @param activeSnapshot complete retained active subscription snapshot
     * @param exactProvider provider for the Root, event, and selected scope
     *                      closure
     * @param rootRevision exact managed/indexed Root revision
     * @param eventOrderKey incoming event's exact total order
     * @return immutable, complete compatibility preparation
     */
    public static CoordinationPreparedDelivery
    prepareCurrentRootCompatibility(
            DocumentProcessor processor,
            BlueContracts contracts,
            Node root,
            Node event,
            CoordinationSubscriptionSnapshot activeSnapshot,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey) {
        DocumentProcessor exactProcessor = Objects.requireNonNull(
                processor, "processor");
        BlueContracts exactContracts = Objects.requireNonNull(
                contracts, "contracts");
        Node exactRoot = Objects.requireNonNull(root, "root").clone();
        Node exactEvent = Objects.requireNonNull(event, "event").clone();
        CoordinationSubscriptionSnapshot snapshot = Objects.requireNonNull(
                activeSnapshot, "activeSnapshot");
        NodeProvider provider = Objects.requireNonNull(
                exactProvider, "exactProvider");
        ExternalOrderKey order = Objects.requireNonNull(
                eventOrderKey, "eventOrderKey");

        List<SubscriptionDelta.Entry> intervals = new ArrayList<>(
                snapshot.occurrences().size());
        Map<String, String> publicOccurrenceKeys = new LinkedHashMap<>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            intervals.add(occurrence.toSubscriptionDeltaEntry());
            String languageKey = CoordinationIndexedDeliveryEngine
                    .languageOccurrenceKey(
                            occurrence.scopePath(),
                            occurrence.channelKey());
            if (publicOccurrenceKeys.put(
                    languageKey, occurrence.occurrenceKey()) != null) {
                throw invalid(
                        "Active snapshot contains duplicate Language "
                                + "occurrences at " + languageKey);
            }
        }

        ExternalDeliveryPlan compatibilityPlan =
                currentRootCompatibilityDeriver(
                        exactContracts,
                        rootRevision,
                        order,
                        intervals)
                        .derive(exactRoot, exactEvent);
        List<String> selectedPublicKeys = selectedPublicKeys(
                compatibilityPlan, publicOccurrenceKeys);
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(exactRoot);
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(exactEvent);
        CoordinationPreparedDelivery indexed = indexed(
                exactProcessor, exactContracts)
                .prepare(
                        rootBlueId,
                        eventBlueId,
                        snapshot,
                        selectedPublicKeys,
                        provider,
                        rootRevision,
                        order);
        requireEquivalentPlans(
                compatibilityPlan, indexed.deliveryPlan());

        return new CoordinationPreparedDelivery(
                rootBlueId,
                eventBlueId,
                indexed.evidence(),
                compatibilityPlan,
                indexed.deliveryPlanIdentity(),
                indexed.subscriptionSnapshotIdentity(),
                indexed.preselectedOccurrenceOrder(),
                indexed.sourceDeliveries(),
                indexed.selectedScopeChainIdentities(),
                indexed.requiredSeedFragmentIdentities(),
                indexed.prefetchIdentities(),
                indexed.demandBoundary());
    }

    /** Creates a projector that delegates semantic projection to Contracts. */
    public static CoordinationSubscriptionProjector subscriptionProjector(
            DocumentProcessor processor,
            BlueContracts contracts) {
        return new CoordinationSubscriptionProjector(
                Objects.requireNonNull(processor, "processor"),
                Objects.requireNonNull(contracts, "contracts"));
    }

    /**
     * Creates an indexed planner whose semantic evaluation goes through the
     * public Contracts service while retaining the processor only for the
     * Coordination registration identity captured by persisted snapshots.
     */
    public static CoordinationIndexedDeliveryPlanner indexed(
            DocumentProcessor processor,
            BlueContracts contracts) {
        return new CoordinationIndexedDeliveryPlanner(
                Objects.requireNonNull(processor, "processor"),
                Objects.requireNonNull(contracts, "contracts"));
    }

    /**
     * Creates an indexed planner with an engine-owned admitted-value
     * capability. The capability is compared by identity and is never
     * exposed by the returned planner. It lets the storage-neutral engine
     * reuse exact values that its admission boundary has already verified,
     * while the ordinary public planner continues to copy and hash untrusted
     * provider results.
     */
    public static CoordinationIndexedDeliveryPlanner indexed(
            DocumentProcessor processor,
            BlueContracts contracts,
            AdmittedPlanningAuthority admittedPlanningAuthority) {
        return new CoordinationIndexedDeliveryPlanner(
                Objects.requireNonNull(processor, "processor"),
                Objects.requireNonNull(contracts, "contracts"),
                Objects.requireNonNull(
                        admittedPlanningAuthority,
                        "admittedPlanningAuthority"));
    }

    private static List<String> selectedPublicKeys(
            ExternalDeliveryPlan plan,
            Map<String, String> publicOccurrenceKeys) {
        List<String> result = new ArrayList<>(
                plan.deliveries().size());
        for (ExternalDeliverySnapshot delivery : plan.deliveries()) {
            String languageKey = CoordinationIndexedDeliveryEngine
                    .languageOccurrenceKey(
                            delivery.scopePath(),
                            delivery.channelKey());
            String publicKey = publicOccurrenceKeys.get(languageKey);
            if (publicKey == null) {
                throw invalid(
                        "Compatibility planning selected an occurrence absent "
                                + "from the active snapshot at " + languageKey);
            }
            result.add(publicKey);
        }
        return result;
    }

    private static void requireEquivalentPlans(
            ExternalDeliveryPlan compatibility,
            ExternalDeliveryPlan indexed) {
        if (compatibility.managedRootRevision()
                != indexed.managedRootRevision()
                || compatibility.indexedRootRevision()
                != indexed.indexedRootRevision()
                || !compatibility.eventOrderKey().equals(
                indexed.eventOrderKey())
                || compatibility.hasActiveSubscriptionIntervals()
                != indexed.hasActiveSubscriptionIntervals()
                || !compatibility.activeSubscriptionIntervals().equals(
                indexed.activeSubscriptionIntervals())
                || !compatibility.availableExactNodeBlueIds().equals(
                indexed.availableExactNodeBlueIds())
                || !compatibility.requiredExactNodeBlueIds().equals(
                indexed.requiredExactNodeBlueIds())
                || compatibility.exactRuntimeState()
                != indexed.exactRuntimeState()
                || !sameDeliveries(
                compatibility.deliveries(), indexed.deliveries())) {
            throw invalid(
                    "Current-Root compatibility and indexed planning "
                            + "produced different canonical delivery evidence");
        }
    }

    private static boolean sameDeliveries(
            List<ExternalDeliverySnapshot> left,
            List<ExternalDeliverySnapshot> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            ExternalDeliverySnapshot first = left.get(index);
            ExternalDeliverySnapshot second = right.get(index);
            if (!first.scopePath().equals(second.scopePath())
                    || !first.channelKey().equals(second.channelKey())
                    || first.order() != second.order()
                    || !first.sourceContributionNodeBlueIds().equals(
                    second.sourceContributionNodeBlueIds())
                    || !first.effectiveTypeBlueId().equals(
                    second.effectiveTypeBlueId())
                    || !first.subscriptionKeys().equals(
                    second.subscriptionKeys())
                    || !first.checkpointDomainBlueId().equals(
                    second.checkpointDomainBlueId())
                    || !first.checkpointSubjectBlueId().equals(
                    second.checkpointSubjectBlueId())
                    || !Objects.equals(
                    first.activationStartExclusive(),
                    second.activationStartExclusive())
                    || !Objects.equals(
                    first.activationEndInclusive(),
                    second.activationEndInclusive())) {
                return false;
            }
        }
        return true;
    }

    private static InvalidExecutionEvidenceException invalid(
            String message) {
        return new InvalidExecutionEvidenceException(message);
    }
}
