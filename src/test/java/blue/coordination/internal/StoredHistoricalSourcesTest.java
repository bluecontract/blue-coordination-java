package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.coordination.sdk.ActivationPolicy;
import blue.language.processor.ExternalOrderKey;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real source publications, cold bytes, bounded conflicts and abandoned host windows. */
final class StoredHistoricalSourcesTest {
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});
    private static final PersistentMapStorage.Limits MAPS = RootedEngineStorageTest.LIMITS.indexes();

    @Test void historicalPacketSurvivesLaterSourcePublicationAndUsesTheFullExclusiveOrder() throws Exception {
        try (var f = new Scenario()) {
            f.publish("admit");
            var first = f.process(); f.publish("first");
            var second = f.append();
            var cutoff = f.order(second.blueId());
            Publication held;
            try (var read = f.read()) {
                assertEquals(1, read.sources.before(f.id, cutoff).orElseThrow().epoch());
                assertEquals(0, read.sources.before(f.id, first).orElseThrow().epoch(), "The exact boundary is excluded");
                var after = new ArrayList<Object>(first.components());
                after.set(after.size() - 1, after.get(after.size() - 1).toString() + "~");
                var sameTimestampLater = ExternalOrderKey.of(after);
                assertTrue(first.compareTo(sameTimestampLater) < 0);
                assertEquals(1, read.sources.before(f.id, sameTimestampLater).orElseThrow().epoch());
                held = read.attempt.prepare("historical-reader", List.of(), EVIDENCE);
                assertTrue(held.points().stream().noneMatch(p -> p.key().family() == Family.SESSION));
            }
            f.fixture.process(f.root, second); f.publish("second");
            assertTrue(f.records.publish(held), "A source publication at/after the cutoff cannot invalidate its earlier prefix");
            try (var cold = f.read()) {
                assertEquals(1, cold.sources.before(f.id, cutoff).orElseThrow().epoch());
                assertEquals(0, cold.sources.admission(f.id).orElseThrow().epoch());
                var lineage = cold.sources.lineages(cutoff, ManagedLineageIndex.empty(), Set.of()).byDocumentId(f.id);
                assertEquals(1, lineage.currentEpoch());
            }
        }
    }

    @Test void anEarlierPublicationInvalidatesAnAlreadyObservedPrefix() throws Exception {
        try (var f = new Scenario()) {
            f.publish("admit"); var input = f.append();
            var order = f.order(input.blueId());
            var components = new ArrayList<Object>(order.components());
            components.set(components.size() - 1, components.get(components.size() - 1).toString() + "~");
            var cutoff = ExternalOrderKey.of(components);
            Publication held;
            try (var read = f.read()) {
                assertEquals(0, read.sources.before(f.id, cutoff).orElseThrow().epoch());
                held = read.attempt.prepare("before-earlier-work", List.of(), EVIDENCE);
            }
            f.fixture.process(f.root, input); f.publish("earlier-work");
            assertFalse(f.records.publish(held), "An omitted earlier source publication is a genuine conflict");
        }
    }

    @Test void accumulatedCompleteOutcomesRetainEveryPositionAndDiscardedWindowPublishesNothing() throws Exception {
        try (var f = new Scenario()) {
            ExternalOrderKey first, second;
            try (var window = f.read()) {
                window.sources.published(Set.of(f.id), f.fixture.engine.documents().storedState());
                first = f.process(); window.sources.published(Set.of(f.id), f.fixture.engine.documents().storedState());
                second = f.process(); window.sources.published(Set.of(f.id), f.fixture.engine.documents().storedState());
                assertEquals(1, window.sources.before(f.id, second).orElseThrow().epoch());
                window.sources.stage(); window.context.flush();
                assertTrue(f.records.publish(window.attempt.prepare("complete-window", List.of(), EVIDENCE)));
            }
            try (var cold = f.read()) {
                assertEquals(0, cold.sources.before(f.id, first).orElseThrow().epoch());
                assertEquals(1, cold.sources.before(f.id, second).orElseThrow().epoch());
            }
            var unpublished = f.process();
            try (var discarded = f.read()) {
                discarded.sources.published(Set.of(f.id), f.fixture.engine.documents().storedState());
                discarded.sources.stage(); discarded.context.flush(); // prewritten artifacts are not publication
            }
            try (var cold = f.read()) {
                assertEquals(2, cold.sources.before(f.id, unpublished).orElseThrow().epoch());
                assertEquals(3, f.fixture.engine.documents().require(f.id).epoch());
            }
        }
    }

    @Test void missingAndCorruptSelectedHistoryNeverBecomeACompletedSource() throws Exception {
        try (var f = new Scenario()) {
            f.publish("admit"); var cutoff = f.process();
            var row = f.records.data.entrySet().stream().filter(e -> e.getKey().family() == Family.SOURCE_HISTORY).findFirst().orElseThrow();
            var key = row.getKey(); var value = row.getValue();
            f.records.data.remove(key);
            try (var read = f.read()) {
                assertTrue(read.sources.admission(f.id).isPresent());
                assertTrue(read.sources.before(f.id, cutoff).isEmpty(), "Admission is not a substitute for historical evidence");
            }
            f.records.data.put(key, new Value(value.revision() + 1, new Bytes(new byte[] {42})));
            try (var read = f.read()) {
                assertThrows(RuntimeException.class, () -> read.sources.before(f.id, cutoff));
                assertThrows(IllegalStateException.class, () -> read.attempt.prepare("corrupt", List.of(), EVIDENCE));
            }
        }
    }

    private static final class Scenario implements AutoCloseable {
        final DocumentSessionStorageTest.Fixture fixture = new DocumentSessionStorageTest.Fixture();
        final LogicalRecordMapTest.Store records = new LogicalRecordMapTest.Store();
        final DocumentSessionStorageTest.Bytes objects = new DocumentSessionStorageTest.Bytes();
        final blue.coordination.sdk.DocumentHandle root = fixture.start(DocumentSessionStorageTest.resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
        final DocumentId id = root.id();
        Scenario() throws Exception { }
        Read read() { return new Read(this); }
        blue.coordination.sdk.EntryHandle append() { return fixture.append(root, "rcp2/source", "tick"); }
        ExternalOrderKey process() { var input = append(); fixture.process(root, input); return order(input.blueId()); }
        ExternalOrderKey order(String entry) {
            try {
                var field = DefaultCoordinationEngine.class.getDeclaredField("journal"); field.setAccessible(true);
                return ((TimelineJournal) field.get(fixture.engine)).byBlueId(entry).orElseThrow().sourceOrderKey();
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        void publish(String name) {
            try (var read = read()) {
                read.sources.published(Set.of(id), fixture.engine.documents().storedState());
                read.sources.stage(); read.context.flush();
                assertTrue(records.publish(read.attempt.prepare(name, List.of(), EVIDENCE)));
            }
        }
        public void close() { fixture.close(); }
    }
    private static final class Read implements AutoCloseable {
        final blue.coordination.api.storage.CoordinationRecordAttempt attempt;
        final LogicalRecordContext context;
        final DocumentSessionStorage.OpenScope views;
        final StoredHistoricalSources sources;
        Read(Scenario f) {
            attempt = f.records.attempt(); context = new LogicalRecordContext(attempt);
            var objects = RootedEngineStorage.controlledNamespace(f.objects);
            views = new DocumentSessionStorage(objects, RootedEngineStorageTest.LIMITS.sessions()).openScope();
            sources = new StoredHistoricalSources(objects, MAPS, context, views);
        }
        public void close() { views.close(); attempt.close(); }
    }
}
