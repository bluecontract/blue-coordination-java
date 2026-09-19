package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.Family;
import java.util.*;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/** Retained rooted topology and both pending-join locator directions. Exact snapshots remain authority. */
final class StoredTopologyIndexes {
    enum Root { COMPONENT, TARGET, SOURCE, JOIN_MEMBERS, JOIN_ROOTS }
    private final StoreIndexCodecs.Binding<DocumentId, ProcessEmbeddedComponentIndex.Component> components;
    private final StoreIndexCodecs.Binding<DocumentId, Boolean> members;
    private final StoreIndexCodecs.Binding<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> targets, sources, joinMembers, joinRoots;

    StoredTopologyIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        var c = new StoreIndexCodecs(objects, limits);
        components = c.binding("topology/component", EmbeddingBinding.DOCUMENT_ORDER, c.documents, c.components);
        members = c.binding("topology/member-bucket", EmbeddingBinding.DOCUMENT_ORDER, c.documents, c.membership);
        targets = c.binding("topology/targets", EmbeddingBinding.DOCUMENT_ORDER, c.documents, members.nested());
        sources = c.binding("topology/sources", EmbeddingBinding.DOCUMENT_ORDER, c.documents, members.nested());
        joinMembers = c.binding("topology/join-members", EmbeddingBinding.DOCUMENT_ORDER, c.documents, members.nested());
        joinRoots = c.binding("topology/join-roots", EmbeddingBinding.DOCUMENT_ORDER, c.documents, members.nested());
    }

    ProcessEmbeddedComponentIndex openLogical(LogicalRecordContext context) {
        var scope = LogicalRecordContext.runtimeScope();
        var joins = RootedJoinCandidateIndex.restoreIndexes(new RootedJoinCandidateIndex.StoredIndexes(
                logicalMembers(context, Family.TOPOLOGY_JOIN_MEMBER), logicalMembers(context, Family.TOPOLOGY_JOIN_ROOT)));
        return ProcessEmbeddedComponentIndex.restoreIndexes(new ProcessEmbeddedComponentIndex.StoredIndexes(
                components.openLogical(context, Family.TOPOLOGY_COMPONENT, scope, OrderedRecordKey.document()),
                logicalMembers(context, Family.TOPOLOGY_TARGET), logicalMembers(context, Family.TOPOLOGY_SOURCE), true, joins));
    }

    private PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> logicalMembers(LogicalRecordContext context, Family family) {
        return members.openLogicalBuckets(context, family, LogicalRecordContext.runtimeScope(), EmbeddingBinding.DOCUMENT_ORDER,
                OrderedRecordKey.document(), OrderedRecordKey.document());
    }

    void selectLogical(ProcessEmbeddedComponentIndex value) {
        var s = value.storedIndexes(); s.components().selectLogicalRecords(); s.targets().selectLogicalRecords(); s.sources().selectLogicalRecords();
        s.joins().storedIndexes().members().selectLogicalRecords(); s.joins().storedIndexes().roots().selectLogicalRecords();
    }

    ProcessEmbeddedComponentIndex retainPartition(ProcessEmbeddedComponentIndex value) {
        return physical(() -> {
            var s = value.storedIndexes(); var joins = s.joins().storedIndexes();
            return ProcessEmbeddedComponentIndex.restoreIndexes(new ProcessEmbeddedComponentIndex.StoredIndexes(components.retain(s.components()),
                    targets.retain(s.targets()), sources.retain(s.sources()), s.rootedViews(), RootedJoinCandidateIndex.restoreIndexes(
                    new RootedJoinCandidateIndex.StoredIndexes(joinMembers.retain(joins.members()), joinRoots.retain(joins.roots())))));
        });
    }
    ProcessEmbeddedComponentIndex open(Function<Root, byte[]> selected, boolean rootedViews) {
        return physical(() -> {
            var joins = RootedJoinCandidateIndex.restoreIndexes(new RootedJoinCandidateIndex.StoredIndexes(
                    joinMembers.open(selected.apply(Root.JOIN_MEMBERS)), joinRoots.open(selected.apply(Root.JOIN_ROOTS))));
            return ProcessEmbeddedComponentIndex.restoreIndexes(new ProcessEmbeddedComponentIndex.StoredIndexes(
                    components.open(selected.apply(Root.COMPONENT)), targets.open(selected.apply(Root.TARGET)), sources.open(selected.apply(Root.SOURCE)),
                    rootedViews, joins));
        });
    }
    byte[] root(ProcessEmbeddedComponentIndex value, Root root) {
        var s = value.storedIndexes(); return switch (root) {
            case COMPONENT -> s.components().storedRootDescriptor(); case TARGET -> s.targets().storedRootDescriptor();
            case SOURCE -> s.sources().storedRootDescriptor(); case JOIN_MEMBERS -> s.joins().storedIndexes().members().storedRootDescriptor();
            case JOIN_ROOTS -> s.joins().storedIndexes().roots().storedRootDescriptor();
        };
    }

    Optional<ProcessEmbeddedComponentIndex.Component> component(ProcessEmbeddedComponentIndex value, DocumentId document) {
        return component(value, document, false);
    }

    /**
     * A controlled logical writer replaces an owner's outgoing edges and the corresponding
     * reverse points atomically. Reading its component does not semantically enumerate
     * other consumers. Selected outgoing edges still authenticate their reverse points;
     * explicit incoming traversal retains its own complete predicate and member checks.
     * Raw/import storage keeps the complete bidirectional integrity check.
     */
    Optional<ProcessEmbeddedComponentIndex.Component> component(ProcessEmbeddedComponentIndex value, DocumentId document,
            boolean controlledLogical) {
        return physical(() -> {
            var state = value.storedIndexes(); var row = state.components().get(document);
            if (row == null) {
                require(empty(state.targets().get(document)) && empty(state.sources().get(document)),
                        "Absent topology owner has retained edges");
                return Optional.empty();
            }
            require(row.members().contains(document), "Selected topology component has foreign owner");
            for (var member : row.members()) require(row.equals(state.components().get(member)), "Selected topology component membership differs");
            requireEdges(state.targets(), state.sources(), state.components(), document);
            if (!controlledLogical) requireEdges(state.sources(), state.targets(), state.components(), document);
            return Optional.of(row);
        });
    }

    /** One selected original snapshot proves the forward locator; no source graph or SCC is reconstructed. */
    void requireJoinRoot(ProcessEmbeddedComponentIndex value, DocumentId owner, RootedDocumentView retainedView) {
        physical(() -> {
            require(retainedView == null || retainedView.storedState().publishedHeads().containsKey(owner), "Selected join view was not published by its owner");
            var joins = value.storedIndexes().joins().storedIndexes(); var expected = retainedView == null ? Set.<DocumentId>of()
                    : RootedJoinEligibility.candidateMembers(retainedView.snapshot(), owner);
            var members = joins.members().get(owner);
            require(new LinkedHashSet<>(members == null ? List.<DocumentId>of() : members.keys()).equals(expected),
                    "Selected pending-join locator differs from retained root snapshot");
            for (var member : expected) require(contains(joins.roots(), member, owner), "Pending-join reverse membership is missing");
            return true;
        });
    }

    List<DocumentId> pendingRoots(ProcessEmbeddedComponentIndex value, DocumentId member, Function<DocumentId, RootedDocumentView> selectedViews) {
        return physical(() -> {
            var joins = value.storedIndexes().joins().storedIndexes(); var roots = joins.roots().get(member);
            if (roots == null) return List.of();
            var result = roots.keys();
            for (var root : result) {
                require(contains(joins.members(), root, member), "Pending-join forward membership is missing");
                var view = selectedViews.apply(root);
                require(view != null, "Pending-join locator has no exact source view");
                requireJoinRoot(value, root, view);
            }
            return result;
        });
    }

    private static boolean empty(PersistentOrderedMap<?, ?> bucket) { return bucket == null || bucket.isEmpty(); }

    private static void requireEdges(PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> forward,
            PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> reverse,
            PersistentOrderedMap<DocumentId, ProcessEmbeddedComponentIndex.Component> components, DocumentId owner) {
        var rows = forward.get(owner); if (rows == null) return;
        for (var target : rows.keys()) require(components.get(target) != null && contains(reverse, target, owner), "Selected topology reverse edge is missing");
    }
    private static boolean contains(PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> map, DocumentId key, DocumentId member) {
        var bucket = map.get(key); return bucket != null && Boolean.TRUE.equals(bucket.get(member));
    }
}
