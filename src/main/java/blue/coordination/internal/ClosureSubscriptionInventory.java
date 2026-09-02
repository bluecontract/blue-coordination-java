package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.ManagedRootSubscriptionSurface;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.SubscriptionState;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Complete durable Contracts subscription state, independent of legacy rows. */
final class ClosureSubscriptionInventory {
    private static final Comparator<Slot> SLOT_ORDER = Comparator
            .comparing(Slot::documentId, EmbeddingBinding.TEXT_ORDER)
            .thenComparing(Slot::rawChannelKey, EmbeddingBinding.TEXT_ORDER);

    private final PersistentOrderedMap<Slot, SubscriptionState> bySlot;
    private final PersistentOrderedMap<String, Slot> slotByIdentity;
    private final PersistentOrderedMap<String,
            PersistentOrderedMap<String, SubscriptionState>> byDocument;
    private final PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<String, EmbeddedDemand>>
            embeddedDemandsByDocument;
    private final int lastOperationComparisons;
    private final int lastOperationCopiedNodes;
    private final int lastOperationVisitedRows;

    private ClosureSubscriptionInventory(
            Indexes indexes,
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<String, EmbeddedDemand>>
                    embeddedDemandsByDocument,
            Work work) {
        Indexes exact = Objects.requireNonNull(indexes, "indexes");
        this.bySlot = exact.bySlot();
        this.slotByIdentity = exact.slotByIdentity();
        this.byDocument = exact.byDocument();
        this.embeddedDemandsByDocument = Objects.requireNonNull(
                embeddedDemandsByDocument,
                "embeddedDemandsByDocument");
        Work exactWork = Objects.requireNonNull(work, "work");
        this.lastOperationComparisons = exactWork.comparisons;
        this.lastOperationCopiedNodes = exactWork.copiedNodes;
        this.lastOperationVisitedRows = exactWork.visitedRows;
    }

    static ClosureSubscriptionInventory empty() {
        return new ClosureSubscriptionInventory(
                Indexes.empty(), emptyEmbeddedDemands(), new Work());
    }

    static ClosureSubscriptionInventory of(
            Collection<SubscriptionState> states) {
        Indexes indexes = Indexes.empty();
        Work work = new Work();
        for (SubscriptionState state : Objects.requireNonNull(
                states, "states")) {
            SubscriptionState exact = Objects.requireNonNull(
                    state, "subscription state");
            Slot slot = Slot.from(exact);
            PersistentOrderedMap.ReadResult<SubscriptionState> existing =
                    indexes.bySlot().read(slot);
            work.read(existing);
            if (existing.found()) {
                throw new IllegalArgumentException(
                        "Duplicate closure subscription slot " + slot);
            }
            indexes = insertAbsent(indexes, exact, work);
        }
        return new ClosureSubscriptionInventory(
                indexes, emptyEmbeddedDemands(), work);
    }

    /** Applies one already verified successful result to the durable inventory. */
    ClosureSubscriptionInventory apply(ClosureProcessResult result) {
        ClosureProcessResult verified = Objects.requireNonNull(result, "result");
        if (!verified.commits()) {
            throw new IllegalArgumentException(
                    "Non-success closure result cannot change subscriptions");
        }
        LinkedHashMap<DocumentId, Long> uniform = new LinkedHashMap<>();
        long expectedGeneration = verified.platformCommitCompanion()
                .expectedInputGraphGeneration();
        verified.platformCommitCompanion().expectedInputDocuments()
                .forEach(document -> uniform.put(
                        DocumentId.of(document.documentId().value()),
                        expectedGeneration));
        return apply(verified, uniform);
    }

