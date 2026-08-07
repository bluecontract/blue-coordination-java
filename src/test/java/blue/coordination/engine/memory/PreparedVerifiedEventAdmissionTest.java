package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationEventAdmissionCompiler;
import blue.coordination.engine.api.CoordinationVerifiedEventAdmission;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-key rebasing proofs for prepared immutable event-store deltas. */
final class PreparedVerifiedEventAdmissionTest {

    @Test
    void shouldPublishSameKeyPreparedAdmissionsIdempotently() {
        CoordinationVerifiedEventAdmission admission = admission(1L);
        InMemoryCoordinationFragmentStore store = store();
        InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                StoredCoordinationEvent> first = stage(store, admission, 1L);
        InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                StoredCoordinationEvent> same = stage(store, admission, 1L);

        store.publishPreparedVerifiedEventAdmission(first.prepared());
        store.publishPreparedVerifiedEventAdmission(same.prepared());

        assertEquals(admission.fragments().size(),
                store.physicalFragmentCount());
        assertEquals(1, store.inventoryCount());
        assertEquals(admission.inventory().toMap(),
                store.requireInventory(
                        admission.inventory().inventoryIdentity()).toMap());
    }

    @Test
    void shouldNotMakeADifferentPreparedEventStale() {
        CoordinationVerifiedEventAdmission firstAdmission = admission(1L);
        CoordinationVerifiedEventAdmission secondAdmission = admission(2L);
        InMemoryCoordinationFragmentStore store = store();
        InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                StoredCoordinationEvent> first =
                stage(store, firstAdmission, 1L);
        InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                StoredCoordinationEvent> second =
                stage(store, secondAdmission, 2L);
        Set<String> expectedFragments = new LinkedHashSet<String>();
        expectedFragments.addAll(firstAdmission.orderedFragmentBlueIds());
        expectedFragments.addAll(secondAdmission.orderedFragmentBlueIds());

        store.publishPreparedVerifiedEventAdmission(first.prepared());
        store.publishPreparedVerifiedEventAdmission(second.prepared());

        assertEquals(expectedFragments.size(),
                store.physicalFragmentCount());
        assertEquals(2, store.inventoryCount());
    }

    @Test
    void shouldNotHoldTheStoreMonitorAcrossConcurrentCallerPreparation()
            throws Exception {
        CoordinationVerifiedEventAdmission firstAdmission = admission(1L);
        CoordinationVerifiedEventAdmission secondAdmission = admission(2L);
        InMemoryCoordinationFragmentStore store = store();
        CountDownLatch enteredCallerWork = new CountDownLatch(2);
        CountDownLatch releaseCallerWork = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                StoredCoordinationEvent>> first = executor.submit(() ->
                stageAfterBarrier(
                        store,
                        firstAdmission,
                        1L,
                        enteredCallerWork,
                        releaseCallerWork));
        Future<InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                StoredCoordinationEvent>> second = executor.submit(() ->
                stageAfterBarrier(
                        store,
                        secondAdmission,
                        2L,
                        enteredCallerWork,
                        releaseCallerWork));
        try {
            boolean bothEntered = enteredCallerWork.await(
                    2L, TimeUnit.SECONDS);
            releaseCallerWork.countDown();
            assertTrue(bothEntered,
                    "event compilation must remain outside the store lock");
            InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                    StoredCoordinationEvent> stagedFirst = first.get(
                    5L, TimeUnit.SECONDS);
            InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
                    StoredCoordinationEvent> stagedSecond = second.get(
                    5L, TimeUnit.SECONDS);
            store.publishPreparedVerifiedEventAdmission(
                    stagedFirst.prepared());
            store.publishPreparedVerifiedEventAdmission(
                    stagedSecond.prepared());
            assertEquals(2, store.inventoryCount());
        } finally {
            releaseCallerWork.countDown();
            first.cancel(true);
            second.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(5L, TimeUnit.SECONDS);
        }
    }

    private static InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
            StoredCoordinationEvent> stageAfterBarrier(
                    InMemoryCoordinationFragmentStore store,
                    CoordinationVerifiedEventAdmission admission,
                    long sequence,
                    CountDownLatch entered,
                    CountDownLatch release) {
        return store.stageVerifiedEventAdmission(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while staging test admission",
                        interrupted);
            }
            store.admitVerifiedEvent(admission);
            return admission.storedEvent(
                    ExternalOrderKey.of(Arrays.<Object>asList(
                            sequence, admission.key().eventBlueId())));
        });
    }

    private static InMemoryCoordinationFragmentStore.StagedVerifiedEvent<
            StoredCoordinationEvent> stage(
                    InMemoryCoordinationFragmentStore store,
                    CoordinationVerifiedEventAdmission admission,
                    long sequence) {
        StoredCoordinationEvent event = admission.storedEvent(
                ExternalOrderKey.of(Arrays.<Object>asList(
                        sequence, admission.key().eventBlueId())));
        return store.stageVerifiedEventAdmission(() -> {
            store.admitVerifiedEvent(admission);
            return event;
        });
    }

    private static CoordinationVerifiedEventAdmission admission(
            long sequence) {
        Node event = new Node().properties(
                "type", new Node().value("prepared-event"),
                "sequence", new Node().value(sequence));
        String blueId = DirectBlueIdCalculator.calculateBlueId(event);
        return compiler().compile(blueId, event);
    }

    private static CoordinationEventAdmissionCompiler compiler() {
        return new CoordinationEventAdmissionCompiler(
                "prepared-admission-test-environment",
                "prepared-admission-test-language",
                "prepared-admission-test-provider",
                CoordinationDocumentSplitter.forEventSplitting(),
                4,
                64,
                new CoordinationEventAdmissionMetrics());
    }

    private static InMemoryCoordinationFragmentStore store() {
        return new InMemoryCoordinationFragmentStore(
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID);
    }
}
