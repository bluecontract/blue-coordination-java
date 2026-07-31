package blue.coordination.processor.mandate;

import blue.coordination.processor.CoordinationHostQuotaSession;
import blue.language.model.Node;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic provider-side selection of an exact active Document Responder
 * Mandate. Persistent candidate lookup and history storage remain external.
 */
public final class DocumentResponderMandateEligibility {
    private DocumentResponderMandateEligibility() {
    }

    public static MandateEligibilityDecision evaluate(Evidence evidence) {
        return evaluate(
                evidence,
                CoordinationHostQuotaSession.disabled());
    }

    /**
     * Evaluates provider evidence while recording admitted host work in the
     * caller-owned nonportable quota session.
     *
     * @param evidence exact responder request and candidate evidence
     * @param hostQuotas invocation-local host quota session
     * @return deterministic eligibility decision for the supplied evidence
     */
    public static MandateEligibilityDecision evaluate(
            Evidence evidence,
            CoordinationHostQuotaSession hostQuotas) {
        CoordinationHostQuotaSession quotas =
                Objects.requireNonNull(
                        hostQuotas, "hostQuotas");
        if (evidence == null || evidence.candidates == null) {
            return MandateEligibilityDecision.suspended(
                    "responder-mandate-evidence-unavailable");
        }
        if (!quotas.admitsResponderCandidates(
                evidence.candidates.size())) {
            return MandateEligibilityDecision.ineligible(
                    "responder-mandate-candidate-limit-exceeded");
        }
        quotas.recordDocumentResponderMandatePredicate(
                "/evidence",
                "evidence-complete");
        if (evidence.requestTimestamp == null
                || evidence.providerActor == null
                || evidence.requestingInitialDocument == null
                || evidence.request == null) {
            return MandateEligibilityDecision.suspended(
                    "responder-request-evidence-unavailable");
        }
        try (MandateEligibilityNodes.MatchingContext matching =
                     MandateEligibilityNodes
                             .fixedRepositoryMatchingContext()) {
            MandateEligibilityDecision suspended = null;
            for (int index = 0;
                    index < evidence.candidates.size();
                    index++) {
                Candidate candidate =
                        evidence.candidates.get(index);
                quotas.recordResponderCandidate(index);
                MandateEligibilityDecision decision =
                        evaluateCandidate(
                                evidence,
                                candidate,
                                matching,
                                index,
                                quotas);
                if (decision.isEligible()) {
                    return decision;
                }
                if (decision.isSuspended()
                        && suspended == null) {
                    suspended = decision;
                }
            }
            return suspended != null
                    ? suspended
                    : MandateEligibilityDecision.ineligible(
                    "no-matching-document-responder-mandate");
        } catch (IllegalArgumentException invalidEvidence) {
            return MandateEligibilityDecision.ineligible(
                    "invalid-exact-responder-mandate-evidence");
        }
    }

