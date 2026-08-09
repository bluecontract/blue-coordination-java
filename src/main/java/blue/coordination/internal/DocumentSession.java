package blue.coordination.internal;

import blue.coordination.api.SessionStatus;

import blue.coordination.api.ExactValue;

import blue.coordination.api.DocumentRevision;

import blue.coordination.api.DocumentId;

import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Mutable in-memory session state behind the synchronized engine boundary. */
final class DocumentSession {
    private final DocumentId documentId;
    private final ExactValue authoredInitialState;
    private final String authoredInitialBlueId;
    private List<SubscriptionDelta.Entry> activeSubscriptions;
    private final List<DocumentRevision> revisions = new ArrayList<>();
    private final Set<String> terminalEntryBlueIds = new LinkedHashSet<>();
    private final Map<String, EmbeddedLink> linksByPath = new LinkedHashMap<>();
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
        this.authoredInitialState = Objects.requireNonNull(
                authoredInitialState, "authoredInitialState");
        this.authoredInitialBlueId = authoredInitialState.blueId();
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
    }

    private DocumentSession(DocumentSession source) {
        synchronized (source) {
            documentId = source.documentId;
            authoredInitialState = source.authoredInitialState;
            authoredInitialBlueId = source.authoredInitialBlueId;
            activeSubscriptions = source.activeSubscriptions;
            revisions.addAll(source.revisions);
            terminalEntryBlueIds.addAll(source.terminalEntryBlueIds);
            source.linksByPath.forEach((path, link) ->
                    linksByPath.put(path, link.copy()));
            layout = source.layout;
            status = source.status;
            readyThrough = source.readyThrough;
            epoch = source.epoch;
            applicationSequence = source.applicationSequence;
        }
    }

    public DocumentSession copy() {
        return new DocumentSession(this);
    }

    public DocumentId documentId() {
        return documentId;
    }

    public ExactValue authoredInitialState() {
        return authoredInitialState;
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

    public synchronized void replaceActiveSubscriptions(
            List<SubscriptionDelta.Entry> replacement) {
        activeSubscriptions = List.copyOf(Objects.requireNonNull(
                replacement, "replacement"));
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

    /** O(number returned), not O(total history), because epochs are contiguous. */
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

    public synchronized Map<String, EmbeddedLink> linksByPath() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(linksByPath));
    }

    public synchronized void putLink(EmbeddedLink link) {
        Objects.requireNonNull(link, "link");
        if (!link.parentDocumentId().equals(documentId)) {
            throw new IllegalArgumentException("Link belongs to another parent");
        }
        EmbeddedLink existing = linksByPath.putIfAbsent(
                link.occurrencePath(), link);
        if (existing != null
                && !existing.childDocumentId().equals(link.childDocumentId())) {
            throw new IllegalStateException(
                    "Occurrence path already links another child");
        }
    }

    public synchronized void removeLink(String path) {
        linksByPath.remove(path);
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

    public synchronized void commit(
            DocumentRevision revision,
            EmbeddedOnlyLayout nextLayout,
            ExternalOrderKey committedFrontier) {
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
        this.layout = Objects.requireNonNull(nextLayout, "nextLayout");
        this.epoch = expectedEpoch;
        this.applicationSequence = revision.rootApplicationOrder();
        this.revisions.add(revision);
        revision.sourceEntry().ifPresent(entry ->
                terminalEntryBlueIds.add(entry.blueId()));
        if (committedFrontier != null
                && (readyThrough == null
                || committedFrontier.compareTo(readyThrough) > 0)) {
            readyThrough = committedFrontier;
        }
    }
}
