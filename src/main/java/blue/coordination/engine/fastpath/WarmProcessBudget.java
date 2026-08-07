package blue.coordination.engine.fastpath;

import java.time.Duration;
import java.util.Objects;

/** Release gate for measured warm end-to-end PROCESS latency. */
public final class WarmProcessBudget {
    public static final Duration ONE_ROOT = Duration.ofMillis(500L);
    public static final Duration TWO_ROOTS = Duration.ofMillis(900L);

    private WarmProcessBudget() { }

    public static void requireWithin(
            int roots, Duration elapsed, String operation) {
        if (roots <= 0) {
            throw new IllegalArgumentException("roots must be positive");
        }
        Duration limit = roots == 1
                ? ONE_ROOT
                : roots == 2
                ? TWO_ROOTS
                : Duration.ofMillis(Math.multiplyExact(450L, roots));
        Duration actual = Objects.requireNonNull(elapsed, "elapsed");
        if (actual.compareTo(limit) > 0) {
            throw new AssertionError(
                    requireText(operation, "operation")
                            + " warm PROCESS exceeded budget: "
                            + actual.toMillis() + "ms > "
                            + limit.toMillis() + "ms for " + roots
                            + " Root(s)");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }
}
