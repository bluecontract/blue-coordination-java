package blue.coordination.internal;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ProcessingSelection;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DrainBudget;
import blue.coordination.sdk.EntryDisposition;
import blue.language.model.NodeWireForm;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real two-root history: related LIVE work cannot overtake an exact terminal cycle join. */
final class RootedTerminalOwnerFenceTest {
    @Test void terminalJoinBlocksRelatedObserverLiveBeforeItChangesBorrowedSource() throws Exception {
        // given
        var rootLocal = run(false);
        // when
        var slicedGlobal = run(true);
        // then
        assertEquals(rootLocal, slicedGlobal, "Sliced global execution preserves the complete root-local history/gas/event evidence");
    }

    @Test void rootLocalTerminalJoinWithoutInterleavedObserverLiveCompletes() throws Exception {
        // given
        boolean global = false;
        // when
        org.junit.jupiter.api.function.Executable scenario = () -> run(global);
        // then
        assertDoesNotThrow(scenario);
    }

    private List<Object> run(boolean global) throws Exception {
        Map<String, String> exact = new LinkedHashMap<>();
        try (var blue = BlueCoordination.builder().contentDerivedDocumentIds()
                .exactNodeProvider(id -> Optional.ofNullable(exact.get(id))).build()) {
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            String timelineId = "restart/retained-managed-epoch/claimed/alice";
            var timeline = blue.timelines().register(timelineId, "alice");
            String template;
            try (var stream = getClass().getResourceAsStream("/rooted/node-graph.template.json")) {
                template = new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8)
                        .replace("\"owner\"", "\"ownerChannel\"");
            }
            Map<String, DocumentHandle> handles = new LinkedHashMap<>();
            Map<String, String> originals = new LinkedHashMap<>();
            for (String name : List.of("A", "B")) {
                String source = template.replace("<NODE>", name).replace("<NAMESPACE>", "representation-restart")
                        .replace("<TIMELINE>", timelineId);
                var authored = blue.values().yaml(source);
                exact.put(authored.blueId(), authored.json());
                originals.put(name, authored.blueId());
                handles.put(name, blue.documents().admitStaticProcessEmbedded(source, ActivationPolicy.fromNow()).document("root"));
            }
            boolean testedJoin = false;
            for (String[] edge : new String[][]{{"A", "b", "B"}, {"B", "a", "A"}}) {
                var entry = blue.operations().on(handles.get(edge[0])).from(timeline).call("attach").through("ownerChannel")
                        .requestYaml("edge: " + edge[1] + "\nsource: {blueId: " + originals.get(edge[2]) + "}").submit();
                assertEquals(EntryDisposition.APPLIED, blue.advanced().drainJournalThrough(entry, new DrainBudget(1, 1)).entry(entry).disposition());
                if (!global) {
                    int local = 0;
                    while (true) {
                        assertTrue(local++ < 16, "Canonical root-local join must terminate");
                        var result = blue.processing().processNext(handles.get(edge[0]));
                        assertFalse(result.blocked(), result.diagnostic().toString());
                        if (result.quiescent()) break;
                    }
                    continue;
                }
                int turn = 0;
                while (true) {
                    var selection = blue.advanced().auditNextProcessingSelection();
                    if (selection.kind() == ProcessingSelection.Kind.NONE) break;
                    assertTrue(turn++ < 16, "Canonical two-root setup must terminate");
                    if (selection.kind() == ProcessingSelection.Kind.JOURNAL) {
                        assertFalse(blue.processing().drainJournal(new DrainBudget(1, 1)).blocked());
                        continue;
                    }
                    var work = selection.managedEpochApplicationWork().orElseThrow();
                    if (edge[0].equals("B") && work.sourceDocumentId().equals(handles.get("A").id()) && work.sourceEpoch() == 2) {
                        var invocation = capture(engine, work.consumerDocumentId());
                        var computed = engine.contractsClosureAdapter().resolveManagedApplicationOccurrences(invocation);
                        assertTrue(computed.attempt().isComplete());
                        var result = computed.attempt().processResult();
                        assertTrue(result.commits(), String.valueOf(result.diagnostic()));
                        invocation = computed.invocation();
                        var a = handles.get("A").id();
                        assertFalse(invocation.rootedEvidence().context().entryOwners().contains(ContractsClosureAdapter.closureId(a)));
                        assertTrue(RootedResultScope.members(result).contains(a));
                        var borrowed = invocation.documents().get(a).head();
                        var fence = invocation.rootedEvidence().publicationFence(a).head();
                        assertEquals(borrowed.epoch(), fence.epoch());
                        assertEquals(borrowed.blueId(), fence.blueId(), "The related LIVE turn cannot replace the frozen terminal source");
                        var owners = new LinkedHashSet<>(RootedResultScope.members(result));
                        var frozen = invocation;
                        assertDoesNotThrow(() -> engine.contractsClosureAdapter().requireRootedOwnersStillCurrent(
                                frozen, result, engine.documents().closureSnapshot(owners), owners));
                        testedJoin = true;
                    }
                    var applied = blue.processing().drainManagedEpochApplication(work.workIdentity());
                    assertEquals(1, applied.managedEpochApplications().size(), applied.diagnostic().toString());
                }
            }
            if (global) assertTrue(testedJoin, "Fixture must reach the actual borrowed-to-owned terminal SCC transition");
                for (var handle : handles.values()) {
                    assertTrue(blue.advanced().auditManagedDocumentReadiness(handle.id()).orElseThrow().ready());
                }
                assertTrue(blue.advanced().auditManagedOccurrence(handles.get("A").id(), "/peers/b").orElseThrow().active());
                assertTrue(blue.advanced().auditManagedOccurrence(handles.get("B").id(), "/peers/a").orElseThrow().active());
                var heads = handles.values().stream().map(handle -> handle.snapshot().blueId()).toList();
                CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
                assertEquals(heads, handles.values().stream().map(handle -> handle.snapshot().blueId()).toList());
                List<Object> proof = new java.util.ArrayList<>();
                for (var handle : handles.values()) {
                    assertTrue(engine.documents().occurrenceResolutionSnapshot().componentIndex().pendingJoinRootsFor(handle.id()).isEmpty());
                    proof.add(engine.documents().require(handle.id()).currentRepresentation().blueId());
                    proof.add(blue.advanced().auditManagedEpochs(handle.id()).stream().map(receipt -> List.of(
                            receipt.receiptIdentity(), receipt.documentId(), receipt.epoch(), receipt.kind(), receipt.beforeBlueId(),
                            receipt.afterBlueId(), receipt.afterDocument().json(), receipt.originalCauseIdentity(),
                            receipt.sourceEntry().map(source -> source.exact().json()), receipt.sourceOrder(),
                            receipt.contractsTransitionReceiptIdentity(), receipt.commitCompanionIdentity(), receipt.processingGas(),
                            receipt.emittedEvents().stream().map(event -> List.of(event.managedEventIdentity(), event.ordinal(),
                                    event.eventOccurrenceOrdinal(), event.sourceDocumentId(), event.eventOccurrenceIdentity(),
                                    event.eventBlueId(), event.exactEvent().json(), event.publicAtSource())).toList())).toList());
                }
                proof.add(engine.documents().publicationSnapshot().outbox().stream().map(event -> List.of(
                        event.publicEventOrdinal(), event.eventOccurrenceOrdinal(), event.publicRootDocumentId(),
                        event.eventOccurrenceIdentity(), event.eventBlueId(),
                        NodeWireForm.get(event.event()))).toList());
                proof.add(engine.documents().publicationSnapshot().closurePublicationReceipts().values().stream()
                        .map(receipt -> List.of(receipt.publicationIdentity(), receipt.attempt().processResult().outputClosureIdentity(),
                                receipt.attempt().processResult().totalGas(), receipt.attempt().processResult().gasTraceIdentity()))
                        .sorted(java.util.Comparator.comparing(row -> row.get(0).toString())).toList());
                return List.copyOf(proof);
        }
    }

    private static ContractsClosureAdapter.CohortInvocation capture(DefaultCoordinationEngine engine, DocumentId root) {
        var selected = new RootedCheckpointDriver(engine.documents(), engine.contractsClosureAdapter()).select(root, engine.auditTimelineEntries());
        var admission = engine.contractsClosureAdmissionAdapter();
        var environment = admission.environment();
        var policy = admission.executionPolicy();
        var profile = ContractsClosureProfile.release10(environment.blueLanguageSpecificationIdentity(), environment.contractsSpecificationIdentity(),
                ContractsExecutionPolicy.exactSharedGas(policy.sharedLimit(), policy.label()), List.of(root));
        return new ManagedEpochInvocationCapturer(engine.contractsClosureAdapter(), engine.runtime(), engine.objects(), engine.documents(), profile, environment)
                .capture(selected.historical(), selected.excludedConsumers()).invocation();
    }
}
