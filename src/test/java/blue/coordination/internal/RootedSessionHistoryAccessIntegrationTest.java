package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.ExactNodeProvider;
import blue.coordination.sdk.RootedCoordinationStorage;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ClosureProcessResultStorageCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.RootedHistoryAccessObjects.digest;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P2 baseline on actual SDK-generated history and complete external-store installation.
 * Construction and full semantic oracles are outside every measured phase. Physical
 * record accesses are not claimed to be decoder calls, SQL traffic or retained heap.
 * The three explicitly named numbered tests let a runner qualify five epochs first.
 */
final class RootedSessionHistoryAccessIntegrationTest {
    private static final int MIB = 1024 * 1024, RECORD = 40 * MIB;
    private static final SessionRecordCodec ROWS = new SessionRecordCodec(RECORD, 256);
    private static final ClosureProcessResultStorageCodec RESULTS = new ClosureProcessResultStorageCodec(RECORD, 256);
    // viewAddress only encodes/hashes; this oracle never reads or stages an object.
    private static final DocumentSessionStorage VIEW_IDENTITIES = new DocumentSessionStorage(
            new RootedHistoryAccessObjects(), new DocumentSessionStorage.Limits(RECORD, 256, 512L * MIB));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RootedCoordinationStorage.Limits LIMITS = new RootedCoordinationStorage.Limits(
            new RootedEngineStorage.Limits(48 * MIB, 64 * 1024, 32 * MIB, 8192, 32,
                    RECORD, 256, 512L * MIB, 100, 100, 100, 256L * MIB, 48 * MIB, 32 * MIB, 16),
            new RootedCoordinationStorage.SdkLimits(new InsertionOrderedStorage.Limits(512 * 1024, 64 * 1024,
                    64 * 1024, 8192, 32, 256 * 1024, 4L * MIB, 100), 32 * MIB, 128 * 1024, 32 * MIB, 100, 256 * 1024));

    @Test void fiveNumberedEpochsColdPointReadsAndSuccessor() throws Exception { exercise(5, false); }
    @Test void twentyNumberedEpochsColdPointReadsAndSuccessor() throws Exception { exercise(20, false); }
    @Test void fiftyNumberedEpochsColdPointReadsAndSuccessor() throws Exception { exercise(50, false); }
    @Test void fiveSameEpochPositionsColdPointReadAndSuccessor() throws Exception { exercise(5, true); }

