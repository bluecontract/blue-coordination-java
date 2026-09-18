package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentId;
import blue.language.identity.BlueIds;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Exact journal-keyed plan row; never a replacement for a verified journal or admission. */
final class OperationPlanStorageCodec {
    private static final String FORMAT = "blue-coordination/operation-plan-row/1";
    private final int maximumBytes;
    private final SessionRecordCodec rows;

    OperationPlanStorageCodec(int maximumBytes, int maximumDepth) {
        this.maximumBytes = maximumBytes;
        rows = new SessionRecordCodec(maximumBytes, maximumDepth);
    }

    record Plans(String entryBlueId, ContractsManagedDraftPlan draft,
            ContractsManagedEpochSelectionPlan selection) {
        Plans {
            BlueIds.requireBlueIdOrCyclicMember(entryBlueId, "entryBlueId");
            require(draft != null || selection != null, "Empty retained operation plan");
            if (draft != null && selection != null)
                require(draft.targetDocumentId().equals(selection.targetDocumentId())
                                && draft.targetEpoch() == selection.targetEpoch()
                                && draft.targetBlueId().equals(selection.targetBlueId()),
                        "Operation plans disagree about the captured target");
        }
    }

    byte[] encode(Plans value) {
        Objects.requireNonNull(value);
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(FORMAT); out.text(value.entryBlueId());
            optional(out, value.draft(), this::draftPlan);
            optional(out, value.selection(), this::selectionPlan);
        });
    }

    Plans decode(String expectedEntryBlueId, byte[] bytes) {
        return physical(() -> {
            Plans value = SessionStorageWire.decode(bytes, maximumBytes, in -> {
                require(FORMAT.equals(text(in)), "Wrong operation-plan format");
                return new Plans(text(in), optional(in, this::draftPlan), optional(in, this::selectionPlan));
            });
            require(value.entryBlueId().equals(expectedEntryBlueId), "Operation plan belongs to another journal entry");
            require(Arrays.equals(bytes, encode(value)), "Noncanonical operation plan row");
            return value;
        });
    }

    void draftPlan(Writer out, ContractsManagedDraftPlan plan) {
        out.text(plan.targetDocumentId().value()); out.longValue(plan.targetEpoch()); out.text(plan.targetBlueId());
        documents(out, plan.drafts(), this::draft);
        map(out, plan.managedRequestFields(), (w, id) -> w.text(id.value()));
        list(out, plan.expectedOccurrences(), (w, occurrence) -> {
            w.text(occurrence.path()); w.text(occurrence.targetDocumentId().value()); w.text(occurrence.activationMode().name());
        });
    }

    ContractsManagedDraftPlan draftPlan(Reader in) {
        return new ContractsManagedDraftPlan(DocumentId.of(text(in)), in.longValue(), text(in),
                documents(in, this::draft), map(in, r -> DocumentId.of(text(r))),
                list(in, r -> new ContractsManagedDraftPlan.ExpectedOccurrence(text(r), DocumentId.of(text(r)),
                        ActivationMode.valueOf(text(r)))));
    }

    void draft(Writer out, ContractsManagedDraftPlan.ManagedDraft draft) {
        out.text(draft.documentId().value()); rows.exact(out, draft.initial());
        optional(out, draft.knownEpoch(), Writer::longValue); out.bool(draft.contentDerivedIdentity());
    }

    ContractsManagedDraftPlan.ManagedDraft draft(Reader in) {
        return new ContractsManagedDraftPlan.ManagedDraft(DocumentId.of(text(in)), rows.exact(in),
                optional(in, Reader::longValue), in.bool());
    }

    void selectionPlan(Writer out, ContractsManagedEpochSelectionPlan plan) {
        out.text(plan.targetDocumentId().value()); out.longValue(plan.targetEpoch()); out.text(plan.targetBlueId());
        list(out, plan.selections(), (w, selection) -> {
            w.text(selection.sourceDocumentId().value()); w.longValue(selection.sourceEpoch());
            w.text(selection.expectedSourceBlueId()); w.text(selection.targetOccurrencePath());
        });
    }

    ContractsManagedEpochSelectionPlan selectionPlan(Reader in) {
        return new ContractsManagedEpochSelectionPlan(DocumentId.of(text(in)), in.longValue(), text(in),
                list(in, r -> new ContractsManagedEpochSelectionPlan.Selection(DocumentId.of(text(r)),
                        r.longValue(), text(r), text(r))));
    }
}
