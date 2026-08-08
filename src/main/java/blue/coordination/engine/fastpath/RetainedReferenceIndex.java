package blue.coordination.engine.fastpath;

import blue.coordination.engine.CoordinationProcessingEngine
        .VerifiedNodeAccessAuthority;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable identity-to-expanded-node index attached to one committed Root.
 *
 * <p>The old path rebuilt this index for every PROCESS result and calculated
 * a BlueId for every expanded node in the prior Root. Build this object while
 * admitting/fragmenting an epoch, then reuse it for every event delivered to
 * that epoch. Entries borrow engine-owned immutable-by-convention Nodes.</p>
 */
public final class RetainedReferenceIndex {
    private final Object owner;
    private final Map<String, ExactNodeHandle> byBlueId;
    private final Map<Node, String> verifiedBlueIdByNode;
    private final long approximateRetainedGraphWeightBytes;

    private RetainedReferenceIndex(
            Object owner,
            Map<String, ExactNodeHandle> byBlueId,
            Map<Node, String> verifiedBlueIdByNode,
            long approximateRetainedGraphWeightBytes) {
        this(
                owner,
                new LinkedHashMap<String, ExactNodeHandle>(byBlueId),
                new IdentityHashMap<Node, String>(verifiedBlueIdByNode),
                approximateRetainedGraphWeightBytes,
                FreshMaps.INSTANCE);
    }

