package blue.coordination.engine.fastpath;

import blue.coordination.fastpath.DeltaProjectionApplier;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.processor.util.PointerUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Single-pass index of a hybrid PROCESS result. Expanded nodes are changed or
 * required headers; retained pure references are exact reuse boundaries and
 * are not expanded. The transition splitter consumes this index directly.
 */
public final class HybridResultFrontier {
    private static final Set<String> PUBLISHED_RUNTIME_TYPE_BLUE_IDS =
            publishedRuntimeTypeBlueIds();

    private final Map<String, Node> expandedByPath;
    private final Map<String, String> retainedBlueIdByPath;

    private HybridResultFrontier(
            Map<String, Node> expandedByPath,
            Map<String, String> retainedBlueIdByPath) {
        this.expandedByPath = Collections.unmodifiableMap(expandedByPath);
        this.retainedBlueIdByPath = Collections.unmodifiableMap(
                retainedBlueIdByPath);
    }

    public static HybridResultFrontier scan(Node processResult) {
        Map<String, Node> expanded = new LinkedHashMap<String, Node>();
        Map<String, String> retained = new LinkedHashMap<String, String>();
        Set<Node> active = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        visit(Objects.requireNonNull(processResult, "processResult"),
                "/", expanded, retained, active);
        return new HybridResultFrontier(expanded, retained);
    }

