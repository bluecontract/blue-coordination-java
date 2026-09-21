package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecords.Family;
import blue.coordination.internal.InsertionOrderedStorage;
import blue.coordination.internal.LogicalPointStorage;
import java.util.*;

/** Original command ownership and observer-specific outcomes; neither changes accepted-entry eligibility. */
final class SdkInstanceResults implements AutoCloseable {
    private final SdkCoordinationRuntime owner;
    private final LogicalPointStorage logical;
    private final SdkStorageCodec codec;
    private final SdkPointStorage points;
    private final SdkPointStorage.Scope views;
    private final LogicalPointStorage.Scope<String, byte[]> originals;
    private final LogicalPointStorage.Scope<String, byte[]> executions;
    private final LogicalPointStorage.Scope<String, byte[]> sourceExecutions;

    SdkInstanceResults(SdkCoordinationRuntime owner, LogicalPointStorage logical, SdkStorageCodec codec,
            SdkPointStorage points, SdkPointStorage.Scope views, InsertionOrderedStorage.Limits limits) {
        this.owner = owner; this.logical = logical; this.codec = codec; this.points = points; this.views = views;
        var keys = valueCodec(String.class); var values = valueCodec(byte[].class);
        originals = logical.open(Family.SDK_INTENT, "sdk/original-observer/1", keys, values, limits);
        executions = logical.open(Family.SDK_RESULT, "sdk/execution-results/1", keys, values, limits);
        sourceExecutions = logical.open(Family.SDK_SOURCE_RESULT, "sdk/source-execution-results/1", keys, values, limits);
    }

    void submitted(String entry, DocumentId target) {
        logical.protect(() -> {
            var ref = owner.activeInstance(target);
            var encoded = codec.encode(List.of(ref.documentId().value(), ref.instanceId()));
            var prior = originals.putIfAbsent(entry, encoded);
            if (prior != null && !Arrays.equals(prior, encoded)) throw new IllegalStateException("Accepted command has another original target instance");
            return null;
        });
    }

    void retain(DocumentId observer, DrainResult result, Map<String, EntryResult> originalResults) {
        retain(owner.activeInstance(observer), result, originalResults);
    }
    void retain(DocumentInstanceRef ref, DrainResult result, Map<String, EntryResult> originalResults) {
        logical.protect(() -> {
            owner.requireRetainedInstance(ref);
            for (var entry : result.entries()) {
                String id = entry.entry().blueId(); var key = key(ref, id);
                var descriptor = points.retainEntryResult(id, entry);
                var verified = views.entryResult(id, descriptor);
                if (entry.entry().owner() != verified.entry().owner()) throw new IllegalArgumentException("Foreign execution result owner");
                var prior = read(ref, id);
                // Completed evidence is stable, including a zero-attempt diagnostic; a wait may advance.
                if (prior.isEmpty() || waiting(prior.orElseThrow())) executions.put(key, codec.encode(List.of(
                        ref.documentId().value(), ref.instanceId(), id, descriptor.bytes())));
                byte[] encoded = codec.encode(List.of(ref.documentId().value(), ref.instanceId()));
                var original = originals.putIfAbsent(id, encoded);
                if (original == null || Arrays.equals(original, encoded)) {
                    var old = originalResults.get(id);
                    if (old == null || waiting(old)) originalResults.put(id, entry);
                }
            }
            return null;
        });
    }

    void retainSource(blue.coordination.api.SourceHistoryRequest request, DrainResult result, Map<String, EntryResult> originalResults) {
        logical.protect(() -> {
            owner.requireSourceRequest(request);
            var descriptor = points.retainSourceResult(request.prerequisite(), result);
            views.sourceResult(request.prerequisite(), descriptor);
            var old = source(request);
            if (old.isEmpty() || waiting(old.orElseThrow())) sourceExecutions.put(sourceKey(request), descriptor.bytes());
            retain(request.sourceInstance(), result, originalResults); return null;
        });
    }
    Optional<DrainResult> source(blue.coordination.api.SourceHistoryRequest request) {
        return logical.protect(() -> {
            owner.requireSourceRequest(request);
            var row = sourceExecutions.get(sourceKey(request));
            return row == null ? Optional.empty() : Optional.of(views.sourceResult(request.prerequisite(), points.descriptor(row)));
        });
    }
    private String sourceKey(blue.coordination.api.SourceHistoryRequest request) {
        return HexFormat.of().formatHex(codec.encode(List.of(request.prerequisite(),
                request.requestingInstances().stream().map(ref -> List.of(ref.documentId().value(), ref.instanceId())).toList(),
                List.of(request.sourceInstance().documentId().value(), request.sourceInstance().instanceId()))));
    }

    Optional<EntryResult> original(String entry) {
        return logical.protect(() -> {
            var row = originals.get(entry); if (row == null) return Optional.empty();
            var fields = codec.decode(row, List.class);
            if (fields.size() != 2) throw new IllegalStateException("Original command observer is malformed");
            return read(new DocumentInstanceRef(DocumentId.of((String) fields.get(0)), (String) fields.get(1)), entry);
        });
    }

    Optional<EntryResult> read(DocumentInstanceRef ref, String entry) {
        return logical.protect(() -> {
            owner.requireRetainedInstance(ref);
            byte[] row = executions.get(key(ref, entry)); if (row == null) return Optional.empty();
            var fields = codec.decode(row, List.class);
            if (fields.size() != 4 || !ref.documentId().value().equals(fields.get(0)) || !ref.instanceId().equals(fields.get(1))
                    || !entry.equals(fields.get(2))) throw new IllegalStateException("SDK execution result association differs");
            return Optional.of(views.entryResult(entry, points.descriptor((byte[]) fields.get(3))));
        });
    }

    private static boolean waiting(DrainResult result) {
        // A completed source action is immutable even when aggregate readiness is not quiescent.
        return result.stats().committedTransitions() == 0 && (result.blocked() || result.paused()
                || result.entries().stream().anyMatch(SdkInstanceResults::waiting));
    }
    private static boolean waiting(EntryResult entry) {
        return entry.disposition() == EntryDisposition.BLOCKED || entry.disposition() == EntryDisposition.NEEDS_RESOURCES;
    }
    private String key(DocumentInstanceRef ref, String entry) {
        return HexFormat.of().formatHex(codec.encode(List.of(ref.documentId().value(), ref.instanceId(), entry)));
    }
    private <T> InsertionOrderedStorage.Codec<T> valueCodec(Class<T> type) {
        return new InsertionOrderedStorage.Codec<>() {
            public String identity() { return "sdk/instance-results/1/" + type.getSimpleName(); }
            public byte[] encode(T value) { return codec.encode(value); }
            public T decode(byte[] bytes) { return codec.decode(bytes, type); }
        };
    }
    public void close() { originals.close(); executions.close(); sourceExecutions.close(); }
}
