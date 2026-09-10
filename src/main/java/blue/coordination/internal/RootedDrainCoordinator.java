package blue.coordination.internal;

import blue.coordination.api.ContractsClosureDispatchAttempt;
import blue.coordination.api.CoordinationEngine.DrainBudget;
import blue.coordination.api.DocumentDispatchOutcome;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochApplicationAttempt;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochEvidenceFailure;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.TimelineEntry;
import blue.language.processor.ExternalOrderKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Host scheduling across independent rooted operations, never a shared semantic invocation. */
final class RootedDrainCoordinator {
    private final RootedCheckpointDriver driver;
    private final InMemoryTimelineJournal journal;
    private final ContractsJournalDrainCoordinator transport;
    private final Function<RootedCheckpointDriver.Head, ProcessingDrainReceipt> execute;

    RootedDrainCoordinator(RootedCheckpointDriver driver, InMemoryTimelineJournal journal,
            ContractsJournalDrainCoordinator transport,
            Function<RootedCheckpointDriver.Head, ProcessingDrainReceipt> execute) {
        this.driver = driver;
        this.journal = journal;
        this.transport = transport;
        this.execute = execute;
    }

    ProcessingDrainReceipt drain(ExternalOrderKey cutoff, DrainBudget budget, boolean managedAllowed) {
        long started = System.nanoTime();
        long committed = 0L;
        long selected = 0L;
        Set<DocumentId> deferred = new LinkedHashSet<>();
        Map<String, TimelineEntry> completed = new LinkedHashMap<>();
        Map<String, List<DocumentDispatchOutcome>> outcomes = new LinkedHashMap<>();
        Map<String, List<ContractsClosureDispatchAttempt>> attempts = new LinkedHashMap<>();
        List<ManagedEpochApplicationReceipt> applications = new ArrayList<>();
        List<ManagedEpochApplicationAttempt> managedAttempts = new ArrayList<>();
        List<ManagedEpochEvidenceFailure> failures = new ArrayList<>();
        List<ProcessingDrainReceipt.RootedRetainedAttempt> localAttempts = new ArrayList<>();
        RootedCheckpointDriver.Scan remaining = driver.scan(journal.entries(), cutoff);
        while (selected < budget.maxSelectedEntries() && committed < budget.maxCommittedProcessTransitions()) {
            var next = remaining.heads().stream().filter(head -> !deferred.contains(head.root())).findFirst();
            if (next.isEmpty() || !managedAllowed && (next.get().selection().historical() != null || next.get().selection().localHistorical() != null)) break;
            var result = execute.apply(next.get());
            selected = Math.addExact(selected, 1L);
            committed = Math.addExact(committed, result.committedProcessTransitions());
            merge(outcomes, result.outcomesByEntry());
            merge(attempts, result.contractsAttemptsByEntry());
            applications.addAll(result.managedEpochApplications());
            managedAttempts.addAll(result.managedEpochApplicationAttempts());
            failures.addAll(result.managedEpochEvidenceFailures());
            localAttempts.addAll(result.rootedRetainedAttempts());
            if (!result.quiescent()) deferred.add(next.get().root());
            remaining = driver.scan(journal.entries(), cutoff);
        }
        // This cursor describes transport completion only. Root selection always
        // uses local exact progress, including entries behind this cursor.
        for (TimelineEntry entry : transport.completeRootedTransport(cutoff, remaining)) {
            completed.putIfAbsent(entry.blueId(), entry);
        }
        boolean budgetExhausted = selected >= budget.maxSelectedEntries()
                || committed >= budget.maxCommittedProcessTransitions();
        boolean runnable = remaining.heads().stream().anyMatch(head -> !deferred.contains(head.root()));
        return new ProcessingDrainReceipt(new ArrayList<>(completed.values()), outcomes, attempts,
                transport.processedThrough(), remaining.quiescent(),
                !remaining.quiescent() && runnable && (budgetExhausted || !managedAllowed),
                committed, System.nanoTime() - started, applications, managedAttempts, failures)
                .withRootedRetainedAttempts(localAttempts);
    }

    private static <T> void merge(Map<String, List<T>> target, Map<String, List<T>> source) {
        source.forEach((key, values) -> target.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(values));
    }
}
