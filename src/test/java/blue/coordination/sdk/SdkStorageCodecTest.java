package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.BundledContracts10Release;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.DefaultCoordinationEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SDK metadata capture plus isolated owner-safe transport; not engine recovery. */
final class SdkStorageCodecTest {
    private static final int MAX = 32 * 1024 * 1024;
    @TempDir Path directory;

    @Test void actualRootedCycleFailureSurvivesBytesWithNewHandlesAndNoProcessing() throws Exception {
        // given
        var policy = ContractsExecutionPolicy.exactSharedGas(5_000, "sdk-storage-failure");
        try (var f = new RootedSdkFixture(policy)) {
            var b = f.start("cycle-b.yaml", "rcp2/cycle", Map.of());
            String aYaml = RootedSdkFixture.resource("cycle-a.yaml") + "\npeer:\n  blueId: " + b.snapshot().blueId() + "\n";
            var authored = f.blue.values().yaml(aYaml);
            f.exact.put(authored.blueId(), authored.json());
            var a = f.startYaml(aYaml, "rcp2/cycle");
            // when
            var connect = appendCycle(f, b, "connectA", 90, "a:\n  blueId: " + authored.blueId());
            // then
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(b).entry(connect).disposition());
            assertTrue(f.blue.processing().processNext(b).quiescent());
            var entry = appendCycle(f, a, "startLoop", 100, "{}");
            var before = List.of(a.snapshot().blueId(), b.snapshot().blueId(), f.history(a), f.history(b));
            var failed = f.blue.processing().processNext(a).entry(entry);
            assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED, failed.disposition());
            assertTrue(failed.stats().gas() > 0);
            long nanos = processingNanos(f.blue);
            Object nextOwner = new Object();
            var stored = roundTrip(f.blue, nextOwner);
            EntryResult restored = stored.results().get(entry.blueId());
            assertNotNull(restored);
            assertEquals(failed.disposition(), restored.disposition());
            assertEquals(failed.stats(), restored.stats());
            assertEquals(failed.diagnostic(), restored.diagnostic());
            assertEquals(failed.closures().get(0).closureId(), restored.closures().get(0).closureId());
            assertNotEquals(entry, restored.entry());
            assertSame(nextOwner, restored.entry().owner());
            assertEquals(entry.globalSequence(), restored.entry().globalSequence());
            assertEquals(entry.timelineSequence(), restored.entry().timelineSequence());
            assertEquals(policy, stored.configuration().policy());
            assertEquals(nanos, processingNanos(f.blue), "Metadata transport does not invoke PROCESS");
            assertEquals(before, List.of(a.snapshot().blueId(), b.snapshot().blueId(), f.history(a), f.history(b)));
            try (var newRuntime = runtime(nextOwner, policy)) {
                assertThrows(IllegalArgumentException.class, () -> newRuntime.drainJournalThrough(entry, new DrainBudget(1, 1)),
                        "The old owner's handle is rejected before journal selection");
            }
        }
    }

    @Test void actualSourceOwnedLiveResultAndStoppedParentRemainSeparate() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            var sourceInput = f.blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            f.exact.put(sourceInput.blueId(), sourceInput.json());
            f.timelines.put("rcp2/source", f.blue.timelines().register("rcp2/source", "alice"));
            var sourceEntry = f.appendReference(sourceInput.blueId(), "rcp2/source", "setCounter", 15, "counterValue: 5", false);
            var attach = f.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + sourceInput.blueId());
            // when
            var stopped = f.blue.processing().processNext(parent).entry(attach);
            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES, stopped.disposition());
            var admission = f.blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            assertEquals(SourceHistoryPrerequisite.Kind.ADMISSION, admission.kind());
            assertTrue(f.blue.advanced().processSourceHistoryPrerequisite(admission).admission().orElseThrow().published());
            var live = f.blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            assertEquals(SourceHistoryPrerequisite.Kind.LIVE, live.kind());
            f.blue.advanced().processSourceHistoryPrerequisite(live);
            var sourceResult = f.blue.advanced().sourceHistoryProcessingResult(live).orElseThrow();
            assertEquals(EntryDisposition.APPLIED, sourceResult.entry(sourceEntry).disposition());
            var before = List.of(parent.snapshot().blueId(), f.history(parent));
            long nanos = processingNanos(f.blue);
            var stored = roundTrip(f.blue, new Object());
            assertEquals(1, stored.sourceResults().size());
            var restored = stored.sourceResults().get(live);
            assertNotNull(restored);
            assertEquals(sourceResult.stats(), restored.stats());
            assertEquals(live.cutoffExclusive(), stored.sourceResults().keySet().iterator().next().cutoffExclusive());
            assertEquals(stopped.stats(), stored.results().get(attach.blueId()).stats());
            assertEquals(EntryDisposition.NEEDS_RESOURCES, stored.results().get(attach.blueId()).disposition());
            assertEquals(sourceEntry.blueId(), restored.entries().get(0).entry().blueId());
            assertEquals(nanos, processingNanos(f.blue));
            assertEquals(before, List.of(parent.snapshot().blueId(), f.history(parent)), "No implicit parent continuation");
        }
    }

    @Test void targetedIntentsActorKindAndRegisteredEmptyTimelinesRemainExact() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            var emptyAgent = f.blue.timelines().register("empty-agent", "agent-account", TimelineActorKind.AGENT);
            // when
            var result = f.blue.operations().on(source).from(f.timelines.get("rcp2/source"))
                    .call("setCounter").through("owner").requestYaml("counterValue: 5").execute();
            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            var stored = roundTrip(f.blue, new Object());
            var intent = stored.intents().get(result.entry().blueId());
            assertTrue(intent.targeted()); assertEquals(source.id(), intent.targetId());
            assertTrue(intent.targetPresentAtSubmission()); assertEquals("setCounter", intent.operation());
            assertEquals("owner", intent.channel()); assertEquals("alice", intent.actorId());
            assertEquals(TimelineActorKind.AGENT, stored.timelines().get(emptyAgent.id()).actorKind());
            var engine = (DefaultCoordinationEngine) f.blue.advanced().rawEngine();
            assertEquals("agent-account", engine.auditRegisteredTimeline("empty-agent").orElseThrow().actorId());
            assertTrue(engine.auditRegisteredTimeline("missing").isEmpty());
            assertTrue(engine.auditTimeline("empty-agent").isEmpty());
            assertTrue(engine.auditTimeline("missing").isEmpty());
        }
    }

    @Test void pristineInstallRejectsMismatchedConfigurationAndMissingEngineBeforeMutation() throws Exception {
        // given
        var policy = ContractsExecutionPolicy.releaseDefault();
        try (var empty = runtime(new Object(), policy); var receiver = runtime(new Object(), policy)) {
            byte[] original = empty.storageMetadata(MAX);
            // when
            receiver.installStorageMetadata(original, MAX);
            // then
            assertArrayEquals(original, receiver.storageMetadata(MAX));
            try (var wrong = runtime(new Object(), ContractsExecutionPolicy.exactSharedGas(99_999, "other-policy"))) {
                byte[] before = wrong.storageMetadata(MAX);
                assertThrows(CoordinationObjectStorageException.class, () -> wrong.installStorageMetadata(original, MAX));
                assertArrayEquals(before, wrong.storageMetadata(MAX));
            }
        }
        try (var f = new RootedSdkFixture(); var missingEngine = runtime(new Object(), policy)) {
            f.blue.timelines().register("empty-but-required", "alice");
            byte[] before = missingEngine.storageMetadata(MAX);
            byte[] captured = f.blue.advanced().storageMetadata(MAX);
            assertThrows(CoordinationObjectStorageException.class, () -> missingEngine.installStorageMetadata(captured, MAX));
            assertArrayEquals(before, missingEngine.storageMetadata(MAX));
        }
    }

    @Test void malformedAndOversizeFramesFailWithoutChangingLiveMetadata() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            f.start("source.yaml", "rcp2/source", Map.of());
            byte[] bytes = f.blue.advanced().storageMetadata(MAX);
            var codec = new SdkStorageCodec(new Object(), MAX);
            byte[] broken = bytes.clone();
            // when
            broken[broken.length / 2] ^= 1;
            // then
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(broken, SdkStorageCodec.Metadata.class));
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(Arrays.copyOf(bytes, bytes.length - 1), SdkStorageCodec.Metadata.class));
            assertThrows(CoordinationObjectStorageException.class, () -> new SdkStorageCodec(new Object(), bytes.length - 1)
                    .decode(bytes, SdkStorageCodec.Metadata.class));
            assertArrayEquals(bytes, f.blue.advanced().storageMetadata(MAX));
        }
    }

    @Test void submittedIntentCannotLoseItsRequiredCoreEntry() throws Exception {
        // given
        try (var f = new RootedSdkFixture(); var receiver = runtime(new Object(), ContractsExecutionPolicy.releaseDefault())) {
            var document = f.start("source.yaml", "rcp2/source", Map.of());
            f.blue.operations().on(document).from(f.timelines.get("rcp2/source"))
                    .call("setCounter").through("owner").requestYaml("counterValue: 5").execute();
            var codec = new SdkStorageCodec(new Object(), MAX);
            // when
            var stored = codec.decode(f.blue.advanced().storageMetadata(MAX), SdkStorageCodec.Metadata.class);
            // then
            assertFalse(stored.intents().isEmpty());
            var missing = new SdkStorageCodec.Metadata(stored.configuration(), stored.timelines(), stored.intents(),
                    stored.results(), Map.of(), stored.sourceResults());
            byte[] before = receiver.storageMetadata(MAX);
            var failure = assertThrows(CoordinationObjectStorageException.class,
                    () -> receiver.installStorageMetadata(codec.encode(missing), MAX));
            assertEquals("Stored SDK submission is missing its core entry", failure.getCause().getMessage());
            assertArrayEquals(before, receiver.storageMetadata(MAX));
        }
    }

    @Test void completeSdkManagedFailureAndRepresentationDtoFieldsAreNotDefaulted() {
        // This is an SDK presentation DTO control, not a manufactured processor
        // receipt or a claim that these arbitrary fixture identities authenticate.
        // given
        var source = blue.coordination.api.DocumentId.of("source");
        var consumer = blue.coordination.api.DocumentId.of("consumer");
        var before = new ManagedEpochApplicationWork.Position("anchor", "before", "after", Optional.empty());
        var after = new ManagedEpochApplicationWork.Position("anchor", "after", "after", Optional.empty());
        var representation = new ManagedEpochApplicationWork.RepresentationStep("cause", "source-before", "source-after", before, after, true);
        var work = new ManagedEpochApplicationWork("work", "plan", "barrier", "anchor", source, 2,
                consumer, "occurrence", "/child", 3, 4, "consumer-before", 5, Optional.of(representation), Optional.empty());
        var trace = new ManagedEpochApplicationAttempt.GasCharge(0, "processor", "charge", 1, 3, 3,
                consumer, "/", 0L, 5L, "contract", "/value", "work-occurrence", "reason");
        var rejectedWork = new ManagedEpochApplicationAttempt.RejectedWorkOccurrence(0, "INITIALIZE", consumer,
                "owner", "event", 1L, "scope", "source-occurrence", "work-occurrence");
        var rejected = new ManagedEpochApplicationAttempt.RejectedCharge("rejected", "processor", "next", 1, 4, 4,
                "SHARED", null, 0, "WORK", "work-occurrence", null, null, null);
        var diagnostic = new Diagnostic("GAS_LIMIT_EXCEEDED", "retained original failure", Map.of("detail", "exact"));
        var result = new ManagedEpochApplicationAttempt.ProcessResult(ManagedEpochApplicationAttempt.Status.GAS_LIMIT_EXCEEDED,
                false, true, true, "invocation", "input", "input", 5, 3, List.of(trace), "trace", rejectedWork,
                rejected, List.of(), diagnostic);
        var attempt = new ManagedEpochApplicationAttempt(work,
                new ManagedEpochApplicationAttempt.ProcessorAttempt(true, result, List.of(), List.of()),
                false, false, Optional.empty());
        var failure = new ManagedEpochEvidenceFailure(work, ManagedEpochEvidenceFailure.Status.WAITING_FOR_HISTORY,
                "MISSING_EXACT_RECEIPT", "receipt unavailable");
        var stats = new ProcessingStats(3, 0, 1, 12345, List.of(consumer), Map.of("processor.charge", 3L));
        var local = new ClosureResult("invocation", EntryDisposition.GAS_LIMIT_EXCEEDED, List.of(), List.of(), stats, diagnostic);
        var drain = new DrainResult(List.of(), stats, false, false, diagnostic, List.of(), List.of(attempt), List.of(failure),
                List.of(new DrainResult.RootedRetainedApplication(consumer, work, local)));
        var codec = new SdkStorageCodec(new Object(), MAX);
        byte[] bytes = codec.encode(drain);
        // when
        var restored = codec.decode(bytes, DrainResult.class);
        // then
        assertEquals(List.of(attempt), restored.managedEpochApplicationAttempts());
        assertEquals(List.of(failure), restored.managedEpochEvidenceFailures());
        assertEquals(drain.rootedRetainedApplications(), restored.rootedRetainedApplications());
        assertEquals(stats, restored.stats());
        assertArrayEquals(bytes, codec.encode(restored));
        try (var nonContracts = DefaultCoordinationEngine.create()) {
            assertThrows(IllegalStateException.class, nonContracts::contractsRuntimeBinding);
        }
    }

    private SdkStorageCodec.Metadata roundTrip(BlueCoordination blue, Object owner) throws Exception {
        byte[] bytes = blue.advanced().storageMetadata(MAX);
        Path path = directory.resolve("sdk-metadata.bin");
        Files.write(path, bytes);
        var codec = new SdkStorageCodec(owner, MAX);
        var decoded = codec.decode(Files.readAllBytes(path), SdkStorageCodec.Metadata.class);
        assertArrayEquals(bytes, codec.encode(decoded), "Every field and insertion order remains exact");
        for (var entry : decoded.results().values()) {
            assertSame(owner, entry.entry().owner());
            assertSame(owner, entry.entry().timeline().orElseThrow().owner());
        }
        for (var entry : decoded.entries().values()) entry.exactEvent().copyNode().name("caller-mutated copy");
        assertArrayEquals(bytes, codec.encode(decoded), "Caller Node mutation cannot alter retained metadata");
        return decoded;
    }

    private static long processingNanos(BlueCoordination blue) {
        return CoordinationTestControl.attach(blue.advanced().rawEngine()).metricsSnapshot()
                .phaseNanos().getOrDefault("contracts.closure.processor", 0L);
    }

    // Same exact cycle envelope as RootedCycleEntrypointTest; this fixture uses
    // ownerChannel rather than the generic parent/source fixture's owner key.
    private static EntryHandle appendCycle(RootedSdkFixture fixture, DocumentHandle target,
            String operation, long timestamp, String request) {
        String yaml = """
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: rcp2/cycle
                timestamp: %d
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
                message:
                  type: Coordination/Operation Request
                  document:
                    blueId: %s
                  requireExactDocumentVersion: false
                  operation: %s
                  channel: ownerChannel
                  request:
                %s
                """.formatted(timestamp, target.snapshot().blueId(), operation, request.indent(4));
        String previous = fixture.previousEntries.get("rcp2/cycle");
        if (previous != null) yaml += "\nprevEntry:\n  blueId: " + previous + "\n";
        var entry = fixture.blue.events().from(fixture.timelines.get("rcp2/cycle"))
                .exact(fixture.blue.values().yaml(yaml)).submit();
        fixture.previousEntries.put("rcp2/cycle", entry.blueId());
        return entry;
    }

    private static SdkCoordinationRuntime runtime(Object owner, ContractsExecutionPolicy policy) {
        var release = BundledContracts10Release.manifest();
        return SdkCoordinationRuntime.create(owner, release.blueLanguageSpecification(), release.contractsSpecification(),
                ExactNodeProvider.empty(), true, policy);
    }
}
