package blue.coordination.processor;

import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.ContractProcessor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.model.Contract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodePathEditor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

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
 * <p>Event splitting uses Language's direct-node graph fragments. Document
 * splitting deliberately uses coarser fragments: an embedded scope remains a
 * header-bearing exact fragment and an executable body remains complete. This
 * lets generic selected-body admission hand the complete body to the
 * registered Handler after its matcher succeeds, without fragmenting unrelated
 * application subtrees.</p>
 */
public final class CoordinationDocumentSplitter {

    private final Map<String, List<String>> executableBodyFieldsByType;

    /**
     * Creates a splitter with the executable-body declarations used by the
     * standard Coordination processors.
     */
    public CoordinationDocumentSplitter() {
        this(defaultExecutableBodyFields());
    }

    /**
     * Creates a splitter from the exact executable-body declarations captured
     * by an application processor registry.
     *
     * @param registry registry used by the corresponding processor
     */
    public CoordinationDocumentSplitter(ContractProcessorRegistry registry) {
        this(snapshotExecutableBodyFields(
                Objects.requireNonNull(registry, "registry")));
    }

    private CoordinationDocumentSplitter(
            Map<String, List<String>> executableBodyFieldsByType) {
        this.executableBodyFieldsByType =
                immutableBodyFieldSnapshot(executableBodyFieldsByType);
    }

    /**
     * Splits one exact Coordination Root according to Process Embedded and
     * registered executable-body declarations.
     */
    public SplitGraph splitDocument(Node admittedRoot) {
        Node exactRoot = requireExactContent(
                admittedRoot, "admittedRoot");
        String rootBlueId =
                BlueIdCalculator.calculateBlueId(
                        exactRoot);

        DocumentPlan plan = discoverDocumentPlan(exactRoot);
        SortedMap<String, Node> fragments = new TreeMap<>();
        List<FragmentMetadata> metadata = new ArrayList<>();

        for (ScopePlan scope : plan.scopes.values()) {
            Node scopeFragment = fragmentScope(scope);
            String scopeBlueId =
                    BlueIdCalculator.calculateBlueId(scope.exactScope);
            requireIdentity(
                    scopeBlueId,
                    scopeFragment,
                    "Coordination scope " + scope.scopePath);
            fragments.put(scopeBlueId, scopeFragment);
            metadata.add(new FragmentMetadata(
                    scopeBlueId,
                    "/".equals(scope.scopePath)
                            ? FragmentKind.DOCUMENT_ROOT
                            : FragmentKind.EMBEDDED_ROOT,
                    scope.scopePath,
                    scope.scopePath,
                    null,
                    null));
        }

        /*
         * Bodies intentionally override a shallower identity-equivalent
         * fragment. Contract conversion needs the selected complete body after
         * admission, while its direct step children are not dispatch headers.
         */
        for (BodyCut body : plan.bodies) {
            if (body.exactBody == null
                    || body.exactBody.isReferenceOnly()) {
                continue;
            }
            String bodyBlueId =
                    BlueIdCalculator.calculateBlueId(body.exactBody);
            fragments.put(bodyBlueId, body.exactBody.clone());
            metadata.add(new FragmentMetadata(
                    bodyBlueId,
                    FragmentKind.EXECUTABLE_BODY,
                    body.scopePath,
                    body.absolutePointer,
                    body.handlerTypeBlueId,
                    body.field));
        }

        Node fragmentedRoot = fragments.get(rootBlueId);
        if (fragmentedRoot == null) {
            throw new IllegalStateException(
                    "Coordination Root fragment was not retained");
        }
        requireIdentity(
                rootBlueId,
                fragmentedRoot,
                "Coordination Root");
        return new SplitGraph(
                rootBlueId,
                exactRoot,
                fragmentedRoot,
                fragments,
                metadata);
    }

