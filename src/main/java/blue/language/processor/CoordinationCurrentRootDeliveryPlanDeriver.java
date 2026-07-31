package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.processor.util.PointerUtils;
import blue.repo.coordination.TerminateProcessing;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Whole-Root compatibility deriver for Coordination runtimes.
 *
 * <p>This is the deterministic baseline for hosts that still process one
 * already materialized current Root. It evaluates the complete effective
 * External Channel surface before PROCESS, supplies every active occurrence,
 * and lets Language independently verify the resulting evidence against the
 * exact Root and event. A channel created by the event is absent from the
 * pre-event Root and therefore cannot receive its creating event.</p>
 *
 * <p>All occurrences present in the current Root are treated as active since
 * Root revision zero with an unbounded order frontier. Fragment-native hosts
 * with historical catch-up must replace this deriver with their persisted
 * revision-bound subscription index; they must not use current Root presence
 * to infer a historical activation frontier.</p>
 */
public final class CoordinationCurrentRootDeliveryPlanDeriver
        implements ExternalDeliveryPlanDeriver {

    private final DocumentProcessor processor;

    /**
     * Creates the whole-current-Root compatibility deriver behind its
     * Language interface.
     *
     * @param processor live processor whose registry and verified snapshot
     *                  manager define the effective contract surface
     * @return compatibility deriver without exposing concrete construction
     */
    public static ExternalDeliveryPlanDeriver forProcessor(
            DocumentProcessor processor) {
        return new CoordinationCurrentRootDeliveryPlanDeriver(
                processor);
    }

    /**
     * Creates a deriver bound to the configured Coordination processor.
     *
     * @param processor live processor whose registry and verified snapshot
     *                  manager define the effective contract surface
     */
    CoordinationCurrentRootDeliveryPlanDeriver(
            DocumentProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    @Override
    public ExternalDeliveryPlan derive(Node root, Node event) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(event, "event");
        /*
         * Blue's public PROCESS boundary may supply an already completed
         * current Root. Resolved nominal definitions carry their published
         * BlueId together with provider fields as provenance, which is legal
         * in the resolved lane but not legal authored input to a second
         * snapshot pass. Normalize both semantic inputs back to canonical
         * exact shape before compatibility planning.
         */
        Node exactRoot =
                CoordinationProcessHeaderBridge
                        .canonicalExactCopy(root);
        Node exactEvent =
                CoordinationProcessHeaderBridge
                        .canonicalExactCopy(event);
        ProcessingSnapshotManager snapshotManager =
                Objects.requireNonNull(
                        processor.snapshotManager(),
                        "processor snapshotManager");
        /*
         * Routing opens contract headers, not Handler bodies. In particular,
         * a Sequential Workflow's steps may contain exact references that are
         * intentionally unavailable until that Handler has matched. Reusing
         * Language's canonical deferred-body boundary keeps those references
         * lazy and also prevents a completed nominal type inside an append-only
         * body from being submitted as authored input to a second resolver.
         */
        ResolvedSnapshot snapshot =
                DocumentProcessingRuntime.resolveCanonicalTransient(
                        snapshotManager,
                        FrozenNode.fromNode(exactRoot),
                        Collections.singleton(JsonPointer.ROOT),
                        processor.registry()
                                .executableBodyFieldsByType());
        List<SubscriptionDelta.Entry> activeSurface =
                activeSurface(
                        exactRoot,
                        snapshot,
                        snapshotManager);
        Map<String, SubscriptionDelta.Entry> remainingSurface =
                new LinkedHashMap<>();
        for (SubscriptionDelta.Entry entry : activeSurface) {
            remainingSurface.put(entry.occurrenceKey(), entry);
        }

        List<Candidate> candidates = new ArrayList<>();
        Deque<String> pendingScopes = new ArrayDeque<>();
        Set<String> visitedScopes = new LinkedHashSet<>();
        pendingScopes.add(JsonPointer.ROOT);
        while (!pendingScopes.isEmpty()) {
            String scopePath = pendingScopes.removeFirst();
            if (!visitedScopes.add(scopePath)) {
                throw new InvalidExecutionEvidenceException(
                        "Repeated Process Embedded scope " + scopePath);
            }
            Node selectedScope =
                    snapshot.canonicalNodeAt(scopePath);
            if (directTerminated(selectedScope, scopePath)) {
                /*
                 * Language's canonical subscription surface excludes a
                 * directly terminated scope and every embedded branch below
                 * it. Keep the compatibility traversal on that same active
                 * surface; loading its historical contracts would invent
                 * candidates that the independently verified surface has
                 * correctly retired.
                 */
                continue;
            }
            ContractBundle bundle =
                    processor.contractLoader().load(snapshot, scopePath);
            List<String> effectiveKeys = new ArrayList<>();
            for (EffectiveContractSnapshot contract
                    : bundle.effectiveContractSnapshots()) {
                effectiveKeys.add(contract.key());
            }
            for (EffectiveContractSnapshot contract
                    : bundle.effectiveContractSnapshots()) {
                if (!EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                        .equals(contract.role())) {
                    continue;
                }
                ExternalChannelFunctionEvaluation evaluation =
                        ExternalChannelFunctionEvaluation.evaluate(
                                processor.registry(),
                                processor.contractConverter(),
                                ExternalChannelFunctionEvaluation
                                        .verifiedMatcherSessions(
                                                snapshotManager),
                                bundle,
                                contract,
                                exactEvent,
                                effectiveKeys);
                if (evaluation.accepts() && !evaluation.preselects()) {
                    throw new InvalidExecutionEvidenceException(
                            "External subscription law violated "
                                    + "(ACCEPTS => PRESELECTS) at "
                                    + scopePath + "/" + contract.key());
                }
                SubscriptionDelta.Entry descriptor =
                        remainingSurface.remove(
                                occurrenceKey(
                                        contract.scopePath(),
                                        contract.key()));
                if (descriptor == null
                        || !descriptor.sameSubscriptionSnapshot(
                        activeInterval(contract, evaluation))) {
                    throw new InvalidExecutionEvidenceException(
                            "Canonical active subscription surface disagrees "
                                    + "with event evaluation at "
                                    + scopePath + "/" + contract.key());
                }
                candidates.add(new Candidate(
                        bundle,
                        contract,
                        evaluation));
            }
            for (String embedded : bundle.embeddedPaths()) {
                String child =
                        PointerUtils.resolvePointer(scopePath, embedded);
                if (visitedScopes.contains(child)
                        || pendingScopes.contains(child)) {
                    throw new InvalidExecutionEvidenceException(
                            "Repeated Process Embedded scope " + child);
                }
                pendingScopes.addLast(child);
            }
        }
        if (!remainingSurface.isEmpty()) {
            throw new InvalidExecutionEvidenceException(
                    "Canonical active subscription surface contains "
                            + "unreachable occurrences: "
                            + remainingSurface.keySet());
        }

        Collections.sort(candidates, Candidate.CANONICAL_ORDER);
        ExternalDeliveryPlan.Builder plan =
                ExternalDeliveryPlan.builder()
                        .revisions(0L, 0L)
                        .eventOrderKey(
                                eventOrder(exactEvent))
                        .exactRuntimeState();
        for (SubscriptionDelta.Entry entry : activeSurface) {
            plan.activeSubscriptionInterval(
                    activeInterval(entry));
        }
        for (Candidate candidate : candidates) {
            if (candidate.evaluation.preselects()) {
                if (candidate.evaluation.accepts()) {
                    preflightSelectedHandlers(
                            candidate,
                            snapshotManager);
                }
                plan.delivery(delivery(
                        candidate.contract,
                        candidate.evaluation));
            }
        }
        return plan.build();
    }

    private void preflightSelectedHandlers(
            Candidate candidate,
            ProcessingSnapshotManager snapshotManager) {
        String channelKey =
                candidate.evaluation
                        .handlerChannelKey();
        FrozenNode payload =
                candidate.evaluation.payload();
        if (channelKey == null || payload == null) {
            return;
        }
        for (ContractBundle.HandlerBinding handler
                : candidate.bundle.handlersFor(channelKey)) {
            /*
             * Event-pattern matching is runtime work and cannot be replayed
             * safely while the compatibility planner is deriving evidence.
             * Event-bound bodies remain lazy and are validated after their
             * real match. Only unconditional handlers are selected here.
             */
            if (handler.contract().getEvent() != null) {
                continue;
            }
            try {
                ContractBundle.HandlerBinding selected =
                        processor.contractLoader()
                        .materializeSelectedExecutableBodies(
                                handler,
                                snapshotManager
                                        ::materializeVerifiedReference);
                validateDeclarativeTermination(
                        selected);
            } catch (ProcessorFailureException failure) {
                throw new InvalidExecutionEvidenceException(
                        ProcessorEngine.deterministicMessage(
                                failure,
                                "Selected handler body is invalid"),
                        failure.errorCategory());
            }
        }
    }

    private static void validateDeclarativeTermination(
            ContractBundle.HandlerBinding handler) {
        FrozenNode contract =
                handler != null ? handler.node() : null;
        FrozenNode steps =
                contract != null
                        ? contract.property("steps")
                        : null;
        if (steps == null || steps.getItems() == null) {
            return;
        }
        for (FrozenNode step : steps.getItems()) {
            FrozenNode type =
                    step != null ? step.getType() : null;
            String typeBlueId =
                    type != null
                            ? type.getReferenceBlueId()
                            : null;
            if (typeBlueId == null && type != null) {
                typeBlueId = type.blueId();
            }
            if (!TerminateProcessing.blueId()
                    .equals(typeBlueId)) {
                continue;
            }
            FrozenNode reason =
                    step.property("reason");
            if (reason != null
                    && !(reason.getValue()
                    instanceof String)) {
                throw new InvalidExecutionEvidenceException(
                        "Terminate Processing reason must be Text",
                        ProcessorErrorCategory
                                .InvalidProcessingDocument);
            }
        }
    }

    private List<SubscriptionDelta.Entry> activeSurface(
            Node root,
            ResolvedSnapshot snapshot,
            ProcessingSnapshotManager snapshotManager) {
        Node emptyRoot = new Node();
        SubscriptionSurfaceValidationContext context =
                SubscriptionSurfaceValidationContext
                        .builder(
                                emptyRoot,
                                root,
                                Collections.singleton(
                                        JsonPointer.ROOT),
                                processor.gasSchedule())
                        .snapshots(
                                snapshotManager
                                        .fromDocumentTransient(
                                                emptyRoot),
                                snapshot)
                        .build();
        SubscriptionDelta delta =
                processor.subscriptionSurfaceValidator()
                        .validate(context);
        if (!delta.removed().isEmpty()) {
            throw new InvalidExecutionEvidenceException(
                    "Current-Root subscription bootstrap unexpectedly "
                            + "retired occurrences");
        }
        return delta.added();
    }

    private static boolean directTerminated(
            Node scope,
            String scopePath) {
        Node contracts =
                scope != null ? scope.getContracts() : null;
        Node marker =
                contracts != null
                        && contracts.getProperties() != null
                        ? contracts.getProperties().get(
                        ProcessorContractConstants.KEY_TERMINATED)
                        : null;
        if (marker == null) {
            return false;
        }
        ProcessorEngine.validateTerminationMarker(
                marker,
                PointerUtils.resolvePointer(
                        scopePath,
                        ProcessorPointerConstants.RELATIVE_TERMINATED));
        return true;
    }

    private static ExternalOrderKey eventOrder(Node event) {
        List<Object> components = new ArrayList<>();
        Node timestamp = property(event, "timestamp");
        Object value = timestamp == null ? null : timestamp.getValue();
        if (value instanceof BigInteger) {
            components.add(value);
        } else if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            components.add(BigInteger.valueOf(((Number) value).longValue()));
        }
        Node timeline = property(event, "timeline");
        if (timeline != null) {
            components.add(BlueIdCalculator.calculateBlueId(timeline));
        }
        components.add(BlueIdCalculator.calculateBlueId(event));
        return ExternalOrderKey.of(components);
    }

    private static Node property(Node node, String key) {
        return node.getProperties() == null
                ? null
                : node.getProperties().get(key);
    }

    private static ExternalDeliverySnapshot delivery(
            EffectiveContractSnapshot snapshot,
            ExternalChannelFunctionEvaluation evaluation) {
        String checkpointSubjectBlueId =
                evaluation.checkpointSubjectBlueId();
        if (checkpointSubjectBlueId == null) {
            /*
             * PRESELECTS is intentionally allowed to over-approximate
             * ACCEPTS. Language ignores the checkpoint subject for a
             * rejected candidate, but the immutable evidence shape still
             * requires one stable exact identity. Keep compatibility
             * planning identical to the indexed planner by using the
             * immutable checkpoint domain as that inert fallback.
             */
            checkpointSubjectBlueId =
                    evaluation.checkpointDomainBlueId();
        }
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                snapshot.scopePath(),
                                snapshot.key())
                        .effectiveTypeBlueId(
                                snapshot.effectiveTypeBlueId())
                        .order(snapshot.order())
                        .checkpointDomainBlueId(
                                evaluation.checkpointDomainBlueId())
                        .checkpointSubjectBlueId(
                                checkpointSubjectBlueId);
        for (String contribution
                : snapshot.sourceContributionNodeBlueIds()) {
            builder.sourceContribution(contribution);
        }
        for (String subscriptionKey
                : evaluation.channelKeys()) {
            builder.subscriptionKey(subscriptionKey);
        }
        return builder.build();
    }

    private static SubscriptionDelta.Entry activeInterval(
            EffectiveContractSnapshot snapshot,
            ExternalChannelFunctionEvaluation evaluation) {
        return new SubscriptionDelta.Entry(
                snapshot.scopePath(),
                snapshot.key(),
                snapshot.effectiveTypeBlueId(),
                snapshot.sourceContributionNodeBlueIds(),
                snapshot.order(),
                evaluation.channelKeys(),
                evaluation.checkpointDomainBlueId(),
                evaluation.dependencies(),
                0L,
                null,
                null);
    }

    private static SubscriptionDelta.Entry activeInterval(
            SubscriptionDelta.Entry entry) {
        return new SubscriptionDelta.Entry(
                entry.scopePath(),
                entry.channelKey(),
                entry.effectiveTypeBlueId(),
                entry.sourceContributionNodeBlueIds(),
                entry.order(),
                entry.subscriptionKeys(),
                entry.checkpointDomainBlueId(),
                entry.dependencies(),
                0L,
                null,
                null);
    }

    private static String occurrenceKey(
            String scopePath,
            String channelKey) {
        return PointerUtils.normalizeScope(scopePath)
                + ProcessorIdentityConstants
                        .SELECTOR_COMPONENT_DELIMITER
                + channelKey;
    }

    private static int depth(String scopePath) {
        return JsonPointer.split(scopePath).size();
    }

    private static final class Candidate {
        private static final Comparator<Candidate> CANONICAL_ORDER =
                new Comparator<Candidate>() {
                    @Override
                    public int compare(Candidate left, Candidate right) {
                        int compared = Integer.compare(
                                depth(right.contract.scopePath()),
                                depth(left.contract.scopePath()));
                        if (compared != 0) {
                            return compared;
                        }
                        compared = ExternalOrderKey.compareTextCodePoints(
                                left.contract.scopePath(),
                                right.contract.scopePath());
                        if (compared != 0) {
                            return compared;
                        }
                        compared = Integer.compare(
                                left.contract.order(),
                                right.contract.order());
                        if (compared != 0) {
                            return compared;
                        }
                        compared = ExternalOrderKey.compareTextCodePoints(
                                left.contract.key(),
                                right.contract.key());
                        return compared != 0
                                ? compared
                                : ExternalOrderKey.compareTextCodePoints(
                                        left.contract.effectiveTypeBlueId(),
                                        right.contract.effectiveTypeBlueId());
                    }
                };

        private final EffectiveContractSnapshot contract;
        private final ExternalChannelFunctionEvaluation evaluation;
        private final ContractBundle bundle;

        private Candidate(
                ContractBundle bundle,
                EffectiveContractSnapshot contract,
                ExternalChannelFunctionEvaluation evaluation) {
            this.bundle = Objects.requireNonNull(
                    bundle, "bundle");
            this.contract = Objects.requireNonNull(contract, "contract");
            this.evaluation =
                    Objects.requireNonNull(evaluation, "evaluation");
        }
    }
}
