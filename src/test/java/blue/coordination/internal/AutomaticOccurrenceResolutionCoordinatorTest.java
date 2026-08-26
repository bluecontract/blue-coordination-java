package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ExactNodeDemand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AutomaticOccurrenceResolutionCoordinatorTest {
    private static final DocumentId ROOT = DocumentId.of(
            "automatic-coordinator-root");

    @Test
    void stopsOnlyAfterTheSameFullProgressIdentityRepeats() {
        try (DefaultCoordinationEngine engine = contractsEngine()) {
            // given
            FakeInvocation invocation = invocation(engine, "repeat");
            ClosureAttemptResult needs = needs(engine);
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger fences = new AtomicInteger();
            AutomaticOccurrenceResolutionCoordinator<FakeInvocation>
                    coordinator = coordinator(
                            engine,
                            ignored -> {
                                attempts.incrementAndGet();
                                return needs;
                            },
                            (current, resolution, storeState) -> current);

            // when
            AutomaticOccurrenceResolutionCoordinator.RunResult<
                    FakeInvocation, String> result = coordinator.run(
                            invocation,
                            4L,
                            ignored -> Optional.empty(),
                            (before, after, storeState) ->
                                    fences.incrementAndGet());

            // then
            assertFalse(result.replayed());
            assertFalse(result.attempt().isComplete());
            assertEquals(2, attempts.get());
            assertEquals(1, fences.get());
            assertEquals(1L, result.expansionCount());
            assertEquals(1L, engine.engineMetrics().counter(
                    AutomaticOccurrenceResolutionCoordinator
                            .REPEATED_DEMAND_STOPS));
        }
    }

    @Test
    void sameDemandShapeMayAdvanceAcrossDistinctInvocationIdentities() {
        try (DefaultCoordinationEngine engine = contractsEngine()) {
            // given
            FakeInvocation first = invocation(engine, "first");
            FakeInvocation second = invocation(engine, "second");
            FakeInvocation third = invocation(engine, "third");
            ClosureAttemptResult needs = needs(engine);
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger expansions = new AtomicInteger();
            AutomaticOccurrenceResolutionCoordinator<FakeInvocation>
                    coordinator = coordinator(
                            engine,
                            ignored -> {
                                attempts.incrementAndGet();
                                return needs;
                            },
                            (current, resolution, storeState) -> switch (
                                    expansions.getAndIncrement()) {
                                case 0 -> second;
                                case 1 -> third;
                                default -> throw new AssertionError(
                                        "unexpected expansion");
                            });

            // when
            AutomaticOccurrenceResolutionCoordinator.RunResult<
                    FakeInvocation, String> result = coordinator.run(
                            first,
                            4L,
                            current -> current == third
                                    ? Optional.of("durable-receipt")
                                    : Optional.empty(),
                            (before, after, storeState) -> { });

            // then
            assertTrue(result.replayed());
            assertEquals("durable-receipt", result.replay());
            assertEquals(2, attempts.get());
            assertEquals(2, expansions.get());
            assertEquals(2L, result.expansionCount());
            assertEquals(0L, engine.engineMetrics().counter(
                    AutomaticOccurrenceResolutionCoordinator
                            .REPEATED_DEMAND_STOPS));
        }
    }

    @Test
    void portableExpansionLimitStopsBeforeAnotherResolution() {
        try (DefaultCoordinationEngine engine = contractsEngine()) {
            // given
            FakeInvocation first = invocation(engine, "limit-first");
            FakeInvocation second = invocation(engine, "limit-second");
            ClosureAttemptResult needs = needs(engine);
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger expansions = new AtomicInteger();
            AutomaticOccurrenceResolutionCoordinator<FakeInvocation>
                    coordinator = coordinator(
                            engine,
                            ignored -> {
                                attempts.incrementAndGet();
                                return needs;
                            },
                            (current, resolution, storeState) -> {
                                expansions.incrementAndGet();
                                return second;
                            });

            // when
            AutomaticOccurrenceResolutionCoordinator.RunResult<
                    FakeInvocation, String> result = coordinator.run(
                            first,
                            1L,
                            ignored -> Optional.empty(),
                            (before, after, storeState) -> { });

            // then
            assertFalse(result.replayed());
            assertEquals(2, attempts.get());
            assertEquals(1, expansions.get());
            assertEquals(1L, result.expansionCount());
            assertEquals(1L, engine.engineMetrics().counter(
                    AutomaticOccurrenceResolutionCoordinator.LIMIT_STOPS));
        }
    }

    private static AutomaticOccurrenceResolutionCoordinator<FakeInvocation>
            coordinator(
                    DefaultCoordinationEngine engine,
                    AutomaticOccurrenceResolutionCoordinator.AttemptRunner<
                            FakeInvocation> attempts,
                    ExpansionFunction expansions) {
        return new AutomaticOccurrenceResolutionCoordinator<>(
                new ManagedOccurrenceResolver(
                        engine.objects(), engine.engineMetrics()),
                engine.engineMetrics(),
                FakeInvocation::input,
                attempts,
                new AutomaticOccurrenceResolutionCoordinator
                        .ExpansionBuilder<>() {
                    @Override
                    public InMemoryDocumentStore.OccurrenceResolutionSnapshot
                            captureStoreState() {
                        return engine.documents()
                                .occurrenceResolutionSnapshot();
                    }

                    @Override
                    public FakeInvocation expand(
                            FakeInvocation current,
                            ManagedOccurrenceResolver.Resolution resolution,
                            InMemoryDocumentStore.OccurrenceResolutionSnapshot
                                    storeState) {
                        return expansions.expand(
                                current, resolution, storeState);
                    }
                });
    }

    private static ClosureAttemptResult needs(
            DefaultCoordinationEngine engine) {
        ExactValue resource = engine.exactValue("value: retry-resource");
        return ClosureAttemptResult.needsResources(List.of(
                ExactNodeDemand.derived(
                        resource.blueId(), closureId(ROOT), "/resource")));
    }

    private static FakeInvocation invocation(
            DefaultCoordinationEngine engine,
            String label) {
        ClosureInvocationInput input = new Contracts10ScenarioBuilder(engine)
                .document(ROOT, """
                        documentId: automatic-coordinator-root
                        state: initial
                        """)
                .publicRoot(ROOT)
                .expectedComponent(ROOT)
                .admissionLabel("automatic-coordinator-" + label)
                .admission();
        return new FakeInvocation(input);
    }

    private static DefaultCoordinationEngine contractsEngine() {
        BundledContracts10Release.Manifest release =
                BundledContracts10Release.manifest();
        return DefaultCoordinationEngine.createContracts10Sdk(
                release.blueLanguageSpecification(),
                release.contractsSpecification());
    }

    private static blue.language.processor.closure.DocumentId closureId(
            DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    private record FakeInvocation(ClosureInvocationInput input) {
    }

    @FunctionalInterface
    private interface ExpansionFunction {
        FakeInvocation expand(
                FakeInvocation current,
                ManagedOccurrenceResolver.Resolution resolution,
                InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState);
    }
}
