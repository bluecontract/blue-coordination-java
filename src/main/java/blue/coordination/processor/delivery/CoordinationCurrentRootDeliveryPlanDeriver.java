package blue.coordination.processor.delivery;

import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Whole-current-Root compatibility boundary backed by the public Contracts
 * compatibility deriver.
 *
 * <p>The host supplies the same revision, event order, and complete retained
 * active interval surface used by its indexed lane. Contracts owns all
 * evaluation and verification; this class only keeps the inputs immutable and
 * gives Coordination an explicitly named architecture choice.</p>
 */
public final class CoordinationCurrentRootDeliveryPlanDeriver
        implements ExternalDeliveryPlanDeriver {

    private final ExternalDeliveryPlanDeriver delegate;

    private CoordinationCurrentRootDeliveryPlanDeriver(
            BlueContracts contracts,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> completeActiveIntervals) {
        if (rootRevision < 0L) {
            throw new IllegalArgumentException(
                    "Root revision must be non-negative");
        }
        List<SubscriptionDelta.Entry> intervals =
                Collections.unmodifiableList(new ArrayList<>(
                        Objects.requireNonNull(
                                completeActiveIntervals,
                                "completeActiveIntervals")));
        for (SubscriptionDelta.Entry interval : intervals) {
            Objects.requireNonNull(
                    interval, "active subscription interval");
        }
        this.delegate = Objects.requireNonNull(contracts, "contracts")
                .currentRootDeliveryPlanDeriver(
                        rootRevision,
                        Objects.requireNonNull(
                                eventOrderKey, "eventOrderKey"),
                        intervals);
    }

    /**
     * Creates the explicit current-Root compatibility deriver through
     * {@link BlueContracts#currentRootDeliveryPlanDeriver(long,
     * ExternalOrderKey, List)}.
     */
    public static ExternalDeliveryPlanDeriver forContracts(
            BlueContracts contracts,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> completeActiveIntervals) {
        return new CoordinationCurrentRootDeliveryPlanDeriver(
                contracts,
                rootRevision,
                eventOrderKey,
                completeActiveIntervals);
    }

    @Override
    public ExternalDeliveryPlan derive(Node root, Node event) {
        return delegate.derive(
                Objects.requireNonNull(root, "root"),
                Objects.requireNonNull(event, "event"));
    }
}
