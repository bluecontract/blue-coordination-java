package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.SubscriptionState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete durable Contracts subscription state, independent of legacy rows. */
final class ClosureSubscriptionInventory {
    private static final Comparator<Slot> SLOT_ORDER = Comparator
            .comparing(Slot::documentId, EmbeddingBinding.TEXT_ORDER)
            .thenComparing(Slot::rawChannelKey, EmbeddingBinding.TEXT_ORDER);

    private final Map<Slot, SubscriptionState> bySlot;
    private final List<SubscriptionState> states;

    private ClosureSubscriptionInventory(
            Map<Slot, SubscriptionState> rows) {
        List<Map.Entry<Slot, SubscriptionState>> canonical =
                new ArrayList<>(rows.entrySet());
        canonical.sort(Map.Entry.comparingByKey(SLOT_ORDER));
        LinkedHashMap<Slot, SubscriptionState> ordered = new LinkedHashMap<>();
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        for (Map.Entry<Slot, SubscriptionState> entry : canonical) {
            SubscriptionState state = Objects.requireNonNull(
                    entry.getValue(), "subscription state");
            Slot actual = Slot.from(state);
            if (!entry.getKey().equals(actual)) {
                throw new IllegalArgumentException(
                        "Subscription state is stored under the wrong slot");
            }
            if (!identities.add(state.subscriptionIdentity())) {
                throw new IllegalArgumentException(
                        "Duplicate closure subscription identity "
                                + state.subscriptionIdentity());
            }
            ordered.put(entry.getKey(), state);
        }
        this.bySlot = Map.copyOf(ordered);
        this.states = List.copyOf(ordered.values());
    }

    static ClosureSubscriptionInventory empty() {
        return new ClosureSubscriptionInventory(Map.of());
    }

    static ClosureSubscriptionInventory of(
            Collection<SubscriptionState> states) {
        LinkedHashMap<Slot, SubscriptionState> rows = new LinkedHashMap<>();
        for (SubscriptionState state : Objects.requireNonNull(
                states, "states")) {
            SubscriptionState exact = Objects.requireNonNull(
                    state, "subscription state");
            Slot slot = Slot.from(exact);
            if (rows.putIfAbsent(slot, exact) != null) {
                throw new IllegalArgumentException(
                        "Duplicate closure subscription slot " + slot);
            }
        }
        return new ClosureSubscriptionInventory(rows);
    }

    /** Applies one already verified successful result to the durable inventory. */
    ClosureSubscriptionInventory apply(ClosureProcessResult result) {
        ClosureProcessResult verified = Objects.requireNonNull(result, "result");
        if (!verified.commits()) {
            throw new IllegalArgumentException(
                    "Non-success closure result cannot change subscriptions");
        }
        Map<String, String> expectedHeads = expectedInputHeads(
                verified.platformCommitCompanion());
        Map<String, ResultingDocument> resultingDocuments =
                resultingDocuments(verified.resultingDocuments());
        LinkedHashMap<Slot, SubscriptionState> next =
                new LinkedHashMap<>(bySlot);
        for (SubscriptionDelta delta : verified.subscriptionDeltas()) {
            SubscriptionState before = delta.beforeSubscription();
            SubscriptionState after = delta.afterSubscription();
            SubscriptionState representative = after != null ? after : before;
            Slot slot = Slot.from(Objects.requireNonNull(
                    representative, "subscription delta side"));
            if (before != null) {
                requireExpectedInputState(before, expectedHeads);
            }
            SubscriptionState current = next.get(slot);
            if (delta.operation() == SubscriptionDelta.Operation.ADD) {
                if (current != null) {
                    throw new IllegalStateException(
                            "Closure subscription ADD targets a present slot "
                                    + slot);
                }
                next.put(slot, after);
            } else {
                if (current == null) {
                    // Migration bootstrap is safe because the verified before
                    // state is bound to the exact CAS-fenced input head.
                    current = before;
                }
                if (!current.subscriptionIdentity().equals(
                        before.subscriptionIdentity())) {
                    throw new IllegalStateException(
                            "Closure subscription before-state CAS mismatch at "
                                    + slot);
                }
                if (delta.operation() == SubscriptionDelta.Operation.REMOVE) {
                    next.remove(slot);
                } else {
                    next.put(slot, after);
                }
            }
        }
        ClosureSubscriptionInventory applied =
                new ClosureSubscriptionInventory(next);
        applied.requireResultingStates(
                resultingDocuments, verified.graphGeneration());
        return applied;
    }

