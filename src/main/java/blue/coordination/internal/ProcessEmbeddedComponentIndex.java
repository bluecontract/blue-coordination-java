package blue.coordination.internal;

import blue.coordination.api.DocumentId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable deterministic SCC and condensation index for Process Embedded
 * topology.
 *
 * <p>This is the explicit cycle-capable graph API. The legacy graph snapshot
 * can expose this index without changing its acyclic reconciliation contract,
 * while a future closure-aware coordinator can build the index directly from
 * an effective binding set that contains cycles.</p>
 */
final class ProcessEmbeddedComponentIndex {
    private static final Comparator<DocumentId> DOCUMENT_ORDER =
            EmbeddingBinding.DOCUMENT_ORDER;
    private static final Comparator<List<DocumentId>> MEMBER_ORDER =
            ProcessEmbeddedComponentIndex::compareMembers;
    private static final Comparator<Component> COMPONENT_ORDER =
            Comparator.comparing(Component::members, MEMBER_ORDER);
    private static final Comparator<Cohort> COHORT_ORDER =
            Comparator.comparing(Cohort::members, MEMBER_ORDER);

    private final List<DocumentId> documents;
    private final List<Component> components;
    private final List<Cohort> cohorts;
    private final Map<DocumentId, Component> componentByDocument;
    private final Map<DocumentId, Cohort> cohortByDocument;
    private final Map<Component, List<Component>> targetsBySource;
    private final Map<Component, List<Component>> sourcesByTarget;

    private ProcessEmbeddedComponentIndex(
            List<DocumentId> documents,
            List<Component> components,
            List<Cohort> cohorts,
            Map<DocumentId, Component> componentByDocument,
            Map<DocumentId, Cohort> cohortByDocument,
            Map<Component, List<Component>> targetsBySource,
            Map<Component, List<Component>> sourcesByTarget) {
        this.documents = List.copyOf(documents);
        this.components = List.copyOf(components);
        this.cohorts = List.copyOf(cohorts);
        this.componentByDocument = Map.copyOf(componentByDocument);
        this.cohortByDocument = Map.copyOf(cohortByDocument);
        this.targetsBySource = immutableComponentIndex(targetsBySource);
        this.sourcesByTarget = immutableComponentIndex(sourcesByTarget);
    }

    /** Builds a cycle-capable index over every binding endpoint. */
    static ProcessEmbeddedComponentIndex fromBindings(
            Collection<EmbeddingBinding> bindings) {
        return fromDocumentsAndBindings(Set.of(), bindings);
    }

    /**
     * Builds the cycle-capable index from the complete occurrence inventory.
     * Inactive reservations retain their document membership but contribute
     * no directed edge.
     */
    static ProcessEmbeddedComponentIndex fromOccurrenceInventory(
            ManagedOccurrenceInventory inventory) {
        return fromDocumentsAndOccurrenceInventory(Set.of(), inventory);
    }

    /**
     * Builds the cycle-capable index from explicit managed membership and the
     * active projection of a complete occurrence inventory.
     */
    static ProcessEmbeddedComponentIndex fromDocumentsAndOccurrenceInventory(
            Collection<DocumentId> documents,
            ManagedOccurrenceInventory inventory) {
        Objects.requireNonNull(documents, "documents");
        ManagedOccurrenceInventory selected = Objects.requireNonNull(
                inventory, "inventory");
        NavigableSet<DocumentId> completeDocuments =
                new TreeSet<>(DOCUMENT_ORDER);
        completeDocuments.addAll(documents);
        completeDocuments.addAll(selected.documentIds());
        List<DirectedBinding> active = selected.activeRows().stream()
                .map(row -> new DirectedBinding(
                        row.occurrenceIdentity(),
                        DocumentId.of(row.sourceDocumentId().value()),
                        DocumentId.of(row.targetDocumentId().value())))
                .toList();
        return fromDirectedBindings(completeDocuments, active);
    }

    /**
     * Builds a cycle-capable index and retains explicitly supplied isolated
     * documents as singleton components and cohorts.
     */
    static ProcessEmbeddedComponentIndex fromDocumentsAndBindings(
            Collection<DocumentId> documents,
            Collection<EmbeddingBinding> bindings) {
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(bindings, "bindings");

        List<DirectedBinding> projected = bindings.stream()
                .map(binding -> Objects.requireNonNull(binding, "binding"))
                .sorted(EmbeddingBinding.GLOBAL_ORDER)
                .map(binding -> new DirectedBinding(
                        binding.bindingId(),
                        binding.parentDocumentId(),
                        binding.childDocumentId()))
                .toList();
        return fromDirectedBindings(documents, projected);
    }

