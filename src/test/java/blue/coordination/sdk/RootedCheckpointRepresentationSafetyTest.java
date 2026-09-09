package blue.coordination.sdk;

import blue.coordination.internal.RootedCalculationFixture;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ManagedRepresentationTransition;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SDK/Contracts and retained-publication counterexamples for the checkpoint-only class. */
final class RootedCheckpointRepresentationSafetyTest {
    private static String rootScopeIdentity(blue.language.processor.closure.DocumentId document) {
        try {
            var envelope = java.util.Map.of("domain", "blue-contracts-managed-scope-key/1.0", "value",
                    java.util.Map.of("documentId", document.value(), "scopePath", "/", "activationGeneration", 0));
            byte[] canonical = new org.erdtman.jcs.JsonCanonicalizer(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope)).getEncodedUTF8();
            return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test void retainedPositionRejectsForgedProofAndAnUnownedSource() throws Exception {
        try (var scenario = new Scenario()) {
            var committed = scenario.checkpoint(100L);
            var before = scenario.state();
            var proof = committed.position().rootedCheckpointReferenceProofIdentity().orElseThrow();
            assertDoesNotThrow(() -> scenario.f.control.verifyRetainedCheckpointPosition(committed.key(),
                    committed.result(), scenario.parent.id(), proof));
            assertDoesNotThrow(() -> scenario.f.control.verifySuppliedRepresentationPosition(committed.position()));
            assertThrows(IllegalArgumentException.class, () -> scenario.f.control.verifyRetainedCheckpointPosition(
                    committed.key(), committed.result(), scenario.parent.id(), wrongHash()));
            assertThrows(IllegalArgumentException.class, () -> scenario.f.control.verifyRetainedCheckpointPosition(
                    committed.key(), committed.result(), scenario.source.id(), proof));
            assertEquals(before, scenario.state(), "Proof rejection must not publish or alter either retained history");
        }
    }

    @Test void anotherActualCheckpointResultAndCompanionCannotReplaceTheOriginalPublication() throws Exception {
        try (var scenario = new Scenario()) {
            var first = scenario.checkpoint(100L);
            var second = scenario.checkpoint(200L);
            assertNotEquals(first.result().rootedProjection().companionIdentity(),
                    second.result().rootedProjection().companionIdentity());
            var before = scenario.state();
            assertThrows(IllegalArgumentException.class, () -> scenario.f.control.verifyRetainedCheckpointPosition(
                    first.key(), second.result(), scenario.parent.id(),
                    first.position().rootedCheckpointReferenceProofIdentity().orElseThrow()));
            assertDoesNotThrow(() -> copyPublicResult(first.input(), first.result(), first.result().commitCompanion()));
            assertThrows(IllegalArgumentException.class, () -> copyPublicResult(first.input(), first.result(),
                    second.result().commitCompanion()));
            assertEquals(before, scenario.state());
        }
    }

    @Test void sameCalculatedBytesAndBaseCompanionDoNotSupplyRootedCheckpointAuthority() throws Exception {
        try (var scenario = new Scenario()) {
            var entry = scenario.f.append(scenario.source, "rcp2/source", "emitUnmatched", 100L, "{}");
            var captured = scenario.f.control.capture(scenario.parent.id(), entry.blueId(), null);
            var materialized = RootedCalculationFixture.materializedReference(captured);
            assertTrue(materialized.commits());
            assertNull(materialized.rootedProjection());
            var original = scenario.commit(entry);
            assertEquals(original.result().outputClosureIdentity(), materialized.outputClosureIdentity());
            assertEquals(original.result().commitCompanion().companionIdentity(), materialized.commitCompanion().companionIdentity());
            assertEquals(original.result().gasTraceIdentity(), materialized.gasTraceIdentity());
            var before = scenario.state();
            assertThrows(IllegalArgumentException.class, () -> new ManagedRepresentationTransition(
                    original.position().documentId(), original.position().epoch(), original.position().anchorReceiptIdentity(),
                    original.position().predecessorPositionIdentity(), original.input(), materialized,
                    original.position().transitionReceipt().transitionReceiptIdentity()));
            assertThrows(IllegalArgumentException.class, () -> scenario.f.control.verifyRetainedCheckpointPosition(
                    original.key(), materialized, scenario.parent.id(),
                    original.position().rootedCheckpointReferenceProofIdentity().orElseThrow()));
            assertEquals(before, scenario.state());
        }
    }

    @Test void aCommittingCheckpointResultCannotLoseItsOriginalCompanion() throws Exception {
        try (var scenario = new Scenario()) {
            var original = scenario.checkpoint(100L);
            var before = scenario.state();
            var base = assertDoesNotThrow(() -> copyPublicResult(original.input(), original.result(), original.result().commitCompanion()));
            assertNull(base.rootedProjection(), "Public reconstruction never manufactures rooted proof");
            assertThrows(IllegalArgumentException.class, () -> copyPublicResult(original.input(), original.result(), null));
            assertEquals(before, scenario.state());
        }
    }

    @Test void realEventlessOwnerWorkCannotMasqueradeAsACheckpointReference() throws Exception {
        try (var scenario = new Scenario()) {
            var entry = scenario.f.append(scenario.source, "rcp2/source", "tick", 100L, "{}");
            var applied = scenario.f.blue.processing().processNext(scenario.parent);
            assertEquals(EntryDisposition.APPLIED, applied.entry(entry).disposition());
            String key = applied.entry(entry).closures().get(0).closureId();
            var input = scenario.f.blue.advanced().closureInvocation(key).orElseThrow();
            var result = scenario.f.blue.advanced().closureExecution(key).orElseThrow();
            var parentId = cid(scenario.parent);
            var transition = result.managedTransitionReceipts().stream().filter(r -> r.documentId().equals(parentId)).findFirst().orElseThrow();
            assertEquals(1L, scenario.parent.snapshot().longAt("/seen"));
            assertEquals(0L, scenario.source.snapshot().longAt("/counter"), "Source work was root-local only");
            assertTrue(transition.emittedRootEvents().isEmpty(), "The counterexample owner is eventless despite real local work");
            assertTrue(result.checkpointWrites().stream().anyMatch(w -> w.targetManagedScopeIdentity().equals(rootScopeIdentity(cid(scenario.source)))));
            assertTrue(result.rootedProjection().checkpointReferenceProofIdentity(parentId).isEmpty());
            var before = scenario.state();
            assertThrows(IllegalArgumentException.class, () -> new ManagedRepresentationTransition(parentId,
                    input.snapshot().managedDocument(parentId).epoch(), scenario.anchor(), scenario.anchor(),
                    input, result, transition.transitionReceiptIdentity()));
            assertThrows(IllegalArgumentException.class, () -> scenario.f.control.verifyRetainedCheckpointPosition(
                    key, result, scenario.parent.id(), wrongHash()));
            assertEquals(before, scenario.state());
        }
    }

    @Test void anOwnersOwnAcceptedCheckpointIsNotAReadOnlyDependencyRepresentation() throws Exception {
        try (var scenario = new Scenario()) {
            var entry = scenario.f.append(scenario.source, "rcp2/source", "noop", 100L, "{}");
            var applied = scenario.f.blue.processing().processNext(scenario.source);
            assertEquals(EntryDisposition.APPLIED, applied.entry(entry).disposition());
            String key = applied.entry(entry).closures().get(0).closureId();
            var input = scenario.f.blue.advanced().closureInvocation(key).orElseThrow();
            var result = scenario.f.blue.advanced().closureExecution(key).orElseThrow();
            var sourceId = cid(scenario.source);
            var transition = result.managedTransitionReceipts().stream().filter(r -> r.documentId().equals(sourceId)).findFirst().orElseThrow();
            assertTrue(result.rootedProjection().owns(sourceId));
            assertTrue(input.directDeliveries().stream().anyMatch(d -> d.targetDocumentId().equals(sourceId)));
            assertTrue(result.checkpointWrites().stream().anyMatch(w -> w.targetManagedScopeIdentity().equals(rootScopeIdentity(sourceId))));
            assertTrue(transition.emittedRootEvents().isEmpty());
            assertEquals(0L, scenario.source.snapshot().longAt("/counter"));
            assertTrue(result.rootedProjection().checkpointReferenceProofIdentity(sourceId).isEmpty());
            String anchor = scenario.f.blue.advanced().auditManagedEpoch(scenario.source.id(), 0L).orElseThrow().receiptIdentity();
            var before = scenario.state();
            assertThrows(IllegalArgumentException.class, () -> new ManagedRepresentationTransition(sourceId,
                    input.snapshot().managedDocument(sourceId).epoch(), anchor, anchor, input, result, transition.transitionReceiptIdentity()));
            assertThrows(IllegalArgumentException.class, () -> scenario.f.control.verifyRetainedCheckpointPosition(
                    key, result, scenario.source.id(), wrongHash()));
            assertEquals(before, scenario.state());
        }
    }

    private static ClosureProcessResult copyPublicResult(ClosureInvocationInput input, ClosureProcessResult r,
            ClosureCommitCompanion companion) {
        return new ClosureProcessResult(input.snapshot(), r.status(), r.invocationIdentity(), r.outputClosureIdentity(),
                r.graphGeneration(), r.resultingDocuments(), r.resultingComponents(), r.occurrenceBindings(),
                r.occurrenceBindingSetIdentity(), r.graphChanges(), r.graphChangesIdentity(), r.subscriptionDeltas(),
                r.subscriptionDeltasIdentity(), r.checkpointWrites(), r.checkpointWritesIdentity(), r.publicEvents(),
                r.publicEventsIdentity(), r.totalGas(), r.gasTrace(), r.gasTraceIdentity(), r.rejectedCharge(),
                r.rejectedWorkOccurrence(), companion, r.diagnostic());
    }

    private static blue.language.processor.closure.DocumentId cid(DocumentHandle document) {
        return new blue.language.processor.closure.DocumentId(document.id().value());
    }
    private static String wrongHash() { return "sha256:" + "f".repeat(64); }

    private record Committed(String key, ClosureInvocationInput input, ClosureProcessResult result,
            ManagedRepresentationTransition position) { }

    private static final class Scenario implements AutoCloseable {
        final RootedSdkFixture f = new RootedSdkFixture();
        final DocumentHandle source;
        final DocumentHandle parent;
        String predecessor;
        Scenario() throws Exception {
            source = f.startYaml(RootedSdkFixture.resource("source.yaml") + """
                      emitUnmatched:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request: {}
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: RCP2/Unmatched
                          - $return: true
                      noop:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request: {}
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $return: true
                    """, "rcp2/source");
            parent = f.startYaml(RootedSdkFixture.resource("parent.yaml")
                    + "\nchild: {blueId: " + f.retain(source) + "}\n", "rcp2/parent");
            predecessor = anchor();
        }
        String anchor() { return f.blue.advanced().auditManagedEpoch(parent.id(), 0L).orElseThrow().receiptIdentity(); }
        Committed checkpoint(long timestamp) { return commit(f.append(source, "rcp2/source", "emitUnmatched", timestamp, "{}")); }
        Committed commit(EntryHandle entry) {
            var applied = f.blue.processing().processNext(parent);
            assertEquals(EntryDisposition.APPLIED, applied.entry(entry).disposition());
            String key = applied.entry(entry).closures().get(0).closureId();
            var input = f.blue.advanced().closureInvocation(key).orElseThrow();
            var result = f.blue.advanced().closureExecution(key).orElseThrow();
            var transition = result.managedTransitionReceipts().stream().filter(r -> r.documentId().equals(cid(parent))).findFirst().orElseThrow();
            var position = new ManagedRepresentationTransition(cid(parent), 0L, anchor(), predecessor,
                    input, result, transition.transitionReceiptIdentity());
            assertTrue(position.rootedCheckpointReferenceProofIdentity().isPresent());
            assertTrue(result.rootedProjection().owns(cid(parent)));
            assertFalse(result.rootedProjection().owns(cid(source)));
            assertEquals(0L, parent.snapshot().epoch());
            assertEquals(1, f.history(parent).size());
            assertEquals(1, f.history(source).size());
            predecessor = position.positionIdentity();
            return new Committed(key, input, result, position);
        }
        List<Object> state() {
            return List.of(parent.snapshot().exact().json(), source.snapshot().exact().json(),
                    f.history(parent), f.history(source),
                    f.blue.advanced().auditTimelineEntries().stream().map(e -> e.exact().json()).toList(),
                    f.control.retainedTerminals(parent.id()).stream().map(RootedCalculationFixture.RetainedTerminal::identity).sorted().toList());
        }
        @Override public void close() { f.close(); }
    }
}
