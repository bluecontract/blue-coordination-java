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

    private final PersistentOrderedMap<DocumentId, Component>
            componentByDocument;
    private final PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<DocumentId, Boolean>> targetsByDocument;
    private final PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<DocumentId, Boolean>> sourcesByDocument;
    private final boolean rootedViews;

    private ProcessEmbeddedComponentIndex(
            PersistentOrderedMap<DocumentId, Component> componentByDocument,
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<DocumentId, Boolean>>
                    targetsByDocument,
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<DocumentId, Boolean>>
                    sourcesByDocument) {
        this(componentByDocument, targetsByDocument, sourcesByDocument, false);
    }

    private ProcessEmbeddedComponentIndex(PersistentOrderedMap<DocumentId, Component> componentByDocument,
            PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> targetsByDocument,
            PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> sourcesByDocument,
            boolean rootedViews) {
        this.rootedViews = rootedViews;
        this.componentByDocument = Objects.requireNonNull(
                componentByDocument, "componentByDocument");
        this.targetsByDocument = Objects.requireNonNull(
                targetsByDocument, "targetsByDocument");
        this.sourcesByDocument = Objects.requireNonNull(
                sourcesByDocument, "sourcesByDocument");
    }

    boolean hasRootedViews() { return rootedViews; }

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

        if (!componentByDocument.keySet().equals(allDocuments)) {
            throw new IllegalStateException(
                    "Component index does not exactly cover its documents");
        }
        return new ProcessEmbeddedComponentIndex(
                persistentComponents(componentByDocument),
                persistentAdjacency(targets),
                persistentAdjacency(sources));
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
        return componentByDocument.keys();
    }

    /**
     * Components grouped by scalar-ordered cohort and ordered target before
     * source within each cohort.
     */
    List<Component> components() {
        List<Component> scalar = scalarComponents();
        return targetBeforeSource(
                scalar,
                componentAdjacency(scalar, true),
                componentAdjacency(scalar, false));
    }

    /** Weakly connected cohorts in exact minimum-member scalar order. */
    List<Cohort> cohorts() {
        List<Component> scalar = scalarComponents();
        return buildCohorts(
                scalar,
                componentAdjacency(scalar, true),
                componentAdjacency(scalar, false));
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
        Component start = component(document);
        NavigableSet<Component> selected = new TreeSet<>(COMPONENT_ORDER);
        Deque<Component> pending = new ArrayDeque<>();
        selected.add(start);
        pending.add(start);
        while (!pending.isEmpty()) {
            Component current = pending.removeFirst();
            for (Component adjacent : union(
                    targets(current), sources(current))) {
                if (selected.add(adjacent)) {
                    pending.addLast(adjacent);
                }
            }
        }
        Map<Component, NavigableSet<Component>> targets =
                componentAdjacency(selected, true);
        Map<Component, NavigableSet<Component>> sources =
                componentAdjacency(selected, false);
        List<DocumentId> members = selected.stream()
                .flatMap(component -> component.members().stream())
                .sorted(DOCUMENT_ORDER)
                .toList();
        return new Cohort(
                members,
                targetBeforeSource(selected, targets, sources));
    }

    /** Direct condensation targets in exact component scalar order. */
    List<Component> targets(Component source) {
        return adjacentComponents(source, targetsByDocument);
    }

    /** Direct condensation sources in exact component scalar order. */
    List<Component> sources(Component target) {
        return adjacentComponents(target, sourcesByDocument);
    }

    /**
     * Rebuilds only an exact forward-closed region and persistently rewrites
     * the raw outgoing/incoming edge buckets owned by its source documents.
     * Components and edge buckets outside the region remain shared by identity.
     */
    ProcessEmbeddedComponentIndex replaceForwardClosure(
            Collection<DocumentId> affectedDocuments,
            ManagedOccurrenceInventory inventory) {
        NavigableSet<DocumentId> affected = new TreeSet<>(DOCUMENT_ORDER);
        Objects.requireNonNull(affectedDocuments, "affectedDocuments")
                .forEach(document -> affected.add(Objects.requireNonNull(
                        document, "affectedDocument")));
        if (affected.isEmpty()) {
            return this;
        }
        ManagedOccurrenceInventory selectedInventory = Objects.requireNonNull(
                inventory, "inventory");
        ArrayList<DirectedBinding> active = new ArrayList<>();
        for (DocumentId source : affected) {
            for (blue.language.processor.closure.ManagedOccurrenceBinding row
                    : selectedInventory.activeRowsFrom(source)) {
                DocumentId target = DocumentId.of(
                        row.targetDocumentId().value());
                if (!affected.contains(target)) {
                    throw new IllegalArgumentException(
                            "Affected component region is not forward closed: "
                                    + source + " -> " + target);
                }
                active.add(new DirectedBinding(
                        row.occurrenceIdentity(), source, target));
            }
        }
        ProcessEmbeddedComponentIndex local = fromDirectedBindings(
                affected, active);

        return replaceRegion(affected, local);
    }

    /** Uses the real rooted finalizer's partition; one-way targets keep their independent components. */
    ProcessEmbeddedComponentIndex replaceOwnedResult(blue.language.processor.closure.ClosureProcessResult result) {
        if (!result.commits() || result.rootedProjection() == null) {
            throw new IllegalArgumentException("Owned component replacement requires processor-derived authority");
        }
        var calculated = result.rootedProjection().resultingSnapshot();
        List<DocumentId> selected = calculated.managedDocuments().stream()
                .map(document -> DocumentId.of(document.documentId().value())).toList();
        List<DirectedBinding> active = calculated.occurrences().stream().filter(row -> row.active())
                .map(row -> new DirectedBinding(row.occurrenceIdentity(), DocumentId.of(row.sourceDocumentId().value()),
                        DocumentId.of(row.targetDocumentId().value()))).toList();
        ProcessEmbeddedComponentIndex local = fromDirectedBindings(selected, active);
        // Each partition is authenticated by the actual root result. Independent
        // roots can retain different exact forward views; their global edge union
        // does not define another authoritative SCC or a single current DAG.
        Map<DocumentId, Component> verified = new LinkedHashMap<>();
        for (var proof : calculated.components()) {
            Component component = new Component(proof.orderedMemberDocumentIds().stream()
                    .map(id -> DocumentId.of(id.value())).toList(),
                    proof.kind() == blue.language.processor.closure.ComponentKind.CYCLIC);
            for (DocumentId member : component.members()) verified.put(member, component);
        }
        local = new ProcessEmbeddedComponentIndex(persistentComponents(verified),
                local.targetsByDocument, local.sourcesByDocument, true);
        return replaceRegion(RootedResultScope.members(result), local);
    }

    private ProcessEmbeddedComponentIndex replaceRegion(Collection<DocumentId> affected,
            ProcessEmbeddedComponentIndex local) {
        PersistentOrderedMap<DocumentId, Component> components =
                componentByDocument;
        for (DocumentId document : affected) {
            components = components.put(
                    document, local.component(document)).map();
        }
        PersistentOrderedMap<DocumentId,
                PersistentOrderedMap<DocumentId, Boolean>> targets =
                targetsByDocument;
        PersistentOrderedMap<DocumentId,
                PersistentOrderedMap<DocumentId, Boolean>> sources =
                sourcesByDocument;
        for (DocumentId source : affected) {
            PersistentOrderedMap<DocumentId, Boolean> beforeTargets =
                    bucket(targets, source);
            PersistentOrderedMap<DocumentId, Boolean> afterTargets =
                    bucket(local.targetsByDocument, source);
            for (DocumentId removed : beforeTargets.keys()) {
                if (!afterTargets.containsKey(removed)) {
                    sources = removeBucketValue(sources, removed, source);
                }
            }
            for (DocumentId added : afterTargets.keys()) {
                if (!beforeTargets.containsKey(added)) {
                    sources = putBucketValue(sources, added, source);
                }
            }
            targets = replaceBucket(targets, source, afterTargets);
        }
        return new ProcessEmbeddedComponentIndex(
                components, targets, sources, rootedViews || local.rootedViews);
    }

    /** Orders only the components intersecting the selected forward region. */
    List<Component> orderedComponentsFor(
            Collection<DocumentId> documents) {
        NavigableSet<Component> selected = new TreeSet<>(COMPONENT_ORDER);
        Objects.requireNonNull(documents, "documents").forEach(document ->
                selected.add(component(document)));
        Map<Component, NavigableSet<Component>> targets =
                componentAdjacency(selected, true);
        Map<Component, NavigableSet<Component>> sources =
                componentAdjacency(selected);
        targets.forEach((source, selectedTargets) -> selectedTargets.forEach(
                target -> sources.get(target).add(source)));
        return targetBeforeSource(selected, targets, sources);
    }

    int sharedComponentNodesForTesting(
            ProcessEmbeddedComponentIndex other) {
        return componentByDocument.sharedNodeCountForTesting(
                Objects.requireNonNull(other, "other").componentByDocument);
    }

    int sharedTargetBucketNodesForTesting(
            ProcessEmbeddedComponentIndex other) {
        return targetsByDocument.sharedNodeCountForTesting(
                Objects.requireNonNull(other, "other").targetsByDocument);
    }

    boolean sameComponentEntryIdentityForTesting(
            ProcessEmbeddedComponentIndex other,
            DocumentId document) {
        ProcessEmbeddedComponentIndex selected = Objects.requireNonNull(
                other, "other");
        DocumentId key = Objects.requireNonNull(document, "document");
        return componentByDocument.get(key)
                == selected.componentByDocument.get(key);
    }

    private List<Component> adjacentComponents(
            Component supplied,
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<DocumentId, Boolean>> adjacency) {
        Component selected = requireCurrent(supplied);
        NavigableSet<Component> adjacent = new TreeSet<>(COMPONENT_ORDER);
        for (DocumentId member : selected.members()) {
            for (DocumentId document : bucket(adjacency, member).keys()) {
                Component component = component(document);
                if (!component.equals(selected)) {
                    adjacent.add(component);
                }
            }
        }
        return List.copyOf(adjacent);
    }

    private Component requireCurrent(Component supplied) {
        Component selected = Objects.requireNonNull(supplied, "component");
        Component current = component(selected.members().get(0));
        if (!current.equals(selected)) {
            throw new IllegalArgumentException(
                    "Unknown Process Embedded component "
                            + selected.members());
        }
        return selected;
    }

    private List<Component> scalarComponents() {
        TreeMap<List<DocumentId>, Component> unique = new TreeMap<>(
                MEMBER_ORDER);
        componentByDocument.values().forEach(component -> unique.putIfAbsent(
                component.members(), component));
        return List.copyOf(unique.values());
    }

    private Map<Component, NavigableSet<Component>> componentAdjacency(
            Collection<Component> selected,
            boolean outgoing) {
        NavigableSet<Component> allowed = new TreeSet<>(COMPONENT_ORDER);
        allowed.addAll(selected);
        Map<Component, NavigableSet<Component>> result =
                componentAdjacency(selected);
        for (Component component : selected) {
            List<Component> adjacent = outgoing
                    ? targets(component) : sources(component);
            for (Component candidate : adjacent) {
                if (allowed.contains(candidate)) {
                    result.get(component).add(candidate);
                }
            }
        }
        return result;
    }

    private static PersistentOrderedMap<DocumentId, Component>
            persistentComponents(Map<DocumentId, Component> source) {
        PersistentOrderedMap<DocumentId, Component> result =
                PersistentOrderedMap.empty(DOCUMENT_ORDER);
        for (Map.Entry<DocumentId, Component> entry : source.entrySet()) {
            result = result.put(entry.getKey(), entry.getValue()).map();
        }
        return result;
    }

    private static PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<DocumentId, Boolean>> persistentAdjacency(
                    Map<DocumentId, ? extends Collection<DocumentId>> source) {
        PersistentOrderedMap<DocumentId,
                PersistentOrderedMap<DocumentId, Boolean>> result =
                PersistentOrderedMap.empty(DOCUMENT_ORDER);
        for (Map.Entry<DocumentId, ? extends Collection<DocumentId>> entry
                : source.entrySet()) {
            PersistentOrderedMap<DocumentId, Boolean> values =
                    PersistentOrderedMap.empty(DOCUMENT_ORDER);
            for (DocumentId document : entry.getValue()) {
                values = values.put(document, Boolean.TRUE).map();
            }
            if (!values.isEmpty()) {
                result = result.put(entry.getKey(), values).map();
            }
        }
        return result;
    }

    private static PersistentOrderedMap<DocumentId, Boolean> bucket(
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<DocumentId, Boolean>> index,
            DocumentId document) {
        PersistentOrderedMap<DocumentId, Boolean> selected =
                index.get(document);
        return selected == null
                ? PersistentOrderedMap.empty(DOCUMENT_ORDER) : selected;
    }

    private static PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<DocumentId, Boolean>> replaceBucket(
                    PersistentOrderedMap<DocumentId,
                            PersistentOrderedMap<DocumentId, Boolean>> index,
                    DocumentId document,
                    PersistentOrderedMap<DocumentId, Boolean> replacement) {
        if (replacement.isEmpty()) {
            return index.remove(document).map();
        }
        return index.put(document, replacement).map();
    }

    private static PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<DocumentId, Boolean>> putBucketValue(
                    PersistentOrderedMap<DocumentId,
                            PersistentOrderedMap<DocumentId, Boolean>> index,
                    DocumentId bucket,
                    DocumentId value) {
        PersistentOrderedMap<DocumentId, Boolean> changed =
                bucket(index, bucket).put(value, Boolean.TRUE).map();
        return index.put(bucket, changed).map();
    }

    private static PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<DocumentId, Boolean>> removeBucketValue(
                    PersistentOrderedMap<DocumentId,
                            PersistentOrderedMap<DocumentId, Boolean>> index,
                    DocumentId bucket,
                    DocumentId value) {
        PersistentOrderedMap<DocumentId, Boolean> changed =
                bucket(index, bucket).remove(value).map();
        return replaceBucket(index, bucket, changed);
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
