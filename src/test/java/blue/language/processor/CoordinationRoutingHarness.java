package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;

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
 * Test-only bridge for preparing exact verified external-delivery evidence.
 */
public final class CoordinationRoutingHarness {
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(
                    java.util.Arrays.<Object>asList(
                            7,
                            "coordination-logical-routing",
                            1));

    private CoordinationRoutingHarness() {
    }

    public static ProcessingSnapshotManager snapshotManager(
            Blue language) {
        return language.getDocumentProcessor()
                .snapshotManager();
    }

    public static DocumentProcessingResult process(
            DocumentProcessor processor,
            Node document,
            Node event,
            String... sourceKeys) {
        DeliveryOccurrence[] occurrences =
                new DeliveryOccurrence[sourceKeys.length];
        for (int index = 0; index < sourceKeys.length; index++) {
            occurrences[index] =
                    DeliveryOccurrence.at("/", sourceKeys[index]);
        }
        VerifiedExecutionEvidence evidence =
                evidence(processor, document, event, occurrences);
        return processor.processDocumentWithTrace(
                document,
                event,
                evidence).processResult();
    }

    /**
     * Builds complete, canonically ordered execution evidence for exact
     * external-channel occurrences at multiple embedded scopes.
     *
     * <p>This helper materializes feeder evidence only. It does not add an
     * authored target to PROCESS or discover application targets while
     * execution is mutating the Root.</p>
     */
    public static VerifiedExecutionEvidence evidence(
            DocumentProcessor processor,
            Node document,
            Node event,
            DeliveryOccurrence... occurrences) {
        return evidence(
                processor,
                document,
                document,
                event,
                occurrences);
    }

    /**
     * Derives occurrence metadata from an exact lifecycle-free contract
     * surface and binds it to a representation-equivalent managed Root.
     *
     * <p>Processor-owned markers do not participate in external-channel
     * selection. This overload lets a test prepare those markers after the
     * immutable subscription surface has been materialized, while the final
     * evidence remains bound to the exact Root passed to PROCESS.</p>
     */
    public static VerifiedExecutionEvidence evidence(
            DocumentProcessor processor,
            Node contractSurfaceDocument,
            Node boundDocument,
            Node event,
            DeliveryOccurrence... occurrences) {
        return evidence(
                processor,
                contractSurfaceDocument,
                boundDocument,
                event,
                7L,
                7L,
                occurrences);
    }

    /**
     * Derives exact occurrence metadata and binds it to the revisions authored
     * by a conformance feeder.
     *
     * @param processor configured processor used for exact channel evaluation
     * @param contractSurfaceDocument lifecycle-free effective Contract surface
     * @param boundDocument exact Root passed to PROCESS
     * @param event exact processing event
     * @param managedRootRevision feeder-managed Root revision
     * @param indexedRootRevision subscription-index Root revision
     * @param occurrences exact eligible source occurrences
     * @return immutable evidence for the three-argument PROCESS API
     */
    public static VerifiedExecutionEvidence evidence(
            DocumentProcessor processor,
            Node contractSurfaceDocument,
            Node boundDocument,
            Node event,
            long managedRootRevision,
            long indexedRootRevision,
            DeliveryOccurrence... occurrences) {
        return evidence(
                processor,
                contractSurfaceDocument,
                boundDocument,
                event,
                event,
                managedRootRevision,
                indexedRootRevision,
                occurrences);
    }

