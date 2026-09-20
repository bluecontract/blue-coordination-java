package blue.coordination.internal;

import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.EntryDisposition;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;

final class SourceDiscoveryStorageCodecTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits LIMITS = new DocumentSessionStorage.Limits(MAX, 128, 128L * 1024 * 1024);

    @Test void includedSourceOwnersSurviveClosedPreparationWithoutBecomingLegacyExclusions() throws Exception {
        // given
        byte[] packet; String identity; DocumentSessionStorageTest.Bytes objects;
        java.util.Set<blue.coordination.api.DocumentId> owners;
        try (var scenario = new Scenario(true)) {
            var original = scenario.coordinator().requireSelection(scenario.selection()); var step = original.step();
            owners = step.live().invocations().stream().flatMap(i -> i.rootedEvidence().context().entryOwners().stream())
                    .map(ContractsClosureAdapter::coordinationId).collect(java.util.stream.Collectors.toSet());
            var scoped = new RootedSourceDiscoveryCoordinator.Prepared(original.descriptor(), original.admission(),
                    new RootedCheckpointDriver.Selection(step.live(), step.historical(), CatchUpConsumerScope.owners(owners),
                            step.blocked(), step.localHistorical()), original.completeness());
            identity = scoped.descriptor().selectionIdentity();
            packet = codec().encodePrepared(scoped, scenario.f.storage::retainView); objects = scenario.f.bytes.copy();
        }
        // when
        try (var cold = new DocumentSessionStorage(objects, LIMITS).openScope()) {
            var restored = codec().decodePrepared(identity, packet, cold);
            // then
            assertTrue(restored.step().consumers().included());
            assertEquals(owners, restored.step().consumers().documents());
            assertArrayEquals(packet, codec().encodePrepared(restored, cold::addressOf));
            assertThrows(IllegalArgumentException.class, () -> CatchUpConsumerScope.owners(java.util.Set.of()));
        }
    }

    @Test void actualPendingAdmissionSurvivesProducerClosureWithoutProviderOrProcessing() throws Exception {
        // given
        byte[] pendingBytes, preparedBytes; String key, selection;
        DocumentSessionStorageTest.Bytes objects;
        try (var scenario = new Scenario(false)) {
            // when
            var descriptor = scenario.selection();
            // then
            assertEquals(SourceHistoryPrerequisite.Kind.ADMISSION, descriptor.kind());
            var coordinator = scenario.coordinator(); var pending = coordinator.pendingForStorage(key(descriptor));
            var prepared = coordinator.requireSelection(descriptor);
            key = pending.key(); selection = descriptor.selectionIdentity();
            var codec = codec(); pendingBytes = codec.encodePending(pending, scenario.f.storage::retainView);
            preparedBytes = codec.encodePrepared(prepared, scenario.f.storage::retainView);
            objects = scenario.f.bytes.copy();
            assertThrows(RuntimeException.class, () -> scenario.f.engine.documents().require(descriptor.sourceDocumentId()),
                    "Preparing and storing an ADMISSION is not publication");
        }
        var sessions = new DocumentSessionStorage(objects, LIMITS);
        int beforeWrites = objects.writes;
        try (var scope = sessions.openScope()) {
            var codec = codec(); var pending = codec.decodePending(key, pendingBytes, scope);
            var prepared = codec.decodePrepared(selection, preparedBytes, scope);
            assertFalse(pending.attempt().isComplete());
            assertTrue(pending.attempt().resourceDemands().stream().anyMatch(demand -> demand == pending.demand()));
            assertEquals(pending.authored().blueId(), prepared.admission().rootDocumentId().value());
            assertArrayEquals(pendingBytes, codec.encodePending(pending, scope::addressOf));
            assertArrayEquals(preparedBytes, codec.encodePrepared(prepared, scope::addressOf));
        }
        assertEquals(beforeWrites, objects.writes);
    }

    @Test void preparedLiveRetainsOriginalEntryCohortAndWindowWithoutReselectingHead() throws Exception {
        // given
        try (var scenario = new Scenario(true)) {
            // when
            var descriptor = scenario.selection();
            // then
            assertEquals(SourceHistoryPrerequisite.Kind.LIVE, descriptor.kind());
            var prepared = scenario.coordinator().requireSelection(descriptor);
            var bytes = codec().encodePrepared(prepared, scenario.f.storage::retainView);
            int providerReads = scenario.f.providerReads.get();
            var sourceBefore = scenario.f.completeEvidence(descriptor.sourceDocumentId());
            try (var scope = new DocumentSessionStorage(scenario.f.bytes.copy(), LIMITS).openScope()) {
                var restored = codec().decodePrepared(descriptor.selectionIdentity(), bytes, scope);
                assertEquals(descriptor, restored.descriptor());
                var exact = new ExactValueStorageCodec(MAX, 128);
                assertArrayEquals(exact.encode(prepared.step().live().entry().exactEvent()), exact.encode(restored.step().live().entry().exactEvent()));
                assertArrayEquals(bytes, codec().encodePrepared(restored, scope::addressOf));
            }
            assertEquals(sourceBefore, scenario.f.completeEvidence(descriptor.sourceDocumentId()));
            assertEquals(providerReads, scenario.f.providerReads.get());
        }
    }

    @Test void wrongKeysTruncationPhysicalBoundsAndChangedPhaseCannotBecomeAuthority() throws Exception {
        // given
        try (var scenario = new Scenario(false)) {
            var descriptor = scenario.selection(); var pending = scenario.coordinator().pendingForStorage(key(descriptor));
            var prepared = scenario.coordinator().requireSelection(descriptor); var codec = codec();
            var pendingBytes = codec.encodePending(pending, scenario.f.storage::retainView);
            // when
            var preparedBytes = codec.encodePrepared(prepared, scenario.f.storage::retainView);
            try (var scope = new DocumentSessionStorage(scenario.f.bytes.copy(), LIMITS).openScope()) {
                // then
                assertThrows(CoordinationObjectStorageException.class, () -> codec.decodePending("another", pendingBytes, scope));
                assertThrows(CoordinationObjectStorageException.class, () -> codec.decodePrepared("another", preparedBytes, scope));
                assertThrows(CoordinationObjectStorageException.class, () -> codec.decodePending(pending.key(), Arrays.copyOf(pendingBytes, pendingBytes.length - 1), scope));
                assertThrows(CoordinationObjectStorageException.class, () -> new SourceDiscoveryStorageCodec(128, 128)
                        .decodePrepared(descriptor.selectionIdentity(), preparedBytes, scope));
                assertThrows(RuntimeException.class, () -> codec.encodePrepared(new RootedSourceDiscoveryCoordinator.Prepared(
                        descriptor, null, null, prepared.completeness()), scope::addressOf));
            }
        }
    }

    @Test void coldPendingAndSubmittedRowsPreserveActualSourcePublicationAndLostResponseReconciliation() throws Exception {
        // given
        var uninterrupted = publishAndReconcile(false);
        // when
        var restored = publishAndReconcile(true);
        // then
        assertEquals(uninterrupted, restored);
    }

    @Test void ordinaryHistoricalSourceWorkRetainsItsOwnOriginalOccurrenceAndPlan() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var leaf = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            var tick = f.append(leaf, "rcp2/source", "tick"); f.process(leaf, tick);
            var source = f.start(resource("parent.yaml").replace("rcp2/parent", "rcp2/middle"),
                    "rcp2/middle", ActivationPolicy.importFullHistory());
            // when
            var attachLeaf = attach(f, source, "rcp2/middle", 120, "child: {blueId: " + leaf.id().value() + "}");
            // then
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(source).entry(attachLeaf).disposition());
            var parent = f.start(resource("parent.yaml"), "rcp2/parent", ActivationPolicy.importFullHistory());
            var attachSource = attach(f, parent, "rcp2/parent", 200, "child: {blueId: " + source.id().value() + "}");
            assertEquals(EntryDisposition.NEEDS_RESOURCES, f.blue.processing().processNext(parent).entry(attachSource).disposition());
            assertSelectedWorkRoundTrip(f, parent, SourceHistoryPrerequisite.Kind.MANAGED_HISTORY);
        }
    }

    @Test void rootedRetainedSourceWorkKeepsItsCapturedHistoricalViewAndIsNotLiveWork() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var nodes = new java.util.LinkedHashMap<String, DocumentHandle>();
            for (String name : java.util.List.of("A", "B", "C")) {
                String yaml = resource("node-graph.template.json").replace("<NODE>", name)
                        .replace("<NAMESPACE>", "stored-source-local").replace("<TIMELINE>", "stored-source/" + name);
                var authored = f.blue.values().yaml(yaml); f.exact.put(authored.blueId(), authored.json());
                nodes.put(name, f.start(yaml, "stored-source/" + name, ActivationPolicy.importFullHistory()));
            }
            var a = nodes.get("A"); var b = nodes.get("B"); var c = nodes.get("C");
            // when
            var ab = attach(f, a, "stored-source/A", 10, "edge: b\nsource: {blueId: " + b.id().value() + "}");
            // then
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(a).entry(ab).disposition());
            assertEquals(1, f.blue.processing().processNext(a).managedEpochApplications().size());
            var bc = attach(f, b, "stored-source/B", 12, "edge: c\nsource: {blueId: " + c.id().value() + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(b).entry(bc).disposition());
            assertEquals(1, f.blue.processing().processNext(b).managedEpochApplications().size());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(a).entry(bc).disposition());
            var parent = f.start(resource("parent.yaml"), "rcp2/parent", ActivationPolicy.importFullHistory());
            var pa = attach(f, parent, "rcp2/parent", 20, "child: {blueId: " + a.id().value() + "}");
            assertEquals(EntryDisposition.NEEDS_RESOURCES, f.blue.processing().processNext(parent).entry(pa).disposition());
            assertSelectedWorkRoundTrip(f, parent, SourceHistoryPrerequisite.Kind.ROOTED_RETAINED);
        }
    }

    private void assertSelectedWorkRoundTrip(DocumentSessionStorageTest.Fixture f, DocumentHandle parent,
            SourceHistoryPrerequisite.Kind kind) throws Exception {
        var selected = f.blue.advanced().sourceHistoryPrerequisites(parent).get(0); assertEquals(kind, selected.kind());
        var coordinator = coordinator(f); var prepared = coordinator.requireSelection(selected);
        var beforeParent = f.completeEvidence(parent.id()); var beforeSource = f.completeEvidence(selected.sourceDocumentId());
        var codec = codec(); var bytes = codec.encodePrepared(prepared, f.storage::retainView);
        int reads = f.providerReads.get();
        try (var scope = new DocumentSessionStorage(f.bytes.copy(), LIMITS).openScope()) {
            var restored = codec.decodePrepared(selected.selectionIdentity(), bytes, scope);
            assertEquals(selected, restored.descriptor());
            assertArrayEquals(bytes, codec.encodePrepared(restored, scope::addressOf));
            if (prepared.step().localHistorical() != null) {
                var original = prepared.step().localHistorical();
                var retained = restored.step().localHistorical();
                var evidence = new blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec(MAX, 128);
                assertNotSame(original, retained);
                assertSame(retained.originalInputForStorage().snapshot(), retained.capturedState().snapshot());
                assertSame(retained.capturedState().view(), retained.invocation().rootedEvidence().historicalOrigin());
                assertSame(retained.work(), retained.invocation().rootedEvidence().historicalWork());
                assertArrayEquals(evidence.encodeInvocation(original.originalInputForStorage()),
                        evidence.encodeInvocation(retained.originalInputForStorage()));
                assertArrayEquals(evidence.encodeInvocation(original.invocation().input()),
                        evidence.encodeInvocation(retained.invocation().input()));
                assertEquals(original.work().workIdentity(), retained.work().workIdentity());
                assertArrayEquals(SessionStorageWire.encode(MAX, out -> StoreIndexCodecs.occurrence(out, original.target())),
                        SessionStorageWire.encode(MAX, out -> StoreIndexCodecs.occurrence(out, retained.target())));
                assertEquals(original.sourceOrder(), retained.sourceOrder());
                assertThrows(RuntimeException.class, retained.capturedState()::routes,
                        "A submitted replay proof cannot replace fresh source selection");
                var localCodec = new RootedLocalStepStorageCodec(MAX, 128);
                var localBytes = localCodec.encode(original, f.storage::retainView);
                assertThrows(CoordinationObjectStorageException.class,
                        () -> localCodec.decode(Arrays.copyOf(localBytes, localBytes.length - 1), scope));
                assertThrows(CoordinationObjectStorageException.class,
                        () -> new RootedLocalStepStorageCodec(128, 128).decode(localBytes, scope));
            }
        }
        assertEquals(reads, f.providerReads.get()); assertEquals(beforeParent, f.completeEvidence(parent.id()));
        assertEquals(beforeSource, f.completeEvidence(selected.sourceDocumentId()));
        try (var scope = new DocumentSessionStorage(f.bytes.copy(), LIMITS).openScope()) {
            var retained = codec.decodePrepared(selected.selectionIdentity(), bytes, scope);
            sourceMap(coordinator, "submitted").put(selected.selectionIdentity(), retained);
            var fresh = coordinator.requireSelection(selected);
            assertNotSame(retained, fresh, "An uncommitted stored proof must be freshly selected before execution");
            if (fresh.step().localHistorical() != null)
                assertNotNull(fresh.step().localHistorical().capturedState().routes());
            var result = f.blue.advanced().processSourceHistoryPrerequisite(selected);
            assertTrue(result.processing().orElseThrow().committedProcessTransitions() > 0);
            assertEquals(beforeParent, f.completeEvidence(parent.id()));
            var published = f.completeEvidence(selected.sourceDocumentId());
            sourceMap(coordinator, "completed").clear();
            sourceMap(coordinator, "submitted").put(selected.selectionIdentity(), retained);
            var reconciled = f.blue.advanced().processSourceHistoryPrerequisite(selected);
            assertTrue(reconciled.replayed(), "Cold submitted evidence reconciles the original committed action");
            assertEquals(published, f.completeEvidence(selected.sourceDocumentId()));
            assertEquals(beforeParent, f.completeEvidence(parent.id()));
        }
    }

    private static blue.coordination.sdk.EntryHandle attach(DocumentSessionStorageTest.Fixture f, DocumentHandle root,
            String timeline, long time, String request) {
        return f.blue.events().from(f.timelines.get(timeline)).exact(f.blue.values().yaml("""
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: %s}
                timestamp: %d
                actor: {type: MyOS/Principal Actor, accountId: alice}
                message:
                  type: Coordination/Operation Request
                  document: {blueId: %s}
                  operation: attach
                  channel: owner
                  request:
                %s
                """.formatted(timeline, time, root.snapshot().blueId(), request.indent(4)))).submit();
    }

    private java.util.List<String> publishAndReconcile(boolean cold) throws Exception {
        try (var scenario = new Scenario(false)) {
            var d = scenario.selection(); var c = scenario.coordinator();
            var pending = c.pendingForStorage(key(d)); var prepared = c.requireSelection(d);
            var codec = codec(); var pendingBytes = codec.encodePending(pending, scenario.f.storage::retainView);
            var preparedBytes = codec.encodePrepared(prepared, scenario.f.storage::retainView);
            try (var scope = new DocumentSessionStorage(scenario.f.bytes.copy(), LIMITS).openScope()) {
                if (cold) {
                    sourceMap(c, "pending").put(key(d), codec.decodePending(key(d), pendingBytes, scope));
                    sourceMap(c, "submitted").put(d.selectionIdentity(), codec.decodePrepared(d.selectionIdentity(), preparedBytes, scope));
                }
                var parentBefore = scenario.f.completeEvidence(scenario.parent.id());
                var result = scenario.f.blue.advanced().processSourceHistoryPrerequisite(d);
                assertTrue(result.admission().orElseThrow().published());
                assertEquals(parentBefore, scenario.f.completeEvidence(scenario.parent.id()), "Independent admission does not publish the parent");
                var sourceBefore = scenario.f.completeEvidence(d.sourceDocumentId());
                // Drop the response-only memo, retaining the original submitted operation.
                // The actual committed source receipt must reconcile without re-admission.
                sourceMap(c, "completed").clear();
                if (cold) sourceMap(c, "submitted").put(d.selectionIdentity(), codec.decodePrepared(d.selectionIdentity(), preparedBytes, scope));
                var repeated = scenario.f.blue.advanced().processSourceHistoryPrerequisite(d);
                assertTrue(repeated.replayed());
                assertEquals(sourceBefore, scenario.f.completeEvidence(d.sourceDocumentId()));
                assertEquals(parentBefore, scenario.f.completeEvidence(scenario.parent.id()));
                return sourceBefore;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> sourceMap(RootedSourceDiscoveryCoordinator coordinator, String name) throws Exception {
        Field field = RootedSourceDiscoveryCoordinator.class.getDeclaredField(name); field.setAccessible(true);
        return (java.util.Map<String, Object>) field.get(coordinator);
    }

    private static SourceDiscoveryStorageCodec codec() { return new SourceDiscoveryStorageCodec(MAX, 128); }
    static String key(SourceHistoryPrerequisite d) { return d.requestingInvocationIdentity() + "/" + d.demandIdentity(); }
    static RootedSourceDiscoveryCoordinator coordinator(DocumentSessionStorageTest.Fixture f) throws Exception {
        Field field = DefaultCoordinationEngine.class.getDeclaredField("rootedSourceDiscoveries"); field.setAccessible(true);
        return (RootedSourceDiscoveryCoordinator) field.get(f.engine);
    }
    static final class Scenario implements AutoCloseable {
        final DocumentSessionStorageTest.Fixture f = new DocumentSessionStorageTest.Fixture();
        final DocumentHandle parent;
        Scenario(boolean known) throws Exception {
            this(known, resource("source.yaml"));
        }
        Scenario(boolean known, String sourceYaml) throws Exception {
            parent = f.start(resource("parent.yaml"), "rcp2/parent", ActivationPolicy.importFullHistory());
            var authored = f.blue.values().yaml(sourceYaml); f.exact.put(authored.blueId(), authored.json());
            if (known) {
                var source = f.start(sourceYaml, "rcp2/source", ActivationPolicy.importFullHistory());
                f.append(source, "rcp2/source", "tick");
            } else f.timelines.put("rcp2/source", f.blue.timelines().register("rcp2/source", "alice"));
            var entry = f.blue.events().from(f.timelines.get("rcp2/parent")).exact(f.blue.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/parent}
                    timestamp: 200
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    message:
                      type: Coordination/Operation Request
                      document: {blueId: %s}
                      operation: attach
                      channel: owner
                      request:
                        child: {blueId: %s}
                    """.formatted(parent.snapshot().blueId(), authored.blueId()))).submit();
            var stopped = f.blue.processing().processNext(parent).entry(entry);
            assertEquals(EntryDisposition.NEEDS_RESOURCES, stopped.disposition(), String.valueOf(stopped.diagnostic()));
        }
        SourceHistoryPrerequisite selection() { return f.blue.advanced().sourceHistoryPrerequisites(parent).get(0); }
        RootedSourceDiscoveryCoordinator coordinator() throws Exception {
            return SourceDiscoveryStorageCodecTest.coordinator(f);
        }
        public void close() { f.close(); }
    }
}
