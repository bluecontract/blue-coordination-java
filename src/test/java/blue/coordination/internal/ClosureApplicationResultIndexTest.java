package blue.coordination.internal;

import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.EntryDisposition;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;

/** Real publication envelopes and cold physical maps: no lookup cache or synthetic PROCESS output. */
final class ClosureApplicationResultIndexTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits SESSIONS = new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final PersistentMapStorage.Limits MAPS = new PersistentMapStorage.Limits(40 * 1024 * 1024, 4096, MAX, 4096, 16);
    private static final PersistentAppendLogStorage.Limits LOGS = new PersistentAppendLogStorage.Limits(40 * 1024 * 1024, MAX, 4096, 8);
    private static final String HASH = "sha256:" + "f".repeat(64);

    @Test void coldPointLookupNeverReadsUnrelatedPublicationPayloadsAtFiveTwentyOrFiftyReceipts() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var target = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(target, f.append(target, "rcp2/source", "tick"));
            var selected = f.engine.documents().storedState().closurePublicationReceipts().values().iterator().next();
            var application = application(selected, HASH);
            var objects = new RootedHistoryAccessObjects();
            var storage = storage(RootedEngineStorage.controlledNamespace(objects));
            var zero = storage.retainPartition(f.engine.documents().storedState());
            Set<String> requiredPayloads;
            try (var cold = storage.open(zero, 8, 256)) {
                objects.begin(Set.of());
                var actual = new InMemoryDocumentStore(new EngineMetrics(), cold.state())
                        .closureReceiptForApplication(application).orElseThrow();
                var measured = objects.end(Set.of(), Set.of());
                assertEquals(selected.publicationIdentity(), actual.publicationIdentity());
                requiredPayloads = new LinkedHashSet<>();
                measured.addresses().stream().filter(a -> a.operation().equals("GET") && !a.category().equals("index-node"))
                        .forEach(a -> requiredPayloads.add(a.address()));
                assertFalse(requiredPayloads.isEmpty(), "The control must decode an actual cold receipt");
            }
            var other = f.start(resource("source.yaml").replace("rcp2/source", "rcp2/unrelated").replace("RCP2 Source", "Unrelated"),
                    "rcp2/unrelated", ActivationPolicy.fromNow());
            for (int n = 1; n <= 50; n++) {
                f.process(other, f.append(other, "rcp2/unrelated", "tick"));
                if (!Set.of(5, 20, 50).contains(n)) continue;
                var state = f.engine.documents().storedState();
                assertEquals(n + 1, state.closurePublicationReceipts().size());
                var partition = storage.retainPartition(state);
                // Fresh owner and no shared decoded cache at every scale.
                try (var cold = storage.open(partition, 8, 256)) {
                    var documents = new InMemoryDocumentStore(new EngineMetrics(), cold.state());
                    objects.begin(Set.of());
                    var actual = documents.closureReceiptForApplication(application).orElseThrow();
                    var measured = objects.end(Set.of(), Set.of());
                    assertEquals(selected.publicationIdentity(), actual.publicationIdentity());
                    var unrelatedPayloads = measured.addresses().stream()
                            .filter(a -> a.operation().equals("GET") && !a.category().equals("index-node"))
                            .filter(a -> !requiredPayloads.contains(a.address())).toList();
                    assertEquals(List.of(), unrelatedPayloads, "No unrelated payload reads at N=" + n);
                    assertTrue(measured.puts().isEmpty(), "Lookup never repairs or writes its index");
                    var codec = new blue.language.processor.closure.ClosureProcessResultStorageCodec(MAX, 256);
                    assertArrayEquals(codec.encode(selected.attempt().processResult()), codec.encode(actual.attempt().processResult()),
                            "The selected complete result (including events, checkpoints and logical gas) is unchanged");
                    objects.begin(Set.of());
                    assertTrue(documents.closureReceiptForApplication(application(selected, HASH, HASH, HASH)).isEmpty());
                    var missing = objects.end(Set.of(), Set.of());
                    assertTrue(missing.addresses().stream().allMatch(a -> a.category().equals("index-node")),
                            "Missing result must not fall back to scanning receipt payloads");
                    var unchanged = cold.stage(cold.state());
                    for (var root : StoredDocumentStore.Root.values()) assertArrayEquals(partition.root(root), unchanged.root(root));
                }
            }
        }
    }

    @Test void selectedBindingAndLegacyPrecedenceArePreservedAndAmbiguityNeverChoosesFirst() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var target = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(target, f.append(target, "rcp2/source", "tick"));
            var state = f.engine.documents().storedState();
            var selected = state.closurePublicationReceipts().values().iterator().next();
            var app = application(selected, HASH);
            String key = ClosureApplicationResultIndex.key(app.contractsResultIdentity(), app.commitCompanionIdentity());
            var wrong = ClosureApplicationResultIndex.restoreStored(state.closureApplicationResults().rows().put(key,
                    new ClosureApplicationResultIndex.Publications(List.of("absent"))).map());
            assertThrows(IllegalStateException.class, () -> documents(state, wrong).closureReceiptForApplication(app));
            var duplicate = ClosureApplicationResultIndex.restoreStored(state.closureApplicationResults().rows().put(key,
                    new ClosureApplicationResultIndex.Publications(List.of("first", "second"))).map());
            assertThrows(IllegalStateException.class, () -> documents(state, duplicate).closureReceiptForApplication(app));
            assertSame(selected, documents(state, duplicate).closureReceiptForApplication(
                    application(selected, selected.publicationIdentity())).orElseThrow(), "Exact work-key lookup has original precedence");
            var wrongKey = ClosureApplicationResultIndex.restoreStored(state.closureApplicationResults().rows().put(
                    ClosureApplicationResultIndex.key(HASH, HASH), new ClosureApplicationResultIndex.Publications(List.of(selected.publicationIdentity()))).map());
            assertThrows(IllegalStateException.class, () -> documents(state, wrongKey)
                    .closureReceiptForApplication(application(selected, HASH, HASH, HASH)));
        }
    }

    @Test void strictRawStorageRejectsOmittedAndMisboundRowsAndFailureDoesNotAuthorizeReuse() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var target = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(target, f.append(target, "rcp2/source", "tick"));
            var state = f.engine.documents().storedState();
            var selected = state.closurePublicationReceipts().values().iterator().next();
            var app = application(selected, HASH);
            var bytes = new RootedHistoryAccessObjects(); var storage = storage(bytes);
            var partition = storage.retainPartition(state);
            try (var valid = storage.open(partition, 8, 256)) {
                assertEquals(selected.publicationIdentity(), new InMemoryDocumentStore(new EngineMetrics(), valid.state())
                        .closureReceiptForApplication(app).orElseThrow().publicationIdentity());
            }
            String key = ClosureApplicationResultIndex.key(app.contractsResultIdentity(), app.commitCompanionIdentity());
            var rows = state.closureApplicationResults().rows();
            for (var forged : List.of(rows.remove(key).map(), rows.put(key,
                    new ClosureApplicationResultIndex.Publications(List.of("absent"))).map(), rows.put(key,
                    new ClosureApplicationResultIndex.Publications(List.of("first", "second"))).map())) {
                var changed = replace(partition, retainIndex(bytes, forged));
                try (var cold = storage.open(changed, 8, 256)) {
                    var documents = new InMemoryDocumentStore(new EngineMetrics(), cold.state());
                    assertThrows(CoordinationObjectStorageException.class, () -> documents.closureReceiptForApplication(app));
                    assertThrows(CoordinationObjectStorageException.class, () -> documents.closureReceiptForApplication(app));
                    assertThrows(CoordinationObjectStorageException.class, () -> cold.stage(cold.state()));
                }
            }
        }
    }

    @Test void indexPublicationIsAtomicAndDoesNotAdvanceAfterFailedStoreSwap() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var before = f.engine.documents().storedState().closureApplicationResults();
            var next = f.append(300);
            f.engine.contractsClosureAdapter().onStoreFailurePoint(point -> {
                if (point == MultiDocumentPublicationTransaction.FailurePoint.BEFORE_SWAP)
                    throw new IllegalStateException("abort application-result publication");
            });
            try { assertThrows(RuntimeException.class, () -> f.blue.processing().processNext(f.parent)); }
            finally { f.engine.contractsClosureAdapter().onStoreFailurePoint(ignored -> { }); }
            assertSame(before, f.engine.documents().storedState().closureApplicationResults());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(f.parent).entry(next).disposition());
            var state = f.engine.documents().storedState();
            ClosureApplicationResultIndex.from(state.closurePublicationReceipts().values()).requireMatches(state.closureApplicationResults());
            assertNotSame(before, state.closureApplicationResults());
        }
    }

    @Test void ambiguityWitnessesAreBoundedCanonicalAndDoNotForgetAnExistingDuplicate() {
        var witnesses = new ClosureApplicationResultIndex.Publications(List.of("b"));
        assertEquals(List.of("b"), witnesses.with("b").identities());
        witnesses = witnesses.with("c").with("a").with("d");
        assertEquals(List.of("a", "b"), witnesses.identities());
        assertNotEquals(ClosureApplicationResultIndex.key("ab", "c"), ClosureApplicationResultIndex.key("a", "bc"));
    }

    private static ManagedEpochApplicationReceipt application(ContractsClosurePublicationReceipt selected, String work) {
        var result = selected.attempt().processResult();
        return application(selected, work, result.outputClosureIdentity(), result.commitCompanion().companionIdentity());
    }
    private static ManagedEpochApplicationReceipt application(ContractsClosurePublicationReceipt selected, String work,
            String resultIdentity, String companion) {
        var document = selected.publicationDocuments().values().iterator().next();
        return ManagedEpochApplicationReceipt.identified(work, HASH, HASH, HASH, resultIdentity, companion,
                selected.documentIds().get(0), 0, HASH, document.afterBlueId(), 0);
    }
    private static InMemoryDocumentStore documents(InMemoryDocumentStore.StoreState s, ClosureApplicationResultIndex index) {
        return new InMemoryDocumentStore(new EngineMetrics(), InMemoryDocumentStore.StoreState.trustedTransition(
                s.sessionIndex(), s.lineageIndex(), s.occurrenceInventory(), s.occurrenceInventoryGeneration(), s.componentIndex(),
                s.componentIndexGeneration(), s.graphGenerations(), s.componentStateInventory(), s.closureSubscriptions(),
                s.outboxLog(), s.checkpointEvidenceLog(), s.publicationReceiptIndex(), s.admissionReceiptIndex(),
                s.closurePublicationReceiptIndex(), s.rootedProviderFrontiers(), index, s.managedEpochReceipts(), s.catchUpPlans()));
    }
    private static byte[] retainIndex(CoordinationImmutableObjectStore bytes,
            PersistentOrderedMap<String, ClosureApplicationResultIndex.Publications> rows) {
        var sessions = new DocumentSessionStorage(bytes, SESSIONS);
        try (var scope = sessions.openScope(); var results = new StoredResultRows(bytes, SESSIONS);
                var publication = new StoredPublicationIndexes(bytes, MAPS, sessions, scope, results, SESSIONS.maximumDepth())) {
            return publication.retainApplicationResults(rows).storedRootDescriptor();
        }
    }
    private static StoredDocumentStore.Selection replace(StoredDocumentStore.Selection original, byte[] value) {
        var roots = new EnumMap<StoredDocumentStore.Root, byte[]>(StoredDocumentStore.Root.class);
        for (var root : StoredDocumentStore.Root.values())
            roots.put(root, root == StoredDocumentStore.Root.APPLICATION_RESULTS ? value : original.root(root));
        return new StoredDocumentStore.Selection(roots, original.metadata());
    }
    private static StoredDocumentStore storage(CoordinationImmutableObjectStore bytes) {
        return new StoredDocumentStore(bytes, MAPS, LOGS, SESSIONS);
    }
}
