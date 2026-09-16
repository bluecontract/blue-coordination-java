package blue.coordination.internal;

import blue.coordination.api.TimelineJournalStore;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.ExactNodeProvider;
import blue.coordination.sdk.TimelineActorKind;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Constructor integration, not a claim of complete physical StoreState transport. */
final class EngineInstallationStorageTest {
    @Test void completeEmptyRootedInstallationPreservesRegistrationsWithoutReplayOrJournalScan() {
        DefaultCoordinationEngine.StoredParts parts;
        byte[] expected;
        var control = new EngineControlStorageCodec(4 * 1024 * 1024);
        try (var blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            blue.timelines().register("one", "alice");
            blue.timelines().register("empty-agent", "agent", TimelineActorKind.AGENT);
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            parts = engine.storedParts(WholeObjectBacking.EMPTY);
            expected = control.encode(parts.control());
        }
        var reads = new AtomicInteger();
        var metadataOnlyJournal = new TimelineJournalStore() {
            public ReadView openRead() {
                reads.incrementAndGet();
                return new ReadView() {
                    public State state() { return State.empty(); }
                    public Optional<TimelineEntry> byBlueId(String id) { return fail("No entry read during restore"); }
                    public Optional<TimelineEntry> timelineHead(String id) { return fail("No Timeline-head read during restore"); }
                    public Optional<TimelineEntry> atAppendPosition(int n) { return fail("No journal scan during restore"); }
                    public Optional<TimelineEntry> atTimelineSequence(String id, long n) { return fail("No Timeline scan during restore"); }
                    public Optional<TimelineEntry> nextExternal(ExternalOrderKey key) { return fail("No next-work selection during restore"); }
                    public Optional<TimelineEntry> atExternalOrder(ExternalOrderKey key) { return fail("No order lookup during restore"); }
                    public Optional<ExternalOrderKey> latestExternalOrder() { return fail("No frontier recalculation during restore"); }
                    public void close() { }
                };
            }
            public void apply(State expected, Mutation mutation) { fail("Restoration must not append or rewrite a Timeline"); }
        };
        ExactNodeProvider unavailableProvider = id -> { fail("Restoration must not ask for application content"); return java.util.Optional.empty(); };
        try (var restored = DefaultCoordinationEngine.restoreRooted(parts, unavailableProvider, metadataOnlyJournal)) {
            assertArrayEquals(expected, control.encode(restored.controlStateForStorage()));
            assertEquals(1, reads.get(), "The existing journal constructor validates its one retained STATE row");
            assertEquals("alice", restored.auditRegisteredTimeline("one").orElseThrow().actorId());
            assertEquals("MyOS/MyOS Agent Actor", restored.timelineActorKind("empty-agent"));
            assertEquals(0, restored.documents().size());
        }
    }

    @Test void pristineEngineCannotTreatAnAbsentRequiredFamilyAsAnEmptyOne() {
        try (var blue = BlueCoordination.inMemory()) {
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            var parts = engine.storedParts(WholeObjectBacking.EMPTY);
            assertThrows(NullPointerException.class, () -> new DefaultCoordinationEngine.StoredParts(parts.control(), parts.objects(),
                    null, parts.routes(), parts.activeSources(), parts.plans(), parts.sources(), parts.feederMaps()));
            assertThrows(NullPointerException.class, () -> DefaultCoordinationEngine.restoreRooted(parts, ExactNodeProvider.empty(), null));
        }
    }
}
