package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.processor.BlueContracts;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.IndexedDeliveryPreparation;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.ProcessorRuntimeAccess;
import blue.language.processor.SubscriptionDelta;
import blue.language.provider.NodeProvider;
import blue.language.snapshot.FrozenNode;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Managed-host access to one immutable Coordination Contracts generation.
 *
 * <p>This façade deliberately delegates to the public {@link BlueContracts}
 * services. It owns no processor internals, snapshot manager, registry,
 * matcher, or cache, and it does not close the caller-owned Contracts
 * service.</p>
 */
public final class CoordinationContractsHost {

    private final BlueContracts contracts;

    /** Creates a host façade borrowing one open Contracts generation. */
    public CoordinationContractsHost(BlueContracts contracts) {
        this.contracts = Objects.requireNonNull(contracts, "contracts");
    }

    /** Returns lifecycle-bound immutable access to the exact runtime. */
    public ProcessorRuntimeAccess runtimeAccess() {
        return contracts.runtimeAccess();
    }

    /** Materializes and verifies one exact value or pure reference. */
    public BlueOperationResult<FrozenNode> materializeVerifiedExactReference(
            Node exactReference) {
        return contracts.runtimeAccess().materializeVerifiedExactReference(
                FrozenNode.fromNode(Objects.requireNonNull(
                        exactReference, "exactReference")));
    }

    /** Inspects the exact generic fragmentation catalog for one Root. */
    public EffectiveFragmentationCatalog effectiveFragmentationCatalog(
            Node exactRoot) {
        return contracts.effectiveFragmentationCatalog(
                Objects.requireNonNull(exactRoot, "exactRoot"));
    }

    /** Projects the complete subscription surface for a newly admitted Root. */
    public SubscriptionDelta projectInitialSubscriptions(
            Node exactRoot,
            long resultingRootRevision,
            ExternalOrderKey activationOrderKey) {
        return contracts.subscriptionSurfaceProjection().projectInitial(
                Objects.requireNonNull(exactRoot, "exactRoot"),
                resultingRootRevision,
                Objects.requireNonNull(
                        activationOrderKey, "activationOrderKey"));
    }

    /** Projects additions and retirements from the retained active surface. */
    public SubscriptionDelta projectSubscriptionUpdate(
            Node resultingExactRoot,
            List<SubscriptionDelta.Entry> priorActiveIntervals,
            Set<String> changedRuntimePointers,
            long resultingRootRevision,
            ExternalOrderKey transitionOrderKey) {
        return contracts.subscriptionSurfaceProjection().projectUpdate(
                Objects.requireNonNull(
                        resultingExactRoot, "resultingExactRoot"),
                Objects.requireNonNull(
                        priorActiveIntervals, "priorActiveIntervals"),
                Objects.requireNonNull(
                        changedRuntimePointers, "changedRuntimePointers"),
                resultingRootRevision,
                Objects.requireNonNull(
                        transitionOrderKey, "transitionOrderKey"));
    }

    /** Evaluates and independently verifies one indexed candidate surface. */
    public IndexedDeliveryPreparation prepareIndexedDelivery(
            Node exactRoot,
            Node exactEvent,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> completeActiveIntervals,
            List<ExternalSubscriptionOccurrenceKey> orderedCandidates) {
        Node root = materializePureReference(
                Objects.requireNonNull(exactRoot, "exactRoot"),
                "Root");
        Node event = materializePureReference(
                Objects.requireNonNull(exactEvent, "exactEvent"),
                "event");
        return contracts.indexedDeliveryEvaluator().prepare(
                root,
                event,
                rootRevision,
                Objects.requireNonNull(eventOrderKey, "eventOrderKey"),
                Objects.requireNonNull(
                        completeActiveIntervals,
                        "completeActiveIntervals"),
                Objects.requireNonNull(
                        orderedCandidates, "orderedCandidates"));
    }

    private Node materializePureReference(
            Node input,
            String label) {
        if (!input.isReferenceOnly()) {
            return input;
        }
        BlueOperationResult<FrozenNode> result =
                materializeVerifiedExactReference(input);
        BlueOperationOutcome outcome = result.outcome();
        if (outcome == BlueOperationOutcome.ESTABLISHED) {
            return result.requireEstablished().toNode();
        }
        String reason = result.reason().orElse(
                "Exact " + label + " reference could not be established");
        if (outcome == BlueOperationOutcome.INCOMPLETE) {
            throw new ExecutionEvidenceUnavailableException(
                    reason,
                    result.outstandingBlueIds());
        }
        if (outcome == BlueOperationOutcome.ABSENT) {
            throw new InvalidExecutionEvidenceException(
                    "Exact " + label + " reference is absent: "
                            + input.getBlueId());
        }
        throw new InvalidExecutionEvidenceException(reason);
    }

    /**
     * Carries one evaluator-bound indexed plan and its exact request provider
     * into the public platform-commit boundary.
     *
     * <p>The plan retains the registry-generation binding established by the
     * public indexed evaluator. Coordination neither reconstructs nor exposes
     * that evidence.</p>
     */
    public PlatformProcessInvocation preparePlatformCommitInvocation(
            IndexedDeliveryPreparation indexed,
            NodeProvider exactRequestProvider) {
        return preparePlatformCommitInvocation(
                Objects.requireNonNull(
                        indexed, "indexed").deliveryPlan(),
                exactRequestProvider);
    }

    /**
     * Carries any evaluator-bound public plan and its exact request provider
     * into the public platform-commit boundary.
     */
    public PlatformProcessInvocation preparePlatformCommitInvocation(
            ExternalDeliveryPlan plan,
            NodeProvider exactRequestProvider) {
        return PlatformProcessInvocation.builder()
                .deliveryPlan(Objects.requireNonNull(plan, "plan"))
                .nodeProvider(Objects.requireNonNull(
                        exactRequestProvider, "exactRequestProvider"))
                .build();
    }

    /** Creates the explicit whole-current-Root compatibility deriver. */
    public ExternalDeliveryPlanDeriver currentRootDeliveryPlanDeriver(
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> completeActiveIntervals) {
        return contracts.currentRootDeliveryPlanDeriver(
                rootRevision,
                Objects.requireNonNull(eventOrderKey, "eventOrderKey"),
                Objects.requireNonNull(
                        completeActiveIntervals,
                        "completeActiveIntervals"));
    }

    /** Prepares semantic output and its companion for one atomic host commit. */
    public PlatformProcessingResult processForPlatformCommit(
            Node exactRoot,
            Node exactEvent,
            PlatformProcessInvocation invocation) {
        return contracts.processForPlatformCommit(
                Objects.requireNonNull(exactRoot, "exactRoot"),
                Objects.requireNonNull(exactEvent, "exactEvent"),
                Objects.requireNonNull(invocation, "invocation"));
    }
}
