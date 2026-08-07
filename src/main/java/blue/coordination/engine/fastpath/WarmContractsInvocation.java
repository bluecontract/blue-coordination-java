package blue.coordination.engine.fastpath;

import blue.coordination.processor.CoordinationContractsHost;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.PlatformProcessingResult;
import blue.language.provider.NodeProvider;

import java.util.Objects;

/**
 * Coordination-side optimized call into the frozen Contracts generation.
 * One exact Root snapshot is supplied inline from the prepared epoch context rather
 * than as a pure reference that the frozen runtime must reconstruct through
 * hundreds of provider lookups. Semantic delivery evidence and the exact
 * request-local provider remain unchanged.
 */
public final class WarmContractsInvocation {
    private final CoordinationContractsHost contracts;

    public WarmContractsInvocation(CoordinationContractsHost contracts) {
        this.contracts = Objects.requireNonNull(contracts, "contracts");
    }

    public PlatformProcessingResult process(
            PreparedRootExecutionContext context,
            ExactNodeHandle exactEvent,
            ExternalDeliveryPlan deliveryPlan,
            NodeProvider exactRequestProvider) {
        PreparedRootExecutionContext prepared = Objects.requireNonNull(
                context, "context");
        PlatformProcessInvocation invocation =
                contracts.preparePlatformCommitInvocation(
                        Objects.requireNonNull(
                                deliveryPlan, "deliveryPlan"),
                        Objects.requireNonNull(
                                exactRequestProvider,
                                "exactRequestProvider"));
        return contracts.processForPlatformCommit(
                prepared.copyRootForPublicInvocation(),
                Objects.requireNonNull(exactEvent, "exactEvent")
                        .copy(),
                invocation);
    }
}
