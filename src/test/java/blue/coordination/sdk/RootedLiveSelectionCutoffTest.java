package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A strict search bound avoids constructing losing LIVE candidates, not their eventual logical execution. */
final class RootedLiveSelectionCutoffTest {
    @Test void anEqualFirstEntryIsNotCapturedAndTheUnboundedCallStillSelectsIt() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            var first = f.append(source, "rcp2/source", "tick", 10, "{}");
            var history = f.history(source);
            String body = source.snapshot().exact().json();
            long before = surfaceCompilations(f);
            // when
            var bounded = f.control.nextLiveInputBefore(source.id(), first.blueId());
            // then
            assertTrue(bounded.isEmpty());
            assertEquals(before, surfaceCompilations(f), "No entry before the bound means no root capture");
            assertEquals(history, f.history(source));
            assertEquals(body, source.snapshot().exact().json());
            assertEquals(first.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            assertEquals(before + 1L, surfaceCompilations(f), "The positive unbounded control really captures the root");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(source).entry(first).disposition());
            assertEquals(1L, source.snapshot().longAt("/counter"));
            assertEquals(history.size() + 1, f.history(source).size());
        }
    }

    @Test void earlierNoMatchAndRejectedTerminalInputsDoNotHideTheNextLiveInput() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.startYaml(RootedSdkFixture.resource("source.yaml") + """
                      reject:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request: {}
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $appendChange:
                              op: remove
                              path: /does-not-exist
                          - $return: true
                    """, "rcp2/source");
            var original = f.history(source);
            var rejected = f.append(source, "rcp2/source", "reject", 1, "{}");
            assertEquals(EntryDisposition.REJECTED, f.blue.processing().processNext(source).entry(rejected).disposition());
            var unmatched = broadcast(f, "rcp2/source", "absent", 2);
            var live = f.append(source, "rcp2/source", "tick", 3, "{}");
            var bound = f.append(source, "rcp2/source", "tick", 4, "{}");
            // when
            var selected = f.control.nextLiveInputBefore(source.id(), bound.blueId());
            // then
            assertEquals(live.blueId(), selected.orElseThrow());
            assertEquals(selected, f.control.nextLiveInput(source.id()));
            assertEquals(original, f.history(source));
            var transport = f.blue.advanced().drainJournalThrough(unmatched, DrainBudget.unlimited());
            assertEquals(EntryDisposition.NO_MATCH, transport.entry(unmatched).disposition());
            assertEquals(0L, transport.entry(unmatched).stats().gas());
            assertEquals(live.blueId(), f.control.nextLiveInputBefore(source.id(), bound.blueId()).orElseThrow());
            var applied = f.blue.processing().processNext(source).entry(live);
            assertEquals(EntryDisposition.APPLIED, applied.disposition());
            assertTrue(applied.stats().gas() > 0L);
            assertEquals(1L, source.snapshot().longAt("/counter"));
            assertTrue(f.control.nextLiveInputBefore(source.id(), bound.blueId()).isEmpty());
            assertEquals(bound.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(f.control.nextLiveInputBefore(source.id(), bound.blueId()).isEmpty());
            assertEquals(bound.blueId(), f.control.nextLiveInput(source.id()).orElseThrow());
            assertEquals(original.size() + 1, f.history(source).size());
        }
    }

    @Test void anEarlierLiveInputWinsBeforeRegisteredHistoryWithoutPublishingItsBorrowedSource() throws Exception {
        // given
        boolean equalSourceOrder = false;
        // when
        var evidence = verifyRetainedOrdering(equalSourceOrder);
        // then
        assertEquals(List.of("LIVE", "HISTORY"), evidence);
    }

    @Test void registeredHistoryWinsTheExactSameEntryTieAndTheLiveInputRemainsPending() throws Exception {
        // given
        boolean equalSourceOrder = true;
        // when
        var evidence = verifyRetainedOrdering(equalSourceOrder);
        // then
        assertEquals(List.of("HISTORY", "LIVE"), evidence);
    }

    private static List<String> verifyRetainedOrdering(boolean equalSourceOrder) throws Exception {
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            String original = f.retain(source);
            String liveTimeline = equalSourceOrder ? "rcp2/source" : "rcp2/live";
            var borrowed = f.startYaml(RootedSdkFixture.resource("source.yaml")
                    .replace("RCP2 Source", "RCP2 Live Sibling").replace("rcp2/source", liveTimeline), liveTimeline);
            var parent = f.startYaml(RootedSdkFixture.resource("parent.yaml")
                    .replace("    - /child", "    - /child\n    - /other")
                    + "\nother:\n  blueId: " + f.retain(borrowed) + "\n", "rcp2/parent");
            var earlier = equalSourceOrder ? null : broadcast(f, liveTimeline, "tick", 5);
            var sourceEntry = broadcast(f, "rcp2/source", "tick", 10);
            var live = equalSourceOrder ? sourceEntry : earlier;
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, sourceEntry).entry(sourceEntry).disposition());
            var attach = f.append(parent, "rcp2/parent", "attach", 20, "child: {blueId: " + original + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(parent, attach).entry(attach).disposition());
            var sourceHistories = List.of(f.history(source), f.history(borrowed));
            var sourceHeads = List.of(source.snapshot().blueId(), borrowed.snapshot().blueId());
            assertEquals(sourceEntry.blueId(), f.blue.advanced().auditManagedEpoch(source.id(), 1L)
                    .orElseThrow().sourceEntry().orElseThrow().blueId());
            assertEquals(live.blueId(), f.control.nextLiveInput(parent.id()).orElseThrow());

            var evidence = new java.util.ArrayList<String>();
            if (!equalSourceOrder) {
                assertEquals(live.blueId(), f.control.nextLiveInputBefore(parent.id(), sourceEntry.blueId()).orElseThrow());
                assertLive(f, parent, live);
                evidence.add("LIVE");
            } else {
                long before = surfaceCompilations(f);
                assertTrue(f.control.nextLiveInputBefore(parent.id(), sourceEntry.blueId()).isEmpty());
                assertEquals(before, surfaceCompilations(f), "The same exact Timeline entry cannot outrank its retained receipt");
            }
            var work = f.control.registeredOwnedHistory(parent.id());
            assertEquals(source.id(), work.sourceDocumentId());
            assertEquals(1L, work.sourceEpoch());
            var historical = f.blue.processing().processNext(parent);
            assertTrue(historical.entries().isEmpty());
            assertEquals(1, historical.managedEpochApplications().size());
            assertEquals(work.workIdentity(), historical.managedEpochApplicationAttempts().get(0).work().workIdentity());
            assertTrue(historical.managedEpochApplicationAttempts().get(0).attempt().processResult().totalGas() > 0L);
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            evidence.add("HISTORY");
            if (equalSourceOrder) {
                assertEquals(live.blueId(), f.control.nextLiveInput(parent.id()).orElseThrow());
                assertLive(f, parent, live);
                evidence.add("LIVE");
            }
            assertEquals(1L, ((Number) f.selected(parent, "/other").scalarAt("/counter")).longValue());
            assertEquals(0L, borrowed.snapshot().longAt("/counter"));
            assertEquals(sourceHistories, List.of(f.history(source), f.history(borrowed)));
            assertEquals(sourceHeads, List.of(source.snapshot().blueId(), borrowed.snapshot().blueId()));
            String parentHead = parent.snapshot().blueId();
            var parentHistory = f.history(parent);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(parentHead, parent.snapshot().blueId());
            assertEquals(parentHistory, f.history(parent));
            assertTrue(f.control.nextLiveInput(parent.id()).isEmpty());
            return List.copyOf(evidence);
        }
    }

    private static void assertLive(RootedSdkFixture f, DocumentHandle parent, EntryHandle entry) {
        var drained = f.blue.processing().processNext(parent);
        assertEquals(EntryDisposition.APPLIED, drained.entry(entry).disposition());
        assertTrue(drained.managedEpochApplicationAttempts().isEmpty());
        assertTrue(drained.entry(entry).stats().gas() > 0L);
    }

    private static long surfaceCompilations(RootedSdkFixture f) {
        return CoordinationTestControl.attach(f.blue.advanced().rawEngine()).metricsSnapshot().counters()
                .getOrDefault("routing.surfaceCompilations", 0L);
    }

    private static EntryHandle broadcast(RootedSdkFixture f, String timeline, String operation, long timestamp) {
        String yaml = """
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: %s
                timestamp: %d
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
                message:
                  type: Coordination/Operation Request
                  operation: %s
                  channel: owner
                  request: {}
                """.formatted(timeline, timestamp, operation);
        String previous = f.previousEntries.get(timeline);
        if (previous != null) yaml += "\nprevEntry:\n  blueId: " + previous + "\n";
        var entry = f.blue.events().from(f.timelines.get(timeline)).exact(f.blue.values().yaml(yaml)).submit();
        f.previousEntries.put(timeline, entry.blueId());
        return entry;
    }
}
