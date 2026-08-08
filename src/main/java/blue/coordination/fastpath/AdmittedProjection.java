package blue.coordination.fastpath;

import java.security.MessageDigest;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;

/**
 * Immutable, already verified event-time view of one subscription generation.
 * It moves scope traversal, chain hashing, dependency indexing and
 * subscription-key inversion out of the hot event loop.
 */
public final class AdmittedProjection {
    private static final String EMPTY_OCCURRENCE_DIGEST = hash(
            "blue.coordination/admitted-projection-occurrences/empty/1.0");

    private final ProjectionGenerationKey generation;
    private final PersistentState state;
    private final List<AdmittedOccurrence> canonicalOccurrences;
    private final PathDependencyIndex dependencyIndex;
    private final String projectionIdentity;
    private final long estimatedWeight;

    public AdmittedProjection(
            ProjectionGenerationKey generation,
            Collection<AdmittedOccurrence> occurrences) {
        this(generation, occurrences, null);
    }

    AdmittedProjection(
            ProjectionGenerationKey generation,
            Collection<AdmittedOccurrence> occurrences,
            PathDependencyIndex suppliedDependencyIndex) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.state = PersistentState.from(
                Objects.requireNonNull(occurrences, "occurrences"));
        this.canonicalOccurrences = state.occurrences();
        this.dependencyIndex = suppliedDependencyIndex != null
                ? suppliedDependencyIndex
                : PathDependencyIndex.fromOccurrences(canonicalOccurrences);
        this.projectionIdentity = identity();
        this.estimatedWeight = estimateWeight();
    }

    private AdmittedProjection(
            ProjectionGenerationKey generation,
            PersistentState state,
            PathDependencyIndex dependencyIndex) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.state = Objects.requireNonNull(state, "state");
        this.canonicalOccurrences = state.occurrences();
        this.dependencyIndex = Objects.requireNonNull(
                dependencyIndex, "dependencyIndex");
        this.projectionIdentity = identity();
        this.estimatedWeight = estimateWeight();
    }

    public ProjectionGenerationKey generation() { return generation; }
    public List<AdmittedOccurrence> occurrences() { return canonicalOccurrences; }
    public String projectionIdentity() { return projectionIdentity; }
    public long estimatedWeight() { return estimatedWeight; }

    AdmittedOccurrence findPublic(String publicKey) {
        return state.publicOccurrence(
                AdmittedOccurrence.text(publicKey, "publicKey"));
    }

    PathDependencyIndex dependencyIndexForSuccessor() {
        return dependencyIndex;
    }

    AdmittedProjection successor(
            ProjectionGenerationKey resultingGeneration,
            Collection<String> retiredPublicKeys,
            Collection<AdmittedOccurrence> refreshed,
            Collection<AdmittedOccurrence> added,
            PathDependencyIndex resultingDependencyIndex) {
        PersistentState changed = state;
        for (String publicKey : Objects.requireNonNull(
                retiredPublicKeys, "retiredPublicKeys")) {
            AdmittedOccurrence old = changed.publicOccurrence(
                    AdmittedOccurrence.text(publicKey, "retiredPublicKey"));
            if (old == null) {
                throw new IllegalArgumentException(
                        "delta retires inactive occurrence: " + publicKey);
            }
            changed = changed.updated(old, null);
        }
        List<AdmittedOccurrence> previousRefreshes =
                new ArrayList<AdmittedOccurrence>();
        for (AdmittedOccurrence replacement : Objects.requireNonNull(
                refreshed, "refreshed")) {
            AdmittedOccurrence exact = Objects.requireNonNull(
                    replacement, "refreshed occurrence");
            AdmittedOccurrence old = changed.publicOccurrence(
                    exact.publicKey());
            if (old == null) {
                throw new IllegalArgumentException(
                        "delta refreshes inactive occurrence: "
                                + exact.publicKey());
            }
            previousRefreshes.add(old);
        }
        // Remove the complete refresh set before inserting replacements. This
        // keeps the update atomic and permits two exact rows to exchange
        // canonical/Language positions without a transient uniqueness clash.
        for (AdmittedOccurrence old : previousRefreshes) {
            changed = changed.updated(old, null);
        }
        for (AdmittedOccurrence replacement : refreshed) {
            changed = changed.updated(null, replacement);
        }
        for (AdmittedOccurrence addition : Objects.requireNonNull(
                added, "added")) {
            changed = changed.updated(
                    null,
                    Objects.requireNonNull(addition, "added occurrence"));
        }
        return new AdmittedProjection(
                resultingGeneration, changed, resultingDependencyIndex);
    }

    public AdmittedOccurrence requirePublic(String publicKey) {
        AdmittedOccurrence result = state.publicOccurrence(
                AdmittedOccurrence.text(publicKey, "publicKey"));
        if (result == null) {
            throw new IllegalArgumentException(
                    "stale or unknown occurrence: " + publicKey);
        }
        return result;
    }

    public AdmittedOccurrence requireLanguage(String languageKey) {
        AdmittedOccurrence result = state.languageOccurrence(
                AdmittedOccurrence.text(languageKey, "languageKey"));
        if (result == null) {
            throw new IllegalArgumentException(
                    "unknown Language occurrence: " + languageKey);
        }
        return result;
    }

    /** Returns already canonical candidate rows for exact subscription keys. */
    public List<String> candidatesForSubscriptionKeys(Collection<String> keys) {
        Set<String> union = new LinkedHashSet<String>();
        for (String key : Objects.requireNonNull(keys, "subscriptionKeys")) {
            KeySetNode matches = state.subscriptionMembers(
                    AdmittedOccurrence.text(key, "subscriptionKey"));
            addKeys(matches, union);
        }
        List<AdmittedOccurrence> occurrences = new ArrayList<AdmittedOccurrence>();
        for (String publicKey : union) {
            occurrences.add(state.publicOccurrence(publicKey));
        }
        Collections.sort(occurrences);
        List<String> result = new ArrayList<String>(occurrences.size());
        for (AdmittedOccurrence occurrence : occurrences) {
            result.add(occurrence.publicKey());
        }
        return Collections.unmodifiableList(result);
    }

    /** Validates only k selected rows and returns their precomputed closure. */
    public SelectedSurface select(Collection<String> orderedCandidateKeys) {
        List<String> supplied = new ArrayList<String>(
                Objects.requireNonNull(orderedCandidateKeys, "orderedCandidateKeys"));
        List<AdmittedOccurrence> selected = new ArrayList<AdmittedOccurrence>(supplied.size());
        Set<String> unique = new LinkedHashSet<String>();
        for (String key : supplied) {
            if (!unique.add(key)) {
                throw new IllegalArgumentException("duplicate candidate: " + key);
            }
            AdmittedOccurrence occurrence = requirePublic(key);
            selected.add(occurrence);
        }
        return new SelectedSurface(generation, selected);
    }

    /** Exact invalidation set; no occurrence scan is performed here. */
    public Set<String> affectedOccurrences(Collection<String> changedPaths) {
        return dependencyIndex.affected(changedPaths);
    }

    private String identity() {
        MessageDigest digest = AdmittedOccurrence.sha256();
        AdmittedOccurrence.add(digest, "blue.coordination/admitted-projection/3.0");
        AdmittedOccurrence.add(digest, generation.environmentIdentity());
        AdmittedOccurrence.add(digest, generation.rootBlueId());
        AdmittedOccurrence.add(digest, Long.toString(generation.rootRevision()));
        AdmittedOccurrence.add(digest, generation.inventoryIdentity());
        AdmittedOccurrence.add(digest, generation.subscriptionDigest());
        AdmittedOccurrence.add(digest, generation.runtimeIdentity());
        AdmittedOccurrence.add(digest, Integer.toString(state.size()));
        AdmittedOccurrence.add(digest, state.digest());
        return "sha256:" + AdmittedOccurrence.hex(digest.digest());
    }

    private long estimateWeight() {
        return Math.max(
                1L,
                Math.multiplyExact(
                        Math.addExact(256L, state.retainedCharacters()),
                        2L));
    }

    private static String hash(String... values) {
        MessageDigest digest = AdmittedOccurrence.sha256();
        for (String value : values) {
            AdmittedOccurrence.add(digest, value);
        }
        return "sha256:" + AdmittedOccurrence.hex(digest.digest());
    }

    private static String priority(String namespace, String key) {
        return hash(
                "blue.coordination/admitted-projection-priority/1.0",
                namespace,
                key);
    }

    private static long occurrenceCharacters(
            AdmittedOccurrence occurrence) {
        // Conservative retained-size accounting: fixed object/list/index
        // overhead plus every String reachable from the immutable occurrence.
        // Persistent successors may share these objects, but charging each
        // cache entry independently keeps eviction safely below the hard cap.
        long characters = 512L;
        characters = Math.addExact(
                characters, occurrence.publicKey().length());
        characters = Math.addExact(
                characters, occurrence.languageKey().length());
        characters = Math.addExact(
                characters, occurrence.scopePath().length());
        characters = Math.addExact(
                characters, occurrence.scopeBlueId().length());
        characters = Math.addExact(
                characters, occurrence.channelKey().length());
        characters = Math.addExact(
                characters, occurrence.effectiveTypeBlueId().length());
        characters = Math.addExact(
                characters, occurrence.headerIdentityBlueId().length());
        characters = Math.addExact(
                characters, occurrence.checkpointDomainBlueId().length());
        characters = Math.addExact(
                characters, occurrence.semanticFingerprint().length());
        for (String value : occurrence.scopeChainBlueIds()) {
            characters = Math.addExact(characters, value.length());
        }
        for (String value : occurrence.sourceContributionBlueIds()) {
            characters = Math.addExact(characters, value.length());
        }
        for (String value : occurrence.dependencyBlueIds()) {
            characters = Math.addExact(characters, value.length());
        }
        for (String value : occurrence.subscriptionKeys()) {
            characters = Math.addExact(characters, value.length());
        }
        for (String value : occurrence.dependencyPaths()) {
            // Also covers the persistent dependency-trie path/key nodes.
            characters = Math.addExact(
                    characters,
                    Math.addExact(96L, value.length()));
        }
        return characters;
    }

    private static Set<String> uniqueSubscriptionKeys(
            AdmittedOccurrence occurrence) {
        return new LinkedHashSet<String>(occurrence.subscriptionKeys());
    }

    private static void addKeys(KeySetNode root, Set<String> target) {
        if (root == null) return;
        Deque<KeySetNode> pending = new ArrayDeque<KeySetNode>();
        KeySetNode cursor = root;
        while (cursor != null || !pending.isEmpty()) {
            while (cursor != null) {
                pending.addLast(cursor);
                cursor = cursor.left;
            }
            KeySetNode next = pending.removeLast();
            target.add(next.key);
            cursor = next.right;
        }
    }

    /** All successor-visible indexes share persistent deterministic spines. */
    private static final class PersistentState {
        private final OccurrenceNode ordered;
        private final LookupNode<AdmittedOccurrence> byPublicKey;
        private final LookupNode<AdmittedOccurrence> byLanguageKey;
        private final LookupNode<KeySetNode> bySubscriptionKey;

        private PersistentState(
                OccurrenceNode ordered,
                LookupNode<AdmittedOccurrence> byPublicKey,
                LookupNode<AdmittedOccurrence> byLanguageKey,
                LookupNode<KeySetNode> bySubscriptionKey) {
            this.ordered = ordered;
            this.byPublicKey = byPublicKey;
            this.byLanguageKey = byLanguageKey;
            this.bySubscriptionKey = bySubscriptionKey;
        }

        private static PersistentState from(
                Collection<AdmittedOccurrence> occurrences) {
            PersistentState state = new PersistentState(
                    null, null, null, null);
            for (AdmittedOccurrence occurrence : occurrences) {
                state = state.updated(
                        null,
                        Objects.requireNonNull(occurrence, "occurrence"));
            }
            return state;
        }

        private PersistentState updated(
                AdmittedOccurrence previous,
                AdmittedOccurrence resulting) {
            if (previous == resulting) return this;
            OccurrenceNode nextOrdered = ordered;
            LookupNode<AdmittedOccurrence> nextPublic = byPublicKey;
            LookupNode<AdmittedOccurrence> nextLanguage = byLanguageKey;
            LookupNode<KeySetNode> nextSubscriptions = bySubscriptionKey;

            if (previous != null) {
                AdmittedOccurrence retained = lookup(
                        nextPublic, previous.publicKey());
                if (retained != previous) {
                    throw new IllegalArgumentException(
                            "previous admitted occurrence is absent or stale: "
                                    + previous.publicKey());
                }
                OccurrenceRemoval removal = remove(
                        nextOrdered, previous);
                if (!removal.removed) {
                    throw new IllegalStateException(
                            "canonical admitted occurrence index is inconsistent");
                }
                nextOrdered = removal.root;
                nextPublic = remove(
                        nextPublic, previous.publicKey());
                nextLanguage = remove(
                        nextLanguage, previous.languageKey());
                for (String subscriptionKey
                        : uniqueSubscriptionKeys(previous)) {
                    KeySetNode members = lookup(
                            nextSubscriptions, subscriptionKey);
                    KeySetRemoval memberRemoval = remove(
                            members, previous.publicKey());
                    if (!memberRemoval.removed) {
                        throw new IllegalStateException(
                                "subscription inversion is inconsistent for "
                                        + subscriptionKey);
                    }
                    nextSubscriptions = memberRemoval.root == null
                            ? remove(nextSubscriptions, subscriptionKey)
                            : put(
                                    nextSubscriptions,
                                    subscriptionKey,
                                    memberRemoval.root,
                                    "subscription-keys");
                }
            }

            if (resulting != null) {
                if (lookup(nextPublic, resulting.publicKey()) != null) {
                    throw new IllegalArgumentException(
                            "duplicate public occurrence key: "
                                    + resulting.publicKey());
                }
                if (lookup(nextLanguage, resulting.languageKey()) != null) {
                    throw new IllegalArgumentException(
                            "duplicate Language occurrence key: "
                                    + resulting.languageKey());
                }
                OccurrenceInsertion insertion = put(
                        nextOrdered, resulting);
                if (!insertion.inserted) {
                    throw new IllegalArgumentException(
                            "duplicate canonical admitted occurrence: "
                                    + resulting.publicKey());
                }
                nextOrdered = insertion.root;
                nextPublic = put(
                        nextPublic,
                        resulting.publicKey(),
                        resulting,
                        "public-keys");
                nextLanguage = put(
                        nextLanguage,
                        resulting.languageKey(),
                        resulting,
                        "language-keys");
                for (String subscriptionKey
                        : uniqueSubscriptionKeys(resulting)) {
                    KeySetNode members = lookup(
                            nextSubscriptions, subscriptionKey);
                    KeySetInsertion memberInsertion = put(
                            members, resulting.publicKey());
                    if (!memberInsertion.inserted) {
                        throw new IllegalStateException(
                                "duplicate subscription inversion for "
                                        + resulting.publicKey());
                    }
                    nextSubscriptions = put(
                            nextSubscriptions,
                            subscriptionKey,
                            memberInsertion.root,
                            "subscription-keys");
                }
            }
            return new PersistentState(
                    nextOrdered,
                    nextPublic,
                    nextLanguage,
                    nextSubscriptions);
        }

        private int size() {
            return AdmittedProjection.size(ordered);
        }

        private long retainedCharacters() {
            return ordered == null ? 0L : ordered.retainedCharacters;
        }

        private String digest() {
            return ordered == null
                    ? EMPTY_OCCURRENCE_DIGEST
                    : ordered.digest;
        }

        private List<AdmittedOccurrence> occurrences() {
            return new PersistentOccurrenceList(ordered);
        }

        private AdmittedOccurrence publicOccurrence(String key) {
            return lookup(byPublicKey, key);
        }

        private AdmittedOccurrence languageOccurrence(String key) {
            return lookup(byLanguageKey, key);
        }

        private KeySetNode subscriptionMembers(String key) {
            return lookup(bySubscriptionKey, key);
        }
    }

    /** Deterministically shaped canonical occurrence Merkle treap. */
    private static final class OccurrenceNode {
        private final AdmittedOccurrence occurrence;
        private final String priority;
        private final OccurrenceNode left;
        private final OccurrenceNode right;
        private final int size;
        private final long retainedCharacters;
        private final String digest;

        private OccurrenceNode(
                AdmittedOccurrence occurrence,
                String priority,
                OccurrenceNode left,
                OccurrenceNode right) {
            this.occurrence = Objects.requireNonNull(
                    occurrence, "occurrence");
            this.priority = Objects.requireNonNull(priority, "priority");
            this.left = left;
            this.right = right;
            this.size = Math.addExact(
                    1,
                    Math.addExact(size(left), size(right)));
            this.retainedCharacters = Math.addExact(
                    occurrenceCharacters(occurrence),
                    Math.addExact(
                            retainedCharacters(left),
                            retainedCharacters(right)));
            this.digest = hash(
                    "blue.coordination/admitted-projection-occurrences/node/1.0",
                    left == null ? EMPTY_OCCURRENCE_DIGEST : left.digest,
                    occurrence.semanticFingerprint(),
                    right == null ? EMPTY_OCCURRENCE_DIGEST : right.digest,
                    Integer.toString(size));
        }
    }

    private static int size(OccurrenceNode node) {
        return node == null ? 0 : node.size;
    }

    private static long retainedCharacters(OccurrenceNode node) {
        return node == null ? 0L : node.retainedCharacters;
    }

    private static OccurrenceInsertion put(
            OccurrenceNode node,
            AdmittedOccurrence occurrence) {
        if (node == null) {
            return new OccurrenceInsertion(
                    new OccurrenceNode(
                            occurrence,
                            priority("occurrence-order", occurrence.publicKey()),
                            null,
                            null),
                    true);
        }
        int compared = occurrence.compareTo(node.occurrence);
        if (compared == 0) {
            return new OccurrenceInsertion(node, false);
        }
        if (compared < 0) {
            OccurrenceInsertion insertion = put(node.left, occurrence);
            if (!insertion.inserted) {
                return new OccurrenceInsertion(node, false);
            }
            OccurrenceNode changed = new OccurrenceNode(
                    node.occurrence,
                    node.priority,
                    insertion.root,
                    node.right);
            return new OccurrenceInsertion(
                    higherPriority(insertion.root, changed)
                            ? rotateRight(changed)
                            : changed,
                    true);
        }
        OccurrenceInsertion insertion = put(node.right, occurrence);
        if (!insertion.inserted) {
            return new OccurrenceInsertion(node, false);
        }
        OccurrenceNode changed = new OccurrenceNode(
                node.occurrence,
                node.priority,
                node.left,
                insertion.root);
        return new OccurrenceInsertion(
                higherPriority(insertion.root, changed)
                        ? rotateLeft(changed)
                        : changed,
                true);
    }

    private static OccurrenceRemoval remove(
            OccurrenceNode node,
            AdmittedOccurrence occurrence) {
        if (node == null) return new OccurrenceRemoval(null, false);
        int compared = occurrence.compareTo(node.occurrence);
        if (compared < 0) {
            OccurrenceRemoval removal = remove(node.left, occurrence);
            return removal.removed
                    ? new OccurrenceRemoval(
                            new OccurrenceNode(
                                    node.occurrence,
                                    node.priority,
                                    removal.root,
                                    node.right),
                            true)
                    : new OccurrenceRemoval(node, false);
        }
        if (compared > 0) {
            OccurrenceRemoval removal = remove(node.right, occurrence);
            return removal.removed
                    ? new OccurrenceRemoval(
                            new OccurrenceNode(
                                    node.occurrence,
                                    node.priority,
                                    node.left,
                                    removal.root),
                            true)
                    : new OccurrenceRemoval(node, false);
        }
        if (!occurrence.publicKey().equals(node.occurrence.publicKey())) {
            return new OccurrenceRemoval(node, false);
        }
        return new OccurrenceRemoval(merge(node.left, node.right), true);
    }

    private static OccurrenceNode merge(
            OccurrenceNode left,
            OccurrenceNode right) {
        if (left == null) return right;
        if (right == null) return left;
        if (higherPriority(left, right)) {
            return new OccurrenceNode(
                    left.occurrence,
                    left.priority,
                    left.left,
                    merge(left.right, right));
        }
        return new OccurrenceNode(
                right.occurrence,
                right.priority,
                merge(left, right.left),
                right.right);
    }

    private static OccurrenceNode rotateRight(OccurrenceNode node) {
        OccurrenceNode pivot = node.left;
        OccurrenceNode moved = new OccurrenceNode(
                node.occurrence,
                node.priority,
                pivot.right,
                node.right);
        return new OccurrenceNode(
                pivot.occurrence,
                pivot.priority,
                pivot.left,
                moved);
    }

    private static OccurrenceNode rotateLeft(OccurrenceNode node) {
        OccurrenceNode pivot = node.right;
        OccurrenceNode moved = new OccurrenceNode(
                node.occurrence,
                node.priority,
                node.left,
                pivot.left);
        return new OccurrenceNode(
                pivot.occurrence,
                pivot.priority,
                moved,
                pivot.right);
    }

    private static boolean higherPriority(
            OccurrenceNode left,
            OccurrenceNode right) {
        int compared = AdmittedOccurrence.codePointCompare(
                left.priority, right.priority);
        return compared < 0 || (compared == 0
                && AdmittedOccurrence.codePointCompare(
                        left.occurrence.publicKey(),
                        right.occurrence.publicKey()) < 0);
    }

    private static final class OccurrenceInsertion {
        private final OccurrenceNode root;
        private final boolean inserted;

        private OccurrenceInsertion(OccurrenceNode root, boolean inserted) {
            this.root = root;
            this.inserted = inserted;
        }
    }

    private static final class OccurrenceRemoval {
        private final OccurrenceNode root;
        private final boolean removed;

        private OccurrenceRemoval(OccurrenceNode root, boolean removed) {
            this.root = root;
            this.removed = removed;
        }
    }

    private static final class PersistentOccurrenceList
            extends AbstractList<AdmittedOccurrence>
            implements RandomAccess {
        private final OccurrenceNode root;

        private PersistentOccurrenceList(OccurrenceNode root) {
            this.root = root;
        }

        @Override
        public AdmittedOccurrence get(int index) {
            if (index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException(
                        "index=" + index + ", size=" + size());
            }
            OccurrenceNode cursor = root;
            int remaining = index;
            while (cursor != null) {
                int leftSize = AdmittedProjection.size(cursor.left);
                if (remaining < leftSize) {
                    cursor = cursor.left;
                } else if (remaining == leftSize) {
                    return cursor.occurrence;
                } else {
                    remaining -= leftSize + 1;
                    cursor = cursor.right;
                }
            }
            throw new AssertionError("persistent occurrence index is corrupt");
        }

        @Override
        public int size() {
            return AdmittedProjection.size(root);
        }

        @Override
        public Iterator<AdmittedOccurrence> iterator() {
            return new Iterator<AdmittedOccurrence>() {
                private final Deque<OccurrenceNode> pending = initialize(root);

                @Override
                public boolean hasNext() {
                    return !pending.isEmpty();
                }

                @Override
                public AdmittedOccurrence next() {
                    if (pending.isEmpty()) throw new NoSuchElementException();
                    OccurrenceNode next = pending.removeLast();
                    pushLeft(next.right, pending);
                    return next.occurrence;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException(
                            "immutable occurrence list");
                }
            };
        }

        private static Deque<OccurrenceNode> initialize(
                OccurrenceNode root) {
            Deque<OccurrenceNode> result =
                    new ArrayDeque<OccurrenceNode>();
            pushLeft(root, result);
            return result;
        }

        private static void pushLeft(
                OccurrenceNode node,
                Deque<OccurrenceNode> target) {
            OccurrenceNode cursor = node;
            while (cursor != null) {
                target.addLast(cursor);
                cursor = cursor.left;
            }
        }
    }

    /** Persistent deterministic string lookup treap. */
    private static final class LookupNode<V> {
        private final String key;
        private final V value;
        private final String priority;
        private final LookupNode<V> left;
        private final LookupNode<V> right;

        private LookupNode(
                String key,
                V value,
                String priority,
                LookupNode<V> left,
                LookupNode<V> right) {
            this.key = key;
            this.value = Objects.requireNonNull(value, "lookup value");
            this.priority = priority;
            this.left = left;
            this.right = right;
        }
    }

    private static <V> V lookup(LookupNode<V> node, String key) {
        LookupNode<V> cursor = node;
        while (cursor != null) {
            int compared = AdmittedOccurrence.codePointCompare(
                    key, cursor.key);
            if (compared == 0) return cursor.value;
            cursor = compared < 0 ? cursor.left : cursor.right;
        }
        return null;
    }

    private static <V> LookupNode<V> put(
            LookupNode<V> node,
            String key,
            V value,
            String namespace) {
        if (node == null) {
            return new LookupNode<V>(
                    key,
                    value,
                    priority(namespace, key),
                    null,
                    null);
        }
        int compared = AdmittedOccurrence.codePointCompare(key, node.key);
        if (compared == 0) {
            return node.value == value
                    ? node
                    : new LookupNode<V>(
                            key,
                            value,
                            node.priority,
                            node.left,
                            node.right);
        }
        if (compared < 0) {
            LookupNode<V> left = put(
                    node.left, key, value, namespace);
            LookupNode<V> changed = new LookupNode<V>(
                    node.key,
                    node.value,
                    node.priority,
                    left,
                    node.right);
            return higherPriority(left, changed)
                    ? rotateRight(changed)
                    : changed;
        }
        LookupNode<V> right = put(
                node.right, key, value, namespace);
        LookupNode<V> changed = new LookupNode<V>(
                node.key,
                node.value,
                node.priority,
                node.left,
                right);
        return higherPriority(right, changed)
                ? rotateLeft(changed)
                : changed;
    }

    private static <V> LookupNode<V> remove(
            LookupNode<V> node,
            String key) {
        if (node == null) return null;
        int compared = AdmittedOccurrence.codePointCompare(key, node.key);
        if (compared < 0) {
            LookupNode<V> left = remove(node.left, key);
            return left == node.left
                    ? node
                    : new LookupNode<V>(
                            node.key,
                            node.value,
                            node.priority,
                            left,
                            node.right);
        }
        if (compared > 0) {
            LookupNode<V> right = remove(node.right, key);
            return right == node.right
                    ? node
                    : new LookupNode<V>(
                            node.key,
                            node.value,
                            node.priority,
                            node.left,
                            right);
        }
        return merge(node.left, node.right);
    }

    private static <V> LookupNode<V> merge(
            LookupNode<V> left,
            LookupNode<V> right) {
        if (left == null) return right;
        if (right == null) return left;
        if (higherPriority(left, right)) {
            return new LookupNode<V>(
                    left.key,
                    left.value,
                    left.priority,
                    left.left,
                    merge(left.right, right));
        }
        return new LookupNode<V>(
                right.key,
                right.value,
                right.priority,
                merge(left, right.left),
                right.right);
    }

    private static <V> LookupNode<V> rotateRight(LookupNode<V> node) {
        LookupNode<V> pivot = node.left;
        LookupNode<V> moved = new LookupNode<V>(
                node.key,
                node.value,
                node.priority,
                pivot.right,
                node.right);
        return new LookupNode<V>(
                pivot.key,
                pivot.value,
                pivot.priority,
                pivot.left,
                moved);
    }

    private static <V> LookupNode<V> rotateLeft(LookupNode<V> node) {
        LookupNode<V> pivot = node.right;
        LookupNode<V> moved = new LookupNode<V>(
                node.key,
                node.value,
                node.priority,
                node.left,
                pivot.left);
        return new LookupNode<V>(
                pivot.key,
                pivot.value,
                pivot.priority,
                moved,
                pivot.right);
    }

    private static boolean higherPriority(
            LookupNode<?> left,
            LookupNode<?> right) {
        int compared = AdmittedOccurrence.codePointCompare(
                left.priority, right.priority);
        return compared < 0 || (compared == 0
                && AdmittedOccurrence.codePointCompare(
                        left.key, right.key) < 0);
    }

    /** Persistent deterministic exact-key bucket. */
    private static final class KeySetNode {
        private final String key;
        private final String priority;
        private final KeySetNode left;
        private final KeySetNode right;

        private KeySetNode(
                String key,
                String priority,
                KeySetNode left,
                KeySetNode right) {
            this.key = key;
            this.priority = priority;
            this.left = left;
            this.right = right;
        }
    }

    private static KeySetInsertion put(KeySetNode node, String key) {
        if (node == null) {
            return new KeySetInsertion(
                    new KeySetNode(
                            key,
                            priority("subscription-members", key),
                            null,
                            null),
                    true);
        }
        int compared = AdmittedOccurrence.codePointCompare(key, node.key);
        if (compared == 0) return new KeySetInsertion(node, false);
        if (compared < 0) {
            KeySetInsertion insertion = put(node.left, key);
            if (!insertion.inserted) return new KeySetInsertion(node, false);
            KeySetNode changed = new KeySetNode(
                    node.key, node.priority, insertion.root, node.right);
            return new KeySetInsertion(
                    higherPriority(insertion.root, changed)
                            ? rotateRight(changed)
                            : changed,
                    true);
        }
        KeySetInsertion insertion = put(node.right, key);
        if (!insertion.inserted) return new KeySetInsertion(node, false);
        KeySetNode changed = new KeySetNode(
                node.key, node.priority, node.left, insertion.root);
        return new KeySetInsertion(
                higherPriority(insertion.root, changed)
                        ? rotateLeft(changed)
                        : changed,
                true);
    }

    private static KeySetRemoval remove(KeySetNode node, String key) {
        if (node == null) return new KeySetRemoval(null, false);
        int compared = AdmittedOccurrence.codePointCompare(key, node.key);
        if (compared < 0) {
            KeySetRemoval removal = remove(node.left, key);
            return removal.removed
                    ? new KeySetRemoval(
                            new KeySetNode(
                                    node.key,
                                    node.priority,
                                    removal.root,
                                    node.right),
                            true)
                    : new KeySetRemoval(node, false);
        }
        if (compared > 0) {
            KeySetRemoval removal = remove(node.right, key);
            return removal.removed
                    ? new KeySetRemoval(
                            new KeySetNode(
                                    node.key,
                                    node.priority,
                                    node.left,
                                    removal.root),
                            true)
                    : new KeySetRemoval(node, false);
        }
        return new KeySetRemoval(merge(node.left, node.right), true);
    }

    private static KeySetNode merge(KeySetNode left, KeySetNode right) {
        if (left == null) return right;
        if (right == null) return left;
        if (higherPriority(left, right)) {
            return new KeySetNode(
                    left.key,
                    left.priority,
                    left.left,
                    merge(left.right, right));
        }
        return new KeySetNode(
                right.key,
                right.priority,
                merge(left, right.left),
                right.right);
    }

    private static KeySetNode rotateRight(KeySetNode node) {
        KeySetNode pivot = node.left;
        KeySetNode moved = new KeySetNode(
                node.key, node.priority, pivot.right, node.right);
        return new KeySetNode(
                pivot.key, pivot.priority, pivot.left, moved);
    }

    private static KeySetNode rotateLeft(KeySetNode node) {
        KeySetNode pivot = node.right;
        KeySetNode moved = new KeySetNode(
                node.key, node.priority, node.left, pivot.left);
        return new KeySetNode(
                pivot.key, pivot.priority, moved, pivot.right);
    }

    private static boolean higherPriority(
            KeySetNode left,
            KeySetNode right) {
        int compared = AdmittedOccurrence.codePointCompare(
                left.priority, right.priority);
        return compared < 0 || (compared == 0
                && AdmittedOccurrence.codePointCompare(
                        left.key, right.key) < 0);
    }

    private static final class KeySetInsertion {
        private final KeySetNode root;
        private final boolean inserted;

        private KeySetInsertion(KeySetNode root, boolean inserted) {
            this.root = root;
            this.inserted = inserted;
        }
    }

    private static final class KeySetRemoval {
        private final KeySetNode root;
        private final boolean removed;

        private KeySetRemoval(KeySetNode root, boolean removed) {
            this.root = root;
            this.removed = removed;
        }
    }

    /** Precomputed per-event resource closure. */
    public static final class SelectedSurface {
        private final ProjectionGenerationKey generation;
        private final List<AdmittedOccurrence> occurrences;
        private final List<String> publicKeys;
        private final List<String> languageKeys;
        private final Map<String, List<String>> scopeChains;
        private final Set<String> requiredIdentities;
        private final List<String> prefetchIdentities;

        private SelectedSurface(
                ProjectionGenerationKey generation,
                List<AdmittedOccurrence> occurrences) {
            this.generation = generation;
            this.occurrences = Collections.unmodifiableList(
                    new ArrayList<AdmittedOccurrence>(occurrences));
            List<String> publicOrder = new ArrayList<String>();
            List<String> languageOrder = new ArrayList<String>();
            Map<String, List<String>> chains = new LinkedHashMap<String, List<String>>();
            Set<String> required = new LinkedHashSet<String>();
            Set<String> prefetch = new java.util.TreeSet<String>(
                    AdmittedOccurrence::codePointCompare);
            required.add(generation.rootBlueId());
            for (AdmittedOccurrence occurrence : occurrences) {
                publicOrder.add(occurrence.publicKey());
                languageOrder.add(occurrence.languageKey());
                chains.putIfAbsent(occurrence.scopePath(), occurrence.scopeChainBlueIds());
                required.addAll(occurrence.scopeChainBlueIds());
                required.addAll(occurrence.sourceContributionBlueIds());
                required.addAll(occurrence.dependencyBlueIds());
                prefetch.addAll(occurrence.sourceContributionBlueIds());
                prefetch.addAll(occurrence.dependencyBlueIds());
            }
            prefetch.remove(generation.rootBlueId());
            this.publicKeys = Collections.unmodifiableList(publicOrder);
            this.languageKeys = Collections.unmodifiableList(languageOrder);
            this.scopeChains = Collections.unmodifiableMap(chains);
            this.requiredIdentities = Collections.unmodifiableSet(required);
            this.prefetchIdentities = Collections.unmodifiableList(
                    new ArrayList<String>(prefetch));
        }

        public ProjectionGenerationKey generation() { return generation; }
        public List<AdmittedOccurrence> occurrences() { return occurrences; }
        public List<String> publicKeys() { return publicKeys; }
        public List<String> languageKeys() { return languageKeys; }
        public Map<String, List<String>> scopeChains() { return scopeChains; }
        public Set<String> requiredIdentities() { return requiredIdentities; }
        public List<String> prefetchIdentities() { return prefetchIdentities; }
    }
}
