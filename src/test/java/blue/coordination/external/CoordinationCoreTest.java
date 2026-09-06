package blue.coordination.external;

import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.SourceExecutionBasis;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.closure.SccPartitioner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CoordinationCoreTest {
    @Test
    void actualTimelineWorkflowProducesOnceAndIndependentObserverImportsItsProgram() {
        Map<String, Node> exactNodes = new java.util.HashMap<>();
        try (var runtime = blue.coordination.processor.CoordinationTestRuntime.create(blue.repo.BlueRepository.current(),
                id -> exactNodes.containsKey(id) ? List.of(exactNodes.get(id).clone()) : List.of(),
                blue.language.api.BlueCachePolicy.disabled())) {
            var options = blue.coordination.processor.CoordinationProcessorOptions.builder().language(runtime.language()).build();
            var registry = blue.coordination.processor.CoordinationProcessors.configure(
                    blue.language.processor.ContractProcessorRegistryBuilder.create().registerDefaults(), options).build();
            try (DocumentProcessor processor = DocumentProcessor.builder().runtimeAccess(runtime.contracts().runtimeAccess())
                    .scanContractTypes("blue.repo").runtimeRegistry(registry).runtimeRegistryIdentity(registry.generationIdentity()).build()) {
            Node sourceBody = runtime.resolveToSnapshot(runtime.yamlToNode("""
                    name: Independent source
                    counter: 0
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline:
                          type: MyOS/MyOS Timeline
                          timelineId: timeline
                        actor:
                          type: MyOS/Principal Actor
                          accountId: account
                      update:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        steps:
                          - type: Coordination/Update Document
                            changeset:
                              - op: replace
                                path: /counter
                                val: 2
                          - type: Coordination/Trigger Event
                            event: source-done
                    """)).canonicalRoot();
            var sourceAuthored = ExactValue.verified(sourceBody);
            var sourceId = new DocumentId(sourceAuthored.blueId());
            var sourceInitial = new ManagedDocumentSnapshot(sourceId, sourceAuthored.blueId(), sourceBody,
                    false, false, true, 0L, 0L);
            var sourceBirth = assertInstanceOf(CoordinationCore.PreparedOperation.class, core(processor).evaluate(
                    new CoordinationCore.WorkIntent(sourceId, CoordinationCore.OperationKind.INITIALIZATION),
                    evidence(sourceInitial, "birth", Set.of(), List.of())));
            assertEquals(ProcessorStatus.SUCCESS, sourceBirth.result().status(), () -> sourceBirth.result().diagnostic().toString());
            var sourceBefore = restored(sourceBirth.projections().get(0));
            exactNodes.put(sourceBefore.blueId(), sourceBefore.document());
            var input = entry("advance", 10L);
            var entries = List.of(new CoordinationCore.TimelinePrefix("timeline", 11L,
                    List.of(new CoordinationCore.TimelineInput("timeline", 10L, input, input, List.of()))));
            var sourceResult = singleGroup(core(processor).evaluate(
                    new CoordinationCore.WorkIntent(sourceId, CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    evidence(sourceBefore, "source-1", Set.of("timeline"), entries)));
            assertEquals(ProcessorStatus.SUCCESS, sourceResult.result().status(), () -> sourceResult.result().failure().toString());
            assertEquals(java.math.BigInteger.valueOf(2), sourceResult.projections().get(0).result().document()
                    .getProperties().get("counter").getValue());
            assertEquals(1, sourceResult.result().events().size());

            Node observerBody = runtime.yamlToNode("""
                    name: Independent observer
                    seen: 0
                    contracts:
                      embedded:
                        type: {blueId: %s}
                        paths: [/source]
                      fromSource:
                        type: {blueId: %s}
                        sourcePath: /source
                      observe:
                        type: Coordination/Sequential Workflow
                        channel: fromSource
                        steps:
                          - type: Coordination/Update Document
                            changeset:
                              - op: replace
                                path: /seen
                                val: 2
                          - type: Coordination/Trigger Event
                            event: observer-done
                    """.formatted(blue.language.processor.registry.RuntimeBlueIds.PROCESS_EMBEDDED,
                            blue.language.processor.registry.RuntimeBlueIds.EMBEDDED_NODE_CHANNEL))
                    .properties("source", new Node().blueId(sourceBefore.blueId()));
            observerBody = runtime.resolveToSnapshotPreservingPaths(observerBody, List.of("/source")).canonicalRoot();
            var observerExact = ExactValue.verified(observerBody);
            var observerId = new DocumentId(observerExact.blueId());
            var observerInitial = new ManagedDocumentSnapshot(observerId, observerExact.blueId(), observerBody,
                    false, false, true, 0L, 0L);
            var binding = blue.language.processor.closure.ManagedOccurrenceBinding.derived(
                    sourceBirth.invocation().environment().managedBindingPolicyIdentity(), observerId,
                    blue.language.processor.closure.ScopeAddress.embedded("/source", 1L), sourceId, sourceBefore.blueId(), true, null);
            var observerBirth = assertInstanceOf(CoordinationCore.PreparedOperation.class, core(processor).evaluate(
                    new CoordinationCore.WorkIntent(observerId, CoordinationCore.OperationKind.INITIALIZATION),
                    pairEvidence(observerInitial, sourceBefore, binding, List.of(), List.of())));
            assertEquals(ProcessorStatus.SUCCESS, observerBirth.result().status(), () -> observerBirth.result().diagnostic().toString());
            var observerBefore = restored(observerBirth.projections().get(0));
            var fresh = assertInstanceOf(CoordinationCore.PreparedOperations.class, core(processor).evaluate(
                    new CoordinationCore.WorkIntent(observerId, CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    pairEvidence(observerBefore, sourceBefore, binding, entries, List.of())));
            assertEquals(2, fresh.operations().size());
            assertEquals(sourceResult.operationId(), fresh.operations().get(0).operationId(), "Producer identity excludes observer inventory");
            var imported = singleGroup(core(processor).evaluate(
                    new CoordinationCore.WorkIntent(observerId, CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    pairEvidence(observerBefore, sourceBefore, binding, entries, List.of(sourceResult.sourceProgram().orElseThrow()))
                            .withExpectedSourceBases(Map.of(sourceId, SourceExecutionBasis.identity(sourceId,
                                    sourceResult.invocation().environment(), sourceResult.invocation().executionPolicy())))));
            assertEquals(ProcessorStatus.SUCCESS, imported.result().status(), () -> imported.result().failure().toString());
            assertEquals(fresh.operations().get(1).operationId(), imported.operationId());
            assertEquals(fresh.operations().get(1).result().gasTraceIdentity(), imported.result().gasTraceIdentity());
            assertEquals(List.of(new CoordinationCore.ReadFence("observer", "1")), imported.fences(),
                    "B may publish first without invalidating the independent A predecessor CAS");
            assertEquals(List.of(new CoordinationCore.ReadFence("source", "1")), fresh.operations().get(0).fences());
            assertEquals(List.of(sourceResult.operationId()), imported.consumedSourceOperations());
            assertThrows(IllegalArgumentException.class, () -> new CoordinationCore.PreparedGroupOperation(
                    imported.operationId(), imported.kind(), imported.disposition(), imported.input(), imported.fences(), imported.invocation(),
                    imported.result(), List.of(), imported.sourcePins(), imported.consumedSourceOperations()));
            assertThrows(IllegalArgumentException.class, () -> new CoordinationCore.PreparedGroupOperation(
                    imported.operationId(), imported.kind(), imported.disposition(), imported.input(), imported.fences(), imported.invocation(),
                    imported.result(), imported.projections(), imported.sourcePins(), List.of()));
            assertThrows(IllegalArgumentException.class, () -> new CoordinationCore.PreparedGroupOperation(
                    imported.operationId(), imported.kind(), imported.disposition(), imported.input(), imported.fences(), imported.invocation(),
                    imported.result(), imported.projections(), List.of(), imported.consumedSourceOperations()));
            var unscoped = pairEvidence(observerBefore, sourceBefore, binding, entries, List.of()).withOperationFences(Map.of());
            var needFences = assertInstanceOf(CoordinationCore.NeedEvidence.class, core(processor).evaluate(
                    new CoordinationCore.WorkIntent(observerId, CoordinationCore.OperationKind.EXTERNAL_INPUT), unscoped));
            assertEquals(2, needFences.keys().size());
            assertTrue(needFences.keys().stream().allMatch(key -> key.startsWith("group-read-fences:")));
            assertEquals(List.of(observerId), imported.projections().stream().map(CoordinationCore.LineageProjection::lineage).toList(),
                    "An imported source is immutable evidence, never an owned publication projection");
            assertEquals(java.math.BigInteger.valueOf(2), imported.projections().get(0).result().document()
                    .getProperties().get("seen").getValue());
            assertEquals(1, imported.result().events().size());
            assertEquals("observer-done", imported.result().events().get(0).event().getValue());
            assertEquals(Set.of(observerId), imported.sourceProgram().orElseThrow().ownedDocumentIds());
            assertTrue(imported.result().gasTrace().stream().noneMatch(g -> sourceId.equals(g.documentId())),
                    "Previously metered source work must not enter the independent observer ledger");
            assertReceiptRoundTrip(sourceBirth);
            assertReceiptRoundTrip(sourceResult);
            assertReceiptRoundTrip(observerBirth);
            assertReceiptRoundTrip(imported);

            var noGas = new CoordinationCore(processor, sourceResult.invocation().environment(),
                    ClosureEvidenceFactory.executionPolicy(0L, Map.of(), "zero-consumer-gas"));
            var failed = singleGroup(noGas.evaluate(
                    new CoordinationCore.WorkIntent(observerId, CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    pairEvidence(observerBefore, sourceBefore, binding, entries, List.of(sourceResult.sourceProgram().orElseThrow()))
                            .withExpectedSourceBases(Map.of(sourceId, SourceExecutionBasis.identity(sourceId,
                                    sourceResult.invocation().environment(), sourceResult.invocation().executionPolicy())))));
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.result().status());
            assertEquals(CoordinationCore.Disposition.CONSUMED, failed.disposition());
            Map<String, byte[]> persisted = new java.util.HashMap<>();
            var limits = blue.language.processor.closure.FrozenNodeEvidenceCodec.Limits.defaults();
            var failureReceipt = OperationReceiptCodec.encode(failed, persisted::put, limits);
            var sourceReceipt = OperationReceiptCodec.encode(sourceResult, persisted::put, limits);
            var gap = OperationReceiptCodec.restoreSourceGap(failureReceipt.receiptIdentity(), observerId, sourceId,
                    sourceReceipt.receiptIdentity(), persisted::get, limits);
            assertEquals(failed.operationId(), gap.failedInvocationIdentity());
            assertEquals(sourceResult.operationId(), gap.offeredSourceOperation());
            assertEquals(sourceBefore.blueId(), gap.observedBlueId());
            assertEquals(sourceBefore.epoch(), gap.observedEpoch());
            var terminal = OperationReceiptCodec.restoreSourceFailure(failureReceipt.receiptIdentity(), persisted::get, limits);
            assertEquals(failed.operationId(), terminal.invocationIdentity());
            assertEquals(Set.of(observerId), terminal.ownedDocumentIds());
            assertEquals(failed.result().totalGas(), terminal.totalGas());
            assertThrows(IllegalArgumentException.class, () -> blue.language.processor.closure.SourceOperationFailure.fromSameOrigin(imported.result()));
            assertReceiptRoundTrip(failed);

            var failedSource = singleGroup(noGas.evaluate(
                    new CoordinationCore.WorkIntent(sourceId, CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    evidence(sourceBefore, "failed-source", Set.of("timeline"), entries)));
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failedSource.result().status());
            var failedSourceReceipt = OperationReceiptCodec.encode(failedSource, persisted::put, limits);
            var sourceFailure = OperationReceiptCodec.restoreSourceFailure(failedSourceReceipt.receiptIdentity(), persisted::get, limits);
            var unchangedCut = pairEvidence(observerBefore, sourceBefore, binding, entries, List.of());
            var failedSourceCut = new CoordinationCore.EvaluationEvidence(unchangedCut.snapshot(), unchangedCut.relevantTimelines(),
                    unchangedCut.prefixes(), unchangedCut.handledThrough(), unchangedCut.fences(), unchangedCut.precedingOperations(),
                    List.of(), Map.of(), List.of(sourceFailure));
            var missingSourceBasis = assertInstanceOf(CoordinationCore.NeedEvidence.class, core(processor).evaluate(
                    new CoordinationCore.WorkIntent(observerId, CoordinationCore.OperationKind.EXTERNAL_INPUT), failedSourceCut));
            assertEquals(List.of("source-execution-basis:" + sourceId.value()), missingSourceBasis.keys());
            // The original producer's configuration is known independently of this offered failure record.
            var originalSourceBasis = Map.of(sourceId, SourceExecutionBasis.identity(sourceId, noGas.environment(), noGas.executionPolicy()));
            var progress = assertInstanceOf(CoordinationCore.MetadataProgress.class, core(processor).evaluate(
                    new CoordinationCore.WorkIntent(observerId, CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    failedSourceCut.withExpectedSourceBases(originalSourceBasis)));
            assertEquals(List.of(failedSource.operationId()), progress.consumedSourceOperations());
            assertEquals(input.blueId(), progress.input().entry().blueId());
            assertEquals(java.math.BigInteger.ZERO, observerBefore.document().getProperties().get("seen").getValue(),
                    "A failed source alone creates no observer invocation, gas settlement, business epoch, or output");
            var wrongPin = new ManagedDocumentSnapshot(sourceBefore.documentId(), sourceBefore.blueId(), sourceBefore.document(),
                    true, false, true, sourceBefore.epoch() + 1L, 0L);
            var wrongCut = pairEvidence(observerBefore, wrongPin, binding, entries, List.of());
            assertThrows(IllegalArgumentException.class, () -> core(processor).evaluate(
                    new CoordinationCore.WorkIntent(observerId, CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    new CoordinationCore.EvaluationEvidence(wrongCut.snapshot(), wrongCut.relevantTimelines(), wrongCut.prefixes(),
                            wrongCut.handledThrough(), wrongCut.fences(), Map.of(), List.of(), Map.of(), List.of(sourceFailure))
                            .withExpectedSourceBases(originalSourceBasis)),
                    "Metadata-only advancement still requires the exact failed source observation basis");
            }
        }
    }

    private static CoordinationCore.PreparedGroupOperation singleGroup(CoordinationCore.EvaluationResult result) {
        var operations = assertInstanceOf(CoordinationCore.PreparedOperations.class, result);
        assertEquals(1, operations.operations().size());
        return operations.operations().get(0);
    }

    private static void assertReceiptRoundTrip(CoordinationCore.PreparedGroupOperation operation) {
        Map<String, byte[]> store = new java.util.HashMap<>();
        var limits = blue.language.processor.closure.FrozenNodeEvidenceCodec.Limits.defaults();
        var encoded = OperationReceiptCodec.encode(operation, store::put, limits);
        var receipt = OperationReceiptCodec.decode(encoded.receiptIdentity(), store::get, limits);
        assertEquals(operation.operationId(), receipt.operationId());
        assertEquals(operation.sourcePins(), receipt.sourcePins());
        assertEquals(operation.consumedSourceOperations(), receipt.consumedSourceOperations());
        var effects = OperationReceiptCodec.restoreEffects(encoded.receiptIdentity(), store::get, limits);
        assertEquals(operation.ownedOccurrenceBindings().stream().map(b -> b.bindingIdentity()).toList(),
                effects.bindings().stream().map(b -> b.bindingIdentity()).toList());
        assertEquals(operation.ownedGraphChanges().size(), effects.graphChanges().size());
        assertEquals(operation.ownedSubscriptionDeltas().stream().map(s -> s.afterSubscriptionIdentity()).toList(),
                effects.subscriptions().stream().map(s -> s.afterSubscriptionIdentity()).toList());
        assertEquals(operation.ownedCheckpointWrites().stream().map(c -> c.afterSubjectBlueId()).toList(),
                effects.checkpoints().stream().map(c -> c.afterSubjectBlueId()).toList());
        for (var state : receipt.states()) {
            var restored = OperationReceiptCodec.restoreState(encoded.receiptIdentity(), state.lineage(), true, 0L, store::get, limits);
            assertEquals(state.afterBlueId(), restored.blueId()); assertEquals(state.afterEpoch(), restored.epoch());
        }
        if (operation.sourceProgram().isPresent()) {
            var program = OperationReceiptCodec.restoreSourceProgram(encoded.receiptIdentity(), store::get, limits);
            assertEquals(operation.ownedLineages(), program.ownedDocumentIds());
        }
        assertEquals(operation.result().totalGas(), OperationReceiptCodec.restoreGas(encoded.receiptIdentity(), store::get, limits)
                .trace().stream().mapToLong(g -> g.subtotal()).sum());
    }

    private static void assertReceiptRoundTrip(CoordinationCore.PreparedOperation operation) {
        Map<String, byte[]> store = new java.util.HashMap<>();
        var limits = blue.language.processor.closure.FrozenNodeEvidenceCodec.Limits.defaults();
        var encoded = OperationReceiptCodec.encode(operation, store::put, limits);
        var receipt = OperationReceiptCodec.decode(encoded.receiptIdentity(), store::get, limits);
        assertEquals(operation.operationId(), receipt.operationId());
        assertEquals(operation.sourcePins(), receipt.sourcePins());
        assertEquals(operation.consumedSourceOperations(), receipt.consumedSourceOperations());
        var pins = OperationReceiptCodec.restoreReadPins(encoded.receiptIdentity(), store::get, limits);
        assertEquals(operation.result().readPins().stream().map(p -> p.documentId().value() + ":" + p.blueId()).toList(),
                pins.stream().map(p -> p.documentId().value() + ":" + p.blueId()).toList());
        var effects = OperationReceiptCodec.restoreEffects(encoded.receiptIdentity(), store::get, limits);
        assertEquals(operation.ownedOccurrenceBindings().stream().map(b -> b.bindingIdentity()).toList(),
                effects.bindings().stream().map(b -> b.bindingIdentity()).toList());
        assertEquals(operation.ownedGraphChanges().size(), effects.graphChanges().size());
        assertEquals(operation.ownedSubscriptionDeltas().stream().map(s -> s.afterSubscriptionIdentity()).toList(),
                effects.subscriptions().stream().map(s -> s.afterSubscriptionIdentity()).toList());
        assertEquals(operation.ownedCheckpointWrites().stream().map(c -> c.afterSubjectBlueId()).toList(),
                effects.checkpoints().stream().map(c -> c.afterSubjectBlueId()).toList());
        for (var state : receipt.states()) {
            var restored = OperationReceiptCodec.restoreState(encoded.receiptIdentity(), state.lineage(), true, 0L, store::get, limits);
            assertEquals(state.afterBlueId(), restored.blueId()); assertEquals(state.afterEpoch(), restored.epoch());
        }
        if (operation.sourceProgram().isPresent()) {
            var program = OperationReceiptCodec.restoreSourceProgram(encoded.receiptIdentity(), store::get, limits);
            assertEquals(operation.ownedLineages(), program.ownedDocumentIds());
        }
        assertEquals(operation.result().totalGas(), OperationReceiptCodec.restoreGas(encoded.receiptIdentity(), store::get, limits)
                .trace().stream().mapToLong(g -> g.subtotal()).sum());
    }

    private static ManagedDocumentSnapshot restored(CoordinationCore.LineageProjection projection) {
        var result = projection.result();
        return new ManagedDocumentSnapshot(projection.lineage(), result.afterBlueId(), result.document(),
                true, false, projection.publicRoot(), result.epoch(), result.componentGeneration());
    }

    private static CoordinationCore.EvaluationEvidence pairEvidence(ManagedDocumentSnapshot observer,
            ManagedDocumentSnapshot source, blue.language.processor.closure.ManagedOccurrenceBinding binding,
            List<CoordinationCore.TimelinePrefix> prefixes,
            List<blue.language.processor.closure.SourceObservationProgram> programs) {
        var snapshot = ClosureEvidenceFactory.affectedClosure(0L, List.of(observer, source), List.of(binding),
                List.of(ClosureEvidenceFactory.acyclicComponent(source), ClosureEvidenceFactory.acyclicComponent(observer)),
                List.of(observer.documentId(), source.documentId()));
        return new CoordinationCore.EvaluationEvidence(snapshot, prefixes.isEmpty() ? Set.of() : Set.of("timeline"),
                prefixes, Optional.empty(), List.of(new CoordinationCore.ReadFence("observer", "1")), Map.of(), programs)
                .withOperationFences(Map.of(observer.documentId(), List.of(new CoordinationCore.ReadFence("observer", "1")),
                        source.documentId(), List.of(new CoordinationCore.ReadFence("source", "1"))));
    }

    @Test
    void canonicalInitializationSurvivesFreshEvaluatorAndHostFenceChanges() {
        ExactValue authored = ExactValue.verified(new Node().name("External-state source"));
        DocumentId lineage = new DocumentId(authored.blueId());
        ManagedDocumentSnapshot before = new ManagedDocumentSnapshot(lineage, authored.blueId(),
                authored.copyNode(), false, false, true, 0L, 0L);
        CoordinationCore.WorkIntent intent = new CoordinationCore.WorkIntent(lineage,
                CoordinationCore.OperationKind.INITIALIZATION);
        CoordinationCore.PreparedOperation first;
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            first = assertInstanceOf(CoordinationCore.PreparedOperation.class,
                    core(processor).evaluate(intent, evidence(before, "worker-1", Set.of(), List.of())));
        }
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ManagedDocumentSnapshot differentHostRole = new ManagedDocumentSnapshot(lineage, authored.blueId(),
                    authored.copyNode(), false, false, false, 0L, 73L);
            var replay = assertInstanceOf(CoordinationCore.PreparedOperation.class,
                    core(processor).evaluate(intent, evidence(differentHostRole, "worker-2", Set.of(), List.of())));
            assertEquals(ProcessorStatus.SUCCESS, first.result().status());
            assertEquals(first.operationId(), replay.operationId());
            assertEquals(first.result().totalGas(), replay.result().totalGas());
            assertEquals(first.projections().get(0).afterBlueId(), replay.projections().get(0).afterBlueId());
            assertEquals(1, first.projections().size());
            assertTrue(first.sourceProgram().isPresent());
            assertEquals("worker-2", replay.fences().get(0).revision());
            assertTrue(first.projections().get(0).publicRoot());
            assertEquals(false, replay.projections().get(0).publicRoot());
        }
    }

    @Test
    void completenessMustBeStrictAndEqualTimeOrderUsesCanonicalEntryText() {
        try (var fixture = new CanonicalSourceHistoryTest.Fixture()) {
            var before = fixture.source(); var lineage = before.documentId();
            var initialized = assertInstanceOf(CoordinationCore.PreparedOperation.class, fixture.core.evaluate(
                    new CoordinationCore.WorkIntent(lineage, CoordinationCore.OperationKind.INITIALIZATION),
                    evidence(before, "0", Set.of(), List.of()))).result().resultingDocuments().get(0);
            ManagedDocumentSnapshot current = new ManagedDocumentSnapshot(lineage, initialized.afterBlueId(),
                    initialized.document(), true, false, true, initialized.epoch(), initialized.componentGeneration());
            List<CoordinationCore.TimelineInput> entries = new ArrayList<>();
            for (String text : List.of("first", "second")) {
                entries.add(CanonicalSourceHistoryTest.input(text, 1_001_001L, "not-the-source-actor"));
            }
            entries.sort(Comparator.comparing(i -> i.entry().blueId()));
            var intent = new CoordinationCore.WorkIntent(lineage, CoordinationCore.OperationKind.EXTERNAL_INPUT);
            assertInstanceOf(CoordinationCore.NeedEvidence.class, fixture.core.evaluate(intent,
                    evidence(current, "1", Set.of("timeline"), List.of())));
            assertInstanceOf(CoordinationCore.NeedEvidence.class, fixture.core.evaluate(intent,
                    evidence(current, "1", Set.of("timeline"), List.of(
                            new CoordinationCore.TimelinePrefix("timeline", 1_001_001L, entries)))));
            var progress = assertInstanceOf(CoordinationCore.MetadataProgress.class, fixture.core.evaluate(intent,
                    evidence(current, "1", Set.of("timeline"), List.of(
                            new CoordinationCore.TimelinePrefix("timeline", 1_001_002L, entries)))));
            assertEquals(entries.get(0).entry().blueId(), progress.input().entry().blueId());
            assertEquals(1_001_001L, progress.input().timestampMicros());
            assertThrows(IllegalArgumentException.class, () -> new CoordinationCore.TimelineInput("timeline",
                    1_001_002L, entries.get(0).entry(), entries.get(0).event(), List.of()));
            assertThrows(IllegalArgumentException.class, () -> new CoordinationCore.TimelineInput("other",
                    1_001_001L, entries.get(0).entry(), entries.get(0).event(), List.of()));
        }
    }

    @Test
    void unusedSameCauseSourceEvidenceCannotChangeMetadataProgress() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var qAuthored = f.source();
            var qBirth = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(qAuthored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    evidence(qAuthored, "q0", Set.of(), List.of())));
            var qReceipt = OperationReceiptCodec.encode(qBirth, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var qBefore = OperationReceiptCodec.restoreState(qReceipt.receiptIdentity(), qAuthored.documentId(), true, 0,
                    f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            f.nodes.put(qBefore.blueId(), qBefore.document());
            var rAuthored = f.authored("""
                    name: Root ignores this actor
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: another-actor}
                    """, Map.of("unused", qBefore.blueId()));
            var rBirth = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(rAuthored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    evidence(rAuthored, "r0", Set.of(), List.of())));
            var rReceipt = OperationReceiptCodec.encode(rBirth, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var root = OperationReceiptCodec.restoreState(rReceipt.receiptIdentity(), rAuthored.documentId(), true, 0,
                    f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            var entry = CanonicalSourceHistoryTest.input("same entry, unrelated producer", 15, "account");
            var prefixes = List.of(new CoordinationCore.TimelinePrefix("timeline", 20, List.of(entry)));
            var produced = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(qBefore.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    evidence(qBefore, "q1", Set.of("timeline"), prefixes))).operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, produced.result().status());
            var retained = OperationReceiptCodec.encode(produced, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var cold = OperationReceiptCodec.restoreSourceProgram(retained.receiptIdentity(), f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            var boundedCore = new CoordinationCore(f.processor, f.core.environment(),
                    ClosureEvidenceFactory.executionPolicy(1L, Map.of(), "unused-source-failure"));
            var failed = assertInstanceOf(CoordinationCore.PreparedOperations.class, boundedCore.evaluate(
                    new CoordinationCore.WorkIntent(qBefore.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    evidence(qBefore, "q-failed", Set.of("timeline"), prefixes))).operations().get(0);
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.result().status());
            var failedReceipt = OperationReceiptCodec.encode(failed, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var coldFailure = OperationReceiptCodec.restoreSourceFailure(failedReceipt.receiptIdentity(),
                    f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            var intent = new CoordinationCore.WorkIntent(root.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            var baseline = evidence(root, "r1", Set.of("timeline"), prefixes);
            var expected = assertInstanceOf(CoordinationCore.MetadataProgress.class, f.core.evaluate(intent, baseline));
            assertTrue(expected.consumedSourceOperations().isEmpty());
            for (boolean inactiveCandidate : List.of(false, true)) {
                var snapshot = baseline.snapshot();
                if (inactiveCandidate) {
                    var binding = ManagedOccurrenceBinding.derived(f.core.environment().managedBindingPolicyIdentity(), root.documentId(),
                            ScopeAddress.embedded("/unused", 1), qBefore.documentId(), qBefore.blueId(), false, null);
                    var states = List.of(root, qBefore);
                    var byId = new HashMap<DocumentId, ManagedDocumentSnapshot>(); states.forEach(state -> byId.put(state.documentId(), state));
                    var components = new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(byId.keySet(), List.of(binding))).stream()
                            .map(members -> ClosureEvidenceFactory.acyclicComponent(byId.get(members.get(0)))).toList();
                    snapshot = ClosureEvidenceFactory.affectedClosure(0, states, List.of(binding), components, List.of(root.documentId(), qBefore.documentId()));
                }
                var withUnusedProgram = new CoordinationCore.EvaluationEvidence(snapshot, baseline.relevantTimelines(), prefixes,
                        baseline.handledThrough(), baseline.fences(), baseline.precedingOperations(), List.of(cold));
                var actual = assertInstanceOf(CoordinationCore.MetadataProgress.class, f.core.evaluate(intent, withUnusedProgram));
                assertEquals(expected, actual, "Wholly unrelated or dormant candidate evidence is not consumed source work");
                var withUnusedFailure = new CoordinationCore.EvaluationEvidence(snapshot, baseline.relevantTimelines(), prefixes,
                        baseline.handledThrough(), baseline.fences(), baseline.precedingOperations(), List.of(), Map.of(), List.of(coldFailure));
                assertEquals(expected, assertInstanceOf(CoordinationCore.MetadataProgress.class, f.core.evaluate(intent, withUnusedFailure)),
                        "An unrelated failed source operation is not a dependency either");
            }
        }
    }

    @Test
    void previousTerminalFailureParticipatesInActualContractsInvocationIdentity() {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ExactValue authored = ExactValue.verified(new Node().name("Identity predecessor"));
            DocumentId id = new DocumentId(authored.blueId());
            ManagedDocumentSnapshot state = new ManagedDocumentSnapshot(id, authored.blueId(), authored.copyNode(),
                    false, false, true, 0L, 0L);
            var evidence = evidence(state, "same-host-version", Set.of(), List.of());
            var cause = ClosureEvidenceFactory.admissionCause(blue.language.processor.closure.AdmissionKind.TOP_LEVEL_ADMISSION,
                    "source", null, null, "FULL_HISTORY");
            var core = core(processor);
            var original = assertInstanceOf(CoordinationCore.PreparedOperation.class, core.evaluate(
                    new CoordinationCore.WorkIntent(id, CoordinationCore.OperationKind.INITIALIZATION), evidence)).invocation();
            var afterFailureA = ClosureEvidenceFactory.withSemanticPredecessors(original, Map.of(id, "sha256:" + "c".repeat(64)));
            var afterFailureB = ClosureEvidenceFactory.withSemanticPredecessors(original, Map.of(id, "sha256:" + "d".repeat(64)));
            assertNotEquals(afterFailureA.invocationIdentity(), afterFailureB.invocationIdentity());
            assertEquals(afterFailureA.snapshot().closureIdentity(), afterFailureB.snapshot().closureIdentity());
        }
    }

    private static ExactValue entry(String text, long micros) {
        return ExactValue.verified(new Node().type(new Node().blueId(blue.repo.coordination.TimelineEntry.blueId()))
                .properties("timeline", new Node().type(new Node().blueId(blue.repo.myos.MyOSTimeline.blueId()))
                        .properties("timelineId", new Node().value("timeline")))
                .properties("timestamp", new Node().value(java.math.BigInteger.valueOf(micros)))
                .properties("message", new Node().value(text))
                .properties("actor", new Node().type(new Node().blueId(blue.repo.myos.PrincipalActor.blueId()))
                        .properties("accountId", new Node().value("account")))
                .properties("source", new Node().value("test")));
    }

    private static CoordinationCore core(DocumentProcessor processor) {
        ClosureEnvironment environment = ClosureEvidenceFactory.environment(processor,
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                "content-lineage", "exact-binding", "test-exact-provider", "micros-entry-text",
                "local-portable-limits", GasSchedule.contracts10().portableLimits());
        ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(100_000L, Map.of(), "local-source-policy");
        return new CoordinationCore(processor, environment, policy);
    }

    private static CoordinationCore.EvaluationEvidence evidence(ManagedDocumentSnapshot document, String fence,
            Set<String> timelines, List<CoordinationCore.TimelinePrefix> prefixes) {
        var snapshot = ClosureEvidenceFactory.affectedClosure(document.componentGeneration(), List.of(document),
                List.of(), List.of(ClosureEvidenceFactory.acyclicComponent(document)),
                document.publicRoot() ? List.of(document.documentId()) : List.of());
        return new CoordinationCore.EvaluationEvidence(snapshot, timelines, prefixes, Optional.empty(),
                List.of(new CoordinationCore.ReadFence("lineage:" + document.documentId().value(), fence)), Map.of())
                .withOperationFences(Map.of(document.documentId(), List.of(new CoordinationCore.ReadFence("lineage:" + document.documentId().value(), fence))));
    }
}
