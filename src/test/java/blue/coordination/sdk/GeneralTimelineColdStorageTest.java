package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationRecords.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real managed stages and logical atomic publication, with disposable runtimes between stages. */
final class GeneralTimelineColdStorageTest {
    private static final RootedCoordinationStorage.Limits LIMITS = RootedCoordinationStorageTest.LIMITS;
    private static final SdkStorageCodec CODEC = new SdkStorageCodec(new Object(), 32 * 1024 * 1024);
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});

    @Test void coldGeneralStageMatchesResidentHistoryAndOriginalResultAfterDiscardAndPublication() {
        // given
        RootedCoordinationStorage.Configuration configuration;
        byte[] expectedResult; String expectedHead; List<String> expectedHistory;
        try (var reference = new RootedSdkFixture()) {
            configuration = RootedCoordinationStorage.configuration(reference.blue, LIMITS);
            var root = reference.startYaml(GeneralTimelineScenario.document("probe/cold"), "probe/cold");
            var entry = reference.blue.events().from(reference.timelines.get("probe/cold"))
                    .exact(GeneralTimelineScenario.event(reference.blue, "probe/cold", 20, 7)).submit();
            var result = reference.blue.processing().processNextStage(root);
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, result.disposition());
            expectedResult = CODEC.encode(result.entry(entry)); expectedHead = root.snapshot().blueId();
            expectedHistory = reference.history(root);
        }
        var records = new SdkRuntimePointMapsTest.LogicalRecords(); var objects = new SdkRuntimePointMapsTest.Bytes();
        DocumentId rootId; String entryId; String initialHead;
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, configuration, attempt, ExactNodeProvider.empty())) {
            var blue = scope.coordination(); var timeline = blue.timelines().register("probe/cold", "alice");
            var root = blue.documents().admitStaticProcessEmbedded(GeneralTimelineScenario.document("probe/cold"),
                    ActivationPolicy.importFullHistory()).document("root");
            rootId = root.id(); initialHead = root.snapshot().blueId();
            entryId = blue.events().from(timeline).exact(GeneralTimelineScenario.event(blue, "probe/cold", 20, 7)).submit().blueId();
            scope.stage(); assertTrue(records.publish(attempt.prepare("general-admitted", List.of(), EVIDENCE)));
        }
        // when: the first complete outcome is discarded, then another owner calculates and publishes it.
        objects = objects.fresh();
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, configuration, attempt, ExactNodeProvider.empty())) {
            var root = scope.documentHandle(rootId).orElseThrow();
            assertEquals(initialHead, root.snapshot().blueId());
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, scope.coordination().processing().processNextStage(root).disposition());
            assertEquals(expectedHead, root.snapshot().blueId());
            scope.stage();
        }
        objects = objects.fresh();
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, configuration, attempt, ExactNodeProvider.empty())) {
            var root = scope.documentHandle(rootId).orElseThrow();
            assertEquals(initialHead, root.snapshot().blueId());
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, scope.coordination().processing().processNextStage(root).disposition());
            assertArrayEquals(expectedResult, CODEC.encode(scope.coordination().runtimeForStorage().storedMaps().results().get(entryId)));
            scope.stage(); assertTrue(records.publish(attempt.prepare("general-published", List.of(), EVIDENCE)));
        }
        // then: a cold owner reads the original result and exact history, with no duplicate application.
        objects = objects.fresh();
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, configuration, attempt, ExactNodeProvider.empty())) {
            var root = scope.documentHandle(rootId).orElseThrow(); var blue = scope.coordination();
            assertEquals(expectedHead, root.snapshot().blueId()); assertEquals(7, root.snapshot().longAt("/counter"));
            assertEquals(expectedHistory, blue.advanced().auditManagedEpochs(rootId).stream().map(ManagedEpochReceipt::receiptIdentity).toList());
            assertArrayEquals(expectedResult, CODEC.encode(blue.runtimeForStorage().storedMaps().results().get(entryId)));
            assertTrue(blue.advanced().auditTimelineEntry(entryId).orElseThrow().operationDetails().isEmpty());
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, blue.processing().processNextStage(root).disposition());
        }
    }
}
