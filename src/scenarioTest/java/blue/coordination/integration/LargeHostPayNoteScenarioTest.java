package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static blue.coordination.integration.EngineTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full retained processor/BEX closure through a managed PayNote. */
@Tag("scenario")
final class LargeHostPayNoteScenarioTest {
    @Test
    void largeHostAndManagedPayNoteCompleteTheWadowiceWorkflow()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            Timeline alice = engine.timeline(
                    "examples/large-order/alice", "alice");
            Timeline bob = engine.timeline(
                    "examples/large-order/bob", "bob");
            Timeline guarantor = engine.timeline(
                    "examples/order/myos-admin", "myos-admin");
            Timeline restaurant = engine.timeline(
                    "examples/order/david", "david");
            engine.start("large-order-host", resource(
                    "examples/clean/large-order-host.yaml"));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            // when
            engine.appendAndDispatch(alice, Operation.exact(
                    "attachPayNote", "ownerChannel",
                    engine.embeddedDocumentRequest(resource(
                            "examples/clean/large-paynote.yaml"))));
            engine.appendAndDispatch(bob, Operation.yaml(
                    "touchHost", "merchantChannel",
                    "note: first host update"));
            engine.appendAndDispatch(guarantor,
                    authorization("RC-AUTH-1", 65_000L));
            engine.appendAndDispatch(guarantor,
                    authorization("RC-AUTH-2", 65_000L));
            engine.appendAndDispatch(restaurant, Operation.yaml(
                    "confirmProduct", "providerChannel",
                    "confirmationReference: RC-DINNER"));
            engine.appendAndDispatch(bob, Operation.yaml(
                    "touchHost", "merchantChannel",
                    "note: second host update"));

            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            // then
            assertEquals(2L, integer(
                    engine, "large-paynote", "/authorizationCountState"));
            assertEquals("Authorized", text(
                    engine, "large-paynote", "/authorization/state"));
            assertEquals(2L, integer(engine, "large-order-host",
                    "/observedAuthorizationCount"));
            assertEquals("Authorized", text(engine, "large-order-host",
                    "/payNote/authorization/state"));
            assertEquals(Boolean.TRUE, engine.value("large-paynote",
                    "/productConditions/restaurant/product/confirmed")
                    .getValue());
            assertEquals(engine.session("large-paynote").current().blueId(),
                    engine.session("large-order-host").current()
                            .canonicalBlueIdAt("/payNote"),
                    "the containing host must retain the current PayNote head");
            assertEquals(Boolean.TRUE, engine.value("large-order-host",
                    "/payNote/productConditions/restaurant/product/confirmed")
                    .getValue());
            assertEquals(2L, integer(
                    engine, "large-order-host", "/hostRevision"));
            assertEquals(engine.session("large-paynote").epoch() + 1L,
                    integer(engine, "large-order-host",
                            "/payNoteRevisionCount"));
            assertEquals("large-paynote", engine.embeddedDocuments(
                    "large-order-host").get("/payNote"));
            assertEquals(2, engine.session("large-order-host")
                    .layout().physicalObjectCount());
            assertEquals(3, engine.session("large-paynote")
                    .layout().physicalObjectCount());
            assertTrue(work.counter(
                    "process.frozenContractsInvocations") > 10L);
            assertEquals(work.counter(
                            "process.frozenContractsInvocations"),
                    work.counter("process.commitCompanionDeltasApplied"));
            assertTrue(work.counter(
                    "process.subscriptionIntervalsReused") > 0L);
            assertTrue(work.counter(
                    "catchUp.parentRevisionApplications") >= 4L);
            assertNoGenericSplitting(work);
        }
    }

    private static Operation authorization(String id, long amountMinor) {
        return Operation.yaml(
                "authorizeAmount",
                "guarantorChannel",
                "authorizationId: " + id + "\n"
                        + "amountMinor: " + amountMinor + "\n"
                        + "currency: PLN\n");
    }
}
