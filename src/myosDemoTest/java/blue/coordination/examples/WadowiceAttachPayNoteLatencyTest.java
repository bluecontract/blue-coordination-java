package blue.coordination.examples;

import blue.coordination.engine.api.CoordinationEventShapeMetrics;
import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.engine.fastpath.ReferenceCutMetrics;
import blue.coordination.examples.scenarios.WadowiceHotelDinnerScenario;
import blue.coordination.examples.scenarios.WadowicePreparedFixture;
import blue.coordination.examples.support.FirstSeenEventGuard;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsLatencyProbe;
import blue.coordination.examples.support.MyOsMeasuredWork;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in release gate from public append through both observable Root commits. */
final class WadowiceAttachPayNoteLatencyTest {

    private static final int REQUIRED_STABILIZATION_COUNT = 30;
    private static final long TIMESTAMP_OFFSET_STRIDE_MICROS = 10_000L;

    @Test
    @Tag("performance")
    void shouldKeepFirstSeenExactEventP95WithinOneSecond() {
        assumeTrue(Boolean.getBoolean("coordination.performance.gates"));
        // given
        int requestedSamples = Integer.getInteger(
                "coordination.performance.paynote.samples",
                WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT);
        int requestedStabilizationSamples = Integer.getInteger(
                "coordination.performance.paynote.stabilization.samples",
                REQUIRED_STABILIZATION_COUNT);
        WadowicePreparedFixture fixture =
                WadowicePreparedFixture.shared();
        WadowiceLatencyEvidence evidence = new WadowiceLatencyEvidence(
                "wadowice-attach-paynote-first-seen",
                "firstSeenExactEvent",
                WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT,
                WadowiceLatencyEvidence.MAXIMUM_NANOS);
        List<Long> rawSamples = new ArrayList<>();
        List<String> semanticFailures = new ArrayList<>();
        FirstSeenEventGuard exactEventGuard = new FirstSeenEventGuard();
        FirstSeenEventGuard previousEntryGuard = new FirstSeenEventGuard();
        Set<Long> exactTimestamps = new LinkedHashSet<>();

        // when
        warmPreviousEntryShape(fixture);
        for (int iteration = 0;
                iteration < requestedStabilizationSamples;
                iteration++) {
            runStabilizationFork(
                    fixture,
                    iteration,
                    exactEventGuard,
                    previousEntryGuard,
                    exactTimestamps);
        }
        for (int iteration = 0; iteration < requestedSamples; iteration++) {
            try (WadowiceHotelDinnerScenario scenario =
                         fixture.beforePayNoteBranch(
                                 "paynote-latency-first-seen-" + iteration,
                                 timestampOffset(
                                         requestedStabilizationSamples
                                                 + iteration + 1))) {
                MyOsDemoEntry previousEntry =
                        scenario.appendPayNoteCampaignCursor();
                requireUnroutedCursor(scenario, previousEntry);
                previousEntryGuard.requireFirstSeen(
                        previousEntry.blueId());
                MyOsMeasuredWork workBefore =
                        scenario.demo().measuredWork();
                CoordinationEventAdmissionMetrics.Snapshot admissionBefore =
                        scenario.demo().eventAdmissionMetrics();
                CoordinationEventShapeMetrics.Snapshot shapeBefore =
                        scenario.demo().eventShapeMetrics();
                long coldFallbacksBefore = scenario.demo()
                        .subscriptionProjectionColdFallbackCount();
                scenario.demo().labelNextOperationTimingSample(
                        "firstSeenExactEvent");
                MyOsDemoDispatch[] observed = new MyOsDemoDispatch[1];
                MyOsDemoEntry[] measuredEntry = new MyOsDemoEntry[1];
                long elapsedNanos = MyOsLatencyProbe.measureNanos(() -> {
                    measuredEntry[0] = scenario.appendPayNoteEntry();
                    observed[0] = scenario.demo().process(measuredEntry[0]);
                });
                scenario.requirePayNoteAttachmentObservable(observed[0]);
                requireFirstSeenIdentity(
                        exactEventGuard,
                        exactTimestamps,
                        previousEntry,
                        measuredEntry[0]);
                MyOsMeasuredWork work = scenario.demo().measuredWork()
                        .minus(workBefore);
                CoordinationEventAdmissionMetrics.Snapshot admission =
                        scenario.demo().eventAdmissionMetrics()
                                .minus(admissionBefore);
                CoordinationEventShapeMetrics.Snapshot shapeAfter =
                        scenario.demo().eventShapeMetrics();
                long coldFallbacks = scenario.demo()
                        .subscriptionProjectionColdFallbackCount()
                        - coldFallbacksBefore;
                WadowiceLatencyEvidence.OperationObservation observation =
                        observation(
                                observed[0],
                                work,
                                admission,
                                coldFallbacks);
                evidence.addFirstSeen(
                        "attachPayNoteAsCustomer",
                        iteration,
                        elapsedNanos,
                        observation,
                        measuredEntry[0],
                        previousEntry.blueId());
                rawSamples.add(elapsedNanos);
                collectFirstSeenFailures(
                        iteration,
                        observation,
                        shapeBefore,
                        shapeAfter,
                        semanticFailures);
            }
        }
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("affectedRootCount", 2);
        reference.put("processCallCount", 2);
        reference.put("fullEventSplits", 0);
        reference.put("shapeInstancesCompiled", 1);
        reference.put("shapeExactGraphsMaterialized", 1);
        reference.put("projectionColdFallbacks", 0);
        reference.put("stabilizationSampleCount",
                requestedStabilizationSamples);
        reference.put("measuredSampleCount", requestedSamples);
        reference.put("uniquePreviousEntryCount",
                previousEntryGuard.observedCount());
        reference.put("uniqueExactEventCount",
                exactEventGuard.observedCount());
        reference.put("maximumElapsedNanos",
                WadowiceLatencyEvidence.MAXIMUM_NANOS);
        boolean completeFirstSeenProtocol =
                requestedStabilizationSamples
                        >= REQUIRED_STABILIZATION_COUNT
                && requestedSamples
                        >= WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT
                && exactEventGuard.observedCount()
                        == requestedStabilizationSamples + requestedSamples
                && previousEntryGuard.observedCount()
                        == requestedStabilizationSamples + requestedSamples
                && exactTimestamps.size()
                        == requestedStabilizationSamples + requestedSamples;
        Path artifact = evidence.write(
                semanticFailures.isEmpty() && completeFirstSeenProtocol,
                reference);
        long p95 = MyOsLatencyProbe.percentile(rawSamples, 0.95d);
        long maximum = Collections.max(rawSamples);

        // then
        assertTrue(requestedStabilizationSamples
                        >= REQUIRED_STABILIZATION_COUNT,
                "The release gate requires at least 30 stabilization forks; "
                        + "evidence=" + artifact);
        assertTrue(requestedSamples
                        >= WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT,
                "The release gate requires 100 first-seen forks; evidence="
                        + artifact);
        assertEquals(requestedStabilizationSamples + requestedSamples,
                exactEventGuard.observedCount(),
                "every stabilization and measured event must be exact-new");
        assertEquals(requestedStabilizationSamples + requestedSamples,
                previousEntryGuard.observedCount(),
                "every fork must use a unique current previous entry");
        assertEquals(requestedStabilizationSamples + requestedSamples,
                exactTimestamps.size(),
                "every fork must use a unique exact PayNote timestamp");
        assertTrue(semanticFailures.isEmpty(),
                () -> "PayNote campaign semantic failures="
                        + semanticFailures + "; evidence=" + artifact);
        assertTrue(p95 <= Duration.ofSeconds(1).toNanos(),
                "firstSeenExactEvent p95 took " + p95
                        + " ns across " + rawSamples.size()
                        + " raw samples; evidence=" + artifact);
        assertTrue(maximum <= WadowiceLatencyEvidence.MAXIMUM_NANOS,
                "firstSeenExactEvent maximum took " + maximum
                        + " ns across " + rawSamples.size()
                        + " unfiltered raw samples; evidence=" + artifact);
    }

