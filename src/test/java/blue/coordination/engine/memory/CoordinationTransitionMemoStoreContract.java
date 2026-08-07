package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.TransitionMemoKey;
import blue.coordination.engine.spi.CoordinationTransitionMemoStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class CoordinationTransitionMemoStoreContract {

    abstract CoordinationTransitionMemoStore createStore();

    @Test
    void shouldReturnEmptyWhenTheExactTransitionKeyWasNeverMemoized() {
        // given
        CoordinationTransitionMemoStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "memo-empty", "before");
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "memo-transition-empty");
        TransitionMemoKey key = CoordinationEngineStorageTestFixtures.memoKey(
                admission.session.sessionId(),
                admission.graph,
                transition.event);

        // when
        Optional<CoordinationTransition> result = store.find(key);

        // then
        assertFalse(result.isPresent());
    }

    @Test
    void shouldReturnTheExactWholeTransitionForTheExactKey() {
        // given
        CoordinationTransitionMemoStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "memo-round-trip", "before");
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "memo-transition-round-trip");
        TransitionMemoKey key = CoordinationEngineStorageTestFixtures.memoKey(
                admission.session.sessionId(),
                admission.graph,
                transition.event);

        // when
        store.put(key, transition.transition);
        CoordinationTransition restored = store.find(key).get();

        // then
        assertSame(transition.transition, restored);
    }

    @Test
    void shouldAcceptAnIdempotentMemoWrite() {
        // given
        CoordinationTransitionMemoStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "memo-idempotent", "before");
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "memo-transition-idempotent");
        TransitionMemoKey key = CoordinationEngineStorageTestFixtures.memoKey(
                admission.session.sessionId(),
                admission.graph,
                transition.event);

        // when
        store.put(key, transition.transition);
        store.put(key, transition.transition);

        // then
        assertSame(transition.transition, store.find(key).get());
    }

    @Test
    void shouldRejectBindingOneExactKeyToAnotherTransition() {
        // given
        CoordinationTransitionMemoStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "memo-conflict", "before");
        CoordinationEngineStorageTestFixtures.CommitFixture first =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "same-after",
                        "memo-transition-first");
        CoordinationEngineStorageTestFixtures.CommitFixture conflicting =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "same-after",
                        "memo-transition-conflicting");
        TransitionMemoKey key = CoordinationEngineStorageTestFixtures.memoKey(
                admission.session.sessionId(),
                admission.graph,
                first.event);
        store.put(key, first.transition);

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.put(key, conflicting.transition));

        // then
        assertTrue(failure.getMessage().contains("another transition"));
        assertSame(first.transition, store.find(key).get());
    }

    @Test
    void shouldIsolateEquivalentSemanticInputsAcrossDocumentSessions() {
        // given
        CoordinationTransitionMemoStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture firstSession =
                CoordinationEngineStorageTestFixtures.admission(
                        "memo-session-a", "shared-before");
        CoordinationEngineStorageTestFixtures.AdmissionFixture secondSession =
                CoordinationEngineStorageTestFixtures.admission(
                        "memo-session-b", "shared-before");
        CoordinationEngineStorageTestFixtures.CommitFixture first =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        firstSession,
                        "shared-after",
                        "memo-shared-transition");
        CoordinationEngineStorageTestFixtures.CommitFixture second =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        secondSession,
                        "shared-after",
                        "memo-shared-transition");
        TransitionMemoKey firstKey =
                CoordinationEngineStorageTestFixtures.memoKey(
                        firstSession.session.sessionId(),
                        firstSession.graph,
                        first.event);
        TransitionMemoKey secondKey =
                CoordinationEngineStorageTestFixtures.memoKey(
                        secondSession.session.sessionId(),
                        secondSession.graph,
                        second.event);

        // when
        store.put(firstKey, first.transition);
        store.put(secondKey, second.transition);

        // then
        assertSame(first.transition, store.find(firstKey).get());
        assertSame(second.transition, store.find(secondKey).get());
        assertTrue(!firstKey.equals(secondKey));
        assertTrue(!store.find(firstKey).get().commitPlan().sessionId().equals(
                store.find(secondKey).get().commitPlan().sessionId()));
    }

    @Test
    void shouldRequireEverySafetyDimensionForAKeyMatch() {
        // given
        CoordinationTransitionMemoStore store = createStore();
        DocumentSessionId sessionId = DocumentSessionId.of("memo-dimensions");
        TransitionMemoKey exact = key(
                sessionId, "root", "event", "evidence", "environment", "gas");
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "memo-dimensions", "before");
        CoordinationTransition transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "memo-transition-dimensions").transition;
        store.put(exact, transition);

        // when
        Optional<CoordinationTransition> otherRoot = store.find(key(
                sessionId, "other-root", "event", "evidence", "environment", "gas"));
        Optional<CoordinationTransition> otherEvent = store.find(key(
                sessionId, "root", "other-event", "evidence", "environment", "gas"));
        Optional<CoordinationTransition> otherEvidence = store.find(key(
                sessionId, "root", "event", "other-evidence", "environment", "gas"));
        Optional<CoordinationTransition> otherEnvironment = store.find(key(
                sessionId, "root", "event", "evidence", "other-environment", "gas"));
        Optional<CoordinationTransition> otherGas = store.find(key(
                sessionId, "root", "event", "evidence", "environment", "other-gas"));

        // then
        assertFalse(otherRoot.isPresent());
        assertFalse(otherEvent.isPresent());
        assertFalse(otherEvidence.isPresent());
        assertFalse(otherEnvironment.isPresent());
        assertFalse(otherGas.isPresent());
        assertSame(transition, store.find(exact).get());
    }

    @Test
    void shouldRejectNullMemoKeysAndValues() {
        // given
        CoordinationTransitionMemoStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "memo-null", "before");
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "memo-transition-null");
        TransitionMemoKey key = CoordinationEngineStorageTestFixtures.memoKey(
                admission.session.sessionId(),
                admission.graph,
                transition.event);

        // when
        NullPointerException findFailure = assertThrows(
                NullPointerException.class,
                () -> store.find(null));
        NullPointerException keyFailure = assertThrows(
                NullPointerException.class,
                () -> store.put(null, transition.transition));
        NullPointerException valueFailure = assertThrows(
                NullPointerException.class,
                () -> store.put(key, null));

        // then
        assertTrue(findFailure.getMessage().contains("key"));
        assertTrue(keyFailure.getMessage().contains("key"));
        assertTrue(valueFailure.getMessage().contains("transition"));
    }

    private static TransitionMemoKey key(
            DocumentSessionId sessionId,
            String root,
            String event,
            String evidence,
            String environment,
            String gas) {
        return new TransitionMemoKey(
                sessionId,
                root,
                event,
                evidence,
                environment,
                gas);
    }
}