    /**
     * Applies a result whose single Contracts graph generation may merge
     * independently retained Coordination cohorts. A lower durable
     * subscription generation is accepted only when its exact per-document
     * graph fence is supplied and every other closed before-state field is
     * identical.
     */
    ClosureSubscriptionInventory apply(
            ClosureProcessResult result,
            Map<DocumentId, Long> expectedGraphGenerations) {
        ClosureProcessResult verified = Objects.requireNonNull(result, "result");
        if (!verified.commits()) {
            throw new IllegalArgumentException(
                    "Non-success closure result cannot change subscriptions");
        }
        Map<DocumentId, Long> exactGraphGenerations = Map.copyOf(
                Objects.requireNonNull(
                        expectedGraphGenerations,
                        "expectedGraphGenerations"));
        Map<String, String> expectedHeads = expectedInputHeads(
                verified.platformCommitCompanion());
        if (!expectedHeads.keySet().containsAll(exactGraphGenerations.keySet()
                .stream().map(DocumentId::value).toList())) {
            throw new IllegalArgumentException(
                    "Graph-generation fences escape the closure result");
        }
        Map<String, ResultingDocument> resultingDocuments =
                resultingDocuments(verified.resultingDocuments());
        Indexes next = new Indexes(
                bySlot, slotByIdentity, byDocument);
        Work work = new Work();
        for (SubscriptionDelta delta : verified.subscriptionDeltas()) {
            SubscriptionState before = delta.beforeSubscription();
            SubscriptionState after = delta.afterSubscription();
            SubscriptionState representative = after != null ? after : before;
            Slot slot = Slot.from(Objects.requireNonNull(
                    representative, "subscription delta side"));
            if (before != null) {
                requireExpectedInputState(before, expectedHeads);
            }
            PersistentOrderedMap.ReadResult<SubscriptionState> currentRead =
                    next.bySlot().read(slot);
            work.read(currentRead);
            SubscriptionState current = currentRead.value();
            if (delta.operation() == SubscriptionDelta.Operation.ADD) {
                if (current != null) {
                    throw new IllegalStateException(
                            "Closure subscription ADD targets a present slot "
                                    + slot);
                }
                next = insertAbsent(
                        next,
                        Objects.requireNonNull(
                                after, "subscription ADD after state"),
                        work);
            } else {
                SubscriptionState exactBefore = Objects.requireNonNull(
                        before, "subscription before state");
                boolean present = current != null;
                if (current == null) {
                    // Migration bootstrap is safe because the verified before
                    // state is bound to the exact CAS-fenced input head.
                    current = exactBefore;
                }
                if (!current.subscriptionIdentity().equals(
                        exactBefore.subscriptionIdentity())) {
                    requireDeterministicGraphMerge(
                            slot,
                            current,
                            exactBefore,
                            exactGraphGenerations,
                            verified.platformCommitCompanion()
                                    .expectedInputGraphGeneration());
                }
                if (delta.operation() == SubscriptionDelta.Operation.REMOVE) {
                    if (present) {
                        next = removePresent(next, slot, current, work);
                    }
                } else {
                    SubscriptionState exactAfter = Objects.requireNonNull(
                            after, "subscription replacement after state");
                    next = present
                            ? replacePresent(
                                    next, slot, current, exactAfter, work)
                            : insertAbsent(next, exactAfter, work);
                }
            }
        }
        next = rebaseAndRequireResultingStates(
                next,
                resultingDocuments,
                verified.graphGeneration(),
                exactGraphGenerations,
                work);
        return new ClosureSubscriptionInventory(
                next, embeddedDemandsByDocument, work);
    }

    private static void requireDeterministicGraphMerge(
            Slot slot,
            SubscriptionState current,
            SubscriptionState expectedBefore,
            Map<DocumentId, Long> exactGraphGenerations,
            long contractsGraphGeneration) {
        DocumentId documentId = DocumentId.of(slot.documentId());
        Long exactGeneration = exactGraphGenerations.get(documentId);
        boolean sameClosedState = current.channelOccurrence()
                        .channelOccurrenceIdentity().equals(
                                expectedBefore.channelOccurrence()
                                        .channelOccurrenceIdentity())
                && current.documentBlueId().equals(
                        expectedBefore.documentBlueId())
                && current.componentGeneration()
                        == expectedBefore.componentGeneration();
        if (exactGeneration == null
                || current.graphGeneration()
                        != exactGeneration.longValue()
                || expectedBefore.graphGeneration()
                        != contractsGraphGeneration
                || current.graphGeneration() >= contractsGraphGeneration
                || !sameClosedState) {
            throw new IllegalStateException(
                    "Closure subscription before-state CAS mismatch at "
                            + slot + ": expected "
                            + expectedBefore.subscriptionIdentity()
                            + " but found "
                            + current.subscriptionIdentity());
        }
    }

