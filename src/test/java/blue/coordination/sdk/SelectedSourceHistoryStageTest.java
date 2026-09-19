package blue.coordination.sdk;

import blue.coordination.api.*;
import blue.coordination.api.storage.SourceHistoryStageStorage;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source work exposes authority before ADMIT/PROCESS and never retries its waiting parent. */
final class SelectedSourceHistoryStageTest {
    private static final int BYTES = 32 * 1024 * 1024;
    @Test void admissionAndEarlierLiveHaveIndependentFrozenOwnersWithoutExecutingFutureInputOrParent() throws Exception {
        // given
        var retained = new ArrayList<byte[]>();
        try (var fixture = new RootedSdkFixture()) {
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            var source = fixture.blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            fixture.exact.put(source.blueId(), source.json());
            fixture.timelines.put("rcp2/source", fixture.blue.timelines().register("rcp2/source", "alice"));
            var earlier = fixture.appendReference(source.blueId(), "rcp2/source", "setCounter", 15, "counterValue: 5", false);
            fixture.appendReference(source.blueId(), "rcp2/source", "setCounter", 50, "counterValue: 99", false);
            var input = fixture.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + source.blueId());
            assertEquals(ProcessingStageResult.Disposition.WAITING, fixture.blue.processing().processNextStage(parent).disposition());
            var parentHead = parent.snapshot().blueId(); var sourceId = DocumentId.of(source.blueId());
            var prerequisite = fixture.blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            var admission = fixture.blue.advanced().selectSourceHistoryStage(prerequisite);
            // when
            assertEquals(SourceHistoryPrerequisite.Kind.ADMISSION, admission.context().prerequisite().kind());
            assertEquals(List.of(sourceId), admission.context().entryOwners().stream().map(SourceHistoryStageContext.Owner::documentId).toList());
            assertTrue(admission.context().entryOwners().get(0).predecessor().isEmpty());
            assertThrows(IllegalStateException.class, () -> fixture.blue.timelines().register("intervening", "alice"));
            var admitted = admission.execute();
            // then
            assertTrue(admitted.result().admission().orElseThrow().published());
            retained.add(SourceHistoryStageStorage.encode(admitted, BYTES, 128));
            assertEquals(List.of(sourceId), admitted.resultOwners()); assertTrue(admitted.selectionInvalidated());
            assertEquals(parentHead, parent.snapshot().blueId());
            assertThrows(IllegalStateException.class, admission::execute);
            var replay = fixture.blue.advanced().selectSourceHistoryStage(prerequisite);
            assertTrue(replay.context().entryOwners().get(0).predecessor().isPresent());
            assertTrue(replay.execute().result().replayed());
            var livePrerequisite = fixture.blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            assertEquals(SourceHistoryPrerequisite.Kind.LIVE, livePrerequisite.kind());
            var live = fixture.blue.advanced().selectSourceHistoryStage(livePrerequisite);
            assertEquals(0, live.context().entryOwners().get(0).predecessor().orElseThrow().epoch());
            var completed = live.execute();
            retained.add(SourceHistoryStageStorage.encode(completed, BYTES, 128));
            assertEquals(List.of(sourceId), completed.resultOwners());
            assertEquals(List.of(earlier.blueId()), completed.result().processing().orElseThrow().processedEntries().stream().map(TimelineEntry::blueId).toList());
            assertEquals(5, fixture.blue.documents().require(sourceId).snapshot().longAt("/counter"));
            assertEquals(parentHead, parent.snapshot().blueId());
            assertFalse(fixture.blue.advanced().sourceHistoryProcessingResult(livePrerequisite).orElseThrow().entries().isEmpty());
            assertTrue(fixture.blue.processing().processNextStage(parent).entry(input).applied());
        }
        for (var encoded : retained) {
            assertArrayEquals(encoded, SourceHistoryStageStorage.encode(SourceHistoryStageStorage.decode(encoded, BYTES, 128), BYTES, 128));
            assertThrows(RuntimeException.class, () -> SourceHistoryStageStorage.decode(Arrays.copyOf(encoded, encoded.length - 1), BYTES, 128));
            assertThrows(RuntimeException.class, () -> SourceHistoryStageStorage.decode(encoded, 16, 128));
        }
    }

    @Test void foreignThreadAndRetiredScopeCannotConsumeFrozenSourceAuthority() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            var source = fixture.blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            fixture.timelines.put("rcp2/source", fixture.blue.timelines().register("rcp2/source", "alice"));
            fixture.exact.put(source.blueId(), source.json());
            fixture.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + source.blueId());
            fixture.blue.processing().processNextStage(parent);
            var selected = fixture.blue.advanced().selectSourceHistoryStage(fixture.blue.advanced().sourceHistoryPrerequisites(parent).get(0));
            var pool = Executors.newSingleThreadExecutor();
            // when
            try { pool.submit(() -> assertThrows(IllegalStateException.class, selected::execute)).get(10, TimeUnit.SECONDS); }
            finally { pool.shutdownNow(); }
            fixture.blue.close();
            // then
            assertThrows(IllegalStateException.class, selected::execute);
        }
    }

    @Test void sourceAdmissionWaitRetainsItsExactDemandWithoutPublishingOrExecutingParent() throws Exception {
        // given
        String missing;
        try (var preparation = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            missing = preparation.values().yaml("state: missing-from-source-stage\n").blueId();
        }
        byte[] encoded;
        try (var fixture = new RootedSdkFixture()) {
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            String source = "name: waiting source\npeer: {blueId: " + missing + "}\n"
                    + "contracts:\n  embedded:\n    type: Process Embedded\n    paths: [/peer]\n";
            fixture.append(parent, "rcp2/parent", "attach", 20, "child:\n" + source.indent(2));
            fixture.blue.processing().processNextStage(parent);
            var before = parent.snapshot().blueId();
            var selected = fixture.blue.advanced().selectSourceHistoryStage(fixture.blue.advanced().sourceHistoryPrerequisites(parent).get(0));
            // when
            var result = selected.execute();
            encoded = SourceHistoryStageStorage.encode(result, BYTES, 128);
            // then
            assertTrue(result.committable());
            var admission = result.result().admission().orElseThrow();
            assertFalse(admission.published()); assertFalse(admission.attempt().isComplete());
            assertTrue(admission.attempt().resourceDemands().stream().anyMatch(demand -> missing.equals(demand.suppliedValueBlueId())));
            assertTrue(result.selection().entryOwners().get(0).predecessor().isEmpty());
            assertEquals(before, parent.snapshot().blueId());
            assertThrows(RuntimeException.class, () -> fixture.blue.documents().require(result.selection().prerequisite().sourceDocumentId()));
        }
        assertArrayEquals(encoded, SourceHistoryStageStorage.encode(SourceHistoryStageStorage.decode(encoded, BYTES, 128), BYTES, 128));
    }
}
