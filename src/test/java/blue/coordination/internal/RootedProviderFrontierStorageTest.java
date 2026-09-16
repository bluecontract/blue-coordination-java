package blue.coordination.internal;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.EntryDisposition;
import blue.language.processor.closure.ExternalEventCause;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Derived provider promises use real rooted terminal receipts, not synthetic execution evidence. */
final class RootedProviderFrontierStorageTest {
    private static final String SOURCE = "rcp2/source";
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits SESSIONS =
            new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final PersistentMapStorage.Limits MAPS =
            new PersistentMapStorage.Limits(40 * 1024 * 1024, 4096, MAX, 4096, 16);
    private static final PersistentAppendLogStorage.Limits LOGS =
            new PersistentAppendLogStorage.Limits(40 * 1024 * 1024, MAX, 4096, 8);

    @Test void maximumAndEqualTimestampWitnessAreIndependentOfReceiptInsertionOrder() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var entry = f.append(300);
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(f.parent, entry).entry(entry).disposition());
            // The parent has consumed 100/200 locally; the independent source still owns those inputs.
            for (int preceding = 0; preceding < 2; preceding++) {
                var applied = f.blue.processing().processNext(f.source);
                assertEquals(1, applied.entries().size());
                assertEquals(EntryDisposition.APPLIED, applied.entries().get(0).disposition());
            }
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(f.source).entry(entry).disposition());
            var state = f.engine.documents().storedState();
            var receipts = new ArrayList<>(state.closurePublicationReceipts().values());
            var ties = receipts.stream().filter(row -> timestamp(row) == 300
                    && row.rootedTerminalEvidence().requiredTimelineIds().contains(SOURCE)).toList();
            assertTrue(ties.size() >= 2, "Two independent roots must produce actual terminals at the same external timestamp");
            var expectedWitness = ties.stream().map(ContractsClosurePublicationReceipt::publicationIdentity)
                    .min(EmbeddingBinding.TEXT_ORDER).orElseThrow();
            var forward = RootedProviderFrontiers.from(receipts);
            Collections.reverse(receipts);
            var reverse = RootedProviderFrontiers.from(receipts);
            forward.requireMatches(reverse);
            assertEquals(300, forward.rows().get(SOURCE).closedThroughMicros());
            assertEquals(expectedWitness, forward.rows().get(SOURCE).publicationIdentity());
            forward.requireMatches(state.rootedProviderFrontiers());

            var exact = f.engine.auditTimelineEntry(entry.blueId()).orElseThrow();
            assertThrows(IllegalArgumentException.class,
                    () -> forward.requireAfter(exact, state.publicationReceiptIndex(), state.closurePublicationReceiptIndex()));
            var later = f.engine.auditTimelineEntry(f.append(301).blueId()).orElseThrow();
            assertDoesNotThrow(() -> forward.requireAfter(later, state.publicationReceiptIndex(), state.closurePublicationReceiptIndex()));
            var unrelated = unrelatedEntry(f, 100);
            assertDoesNotThrow(() -> forward.requireAfter(unrelated, state.publicationReceiptIndex(), state.closurePublicationReceiptIndex()),
                    "An unrelated Timeline does not inherit another root's timestamp frontier");
        }
    }

    @Test void controlledColdGuardReadsOnlySelectedIndexPathsNotHistoricalReceiptOrSessionPayloads() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var equal = f.engine.auditTimelineEntry(f.previous).orElseThrow();
            var later = f.engine.auditTimelineEntry(f.append(300).blueId()).orElseThrow();
            var bytes = new RootedHistoryAccessObjects();
            var storage = storage(RootedEngineStorage.controlledNamespace(bytes));
            var selected = storage.retainPartition(f.engine.documents().storedState());
            try (var cold = storage.open(selected, 8, 256)) {
                var documents = new InMemoryDocumentStore(new EngineMetrics(), cold.state());
                bytes.begin(Set.of());
                assertThrows(IllegalArgumentException.class, () -> documents.requireAfterRootedProviderFrontier(equal));
                assertDoesNotThrow(() -> documents.requireAfterRootedProviderFrontier(later));
                var measured = bytes.end(Set.of(), Set.of());
                assertFalse(measured.gets().isEmpty(), "Cold guard must exercise actual stored index paths");
                assertEquals(Set.of("index-node"), measured.gets().keySet(),
                        "No closure receipt, rooted view, revision or result payload may be opened by this guard");
                assertTrue(measured.puts().isEmpty());
            }
        }
    }

    @Test void strictColdUseRejectsHashCorrectOmissionAndNonmaximumProjection() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var state = f.engine.documents().storedState();
            var bytes = new RootedHistoryAccessObjects();
            var selected = storage(bytes).retainPartition(state);
            try (var valid = storage(bytes).open(selected, 8, 256)) {
                valid.state().rootedProviderFrontiers().rows();
                valid.state().rootedProviderFrontiers().requireMatches(state.rootedProviderFrontiers());
            }
            var older = state.closurePublicationReceipts().values().stream()
                    .filter(row -> timestamp(row) == 100 && row.rootedTerminalEvidence().requiredTimelineIds().contains(SOURCE))
                    .findFirst().orElseThrow();
            var olderRow = RootedProviderFrontiers.from(List.of(older)).rows().get(SOURCE);
            var omitted = state.rootedProviderFrontiers().rows().remove(SOURCE).map();
            var nonmaximum = state.rootedProviderFrontiers().rows().put(SOURCE, olderRow).map();
            for (var forged : List.of(omitted, nonmaximum)) {
                var changed = replace(selected, StoredDocumentStore.Root.PROVIDER_FRONTIERS, retainFrontiers(bytes, forged));
                try (var opened = storage(bytes).open(changed, 8, 256)) {
                    assertThrows(CoordinationObjectStorageException.class,
                            () -> opened.state().rootedProviderFrontiers().rows(),
                            "A correctly addressed secondary index cannot establish its own completeness");
                    assertThrows(CoordinationObjectStorageException.class,
                            () -> opened.state().rootedProviderFrontiers().rows(),
                            "A failed verification must not authorize the next use of the same immutable basis");
                }
            }
        }
    }

    @Test void selectedFrontierRequiresExactTimelineAndBothPublicationMemberships() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var state = f.engine.documents().storedState();
            var entry = f.engine.auditTimelineEntry(f.append(300).blueId()).orElseThrow();
            var frontiers = state.rootedProviderFrontiers();
            var row = frontiers.rows().get(SOURCE);
            assertNotNull(row);
            assertThrows(CoordinationObjectStorageException.class, () -> frontiers.requireAfter(entry,
                    state.publicationReceiptIndex().remove(row.publicationIdentity()).map(), state.closurePublicationReceiptIndex()));
            assertThrows(CoordinationObjectStorageException.class, () -> frontiers.requireAfter(entry,
                    state.publicationReceiptIndex(), state.closurePublicationReceiptIndex().remove(row.publicationIdentity()).map()));
            var wrongOwner = new RootedProviderFrontiers.Frontier("wrong-timeline", row.closedThroughMicros(),
                    row.sourceOrder(), row.publicationIdentity());
            var malformed = RootedProviderFrontiers.restoreStored(frontiers.rows().put(SOURCE, wrongOwner).map());
            assertThrows(CoordinationObjectStorageException.class, () -> malformed.requireAfter(entry,
                    state.publicationReceiptIndex(), state.closurePublicationReceiptIndex()));
        }
    }

    @Test void failedAtomicPublicationDoesNotAdvanceButGasTerminalAndExactReplayPreserveThePromise() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var before = f.engine.documents().storedState().rootedProviderFrontiers();
            var entry = f.append(300);
            var reached = new java.util.concurrent.atomic.AtomicBoolean();
            f.engine.contractsClosureAdapter().onStoreFailurePoint(point -> {
                if (point == MultiDocumentPublicationTransaction.FailurePoint.BEFORE_SWAP) {
                    reached.set(true);
                    throw new IllegalStateException("abort provider frontier publication");
                }
            });
            try { assertThrows(RuntimeException.class, () -> f.blue.processing().processNext(f.parent)); }
            finally { f.engine.contractsClosureAdapter().onStoreFailurePoint(ignored -> { }); }
            assertTrue(reached.get());
            assertSame(before, f.engine.documents().storedState().rootedProviderFrontiers());
            assertEquals(200, before.rows().get(SOURCE).closedThroughMicros());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(f.parent).entry(entry).disposition());
            assertEquals(300, f.engine.documents().storedState().rootedProviderFrontiers().rows().get(SOURCE).closedThroughMicros());

            var failing = f.append(400);
            var parentBefore = f.engine.documents().require(f.parent.id()).storedState();
            var policy = ContractsExecutionPolicy.exactSharedGas(1, "stored-provider-frontier-gas");
            assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED,
                    f.blue.advanced().process(f.parent, failing, policy).entry(failing).disposition());
            var after = f.engine.documents().storedState();
            assertEquals(400, after.rootedProviderFrontiers().rows().get(SOURCE).closedThroughMicros());
            var parentAfter = f.engine.documents().require(f.parent.id()).storedState();
            assertEquals(parentBefore.revisions(), parentAfter.revisions(), "Gas terminal publishes no document epoch");
            assertEquals(parentBefore.representationTransitions(), parentAfter.representationTransitions());
            assertEquals(parentBefore.rootedViewPositions(), parentAfter.rootedViewPositions());
            assertSame(parentBefore.rootedView(), parentAfter.rootedView());
            assertEquals(parentBefore.layout().rootBlueId(), parentAfter.layout().rootBlueId());
            var exact = f.engine.auditTimelineEntry(failing.blueId()).orElseThrow();
            assertFalse(f.engine.appendTimelineEntry(exact.exactEvent().copyNode()).stored(),
                    "Already retained exact entry remains idempotent despite the closed timestamp");
            assertSame(after.rootedProviderFrontiers(), f.engine.documents().storedState().rootedProviderFrontiers());
            assertThrows(IllegalArgumentException.class, () -> f.engine.documents().requireAfterRootedProviderFrontier(exact));

            var bytes = new RootedHistoryAccessObjects();
            var storage = storage(RootedEngineStorage.controlledNamespace(bytes));
            var selected = storage.retainPartition(after);
            try (var cold = storage.open(selected, 8, 256)) {
                var documents = new InMemoryDocumentStore(new EngineMetrics(), cold.state());
                assertThrows(IllegalArgumentException.class, () -> documents.requireAfterRootedProviderFrontier(exact));
            }
        }
    }

    private static long timestamp(ContractsClosurePublicationReceipt receipt) {
        var evidence = receipt.rootedTerminalEvidence();
        if (evidence == null || !(evidence.input().cause() instanceof ExternalEventCause cause)) return -1;
        return new java.math.BigInteger(cause.sourceOrder().components().get(0).toString()).longValueExact();
    }

    private static TimelineEntry unrelatedEntry(ManagedRepresentationVerificationMemoTest.Scenario f, long timestamp) {
        var timeline = f.blue.timelines().register("rcp2/unrelated-frontier", "alice");
        var entry = f.blue.events().from(timeline).exact(f.blue.values().yaml("""
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/unrelated-frontier}
                timestamp: %d
                actor: {type: MyOS/Principal Actor, accountId: alice}
                message:
                  type: Coordination/Operation Request
                  document: {blueId: %s}
                  requireExactDocumentVersion: false
                  operation: tick
                  channel: owner
                  request: {}
                """.formatted(timestamp, f.source.snapshot().blueId()))).submit();
        return f.engine.auditTimelineEntry(entry.blueId()).orElseThrow();
    }

    private static byte[] retainFrontiers(CoordinationImmutableObjectStore bytes,
            PersistentOrderedMap<String, RootedProviderFrontiers.Frontier> rows) {
        var sessions = new DocumentSessionStorage(bytes, SESSIONS);
        try (var scope = sessions.openScope(); var results = new StoredResultRows(bytes, SESSIONS);
                var publication = new StoredPublicationIndexes(bytes, MAPS, sessions, scope, results, SESSIONS.maximumDepth())) {
            return publication.retainFrontiers(rows).storedRootDescriptor();
        }
    }

    private static StoredDocumentStore.Selection replace(StoredDocumentStore.Selection original,
            StoredDocumentStore.Root selected, byte[] value) {
        var roots = new EnumMap<StoredDocumentStore.Root, byte[]>(StoredDocumentStore.Root.class);
        for (var root : StoredDocumentStore.Root.values()) roots.put(root, root == selected ? value : original.root(root));
        return new StoredDocumentStore.Selection(roots, original.metadata());
    }

    private static StoredDocumentStore storage(CoordinationImmutableObjectStore bytes) {
        return new StoredDocumentStore(bytes, MAPS, LOGS, SESSIONS);
    }
}
