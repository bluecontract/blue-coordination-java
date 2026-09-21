package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ProcessingDrainReceipt;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Retained host fairness between canonical root heads; never selects a later input within a root. */
final class RootedProcessingSchedule {
    private boolean historicalTurn;
    private final Set<DocumentId> yielded;
    private final Map<DocumentId, String> isolated;
    private final Map<DocumentId, Boolean> logicalTurns;
    private final Map<DocumentId, Long> logicalRounds;

    RootedProcessingSchedule() { this(null, null, new LinkedHashSet<>(), new LinkedHashMap<>()); }
    RootedProcessingSchedule(Map<DocumentId, Boolean> turns, Map<DocumentId, Long> rounds,
            Set<DocumentId> yielded, Map<DocumentId, String> isolated) {
        logicalTurns = turns; logicalRounds = rounds; this.yielded = java.util.Objects.requireNonNull(yielded);
        this.isolated = java.util.Objects.requireNonNull(isolated);
    }

    record StorageState(boolean historicalTurn, java.util.List<DocumentId> yielded,
            Map<DocumentId, String> isolated) {
        StorageState {
            yielded = java.util.List.copyOf(yielded);
            isolated = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(isolated));
            if (new LinkedHashSet<>(yielded).size() != yielded.size())
                throw new IllegalArgumentException("Repeated yielded root");
        }
    }

    StorageState storageState() {
        if (logicalTurns != null) throw new IllegalStateException("Logical root fairness must be published as owner records");
        return new StorageState(historicalTurn, java.util.List.copyOf(yielded), isolated);
    }

    static RootedProcessingSchedule fromStorage(StorageState state) {
        var restored = new RootedProcessingSchedule();
        restored.historicalTurn = state.historicalTurn();
        restored.yielded.addAll(state.yielded());
        restored.isolated.putAll(state.isolated());
        return restored;
    }

    /** Read-only prediction; an admission hint does not make that root's later LIVE input eligible. */
    RootedCheckpointDriver.Head next(RootedCheckpointDriver.Scan scan, boolean journalAdmission,
            Set<DocumentId> deferredInCall) {
        var heads = scan.heads().stream().filter(head -> !deferredInCall.contains(head.root())).toList();
        var live = heads.stream().filter(head -> head.selection().live() != null).findFirst().orElse(null);
        var history = heads.stream().filter(head -> historical(head.selection()))
                .filter(head -> !work(head.selection()).equals(isolated.get(head.root()))).toList();
        var nextHistory = logicalRounds == null
                ? history.stream().filter(head -> !yielded.contains(head.root())).findFirst()
                        .orElse(history.isEmpty() ? null : history.get(0))
                : history.stream().min(java.util.Comparator.comparingLong(
                        head -> logicalRounds.getOrDefault(head.root(), 0L))).orElse(null);
        if (nextHistory != null && (logicalTurns == null ? historicalTurn
                : Boolean.TRUE.equals(logicalTurns.get(nextHistory.root())))) return nextHistory;
        if (live != null) return live;
        if (journalAdmission) return null;
        if (nextHistory != null) return nextHistory;
        // No independent progress remains: expose the unchanged failed work for an explicit retry/park.
        return heads.isEmpty() ? null : heads.get(0);
    }

    /** Retains a bounded Journal call's yield without executing or clearing a failed historical item. */
    void yieldJournalToHistory(RootedCheckpointDriver.Head next) {
        if (!historical(next.selection())) throw new IllegalArgumentException("A Journal yield requires retained work");
        if (work(next.selection()).equals(isolated.get(next.root()))) return;
        if (logicalTurns == null) historicalTurn = true;
        else logicalTurns.put(next.root(), true);
        // This root can already have had its turn while another root's next LIVE
        // input lies beyond the cutoff. Reopen only this eligible historical turn.
        if (logicalRounds == null) yielded.remove(next.root());
    }

    /** Called only after actual execution, before readiness is widened to include further pending work. */
    void completed(DocumentId owner, RootedCheckpointDriver.Selection selected, ProcessingDrainReceipt result) {
        if (selected.live() != null) {
            if (logicalTurns == null) historicalTurn = true;
            else logicalTurns.put(owner, true);
        } else if (historical(selected)) {
            if (logicalTurns == null) historicalTurn = false;
            else logicalTurns.put(owner, false);
            // A scoped stage yields only its owner. Global rounds remain a resident convenience policy.
            if (logicalRounds == null) {
                if (yielded.contains(owner)) yielded.clear();
                yielded.add(owner);
            } else logicalRounds.put(owner, Math.addExact(logicalRounds.getOrDefault(owner, 0L), 1L));
            boolean publicationFailure = result.managedEpochApplicationAttempts().stream()
                    .anyMatch(attempt -> attempt.publicationFailure().isPresent())
                    || result.rootedRetainedAttempts().stream().anyMatch(attempt ->
                            attempt.attempt().attempt().isComplete()
                            && attempt.attempt().attempt().processResult().commits() && !attempt.attempt().published());
            if (result.quiescent()) isolated.remove(owner);
            else if (!publicationFailure) isolated.put(owner, work(selected));
        }
    }

    void retire(DocumentId owner) {
        if (logicalTurns == null || logicalRounds == null) throw new IllegalStateException("Instance retirement requires logical scheduling");
        logicalTurns.remove(owner); logicalRounds.remove(owner); isolated.remove(owner);
    }

    private static boolean historical(RootedCheckpointDriver.Selection selected) {
        return selected.historical() != null || selected.localHistorical() != null;
    }

    private static String work(RootedCheckpointDriver.Selection selected) {
        return selected.historical() != null ? selected.historical().workIdentity()
                : selected.localHistorical().work().workIdentity();
    }
}
