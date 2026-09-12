package blue.coordination.sdk;

import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedJoinPublicationSafetyProbe;
import blue.coordination.internal.RootedJoinPublicationSafetyProbe.Publication;
import blue.coordination.internal.RootedJoinPublicationSafetyProbe.Selection;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ManagedRepresentationCause;
import blue.language.processor.closure.ManagedRevisionCause;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Safety of the real B-owned terminal which atomically settles C's registered historical application. */
final class RootedJoinPublicationSafetyTest {
    private static final String TIMELINE = "witness-forwarding/alice";

    @Test void failureBeforeSwapPreservesEveryOwnerAndRegisteredCursorForExactRetry() throws Exception {
        // given
        try (var s = new Scenario()) {
            var before = s.state();
            var store = s.probe.publicationState();
            long calls = s.probe.processCalls();
            s.f.control.failPublicationAt("BEFORE_SWAP");
            // when
            var failure = assertThrows(IllegalStateException.class, () -> s.probe.publish(s.terminal));
            // then
            assertTrue(failure.getMessage().contains("BEFORE_SWAP"), failure.toString());
            assertEquals(calls + 1, s.probe.processCalls(), "The failed publication follows one genuine PROCESS");
            assertEquals(store, s.probe.publicationState(), "No document, topology, event, checkpoint or C plan may swap");
            assertEquals(before, s.state());
            assertTrue(s.probe.retained(s.terminal).isEmpty());
            s.f.control.clearPublicationFailure();
            var published = s.probe.publish(s.terminal);
            assertFalse(published.replayed());
            assertEquals(calls + 2, s.probe.processCalls());
            s.assertPublished(published);
            s.assertSettled();
        }
    }

