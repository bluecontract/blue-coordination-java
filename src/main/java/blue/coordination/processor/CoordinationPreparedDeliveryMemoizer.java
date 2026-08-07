package blue.coordination.processor;

import blue.coordination.engine.CoordinationProcessingEngine
        .AdmittedPlanningAuthority;
import blue.coordination.fastpath.AdmittedProjection;
import blue.coordination.fastpath.PlanCacheKey;
import blue.coordination.fastpath.PlanningFastPath;
import blue.coordination.fastpath.ProjectionGenerationKey;
import blue.language.processor.ExternalOrderKey;
import blue.language.provider.NodeProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Exact retry/duplicate-delivery fast path around the authoritative indexed
 * planner. A miss still calls Contracts and therefore cannot weaken semantic
 * selection. A hit returns only a previously complete immutable preparation
 * for the same Root generation, event identity/order and ordered candidates.
 */
public final class CoordinationPreparedDeliveryMemoizer {
    private static final String UNTRUSTED_POLICY =
            "blue.coordination/indexed-planning/current-contracts/1.0";
    private static final String ADMITTED_POLICY =
            "blue.coordination/indexed-planning/admitted-contracts/1.0";
    private final CoordinationIndexedDeliveryPlanner planner;
    private final AdmittedPlanningAuthority admittedPlanningAuthority;
    private final PlanningFastPath<CoordinationPreparedDelivery> cache;

    public CoordinationPreparedDeliveryMemoizer(
            CoordinationIndexedDeliveryPlanner planner,
            int maximumEntries,
            long maximumEstimatedBytes) {
        this(planner, null, maximumEntries, maximumEstimatedBytes);
    }

    public CoordinationPreparedDeliveryMemoizer(
            CoordinationIndexedDeliveryPlanner planner,
            AdmittedPlanningAuthority admittedPlanningAuthority,
            int maximumEntries,
            long maximumEstimatedBytes) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.admittedPlanningAuthority = admittedPlanningAuthority;
        this.cache = new PlanningFastPath<CoordinationPreparedDelivery>(
                maximumEntries,
                maximumEstimatedBytes,
                CoordinationPreparedDeliveryMemoizer::estimatedWeight);
    }

    public CoordinationPreparedDelivery prepare(
            ProjectionGenerationKey generation,
            AdmittedProjection admittedProjection,
            String eventBlueId,
            String eventInventoryIdentity,
            ExternalOrderKey eventOrder,
            CoordinationSubscriptionSnapshot snapshot,
            List<String> orderedOccurrenceKeys,
            NodeProvider exactProvider) {
        ProjectionGenerationKey exactGeneration = Objects.requireNonNull(
                generation, "generation");
        List<String> exactOccurrenceKeys = immutableOccurrenceKeys(
                orderedOccurrenceKeys);
        PlanCacheKey key = new PlanCacheKey(
                exactGeneration,
                eventBlueId,
                eventInventoryIdentity,
                eventOrder,
                exactOccurrenceKeys,
                UNTRUSTED_POLICY);
        return cache.prepare(key, admittedProjection, selected -> {
            if (!selected.publicKeys().equals(exactOccurrenceKeys)) {
                throw new IllegalStateException(
                        "admitted projection changed candidate order");
            }
            return planner.prepare(
                    exactGeneration.rootBlueId(),
                    eventBlueId,
                    snapshot,
                    exactOccurrenceKeys,
                    exactProvider,
                    exactGeneration.rootRevision(),
                    eventOrder);
        });
    }

    /**
     * Exact admitted path used by the Coordination engine. A cache miss still
     * invokes the frozen semantic planner once; a hit returns only that
     * complete immutable result for the exact event and generation key.
     */
    public CoordinationPreparedDelivery prepareAdmitted(
            ProjectionGenerationKey generation,
            AdmittedProjection admittedProjection,
            String eventBlueId,
            String eventInventoryIdentity,
            ExternalOrderKey eventOrder,
            blue.language.model.Node exactRoot,
            blue.language.model.Node exactEvent,
            CoordinationSubscriptionSnapshot snapshot,
            List<String> orderedOccurrenceKeys,
            NodeProvider exactProvider) {
        if (admittedPlanningAuthority == null) {
            throw new IllegalStateException(
                    "admitted planning authority is unavailable");
        }
        ProjectionGenerationKey exactGeneration = Objects.requireNonNull(
                generation, "generation");
        List<String> exactOccurrenceKeys = immutableOccurrenceKeys(
                orderedOccurrenceKeys);
        PlanCacheKey key = new PlanCacheKey(
                exactGeneration,
                eventBlueId,
                eventInventoryIdentity,
                eventOrder,
                exactOccurrenceKeys,
                ADMITTED_POLICY);
        return cache.prepare(key, admittedProjection, selected -> {
            if (!selected.publicKeys().equals(exactOccurrenceKeys)) {
                throw new IllegalStateException(
                        "admitted projection changed candidate order");
            }
            return planner.prepareProjectedAdmitted(
                    admittedPlanningAuthority,
                    exactGeneration.rootBlueId(),
                    Objects.requireNonNull(exactRoot, "exactRoot"),
                    eventBlueId,
                    Objects.requireNonNull(exactEvent, "exactEvent"),
                    snapshot,
                    exactOccurrenceKeys,
                    exactProvider,
                    exactGeneration.rootRevision(),
                    eventOrder,
                    selected);
        });
    }

    public int generationCommitted(ProjectionGenerationKey previous) {
        return cache.generationCommitted(previous);
    }

    public blue.coordination.fastpath.CacheMetrics metrics() {
        return cache.metrics();
    }

    private static List<String> immutableOccurrenceKeys(
            List<String> orderedOccurrenceKeys) {
        return Collections.unmodifiableList(new ArrayList<String>(
                Objects.requireNonNull(
                        orderedOccurrenceKeys,
                        "orderedOccurrenceKeys")));
    }

    private static long estimatedWeight(CoordinationPreparedDelivery value) {
        long count = 256L;
        count += value.preselectedOccurrenceOrder().size() * 96L;
        count += value.sourceDeliveries().size() * 512L;
        count += value.requiredSeedFragmentIdentities().size() * 96L;
        count += value.prefetchIdentities().size() * 96L;
        for (List<String> chain : value.selectedScopeChainIdentities().values()) {
            count += chain.size() * 96L;
        }
        return Math.max(1L, count);
    }
}
