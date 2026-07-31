package blue.coordination.processor;

import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationSubscriptionPersistenceTest {

    @Test
    void shouldExposeOnlyDeeplyImmutableSnapshotPersistenceValues() {
        // Given
        CoordinationSubscriptionSnapshot snapshot =
                snapshot(false);

        // When
        Map<String, Object> persisted =
                snapshot.toMap();

        // Then
        assertDeeplyUnmodifiable(persisted);
        assertEquals(
                persisted,
                snapshot.toMap());
    }

    @Test
    void shouldKeepUpdateViewsDetachedFromMutableInputLists() {
        // Given
        CoordinationSubscriptionSnapshot snapshot =
                snapshot(false);
        CoordinationSubscriptionOccurrence occurrence =
                snapshot.occurrences().get(0);
        List<CoordinationSubscriptionOccurrence> added =
                new ArrayList<
                        CoordinationSubscriptionOccurrence>();
        added.add(occurrence);
        List<CoordinationSubscriptionOccurrence> retired =
                new ArrayList<
                        CoordinationSubscriptionOccurrence>();
        List<CoordinationSubscriptionOccurrence> unchanged =
                new ArrayList<
                        CoordinationSubscriptionOccurrence>();
        CoordinationSubscriptionUpdate update =
                new CoordinationSubscriptionUpdate(
                        snapshot,
                        added,
                        retired,
                        unchanged,
                        order(4));

        // When
        added.clear();
        retired.add(occurrence);
        unchanged.add(occurrence);

        // Then
        assertEquals(1, update.added().size());
        assertTrue(update.retired().isEmpty());
        assertTrue(update.unchanged().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> update.added().clear());
    }

    @Test
    void shouldRejectUnknownPersistedSnapshotFields() {
        // Given
        Map<String, Object> persisted =
                mutableSnapshot(false);
        persisted.put("unexpected", "value");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationSubscriptionSnapshot
                                .rehydrate(persisted));

        // Then
        assertUnknownField(failure);
    }

    @Test
    void shouldRejectUnknownPersistedOccurrenceFields() {
        // Given
        Map<String, Object> persisted =
                mutableSnapshot(false);
        firstOccurrence(persisted)
                .put("unexpected", "value");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationSubscriptionSnapshot
                                .rehydrate(persisted));

        // Then
        assertUnknownField(failure);
    }

    @Test
    void shouldRejectUnknownPersistedDependencyFields() {
        // Given
        Map<String, Object> persisted =
                mutableSnapshot(false);
        dependencies(persisted)
                .put("unexpected", "value");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationSubscriptionSnapshot
                                .rehydrate(persisted));

        // Then
        assertUnknownField(failure);
    }

    @Test
    void shouldRejectUnknownPersistedDependencyEntryFields() {
        // Given
        Map<String, Object> persisted =
                mutableSnapshot(false);
        firstObject(
                dependencies(persisted),
                "entries").put(
                        "unexpected",
                        "value");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationSubscriptionSnapshot
                                .rehydrate(persisted));

        // Then
        assertUnknownField(failure);
    }

    @Test
    void shouldRejectUnknownPersistedTypeFamilyFields() {
        // Given
        Map<String, Object> persisted =
                mutableSnapshot(false);
        firstObject(
                dependencies(persisted),
                "typeFamilies").put(
                        "unexpected",
                        "value");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationSubscriptionSnapshot
                                .rehydrate(persisted));

        // Then
        assertUnknownField(failure);
    }

    @Test
    void shouldRejectUnknownPersistedTypeFamilyMemberFields() {
        // Given
        Map<String, Object> persisted =
                mutableSnapshot(false);
        Map<String, Object> family =
                firstObject(
                        dependencies(persisted),
                        "typeFamilies");
        firstObject(family, "members")
                .put("unexpected", "value");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationSubscriptionSnapshot
                                .rehydrate(persisted));

        // Then
        assertUnknownField(failure);
    }

    @Test
    void shouldRejectUnknownPersistedChannelEntryFields() {
        // Given
        Map<String, Object> persisted =
                mutableSnapshot(false);
        firstObject(
                dependencies(persisted),
                "channelEntries").put(
                        "unexpected",
                        "value");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationSubscriptionSnapshot
                                .rehydrate(persisted));

        // Then
        assertUnknownField(failure);
    }

    @Test
    void shouldRejectExplicitNullForOptionalOccurrenceFields() {
        // Given
        Map<String, Object> persisted =
                mutableSnapshot(false);
        firstOccurrence(persisted)
                .put("endAtRootRevision", null);

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationSubscriptionSnapshot
                                .rehydrate(persisted));

        // Then
        assertTrue(
                failure.getMessage().contains(
                        "must be omitted rather than null"),
                failure.getMessage());
    }

    @Test
    void shouldRejectNonCanonicalPersistedOccurrenceOrder() {
        // Given
        Map<String, Object> persisted =
                mutableSnapshot(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> occurrences =
                (List<Map<String, Object>>)
                        persisted.get("occurrences");
        Collections.reverse(occurrences);

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationSubscriptionSnapshot
                                .rehydrate(persisted));

        // Then
        assertTrue(
                failure.getMessage().contains(
                        "not canonically ordered"),
                failure.getMessage());
    }

    @Test
    void shouldRejectNonCanonicalPersistedScopePaths() {
        // Given
        Map<String, Object> persisted =
                mutableSnapshot(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> occurrences =
                (List<Map<String, Object>>)
                        persisted.get("occurrences");
        occurrences.get(1).put(
                "scopePath",
                "child");

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationSubscriptionSnapshot
                                .rehydrate(persisted));

        // Then
        assertTrue(
                failure.getMessage().contains(
                        "scopePath must be canonical"),
                failure.getMessage());
    }

    private static CoordinationSubscriptionSnapshot snapshot(
            boolean includeSecondOccurrence) {
        ExternalChannelDependencySnapshot dependencies =
                dependencies();
        List<CoordinationSubscriptionOccurrence> occurrences =
                new ArrayList<
                        CoordinationSubscriptionOccurrence>();
        occurrences.add(
                occurrence(
                        "/",
                        "root-scope",
                        "source",
                        0,
                        dependencies));
        if (includeSecondOccurrence) {
            occurrences.add(
                    occurrence(
                            "/child",
                            "child-scope",
                            "child-source",
                            1,
                            dependencies));
        }
        Map<String, List<String>> routes =
                new LinkedHashMap<String, List<String>>();
        routes.put(
                "/contracts/embedded",
                Collections.singletonList("/child"));
        Set<String> pruned =
                new LinkedHashSet<String>();
        pruned.add("/terminated");
        return new CoordinationSubscriptionSnapshot(
                "language-runtime",
                "coordination-runtime",
                "root-blue-id",
                4L,
                order(4),
                occurrences,
                routes,
                pruned);
    }

    private static CoordinationSubscriptionOccurrence occurrence(
            String scopePath,
            String scopeBlueId,
            String channelKey,
            int order,
            ExternalChannelDependencySnapshot dependencies) {
        Map<String, String> headerFields =
                new LinkedHashMap<String, String>();
        headerFields.put(
                "timeline",
                "timeline-header-blue-id");
        return new CoordinationSubscriptionOccurrence(
                scopePath,
                scopeBlueId,
                channelKey,
                Collections.singletonList(
                        channelKey + "-contribution"),
                channelKey + "-type",
                order,
                channelKey + "-checkpoint-domain",
                channelKey + "-header",
                headerFields,
                Collections.singletonList(
                        "timeline:" + channelKey),
                Long.valueOf(4L),
                order(4),
                null,
                dependencies);
    }

    private static ExternalChannelDependencySnapshot dependencies() {
        ExternalChannelDependencySnapshot.Entry entry =
                new ExternalChannelDependencySnapshot.Entry(
                        "peer",
                        1,
                        "peer-type",
                        Collections.singletonList(
                                "peer-contribution"),
                        Collections.singletonList(
                                "peer-dependency"),
                        "peer-checkpoint-domain");
        ExternalChannelDependencySnapshot.Member member =
                new ExternalChannelDependencySnapshot.Member(
                        "family-member",
                        2,
                        "family-member-type",
                        Collections.singletonList(
                                "family-contribution"),
                        Collections.singletonList(
                                "family-dependency"));
        ExternalChannelDependencySnapshot.TypeFamily family =
                new ExternalChannelDependencySnapshot.TypeFamily(
                        "source",
                        "family-base-type",
                        ExternalChannelDependencySnapshot
                                .TypeMatchMode.ASSIGNABLE,
                        Collections.singletonList(member));
        ExternalChannelDependencySnapshot.ChannelEntry channel =
                new ExternalChannelDependencySnapshot.ChannelEntry(
                        "target",
                        3,
                        "target-type",
                        EffectiveContractSnapshotConstants
                                .Role.PROCESSOR_CHANNEL,
                        Collections.singletonList(
                                "target-contribution"),
                        Collections.singletonList(
                                "target-dependency"),
                        "target-header");
        return new ExternalChannelDependencySnapshot(
                Collections.singletonList(
                        "intrinsic-dependency"),
                Collections.singletonList(entry),
                Collections.singletonList(family),
                true,
                Collections.singletonList(channel),
                true,
                Arrays.asList(
                        "target",
                        "unrelated"));
    }

    private static ExternalOrderKey order(
            long value) {
        return ExternalOrderKey.of(
                Collections.singletonList(
                        BigInteger.valueOf(value)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableSnapshot(
            boolean includeSecondOccurrence) {
        return (Map<String, Object>)
                mutableCopy(
                        snapshot(includeSecondOccurrence)
                                .toMap());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstOccurrence(
            Map<String, Object> persisted) {
        return ((List<Map<String, Object>>)
                persisted.get("occurrences")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dependencies(
            Map<String, Object> persisted) {
        return (Map<String, Object>)
                firstOccurrence(persisted)
                        .get("dependencies");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstObject(
            Map<String, Object> owner,
            String key) {
        return ((List<Map<String, Object>>)
                owner.get(key)).get(0);
    }

    private static Object mutableCopy(
            Object value) {
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
            List<Object> result =
                    new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(mutableCopy(item));
            }
            return result;
        }
        return value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertDeeplyUnmodifiable(
            Object value) {
        if (value instanceof Map) {
            Map map = (Map) value;
            for (Object child
                    : new ArrayList<Object>(
                            map.values())) {
                assertDeeplyUnmodifiable(child);
            }
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> map.put(
                            "__mutation__",
                            Boolean.TRUE));
        } else if (value instanceof List) {
            List list = (List) value;
            for (Object child
                    : new ArrayList<Object>(list)) {
                assertDeeplyUnmodifiable(child);
            }
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> list.add("__mutation__"));
        }
    }

    private static void assertUnknownField(
            IllegalArgumentException failure) {
        assertTrue(
                failure.getMessage().contains(
                        "unknown field 'unexpected'"),
                failure.getMessage());
    }
}
