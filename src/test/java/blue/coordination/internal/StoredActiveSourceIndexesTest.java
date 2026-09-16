package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.NoncommittingExecutionException;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.StoredRouteIndexesTest.*;

final class StoredActiveSourceIndexesTest {
    @Test void coldDynamicAdmissionSharedMembershipRemovalAndCountersMatchResident() {
        var bytes = new Bytes(); var storage = new StoredActiveSourceIndexes(bytes, LIMITS);
        var aMetrics = new EngineMetrics(); var bMetrics = new EngineMetrics();
        var a = new ContractsActiveSourceTimelineIndex(List.of(id(1)), aMetrics);
        var b = new ContractsActiveSourceTimelineIndex(List.of(id(1)), bMetrics);
        var inventory = ManagedOccurrenceInventory.of(List.of(edge(1, 3)));
        var timelines = new HashMap<DocumentId, List<String>>();
        timelines.put(id(1), List.of("timeline/1")); timelines.put(id(2), List.of("timeline/2"));
        timelines.put(id(3), List.of("timeline/shared"));
        a.refresh(List.of(id(1)), inventory, timelines::get); b.refresh(List.of(id(1)), inventory, timelines::get);
        b = storage.retainPartition(b, bMetrics); var before = roots(storage, b);
        b = new StoredActiveSourceIndexes(bytes.fresh(), LIMITS).open(before::get, bMetrics);
        Set<String> oldSnapshot = b.timelineIds();
        a.addPublicRoots(List.of(id(2))); b.addPublicRoots(List.of(id(2)));
        inventory = inventory.replaceSources(List.of(id(2)), List.of(edge(2, 3))).inventory();
        a.refresh(List.of(id(2)), inventory, timelines::get); b.refresh(List.of(id(2)), inventory, timelines::get);
        assertEquals(2L, b.storedIndexes().timelineReferences().get("timeline/shared"));
        assertEquals(a.timelineIds(), b.timelineIds());
        assertEquals(aMetrics.snapshot().counters(), bMetrics.snapshot().counters());
        timelines.put(id(3), List.of("timeline/changed"));
        a.refresh(List.of(id(3)), inventory, timelines::get); b.refresh(List.of(id(3)), inventory, timelines::get);
        assertEquals(a.timelineIds(), b.timelineIds());
        assertEquals(Set.of("timeline/1", "timeline/shared"), oldSnapshot);
        assertThrows(UnsupportedOperationException.class, () -> oldSnapshot.remove("timeline/1"));
        for (int root : List.of(1, 2)) {
            inventory = inventory.replaceSources(List.of(id(root)), List.of()).inventory();
            a.refresh(List.of(id(root)), inventory, timelines::get); b.refresh(List.of(id(root)), inventory, timelines::get);
            assertEquals(a.timelineIds(), b.timelineIds());
            assertEquals(aMetrics.snapshot().counters(), bMetrics.snapshot().counters());
        }
        assertEquals(Set.of("timeline/1", "timeline/2"), b.timelineIds());
        assertNull(b.storedIndexes().timelineReferences().get("timeline/changed"));
        assertEquals(a.storedIndexes().memberships().entries(), b.storedIndexes().memberships().entries());
        // Existing refresh removes a Root's last Timeline contribution; there is
        // no new public-root deregistration policy or API in this component.
        timelines.put(id(1), List.of());
        a.refresh(List.of(id(1)), inventory, timelines::get); b.refresh(List.of(id(1)), inventory, timelines::get);
        assertEquals(Set.of("timeline/2"), b.timelineIds());
        assertEquals(aMetrics.snapshot().counters(), bMetrics.snapshot().counters());
    }

    @Test void coldOpenAndSelectedMembershipRefreshDoNotResolveOtherRoots() {
        var bytes = new Bytes(); var storage = new StoredActiveSourceIndexes(bytes, LIMITS);
        var ids = new ArrayList<DocumentId>(); for (int i = 0; i < 127; i++) ids.add(id(i));
        var index = new ContractsActiveSourceTimelineIndex(ids);
        index.refresh(ids, ManagedOccurrenceInventory.empty(), document -> List.of("timeline/" + document.value()));
        var stored = storage.retainPartition(index, new EngineMetrics()); var roots = roots(storage, stored);
        var coldBytes = bytes.fresh(); var cold = new StoredActiveSourceIndexes(coldBytes, LIMITS);
        var metrics = new EngineMetrics(); var opened = cold.open(roots::get, metrics);
        assertEquals(4, coldBytes.reads, "only the four root metadata nodes");
        assertTrue(opened.timelineIds().contains("timeline/document-63"));
        assertEquals(0, metrics.counter("sourceSurface.rootsResolved"));
        assertEquals(0, metrics.counter("sourceSurface.documentsResolved"));
        var resolved = new ArrayList<DocumentId>();
        opened.refresh(List.of(id(63)), ManagedOccurrenceInventory.empty(), document -> {
            resolved.add(document); return List.of("timeline/new");
        });
        assertEquals(List.of(id(63)), resolved);
        assertEquals(1, metrics.counter("sourceSurface.rootsResolved"));
        assertEquals(1, metrics.counter("sourceSurface.documentsResolved"));
        assertTrue(opened.timelineIds().contains("timeline/new"));
        assertFalse(opened.timelineIds().contains("timeline/document-63"));
        assertTrue(coldBytes.reads < 250, "point paths, not all stored surfaces");
        assertEquals(127, opened.timelineIds().size());
    }