    ClosureSubscriptionInventory retainingDocuments(
            Collection<DocumentId> documents) {
        Set<String> retained = new LinkedHashSet<>();
        for (DocumentId document : Objects.requireNonNull(
                documents, "documents")) {
            retained.add(Objects.requireNonNull(
                    document, "document").value());
        }
        LinkedHashMap<Slot, SubscriptionState> selected = new LinkedHashMap<>();
        bySlot.forEach((slot, state) -> {
            if (retained.contains(slot.documentId())) {
                selected.put(slot, state);
            }
        });
        return new ClosureSubscriptionInventory(selected);
    }

    List<SubscriptionState> states() {
        return states;
    }

    List<SubscriptionState> statesFor(DocumentId documentId) {
        String selected = Objects.requireNonNull(
                documentId, "documentId").value();
        return states.stream()
                .filter(state -> state.channelOccurrence().managedDocumentId()
                        .value().equals(selected))
                .toList();
    }

    private void requireResultingStates(
            Map<String, ResultingDocument> resultingDocuments,
            long graphGeneration) {
        for (SubscriptionState state : states) {
            String documentId = state.channelOccurrence()
                    .managedDocumentId().value();
            ResultingDocument resulting = resultingDocuments.get(documentId);
            if (resulting == null) {
                continue;
            }
            if (!state.documentBlueId().equals(resulting.afterBlueId())
                    || state.graphGeneration() != graphGeneration
                    || state.componentGeneration()
                    != resulting.componentGeneration()) {
                throw new IllegalStateException(
                        "Closure subscription state is stale after publication "
                                + documentId + "/"
                                + state.channelOccurrence().rawChannelKey());
            }
        }
    }

    private static void requireExpectedInputState(
            SubscriptionState before,
            Map<String, String> expectedHeads) {
        String documentId = before.channelOccurrence()
                .managedDocumentId().value();
        String expected = expectedHeads.get(documentId);
        if (expected == null || !expected.equals(before.documentBlueId())) {
            throw new IllegalStateException(
                    "Closure subscription before state is not bound to the "
                            + "input head for " + documentId);
        }
    }

    private static Map<String, String> expectedInputHeads(
            ClosureCommitCompanion companion) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (ClosureCommitCompanion.InputDocument document
                : Objects.requireNonNull(
                        companion, "platformCommitCompanion")
                        .expectedInputDocuments()) {
            result.put(document.documentId().value(), document.blueId());
        }
        return result;
    }

    private static Map<String, ResultingDocument> resultingDocuments(
            Collection<ResultingDocument> documents) {
        LinkedHashMap<String, ResultingDocument> result = new LinkedHashMap<>();
        for (ResultingDocument document : documents) {
            if (result.put(document.documentId().value(), document) != null) {
                throw new IllegalArgumentException(
                        "Duplicate resulting document "
                                + document.documentId().value());
            }
        }
        return result;
    }

    private record Slot(String documentId, String rawChannelKey) {
        private Slot {
            documentId = requireText(documentId, "documentId");
            rawChannelKey = requireText(rawChannelKey, "rawChannelKey");
        }

        static Slot from(SubscriptionState state) {
            return new Slot(
                    state.channelOccurrence().managedDocumentId().value(),
                    state.channelOccurrence().rawChannelKey());
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
