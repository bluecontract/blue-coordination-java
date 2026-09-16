package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ExternalEventCause;
import java.math.BigInteger;
import java.util.Objects;
import java.util.function.Supplier;
import static blue.coordination.internal.SessionStorageWire.require;

/** Library-derived per-source completeness promises, published atomically with their terminal receipts. */
final class RootedProviderFrontiers {
    record Frontier(String timelineId, long closedThroughMicros, ExternalOrderKey sourceOrder,
            String publicationIdentity) {
        Frontier {
            Objects.requireNonNull(timelineId, "timelineId");
            Objects.requireNonNull(sourceOrder, "sourceOrder");
            Objects.requireNonNull(publicationIdentity, "publicationIdentity");
            require(!timelineId.isBlank() && !publicationIdentity.isBlank(), "Blank provider-frontier identity");
            require(timestamp(sourceOrder) == closedThroughMicros, "Provider frontier differs from its exact order");
        }
    }

    private final PersistentOrderedMap<String, Frontier> rows;
    private Supplier<RootedProviderFrontiers> strictVerification;

    private RootedProviderFrontiers(PersistentOrderedMap<String, Frontier> rows) {
        this.rows = Objects.requireNonNull(rows, "rows");
    }

    private RootedProviderFrontiers(PersistentOrderedMap<String, Frontier> rows,
            Supplier<RootedProviderFrontiers> strictVerification) {
        this(rows);
        this.strictVerification = Objects.requireNonNull(strictVerification, "strictVerification");
    }

    static RootedProviderFrontiers empty() {
        return new RootedProviderFrontiers(PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER));
    }

    static RootedProviderFrontiers from(Iterable<ContractsClosurePublicationReceipt> receipts) {
        RootedProviderFrontiers result = empty();
        for (var receipt : receipts) result = result.withReceipt(receipt);
        return result;
    }

    static RootedProviderFrontiers restoreStored(PersistentOrderedMap<String, Frontier> rows) {
        return new RootedProviderFrontiers(rows);
    }

    /** The raw-store scan remains lazy, but no consumer or exporter can use its projection before it succeeds. */
    static RootedProviderFrontiers restoreStrict(PersistentOrderedMap<String, Frontier> rows,
            Supplier<RootedProviderFrontiers> strictVerification) {
        return new RootedProviderFrontiers(rows, strictVerification);
    }

    PersistentOrderedMap<String, Frontier> rows() { requireVerified(); return rows; }

    RootedProviderFrontiers withReceipt(ContractsClosurePublicationReceipt receipt) {
        requireVerified();
        var evidence = Objects.requireNonNull(receipt, "receipt").rootedTerminalEvidence();
        if (evidence == null || evidence.requiredTimelineIds().isEmpty()
                || !(evidence.input().cause() instanceof ExternalEventCause cause)) return this;
        long timestamp = timestamp(cause.sourceOrder());
        var updated = rows;
        // Failed terminal decisions retain the same provider promise. Suspended attempts have no terminal receipt.
        for (String timeline : evidence.requiredTimelineIds()) {
            Frontier previous = updated.get(timeline);
            if (previous != null) require(timeline.equals(previous.timelineId()), "Provider frontier key differs");
            if (previous == null || previous.closedThroughMicros() < timestamp
                    || previous.closedThroughMicros() == timestamp
                    && EmbeddingBinding.TEXT_ORDER.compare(receipt.publicationIdentity(), previous.publicationIdentity()) < 0) {
                updated = updated.put(timeline, new Frontier(timeline, timestamp, cause.sourceOrder(),
                        receipt.publicationIdentity())).map();
            }
        }
        return updated == rows ? this : new RootedProviderFrontiers(updated);
    }

    void requireAfter(TimelineEntry entry, PersistentOrderedMap<String, Boolean> publications,
            PersistentOrderedMap<String, ContractsClosurePublicationReceipt> closures) {
        requireVerified();
        String timeline = entry.timeline().timelineId();
        Frontier selected = rows.get(timeline);
        if (selected == null) return;
        require(timeline.equals(selected.timelineId()), "Provider frontier key differs");
        require(Boolean.TRUE.equals(publications.get(selected.publicationIdentity()))
                        && closures.containsKeyWithoutValue(selected.publicationIdentity()),
                "Provider frontier has no coherent terminal publication");
        if (entry.timestampMicros() <= selected.closedThroughMicros()) {
            throw new IllegalArgumentException("Timeline Entry order " + entry.sourceOrderKey()
                    + " is not after its required Timeline completeness frontier " + selected.sourceOrder());
        }
    }

    /** Raw stores cannot establish completeness of this secondary projection through a selected witness alone. */
    void requireMatches(RootedProviderFrontiers expected) {
        var actualRows = rows.range(null, null).iterator();
        var expectedRows = expected.rows.range(null, null).iterator();
        while (actualRows.hasNext() && expectedRows.hasNext()) {
            require(actualRows.next().equals(expectedRows.next()), "Provider frontier projection differs from retained receipts");
        }
        require(!actualRows.hasNext() && !expectedRows.hasNext(), "Provider frontier projection is incomplete");
    }

    private synchronized void requireVerified() {
        if (strictVerification != null) {
            requireMatches(Objects.requireNonNull(strictVerification.get(), "verified provider frontiers"));
            // Failed reads or comparisons keep the verifier attached. Only this exact immutable basis is memoized.
            strictVerification = null;
        }
    }

    private static long timestamp(ExternalOrderKey order) {
        return new BigInteger(order.components().get(0).toString()).longValueExact();
    }
}
