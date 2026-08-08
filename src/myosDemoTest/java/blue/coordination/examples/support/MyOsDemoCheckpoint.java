package blue.coordination.examples.support;

import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.memory.DemoTransition;
import blue.coordination.engine.memory.InMemoryCoordinationCheckpoint;
import blue.coordination.engine.memory.InMemoryCoordinationDispatchLedger;
import blue.language.model.Node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable in-process checkpoint of one quiescent demo runtime.
 *
 * <p>Engine fragment bodies remain shared and content-addressed. Every mutable
 * host container is copied into this value and copied again for each fork:
 * sessions, ledgers, journal, Timeline heads, topology, inverse indexes and
 * initialization state. Restoring never parses or initializes a document and
 * never replays a historical Timeline Entry.</p>
 */
public final class MyOsDemoCheckpoint {

    final InMemoryCoordinationCheckpoint environment;
    final InMemoryCoordinationDispatchLedger fanoutLedger;
    final Map<String, MyOsDemoDocument> documents;
    final Map<String, String> initialBlueIds;
    final Map<String, String> canonicalIdentityInputBlueIds;
    final Map<String, Node> ownedInitializationEvidence;
    final Map<String, MyOsDemoEntry> authoredEntries;
    final List<MyOsTimelineCheckpoint> timelines;
    final MyOsPositionedTimelineJournal journal;
    final MyOsEventInventoryRegistry eventInventories;
    final MyOsTopologyCatalog topology;
    final MyOsInitializationCoordinator<MyOsDemoDocument> initialization;
    final MyOsTimelineDocumentIndex timelineIndex;
    final MyOsDeliveryLedger topologyDeliveryLedger;
    final Map<MyOsDocumentIdentity, List<MyOsManagedEmbedding>>
            managedEmbeddings;
    final Map<String, Map<DocumentSessionId, DemoTransition>>
            transitionsByEvent;
    final long admissionSequence;
    final long timelineEntrySequence;
    final long timelineTimestampOffsetMicros;
    private final String stateFingerprint;

