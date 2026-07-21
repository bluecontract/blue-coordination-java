package blue.coordination.processor.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowExecutionStateTest {
    @Test
    void snapshotViewsAreStableOrderedAndReadOnly() {
        WorkflowExecutionState state = new WorkflowExecutionState();
        WorkflowExecutionState.Snapshot empty = state.snapshotView();

        state.record("First", "a", false);
        WorkflowExecutionState.Snapshot afterFirst = state.snapshotView();
        state.record("Second", "b", true);
        WorkflowExecutionState.Snapshot afterSecond = state.snapshotView();

        assertTrue(empty.results().isEmpty());
        assertEquals(Arrays.asList("First"), new ArrayList<String>(afterFirst.results().keySet()));
        assertEquals("a", afterFirst.results().get("First"));
        assertFalse(afterFirst.results().containsKey("Second"));
        assertFalse(afterFirst.wasChangesetHandled("Second"));
        assertEquals(Arrays.asList("First", "Second"),
                new ArrayList<String>(afterSecond.results().keySet()));
        assertTrue(afterSecond.wasChangesetHandled("Second"));

        assertThrows(UnsupportedOperationException.class,
                () -> empty.results().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> afterFirst.results().put("Other", "value"));
        assertThrows(UnsupportedOperationException.class,
                () -> afterSecond.results().remove("missing"));
    }

    @Test
    void duplicateKeysPreserveEarlierViewsNullValuesAndFirstInsertionOrder() {
        WorkflowExecutionState state = new WorkflowExecutionState();
        state.record("Repeated", "first", false);
        WorkflowExecutionState.Snapshot beforeOverwrite = state.snapshotView();
        state.record("Other", "other", false);
        state.record("Repeated", null, true);
        WorkflowExecutionState.Snapshot afterOverwrite = state.snapshotView();
        state.record("Repeated", "third", false);

        assertEquals("first", beforeOverwrite.results().get("Repeated"));
        assertFalse(beforeOverwrite.wasChangesetHandled("Repeated"));
        assertEquals(Arrays.asList("Repeated", "Other"),
                new ArrayList<String>(afterOverwrite.results().keySet()));
        assertTrue(afterOverwrite.results().containsKey("Repeated"));
        assertNull(afterOverwrite.results().get("Repeated"));
        assertTrue(afterOverwrite.wasChangesetHandled("Repeated"));
        assertNull(afterOverwrite.results().get("Repeated"));
    }

    @Test
    void oneThousandStepViewsRetainTheirPrefixWithoutMapCopies() {
        WorkflowExecutionState state = new WorkflowExecutionState();
        List<WorkflowExecutionState.Snapshot> retained =
                new ArrayList<WorkflowExecutionState.Snapshot>(1001);
        for (int i = 0; i <= 1000; i++) {
            retained.add(state.snapshotView());
            if (i < 1000) {
                state.record("Step" + (i + 1), Integer.valueOf(i), false);
            }
        }

        assertEquals(0, retained.get(0).size());
        assertEquals(500, retained.get(500).size());
        assertEquals(Integer.valueOf(499), retained.get(500).get("Step500"));
        assertFalse(retained.get(500).containsKey("Step501"));
        assertEquals(1000, retained.get(1000).size());
        assertEquals(Integer.valueOf(999), retained.get(1000).get("Step1000"));
    }
}
