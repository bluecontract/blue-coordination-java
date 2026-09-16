package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import static blue.coordination.internal.SessionStorageWire.*;

/** Small closure-index values referencing unchanged complete receipt envelopes. */
final class StoredClosureReceiptReferenceCodec
        implements PersistentMapCodec<ContractsClosurePublicationReceipt>, AutoCloseable {
    private static final String FORMAT = "blue-coordination/publication-closure-reference/1";
    private static final int MAXIMUM_DESCRIPTOR_BYTES = 256;
    private final CoordinationImmutableObjectStore objects;
    private final int maximumPayloadBytes;
    private final int maximumDescriptorBytes;
    private final PersistentMapCodec<ContractsClosurePublicationReceipt> payloads;
    // One fully decoded immutable value only; no receipt history or payload byte map is retained.
    private ContractsClosurePublicationReceipt lastValue;
    private byte[] lastDescriptor;
    private boolean closed;

    StoredClosureReceiptReferenceCodec(CoordinationImmutableObjectStore objects, int maximumPayloadBytes,
            PersistentMapCodec<ContractsClosurePublicationReceipt> payloads) {
        this.objects = Objects.requireNonNull(objects);
        require(maximumPayloadBytes > 0, "Invalid closure receipt payload bound");
        this.maximumPayloadBytes = maximumPayloadBytes;
        maximumDescriptorBytes = Math.min(maximumPayloadBytes, MAXIMUM_DESCRIPTOR_BYTES);
        this.payloads = Objects.requireNonNull(payloads);
    }

    @Override public String identity() { return "blue-coordination/publication-index-row/closure/2"; }

    /** Prewrites dependencies and the receipt before a new index node can name it. */
    @Override public synchronized ContractsClosurePublicationReceipt prepareForStorage(ContractsClosurePublicationReceipt value) {
        return retain(value).value();
    }

    /** Capture once for this mutation; later encode(value) receives no fresh-identity privilege. */
    @Override public synchronized PreparedEncoding<ContractsClosurePublicationReceipt> prepareEncoding(
            ContractsClosurePublicationReceipt value) {
        return PreparedEncoding.encoded(retain(value).descriptor());
    }

    private PreparedReceipt retain(ContractsClosurePublicationReceipt value) {
        return physical(() -> {
            requireOpen();
            var prepared = Objects.requireNonNull(payloads.prepareForStorage(Objects.requireNonNull(value)));
            byte[] bytes = payload(payloads.encode(prepared));
            String address = PersistentMapStorage.digest(bytes);
            byte[] selected = descriptor(address, bytes.length); // Bound before any receipt write.
            byte[] acknowledged = payload(objects.putIfAbsent(address, bytes.clone()));
            require(Arrays.equals(bytes, acknowledged), "Closure receipt retention acknowledgment differs");
            return new PreparedReceipt(prepared, selected);
        });
    }

    /** Pure canonical encoding; an address does not acknowledge retention or publication. */
    @Override public synchronized byte[] encode(ContractsClosurePublicationReceipt value) {
        return physical(() -> {
            requireOpen(); Objects.requireNonNull(value);
            if (lastValue == value) return lastDescriptor.clone();
            byte[] bytes = payload(payloads.encode(value));
            return descriptor(PersistentMapStorage.digest(bytes), bytes.length);
        });
    }

    /** Point-read the exact bounded object, then apply the existing full receipt decoder. */
    @Override public synchronized ContractsClosurePublicationReceipt decode(byte[] descriptor) {
        return physical(() -> {
            requireOpen();
            require(descriptor != null && descriptor.length <= maximumDescriptorBytes, "Missing or oversized closure receipt reference");
            byte[] selected = descriptor.clone();
            var reference = SessionStorageWire.decode(selected, maximumDescriptorBytes, in -> {
                require(FORMAT.equals(in.text(in.remaining())), "Wrong closure receipt reference format");
                byte[] digest = in.bytes(32);
                require(digest.length == 32, "Invalid closure receipt reference address");
                int length = in.integer();
                require(length > 0 && length <= maximumPayloadBytes, "Invalid closure receipt reference byte bound");
                return new Reference(HexFormat.of().formatHex(digest), length);
            });
            byte[] bytes = payload(objects.get(reference.address(), reference.length())
                    .orElseThrow(() -> new blue.coordination.api.storage.CoordinationObjectStorageException("Missing selected closure receipt")));
            require(bytes.length == reference.length(), "Closure receipt reference length differs");
            require(reference.address().equals(PersistentMapStorage.digest(bytes)), "Closure receipt reference digest differs");
            var restored = Objects.requireNonNull(payloads.decode(bytes.clone()));
            require(Arrays.equals(bytes, payload(payloads.encode(restored))), "Noncanonical referenced closure receipt");
            remember(restored, selected);
            return restored;
        });
    }

    private byte[] descriptor(String address, int length) {
        return SessionStorageWire.encode(maximumDescriptorBytes, out -> {
            out.text(FORMAT); out.bytes(HexFormat.of().parseHex(address)); out.integer(length);
        });
    }

    private byte[] payload(byte[] bytes) {
        require(bytes != null && bytes.length > 0 && bytes.length <= maximumPayloadBytes,
                "Missing or oversized closure receipt payload");
        return bytes.clone();
    }

    private void remember(ContractsClosurePublicationReceipt value, byte[] descriptor) {
        lastValue = value; lastDescriptor = descriptor.clone();
    }

    private void requireOpen() { require(!closed, "Closure receipt reference codec is closed"); }

    @Override public synchronized void close() {
        closed = true; lastValue = null; lastDescriptor = null;
    }

    private record Reference(String address, int length) { }
    private record PreparedReceipt(ContractsClosurePublicationReceipt value, byte[] descriptor) { }
}
