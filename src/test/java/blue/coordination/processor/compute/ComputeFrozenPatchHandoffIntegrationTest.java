package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused proof that built-in Compute effects use the rc15 frozen patch boundary. */
class ComputeFrozenPatchHandoffIntegrationTest {

    @Test
    void accumulatedChangesetRetainsCanonicalFrozenBindingWithoutNodeMaterialization() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node document = support.initializedOperationWorkflow(String.join("\n",
                "    steps:",
                "      - name: CopyChannel",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendChange:",
                "              op: add",
                "              path: /copiedChannel",
                "              val:",
                "                $currentContract: /channel",
                "          - $return:",
                "              changeset:",
                "                $changeset: true"));
        Counters before = Counters.capture(metrics);

        DocumentProcessingResult result = support.processRun(document);

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertEquals("ownerChannel", result.document().get("/copiedChannel"));
        assertEquals(1L, metrics.directBexChangesetHits());
        assertEquals(1L, metrics.bexPatchFrozenDirectConversions());
        assertEquals(0L, metrics.bexPatchNodeMaterializations());
        assertEquals(1L, delta(metrics, before, "frozenPatchesHandedToLanguage"));
        assertEquals(2L, delta(metrics, before, "frozenPatchValuesAccepted"),
                "the frozen value is accepted during preview and runtime consumption");
        assertEquals(2L, delta(metrics, before, "frozenPatchValuesHandedToLanguage"));
        assertEquals(0L, delta(metrics, before, "mutablePatchValuesFrozen"));
        assertEquals(0L, delta(metrics, before, "frozenPatchValuesMaterialized"));
    }

    @Test
    void independentlyReturnedChangesetEventsAndTerminationKeepEffectOrder() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = support(metrics);
        Node document = support.initialize(support.yaml(
                support.operationWorkflowDocumentWithStatus("removeMe: old", String.join("\n",
                        "    steps:",
                        "      - name: ReturnEffects",
                        "        type: Coordination/Compute",
                        "        do:",
                        "          - $return:",
                        "              changeset:",
                        "                - op: add",
                        "                  path: /added",
                        "                  val:",
                        "                    nested: value",
                        "                - op: replace",
                        "                  path: /status",
                        "                  val: changed",
                        "                - op: remove",
                        "                  path: /removeMe",
                        "              events:",
                        "                - type: Coordination/Event",
                        "                  kind: first",
                        "                - type: Coordination/Event",
                        "                  kind: second",
                        "              termination:",
                        "                reason: complete",
                        "      - name: MustNotRun",
                        "        type: Coordination/Update Document",
                        "        changeset:",
                        "          - op: replace",
                        "            path: /status",
                        "            val: forbidden")))).document();
        Counters before = Counters.capture(metrics);

        DocumentProcessingResult result = support.processRun(document);

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertEquals("changed", result.document().get("/status"));
        assertEquals("value", result.document().get("/added/nested"));
        assertFalse(hasPath(result.document(), "/removeMe"));
        assertEquals("graceful", result.document().get("/contracts/terminated/cause"));
        assertEquals("complete", result.document().get("/contracts/terminated/reason"));
        assertEquals(Arrays.asList("first", "second"), selectedKinds(result));
        assertTrue(indexOfKind(result, "second") < indexOfType(result,
                RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED));

        assertEquals(0L, metrics.directBexChangesetHits(),
                "the returned list is independent of BEX's accumulated changeset");
        assertEquals(0L, metrics.bexPatchFrozenDirectConversions());
        assertEquals(2L, metrics.bexPatchNodeMaterializations());
        assertEquals(3L, delta(metrics, before, "frozenPatchesHandedToLanguage"));
        assertEquals(4L, delta(metrics, before, "frozenPatchValuesAccepted"),
                "each add/replace value is accepted by preview and runtime consumption");
        assertEquals(4L, delta(metrics, before, "frozenPatchValuesHandedToLanguage"));
        assertEquals(0L, delta(metrics, before, "mutablePatchValuesFrozen"));
        assertEquals(0L, delta(metrics, before, "frozenPatchValuesMaterialized"));
        assertEquals(2L, metrics.eventsEmitted());
        assertEquals(1L, metrics.successfulComputeTerminationRequests());
    }

    private static ComputeWorkflowTestSupport support(BexProcessingMetrics metrics) {
        return ComputeWorkflowTestSupport.create(CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());
    }

    private static long delta(BexProcessingMetrics metrics, Counters before, String name) {
        return metric(metrics.languageCounters(), name) - metric(before.values, name);
    }

    private static long metric(Map<String, Long> values, String name) {
        Long value = values.get(name);
        return value != null ? value.longValue() : 0L;
    }

    private static boolean hasPath(Node document, String path) {
        try {
            return document.getNode(path) != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static List<String> selectedKinds(DocumentProcessingResult result) {
        List<String> selected = new ArrayList<String>();
        for (Node event : result.triggeredEvents()) {
            Object kind = valueAt(event, "/kind");
            if ("first".equals(kind) || "second".equals(kind)) {
                selected.add((String) kind);
            }
        }
        return selected;
    }

    private static int indexOfKind(DocumentProcessingResult result, String kind) {
        for (int index = 0; index < result.triggeredEvents().size(); index++) {
            if (kind.equals(valueAt(result.triggeredEvents().get(index), "/kind"))) {
                return index;
            }
        }
        return -1;
    }

    private static Object valueAt(Node node, String path) {
        try {
            return node.get(path);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static int indexOfType(DocumentProcessingResult result, String typeBlueId) {
        for (int index = 0; index < result.triggeredEvents().size(); index++) {
            Node event = result.triggeredEvents().get(index);
            if (event.getType() != null && typeBlueId.equals(event.getType().getBlueId())) {
                return index;
            }
        }
        return -1;
    }

    private static final class Counters {
        private final Map<String, Long> values;

        private Counters(Map<String, Long> values) {
            this.values = values;
        }

        private static Counters capture(BexProcessingMetrics metrics) {
            return new Counters(metrics.languageCounters());
        }
    }
}
