package blue.coordination.integration;

import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end evidence that completeness follows sources, not business state. */
final class SourceSurfaceIdentityIntegrationTest {
    private static final long T0 = 1_738_000_000_000_000L;
    private static final String CHILD = "source-identity-child";
    private static final String PARENT = "source-identity-parent";
    private static final String OWNER_TIMELINE =
            "examples/source-identity/owner";
    private static final String DYNAMIC_TIMELINE =
            "examples/source-identity/dynamic";

    @Test
    void businessOnlyTransitionReusesIdentityAndRouteChangeInvalidatesIt()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(OWNER_TIMELINE, "owner");
            Timeline dynamic = engine.timeline(
                    DYNAMIC_TIMELINE, "dynamic-owner");
            Timeline parent = engine.timeline(
                    "examples/source-identity/parent", "parent-owner");
            engine.start(CHILD, childSource());
            engine.start(PARENT, parentSource());

            var attachment = engine.appendAt(
                    parent,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(
                                    engine.session(CHILD).current())),
                    T0 + 100L);
            engine.dispatch(attachment);
            long initialEvidenceResolutions = counter(
                    engine.metricsSnapshot(),
                    "journal.sourceSurfaceIdentitiesResolved");
            assertTrue(initialEvidenceResolutions > 0L,
                    "initial catch-up must close with exact source evidence");

            ExactValue dynamicChannel = engine.registerType("""
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: examples/source-identity/dynamic
                    actor:
                      type: MyOS/Principal Actor
                      accountId: dynamic-owner
                    """);
            ExactValue dynamicHandler = engine.registerType("""
                    type: Coordination/Sequential Workflow Operation
                    channel: dynamicChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /total
                              val: {$add: [$document: /total, $binding: event/message/request/amount]}
                          - $return: true
                    """);
            var activation = engine.appendAt(
                    owner,
                    activateDynamic(dynamicChannel, dynamicHandler),
                    T0 + 200L);
            engine.dispatch(activation);
            var businessOnly = engine.appendAt(
                    dynamic,
                    Operation.yaml(
                            "applyDynamic", "dynamicChannel", "amount: 2"),
                    T0 + 300L);
            engine.dispatch(businessOnly);
            assertEquals(2L, integer(engine, CHILD, "/total"));
            assertEquals(initialEvidenceResolutions, counter(
                    engine.metricsSnapshot(),
                    "journal.sourceSurfaceIdentitiesResolved"),
                    "eligible business delivery must not re-hash the surface");

            var retirement = engine.appendAt(
                    owner,
                    Operation.yaml("retireDynamic", "ownerChannel", "{}"),
                    T0 + 400L);
            engine.dispatch(retirement);
            assertEquals(2L, integer(engine, PARENT, "/child/total"));

            long beforeFreshWindow = counter(engine.metricsSnapshot(),
                    "journal.sourceSurfaceIdentitiesResolved");
            engine.dispatch(engine.appendAt(parent, Operation.yaml(
                    "detachChild", "ownerChannel", "{}"), T0 + 500L));
            engine.dispatch(engine.appendAt(parent, Operation.exact(
                    "attachChild", "ownerChannel",
                    engine.embeddedDocumentRequest(
                            engine.session(CHILD).current())), T0 + 600L));
            assertTrue(counter(engine.metricsSnapshot(),
                    "journal.sourceSurfaceIdentitiesResolved")
                    > beforeFreshWindow,
                    "a new binding after source change needs fresh evidence");
            assertEquals(2L, integer(engine, PARENT, "/child/total"));
        }
    }

    private static long counter(
            EngineMetrics.MetricsSnapshot snapshot,
            String name) {
        return snapshot.counters().getOrDefault(name, 0L);
    }

    private static Operation activateDynamic(
            ExactValue channel,
            ExactValue handler) {
        Map<String, Node> fields = new LinkedHashMap<>();
        fields.put("dynamicChannel", channel.referenceNode());
        fields.put("dynamicHandler", handler.referenceNode());
        return Operation.exact(
                "activateDynamic",
                "ownerChannel",
                ExactValue.verified(new Node().properties(fields)));
    }

    private static String childSource() throws Exception {
        return resource("examples/clean/dynamic-source-surface.yaml")
                .replace("documentId: dynamic-source-surface",
                        "documentId: " + CHILD)
                .replace("examples/dynamic-source-surface/owner",
                        OWNER_TIMELINE);
    }

    private static String parentSource() throws Exception {
        return resource("examples/clean/embedded-state-parent.yaml")
                .replace("documentId: embedded-state-parent",
                        "documentId: " + PARENT)
                .replace("coordination/internal/embedded-state-parent",
                        "coordination/internal/" + PARENT)
                .replace("timelineId: examples/embedded/state-parent",
                        "timelineId: examples/source-identity/parent")
                .replace("accountId: bob", "accountId: parent-owner");
    }
}