    @Test void physicalFailureKeepsAllFourRootsAndPriorUnionUnchanged() {
        var bytes = new Bytes(); var storage = new StoredActiveSourceIndexes(bytes, LIMITS);
        var index = new ContractsActiveSourceTimelineIndex(List.of(id(1), id(2)));
        var inventory = ManagedOccurrenceInventory.of(List.of(edge(1, 3), edge(2, 3)));
        index.refresh(List.of(id(1), id(2)), inventory, document -> List.of("timeline/" + document.value()));
        var stored = storage.retainPartition(index, new EngineMetrics()); var roots = roots(storage, stored);
        Set<String> previous = stored.timelineIds();
        bytes.failWriteAt = bytes.writes + 3;
        assertThrows(NoncommittingExecutionException.class, () -> stored.refresh(List.of(id(3)), inventory,
                document -> List.of("changed/" + document.value())));
        sameRoots(roots, roots(storage, stored)); assertEquals(previous, stored.timelineIds());
        bytes.failWriteAt = -1;
        stored.refresh(List.of(id(3)), inventory, document -> List.of("changed/" + document.value()));
        assertEquals(Set.of("changed/document-1", "changed/document-2", "changed/document-3"), stored.timelineIds());
        assertEquals(Set.of("timeline/document-1", "timeline/document-2", "timeline/document-3"), previous);
    }

    @Test void missingWrongCorruptedRootsAndOversizedRowsCannotBecomeEmptyUnion() {
        var bytes = new Bytes(); var storage = new StoredActiveSourceIndexes(bytes, LIMITS);
        var index = new ContractsActiveSourceTimelineIndex(List.of(id(1)));
        index.refresh(List.of(id(1)), ManagedOccurrenceInventory.empty(), ignored -> List.of("timeline/1"));
        var stored = storage.retainPartition(index, new EngineMetrics()); var roots = roots(storage, stored);
        assertThrows(NoncommittingExecutionException.class, () -> storage.open(ignored -> null, new EngineMetrics()));
        assertThrows(NoncommittingExecutionException.class, () -> storage.open(ignored -> roots.get(StoredActiveSourceIndexes.Root.PUBLIC_ROOTS), new EngineMetrics()));
        var absent = new StoredActiveSourceIndexes(new Bytes(), LIMITS);
        assertThrows(NoncommittingExecutionException.class, () -> absent.open(roots::get, new EngineMetrics()));
        var damaged = bytes.fresh(); damaged.values.replaceAll((key, value) -> { var copy = value.clone(); copy[0] ^= 1; return copy; });
        var corrupt = new StoredActiveSourceIndexes(damaged, LIMITS);
        assertThrows(NoncommittingExecutionException.class, () -> corrupt.open(roots::get, new EngineMetrics()));
        assertThrows(NoncommittingExecutionException.class, () -> stored.refresh(List.of(id(1)), ManagedOccurrenceInventory.empty(),
                ignored -> List.of("x".repeat(LIMITS.valueBytes()))));
        sameRoots(roots, roots(storage, stored)); assertEquals(Set.of("timeline/1"), stored.timelineIds());
    }

    private static ManagedOccurrenceBinding edge(int from, int to) {
        return ManagedOccurrenceBinding.derived("sha256:" + "c".repeat(64),
                new blue.language.processor.closure.DocumentId(id(from).value()),
                ScopeAddress.embedded("/child", 1L), new blue.language.processor.closure.DocumentId(id(to).value()),
                "8M3d43KXskYr7rrdiaXPiPHmypFEjtU4uUyECtU7Tiyx", true, null);
    }
    private static EnumMap<StoredActiveSourceIndexes.Root, byte[]> roots(StoredActiveSourceIndexes storage, ContractsActiveSourceTimelineIndex index) {
        var result = new EnumMap<StoredActiveSourceIndexes.Root, byte[]>(StoredActiveSourceIndexes.Root.class);
        for (var root : StoredActiveSourceIndexes.Root.values()) result.put(root, storage.root(index, root)); return result;
    }
}
