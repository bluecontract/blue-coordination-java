package blue.coordination.basic.engine;

import blue.coordination.processor.CoordinationTestRuntime;
import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.SubscriptionDelta;
import blue.language.provider.NodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.repo.BlueRepository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Thin adapter around the frozen Language, Contracts, BEX, and Repository
 * releases.
 *
 * <p>The basic lane creates exactly one runtime generation, uses Language's
 * high-throughput bounded cache policy, retains complete snapshots when they
 * are useful across calls, and keeps request subtrees deferred. It does not
 * fork or patch any frozen sibling project.</p>
 */
public final class FrozenBlueRuntime implements AutoCloseable {
    private final CoordinationTestRuntime delegate;

    private FrozenBlueRuntime(CoordinationTestRuntime delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static FrozenBlueRuntime create(WholeObjectStore wholeObjects) {
        CoordinationTestRuntime runtime = CoordinationTestRuntime.create(
                BlueRepository.current(),
                Objects.requireNonNull(wholeObjects, "wholeObjects"),
                BlueCachePolicy.highThroughputDefaults());
        return new FrozenBlueRuntime(runtime);
    }

    public Node parseSourceYaml(String yaml) {
        return delegate.parseSourceYaml(yaml);
    }

    public Node preprocess(Node source) {
        return delegate.preprocess(source);
    }

    public String nodeToYaml(Node node) {
        return delegate.nodeToYaml(node);
    }

    public ResolvedSnapshot resolveToSnapshot(Node source) {
        return delegate.resolveToSnapshot(source);
    }

    /** Loads one exact body through the verified provider-reference boundary. */
    public ResolvedSnapshot loadExactSnapshot(String blueId) {
        FrozenNode reference = FrozenNode.fromNode(new Node().blueId(
                Objects.requireNonNull(blueId, "blueId")));
        FrozenNode materialized = delegate.contracts()
                .runtimeAccess()
                .materializeVerifiedExactReference(reference)
                .requireEstablished();
        return delegate.contracts().runtimeAccess()
                .resolveTransient(materialized.toNode());
    }

    public ResolvedSnapshot resolveToSnapshotPreservingPaths(
            Node source,
            Collection<String> paths) {
        return delegate.resolveToSnapshotPreservingPaths(source, paths);
    }

    public ResolvedSnapshot cache(ResolvedSnapshot snapshot) {
        return delegate.language().snapshots().cache(
                Objects.requireNonNull(snapshot, "snapshot"));
    }

    public BlueCacheStats cacheStats() {
        return delegate.language().snapshots().stats();
    }

    public DocumentProcessingResult initialize(ResolvedSnapshot snapshot) {
        return delegate.initializeDocument(snapshot);
    }

    /**
     * Captures the external delivery surface owned by one autonomous session.
     * Ordinary nested scopes remain part of that Root. Every scope at or below
     * a Process Embedded boundary is excluded because it has its own session.
     */
    public List<SubscriptionDelta.Entry> projectInitialOwnedSubscriptions(
            FrozenNode processingRoot,
            long rootRevision,
            ExternalOrderKey activationOrderKey) {
        FrozenNode exactProcessingRoot = Objects.requireNonNull(
                processingRoot, "processingRoot");
        // Keep admission evidence in the same ownership domain as PROCESS.
        SubscriptionDelta delta = delegate.contracts()
                .subscriptionSurfaceProjection()
                .projectInitial(
                        exactProcessingRoot.toNode(),
                        rootRevision,
                        activationOrderKey);
        if (!delta.removed().isEmpty()) {
            throw new IllegalStateException(
                    "Initial subscription projection retired an occurrence");
        }
        return delta.added();
    }

    /** Exactly one frozen Contracts PROCESS call for one autonomous Root. */
    public PlatformProcessingResult process(
            Node currentRootRepresentation,
            String exactEventBlueId,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> rootSubscriptions) {
        Node root = Objects.requireNonNull(
                currentRootRepresentation, "currentRootRepresentation");
        Node eventReference = new Node().blueId(Objects.requireNonNull(
                exactEventBlueId, "exactEventBlueId"));
        ExternalDeliveryPlan deliveryPlan = delegate.contracts()
                .currentRootDeliveryPlanDeriver(
                        rootRevision,
                        eventOrderKey,
                        rootSubscriptions)
                .derive(root, eventReference);
        PlatformProcessInvocation invocation =
                PlatformProcessInvocation.builder()
                        .deliveryPlan(deliveryPlan)
                        .nodeProvider(delegate.nodeProvider())
                        .build();
        return delegate.contracts().processForPlatformCommit(
                root,
                eventReference,
                invocation);
    }

    /** One authoritative catalog call when a semantic surface is compiled. */
    public EffectiveFragmentationCatalog effectiveFragmentationCatalog(
            String exactRootBlueId) {
        Node rootReference = new Node().blueId(Objects.requireNonNull(
                exactRootBlueId, "exactRootBlueId"));
        return delegate.contracts().effectiveFragmentationCatalog(rootReference);
    }

    public ExactNodeValue exactSource(
            String yaml,
            WholeObjectStore objects,
            String purpose) {
        Node source = parseSourceYaml(yaml);
        Node preprocessed = preprocess(source);
        ResolvedSnapshot snapshot = cache(resolveToSnapshot(preprocessed));
        return objects.put(snapshot, purpose);
    }

    public NodeProvider nodeProvider() {
        return delegate.nodeProvider();
    }

    @Override
    public void close() {
        delegate.close();
    }


}
