package blue.coordination.internal;

import blue.coordination.api.*;
import blue.language.processor.ExternalOrderKey;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RootedProcessingScheduleTest {
    @Test void logicalFairnessContinuesAcrossColdRoundsWithoutStarvingLaterRoots() {
        // given
        var turns = new HashMap<DocumentId, Boolean>(); var rounds = new HashMap<DocumentId, Long>();
        var isolated = new HashMap<DocumentId, String>(); var a = head("a"); var b = head("b");
        var scan = new RootedCheckpointDriver.Scan(List.of(a, b), Set.of());
        var selected = new ArrayList<DocumentId>();
        // when
        for (int i = 0; i < 12; i++) {
            var cold = new RootedProcessingSchedule(turns, rounds, Set.of(), isolated);
            var next = cold.next(scan, false, Set.of()); selected.add(next.root());
            cold.completed(next.root(), next.selection(), completed());
        }
        // then
        assertEquals(Collections.nCopies(6, List.of(a.root(), b.root())).stream().flatMap(List::stream).toList(), selected);
        assertEquals(Map.of(a.root(), 6L, b.root(), 6L), rounds);
        assertTrue(isolated.isEmpty());
    }

    @Test void completingOneOwnerLeavesEveryOtherOwnersFairnessUntouched() {
        // given
        var a = head("a"); var b = head("b");
        var turns = new HashMap<DocumentId, Boolean>(Map.of(b.root(), true));
        var rounds = new HashMap<DocumentId, Long>(Map.of(b.root(), 7L));
        var isolated = new HashMap<DocumentId, String>(Map.of(b.root(), "blocked"));
        var schedule = new RootedProcessingSchedule(turns, rounds, Set.of(), isolated);
        // when
        schedule.completed(a.root(), a.selection(), completed());
        // then
        assertEquals(true, turns.get(b.root())); assertEquals(7L, rounds.get(b.root()));
        assertEquals("blocked", isolated.get(b.root())); assertEquals(1L, rounds.get(a.root()));
        assertThrows(IllegalStateException.class, schedule::storageState);
    }

    private static ProcessingDrainReceipt completed() {
        return new ProcessingDrainReceipt(List.of(), Map.of(), null, true, false, 1, 0);
    }
    private static RootedCheckpointDriver.Head head(String id) {
        var owner = DocumentId.of(id); var sha = "sha256:" + "a".repeat(64);
        var work = ManagedEpochApplicationWork.identified(sha, sha, sha, DocumentId.of("source"), 0,
                owner, sha, "/child", 1, 0, ExactValue.verified(new blue.language.model.Node().value("fairness")).blueId(), 0);
        return new RootedCheckpointDriver.Head(owner, new RootedCheckpointDriver.Selection(null, work, Set.of(), false),
                ExternalOrderKey.of(List.of(1L)));
    }
}
