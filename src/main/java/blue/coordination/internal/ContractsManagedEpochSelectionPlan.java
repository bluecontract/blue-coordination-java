package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.identity.BlueIds;
import blue.language.model.value.BlueNumbers;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable host evidence selecting exact managed epochs for one operation.
 *
 * <p>The plan is bound either to an operation target head before journal
 * append or to the authored Root of one atomic static admission. Target epoch
 * {@code -1} is only the authored-pre-initialization position for that static
 * admission; it is never a durable managed receipt epoch. Each selector is
 * occurrence-specific: the path belongs to the target document, while the
 * stable source identity and epoch name the managed state that the prospective
 * occurrence must use.</p>
 */
public final class ContractsManagedEpochSelectionPlan {
    private final DocumentId targetDocumentId;
    private final long targetEpoch;
    private final String targetBlueId;
    private final Map<String, Selection> selectionsByPath;

    /** Creates one canonical exact selection plan for a captured target head. */
    public ContractsManagedEpochSelectionPlan(
            DocumentId targetDocumentId,
            long targetEpoch,
            String targetBlueId,
            List<Selection> selections) {
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        long maximum = BlueNumbers.MAX_INTEROPERABLE_INTEGER.longValueExact();
        if (targetEpoch < -1L || targetEpoch > maximum) {
            throw new IllegalArgumentException(
                    "targetEpoch must be -1 or a non-negative safe integer");
        }
        this.targetEpoch = targetEpoch;
        this.targetBlueId = BlueIds.requireBlueIdOrCyclicMember(
                targetBlueId, "targetBlueId");
        ArrayList<Selection> canonical = new ArrayList<>(
                Objects.requireNonNull(selections, "selections"));
        canonical.replaceAll(selection -> Objects.requireNonNull(
                selection, "selection"));
        canonical.sort(Comparator.comparing(
                Selection::targetOccurrencePath,
                EmbeddingBinding.TEXT_ORDER));
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException(
                    "Managed epoch selection plan requires a selector");
        }
        LinkedHashMap<String, Selection> retained = new LinkedHashMap<>();
        for (Selection selection : canonical) {
            if (retained.putIfAbsent(
                    selection.targetOccurrencePath(), selection) != null) {
                throw new IllegalArgumentException(
                        "More than one managed epoch selector targets "
                                + selection.targetOccurrencePath());
            }
        }
        this.selectionsByPath = Collections.unmodifiableMap(retained);
    }

    /** Exact operation or authored-admission target lineage. */
    public DocumentId targetDocumentId() {
        return targetDocumentId;
    }

    /** Exact target position; {@code -1} only means authored admission Root. */
    public long targetEpoch() {
        return targetEpoch;
    }

    /** Exact operation target BlueId captured before append. */
    public String targetBlueId() {
        return targetBlueId;
    }

    /** Canonically ordered occurrence-specific selectors. */
    public List<Selection> selections() {
        return List.copyOf(selectionsByPath.values());
    }

    Selection selectionFor(DocumentId demandSource, String demandPath) {
        if (!targetDocumentId.equals(Objects.requireNonNull(
                demandSource, "demandSource"))) {
            return null;
        }
        return selectionsByPath.get(JsonPointer.canonicalize(
                Objects.requireNonNull(demandPath, "demandPath")));
    }

    Set<String> paths() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                selectionsByPath.keySet()));
    }

    void requireEveryPathResolved(Set<String> resolvedPaths) {
        Set<String> selected = new LinkedHashSet<>(Objects.requireNonNull(
                resolvedPaths, "resolvedPaths"));
        if (!selected.equals(selectionsByPath.keySet())) {
            LinkedHashSet<String> missing = new LinkedHashSet<>(
                    selectionsByPath.keySet());
            missing.removeAll(selected);
            LinkedHashSet<String> unexpected = new LinkedHashSet<>(selected);
            unexpected.removeAll(selectionsByPath.keySet());
            throw new IllegalArgumentException(
                    "MANAGED_EPOCH_SELECTOR_PATH_MISMATCH: missing="
                            + missing + ", unexpected=" + unexpected);
        }
    }

    /** One exact source-lineage position selected for a target occurrence. */
    public record Selection(
            DocumentId sourceDocumentId,
            long sourceEpoch,
            String expectedSourceBlueId,
            String targetOccurrencePath) {
        /** Validates stable lineage, exact epoch, exact state, and path. */
        public Selection {
            sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            long maximum = BlueNumbers.MAX_INTEROPERABLE_INTEGER
                    .longValueExact();
            if (sourceEpoch < -1L || sourceEpoch > maximum) {
                throw new IllegalArgumentException(
                        "sourceEpoch must be -1 or a non-negative safe "
                                + "integer");
            }
            expectedSourceBlueId = BlueIds.requireBlueIdOrCyclicMember(
                    expectedSourceBlueId, "expectedSourceBlueId");
            targetOccurrencePath = JsonPointer.canonicalize(
                    Objects.requireNonNull(
                            targetOccurrencePath,
                            "targetOccurrencePath"));
            if (targetOccurrencePath.isEmpty()) {
                throw new IllegalArgumentException(
                        "A managed occurrence cannot replace the document "
                                + "Root");
            }
        }
    }
}
