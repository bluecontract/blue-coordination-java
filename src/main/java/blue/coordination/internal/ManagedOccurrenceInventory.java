package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.identity.BlueIds;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable complete inventory of managed Process Embedded occurrence rows.
 *
 * <p>The inventory retains Contracts-owned active graph edges and inactive
 * reservations in one canonical collection. Transition application is atomic
 * because it always constructs a replacement inventory; a rejected transition
 * cannot mutate the captured input. One call represents one Contracts
 * invocation boundary, so a source/path may have at most one transition in
 * that call.</p>
 */
final class ManagedOccurrenceInventory {
    private static final Comparator<OccurrenceKey> KEY_ORDER = Comparator
            .comparing((OccurrenceKey key) ->
                            key.sourceDocumentId().value(),
                    EmbeddingBinding.TEXT_ORDER)
            .thenComparing(OccurrenceKey::sourcePath,
                    EmbeddingBinding.TEXT_ORDER);
    private static final Comparator<RowOrderKey> ROW_ORDER = Comparator
            .comparing(RowOrderKey::occurrenceIdentity,
                    EmbeddingBinding.TEXT_ORDER)
            .thenComparing(RowOrderKey::bindingIdentity,
                    EmbeddingBinding.TEXT_ORDER);
    private static final ManagedOccurrenceInventory EMPTY =
            new ManagedOccurrenceInventory();

    private final PersistentOrderedMap<OccurrenceKey,
            ManagedOccurrenceBinding> rowsBySourcePath;
    private final PersistentOrderedMap<RowOrderKey,
            ManagedOccurrenceBinding> rowsByCanonicalOrder;
    private final PersistentOrderedMap<RowOrderKey,
            ManagedOccurrenceBinding> activeRowsByCanonicalOrder;
    private final PersistentOrderedMap<String, OccurrenceKey>
            keysByOccurrenceIdentity;
    private final PersistentOrderedMap<String, OccurrenceKey>
            keysByBindingIdentity;
    private final PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding>>
            rowsByDocument;
    private final PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding>>
            rowsBySourceDocument;
    private final PersistentOrderedMap<DocumentId,
            PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding>>
            activeRowsBySourceDocument;

