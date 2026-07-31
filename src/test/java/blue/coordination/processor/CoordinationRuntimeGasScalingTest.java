package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.CoordinationAggregateGasHarness;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasTraceEntry;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class CoordinationRuntimeGasScalingTest {

    @Test
    void shouldReuseOneLedgerAcrossMoreThan128CompositeMembers() {
        // Given
        int memberCount = 129;
        GasMeter parent = new GasMeter();
        ExternalChannelFunctionContext context =
                CoordinationAggregateGasHarness
                        .rejectingTimelineMembers(
                                parent,
                                memberCount,
                                128);
        CompositeTimelineChannel composite =
                new CompositeTimelineChannel()
                        .channels(memberKeys(
                                memberCount));
        Node event = new Node().value(
                "rejected-by-every-member");

        // When
        boolean accepted =
                CompositeTimelineExternalSubscriptionFunctions
                        .INSTANCE
                        .accepts(
                                composite,
                                event,
                                context);
        List<GasTraceEntry> staged =
                context.runtimeWorkSession()
                        .stagedTrace();
        CoordinationAggregateGasHarness.complete(
                context);

        // Then
        assertFalse(accepted);
        assertEquals(
                memberCount * 2,
                staged.size());
        for (int member = 0;
             member < memberCount;
             member++) {
            GasTraceEntry visit =
                    staged.get(member * 2);
            GasTraceEntry header =
                    staged.get(member * 2 + 1);
            assertEquals(
                    "coordination.00000000",
                    visit.namespace());
            assertEquals(
                    visit.namespace(),
                    header.namespace());
            assertEquals(
                    "compositeMemberVisited",
                    visit.counter());
            assertEquals(
                    1L,
                    visit.quantity());
            assertEquals(
                    "aggregate",
                    visit.contractKey());
            assertEquals(
                    "timelineHeaderRead",
                    header.counter());
            assertEquals(
                    1L,
                    header.quantity());
            assertEquals(
                    String.format(
                            java.util.Locale.ROOT,
                            "timeline-%04d",
                            Integer.valueOf(member)),
                    header.contractKey());
        }
        assertEquals(
                staged.size(),
                parent.trace().size());
    }

    @Test
    void shouldPreserveOriginalFailureFromNestedComponentCharge() {
        // Given
        GasMeter parent = new GasMeter();
        ExternalChannelFunctionContext context =
                CoordinationAggregateGasHarness
                        .rejectingTimelineMembers(
                                parent,
                                0,
                                0);

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationRuntimeGas.inComponent(
                                context.runtimeWorkSession(),
                                () -> {
                                    CoordinationRuntimeGas.charge(
                                            context
                                                    .runtimeWorkSession(),
                                            "timelineHeaderRead",
                                            1L,
                                            null);
                                    CoordinationRuntimeGas.charge(
                                            context
                                                    .runtimeWorkSession(),
                                            "not-a-counter",
                                            1L,
                                            null);
                                    return null;
                                }));
        CoordinationAggregateGasHarness
                .failDeterministically(context);

        // Then
        assertTrue(
                failure.getMessage().contains(
                        "Unknown Coordination gas counter "
                                + "not-a-counter"));
        assertEquals(
                1,
                parent.trace().size());
        assertEquals(
                "timelineHeaderRead",
                parent.trace().get(0).counter());
    }

    @Test
    void shouldEvictAbandonedLedgerBeforeReacquiringAfterCaughtNestedFailure() {
        // Given
        GasMeter parent = new GasMeter();
        ExternalChannelFunctionContext context =
                CoordinationAggregateGasHarness
                        .rejectingTimelineMembers(
                                parent,
                                0,
                                0);
        AtomicReference<IllegalArgumentException> nestedFailure =
                new AtomicReference<IllegalArgumentException>();

        // When
        IllegalStateException abandoned =
                assertThrows(
                        IllegalStateException.class,
                        () -> CoordinationRuntimeGas.inComponent(
                                context.runtimeWorkSession(),
                                () -> {
                                    try {
                                        CoordinationRuntimeGas.inComponent(
                                                context.runtimeWorkSession(),
                                                () -> {
                                                    CoordinationRuntimeGas.charge(
                                                            context.runtimeWorkSession(),
                                                            "timelineHeaderRead",
                                                            1L,
                                                            null);
                                                    throw new IllegalArgumentException(
                                                            "nested failure");
                                                });
                                    } catch (IllegalArgumentException failure) {
                                        nestedFailure.set(failure);
                                    }
                                    return null;
                                }));
        CoordinationRuntimeGas.charge(
                context.runtimeWorkSession(),
                "timelineBindingCompared",
                1L,
                null);
        List<GasTraceEntry> staged =
                context.runtimeWorkSession().stagedTrace();
        CoordinationAggregateGasHarness
                .failDeterministically(context);

        // Then
        assertEquals(
                "nested failure",
                nestedFailure.get().getMessage());
        assertTrue(
                abandoned.getMessage().contains(
                        "abandoned by nested work"));
        assertEquals(2, staged.size());
        assertEquals(
                "coordination.00000000",
                staged.get(0).namespace());
        assertEquals(
                "timelineHeaderRead",
                staged.get(0).counter());
        assertEquals(
                "coordination.00000001",
                staged.get(1).namespace());
        assertEquals(
                "timelineBindingCompared",
                staged.get(1).counter());
        assertEquals(staged.size(), parent.trace().size());
    }

    @Test
    void shouldRejectOverlappingIndependentLedgerOwnership()
            throws Exception {
        // Given
        GasMeter parent = new GasMeter();
        ExternalChannelFunctionContext context =
                CoordinationAggregateGasHarness
                        .rejectingTimelineMembers(
                                parent,
                                0,
                                0);
        CoordinationRuntimeGas.Ledger owner =
                CoordinationRuntimeGas.open(
                        context.runtimeWorkSession());
        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        // When
        Throwable overlap;
        try {
            Future<Throwable> attempted =
                    executor.submit(
                            () -> {
                                try {
                                    CoordinationRuntimeGas.open(
                                            context.runtimeWorkSession());
                                    return null;
                                } catch (RuntimeException | Error failure) {
                                    return failure;
                                }
                            });
            overlap = attempted.get(
                    5L,
                    TimeUnit.SECONDS);
            owner.charge(
                    "timelineHeaderRead",
                    1L,
                    null);
            owner.submit();
        } finally {
            executor.shutdownNow();
        }
        List<GasTraceEntry> staged =
                context.runtimeWorkSession().stagedTrace();
        CoordinationAggregateGasHarness.complete(
                context);

        // Then
        assertTrue(
                overlap instanceof IllegalStateException,
                String.valueOf(overlap));
        assertTrue(
                overlap.getMessage().contains(
                        "Concurrent Coordination runtime ledger ownership"));
        assertEquals(1, staged.size());
        assertEquals(
                "coordination.00000000",
                staged.get(0).namespace());
        assertEquals(
                "timelineHeaderRead",
                staged.get(0).counter());
        assertEquals(staged.size(), parent.trace().size());
    }

    @Test
    void shouldRetainTheFullTraceForA129MemberCompositeScan() {
        // Given
        int memberCount = 129;
        int expectedTraceEntries =
                memberCount * 4;
        GasMeter parent = new GasMeter();
        ExternalChannelFunctionContext context =
                CoordinationAggregateGasHarness
                        .fullyEvaluatedRejectingTimelineMembers(
                                parent,
                                memberCount);
        CompositeTimelineChannel composite =
                new CompositeTimelineChannel()
                        .channels(memberKeys(
                                memberCount));
        Node event = new Node().value(
                "fully-evaluated-and-rejected-by-every-member");
        boolean accepted = false;
        Throwable failure = null;

        // When
        try {
            accepted =
                    CompositeTimelineExternalSubscriptionFunctions
                            .INSTANCE
                            .accepts(
                                    composite,
                                    event,
                                    context);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        List<GasTraceEntry> staged =
                context.runtimeWorkSession()
                        .stagedTrace();
        if (failure == null) {
            CoordinationAggregateGasHarness.complete(
                    context);
        } else {
            CoordinationAggregateGasHarness
                    .failDeterministically(context);
        }

        // Then
        if (failure != null) {
            fail(
                    "A 129-member Composite scan requires "
                            + expectedTraceEntries
                            + " exact ordered entries, but the local "
                            + "Language runtime stopped after "
                            + staged.size(),
                    failure);
        }
        assertFalse(accepted);
        assertEquals(
                expectedTraceEntries,
                staged.size());
        System.out.println(
                "coordination.maximumRuntimeTraceEntriesObserved="
                        + staged.size());
        assertEquals(
                staged.size(),
                parent.trace().size());
        for (int member = 0;
             member < memberCount;
             member++) {
            int visitIndex = member * 4;
            GasTraceEntry visit =
                    staged.get(visitIndex);
            GasTraceEntry header =
                    staged.get(visitIndex + 1);
            GasTraceEntry timelineBinding =
                    staged.get(visitIndex + 2);
            GasTraceEntry actorBinding =
                    staged.get(visitIndex + 3);
            assertEquals(
                    "compositeMemberVisited",
                    visit.counter());
            assertEquals(
                    "timelineHeaderRead",
                    header.counter());
            assertEquals(
                    "timelineBindingCompared",
                    timelineBinding.counter());
            assertEquals(
                    "timelineBindingCompared",
                    actorBinding.counter());
            assertEquals(
                    1L,
                    visit.quantity());
            assertEquals(
                    1L,
                    timelineBinding.quantity());
            assertEquals(
                    1L,
                    actorBinding.quantity());
            assertEquals(
                    header.contractKey(),
                    timelineBinding.contractKey());
            assertEquals(
                    header.contractKey(),
                    actorBinding.contractKey());
        }
    }

    @Test
    void shouldAcceptCompositeAtExactOneMemberVisitBudget() {
        // Given
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(),
                2L);
        ExternalChannelFunctionContext context =
                CoordinationAggregateGasHarness
                        .acceptingTimelineMembers(
                                parent,
                                3,
                                0);
        CompositeTimelineChannel composite =
                new CompositeTimelineChannel()
                        .channels(memberKeys(3));
        Node event = new Node().value(
                "accepted-by-first-member");

        // When
        boolean accepted =
                CompositeTimelineExternalSubscriptionFunctions
                        .INSTANCE
                        .accepts(
                                composite,
                                event,
                                context);
        List<GasTraceEntry> staged =
                context.runtimeWorkSession()
                        .stagedTrace();
        CoordinationAggregateGasHarness.complete(
                context);

        // Then
        assertTrue(accepted);
        assertEquals(1, staged.size());
        assertEquals(
                "compositeMemberVisited",
                staged.get(0).counter());
        assertEquals(1L, staged.get(0).quantity());
        assertEquals(2L, parent.totalGas());
    }

    @Test
    void shouldAcceptAllTimelinesAtExactOneMemberVisitBudget() {
        // Given
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(),
                2L);
        ExternalChannelFunctionContext context =
                CoordinationAggregateGasHarness
                        .acceptingTimelineMembers(
                                parent,
                                3,
                                0);
        AllTimelinesChannel allTimelines =
                new AllTimelinesChannel();
        Node event = new Node().value(
                "accepted-by-first-member");

        // When
        boolean accepted =
                AllTimelinesExternalSubscriptionFunctions
                        .INSTANCE
                        .accepts(
                                allTimelines,
                                event,
                                context);
        List<GasTraceEntry> staged =
                context.runtimeWorkSession()
                        .stagedTrace();
        CoordinationAggregateGasHarness.complete(
                context);

        // Then
        assertTrue(accepted);
        assertEquals(1, staged.size());
        assertEquals(
                "allTimelinesMemberVisited",
                staged.get(0).counter());
        assertEquals(1L, staged.get(0).quantity());
        assertEquals(2L, parent.totalGas());
    }

    @Test
    void shouldRejectCompositeMemberVisitBeforeAnyMemberResolution() {
        // Given
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(),
                0L);
        CoordinationAggregateGasHarness.MemberResolutionProbe probe =
                new CoordinationAggregateGasHarness
                        .MemberResolutionProbe();
        ExternalChannelFunctionContext context =
                CoordinationAggregateGasHarness
                        .observedAcceptingTimelineMembers(
                                parent,
                                3,
                                0,
                                probe);
        CompositeTimelineChannel composite =
                new CompositeTimelineChannel()
                        .channels(memberKeys(3));
        Node event = new Node().value(
                "must-not-reach-member");

        // When
        GasLimitExceededException failure =
                assertThrows(
                        GasLimitExceededException.class,
                        () -> CompositeTimelineExternalSubscriptionFunctions
                                .INSTANCE
                                .accepts(
                                        composite,
                                        event,
                                        context));
        GasLimitExceededException propagated =
                assertThrows(
                        GasLimitExceededException.class,
                        () -> CoordinationAggregateGasHarness
                                .failDeterministically(context));

        // Then
        assertEquals(
                "compositeMemberVisited",
                failure.counter());
        assertSame(failure, propagated);
        assertEquals(1L, failure.quantity());
        assertEquals(1, probe.shallowTypeFamilyQueries());
        assertEquals(0, probe.directMemberLookups());
        assertEquals(0, probe.memberEvaluations());
        assertTrue(parent.trace().isEmpty());
    }

    @Test
    void shouldRejectAllTimelinesMemberVisitBeforeAnyMemberResolution() {
        // Given
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(),
                0L);
        CoordinationAggregateGasHarness.MemberResolutionProbe probe =
                new CoordinationAggregateGasHarness
                        .MemberResolutionProbe();
        ExternalChannelFunctionContext context =
                CoordinationAggregateGasHarness
                        .observedAcceptingTimelineMembers(
                                parent,
                                3,
                                0,
                                probe);
        AllTimelinesChannel allTimelines =
                new AllTimelinesChannel();
        Node event = new Node().value(
                "must-not-reach-member");

        // When
        GasLimitExceededException failure =
                assertThrows(
                        GasLimitExceededException.class,
                        () -> AllTimelinesExternalSubscriptionFunctions
                                .INSTANCE
                                .accepts(
                                        allTimelines,
                                        event,
                                        context));
        GasLimitExceededException propagated =
                assertThrows(
                        GasLimitExceededException.class,
                        () -> CoordinationAggregateGasHarness
                                .failDeterministically(context));

        // Then
        assertEquals(
                "allTimelinesMemberVisited",
                failure.counter());
        assertSame(failure, propagated);
        assertEquals(1L, failure.quantity());
        assertEquals(1, probe.shallowTypeFamilyQueries());
        assertEquals(0, probe.directMemberLookups());
        assertEquals(0, probe.memberEvaluations());
        assertTrue(parent.trace().isEmpty());
    }

    private static List<String> memberKeys(
            int count) {
        List<String> keys =
                new ArrayList<String>(count);
        for (int index = 0; index < count; index++) {
            keys.add(String.format(
                    java.util.Locale.ROOT,
                    "timeline-%04d",
                    Integer.valueOf(index)));
        }
        return keys;
    }
}
