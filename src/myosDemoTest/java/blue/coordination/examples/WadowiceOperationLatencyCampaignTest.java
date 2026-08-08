package blue.coordination.examples;

import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.examples.scenarios.WadowiceHotelDinnerScenario;
import blue.coordination.examples.scenarios.WadowicePreparedFixture;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoCheckpoint;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsLatencyProbe;
import blue.coordination.examples.support.MyOsMeasuredWork;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in 17-operation p95 campaign with a correctness oracle. */
final class WadowiceOperationLatencyCampaignTest {

    private static final int OPERATION_COUNT = 17;
    private static final long COMPLETE_CAMPAIGN_MAXIMUM_NANOS =
            Duration.ofSeconds(20).toNanos();

    @Test
    @Tag("performance")
    void shouldKeepEveryReportedOperationP95WithinOneSecond() {
        assumeTrue(Boolean.getBoolean("coordination.performance.gates"));
        // given
        int requestedSamples = Integer.getInteger(
                "coordination.performance.operation.samples",
                WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT);
        WadowicePreparedFixture fixture = WadowicePreparedFixture.shared();
        CampaignOutcome correctness = runCampaign(
                fixture, -1, null, new LinkedHashMap<>());
        WadowiceLatencyEvidence evidence =
                WadowiceLatencyEvidence.rootAlignedCampaign(
                "wadowice-all-17-operations",
                "campaign",
                WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT,
                COMPLETE_CAMPAIGN_MAXIMUM_NANOS);
        Map<String, List<Long>> rawByOperation = new LinkedHashMap<>();
        List<Long> rawCampaignTotals = new ArrayList<>();
        List<String> semanticFailures = new ArrayList<>();

        // when
        for (int iteration = 0; iteration < requestedSamples; iteration++) {
            AtomicReference<CampaignOutcome> captured =
                    new AtomicReference<>();
            int measuredIteration = iteration;
            long campaignElapsedNanos = MyOsLatencyProbe.measureNanos(() ->
                    captured.set(runCampaign(
                            fixture,
                            measuredIteration,
                            evidence,
                            rawByOperation)));
            CampaignOutcome measured = Objects.requireNonNull(
                    captured.get(), "measured campaign");
            evidence.addCampaignTotal(iteration, campaignElapsedNanos);
            rawCampaignTotals.add(campaignElapsedNanos);
            if (!correctness.equals(measured)) {
                semanticFailures.add("iteration " + iteration
                        + " differs from the correctness campaign");
                break;
            }
        }
        Map<String, Object> reference = correctnessReference(correctness);
        Path artifact = evidence.write(
                semanticFailures.isEmpty(), reference);
        List<String> latencyFailures = latencyFailures(
                rawByOperation,
                rawCampaignTotals,
                correctness);

        // then
        assertEquals(OPERATION_COUNT, correctness.operations().size());
        assertEquals(OPERATION_COUNT, rawByOperation.size(),
                "the campaign must report all 17 operations; evidence="
                        + artifact);
        assertTrue(requestedSamples
                        >= WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT,
                "the hard campaign requires 100 complete forks; evidence="
                        + artifact);
        assertTrue(semanticFailures.isEmpty(),
                () -> "campaign semantics changed: " + semanticFailures
                        + "; evidence=" + artifact);
        assertTrue(latencyFailures.isEmpty(),
                () -> "root-aligned latency failures=" + latencyFailures
                        + "; evidence=" + artifact);
    }

