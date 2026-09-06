package blue.coordination.external;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.model.Node;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Actual co-owned Core/History projection plus narrow original-admission codec controls. */
class SourceInputAdmissionCoOwnedTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test void actualCoOwnedHistoryKeepsOneOperationWhenRequestedThroughEitherMember() {
        try (var runtime = new CanonicalSourceHistoryTest.Fixture()) {
            var authored = cyclicAuthored(runtime);
            Set<DocumentId> members = Set.copyOf(authored.managedDocuments().stream()
                    .map(ManagedDocumentSnapshot::documentId).toList());
            assertEquals(2, members.size());
            assertEquals(1, authored.components().size());
            assertEquals(ComponentKind.CYCLIC, authored.components().get(0).kind());
            var entry = CanonicalSourceHistoryTest.input("one co-owned operation", 15, "account");
            var history = new CanonicalSourceHistory(runtime.core);
            var emptyCut = new CoordinationCore.EvaluationEvidence(authored, Set.of(), List.of(),
                    Optional.empty(), List.of(), Map.of());
            Map<DocumentId, CanonicalSourceHistory.Step> births = new HashMap<>();
            for (DocumentId member : members) {
                var birth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(
                        new CanonicalSourceHistory.Request(member, entry.order()), history.start(member),
                        emptyCut, runtime.blobs::put, LIMITS));
                var operation = assertInstanceOf(CoordinationCore.PreparedOperation.class, birth.evaluation());
                assertEquals(ProcessorStatus.SUCCESS, operation.result().status());
                assertEquals(members, operation.ownedLineages());
                births.put(member, birth);
            }
            var firstBirth = births.values().iterator().next();
            var initialized = assertInstanceOf(CoordinationCore.PreparedOperation.class, firstBirth.evaluation());
            for (var birth : births.values()) {
                assertEquals(initialized.operationId(),
                        assertInstanceOf(CoordinationCore.PreparedOperation.class, birth.evaluation()).operationId());
            }
            List<ManagedDocumentSnapshot> before = members.stream().map(member -> {
                var projection = initialized.projections().stream().filter(value -> value.lineage().equals(member))
                        .findFirst().orElseThrow();
                return OperationReceiptCodec.restoreState(firstBirth.receiptIdentity(), member, true,
                        projection.result().componentGeneration(), runtime.blobs::get, LIMITS);
            }).toList();
            var initializedCut = ClosureEvidenceFactory.affectedClosure(1, before, initialized.result().occurrenceBindings(),
                    initialized.result().resultingComponents(), List.copyOf(members), initialized.result().readPins());
            Map<DocumentId, String> bases = new HashMap<>(), predecessors = new HashMap<>();
            Map<DocumentId, List<CoordinationCore.ReadFence>> fences = new HashMap<>();
            for (DocumentId member : members) {
                bases.put(member, SourceExecutionBasis.identity(member, runtime.core.environment(), runtime.core.executionPolicy()));
                predecessors.put(member, births.get(member).after().semanticPredecessor().orElseThrow());
                fences.put(member, List.of(new CoordinationCore.ReadFence(member.value(), "before-original-input")));
            }
            var external = new CoordinationCore.EvaluationEvidence(initializedCut, Set.of("timeline"),
                    List.of(new CoordinationCore.TimelinePrefix("timeline", 20, List.of(entry))), Optional.empty(),
                    fences.values().stream().flatMap(List::stream).toList(), predecessors).withOperationFences(fences);
            var original = new CoOwnedInput(entry, bases, predecessors);
            String originalRoot = encode(original, bases, predecessors, runtime.blobs);
            var restored = SourceInputAdmission.restore(originalRoot, runtime.blobs::get, LIMITS);
            CoordinationCore.PreparedGroupOperation firstGroup = null;
            String firstReceipt = null;
            for (DocumentId member : members) {
                String incompleteRoot = encode(original, Map.of(member, bases.get(member)),
                        Map.of(member, predecessors.get(member)), runtime.blobs);
                var incomplete = SourceInputAdmission.restore(incompleteRoot, runtime.blobs::get, LIMITS);
                assertThrows(InvalidExecutionEvidenceException.class, () -> history.prepareNext(
                        new CanonicalSourceHistory.Request(member, entry.order(), Map.of(entry.entry().blueId(), incompleteRoot)),
                        births.get(member).after(), external.withSourceInputAdmissions(List.of(incomplete)), runtime.blobs::put, LIMITS));

                DocumentId otherMember = members.stream().filter(other -> !other.equals(member)).findFirst().orElseThrow();
                var otherPolicy = ClosureEvidenceFactory.executionPolicy(runtime.core.executionPolicy().sharedLimit() + 1,
                        runtime.core.executionPolicy().localLimits(), "different co-owned producer policy");
                Map<DocumentId, String> wrongOtherBasis = new HashMap<>(bases);
                wrongOtherBasis.put(otherMember, SourceExecutionBasis.identity(otherMember, runtime.core.environment(), otherPolicy));
                assertEquals(bases.get(member), wrongOtherBasis.get(member), "The requested source's basis remains correct");
                assertNotEquals(bases.get(otherMember), wrongOtherBasis.get(otherMember));
                String wrongOtherRoot = encode(original, wrongOtherBasis, predecessors, runtime.blobs);
                var wrongOtherAdmission = SourceInputAdmission.restore(wrongOtherRoot, runtime.blobs::get, LIMITS);
                Set<String> storedBeforeRejection = Set.copyOf(runtime.blobs.keySet());
                assertThrows(InvalidExecutionEvidenceException.class, () -> history.prepareNext(
                        new CanonicalSourceHistory.Request(member, entry.order(), Map.of(entry.entry().blueId(), wrongOtherRoot)),
                        births.get(member).after(), external.withSourceInputAdmissions(List.of(wrongOtherAdmission)), runtime.blobs::put, LIMITS),
                        "One genuine co-owned operation cannot admit its other member under a different producer policy");
                assertEquals(storedBeforeRejection, runtime.blobs.keySet(), "Invalid co-owned basis cannot stage a new prefix or result");

                var step = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(
                        new CanonicalSourceHistory.Request(member, entry.order(), Map.of(entry.entry().blueId(), originalRoot)),
                        births.get(member).after(), external.withSourceInputAdmissions(List.of(restored)), runtime.blobs::put, LIMITS));
                var operations = assertInstanceOf(CoordinationCore.PreparedOperations.class, step.evaluation());
                assertEquals(1, operations.operations().size());
                var group = operations.operations().get(0);
                assertEquals(members, group.ownedLineages());
                assertEquals(ProcessorStatus.SUCCESS, group.result().status());
                assertEquals(2, group.projections().size());
                for (var projection : group.projections()) {
                    assertEquals(predecessors.get(projection.lineage()), projection.precedingOperation());
                    assertEquals(BigInteger.ONE, projection.result().document().get("/counter"));
                    assertEquals(1L, projection.afterEpoch());
                }
                if (firstGroup == null) {
                    firstGroup = group;
                    firstReceipt = step.receiptIdentity();
                } else {
                    assertEquals(firstGroup.operationId(), group.operationId());
                    assertEquals(firstGroup.result().totalGas(), group.result().totalGas());
                    assertEquals(firstReceipt, step.receiptIdentity(), "Changing the requested projection does not change the shared receipt");
                }
                assertEquals(originalRoot, step.after().originalAdmissionIdentity().orElseThrow());
                var cold = history.resume(member, step.after().recordIdentity().orElseThrow(), runtime.blobs::get, LIMITS);
                assertEquals(originalRoot, cold.originalAdmissionIdentity().orElseThrow());
                assertEquals(group.operationId(), cold.semanticPredecessor().orElseThrow());
                assertEquals(step.after().successfulView(), cold.successfulView());
            }
        }
    }

    @Test void oneColdOriginalAdmissionProjectsThroughEitherCoOwnedMember() {
        try (var runtime = new CanonicalSourceHistoryTest.Fixture()) {
            var input = input(runtime);
            Map<String, byte[]> originalStore = new HashMap<>();
            // The fixture fixes this root at original admission, before either member reconstructs it.
            String admittedRoot = encode(input, input.bases(), input.predecessors(), originalStore);
            Map<String, byte[]> coldStore = new HashMap<>();
            originalStore.forEach((key, value) -> coldStore.put(key, value.clone()));
            var restored = SourceInputAdmission.restore(admittedRoot, coldStore::get, LIMITS);

            assertEquals(admittedRoot, restored.identity());
            assertEquals(input.bases(), restored.sourceBases());
            assertEquals(input.predecessors(), restored.originalPredecessors());
            assertEquals(input.entry().entry().blueId(), restored.entryBlueId());
            assertTrue(restored.selections().entries().isEmpty(), "An empty original choice is retained explicitly");
            for (DocumentId source : input.members()) {
                assertDoesNotThrow(() -> restored.verify(source, input.bases().get(source), input.entry(),
                        input.predecessors(), input.members(), input.members()));
                assertDoesNotThrow(() -> restored.verifyPrefix(source, input.bases().get(source),
                        input.entry().order(), input.predecessors().get(source)));
                assertEquals(admittedRoot, restored.identity(), "Projection does not create a per-observer admission root");
            }
        }
    }

    @Test void bothMemberBasesAndPredecessorsRemainBoundAcrossProjectionAndColdRestore() {
        try (var runtime = new CanonicalSourceHistoryTest.Fixture()) {
            var input = input(runtime);
            Map<String, byte[]> originalStore = new HashMap<>();
            String admittedRoot = encode(input, input.bases(), input.predecessors(), originalStore);
            var restored = SourceInputAdmission.restore(admittedRoot, originalStore::get, LIMITS);

            for (DocumentId changed : input.members()) {
                Map<DocumentId, String> differentBases = new HashMap<>(input.bases());
                differentBases.put(changed, "d".repeat(64));
                assertThrows(InvalidExecutionEvidenceException.class, () -> restored.verify(changed,
                        differentBases.get(changed), input.entry(), input.predecessors(), input.members(), input.members()));
                assertThrows(InvalidExecutionEvidenceException.class, () -> restored.verifyPrefix(changed,
                        differentBases.get(changed), input.entry().order(), input.predecessors().get(changed)));

                Map<DocumentId, String> differentPredecessors = new HashMap<>(input.predecessors());
                differentPredecessors.put(changed, "sha256:" + "e".repeat(64));
                for (DocumentId projection : input.members()) {
                    assertThrows(InvalidExecutionEvidenceException.class, () -> restored.verify(projection,
                            input.bases().get(projection), input.entry(), differentPredecessors,
                            input.members(), input.members()), "Each projection must retain the other owner's predecessor too");
                }
                assertThrows(InvalidExecutionEvidenceException.class, () -> restored.verifyPrefix(changed,
                        input.bases().get(changed), input.entry().order(), differentPredecessors.get(changed)));

                for (boolean changeBasis : new boolean[] {true, false}) {
                    Map<String, byte[]> substitutedStore = new HashMap<>();
                    String replacementRoot = encode(input, changeBasis ? differentBases : input.bases(),
                            changeBasis ? input.predecessors() : differentPredecessors, substitutedStore);
                    assertNotEquals(admittedRoot, replacementRoot, "Both owners' context is part of the original root");
                    assertThrows(InvalidExecutionEvidenceException.class, () -> SourceInputAdmission.restore(admittedRoot,
                            key -> substitutedStore.get(replacementRoot), LIMITS),
                            "Self-consistent replacement bytes do not satisfy the independently fixed original root");
                }
            }
        }
    }

    @Test void omittingEitherCoOwnedMemberRejectsEvenWhenTheRequestedMemberMatches() {
        try (var runtime = new CanonicalSourceHistoryTest.Fixture()) {
            var input = input(runtime);
            for (DocumentId retained : input.members()) {
                Map<String, byte[]> store = new HashMap<>();
                String incompleteRoot = encode(input, Map.of(retained, input.bases().get(retained)),
                        Map.of(retained, input.predecessors().get(retained)), store);
                var incomplete = SourceInputAdmission.restore(incompleteRoot, store::get, LIMITS);
                var rejected = assertThrows(InvalidExecutionEvidenceException.class, () -> incomplete.verify(retained,
                        input.bases().get(retained), input.entry(), input.predecessors(), input.members(), input.members()));
                assertTrue(rejected.getMessage().contains("omits an original co-owned member"));
            }
        }
    }

    private static CoOwnedInput input(CanonicalSourceHistoryTest.Fixture runtime) {
        DocumentId a = runtime.authored("name: Original co-owned A", Map.of()).documentId();
        DocumentId b = runtime.authored("name: Original co-owned B", Map.of()).documentId();
        Map<DocumentId, String> bases = Map.of(
                a, SourceExecutionBasis.identity(a, runtime.core.environment(), runtime.core.executionPolicy()),
                b, SourceExecutionBasis.identity(b, runtime.core.environment(), runtime.core.executionPolicy()));
        assertNotEquals(bases.get(a), bases.get(b), "The producer basis binds each source member independently");
        return new CoOwnedInput(CanonicalSourceHistoryTest.input("original co-owned input", 15, "account"), bases,
                Map.of(a, "sha256:" + "a".repeat(64), b, "sha256:" + "b".repeat(64)));
    }

    private static AffectedClosureSnapshot cyclicAuthored(CanonicalSourceHistoryTest.Fixture runtime) {
        String yaml = """
                name: %s
                counter: 0
                contracts:
                  embedded:
                    type: {blueId: %s}
                    paths: [/peer]
                  ingress:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                    actor: {type: MyOS/Principal Actor, accountId: account}
                  update:
                    type: Coordination/Sequential Workflow
                    channel: ingress
                    steps:
                      - type: Coordination/Update Document
                        changeset: [{op: replace, path: /counter, val: 1}]
                """;
        Node a = runtime.authored(yaml.formatted("Co-owned A", RuntimeBlueIds.PROCESS_EMBEDDED), Map.of()).document();
        Node b = runtime.authored(yaml.formatted("Co-owned B", RuntimeBlueIds.PROCESS_EMBEDDED), Map.of()).document();
        // The authored origins are the final cyclic members, not the temporary pre-peer body IDs.
        var authoredSet = new CircularSetIdentityCalculator().finalizeCyclicSet(List.of(
                a.properties("peer", new Node().blueId("this#1")),
                b.properties("peer", new Node().blueId("this#0"))));
        Map<DocumentId, Node> bodies = new HashMap<>();
        Map<DocumentId, Long> generations = new HashMap<>();
        List<ManagedOccurrenceBinding> bindings = new java.util.ArrayList<>();
        for (var member : authoredSet.membersInCanonicalOrder()) {
            Node body = member.canonicalMemberBody();
            String peer = authoredSet.masterBlueId() + body.getAsNode("/peer").getBlueId().substring(4);
            body.getAsNode("/peer").blueId(peer);
            DocumentId id = new DocumentId(member.finalBlueId());
            bodies.put(id, body);
            generations.put(id, 0L);
            bindings.add(ManagedOccurrenceBinding.derived(runtime.core.environment().managedBindingPolicyIdentity(), id,
                    ScopeAddress.embedded("/peer", 1), new DocumentId(peer), peer, true, null));
        }
        List<DocumentId> members = bodies.keySet().stream().sorted().toList();
        var finalized = new ComponentFinalizationKernel().finalizeComponents(new ComponentFinalizationInput(
                ManagedDocumentGraph.fromBindings(members, bindings), generations, bodies, bindings));
        var documents = members.stream().map(member -> {
            var exact = finalized.document(member);
            assertEquals(member.value(), exact.blueId(), "Canonical history starts at the exact authored cyclic member");
            return new ManagedDocumentSnapshot(member, exact.blueId(), exact.document(), false, false, true,
                    0, exact.componentGeneration());
        }).toList();
        return ClosureEvidenceFactory.affectedClosure(0, documents, finalized.finalizedGraph().bindings(),
                finalized.components().stream().map(FinalizedComponentEvidence::component).toList(), members);
    }

    private static String encode(CoOwnedInput input, Map<DocumentId, String> bases,
                                 Map<DocumentId, String> predecessors, Map<String, byte[]> store) {
        return SourceInputAdmission.encodeCandidate(input.entry(), bases, predecessors,
                SameOriginAttachmentPolicy.empty(), store::put, LIMITS);
    }

    private record CoOwnedInput(CoordinationCore.TimelineInput entry, Map<DocumentId, String> bases,
                               Map<DocumentId, String> predecessors) {
        Set<DocumentId> members() { return bases.keySet(); }
    }
}
