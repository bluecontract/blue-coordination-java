package blue.coordination.internal;

import blue.coordination.api.ProcessingSelection;
import blue.coordination.api.ExactValue;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DrainBudget;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.ManagedEpochSelector;
import blue.coordination.sdk.TimelineHandle;
import blue.language.model.Node;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.processor.closure.ClosureProcessResult;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real reciprocal history whose unchanged, nonmanaged candidate also has a provider identity. */
final class RootedRetainedSourceWireTest {
    @Test void terminalReciprocalReceiptUsesItsExactFrozenSourceWireRepresentation() throws Exception {
        // given
        try (var s = new Scenario()) {
            s.advanceTo(3L);
            var input = s.input();
            var cause = (ManagedRevisionCause) input.cause();
            var source = input.snapshot().managedDocument(new blue.language.processor.closure.DocumentId(s.a.id().value()));
            assertEquals(3L, source.epoch());
            assertEquals(cause.afterBlueId(), source.blueId());
            var sourceBefore = s.history(s.a);
            var sourceEpoch = s.engine.auditDocument(s.a.id()).epoch();
            var consumerBefore = s.history(s.b);
            var resources = s.exactResources();
            var work = s.work();
            // when
            var result = s.blue.processing().drainManagedEpochApplication(work.workIdentity());
            // then
            assertEquals(1, result.managedEpochApplications().size(), result.diagnostic().toString());
            assertEquals(s.json(source.document()), s.json(cause.afterDocument()),
                    "One exact source must have one wire representation inside the frozen invocation");
            var receipt = s.engine.documents().catchUpApplicationByWork(work.workIdentity()).orElseThrow();
            var retained = s.engine.documents().closureReceiptForApplication(receipt).orElseThrow();
            var executed = retained.rootedTerminalEvidence().input();
            var actual = retained.attempt().processResult();
            var reference = coldMaterialized(executed, resources);
            assertTrue(reference.commits(), String.valueOf(reference.diagnostic()));
            assertNotNull(reference.rootedProjection(), "Historical witness roles belong to this original rooted input");
            assertFullResult(reference, actual);
            assertEquals(actual.totalGas(), result.managedEpochApplicationAttempts().get(0).attempt().processResult().totalGas());
            assertEquals(sourceBefore, s.history(s.a), "Import never re-executes or republishes its independent source");
            assertEquals(sourceEpoch, s.engine.auditDocument(s.a.id()).epoch());
            var finalizedSource = actual.resultingDocuments().stream()
                    .filter(document -> document.documentId().value().equals(s.a.id().value())).findFirst().orElseThrow();
            assertEquals(finalizedSource.afterBlueId(), s.engine.auditDocument(s.a.id()).current().blueId(),
                    "Terminal SCC finalization may rebase the source representation, without another numbered source epoch");
            assertEquals(consumerBefore.size() + 1, s.history(s.b).size());
            assertEquals(consumerBefore, s.history(s.b).subList(0, consumerBefore.size()));
            var histories = List.of(s.history(s.a), s.history(s.b));
            s.engine.restartFromStores();
            assertThrows(blue.coordination.api.CoordinationException.class,
                    () -> s.blue.processing().drainManagedEpochApplication(work.workIdentity()),
                    "Committed work cannot claim the current canonical SDK selector again");
            var replay = s.engine.contractsClosureAdapter().executeManagedEpochApplication(work);
            assertTrue(replay.replayed(), "The retained publication still reconciles by its original work identity");
            assertEquals(receipt.applicationReceiptIdentity(), replay.applicationReceipt().applicationReceiptIdentity());
            assertFullResult(actual, replay.attempt().processResult());
            assertEquals(histories, List.of(s.history(s.a), s.history(s.b)));
            var tightPolicy = blue.language.processor.closure.ClosureEvidenceFactory.executionPolicy(
                    reference.totalGas() - 1L, executed.executionPolicy().localLimits(), executed.executionPolicy().label());
            var tightInput = blue.language.processor.closure.ClosureEvidenceFactory.processClosure(executed.snapshot(),
                    executed.cause(), executed.directDeliveries(), tightPolicy, executed.environment())
                    .withRootedContext(actual.rootedProjection().context(), actual.rootedProjection().deliveryBasisIdentity());
            var tight = coldMaterialized(tightInput, resources);
            assertEquals(blue.language.processor.ProcessorStatus.GAS_LIMIT_EXCEEDED, tight.status());
            assertTrue(tight.rollbackToInput());
            assertEquals(tightInput.snapshot().closureIdentity(), tight.outputClosureIdentity());
            assertNull(tight.commitCompanion());
            assertTrue(tight.checkpointWrites().isEmpty());
            assertTrue(tight.managedTransitionReceipts().isEmpty());
            assertTrue(tight.publicEvents().isEmpty());
            assertNotNull(tight.rejectedCharge());
            assertEquals(reference.totalGas(), Math.addExact(tight.totalGas(), tight.rejectedCharge().subtotal()));
            assertNotEquals(executed.invocationIdentity(), tightInput.invocationIdentity(), "The budget is part of invocation authority");
            // A different budget identifies a different invocation and therefore different work IDs.
            // All other ordered charge operands remain the exact successful prefix.
            assertEquals(meteredTrace(reference).subList(0, reference.gasTrace().size() - 1), meteredTrace(tight));
            var tightAgain = coldMaterialized(tightInput, resources);
            assertEquals(tight.gasTraceIdentity(), tightAgain.gasTraceIdentity());
            assertEquals(fullTrace(tight), fullTrace(tightAgain));
            assertEquals(tight.rejectedCharge().rejectedChargeIdentity(), tightAgain.rejectedCharge().rejectedChargeIdentity());
            var last = reference.gasTrace().get(reference.gasTrace().size() - 1);
            assertEquals(last.namespace(), tight.rejectedCharge().namespace());
            assertEquals(last.counter(), tight.rejectedCharge().counter());
            assertEquals(last.quantity(), tight.rejectedCharge().quantity());
            assertEquals(last.weight(), tight.rejectedCharge().weight());
            assertEquals(last.subtotal(), tight.rejectedCharge().subtotal());
            assertEquals(histories, List.of(s.history(s.a), s.history(s.b)));
            System.out.println("RETAINED_SOURCE_WIRE_GAS=" + actual.totalGas() + "; trace=" + actual.gasTraceIdentity()
                    + "; receipt=" + receipt.applicationReceiptIdentity());
        }
    }

