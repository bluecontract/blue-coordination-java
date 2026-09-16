package blue.coordination.sdk;

import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.language.processor.NoncommittingExecutionException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Closed selected SDK rows, not a complete engine restore or a publication port.
 * The caller must pin descriptor membership and dependency reads in its selected
 * runtime read set. Addresses authenticate physical bytes, not semantic authority.
 */
final class SdkPointStorage {
    private static final String FORMAT = "blue-coordination/rooted-sdk-point/1";
    private enum Kind { TIMELINE, INTENT, CORE_ENTRY, ENTRY_RESULT, SOURCE_RESULT }

    /** Operational bounds only. Memo charges encoded row plus descriptor bytes, not JVM heap. */
    record Limits(int maximumRowBytes, int maximumDescriptorBytes, long maximumMemoBytes, int maximumMemoEntries) {
        Limits {
            if (maximumRowBytes <= 0 || maximumDescriptorBytes <= 0 || maximumMemoBytes < 0 || maximumMemoEntries < 0)
                throw new IllegalArgumentException("Invalid SDK point storage bounds");
        }
    }

    /** Opaque owned bytes, supplied by an authenticated selected index, never a writable authority factory. */
    static final class Descriptor {
        private final byte[] bytes;
        private Descriptor(byte[] bytes) { this.bytes = bytes.clone(); }
        byte[] bytes() { return bytes.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Descriptor descriptor && Arrays.equals(bytes, descriptor.bytes);
        }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }

    /** Point reads from the caller's pinned selected indexes. A required absent row fails closed. */
    interface References {
        Descriptor timeline(String timelineId);
        Descriptor coreEntry(String entryBlueId);
    }

    private final CoordinationImmutableObjectStore objects;
    private final Limits limits;
    private final SdkStorageCodec.Configuration configuration;
    private final SdkStorageCodec rows;
    private final SdkStorageCodec descriptors;

