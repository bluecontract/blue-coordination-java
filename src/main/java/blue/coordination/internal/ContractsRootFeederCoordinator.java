package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;
import blue.language.processor.closure.ClosureResourceDemand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Executes eligible closure cohorts under lane-local feeder barriers. */
final class ContractsRootFeederCoordinator {
    private final ContractsClosureAdapter adapter;
    private final ContractsRootFeederWindow window;
    private final AdmissionExecutor executor;
    private final Predicate<ContractsClosureAdapter.CohortInvocation>
            eligibility;

    ContractsRootFeederCoordinator(
            ContractsClosureAdapter adapter,
            ContractsRootFeederWindow window) {
        this(adapter, window, ignored -> true, adapter::prepareAndPublish);
    }

    ContractsRootFeederCoordinator(
            ContractsClosureAdapter adapter,
            ContractsRootFeederWindow window,
            CohortExecutor executor) {
        this(adapter, window, executor, ignored -> true);
    }

    ContractsRootFeederCoordinator(
            ContractsClosureAdapter adapter,
            ContractsRootFeederWindow window,
            CohortExecutor executor,
            Predicate<ContractsClosureAdapter.CohortInvocation> eligibility) {
        this(adapter, window, eligibility, (batch, invocation) ->
                ContractsClosureAdapter.CohortAdmission.admitted(executor.execute(batch, invocation)));
    }

    ContractsRootFeederCoordinator(
            ContractsClosureAdapter adapter,
            ContractsRootFeederWindow window,
            Predicate<ContractsClosureAdapter.CohortInvocation> eligibility,
            AdmissionExecutor executor) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.window = Objects.requireNonNull(window, "window");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.eligibility = Objects.requireNonNull(
                eligibility, "eligibility");
    }

    /** Captures one exact event and executes every currently eligible lane. */
    synchronized EventProgress process(TimelineEntry entry) {
        return process(adapter.capture(Objects.requireNonNull(entry, "entry")));
    }

    /**
     * Executes one already frozen Root event.
     *
     * <p>A NeedsResources outcome is recorded for only its lane. The loop
     * continues with other disconnected lanes selected by the same event.
     * A later event recapture is filtered by the durable window, so it cannot
     * overtake the suspended lane or re-drive a terminal lane.</p>
     */
    synchronized EventProgress process(
            ContractsClosureAdapter.FrozenBatch batch) {
        ContractsClosureAdapter.FrozenBatch frozen = Objects.requireNonNull(
                batch, "batch");
        List<CohortProgress> progress = new ArrayList<>();
        for (ContractsRootFeederWindow.AttemptTicket ticket
                : window.select(frozen)) {
            ContractsClosureAdapter.CohortInvocation invocation =
                    frozen.invocations().get(ticket.cohortIndex());
            if (!ticket.invocationIdentity().equals(
                    invocation.input().invocationIdentity())
                    || !ticket.members().equals(invocation.members())) {
                throw new IllegalStateException(
                        "Feeder ticket no longer identifies its frozen cohort");
            }
            if (!eligibility.test(invocation)) {
                window.releaseUnexecuted(ticket);
                continue;
            }
            var admission = executor.execute(frozen, invocation);
            if (admission.prerequisite() != null) {
                window.releaseUnexecuted(ticket);
                continue;
            }
            ContractsClosureAdapter.CohortOutcome outcome = admission.outcome();
            window.record(ticket, outcome);
            progress.add(new CohortProgress(ticket, outcome));
        }
        return new EventProgress(
                frozen,
                progress,
                window.isTerminal(frozen),
                window.requiredResourcesByLane());
    }

    ContractsRootFeederWindow.DurableState durableState() {
        return window.durableState();
    }

    @FunctionalInterface
    interface CohortExecutor {
        ContractsClosureAdapter.CohortOutcome execute(
                ContractsClosureAdapter.FrozenBatch batch,
                ContractsClosureAdapter.CohortInvocation invocation);
    }

    @FunctionalInterface
    interface AdmissionExecutor {
        ContractsClosureAdapter.CohortAdmission execute(
                ContractsClosureAdapter.FrozenBatch batch,
                ContractsClosureAdapter.CohortInvocation invocation);
    }

    /** One selected cohort execution and its exact Contracts outcome. */
    record CohortProgress(
            ContractsRootFeederWindow.AttemptTicket ticket,
            ContractsClosureAdapter.CohortOutcome outcome) {
        CohortProgress {
            ticket = Objects.requireNonNull(ticket, "ticket");
            outcome = Objects.requireNonNull(outcome, "outcome");
            if (!ticket.members().equals(outcome.members())) {
                throw new IllegalArgumentException(
                        "Cohort progress members disagree");
            }
        }
    }

    /** Exact feeder progress for one capture of one Root event. */
    record EventProgress(
            ContractsClosureAdapter.FrozenBatch batch,
            List<CohortProgress> cohorts,
            boolean terminal,
            Map<ContractsRootFeederWindow.LaneId,
                    List<ClosureResourceDemand>>
                    requiredResourcesByLane) {
        EventProgress {
            batch = Objects.requireNonNull(batch, "batch");
            cohorts = List.copyOf(Objects.requireNonNull(
                    cohorts, "cohorts"));
            requiredResourcesByLane = Map.copyOf(Objects.requireNonNull(
                    requiredResourcesByLane,
                    "requiredResourcesByLane"));
            if (terminal && !requiredResourcesByLane.isEmpty()) {
                throw new IllegalArgumentException(
                        "Terminal feeder progress cannot need resources");
            }
        }
    }
}
