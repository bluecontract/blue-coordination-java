package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LogicalRecordBucketsTest {
    private static final Bytes SCOPE = new Bytes(new byte[] {3});
    private static final Bytes EVIDENCE = new Bytes(new byte[] {4});
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(8192, 1024, 4096, 256, 8);

    @Test void twoMembersOfTheSamePreviouslyAbsentBucketPublishIndependentlyInBothOrders() {
        // given
        for (boolean reverse : List.of(false, true)) {
            var store = new LogicalRecordMapTest.Store();
            var a = store.attempt(); var b = store.attempt(); var ca = new LogicalRecordContext(a); var cb = new LogicalRecordContext(b);
            var left = buckets(ca); var right = buckets(cb);
            // when
            left.put("same-blue-id", left.get("same-blue-id").put("a", "a1").map()).map().selectLogicalRecords();
            right.put("same-blue-id", right.get("same-blue-id").put("b", "b1").map()).map().selectLogicalRecords();
            ca.flush(); cb.flush(); var pa = a.prepare("a", List.of(), EVIDENCE); var pb = b.prepare("b", List.of(), EVIDENCE);
            // then
            assertEquals(1, pa.mutations().size()); assertEquals(1, pb.mutations().size());
            assertTrue(pa.queries().isEmpty()); assertTrue(pb.queries().isEmpty());
            assertTrue(store.publish(reverse ? pb : pa)); assertTrue(store.publish(reverse ? pa : pb));
            try (var cold = store.attempt()) {
                var root = buckets(new LogicalRecordContext(cold));
                assertEquals(List.of("same-blue-id"), root.keys());
                assertEquals(List.of("a1", "b1"), root.get("same-blue-id").values());
                assertFalse(root.containsKey("absent")); assertTrue(root.get("absent").isEmpty());
            }
        }
    }

    @Test void removingLastObservedMemberDoesNotDeleteAnotherConcurrentInsertion() {
        // given
        var store = new LogicalRecordMapTest.Store(); insert(store, "seed", "same", "a", "a0");
        var remove = store.attempt(); var insert = store.attempt();
        var cr = new LogicalRecordContext(remove); var ci = new LogicalRecordContext(insert);
        var removals = buckets(cr); var additions = buckets(ci);
        // when
        removals.put("same", removals.get("same").remove("a").map()).map().selectLogicalRecords();
        additions.put("same", additions.get("same").put("b", "b1").map()).map().selectLogicalRecords();
        cr.flush(); ci.flush(); var pr = remove.prepare("remove", List.of(), EVIDENCE); var pi = insert.prepare("insert", List.of(), EVIDENCE);
        // then
        assertTrue(pr.queries().isEmpty()); assertTrue(pi.queries().isEmpty());
        assertTrue(store.publish(pi)); assertTrue(store.publish(pr));
        try (var cold = store.attempt()) {
            assertEquals(List.of("b"), buckets(new LogicalRecordContext(cold)).get("same").keys());
        }
    }

    @Test void semanticWholeBucketReadRejectsNewMemberButIgnoresOtherBuckets() {
        // given
        var store = new LogicalRecordMapTest.Store(); insert(store, "seed", "same", "a", "a0");
        var read = store.attempt(); var context = new LogicalRecordContext(read); var root = buckets(context);
        // when
        assertEquals(List.of("a0"), root.get("same").values()); context.flush(); var packet = read.prepare("read", List.of(), EVIDENCE);
        insert(store, "unrelated", "other", "b", "b0");
        // then
        assertTrue(store.publish(packet));
        insert(store, "related", "same", "c", "c0"); assertFalse(store.publish(packet));
    }

    @Test void foreignBucketCannotPublishUnderAnotherOwnerEvenWhenEmpty() {
        // given
        var store = new LogicalRecordMapTest.Store(); var attempt = store.attempt(); var context = new LogicalRecordContext(attempt);
        var root = buckets(context);
        // when
        assertThrows(IllegalArgumentException.class, () -> root.put("a", root.get("b")).map().selectLogicalRecords());
        // then
        assertThrows(IllegalStateException.class, () -> attempt.prepare("foreign", List.of(), EVIDENCE));
    }

    @Test void removingBucketVirtuallyHidesEmptyMembershipFromOrderedEnumeration() {
        // given
        var store = new LogicalRecordMapTest.Store(); insert(store, "seed", "same", "a", "a0"); insert(store, "later", "z", "b", "b0");
        var attempt = store.attempt(); var context = new LogicalRecordContext(attempt); var root = buckets(context);
        // when
        var changed = root.put("same", root.get("same").remove("a").map()).map();
        // then
        assertFalse(changed.containsKey("same")); assertEquals(List.of("z"), changed.keys());
        assertEquals("z", changed.minimum().entry().getKey()); assertEquals("z", changed.range(null, null).next().getKey());
        assertEquals(List.of("same", "z"), root.keys());
    }

    private static PersistentOrderedMap<String, PersistentOrderedMap<String, String>> buckets(LogicalRecordContext context) {
        return new LogicalRecordBuckets<>(EmbeddingBinding.TEXT_ORDER, EmbeddingBinding.TEXT_ORDER, context,
                Family.LINEAGE_CURRENT, SCOPE, OrderedRecordKey.text(), OrderedRecordKey.text(), OrderedRecordKey.text(), LIMITS).open();
    }
    private static void insert(LogicalRecordMapTest.Store store, String id, String owner, String member, String value) {
        var attempt = store.attempt(); var context = new LogicalRecordContext(attempt); var root = buckets(context);
        root.put(owner, root.get(owner).put(member, value).map()).map().selectLogicalRecords(); context.flush();
        assertTrue(store.publish(attempt.prepare(id, List.of(), EVIDENCE)));
    }
}
