package blue.coordination.processor;

import blue.language.Blue;
import blue.language.processor.CoordinationCurrentRootDeliveryPlanDeriver;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalDeliveryPlanDeriver;

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
     * @param processor configured Coordination processor
     * @return the supplied processor
     */
    public static DocumentProcessor currentRootCompatibility(
            DocumentProcessor processor) {
        DocumentProcessor exact =
                Objects.requireNonNull(processor, "processor");
        return exact.externalDeliveryPlanDeriver(
                currentRootCompatibilityDeriver(exact));
    }

    /**
     * Installs the deterministic whole-current-Root compatibility deriver.
     *
     * @param blue configured Coordination Language façade
     * @return the supplied façade
     */
    public static Blue currentRootCompatibility(Blue blue) {
        Blue exact = Objects.requireNonNull(blue, "blue");
        currentRootCompatibility(exact.getDocumentProcessor());
        return exact;
    }

    /**
     * Creates, without installing, the explicitly named compatibility
     * deriver.
     *
     * @param processor configured Coordination processor
     * @return deterministic whole-current-Root deriver
     */
    public static ExternalDeliveryPlanDeriver
    currentRootCompatibilityDeriver(DocumentProcessor processor) {
        return CoordinationCurrentRootDeliveryPlanDeriver.forProcessor(
                Objects.requireNonNull(processor, "processor"));
    }

    /**
     * Creates a deterministic, persistence-neutral subscription projector.
     *
     * @param processor configured Coordination processor
     * @return a projector bound to that processor's exact runtime semantics
     */
    public static CoordinationSubscriptionProjector subscriptionProjector(
            DocumentProcessor processor) {
        return new CoordinationSubscriptionProjector(
                Objects.requireNonNull(processor, "processor"));
    }

    /**
     * Creates an exact indexed delivery planner without installing a
     * whole-Root plan deriver.
     *
     * @param processor configured Coordination processor
     * @return persistence-neutral indexed planning façade
     */
    public static CoordinationIndexedDeliveryPlanner indexed(
            DocumentProcessor processor) {
        return new CoordinationIndexedDeliveryPlanner(
                Objects.requireNonNull(processor, "processor"));
    }
}