    /**
     * Derives exact occurrence metadata from the materialized event and binds
     * the resulting evidence to the representation-equivalent event supplied
     * to PROCESS.
     *
     * @param processor configured processor used for exact channel evaluation
     * @param contractSurfaceDocument exact initialized Contract surface
     * @param boundDocument representation-equivalent Root passed to PROCESS
     * @param contractSurfaceEvent exact event used for channel evaluation
     * @param boundEvent representation-equivalent event passed to PROCESS
     * @param managedRootRevision feeder-managed Root revision
     * @param indexedRootRevision subscription-index Root revision
     * @param occurrences exact eligible source occurrences
     * @return immutable evidence for the three-argument PROCESS API
     */
    public static VerifiedExecutionEvidence evidence(
            DocumentProcessor processor,
            Node contractSurfaceDocument,
            Node boundDocument,
            Node contractSurfaceEvent,
            Node boundEvent,
            long managedRootRevision,
            long indexedRootRevision,
            DeliveryOccurrence... occurrences) {
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(
                contractSurfaceDocument,
                "contractSurfaceDocument");
        Objects.requireNonNull(
                boundDocument, "boundDocument");
        Objects.requireNonNull(
                contractSurfaceEvent,
                "contractSurfaceEvent");
        Objects.requireNonNull(boundEvent, "boundEvent");
        ResolvedSnapshot snapshot =
                snapshotPreservingExecutableBodies(
                        processor,
                        contractSurfaceDocument);
        List<Candidate> allExternalChannels =
                new ArrayList<>();
        Deque<String> pendingScopes =
                new ArrayDeque<>();
        Set<String> visitedScopes =
                new LinkedHashSet<>();
        pendingScopes.add(JsonPointer.ROOT);
        while (!pendingScopes.isEmpty()) {
            String scopePath =
                    pendingScopes.removeFirst();
            if (!visitedScopes.add(scopePath)) {
                throw new IllegalArgumentException(
                        "Repeated Process Embedded scope "
                                + scopePath);
            }
            ContractBundle bundle =
                    processor.contractLoader()
                            .load(snapshot, scopePath);
            List<String> effectiveKeys =
                    new ArrayList<>();
            for (EffectiveContractSnapshot contract
                    : bundle
                    .effectiveContractSnapshots()) {
                effectiveKeys.add(contract.key());
            }
            for (EffectiveContractSnapshot contract
                    : bundle
                    .effectiveContractSnapshots()) {
                if (!EffectiveContractSnapshotConstants
                        .Role.EXTERNAL_CHANNEL.equals(
                                contract.role())) {
                    continue;
                }
                ExternalChannelFunctionEvaluation
                        evaluation =
                        ExternalChannelFunctionEvaluation
                                .evaluate(
                                        processor.registry(),
                                        processor
                                                .contractConverter(),
                                        ExternalChannelFunctionEvaluation
                                                .verifiedMatcherSessions(
                                                        processor
                                                                .snapshotManager()),
                                        bundle,
                                        contract,
                                        contractSurfaceEvent,
                                        effectiveKeys);
                if (evaluation.accepts()
                        && !evaluation.preselects()) {
                    throw new IllegalArgumentException(
                            "External subscription law violated "
                                    + "(ACCEPTS => PRESELECTS) at "
                                    + scopePath + "/"
                                    + contract.key());
                }
                allExternalChannels.add(
                        new Candidate(
                                contract,
                                evaluation));
            }
            for (String embedded
                    : bundle.embeddedPaths()) {
                String child =
                        PointerUtils.resolvePointer(
                                scopePath,
                                embedded);
                if (visitedScopes.contains(child)
                        || pendingScopes.contains(child)) {
                    throw new IllegalArgumentException(
                            "Repeated Process Embedded scope "
                                    + child);
                }
                pendingScopes.addLast(child);
            }
        }
        Collections.sort(
                allExternalChannels,
                Candidate.CANONICAL_ORDER);
        List<String> authoredAccepted =
                new ArrayList<>();
        Set<String> distinctAuthored =
                new LinkedHashSet<>();
        for (DeliveryOccurrence occurrence :
                Objects.requireNonNull(
                        occurrences, "occurrences")) {
            DeliveryOccurrence exact =
                    Objects.requireNonNull(
                            occurrence, "occurrence");
            String location = exact.location();
            if (!distinctAuthored.add(location)) {
                throw new IllegalArgumentException(
                        "Duplicate authored eligible source "
                                + location);
            }
            authoredAccepted.add(location);
        }
        List<String> derivedAccepted =
                new ArrayList<>();
        for (Candidate candidate
                : allExternalChannels) {
            if (candidate.evaluation.accepts()) {
                derivedAccepted.add(
                        candidate.location());
            }
        }
        if (!authoredAccepted.equals(
                derivedAccepted)) {
            throw new IllegalArgumentException(
                    "Authored eligible sources do not equal "
                            + "the exact accepting source sequence: "
                            + "authored=" + authoredAccepted
                            + ", derived=" + derivedAccepted);
        }
        ExternalDeliveryPlan.Builder plan =
                ExternalDeliveryPlan.builder()
                        .revisions(
                                managedRootRevision,
                                indexedRootRevision)
                        .eventOrderKey(EVENT_ORDER)
                        .exactRuntimeState();
        for (Candidate candidate
                : allExternalChannels) {
            if (candidate.evaluation
                    .preselects()) {
                plan.delivery(delivery(
                        candidate.contract,
                        candidate.evaluation));
            }
            plan.activeSubscriptionInterval(
                    activeInterval(
                            candidate.contract,
                            candidate.evaluation));
        }
        return plan.build().bind(
                boundDocument,
                boundEvent,
                processor.runtimeRegistryIdentity());
    }