    /**
     * Splits one exact Event into Language direct-node fragments.
     */
    public SplitGraph splitEvent(Node admittedEvent) {
        Node exactEvent = requireExactContent(
                admittedEvent, "admittedEvent");
        if (containsCyclicMemberReference(
                exactEvent)) {
            return splitCyclicAwareEvent(
                    exactEvent);
        }
        ExactNodeGraphFragments exactGraph =
                new ExactNodeGraphFragments(exactEvent);
        ExactNodeGraphFragments.RootRepresentation root =
                exactGraph.roots().get(0);
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
        return new SplitGraph(
                root.blueId(),
                root.original(),
                root.directFragment(),
                exactGraph.fragments(),
                metadata);
    }

    private SplitGraph splitCyclicAwareEvent(
            Node exactEvent) {
        CyclicAwareEventFragments eventGraph =
                new CyclicAwareEventFragments(
                        exactEvent);
        List<FragmentMetadata> metadata =
                new ArrayList<>();
        for (String blueId
                : eventGraph.fragments.keySet()) {
            boolean eventRoot =
                    eventGraph.rootBlueId.equals(
                            blueId);
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
        return new SplitGraph(
                eventGraph.rootBlueId,
                exactEvent,
                eventGraph.directRoot,
                eventGraph.fragments,
                metadata);
    }

    /**
     * Prepares the exact two PROCESS arguments and a lazily verified fragment
     * provider. Preparation itself does not consume either fragment; BlueId
     * evidence is verified when PROCESS first demands it. Execution evidence
     * remains out-of-band environment evidence, not a third semantic input.
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
                new VerifyingNodeProvider(
                        Objects.requireNonNull(
                                fragmentProvider, "fragmentProvider"));

        return new PreparedProcessingInput(
                new Node().blueId(checkedRoot),
                new Node().blueId(checkedEvent),
                checkedEvidence,
                verifiedProvider);
    }

    private DocumentPlan discoverDocumentPlan(Node root) {
        SortedMap<String, ScopePlan> scopes =
                new TreeMap<>();
        Deque<ScopePlan> pending = new ArrayDeque<>();
        ScopePlan rootScope =
                new ScopePlan("/", root);
        scopes.put("/", rootScope);
        pending.add(rootScope);
        List<BodyCut> bodies = new ArrayList<>();

        while (!pending.isEmpty()) {
            ScopePlan scope = pending.removeFirst();
            discoverBodies(scope, bodies);
            List<String> declaredPaths =
                    new ArrayList<>(
                            declaredEmbeddedPaths(scope));
            Collections.sort(
                    declaredPaths,
                    new Comparator<String>() {
                        @Override
                        public int compare(
                                String left,
                                String right) {
                            int depthComparison =
                                    Integer.compare(
                                            JsonPointer.split(left)
                                                    .size(),
                                            JsonPointer.split(right)
                                                    .size());
                            return depthComparison != 0
                                    ? depthComparison
                                    : left.compareTo(right);
                        }
                    });
            for (String relativePath
                    : declaredPaths) {
                String absolutePath =
                        PointerUtils.resolvePointer(
                                scope.scopePath,
                                relativePath);
                Node child = NodePathEditor.getOrNull(
                        root, absolutePath);
                if (child == null) {
                    throw new IllegalArgumentException(
                            "Process Embedded path "
                                    + relativePath
                                    + " at "
                                    + scope.scopePath
                                    + " selects no child");
                }
                if (child.isReferenceOnly()) {
                    throw new IllegalArgumentException(
                            "Process Embedded child "
                                    + absolutePath
                                    + " must be admitted as exact content before splitting");
                }
                if (child.getRawValue() != null
                        || child.getItems() != null) {
                    throw new IllegalArgumentException(
                            "Process Embedded child "
                                    + absolutePath
                                    + " must be an object Root");
                }
                if (scopes.containsKey(absolutePath)) {
                    throw new IllegalArgumentException(
                            "Process Embedded scope is declared more than once: "
                                    + absolutePath);
                }
                ScopePlan childScope =
                        new ScopePlan(absolutePath, child);
                ScopePlan containingScope =
                        nearestDeclaredAncestor(
                                scopes,
                                absolutePath);
                scopes.put(absolutePath, childScope);
                pending.addLast(childScope);
                containingScope.embeddedCuts.add(
                        new EmbeddedCut(
                                PointerUtils.relativizePointer(
                                        containingScope.scopePath,
                                        absolutePath),
                                child));
            }
        }

        Collections.sort(
                bodies,
                Comparator.comparing(
                        body -> body.absolutePointer));
        return new DocumentPlan(scopes, bodies);
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

    private void discoverBodies(
            ScopePlan scope,
            List<BodyCut> allBodies) {
        Node contracts = scope.exactScope.getContracts();
        if (contracts == null) {
            return;
        }
        if (contracts.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Coordination contract headers at "
                            + scope.scopePath
                            + " must be admitted before splitting");
        }
        if (contracts.getProperties() == null) {
            return;
        }

        SortedMap<String, Node> ordered =
                new TreeMap<>(contracts.getProperties());
        for (Map.Entry<String, Node> entry
                : ordered.entrySet()) {
            Node contract = entry.getValue();
            if (contract == null) {
                continue;
            }
            if (contract.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Coordination contract header '"
                                + entry.getKey()
                                + "' at "
                                + scope.scopePath
                                + " must be admitted before splitting");
            }
            String typeBlueId =
                    exactTypeBlueId(contract);
            List<String> fields =
                    executableBodyFieldsByType.get(
                            typeBlueId);
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            for (String field : fields) {
                Node body = contract.getProperties() != null
                        ? contract.getProperties().get(field)
                        : null;
                if (body == null) {
                    continue;
                }
                String relativePointer =
                        JsonPointer.toPointer(
                                Arrays.asList(
                                        "contracts",
                                        entry.getKey(),
                                        field));
                String absolutePointer =
                        PointerUtils.resolvePointer(
                                scope.scopePath,
                                relativePointer);
                BodyCut cut = new BodyCut(
                        scope.scopePath,
                        relativePointer,
                        absolutePointer,
                        typeBlueId,
                        field,
                        body);
                scope.bodyCuts.add(cut);
                allBodies.add(cut);
            }
        }
    }

    private List<String> declaredEmbeddedPaths(
            ScopePlan scope) {
        Node contracts = scope.exactScope.getContracts();
        if (contracts == null
                || contracts.getProperties() == null) {
            return Collections.emptyList();
        }
        Node embedded = contracts.getProperties().get(
                blue.language.processor.util
                        .ProcessorContractConstants.KEY_EMBEDDED);
        if (embedded == null) {
            return Collections.emptyList();
        }
        String typeBlueId = exactTypeBlueId(embedded);
        if (!RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                typeBlueId)) {
            throw new IllegalArgumentException(
                    "Reserved embedded contract at "
                            + scope.scopePath
                            + " is not the exact Process Embedded runtime type");
        }
        Node paths = embedded.getProperties() != null
                ? embedded.getProperties().get("paths")
                : null;
        if (paths == null) {
            return Collections.emptyList();
        }
        if (paths.getItems() == null) {
            throw new IllegalArgumentException(
                    "Process Embedded paths at "
                            + scope.scopePath
                            + " must be a List of Text");
        }

        List<String> result =
                new ArrayList<>(paths.getItems().size());
        Set<String> unique = new LinkedHashSet<>();
        for (Node pathNode : paths.getItems()) {
            Object raw =
                    pathNode != null
                            ? pathNode.getRawValue()
                            : null;
            if (!(raw instanceof String)) {
                throw new IllegalArgumentException(
                        "Process Embedded path at "
                                + scope.scopePath
                                + " must be Text");
            }
            String normalized =
                    PointerUtils.assertValidRuntimePointer(
                            (String) raw);
            if ("/".equals(normalized)) {
                throw new IllegalArgumentException(
                        "Process Embedded path '/' cannot embed its declaring scope");
            }
            if (!unique.add(normalized)) {
                throw new IllegalArgumentException(
                        "Process Embedded paths must be unique at "
                                + scope.scopePath);
            }
            result.add(normalized);
        }
        return result;
    }

    private Node fragmentScope(ScopePlan scope) {
        Node fragment = scope.exactScope.clone();
        List<EmbeddedCut> embedded =
                new ArrayList<>(scope.embeddedCuts);
        Collections.sort(
                embedded,
                Comparator.comparing(
                        cut -> cut.relativePointer));
        for (EmbeddedCut cut : embedded) {
            String childBlueId =
                    BlueIdCalculator.calculateBlueId(
                            cut.exactChild);
            NodePathEditor.put(
                    fragment,
                    cut.relativePointer,
                    new Node().blueId(
                            childBlueId));
        }

        List<BodyCut> bodies =
                new ArrayList<>(scope.bodyCuts);
        Collections.sort(
                bodies,
                Comparator.comparing(
                        cut -> cut.relativePointer));
        for (BodyCut cut : bodies) {
            if (cut.exactBody.isReferenceOnly()) {
                continue;
            }
            NodePathEditor.put(
                    fragment,
                    cut.relativePointer,
                    new Node().blueId(
                            BlueIdCalculator.calculateBlueId(
                                    cut.exactBody)));
        }
        return fragment;
    }

    private static String exactTypeBlueId(Node contract) {
        Node type =
                contract != null ? contract.getType() : null;
        if (type == null) {
            return null;
        }
        return type.isReferenceOnly()
                ? type.getBlueId()
                : BlueIdCalculator.calculateBlueId(type);
    }

    private static boolean containsCyclicMemberReference(
            Node root) {
        Set<Node> visited =
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>());
        return containsCyclicMemberReference(
                root, visited);
    }

    private static boolean containsCyclicMemberReference(
            Node node,
            Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return false;
        }
        if (isCyclicMemberId(node.getBlueId())
                || isCyclicMemberId(
                node.getPreviousBlueId())) {
            return true;
        }
        if (containsCyclicMemberReference(
                node.getType(), visited)
                || containsCyclicMemberReference(
                node.getItemType(), visited)
                || containsCyclicMemberReference(
                node.getKeyType(), visited)
                || containsCyclicMemberReference(
                node.getValueType(), visited)
                || containsCyclicMemberReference(
                node.getContracts(), visited)
                || containsCyclicMemberReference(
                node.getBlue(), visited)
                || containsCyclicMemberReference(
                node.getSchema(), visited)) {
            return true;
        }
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                if (containsCyclicMemberReference(
                        item, visited)) {
                    return true;
                }
            }
        }
        if (node.getProperties() != null) {
            for (Node property
                    : node.getProperties().values()) {
                if (containsCyclicMemberReference(
                        property, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsCyclicMemberReference(
            Schema schema,
            Set<Node> visited) {
        if (schema == null) {
            return false;
        }
        if (isCyclicMemberId(schema.getBlueId())) {
            return true;
        }
        if (containsCyclicMemberReference(
                schema.getRequired(), visited)
                || containsCyclicMemberReference(
                schema.getMinLength(), visited)
                || containsCyclicMemberReference(
                schema.getMaxLength(), visited)
                || containsCyclicMemberReference(
                schema.getMinimum(), visited)
                || containsCyclicMemberReference(
                schema.getMaximum(), visited)
                || containsCyclicMemberReference(
                schema.getExclusiveMinimum(), visited)
                || containsCyclicMemberReference(
                schema.getExclusiveMaximum(), visited)
                || containsCyclicMemberReference(
                schema.getMultipleOf(), visited)
                || containsCyclicMemberReference(
                schema.getMinItems(), visited)
                || containsCyclicMemberReference(
                schema.getMaxItems(), visited)
                || containsCyclicMemberReference(
                schema.getUniqueItems(), visited)
                || containsCyclicMemberReference(
                schema.getMinFields(), visited)
                || containsCyclicMemberReference(
                schema.getMaxFields(), visited)) {
            return true;
        }
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                if (containsCyclicMemberReference(
                        value, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCyclicMemberId(
            String blueId) {
        return blueId != null
                && blueId.indexOf('#') >= 0;
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
                BlueIdCalculator.calculateBlueId(fragment);
        if (!expectedBlueId.equals(actualBlueId)) {
            throw new IllegalStateException(
                    label
                            + " fragmentation changed BlueId from "
                            + expectedBlueId
                            + " to "
                            + actualBlueId);
        }
    }

    private static Map<String, List<String>>
    defaultExecutableBodyFields() {
        SequentialWorkflowRunner runner =
                new SequentialWorkflowRunner(
                        Collections.emptyList());
        try {
            ContractProcessorRegistry registry =
                    ContractProcessorRegistryBuilder
                            .create()
                            .register(
                                    new SequentialWorkflowProcessor(
                                            runner))
                            .register(
                                    new SequentialWorkflowOperationProcessor(
                                            runner))
                            .register(
                                    new ChatWorkflowOperationProcessor(
                                            runner))
                            .build();
            return snapshotExecutableBodyFields(
                    registry);
        } finally {
            runner.close();
        }
    }

    private static Map<String, List<String>>
    snapshotExecutableBodyFields(
            ContractProcessorRegistry registry) {
        SortedMap<String, List<String>> result =
                new TreeMap<>();
        for (Map.Entry<String, ContractProcessor<? extends Contract>>
                entry : registry.processors().entrySet()) {
            if (!(entry.getValue()
                    instanceof HandlerProcessor)) {
                continue;
            }
            List<String> fields =
                    registry.executableBodyFields(
                            entry.getKey());
            if (fields != null && !fields.isEmpty()) {
                result.put(
                        entry.getKey(),
                        new ArrayList<>(fields));
            }
        }
        return result;
    }

    private static Map<String, List<String>>
    immutableBodyFieldSnapshot(
            Map<String, List<String>> source) {
        SortedMap<String, List<String>> result =
                new TreeMap<>();
        for (Map.Entry<String, List<String>>
                entry : source.entrySet()) {
            String typeBlueId =
                    BlueIds.requireBlueIdOrCyclicMember(
                            entry.getKey(),
                            "handlerTypeBlueId");
            List<String> fields =
                    new ArrayList<>();
            for (String field : entry.getValue()) {
                if (field == null
                        || field.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Executable body field must be non-empty");
                }
                fields.add(field);
            }
            result.put(
                    typeBlueId,
                    Collections.unmodifiableList(
                            fields));
        }
        return Collections.unmodifiableMap(
                result);
    }

    /**
     * The exact physical fragment inventory for one semantic input.
     */
    public static final class SplitGraph {

        private final String rootBlueId;
        private final Node originalRoot;
        private final Node fragmentedRoot;
        private final SortedMap<String, Node> fragments;
        private final List<FragmentMetadata> metadata;
        private final NodeProvider provider;

        private SplitGraph(
                String rootBlueId,
                Node originalRoot,
                Node fragmentedRoot,
                Map<String, Node> fragments,
                Collection<FragmentMetadata> metadata) {
            this.rootBlueId =
                    Objects.requireNonNull(
                            rootBlueId, "rootBlueId");
            this.originalRoot =
                    Objects.requireNonNull(
                            originalRoot, "originalRoot")
                            .clone();
            this.fragmentedRoot =
                    Objects.requireNonNull(
                            fragmentedRoot,
                            "fragmentedRoot")
                            .clone();
            this.fragments =
                    immutableFragments(fragments);
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
            this.provider =
                    verifiedProvider(this.fragments);
            requireIdentity(
                    rootBlueId,
                    this.fragmentedRoot,
                    "Split Root");
        }

        public String rootBlueId() {
            return rootBlueId;
        }

        public Node originalRoot() {
            return originalRoot.clone();
        }

        public Node fragmentedRoot() {
            return fragmentedRoot.clone();
        }

        public Node pureReference() {
            return new Node().blueId(
                    rootBlueId);
        }

        public Map<String, Node> fragments() {
            return immutableFragments(
                    fragments);
        }

        public NodeProvider provider() {
            return provider;
        }

        public List<FragmentMetadata> metadata() {
            return metadata;
        }
    }

    public enum FragmentKind {
        DOCUMENT_ROOT,
        EMBEDDED_ROOT,
        EXECUTABLE_BODY,
        EVENT_ROOT,
        EVENT_FRAGMENT
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

    /**
     * Event fragments may contain published cyclic-set member type references.
     * Those references are external exact identities, not local fragments.
     * Language's ordinary graph helper intentionally rejects them because it
     * cannot prove cyclic-set content; this builder never claims or expands
     * that content and records only locally inline nodes under plain BlueIds.
     */
    private static final class CyclicAwareEventFragments {

        private final IdentityHashMap<Node, EventFragmentRecord> records =
                new IdentityHashMap<>();
        private final IdentityHashMap<Node, String> active =
                new IdentityHashMap<>();
        private final SortedMap<String, Node> fragments =
                new TreeMap<>();
        private final SortedMap<String, SortedSet<String>> edges =
                new TreeMap<>();
        private final String rootBlueId;
        private final Node directRoot;

        private CyclicAwareEventFragments(
                Node exactEvent) {
            EventFragmentRecord root =
                    record(exactEvent, "event");
            rejectLocalFragmentCycles();
            this.rootBlueId = root.blueId;
            this.directRoot =
                    root.directFragment.clone();
        }

        private EventFragmentRecord record(
                Node node,
                String path) {
            if (node.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Exact event content at "
                                + path
                                + " must not be a pure reference");
            }
            if (node.getBlueId() != null) {
                throw new IllegalArgumentException(
                        "Exact event content at "
                                + path
                                + " must not mix its own BlueId with inline content");
            }
            EventFragmentRecord retained =
                    records.get(node);
            if (retained != null) {
                return retained;
            }
            String activePath =
                    active.put(node, path);
            if (activePath != null) {
                throw new IllegalArgumentException(
                        "Blue object cycle between "
                                + activePath
                                + " and "
                                + path
                                + " cannot be fragmented");
            }
            try {
                String originalBlueId =
                        BlueIds.requirePlainBlueId(
                                BlueIdCalculator
                                        .calculateBlueId(
                                                node),
                                path);
                SortedSet<String> directEdges =
                        new TreeSet<>();
                Node direct = node.clone();

                direct.type(referenceFor(
                        node.getType(),
                        path + "/type",
                        directEdges));
                direct.itemType(referenceFor(
                        node.getItemType(),
                        path + "/itemType",
                        directEdges));
                direct.keyType(referenceFor(
                        node.getKeyType(),
                        path + "/keyType",
                        directEdges));
                direct.valueType(referenceFor(
                        node.getValueType(),
                        path + "/valueType",
                        directEdges));
                direct.contracts(referenceFor(
                        node.getContracts(),
                        path + "/contracts",
                        directEdges));
                direct.blue(referenceFor(
                        node.getBlue(),
                        path + "/blue",
                        directEdges));

                if (node.getItems() != null) {
                    List<Node> items =
                            new ArrayList<>(
                                    node.getItems().size());
                    for (int index = 0;
                         index < node.getItems().size();
                         index++) {
                        items.add(referenceFor(
                                node.getItems().get(index),
                                path + "/items/" + index,
                                directEdges));
                    }
                    direct.items(items);
                }

                if (node.getProperties() != null) {
                    SortedMap<String, Node> ordered =
                            new TreeMap<>(
                                    node.getProperties());
                    Map<String, Node> properties =
                            new TreeMap<>();
                    for (Map.Entry<String, Node> entry
                            : ordered.entrySet()) {
                        Node child = entry.getValue();
                        properties.put(
                                entry.getKey(),
                                isRawHashProperty(
                                        entry.getKey())
                                        ? cloneOrNull(child)
                                        : referenceFor(
                                                child,
                                                path + "/"
                                                        + entry.getKey(),
                                                directEdges));
                    }
                    direct.properties(properties);
                }

                direct.schema(fragmentSchema(
                        node.getSchema(),
                        path + "/schema",
                        directEdges));
                if (node.getPreviousBlueId() != null) {
                    String previous =
                            BlueIds
                                    .requireBlueIdOrCyclicMember(
                                            node.getPreviousBlueId(),
                                            path
                                                    + "/$previous/blueId");
                    directEdges.add(previous);
                }

                requireIdentity(
                        originalBlueId,
                        direct,
                        "Event fragment at " + path);
                Node existing =
                        fragments.get(originalBlueId);
                if (existing == null) {
                    fragments.put(
                            originalBlueId,
                            direct.clone());
                }
                SortedSet<String> retainedEdges =
                        edges.computeIfAbsent(
                                originalBlueId,
                                ignored -> new TreeSet<>());
                retainedEdges.addAll(directEdges);
                EventFragmentRecord created =
                        new EventFragmentRecord(
                                originalBlueId,
                                direct);
                records.put(node, created);
                return created;
            } finally {
                active.remove(node);
            }
        }

        private Node referenceFor(
                Node child,
                String path,
                Set<String> directEdges) {
            if (child == null) {
                return null;
            }
            String childBlueId;
            if (child.isReferenceOnly()) {
                childBlueId =
                        BlueIds
                                .requireBlueIdOrCyclicMember(
                                        child.getBlueId(),
                                        path + "/blueId");
            } else {
                childBlueId =
                        record(child, path).blueId;
            }
            directEdges.add(childBlueId);
            return new Node().blueId(
                    childBlueId);
        }

        private Schema fragmentSchema(
                Schema schema,
                String path,
                Set<String> directEdges) {
            if (schema == null) {
                return null;
            }
            if (schema.isReferenceOnly()) {
                String schemaBlueId =
                        BlueIds
                                .requireBlueIdOrCyclicMember(
                                        schema.getBlueId(),
                                        path + "/blueId");
                directEdges.add(schemaBlueId);
                return new Schema().blueId(
                        schemaBlueId);
            }
            if (schema.getBlueId() != null) {
                throw new IllegalArgumentException(
                        "Exact event schema at "
                                + path
                                + " must not mix its own BlueId with inline content");
            }

            Schema direct = schema.clone();
            direct.minimum(fragmentSchemaValue(
                    schema.getMinimum(),
                    path + "/minimum",
                    directEdges));
            direct.maximum(fragmentSchemaValue(
                    schema.getMaximum(),
                    path + "/maximum",
                    directEdges));
            direct.exclusiveMinimum(
                    fragmentSchemaValue(
                            schema.getExclusiveMinimum(),
                            path
                                    + "/exclusiveMinimum",
                            directEdges));
            direct.exclusiveMaximum(
                    fragmentSchemaValue(
                            schema.getExclusiveMaximum(),
                            path
                                    + "/exclusiveMaximum",
                            directEdges));
            direct.multipleOf(
                    fragmentSchemaValue(
                            schema.getMultipleOf(),
                            path + "/multipleOf",
                            directEdges));
            if (schema.getEnum() != null) {
                List<Node> values =
                        new ArrayList<>(
                                schema.getEnum().size());
                for (int index = 0;
                     index < schema.getEnum().size();
                     index++) {
                    values.add(fragmentSchemaValue(
                            schema.getEnum().get(index),
                            path + "/enum/" + index,
                            directEdges));
                }
                direct.enumValues(values);
            }
            return direct;
        }

        private Node fragmentSchemaValue(
                Node value,
                String path,
                Set<String> directEdges) {
            if (value == null) {
                return null;
            }
            return isPlainSchemaScalar(value)
                    ? value.clone()
                    : referenceFor(
                            value, path, directEdges);
        }

        private void rejectLocalFragmentCycles() {
            Map<String, LocalVisitState> states =
                    new TreeMap<>();
            for (String blueId : fragments.keySet()) {
                rejectLocalFragmentCycles(
                        blueId,
                        states,
                        new ArrayList<String>());
            }
        }

        private void rejectLocalFragmentCycles(
                String blueId,
                Map<String, LocalVisitState> states,
                List<String> path) {
            LocalVisitState state =
                    states.get(blueId);
            if (state == LocalVisitState.COMPLETE) {
                return;
            }
            if (state == LocalVisitState.ACTIVE) {
                path.add(blueId);
                throw new IllegalArgumentException(
                        "Mixed reference/object cycle cannot be fragmented: "
                                + path);
            }
            states.put(
                    blueId, LocalVisitState.ACTIVE);
            path.add(blueId);
            SortedSet<String> targets =
                    edges.get(blueId);
            if (targets != null) {
                for (String target : targets) {
                    if (fragments.containsKey(target)) {
                        rejectLocalFragmentCycles(
                                target,
                                states,
                                new ArrayList<>(
                                        path));
                    }
                }
            }
            states.put(
                    blueId, LocalVisitState.COMPLETE);
        }

        private static boolean isRawHashProperty(
                String key) {
            return "name".equals(key)
                    || "description".equals(key)
                    || "value".equals(key);
        }

        private static Node cloneOrNull(
                Node node) {
            return node != null
                    ? node.clone()
                    : null;
        }
    }

    private static boolean isPlainSchemaScalar(
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

    private static final class EventFragmentRecord {

        private final String blueId;
        private final Node directFragment;

        private EventFragmentRecord(
                String blueId,
                Node directFragment) {
            this.blueId = blueId;
            this.directFragment =
                    directFragment.clone();
        }
    }

    private enum LocalVisitState {
        ACTIVE,
        COMPLETE
    }

    private static final class DocumentPlan {

        private final SortedMap<String, ScopePlan> scopes;
        private final List<BodyCut> bodies;

        private DocumentPlan(
                SortedMap<String, ScopePlan> scopes,
                List<BodyCut> bodies) {
            this.scopes = scopes;
            this.bodies = bodies;
        }
    }

    private static final class ScopePlan {

        private final String scopePath;
        private final Node exactScope;
        private final List<EmbeddedCut> embeddedCuts =
                new ArrayList<>();
        private final List<BodyCut> bodyCuts =
                new ArrayList<>();

        private ScopePlan(
                String scopePath,
                Node exactScope) {
            this.scopePath = scopePath;
            this.exactScope = exactScope;
        }
    }

    private static final class EmbeddedCut {

        private final String relativePointer;
        private final Node exactChild;

        private EmbeddedCut(
                String relativePointer,
                Node exactChild) {
            this.relativePointer =
                    relativePointer;
            this.exactChild = exactChild;
        }
    }

    private static final class BodyCut {

        private final String scopePath;
        private final String relativePointer;
        private final String absolutePointer;
        private final String handlerTypeBlueId;
        private final String field;
        private final Node exactBody;

        private BodyCut(
                String scopePath,
                String relativePointer,
                String absolutePointer,
                String handlerTypeBlueId,
                String field,
                Node exactBody) {
            this.scopePath = scopePath;
            this.relativePointer =
                    relativePointer;
            this.absolutePointer =
                    absolutePointer;
            this.handlerTypeBlueId =
                    handlerTypeBlueId;
            this.field = field;
            this.exactBody = exactBody;
        }
    }
}
