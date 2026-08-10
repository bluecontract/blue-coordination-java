package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.SessionStatus;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

final class DocumentSession {
    private final DocumentId documentId;
    private final String authoredInitialBlueId;
    private List<SubscriptionDelta.Entry> activeSubscriptions;
    private final List<DocumentRevision> revisions = new ArrayList<>();
    private final Set<String> terminalEntryBlueIds = new LinkedHashSet<>();
    private final Set<String> transitionReceipts = new LinkedHashSet<>();
    private final StateEpochs stateEpochs = new StateEpochs();
    private EmbeddedOnlyLayout layout;
    private SessionStatus status;
    private ExternalOrderKey readyThrough;
    private long epoch;
    private long applicationSequence;

    public DocumentSession(
            DocumentId documentId,
            ExactValue authoredInitialState,
            EmbeddedOnlyLayout initializedLayout,
            List<SubscriptionDelta.Entry> activeSubscriptions,
            ExternalOrderKey admissionFrontier,
            DocumentRevision initializationRevision) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.authoredInitialBlueId = Objects.requireNonNull(
                authoredInitialState, "authoredInitialState").blueId();
        this.layout = Objects.requireNonNull(
                initializedLayout, "initializedLayout");
        this.activeSubscriptions = List.copyOf(Objects.requireNonNull(
                activeSubscriptions, "activeSubscriptions"));
        this.status = SessionStatus.READY;
        this.readyThrough = Objects.requireNonNull(
                admissionFrontier, "admissionFrontier");
        this.epoch = 0L;
        this.applicationSequence = 0L;
        this.revisions.add(Objects.requireNonNull(
                initializationRevision, "initializationRevision"));
        if (!initializationRevision.documentId().equals(documentId)
                || initializationRevision.epoch() != 0L
                || initializationRevision.kind() != DocumentRevision.Kind.INITIALIZATION
                || !initializationRevision.after().blueId()
                        .equals(initializedLayout.rootBlueId())) {
            throw new IllegalArgumentException(
                    "Initialization revision does not belong to session");
        }
        stateEpochs.record(initializationRevision);
        this.transitionReceipts.add(
                "initialization|" + documentId.value());
    }

    public DocumentId documentId() {
        return documentId;
    }

    public String authoredInitialBlueId() {
        return authoredInitialBlueId;
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized long nextApplicationOrder() {
        return Math.addExact(applicationSequence, 1L);
    }

    public synchronized EmbeddedOnlyLayout layout() {
        return layout;
    }

    public synchronized List<SubscriptionDelta.Entry> activeSubscriptions() {
        return activeSubscriptions;
    }

    public synchronized SessionStatus status() {
        return status;
    }

    public synchronized ExternalOrderKey readyThrough() {
        return readyThrough;
    }

    public synchronized DocumentRevision currentRevision() {
        return revisions.get(revisions.size() - 1);
    }

    public synchronized List<DocumentRevision> revisions() {
        return Collections.unmodifiableList(new ArrayList<>(revisions));
    }

    public synchronized DocumentRevision revision(long childEpoch) {
        if (childEpoch < 0L || childEpoch >= revisions.size()) {
            throw new IllegalArgumentException(
                    "Unknown revision epoch " + childEpoch);
        }
        DocumentRevision revision = revisions.get(Math.toIntExact(childEpoch));
        if (revision.epoch() != childEpoch) {
            throw new IllegalStateException("Revision history is not contiguous");
        }
        return revision;
    }

    public synchronized List<DocumentRevision> revisionsAfter(
            long epochExclusive) {
        long firstEpoch = Math.max(0L, Math.addExact(epochExclusive, 1L));
        if (firstEpoch >= revisions.size()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(
                revisions.subList(Math.toIntExact(firstEpoch), revisions.size())));
    }

    public synchronized boolean hasTerminalEntry(String entryBlueId) {
        return terminalEntryBlueIds.contains(entryBlueId);
    }

    public synchronized Optional<DocumentRevision> revisionForEntry(
            String entryBlueId) {
        String identity = Objects.requireNonNull(entryBlueId, "entryBlueId");
        return revisions.stream()
                .filter(revision -> revision.sourceEntry()
                        .map(entry -> entry.blueId().equals(identity))
                        .orElse(false))
                .findFirst();
    }

    public synchronized boolean hasTransitionReceipt(String receiptId) {
        return transitionReceipts.contains(Objects.requireNonNull(
                receiptId, "receiptId"));
    }

    public synchronized OptionalLong epochForState(String exactBlueId) {
        return stateEpochs.first(exactBlueId);
    }

    synchronized long resolveAdmissionEpoch(
            String stateBlueId,
            Long exactEpoch) {
        String state = Objects.requireNonNull(stateBlueId, "stateBlueId");
        if (exactEpoch == null) {
            return stateEpochs.resolve(documentId, authoredInitialBlueId, state);
        }
        if (exactEpoch > epoch) {
            throw invalidAdmission("unknown epoch " + exactEpoch);
        }
        if (!revision(exactEpoch).after().blueId().equals(state)) {
            throw invalidAdmission("epoch does not match state " + state);
        }
        return exactEpoch;
    }

    public synchronized void markCatchingUp() {
        if (status == SessionStatus.BLOCKED
                || status == SessionStatus.TERMINATED) {
            throw new IllegalStateException("Cannot catch up from " + status);
        }
        status = SessionStatus.CATCHING_UP;
    }

    public synchronized void markBlocked() {
        status = SessionStatus.BLOCKED;
    }

    public synchronized void markReady(ExternalOrderKey frontier) {
        if (status == SessionStatus.BLOCKED
                || status == SessionStatus.TERMINATED) {
            throw new IllegalStateException(
                    "Cannot become ready from " + status);
        }
        status = SessionStatus.READY;
        if (readyThrough == null || frontier.compareTo(readyThrough) > 0) {
            readyThrough = frontier;
        }
    }

    synchronized void restoreCoordinationState(
            SessionStatus restoredStatus,
            ExternalOrderKey restoredReadyThrough) {
        this.status = Objects.requireNonNull(
                restoredStatus, "restoredStatus");
        this.readyThrough = Objects.requireNonNull(
                restoredReadyThrough, "restoredReadyThrough");
    }

    public synchronized void commit(
            DocumentRevision revision,
            EmbeddedOnlyLayout nextLayout,
            ExternalOrderKey committedFrontier,
            List<SubscriptionDelta.Entry> nextSubscriptions,
            String transitionReceipt) {
        Objects.requireNonNull(revision, "revision");
        if (!revision.documentId().equals(documentId)) {
            throw new IllegalArgumentException(
                    "Revision belongs to another session");
        }
        long expectedEpoch = Math.addExact(epoch, 1L);
        if (revision.epoch() != expectedEpoch) {
            throw new IllegalStateException(
                    "Expected epoch " + expectedEpoch
                            + " but got " + revision.epoch());
        }
        if (revision.rootApplicationOrder()
                != Math.addExact(applicationSequence, 1L)) {
            throw new IllegalStateException(
                    "Application order is not contiguous");
        }
        String receipt = Objects.requireNonNull(
                transitionReceipt, "transitionReceipt");
        if (receipt.isBlank() || transitionReceipts.contains(receipt)) {
            throw new IllegalStateException(
                    "Duplicate or blank transition receipt " + receipt);
        }
        List<SubscriptionDelta.Entry> subscriptions = List.copyOf(
                Objects.requireNonNull(
                        nextSubscriptions, "nextSubscriptions"));
        this.layout = Objects.requireNonNull(nextLayout, "nextLayout");
        this.activeSubscriptions = subscriptions;
        this.epoch = expectedEpoch;
        this.applicationSequence = revision.rootApplicationOrder();
        this.revisions.add(revision);
        transitionReceipts.add(receipt);
        stateEpochs.record(revision);
        revision.sourceEntry().ifPresent(entry ->
                terminalEntryBlueIds.add(entry.blueId()));
        if (committedFrontier != null
                && (readyThrough == null
                || committedFrontier.compareTo(readyThrough) > 0)) {
            readyThrough = committedFrontier;
        }
    }

    static final class StateEpochs {
        private final Map<String, Long> first = new LinkedHashMap<>();
        private final Set<String> ambiguous = new LinkedHashSet<>();

        void record(DocumentRevision revision) {
            String state = revision.after().blueId();
            Long prior = first.putIfAbsent(state, revision.epoch());
            if (prior != null && prior != revision.epoch()) {
                ambiguous.add(state);
            }
        }

        OptionalLong first(String stateBlueId) {
            Long epoch = first.get(Objects.requireNonNull(
                    stateBlueId, "stateBlueId"));
            return epoch == null ? OptionalLong.empty() : OptionalLong.of(epoch);
        }

        long resolve(
                DocumentId documentId,
                String authoredInitialBlueId,
                String stateBlueId) {
            OptionalLong known = first(stateBlueId);
            boolean authored = authoredInitialBlueId.equals(stateBlueId);
            if (ambiguous.contains(stateBlueId)
                    || authored && known.isPresent() && known.getAsLong() > 0L) {
                throw new IllegalStateException(
                        "Ambiguous historical state " + stateBlueId
                                + "; exact admitted epoch is required");
            }
            if (authored) {
                return -1L;
            }
            if (known.isEmpty()) {
                throw invalidAdmission("unknown state " + stateBlueId
                        + " for " + documentId);
            }
            return known.getAsLong();
        }
    }

    private static IllegalStateException invalidAdmission(String diagnostic) {
        return new IllegalStateException(
                "Invalid admission evidence: " + diagnostic);
    }
}
