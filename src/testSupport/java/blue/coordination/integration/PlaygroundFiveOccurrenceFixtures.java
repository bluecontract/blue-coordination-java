package blue.coordination.integration;

import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared exact fixtures for the five-occurrence Playground acceptance lane. */
final class PlaygroundFiveOccurrenceFixtures {
    static final String HOST_ID = "playground-five-occurrence-host";
    static final String HOST_RESOURCE =
            "examples/playground/five-occurrence-initialization-host.yaml";
    static final String HOST_TIMELINE =
            "examples/playground/five-occurrence/host";
    static final String HOST_ACTOR = "playground-owner";
    static final String ALPHA_ID = "playground-game-alpha";
    static final String BETA_ID = "playground-game-beta";
    static final String GAMMA_ID = "playground-game-gamma";
    static final String ALPHA_FIRST = "alphaFirst";
    static final String ALPHA_SECOND = "alphaSecond";
    static final String BETA_FIRST = "betaFirst";
    static final String BETA_SECOND = "betaSecond";
    static final String GAMMA = "gamma";

    static final List<String> DELIBERATELY_UNORDERED_KEYS = List.of(
            BETA_SECOND,
            ALPHA_SECOND,
            GAMMA,
            BETA_FIRST,
            ALPHA_FIRST);
    static final List<String> REVERSE_KEYS = List.of(
            ALPHA_FIRST,
            BETA_FIRST,
            GAMMA,
            ALPHA_SECOND,
            BETA_SECOND);

    private PlaygroundFiveOccurrenceFixtures() {
    }

    static void registerChildTimelines(TestEngine engine) {
        engine.timeline(alphaTimeline(), "playground-feed-alpha");
        engine.timeline(betaTimeline(), "playground-feed-beta");
        engine.timeline(gammaTimeline(), "playground-feed-gamma");
    }

    /**
     * Retains each unique child body once, then authors the five occurrences as
     * exact BlueId references. The request therefore proves both logical
     * session reuse and whole-object provider reuse.
     */
    static Operation attachFive(TestEngine engine) throws Exception {
        return replaceDocuments(engine, DELIBERATELY_UNORDERED_KEYS);
    }

    /** Authors any ordered subset of the five stable-key occurrences. */
    static Operation replaceDocuments(
            TestEngine engine,
            List<String> orderedKeys) throws Exception {
        ExactValue alpha = alpha(engine);
        ExactValue beta = beta(engine);
        ExactValue gamma = gamma(engine);
        return Operation.exact(
                "attachFiveDocuments",
                "ownerChannel",
                engine.exactRequest(referenceRequest(
                        alpha, beta, gamma, orderedKeys)));
    }

    static String referenceRequest(
            ExactValue alpha,
            ExactValue beta,
            ExactValue gamma) {
        return referenceRequest(
                alpha, beta, gamma, DELIBERATELY_UNORDERED_KEYS);
    }

    static String referenceRequest(
            ExactValue alpha,
            ExactValue beta,
            ExactValue gamma,
            List<String> orderedKeys) {
        Map<String, ExactValue> values = Map.of(
                ALPHA_FIRST, alpha,
                ALPHA_SECOND, alpha,
                BETA_FIRST, beta,
                BETA_SECOND, beta,
                GAMMA, gamma);
        Map<String, String> requestedDocuments = new LinkedHashMap<>();
        for (String key : List.copyOf(orderedKeys)) {
            ExactValue value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                        "Unknown five-occurrence key " + key);
            }
            requestedDocuments.put(key, value.blueId());
        }
        StringBuilder result = new StringBuilder("documents:\n");
        requestedDocuments.forEach((key, blueId) -> result
                .append("  ").append(key).append(":\n")
                .append("    blueId: ").append(blueId).append('\n'));
        return result.toString();
    }

    static Map<String, String> expectedBindings() {
        return Map.of(
                "/children/alphaFirst", ALPHA_ID,
                "/children/alphaSecond", ALPHA_ID,
                "/children/betaFirst", BETA_ID,
                "/children/betaSecond", BETA_ID,
                "/children/gamma", GAMMA_ID);
    }

    static java.util.List<String> expectedApplicationPaths() {
        return java.util.List.of(
                "/children/alphaFirst",
                "/children/alphaSecond",
                "/children/betaFirst",
                "/children/betaSecond",
                "/children/gamma");
    }

    private static ExactValue exactGame(
            TestEngine engine,
            String documentId,
            String timelineId,
            String actorId) throws Exception {
        return engine.exactValue(Round12NbaFixtures.game(
                documentId, timelineId, actorId));
    }

    static ExactValue alpha(TestEngine engine) throws Exception {
        return exactGame(
                engine, ALPHA_ID, alphaTimeline(), "playground-feed-alpha");
    }

    static ExactValue beta(TestEngine engine) throws Exception {
        return exactGame(
                engine, BETA_ID, betaTimeline(), "playground-feed-beta");
    }

    static ExactValue gamma(TestEngine engine) throws Exception {
        return exactGame(
                engine, GAMMA_ID, gammaTimeline(), "playground-feed-gamma");
    }

    static String alphaTimeline() {
        return "examples/playground/five-occurrence/alpha";
    }

    static String betaTimeline() {
        return "examples/playground/five-occurrence/beta";
    }

    static String gammaTimeline() {
        return "examples/playground/five-occurrence/gamma";
    }
}
