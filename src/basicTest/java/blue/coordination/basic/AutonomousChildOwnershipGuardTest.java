package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.ExactTimelineEntry;
import blue.coordination.basic.engine.SessionStatus;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.integer;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An external parent operation cannot mutate an autonomous child's state. */
final class AutonomousChildOwnershipGuardTest {
    @Test
    void rejectedParentMutationRollsBackAndCreatesNoDeliveryReceipt()
            throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            Timeline shared = engine.timeline(
                    "examples/root-isolation/shared", "alice");
            engine.start(
                    "root-isolation-parent",
                    resource("examples/clean/root-isolation-parent.yaml"));
            engine.appendAndDispatch(
                    shared,
                    BasicOperation.exact(
                            "attachChild",
                            "sharedChannel",
                            engine.embeddedDocumentRequest(resource(
                                    "examples/clean/root-isolation-child.yaml"))));

            long parentEpoch = engine.session("root-isolation-parent").epoch();
            long childEpoch = engine.session("root-isolation-child").epoch();
            ExactTimelineEntry illegal = engine.append(
                    shared,
                    BasicOperation.of(
                            "mutateChildIllegally",
                            "sharedChannel",
                            "amount: 7"));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            IllegalStateException first = assertThrows(
                    IllegalStateException.class,
                    () -> engine.dispatch(illegal));
            IllegalStateException retry = assertThrows(
                    IllegalStateException.class,
                    () -> engine.dispatch(illegal));
            BasicEngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertTrue(first.getMessage().contains(
                    "attempted to mutate autonomous child"),
                    first::getMessage);
            assertTrue(retry.getMessage().contains(
                    "attempted to mutate autonomous child"),
                    retry::getMessage);
            assertEquals(parentEpoch,
                    engine.session("root-isolation-parent").epoch());
            assertEquals(childEpoch,
                    engine.session("root-isolation-child").epoch());
            assertEquals(SessionStatus.READY,
                    engine.session("root-isolation-parent").status());
            assertEquals(0L, integer(
                    engine, "root-isolation-child", "/childCount"));
            assertEquals(0L, integer(
                    engine, "root-isolation-parent", "/child/childCount"));
            assertEquals(2L, work.counter(
                    "layout.externalAutonomousChildMutationsRejected"));
            assertEquals(2L, work.counter(
                    "process.frozenContractsInvocations"));
            assertEquals(2L, work.counter("transactionRetries"));
        }
    }
}
