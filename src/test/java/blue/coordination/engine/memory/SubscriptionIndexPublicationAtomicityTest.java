package blue.coordination.engine.memory;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DeliveryPlanningMode;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.ProcessRequest;
import blue.coordination.engine.spi.CoordinationProcessingBundleLoader;
import blue.coordination.engine.spi.CoordinationTargetCursor;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationSubscriptionOccurrence;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.coordination.processor.RepositoryIndependentCoordinationTestRuntime;
import blue.coordination.processor.RepositoryIndependentCoordinationTypes;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SubscriptionIndexPublicationAtomicityTest {

    private static final String CHANNEL_KEY = "timeline";

    @Test
    void shouldBlockAuthoritativeCursorUntilSessionAndRoutesArePublished()
            throws Exception {
        AtomicReference<Harness> harnessReference =
                new AtomicReference<Harness>();
        AtomicReference<Observation> observation =
                new AtomicReference<Observation>();
        AtomicReference<Throwable> readerFailure =
                new AtomicReference<Throwable>();
        AtomicReference<Thread> readerThread =
                new AtomicReference<Thread>();
        AtomicBoolean observedBlockedReader = new AtomicBoolean();
        CountDownLatch readerAttemptedCombinedRead = new CountDownLatch(1);

        InMemorySessionIndexPublisher.PublicationHook hook = snapshot -> {
            Harness harness = Objects.requireNonNull(
                    harnessReference.get(), "harness");
            assertEquals(
                    snapshot.currentRootBlueId(),
                    harness.sessionStore.findSession(snapshot.sessionId())
                            .get().currentRootBlueId());
            assertTrue(harness.subscriptionIndex.snapshot().rows().isEmpty(),
                    "hook must run before derived route publication");
            RouteQuery query = RouteQuery.from(snapshot);
            Thread reader = new Thread(() -> {
                readerAttemptedCombinedRead.countDown();
                try {
                    List<IndexedSessionCandidates> candidates =
                            new ArrayList<IndexedSessionCandidates>();
                    try (CoordinationTargetCursor cursor = harness.publisher
                            .openAuthoritativeCandidates(
                                    query.subscriptionKeys,
                                    query.sourceChannel,
                                    order(20L, "reader"))) {
                        while (!cursor.exhausted()) {
                            candidates.addAll(cursor.nextPage(16));
                        }
                    }
                    ManagedDocumentSnapshot session = harness.sessionStore
                            .findSession(snapshot.sessionId()).orElse(null);
                    observation.set(new Observation(session, candidates));
                } catch (Throwable failure) {
                    readerFailure.set(failure);
                }
            }, "subscription-index-atomicity-reader");
            reader.setDaemon(true);
            readerThread.set(reader);
            reader.start();
            assertTrue(await(readerAttemptedCombinedRead),
                    "reader did not reach the combined publication read");
            awaitBlocked(reader);
            observedBlockedReader.set(true);
            assertNull(observation.get(),
                    "reader crossed the locked publication boundary");
            assertNull(readerFailure.get());
        };

        try (Harness harness = Harness.open(hook)) {
            harnessReference.set(harness);
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "atomic-publication-session");

            DocumentAdmissionResult admitted =
                    harness.publisher.admitAndPublish(
                            DocumentRegistration.openOrCreate(
                                    sessionId,
                                    harness.initializedRoot(),
                                    order(10L, "admission")));

            Thread reader = Objects.requireNonNull(
                    readerThread.get(), "reader thread");
            reader.join(TimeUnit.SECONDS.toMillis(5L));
            assertFalse(reader.isAlive(),
                    "reader remained blocked after publication completed");
            assertTrue(admitted.succeeded());
            assertTrue(observedBlockedReader.get());
            assertNull(readerFailure.get());

            Observation exact = Objects.requireNonNull(
                    observation.get(), "reader observation");
            ManagedDocumentSnapshot authoritative = admitted.session().get();
            assertEquals(authoritative.currentRootBlueId(),
                    exact.session.currentRootBlueId());
            assertEquals(1, exact.candidates.size());
            IndexedSessionCandidates route = exact.candidates.get(0);
            assertEquals(sessionId, route.sessionId());
            assertEquals(authoritative.currentEpoch(), route.plannedEpoch());
            assertEquals(authoritative.currentRootBlueId(),
                    route.plannedRootBlueId());
            assertEquals(authoritative.subscriptions().digest(),
                    route.subscriptionSnapshotIdentity());
        }
    }

    @Test
    void shouldRebuildCanonicalRowsFromAuthoritativeSessionsExactly() {
        try (Harness harness = Harness.open(snapshot -> { })) {
            Node root = harness.initializedRoot();
            List<String> sessionValues = Arrays.asList(
                    "session/\uE000",
                    "session/\uD83D\uDE00",
                    "session/a");
            long sequence = 1L;
            for (String value : sessionValues) {
                harness.publisher.admitAndPublish(
                        DocumentRegistration.openOrCreate(
                                DocumentSessionId.of(value),
                                root,
                                order(sequence++, value)));
            }

            List<ManagedDocumentSnapshot> authoritative =
                    new ArrayList<ManagedDocumentSnapshot>(
                            harness.sessionStore.sessions());
            harness.subscriptionIndex.replaceSession(authoritative.get(0));
            InMemoryCoordinationSubscriptionIndexSnapshot live =
                    harness.subscriptionIndex.snapshot();

            Collections.reverse(authoritative);
            InMemoryCoordinationSubscriptionIndex rebuilt =
                    new InMemoryCoordinationSubscriptionIndex();
            rebuilt.rebuildFromAuthoritativeSessions(authoritative);
            InMemoryCoordinationSubscriptionIndexSnapshot restored =
                    rebuilt.snapshot();

            assertNotEquals(live.generation(), restored.generation(),
                    "content identity must not depend on publication history");
            assertEquals(live.rows(), restored.rows());
            assertEquals(live.digest(), restored.digest());
            assertTrue(restored.digest().startsWith("sha256:"));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> restored.rows().clear());

            InMemoryCoordinationSubscriptionIndexSnapshot beforeFailure =
                    rebuilt.snapshot();
            List<ManagedDocumentSnapshot> duplicate = Arrays.asList(
                    authoritative.get(0), authoritative.get(0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> rebuilt.rebuildFromAuthoritativeSessions(duplicate));
            InMemoryCoordinationSubscriptionIndexSnapshot afterFailure =
                    rebuilt.snapshot();
            assertEquals(beforeFailure.generation(),
                    afterFailure.generation());
            assertEquals(beforeFailure.rows(), afterFailure.rows());
            assertEquals(beforeFailure.digest(), afterFailure.digest());
        }
    }

    @Test
    void shouldNeverRepublishAHistoricalRouteSnapshotOnExactCommitRetry() {
        try (Harness harness = Harness.open(snapshot -> { })) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "already-committed-route-session");
            harness.publisher.admitAndPublish(
                    DocumentRegistration.openOrCreate(
                            sessionId,
                            harness.initializedMutatingRoot(),
                            order(10L, "admission")));

            CoordinationTransition first = harness.transition(
                    sessionId,
                    0L,
                    20L,
                    "first");
            DemoTransition firstCommit = harness.publisher.commitAndPublish(
                    first);
            assertEquals(CommitStatus.COMMITTED,
                    firstCommit.commitOutcome().status());

            CoordinationTransition second = harness.transition(
                    sessionId,
                    1L,
                    30L,
                    "second");
            DemoTransition secondCommit = harness.publisher.commitAndPublish(
                    second);
            assertEquals(CommitStatus.COMMITTED,
                    secondCommit.commitOutcome().status());
            ManagedDocumentSnapshot current = harness.engine.session(sessionId);
            assertEquals(2L, current.currentEpoch());

            DemoTransition retried = harness.publisher.commitAndPublish(first);

            assertEquals(CommitStatus.ALREADY_COMMITTED,
                    retried.commitOutcome().status());
            ManagedDocumentSnapshot stillCurrent = harness.engine.session(
                    sessionId);
            assertEquals(current.currentEpoch(), stillCurrent.currentEpoch());
            assertEquals(current.currentRootBlueId(),
                    stillCurrent.currentRootBlueId());
            RouteQuery query = RouteQuery.from(stillCurrent);
            List<IndexedSessionCandidates> candidates =
                    harness.subscriptionIndex.candidates(
                            query.subscriptionKeys,
                            query.sourceChannel,
                            order(40L, "query"));
            assertEquals(1, candidates.size());
            IndexedSessionCandidates route = candidates.get(0);
            assertEquals(stillCurrent.currentEpoch(), route.plannedEpoch());
            assertEquals(stillCurrent.currentRootBlueId(),
                    route.plannedRootBlueId());
            assertEquals(stillCurrent.subscriptions().digest(),
                    route.subscriptionSnapshotIdentity());
        }
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5L, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting reader", failure);
        }
    }

    private static void awaitBlocked(Thread reader) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (reader.getState() != Thread.State.BLOCKED
                && reader.isAlive()
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertEquals(Thread.State.BLOCKED, reader.getState(),
                "reader did not block on the session publication monitor");
    }

    private static ExternalOrderKey order(long sequence, String label) {
        return ExternalOrderKey.of(Arrays.<Object>asList(sequence, label));
    }

    private static final class RouteQuery {
        private final List<String> subscriptionKeys;
        private final String sourceChannel;

        private RouteQuery(
                List<String> subscriptionKeys,
                String sourceChannel) {
            this.subscriptionKeys = subscriptionKeys;
            this.sourceChannel = sourceChannel;
        }

        private static RouteQuery from(ManagedDocumentSnapshot snapshot) {
            List<String> keys = new ArrayList<String>();
            String source = null;
            for (CoordinationSubscriptionOccurrence occurrence
                    : snapshot.subscriptions().occurrences()) {
                keys.addAll(occurrence.subscriptionKeys());
                if (source == null) {
                    source = occurrence.channelKey();
                }
            }
            assertFalse(keys.isEmpty(),
                    "the admitted Root must publish at least one route key");
            assertNotNull(source);
            return new RouteQuery(
                    Collections.unmodifiableList(keys), source);
        }
    }

    private static final class Observation {
        private final ManagedDocumentSnapshot session;
        private final List<IndexedSessionCandidates> candidates;

        private Observation(
                ManagedDocumentSnapshot session,
                List<IndexedSessionCandidates> candidates) {
            this.session = Objects.requireNonNull(session, "session");
            this.candidates = Objects.requireNonNull(
                    candidates, "candidates");
        }
    }

    private static final class Harness implements AutoCloseable {
        private final RepositoryIndependentCoordinationTestRuntime runtime;
        private final InMemoryCoordinationFragmentStore fragmentStore;
        private final InMemoryCoordinationSessionStore sessionStore;
        private final InMemoryCoordinationSubscriptionIndex subscriptionIndex;
        private final CoordinationProcessingEngine engine;
        private final InMemorySessionIndexPublisher publisher;

        private Harness(
                InMemorySessionIndexPublisher.PublicationHook hook) {
            runtime = RepositoryIndependentCoordinationTestRuntime.open();
            fragmentStore = new InMemoryCoordinationFragmentStore(
                    CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID);
            runtime.addNodeProvider(fragmentStore);
            sessionStore = new InMemoryCoordinationSessionStore();
            subscriptionIndex = new InMemoryCoordinationSubscriptionIndex();
            CoordinationProcessingBundleLoader loader =
                    new InMemoryCoordinationProcessingBundleLoader(
                            fragmentStore,
                            runtime.platformProcessor()
                                    .administration()
                                    .runtimeAccess()
                                    .languageRuntime()
                                    .getNodeProvider());
            engine = CoordinationProcessingEngine.builder()
                    .contracts(runtime.contracts())
                    .documentProcessor(runtime.platformProcessor())
                    .fragmentStore(fragmentStore)
                    .sessionStore(sessionStore)
                    .bundleLoader(loader)
                    .providerEvidenceDomain(
                            "test:subscription-index-publication")
                    .build();
            publisher = new InMemorySessionIndexPublisher(
                    engine, sessionStore, subscriptionIndex, hook);
        }

        private static Harness open(
                InMemorySessionIndexPublisher.PublicationHook hook) {
            return new Harness(hook);
        }

        private Node initializedRoot() {
            return initialize(authoredRoot());
        }

        private Node initializedMutatingRoot() {
            return initialize(authoredMutatingRoot());
        }

        private Node initialize(Node authored) {
            DocumentProcessingResult initialized = runtime.initializeDocument(
                    authored);
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    initialized.status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            initialized));
            return initialized.document();
        }

        private CoordinationTransition transition(
                DocumentSessionId sessionId,
                long expectedEpoch,
                long sequence,
                String message) {
            Node event = RepositoryIndependentCoordinationTypes.timelineEntry(
                    "timeline-a",
                    "actor-a",
                    BigInteger.valueOf(sequence),
                    RepositoryIndependentCoordinationTypes.chatMessage(
                            message));
            ProcessRequest request = new ProcessRequest(
                    sessionId,
                    expectedEpoch,
                    event,
                    order(sequence, message),
                    DeliveryPlanningMode.CURRENT_ROOT_COMPATIBILITY,
                    Collections.<String>emptyList(),
                    PrefetchPolicy.BALANCED,
                    true);
            CoordinationTransition transition = engine.execute(
                    engine.plan(request));
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    transition.status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            transition.platformResult().processResult()));
            return transition;
        }

        private static Node authoredRoot() {
            Map<String, Node> contracts = new LinkedHashMap<String, Node>();
            contracts.put(
                    CHANNEL_KEY,
                    RepositoryIndependentCoordinationTypes.timelineChannel(
                            "timeline-a", "actor-a"));
            return new Node()
                    .name("Subscription-index publication Root")
                    .properties("contracts", new Node().properties(contracts));
        }

        private static Node authoredMutatingRoot() {
            Map<String, Node> contracts = new LinkedHashMap<String, Node>();
            contracts.put(
                    CHANNEL_KEY,
                    RepositoryIndependentCoordinationTypes.timelineChannel(
                            "timeline-a", "actor-a"));
            contracts.put(
                    "workflow",
                    RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                            CHANNEL_KEY,
                            RepositoryIndependentCoordinationTypes
                                    .updateDocumentStep(
                                            "/counter",
                                            new Node().value(7))));
            return new Node()
                    .name("Mutable subscription-index publication Root")
                    .properties("counter", new Node().value(0))
                    .properties("contracts", new Node().properties(contracts));
        }

        @Override
        public void close() {
            engine.close();
            runtime.close();
        }
    }

}