    private static ProcessEmbeddedComponentIndex fromDirectedBindings(
            Collection<DocumentId> documents,
            Collection<DirectedBinding> bindings) {
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(bindings, "bindings");

        NavigableSet<DocumentId> allDocuments = new TreeSet<>(DOCUMENT_ORDER);
        documents.forEach(document -> allDocuments.add(
                Objects.requireNonNull(document, "document")));

        Map<String, DirectedBinding> bindingsById = new LinkedHashMap<>();
        List<DirectedBinding> canonicalBindings = bindings.stream()
                .map(binding -> Objects.requireNonNull(binding, "binding"))
                .sorted(DirectedBinding.ORDER)
                .toList();
        for (DirectedBinding binding : canonicalBindings) {
            if (bindingsById.putIfAbsent(
                    binding.identity(), binding) != null) {
                throw new IllegalStateException(
                        "Duplicate Process Embedded binding "
                                + binding.identity());
            }
            allDocuments.add(binding.sourceDocumentId());
            allDocuments.add(binding.targetDocumentId());
        }

        Map<DocumentId, NavigableSet<DocumentId>> targets = adjacency(
                allDocuments);
        Map<DocumentId, NavigableSet<DocumentId>> sources = adjacency(
                allDocuments);
        for (DirectedBinding binding : canonicalBindings) {
            targets.get(binding.sourceDocumentId()).add(
                    binding.targetDocumentId());
            sources.get(binding.targetDocumentId()).add(
                    binding.sourceDocumentId());
        }

        List<List<DocumentId>> stronglyConnected = stronglyConnected(
                allDocuments, targets, sources);
        Map<DocumentId, Component> componentByDocument = new HashMap<>();
        List<Component> scalarComponents = new ArrayList<>();
        for (List<DocumentId> members : stronglyConnected) {
            boolean selfCycle = members.size() == 1
                    && targets.get(members.get(0)).contains(members.get(0));
            Component component = new Component(
                    members, members.size() > 1 || selfCycle);
            scalarComponents.add(component);
            for (DocumentId member : members) {
                Component duplicate = componentByDocument.put(
                        member, component);
                if (duplicate != null) {
                    throw new IllegalStateException(
                            "Document appears in more than one component: "
                                    + member);
                }
            }
        }
        scalarComponents.sort(COMPONENT_ORDER);

        Map<Component, NavigableSet<Component>> componentTargets =
                componentAdjacency(scalarComponents);
        Map<Component, NavigableSet<Component>> componentSources =
                componentAdjacency(scalarComponents);
        for (Map.Entry<DocumentId, NavigableSet<DocumentId>> entry
                : targets.entrySet()) {
            Component source = componentByDocument.get(entry.getKey());
            for (DocumentId targetDocument : entry.getValue()) {
                Component target = componentByDocument.get(targetDocument);
                if (!source.equals(target)) {
                    componentTargets.get(source).add(target);
                    componentSources.get(target).add(source);
                }
            }
        }

        List<Cohort> cohorts = buildCohorts(
                scalarComponents, componentTargets, componentSources);
        List<Component> targetBeforeSource = targetBeforeSource(
                scalarComponents, componentTargets, componentSources);
        Map<DocumentId, Cohort> cohortByDocument = new HashMap<>();
        for (Cohort cohort : cohorts) {
            for (DocumentId member : cohort.members()) {
                Cohort duplicate = cohortByDocument.put(member, cohort);
                if (duplicate != null) {
                    throw new IllegalStateException(
                            "Document appears in more than one cohort: "
                                    + member);
                }
            }
        }
        if (!componentByDocument.keySet().equals(allDocuments)
                || !cohortByDocument.keySet().equals(allDocuments)) {
            throw new IllegalStateException(
                    "Component index does not exactly cover its documents");
        }

        return new ProcessEmbeddedComponentIndex(
                List.copyOf(allDocuments),
                targetBeforeSource,
                cohorts,
                componentByDocument,
                cohortByDocument,
                asListIndex(componentTargets),
                asListIndex(componentSources));
    }

