package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coordination specification §6.2 defines the exact Timeline identity as the
 * BlueId of the complete exact Timeline value, including its concrete type.
 * §7.3 requires every entry of one Timeline to carry that same identity, and
 * §9.2 orders entries across Timelines by that BlueId. A registered Timeline
 * must therefore reject an exact entry whose {@code /timeline} value shares
 * only the {@code timelineId} text with the registered Timeline.
 */
final class SdkExactTimelineIdentityTest {
    private static final String ACTOR = "alice";
    private static final String TIMELINE_ID = "bank/account/42";
    private static final DocumentId LEDGER = DocumentId.of(
            "sdk-exact-timeline-identity-ledger");
    /** Far-future micros keep the foreign entry after the SDK-authored one. */
    private static final long FOREIGN_TIMESTAMP = 2_100_000_000_000_801L;

    @Test
    void exactEntryWithDifferentTimelineIdentityButSameTextIdsIsRejected() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            TimelineHandle timeline = coordination.timelines().register(
                    TIMELINE_ID, ACTOR);
            DocumentHandle ledger = coordination.documents().admit(
                    ManagedDocument.yaml(LEDGER, ledgerYaml())
                            .publicRoot()
                            .fromNow());
            EntryResult registered = count(coordination, ledger, timeline)
                    .execute();
            assertEquals(EntryDisposition.APPLIED, registered.disposition(),
                    registered.diagnostic().toString());
            List<TimelineEntrySnapshot> journalBefore =
                    coordination.advanced().auditTimeline(TIMELINE_ID);
            ExactBlueValue foreign = foreignProviderEntry(
                    coordination, registered.entry().blueId());
            assertNotEquals(
                    timelineIdentity(journalBefore.get(0).exact()),
                    timelineIdentity(foreign),
                    "the foreign entry names a different exact Timeline");

            // when
            assertThrows(IllegalArgumentException.class,
                    () -> coordination.events()
                            .from(timeline)
                            .exact(foreign)
                            .submit(),
                    "an entry of another exact Timeline must not be admitted "
                            + "under the registered Timeline");

            // then
            assertEquals(journalBefore,
                    coordination.advanced().auditTimeline(TIMELINE_ID),
                    "the journal and the Timeline head must stay unchanged");
            assertEquals(1L, ledger.snapshot().longAt("/count"));
        }
    }

    /**
     * Pins the current text-only ingress so the merge is visible; this test
     * must turn red once exact Timeline identity is enforced.
     */
    @Test
    void currentIngressMergesTimelinesThatShareOnlyTextIdentifiers() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            TimelineHandle timeline = coordination.timelines().register(
                    TIMELINE_ID, ACTOR);
            DocumentHandle ledger = coordination.documents().admit(
                    ManagedDocument.yaml(LEDGER, ledgerYaml())
                            .publicRoot()
                            .fromNow());
            EntryResult registered = count(coordination, ledger, timeline)
                    .execute();
            assertEquals(EntryDisposition.APPLIED, registered.disposition(),
                    registered.diagnostic().toString());
            ExactBlueValue foreign = foreignProviderEntry(
                    coordination, registered.entry().blueId());

            // when
            EntryHandle foreignEntry = coordination.events()
                    .from(timeline)
                    .exact(foreign)
                    .submit();
            DrainResult drained = coordination.processing().drain();
            EntryResult afterForeign = count(coordination, ledger, timeline)
                    .execute();

            // then
            List<TimelineEntrySnapshot> journal =
                    coordination.advanced().auditTimeline(TIMELINE_ID);
            assertEquals(3, journal.size(),
                    "the foreign entry is admitted under the registered "
                            + "timelineId text");
            assertNotEquals(
                    timelineIdentity(journal.get(0).exact()),
                    timelineIdentity(journal.get(1).exact()),
                    "two exact Timeline identities now share one journal "
                            + "lane");
            assertEquals(Optional.of(registered.entry().blueId()),
                    journal.get(1).previousEntryBlueId(),
                    "the foreign entry chains onto the MyOS head");
            assertEquals(Optional.of(foreignEntry.blueId()),
                    journal.get(2).previousEntryBlueId(),
                    "the next SDK-authored MyOS entry chains onto the "
                            + "foreign entry");
            assertEquals(EntryDisposition.APPLIED,
                    drained.entry(foreignEntry).disposition(),
                    drained.entry(foreignEntry).diagnostic().toString());
            assertEquals(EntryDisposition.APPLIED, afterForeign.disposition(),
                    afterForeign.diagnostic().toString());
            assertEquals(3L, ledger.snapshot().longAt("/count"),
                    "the ledger consumed the foreign entry through its MyOS "
                            + "Timeline Channel");
        }
    }

    private static OperationCall count(
            BlueCoordination coordination,
            DocumentHandle ledger,
            TimelineHandle timeline) {
        return coordination.operations()
                .on(ledger)
                .from(timeline)
                .call("count")
                .through("ownerChannel")
                .request(request -> { });
    }

    /**
     * A complete exact entry whose Timeline has the registered
     * {@code timelineId} and actor but a different concrete Timeline type,
     * hence a different exact Timeline BlueId.
     */
    private static ExactBlueValue   foreignProviderEntry(
            BlueCoordination coordination,
            String previousEntryBlueId) {
        return coordination.values().yaml("""
                type: Coordination/Timeline Entry
                timeline:
                  type:
                    name: Provider B Bank Timeline
                    description: Same timelineId text, other provider semantics.
                    type: Coordination/Timeline
                  timelineId: %s
                prevEntry:
                  blueId: %s
                timestamp: %d
                actor:
                  type: MyOS/Principal Actor
                  accountId: %s
                message:
                  type: Coordination/Operation Request
                  operation: count
                  channel: ownerChannel
                  request: {}
                """.formatted(
                TIMELINE_ID, previousEntryBlueId, FOREIGN_TIMESTAMP, ACTOR));
    }

    private static String timelineIdentity(ExactBlueValue entry) {
        return entry.unwrap().canonicalBlueIdAt("/timeline");
    }

    private static String ledgerYaml() {
        return """
                documentId: %s
                count: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  count:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /count
                              val: {$add: [{$document: /count}, 1]}
                          - $return: true
                """.formatted(LEDGER.value(), TIMELINE_ID, ACTOR);
    }
}
