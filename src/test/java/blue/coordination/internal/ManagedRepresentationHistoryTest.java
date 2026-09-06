package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.sdk.*;
import blue.language.processor.closure.ManagedRepresentationCursor;
import blue.language.processor.closure.ManagedRepresentationTransition;
import blue.language.processor.closure.*;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.identity.DirectBlueIdCalculator;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedRepresentationHistoryTest {
    @Test
    void authenticatesTheActualIntermediateGapAndRejectsRecomputedWrongAnchors() throws Exception {
        // given
        try (BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            com.fasterxml.jackson.databind.JsonNode scenario;
            try (var stream = getClass().getResourceAsStream("/historical-representation/scenario.json")) {
                scenario = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.util.Objects.requireNonNull(stream));
            }
            assertEquals("PROPOSED_NOT_RELEASED", scenario.path("status").asText());
            String template;
            try (var stream = getClass().getResourceAsStream("/tutorial-graphs/node.template.json")) {
                template = new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
            }
            String owner = "representation-proof/alice";
            Map<String, String> sources = new LinkedHashMap<>();
            Map<String, ExactBlueValue> originals = new LinkedHashMap<>();
            Map<String, DocumentHandle> handles = new LinkedHashMap<>();
            for (String name : List.of("A", "B", "C")) {
                String source = template.replace("<NODE>", name).replace("<NAMESPACE>", "proof-gap").replace("<TIMELINE>", owner);
                sources.put(name, source); originals.put(name, blue.values().yaml(source));
            }
            TimelineHandle timeline = blue.timelines().register(owner, "alice");
            for (String name : sources.keySet()) handles.put(name, blue.documents().admit(ManagedDocument.yaml(
                    DocumentId.of(originals.get(name).blueId()), sources.get(name)).publicRoot().fromNow()));
            // when
            for (String[] edge : new String[][]{{"A", "b", "B"}, {"B", "a", "A"}, {"A", "c", "C"}}) {
                EntryHandle entry = blue.operations().on(handles.get(edge[0])).from(timeline).call("attach").through("ownerChannel")
                        .request(r -> r.exact("edge", blue.values().yaml(edge[1])).exact("source", originals.get(edge[2]))).submit();
                assertTrue(blue.processing().drainJournal(new DrainBudget(1L, 1L)).entry(entry).applied());
                for (int step = 0; step < 32 && originals.values().stream().anyMatch(value -> !blue.advanced()
                        .auditManagedDocumentReadiness(DocumentId.of(value.blueId())).orElseThrow().ready()); step++) {
                    var work = blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow();
                    assertEquals(1, blue.processing().drainManagedEpochApplication(work.workIdentity()).managedEpochApplications().size());
                }
                for (var original : originals.values()) assertTrue(blue.advanced()
                        .auditManagedDocumentReadiness(DocumentId.of(original.blueId())).orElseThrow().ready());
                if (edge[0].equals("B")) {
                    var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
                    var source = DocumentId.of(originals.get("A").blueId());
                    var tail = new ManagedRepresentationHistory(engine.documents()).at(source,
                            engine.documents().find(source).orElseThrow().epoch());
                    assertNull(tail.nextRevisionReceiptIdentity());
                    assertEquals(scenario.path("terminalTail").path("sourceEpoch").asLong(), tail.epoch());
                    assertEquals(scenario.path("terminalTail").path("representationSteps").asInt(), tail.transitions().size());
                    verifyTerminalTailProcessing(blue, engine, tail, template, owner);
                }
            }
            DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            ManagedRepresentationHistory history = new ManagedRepresentationHistory(engine.documents());
            var source = DocumentId.of(originals.get("A").blueId());
            var numbered = blue.advanced().auditManagedEpochs(source);
            long gapEpoch = -1L;
            for (int index = 1; index < numbered.size(); index++) {
                if (!numbered.get(index - 1).afterBlueId().equals(numbered.get(index).beforeBlueId().orElseThrow())) {
                    gapEpoch = index - 1L; break;
                }
            }
            // then
            assertTrue(gapEpoch >= 0L, "the test must retain the real intermediate exact predecessor gap");
            var chain = history.at(source, gapEpoch);
            assertEquals(scenario.path("intermediate").path("sourceEpoch").asLong(), gapEpoch);
            assertEquals(scenario.path("intermediate").path("representationSteps").asInt(), chain.transitions().size());
            assertNotNull(chain.nextRevisionReceiptIdentity());
            ManagedRepresentationCursor cursor = null;
            String current = chain.anchor().afterBlueId();
            int applied = 0;
            for (var expected : chain.transitions()) {
                var next = chain.next(cursor, current).orElseThrow();
                assertEquals(expected.positionIdentity(), next.positionIdentity());
                history.verifySupplied(next);
                System.out.println("AUTHENTICATED_REPRESENTATION epoch=" + gapEpoch + " before=" + current
                        + " after=" + next.transitionReceipt().afterBlueId() + " position=" + next.positionIdentity());
                for (String mutation : List.of("anchor", "predecessor")) {
                    String wrong = "sha256:" + "f".repeat(64);
                    var forged = new ManagedRepresentationTransition(next.documentId(), next.epoch(),
                            mutation.equals("anchor") ? wrong : next.anchorReceiptIdentity(),
                            mutation.equals("predecessor") ? wrong : next.predecessorPositionIdentity(),
                            next.originalInput(), next.originalResult(), next.transitionReceipt().transitionReceiptIdentity());
                    assertThrows(IllegalArgumentException.class, () -> history.verifySupplied(forged), mutation);
                }
                assertThrows(IllegalArgumentException.class, () -> new ManagedRepresentationTransition(
                        next.documentId(), next.epoch() + 1L, next.anchorReceiptIdentity(), next.predecessorPositionIdentity(),
                        next.originalInput(), next.originalResult(), next.transitionReceipt().transitionReceiptIdentity()));
                assertThrows(IllegalArgumentException.class, () -> new ManagedRepresentationTransition(
                        new blue.language.processor.closure.DocumentId("uncommitted-source"), next.epoch(),
                        next.anchorReceiptIdentity(), next.predecessorPositionIdentity(), next.originalInput(),
                        next.originalResult(), next.transitionReceipt().transitionReceiptIdentity()),
                        "complete evidence for A cannot authorize another source lineage");
                assertThrows(IllegalArgumentException.class, () -> new ManagedRepresentationTransition(
                        next.documentId(), next.epoch(), next.anchorReceiptIdentity(), next.predecessorPositionIdentity(),
                        next.originalInput(), next.originalResult(), "sha256:" + "f".repeat(64)),
                        "a missing original transition cannot acquire authority through a new position hash");
                current = next.transitionReceipt().afterBlueId();
                cursor = new ManagedRepresentationCursor(next.anchorReceiptIdentity(), next.positionIdentity(),
                        chain.targetPositionIdentity(), chain.nextRevisionReceiptIdentity());
                applied++;
            }
            assertEquals(chain.transitions().size(), applied);
            assertTrue(chain.next(cursor, current).isEmpty());
            assertEquals(numbered.get(Math.toIntExact(gapEpoch + 1L)).beforeBlueId().orElseThrow(), current);
            String actualCurrent = current;
            assertThrows(IllegalArgumentException.class, () -> chain.next(new ManagedRepresentationCursor(
                    chain.anchor().receiptIdentity(), "sha256:" + "f".repeat(64), chain.targetPositionIdentity(),
                    chain.nextRevisionReceiptIdentity()), actualCurrent));
            verifyIndependentConsumerProcessing(blue, engine, chain, template, owner);
            assertEquals(numbered.stream().map(r -> r.receiptIdentity()).toList(),
                    blue.advanced().auditManagedEpochs(source).stream().map(r -> r.receiptIdentity()).toList());
            var positions = chain.transitions().stream().map(ManagedRepresentationTransition::positionIdentity).toList();
            var beforeRestart = engine.documents().publicationSnapshot().documentHeads();
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            var restarted = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            var retainedChain = new ManagedRepresentationHistory(restarted.documents()).at(source, gapEpoch);
            assertEquals(positions, retainedChain.transitions().stream().map(ManagedRepresentationTransition::positionIdentity).toList());
            assertEquals(beforeRestart, restarted.documents().publicationSnapshot().documentHeads());
            for (var transition : retainedChain.transitions()) new ManagedRepresentationHistory(restarted.documents()).verifySupplied(transition);
            System.out.println("SDK_STORE_RESTART_PRESERVES_AUTHENTICATED_REPRESENTATION_CHAIN");
        }
    }
    @Test
    void reconnectProcessorProofAgreesWithPublishedSdkTraversal() throws Exception {
        // given
        try (BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            String template;
            try (var stream = getClass().getResourceAsStream("/historical-representation/reconnect.template.json")) {
                template = new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
            }
            String owner = "review/representation-gap/alice";
            Map<String, String> sources = new LinkedHashMap<>();
            Map<String, ExactBlueValue> originals = new LinkedHashMap<>();
            Map<String, DocumentHandle> handles = new LinkedHashMap<>();
            for (String name : List.of("A", "B")) {
                String source = template.replace("<NODE>", name).replace("<NAMESPACE>", "review-gap").replace("<TIMELINE>", owner);
                sources.put(name, source);
                originals.put(name, blue.values().yaml(source));
            }
            assertEquals("EvDZ5SpjRrcYNbikhKvtPctuwoFXCMZvK9kpfc52owWy", originals.get("A").blueId());
            assertEquals("HbhBw4c8AU5fUSeEgqMVm2eZoH1j1FSSrMUr7rFNn61z", originals.get("B").blueId());
            var timeline = blue.timelines().register(owner, "alice");
            for (String name : sources.keySet()) handles.put(name, blue.documents().admit(ManagedDocument.yaml(
                    DocumentId.of(originals.get(name).blueId()), sources.get(name)).publicRoot().fromNow()));
            for (String[] edge : new String[][]{{"A", "b", "B"}, {"B", "a", "A"}}) {
                var entry = blue.operations().on(handles.get(edge[0])).from(timeline).call("attach").through("ownerChannel")
                        .request(r -> r.exact("edge", blue.values().yaml(edge[1])).exact("source", originals.get(edge[2]))).submit();
                assertTrue(blue.processing().drainJournal(new DrainBudget(1L, 1L)).entry(entry).applied());
                for (int step = 0; step < 32 && !pairReady(blue, originals); step++) {
                    var work = blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow();
                    assertEquals(1, blue.processing().drainManagedEpochApplication(work.workIdentity()).managedEpochApplications().size());
                }
                assertTrue(pairReady(blue, originals));
            }
            assertTrue(blue.operations().on(handles.get("B")).from(timeline).call("emit").through("ownerChannel")
                    .requestYaml("{to: A, next: stop}").execute().applied());
            assertTrue(blue.operations().on(handles.get("A")).from(timeline).call("detach").through("ownerChannel")
                    .requestYaml("{edge: b}").execute().applied());
            assertTrue(blue.operations().on(handles.get("B")).from(timeline).call("emit").through("ownerChannel")
                    .requestYaml("{to: A, next: stop}").execute().applied());
            // when
            var entry = blue.operations().on(handles.get("A")).from(timeline).call("attach").through("ownerChannel")
                    .request(r -> r.exact("edge", blue.values().yaml("b")).exact("source", originals.get("B"))).submit();
            assertTrue(blue.processing().drainJournal(new DrainBudget(1L, 1L)).entry(entry).applied());
            boolean processorProofRan = false;
            for (int step = 0; step < 32 && !pairReady(blue, originals); step++) {
                var work = blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow();
                if (!processorProofRan && work.isRepresentationApplication()) {
                    assertFalse(pairReady(blue, originals));
                    verifyBlockedPairProcessorTraversal(blue);
                    assertFalse(pairReady(blue, originals), "detached processor proof cannot publish SDK readiness");
                    processorProofRan = true;
                }
                assertEquals(1, blue.processing().drainManagedEpochApplication(work.workIdentity()).managedEpochApplications().size());
            }
            assertTrue(processorProofRan, "the real reconnect must exercise the independently verified position chain");
            assertTrue(pairReady(blue, originals));
        }
    }

    private static boolean pairReady(BlueCoordination blue, Map<String, ExactBlueValue> originals) {
        return originals.values().stream().allMatch(value -> blue.advanced()
                .auditManagedDocumentReadiness(DocumentId.of(value.blueId())).orElseThrow().ready());
    }

    private static void verifyBlockedPairProcessorTraversal(BlueCoordination blue) {
        var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        var publication = engine.documents().publicationSnapshot();
        var pending = publication.occurrenceInventory().rows().stream()
                .filter(row -> !row.active() && row.pendingHistoricalEpoch() != null).findFirst().orElseThrow();
        var sourceId = DocumentId.of(pending.targetDocumentId().value());
        var history = new ManagedRepresentationHistory(engine.documents());
        var chainAtSeven = history.at(sourceId, pending.pendingHistoricalEpoch());
        assertEquals(7L, chainAtSeven.epoch());
        assertEquals(6, chainAtSeven.transitions().size());
        assertEquals(1, history.at(sourceId, 8L).transitions().size());
        var original = chainAtSeven.transitions().get(0).originalInput();
        var retainedReceipts = blue.advanced().auditManagedEpochs(sourceId).stream().map(r -> r.receiptIdentity()).toList();
        var members = new ArrayList<ManagedDocumentSnapshot>();
        for (var component : publication.componentStates()) for (var memberId : component.orderedMemberDocumentIds()) {
            var session = engine.documents().find(DocumentId.of(memberId.value())).orElseThrow();
            members.add(new ManagedDocumentSnapshot(memberId, session.currentRepresentation().blueId(),
                    session.currentRepresentation().copyNode(), true, false, true, session.epoch(), component.componentGeneration()));
        }
        var roots = members.stream().map(ManagedDocumentSnapshot::documentId).sorted().toList();
        long generation = publication.documentHeads().keySet().stream()
                .mapToLong(id -> publication.graphGenerations().require(id)).max().orElseThrow();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(generation, members,
                publication.occurrenceInventory().rows(), publication.componentStates(), roots);
        String occurrence = pending.occurrenceIdentity();
        int steps = 0;
        int representationSteps = 0;
        long totalGas = 0;
        try (BlueRuntime runtime = BlueRuntime.create(engine.objects());
                BlueClosureContracts contracts = new BlueClosureContracts(runtime.documentProcessor())) {
            while (steps < 32) {
                var row = snapshot.occurrences().stream().filter(r -> r.occurrenceIdentity().equals(occurrence)).findFirst().orElseThrow();
                if (row.active()) break;
                var chain = history.atCaptured(sourceId, row.pendingHistoricalEpoch(), row.pendingRepresentationCursor());
                var transition = chain.next(row.pendingRepresentationCursor(), row.expectedTargetBlueId());
                ProcessingCause cause;
                if (transition.isPresent()) {
                    var next = transition.orElseThrow();
                    cause = new ManagedRepresentationCause(occurrence, next, chain.targetPositionIdentity(),
                            chain.nextRevisionReceiptIdentity(), engine.objects().cyclicSetProofFor(next.transitionReceipt().afterBlueId()).proof().orElse(null));
                    history.verifyCause((ManagedRepresentationCause) cause, row);
                    representationSteps++;
                } else {
                    var next = engine.documents().managedEpochEvidence(sourceId, row.pendingHistoricalEpoch() + 1L);
                    cause = ClosureEvidenceFactory.managedRevisionCause(occurrence, row.pendingHistoricalEpoch(),
                            row.pendingHistoricalEpoch() + 1L, next.receipt().afterDocument().copyNode(), next.transitionReceipt(),
                            engine.objects().cyclicSetProofFor(next.receipt().afterBlueId()).proof().orElse(null));
                }
                var input = ClosureEvidenceFactory.processClosure(snapshot, cause, List.of(), original.executionPolicy(), original.environment());
                var rollback = contracts.processClosure(ClosureEvidenceFactory.processClosure(snapshot, cause, List.of(),
                        ClosureEvidenceFactory.executionPolicy(1L, original.executionPolicy().localLimits(), "reconnect-rollback"), original.environment()));
                assertTrue(rollback.isComplete());
                assertFalse(rollback.processResult().commits());
                assertEquals(snapshot.closureIdentity(), rollback.processResult().outputClosureIdentity());
                var attempt = contracts.processClosure(input);
                assertTrue(attempt.isComplete());
                var result = attempt.processResult();
                assertTrue(result.commits(), "core reconnect step rejected: " + result.diagnostic());
                assertTrue(result.publicEvents().isEmpty(), "traversal must not duplicate already consumed source events");
                assertTrue(result.totalGas() > 0L);
                totalGas += result.totalGas();
                steps++;
                assertTrue(result.resultingDocuments().stream().allMatch(r -> r.epoch() >= publication
                        .requireHead(DocumentId.of(r.documentId().value())).epoch()), "no authoritative member may rewind");
                snapshot = resultingSnapshot(result, roots);
            }
        }
        assertEquals(9, steps, "seven positional steps and two unchanged numbered revisions finish the actual blocked closure");
        assertEquals(7, representationSteps);
        assertTrue(snapshot.occurrences().stream().allMatch(ManagedOccurrenceBinding::active));
        assertEquals(publication.documentHeads(), engine.documents().publicationSnapshot().documentHeads());
        assertEquals(retainedReceipts, blue.advanced().auditManagedEpochs(sourceId).stream().map(r -> r.receiptIdentity()).toList());
        System.out.println("CORE_PAIR_POSITION_TRAVERSAL_COMPLETE steps=" + steps + " gas=" + totalGas + " SDK_PUBLICATION_NOT_IMPLEMENTED");
    }

    /** Core conformance fixture; it deliberately does not claim SDK publication or restart coverage. */
    private void verifyIndependentConsumerProcessing(BlueCoordination blue, DefaultCoordinationEngine engine,
            ManagedRepresentationHistory.Chain chain, String template, String owner) {
        // given: an independently initialized consumer plus the actual current authoritative source component.
        String text = template.replace("<NODE>", "D").replace("<NAMESPACE>", "proof-step").replace("<TIMELINE>", owner);
        text = text.replace("\"ownerChannel\": {", """
                "referenceUpdates": {"type": "Document Update Channel", "path": "/peers/a"},
                "countReferenceUpdates": {
                  "type": "Coordination/Sequential Workflow", "channel": "referenceUpdates",
                  "steps": [{"type": "Coordination/Compute", "do": [
                    {"$appendChange": {"op": "replace", "path": "/observed", "val": {"$add": [{"$document": "/observed"}, 1]}}},
                    {"$return": true}
                  ]}]
                },
                "ownerChannel": {
                """);
        var initial = blue.values().yaml(text);
        DocumentId consumer = DocumentId.of(initial.blueId());
        blue.documents().admit(ManagedDocument.yaml(consumer, text).publicRoot().fromNow());
        var publication = engine.documents().publicationSnapshot();
        var original = chain.transitions().get(0).originalInput();
        var consumerId = ContractsClosureAdapter.closureId(consumer);
        var sourceId = ContractsClosureAdapter.closureId(chain.documentId());
        var binding = ManagedOccurrenceBinding.derived(original.environment().managedBindingPolicyIdentity(),
                consumerId, ScopeAddress.embedded("/peers/a", 1L), sourceId,
                chain.anchor().afterBlueId(), false, chain.epoch());
        var bindings = new ArrayList<>(publication.occurrenceInventory().rows());
        bindings.add(binding);
        var members = new ArrayList<ManagedDocumentSnapshot>();
        var components = new ArrayList<ComponentSnapshot>();
        for (var component : publication.componentStates()) {
            for (var memberId : component.orderedMemberDocumentIds()) {
                var session = engine.documents().find(DocumentId.of(memberId.value())).orElseThrow();
                Node body = session.currentRepresentation().copyNode();
                if (memberId.equals(consumerId)) NodePathEditor.put(body, "/peers/a", new Node().blueId(chain.anchor().afterBlueId()));
                String exact = memberId.equals(consumerId) ? DirectBlueIdCalculator.calculateBlueId(body)
                        : session.currentRepresentation().blueId();
                var snapshot = new ManagedDocumentSnapshot(memberId, exact, body, true, false, true,
                        session.epoch(), component.componentGeneration());
                members.add(snapshot);
                if (memberId.equals(consumerId)) components.add(ClosureEvidenceFactory.acyclicComponent(snapshot));
            }
            if (!component.orderedMemberDocumentIds().contains(consumerId)) components.add(component);
        }
        var roots = members.stream().map(ManagedDocumentSnapshot::documentId).sorted().toList();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(
                original.snapshot().graphGeneration() + 1L, members, bindings, components, roots);
        var authoritative = snapshot.managedDocument(sourceId);
        assertTrue(authoritative.epoch() > chain.epoch(), "the current source must be ahead of this intermediate step");
        long totalGas = 0L;
        long expectedConsumerUpdates = 0L;
        try (BlueRuntime runtime = BlueRuntime.create(engine.objects());
                BlueClosureContracts contracts = new BlueClosureContracts(runtime.documentProcessor())) {
            // when: process each authenticated representation independently at the fixed historical epoch.
            for (var transition : chain.transitions()) {
                var cyclic = engine.objects().cyclicSetProofFor(transition.transitionReceipt().afterBlueId()).proof().orElse(null);
                var cause = new ManagedRepresentationCause(binding.occurrenceIdentity(), transition,
                        chain.targetPositionIdentity(), chain.nextRevisionReceiptIdentity(), cyclic);
                var pending = snapshot.occurrences().stream().filter(r -> r.occurrenceIdentity().equals(binding.occurrenceIdentity())).findFirst().orElseThrow();
                var history = new ManagedRepresentationHistory(engine.documents());
                history.verifyCause(cause, pending);
                assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceFactory.managedRevisionCause(
                        binding.occurrenceIdentity(), chain.epoch(), chain.epoch(), transition.afterDocument(),
                        transition.transitionReceipt(), cyclic), "ordinary revision causes retain their +1-epoch rule");
                if (cyclic != null) {
                    assertThrows(IllegalArgumentException.class, () -> new ManagedRepresentationCause(
                            binding.occurrenceIdentity(), transition, chain.targetPositionIdentity(),
                            chain.nextRevisionReceiptIdentity(), null), "a cyclic successor requires complete cyclic evidence");
                }
                int nextIndex = chain.transitions().indexOf(transition) + 1;
                if (nextIndex < chain.transitions().size()) {
                    var skipped = chain.transitions().get(nextIndex);
                    var skippedProof = engine.objects().cyclicSetProofFor(skipped.transitionReceipt().afterBlueId()).proof().orElse(null);
                    var skippedCause = new ManagedRepresentationCause(binding.occurrenceIdentity(), skipped,
                            chain.targetPositionIdentity(), chain.nextRevisionReceiptIdentity(), skippedProof);
                    assertThrows(IllegalArgumentException.class, () -> history.verifyCause(skippedCause, pending),
                            "a later authentic transition cannot skip the exact next committed position");
                }
                for (String mutation : List.of("goal", "next-receipt")) {
                    String wrong = "sha256:" + "f".repeat(64);
                    var forgedCause = new ManagedRepresentationCause(binding.occurrenceIdentity(), transition,
                            mutation.equals("goal") ? wrong : chain.targetPositionIdentity(),
                            mutation.equals("next-receipt") ? wrong : chain.nextRevisionReceiptIdentity(), cyclic);
                    assertThrows(IllegalArgumentException.class, () -> history.verifyCause(forgedCause, pending), mutation);
                }
                var input = ClosureEvidenceFactory.processClosure(snapshot, cause, List.of(),
                        original.executionPolicy(), original.environment());
                var exhaustedInput = ClosureEvidenceFactory.processClosure(snapshot, cause, List.of(),
                        ClosureEvidenceFactory.executionPolicy(1L, original.executionPolicy().localLimits(),
                                "representation-rollback"), original.environment());
                var exhausted = contracts.processClosure(exhaustedInput);
                assertTrue(exhausted.isComplete());
                assertFalse(exhausted.processResult().commits(), "one gas unit cannot publish representation work");
                assertEquals(input.snapshot().closureIdentity(), exhausted.processResult().outputClosureIdentity(),
                        "gas rollback preserves the complete input closure and historical position");
                var attempt = contracts.processClosure(input);
                assertTrue(attempt.isComplete(), "representation step suspended: " + attempt.resourceDemands());
                var result = attempt.processResult();
                assertTrue(result.commits(), "representation step failed: " + result.status() + " " + result.diagnostic());
                var repeat = contracts.processClosure(input);
                assertTrue(repeat.isComplete());
                assertEquals(result.outputClosureIdentity(), repeat.processResult().outputClosureIdentity());
                assertEquals(result.totalGas(), repeat.processResult().totalGas(), "same exact invocation has deterministic actual gas");
                totalGas += result.totalGas();
                // then: the cursor moves exactly one position, the epoch stays put and the current source is preserved.
                var current = result.occurrenceBindings().stream().filter(r -> r.occurrenceIdentity().equals(binding.occurrenceIdentity())).findFirst().orElseThrow();
                assertThrows(IllegalArgumentException.class, () -> history.verifyCause(cause, current),
                        "a completed representation transition cannot be submitted again against its successor cursor");
                assertFalse(current.active());
                assertEquals(chain.epoch(), current.pendingHistoricalEpoch());
                assertEquals(transition.positionIdentity(), current.pendingRepresentationCursor().positionIdentity());
                assertEquals(transition.transitionReceipt().afterBlueId(), current.expectedTargetBlueId());
                var source = result.resultingDocuments().stream().filter(r -> r.documentId().equals(sourceId)).findFirst().orElseThrow();
                assertEquals(authoritative.epoch(), source.epoch());
                assertEquals(authoritative.blueId(), source.afterBlueId());
                var consumerResult = result.resultingDocuments().stream().filter(r -> r.documentId().equals(consumerId)).findFirst().orElseThrow();
                assertEquals(++expectedConsumerUpdates, ((Number) NodePathEditor.getOrNull(consumerResult.document(), "/observed").getValue()).longValue(),
                        "each reference update runs the real consumer Document Update reaction exactly once");
                assertTrue(result.publicEvents().isEmpty(), "a representation bridge must not replay source events");
                assertTrue(result.totalGas() > 0L, "actual consumer work must be metered");
                System.out.println("APPLIED_REPRESENTATION epoch=" + chain.epoch() + " position=" + transition.positionIdentity() + " gas=" + result.totalGas());
                snapshot = resultingSnapshot(result, roots);
            }
            var receipt = engine.documents().managedEpochEvidence(chain.documentId(), chain.epoch() + 1L);
            var nextCause = ClosureEvidenceFactory.managedRevisionCause(binding.occurrenceIdentity(), chain.epoch(),
                    chain.epoch() + 1L, receipt.receipt().afterDocument().copyNode(), receipt.transitionReceipt(),
                    engine.objects().cyclicSetProofFor(receipt.receipt().afterBlueId()).proof().orElse(null));
            var input = ClosureEvidenceFactory.processClosure(snapshot, nextCause, List.of(),
                    original.executionPolicy(), original.environment());
            var attempt = contracts.processClosure(input);
            assertTrue(attempt.isComplete());
            assertTrue(attempt.processResult().commits(), "ordinary next revision failed: " + attempt.processResult().diagnostic());
            var current = attempt.processResult().occurrenceBindings().stream().filter(r -> r.occurrenceIdentity().equals(binding.occurrenceIdentity())).findFirst().orElseThrow();
            assertNull(current.pendingRepresentationCursor(), "ordinary +1 revision clears the completed representation cursor");
            assertTrue(totalGas > 0);
            assertEquals(publication.documentHeads(), engine.documents().publicationSnapshot().documentHeads(),
                    "core prototype calls do not publish into the SDK store");
        }
    }

    private void verifyTerminalTailProcessing(BlueCoordination blue, DefaultCoordinationEngine engine,
            ManagedRepresentationHistory.Chain chain, String template, String owner) {
        // given: an independently initialized consumer plus the actual current authoritative source component.
        String text = template.replace("<NODE>", "T").replace("<NAMESPACE>", "proof-tail").replace("<TIMELINE>", owner);
        text = text.replace("\"ownerChannel\": {", """
                "referenceUpdates": {"type": "Document Update Channel", "path": "/peers/a"},
                "countReferenceUpdates": {
                  "type": "Coordination/Sequential Workflow", "channel": "referenceUpdates",
                  "steps": [{"type": "Coordination/Compute", "do": [
                    {"$appendChange": {"op": "replace", "path": "/observed", "val": {"$add": [{"$document": "/observed"}, 1]}}},
                    {"$return": true}
                  ]}]
                },
                "ownerChannel": {
                """);
        var initial = blue.values().yaml(text);
        DocumentId consumer = DocumentId.of(initial.blueId());
        blue.documents().admit(ManagedDocument.yaml(consumer, text).publicRoot().fromNow());
        var publication = engine.documents().publicationSnapshot();
        var original = chain.transitions().get(0).originalInput();
        var consumerId = ContractsClosureAdapter.closureId(consumer);
        var sourceId = ContractsClosureAdapter.closureId(chain.documentId());
        var binding = ManagedOccurrenceBinding.derived(original.environment().managedBindingPolicyIdentity(),
                consumerId, ScopeAddress.embedded("/peers/a", 1L), sourceId,
                chain.anchor().afterBlueId(), false, chain.epoch());
        var bindings = new ArrayList<>(publication.occurrenceInventory().rows());
        bindings.add(binding);
        var members = new ArrayList<ManagedDocumentSnapshot>();
        var components = new ArrayList<ComponentSnapshot>();
        for (var component : publication.componentStates()) {
            for (var memberId : component.orderedMemberDocumentIds()) {
                var session = engine.documents().find(DocumentId.of(memberId.value())).orElseThrow();
                Node body = session.currentRepresentation().copyNode();
                if (memberId.equals(consumerId)) NodePathEditor.put(body, "/peers/a", new Node().blueId(chain.anchor().afterBlueId()));
                String exact = memberId.equals(consumerId) ? DirectBlueIdCalculator.calculateBlueId(body)
                        : session.currentRepresentation().blueId();
                var snapshot = new ManagedDocumentSnapshot(memberId, exact, body, true, false, true,
                        session.epoch(), component.componentGeneration());
                members.add(snapshot);
                if (memberId.equals(consumerId)) components.add(ClosureEvidenceFactory.acyclicComponent(snapshot));
            }
            if (!component.orderedMemberDocumentIds().contains(consumerId)) components.add(component);
        }
        var roots = members.stream().map(ManagedDocumentSnapshot::documentId).sorted().toList();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(
                original.snapshot().graphGeneration() + 1L, members, bindings, components, roots);
        var authoritative = snapshot.managedDocument(sourceId);
        assertEquals(chain.epoch(), authoritative.epoch(), "this tail ends at the current numbered epoch");
        long totalGas = 0L;
        long expectedConsumerUpdates = 0L;
        try (BlueRuntime runtime = BlueRuntime.create(engine.objects());
                BlueClosureContracts contracts = new BlueClosureContracts(runtime.documentProcessor())) {
            // when: process each authenticated representation independently at the fixed historical epoch.
            for (var transition : chain.transitions()) {
                var cyclic = engine.objects().cyclicSetProofFor(transition.transitionReceipt().afterBlueId()).proof().orElse(null);
                var cause = new ManagedRepresentationCause(binding.occurrenceIdentity(), transition,
                        chain.targetPositionIdentity(), chain.nextRevisionReceiptIdentity(), cyclic);
                var pending = snapshot.occurrences().stream().filter(r -> r.occurrenceIdentity().equals(binding.occurrenceIdentity())).findFirst().orElseThrow();
                var history = new ManagedRepresentationHistory(engine.documents());
                history.verifyCause(cause, pending);
                for (String mutation : List.of("goal", "next-receipt")) {
                    String wrong = "sha256:" + "f".repeat(64);
                    var forgedCause = new ManagedRepresentationCause(binding.occurrenceIdentity(), transition,
                            mutation.equals("goal") ? wrong : chain.targetPositionIdentity(),
                            mutation.equals("next-receipt") ? wrong : chain.nextRevisionReceiptIdentity(), cyclic);
                    assertThrows(IllegalArgumentException.class, () -> history.verifyCause(forgedCause, pending), mutation);
                }
                var input = ClosureEvidenceFactory.processClosure(snapshot, cause, List.of(),
                        original.executionPolicy(), original.environment());
                var exhaustedInput = ClosureEvidenceFactory.processClosure(snapshot, cause, List.of(),
                        ClosureEvidenceFactory.executionPolicy(1L, original.executionPolicy().localLimits(),
                                "representation-rollback"), original.environment());
                var exhausted = contracts.processClosure(exhaustedInput);
                assertTrue(exhausted.isComplete());
                assertFalse(exhausted.processResult().commits(), "one gas unit cannot publish representation work");
                assertEquals(input.snapshot().closureIdentity(), exhausted.processResult().outputClosureIdentity(),
                        "gas rollback preserves the complete input closure and historical position");
                var attempt = contracts.processClosure(input);
                assertTrue(attempt.isComplete(), "representation step suspended: " + attempt.resourceDemands());
                var result = attempt.processResult();
                assertTrue(result.commits(), "representation step failed: " + result.status() + " " + result.diagnostic());
                var repeat = contracts.processClosure(input);
                assertTrue(repeat.isComplete());
                assertEquals(result.outputClosureIdentity(), repeat.processResult().outputClosureIdentity());
                assertEquals(result.totalGas(), repeat.processResult().totalGas(), "same exact invocation has deterministic actual gas");
                totalGas += result.totalGas();
                // then: the cursor moves exactly one position, the epoch stays put and the current source is preserved.
                var current = result.occurrenceBindings().stream().filter(r -> r.occurrenceIdentity().equals(binding.occurrenceIdentity())).findFirst().orElseThrow();
                boolean terminal = transition.positionIdentity().equals(chain.targetPositionIdentity());
                assertEquals(terminal, current.active(), "only the captured terminal position activates the occurrence");
                assertEquals(terminal ? null : Long.valueOf(chain.epoch()), current.pendingHistoricalEpoch());
                assertEquals(terminal ? null : new ManagedRepresentationCursor(transition.anchorReceiptIdentity(),
                        transition.positionIdentity(), chain.targetPositionIdentity(), null), current.pendingRepresentationCursor());
                assertEquals(terminal ? authoritative.blueId() : transition.transitionReceipt().afterBlueId(), current.expectedTargetBlueId());
                var source = result.resultingDocuments().stream().filter(r -> r.documentId().equals(sourceId)).findFirst().orElseThrow();
                assertEquals(authoritative.epoch(), source.epoch());
                assertEquals(authoritative.blueId(), source.afterBlueId());
                var consumerResult = result.resultingDocuments().stream().filter(r -> r.documentId().equals(consumerId)).findFirst().orElseThrow();
                assertEquals(++expectedConsumerUpdates, ((Number) NodePathEditor.getOrNull(consumerResult.document(), "/observed").getValue()).longValue(),
                        "each reference update runs the real consumer Document Update reaction exactly once");
                assertTrue(result.publicEvents().isEmpty(), "a representation bridge must not replay source events");
                assertTrue(result.totalGas() > 0L, "actual consumer work must be metered");
                System.out.println("APPLIED_TERMINAL_REPRESENTATION epoch=" + chain.epoch() + " position=" + transition.positionIdentity() + " gas=" + result.totalGas());
                snapshot = resultingSnapshot(result, roots);
            }
            var active = snapshot.occurrences().stream().filter(r -> r.occurrenceIdentity().equals(binding.occurrenceIdentity())).findFirst().orElseThrow();
            assertTrue(active.active());
            assertNull(active.pendingHistoricalEpoch());
            assertNull(active.pendingRepresentationCursor());
            assertTrue(totalGas > 0);
            assertEquals(publication.documentHeads(), engine.documents().publicationSnapshot().documentHeads(),
                    "core prototype calls do not publish into the SDK store");
        }
    }

    private static AffectedClosureSnapshot resultingSnapshot(ClosureProcessResult result,
            List<blue.language.processor.closure.DocumentId> roots) {
        var members = result.resultingDocuments().stream().map(r -> new ManagedDocumentSnapshot(r.documentId(),
                r.afterBlueId(), r.document(), r.initialized(), r.terminated(), r.publicRoot(), r.epoch(), r.componentGeneration())).toList();
        return ClosureEvidenceFactory.affectedClosure(result.graphGeneration(), members,
                result.occurrenceBindings(), result.resultingComponents(), roots);
    }

}
