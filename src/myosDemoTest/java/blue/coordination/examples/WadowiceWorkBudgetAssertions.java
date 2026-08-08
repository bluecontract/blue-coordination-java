package blue.coordination.examples;

import blue.coordination.examples.support.MyOsMeasuredWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact budgets sourced from live host, engine-observer and store counters. */
final class WadowiceWorkBudgetAssertions {

    private WadowiceWorkBudgetAssertions() { }

    static void assertOneRootProcess(MyOsMeasuredWork work) {
        assertHostEntryWork(work);
        assertEquals(1L, work.engine().plans(), "one plan per Root");
        assertEquals(1L, work.engine().bundleLoads(), "one bundle load");
        assertEquals(1L, work.engine().bundleBatches(), "one engine batch");
        assertEquals(1L, work.engine().processCompletions(), "one PROCESS");
        assertEquals(1L, work.engine().commitAttempts(), "one CAS attempt");
        assertEquals(1L, work.engine().committed(), "one committed Root");
        assertNoRetryOrConflict(work);
        assertEquals(0L, work.storeSingleReads(),
                "indexed hot path must not perform single fragment reads");
        assertTrue(work.storeBatchReads() <= 2L,
                () -> "expected at most two store batches but saw "
                        + work.storeBatchReads());
        assertIncrementalProjectionAndTransition(work, 1L);
    }

    static void assertTwoRootFanout(MyOsMeasuredWork work) {
        assertHostEntryWork(work);
        assertEquals(2L, work.engine().plans(), "one plan per Root");
        assertEquals(2L, work.engine().bundleLoads(),
                "one bundle load per Root");
        assertEquals(2L, work.engine().bundleBatches(),
                "one engine batch per Root");
        assertEquals(2L, work.engine().processCompletions(),
                "one PROCESS per Root");
        assertEquals(2L, work.engine().commitAttempts(),
                "one CAS attempt per Root");
        assertEquals(2L, work.engine().committed(),
                "both Roots committed");
        assertNoRetryOrConflict(work);
        assertEquals(0L, work.storeSingleReads(),
                "fan-out hot path must use batch fragment reads");
        assertTrue(work.storeBatchReads() <= 4L,
                () -> "expected at most four store batches but saw "
                        + work.storeBatchReads());
        assertIncrementalProjectionAndTransition(work, 2L);
    }

    private static void assertHostEntryWork(MyOsMeasuredWork work) {
        assertEquals(0L, work.sourceParses());
        assertEquals(0L, work.documentInitializations());
        assertEquals(1L, work.eventPreparations(),
                "prepare one canonical event");
        assertEquals(0L, work.eventSplits(),
                "cached-shape exact admission must not split the event");
        assertEquals(1L, work.routeIndexProbes(),
                "query the cross-session index once");
        assertEquals(1L, work.fanoutPages(),
                "current targets fit one bounded page");
    }

    private static void assertNoRetryOrConflict(MyOsMeasuredWork work) {
        assertEquals(0L, work.engine().alreadyCommitted());
        assertEquals(0L, work.engine().conflicts());
    }

    private static void assertIncrementalProjectionAndTransition(
            MyOsMeasuredWork work, long affectedRoots) {
        assertEquals(affectedRoots,
                work.projection().deltaProjectionUpdates(),
                "one delta projection per committed Root");
        assertEquals(0L, work.projection().coldProjectionFallbacks());
        assertEquals(0L, work.projection().fullProjectorFallbacks());
        assertEquals(0L, work.projection().catalogFallbacks());
        assertEquals(0L, work.projection().unrelatedOccurrences(),
                "unrelated occurrences must not be visited or refreshed");
        assertEquals(0L, work.projection().snapshotSerializations(),
                "the persistent snapshot identity must not serialize all "
                        + "occurrences");
        assertEquals(0L, work.projection().snapshotSerializedOccurrences());

        assertEquals(affectedRoots, work.fragmentTransition().deltaHits(),
                "one verified frontier transition per committed Root");
        assertEquals(0L,
                work.fragmentTransition().typedFallbackCount());
        assertEquals(0L,
                work.fragmentTransition().fullBlueprintAttempts());
        assertEquals(0L, work.fragmentTransition().fullResultClones());
        assertEquals(0L,
                work.fragmentTransition().fullRootMaterializations());
        assertEquals(0L,
                work.fragmentTransition().retainedIndexFullScans());
        assertTrue(
                work.fragmentTransition().unchangedFragmentShareRatio()
                        >= 0.90d,
                () -> "expected at least 90% unchanged fragment sharing but "
                        + "saw "
                        + work.fragmentTransition()
                                .unchangedFragmentShareRatio());
    }
}
