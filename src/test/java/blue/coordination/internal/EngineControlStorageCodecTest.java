package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.Timeline;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.TimelineActorKind;
import blue.language.processor.ExternalOrderKey;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Control-only transport; document/journal indexes are not restored by these tests. */
final class EngineControlStorageCodecTest {
    private static final int MAX = 4 * 1024 * 1024;
    @TempDir Path directory;

    @Test void actualRootedProcessingCapturesExactClocksRegistrationsAndTerminalProgress() throws Exception {
        // given
        try (var blue = sdk(Map.of())) {
            var timeline = blue.timelines().register("rcp2/source", "alice");
            blue.timelines().register("empty-agent", "agent", TimelineActorKind.AGENT);
            var source = blue.documents().admitStaticProcessEmbedded(resource("source.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            // when
            var result = blue.operations().on(source).from(timeline).call("setCounter").through("owner")
                    .requestYaml("counterValue: 5").execute();
            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            var before = engine.auditDocument(source.id());
            var history = engine.auditManagedEpochs(source.id());
            var state = engine.controlStateForStorage();
            assertTrue(state.publicRoots().contains(source.id()));
            assertEquals("MyOS/MyOS Agent Actor", state.actorKinds().get("empty-agent"));
            assertTrue(state.applicationClockMicros() >= 1_800_000_000_000_000L);
            assertTrue(state.logicalClockMicros() > 1_800_000_000_000_000L);
            var codec = new EngineControlStorageCodec(MAX); byte[] bytes = codec.encode(state);
            Path file = directory.resolve("control.bytes"); Files.write(file, bytes);
            var restored = new EngineControlStorageCodec(MAX).decode(Files.readAllBytes(file));
            assertEquals(state, restored); assertArrayEquals(bytes, codec.encode(restored));
            assertNotNull(DefaultCoordinationEngine.recoveryStateFromStorage(restored));
            assertEquals(before, engine.auditDocument(source.id())); assertEquals(history, engine.auditManagedEpochs(source.id()));
        }
    }

    @Test void exactFairnessFrontiersAndArbitraryOrderKeysAreOwnedAndRestorable() {
        // given
        var root = DocumentId.of("root");
        var order = ExternalOrderKey.of(List.of(BigInteger.ONE.shiftLeft(130), "\uD800-order"));
        var lane = ContractsRootFeederWindow.LaneId.publicRoots(List.of(root));
        var ticket = new ContractsRootFeederWindow.AttemptTicket("entry", order, 0, lane, "invocation", List.of(root));
        var feeder = new ContractsRootFeederWindow.StorageState(
                List.of(new ContractsRootFeederWindow.TerminalProgress(ticket, false, false)), Map.of(lane, order));
        var journal = new ContractsJournalDrainCoordinator.StorageState(
                List.of(new ContractsJournalDrainCoordinator.EntryKey("entry", order)), order);
        Map<DocumentId,String> isolated = new LinkedHashMap<>(); isolated.put(root, "exact-failed-work");
        var schedule = new RootedProcessingSchedule.StorageState(true, List.of(root), isolated);
        isolated.clear();
        var binding = new DefaultCoordinationEngine.ContractsRuntimeBinding(
                BundledContracts10Release.manifest().blueLanguageSpecification(),
                BundledContracts10Release.manifest().contractsSpecification(), blue.coordination.api.ContractsExecutionPolicy.releaseDefault());
        var state = new EngineControlStorageCodec.State(binding, Map.of("t", new Timeline("t", "actor\uDFFF")),
                Map.of("t", "MyOS/Principal Actor"), 1_800_000_000_000_002L, 1_800_000_000_000_001L,
                List.of(root), schedule, List.of(root), List.of(root), true, feeder, journal);
        var codec = new EngineControlStorageCodec(MAX);
        // when
        var restored = codec.decode(codec.encode(state));
        // then
        assertEquals(state, restored);
        assertEquals(schedule, RootedProcessingSchedule.fromStorage(restored.rootedSchedule()).storageState());
        assertEquals(feeder, ContractsRootFeederWindow.DurableState.fromStorage(restored.feeder()).storageState());
        assertEquals(journal, ContractsJournalDrainCoordinator.DurableState.fromStorage(restored.journal()).storageState());
        assertThrows(UnsupportedOperationException.class, () -> restored.actorKinds().clear());
    }

    @Test void malformedFramesAndInconsistentTerminalFrontierFailClosed() {
        // given
        var codec = new EngineControlStorageCodec(MAX);
        try (var blue = sdk(Map.of())) {
            var state = ((DefaultCoordinationEngine) blue.advanced().rawEngine()).controlStateForStorage();
            byte[] bytes = codec.encode(state); byte[] corrupt = bytes.clone();
            // when
            corrupt[corrupt.length / 2] ^= 1;
            // then
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(corrupt));
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
            assertThrows(CoordinationObjectStorageException.class, () -> new EngineControlStorageCodec(bytes.length - 1).decode(bytes));
            var lane = ContractsRootFeederWindow.LaneId.publicRoots(List.of(DocumentId.of("root")));
            assertThrows(IllegalArgumentException.class, () -> new ContractsRootFeederWindow.StorageState(
                    List.of(), Map.of(lane, ExternalOrderKey.of(List.of(BigInteger.ONE)))));
        }
    }

    @Test void genuinePendingSourceEvidenceCannotBeSilentlyOmitted() throws Exception {
        // given
        Map<String,String> content = new LinkedHashMap<>();
        try (var blue = sdk(content)) {
            var timeline = blue.timelines().register("rcp2/parent", "alice");
            var parent = blue.documents().admitStaticProcessEmbedded(resource("parent.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            var source = blue.values().yaml(resource("source.yaml")); content.put(source.blueId(), source.json());
            // when
            var result = blue.operations().on(parent).from(timeline).call("attach").through("owner")
                    .requestYaml("child:\n  blueId: " + source.blueId()).execute();
            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES, result.disposition());
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            var failure = assertThrows(CoordinationObjectStorageException.class, engine::controlStateForStorage);
            assertTrue(failure.getMessage().contains("Retained source discovery"), failure.getMessage());
            assertFalse(blue.advanced().sourceHistoryPrerequisites(parent).isEmpty());
        }
    }

    private static BlueCoordination sdk(Map<String,String> exact) {
        return BlueCoordination.builder().contentDerivedDocumentIds()
                .release(BundledContracts10Release.manifest().blueLanguageSpecification(),
                        BundledContracts10Release.manifest().contractsSpecification())
                .exactNodeProvider(id -> Optional.ofNullable(exact.get(id))).build();
    }
    private static String resource(String name) throws Exception {
        try (var in = EngineControlStorageCodecTest.class.getResourceAsStream("/rooted/" + name)) {
            return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
