package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.internal.CoordinationTestControl;
import blue.language.processor.closure.ManagedRepresentationTransition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SDK-only source publications for the bounded successor tests. No source receipt is installed by this fixture. */
final class RootedTerminalTailSdkScenario implements AutoCloseable {
    final RootedSdkFixture f;
    final DocumentHandle source;
    final DocumentHandle parent;
    final DocumentHandle consumer;
    final ExactBlueValue savedParent;
    final EntryHandle attachment;
    final List<ManagedRepresentationTransition> positions = new ArrayList<>();
    final List<String> parentIdentities = new ArrayList<>();
    final List<EntryHandle> originalSourceEntries = new ArrayList<>();
    EntryHandle future;
    final String expectedConsumerChild;
    private final String sourceInitial;
    private final List<List<Object>> parentNumbered;

    RootedTerminalTailSdkScenario(long gas, boolean futurePosition, int reactionSteps) throws Exception {
        f = new RootedSdkFixture(ContractsExecutionPolicy.exactSharedGas(gas, "rooted-successor-boundary"));
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
        source = f.startYaml(sourceYaml, "rcp2/source");
        sourceInitial = source.snapshot().blueId();
        String parentYaml = RootedSdkFixture.resource("parent.yaml")
                + "\nchild: {blueId: " + f.retain(source) + "}\n";
        savedParent = f.blue.values().yaml(parentYaml);
        f.exact.put(savedParent.blueId(), savedParent.json());
        parent = f.startYaml(parentYaml, "rcp2/parent");
        assertEquals(savedParent.blueId(), parent.id().value(), "The saved authored input must be the actual document ID");
        parentNumbered = receipts(parent);
        parentIdentities.add(parent.snapshot().blueId());
        for (int index = 0; index < 2; index++) {
            EntryHandle entry = f.append(source, "rcp2/source", "emitUnmatched", (index + 1L) * 100L, "{}");
            originalSourceEntries.add(entry);
            var applied = f.blue.advanced().process(parent, entry, ContractsExecutionPolicy.releaseDefault());
            assertEquals(EntryDisposition.APPLIED, applied.entry(entry).disposition());
            assertTrue(applied.quiescent());
            retainPosition(applied.entry(entry).closures().get(0).closureId());
            assertEquals(sourceInitial, source.snapshot().blueId(), "Incoming processing must not publish S");
        }
        assertEquals(parentNumbered, receipts(parent));
        assertNotEquals(parentIdentities.get(0), parentIdentities.get(1));
        assertEquals(3L, parentIdentities.stream().distinct().count(),
                "The fixture must retain three genuinely distinct source representations");
        expectedConsumerChild = parent.snapshot().blueId();
        consumer = f.startYaml(consumerYaml(reactionSteps), "rcp2/consumer");
        attachment = f.append(consumer, "rcp2/consumer", "attach", 400L,
                "child: {blueId: " + savedParent.blueId() + "}");
        if (futurePosition) {
            future = f.append(source, "rcp2/source", "emitUnmatched", 500L, "{}");
            var applied = f.blue.advanced().process(parent, future, ContractsExecutionPolicy.releaseDefault());
            assertEquals(EntryDisposition.APPLIED, applied.entry(future).disposition());
            retainPosition(applied.entry(future).closures().get(0).closureId());
            assertEquals(parentNumbered, receipts(parent));
            assertNotEquals(expectedConsumerChild, parent.snapshot().blueId());
        }
        var attached = f.blue.advanced().process(consumer, attachment, ContractsExecutionPolicy.releaseDefault());
        assertEquals(EntryDisposition.APPLIED, attached.entry(attachment).disposition());
        assertFalse(attached.quiescent());
        var work = f.control.registeredOwnedHistory(consumer.id());
        assertEquals(parent.id(), work.sourceDocumentId());
        assertEquals(0L, work.sourceEpoch());
        var successor = work.successorRepresentationCause().orElseThrow();
        assertEquals(positions.get(0).positionIdentity(), successor.transition().positionIdentity());
        assertEquals(positions.get(1).positionIdentity(), successor.targetPositionIdentity());
        assertTrue(work.representationCause().isEmpty());
    }

    private void retainPosition(String publication) {
        var input = f.blue.advanced().closureInvocation(publication).orElseThrow();
        var result = f.blue.advanced().closureExecution(publication).orElseThrow();
        var id = new blue.language.processor.closure.DocumentId(parent.id().value());
        var receipt = result.managedTransitionReceipts().stream().filter(row -> row.documentId().equals(id))
                .findFirst().orElseThrow();
        String anchor = f.blue.advanced().auditManagedEpoch(parent.id(), 0L).orElseThrow().receiptIdentity();
        var transition = new ManagedRepresentationTransition(id, 0L, anchor,
                positions.isEmpty() ? anchor : positions.get(positions.size() - 1).positionIdentity(),
                input, result, receipt.transitionReceiptIdentity());
        assertTrue(transition.rootedCheckpointReferenceProofIdentity().isPresent());
        assertTrue(receipt.emittedRootEvents().isEmpty());
        assertEquals(0L, parent.snapshot().epoch());
        assertEquals(parent.snapshot().blueId(), receipt.afterBlueId());
        f.control.verifySuppliedRepresentationPosition(transition);
        positions.add(transition); parentIdentities.add(parent.snapshot().blueId());
    }

