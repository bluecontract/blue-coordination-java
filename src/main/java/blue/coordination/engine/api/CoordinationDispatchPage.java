package blue.coordination.engine.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One immutable, indivisible-Root page in a frozen dispatch plan. */
public final class CoordinationDispatchPage {

    private final List<IndexedSessionCandidates> targets;

    public CoordinationDispatchPage(
            List<IndexedSessionCandidates> targets) {
        List<IndexedSessionCandidates> copied =
                new ArrayList<IndexedSessionCandidates>(
                        Objects.requireNonNull(targets, "targets"));
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(
                    "A frozen dispatch page must not be empty");
        }
        for (IndexedSessionCandidates target : copied) {
            Objects.requireNonNull(target, "target");
        }
        this.targets = Collections.unmodifiableList(copied);
    }

    public List<IndexedSessionCandidates> targets() { return targets; }
    public int size() { return targets.size(); }
}
