package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationDeliveryReceipt;
import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.engine.spi.CoordinationSubscriptionIndex;
import blue.coordination.engine.spi.CoordinationTargetCursor;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared deterministic fixtures for the named Round 3 parallel proofs. */
final class ParallelRootAcceptanceSupport {

    private ParallelRootAcceptanceSupport() {
    }

    static StoredCoordinationEvent event(String identity) {
        return new StoredCoordinationEvent(
                identity,
                identity + "-inventory",
                ExternalOrderKey.of(Arrays.<Object>asList(3L, identity)));
    }

    static IndexedSessionCandidates target(String session) {
        return new IndexedSessionCandidates(
                DocumentSessionId.of(session),
                Collections.singletonList("occurrence-" + session),
                1,
                0L,
                "root-before-" + session,
                "subscriptions-" + session);
    }

    static List<IndexedSessionCandidates> threeTargetsOutOfOrder() {
        return Arrays.asList(
                target("root-c"), target("root-a"), target("root-b"));
    }

    static CoordinationCommittedDelivery committed(
            StoredCoordinationEvent event,
            IndexedSessionCandidates target) {
        String session = target.sessionId().value();
        return new CoordinationCommittedDelivery(
                event.eventBlueId(),
                target.sessionId(),
                target.plannedEpoch(),
                target.plannedRootBlueId(),
                target.plannedEpoch() + 1L,
                "root-after-" + session,
                "transition-" + session,
                Collections.singletonList("outbox-" + session));
    }

    static List<ReceiptSignature> receiptSignatures(
            CoordinationDispatchSnapshot snapshot) {
        List<ReceiptSignature> result = new ArrayList<>();
        for (CoordinationDeliveryReceipt receipt : snapshot.receipts()) {
            result.add(new ReceiptSignature(
                    receipt.sessionId().value(),
                    receipt.status().name(),
                    receipt.attemptCount(),
                    receipt.resultingEpoch().orElse(null),
                    receipt.resultingRootBlueId().orElse(null),
                    receipt.transitionIdentity().orElse(null),
                    receipt.committedOutboxEventBlueIds()));
        }
        return Collections.unmodifiableList(result);
    }

    static SemanticState semanticState(String session) {
        int ordinal = session.charAt(session.length() - 1) - 'a' + 1;
        return new SemanticState(
                "root-after-" + session,
                100L + ordinal,
                Collections.singletonList("outbox-" + session),
                "transition-" + session);
    }

    static final class ReceiptSignature {
        private final String session;
        private final String status;
        private final int attempts;
        private final Long resultingEpoch;
        private final String resultingRoot;
        private final String transition;
        private final List<String> outbox;

        ReceiptSignature(
                String session,
                String status,
                int attempts,
                Long resultingEpoch,
                String resultingRoot,
                String transition,
                List<String> outbox) {
            this.session = session;
            this.status = status;
            this.attempts = attempts;
            this.resultingEpoch = resultingEpoch;
            this.resultingRoot = resultingRoot;
            this.transition = transition;
            this.outbox = Collections.unmodifiableList(
                    new ArrayList<String>(outbox));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ReceiptSignature)) return false;
            ReceiptSignature that = (ReceiptSignature) other;
            return attempts == that.attempts
                    && java.util.Objects.equals(session, that.session)
                    && java.util.Objects.equals(status, that.status)
                    && java.util.Objects.equals(
                            resultingEpoch, that.resultingEpoch)
                    && java.util.Objects.equals(
                            resultingRoot, that.resultingRoot)
                    && java.util.Objects.equals(transition, that.transition)
                    && java.util.Objects.equals(outbox, that.outbox);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    session,
                    status,
                    Integer.valueOf(attempts),
                    resultingEpoch,
                    resultingRoot,
                    transition,
                    outbox);
        }
    }

    static final class SemanticState {
        private final String resultingRoot;
        private final long gas;
        private final List<String> outbox;
        private final String transition;

        SemanticState(
                String resultingRoot,
                long gas,
                List<String> outbox,
                String transition) {
            this.resultingRoot = resultingRoot;
            this.gas = gas;
            this.outbox = Collections.unmodifiableList(
                    new ArrayList<String>(outbox));
            this.transition = transition;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SemanticState)) return false;
            SemanticState that = (SemanticState) other;
            return gas == that.gas
                    && java.util.Objects.equals(
                            resultingRoot, that.resultingRoot)
                    && java.util.Objects.equals(outbox, that.outbox)
                    && java.util.Objects.equals(
                            transition, that.transition);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    resultingRoot,
                    Long.valueOf(gas),
                    outbox,
                    transition);
        }
    }

    static final class FixedIndex implements CoordinationSubscriptionIndex {
        private final List<IndexedSessionCandidates> candidates;
        private int queryCount;

        FixedIndex(List<IndexedSessionCandidates> candidates) {
            this.candidates = new ArrayList<>(candidates);
        }

        int queryCount() {
            return queryCount;
        }

        @Override
        public void replaceSession(ManagedDocumentSnapshot snapshot) {
        }

        @Override
        public void removeSession(DocumentSessionId sessionId) {
        }

        @Override
        public CoordinationTargetCursor openCandidates(
                List<String> exactEventSubscriptionKeys,
                String sourceChannel,
                ExternalOrderKey eventOrderKey) {
            queryCount++;
            List<IndexedSessionCandidates> frozen = new ArrayList<>(
                    candidates);
            Collections.sort(frozen);
            return new CoordinationTargetCursor() {
                private int offset;
                private boolean closed;

                @Override
                public List<IndexedSessionCandidates> nextPage(
                        int maximumRoots) {
                    if (closed || maximumRoots <= 0) {
                        throw new IllegalStateException("invalid cursor use");
                    }
                    int end = Math.min(
                            frozen.size(), offset + maximumRoots);
                    List<IndexedSessionCandidates> page = new ArrayList<>(
                            frozen.subList(offset, end));
                    offset = end;
                    return page;
                }

                @Override
                public boolean exhausted() {
                    return offset >= frozen.size();
                }

                @Override
                public long generation() {
                    return 7L;
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }

        @Override
        public List<IndexedSessionCandidates> candidates(
                List<String> exactEventSubscriptionKeys,
                String sourceChannel,
                ExternalOrderKey eventOrderKey) {
            List<IndexedSessionCandidates> result = new ArrayList<>();
            try (CoordinationTargetCursor cursor = openCandidates(
                    exactEventSubscriptionKeys,
                    sourceChannel,
                    eventOrderKey)) {
                while (!cursor.exhausted()) {
                    result.addAll(cursor.nextPage(128));
                }
            }
            return result;
        }
    }

    static final class SerialSemanticExecutor
            implements CoordinationIndexedDeliveryExecutor {
        private final Map<String, SemanticState> states =
                new LinkedHashMap<>();
        private final List<String> commits = new ArrayList<>();

        @Override
        public CoordinationCommittedDelivery deliver(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                blue.coordination.engine.api.PrefetchPolicy prefetchPolicy) {
            String session = target.sessionId().value();
            commits.add(session);
            states.put(session, semanticState(session));
            return committed(event, target);
        }

        Map<String, SemanticState> states() {
            return Collections.unmodifiableMap(states);
        }

        List<String> commits() {
            return Collections.unmodifiableList(commits);
        }
    }
}