    /**
     * Identifies one external-channel occurrence in the managed Root.
     */
    public static final class DeliveryOccurrence {
        private final String scopePath;
        private final String sourceKey;

        private DeliveryOccurrence(
                String scopePath,
                String sourceKey) {
            this.scopePath =
                    Objects.requireNonNull(
                            scopePath, "scopePath");
            this.sourceKey =
                    Objects.requireNonNull(
                            sourceKey, "sourceKey");
        }

        public static DeliveryOccurrence at(
                String scopePath,
                String sourceKey) {
            return new DeliveryOccurrence(
                scopePath, sourceKey);
        }

        private String location() {
            return scopePath
                    + ":" + sourceKey;
        }

        /**
         * Returns the exact owning scope.
         *
         * @return normalized absolute scope path
         */
        public String scopePath() {
            return scopePath;
        }

        /**
         * Returns the exact source-channel key.
         *
         * @return source-channel key
         */
        public String sourceKey() {
            return sourceKey;
        }
    }

    private static final class Candidate {
        private static final Comparator<Candidate>
                CANONICAL_ORDER =
                new Comparator<Candidate>() {
                    @Override
                    public int compare(
                            Candidate left,
                            Candidate right) {
                        int compared = Integer.compare(
                                depth(right.contract.scopePath()),
                                depth(left.contract.scopePath()));
                        if (compared != 0) {
                            return compared;
                        }
                        compared =
                                ExternalOrderKey
                                        .compareTextCodePoints(
                                                left.contract
                                                        .scopePath(),
                                                right.contract
                                                        .scopePath());
                        if (compared != 0) {
                            return compared;
                        }
                        compared = Integer.compare(
                                left.contract.order(),
                                right.contract.order());
                        if (compared != 0) {
                            return compared;
                        }
                        compared =
                                ExternalOrderKey
                                        .compareTextCodePoints(
                                                left.contract.key(),
                                                right.contract.key());
                        return compared != 0
                                ? compared
                                : ExternalOrderKey
                                .compareTextCodePoints(
                                        left.contract
                                                .effectiveTypeBlueId(),
                                        right.contract
                                                .effectiveTypeBlueId());
                    }
                };

        private final EffectiveContractSnapshot contract;
        private final ExternalChannelFunctionEvaluation evaluation;
        private final ContractBundle bundle;

        private Candidate(
                EffectiveContractSnapshot contract,
                ExternalChannelFunctionEvaluation evaluation) {
            this(
                    contract,
                    evaluation,
                    null);
        }

