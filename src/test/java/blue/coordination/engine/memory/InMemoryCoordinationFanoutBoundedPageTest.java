package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationDeliveryStatus;
import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.engine.spi.CoordinationSubscriptionIndex;
import blue.coordination.engine.spi.CoordinationTargetCursor;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InMemoryCoordinationFanoutBoundedPageTest {

    private static final int ROOT_COUNT = 10_000;
    private static final int MAXIMUM_ROOTS_PER_PAGE = 128;
    private static final int FAIL_ONCE_AT = 4_321;

    @Test
    void shouldBoundTenThousandRootDiscoveryAndResumeFrozenPages()
            throws IOException {
        LazyRecordingIndex index = new LazyRecordingIndex(ROOT_COUNT);
        LightweightExecutor executor = new LightweightExecutor(
                ROOT_COUNT, FAIL_ONCE_AT);
        InMemoryCoordinationFanout fanout = new InMemoryCoordinationFanout(
                index,
                new InMemoryCoordinationDispatchLedger(),
                executor);
        StoredCoordinationEvent event = new StoredCoordinationEvent(
                "event-ten-thousand",
                "inventory-ten-thousand",
                ExternalOrderKey.of(Arrays.<Object>asList(
                        1L, "event-ten-thousand")));

        long startedAt = System.nanoTime();
        CoordinationFanoutException partial = assertThrows(
                CoordinationFanoutException.class,
                () -> fanout.dispatch(
                        event,
                        Collections.singletonList("actor:alice"),
                        "ownerChannel",
                        MAXIMUM_ROOTS_PER_PAGE,
                        PrefetchPolicy.MINIMUM_ROUND_TRIPS));
        CoordinationDispatchSnapshot completed = fanout.resume(
                event.eventBlueId(),
                PrefetchPolicy.MINIMUM_ROUND_TRIPS);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertFalse(partial.dispatch().complete());
        assertEquals(FAIL_ONCE_AT, partial.dispatch().succeededCount());
        assertEquals(CoordinationDeliveryStatus.COMMITTED,
                partial.dispatch().receipts().get(FAIL_ONCE_AT - 1).status());
        assertEquals(CoordinationDeliveryStatus.FAILED,
                partial.dispatch().receipts().get(FAIL_ONCE_AT).status());
        assertEquals(1, partial.dispatch().receipts()
                .get(FAIL_ONCE_AT).attemptCount());
        assertEquals(CoordinationDeliveryStatus.PENDING,
                partial.dispatch().receipts().get(FAIL_ONCE_AT + 1).status());
        assertTrue(completed.complete());
        assertEquals(ROOT_COUNT, completed.succeededCount());
        assertEquals(ROOT_COUNT, completed.plan().targetCount());
        assertEquals(
                (ROOT_COUNT + MAXIMUM_ROOTS_PER_PAGE - 1)
                        / MAXIMUM_ROOTS_PER_PAGE,
                completed.plan().pageCount());
        assertEquals(completed.plan().pageCount(),
                completed.receiptPages().size());
        assertEquals(2, completed.receipts()
                .get(FAIL_ONCE_AT).attemptCount());
        for (List<IndexedSessionCandidates> page
                : completed.plan().pages()) {
            assertTrue(page.size() <= MAXIMUM_ROOTS_PER_PAGE);
        }

        assertEquals(1, index.queryCount,
                "resume must consume the sealed plan, not requery routes");
        assertEquals(MAXIMUM_ROOTS_PER_PAGE, index.maximumRequestedPage);
        assertTrue(index.maximumReturnedPage <= MAXIMUM_ROOTS_PER_PAGE);
        assertEquals(MAXIMUM_ROOTS_PER_PAGE,
                fanout.ledger().maximumFrozenPageSize(event.eventBlueId()),
                "the real plan store must admit only bounded pages");
        assertEquals(MAXIMUM_ROOTS_PER_PAGE + 1,
                index.maximumCursorTargets,
                "one bounded page plus one merge lookahead is the limit");
        assertEquals(1, executor.attempts[0]);
        assertEquals(1, executor.attempts[FAIL_ONCE_AT - 1]);
        assertEquals(2, executor.attempts[FAIL_ONCE_AT]);
        assertEquals(1, executor.attempts[FAIL_ONCE_AT + 1]);
        assertEquals(1, executor.attempts[ROOT_COUNT - 1]);
        assertEquals(ROOT_COUNT + 1, executor.deliveryCalls);
        assertTrue(elapsedMillis < 15_000L,
                "lightweight 10,000-Root dispatch took "
                        + elapsedMillis + " ms");

        assertNoFlatListPartitioning(
                "src/main/java/blue/coordination/engine/memory/"
                        + "InMemoryCoordinationFanout.java");
        assertDirectCursorPageHandoff(
                "src/main/java/blue/coordination/engine/memory/"
                        + "InMemoryCoordinationFanout.java");
        assertNoFlatListPartitioning(
                "src/main/java/blue/coordination/engine/memory/"
                        + "InMemoryCoordinationDispatchLedger.java");
        assertNoFlatListPartitioning(
                "src/main/java/blue/coordination/engine/api/"
                        + "CoordinationDispatchPlan.java");
    }

    private static void assertNoFlatListPartitioning(String source)
            throws IOException {
        Path path = Paths.get(source);
        String text = new String(
                Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertFalse(text.contains("subList("), source);
    }

    private static void assertDirectCursorPageHandoff(String source)
            throws IOException {
        String text = new String(
                Files.readAllBytes(Paths.get(source)),
                StandardCharsets.UTF_8);
        int start = text.indexOf("private String existingOrFreeze(");
        int end = text.indexOf("private static void requireEvent(", start);
        assertTrue(start >= 0 && end > start,
                "could not locate target-freeze implementation");
        String targetFreeze = text.substring(start, end);
        assertFalse(targetFreeze.contains("new ArrayList"), source);
        assertFalse(targetFreeze.contains(".add("), source);
        assertFalse(targetFreeze.contains(".addAll("), source);
        assertFalse(targetFreeze.contains(".candidates("), source);
        assertFalse(targetFreeze.contains(".targets()"), source);
    }

    private static IndexedSessionCandidates target(int ordinal) {
        String session = session(ordinal);
        return new IndexedSessionCandidates(
                DocumentSessionId.of(session),
                Collections.singletonList("/counter"),
                1,
                0L,
                "root-before-" + session,
                "subscriptions-" + session);
    }

    private static String session(int ordinal) {
        String decimal = Integer.toString(ordinal);
        StringBuilder result = new StringBuilder("root-");
        for (int padding = decimal.length(); padding < 5; padding++) {
            result.append('0');
        }
        return result.append(decimal).toString();
    }

    private static int ordinal(DocumentSessionId sessionId) {
        return Integer.parseInt(sessionId.value().substring("root-".length()));
    }

    private static final class LazyRecordingIndex
            implements CoordinationSubscriptionIndex {
        private final int rootCount;
        private int queryCount;
        private int maximumRequestedPage;
        private int maximumReturnedPage;
        private int maximumCursorTargets;

        private LazyRecordingIndex(int rootCount) {
            this.rootCount = rootCount;
        }

        @Override
        public void replaceSession(ManagedDocumentSnapshot snapshot) { }

        @Override
        public void removeSession(DocumentSessionId sessionId) { }

        @Override
        public CoordinationTargetCursor openCandidates(
                List<String> exactEventSubscriptionKeys,
                String sourceChannel,
                ExternalOrderKey eventOrderKey) {
            queryCount++;
            return new CoordinationTargetCursor() {
                private int nextOrdinal;
                private IndexedSessionCandidates mergeHead;
                private boolean closed;

                @Override
                public List<IndexedSessionCandidates> nextPage(
                        int maximumRoots) {
                    if (closed || maximumRoots <= 0) {
                        throw new IllegalStateException("invalid cursor use");
                    }
                    maximumRequestedPage = Math.max(
                            maximumRequestedPage, maximumRoots);
                    java.util.ArrayList<IndexedSessionCandidates> page =
                            new java.util.ArrayList<IndexedSessionCandidates>(
                                    maximumRoots);
                    if (mergeHead == null && nextOrdinal < rootCount) {
                        mergeHead = target(nextOrdinal);
                        nextOrdinal++;
                    }
                    while (page.size() < maximumRoots
                            && mergeHead != null) {
                        page.add(mergeHead);
                        mergeHead = nextOrdinal < rootCount
                                ? target(nextOrdinal) : null;
                        if (mergeHead != null) nextOrdinal++;
                        maximumCursorTargets = Math.max(
                                maximumCursorTargets,
                                page.size() + (mergeHead == null ? 0 : 1));
                    }
                    maximumReturnedPage = Math.max(
                            maximumReturnedPage, page.size());
                    return new LedgerTraversalOnlyPage(page);
                }

                @Override
                public boolean exhausted() {
                    return mergeHead == null && nextOrdinal >= rootCount;
                }

                @Override
                public long generation() { return 7L; }

                @Override
                public void close() {
                    closed = true;
                    mergeHead = null;
                }
            };
        }

        @Override
        public List<IndexedSessionCandidates> candidates(
                List<String> exactEventSubscriptionKeys,
                String sourceChannel,
                ExternalOrderKey eventOrderKey) {
            throw new AssertionError(
                    "fan-out must use the bounded target cursor");
        }
    }

    /**
     * Fails if dispatcher code traverses a cursor page instead of handing it
     * directly to the frozen-plan store. Size checks remain permitted.
     */
    private static final class LedgerTraversalOnlyPage
            extends AbstractList<IndexedSessionCandidates> {
        private static final String LEDGER_CLASS =
                InMemoryCoordinationDispatchLedger.class.getName();
        private final List<IndexedSessionCandidates> delegate;

        private LedgerTraversalOnlyPage(
                List<IndexedSessionCandidates> delegate) {
            this.delegate = Collections.unmodifiableList(delegate);
        }

        @Override
        public IndexedSessionCandidates get(int index) {
            requireLedgerTraversal();
            return delegate.get(index);
        }

        @Override
        public int size() { return delegate.size(); }

        @Override
        public Iterator<IndexedSessionCandidates> iterator() {
            requireLedgerTraversal();
            return delegate.iterator();
        }

        @Override
        public Object[] toArray() {
            requireLedgerTraversal();
            return delegate.toArray();
        }

        @Override
        public <T> T[] toArray(T[] values) {
            requireLedgerTraversal();
            return delegate.toArray(values);
        }

        private static void requireLedgerTraversal() {
            for (StackTraceElement frame
                    : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().equals(LEDGER_CLASS)
                        || frame.getClassName().startsWith(
                                LEDGER_CLASS + "$")) {
                    return;
                }
            }
            throw new AssertionError(
                    "cursor page was traversed outside the plan store");
        }
    }

    private static final class LightweightExecutor
            implements CoordinationIndexedDeliveryExecutor {
        private final int[] attempts;
        private final int failOnceAt;
        private int deliveryCalls;

        private LightweightExecutor(int rootCount, int failOnceAt) {
            this.attempts = new int[rootCount];
            this.failOnceAt = failOnceAt;
        }

        @Override
        public CoordinationCommittedDelivery deliver(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            int targetOrdinal = ordinal(target.sessionId());
            attempts[targetOrdinal]++;
            deliveryCalls++;
            if (targetOrdinal == failOnceAt
                    && attempts[targetOrdinal] == 1) {
                throw new IllegalStateException("injected bounded retry");
            }
            return new CoordinationCommittedDelivery(
                    event.eventBlueId(),
                    target.sessionId(),
                    target.plannedEpoch(),
                    target.plannedRootBlueId(),
                    target.plannedEpoch() + 1L,
                    "root-after-" + target.sessionId().value(),
                    event.eventBlueId() + "->" + target.sessionId().value(),
                    Collections.<String>emptyList());
        }
    }
}
