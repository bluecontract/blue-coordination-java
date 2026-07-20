package blue.coordination.processor.workflow;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Per-workflow append-only result state.
 *
 * <p>Each read-only view captures a revision rather than copying all preceding
 * entries. This keeps workflow allocation linear while retaining the stable
 * snapshot behavior previously provided by {@code StepExecutionContext}.</p>
 */
final class WorkflowExecutionState {
    private final List<ResultSlot> orderedSlots = new ArrayList<ResultSlot>();
    private final Map<String, ResultSlot> slotsByKey = new HashMap<String, ResultSlot>();
    private final Map<String, Long> firstHandledRevision = new HashMap<String, Long>();
    private long revision;

    WorkflowExecutionState() {
    }

    static Snapshot snapshotOf(Map<String, Object> results, Set<String> handledChangesets) {
        WorkflowExecutionState state = new WorkflowExecutionState();
        if (results != null) {
            for (Map.Entry<String, Object> entry : results.entrySet()) {
                state.record(entry.getKey(), entry.getValue(), false);
            }
        }
        if (handledChangesets != null) {
            for (String key : handledChangesets) {
                state.markHandledAtCurrentRevision(key);
            }
        }
        return state.snapshotView();
    }

    Snapshot snapshotView() {
        return new Snapshot(this, revision, orderedSlots.size());
    }

    void record(String key, Object value, boolean changesetHandled) {
        revision++;
        ResultSlot slot = slotsByKey.get(key);
        if (slot == null) {
            slot = new ResultSlot(key);
            slotsByKey.put(key, slot);
            orderedSlots.add(slot);
        }
        slot.latest = new ResultVersion(revision, value, slot.latest);
        if (changesetHandled && !firstHandledRevision.containsKey(key)) {
            firstHandledRevision.put(key, Long.valueOf(revision));
        }
    }

    private void markHandledAtCurrentRevision(String key) {
        if (!firstHandledRevision.containsKey(key)) {
            firstHandledRevision.put(key, Long.valueOf(revision));
        }
    }

    private Object valueAt(String key, long visibleRevision) {
        ResultSlot slot = slotsByKey.get(key);
        if (slot == null) {
            return null;
        }
        ResultVersion version = slot.latest;
        while (version != null && version.revision > visibleRevision) {
            version = version.previous;
        }
        return version != null ? version.value : null;
    }

    private boolean containsAt(String key, long visibleRevision) {
        ResultSlot slot = slotsByKey.get(key);
        if (slot == null) {
            return false;
        }
        ResultVersion version = slot.latest;
        while (version != null && version.revision > visibleRevision) {
            version = version.previous;
        }
        return version != null;
    }

    private boolean wasChangesetHandledAt(String key, long visibleRevision) {
        Long handledRevision = firstHandledRevision.get(key);
        return handledRevision != null && handledRevision.longValue() <= visibleRevision;
    }

    static final class Snapshot extends AbstractMap<String, Object> {
        private final WorkflowExecutionState state;
        private final long visibleRevision;
        private final int visibleSlotCount;
        private final Set<Map.Entry<String, Object>> entries;
        private final Map<String, Object> readOnlyResults;

        private Snapshot(WorkflowExecutionState state,
                         long visibleRevision,
                         int visibleSlotCount) {
            this.state = state;
            this.visibleRevision = visibleRevision;
            this.visibleSlotCount = visibleSlotCount;
            this.entries = new SnapshotEntrySet();
            this.readOnlyResults = Collections.unmodifiableMap(this);
        }

        Map<String, Object> results() {
            return readOnlyResults;
        }

        boolean wasChangesetHandled(String stepKey) {
            return stepKey != null && state.wasChangesetHandledAt(stepKey, visibleRevision);
        }

        @Override
        public Object get(Object key) {
            if (key != null && !(key instanceof String)) {
                return null;
            }
            return state.valueAt((String) key, visibleRevision);
        }

        @Override
        public boolean containsKey(Object key) {
            if (key != null && !(key instanceof String)) {
                return false;
            }
            return state.containsAt((String) key, visibleRevision);
        }

        @Override
        public int size() {
            return visibleSlotCount;
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return entries;
        }

        private final class SnapshotEntrySet extends AbstractSet<Map.Entry<String, Object>> {
            @Override
            public Iterator<Map.Entry<String, Object>> iterator() {
                return new Iterator<Map.Entry<String, Object>>() {
                    private int index;

                    @Override
                    public boolean hasNext() {
                        return index < visibleSlotCount;
                    }

                    @Override
                    public Map.Entry<String, Object> next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        ResultSlot slot = state.orderedSlots.get(index++);
                        return new SimpleImmutableEntry<String, Object>(slot.key,
                                state.valueAt(slot.key, visibleRevision));
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException("workflow state is read-only");
                    }
                };
            }

            @Override
            public int size() {
                return visibleSlotCount;
            }

            @Override
            public void clear() {
                throw new UnsupportedOperationException("workflow state is read-only");
            }
        }
    }

    private static final class ResultSlot {
        private final String key;
        private ResultVersion latest;

        private ResultSlot(String key) {
            this.key = key;
        }
    }

    private static final class ResultVersion {
        private final long revision;
        private final Object value;
        private final ResultVersion previous;

        private ResultVersion(long revision, Object value, ResultVersion previous) {
            this.revision = revision;
            this.value = value;
            this.previous = previous;
        }
    }
}