    List<List<Object>> receipts(DocumentHandle document) {
        return f.blue.advanced().auditManagedEpochs(document.id()).stream().map(receipt -> Arrays.<Object>asList(
                receipt.receiptIdentity(), receipt.documentId().value(), receipt.epoch(), receipt.kind(),
                receipt.beforeBlueId(), receipt.afterBlueId(), receipt.afterDocument().json(),
                receipt.originalCauseIdentity(), receipt.sourceOrder().map(SourceOrder::components),
                receipt.contractsTransitionReceiptIdentity(), receipt.commitCompanionIdentity(), receipt.processingGas(),
                receipt.sourceEntry().map(entry -> Arrays.asList(entry.exact().json(), entry.request().map(ExactBlueValue::json),
                        entry.previousEntryBlueId(), entry.operation(), entry.channel(), entry.timestampMicros(),
                        entry.globalSequence(), entry.timelineSequence())),
                receipt.emittedEvents().stream().map(event -> Arrays.asList(event.managedEventIdentity(), event.ordinal(),
                        event.eventOccurrenceOrdinal(), event.sourceDocumentId().value(), event.eventOccurrenceIdentity(),
                        event.eventBlueId(), event.exactEvent().json(), event.publicAtSource())).toList())).toList();
    }

    List<Object> sourceState() {
        return List.of(parent.snapshot().exact().json(), source.snapshot().exact().json(),
                receipts(parent), receipts(source), f.control.selectedView(parent.id()).closureIdentity(),
                f.control.selectedView(source.id()).closureIdentity());
    }

    List<Object> consumerProgress() {
        var audit = f.blue.advanced().auditDocument(consumer.id());
        var occurrence = f.blue.advanced().auditManagedOccurrence(consumer.id(), "/child").orElseThrow();
        var selected = f.control.selectedView(consumer.id());
        var binding = selected.occurrences().stream().filter(row -> row.sourceDocumentId().value().equals(consumer.id().value())
                && row.sourcePath().equals("/child")).findFirst().orElseThrow();
        return Arrays.asList(audit.epoch(), ExactBlueValue.wrap(audit.current()).json(), consumer.snapshot().exact().json(), receipts(consumer),
                selected.closureIdentity(), occurrence, binding.occurrenceIdentity(), binding.expectedTargetBlueId(),
                binding.pendingHistoricalEpoch(), binding.pendingRepresentationCursor(),
                f.blue.advanced().auditManagedCatchUpPlans(consumer.id()).stream().map(plan -> Arrays.asList(
                        plan.planIdentity(), plan.nextSourceEpoch(), plan.requiredThroughSourceEpoch())).toList());
    }

    void finishExactlyTwoPositions() {
        var sourceBefore = sourceState();
        var consumed = new ArrayList<String>();
        boolean ready = false;
        int numbered = 0;
        for (int step = 0; step < 32; step++) {
            var result = f.blue.processing().processNext(consumer);
            assertFalse(result.blocked(), result.diagnostic().toString());
            var workItems = java.util.stream.Stream.concat(result.managedEpochApplicationAttempts().stream().map(ManagedEpochApplicationAttempt::work),
                    result.rootedRetainedApplications().stream().map(DrainResult.RootedRetainedApplication::work)).toList();
            for (var work : workItems) if (work.sourceDocumentId().equals(parent.id())) {
                if (work.successorRepresentationStep().isPresent()) {
                    numbered++;
                    assertTrue(work.representationStep().isEmpty());
                    assertTrue(consumed.isEmpty(), "The numbered step consumes no future position");
                    var cursor = work.successorRepresentationStep().orElseThrow().before();
                    assertEquals(work.sourceReceiptIdentity(), cursor.positionIdentity());
                    assertEquals(positions.get(1).positionIdentity(), cursor.targetPositionIdentity());
                    assertFalse(f.blue.advanced().auditManagedOccurrence(consumer.id(), "/child").orElseThrow().active());
                }
                work.representationStep().ifPresent(value -> consumed.add(value.after().positionIdentity()));
            }
            assertEquals(sourceBefore, sourceState());
            if (f.blue.advanced().auditManagedDocumentReadiness(consumer.id()).orElseThrow().ready()) { ready = true; break; }
        }
        assertTrue(ready, "The saved-authored attachment must finish within the original32 selections");
        assertEquals(1, numbered);
        assertEquals(positions.subList(0, 2).stream().map(ManagedRepresentationTransition::positionIdentity).toList(), consumed);
        assertEquals(2L, consumed.stream().distinct().count());
        assertEquals(expectedConsumerChild, consumer.snapshot().valueAt("/child").blueId());
        var completed = consumerProgress();
        CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
        assertEquals(completed, consumerProgress()); assertEquals(sourceBefore, sourceState());
        if (future == null) assertTrue(f.blue.processing().processNext(consumer).quiescent());
        else {
            var live = f.blue.processing().processNext(consumer);
            assertEquals(EntryDisposition.APPLIED, live.entry(future).disposition());
            assertTrue(live.managedEpochApplicationAttempts().isEmpty());
            assertTrue(live.rootedRetainedApplications().isEmpty());
            assertEquals(parent.snapshot().blueId(), consumer.snapshot().valueAt("/child").blueId());
            assertEquals(sourceBefore, sourceState());
        }
    }

    private static String consumerYaml(int steps) throws Exception {
        String action = """
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
        return "updates: 0\n" + RootedSdkFixture.resource("parent.yaml")
                .replace("RCP2 Parent", "RCP2 terminal successor consumer").replace("rcp2/parent", "rcp2/consumer")
                + "  referenceChanges:\n    type: Document Update Channel\n    path: /child\n"
                + "  countChanges:\n    type: Coordination/Sequential Workflow\n    channel: referenceChanges\n    steps:\n"
                + action.repeat(steps);
    }
    @Override public void close() { f.close(); }
}
