package blue.coordination.sdk;

import blue.coordination.api.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Missing external history must not become native CompleteEmpty merely because retained rows are empty. */
final class FiniteTimelineHistoryCoverageTest {
    @Test void unseenSourceRequiresCoverageThenOriginalEarlierInputBeforeParentCanProceed() throws Exception {
        // given
        var fixture = new Fixture();
        String source = RootedSdkFixture.resource("source.yaml"), parent = RootedSdkFixture.resource("parent.yaml");
        var ids = fixture.transact(scope -> {
            var blue = scope.coordination();
            blue.timelines().register("rcp2/source", "alice"); blue.timelines().register("rcp2/parent", "alice");
            var value = blue.values().yaml(source); fixture.account.exact.put(value.blueId(), value.json());
            var a = blue.documents().admitStaticProcessEmbedded(source, ActivationPolicy.importFullHistory()).document("root");
            var b = blue.documents().admitStaticProcessEmbedded(parent, ActivationPolicy.importFullHistory()).document("root");
            fixture.account.timestamp = 100;
            fixture.account.append(scope, b, "rcp2/parent", "owner", "attach", "child: {blueId: " + value.blueId() + "}");
            return List.of(a.id(), b.id());
        });
        // when
        fixture.coverage = (timelines, boundary, inclusive) -> timelines.contains("rcp2/source")
                ? TimelineHistoryCoverage.Evidence.unavailable("source-before-90", "Source provider prefix is incomplete")
                : TimelineHistoryCoverage.Evidence.complete("parent-through-101");
        fixture.transact(scope -> {
            var b = scope.documentHandle(ids.get(1)).orElseThrow();
            // then
            // Supplied root input is complete, but newly discovered source history is not.
            assertEquals(ProcessingStageResult.Disposition.WAITING,
                    scope.coordination().processing().selectStage(b, fixture.account.previous.get("rcp2/parent")).execute().disposition());
            assertEquals(0, b.snapshot().epoch());
            assertEquals(SourceHistoryPrerequisite.Kind.WAIT,
                    scope.coordination().advanced().sourceHistoryRequests(b).get(0).prerequisite().kind());
            return null;
        });
        String original = fixture.transact(scope -> {
            fixture.account.timestamp = 90;
            return fixture.account.append(scope, scope.documentHandle(ids.get(0)).orElseThrow(),
                    "rcp2/source", "owner", "tick", "{}").blueId();
        });
        fixture.coverage = (timelines, boundary, inclusive) -> TimelineHistoryCoverage.Evidence.complete("all-through-101");
        var request = fixture.transact(scope -> {
            var b = scope.documentHandle(ids.get(1)).orElseThrow();
            var selected = scope.coordination().advanced().sourceHistoryRequests(b).get(0);
            assertEquals(SourceHistoryPrerequisite.Kind.LIVE, selected.prerequisite().kind());
            assertEquals(original, selected.prerequisite().entryBlueId());
            return selected;
        });
        fixture.transact(scope -> {
            assertEquals(1, scope.coordination().advanced().selectSourceHistoryStage(request).execute()
                    .result().processing().orElseThrow().committedProcessTransitions());
            return null;
        });
        fixture.transact(scope -> {
            var b = scope.documentHandle(ids.get(1)).orElseThrow();
            assertEquals(ProcessingStageResult.Disposition.COMPLETED,
                    scope.coordination().processing().selectStage(b, fixture.account.previous.get("rcp2/parent")).execute().disposition());
            assertEquals(1, scope.documentHandle(ids.get(0)).orElseThrow().snapshot().longAt("/counter"));
            return null;
        });
    }