    @Test void lostAcknowledgementReplaysTheSameReceiptWithoutProcessBeforeAndAfterRestart() throws Exception {
        // given
        try (var s = new Scenario()) {
            var before = s.state();
            long calls = s.probe.processCalls();
            s.f.control.failPublicationAt("AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH");
            // when
            var failure = assertThrows(IllegalStateException.class, () -> s.probe.publish(s.terminal));
            // then
            assertTrue(failure.getMessage().contains("Injected rooted publication failure"), failure.toString());
            var committed = s.probe.retained(s.terminal).orElseThrow();
            assertEquals(calls + 1, s.probe.processCalls());
            assertNotEquals(before, s.state(), "Lost acknowledgement is after the one atomic store commit");
            s.assertPublished(committed);
            var stored = s.probe.publicationState();
            s.f.control.clearPublicationFailure();
            for (int repeat = 0; repeat < 2; repeat++) {
                var replay = s.probe.publish(s.terminal);
                assertTrue(replay.replayed());
                assertSameResult(committed, replay);
                assertEquals(calls + 1, s.probe.processCalls(), "Receipt repair must never run PROCESS again");
                assertEquals(stored, s.probe.publicationState());
            }
            s.assertSettled();
            var settled = s.state();
            CoordinationTestControl.attach(s.f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(settled, s.state());
            long restartedCalls = s.probe.processCalls();
            var replay = s.probe.publish(s.terminal);
            assertTrue(replay.replayed());
            assertSameResult(committed, replay);
            assertEquals(restartedCalls, s.probe.processCalls());
            assertEquals(settled, s.state());
            s.assertSettled();
        }
    }

    @Test void staleLocalOwnerCaptureCannotPublishTheStillCanonicalRegisteredTerminal() throws Exception {
        // given
        try (var s = new Scenario()) {
            assertEquals(s.terminal.work().workIdentity(), s.stale.work().workIdentity(),
                    "C's independent work is still canonical; only B's retained capture has advanced");
            assertNotEquals(s.terminal.input().invocationIdentity(), s.stale.input().invocationIdentity());
            var before = s.state();
            var store = s.probe.publicationState();
            long calls = s.probe.processCalls();
            // when
            var failure = assertThrows(IllegalArgumentException.class, () -> s.probe.publish(s.stale));
            // then
            assertEquals("Local history must retain the exact captured root and its frozen frontier", failure.getMessage());
            assertEquals(calls, s.probe.processCalls(), "Stale capture is rejected before PROCESS");
            assertEquals(store, s.probe.publicationState());
            assertEquals(before, s.state());
            assertTrue(s.probe.retained(s.terminal).isEmpty());
            var published = s.probe.publish(s.terminal);
            assertFalse(published.replayed());
            s.assertPublished(published);
            s.assertSettled();
        }
    }

    private static void assertSameResult(Publication expected, Publication actual) {
        assertEquals(expected.application().applicationReceiptIdentity(), actual.application().applicationReceiptIdentity());
        assertEquals(expected.result().invocationIdentity(), actual.result().invocationIdentity());
        assertEquals(expected.result().outputClosureIdentity(), actual.result().outputClosureIdentity());
        assertEquals(expected.result().publicEventsIdentity(), actual.result().publicEventsIdentity());
        assertEquals(expected.result().managedTransitionReceiptsIdentity(), actual.result().managedTransitionReceiptsIdentity());
        assertEquals(expected.result().checkpointWritesIdentity(), actual.result().checkpointWritesIdentity());
        assertEquals(expected.result().commitCompanion().companionIdentity(), actual.result().commitCompanion().companionIdentity());
        assertEquals(expected.result().gasTraceIdentity(), actual.result().gasTraceIdentity());
        assertEquals(expected.result().totalGas(), actual.result().totalGas());
    }

    private static final class Scenario implements AutoCloseable {
        final RootedSdkFixture f = new RootedSdkFixture();
        final RootedJoinPublicationSafetyProbe probe = new RootedJoinPublicationSafetyProbe(f.blue.advanced().rawEngine());
        final LinkedHashMap<String, DocumentHandle> roots = new LinkedHashMap<>();
        final Selection stale;
        final Selection terminal;

        Scenario() throws Exception {
            String template = RootedSdkFixture.resource("node-graph.template.json");
            for (String name : List.of("A", "B", "C")) {
                String authored = template.replace("<NODE>", name).replace("<NAMESPACE>", "witness-forwarding")
                        .replace("<TIMELINE>", TIMELINE);
                var exact = f.blue.values().yaml(authored);
                f.exact.put(exact.blueId(), exact.json());
                roots.put(name, f.startYaml(authored, TIMELINE));
            }
            var a = roots.get("A"); var b = roots.get("B"); var c = roots.get("C");
            var entries = new ArrayList<String>();
            prefix(b, "attach", 100, attachment("c", c), entries);
            prefix(a, "attach", 150, attachment("b", b), entries);
            prefix(a, "emit", 200, "to: C\nnext: B", entries);
            prefix(a, "touch", 250, "{}", entries);
            assertEquals(6L, a.snapshot().epoch());
            var entry = f.append(c, TIMELINE, "attach", 300, attachment("a", a), true);
            entries.add(entry.blueId());
            assertEquals(List.of("qCiBGwwixGUbYPrcGyAVapBmXZWxvpeDU9yjKmVjC6f",
                    "8UeTdMdjMkzjwSVwprqZijmD6r1Wr6mPEc1cDfG8YuS", "WH5QBGYkM7iMEuFVsPKJpnXNo1dk5BpArcTNoANQ8Da",
                    "ABgTJyzKMFhC5XAynikZFkuiWq5EStphvb18k5cgYa2N", "HgWUG5gsFmWCS84v3C7KE995QVACy6P5oNM8J4h8fTE5"), entries);
            var originalB = f.control.capture(b.id(), entry.blueId(), null);
            apply(c, entry);
            for (int step = 0; step < 16; step++) {
                var input = probe.registeredInput(c.id());
                if (terminalPosition(input)) break;
                var work = probe.registered(c.id());
                assertTrue(work.sourceEpoch() <= 6L);
                var advanced = f.blue.processing().processNext(c);
                assertEquals(1L, advanced.stats().committedTransitions(), "The selected C prefix step must commit");
                assertEquals(1, advanced.managedEpochApplicationAttempts().size());
                assertEquals(1, advanced.managedEpochApplications().size());
                var attempt = advanced.managedEpochApplicationAttempts().get(0);
                assertEquals(work.workIdentity(), attempt.work().workIdentity());
                assertTrue(attempt.published());
                assertFalse(attempt.replayed());
                assertTrue(attempt.attempt().isComplete());
                var executed = attempt.attempt().processResult();
                assertTrue(executed.commits());
                assertTrue(executed.totalGas() > 0L, "Registered-history gas lives on its actual typed attempt");
                var receipt = advanced.managedEpochApplications().get(0);
                assertEquals(receipt, attempt.receipt().orElseThrow());
                assertEquals(executed.invocationIdentity(), receipt.contractsInvocationIdentity());
                if (advanced.blocked()) assertTrue(terminalPosition(probe.registeredInput(c.id())),
                        "Only the next terminal may wait after committed prefix progress");
            }
            assertTrue(terminalPosition(probe.registeredInput(c.id())));
            assertEquals(6L, probe.registered(c.id()).sourceEpoch());
            assertEquals(a.id(), probe.registered(c.id()).sourceDocumentId());
            var beforeBlocked = state();
            var blocked = f.blue.processing().processNext(c);
            assertTrue(blocked.blocked());
            assertEquals(0L, blocked.stats().committedTransitions());
            assertEquals(0L, blocked.stats().gas());
            assertEquals(beforeBlocked, state(), "C's terminal must wait without executing another owner");
            assertEquals(1L, observed(c)); assertEquals(0L, observed(b));
            assertEquals(1L, f.blue.advanced().auditManagedEpochs(c.id()).stream()
                    .flatMap(receipt -> receipt.emittedEvents().stream())
                    .filter(event -> "B".equals(event.exactEvent().scalarAt("/to"))
                            && "stop".equals(event.exactEvent().scalarAt("/next"))).count());
            assertEquals(originalB.invocationIdentity(), f.control.capture(b.id(), entry.blueId(), null).invocationIdentity());
            var sourceHeads = states(List.of(a, c));
            apply(b, entry);
            stale = probe.capture(b.id());
            assertFalse(terminalPosition(stale.input()));
            for (int step = 0; step < 16; step++) {
                if (terminalPosition(f.control.captureLocalHistory(b.id()))) break;
                assertTrue(f.control.localHistoryWork(b.id()).sourceEpoch() <= 6L);
                var next = f.blue.processing().processNext(b);
                assertEquals(1L, next.stats().committedTransitions(), "The selected B prefix step must commit");
                assertTrue(next.stats().gas() > 0L);
                if (next.blocked()) assertTrue(terminalPosition(f.control.captureLocalHistory(b.id())),
                        "Only the next terminal may wait after committed prefix progress");
            }
            terminal = probe.capture(b.id());
            assertTrue(terminalPosition(terminal.input()));
            var cause = assertInstanceOf(ManagedRevisionCause.class, terminal.input().cause());
            assertEquals(5L, cause.fromEpoch()); assertEquals(6L, cause.toEpoch());
            assertEquals(a.id().value(), cause.childDocumentId().value());
            assertEquals(sourceHeads, states(List.of(a, c)), "B's local prefix must not independently publish A or C");
            assertEquals(List.of(0L, 1L, 1L), roots.values().stream().map(this::observed).toList());
            assertTrue(probe.retained(terminal).isEmpty());
        }

        void assertPublished(Publication publication) {
            assertTrue(publication.published());
            assertTrue(publication.result().commits());
            assertEquals(terminal.input().invocationIdentity(), publication.result().invocationIdentity());
            assertEquals(622L, publication.result().totalGas(), "Exact default-policy five-input terminal gas");
            assertEquals(terminal.work().workIdentity(), publication.application().workIdentity());
            assertEquals(terminal.work().sourceReceiptIdentity(), publication.application().sourceReceiptIdentity());
            assertEquals(roots.get("C").id(), publication.application().consumerDocumentId());
            assertEquals(7L, publication.application().resultingSourceCursor());
            assertEquals(roots.values().stream().map(root -> root.id().value()).collect(java.util.stream.Collectors.toSet()),
                    publication.result().rootedProjection().ownedDocumentIds().stream().map(id -> id.value())
                            .collect(java.util.stream.Collectors.toSet()));
        }

        void assertSettled() {
            assertEquals(List.of(0L, 1L, 1L), roots.values().stream().map(this::observed).toList());
            for (var root : roots.values()) {
                assertTrue(f.blue.advanced().auditManagedCatchUpPlans(root.id()).stream()
                        .allMatch(plan -> plan.status() == ManagedCatchUpStatus.COMPLETE));
                assertTrue(f.blue.advanced().auditManagedDocumentReadiness(root.id()).orElseThrow().ready());
                assertEquals(f.blue.advanced().auditDocument(root.id()).blueId(), root.snapshot().blueId());
                assertEquals(f.blue.advanced().auditDocument(root.id()).epoch(), root.snapshot().epoch());
                var next = f.blue.processing().processNext(root);
                assertTrue(next.quiescent());
                assertEquals(0L, next.stats().gas());
                assertEquals(0L, next.stats().committedTransitions());
            }
        }

        List<Object> state() { return states(new ArrayList<>(roots.values())); }

        private List<Object> states(List<DocumentHandle> selected) {
            return selected.stream().map(root -> {
                var current = f.blue.advanced().auditDocument(root.id());
                var plans = f.blue.advanced().auditManagedCatchUpPlans(root.id()).stream().map(plan ->
                        List.of(plan.planIdentity(), plan.snapshotIdentity(), plan.status(), plan.nextSourceEpoch(),
                                plan.requiredThroughSourceEpoch())).toList();
                return (Object) List.of(root.id(), current.epoch(), current.blueId(),
                        ExactBlueValue.wrap(current.current()).json(), f.history(root),
                        f.control.selectedView(root.id()).closureIdentity(), plans);
            }).toList();
        }

        private long observed(DocumentHandle root) {
            return ((Number) ExactBlueValue.wrap(f.blue.advanced().auditDocument(root.id()).current())
                    .scalarAt("/observed")).longValue();
        }

        private void prefix(DocumentHandle root, String operation, long time, String request, List<String> entries) {
            var entry = f.append(root, TIMELINE, operation, time, request, true);
            entries.add(entry.blueId()); apply(root, entry);
            for (int step = 0; step < 16; step++) {
                var next = f.blue.processing().processNext(root);
                assertFalse(next.blocked(), String.valueOf(next.diagnostic()));
                if (next.quiescent()) return;
            }
            fail("Prefix did not settle within 16 real selections");
        }

        private void apply(DocumentHandle root, EntryHandle entry) {
            var result = f.blue.processing().process(root, entry);
            assertEquals(EntryDisposition.APPLIED, result.entry(entry).disposition());
            assertFalse(result.blocked(), String.valueOf(result.diagnostic()));
        }

        @Override public void close() { f.close(); }
    }

    private static String attachment(String edge, DocumentHandle child) {
        return "edge: " + edge + "\nsource: {blueId: " + child.id().value() + "}";
    }

    private static boolean terminalPosition(ClosureInvocationInput input) {
        if (input.cause() instanceof ManagedRepresentationCause representation) return representation.terminalPositionReached();
        var revision = assertInstanceOf(ManagedRevisionCause.class, input.cause());
        return revision.successorRepresentationCause().isEmpty()
                && revision.toEpoch() == input.snapshot().managedDocument(revision.childDocumentId()).epoch();
    }
}