    @Test void olderHistoricalReceiptIsNotReplacedByTheSelectedTerminalSource() throws Exception {
        // given
        try (var s = new Scenario()) {
            // when
            var input = s.input();
            var cause = (ManagedRevisionCause) input.cause();
            var source = input.snapshot().managedDocument(new blue.language.processor.closure.DocumentId(s.a.id().value()));
            // then
            assertEquals(0L, cause.toEpoch());
            assertEquals(3L, source.epoch());
            assertNotEquals(cause.afterBlueId(), source.blueId());
            var receipt = s.engine.documents().managedEpochEvidence(s.a.id(), 0L).receipt();
            assertEquals(receipt.afterBlueId(), cause.afterBlueId());
            assertEquals(receipt.afterDocument().blueId(), ExactValue.verified(cause.afterDocument()).blueId());
            assertNotEquals(s.json(source.document()), s.json(cause.afterDocument()));
            assertEquals(1, s.blue.processing().drainManagedEpochApplication(s.work().workIdentity())
                    .managedEpochApplications().size());
        }
    }

    private static final class Scenario implements AutoCloseable {
        final Map<String, String> exact = new LinkedHashMap<>();
        final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds()
                .exactNodeProvider(id -> Optional.ofNullable(exact.get(id))).build();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        final RootedCalculationFixture control = new RootedCalculationFixture(engine);
        final TimelineHandle timeline = blue.timelines().register("labs/retained-managed-epoch/cycle/alice", "alice");
        final DocumentHandle a;
        final DocumentHandle b;