        private Candidate(
                EffectiveContractSnapshot contract,
                ExternalChannelFunctionEvaluation evaluation,
                ContractBundle bundle) {
            this.contract =
                    Objects.requireNonNull(
                            contract, "contract");
            this.evaluation =
                    Objects.requireNonNull(
                            evaluation, "evaluation");
            this.bundle = bundle;
        }

        private String location() {
            return contract.scopePath()
                    + ":" + contract.key();
        }

        private static int depth(String scopePath) {
            return JsonPointer.split(scopePath).size();
        }
    }

    public static java.util.List<String> routingProjection(
            DocumentProcessor processor,
            Node document,
            Node event,
            String sourceKey) {
        ResolvedSnapshot snapshot =
                processor.snapshotManager()
                        .fromDocumentTransient(document);
        ContractBundle bundle =
                processor.contractLoader()
                        .load(snapshot, "/");
        EffectiveContractSnapshot contract =
                bundle.effectiveContractSnapshot(
                        sourceKey);
        ExternalChannelFunctionEvaluation evaluation =
                ExternalChannelFunctionEvaluation
                        .evaluate(
                                processor.registry(),
                                processor.contractConverter(),
                                ExternalChannelFunctionEvaluation
                                        .verifiedMatcherSessions(
                                                processor
                                                        .snapshotManager()),
                                bundle,
                                contract,
                                event);
        return java.util.Arrays.asList(
                evaluation.handlerChannelKey(),
                evaluation.logicalDeliveryKey());
    }

    /**
     * Reports exact retained-header fields that change across two equivalent
     * physical representations.
     *
     * <p>This is a conformance diagnostic only. It evaluates both sides with
     * the same configured processor and never substitutes either result for
     * feeder evidence.</p>
     */
    public static List<String> retainedHeaderDifferences(
            DocumentProcessor processor,
            Node exactDocument,
            Node representedDocument,
            Node exactEvent,
            Node representedEvent,
            DeliveryOccurrence occurrence) {
        Candidate exact = candidate(
                processor,
                exactDocument,
                exactEvent,
                occurrence);
        Candidate represented = candidate(
                processor,
                representedDocument,
                representedEvent,
                occurrence);
        List<String> differences =
                new ArrayList<String>();
        difference(
                differences,
                "scopePath",
                exact.contract.scopePath(),
                represented.contract.scopePath());
        difference(
                differences,
                "channelKey",
                exact.contract.key(),
                represented.contract.key());
        difference(
                differences,
                "effectiveTypeBlueId",
                exact.contract.effectiveTypeBlueId(),
                represented.contract.effectiveTypeBlueId());
        difference(
                differences,
                "sourceContributionNodeBlueIds",
                exact.contract.sourceContributionNodeBlueIds(),
                represented.contract
                        .sourceContributionNodeBlueIds());
        difference(
                differences,
                "order",
                Integer.valueOf(exact.contract.order()),
                Integer.valueOf(
                        represented.contract.order()));
        difference(
                differences,
                "intrinsicDependencies",
                exact.contract
                        .deterministicDependencyNodeBlueIds(),
                represented.contract
                        .deterministicDependencyNodeBlueIds());
        difference(
                differences,
                "sameScopeChannelHeaders",
                channelHeaderSignatures(exact.bundle),
                channelHeaderSignatures(
                        represented.bundle));
        difference(
                differences,
                "channelBinding",
                channelBindingSignature(
                        exact.bundle,
                        occurrence.sourceKey),
                channelBindingSignature(
                        represented.bundle,
                        occurrence.sourceKey));
        difference(
                differences,
                "subscriptionKeys",
                exact.evaluation.channelKeys(),
                represented.evaluation.channelKeys());
        difference(
                differences,
                "checkpointDomainBlueId",
                exact.evaluation.checkpointDomainBlueId(),
                represented.evaluation
                        .checkpointDomainBlueId());
        difference(
                differences,
                "dependencies",
                exact.evaluation.dependencies()
                        .deterministicDependencyNodeBlueIds(),
                represented.evaluation.dependencies()
                        .deterministicDependencyNodeBlueIds());
        difference(
                differences,
                "eventKeys",
                exact.evaluation.eventKeys(),
                represented.evaluation.eventKeys());
        difference(
                differences,
                "preselects",
                Boolean.valueOf(
                        exact.evaluation.preselects()),
                Boolean.valueOf(
                        represented.evaluation.preselects()));
        difference(
                differences,
                "accepts",
                Boolean.valueOf(
                        exact.evaluation.accepts()),
                Boolean.valueOf(
                        represented.evaluation.accepts()));
        difference(
                differences,
                "checkpointSubjectBlueId",
                exact.evaluation.checkpointSubjectBlueId(),
                represented.evaluation
                        .checkpointSubjectBlueId());
        difference(
                differences,
                "handlerChannelKey",
                exact.evaluation.handlerChannelKey(),
                represented.evaluation
                        .handlerChannelKey());
        difference(
                differences,
                "logicalDeliveryKey",
                exact.evaluation.logicalDeliveryKey(),
                represented.evaluation
                        .logicalDeliveryKey());
        difference(
                differences,
                "channelLookupResults",
                exact.evaluation.channelLookupResults(),
                represented.evaluation
                        .channelLookupResults());
        return Collections.unmodifiableList(
                differences);
    }

