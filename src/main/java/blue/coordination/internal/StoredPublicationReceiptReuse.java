package blue.coordination.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Function;

/** Bounded, disposable identity scope for fully decoded immutable closure receipts only. */
final class StoredPublicationReceiptReuse implements AutoCloseable {
    private static final String DIAGNOSTIC_FAMILY = "blue-coordination/owner-publication-receipt-reuse/1";
    private final int maximumEntries;
    private final long maximumEncodedBytes;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final IdentityHashMap<ContractsClosurePublicationReceipt, Entry> identities = new IdentityHashMap<>();
    private long retainedEncodedBytes;
    private Object retentionEpoch = new Object();
    private Function<ContractsClosurePublicationReceipt, ManagedRepresentationVerificationMemo> processProofs;
    private boolean closed;

    private record Entry(byte[] bytes, ContractsClosurePublicationReceipt receipt) { }

    StoredPublicationReceiptReuse() { this(2048, 8L * 1024 * 1024); }

    StoredPublicationReceiptReuse(int maximumEntries, long maximumEncodedBytes) {
        if (maximumEntries < 0 || maximumEncodedBytes < 0) throw new IllegalArgumentException("Negative receipt reuse bound");
        this.maximumEntries = maximumEntries;
        this.maximumEncodedBytes = maximumEncodedBytes;
    }

    synchronized ContractsClosurePublicationReceipt decode(byte[] supplied,
            Function<byte[], ContractsClosurePublicationReceipt> fullyVerifiedDecoder) {
        requireOpen();
        byte[] bytes = Objects.requireNonNull(supplied).clone();
        String digest = digest(bytes);
        diagnosticEvent(DIAGNOSTIC_FAMILY, digest, "LOOKUP", false, bytes.length);
        var known = entries.get(digest);
        if (known != null && Arrays.equals(bytes, known.bytes())) {
            diagnosticEvent(DIAGNOSTIC_FAMILY, digest, "HIT", false, known.bytes().length);
            return known.receipt();
        }
        diagnosticEvent(DIAGNOSTIC_FAMILY, digest, known == null ? "MISS_ABSENT" : "MISS_BYTES_DIFFER", false, bytes.length);
        // The supplied decoder performs all original constructor and canonical round-trip checks.
        // A failed decode is never retained, nor are arbitrary encode-only/staged objects trusted.
        diagnosticEvent(DIAGNOSTIC_FAMILY, digest, "LOAD_BEGIN", false, bytes.length);
        ContractsClosurePublicationReceipt receipt;
        try {
            receipt = Objects.requireNonNull(fullyVerifiedDecoder.apply(bytes.clone()));
            diagnosticEvent(DIAGNOSTIC_FAMILY, digest, "LOAD_SUCCESS", false, bytes.length);
        } catch (RuntimeException | Error failure) {
            diagnosticEvent(DIAGNOSTIC_FAMILY, digest, "LOAD_FAILURE", false, bytes.length);
            throw failure;
        }
        if (maximumEntries == 0 || bytes.length > maximumEncodedBytes) {
            diagnosticEvent(DIAGNOSTIC_FAMILY, digest, maximumEntries == 0 ? "DISABLED" : "NOT_RETAINED_TOTAL_LIMIT",
                    false, bytes.length);
            return receipt;
        }
        if (known != null) {
            diagnosticEvent(DIAGNOSTIC_FAMILY, digest, "REPLACE", false, known.bytes().length);
            remove(digest); // Never equate a digest collision with exact bytes.
        }
        while (!entries.isEmpty() && (entries.size() >= maximumEntries
                || retainedEncodedBytes > maximumEncodedBytes - bytes.length)) {
            String oldest = entries.keySet().iterator().next();
            diagnosticEvent(DIAGNOSTIC_FAMILY, oldest, "EVICT", false, -1);
            remove(oldest);
        }
        var row = new Entry(bytes, receipt);
        entries.put(digest, row); identities.put(receipt, row); retainedEncodedBytes += bytes.length;
        diagnosticEvent(DIAGNOSTIC_FAMILY, digest, "RETAIN", false, bytes.length);
        return receipt;
    }

    synchronized byte[] encode(ContractsClosurePublicationReceipt receipt,
            Function<ContractsClosurePublicationReceipt, byte[]> encoder) {
        requireOpen();
        var known = identities.get(receipt);
        diagnosticEvent(DIAGNOSTIC_FAMILY, "", known == null ? "CANONICAL_IDENTITY_MISS" : "CANONICAL_IDENTITY_HIT",
                false, known == null ? -1 : known.bytes().length);
        return known == null ? encoder.apply(receipt) : known.bytes().clone();
    }

    synchronized boolean contains(ContractsClosurePublicationReceipt receipt) {
        return !closed && identities.containsKey(receipt);
    }

    /** Bound only to the process cache's exact library-issued publication certificate, never an owner. */
    synchronized void bindProcessProofs(
            Function<ContractsClosurePublicationReceipt, ManagedRepresentationVerificationMemo> lookup) {
        requireOpen();
        if (processProofs != null) throw new IllegalStateException("Process publication proof lookup is already bound");
        processProofs = Objects.requireNonNull(lookup);
    }

    synchronized ManagedRepresentationVerificationMemo processProofs(ContractsClosurePublicationReceipt receipt) {
        return closed || processProofs == null ? null : processProofs.apply(receipt);
    }

    /** A proof memo must clear before reuse/retention if any deep receipt was evicted. */
    synchronized Object retentionEpoch() { return retentionEpoch; }
    synchronized long retainedEncodedBytes() { return retainedEncodedBytes; }
    synchronized int retainedEntries() { return entries.size(); }

    private void remove(String digest) {
        var removed = entries.remove(digest);
        identities.remove(removed.receipt()); retainedEncodedBytes -= removed.bytes().length;
        retentionEpoch = new Object();
    }

    synchronized void clear() {
        diagnosticEvent(DIAGNOSTIC_FAMILY, "", "CLEAR", false, retainedEncodedBytes);
        entries.clear(); identities.clear(); retainedEncodedBytes = 0;
        retentionEpoch = new Object();
    }

    @Override public synchronized void close() { closed = true; processProofs = null; clear(); }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Stored publication receipt reuse scope is closed");
    }

    /**
     * No-op seam for hash-pinned external diagnostics; no callback or retained state.
     * This owner's weight is encoded bytes, not the process cache's decoded-weight
     * estimate. Empty digests identify aggregate/identity-only observations; -1 is unknown.
     * LOAD calls the next verified decoder and may itself reuse the process cache.
     */
    static void diagnosticEvent(String family, String digest, String outcome, boolean canonical, long weightBytes) { }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