        Scenario() throws Exception {
            var bYaml = resource("source-b.yaml");
            var bAuthored = blue.values().yaml(bYaml);
            exact.put(bAuthored.blueId(), bAuthored.json());
            b = blue.documents().admitStaticProcessEmbedded(bYaml, ActivationPolicy.fromNow()).document("root");
            var advance = blue.operations().on(b).from(timeline).call("advance").through("ownerChannel")
                    .requestYaml("{}").submit();
            assertEquals(EntryDisposition.APPLIED,
                    blue.advanced().drainJournalThrough(advance, DrainBudget.unlimited()).entry(advance).disposition());
            var aYaml = resource("consumer-a.yaml");
            var aAuthored = blue.values().yaml(aYaml);
            exact.put(aAuthored.blueId(), aAuthored.json());
            a = blue.documents().admitStaticProcessEmbedded(aYaml, ActivationPolicy.fromNow()).document("root");
            var attach = blue.operations().on(a).from(timeline).call("attachCandidate").through("ownerChannel")
                    .request(request -> request.exact("embeddedContract", bAuthored))
                    .selectManagedEpoch(ManagedEpochSelector.exact(b.id(), -1L, bAuthored.blueId(), "/embeddedCandidates/selected"))
                    .submit();
            assertEquals(EntryDisposition.APPLIED,
                    blue.advanced().drainJournalThrough(attach, DrainBudget.unlimited()).entry(attach).disposition());
            for (long epoch = 0L; epoch <= 1L; epoch++) {
                var selected = blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow();
                assertEquals(b.id(), selected.sourceDocumentId());
                assertEquals(epoch, selected.sourceEpoch());
                assertEquals(1, blue.processing().drainManagedEpochApplication(selected.workIdentity()).managedEpochApplications().size());
            }
            var connect = blue.operations().on(b).from(timeline).call("connectA").through("ownerChannel")
                    .request(request -> request.exact("a", aAuthored))
                    .selectManagedEpoch(ManagedEpochSelector.exact(a.id(), -1L, aAuthored.blueId(), "/peer/a"))
                    .submit();
            assertEquals(EntryDisposition.APPLIED,
                    blue.advanced().drainJournalThrough(connect, DrainBudget.unlimited()).entry(connect).disposition());
        }

        void advanceTo(long epoch) {
            for (long expected = 0; expected < epoch; expected++) {
                var work = work();
                assertEquals(expected, work.sourceEpoch());
                assertEquals(1, blue.processing().drainManagedEpochApplication(work.workIdentity()).managedEpochApplications().size());
            }
            assertEquals(epoch, work().sourceEpoch());
        }

        blue.coordination.api.ManagedEpochApplicationWork work() {
            var selected = blue.advanced().auditNextProcessingSelection();
            assertEquals(ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION, selected.kind(),
                    "A live input must not overtake the canonical reciprocal catch-up");
            var work = control.registeredOwnedHistory(b.id());
            assertEquals(work.workIdentity(), selected.managedEpochApplicationWork().orElseThrow().workIdentity());
            assertEquals(a.id(), work.sourceDocumentId());
            return work;
        }

        ClosureInvocationInput input() { return control.captureRegisteredOwnedHistory(b.id()); }
        String json(Node node) { return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(node); }
        List<ExactValue> exactResources() {
            var values = new java.util.ArrayList<ExactValue>();
            exact.keySet().forEach(id -> values.add(engine.objects().require(id)));
            for (var document : List.of(a, b)) for (var receipt : blue.advanced().auditManagedEpochs(document.id()))
                values.add(engine.documents().managedEpochEvidence(document.id(), receipt.epoch()).receipt().afterDocument());
            return List.copyOf(values);
        }
        List<List<Object>> history(DocumentHandle document) {
            return blue.advanced().auditManagedEpochs(document.id()).stream().map(receipt -> List.<Object>of(
                    receipt.receiptIdentity(), receipt.documentId(), receipt.epoch(), receipt.kind(), receipt.beforeBlueId(),
                    receipt.afterBlueId(), receipt.afterDocument().json(), receipt.originalCauseIdentity(),
                    receipt.sourceEntry().map(entry -> List.of(entry.exact().json(), entry.globalSequence(), entry.timelineSequence())),
                    receipt.sourceOrder(), receipt.contractsTransitionReceiptIdentity(), receipt.commitCompanionIdentity(),
                    receipt.emittedEvents().stream().map(event -> List.of(event.managedEventIdentity(), event.ordinal(),
                            event.eventOccurrenceOrdinal(), event.sourceDocumentId(), event.eventOccurrenceIdentity(),
                            event.eventBlueId(), event.exactEvent().json(), event.publicAtSource())).toList(),
                    receipt.processingGas())).toList();
        }
        @Override public void close() { blue.close(); }
    }

