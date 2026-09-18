package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.language.processor.ExternalOrderKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Byte-only selected metadata component; engine setup is explicit, not cold-engine recovery. */
final class SdkPointStorageTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final SdkPointStorage.Limits LIMITS = new SdkPointStorage.Limits(MAX, 128 * 1024, MAX, 100);
    @TempDir Path directory;

    @Test void actualTargetedRowsReopenInNewOwnerWithoutProcessingOrUnselectedReads() throws Exception {
        // given
        try (var original = new RootedSdkFixture(); var receiver = new RootedSdkFixture()) {
            EntryResult first = execute(original);
            // when
            EntryResult independent = execute(receiver);
            // then
            assertEquals(first.entry().blueId(), independent.entry().blueId());
            var stored = new Stored(original.blue, directory);
            var freshObjects = new FilesStore(directory);
            var fresh = new SdkPointStorage(freshObjects, LIMITS, stored.metadata.configuration());
            long nanos = processingNanos(receiver.blue);
            var before = receiver.blue.advanced().storageMetadata(MAX);
            try (var scope = receiver.blue.advanced().openPointStorage(fresh, stored)) {
                assertEquals(0, freshObjects.reads.get(), "Opening does not enumerate or read any SDK row");
                EntryResult restored = scope.entryResult(first.entry().blueId(), stored.results.get(first.entry().blueId()));
                assertArrayEquals(stored.codec.encode(first), stored.codec.encode(restored));
                assertEquals(independent.entry(), restored.entry());
                assertNotEquals(first.entry(), restored.entry(), "Old handles do not acquire the new owner's identity");
                assertSame(restored, scope.entryResult(first.entry().blueId(), stored.results.get(first.entry().blueId())));
                assertEquals(2, freshObjects.reads.get(), "Only result and its Timeline are read, not intent/core/empty Timeline");
                assertThrows(IllegalArgumentException.class,
                        () -> receiver.blue.advanced().drainJournalThrough(first.entry(), new DrainBudget(1, 1)));
                var intent = scope.intent(first.entry().blueId(), stored.intents.get(first.entry().blueId()));
                assertTrue(intent.targeted());
                assertEquals(stored.metadata.intents().get(first.entry().blueId()), intent);
                assertEquals(4, freshObjects.reads.get(), "Intent selects its mandatory core row only on demand");
                var verified = scope.coreEntry(first.entry().blueId(), stored.entries.get(first.entry().blueId()));
                assertSame(((DefaultCoordinationEngine) receiver.blue.advanced().rawEngine())
                        .auditTimelineEntry(first.entry().blueId()).orElseThrow(), verified);
                verified.exactEvent().copyNode().name("caller copy");
                assertArrayEquals(stored.codec.encode(first), stored.codec.encode(restored));
                assertEquals(TimelineActorKind.AGENT, scope.timeline("empty-agent", stored.timelines.get("empty-agent")).actorKind());
                assertEquals(5, freshObjects.reads.get());
                assertEquals(nanos, processingNanos(receiver.blue));
                assertArrayEquals(before, receiver.blue.advanced().storageMetadata(MAX), "Selected reads do not install or change runtime maps");
            }
        }
    }

    @Test void completeSourceKeyAndStoppedParentArePreservedWithoutImplicitContinuation() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            var source = f.blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            f.exact.put(source.blueId(), source.json());
            f.timelines.put("rcp2/source", f.blue.timelines().register("rcp2/source", "alice"));
            var sourceEntry = f.appendReference(source.blueId(), "rcp2/source", "setCounter", 15, "counterValue: 5", false);
            var attach = f.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + source.blueId());
            // when
            var stopped = f.blue.processing().processNext(parent).entry(attach);
            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES, stopped.disposition());
            var admission = f.blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            assertTrue(f.blue.advanced().processSourceHistoryPrerequisite(admission).admission().orElseThrow().published());
            var live = f.blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            f.blue.advanced().processSourceHistoryPrerequisite(live);
            var sourceResult = f.blue.advanced().sourceHistoryProcessingResult(live).orElseThrow();
            assertEquals(EntryDisposition.APPLIED, sourceResult.entry(sourceEntry).disposition());
            var stored = new Stored(f.blue, directory);
            var before = List.of(parent.snapshot().blueId(), f.history(parent));
            long nanos = processingNanos(f.blue);
            try (var scope = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                var restored = scope.sourceResult(live, stored.sources.get(live));
                assertArrayEquals(stored.codec.encode(sourceResult), stored.codec.encode(restored));
                assertArrayEquals(stored.codec.encode(stopped), stored.codec.encode(scope.entryResult(attach.blueId(), stored.results.get(attach.blueId()))));
                // A colliding claimed selection identity is insufficient: every frozen/physical field belongs to the key.
                var wrongCutoff = new SourceHistoryPrerequisite(live.selectionIdentity(), live.requestingRoot(),
                        live.requestingInvocationIdentity(), live.demandIdentity(), live.sourceDocumentId(), live.authoredBlueId(),
                        ExternalOrderKey.of(List.of(999L)), live.kind(), live.sourceEpoch(), live.sourceBlueId(), live.workIdentity(),
                        live.entryBlueId(), live.journalRevision(), live.routeGeneration(), live.sourceSurfaceIdentity(), live.diagnostic());
                int reads = stored.objects.reads.get();
                assertThrows(CoordinationObjectStorageException.class, () -> scope.sourceResult(wrongCutoff, stored.sources.get(live)));
                assertEquals(reads, stored.objects.reads.get());
                assertEquals(nanos, processingNanos(f.blue));
                assertEquals(before, List.of(parent.snapshot().blueId(), f.history(parent)));
            }
        }
    }

    @Test void actualConfigurationAndRegisteredEmptyTimelineAreCheckedBeforeAuthorityIsReturned() throws Exception {
        // given
        try (var f = new RootedSdkFixture(); var absent = new RootedSdkFixture()) {
            f.blue.timelines().register("empty-agent", "agent-account", TimelineActorKind.AGENT);
            // when
            var stored = new Stored(f.blue, directory);
            try (var scope = absent.blue.advanced().openPointStorage(stored.storage, stored)) {
                // then
                assertThrows(CoordinationObjectStorageException.class, () -> scope.timeline("empty-agent", stored.timelines.get("empty-agent")));
            }
            var config = stored.metadata.configuration();
            for (var wrong : List.of(
                    new SdkStorageCodec.Configuration(config.language(), config.contracts(), config.bundledRelease(),
                            !config.contentDerivedDocumentIds(), config.policy(), config.bundledIdentities()),
                    new SdkStorageCodec.Configuration(config.language(), config.contracts(), config.bundledRelease(),
                            config.contentDerivedDocumentIds(), ContractsExecutionPolicy.exactSharedGas(99, "other"), config.bundledIdentities()),
                    new SdkStorageCodec.Configuration(config.language(), config.contracts(), config.bundledRelease(),
                            config.contentDerivedDocumentIds(), config.policy(), Map.of("release", "wrong")))) {
                var storage = new SdkPointStorage(stored.objects, LIMITS, wrong);
                int reads = stored.objects.reads.get();
                assertThrows(CoordinationObjectStorageException.class, () -> f.blue.advanced().openPointStorage(storage, stored));
                assertEquals(reads, stored.objects.reads.get());
            }
            Object forgedOwner = new Object();
            var wrongActor = stored.storage.retainTimeline("empty-agent", new TimelineHandle(forgedOwner, "empty-agent", "other", TimelineActorKind.AGENT));
            try (var scope = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                assertThrows(CoordinationObjectStorageException.class, () -> scope.timeline("empty-agent", wrongActor));
                assertThrows(CoordinationObjectStorageException.class, () -> scope.timeline("other", stored.timelines.get("empty-agent")));
            }
        }
    }

    @Test void intentRequiresExactJournalAndSelectedCoreRowWhileResultsNeedNotBeSdkSubmissions() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var result = execute(f);
            String id = result.entry().blueId();
            var stored = new Stored(f.blue, directory);
            // when
            var core = stored.entries.remove(id);
            try (var scope = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                // then
                assertThrows(CoordinationObjectStorageException.class, () -> scope.intent(id, stored.intents.get(id)));
                assertNotNull(scope.entryResult(id, stored.results.get(id)), "Advanced result retention does not imply SDK submission membership");
            }
            stored.entries.put(id, core);
            byte[] originalCore = Files.readAllBytes(directory.resolve(address(core)));
            Files.delete(directory.resolve(address(core)));
            try (var scope = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                assertThrows(CoordinationObjectStorageException.class, () -> scope.intent(id, stored.intents.get(id)),
                        "An existing descriptor with missing core bytes is not a broadcast/absent-intent fallback");
            }
            Files.write(directory.resolve(address(core)), originalCore);
            var value = stored.metadata.entries().get(id);
            var wrong = new SdkStorageCodec.CoreEntrySnapshot(value.exactEvent(), value.request(), value.journalOrderKey(),
                    value.sourceOrderKey(), value.timeline(), value.operation(), value.channel(), value.timestampMicros(),
                    value.globalSequence() + 1, value.timelineSequence());
            stored.entries.put(id, stored.storage.retainCoreEntry(id, wrong));
            try (var scope = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                assertThrows(CoordinationObjectStorageException.class, () -> scope.intent(id, stored.intents.get(id)));
            }
            stored.entries.put(id, core);
            var original = stored.metadata.intents().get(id);
            var mismatched = new SdkCoordinationRuntime.EntryIntent(true, original.targetId(), original.expectedTargetBlueId(),
                    original.targetPresentAtSubmission(), "wrong-operation", original.channel(), original.timelineId(), original.actorId());
            var wrongIntent = stored.storage.retainIntent(id, mismatched);
            try (var scope = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                assertThrows(CoordinationObjectStorageException.class, () -> scope.intent(id, wrongIntent));
            }
            var wrongTarget = new SdkCoordinationRuntime.EntryIntent(true, original.targetId(), "other-exact-target",
                    original.targetPresentAtSubmission(), original.operation(), original.channel(), original.timelineId(), original.actorId());
            var targetDescriptor = stored.storage.retainIntent(id, wrongTarget);
            try (var scope = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                assertThrows(CoordinationObjectStorageException.class, () -> scope.intent(id, targetDescriptor));
            }
        }
    }

    @Test void missingCorruptAndMisboundRowsNeverFallBackAndPhysicalFailureRemainsNoncommitting() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            f.blue.timelines().register("one", "alice"); f.blue.timelines().register("two", "bob");
            var stored = new Stored(f.blue, directory);
            var one = stored.timelines.get("one"); var two = stored.timelines.get("two");
            byte[] bytes = Files.readAllBytes(directory.resolve(address(one)));
            try (var warm = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                var retained = warm.timeline("one", one);
                // when
                Files.delete(directory.resolve(address(one)));
                // then
                assertSame(retained, warm.timeline("one", one), "Immutable memo is not a continuous storage scrubber");
                try (var cold = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                    assertThrows(CoordinationObjectStorageException.class, () -> cold.timeline("one", one));
                }
                Files.write(directory.resolve(address(one)), Arrays.copyOf(bytes, bytes.length - 1));
                try (var cold = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                    assertThrows(CoordinationObjectStorageException.class, () -> cold.timeline("one", one));
                }
            }
            Files.write(directory.resolve(address(one)), bytes);
            List<?> originalFields = stored.codec.decode(one.bytes(), List.class);
            var fields = new ArrayList<Object>(originalFields);
            List<?> other = stored.codec.decode(two.bytes(), List.class);
            fields.set(4, other.get(4)); fields.set(5, other.get(5));
            var swapped = stored.storage.descriptor(stored.codec.encode(fields));
            try (var scope = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                assertThrows(CoordinationObjectStorageException.class, () -> scope.timeline("one", swapped));
                var outage = new CoordinationObjectStorageException("offline");
                stored.objects.failure = outage;
                assertSame(outage, assertThrows(CoordinationObjectStorageException.class, () -> scope.timeline("two", two)));
                assertSame(outage, assertThrows(CoordinationObjectStorageException.class,
                        () -> stored.storage.retainTimeline("new", new TimelineHandle(new Object(), "new", "alice"))));
                stored.objects.failure = null;
                assertEquals("one", scope.timeline("one", one).id(), "Failed retention did not publish or damage prior descriptors");
            }
        }
    }

    @Test void byteBoundsEvictionAndDescriptorOwnershipRemainExplicit() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            f.blue.timelines().register("one", "alice"); f.blue.timelines().register("two", "bob");
            var stored = new Stored(f.blue, directory);
            var one = stored.timelines.get("one"); var two = stored.timelines.get("two");
            byte[] copy = one.bytes(); var owned = stored.storage.descriptor(copy);
            // when
            copy[0] ^= 1;
            // then
            assertEquals(one, owned);
            var single = new SdkPointStorage(stored.objects, new SdkPointStorage.Limits(MAX, 128 * 1024, MAX, 1), stored.metadata.configuration());
            try (var scope = f.blue.advanced().openPointStorage(single, stored)) {
                var first = scope.timeline("one", owned);
                assertSame(first, scope.timeline("one", one));
                scope.timeline("two", two);
                assertNotSame(first, scope.timeline("one", one));
                assertEquals(3, stored.objects.reads.get());
                assertEquals(1, scope.memoEntries()); assertTrue(scope.chargedBytes() <= MAX);
            }
            var noAdmission = new SdkPointStorage(stored.objects, new SdkPointStorage.Limits(MAX, 128 * 1024, 1, 100), stored.metadata.configuration());
            try (var scope = f.blue.advanced().openPointStorage(noAdmission, stored)) {
                assertNotSame(scope.timeline("one", one), scope.timeline("one", one));
                assertEquals(0, scope.memoEntries()); assertEquals(0, scope.chargedBytes());
            }
            int rowLength = Files.readAllBytes(directory.resolve(address(one))).length;
            var tooSmall = new SdkPointStorage(stored.objects, new SdkPointStorage.Limits(rowLength - 1, 128 * 1024, MAX, 100), stored.metadata.configuration());
            int before = stored.objects.reads.get();
            try (var scope = f.blue.advanced().openPointStorage(tooSmall, stored)) {
                assertThrows(CoordinationObjectStorageException.class, () -> scope.timeline("one", one));
            }
            assertEquals(before, stored.objects.reads.get(), "Length bound is checked before object allocation/read");
            var descriptorBound = new SdkPointStorage(stored.objects, new SdkPointStorage.Limits(MAX, one.bytes().length - 1, MAX, 100), stored.metadata.configuration());
            try (var scope = f.blue.advanced().openPointStorage(descriptorBound, stored)) {
                assertThrows(CoordinationObjectStorageException.class, () -> scope.timeline("one", one));
            }
            assertEquals(before, stored.objects.reads.get());
            assertThrows(CoordinationObjectStorageException.class, () -> descriptorBound.descriptor(one.bytes()));
        }
    }

    @Test void concurrentSelectedReadsShareOnlyTheirOwnerScopeAndCloseReleasesMemo() throws Exception {
        // given
        try (var f = new RootedSdkFixture(); var second = new RootedSdkFixture()) {
            f.blue.timelines().register("one", "alice"); second.blue.timelines().register("one", "alice");
            var stored = new Stored(f.blue, directory);
            var scope = f.blue.advanced().openPointStorage(stored.storage, stored);
            var pool = Executors.newFixedThreadPool(4);
            try {
                var tasks = new ArrayList<java.util.concurrent.Callable<TimelineHandle>>();
                for (int i = 0; i < 12; i++) tasks.add(() -> scope.timeline("one", stored.timelines.get("one")));
                // when
                var values = pool.invokeAll(tasks);
                for (var value : values)
                // then
                assertSame(values.get(0).get(), value.get());
                assertEquals(1, stored.objects.reads.get());
                try (var foreign = second.blue.advanced().openPointStorage(stored.storage, stored)) {
                    var handle = foreign.timeline("one", stored.timelines.get("one"));
                    assertNotEquals(values.get(0).get(), handle);
                    assertEquals(2, stored.objects.reads.get(), "No decoded value/owner is shared across scopes");
                }
            } finally { pool.shutdownNow(); scope.close(); }
            assertEquals(0, scope.memoEntries()); assertEquals(0, scope.chargedBytes());
            assertThrows(CoordinationObjectStorageException.class, () -> scope.timeline("one", stored.timelines.get("one")));
            try (var after = f.blue.advanced().openPointStorage(stored.storage, stored)) {
                after.timeline("one", stored.timelines.get("one")); f.blue.close();
                assertThrows(CoordinationObjectStorageException.class, () -> after.timeline("one", stored.timelines.get("one")));
            }
        }
    }

    private static EntryResult execute(RootedSdkFixture f) throws IOException {
        var source = f.start("source.yaml", "rcp2/source", Map.of());
        f.blue.timelines().register("empty-agent", "agent-account", TimelineActorKind.AGENT);
        var result = f.blue.operations().on(source).from(f.timelines.get("rcp2/source"))
                .call("setCounter").through("owner").requestYaml("counterValue: 5").execute();
        assertEquals(EntryDisposition.APPLIED, result.disposition());
        return result;
    }
    private static long processingNanos(BlueCoordination blue) {
        return CoordinationTestControl.attach(blue.advanced().rawEngine()).metricsSnapshot()
                .phaseNanos().getOrDefault("contracts.closure.processor", 0L);
    }
    private static String address(SdkPointStorage.Descriptor descriptor) {
        return (String) new SdkStorageCodec(new Object(), MAX).decode(descriptor.bytes(), List.class).get(4);
    }

    private static final class Stored implements SdkPointStorage.References {
        final FilesStore objects;
        final SdkStorageCodec codec = new SdkStorageCodec(new Object(), MAX);
        final SdkStorageCodec.Metadata metadata;
        final SdkPointStorage storage;
        final Map<String, SdkPointStorage.Descriptor> timelines = new LinkedHashMap<>(), intents = new LinkedHashMap<>(),
                results = new LinkedHashMap<>(), entries = new LinkedHashMap<>();
        final Map<SourceHistoryPrerequisite, SdkPointStorage.Descriptor> sources = new LinkedHashMap<>();
        Stored(BlueCoordination blue, Path directory) {
            objects = new FilesStore(directory);
            // Whole export is fixture capture only; selected storage never performs this scan.
            metadata = codec.decode(blue.advanced().storageMetadata(MAX), SdkStorageCodec.Metadata.class);
            storage = new SdkPointStorage(objects, LIMITS, metadata.configuration());
            metadata.timelines().forEach((id, value) -> timelines.put(id, storage.retainTimeline(id, value)));
            metadata.intents().forEach((id, value) -> intents.put(id, storage.retainIntent(id, value)));
            metadata.results().forEach((id, value) -> results.put(id, storage.retainEntryResult(id, value)));
            metadata.entries().forEach((id, value) -> entries.put(id, storage.retainCoreEntry(id, value)));
            metadata.sourceResults().forEach((key, value) -> sources.put(key, storage.retainSourceResult(key, value)));
        }
        @Override public SdkPointStorage.Descriptor timeline(String id) { return timelines.get(id); }
        @Override public SdkPointStorage.Descriptor coreEntry(String id) { return entries.get(id); }
    }

    private static final class FilesStore implements CoordinationImmutableObjectStore {
        final Path directory;
        final AtomicInteger reads = new AtomicInteger();
        RuntimeException failure;
        FilesStore(Path directory) { this.directory = directory; }
        @Override public byte[] putIfAbsent(String address, byte[] bytes) {
            if (failure != null) throw failure;
            try {
                Path path = directory.resolve(address);
                if (!Files.exists(path)) Files.write(path, bytes);
                byte[] actual = Files.readAllBytes(path);
                if (!Arrays.equals(actual, bytes)) throw new CoordinationObjectStorageException("Conflicting immutable fixture object");
                return actual;
            } catch (IOException failure) { throw new CoordinationObjectStorageException("Fixture I/O", failure); }
        }
        @Override public Optional<byte[]> get(String address, int maximumBytes) {
            if (failure != null) throw failure;
            reads.incrementAndGet();
            try {
                Path path = directory.resolve(address);
                if (!Files.exists(path)) return Optional.empty();
                if (Files.size(path) > maximumBytes) throw new CoordinationObjectStorageException("Fixture read bound");
                return Optional.of(Files.readAllBytes(path));
            } catch (IOException failure) { throw new CoordinationObjectStorageException("Fixture I/O", failure); }
        }
    }
}
