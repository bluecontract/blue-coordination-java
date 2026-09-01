package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.language.api.NodeProviderOutcome;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicSetProof;
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
        // given
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore store = new WholeObjectStore(metrics);
        Node authored = new Node().properties(
                "status", new Node().value("authored"));

        // when
        ExactValue retained = store.put(authored, "test object");
        authored.getProperties().get("status").value("mutated");
        List<Node> provider = store.fetchByBlueId(retained.blueId());
        provider.get(0).getProperties().get("status").value("provider-copy");

        // then
        assertTrue(store.contains(retained.blueId()));
        assertEquals("authored", store.require(retained.blueId()).copyNode()
                .getProperties().get("status").getValue());
        assertEquals("authored", store.fetchByBlueId(retained.blueId()).get(0)
                .getProperties().get("status").getValue());
        assertEquals(1L, metrics.counter("wholeObjectStore.insertions"));
        assertEquals(1L, metrics.counter(
                "wholeObjectStore.purpose.test_object"));
    }

    @Test
    void duplicateIdentityDoesNotIncreaseStoreSize() {
        // given
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore store = new WholeObjectStore(metrics);
        ExactValue value = ExactValue.verified(new Node().value("same"));

        // when
        store.put(value, "first");
        store.put(ExactValue.verified(new Node().value("same")), "second");

        // then
        assertEquals(1, store.size());
        assertEquals(1L, metrics.counter(
                "wholeObjectStore.representationVariants"));
    }

    @Test
    void rollbackRestoresProviderAndCanonicalVisibility() {
        // given
        WholeObjectStore store = new WholeObjectStore(new EngineMetrics());
        ExactValue retained = ExactValue.verified(new Node().value("later"));
        WholeObjectStore.Mark before = store.mark();
        store.put(retained, "later");
        int sizeBeforeRollback = store.size();

        // when
        store.rollbackTo(before);

        // then
        assertEquals(1, sizeBeforeRollback);
        assertEquals(0, store.size());
        assertFalse(store.contains(retained.blueId()));
        assertTrue(store.fetchByBlueId(retained.blueId()).isEmpty());
    }

    @Test
    void cyclicProviderBodyAndProofShareTheSameSavepoint() {
        // given
        WholeObjectStore store = new WholeObjectStore(new EngineMetrics());
        BasicNodeProvider source = cyclicProvider();
        String blueId = source.getBlueIdByName("store-cycle-a");
        Node providerBody = source.fetchByBlueId(blueId).get(0);
        CyclicSetProof proof = source.cyclicSetProofFor(blueId)
                .proof().orElseThrow();
        ExactValue verified = ExactValue.fromVerifiedProviderEvidence(
                blueId, providerBody, proof);
        store.put(verified, "normalized-only");

        // when
        boolean normalizedBodyWasHidden = store.fetchByBlueId(blueId)
                .isEmpty();
        WholeObjectStore.Mark beforeEvidence = store.mark();
        store.putVerifiedProviderEvidence(
                verified, providerBody, proof, "cyclic-evidence");
        boolean evidenceWasReadable = store.hasVerifiedContentForBlueId(
                blueId);
        Node detachedRead = store.fetchByBlueId(blueId).get(0);
        detachedRead.name("mutated-copy");
        String retainedName = store.fetchByBlueId(blueId).get(0).getName();
        store.rollbackTo(beforeEvidence);

        // then
        assertTrue(normalizedBodyWasHidden,
                "a cyclic ExactValue is not a wire-preserving provider body");
        assertTrue(evidenceWasReadable);
        assertEquals("store-cycle-a", retainedName);
        assertTrue(store.fetchByBlueId(blueId).isEmpty());
        assertFalse(store.hasVerifiedContentForBlueId(blueId));
        assertEquals(NodeProviderOutcome.NOT_FOUND,
                store.cyclicSetProofFor(blueId).outcome());
    }

    @Test
    void savepointsJournalOnlyChangedKeysAndNestInConstantStartTime() {
        // given
        WholeObjectStore store = new WholeObjectStore(new EngineMetrics());
        for (int index = 0; index < 100; index++) {
            store.put(new Node().value("existing-" + index), "existing");
        }

        // when
        WholeObjectStore.Mark outer = store.mark();
        ExactValue retained = store.put(
                new Node().value("outer-change"), "outer");
        int outerChangedKeyCount = outer.changedKeyCount();
        WholeObjectStore.Mark inner = store.mark();
        ExactValue rolledBack = store.put(
                new Node().value("inner-change"), "inner");
        int innerChangedKeyCount = inner.changedKeyCount();
        store.rollbackTo(inner);
        boolean retainedAfterInnerRollback = store.contains(retained.blueId());
        boolean removedAfterInnerRollback = !store.contains(
                rolledBack.blueId());
        store.commit(outer);

        // then
        assertEquals(1, outerChangedKeyCount,
                "a mark must not copy the hundred existing objects");
        assertEquals(1, innerChangedKeyCount);
        assertTrue(retainedAfterInnerRollback);
        assertTrue(removedAfterInnerRollback);
        assertTrue(store.contains(retained.blueId()));
        assertEquals(101, store.size());
    }

    @Test
    void exactReadsAreDetachedAndUnknownObjectsFailClearly() {
        // given
        WholeObjectStore store = new WholeObjectStore(new EngineMetrics());
        ExactValue value = store.put(new Node().value("known"), "known");

        // when
        Node detached = store.require(value.blueId()).copyNode();
        detached.value("changed");

        // then
        assertEquals("known", store.require(value.blueId())
                .copyNode().getValue());
        assertThrows(IllegalArgumentException.class,
                () -> store.require("missing"));
    }

    @Test
    void providerPreferenceRejectsUnknownAndReferenceOnlyValues() {
        // given
        WholeObjectStore store = new WholeObjectStore(new EngineMetrics());
        ExactValue known = store.put(new Node().value("known"), "known");
        ExactValue unknown = ExactValue.verified(new Node().value("other"));
        ExactValue reference = ExactValue.verified(
                new Node().blueId(known.blueId()));

        // when
        IllegalStateException unknownFailure = assertThrows(
                IllegalStateException.class,
                () -> store.preferProviderRepresentation(
                        unknown.frozen(),
                        "unknown"));
        IllegalArgumentException referenceFailure = assertThrows(
                IllegalArgumentException.class,
                () -> store.preferProviderRepresentation(
                        reference.frozen(),
                        "reference"));

        // then
        assertFalse(unknownFailure.getMessage().isBlank());
        assertFalse(referenceFailure.getMessage().isBlank());
    }

    @Test
    void materializedCanonicalBodyDoesNotReplaceCompactProviderShell() {
        // given
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore store = new WholeObjectStore(metrics);
        ExactValue child = store.put(
                new Node().properties(
                        "status", new Node().value("confirmed")),
                "child");
        ExactValue shell = store.put(
                new Node().properties("child", child.referenceNode()),
                "shell");
        ExactValue materialized = ExactValue.verified(
                new Node().properties("child", child.copyNode()));

        // when
        store.preferCanonicalRepresentation(
                materialized.frozen(), "semantic-root");

        // then
        assertEquals(shell.blueId(), materialized.blueId());
        assertEquals("confirmed", store.require(shell.blueId()).copyNode()
                .getProperties().get("child")
                .getProperties().get("status").getValue());
        assertEquals(child.blueId(), store.fetchByBlueId(shell.blueId()).get(0)
                .getProperties().get("child").getBlueId());
        assertEquals(1L, metrics.counter(
                "wholeObjectStore.canonicalRepresentationsPreferred"));
    }

    private static BasicNodeProvider cyclicProvider() {
        return new BasicNodeProvider(new Node().items(List.of(
                new Node().name("store-cycle-a").properties(
                        "peer", new Node().blueId("this#1")),
                new Node().name("store-cycle-b").properties(
                        "peer", new Node().blueId("this#0")))));
    }
}
