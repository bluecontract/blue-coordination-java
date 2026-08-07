package blue.coordination.examples.support;

import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class MyOsAppendFastPathTest {

    @Test
    void shouldKeepPrimingCacheOnlyAndReuseItsCanonicalSplitOnAppend() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "append-fast-path", "cache-only-prime")) {
            MyOsDemoTimeline timeline = demo.timeline(
                    "examples/append-fast-path/cache-only/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = increment(1);
            int fragmentsBefore = demo.physicalFragmentCount();
            CoordinationEventAdmissionMetrics.Snapshot before =
                    demo.eventAdmissionMetrics();

            // when
            timeline.prime(operation);

            assertEquals(0, demo.authoredEntries().size());
            assertEquals(0, demo.journalEntryCount());
            assertEquals(0, demo.storedEventInventoryCount());
            assertEquals(0, demo.canonicalStoredEventCount());
            assertEquals(fragmentsBefore, demo.physicalFragmentCount());
            CoordinationEventAdmissionMetrics.Snapshot primed =
                    demo.eventAdmissionMetrics().minus(before);
            assertEquals(1, primed.fullEventSplits());
            assertEquals(1, primed.templateCompilations());
            assertEquals(0, primed.admittedFragments());
            assertEquals(0, primed.nodeMaterializations());

            MyOsDemoEntry appended = demo.append(timeline, operation);

            assertEquals(1, demo.authoredEntries().size());
            assertEquals(1, demo.journalEntryCount());
            assertEquals(1, demo.storedEventInventoryCount());
            assertEquals(1, demo.canonicalStoredEventCount());
            CoordinationEventAdmissionMetrics.Snapshot actual =
                    demo.eventAdmissionMetrics().minus(before);

            // then
            assertEquals(1, actual.fullEventSplits(),
                    "append must reuse the primed canonical split");
            assertEquals(1, actual.templateCompilations());
            assertTrue(actual.templateHits() >= 1L);
            assertTrue(actual.admittedFragments() > 0L);
            assertEquals(0, actual.winnerReadBacks());
            assertEquals(appended.blueId(),
                    demo.authoredEntries().get(0).blueId());
        }
    }

    @Test
    void shouldKeepPreparedShapeIdentitiesDistinctAcrossTimelinePositions() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "append-fast-path", "distinct-positions")) {
            MyOsDemoTimeline timeline = demo.timeline(
                    "examples/append-fast-path/distinct/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = increment(1);
            timeline.prime(operation);

            // when
            MyOsDemoEntry first = demo.append(timeline, operation);
            timeline.prime(operation);
            MyOsDemoEntry second = demo.append(timeline, operation);

            // then
            assertNotEquals(first.blueId(), second.blueId());
            assertTrue(second.timestampMicros() > first.timestampMicros());
            assertEquals(2, demo.journalEntryCount());
            assertEquals(2, demo.canonicalStoredEventCount());
        }
    }

    @Test
    void shouldReuseCanonicalFragmentEvidenceForStableSubgraphs() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "append-fast-path", "fragment-evidence-sharing")) {
            MyOsDemoTimeline timeline = demo.timeline(
                    "examples/append-fast-path/evidence/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = increment(1);
            timeline.prime(operation);
            demo.append(timeline, operation);
            CoordinationEventAdmissionMetrics.Snapshot before =
                    demo.eventAdmissionMetrics();

            // when
            timeline.prime(operation);
            demo.append(timeline, operation);

            // then
            CoordinationEventAdmissionMetrics.Snapshot delta =
                    demo.eventAdmissionMetrics().minus(before);
            assertTrue(delta.fragmentEvidenceHits() > 0L,
                    "unchanged type/actor/request fragments must be shared");
            assertTrue(delta.fragmentEvidenceMisses()
                            < delta.fragmentEvidenceHits(),
                    "only the dynamic path spine should need new evidence");
        }
    }

    @Test
    @Tag("performance")
    void shouldKeepWarmAppendP95BelowOneHundredMilliseconds() {
        assumeTrue(Boolean.getBoolean("coordination.performance.gates"));
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "append-fast-path", "p95")) {
            MyOsDemoTimeline timeline = demo.timeline(
                    "examples/append-fast-path/p95/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = increment(1);
            timeline.prime(operation);
            demo.append(timeline, operation);
            timeline.prime(operation);

            // when
            List<Long> samples = MyOsLatencyProbe.measureNanos(
                    32, () -> demo.append(timeline, operation));
            long p95 = MyOsLatencyProbe.percentile(samples, 0.95d);

            // then
            assertTrue(p95 <= Duration.ofMillis(100).toNanos(),
                    "warm append p95 was "
                            + Duration.ofNanos(p95).toMillis() + " ms");
        }
    }

    @Test
    @Tag("performance")
    void shouldKeepTheFirstPrimedBusinessAppendBelowOneSecond() {
        assumeTrue(Boolean.getBoolean("coordination.performance.gates"));
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "append-fast-path", "cold-budget")) {
            MyOsDemoTimeline timeline = demo.timeline(
                    "examples/append-fast-path/cold/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = increment(1);
            timeline.prime(operation);

            // when
            assertTimeout(
                    Duration.ofSeconds(1),
                    () -> demo.append(timeline, operation));

            // then
            assertEquals(1, demo.journalEntryCount());
        }
    }

    private static MyOsDemoOperation increment(int amount) {
        return MyOsDemoOperation.operation("increment")
                .through("ownerChannel")
                .request("amount: " + amount)
                .build();
    }
}
