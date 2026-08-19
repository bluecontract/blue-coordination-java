package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.identity.BlueIds;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final ManagedOccurrenceInventory EMPTY =
            new ManagedOccurrenceInventory(List.of());

    private final List<ManagedOccurrenceBinding> rows;
    private final List<ManagedOccurrenceBinding> activeRows;
    private final List<DocumentId> documentIds;
    private final Map<OccurrenceKey, ManagedOccurrenceBinding>
            rowsBySourcePath;
    private final Map<DocumentId, List<ManagedOccurrenceBinding>>
            rowsByDocument;
    private final Map<DocumentId, List<ManagedOccurrenceBinding>>
            activeRowsBySourceDocument;

    private ManagedOccurrenceInventory(
            Collection<ManagedOccurrenceBinding> suppliedRows) {
        ArrayList<ManagedOccurrenceBinding> canonical = new ArrayList<>(
                Objects.requireNonNull(suppliedRows, "suppliedRows"));
        canonical.replaceAll(ManagedOccurrenceInventory::verifyLoadedRow);
        Collections.sort(canonical);

        LinkedHashMap<OccurrenceKey, ManagedOccurrenceBinding> bySourcePath =
                new LinkedHashMap<>();
        Set<String> occurrenceIdentities = new LinkedHashSet<>();
        Set<String> bindingIdentities = new LinkedHashSet<>();
        TreeSet<DocumentId> documents = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        TreeMap<DocumentId, List<ManagedOccurrenceBinding>> byDocument =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        TreeMap<DocumentId, List<ManagedOccurrenceBinding>> activeBySource =
                new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        ArrayList<ManagedOccurrenceBinding> active = new ArrayList<>();
        for (ManagedOccurrenceBinding row : canonical) {
            OccurrenceKey key = key(row);
            if (bySourcePath.putIfAbsent(key, row) != null) {
                throw new IllegalArgumentException(
                        "More than one occurrence row for source/path "
                                + key);
            }
            if (!occurrenceIdentities.add(row.occurrenceIdentity())) {
                throw new IllegalArgumentException(
                        "Duplicate occurrence identity "
                                + row.occurrenceIdentity());
            }
            if (!bindingIdentities.add(row.bindingIdentity())) {
                throw new IllegalArgumentException(
                        "Duplicate binding identity "
                                + row.bindingIdentity());
            }
            DocumentId source = toCoordinationDocumentId(
                    row.sourceDocumentId());
            DocumentId target = toCoordinationDocumentId(
                    row.targetDocumentId());
            documents.add(source);
            documents.add(target);
            byDocument.computeIfAbsent(
                    source, ignored -> new ArrayList<>()).add(row);
            if (!source.equals(target)) {
                byDocument.computeIfAbsent(
                        target, ignored -> new ArrayList<>()).add(row);
            }
            if (row.active()) {
                active.add(row);
                activeBySource.computeIfAbsent(
                        source, ignored -> new ArrayList<>()).add(row);
            }
        }
        this.rows = List.copyOf(canonical);
        this.activeRows = List.copyOf(active);
        this.documentIds = List.copyOf(documents);
        this.rowsBySourcePath = Collections.unmodifiableMap(bySourcePath);
        LinkedHashMap<DocumentId, List<ManagedOccurrenceBinding>>
                immutableByDocument = new LinkedHashMap<>();
        byDocument.forEach((documentId, touching) ->
                immutableByDocument.put(documentId, List.copyOf(touching)));
        this.rowsByDocument = Collections.unmodifiableMap(
                immutableByDocument);
        LinkedHashMap<DocumentId, List<ManagedOccurrenceBinding>>
                immutableActiveBySource = new LinkedHashMap<>();
        activeBySource.forEach((documentId, outgoing) ->
                immutableActiveBySource.put(
                        documentId, List.copyOf(outgoing)));
        this.activeRowsBySourceDocument = Collections.unmodifiableMap(
                immutableActiveBySource);
    }

    /** Returns the canonical empty inventory. */
    static ManagedOccurrenceInventory empty() {
        return EMPTY;
    }

    /** Captures and verifies all Contracts-owned active and inactive rows. */
    static ManagedOccurrenceInventory of(
            Collection<ManagedOccurrenceBinding> rows) {
        Objects.requireNonNull(rows, "rows");
        return rows.isEmpty() ? EMPTY : new ManagedOccurrenceInventory(rows);
    }

    /** Every row in canonical Contracts occurrence-identity order. */
    List<ManagedOccurrenceBinding> rows() {
        return rows;
    }

    /** Only rows contributing graph edges, in canonical row order. */
    List<ManagedOccurrenceBinding> activeRows() {
        return activeRows;
    }

    /** Every source or target lineage named by any retained row. */
    List<DocumentId> documentIds() {
        return documentIds;
    }

    /** All canonical occurrence rows touching one managed lineage. */
    List<ManagedOccurrenceBinding> rowsTouching(DocumentId documentId) {
        return rowsByDocument.getOrDefault(Objects.requireNonNull(
                documentId, "documentId"), List.of());
    }

    /** Active authored edges whose source is one managed lineage. */
    List<ManagedOccurrenceBinding> activeRowsFrom(DocumentId documentId) {
        return activeRowsBySourceDocument.getOrDefault(
                Objects.requireNonNull(documentId, "documentId"), List.of());
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

    /**
     * Applies one invocation's occurrence transitions atomically.
     *
     * <p>Retirement allocates the same-lineage inactive successor at exactly
     * generation plus one. Activation consumes an already committed inactive
     * row and preserves its generation and occurrence identity. Supplying two
     * changes for one source/path is rejected, which excludes same-invocation
     * remove-then-re-add. Every resulting identity is derived by Contracts,
     * never by Coordination.</p>
     */
    ManagedOccurrenceInventory apply(Collection<Change> changes) {
        Objects.requireNonNull(changes, "changes");
        if (changes.isEmpty()) {
            return this;
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

        LinkedHashMap<OccurrenceKey, ManagedOccurrenceBinding> resultingRows =
                new LinkedHashMap<>(rowsBySourcePath);
        boolean changed = false;
        for (Change change : canonicalChanges.values()) {
            ManagedOccurrenceBinding current = resultingRows.get(
                    change.key());
            if (current == null) {
                throw new IllegalArgumentException(
                        "Transition has no committed occurrence row: "
                                + change.key());
            }
            if (!current.targetDocumentId().value().equals(
                    change.targetDocumentId().value())) {
                throw new UnsupportedOperationException(
                        "Different-lineage Process Embedded retarget is "
                                + "unsupported for " + change.key());
            }
            ManagedOccurrenceBinding replacement = switch (change.kind()) {
                case RETIRE -> retire(current, change.expectedTargetBlueId());
                case ACTIVATE -> activate(
                        current, change.expectedTargetBlueId());
                case REBIND -> rebind(
                        current, change.expectedTargetBlueId());
            };
            resultingRows.put(change.key(), replacement);
            changed |= !sameRow(replacement, current);
        }
        if (!changed) {
            return this;
        }
        return of(resultingRows.values());
    }

    private static ManagedOccurrenceBinding retire(
            ManagedOccurrenceBinding current,
            String expectedTargetBlueId) {
        if (!current.active()) {
            throw new IllegalStateException(
                    "Only an active occurrence can be retired: "
                            + key(current));
        }
        ScopeAddress successorAddress;
        try {
            successorAddress = ScopeAddress.embedded(
                    current.sourcePath(),
                    Math.addExact(current.activationGeneration(), 1L));
        } catch (ArithmeticException | IllegalArgumentException rejected) {
            throw new IllegalStateException(
                    "Occurrence generation exceeds the portable safe-integer "
                            + "range at " + key(current), rejected);
        }
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
            String expectedTargetBlueId) {
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
}
