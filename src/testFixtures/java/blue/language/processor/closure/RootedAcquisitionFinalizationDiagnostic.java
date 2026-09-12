package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Test-only observation of exactly the existing read-expansion finalization, not an alternate factory. */
public final class RootedAcquisitionFinalizationDiagnostic {
    private RootedAcquisitionFinalizationDiagnostic() { }

    /** Does not return an input or authorize a changed primary; the caller must still use the real factory. */
    public static List<String> describe(ClosureInvocationInput original,
            List<ManagedDocumentSnapshot> documents, List<ManagedOccurrenceBinding> bindings,
            List<AffectedClosureSnapshot> proofs) {
        var selected = new ArrayList<>(documents);
        selected.sort(Comparator.comparing(ManagedDocumentSnapshot::documentId));
        var rows = new ArrayList<>(bindings); Collections.sort(rows);
        Map<DocumentId, Node> bodies = new LinkedHashMap<>();
        Map<DocumentId, Long> generations = new LinkedHashMap<>();
        for (var document : selected) {
            if (bodies.put(document.documentId(), document.document()) != null)
                throw new IllegalArgumentException("Duplicate diagnostic primary");
            generations.put(document.documentId(), document.componentGeneration());
        }
        var witnesses = RootedWitnessFrame.readExpansion(original, selected, rows, proofs);
        var graph = ManagedDocumentGraph.fromBindings(bodies.keySet(), rows, witnesses);
        var finalized = new ComponentFinalizationKernel().finalizeComponents(
                new ComponentFinalizationInput(graph, generations, bodies, rows, witnesses));
        var lines = new ArrayList<String>();
        lines.add("original=" + original.invocationIdentity() + " witnesses=" + witnesses.sources()
                + " activeGraph=" + graph.adjacency());
        for (var document : selected) {
            var exact = finalized.documents().get(document.documentId());
            var differences = new ArrayList<String>();
            changes("", NodeWireForm.get(document.document()), NodeWireForm.get(exact.document()), differences);
            lines.add("primary=" + document.documentId() + " wasOriginal=" + original.snapshot().contains(document.documentId())
                    + " witness=" + witnesses.sources().contains(document.documentId())
                    + " selected=" + document.epoch() + ":" + document.blueId() + ":generation" + document.componentGeneration()
                    + " finalized=" + exact.blueId() + ":generation" + exact.componentGeneration()
                    + " identityChanged=" + !document.blueId().equals(exact.blueId())
                    + " generationChanged=" + (document.componentGeneration() != exact.componentGeneration())
                    + " bodyPaths=" + differences + " pathsPossiblyTruncated=" + (differences.size() == 64));
        }
        for (var row : rows) {
            var actual = finalized.finalizedGraph().bindings().stream().filter(value ->
                    value.occurrenceIdentity().equals(row.occurrenceIdentity())).findFirst().orElseThrow();
            if (!row.bindingIdentity().equals(actual.bindingIdentity())) lines.add("row=" + row.sourceDocumentId()
                    + row.sourcePath() + " target=" + row.targetDocumentId() + " active=" + row.active()
                    + " witnessSource=" + witnesses.sources().contains(row.sourceDocumentId())
                    + " selectedTarget=" + row.expectedTargetBlueId() + " finalizedTarget=" + actual.expectedTargetBlueId());
        }
        return List.copyOf(lines);
    }

    private static void changes(String path, Object before, Object after, List<String> changes) {
        if (changes.size() >= 64 || Objects.equals(before, after)) return;
        if (before instanceof Map<?, ?> left && after instanceof Map<?, ?> right) {
            var keys = new TreeSet<String>();
            left.keySet().forEach(key -> keys.add((String) key)); right.keySet().forEach(key -> keys.add((String) key));
            for (String key : keys) changes(path + "/" + key.replace("~", "~0").replace("/", "~1"), left.get(key), right.get(key), changes);
        } else if (before instanceof List<?> left && after instanceof List<?> right) {
            if (left.size() != right.size()) changes.add(path + "/length:" + left.size() + "→" + right.size());
            for (int i = 0; i < Math.min(left.size(), right.size()); i++) changes(path + "/" + i, left.get(i), right.get(i), changes);
        } else changes.add(path + ":" + brief(before) + "→" + brief(after));
    }

    private static String brief(Object value) {
        if (value instanceof Map<?, ?> map) return "object(" + map.size() + ")";
        if (value instanceof List<?> list) return "array(" + list.size() + ")";
        String text = String.valueOf(value);
        return text.length() <= 96 ? text : text.substring(0, 96) + "…";
    }
}
