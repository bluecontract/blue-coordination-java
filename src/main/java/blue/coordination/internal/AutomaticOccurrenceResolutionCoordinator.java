package blue.coordination.internal;

import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureResourceDemand;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Runs one bounded noncommitting Contracts resource-resolution loop. */
final class AutomaticOccurrenceResolutionCoordinator<I> {
    static final String RETRIES =
            "contracts.occurrenceResolver.retries";
    static final String ATTEMPTS =
            "contracts.occurrenceResolver.attempts";
    static final String TYPED_DEMANDS =
            "contracts.occurrenceResolver.typedDemands";
    static final String REPEATED_DEMAND_STOPS =
            "contracts.occurrenceResolver.repeatedDemandStops";
    static final String LIMIT_STOPS =
            "contracts.occurrenceResolver.limitStops";

    private final ManagedOccurrenceResolver resolver;
    private final EngineMetrics metrics;
    private final InputView<I> inputs;
    private final AttemptRunner<I> attempts;
    private final ExpansionBuilder<I> expansions;

    AutomaticOccurrenceResolutionCoordinator(
            ManagedOccurrenceResolver resolver,
            EngineMetrics metrics,
            InputView<I> inputs,
            AttemptRunner<I> attempts,
            ExpansionBuilder<I> expansions) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.expansions = Objects.requireNonNull(expansions, "expansions");
    }

    <R> RunResult<I, R> run(
            I initial,
            long maximumExpansions,
            ReplayLookup<I, R> replays,
            RetryFence<I> retryFence) {
        return run(
                initial,
                maximumExpansions,
                replays,
                retryFence,
                null);
    }

    <R> RunResult<I, R> run(
            I initial,
            long maximumExpansions,
            ReplayLookup<I, R> replays,
            RetryFence<I> retryFence,
            ContractsManagedEpochSelectionPlan selectionPlan) {
        I current = Objects.requireNonNull(initial, "initial");
        ReplayLookup<I, R> replayLookup = Objects.requireNonNull(
                replays, "replays");
        RetryFence<I> selectedFence = Objects.requireNonNull(
                retryFence, "retryFence");
        MultiDocumentPublicationTransaction.requireSafeInteger(
                maximumExpansions, "maximumExpansions");
        if (maximumExpansions == 0L) {
            throw new IllegalArgumentException(
                    "maximumExpansions must be positive");
        }
        Set<ProgressIdentity> attemptedProgress = new LinkedHashSet<>();
        Set<String> resolvedSelectorPaths = new LinkedHashSet<>();
        long expansionCount = 0L;
        while (true) {
            Optional<R> replay = Objects.requireNonNull(
                    replayLookup.find(current), "replay");
            if (replay.isPresent()) {
                return RunResult.replayed(
                        current, replay.orElseThrow(), expansionCount);
            }
            metrics.increment(ATTEMPTS);
            ClosureAttemptResult attempt = attempts.run(current);
            if (attempt.isComplete()) {
                if (selectionPlan != null) {
                    selectionPlan.requireEveryPathResolved(
                            resolvedSelectorPaths);
                }
                return RunResult.executed(
                        current, attempt, expansionCount);
            }
            metrics.add(TYPED_DEMANDS, attempt.resourceDemands().size());
            List<String> demandVector = demandVector(
                    attempt.resourceDemands());
            if (expansionCount >= maximumExpansions) {
                metrics.increment(LIMIT_STOPS);
                return RunResult.stopped(
                        current,
                        attempt,
                        expansionCount,
                        StopReason.EXPANSION_LIMIT,
                        List.of());
            }

            InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState =
                    expansions.captureStoreState();
            ManagedOccurrenceResolver.Resolution resolution = resolver.resolve(
                    ManagedOccurrenceResolver.ResolutionRequest.from(
                            inputs.input(current),
                            attempt.resourceDemands(),
                            storeState,
                            selectionPlan));
            resolution = expansions.requirePrerequisites(current, resolution);
            resolvedSelectorPaths.addAll(
                    resolution.resolvedSelectorPaths());
            if (!resolution.complete()) {
                return RunResult.stopped(
                        current,
                        attempt,
                        expansionCount,
                        StopReason.UNRESOLVED_DEMANDS,
                        resolution.unresolvedDemands());
            }
            I expanded =
                    expansions.expand(current, resolution, storeState);
            ProgressIdentity progress = new ProgressIdentity(
                    inputs.invocationIdentity(current),
                    demandVector,
                    inputs.invocationIdentity(expanded));
            if (!attemptedProgress.add(progress)) {
                metrics.increment(REPEATED_DEMAND_STOPS);
                return RunResult.stopped(
                        current,
                        attempt,
                        expansionCount,
                        StopReason.REPEATED_PROGRESS,
                        List.of());
            }
            selectedFence.verify(current, expanded, storeState);
            current = expanded;
            expansionCount = Math.addExact(expansionCount, 1L);
            metrics.increment(RETRIES);
        }
    }

    private record ProgressIdentity(
            String currentInvocationIdentity,
            List<String> demandIdentities,
            String nextInvocationIdentity) {
        private ProgressIdentity {
            currentInvocationIdentity = Objects.requireNonNull(
                    currentInvocationIdentity, "currentInvocationIdentity");
            demandIdentities = List.copyOf(Objects.requireNonNull(
                    demandIdentities, "demandIdentities"));
            nextInvocationIdentity = Objects.requireNonNull(
                    nextInvocationIdentity, "nextInvocationIdentity");
        }
    }

    private static List<String> demandVector(
            List<ClosureResourceDemand> demands) {
        ArrayList<String> identities = new ArrayList<>(Objects.requireNonNull(
                demands, "demands").size());
        for (ClosureResourceDemand demand : demands) {
            identities.add(Objects.requireNonNull(
                    demand, "demand").demandIdentity());
        }
        if (identities.isEmpty()) {
            throw new IllegalArgumentException(
                    "A suspended attempt must expose typed resource demands");
        }
        return List.copyOf(identities);
    }

    @FunctionalInterface
    interface InputView<I> {
        blue.language.processor.closure.ClosureInvocationInput input(
                I invocation);

        default String invocationIdentity(I invocation) {
            return input(invocation).invocationIdentity();
        }
    }

    @FunctionalInterface
    interface AttemptRunner<I> {
        ClosureAttemptResult run(I invocation);
    }

    interface ExpansionBuilder<I> {
        InMemoryDocumentStore.OccurrenceResolutionSnapshot captureStoreState();

        default ManagedOccurrenceResolver.Resolution requirePrerequisites(I current,
                ManagedOccurrenceResolver.Resolution resolution) {
            return resolution;
        }

        I expand(
                I current,
                ManagedOccurrenceResolver.Resolution resolution,
                InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState);
    }

    @FunctionalInterface
    interface ReplayLookup<I, R> {
        Optional<R> find(I invocation);
    }

    @FunctionalInterface
    interface RetryFence<I> {
        void verify(
                I before,
                I expanded,
                InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState);
    }

    /** Closed reason why automatic resolution returned a suspended attempt. */
    enum StopReason {
        /** One or more demands could not be matched to exact managed evidence. */
        UNRESOLVED_DEMANDS,
        /** The admitted portable expansion bound was reached. */
        EXPANSION_LIMIT,
        /** The same complete resolution progress identity repeated. */
        REPEATED_PROGRESS
    }

    record RunResult<I, R>(
            I invocation,
            ClosureAttemptResult attempt,
            R replay,
            long expansionCount,
            Optional<StopReason> automaticResolutionStopReason,
            List<ManagedOccurrenceResolver.UnresolvedDemand>
                    unresolvedDemands) {
        RunResult {
            invocation = Objects.requireNonNull(invocation, "invocation");
            if ((attempt == null) == (replay == null)) {
                throw new IllegalArgumentException(
                        "A resolution run must execute or replay exactly once");
            }
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    expansionCount, "expansionCount");
            automaticResolutionStopReason = Objects.requireNonNull(
                    automaticResolutionStopReason,
                    "automaticResolutionStopReason");
            unresolvedDemands = List.copyOf(Objects.requireNonNull(
                    unresolvedDemands, "unresolvedDemands"));
            if ((attempt == null || attempt.isComplete())
                    && !unresolvedDemands.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only a suspended execution may expose unresolved "
                                + "managed occurrence evidence");
            }
            if (attempt == null || attempt.isComplete()) {
                if (automaticResolutionStopReason.isPresent()) {
                    throw new IllegalArgumentException(
                            "Only a suspended execution may expose an "
                                    + "automatic resolution stop reason");
                }
            } else if (automaticResolutionStopReason.isEmpty()) {
                throw new IllegalArgumentException(
                        "A suspended execution requires an automatic "
                                + "resolution stop reason");
            }
            boolean unresolvedStop = automaticResolutionStopReason
                    .filter(reason -> reason == StopReason.UNRESOLVED_DEMANDS)
                    .isPresent();
            if (unresolvedStop != !unresolvedDemands.isEmpty()) {
                throw new IllegalArgumentException(
                        "UNRESOLVED_DEMANDS must identify at least one exact "
                                + "managed occurrence issue");
            }
        }

        static <I, R> RunResult<I, R> executed(
                I invocation,
                ClosureAttemptResult attempt,
                long expansionCount) {
            return new RunResult<>(
                    invocation,
                    Objects.requireNonNull(attempt, "attempt"),
                    null,
                    expansionCount,
                    Optional.empty(),
                    List.of());
        }

        static <I, R> RunResult<I, R> stopped(
                I invocation,
                ClosureAttemptResult attempt,
                long expansionCount,
                StopReason stopReason,
                List<ManagedOccurrenceResolver.UnresolvedDemand>
                        unresolvedDemands) {
            return new RunResult<>(
                    invocation,
                    Objects.requireNonNull(attempt, "attempt"),
                    null,
                    expansionCount,
                    Optional.of(Objects.requireNonNull(
                            stopReason, "stopReason")),
                    unresolvedDemands);
        }

        static <I, R> RunResult<I, R> replayed(
                I invocation,
                R replay,
                long expansionCount) {
            return new RunResult<>(
                    invocation,
                    null,
                    Objects.requireNonNull(replay, "replay"),
                    expansionCount,
                    Optional.empty(),
                    List.of());
        }

        boolean replayed() {
            return replay != null;
        }
    }
}
