package blue.coordination.external;

import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.processor.closure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptReadCutTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test void actualReceiptRestoresCompleteTimelineAuthorityWithoutItsApplicationBody() {
        Map<String, byte[]> blobs = new HashMap<>(); String receipt; DocumentId source; String body;
        try (var f = new CanonicalSourceHistoryTest.Fixture(); var contracts = new BlueClosureContracts(f.processor)) {
            var init = initialize(f, f.source()); source = init.projections().get(0).lineage(); var output = output(init);
            var encoded = OperationReceiptCodec.encodeWithReadCut(init, output, contracts.captureRootMetadata(output), blobs::put, LIMITS);
            receipt = encoded.receiptIdentity(); body = OperationReceiptCodec.decode(receipt, blobs::get, LIMITS).states().get(0).bodyIdentity();
        }
        assertNotNull(blobs.remove(body)); Set<String> reads = new HashSet<>();
        try (var cold = new CanonicalSourceHistoryTest.Fixture()) {
            var authorities = OperationReceiptCodec.restoreReadCutAuthorities(receipt, cold.processor, id -> { reads.add(id); return blobs.get(id); }, LIMITS);
            var cut = cut(authorities); assertFalse(cut.managedDocument(source).hasResidentBody());
            assertFalse(reads.contains(body));
            var progress = assertInstanceOf(CoordinationCore.MetadataProgress.class, cold.core.evaluate(
                    new CoordinationCore.WorkIntent(source, CoordinationCore.OperationKind.EXTERNAL_INPUT), evidence(cut, "other-account")));
            assertEquals(15, progress.input().timestampMicros());
        }
    }

    @Test void actualGroupReceiptAlsoBindsItsOwnNewReadCutRatherThanThePredecessor() {
        try (var f = new CanonicalSourceHistoryTest.Fixture(); var contracts = new BlueClosureContracts(f.processor)) {
            var init = initialize(f, f.source()); var before = output(init);
            var groups = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(init.projections().get(0).lineage(), CoordinationCore.OperationKind.EXTERNAL_INPUT), evidence(before, "account")));
            var group = groups.operations().get(0);
            var after = output(group.result().resultingDocuments(), group.result().resultingComponents(), group.result().occurrenceBindings(), 0);
            var encoded = OperationReceiptCodec.encodeWithReadCut(group, after, contracts.captureRootMetadata(after), f.blobs::put, LIMITS);
            var cold = OperationReceiptCodec.restoreReadCutAuthorities(encoded.receiptIdentity(), f.processor, f.blobs::get, LIMITS).get(0);
            assertEquals(group.projections().get(0).afterBlueId(), cold.memberHeaders().get(0).blueId());
            assertEquals(group.projections().get(0).afterEpoch(), cold.memberHeaders().get(0).epoch());
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> OperationReceiptCodec.encodeWithReadCut(group, before, contracts.captureRootMetadata(before), f.blobs::put, LIMITS));
        }
    }

    @Test void missingOrForeignAuthorityDoesNotBecomeACompleteEmptyChannelSet() throws Exception {
        try (var f = new CanonicalSourceHistoryTest.Fixture(); var contracts = new BlueClosureContracts(f.processor)) {
            var init = initialize(f, f.source()); var output = output(init);
            var plain = OperationReceiptCodec.encode(init, f.blobs::put, LIMITS);
            assertThrows(ExecutionEvidenceUnavailableException.class,
                    () -> OperationReceiptCodec.restoreReadCutAuthorities(plain.receiptIdentity(), f.processor, f.blobs::get, LIMITS));
            var a = OperationReceiptCodec.encodeWithReadCut(init, output, contracts.captureRootMetadata(output), f.blobs::put, LIMITS);
            var other = initialize(f, f.authored("name: Other independent owner", Map.of())); var otherOutput = output(other);
            var b = OperationReceiptCodec.encodeWithReadCut(other, otherOutput, contracts.captureRootMetadata(otherOutput), f.blobs::put, LIMITS);
            ObjectMapper json = new ObjectMapper(); ObjectNode first = (ObjectNode) json.readTree(f.blobs.get(a.receiptIdentity()));
            var foreign = json.readTree(f.blobs.get(b.receiptIdentity())).get("readCutAuthorities").get(0);
            ((ObjectNode) first.get("readCutAuthorities").get(0)).put("authority", foreign.get("authority").textValue());
            String changed = new FrozenNodeEvidenceCodec.Encoder(f.blobs::put, LIMITS).blob(json.writeValueAsBytes(first));
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> OperationReceiptCodec.restoreReadCutAuthorities(changed, f.processor, f.blobs::get, LIMITS));
            ((com.fasterxml.jackson.databind.node.ArrayNode) first.get("readCutAuthorities")).set(0, foreign);
            String wrongOwner = new FrozenNodeEvidenceCodec.Encoder(f.blobs::put, LIMITS).blob(json.writeValueAsBytes(first));
            Set<String> read = new HashSet<>();
            assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreReadCutAuthorities(wrongOwner,
                    f.processor, key -> { read.add(key); return f.blobs.get(key); }, LIMITS));
            assertFalse(read.contains(foreign.get("authority").textValue()), "Foreign-owner association is rejected before authority reconstruction");
        }
    }

    @Test void inheritedRootHeaderRestoresAndClassifiesWithoutReopeningItsApplicationType() {
        Map<String, byte[]> blobs = new HashMap<>(); Set<String> forbidden = new HashSet<>(); String receipt;
        String channelIdentity, headerIdentity, domain, subject; DocumentId source;
        Node event = CanonicalSourceHistoryTest.input("source-change", 15, "account").entry().copyNode();
        try (var f = new CanonicalSourceHistoryTest.Fixture(); var contracts = new BlueClosureContracts(f.processor);
             var runtime = new ManagedDocumentStepRuntime(f.processor)) {
            var base = f.source(); f.nodes.put(base.blueId(), base.document()); forbidden.add(base.blueId());
            var derived = f.authored("name: Derived application\ntype: {blueId: " + base.blueId() + "}\nlargePrivateData: not-a-routing-header", Map.of());
            var init = initialize(f, derived); var output = output(init); source = derived.documentId();
            forbidden.add(derived.blueId()); forbidden.add(output.managedDocument(source).blueId());
            var warm = runtime.classifyExternalDelivery(output.managedDocument(source).document(), "ingress", event, GasChargeContext.empty()).candidate();
            channelIdentity = warm.channelOccurrence().effectiveRuntimeContributionBlueId(); headerIdentity = warm.channelOccurrence().subscriptionHeaderBlueId();
            domain = warm.domain().blueId(); subject = warm.subjectBlueId();
            receipt = OperationReceiptCodec.encodeWithReadCut(init, output, contracts.captureRootMetadata(output), blobs::put, LIMITS).receiptIdentity();
        }
        try (var cold = new CanonicalSourceHistoryTest.Fixture(); var runtime = new ManagedDocumentStepRuntime(cold.processor)) {
            cold.forbiddenExactReads.addAll(forbidden);
            var authority = OperationReceiptCodec.restoreReadCutAuthorities(receipt, cold.processor, blobs::get, LIMITS).get(0);
            var metadata = authority.memberHeader(source).rootMetadata().orElseThrow();
            assertNull(metadata.routingDocument().getType());
            var classification = runtime.classifyExternalDelivery(metadata, "ingress", event, GasChargeContext.empty());
            assertEquals(ManagedExternalDeliveryClassification.State.ACCEPTED_NEW, classification.state());
            assertEquals(channelIdentity, classification.candidate().channelOccurrence().effectiveRuntimeContributionBlueId());
            assertEquals(headerIdentity, classification.candidate().channelOccurrence().subscriptionHeaderBlueId());
            assertEquals(domain, classification.candidate().domain().blueId()); assertEquals(subject, classification.candidate().subjectBlueId());
            assertTrue(Collections.disjoint(cold.exactReads, forbidden));
        }
    }

    @Test void sourceAfterCannotDropAChildTimelineWhenSelectingItsOwnEarlierInput() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var cBirth = initialize(f, f.authored("""
                    name: Quiet child
                    contracts:
                      quiet:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: quiet}
                        actor: {type: MyOS/Principal Actor, accountId: child}
                    """, Map.of()));
            var child = output(cBirth).managedDocuments().get(0);
            f.nodes.put(child.blueId(), child.document());
            var bAuthored = f.authored("""
                    name: Source retires its child
                    contracts:
                      embedded: {type: Process Embedded, paths: [/child]}
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: account}
                      removeChild:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        steps:
                          - type: Coordination/Update Document
                            changeset: [{op: remove, path: /contracts/embedded}]
                    """, Map.of("child", child.blueId()));
            var bChild = f.binding(bAuthored, child);
            var bBirth = initializeWithCut(f, bAuthored, List.of(bAuthored, child), List.of(bChild));
            var sourceBefore = output(bBirth).managedDocument(bAuthored.documentId());
            assertTrue(bBirth.ownedOccurrenceBindings().get(0).active());
            f.nodes.put(sourceBefore.blueId(), sourceBefore.document());

            var rootAuthored = f.authored("""
                    name: Root observes the source's earlier cut
                    contracts:
                      embedded: {type: Process Embedded, paths: [/child]}
                    """, Map.of("child", sourceBefore.blueId()));
            var rootSource = f.binding(rootAuthored, sourceBefore);
            List<ManagedOccurrenceBinding> beforeRows = List.of(rootSource, bBirth.ownedOccurrenceBindings().get(0));
            var rootBirth = initializeWithCut(f, rootAuthored, List.of(rootAuthored, sourceBefore, child), beforeRows);
            var root = output(rootBirth).managedDocument(rootAuthored.documentId());
            var event = CanonicalSourceHistoryTest.input("retire-child", 15, "account");
            var inputPrefix = new CoordinationCore.TimelinePrefix("timeline", 20, List.of(event));
            var sourceCut = exactCut(List.of(sourceBefore, child), bBirth.ownedOccurrenceBindings(), List.of());
            var producing = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(sourceBefore.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    new CoordinationCore.EvaluationEvidence(sourceCut, Set.of("timeline", "quiet"),
                            List.of(inputPrefix, new CoordinationCore.TimelinePrefix("quiet", 20, List.of())),
                            Optional.empty(), List.of(), Map.of(sourceBefore.documentId(), bBirth.operationId()))));
            assertEquals(1, producing.operations().size());
            var produced = producing.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, produced.result().status());
            assertTrue(produced.ownedOccurrenceBindings().stream().noneMatch(ManagedOccurrenceBinding::active));
            var encoded = OperationReceiptCodec.encode(produced, f.blobs::put, LIMITS);
            var sourceAfter = OperationReceiptCodec.restoreState(encoded.receiptIdentity(), sourceBefore.documentId(), true, 0, f.blobs::get, LIMITS);
            var coldProgram = OperationReceiptCodec.restoreSourceProgram(encoded.receiptIdentity(), f.blobs::get, LIMITS);
            List<ManagedOccurrenceBinding> afterRows = new ArrayList<>(produced.ownedOccurrenceBindings()); afterRows.add(rootSource);
            var afterCut = exactCut(List.of(root, sourceAfter, child), afterRows,
                    List.of(ManagedReadPin.fromExactEvidence(sourceBefore.documentId(), sourceBefore.blueId(), sourceBefore.document(), null)));
            var work = new CoordinationCore.WorkIntent(root.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            var predecessors = Map.of(root.documentId(), rootBirth.operationId(), sourceBefore.documentId(), bBirth.operationId());
            var sourceBasis = Map.of(sourceBefore.documentId(), SourceExecutionBasis.identity(
                    sourceBefore.documentId(), f.core.environment(), f.core.executionPolicy()));
            var wrongCut = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(work,
                    new CoordinationCore.EvaluationEvidence(afterCut, Set.of("timeline"), List.of(inputPrefix),
                            Optional.empty(), List.of(), predecessors, List.of(coldProgram)).withExpectedSourceBases(sourceBasis)));
            assertTrue(wrongCut.keys().contains("root-channel-logical-view:" + sourceBefore.documentId().value()));

            // Reconstructing the actual predecessor topology restores C's required guarantee.
            var priorCut = exactCut(List.of(root, sourceBefore, child), beforeRows, List.of());
            var missingChildGuarantee = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(work,
                    new CoordinationCore.EvaluationEvidence(priorCut, Set.of("timeline", "quiet"), List.of(inputPrefix),
                            Optional.empty(), List.of(), predecessors, List.of(coldProgram)).withExpectedSourceBases(sourceBasis)));
            assertEquals(List.of("timeline-prefix:quiet"), missingChildGuarantee.keys());
        }
    }

    private static CoordinationCore.PreparedOperation initializeWithCut(CanonicalSourceHistoryTest.Fixture f,
            ManagedDocumentSnapshot owner, List<ManagedDocumentSnapshot> states, List<ManagedOccurrenceBinding> bindings) {
        return assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                new CoordinationCore.WorkIntent(owner.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                new CoordinationCore.EvaluationEvidence(exactCut(states, bindings, List.of()), Set.of(), List.of(),
                        Optional.empty(), List.of(), Map.of())));
    }

    private static AffectedClosureSnapshot exactCut(List<ManagedDocumentSnapshot> states,
            List<ManagedOccurrenceBinding> bindings, List<ManagedReadPin> pins) {
        Map<DocumentId, ManagedDocumentSnapshot> byId = new HashMap<>();
        states.forEach(state -> byId.put(state.documentId(), state));
        var components = new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(byId.keySet(), bindings)).stream()
                .map(members -> {
                    assertEquals(1, members.size(), "This read-cut fixture contains no returning edge");
                    return ClosureEvidenceFactory.acyclicComponent(byId.get(members.get(0)));
                }).toList();
        return ClosureEvidenceFactory.affectedClosure(0, states, bindings,
                components,
                states.stream().map(ManagedDocumentSnapshot::documentId).toList(), pins);
    }

    private static CoordinationCore.PreparedOperation initialize(CanonicalSourceHistoryTest.Fixture f, ManagedDocumentSnapshot authored) {
        var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(authored), List.of(), List.of(ClosureEvidenceFactory.acyclicComponent(authored)), List.of(authored.documentId()));
        return assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                new CoordinationCore.EvaluationEvidence(snapshot, Set.of(), List.of(), Optional.empty(), List.of(), Map.of())));
    }
    private static AffectedClosureSnapshot output(CoordinationCore.PreparedOperation operation) {
        return output(operation.result().resultingDocuments(), operation.result().resultingComponents(), operation.result().occurrenceBindings(), operation.result().graphGeneration());
    }
    private static AffectedClosureSnapshot output(List<ResultingDocument> documents, List<ComponentSnapshot> components, List<ManagedOccurrenceBinding> bindings, long generation) {
        List<ManagedDocumentSnapshot> states = documents.stream().map(d -> new ManagedDocumentSnapshot(d.documentId(), d.afterBlueId(), d.document(),
                d.initialized(), d.terminated(), d.publicRoot(), d.epoch(), d.componentGeneration())).toList();
        return ClosureEvidenceFactory.affectedClosure(generation, states, bindings, components,
                states.stream().filter(ManagedDocumentSnapshot::publicRoot).map(ManagedDocumentSnapshot::documentId).toList());
    }
    private static AffectedClosureSnapshot cut(List<ReusableComponentAuthority> authorities) {
        var states = authorities.stream().flatMap(a -> a.memberHeaders().stream()).toList();
        return ClosureEvidenceFactory.affectedClosure(0, states, authorities.stream().flatMap(a -> a.outgoingBindings().stream()).toList(),
                authorities.stream().map(ReusableComponentAuthority::component).toList(), states.stream().filter(ManagedDocumentSnapshot::publicRoot).map(ManagedDocumentSnapshot::documentId).toList());
    }
    private static CoordinationCore.EvaluationEvidence evidence(AffectedClosureSnapshot cut, String actor) {
        return new CoordinationCore.EvaluationEvidence(cut, Set.of("timeline"), List.of(new CoordinationCore.TimelinePrefix("timeline", 20,
                List.of(CanonicalSourceHistoryTest.input("entry", 15, actor)))), Optional.empty(), List.of(), Map.of());
    }
}
