package blue.coordination.internal;

import blue.coordination.api.*;
import java.util.*;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Closed storage bridge for independently completed source-stage evidence. */
final class SourceStageStorageCodec {
    private static final String FORMAT = "blue-coordination/source-stage/1";
    private final int maximumBytes;
    private final SourceDiscoveryStorageCodec sources;
    private final CoreReceiptStorageCodec receipts;
    /** Creates a physical codec, without a runtime or provider. */
    SourceStageStorageCodec(int maximumBytes, int maximumDepth) {
        this.maximumBytes = maximumBytes;
        sources = new SourceDiscoveryStorageCodec(maximumBytes, maximumDepth);
        receipts = new CoreReceiptStorageCodec(maximumBytes, maximumDepth);
    }
    /** Encodes library observations; no mutable authority crosses this boundary. */
    byte[] encode(SourceHistoryStageResult value) {
        Objects.requireNonNull(value);
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(FORMAT); sources.descriptor(out, value.selection().prerequisite());
            list(out, value.selection().entryOwners(), (writer, owner) -> {
                writer.text(owner.documentId().value()); optional(writer, owner.predecessor().orElse(null), (w, predecessor) -> {
                    w.longValue(predecessor.epoch()); w.text(predecessor.headBlueId()); w.text(predecessor.historyIdentity());
                    w.longValue(predecessor.graphGeneration()); w.text(predecessor.closureIdentity());
                });
            });
            list(out, value.selection().invocationIdentities(), Writer::text);
            optional(out, value.result().admission().orElse(null), (w, admission) -> w.bytes(receipts.encodeAdmission(admission)));
            optional(out, value.result().processing().orElse(null), (w, processing) -> w.bytes(receipts.encodeDrain(processing,
                    id -> processing.managedEpochApplicationAttempts().stream().map(ManagedEpochApplicationAttempt::work)
                            .filter(work -> work.workIdentity().equals(id)).findFirst().orElseThrow(() ->
                                    new IllegalArgumentException("Source stage receipt lost its exact attempted work")))));
            out.bool(value.result().replayed()); list(out, value.resultOwners(), (w, id) -> w.text(id.value()));
            out.bool(value.selectionInvalidated());
        });
    }
    /** Rejects malformed, noncanonical and physically oversized observations. */
    SourceHistoryStageResult decode(byte[] bytes) {
        var value = SessionStorageWire.decode(Objects.requireNonNull(bytes), maximumBytes, in -> {
            require(FORMAT.equals(text(in)), "Wrong source stage format"); var prerequisite = sources.descriptor(in);
            var owners = list(in, r -> {
                var id = DocumentId.of(text(r));
                return new SourceHistoryStageContext.Owner(id, Optional.ofNullable(optional(r, reader ->
                        new ProcessingStageContext.Owner(id, reader.longValue(), text(reader), text(reader), reader.longValue(), text(reader)))));
            });
            var context = new SourceHistoryStageContext(prerequisite, owners, list(in, SessionRecordCodec::text));
            var result = new SourceHistoryPrerequisiteResult(prerequisite,
                    Optional.ofNullable(optional(in, r -> receipts.decodeAdmission(r.bytes(maximumBytes)))),
                    Optional.ofNullable(optional(in, r -> receipts.decodeDrain(r.bytes(maximumBytes)))), in.bool());
            return new SourceHistoryStageResult(context, result, list(in, r -> DocumentId.of(text(r))), in.bool());
        });
        require(Arrays.equals(bytes, encode(value)), "Noncanonical source stage evidence"); return value;
    }
}
