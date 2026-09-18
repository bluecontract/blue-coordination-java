package blue.coordination.internal;

import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.PublicEventOccurrence;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Named complete result plus exact row position; private Language witnesses are never flattened. */
final class ResultRowStorageCodec {
    private static final String FORMAT = "blue-coordination/result-row-reference/1";
    private final int maximumBytes;

    ResultRowStorageCodec(int maximumBytes) { this.maximumBytes = maximumBytes; }

    byte[] encodeOutbox(PublicEventOccurrence row, ClosureProcessResult original,
            Function<ClosureProcessResult, String> retainResult) {
        return physical(() -> encode("PUBLIC_EVENT", position(original, original.publicEvents(), row), original, retainResult));
    }

    PublicEventOccurrence decodeOutbox(byte[] bytes, Function<String, ClosureProcessResult> openResult) {
        return physical(() -> {
            var selected = decode(bytes, "PUBLIC_EVENT", openResult);
            return select(selected.result().publicEvents(), selected.position());
        });
    }

    byte[] encodeCheckpoint(CheckpointWrite row, ClosureProcessResult original,
            Function<ClosureProcessResult, String> retainResult) {
        return physical(() -> encode("CHECKPOINT", position(original, original.checkpointWrites(), row), original, retainResult));
    }

    CheckpointWrite decodeCheckpoint(byte[] bytes, Function<String, ClosureProcessResult> openResult) {
        return physical(() -> {
            var selected = decode(bytes, "CHECKPOINT", openResult);
            return select(selected.result().checkpointWrites(), selected.position());
        });
    }

    private <T> int position(ClosureProcessResult result, List<T> rows, T selected) {
        require(Objects.requireNonNull(result).commits(), "Published row requires a committing original result");
        int position = -1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) == selected) {
            require(position == -1, "Original result repeats the identical row object"); position = i;
        }
        require(position >= 0, "Row is not the original result member; standalone staged witnesses are unsupported");
        return position;
    }

    private byte[] encode(String kind, int position, ClosureProcessResult result,
            Function<ClosureProcessResult, String> retainResult) {
        return physical(() -> reference(kind, Objects.requireNonNull(retainResult.apply(result)), position));
    }

    private byte[] reference(String kind, String address, int position) {
        require(!address.isBlank(), "Missing original result address");
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(FORMAT); out.text(kind); out.text(address); out.integer(position);
        });
    }

    private record Selected(ClosureProcessResult result, int position) { }
    private Selected decode(byte[] bytes, String kind, Function<String, ClosureProcessResult> openResult) {
        return SessionStorageWire.decode(bytes, maximumBytes, in -> {
            require(FORMAT.equals(text(in)) && kind.equals(text(in)), "Wrong result row reference domain");
            String address = text(in); int position = in.integer();
            require(Arrays.equals(bytes, reference(kind, address, position)), "Noncanonical result row reference");
            var result = Objects.requireNonNull(openResult.apply(address), "Selected original result is missing");
            require(result.commits(), "Selected result cannot publish rows");
            return new Selected(result, position);
        });
    }

    private <T> T select(List<T> rows, int position) {
        require(position >= 0 && position < rows.size(), "Selected result row position is absent"); return rows.get(position);
    }
}
