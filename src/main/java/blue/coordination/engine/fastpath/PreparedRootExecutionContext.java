package blue.coordination.engine.fastpath;

import blue.coordination.engine.CoordinationProcessingEngine
        .VerifiedNodeAccessAuthority;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable warm execution state for one exact session epoch. The context is
 * created after admission/commit and replaced atomically with the session
 * revision. PROCESS must not rediscover these facts from Node graphs.
 */
public final class PreparedRootExecutionContext {
    private final String sessionId;
    private final long epoch;
    private final String rootBlueId;
    private final String inventoryIdentity;
    private final Object owner;
    private final ExactNodeHandle exactRoot;
    private final RetainedReferenceIndex retainedReferences;
    private final RetainedReferenceIndex projectionReferences;
    private final Set<String> fragmentBlueIds;
    private final Map<String, ExactNodeHandle> preparedProcessingViews;
    private final long approximateRetainedWeightBytes;

    public PreparedRootExecutionContext(
            String sessionId,
            long epoch,
            CoordinationFragmentInventory inventory,
            ExactNodeHandle exactRoot,
            RetainedReferenceIndex retainedReferences,
            Map<String, ExactNodeHandle> preparedProcessingViews,
            Object owner) {
        this(
                sessionId,
                epoch,
                inventory,
                exactRoot,
                retainedReferences,
                retainedReferences,
                preparedProcessingViews,
                owner);
    }

    public PreparedRootExecutionContext(
            String sessionId,
            long epoch,
            CoordinationFragmentInventory inventory,
            ExactNodeHandle exactRoot,
            RetainedReferenceIndex retainedReferences,
            RetainedReferenceIndex projectionReferences,
            Map<String, ExactNodeHandle> preparedProcessingViews,
            Object owner) {
        this.sessionId = requireText(sessionId, "sessionId");
        if (epoch < 0L) throw new IllegalArgumentException(
                "epoch must be non-negative");
        this.epoch = epoch;
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        this.rootBlueId = checked.rootBlueId();
        this.inventoryIdentity = checked.inventoryIdentity();
        this.owner = Objects.requireNonNull(owner, "owner");
        this.exactRoot = Objects.requireNonNull(exactRoot, "exactRoot");
        if (!rootBlueId.equals(exactRoot.blueId())) {
            throw new IllegalArgumentException(
                    "Root handle does not match inventory");
        }
        borrowHandle(exactRoot);
        this.retainedReferences = Objects.requireNonNull(
                retainedReferences, "retainedReferences");
        this.retainedReferences.requireOwner(owner);
        this.projectionReferences = Objects.requireNonNull(
                projectionReferences, "projectionReferences");
        this.projectionReferences.requireOwner(owner);
        this.fragmentBlueIds = Collections.unmodifiableSet(
                new LinkedHashSet<String>(checked.fragmentBlueIds()));
        Map<String, ExactNodeHandle> views =
                new LinkedHashMap<String, ExactNodeHandle>();
        for (Map.Entry<String, ExactNodeHandle> entry
                : Objects.requireNonNull(
                        preparedProcessingViews,
                        "preparedProcessingViews").entrySet()) {
            if (!fragmentBlueIds.contains(entry.getKey())
                    || !entry.getKey().equals(entry.getValue().blueId())) {
                throw new IllegalArgumentException(
                        "Prepared view is outside inventory: "
                                + entry.getKey());
            }
            borrowHandle(entry.getValue());
            views.put(entry.getKey(), entry.getValue());
        }
        this.preparedProcessingViews = Collections.unmodifiableMap(views);
        this.approximateRetainedWeightBytes = retainedWeight(
                this.retainedReferences,
                this.projectionReferences,
                this.fragmentBlueIds.size(),
                this.preparedProcessingViews.size());
    }

    public String sessionId() { return sessionId; }
    public long epoch() { return epoch; }
    public String rootBlueId() { return rootBlueId; }
    public String inventoryIdentity() { return inventoryIdentity; }
    public Set<String> fragmentBlueIds() { return fragmentBlueIds; }
    public long approximateRetainedWeightBytes() {
        return approximateRetainedWeightBytes;
    }
    public RetainedReferenceIndex retainedReferences() {
        return retainedReferences;
    }

    public RetainedReferenceIndex projectionReferences() {
        return projectionReferences;
    }

    public Node borrowRoot(Object expectedOwner) {
        requireOwner(expectedOwner);
        return exactRoot.borrow(owner);
    }