    /** Adopts maps allocated exclusively for this immutable successor. */
    private RetainedReferenceIndex(
            Object owner,
            Map<String, ExactNodeHandle> byBlueId,
            Map<Node, String> verifiedBlueIdByNode,
            long approximateRetainedGraphWeightBytes,
            FreshMaps ignored) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.byBlueId = Collections.unmodifiableMap(byBlueId);
        this.verifiedBlueIdByNode = Collections.unmodifiableMap(
                verifiedBlueIdByNode);
        if (approximateRetainedGraphWeightBytes <= 0L) {
            throw new IllegalArgumentException(
                    "retained graph weight must be positive");
        }
        this.approximateRetainedGraphWeightBytes =
                approximateRetainedGraphWeightBytes;
    }

    /**
     * Slow construction oracle. Production should call {@link Builder#add}
     * from the splitter traversal, using its already-calculated identities.
     */
    public static RetainedReferenceIndex scanOnce(
            ExactNodeHandle exactRoot,
            Object owner,
            RequestDigestMemo digests) {
        return scanAll(
                Collections.singletonList(Objects.requireNonNull(
                        exactRoot, "exactRoot")),
                owner,
                digests);
    }

    /** Builds one identity proof index across an exact Root and its views. */
    public static RetainedReferenceIndex scanAll(
            Collection<ExactNodeHandle> exactRoots,
            Object owner,
            RequestDigestMemo digests) {
        Builder builder = builder(owner);
        ArrayDeque<Node> stack = new ArrayDeque<Node>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        for (ExactNodeHandle root : Objects.requireNonNull(
                exactRoots, "exactRoots")) {
            stack.push(Objects.requireNonNull(
                    root, "exact root handle").borrowTrusted(owner));
        }
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            if (!visited.add(node) || node.isReferenceOnly()) continue;
            String blueId = digests.blueId(node);
            builder.addBound(blueId, node, digests);
            pushChildren(node, stack);
        }
        return builder.build();
    }

    public static Builder builder(Object owner) {
        return new Builder(owner);
    }

    public ExactNodeHandle find(String blueId) {
        return byBlueId.get(Objects.requireNonNull(blueId, "blueId"));
    }

    /** Borrows a retained expanded value without hash/clone. */
    public Node borrowExpanded(String blueId, Object expectedOwner) {
        requireOwner(expectedOwner);
        ExactNodeHandle handle = byBlueId.get(blueId);
        return handle == null ? null : handle.copy();
    }

    Node borrowExpandedTrusted(String blueId, Object expectedOwner) {
        requireOwner(expectedOwner);
        ExactNodeHandle handle = byBlueId.get(blueId);
        return handle == null ? null : handle.borrowTrusted(owner);
    }

    public int size() {
        return byBlueId.size();
    }

    /**
     * Returns graph weight collected by the same walk which established the
     * retained identity evidence. No second Root traversal is required.
     */
    public long approximateRetainedGraphWeightBytes() {
        return approximateRetainedGraphWeightBytes;
    }

    public Set<String> identities() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(byBlueId.keySet()));
    }

    /**
     * Adds already verified top-level view handles without rescanning their
     * complete graphs. Nested values remain fail-closed unless the exact Root
     * scan already proved them or they are pure BlueId references.
     */
    public RetainedReferenceIndex withVerifiedHandles(
            Collection<ExactNodeHandle> handles,
            Object expectedOwner) {
        requireOwner(expectedOwner);
        Collection<ExactNodeHandle> supplied = Objects.requireNonNull(
                handles, "handles");
        if (supplied.isEmpty()) return this;
        Map<String, ExactNodeHandle> identities =
                new LinkedHashMap<String, ExactNodeHandle>(byBlueId);
        Map<Node, String> bindings =
                new IdentityHashMap<Node, String>(verifiedBlueIdByNode);
        for (ExactNodeHandle handle : supplied) {
            ExactNodeHandle checked = Objects.requireNonNull(
                    handle, "handle");
            Node node = checked.borrowTrusted(expectedOwner);
            String previous = bindings.put(node, checked.blueId());
            if (previous != null && !previous.equals(checked.blueId())) {
                throw new IllegalStateException(
                        "One prepared view was bound to two identities");
            }
            identities.putIfAbsent(checked.blueId(), checked);
        }
        return new RetainedReferenceIndex(
                owner,
                identities,
                bindings,
                approximateRetainedGraphWeightBytes,
                FreshMaps.INSTANCE);
    }

    /**
     * Creates the next epoch index by structurally sharing the prior proof
     * and binding only expanded nodes from a verified sparse frontier.
     * Retained subtrees are neither traversed nor copied.
     */
    public RetainedReferenceIndex graftVerifiedExpanded(
            Node exactResolvedRoot,
            Map<String, String> expandedBlueIdByPath,
            Object expectedOwner,
            Object nextOwner,
            RequestDigestMemo digests,
            VerifiedNodeAccessAuthority accessAuthority) {
        requireOwner(expectedOwner);
        Objects.requireNonNull(accessAuthority, "accessAuthority");
        Object targetOwner = Objects.requireNonNull(nextOwner, "nextOwner");
        RequestDigestMemo memo = Objects.requireNonNull(digests, "digests");
        Map<String, ExactNodeHandle> identities =
                new LinkedHashMap<String, ExactNodeHandle>();
        for (Map.Entry<String, ExactNodeHandle> retained
                : byBlueId.entrySet()) {
            identities.put(
                    retained.getKey(),
                    owner == targetOwner
                            ? retained.getValue()
                            : retained.getValue().rebind(
                                    owner, targetOwner));
        }
        Map<Node, String> bindings =
                new IdentityHashMap<Node, String>(verifiedBlueIdByNode);
        Node root = Objects.requireNonNull(
                exactResolvedRoot, "exactResolvedRoot");
        long addedWeight = 0L;
        for (Map.Entry<String, String> expanded
                : Objects.requireNonNull(
                        expandedBlueIdByPath,
                        "expandedBlueIdByPath").entrySet()) {
            Node node = structuralNodeAt(root, expanded.getKey());
            if (node == null || node.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Verified expanded path is unavailable after graft: "
                                + expanded.getKey());
            }
            String blueId = Objects.requireNonNull(
                    expanded.getValue(), "expanded BlueId");
            memo.bindVerified(node, blueId);
            String previous = bindings.put(node, blueId);
            if (previous != null && !previous.equals(blueId)) {
                throw new IllegalStateException(
                        "One grafted Node was bound to two identities");
            }
            identities.putIfAbsent(
                    blueId,
                    ExactNodeHandle.adoptBound(
                            blueId, node, targetOwner, memo));
            addedWeight += 64L;
        }
        long weight = approximateRetainedGraphWeightBytes > Long.MAX_VALUE
                - addedWeight
                ? Long.MAX_VALUE
                : approximateRetainedGraphWeightBytes + addedWeight;
        return new RetainedReferenceIndex(
                targetOwner,
                identities,
                bindings,
                Math.max(1L, weight),
                FreshMaps.INSTANCE);
    }

    /**
     * Proves the content identity of the exact object found at a prior path.
     * Separately allocated but content-equal nodes are each recorded during
     * the prepared-epoch scan, so this does not depend on which
     * representative won the {@code byBlueId} map. Pure references carry
     * their complete content identity directly.
     */
    boolean bindsExactValue(
            Node node, String blueId, Object expectedOwner) {
        String identity = Objects.requireNonNull(blueId, "blueId");
        return identity.equals(verifiedIdentity(node, expectedOwner));
    }

    String verifiedIdentity(Node node, Object expectedOwner) {
        requireOwner(expectedOwner);
        Node checked = Objects.requireNonNull(node, "node");
        return checked.isReferenceOnly()
                ? checked.getBlueId()
                : verifiedBlueIdByNode.get(checked);
    }

    void requireOwner(Object expectedOwner) {
        if (owner != Objects.requireNonNull(expectedOwner, "expectedOwner")) {
            throw new IllegalArgumentException(
                    "Retained-reference index belongs to another epoch");
        }
    }

    private static void pushChildren(Node node, ArrayDeque<Node> stack) {
        if (node.getType() != null) stack.push(node.getType());
        if (node.getItemType() != null) stack.push(node.getItemType());
        if (node.getKeyType() != null) stack.push(node.getKeyType());
        if (node.getValueType() != null) stack.push(node.getValueType());
        if (node.getContracts() != null) stack.push(node.getContracts());
        if (node.getBlue() != null) stack.push(node.getBlue());
        if (node.getItems() != null) {
            for (Node item : node.getItems()) stack.push(item);
        }
        if (node.getProperties() != null) {
            for (Node value : node.getProperties().values()) {
                stack.push(value);
            }
        }
    }

    private static Node structuralNodeAt(Node root, String path) {
        Node current = root;
        for (String segment : JsonPointer.split(path)) {
            if (current == null || current.isReferenceOnly()) return null;
            if ("$type".equals(segment)) {
                current = current.getType();
            } else if ("$itemType".equals(segment)) {
                current = current.getItemType();
            } else if ("$keyType".equals(segment)) {
                current = current.getKeyType();
            } else if ("$valueType".equals(segment)) {
                current = current.getValueType();
            } else if ("$contracts".equals(segment)) {
                current = current.getContracts();
            } else if ("$blue".equals(segment)) {
                current = current.getBlue();
            } else if (current.getItems() != null) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException invalid) {
                    return null;
                }
                current = index >= 0 && index < current.getItems().size()
                        ? current.getItems().get(index)
                        : null;
            } else {
                current = current.getProperties() == null
                        ? null
                        : current.getProperties().get(segment);
            }
        }
        return current;
    }

    private enum FreshMaps { INSTANCE }

    public static final class Builder {
        private final Object owner;
        private final Map<String, ExactNodeHandle> values =
                new LinkedHashMap<String, ExactNodeHandle>();
        private final Map<Node, String> verifiedBindings =
                new IdentityHashMap<Node, String>();
        private final RetainedNodeWeight.Accumulator retainedWeight =
                RetainedNodeWeight.accumulator();

        private Builder(Object owner) {
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        /** Adds an internal node with an identity verified by the same walk. */
        public Builder add(String blueId, Node requestOwnedNode) {
            Node checkedNode = Objects.requireNonNull(
                    requestOwnedNode, "requestOwnedNode");
            ExactNodeHandle handle = ExactNodeHandle.adoptAndVerify(
                    blueId,
                    checkedNode,
                    owner);
            bindVerifiedNode(checkedNode, blueId);
            ExactNodeHandle previous = values.putIfAbsent(blueId, handle);
            if (previous != null) {
                // The BlueId is the complete equality proof; keep first.
                return this;
            }
            return this;
        }

        /** Adds a node already verified by the shared splitter digest memo. */
        public Builder addBound(
                String blueId,
                Node requestOwnedNode,
                RequestDigestMemo digests) {
            Node checkedNode = Objects.requireNonNull(
                    requestOwnedNode, "requestOwnedNode");
            ExactNodeHandle handle = ExactNodeHandle.adoptBound(
                    blueId,
                    checkedNode,
                    owner,
                    Objects.requireNonNull(digests, "digests"));
            bindVerifiedNode(checkedNode, blueId);
            values.putIfAbsent(blueId, handle);
            return this;
        }

        /** Adds a handle already verified in this ownership domain. */
        public Builder add(ExactNodeHandle handle) {
            ExactNodeHandle checked = Objects.requireNonNull(
                    handle, "handle");
            Node checkedNode = checked.borrowTrusted(owner);
            bindVerifiedNode(checkedNode, checked.blueId());
            values.putIfAbsent(checked.blueId(), checked);
            return this;
        }

        public RetainedReferenceIndex build() {
            return new RetainedReferenceIndex(
                    owner,
                    values,
                    verifiedBindings,
                    Math.max(1L, retainedWeight.retainedWeightBytes()));
        }

        private void bindVerifiedNode(Node node, String blueId) {
            String previous = verifiedBindings.put(node, blueId);
            if (previous != null && !previous.equals(blueId)) {
                throw new IllegalStateException(
                        "One prepared Node was bound to two identities");
            }
            if (previous == null) retainedWeight.addShallow(node);
        }
    }
}
