package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationDeliveryStatus;
import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InMemoryCoordinationDispatchLedgerTest {

    @Test
    void shouldFreezeSortedTargetsAndDeterministicChunks() {
        InMemoryCoordinationDispatchLedger ledger =
                new InMemoryCoordinationDispatchLedger();

        CoordinationDispatchSnapshot state = ledger.beginOrResume(
                event("event-a", "inventory-a"),
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                1L,
                Arrays.asList(target("session-c", "/c"),
                        target("session-a", "/a"),
                        target("session-b", "/b")),
                2);

        assertEquals(3, state.plan().targets().size());
        assertEquals("session-a", state.plan().targets().get(0)
                .sessionId().value());
        assertEquals("session-b", state.plan().targets().get(1)
                .sessionId().value());
        assertEquals("session-c", state.plan().targets().get(2)
                .sessionId().value());
        assertEquals(Arrays.asList(2, 1), Arrays.asList(
                state.plan().chunks().get(0).size(),
                state.plan().chunks().get(1).size()));
    }

    @Test
    void shouldMakeCommitTerminalAndPersistCompleteDeliveryEvidence() {
        InMemoryCoordinationDispatchLedger ledger =
                new InMemoryCoordinationDispatchLedger();
        StoredCoordinationEvent event = event("event-b", "inventory-b");
        IndexedSessionCandidates target = target("session-a", "/a");
        ledger.beginOrResume(
                event,
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                1L,
                Collections.singletonList(target),
                1);

        CoordinationDeliveryAdmission admission = ledger.beginAttempt(
                event.eventBlueId(), target.sessionId());
        ledger.commit(admission, committed(event, target, "transition-a"));

        CoordinationDispatchSnapshot completed = ledger.require(
                event.eventBlueId());
        assertTrue(completed.complete());
        assertEquals(1, completed.receipts().get(0).attemptCount());
        assertEquals(CoordinationDeliveryStatus.COMMITTED,
                completed.receipts().get(0).status());
        assertEquals(target.orderedOccurrenceKeys(),
                completed.receipts().get(0).orderedOccurrenceKeys());
        assertEquals(Long.valueOf(1L), completed.receipts().get(0)
                .resultingEpoch().get());
        assertEquals(Collections.singletonList("outbox-event"),
                completed.receipts().get(0)
                        .committedOutboxEventBlueIds());
        assertThrows(IllegalStateException.class, () -> ledger.beginAttempt(
                event.eventBlueId(), target.sessionId()));
    }

    @Test
    void shouldRejectConflictingCanonicalRouteOrTargetEvidence() {
        InMemoryCoordinationDispatchLedger ledger =
                new InMemoryCoordinationDispatchLedger();
        StoredCoordinationEvent event = event("event-c", "inventory-c");
        ledger.beginOrResume(
                event,
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                1L,
                Collections.singletonList(target("session-a", "/a")),
                10);

        assertThrows(IllegalStateException.class, () -> ledger.beginOrResume(
                event("event-c", "other-inventory"),
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                1L,
                Collections.singletonList(target("session-a", "/a")),
                10));
        assertThrows(IllegalStateException.class, () -> ledger.beginOrResume(
                event,
                Collections.singletonList("actor:bob"),
                "ownerChannel",
                1L,
                Collections.singletonList(target("session-a", "/a")),
                10));
        assertThrows(IllegalStateException.class, () -> ledger.beginOrResume(
                event,
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                1L,
                Collections.singletonList(target("session-b", "/b")),
                10));
    }

    @Test
    void shouldRejectConcurrentAndStaleAttemptCompletions() {
        InMemoryCoordinationDispatchLedger ledger =
                new InMemoryCoordinationDispatchLedger();
        StoredCoordinationEvent event = event("event-d", "inventory-d");
        IndexedSessionCandidates target = target("session-a", "/a");
        ledger.beginOrResume(
                event,
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                1L,
                Collections.singletonList(target),
                1);
        CoordinationDeliveryAdmission first = ledger.beginAttempt(
                event.eventBlueId(), target.sessionId());

        assertThrows(IllegalStateException.class, () -> ledger.beginAttempt(
                event.eventBlueId(), target.sessionId()));
        ledger.fail(first, new IllegalArgumentException("not persisted"));
        CoordinationDeliveryAdmission second = ledger.beginAttempt(
                event.eventBlueId(), target.sessionId());
        assertThrows(IllegalStateException.class, () -> ledger.commit(
                first, committed(event, target, "stale")));
        ledger.commit(second, committed(event, target, "current"));
        assertEquals(2, ledger.require(event.eventBlueId())
                .receipts().get(0).attemptCount());
    }

    @Test
    void shouldTreatEquivalentTargetStreamsAsTheSamePlanAcrossPageBoundaries() {
        InMemoryCoordinationDispatchLedger ledger =
                new InMemoryCoordinationDispatchLedger();
        StoredCoordinationEvent event = event("event-pages", "inventory-pages");
        IndexedSessionCandidates first = target("session-a", "/a");
        IndexedSessionCandidates second = target("session-b", "/b");
        IndexedSessionCandidates third = target("session-c", "/c");
        InMemoryCoordinationDispatchLedger.FreezeAdmission admission =
                ledger.beginFreeze(
                        event,
                        Collections.singletonList("actor:alice"),
                        "ownerChannel",
                        1L,
                        2);
        ledger.appendFrozenPage(admission, Collections.singletonList(first));
        ledger.appendFrozenPage(admission, Arrays.asList(second, third));
        ledger.sealFreeze(admission);

        CoordinationDispatchSnapshot retried = ledger.beginOrResume(
                event,
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                1L,
                Arrays.asList(first, second, third),
                2);

        assertEquals(3, retried.plan().targetCount());
        assertEquals(Arrays.asList(1, 2), Arrays.asList(
                retried.plan().pages().get(0).size(),
                retried.plan().pages().get(1).size()));
    }

    private static StoredCoordinationEvent event(
            String eventBlueId,
            String inventoryIdentity) {
        return new StoredCoordinationEvent(
                eventBlueId,
                inventoryIdentity,
                ExternalOrderKey.of(Arrays.<Object>asList(1L, eventBlueId)));
    }

    private static IndexedSessionCandidates target(
            String sessionId,
            String occurrenceKey) {
        return new IndexedSessionCandidates(
                DocumentSessionId.of(sessionId),
                Collections.singletonList(occurrenceKey),
                1,
                0L,
                "root-before-" + sessionId,
                "subscriptions-" + sessionId);
    }

    private static CoordinationCommittedDelivery committed(
            StoredCoordinationEvent event,
            IndexedSessionCandidates target,
            String transitionIdentity) {
        return new CoordinationCommittedDelivery(
                event.eventBlueId(),
                target.sessionId(),
                target.plannedEpoch(),
                target.plannedRootBlueId(),
                target.plannedEpoch() + 1L,
                "root-after-" + target.sessionId().value(),
                transitionIdentity,
                Collections.singletonList("outbox-event"));
    }
}
