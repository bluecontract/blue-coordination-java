package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import blue.language.processor.closure.ManagedRepresentationCause;
import java.util.Arrays;
import java.util.Objects;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Closed exact work coordinates; storage does not select, authenticate or apply source history. */
final class ManagedWorkStorageCodec {
    private static final String FORMAT = "blue-coordination/managed-work-storage/1";
    private final int maximumBytes;
    private final ClosureExecutionEvidenceStorageCodec evidence;

    ManagedWorkStorageCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, null);
    }

    ManagedWorkStorageCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache) {
        this.maximumBytes = maximumBytes;
        evidence = new ClosureExecutionEvidenceStorageCodec(maximumBytes, maximumDepth,
                new StoredClosureResultCodec(maximumBytes, maximumDepth, cache).configured());
    }

    byte[] encode(ManagedEpochApplicationWork work) {
        return SessionStorageWire.encode(maximumBytes, out -> { out.text(FORMAT); work(out, work); });
    }

    ManagedEpochApplicationWork decode(byte[] bytes) {
        return physical(() -> {
            var work = SessionStorageWire.decode(bytes, maximumBytes, in -> {
                require(FORMAT.equals(text(in)), "Wrong historical work format"); return work(in);
            });
            require(Arrays.equals(bytes, encode(work)), "Noncanonical historical work record");
            return work;
        });
    }

    void work(Writer out, ManagedEpochApplicationWork work) {
        Objects.requireNonNull(work);
        out.text(work.workIdentity()); out.text(work.planIdentity()); out.text(work.barrierIdentity());
        out.text(work.sourceReceiptIdentity()); out.text(work.sourceDocumentId().value()); out.longValue(work.sourceEpoch());
        out.text(work.consumerDocumentId().value()); out.text(work.targetOccurrenceIdentity()); out.text(work.targetPath());
        out.longValue(work.activationGeneration()); out.longValue(work.expectedConsumerCommittedEpoch());
        out.text(work.expectedConsumerCommittedBlueId()); out.longValue(work.expectedGraphGeneration());
        optional(out, work.representationCause().orElse(null), this::cause);
        optional(out, work.successorRepresentationCause().orElse(null), this::cause);
    }

    ManagedEpochApplicationWork work(Reader in) {
        String identity = text(in);
        var coordinates = ManagedEpochApplicationWork.identified(text(in), text(in), text(in), DocumentId.of(text(in)),
                in.longValue(), DocumentId.of(text(in)), text(in), text(in), in.longValue(), in.longValue(), text(in), in.longValue());
        var representation = optional(in, this::cause);
        var successor = optional(in, this::cause);
        require(representation == null || successor == null, "Historical work has two incompatible cause roles");
        var work = representation != null ? ManagedEpochApplicationWork.identifiedRepresentation(coordinates, representation)
                : successor != null ? ManagedEpochApplicationWork.identifiedWithSuccessorRepresentationCause(coordinates, successor) : coordinates;
        require(identity.equals(work.workIdentity()), "Historical work differs from its exact cause or coordinates");
        return work;
    }

    private void cause(Writer out, ManagedRepresentationCause cause) { out.bytes(evidence.encodeProcessingCause(cause)); }
    private ManagedRepresentationCause cause(Reader in) {
        var cause = evidence.decodeProcessingCause(in.bytes(maximumBytes));
        require(cause instanceof ManagedRepresentationCause, "Historical work requires representation cause evidence");
        return (ManagedRepresentationCause) cause;
    }
}
