package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.coordination.internal.ColdStorageJournalFixture;
import blue.coordination.internal.DefaultCoordinationEngine;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Complete SDK/engine cold assembly; this fixture's journal is separately coherent and host-owned. */
final class LogicalCoordinationStorageTest {
    private static final RootedCoordinationStorage.Limits LIMITS = logicalLimits();
    private static RootedCoordinationStorage.Limits logicalLimits() {
        var base = RootedCoordinationStorageTest.LIMITS; var e = base.engine();
        // Logical empty buckets are selected scopes too; provider probes exceed the 256-map fixture capacity.
        return new RootedCoordinationStorage.Limits(new blue.coordination.internal.RootedEngineStorage.Limits(
                e.indexNodeBytes(), e.keyBytes(), e.valueBytes(), e.descriptorBytes(), e.cachedNodes(),
                e.maximumRecordBytes(), e.maximumDepth(), e.maximumScopeBytes(), e.maximumSelectedSessions(),
                2048, e.maximumPendingEntries(), e.maximumPendingBytes(), e.logChunkBytes(), e.logValueBytes(),
                e.cachedLogChunks()), base.sdk());
    }
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});
    private static final SdkStorageCodec CODEC = new SdkStorageCodec(new Object(), 32 * 1024 * 1024);

    @Test void completeLogicalOwnerMatchesResidentResultAndHistoryAcrossColdStages() throws Exception {
        // given
        byte[] expected; String head; List<String> history;
        RootedCoordinationStorage.Configuration configuration;
        try (var reference = new RootedSdkFixture()) {
            configuration = RootedCoordinationStorage.configuration(reference.blue, LIMITS);
            var root = reference.start("source.yaml", "rcp2/source", Map.of());
            var entry = reference.append(root, "rcp2/source", "setCounter", 10, "counterValue: 5");
            expected = CODEC.encode(reference.blue.processing().processNextStage(root).entry(entry));
            head = root.snapshot().blueId(); history = reference.history(root);
        }
        var records = new SdkRuntimePointMapsTest.LogicalRecords(); var objects = new SdkRuntimePointMapsTest.Bytes();
        var journal = ColdStorageJournalFixture.empty(); DocumentId rootId; String entryId;
        // when
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.openLogical(objects, LIMITS, configuration,
                attempt, ExactNodeProvider.empty(), journal)) {
            var blue = scope.coordination(); var timeline = blue.timelines().register("rcp2/source", "alice");
            var root = blue.documents().admitStaticProcessEmbedded(RootedSdkFixture.resource("source.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            rootId = root.id(); var entry = append(blue, root, timeline, 10, "counterValue: 5"); entryId = entry.blueId();
            scope.stage(); assertTrue(records.publish(attempt.prepare("accepted", List.of(), EVIDENCE)));
            assertThrows(RuntimeException.class, root::snapshot);
        }
        var detachedJournal = ColdStorageJournalFixture.retain(journal);
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.openLogical(objects, LIMITS, configuration,
                attempt, ExactNodeProvider.empty(), ColdStorageJournalFixture.open(detachedJournal))) {
            var root = scope.documentHandle(rootId).orElseThrow();
            var stage = scope.coordination().processing().processNextStage(root);
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, stage.disposition());
            assertArrayEquals(expected, CODEC.encode(scope.coordination().runtimeForStorage().storedMaps().results().get(entryId)));
            // Retain all newly prewritten artifacts for the next owner.
            scope.stage(); assertTrue(records.publish(attempt.prepare("processed", List.of(), EVIDENCE)));
        }
        // then
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.openLogical(objects.fresh(), LIMITS, configuration,
                attempt, ExactNodeProvider.empty(), ColdStorageJournalFixture.open(detachedJournal))) {
            var root = scope.documentHandle(rootId).orElseThrow();
            assertEquals(head, root.snapshot().blueId());
            assertEquals(history, scope.coordination().advanced().auditManagedEpochs(rootId).stream().map(ManagedEpochReceipt::receiptIdentity).toList());
            assertArrayEquals(expected, CODEC.encode(scope.coordination().runtimeForStorage().storedMaps().results().get(entryId)));
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, scope.coordination().processing().processNextStage(root).disposition());
        }
    }

    @Test void independentCompleteColdOwnersPublishInBothOrdersWithoutSemanticReplay() throws Exception {
        // given
        for (boolean reverse : List.of(false, true)) {
            var records = new SdkRuntimePointMapsTest.LogicalRecords(); var objects = new SdkRuntimePointMapsTest.Bytes();
            var journal = ColdStorageJournalFixture.empty();
            RootedCoordinationStorage.Configuration configuration;
            var expected = new ArrayList<byte[]>(); var roots = new ArrayList<DocumentId>(); var entries = new ArrayList<String>();
            try (var reference = new RootedSdkFixture()) {
                configuration = RootedCoordinationStorage.configuration(reference.blue, LIMITS);
                for (int i = 0; i < 2; i++) {
                    String timeline = "independent/" + i;
                    var root = reference.startYaml(RootedSdkFixture.resource("source.yaml").replace("rcp2/source", timeline), timeline);
                    var entry = reference.append(root, timeline, "setCounter", 10 + i, "counterValue: " + (5 + i));
                    expected.add(CODEC.encode(reference.blue.processing().processNextStage(root).entry(entry)));
                }
            }
            try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects).openLogical(LIMITS, configuration,
                    attempt, ExactNodeProvider.empty(), journal)) {
                var blue = scope.coordination();
                for (int i = 0; i < 2; i++) {
                    String timelineId = "independent/" + i; var timeline = blue.timelines().register(timelineId, "alice");
                    var root = blue.documents().admitStaticProcessEmbedded(RootedSdkFixture.resource("source.yaml").replace("rcp2/source", timelineId),
                            ActivationPolicy.importFullHistory()).document("root");
                    roots.add(root.id()); entries.add(append(blue, root, timeline, 10 + i, "counterValue: " + (5 + i)).blueId());
                }
                scope.stage(); assertTrue(records.publish(attempt.prepare("accepted", List.of(), EVIDENCE)));
            }
            var journalBytes = ColdStorageJournalFixture.retain(journal);
            try (var a = records.attempt(); var b = records.attempt();
                 var left = RootedCoordinationStorage.controlledRepository(objects).openLogical(LIMITS, configuration, a, ExactNodeProvider.empty(), ColdStorageJournalFixture.open(journalBytes));
                 var right = RootedCoordinationStorage.controlledRepository(objects).openLogical(LIMITS, configuration, b, ExactNodeProvider.empty(), ColdStorageJournalFixture.open(journalBytes))) {
                // when
                for (int i = 0; i < 2; i++) {
                    var scope = i == 0 ? left : right; var root = scope.documentHandle(roots.get(i)).orElseThrow();
                    var stage = scope.coordination().processing().processNextStage(root);
                    assertEquals(1, stage.stats().committedTransitions());
                    assertArrayEquals(expected.get(i), CODEC.encode(scope.coordination().runtimeForStorage().storedMaps().results().get(entries.get(i))));
                    scope.stage();
                }
                var pa = a.prepare("left", List.of(), EVIDENCE); var pb = b.prepare("right", List.of(), EVIDENCE);
                // then
                assertTrue(records.publish(reverse ? pb : pa));
                var second = reverse ? pa : pb;
                assertTrue(records.publish(second), () -> "Conflicting records: " + second.points().stream()
                        .filter(point -> !point.expected().equals(records.data.getOrDefault(point.key(), Value.absent())))
                        .map(point -> point.key().toString()).toList() + " queries=" + second.queries().stream()
                        .filter(q -> !q.expected().equals(records.data.entrySet().stream().filter(e -> q.range().contains(e.getKey()) && e.getValue().content() != null)
                                .map(e -> new Row(e.getKey(), e.getValue())).toList()))
                        .map(q -> q.range().toString()).toList());
            }
            try (var attempt = records.attempt(); var cold = RootedCoordinationStorage.controlledRepository(objects.fresh()).openLogical(LIMITS, configuration,
                    attempt, ExactNodeProvider.empty(), ColdStorageJournalFixture.open(journalBytes))) {
                for (int i = 0; i < 2; i++) {
                    var root = cold.documentHandle(roots.get(i)).orElseThrow(); assertEquals(5 + i, root.snapshot().longAt("/counter"));
                    assertArrayEquals(expected.get(i), CODEC.encode(cold.coordination().runtimeForStorage().storedMaps().results().get(entries.get(i))));
                }
            }
        }
    }

    @Test void suspendedParentAndSourceContinuationSurviveEveryCompleteOwnerClosing() throws Exception {
        // given
        RootedCoordinationStorage.Configuration configuration; String expectedHead; List<String> expectedHistory;
        var expectedStages = new ArrayList<String>(); String sourceId; String sourceJson;
        try (var reference = new RootedSdkFixture()) {
            configuration = RootedCoordinationStorage.configuration(reference.blue, LIMITS);
            var parent = reference.start("parent.yaml", "rcp2/parent", Map.of());
            var source = reference.blue.values().yaml(RootedSdkFixture.resource("source.yaml")); sourceId = source.blueId(); sourceJson = source.json();
            reference.exact.put(sourceId, sourceJson); reference.blue.timelines().register("rcp2/source", "alice");
            reference.append(parent, "rcp2/parent", "attach", 200, "child:\n  blueId: " + sourceId);
            expectedStages.add(stageIdentity(reference.blue.processing().processNextStage(parent)));
            var action = reference.blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            assertTrue(reference.blue.advanced().processSourceHistoryPrerequisite(action).admission().orElseThrow().published());
            for (int i = 0; i < 8; i++) {
                var result = reference.blue.processing().processNextStage(parent); expectedStages.add(stageIdentity(result));
                if (result.disposition() == ProcessingStageResult.Disposition.NO_WORK) break;
            }
            expectedHead = parent.snapshot().blueId(); expectedHistory = reference.history(parent);
        }
        var records = new SdkRuntimePointMapsTest.LogicalRecords(); var objects = new SdkRuntimePointMapsTest.Bytes();
        var journal = ColdStorageJournalFixture.empty(); DocumentId parentId;
        ExactNodeProvider provider = id -> id.equals(sourceId) ? Optional.of(sourceJson) : Optional.empty();
        // when
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects).openLogical(
                LIMITS, configuration, attempt, provider, journal)) {
            var blue = scope.coordination(); var timeline = blue.timelines().register("rcp2/parent", "alice");
            blue.timelines().register("rcp2/source", "alice");
            var parent = blue.documents().admitStaticProcessEmbedded(RootedSdkFixture.resource("parent.yaml"),
                    ActivationPolicy.importFullHistory()).document("root"); parentId = parent.id();
            append(blue, parent, timeline, 200, "child:\n  blueId: " + sourceId, "attach");
            scope.stage(); assertTrue(records.publish(attempt.prepare("parent-accepted", List.of(), EVIDENCE)));
        }
        var journalBytes = ColdStorageJournalFixture.retain(journal); var actualStages = new ArrayList<String>();
        blue.coordination.api.SourceHistoryPrerequisite original;
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects).openLogical(
                LIMITS, configuration, attempt, provider, ColdStorageJournalFixture.open(journalBytes))) {
            var parent = scope.documentHandle(parentId).orElseThrow();
            actualStages.add(stageIdentity(scope.coordination().processing().processNextStage(parent)));
            original = scope.coordination().advanced().sourceHistoryPrerequisites(parent).get(0);
            scope.stage(); assertTrue(records.publish(attempt.prepare("parent-waiting", List.of(), EVIDENCE)));
        }
        for (int i = 0; i < 2; i++) try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects).openLogical(
                LIMITS, configuration, attempt, provider, ColdStorageJournalFixture.open(journalBytes))) {
            var result = scope.coordination().advanced().processSourceHistoryPrerequisite(original);
            assertTrue(result.admission().orElseThrow().published()); assertEquals(i != 0, result.replayed());
            scope.stage(); assertTrue(records.publish(attempt.prepare("source-" + i, List.of(), EVIDENCE)));
        }
        for (int i = 0; i < 8; i++) try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects).openLogical(
                LIMITS, configuration, attempt, provider, ColdStorageJournalFixture.open(journalBytes))) {
            var result = scope.coordination().processing().processNextStage(scope.documentHandle(parentId).orElseThrow());
            actualStages.add(stageIdentity(result)); scope.stage();
            assertTrue(records.publish(attempt.prepare("parent-resume-" + i, List.of(), EVIDENCE)));
            if (result.disposition() == ProcessingStageResult.Disposition.NO_WORK) break;
        }
        // then
        assertEquals(expectedStages, actualStages);
        assertTrue(actualStages.get(actualStages.size() - 1).startsWith("NO_WORK/"), "Bounded continuation must finish");
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects.fresh()).openLogical(
                LIMITS, configuration, attempt, provider, ColdStorageJournalFixture.open(journalBytes))) {
            assertEquals(expectedHead, scope.documentHandle(parentId).orElseThrow().snapshot().blueId());
            assertEquals(expectedHistory, scope.coordination().advanced().auditManagedEpochs(parentId).stream().map(ManagedEpochReceipt::receiptIdentity).toList());
        }
    }

    @Test void retainedParentStageAndUnrelatedLiveStagePublishFromOneSnapshotInBothOrders() throws Exception {
        // given
        for (boolean reverse : List.of(false, true)) {
            var records = new SdkRuntimePointMapsTest.LogicalRecords(); var objects = new SdkRuntimePointMapsTest.Bytes();
            RootedCoordinationStorage.Configuration configuration;
            String sourceId; String sourceJson;
            try (var seed = new RootedSdkFixture()) {
                configuration = RootedCoordinationStorage.configuration(seed.blue, LIMITS);
                var value = seed.blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
                sourceId = value.blueId(); sourceJson = value.json();
            }
            ExactNodeProvider provider = id -> id.equals(sourceId) ? Optional.of(sourceJson) : Optional.empty();
            var journal = ColdStorageJournalFixture.empty(); DocumentId parentId; DocumentId otherId;
            try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                    .openLogical(LIMITS, configuration, attempt, provider, journal)) {
                var blue = scope.coordination(); var timeline = blue.timelines().register("rcp2/parent", "alice");
                blue.timelines().register("rcp2/source", "alice"); var otherTimeline = blue.timelines().register("other", "alice");
                var parent = blue.documents().admitStaticProcessEmbedded(RootedSdkFixture.resource("parent.yaml"),
                        ActivationPolicy.importFullHistory()).document("root"); parentId = parent.id();
                var other = blue.documents().admitStaticProcessEmbedded(RootedSdkFixture.resource("source.yaml")
                        .replace("rcp2/source", "other"), ActivationPolicy.importFullHistory()).document("root"); otherId = other.id();
                append(blue, parent, timeline, 200, "child:\n  blueId: " + sourceId, "attach");
                append(blue, other, otherTimeline, 300, "counterValue: 7");
                scope.stage(); assertTrue(records.publish(attempt.prepare("seed", List.of(), EVIDENCE)));
            }
            var journalBytes = ColdStorageJournalFixture.retain(journal);
            blue.coordination.api.SourceHistoryPrerequisite source;
            try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                    .openLogical(LIMITS, configuration, attempt, provider, ColdStorageJournalFixture.open(journalBytes))) {
                var parent = scope.documentHandle(parentId).orElseThrow();
                assertEquals(ProcessingStageResult.Disposition.WAITING, scope.coordination().processing().processNextStage(parent).disposition());
                source = scope.coordination().advanced().sourceHistoryPrerequisites(parent).get(0);
                scope.stage(); assertTrue(records.publish(attempt.prepare("waiting", List.of(), EVIDENCE)));
            }
            try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                    .openLogical(LIMITS, configuration, attempt, provider, ColdStorageJournalFixture.open(journalBytes))) {
                assertTrue(scope.coordination().advanced().processSourceHistoryPrerequisite(source).admission().orElseThrow().published());
                scope.stage(); assertTrue(records.publish(attempt.prepare("source", List.of(), EVIDENCE)));
            }
            try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                    .openLogical(LIMITS, configuration, attempt, provider, ColdStorageJournalFixture.open(journalBytes))) {
                scope.coordination().processing().processNextStage(scope.documentHandle(parentId).orElseThrow());
                scope.stage(); assertTrue(records.publish(attempt.prepare("attach", List.of(), EVIDENCE)));
            }
            String parentHead; List<String> history;
            // when
            try (var a = records.attempt(); var b = records.attempt();
                 var left = RootedCoordinationStorage.controlledRepository(objects).openLogical(LIMITS, configuration, a, provider,
                         ColdStorageJournalFixture.open(journalBytes));
                 var right = RootedCoordinationStorage.controlledRepository(objects).openLogical(LIMITS, configuration, b, provider,
                         ColdStorageJournalFixture.open(journalBytes))) {
                var parent = left.documentHandle(parentId).orElseThrow();
                var stage = left.coordination().processing().processNextStage(parent);
                assertFalse(stage.managedEpochApplications().isEmpty(), "Fixture must select retained owner history");
                right.coordination().processing().processNextStage(right.documentHandle(otherId).orElseThrow());
                parentHead = parent.snapshot().blueId();
                history = left.coordination().advanced().auditManagedEpochs(parentId).stream().map(ManagedEpochReceipt::receiptIdentity).toList();
                left.stage(); right.stage(); var pa = a.prepare("parent", List.of(), EVIDENCE); var pb = b.prepare("other", List.of(), EVIDENCE);
                assertFalse(pa.artifacts().isEmpty()); assertFalse(pb.artifacts().isEmpty());
                // then
                assertTrue(pa.queries().stream().noneMatch(q -> q.range().family() == Family.WORK_DUE), "No global due prefix in a root stage");
                assertTrue(records.publish(reverse ? pb : pa)); assertTrue(records.publish(reverse ? pa : pb));
            }
            try (var attempt = records.attempt(); var cold = RootedCoordinationStorage.controlledRepository(objects.fresh())
                    .openLogical(LIMITS, configuration, attempt, provider, ColdStorageJournalFixture.open(journalBytes))) {
                assertEquals(parentHead, cold.documentHandle(parentId).orElseThrow().snapshot().blueId());
                assertEquals(7, cold.documentHandle(otherId).orElseThrow().snapshot().longAt("/counter"));
                assertEquals(history, cold.coordination().advanced().auditManagedEpochs(parentId).stream().map(ManagedEpochReceipt::receiptIdentity).toList());
            }
        }
    }

    private static String stageIdentity(ProcessingStageResult stage) {
        return stage.disposition() + "/" + stage.stats().gas() + "/" + stage.stats().committedTransitions() + "/" + stage.stats().documentStepOrder();
    }

    private static EntryHandle append(BlueCoordination blue, DocumentHandle root, TimelineHandle timeline, long time, String request) {
        return append(blue, root, timeline, time, request, "setCounter");
    }
    private static EntryHandle append(BlueCoordination blue, DocumentHandle root, TimelineHandle timeline, long time, String request, String operation) {
        return blue.events().from(timeline).exact(blue.values().yaml("""
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: %s}
                timestamp: %d
                actor: {type: MyOS/Principal Actor, accountId: alice}
                message:
                  type: Coordination/Operation Request
                  document: {blueId: %s}
                  requireExactDocumentVersion: false
                  operation: %s
                  channel: owner
                  request:
                %s
                """.formatted(timeline.id(), time, root.snapshot().blueId(), operation, request.indent(4)))).submit();
    }
}
