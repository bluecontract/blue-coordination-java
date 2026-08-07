package blue.coordination.examples;

import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.examples.scenarios.WadowiceHotelDinnerScenario;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsLatencyProbe;
import blue.coordination.examples.support.MyOsMeasuredWork;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in release gate from public append through both observable Root commits. */
final class WadowiceAttachPayNoteLatencyTest {

    @Test
    @Tag("performance")
    void shouldKeepFirstSeenExactEventP95WithinOneSecond() {
        assumeTrue(Boolean.getBoolean("coordination.performance.gates"));
        // given
        int requestedSamples = Integer.getInteger(
                "coordination.performance.paynote.samples",
                WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT);
        WadowiceLatencyEvidence evidence = new WadowiceLatencyEvidence(
                "wadowice-attach-paynote-first-seen",
                "firstSeenExactEvent",
                WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT);
        List<Long> rawSamples = new ArrayList<>();
        List<String> semanticFailures = new ArrayList<>();

        // when
        for (int iteration = 0; iteration < requestedSamples; iteration++) {
            try (WadowiceHotelDinnerScenario scenario =
                         WadowiceHotelDinnerScenario.create(
                                 "paynote-latency-first-seen-" + iteration)) {
                MyOsMeasuredWork workBefore =
                        scenario.demo().measuredWork();
                CoordinationEventAdmissionMetrics.Snapshot admissionBefore =
                        scenario.demo().eventAdmissionMetrics();
                long coldFallbacksBefore = scenario.demo()
                        .subscriptionProjectionColdFallbackCount();
                scenario.demo().labelNextOperationTimingSample(
                        "firstSeenExactEvent");
                MyOsDemoDispatch[] observed = new MyOsDemoDispatch[1];
                long elapsedNanos = MyOsLatencyProbe.measureNanos(() -> {
                    MyOsDemoEntry entry = scenario.appendPayNoteEntry();
                    observed[0] = scenario.demo().process(entry);
                    scenario.requirePayNoteAttachmentObservable(observed[0]);
                });
                MyOsMeasuredWork work = scenario.demo().measuredWork()
                        .minus(workBefore);
                CoordinationEventAdmissionMetrics.Snapshot admission =
                        scenario.demo().eventAdmissionMetrics()
                                .minus(admissionBefore);
                long coldFallbacks = scenario.demo()
                        .subscriptionProjectionColdFallbackCount()
                        - coldFallbacksBefore;
                WadowiceLatencyEvidence.OperationObservation observation =
                        observation(
                                observed[0],
                                work,
                                admission,
                                coldFallbacks);
                evidence.add(
                        "attachPayNoteAsCustomer",
                        iteration,
                        elapsedNanos,
                        observation);
                rawSamples.add(elapsedNanos);
                collectFirstSeenFailures(
                        iteration, observation, semanticFailures);
            }
        }
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("affectedRootCount", 2);
        reference.put("processCallCount", 2);
        reference.put("fullEventSplits", 1);
        reference.put("projectionColdFallbacks", 0);
        Path artifact = evidence.write(
                semanticFailures.isEmpty(), reference);
        long p95 = MyOsLatencyProbe.percentile(rawSamples, 0.95d);

        // then
        assertTrue(requestedSamples
                        >= WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT,
                "The release gate requires 100 first-seen forks; evidence="
                        + artifact);
        assertTrue(semanticFailures.isEmpty(),
                () -> "PayNote campaign semantic failures="
                        + semanticFailures + "; evidence=" + artifact);
        assertTrue(p95 <= Duration.ofSeconds(1).toNanos(),
                "firstSeenExactEvent p95 took " + p95
                        + " ns across " + rawSamples.size()
                        + " raw samples; evidence=" + artifact);
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
            long coldFallbacksBefore = scenario.demo()
                    .subscriptionProjectionColdFallbackCount();
            scenario.demo().labelNextOperationTimingSample("primed");
            MyOsDemoDispatch[] observed = new MyOsDemoDispatch[1];

            // when
            long elapsedNanos = MyOsLatencyProbe.measureNanos(() -> {
                MyOsDemoEntry entry = scenario.appendPayNoteEntry();
                observed[0] = scenario.demo().process(entry);
                scenario.requirePayNoteAttachmentObservable(observed[0]);
            });

            // then
            assertEquals(splitsBefore,
                    scenario.demo().eventAdmissionMetrics()
                            .fullEventSplits());
            assertEquals(coldFallbacksBefore,
                    scenario.demo()
                            .subscriptionProjectionColdFallbackCount());
            assertTrue(elapsedNanos > 0L,
                    "primed diagnostic must publish a raw positive sample");
        }
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
        return new WadowiceLatencyEvidence.OperationObservation(
                dispatch.deliveries().size(),
                gas,
                outbox,
                fallbackReads,
                forbiddenReads,
                coldFallbacks,
                work,
                admission);
    }

    private static void collectFirstSeenFailures(
            int iteration,
            WadowiceLatencyEvidence.OperationObservation observation,
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
        if (observation.eventAdmission().fullEventSplits() != 1L
                || observation.work().eventSplits() != 1L) {
            failures.add(iteration + ": exact event was not first-seen");
        }
        if (observation.localityFallbackReadCount() != 0L
                || observation.forbiddenReadCount() != 0L
                || observation.subscriptionProjectionColdFallbackCount()
                != 0L) {
            failures.add(iteration + ": fallback work was observed");
        }
    }
}
