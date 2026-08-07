package blue.coordination.engine.memory;

import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.spi.CoordinationSubscriptionIndex;
import blue.coordination.engine.spi.CoordinationTargetCursor;
import blue.coordination.processor.CoordinationSubscriptionOccurrence;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;

/** In-memory cross-session index over current occurrence subscription keys. */
public final class InMemoryCoordinationSubscriptionIndex
        implements CoordinationSubscriptionIndex {

    private static final Comparator<IndexedOccurrence> OCCURRENCE_ORDER =
            new Comparator<IndexedOccurrence>() {
                @Override
                public int compare(
                        IndexedOccurrence left,
                        IndexedOccurrence right) {
                    int compared = compareText(
                            left.sessionId.value(), right.sessionId.value());
                    if (compared != 0) {
                        return compared;
                    }
                    compared = Integer.compare(
                            depth(right.scopePath), depth(left.scopePath));
                    if (compared != 0) {
                        return compared;
                    }
                    compared = compareText(left.scopePath, right.scopePath);
                    if (compared != 0) {
                        return compared;
                    }
                    compared = Integer.compare(left.order, right.order);
                    if (compared != 0) {
                        return compared;
                    }
                    compared = compareText(left.channelKey, right.channelKey);
                    if (compared != 0) {
                        return compared;
                    }
                    compared = compareText(
                            left.effectiveTypeBlueId,
                            right.effectiveTypeBlueId);
                    return compared != 0
                            ? compared
                            : compareText(
                                    left.occurrenceKey,
                                    right.occurrenceKey);
                }
            };

    private final Map<String, NavigableSet<IndexedOccurrence>>
            occurrencesBySubscriptionKey = new TreeMap<String,
                    NavigableSet<IndexedOccurrence>>(
                            ExternalOrderKey::compareTextCodePoints);
    private final Map<DocumentSessionId, List<Registration>>
            registrationsBySession = new LinkedHashMap<DocumentSessionId,
                    List<Registration>>();
    private long generation;

    @Override
    public synchronized void replaceSession(ManagedDocumentSnapshot snapshot) {
        ManagedDocumentSnapshot checked = Objects.requireNonNull(
                snapshot, "snapshot");
        List<Registration> staged = registrationsFor(checked);
        long nextGeneration = Math.addExact(generation, 1L);

        removeInternal(checked.sessionId());
        for (Registration registration : staged) {
            NavigableSet<IndexedOccurrence> current =
                    occurrencesBySubscriptionKey.get(
                            registration.subscriptionKey);
            NavigableSet<IndexedOccurrence> replacement =
                    new TreeSet<IndexedOccurrence>(OCCURRENCE_ORDER);
            if (current != null) {
                replacement.addAll(current);
            }
            replacement.add(registration.occurrence);
            occurrencesBySubscriptionKey.put(
                    registration.subscriptionKey,
                    Collections.unmodifiableNavigableSet(replacement));
        }
        registrationsBySession.put(
                checked.sessionId(),
                Collections.unmodifiableList(
                        new ArrayList<Registration>(staged)));
        generation = nextGeneration;
    }

    @Override
    public synchronized void removeSession(DocumentSessionId sessionId) {
        DocumentSessionId checked = Objects.requireNonNull(
                sessionId, "sessionId");
        if (registrationsBySession.containsKey(checked)) {
            long nextGeneration = Math.addExact(generation, 1L);
            removeInternal(checked);
            generation = nextGeneration;
        }
    }

    /**
     * Atomically rebuilds every derived row from authoritative sessions.
     *
     * <p>The supplied snapshots are copied, validated, and canonically sorted
     * before any live row is changed. Duplicate session identities or an
     * invalid snapshot fail without modifying the current generation.</p>
     *
     * @param authoritativeSessions complete current authoritative session set
     */
    public synchronized void rebuildFromAuthoritativeSessions(
            Iterable<? extends ManagedDocumentSnapshot>
                    authoritativeSessions) {
        List<ManagedDocumentSnapshot> ordered =
                new ArrayList<ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot session : Objects.requireNonNull(
                authoritativeSessions, "authoritativeSessions")) {
            ordered.add(Objects.requireNonNull(
                    session, "authoritative session"));
        }
        Collections.sort(
                ordered,
                new Comparator<ManagedDocumentSnapshot>() {
                    @Override
                    public int compare(
                            ManagedDocumentSnapshot left,
                            ManagedDocumentSnapshot right) {
                        return compareText(
                                left.sessionId().value(),
                                right.sessionId().value());
                    }
                });

        Map<String, NavigableSet<IndexedOccurrence>> stagedByKey =
                new TreeMap<String, NavigableSet<IndexedOccurrence>>(
                        ExternalOrderKey::compareTextCodePoints);
        Map<DocumentSessionId, List<Registration>> stagedBySession =
                new LinkedHashMap<DocumentSessionId, List<Registration>>();
        for (ManagedDocumentSnapshot session : ordered) {
            if (stagedBySession.containsKey(session.sessionId())) {
                throw new IllegalArgumentException(
                        "Duplicate authoritative session: "
                                + session.sessionId());
            }
            List<Registration> registrations = registrationsFor(session);
            for (Registration registration : registrations) {
                NavigableSet<IndexedOccurrence> values = stagedByKey.get(
                        registration.subscriptionKey);
                if (values == null) {
                    values = new TreeSet<IndexedOccurrence>(OCCURRENCE_ORDER);
                    stagedByKey.put(registration.subscriptionKey, values);
                }
                values.add(registration.occurrence);
            }
            stagedBySession.put(
                    session.sessionId(),
                    Collections.unmodifiableList(
                            new ArrayList<Registration>(registrations)));
        }

        Map<String, NavigableSet<IndexedOccurrence>> frozenByKey =
                new TreeMap<String, NavigableSet<IndexedOccurrence>>(
                        ExternalOrderKey::compareTextCodePoints);
        for (Map.Entry<String, NavigableSet<IndexedOccurrence>> entry
                : stagedByKey.entrySet()) {
            frozenByKey.put(
                    entry.getKey(),
                    Collections.unmodifiableNavigableSet(
                            new TreeSet<IndexedOccurrence>(entry.getValue())));
        }
        long nextGeneration = Math.addExact(generation, 1L);
        occurrencesBySubscriptionKey.clear();
        occurrencesBySubscriptionKey.putAll(frozenByKey);
        registrationsBySession.clear();
        registrationsBySession.putAll(stagedBySession);
        generation = nextGeneration;
    }

    @Override
    public synchronized CoordinationTargetCursor openCandidates(
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            ExternalOrderKey eventOrderKey) {
        Objects.requireNonNull(
                exactEventSubscriptionKeys, "exactEventSubscriptionKeys");
        String checkedSource = Objects.requireNonNull(
                sourceChannel, "sourceChannel");
        ExternalOrderKey checkedOrder = Objects.requireNonNull(
                eventOrderKey, "eventOrderKey");
        Set<String> uniqueKeys = new TreeSet<String>(
                ExternalOrderKey::compareTextCodePoints);
        uniqueKeys.addAll(exactEventSubscriptionKeys);
        List<NavigableSet<IndexedOccurrence>> immutableSources =
                new ArrayList<NavigableSet<IndexedOccurrence>>();
        for (String key : uniqueKeys) {
            NavigableSet<IndexedOccurrence> indexed =
                    occurrencesBySubscriptionKey.get(key);
            if (indexed != null && !indexed.isEmpty()) {
                immutableSources.add(indexed);
            }
        }
        return new TargetCursor(
                immutableSources,
                checkedSource,
                checkedOrder,
                generation);
    }

    @Override
    public synchronized List<IndexedSessionCandidates> candidates(
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            ExternalOrderKey eventOrderKey) {
        List<IndexedSessionCandidates> result =
                new ArrayList<IndexedSessionCandidates>();
        try (CoordinationTargetCursor cursor = openCandidates(
                exactEventSubscriptionKeys,
                sourceChannel,
                eventOrderKey)) {
            while (!cursor.exhausted()) {
                result.addAll(cursor.nextPage(1024));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized int indexedOccurrenceCount() {
        NavigableSet<IndexedOccurrence> unique =
                new TreeSet<IndexedOccurrence>(OCCURRENCE_ORDER);
        for (NavigableSet<IndexedOccurrence> values
                : occurrencesBySubscriptionKey.values()) {
            unique.addAll(values);
        }
        return unique.size();
    }

    /** Returns current sessions registered under any supplied exact key. */
    public synchronized Set<DocumentSessionId> sessionsFor(
            List<String> subscriptionKeys) {
        NavigableSet<DocumentSessionId> result =
                new TreeSet<DocumentSessionId>(
                        new Comparator<DocumentSessionId>() {
                            @Override
                            public int compare(
                                    DocumentSessionId left,
                                    DocumentSessionId right) {
                                return compareText(
                                        left.value(), right.value());
                            }
                        });
        for (String key : Objects.requireNonNull(
                subscriptionKeys, "subscriptionKeys")) {
            NavigableSet<IndexedOccurrence> occurrences =
                    occurrencesBySubscriptionKey.get(key);
            if (occurrences != null) {
                for (IndexedOccurrence occurrence : occurrences) {
                    result.add(occurrence.sessionId);
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** Returns the exact current key surface for deterministic diagnostics. */
    public synchronized Set<String> subscriptionKeys() {
        return Collections.unmodifiableSet(
                new java.util.LinkedHashSet<String>(
                        occurrencesBySubscriptionKey.keySet()));
    }

    /**
     * Captures an immutable canonical view of every physical route row.
     *
     * @return rows and their content-only deterministic digest
     */
    public synchronized InMemoryCoordinationSubscriptionIndexSnapshot
            snapshot() {
        List<InMemoryCoordinationSubscriptionIndexSnapshot.Row> rows =
                new ArrayList<
                        InMemoryCoordinationSubscriptionIndexSnapshot.Row>();
        for (Map.Entry<String, NavigableSet<IndexedOccurrence>> entry
                : occurrencesBySubscriptionKey.entrySet()) {
            for (IndexedOccurrence occurrence : entry.getValue()) {
                rows.add(new InMemoryCoordinationSubscriptionIndexSnapshot.Row(
                        entry.getKey(),
                        occurrence.sessionId.value(),
                        occurrence.occurrenceKey,
                        occurrence.scopePath,
                        occurrence.order,
                        occurrence.channelKey,
                        occurrence.effectiveTypeBlueId,
                        occurrence.activationFrontier,
                        occurrence.plannedEpoch,
                        occurrence.plannedRootBlueId,
                        occurrence.subscriptionSnapshotIdentity));
            }
        }
        return new InMemoryCoordinationSubscriptionIndexSnapshot(
                generation, rows);
    }

    private static List<Registration> registrationsFor(
            ManagedDocumentSnapshot snapshot) {
        List<Registration> result = new ArrayList<Registration>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.subscriptions().occurrences()) {
            IndexedOccurrence indexed = new IndexedOccurrence(
                    snapshot, occurrence);
            for (String key : occurrence.subscriptionKeys()) {
                result.add(new Registration(key, indexed));
            }
        }
        return result;
    }

    private boolean removeInternal(DocumentSessionId sessionId) {
        List<Registration> previous = registrationsBySession.remove(sessionId);
        if (previous == null) {
            return false;
        }
        for (Registration registration : previous) {
            NavigableSet<IndexedOccurrence> current =
                    occurrencesBySubscriptionKey.get(
                            registration.subscriptionKey);
            if (current == null) {
                continue;
            }
            NavigableSet<IndexedOccurrence> replacement =
                    new TreeSet<IndexedOccurrence>(OCCURRENCE_ORDER);
            replacement.addAll(current);
            replacement.remove(registration.occurrence);
            if (replacement.isEmpty()) {
                occurrencesBySubscriptionKey.remove(
                        registration.subscriptionKey);
            } else {
                occurrencesBySubscriptionKey.put(
                        registration.subscriptionKey,
                        Collections.unmodifiableNavigableSet(replacement));
            }
        }
        return true;
    }

    private static int depth(String path) {
        return JsonPointer.split(path).size();
    }

    private static int compareText(String left, String right) {
        return ExternalOrderKey.compareTextCodePoints(left, right);
    }

    private static final class Registration {
        private final String subscriptionKey;
        private final IndexedOccurrence occurrence;

        private Registration(
                String subscriptionKey,
                IndexedOccurrence occurrence) {
            this.subscriptionKey = Objects.requireNonNull(
                    subscriptionKey, "subscriptionKey");
            this.occurrence = Objects.requireNonNull(
                    occurrence, "occurrence");
        }
    }

    private static final class IndexedOccurrence {
        private final DocumentSessionId sessionId;
        private final String occurrenceKey;
        private final String scopePath;
        private final int order;
        private final String channelKey;
        private final String effectiveTypeBlueId;
        private final ExternalOrderKey activationFrontier;
        private final long plannedEpoch;
        private final String plannedRootBlueId;
        private final String subscriptionSnapshotIdentity;

        private IndexedOccurrence(
                ManagedDocumentSnapshot snapshot,
                CoordinationSubscriptionOccurrence occurrence) {
            ManagedDocumentSnapshot checked = Objects.requireNonNull(
                    snapshot, "snapshot");
            this.sessionId = checked.sessionId();
            this.occurrenceKey = occurrence.occurrenceKey();
            this.scopePath = occurrence.scopePath();
            this.order = occurrence.order();
            this.channelKey = occurrence.channelKey();
            this.effectiveTypeBlueId = occurrence.effectiveTypeBlueId();
            this.activationFrontier = occurrence.activationFrontier();
            this.plannedEpoch = checked.currentEpoch();
            this.plannedRootBlueId = checked.currentRootBlueId();
            this.subscriptionSnapshotIdentity =
                    checked.subscriptions().digest();
        }
    }

    private static final class TargetCursor
            implements CoordinationTargetCursor {

        private final PriorityQueue<CursorHead> heads =
                new PriorityQueue<CursorHead>(
                        new Comparator<CursorHead>() {
                            @Override
                            public int compare(CursorHead left, CursorHead right) {
                                int compared = OCCURRENCE_ORDER.compare(
                                        left.value, right.value);
                                return compared != 0
                                        ? compared
                                        : Integer.compare(
                                                left.sourceOrdinal,
                                                right.sourceOrdinal);
                            }
                        });
        private final String sourceChannel;
        private final ExternalOrderKey eventOrderKey;
        private final long generation;
        private IndexedOccurrence buffered;
        private IndexedOccurrence lastReturned;
        private boolean exhausted;
        private boolean closed;

        private TargetCursor(
                List<NavigableSet<IndexedOccurrence>> sources,
                String sourceChannel,
                ExternalOrderKey eventOrderKey,
                long generation) {
            this.sourceChannel = sourceChannel;
            this.eventOrderKey = eventOrderKey;
            this.generation = generation;
            int ordinal = 0;
            for (NavigableSet<IndexedOccurrence> source : sources) {
                Iterator<IndexedOccurrence> iterator = source.iterator();
                if (iterator.hasNext()) {
                    heads.add(new CursorHead(
                            ordinal, iterator, iterator.next()));
                }
                ordinal++;
            }
            exhausted = heads.isEmpty();
        }

        @Override
        public List<IndexedSessionCandidates> nextPage(int maximumRoots) {
            if (maximumRoots <= 0) {
                throw new IllegalArgumentException(
                        "maximumRoots must be positive");
            }
            requireOpen();
            List<IndexedSessionCandidates> result =
                    new ArrayList<IndexedSessionCandidates>(maximumRoots);
            while (result.size() < maximumRoots) {
                List<IndexedOccurrence> session = nextSession();
                if (session == null) {
                    exhausted = true;
                    break;
                }
                IndexedSessionCandidates target = target(session);
                if (target != null) {
                    result.add(target);
                }
            }
            return Collections.unmodifiableList(result);
        }

        @Override
        public boolean exhausted() {
            return exhausted;
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public void close() {
            closed = true;
            heads.clear();
            buffered = null;
            exhausted = true;
        }

        private List<IndexedOccurrence> nextSession() {
            IndexedOccurrence first = buffered != null
                    ? takeBuffered()
                    : nextActiveUnique();
            if (first == null) {
                return null;
            }
            List<IndexedOccurrence> result =
                    new ArrayList<IndexedOccurrence>();
            result.add(first);
            while (true) {
                IndexedOccurrence next = nextActiveUnique();
                if (next == null) {
                    break;
                }
                if (!next.sessionId.equals(first.sessionId)) {
                    buffered = next;
                    break;
                }
                result.add(next);
            }
            return result;
        }

        private IndexedOccurrence takeBuffered() {
            IndexedOccurrence result = buffered;
            buffered = null;
            return result;
        }

        private IndexedOccurrence nextActiveUnique() {
            while (!heads.isEmpty()) {
                CursorHead head = heads.remove();
                IndexedOccurrence candidate = head.value;
                if (head.iterator.hasNext()) {
                    heads.add(new CursorHead(
                            head.sourceOrdinal,
                            head.iterator,
                            head.iterator.next()));
                }
                if (lastReturned != null
                        && OCCURRENCE_ORDER.compare(
                                lastReturned, candidate) == 0) {
                    continue;
                }
                lastReturned = candidate;
                if (candidate.activationFrontier != null
                        && eventOrderKey.compareTo(
                                candidate.activationFrontier) <= 0) {
                    continue;
                }
                return candidate;
            }
            return null;
        }

        private IndexedSessionCandidates target(
                List<IndexedOccurrence> occurrences) {
            boolean sourcePresent = false;
            List<String> keys = new ArrayList<String>(occurrences.size());
            int totalScopeDepth = 0;
            IndexedOccurrence first = occurrences.get(0);
            for (IndexedOccurrence occurrence : occurrences) {
                if (!samePlan(first, occurrence)) {
                    throw new IllegalStateException(
                            "one indexed session contains mixed generations: "
                                    + first.sessionId);
                }
                sourcePresent |= sourceChannel.equals(
                        occurrence.channelKey);
                keys.add(occurrence.occurrenceKey);
                totalScopeDepth = Math.addExact(
                        totalScopeDepth, depth(occurrence.scopePath));
            }
            return !sourcePresent
                    ? null
                    : new IndexedSessionCandidates(
                            first.sessionId,
                            keys,
                            totalScopeDepth,
                            first.plannedEpoch,
                            first.plannedRootBlueId,
                            first.subscriptionSnapshotIdentity);
        }

        private static boolean samePlan(
                IndexedOccurrence left,
                IndexedOccurrence right) {
            return left.plannedEpoch == right.plannedEpoch
                    && left.plannedRootBlueId.equals(
                            right.plannedRootBlueId)
                    && left.subscriptionSnapshotIdentity.equals(
                            right.subscriptionSnapshotIdentity);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("target cursor is closed");
            }
        }
    }

    private static final class CursorHead {
        private final int sourceOrdinal;
        private final Iterator<IndexedOccurrence> iterator;
        private final IndexedOccurrence value;

        private CursorHead(
                int sourceOrdinal,
                Iterator<IndexedOccurrence> iterator,
                IndexedOccurrence value) {
            this.sourceOrdinal = sourceOrdinal;
            this.iterator = iterator;
            this.value = value;
        }
    }
}
