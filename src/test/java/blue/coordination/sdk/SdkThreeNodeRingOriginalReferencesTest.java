package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The public MyOS tutorial's exact saved-original ring, without persistence. */
final class SdkThreeNodeRingOriginalReferencesTest {
    @Test
    void closesThreeNodeRingWithoutReinterpretingRetainedReceipts() throws Exception {
        // given
        try (BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            CoordinationTestControl diagnostics = CoordinationTestControl.attach(blue.advanced().rawEngine());
            String template;
            try (var stream = getClass().getResourceAsStream("/tutorial-graphs/node.template.json")) {
                template = new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
            }
            String owner = "tutorial/graph-ring/alice";
            Map<String, String> sources = new LinkedHashMap<>();
            Map<String, ExactBlueValue> originals = new LinkedHashMap<>();
            Map<String, DocumentHandle> handles = new LinkedHashMap<>();
            for (String name : List.of("A", "B", "C")) {
                String source = template.replace("<NODE>", name).replace("<NAMESPACE>", "tutorial-ring")
                        .replace("<TIMELINE>", owner);
                sources.put(name, source);
                originals.put(name, blue.values().yaml(source));
            }
            TimelineHandle timeline = blue.timelines().register(owner, "alice");
            for (String name : sources.keySet()) {
                handles.put(name, blue.documents().admit(ManagedDocument.yaml(
                        DocumentId.of(originals.get(name).blueId()), sources.get(name)).publicRoot().fromNow()));
            }
            // when
            for (String[] edge : new String[][]{{"A", "b", "B"}, {"B", "c", "C"}, {"C", "a", "A"}}) {
                DocumentId child = DocumentId.of(originals.get(edge[2]).blueId());
                EntryHandle submitted = blue.operations().on(handles.get(edge[0])).from(timeline)
                        .call("attach").through("ownerChannel")
                        .request(request -> request.exact("edge", blue.values().yaml(edge[1]))
                                .exact("source", originals.get(edge[2])))
                        .selectManagedEpoch("/peers/" + edge[1], child, -1L, originals.get(edge[2]).blueId())
                        .submit();
                assertTrue(blue.processing().drainJournal(new DrainBudget(1L, 1L)).entry(submitted).applied());
                for (int step = 0; step < 32 && originals.values().stream().anyMatch(value -> !blue.advanced()
                        .auditManagedDocumentReadiness(DocumentId.of(value.blueId())).orElseThrow().ready()); step++) {
                    var work = blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow();
                    Map<DocumentId, List<String>> priorReceipts = new LinkedHashMap<>();
                    for (var value : originals.values()) {
                        DocumentId id = DocumentId.of(value.blueId());
                        priorReceipts.put(id, blue.advanced().auditManagedEpochs(id).stream()
                                .map(receipt -> receipt.receiptIdentity()).toList());
                    }
                    try {
                        DrainResult drained = blue.processing().drainManagedEpochApplication(work.workIdentity());
                        assertEquals(1, drained.managedEpochApplications().size(), drained.managedEpochApplicationAttempts().toString());
                    } catch (RuntimeException | AssertionError failure) {
                        System.out.println("RING_FAILED_WORK " + work);
                        diagnostics.lastClosureProcessEvidence().ifPresent(evidence ->
                                System.out.println("RING_WORK_TRACE " + evidence.workTrace()));
                        throw failure;
                    }
                    for (var prior : priorReceipts.entrySet()) {
                        List<String> retained = blue.advanced().auditManagedEpochs(prior.getKey()).stream()
                                .map(receipt -> receipt.receiptIdentity()).toList();
                        assertEquals(prior.getValue(), retained.subList(0, prior.getValue().size()));
                    }
                }
            }
            // then
            for (var original : originals.values()) {
                assertTrue(blue.advanced().auditManagedDocumentReadiness(DocumentId.of(original.blueId())).orElseThrow().ready());
            }
        }
    }
}
