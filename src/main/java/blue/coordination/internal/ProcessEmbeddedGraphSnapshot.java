package blue.coordination.internal;

import blue.coordination.api.DocumentId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable indexed snapshot derived only from effective Process Embedded occurrences. */
final class ProcessEmbeddedGraphSnapshot {
    private final long generation;
    private final Map<DocumentId, List<EmbeddingBinding>> childrenByParent;
    private final Map<DocumentId, List<EmbeddingBinding>> parentsByChild;
    private final Map<String, EmbeddingBinding> byId;

    private ProcessEmbeddedGraphSnapshot(
            long generation,
            List<EmbeddingBinding> bindings) {
        if (generation < 0L) {
            throw new IllegalArgumentException(
                    "generation must be non-negative");
        }
        this.generation = generation;
        Map<DocumentId, List<EmbeddingBinding>> children =
                new LinkedHashMap<>();
        Map<DocumentId, List<EmbeddingBinding>> parents =
                new LinkedHashMap<>();
        Map<String, EmbeddingBinding> identities = new LinkedHashMap<>();
        bindings.stream().sorted(EmbeddingBinding.GLOBAL_ORDER).forEach(binding -> {
            EmbeddingBinding duplicate = identities.putIfAbsent(
                    binding.bindingId(), binding);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate Process Embedded binding "
                                + binding.bindingId());
            }
            children.computeIfAbsent(
                    binding.parentDocumentId(), ignored -> new ArrayList<>())
                    .add(binding);
            parents.computeIfAbsent(
                    binding.childDocumentId(), ignored -> new ArrayList<>())
                    .add(binding);
        });
        children.values().forEach(list -> list.sort(EmbeddingBinding.GLOBAL_ORDER));
        parents.values().forEach(list -> list.sort(EmbeddingBinding.GLOBAL_ORDER));
        this.childrenByParent = immutableIndex(children);
        this.parentsByChild = immutableIndex(parents);
        this.byId = Collections.unmodifiableMap(identities);
        validateAcyclic();
    }

    private ProcessEmbeddedGraphSnapshot(
            long generation,
            Map<DocumentId, List<EmbeddingBinding>> childrenByParent,
            Map<DocumentId, List<EmbeddingBinding>> parentsByChild,
            Map<String, EmbeddingBinding> byId) {
        this.generation = generation;
        this.childrenByParent = Collections.unmodifiableMap(childrenByParent);
        this.parentsByChild = Collections.unmodifiableMap(parentsByChild);
        this.byId = Collections.unmodifiableMap(byId);
    }

    static ProcessEmbeddedGraphSnapshot empty() {
        return new ProcessEmbeddedGraphSnapshot(0L, List.of());
    }

    long generation() {
        return generation;
    }

    List<EmbeddingBinding> children(DocumentId parent) {
        return childrenByParent.getOrDefault(
                Objects.requireNonNull(parent, "parent"), List.of());
    }

    List<EmbeddingBinding> parents(DocumentId child) {
        return parentsByChild.getOrDefault(
                Objects.requireNonNull(child, "child"), List.of());
    }

    EmbeddingBinding binding(String bindingId) {
        EmbeddingBinding binding = byId.get(Objects.requireNonNull(
                bindingId, "bindingId"));
        if (binding == null) {
            throw new IllegalArgumentException(
                    "Unknown Process Embedded binding " + bindingId);
        }
        return binding;
    }

    boolean containsBinding(String bindingId) {
        return byId.containsKey(Objects.requireNonNull(
                bindingId, "bindingId"));
    }

    List<EmbeddingBinding> bindings() {
        return byId.values().stream().sorted(EmbeddingBinding.GLOBAL_ORDER).toList();
    }

    /**
     * Builds the deterministic SCC/condensation view of this captured
     * generation. This does not relax legacy acyclic reconciliation.
     */
    ProcessEmbeddedComponentIndex componentIndex() {
        return ProcessEmbeddedComponentIndex.fromBindings(bindings());
    }

    ProcessEmbeddedGraphSnapshot reconcileParent(
            DocumentId parent,
            List<EmbeddingBinding> replacement) {
        return reconcileParent(parent, replacement, null);
    }

    ProcessEmbeddedGraphSnapshot reconcileParent(
            DocumentId parent,
            List<EmbeddingBinding> replacement,
            EngineMetrics metrics) {
        Objects.requireNonNull(parent, "parent");
        List<EmbeddingBinding> canonical = replacement.stream()
                .peek(binding -> {
                    if (!binding.parentDocumentId().equals(parent)) {
                        throw new IllegalArgumentException(
                                "Replacement binding belongs to another parent");
                    }
                })
                .sorted(EmbeddingBinding.GLOBAL_ORDER)
                .toList();
        if (children(parent).equals(canonical)) {
            return this;
        }
        List<EmbeddingBinding> current = children(parent);
        Map<DocumentId, List<EmbeddingBinding>> children =
                new LinkedHashMap<>(childrenByParent);
        if (canonical.isEmpty()) {
            children.remove(parent);
        } else {
            children.put(parent, canonical);
        }

        Map<DocumentId, List<EmbeddingBinding>> oldByChild = byChild(current);
        Map<DocumentId, List<EmbeddingBinding>> newByChild = byChild(canonical);
        Set<DocumentId> affectedChildren = new LinkedHashSet<>();
        affectedChildren.addAll(oldByChild.keySet());
        affectedChildren.addAll(newByChild.keySet());
        affectedChildren.removeIf(child -> Objects.equals(
                oldByChild.get(child), newByChild.get(child)));
        Map<DocumentId, List<EmbeddingBinding>> parents =
                new LinkedHashMap<>(parentsByChild);
        for (DocumentId child : affectedChildren) {
            List<EmbeddingBinding> updated = new ArrayList<>(
                    parents.getOrDefault(child, List.of()));
            updated.removeIf(binding ->
                    binding.parentDocumentId().equals(parent));
            updated.addAll(newByChild.getOrDefault(child, List.of()));
            updated.sort(EmbeddingBinding.GLOBAL_ORDER);
            if (updated.isEmpty()) {
                parents.remove(child);
            } else {
                parents.put(child, List.copyOf(updated));
            }
        }

        Map<String, EmbeddingBinding> identities =
                new LinkedHashMap<>(byId);
        for (EmbeddingBinding binding : current) {
            identities.remove(binding.bindingId());
        }
        int added = 0;
        for (EmbeddingBinding binding : canonical) {
            if (identities.putIfAbsent(
                    binding.bindingId(), binding) != null) {
                throw new IllegalStateException(
                        "Duplicate Process Embedded binding "
                                + binding.bindingId());
            }
            if (!current.contains(binding)) {
                added++;
            }
        }
        validateNewEdges(parent, current, canonical, children);
        if (metrics != null) {
            metrics.increment("temporal.graphForwardBucketsUpdated");
            metrics.add("temporal.graphReverseBucketsUpdated",
                    affectedChildren.size());
            metrics.add("temporal.graphBindingRecordsAdded", added);
            metrics.add("temporal.graphBindingRecordsRemoved",
                    current.size() - canonical.size() + added);
            metrics.add("temporal.graphBindingRecordsRetained",
                    canonical.size() - added);
        }
        return new ProcessEmbeddedGraphSnapshot(
                Math.addExact(generation, 1L),
                children, parents, identities);
    }

    private static Map<DocumentId, List<EmbeddingBinding>> byChild(
            List<EmbeddingBinding> bindings) {
        Map<DocumentId, List<EmbeddingBinding>> result = new LinkedHashMap<>();
        for (EmbeddingBinding binding : bindings) {
            result.computeIfAbsent(binding.childDocumentId(), ignored ->
                    new ArrayList<>()).add(binding);
        }
        result.replaceAll((ignored, grouped) -> grouped.stream()
                .sorted(EmbeddingBinding.GLOBAL_ORDER).toList());
        return result;
    }

    private static void validateNewEdges(
            DocumentId parent,
            List<EmbeddingBinding> current,
            List<EmbeddingBinding> replacement,
            Map<DocumentId, List<EmbeddingBinding>> children) {
        for (EmbeddingBinding binding : replacement) {
            if (!current.contains(binding) && reaches(
                    binding.childDocumentId(), parent, children,
                    new LinkedHashSet<>())) {
                throw new IllegalStateException(
                        "Process Embedded cycle reaches " + parent);
            }
        }
    }

    private static boolean reaches(
            DocumentId current,
            DocumentId target,
            Map<DocumentId, List<EmbeddingBinding>> children,
            Set<DocumentId> visited) {
        if (current.equals(target)) {
            return true;
        }
        return visited.add(current) && children.getOrDefault(
                current, List.of()).stream().anyMatch(binding -> reaches(
                binding.childDocumentId(), target, children, visited));
    }

    /** Direct targets plus every active ancestor, ordered child before parent. */
    List<DocumentId> childFirstClosure(Set<DocumentId> directTargets) {
        LinkedHashSet<DocumentId> closure = new LinkedHashSet<>(
                Objects.requireNonNull(directTargets, "directTargets"));
        Deque<DocumentId> pending = new ArrayDeque<>(closure);
        while (!pending.isEmpty()) {
            DocumentId child = pending.removeFirst();
            for (EmbeddingBinding parent : parents(child)) {
                if (closure.add(parent.parentDocumentId())) {
                    pending.addLast(parent.parentDocumentId());
                }
            }
        }
        List<DocumentId> result = new ArrayList<>();
        Set<DocumentId> emitted = new LinkedHashSet<>();
        closure.stream().sorted(EmbeddingBinding.DOCUMENT_ORDER)
                .forEach(document -> appendChildFirst(
                document, closure, emitted, result));
        return List.copyOf(result);
    }

    private void appendChildFirst(
            DocumentId document,
            Set<DocumentId> closure,
            Set<DocumentId> emitted,
            List<DocumentId> result) {
        if (emitted.contains(document)) {
            return;
        }
        for (EmbeddingBinding child : children(document)) {
            if (closure.contains(child.childDocumentId())) {
                appendChildFirst(
                        child.childDocumentId(), closure, emitted, result);
            }
        }
        if (emitted.add(document)) {
            result.add(document);
        }
    }

    private void validateAcyclic() {
        Set<DocumentId> visited = new LinkedHashSet<>();
        Set<DocumentId> active = new LinkedHashSet<>();
        LinkedHashSet<DocumentId> nodes = new LinkedHashSet<>();
        byId.values().forEach(binding -> {
            nodes.add(binding.parentDocumentId());
            nodes.add(binding.childDocumentId());
        });
        for (DocumentId node : nodes) {
            validateAcyclic(node, visited, active);
        }
    }

    private void validateAcyclic(
            DocumentId node,
            Set<DocumentId> visited,
            Set<DocumentId> active) {
        if (visited.contains(node)) {
            return;
        }
        if (!active.add(node)) {
            throw new IllegalStateException(
                    "Process Embedded cycle reaches " + node);
        }
        for (EmbeddingBinding child : children(node)) {
            validateAcyclic(child.childDocumentId(), visited, active);
        }
        active.remove(node);
        visited.add(node);
    }

    private static Map<DocumentId, List<EmbeddingBinding>> immutableIndex(
            Map<DocumentId, List<EmbeddingBinding>> source) {
        Map<DocumentId, List<EmbeddingBinding>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }
}
