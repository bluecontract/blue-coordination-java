package blue.coordination.integration;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-engine routing coverage beyond one scalar Timeline Channel. */
final class NonScalarRoutingIntegrationTest {
    private static final String ALICE_TIMELINE =
            "examples/routing/alice";
    private static final String BOB_TIMELINE =
            "examples/routing/bob";
    private static final String CHARLIE_TIMELINE =
            "examples/routing/charlie";
    private static final long T0 = 1_710_000_000_000_000L;

    @Test
    void compositeTimelineRoutesOnlyItsDeclaredMemberSources()
            throws Exception {
        // given
        DocumentId documentId = DocumentId.of("composite-routing-counter");
        String fixture = "examples/clean/composite-routing-counter.yaml";

        // when
        AggregateRoutingEvidence evidence = runAggregateRouting(
                documentId,
                fixture);

        // then
        assertAggregateRouting(evidence);
    }

    @Test
    void allTimelinesRoutesOnlyTheFrozenSameScopeTimelineFamily()
            throws Exception {
        // given
        DocumentId documentId = DocumentId.of("all-timelines-routing-counter");
        String fixture = "examples/clean/all-timelines-routing-counter.yaml";

        // when
        AggregateRoutingEvidence evidence = runAggregateRouting(
                documentId,
                fixture);

        // then
        assertAggregateRouting(evidence);
    }

    @Test
    void fromNowRouteIntervalExcludesBacklogStillInTheGlobalJournal()
            throws Exception {
        // given
        DocumentId counter = DocumentId.of("counter");
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            Timeline alice = engine.registerTimeline(
                    "examples/clean-counter/alice", "alice");
            TimelineEntry oldOne = engine.appendAt(
                    alice, increment(5), T0 + 100L);
            TimelineEntry oldTwo = engine.appendAt(
                    alice, increment(7), T0 + 200L);

            engine.startDocument(
                    counter,
                    resource("examples/clean/counter.yaml"),
                    CoordinationEngine.AdmissionPolicy.FROM_NOW,
                    null);
            TimelineEntry live = engine.appendAt(
                    alice, increment(3), T0 + 300L);

            assertEquals(0, engine.routeTargetCount(oldOne));
            assertEquals(0, engine.routeTargetCount(oldTwo));
            assertEquals(1, engine.routeTargetCount(live));
            long processBefore = engine.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS);

            // when
            ProcessingDrainReceipt receipt = engine.drain();

            // then
            assertEquals(
                    List.of(oldOne.blueId(), oldTwo.blueId(), live.blueId()),
                    entryIds(receipt.processedEntries()));
            assertTrue(receipt.outcomesFor(oldOne.blueId()).isEmpty());
            assertTrue(receipt.outcomesFor(oldTwo.blueId()).isEmpty());
            assertEquals(1, receipt.outcomesFor(live.blueId()).size());
            assertEquals(3L, counter(engine, counter));
            assertEquals(1L, engine.document(counter).epoch());
            assertEquals(
                    List.of(live.blueId()),
                    engine.history(counter).stream()
                            .flatMap(revision -> revision.sourceEntry().stream())
                            .map(TimelineEntry::blueId)
                            .toList());
            assertEquals(processBefore + 1L, engine.metrics().counter(
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));
        }
    }

    private static AggregateRoutingEvidence runAggregateRouting(
            DocumentId documentId,
            String resourcePath) throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            Timeline alice = engine.registerTimeline(
                    ALICE_TIMELINE, "alice");
            Timeline bob = engine.registerTimeline(
                    BOB_TIMELINE, "bob");
            Timeline charlie = engine.registerTimeline(
                    CHARLIE_TIMELINE, "charlie");
            engine.startDocument(documentId, resource(resourcePath));

            TimelineEntry aliceEntry = engine.appendAt(
                    alice, add(2), T0 + 100L);
            TimelineEntry bobEntry = engine.appendAt(
                    bob, add(3), T0 + 200L);
            TimelineEntry unrelated = engine.appendAt(
                    charlie, add(7), T0 + 300L);

            int aliceTargets = engine.routeTargetCount(aliceEntry);
            int bobTargets = engine.routeTargetCount(bobEntry);
            int unrelatedTargets = engine.routeTargetCount(unrelated);
            Set<String> effectiveTimelineIds =
                    engine.effectiveTimelineIds(documentId);

            ProcessingDrainReceipt receipt = engine.drain();

            return new AggregateRoutingEvidence(
                    aliceTargets,
                    bobTargets,
                    unrelatedTargets,
                    effectiveTimelineIds,
                    List.of(
                            aliceEntry.blueId(),
                            bobEntry.blueId(),
                            unrelated.blueId()),
                    entryIds(receipt.processedEntries()),
                    receipt.outcomesFor(aliceEntry.blueId()).size(),
                    receipt.outcomesFor(bobEntry.blueId()).size(),
                    receipt.outcomesFor(unrelated.blueId()).isEmpty(),
                    counter(engine, documentId),
                    engine.document(documentId).epoch());
        }
    }

    private static void assertAggregateRouting(
            AggregateRoutingEvidence evidence) {
        assertEquals(1, evidence.aliceTargets());
        assertEquals(1, evidence.bobTargets());
        assertEquals(0, evidence.unrelatedTargets());
        assertEquals(Set.of(ALICE_TIMELINE, BOB_TIMELINE),
                evidence.effectiveTimelineIds());
        assertEquals(evidence.expectedEntryIds(),
                evidence.processedEntryIds());
        assertEquals(1, evidence.aliceOutcomes());
        assertEquals(1, evidence.bobOutcomes());
        assertTrue(evidence.unrelatedOutcomeEmpty());
        assertEquals(5L, evidence.counter());
        assertEquals(2L, evidence.epoch());
    }

    private record AggregateRoutingEvidence(
            int aliceTargets,
            int bobTargets,
            int unrelatedTargets,
            Set<String> effectiveTimelineIds,
            List<String> expectedEntryIds,
            List<String> processedEntryIds,
            int aliceOutcomes,
            int bobOutcomes,
            boolean unrelatedOutcomeEmpty,
            long counter,
            long epoch) {
        private AggregateRoutingEvidence {
            effectiveTimelineIds = Set.copyOf(effectiveTimelineIds);
            expectedEntryIds = List.copyOf(expectedEntryIds);
            processedEntryIds = List.copyOf(processedEntryIds);
        }
    }

    private static Operation add(long amount) {
        return Operation.yaml(
                "add", "aggregateChannel", "amount: " + amount);
    }

    private static Operation increment(long amount) {
        return Operation.yaml(
                "increment", "aliceChannel", "amount: " + amount);
    }

    private static List<String> entryIds(List<TimelineEntry> entries) {
        return entries.stream().map(TimelineEntry::blueId).toList();
    }

    private static long counter(
            CoordinationEngine engine,
            DocumentId documentId) {
        Object value = engine.document(documentId)
                .valueAt("/counter")
                .copyNode()
                .getValue();
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new AssertionError("Expected numeric /counter, got " + value);
    }
}
