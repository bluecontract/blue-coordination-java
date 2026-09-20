package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.language.processor.NoncommittingExecutionException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.StoredRouteIndexesTest.*;

final class StoredTopologyConditionTest {
    private static final blue.coordination.api.storage.CoordinationRecords.Bytes EVIDENCE =
            new blue.coordination.api.storage.CoordinationRecords.Bytes(new byte[] {1});

    @Test void selectedSourceComponentSurvivesAnotherIncomingConsumerButCompleteTraversalDoesNot() {
        // given
        var records = new LogicalRecordMapTest.Store(); var storage = new StoredTopologyIndexes(new StoredRouteIndexesTest.Bytes(), LIMITS);
        seed(records, storage);
        Publication selected, incoming, strict;
        try (var attempt = records.attempt()) {
            var graph = storage.openLogical(new LogicalRecordContext(attempt));
            assertEquals(List.of(id(1)), storage.component(graph, id(1), true).orElseThrow().members());
            selected = attempt.prepare("selected", List.of(), EVIDENCE);
        }
        try (var attempt = records.attempt()) {
            var graph = storage.openLogical(new LogicalRecordContext(attempt));
            assertTrue(graph.sources(graph.component(id(1))).isEmpty());
            incoming = attempt.prepare("incoming-query", List.of(), EVIDENCE);
        }
        try (var attempt = records.attempt()) {
            var graph = storage.openLogical(new LogicalRecordContext(attempt));
            assertTrue(storage.component(graph, id(1)).isPresent());
            strict = attempt.prepare("uncontrolled-check", List.of(), EVIDENCE);
        }
        // when
        addEdge(records, storage, id(2), id(1), true);
        // then
        assertTrue(records.publish(selected));
        assertFalse(records.publish(incoming), "An actual incoming traversal must retain phantom protection");
        assertFalse(records.publish(strict), "Uncontrolled validation still authenticates complete incoming membership");
        assertTrue(selected.queries().stream().noneMatch(q -> q.range().family() == Family.TOPOLOGY_SOURCE));
    }

    @Test void selectedOutgoingEdgeStillRequiresItsReversePointAndValidComponent() {
        // given
        var records = new LogicalRecordMapTest.Store(); var storage = new StoredTopologyIndexes(new StoredRouteIndexesTest.Bytes(), LIMITS);
        seed(records, storage);
        // when
        addEdge(records, storage, id(2), id(1), false);
        try (var attempt = records.attempt()) {
            var graph = storage.openLogical(new LogicalRecordContext(attempt));
            // then
            assertThrows(NoncommittingExecutionException.class, () -> storage.component(graph, id(2), true));
        }
    }

    @Test void incomingCorruptionIsStillDetectedByUncontrolledCheck() {
        // given
        var records = new LogicalRecordMapTest.Store(); var storage = new StoredTopologyIndexes(new StoredRouteIndexesTest.Bytes(), LIMITS);
        seed(records, storage);
        // when
        try (var attempt = records.attempt()) {
            var context = new LogicalRecordContext(attempt); var graph = storage.openLogical(context); var s = graph.storedIndexes();
            var bucket = s.sources().get(id(1));
            s.sources().put(id(1), bucket.put(id(2), true).map()).map().selectLogicalRecords();
            context.flush(); assertTrue(records.publish(attempt.prepare("corrupt-incoming", List.of(), EVIDENCE)));
        }
        try (var attempt = records.attempt()) {
            var graph = storage.openLogical(new LogicalRecordContext(attempt));
            // then
            assertThrows(NoncommittingExecutionException.class, () -> storage.component(graph, id(1)));
        }
    }

    private static void seed(LogicalRecordMapTest.Store records, StoredTopologyIndexes storage) {
        try (var attempt = records.attempt()) {
            var context = new LogicalRecordContext(attempt); var graph = storage.openLogical(context);
            var components = graph.storedIndexes().components();
            for (int number : List.of(1, 2, 3))
                components = components.put(id(number), new ProcessEmbeddedComponentIndex.Component(List.of(id(number)), false)).map();
            components.selectLogicalRecords(); context.flush();
            assertTrue(records.publish(attempt.prepare("seed", List.of(), EVIDENCE)));
        }
    }

    private static void addEdge(LogicalRecordMapTest.Store records, StoredTopologyIndexes storage,
            DocumentId from, DocumentId to, boolean reverse) {
        try (var attempt = records.attempt()) {
            var context = new LogicalRecordContext(attempt); var s = storage.openLogical(context).storedIndexes();
            s.targets().put(from, s.targets().get(from).put(to, true).map()).map().selectLogicalRecords();
            if (reverse) s.sources().put(to, s.sources().get(to).put(from, true).map()).map().selectLogicalRecords();
            context.flush(); assertTrue(records.publish(attempt.prepare("edge", List.of(), EVIDENCE)));
        }
    }
}
