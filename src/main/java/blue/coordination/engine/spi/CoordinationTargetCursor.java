package blue.coordination.engine.spi;

import blue.coordination.engine.api.IndexedSessionCandidates;

import java.util.List;

/**
 * Bounded, deterministic cursor over complete Root-session target groups.
 * One session's occurrence vector is never split across pages. Targets are
 * returned exactly once in ascending canonical session-ID order, including
 * across page boundaries.
 */
public interface CoordinationTargetCursor extends AutoCloseable {

    /** Returns at most {@code maximumRoots} complete Root targets. */
    List<IndexedSessionCandidates> nextPage(int maximumRoots);

    /** Returns whether the immutable index generation has been exhausted. */
    boolean exhausted();

    /** Index generation captured when this cursor was opened. */
    long generation();

    @Override
    void close();
}
