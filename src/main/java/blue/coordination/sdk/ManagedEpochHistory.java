package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Library-issued immutable receipt history with an authenticated reusable prefix.
 * Each audit still reads the current engine history. A prefix matches only the
 * same immutable receipt objects in the same positions and SDK configuration;
 * matching logical identities alone never certify another body or proof.
 *
 * <p>Receipts contain detached read-only Timeline metadata, not live SDK handles.
 * This artifact retains no engine, provider, meter or mutable session. Hosts must
 * bound its lifetime/weight and still check their own current durable rows and
 * authorities. Eviction and cold reconstruction safely expose a cold suffix.</p>
 */
public final class ManagedEpochHistory {
    private static final Object DETACHED_TIMELINES = new Object();
    private final DocumentId documentId;
    private final SdkStorageCodec.Configuration configuration;
    private final List<ManagedEpochReceipt> receipts;
    private final List<Long> weights;
    private final int retainedPrefixLength;
    private final long estimatedRetainedBytes;

    private ManagedEpochHistory(DocumentId documentId, SdkStorageCodec.Configuration configuration,
            List<blue.coordination.api.ManagedEpochReceipt> selected, ManagedEpochHistory previous,
            Function<blue.coordination.api.ManagedEpochReceipt, ManagedEpochReceipt> project) {
        this.documentId = Objects.requireNonNull(documentId);
        this.configuration = Objects.requireNonNull(configuration);
        List<blue.coordination.api.ManagedEpochReceipt> current = List.copyOf(selected);
        for (int i = 0; i < current.size(); i++) {
            var receipt = current.get(i);
            if (!receipt.documentId().equals(documentId) || receipt.epoch() != i)
                throw new IllegalArgumentException("Managed receipt history has another owner or a noncontiguous epoch");
        }
        int prefix = 0;
        if (previous != null && documentId.equals(previous.documentId) && configuration.equals(previous.configuration)
                && previous.receipts.size() <= current.size()) {
            while (prefix < previous.receipts.size()
                    && previous.receipts.get(prefix).retains(current.get(prefix))) prefix++;
        }
        retainedPrefixLength = prefix;
        List<ManagedEpochReceipt> values = new ArrayList<>(current.size());
        List<Long> retainedWeights = new ArrayList<>(current.size());
        long total = 512L;
        for (int i = 0; i < current.size(); i++) {
            ManagedEpochReceipt value = i < prefix ? previous.receipts.get(i) : detached(current.get(i), project);
            long weight = i < prefix ? previous.weights.get(i) : estimate(value);
            values.add(value); retainedWeights.add(weight); total = addWeight(total, weight);
        }
        receipts = List.copyOf(values);
        weights = List.copyOf(retainedWeights);
        estimatedRetainedBytes = total;
    }

    static ManagedEpochHistory capture(DocumentId document, SdkStorageCodec.Configuration configuration,
            List<blue.coordination.api.ManagedEpochReceipt> selected, ManagedEpochHistory previous,
            Function<blue.coordination.api.ManagedEpochReceipt, ManagedEpochReceipt> project) {
        return new ManagedEpochHistory(document, configuration, selected, previous, project);
    }

    /** Returns the source lineage of every receipt in this artifact. */
    public DocumentId documentId() { return documentId; }

    /** Returns complete immutable ordered receipts; duplicate event occurrences are unchanged. */
    public List<ManagedEpochReceipt> receipts() { return receipts; }

    /** Returns the exact leading count reusable from the supplied previous artifact, otherwise zero. */
    public int retainedPrefixLength() { return retainedPrefixLength; }

    /** Returns every receipt not certified by the previous artifact, in ordinary epoch order. */
    public List<ManagedEpochReceipt> appendedReceipts() { return receipts.subList(retainedPrefixLength, receipts.size()); }

    /**
     * Returns a starting estimate for host cache budgeting, not a retained-heap guarantee.
     * Charges exact body/proof/event/source-entry text plus an eightfold graph allowance;
     * shared graphs are deliberately charged again. Measure actual heap for production sizing.
     */
    public long estimatedRetainedBytes() { return estimatedRetainedBytes; }

    private static ManagedEpochReceipt detached(blue.coordination.api.ManagedEpochReceipt receipt,
            Function<blue.coordination.api.ManagedEpochReceipt, ManagedEpochReceipt> project) {
        var source = project.apply(receipt).sourceEntry().map(entry -> new TimelineEntrySnapshot(
                entry.exact(), entry.operationDetails(), new TimelineHandle(DETACHED_TIMELINES, entry.timeline().id(),
                        entry.timeline().accountId(), entry.timeline().actorKind()), entry.previousEntryBlueId(),
                entry.timestampMicros(), entry.globalSequence(), entry.timelineSequence()));
        return new ManagedEpochReceipt(receipt, source.orElse(null));
    }

    private static long estimate(ManagedEpochReceipt receipt) {
        long bytes = addWeight(512L, receipt.afterDocument().verifiedEvidence().textBytes());
        bytes = addWeight(bytes, 2L * (receipt.receiptIdentity().length() + (long) receipt.documentId().value().length()
                + receipt.originalCauseIdentity().length() + receipt.contractsTransitionReceiptIdentity().length()
                + receipt.commitCompanionIdentity().length() + receipt.beforeBlueId().map(String::length).orElse(0)));
        for (var event : receipt.emittedEvents()) bytes = addWeight(bytes,
                addWeight(512L, event.exactEvent().verifiedEvidence().textBytes()));
        if (receipt.sourceEntry().isPresent()) {
            var entry = receipt.sourceEntry().orElseThrow();
            bytes = addWeight(bytes, entry.exact().verifiedEvidence().textBytes());
            if (entry.request().isPresent()) bytes = addWeight(bytes, entry.request().orElseThrow().verifiedEvidence().textBytes());
        }
        try { return Math.multiplyExact(bytes, 8L); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    static long addWeight(long first, long second) {
        try { return Math.addExact(first, second); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
}
