package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine.DrainBudget;
import blue.coordination.api.TimelineEntry;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Scans the global journal while retaining lane-local Contracts progress.
 *
 * <p>The scan cursor may inspect entries after a lane-local resource barrier,
 * allowing disconnected Root lanes to settle. The durable global frontier is
 * stricter: it advances only across a contiguous prefix of terminal entries.
 * Rescanning delegates suppression of already-terminal cohorts to the feeder
 * window.</p>
 */
final class ContractsJournalDrainCoordinator {
    private final InMemoryTimelineJournal journal;
    private final ContractsRootFeederCoordinator feeder;
    private final DurableState durableState;
    private final Supplier<Set<String>> activeSourceTimelines;

    ContractsJournalDrainCoordinator(
            InMemoryTimelineJournal journal,
            ContractsRootFeederCoordinator feeder) {
        this(journal, feeder, new DurableState(), null);
    }

    ContractsJournalDrainCoordinator(
            InMemoryTimelineJournal journal,
            ContractsRootFeederCoordinator feeder,
            DurableState durableState) {
        this(journal, feeder, durableState, null);
    }

    ContractsJournalDrainCoordinator(
            InMemoryTimelineJournal journal,
            ContractsRootFeederCoordinator feeder,
            DurableState durableState,
            Supplier<Set<String>> activeSourceTimelines) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.feeder = Objects.requireNonNull(feeder, "feeder");
        this.durableState = Objects.requireNonNull(
                durableState, "durableState");
        this.activeSourceTimelines = activeSourceTimelines;
    }

    synchronized DrainProgress drain() {
        return drainThrough(null, DrainBudget.unlimited());
    }

    /** Scans eligible entries without moving the contiguous frontier past a gap. */
    synchronized DrainProgress drainThrough(
            ExternalOrderKey inclusiveCutoff) {
        return drainThrough(inclusiveCutoff, DrainBudget.unlimited());
    }

    synchronized DrainProgress drainThrough(
            ExternalOrderKey inclusiveCutoff,
            DrainBudget budget) {
        DrainBudget limits = Objects.requireNonNull(budget, "budget");
        ExternalOrderKey scanAfter = durableState.processedThrough;
        List<ContractsRootFeederCoordinator.EventProgress> attempts =
                new ArrayList<>();
        long committedTransitions = 0L;
        long selectedEntries = 0L;
        boolean paused = false;
        while (true) {
            if (selectedEntries >= limits.maxSelectedEntries()
                    || committedTransitions
                    >= limits.maxCommittedProcessTransitions()) {
                paused = journal.nextExternal(
                        scanAfter, inclusiveCutoff).isPresent();
                break;
            }
            Optional<TimelineEntry> selected = journal.nextExternal(
                    scanAfter, inclusiveCutoff);
            if (selected.isEmpty()) {
                break;
            }
            TimelineEntry entry = selected.orElseThrow();
            EntryKey key = EntryKey.from(entry);
            if (!durableState.terminalEntries.contains(key)) {
                if (!isOnActiveSourceSurface(entry)) {
                    durableState.terminalEntries.add(key);
                    scanAfter = entry.sourceOrderKey();
                    continue;
                }
                ContractsRootFeederCoordinator.EventProgress progress =
                        feeder.process(entry);
                attempts.add(progress);
                selectedEntries = Math.addExact(selectedEntries, 1L);
                committedTransitions = Math.addExact(
                        committedTransitions,
                        committedTransitions(progress));
                if (progress.terminal()) {
                    durableState.terminalEntries.add(key);
                }
            }
            scanAfter = entry.sourceOrderKey();
        }
        List<TimelineEntry> completed = advanceContiguousFrontier(
                inclusiveCutoff);
        boolean quiescent = !paused && journal.nextExternal(
                durableState.processedThrough, inclusiveCutoff).isEmpty();
        return new DrainProgress(
                attempts,
                completed,
                durableState.processedThrough,
                quiescent,
                paused,
                committedTransitions);
    }

    synchronized ExternalOrderKey processedThrough() {
        return durableState.processedThrough;
    }

    synchronized DurableState durableState() {
        return durableState;
    }

    private List<TimelineEntry> advanceContiguousFrontier(
            ExternalOrderKey inclusiveCutoff) {
        List<TimelineEntry> completed = new ArrayList<>();
        while (true) {
            Optional<TimelineEntry> next = journal.nextExternal(
                    durableState.processedThrough, inclusiveCutoff);
            if (next.isEmpty()) {
                return List.copyOf(completed);
            }
            TimelineEntry entry = next.orElseThrow();
            if (!durableState.terminalEntries.contains(
                    EntryKey.from(entry))) {
                return List.copyOf(completed);
            }
            durableState.processedThrough = entry.sourceOrderKey();
            completed.add(entry);
        }
    }

    private boolean isOnActiveSourceSurface(TimelineEntry entry) {
        if (activeSourceTimelines == null) {
            return true;
        }
        Set<String> active = Set.copyOf(Objects.requireNonNull(
                activeSourceTimelines.get(), "activeSourceTimelines"));
        return active.contains(entry.timeline().timelineId());
    }

    private static long committedTransitions(
            ContractsRootFeederCoordinator.EventProgress progress) {
        return progress.cohorts().stream()
                .filter(cohort -> cohort.outcome().published()
                        && !cohort.outcome().replayed())
                .mapToLong(cohort -> cohort.outcome().members().size())
                .sum();
    }

    record DrainProgress(
            List<ContractsRootFeederCoordinator.EventProgress> attempts,
            List<TimelineEntry> completedEntries,
            ExternalOrderKey processedThrough,
            boolean quiescent,
            boolean paused,
            long committedTransitions) {
        DrainProgress {
            attempts = List.copyOf(Objects.requireNonNull(
                    attempts, "attempts"));
            completedEntries = List.copyOf(Objects.requireNonNull(
                    completedEntries, "completedEntries"));
            if (quiescent && paused) {
                throw new IllegalArgumentException(
                        "A drain cannot be quiescent and paused");
            }
            if (committedTransitions < 0L) {
                throw new IllegalArgumentException(
                        "committedTransitions must be non-negative");
            }
        }
    }

    static final class DurableState {
        private final Set<EntryKey> terminalEntries = new LinkedHashSet<>();
        private ExternalOrderKey processedThrough;
    }

    private record EntryKey(
            String entryBlueId,
            ExternalOrderKey sourceOrder) {
        private EntryKey {
            entryBlueId = Objects.requireNonNull(entryBlueId, "entryBlueId");
            sourceOrder = Objects.requireNonNull(sourceOrder, "sourceOrder");
        }

        private static EntryKey from(TimelineEntry entry) {
            TimelineEntry checked = Objects.requireNonNull(entry, "entry");
            return new EntryKey(
                    checked.blueId(), checked.sourceOrderKey());
        }
    }
}
