package blue.coordination.external;

import blue.language.processor.ProcessorStatus;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.closure.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual owning Core, creator workflow, receipt, and later occurrence-lane integration. */
class FrontierCreatorIntegrationTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test void creatorReadsOnlyFThenSeparateLaneImportsTheSuffixAfterColdReceiptRestore() { run(false, false, false); }
    @Test void frontierEqualToCreationHasAnEmptyHistoricalLane() { run(true, false, false); }
    @Test void failedTerminalAtFInstallsThePriorSuccessfulViewAndStartsAfterTheFailure() { run(false, true, false); }
    @Test void fullHistoryCreatorInstallsActualInit0AndItsRetainedInstallationMintsTheLaterLane() { run(false, false, true); }

    private void run(boolean atCreation, boolean failedTerminal, boolean fullHistory) {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = f.authored("""
                    name: source with distinguishable frontier and head
                    counter: 0
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: account}
                      update:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /counter, val: {$add: [{$document: /counter}, 5]}}
                              - $return: true
                          - type: Coordination/Trigger Event
                            event: agreement-updated
                      failingInput:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: failure-account}
                      fail:
                        type: Coordination/Sequential Workflow
                        channel: failingInput
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /counter, val: {$divide: [1, 0]}}
                              - $return: true
                    """, Map.of());
            var e15 = CanonicalSourceHistoryTest.input("source-first", 15, "account");
            var e25 = CanonicalSourceHistoryTest.input("source-second", 25, "account");
            var creation = CanonicalSourceHistoryTest.input("create-occurrence", 30, "creator");
            var inputs = failedTerminal ? List.of(e15, CanonicalSourceHistoryTest.input("source-failure", 17, "failure-account"), e25, creation)
                    : List.of(e15, e25, creation);
            var history = new CanonicalSourceHistory(f.core);
            var complete = f.prepare(history, source, creation.order(), inputs, 31);
            var selected = atCreation ? complete : f.prepare(history, source,
                    CanonicalSourceHistoryTest.input("selected-frontier", 20, "other").order(), inputs, 31);
            var head = f.restore(complete.boundary().cursor().successfulView().orElseThrow());
            assertEquals(BigInteger.TEN, head.document().get("/counter"));
            var authored = f.authored("""
                    name: actual frontier creator
                    seen: -1
                    previewSeen: -1
                    imported: 0
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: creator}
                      create:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        order: 0
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange:
                                  op: add
                                  path: /contracts/embedded
                                  val: {type: Process Embedded, paths: [/child]}
                              - $return: true
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /previewSeen, val: {$document: /child/counter}}
                              - $return: true
                      readInstalled:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        order: 1
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /seen, val: {$document: /child/counter}}
                              - $return: true
                      fromSource:
                        type: Embedded Node Channel
                        sourcePath: /child
                      observe:
                        type: Coordination/Sequential Workflow
                        channel: fromSource
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /seen, val: {$document: /child/counter}}
                              - $appendChange: {op: replace, path: /imported, val: {$add: [{$document: /imported}, 1]}}
                              - $return: true
                    """, Map.of("child", fullHistory ? source.blueId() : head.blueId()));
            var birth = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    evidence(List.of(authored), List.of(), List.of(), Map.of())));
            var bornReceipt = OperationReceiptCodec.encode(birth, f.blobs::put, LIMITS);
            var creator = OperationReceiptCodec.restoreState(bornReceipt.receiptIdentity(), authored.documentId(), true, 0, f.blobs::get, LIMITS);
            String suppliedReference = fullHistory ? source.blueId() : head.blueId();
            var row = ManagedOccurrenceBinding.derived(f.core.environment().managedBindingPolicyIdentity(), creator.documentId(),
                    ScopeAddress.embedded("/child", 1), source.documentId(), suppliedReference, false, null);
            var choice = new SameOriginAttachmentPolicy.Selection(fullHistory ? SameOriginAttachmentPolicy.Mode.FULL_HISTORY : SameOriginAttachmentPolicy.Mode.FROM_FRONTIER,
                    creator.documentId(), row.occurrenceIdentity(), source.documentId(), suppliedReference, fullHistory ? null : selected.boundary().cut());
            var policy = new SameOriginAttachmentPolicy(List.of(choice));
            var terminal = selected.steps().stream().flatMap(step -> step.publications().stream())
                    .filter(publication -> publication.operationIdentity().equals(selected.boundary().cursor().semanticPredecessor().orElseThrow())).findFirst().orElseThrow();
            var frontier = fullHistory ? null : SourceFrontierSelection.fromBoundary(choice, selected.boundary(), terminal.receiptIdentity(), f.blobs::get, LIMITS);
            var initialization = fullHistory ? OperationReceiptCodec.restoreSourceInitialization(complete.steps().get(0).receiptIdentity(), f.blobs::get, LIMITS) : null;
            if (failedTerminal) assertNotEquals(frontier.selectedView().successfulOperationIdentity(), frontier.selectedView().terminalOperationIdentity());
            var work = new CoordinationCore.WorkIntent(creator.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            var input = evidence(List.of(head, creator), List.of(row), List.of(creation), Map.of(creator.documentId(), birth.operationId(),
                    source.documentId(), complete.boundary().cursor().semanticPredecessor().orElseThrow()));
            if (fullHistory) {
                var snapshot = input.snapshot();
                snapshot = ClosureEvidenceFactory.affectedClosure(0, snapshot.managedDocuments(), snapshot.occurrences(), snapshot.components(), snapshot.publicRootDocumentIds(),
                        List.of(ManagedReadPin.fromExactEvidence(source.documentId(), source.blueId(), source.document(), null)));
                input = new CoordinationCore.EvaluationEvidence(snapshot, input.relevantTimelines(), input.prefixes(), input.handledThrough(), input.fences(), input.precedingOperations());
            }
            // Workflow preview changes become actual patches only when that handler returns.
            // The next ordered handler therefore tests the owning creation-site barrier.
            var need = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(work, input, policy));
            if (fullHistory) assertFalse(need.resourceDemands().isEmpty());
            else assertTrue(need.keys().stream().anyMatch(key -> key.startsWith("source-frontier:")));
            var operations = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(work,
                    fullHistory ? input.withSourceInitializations(List.of(initialization)) : input.withSourceFrontiers(List.of(frontier)), policy));
            assertEquals(1, operations.operations().size()); var created = operations.operations().get(0);
            assertEquals(Set.of(creator.documentId()), created.ownedLineages());
            assertEquals(ProcessorStatus.SUCCESS, created.result().status(), () -> created.result().failure().toString());
            assertEquals(BigInteger.valueOf(fullHistory ? 0 : 10), created.projections().get(0).result().document().get("/previewSeen"),
                    "One workflow buffers changes until its handler returns; its preview reads the original supplied exact reference");
            assertEquals(BigInteger.valueOf(fullHistory ? 0 : atCreation ? 10 : 5), created.projections().get(0).result().document().get("/seen"),
                    () -> "child=" + created.projections().get(0).result().document().get("/child")
                            + ", rows=" + created.ownedOccurrenceBindings().stream().map(value -> value.expectedTargetBlueId() + ":" + value.active() + ":" + value.pendingHistoricalEpoch()).toList()
                            + ", selected=" + (fullHistory ? initialization.program().invocationIdentity() : frontier.selectedView().selectedView().blueId())
                            + ", head=" + head.blueId());
            assertEquals(BigInteger.ZERO, created.projections().get(0).result().document().get("/imported"));
            var receipt = OperationReceiptCodec.encode(created, f.blobs::put, LIMITS);
            var coldProgram = OperationReceiptCodec.restoreSourceProgram(receipt.receiptIdentity(), f.blobs::get, LIMITS);
            ManagedImportLane.Descriptor lane;
            if (fullHistory) {
                var installation = coldProgram.acceptedInitializations().stream().filter(value -> value.selection().occurrenceIdentity().equals(row.occurrenceIdentity())).findFirst().orElseThrow();
                lane = ManagedImportLane.Descriptor.fromAcceptedInitialization(created.result(), installation, initialization, complete.boundary().cursor()).orElseThrow();
            } else {
                var installation = coldProgram.acceptedViews().stream().filter(value -> value.selection().occurrenceIdentity().equals(row.occurrenceIdentity())).findFirst().orElseThrow();
                assertEquals(frontier.selectedView().identity(), installation.frontierView().orElseThrow().identity());
                lane = ManagedImportLane.Descriptor.fromAcceptedFrontier(created, installation, frontier).orElseThrow();
            }
            var cursor = ManagedImportLane.Cursor.start(lane); assertEquals(atCreation, cursor.complete());
            assertEquals(fullHistory ? initialization.program().invocationIdentity() : frontier.selectedView().terminalOperationIdentity(), cursor.lastTerminalSourceOperationIdentity());
            if (atCreation) return;
            var suffix = complete.steps().stream().map(CanonicalSourceHistory.Step::evaluation)
                    .filter(value -> value instanceof CoordinationCore.PreparedOperations).map(value -> (CoordinationCore.PreparedOperations) value)
                    .flatMap(value -> value.operations().stream()).filter(value -> fullHistory || value.input().orElseThrow().timestampMicros() == 25).toList();
            var consumer = OperationReceiptCodec.restoreState(receipt.receiptIdentity(), creator.documentId(), true, 0, f.blobs::get, LIMITS);
            var beforeSource = f.restore(fullHistory ? complete.boundary().cursor().initialView().orElseThrow() : selected.boundary().cursor().successfulView().orElseThrow());
            List<ManagedOccurrenceBinding> rows = created.ownedOccurrenceBindings(); String precedingConsumer = created.operationId();
            String consumerReceipt = receipt.receiptIdentity();
            int importedCount = 0; int acquiredExactValues = 0;
            for (var sourceOperation : suffix) {
                var due = new ManagedImportLane.Due(cursor, ManagedImportLane.Header.fromOperation(sourceOperation, source.documentId(), lane.canonicalSourceBasis()),
                        Optional.of(ManagedImportLane.PrefixAuthority.fromBoundary(lane, complete.boundary())));
                var laneEvidence = evidence(List.of(beforeSource, consumer), rows, List.of(), Map.of(creator.documentId(), precedingConsumer));
                laneEvidence = new CoordinationCore.EvaluationEvidence(laneEvidence.snapshot(), Set.of(), List.of(), Optional.of(creation.order()), List.of(),
                        laneEvidence.precedingOperations(), List.of(sourceOperation.sourceProgram().orElseThrow()));
                var sourceReceipt = OperationReceiptCodec.encode(sourceOperation, f.blobs::put, LIMITS);
                var laneResult = f.core.evaluate(new ManagedImportSelection(List.of(due)), laneEvidence);
                Set<String> acquired = new HashSet<>();
                for (int attempt = 0; attempt < 4 && laneResult instanceof CoordinationCore.NeedEvidence; attempt++) {
                    var missing = (CoordinationCore.NeedEvidence) laneResult;
                    boolean supplied = false;
                    for (var demand : missing.resourceDemands()) if (demand instanceof ExactNodeDemand exact) {
                        if (!acquired.contains(exact.blueId()) && (supplyExactFromReceipt(f, consumerReceipt, exact.blueId())
                                || supplyExactFromReceipt(f, sourceReceipt.receiptIdentity(), exact.blueId()))) {
                            acquired.add(exact.blueId()); supplied = true;
                        }
                    }
                    if (!supplied) break;
                    laneResult = f.core.evaluate(new ManagedImportSelection(List.of(due)), laneEvidence);
                }
                acquiredExactValues += acquired.size();
                var finalLaneResult = laneResult;
                var imported = assertInstanceOf(CoordinationCore.PreparedOperation.class, finalLaneResult, () -> "Exact lane result: " + finalLaneResult
                        + (finalLaneResult instanceof CoordinationCore.NeedEvidence missing ? ", demands=" + missing.resourceDemands().stream()
                        .map(demand -> demand.kind() + ":" + demand.sourceDocumentId() + ":" + demand.sourcePath() + ":" + demand.suppliedValueBlueId()).toList() : "")
                        + ", authoredSource=" + source.blueId());
                assertEquals(ProcessorStatus.SUCCESS, imported.result().status());
                var importedReceipt = OperationReceiptCodec.encode(imported, f.blobs::put, LIMITS);
                consumerReceipt = importedReceipt.receiptIdentity();
                consumer = OperationReceiptCodec.restoreState(importedReceipt.receiptIdentity(), creator.documentId(), true, 0, f.blobs::get, LIMITS);
                rows = imported.ownedOccurrenceBindings(); precedingConsumer = imported.operationId(); cursor = imported.laneDeltas().get(0).nextCursor(); importedCount++;
                beforeSource = OperationReceiptCodec.restoreState(sourceReceipt.receiptIdentity(), source.documentId(), true, 0, f.blobs::get, LIMITS);
            }
            assertEquals(BigInteger.TEN, consumer.document().get("/seen"));
            assertEquals(BigInteger.valueOf(fullHistory ? 2 : 1), consumer.document().get("/imported"));
            assertEquals(created.projections().get(0).afterEpoch() + importedCount, consumer.epoch());
            assertTrue(acquiredExactValues > 0, "Cold checkpoint values must be acquired from authenticated receipts");
            assertTrue(cursor.complete());
        }
    }

    /** Resolve only the demanded exact value from bounded, authenticated operation evidence. */
    private static boolean supplyExactFromReceipt(CanonicalSourceHistoryTest.Fixture f, String receipt, String exactId) {
        var metadata = OperationReceiptCodec.decode(receipt, f.blobs::get, LIMITS);
        String bodyIdentity = metadata.events().stream().filter(event -> event.blueId().equals(exactId))
                .map(OperationReceiptCodec.Event::bodyIdentity).findFirst().orElse(null);
        if (bodyIdentity == null && metadata.input().isPresent()) {
            var input = metadata.input().orElseThrow();
            if (input.entryBlueId().equals(exactId)) bodyIdentity = input.entryIdentity();
            else if (input.eventBlueId().equals(exactId)) bodyIdentity = input.eventIdentity();
        }
        if (bodyIdentity != null) {
            var body = FrozenNodeEvidenceCodec.decode(bodyIdentity, f.blobs::get, LIMITS).toNode();
            assertEquals(exactId, blue.language.identity.DirectBlueIdCalculator.calculateBlueId(body));
            f.nodes.put(exactId, body); return true;
        }
        for (var checkpoint : OperationReceiptCodec.restoreEffects(receipt, f.blobs::get, LIMITS).checkpoints()) {
            for (var domain : Arrays.asList(checkpoint.beforeDomainValue(), checkpoint.afterDomainValue())) {
                if (domain == null || !domain.blueId().equals(exactId)) continue;
                // Reconstruct the exact value from the receipt's complete domain
                // fields using the owning inverse, not from a current channel.
                var body = CheckpointDomain.value(domain.effectiveTypeBlueId(), domain.sourceContributionNodeBlueIds(),
                        new ExternalChannelDependencySnapshot(domain.deterministicDependencyNodeBlueIds(), List.of(), false),
                        domain.runtimeDiscriminator());
                assertEquals(exactId, blue.language.identity.DirectBlueIdCalculator.calculateBlueId(body));
                f.nodes.put(exactId, body); return true;
            }
        }
        return false;
    }

    private static CoordinationCore.EvaluationEvidence evidence(List<ManagedDocumentSnapshot> states, List<ManagedOccurrenceBinding> bindings,
            List<CoordinationCore.TimelineInput> inputs, Map<DocumentId, String> predecessors) {
        Map<DocumentId, ManagedDocumentSnapshot> byId = new HashMap<>(); states.forEach(state -> byId.put(state.documentId(), state));
        var components = new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(byId.keySet(), bindings)).stream()
                .map(members -> {
                    assertEquals(1, members.size(), "This creator fixture has no cyclic component");
                    return ClosureEvidenceFactory.acyclicComponent(byId.get(members.get(0)));
                }).toList();
        var snapshot = ClosureEvidenceFactory.affectedClosure(0, states, bindings, components,
                states.stream().map(ManagedDocumentSnapshot::documentId).toList());
        return new CoordinationCore.EvaluationEvidence(snapshot, inputs.isEmpty() ? Set.of() : Set.of("timeline"),
                inputs.isEmpty() ? List.of() : List.of(new CoordinationCore.TimelinePrefix("timeline", 31, inputs)), Optional.empty(), List.of(), predecessors);
    }
}
