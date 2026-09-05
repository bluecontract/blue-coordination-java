package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.language.identity.BlueIds;
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
import java.util.TreeMap;

/**
 * Exact host evidence for one operation which may create managed children.
 *
 * <p>This is an internal bridge value, not a second authored graph API. The
 * graph remains derived by Contracts from prospective occurrence rows. The
 * first production lane intentionally accepts only new, from-now lineages;
 * import and existing-lineage attachment keep their closed legacy policies.</p>
 */
public final class ContractsManagedDraftPlan {
    private final DocumentId targetDocumentId;
    private final long targetEpoch;
    private final String targetBlueId;
    private final Map<DocumentId, ManagedDraft> drafts;
    private final Map<String, DocumentId> managedRequestFields;
    private final List<ExpectedOccurrence> expectedOccurrences;

    boolean missingExpectedOccurrence(blue.language.processor.closure.ClosureProcessResult result) {
        if (!result.commits()) return false;
        Map<DocumentId, blue.language.processor.closure.ResultingDocument> documents = new LinkedHashMap<>();
        result.resultingDocuments().forEach(document -> documents.put(DocumentId.of(document.documentId().value()), document));
        var source = documents.get(targetDocumentId);
        if (source == null || !source.beforeBlueId().equals(targetBlueId)) {
            throw new IllegalArgumentException("Managed draft decision has another source fence");
        }
        for (ManagedDraft draft : drafts.values()) {
            var target = documents.get(draft.documentId());
            if (target == null || !target.beforeBlueId().equals(draft.initial().blueId())) {
                throw new IllegalArgumentException("Managed draft decision has another exact draft");
            }
        }
        return expectedOccurrences.stream().anyMatch(expectation -> {
            var target = documents.get(expectation.targetDocumentId());
            var matches = result.occurrenceBindings().stream().filter(row ->
                    row.sourceDocumentId().value().equals(targetDocumentId.value())
                            && row.sourcePath().equals(expectation.path())).toList();
            var exact = blue.language.model.NodePathEditor.getOrNull(source.document(), expectation.path());
            return matches.size() != 1 || !matches.get(0).active()
                    || !matches.get(0).targetDocumentId().value().equals(expectation.targetDocumentId().value())
                    || !matches.get(0).expectedTargetBlueId().equals(target.afterBlueId())
                    || exact == null || !target.afterBlueId().equals(exact.getBlueId());
        });
    }

    blue.language.processor.closure.ManagedOccurrenceBinding prospectiveOccurrence(
            blue.language.processor.closure.ClosureInvocationInput input, ExpectedOccurrence expected) {
        var rows = input.snapshot().occurrences().stream().filter(row -> !row.active()
                && row.sourceDocumentId().value().equals(targetDocumentId.value())
                && row.sourcePath().equals(expected.path())
                && row.targetDocumentId().value().equals(expected.targetDocumentId().value())).toList();
        if (rows.size() != 1) throw new IllegalStateException("Managed plan has no unique prospective input row");
        return rows.get(0);
    }

    /** Creates one canonical exact managed-draft plan. */
    public ContractsManagedDraftPlan(
            DocumentId targetDocumentId,
            long targetEpoch,
            String targetBlueId,
            Map<DocumentId, ManagedDraft> drafts,
            Map<String, DocumentId> managedRequestFields,
            List<ExpectedOccurrence> expectedOccurrences) {
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.targetEpoch = MultiDocumentPublicationTransaction
                .requireSafeInteger(targetEpoch, "targetEpoch");
        this.targetBlueId = BlueIds.requireBlueIdOrCyclicMember(
                targetBlueId, "targetBlueId");

        TreeMap<DocumentId, ManagedDraft> retainedDrafts = new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        retainedDrafts.putAll(Objects.requireNonNull(drafts, "drafts"));
        for (Map.Entry<DocumentId, ManagedDraft> entry : retainedDrafts.entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "draft documentId");
            ManagedDraft draft = Objects.requireNonNull(
                    entry.getValue(), "draft");
            if (!documentId.equals(draft.documentId())) {
                throw new IllegalArgumentException(
                        "Managed draft is stored under the wrong DocumentId");
            }
            if (documentId.equals(this.targetDocumentId)) {
                throw new IllegalArgumentException(
                        "Managed draft cannot reuse the operation target "
                                + documentId);
            }
        }
        if (retainedDrafts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Managed draft plan requires at least one new lineage");
        }
        this.drafts = Collections.unmodifiableMap(retainedDrafts);

        TreeMap<String, DocumentId> canonicalFields = new TreeMap<>(EmbeddingBinding.TEXT_ORDER);
        canonicalFields.putAll(Objects.requireNonNull(managedRequestFields, "managedRequestFields"));
        LinkedHashMap<String, DocumentId> retainedFields =
                new LinkedHashMap<>();
        for (Map.Entry<String, DocumentId> entry : canonicalFields.entrySet()) {
            String field = requireText(entry.getKey(), "request field");
            DocumentId documentId = Objects.requireNonNull(
                    entry.getValue(), "request draft DocumentId");
            if (!this.drafts.containsKey(documentId)) {
                throw new IllegalArgumentException(
                        "Managed request field names an unknown draft "
                                + documentId);
            }
            if (retainedFields.putIfAbsent(field, documentId) != null) {
                throw new IllegalArgumentException(
                        "Duplicate managed request field " + field);
            }
        }
        if (retainedFields.isEmpty()
                || !new LinkedHashSet<>(retainedFields.values()).equals(
                        this.drafts.keySet())) {
            throw new IllegalArgumentException(
                    "Every managed draft must be retained by at least one "
                            + "exact request field");
        }
        this.managedRequestFields = Collections.unmodifiableMap(
                retainedFields);