    /** Engine-only zero-copy Root access guarded by private-held authority. */
    public Node borrowRootVerified(
            Object expectedOwner,
            VerifiedNodeAccessAuthority accessAuthority) {
        requireOwner(expectedOwner);
        return exactRoot.borrowVerified(owner, accessAuthority);
    }

    Node borrowRootVerified(Object expectedOwner) {
        requireOwner(expectedOwner);
        return exactRoot.borrowTrusted(owner);
    }

    /**
     * Selects one prior PROCESS path while expanding only verified,
     * identity-equivalent header views. Canonical storage remains untouched.
     */
    public Node projectionNodeAt(
            String pointer, Object expectedOwner) {
        Node selected = projectionNodeAtVerified(pointer, expectedOwner);
        return selected == null ? null : selected.clone();
    }

    Node projectionNodeAtVerified(
            String pointer, Object expectedOwner) {
        requireOwner(expectedOwner);
        Node current = borrowHandle(exactRoot);
        for (String segment : JsonPointer.split(
                Objects.requireNonNull(pointer, "pointer"))) {
            current = expandProcessingView(current);
            if (current == null || current.isReferenceOnly()) {
                return null;
            }
            current = structuralChild(current, segment);
        }
        return expandProcessingView(current);
    }

    /** One defensive copy at the frozen Contracts public boundary. */
    public Node copyRootForPublicInvocation() {
        return exactRoot.copy();
    }

    public ExactNodeHandle processingView(String blueId) {
        return preparedProcessingViews.get(blueId);
    }

    public Map<String, ExactNodeHandle> selectedViews(
            Collection<String> selectedBlueIds) {
        Map<String, ExactNodeHandle> result =
                new LinkedHashMap<String, ExactNodeHandle>();
        for (String blueId : Objects.requireNonNull(
                selectedBlueIds, "selectedBlueIds")) {
            ExactNodeHandle handle = preparedProcessingViews.get(blueId);
            if (handle != null) result.put(blueId, handle);
        }
        return Collections.unmodifiableMap(result);
    }

    public boolean matches(
            String expectedSessionId,
            long expectedEpoch,
            String expectedRootBlueId,
            String expectedInventoryIdentity) {
        return sessionId.equals(expectedSessionId)
                && epoch == expectedEpoch
                && rootBlueId.equals(expectedRootBlueId)
                && inventoryIdentity.equals(expectedInventoryIdentity);
    }

    private void requireOwner(Object expectedOwner) {
        if (owner != Objects.requireNonNull(expectedOwner, "expectedOwner")) {
            throw new IllegalArgumentException(
                    "Prepared context belongs to another ownership domain");
        }
    }

    private Node expandProcessingView(Node supplied) {
        if (supplied == null) return null;
        String blueId = projectionReferences.verifiedIdentity(
                supplied, owner);
        if (blueId == null) return supplied;
        ExactNodeHandle view = preparedProcessingViews.get(
                blueId);
        return view == null ? supplied : borrowHandle(view);
    }

    Node borrowVerifiedHandle(
            ExactNodeHandle handle, Object expectedOwner) {
        requireOwner(expectedOwner);
        return borrowHandle(Objects.requireNonNull(handle, "handle"));
    }

    private Node borrowHandle(ExactNodeHandle handle) {
        return handle.borrowTrusted(owner);
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
        return parent.getProperties() == null
                ? null
                : parent.getProperties().get(segment);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }

    private static long retainedWeight(
            RetainedReferenceIndex retained,
            RetainedReferenceIndex projection,
            int fragmentCount,
            int preparedViewCount) {
        /* The exact Root graph is counted once. PROCESS-view handles point
         * at immutable content also owned by the fragment store, so counting
         * those bodies again would make every template/context charge the
         * same interned graph repeatedly. The maps and references owned by
         * this context remain fully represented below. */
        long weight = retained.approximateRetainedGraphWeightBytes();
        weight = RetainedNodeWeight.saturatedAdd(weight, 256L);
        weight = RetainedNodeWeight.saturatedAdd(
                weight,
                RetainedNodeWeight.saturatedMultiply(
                        104L,
                        (long) retained.size() + projection.size()));
        weight = RetainedNodeWeight.saturatedAdd(
                weight,
                RetainedNodeWeight.saturatedMultiply(
                        56L, fragmentCount));
        weight = RetainedNodeWeight.saturatedAdd(
                weight,
                RetainedNodeWeight.saturatedMultiply(
                        64L, preparedViewCount));
        return Math.max(1L, weight);
    }
}
