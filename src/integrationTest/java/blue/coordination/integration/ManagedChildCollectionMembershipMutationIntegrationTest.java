package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.indent;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A child revision may change sibling membership owned by its parent. */
final class ManagedChildCollectionMembershipMutationIntegrationTest {
    private static final String PARENT = "managed-child-membership-parent";
    private static final String ALPHA = "embedded-counter-A";
    private static final String BETA = "embedded-counter-B";

    @Test
    void childApplicationAddsThenRemovesParentOwnedCollectionSibling()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String parent = parentFixture(
                    registeredBetaBlueId(engine),
                    engine.exactRequest("{}").blueId());
            Timeline alpha = engine.timeline("examples/embedded/A", "alice");
            EngineMetrics.MetricsSnapshot beforeStart = engine.metricsSnapshot();
            engine.start(PARENT, parent);
            EngineTestSupport.MetricDelta start = delta(
                    beforeStart, engine.metricsSnapshot());

            assertEquals(Map.of(
                    "/games/alpha", ALPHA,
                    "/games/beta", BETA), engine.embeddedDocuments(PARENT));
            assertEquals(3, engine.documentCount());
            assertEquals(3, engine.session(PARENT).layout().physicalObjectCount());
            assertEquals(SessionStatus.READY, engine.session(PARENT).status());
            assertEquals(2L, start.counter("layout.ownedMembershipRefreshes"));
            assertNoGenericSplitting(start);

            List<DocumentRevision> initial = engine.history(PARENT);
            assertEquals(List.of(
                    DocumentRevision.Kind.INITIALIZATION,
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION),
                    initial.stream().map(DocumentRevision::kind).toList());
            assertNull(initial.get(0).after().canonicalAt("/games/beta"));
            assertNotNull(initial.get(1).after().canonicalAt("/games/beta"));
            assertEquals(0L, integer(engine, PARENT, "/games/alpha/counter"));

            EngineMetrics.MetricsSnapshot beforeRemoval =
                    engine.metricsSnapshot();
            engine.appendAndDispatch(alpha, Operation.yaml(
                    "increment", "ownerChannel", "amount: 1"));
            EngineTestSupport.MetricDelta removal = delta(
                    beforeRemoval, engine.metricsSnapshot());

            assertEquals(Map.of("/games/alpha", ALPHA),
                    engine.embeddedDocuments(PARENT));
            assertEquals(SessionStatus.READY, engine.session(PARENT).status());
            assertEquals(1L, integer(engine, PARENT, "/games/alpha/counter"));
            assertEquals(DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    engine.history(PARENT).get(3).kind());
            assertNull(engine.history(PARENT).get(3).after()
                    .canonicalAt("/games/beta"));
            assertEquals(1L, removal.counter("embedding.bindingsRetired"));
            assertEquals(1L, removal.counter(
                    "layout.ownedMembershipRefreshes"));
            assertEquals(1L, removal.counter("layout.catalogCompilations"));
            assertNoGenericSplitting(removal);
            assertEquals(List.of(DocumentRevision.Kind.INITIALIZATION),
                    engine.history(BETA).stream()
                            .map(DocumentRevision::kind).toList());
        }
    }

    @Test
    void childApplicationRevalidatesPortableCollectionLimit()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String parent = parentFixture(
                    registeredBetaBlueId(engine),
                    engine.exactRequest(overflowMembers()).blueId());
            Timeline alpha = engine.timeline("examples/embedded/A", "alice");
            engine.start(PARENT, parent);
            int parentHistory = engine.history(PARENT).size();

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> engine.appendAndDispatch(alpha, Operation.yaml(
                            "increment", "ownerChannel", "amount: 2")));

            assertTrue(failure.getMessage().contains(
                    "PORTABLE_LIMIT_EXCEEDED: Portable limit exceeded: "
                            + "processEmbeddedPathsPerScope"));
            assertEquals(parentHistory, engine.history(PARENT).size());
            assertEquals(Map.of(
                    "/games/alpha", ALPHA,
                    "/games/beta", BETA), engine.embeddedDocuments(PARENT));
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session(PARENT).status());
            assertEquals(0L, integer(engine, PARENT, "/games/alpha/counter"));
            assertEquals(2L, integer(engine, ALPHA, "/counter"));
        }
    }

    private static String parentFixture(
            String betaBlueId,
            String overflowBlueId) throws Exception {
        return resource(
                "examples/round12/managed-child-collection-membership-parent.yaml")
                .replace("  alpha: __ALPHA_DOCUMENT__",
                        "  alpha:\n" + indent(resource(
                                "examples/clean/embedded-counter.yaml").strip(), 4))
                .replace("__BETA_BLUE_ID__", betaBlueId)
                .replace("__OVERFLOW_BLUE_ID__", overflowBlueId);
    }

    private static String registeredBetaBlueId(TestEngine engine)
            throws Exception {
        return engine.exactRequest(resource(
                "examples/clean/embedded-counter-B.yaml")).blueId();
    }

    private static String overflowMembers() {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < 4_097; index++) {
            result.append("member").append(index)
                    .append(": {documentId: overflow-member-")
                    .append(index).append("}\n");
        }
        return result.toString().stripTrailing();
    }
}
