package blue.coordination.consumer;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-API-only smoke scenarios resolved from the published Maven artifact. */
final class PublishedArtifactConsumerTest {
    private static final long T0 = 1_700_000_000_000_000L;

    @Test
    void counterExternalApiExample() throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            Timeline alice = engine.registerTimeline(
                    "examples/clean-counter/alice", "alice");
            Timeline bob = engine.registerTimeline(
                    "examples/clean-counter/bob", "bob");
            DocumentId counter = DocumentId.of("counter");
            engine.startDocument(
                    counter, resource("examples/clean/counter.yaml"));
            engine.append(alice, Operation.yaml(
                    "increment", "aliceChannel", "amount: 3"));
            engine.append(bob, Operation.yaml(
                    "decrement", "bobChannel", "amount: 1"));
            var first = engine.drain(
                    new CoordinationEngine.DrainBudget(1L, 1L));
            assertTrue(first.paused());
            assertEquals(1L, first.committedProcessTransitions());
            var second = engine.drain(
                    new CoordinationEngine.DrainBudget(1L, 1L));
            assertTrue(second.quiescent());
            assertEquals(1L, second.committedProcessTransitions());
            assertEquals(engine.document(counter).blueId(),
                    engine.auditDocument(counter).blueId());
            assertEquals(2L, integer(engine, counter, "/counter"));
        }
    }

    @Test
    void largeOrdinaryRequestAppendsWholeWithoutTarget() throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            Timeline unmatched = engine.registerTimeline(
                    "consumer/unmatched", "consumer");
            ExactValue payNote = engine.exactValue(
                    resource("examples/clean/large-paynote.yaml"));
            ExactValue request = engine.referenceRequest("payload", payNote);
            var entry = engine.append(
                    unmatched,
                    Operation.exact("store", "unmatchedChannel", request));
            assertEquals(0, engine.routeTargetCount(entry));
            assertEquals(1, engine.metrics().journalEntryCount());
            assertEquals(0L, engine.metrics().counter(
                    CoordinationMetrics.Counter.REQUEST_FRAGMENTS));
            assertEquals(0L, engine.metrics().counter(
                    CoordinationMetrics.Counter.TIMELINE_ENTRY_FRAGMENTS));
        }
    }

    @Test
    void largeHostCanAttachAuthorizeAndConfirmPayNote() throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            Timeline alice = engine.registerTimeline(
                    "examples/large-order/alice", "alice");
            Timeline admin = engine.registerTimeline(
                    "examples/order/myos-admin", "myos-admin");
            Timeline restaurant = engine.registerTimeline(
                    "examples/order/david", "david");
            DocumentId host = DocumentId.of("large-order-host");
            DocumentId payNote = DocumentId.of("large-paynote");
            String payNoteYaml = resource(
                    "examples/clean/large-paynote.yaml");
            engine.startDocument(
                    host, resource("examples/clean/large-order-host.yaml"));
            appendAndDrain(engine,
                    alice,
                    Operation.exact(
                            "attachPayNote",
                            "ownerChannel",
                            engine.referenceRequest(
                                    "document",
                                    engine.exactValue(payNoteYaml))));
            appendAndDrain(engine,
                    admin,
                    Operation.yaml(
                            "authorizeAmount",
                            "guarantorChannel",
                            "authorizationId: CONSUMER-1\n"
                                    + "amountMinor: 65000\n"
                                    + "currency: PLN"));
            appendAndDrain(engine,
                    admin,
                    Operation.yaml(
                            "authorizeAmount",
                            "guarantorChannel",
                            "authorizationId: CONSUMER-2\n"
                                    + "amountMinor: 65000\n"
                                    + "currency: PLN"));
            appendAndDrain(engine,
                    restaurant,
                    Operation.yaml(
                            "confirmProduct",
                            "providerChannel",
                            "confirmationReference: CONSUMER-DINNER"));

            assertEquals("Authorized", text(
                    engine, payNote, "/authorization/state"));
            assertEquals("Authorized", text(
                    engine, host, "/payNote/authorization/state"));
            assertEquals(Boolean.TRUE, engine.document(host).valueAt(
                    "/payNote/productConditions/restaurant/product/confirmed")
                    .copyNode().getValue());
            assertEquals(2, engine.document(host).physicalObjectCount());
        }
    }

    @Test
    void existingSharedChildAdvancesTwoParents() throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            String childYaml = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.registerTimeline(
                    "examples/embedded/A", "alice");
            Timeline firstTimeline = engine.registerTimeline(
                    "examples/embedded/parent-one", "bob-one");
            Timeline secondTimeline = engine.registerTimeline(
                    "examples/embedded/parent-two", "bob-two");
            DocumentId child = DocumentId.of("embedded-counter-A");
            DocumentId first = DocumentId.of("embedded-parent-one");
            DocumentId second = DocumentId.of("embedded-parent-two");
            engine.startDocument(child, childYaml);
            appendAndDrain(engine, childTimeline, Operation.yaml(
                    "increment", "ownerChannel", "amount: 2"));
            engine.startDocument(first, parentDefinition(
                    "embedded-parent-one",
                    "examples/embedded/parent-one",
                    "bob-one"));
            engine.startDocument(second, parentDefinition(
                    "embedded-parent-two",
                    "examples/embedded/parent-two",
                    "bob-two"));
            ExactValue childReference = engine.referenceRequest(
                    "document", engine.exactValue(childYaml));
            appendAndDrain(engine, firstTimeline, Operation.exact(
                    "attachChild", "ownerChannel", childReference));
            appendAndDrain(engine, secondTimeline, Operation.exact(
                    "attachChild", "ownerChannel", childReference));
            appendAndDrain(engine, childTimeline, Operation.yaml(
                    "increment", "ownerChannel", "amount: 5"));

            assertEquals(7L, integer(engine, child, "/counter"));
            assertEquals(7L, integer(engine, first, "/child/counter"));
            assertEquals(7L, integer(engine, second, "/child/counter"));
        }
    }

    @Test
    void nbaHistoricalGameCatchesStatisticsUp() throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            String gameYaml = resource("examples/clean/nba-game.yaml");
            Timeline gameFeed = engine.registerTimeline(
                    "examples/nba/game-2016-lal-min", "nba-feed");
            Timeline commissioner = engine.registerTimeline(
                    "examples/nba/statistics", "commissioner");
            DocumentId game = DocumentId.of("nba-game-2016-lal-min");
            DocumentId statistics = DocumentId.of("nba-statistics");
            engine.startDocument(game, gameYaml);
            dispatch(engine, gameFeed, T0 + 100, "startGame", "{}");
            dispatch(engine, gameFeed, T0 + 200, "homeScores", "points: 2");
            dispatch(engine, gameFeed, T0 + 300, "awayScores", "points: 3");
            dispatch(engine, gameFeed, T0 + 400, "endGame", "{}");
            int gameRevisions = engine.history(game).size();
            engine.startDocument(
                    statistics,
                    resource("examples/clean/nba-statistics.yaml"));
            appendAndDrain(engine,
                    commissioner,
                    Operation.exact(
                            "attachGame",
                            "commissionerChannel",
                            engine.referenceRequest(
                                    "document", engine.exactValue(gameYaml))));

            assertEquals("Final", text(
                    engine, statistics, "/observedStatus"));
            assertEquals(2L, integer(
                    engine, statistics, "/observedHomeScore"));
            assertEquals(3L, integer(
                    engine, statistics, "/observedAwayScore"));
            assertEquals(gameRevisions, engine.history(game).size());
            assertTrue(engine.effectiveTimelineIds(statistics).contains(
                    "examples/nba/game-2016-lal-min"));
        }
    }

    @Test
    void fiveEmbeddedOccurrencesReuseThreeManagedDocuments() throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            Timeline owner = engine.registerTimeline(
                    "examples/playground/five-occurrence/host",
                    "playground-owner");
            engine.registerTimeline(
                    "examples/playground/five-occurrence/alpha",
                    "playground-feed-alpha");
            engine.registerTimeline(
                    "examples/playground/five-occurrence/beta",
                    "playground-feed-beta");
            engine.registerTimeline(
                    "examples/playground/five-occurrence/gamma",
                    "playground-feed-gamma");
            DocumentId host = DocumentId.of(
                    "playground-five-occurrence-host");
            engine.startDocument(host, resource(
                    "examples/playground/five-occurrence-initialization-host.yaml"));

            int wholeObjectsBeforeRequest =
                    engine.metrics().wholeObjectCount();
            ExactValue request = engine.exactValue(
                    fiveDocumentRequest(engine));
            assertEquals(4,
                    engine.metrics().wholeObjectCount()
                            - wholeObjectsBeforeRequest,
                    "three unique child bodies plus one whole request");
            appendAndDrain(engine, owner, Operation.exact(
                    "attachFiveDocuments",
                    "ownerChannel",
                    request));

            assertEquals(4, engine.metrics().documentCount());
            assertEquals(5, engine.document(host).embeddedChildren().size());
            assertEquals(5L, integer(
                    engine, host, "/initializationEventCount"));
            assertEquals(2L, integer(
                    engine, host, "/alphaInitializationEventCount"));
            assertEquals(2L, integer(
                    engine, host, "/betaInitializationEventCount"));
            assertEquals(1L, integer(
                    engine, host, "/gammaInitializationEventCount"));
            assertEquals(1, engine.history(
                    DocumentId.of("playground-game-alpha")).size());
            assertEquals(1, engine.history(
                    DocumentId.of("playground-game-beta")).size());
            assertEquals(1, engine.history(
                    DocumentId.of("playground-game-gamma")).size());
        }
    }

    private static String fiveDocumentRequest(
            CoordinationEngine engine) throws IOException {
        String template = resource(
                "examples/round12/nba-lifecycle-game.yaml");
        ExactValue alpha = engine.exactValue(gameDefinition(
                template,
                "playground-game-alpha",
                "examples/playground/five-occurrence/alpha",
                "playground-feed-alpha"));
        ExactValue beta = engine.exactValue(gameDefinition(
                template,
                "playground-game-beta",
                "examples/playground/five-occurrence/beta",
                "playground-feed-beta"));
        ExactValue gamma = engine.exactValue(gameDefinition(
                template,
                "playground-game-gamma",
                "examples/playground/five-occurrence/gamma",
                "playground-feed-gamma"));
        return "documents:\n"
                + childReference("betaSecond", beta)
                + childReference("alphaSecond", alpha)
                + childReference("gamma", gamma)
                + childReference("betaFirst", beta)
                + childReference("alphaFirst", alpha);
    }

    private static String childReference(String key, ExactValue value) {
        return "  " + key + ":\n"
                + "    blueId: " + value.blueId() + "\n";
    }

    private static String gameDefinition(
            String template,
            String documentId,
            String timelineId,
            String actorId) {
        return template
                .replace("documentId: nba-lifecycle-game",
                        "documentId: " + documentId)
                .replace("timelineId: examples/round12/nba/game",
                        "timelineId: " + timelineId)
                .replace("accountId: nba-feed",
                        "accountId: " + actorId);
    }


    private static void dispatch(
            CoordinationEngine engine,
            Timeline timeline,
            long timestamp,
            String operation,
            String request) {
        var entry = engine.appendAt(
                timeline,
                Operation.yaml(operation, "gameFeed", request),
                timestamp);
        engine.drainThrough(entry.sourceOrderKey());
    }

    private static void appendAndDrain(
            CoordinationEngine engine,
            Timeline timeline,
            Operation operation) {
        var entry = engine.append(timeline, operation);
        engine.drainThrough(entry.sourceOrderKey());
    }

    private static String parentDefinition(
            String documentId,
            String timelineId,
            String actorId) throws IOException {
        return resource("examples/clean/embedded-state-parent.yaml")
                .replace("documentId: embedded-state-parent",
                        "documentId: " + documentId)
                .replace("coordination/internal/embedded-state-parent",
                        "coordination/internal/" + documentId)
                .replace("timelineId: examples/embedded/state-parent",
                        "timelineId: " + timelineId)
                .replace("accountId: bob", "accountId: " + actorId);
    }

    private static long integer(
            CoordinationEngine engine,
            DocumentId document,
            String pointer) {
        Object value = engine.document(document).valueAt(pointer)
                .copyNode().getValue();
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        return ((Number) value).longValue();
    }

    private static String text(
            CoordinationEngine engine,
            DocumentId document,
            String pointer) {
        return (String) engine.document(document).valueAt(pointer)
                .copyNode().getValue();
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = PublishedArtifactConsumerTest.class
                .getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing resource " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
