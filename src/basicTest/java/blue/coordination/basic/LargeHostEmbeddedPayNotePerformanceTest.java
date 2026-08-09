package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.DispatchResult;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.ExactNodeValue;
import blue.coordination.basic.engine.ExactTimelineEntry;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static blue.coordination.basic.BasicEngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.integer;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static blue.coordination.basic.BasicEngineTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wadowice-shaped vertical slice: a large host with forty unrelated workflows
 * embeds the real large PayNote, then both autonomous documents execute real
 * operations while exact phase timings are recorded.
 */
@Tag("performance")
@Tag("scenario")
final class LargeHostEmbeddedPayNotePerformanceTest {
    @Test
    void largeHostAndRealPayNoteRemainAutonomousFastAndObservable()
            throws Exception {
        String hostYaml = resource("examples/clean/large-order-host.yaml");
        String payNoteYaml = resource("examples/clean/large-paynote.yaml");
        try (BasicTestMetrics report = BasicTestMetrics.start(
                "large-host-embedded-paynote",
                "Large host with autonomous Wadowice PayNote");
             BasicTestMetrics.MeasuredResource<BasicCoordinationEngine> managed =
                     report.manage(
                             "16 close environment",
                             report.measure(
                                     "01 start environment",
                                     BasicCoordinationEngine::create))) {
            BasicCoordinationEngine engine = managed.value();
            Timeline alice = report.measure(
                    "02 add Alice timeline",
                    () -> engine.timeline(
                            "examples/large-order/alice", "alice"));
            Timeline bob = report.measure(
                    "03 add Bob timeline",
                    () -> engine.timeline(
                            "examples/large-order/bob", "bob"));
            Timeline admin = report.measure(
                    "04 add guarantor timeline",
                    () -> engine.timeline(
                            "examples/order/myos-admin", "myos-admin"));
            Timeline david = report.measure(
                    "05 add restaurant provider timeline",
                    () -> engine.timeline("examples/order/david", "david"));
            report.measure(
                    "06 start 60 KB host with 43 workflows",
                    () -> engine.start("large-order-host", hostYaml));
            ExactNodeValue attachRequest = report.measure(
                    "07 retain real PayNote as one whole request value",
                    () -> engine.embeddedDocumentRequest(payNoteYaml));

            TimedDispatch attach = report.measure(
                    "08 attach and initialize autonomous PayNote",
                    () -> timedDispatch(
                            engine,
                            alice,
                            BasicOperation.exact(
                                    "attachPayNote",
                                    "ownerChannel",
                                    attachRequest)));
            TimedDispatch hostCold = report.measure(
                    "09 host operation with embedded PayNote present",
                    () -> timedDispatch(
                            engine,
                            bob,
                            BasicOperation.of(
                                    "touchHost",
                                    "merchantChannel",
                                    "note: first host update")));
            TimedDispatch authorizeFirst = report.measure(
                    "10 PayNote authorize #1 plus parent propagation",
                    () -> timedDispatch(
                            engine,
                            admin,
                            authorization("AUTH-001", 65_000)));
            TimedDispatch authorizeWarm = report.measure(
                    "11 PayNote authorize #2 plus parent propagation",
                    () -> timedDispatch(
                            engine,
                            admin,
                            authorization("AUTH-002", 65_000)));
            TimedDispatch restaurantConfirm = report.measure(
                    "12 PayNote restaurant confirmation plus parent propagation",
                    () -> timedDispatch(
                            engine,
                            david,
                            BasicOperation.of(
                                    "confirmProduct",
                                    "providerChannel",
                                    "confirmationReference: DINNER-001")));
            TimedDispatch hostWarm = report.measure(
                    "13 warm host operation after PayNote changes",
                    () -> timedDispatch(
                            engine,
                            bob,
                            BasicOperation.of(
                                    "touchHost",
                                    "merchantChannel",
                                    "note: second host update")));

            report.measure("14 verify exact state and work shape", () -> {
                assertEquals(2L, integer(
                        engine, "large-paynote", "/authorizationCountState"));
                assertEquals("Authorized", text(
                        engine, "large-paynote", "/authorization/state"));
                assertEquals(2L, integer(
                        engine,
                        "large-order-host",
                        "/observedAuthorizationCount"));
                assertEquals("Authorized", text(
                        engine,
                        "large-order-host",
                        "/payNote/authorization/state"));
                assertEquals(2L, integer(
                        engine, "large-order-host", "/hostRevision"));
                assertEquals(4L, integer(
                        engine,
                        "large-order-host",
                        "/payNoteRevisionCount"));
                assertEquals(Boolean.TRUE, engine.value(
                        "large-paynote",
                        "/productConditions/restaurant/product/confirmed")
                        .getValue());
                assertEquals(Boolean.TRUE, engine.value(
                        "large-order-host",
                        "/payNote/productConditions/restaurant/product/confirmed")
                        .getValue());
                assertEquals(2, engine.session("large-order-host")
                        .layout().physicalObjectCount());
                assertEquals(1, engine.session("large-paynote")
                        .layout().physicalObjectCount());
                assertEquals("large-paynote", engine.embeddedDocuments(
                        "large-order-host").get("/payNote"));

                assertEquals(2L, attach.work().counter(
                        "process.frozenContractsInvocations"));
                assertEquals(1L, hostCold.work().counter(
                        "process.frozenContractsInvocations"));
                assertEquals(2L, authorizeFirst.work().counter(
                        "process.frozenContractsInvocations"));
                assertEquals(2L, authorizeWarm.work().counter(
                        "process.frozenContractsInvocations"));
                assertEquals(2L, restaurantConfirm.work().counter(
                        "process.frozenContractsInvocations"));
                assertEquals(1L, hostWarm.work().counter(
                        "process.frozenContractsInvocations"));
                assertReferenceOnlyFrozenPath(attach);
                assertReferenceOnlyFrozenPath(hostCold);
                assertReferenceOnlyFrozenPath(authorizeFirst);
                assertReferenceOnlyFrozenPath(authorizeWarm);
                assertReferenceOnlyFrozenPath(restaurantConfirm);
                assertReferenceOnlyFrozenPath(hostWarm);
                assertTrue(hostWarm.work().counter(
                        "process.subscriptionIntervalsReused") > 0L);
                assertTrue(authorizeWarm.work().counter(
                        "process.subscriptionIntervalsReused") > 0L);
                assertEquals(0L, hostWarm.work().counter(
                        "layout.catalogCompilations"));
                assertEquals(0L, authorizeWarm.work().counter(
                        "layout.catalogCompilations"));
                assertEquals(0L, hostWarm.work().nanos(
                        "process.reconstructEmbeddedOnlyRoot"));
                assertEquals(0L, authorizeWarm.work().nanos(
                        "process.refreshChangedSubscriptionSurface"));
                assertNoGenericSplitting(attach.work());
                assertNoGenericSplitting(hostCold.work());
                assertNoGenericSplitting(authorizeFirst.work());
                assertNoGenericSplitting(authorizeWarm.work());
                assertNoGenericSplitting(hostWarm.work());

                if (Boolean.getBoolean("basic.strictPerformance")) {
                    assertCoordinationBudget(
                            "warm host operation", hostWarm, 175);
                    assertCoordinationBudget(
                            "warm PayNote + parent propagation",
                            authorizeWarm,
                            250);
                }
            });

            report.measure("15 publish exact phase comparison", () -> {
                printComparison(Map.of(
                        "attach+initialize", attach,
                        "host cold", hostCold,
                        "PayNote auth #1", authorizeFirst,
                        "PayNote auth #2", authorizeWarm,
                        "restaurant confirmation", restaurantConfirm,
                        "host warm", hostWarm));
            });
            addDetail(report, "attach-paynote", "08 attach and initialize autonomous PayNote", attach);
            addDetail(report, "host-cold", "09 host operation with embedded PayNote present", hostCold);
            addDetail(report, "paynote-auth-first", "10 PayNote authorize #1 plus parent propagation", authorizeFirst);
            addDetail(report, "paynote-auth-warm", "11 PayNote authorize #2 plus parent propagation", authorizeWarm);
            addDetail(report, "paynote-restaurant-confirmation", "12 PayNote restaurant confirmation plus parent propagation", restaurantConfirm);
            addDetail(report, "host-warm", "13 warm host operation after PayNote changes", hostWarm);
        }
    }

