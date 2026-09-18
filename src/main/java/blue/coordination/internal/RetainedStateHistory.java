package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import static blue.coordination.internal.SessionStorageWire.require;

/** Immutable list compatibility view over the session's shared epoch-metadata lane. */
final class RetainedStateHistory extends AbstractList<ManagedLineageIndex.RetainedState> implements RandomAccess {
    private final DocumentId documentId;
    private final PersistentOrderedMap<Long, ManagedLineageIndex.RetainedState> states;

    RetainedStateHistory(DocumentId documentId, PersistentOrderedMap<Long, ManagedLineageIndex.RetainedState> states) {
        this.documentId = Objects.requireNonNull(documentId);
        this.states = Objects.requireNonNull(states);
    }

    static RetainedStateHistory indexed(DocumentId documentId, List<ManagedLineageIndex.RetainedState> states) {
        if (states instanceof RetainedStateHistory indexed) {
            require(documentId.equals(indexed.documentId), "Retained history belongs to another document");
            return indexed;
        }
        PersistentOrderedMap<Long, ManagedLineageIndex.RetainedState> index = PersistentOrderedMap.empty(Long::compare);
        long ordinal = 0;
        for (var state : states) index = index.put(ordinal++, state).map();
        return new RetainedStateHistory(documentId, index);
    }

    PersistentOrderedMap<Long, ManagedLineageIndex.RetainedState> index() { return states; }

    @Override public int size() { return states.size(); }

    @Override public ManagedLineageIndex.RetainedState get(int ordinal) {
        Objects.checkIndex(ordinal, size());
        return checked(ordinal, states.get((long) ordinal));
    }

    private ManagedLineageIndex.RetainedState checked(long ordinal, ManagedLineageIndex.RetainedState row) {
        require(row != null && row.epoch() == ordinal && documentId.equals(row.documentId()),
                "Retained history row differs from its selected owner/epoch");
        return row;
    }

    @Override public Iterator<ManagedLineageIndex.RetainedState> iterator() {
        Iterator<Map.Entry<Long, ManagedLineageIndex.RetainedState>> entries = states.range(null, null);
        return new Iterator<>() {
            private long ordinal;
            @Override public boolean hasNext() { return entries.hasNext(); }
            @Override public ManagedLineageIndex.RetainedState next() {
                var entry = entries.next();
                require(entry.getKey() == ordinal, "Retained history contains a missing epoch");
                return checked(ordinal++, entry.getValue());
            }
        };
    }

    static boolean sameBasis(List<ManagedLineageIndex.RetainedState> left,
            List<ManagedLineageIndex.RetainedState> right) {
        if (left == right) return true;
        if (!(left instanceof RetainedStateHistory a) || !(right instanceof RetainedStateHistory b)
                || !a.documentId.equals(b.documentId)) return false;
        if (a.states == b.states) return true;
        return a.states.isStored() && b.states.isStored()
                && Arrays.equals(a.states.storedRootDescriptor(), b.states.storedRootDescriptor());
    }

    @Override public boolean equals(Object other) {
        if (other instanceof RetainedStateHistory history && sameBasis(this, history)) return true;
        return super.equals(other);
    }

    @Override public int hashCode() { return super.hashCode(); }
}
