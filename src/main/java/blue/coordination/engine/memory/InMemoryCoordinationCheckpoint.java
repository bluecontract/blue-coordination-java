package blue.coordination.engine.memory;

import blue.coordination.engine.CoordinationProcessingEngine
        .PreparedCheckpointState;
import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.DocumentEpochSnapshot;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable in-process checkpoint of the reference in-memory host stores.
 *
 * <p>This is deliberately not a serialization format. Content-addressed
 * fragment bodies and immutable inventories are retained by reference, while
 * every mutable store container is copied when the checkpoint is captured and
 * copied again for each fork. The fragment store never exposes retained nodes
 * and verifies and clones every outward read, which makes this sharing a safe
 * copy-on-write optimization.</p>
 */
public final class InMemoryCoordinationCheckpoint {

    final String profileIdentity;
    final Object immutableContentSharingToken;
    final String canonicalFragmentStorageGenerationAuthority;
    final String preparedRepresentationStorageGenerationAuthority;
    final Map<String, Node> fragments;
    final Map<String, ExactNodeHandle> fragmentHandles;
    final Map<String, Long> fragmentEncodedSizes;
    final Map<String, String> fragmentWireFingerprints;
    final Map<String, Node> processingViews;
    final Map<String, Map<String, Node>> processingViewsByInventory;
    final Map<String, Map<String, ExactNodeHandle>>
            processingViewHandlesByInventory;
    final Map<String, Map<String, Long>>
            processingViewEncodedSizesByInventory;
    final Map<String, Map<String, String>>
            processingViewWireFingerprintsByInventory;
    final Map<String, CoordinationFragmentInventory> inventories;
    final Map<String, Node> currentRootViews;
    final PreparedCheckpointState preparedRootState;
    final Map<DocumentSessionId, ManagedDocumentSnapshot> sessions;
    final Map<DocumentSessionId, Map<Long, DocumentEpochSnapshot>> epochs;
    final Map<String, CommitOutcome> committedTransitions;
    final Map<DocumentSessionId, List<String>> rootOutboxes;
    final Map<DocumentSessionId, List<String>> terminalProgress;
    final InMemoryCommittedDeliveryIndex committedDeliveries;
    final InMemoryStoredCoordinationEventStore storedEvents;
    final InMemoryCoordinationDispatchLedger dispatchLedger;
    final long sessionSequence;
    private final String stateFingerprint;