    private static BasicOperation authorization(String id, long amountMinor) {
        return BasicOperation.of(
                "authorizeAmount",
                "guarantorChannel",
                "authorizationId: " + id + "\n"
                        + "amountMinor: " + amountMinor + "\n"
                        + "currency: PLN\n");
    }

    private static TimedDispatch timedDispatch(
            BasicCoordinationEngine engine,
            Timeline timeline,
            BasicOperation operation) {
        EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
        long appendStarted = System.nanoTime();
        ExactTimelineEntry entry = engine.append(timeline, operation);
        long appendNanos = System.nanoTime() - appendStarted;
        long dispatchStarted = System.nanoTime();
        DispatchResult result = engine.dispatch(entry);
        long dispatchNanos = System.nanoTime() - dispatchStarted;
        return new TimedDispatch(
                appendNanos,
                dispatchNanos,
                result.outcomes().size(),
                delta(before, engine.metricsSnapshot()));
    }

    private static void assertReferenceOnlyFrozenPath(
            TimedDispatch timing) {
        long calls = timing.work().counter(
                "process.frozenContractsInvocations");
        assertEquals(calls, timing.work().counter(
                "process.concreteOwnershipRootInputs"));
        assertEquals(0L, timing.work().counter(
                "process.referenceOnlyRootInputs"));
        assertEquals(calls, timing.work().counter(
                "process.referenceOnlyEventInputs"));
        assertEquals(0L, timing.work().counter(
                "process.concreteSubscriptionProjections"));
        assertEquals(calls, timing.work().counter(
                "process.commitCompanionDeltasApplied"));
    }

