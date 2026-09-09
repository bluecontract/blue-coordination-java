package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Proposed SDK regression: genuine retained reference changes, never fabricated source positions. */
final class RootedHistoricalReferenceChainTest {
    @Test void savedAuthoredParentTraversesTheActualSameEpochTail() throws Exception { run(false); }
    @Test void savedAuthoredParentTraversesTheActualIntermediateChain() throws Exception { run(true); }

    @Test void terminalAnchorSurvivesRestartBeforeEitherRepresentationPosition() throws Exception {
        run(false, false, true);
    }
    @Test void attachment400CompletesItsTailBeforeAnAlreadyCommittedNumbered500() throws Exception {
        run(false, true, true);
    }

    private static List<List<Object>> receiptState(RootedSdkFixture f, DocumentHandle document) {
        return f.blue.advanced().auditManagedEpochs(document.id()).stream().map(receipt -> List.<Object>of(
                receipt.receiptIdentity(), receipt.documentId().value(), receipt.epoch(), receipt.kind(),
                receipt.beforeBlueId(), receipt.afterBlueId(), receipt.afterDocument().json(),
                receipt.originalCauseIdentity(), receipt.sourceOrder().map(SourceOrder::components),
                receipt.contractsTransitionReceiptIdentity(), receipt.commitCompanionIdentity(), receipt.processingGas(),
                receipt.sourceEntry().map(entry -> List.of(entry.exact().json(), entry.request().map(ExactBlueValue::json),
                        entry.previousEntryBlueId(), entry.operation(), entry.channel(), entry.timestampMicros(),
                        entry.globalSequence(), entry.timelineSequence())),
                receipt.emittedEvents().stream().map(event -> List.of(event.managedEventIdentity(), event.ordinal(),
                        event.eventOccurrenceOrdinal(), event.sourceDocumentId().value(), event.eventOccurrenceIdentity(),
                        event.eventBlueId(), event.exactEvent().json(), event.publicAtSource())).toList())).toList();
    }

    private static void run(boolean followingRevision) throws Exception {
        run(followingRevision, false, false);
    }