    private static void exercise(int historySize, boolean sameEpoch) throws Exception {
        Fixture f = construct(historySize, sameEpoch);
        var measurements = new ArrayList<Measured>();
        report(Map.of("kind", "fixture", "workload", f.name(), "graphDocuments", 2,
                "numberedParentEpochs", f.before().epochs().get(f.parent()),
                "parentRetainedViewPositions", f.before().views().get(f.parent()).size(),
                "sessionDescriptorBytes", f.objects().sessionDescriptors(),
                "physicalCategoryInventory", f.objects().categoryInventory(),
                "cache", "disabled", "decoderCountsAvailable", false,
                "requiredPayloadPolicy", "Point plus fixed current/anchor/predecessor boundary records, declared before reads"));

        // Each independent probe starts from detached bytes with no resident or L1 warming.
        readProbe(f, "current", measurements, scope -> scope.documentHandle(f.parent()).orElseThrow().snapshot().blueId(),
                f.before().heads().get(f.parent()), f.currentBoundaries());
        var revisionRequired = union(f.currentBoundaries(), Set.of(f.revisionAddress()));
        readProbe(f, "historical-revision", measurements,
                scope -> engine(scope).documents().require(f.parent()).revision(f.historicalEpoch()),
                f.revisionDigest(), revisionRequired);
        readProbe(f, "historical-position", measurements,
                scope -> engine(scope).documents().require(f.parent()).rootedViewBefore(f.historicalBoundary()),
                f.viewIdentity(), union(f.currentBoundaries(), f.positionBoundaries()));

        var objects = f.objects().detachedCopy();
        var journal = ColdStorageJournalFixture.open(f.journal());
        RootedCoordinationStorage.Selection staged;
        try (var scope = phase(f, "successor/open", objects, f.currentBoundaries(), measurements,
                () -> RootedCoordinationStorage.open(objects, LIMITS, f.selection(), ExactNodeProvider.empty(), journal))) {
            var entry = phase(f, "successor/submit", objects, f.currentBoundaries(), measurements,
                    () -> scope.coordination().events().from(scope.timelineHandle("rcp2/source").orElseThrow())
                            .exact(scope.coordination().values().yaml(f.nextYaml())).submit());
            assertEquals(f.nextEntryId(), entry.blueId());
            var result = phase(f, "successor/process", objects, f.currentBoundaries(), measurements,
                    () -> scope.coordination().processing().processNext(scope.documentHandle(f.parent()).orElseThrow()));
            assertEquals(EntryDisposition.APPLIED, result.entry(entry).disposition());
            assertEquals(1, journal.mutations(), "Only the new input is submitted; no old input is replayed");
            // Stage before the whole-history oracle: verification must not warm its dependencies.
            staged = phase(f, "successor/stage", objects, f.currentBoundaries(), measurements, scope::stage);
            assertEquals(f.after(), snapshot(engine(scope), f.source(), f.parent()));
        }
        var reopenedBytes = objects.detachedCopy();
        try (var reopened = phase(f, "successor/reopen/open", reopenedBytes, f.afterCurrentBoundaries(), measurements,
                () -> RootedCoordinationStorage.open(reopenedBytes, LIMITS, staged,
                        ExactNodeProvider.empty(), ColdStorageJournalFixture.open(journal.snapshot())))) {
            String reopenedHead = phase(f, "successor/reopen/select", reopenedBytes, f.afterCurrentBoundaries(), measurements,
                    () -> reopened.documentHandle(f.parent()).orElseThrow().snapshot().blueId());
            assertEquals(f.after().heads().get(f.parent()), reopenedHead);
            assertEquals(f.after(), snapshot(engine(reopened), f.source(), f.parent()));
            assertEquals(0L, CoordinationTestControl.attach(engine(reopened)).metricsSnapshot().phaseNanos()
                    .getOrDefault("contracts.closure.processor", 0L), "Cold restore does not rerun the processor");
        }
        // An old descriptor must still expose its complete old history after staging a successor.
        try (var old = RootedCoordinationStorage.open(objects.detachedCopy(), LIMITS, f.selection(),
                ExactNodeProvider.empty(), ColdStorageJournalFixture.open(f.journal()))) {
            assertEquals(f.before(), snapshot(engine(old), f.source(), f.parent()));
        }
        long unrequested = measurements.stream().mapToLong(row -> row.accesses().unrequestedHistoricalPayloadGetCalls()).sum();
        long unrequestedDistinctByPhase = measurements.stream()
                .mapToLong(row -> row.accesses().unrequestedHistoricalPayloadAddresses()).sum();
        long rewritten = measurements.stream().mapToLong(row -> row.accesses().oldPayloadPutCalls()).sum();
        report(Map.of("kind", "semantic-result", "workload", f.name(), "status", "PASS",
                "unrequestedHistoricalPayloadGetCalls", unrequested,
                "unrequestedHistoricalPayloadDistinctAddressesSummedPerPhase", unrequestedDistinctByPhase,
                "oldPayloadPutCalls", rewritten, "candidateZeroBudgetAsserted", Boolean.getBoolean("blue.poc.history.requireSelective"),
                "decoderCountsAvailable", false, "fullAcceptanceClaimed", false));
        // Off for baseline measurement. Enable on the same workload for the candidate's mechanism gate.
        if (Boolean.getBoolean("blue.poc.history.requireSelective")) {
            assertEquals(0L, unrequested, "P2: unrequested historical payload GETs");
            assertEquals(0L, rewritten, "P2: unchanged historical payload PUTs");
        }
    }

    private static void readProbe(Fixture f, String name, List<Measured> measured,
            java.util.function.Function<RootedCoordinationStorage.Scope, Object> select,
            Object expected, Set<String> required) {
        var objects = f.objects().detachedCopy(); var journal = ColdStorageJournalFixture.open(f.journal());
        try (var scope = phase(f, name + "/open", objects, required, measured,
                () -> RootedCoordinationStorage.open(objects, LIMITS, f.selection(), ExactNodeProvider.empty(), journal))) {
            Object actual = phase(f, name + "/select", objects, required, measured, () -> select.apply(scope));
            // Heavy complete encoding is an oracle, never part of the measured read.
            if (actual instanceof DocumentRevision revision) actual = digest(ROWS.encodeRevision(revision));
            if (actual instanceof RootedDocumentView view) actual = viewIdentity(view);
            assertEquals(expected, actual, name);
            assertEquals(0, journal.mutations());
            assertEquals(0L, measured.stream().filter(row -> row.phase().startsWith(name + "/"))
                    .flatMap(row -> row.accesses().puts().values().stream()).mapToLong(RootedHistoryAccessObjects.Count::calls).sum());
            assertEquals(0L, CoordinationTestControl.attach(engine(scope)).metricsSnapshot().phaseNanos()
                    .getOrDefault("contracts.closure.processor", 0L));
        }
    }

