package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalOrderKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Durable synchronized catch-up state for one parent attachment transition. */
final class CatchUpBarrier {
    enum Status {
        OPEN,
        DEFERRED,
        COMPLETE,
        BLOCKED
    }

    private final String barrierId;
    private final DocumentId parentDocumentId;
    private final String attachmentEntryBlueId;
    private final ExternalOrderKey cutoffExclusive;
    private final LinkedHashSet<String> bindingIds = new LinkedHashSet<>();
    private final LinkedHashSet<String> nestedBarrierIds = new LinkedHashSet<>();
    private final Map<String, ExternalOrderKey> historicalProgress =
            new LinkedHashMap<>();
    private final Map<String, CompletenessEvidence> completenessByBinding =
            new LinkedHashMap<>();
    private Status status = Status.OPEN;
    private String diagnostic;

    CatchUpBarrier(
            String barrierId,
            DocumentId parentDocumentId,
            String attachmentEntryBlueId,
            ExternalOrderKey cutoffExclusive) {
        this.barrierId = requireText(barrierId, "barrierId");
        this.parentDocumentId = Objects.requireNonNull(
                parentDocumentId, "parentDocumentId");
        this.attachmentEntryBlueId = requireText(
                attachmentEntryBlueId, "attachmentEntryBlueId");
        this.cutoffExclusive = Objects.requireNonNull(
                cutoffExclusive, "cutoffExclusive");
    }

    private CatchUpBarrier(CatchUpBarrier source) {
        barrierId = source.barrierId;
        parentDocumentId = source.parentDocumentId;
        attachmentEntryBlueId = source.attachmentEntryBlueId;
        cutoffExclusive = source.cutoffExclusive;
        bindingIds.addAll(source.bindingIds);
        nestedBarrierIds.addAll(source.nestedBarrierIds);
        historicalProgress.putAll(source.historicalProgress);
        completenessByBinding.putAll(source.completenessByBinding);
        status = source.status;
        diagnostic = source.diagnostic;
    }

    String barrierId() {
        return barrierId;
    }

    DocumentId parentDocumentId() {
        return parentDocumentId;
    }

    String attachmentEntryBlueId() {
        return attachmentEntryBlueId;
    }

    ExternalOrderKey cutoffExclusive() {
        return cutoffExclusive;
    }

    synchronized Set<String> bindingIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(bindingIds));
    }

    synchronized Set<String> nestedBarrierIds() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(nestedBarrierIds));
    }

    synchronized Status status() {
        return status;
    }

    synchronized String diagnostic() {
        return diagnostic;
    }

    synchronized void extend(String bindingId) {
        if (status == Status.COMPLETE || status == Status.BLOCKED) {
            throw new IllegalStateException(
                    "Cannot extend barrier in state " + status);
        }
        bindingIds.add(requireText(bindingId, "bindingId"));
        status = Status.OPEN;
    }

    synchronized void addNestedBarrier(String nestedBarrierId) {
        if (status == Status.COMPLETE || status == Status.BLOCKED) {
            throw new IllegalStateException(
                    "Cannot extend barrier in state " + status);
        }
        nestedBarrierIds.add(requireText(
                nestedBarrierId, "nestedBarrierId"));
    }

    synchronized ExternalOrderKey progress(String bindingId) {
        return historicalProgress.get(requireText(bindingId, "bindingId"));
    }

    synchronized void recordProgress(
            String bindingId,
            ExternalOrderKey sourceOrder) {
        String id = requireText(bindingId, "bindingId");
        ExternalOrderKey order = Objects.requireNonNull(
                sourceOrder, "sourceOrder");
        ExternalOrderKey previous = historicalProgress.get(id);
        if (previous != null && order.compareTo(previous) <= 0) {
            throw new IllegalStateException(
                    "Historical progress must increase for " + id);
        }
        historicalProgress.put(id, order);
        completenessByBinding.remove(id);
    }

    synchronized Map<String, ExternalOrderKey> historicalProgress() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(historicalProgress));
    }

    synchronized void recordCompletenessEvidence(
            String bindingId,
            CompletenessEvidence evidence) {
        String id = requireText(bindingId, "bindingId");
        if (!bindingIds.contains(id)) {
            throw new IllegalArgumentException(
                    "Completeness evidence has no barrier binding " + id);
        }
        CompletenessEvidence checked = Objects.requireNonNull(
                evidence, "evidence");
        if (!cutoffExclusive.equals(checked.cutoffExclusive())) {
            throw new IllegalArgumentException(
                    "Completeness evidence cutoff does not match barrier");
        }
        completenessByBinding.put(id, checked);
    }

    synchronized CompletenessEvidence completenessEvidence(
            String bindingId) {
        return completenessByBinding.get(requireText(
                bindingId, "bindingId"));
    }

    synchronized Map<String, CompletenessEvidence> completenessEvidence() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(completenessByBinding));
    }

    synchronized void defer(String reason) {
        diagnostic = requireText(reason, "reason");
        status = Status.DEFERRED;
    }

    synchronized void reopen() {
        if (status == Status.BLOCKED || status == Status.COMPLETE) {
            throw new IllegalStateException(
                    "Cannot reopen barrier in state " + status);
        }
        status = Status.OPEN;
        diagnostic = null;
    }

    synchronized void complete() {
        if (status == Status.BLOCKED) {
            throw new IllegalStateException("Blocked barrier cannot complete");
        }
        status = Status.COMPLETE;
        diagnostic = null;
    }

    synchronized void block(String reason) {
        diagnostic = requireText(reason, "reason");
        status = Status.BLOCKED;
    }

    synchronized CatchUpBarrier copy() {
        return new CatchUpBarrier(this);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
