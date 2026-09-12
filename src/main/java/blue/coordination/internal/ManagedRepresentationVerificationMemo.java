package blue.coordination.internal;

import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.ManagedRepresentationTransition;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Pure constructor reuse, exclusively for publications already retained by this memo's store. */
final class ManagedRepresentationVerificationMemo {
    private final int maximumEntries;
    private final long maximumWeight;
    private final LinkedHashMap<Request, ManagedRepresentationTransition> entries = new LinkedHashMap<>(16, .75f, true);
    private long retainedWeight;

    ManagedRepresentationVerificationMemo() { this(2048, 4L * 1024 * 1024); }

    ManagedRepresentationVerificationMemo(int maximumEntries, long maximumWeight) {
        if (maximumEntries < 0 || maximumWeight < 0) throw new IllegalArgumentException("Negative memo limit");
        this.maximumEntries = maximumEntries;
        this.maximumWeight = maximumWeight;
    }

    // Map operations use the owning store's monitor; Request.prove runs outside that monitor.
    ManagedRepresentationTransition find(Request key) { return entries.get(key); }

    ManagedRepresentationTransition retain(Request key, ManagedRepresentationTransition proved) {
        var concurrent = entries.get(key);
        if (concurrent != null) return concurrent;
        long weight = weight(key, proved);
        if (maximumEntries == 0 || weight > maximumWeight) return proved;
        while (!entries.isEmpty() && (entries.size() >= maximumEntries || retainedWeight > maximumWeight - weight)) {
            var oldest = entries.entrySet().iterator();
            var removed = oldest.next();
            retainedWeight -= weight(removed.getKey(), removed.getValue());
            oldest.remove();
        }
        entries.put(key, proved);
        retainedWeight += weight;
        return proved;
    }

    void clear() { entries.clear(); retainedWeight = 0L; }

    private static long weight(Request key, ManagedRepresentationTransition value) {
        // Bounds additional wrappers/strings, NOT deep store heap. Input/result/selected documents
        // are already retained by the same append-only durable publication inventory. Staged
        // proposals must never enter this memo, since that lifetime argument does not apply.
        return 512L + textWeight(key.document.value()) + textWeight(key.anchor) + textWeight(key.predecessor)
                + textWeight(key.receipt) + textWeight(value.positionIdentity())
                + value.rootedCheckpointReferenceProofIdentity().map(ManagedRepresentationVerificationMemo::textWeight).orElse(0L);
    }

    private static long textWeight(String value) { return 48L + 2L * value.length(); }

    static final class Request {
        private final ContractsClosurePublicationReceipt publication;
        private final DocumentId document;
        private final long epoch;
        private final String anchor;
        private final String predecessor;
        private final ClosureInvocationInput input;
        private final ClosureProcessResult result;
        private final String receipt;

        Request(ContractsClosurePublicationReceipt publication, DocumentId document, long epoch,
                String anchor, String predecessor, ClosureInvocationInput input, ClosureProcessResult result, String receipt) {
            this.publication = Objects.requireNonNull(publication);
            this.document = document;
            this.epoch = epoch;
            this.anchor = anchor;
            this.predecessor = predecessor;
            this.input = input;
            this.result = result;
            this.receipt = receipt;
        }

        // No store, mutable provider, meter or callback is consulted. Failures never reach retain.
        ManagedRepresentationTransition prove() {
            return new ManagedRepresentationTransition(document, epoch, anchor, predecessor, input, result, receipt);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Request key)) return false;
            return publication == key.publication && input == key.input && result == key.result
                    && epoch == key.epoch && Objects.equals(document, key.document)
                    && Objects.equals(anchor, key.anchor) && Objects.equals(predecessor, key.predecessor)
                    && Objects.equals(receipt, key.receipt);
        }

        @Override public int hashCode() {
            return Objects.hash(System.identityHashCode(publication), System.identityHashCode(input),
                    System.identityHashCode(result), document, epoch, anchor, predecessor, receipt);
        }
    }
}