    private static String resource(String name) throws Exception {
        try (var in = RootedRetainedSourceWireTest.class.getResourceAsStream("/rooted/retained-source-wire/" + name)) {
            return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertFullResult(ClosureProcessResult reference, ClosureProcessResult actual) {
        assertEquals(reference.status(), actual.status());
        assertEquals(reference.inputClosureIdentity(), actual.inputClosureIdentity());
        assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
        assertEquals(reference.commitCompanion().companionIdentity(), actual.commitCompanion().companionIdentity());
        assertEquals(reference.publicEventsIdentity(), actual.publicEventsIdentity());
        assertEquals(reference.checkpointWritesIdentity(), actual.checkpointWritesIdentity());
        assertEquals(reference.managedTransitionReceiptsIdentity(), actual.managedTransitionReceiptsIdentity());
        assertEquals(reference.totalGas(), actual.totalGas());
        assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
        assertEquals(fullTrace(reference), fullTrace(actual));
        assertEquals(documents(reference), documents(actual));
    }

    private static List<List<Object>> documents(ClosureProcessResult result) {
        return result.resultingDocuments().stream().map(document -> java.util.Arrays.<Object>asList(
                document.documentId(), document.beforeBlueId(), document.afterBlueId(),
                UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(document.document()), document.initialized(),
                document.terminated(), document.publicRoot(), document.epoch(), document.componentGeneration(),
                document.componentIdentity(), document.componentStateIdentity(), document.memberIndex())).toList();
    }

    /** Fresh physical materialization, preserving the authenticated rooted witness roles, not substituting base semantics. */
    private static ClosureProcessResult coldMaterialized(ClosureInvocationInput input, List<ExactValue> resources) {
        var objects = new WholeObjectStore(new EngineMetrics());
        for (var value : resources) objects.putVerifiedProviderEvidence(value, value.copyNode(),
                value.cyclicSetProof().orElse(null), "retained source wire reference exact resource");
        for (var document : input.snapshot().managedDocuments()) {
            var component = input.snapshot().components().stream()
                    .filter(value -> value.orderedMemberDocumentIds().contains(document.documentId())).findFirst().orElseThrow();
            var proof = component.completeCyclicProof();
            var value = proof == null ? ExactValue.verified(document.blueId(), document.document())
                    : ExactValue.fromVerifiedProviderEvidence(document.blueId(), document.document(), proof);
            objects.putVerifiedProviderEvidence(value, document.document(), proof, "retained source wire reference snapshot");
        }
        try (var runtime = BlueRuntime.create(objects)) {
            var attempt = new blue.language.processor.closure.BlueClosureContracts(runtime.documentProcessor()).processClosure(input);
            assertTrue(attempt.isComplete(), () -> "Cold materialized input suspended: " + attempt.resourceDemands().stream()
                    .map(demand -> demand.kind() + ":" + demand.sourceDocumentId() + ":" + demand.sourcePath()).toList());
            return attempt.processResult();
        }
    }

    private static List<List<Object>> fullTrace(ClosureProcessResult result) {
        return result.gasTrace().stream().map(charge -> java.util.Arrays.<Object>asList(
                charge.sequence(), charge.namespace(), charge.counter(), charge.quantity(), charge.weight(), charge.subtotal(),
                charge.documentId(), charge.scopePath(), charge.activationGeneration(), charge.componentGeneration(),
                charge.contractKey(), charge.logicalPath(), charge.workOccurrenceId(), charge.reason())).toList();
    }

    private static List<List<Object>> meteredTrace(ClosureProcessResult result) {
        return fullTrace(result).stream().map(charge -> {
            var operands = new java.util.ArrayList<>(charge);
            operands.remove(12); // The invocation-bound work identity is deliberately different across policies.
            return java.util.Collections.unmodifiableList(operands);
        }).toList();
    }
}