    private static Candidate candidate(
            DocumentProcessor processor,
            Node document,
            Node event,
            DeliveryOccurrence occurrence) {
        Node contractSurface =
                document.isReferenceOnly()
                        ? processor.snapshotManager()
                        .materializeVerifiedExactReference(
                                FrozenNode.fromNode(document))
                        .toNode()
                        : document;
        ResolvedSnapshot snapshot =
                snapshotPreservingExecutableBodies(
                        processor,
                        contractSurface);
        ContractBundle bundle =
                processor.contractLoader()
                        .load(snapshot, occurrence.scopePath);
        List<String> effectiveKeys =
                new ArrayList<String>();
        for (EffectiveContractSnapshot contract
                : bundle.effectiveContractSnapshots()) {
            effectiveKeys.add(contract.key());
        }
        EffectiveContractSnapshot contract =
                bundle.effectiveContractSnapshot(
                        occurrence.sourceKey);
        ExternalChannelFunctionEvaluation evaluation =
                ExternalChannelFunctionEvaluation.evaluate(
                        processor.registry(),
                        processor.contractConverter(),
                        ExternalChannelFunctionEvaluation
                                .verifiedMatcherSessions(
                                        processor
                                                .snapshotManager()),
                        bundle,
                        contract,
                        event,
                        effectiveKeys);
        return new Candidate(
                contract,
                evaluation,
                bundle);
    }

