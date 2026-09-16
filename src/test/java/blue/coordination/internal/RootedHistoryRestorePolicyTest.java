package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.ExactNodeProvider;
import blue.coordination.sdk.RootedCoordinationStorage;
import blue.language.processor.ExternalOrderKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static blue.coordination.internal.RootedHistoryAccessObjects.digest;
import static org.junit.jupiter.api.Assertions.*;

/** The controlled writer is an explicit storage contract, never a hash/cache-derived trust decision. */
final class RootedHistoryRestorePolicyTest {
    private static final int MIB = 1024 * 1024, RECORD = 40 * MIB;
    private static final SessionRecordCodec ROWS = new SessionRecordCodec(RECORD, 256);
    private static final DocumentSessionStorage PHYSICAL = new DocumentSessionStorage(new Bytes(),
            new DocumentSessionStorage.Limits(RECORD, 256, 512L * MIB));
    private static final RootedCoordinationStorage.Limits LIMITS = new RootedCoordinationStorage.Limits(
            new RootedEngineStorage.Limits(48 * MIB, 64 * 1024, 32 * MIB, 8192, 32,
                    RECORD, 256, 512L * MIB, 100, 100, 100, 256L * MIB, 48 * MIB, 32 * MIB, 16),
            new RootedCoordinationStorage.SdkLimits(new InsertionOrderedStorage.Limits(512 * 1024, 64 * 1024,
                    64 * 1024, 8192, 32, 256 * 1024, 4L * MIB, 100), 32 * MIB, 128 * 1024, 32 * MIB, 100, 256 * 1024));

