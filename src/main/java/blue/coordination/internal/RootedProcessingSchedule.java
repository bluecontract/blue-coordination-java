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
    private final Set<DocumentId> yielded = new LinkedHashSet<>();
    private final Map<DocumentId, String> isolated = new LinkedHashMap<>();

    /** Read-only prediction; an admission hint does not make that root's later LIVE input eligible. */
    RootedCheckpointDriver.Head next(RootedCheckpointDriver.Scan scan, boolean journalAdmission,
            Set<DocumentId> deferredInCall) {
        var heads = scan.heads().stream().filter(head -> !deferredInCall.contains(head.root())).toList();
        var live = heads.stream().filter(head -> head.selection().live() != null).findFirst().orElse(null);
        var history = heads.stream().filter(head -> historical(head.selection()))
                .filter(head -> !work(head.selection()).equals(isolated.get(head.root()))).toList();
        var nextHistory = history.stream().filter(head -> !yielded.contains(head.root())).findFirst()
                .orElse(history.isEmpty() ? null : history.get(0));
        if (historicalTurn && nextHistory != null) return nextHistory;
        if (live != null) return live;
        if (journalAdmission) return null;
        if (nextHistory != null) return nextHistory;
        // No independent progress remains: expose the unchanged failed work for an explicit retry/park.
        return heads.isEmpty() ? null : heads.get(0);
    }

    /** Called only after actual execution, before readiness is widened to include further pending work. */
    void completed(DocumentId owner, RootedCheckpointDriver.Selection selected, ProcessingDrainReceipt result) {
        if (selected.live() != null) {
            historicalTurn = true;
        } else if (historical(selected)) {
            historicalTurn = false;
            if (yielded.contains(owner)) yielded.clear();
            yielded.add(owner);
            boolean publicationFailure = result.managedEpochApplicationAttempts().stream()
                    .anyMatch(attempt -> attempt.publicationFailure().isPresent())
                    || result.rootedRetainedAttempts().stream().anyMatch(attempt ->
                            attempt.attempt().attempt().isComplete()
                            && attempt.attempt().attempt().processResult().commits() && !attempt.attempt().published());
            if (result.quiescent()) isolated.remove(owner);
            else if (!publicationFailure) isolated.put(owner, work(selected));
        }
    }

    private static boolean historical(RootedCheckpointDriver.Selection selected) {
        return selected.historical() != null || selected.localHistorical() != null;
    }

    private static String work(RootedCheckpointDriver.Selection selected) {
        return selected.historical() != null ? selected.historical().workIdentity()
                : selected.localHistorical().work().workIdentity();
    }
}
