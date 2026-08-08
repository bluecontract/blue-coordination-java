package blue.coordination.engine.memory;

import blue.coordination.engine.CoordinationProcessingEngine
        .PreparedCheckpointState;
import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.CoordinationAtomicCommitPlan;
import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.DocumentAdmissionCommit;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentAdmissionStatus;
import blue.coordination.engine.api.DocumentEpochSnapshot;
import blue.coordination.engine.api.DocumentRemovalResult;
import blue.coordination.engine.api.DocumentRemovalStatus;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.ManagedDocumentStatus;
import blue.coordination.engine.api.RegistrationMode;
import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.coordination.engine.spi.CoordinationSessionStore;
import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Thread-safe reference implementation of the compact authoritative CAS SPI. */
public final class InMemoryCoordinationSessionStore
        implements CoordinationSessionStore {

    private final Map<DocumentSessionId, ManagedDocumentSnapshot> sessions =
            new LinkedHashMap<DocumentSessionId, ManagedDocumentSnapshot>();
    private final Map<DocumentSessionId, Map<Long, DocumentEpochSnapshot>>
            epochs = new LinkedHashMap<
                    DocumentSessionId,
                    Map<Long, DocumentEpochSnapshot>>();
    private final Map<String, CommitOutcome> committedTransitions =
            new LinkedHashMap<String, CommitOutcome>();
    private final Map<DocumentSessionId, List<String>> rootOutboxes =
            new LinkedHashMap<DocumentSessionId, List<String>>();
    private final Map<DocumentSessionId, List<String>> terminalProgress =
            new LinkedHashMap<DocumentSessionId, List<String>>();
    private final InMemoryCommittedDeliveryIndex committedDeliveries;

    public InMemoryCoordinationSessionStore() {
        this(new InMemoryCommittedDeliveryIndex());
    }

    private InMemoryCoordinationSessionStore(
            InMemoryCommittedDeliveryIndex committedDeliveries) {
        this.committedDeliveries = Objects.requireNonNull(
                committedDeliveries, "committedDeliveries");
    }

    static InMemoryCoordinationSessionStore fromCheckpoint(
            InMemoryCoordinationCheckpoint checkpoint) {
        InMemoryCoordinationCheckpoint checked = Objects.requireNonNull(
                checkpoint, "checkpoint");
        InMemoryCoordinationSessionStore result =
                new InMemoryCoordinationSessionStore(
                        checked.committedDeliveries.copy());
        result.sessions.putAll(checked.sessions);
        for (Map.Entry<DocumentSessionId, Map<Long, DocumentEpochSnapshot>>
                entry : checked.epochs.entrySet()) {
            result.epochs.put(entry.getKey(),
                    new LinkedHashMap<Long, DocumentEpochSnapshot>(
                            entry.getValue()));
        }
        result.committedTransitions.putAll(checked.committedTransitions);
        for (Map.Entry<DocumentSessionId, List<String>> entry
                : checked.rootOutboxes.entrySet()) {
            result.rootOutboxes.put(entry.getKey(),
                    new ArrayList<String>(entry.getValue()));
        }
        for (Map.Entry<DocumentSessionId, List<String>> entry
                : checked.terminalProgress.entrySet()) {
            result.terminalProgress.put(entry.getKey(),
                    new ArrayList<String>(entry.getValue()));
        }
        return result;
    }

    synchronized InMemoryCoordinationCheckpoint checkpoint(
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
            InMemoryStoredCoordinationEventStore storedEvents,
            InMemoryCoordinationDispatchLedger dispatchLedger,
            long sessionSequence) {
        return new InMemoryCoordinationCheckpoint(
                profileIdentity,
                immutableContentSharingToken,
                canonicalFragmentStorageGenerationAuthority,
                preparedRepresentationStorageGenerationAuthority,
                fragments,
                fragmentHandles,
                fragmentEncodedSizes,
                fragmentWireFingerprints,
                processingViews,
                processingViewsByInventory,
                processingViewHandlesByInventory,
                processingViewEncodedSizesByInventory,
                processingViewWireFingerprintsByInventory,
                inventories,
                currentRootViews,
                preparedRootState,
                sessions,
                epochs,
                committedTransitions,
                rootOutboxes,
                terminalProgress,
                committedDeliveries,
                storedEvents,
                dispatchLedger,
                sessionSequence);
    }

    /** Current authoritative sessions for deterministic index rebuilding. */
    public synchronized List<ManagedDocumentSnapshot> sessions() {
        return Collections.unmodifiableList(
                new ArrayList<ManagedDocumentSnapshot>(sessions.values()));
    }

    @Override
    public synchronized Optional<ManagedDocumentSnapshot> findSession(
            DocumentSessionId id) {
        return Optional.ofNullable(sessions.get(
                Objects.requireNonNull(id, "id")));
    }

    @Override
    public synchronized Optional<DocumentEpochSnapshot> findEpoch(
            DocumentSessionId id,
            long epoch) {
        if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative");
        }
        Map<Long, DocumentEpochSnapshot> byEpoch = epochs.get(
                Objects.requireNonNull(id, "id"));
        return Optional.ofNullable(
                byEpoch == null ? null : byEpoch.get(epoch));
    }

    @Override
    public synchronized DocumentAdmissionResult admit(
            DocumentAdmissionCommit commit) {
        DocumentAdmissionCommit checked = Objects.requireNonNull(
                commit, "commit");
        DocumentSessionId id = checked.session().sessionId();
        ManagedDocumentSnapshot current = sessions.get(id);
        if (current == null) {
            if (checked.registration().mode()
                    == RegistrationMode.ATTACH_EXISTING) {
                return new DocumentAdmissionResult(
                        DocumentAdmissionStatus.CONFLICT,
                        null,
                        "The requested session does not exist");
            }
            sessions.put(id, checked.session());
            Map<Long, DocumentEpochSnapshot> history =
                    new LinkedHashMap<Long, DocumentEpochSnapshot>();
            history.put(0L, checked.epochZero());
            epochs.put(id, history);
            rootOutboxes.put(id, new ArrayList<String>());
            terminalProgress.put(id, new ArrayList<String>());
            return new DocumentAdmissionResult(
                    DocumentAdmissionStatus.CREATED,
                    checked.session(),
                    null);
        }

        if (checked.registration().mode() == RegistrationMode.CREATE_ONLY) {
            return new DocumentAdmissionResult(
                    DocumentAdmissionStatus.CONFLICT,
                    null,
                    "The requested session already exists");
        }
        String suppliedRoot = checked.session().currentRootBlueId();
        if (current.currentRootBlueId().equals(suppliedRoot)) {
            return new DocumentAdmissionResult(
                    DocumentAdmissionStatus.ATTACHED_CURRENT,
                    current,
                    null);
        }
        DocumentEpochSnapshot historical = findHistoricalRoot(id, suppliedRoot);
        if (historical != null) {
            return new DocumentAdmissionResult(
                    DocumentAdmissionStatus.ATTACHED_TO_CURRENT,
                    current,
                    "Supplied exact state is historical epoch "
                            + historical.epoch());
        }
        Long claimed = checked.registration().claimedEpoch();
        if (claimed == null) {
            return new DocumentAdmissionResult(
                    DocumentAdmissionStatus.VERIFIED_LINEAGE_REQUIRED,
                    null,
                    "Unknown exact state requires verified lineage");
        }
        if (claimed.longValue() > current.currentEpoch()) {
            return new DocumentAdmissionResult(
                    DocumentAdmissionStatus.FORK_REQUIRED,
                    null,
                    "Unknown newer exact state cannot fast-forward a session");
        }
        return new DocumentAdmissionResult(
                DocumentAdmissionStatus.CONFLICT,
                null,
                "Unknown claimed historical state");
    }

    @Override
    public synchronized CommitOutcome commit(
            CoordinationAtomicCommitPlan plan) {
        CoordinationAtomicCommitPlan checked = Objects.requireNonNull(
                plan, "plan");
        String committedKey = committedKey(
                checked.sessionId(), checked.transitionIdentity());
        CommitOutcome prior = committedTransitions.get(committedKey);
        if (prior != null) {
            return new CommitOutcome(
                    CommitStatus.ALREADY_COMMITTED,
                    prior.session().orElse(null),
                    checked.transitionIdentity());
        }
        Optional<CoordinationCommittedDelivery> priorDelivery =
                committedDeliveries.find(
                        checked.eventBlueId(), checked.sessionId());
        if (priorDelivery.isPresent()) {
            requireSamePlannedDelivery(checked, priorDelivery.get());
            return new CommitOutcome(
                    CommitStatus.ALREADY_COMMITTED,
                    sessions.get(checked.sessionId()),
                    priorDelivery.get().transitionIdentity());
        }
        ManagedDocumentSnapshot current = sessions.get(checked.sessionId());
        if (current == null
                || current.status() != ManagedDocumentStatus.ACTIVE
                || current.currentEpoch() != checked.expectedEpoch()
                || !current.currentRootBlueId().equals(
                        checked.expectedRootBlueId())
                || !current.initialDocumentBlueId().equals(
                        checked.expectedInitialDocumentBlueId())
                || !current.environmentIdentity().equals(
                        checked.expectedEnvironmentIdentity())
                || !current.committedFrontier().equals(
                        checked.expectedCommittedFrontier())
                || !current.fragmentInventoryIdentity().equals(
                        checked.expectedFragmentInventoryIdentity())
                || !current.subscriptions().digest().equals(
                        checked.expectedSubscriptionSnapshotIdentity())) {
            return new CommitOutcome(
                    CommitStatus.CONFLICT,
                    current,
                    checked.transitionIdentity());
        }

        committedDeliveries.requireRecordable(checked);
        sessions.put(checked.sessionId(), checked.resultingSession());
        if (checked.resultingEpochSnapshot() != null) {
            epochs.get(checked.sessionId()).put(
                    checked.resultingEpoch(),
                    checked.resultingEpochSnapshot());
        }
        rootOutboxes.get(checked.sessionId()).addAll(
                checked.rootOutboxEventBlueIds());
        terminalProgress.get(checked.sessionId()).add(
                checked.eventBlueId());
        CommitOutcome outcome = new CommitOutcome(
                CommitStatus.COMMITTED,
                checked.resultingSession(),
                checked.transitionIdentity());
        committedTransitions.put(committedKey, outcome);
        committedDeliveries.record(checked);
        return outcome;
    }

    @Override
    public synchronized DocumentRemovalResult remove(
            DocumentSessionId id,
            long expectedEpoch) {
        if (expectedEpoch < 0L) {
            throw new IllegalArgumentException(
                    "expectedEpoch must be non-negative");
        }
        DocumentSessionId checkedId = Objects.requireNonNull(id, "id");
        ManagedDocumentSnapshot current = sessions.get(checkedId);
        if (current == null) {
            return new DocumentRemovalResult(
                    DocumentRemovalStatus.NOT_FOUND, null);
        }
        if (current.status() == ManagedDocumentStatus.REMOVED) {
            return new DocumentRemovalResult(
                    DocumentRemovalStatus.ALREADY_REMOVED, current);
        }
        if (current.currentEpoch() != expectedEpoch) {
            return new DocumentRemovalResult(
                    DocumentRemovalStatus.CONFLICT, current);
        }
        ManagedDocumentSnapshot removed = current.withStatus(
                ManagedDocumentStatus.REMOVED);
        sessions.put(checkedId, removed);
        return new DocumentRemovalResult(
                DocumentRemovalStatus.REMOVED, removed);
    }

    public synchronized List<String> rootOutbox(DocumentSessionId id) {
        return immutableCopy(rootOutboxes.get(id));
    }

    public synchronized List<String> terminalProgress(DocumentSessionId id) {
        return immutableCopy(terminalProgress.get(id));
    }

    public synchronized int sessionCount() { return sessions.size(); }

    /** Authoritative event/session evidence committed with session state. */
    public InMemoryCommittedDeliveryIndex committedDeliveries() {
        return committedDeliveries;
    }

    private DocumentEpochSnapshot findHistoricalRoot(
            DocumentSessionId id,
            String rootBlueId) {
        Map<Long, DocumentEpochSnapshot> history = epochs.get(id);
        if (history == null) return null;
        for (DocumentEpochSnapshot snapshot : history.values()) {
            if (snapshot.rootBlueId().equals(rootBlueId)) return snapshot;
        }
        return null;
    }

    private static String committedKey(
            DocumentSessionId sessionId,
            String transitionIdentity) {
        return sessionId.value() + "\u0000" + transitionIdentity;
    }

    private static void requireSamePlannedDelivery(
            CoordinationAtomicCommitPlan plan,
            CoordinationCommittedDelivery committed) {
        if (plan.expectedEpoch() != committed.plannedEpoch()
                || !plan.expectedRootBlueId().equals(
                        committed.plannedRootBlueId())) {
            throw new IllegalStateException(
                    "Event/session delivery was already committed from "
                            + "another planned Root revision");
        }
    }

    private static List<String> immutableCopy(List<String> source) {
        return source == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(
                        new ArrayList<String>(source));
    }
}
