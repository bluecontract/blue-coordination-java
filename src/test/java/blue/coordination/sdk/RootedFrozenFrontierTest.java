package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** A historical interval ends at attachment order, independently of a cached future source head. */
final class RootedFrozenFrontierTest {
    @Test
    void attachmentAt200ImportsOnly100105115ThenProcesses250BeforeLive500() throws Exception {
        // given
        boolean sourceAlreadyAhead = true;
        // when
        var log = verify(sourceAlreadyAhead);
        // then
        assertEquals(List.of(1L, 2L, 3L, 4L), log);
    }

    @Test
    void aSourcePublishedAfterAttachmentCannotExtendTheFrozenInterval() throws Exception {
        // given
        boolean sourceAlreadyAhead = false;
        // when
        var log = verify(sourceAlreadyAhead);
        // then
        assertEquals(List.of(1L, 2L, 3L, 4L), log);
    }

    private List<Long> verify(boolean sourceAlreadyAhead) throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var a = fixture.start("historical-a.yaml", "rcp2/a", Map.of());
            String a0 = a.snapshot().blueId();
            var b = fixture.startYaml(consumer(), "rcp2/b");
            EntryHandle future = null;
            for (long time : sourceAlreadyAhead ? List.of(100L, 105L, 115L, 500L) : List.of(100L, 105L, 115L)) {
                var entry = fixture.append(a, "rcp2/a", "tick", time, "{}");
                assertEquals(EntryDisposition.APPLIED, blue.processing().process(a, entry).entry(entry).disposition());
                fixture.retain(a);
                if (time == 500) future = entry;
            }
            String a4 = a.snapshot().blueId();
            var sourceHistory = fixture.history(a);
            String previousReady = b.snapshot().blueId();
            var attach = fixture.append(b, "rcp2/b", "attach", 200, "child:\n  blueId: " + a0);
            assertEquals(EntryDisposition.APPLIED, blue.processing().process(b, attach).entry(attach).disposition());
            var plans = blue.advanced().auditManagedCatchUpPlans(b.id());
            assertEquals(1, plans.size());
            assertEquals(3L, plans.get(0).requiredThroughSourceEpoch(), "The future source head is not the attachment frontier");
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(plans, blue.advanced().auditManagedCatchUpPlans(b.id()));
            if (!sourceAlreadyAhead) {
                future = fixture.append(a, "rcp2/a", "tick", 500, "{}");
                assertEquals(EntryDisposition.APPLIED, blue.processing().process(a, future).entry(future).disposition());
                fixture.retain(a);
                assertEquals(sourceHistory, fixture.history(a).subList(0, sourceHistory.size()));
                a4 = a.snapshot().blueId();
                sourceHistory = fixture.history(a);
                assertEquals(3L, blue.advanced().auditManagedCatchUpPlans(b.id()).get(0).requiredThroughSourceEpoch());
            }
            var own = fixture.append(b, "rcp2/b", "mark", 250, "{}");
            for (int epoch = 1; epoch <= 3; epoch++) {
                var result = blue.processing().processNext(b);
                assertEquals(1, result.managedEpochApplications().size());
                assertEquals(epoch, result.managedEpochApplicationAttempts().get(0).work().sourceEpoch());
                assertEquals(epoch, ((Number) blue.advanced().auditDocument(b.id()).current().copyNode().get("/seen")).longValue());
                if (epoch < 3) assertEquals(previousReady, b.snapshot().blueId(), "Pending catch-up retains the last valid Ready view");
                assertEquals(a4, a.snapshot().blueId());
                assertEquals(sourceHistory, fixture.history(a));
            }
            assertTrue(blue.advanced().auditManagedOccurrence(b.id(), "/child").orElseThrow().active());
            String selectedChild = blue.advanced().auditDocument(b.id()).current().canonicalBlueIdAt("/child");
            assertEquals(3L, ((Number) blue.values().retained(selectedChild).orElseThrow().scalarAt("/counter")).longValue());
            var ownResult = blue.processing().processNext(b);
            assertTrue(ownResult.managedEpochApplications().isEmpty());
            assertEquals(EntryDisposition.APPLIED, ownResult.entry(own).disposition());
            assertEquals(3L, ((Number) blue.advanced().auditDocument(b.id()).current().copyNode().get("/seenAtOwnEntry")).longValue());
            var futureResult = blue.processing().processNext(b);
            assertTrue(futureResult.managedEpochApplications().isEmpty(), "500 is LIVE, never retained catch-up");
            assertEquals(EntryDisposition.APPLIED, futureResult.entry(future).disposition());
            assertEquals(4L, b.snapshot().longAt("/seen"));
            for (int i = 0; i < 4; i++) assertEquals(i + 1, b.snapshot().longAt("/log/" + i));
            assertEquals(sourceHistory, fixture.history(a));
            var heads = List.of(a.snapshot().blueId(), b.snapshot().blueId());
            var histories = List.of(fixture.history(a), fixture.history(b));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(heads, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
            assertEquals(histories, List.of(fixture.history(a), fixture.history(b)));
            assertTrue(blue.processing().processNext(b).entries().isEmpty());
            return java.util.stream.IntStream.range(0, 4).mapToObj(i -> b.snapshot().longAt("/log/" + i)).toList();
        }
    }

    private static String consumer() throws Exception {
        try (var in = RootedFrozenFrontierTest.class.getResourceAsStream("/rooted/historical-b.yaml")) {
            return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8)
                    .replace("seen: 0", "seen: 0\nseenAtOwnEntry: -1") + """
                      mark:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request: {}
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $appendChange:
                              op: replace
                              path: /seenAtOwnEntry
                              val:
                                $document: /seen
                          - $return: true
                    """;
        }
    }
}