        ArrayList<ExpectedOccurrence> canonicalOccurrences =
                new ArrayList<>(Objects.requireNonNull(
                        expectedOccurrences, "expectedOccurrences"));
        canonicalOccurrences.replaceAll(occurrence -> Objects.requireNonNull(
                occurrence, "expectedOccurrence"));
        canonicalOccurrences.sort(Comparator
                .comparing(ExpectedOccurrence::path,
                        EmbeddingBinding.TEXT_ORDER)
                .thenComparing(ExpectedOccurrence::targetDocumentId,
                        EmbeddingBinding.DOCUMENT_ORDER));
        Set<String> paths = new LinkedHashSet<>();
        Set<DocumentId> expectedDrafts = new LinkedHashSet<>();
        for (ExpectedOccurrence occurrence : canonicalOccurrences) {
            if (!paths.add(occurrence.path())) {
                throw new IllegalArgumentException(
                        "More than one expected occurrence at "
                                + occurrence.path());
            }
            if (!this.drafts.containsKey(
                    occurrence.targetDocumentId())) {
                throw new IllegalArgumentException(
                        "Expected occurrence names an unknown draft "
                                + occurrence.targetDocumentId());
            }
            if (occurrence.activationMode()
                    != ActivationMode.BIRTH_AT_ATTACHMENT) {
                throw new UnsupportedOperationException(
                        "Managed PROCESS expansion currently requires "
                                + "BIRTH_AT_ATTACHMENT");
            }
            expectedDrafts.add(occurrence.targetDocumentId());
        }
        if (canonicalOccurrences.isEmpty()
                || !expectedDrafts.equals(this.drafts.keySet())) {
            throw new IllegalArgumentException(
                    "Every managed draft must have at least one expected "
                            + "occurrence");
        }
        this.expectedOccurrences = List.copyOf(canonicalOccurrences);
    }

    public DocumentId targetDocumentId() {
        return targetDocumentId;
    }

    public long targetEpoch() {
        return targetEpoch;
    }

    public String targetBlueId() {
        return targetBlueId;
    }

    public Map<DocumentId, ManagedDraft> drafts() {
        return drafts;
    }

    public Map<String, DocumentId> managedRequestFields() {
        return managedRequestFields;
    }

    public List<ExpectedOccurrence> expectedOccurrences() {
        return expectedOccurrences;
    }

    /** One exact new managed Root candidate. */
    public record ManagedDraft(
            DocumentId documentId,
            ExactValue initial,
            Long knownEpoch,
            boolean contentDerivedIdentity) {
        /** Preserves the legacy authored {@code /documentId} identity policy. */
        public ManagedDraft(
                DocumentId documentId,
                ExactValue initial,
                Long knownEpoch) {
            this(documentId, initial, knownEpoch, false);
        }

        public ManagedDraft {
            documentId = Objects.requireNonNull(documentId, "documentId");
            initial = Objects.requireNonNull(initial, "initial");
            if (initial.isCyclicMember()
                    && initial.cyclicSetProof().isEmpty()) {
                throw new UnsupportedOperationException(
                        "Managed PROCESS cyclic expansion requires complete "
                                + "verified provider proof");
            }
            DocumentId exactIdentity = DocumentId.of(initial.blueId());
            DocumentId selectedIdentity = contentDerivedIdentity
                    ? exactIdentity
                    : DocumentIdentityReader.requireDocumentId(initial);
            if (!selectedIdentity.equals(documentId)) {
                throw new IllegalArgumentException(
                        "Managed draft DocumentId does not match its "
                                + (contentDerivedIdentity
                                ? "exact authored BlueId "
                                : "authored /documentId ")
                                + documentId);
            }
            if (knownEpoch != null) {
                MultiDocumentPublicationTransaction.requireSafeInteger(
                        knownEpoch.longValue(), "knownEpoch");
                throw new UnsupportedOperationException(
                        "Managed PROCESS expansion does not import historical "
                                + "draft epochs");
            }
        }
    }

    /** One expected prospective source-path-to-draft occurrence. */
    public record ExpectedOccurrence(
            String path,
            DocumentId targetDocumentId,
            ActivationMode activationMode) {
        public ExpectedOccurrence {
            path = JsonPointer.canonicalize(Objects.requireNonNull(
                    path, "path"));
            if (path.isEmpty()) {
                throw new IllegalArgumentException(
                        "A managed occurrence cannot replace the document Root");
            }
            targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
            activationMode = Objects.requireNonNull(
                    activationMode, "activationMode");
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