    SdkPointStorage(CoordinationImmutableObjectStore objects, Limits limits, SdkStorageCodec.Configuration configuration) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        rows = new SdkStorageCodec(new Object(), limits.maximumRowBytes());
        descriptors = new SdkStorageCodec(new Object(), limits.maximumDescriptorBytes());
    }

    Descriptor retainTimeline(String id, TimelineHandle value) { return retain(Kind.TIMELINE, id, value); }
    Descriptor retainIntent(String id, SdkCoordinationRuntime.EntryIntent value) { return retain(Kind.INTENT, id, value); }
    Descriptor retainCoreEntry(String id, SdkStorageCodec.CoreEntrySnapshot value) { return retain(Kind.CORE_ENTRY, id, value); }
    Descriptor retainEntryResult(String id, EntryResult value) { return retain(Kind.ENTRY_RESULT, id, value); }
    Descriptor retainSourceResult(SourceHistoryPrerequisite key, DrainResult value) { return retain(Kind.SOURCE_RESULT, key, value); }

    Descriptor descriptor(byte[] bytes) {
        return physical(() -> {
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.length > limits.maximumDescriptorBytes()) throw invalid("SDK point descriptor exceeds byte bound");
            return new Descriptor(bytes);
        });
    }

    private Descriptor retain(Kind kind, Object key, Object value) {
        return physical(() -> {
            byte[] bytes = rows.encode(List.of(FORMAT, kind.name(), configuration, key, value));
            String address = digest(bytes);
            // Bound the descriptor before writing an object; no index is installed here.
            Descriptor descriptor = new Descriptor(descriptors.encode(
                    List.of(FORMAT, kind.name(), configuration, key, address, bytes.length)));
            byte[] retained = objects.putIfAbsent(address, bytes.clone());
            if (!Arrays.equals(bytes, retained)) throw invalid("SDK point object retention returned different bytes");
            return descriptor;
        });
    }

    // Only the owning SDK calls this with its actual bootstrap/configuration. No
    // caller-supplied policy claim substitutes for that runtime binding.
    Scope openScope(Object owner, DefaultCoordinationEngine engine, SdkStorageCodec.Configuration actual,
            References references, Runnable requireOpenOwner) {
        return physical(() -> {
            if (!configuration.equals(actual)) throw invalid("SDK point configuration differs from actual owner");
            var binding = engine.contractsRuntimeBinding();
            if (!binding.languageSpecificationIdentity().equals(actual.language())
                    || !binding.contractsSpecificationIdentity().equals(actual.contracts())
                    || !binding.executionPolicy().equals(actual.policy()))
                throw invalid("SDK point configuration differs from actual engine");
            return new Scope(owner, engine, references, requireOpenOwner);
        });
    }

    final class Scope implements AutoCloseable {
        private final Object owner;
        private final DefaultCoordinationEngine engine;
        private final References references;
        private final Runnable requireOpenOwner;
        private final SdkStorageCodec codec;
        private final LinkedHashMap<Descriptor, Memo> memo = new LinkedHashMap<>(16, .75f, true);
        private long chargedBytes;
        private boolean closed;

        private Scope(Object owner, DefaultCoordinationEngine engine, References references, Runnable requireOpenOwner) {
            this.owner = Objects.requireNonNull(owner);
            this.engine = Objects.requireNonNull(engine);
            this.references = Objects.requireNonNull(references);
            this.requireOpenOwner = Objects.requireNonNull(requireOpenOwner);
            codec = new SdkStorageCodec(owner, limits.maximumRowBytes());
        }

        synchronized TimelineHandle timeline(String id, Descriptor descriptor) {
            return physical(() -> {
                TimelineHandle handle = read(Kind.TIMELINE, id, descriptor, TimelineHandle.class);
                if (!id.equals(handle.id()) || handle.owner() != owner) throw invalid("Invalid SDK Timeline handle key/owner");
                Timeline registered = engine.auditRegisteredTimeline(id).orElseThrow(() -> invalid("SDK Timeline is not registered"));
                if (!registered.actorId().equals(handle.accountId())
                        || !engine.timelineActorKind(id).equals(handle.actorKind().blueType()))
                    throw invalid("SDK Timeline actor differs from actual engine");
                return handle;
            });
        }

        synchronized TimelineEntry coreEntry(String id, Descriptor descriptor) {
            return physical(() -> {
                var snapshot = read(Kind.CORE_ENTRY, id, descriptor, SdkStorageCodec.CoreEntrySnapshot.class);
                TimelineEntry entry = verifiedEntry(id);
                if (!id.equals(snapshot.exactEvent().blueId()) || !Arrays.equals(codec.encode(snapshot),
                        codec.encode(SdkStorageCodec.CoreEntrySnapshot.from(entry))))
                    throw invalid("SDK core entry differs from verified journal row");
                timeline(entry.timeline().timelineId(), references.timeline(entry.timeline().timelineId()));
                return entry; // Return the verified row, never construct TimelineEntry from storage DTOs.
            });
        }

        synchronized SdkCoordinationRuntime.EntryIntent intent(String id, Descriptor descriptor) {
            return physical(() -> {
                var intent = read(Kind.INTENT, id, descriptor, SdkCoordinationRuntime.EntryIntent.class);
                TimelineEntry entry = coreEntry(id, references.coreEntry(id));
                if (intent.targeted()) {
                    var target = blue.language.model.NodePathEditor.getOrNull(entry.exactEvent().copyNode(), "/message/document");
                    if (intent.targetId() == null || intent.expectedTargetBlueId() == null
                            || target == null || !intent.expectedTargetBlueId().equals(target.getBlueId())
                            || !Objects.equals(intent.timelineId(), entry.timeline().timelineId())
                            || !Objects.equals(intent.actorId(), entry.timeline().actorId())
                            || !Objects.equals(intent.operation(), entry.operation()) || !Objects.equals(intent.channel(), entry.channel()))
                        throw invalid("SDK targeted intent differs from original journal row");
                } else if (!intent.equals(SdkCoordinationRuntime.EntryIntent.broadcast()))
                    throw invalid("SDK broadcast intent carries targeted operands");
                return intent;
            });
        }

        synchronized EntryResult entryResult(String id, Descriptor descriptor) {
            return physical(() -> {
                EntryResult result = read(Kind.ENTRY_RESULT, id, descriptor, EntryResult.class);
                if (!id.equals(result.entry().blueId())) throw invalid("SDK result key differs from entry");
                validateHandle(result.entry());
                return result;
            });
        }

        synchronized DrainResult sourceResult(SourceHistoryPrerequisite key, Descriptor descriptor) {
            return physical(() -> {
                DrainResult result = read(Kind.SOURCE_RESULT, key, descriptor, DrainResult.class);
                result.entries().forEach(entry -> validateHandle(entry.entry()));
                return result;
            });
        }

        private void validateHandle(EntryHandle handle) {
            TimelineEntry entry = verifiedEntry(handle.blueId());
            TimelineHandle timeline = timeline(entry.timeline().timelineId(), references.timeline(entry.timeline().timelineId()));
            if (handle.owner() != owner || !handle.timeline().equals(java.util.Optional.of(timeline))
                    || handle.globalSequence().isEmpty() || handle.globalSequence().getAsLong() != entry.globalSequence()
                    || handle.timelineSequence().isEmpty() || handle.timelineSequence().getAsLong() != entry.timelineSequence())
                throw invalid("SDK result handle differs from verified journal coordinates");
        }

        private TimelineEntry verifiedEntry(String id) {
            return engine.auditTimelineEntry(id).orElseThrow(() -> invalid("SDK row has no verified journal entry"));
        }

        private <T> T read(Kind kind, Object key, Descriptor descriptor, Class<T> type) {
            if (closed) throw invalid("SDK point scope is closed");
            requireOpenOwner.run();
            if (descriptor == null) throw invalid("Required SDK point descriptor is missing");
            List<?> fields = descriptors.decode(descriptor.bytes, List.class);
            if (fields.size() != 6 || !FORMAT.equals(fields.get(0)) || !kind.name().equals(fields.get(1))
                    || !configuration.equals(fields.get(2)) || !key.equals(fields.get(3)))
                throw invalid("SDK point descriptor key/kind/configuration mismatch");
            String address = (String) fields.get(4);
            int length = (Integer) fields.get(5);
            if (!address.matches("[0-9a-f]{64}") || length <= 0 || length > limits.maximumRowBytes())
                throw invalid("SDK point descriptor address/length is invalid or exceeds bounds");
            Memo hit = memo.get(descriptor);
            if (hit != null) return type.cast(hit.value());
            byte[] bytes = objects.get(address, length)
                    .orElseThrow(() -> invalid("Referenced SDK point object is missing"));
            if (bytes.length != length || !digest(bytes).equals(address)) throw invalid("SDK point object integrity failure");
            List<?> row = codec.decode(bytes, List.class);
            if (row.size() != 5 || !row.subList(0, 4).equals(fields.subList(0, 4)))
                throw invalid("SDK point payload binding differs from descriptor");
            T value = type.cast(row.get(4));
            long charge = (long) bytes.length + descriptor.bytes.length;
            if (limits.maximumMemoEntries() > 0 && charge <= limits.maximumMemoBytes()) {
                while (!memo.isEmpty() && (memo.size() >= limits.maximumMemoEntries()
                        || chargedBytes > limits.maximumMemoBytes() - charge)) {
                    var first = memo.entrySet().iterator();
                    chargedBytes -= first.next().getValue().charge(); first.remove();
                }
                memo.put(descriptor, new Memo(value, charge)); chargedBytes += charge;
            }
            return value;
        }

        synchronized long chargedBytes() { return chargedBytes; }
        synchronized int memoEntries() { return memo.size(); }
        @Override public synchronized void close() { closed = true; memo.clear(); chargedBytes = 0; }
    }

    private record Memo(Object value, long charge) {}

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static CoordinationObjectStorageException invalid(String message) { return new CoordinationObjectStorageException(message); }
    private static <T> T physical(Supplier<T> operation) {
        try { return operation.get(); }
        catch (NoncommittingExecutionException failure) { throw failure; }
        catch (RuntimeException failure) { throw new CoordinationObjectStorageException("SDK point storage failed", failure); }
    }
}
