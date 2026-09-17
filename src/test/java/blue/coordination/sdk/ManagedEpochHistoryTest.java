package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.ColdStorageJournalFixture;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.coordination.internal.InsertionOrderedStorage;
import blue.coordination.internal.RootedEngineStorage;
import blue.language.model.Node;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

final class ManagedEpochHistoryTest {
    private static final DocumentId SOURCE = DocumentId.of("history-artifact-source");
    private static final SdkStorageCodec.Configuration CONFIG = new SdkStorageCodec.Configuration(
            "language", "contracts", false, true, ContractsExecutionPolicy.releaseDefault(), Map.of());

    @ParameterizedTest @ValueSource(ints = {5, 50, 100})
    void ownedPrefixProjectsOnlyDeltaAndRetainsExactArtifacts(int count) {
        var selected = receipts(SOURCE, count);
        var projects = new AtomicInteger();
        var first = capture(SOURCE, CONFIG, selected, null, projects);
        assertEquals(count, projects.get()); assertEquals(0, first.retainedPrefixLength());
        var warm = capture(SOURCE, CONFIG, selected, first, projects);
        assertEquals(count, projects.get()); assertEquals(count, warm.retainedPrefixLength());
        assertTrue(warm.appendedReceipts().isEmpty());
        selected.add(receipt(SOURCE, count));
        var appended = capture(SOURCE, CONFIG, selected, warm, projects);
        assertEquals(count + 1, projects.get()); assertEquals(count, appended.retainedPrefixLength());
        assertEquals(List.of((long) count), appended.appendedReceipts().stream().map(ManagedEpochReceipt::epoch).toList());
        for (int i = 0; i < count; i++) {
            assertSame(first.receipts().get(i), appended.receipts().get(i));
            assertSame(first.receipts().get(i).afterDocument().verifiedEvidence(),
                    appended.receipts().get(i).afterDocument().verifiedEvidence());
        }
        assertEquals(first.estimatedRetainedBytes(), warm.estimatedRetainedBytes());
        assertTrue(appended.estimatedRetainedBytes() > warm.estimatedRetainedBytes());
        selected.clear(); assertEquals(count + 1, appended.receipts().size());
        assertThrows(UnsupportedOperationException.class, () -> appended.receipts().clear());
        assertThrows(UnsupportedOperationException.class, () -> appended.appendedReceipts().clear());
    }

    @Test void equalLogicalReceiptsDoNotBorrowAnotherObjectProofAndForeignDomainsAreCold() {
        // given
        var projects = new AtomicInteger();
        var original = receipts(SOURCE, 3);
        var first = capture(SOURCE, CONFIG, original, null, projects);
        // when
        var reconstructed = receipts(SOURCE, 3);
        // then
        assertEquals(first.receipts().stream().map(ManagedEpochReceipt::receiptIdentity).toList(),
                reconstructed.stream().map(blue.coordination.api.ManagedEpochReceipt::receiptIdentity).toList());
        assertEquals(0, capture(SOURCE, CONFIG, reconstructed, first, projects).retainedPrefixLength());
        var foreign = DocumentId.of("other-history-source");
        assertEquals(0, capture(foreign, CONFIG, receipts(foreign, 3), first, projects).retainedPrefixLength());
        var changedPolicy = new SdkStorageCodec.Configuration(CONFIG.language(), CONFIG.contracts(), false, true,
                ContractsExecutionPolicy.exactSharedGas(123, "another-policy"), Map.of());
        assertEquals(0, capture(SOURCE, changedPolicy, original, first, projects).retainedPrefixLength());
        assertEquals(0, capture(SOURCE, CONFIG, original.subList(0, 2), first, projects).retainedPrefixLength());
    }

    @Test void laterMalformedOwnerGapAndOrderRejectBeforeIssuingAnyPartialArtifact() {
        // given
        var projects = new AtomicInteger();
        var selected = receipts(SOURCE, 3);
        var prefix = capture(SOURCE, CONFIG, selected, null, projects);
        // when
        projects.set(0);
        for (var malformed : List.of(
                List.of(selected.get(0), selected.get(2)),
                List.of(selected.get(0), selected.get(2), selected.get(1)),
                List.of(selected.get(0), receipt(DocumentId.of("foreign"), 1)))) {
            // then
            assertThrows(IllegalArgumentException.class, () -> capture(SOURCE, CONFIG, malformed, prefix, projects));
            assertEquals(0, projects.get());
        }
        var replacement = receipts(SOURCE, 3); replacement.set(0, selected.get(0));
        var changed = capture(SOURCE, CONFIG, replacement, prefix, projects);
        assertEquals(1, changed.retainedPrefixLength()); assertEquals(2, projects.get());
        assertEquals(List.of(1L, 2L), changed.appendedReceipts().stream().map(ManagedEpochReceipt::epoch).toList());
    }

