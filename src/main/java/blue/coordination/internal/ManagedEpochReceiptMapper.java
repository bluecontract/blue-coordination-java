package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedEventOccurrence;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.BlueIds;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedRootEventOccurrence;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.provider.CyclicSetProof;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Maps one verified Contracts transition into its Coordination epoch proof. */
final class ManagedEpochReceiptMapper {
    private ManagedEpochReceiptMapper() {
    }

    static ManagedEpochReceipt map(
            DocumentId documentId,
            long epoch,
            DocumentRevision.Kind kind,
            ExactValue before,
            ExactValue after,
            TimelineEntry sourceEntry,
            ExternalOrderKey sourceOrder,
            ManagedDocumentTransitionReceipt transition,
            ClosureCommitCompanion companion) {
        DocumentId document = Objects.requireNonNull(
                documentId, "documentId");
        ExactValue resulting = Objects.requireNonNull(after, "after");
        ManagedDocumentTransitionReceipt source = Objects.requireNonNull(
                transition, "transition");
        DocumentRevision.Kind revisionKind = Objects.requireNonNull(
                kind, "kind");
        if (!source.documentId().value().equals(document.value())
                || before == null
                || !source.beforeBlueId().equals(before.blueId())
                || !source.afterBlueId().equals(resulting.blueId())) {
            throw new IllegalArgumentException(
                    "Contracts transition does not identify the Coordination "
                            + "document revision");
        }
        ArrayList<ManagedEventOccurrence> events = new ArrayList<>();
        for (ManagedRootEventOccurrence event
                : source.emittedRootEvents()) {
            events.add(ManagedEventOccurrence.identified(
                    event.ordinal(),
                    event.occurrenceOrdinal(),
                    document,
                    event.occurrenceIdentity(),
                    ExactValue.verified(
                            event.eventBlueId(), event.exactEvent()),
                    event.publicAtSource()));
        }
        return ManagedEpochReceipt.identified(
                document,
                epoch,
                revisionKind,
                revisionKind == DocumentRevision.Kind.INITIALIZATION
                        ? null
                        : before.blueId(),
                resulting,
                source.originalCauseIdentity(),
                sourceEntry,
                sourceOrder,
                source.transitionReceiptIdentity(),
                Objects.requireNonNull(companion, "companion")
                        .companionIdentity(),
                List.copyOf(events),
                source.admittedGas());
    }

    /**
     * Maps a successful, eventless application whose consumer exact value did
     * not change. Contracts deliberately has no document-transition receipt
     * for that shape, so its legacy source-revision identity and exact commit
     * companion bind the Coordination-owned epoch marker.
     */
    static ManagedEpochReceipt mapEventlessApplication(
            ManagedEpochApplicationWork work,
            long epoch,
            ExactValue exactDocument,
            TimelineEntry sourceEntry,
            ExternalOrderKey sourceOrder,
            ClosureProcessResult result) {
        ManagedEpochApplicationWork application = Objects.requireNonNull(
                work, "work");
        ExactValue exact = Objects.requireNonNull(
                exactDocument, "exactDocument");
        ClosureProcessResult committed = Objects.requireNonNull(
                result, "result");
        if (!committed.commits()
                || committed.platformCommitCompanion() == null) {
            throw new IllegalArgumentException(
                    "An eventless application receipt requires a committing "
                            + "Contracts result");
        }
        long beforeEpoch = Math.subtractExact(epoch, 1L);
        CyclicSetProof afterCyclicProof = resultingCyclicProof(
                committed,
                application.consumerDocumentId(),
                exact.blueId());
        ManagedRevisionCause sourceEvidence = ClosureEvidenceFactory
                .managedRevisionCause(
                        application.targetOccurrenceIdentity(),
                        ContractsClosureAdapter.closureId(
                                application.consumerDocumentId()),
                        beforeEpoch,
                        epoch,
                        exact.blueId(),
                        exact.blueId(),
                        exact.copyNode(),
                        application.workIdentity(),
                        afterCyclicProof);
        return ManagedEpochReceipt.identified(
                application.consumerDocumentId(),
                epoch,
                DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                exact.blueId(),
                exact,
                application.workIdentity(),
                sourceEntry,
                sourceOrder,
                sourceEvidence.sourceRevisionReceiptIdentity(),
                committed.platformCommitCompanion().companionIdentity(),
                List.of(),
                committed.totalGas());
    }

    /**
     * Derives a cyclic successor proof only from one committed result's
     * authenticated component partition. The parallel member identity must
     * name the exact unchanged document used by the receipt.
     */
    static CyclicSetProof resultingCyclicProof(
            ClosureProcessResult result,
            DocumentId documentId,
            String expectedBlueId) {
        ClosureProcessResult committed = Objects.requireNonNull(
                result, "result");
        DocumentId document = Objects.requireNonNull(
                documentId, "documentId");
        String expected = Objects.requireNonNull(
                expectedBlueId, "expectedBlueId");
        if (!committed.commits()) {
            throw new IllegalArgumentException(
                    "Cyclic receipt proof requires a committing result");
        }

        blue.language.processor.closure.DocumentId contractsDocumentId =
                ContractsClosureAdapter.closureId(document);
        ComponentSnapshot containing = null;
        int memberIndex = -1;
        for (ComponentSnapshot component : committed.resultingComponents()) {
            int candidate = component.orderedMemberDocumentIds().indexOf(
                    contractsDocumentId);
            if (candidate < 0) {
                continue;
            }
            if (containing != null) {
                throw new IllegalArgumentException(
                        "Committed result contains a document in more than "
                                + "one resulting component " + document);
            }
            containing = component;
            memberIndex = candidate;
        }
        if (containing == null) {
            throw new IllegalArgumentException(
                    "Committed result omits the resulting component for "
                            + document);
        }
        String memberBlueId = containing.orderedMemberBlueIds().get(
                memberIndex);
        if (!expected.equals(memberBlueId)) {
            throw new IllegalArgumentException(
                    "Committed resulting component member disagrees with "
                            + "the unchanged receipt BlueId for " + document);
        }

        boolean cyclic = BlueIds.hasCyclicMemberSeparator(expected);
        if (containing.kind() == ComponentKind.ACYCLIC) {
            if (cyclic) {
                throw new IllegalArgumentException(
                        "A cyclic receipt identity belongs to an acyclic "
                                + "resulting component " + document);
            }
            return null;
        }
        if (!cyclic) {
            throw new IllegalArgumentException(
                    "A cyclic resulting component has an acyclic receipt "
                            + "identity for " + document);
        }
        CyclicSetProof proof = Objects.requireNonNull(
                containing.completeCyclicProof(),
                "complete resulting cyclic proof");
        return CyclicSetProof.fromDeclaredPlaceholderSet(
                proof.declaredPlaceholderSet());
    }
}
