package blue.coordination.internal;

import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DrainBudget;
import blue.coordination.sdk.EntryHandle;
import blue.coordination.sdk.TimelineHandle;
import blue.language.processor.closure.ClosureEvidenceFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The exact original public report, including unchanged authored identities. */
final class RootedExactInlinePublicSequenceTest {
    @Test void retainedCaptureRejectsAnotherProjectionAndStalePublishedView() throws Exception {
        try (var blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            var timeline = blue.timelines().register("tutorial/cycle/alice", "alice");
            String yaml = resource("exact-user-cycle-b.yaml");
            var b = blue.documents().admitStaticProcessEmbedded(yaml, ActivationPolicy.importFullHistory()).document("root");
            var other = blue.documents().admitStaticProcessEmbedded(
                    yaml.replace("Dynamic Cycle B", "Separate capture owner"), ActivationPolicy.importFullHistory()).document("root");
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            var adapter = engine.contractsClosureAdapter();
            var admission = adapter.captureRootedState(b.id());
            var first = append(blue, timeline, b, "touchB", null, null, 100, null);
            assertThrows(IllegalArgumentException.class,
                    () -> admission.requireRetainedInput(input(engine, admission, first), engine.documents()));
            blue.advanced().drainJournalThrough(first, new DrainBudget(1, 1));
            drain(blue);
            var captured = adapter.captureRootedState(b.id());
            var exactInput = input(engine, captured, first);
            assertDoesNotThrow(() -> captured.requireRetainedInput(exactInput, engine.documents()));
            var selected = captured.snapshot().managedDocuments().get(0);
            var alteredBody = selected.document().properties("phase", new blue.language.model.Node().value("forged"));
            var altered = new blue.language.processor.closure.ManagedDocumentSnapshot(selected.documentId(),
                    selected.blueId(), alteredBody, selected.initialized(), selected.terminated(), selected.publicRoot(),
                    selected.epoch(), selected.componentGeneration());
            var forgedSnapshot = ClosureEvidenceFactory.affectedClosure(
                    captured.snapshot().graphGeneration(), List.of(altered), captured.snapshot().occurrences(),
                    captured.snapshot().components(), captured.snapshot().publicRootDocumentIds());
            assertEquals(captured.snapshot().closureIdentity(), forgedSnapshot.closureIdentity(),
                    "An asserted state identity alone cannot authenticate different document bytes");
            var forgedInput = ClosureEvidenceFactory.processClosure(forgedSnapshot,
                    exactInput.cause(), List.of(), exactInput.executionPolicy(), exactInput.environment());
            assertThrows(IllegalArgumentException.class,
                    () -> captured.requireRetainedInput(forgedInput, engine.documents()));
            var otherCapture = adapter.captureRootedState(other.id());
            assertThrows(IllegalArgumentException.class,
                    () -> captured.requireRetainedInput(input(engine, otherCapture, first), engine.documents()));
            var next = append(blue, timeline, b, "touchB", null, null, 200, first);
            blue.advanced().drainJournalThrough(next, new DrainBudget(1, 1));
            drain(blue);
            assertThrows(IllegalArgumentException.class,
                    () -> captured.requireRetainedInput(exactInput, engine.documents()));
            assertTrue(java.util.Arrays.stream(ContractsClosureAdapter.RootedCapturedState.class.getDeclaredConstructors())
                    .allMatch(c -> java.lang.reflect.Modifier.isPrivate(c.getModifiers())));
        }
    }

    private static blue.language.processor.closure.ClosureInvocationInput input(DefaultCoordinationEngine engine,
            ContractsClosureAdapter.RootedCapturedState state, EntryHandle handle) {
        var entry = engine.auditTimelineEntry(handle.blueId()).orElseThrow();
        var environment = engine.contractsClosureAdmissionAdapter().environment();
        var cause = blue.language.processor.closure.ClosureEvidenceFactory.externalCause(entry.exactEvent().copyNode(),
                entry.blueId(), entry.sourceOrderKey(), environment.externalOrderPolicyIdentity());
        return blue.language.processor.closure.ClosureEvidenceFactory.processClosure(state.snapshot(), cause, List.of(),
                blue.language.processor.closure.ClosureEvidenceFactory.executionPolicy(100000, java.util.Map.of(), "release-default"),
                environment);
    }

    @Test void reattachesTheSavedOriginalAfterTheInlinePairJoined() throws Exception {
        exactPair(false);
    }

    @Test void independentlySelectedReturnWaitsWithoutMutationAndSurvivesRestart() throws Exception {
        exactPair(true);
    }