    ClosureSubscriptionInventory retainingDocuments(
            Collection<DocumentId> documents) {
        TreeSet<String> retained = new TreeSet<>(
                EmbeddingBinding.TEXT_ORDER);
        for (DocumentId document : Objects.requireNonNull(
                documents, "documents")) {
            retained.add(Objects.requireNonNull(
                    document, "document").value());
        }
        Indexes selected = Indexes.empty();
        PersistentOrderedMap<DocumentId,
                PersistentOrderedMap<String, EmbeddedDemand>>
                selectedDemands = emptyEmbeddedDemands();
        Work work = new Work();
        for (String documentId : retained) {
            DocumentId coordinationId = DocumentId.of(documentId);
            PersistentOrderedMap<String, EmbeddedDemand> demandBucket =
                    embeddedDemandsByDocument.get(coordinationId);
            if (demandBucket != null) {
                selectedDemands = selectedDemands.put(
                        coordinationId, demandBucket).map();
            }
            PersistentOrderedMap.ReadResult<PersistentOrderedMap<String,
                    SubscriptionState>> bucketRead =
                    byDocument.read(documentId);
            work.read(bucketRead);
            if (!bucketRead.found()) {
                continue;
            }
            PersistentOrderedMap<String, SubscriptionState> bucket =
                    bucketRead.value();
            work.visitedRows(bucket.size());
            for (SubscriptionState state : bucket.values()) {
                Slot slot = Slot.from(state);
                PersistentOrderedMap.Mutation<Slot, SubscriptionState>
                        slotMutation = selected.bySlot().put(slot, state);
                work.mutation(slotMutation);
                PersistentOrderedMap.Mutation<String, Slot>
                        identityMutation = selected.slotByIdentity().put(
                                state.subscriptionIdentity(), slot);
                work.mutation(identityMutation);
                selected = new Indexes(
                        slotMutation.map(),
                        identityMutation.map(),
                        selected.byDocument());
            }
            PersistentOrderedMap.Mutation<String,
                    PersistentOrderedMap<String, SubscriptionState>>
                    documentMutation = selected.byDocument().put(
                            documentId, bucket);
            work.mutation(documentMutation);
            selected = new Indexes(
                    selected.bySlot(),
                    selected.slotByIdentity(),
                    documentMutation.map());
        }
        return new ClosureSubscriptionInventory(
                selected, selectedDemands, work);
    }

    List<SubscriptionState> states() {
        return bySlot.values();
    }

    List<SubscriptionState> statesFor(DocumentId documentId) {
        String selected = Objects.requireNonNull(
                documentId, "documentId").value();
        PersistentOrderedMap<String, SubscriptionState> bucket =
                byDocument.get(selected);
        return bucket == null ? List.of() : bucket.values();
    }

