package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.ExactNodeProvider;
import blue.coordination.sdk.RootedCoordinationStorage;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureProcessResultStorageCodec;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real SDK/storage integration, not a direct-codec benchmark. The maintained
 * source/parent fixture publishes five representation positions in the parent's
 * same epoch. Fresh SDK owners read those overlapping result frames through
 * both session views and publication receipts, then execute another real tick.
 * The 40/32 numbers are reader byte caps, NOT artificial 40 MiB payloads.
 */
final class RootedStorageResultProfileIntegrationTest {
    private static final int MIB = 1024 * 1024, RECORD = 40 * MIB, HISTORY = 5;
    private static final ClosureProcessResultStorageCodec RESULT = new ClosureProcessResultStorageCodec(RECORD, 256);
    private static final SessionRecordCodec REVISION = new SessionRecordCodec(RECORD, 256);

    @Test void realColdSdkOwnersShareExactResultsAcrossSessionAndPublicationByteProfiles() throws Exception {
        var originalBytes = new ObservedObjects();
        RootedCoordinationStorage.Selection selected;
        ColdStorageJournalFixture.Snapshot journal;
        Snapshot before, after;
        DocumentId source, parent;
        String nextEntryYaml, nextEntryId;
        try (var fixture = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            for (int position = 3; position <= HISTORY; position++) {
                var entry = fixture.append(position * 100L);
                assertEquals(EntryDisposition.APPLIED,
                        fixture.blue.processing().processNext(fixture.parent).entry(entry).disposition());
            }
            assertEquals(HISTORY, fixture.chain().transitions().size());
            source = fixture.source.id(); parent = fixture.parent.id();
            before = snapshot(fixture.engine, source, parent);
            selected = RootedCoordinationStorage.retainPartition(fixture.blue, originalBytes, limits(32 * MIB));
            journal = ColdStorageJournalFixture.retain(fixture.engine);
            nextEntryYaml = tickYaml(fixture.source.snapshot().blueId(), fixture.previous);
            var entry = fixture.append((HISTORY + 1) * 100L, "tick"); nextEntryId = entry.blueId();
            assertEquals(EntryDisposition.APPLIED,
                    fixture.blue.processing().processNext(fixture.parent).entry(entry).disposition());
            after = snapshot(fixture.engine, source, parent);
        }
        // Both readers receive identical stored bytes and a fresh independent
        // process cache. Never manufacture the old implementation with a flag.
        var failures = new ArrayList<String>();
        for (int publicationCap : List.of(RECORD, 32 * MIB)) {
            exercise(publicationCap, originalBytes.copy(), selected, journal, source, parent,
                    before, after, nextEntryYaml, nextEntryId, failures);
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static void exercise(int publicationCap, ObservedObjects objects,
            RootedCoordinationStorage.Selection selected, ColdStorageJournalFixture.Snapshot initialJournal,
            DocumentId source, DocumentId parent, Snapshot before, Snapshot after,
            String nextEntryYaml, String nextEntryId, List<String> failures) throws Exception {
        String variant = "record40-publication" + publicationCap / MIB;
        var identities = new ResultIdentities();
        try (var cache = new RootedCoordinationStorage.Cache(512L * MIB, 4096, 256L * MIB)) {
            var journal = ColdStorageJournalFixture.open(initialJournal);
            RootedCoordinationStorage.Selection staged = selected;
            for (int owner = 0; owner < 3; owner++) {
                String prefix = variant + "/owner" + owner;
                var readJournal = ColdStorageJournalFixture.open(initialJournal);
                try (var scope = phase(prefix + "/open", objects, cache, () -> RootedCoordinationStorage.open(
                        objects, limits(publicationCap), selected, ExactNodeProvider.empty(),
                        readJournal, cache))) {
                    BlueCoordination blue = scope.coordination();
                    var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
                    assertEquals(0L, CoordinationTestControl.attach(engine).metricsSnapshot().phaseNanos()
                            .getOrDefault("contracts.closure.processor", 0L));
                    List<ClosureProcessResult> views, receipts;
                    if (owner == 1) {
                        receipts = phase(prefix + "/publications-first", objects, cache,
                                () -> publicationResults(engine, before.publications().keySet()));
                        views = phase(prefix + "/sessions-second", objects, cache,
                                () -> sessionResults(engine, source, parent));
                    } else {
                        // Select through the public SDK before looking at the
                        // resulting internal objects for an identity assertion.
                        views = phase(prefix + "/sessions-first", objects, cache, () -> {
                            scope.documentHandle(parent).orElseThrow().snapshot();
                            scope.documentHandle(source).orElseThrow().snapshot();
                            return sessionResults(engine, source, parent);
                        });
                        receipts = phase(prefix + "/publications-second", objects, cache,
                                () -> publicationResults(engine, before.publications().keySet()));
                    }
                    phase(prefix + "/verify-exact-results-and-history", objects, cache, () -> {
                        views.forEach(value -> identities.observe(value, "session", failures));
                        receipts.forEach(value -> identities.observe(value, "publication", failures));
                        assertEquals(before, snapshot(engine, source, parent),
                                "Cold reads preserve all recorded history, events, gas and identities");
                        return null;
                    });
                    assertEquals(0, readJournal.mutations(), "Cold history reads never resubmit retained entries");
                    assertEquals(0L, objects.putCalls, "Reading and closing owners never writes physical storage");
                    assertEquals(0L, CoordinationTestControl.attach(engine).metricsSnapshot().phaseNanos()
                            .getOrDefault("contracts.closure.processor", 0L), "Restoration does not execute the processor");
                }
            }
            // A fourth fresh owner must still execute through the actual SDK,
            // not just deserialize values whose references happen to match.
            try (var scope = RootedCoordinationStorage.open(objects, limits(publicationCap), selected,
                    ExactNodeProvider.empty(), journal, cache)) {
                phase(variant + "/process-next-tick", objects, cache, () -> {
                    var blue = scope.coordination();
                    var entry = blue.events().from(scope.timelineHandle("rcp2/source").orElseThrow())
                            .exact(blue.values().yaml(nextEntryYaml)).submit();
                    assertEquals(nextEntryId, entry.blueId());
                    assertEquals(EntryDisposition.APPLIED,
                            blue.processing().processNext(scope.documentHandle(parent).orElseThrow()).entry(entry).disposition());
                    assertEquals(after, snapshot((DefaultCoordinationEngine) blue.advanced().rawEngine(), source, parent));
                    return null;
                });
                assertEquals(1, journal.mutations(), "Only the new tick appends; no retained entry is replayed");
                staged = phase(variant + "/stage-new-tick", objects, cache, scope::stage);
            }
            try (var reopened = RootedCoordinationStorage.open(objects, limits(publicationCap), staged,
                    ExactNodeProvider.empty(), ColdStorageJournalFixture.open(journal.snapshot()), cache)) {
                phase(variant + "/reopen-new-tick", objects, cache, () -> {
                    assertEquals(after, snapshot((DefaultCoordinationEngine) reopened.coordination().advanced().rawEngine(), source, parent));
                    return null;
                });
            }
            // Report before the hard identity assertion, so an old-family red
            // run still yields useful counts alongside its failure evidence.
            identities.report(variant, cache.statistics(), objects);
            assertEquals(0L, cache.statistics().evictions(), "This bounded fixture must retain the overlapping working set");
            assertTrue(identities.crossReaderFrames() >= HISTORY,
                    "The scenario must actually select overlapping source results through both readers");
            if (identities.duplicateFrames() != 0) failures.add(variant + ": " + identities.duplicateFrames()
                    + " exact frames acquired multiple result instances solely across readers/owners");
        }
    }

    private static List<ClosureProcessResult> sessionResults(DefaultCoordinationEngine engine, DocumentId... ids) {
        var results = new ArrayList<ClosureProcessResult>();
        for (DocumentId id : ids) engine.documents().require(id).storedState().rootedViewPositions()
                .forEach(position -> results.add(position.view().result()));
        return results;
    }

    private static List<ClosureProcessResult> publicationResults(DefaultCoordinationEngine engine, java.util.Set<String> keys) {
        return keys.stream().map(key -> engine.documents().closurePublicationReceipt(key).orElseThrow()
                .attempt().processResult()).toList();
    }

    private record Snapshot(Map<DocumentId, String> documents, Map<DocumentId, List<String>> revisions,
            List<String> representationPositions, Map<String, String> publications) { }

    private static Snapshot snapshot(DefaultCoordinationEngine engine, DocumentId source, DocumentId parent) {
        var documents = new LinkedHashMap<DocumentId, String>();
        var revisions = new LinkedHashMap<DocumentId, List<String>>();
        for (DocumentId id : List.of(source, parent)) {
            var session = engine.documents().require(id);
            documents.put(id, session.currentRepresentation().blueId());
            revisions.put(id, session.revisions().stream().map(revision -> hash(SessionStorageWire.encode(RECORD,
                    out -> REVISION.revision(out, revision)))).toList());
        }
        var positions = new ManagedRepresentationHistory(engine.documents()).at(parent, 0).transitions().stream()
                .map(row -> row.positionIdentity() + ":" + hash(RESULT.encode(row.originalResult()))).toList();
        var publications = new LinkedHashMap<String, String>();
        engine.documents().publicationSnapshot().closurePublicationReceipts().forEach((key, receipt) ->
                publications.put(key, hash(RESULT.encode(receipt.attempt().processResult()))));
        return new Snapshot(Map.copyOf(documents), Map.copyOf(revisions), positions, Map.copyOf(publications));
    }

    private static final class ResultIdentities {
        final Map<String, IdentityHashMap<ClosureProcessResult, Boolean>> frames = new LinkedHashMap<>();
        final Map<String, java.util.Set<String>> readers = new LinkedHashMap<>();
        final Map<String, String> invocationFrames = new LinkedHashMap<>();
        void observe(ClosureProcessResult result, String reader, List<String> failures) {
            String digest = hash(RESULT.encode(result));
            String prior = invocationFrames.putIfAbsent(result.invocationIdentity(), digest);
            if (prior != null && !prior.equals(digest)) failures.add("Same invocation changed its canonical complete result");
            frames.computeIfAbsent(digest, ignored -> new IdentityHashMap<>()).put(result, Boolean.TRUE);
            readers.computeIfAbsent(digest, ignored -> new java.util.LinkedHashSet<>()).add(reader);
        }
        long crossReaderFrames() { return readers.values().stream().filter(value -> value.size() == 2).count(); }
        long duplicateFrames() { return frames.values().stream().filter(value -> value.size() > 1).count(); }
        void report(String variant, RootedCoordinationStorage.Cache.Statistics cache, ObservedObjects objects) {
            System.out.printf("ROOTED_PROFILE_RESULT {\"variant\":\"%s\",\"historyPositions\":%d,\"exactFrames\":%d,"
                    + "\"crossReaderFrames\":%d,\"duplicateResultFrames\":%d,\"retainedAcceptedResultInstances\":%d,"
                    + "\"allFamilyCacheLoads\":%d,\"allFamilyCacheHits\":%d,\"cacheEvictions\":%d,"
                    + "\"getCalls\":%d,\"getBytes\":%d,\"putCalls\":%d,\"putCandidateBytes\":%d}%n",
                    variant, HISTORY, frames.size(), crossReaderFrames(), duplicateFrames(),
                    frames.values().stream().mapToInt(Map::size).sum(), cache.loads(), cache.hits(), cache.evictions(),
                    objects.getCalls, objects.getBytes, objects.putCalls, objects.putBytes);
        }
    }

    private static <T> T phase(String name, ObservedObjects objects, RootedCoordinationStorage.Cache cache, Supplier<T> work) {
        long started = System.nanoTime(), gets = objects.getCalls, bytes = objects.getBytes;
        long puts = objects.putCalls, putBytes = objects.putBytes;
        var before = cache.statistics();
        try { return work.get(); }
        finally {
            var after = cache.statistics();
            System.out.printf("ROOTED_PROFILE_PHASE {\"phase\":\"%s\",\"nanos\":%d,\"getCalls\":%d,\"getBytes\":%d,"
                    + "\"putCalls\":%d,\"putCandidateBytes\":%d,\"allFamilyCacheLoads\":%d,\"allFamilyCacheHits\":%d}%n",
                    name, System.nanoTime() - started, objects.getCalls - gets, objects.getBytes - bytes,
                    objects.putCalls - puts, objects.putBytes - putBytes, after.loads() - before.loads(), after.hits() - before.hits());
        }
    }

    private static RootedCoordinationStorage.Limits limits(int publicationCap) {
        return new RootedCoordinationStorage.Limits(new RootedEngineStorage.Limits(48 * MIB, 64 * 1024,
                publicationCap, 8192, 32, RECORD, 256, 512L * MIB, 100, 100, 100, 256L * MIB,
                48 * MIB, publicationCap, 16), new RootedCoordinationStorage.SdkLimits(
                new InsertionOrderedStorage.Limits(512 * 1024, 64 * 1024, 64 * 1024, 8192, 32,
                        256 * 1024, 4L * MIB, 100), 32 * MIB, 128 * 1024, 32 * MIB, 100, 256 * 1024));
    }

    private static String tickYaml(String target, String previous) {
        return """
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/source}
                timestamp: %d
                actor: {type: MyOS/Principal Actor, accountId: alice}
                message:
                  type: Coordination/Operation Request
                  document: {blueId: %s}
                  requireExactDocumentVersion: false
                  operation: tick
                  channel: owner
                  request: {}
                prevEntry: {blueId: %s}
                """.formatted((HISTORY + 1) * 100L, target, previous);
    }

    private static final class ObservedObjects implements CoordinationImmutableObjectStore {
        private final Map<String, byte[]> values = new LinkedHashMap<>();
        private long getCalls, getBytes, putCalls, putBytes;
        public byte[] putIfAbsent(String address, byte[] bytes) {
            putCalls++; putBytes += bytes.length;
            byte[] prior = values.putIfAbsent(address, bytes.clone());
            if (prior != null && !java.util.Arrays.equals(prior, bytes)) throw new CoordinationObjectStorageException("Address collision");
            return (prior == null ? bytes : prior).clone();
        }
        public Optional<byte[]> get(String address, int maximumBytes) {
            getCalls++; byte[] bytes = values.get(address);
            if (bytes == null) return Optional.empty();
            if (bytes.length > maximumBytes) throw new CoordinationObjectStorageException("Physical bound");
            getBytes += bytes.length; return Optional.of(bytes.clone());
        }
        ObservedObjects copy() {
            var copy = new ObservedObjects(); values.forEach((key, value) -> copy.values.put(key, value.clone())); return copy;
        }
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
