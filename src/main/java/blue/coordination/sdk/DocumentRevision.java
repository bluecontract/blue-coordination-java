package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable application-safe history record for one committed transition. */
public final class DocumentRevision {
    /** Stable semantic revision kinds without physical storage detail. */
    public enum Kind {
        /** Initial exact authored state. */
        INITIALIZATION,
        /** State produced from an external Timeline Entry. */
        TIMELINE_ENTRY,
        /** Parent state advanced through one managed child epoch. */
        EMBEDDED_REVISION_APPLICATION,
        /** Readiness marker after historical work reached its frontier. */
        CATCH_UP_COMPLETED
    }

    private final DocumentId documentId;
    private final long epoch;
    private final Kind kind;
    private final ExactBlueValue before;
    private final ExactBlueValue after;
    private final EntryHandle sourceEntry;
    private final List<PublicEvent> publicEvents;
    private final long processingGas;

    /** Creates one immutable public revision. */
    public DocumentRevision(
            DocumentId documentId,
            long epoch,
            Kind kind,
            ExactBlueValue before,
            ExactBlueValue after,
            EntryHandle sourceEntry,
            List<PublicEvent> publicEvents,
            long processingGas) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.epoch = SdkPreconditions.requireNonNegative(epoch, "epoch");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.before = before;
        this.after = Objects.requireNonNull(after, "after");
        this.sourceEntry = sourceEntry;
        this.publicEvents = List.copyOf(Objects.requireNonNull(
                publicEvents, "publicEvents"));
        this.processingGas = SdkPreconditions.requireNonNegative(
                processingGas, "processingGas");
        if (kind == Kind.INITIALIZATION && before != null) {
            throw new IllegalArgumentException(
                    "Initialization cannot have a prior exact state");
        }
        if (kind == Kind.TIMELINE_ENTRY && sourceEntry == null) {
            throw new IllegalArgumentException(
                    "Timeline revision requires a source entry");
        }
    }

    /** Managed lineage whose state committed. */
    public DocumentId documentId() {
        return documentId;
    }

    /** Document-local committed epoch. */
    public long epoch() {
        return epoch;
    }

    /** Semantic transition kind. */
    public Kind kind() {
        return kind;
    }

    /** Exact prior state, absent for initialization. */
    public Optional<ExactBlueValue> before() {
        return Optional.ofNullable(before);
    }

    /** Exact committed state. */
    public ExactBlueValue after() {
        return after;
    }

    /** External source entry when the transition consumed one. */
    public Optional<EntryHandle> sourceEntry() {
        return Optional.ofNullable(sourceEntry);
    }

    /** Exact public events emitted by this transition. */
    public List<PublicEvent> publicEvents() {
        return publicEvents;
    }

    /** Frozen semantic gas charged to this transition. */
    public long processingGas() {
        return processingGas;
    }
}