    MyOsDemoCheckpoint(
            InMemoryCoordinationCheckpoint environment,
            InMemoryCoordinationDispatchLedger fanoutLedger,
            Map<String, MyOsDemoDocument> documents,
            Map<String, String> initialBlueIds,
            Map<String, String> canonicalIdentityInputBlueIds,
            Map<String, Node> ownedInitializationEvidence,
            Map<String, MyOsDemoEntry> authoredEntries,
            List<MyOsTimelineCheckpoint> timelines,
            MyOsPositionedTimelineJournal journal,
            MyOsEventInventoryRegistry eventInventories,
            MyOsTopologyCatalog topology,
            MyOsInitializationCoordinator<MyOsDemoDocument> initialization,
            MyOsTimelineDocumentIndex timelineIndex,
            MyOsDeliveryLedger topologyDeliveryLedger,
            Map<MyOsDocumentIdentity, List<MyOsManagedEmbedding>>
                    managedEmbeddings,
            Map<String, Map<DocumentSessionId, DemoTransition>>
                    transitionsByEvent,
            long admissionSequence,
            long timelineEntrySequence,
            long timelineTimestampOffsetMicros,
            String stateFingerprint) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.fanoutLedger = Objects.requireNonNull(
                fanoutLedger, "fanoutLedger").copyAtQuiescence();
        this.documents = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(documents, "documents")));
        this.initialBlueIds = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(initialBlueIds, "initialBlueIds")));
        this.canonicalIdentityInputBlueIds = Collections.unmodifiableMap(
                new LinkedHashMap<>(
                Objects.requireNonNull(
                        canonicalIdentityInputBlueIds,
                        "canonicalIdentityInputBlueIds")));
        if (!this.documents.keySet().equals(
                this.canonicalIdentityInputBlueIds.keySet())) {
            throw new IllegalArgumentException(
                    "canonical identity inputs must cover every document");
        }
        this.ownedInitializationEvidence = immutableNodeMap(
                Objects.requireNonNull(
                        ownedInitializationEvidence,
                        "ownedInitializationEvidence"));
        this.authoredEntries = Collections.unmodifiableMap(
                new LinkedHashMap<>(
                Objects.requireNonNull(authoredEntries, "authoredEntries")));
        this.timelines = List.copyOf(
                Objects.requireNonNull(timelines, "timelines"));
        this.journal = Objects.requireNonNull(journal, "journal").copy();
        this.eventInventories = Objects.requireNonNull(
                eventInventories, "eventInventories").copy();
        this.topology = Objects.requireNonNull(topology, "topology").copy();
        this.initialization = Objects.requireNonNull(
                initialization, "initialization").copyAtQuiescence();
        this.timelineIndex = Objects.requireNonNull(
                timelineIndex, "timelineIndex").copy();
        this.topologyDeliveryLedger = Objects.requireNonNull(
                topologyDeliveryLedger,
                "topologyDeliveryLedger").copy();
        this.managedEmbeddings = immutableListMap(
                Objects.requireNonNull(
                        managedEmbeddings, "managedEmbeddings"));
        this.transitionsByEvent = immutableTransitionMap(
                Objects.requireNonNull(
                        transitionsByEvent, "transitionsByEvent"));
        if (admissionSequence < 0L || timelineEntrySequence < 0L
                || timelineTimestampOffsetMicros < 0L) {
            throw new IllegalArgumentException(
                    "checkpoint sequences and timestamp offset must be "
                            + "non-negative");
        }
        this.admissionSequence = admissionSequence;
        this.timelineEntrySequence = timelineEntrySequence;
        this.timelineTimestampOffsetMicros =
                timelineTimestampOffsetMicros;
        this.stateFingerprint = requireText(
                stateFingerprint, "stateFingerprint");
        if (this.journal.size() != this.authoredEntries.size()
                || this.environment.storedEventCount()
                != this.journal.size()) {
            throw new IllegalArgumentException(
                    "journal, authored-entry and event-store counts disagree");
        }
    }

    public int documentCount() { return documents.size(); }
    public int timelineCount() { return timelines.size(); }
    public int journalEntryCount() { return journal.size(); }
    public int physicalFragmentCount() {
        return environment.physicalFragmentCount();
    }
    public String stateFingerprint() { return stateFingerprint; }

    /** Proves that a fork checkpoint retains the same immutable CAS bodies. */
    public boolean sharesImmutableContentWith(MyOsDemoCheckpoint other) {
        return other != null
                && environment.sharesImmutableContentWith(other.environment);
    }

    /** Whether any authoritative mutable checkpoint container is aliased. */
    public boolean sharesMutableStateWith(MyOsDemoCheckpoint other) {
        return other != null
                && (environment.sharesMutableStateWith(other.environment)
                || fanoutLedger == other.fanoutLedger
                || documents == other.documents
                || ownedInitializationEvidence
                        == other.ownedInitializationEvidence
                || authoredEntries == other.authoredEntries
                || timelines == other.timelines
                || journal == other.journal
                || eventInventories == other.eventInventories
                || topology == other.topology
                || initialization == other.initialization
                || timelineIndex == other.timelineIndex
                || topologyDeliveryLedger == other.topologyDeliveryLedger
                || managedEmbeddings == other.managedEmbeddings
                || transitionsByEvent == other.transitionsByEvent);
    }

    private static Map<MyOsDocumentIdentity, List<MyOsManagedEmbedding>>
            immutableListMap(
                    Map<MyOsDocumentIdentity, List<MyOsManagedEmbedding>>
                            source) {
        Map<MyOsDocumentIdentity, List<MyOsManagedEmbedding>> copy =
                new LinkedHashMap<>();
        source.forEach((identity, embeddings) -> copy.put(
                Objects.requireNonNull(identity, "document identity"),
                List.copyOf(Objects.requireNonNull(
                        embeddings, "managed embeddings"))));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Node> immutableNodeMap(
            Map<String, Node> source) {
        Map<String, Node> copy = new LinkedHashMap<>();
        source.forEach((blueId, exactNode) -> copy.put(
                requireText(blueId, "initializationBlueId"),
                Objects.requireNonNull(
                        exactNode, "initialization exact node").clone()));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Map<DocumentSessionId, DemoTransition>>
            immutableTransitionMap(
                    Map<String, Map<DocumentSessionId, DemoTransition>>
                            source) {
        Map<String, Map<DocumentSessionId, DemoTransition>> copy =
                new LinkedHashMap<>();
        source.forEach((event, transitions) -> copy.put(
                requireText(event, "eventBlueId"),
                Collections.unmodifiableMap(new LinkedHashMap<>(
                        Objects.requireNonNull(
                                transitions, "transitions")))));
        return Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " is blank");
        }
        return checked;
    }
}