    @Test
    @Tag("performance")
    void shouldPublishAnExplicitlyPrimedDiagnosticWithoutReplacingTheGate() {
        assumeTrue(Boolean.getBoolean("coordination.performance.gates"));
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     WadowiceHotelDinnerScenario.create(
                             "paynote-latency-primed")) {
            scenario.primePayNoteAppend();
            long splitsBefore = scenario.demo().eventAdmissionMetrics()
                    .fullEventSplits();
            CoordinationEventShapeMetrics.Snapshot shapeBefore =
                    scenario.demo().eventShapeMetrics();
            long coldFallbacksBefore = scenario.demo()
                    .subscriptionProjectionColdFallbackCount();
            scenario.demo().labelNextOperationTimingSample("primed");
            MyOsDemoDispatch[] observed = new MyOsDemoDispatch[1];

            // when
            long elapsedNanos = MyOsLatencyProbe.measureNanos(() -> {
                MyOsDemoEntry entry = scenario.appendPayNoteEntry();
                observed[0] = scenario.demo().process(entry);
            });
            scenario.requirePayNoteAttachmentObservable(observed[0]);

            // then
            assertEquals(splitsBefore,
                    scenario.demo().eventAdmissionMetrics()
                            .fullEventSplits());
            assertOneCachedShapeInstance(
                    shapeBefore,
                    scenario.demo().eventShapeMetrics());
            assertEquals(coldFallbacksBefore,
                    scenario.demo()
                            .subscriptionProjectionColdFallbackCount());
            assertTrue(elapsedNanos > 0L,
                    "primed diagnostic must publish a raw positive sample");
        }
    }

    private static void warmPreviousEntryShape(
            WadowicePreparedFixture fixture) {
        try (WadowiceHotelDinnerScenario warmup =
                     fixture.beforePayNoteBranch(
                             "paynote-latency-shape-warmup",
                             timestampOffset(0))) {
            MyOsDemoEntry previousEntry =
                    warmup.appendPayNoteCampaignCursor();
            requireUnroutedCursor(warmup, previousEntry);
            warmup.primePayNoteShape();
        }
    }

    private static void runStabilizationFork(
            WadowicePreparedFixture fixture,
            int iteration,
            FirstSeenEventGuard exactEventGuard,
            FirstSeenEventGuard previousEntryGuard,
            Set<Long> exactTimestamps) {
        try (WadowiceHotelDinnerScenario scenario =
                     fixture.beforePayNoteBranch(
                             "paynote-latency-stabilization-" + iteration,
                             timestampOffset(iteration + 1))) {
            MyOsDemoEntry previousEntry =
                    scenario.appendPayNoteCampaignCursor();
            requireUnroutedCursor(scenario, previousEntry);
            previousEntryGuard.requireFirstSeen(previousEntry.blueId());
            MyOsDemoEntry measuredEntry = scenario.appendPayNoteEntry();
            MyOsDemoDispatch dispatch = scenario.demo().process(measuredEntry);
            scenario.requirePayNoteAttachmentObservable(dispatch);
            requireFirstSeenIdentity(
                    exactEventGuard,
                    exactTimestamps,
                    previousEntry,
                    measuredEntry);
        }
    }

    private static void requireUnroutedCursor(
            WadowiceHotelDinnerScenario scenario,
            MyOsDemoEntry cursor) {
        MyOsDemoDispatch dispatch = scenario.demo().process(cursor);
        if (!dispatch.deliveries().isEmpty()) {
            throw new IllegalStateException(
                    "PayNote campaign cursor unexpectedly targeted Roots: "
                            + dispatch.documentKeys());
        }
    }

    private static void requireFirstSeenIdentity(
            FirstSeenEventGuard exactEventGuard,
            Set<Long> exactTimestamps,
            MyOsDemoEntry previousEntry,
            MyOsDemoEntry measuredEntry) {
        exactEventGuard.requireFirstSeen(measuredEntry.blueId());
        if (!exactTimestamps.add(measuredEntry.timestampMicros())) {
            throw new IllegalStateException(
                    "PayNote campaign reused exact timestamp "
                            + measuredEntry.timestampMicros());
        }
        String actualPrevious = measuredEntry.exactEntry()
                .getAsNode("/prevEntry")
                .getBlueId();
        if (!previousEntry.blueId().equals(actualPrevious)) {
            throw new IllegalStateException(
                    "PayNote event did not bind the current previous entry: "
                            + actualPrevious);
        }
    }

    private static long timestampOffset(int sampleOrdinal) {
        if (sampleOrdinal < 0) {
            throw new IllegalArgumentException(
                    "sampleOrdinal must be non-negative");
        }
        return Math.multiplyExact(
                Math.addExact((long) sampleOrdinal, 1L),
                TIMESTAMP_OFFSET_STRIDE_MICROS);
    }

    private static WadowiceLatencyEvidence.OperationObservation observation(
            MyOsDemoDispatch dispatch,
            MyOsMeasuredWork work,
            CoordinationEventAdmissionMetrics.Snapshot admission,
            long coldFallbacks) {
        long gas = dispatch.deliveries().stream()
                .mapToLong(result -> result.delivery().transition()
                        .platformResult().processResult().totalGas())
                .sum();
        int outbox = dispatch.deliveries().stream()
                .mapToInt(result -> result.delivery().transition()
                        .platformResult().processResult().events().size())
                .sum();
        long fallbackReads = dispatch.deliveries().stream()
                .mapToLong(result -> result.delivery().transition()
                        .locality().fallbackReadCount())
                .sum();
        long forbiddenReads = dispatch.deliveries().stream()
                .mapToLong(result -> result.delivery().transition()
                        .locality().forbiddenReadCount())
                .sum();
        var orderTransition = dispatch
                .require(WadowiceHotelDinnerScenario.ORDER)
                .delivery().transition();
        long orderInventoryFragments = orderTransition.plan()
                .rootInventory().fragmentBlueIds().size();
        WadowiceLatencyEvidence.OrderRootSparseProof orderSparseProof =
                new WadowiceLatencyEvidence.OrderRootSparseProof(
                        WadowiceHotelDinnerScenario.ORDER,
                        orderTransition.plan().session().sessionId().value(),
                        orderTransition.beforeRootBlueId(),
                        orderTransition.plan().rootInventory()
                                .inventoryIdentity(),
                        orderInventoryFragments,
                        work.referenceCuts()
                                .processMaterializedFragments());
        return new WadowiceLatencyEvidence.OperationObservation(
                dispatch.deliveries().size(),
                gas,
                outbox,
                fallbackReads,
                forbiddenReads,
                coldFallbacks,
                work,
                orderSparseProof,
                admission);
    }

    private static void collectFirstSeenFailures(
            int iteration,
            WadowiceLatencyEvidence.OperationObservation observation,
            CoordinationEventShapeMetrics.Snapshot shapeBefore,
            CoordinationEventShapeMetrics.Snapshot shapeAfter,
            List<String> failures) {
        if (observation.affectedRootCount() != 2) {
            failures.add(iteration + ": affectedRoots="
                    + observation.affectedRootCount());
        }
        if (observation.work().engine().processCompletions() != 2L
                || observation.work().engine().committed() != 2L) {
            failures.add(iteration + ": engineWork="
                    + observation.work().engine().processCompletions()
                    + "/" + observation.work().engine().committed());
        }
        if (observation.eventAdmission().fullEventSplits() != 0L
                || observation.work().eventSplits() != 0L) {
            failures.add(iteration
                    + ": cached-shape exact admission performed a full "
                    + "event split");
        }
        if (shapeAfter.templatesCompiled()
                        - shapeBefore.templatesCompiled() != 0L
                || shapeAfter.instancesCompiled()
                        - shapeBefore.instancesCompiled() != 1L
                || shapeAfter.exactGraphsMaterialized()
                        - shapeBefore.exactGraphsMaterialized() != 1L
                || shapeAfter.fullSplitterOracleRuns()
                        - shapeBefore.fullSplitterOracleRuns() != 0L
                || shapeAfter.oracleFailures()
                        - shapeBefore.oracleFailures() != 0L) {
            failures.add(iteration + ": shapeAdmission="
                    + shapeBefore + " -> " + shapeAfter);
        }
        if (observation.localityFallbackReadCount() != 0L
                || observation.forbiddenReadCount() != 0L
                || observation.subscriptionProjectionColdFallbackCount()
                != 0L) {
            failures.add(iteration + ": fallback work was observed");
        }
        if (observation.work().projection().deltaProjectionUpdates() != 2L
                || observation.work().projection()
                        .coldProjectionFallbacks() != 0L
                || observation.work().projection()
                        .fullProjectorFallbacks() != 0L
                || observation.work().projection().catalogFallbacks() != 0L
                || observation.work().projection()
                        .snapshotSerializations() != 0L
                || observation.work().projection()
                        .snapshotSerializedOccurrences() != 0L) {
            failures.add(iteration + ": projection="
                    + observation.work().projection());
        }
        if (observation.work().fragmentTransition().deltaHits() != 2L
                || observation.work().fragmentTransition()
                        .typedFallbackCount() != 0L
                || observation.work().fragmentTransition()
                        .fullBlueprintAttempts() != 0L
                || observation.work().fragmentTransition()
                        .fullResultClones() != 0L
                || observation.work().fragmentTransition()
                        .fullRootMaterializations() != 0L
                || observation.work().fragmentTransition()
                        .retainedIndexFullScans() != 0L
                || observation.work().fragmentTransition()
                        .unchangedFragmentShareRatio() < 0.90d) {
            failures.add(iteration + ": fragmentTransition="
                    + observation.work().fragmentTransition());
        }
        ReferenceCutMetrics.Snapshot sparse =
                observation.work().referenceCuts();
        if (sparse.decisions() != 4L
                || sparse.sparseUses() != 4L
                || sparse.fullRootUses() != 0L
                || sparse.plannedArtifactFallbacks() != 0L
                || sparse.plannedArtifactReuses()
                        + sparse.plannedArtifactNotApplicable() != 2L
                || sparse.processRootSelections() != 2L
                || sparse.cacheHits() < 2L
                || sparse.inventoryCompilations() > 1L
                || sparse.canonicalBatchReads() > 1L
                || sparse.canonicalSingleReads() != 0L
                || sparse.identityFailures() != 0L
                || sparse.processInventoryFragments() <= 0L
                || observation.orderRootSparseProof()
                        .maximumPossibleMaterializationFraction()
                        > 0.20d) {
            failures.add(iteration + ": sparseRoot=" + sparse);
        }
    }

    private static void assertOneCachedShapeInstance(
            CoordinationEventShapeMetrics.Snapshot before,
            CoordinationEventShapeMetrics.Snapshot after) {
        assertEquals(0L,
                after.templatesCompiled() - before.templatesCompiled(),
                "the operation shape must already be cached");
        assertEquals(1L,
                after.instancesCompiled() - before.instancesCompiled(),
                "compile exactly one first-seen event instance");
        assertEquals(1L,
                after.exactGraphsMaterialized()
                        - before.exactGraphsMaterialized(),
                "materialize exactly one exact event graph");
        assertEquals(0L,
                after.fullSplitterOracleRuns()
                        - before.fullSplitterOracleRuns());
        assertEquals(0L,
                after.oracleFailures() - before.oracleFailures());
    }
}
