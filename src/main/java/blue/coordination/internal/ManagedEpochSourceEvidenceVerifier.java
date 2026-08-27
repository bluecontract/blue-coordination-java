package blue.coordination.internal;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedEventOccurrence;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.processor.closure.ManagedRootEventOccurrence;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;

import java.util.List;
import java.util.Objects;

/**
 * Verifies the immutable source-receipt chain consumed by one retained
 * managed-epoch application.
 *
 * <p>This boundary deliberately owns no PROCESS or publication behavior. It
 * proves only that the Coordination receipt, Contracts transition evidence,
 * optional eventless application evidence, and cyclic successor proof all
 * identify the same durable work item.</p>
 */
final class ManagedEpochSourceEvidenceVerifier {
    private final WholeObjectStore objects;
    private final InMemoryDocumentStore documents;

    ManagedEpochSourceEvidenceVerifier(
            WholeObjectStore objects,
            InMemoryDocumentStore documents) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    /** Re-verifies every cross-layer immutable receipt edge before PROCESS. */
    VerifiedSourceEvidence verify(
            ManagedEpochApplicationWork work,
            InMemoryDocumentStore.ManagedEpochEvidence evidence,
            ManagedOccurrenceCatchUpPlan plan) {
        ManagedEpochReceipt receipt = evidence.receipt();
        if (receipt == null) {
            throw ManagedEpochEvidenceException.waiting(
                    work,
                    ManagedEpochEvidenceException.SOURCE_EPOCH_MISSING,
                    "Immutable source epoch " + work.sourceEpoch()
                            + " is unavailable for " + work.workIdentity());
        }
        ManagedDocumentTransitionReceipt transition =
                evidence.transitionReceipt();
        if (!receipt.documentId().equals(work.sourceDocumentId())) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.SOURCE_DOCUMENT_MISMATCH,
                    "Immutable source evidence names another document");
        }
        if (receipt.epoch() != work.sourceEpoch()) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.SOURCE_EPOCH_MISMATCH,
                    "Immutable source evidence names another epoch");
        }
        if (!receipt.receiptIdentity().equals(
                work.sourceReceiptIdentity())) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.RECEIPT_IDENTITY_MISMATCH,
                    "Managed epoch receipt identity disagrees with its work");
        }
        if (transition == null) {
            CyclicSetProof afterCyclicProof = requireAfterCyclicProof(
                    work, receipt.afterBlueId());
            verifyEventlessApplicationEvidence(
                    work, receipt, afterCyclicProof);
            return new VerifiedSourceEvidence(
                    receipt, null, afterCyclicProof);
        }
        if (!transition.documentId().value().equals(
                work.sourceDocumentId().value())) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.SOURCE_DOCUMENT_MISMATCH,
                    "Contracts transition names another document");
        }
        String before = receipt.beforeBlueId().orElse(null);
        boolean initialization = receipt.epoch() == 0L
                && receipt.kind() == DocumentRevision.Kind.INITIALIZATION;
        boolean initializationMismatch = initialization
                && (before != null
                        || plan.admittedSourceEpoch() != -1L
                        || work.sourceEpoch()
                                != Math.addExact(
                                        plan.admittedSourceEpoch(), 1L)
                        || !plan.admittedSourceBlueId().equals(
                                transition.beforeBlueId()));
        if (initializationMismatch
                || (!initialization
                        && (before == null
                                || !before.equals(
                                        transition.beforeBlueId())))) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.BEFORE_BLUE_ID_MISMATCH,
                    "Managed epoch predecessor identity disagrees with "
                            + "Contracts evidence");
        }
        if (!receipt.afterBlueId().equals(transition.afterBlueId())) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.AFTER_BLUE_ID_MISMATCH,
                    "Managed epoch successor identity disagrees with "
                            + "Contracts evidence");
        }
        if (!receipt.originalCauseIdentity().equals(
                transition.originalCauseIdentity())) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.ORIGINAL_CAUSE_MISMATCH,
                    "Managed epoch original cause disagrees with Contracts "
                            + "evidence");
        }
        if (receipt.processingGas() != transition.admittedGas()) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.PROCESSING_GAS_MISMATCH,
                    "Managed epoch gas disagrees with Contracts evidence");
        }
        requireSameEvents(work, receipt, transition);
        if (!receipt.contractsTransitionReceiptIdentity().equals(
                transition.transitionReceiptIdentity())) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.RECEIPT_IDENTITY_MISMATCH,
                    "Managed epoch receipt identity disagrees with its "
                            + "Contracts transition");
        }
        CyclicSetProof afterCyclicProof = requireAfterCyclicProof(
                work, receipt.afterBlueId());
        return new VerifiedSourceEvidence(
                receipt, transition, afterCyclicProof);
    }

    private void verifyEventlessApplicationEvidence(
            ManagedEpochApplicationWork work,
            ManagedEpochReceipt receipt,
            CyclicSetProof afterCyclicProof) {
        String before = receipt.beforeBlueId().orElse(null);
        if (receipt.kind()
                        != DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION
                || before == null
                || !before.equals(receipt.afterBlueId())
                || !receipt.emittedEvents().isEmpty()) {
            throw ManagedEpochEvidenceException.blocked(
                    work,
                    ManagedEpochEvidenceException.TRANSITION_RECEIPT_MISSING,
                    "Source epoch has no complete Contracts transition or "
                            + "verified eventless application evidence for "
                            + work.workIdentity());
        }
        String sourceApplicationWork = receipt.originalCauseIdentity();
        ManagedEpochApplicationReceipt application = documents
                .catchUpApplicationByWork(sourceApplicationWork)
                .orElseThrow(() -> ManagedEpochEvidenceException.blocked(
                        work,
                        ManagedEpochEvidenceException
                                .TRANSITION_RECEIPT_MISSING,
                        "Eventless source epoch has no owning application "
                                + "receipt for " + work.workIdentity()));
        ContractsClosurePublicationReceipt publication = documents
                .closurePublicationReceipt(sourceApplicationWork)
                .filter(ContractsClosurePublicationReceipt::commits)
                .orElseThrow(() -> ManagedEpochEvidenceException.blocked(
                        work,
                        ManagedEpochEvidenceException
                                .TRANSITION_RECEIPT_MISSING,
                        "Eventless source epoch has no committing Contracts "
                                + "result for " + work.workIdentity()));
        ClosureProcessResult result = publication.attempt().processResult();
        ExactValue resultDocument = ExactValue.fromVerifiedClosureResult(
                result, receipt.documentId());
        boolean exactApplication = application.workIdentity().equals(
                        sourceApplicationWork)
                && application.consumerDocumentId().equals(
                        receipt.documentId())
                && application.consumerRevisionEpoch() == receipt.epoch()
                && application.consumerRevisionReceiptIdentity().equals(
                        receipt.receiptIdentity())
                && application.consumerCommittedBlueId().equals(
                        receipt.afterBlueId())
                && application.contractsInvocationIdentity().equals(
                        result.invocationIdentity())
                && application.contractsResultIdentity().equals(
                        result.outputClosureIdentity())
                && application.commitCompanionIdentity().equals(
                        receipt.commitCompanionIdentity())
                && result.platformCommitCompanion() != null
                && receipt.commitCompanionIdentity().equals(
                        result.platformCommitCompanion()
                                .companionIdentity())
                && publication.publicationIdentity().equals(
                        sourceApplicationWork)
                && publication.documentIds().contains(receipt.documentId())
                && resultDocument.blueId().equals(receipt.afterBlueId())
                && receipt.processingGas() == result.totalGas()
                && result.managedTransitionReceipts().stream().noneMatch(
                        candidate -> candidate.documentId().value().equals(
                                receipt.documentId().value()));
        ManagedRevisionCause sourceEvidence = ClosureEvidenceFactory
                .managedRevisionCause(
                        work.targetOccurrenceIdentity(),
                        ContractsClosureAdapter.closureId(
                                receipt.documentId()),
                        Math.subtractExact(receipt.epoch(), 1L),
                        receipt.epoch(),
                        before,
                        receipt.afterBlueId(),
                        receipt.afterDocument().copyNode(),
                        sourceApplicationWork,
                        afterCyclicProof);
        if (!exactApplication
                || !receipt.contractsTransitionReceiptIdentity().equals(
                        sourceEvidence.sourceRevisionReceiptIdentity())) {
            throw mismatch(
                    work,
                    ManagedEpochEvidenceException.RECEIPT_IDENTITY_MISMATCH,
                    "Eventless managed application evidence is incomplete "
                            + "or inconsistent");
        }
    }

    private CyclicSetProof requireAfterCyclicProof(
            ManagedEpochApplicationWork work,
            String afterBlueId) {
        if (!BlueIds.hasCyclicMemberSeparator(afterBlueId)) {
            return null;
        }
        CyclicSetProofResult result = objects.cyclicSetProofFor(afterBlueId);
        NodeProviderOutcome outcome = result.outcome();
        if (outcome == NodeProviderOutcome.FOUND) {
            return copyCyclicSetProof(result.proof().orElseThrow(() ->
                    new IllegalStateException(
                            "A found cyclic proof result has no proof")));
        }
        String diagnostic = result.diagnostic()
                .map(value -> ": " + value)
                .orElse("");
        if (outcome == NodeProviderOutcome.NOT_FOUND) {
            throw ManagedEpochEvidenceException.waiting(
                    work,
                    ManagedEpochEvidenceException.CYCLIC_PROOF_MISSING,
                    "Complete cyclic successor proof is not retained for "
                            + afterBlueId + diagnostic);
        }
        if (outcome == NodeProviderOutcome.UNAVAILABLE) {
            throw ManagedEpochEvidenceException.waiting(
                    work,
                    ManagedEpochEvidenceException.CYCLIC_PROOF_UNAVAILABLE,
                    "Complete cyclic successor proof is unavailable for "
                            + afterBlueId + diagnostic);
        }
        if (outcome == NodeProviderOutcome.INVALID_EVIDENCE) {
            throw ManagedEpochEvidenceException.blocked(
                    work,
                    ManagedEpochEvidenceException.CYCLIC_PROOF_INVALID,
                    "Complete cyclic successor proof is invalid for "
                            + afterBlueId + diagnostic);
        }
        throw new IllegalStateException(
                "Unsupported cyclic proof outcome " + outcome);
    }

    private static CyclicSetProof copyCyclicSetProof(
            CyclicSetProof proof) {
        return proof == null
                ? null
                : CyclicSetProof.fromDeclaredPlaceholderSet(
                        proof.declaredPlaceholderSet());
    }

    private static void requireSameEvents(
            ManagedEpochApplicationWork work,
            ManagedEpochReceipt receipt,
            ManagedDocumentTransitionReceipt transition) {
        List<ManagedEventOccurrence> exposed = receipt.emittedEvents();
        List<ManagedRootEventOccurrence> exact =
                transition.emittedRootEvents();
        if (exposed.size() != exact.size()) {
            throw eventMismatch(work, "Managed epoch event count disagrees");
        }
        for (int index = 0; index < exposed.size(); index++) {
            ManagedEventOccurrence publicEvent = exposed.get(index);
            ManagedRootEventOccurrence contractsEvent = exact.get(index);
            if (publicEvent.ordinal() != contractsEvent.ordinal()
                    || publicEvent.eventOccurrenceOrdinal()
                            != contractsEvent.occurrenceOrdinal()
                    || !publicEvent.sourceDocumentId().value().equals(
                            contractsEvent.sourceDocumentId().value())
                    || !publicEvent.eventOccurrenceIdentity().equals(
                            contractsEvent.occurrenceIdentity())
                    || !publicEvent.eventBlueId().equals(
                            contractsEvent.eventBlueId())
                    || publicEvent.publicAtSource()
                            != contractsEvent.publicAtSource()) {
                throw eventMismatch(
                        work,
                        "Managed epoch event occurrence " + index
                                + " disagrees with Contracts evidence");
            }
        }
    }

    private static ManagedEpochEvidenceException eventMismatch(
            ManagedEpochApplicationWork work, String message) {
        return mismatch(
                work,
                ManagedEpochEvidenceException.EVENT_OCCURRENCE_MISMATCH,
                message);
    }

    private static ManagedEpochEvidenceException mismatch(
            ManagedEpochApplicationWork work,
            String code,
            String message) {
        return ManagedEpochEvidenceException.blocked(
                work, code, message + " for " + work.workIdentity());
    }

    record VerifiedSourceEvidence(
            ManagedEpochReceipt receipt,
            ManagedDocumentTransitionReceipt transitionReceipt,
            CyclicSetProof afterCyclicProof) {
        VerifiedSourceEvidence {
            receipt = Objects.requireNonNull(receipt, "receipt");
            afterCyclicProof = copyCyclicSetProof(afterCyclicProof);
        }

        @Override
        public CyclicSetProof afterCyclicProof() {
            return copyCyclicSetProof(afterCyclicProof);
        }
    }
}
