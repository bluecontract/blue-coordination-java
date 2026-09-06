package blue.coordination.external;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.model.Node;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalInitializationIdentityTest {
    @Test void eitherRequestedMemberOfTheSameAuthoredSccHasOneCanonicalInitialization() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var finalized = new CircularSetIdentityCalculator().finalizeCyclicSet(List.of(member("B", "this#1"), member("C", "this#0")));
            List<ManagedDocumentSnapshot> states = new ArrayList<>(); List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
            Map<DocumentId, Node> bodies = new HashMap<>(); Map<DocumentId, Long> generations = new HashMap<>();
            for (var member : finalized.membersInCanonicalOrder()) {
                Node body = member.canonicalMemberBody();
                String target = finalized.masterBlueId() + body.getAsNode("/peer").getBlueId().substring(4);
                body.getAsNode("/peer").blueId(target);
                var id = new DocumentId(member.finalBlueId()); bodies.put(id, body); generations.put(id, 0L);
                bindings.add(ManagedOccurrenceBinding.derived(f.core.environment().managedBindingPolicyIdentity(), id,
                        ScopeAddress.embedded("/peer", 1), new DocumentId(target), target, true, null));
            }
            var exact = new ComponentFinalizationKernel().finalizeComponents(new ComponentFinalizationInput(
                    ManagedDocumentGraph.fromBindings(bodies.keySet(), bindings), generations, bodies, bindings));
            for (DocumentId id : new TreeSet<>(bodies.keySet())) states.add(new ManagedDocumentSnapshot(id, exact.document(id).blueId(),
                    exact.document(id).document(), false, false, true, 0, 0));
            var snapshot = ClosureEvidenceFactory.affectedClosure(0, states, exact.finalizedGraph().bindings(),
                    exact.components().stream().map(FinalizedComponentEvidence::component).toList(), states.stream().map(ManagedDocumentSnapshot::documentId).toList());
            var evidence = new CoordinationCore.EvaluationEvidence(snapshot, Set.of(), List.of(), Optional.empty(), List.of(), Map.of());
            var first = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(new CoordinationCore.WorkIntent(states.get(0).documentId(),
                    CoordinationCore.OperationKind.INITIALIZATION), evidence));
            var second = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(new CoordinationCore.WorkIntent(states.get(1).documentId(),
                    CoordinationCore.OperationKind.INITIALIZATION), evidence));
            assertEquals(ProcessorStatus.SUCCESS, first.result().status()); assertEquals(ProcessorStatus.SUCCESS, second.result().status());
            assertEquals(first.operationId(), second.operationId()); assertEquals(first.invocation().invocationIdentity(), second.invocation().invocationIdentity());
            assertEquals(first.result().gasTraceIdentity(), second.result().gasTraceIdentity()); assertEquals(first.result().totalGas(), second.result().totalGas());
            assertEquals(first.projections().stream().map(p -> List.of(p.lineage(), p.afterBlueId(), p.afterEpoch())).toList(),
                    second.projections().stream().map(p -> List.of(p.lineage(), p.afterBlueId(), p.afterEpoch())).toList());
            assertEquals(first.sourceProgram().orElseThrow().steps().stream().map(SourceObservationProgram.Step::workIdentity).toList(),
                    second.sourceProgram().orElseThrow().steps().stream().map(SourceObservationProgram.Step::workIdentity).toList());
            var limits = FrozenNodeEvidenceCodec.Limits.defaults();
            assertEquals(OperationReceiptCodec.encode(first, f.blobs::put, limits), OperationReceiptCodec.encode(second, f.blobs::put, limits));
            assertEquals(2, SourceInitialization.fromProgram(first.sourceProgram().orElseThrow()).ownedDocumentIds().size());
        }
    }

    private static Node member(String name, String peer) {
        return new Node().name(name).properties("peer", new Node().blueId(peer)).contracts(new Node()
                .properties("embedded", new Node().type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                        .properties("paths", new Node().items(List.of(new Node().value("/peer"))))));
    }
}