    private static void assertCoordinationBudget(
            String label,
            TimedDispatch timing,
            long hostOverheadLimitMillis) {
        assertTrue(timing.hostOverheadMillis() <= hostOverheadLimitMillis,
                label + " added too much Coordination overhead: "
                        + timing.hostOverheadMillis() + " ms; total="
                        + timing.totalMillis() + " ms; frozen="
                        + timing.frozenMillis() + " ms");
    }

    private static void addDetail(
            BasicTestMetrics report,
            String id,
            String parent,
            TimedDispatch timing) {
        report.detail(id, parent)
                .counter("selected autonomous Roots", timing.rootCount())
                .counter("frozen PROCESS calls", timing.work().counter(
                        "process.frozenContractsInvocations"))
                .counter("generic fragments", timing.work().counter(
                        "layout.ordinaryNodeFragments"))
                .counter("catalog compilations", timing.work().counter(
                        "layout.catalogCompilations"))
                .counter("concrete ownership Root inputs", timing.work().counter(
                        "process.concreteOwnershipRootInputs"))
                .counter("reference-only event inputs", timing.work().counter(
                        "process.referenceOnlyEventInputs"))
                .counter("post-PROCESS full projections", timing.work().counter(
                        "process.concreteSubscriptionProjections"))
                .counter("commit companion deltas applied", timing.work().counter(
                        "process.commitCompanionDeltasApplied"))
                .counter("subscription intervals reused", timing.work().counter(
                        "process.subscriptionIntervalsReused"))
                .phase("append exact whole entry", timing.appendNanos())
                .phase("frozen Contracts PROCESS", timing.work().nanos(
                        "process.frozenContractsOnce"))
                .phase("embedded-only structural-sharing layout", timing.work().nanos(
                        "layout.retainEmbeddedOnly"))
                .phase("commit companion delta application", timing.work().nanos(
                        "process.applyCommitCompanionDelta"));
    }

    private static void printComparison(Map<String, TimedDispatch> values) {
        Map<String, TimedDispatch> ordered = new LinkedHashMap<>(values);
        System.out.println();
        System.out.printf("%-28s %10s %12s %12s %10s%n",
                "Operation", "append ms", "dispatch ms", "frozen ms", "host ms");
        ordered.forEach((name, timing) -> System.out.printf(
                "%-28s %10.3f %12.3f %12.3f %10.3f%n",
                name,
                timing.appendMillis(),
                timing.dispatchMillis(),
                timing.frozenMillis(),
                timing.hostOverheadMillis()));
        System.out.println();
    }

    private record TimedDispatch(
            long appendNanos,
            long dispatchNanos,
            int rootCount,
            BasicEngineTestSupport.MetricDelta work) {
        double appendMillis() {
            return appendNanos / 1_000_000.0;
        }

        double dispatchMillis() {
            return dispatchNanos / 1_000_000.0;
        }

        double frozenMillis() {
            return work.millis("process.frozenContractsOnce");
        }

        double layoutMillis() {
            return work.millis("layout.retainEmbeddedOnly");
        }

        double hostOverheadMillis() {
            return Math.max(0.0,
                    dispatchMillis() - frozenMillis() - layoutMillis());
        }

        double totalMillis() {
            return appendMillis() + dispatchMillis();
        }
    }
}
