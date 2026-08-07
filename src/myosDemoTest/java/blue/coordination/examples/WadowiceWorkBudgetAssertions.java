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
    }

    private static void assertHostEntryWork(MyOsMeasuredWork work) {
        assertEquals(0L, work.sourceParses());
        assertEquals(0L, work.documentInitializations());
        assertEquals(1L, work.eventPreparations(),
                "prepare one canonical event");
        assertEquals(1L, work.eventSplits(), "split the event graph once");
        assertEquals(1L, work.routeIndexProbes(),
                "query the cross-session index once");
        assertEquals(1L, work.fanoutPages(),
                "current targets fit one bounded page");
    }

    private static void assertNoRetryOrConflict(MyOsMeasuredWork work) {
        assertEquals(0L, work.engine().alreadyCommitted());
        assertEquals(0L, work.engine().conflicts());
    }
}
