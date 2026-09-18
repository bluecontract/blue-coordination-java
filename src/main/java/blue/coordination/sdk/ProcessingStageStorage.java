package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ProcessingStageContext;
import blue.coordination.api.storage.CoordinationRecords;
import blue.coordination.api.storage.CoordinationRecords.Bytes;
import java.util.*;

/** Closed detached stage evidence. Decoding creates observations, never runtime or publication authority. */
public final class ProcessingStageStorage {
    private static final String FORMAT = "blue-coordination/processing-stage/1";
    private ProcessingStageStorage() { }

    /** Encodes exact completed evidence without consulting the owner or selecting later work. */
    public static byte[] encode(ProcessingStageResult stage, int maximumBytes) {
        Objects.requireNonNull(stage);
        return codec(maximumBytes).encode(List.of(FORMAT, context(stage.selection()), stage.disposition().name(),
                stage.evidence(), stage.resultOwners(), stage.selectionInvalidated()));
    }

    /** Restores detached observations under a caller's physical byte bound; rejects noncanonical input. */
    public static ProcessingStageResult decode(byte[] bytes, int maximumBytes) {
        var fields = codec(maximumBytes).decode(Objects.requireNonNull(bytes), List.class);
        if (fields.size() != 6 || !FORMAT.equals(fields.get(0))) throw new IllegalArgumentException("Wrong stage evidence format");
        var stage = new ProcessingStageResult(ProcessingStageResult.Disposition.valueOf((String) fields.get(2)),
                (DrainResult) fields.get(3), restore(list(fields.get(1))), documents(fields.get(4)), (Boolean) fields.get(5));
        if (!Arrays.equals(bytes, encode(stage, maximumBytes))) throw new IllegalArgumentException("Noncanonical stage evidence");
        return stage;
    }

    /** Stable selected transition identity; excludes elapsed measurements and host retry/attempt counters. */
    public static String selectionIdentity(ProcessingStageContext selection, int maximumBytes) {
        var encoded = codec(maximumBytes).encode(List.of(FORMAT, context(Objects.requireNonNull(selection))));
        return "sha256:" + HexFormat.of().formatHex(CoordinationRecords.sha256(new Bytes(encoded)).copy());
    }

    private static SdkStorageCodec codec(int maximumBytes) { return new SdkStorageCodec(new Object(), maximumBytes); }
    private static List<?> context(ProcessingStageContext value) {
        return List.of(value.kind().name(), value.root(), value.causes(), value.entryOwners().stream().map(owner ->
                List.of(owner.documentId(), owner.epoch(), owner.headBlueId(), owner.historyIdentity(),
                        owner.graphGeneration(), owner.closureIdentity())).toList(), value.invocationIdentities());
    }
    private static ProcessingStageContext restore(List<?> fields) {
        if (fields.size() != 5) throw new IllegalArgumentException("Wrong stage selection fields");
        var owners = new ArrayList<ProcessingStageContext.Owner>();
        for (Object row : list(fields.get(3))) {
            var owner = list(row);
            if (owner.size() != 6) throw new IllegalArgumentException("Wrong stage owner fields");
            owners.add(new ProcessingStageContext.Owner((DocumentId) owner.get(0), (Long) owner.get(1), (String) owner.get(2),
                    (String) owner.get(3), (Long) owner.get(4), (String) owner.get(5)));
        }
        return new ProcessingStageContext(ProcessingStageContext.Kind.valueOf((String) fields.get(0)),
                (DocumentId) fields.get(1), strings(fields.get(2)), owners, strings(fields.get(4)));
    }
    private static List<?> list(Object value) { return (List<?>) value; }
    private static List<String> strings(Object value) { return list(value).stream().map(String.class::cast).toList(); }
    private static List<DocumentId> documents(Object value) { return list(value).stream().map(DocumentId.class::cast).toList(); }
}