    InMemoryCoordinationCheckpoint(
            String profileIdentity,
            Object immutableContentSharingToken,
            String canonicalFragmentStorageGenerationAuthority,
            String preparedRepresentationStorageGenerationAuthority,
            Map<String, Node> fragments,
            Map<String, ExactNodeHandle> fragmentHandles,
            Map<String, Long> fragmentEncodedSizes,
            Map<String, String> fragmentWireFingerprints,
            Map<String, Node> processingViews,
            Map<String, Map<String, Node>> processingViewsByInventory,
            Map<String, Map<String, ExactNodeHandle>>
                    processingViewHandlesByInventory,
            Map<String, Map<String, Long>>
                    processingViewEncodedSizesByInventory,
            Map<String, Map<String, String>>
                    processingViewWireFingerprintsByInventory,
            Map<String, CoordinationFragmentInventory> inventories,
            Map<String, Node> currentRootViews,
            PreparedCheckpointState preparedRootState,
            Map<DocumentSessionId, ManagedDocumentSnapshot> sessions,
            Map<DocumentSessionId, Map<Long, DocumentEpochSnapshot>> epochs,
            Map<String, CommitOutcome> committedTransitions,
            Map<DocumentSessionId, List<String>> rootOutboxes,
            Map<DocumentSessionId, List<String>> terminalProgress,
            InMemoryCommittedDeliveryIndex committedDeliveries,
            InMemoryStoredCoordinationEventStore storedEvents,
            InMemoryCoordinationDispatchLedger dispatchLedger,
            long sessionSequence) {
        this.profileIdentity = requireText(profileIdentity, "profileIdentity");
        this.immutableContentSharingToken = Objects.requireNonNull(
                immutableContentSharingToken,
                "immutableContentSharingToken");
        this.canonicalFragmentStorageGenerationAuthority = requireText(
                canonicalFragmentStorageGenerationAuthority,
                "canonicalFragmentStorageGenerationAuthority");
        this.preparedRepresentationStorageGenerationAuthority = requireText(
                preparedRepresentationStorageGenerationAuthority,
                "preparedRepresentationStorageGenerationAuthority");
        this.fragments = immutableNodeMap(fragments);
        this.fragmentHandles = immutableHandleMap(fragmentHandles);
        this.fragmentEncodedSizes = immutableLongMap(
                fragmentEncodedSizes, "fragmentEncodedSizes");
        this.fragmentWireFingerprints = immutableStringMap(
                fragmentWireFingerprints, "fragmentWireFingerprints");
        this.processingViews = immutableNodeMap(processingViews);
        this.processingViewsByInventory = immutableNestedNodeMap(
                processingViewsByInventory);
        this.processingViewHandlesByInventory = immutableNestedHandleMap(
                processingViewHandlesByInventory);
        this.processingViewEncodedSizesByInventory = immutableNestedLongMap(
                processingViewEncodedSizesByInventory,
                "processingViewEncodedSizesByInventory");
        this.processingViewWireFingerprintsByInventory =
                immutableNestedStringMap(
                        processingViewWireFingerprintsByInventory,
                        "processingViewWireFingerprintsByInventory");
        this.inventories = immutableMap(inventories, "inventories");
        this.currentRootViews = immutableClonedNodeMap(
                currentRootViews, "currentRootViews");
        this.preparedRootState = preparedRootState;
        this.sessions = immutableMap(sessions, "sessions");
        this.epochs = immutableNestedMap(epochs, "epochs");
        this.committedTransitions = immutableMap(
                committedTransitions, "committedTransitions");
        this.rootOutboxes = immutableListMap(rootOutboxes, "rootOutboxes");
        this.terminalProgress = immutableListMap(
                terminalProgress, "terminalProgress");
        this.committedDeliveries = Objects.requireNonNull(
                committedDeliveries, "committedDeliveries").copy();
        this.storedEvents = Objects.requireNonNull(
                storedEvents, "storedEvents").copy();
        this.dispatchLedger = Objects.requireNonNull(
                dispatchLedger, "dispatchLedger").copyAtQuiescence();
        if (sessionSequence < 0L) {
            throw new IllegalArgumentException(
                    "sessionSequence must be non-negative");
        }
        this.sessionSequence = sessionSequence;
        requireClosedContentGraph();
        this.stateFingerprint = calculateStateFingerprint();
    }

    /** Number of immutable physical bodies shared by every fork. */
    public int physicalFragmentCount() { return fragments.size(); }

    /** Number of current authoritative sessions captured. */
    public int sessionCount() { return sessions.size(); }

    /** Number of immutable inventories shared by every fork. */
    public int inventoryCount() { return inventories.size(); }

    /** Number of canonical event handles restored without re-splitting. */
    public int storedEventCount() { return storedEvents.size(); }

    /** Stable digest covering authoritative session and delivery state. */
    public String stateFingerprint() { return stateFingerprint; }

    /** Whether two checkpoints retain the same immutable CAS backing. */
    public boolean sharesImmutableContentWith(
            InMemoryCoordinationCheckpoint other) {
        return other != null
                && immutableContentSharingToken
                == other.immutableContentSharingToken;
    }

    /** Proves that copy-on-write checkpoints do not alias mutable containers. */
    public boolean sharesMutableStateWith(
            InMemoryCoordinationCheckpoint other) {
        return other != null
                && (sessions == other.sessions
                || epochs == other.epochs
                || committedTransitions == other.committedTransitions
                || rootOutboxes == other.rootOutboxes
                || terminalProgress == other.terminalProgress
                || committedDeliveries == other.committedDeliveries
                || storedEvents == other.storedEvents
                || dispatchLedger == other.dispatchLedger);
    }