    private static CampaignOutcome runCampaign(
            WadowicePreparedFixture fixture,
            int iteration,
            WadowiceLatencyEvidence evidence,
            Map<String, List<Long>> rawByOperation) {
        List<OperationSignature> operations = new ArrayList<>();
        Map<String, String> finalStates = new LinkedHashMap<>();
        MyOsDemoCheckpoint outcomeCheckpoint;
        try (WadowiceHotelDinnerScenario source =
                     fixture.beforePayNoteBranch(
                             caseId("source", iteration))) {
            operations.add(observe(
                    "attachPayNoteAsCustomer",
                    iteration,
                    source,
                    () -> {
                        MyOsDemoDispatch dispatch = source.demo().process(
                                source.appendPayNoteEntry());
                        source.requirePayNoteAttachmentObservable(dispatch);
                        return dispatch.deliveries();
                    },
                    evidence,
                    rawByOperation));
            operations.add(observe(
                    "authorizeAmount.50000",
                    iteration,
                    source,
                    () -> source.authorizeDispatch(
                            "wadowice-auth-50000", 50000).deliveries(),
                    evidence,
                    rawByOperation));
            operations.add(observe(
                    "authorizeAmount.80000",
                    iteration,
                    source,
                    () -> source.authorizeDispatch(
                            "wadowice-auth-80000", 80000).deliveries(),
                    evidence,
                    rawByOperation));
            operations.add(observeResult(
                    "createServiceOrders",
                    iteration,
                    source,
                    source::createServiceOrders,
                    evidence,
                    rawByOperation));
            operations.add(observeResult(
                    "attachServiceOrders",
                    iteration,
                    source,
                    source::linkServiceOrders,
                    evidence,
                    rawByOperation));
            operations.add(observeResult(
                    "attachHotelCondition",
                    iteration,
                    source,
                    source::attachHotelCondition,
                    evidence,
                    rawByOperation));
            operations.add(observeResult(
                    "attachRestaurantCondition",
                    iteration,
                    source,
                    source::attachRestaurantCondition,
                    evidence,
                    rawByOperation));
            operations.add(observeResult(
                    "confirmRestaurant",
                    iteration,
                    source,
                    source::confirmRestaurant,
                    evidence,
                    rawByOperation));
            operations.add(observeResult(
                    "confirmHotel",
                    iteration,
                    source,
                    source::confirmHotel,
                    evidence,
                    rawByOperation));
            operations.add(observeResult(
                    "capturePayment",
                    iteration,
                    source,
                    source::capturePayment,
                    evidence,
                    rawByOperation));
            operations.add(observeResult(
                    "completeHotelStay",
                    iteration,
                    source,
                    source::completeHotelStay,
                    evidence,
                    rawByOperation));
            outcomeCheckpoint = source.demo().checkpoint();
            finalStates.put("sharedRestaurantOutcome",
                    outcomeCheckpoint.stateFingerprint());
        }

        try (WadowiceHotelDinnerScenario complete =
                     WadowiceHotelDinnerScenario.fork(
                             outcomeCheckpoint,
                             caseId("complete", iteration))) {
            operations.add(observeResult(
                    "completeRestaurantDinner",
                    iteration,
                    complete,
                    complete::completeRestaurantDinner,
                    evidence,
                    rawByOperation));
            MyOsDemoAssertions.assertValue(
                    complete.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/orderState", "Confirmed");
            MyOsDemoAssertions.assertValue(
                    complete.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/paymentState", "Completed");
            finalStates.put("complete", complete.demo().stateFingerprint());
        }

        try (WadowiceHotelDinnerScenario cancellation =
                     WadowiceHotelDinnerScenario.fork(
                             outcomeCheckpoint,
                             caseId("cancellation", iteration))) {
            operations.add(observeResult(
                    "cancelRestaurantWithinRange",
                    iteration,
                    cancellation,
                    cancellation::cancelRestaurantWithinRange,
                    evidence,
                    rawByOperation));
            operations.add(observeResult(
                    "completeCancellationRefund",
                    iteration,
                    cancellation,
                    cancellation::completeCancellationRefund,
                    evidence,
                    rawByOperation));
            MyOsDemoAssertions.assertValue(
                    cancellation.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/refund/completed", true);
            MyOsDemoAssertions.assertValue(
                    cancellation.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/amount/captured", 92000);
            finalStates.put("cancellation",
                    cancellation.demo().stateFingerprint());
        }

        try (WadowiceHotelDinnerScenario discount =
                     WadowiceHotelDinnerScenario.fork(
                             outcomeCheckpoint,
                             caseId("discount", iteration))) {
            operations.add(observeResult(
                    "completeRestaurantWithDiscount",
                    iteration,
                    discount,
                    discount::completeRestaurantWithDiscount,
                    evidence,
                    rawByOperation));
            operations.add(observeResult(
                    "completeDiscountAdjustment",
                    iteration,
                    discount,
                    discount::completeDiscountAdjustment,
                    evidence,
                    rawByOperation));
            MyOsDemoAssertions.assertValue(
                    discount.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/discountPercent", 10);
            MyOsDemoAssertions.assertValue(
                    discount.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/amount/captured", 126200);
            finalStates.put("discount",
                    discount.demo().stateFingerprint());
        }

        try (WadowiceHotelDinnerScenario declined =
                     WadowiceHotelDinnerScenario.fork(
                             outcomeCheckpoint,
                             caseId("declined", iteration))) {
            String before = declined.demo().stateFingerprint();
            operations.add(observeResult(
                    "declineLateRestaurantCancellation",
                    iteration,
                    declined,
                    declined::declineLateRestaurantCancellation,
                    evidence,
                    rawByOperation));
            MyOsDemoAssertions.assertValue(
                    declined.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/cancelled", false);
            MyOsDemoAssertions.assertValue(
                    declined.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/refund/requested", false);
            finalStates.put("declinedBefore", before);
            finalStates.put("declinedAfter",
                    declined.demo().stateFingerprint());
        }
        return new CampaignOutcome(
                Collections.unmodifiableList(operations),
                Collections.unmodifiableMap(finalStates));
    }

    private static OperationSignature observeResult(
            String operation,
            int iteration,
            WadowiceHotelDinnerScenario scenario,
            Supplier<MyOsDemoResult> invocation,
            WadowiceLatencyEvidence evidence,
            Map<String, List<Long>> rawByOperation) {
        return observe(
                operation,
                iteration,
                scenario,
                () -> Collections.singletonList(invocation.get()),
                evidence,
                rawByOperation);
    }

    private static OperationSignature observe(
            String operation,
            int iteration,
            WadowiceHotelDinnerScenario scenario,
            Supplier<List<MyOsDemoResult>> invocation,
            WadowiceLatencyEvidence evidence,
            Map<String, List<Long>> rawByOperation) {
        MyOsMeasuredWork workBefore = scenario.demo().measuredWork();
        CoordinationEventAdmissionMetrics.Snapshot admissionBefore =
                scenario.demo().eventAdmissionMetrics();
        long coldFallbacksBefore = scenario.demo()
                .subscriptionProjectionColdFallbackCount();
        List<MyOsDemoResult> results;
        long elapsedNanos;
        if (evidence == null) {
            results = invocation.get();
            elapsedNanos = 0L;
        } else {
            scenario.demo().labelNextOperationTimingSample(
                    "campaign:" + operation + ":" + iteration);
            AtomicReference<List<MyOsDemoResult>> captured =
                    new AtomicReference<>();
            elapsedNanos = MyOsLatencyProbe.measureNanos(() ->
                    captured.set(invocation.get()));
            results = Objects.requireNonNull(
                    captured.get(), "operation result");
        }
        MyOsMeasuredWork work = scenario.demo().measuredWork()
                .minus(workBefore);
        CoordinationEventAdmissionMetrics.Snapshot admission =
                scenario.demo().eventAdmissionMetrics()
                        .minus(admissionBefore);
        long coldFallbacks = scenario.demo()
                .subscriptionProjectionColdFallbackCount()
                - coldFallbacksBefore;
        WadowiceLatencyEvidence.OperationObservation observation =
                observation(results, work, admission, coldFallbacks);
        requireExactWork(operation, observation);
        if (evidence != null) {
            evidence.add(operation, iteration, elapsedNanos, observation);
            rawByOperation.computeIfAbsent(
                    operation, ignored -> new ArrayList<>())
                    .add(elapsedNanos);
        }
        return signature(operation, results);
    }

    private static WadowiceLatencyEvidence.OperationObservation observation(
            List<MyOsDemoResult> results,
            MyOsMeasuredWork work,
            CoordinationEventAdmissionMetrics.Snapshot admission,
            long coldFallbacks) {
        long gas = results.stream()
                .mapToLong(result -> result.delivery().transition()
                        .platformResult().processResult().totalGas())
                .sum();
        int outbox = results.stream()
                .mapToInt(result -> result.delivery().transition()
                        .platformResult().processResult().events().size())
                .sum();
        long fallbackReads = results.stream()
                .mapToLong(result -> result.delivery().transition()
                        .locality().fallbackReadCount())
                .sum();
        long forbiddenReads = results.stream()
                .mapToLong(result -> result.delivery().transition()
                        .locality().forbiddenReadCount())
                .sum();
        return new WadowiceLatencyEvidence.OperationObservation(
                results.size(),
                gas,
                outbox,
                fallbackReads,
                forbiddenReads,
                coldFallbacks,
                work,
                null,
                admission);
    }

    private static void requireExactWork(
            String operation,
            WadowiceLatencyEvidence.OperationObservation observation) {
        long roots = observation.affectedRootCount();
        if (observation.work().engine().processCompletions() != roots
                || observation.work().engine().committed() != roots
                || observation.work().engine().commitAttempts() != roots) {
            throw new IllegalStateException(operation
                    + " did not execute and commit exactly one PROCESS per "
                    + "affected Root");
        }
        if (observation.localityFallbackReadCount() != 0L
                || observation.forbiddenReadCount() != 0L
                || observation.subscriptionProjectionColdFallbackCount()
                != 0L
                || observation.work().projection()
                        .coldProjectionFallbacks() != 0L
                || observation.work().projection()
                        .fullProjectorFallbacks() != 0L
                || observation.work().projection()
                        .catalogFallbacks() != 0L
                || observation.work().fragmentTransition()
                        .typedFallbackCount() != 0L
                || observation.work().fragmentTransition()
                        .fullBlueprintAttempts() != 0L
                || observation.work().fragmentTransition()
                        .fullResultClones() != 0L
                || observation.work().fragmentTransition()
                        .fullRootMaterializations() != 0L) {
            throw new IllegalStateException(
                    operation + " used a forbidden cold fallback");
        }
    }

    private static OperationSignature signature(
            String operation,
            List<MyOsDemoResult> results) {
        Map<String, RootSignature> roots = new LinkedHashMap<>();
        for (MyOsDemoResult result : results) {
            var transition = result.delivery().transition();
            String session = transition.plan().session().sessionId().value();
            roots.put(session, new RootSignature(
                    transition.afterRootBlueId(),
                    transition.afterEpoch(),
                    transition.platformResult().processResult().totalGas(),
                    transition.platformResult().processResult()
                            .events().size(),
                    transition.commitPlan().transitionIdentity()));
        }
        return new OperationSignature(
                operation, Collections.unmodifiableMap(roots));
    }

    private static Map<String, Object> correctnessReference(
            CampaignOutcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operationCount", outcome.operations().size());
        result.put("operationNames", outcome.operations().stream()
                .map(OperationSignature::operation)
                .toList());
        result.put("rootCounts", outcome.operations().stream()
                .collect(LinkedHashMap::new,
                        (values, operation) -> values.put(
                                operation.operation(),
                                operation.roots().size()),
                        LinkedHashMap::putAll));
        Map<String, Long> budget = new LinkedHashMap<>();
        budget.put("oneRootP95Nanos",
                WadowiceLatencyEvidence.ONE_ROOT_P95_NANOS);
        budget.put("oneRootMaximumNanos",
                WadowiceLatencyEvidence.ONE_ROOT_MAXIMUM_NANOS);
        budget.put("twoRootP95Nanos",
                WadowiceLatencyEvidence.TWO_ROOT_P95_NANOS);
        budget.put("twoRootMaximumNanos",
                WadowiceLatencyEvidence.TWO_ROOT_MAXIMUM_NANOS);
        budget.put("completeCampaignMaximumNanos",
                COMPLETE_CAMPAIGN_MAXIMUM_NANOS);
        result.put("latencyBudget", budget);
        result.put("finalStateFingerprints", outcome.finalStates());
        return result;
    }

    private static List<String> latencyFailures(
            Map<String, List<Long>> rawByOperation,
            List<Long> rawCampaignTotals,
            CampaignOutcome correctness) {
        Map<String, Integer> rootCounts = new LinkedHashMap<>();
        for (OperationSignature operation : correctness.operations()) {
            rootCounts.put(operation.operation(), operation.roots().size());
        }
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry
                : rawByOperation.entrySet()) {
            long p95 = MyOsLatencyProbe.percentile(
                    entry.getValue(), 0.95d);
            long maximum = Collections.max(entry.getValue());
            Integer roots = rootCounts.get(entry.getKey());
            long p95Budget = roots != null && roots.intValue() == 1
                    ? WadowiceLatencyEvidence.ONE_ROOT_P95_NANOS
                    : roots != null && roots.intValue() == 2
                    ? WadowiceLatencyEvidence.TWO_ROOT_P95_NANOS
                    : -1L;
            long maximumBudget = roots != null && roots.intValue() == 1
                    ? WadowiceLatencyEvidence.ONE_ROOT_MAXIMUM_NANOS
                    : roots != null && roots.intValue() == 2
                    ? WadowiceLatencyEvidence.TWO_ROOT_MAXIMUM_NANOS
                    : -1L;
            if (p95Budget < 0L || maximumBudget < 0L) {
                failures.add(entry.getKey()
                        + " has unsupported affectedRootCount=" + roots);
            } else {
                if (p95 > p95Budget) {
                    failures.add(entry.getKey() + " p95=" + p95
                            + "ns > " + p95Budget + "ns");
                }
                if (maximum > maximumBudget) {
                    failures.add(entry.getKey() + " max=" + maximum
                            + "ns > " + maximumBudget + "ns");
                }
            }
            if (entry.getValue().size()
                    < WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT) {
                failures.add(entry.getKey() + " has only "
                        + entry.getValue().size() + " samples");
            }
        }
        if (rawCampaignTotals.size()
                < WadowiceLatencyEvidence.REQUIRED_SAMPLE_COUNT) {
            failures.add("complete campaign has only "
                    + rawCampaignTotals.size() + " samples");
        } else {
            long maximumCampaign = Collections.max(rawCampaignTotals);
            if (maximumCampaign > COMPLETE_CAMPAIGN_MAXIMUM_NANOS) {
                failures.add("complete campaign max=" + maximumCampaign
                        + "ns > " + COMPLETE_CAMPAIGN_MAXIMUM_NANOS + "ns");
            }
        }
        return Collections.unmodifiableList(failures);
    }

    private static String caseId(String branch, int iteration) {
        return "latency-campaign-" + branch + "-"
                + (iteration < 0 ? "correctness" : iteration);
    }

    private record RootSignature(
            String rootBlueId,
            long epoch,
            long gas,
            int outboxEventCount,
            String transitionIdentity) {
    }

    private record OperationSignature(
            String operation,
            Map<String, RootSignature> roots) {

        private OperationSignature {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(roots, "roots");
        }
    }

    private record CampaignOutcome(
            List<OperationSignature> operations,
            Map<String, String> finalStates) {

        private CampaignOutcome {
            Objects.requireNonNull(operations, "operations");
            Objects.requireNonNull(finalStates, "finalStates");
        }
    }
}
