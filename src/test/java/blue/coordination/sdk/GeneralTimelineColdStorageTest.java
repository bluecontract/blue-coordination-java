package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationRecords.Bytes;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real managed stages and logical atomic publication, with disposable runtimes between stages. */
final class GeneralTimelineColdStorageTest {
    private static final RootedCoordinationStorage.Limits LIMITS = RootedCoordinationStorageTest.LIMITS;
    private static final SdkStorageCodec CODEC = new SdkStorageCodec(new Object(), 32 * 1024 * 1024);
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void coldGeneralStageMatchesResidentHistoryAndOriginalResultAfterDiscardAndPublication(boolean referenced) {
        // given
        var retained = new java.util.HashMap<String, String>();
        String messageId;
        try (var values = BlueCoordination.inMemory()) {
            var type = values.values().yaml("name: CreditRecordedBusinessType\n");
            var message = values.values().yaml("type: {blueId: " + type.blueId() + "}\nkind: CreditRecorded\namount: 7\n");
            retained.put(type.blueId(), type.json()); retained.put(message.blueId(), message.json()); messageId = message.blueId();
        }
        ExactNodeProvider provider = id -> java.util.Optional.ofNullable(retained.get(id));
        RootedCoordinationStorage.Configuration configuration;
        byte[] expectedResult; String expectedHead; List<String> expectedHistory;
        try (var reference = new RootedSdkFixture()) {
            reference.exact.putAll(retained);
            configuration = RootedCoordinationStorage.configuration(reference.blue, LIMITS);
            var root = reference.startYaml(GeneralTimelineScenario.document("probe/cold"), "probe/cold");
            var entry = reference.blue.events().from(reference.timelines.get("probe/cold"))
                    .exact(input(reference.blue, referenced, messageId)).submit();
            var result = reference.blue.processing().processNextStage(root);
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, result.disposition());
            expectedResult = CODEC.encode(result.entry(entry)); expectedHead = root.snapshot().blueId();
            expectedHistory = reference.history(root);
        }
        var records = new SdkRuntimePointMapsTest.LogicalRecords(); var objects = new SdkRuntimePointMapsTest.Bytes();
        DocumentId rootId; String entryId; String initialHead;
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, configuration, attempt, provider)) {
            var blue = scope.coordination(); var timeline = blue.timelines().register("probe/cold", "alice");
            var root = blue.documents().admitStaticProcessEmbedded(GeneralTimelineScenario.document("probe/cold"),
                    ActivationPolicy.importFullHistory()).document("root");
            rootId = root.id(); initialHead = root.snapshot().blueId();
            entryId = blue.events().from(timeline).exact(input(blue, referenced, messageId)).submit().blueId();
            scope.stage(); assertTrue(records.publish(attempt.prepare("general-admitted", List.of(), EVIDENCE)));
        }
        // when
        // The first complete outcome is discarded, then another owner calculates and publishes it.
        objects = objects.fresh();
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, configuration, attempt, provider)) {
            var root = scope.documentHandle(rootId).orElseThrow();
            assertEquals(initialHead, root.snapshot().blueId());
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, scope.coordination().processing().processNextStage(root).disposition());
            assertEquals(expectedHead, root.snapshot().blueId());
            scope.stage();
        }
        objects = objects.fresh();
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, configuration, attempt, provider)) {
            var root = scope.documentHandle(rootId).orElseThrow();
            assertEquals(initialHead, root.snapshot().blueId());
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, scope.coordination().processing().processNextStage(root).disposition());
            assertArrayEquals(expectedResult, CODEC.encode(scope.coordination().runtimeForStorage().storedMaps().results().get(entryId)));
            scope.stage(); assertTrue(records.publish(attempt.prepare("general-published", List.of(), EVIDENCE)));
        }
        // then
        // A cold owner reads the original result and exact history, with no duplicate application.
        objects = objects.fresh();
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, configuration, attempt, provider)) {
            var root = scope.documentHandle(rootId).orElseThrow(); var blue = scope.coordination();
            assertEquals(expectedHead, root.snapshot().blueId()); assertEquals(7, root.snapshot().longAt("/counter"));
            assertEquals(expectedHistory, blue.advanced().auditManagedEpochs(rootId).stream().map(ManagedEpochReceipt::receiptIdentity).toList());
            assertArrayEquals(expectedResult, CODEC.encode(blue.runtimeForStorage().storedMaps().results().get(entryId)));
            var audit = blue.advanced().auditTimelineEntry(entryId).orElseThrow();
            assertTrue(audit.operationDetails().isEmpty());
            assertEquals(entryId, audit.exact().blueId());
            if (referenced) assertEquals(messageId, audit.exact().valueAt("/message").blueId());
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, blue.processing().processNextStage(root).disposition());
        }
    }
    private static ExactBlueValue input(BlueCoordination blue, boolean referenced, String messageId) {
        if (!referenced) return GeneralTimelineScenario.event(blue, "probe/cold", 20, 7);
        return blue.values().yaml("""
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: probe/cold}
                actor: {type: MyOS/Principal Actor, accountId: alice}
                timestamp: 20
                message: {blueId: %s}
                """.formatted(messageId));
    }

}