    /**
     * Returns the complete exact PROCESS representation for one retained
     * inventory. Scoped PROCESS overrides are sparse by design; every absent
     * override resolves to its immutable physical fragment without a store
     * operation.
     */
    Map<String, Node> completeProcessingViewsForRestore(
            String inventoryIdentity) {
        String identity = requireText(
                inventoryIdentity, "inventoryIdentity");
        CoordinationFragmentInventory inventory = inventories.get(identity);
        if (inventory == null) {
            throw new IllegalArgumentException(
                    "Checkpoint inventory is absent: " + identity);
        }
        Map<String, Node> scoped = processingViewsByInventory.get(identity);
        Map<String, Node> complete = new LinkedHashMap<String, Node>();
        for (String blueId : inventory.fragmentBlueIds()) {
            Node exact = scoped == null ? null : scoped.get(blueId);
            if (exact == null) exact = fragments.get(blueId);
            if (exact == null) {
                throw new IllegalStateException(
                        "Checkpoint PROCESS view is absent: " + blueId);
            }
            complete.put(blueId, exact);
        }
        return Collections.unmodifiableMap(complete);
    }

    private String calculateStateFingerprint() {
        StringBuilder canonical = new StringBuilder();
        canonical.append(profileIdentity).append('\n')
                .append(sessionSequence).append('\n');
        List<String> fragmentIds = new ArrayList<String>(fragments.keySet());
        Collections.sort(fragmentIds);
        canonical.append(fragmentIds).append('\n');
        List<String> inventoryIds = new ArrayList<String>(inventories.keySet());
        Collections.sort(inventoryIds);
        canonical.append(inventoryIds).append('\n');

        List<ManagedDocumentSnapshot> orderedSessions =
                new ArrayList<ManagedDocumentSnapshot>(sessions.values());
        orderedSessions.sort(Comparator.comparing(
                value -> value.sessionId().value()));
        for (ManagedDocumentSnapshot session : orderedSessions) {
            canonical.append(session.sessionId().value()).append('\u0000')
                    .append(session.initialDocumentBlueId()).append('\u0000')
                    .append(session.currentRootBlueId()).append('\u0000')
                    .append(session.currentEpoch()).append('\u0000')
                    .append(session.committedFrontier()).append('\u0000')
                    .append(session.fragmentInventoryIdentity())
                    .append('\u0000')
                    .append(session.subscriptions().digest()).append('\u0000')
                    .append(session.status()).append('\n');
            Map<Long, DocumentEpochSnapshot> history = epochs.get(
                    session.sessionId());
            List<Long> epochNumbers = new ArrayList<Long>(history.keySet());
            Collections.sort(epochNumbers);
            for (Long epoch : epochNumbers) {
                DocumentEpochSnapshot value = history.get(epoch);
                canonical.append("epoch:").append(value.epoch())
                        .append(',').append(value.rootBlueId())
                        .append(',').append(value.priorRootBlueId())
                        .append(',').append(value.causedByEventBlueId())
                        .append(',').append(value.transitionIdentity())
                        .append('\n');
            }
            canonical.append("outbox:")
                    .append(rootOutboxes.get(session.sessionId()))
                    .append('\n')
                    .append("progress:")
                    .append(terminalProgress.get(session.sessionId()))
                    .append('\n');
        }
        canonical.append("committed:")
                .append(committedDeliveries.stateFingerprint()).append('\n')
                .append("events:")
                .append(storedEvents.stateFingerprint()).append('\n')
                .append("dispatch:")
                .append(dispatchLedger.stateFingerprint()).append('\n');
        return InMemoryCheckpointFingerprint.sha256(canonical.toString());
    }

