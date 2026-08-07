package blue.coordination.processor;

import blue.coordination.processor.fragmentation.EffectiveCutCatalogReader;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.Schema;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.BlueContracts;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.ExecutableBodySourceDescriptor;
import blue.language.processor.ProcessorRuntimeAccess;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.registry.NodeProviderWrapper;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Coordination-specific physical fragmentation for the two semantic PROCESS
 * inputs.
 *
 * <p>The splitter does not derive a delivery plan or execute a contract. It
 * keeps Coordination dispatch headers inline and replaces only declared
 * embedded roots and exact registered Handler executable-body fields with
 * ordinary BlueId references. Every replacement is checked to preserve the
 * containing Root's exact BlueId.</p>
 *
 * <p>Both inputs use Language's canonical direct-node graph profile. One
 * physical representation is therefore retained for a given BlueId even when
 * the same content is encountered as a Root, embedded scope, Source
 * contribution, or executable body. Semantic cut occurrences are retained
 * separately from physical fragments.</p>
 */
public final class CoordinationDocumentSplitter {

    /**
     * Stable profile for immutable exact fragments produced by this splitter.
     */
    public static final String FRAGMENTATION_PROFILE_ID =
            "blue.coordination/fragmentation/canonical-direct-node/1.0";

    /**
     * Stable identity of the nonsemantic provider view used while PROCESS
     * resolves immutable Coordination headers.
     *
     * <p>This view is not a storage profile. {@link SplitGraph#fragments()},
     * admission, digests, and reconstruction remain bound exclusively to
     * {@link #FRAGMENTATION_PROFILE_ID}. The view differs only by returning
     * exact registered contract headers with executable-body fields retained
     * as pure references. Participating scope views inline only that immutable
     * contracts-map view so Language can walk a fragmented Process Embedded
     * route one scope at a time without opening executable bodies. When an
     * admitted executable body is demanded, its ephemeral view inlines each
     * exact authored direct child so Language can select the concrete
     * workflow-step type and execute either list- or object-shaped literal
     * payloads. Authored pure references inside a selected body remain
     * references.</p>
     */
    public static final String PROCESS_HEADER_VIEW_PROFILE_ID =
            "blue.coordination/process-header-view/1.0";

    /**
     * Stable schema/version for {@link EdgeOccurrence} values.
     */
    public static final String EDGE_METADATA_SCHEMA_ID =
            "blue.coordination/fragment-edge-occurrence/2.0";

    private final Function<Node, EffectiveFragmentationCatalog>
            fragmentationCatalog;
    private final NodeProvider localProvider;

    private CoordinationDocumentSplitter() {
        this.fragmentationCatalog = null;
        this.localProvider = null;
    }

    private CoordinationDocumentSplitter(
            Function<Node, EffectiveFragmentationCatalog> catalog,
            NodeProvider localProvider) {
        this.fragmentationCatalog = Objects.requireNonNull(
                catalog, "catalog");
        this.localProvider = localProvider != null
                ? NodeProviderWrapper.wrap(localProvider)
                : null;
    }

    /**
     * Creates a splitter for the exact Event input only.
     *
     * <p>Document splitting requires the effective, inheritance-aware catalog
     * exposed by {@link BlueContracts}; this explicit factory cannot be
     * used for {@link #splitDocument(Node)}.</p>
     *
     * @return splitter configured for Event inputs only
     */
    public static CoordinationDocumentSplitter forEventSplitting() {
        return new CoordinationDocumentSplitter();
    }

    /**
     * Creates an offline splitter from an already established effective
     * catalog boundary.
     *
     * <p>This entry point is intended for deterministic replay, conformance
     * fixtures, and hosts that persist the public Language catalog as exact
     * evidence. The supplied function must bind the returned catalog to the
     * exact admitted Root; the splitter independently verifies the Root
     * BlueId before producing a fragment.</p>
     *
     * @param catalog exact effective-catalog lookup
     * @param localProvider optional exact provider for reference-backed input
     * @return splitter using only the supplied public catalog evidence
     */
    public static CoordinationDocumentSplitter fromEffectiveCatalog(
            Function<Node, EffectiveFragmentationCatalog> catalog,
            NodeProvider localProvider) {
        return new CoordinationDocumentSplitter(catalog, localProvider);
    }

    /**
     * Creates a splitter using the current focused Contracts facade.
     *
     * @param contracts borrowed Contracts service
     */
    public CoordinationDocumentSplitter(BlueContracts contracts) {
        this(contracts, null);
    }

    /**
     * Creates a splitter using the current focused Contracts facade and an
     * explicit exact provider for authored reference-backed headers.
     *
     * @param contracts borrowed Contracts service
     * @param localProvider exact provider, or {@code null} when all required
     *                      headers are inline
     */
    public CoordinationDocumentSplitter(
            BlueContracts contracts,
            NodeProvider localProvider) {
        BlueContracts checkedContracts = Objects.requireNonNull(
                contracts, "contracts");
        this.fragmentationCatalog =
                checkedContracts::effectiveFragmentationCatalog;
        NodeProvider runtimeProvider = runtimeProvider(
                checkedContracts.runtimeAccess());
        this.localProvider = NodeProviderWrapper.wrap(
                localProvider != null
                        ? new SequentialNodeProvider(
                                localProvider,
                                runtimeProvider)
                        : runtimeProvider);
    }