    @Test void strictAndControlledFreshOwnersPreserveCompleteHistoryAndSameEpochPositions() throws Exception {
        Fixture f = fixture();
        for (boolean controlled : List.of(false, true)) {
            var bytes = f.bytes().copy(); var journal = ColdStorageJournalFixture.open(f.journal());
            try (var scope = controlled
                    ? RootedCoordinationStorage.controlledRepository(bytes).open(LIMITS, f.selection(), ExactNodeProvider.empty(), journal)
                    : RootedCoordinationStorage.open(bytes, LIMITS, f.selection(), ExactNodeProvider.empty(), journal)) {
                var session = engine(scope).documents().require(f.document());
                assertEquals(f.evidence(), evidence(session));
                assertEquals(f.oldViewAddress(), PHYSICAL.viewAddress(session.rootedViewBefore(f.oldViewUpperBoundary())));
                assertEquals(f.oldRevisionAddress(), digest(ROWS.encodeRevision(session.revision(f.oldEpoch()))));
                assertReadOnly(scope, journal, bytes);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "corrupt", "unavailable"})
    void controlledSelectionDefersUnrequestedDamageButNeverHidesSelectedDamage(String fault) throws Exception {
        Fixture f = fixture();
        for (boolean view : List.of(false, true)) {
            String selected = view ? f.oldViewAddress() : f.oldRevisionAddress();
            for (boolean warm : List.of(false, true)) {
                try (var cache = new RootedCoordinationStorage.Cache(warm ? 256L * MIB : 0, 512, 64L * MIB)) {
                    if (warm) warm(f, cache);
                    var bytes = f.bytes().copy(); bytes.faultAddress = selected; bytes.fault = fault;
                    var journal = ColdStorageJournalFixture.open(f.journal());
                    try (var scope = RootedCoordinationStorage.controlledRepository(bytes)
                            .open(LIMITS, f.selection(), ExactNodeProvider.empty(), journal, cache)) {
                        assertEquals(f.evidence().head(), scope.documentHandle(f.document()).orElseThrow().snapshot().blueId());
                        assertFalse(bytes.reads.contains(selected), "Current tip must not select the unrelated old payload");
                        assertThrows(CoordinationObjectStorageException.class, () -> {
                            var session = engine(scope).documents().require(f.document());
                            if (view) session.rootedViewBefore(f.oldViewUpperBoundary());
                            else session.revision(f.oldEpoch());
                        }, "A selected record remains checked, including with a previously warmed cache");
                        assertTrue(bytes.reads.contains(selected), "The failure is caused by the exact selected payload");
                        assertReadOnly(scope, journal, bytes);
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "corrupt", "unavailable"})
    void genericStrictOpeningCannotInheritControlledTrustFromMatchingAddressesOrWarmCache(String fault) throws Exception {
        Fixture f = fixture();
        try (var cache = new RootedCoordinationStorage.Cache(256L * MIB, 512, 64L * MIB)) {
            warm(f, cache);
            for (String selected : List.of(f.oldRevisionAddress(), f.oldViewAddress())) {
                var bytes = f.bytes().copy(); bytes.faultAddress = selected; bytes.fault = fault;
                var journal = ColdStorageJournalFixture.open(f.journal());
                assertThrows(CoordinationObjectStorageException.class, () -> {
                    try (var scope = RootedCoordinationStorage.open(bytes, LIMITS, f.selection(), ExactNodeProvider.empty(), journal, cache)) {
                        scope.documentHandle(f.document()).orElseThrow().snapshot();
                    }
                }, "Untrusted selected session restore must validate the complete retained history");
                assertTrue(bytes.reads.contains(selected));
                assertEquals(0, journal.mutations()); assertEquals(0, bytes.writes);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "wrong-acknowledgement"})
    void failedSuccessorPrewriteDoesNotPublishAndRetryKeepsTheExactOldAndNewSelections(String fault) throws Exception {
        Fixture f = fixture(); var bytes = f.bytes().copy();
        var journal = ColdStorageJournalFixture.open(f.journal());
        RootedCoordinationStorage.Selection next;
        try (var scope = RootedCoordinationStorage.controlledRepository(bytes)
                .open(LIMITS, f.selection(), ExactNodeProvider.empty(), journal)) {
            var entry = scope.coordination().events().from(scope.timelineHandle("rcp2/source").orElseThrow())
                    .exact(scope.coordination().values().yaml(f.nextYaml())).submit();
            assertEquals(f.nextEntryId(), entry.blueId());
            assertEquals(EntryDisposition.APPLIED, scope.coordination().processing()
                    .processNext(scope.documentHandle(f.document()).orElseThrow()).entry(entry).disposition());
            bytes.writeFault = fault;
            assertThrows(CoordinationObjectStorageException.class, scope::stage);
            bytes.writeFault = null;
            // No returned successor selection was published. The old selection must
            // remain independently readable despite any unreachable immutable prewrites.
            try (var old = RootedCoordinationStorage.controlledRepository(bytes.copy())
                    .open(LIMITS, f.selection(), ExactNodeProvider.empty(), ColdStorageJournalFixture.open(f.journal()))) {
                assertEquals(f.evidence(), evidence(engine(old).documents().require(f.document())));
            }
            next = scope.stage();
            assertEquals(f.after(), evidence(engine(scope).documents().require(f.document())));
            assertEquals(1, journal.mutations(), "Retrying storage does not resubmit or reexecute the input");
        }
        var reopenedBytes = bytes.copy(); var reopenedJournal = ColdStorageJournalFixture.open(journal.snapshot());
        try (var reopened = RootedCoordinationStorage.controlledRepository(reopenedBytes)
                .open(LIMITS, next, ExactNodeProvider.empty(), reopenedJournal)) {
            assertEquals(f.after(), evidence(engine(reopened).documents().require(f.document())));
            assertReadOnly(reopened, reopenedJournal, reopenedBytes);
        }
    }

    private static void warm(Fixture f, RootedCoordinationStorage.Cache cache) {
        var bytes = f.bytes().copy(); var journal = ColdStorageJournalFixture.open(f.journal());
        try (var scope = RootedCoordinationStorage.controlledRepository(bytes)
                .open(LIMITS, f.selection(), ExactNodeProvider.empty(), journal, cache)) {
            assertEquals(f.evidence(), evidence(engine(scope).documents().require(f.document())));
            assertReadOnly(scope, journal, bytes);
        }
    }

    private static Fixture fixture() throws Exception {
        var bytes = new Bytes();
        try (var scenario = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            for (long time : List.of(300L, 400L, 500L)) {
                var entry = scenario.append(time, "tick");
                assertEquals(EntryDisposition.APPLIED, scenario.blue.processing().processNext(scenario.parent).entry(entry).disposition());
            }
            var session = scenario.engine.documents().require(scenario.parent.id());
            assertEquals(3L, session.epoch());
            assertEquals(2, session.representationTransitionsAt(0).size());
            var positions = session.storedState().rootedViewPositions();
            var old = positions.get(1); var upper = positions.get(2).boundary();
            assertSame(old.view(), session.rootedViewBefore(upper));
            String oldView = PHYSICAL.viewAddress(old.view());
            String oldRevision = digest(ROWS.encodeRevision(session.revision(1)));
            assertNotEquals(oldView, PHYSICAL.viewAddress(session.rootedView()));
            assertNotEquals(oldRevision, digest(ROWS.encodeRevision(session.currentRevision())));
            Evidence expected = evidence(session);
            var selection = RootedCoordinationStorage.controlledRepository(bytes).retainPartition(scenario.blue, LIMITS);
            assertTrue(bytes.records.containsKey(oldView)); assertTrue(bytes.records.containsKey(oldRevision));
            bytes.clearObservations();
            var journal = ColdStorageJournalFixture.retain(scenario.engine);
            String nextYaml = tick(scenario.source.snapshot().blueId(), scenario.previous);
            var nextEntry = scenario.append(600L, "tick");
            assertEquals(EntryDisposition.APPLIED, scenario.blue.processing().processNext(scenario.parent).entry(nextEntry).disposition());
            Evidence after = evidence(scenario.engine.documents().require(scenario.parent.id()));
            return new Fixture(bytes, selection, journal, scenario.parent.id(), expected, after,
                    1, oldRevision, oldView, upper, nextYaml, nextEntry.blueId());
        }
    }

    private record Fixture(Bytes bytes, RootedCoordinationStorage.Selection selection,
            ColdStorageJournalFixture.Snapshot journal, DocumentId document, Evidence evidence, Evidence after,
            long oldEpoch, String oldRevisionAddress, String oldViewAddress, ExternalOrderKey oldViewUpperBoundary,
            String nextYaml, String nextEntryId) { }
    private record ViewPosition(ExternalOrderKey position, ExternalOrderKey logicalBoundary, String frame) { }
    private record Evidence(String head, long epoch, List<String> revisions, List<ViewPosition> views, List<String> transitions) { }

    private static Evidence evidence(DocumentSession session) {
        return new Evidence(session.currentRepresentation().blueId(), session.epoch(),
                session.revisions().stream().map(revision -> digest(ROWS.encodeRevision(revision))).toList(),
                session.storedState().rootedViewPositions().stream().map(position -> new ViewPosition(position.boundary(),
                        position.view().storedState().logicalBoundary(), PHYSICAL.viewAddress(position.view()))).toList(),
                session.representationTransitions().stream().map(Object::toString).toList());
    }

    private static DefaultCoordinationEngine engine(RootedCoordinationStorage.Scope scope) {
        return (DefaultCoordinationEngine) scope.coordination().advanced().rawEngine();
    }
    private static void assertReadOnly(RootedCoordinationStorage.Scope scope, ColdStorageJournalFixture journal, Bytes bytes) {
        assertEquals(0, journal.mutations()); assertEquals(0, bytes.writes);
        assertEquals(0L, CoordinationTestControl.attach(engine(scope)).metricsSnapshot().phaseNanos()
                .getOrDefault("contracts.closure.processor", 0L));
    }
    private static String tick(String source, String previous) {
        return """
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/source}
                timestamp: 600
                actor: {type: MyOS/Principal Actor, accountId: alice}
                message:
                  type: Coordination/Operation Request
                  document: {blueId: %s}
                  requireExactDocumentVersion: false
                  operation: tick
                  channel: owner
                  request: {}
                prevEntry: {blueId: %s}
                """.formatted(source, previous);
    }

    private static final class Bytes implements CoordinationImmutableObjectStore {
        final Map<String, byte[]> records = new LinkedHashMap<>();
        final List<String> reads = new ArrayList<>(); int writes;
        String faultAddress, fault, writeFault;
        Bytes copy() { var copy = new Bytes(); records.forEach((key, value) -> copy.records.put(key, value.clone())); return copy; }
        void clearObservations() { reads.clear(); writes = 0; }
        @Override public Optional<byte[]> get(String address, int maximumBytes) {
            reads.add(address);
            if (address.equals(faultAddress)) {
                if ("missing".equals(fault)) return Optional.empty();
                if ("unavailable".equals(fault)) throw new CoordinationObjectStorageException("Selected record unavailable");
            }
            byte[] value = records.get(address);
            if (value == null) return Optional.empty();
            if (value.length > maximumBytes) throw new CoordinationObjectStorageException("Read bound");
            byte[] result = value.clone();
            if (address.equals(faultAddress) && "corrupt".equals(fault)) result[result.length - 1] ^= 1;
            return Optional.of(result);
        }
        @Override public byte[] putIfAbsent(String address, byte[] value) {
            writes++;
            if ("unavailable".equals(writeFault)) throw new CoordinationObjectStorageException("Prewrite unavailable");
            byte[] prior = records.putIfAbsent(address, value.clone());
            if (prior != null && !Arrays.equals(prior, value)) throw new CoordinationObjectStorageException("Address collision");
            byte[] result = (prior == null ? value : prior).clone();
            if ("wrong-acknowledgement".equals(writeFault)) result[result.length - 1] ^= 1;
            return result;
        }
    }
}