    private static MandateEligibilityDecision evaluateCandidate(
            Evidence evidence,
            Candidate candidate,
            MandateEligibilityNodes.MatchingContext matching,
            int candidateIndex,
            CoordinationHostQuotaSession hostQuotas) {
        String candidatePath =
                "/candidates/" + candidateIndex;
        hostQuotas.recordDocumentResponderMandatePredicate(
                candidatePath + "/history",
                "candidate-history");
        if (candidate == null
                || !Boolean.TRUE.equals(
                candidate.historyCompleteAtRequestTime)
                || candidate.mandateState == null
                || candidate.mandateState.isReferenceOnly()) {
            return MandateEligibilityDecision.suspended(
                    "responder-mandate-history-incomplete");
        }
        try {
            hostQuotas.recordDocumentResponderMandatePredicate(
                    candidatePath + "/mandateState/type",
                    "document-responder-mandate-type");
            MandateEligibilityNodes.Match mandateType =
                    matching.documentResponderMandateType(
                            candidate.mandateState);
            if (mandateType
                    == MandateEligibilityNodes.Match.UNAVAILABLE) {
                return MandateEligibilityDecision.suspended(
                        "responder-mandate-type-evidence-unavailable");
            }
            if (mandateType
                    == MandateEligibilityNodes.Match.INVALID) {
                throw new IllegalArgumentException(
                        "invalid fixed Document Responder Mandate type evidence");
            }
            if (mandateType
                    != MandateEligibilityNodes.Match.MATCH) {
                return MandateEligibilityDecision.ineligible(
                        "document-responder-mandate-type-mismatch");
            }
            hostQuotas.recordDocumentResponderMandatePredicate(
                    candidatePath + "/mandateState/status",
                    "active-window");
            MandateEligibilityDecision active =
                    OperationMandateEligibility.activeAt(
                            matching,
                            candidate.mandateState,
                            evidence.requestTimestamp);
            if (active != null) {
                return active;
            }
            hostQuotas.recordDocumentResponderMandatePredicate(
                    candidatePath + "/mandateState/contracts",
                    "participants");
            Node guarantorChannel = MandateEligibilityNodes.participant(
                    candidate.mandateState,
                    "mandateGuarantorChannel");
            Node holderChannel = MandateEligibilityNodes.participant(
                    candidate.mandateState,
                    "authorityHolderChannel");
            Node authorizedChannel = MandateEligibilityNodes.participant(
                    candidate.mandateState,
                    "authorizedActorChannel");
            if (guarantorChannel == null
                    || holderChannel == null
                    || authorizedChannel == null) {
                return MandateEligibilityDecision.ineligible(
                        "mandate-participant-channel-missing");
            }
            if (guarantorChannel.isReferenceOnly()
                    || holderChannel.isReferenceOnly()
                    || authorizedChannel.isReferenceOnly()) {
                return MandateEligibilityDecision.suspended(
                        "mandate-participant-channel-unavailable");
            }
            Node guarantor = MandateEligibilityNodes.property(
                    guarantorChannel, "actor");
            Node holder = MandateEligibilityNodes.property(
                    holderChannel, "actor");
            Node authorized = MandateEligibilityNodes.property(
                    authorizedChannel, "actor");
            if (guarantor == null || holder == null || authorized == null) {
                return MandateEligibilityDecision.ineligible(
                        "mandate-participant-actor-missing");
            }
            hostQuotas.recordDocumentResponderMandatePredicate(
                    candidatePath + "/providerActor",
                    "provider-actor");
            if (!MandateEligibilityNodes.sameExact(
                    authorized, evidence.providerActor)) {
                return MandateEligibilityDecision.ineligible(
                        "responder-actor-mismatch");
            }
            hostQuotas.recordDocumentResponderMandatePredicate(
                    candidatePath
                            + "/mandateState/authorizedInitialDocument",
                    "initial-document");
            Node initialDocument = MandateEligibilityNodes.property(
                    candidate.mandateState,
                    "authorizedInitialDocument");
            if (initialDocument == null) {
                return MandateEligibilityDecision.ineligible(
                        "authorized-initial-document-missing");
            }
            if (!MandateEligibilityNodes.sameExact(
                    initialDocument,
                    evidence.requestingInitialDocument)) {
                return MandateEligibilityDecision.ineligible(
                        "authorized-initial-document-mismatch");
            }
            hostQuotas.recordDocumentResponderMandatePredicate(
                    candidatePath + "/mandateState/validation",
                    "request-validation");
            MandateEligibilityDecision validation =
                    OperationMandateEligibility.validateRequest(
                            matching,
                            candidate.mandateState,
                            evidence.request,
                            candidate.validationEvidence);
            if (validation != null) {
                return validation;
            }
            return MandateEligibilityDecision.eligible(
                    "active-document-responder-mandate",
                    MandateEligibilityNodes.exactBlueId(
                            candidate.mandateState,
                            "processed Document Responder Mandate state"));
        } catch (IllegalArgumentException invalidEvidence) {
            return MandateEligibilityDecision.ineligible(
                    "invalid-exact-responder-mandate-evidence");
        }
    }

    public static final class Candidate {
        private final Node mandateState;
        private final Boolean historyCompleteAtRequestTime;
        private final MandateValidationEvidence validationEvidence;

        private Candidate(
                Node mandateState,
                Boolean historyCompleteAtRequestTime,
                MandateValidationEvidence validationEvidence) {
            this.mandateState = mandateState != null
                    ? mandateState.clone()
                    : null;
            this.historyCompleteAtRequestTime =
                    historyCompleteAtRequestTime;
            this.validationEvidence = validationEvidence;
        }

        public static Candidate complete(
                Node mandateState,
                MandateValidationEvidence validationEvidence) {
            return new Candidate(
                    mandateState,
                    Boolean.TRUE,
                    validationEvidence);
        }

        public static Candidate incomplete(Node mandateState) {
            return new Candidate(
                    mandateState,
                    Boolean.FALSE,
                    null);
        }
    }

    public static final class Evidence {
        private final BigInteger requestTimestamp;
        private final Node providerActor;
        private final Node requestingInitialDocument;
        private final Node request;
        private final List<Candidate> candidates;

        private Evidence(Builder builder) {
            this.requestTimestamp = builder.requestTimestamp;
            this.providerActor = cloneNode(builder.providerActor);
            this.requestingInitialDocument =
                    cloneNode(builder.requestingInitialDocument);
            this.request = cloneNode(builder.request);
            this.candidates = builder.candidates != null
                    ? Collections.unmodifiableList(
                    new ArrayList<Candidate>(builder.candidates))
                    : null;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private BigInteger requestTimestamp;
            private Node providerActor;
            private Node requestingInitialDocument;
            private Node request;
            private List<Candidate> candidates;

            public Builder requestTimestamp(BigInteger value) {
                this.requestTimestamp = value;
                return this;
            }

            public Builder providerActor(Node value) {
                this.providerActor = value;
                return this;
            }

            public Builder requestingInitialDocument(Node value) {
                this.requestingInitialDocument = value;
                return this;
            }

            public Builder request(Node value) {
                this.request = value;
                return this;
            }

            public Builder candidates(List<Candidate> value) {
                this.candidates = value;
                return this;
            }

            public Evidence build() {
                return new Evidence(this);
            }
        }
    }

    private static Node cloneNode(Node value) {
        return value != null ? value.clone() : null;
    }
}