    private static ResolvedSnapshot
    snapshotPreservingExecutableBodies(
            DocumentProcessor processor,
            Node contractSurface) {
        Node exactContractSurface =
                Objects.requireNonNull(
                        contractSurface,
                        "contractSurface");
        if (exactContractSurface.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Routing evidence requires canonical exact "
                            + "contract-surface content");
        }
        try {
            BlueIdCalculator.calculateBlueId(
                    exactContractSurface);
        } catch (IllegalArgumentException mixedForm) {
            throw new IllegalArgumentException(
                    "Routing evidence requires canonical exact "
                            + "contract-surface content without resolved "
                            + "reference provenance",
                    mixedForm);
        }
        EffectiveFragmentationCatalog catalog =
                processor.effectiveFragmentationCatalog(
                        exactContractSurface);
        Set<String> executableBodyPaths =
                new LinkedHashSet<String>();
        for (Map.Entry<String,
                List<EffectiveContractSnapshot>>
                scopedContracts
                : catalog.effectiveContractsByScope()
                .entrySet()) {
            for (EffectiveContractSnapshot contract
                    : scopedContracts.getValue()) {
                for (String field
                        : contract.executableBodyFields()) {
                    executableBodyPaths.add(
                            PointerUtils.resolvePointer(
                                    scopedContracts.getKey(),
                                    JsonPointer.toPointer(
                                            java.util.Arrays.asList(
                                                    "contracts",
                                                    contract.key(),
                                                    field))));
                }
            }
        }
        return processor.snapshotManager()
                .fromDocumentTransientPreservingPaths(
                        exactContractSurface,
                        executableBodyPaths);
    }

    private static Map<String, Object> channelHeaderSignatures(
            ContractBundle bundle) {
        Map<String, Object> result =
                new LinkedHashMap<String, Object>();
        for (EffectiveContractSnapshot snapshot
                : bundle.effectiveContractSnapshots()) {
            boolean channel =
                    EffectiveContractSnapshotConstants
                            .Role.EXTERNAL_CHANNEL.equals(
                                    snapshot.role())
                            || EffectiveContractSnapshotConstants
                            .Role.PROCESSOR_CHANNEL.equals(
                                    snapshot.role());
            if (!channel) {
                continue;
            }
            Map<String, String> fields =
                    new LinkedHashMap<String, String>();
            for (Map.Entry<String, FrozenNode> field
                    : snapshot.headerFields().entrySet()) {
                fields.put(
                        field.getKey(),
                        field.getValue().blueId());
            }
            ChannelMemberSnapshot member =
                    ChannelMemberSnapshot.from(
                            snapshot);
            Map<String, Object> signature =
                    new LinkedHashMap<String, Object>();
            signature.put(
                    "type",
                    snapshot.effectiveTypeBlueId());
            signature.put(
                    "contributions",
                    snapshot.sourceContributionNodeBlueIds());
            signature.put(
                    "intrinsicDependencies",
                    snapshot
                            .deterministicDependencyNodeBlueIds());
            signature.put(
                    "headerFields",
                    fields);
            signature.put(
                    "headerIdentity",
                    member.headerIdentityBlueId());
            result.put(
                    snapshot.key(),
                    signature);
        }
        return result;
    }

    private static Map<String, Object>
    channelBindingSignature(
            ContractBundle bundle,
            String key) {
        Map<String, Object> result =
                new LinkedHashMap<String, Object>();
        ContractBundle.ChannelBinding binding =
                bundle.channelBinding(key);
        result.put(
                "present",
                Boolean.valueOf(binding != null));
        if (binding == null) {
            return result;
        }
        result.put(
                "key", binding.key());
        result.put(
                "contractClass",
                binding.contract().getClass().getName());
        result.put(
                "processorManaged",
                Boolean.valueOf(
                        ProcessorContractConstants
                                .isProcessorManagedChannel(
                                        binding.contract())));
        result.put(
                "order",
                Integer.valueOf(binding.order()));
        result.put(
                "nodeBlueId",
                binding.node() != null
                        ? binding.node().blueId()
                        : null);
        return result;
    }

    private static void difference(
            List<String> differences,
            String field,
            Object exact,
            Object represented) {
        if (!Objects.equals(exact, represented)) {
            differences.add(
                    field + ": exact=" + exact
                            + ", represented="
                            + represented);
        }
    }

    private static ExternalDeliverySnapshot delivery(
            EffectiveContractSnapshot snapshot,
            ExternalChannelFunctionEvaluation evaluation) {
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                snapshot.scopePath(),
                                snapshot.key())
                        .effectiveTypeBlueId(
                                snapshot
                                        .effectiveTypeBlueId())
                        .order(snapshot.order())
                        .checkpointDomainBlueId(
                                evaluation
                                        .checkpointDomainBlueId())
                        .checkpointSubjectBlueId(
                                evaluation
                                        .checkpointSubjectBlueId());
        for (String contribution
                : snapshot
                .sourceContributionNodeBlueIds()) {
            builder.sourceContribution(
                    contribution);
        }
        for (String subscriptionKey
                : evaluation.channelKeys()) {
            builder.subscriptionKey(
                    subscriptionKey);
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
                1L,
                null,
                null);
    }
}