    /**
     * Proves that every pure reference in one hybrid PROCESS result denotes
     * the exact value retained at the same path by the prepared prior epoch.
     *
     * <p>A BlueId that merely exists somewhere in the prior Root is not
     * enough: moving that value to another path can change embedded-scope
     * topology.  The object-identity comparison below is backed by the
     * prepared epoch's verified BlueId index and therefore establishes both
     * content identity and path continuity without hashing the old Root in
     * the event loop. A prior path may itself contain the same pure BlueId
     * reference while an expanded, verified representative lives elsewhere
     * in the prepared Root. That is still an exact path binding: the BlueId
     * is the complete content identity, and the representative is the value
     * the retained-reference resolver will install.</p>
     *
     * @param processResult request-owned hybrid PROCESS result
     * @param prior prepared exact prior epoch
     * @param expectedOwner engine ownership capability for {@code prior}
     * @return non-forgeable path-bound frontier proof
     * @throws DeltaProjectionApplier.ColdProjectionRequiredException when a
     *         retained reference cannot be proved at its exact prior path
     */
    public static VerifiedHybridResultFrontier proveRetainedBindings(
            Node processResult,
            PreparedRootExecutionContext prior,
            Object expectedOwner) {
        Node result = Objects.requireNonNull(
                processResult, "processResult");
        PreparedRootExecutionContext prepared = Objects.requireNonNull(
                prior, "prior");
        Object owner = Objects.requireNonNull(
                expectedOwner, "expectedOwner");
        Node priorRoot = prepared.borrowRootVerified(owner);
        HybridResultFrontier frontier = scan(result);
        Map<String, String> exactValueBoundaryBlueIdByPath =
                new LinkedHashMap<String, String>();
        Map<String, String> newRuntimeBoundaryBlueIdByPath =
                new LinkedHashMap<String, String>();
        Map<String, String> processEmbeddedBoundaryBlueIdByPath =
                new LinkedHashMap<String, String>();
        Set<String> provedExpandedPaths = new LinkedHashSet<String>();
        Map<String, Node> priorExpandedNodes =
                new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> expanded
                : frontier.expandedByPath.entrySet()) {
            String expandedPath = expanded.getKey();
            if (isWithinAnyBoundary(
                    expandedPath,
                    exactValueBoundaryBlueIdByPath.keySet())
                    || isWithinAnyBoundary(
                            expandedPath,
                            newRuntimeBoundaryBlueIdByPath.keySet())
                    || isWithinAnyBoundary(
                            expandedPath,
                            processEmbeddedBoundaryBlueIdByPath.keySet())) {
                continue;
            }
            Node structuralPrior = structuralNodeAt(
                    priorRoot, expandedPath);
            Node priorExpanded = prepared.projectionNodeAtVerified(
                    expandedPath, owner);
            String newRuntimeBoundaryBlueId =
                    newRuntimeCheckpointBoundaryBlueId(
                            expandedPath,
                            structuralPrior,
                            priorExpanded,
                            expanded.getValue(),
                            priorRoot,
                            prepared,
                            owner);
            if (newRuntimeBoundaryBlueId != null) {
                newRuntimeBoundaryBlueIdByPath.put(
                        expandedPath, newRuntimeBoundaryBlueId);
                continue;
            }
            String processEmbeddedBoundaryBlueId =
                    processEmbeddedBoundaryBlueId(
                            expandedPath,
                            structuralPrior,
                            priorExpanded,
                            expanded.getValue());
            if (processEmbeddedBoundaryBlueId != null) {
                processEmbeddedBoundaryBlueIdByPath.put(
                        expandedPath, processEmbeddedBoundaryBlueId);
                continue;
            }
            String exactBoundaryBlueId = exactBoundaryBlueId(
                    structuralPrior,
                    priorExpanded,
                    expanded.getValue());
            if (exactBoundaryBlueId != null) {
                /* PROCESS may expand an exact reference or make canonical
                 * identity metadata explicit (notably an inferred scalar
                 * $type). Descendants belong to that identity-equivalent
                 * value, not to independent prior-Root paths. */
                exactValueBoundaryBlueIdByPath.put(
                        expandedPath, exactBoundaryBlueId);
                continue;
            }
            provedExpandedPaths.add(expandedPath);
            if (priorExpanded != null) {
                priorExpandedNodes.put(expandedPath, priorExpanded);
            }
        }
        Map<String, String> provedRetainedBlueIdByPath =
                new LinkedHashMap<String, String>();
        Map<String, Node> retainedPriorNodes =
                new LinkedHashMap<String, Node>();
        Map<String, String> newSubtreeHeaderBlueIdByPath =
                new LinkedHashMap<String, String>();
        Map<String, Node> newSubtreeHeaderResolvedNodeByPath =
                new LinkedHashMap<String, Node>();
        RetainedReferenceIndex projectionIndex =
                prepared.projectionReferences();
        RetainedReferenceIndex canonicalIndex =
                prepared.retainedReferences();
        for (Map.Entry<String, String> retained
                : frontier.retainedBlueIdByPath.entrySet()) {
            if (isWithinAnyBoundary(
                    retained.getKey(),
                    exactValueBoundaryBlueIdByPath.keySet())
                    || isWithinAnyBoundary(
                            retained.getKey(),
                            newRuntimeBoundaryBlueIdByPath.keySet())
                    || isWithinAnyBoundary(
                            retained.getKey(),
                            processEmbeddedBoundaryBlueIdByPath.keySet())) {
                continue;
            }
            Node priorNode = prepared.projectionNodeAtVerified(
                    retained.getKey(), owner);
            ExactNodeHandle admitted = canonicalIndex.find(
                    retained.getValue());
            if (priorNode == null) {
                boolean provedHeader = isProvedNewSubtreeHeader(
                        retained.getKey(),
                        retained.getValue(),
                        priorRoot,
                        result,
                        prepared,
                        projectionIndex,
                        owner);
                if (provedHeader) {
                    newSubtreeHeaderBlueIdByPath.put(
                            retained.getKey(), retained.getValue());
                    if (admitted != null) {
                        newSubtreeHeaderResolvedNodeByPath.put(
                                retained.getKey(),
                                prepared.borrowVerifiedHandle(
                                        admitted, owner));
                    }
                    continue;
                }
                throw new DeltaProjectionApplier
                        .ColdProjectionRequiredException(
                        "retained PROCESS reference has no prior path: "
                                + retained.getKey());
            }
            if (!projectionIndex.bindsExactValue(
                    priorNode, retained.getValue(), owner)) {
                throw new DeltaProjectionApplier
                        .ColdProjectionRequiredException(
                        "retained PROCESS reference is not bound to its "
                                + "exact prior path: " + retained.getKey());
            }
            if (admitted != null) {
                retainedPriorNodes.put(
                        retained.getKey(),
                        prepared.borrowVerifiedHandle(admitted, owner));
            }
            provedRetainedBlueIdByPath.put(
                    retained.getKey(), retained.getValue());
        }
        return new VerifiedHybridResultFrontier(
                prepared.sessionId(),
                prepared.epoch(),
                prepared.rootBlueId(),
                prepared.inventoryIdentity(),
                priorRoot,
                result,
                provedExpandedPaths,
                priorExpandedNodes,
                provedRetainedBlueIdByPath,
                retainedPriorNodes,
                exactValueBoundaryBlueIdByPath,
                newRuntimeBoundaryBlueIdByPath,
                processEmbeddedBoundaryBlueIdByPath,
                newSubtreeHeaderBlueIdByPath,
                newSubtreeHeaderResolvedNodeByPath);
    }

    public Map<String, Node> expandedByPath() { return expandedByPath; }
    public Map<String, String> retainedBlueIdByPath() {
        return retainedBlueIdByPath;
    }
    public int expandedNodeCount() { return expandedByPath.size(); }
    public int retainedBoundaryCount() { return retainedBlueIdByPath.size(); }


    public Set<String> changedAncestorPaths() {
        Set<String> result = new LinkedHashSet<String>();
        for (String path : expandedByPath.keySet()) {
            String current = path;
            while (current != null) {
                result.add(current);
                if ("/".equals(current)) break;
                int slash = current.lastIndexOf('/');
                current = slash <= 0 ? "/" : current.substring(0, slash);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static void visit(
            Node node,
            String path,
            Map<String, Node> expanded,
            Map<String, String> retained,
            Set<Node> active) {
        if (node.isReferenceOnly()) {
            retained.put(path, node.getBlueId());
            return;
        }
        // A shared immutable subtree can occur at several canonical paths;
        // index every path. Only an object-identity cycle is suppressed.
        if (!active.add(node)) return;
        expanded.put(path, node);
        visitNullable(node.getType(), append(path, "$type"),
                expanded, retained, active);
        visitNullable(node.getItemType(), append(path, "$itemType"),
                expanded, retained, active);
        visitNullable(node.getKeyType(), append(path, "$keyType"),
                expanded, retained, active);
        visitNullable(node.getValueType(), append(path, "$valueType"),
                expanded, retained, active);
        visitNullable(node.getContracts(), append(path, "$contracts"),
                expanded, retained, active);
        visitNullable(node.getBlue(), append(path, "$blue"),
                expanded, retained, active);
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                visit(node.getItems().get(index),
                        append(path, Integer.toString(index)),
                        expanded, retained, active);
            }
        }
        if (node.getProperties() != null) {
            List<String> names = new ArrayList<String>(
                    node.getProperties().keySet());
            Collections.sort(names);
            for (String name : names) {
                visit(node.getProperties().get(name), append(path, name),
                        expanded, retained, active);
            }
        }
        active.remove(node);
    }

    private static void visitNullable(
            Node node,
            String path,
            Map<String, Node> expanded,
            Map<String, String> retained,
            Set<Node> active) {
        if (node != null) visit(node, path, expanded, retained, active);
    }

    private static String append(String base, String segment) {
        String escaped = JsonPointer.escape(segment);
        return "/".equals(base) ? "/" + escaped : base + "/" + escaped;
    }

    private static boolean isWithinAnyBoundary(
            String path, Set<String> boundaries) {
        String current = Objects.requireNonNull(path, "path");
        while (true) {
            if (boundaries.contains(current)) return true;
            if (JsonPointer.ROOT.equals(current)) return false;
            int slash = current.lastIndexOf('/');
            current = slash <= 0
                    ? JsonPointer.ROOT
                    : current.substring(0, slash);
        }
    }

    private static String exactBoundaryBlueId(
            Node structuralPrior,
            Node projectionPrior,
            Node result) {
        Node reference = structuralPrior != null
                && structuralPrior.isReferenceOnly()
                ? structuralPrior
                : projectionPrior != null && projectionPrior.isReferenceOnly()
                        ? projectionPrior
                        : null;
        if (reference != null) {
            String expected = reference.getBlueId();
            if (expected.equals(
                    DirectBlueIdCalculator.calculateBlueId(result))) {
                return expected;
            }
        }
        if (!materializesImplicitType(projectionPrior, result)) {
            return null;
        }
        String priorBlueId = DirectBlueIdCalculator.calculateBlueId(
                projectionPrior);
        return priorBlueId.equals(
                DirectBlueIdCalculator.calculateBlueId(result))
                ? priorBlueId
                : null;
    }

    /**
     * Recognizes only the representation change in which PROCESS makes an
     * already-implied type explicit. Full BlueId equality above remains the
     * authority: an arbitrary or semantically different type cannot pass.
     */
    private static boolean materializesImplicitType(
            Node prior, Node result) {
        return prior != null
                && !prior.isReferenceOnly()
                && prior.getType() == null
                && result != null
                && !result.isReferenceOnly()
                && result.getType() != null;
    }

    /**
     * Accepts a pure type-family header only when its parent is genuinely new
     * at this path and the identity is either owned by the prepared projection
     * index or named by the closed published runtime-type registry. The latter
     * covers processor-created markers which cannot exist in the prior epoch.
     * Ordinary properties are never accepted here, even when the same BlueId
     * exists elsewhere.
     */
    private static boolean isProvedNewSubtreeHeader(
            String path,
            String blueId,
            Node priorRoot,
            Node resultRoot,
            PreparedRootExecutionContext prepared,
            RetainedReferenceIndex projectionIndex,
            Object owner) {
        if (!isTypeFamilyHeader(path)) return false;
        if (materializesCanonicalImplicitTextHeader(
                path,
                blueId,
                priorRoot,
                resultRoot,
                prepared,
                owner)) {
            return true;
        }
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return false;
        String parent = path.substring(0, slash);
        /* The reserved checkpoint path and type are accepted only as the
         * fully validated opaque runtime boundary above. */
        if (isRuntimeCheckpointPath(parent)
                || RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT.equals(blueId)) {
            return false;
        }
        if (structuralNodeAt(priorRoot, parent) != null
                || prepared.projectionNodeAtVerified(parent, owner) != null
                || structuralNodeAt(resultRoot, parent) == null) {
            return false;
        }
        return projectionIndex.find(blueId) != null
                || PUBLISHED_RUNTIME_TYPE_BLUE_IDS.contains(blueId);
    }

    /**
     * A changed Text scalar cannot be collapsed into an exact-value boundary,
     * but PROCESS may still make its already-implied canonical type explicit.
     * Prove that one header directly from both bound parents; this does not
     * authorize any sibling or descendant payload reference.
     */
    private static boolean materializesCanonicalImplicitTextHeader(
            String path,
            String blueId,
            Node priorRoot,
            Node resultRoot,
            PreparedRootExecutionContext prepared,
            Object owner) {
        if (!path.endsWith("/$type")
                || !BlueLanguageConstants.TEXT_TYPE_BLUE_ID.equals(blueId)) {
            return false;
        }
        String parent = parentPath(path);
        Node priorParent = prepared.projectionNodeAtVerified(parent, owner);
        if (priorParent == null) {
            priorParent = structuralNodeAt(priorRoot, parent);
        }
        Node resultParent = structuralNodeAt(resultRoot, parent);
        return canonicalImplicitTextScalar(priorParent, true)
                && canonicalImplicitTextScalar(resultParent, false)
                && resultParent.getType().isReferenceOnly()
                && blueId.equals(resultParent.getType().getBlueId());
    }

    private static boolean canonicalImplicitTextScalar(
            Node node, boolean requireImplicitType) {
        return node != null
                && !node.isReferenceOnly()
                && node.getValue() instanceof String
                && node.getItems() == null
                && node.getProperties() == null
                && (requireImplicitType
                        ? node.getType() == null
                        : node.getType() != null);
    }

    private static String newRuntimeCheckpointBoundaryBlueId(
            String path,
            Node structuralPrior,
            Node projectionPrior,
            Node result,
            Node priorRoot,
            PreparedRootExecutionContext prepared,
            Object owner) {
        if (!isRuntimeCheckpointPath(path)
                || structuralPrior != null
                || projectionPrior != null
                || !validRuntimeCheckpoint(result)) {
            return null;
        }
        String contractsPath = parentPath(path);
        Node priorContracts = structuralNodeAt(priorRoot, contractsPath);
        if (priorContracts == null) {
            priorContracts = prepared.projectionNodeAtVerified(
                    contractsPath, owner);
        }
        if (priorContracts == null || priorContracts.isReferenceOnly()) {
            return null;
        }
        return DirectBlueIdCalculator.calculateBlueId(result);
    }

    private static boolean isRuntimeCheckpointPath(String path) {
        List<String> segments = JsonPointer.split(path);
        int size = segments.size();
        return size >= 2
                && "$contracts".equals(segments.get(size - 2))
                && "checkpoint".equals(segments.get(size - 1));
    }

    private static boolean validRuntimeCheckpoint(Node checkpoint) {
        if (!plainObjectWithOptionalType(checkpoint, true)
                || checkpoint.getType() == null
                || !checkpoint.getType().isReferenceOnly()
                || !RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT.equals(
                        checkpoint.getType().getBlueId())
                || checkpoint.getProperties().size() != 1
                || !checkpoint.getProperties().containsKey("entries")) {
            return false;
        }
        Node entries = checkpoint.getProperties().get("entries");
        if (!plainObjectWithOptionalType(entries, false)) return false;
        for (Node entry : entries.getProperties().values()) {
            if (!validCheckpointEntry(entry)) return false;
        }
        return true;
    }

    /**
     * Proves the one existing semantic runtime boundary whose payload may
     * legitimately change the embedded-scope topology during PROCESS.  The
     * proof is deliberately narrower than general contract mutation: one
     * direct Process Embedded declaration may append exactly one canonical
     * explicit path, and no other contract field may change.  The resulting
     * catalog remains the semantic authority for that path; this boundary
     * only prevents a second descendant walk while retaining a complete
     * hash-reverified mutation witness.
     */
    private static String processEmbeddedBoundaryBlueId(
            String path,
            Node structuralPrior,
            Node projectionPrior,
            Node result) {
        if (!isDirectContractEntryPath(path)) return null;
        Node prior = structuralPrior != null
                && !structuralPrior.isReferenceOnly()
                ? structuralPrior
                : projectionPrior;
        if (!validProcessEmbeddedAppend(prior, result)) return null;
        return DirectBlueIdCalculator.calculateBlueId(result);
    }

    private static boolean isDirectContractEntryPath(String path) {
        List<String> segments = JsonPointer.split(path);
        int size = segments.size();
        return size >= 2
                && "$contracts".equals(segments.get(size - 2))
                && !segments.get(size - 1).startsWith("$");
    }

    private static boolean validProcessEmbeddedAppend(
            Node prior, Node result) {
        if (!validProcessEmbeddedDeclaration(prior)
                || !validProcessEmbeddedDeclaration(result)
                || !Objects.equals(prior.getName(), result.getName())
                || !Objects.equals(
                        prior.getDescription(), result.getDescription())
                || !sameOrMaterializedCanonicalType(
                        prior.getProperties().get("paths").getType(),
                        result.getProperties().get("paths").getType(),
                        BlueLanguageConstants.LIST_TYPE_BLUE_ID)
                || !sameOrMaterializedCanonicalType(
                        prior.getProperties().get("paths").getItemType(),
                        result.getProperties().get("paths").getItemType(),
                        BlueLanguageConstants.TEXT_TYPE_BLUE_ID)) {
            return false;
        }
        List<Node> oldPaths = prior.getProperties().get("paths").getItems();
        List<Node> newPaths = result.getProperties().get("paths").getItems();
        if (newPaths.size() != oldPaths.size() + 1) return false;
        Set<String> oldValues = new LinkedHashSet<String>();
        for (int index = 0; index < oldPaths.size(); index++) {
            String oldValue = canonicalPathValue(oldPaths.get(index));
            String newValue = canonicalPathValue(newPaths.get(index));
            if (oldValue == null
                    || !oldValue.equals(newValue)
                    || !canonicalScalarIdentity(
                            oldPaths.get(index), oldValue)
                    || !canonicalScalarIdentity(
                            newPaths.get(index), newValue)
                    || !sameExactIdentity(
                            oldPaths.get(index), newPaths.get(index))
                    || !oldValues.add(oldValue)) {
                return false;
            }
        }
        Node appendedNode = newPaths.get(newPaths.size() - 1);
        String appended = canonicalPathValue(appendedNode);
        return appended != null
                && canonicalScalarIdentity(appendedNode, appended)
                && !oldValues.contains(appended);
    }

    private static boolean validProcessEmbeddedDeclaration(Node node) {
        if (node == null
                || node.isReferenceOnly()
                || node.getType() == null
                || !hasExactTypeIdentity(
                        node, RuntimeBlueIds.PROCESS_EMBEDDED)
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getValue() != null
                || node.getItems() != null
                || node.getProperties() == null
                || node.getContracts() != null
                || node.getBlueId() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null
                || node.isInlineValue()
                || node.isPreprocessingTransformationConfiguration()
                || node.getProperties().size() != 1
                || !node.getProperties().containsKey("paths")) {
            return false;
        }
        Node paths = node.getProperties().get("paths");
        return plainPathList(paths);
    }

    private static boolean hasExactTypeIdentity(
            Node node, String expectedBlueId) {
        Node type = node == null ? null : node.getType();
        if (type == null) return false;
        if (type.isReferenceOnly()) {
            return expectedBlueId.equals(type.getBlueId());
        }
        try {
            return expectedBlueId.equals(
                    DirectBlueIdCalculator.calculateBlueId(type));
        } catch (RuntimeException invalidType) {
            return false;
        }
    }

    private static boolean sameExactIdentity(Node left, Node right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        try {
            String leftBlueId = left.isReferenceOnly()
                    ? left.getBlueId()
                    : DirectBlueIdCalculator.calculateBlueId(left);
            String rightBlueId = right.isReferenceOnly()
                    ? right.getBlueId()
                    : DirectBlueIdCalculator.calculateBlueId(right);
            return leftBlueId.equals(rightBlueId);
        } catch (RuntimeException invalidMetadata) {
            return false;
        }
    }

    /**
     * PROCESS may make the canonical List/Text headers of a path declaration
     * explicit.  Only the one-way implicit-to-exact representation change is
     * admitted; an explicit prior header may not disappear or change.
     */
    private static boolean sameOrMaterializedCanonicalType(
            Node prior,
            Node result,
            String canonicalBlueId) {
        return sameExactIdentity(prior, result)
                || (prior == null
                        && hasExactIdentity(result, canonicalBlueId));
    }

    private static boolean hasExactIdentity(
            Node node, String expectedBlueId) {
        if (node == null) return false;
        try {
            String actual = node.isReferenceOnly()
                    ? node.getBlueId()
                    : DirectBlueIdCalculator.calculateBlueId(node);
            return expectedBlueId.equals(actual);
        } catch (RuntimeException invalidMetadata) {
            return false;
        }
    }

    private static boolean canonicalScalarIdentity(
            Node item, String value) {
        try {
            return DirectBlueIdCalculator.calculateBlueId(item).equals(
                    DirectBlueIdCalculator.calculateBlueId(
                            new Node().value(value)));
        } catch (RuntimeException invalidItem) {
            return false;
        }
    }

    private static boolean plainPathList(Node paths) {
        if (paths == null
                || paths.isReferenceOnly()
                || paths.getName() != null
                || paths.getDescription() != null
                || (paths.getType() != null
                        && !hasExactIdentity(
                                paths.getType(),
                                BlueLanguageConstants.LIST_TYPE_BLUE_ID))
                || (paths.getItemType() != null
                        && !hasExactIdentity(
                                paths.getItemType(),
                                BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                || paths.getKeyType() != null
                || paths.getValueType() != null
                || paths.getValue() != null
                || paths.getItems() == null
                || paths.getProperties() != null
                || paths.getContracts() != null
                || paths.getBlueId() != null
                || paths.getSchema() != null
                || paths.getMergePolicy() != null
                || paths.getPreviousBlueId() != null
                || paths.getPosition() != null
                || paths.getBlue() != null
                || paths.isInlineValue()
                || paths.isPreprocessingTransformationConfiguration()) {
            return false;
        }
        for (Node item : paths.getItems()) {
            String value = canonicalPathValue(item);
            if (value == null || !canonicalScalarIdentity(item, value)) {
                return false;
            }
        }
        return true;
    }

    private static String canonicalPathValue(Node item) {
        if (item == null
                || item.isReferenceOnly()
                || !(item.getValue() instanceof String)
                || item.getName() != null
                || item.getDescription() != null
                || item.getItemType() != null
                || item.getKeyType() != null
                || item.getValueType() != null
                || item.getItems() != null
                || item.getProperties() != null
                || item.getContracts() != null
                || item.getBlueId() != null
                || item.getSchema() != null
                || item.getMergePolicy() != null
                || item.getPreviousBlueId() != null
                || item.getPosition() != null
                || item.getBlue() != null
                || item.isInlineValue()
                || item.isPreprocessingTransformationConfiguration()) {
            return null;
        }
        String value = (String) item.getValue();
        try {
            String canonical = PointerUtils.assertValidRuntimePointer(value);
            return value.equals(canonical) ? value : null;
        } catch (RuntimeException invalidPointer) {
            return null;
        }
    }

    private static boolean validCheckpointEntry(Node entry) {
        if (!plainObjectWithOptionalType(entry, false)
                || entry.getProperties().size() != 2
                || !entry.getProperties().containsKey("domain")
                || !entry.getProperties().containsKey("subject")) {
            return false;
        }
        Node domain = entry.getProperties().get("domain");
        Node subject = entry.getProperties().get("subject");
        return domain != null
                && domain.isReferenceOnly()
                && subject != null;
    }

    private static boolean plainObjectWithOptionalType(
            Node node, boolean allowType) {
        return node != null
                && !node.isReferenceOnly()
                && node.getName() == null
                && node.getDescription() == null
                && (allowType || node.getType() == null)
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getValue() == null
                && node.getItems() == null
                && node.getProperties() != null
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null
                && !node.isInlineValue()
                && !node.isPreprocessingTransformationConfiguration();
    }

    private static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private static boolean isTypeFamilyHeader(String path) {
        int slash = path.lastIndexOf('/');
        String segment = slash < 0 ? path : path.substring(slash + 1);
        return "$type".equals(segment)
                || "$itemType".equals(segment)
                || "$keyType".equals(segment)
                || "$valueType".equals(segment);
    }

    private static Set<String> publishedRuntimeTypeBlueIds() {
        Set<String> result = new LinkedHashSet<String>();
        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            if (!result.add(RuntimeBlueIds.blueId(key))) {
                throw new IllegalStateException(
                        "Duplicate published runtime type identity: " + key);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Node structuralNodeAt(Node root, String pointer) {
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (current == null || current.isReferenceOnly()) {
                return null;
            }
            current = structuralChild(current, segment);
        }
        return current;
    }

    private static Node structuralChild(Node parent, String segment) {
        if ("$type".equals(segment)) return parent.getType();
        if ("$itemType".equals(segment)) return parent.getItemType();
        if ("$keyType".equals(segment)) return parent.getKeyType();
        if ("$valueType".equals(segment)) return parent.getValueType();
        if ("$contracts".equals(segment)) return parent.getContracts();
        if ("$blue".equals(segment)) return parent.getBlue();
        if (JsonPointer.isArrayIndexSegment(segment)
                && parent.getItems() != null) {
            int index = Integer.parseInt(segment);
            return index < parent.getItems().size()
                    ? parent.getItems().get(index)
                    : null;
        }
        return parent.getProperties() != null
                ? parent.getProperties().get(segment)
                : null;
    }
}
