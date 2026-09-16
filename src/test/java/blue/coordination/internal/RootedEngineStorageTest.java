package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.ExactNodeProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;

/** Complete new engine from bytes; SDK owner and PostgreSQL acceptance are separate subsequent gates. */
final class RootedEngineStorageTest {
    private static final int MAX = 32 * 1024 * 1024;
    static final RootedEngineStorage.Limits LIMITS = new RootedEngineStorage.Limits(40 * 1024 * 1024,
            8192, MAX, 8192, 32, MAX, 256, 256L * 1024 * 1024, 16, 512, 64, 256L * 1024 * 1024,
            40 * 1024 * 1024, MAX, 16);

    @Test void entirelyNewEngineContinuesIdenticalHistoryGasAndCheckpointAfterProducerCloses() throws Exception {
        assertEquals(counter(false), counter(true));
    }

    private List<String> counter(boolean cold) throws Exception {
        RootedEngineStorage.Selection selected; ColdStorageJournalFixture.Snapshot journal; DocumentId root;
        var objects = new DocumentSessionStorageTest.Bytes();
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory()); root = handle.id();
            f.process(handle, f.append(handle, "rcp2/source", "tick"));
            f.process(handle, f.append(handle, "rcp2/source", "tick"));
            f.append(handle, "rcp2/source", "tick");
            if (!cold) {
                assertEquals(1, f.engine.processNextRoot(root).committedProcessTransitions());
                return evidence(f.engine, objects, root);
            }
            selected = RootedEngineStorage.retainPartition(f.engine, objects, LIMITS);
            journal = ColdStorageJournalFixture.retain(f.engine); objects = objects.copy();
        }
        var physicalJournal = ColdStorageJournalFixture.open(journal); int writes = objects.writes;
        try (var opened = RootedEngineStorage.open(objects, LIMITS, selected, ExactNodeProvider.empty(), physicalJournal)) {
            assertEquals(0, physicalJournal.bodyReads(), "Opening does not scan the Timeline");
            assertEquals(writes, objects.writes); assertEquals(2L, opened.engine().documents().require(root).epoch());
            assertEquals(1, opened.engine().processNextRoot(root).committedProcessTransitions());
            var expected = evidence(opened.engine(), objects, root); var staged = opened.stage();
            try (var again = RootedEngineStorage.open(objects.copy(), LIMITS, staged, ExactNodeProvider.empty(), ColdStorageJournalFixture.open(journal))) {
                assertEquals(expected, evidence(again.engine(), new DocumentSessionStorageTest.Bytes(), root));
                assertEquals(0, again.engine().processNextRoot(root).committedProcessTransitions(), "Committed entry is not processed twice");
            }
            return expected;
        }
    }

    @Test void coldSuspendedParentResumesItsExactSourceActionWithoutRestartingItsHistory() throws Exception {
        sourceResumption(null);
    }

    @Test void completedSourceAndAdmissionLedgerReuseTheSameResultAcrossFreshCachedEngineOwners() throws Exception {
        try (var cache = new RootedEngineStorage.Cache(512L * 1024 * 1024, 4096, 256L * 1024 * 1024)) {
            sourceResumption(cache);
        }
    }

    private void sourceResumption(RootedEngineStorage.Cache cache) throws Exception {
        RootedEngineStorage.Selection selected; ColdStorageJournalFixture.Snapshot journal;
        SourceHistoryPrerequisite action; DocumentId parent; Map<String, String> provided;
        var objects = new DocumentSessionStorageTest.Bytes(); List<String> parentBefore;
        try (var scenario = new SourceDiscoveryStorageCodecTest.Scenario(false)) {
            action = scenario.selection(); parent = scenario.parent.id();
            parentBefore = documentEvidence(scenario.f.engine, objects, parent);
            selected = RootedEngineStorage.retainPartition(scenario.f.engine, objects, LIMITS);
            journal = ColdStorageJournalFixture.retain(scenario.f.engine);
            provided = Map.copyOf(scenario.f.exact); objects = objects.copy();
        }
        var providerReads = new java.util.concurrent.atomic.AtomicInteger();
        ExactNodeProvider provider = id -> { providerReads.incrementAndGet(); return Optional.ofNullable(provided.get(id)); };
        try (var opened = open(objects, selected, provider, journal, cache)) {
            assertEquals(0, providerReads.get());
            var result = opened.engine().processSourceHistoryPrerequisite(action);
            assertTrue(result.admission().orElseThrow().published());
            assertEquals(parentBefore, documentEvidence(opened.engine(), objects, parent));
            var source = documentEvidence(opened.engine(), objects, action.sourceDocumentId());
            var staged = opened.stage();
            try (var again = open(objects.copy(), staged, provider, journal, cache)) {
                var repeated = again.engine().processSourceHistoryPrerequisite(action);
                assertTrue(repeated.replayed());
                if (cache != null) {
                    var admission = repeated.admission().orElseThrow();
                    var ledger = again.engine().documents().publicationSnapshot().admissionReceipts().get(admission.publicationIdentity());
                    assertNotNull(ledger);
                    assertSame(admission.attempt().processResult(), ledger.attempt().processResult(),
                            "Pending and publication factories share the complete frame, not their mutable owner");
                }
                assertEquals(source, documentEvidence(again.engine(), objects, action.sourceDocumentId()));
                assertEquals(parentBefore, documentEvidence(again.engine(), objects, parent));
                assertTrue(again.engine().processNextRoot(parent).committedProcessTransitions() > 0);
            }
        }
    }

    private static RootedEngineStorage.Scope open(DocumentSessionStorageTest.Bytes objects,
            RootedEngineStorage.Selection selected, ExactNodeProvider provider,
            ColdStorageJournalFixture.Snapshot journal, RootedEngineStorage.Cache cache) {
        return cache == null ? RootedEngineStorage.open(objects, LIMITS, selected, provider, ColdStorageJournalFixture.open(journal))
                : RootedEngineStorage.open(objects, LIMITS, selected, provider, ColdStorageJournalFixture.open(journal), cache);
    }

    @Test void targetedRootProcessingDoesNotMaterializeUnrelatedCatalogSessions() throws Exception {
        RootedEngineStorage.Selection selected; ColdStorageJournalFixture.Snapshot journal; DocumentId root;
        var objects = new DocumentSessionStorageTest.Bytes(); var forbidden = new HashSet<String>();
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory()); root = handle.id();
            for (int i = 0; i < 8; i++) f.start(resource("source.yaml").replace("rcp2/source", "unrelated/" + i), "unrelated/" + i, ActivationPolicy.importFullHistory());
            f.append(handle, "rcp2/source", "tick");
            selected = RootedEngineStorage.retainPartition(f.engine, objects, LIMITS); journal = ColdStorageJournalFixture.retain(f.engine);
            var rows = new StoredDocumentIndexes(objects, LIMITS.indexes(), LIMITS.sessions()).openSessions(selected.slots().get("documents/SESSIONS"));
            for (var row : rows.entries()) if (!row.getKey().equals(root)) forbidden.add(row.getValue().address());
        }
        forbidden.forEach(objects.records::remove);
        try (var opened = RootedEngineStorage.open(objects, LIMITS, selected, ExactNodeProvider.empty(), ColdStorageJournalFixture.open(journal))) {
            assertEquals(1, opened.engine().processNextRoot(root).committedProcessTransitions());
            assertEquals(1, opened.engine().documents().require(root).epoch());
        }
    }

    @Test void partialPrewritesNeverAdvancePinnedStateAndWorkingResultCanBeStagedAgain() throws Exception {
        RootedEngineStorage.Selection selected; ColdStorageJournalFixture.Snapshot journal; DocumentId root;
        var objects = new DocumentSessionStorageTest.Bytes();
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory()); root = handle.id();
            f.append(handle, "rcp2/source", "tick"); selected = RootedEngineStorage.retainPartition(f.engine, objects, LIMITS);
            journal = ColdStorageJournalFixture.retain(f.engine);
        }
        try (var opened = RootedEngineStorage.open(objects, LIMITS, selected, ExactNodeProvider.empty(), ColdStorageJournalFixture.open(journal))) {
            opened.engine().processNextRoot(root); var after = documentEvidence(opened.engine(), objects, root);
            objects.failAtWrite = objects.writes + 3;
            assertThrows(CoordinationObjectStorageException.class, opened::stage); objects.failAtWrite = -1;
            try (var old = RootedEngineStorage.open(objects.copy(), LIMITS, selected, ExactNodeProvider.empty(), ColdStorageJournalFixture.open(journal))) {
                assertEquals(0, old.engine().documents().require(root).epoch());
            }
            assertEquals(after, documentEvidence(opened.engine(), objects, root));
            var staged = opened.stage();
            try (var again = RootedEngineStorage.open(objects.copy(), LIMITS, staged, ExactNodeProvider.empty(), ColdStorageJournalFixture.open(journal))) {
                assertEquals(after, documentEvidence(again.engine(), objects, root));
                assertThrows(CoordinationObjectStorageException.class, () -> RootedEngineStorage.retainPartition(again.engine(), new DocumentSessionStorageTest.Bytes(), LIMITS));
            }
        }
    }

    private static List<String> documentEvidence(DefaultCoordinationEngine engine, DocumentSessionStorageTest.Bytes objects, DocumentId root) {
        var session = engine.documents().require(root);
        return List.of(Long.toString(session.epoch()), new DocumentSessionStorage(objects, LIMITS.sessions()).retain(session));
    }
    private static List<String> evidence(DefaultCoordinationEngine engine, DocumentSessionStorageTest.Bytes objects, DocumentId root) {
        var result = new ArrayList<>(documentEvidence(engine, objects, root));
        result.add(Base64.getEncoder().encodeToString(new EngineControlStorageCodec(MAX).encode(engine.storedParts(WholeObjectBacking.EMPTY).control())));
        result.add(StoredDocumentStore.Metadata.from(engine.documents().storedState()).toString()); return result;
    }
}