    private record Fixture(String name, RootedHistoryAccessObjects objects, RootedCoordinationStorage.Selection selection,
            ColdStorageJournalFixture.Snapshot journal, DocumentId source, DocumentId parent,
            Snapshot before, Snapshot after, long historicalEpoch, String revisionAddress, String revisionDigest,
            ExternalOrderKey historicalBoundary, ViewIdentity viewIdentity, Set<String> currentBoundaries,
            Set<String> afterCurrentBoundaries,
            Set<String> positionBoundaries, Set<String> historicalPayloads, String nextYaml, String nextEntryId) { }

    private static Fixture construct(int historySize, boolean sameEpoch) throws Exception {
        var objects = new RootedHistoryAccessObjects();
        // Same source/parent semantics as the maintained P1 fixture, but scalar parent state:
        // growing /log payloads would confound retained-history growth with body growth.
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario(PARENT)) {
            int additions = sameEpoch ? historySize - 2 : historySize;
            for (int i = 0; i < additions; i++) {
                var entry = f.append((i + 3L) * 100, sameEpoch ? "emitUnmatched" : "tick");
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(f.parent).entry(entry).disposition());
            }
            assertEquals(sameEpoch ? 0 : historySize, f.parent.snapshot().epoch());
            var parent = f.engine.documents().require(f.parent.id());
            var positions = parent.storedState().rootedViewPositions();
            if (sameEpoch) assertEquals(historySize, parent.representationTransitionsAt(0).size());
            int selected = Math.max(1, positions.size() / 2);
            assertTrue(selected + 1 < positions.size());
            var point = positions.get(selected); var boundary = positions.get(selected + 1).boundary();
            assertSame(point.view(), parent.rootedViewBefore(boundary), "Use a genuine unambiguous historical position");
            long epoch = sameEpoch ? 0 : 2;
            var revision = parent.revision(epoch);
            var required = currentBoundaries(f.engine, f.source.id(), f.parent.id());
            var pointRequired = Set.of(VIEW_IDENTITIES.viewAddress(point.view()),
                    VIEW_IDENTITIES.viewAddress(positions.get(selected - 1).view()),
                    VIEW_IDENTITIES.viewAddress(positions.get(0).view()));
            Snapshot before = snapshot(f.engine, f.source.id(), f.parent.id());
            var selection = RootedCoordinationStorage.retainPartition(f.blue, objects, LIMITS);
            String revisionAddress = digest(ROWS.encodeRevision(revision));
            var historicalPayloads = objects.historicalPayloadAddresses();
            assertFalse(historicalPayloads.isEmpty(), "Header accounting must recognize real stored payloads");
            assertEquals("revision-payload", objects.categoryAt(revisionAddress));
            assertTrue(historicalPayloads.contains(revisionAddress));
            assertTrue(historicalPayloads.containsAll(required), "Every fixed current boundary is a stored payload");
            assertTrue(historicalPayloads.containsAll(pointRequired), "Every predeclared position boundary is retained");
            for (String address : pointRequired) assertEquals("view-payload", objects.categoryAt(address));
            assertEquals(2, objects.sessionDescriptors().size(), "Exactly two actual session descriptors");
            assertTrue(objects.categoryInventory().getOrDefault("index-node", 0) > 0,
                    "The measured SDK must use the native physical point indexes");
            var journal = ColdStorageJournalFixture.retain(f.engine);
            long nextTime = (additions + 3L) * 100;
            String nextYaml = tick(f.source.snapshot().blueId(), f.previous, nextTime);
            var entry = f.append(nextTime, "tick");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(f.parent).entry(entry).disposition());
            Snapshot after = snapshot(f.engine, f.source.id(), f.parent.id());
            // Freeze the successor's required tip payloads from the independent resident
            // oracle, before any measured durable scope or staged result is inspected.
            var afterRequired = currentBoundaries(f.engine, f.source.id(), f.parent.id());
            return new Fixture((sameEpoch ? "same-epoch-positions-" : "numbered-parent-epochs-") + historySize,
                    objects, selection, journal, f.source.id(), f.parent.id(), before, after, epoch,
                    revisionAddress, revisionAddress, boundary,
                    viewIdentity(point.view()), required, afterRequired, pointRequired,
                    historicalPayloads, nextYaml, entry.blueId());
        }
    }

    private record Snapshot(Map<DocumentId, String> heads, Map<DocumentId, Long> epochs,
            Map<DocumentId, List<String>> revisions, Map<DocumentId, List<ViewPositionIdentity>> views,
            Map<DocumentId, List<String>> representationPositions, Map<String, String> publications) { }

    private record ViewIdentity(String physicalAddress, ExternalOrderKey logicalBoundary) { }
    private record ViewPositionIdentity(ExternalOrderKey boundary, ViewIdentity view) { }

    private static ViewIdentity viewIdentity(RootedDocumentView view) {
        // The complete physical frame includes the result, both snapshots, routes,
        // subscriptions, retained witness/publication bindings and logical boundary.
        return new ViewIdentity(VIEW_IDENTITIES.viewAddress(view), view.storedState().logicalBoundary());
    }

    private static Set<String> currentBoundaries(DefaultCoordinationEngine engine, DocumentId source, DocumentId parent) {
        var required = new LinkedHashSet<String>();
        for (var id : List.of(source, parent)) {
            var session = engine.documents().require(id);
            required.add(digest(ROWS.encodeRevision(session.currentRevision())));
            required.add(VIEW_IDENTITIES.viewAddress(session.rootedView()));
        }
        return Set.copyOf(required);
    }

    private static Snapshot snapshot(DefaultCoordinationEngine engine, DocumentId source, DocumentId parent) {
        var heads = new LinkedHashMap<DocumentId, String>(); var epochs = new LinkedHashMap<DocumentId, Long>();
        var revisions = new LinkedHashMap<DocumentId, List<String>>();
        var views = new LinkedHashMap<DocumentId, List<ViewPositionIdentity>>();
        var positions = new LinkedHashMap<DocumentId, List<String>>(); var publications = new LinkedHashMap<String, String>();
        for (var id : List.of(source, parent)) {
            var session = engine.documents().require(id); heads.put(id, session.currentRepresentation().blueId()); epochs.put(id, session.epoch());
            revisions.put(id, session.revisions().stream().map(row -> digest(ROWS.encodeRevision(row))).toList());
            views.put(id, session.storedState().rootedViewPositions().stream().map(position ->
                    new ViewPositionIdentity(position.boundary(), viewIdentity(position.view()))).toList());
            positions.put(id, session.representationTransitions().stream().map(Object::toString).toList());
        }
        engine.documents().publicationSnapshot().closurePublicationReceipts().forEach((key, value) ->
                publications.put(key, digest(RESULTS.encode(value.attempt().processResult()))));
        return new Snapshot(Map.copyOf(heads), Map.copyOf(epochs), Map.copyOf(revisions), Map.copyOf(views),
                Map.copyOf(positions), Map.copyOf(publications));
    }

    private record Measured(String phase, RootedHistoryAccessObjects.Measurement accesses) { }
    private static <T> T phase(Fixture f, String name, RootedHistoryAccessObjects objects, Set<String> required,
            List<Measured> measured, Supplier<T> work) {
        objects.begin(); long started = System.nanoTime();
        var cpu = ManagementFactory.getThreadMXBean(); long beforeCpu = cpu.isCurrentThreadCpuTimeSupported() ? cpu.getCurrentThreadCpuTime() : -1;
        try { return work.get(); }
        finally {
            long elapsed = System.nanoTime() - started;
            long elapsedCpu = beforeCpu < 0 ? -1 : cpu.getCurrentThreadCpuTime() - beforeCpu;
            var access = objects.end(f.historicalPayloads(), required); measured.add(new Measured(name, access));
            report(Map.of("kind", "phase", "workload", f.name(), "phase", name, "wallNanos", elapsed,
                    "currentThreadCpuNanos", elapsedCpu, "requiredHistoricalPayloadAddresses", required,
                    "access", access, "decoderCountsAvailable", false));
        }
    }

    private static DefaultCoordinationEngine engine(RootedCoordinationStorage.Scope scope) {
        return (DefaultCoordinationEngine) scope.coordination().advanced().rawEngine();
    }
    private static Set<String> union(Set<String> left, Set<String> right) {
        var result = new LinkedHashSet<>(left); result.addAll(right); return Set.copyOf(result);
    }
    private static void report(Object value) {
        try { System.out.println("ROOTED_HISTORY_ACCESS " + JSON.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new AssertionError(failure); }
    }
    private static String tick(String target, String previous, long time) {
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
                """.formatted(time, target, previous);
    }
    private static final String PARENT = """
            name: P2 Scalar Parent
            seen: 0
            contracts:
              embedded:
                type: Process Embedded
                paths: [/child]
              owner:
                type: Coordination/Timeline Channel
                timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/parent}
                actor: {type: MyOS/Principal Actor, accountId: alice}
              fromChild:
                type: Embedded Node Channel
                sourcePath: /child
                event: {type: Coordination/Event, kind: RCP2/Tick}
              observe:
                type: Coordination/Sequential Workflow
                channel: fromChild
                event: {type: Coordination/Event, kind: RCP2/Tick}
                steps:
                - type: Coordination/Compute
                  do:
                  - $appendChange:
                      op: replace
                      path: /seen
                      val: {$document: /child/counter}
                  - $return: true
            """;
}
