package blue.coordination.external;

import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real independent producer admission, not a policy copied out of an offered receipt. */
class IndependentInitializationPolicyTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test void hotAndColdInitializationUseTheProducersBasisAndTheConsumersGas() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var producer = producer(f, 5_000);
            var source = birth(f, producer, f.source());
            var parent = f.initializingObserver("Independent parent", source.authored.blueId(), "agreement-updated");
            var evidence = installation(f, parent, source).withExpectedSourceBases(bases(producer, source));
            var work = initialization(parent);
            CoordinationCore.PreparedOperation previous = null;
            for (boolean cold : List.of(false, true)) {
                var result = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(work,
                        evidence.withSourceInitializations(List.of(source.capability(f, cold)))));
                assertEquals(ProcessorStatus.SUCCESS, result.result().status());
                assertEquals(100_000, result.invocation().executionPolicy().sharedLimit());
                assertEquals(5_000, result.sourceProgram().orElseThrow().borrowedPrograms().get(0).executionPolicy().sharedLimit());
                assertEquals(List.of(parent.documentId()), result.projections().stream().map(CoordinationCore.LineageProjection::lineage).toList());
                assertEquals(1518, result.result().totalGas(), "Only the parent's own initialization/observation work is charged");
                if (previous != null) {
                    assertEquals(previous.operationId(), result.operationId());
                    assertEquals(previous.result().gasTraceIdentity(), result.result().gasTraceIdentity());
                }
                previous = result;
            }
        }
    }

    @Test void missingAndWrongInitializationAuthorityFailBeforeInstallationButUnrelatedCacheDoesNotBlock() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var producer = producer(f, 5_000); var source = birth(f, producer, f.source());
            var parent = f.initializingObserver("Parent", source.authored.blueId(), "agreement-updated");
            var evidence = installation(f, parent, source).withSourceInitializations(List.of(source.capability(f, true)));
            assertEquals(List.of("source-execution-basis:" + source.authored.documentId().value()),
                    assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(initialization(parent), evidence)).keys());
            assertThrows(IllegalArgumentException.class, () -> f.core.evaluate(initialization(parent),
                    evidence.withExpectedSourceBases(bases(f.core, source))));
            var unrelated = f.authored("name: Unrelated parent", Map.of());
            assertEquals(ProcessorStatus.SUCCESS, assertInstanceOf(CoordinationCore.PreparedOperation.class,
                    f.core.evaluate(initialization(unrelated), f.evidence(unrelated, List.of(), 21)
                            .withSourceInitializations(evidence.sourceInitializations()))).result().status());
        }
    }

    @Test void nestedBorrowedInitializationKeepsEachIndependentProducerBasisHotAndCold() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var zCore = producer(f, 5_000); var yCore = producer(f, 20_000);
            var z = birth(f, zCore, f.source());
            var yAuthored = f.initializingObserver("Y", z.authored.blueId(), "agreement-updated");
            var yOperation = assertInstanceOf(CoordinationCore.PreparedOperation.class, yCore.evaluate(initialization(yAuthored),
                    installation(f, yAuthored, z).withExpectedSourceBases(bases(zCore, z))
                            .withSourceInitializations(List.of(z.capability(f, true)))));
            var y = retained(f, yAuthored, yOperation);
            var x = f.initializingObserver("X", y.authored.blueId(), "Y-reacted");
            var bindings = new ArrayList<>(yOperation.ownedOccurrenceBindings()); bindings.add(f.binding(x, y.authored));
            var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(x, y.state, z.state), bindings,
                    List.of(ClosureEvidenceFactory.acyclicComponent(z.state), ClosureEvidenceFactory.acyclicComponent(y.state),
                            ClosureEvidenceFactory.acyclicComponent(x)), List.of(x.documentId(), y.state.documentId(), z.state.documentId()),
                    List.of(pin(z), pin(y)));
            var expected = new TreeMap<>(bases(zCore, z)); expected.putAll(bases(yCore, y));
            var evidence = new CoordinationCore.EvaluationEvidence(snapshot, Set.of(), List.of(), Optional.empty(), List.of(), Map.of());
            String operation = null;
            for (boolean cold : List.of(false, true)) {
                var offered = evidence.withSourceInitializations(List.of(y.capability(f, cold)));
                assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(initialization(x), offered.withExpectedSourceBases(bases(yCore, y))));
                var wrong = new TreeMap<>(expected); wrong.putAll(bases(yCore, z));
                assertThrows(IllegalArgumentException.class, () -> f.core.evaluate(initialization(x), offered.withExpectedSourceBases(wrong)));
                var result = assertInstanceOf(CoordinationCore.PreparedOperation.class,
                        f.core.evaluate(initialization(x), offered.withExpectedSourceBases(expected)));
                assertEquals(ProcessorStatus.SUCCESS, result.result().status());
                if (operation != null) assertEquals(operation, result.operationId()); operation = result.operationId();
                assertEquals(1, result.projections().size());
            }
        }
    }

    @Test void actualFrontierCreationAcceptsIndependentBasisAndRejectsMissingOrWrongAuthority() {
        Set<String> operations = new HashSet<>();
        assertEquals(creation(false, false, operations), creation(false, true, operations), "Changing the producer budget cannot charge its work to the creator");
        assertEquals(2, operations.size(), "Different authenticated frontier producer contexts cannot alias one creator operation");
    }
    @Test void actualRuntimeInitializationAcceptsIndependentBasisAndRejectsMissingOrWrongAuthority() {
        Set<String> operations = new HashSet<>();
        assertEquals(creation(true, false, operations), creation(true, true, operations), "The same creator work has the same exact charged counter sequence");
        assertEquals(2, operations.size(), "Different authenticated canonical initializations cannot alias one creator operation");
    }

    private static GasShape creation(boolean fullHistory, boolean independent, Set<String> operations) {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var producer = independent ? producer(f, 5_000) : f.core;
            var source = birth(f, producer, f.authored("""
                    name: Source also matches the creator Entry
                    counter: 0
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: creator}
                      update:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        steps:
                          - type: Coordination/Update Document
                            changeset: [{op: replace, path: /counter, val: 99}]
                          - type: Coordination/Trigger Event
                            event: forbidden-source-rerun
                    """, Map.of()));
            var frontierOrder = CanonicalSourceHistoryTest.input("frontier", 5, "other").order();
            var history = new CanonicalSourceHistory(producer);
            var request = new CanonicalSourceHistory.Request(source.authored.documentId(), frontierOrder);
            var birth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(request, history.start(source.authored.documentId()),
                    f.evidence(source.authored, List.of(), 100), f.blobs::put, LIMITS));
            var boundary = assertInstanceOf(CanonicalSourceHistory.Complete.class, history.prepareNext(request, birth.after(),
                    f.evidence(source.state, List.of(), 100), f.blobs::put, LIMITS)).boundary();
            String supplied = fullHistory ? source.authored.blueId() : source.state.blueId();
            var authored = f.authored("""
                    name: Independent creator
                    counter: -1
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: creator}
                      create:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        steps:
                          - type: Coordination/Update Document
                            changeset: [{op: add, path: /contracts/embedded, val: {type: Process Embedded, paths: [/child]}}]
                    """, Map.of("child", supplied));
            var parent = birth(f, f.core, authored);
            var binding = ManagedOccurrenceBinding.derived(f.core.environment().managedBindingPolicyIdentity(), parent.state.documentId(),
                    ScopeAddress.embedded("/child", 1), source.state.documentId(), supplied, false, null);
            var choice = new SameOriginAttachmentPolicy.Selection(fullHistory ? SameOriginAttachmentPolicy.Mode.FULL_HISTORY
                    : SameOriginAttachmentPolicy.Mode.FROM_FRONTIER, parent.state.documentId(), binding.occurrenceIdentity(),
                    source.state.documentId(), supplied, fullHistory ? null : frontierOrder);
            var policy = new SameOriginAttachmentPolicy(List.of(choice));
            var documents = Map.of(parent.state.documentId(), parent.state, source.state.documentId(), source.state);
            var components = new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(documents.keySet(), List.of(binding)))
                    .stream().map(ids -> ClosureEvidenceFactory.acyclicComponent(documents.get(ids.get(0)))).toList();
            var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(parent.state, source.state), List.of(binding), components,
                    List.of(parent.state.documentId(), source.state.documentId()), fullHistory ? List.of(pin(source)) : List.of());
            var entry = CanonicalSourceHistoryTest.input("create", 20, "creator");
            var evidence = new CoordinationCore.EvaluationEvidence(snapshot, Set.of("timeline"),
                    List.of(new CoordinationCore.TimelinePrefix("timeline", 100, List.of(entry))), Optional.empty(), List.of(),
                    Map.of(parent.state.documentId(), parent.operation.operationId(), source.state.documentId(), source.operation.operationId()));
            var frontier = fullHistory ? null : SourceFrontierSelection.fromBoundary(choice, boundary, birth.receiptIdentity(), f.blobs::get, LIMITS);
            var work = new CoordinationCore.WorkIntent(parent.state.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            String operation = null, trace = null; GasShape gas = null;
            for (boolean cold : List.of(false, true)) {
                var offered = fullHistory ? evidence.withSourceInitializations(List.of(source.capability(f, cold)))
                        : evidence.withSourceFrontiers(List.of(frontier));
                assertEquals(List.of("source-execution-basis:" + source.state.documentId().value()),
                        assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(work, offered, policy)).keys());
                assertThrows(IllegalArgumentException.class, () -> f.core.evaluate(work, offered.withExpectedSourceBases(bases(producer(f, 7_000), source)), policy));
                var result = assertInstanceOf(CoordinationCore.PreparedOperations.class,
                        f.core.evaluate(work, offered.withExpectedSourceBases(bases(producer, source)), policy));
                assertEquals(1, result.operations().size()); var created = result.operations().get(0);
                assertEquals(ProcessorStatus.SUCCESS, created.result().status());
                assertEquals(Set.of(parent.state.documentId()), created.ownedLineages());
                if (operation != null) assertEquals(operation, created.operationId()); operation = created.operationId();
                if (trace != null) assertEquals(trace, created.result().gasTraceIdentity()); trace = created.result().gasTraceIdentity();
                var shape = new GasShape(created.result().totalGas(), created.result().gasTrace().stream()
                        .map(charge -> charge.namespace() + ":" + charge.counter() + ":" + charge.quantity() + ":" + charge.weight() + ":" + charge.subtotal()).toList());
                if (gas != null) assertEquals(gas, shape); gas = shape;
                if (fullHistory) assertEquals(1, created.result().sourceProgram().orElseThrow().acceptedInitializations().size());
                String receipt = OperationReceiptCodec.encode(created, f.blobs::put, LIMITS).receiptIdentity();
                var retained = OperationReceiptCodec.restoreSourceProgram(receipt, f.blobs::get, LIMITS);
                var retainedBases = new TreeMap<>(bases(producer, source)); retainedBases.putAll(bases(f.core, parent));
                var wrongRetainedBases = new TreeMap<>(retainedBases); wrongRetainedBases.putAll(bases(producer(f, 7_000), source));
                try (var contracts = new BlueClosureContracts(f.processor)) {
                    assertThrows(IllegalArgumentException.class, () -> contracts.processSameOrigin(created.invocation(), policy,
                            List.of(retained), Map.of(), List.of(), List.of(), List.of(), wrongRetainedBases));
                    var replay = contracts.processSameOrigin(created.invocation(), policy, List.of(retained), Map.of(),
                            List.of(), List.of(), List.of(), retainedBases);
                    assertTrue(replay.complete()); assertTrue(replay.operations().isEmpty(), "Cold replay republishes neither producer");
                }
                if (cold) managedImport(f, source, producer, parent, created, retained);
            }
            operations.add(operation);
            return gas;
        }
    }

    private static void managedImport(CanonicalSourceHistoryTest.Fixture f, Birth source, CoordinationCore producer,
                                      Birth parent, CoordinationCore.PreparedGroupOperation created, SourceObservationProgram retained) {
        var installed = new CanonicalSourceHistory.View(parent.state.documentId(), parent.state.blueId(), 0, false, parent.receipt);
        var observer = f.observer("Managed observer of the creator", installed);
        String parentBasis = bases(f.core, parent).get(parent.state.documentId());
        // This fixture's committed FULL_HISTORY attachment selects the exact init0 header
        // and a cut after Entry20. It admits a historical lane, not ordinary live work.
        var lane = new ManagedImportLane.Descriptor(observer.before().documentId(), observer.binding().occurrenceIdentity(),
                parent.state.documentId(), ManagedImportLane.digest("fixture-creation", observer.before().blueId()),
                ManagedImportLane.digest("fixture-seed", observer.before().blueId()), ManagedImportLane.digest("fixture-site", observer.before().blueId()),
                ObserverAttachmentPlan.Selection.fullHistory(CanonicalSourceHistoryTest.input("historical activation", 30, "observer").order()),
                parentBasis, parent.operation.operationId(), parent.state.blueId(), 0);
        var due = new ManagedImportLane.Due(ManagedImportLane.Cursor.start(lane),
                ManagedImportLane.Header.fromOperation(created, parent.state.documentId(), parentBasis), Optional.empty());
        var documents = new TreeMap<DocumentId, ManagedDocumentSnapshot>();
        for (var document : created.invocation().snapshot().managedDocuments()) documents.put(document.documentId(), document);
        documents.put(observer.before().documentId(), observer.before());
        var bindings = new ArrayList<>(created.invocation().snapshot().occurrences()); bindings.add(observer.binding());
        var components = new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(documents.keySet(), bindings))
                .stream().map(ids -> ClosureEvidenceFactory.acyclicComponent(documents.get(ids.get(0)))).toList();
        var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.copyOf(documents.values()), bindings, components,
                List.copyOf(documents.keySet()), created.invocation().snapshot().readPins());
        var evidence = new CoordinationCore.EvaluationEvidence(snapshot, Set.of(), List.of(), Optional.empty(), List.of(), Map.of(), List.of(retained));
        var selection = new ManagedImportSelection(List.of(due));
        var withoutChild = new TreeMap<>(documents); withoutChild.remove(source.state.documentId());
        var withoutChildRows = bindings.stream().filter(row -> withoutChild.containsKey(row.sourceDocumentId())
                && withoutChild.containsKey(row.targetDocumentId())).toList();
        var withoutChildComponents = new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(withoutChild.keySet(), withoutChildRows))
                .stream().map(ids -> ClosureEvidenceFactory.acyclicComponent(withoutChild.get(ids.get(0)))).toList();
        var absentSnapshot = ClosureEvidenceFactory.affectedClosure(0, List.copyOf(withoutChild.values()), withoutChildRows,
                withoutChildComponents, List.copyOf(withoutChild.keySet()));
        var absentEvidence = new CoordinationCore.EvaluationEvidence(absentSnapshot, Set.of(), List.of(), Optional.empty(), List.of(), Map.of(), List.of(retained))
                .withExpectedSourceBases(bases(producer, source));
        assertEquals(List.of("lineage:" + source.state.documentId().value()),
                assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(selection, absentEvidence)).keys());
        assertEquals(List.of("source-execution-basis:" + source.state.documentId().value()),
                assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(selection, evidence)).keys());
        assertThrows(IllegalArgumentException.class, () -> f.core.evaluate(selection, evidence.withExpectedSourceBases(bases(producer(f, 7_000), source))));
        var imported = assertInstanceOf(CoordinationCore.PreparedOperation.class,
                f.core.evaluate(selection, evidence.withExpectedSourceBases(bases(producer, source))));
        assertEquals(ProcessorStatus.SUCCESS, imported.result().status());
        assertEquals(Set.of(observer.before().documentId()), imported.ownedLineages());
        assertEquals(List.of(created.operationId()), imported.consumedSourceOperations());
        assertTrue(imported.invocation().directDeliveries().stream().allMatch(delivery -> delivery.targetDocumentId().equals(parent.state.documentId())),
                "A selected immutable child header is not a new producer, even when its actor matches the Entry");
    }

    private static CoordinationCore producer(CanonicalSourceHistoryTest.Fixture f, long limit) {
        return new CoordinationCore(f.processor, f.core.environment(), ClosureEvidenceFactory.executionPolicy(limit, Map.of(), "producer-" + limit));
    }
    private static CoordinationCore.WorkIntent initialization(ManagedDocumentSnapshot source) {
        return new CoordinationCore.WorkIntent(source.documentId(), CoordinationCore.OperationKind.INITIALIZATION);
    }
    private static Birth birth(CanonicalSourceHistoryTest.Fixture f, CoordinationCore core, ManagedDocumentSnapshot authored) {
        return retained(f, authored, assertInstanceOf(CoordinationCore.PreparedOperation.class,
                core.evaluate(initialization(authored), f.evidence(authored, List.of(), 100))));
    }
    private static Birth retained(CanonicalSourceHistoryTest.Fixture f, ManagedDocumentSnapshot authored, CoordinationCore.PreparedOperation operation) {
        assertEquals(ProcessorStatus.SUCCESS, operation.result().status());
        String receipt = OperationReceiptCodec.encode(operation, f.blobs::put, LIMITS).receiptIdentity();
        var state = OperationReceiptCodec.restoreState(receipt, authored.documentId(), true, 0, f.blobs::get, LIMITS);
        f.nodes.put(state.blueId(), state.document()); return new Birth(authored, state, operation, receipt);
    }
    private static Map<DocumentId, String> bases(CoordinationCore core, Birth source) {
        return Map.of(source.authored.documentId(), SourceExecutionBasis.identity(source.authored.documentId(), core.environment(), core.executionPolicy()));
    }
    private static ManagedReadPin pin(Birth source) {
        return ManagedReadPin.fromExactEvidence(source.authored.documentId(), source.authored.blueId(), source.authored.document(), null);
    }
    private static CoordinationCore.EvaluationEvidence installation(CanonicalSourceHistoryTest.Fixture f, ManagedDocumentSnapshot parent, Birth source) {
        var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(parent, source.state), List.of(f.binding(parent, source.authored)),
                List.of(ClosureEvidenceFactory.acyclicComponent(source.state), ClosureEvidenceFactory.acyclicComponent(parent)),
                List.of(parent.documentId(), source.state.documentId()), List.of(pin(source)));
        return new CoordinationCore.EvaluationEvidence(snapshot, Set.of(), List.of(), Optional.empty(), List.of(), Map.of());
    }
    private record Birth(ManagedDocumentSnapshot authored, ManagedDocumentSnapshot state, CoordinationCore.PreparedOperation operation, String receipt) {
        SourceInitialization capability(CanonicalSourceHistoryTest.Fixture f, boolean cold) {
            return cold ? OperationReceiptCodec.restoreSourceInitialization(receipt, f.blobs::get, LIMITS)
                    : SourceInitialization.fromProgram(operation.sourceProgram().orElseThrow());
        }
    }
    private record GasShape(long total, List<String> charges) { }
}
