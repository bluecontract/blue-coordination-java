package blue.coordination.processor;

import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.ManagedDocumentStatus;
import blue.coordination.engine.memory.InMemoryCoordinationSubscriptionIndex;
import blue.coordination.engine.spi.CoordinationTargetCursor;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InMemoryCoordinationSubscriptionIndexCursorTest {

    @Test
    void shouldPageCompleteRootsInCanonicalOrderFromAFrozenGeneration() {
        InMemoryCoordinationSubscriptionIndex index =
                new InMemoryCoordinationSubscriptionIndex();
        index.replaceSession(snapshot("session/c", Collections.singletonList("/")));
        index.replaceSession(snapshot(
                "session/a", Arrays.asList("/", "/nested/deep")));
        index.replaceSession(snapshot("session/b", Collections.singletonList("/")));

        try (CoordinationTargetCursor cursor = index.openCandidates(
                Collections.singletonList("timeline:key"), "owner", order(1))) {
            index.replaceSession(snapshot(
                    "session/d", Collections.singletonList("/")));

            List<IndexedSessionCandidates> first = cursor.nextPage(2);
            List<IndexedSessionCandidates> second = cursor.nextPage(2);
            cursor.nextPage(2);

            assertEquals(Arrays.asList("session/a", "session/b"),
                    sessionIds(first));
            assertEquals(Arrays.asList(
                            occurrence("session/a", "/nested/deep")
                                    .occurrenceKey(),
                            occurrence("session/a", "/").occurrenceKey()),
                    first.get(0).orderedOccurrenceKeys());
            assertEquals(Collections.singletonList("session/c"), sessionIds(second));
            assertTrue(cursor.exhausted());
            assertEquals(3L, cursor.generation());
        }

        try (CoordinationTargetCursor current = index.openCandidates(
                Collections.singletonList("timeline:key"), "owner", order(1))) {
            assertEquals(Arrays.asList(
                            "session/a", "session/b", "session/c", "session/d"),
                    sessionIds(current.nextPage(8)));
        }
    }

    @Test
    void shouldKeepEveryReturnedPageWithinTheConfiguredRootBound() {
        InMemoryCoordinationSubscriptionIndex index =
                new InMemoryCoordinationSubscriptionIndex();
        int sessionCount = 1024;
        for (int indexValue = sessionCount - 1;
                indexValue >= 0;
                indexValue--) {
            index.replaceSession(snapshot(
                    String.format("session/%04d", indexValue),
                    Collections.singletonList("/")));
        }

        int delivered = 0;
        int largestPage = 0;
        try (CoordinationTargetCursor cursor = index.openCandidates(
                Collections.singletonList("timeline:key"), "owner", order(1))) {
            while (!cursor.exhausted()) {
                List<IndexedSessionCandidates> page = cursor.nextPage(17);
                delivered = Math.addExact(delivered, page.size());
                largestPage = Math.max(largestPage, page.size());
            }
        }

        assertEquals(sessionCount, delivered);
        assertEquals(17, largestPage);
        assertFalse(index.candidates(
                Collections.singletonList("unrelated"),
                "owner",
                order(1)).iterator().hasNext());
    }

    private static ManagedDocumentSnapshot snapshot(
            String sessionId,
            List<String> paths) {
        List<CoordinationSubscriptionOccurrence> occurrences =
                new ArrayList<CoordinationSubscriptionOccurrence>();
        for (String path : paths) {
            occurrences.add(occurrence(sessionId, path));
        }
        String rootBlueId = "root/" + sessionId;
        CoordinationSubscriptionSnapshot subscriptions =
                new CoordinationSubscriptionSnapshot(
                        "language-runtime",
                        "coordination-runtime",
                        rootBlueId,
                        0L,
                        order(0),
                        occurrences,
                        Collections.<String, List<String>>emptyMap(),
                        Collections.<String>emptySet());
        return new ManagedDocumentSnapshot(
                DocumentSessionId.of(sessionId),
                "initial/" + sessionId,
                rootBlueId,
                0L,
                "environment",
                order(0),
                "inventory/" + sessionId,
                subscriptions,
                ManagedDocumentStatus.ACTIVE);
    }

    private static CoordinationSubscriptionOccurrence occurrence(
            String sessionId,
            String path) {
        LinkedHashMap<String, String> headerFields =
                new LinkedHashMap<String, String>();
        headerFields.put("timeline", "timeline-header");
        return new CoordinationSubscriptionOccurrence(
                path,
                "scope/" + sessionId + path,
                "/",
                "/".equals(path)
                        ? CoordinationSubscriptionOccurrence.Origin.ROOT
                        : CoordinationSubscriptionOccurrence.Origin.EXPLICIT,
                "/".equals(path) ? null : path,
                null,
                null,
                "owner",
                Collections.singletonList("source/" + sessionId + path),
                "type/owner",
                0,
                "checkpoint/" + sessionId + path,
                "header/" + sessionId + path,
                headerFields,
                Collections.singletonList("timeline:key"),
                Long.valueOf(0L),
                order(0),
                null,
                ExternalChannelDependencySnapshot.none());
    }

    private static List<String> sessionIds(
            List<IndexedSessionCandidates> candidates) {
        List<String> result = new ArrayList<String>();
        for (IndexedSessionCandidates candidate : candidates) {
            result.add(candidate.sessionId().value());
        }
        return result;
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(
                Collections.<Object>singletonList(BigInteger.valueOf(value)));
    }
}