    private ManagedOccurrenceInventory() {
        this(
                PersistentOrderedMap.empty(KEY_ORDER),
                PersistentOrderedMap.empty(ROW_ORDER),
                PersistentOrderedMap.empty(ROW_ORDER),
                PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER),
                PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER),
                PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER));
    }

    private ManagedOccurrenceInventory(
            PersistentOrderedMap<OccurrenceKey, ManagedOccurrenceBinding>
                    rowsBySourcePath,
            PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding>
                    rowsByCanonicalOrder,
            PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding>
                    activeRowsByCanonicalOrder,
            PersistentOrderedMap<String, OccurrenceKey>
                    keysByOccurrenceIdentity,
            PersistentOrderedMap<String, OccurrenceKey>
                    keysByBindingIdentity,
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<RowOrderKey,
                            ManagedOccurrenceBinding>> rowsByDocument,
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<RowOrderKey,
                            ManagedOccurrenceBinding>> rowsBySourceDocument,
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<RowOrderKey,
                            ManagedOccurrenceBinding>>
                    activeRowsBySourceDocument) {
        this.rowsBySourcePath = Objects.requireNonNull(
                rowsBySourcePath, "rowsBySourcePath");
        this.rowsByCanonicalOrder = Objects.requireNonNull(
                rowsByCanonicalOrder, "rowsByCanonicalOrder");
        this.activeRowsByCanonicalOrder = Objects.requireNonNull(
                activeRowsByCanonicalOrder, "activeRowsByCanonicalOrder");
        this.keysByOccurrenceIdentity = Objects.requireNonNull(
                keysByOccurrenceIdentity, "keysByOccurrenceIdentity");
        this.keysByBindingIdentity = Objects.requireNonNull(
                keysByBindingIdentity, "keysByBindingIdentity");
        this.rowsByDocument = Objects.requireNonNull(
                rowsByDocument, "rowsByDocument");
        this.rowsBySourceDocument = Objects.requireNonNull(
                rowsBySourceDocument, "rowsBySourceDocument");
        this.activeRowsBySourceDocument = Objects.requireNonNull(
                activeRowsBySourceDocument,
                "activeRowsBySourceDocument");
    }

    /** Returns the canonical empty inventory. */
    static ManagedOccurrenceInventory empty() {
        return EMPTY;
    }

    /** Captures and verifies all Contracts-owned active and inactive rows. */
    static ManagedOccurrenceInventory of(
            Collection<ManagedOccurrenceBinding> rows) {
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            return EMPTY;
        }
        ArrayList<ManagedOccurrenceBinding> canonical = new ArrayList<>(
                rows.size());
        for (ManagedOccurrenceBinding row : rows) {
            canonical.add(verifyLoadedRow(row));
        }
        canonical.sort(Comparator.naturalOrder());
        IndexWork ignored = new IndexWork();
        DeltaBuilder builder = new DeltaBuilder(EMPTY, ignored);
        for (ManagedOccurrenceBinding row : canonical) {
            builder.add(row);
        }
        return builder.build();
    }

    /** Every row in canonical Contracts occurrence-identity order. */
    List<ManagedOccurrenceBinding> rows() {
        return rowsByCanonicalOrder.values();
    }

    /** Only rows contributing graph edges, in canonical row order. */
    List<ManagedOccurrenceBinding> activeRows() {
        return activeRowsByCanonicalOrder.values();
    }

    /** Every source or target lineage named by any retained row. */
    List<DocumentId> documentIds() {
        return rowsByDocument.keys();
    }

    /** All canonical occurrence rows touching one managed lineage. */
    List<ManagedOccurrenceBinding> rowsTouching(DocumentId documentId) {
        PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding> bucket =
                rowsByDocument.get(Objects.requireNonNull(
                        documentId, "documentId"));
        return bucket == null ? List.of() : bucket.values();
    }

    /** Active authored edges whose source is one managed lineage. */
    List<ManagedOccurrenceBinding> activeRowsFrom(DocumentId documentId) {
        return activeRowsFromRead(documentId).rows();
    }

    /**
     * Opens only the exact active-source bucket and reports its real index work.
     */
    RowsRead activeRowsFromRead(DocumentId documentId) {
        PersistentOrderedMap.ReadResult<PersistentOrderedMap<RowOrderKey,
                ManagedOccurrenceBinding>> read = activeRowsBySourceDocument
                .read(Objects.requireNonNull(documentId, "documentId"));
        List<ManagedOccurrenceBinding> rows = read.found()
                ? read.value().values() : List.of();
        return new RowsRead(rows, read.comparisons(), rows.size(), 0);
    }

    /** All active and inactive retained rows sourced by one lineage. */
    List<ManagedOccurrenceBinding> rowsFrom(DocumentId documentId) {
        PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding> bucket =
                rowsBySourceDocument.get(Objects.requireNonNull(
                        documentId, "documentId"));
        return bucket == null ? List.of() : bucket.values();
    }

    record RowsRead(
            List<ManagedOccurrenceBinding> rows,
            int indexComparisons,
            int occurrenceRowsRead,
            int unrelatedDocumentReads) {
        RowsRead {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            if (indexComparisons < 0 || occurrenceRowsRead < 0
                    || unrelatedDocumentReads < 0) {
                throw new IllegalArgumentException(
                        "Occurrence read counters must be non-negative");
            }
        }
    }

    /** Returns the unique retained row for one source/path. */
    ManagedOccurrenceBinding row(
            DocumentId sourceDocumentId,
            String sourcePath) {
        OccurrenceKey key = OccurrenceKey.of(sourceDocumentId, sourcePath);
        ManagedOccurrenceBinding selected = rowsBySourcePath.get(key);
        if (selected == null) {
            throw new IllegalArgumentException(
                    "Unknown managed occurrence " + key);
        }
        return selected;
    }

    /** Finds the retained row for one source/path without exposing the map. */
    Optional<ManagedOccurrenceBinding> find(
            DocumentId sourceDocumentId,
            String sourcePath) {
        return Optional.ofNullable(rowsBySourcePath.get(
                OccurrenceKey.of(sourceDocumentId, sourcePath)));
    }

    /**
     * Replaces the complete retained occurrence surface of exact sources.
     *
     * <p>Only source buckets named by {@code affectedSourceDocumentIds} are
     * opened. Existing rows for changed sources are removed and supplied rows
     * for those sources are inserted through persistent exact indexes. Rows
     * belonging to every other source remain structurally shared. The result
     * reports comparator calls and actual AVL node allocations performed by
     * persistent-index operations, plus committed rows read from the selected
     * source buckets.</p>
     */
    DeltaResult replaceSources(
            Collection<DocumentId> affectedSourceDocumentIds,
            Collection<ManagedOccurrenceBinding> replacementRows) {
        Objects.requireNonNull(
                affectedSourceDocumentIds,
                "affectedSourceDocumentIds");
        Objects.requireNonNull(replacementRows, "replacementRows");
        TreeSet<DocumentId> affected = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId documentId : affectedSourceDocumentIds) {
            affected.add(validateDocumentId(
                    documentId, "affectedSourceDocumentId"));
        }

        ArrayList<ManagedOccurrenceBinding> canonicalReplacements =
                new ArrayList<>(replacementRows.size());
        TreeSet<OccurrenceKey> replacementKeys = new TreeSet<>(KEY_ORDER);
        TreeSet<String> occurrenceIdentities = new TreeSet<>(
                EmbeddingBinding.TEXT_ORDER);
        TreeSet<String> bindingIdentities = new TreeSet<>(
                EmbeddingBinding.TEXT_ORDER);
        for (ManagedOccurrenceBinding supplied : replacementRows) {
            ManagedOccurrenceBinding row = verifyLoadedRow(supplied);
            DocumentId source = toCoordinationDocumentId(
                    row.sourceDocumentId());
            if (!affected.contains(source)) {
                throw new IllegalArgumentException(
                        "Replacement row has an unaffected source " + source);
            }
            if (!replacementKeys.add(key(row))) {
                throw new IllegalArgumentException(
                        "More than one replacement row for source/path "
                                + key(row));
            }
            if (!occurrenceIdentities.add(row.occurrenceIdentity())) {
                throw new IllegalArgumentException(
                        "Duplicate replacement occurrence identity "
                                + row.occurrenceIdentity());
            }
            if (!bindingIdentities.add(row.bindingIdentity())) {
                throw new IllegalArgumentException(
                        "Duplicate replacement binding identity "
                                + row.bindingIdentity());
            }
            canonicalReplacements.add(row);
        }
        if (affected.isEmpty()) {
            if (!canonicalReplacements.isEmpty()) {
                throw new IllegalArgumentException(
                        "Replacement rows require an affected source");
            }
            return DeltaResult.unchanged(this, DeltaMetrics.ZERO);
        }
        canonicalReplacements.sort(Comparator.naturalOrder());

        TreeMap<DocumentId, List<ManagedOccurrenceBinding>>
                replacementsBySource = new TreeMap<>(
                        EmbeddingBinding.DOCUMENT_ORDER);
        for (ManagedOccurrenceBinding row : canonicalReplacements) {
            replacementsBySource.computeIfAbsent(
                    toCoordinationDocumentId(row.sourceDocumentId()),
                    ignored -> new ArrayList<>()).add(row);
        }

        IndexWork work = new IndexWork();
        TreeMap<DocumentId, SourceReplacement> changedSources =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId source : affected) {
            PersistentOrderedMap.ReadResult<PersistentOrderedMap<RowOrderKey,
                    ManagedOccurrenceBinding>> read =
                    rowsBySourceDocument.read(source);
            work.add(read);
            List<ManagedOccurrenceBinding> before = read.found()
                    ? read.value().values()
                    : List.of();
            work.rowsRead(before.size());
            List<ManagedOccurrenceBinding> after =
                    replacementsBySource.getOrDefault(source, List.of());
            if (!sameRows(before, after)) {
                changedSources.put(
                        source, new SourceReplacement(before, after));
            }
        }
        if (changedSources.isEmpty()) {
            return DeltaResult.unchanged(this, work.metrics());
        }

        DeltaBuilder builder = new DeltaBuilder(this, work);
        for (SourceReplacement replacement : changedSources.values()) {
            replacement.before().forEach(builder::remove);
        }
        for (SourceReplacement replacement : changedSources.values()) {
            replacement.after().forEach(builder::add);
        }
        return new DeltaResult(builder.build(), true, work.metrics());
    }

    /**
     * Applies one invocation's occurrence transitions atomically.
     *
     * <p>Retirement allocates the same-lineage inactive successor at exactly
     * generation plus one. Activation consumes an already committed inactive
     * row and preserves its generation and occurrence identity. An active
     * REBIND may atomically select a different target lineage by allocating
     * the next activation generation and fresh Contracts-owned occurrence and
     * binding identities. Supplying two changes for one source/path is
     * rejected, which excludes same-invocation remove-then-re-add. Every
     * resulting identity is derived by Contracts, never by Coordination.</p>
     */
    ManagedOccurrenceInventory apply(Collection<Change> changes) {
        return applyDelta(changes).inventory();
    }

    /**
     * Applies exact source/path transitions and reports their structural work.
     */
    DeltaResult applyDelta(Collection<Change> changes) {
        Objects.requireNonNull(changes, "changes");
        if (changes.isEmpty()) {
            return DeltaResult.unchanged(this, DeltaMetrics.ZERO);
        }
        TreeMap<OccurrenceKey, Change> canonicalChanges =
                new TreeMap<>(KEY_ORDER);
        for (Change change : changes) {
            Change checked = Objects.requireNonNull(change, "change");
            Change duplicate = canonicalChanges.putIfAbsent(
                    checked.key(), checked);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "One invocation cannot transition source/path twice: "
                                + checked.key());
            }
        }

        IndexWork work = new IndexWork();
        TreeMap<OccurrenceKey, RowReplacement> replacements =
                new TreeMap<>(KEY_ORDER);
        for (Change change : canonicalChanges.values()) {
            PersistentOrderedMap.ReadResult<ManagedOccurrenceBinding> read =
                    rowsBySourcePath.read(change.key());
            work.add(read);
            ManagedOccurrenceBinding current = read.value();
            if (current == null) {
                throw new IllegalArgumentException(
                        "Transition has no committed occurrence row: "
                                + change.key());
            }
            work.rowsRead(1);
            ManagedOccurrenceBinding replacement = switch (change.kind()) {
                case RETIRE -> retire(
                        requireReservedTarget(current, change),
                        change.expectedTargetBlueId());
                case ACTIVATE -> activate(
                        requireReservedTarget(current, change),
                        change.expectedTargetBlueId());
                case REBIND -> rebind(
                        current,
                        change.targetDocumentId(),
                        change.expectedTargetBlueId());
            };
            if (!sameRow(replacement, current)) {
                replacements.put(
                        change.key(),
                        new RowReplacement(current, replacement));
            }
        }
        if (replacements.isEmpty()) {
            return DeltaResult.unchanged(this, work.metrics());
        }

        DeltaBuilder builder = new DeltaBuilder(this, work);
        replacements.values().forEach(
                replacement -> builder.remove(replacement.before()));
        replacements.values().forEach(
                replacement -> builder.add(replacement.after()));
        return new DeltaResult(builder.build(), true, work.metrics());
    }

    /**
     * Exhaustive cross-index validation used only by focused structure tests.
     */
    void assertStructurallyValid() {
        rowsBySourcePath.assertStructurallyValid();
        rowsByCanonicalOrder.assertStructurallyValid();
        activeRowsByCanonicalOrder.assertStructurallyValid();
        keysByOccurrenceIdentity.assertStructurallyValid();
        keysByBindingIdentity.assertStructurallyValid();
        assertBucketsValid(rowsByDocument);
        assertBucketsValid(rowsBySourceDocument);
        assertBucketsValid(activeRowsBySourceDocument);
        if (rowsBySourcePath.size() != rowsByCanonicalOrder.size()
                || rowsBySourcePath.size()
                        != keysByOccurrenceIdentity.size()
                || rowsBySourcePath.size() != keysByBindingIdentity.size()) {
            throw new IllegalStateException(
                    "Occurrence exact-index cardinalities disagree");
        }

        int activeCount = 0;
        int touchingCount = 0;
        for (ManagedOccurrenceBinding row : rowsBySourcePath.values()) {
            OccurrenceKey occurrenceKey = key(row);
            RowOrderKey rowOrderKey = RowOrderKey.from(row);
            requireSameIndexedRow(
                    rowsByCanonicalOrder.get(rowOrderKey), row,
                    "canonical row");
            if (!occurrenceKey.equals(keysByOccurrenceIdentity.get(
                    row.occurrenceIdentity()))
                    || !occurrenceKey.equals(keysByBindingIdentity.get(
                            row.bindingIdentity()))) {
                throw new IllegalStateException(
                        "Occurrence identity indexes disagree");
            }
            DocumentId source = toCoordinationDocumentId(
                    row.sourceDocumentId());
            DocumentId target = toCoordinationDocumentId(
                    row.targetDocumentId());
            requireBucketRow(
                    rowsBySourceDocument, source, rowOrderKey, row,
                    "source");
            requireBucketRow(
                    rowsByDocument, source, rowOrderKey, row,
                    "touching source");
            touchingCount = Math.addExact(touchingCount, 1);
            if (!source.equals(target)) {
                requireBucketRow(
                        rowsByDocument, target, rowOrderKey, row,
                        "touching target");
                touchingCount = Math.addExact(touchingCount, 1);
            }
            if (row.active()) {
                activeCount = Math.addExact(activeCount, 1);
                requireSameIndexedRow(
                        activeRowsByCanonicalOrder.get(rowOrderKey), row,
                        "active canonical row");
                requireBucketRow(
                        activeRowsBySourceDocument,
                        source,
                        rowOrderKey,
                        row,
                        "active source");
            } else if (activeRowsByCanonicalOrder.get(rowOrderKey) != null) {
                throw new IllegalStateException(
                        "Inactive occurrence is present in an active index");
            }
        }
        if (sumBucketRows(rowsBySourceDocument)
                        != rowsBySourcePath.size()
                || sumBucketRows(rowsByDocument) != touchingCount
                || activeCount != activeRowsByCanonicalOrder.size()
                || sumBucketRows(activeRowsBySourceDocument)
                        != activeCount) {
            throw new IllegalStateException(
                    "Occurrence bucket-index cardinalities disagree");
        }
    }

    /** Package-private seam proving primary-index structural sharing. */
    int sharedSourcePathNodeCountForTesting(
            ManagedOccurrenceInventory other) {
        return rowsBySourcePath.sharedNodeCountForTesting(
                Objects.requireNonNull(other, "other").rowsBySourcePath);
    }

    /** Package-private seam proving canonical-index structural sharing. */
    int sharedCanonicalNodeCountForTesting(
            ManagedOccurrenceInventory other) {
        return rowsByCanonicalOrder.sharedNodeCountForTesting(
                Objects.requireNonNull(other, "other")
                        .rowsByCanonicalOrder);
    }

    private static ManagedOccurrenceBinding retire(
            ManagedOccurrenceBinding current,
            String expectedTargetBlueId) {
        if (!current.active()) {
            throw new IllegalStateException(
                    "Only an active occurrence can be retired: "
                            + key(current));
        }
        ScopeAddress successorAddress = nextGenerationAddress(current);
        ManagedOccurrenceBinding successor = ManagedOccurrenceBinding.derived(
                current.bindingPolicyIdentity(),
                current.sourceDocumentId(),
                successorAddress,
                current.targetDocumentId(),
                expectedTargetBlueId,
                false,
                null);
        if (successor.occurrenceIdentity().equals(
                    current.occurrenceIdentity())
                || successor.bindingIdentity().equals(
                        current.bindingIdentity())) {
            throw new IllegalStateException(
                    "Retirement successor did not allocate fresh identities");
        }
        return successor;
    }

    private static ManagedOccurrenceBinding activate(
            ManagedOccurrenceBinding current,
            String expectedTargetBlueId) {
        if (current.active()) {
            throw new IllegalStateException(
                    "Occurrence is already active: " + key(current));
        }
        if (current.pendingHistoricalEpoch() != null) {
            throw new IllegalStateException(
                    "Historical occurrence cannot activate before catch-up: "
                            + key(current));
        }
        ManagedOccurrenceBinding activated = ManagedOccurrenceBinding.derived(
                current.bindingPolicyIdentity(),
                current.sourceDocumentId(),
                current.sourceAddress(),
                current.targetDocumentId(),
                expectedTargetBlueId,
                true,
                null);
        if (!activated.occurrenceIdentity().equals(
                current.occurrenceIdentity())) {
            throw new IllegalStateException(
                    "Activation changed stable occurrence identity");
        }
        return activated;
    }

    private static ManagedOccurrenceBinding rebind(
            ManagedOccurrenceBinding current,
            DocumentId targetDocumentId,
            String expectedTargetBlueId) {
        if (!current.targetDocumentId().value().equals(
                targetDocumentId.value())) {
            return rebindDifferentLineage(
                    current, targetDocumentId, expectedTargetBlueId);
        }
        if (current.expectedTargetBlueId().equals(expectedTargetBlueId)) {
            return current;
        }
        ManagedOccurrenceBinding rebound = ManagedOccurrenceBinding.derived(
                current.bindingPolicyIdentity(),
                current.sourceDocumentId(),
                current.sourceAddress(),
                current.targetDocumentId(),
                expectedTargetBlueId,
                current.active(),
                current.pendingHistoricalEpoch());
        if (!rebound.occurrenceIdentity().equals(
                current.occurrenceIdentity())) {
            throw new IllegalStateException(
                    "Same-lineage rebind changed occurrence identity");
        }
        if (rebound.bindingIdentity().equals(current.bindingIdentity())) {
            throw new IllegalStateException(
                    "Changed exact target state retained binding identity");
        }
        return rebound;
    }

    private static ManagedOccurrenceBinding rebindDifferentLineage(
            ManagedOccurrenceBinding current,
            DocumentId targetDocumentId,
            String expectedTargetBlueId) {
        if (current.pendingHistoricalEpoch() != null) {
            throw new IllegalStateException(
                    "Historical occurrence cannot retarget before catch-up: "
                            + key(current));
        }
        if (!current.active()) {
            throw new IllegalStateException(
                    "Only an active occurrence can retarget to a different "
                            + "lineage: " + key(current));
        }
        ScopeAddress nextAddress = nextGenerationAddress(current);
        ManagedOccurrenceBinding rebound = ManagedOccurrenceBinding.derived(
                current.bindingPolicyIdentity(),
                current.sourceDocumentId(),
                nextAddress,
                contractsDocumentId(targetDocumentId),
                expectedTargetBlueId,
                true,
                null);
        if (rebound.activationGeneration()
                        != current.activationGeneration() + 1L
                || rebound.targetDocumentId().equals(
                        current.targetDocumentId())
                || !rebound.targetDocumentId().value().equals(
                        targetDocumentId.value())
                || !rebound.expectedTargetBlueId().equals(
                        expectedTargetBlueId)
                || rebound.occurrenceIdentity().equals(
                        current.occurrenceIdentity())
                || rebound.bindingIdentity().equals(
                        current.bindingIdentity())) {
            throw new IllegalStateException(
                    "Different-lineage rebind did not allocate the exact "
                            + "fresh next-generation occurrence");
        }
        return rebound;
    }

    private static ManagedOccurrenceBinding requireReservedTarget(
            ManagedOccurrenceBinding current,
            Change change) {
        if (!current.targetDocumentId().value().equals(
                change.targetDocumentId().value())) {
            throw new IllegalStateException(
                    change.kind() + " cannot change the reserved target "
                            + "lineage for " + change.key());
        }
        return current;
    }

    private static ScopeAddress nextGenerationAddress(
            ManagedOccurrenceBinding current) {
        try {
            return ScopeAddress.embedded(
                    current.sourcePath(),
                    Math.addExact(current.activationGeneration(), 1L));
        } catch (ArithmeticException | IllegalArgumentException rejected) {
            throw new IllegalStateException(
                    "Occurrence generation exceeds the portable safe-integer "
                            + "range at " + key(current), rejected);
        }
    }

    /** Exact persistent-index work performed by one inventory delta. */
    record DeltaMetrics(
            long indexComparisons,
            long nodeAllocations,
            long rowsRead) {
        private static final DeltaMetrics ZERO =
                new DeltaMetrics(0L, 0L, 0L);

        DeltaMetrics {
            if (indexComparisons < 0L
                    || nodeAllocations < 0L
                    || rowsRead < 0L) {
                throw new IllegalArgumentException(
                        "Delta metrics must be non-negative");
            }
        }
    }

    /** Immutable result of one exact inventory delta. */
    record DeltaResult(
            ManagedOccurrenceInventory inventory,
            boolean changed,
            DeltaMetrics metrics) {
        DeltaResult {
            inventory = Objects.requireNonNull(inventory, "inventory");
            metrics = Objects.requireNonNull(metrics, "metrics");
        }

        private static DeltaResult unchanged(
                ManagedOccurrenceInventory inventory,
                DeltaMetrics metrics) {
            return new DeltaResult(inventory, false, metrics);
        }
    }

    private record RowOrderKey(
            String occurrenceIdentity,
            String bindingIdentity) {
        private RowOrderKey {
            occurrenceIdentity = Objects.requireNonNull(
                    occurrenceIdentity, "occurrenceIdentity");
            bindingIdentity = Objects.requireNonNull(
                    bindingIdentity, "bindingIdentity");
        }

        private static RowOrderKey from(
                ManagedOccurrenceBinding row) {
            ManagedOccurrenceBinding selected = Objects.requireNonNull(
                    row, "row");
            return new RowOrderKey(
                    selected.occurrenceIdentity(),
                    selected.bindingIdentity());
        }
    }

    private record RowReplacement(
            ManagedOccurrenceBinding before,
            ManagedOccurrenceBinding after) {
        private RowReplacement {
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
        }
    }

    private record SourceReplacement(
            List<ManagedOccurrenceBinding> before,
            List<ManagedOccurrenceBinding> after) {
        private SourceReplacement {
            before = List.copyOf(Objects.requireNonNull(before, "before"));
            after = List.copyOf(Objects.requireNonNull(after, "after"));
        }
    }

    private static final class IndexWork {
        private long indexComparisons;
        private long nodeAllocations;
        private long rowsRead;

        private void add(PersistentOrderedMap.ReadResult<?> read) {
            indexComparisons = Math.addExact(
                    indexComparisons,
                    Objects.requireNonNull(read, "read").comparisons());
        }

        private void add(PersistentOrderedMap.Mutation<?, ?> mutation) {
            PersistentOrderedMap.Mutation<?, ?> selected =
                    Objects.requireNonNull(mutation, "mutation");
            indexComparisons = Math.addExact(
                    indexComparisons, selected.comparisons());
            nodeAllocations = Math.addExact(
                    nodeAllocations, selected.copiedNodes());
        }

        private void rowsRead(long count) {
            if (count < 0L) {
                throw new IllegalArgumentException(
                        "rowsRead increment must be non-negative");
            }
            rowsRead = Math.addExact(rowsRead, count);
        }

        private DeltaMetrics metrics() {
            return new DeltaMetrics(
                    indexComparisons, nodeAllocations, rowsRead);
        }
    }

    /**
     * Mutable holder for new immutable roots while applying one atomic delta.
     */
    private static final class DeltaBuilder {
        private PersistentOrderedMap<OccurrenceKey,
                ManagedOccurrenceBinding> rowsBySourcePath;
        private PersistentOrderedMap<RowOrderKey,
                ManagedOccurrenceBinding> rowsByCanonicalOrder;
        private PersistentOrderedMap<RowOrderKey,
                ManagedOccurrenceBinding> activeRowsByCanonicalOrder;
        private PersistentOrderedMap<String, OccurrenceKey>
                keysByOccurrenceIdentity;
        private PersistentOrderedMap<String, OccurrenceKey>
                keysByBindingIdentity;
        private PersistentOrderedMap<DocumentId,
                PersistentOrderedMap<RowOrderKey,
                        ManagedOccurrenceBinding>> rowsByDocument;
        private PersistentOrderedMap<DocumentId,
                PersistentOrderedMap<RowOrderKey,
                        ManagedOccurrenceBinding>> rowsBySourceDocument;
        private PersistentOrderedMap<DocumentId,
                PersistentOrderedMap<RowOrderKey,
                        ManagedOccurrenceBinding>>
                activeRowsBySourceDocument;
        private final IndexWork work;

        private DeltaBuilder(
                ManagedOccurrenceInventory inventory,
                IndexWork work) {
            ManagedOccurrenceInventory selected = Objects.requireNonNull(
                    inventory, "inventory");
            this.rowsBySourcePath = selected.rowsBySourcePath;
            this.rowsByCanonicalOrder = selected.rowsByCanonicalOrder;
            this.activeRowsByCanonicalOrder =
                    selected.activeRowsByCanonicalOrder;
            this.keysByOccurrenceIdentity =
                    selected.keysByOccurrenceIdentity;
            this.keysByBindingIdentity = selected.keysByBindingIdentity;
            this.rowsByDocument = selected.rowsByDocument;
            this.rowsBySourceDocument = selected.rowsBySourceDocument;
            this.activeRowsBySourceDocument =
                    selected.activeRowsBySourceDocument;
            this.work = Objects.requireNonNull(work, "work");
        }

        private void add(ManagedOccurrenceBinding row) {
            ManagedOccurrenceBinding selected = Objects.requireNonNull(
                    row, "row");
            OccurrenceKey occurrenceKey = key(selected);
            RowOrderKey rowOrderKey = RowOrderKey.from(selected);
            rejectExisting(
                    rowsBySourcePath.read(occurrenceKey),
                    "More than one occurrence row for source/path "
                            + occurrenceKey);
            rejectExisting(
                    keysByOccurrenceIdentity.read(
                            selected.occurrenceIdentity()),
                    "Duplicate occurrence identity "
                            + selected.occurrenceIdentity());
            rejectExisting(
                    keysByBindingIdentity.read(selected.bindingIdentity()),
                    "Duplicate binding identity "
                            + selected.bindingIdentity());

            rowsBySourcePath = put(
                    rowsBySourcePath, occurrenceKey, selected);
            rowsByCanonicalOrder = put(
                    rowsByCanonicalOrder, rowOrderKey, selected);
            keysByOccurrenceIdentity = put(
                    keysByOccurrenceIdentity,
                    selected.occurrenceIdentity(),
                    occurrenceKey);
            keysByBindingIdentity = put(
                    keysByBindingIdentity,
                    selected.bindingIdentity(),
                    occurrenceKey);

            DocumentId source = toCoordinationDocumentId(
                    selected.sourceDocumentId());
            DocumentId target = toCoordinationDocumentId(
                    selected.targetDocumentId());
            rowsBySourceDocument = putBucket(
                    rowsBySourceDocument,
                    source,
                    rowOrderKey,
                    selected);
            rowsByDocument = putBucket(
                    rowsByDocument,
                    source,
                    rowOrderKey,
                    selected);
            if (!source.equals(target)) {
                rowsByDocument = putBucket(
                        rowsByDocument,
                        target,
                        rowOrderKey,
                        selected);
            }
            if (selected.active()) {
                activeRowsByCanonicalOrder = put(
                        activeRowsByCanonicalOrder,
                        rowOrderKey,
                        selected);
                activeRowsBySourceDocument = putBucket(
                        activeRowsBySourceDocument,
                        source,
                        rowOrderKey,
                        selected);
            }
        }

        private void remove(ManagedOccurrenceBinding row) {
            ManagedOccurrenceBinding selected = Objects.requireNonNull(
                    row, "row");
            OccurrenceKey occurrenceKey = key(selected);
            RowOrderKey rowOrderKey = RowOrderKey.from(selected);
            rowsBySourcePath = removeRequired(
                    rowsBySourcePath,
                    occurrenceKey,
                    "source/path row");
            rowsByCanonicalOrder = removeRequired(
                    rowsByCanonicalOrder,
                    rowOrderKey,
                    "canonical row");
            keysByOccurrenceIdentity = removeRequired(
                    keysByOccurrenceIdentity,
                    selected.occurrenceIdentity(),
                    "occurrence identity");
            keysByBindingIdentity = removeRequired(
                    keysByBindingIdentity,
                    selected.bindingIdentity(),
                    "binding identity");

            DocumentId source = toCoordinationDocumentId(
                    selected.sourceDocumentId());
            DocumentId target = toCoordinationDocumentId(
                    selected.targetDocumentId());
            rowsBySourceDocument = removeBucket(
                    rowsBySourceDocument,
                    source,
                    rowOrderKey,
                    "source row");
            rowsByDocument = removeBucket(
                    rowsByDocument,
                    source,
                    rowOrderKey,
                    "touching source row");
            if (!source.equals(target)) {
                rowsByDocument = removeBucket(
                        rowsByDocument,
                        target,
                        rowOrderKey,
                        "touching target row");
            }
            if (selected.active()) {
                activeRowsByCanonicalOrder = removeRequired(
                        activeRowsByCanonicalOrder,
                        rowOrderKey,
                        "active canonical row");
                activeRowsBySourceDocument = removeBucket(
                        activeRowsBySourceDocument,
                        source,
                        rowOrderKey,
                        "active source row");
            }
        }

        private ManagedOccurrenceInventory build() {
            return new ManagedOccurrenceInventory(
                    rowsBySourcePath,
                    rowsByCanonicalOrder,
                    activeRowsByCanonicalOrder,
                    keysByOccurrenceIdentity,
                    keysByBindingIdentity,
                    rowsByDocument,
                    rowsBySourceDocument,
                    activeRowsBySourceDocument);
        }

        private void rejectExisting(
                PersistentOrderedMap.ReadResult<?> read,
                String message) {
            work.add(read);
            if (read.found()) {
                throw new IllegalArgumentException(message);
            }
        }

        private <K, V> PersistentOrderedMap<K, V> put(
                PersistentOrderedMap<K, V> index,
                K key,
                V value) {
            PersistentOrderedMap.Mutation<K, V> mutation =
                    index.put(key, value);
            work.add(mutation);
            return mutation.map();
        }

        private <K, V> PersistentOrderedMap<K, V> removeRequired(
                PersistentOrderedMap<K, V> index,
                K key,
                String label) {
            PersistentOrderedMap.Mutation<K, V> mutation =
                    index.remove(key);
            work.add(mutation);
            if (!mutation.changed()) {
                throw new IllegalStateException(
                        "Missing indexed " + label);
            }
            return mutation.map();
        }

        private PersistentOrderedMap<DocumentId,
                PersistentOrderedMap<RowOrderKey,
                        ManagedOccurrenceBinding>> putBucket(
                PersistentOrderedMap<DocumentId,
                        PersistentOrderedMap<RowOrderKey,
                                ManagedOccurrenceBinding>> index,
                DocumentId documentId,
                RowOrderKey rowOrderKey,
                ManagedOccurrenceBinding row) {
            PersistentOrderedMap.ReadResult<PersistentOrderedMap<
                    RowOrderKey, ManagedOccurrenceBinding>> read =
                    index.read(documentId);
            work.add(read);
            PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding>
                    bucket = read.found()
                    ? read.value()
                    : PersistentOrderedMap.empty(ROW_ORDER);
            PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding>
                    changedBucket = put(bucket, rowOrderKey, row);
            return put(index, documentId, changedBucket);
        }

        private PersistentOrderedMap<DocumentId,
                PersistentOrderedMap<RowOrderKey,
                        ManagedOccurrenceBinding>> removeBucket(
                PersistentOrderedMap<DocumentId,
                        PersistentOrderedMap<RowOrderKey,
                                ManagedOccurrenceBinding>> index,
                DocumentId documentId,
                RowOrderKey rowOrderKey,
                String label) {
            PersistentOrderedMap.ReadResult<PersistentOrderedMap<
                    RowOrderKey, ManagedOccurrenceBinding>> read =
                    index.read(documentId);
            work.add(read);
            if (!read.found()) {
                throw new IllegalStateException(
                        "Missing indexed " + label + " bucket");
            }
            PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding>
                    changedBucket = removeRequired(
                    read.value(), rowOrderKey, label);
            return changedBucket.isEmpty()
                    ? removeRequired(index, documentId, label + " bucket")
                    : put(index, documentId, changedBucket);
        }
    }

    /** One explicit occurrence mutation within an invocation. */
    record Change(
            Kind kind,
            DocumentId sourceDocumentId,
            String sourcePath,
            DocumentId targetDocumentId,
            String expectedTargetBlueId) {
        Change {
            kind = Objects.requireNonNull(kind, "kind");
            sourceDocumentId = validateDocumentId(
                    sourceDocumentId, "sourceDocumentId");
            sourcePath = validateSourcePath(sourcePath);
            targetDocumentId = validateDocumentId(
                    targetDocumentId, "targetDocumentId");
            expectedTargetBlueId = BlueIds.requireBlueIdOrCyclicMember(
                    expectedTargetBlueId, "expectedTargetBlueId");
        }

        static Change retire(
                DocumentId sourceDocumentId,
                String sourcePath,
                DocumentId targetDocumentId,
                String expectedTargetBlueId) {
            return new Change(
                    Kind.RETIRE, sourceDocumentId, sourcePath,
                    targetDocumentId, expectedTargetBlueId);
        }

        static Change activate(
                DocumentId sourceDocumentId,
                String sourcePath,
                DocumentId targetDocumentId,
                String expectedTargetBlueId) {
            return new Change(
                    Kind.ACTIVATE, sourceDocumentId, sourcePath,
                    targetDocumentId, expectedTargetBlueId);
        }

        static Change rebind(
                DocumentId sourceDocumentId,
                String sourcePath,
                DocumentId targetDocumentId,
                String expectedTargetBlueId) {
            return new Change(
                    Kind.REBIND, sourceDocumentId, sourcePath,
                    targetDocumentId, expectedTargetBlueId);
        }

        OccurrenceKey key() {
            return new OccurrenceKey(sourceDocumentId, sourcePath);
        }
    }

    enum Kind {
        ACTIVATE,
        RETIRE,
        REBIND
    }

    private record OccurrenceKey(
            DocumentId sourceDocumentId,
            String sourcePath) {
        private static OccurrenceKey of(
                DocumentId sourceDocumentId,
                String sourcePath) {
            return new OccurrenceKey(
                    validateDocumentId(sourceDocumentId, "sourceDocumentId"),
                    validateSourcePath(sourcePath));
        }

        @Override
        public String toString() {
            return sourceDocumentId + ":" + sourcePath;
        }
    }

    private static ManagedOccurrenceBinding verifyLoadedRow(
            ManagedOccurrenceBinding supplied) {
        ManagedOccurrenceBinding selected = Objects.requireNonNull(
                supplied, "row");
        return ManagedOccurrenceBinding.verified(
                selected.occurrenceIdentity(),
                selected.bindingIdentity(),
                selected.bindingPolicyIdentity(),
                selected.sourceDocumentId(),
                selected.sourceAddress(),
                selected.targetDocumentId(),
                selected.expectedTargetBlueId(),
                selected.active(),
                selected.pendingHistoricalEpoch());
    }

    private static boolean sameRow(
            ManagedOccurrenceBinding left,
            ManagedOccurrenceBinding right) {
        return left.occurrenceIdentity().equals(right.occurrenceIdentity())
                && left.bindingIdentity().equals(right.bindingIdentity())
                && left.active() == right.active()
                && Objects.equals(left.pendingHistoricalEpoch(),
                        right.pendingHistoricalEpoch());
    }

    private static boolean sameRows(
            List<ManagedOccurrenceBinding> left,
            List<ManagedOccurrenceBinding> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!sameRow(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static void assertBucketsValid(
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<RowOrderKey,
                            ManagedOccurrenceBinding>> index) {
        index.assertStructurallyValid();
        for (PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding>
                bucket : index.values()) {
            bucket.assertStructurallyValid();
            if (bucket.isEmpty()) {
                throw new IllegalStateException(
                        "Occurrence index retains an empty bucket");
            }
        }
    }

    private static int sumBucketRows(
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<RowOrderKey,
                            ManagedOccurrenceBinding>> index) {
        int count = 0;
        for (PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding>
                bucket : index.values()) {
            count = Math.addExact(count, bucket.size());
        }
        return count;
    }

    private static void requireBucketRow(
            PersistentOrderedMap<DocumentId,
                    PersistentOrderedMap<RowOrderKey,
                            ManagedOccurrenceBinding>> index,
            DocumentId documentId,
            RowOrderKey rowOrderKey,
            ManagedOccurrenceBinding expected,
            String label) {
        PersistentOrderedMap<RowOrderKey, ManagedOccurrenceBinding> bucket =
                index.get(documentId);
        if (bucket == null) {
            throw new IllegalStateException(
                    "Missing " + label + " occurrence bucket");
        }
        requireSameIndexedRow(
                bucket.get(rowOrderKey), expected, label + " occurrence");
    }

    private static void requireSameIndexedRow(
            ManagedOccurrenceBinding actual,
            ManagedOccurrenceBinding expected,
            String label) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Mismatched indexed " + label);
        }
    }

    private static OccurrenceKey key(ManagedOccurrenceBinding row) {
        return new OccurrenceKey(
                toCoordinationDocumentId(row.sourceDocumentId()),
                row.sourcePath());
    }

    private static DocumentId validateDocumentId(
            DocumentId documentId,
            String label) {
        DocumentId selected = Objects.requireNonNull(documentId, label);
        new blue.language.processor.closure.DocumentId(selected.value());
        return selected;
    }

    private static String validateSourcePath(String sourcePath) {
        return ScopeAddress.embedded(sourcePath, 1L).path();
    }

    private static DocumentId toCoordinationDocumentId(
            blue.language.processor.closure.DocumentId documentId) {
        return DocumentId.of(Objects.requireNonNull(
                documentId, "documentId").value());
    }

    private static blue.language.processor.closure.DocumentId
            contractsDocumentId(DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                Objects.requireNonNull(documentId, "documentId").value());
    }
}