    private static NodeProvider runtimeProvider(
            ProcessorRuntimeAccess runtimeAccess) {
        ProcessorRuntimeAccess access = Objects.requireNonNull(
                runtimeAccess, "runtimeAccess");
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return fetchResultByBlueId(blueId).nodes();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(
                    String blueId) {
                BlueOperationResult<FrozenNode> result = access
                        .materializeVerifiedExactReference(
                                FrozenNode.fromNode(
                                        new Node().blueId(
                                                Objects.requireNonNull(
                                                        blueId,
                                                        "blueId"))));
                if (result.isEstablished()) {
                    return NodeProviderResult.found(
                            Collections.singletonList(
                                    result.requireEstablished().toNode()));
                }
                if (result.isAbsent()) {
                    return NodeProviderResult.notFound();
                }
                String reason = result.reason().orElse(
                        "Contracts runtime could not materialize exact "
                                + "content for " + blueId);
                return result.outcome() == BlueOperationOutcome.INCOMPLETE
                        ? NodeProviderResult.unavailable(reason)
                        : NodeProviderResult.invalidEvidence(reason);
            }
        };
    }

    /**
     * Splits one exact Coordination Root according to Process Embedded and
     * registered executable-body declarations.
     *
     * @param admittedRoot exact Coordination Root admitted for splitting
     * @return identity-preserving document split graph
     */
    public SplitGraph splitDocument(Node admittedRoot) {
        return splitDocument(
                admittedRoot,
                CoordinationHostQuotaSession.disabled());
    }

    /**
     * Splits one exact Coordination Root and reports nonportable host work to
     * the explicit invocation-local quota session.
     *
     * @param admittedRoot exact Coordination Root admitted for splitting
     * @param hostQuotas invocation-local host quota session
     * @return identity-preserving document split graph
     */
    public SplitGraph splitDocument(
            Node admittedRoot,
            CoordinationHostQuotaSession hostQuotas) {
        CoordinationHostQuotaSession quotas =
                Objects.requireNonNull(
                        hostQuotas, "hostQuotas");
        DocumentFragmentationBlueprint blueprint =
                documentFragmentationBlueprint(
                        admittedRoot,
                        quotas,
                        null);
        List<Node> canonicalRoots = new ArrayList<>();
        for (PhysicalFragmentRoot root
                : blueprint.physicalRoots) {
            canonicalRoots.add(root.exactRoot);
        }
        ExactNodeGraphFragments canonicalGraph =
                new ExactNodeGraphFragments(
                        canonicalRoots);
        Map<String, Node> canonicalFragments = immutableFragments(
                canonicalGraph.fragments());
        Node fragmentedRoot =
                canonicalGraph.roots().get(0)
                        .directFragment();
        requireIdentity(
                blueprint.rootBlueId,
                fragmentedRoot,
                "Coordination Root");
        for (String blueId : canonicalGraph.blueIds()) {
            boolean documentRoot =
                    blueprint.rootBlueId.equals(
                            blueId);
            quotas.recordSplitterFragment(
                    CoordinationHostQuotaSession.SPLIT_DOCUMENT,
                    documentRoot
                            ? "/"
                            : "/fragments/" + blueId,
                    documentRoot
                            ? "document-root"
                            : "canonical-direct-node");
        }
        return new SplitGraph(
                blueprint.rootBlueId,
                blueprint.exactRoot,
                fragmentedRoot,
                canonicalFragments,
                blueprint.metadata,
                directEdges(
                        blueprint.physicalRootsInternal(),
                        blueprint.rootBlueId,
                        blueprint.cuts,
                        blueprint.scopePaths,
                        canonicalFragments,
                        new EdgeQuota(
                                quotas,
                                CoordinationHostQuotaSession
                                        .SPLIT_DOCUMENT)),
                blueprint.fragmentRoots,
                composedProvider(
                        canonicalFragments,
                        blueprint.processHeaderViews));
    }

    /**
     * Discovers the exact physical roots, cut provenance, and PROCESS views
     * needed to incrementally assemble one document graph.
     *
     * <p>This operation deliberately does not construct the canonical
     * direct-node fragment graph. Callers may inspect exact identities and
     * ask {@link #inspectDirectNode(DocumentFragmentationBlueprint,
     * FragmentRootKind, Node, String, boolean)} to assemble only bodies that
     * are absent from a prior immutable inventory. {@link #splitDocument(Node)}
     * remains the explicit full-graph oracle.</p>
     *
     * @param admittedRoot exact Coordination Root
     * @return immutable document fragmentation blueprint
     */
    public DocumentFragmentationBlueprint documentFragmentationBlueprint(
            Node admittedRoot) {
        return documentFragmentationBlueprint(
                admittedRoot,
                CoordinationHostQuotaSession.disabled(),
                null);
    }

    /**
     * Discovers a fragmentation blueprint using an already established
     * immutable effective catalog for the same exact Root.
     *
     * <p>The supplied catalog is an optimization input rather than trusted
     * identity evidence. This splitter independently canonicalizes and hashes
     * the admitted Root and rejects a catalog bound to any other identity.</p>
     *
     * @param admittedRoot exact Coordination Root
     * @param effectiveCatalog immutable effective catalog for that Root
     * @return immutable document fragmentation blueprint
     */
    public DocumentFragmentationBlueprint documentFragmentationBlueprint(
            Node admittedRoot,
            EffectiveFragmentationCatalog effectiveCatalog) {
        return documentFragmentationBlueprint(
                admittedRoot,
                CoordinationHostQuotaSession.disabled(),
                Objects.requireNonNull(
                        effectiveCatalog,
                        "effectiveCatalog"));
    }

    private DocumentFragmentationBlueprint documentFragmentationBlueprint(
            Node admittedRoot,
            CoordinationHostQuotaSession quotas,
            EffectiveFragmentationCatalog suppliedCatalog) {
        if (fragmentationCatalog == null && suppliedCatalog == null) {
            throw new IllegalStateException(
                    "Document splitting requires a BlueContracts-backed or "
                            + "explicit effective fragmentation catalog");
        }
        /* The ordinary catalog lookup admits its Root into a transient
         * snapshot, while a supplied catalog is already immutable.
         * canonicalExactCopy independently owns the splitter's mutable working
         * graph, so an additional eager complete-Root clone would duplicate
         * linear work on both paths. */
        Node suppliedRoot = Objects.requireNonNull(
                admittedRoot,
                "admittedRoot");
        EffectiveFragmentationCatalog catalog = suppliedCatalog != null
                ? suppliedCatalog
                : fragmentationCatalog.apply(suppliedRoot);
        Node exactRoot =
                CoordinationProcessHeaderBridge
                        .canonicalExactCopy(
                                suppliedRoot.isReferenceOnly()
                                        ? exactContent(
                                                suppliedRoot,
                                                "admittedRoot",
                                                true)
                                        : suppliedRoot);
        CoordinationExactNodeIndex exactNodeIndex =
                new CoordinationExactNodeIndex();
        String rootBlueId = exactNodeIndex.blueId(exactRoot);
        if (!rootBlueId.equals(catalog.rootBlueId())) {
            throw new IllegalStateException(
                    "Effective fragmentation catalog changed Root BlueId from "
                            + rootBlueId
                            + " to "
                            + catalog.rootBlueId());
        }
        DocumentPlan plan = discoverDocumentPlan(
                exactRoot,
                catalog,
                quotas);
        List<FragmentMetadata> metadata = new ArrayList<>();
        List<PhysicalFragmentRoot> physicalRoots = new ArrayList<>();
        List<FragmentRoot> fragmentRoots = new ArrayList<>();
        physicalRoots.add(new PhysicalFragmentRoot(
                exactRoot,
                rootBlueId,
                FragmentRootKind.DOCUMENT,
                "/"));
        fragmentRoots.add(new FragmentRoot(
                rootBlueId,
                FragmentRootKind.DOCUMENT,
                "/"));

        for (ScopePlan scope : plan.scopes.values()) {
            String scopeBlueId =
                    exactNodeIndex.blueId(scope.exactScope);
            metadata.add(new FragmentMetadata(
                    scopeBlueId,
                    "/".equals(scope.scopePath)
                            ? FragmentKind.DOCUMENT_ROOT
                            : FragmentKind.EMBEDDED_ROOT,
                    scope.scopePath,
                    scope.scopePath,
                    null,
                            null));
            if (!"/".equals(scope.scopePath)) {
                physicalRoots.add(new PhysicalFragmentRoot(
                        scope.exactScope,
                        scopeBlueId,
                        FragmentRootKind.DOCUMENT_SCOPE,
                        scope.scopePath));
                fragmentRoots.add(new FragmentRoot(
                        scopeBlueId,
                        FragmentRootKind.DOCUMENT_SCOPE,
                        scope.scopePath));
            }
        }

        for (SourceContributionPlan sourceContribution
                : plan.sourceContributions.values()) {
            metadata.add(new FragmentMetadata(
                    sourceContribution.blueId,
                    FragmentKind.SOURCE_CONTRIBUTION,
                    null,
                    null,
                    null,
                    null));
            physicalRoots.add(new PhysicalFragmentRoot(
                    sourceContribution.exactContribution,
                    sourceContribution.blueId,
                    FragmentRootKind.SOURCE_CONTRIBUTION,
                    sourceContributionBasePath(
                            sourceContribution.blueId)));
            fragmentRoots.add(new FragmentRoot(
                    sourceContribution.blueId,
                    FragmentRootKind.SOURCE_CONTRIBUTION,
                    sourceContributionBasePath(
                            sourceContribution.blueId)));
        }

        for (BodyCut body : plan.bodies) {
            if (body.exactBody == null
                    || body.exactBody.isReferenceOnly()) {
                continue;
            }
            String bodyBlueId =
                    exactNodeIndex.blueId(body.exactBody);
            metadata.add(new FragmentMetadata(
                    bodyBlueId,
                    FragmentKind.EXECUTABLE_BODY,
                    body.scopePath,
                    body.absolutePointer,
                    body.handlerTypeBlueId,
                    body.field));
        }
        List<Node> exactRoots = new ArrayList<>();
        for (PhysicalFragmentRoot root : physicalRoots) {
            exactRoots.add(root.exactRoot);
        }
        Map<String, Node> processHeaderViews =
                processHeaderViews(
                        plan,
                        exactRoots,
                        exactNodeIndex);
        return new DocumentFragmentationBlueprint(
                rootBlueId,
                exactRoot,
                physicalRoots,
                metadata,
                fragmentRoots,
                documentCutDescriptors(plan),
                new ArrayList<String>(plan.scopes.keySet()),
                processHeaderViews,
                exactNodeIndex);
    }

    /**
     * Inspects one exact physical node under a document blueprint.
     *
     * <p>Direct child occurrences are always returned. The shallow canonical
     * body is assembled only when {@code assembleFragment} is true, allowing
     * an incremental host to retain a prior body without rebuilding it. The
     * returned child values are exact defensive copies and identify the
     * recursion frontier for splitter-created edges.</p>
     */
    public DirectNodeInspection inspectDirectNode(
            DocumentFragmentationBlueprint blueprint,
            FragmentRootKind rootKind,
            Node exactOwner,
            String ownerAbsolutePath,
            boolean assembleFragment) {
        DocumentFragmentationBlueprint checkedBlueprint =
                Objects.requireNonNull(
                        blueprint, "blueprint");
        FragmentRootKind checkedRootKind =
                Objects.requireNonNull(
                        rootKind, "rootKind");
        Node owner = Objects.requireNonNull(
                exactOwner, "exactOwner");
        if (owner.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "A physical fragment owner requires exact inline content");
        }
        String absolutePath = JsonPointer.canonicalize(
                Objects.requireNonNull(
                        ownerAbsolutePath,
                        "ownerAbsolutePath"));
        String ownerBlueId = checkedBlueprint.blueId(owner);
        List<DirectChildSpec> children =
                directChildSpecs(owner);
        Node directFragment = null;
        if (assembleFragment) {
            directFragment = checkedBlueprint.directFragment(owner);
            requireIdentity(
                    ownerBlueId,
                    directFragment,
                    "Incremental direct fragment");
        }

        List<DirectChildOccurrence> occurrences =
                new ArrayList<>();
        for (DirectChildSpec child : children) {
            String childBlueId = checkedBlueprint.blueId(
                    child.exactChild);
            EdgeOccurrence edge = describeDirectEdge(
                    checkedBlueprint,
                    checkedRootKind,
                    ownerBlueId,
                    absolutePath,
                    child.relativePointer,
                    childBlueId,
                    child.exactChild.isReferenceOnly(),
                    !child.exactChild.isReferenceOnly());
            occurrences.add(new DirectChildOccurrence(
                    child.exactChild,
                    edge));
        }
        return new DirectNodeInspection(
                ownerBlueId,
                directFragment,
                occurrences);
    }

    /**
     * Continues an incremental inspection through one splitter-created child
     * without cloning its unchanged descendant subtree.
     */
    public DirectNodeInspection inspectDirectChild(
            DocumentFragmentationBlueprint blueprint,
            FragmentRootKind rootKind,
            DirectChildOccurrence child,
            boolean assembleFragment) {
        DirectChildOccurrence checked = Objects.requireNonNull(
                child, "child");
        if (!Objects.requireNonNull(
                blueprint, "blueprint").rootBlueId.equals(
                checked.edge.rootBlueId())) {
            throw new IllegalArgumentException(
                    "Direct child belongs to another document blueprint");
        }
        if (!checked.edge.splitterCreated()) {
            throw new IllegalArgumentException(
                    "An authored pure reference has no local child body");
        }
        return inspectDirectNode(
                blueprint,
                rootKind,
                checked.exactChild,
                checked.edge.absolutePointer(),
                assembleFragment);
    }

    /** Inspects one retained blueprint root without copying its subtree. */
    public DirectNodeInspection inspectPhysicalRoot(
            DocumentFragmentationBlueprint blueprint,
            PhysicalFragmentRoot root,
            boolean assembleFragment) {
        PhysicalFragmentRoot checked = Objects.requireNonNull(
                root, "root");
        DocumentFragmentationBlueprint checkedBlueprint =
                Objects.requireNonNull(blueprint, "blueprint");
        if (!checkedBlueprint.physicalRoots.contains(checked)) {
            throw new IllegalArgumentException(
                    "Physical root belongs to another document blueprint");
        }
        return inspectDirectNode(
                checkedBlueprint,
                checked.rootKind,
                checked.exactRoot,
                checked.basePath,
                assembleFragment);
    }

    /**
     * Rebinds a retained canonical reference shape to a current occurrence.
     * This keeps an immutable prior body authoritative when the exact result
     * contains a representation-equivalent expanded or implicit form.
     */
    public EdgeOccurrence describeRetainedDirectEdge(
            DocumentFragmentationBlueprint blueprint,
            FragmentRootKind rootKind,
            String ownerBlueId,
            String ownerAbsolutePath,
            String ownerRelativePointer,
            String childBlueId,
            boolean originalPureReference,
            boolean splitterCreated) {
        return describeDirectEdge(
                Objects.requireNonNull(blueprint, "blueprint"),
                Objects.requireNonNull(rootKind, "rootKind"),
                BlueIds.requirePlainBlueId(
                        ownerBlueId, "ownerBlueId"),
                JsonPointer.canonicalize(
                        Objects.requireNonNull(
                                ownerAbsolutePath,
                                "ownerAbsolutePath")),
                JsonPointer.canonicalize(
                        Objects.requireNonNull(
                                ownerRelativePointer,
                                "ownerRelativePointer")),
                requireText(childBlueId, "childBlueId"),
                originalPureReference,
                splitterCreated);
    }

    private static EdgeOccurrence describeDirectEdge(
            DocumentFragmentationBlueprint blueprint,
            FragmentRootKind rootKind,
            String ownerBlueId,
            String ownerAbsolutePath,
            String ownerRelativePointer,
            String childBlueId,
            boolean originalPureReference,
            boolean splitterCreated) {
        String absolutePointer = appendRelativePointer(
                ownerAbsolutePath,
                ownerRelativePointer);
        CutDescriptor cut = blueprint.cuts.get(
                absolutePointer);
        EdgeKind edgeKind = cut != null
                ? cut.kind
                : rootKind == FragmentRootKind.EVENT
                ? EdgeKind.EVENT_DIRECT_CHILD
                : EdgeKind.DOCUMENT_DIRECT_CHILD;
        String ownerScopePath = cut != null
                ? cut.ownerScopePath
                : nearestScopePath(
                        blueprint.scopePaths,
                        ownerAbsolutePath);
        return new EdgeOccurrence(
                FRAGMENTATION_PROFILE_ID,
                EDGE_METADATA_SCHEMA_ID,
                rootKind,
                blueprint.rootBlueId,
                ownerBlueId,
                ownerScopePath,
                absolutePointer,
                ownerRelativePointer,
                childBlueId,
                edgeKind,
                originalPureReference,
                splitterCreated,
                cut != null ? cut.declaringScopePath : null,
                cut != null
                        ? cut.embeddedOrigin
                        : EmbeddedEdgeOrigin.NONE,
                cut != null
                        ? cut.explicitDeclarationPath
                        : null,
                cut != null
                        ? cut.collectionDeclarationPath
                        : null,
                cut != null ? cut.collectionMemberKey : null,
                cut != null ? cut.handlerTypeBlueId : null,
                cut != null ? cut.executableBodyField : null,
                cut != null
                        ? cut.sourceContributionBlueIds
                        : Collections.<String>emptyList());
    }

    private static List<DirectChildSpec> directChildSpecs(
            Node owner) {
        List<DirectChildSpec> result = new ArrayList<>();
        if (!isImplicitScalarRepresentation(owner)) {
            addDirectChild(result, "/type", owner.getType());
        }
        addDirectChild(result, "/itemType", owner.getItemType());
        addDirectChild(result, "/keyType", owner.getKeyType());
        addDirectChild(result, "/valueType", owner.getValueType());
        addDirectChild(result, "/contracts", owner.getContracts());
        addDirectChild(result, "/blue", owner.getBlue());
        if (owner.getItems() != null) {
            for (int index = 0;
                    index < owner.getItems().size();
                    index++) {
                addDirectChild(
                        result,
                        JsonPointer.toPointer(
                                Arrays.asList(
                                        "items",
                                        String.valueOf(index))),
                        owner.getItems().get(index));
            }
        }
        if (owner.getProperties() != null) {
            SortedMap<String, Node> ordered = new TreeMap<>(
                    owner.getProperties());
            for (Map.Entry<String, Node> entry
                    : ordered.entrySet()) {
                addDirectChild(
                        result,
                        JsonPointer.toPointer(
                                Collections.singletonList(
                                        entry.getKey())),
                        entry.getValue());
            }
        }
        Schema schema = owner.getSchema();
        if (schema != null && !schema.isReferenceOnly()) {
            addSchemaChild(
                    result,
                    "/schema/minimum",
                    schema.getMinimum());
            addSchemaChild(
                    result,
                    "/schema/maximum",
                    schema.getMaximum());
            addSchemaChild(
                    result,
                    "/schema/exclusiveMinimum",
                    schema.getExclusiveMinimum());
            addSchemaChild(
                    result,
                    "/schema/exclusiveMaximum",
                    schema.getExclusiveMaximum());
            addSchemaChild(
                    result,
                    "/schema/multipleOf",
                    schema.getMultipleOf());
            if (schema.getEnum() != null) {
                for (int index = 0;
                        index < schema.getEnum().size();
                        index++) {
                    addSchemaChild(
                            result,
                            JsonPointer.toPointer(
                                    Arrays.asList(
                                            "schema",
                                            "enum",
                                            String.valueOf(index))),
                            schema.getEnum().get(index));
                }
            }
        }
        return result;
    }

    private static boolean isImplicitScalarRepresentation(Node node) {
        if (node.getRawValue() == null
                || node.getName() != null
                || node.getDescription() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getItems() != null
                || node.getProperties() != null
                || node.getContracts() != null
                || node.getBlueId() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null) {
            return false;
        }
        return DirectBlueIdCalculator.calculateBlueId(node)
                .equals(DirectBlueIdCalculator.calculateBlueId(
                        new Node().value(node.getRawValue())));
    }

    private static void addDirectChild(
            List<DirectChildSpec> result,
            String relativePointer,
            Node child) {
        if (child != null) {
            result.add(new DirectChildSpec(
                    relativePointer,
                    child));
        }
    }

    private static void addSchemaChild(
            List<DirectChildSpec> result,
            String relativePointer,
            Node child) {
        if (child != null && !isPlainSchemaScalar(child)) {
            addDirectChild(result, relativePointer, child);
        }
    }

    static boolean isPlainSchemaScalar(
            Node node) {
        return node != null
                && node.getRawValue() != null
                && node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getItems() == null
                && node.getProperties() == null
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    /**
     * Splits one exact Event into Language direct-node fragments.
     *
     * @param admittedEvent exact Event admitted for splitting
     * @return identity-preserving Event split graph
     */
    public SplitGraph splitEvent(Node admittedEvent) {
        return splitEvent(
                admittedEvent,
                CoordinationHostQuotaSession.disabled());
    }

    /**
     * Splits one exact Event and reports each retained exact fragment to the
     * explicit invocation-local quota session.
     *
     * @param admittedEvent exact Event admitted for splitting
     * @param hostQuotas invocation-local host quota session
     * @return identity-preserving Event split graph
     */
    public SplitGraph splitEvent(
            Node admittedEvent,
            CoordinationHostQuotaSession hostQuotas) {
        CoordinationHostQuotaSession quotas =
                Objects.requireNonNull(
                        hostQuotas, "hostQuotas");
        Node exactEvent = requireExactContent(
                admittedEvent, "admittedEvent");
        ExactNodeGraphFragments exactGraph =
                new ExactNodeGraphFragments(exactEvent);
        /* ExactNodeGraphFragments already returns a private defensive
         * snapshot.  This method is its sole owner, so hashing and cloning
         * every shallow fragment again before the SplitGraph takes ownership
         * would establish no additional boundary. */
        Map<String, Node> canonicalFragments = exactGraph.fragments();
        ExactNodeGraphFragments.RootRepresentation root =
                exactGraph.roots().get(0);
        Node originalEvent = root.original();
        Node directEvent = root.directFragment();
        List<FragmentMetadata> metadata = new ArrayList<>();
        for (String blueId : exactGraph.blueIds()) {
            boolean eventRoot = root.blueId().equals(blueId);
            metadata.add(new FragmentMetadata(
                    blueId,
                    eventRoot
                            ? FragmentKind.EVENT_ROOT
                            : FragmentKind.EVENT_FRAGMENT,
                    "/",
                    eventRoot ? "/" : null,
                    null,
                    null));
        }
        for (String blueId : exactGraph.blueIds()) {
            boolean eventRoot =
                    root.blueId().equals(blueId);
            quotas.recordSplitterFragment(
                    CoordinationHostQuotaSession.SPLIT_EVENT,
                    eventRoot
                            ? "/"
                            : "/fragments/" + blueId,
                    eventRoot
                            ? "event-root"
                            : "event-fragment");
        }
        return new SplitGraph(
                root.blueId(),
                originalEvent,
                directEvent,
                canonicalFragments,
                metadata,
                directEdges(
                        originalEvent,
                        root.blueId(),
                        FragmentRootKind.EVENT,
                        "/",
                        Collections.<String, CutDescriptor>
                                emptyMap(),
                        canonicalFragments,
                        quotas),
                Collections.singletonList(
                        new FragmentRoot(
                                root.blueId(),
                                FragmentRootKind.EVENT,
                                "/")),
                exactGraph.provider(),
                true);
    }

    /**
     * Prepares the exact two PROCESS arguments and a lazily verified fragment
     * provider. Preparation itself does not consume either fragment; BlueId
     * evidence is verified when PROCESS first demands it. Execution evidence
     * remains out-of-band environment evidence, not a third semantic input.
     *
     * @param rootBlueId exact BlueId of the document Root
     * @param eventBlueId exact BlueId of the Event
     * @param evidence execution evidence bound to the Root and Event
     * @param fragmentProvider provider for lazily demanded exact fragments
     * @return prepared PROCESS inputs and verified fragment provider
     */
    public PreparedProcessingInput prepareForProcessing(
            String rootBlueId,
            String eventBlueId,
            VerifiedExecutionEvidence evidence,
            NodeProvider fragmentProvider) {
        String checkedRoot = BlueIds.requirePlainBlueId(
                rootBlueId, "rootBlueId");
        String checkedEvent = BlueIds.requirePlainBlueId(
                eventBlueId, "eventBlueId");
        VerifiedExecutionEvidence checkedEvidence =
                Objects.requireNonNull(evidence, "evidence");
        if (!checkedRoot.equals(checkedEvidence.rootBlueId())
                || !checkedEvent.equals(
                checkedEvidence.eventBlueId())) {
            throw new IllegalArgumentException(
                    "Execution evidence is not bound to the prepared Root and Event");
        }

        NodeProvider verifiedProvider =
                NodeProviderWrapper.wrap(
                        Objects.requireNonNull(
                                fragmentProvider, "fragmentProvider"));

        return new PreparedProcessingInput(
                new Node().blueId(checkedRoot),
                new Node().blueId(checkedEvent),
                checkedEvidence,
                verifiedProvider);
    }

    private static SortedMap<String, CutDescriptor>
    documentCutDescriptors(
            DocumentPlan plan) {
        SortedMap<String, CutDescriptor> cuts =
                new TreeMap<>();
        for (ScopePlan scope : plan.scopes.values()) {
            for (EmbeddedCut embedded
                    : scope.embeddedCuts) {
                cuts.put(
                        embedded.absolutePointer,
                        new CutDescriptor(
                                EdgeKind.EMBEDDED_ROOT,
                                embedded.ownerScopePath,
                                embedded.declaringScopePath,
                                embedded.origin,
                                embedded.explicitDeclarationPath,
                                embedded.collectionDeclarationPath,
                                embedded.collectionMemberKey,
                                null,
                                null,
                                Collections.<String>emptyList()));
            }
        }
        for (BodyCut body : plan.bodies) {
            String pointer = body.sourceContribution != null
                    ? PointerUtils.resolvePointer(
                    sourceContributionBasePath(
                            body.sourceContribution.blueId),
                    body.sourceContribution.sourcePointer)
                    : body.absolutePointer;
            cuts.put(
                    pointer,
                    new CutDescriptor(
                            body.sourceContribution != null
                                    ? EdgeKind
                                    .SOURCE_CONTRIBUTION_BODY
                                    : EdgeKind
                                    .EXECUTABLE_BODY,
                            body.scopePath,
                            null,
                            EmbeddedEdgeOrigin.NONE,
                            null,
                            null,
                            null,
                            body.handlerTypeBlueId,
                            body.field,
                            body.sourceContributionBlueIds));
        }
        return Collections.unmodifiableSortedMap(cuts);
    }

    private static List<EdgeOccurrence> directEdges(
            Node exactRoot,
            String rootBlueId,
            FragmentRootKind rootKind,
            String basePath,
            Map<String, CutDescriptor> cuts,
            Map<String, Node> canonicalFragments,
            CoordinationHostQuotaSession hostQuotas) {
        return directEdges(
                Collections.singletonList(
                        new PhysicalFragmentRoot(
                                exactRoot,
                                rootBlueId,
                                rootKind,
                                basePath)),
                rootBlueId,
                cuts,
                Collections.<String>emptyList(),
                canonicalFragments,
                new EdgeQuota(
                        hostQuotas,
                        rootKind == FragmentRootKind.EVENT
                                ? CoordinationHostQuotaSession
                                .SPLIT_EVENT
                                : CoordinationHostQuotaSession
                                .SPLIT_DOCUMENT));
    }

    private static List<EdgeOccurrence> directEdges(
            List<PhysicalFragmentRoot> roots,
            String rootBlueId,
            Map<String, CutDescriptor> cuts,
            List<String> scopePaths,
            Map<String, Node> canonicalFragments,
            EdgeQuota edgeQuota) {
        Map<String, Node> fragments = Objects.requireNonNull(
                canonicalFragments, "canonicalFragments");
        SortedMap<String, EdgeOccurrence> occurrences =
                new TreeMap<>();
        for (PhysicalFragmentRoot root : roots) {
            collectDirectEdges(
                    root.exactRoot,
                    root.blueId,
                    rootBlueId,
                    root.rootKind,
                    root.basePath,
                    cuts,
                    scopePaths,
                    fragments,
                    occurrences,
                    Collections.newSetFromMap(
                            new IdentityHashMap<Node, Boolean>()),
                    edgeQuota);
        }
        return Collections.unmodifiableList(
                new ArrayList<>(
                        occurrences.values()));
    }

    /**
     * Walks an owner whose canonical identity was already established by the
     * direct-fragment edge that led to it.  ExactNodeGraphFragments creates
     * that edge and the matching fragment in one pass, so re-hashing every
     * descendant during metadata collection is redundant.
     */
    private static void collectDirectEdges(
            Node owner,
            String ownerBlueId,
            String rootBlueId,
            FragmentRootKind rootKind,
            String ownerAbsolutePath,
            Map<String, CutDescriptor> cuts,
            List<String> scopePaths,
            Map<String, Node> canonicalFragments,
            SortedMap<String, EdgeOccurrence> occurrences,
            Set<Node> active,
            EdgeQuota edgeQuota) {
        if (!active.add(owner)) {
            throw new IllegalArgumentException(
                    "Inline object cycle cannot be represented by the "
                            + "Coordination fragmentation profile");
        }
        try {
            Node directOwner =
                    canonicalFragments.get(
                            ownerBlueId);
            if (directOwner == null) {
                throw new IllegalStateException(
                        "Canonical direct fragment is missing owner "
                                + ownerBlueId);
            }
            if (!isImplicitScalarRepresentation(owner)) {
                collectNodeEdge(
                        ownerBlueId,
                        owner.getType(),
                        directOwner.getType(),
                        "/type",
                        ownerAbsolutePath,
                        rootBlueId,
                        rootKind,
                        cuts,
                        scopePaths,
                        canonicalFragments,
                        occurrences,
                        active,
                        edgeQuota);
            }
            collectNodeEdge(
                    ownerBlueId,
                    owner.getItemType(),
                    directOwner.getItemType(),
                    "/itemType",
                    ownerAbsolutePath,
                    rootBlueId,
                    rootKind,
                    cuts,
                    scopePaths,
                    canonicalFragments,
                    occurrences,
                    active,
                    edgeQuota);
            collectNodeEdge(
                    ownerBlueId,
                    owner.getKeyType(),
                    directOwner.getKeyType(),
                    "/keyType",
                    ownerAbsolutePath,
                    rootBlueId,
                    rootKind,
                    cuts,
                    scopePaths,
                    canonicalFragments,
                    occurrences,
                    active,
                    edgeQuota);
            collectNodeEdge(
                    ownerBlueId,
                    owner.getValueType(),
                    directOwner.getValueType(),
                    "/valueType",
                    ownerAbsolutePath,
                    rootBlueId,
                    rootKind,
                    cuts,
                    scopePaths,
                    canonicalFragments,
                    occurrences,
                    active,
                    edgeQuota);
            collectNodeEdge(
                    ownerBlueId,
                    owner.getContracts(),
                    directOwner.getContracts(),
                    "/contracts",
                    ownerAbsolutePath,
                    rootBlueId,
                    rootKind,
                    cuts,
                    scopePaths,
                    canonicalFragments,
                    occurrences,
                    active,
                    edgeQuota);
            collectNodeEdge(
                    ownerBlueId,
                    owner.getBlue(),
                    directOwner.getBlue(),
                    "/blue",
                    ownerAbsolutePath,
                    rootBlueId,
                    rootKind,
                    cuts,
                    scopePaths,
                    canonicalFragments,
                    occurrences,
                    active,
                    edgeQuota);
            if (owner.getItems() != null) {
                for (int index = 0;
                        index < owner.getItems().size();
                        index++) {
                    collectNodeEdge(
                            ownerBlueId,
                            owner.getItems().get(index),
                            directOwner.getItems().get(index),
                            JsonPointer.toPointer(
                                    Arrays.asList(
                                            "items",
                                            String.valueOf(index))),
                            ownerAbsolutePath,
                            rootBlueId,
                            rootKind,
                            cuts,
                            scopePaths,
                            canonicalFragments,
                            occurrences,
                            active,
                            edgeQuota);
                }
            }
            if (owner.getProperties() != null) {
                SortedMap<String, Node> ordered =
                        new TreeMap<>(
                                owner.getProperties());
                for (Map.Entry<String, Node> property
                        : ordered.entrySet()) {
                    Node directChild =
                            directOwner.getProperties()
                                    .get(property.getKey());
                    collectNodeEdge(
                            ownerBlueId,
                            property.getValue(),
                            directChild,
                            JsonPointer.toPointer(
                                    Collections.singletonList(
                                            property.getKey())),
                            ownerAbsolutePath,
                            rootBlueId,
                            rootKind,
                            cuts,
                            scopePaths,
                            canonicalFragments,
                            occurrences,
                            active,
                            edgeQuota);
                }
            }
            collectSchemaEdges(
                    owner,
                    ownerBlueId,
                    directOwner,
                    ownerAbsolutePath,
                    rootBlueId,
                    rootKind,
                    cuts,
                    scopePaths,
                    canonicalFragments,
                    occurrences,
                    active,
                    edgeQuota);
        } finally {
            active.remove(owner);
        }
    }

    private static void collectSchemaEdges(
            Node owner,
            String ownerBlueId,
            Node directOwner,
            String ownerAbsolutePath,
            String rootBlueId,
            FragmentRootKind rootKind,
            Map<String, CutDescriptor> cuts,
            List<String> scopePaths,
            Map<String, Node> canonicalFragments,
            SortedMap<String, EdgeOccurrence> occurrences,
            Set<Node> active,
            EdgeQuota edgeQuota) {
        if (owner.getSchema() == null
                || owner.getSchema().isReferenceOnly()
                || directOwner.getSchema() == null) {
            return;
        }
        List<SchemaChild> children =
                Arrays.asList(
                        new SchemaChild(
                                "minimum",
                                owner.getSchema().getMinimum(),
                                directOwner.getSchema().getMinimum()),
                        new SchemaChild(
                                "maximum",
                                owner.getSchema().getMaximum(),
                                directOwner.getSchema().getMaximum()),
                        new SchemaChild(
                                "exclusiveMinimum",
                                owner.getSchema()
                                        .getExclusiveMinimum(),
                                directOwner.getSchema()
                                        .getExclusiveMinimum()),
                        new SchemaChild(
                                "exclusiveMaximum",
                                owner.getSchema()
                                        .getExclusiveMaximum(),
                                directOwner.getSchema()
                                        .getExclusiveMaximum()),
                        new SchemaChild(
                                "multipleOf",
                                owner.getSchema().getMultipleOf(),
                                directOwner.getSchema()
                                        .getMultipleOf()));
        for (SchemaChild child : children) {
            collectNodeEdge(
                    ownerBlueId,
                    child.original,
                    child.direct,
                    JsonPointer.toPointer(
                            Arrays.asList(
                                    "schema",
                                    child.key)),
                    ownerAbsolutePath,
                    rootBlueId,
                    rootKind,
                    cuts,
                    scopePaths,
                    canonicalFragments,
                    occurrences,
                    active,
                    edgeQuota);
        }
        if (owner.getSchema().getEnum() != null) {
            for (int index = 0;
                    index < owner.getSchema()
                    .getEnum().size();
                    index++) {
                collectNodeEdge(
                        ownerBlueId,
                        owner.getSchema().getEnum()
                                .get(index),
                        directOwner.getSchema().getEnum()
                                .get(index),
                        JsonPointer.toPointer(
                                Arrays.asList(
                                        "schema",
                                        "enum",
                                        String.valueOf(index))),
                        ownerAbsolutePath,
                        rootBlueId,
                        rootKind,
                        cuts,
                        scopePaths,
                        canonicalFragments,
                        occurrences,
                        active,
                        edgeQuota);
            }
        }
    }

    private static void collectNodeEdge(
            String ownerBlueId,
            Node originalChild,
            Node directChild,
            String ownerRelativePointer,
            String ownerAbsolutePath,
            String rootBlueId,
            FragmentRootKind rootKind,
            Map<String, CutDescriptor> cuts,
            List<String> scopePaths,
            Map<String, Node> canonicalFragments,
            SortedMap<String, EdgeOccurrence> occurrences,
            Set<Node> active,
            EdgeQuota edgeQuota) {
        if (directChild == null) {
            return;
        }
        if (!directChild.isReferenceOnly()) {
            /* ExactNodeGraphFragments may keep a small child inline in one
             * occurrence while retaining that same identity as a canonical
             * fragment because another occurrence is cut. Walk the inline
             * occurrence as provenance for the retained child's own direct
             * reference shape; there is deliberately no physical edge from
             * this owner to the inline child. */
            String inlineBlueId = originalChild != null
                    && !originalChild.isReferenceOnly()
                    ? exactIdentity(originalChild)
                    : null;
            if (inlineBlueId != null
                    && canonicalFragments.containsKey(inlineBlueId)) {
                collectDirectEdges(
                        originalChild,
                        inlineBlueId,
                        rootBlueId,
                        rootKind,
                        appendRelativePointer(
                                ownerAbsolutePath,
                                ownerRelativePointer),
                        cuts,
                        scopePaths,
                        canonicalFragments,
                        occurrences,
                        active,
                        edgeQuota);
            }
            return;
        }
        /* Canonical graph fragments may make an implicit scalar type explicit.
         * Such a physical reference has no original structural child. It is
         * complete identity evidence in its own right and remains opaque just
         * like an authored pure reference. */
        /* The direct reference and the matching fragment were emitted by the
         * same ExactNodeGraphFragments pass.  Its reference identity is the
         * established child identity; hashing the complete original child a
         * second time here used to make this metadata walk unnecessarily
         * expensive. */
        String childBlueId = directChild.getBlueId();
        String absolutePointer =
                appendRelativePointer(
                        ownerAbsolutePath,
                        ownerRelativePointer);
        CutDescriptor cut =
                cuts.get(
                        absolutePointer);
        EdgeKind edgeKind = cut != null
                ? cut.kind
                : rootKind == FragmentRootKind.EVENT
                ? EdgeKind.EVENT_DIRECT_CHILD
                : EdgeKind.DOCUMENT_DIRECT_CHILD;
        String ownerScopePath = cut != null
                ? cut.ownerScopePath
                : nearestScopePath(
                        scopePaths,
                        ownerAbsolutePath);
        EdgeOccurrence occurrence =
                new EdgeOccurrence(
                        FRAGMENTATION_PROFILE_ID,
                        EDGE_METADATA_SCHEMA_ID,
                        rootKind,
                        rootBlueId,
                        ownerBlueId,
                        ownerScopePath,
                        absolutePointer,
                        ownerRelativePointer,
                        childBlueId,
                        edgeKind,
                        originalChild == null
                                || originalChild.isReferenceOnly(),
                        originalChild != null
                                && !originalChild.isReferenceOnly(),
                        cut != null
                                ? cut.declaringScopePath
                                : null,
                        cut != null
                                ? cut.embeddedOrigin
                                : EmbeddedEdgeOrigin.NONE,
                        cut != null
                                ? cut.explicitDeclarationPath
                                : null,
                        cut != null
                                ? cut.collectionDeclarationPath
                                : null,
                        cut != null
                                ? cut.collectionMemberKey
                                : null,
                        cut != null
                                ? cut.handlerTypeBlueId
                                : null,
                        cut != null
                                ? cut.executableBodyField
                                : null,
                        cut != null
                                ? cut.sourceContributionBlueIds
                                : Collections
                                .<String>emptyList());
        String key = occurrence.ownerNodeBlueId()
                + '\u0000'
                + occurrence.absolutePointer()
                + '\u0000'
                + occurrence.childBlueId();
        EdgeOccurrence existing =
                occurrences.get(key);
        if (existing == null) {
            edgeQuota.record(
                    absolutePointer,
                    edgeKind);
            occurrences.put(
                    key,
                    occurrence);
        } else if (existing.rootKind()
                != FragmentRootKind.DOCUMENT
                && occurrence.rootKind()
                == FragmentRootKind.DOCUMENT) {
            occurrences.put(
                    key,
                    occurrence);
        } else if (!existing.physicallyEquivalent(
                occurrence)) {
            throw new IllegalStateException(
                    "One canonical direct edge has inconsistent occurrence "
                            + "metadata at "
                            + absolutePointer);
        }
        if (originalChild != null
                && !originalChild.isReferenceOnly()) {
            collectDirectEdges(
                    originalChild,
                    childBlueId,
                    rootBlueId,
                    rootKind,
                    absolutePointer,
                    cuts,
                    scopePaths,
                    canonicalFragments,
                    occurrences,
                    active,
                    edgeQuota);
        }
    }

    private static String appendRelativePointer(
            String base,
            String relative) {
        String result = base;
        for (String segment
                : JsonPointer.split(relative)) {
            result = JsonPointer.append(
                    result,
                    segment);
        }
        return result;
    }

    private static String nearestScopePath(
            List<String> scopePaths,
            String path) {
        String nearest = null;
        int depth = -1;
        for (String scopePath : scopePaths) {
            if (!PointerUtils.descendantOrEqual(
                    path,
                    scopePath)) {
                continue;
            }
            int candidateDepth =
                    JsonPointer.split(
                            scopePath).size();
            if (candidateDepth > depth) {
                nearest = scopePath;
                depth = candidateDepth;
            }
        }
        return nearest;
    }

    private static String sourceContributionBasePath(
            String blueId) {
        return JsonPointer.toPointer(
                Arrays.asList(
                        "source-contributions",
                        blueId));
    }

    private DocumentPlan discoverDocumentPlan(
            Node root,
            EffectiveFragmentationCatalog catalog,
            CoordinationHostQuotaSession hostQuotas) {
        SortedMap<String, ScopePlan> scopes =
                new TreeMap<>();
        List<EffectiveCutCatalogReader.ScopePlan> catalogScopes =
                EffectiveCutCatalogReader.read(catalog);
        List<String> scopePaths = new ArrayList<>();
        for (EffectiveCutCatalogReader.ScopePlan catalogScope
                : catalogScopes) {
            scopePaths.add(catalogScope.scopePath());
        }
        for (String scopePath : scopePaths) {
            hostQuotas.recordSplitterCatalogEntry(
                    scopePath,
                    "effective-scope");
            Node selected;
            if ("/".equals(scopePath)) {
                selected = root;
            } else {
                ScopePlan containingScope =
                        nearestDeclaredAncestor(
                                scopes, scopePath);
                selected = nodeAt(
                        containingScope.exactScope,
                        PointerUtils.relativizePointer(
                                containingScope.scopePath,
                                scopePath),
                        true,
                        "Effective Process Embedded scope "
                                + scopePath);
            }
            if (selected == null) {
                throw new IllegalStateException(
                        "Effective fragmentation catalog retained unavailable scope "
                                + scopePath);
            }
            if (selected.getRawValue() != null
                    || selected.getItems() != null) {
                throw new IllegalArgumentException(
                        "Effective Process Embedded scope "
                                + scopePath
                                + " must be an object Root");
            }
            scopes.put(
                    scopePath,
                    new ScopePlan(
                            scopePath, selected));
        }
        if (!scopes.containsKey("/")) {
            throw new IllegalStateException(
                    "Effective fragmentation catalog did not retain the exact Root scope");
        }

        for (EffectiveCutCatalogReader.ScopePlan catalogScope
                : catalogScopes) {
            for (EffectiveCutCatalogReader.EmbeddedOccurrence occurrence
                    : catalogScope.occurrences()) {
                String absolutePath = occurrence.concretePath();
                hostQuotas.recordSplitterCatalogEntry(
                        absolutePath,
                        "embedded-path");
                ScopePlan childScope = scopes.get(
                        absolutePath);
                if (childScope == null) {
                    continue;
                }
                ScopePlan containingScope =
                        nearestDeclaredAncestor(
                                scopes,
                                absolutePath);
                EmbeddedCut cut =
                        new EmbeddedCut(
                                containingScope.scopePath,
                                absolutePath,
                                occurrence.declaringScopePath(),
                                occurrence.origin(),
                                occurrence.explicitDeclarationPath(),
                                occurrence.collectionDeclarationPath(),
                                occurrence.collectionMemberKey());
                hostQuotas.recordSplitterCut(
                        absolutePath,
                        "embedded-root");
                containingScope.embeddedCuts.add(cut);
            }
        }

        List<BodyCut> bodies = new ArrayList<>();
        SortedMap<String, SourceContributionPlan>
                sourceContributions = new TreeMap<>();
        SortedMap<String, List<EffectiveContractSnapshot>>
                contractsByScope =
                new TreeMap<>(
                        catalog.effectiveContractsByScope());
        for (Map.Entry<String, List<EffectiveContractSnapshot>>
                contractsAtScope : contractsByScope.entrySet()) {
            ScopePlan scope =
                    scopes.get(
                            contractsAtScope.getKey());
            if (scope == null) {
                continue;
            }
            List<EffectiveContractSnapshot> contracts =
                    new ArrayList<>(
                            contractsAtScope.getValue());
            Collections.sort(
                    contracts,
                    Comparator.comparing(
                            EffectiveContractSnapshot::key));
            for (EffectiveContractSnapshot contract : contracts) {
                String contractPath =
                        PointerUtils.resolvePointer(
                                scope.scopePath,
                                JsonPointer.toPointer(
                                        Arrays.asList(
                                                "contracts",
                                                contract.key())));
                hostQuotas.recordSplitterCatalogEntry(
                        contractPath,
                        "effective-contract");
                SortedMap<String, String> bodyFields =
                        new TreeMap<>(
                                contract
                                        .executableBodyNodeBlueIdsByField());
                for (Map.Entry<String, String> body
                        : bodyFields.entrySet()) {
                    String contractPointer =
                            JsonPointer.toPointer(
                                    Arrays.asList(
                                            "contracts",
                                            contract.key()));
                    String relativePointer =
                            JsonPointer.toPointer(
                                    Arrays.asList(
                                            "contracts",
                                            contract.key(),
                                            body.getKey()));
                    String absolutePointer =
                            PointerUtils.resolvePointer(
                                    scope.scopePath,
                                    relativePointer);
                    hostQuotas.recordSplitterCatalogEntry(
                            absolutePointer,
                            "executable-body");
                    Node directBody =
                            NodePathEditor.getOrNull(
                                    scope.exactScope,
                                    relativePointer);
                    Node directContract =
                            NodePathEditor.getOrNull(
                                    scope.exactScope,
                                    contractPointer);
                    ResolvedEffectiveBody resolvedBody =
                            resolveEffectiveBody(
                            contract,
                            body.getKey(),
                            body.getValue(),
                            directContract,
                            directBody);
                    Node exactBody =
                            resolvedBody.exactBody;
                    BodyCut cut = new BodyCut(
                            scope.scopePath,
                            absolutePointer,
                            contract.effectiveTypeBlueId(),
                            body.getKey(),
                            exactBody,
                            contract
                                    .sourceContributionNodeBlueIds(),
                            resolvedBody.sourceContribution);
                    bodies.add(cut);
                    if (exactBody.isReferenceOnly()) {
                        /*
                         * Preserve an authored cold edge. Its occurrence is
                         * still described by edge metadata, but its content is
                         * not admitted as a local fragment.
                         */
                        continue;
                    }
                    hostQuotas.recordSplitterCut(
                            absolutePointer,
                            "executable-body");
                    if (resolvedBody.sourceContribution
                            != null) {
                        addSourceContributionCut(
                                sourceContributions,
                                resolvedBody
                                        .sourceContribution,
                                cut);
                    }
                }
            }
        }

        Collections.sort(
                bodies,
                Comparator.comparing(
                        body -> body.absolutePointer));
        return new DocumentPlan(
                scopes,
                bodies,
                sourceContributions,
                contractsByScope);
    }

    private ResolvedEffectiveBody resolveEffectiveBody(
            EffectiveContractSnapshot contract,
            String field,
            String expectedBodyBlueId,
            Node directContract,
            Node directBody) {
        Objects.requireNonNull(
                expectedBodyBlueId,
                "expectedBodyBlueId");
        ExecutableBodySourceDescriptor descriptor =
                contract
                        .executableBodySourceDescriptorsByField()
                        .get(field);
        if (descriptor != null) {
            return resolveDescribedEffectiveBody(
                    contract,
                    field,
                    expectedBodyBlueId,
                    directContract,
                    descriptor);
        }
        if (directBody != null
                && expectedBodyBlueId.equals(
                exactIdentity(directBody))) {
            return ResolvedEffectiveBody.inScope(
                    directBody);
        }

        String directContributionBlueId =
                directContract != null
                        ? exactIdentity(directContract)
                        : null;
        List<String> contributions =
                contract
                        .sourceContributionNodeBlueIds();
        for (int index = contributions.size() - 1;
                index >= 0;
                index--) {
            String contributionBlueId =
                    contributions.get(index);
            Node contribution;
            if (contributionBlueId.equals(
                    directContributionBlueId)) {
                contribution = directContract.isReferenceOnly()
                        ? exactContent(
                                directContract,
                                "Effective contract '"
                                        + contract.key()
                                        + "' direct Source contribution "
                                        + contributionBlueId,
                                true)
                        : directContract;
            } else {
                contribution = exactContent(
                        new Node().blueId(
                                contributionBlueId),
                        "Effective contract '"
                                + contract.key()
                                + "' source contribution "
                                + contributionBlueId,
                        true);
            }
            Node candidate =
                    NodePathEditor.getOrNull(
                            contribution,
                            JsonPointer.toPointer(
                                    Collections.singletonList(
                                            field)));
            if (candidate == null) {
                continue;
            }
            String candidateBlueId =
                    exactIdentity(candidate);
            if (!expectedBodyBlueId.equals(
                    candidateBlueId)) {
                throw new IllegalStateException(
                        "Effective fragmentation catalog body "
                                + expectedBodyBlueId
                                + " for contract '"
                                + contract.key()
                                + "' field '"
                                + field
                                + "' disagrees with its most-derived Source "
                                + "contribution "
                                + contributionBlueId
                                + " body "
                                + candidateBlueId);
            }
            if (!candidate.isReferenceOnly()) {
                throw new IllegalStateException(
                        "Effective executable body "
                                + expectedBodyBlueId
                                + " for contract '"
                                + contract.key()
                                + "' field '"
                                + field
                                + "' is inline in inherited Source contribution "
                                + contributionBlueId
                                + ", but EffectiveFragmentationCatalog exposes "
                                + "no exact source location that can be replaced "
                                + "without reimplementing Language inheritance");
            }
            return ResolvedEffectiveBody.inScope(
                    candidate);
        }

        if (directBody != null) {
            throw new IllegalStateException(
                    "Direct authored body "
                            + exactIdentity(directBody)
                            + " for contract '"
                            + contract.key()
                            + "' field '"
                            + field
                            + "' does not match effective catalog body "
                                + expectedBodyBlueId);
        }
        throw new IllegalStateException(
                "Effective fragmentation catalog declares body "
                        + expectedBodyBlueId
                        + " for contract '"
                        + contract.key()
                        + "' field '"
                        + field
                        + "' but no ordered Source contribution declares it");
    }

    private ResolvedEffectiveBody resolveDescribedEffectiveBody(
            EffectiveContractSnapshot contract,
            String field,
            String expectedBodyBlueId,
            Node directContract,
            ExecutableBodySourceDescriptor descriptor) {
        if (!expectedBodyBlueId.equals(
                descriptor.bodyNodeBlueId())) {
            throw new IllegalStateException(
                    "Effective executable-body Source descriptor for contract '"
                            + contract.key()
                            + "' field '"
                            + field
                            + "' changed body identity from "
                            + expectedBodyBlueId
                            + " to "
                            + descriptor.bodyNodeBlueId());
        }
        String ownerBlueId =
                descriptor
                        .owningSourceContributionNodeBlueId();
        if (!descriptor
                .sourceContributionNodeBlueIds()
                .contains(ownerBlueId)) {
            throw new IllegalStateException(
                    "Effective executable-body Source descriptor for contract '"
                            + contract.key()
                            + "' field '"
                            + field
                            + "' names an owner outside its ordered Source contributions: "
                            + ownerBlueId);
        }
        if (descriptor.pureReference()) {
            /*
             * Language already proved the exact source binding. Keeping the
             * body cold must not demand either the owning contribution or the
             * referenced body merely to rediscover that binding.
             */
            return ResolvedEffectiveBody.inScope(
                    new Node().blueId(
                            expectedBodyBlueId));
        }

        String directContributionBlueId =
                directContract != null
                        ? exactIdentity(directContract)
                        : null;
        boolean ownerIsInlineDirectContribution =
                ownerBlueId.equals(
                        directContributionBlueId)
                        && directContract != null
                        && !directContract.isReferenceOnly();
        Node owner = ownerIsInlineDirectContribution
                ? directContract
                : exactContent(
                        new Node().blueId(ownerBlueId),
                        "Effective contract '"
                                + contract.key()
                                + "' executable-body owning Source contribution "
                                + ownerBlueId,
                        true);
        Node exactBody = nodeAt(
                owner,
                descriptor.sourcePointer(),
                true,
                "Effective contract '" + contract.key()
                        + "' executable-body Source contribution "
                        + ownerBlueId);
        if (exactBody == null) {
            throw new IllegalStateException(
                    "Effective executable-body Source descriptor for contract '"
                            + contract.key()
                            + "' field '"
                            + field
                            + "' points to unavailable Source location "
                            + descriptor.sourcePointer()
                            + " in "
                            + ownerBlueId);
        }
        String actualBodyBlueId =
                exactIdentity(exactBody);
        if (!expectedBodyBlueId.equals(
                actualBodyBlueId)) {
            throw new IllegalStateException(
                    "Effective executable-body Source descriptor for contract '"
                            + contract.key()
                            + "' field '"
                            + field
                            + "' expected body "
                            + expectedBodyBlueId
                            + " at "
                            + ownerBlueId
                            + descriptor.sourcePointer()
                            + " but found "
                            + actualBodyBlueId);
        }
        if (exactBody.isReferenceOnly()) {
            throw new IllegalStateException(
                    "Effective executable-body Source descriptor for contract '"
                            + contract.key()
                            + "' field '"
                            + field
                            + "' declares inline content but "
                            + ownerBlueId
                            + descriptor.sourcePointer()
                            + " is a pure reference");
        }
        if (ownerIsInlineDirectContribution) {
            return ResolvedEffectiveBody.inScope(
                    exactBody);
        }
        Node exactOwner = owner.clone();
        NodePathEditor.put(
                exactOwner,
                descriptor.sourcePointer(),
                exactBody.clone());
        requireIdentity(
                ownerBlueId,
                exactOwner,
                "Materialized executable-body Source contribution");
        return ResolvedEffectiveBody.inSourceContribution(
                exactBody,
                new SourceContributionCut(
                        ownerBlueId,
                        exactOwner,
                        descriptor.sourcePointer()));
    }

    private static void addSourceContributionCut(
            SortedMap<String, SourceContributionPlan>
                    sourceContributions,
            SourceContributionCut sourceCut,
            BodyCut bodyCut) {
        SourceContributionPlan plan =
                sourceContributions.get(
                        sourceCut.blueId);
        if (plan == null) {
            plan = new SourceContributionPlan(
                    sourceCut.blueId,
                    sourceCut.exactContribution);
            sourceContributions.put(
                    sourceCut.blueId,
                    plan);
        } else {
            requireIdentity(
                    sourceCut.blueId,
                    sourceCut.exactContribution,
                    "Repeated executable-body Source contribution "
                            + sourceCut.blueId);
        }
        SourceBodyCut existing =
                plan.bodyCuts.get(
                        sourceCut.sourcePointer);
        String bodyBlueId =
                exactIdentity(
                        bodyCut.exactBody);
        if (existing != null
                && !bodyBlueId.equals(
                exactIdentity(
                        existing.exactBody))) {
            throw new IllegalStateException(
                    "Executable-body Source contribution "
                            + sourceCut.blueId
                            + " assigns different bodies to "
                            + sourceCut.sourcePointer);
        }
        plan.bodyCuts.put(
                sourceCut.sourcePointer,
                new SourceBodyCut(
                        sourceCut.sourcePointer,
                        bodyCut.exactBody));
    }

    private static String exactIdentity(
            Node node) {
        return node.isReferenceOnly()
                ? node.getBlueId()
                : DirectBlueIdCalculator.calculateBlueId(
                        node);
    }

    private static ScopePlan nearestDeclaredAncestor(
            SortedMap<String, ScopePlan> scopes,
            String absolutePath) {
        ScopePlan nearest = null;
        int nearestDepth = -1;
        for (ScopePlan candidate : scopes.values()) {
            if (!PointerUtils.strictlyInside(
                    absolutePath,
                    candidate.scopePath)) {
                continue;
            }
            int candidateDepth =
                    JsonPointer.split(
                            candidate.scopePath)
                            .size();
            if (candidateDepth > nearestDepth) {
                nearest = candidate;
                nearestDepth = candidateDepth;
            }
        }
        if (nearest == null) {
            throw new IllegalStateException(
                    "Process Embedded child "
                            + absolutePath
                            + " has no declared ancestor scope");
        }
        return nearest;
    }


    private Node exactContent(
            Node node,
            String label,
            boolean materializeReference) {
        Node checked =
                Objects.requireNonNull(node, label)
                        .clone();
        if (!checked.isReferenceOnly()
                || !materializeReference) {
            return checked;
        }
        if (localProvider == null) {
            throw new IllegalStateException(
                    label
                            + " is a pure reference; construct the splitter "
                            + "with the exact local NodeProvider");
        }

        String expectedBlueId =
                BlueIds.requirePlainBlueId(
                        checked.getBlueId(),
                        label + ".blueId");
        NodeProviderResult result =
                localProvider.fetchResultByBlueId(
                        expectedBlueId);
        return exactProviderContent(
                expectedBlueId,
                result,
                label);
    }

    private NodeProvider requiredLocalProvider(String label) {
        if (localProvider == null) {
            throw new IllegalStateException(
                    "Exact local provider is required to materialize "
                            + label);
        }
        return localProvider;
    }

    private static Node exactProviderContent(
            String expectedBlueId,
            NodeProviderResult result,
            String label) {
        if (result.outcome()
                != NodeProviderOutcome.FOUND) {
            throw new IllegalStateException(
                    "Cannot materialize "
                            + label
                            + " from the exact local provider: "
                            + result.outcome()
                            + " ("
                            + result.diagnostic().orElse(
                                    "no diagnostic")
                            + ")");
        }
        List<Node> nodes = result.nodes();
        if (nodes.size() != 1) {
            throw new IllegalStateException(
                    "Exact local provider returned "
                            + nodes.size()
                            + " nodes for "
                            + label
                            + " "
                            + expectedBlueId);
        }

        Node exact = nodes.get(0).clone();
        if (exact.isReferenceOnly()) {
            throw new IllegalStateException(
                    "Exact local provider returned another pure reference for "
                            + label
                            + " "
                            + expectedBlueId);
        }
        if (exact.getBlueId() != null) {
            if (!expectedBlueId.equals(
                    exact.getBlueId())) {
                throw new IllegalStateException(
                        "Exact local provider returned root identity "
                                + exact.getBlueId()
                                + " for requested "
                                + expectedBlueId);
            }
            exact.blueId(null);
        }
        requireIdentity(
                expectedBlueId,
                exact,
                "Exact local provider content for "
                        + label);
        return exact;
    }

    private Node optionalExactProviderContent(
            Node reference,
            String label) {
        if (reference == null
                || !reference.isReferenceOnly()
                || localProvider == null) {
            return null;
        }
        String expectedBlueId =
                BlueIds.requirePlainBlueId(
                        reference.getBlueId(),
                        label + ".blueId");
        NodeProviderResult result =
                localProvider.fetchResultByBlueId(
                        expectedBlueId);
        if (result.outcome()
                == NodeProviderOutcome.NOT_FOUND) {
            return null;
        }
        return exactProviderContent(
                expectedBlueId,
                result,
                label);
    }

    private Node nodeAt(
            Node root,
            String pointer,
            boolean materializeFinalReference,
            String label) {
        Node current =
                Objects.requireNonNull(root, "root");
        List<String> segments =
                JsonPointer.split(pointer);
        String traversed = "/";
        for (String segment : segments) {
            /* Traversal is read-only. Cloning each ancestor duplicates its
             * complete remaining subtree at every path segment and makes
             * scope discovery quadratic in depth. Only provider-backed
             * references and the final returned selection require copies. */
            if (current.isReferenceOnly()) {
                current = exactContent(
                        current,
                        label + " at " + traversed,
                        true);
            }
            current = NodePathEditor.getOrNull(
                    current,
                    JsonPointer.toPointer(
                            Collections.singletonList(
                                    segment)));
            if (current == null) {
                return null;
            }
            traversed =
                    JsonPointer.append(
                            traversed,
                            segment);
        }
        return exactContent(
                current,
                label + " at " + traversed,
                materializeFinalReference);
    }

    private NodeProvider composedProvider(
            Map<String, Node> fragments,
            Map<String, Node> processHeaderViews) {
        NodeProvider headers =
                verifiedProvider(
                        processHeaderViews);
        NodeProvider generated =
                verifiedProvider(fragments);
        NodeProvider generatedWithHeaders =
                new SequentialNodeProvider(
                        headers,
                        generated);
        if (localProvider == null) {
            return generatedWithHeaders;
        }
        /*
         * The exact PROCESS header view wins only for registered contract
         * contribution identities. Canonical generated fragments win for
         * every other retained identity. The exact local provider remains a
         * verified fallback for unchanged authored references that were
         * deliberately left lazy.
         */
        return new SequentialNodeProvider(
                generatedWithHeaders,
                localProvider);
    }

    private Map<String, Node> processHeaderViews(
            DocumentPlan plan,
            Collection<? extends Node> exactRoots,
            CoordinationExactNodeIndex exactNodeIndex) {
        CoordinationExactNodeIndex index = Objects.requireNonNull(
                exactNodeIndex, "exactNodeIndex");
        for (Node exactRoot : exactRoots) {
            index.blueId(exactRoot);
        }
        Map<String, Node> exactNodes = index.nodesByBlueId();

        SortedMap<String, Set<String>>
                executableFieldsByContribution =
                new TreeMap<String, Set<String>>();
        for (List<EffectiveContractSnapshot> contracts
                : plan.contractsByScope.values()) {
            for (EffectiveContractSnapshot contract
                    : contracts) {
                boolean channel =
                        EffectiveContractSnapshotConstants
                                .Role.EXTERNAL_CHANNEL.equals(
                                        contract.role())
                                || EffectiveContractSnapshotConstants
                                .Role.PROCESSOR_CHANNEL.equals(
                                        contract.role());
                boolean handler =
                        EffectiveContractSnapshotConstants
                                .Role.HANDLER.equals(
                                        contract.role());
                boolean processEmbedded =
                        EffectiveContractSnapshotConstants
                                .Role.PROCESS_EMBEDDED.equals(
                                        contract.role());
                if (!channel
                        && !handler
                        && !processEmbedded) {
                    continue;
                }
                for (String contribution
                        : contract
                        .sourceContributionNodeBlueIds()) {
                    if (!exactNodes.containsKey(
                            contribution)
                            && canKeepProviderHeaderCold(
                                    contract,
                                    contribution)) {
                        continue;
                    }
                    Set<String> fields =
                            executableFieldsByContribution
                                    .computeIfAbsent(
                                            contribution,
                                            ignored ->
                                                    new TreeSet<String>());
                    if (handler) {
                        fields.addAll(
                                contract
                                        .executableBodyFields());
                    }
                }
            }
        }
        indexProviderBackedContractContributions(
                exactRoots,
                executableFieldsByContribution.keySet(),
                index);
        exactNodes = index.nodesByBlueId();

        SortedMap<String, Node> result =
                new TreeMap<String, Node>();
        Map<String, Node> materializedHeaders =
                new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Set<String>> entry
                : executableFieldsByContribution.entrySet()) {
            String blueId = entry.getKey();
            Node exact = exactNodes.get(blueId);
            if (exact == null) {
                if (localProvider == null) {
                    throw new IllegalStateException(
                            "Registered Coordination contract header "
                                    + blueId
                                    + " is unavailable for the PROCESS "
                                    + "header view");
                }
                exact = exactContent(
                        new Node().blueId(blueId),
                        "Registered Coordination contract header "
                                + blueId,
                        true);
            }
            Node header = exact.clone();
            for (String field : entry.getValue()) {
                Node body =
                        header.getProperties() != null
                                ? header.getProperties()
                                .get(field)
                                : null;
                if (body == null
                        || body.isReferenceOnly()) {
                    continue;
                }
                header.getProperties().put(
                        field,
                        new Node().blueId(
                                DirectBlueIdCalculator
                                        .calculateBlueId(
                                                body)));
            }
            materializeHeaderProperties(
                    header,
                    entry.getValue(),
                    materializedHeaders,
                    new LinkedHashSet<String>(),
                    "PROCESS contract header " + blueId);
            requireIdentity(
                    blueId,
                    header,
                    "PROCESS contract header view");
            result.put(blueId, header);
        }
        addProcessContractsViews(
                plan,
                result,
                index);
        addProcessScopeViews(
                plan,
                result,
                index);
        addProcessExecutableBodyViews(
                plan,
                result,
                index);
        return Collections.unmodifiableSortedMap(
                result);
    }

    private static boolean canKeepProviderHeaderCold(
            EffectiveContractSnapshot contract,
            String contributionBlueId) {
        if (!EffectiveContractSnapshotConstants
                .Role.HANDLER.equals(
                        contract.role())
                || contract.executableBodyFields()
                .isEmpty()) {
            return false;
        }
        for (String field
                : contract.executableBodyFields()) {
            ExecutableBodySourceDescriptor descriptor =
                    contract
                            .executableBodySourceDescriptorsByField()
                            .get(field);
            if (descriptor == null
                    || !descriptor.pureReference()
                    || !contributionBlueId.equals(
                            descriptor
                                    .owningSourceContributionNodeBlueId())) {
                return false;
            }
        }
        return true;
    }

    private void addProcessContractsViews(
            DocumentPlan plan,
            SortedMap<String, Node> processViews,
            CoordinationExactNodeIndex exactNodeIndex) {
        for (ScopePlan scope : plan.scopes.values()) {
            Node suppliedContracts =
                    scope.exactScope.getContracts();
            if (suppliedContracts == null) {
                continue;
            }
            String contractsBlueId =
                    exactIdentity(
                            suppliedContracts);
            Node exactContracts =
                    suppliedContracts.isReferenceOnly()
                            ? CoordinationProcessHeaderBridge
                            .materializeVerifiedExactReference(
                                    requiredLocalProvider(
                                            "contracts map at "
                                                    + scope.scopePath),
                                    suppliedContracts)
                            : suppliedContracts.clone();
            if (exactContracts.getBlueId() != null) {
                if (!contractsBlueId.equals(
                        exactContracts.getBlueId())) {
                    throw new IllegalStateException(
                            "Verified PROCESS contracts map changed "
                                    + contractsBlueId + " to "
                                    + exactContracts.getBlueId()
                                    + " at " + scope.scopePath);
                }
                exactContracts.blueId(null);
            }
            requireIdentity(
                    contractsBlueId,
                    exactContracts,
                    "Verified PROCESS contracts map at "
                            + scope.scopePath);

            Node contractsView =
                    exactNodeIndex.directFragment(exactContracts);
            if (exactContracts.getProperties() != null) {
                for (Map.Entry<String, Node> contract
                        : exactContracts
                        .getProperties()
                        .entrySet()) {
                    String contributionBlueId =
                            exactIdentity(
                                    contract.getValue());
                    Node header =
                            processViews.get(
                                    contributionBlueId);
                    EffectiveContractSnapshot snapshot =
                            effectiveContractSnapshot(
                                    plan,
                                    scope.scopePath,
                                    contract.getKey());
                    if (header == null
                            && (isProcessHeaderRole(
                                    snapshot)
                            || ProcessorContractConstants
                            .isReservedKey(
                                    contract.getKey()))) {
                        Node exactContribution =
                                contract.getValue()
                                        .isReferenceOnly()
                                        ? CoordinationProcessHeaderBridge
                                        .materializeVerifiedExactReference(
                                                requiredLocalProvider(
                                                        "contract contribution at "
                                                                + scope.scopePath
                                                                + "/"
                                                                + contract.getKey()),
                                                contract.getValue())
                                        : contract.getValue()
                                        .clone();
                        header =
                                processHeaderView(
                                        contributionBlueId,
                                        exactContribution,
                                        snapshot != null
                                                ? snapshot
                                                .executableBodyFields()
                                                : Collections
                                                .<String>emptyList(),
                                        snapshot != null
                                                && !EffectiveContractSnapshotConstants
                                                .Role.MARKER.equals(
                                                        snapshot.role()),
                                        "PROCESS direct contract "
                                                + scope.scopePath
                                                + "/"
                                                + contract.getKey());
                        processViews.put(
                                contributionBlueId,
                                header);
                    }
                    if (header != null) {
                        contractsView.getProperties().put(
                                contract.getKey(),
                                header.clone());
                    }
                }
            }
            requireIdentity(
                    contractsBlueId,
                    contractsView,
                    "PROCESS contracts-map view at "
                            + scope.scopePath);
            Node previous =
                    processViews.put(
                            contractsBlueId,
                            contractsView);
            if (previous != null
                    && !Objects.equals(
                            NodeWireForm.get(
                                    previous),
                            NodeWireForm.get(
                                    contractsView))) {
                throw new IllegalStateException(
                        "One PROCESS contracts-map identity has "
                                + "inconsistent registered header views: "
                                + contractsBlueId);
            }
        }
    }

    private static EffectiveContractSnapshot
    effectiveContractSnapshot(
            DocumentPlan plan,
            String scopePath,
            String key) {
        List<EffectiveContractSnapshot> contracts =
                plan.contractsByScope.get(
                        scopePath);
        if (contracts == null) {
            return null;
        }
        for (EffectiveContractSnapshot contract
                : contracts) {
            if (contract.key().equals(key)) {
                return contract;
            }
        }
        return null;
    }

    private static boolean isProcessHeaderRole(
            EffectiveContractSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        String role = snapshot.role();
        return EffectiveContractSnapshotConstants
                .Role.EXTERNAL_CHANNEL.equals(role)
                || EffectiveContractSnapshotConstants
                .Role.PROCESSOR_CHANNEL.equals(role)
                || EffectiveContractSnapshotConstants
                .Role.HANDLER.equals(role)
                || EffectiveContractSnapshotConstants
                .Role.PROCESS_EMBEDDED.equals(role);
    }

    private Node processHeaderView(
            String expectedBlueId,
            Node exactContribution,
            Collection<String> executableFields,
            boolean materializeProperties,
            String label) {
        Node header =
                exactContribution.clone();
        Set<String> excluded =
                new TreeSet<String>(
                        executableFields);
        for (String field : excluded) {
            Node body =
                    header.getProperties() != null
                            ? header.getProperties()
                            .get(field)
                            : null;
            if (body == null
                    || body.isReferenceOnly()) {
                continue;
            }
            header.getProperties().put(
                    field,
                    new Node().blueId(
                            DirectBlueIdCalculator
                                    .calculateBlueId(
                                            body)));
        }
        if (materializeProperties) {
            materializeHeaderProperties(
                    header,
                    excluded,
                    new LinkedHashMap<String, Node>(),
                    new LinkedHashSet<String>(),
                    label);
        }
        requireIdentity(
                expectedBlueId,
                header,
                label);
        return header;
    }

    private void addProcessScopeViews(
            DocumentPlan plan,
            SortedMap<String, Node> processViews,
            CoordinationExactNodeIndex exactNodeIndex) {
        SortedMap<String, Node> standaloneByPath =
                new TreeMap<String, Node>();
        for (ScopePlan scope : plan.scopes.values()) {
            String scopeBlueId =
                    exactNodeIndex.blueId(scope.exactScope);
            Node scopeView =
                    exactNodeIndex.directFragment(scope.exactScope);
            Node suppliedContracts =
                    scope.exactScope.getContracts();
            if (suppliedContracts != null) {
                String contractsBlueId =
                        exactIdentity(
                                suppliedContracts);
                Node contractsView =
                        processViews.get(
                                contractsBlueId);
                if (contractsView == null) {
                    throw new IllegalStateException(
                            "PROCESS contracts-map view is missing for "
                                    + scope.scopePath + " at "
                                    + contractsBlueId);
                }
                scopeView.contracts(
                        contractsView.clone());
            }
            requireIdentity(
                    scopeBlueId,
                    scopeView,
                    "PROCESS participating-scope view at "
                            + scope.scopePath);
            standaloneByPath.put(
                    scope.scopePath,
                    scopeView);
            Node previous =
                    processViews.put(
                            scopeBlueId,
                            scopeView);
            if (previous != null
                    && !Objects.equals(
                            NodeWireForm.get(
                                    previous),
                            NodeWireForm.get(
                                    scopeView))) {
                throw new IllegalStateException(
                        "One PROCESS scope identity has inconsistent "
                                + "header views: " + scopeBlueId);
            }
        }

        List<ScopePlan> deepestFirst =
                new ArrayList<ScopePlan>(
                        plan.scopes.values());
        Collections.sort(
                deepestFirst,
                Comparator
                        .comparingInt(
                                (ScopePlan scope) ->
                                        JsonPointer.split(
                                                scope.scopePath)
                                                .size())
                        .reversed()
                        .thenComparing(
                                scope -> scope.scopePath));
        SortedMap<String, Node> expandedByPath =
                new TreeMap<String, Node>();
        for (ScopePlan scope : deepestFirst) {
            Node expanded =
                    standaloneByPath.get(
                            scope.scopePath)
                            .clone();
            List<EmbeddedCut> cuts =
                    new ArrayList<EmbeddedCut>(
                            scope.embeddedCuts);
            Collections.sort(
                    cuts,
                    Comparator.comparing(
                            cut -> cut.absolutePointer));
            for (EmbeddedCut cut : cuts) {
                Node child =
                        expandedByPath.get(
                                cut.absolutePointer);
                if (child == null) {
                    throw new IllegalStateException(
                            "PROCESS scope view is missing declared child "
                                    + cut.absolutePointer);
                }
                inlineProcessScopePath(
                        expanded,
                        scope.exactScope,
                        PointerUtils.relativizePointer(
                                scope.scopePath,
                                cut.absolutePointer),
                        child,
                        scope.scopePath,
                        exactNodeIndex);
            }
            requireIdentity(
                    exactIdentity(
                            scope.exactScope),
                    expanded,
                    "Expanded PROCESS participating-scope view at "
                            + scope.scopePath);
            expandedByPath.put(
                    scope.scopePath,
                    expanded);
        }

        ScopePlan root =
                plan.scopes.get("/");
        Node processingRoot =
                expandedByPath.get("/");
        if (root == null
                || processingRoot == null) {
            throw new IllegalStateException(
                    "PROCESS scope views contain no Root");
        }
        processViews.put(
                exactIdentity(root.exactScope),
                processingRoot);
        addExpandedStructuralViews(
                processingRoot,
                processViews);
    }

    /**
     * Retains identity-equivalent body-free views for intermediate collection
     * and object containers on the expanded selector-catalog spine.
     *
     * <p>Language may verify an indexed plan by demanding one of these direct
     * container identities. Returning the expanded header view lets that
     * verification observe member headers without separately opening every
     * unselected embedded Root. Existing specialized contract/body views win
     * over this general structural projection.</p>
     */
    private static void addExpandedStructuralViews(
            Node expandedRoot,
            SortedMap<String, Node> processViews) {
        CoordinationExactNodeIndex expandedIndex =
                new CoordinationExactNodeIndex();
        expandedIndex.blueId(expandedRoot);
        Map<String, Node> expanded = expandedIndex.nodesByBlueId();
        for (Map.Entry<String, Node> entry : expanded.entrySet()) {
            processViews.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private void inlineProcessScopePath(
            Node ownerView,
            Node exactOwner,
            String relativePointer,
            Node childView,
            String ownerScopePath,
            CoordinationExactNodeIndex exactNodeIndex) {
        List<String> segments =
                JsonPointer.split(
                        relativePointer);
        if (segments.isEmpty()) {
            throw new IllegalStateException(
                    "PROCESS scope cannot embed itself at "
                            + ownerScopePath);
        }
        Node currentView = ownerView;
        for (int index = 0;
                index < segments.size();
                index++) {
            String segment =
                    segments.get(index);
            String oneSegment =
                    JsonPointer.toPointer(
                            Collections.singletonList(
                                    segment));
            if (index == segments.size() - 1) {
                NodePathEditor.put(
                        currentView,
                        oneSegment,
                        childView.clone());
                continue;
            }
            Node nextView =
                    NodePathEditor.getOrNull(
                            currentView,
                            oneSegment);
            if (nextView == null
                    || nextView.isReferenceOnly()) {
                String prefix =
                        JsonPointer.toPointer(
                                segments.subList(
                                        0,
                                        index + 1));
                Node exactIntermediate =
                        nodeAt(
                                exactOwner,
                                prefix,
                                true,
                                "PROCESS scope-chain node at "
                                        + PointerUtils
                                        .resolvePointer(
                                                ownerScopePath,
                                                prefix));
                if (exactIntermediate == null) {
                    throw new IllegalStateException(
                            "PROCESS scope-chain node is absent at "
                                    + PointerUtils.resolvePointer(
                                    ownerScopePath,
                                    prefix));
                }
                nextView = exactNodeIndex.directFragment(
                        exactIntermediate);
                requireIdentity(
                        exactIdentity(
                                exactIntermediate),
                        nextView,
                        "PROCESS scope-chain view at "
                                + PointerUtils.resolvePointer(
                                ownerScopePath,
                                prefix));
                NodePathEditor.put(
                        currentView,
                        oneSegment,
                        nextView);
            }
            currentView = nextView;
        }
    }

    private static void addProcessExecutableBodyViews(
            DocumentPlan plan,
            SortedMap<String, Node> processViews,
            CoordinationExactNodeIndex exactNodeIndex) {
        for (BodyCut body : plan.bodies) {
            if (body.exactBody == null
                    || body.exactBody.isReferenceOnly()) {
                continue;
            }
            String bodyBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            body.exactBody);
            Node canonicalExactBody =
                    CoordinationProcessHeaderBridge
                            .canonicalExactCopy(
                                    body.exactBody);
            requireIdentity(
                    bodyBlueId,
                    canonicalExactBody,
                    "canonical PROCESS executable-body view at "
                            + body.absolutePointer);
            Node bodyView =
                    exactNodeIndex.directFragment(canonicalExactBody);
            if (canonicalExactBody.getProperties() != null) {
                Map<String, Node> properties =
                        new LinkedHashMap<String, Node>();
                for (Map.Entry<String, Node> property
                        : canonicalExactBody.getProperties().entrySet()) {
                    properties.put(
                            property.getKey(),
                            property.getValue() != null
                                    ? property.getValue().clone()
                                    : null);
                }
                bodyView.properties(properties);
            }
            if (canonicalExactBody.getItems() != null) {
                List<Node> items =
                        new ArrayList<Node>();
                for (Node exactItem :
                        canonicalExactBody.getItems()) {
                    if (exactItem == null
                            || exactItem.isReferenceOnly()) {
                        items.add(
                                exactItem != null
                                        ? exactItem.clone()
                                        : null);
                    } else {
                        items.add(
                                CoordinationProcessHeaderBridge
                                        .canonicalExactCopy(
                                                exactItem));
                    }
                }
                bodyView.items(items);
            }
            requireIdentity(
                    bodyBlueId,
                    bodyView,
                    "PROCESS executable-body view at "
                            + body.absolutePointer);
            Node previous =
                    processViews.put(
                            bodyBlueId,
                            bodyView);
            if (previous != null
                    && !Objects.equals(
                            NodeWireForm.get(
                                    previous),
                            NodeWireForm.get(
                                    bodyView))) {
                throw new IllegalStateException(
                        "One PROCESS executable-body identity has "
                                + "inconsistent exact-item views: "
                                + bodyBlueId);
            }
        }
    }

    private void materializeHeaderProperties(
            Node header,
            Set<String> excludedRootProperties,
            Map<String, Node> memoized,
            Set<String> activeBlueIds,
            String label) {
        if (header.getProperties() == null) {
            return;
        }
        List<String> names =
                new ArrayList<String>(
                        header.getProperties().keySet());
        Collections.sort(names);
        for (String name : names) {
            if (excludedRootProperties.contains(name)) {
                continue;
            }
            Node value =
                    header.getProperties().get(name);
            header.getProperties().put(
                    name,
                    materializeHeaderValue(
                            value,
                            memoized,
                            activeBlueIds,
                            label + JsonPointer.toPointer(
                                    Collections.singletonList(
                                            name))));
        }
    }

    private Node materializeHeaderValue(
            Node supplied,
            Map<String, Node> memoized,
            Set<String> activeBlueIds,
            String label) {
        if (supplied == null) {
            return null;
        }
        Node exact = supplied.clone();
        String demandedBlueId = null;
        if (exact.isReferenceOnly()) {
            demandedBlueId =
                    BlueIds.requirePlainBlueId(
                            exact.getBlueId(),
                            label + ".blueId");
            Node retained =
                    memoized.get(demandedBlueId);
            if (retained != null) {
                return retained.clone();
            }
            if (!activeBlueIds.add(demandedBlueId)) {
                throw new IllegalStateException(
                        "Cyclic non-executable PROCESS header reference at "
                                + label + " for " + demandedBlueId);
            }
            exact =
                    CoordinationProcessHeaderBridge
                            .materializeVerifiedExactReference(
                                    requiredLocalProvider(label),
                                    exact);
            if (exact.getBlueId() != null) {
                if (!demandedBlueId.equals(
                        exact.getBlueId())) {
                    throw new IllegalStateException(
                            "Verified PROCESS header evidence changed "
                                    + demandedBlueId + " to "
                                    + exact.getBlueId()
                                    + " at " + label);
                }
                exact.blueId(null);
            }
            requireIdentity(
                    demandedBlueId,
                    exact,
                    "Verified PROCESS header evidence at " + label);
        }

        if (exact.getProperties() != null) {
            List<String> names =
                    new ArrayList<String>(
                            exact.getProperties().keySet());
            Collections.sort(names);
            for (String name : names) {
                exact.getProperties().put(
                        name,
                        materializeHeaderValue(
                                exact.getProperties().get(name),
                                memoized,
                                activeBlueIds,
                                label + JsonPointer.toPointer(
                                        Collections.singletonList(
                                                name))));
            }
        }
        if (exact.getItems() != null) {
            for (int index = 0;
                    index < exact.getItems().size();
                    index++) {
                exact.getItems().set(
                        index,
                        materializeHeaderValue(
                                exact.getItems().get(index),
                                memoized,
                                activeBlueIds,
                                label + JsonPointer.toPointer(
                                        Collections.singletonList(
                                                String.valueOf(index)))));
            }
        }

        if (demandedBlueId != null) {
            requireIdentity(
                    demandedBlueId,
                    exact,
                    "Materialized PROCESS header value at " + label);
            activeBlueIds.remove(demandedBlueId);
            memoized.put(
                    demandedBlueId,
                    exact.clone());
        }
        return exact;
    }

    private void indexProviderBackedContractContributions(
            Collection<? extends Node> exactRoots,
            Collection<String> requiredContributionBlueIds,
            CoordinationExactNodeIndex exactNodeIndex) {
        if (localProvider == null
                || requiredContributionBlueIds.isEmpty()) {
            return;
        }
        Set<String> missing =
                new TreeSet<String>(
                        requiredContributionBlueIds);
        missing.removeAll(
                exactNodeIndex.nodesByBlueId().keySet());
        for (String blueId
                : new ArrayList<String>(missing)) {
            Node exact =
                    optionalExactProviderContent(
                            new Node().blueId(blueId),
                            "Registered Coordination contract header "
                                    + blueId);
            if (exact == null) {
                continue;
            }
            exactNodeIndex.blueId(exact);
        }
        missing.removeAll(
                exactNodeIndex.nodesByBlueId().keySet());
        if (missing.isEmpty()) {
            return;
        }

        Set<String> openedReferences =
                new LinkedHashSet<String>();
        Set<Node> visitedDefinitions =
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>());
        for (Node exactRoot : exactRoots) {
            indexContractDefinitionChain(
                    exactRoot,
                    missing,
                    exactNodeIndex,
                    openedReferences,
                    visitedDefinitions);
            if (missing.isEmpty()) {
                return;
            }
        }
    }

    private void indexContractDefinitionChain(
            Node suppliedDefinition,
            Set<String> missing,
            CoordinationExactNodeIndex exactNodeIndex,
            Set<String> openedReferences,
            Set<Node> visitedDefinitions) {
        Node definition = suppliedDefinition;
        while (definition != null
                && !missing.isEmpty()) {
            if (definition.isReferenceOnly()) {
                String blueId =
                        BlueIds.requirePlainBlueId(
                                definition.getBlueId(),
                                "contract definition blueId");
                if (!openedReferences.add(blueId)) {
                    return;
                }
                definition =
                        optionalExactProviderContent(
                                definition,
                                "Contract definition " + blueId);
                if (definition == null) {
                    return;
                }
            } else if (!visitedDefinitions.add(
                    definition)) {
                return;
            }

            exactNodeIndex.blueId(definition);
            indexReferencedContractsMap(
                    definition.getContracts(),
                    exactNodeIndex,
                    openedReferences);
            missing.removeAll(
                    exactNodeIndex.nodesByBlueId().keySet());
            definition = definition.getType();
        }
    }

    private void indexReferencedContractsMap(
            Node contracts,
            CoordinationExactNodeIndex exactNodeIndex,
            Set<String> openedReferences) {
        if (contracts == null
                || !contracts.isReferenceOnly()) {
            return;
        }
        String blueId =
                BlueIds.requirePlainBlueId(
                        contracts.getBlueId(),
                        "contracts map blueId");
        if (!openedReferences.add(blueId)) {
            return;
        }
        Node exact =
                optionalExactProviderContent(
                        contracts,
                        "Contracts map " + blueId);
        if (exact != null) {
            exactNodeIndex.blueId(exact);
        }
    }

    private static Node requireExactContent(
            Node node,
            String label) {
        Node checked =
                Objects.requireNonNull(node, label);
        if (checked.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    label + " must contain exact admitted content");
        }
        return checked.clone();
    }

    private static void requireIdentity(
            String expectedBlueId,
            Node fragment,
            String label) {
        String actualBlueId =
                DirectBlueIdCalculator.calculateBlueId(fragment.clone());
        if (!expectedBlueId.equals(actualBlueId)) {
            throw new IllegalStateException(
                    label
                            + " fragmentation changed BlueId from "
                            + expectedBlueId
                            + " to "
                            + actualBlueId);
        }
    }

    /**
     * Immutable semantic/cut blueprint used by the incremental engine path.
     * No canonical direct fragment bodies are retained by this value.
     */
    public static final class DocumentFragmentationBlueprint {

        private final String rootBlueId;
        private final Node exactRoot;
        private final List<PhysicalFragmentRoot> physicalRoots;
        private final List<FragmentMetadata> metadata;
        private final List<FragmentRoot> fragmentRoots;
        private final SortedMap<String, CutDescriptor> cuts;
        private final List<String> scopePaths;
        private final SortedMap<String, Node> processHeaderViews;
        private final CoordinationExactNodeIndex exactNodeIndex;

        private DocumentFragmentationBlueprint(
                String rootBlueId,
                Node exactRoot,
                Collection<PhysicalFragmentRoot> physicalRoots,
                Collection<FragmentMetadata> metadata,
                Collection<FragmentRoot> fragmentRoots,
                Map<String, CutDescriptor> cuts,
                Collection<String> scopePaths,
                Map<String, Node> processHeaderViews,
                CoordinationExactNodeIndex exactNodeIndex) {
            this.rootBlueId = Objects.requireNonNull(
                    rootBlueId, "rootBlueId");
            this.exactRoot = Objects.requireNonNull(
                    exactRoot, "exactRoot");
            this.physicalRoots = Collections.unmodifiableList(
                    new ArrayList<PhysicalFragmentRoot>(
                            Objects.requireNonNull(
                                    physicalRoots,
                                    "physicalRoots")));
            this.metadata = Collections.unmodifiableList(
                    new ArrayList<FragmentMetadata>(
                            Objects.requireNonNull(
                                    metadata,
                                    "metadata")));
            this.fragmentRoots = Collections.unmodifiableList(
                    new ArrayList<FragmentRoot>(
                            Objects.requireNonNull(
                                    fragmentRoots,
                                    "fragmentRoots")));
            this.cuts = Collections.unmodifiableSortedMap(
                    new TreeMap<String, CutDescriptor>(
                            Objects.requireNonNull(cuts, "cuts")));
            this.scopePaths = Collections.unmodifiableList(
                    new ArrayList<String>(
                            Objects.requireNonNull(
                                    scopePaths,
                                    "scopePaths")));
            this.processHeaderViews = immutableFragments(
                    processHeaderViews);
            this.exactNodeIndex = Objects.requireNonNull(
                    exactNodeIndex, "exactNodeIndex");
        }

        public String rootBlueId() {
            return rootBlueId;
        }

        public Node exactRoot() {
            return exactRoot.clone();
        }

        public List<PhysicalFragmentRoot> physicalRoots() {
            return physicalRoots;
        }

        public List<FragmentMetadata> metadata() {
            return metadata;
        }

        public List<FragmentRoot> fragmentRoots() {
            return fragmentRoots;
        }

        /**
         * Returns exact identity-equivalent PROCESS representations keyed by
         * their physical fragment identity.
         */
        public Map<String, Node> processHeaderViews() {
            return immutableFragments(processHeaderViews);
        }

        private List<PhysicalFragmentRoot> physicalRootsInternal() {
            return physicalRoots;
        }

        private String blueId(Node exactNode) {
            return exactNodeIndex.blueId(exactNode);
        }

        private Node directFragment(Node exactNode) {
            return exactNodeIndex.directFragment(exactNode);
        }
    }

    /** One independently retained exact root in a document blueprint. */
    public static final class PhysicalFragmentRoot {

        private final Node exactRoot;
        private final String blueId;
        private final FragmentRootKind rootKind;
        private final String basePath;

        private PhysicalFragmentRoot(
                Node exactRoot,
                FragmentRootKind rootKind,
                String basePath) {
            this(
                    exactRoot,
                    DirectBlueIdCalculator.calculateBlueId(exactRoot),
                    rootKind,
                    basePath);
        }

        private PhysicalFragmentRoot(
                Node exactRoot,
                String blueId,
                FragmentRootKind rootKind,
                String basePath) {
            this.exactRoot = Objects.requireNonNull(
                    exactRoot, "exactRoot");
            this.blueId = Objects.requireNonNull(blueId, "blueId");
            this.rootKind = Objects.requireNonNull(
                    rootKind, "rootKind");
            this.basePath = JsonPointer.canonicalize(
                    Objects.requireNonNull(
                            basePath, "basePath"));
        }

        public Node exactRoot() {
            return exactRoot.clone();
        }

        public FragmentRootKind rootKind() {
            return rootKind;
        }

        public String blueId() {
            return blueId;
        }

        public String basePath() {
            return basePath;
        }
    }

    /** Direct-node identity, optional new body, and exact child frontier. */
    public static final class DirectNodeInspection {

        private final String ownerBlueId;
        private final Node directFragment;
        private final List<DirectChildOccurrence> children;

        private DirectNodeInspection(
                String ownerBlueId,
                Node directFragment,
                Collection<DirectChildOccurrence> children) {
            this.ownerBlueId = Objects.requireNonNull(
                    ownerBlueId, "ownerBlueId");
            this.directFragment = directFragment != null
                    ? directFragment.clone()
                    : null;
            this.children = Collections.unmodifiableList(
                    new ArrayList<DirectChildOccurrence>(
                            Objects.requireNonNull(
                                    children, "children")));
        }

        public String ownerBlueId() {
            return ownerBlueId;
        }

        public boolean assembledFragment() {
            return directFragment != null;
        }

        public Node directFragment() {
            if (directFragment == null) {
                throw new IllegalStateException(
                        "This inspection did not assemble a fragment body");
            }
            return directFragment.clone();
        }

        public List<DirectChildOccurrence> children() {
            return children;
        }
    }

    /** One canonical direct child occurrence and its exact recursion value. */
    public static final class DirectChildOccurrence {

        private final Node exactChild;
        private final EdgeOccurrence edge;

        private DirectChildOccurrence(
                Node exactChild,
                EdgeOccurrence edge) {
            this.exactChild = Objects.requireNonNull(
                    exactChild, "exactChild");
            this.edge = Objects.requireNonNull(edge, "edge");
        }

        public Node exactChild() {
            return exactChild.clone();
        }

        public EdgeOccurrence edge() {
            return edge;
        }
    }

    /**
     * The exact physical fragment inventory for one semantic input.
     *
     * <p>Fragments are immutable under
     * {@code (fragmentationProfileIdentity, BlueId)}. A persistent host may
     * race admissions, but must re-read and byte-verify the winning value with
     * {@link CoordinationFragmentAdmissionVerifier}. Accessors return
     * defensive nodes; a warm cache does not grant permission to demand an
     * otherwise disallowed identity.</p>
     */
    public static final class SplitGraph {

        private final String rootBlueId;
        private final Node originalRoot;
        private final Node fragmentedRoot;
        private final SortedMap<String, Node> fragments;
        private final List<String> fragmentBlueIds;
        private final List<FragmentMetadata> metadata;
        private final List<EdgeOccurrence> edgeOccurrences;
        private final List<FragmentRoot> fragmentRoots;
        private final NodeProvider provider;

        private SplitGraph(
                String rootBlueId,
                Node originalRoot,
                Node fragmentedRoot,
                Map<String, Node> fragments,
                Collection<FragmentMetadata> metadata,
                Collection<EdgeOccurrence> edgeOccurrences,
                Collection<FragmentRoot> fragmentRoots,
                NodeProvider provider) {
            this(
                    rootBlueId,
                    originalRoot,
                    fragmentedRoot,
                    fragments,
                    metadata,
                    edgeOccurrences,
                    fragmentRoots,
                    provider,
                    false);
        }

        private SplitGraph(
                String rootBlueId,
                Node originalRoot,
                Node fragmentedRoot,
                Map<String, Node> fragments,
                Collection<FragmentMetadata> metadata,
                Collection<EdgeOccurrence> edgeOccurrences,
                Collection<FragmentRoot> fragmentRoots,
                NodeProvider provider,
                boolean ownsCanonicalInputs) {
            this.rootBlueId =
                    Objects.requireNonNull(
                            rootBlueId, "rootBlueId");
            Node checkedOriginalRoot = Objects.requireNonNull(
                    originalRoot, "originalRoot");
            Node checkedFragmentedRoot = Objects.requireNonNull(
                    fragmentedRoot, "fragmentedRoot");
            this.originalRoot = ownsCanonicalInputs
                    ? checkedOriginalRoot
                    : checkedOriginalRoot.clone();
            this.fragmentedRoot = ownsCanonicalInputs
                    ? checkedFragmentedRoot
                    : checkedFragmentedRoot.clone();
            this.fragments = ownsCanonicalInputs
                    ? ownedCanonicalFragments(fragments)
                    : immutableFragments(fragments);
            this.fragmentBlueIds = Collections.unmodifiableList(
                    new ArrayList<String>(this.fragments.keySet()));
            List<FragmentMetadata> ordered =
                    new ArrayList<>(metadata);
            Collections.sort(
                    ordered,
                    Comparator
                            .comparing(
                                    FragmentMetadata::blueId)
                            .thenComparing(
                                    value -> value.kind().name())
                            .thenComparing(
                                    value -> nullToEmpty(
                                            value.pointer())));
            this.metadata =
                    Collections.unmodifiableList(
                            ordered);
            List<EdgeOccurrence> orderedEdges =
                    new ArrayList<>(
                            Objects.requireNonNull(
                                    edgeOccurrences,
                                    "edgeOccurrences"));
            Collections.sort(
                    orderedEdges,
                    EdgeOccurrence.CANONICAL_ORDER);
            this.edgeOccurrences =
                    Collections.unmodifiableList(
                            orderedEdges);
            List<FragmentRoot> orderedRoots =
                    new ArrayList<>(
                            Objects.requireNonNull(
                                    fragmentRoots,
                                    "fragmentRoots"));
            Collections.sort(
                    orderedRoots,
                    FragmentRoot.CANONICAL_ORDER);
            this.fragmentRoots =
                    Collections.unmodifiableList(
                            orderedRoots);
            this.provider =
                    Objects.requireNonNull(
                            provider, "provider");
            requireIdentity(
                    rootBlueId,
                    this.fragmentedRoot,
                    "Split Root");
            Node storedRoot =
                    this.fragments.get(
                            rootBlueId);
            if (storedRoot == null
                    || !NodeWireForm.get(
                    storedRoot).equals(
                    NodeWireForm.get(
                            this.fragmentedRoot))) {
                throw new IllegalStateException(
                        "Split Root is not its canonical stored direct fragment");
            }
        }

        public String rootBlueId() {
            return rootBlueId;
        }

        public Node originalRoot() {
            return originalRoot.clone();
        }

        /**
         * Freezes the splitter-owned exact Root without first creating an
         * intermediate mutable full-graph copy.
         */
        public FrozenNode frozenOriginalRoot() {
            return FrozenNode.fromNode(originalRoot);
        }

        public Node fragmentedRoot() {
            return fragmentedRoot.clone();
        }

        /**
         * Returns the identity-preserving PROCESS Root view.
         *
         * <p>The canonical stored Root remains {@link #fragmentedRoot()}. This
         * ephemeral view additionally inlines only the declared participating
         * scope chain and immutable contract headers. Registered executable
         * bodies and unrelated direct children remain pure references, which
         * lets Language create a selective snapshot without opening decoys.</p>
         *
         * @return defensive exact Root view for snapshot-native PROCESS
         */
        public Node processingRootView() {
            NodeProviderResult result =
                    provider.fetchResultByBlueId(
                            rootBlueId);
            if (result.outcome()
                    != NodeProviderOutcome.FOUND
                    || result.nodes().size() != 1) {
                throw new IllegalStateException(
                        "PROCESS Root view is unavailable for "
                                + rootBlueId + ": "
                                + result.outcome());
            }
            Node root =
                    result.nodes().get(0);
            requireIdentity(
                    rootBlueId,
                    root,
                    "PROCESS Root view");
            return root.clone();
        }

        public Node pureReference() {
            return new Node().blueId(
                    rootBlueId);
        }

        public Map<String, Node> fragments() {
            return immutableFragments(
                    fragments);
        }

        /**
         * Returns the already verified canonical fragment identities without
         * materializing their bodies.
         */
        public List<String> fragmentBlueIds() {
            return fragmentBlueIds;
        }

        /** Materializes one verified fragment body on demand. */
        public Node fragment(String blueId) {
            Node fragment = fragments.get(Objects.requireNonNull(
                    blueId, "blueId"));
            return fragment == null ? null : fragment.clone();
        }

        /**
         * Returns an ephemeral, verified provider for PROCESS.
         *
         * <p>The provider serves the canonical fragments except for exact
         * registered Coordination header and participating-scope identities,
         * which are exposed through
         * {@link #processHeaderViewProfileIdentity()} with immutable headers
         * inline and every registered executable body still a pure reference.
         * The Root view contains the declared scope chain so selective
         * snapshot construction does not need to open unrelated branches.
         * These header-view values are never part of
         * {@link #fragments()}, admission, graph digests, or reconstruction
         * storage.</p>
         *
         * @return defensive exact PROCESS materialization provider
         */
        public NodeProvider provider() {
            return provider;
        }

        /**
         * Returns the stable identity of the nonsemantic PROCESS header view.
         *
         * @return PROCESS header-view profile identity
         */
        public String processHeaderViewProfileIdentity() {
            return PROCESS_HEADER_VIEW_PROFILE_ID;
        }

        public List<FragmentMetadata> metadata() {
            return metadata;
        }

        /**
         * Returns the stable immutable physical-fragment profile.
         */
        public String fragmentationProfileIdentity() {
            return FRAGMENTATION_PROFILE_ID;
        }

        /**
         * Returns the stable schema/version of edge occurrence metadata.
         */
        public String edgeMetadataSchemaIdentity() {
            return EDGE_METADATA_SCHEMA_ID;
        }

        /**
         * Returns every canonically ordered physical edge occurrence.
         */
        public List<EdgeOccurrence> edgeOccurrences() {
            return edgeOccurrences;
        }

        /**
         * Returns all exact roots whose direct graphs form this inventory.
         */
        public List<FragmentRoot> fragmentRoots() {
            return fragmentRoots;
        }

        /**
         * Reconstructs and verifies the original semantic Root using only the
         * immutable physical inventory and occurrence metadata.
         */
        public Node reconstruct() {
            return CoordinationFragmentReconstructor.reconstruct(
                    FRAGMENTATION_PROFILE_ID,
                    rootBlueId,
                    fragmentRoots,
                    fragments,
                    edgeOccurrences);
        }

        /**
         * Returns a deterministic digest over the profile, fragments, roots,
         * and exact edge occurrences.
         */
        public String inventoryIdentity() {
            return CoordinationFragmentAdmissionVerifier
                    .inventoryIdentity(
                            FRAGMENTATION_PROFILE_ID,
                            fragmentRoots,
                            fragments,
                            edgeOccurrences);
        }
    }

    public enum FragmentKind {
        DOCUMENT_ROOT,
        EMBEDDED_ROOT,
        SOURCE_CONTRIBUTION,
        EXECUTABLE_BODY,
        EVENT_ROOT,
        EVENT_FRAGMENT
    }

    /**
     * Role of one independently retained exact root in the physical inventory.
     */
    public enum FragmentRootKind {
        DOCUMENT,
        DOCUMENT_SCOPE,
        SOURCE_CONTRIBUTION,
        EVENT
    }

    /**
     * Semantic meaning of one exact direct-node edge occurrence.
     */
    public enum EdgeKind {
        DOCUMENT_DIRECT_CHILD,
        EMBEDDED_ROOT,
        EXECUTABLE_BODY,
        SOURCE_CONTRIBUTION_BODY,
        EVENT_DIRECT_CHILD
    }

    /** Declaration provenance for a concrete embedded edge occurrence. */
    public enum EmbeddedEdgeOrigin {
        /** The physical edge is not a Process Embedded child cut. */
        NONE,
        /** The child came from an exact {@code paths} declaration. */
        EXPLICIT,
        /** The child came from a stable-key {@code collectionPaths} member. */
        COLLECTION_MEMBER
    }

    /**
     * Immutable descriptor of one exact root admitted to the fragment graph.
     */
    public static final class FragmentRoot {

        private static final Comparator<FragmentRoot>
                CANONICAL_ORDER =
                Comparator
                        .comparing(
                                (FragmentRoot value) ->
                                        value.kind.name())
                        .thenComparing(
                                FragmentRoot::absolutePath)
                        .thenComparing(
                                FragmentRoot::blueId);

        private final String blueId;
        private final FragmentRootKind kind;
        private final String absolutePath;

        public FragmentRoot(
                String blueId,
                FragmentRootKind kind,
                String absolutePath) {
            this.blueId =
                    BlueIds.requirePlainBlueId(
                            blueId, "blueId");
            this.kind =
                    Objects.requireNonNull(
                            kind, "kind");
            this.absolutePath =
                    JsonPointer.canonicalize(
                            Objects.requireNonNull(
                                    absolutePath,
                                    "absolutePath"));
        }

        public String blueId() {
            return blueId;
        }

        public FragmentRootKind kind() {
            return kind;
        }

        public String absolutePath() {
            return absolutePath;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FragmentRoot)) {
                return false;
            }
            FragmentRoot that =
                    (FragmentRoot) other;
            return blueId.equals(that.blueId)
                    && kind == that.kind
                    && absolutePath.equals(
                    that.absolutePath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    blueId,
                    kind,
                    absolutePath);
        }
    }

    /**
     * Canonical metadata for one direct physical edge occurrence.
     *
     * <p>{@code originalPureReference} distinguishes an authored cold edge
     * from a reference introduced by the canonical direct-node profile.
     * Several values may name the same child identity at different absolute
     * pointers.</p>
     */
    public static final class EdgeOccurrence {

        private static final Comparator<EdgeOccurrence>
                CANONICAL_ORDER =
                Comparator
                        .comparing(
                                (EdgeOccurrence value) ->
                                        value.rootKind.name())
                        .thenComparing(
                                EdgeOccurrence::rootBlueId)
                        .thenComparing(
                                EdgeOccurrence::ownerNodeBlueId)
                        .thenComparing(
                                EdgeOccurrence::absolutePointer)
                        .thenComparing(
                                value -> value.edgeKind.name())
                        .thenComparing(
                                EdgeOccurrence::childBlueId)
                        .thenComparing(
                                EdgeOccurrence::ownerRelativePointer)
                        .thenComparing(
                                EdgeOccurrence::originalPureReference)
                        .thenComparing(
                                value -> value.embeddedOrigin().name())
                        .thenComparing(
                                value -> nullToEmpty(
                                        value.declaringScopePath()))
                        .thenComparing(
                                value -> nullToEmpty(
                                        value.explicitDeclarationPath()))
                        .thenComparing(
                                value -> nullToEmpty(
                                        value.collectionDeclarationPath()))
                        .thenComparing(
                                value -> nullToEmpty(
                                        value.collectionMemberKey()))
                        .thenComparing(
                                value -> nullToEmpty(
                                        value.handlerEffectiveTypeBlueId()))
                        .thenComparing(
                                value -> nullToEmpty(
                                        value.executableBodyField()))
                        .thenComparing(
                                value -> value
                                        .sourceContributionBlueIds()
                                        .toString());

        private final String fragmentationProfileIdentity;
        private final String schemaIdentity;
        private final FragmentRootKind rootKind;
        private final String rootBlueId;
        private final String ownerNodeBlueId;
        private final String ownerScopePath;
        private final String absolutePointer;
        private final String ownerRelativePointer;
        private final String childBlueId;
        private final EdgeKind edgeKind;
        private final boolean originalPureReference;
        private final boolean splitterCreated;
        private final String declaringScopePath;
        private final EmbeddedEdgeOrigin embeddedOrigin;
        private final String explicitDeclarationPath;
        private final String collectionDeclarationPath;
        private final String collectionMemberKey;
        private final String handlerEffectiveTypeBlueId;
        private final String executableBodyField;
        private final List<String> sourceContributionBlueIds;

        public EdgeOccurrence(
                String fragmentationProfileIdentity,
                String schemaIdentity,
                FragmentRootKind rootKind,
                String rootBlueId,
                String ownerNodeBlueId,
                String ownerScopePath,
                String absolutePointer,
                String ownerRelativePointer,
                String childBlueId,
                EdgeKind edgeKind,
                boolean originalPureReference,
                boolean splitterCreated,
                String handlerEffectiveTypeBlueId,
                String executableBodyField,
                Collection<String> sourceContributionBlueIds) {
            this(
                    fragmentationProfileIdentity,
                    schemaIdentity,
                    rootKind,
                    rootBlueId,
                    ownerNodeBlueId,
                    ownerScopePath,
                    absolutePointer,
                    ownerRelativePointer,
                    childBlueId,
                    edgeKind,
                    originalPureReference,
                    splitterCreated,
                    null,
                    EmbeddedEdgeOrigin.NONE,
                    null,
                    null,
                    null,
                    handlerEffectiveTypeBlueId,
                    executableBodyField,
                    sourceContributionBlueIds);
        }

        public EdgeOccurrence(
                String fragmentationProfileIdentity,
                String schemaIdentity,
                FragmentRootKind rootKind,
                String rootBlueId,
                String ownerNodeBlueId,
                String ownerScopePath,
                String absolutePointer,
                String ownerRelativePointer,
                String childBlueId,
                EdgeKind edgeKind,
                boolean originalPureReference,
                boolean splitterCreated,
                String declaringScopePath,
                EmbeddedEdgeOrigin embeddedOrigin,
                String explicitDeclarationPath,
                String collectionDeclarationPath,
                String collectionMemberKey,
                String handlerEffectiveTypeBlueId,
                String executableBodyField,
                Collection<String> sourceContributionBlueIds) {
            this.fragmentationProfileIdentity =
                    requireText(
                            fragmentationProfileIdentity,
                            "fragmentationProfileIdentity");
            this.schemaIdentity =
                    requireText(
                            schemaIdentity,
                            "schemaIdentity");
            this.rootKind =
                    Objects.requireNonNull(
                            rootKind, "rootKind");
            this.rootBlueId =
                    BlueIds.requirePlainBlueId(
                            rootBlueId, "rootBlueId");
            this.ownerNodeBlueId =
                    BlueIds.requirePlainBlueId(
                            ownerNodeBlueId,
                            "ownerNodeBlueId");
            this.ownerScopePath =
                    ownerScopePath != null
                            ? JsonPointer.canonicalize(
                            ownerScopePath)
                            : null;
            this.absolutePointer =
                    JsonPointer.canonicalize(
                            Objects.requireNonNull(
                                    absolutePointer,
                                    "absolutePointer"));
            this.ownerRelativePointer =
                    JsonPointer.canonicalize(
                            Objects.requireNonNull(
                                    ownerRelativePointer,
                                    "ownerRelativePointer"));
            this.childBlueId =
                    requireText(
                            childBlueId,
                            "childBlueId");
            this.edgeKind =
                    Objects.requireNonNull(
                            edgeKind, "edgeKind");
            if (originalPureReference
                    == splitterCreated) {
                throw new IllegalArgumentException(
                        "Exactly one of originalPureReference and "
                                + "splitterCreated must be true");
            }
            this.originalPureReference =
                    originalPureReference;
            this.splitterCreated =
                    splitterCreated;
            this.declaringScopePath =
                    declaringScopePath != null
                            ? JsonPointer.canonicalize(
                            declaringScopePath)
                            : null;
            this.embeddedOrigin =
                    Objects.requireNonNull(
                            embeddedOrigin,
                            "embeddedOrigin");
            this.explicitDeclarationPath =
                    explicitDeclarationPath != null
                            ? JsonPointer.canonicalize(
                            explicitDeclarationPath)
                            : null;
            this.collectionDeclarationPath =
                    collectionDeclarationPath != null
                            ? JsonPointer.canonicalize(
                            collectionDeclarationPath)
                            : null;
            this.collectionMemberKey = collectionMemberKey;
            validateEmbeddedProvenance();
            this.handlerEffectiveTypeBlueId =
                    handlerEffectiveTypeBlueId;
            this.executableBodyField =
                    executableBodyField;
            List<String> sources =
                    new ArrayList<>(
                            Objects.requireNonNull(
                                    sourceContributionBlueIds,
                                    "sourceContributionBlueIds"));
            for (String source : sources) {
                requireText(
                        source,
                        "sourceContributionBlueId");
            }
            this.sourceContributionBlueIds =
                    Collections.unmodifiableList(
                            sources);
        }

        public String fragmentationProfileIdentity() {
            return fragmentationProfileIdentity;
        }

        public String schemaIdentity() {
            return schemaIdentity;
        }

        public FragmentRootKind rootKind() {
            return rootKind;
        }

        public String rootBlueId() {
            return rootBlueId;
        }

        public String ownerNodeBlueId() {
            return ownerNodeBlueId;
        }

        public String ownerScopePath() {
            return ownerScopePath;
        }

        public String absolutePointer() {
            return absolutePointer;
        }

        public String ownerRelativePointer() {
            return ownerRelativePointer;
        }

        public String childBlueId() {
            return childBlueId;
        }

        public EdgeKind edgeKind() {
            return edgeKind;
        }

        public boolean originalPureReference() {
            return originalPureReference;
        }

        public boolean splitterCreated() {
            return splitterCreated;
        }

        public String declaringScopePath() {
            return declaringScopePath;
        }

        public EmbeddedEdgeOrigin embeddedOrigin() {
            return embeddedOrigin;
        }

        public String explicitDeclarationPath() {
            return explicitDeclarationPath;
        }

        public String collectionDeclarationPath() {
            return collectionDeclarationPath;
        }

        /** Returns the exact decoded stable collection key. */
        public String collectionMemberKey() {
            return collectionMemberKey;
        }

        public String handlerEffectiveTypeBlueId() {
            return handlerEffectiveTypeBlueId;
        }

        public String executableBodyField() {
            return executableBodyField;
        }

        public List<String> sourceContributionBlueIds() {
            return sourceContributionBlueIds;
        }

        private boolean physicallyEquivalent(
                EdgeOccurrence other) {
            return fragmentationProfileIdentity.equals(
                    other.fragmentationProfileIdentity)
                    && schemaIdentity.equals(
                    other.schemaIdentity)
                    && rootBlueId.equals(
                    other.rootBlueId)
                    && ownerNodeBlueId.equals(
                    other.ownerNodeBlueId)
                    && Objects.equals(
                    ownerScopePath,
                    other.ownerScopePath)
                    && absolutePointer.equals(
                    other.absolutePointer)
                    && ownerRelativePointer.equals(
                    other.ownerRelativePointer)
                    && childBlueId.equals(
                    other.childBlueId)
                    && edgeKind == other.edgeKind
                    && originalPureReference
                    == other.originalPureReference
                    && splitterCreated
                    == other.splitterCreated
                    && Objects.equals(
                    declaringScopePath,
                    other.declaringScopePath)
                    && embeddedOrigin == other.embeddedOrigin
                    && Objects.equals(
                    explicitDeclarationPath,
                    other.explicitDeclarationPath)
                    && Objects.equals(
                    collectionDeclarationPath,
                    other.collectionDeclarationPath)
                    && Objects.equals(
                    collectionMemberKey,
                    other.collectionMemberKey)
                    && Objects.equals(
                    handlerEffectiveTypeBlueId,
                    other.handlerEffectiveTypeBlueId)
                    && Objects.equals(
                    executableBodyField,
                    other.executableBodyField)
                    && sourceContributionBlueIds.equals(
                    other.sourceContributionBlueIds);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof EdgeOccurrence)) {
                return false;
            }
            EdgeOccurrence that =
                    (EdgeOccurrence) other;
            return rootKind == that.rootKind
                    && physicallyEquivalent(that);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    fragmentationProfileIdentity,
                    schemaIdentity,
                    rootKind,
                    rootBlueId,
                    ownerNodeBlueId,
                    ownerScopePath,
                    absolutePointer,
                    ownerRelativePointer,
                    childBlueId,
                    edgeKind,
                    originalPureReference,
                    splitterCreated,
                    declaringScopePath,
                    embeddedOrigin,
                    explicitDeclarationPath,
                    collectionDeclarationPath,
                    collectionMemberKey,
                    handlerEffectiveTypeBlueId,
                    executableBodyField,
                    sourceContributionBlueIds);
        }

        private void validateEmbeddedProvenance() {
            if (edgeKind != EdgeKind.EMBEDDED_ROOT) {
                if (embeddedOrigin != EmbeddedEdgeOrigin.NONE
                        || declaringScopePath != null
                        || explicitDeclarationPath != null
                        || collectionDeclarationPath != null
                        || collectionMemberKey != null) {
                    throw new IllegalArgumentException(
                            "Only an embedded-root edge may carry embedded provenance");
                }
                return;
            }
            if (declaringScopePath == null
                    || embeddedOrigin == EmbeddedEdgeOrigin.NONE) {
                throw new IllegalArgumentException(
                        "Embedded-root edge requires declaration provenance");
            }
            if (embeddedOrigin == EmbeddedEdgeOrigin.EXPLICIT) {
                if (explicitDeclarationPath == null
                        || collectionDeclarationPath != null
                        || collectionMemberKey != null) {
                    throw new IllegalArgumentException(
                            "Explicit embedded edge has inconsistent declaration provenance");
                }
                String expected = PointerUtils.resolvePointer(
                        declaringScopePath,
                        explicitDeclarationPath);
                if (!absolutePointer.equals(expected)) {
                    throw new IllegalArgumentException(
                            "Explicit embedded edge pointer does not match its declaration");
                }
                return;
            }
            if (explicitDeclarationPath != null
                    || collectionDeclarationPath == null
                    || collectionMemberKey == null) {
                throw new IllegalArgumentException(
                        "Collection-member edge has inconsistent declaration provenance");
            }
            String collection = PointerUtils.resolvePointer(
                    declaringScopePath,
                    collectionDeclarationPath);
            String expected = JsonPointer.append(
                    collection,
                    collectionMemberKey);
            if (!absolutePointer.equals(expected)) {
                throw new IllegalArgumentException(
                        "Collection-member edge pointer does not match its exact member key");
            }
        }
    }

    /**
     * Non-semantic diagnostic information for one retained fragment occurrence.
     */
    public static final class FragmentMetadata {

        private final String blueId;
        private final FragmentKind kind;
        private final String scopePath;
        private final String pointer;
        private final String handlerTypeBlueId;
        private final String executableBodyField;

        private FragmentMetadata(
                String blueId,
                FragmentKind kind,
                String scopePath,
                String pointer,
                String handlerTypeBlueId,
                String executableBodyField) {
            this.blueId =
                    Objects.requireNonNull(
                            blueId, "blueId");
            this.kind =
                    Objects.requireNonNull(
                            kind, "kind");
            this.scopePath = scopePath;
            this.pointer = pointer;
            this.handlerTypeBlueId =
                    handlerTypeBlueId;
            this.executableBodyField =
                    executableBodyField;
        }

        public String blueId() {
            return blueId;
        }

        public FragmentKind kind() {
            return kind;
        }

        public String scopePath() {
            return scopePath;
        }

        public String pointer() {
            return pointer;
        }

        public String handlerTypeBlueId() {
            return handlerTypeBlueId;
        }

        public String executableBodyField() {
            return executableBodyField;
        }
    }

    /**
     * Exact PROCESS inputs plus the provider and revision-bound evidence needed
     * by a configured Language processor.
     */
    public static final class PreparedProcessingInput {

        private final Node document;
        private final Node event;
        private final VerifiedExecutionEvidence evidence;
        private final NodeProvider provider;

        private PreparedProcessingInput(
                Node document,
                Node event,
                VerifiedExecutionEvidence evidence,
                NodeProvider provider) {
            this.document = document.clone();
            this.event = event.clone();
            this.evidence = evidence;
            this.provider = provider;
        }

        public Node document() {
            return document.clone();
        }

        public Node event() {
            return event.clone();
        }

        public VerifiedExecutionEvidence evidence() {
            return evidence;
        }

        public NodeProvider provider() {
            return provider;
        }
    }

    private static SortedMap<String, Node>
    immutableFragments(
            Map<String, Node> source) {
        SortedMap<String, Node> result =
                new TreeMap<>();
        for (Map.Entry<String, Node>
                entry : source.entrySet()) {
            Node fragment =
                    Objects.requireNonNull(
                            entry.getValue(),
                            "fragment")
                            .clone();
            requireIdentity(
                    entry.getKey(),
                    fragment,
                    "Exact fragment");
            result.put(
                    entry.getKey(),
                    fragment);
        }
        return Collections.unmodifiableSortedMap(
                result);
    }

    /**
     * Retains a canonical fragment snapshot exclusively owned by this
     * splitter invocation.  Values are never exposed directly by SplitGraph;
     * its public body accessors remain defensive.
     */
    private static SortedMap<String, Node> ownedCanonicalFragments(
            Map<String, Node> source) {
        SortedMap<String, Node> result = new TreeMap<>();
        for (Map.Entry<String, Node> entry : Objects.requireNonNull(
                source, "source").entrySet()) {
            String blueId = requireText(entry.getKey(), "fragmentBlueId");
            result.put(
                    blueId,
                    Objects.requireNonNull(
                            entry.getValue(), "fragment"));
        }
        return Collections.unmodifiableSortedMap(result);
    }

    private static NodeProvider verifiedProvider(
            Map<String, Node> fragments) {
        final SortedMap<String, Node> retained =
                immutableFragments(fragments);
        NodeProvider raw = blueId -> {
            Node fragment = retained.get(blueId);
            return fragment != null
                    ? Collections.singletonList(
                    fragment.clone())
                    : null;
        };
        return new VerifyingNodeProvider(raw);
    }

    private static String nullToEmpty(
            String value) {
        return value != null ? value : "";
    }

    private static String requireText(
            String value,
            String label) {
        String checked =
                Objects.requireNonNull(
                        value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must not be blank");
        }
        return checked;
    }

    private static final class EdgeQuota {
        private final CoordinationHostQuotaSession session;
        private final String operation;

        private EdgeQuota(
                CoordinationHostQuotaSession session,
                String operation) {
            this.session =
                    Objects.requireNonNull(
                            session, "session");
            this.operation =
                    Objects.requireNonNull(
                            operation, "operation");
        }

        private void record(
                String absolutePointer,
                EdgeKind kind) {
            session.recordFragmentEdgeMetadata(
                    operation,
                    absolutePointer,
                    quotaReason(kind));
        }

        private static String quotaReason(
                EdgeKind kind) {
            switch (kind) {
                case DOCUMENT_DIRECT_CHILD:
                    return "document-direct-child";
                case EMBEDDED_ROOT:
                    return "embedded-root";
                case EXECUTABLE_BODY:
                    return "executable-body";
                case SOURCE_CONTRIBUTION_BODY:
                    return "source-contribution-body";
                case EVENT_DIRECT_CHILD:
                    return "event-direct-child";
                default:
                    throw new IllegalStateException(
                            "Unsupported fragment edge kind "
                                    + kind);
            }
        }
    }

    private static final class CutDescriptor {

        private final EdgeKind kind;
        private final String ownerScopePath;
        private final String declaringScopePath;
        private final EmbeddedEdgeOrigin embeddedOrigin;
        private final String explicitDeclarationPath;
        private final String collectionDeclarationPath;
        private final String collectionMemberKey;
        private final String handlerTypeBlueId;
        private final String executableBodyField;
        private final List<String> sourceContributionBlueIds;

        private CutDescriptor(
                EdgeKind kind,
                String ownerScopePath,
                String declaringScopePath,
                EmbeddedEdgeOrigin embeddedOrigin,
                String explicitDeclarationPath,
                String collectionDeclarationPath,
                String collectionMemberKey,
                String handlerTypeBlueId,
                String executableBodyField,
                Collection<String> sourceContributionBlueIds) {
            this.kind =
                    Objects.requireNonNull(
                            kind, "kind");
            this.ownerScopePath =
                    ownerScopePath;
            this.declaringScopePath = declaringScopePath;
            this.embeddedOrigin = Objects.requireNonNull(
                    embeddedOrigin,
                    "embeddedOrigin");
            this.explicitDeclarationPath = explicitDeclarationPath;
            this.collectionDeclarationPath = collectionDeclarationPath;
            this.collectionMemberKey = collectionMemberKey;
            this.handlerTypeBlueId =
                    handlerTypeBlueId;
            this.executableBodyField =
                    executableBodyField;
            this.sourceContributionBlueIds =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    sourceContributionBlueIds));
        }
    }

    private static final class SchemaChild {

        private final String key;
        private final Node original;
        private final Node direct;

        private SchemaChild(
                String key,
                Node original,
                Node direct) {
            this.key = key;
            this.original = original;
            this.direct = direct;
        }
    }

    private static final class DirectChildSpec {

        private final String relativePointer;
        private final Node exactChild;

        private DirectChildSpec(
                String relativePointer,
                Node exactChild) {
            this.relativePointer = JsonPointer.canonicalize(
                    Objects.requireNonNull(
                            relativePointer,
                            "relativePointer"));
            this.exactChild = Objects.requireNonNull(
                    exactChild, "exactChild");
        }
    }

    private static final class DocumentPlan {

        private final SortedMap<String, ScopePlan> scopes;
        private final List<BodyCut> bodies;
        private final SortedMap<String, SourceContributionPlan>
                sourceContributions;
        private final SortedMap<String,
                List<EffectiveContractSnapshot>>
                contractsByScope;

        private DocumentPlan(
                SortedMap<String, ScopePlan> scopes,
                List<BodyCut> bodies,
                SortedMap<String, SourceContributionPlan>
                        sourceContributions,
                SortedMap<String,
                        List<EffectiveContractSnapshot>>
                        contractsByScope) {
            this.scopes = scopes;
            this.bodies = bodies;
            this.sourceContributions =
                    sourceContributions;
            this.contractsByScope =
                    contractsByScope;
        }
    }

    private static final class ScopePlan {

        private final String scopePath;
        private final Node exactScope;
        private final List<EmbeddedCut> embeddedCuts =
                new ArrayList<>();

        private ScopePlan(
                String scopePath,
                Node exactScope) {
            this.scopePath = scopePath;
            this.exactScope = exactScope;
        }
    }

    private static final class EmbeddedCut {

        private final String ownerScopePath;
        private final String absolutePointer;
        private final String declaringScopePath;
        private final EmbeddedEdgeOrigin origin;
        private final String explicitDeclarationPath;
        private final String collectionDeclarationPath;
        private final String collectionMemberKey;

        private EmbeddedCut(
                String ownerScopePath,
                String absolutePointer,
                String declaringScopePath,
                EmbeddedScopePlanView.Origin origin,
                String explicitDeclarationPath,
                String collectionDeclarationPath,
                String collectionMemberKey) {
            this.ownerScopePath =
                    ownerScopePath;
            this.absolutePointer =
                    absolutePointer;
            this.declaringScopePath = declaringScopePath;
            this.origin = origin == EmbeddedScopePlanView.Origin.EXPLICIT
                    ? EmbeddedEdgeOrigin.EXPLICIT
                    : EmbeddedEdgeOrigin.COLLECTION_MEMBER;
            this.explicitDeclarationPath = explicitDeclarationPath;
            this.collectionDeclarationPath = collectionDeclarationPath;
            this.collectionMemberKey = collectionMemberKey;
        }
    }

    private static final class BodyCut {

        private final String scopePath;
        private final String absolutePointer;
        private final String handlerTypeBlueId;
        private final String field;
        private final Node exactBody;
        private final List<String>
                sourceContributionBlueIds;
        private final SourceContributionCut
                sourceContribution;

        private BodyCut(
                String scopePath,
                String absolutePointer,
                String handlerTypeBlueId,
                String field,
                Node exactBody,
                Collection<String>
                        sourceContributionBlueIds,
                SourceContributionCut
                        sourceContribution) {
            this.scopePath = scopePath;
            this.absolutePointer =
                    absolutePointer;
            this.handlerTypeBlueId =
                    handlerTypeBlueId;
            this.field = field;
            this.exactBody = exactBody;
            this.sourceContributionBlueIds =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    sourceContributionBlueIds));
            this.sourceContribution =
                    sourceContribution;
        }
    }

    private static final class ResolvedEffectiveBody {

        private final Node exactBody;
        private final SourceContributionCut
                sourceContribution;

        private ResolvedEffectiveBody(
                Node exactBody,
                SourceContributionCut sourceContribution) {
            this.exactBody =
                    Objects.requireNonNull(
                            exactBody, "exactBody");
            this.sourceContribution =
                    sourceContribution;
        }

        private static ResolvedEffectiveBody inScope(
                Node exactBody) {
            return new ResolvedEffectiveBody(
                    exactBody,
                    null);
        }

        private static ResolvedEffectiveBody
        inSourceContribution(
                Node exactBody,
                SourceContributionCut sourceContribution) {
            return new ResolvedEffectiveBody(
                    exactBody,
                    Objects.requireNonNull(
                            sourceContribution,
                            "sourceContribution"));
        }
    }

    private static final class SourceContributionCut {

        private final String blueId;
        private final Node exactContribution;
        private final String sourcePointer;

        private SourceContributionCut(
                String blueId,
                Node exactContribution,
                String sourcePointer) {
            this.blueId =
                    Objects.requireNonNull(
                            blueId, "blueId");
            this.exactContribution =
                    Objects.requireNonNull(
                            exactContribution,
                            "exactContribution")
                            .clone();
            this.sourcePointer =
                    Objects.requireNonNull(
                            sourcePointer,
                            "sourcePointer");
        }
    }

    private static final class SourceContributionPlan {

        private final String blueId;
        private final Node exactContribution;
        private final SortedMap<String, SourceBodyCut>
                bodyCuts = new TreeMap<>();

        private SourceContributionPlan(
                String blueId,
                Node exactContribution) {
            this.blueId =
                    Objects.requireNonNull(
                            blueId, "blueId");
            this.exactContribution =
                    Objects.requireNonNull(
                            exactContribution,
                            "exactContribution")
                            .clone();
        }
    }

    private static final class SourceBodyCut {

        private final String sourcePointer;
        private final Node exactBody;

        private SourceBodyCut(
                String sourcePointer,
                Node exactBody) {
            this.sourcePointer =
                    Objects.requireNonNull(
                            sourcePointer,
                            "sourcePointer");
            this.exactBody =
                    Objects.requireNonNull(
                            exactBody, "exactBody")
                            .clone();
        }
    }
}
