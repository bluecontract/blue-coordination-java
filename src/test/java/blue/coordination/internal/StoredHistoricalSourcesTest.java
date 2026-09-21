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
        // given
        try (var f = new Scenario()) {
            f.publish("admit");
            var first = f.process(); f.publish("first");
            var second = f.append();
            var cutoff = f.order(second.blueId());
            // when
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
            // then
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
        // given
        try (var f = new Scenario()) {
            f.publish("admit"); var input = f.append();
            var order = f.order(input.blueId());
            var components = new ArrayList<Object>(order.components());
            components.set(components.size() - 1, components.get(components.size() - 1).toString() + "~");
            var cutoff = ExternalOrderKey.of(components);
            // when
            Publication held;
            try (var read = f.read()) {
                assertEquals(0, read.sources.before(f.id, cutoff).orElseThrow().epoch());
                held = read.attempt.prepare("before-earlier-work", List.of(), EVIDENCE);
            }
            // then
            f.fixture.process(f.root, input); f.publish("earlier-work");
            assertFalse(f.records.publish(held), "An omitted earlier source publication is a genuine conflict");
        }
    }

    @Test void accumulatedCompleteOutcomesRetainEveryPositionAndDiscardedWindowPublishesNothing() throws Exception {
        // given
        try (var f = new Scenario()) {
            ExternalOrderKey first, second;
            // when
            try (var window = f.read()) {
                window.sources.published(Set.of(f.id), f.fixture.engine.documents().storedState());
                first = f.process(); window.sources.published(Set.of(f.id), f.fixture.engine.documents().storedState());
                second = f.process(); window.sources.published(Set.of(f.id), f.fixture.engine.documents().storedState());
                assertEquals(1, window.sources.before(f.id, second).orElseThrow().epoch());
                window.sources.stage(); window.context.flush();
                assertTrue(f.records.publish(window.attempt.prepare("complete-window", List.of(), EVIDENCE)));
            }
            // then
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

    @Test void replacementHistoryCannotAliasTheOriginalInstancesBoundaryOrAdmission() throws Exception {
        // given
        try (var f = new Scenario()) {
            var beginning = f.fixture.engine.documents().storedState();
            f.publish("admit"); var first = f.process(); f.publish("old-first");
            var order = new ArrayList<Object>(first.components());
            order.set(order.size() - 1, order.get(order.size() - 1).toString() + "~");
            var cutoff = ExternalOrderKey.of(order);
            var original = LogicalDocumentInstances.initialReference(f.id);
            var replacement = new blue.coordination.api.DocumentInstanceRef(f.id, "replacement");
            Publication oldReader;
            try (var read = f.read()) {
                assertEquals(1, read.sources.before(original, cutoff).orElseThrow().epoch());
                oldReader = read.attempt.prepare("old-explicit-source", List.of(), EVIDENCE);
                assertTrue(oldReader.points().stream().noneMatch(point -> point.key().family() == Family.INSTANCE_BINDING));
            }
            // when
            try (var writer = f.read()) {
                var ledger = writer.context.instances(MAPS.valueBytes());
                ledger.retire(ledger.requireActive(original)); ledger.start(replacement);
                // Actual earlier library image, not a fabricated new initialization result.
                writer.sources.published(Set.of(f.id), beginning);
                writer.sources.stage(); writer.context.flush();
                assertTrue(f.records.publish(writer.attempt.prepare("replacement-basis", List.of(), EVIDENCE)));
            }
            // then
            assertTrue(f.records.publish(oldReader), "A2 publication cannot invalidate an A1-only historical prefix");
            try (var cold = f.read()) {
                assertEquals(1, cold.sources.before(original, cutoff).orElseThrow().epoch());
                assertEquals(0, cold.sources.before(replacement, cutoff).orElseThrow().epoch());
                assertEquals(0, cold.sources.before(f.id, cutoff).orElseThrow().epoch());
                assertEquals(0, cold.sources.admission(original).orElseThrow().epoch());
                assertEquals(0, cold.sources.admission(replacement).orElseThrow().epoch());
                var oldLineage = cold.sources.lineages(cutoff, ManagedLineageIndex.empty(), Set.of(),
                        id -> Optional.of(original)).byDocumentId(f.id);
                assertEquals(1, oldLineage.currentEpoch());
                assertEquals(0, cold.sources.lineages(cutoff, ManagedLineageIndex.empty(), Set.of()).byDocumentId(f.id).currentEpoch());
            }
        }
    }

    @Test void retainedOccurrenceSelectsOriginalSourceHistoryAfterTheActiveBindingChanges() throws Exception {
        // given
        try (var f = new Scenario()) {
            var beginning = f.fixture.engine.documents().storedState();
            var observer = f.fixture.start(DocumentSessionStorageTest.resource("parent.yaml")
                    + "\nchild: {blueId: " + f.root.snapshot().blueId() + "}\n", "rcp2/parent", ActivationPolicy.importFullHistory());
            var observerSession = f.fixture.engine.documents().require(observer.id());
            try (var writer = f.read()) {
                writer.sources.published(Set.of(f.id, observer.id()), f.fixture.engine.documents().storedState());
                writer.sources.stage(); writer.context.flush();
                assertTrue(f.records.publish(writer.attempt.prepare("both-admitted", List.of(), EVIDENCE)));
            }
            var first = f.process(); f.publish("source-first");
            var order = new ArrayList<Object>(first.components());
            order.set(order.size() - 1, order.get(order.size() - 1).toString() + "~");
            var cutoff = ExternalOrderKey.of(order);
            var original = LogicalDocumentInstances.initialReference(f.id);
            // when
            try (var writer = f.read()) {
                var ledger = writer.context.instances(MAPS.valueBytes());
                ledger.retire(ledger.requireActive(original));
                ledger.start(new blue.coordination.api.DocumentInstanceRef(f.id, "replacement"));
                writer.sources.published(Set.of(f.id), beginning);
                writer.sources.stage(); writer.context.flush();
                assertTrue(f.records.publish(writer.attempt.prepare("replace-source", List.of(), EVIDENCE)));
            }
            // then
            try (var reader = f.read()) {
                var selected = reader.sources.sourceInstance(f.id, List.of(observerSession)).orElseThrow();
                assertEquals(original, selected);
                assertEquals(1, reader.sources.before(selected, cutoff).orElseThrow().epoch());
                var proof = reader.attempt.prepare("old-occurrence", List.of(), EVIDENCE);
                assertEquals(1, proof.points().stream().filter(point -> point.key().family() == Family.INSTANCE_BINDING).count(),
                        "Only the actual observer's mutable binding is selected, never the source replacement");
                assertTrue(f.records.publish(proof));
            }
            try (var reader = f.read()) {
                var observerRef = LogicalDocumentInstances.initialReference(observer.id());
                assertEquals(1, reader.sources.beforeFrom(f.id, observerRef, observerSession, cutoff).orElseThrow().epoch());
                var proof = reader.attempt.prepare("exact-forward-eligibility", List.of(), EVIDENCE);
                assertTrue(proof.points().stream().noneMatch(point -> point.key().family() == Family.INSTANCE_BINDING),
                        "An exact retained forward role needs neither observer nor target current binding");
            }
            try (var reader = f.read()) {
                String block = reader.sources.sourceWorkBlock(f.id, List.of(observerSession)).orElseThrow();
                assertTrue(block.contains(original.instanceId()));
                assertTrue(block.contains("RETIRED_SOURCE_INSTANCE_WORK_REQUIRED"));
            }
        }
    }

    @Test void nonExactHistoricalTargetEligibilityUsesTheOriginalSourceInstance() throws Exception {
        // given
        try (var f = new Scenario()) {
            var beginning = f.fixture.engine.documents().storedState();
            f.publish("admit"); f.process(); f.publish("first");
            String historicalTarget = f.root.snapshot().blueId();
            f.process(); f.publish("second");
            var original = LogicalDocumentInstances.initialReference(f.id);
            String yaml = """
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/source}
                    timestamp: %d
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    prevEntry: {blueId: %s}
                    message:
                      type: Coordination/Operation Request
                      document: {blueId: %s}
                      requireExactDocumentVersion: false
                      operation: tick
                      channel: owner
                      request: {}
                    """.formatted(f.fixture.clock, f.fixture.previous.get("rcp2/source"), historicalTarget);
            f.fixture.clock += 100;
            var accepted = f.fixture.blue.events().from(f.fixture.timelines.get("rcp2/source"))
                    .exact(f.fixture.blue.values().yaml(yaml)).submit();
            f.fixture.previous.put("rcp2/source", accepted.blueId());
            var cutoff = f.order(f.append().blueId());
            // when
            try (var writer = f.read()) {
                var ledger = writer.context.instances(MAPS.valueBytes());
                ledger.retire(ledger.requireActive(original));
                ledger.start(new blue.coordination.api.DocumentInstanceRef(f.id, "replacement"));
                writer.sources.published(Set.of(f.id), beginning);
                writer.sources.stage(); writer.context.flush();
                assertTrue(f.records.publish(writer.attempt.prepare("replace-eligibility-source", List.of(), EVIDENCE)));
            }
            // then
            try (var reader = f.read()) {
                var retained = reader.sources.before(original, cutoff).orElseThrow();
                assertEquals(2, retained.epoch());
                f.fixture.engine.documents().bindHistoricalSources(reader.sources);
                var adapter = f.fixture.engine.contractsClosureAdapter();
                assertTrue(adapter.sourceHasEligibleInputBefore(f.id, retained.rootedViewBefore(cutoff), cutoff, f.journal(),
                        id -> f.fixture.engine.documents().retainedSourceBefore(id, retained, cutoff, Set.of()).orElseThrow()),
                        "A1 must recognize the accepted non-head epoch-1 target despite A2 starting at epoch 0");
                var proof = reader.attempt.prepare("retained-target-eligibility", List.of(), EVIDENCE);
                assertTrue(proof.points().stream().noneMatch(point -> point.key().family() == Family.INSTANCE_BINDING),
                        "Exact retained source eligibility must not select A2's mutable binding");
            }
        }
    }

    @Test void missingOccurrenceAssociationCannotFallBackToTheActiveSourceBinding() throws Exception {
        // given
        try (var f = new Scenario()) {
            var observer = f.fixture.start(DocumentSessionStorageTest.resource("parent.yaml")
                    + "\nchild: {blueId: " + f.root.snapshot().blueId() + "}\n", "rcp2/parent", ActivationPolicy.importFullHistory());
            var session = f.fixture.engine.documents().require(observer.id());
            try (var writer = f.read()) {
                writer.sources.published(Set.of(f.id, observer.id()), f.fixture.engine.documents().storedState());
                writer.sources.stage(); writer.context.flush();
                assertTrue(f.records.publish(writer.attempt.prepare("with-occurrence", List.of(), EVIDENCE)));
            }
            var associations = f.records.data.keySet().stream().filter(key -> key.family() == Family.INSTANCE_OCCURRENCE).toList();
            // when
            assertFalse(associations.isEmpty()); associations.forEach(f.records.data::remove);
            // then
            try (var reader = f.read()) {
                assertThrows(RuntimeException.class, () -> reader.sources.sourceInstance(f.id, List.of(session)));
                assertThrows(IllegalStateException.class, () -> reader.attempt.prepare("missing-association", List.of(), EVIDENCE));
            }
        }
    }

    @Test void missingAndCorruptSelectedHistoryNeverBecomeACompletedSource() throws Exception {
        // given
        try (var f = new Scenario()) {
            f.publish("admit"); var cutoff = f.process();
            var row = f.records.data.entrySet().stream().filter(e -> e.getKey().family() == Family.SOURCE_HISTORY).findFirst().orElseThrow();
            var key = row.getKey(); var value = row.getValue();
            // when
            f.records.data.remove(key);
            // then
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
        ExternalOrderKey order(String entry) { return journal().byBlueId(entry).orElseThrow().sourceOrderKey(); }
        TimelineJournal journal() {
            try {
                var field = DefaultCoordinationEngine.class.getDeclaredField("journal"); field.setAccessible(true);
                return (TimelineJournal) field.get(fixture.engine);
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
