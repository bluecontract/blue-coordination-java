package blue.coordination.internal;

import blue.coordination.sdk.*;
import blue.language.processor.closure.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Real SDK checkpoint publications retain complete authority checks on every reconstruction. */
final class ManagedRepresentationHistoryEvidenceTest {
    private static final String WRONG = "sha256:" + "f".repeat(64);

    @Test void repeatedHistoryInstancesReconstructExactImmutableEvidenceAndRestartCold() throws Exception {
        // given
        try (var f = new Scenario()) {
            var before = f.state();
            var first = f.chain();
            // when
            // A different short-lived history reader uses the same store.
            var repeated = f.chain();
            // then
            // POC readers reuse the same complete proof only after current authority checks.
            assertEquals(2, first.transitions().size());
            for (int i = 0; i < first.transitions().size(); i++) {
                assertSame(first.transitions().get(i), repeated.transitions().get(i));
                assertEquals(first.transitions().get(i).positionIdentity(), repeated.transitions().get(i).positionIdentity());
                new ManagedRepresentationHistory(f.engine.documents()).verifySupplied(first.transitions().get(i));
            }
            var retained = first.transitions().get(0);
            retained.originalInput().snapshot().managedDocument(retained.documentId()).document().name("detached input mutation");
            retained.afterDocument().name("detached result mutation");
            assertThrows(UnsupportedOperationException.class, () -> retained.originalInput().directDeliveries().clear());
            assertThrows(UnsupportedOperationException.class, () -> retained.originalResult().resultingDocuments().clear());
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            var cold = f.chain();
            for (int i = 0; i < first.transitions().size(); i++) {
                assertNotSame(first.transitions().get(i), cold.transitions().get(i));
                assertEquals(first.transitions().get(i).positionIdentity(), cold.transitions().get(i).positionIdentity());
                assertSame(first.transitions().get(i).originalResult(), cold.transitions().get(i).originalResult());
            }
            assertEquals(before, f.state(), "Restart/reverification changes no exact history, output, events, or gas");
            f.engine.close();
            assertNotSame(cold.transitions().get(0), f.chain().transitions().get(0), "Reconstruction remains independent after close");
        }
    }

