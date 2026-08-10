package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An external parent operation cannot mutate a managed child's state. */
final class ManagedChildOwnershipGuardTest {
    @Test
    void rejectedParentMutationRollsBackAndCreatesNoDeliveryReceipt()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline shared = engine.timeline(
                    "examples/root-isolation/shared", "alice");
            engine.start(
                    "root-isolation-parent",
                    resource("examples/clean/root-isolation-parent.yaml"));
            engine.appendAndDispatch(
                    shared,
                    Operation.exact(
                            "attachChild",
                            "sharedChannel",
                            engine.embeddedDocumentRequest(resource(
                                    "examples/clean/root-isolation-child.yaml"))));

            long parentEpoch = engine.session("root-isolation-parent").epoch();
            long childEpoch = engine.session("root-isolation-child").epoch();
            TimelineEntry illegal = engine.append(
                    shared,
                    Operation.yaml(
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
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertTrue(first.getMessage().contains(
                    "attempted to mutate managed child"),
                    first::getMessage);
            assertTrue(retry.getMessage().contains(
                    "attempted to mutate managed child"),
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
                    "layout.externalManagedChildMutationsRejected"));
            assertEquals(2L, work.counter(
                    "process.frozenContractsInvocations"));
        }
    }
}
