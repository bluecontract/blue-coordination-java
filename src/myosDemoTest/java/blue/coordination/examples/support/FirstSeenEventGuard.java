package blue.coordination.examples.support;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Fails a latency campaign when an exact event identity is measured twice. */
public final class FirstSeenEventGuard {
    private final Set<String> observedExactEventBlueIds =
            Collections.synchronizedSet(new LinkedHashSet<>());

    public void requireFirstSeen(String eventBlueId) {
        if (!observedExactEventBlueIds.add(eventBlueId)) {
            throw new IllegalStateException(
                    "Primary latency gate used a pre-seen exact event: "
                            + eventBlueId);
        }
    }

    public int observedCount() {
        return observedExactEventBlueIds.size();
    }
}