    private static void exactPair(boolean sourceFirst) throws Exception {
        try (var blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            var timeline = blue.timelines().register("tutorial/cycle/alice", "alice");
            String aYaml = resource("exact-user-inline-cycle-a.yaml");
            String bYaml = resource("exact-user-cycle-b.yaml");
            var originalA = blue.values().yaml(aYaml);
            var originalB = blue.values().yaml(bYaml);
            assertEquals("H5PqsgVoN3ZL1Sg3b9wMfMK24R6ihV8n4t7zRFiivDbT", originalA.blueId());
            assertEquals("ARLSEvSXoDQbAfXNuKxj5d1cLh48caJbE74wZyJ6UYgB", originalB.blueId());
            var b = blue.documents().admitStaticProcessEmbedded(bYaml, ActivationPolicy.importFullHistory()).document("root");
            var a = blue.documents().admitStaticProcessEmbedded(aYaml, ActivationPolicy.importFullHistory()).document("root");
            drain(blue);
            var connect = append(blue, timeline, b, "connectA", "a", originalA.blueId(), 100, null);
            blue.advanced().drainJournalThrough(connect, new DrainBudget(1, 1));
            drain(blue);
            assertTrue(blue.advanced().auditManagedOccurrence(a.id(), "/peer").orElseThrow().active());
            assertTrue(blue.advanced().auditManagedOccurrence(b.id(), "/peer").orElseThrow().active());
            var reattach = append(blue, timeline, a, "attachB", "b", originalB.blueId(), 200, connect);
            try {
                blue.advanced().drainJournalThrough(reattach, new DrainBudget(1, 1));
                if (sourceFirst) {
                    var before = List.of(a.snapshot().blueId(), b.snapshot().blueId());
                    var history = List.of(history(blue, a), history(blue, b));
                    var waiting = blue.processing().processNext(b);
                    assertTrue(waiting.blocked());
                    assertFalse(waiting.quiescent());
                    assertEquals(0L, waiting.stats().gas());
                    assertEquals(before, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
                    assertEquals(history, List.of(history(blue, a), history(blue, b)));
                    CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
                    assertTrue(blue.processing().processNext(b).blocked());
                    assertEquals(before, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
                    assertEquals(history, List.of(history(blue, a), history(blue, b)));
                }
                drain(blue);
            } catch (RuntimeException failure) {
                var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
                for (var document : List.of(a, b)) {
                    var state = engine.contractsClosureAdapter().captureRootedState(document.id());
                    var view = state.view();
                    System.err.println("EXACT_INLINE_DIAGNOSTIC root=" + document.id() + " anchor=" + state.anchor()
                            + " sameView=" + (engine.documents().require(state.anchor()).rootedView() == view)
                            + " boundary=" + view.logicalBoundary()
                            + " origin=" + view.snapshot().closureIdentity()
                            + " retained=" + view.retainedSnapshot().closureIdentity()
                            + " captured=" + state.snapshot().closureIdentity()
                            + " pending=" + RootedLocalHistory.pending(state, document.id()).size());
                    for (var selected : state.snapshot().managedDocuments())
                        System.err.println("EXACT_INLINE_MEMBER " + selected.documentId() + " epoch=" + selected.epoch()
                                + " blueId=" + selected.blueId() + " publicRoot=" + selected.publicRoot());
                    var session = engine.documents().require(document.id());
                    var chain = new ManagedRepresentationHistory(engine.documents()).at(document.id(), session.epoch());
                    System.err.println("EXACT_INLINE_SOURCE source=" + document.id() + " epoch=" + session.epoch()
                            + " anchor=" + chain.anchor().afterBlueId() + " order=" + chain.anchor().sourceOrder()
                            + " nextReceipt=" + chain.nextRevisionReceiptIdentity());
                    for (var transition : chain.transitions())
                        System.err.println("EXACT_INLINE_POSITION before=" + transition.transitionReceipt().beforeBlueId()
                                + " after=" + transition.transitionReceipt().afterBlueId()
                                + " invocation=" + transition.originalResult().invocationIdentity()
                                + " cause=" + transition.originalInput().cause());
                }
                throw failure;
            }
            var completedHeads = List.of(a.snapshot().blueId(), b.snapshot().blueId());
            var completedHistory = List.of(history(blue, a), history(blue, b));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertTrue(blue.processing().processNext(a).quiescent());
            assertTrue(blue.processing().processNext(b).quiescent());
            assertEquals(completedHeads, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
            assertEquals(completedHistory, List.of(history(blue, a), history(blue, b)));
            for (var document : List.of(a, b)) {
                assertTrue(blue.advanced().auditManagedDocumentReadiness(document.id()).orElseThrow().ready());
                assertTrue(blue.advanced().auditManagedOccurrence(document.id(), "/peer").orElseThrow().active());
                assertEquals(1, blue.advanced().auditManagedEpochs(document.id()).stream()
                        .filter(row -> row.kind().name().equals("INITIALIZATION")).count());
                assertTrue(blue.advanced().auditManagedEpochs(document.id()).stream().allMatch(row -> row.emittedEvents().isEmpty()));
            }
        }
    }

    private static List<List<String>> history(BlueCoordination blue, DocumentHandle document) {
        return blue.advanced().auditManagedEpochs(document.id()).stream()
                .map(receipt -> List.of(receipt.receiptIdentity(), receipt.afterDocument().json())).toList();
    }

    private static void drain(BlueCoordination blue) {
        for (int i = 0; i < 32; i++) {
            var result = blue.processing().drain(new DrainBudget(1, 1));
            assertFalse(result.blocked(), result.diagnostic().toString());
            if (result.quiescent()) return;
        }
        fail("Exact original pair did not reach readiness within 32 bounded calls");
    }

    private static EntryHandle append(BlueCoordination blue, TimelineHandle timeline, DocumentHandle target,
            String operation, String parameter, String source, long timestamp, EntryHandle previous) {
        String yaml = """
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: tutorial/cycle/alice}
                timestamp: %d
                actor: {type: MyOS/Principal Actor, accountId: alice}
                message:
                  type: Coordination/Operation Request
                  document: {blueId: %s}
                  requireExactDocumentVersion: false
                  operation: %s
                  channel: ownerChannel
                  request: %s
                """.formatted(timestamp, target.snapshot().blueId(), operation,
                        parameter == null ? "{}" : "\n    " + parameter + ": {blueId: " + source + "}");
        if (previous != null) yaml += "prevEntry: {blueId: " + previous.blueId() + "}\n";
        return blue.events().from(timeline).exact(blue.values().yaml(yaml)).submit();
    }

    private static String resource(String name) throws Exception {
        try (var in = RootedExactInlinePublicSequenceTest.class.getResourceAsStream("/rooted/" + name)) {
            return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
