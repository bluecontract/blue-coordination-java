package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;
import blue.language.api.BlueCachePolicy;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.DirectLogicalDelivery;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Disposable exact-input memo; neither terminal evidence nor a processing capability. */
final class RootedEligibilityCache {
    private final Limits limits;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedWeight;

    RootedEligibilityCache() {
        this(Limits.defaults());
    }

    RootedEligibilityCache(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    synchronized List<DirectLogicalDelivery> select(AffectedClosureSnapshot snapshot,
            List<DirectLogicalDelivery> deliveries, TimelineEntry event, Supplier<boolean[]> classify) {
        Captured captured = captureIfEnabled(snapshot, deliveries, event);
        Entry found = captured == null ? null : entries.get(captured.key());
        boolean[] mask;
        if (found != null) {
            mask = found.mask();
        } else {
            // One miss executes the complete original comparison session. Mixing
            // per-delivery hits and misses would change its shared meter's budget.
            mask = Objects.requireNonNull(classify.get(), "eligibility mask").clone();
            if (mask.length != deliveries.size()) {
                throw new IllegalStateException("Eligibility mask differs from the complete ordered call");
            }
            if (captured != null) retain(captured, mask);
        }
        List<DirectLogicalDelivery> selected = new ArrayList<>();
        for (int index = 0; index < mask.length; index++) {
            if (mask[index]) selected.add(deliveries.get(index));
        }
        // Return this call's original DTOs, never an earlier capture's handles.
        return List.copyOf(selected);
    }

    synchronized void clear() {
        entries.clear();
        retainedWeight = 0L;
    }

    synchronized int entryCount() { return entries.size(); }

    synchronized long retainedWeightBytes() { return retainedWeight; }

    private Captured captureIfEnabled(AffectedClosureSnapshot snapshot, List<DirectLogicalDelivery> deliveries,
            TimelineEntry event) {
        if (limits.maximumEntries() == 0 || limits.maximumWeightBytes() == 0
                || limits.maximumEntryWeightBytes() == 0) return null;
        try {
            return capture(snapshot, deliveries, event);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | ClassCastException unsupportedKey) {
            // This physical optimization is not input validation. The unchanged
            // classifier must decide unsupported/malformed inputs in its original
            // order. Its exceptions are outside this catch and are never retained.
            return null;
        }
    }

    private void retain(Captured captured, boolean[] mask) {
        long weight = captured.weight();
        if (limits.maximumEntries() == 0 || weight > limits.maximumEntryWeightBytes()
                || weight > limits.maximumWeightBytes()) return;
        while (!entries.isEmpty() && (entries.size() >= limits.maximumEntries()
                || retainedWeight > limits.maximumWeightBytes() - weight)) {
            var oldest = entries.entrySet().iterator();
            retainedWeight -= oldest.next().getValue().weight();
            oldest.remove();
        }
        entries.put(captured.key(), new Entry(mask, weight));
        retainedWeight += weight;
    }

    private static Captured capture(AffectedClosureSnapshot snapshot, List<DirectLogicalDelivery> deliveries,
            TimelineEntry event) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        Map<blue.language.processor.closure.DocumentId, FrozenNode> documents = new LinkedHashMap<>();
        List<DeliveryKey> ordered = new ArrayList<>();
        long overhead = add(256L, stringWeight(event.blueId()));
        for (DirectLogicalDelivery delivery : deliveries) {
            FrozenNode document = documents.computeIfAbsent(delivery.targetDocumentId(), id ->
                    FrozenNode.fromResolvedNode(Objects.requireNonNull(snapshot.managedDocument(id),
                            "Missing exact eligibility target").document()));
            ordered.add(new DeliveryKey(delivery.targetDocumentId().value(), document.resolvedStructuralKey(),
                    delivery.channelKey(), delivery.logicalDeliveryKey(), delivery.rawOccurrenceOrder()));
            overhead = add(overhead, add(160L, add(stringWeight(delivery.targetDocumentId().value()),
                    add(stringWeight(delivery.channelKey()), stringWeight(delivery.logicalDeliveryKey())))));
        }
        FrozenNode exactEvent = event.exactEvent().frozen();
        Key key = new Key(event.blueId(), exactEvent.resolvedStructuralKey(), List.copyOf(ordered));
        List<FrozenNode> roots = new ArrayList<>(documents.values());
        roots.add(exactEvent);
        // Weigh after key construction: the estimator includes those cached
        // structural-key graphs. Cross-entry sharing is conservatively overcounted.
        long weight = add(overhead, FrozenNode.approximateRetainedWeightBytesOf(roots.toArray(FrozenNode[]::new)));
        return new Captured(key, weight);
    }

    private static long stringWeight(String value) { return add(48L, 2L * value.length()); }

    private static long add(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    record Limits(int maximumEntries, long maximumWeightBytes, long maximumEntryWeightBytes) {
        Limits {
            if (maximumEntries < 0 || maximumWeightBytes < 0 || maximumEntryWeightBytes < 0) {
                throw new IllegalArgumentException("Eligibility cache limits must be non-negative");
            }
        }

        static Limits defaults() {
            BlueCachePolicy policy = BlueCachePolicy.boundedDefaults();
            return new Limits(policy.derivedSnapshotMaxEntries(), policy.derivedSnapshotMaxWeightBytes(),
                    policy.maximumDerivedEntryWeightBytes());
        }
    }

    private record DeliveryKey(String documentId, FrozenNode.ResolvedStructuralKey document,
            String channel, String logicalDelivery, long occurrenceOrder) { }
    private record Key(String eventBlueId, FrozenNode.ResolvedStructuralKey event, List<DeliveryKey> deliveries) { }
    private record Captured(Key key, long weight) { }
    private record Entry(boolean[] mask, long weight) { }
}