    @Test void aCapturedTailStillRejectsAGenuineLaterNumberedReceipt() throws Exception {
        // given
        try (var f = new Scenario()) {
            var tail = f.chain();
            var cursor = new ManagedRepresentationCursor(tail.anchor().receiptIdentity(), tail.anchor().receiptIdentity(),
                    tail.targetPositionIdentity(), null);
            var history = new ManagedRepresentationHistory(f.engine.documents());
            assertNull(history.atCaptured(f.parent.id(), 0, cursor).nextRevisionReceiptIdentity());
            // when
            // Real parent reaction to the child's tick publishes its first numbered successor.
            var entry = f.append(300, "tick");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(f.parent).entry(entry).disposition());
            assertEquals(1, f.parent.snapshot().epoch());
            // then
            // The current next-receipt/anchor guard runs on every reconstruction.
            assertNotNull(f.chain().nextRevisionReceiptIdentity());
            assertEquals(tail.transitions().get(0).positionIdentity(), f.chain().transitions().get(0).positionIdentity());
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> history.atCaptured(f.parent.id(), 0, cursor)).getMessage().contains("immutable next revision changed"));
        }
    }

    @Test void reconstructedPublicationsPreserveEveryConstructorOperandAndRejection() throws Exception {
        // given
        try (var f = new Scenario()) {
            var p = f.chain().transitions().get(0);
            var publication = f.publication(p);
            var cached = prove(publication, p);
            assertEquals(cached.positionIdentity(), prove(publication, p).positionIdentity());
            // when
            // A value-equal durable record is not the same retained publication object.
            var copy = new ContractsClosurePublicationReceipt(publication.publicationIdentity(), publication.documentIds(),
                    publication.attempt(), publication.automaticRetryCount(), publication.managedSurfaceEvidence(),
                    publication.rejectedDraftPlan(), publication.rootedTerminalEvidence());
            assertEquals(publication, copy);
            var differentPublication = prove(copy, p);
            // then
            assertNotSame(cached, differentPublication);
            assertEquals(cached.positionIdentity(), differentPublication.positionIdentity());
            for (boolean anchor : List.of(false, true)) {
                var changed = prove(publication, p.documentId(), p.epoch(), anchor ? WRONG : p.anchorReceiptIdentity(),
                        anchor ? p.predecessorPositionIdentity() : WRONG, p.originalInput(), p.originalResult(),
                        p.transitionReceipt().transitionReceiptIdentity());
                assertNotSame(cached, changed);
                assertNotEquals(cached.positionIdentity(), changed.positionIdentity());
            }
            assertThrows(IllegalArgumentException.class, () -> prove(publication, p.documentId(), p.epoch() + 1,
                    p.anchorReceiptIdentity(), p.predecessorPositionIdentity(), p.originalInput(), p.originalResult(),
                    p.transitionReceipt().transitionReceiptIdentity()));
            assertThrows(IllegalArgumentException.class, () -> prove(publication, new blue.language.processor.closure.DocumentId("unowned"),
                    p.epoch(), p.anchorReceiptIdentity(), p.predecessorPositionIdentity(), p.originalInput(), p.originalResult(),
                    p.transitionReceipt().transitionReceiptIdentity()));
            assertThrows(IllegalArgumentException.class, () -> prove(publication, p.documentId(), p.epoch(),
                    p.anchorReceiptIdentity(), p.predecessorPositionIdentity(), p.originalInput(), p.originalResult(), WRONG));

            var original = p.originalInput();
            var badInput = ClosureInvocationInput.processClosure(WRONG, original.snapshot(), original.cause(),
                    original.directDeliveries(), original.directDeliverySnapshotIdentity(), original.executionPolicy(), original.environment());
            var r = p.originalResult();
            var unrootedResult = new ClosureProcessResult(original.snapshot(), r.status(), r.invocationIdentity(), r.outputClosureIdentity(),
                    r.graphGeneration(), r.resultingDocuments(), r.resultingComponents(), r.occurrenceBindings(),
                    r.occurrenceBindingSetIdentity(), r.graphChanges(), r.graphChangesIdentity(), r.subscriptionDeltas(),
                    r.subscriptionDeltasIdentity(), r.checkpointWrites(), r.checkpointWritesIdentity(), r.publicEvents(),
                    r.publicEventsIdentity(), r.totalGas(), r.gasTrace(), r.gasTraceIdentity(), r.rejectedCharge(),
                    r.rejectedWorkOccurrence(), r.commitCompanion(), r.diagnostic());
            assertEquals(r.outputClosureIdentity(), unrootedResult.outputClosureIdentity());
            assertEquals(r.commitCompanion().companionIdentity(), unrootedResult.commitCompanion().companionIdentity());
            for (int retry = 0; retry < 2; retry++) {
                assertThrows(IllegalArgumentException.class, () -> prove(publication, p.documentId(), p.epoch(),
                        p.anchorReceiptIdentity(), p.predecessorPositionIdentity(), badInput, r,
                        p.transitionReceipt().transitionReceiptIdentity()));
                assertThrows(IllegalArgumentException.class, () -> prove(publication, p.documentId(), p.epoch(),
                        p.anchorReceiptIdentity(), p.predecessorPositionIdentity(), original, unrootedResult,
                        p.transitionReceipt().transitionReceiptIdentity()));
            }
            assertEquals(cached.positionIdentity(), prove(publication, p).positionIdentity(),
                    "Failed variants do not change the original proof");
        }
    }

    @Test void repeatedReadsDoNotReplaceCurrentPublicationRowsAnchorsHeadsOrRootedAuthority() throws Exception {
        // given
        try (var f = new Scenario()) {
            var chain = f.chain();
            var originalState = f.state();
            var p = chain.transitions().get(0);
            var publication = f.publication(p);
            // Test-only store-image corruption: every ordinary reader must reject.
            var stateField = InMemoryDocumentStore.class.getDeclaredField("state");
            stateField.setAccessible(true);
            var state = stateField.get(f.engine.documents());
            var mapField = state.getClass().getDeclaredField("closurePublicationReceipts");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked") var originals = (Map<String, ContractsClosurePublicationReceipt>) mapField.get(state);
            try {
                var missing = new LinkedHashMap<>(originals);
                missing.remove(publication.publicationIdentity());
                // when
                mapField.set(state, Map.copyOf(missing));
                // then
                assertTrue(assertThrows(IllegalArgumentException.class, f::chain).getMessage().contains("Original representation commit"));
                var unowned = new ContractsClosurePublicationReceipt(publication.publicationIdentity(), publication.documentIds(),
                        publication.attempt(), publication.automaticRetryCount(), publication.managedSurfaceEvidence(), null, null);
                var forged = new LinkedHashMap<>(originals);
                forged.put(publication.publicationIdentity(), unowned);
                mapField.set(state, Map.copyOf(forged));
                assertTrue(assertThrows(IllegalArgumentException.class, f::chain).getMessage().contains("Original rooted representation authority"));
            } finally { mapField.set(state, originals); }

            var session = f.engine.documents().require(f.parent.id());
            var rows = new java.util.ArrayList<>(session.representationTransitions());
            try (var corruption = new RepresentationRowCorruption(session)) {
                var last = rows.remove(rows.size() - 1);
                corruption.replace(rows);
                assertTrue(assertThrows(IllegalArgumentException.class, f::chain).getMessage().contains("terminal representation head"));
                rows.add(new DocumentSession.ComponentRepresentationTransition(last.epoch(), last.beforeBlueId(), "forged-head",
                        last.transitionReceiptIdentity(), last.originalPublicationIdentity()));
                corruption.replace(rows);
                assertTrue(assertThrows(IllegalArgumentException.class, f::chain).getMessage().contains("durable source history"));
            }
            var anchorState = stateField.get(f.engine.documents());
            try {
                f.engine.documents().replaceManagedEpochEvidenceForTesting(f.parent.id(), 0, null, null);
                assertTrue(assertThrows(IllegalArgumentException.class, f::chain).getMessage().contains("anchor is unavailable"));
            } finally { stateField.set(f.engine.documents(), anchorState); }
            var forgedPosition = new ManagedRepresentationTransition(p.documentId(), p.epoch(), WRONG,
                    p.predecessorPositionIdentity(), p.originalInput(), p.originalResult(), p.transitionReceipt().transitionReceiptIdentity());
            assertThrows(IllegalArgumentException.class, () -> new ManagedRepresentationHistory(f.engine.documents()).verifySupplied(forgedPosition));
            assertEquals(p.positionIdentity(), f.chain().transitions().get(0).positionIdentity());
            assertEquals(originalState, f.state());
        }
    }

    @Test void stagedEvidenceAndFailedPublicationNeverBecomeDurableAuthority() throws Exception {
        // given
        try (var f = new Scenario()) {
            var entry = f.append(300);
            var invocation = f.engine.contractsClosureAdapter().captureRoot(f.parent.id(),
                    f.engine.auditTimelineEntry(entry.blueId()).orElseThrow(), null).invocations().get(0);
            var attempt = new BlueClosureContracts(f.engine.runtime().documentProcessor()).processClosure(invocation.input());
            assertTrue(attempt.isComplete());
            var result = attempt.processResult();
            assertTrue(result.commits());
            var rooted = RootedTerminalEvidence.capture(invocation, result);
            var key = result.rootedProjection().context().terminalKey(result.rootedProjection().deliveryBasisIdentity());
            var staged = new ContractsClosurePublicationReceipt(key, List.of(f.parent.id()), attempt, 0,
                    ManagedSurfacePublicationEvidence.committed(invocation, result), null, rooted);
            var last = f.chain().transitions().get(1);
            var row = result.managedTransitionReceipts().stream().filter(r -> r.documentId().equals(last.documentId())).findFirst().orElseThrow();
            var before = f.state();
            // when
            var one = prove(staged, last.documentId(), 0,
                    last.anchorReceiptIdentity(), last.positionIdentity(), row.transitionReceiptIdentity());
            var two = prove(staged, last.documentId(), 0,
                    last.anchorReceiptIdentity(), last.positionIdentity(), row.transitionReceiptIdentity());
            // then
            assertNotSame(one, two);
            assertEquals(one.positionIdentity(), two.positionIdentity());
            assertTrue(f.engine.documents().closurePublicationReceipt(key).isEmpty());
            var reachedSwap = new java.util.concurrent.atomic.AtomicBoolean();
            f.engine.contractsClosureAdapter().onStoreFailurePoint(point -> {
                if (point == MultiDocumentPublicationTransaction.FailurePoint.BEFORE_SWAP) {
                    reachedSwap.set(true);
                    throw new IllegalStateException("proof abort control");
                }
            });
            try { assertThrows(RuntimeException.class, () -> f.blue.processing().processNext(f.parent)); }
            finally { f.engine.contractsClosureAdapter().onStoreFailurePoint(ignored -> { }); }
            assertTrue(reachedSwap.get(), "The failure must reach the actual pre-swap boundary");
            assertTrue(f.engine.documents().closurePublicationReceipt(key).isEmpty());
            assertEquals(before, f.state(), "No staged head, history, result or gas receipt survives the abort");
            var afterAbort = prove(staged, last.documentId(), 0,
                    last.anchorReceiptIdentity(), last.positionIdentity(), row.transitionReceiptIdentity());
            assertNotSame(two, afterAbort);
            assertEquals(two.positionIdentity(), afterAbort.positionIdentity());
        }
    }

    private static ManagedRepresentationTransition prove(
            ContractsClosurePublicationReceipt publication, ManagedRepresentationTransition p) {
        return prove(publication, p.documentId(), p.epoch(), p.anchorReceiptIdentity(), p.predecessorPositionIdentity(),
                p.originalInput(), p.originalResult(), p.transitionReceipt().transitionReceiptIdentity());
    }

    private static ManagedRepresentationTransition prove(
            ContractsClosurePublicationReceipt publication, blue.language.processor.closure.DocumentId document,
            long epoch, String anchor, String predecessor, String receipt) {
        return prove(publication, document, epoch, anchor, predecessor,
                publication.managedSurfaceEvidence().originalInvocation(), publication.attempt().processResult(), receipt);
    }

    private static ManagedRepresentationTransition prove(
            ContractsClosurePublicationReceipt publication, blue.language.processor.closure.DocumentId document,
            long epoch, String anchor, String predecessor, ClosureInvocationInput input, ClosureProcessResult result, String receipt) {
        assertNotNull(publication);
        return new ManagedRepresentationTransition(document, epoch, anchor, predecessor, input, result, receipt);
    }

    /** The maintained checkpoint-only source/parent shape, with two real atomic publications. */
    private static final class Scenario implements AutoCloseable {
        final Map<String, String> exact = new LinkedHashMap<>();
        final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds()
                .release(BundledContracts10Release.manifest().blueLanguageSpecification(), BundledContracts10Release.manifest().contractsSpecification())
                .exactNodeProvider(id -> Optional.ofNullable(exact.get(id))).build();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        final TimelineHandle timeline = blue.timelines().register("rcp2/source", "alice");
        final DocumentHandle source;
        final DocumentHandle parent;
        String previous;
        Scenario() throws Exception {
            blue.timelines().register("rcp2/parent", "alice");
            source = blue.documents().admitStaticProcessEmbedded(resource("source.yaml") + """
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
                    """, ActivationPolicy.importFullHistory()).document("root");
            exact.put(source.snapshot().blueId(), source.snapshot().exact().json());
            parent = blue.documents().admitStaticProcessEmbedded(resource("parent.yaml")
                    + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n", ActivationPolicy.importFullHistory()).document("root");
            for (long time : List.of(100L, 200L)) {
                var entry = append(time);
                var applied = blue.processing().processNext(parent);
                assertEquals(EntryDisposition.APPLIED, applied.entry(entry).disposition());
                assertTrue(applied.quiescent());
                assertEquals(0, parent.snapshot().epoch());
            }
            assertEquals(2, chain().transitions().size());
        }
        EntryHandle append(long time) { return append(time, "emitUnmatched"); }
        EntryHandle append(long time, String operation) {
            String yaml = """
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/source}
                    timestamp: %d
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    message:
                      type: Coordination/Operation Request
                      document: {blueId: %s}
                      requireExactDocumentVersion: false
                      operation: %s
                      channel: owner
                      request: {}
                    """.formatted(time, source.snapshot().blueId(), operation);
            if (previous != null) yaml += "\nprevEntry: {blueId: " + previous + "}\n";
            var entry = blue.events().from(timeline).exact(blue.values().yaml(yaml)).submit();
            previous = entry.blueId();
            return entry;
        }
        ManagedRepresentationHistory.Chain chain() { return new ManagedRepresentationHistory(engine.documents()).at(parent.id(), 0); }
        ContractsClosurePublicationReceipt publication(ManagedRepresentationTransition p) {
            return engine.documents().closurePublicationReceipt(p.originalResult().rootedProjection().context()
                    .terminalKey(p.originalResult().rootedProjection().deliveryBasisIdentity())).orElseThrow();
        }
        List<Object> state() {
            return List.of(parent.snapshot().exact().json(), source.snapshot().exact().json(),
                    blue.advanced().auditManagedEpochs(parent.id()).stream().map(r -> List.of(r.receiptIdentity(), r.processingGas())).toList(),
                    blue.advanced().auditManagedEpochs(source.id()).stream().map(r -> List.of(r.receiptIdentity(), r.processingGas())).toList(),
                    chain().transitions().stream().map(p -> List.of(p.positionIdentity(), p.originalResult().outputClosureIdentity(),
                            p.originalResult().publicEventsIdentity(), p.originalResult().gasTraceIdentity(), p.originalResult().totalGas())).toList());
        }
        @Override public void close() { blue.close(); }
        private static String resource(String name) throws Exception {
            try (var in = ManagedRepresentationHistoryEvidenceTest.class.getResourceAsStream("/rooted/" + name)) {
                return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