    private void requireClosedContentGraph() {
        if (!fragments.keySet().equals(fragmentHandles.keySet())
                || !fragments.keySet().equals(
                        fragmentEncodedSizes.keySet())) {
            throw new IllegalArgumentException(
                    "prepared physical representations must exactly cover "
                            + "fragments");
        }
        for (Map.Entry<String, ExactNodeHandle> entry
                : fragmentHandles.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().blueId())
                    || !entry.getValue().belongsTo(
                            immutableContentSharingToken)) {
                throw new IllegalArgumentException(
                        "prepared physical handle has invalid ownership: "
                                + entry.getKey());
            }
        }
        requireNonNegativeSizes(
                fragmentEncodedSizes, "fragmentEncodedSizes");
        if (!fragments.keySet().containsAll(
                fragmentWireFingerprints.keySet())) {
            throw new IllegalArgumentException(
                    "physical fingerprints name absent fragments");
        }
        if (!fragments.keySet().containsAll(processingViews.keySet())) {
            throw new IllegalArgumentException(
                    "global PROCESS views must name physical fragments");
        }
        for (Map.Entry<String, Map<String, Node>> entry
                : processingViewsByInventory.entrySet()) {
            CoordinationFragmentInventory inventory = inventories.get(
                    entry.getKey());
            if (inventory == null) {
                throw new IllegalArgumentException(
                        "PROCESS views name absent inventory "
                                + entry.getKey());
            }
            if (!fragments.keySet().containsAll(entry.getValue().keySet())) {
                throw new IllegalArgumentException(
                        "inventory PROCESS views must name physical fragments");
            }
            if (!inventory.fragmentBlueIds().containsAll(
                    entry.getValue().keySet())) {
                throw new IllegalArgumentException(
                        "inventory PROCESS views must belong to inventory "
                                + entry.getKey());
            }
            Map<String, ExactNodeHandle> handles =
                    processingViewHandlesByInventory.get(entry.getKey());
            Map<String, Long> sizes =
                    processingViewEncodedSizesByInventory.get(
                            entry.getKey());
            if (handles == null || sizes == null
                    || !handles.keySet().equals(sizes.keySet())) {
                throw new IllegalArgumentException(
                        "prepared PROCESS representations are incomplete for "
                                + entry.getKey());
            }
            java.util.Set<String> expanded =
                    new java.util.LinkedHashSet<String>();
            for (Map.Entry<String, Node> view : entry.getValue().entrySet()) {
                if (!view.getValue().isReferenceOnly()) {
                    expanded.add(view.getKey());
                }
            }
            if (!expanded.equals(handles.keySet())) {
                throw new IllegalArgumentException(
                        "prepared PROCESS handles do not match expanded views "
                                + entry.getKey());
            }
            for (Map.Entry<String, ExactNodeHandle> handle
                    : handles.entrySet()) {
                if (!handle.getKey().equals(handle.getValue().blueId())
                        || !handle.getValue().belongsTo(
                                immutableContentSharingToken)) {
                    throw new IllegalArgumentException(
                            "prepared PROCESS handle has invalid ownership: "
                                    + handle.getKey());
                }
            }
            requireNonNegativeSizes(
                    sizes, "processing view encoded sizes");
        }
        if (!processingViewsByInventory.keySet().equals(
                processingViewHandlesByInventory.keySet())
                || !processingViewsByInventory.keySet().equals(
                        processingViewEncodedSizesByInventory.keySet())
                || !processingViewsByInventory.keySet().containsAll(
                        processingViewWireFingerprintsByInventory.keySet())) {
            throw new IllegalArgumentException(
                    "prepared PROCESS representation inventories disagree");
        }
        for (Map.Entry<String, CoordinationFragmentInventory> entry
                : inventories.entrySet()) {
            CoordinationFragmentInventory inventory = entry.getValue();
            if (!entry.getKey().equals(inventory.inventoryIdentity())) {
                throw new IllegalArgumentException(
                        "inventory map key does not match identity");
            }
            if (!fragments.keySet().containsAll(
                    inventory.fragmentBlueIds())) {
                throw new IllegalArgumentException(
                        "inventory is not closed over physical fragments");
            }
        }
        for (ManagedDocumentSnapshot session : sessions.values()) {
            if (!inventories.containsKey(
                    session.fragmentInventoryIdentity())) {
                throw new IllegalArgumentException(
                        "session names absent fragment inventory: "
                                + session.sessionId());
            }
            if (!epochs.containsKey(session.sessionId())
                    || !rootOutboxes.containsKey(session.sessionId())
                    || !terminalProgress.containsKey(session.sessionId())) {
                throw new IllegalArgumentException(
                        "session checkpoint metadata is incomplete: "
                                + session.sessionId());
            }
        }
        java.util.Set<String> expectedCurrentInventories =
                new java.util.LinkedHashSet<String>();
        for (ManagedDocumentSnapshot session : sessions.values()) {
            expectedCurrentInventories.add(
                    session.fragmentInventoryIdentity());
        }
        if (!expectedCurrentInventories.containsAll(
                currentRootViews.keySet())) {
            throw new IllegalArgumentException(
                    "current Root views must be a bounded subset of current "
                            + "session inventories");
        }
        for (Map.Entry<String, Node> entry : currentRootViews.entrySet()) {
            CoordinationFragmentInventory inventory = inventories.get(
                    entry.getKey());
            String actual = blue.language.identity.DirectBlueIdCalculator
                    .calculateBlueId(entry.getValue().clone());
            if (inventory == null
                    || entry.getValue().isReferenceOnly()
                    || !inventory.rootBlueId().equals(actual)) {
                throw new IllegalArgumentException(
                        "current Root view disagrees with inventory "
                                + entry.getKey());
            }
        }
    }

    private static Map<String, Node> immutableNodeMap(
            Map<String, Node> source) {
        return Collections.unmodifiableMap(
                new LinkedHashMap<String, Node>(
                        Objects.requireNonNull(source, "node map")));
    }

    private static Map<String, Node> immutableClonedNodeMap(
            Map<String, Node> source,
            String label) {
        Map<String, Node> result = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : Objects.requireNonNull(
                source, label).entrySet()) {
            result.put(
                    Objects.requireNonNull(entry.getKey(), label + " key"),
                    Objects.requireNonNull(
                            entry.getValue(), label + " value").clone());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Map<String, Node>> immutableNestedNodeMap(
            Map<String, Map<String, Node>> source) {
        Map<String, Map<String, Node>> copy =
                new LinkedHashMap<String, Map<String, Node>>();
        for (Map.Entry<String, Map<String, Node>> entry
                : Objects.requireNonNull(source, "nested node map")
                .entrySet()) {
            copy.put(entry.getKey(), immutableNodeMap(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, ExactNodeHandle> immutableHandleMap(
            Map<String, ExactNodeHandle> source) {
        return immutableMap(source, "handle map");
    }

    private static Map<String, Long> immutableLongMap(
            Map<String, Long> source,
            String label) {
        return immutableMap(source, label);
    }

    private static Map<String, String> immutableStringMap(
            Map<String, String> source,
            String label) {
        return immutableMap(source, label);
    }

    private static Map<String, Map<String, ExactNodeHandle>>
            immutableNestedHandleMap(
                    Map<String, Map<String, ExactNodeHandle>> source) {
        return immutableNestedStringKeyMap(source, "nested handle map");
    }

    private static Map<String, Map<String, Long>> immutableNestedLongMap(
            Map<String, Map<String, Long>> source,
            String label) {
        return immutableNestedStringKeyMap(source, label);
    }

    private static Map<String, Map<String, String>> immutableNestedStringMap(
            Map<String, Map<String, String>> source,
            String label) {
        return immutableNestedStringKeyMap(source, label);
    }

    private static <V> Map<String, Map<String, V>>
            immutableNestedStringKeyMap(
                    Map<String, Map<String, V>> source,
                    String label) {
        Map<String, Map<String, V>> copy =
                new LinkedHashMap<String, Map<String, V>>();
        for (Map.Entry<String, Map<String, V>> entry
                : Objects.requireNonNull(source, label).entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), label + " key"),
                    Collections.unmodifiableMap(
                            new LinkedHashMap<String, V>(Objects.requireNonNull(
                                    entry.getValue(), label + " value"))));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void requireNonNegativeSizes(
            Map<String, Long> sizes,
            String label) {
        for (Map.Entry<String, Long> entry : sizes.entrySet()) {
            Long size = Objects.requireNonNull(
                    entry.getValue(), label + " value");
            if (size.longValue() < 0L) {
                throw new IllegalArgumentException(
                        label + " must be non-negative");
            }
        }
    }

    private static <K, V> Map<K, V> immutableMap(
            Map<K, V> source,
            String label) {
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(
                Objects.requireNonNull(source, label)));
    }

    private static <K, V> Map<K, Map<Long, V>> immutableNestedMap(
            Map<K, Map<Long, V>> source,
            String label) {
        Map<K, Map<Long, V>> copy = new LinkedHashMap<K, Map<Long, V>>();
        for (Map.Entry<K, Map<Long, V>> entry
                : Objects.requireNonNull(source, label).entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(
                    new LinkedHashMap<Long, V>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static <K> Map<K, List<String>> immutableListMap(
            Map<K, List<String>> source,
            String label) {
        Map<K, List<String>> copy = new LinkedHashMap<K, List<String>>();
        for (Map.Entry<K, List<String>> entry
                : Objects.requireNonNull(source, label).entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(
                    new ArrayList<String>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