    @Test void duplicateEventsGasAndDetachedValueCopiesArePreserved() {
        // given
        var event = ExactValue.verified(new Node().value("same event"));
        var first = blue.coordination.api.ManagedEventOccurrence.identified(0, 3, SOURCE, hash(30), event, true);
        var second = blue.coordination.api.ManagedEventOccurrence.identified(1, 4, SOURCE, hash(31), event, false);
        var initial = blue.coordination.api.ManagedEpochReceipt.identified(SOURCE, 0,
                blue.coordination.api.DocumentRevision.Kind.INITIALIZATION, null,
                ExactValue.verified(new Node().value("state")), hash(1), null, null,
                hash(2), hash(3), List.of(first, second), 47);
        var artifact = capture(SOURCE, CONFIG, List.of(initial), null, new AtomicInteger());
        // when
        var receipt = artifact.receipts().get(0);
        // then
        assertEquals(47, receipt.processingGas());
        assertEquals(List.of(first.managedEventIdentity(), second.managedEventIdentity()),
                receipt.emittedEvents().stream().map(ManagedEventOccurrence::managedEventIdentity).toList());
        assertEquals(List.of(true, false), receipt.emittedEvents().stream().map(ManagedEventOccurrence::publicAtSource).toList());
        var before = receipt.afterDocument().json(); receipt.afterDocument().copyNode().value("tamper");
        assertEquals(before, receipt.afterDocument().verifiedEvidence().exactContent());
        assertSame(receipt.emittedEvents().get(0).exactEvent(), receipt.emittedEvents().get(0).exactEvent());
        assertEquals(receipt.emittedEvents().get(0).exactEvent().json(), receipt.emittedEvents().get(1).exactEvent().json());
    }

    @Test void freshOwnersReuseVerifiedReceiptFramesButColdAndFailedPhysicalReadsDoNot() throws Exception {
        // given
        var objects = new Bytes(); RootedCoordinationStorage.Selection selected;
        ColdStorageJournalFixture.Snapshot journal; DocumentId document;
        try (var original = new RootedSdkFixture()) {
            var source = original.start("source.yaml", "rcp2/source", Map.of()); document = source.id();
            // when
            var applied = original.blue.operations().on(source).from(original.timelines.get("rcp2/source"))
                    .call("setCounter").through("owner").requestYaml("counterValue: 5").execute();
            // then
            assertEquals(EntryDisposition.APPLIED, applied.disposition());
            selected = RootedCoordinationStorage.retainPartition(original.blue, objects, limits());
            journal = ColdStorageJournalFixture.retain((DefaultCoordinationEngine) original.blue.advanced().rawEngine());
        }
        try (var cache = new RootedCoordinationStorage.Cache(256L * 1024 * 1024, 1000, 128L * 1024 * 1024)) {
            ManagedEpochHistory first;
            try (var scope = RootedCoordinationStorage.open(objects, limits(), selected, ExactNodeProvider.empty(),
                    ColdStorageJournalFixture.open(journal), cache)) {
                var blue = scope.coordination();
                first = blue.advanced().auditManagedEpochHistory(document, null);
                assertEquals(0, first.retainedPrefixLength()); assertEquals(2, first.receipts().size());
                var source = first.receipts().get(1).sourceEntry().orElseThrow();
                assertNotSame(blue, source.timeline().owner(), "History must not retain the live SDK owner");
                assertThrows(IllegalArgumentException.class, () -> blue.events().from(source.timeline()).exact(source.exact()).submit());
                assertEquals(blue.advanced().auditManagedEpochs(document).stream().map(ManagedEpochReceipt::receiptIdentity).toList(),
                        first.receipts().stream().map(ManagedEpochReceipt::receiptIdentity).toList());
            }
            int reads = objects.reads;
            try (var scope = RootedCoordinationStorage.open(objects, limits(), selected, ExactNodeProvider.empty(),
                    ColdStorageJournalFixture.open(journal), cache)) {
                var warm = scope.coordination().advanced().auditManagedEpochHistory(document, first);
                assertEquals(first.receipts().size(), warm.retainedPrefixLength());
                assertSame(first.receipts().get(1), warm.receipts().get(1));
            }
            assertTrue(objects.reads > reads, "Warm history still selects current physical evidence");
            var latestSelection = selected; var latestJournal = journal; var latestHistory = first;
            for (int step = 0; step < 3; step++) {
                var currentJournal = ColdStorageJournalFixture.open(latestJournal);
                try (var scope = RootedCoordinationStorage.open(objects, limits(), latestSelection,
                        ExactNodeProvider.empty(), currentJournal, cache)) {
                    var blue = scope.coordination();
                    var before = blue.advanced().auditManagedEpochHistory(document, latestHistory);
                    assertEquals(latestHistory.receipts().size(), before.retainedPrefixLength(),
                            "Publish/reopen must retain the complete old producer prefix, not only reopen a fixed selection");
                    var result = blue.operations().on(blue.documents().require(document))
                            .from(blue.timelines().register("rcp2/source", "alice"))
                            .call("setCounter").through("owner").requestYaml("counterValue: " + (10 + step)).execute();
                    assertEquals(EntryDisposition.APPLIED, result.disposition());
                    latestHistory = blue.advanced().auditManagedEpochHistory(document, before);
                    assertEquals(before.receipts().size(), latestHistory.retainedPrefixLength());
                    assertEquals(1, latestHistory.appendedReceipts().size());
                    latestSelection = scope.stage(); latestJournal = currentJournal.snapshot();
                }
            }
            cache.clear();
            try (var scope = RootedCoordinationStorage.open(objects, limits(), latestSelection, ExactNodeProvider.empty(),
                    ColdStorageJournalFixture.open(latestJournal), cache)) {
                assertEquals(0, scope.coordination().advanced().auditManagedEpochHistory(document, latestHistory).retainedPrefixLength());
            }
            var selectedAtFailure = latestSelection; var journalAtFailure = latestJournal; var historyAtFailure = latestHistory;
            objects.failReads = true;
            assertThrows(RuntimeException.class, () -> {
                try (var scope = RootedCoordinationStorage.open(objects, limits(), selectedAtFailure, ExactNodeProvider.empty(),
                        ColdStorageJournalFixture.open(journalAtFailure), cache)) {
                    scope.coordination().advanced().auditManagedEpochHistory(document, historyAtFailure);
                }
            });
        }
    }

