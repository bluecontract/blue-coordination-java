package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for exact whole-object retention and rollback visibility. */
final class WholeObjectStoreTest {
    @Test
    void insertionAndReadsRetainDetachedExactBodies() {
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore store = new WholeObjectStore(metrics);
        Node authored = new Node().properties(
                "status", new Node().value("authored"));
        ExactValue retained = store.put(authored, "test object");
        authored.getProperties().get("status").value("mutated");

        assertTrue(store.contains(retained.blueId()));
        assertEquals("authored", store.require(retained.blueId()).copyNode()
                .getProperties().get("status").getValue());
        List<Node> provider = store.fetchByBlueId(retained.blueId());
        provider.get(0).getProperties().get("status").value("provider-copy");
        assertEquals("authored", store.fetchByBlueId(retained.blueId()).get(0)
                .getProperties().get("status").getValue());
        assertEquals(1L, metrics.counter("wholeObjectStore.insertions"));
        assertEquals(1L, metrics.counter(
                "wholeObjectStore.purpose.test_object"));
    }

    @Test
    void duplicateIdentityDoesNotIncreaseStoreSize() {
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore store = new WholeObjectStore(metrics);
        ExactValue value = ExactValue.verified(new Node().value("same"));

        store.put(value, "first");
        store.put(ExactValue.verified(new Node().value("same")), "second");

        assertEquals(1, store.size());
        assertEquals(1L, metrics.counter(
                "wholeObjectStore.representationVariants"));
    }

    @Test
    void rollbackRestoresProviderAndCanonicalVisibility() {
        WholeObjectStore store = new WholeObjectStore(new EngineMetrics());
        ExactValue retained = ExactValue.verified(new Node().value("later"));
        WholeObjectStore.Mark before = store.mark();
        store.put(retained, "later");
        assertEquals(1, store.size());

        store.rollbackTo(before);

        assertEquals(0, store.size());
        assertFalse(store.contains(retained.blueId()));
        assertTrue(store.fetchByBlueId(retained.blueId()).isEmpty());
    }

    @Test
    void snapshotsAreImmutableAndUnknownObjectsFailClearly() {
        WholeObjectStore store = new WholeObjectStore(new EngineMetrics());
        ExactValue value = store.put(new Node().value("known"), "known");

        assertEquals(value, store.snapshot().get(value.blueId()));
        assertThrows(UnsupportedOperationException.class,
                () -> store.snapshot().clear());
        assertThrows(IllegalArgumentException.class,
                () -> store.require("missing"));
    }

    @Test
    void providerPreferenceRejectsUnknownAndReferenceOnlyValues() {
        WholeObjectStore store = new WholeObjectStore(new EngineMetrics());
        ExactValue known = store.put(new Node().value("known"), "known");

        assertThrows(IllegalStateException.class,
                () -> store.preferProviderRepresentation(
                        ExactValue.verified(new Node().value("other")).frozen(),
                        "unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> store.preferProviderRepresentation(
                        ExactValue.verified(
                                new Node().blueId(known.blueId())).frozen(),
                        "reference"));
    }
}
