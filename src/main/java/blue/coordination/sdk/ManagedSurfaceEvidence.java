package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable processor and automatic-resolution evidence for one published
 * managed closure transition.
 *
 * <p>This surface reports facts already accepted by Contracts and published
 * atomically by Coordination. It is presentation evidence only: it neither
 * participates in identity nor authorizes a later transition.</p>
 */
public record ManagedSurfaceEvidence(
        long graphGeneration,
        List<OccurrenceResolution> resolvedOccurrences,
        List<GraphChange> graphChanges,
        List<ComponentTransition> componentTransitions,
        List<SubscriptionChange> subscriptionChanges,
        List<DocumentTransition> documentTransitions,
        List<OperationRouteChange> operationRouteChanges) {

    /**
     * Preserves the original constructor for callers compiled before typed
     * operation-route evidence was added.
     */
    public ManagedSurfaceEvidence(
            long graphGeneration,
            List<OccurrenceResolution> resolvedOccurrences,
            List<GraphChange> graphChanges,
            List<ComponentTransition> componentTransitions,
            List<SubscriptionChange> subscriptionChanges,
            List<DocumentTransition> documentTransitions) {
        this(graphGeneration,
                resolvedOccurrences,
                graphChanges,
                componentTransitions,
                subscriptionChanges,
                documentTransitions,
                List.of());
    }

    /** Defensively retains all ordered evidence collections. */
    public ManagedSurfaceEvidence {
        graphGeneration = SdkPreconditions.requireNonNegative(
                graphGeneration, "graphGeneration");
        resolvedOccurrences = immutable(
                resolvedOccurrences, "resolvedOccurrences");
        graphChanges = immutable(graphChanges, "graphChanges");
        componentTransitions = immutable(
                componentTransitions, "componentTransitions");
        subscriptionChanges = immutable(
                subscriptionChanges, "subscriptionChanges");
        documentTransitions = immutable(
                documentTransitions, "documentTransitions");
        operationRouteChanges = immutable(
                operationRouteChanges, "operationRouteChanges");
    }

    /** Canonical absence of committed managed-surface evidence. */
    public static ManagedSurfaceEvidence empty() {
        return new ManagedSurfaceEvidence(
                0L, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of());
    }

    /** Whether this view contains any committed managed-surface evidence. */
    public boolean present() {
        return !resolvedOccurrences.isEmpty()
                || !graphChanges.isEmpty()
                || !componentTransitions.isEmpty()
                || !subscriptionChanges.isEmpty()
                || !documentTransitions.isEmpty()
                || !operationRouteChanges.isEmpty();
    }

    /** Automatic resolver target classification. */
    public enum ResolutionKind {
        /** The authored value matched the exact current state of a lineage. */
        CURRENT_EXISTING,
        /** The authored value initialized a new managed lineage. */
        NEW_AUTHORED,
        /** The value matched an existing lineage's authored initial state. */
        EXISTING_AUTHORED_INITIAL,
        /** The value matched an existing lineage at initialized epoch zero. */
        EXISTING_INITIALIZED_EPOCH_ZERO,
        /** The value matched one unique retained source epoch. */
        EXISTING_RETAINED_EPOCH
    }

    /** Exact outcome of one automatic managed-occurrence resolution. */
    public record OccurrenceResolution(
            String demandIdentity,
            String occurrenceIdentity,
            String bindingIdentity,
            ClosureOccurrenceSnapshot occurrence,
            ResolutionKind kind,
            Optional<ExactBlueValue> authoredInitial) {
        /** Validates one immutable resolution result. */
        public OccurrenceResolution {
            demandIdentity = text(demandIdentity, "demandIdentity");
            occurrenceIdentity = text(
                    occurrenceIdentity, "occurrenceIdentity");
            bindingIdentity = text(bindingIdentity, "bindingIdentity");
            occurrence = Objects.requireNonNull(occurrence, "occurrence");
            kind = Objects.requireNonNull(kind, "kind");
            authoredInitial = Objects.requireNonNull(
                    authoredInitial, "authoredInitial");
            if ((kind == ResolutionKind.NEW_AUTHORED)
                    != authoredInitial.isPresent()) {
                throw new IllegalArgumentException(
                        "Only NEW_AUTHORED retains authored initial content");
            }
        }
    }

    /** Closed graph-change operation. */
    public enum GraphChangeKind {
        ADD,
        REMOVE,
        REBIND
    }

    /** One exact side of a managed graph change. */
    public record GraphSide(
            long activationGeneration,
            String occurrenceIdentity,
            String bindingIdentity,
            DocumentId targetDocumentId,
            String targetBlueId) {
        /** Validates one exact graph side. */
        public GraphSide {
            if (activationGeneration < 1L) {
                throw new IllegalArgumentException(
                        "activationGeneration must be positive");
            }
            occurrenceIdentity = text(
                    occurrenceIdentity, "occurrenceIdentity");
            bindingIdentity = text(bindingIdentity, "bindingIdentity");
            targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
            targetBlueId = text(targetBlueId, "targetBlueId");
        }
    }

    /** One exact committed graph transition. */
    public record GraphChange(
            long ordinal,
            GraphChangeKind kind,
            DocumentId sourceDocumentId,
            String sourcePath,
            Optional<GraphSide> before,
            Optional<GraphSide> after) {
        /** Validates the closed shape of one graph transition. */
        public GraphChange {
            ordinal = SdkPreconditions.requireNonNegative(
                    ordinal, "ordinal");
            kind = Objects.requireNonNull(kind, "kind");
            sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            sourcePath = path(sourcePath, "sourcePath");
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            if ((kind == GraphChangeKind.ADD
                    && (before.isPresent() || after.isEmpty()))
                    || (kind == GraphChangeKind.REMOVE
                    && (before.isEmpty() || after.isPresent()))
                    || (kind == GraphChangeKind.REBIND
                    && (before.isEmpty() || after.isEmpty()))) {
                throw new IllegalArgumentException(
                        "Graph change kind disagrees with its sides");
            }
        }
    }

    /** Exact component structural kind. */
    public enum ComponentKind {
        ACYCLIC,
        CYCLIC
    }

    /** Exact independently addressable component state. */
    public record ComponentState(
            String componentIdentity,
            String componentStateIdentity,
            long componentGeneration,
            ComponentKind kind,
            List<DocumentId> memberDocumentIds,
            List<String> memberBlueIds,
            Optional<String> masterBlueId,
            Optional<String> cyclicProofIdentity) {
        /** Validates one complete presentation state. */
        public ComponentState {
            componentIdentity = text(
                    componentIdentity, "componentIdentity");
            componentStateIdentity = text(
                    componentStateIdentity, "componentStateIdentity");
            componentGeneration = SdkPreconditions.requireNonNegative(
                    componentGeneration, "componentGeneration");
            kind = Objects.requireNonNull(kind, "kind");
            memberDocumentIds = immutable(
                    memberDocumentIds, "memberDocumentIds");
            memberBlueIds = immutable(memberBlueIds, "memberBlueIds");
            masterBlueId = Objects.requireNonNull(masterBlueId, "masterBlueId");
            cyclicProofIdentity = Objects.requireNonNull(
                    cyclicProofIdentity, "cyclicProofIdentity");
            if (memberDocumentIds.isEmpty()
                    || memberDocumentIds.size() != memberBlueIds.size()) {
                throw new IllegalArgumentException(
                        "Component member lists must be non-empty and parallel");
            }
            memberBlueIds.forEach(value -> text(value, "memberBlueId"));
            if ((kind == ComponentKind.CYCLIC)
                    != (masterBlueId.isPresent()
                            && cyclicProofIdentity.isPresent())) {
                throw new IllegalArgumentException(
                        "Cyclic evidence disagrees with component kind");
            }
        }
    }

    /** Structural relationship between an input and resulting partition. */
    public enum ComponentTransitionKind {
        UNCHANGED_MEMBERSHIP,
        EXPANDED,
        CONTRACTED,
        MERGED,
        SPLIT,
        REPARTITIONED,
        CREATED,
        RETIRED
    }

    /** One connected before/after component-partition transition. */
    public record ComponentTransition(
            ComponentTransitionKind kind,
            List<ComponentState> before,
            List<ComponentState> after) {
        /** Validates one complete component transition group. */
        public ComponentTransition {
            kind = Objects.requireNonNull(kind, "kind");
            before = immutable(before, "before");
            after = immutable(after, "after");
            if (before.isEmpty() && after.isEmpty()) {
                throw new IllegalArgumentException(
                        "A component transition must retain one side");
            }
            if ((kind == ComponentTransitionKind.CREATED) != before.isEmpty()
                    || (kind == ComponentTransitionKind.RETIRED)
                            != after.isEmpty()) {
                throw new IllegalArgumentException(
                        "Created or retired component shape is inconsistent");
            }
        }
    }

    /** Closed subscription operation. */
    public enum SubscriptionOperation {
        ADD,
        REMOVE,
        REPLACE
    }

    /** Closed logical operation-route transition kind. */
    public enum OperationRouteChangeKind {
        ADD,
        REMOVE,
        REPLACE
    }

    /** Exact externally routable operation state. */
    public record OperationRouteState(
            String scopePath,
            String operation,
            String channel,
            List<TimelineSourceSnapshot> acceptedSources) {
        /** Validates one immutable route state. */
        public OperationRouteState {
            scopePath = path(scopePath, "scopePath");
            operation = text(operation, "operation");
            channel = text(channel, "channel");
            acceptedSources = immutable(
                    acceptedSources, "acceptedSources");
        }
    }

    /** One exact committed operation-route reconciliation change. */
    public record OperationRouteChange(
            long ordinal,
            OperationRouteChangeKind kind,
            DocumentId documentId,
            Optional<OperationRouteState> before,
            Optional<OperationRouteState> after) {
        /** Validates the closed before/after route transition shape. */
        public OperationRouteChange {
            ordinal = SdkPreconditions.requireNonNegative(
                    ordinal, "ordinal");
            kind = Objects.requireNonNull(kind, "kind");
            documentId = Objects.requireNonNull(documentId, "documentId");
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            if ((kind == OperationRouteChangeKind.ADD
                    && (before.isPresent() || after.isEmpty()))
                    || (kind == OperationRouteChangeKind.REMOVE
                    && (before.isEmpty() || after.isPresent()))
                    || (kind == OperationRouteChangeKind.REPLACE
                    && (before.isEmpty() || after.isEmpty()))) {
                throw new IllegalArgumentException(
                        "Operation route change kind disagrees with its sides");
            }
        }
    }

    /** Complete exact state of one Root-scoped Channel subscription. */
    public record SubscriptionState(
            String subscriptionIdentity,
            String channelOccurrenceIdentity,
            DocumentId managedDocumentId,
            String scopePath,
            long scopeActivationGeneration,
            String rawChannelKey,
            String effectiveRuntimeContributionBlueId,
            String subscriptionHeaderBlueId,
            String documentBlueId,
            long graphGeneration,
            long componentGeneration) {
        /** Validates one exact subscription state. */
        public SubscriptionState {
            subscriptionIdentity = text(
                    subscriptionIdentity, "subscriptionIdentity");
            channelOccurrenceIdentity = text(
                    channelOccurrenceIdentity,
                    "channelOccurrenceIdentity");
            managedDocumentId = Objects.requireNonNull(
                    managedDocumentId, "managedDocumentId");
            scopePath = path(scopePath, "scopePath");
            scopeActivationGeneration = SdkPreconditions.requireNonNegative(
                    scopeActivationGeneration, "scopeActivationGeneration");
            rawChannelKey = text(rawChannelKey, "rawChannelKey");
            effectiveRuntimeContributionBlueId = text(
                    effectiveRuntimeContributionBlueId,
                    "effectiveRuntimeContributionBlueId");
            subscriptionHeaderBlueId = text(
                    subscriptionHeaderBlueId, "subscriptionHeaderBlueId");
            documentBlueId = text(documentBlueId, "documentBlueId");
            graphGeneration = SdkPreconditions.requireNonNegative(
                    graphGeneration, "graphGeneration");
            componentGeneration = SdkPreconditions.requireNonNegative(
                    componentGeneration, "componentGeneration");
        }
    }

    /** One exact committed subscription delta. */
    public record SubscriptionChange(
            long ordinal,
            SubscriptionOperation operation,
            String targetManagedScopeIdentity,
            String channelOccurrenceIdentity,
            Optional<SubscriptionState> before,
            Optional<SubscriptionState> after) {
        /** Validates one closed subscription delta. */
        public SubscriptionChange {
            ordinal = SdkPreconditions.requireNonNegative(
                    ordinal, "ordinal");
            operation = Objects.requireNonNull(operation, "operation");
            targetManagedScopeIdentity = text(
                    targetManagedScopeIdentity,
                    "targetManagedScopeIdentity");
            channelOccurrenceIdentity = text(
                    channelOccurrenceIdentity,
                    "channelOccurrenceIdentity");
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            if ((operation == SubscriptionOperation.ADD
                    && (before.isPresent() || after.isEmpty()))
                    || (operation == SubscriptionOperation.REMOVE
                    && (before.isEmpty() || after.isPresent()))
                    || (operation == SubscriptionOperation.REPLACE
                    && (before.isEmpty() || after.isEmpty()))) {
                throw new IllegalArgumentException(
                        "Subscription operation disagrees with its sides");
            }
        }
    }

    /** Authored JSON Patch operation at {@code /contracts} or below. */
    public enum ContractPatchOperation {
        ADD,
        REPLACE,
        REMOVE
    }

    /** Exact authored contract-surface patch evidence. */
    public record ContractPatch(
            ContractPatchOperation operation,
            String path,
            Optional<String> authoredValueBlueId,
            Optional<String> beforeValueBlueId,
            Optional<String> afterValueBlueId) {
        /** Retains one immutable authored patch. */
        public ContractPatch {
            operation = Objects.requireNonNull(operation, "operation");
            path = ManagedSurfaceEvidence.path(path, "path");
            authoredValueBlueId = optionalText(
                    authoredValueBlueId, "authoredValueBlueId");
            beforeValueBlueId = optionalText(
                    beforeValueBlueId, "beforeValueBlueId");
            afterValueBlueId = optionalText(
                    afterValueBlueId, "afterValueBlueId");
        }
    }

    /** Exact processor-generated type-metadata write. */
    public record GeneralizationWrite(
            String path,
            String valueBlueId,
            int requiringPatchIndex) {
        /** Validates one generated metadata write. */
        public GeneralizationWrite {
            path = ManagedSurfaceEvidence.path(path, "path");
            valueBlueId = text(valueBlueId, "valueBlueId");
            if (requiringPatchIndex < 0) {
                throw new IllegalArgumentException(
                        "requiringPatchIndex must be non-negative");
            }
        }
    }

    /** Exact transition evidence for one processor-owned document step. */
    public record DocumentTransition(
            DocumentId documentId,
            String workOccurrenceIdentity,
            String beforeDocumentBlueId,
            String afterDocumentBlueId,
            Optional<String> beforeEffectiveTypeBlueId,
            Optional<String> afterEffectiveTypeBlueId,
            List<ContractPatch> authoredContractPatches,
            List<GeneralizationWrite> generatedGeneralizationWrites) {
        /** Retains one complete immutable transition view. */
        public DocumentTransition {
            documentId = Objects.requireNonNull(documentId, "documentId");
            workOccurrenceIdentity = text(
                    workOccurrenceIdentity, "workOccurrenceIdentity");
            beforeDocumentBlueId = text(
                    beforeDocumentBlueId, "beforeDocumentBlueId");
            afterDocumentBlueId = text(
                    afterDocumentBlueId, "afterDocumentBlueId");
            beforeEffectiveTypeBlueId = optionalText(
                    beforeEffectiveTypeBlueId,
                    "beforeEffectiveTypeBlueId");
            afterEffectiveTypeBlueId = optionalText(
                    afterEffectiveTypeBlueId,
                    "afterEffectiveTypeBlueId");
            authoredContractPatches = immutable(
                    authoredContractPatches, "authoredContractPatches");
            generatedGeneralizationWrites = immutable(
                    generatedGeneralizationWrites,
                    "generatedGeneralizationWrites");
        }
    }

    private static <T> List<T> immutable(List<T> values, String label) {
        ArrayList<T> result = new ArrayList<>(Objects.requireNonNull(
                values, label));
        for (T value : result) {
            Objects.requireNonNull(value, label + " entry");
        }
        return List.copyOf(result);
    }

    private static Optional<String> optionalText(
            Optional<String> value,
            String label) {
        Optional<String> selected = Objects.requireNonNull(value, label);
        selected.ifPresent(item -> text(item, label));
        return selected;
    }

    private static String text(String value, String label) {
        return SdkPreconditions.requireText(value, label);
    }

    private static String path(String value, String label) {
        String selected = text(value, label);
        if (!selected.startsWith("/")) {
            throw new IllegalArgumentException(
                    label + " must be an absolute path");
        }
        return selected;
    }
}
