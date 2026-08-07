package blue.coordination.engine.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable outcome of one verified in-memory admission transaction. */
public final class CoordinationEventAdmissionReceipt {

    private final String eventBlueId;
    private final String inventoryIdentity;
    private final List<String> insertedFragmentBlueIds;
    private final List<String> retainedFragmentBlueIds;
    private final int insertedProcessingViewCount;
    private final boolean inventoryInserted;

    public CoordinationEventAdmissionReceipt(
            String eventBlueId,
            String inventoryIdentity,
            List<String> insertedFragmentBlueIds,
            List<String> retainedFragmentBlueIds,
            int insertedProcessingViewCount,
            boolean inventoryInserted) {
        this.eventBlueId = requireText(eventBlueId, "eventBlueId");
        this.inventoryIdentity = requireText(
                inventoryIdentity, "inventoryIdentity");
        this.insertedFragmentBlueIds = immutableTextList(
                insertedFragmentBlueIds, "insertedFragmentBlueIds");
        this.retainedFragmentBlueIds = immutableTextList(
                retainedFragmentBlueIds, "retainedFragmentBlueIds");
        Set<String> overlap = new HashSet<String>(
                this.insertedFragmentBlueIds);
        overlap.retainAll(this.retainedFragmentBlueIds);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "A fragment cannot be both inserted and retained");
        }
        if (insertedProcessingViewCount < 0) {
            throw new IllegalArgumentException(
                    "insertedProcessingViewCount must not be negative");
        }
        this.insertedProcessingViewCount = insertedProcessingViewCount;
        this.inventoryInserted = inventoryInserted;
    }

    public String eventBlueId() { return eventBlueId; }
    public String inventoryIdentity() { return inventoryIdentity; }
    public List<String> insertedFragmentBlueIds() {
        return insertedFragmentBlueIds;
    }
    public List<String> retainedFragmentBlueIds() {
        return retainedFragmentBlueIds;
    }
    public int insertedProcessingViewCount() {
        return insertedProcessingViewCount;
    }
    public boolean inventoryInserted() { return inventoryInserted; }

    public boolean installedAnything() {
        return inventoryInserted
                || !insertedFragmentBlueIds.isEmpty()
                || insertedProcessingViewCount > 0;
    }

    private static List<String> immutableTextList(
            List<String> values,
            String label) {
        List<String> copied = new ArrayList<String>();
        for (String value : Objects.requireNonNull(values, label)) {
            copied.add(requireText(value, label + " element"));
        }
        return Collections.unmodifiableList(copied);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