    ClosureSubscriptionInventory replaceEmbeddedDemands(
            DocumentId documentId,
            Collection<EmbeddedDemand> demands) {
        DocumentId selectedDocument = Objects.requireNonNull(
                documentId, "documentId");
        PersistentOrderedMap<String, EmbeddedDemand> replacement =
                PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER);
        for (EmbeddedDemand demand : Objects.requireNonNull(
                demands, "demands")) {
            EmbeddedDemand selected = Objects.requireNonNull(
                    demand, "embedded demand");
            if (replacement.containsKey(selected.rawChannelKey())) {
                throw new IllegalArgumentException(
                        "Duplicate embedded demand Channel key "
                                + selected.rawChannelKey());
            }
            replacement = replacement.put(
                    selected.rawChannelKey(), selected).map();
        }
        PersistentOrderedMap<DocumentId,
                PersistentOrderedMap<String, EmbeddedDemand>> updated =
                replacement.isEmpty()
                        ? embeddedDemandsByDocument.remove(
                                selectedDocument).map()
                        : embeddedDemandsByDocument.put(
                                selectedDocument, replacement).map();
        Work retainedWork = new Work();
        retainedWork.comparisons = lastOperationComparisons;
        retainedWork.copiedNodes = lastOperationCopiedNodes;
        retainedWork.visitedRows = lastOperationVisitedRows;
        return new ClosureSubscriptionInventory(
                new Indexes(bySlot, slotByIdentity, byDocument),
                updated,
                retainedWork);
    }

    boolean hasEmbeddedDemand(DocumentId documentId, String sourcePath) {
        PersistentOrderedMap<String, EmbeddedDemand> bucket =
                embeddedDemandsByDocument.get(Objects.requireNonNull(
                        documentId, "documentId"));
        if (bucket == null) {
            return false;
        }
        String candidate = Objects.requireNonNull(sourcePath, "sourcePath");
        for (EmbeddedDemand demand : bucket.values()) {
            if (demand.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    List<EmbeddedDemand> embeddedDemandsFor(DocumentId documentId) {
        PersistentOrderedMap<String, EmbeddedDemand> bucket =
                embeddedDemandsByDocument.get(Objects.requireNonNull(
                        documentId, "documentId"));
        return bucket == null ? List.of() : bucket.values();
    }

    enum EmbeddedDemandMode {
        EXACT,
        ALL_DESCENDANTS,
        COLLECTION_DIRECT,
        COLLECTION_DESCENDANTS
    }

    /** One authenticated internal Channel selector retained by its Root. */
    static final class EmbeddedDemand {
        private final String rawChannelKey;
        private final String selectorPath;
        private final List<String> selectorSegments;
        private final EmbeddedDemandMode mode;
        private final String effectiveRuntimeContributionBlueId;

        EmbeddedDemand(
                String rawChannelKey,
                String selectorPath,
                EmbeddedDemandMode mode,
                String effectiveRuntimeContributionBlueId) {
            this.rawChannelKey = requireText(
                    rawChannelKey, "rawChannelKey");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.selectorPath = canonicalSelectorPath(
                    selectorPath, this.mode);
            this.selectorSegments = List.copyOf(
                    JsonPointer.split(this.selectorPath));
            this.effectiveRuntimeContributionBlueId = requireText(
                    effectiveRuntimeContributionBlueId,
                    "effectiveRuntimeContributionBlueId");
        }

        String rawChannelKey() {
            return rawChannelKey;
        }

        String selectorPath() {
            return selectorPath;
        }

        EmbeddedDemandMode mode() {
            return mode;
        }

        String effectiveRuntimeContributionBlueId() {
            return effectiveRuntimeContributionBlueId;
        }

        boolean matches(String candidatePath) {
            List<String> candidate = JsonPointer.split(requireText(
                    candidatePath, "candidatePath"));
            int cursor = advance(0, candidate);
            return cursor >= 0 && accepts(cursor);
        }

        int advance(int cursor, List<String> decodedSegments) {
            if (cursor < 0) {
                throw new IllegalArgumentException(
                        "demand cursor must be non-negative");
            }
            int selected = cursor;
            for (String segment : Objects.requireNonNull(
                    decodedSegments, "decodedSegments")) {
                if (mode == EmbeddedDemandMode.EXACT) {
                    if (selected >= selectorSegments.size()
                            || !selectorSegments.get(selected)
                                    .equals(segment)) {
                        return -1;
                    }
                    selected++;
                } else if (mode
                        == EmbeddedDemandMode.COLLECTION_DIRECT) {
                    if (selected < selectorSegments.size()
                            && !selectorSegments.get(selected)
                                    .equals(segment)) {
                        return -1;
                    }
                    if (selected >= selectorSegments.size() + 1) {
                        return -1;
                    }
                    selected++;
                } else if (mode
                        == EmbeddedDemandMode.COLLECTION_DESCENDANTS) {
                    if (selected < selectorSegments.size()
                            && !selectorSegments.get(selected)
                                    .equals(segment)) {
                        return -1;
                    }
                    selected = Math.min(
                            selected + 1, selectorSegments.size() + 1);
                } else {
                    selected = 1;
                }
            }
            return selected;
        }

        boolean accepts(int cursor) {
            return switch (mode) {
                case EXACT -> cursor == selectorSegments.size();
                case ALL_DESCENDANTS -> cursor > 0;
                case COLLECTION_DIRECT ->
                        cursor == selectorSegments.size() + 1;
                case COLLECTION_DESCENDANTS ->
                        cursor > selectorSegments.size();
            };
        }

        boolean canContinue(int cursor) {
            return switch (mode) {
                case EXACT -> cursor < selectorSegments.size();
                case ALL_DESCENDANTS, COLLECTION_DESCENDANTS -> true;
                case COLLECTION_DIRECT ->
                        cursor < selectorSegments.size() + 1;
            };
        }

        private static String canonicalSelectorPath(
                String supplied,
                EmbeddedDemandMode suppliedMode) {
            EmbeddedDemandMode selectedMode = Objects.requireNonNull(
                    suppliedMode, "mode");
            String path = requireText(supplied, "selectorPath");
            String canonical = JsonPointer.toPointer(JsonPointer.split(path));
            if (!path.equals(canonical)) {
                throw new IllegalArgumentException(
                        "selectorPath must be a canonical absolute pointer: "
                                + path);
            }
            boolean root = JsonPointer.ROOT.equals(canonical);
            if (root != (selectedMode
                    == EmbeddedDemandMode.ALL_DESCENDANTS)) {
                throw new IllegalArgumentException(
                        "Only ALL_DESCENDANTS uses the Root selector path");
            }
            return canonical;
        }
    }

    static List<EmbeddedDemand> embeddedDemands(
            ManagedRootSubscriptionSurface surface) {
        ManagedRootSubscriptionSurface projected = Objects.requireNonNull(
                surface, "surface");
        Map<String, ManagedRootChannelOccurrence> channels =
                new LinkedHashMap<>();
        for (ManagedRootChannelOccurrence channel
                : projected.channelOccurrences()) {
            channels.put(channel.rawChannelKey(), channel);
        }
        ArrayList<EmbeddedDemand> result = new ArrayList<>();
        for (EffectiveContractSnapshot contract
                : projected.effectiveRootContracts()) {
            boolean embeddedNode = RuntimeBlueIds.EMBEDDED_NODE_CHANNEL.equals(
                    contract.effectiveTypeBlueId());
            boolean embeddedCollection = contract.dispatchFields()
                    .containsKey("collectionPath");
            if (!embeddedNode && !embeddedCollection) {
                continue;
            }
            ManagedRootChannelOccurrence channel = channels.get(
                    contract.key());
            if (channel == null
                    || !channel.effectiveTypeBlueId().equals(
                            contract.effectiveTypeBlueId())
                    || channel.externalSource()
                    || !EffectiveContractSnapshotConstants.Role
                            .PROCESSOR_CHANNEL.equals(contract.role())) {
                throw new IllegalArgumentException(
                        "Embedded Channel projection is incomplete at "
                                + contract.key());
            }
            if (embeddedNode) {
                String sourcePath = contract.dispatchFields().get(
                        "sourcePath");
                result.add(new EmbeddedDemand(
                        channel.rawChannelKey(),
                        sourcePath == null
                                ? JsonPointer.ROOT : sourcePath,
                        sourcePath == null
                                ? EmbeddedDemandMode.ALL_DESCENDANTS
                                : EmbeddedDemandMode.EXACT,
                        channel.effectiveRuntimeContributionBlueId()));
                continue;
            }
            String collectionPath = contract.dispatchFields().get(
                    "collectionPath");
            String descendants = contract.dispatchFields().get(
                    "includeDescendants");
            boolean includeDescendants;
            if (descendants == null || "false".equals(descendants)) {
                includeDescendants = false;
            } else if ("true".equals(descendants)) {
                includeDescendants = true;
            } else {
                throw new IllegalArgumentException(
                        "Embedded Collection Event Channel has invalid "
                                + "includeDescendants at " + contract.key());
            }
            result.add(new EmbeddedDemand(
                    channel.rawChannelKey(),
                    collectionPath,
                    includeDescendants
                            ? EmbeddedDemandMode.COLLECTION_DESCENDANTS
                            : EmbeddedDemandMode.COLLECTION_DIRECT,
                    channel.effectiveRuntimeContributionBlueId()));
        }
        return List.copyOf(result);
    }

    private static PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<String, EmbeddedDemand>>
            emptyEmbeddedDemands() {
        return PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER);
    }

    private static Indexes rebaseAndRequireResultingStates(
            Indexes indexes,
            Map<String, ResultingDocument> resultingDocuments,
            long graphGeneration,
            Map<DocumentId, Long> exactGraphGenerations,
            Work work) {
        Indexes next = indexes;
        for (Map.Entry<String, ResultingDocument> entry
                : resultingDocuments.entrySet()) {
            PersistentOrderedMap.ReadResult<PersistentOrderedMap<String,
                    SubscriptionState>> bucketRead =
                    next.byDocument().read(entry.getKey());
            work.read(bucketRead);
            if (!bucketRead.found()) {
                continue;
            }
            ResultingDocument resulting = entry.getValue();
            work.visitedRows(bucketRead.value().size());
            for (SubscriptionState state : bucketRead.value().values()) {
                if (state.documentBlueId().equals(resulting.afterBlueId())
                        && state.graphGeneration() == graphGeneration
                        && state.componentGeneration()
                        == resulting.componentGeneration()) {
                    continue;
                }
                DocumentId documentId = DocumentId.of(entry.getKey());
                Long exactGeneration = exactGraphGenerations.get(documentId);
                boolean deterministicGraphMerge = exactGeneration != null
                        && state.graphGeneration()
                        == exactGeneration.longValue()
                        && state.graphGeneration() < graphGeneration
                        && state.documentBlueId().equals(
                                resulting.afterBlueId())
                        && state.componentGeneration()
                        == resulting.componentGeneration();
                if (!deterministicGraphMerge) {
                    throw staleResultingState(entry.getKey(), state);
                }
                SubscriptionState rebased = SubscriptionState.identified(
                        state.channelOccurrence(),
                        state.documentBlueId(),
                        graphGeneration,
                        state.componentGeneration());
                Slot slot = Slot.from(state);
                next = replacePresent(next, slot, state, rebased, work);
            }
        }
        return next;
    }

    private static IllegalStateException staleResultingState(
            String documentId,
            SubscriptionState state) {
        return new IllegalStateException(
                "Closure subscription state is stale after publication "
                        + documentId + "/"
                        + state.channelOccurrence().rawChannelKey());
    }

    private static Indexes insertAbsent(
            Indexes indexes,
            SubscriptionState state,
            Work work) {
        SubscriptionState exact = Objects.requireNonNull(
                state, "subscription state");
        Slot slot = Slot.from(exact);
        PersistentOrderedMap.ReadResult<Slot> identityRead =
                indexes.slotByIdentity().read(
                        exact.subscriptionIdentity());
        work.read(identityRead);
        if (identityRead.found()) {
            throw new IllegalArgumentException(
                    "Duplicate closure subscription identity "
                            + exact.subscriptionIdentity());
        }

        PersistentOrderedMap.Mutation<Slot, SubscriptionState>
                slotMutation = indexes.bySlot().put(slot, exact);
        work.mutation(slotMutation);
        PersistentOrderedMap.Mutation<String, Slot> identityMutation =
                indexes.slotByIdentity().put(
                        exact.subscriptionIdentity(), slot);
        work.mutation(identityMutation);

        PersistentOrderedMap.ReadResult<PersistentOrderedMap<String,
                SubscriptionState>> bucketRead =
                indexes.byDocument().read(slot.documentId());
        work.read(bucketRead);
        PersistentOrderedMap<String, SubscriptionState> bucket =
                bucketRead.found()
                        ? bucketRead.value()
                        : PersistentOrderedMap.empty(
                                EmbeddingBinding.TEXT_ORDER);
        PersistentOrderedMap.Mutation<String, SubscriptionState>
                bucketMutation = bucket.put(slot.rawChannelKey(), exact);
        work.mutation(bucketMutation);
        PersistentOrderedMap.Mutation<String,
                PersistentOrderedMap<String, SubscriptionState>>
                documentMutation = indexes.byDocument().put(
                        slot.documentId(), bucketMutation.map());
        work.mutation(documentMutation);
        return new Indexes(
                slotMutation.map(),
                identityMutation.map(),
                documentMutation.map());
    }

    private static Indexes replacePresent(
            Indexes indexes,
            Slot slot,
            SubscriptionState current,
            SubscriptionState after,
            Work work) {
        SubscriptionState exactAfter = Objects.requireNonNull(
                after, "subscription replacement after state");
        if (!slot.equals(Slot.from(exactAfter))) {
            throw new IllegalArgumentException(
                    "Subscription replacement changes its slot");
        }
        PersistentOrderedMap.ReadResult<Slot> afterIdentity =
                indexes.slotByIdentity().read(
                        exactAfter.subscriptionIdentity());
        work.read(afterIdentity);
        if (afterIdentity.found() && !slot.equals(afterIdentity.value())) {
            throw new IllegalArgumentException(
                    "Duplicate closure subscription identity "
                            + exactAfter.subscriptionIdentity());
        }

        PersistentOrderedMap.Mutation<Slot, SubscriptionState>
                slotMutation = indexes.bySlot().put(slot, exactAfter);
        work.mutation(slotMutation);
        PersistentOrderedMap<String, Slot> identities =
                indexes.slotByIdentity();
        if (!current.subscriptionIdentity().equals(
                exactAfter.subscriptionIdentity())) {
            PersistentOrderedMap.ReadResult<Slot> currentIdentity =
                    identities.read(current.subscriptionIdentity());
            work.read(currentIdentity);
            if (!currentIdentity.found()
                    || !slot.equals(currentIdentity.value())) {
                throw new IllegalStateException(
                        "Closure subscription identity index is inconsistent "
                                + current.subscriptionIdentity());
            }
            PersistentOrderedMap.Mutation<String, Slot> removed =
                    identities.remove(current.subscriptionIdentity());
            work.mutation(removed);
            PersistentOrderedMap.Mutation<String, Slot> added =
                    removed.map().put(
                            exactAfter.subscriptionIdentity(), slot);
            work.mutation(added);
            identities = added.map();
        }

        PersistentOrderedMap.ReadResult<PersistentOrderedMap<String,
                SubscriptionState>> bucketRead =
                indexes.byDocument().read(slot.documentId());
        work.read(bucketRead);
        if (!bucketRead.found()) {
            throw new IllegalStateException(
                    "Closure subscription document index is missing "
                            + slot.documentId());
        }
        PersistentOrderedMap.ReadResult<SubscriptionState> indexedCurrent =
                bucketRead.value().read(slot.rawChannelKey());
        work.read(indexedCurrent);
        if (!indexedCurrent.found()
                || !indexedCurrent.value().subscriptionIdentity().equals(
                        current.subscriptionIdentity())) {
            throw new IllegalStateException(
                    "Closure subscription document index is inconsistent "
                            + slot);
        }
        PersistentOrderedMap.Mutation<String, SubscriptionState>
                bucketMutation = bucketRead.value().put(
                        slot.rawChannelKey(), exactAfter);
        work.mutation(bucketMutation);
        PersistentOrderedMap.Mutation<String,
                PersistentOrderedMap<String, SubscriptionState>>
                documentMutation = indexes.byDocument().put(
                        slot.documentId(), bucketMutation.map());
        work.mutation(documentMutation);
        return new Indexes(
                slotMutation.map(), identities, documentMutation.map());
    }

    private static Indexes removePresent(
            Indexes indexes,
            Slot slot,
            SubscriptionState current,
            Work work) {
        PersistentOrderedMap.Mutation<Slot, SubscriptionState>
                slotMutation = indexes.bySlot().remove(slot);
        work.mutation(slotMutation);
        if (!slotMutation.changed()) {
            throw new IllegalStateException(
                    "Closure subscription slot index is missing " + slot);
        }

        PersistentOrderedMap.ReadResult<Slot> identityRead =
                indexes.slotByIdentity().read(
                        current.subscriptionIdentity());
        work.read(identityRead);
        if (!identityRead.found() || !slot.equals(identityRead.value())) {
            throw new IllegalStateException(
                    "Closure subscription identity index is inconsistent "
                            + current.subscriptionIdentity());
        }
        PersistentOrderedMap.Mutation<String, Slot> identityMutation =
                indexes.slotByIdentity().remove(
                        current.subscriptionIdentity());
        work.mutation(identityMutation);

        PersistentOrderedMap.ReadResult<PersistentOrderedMap<String,
                SubscriptionState>> bucketRead =
                indexes.byDocument().read(slot.documentId());
        work.read(bucketRead);
        if (!bucketRead.found()) {
            throw new IllegalStateException(
                    "Closure subscription document index is missing "
                            + slot.documentId());
        }
        PersistentOrderedMap.Mutation<String, SubscriptionState>
                bucketMutation = bucketRead.value().remove(
                        slot.rawChannelKey());
        work.mutation(bucketMutation);
        if (!bucketMutation.changed()) {
            throw new IllegalStateException(
                    "Closure subscription document slot is missing " + slot);
        }
        PersistentOrderedMap.Mutation<String,
                PersistentOrderedMap<String, SubscriptionState>>
                documentMutation = bucketMutation.map().isEmpty()
                        ? indexes.byDocument().remove(slot.documentId())
                        : indexes.byDocument().put(
                                slot.documentId(), bucketMutation.map());
        work.mutation(documentMutation);
        return new Indexes(
                slotMutation.map(),
                identityMutation.map(),
                documentMutation.map());
    }

    /** Comparator calls across persistent slot/identity/document indexes. */
    int lastOperationComparisonsForTesting() {
        return lastOperationComparisons;
    }

    /** Persistent tree nodes allocated across every maintained index. */
    int lastOperationCopiedNodesForTesting() {
        return lastOperationCopiedNodes;
    }

    /** Rows explicitly enumerated inside selected document buckets. */
    int lastOperationVisitedRowsForTesting() {
        return lastOperationVisitedRows;
    }

    int slotLookupStepsForTesting(
            DocumentId documentId,
            String rawChannelKey) {
        return bySlot.lookupSteps(new Slot(
                Objects.requireNonNull(documentId, "documentId").value(),
                rawChannelKey));
    }

    Object slotRootIdentityForTesting() {
        return bySlot.rootIdentityForTesting();
    }

    int sharedSlotNodeCountForTesting(
            ClosureSubscriptionInventory other) {
        return bySlot.sharedNodeCountForTesting(
                Objects.requireNonNull(other, "other").bySlot);
    }

    void assertStructurallyValidForTesting() {
        bySlot.assertStructurallyValid();
        slotByIdentity.assertStructurallyValid();
        byDocument.assertStructurallyValid();
        for (SubscriptionState state : bySlot.values()) {
            Slot slot = Slot.from(state);
            if (!slot.equals(slotByIdentity.get(
                    state.subscriptionIdentity()))) {
                throw new IllegalStateException(
                        "Closure subscription identity index is inconsistent "
                                + state.subscriptionIdentity());
            }
            PersistentOrderedMap<String, SubscriptionState> bucket =
                    byDocument.get(slot.documentId());
            if (bucket == null
                    || bucket.get(slot.rawChannelKey()) != state) {
                throw new IllegalStateException(
                        "Closure subscription document index is inconsistent "
                                + slot);
            }
        }
        for (PersistentOrderedMap<String, SubscriptionState> bucket
                : byDocument.values()) {
            bucket.assertStructurallyValid();
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

    private record Indexes(
            PersistentOrderedMap<Slot, SubscriptionState> bySlot,
            PersistentOrderedMap<String, Slot> slotByIdentity,
            PersistentOrderedMap<String,
                    PersistentOrderedMap<String, SubscriptionState>>
                    byDocument) {
        private Indexes {
            bySlot = Objects.requireNonNull(bySlot, "bySlot");
            slotByIdentity = Objects.requireNonNull(
                    slotByIdentity, "slotByIdentity");
            byDocument = Objects.requireNonNull(
                    byDocument, "byDocument");
        }

        private static Indexes empty() {
            return new Indexes(
                    PersistentOrderedMap.empty(SLOT_ORDER),
                    PersistentOrderedMap.empty(
                            EmbeddingBinding.TEXT_ORDER),
                    PersistentOrderedMap.empty(
                            EmbeddingBinding.TEXT_ORDER));
        }
    }

    private static final class Work {
        private int comparisons;
        private int copiedNodes;
        private int visitedRows;

        private void read(PersistentOrderedMap.ReadResult<?> read) {
            comparisons = Math.addExact(
                    comparisons,
                    Objects.requireNonNull(read, "read").comparisons());
        }

        private void mutation(PersistentOrderedMap.Mutation<?, ?> mutation) {
            PersistentOrderedMap.Mutation<?, ?> exact =
                    Objects.requireNonNull(mutation, "mutation");
            comparisons = Math.addExact(
                    comparisons, exact.comparisons());
            copiedNodes = Math.addExact(
                    copiedNodes, exact.copiedNodes());
        }

        private void visitedRows(int count) {
            if (count < 0) {
                throw new IllegalArgumentException(
                        "visited row count must be non-negative");
            }
            visitedRows = Math.addExact(visitedRows, count);
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
