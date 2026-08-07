package blue.coordination.engine.api;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Frozen, deterministic, all-matching-Root plan for one stored event.
 *
 * <p>The canonical representation is page-addressable. {@link #targets()} is
 * a lazy compatibility view and never constructs a second flat target list.</p>
 */
public final class CoordinationDispatchPlan {

    private final StoredCoordinationEvent event;
    private final List<String> exactEventSubscriptionKeys;
    private final String sourceChannel;
    private final long routeIndexGeneration;
    private final List<CoordinationDispatchPage> frozenPages;
    private final List<List<IndexedSessionCandidates>> pages;
    private final List<IndexedSessionCandidates> targets;
    private final int maximumRootsPerChunk;

    /**
     * Compatibility constructor for callers that already hold a complete
     * target vector. New dispatchers should stream immutable pages into their
     * plan store and call {@link #fromFrozenPages}.
     */
    public CoordinationDispatchPlan(
            StoredCoordinationEvent event,
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            long routeIndexGeneration,
            List<IndexedSessionCandidates> targets,
            int maximumRootsPerChunk) {
        this(event,
                exactEventSubscriptionKeys,
                sourceChannel,
                routeIndexGeneration,
                new FrozenPageVector(
                        partitionSorted(targets, maximumRootsPerChunk)),
                maximumRootsPerChunk);
    }

    /** Creates a plan from canonical target lists, defensively copying pages. */
    public static CoordinationDispatchPlan fromPages(
            StoredCoordinationEvent event,
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            long routeIndexGeneration,
            List<List<IndexedSessionCandidates>> pages,
            int maximumRootsPerChunk) {
        return fromFrozenPages(
                event,
                exactEventSubscriptionKeys,
                sourceChannel,
                routeIndexGeneration,
                immutablePages(pages),
                maximumRootsPerChunk);
    }

    /**
     * Creates a plan by sharing immutable page values with its durable store.
     * Only the bounded outer page index is copied.
     */
    public static CoordinationDispatchPlan fromFrozenPages(
            StoredCoordinationEvent event,
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            long routeIndexGeneration,
            List<CoordinationDispatchPage> pages,
            int maximumRootsPerChunk) {
        return new CoordinationDispatchPlan(
                event,
                exactEventSubscriptionKeys,
                sourceChannel,
                routeIndexGeneration,
                new FrozenPageVector(pages),
                maximumRootsPerChunk);
    }

    private CoordinationDispatchPlan(
            StoredCoordinationEvent event,
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            long routeIndexGeneration,
            FrozenPageVector suppliedPages,
            int maximumRootsPerChunk) {
        this.event = Objects.requireNonNull(event, "event");
        this.exactEventSubscriptionKeys = immutableText(
                exactEventSubscriptionKeys,
                "exactEventSubscriptionKeys");
        this.sourceChannel = requireText(sourceChannel, "sourceChannel");
        if (routeIndexGeneration < 0L) {
            throw new IllegalArgumentException(
                    "routeIndexGeneration must be non-negative");
        }
        this.routeIndexGeneration = routeIndexGeneration;
        if (maximumRootsPerChunk <= 0) {
            throw new IllegalArgumentException(
                    "maximumRootsPerChunk must be positive");
        }
        this.maximumRootsPerChunk = maximumRootsPerChunk;
        this.frozenPages = immutableCanonicalPages(
                suppliedPages.values, maximumRootsPerChunk);
        this.pages = pageLists(this.frozenPages);
        this.targets = new CoordinationPagedList<IndexedSessionCandidates>(
                this.pages);
    }

    public StoredCoordinationEvent event() { return event; }
    public String dispatchIdentity() { return event.eventBlueId(); }
    public List<String> exactEventSubscriptionKeys() {
        return exactEventSubscriptionKeys;
    }
    public String sourceChannel() { return sourceChannel; }
    public long routeIndexGeneration() { return routeIndexGeneration; }

    /** Lazy flattened compatibility view over {@link #pages()}. */
    public List<IndexedSessionCandidates> targets() { return targets; }

    /** Immutable page values suitable for a page-addressable plan store. */
    public List<CoordinationDispatchPage> frozenPages() {
        return frozenPages;
    }

    /** Immutable, bounded target-list view in canonical session order. */
    public List<List<IndexedSessionCandidates>> pages() { return pages; }

    /** Compatibility alias for {@link #pages()}. */
    public List<List<IndexedSessionCandidates>> chunks() { return pages; }

    public int pageCount() { return frozenPages.size(); }
    public int targetCount() { return targets.size(); }
    public int maximumRootsPerChunk() { return maximumRootsPerChunk; }

    private static List<CoordinationDispatchPage> partitionSorted(
            List<IndexedSessionCandidates> supplied,
            int maximumRootsPerChunk) {
        if (maximumRootsPerChunk <= 0) {
            throw new IllegalArgumentException(
                    "maximumRootsPerChunk must be positive");
        }
        List<IndexedSessionCandidates> ordered =
                new ArrayList<IndexedSessionCandidates>(
                        Objects.requireNonNull(supplied, "targets"));
        for (IndexedSessionCandidates target : ordered) {
            Objects.requireNonNull(target, "target");
        }
        Collections.sort(ordered);
        List<CoordinationDispatchPage> result =
                new ArrayList<CoordinationDispatchPage>();
        List<IndexedSessionCandidates> current =
                new ArrayList<IndexedSessionCandidates>(
                        Math.min(maximumRootsPerChunk, ordered.size()));
        IndexedSessionCandidates previous = null;
        for (IndexedSessionCandidates target : ordered) {
            if (previous != null && previous.compareTo(target) == 0) {
                throw new IllegalArgumentException(
                        "Duplicate target session " + target.sessionId());
            }
            current.add(target);
            if (current.size() == maximumRootsPerChunk) {
                result.add(new CoordinationDispatchPage(current));
                current = new ArrayList<IndexedSessionCandidates>(
                        maximumRootsPerChunk);
            }
            previous = target;
        }
        if (!current.isEmpty()) {
            result.add(new CoordinationDispatchPage(current));
        }
        return result;
    }

    private static List<CoordinationDispatchPage> immutablePages(
            List<List<IndexedSessionCandidates>> supplied) {
        List<CoordinationDispatchPage> result =
                new ArrayList<CoordinationDispatchPage>();
        for (List<IndexedSessionCandidates> page : Objects.requireNonNull(
                supplied, "pages")) {
            result.add(new CoordinationDispatchPage(page));
        }
        return result;
    }

    private static List<CoordinationDispatchPage> immutableCanonicalPages(
            List<CoordinationDispatchPage> supplied,
            int maximumRootsPerChunk) {
        Objects.requireNonNull(supplied, "pages");
        List<CoordinationDispatchPage> copied =
                new ArrayList<CoordinationDispatchPage>(supplied.size());
        IndexedSessionCandidates previous = null;
        for (CoordinationDispatchPage page : supplied) {
            CoordinationDispatchPage checked = Objects.requireNonNull(
                    page, "page");
            if (checked.size() > maximumRootsPerChunk) {
                throw new IllegalArgumentException(
                        "Frozen target page exceeds maximumRootsPerChunk");
            }
            for (IndexedSessionCandidates target : checked.targets()) {
                if (previous != null && previous.compareTo(target) >= 0) {
                    throw new IllegalArgumentException(
                            "Frozen targets must be unique and in canonical "
                                    + "session order: " + target.sessionId());
                }
                previous = target;
            }
            copied.add(checked);
        }
        return Collections.unmodifiableList(copied);
    }

    private static List<List<IndexedSessionCandidates>> pageLists(
            final List<CoordinationDispatchPage> pages) {
        return new AbstractList<List<IndexedSessionCandidates>>() {
            @Override
            public List<IndexedSessionCandidates> get(int index) {
                return pages.get(index).targets();
            }

            @Override
            public int size() { return pages.size(); }
        };
    }

    private static List<String> immutableText(
            List<String> source,
            String label) {
        List<String> copied = new ArrayList<String>(
                Objects.requireNonNull(source, label));
        for (String value : copied) {
            requireText(value, label + " entry");
        }
        return Collections.unmodifiableList(copied);
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return checked;
    }

    private static final class FrozenPageVector {
        private final List<CoordinationDispatchPage> values;

        private FrozenPageVector(List<CoordinationDispatchPage> values) {
            this.values = Objects.requireNonNull(values, "pages");
        }
    }
}
