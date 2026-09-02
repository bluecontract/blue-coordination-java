package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.DocumentId;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.merge.ResolvedSnapshot;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
/** Cuts only effective Process Embedded paths from whole exact values. */
final class EmbeddedOnlyLayoutBuilder {
    private final BlueRuntime runtime;
    private final WholeObjectStore objects;
    private final EngineMetrics metrics;
    public EmbeddedOnlyLayoutBuilder(
            BlueRuntime runtime,
            WholeObjectStore objects,
            EngineMetrics metrics) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }
    public EmbeddedOnlyLayout build(ExactValue exactRoot) {
        Objects.requireNonNull(exactRoot, "exactRoot");
        return metrics.timed("layout.compileFrozenCatalog", () -> {
            metrics.increment("layout.referenceOnlyCatalogInputs");
            EffectiveFragmentationCatalog catalog =
                    runtime.effectiveFragmentationCatalog(exactRoot.blueId());
            EmbeddedLayoutPlan plan = EmbeddedLayoutPlan.compile(
                    exactRoot,
                    catalog,
                    path -> exactScopeAt(exactRoot, path));
            metrics.increment("layout.catalogCompilations");
            return buildWithPlan(
                    exactRoot,
                    plan,
                    concreteFromCatalog(catalog));
        });
    }
    public EmbeddedOnlyLayout rebuild(
            ExactValue exactRoot,
            EmbeddedOnlyLayout previous) {
        return rebuild(exactRoot, previous, false);
    }
    public EmbeddedOnlyLayout rebuildAfterManagedChildRevision(
            ExactValue exactRoot,
            EmbeddedOnlyLayout previous) {
        return rebuild(exactRoot, previous, true);
    }

    /**
     * Retains one independently managed document from a verified closure
     * result without re-hashing a cyclic {@code MASTER#n} member as an
     * acyclic value.
     *
     * <p>The Contracts result already authenticates the complete local body,
     * cyclic component proof, and authoritative member identity. The durable
     * occurrence inventory owns all managed-document edges, so this layout
     * intentionally stores only the selected document's Root and carries no
     * ambient container or recursively opened child scope. The existing local
     * contract plan may be reused only while its exact declarations still
     * match the resulting body.</p>
     */
    /** Retains one verified existing Root with an exact non-recursive routing
     * surface projected by the managed-document processor. */
    EmbeddedOnlyLayout retainVerifiedClosureRoot(
            ClosureProcessResult result,
            DocumentId documentId,
            EmbeddedOnlyLayout previous,
            RoutingSurface routingSurface) {
        Objects.requireNonNull(previous, "previous");
        return retainVerifiedClosureRoot(result, documentId, routingSurface);
    }

    /** Retains one new independently managed Root after verified admission,
     * using the exact Root-only routing projection rather than reopening a
     * cyclic member as a fragmentation-catalog Root. */
    EmbeddedOnlyLayout retainVerifiedClosureRoot(
            ClosureProcessResult result,
            DocumentId documentId,
            RoutingSurface routingSurface) {
        ClosureProcessResult verified = Objects.requireNonNull(
                result, "result");
        DocumentId selected = Objects.requireNonNull(documentId, "documentId");
        ExactValue exactRoot = objects.put(
                ExactValue.fromVerifiedClosureResult(verified, selected),
                "verified-closure-component-member");
        return verifiedRootLayout(
                exactRoot,
                EmbeddedLayoutPlan.managedRoot(routingSurface));
    }

    private EmbeddedOnlyLayout verifiedRootLayout(
            ExactValue exactRoot,
            EmbeddedLayoutPlan plan) {
        metrics.increment("layout.verifiedClosureRootsRetained");
        return new EmbeddedOnlyLayout(
                exactRoot,
                exactRoot.frozen(),
                Map.of(JsonPointer.ROOT, exactRoot),
                List.of(),
                List.of(),
                plan);
    }
    private EmbeddedOnlyLayout rebuild(
            ExactValue exactRoot,
            EmbeddedOnlyLayout previous,
            boolean managedChildRevision) {
        Objects.requireNonNull(exactRoot, "exactRoot");
        Objects.requireNonNull(previous, "previous");
        if (!previous.plan().reusableFor(
                path -> exactScopeAt(exactRoot, path))) {
            metrics.increment("layout.plansRecompiledAfterContractChange");
            return build(exactRoot);
        }
        metrics.increment("layout.plansReused");
        List<ConcreteBoundary> concreteBoundaries = managedChildRevision
                && previous.plan().hasCollections()
                ? concreteFromOwnedCatalog(exactRoot, previous)
                : previous.plan()
                .hasCollections()
                ? concreteFromCurrentCatalog(exactRoot)
                : concreteFromFixedDeclarations(exactRoot, previous.plan(),
                managedChildRevision ? previous.directOccurrences() : List.of());
        return buildWithPlan(exactRoot, previous.plan(), concreteBoundaries);
    }
    private List<ConcreteBoundary> concreteFromOwnedCatalog(
            ExactValue exactRoot,
            EmbeddedOnlyLayout previous) {
        return metrics.timed("layout.refreshOwnedCatalog", () -> {
            FrozenNode opaque = exactRoot.frozen();
            for (EmbeddedOccurrence occurrence : previous.directOccurrences()) {
                List<String> path = JsonPointer.split(occurrence.scopePath());
                FrozenNode child = resolveThroughReferences(opaque, path);
                if (child != null) {
                    opaque = replaceAt(opaque, path,
                            managedOwnershipProjection(child.toNode()));
                }
            }
            ExactValue validationRoot = objects.put(
                    opaque, "owned-membership-validation-root");
            metrics.increment("layout.referenceOnlyCatalogInputs");
            metrics.increment("layout.ownedMembershipRefreshes");
            EffectiveFragmentationCatalog catalog =
                    runtime.effectiveFragmentationCatalog(
                            validationRoot.blueId());
            metrics.increment("layout.catalogCompilations");
            return concreteFromCatalog(catalog);
        });
    }
    private List<ConcreteBoundary> concreteFromCurrentCatalog(
            ExactValue exactRoot) {
        return metrics.timed("layout.refreshCollectionCatalog", () -> {
            metrics.increment("layout.referenceOnlyCatalogInputs");
            EffectiveFragmentationCatalog catalog =
                    runtime.effectiveFragmentationCatalog(exactRoot.blueId());
            metrics.increment("layout.catalogCompilations");
            metrics.increment("layout.collectionCatalogRefreshes");
            return concreteFromCatalog(catalog);
        });
    }
    public ExactValue restoreManagedChildren(
            Node processedRoot,
            EmbeddedOnlyLayout previous,
            boolean processorManagedRevision) {
        FrozenNode restored = FrozenNode.fromNode(Objects.requireNonNull(
                processedRoot, "processedRoot"));
        for (EmbeddedOccurrence occurrence : previous.directOccurrences()) {
            List<String> path = JsonPointer.split(occurrence.scopePath());
            FrozenNode processedChild = restored.at(occurrence.scopePath());
            FrozenNode executionChild = previous.processingFrozen()
                    .at(occurrence.scopePath());
            if (executionChild == null) {
                throw new IllegalStateException(
                        "Missing managed PROCESS boundary at "
                                + occurrence.scopePath());
            }
            if (processedChild == null) {
                metrics.increment("layout.managedChildrenRemoved");
                continue;
            }
            if (processedChild.blueId().equals(executionChild.blueId())) {
                restored = replaceAt(restored, path,
                        occurrence.suppliedState().frozen());
                metrics.increment("layout.managedChildrenRestored");
                continue;
            }
            if (processorManagedRevision) {
                metrics.increment(
                        "layout.processorManagedChildUpdatesAccepted");
                continue;
            }
            ExactValue proposed = ExactValue.fromFrozen(
                    materializeReference(processedChild));
            DocumentId proposedId = DocumentIdentityReader.requireDocumentId(
                    proposed);
            if (occurrence.childDocumentId().equals(proposedId)) {
                metrics.increment(
                        "layout.externalManagedChildMutationsRejected");
                throw new IllegalStateException(
                        "External parent operation attempted to mutate managed "
                                + "child " + proposedId + " at "
                                + occurrence.scopePath());
            }
            metrics.increment("layout.managedChildrenReplaced");
        }
        return objects.put(restored, "document-revision");
    }
    public ExactValue restoreAuthoredChildrenAfterInitialization(
            Node initializedRoot,
            EmbeddedOnlyLayout authoredLayout) {
        FrozenNode restored = FrozenNode.fromNode(Objects.requireNonNull(
                initializedRoot, "initializedRoot"));
        for (EmbeddedOccurrence occurrence
                : authoredLayout.directOccurrences()) {
            List<String> path = JsonPointer.split(occurrence.scopePath());
            if (restored.at(occurrence.scopePath()) == null) {
                throw new IllegalStateException(
                        "Initialization removed Process Embedded child at "
                                + occurrence.scopePath());
            }
            restored = replaceAt(
                    restored, path, occurrence.suppliedState().frozen());
            metrics.increment("layout.initializationOwnershipRestorations");
        }
        return objects.put(restored, "initialized-document");
    }
    public ResolvedSnapshot isolateManagedInitializationScopes(
            ResolvedSnapshot snapshot,
            EmbeddedOnlyLayout authoredLayout) {
        Objects.requireNonNull(snapshot, "snapshot");
        Node resolvedNode = snapshot.resolvedRoot();
        for (String scopePath : authoredLayout.plan()
                .rulesByScope().keySet()) {
            Node scope = nodeAt(resolvedNode, scopePath);
            Node contracts = scope != null ? scope.getContracts() : null;
            if (contracts == null || contracts.getProperties() == null) {
                continue;
            }
            contracts.getProperties().entrySet().removeIf(entry -> {
                FrozenNode contract = FrozenNode.fromResolvedNode(
                        entry.getValue());
                FrozenNode type = contract.getType();
                return type != null
                        && (RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                        type.getReferenceBlueId())
                        || RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                        type.blueId()));
            });
        }
        FrozenNode resolved = FrozenNode.fromResolvedNode(resolvedNode);
        for (EmbeddedOccurrence occurrence
                : authoredLayout.directOccurrences()) {
            FrozenNode opaque = authoredLayout.processingFrozen().at(
                    occurrence.scopePath());
            if (opaque == null) {
                throw new IllegalStateException(
                        "Missing managed initialization scope at "
                                + occurrence.scopePath());
            }
            resolved = replaceAt(
                    resolved,
                    JsonPointer.split(occurrence.scopePath()),
                    opaque);
        }
        return new ResolvedSnapshot(
                snapshot.frozenCanonicalRoot(),
                resolved,
                snapshot.blueId());
    }
    private static Node nodeAt(Node root, String pointer) {
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            Map<String, Node> properties = current != null
                    ? current.getProperties() : null;
            current = properties != null ? properties.get(segment) : null;
        }
        return current;
    }
    private EmbeddedOnlyLayout buildWithPlan(
            ExactValue suppliedRoot,
            EmbeddedLayoutPlan plan,
            List<ConcreteBoundary> concreteBoundaries) {
        return metrics.timed("layout.retainEmbeddedOnly", () -> {
            FrozenNode materializedRoot = materializeDeclaredChildren(
                    suppliedRoot.frozen(), concreteBoundaries);
            ExactValue semanticRoot = objects.put(
                    materializedRoot, "document-semantic-root");
            if (!suppliedRoot.blueId().equals(semanticRoot.blueId())) {
                throw new IllegalStateException(
                        "Process Embedded materialization changed Root identity");
            }
            objects.preferCanonicalRepresentation(
                    materializedRoot, "document-semantic-root");
            Set<String> scopePaths = new LinkedHashSet<>();
            scopePaths.add(JsonPointer.ROOT);
            for (ConcreteBoundary boundary : concreteBoundaries) {
                scopePaths.add(boundary.childPath());
            }
            Map<String, ExactValue> exactByScope = new LinkedHashMap<>();
            for (String scopePath : depthOrdered(scopePaths, false)) {
                FrozenNode selected = selectMaterialized(
                        materializedRoot, scopePath);
                exactByScope.put(
                        scopePath,
                        objects.put(selected, "managed-document-exact"));
            }
            Map<String, ExactValue> shellsByScope = new LinkedHashMap<>();
            List<EmbeddedBoundary> boundaries = new ArrayList<>();
            for (String scopePath : depthOrdered(scopePaths, true)) {
                FrozenNode exactScope = exactByScope.get(scopePath).frozen();
                FrozenNode shell = exactScope;
                for (ConcreteBoundary boundary : concreteBoundaries) {
                    if (!boundary.parentPath().equals(scopePath)) {
                        continue;
                    }
                    ExactValue child = exactByScope.get(
                            boundary.childPath());
                    if (child == null) {
                        throw new IllegalStateException(
                                "No exact Process Embedded child at "
                                        + boundary.childPath());
                    }
                    String relative = PointerUtils.relativizePointer(
                            scopePath, boundary.childPath());
                    FrozenNode authoredChild = exactScope.at(relative);
                    if (authoredChild == null) {
                        throw new IllegalStateException(
                                "Process Embedded child disappeared at "
                                        + boundary.childPath());
                    }
                    boolean cutCreated = !authoredChild.isReferenceOnly();
                    shell = replaceAt(
                            shell,
                            JsonPointer.split(relative),
                            pureReference(child.blueId()));
                    boundaries.add(new EmbeddedBoundary(
                            scopePath,
                            boundary.childPath(),
                            child.blueId(),
                            boundary.origin(),
                            cutCreated));
                }
                ExactValue stored = objects.put(
                        shell, "managed-document-shell");
                objects.preferProviderRepresentation(
                        shell, "managed-document-shell");
                if (!exactByScope.get(scopePath).blueId()
                        .equals(stored.blueId())) {
                    throw new IllegalStateException(
                            "Embedded-only cut changed semantic identity at "
                                    + scopePath);
                }
                shellsByScope.put(scopePath, stored);
            }
            Map<String, ExactValue> rootFirst = new LinkedHashMap<>();
            depthOrdered(shellsByScope.keySet(), false).forEach(path ->
                    rootFirst.put(path, shellsByScope.get(path)));
            boundaries.sort(Comparator.comparing(
                    EmbeddedBoundary::parentScopePath,
                    EmbeddingBinding.TEXT_ORDER).thenComparing(
                    EmbeddedBoundary::childScopePath,
                    EmbeddingBinding.TEXT_ORDER));
            List<EmbeddedOccurrence> directOccurrences = directOccurrences(
                    exactByScope, boundaries);
            FrozenNode processingRoot = materializedRoot;
            for (EmbeddedOccurrence occurrence : directOccurrences) {
                processingRoot = replaceAt(
                        processingRoot,
                        JsonPointer.split(occurrence.scopePath()),
                        managedOwnershipProjection(
                                occurrence.suppliedState().copyNode()));
            }
            objects.put(processingRoot, "processing-ownership-view");
            metrics.increment("layout.managedOwnershipViewsBuilt");

            metrics.add("layout.embeddedDocuments",
                    Math.max(0L, rootFirst.size() - 1L));
            metrics.add("layout.processEmbeddedCuts", boundaries.stream()
                    .filter(EmbeddedBoundary::physicalCutCreated).count());
            metrics.increment("layout.semanticRootsRetained");
            return new EmbeddedOnlyLayout(
                    semanticRoot,
                    processingRoot,
                    rootFirst,
                    boundaries,
                    directOccurrences,
                    plan);
        });
    }
    private FrozenNode materializeDeclaredChildren(
            FrozenNode suppliedRoot,
            List<ConcreteBoundary> boundaries) {
        FrozenNode result = suppliedRoot;
        for (ConcreteBoundary boundary : boundaries.stream()
                .sorted(Comparator.comparingInt(
                        value -> JsonPointer.split(value.childPath()).size()))
                .toList()) {
            FrozenNode child = resolveThroughReferences(
                    result, JsonPointer.split(boundary.childPath()));
            if (child == null) {
                throw new IllegalStateException(
                        "Process Embedded scope is absent at "
                                + boundary.childPath());
            }
            child = canonicalManagedBody(child);
            result = replaceAt(
                    result,
                    JsonPointer.split(boundary.childPath()),
                    child);
        }
        return result;
    }

    private FrozenNode canonicalManagedBody(FrozenNode selected) {
        String identity = selected.isReferenceOnly()
                ? selected.getReferenceBlueId()
                : selected.blueId();
        return objects.contains(identity)
                ? objects.require(identity).frozen()
                : selected;
    }
    private FrozenNode resolveThroughReferences(
            FrozenNode root,
            List<String> segments) {
        FrozenNode current = root;
        for (String segment : segments) {
            current = materializeReference(current);
            if (current == null) {
                return null;
            }
            if (current.getProperties() != null) {
                current = current.getProperties().get(segment);
            } else if (current.getItems() != null) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException failure) {
                    return null;
                }
                current = index >= 0 && index < current.getItems().size()
                        ? current.getItems().get(index)
                        : null;
            } else {
                return null;
            }
        }
        return materializeReference(current);
    }
    private FrozenNode exactScopeAt(ExactValue root, String path) {
        return resolveThroughReferences(
                root.frozen(), JsonPointer.split(path));
    }
    private FrozenNode materializeReference(FrozenNode value) {
        if (value == null || !value.isReferenceOnly()) {
            return value;
        }
        return objects.require(value.getReferenceBlueId()).frozen();
    }
    private List<ConcreteBoundary> concreteFromFixedDeclarations(
            ExactValue exactRoot,
            EmbeddedLayoutPlan plan,
            List<EmbeddedOccurrence> opaqueScopes) {
        List<ConcreteBoundary> result = new ArrayList<>();
        for (EmbeddedLayoutPlan.ScopeRule rule
                : plan.rulesByScope().values()) {
            if (opaqueScopes.stream().anyMatch(occurrence ->
                    PointerUtils.descendantOrEqual(
                            rule.scopePath(), occurrence.scopePath()))) {
                continue;
            }
            for (String childPath : rule.explicitAbsolutePaths()) {
                FrozenNode selected = resolveThroughReferences(
                        exactRoot.frozen(), JsonPointer.split(childPath));
                if (selected != null) {
                    result.add(new ConcreteBoundary(
                            rule.scopePath(),
                            childPath,
                            EmbeddedScopePlanView.Origin.EXPLICIT));
                }
            }
        }
        return canonicalBoundaries(result);
    }
    private static List<ConcreteBoundary> concreteFromCatalog(
            EffectiveFragmentationCatalog catalog) {
        List<ConcreteBoundary> result = new ArrayList<>();
        for (Map.Entry<String, EmbeddedScopePlanView> entry
                : catalog.scopePlansByScope().entrySet()) {
            String parent = entry.getKey();
            EmbeddedScopePlanView view = entry.getValue();
            for (String child : view.concreteChildPaths()) {
                result.add(new ConcreteBoundary(
                        parent,
                        child,
                        Objects.requireNonNull(
                                view.originsByConcretePath().get(child),
                                "embedded origin for " + child)));
            }
        }
        return canonicalBoundaries(result);
    }
    private static List<ConcreteBoundary> canonicalBoundaries(
            Collection<ConcreteBoundary> source) {
        Map<String, ConcreteBoundary> unique = new LinkedHashMap<>();
        source.stream()
                .sorted(Comparator.comparing(
                        ConcreteBoundary::parentPath,
                        EmbeddingBinding.TEXT_ORDER).thenComparing(
                        ConcreteBoundary::childPath,
                        EmbeddingBinding.TEXT_ORDER))
                .forEach(boundary -> unique.putIfAbsent(
                        boundary.parentPath() + "|" + boundary.childPath(),
                        boundary));
        return List.copyOf(unique.values());
    }
    private static List<String> depthOrdered(
            Collection<String> paths,
            boolean deepestFirst) {
        Comparator<String> order = Comparator
                .comparingInt((String path) -> JsonPointer.split(path).size())
                .thenComparing(EmbeddingBinding.TEXT_ORDER);
        if (deepestFirst) {
            order = order.reversed();
        }
        return paths.stream().sorted(order).toList();
    }
    private static FrozenNode selectMaterialized(
            FrozenNode materializedRoot,
            String path) {
        FrozenNode selected = JsonPointer.ROOT.equals(path)
                ? materializedRoot
                : materializedRoot.at(path);
        if (selected == null) {
            throw new IllegalStateException("Missing exact scope " + path);
        }
        return selected;
    }
    private static FrozenNode replaceAt(
            FrozenNode root,
            List<String> segments,
            FrozenNode replacement) {
        if (segments.isEmpty()) {
            return Objects.requireNonNull(replacement, "replacement");
        }
        String head = segments.get(0);
        List<String> tail = segments.subList(1, segments.size());
        if (root.getProperties() != null) {
            FrozenNode child = root.getProperties().get(head);
            if (child == null) {
                throw new IllegalStateException(
                        "Cannot replace absent property " + head);
            }
            return root.withProperty(
                    head, replaceAt(child, tail, replacement));
        }
        if (root.getItems() != null) {
            int index;
            try {
                index = Integer.parseInt(head);
            } catch (NumberFormatException failure) {
                throw new IllegalStateException(
                        "List path segment is not an index: " + head,
                        failure);
            }
            if (index < 0 || index >= root.getItems().size()) {
                throw new IllegalStateException(
                        "List index out of range: " + index);
            }
            List<FrozenNode> items = new ArrayList<>(root.getItems());
            items.set(index, replaceAt(items.get(index), tail, replacement));
            return root.withItems(items);
        }
        throw new IllegalStateException(
                "Cannot descend through scalar path segment " + head);
    }

    private static FrozenNode pureReference(String blueId) {
        return FrozenNode.fromNode(new Node().blueId(
                Objects.requireNonNull(blueId, "blueId")));
    }

    private static FrozenNode managedOwnershipProjection(Node source) {
        Node result = new Node()
                .name(source.getName())
                .description(source.getDescription())
                .value(source.getRawValue())
                .inlineValue(source.isInlineValue());
        if (source.getItems() != null) {
            List<Node> items = new ArrayList<>(source.getItems().size());
            source.getItems().forEach(item ->
                    items.add(managedOwnershipProjection(item).toNode()));
            result.items(items);
        }
        if (source.getProperties() != null) {
            Map<String, Node> properties = new LinkedHashMap<>();
            source.getProperties().forEach((key, value) ->
                    properties.put(
                            key,
                            managedOwnershipProjection(value).toNode()));
            result.properties(properties);
        }
        return FrozenNode.fromNode(result);
    }

    private static List<EmbeddedOccurrence> directOccurrences(
            Map<String, ExactValue> exactByScope,
            List<EmbeddedBoundary> boundaries) {
        List<EmbeddedOccurrence> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EmbeddedBoundary boundary : boundaries) {
            if (!JsonPointer.ROOT.equals(boundary.parentScopePath())
                    || !seen.add(boundary.childScopePath())) {
                continue;
            }
            ExactValue child = exactByScope.get(
                    boundary.childScopePath());
            result.add(new EmbeddedOccurrence(
                    boundary.childScopePath(),
                    DocumentIdentityReader.requireDocumentId(child),
                    child));
        }
        result.sort(Comparator.comparing(
                EmbeddedOccurrence::scopePath, EmbeddingBinding.TEXT_ORDER));
        return List.copyOf(result);
    }

    private record ConcreteBoundary(
            String parentPath,
            String childPath,
            EmbeddedScopePlanView.Origin origin) {
        private ConcreteBoundary {
            parentPath = Objects.requireNonNull(parentPath, "parentPath");
            childPath = Objects.requireNonNull(childPath, "childPath");
            origin = Objects.requireNonNull(origin, "origin");
        }
    }
}
