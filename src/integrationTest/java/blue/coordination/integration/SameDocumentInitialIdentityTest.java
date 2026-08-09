package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One stable document identity has one authoritative original initial state.
 * A second parent may reuse that session only by supplying the exact same
 * initial Blue value; a conflicting body with the same documentId fails closed.
 */
final class SameDocumentInitialIdentityTest {
    @Test
    void sameDocumentIsReusedButConflictingInitialStateIsRejectedAtomically()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.start("embedded-counter-A", childInitial);
            engine.appendAndDispatch(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 2"));

            Timeline firstParentTimeline = engine.timeline(
                    "examples/embedded/identity-parent-one", "bob-one");
            engine.start(
                    "identity-parent-one",
                    parentDefinition(
                            "identity-parent-one",
                            "examples/embedded/identity-parent-one",
                            "bob-one"));
            engine.appendAndDispatch(
                    firstParentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));

            assertEquals(2L, integer(
                    engine, "identity-parent-one", "/child/counter"));
            assertEquals(
                    "embedded-counter-A",
                    engine.embeddedDocuments("identity-parent-one")
                            .get("/child"));

            Timeline secondParentTimeline = engine.timeline(
                    "examples/embedded/identity-parent-two", "bob-two");
            engine.start(
                    "identity-parent-two",
                    parentDefinition(
                            "identity-parent-two",
                            "examples/embedded/identity-parent-two",
                            "bob-two"));
            long secondParentEpochBefore = engine.session(
                    "identity-parent-two").epoch();
            int childHistoryBefore = engine.history(
                    "embedded-counter-A").size();

            String conflictingInitial = childInitial.replace(
                    "counter: 0", "counter: 99");
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> engine.appendAndDispatch(
                            secondParentTimeline,
                            Operation.exact(
                                    "attachChild",
                                    "ownerChannel",
                                    engine.embeddedDocumentRequest(
                                            conflictingInitial))));

            assertTrue(failure.getMessage().contains(
                    "exact original initial state"));
            assertEquals(secondParentEpochBefore,
                    engine.session("identity-parent-two").epoch());
            assertTrue(engine.embeddedDocuments(
                    "identity-parent-two").isEmpty());
            assertEquals(childHistoryBefore,
                    engine.history("embedded-counter-A").size());
            assertEquals(2L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(2L, integer(
                    engine, "identity-parent-one", "/child/counter"));
        }
    }

    private static String parentDefinition(
            String documentId,
            String timelineId,
            String actorId) throws Exception {
        return resource("examples/clean/embedded-state-parent.yaml")
                .replace("documentId: embedded-state-parent",
                        "documentId: " + documentId)
                .replace("timelineId: examples/embedded/state-parent",
                        "timelineId: " + timelineId)
                .replace("accountId: bob", "accountId: " + actorId);
    }
}
