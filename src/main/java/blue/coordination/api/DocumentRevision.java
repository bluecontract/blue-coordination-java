package blue.coordination.api;

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
    private final DocumentRevision.Kind kind;
    private final ExactValue before;
    private final ExactValue after;
    private final TimelineEntry sourceEntry;
    private final TimelineEntry.CatchUpCause catchUpCause;
    private final List<Node> emittedEvents;
    private final long processingGas;

    /** Creates one immutable committed transition record. */
    public DocumentRevision(
            DocumentId documentId,
            long epoch,
            long rootApplicationOrder,
            DocumentRevision.Kind kind,
            ExactValue before,
            ExactValue after,
            TimelineEntry sourceEntry,
            TimelineEntry.CatchUpCause catchUpCause,
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
        if (kind == DocumentRevision.Kind.INITIALIZATION && sourceEntry != null) {
            throw new IllegalArgumentException(
                    "Initialization revision cannot have a Timeline Entry");
        }
        if (kind == DocumentRevision.Kind.TIMELINE_ENTRY && sourceEntry == null) {
            throw new IllegalArgumentException(
                    "Timeline revision requires a source entry");
        }
    }

    /** Returns the document whose state was committed. */
    public DocumentId documentId() {
        return documentId;
    }

    /** Returns the document-local committed transition number. */
    public long epoch() {
        return epoch;
    }

    /** Returns the deterministic order in which the Root applied this fact. */
    public long rootApplicationOrder() {
        return rootApplicationOrder;
    }

    /** Returns the semantic kind of committed transition. */
    public DocumentRevision.Kind kind() {
        return kind;
    }

    /** Returns the exact state before the transition, absent at initialization. */
    public Optional<ExactValue> before() {
        return Optional.ofNullable(before);
    }

    /** Returns the exact state after the transition. */
    public ExactValue after() {
        return after;
    }

    /** Returns the source entry when this revision originated from a Timeline. */
    public Optional<TimelineEntry> sourceEntry() {
        return Optional.ofNullable(sourceEntry);
    }

    /** Returns the deterministic source order when a source entry exists. */
    public Optional<ExternalOrderKey> sourceOrderKey() {
        return sourceEntry == null
                ? Optional.empty()
                : Optional.of(sourceEntry.sourceOrderKey());
    }

    /** Returns attachment evidence for a historical catch-up transition. */
    public Optional<TimelineEntry.CatchUpCause> catchUpCause() {
        return Optional.ofNullable(catchUpCause);
    }

    /** Returns detached copies of semantic events emitted by the transition. */
    public List<Node> emittedEvents() {
        List<Node> copy = new ArrayList<>(emittedEvents.size());
        emittedEvents.forEach(event -> copy.add(event.clone()));
        return Collections.unmodifiableList(copy);
    }

    /** Returns frozen semantic processing gas charged to this transition. */
    public long processingGas() {
        return processingGas;
    }

    /** Semantic cause of one exact committed document state. */
    public enum Kind {
        /** Initial exact authored state. */
        INITIALIZATION,
        /** State produced from an external exact Timeline Entry. */
        TIMELINE_ENTRY,
        /** Parent state advanced by one autonomous child revision. */
        EMBEDDED_REVISION_APPLICATION,
        /** Readiness marker after historical work reaches its frontier. */
        CATCH_UP_COMPLETED
    }
}
