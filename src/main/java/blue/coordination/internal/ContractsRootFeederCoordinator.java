package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes eligible closure cohorts under lane-local feeder barriers. */
final class ContractsRootFeederCoordinator {
    private final ContractsClosureAdapter adapter;
    private final ContractsRootFeederWindow window;
    private final CohortExecutor executor;

    ContractsRootFeederCoordinator(
            ContractsClosureAdapter adapter,
            ContractsRootFeederWindow window) {
        this(adapter, window, adapter::executeAndPublish);
    }

    ContractsRootFeederCoordinator(
            ContractsClosureAdapter adapter,
            ContractsRootFeederWindow window,
            CohortExecutor executor) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.window = Objects.requireNonNull(window, "window");
        this.executor = Objects.requireNonNull(executor, "executor");
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
            ContractsClosureAdapter.CohortOutcome outcome =
                    executor.execute(frozen, invocation);
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
            Map<ContractsRootFeederWindow.LaneId, List<String>>
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
