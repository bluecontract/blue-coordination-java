package blue.coordination.basic.engine;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact auditable history record for one committed document transition. */
public final class DocumentRevision {
    private final DocumentId documentId;
    private final long epoch;
    private final long rootApplicationOrder;
    private final RevisionKind kind;
    private final ExactNodeValue before;
    private final ExactNodeValue after;
    private final ExactTimelineEntry sourceEntry;
    private final CatchUpCause catchUpCause;
    private final List<Node> emittedEvents;
    private final long processingGas;

    public DocumentRevision(
            DocumentId documentId,
            long epoch,
            long rootApplicationOrder,
            RevisionKind kind,
            ExactNodeValue before,
            ExactNodeValue after,
            ExactTimelineEntry sourceEntry,
            CatchUpCause catchUpCause,
            List<Node> emittedEvents,
            long processingGas) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative");
        }
        if (rootApplicationOrder < 0L) {
            throw new IllegalArgumentException(
                    "rootApplicationOrder must be non-negative");
        }
        if (processingGas < 0L) {
            throw new IllegalArgumentException("processingGas must be non-negative");
        }
        this.epoch = epoch;
        this.rootApplicationOrder = rootApplicationOrder;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.before = before;
        this.after = Objects.requireNonNull(after, "after");
        this.sourceEntry = sourceEntry;
        this.catchUpCause = catchUpCause;
        List<Node> events = new ArrayList<>();
        for (Node event : Objects.requireNonNull(emittedEvents, "emittedEvents")) {
            events.add(Objects.requireNonNull(event, "event").clone());
        }
        this.emittedEvents = Collections.unmodifiableList(events);
        this.processingGas = processingGas;
        if (kind == RevisionKind.INITIALIZATION && sourceEntry != null) {
            throw new IllegalArgumentException(
                    "Initialization revision cannot have a Timeline Entry");
        }
        if (kind == RevisionKind.TIMELINE_ENTRY && sourceEntry == null) {
            throw new IllegalArgumentException(
                    "Timeline revision requires a source entry");
        }
    }

    public DocumentId documentId() {
        return documentId;
    }

    public long epoch() {
        return epoch;
    }

    public long rootApplicationOrder() {
        return rootApplicationOrder;
    }

    public RevisionKind kind() {
        return kind;
    }

    public Optional<ExactNodeValue> before() {
        return Optional.ofNullable(before);
    }

    public ExactNodeValue after() {
        return after;
    }

    public Optional<ExactTimelineEntry> sourceEntry() {
        return Optional.ofNullable(sourceEntry);
    }

    public Optional<ExternalOrderKey> sourceOrderKey() {
        return sourceEntry == null
                ? Optional.empty()
                : Optional.of(sourceEntry.sourceOrderKey());
    }

    public Optional<CatchUpCause> catchUpCause() {
        return Optional.ofNullable(catchUpCause);
    }

    public List<Node> emittedEvents() {
        List<Node> copy = new ArrayList<>(emittedEvents.size());
        emittedEvents.forEach(event -> copy.add(event.clone()));
        return Collections.unmodifiableList(copy);
    }

    public long processingGas() {
        return processingGas;
    }
}
