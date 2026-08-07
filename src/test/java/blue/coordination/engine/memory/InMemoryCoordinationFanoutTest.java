package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationDeliveryStatus;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.engine.spi.CoordinationSubscriptionIndex;
import blue.coordination.engine.spi.CoordinationTargetCursor;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InMemoryCoordinationFanoutTest {

    @Test
    void shouldDeliverEveryMatchingRootWithoutSpecificityFiltering() {
        // given
        RecordingIndex index = new RecordingIndex(Arrays.asList(
                target("root-one", "/owner"),
                target("root-two", "/deep", "/deeper"),
                target("root-three", "/owner")));
        RecordingExecutor executor = new RecordingExecutor();
        InMemoryCoordinationFanout fanout = new InMemoryCoordinationFanout(
                index,
                new InMemoryCoordinationDispatchLedger(),
                executor);

        // when
        CoordinationDispatchSnapshot result = fanout.dispatch(
                event("event-all"),
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                2,
                PrefetchPolicy.MINIMUM_ROUND_TRIPS);

        // then
        assertTrue(result.complete());
        assertEquals(Arrays.asList("root-one", "root-three", "root-two"),
                executor.deliveredSessions);
        assertEquals(3, result.succeededCount());
        assertEquals(1, index.queryCount);
    }

    @Test
    void shouldResumeAfterPartialFailureWithoutRequeryOrRedelivery() {
        // given
        RecordingIndex index = new RecordingIndex(Arrays.asList(
                target("root-a", "/a"),
                target("root-b", "/b"),
                target("root-c", "/c")));
        RecordingExecutor executor = new RecordingExecutor();
        executor.failOnceAt = "root-b";
        InMemoryCoordinationFanout fanout = new InMemoryCoordinationFanout(
                index,
                new InMemoryCoordinationDispatchLedger(),
                executor);
        StoredCoordinationEvent event = event("event-resume");

        // when
        CoordinationFanoutException first = assertThrows(
                CoordinationFanoutException.class,
                () -> fanout.dispatch(
                        event,
                        Collections.singletonList("actor:alice"),
                        "ownerChannel",
                        2,
                        PrefetchPolicy.MINIMUM_ROUND_TRIPS));
        index.candidates = Collections.emptyList();
        CoordinationDispatchSnapshot resumed = fanout.dispatch(
                event,
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                2,
                PrefetchPolicy.MINIMUM_ROUND_TRIPS);

        // then
        assertEquals("root-b", first.failedSessionId().value());
        assertFalse(first.dispatch().complete());
        assertEquals(CoordinationDeliveryStatus.COMMITTED,
                first.dispatch().receipts().get(0).status());
        assertEquals(1, first.dispatch().receipts().get(0).attemptCount());
        assertEquals("root-after-root-a",
                first.dispatch().receipts().get(0)
                        .resultingRootBlueId().orElseThrow(
                                () -> new AssertionError(
                                        "committed receipt has no Root")));
        assertEquals(Collections.singletonList("outbox-root-a"),
                first.dispatch().receipts().get(0)
                        .committedOutboxEventBlueIds());
        assertEquals(CoordinationDeliveryStatus.FAILED,
                first.dispatch().receipts().get(1).status());
        assertEquals(1, first.dispatch().receipts().get(1).attemptCount());
        assertFalse(first.dispatch().receipts().get(1)
                .resultingRootBlueId().isPresent());
        assertEquals(CoordinationDeliveryStatus.PENDING,
                first.dispatch().receipts().get(2).status());
        assertEquals(0, first.dispatch().receipts().get(2).attemptCount());
        assertTrue(resumed.complete());
        assertEquals(1, index.queryCount,
                "A retry uses its frozen target plan");
        assertEquals(1, executor.attempts.get("root-a").intValue(),
                "A successful Root is never delivered twice");
        assertEquals(2, executor.attempts.get("root-b").intValue());
        assertEquals(1, executor.attempts.get("root-c").intValue());
        Map<String, Integer> expectedApplications = new LinkedHashMap<>();
        expectedApplications.put("root-a", 1);
        expectedApplications.put("root-b", 1);
        expectedApplications.put("root-c", 1);
        assertEquals(expectedApplications, executor.committedApplications);
        assertEquals(Arrays.asList(1, 2, 1),
                resumed.receipts().stream()
                        .map(receipt -> receipt.attemptCount())
                        .collect(java.util.stream.Collectors.toList()));
        assertEquals(Arrays.asList(
                        "root-after-root-a",
                        "root-after-root-b",
                        "root-after-root-c"),
                resumed.receipts().stream()
                        .map(receipt -> receipt.resultingRootBlueId()
                                .orElseThrow(() -> new AssertionError(
                                        "committed receipt has no Root")))
                        .collect(java.util.stream.Collectors.toList()));
        assertEquals(Arrays.asList(
                        Collections.singletonList("outbox-root-a"),
                        Collections.singletonList("outbox-root-b"),
                        Collections.singletonList("outbox-root-c")),
                resumed.receipts().stream()
                        .map(receipt -> receipt.committedOutboxEventBlueIds())
                        .collect(java.util.stream.Collectors.toList()));
        assertEquals(Arrays.asList("root-a", "root-b", "root-b", "root-c"),
                executor.deliveredSessions);
    }

    @Test
    void shouldRecoverWhenSessionCommittedBeforeHostReceiptWasWritten() {
        // given
        RecordingIndex index = new RecordingIndex(
                Collections.singletonList(target("root-a", "/a")));
        Map<String, CoordinationCommittedDelivery> authoritative =
                new LinkedHashMap<>();
        RecordingExecutor executor = new RecordingExecutor() {
            @Override
            public CoordinationCommittedDelivery deliver(
                    StoredCoordinationEvent event,
                    IndexedSessionCandidates target,
                    PrefetchPolicy prefetchPolicy) {
                CoordinationCommittedDelivery committed = super.deliver(
                        event, target, prefetchPolicy);
                authoritative.put(target.sessionId().value(), committed);
                throw new IllegalStateException(
                        "injected failure after authoritative commit");
            }
        };
        InMemoryCoordinationFanout fanout = new InMemoryCoordinationFanout(
                index,
                new InMemoryCoordinationDispatchLedger(),
                executor,
                (event, sessionId) -> Optional.ofNullable(
                        authoritative.get(sessionId.value())));
        StoredCoordinationEvent event = event("event-commit-gap");
        CoordinationDispatchSnapshot recovered = fanout.dispatch(
                event,
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                1,
                PrefetchPolicy.MINIMUM_ROUND_TRIPS);

        assertTrue(recovered.complete());
        assertEquals(1, executor.attempts.get("root-a").intValue(),
                "Authoritative commit recovery must not replay PROCESS");
        assertEquals(1, index.queryCount);
    }

    @Test
    void shouldSerializeConcurrentRetriesForTheSameDispatch()
            throws Exception {
        RecordingIndex index = new RecordingIndex(
                Collections.singletonList(target("root-a", "/a")));
        CountDownLatch firstDeliveryEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstDelivery = new CountDownLatch(1);
        AtomicInteger deliveryCalls = new AtomicInteger();
        CoordinationIndexedDeliveryExecutor executor =
                (event, target, prefetchPolicy) -> {
                    deliveryCalls.incrementAndGet();
                    firstDeliveryEntered.countDown();
                    try {
                        if (!releaseFirstDelivery.await(
                                5L, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "test delivery was not released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return new CoordinationCommittedDelivery(
                            event.eventBlueId(),
                            target.sessionId(),
                            target.plannedEpoch(),
                            target.plannedRootBlueId(),
                            target.plannedEpoch() + 1L,
                            "root-after-" + target.sessionId().value(),
                            event.eventBlueId() + "->"
                                    + target.sessionId().value(),
                            Collections.<String>emptyList());
                };
        InMemoryCoordinationFanout fanout = new InMemoryCoordinationFanout(
                index,
                new InMemoryCoordinationDispatchLedger(),
                executor);
        StoredCoordinationEvent event = event("event-concurrent");
        ExecutorService callers = Executors.newFixedThreadPool(2);

        try {
            Future<CoordinationDispatchSnapshot> first = callers.submit(
                    () -> fanout.dispatch(
                            event,
                            Collections.singletonList("actor:alice"),
                            "ownerChannel",
                            1,
                            PrefetchPolicy.MINIMUM_ROUND_TRIPS));
            assertTrue(firstDeliveryEntered.await(5L, TimeUnit.SECONDS));
            Future<CoordinationDispatchSnapshot> concurrent = callers.submit(
                    () -> fanout.dispatch(
                            event,
                            Collections.singletonList("actor:alice"),
                            "ownerChannel",
                            1,
                            PrefetchPolicy.MINIMUM_ROUND_TRIPS));

            assertThrows(TimeoutException.class,
                    () -> concurrent.get(100L, TimeUnit.MILLISECONDS));
            releaseFirstDelivery.countDown();

            assertTrue(first.get(5L, TimeUnit.SECONDS).complete());
            assertTrue(concurrent.get(5L, TimeUnit.SECONDS).complete());
            assertEquals(1, deliveryCalls.get(),
                    "the concurrent retry must skip the committed Root");
            assertEquals(1, index.queryCount,
                    "the concurrent retry must use the sealed plan");
        } finally {
            releaseFirstDelivery.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void shouldFailTheAdmissionWhenPostFailureReconciliationAlsoFails() {
        RecordingIndex index = new RecordingIndex(
                Collections.singletonList(target("root-a", "/a")));
        RecordingExecutor executor = new RecordingExecutor();
        executor.failOnceAt = "root-a";
        AtomicInteger probes = new AtomicInteger();
        InMemoryCoordinationFanout fanout = new InMemoryCoordinationFanout(
                index,
                new InMemoryCoordinationDispatchLedger(),
                executor,
                (event, sessionId) -> {
                    if (probes.incrementAndGet() == 2) {
                        throw new IllegalStateException(
                                "injected reconciliation outage");
                    }
                    return Optional.empty();
                });
        StoredCoordinationEvent event = event("event-probe-failure");

        CoordinationFanoutException failed = assertThrows(
                CoordinationFanoutException.class,
                () -> fanout.dispatch(
                        event,
                        Collections.singletonList("actor:alice"),
                        "ownerChannel",
                        1,
                        PrefetchPolicy.MINIMUM_ROUND_TRIPS));

        assertEquals(CoordinationDeliveryStatus.FAILED,
                failed.dispatch().receipts().get(0).status());
        assertEquals(1, failed.getCause().getSuppressed().length);
        assertTrue(fanout.resume(
                event.eventBlueId(),
                PrefetchPolicy.MINIMUM_ROUND_TRIPS).complete());
        assertEquals(2, executor.attempts.get("root-a").intValue());
    }

    private static StoredCoordinationEvent event(String blueId) {
        return new StoredCoordinationEvent(
                blueId,
                blueId + "-inventory",
                ExternalOrderKey.of(Arrays.<Object>asList(2L, blueId)));
    }

    private static IndexedSessionCandidates target(
            String session,
            String... occurrences) {
        return new IndexedSessionCandidates(
                DocumentSessionId.of(session),
                Arrays.asList(occurrences),
                occurrences.length,
                0L,
                "root-before-" + session,
                "subscriptions-" + session);
    }

    private static final class RecordingIndex
            implements CoordinationSubscriptionIndex {
        private List<IndexedSessionCandidates> candidates;
        private int queryCount;

        private RecordingIndex(List<IndexedSessionCandidates> candidates) {
            this.candidates = new ArrayList<IndexedSessionCandidates>(
                    candidates);
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
            final List<IndexedSessionCandidates> frozen =
                    new ArrayList<IndexedSessionCandidates>(candidates);
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
                    int to = Math.min(
                            frozen.size(), offset + maximumRoots);
                    List<IndexedSessionCandidates> page =
                            new ArrayList<IndexedSessionCandidates>(
                                    to - offset);
                    while (offset < to) {
                        page.add(frozen.get(offset));
                        offset++;
                    }
                    return page;
                }

                @Override
                public boolean exhausted() {
                    return offset >= frozen.size();
                }

                @Override
                public long generation() { return 1L; }

                @Override
                public void close() { closed = true; }
            };
        }

        @Override
        public List<IndexedSessionCandidates> candidates(
                List<String> exactEventSubscriptionKeys,
                String sourceChannel,
                ExternalOrderKey eventOrderKey) {
            List<IndexedSessionCandidates> result =
                    new ArrayList<IndexedSessionCandidates>();
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

    private static class RecordingExecutor
            implements CoordinationIndexedDeliveryExecutor {
        private final List<String> deliveredSessions =
                new ArrayList<String>();
        private final Map<String, Integer> attempts =
                new LinkedHashMap<String, Integer>();
        private final Map<String, Integer> committedApplications =
                new LinkedHashMap<String, Integer>();
        private String failOnceAt;

        @Override
        public CoordinationCommittedDelivery deliver(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            String session = target.sessionId().value();
            deliveredSessions.add(session);
            int attempt = attempts.containsKey(session)
                    ? attempts.get(session).intValue() + 1 : 1;
            attempts.put(session, Integer.valueOf(attempt));
            if (session.equals(failOnceAt) && attempt == 1) {
                throw new IllegalStateException("injected failure");
            }
            committedApplications.put(
                    session,
                    Integer.valueOf(committedApplications.containsKey(session)
                            ? committedApplications.get(session).intValue() + 1
                            : 1));
            return new CoordinationCommittedDelivery(
                    event.eventBlueId(),
                    target.sessionId(),
                    target.plannedEpoch(),
                    target.plannedRootBlueId(),
                    target.plannedEpoch() + 1L,
                    "root-after-" + session,
                    event.eventBlueId() + "->" + session,
                    Collections.singletonList("outbox-" + session));
        }
    }
}