    private record DirectedBinding(
            String identity,
            DocumentId sourceDocumentId,
            DocumentId targetDocumentId) {
        private static final Comparator<DirectedBinding> ORDER = Comparator
                .comparing((DirectedBinding binding) ->
                                binding.sourceDocumentId().value(),
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparing(binding ->
                                binding.targetDocumentId().value(),
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparing(DirectedBinding::identity,
                        EmbeddingBinding.TEXT_ORDER);

        private DirectedBinding {
            identity = Objects.requireNonNull(identity, "identity");
            if (identity.isBlank()) {
                throw new IllegalArgumentException(
                        "Process Embedded binding identity must not be blank");
            }
            sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
        }
    }

    /** Every indexed document in exact scalar order. */
    List<DocumentId> documents() {
        return documents;
    }

    /**
     * Components grouped by scalar-ordered cohort and ordered target before
     * source within each cohort.
     */
    List<Component> components() {
        return components;
    }

    /** Weakly connected cohorts in exact minimum-member scalar order. */
    List<Cohort> cohorts() {
        return cohorts;
    }

    Component component(DocumentId document) {
        Component component = componentByDocument.get(
                Objects.requireNonNull(document, "document"));
        if (component == null) {
            throw new IllegalArgumentException(
                    "Unknown Process Embedded document " + document);
        }
        return component;
    }

    Cohort cohort(DocumentId document) {
        Cohort cohort = cohortByDocument.get(
                Objects.requireNonNull(document, "document"));
        if (cohort == null) {
            throw new IllegalArgumentException(
                    "Unknown Process Embedded document " + document);
        }
        return cohort;
    }

    /** Direct condensation targets in exact component scalar order. */
    List<Component> targets(Component source) {
        return requireIndexed(source, targetsBySource, "component");
    }

    /** Direct condensation sources in exact component scalar order. */
    List<Component> sources(Component target) {
        return requireIndexed(target, sourcesByTarget, "component");
    }

    private static List<Component> requireIndexed(
            Component component,
            Map<Component, List<Component>> index,
            String label) {
        Component checked = Objects.requireNonNull(component, label);
        List<Component> adjacent = index.get(checked);
        if (adjacent == null) {
            throw new IllegalArgumentException(
                    "Unknown Process Embedded component "
                            + checked.members());
        }
        return adjacent;
    }

    private static Map<DocumentId, NavigableSet<DocumentId>> adjacency(
            Collection<DocumentId> documents) {
        Map<DocumentId, NavigableSet<DocumentId>> result =
                new TreeMap<>(DOCUMENT_ORDER);
        for (DocumentId document : documents) {
            result.put(document, new TreeSet<>(DOCUMENT_ORDER));
        }
        return result;
    }

    private static Map<Component, NavigableSet<Component>> componentAdjacency(
            Collection<Component> components) {
        Map<Component, NavigableSet<Component>> result =
                new TreeMap<>(COMPONENT_ORDER);
        for (Component component : components) {
            result.put(component, new TreeSet<>(COMPONENT_ORDER));
        }
        return result;
    }

    private static List<List<DocumentId>> stronglyConnected(
            NavigableSet<DocumentId> documents,
            Map<DocumentId, NavigableSet<DocumentId>> targets,
            Map<DocumentId, NavigableSet<DocumentId>> sources) {
        List<DocumentId> finishOrder = finishOrder(documents, targets);
        Set<DocumentId> assigned = new LinkedHashSet<>();
        List<List<DocumentId>> result = new ArrayList<>();
        for (int index = finishOrder.size() - 1; index >= 0; index--) {
            DocumentId start = finishOrder.get(index);
            if (!assigned.add(start)) {
                continue;
            }
            NavigableSet<DocumentId> members = new TreeSet<>(DOCUMENT_ORDER);
            Deque<DocumentId> pending = new ArrayDeque<>();
            pending.push(start);
            while (!pending.isEmpty()) {
                DocumentId current = pending.pop();
                members.add(current);
                List<DocumentId> predecessors = new ArrayList<>(
                        sources.get(current));
                Collections.reverse(predecessors);
                for (DocumentId predecessor : predecessors) {
                    if (assigned.add(predecessor)) {
                        pending.push(predecessor);
                    }
                }
            }
            result.add(List.copyOf(members));
        }
        result.sort(MEMBER_ORDER);
        return result;
    }

    private static List<DocumentId> finishOrder(
            Collection<DocumentId> documents,
            Map<DocumentId, NavigableSet<DocumentId>> targets) {
        Set<DocumentId> visited = new LinkedHashSet<>();
        List<DocumentId> finished = new ArrayList<>();
        for (DocumentId start : documents) {
            if (!visited.add(start)) {
                continue;
            }
            Deque<DepthFirstFrame> pending = new ArrayDeque<>();
            pending.push(new DepthFirstFrame(
                    start, targets.get(start).iterator()));
            while (!pending.isEmpty()) {
                DepthFirstFrame frame = pending.peek();
                if (frame.targets().hasNext()) {
                    DocumentId target = frame.targets().next();
                    if (visited.add(target)) {
                        pending.push(new DepthFirstFrame(
                                target, targets.get(target).iterator()));
                    }
                } else {
                    finished.add(pending.pop().document());
                }
            }
        }
        return finished;
    }

    private static List<Cohort> buildCohorts(
            List<Component> scalarComponents,
            Map<Component, NavigableSet<Component>> targets,
            Map<Component, NavigableSet<Component>> sources) {
        Set<Component> assigned = new LinkedHashSet<>();
        List<Cohort> result = new ArrayList<>();
        for (Component start : scalarComponents) {
            if (!assigned.add(start)) {
                continue;
            }
            NavigableSet<Component> cohortComponents =
                    new TreeSet<>(COMPONENT_ORDER);
            Deque<Component> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                Component current = pending.removeFirst();
                cohortComponents.add(current);
                for (Component adjacent : union(
                        targets.get(current), sources.get(current))) {
                    if (assigned.add(adjacent)) {
                        pending.addLast(adjacent);
                    }
                }
            }
            List<Component> ordered = targetBeforeSource(
                    cohortComponents, targets, sources);
            List<DocumentId> members = cohortComponents.stream()
                    .flatMap(component -> component.members().stream())
                    .sorted(DOCUMENT_ORDER)
                    .toList();
            result.add(new Cohort(members, ordered));
        }
        result.sort(COHORT_ORDER);
        return List.copyOf(result);
    }

    private static NavigableSet<Component> union(
            Collection<Component> first,
            Collection<Component> second) {
        NavigableSet<Component> result = new TreeSet<>(COMPONENT_ORDER);
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static List<Component> targetBeforeSource(
            Collection<Component> components,
            Map<Component, NavigableSet<Component>> targets,
            Map<Component, NavigableSet<Component>> sources) {
        Map<Component, Integer> remainingTargets = new HashMap<>();
        PriorityQueue<Component> ready = new PriorityQueue<>(COMPONENT_ORDER);
        for (Component component : components) {
            int count = targets.get(component).size();
            remainingTargets.put(component, count);
            if (count == 0) {
                ready.add(component);
            }
        }
        List<Component> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            Component target = ready.remove();
            result.add(target);
            for (Component source : sources.get(target)) {
                int remaining = remainingTargets.compute(
                        source, (ignored, value) -> Math.subtractExact(
                                Objects.requireNonNull(value, "value"), 1));
                if (remaining == 0) {
                    ready.add(source);
                }
            }
        }
        if (result.size() != components.size()) {
            throw new IllegalStateException(
                    "SCC condensation must be acyclic");
        }
        return List.copyOf(result);
    }

    private static Map<Component, List<Component>> asListIndex(
            Map<Component, NavigableSet<Component>> source) {
        Map<Component, List<Component>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return result;
    }

    private static Map<Component, List<Component>> immutableComponentIndex(
            Map<Component, List<Component>> source) {
        Map<Component, List<Component>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }

    private static int compareMembers(
            List<DocumentId> first,
            List<DocumentId> second) {
        int common = Math.min(first.size(), second.size());
        for (int index = 0; index < common; index++) {
            int comparison = DOCUMENT_ORDER.compare(
                    first.get(index), second.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(first.size(), second.size());
    }

    /** One exact strongly connected component. */
    record Component(List<DocumentId> members, boolean cyclic) {
        Component {
            members = List.copyOf(Objects.requireNonNull(
                    members, "members"));
            if (members.isEmpty()) {
                throw new IllegalArgumentException(
                        "component members must not be empty");
            }
        }
    }

    /** One weakly connected cohort with its condensation execution order. */
    record Cohort(List<DocumentId> members, List<Component> components) {
        Cohort {
            members = List.copyOf(Objects.requireNonNull(
                    members, "members"));
            components = List.copyOf(Objects.requireNonNull(
                    components, "components"));
            if (members.isEmpty() || components.isEmpty()) {
                throw new IllegalArgumentException(
                        "cohort must not be empty");
            }
        }
    }

    private record DepthFirstFrame(
            DocumentId document,
            Iterator<DocumentId> targets) {
    }
}