    @Test void finiteRootSelectionRequiresInclusiveExactBoundaryAndUnboundedSelectionFailsClosed() throws Exception {
        // given
        var fixture = new Fixture();
        String source = RootedSdkFixture.resource("source.yaml");
        var id = fixture.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            var root = scope.coordination().documents().admitStaticProcessEmbedded(source,
                    ActivationPolicy.importFullHistory()).document("root");
            fixture.account.append(scope, root, "rcp2/source", "owner", "tick", "{}");
            return root.id();
        });
        // when
        var calls = new ArrayList<String>();
        fixture.coverage = (timelines, boundary, inclusive) -> {
            assertEquals(Set.of("rcp2/source"), timelines);
            calls.add(boundary == null ? "unbounded" : boundary.components().get(0).toString() + ":" + inclusive);
            return TimelineHistoryCoverage.Evidence.unavailable("exclusive-T-90", "Timestamp ties at 90 are not complete");
        };
        try (var attempt = fixture.account.records.attempt(); var scope = fixture.open(attempt)) {
            var root = scope.documentHandle(id).orElseThrow();
            assertThrows(RuntimeException.class, () -> scope.coordination().processing()
                    .selectStage(root, fixture.account.previous.get("rcp2/source")));
            assertThrows(RuntimeException.class, scope::stage);
            assertThrows(IllegalStateException.class, () -> attempt.prepare("uncovered", List.of(),
                    LogicalInstanceHistoryTest.Account.EVIDENCE));
        }
        // then
        assertEquals(List.of("90:true"), calls);
        fixture.coverage = (timelines, boundary, inclusive) -> boundary == null
                ? TimelineHistoryCoverage.Evidence.unavailable("finite-T-91", "Unbounded history is not established")
                : TimelineHistoryCoverage.Evidence.complete("complete-original-prefix-before-91");
        try (var attempt = fixture.account.records.attempt(); var scope = fixture.open(attempt)) {
            assertThrows(RuntimeException.class, () -> scope.coordination().processing()
                    .selectNextStage(scope.documentHandle(id).orElseThrow()));
        }
        fixture.transact(scope -> {
            var root = scope.documentHandle(id).orElseThrow();
            assertEquals(0, root.snapshot().longAt("/counter"));
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, scope.coordination().processing()
                    .selectStage(root, fixture.account.previous.get("rcp2/source")).execute().disposition());
            assertEquals(1, root.snapshot().longAt("/counter"));
            return null;
        });
    }

    @Test void coverageProviderFailurePoisonsTheAttemptAndCannotCertifyEmptyHistory() throws Exception {
        // given
        var fixture = new Fixture();
        String source = RootedSdkFixture.resource("source.yaml");
        var id = fixture.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            var root = scope.coordination().documents().admitStaticProcessEmbedded(source,
                    ActivationPolicy.importFullHistory()).document("root");
            fixture.account.append(scope, root, "rcp2/source", "owner", "tick", "{}"); return root.id();
        });
        // when
        for (boolean throwsFailure : List.of(false, true)) {
            fixture.coverage = (timelines, boundary, inclusive) -> {
                if (throwsFailure) throw new IllegalStateException("provider access failed");
                return TimelineHistoryCoverage.Evidence.invalid("bad-proof", "Provider evidence is corrupt");
            };
            try (var attempt = fixture.account.records.attempt(); var scope = fixture.open(attempt)) {
                // then
                assertThrows(RuntimeException.class, () -> scope.coordination().processing().selectStage(
                        scope.documentHandle(id).orElseThrow(), fixture.account.previous.get("rcp2/source")));
                assertThrows(RuntimeException.class, scope::stage);
                assertThrows(IllegalStateException.class, () -> attempt.prepare("bad-proof", List.of(),
                        LogicalInstanceHistoryTest.Account.EVIDENCE));
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,false", "20,true", "30,true", "30,false", "40,false"})
    void completeEnumerationPreservesEmptyAndBothFullOrderTimestampTieSides(long timestamp, boolean before) throws Exception {
        var fixture = new Fixture();
        String sourceTimeline;
        try (var blue = BlueCoordination.inMemory()) {
            String parentExact = blue.values().yaml("type: MyOS/MyOS Timeline\ntimelineId: rcp2/parent").blueId();
            sourceTimeline = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "finite/source-" + i)
                    .filter(id -> (blue.values().yaml("type: MyOS/MyOS Timeline\ntimelineId: " + id).blueId()
                            .compareTo(parentExact) < 0) == before).findFirst().orElseThrow();
        }
        String source = GeneralTimelineScenario.document(sourceTimeline), parent = RootedSdkFixture.resource("parent.yaml");
        var ids = fixture.transact(scope -> {
            var blue = scope.coordination();
            blue.timelines().register(sourceTimeline, "alice"); blue.timelines().register("rcp2/parent", "alice");
            var value = blue.values().yaml(source); fixture.account.exact.put(value.blueId(), value.json());
            var a = blue.documents().admitStaticProcessEmbedded(source, ActivationPolicy.importFullHistory()).document("root");
            var b = blue.documents().admitStaticProcessEmbedded(parent, ActivationPolicy.importFullHistory()).document("root");
            if (timestamp > 0) blue.events().from(scope.timelineHandle(sourceTimeline).orElseThrow())
                    .exact(GeneralTimelineScenario.event(blue, sourceTimeline, timestamp, 7)).submit();
            fixture.account.timestamp = 30;
            fixture.account.append(scope, b, "rcp2/parent", "owner", "attach", "child: {blueId: " + value.blueId() + "}");
            return List.of(a.id(), b.id());
        });
        var requests = new ArrayList<Boolean>();
        fixture.coverage = (timelines, boundary, inclusive) -> {
            assertNotNull(boundary); assertEquals("30", boundary.components().get(0).toString());
            if (timelines.contains(sourceTimeline)) { assertFalse(inclusive); requests.add(inclusive); }
            return TimelineHistoryCoverage.Evidence.complete("verified-entire-prefix-before-31");
        };
        fixture.transact(scope -> {
            var b = scope.documentHandle(ids.get(1)).orElseThrow();
            var result = scope.coordination().processing().selectStage(b, fixture.account.previous.get("rcp2/parent")).execute();
            assertEquals(before ? ProcessingStageResult.Disposition.WAITING : ProcessingStageResult.Disposition.COMPLETED,
                    result.disposition());
            assertEquals(0, scope.documentHandle(ids.get(0)).orElseThrow().snapshot().longAt("/counter"));
            assertFalse(requests.isEmpty(), "Native source discovery must demand the external exclusive prefix");
            return null;
        });
    }

    static final class Fixture {
        final LogicalInstanceHistoryTest.Account account = new LogicalInstanceHistoryTest.Account();
        TimelineHistoryCoverage coverage = (timelines, boundary, inclusive) -> TimelineHistoryCoverage.Evidence.complete("closed-fixture");
        RootedCoordinationStorage.LogicalScope open(blue.coordination.api.storage.CoordinationRecordAttempt attempt) {
            var pinned = coverage;
            return RootedCoordinationStorage.openLogicalWithHistoryCoverage(account.objects, account.limits,
                    account.configuration, attempt, id -> Optional.ofNullable(account.exact.get(id)), pinned);
        }
        <T> T transact(Function<RootedCoordinationStorage.LogicalScope, T> action) {
            try (var attempt = account.records.attempt(); var scope = open(attempt)) {
                T value = action.apply(scope); scope.stage();
                assertTrue(account.records.publish(attempt.prepare("finite-" + ++account.command,
                        List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE)));
                return value;
            }
        }
    }
}