    private static void run(boolean followingRevision, boolean futureRevision, boolean restartAtAnchor) throws Exception {
        String sourceYaml = RootedSdkFixture.resource("source.yaml") + """
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
                """;
        String seal = """
                  seal:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendChange:
                          op: replace
                          path: /seals
                          val: 1
                      - $return: true
                """;
        try (var f = new RootedSdkFixture()) {
            var source = f.startYaml(sourceYaml, "rcp2/source");
            String parentYaml = "seals: 0\n" + RootedSdkFixture.resource("parent.yaml") + seal
                    + "\nchild: {blueId: " + f.retain(source) + "}\n";
            var savedParent = f.blue.values().yaml(parentYaml);
            f.exact.put(savedParent.blueId(), savedParent.json());
            var parent = f.startYaml(parentYaml, "rcp2/parent");
            assertEquals(savedParent.blueId(), parent.id().value());
            var genesis = receiptState(f, parent);
            var independentSource = source.snapshot().exact().json();
            var independentSourceHistory = receiptState(f, source);
            String previousParent = parent.snapshot().blueId();
            var representations = new ArrayList<String>(); representations.add(previousParent);
            var recordedPositions = new ArrayList<String>();
            String positionPredecessor = f.blue.advanced().auditManagedEpoch(parent.id(), 0L).orElseThrow().receiptIdentity();
            for (long timestamp : List.of(100L, 200L)) {
                var input = f.append(source, "rcp2/source", "emitUnmatched", timestamp, "{}");
                var applied = f.blue.processing().processNext(parent);
                assertEquals(EntryDisposition.APPLIED, applied.entry(input).disposition());
                assertTrue(applied.quiescent());
                assertNotEquals(previousParent, parent.snapshot().blueId());
                assertEquals(0L, parent.snapshot().epoch());
                assertEquals(genesis, receiptState(f, parent));
                assertEquals(independentSource, source.snapshot().exact().json());
                assertEquals(independentSourceHistory, receiptState(f, source));
                previousParent = parent.snapshot().blueId(); representations.add(previousParent);
                String publication = applied.entry(input).closures().get(0).closureId();
                var originalInput = f.blue.advanced().closureInvocation(publication).orElseThrow();
                var originalResult = f.blue.advanced().closureExecution(publication).orElseThrow();
                var parentId = new blue.language.processor.closure.DocumentId(parent.id().value());
                var transition = originalResult.managedTransitionReceipts().stream()
                        .filter(row -> row.documentId().equals(parentId)).findFirst().orElseThrow();
                var position = new blue.language.processor.closure.ManagedRepresentationTransition(parentId, 0L,
                        f.blue.advanced().auditManagedEpoch(parent.id(), 0L).orElseThrow().receiptIdentity(),
                        positionPredecessor, originalInput, originalResult, transition.transitionReceiptIdentity());
                assertTrue(position.rootedCheckpointReferenceProofIdentity().isPresent(),
                        "The actual LIVE checkpoint transition needs the separate authenticated rooted class");
                assertEquals(originalResult.rootedProjection().checkpointReferenceProofIdentity(parentId),
                        position.rootedCheckpointReferenceProofIdentity());
                positionPredecessor = position.positionIdentity(); recordedPositions.add(positionPredecessor);
            }
            assertEquals(3, representations.stream().distinct().count());
            if (followingRevision) {
                var sealEntry = f.append(parent, "rcp2/parent", "seal", 300L, "{}");
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(parent).entry(sealEntry).disposition());
                assertEquals(1L, parent.snapshot().epoch());
                var next = f.blue.advanced().auditManagedEpoch(parent.id(), 1L).orElseThrow();
                assertEquals(representations.get(2), next.beforeBlueId().orElseThrow());
            }
            String selectedParentBlueId = parent.snapshot().blueId();
            var sourceHead = parent.snapshot().exact().json();
            var sourceHistory = receiptState(f, parent);
            String consumerYaml = "updates: 0\n" + RootedSdkFixture.resource("parent.yaml")
                    .replace("RCP2 Parent", "RCP2 Representation Consumer")
                    .replace("rcp2/parent", "rcp2/consumer") + """
                      referenceChanges:
                        type: Document Update Channel
                        path: /child
                      countChanges:
                        type: Coordination/Sequential Workflow
                        channel: referenceChanges
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $appendChange:
                              op: replace
                              path: /updates
                              val:
                                $add:
                                - $document: /updates
                                - 1
                          - $return: true
                    """;
            var consumer = f.startYaml(consumerYaml, "rcp2/consumer");
            var entry = f.append(consumer, "rcp2/consumer", "attach", 400L,
                    "child: {blueId: " + savedParent.blueId() + "}");
            EntryHandle future = null;
            if (futureRevision) {
                // Establish C400 first, before P500 can advance any provider-completeness frontier.
                future = f.append(parent, "rcp2/parent", "seal", 500L, "{}");
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(parent).entry(future).disposition());
                assertEquals(1L, parent.snapshot().epoch(), "The future source revision is genuine and independently committed");
                sourceHead = parent.snapshot().exact().json();
                sourceHistory = receiptState(f, parent);
                assertNotEquals(selectedParentBlueId, parent.snapshot().blueId());
            }
            var positions = new ArrayList<String>();
            boolean ready = false;
            boolean anchorRestarted = false;
            int capturedAnchors = 0;
            for (int selection = 0; selection < 32; selection++) {
                long updatesBefore = ((Number) f.blue.advanced().auditDocument(consumer.id()).current().copyNode().get("/updates")).longValue();
                String readyBefore = consumer.snapshot().blueId();
                int positionsBefore = positions.size();
                var result = f.blue.processing().processNext(consumer);
                assertFalse(result.blocked(), "selection="+selection+" diagnostic="+result.diagnostic()+" failures="+result.managedEpochEvidenceFailures()+" entries="+result.entries().stream().map(e -> e.disposition()+":"+e.diagnostic()).toList()+" attempts="+result.managedEpochApplicationAttempts()+" local="+result.rootedRetainedResults().stream().map(e -> e.disposition()+":"+e.diagnostic()).toList()+" plans="+f.blue.advanced().auditManagedCatchUpPlans(consumer.id()).stream().map(p -> p.status()+":"+p.waitingCode()+":"+p.waitingMessage()).toList());
                if (selection == 0) assertEquals(EntryDisposition.APPLIED, result.entry(entry).disposition());
                for (var attempt : result.managedEpochApplicationAttempts())
                    if (attempt.work().sourceDocumentId().equals(parent.id())) attempt.work().representationStep()
                            .ifPresent(step -> positions.add(step.after().positionIdentity()));
                for (var local : result.rootedRetainedApplications())
                    if (local.work().sourceDocumentId().equals(parent.id())) local.work().representationStep()
                            .ifPresent(step -> positions.add(step.after().positionIdentity()));
                var numbered = java.util.stream.Stream.concat(
                        result.managedEpochApplicationAttempts().stream().map(ManagedEpochApplicationAttempt::work),
                        result.rootedRetainedApplications().stream().map(DrainResult.RootedRetainedApplication::work))
                        .filter(work -> work.sourceDocumentId().equals(parent.id()) && work.successorRepresentationStep().isPresent()).toList();
                if (!numbered.isEmpty()) {
                    assertEquals(1, numbered.size());
                    assertTrue(numbered.get(0).representationStep().isEmpty(), "Numbered work did not execute a representation position");
                    var successor = numbered.get(0).successorRepresentationStep().orElseThrow();
                    assertEquals(recordedPositions.get(1), successor.before().targetPositionIdentity());
                    assertEquals(numbered.get(0).sourceReceiptIdentity(), successor.before().positionIdentity());
                    assertEquals(positionsBefore, positions.size(), "The successor is future evidence, not applied work");
                    assertFalse(f.blue.advanced().auditManagedOccurrence(consumer.id(), "/child").orElseThrow().active());
                    for (var attempt : result.managedEpochApplicationAttempts()) if (attempt.work() == numbered.get(0)) {
                        var receipt = attempt.receipt().orElseThrow();
                        assertTrue(receipt.representationCauseIdentity().isEmpty());
                        assertEquals(successor.causeIdentity(), receipt.successorRepresentationCauseIdentity().orElseThrow());
                        assertEquals(successor.before(), receipt.resultingRepresentationCursor().orElseThrow());
                        assertEquals(attempt.work().sourceEpoch() + 1L, receipt.resultingSourceCursor());
                    }
                    capturedAnchors++;
                    if (restartAtAnchor) {
                        var pendingHeads = List.of(consumer.snapshot().exact().json(), parent.snapshot().exact().json());
                        var plansBeforeRestart = f.blue.advanced().auditManagedCatchUpPlans(consumer.id());
                        CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                        assertEquals(pendingHeads, List.of(consumer.snapshot().exact().json(), parent.snapshot().exact().json()));
                        assertEquals(plansBeforeRestart, f.blue.advanced().auditManagedCatchUpPlans(consumer.id()));
                        assertFalse(f.blue.advanced().auditManagedOccurrence(consumer.id(), "/child").orElseThrow().active());
                        anchorRestarted = true;
                    }
                }
                if (positions.size() > positionsBefore) {
                    assertEquals(positionsBefore + 1, positions.size(), "Each call owns exactly one representation position");
                    assertEquals(updatesBefore + 1L, ((Number) f.blue.advanced().auditDocument(consumer.id()).current().copyNode().get("/updates")).longValue(),
                            "Real reference reaction runs once per position");
                }
                if (!f.blue.advanced().auditManagedDocumentReadiness(consumer.id()).orElseThrow().ready())
                    assertEquals(readyBefore, consumer.snapshot().blueId(), "Pending work keeps the earlier Ready view");
                assertEquals(sourceHead, parent.snapshot().exact().json());
                assertEquals(sourceHistory, receiptState(f, parent));
                assertEquals(independentSource, source.snapshot().exact().json());
                assertEquals(independentSourceHistory, receiptState(f, source));
                if (futureRevision && positions.size() == 2
                        && f.blue.advanced().auditManagedDocumentReadiness(consumer.id()).orElseThrow().ready()) {
                    ready = true; break;
                }
                if (result.quiescent()) { ready = true; break; }
            }
            assertTrue(ready, "An acyclic saved-original attachment must finish within 32 actual selections");
            assertEquals(recordedPositions, positions, "Historical traversal must use exactly the original committed positions");
            assertEquals(2, positions.size(), "Both actual same-epoch reference transitions must be traversed");
            assertEquals(2, positions.stream().distinct().count(), "No positional work may be duplicated");
            assertEquals(selectedParentBlueId, consumer.snapshot().valueAt("/child").blueId());
            if (!followingRevision) assertEquals(1, capturedAnchors, "The exact tail target is captured once at its numbered anchor");
            if (restartAtAnchor) assertTrue(anchorRestarted, "Restart must occur between the numbered step and P1");
            var occurrence = f.blue.advanced().auditManagedOccurrence(consumer.id(), "/child").orElseThrow();
            assertTrue(occurrence.active());
            assertTrue(f.blue.advanced().auditManagedDocumentReadiness(consumer.id()).orElseThrow().ready());
            var consumerHead = consumer.snapshot().exact().json();
            var consumerHistory = receiptState(f, consumer);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            if (!futureRevision) assertTrue(f.blue.processing().processNext(consumer).quiescent());
            assertEquals(consumerHead, consumer.snapshot().exact().json());
            assertEquals(consumerHistory, receiptState(f, consumer));
            assertEquals(sourceHead, parent.snapshot().exact().json());
            assertEquals(sourceHistory, receiptState(f, parent));
            if (futureRevision) {
                assertEquals(0L, ((Number) f.blue.values().retained(consumer.snapshot().valueAt("/child").blueId())
                        .orElseThrow().scalarAt("/seals")).longValue(), "Catch-up through 400 excludes genuine future 500");
                var later = f.blue.processing().processNext(consumer);
                assertTrue(later.managedEpochApplicationAttempts().isEmpty(), "500 is subsequent LIVE work, never historical tail work");
                assertEquals(EntryDisposition.APPLIED, later.entry(future).disposition());
                assertEquals(1L, ((Number) f.blue.values().retained(consumer.snapshot().valueAt("/child").blueId())
                        .orElseThrow().scalarAt("/seals")).longValue());
                assertEquals(sourceHead, parent.snapshot().exact().json());
                assertEquals(sourceHistory, receiptState(f, parent));
            }
        }
    }
}
