package blue.coordination.examples.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable receipt for one entry's environment-owned Root fan-out. */
public record MyOsDemoDispatch(
        MyOsDemoEntry entry,
        Map<String, MyOsDemoResult> deliveriesByDocument,
        List<Integer> chunkSizes,
        MyOsWorkSnapshot work) {

    public MyOsDemoDispatch {
        Objects.requireNonNull(entry, "entry");
        deliveriesByDocument = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        deliveriesByDocument, "deliveriesByDocument")));
        chunkSizes = List.copyOf(chunkSizes);
        Objects.requireNonNull(work, "work");
    }

    public List<MyOsDemoResult> deliveries() {
        return List.copyOf(deliveriesByDocument.values());
    }

    public Set<String> documentKeys() {
        return deliveriesByDocument.keySet();
    }

    public MyOsDemoResult require(String documentKey) {
        MyOsDemoResult result = deliveriesByDocument.get(documentKey);
        if (result == null) {
            throw new IllegalStateException(
                    "Entry did not affect document " + documentKey
                            + "; actual=" + deliveriesByDocument.keySet());
        }
        return result;
    }

    public MyOsDemoResult onlyResult() {
        if (deliveriesByDocument.size() != 1) {
            throw new IllegalStateException(
                    "Expected one affected document, got "
                            + deliveriesByDocument.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    item -> Map.of(
                                            "scopes",
                                            item.getValue().delivery()
                                                    .transition().plan()
                                                    .preparedDelivery()
                                                    .selectedScopeChainIdentities()
                                                    .keySet(),
                                            "events",
                                            item.getValue().delivery()
                                                    .transition()
                                                    .platformResult()
                                                    .processResult()
                                                    .events().size(),
                                            "root",
                                            item.getValue().delivery()
                                                    .transition()
                                                    .commitPlan()
                                                    .resultingRootBlueId()),
                                    (left, right) -> left,
                                    LinkedHashMap::new)));
        }
        return deliveriesByDocument.values().iterator().next();
    }
}
