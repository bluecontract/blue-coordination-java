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
 * A stable document identity accepts verified known epochs and rejects an
 * unknown divergent body without changing the existing managed lineage.
 */
final class SameDocumentInitialIdentityTest {
    @Test
    void equalExactStatesWithDifferentDocumentIdsKeepIndependentHistories()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String identityFreeCounter = resource(
                    "examples/clean/embedded-counter.yaml")
                    .replace("documentId: embedded-counter-A\n", "");
            Timeline timeline = engine.timeline(
                    "examples/embedded/A", "alice");

            engine.start("counter-lineage-one", identityFreeCounter);
            engine.start("counter-lineage-two", identityFreeCounter);

            assertEquals(
                    engine.session("counter-lineage-one").current().blueId(),
                    engine.session("counter-lineage-two").current().blueId());

            // when
            engine.appendAndDispatch(
                    timeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 4"));

            // then
            assertEquals(4L, integer(
                    engine, "counter-lineage-one", "/counter"));
            assertEquals(4L, integer(
                    engine, "counter-lineage-two", "/counter"));
            assertEquals(2, engine.history("counter-lineage-one").size());
            assertEquals(2, engine.history("counter-lineage-two").size());
            assertEquals("counter-lineage-one", engine.history(
                    "counter-lineage-one").get(1).documentId().value());
            assertEquals("counter-lineage-two", engine.history(
                    "counter-lineage-two").get(1).documentId().value());
        }
    }

    @Test
    void sameDocumentIsReusedButUnknownDivergentStateIsRejectedAtomically()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
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

            // when
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> engine.appendAndDispatch(
                            secondParentTimeline,
                            Operation.exact(
                                    "attachChild",
                                    "ownerChannel",
                                    engine.embeddedDocumentRequest(
                                            conflictingInitial))));

            // then
            assertTrue(failure.getMessage().contains(
                    "Invalid admission evidence: unknown state"),
                    failure::getMessage);
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
                .replace("coordination/internal/embedded-state-parent",
                        "coordination/internal/" + documentId)
                .replace("timelineId: examples/embedded/state-parent",
                        "timelineId: " + timelineId)
                .replace("accountId: bob", "accountId: " + actorId);
    }
}