    @Test void noPublicConstructorCanForgeAHistoryCapability() {
        // given
        var constructors = ManagedEpochHistory.class.getDeclaredConstructors();
        // when
        boolean allPrivate = Arrays.stream(constructors)
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        // then
        assertTrue(allPrivate);
    }

    private static ManagedEpochHistory capture(DocumentId document, SdkStorageCodec.Configuration config,
            List<blue.coordination.api.ManagedEpochReceipt> selected, ManagedEpochHistory previous, AtomicInteger count) {
        return ManagedEpochHistory.capture(document, config, selected, previous, receipt -> {
            count.incrementAndGet(); return new ManagedEpochReceipt(receipt, null);
        });
    }
    private static List<blue.coordination.api.ManagedEpochReceipt> receipts(DocumentId document, int count) {
        var receipts = new ArrayList<blue.coordination.api.ManagedEpochReceipt>();
        for (int i = 0; i < count; i++) receipts.add(receipt(document, i));
        return receipts;
    }
    private static blue.coordination.api.ManagedEpochReceipt receipt(DocumentId document, int epoch) {
        return blue.coordination.api.ManagedEpochReceipt.identified(document, epoch,
                epoch == 0 ? blue.coordination.api.DocumentRevision.Kind.INITIALIZATION : blue.coordination.api.DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                epoch == 0 ? null : ExactValue.verified(new Node().value("state-" + (epoch - 1))).blueId(),
                ExactValue.verified(new Node().value("state-" + epoch)), hash(1), null, null,
                hash(epoch + 2), hash(epoch + 3), List.of(), epoch);
    }
    private static String hash(int value) { return "sha256:" + String.format("%064x", value); }
    private static RootedCoordinationStorage.Limits limits() {
        int max = 32 * 1024 * 1024;
        return new RootedCoordinationStorage.Limits(new RootedEngineStorage.Limits(40 * 1024 * 1024, 64 * 1024, max,
                8192, 32, max, 256, 256L * 1024 * 1024, 100, 100, 100, 256L * 1024 * 1024, 40 * 1024 * 1024, max, 16),
                new RootedCoordinationStorage.SdkLimits(new InsertionOrderedStorage.Limits(512 * 1024, 64 * 1024,
                        64 * 1024, 8192, 32, 256 * 1024, 4L * 1024 * 1024, 100), max, 128 * 1024, max, 100, 256 * 1024));
    }
    private static final class Bytes implements CoordinationImmutableObjectStore {
        private final Map<String, byte[]> values = new LinkedHashMap<>(); private int reads; private boolean failReads;
        @Override public byte[] putIfAbsent(String address, byte[] bytes) {
            var prior = values.putIfAbsent(address, bytes.clone()); return prior == null ? bytes.clone() : prior.clone();
        }
        @Override public Optional<byte[]> get(String address, int maximumBytes) {
            reads++; if (failReads) throw new CoordinationObjectStorageException("injected physical read failure");
            var bytes = values.get(address);
            if (bytes != null && bytes.length > maximumBytes) throw new CoordinationObjectStorageException("oversized row");
            return Optional.ofNullable(bytes == null ? null : bytes.clone());
        }
    }
}
