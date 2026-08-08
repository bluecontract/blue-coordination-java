package blue.coordination.processor;

import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationSubscriptionProvenancePersistenceTest {

    @Test
    void shouldRoundTripExactCollectionMemberProvenanceInSchemaTwo() {
        // given
        CoordinationSubscriptionSnapshot snapshot = snapshot(
                collectionOccurrence("lesson/key~v"));

        // when
        Map<String, Object> persisted = snapshot.toMap();
        CoordinationSubscriptionSnapshot rehydrated =
                CoordinationSubscriptionSnapshot.rehydrate(persisted);
        CoordinationSubscriptionOccurrence occurrence =
                rehydrated.occurrences().get(0);

        // then
        assertEquals(
                "blue.coordination/subscription-snapshot/3.0",
                rehydrated.projectionVersion());
        assertEquals("/", occurrence.declaringScopePath());
        assertEquals(
                CoordinationSubscriptionOccurrence
                        .Origin.COLLECTION_MEMBER,
                occurrence.origin());
        assertNull(occurrence.explicitDeclarationPath());
        assertEquals(
                "/lessons",
                occurrence.collectionDeclarationPath());
        assertEquals(
                "lesson/key~v",
                occurrence.collectionMemberKey());
        assertEquals(
                "/lessons/lesson~1key~0v",
                occurrence.scopePath());
        assertEquals(snapshot.toMap(), rehydrated.toMap());
    }

    @Test
    void shouldIncludeDeclarationOriginInSnapshotDigest() {
        // given
        CoordinationSubscriptionOccurrence explicit = occurrence(
                "/lessons/a",
                "/",
                CoordinationSubscriptionOccurrence.Origin.EXPLICIT,
                "/lessons/a",
                null,
                null);
        CoordinationSubscriptionOccurrence collection = occurrence(
                "/lessons/a",
                "/",
                CoordinationSubscriptionOccurrence
                        .Origin.COLLECTION_MEMBER,
                null,
                "/lessons",
                "a");

        // when
        CoordinationSubscriptionSnapshot explicitSnapshot =
                snapshot(explicit);
        CoordinationSubscriptionSnapshot collectionSnapshot =
                snapshot(collection);

        // then
        assertEquals(
                explicit.occurrenceKey(),
                collection.occurrenceKey());
        assertNotEquals(
                explicitSnapshot.digest(),
                collectionSnapshot.digest());
    }

    @Test
    void shouldRoundTripEmptyCollectionMemberKeyExactly() {
        // given
        CoordinationSubscriptionSnapshot snapshot = snapshot(
                collectionOccurrence(""));

        // when
        CoordinationSubscriptionOccurrence occurrence =
                CoordinationSubscriptionSnapshot
                        .rehydrate(snapshot.toMap())
                        .occurrences()
                        .get(0);

        // then
        assertEquals("", occurrence.collectionMemberKey());
        assertEquals("/lessons/", occurrence.scopePath());
    }

    @Test
    void shouldPreserveCollectionProvenanceWhenIntervalIsRetired() {
        // given
        CoordinationSubscriptionOccurrence active =
                collectionOccurrence("a");
        SubscriptionDelta.Entry entry =
                active.toSubscriptionDeltaEntry();
        SubscriptionDelta.Entry retiredEntry =
                new SubscriptionDelta.Entry(
                        entry.scopePath(),
                        entry.channelKey(),
                        entry.effectiveTypeBlueId(),
                        entry.sourceContributionNodeBlueIds(),
                        entry.order(),
                        entry.subscriptionKeys(),
                        entry.checkpointDomainBlueId(),
                        entry.dependencies(),
                        entry.activationRootRevision(),
                        entry.startAfterExternalOrderKey(),
                        Long.valueOf(8L));

        // when
        CoordinationSubscriptionOccurrence retired =
                active.withScopeAndInterval(
                        active.scopeBlueId(),
                        retiredEntry);

        // then
        assertEquals(active.declaringScopePath(),
                retired.declaringScopePath());
        assertEquals(active.origin(), retired.origin());
        assertEquals(active.collectionDeclarationPath(),
                retired.collectionDeclarationPath());
        assertEquals(active.collectionMemberKey(),
                retired.collectionMemberKey());
        assertEquals(Long.valueOf(8L),
                retired.endAtRootRevision());
    }

    @Test
    void shouldRejectUnknownPersistedScopeOrigin() {
        // given
        Map<String, Object> persisted = mutableSnapshot(
                collectionOccurrence("a"));
        firstOccurrence(persisted).put(
                "origin",
                "GENERATED_BY_GUESSING");

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CoordinationSubscriptionSnapshot
                        .rehydrate(persisted));

        // then
        assertTrue(
                failure.getMessage().contains(
                        "Unsupported subscription occurrence origin"),
                failure.getMessage());
    }

    @Test
    void shouldRejectCollectionOccurrenceWithExplicitDeclarationField() {
        // given
        Map<String, Object> persisted = mutableSnapshot(
                collectionOccurrence("a"));
        firstOccurrence(persisted).put(
                "explicitDeclarationPath",
                "/lessons/a");

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CoordinationSubscriptionSnapshot
                        .rehydrate(persisted));

        // then
        assertTrue(
                failure.getMessage().contains(
                        "COLLECTION_MEMBER occurrence has inconsistent"),
                failure.getMessage());
    }

    @Test
    void shouldRejectCollectionMemberKeyThatDoesNotSelectScopePath() {
        // given
        Map<String, Object> persisted = mutableSnapshot(
                collectionOccurrence("a"));
        firstOccurrence(persisted).put(
                "collectionMemberKey",
                "different");

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CoordinationSubscriptionSnapshot
                        .rehydrate(persisted));

        // then
        assertTrue(
                failure.getMessage().contains(
                        "COLLECTION_MEMBER occurrence has inconsistent"),
                failure.getMessage());
    }

    @Test
    void shouldRejectPreviousSubscriptionSchemaBeforeReadingOccurrences() {
        // given
        Map<String, Object> persisted = mutableSnapshot(
                collectionOccurrence("a"));
        persisted.put(
                "projectionVersion",
                "blue.coordination/subscription-snapshot/1.0");
        firstOccurrence(persisted).remove("declaringScopePath");

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CoordinationSubscriptionSnapshot
                        .rehydrate(persisted));

        // then
        assertTrue(
                failure.getMessage().contains(
                        "Unsupported Coordination projection version"),
                failure.getMessage());
    }

    @Test
    void shouldPersistNoExecutableBodyContentWithScopeProvenance() {
        // given
        CoordinationSubscriptionSnapshot snapshot = snapshot(
                collectionOccurrence("a"));

        // when
        String persistenceText = snapshot.toMap().toString();

        // then
        assertFalse(persistenceText.contains("workflow"));
        assertFalse(persistenceText.contains("JavaScript"));
        assertFalse(persistenceText.contains("executableBody"));
    }

    private static CoordinationSubscriptionOccurrence
    collectionOccurrence(String memberKey) {
        return occurrence(
                "/lessons/"
                        + blue.language.model.wire.JsonPointer
                                .escape(memberKey),
                "/",
                CoordinationSubscriptionOccurrence
                        .Origin.COLLECTION_MEMBER,
                null,
                "/lessons",
                memberKey);
    }

    private static CoordinationSubscriptionOccurrence occurrence(
            String scopePath,
            String declaringScopePath,
            CoordinationSubscriptionOccurrence.Origin origin,
            String explicitDeclarationPath,
            String collectionDeclarationPath,
            String collectionMemberKey) {
        Map<String, String> headerFields =
                new LinkedHashMap<String, String>();
        headerFields.put("timeline", "timeline-header");
        return new CoordinationSubscriptionOccurrence(
                scopePath,
                "scope-blue-id",
                declaringScopePath,
                origin,
                explicitDeclarationPath,
                collectionDeclarationPath,
                collectionMemberKey,
                "channel",
                Collections.singletonList("source-contribution"),
                "channel-type",
                0,
                "checkpoint-domain",
                "header-identity",
                headerFields,
                Collections.singletonList("timeline:key"),
                Long.valueOf(4L),
                order(4L),
                null,
                ExternalChannelDependencySnapshot.none());
    }

    private static CoordinationSubscriptionSnapshot snapshot(
            CoordinationSubscriptionOccurrence occurrence) {
        return new CoordinationSubscriptionSnapshot(
                "language-runtime",
                "coordination-runtime",
                "root-blue-id",
                4L,
                order(4L),
                Collections.singletonList(occurrence),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptySet());
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(
                Collections.singletonList(
                        BigInteger.valueOf(value)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableSnapshot(
            CoordinationSubscriptionOccurrence occurrence) {
        return (Map<String, Object>) mutableCopy(
                snapshot(occurrence).toMap());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstOccurrence(
            Map<String, Object> persisted) {
        return ((List<Map<String, Object>>)
                persisted.get("occurrences")).get(0);
    }

    private static Object mutableCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result =
                    new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry
                    : ((Map<?, ?>) value).entrySet()) {
                result.put(
                        (String) entry.getKey(),
                        mutableCopy(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object child : (List<?>) value) {
                result.add(mutableCopy(child));
            }
            return result;
        }
        return value;
    }
}
