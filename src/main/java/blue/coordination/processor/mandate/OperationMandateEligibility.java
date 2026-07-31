package blue.coordination.processor.mandate;

import blue.coordination.processor.CoordinationHostQuotaSession;
import blue.language.model.Node;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Deterministic feeder-side Operation Mandate eligibility.
 *
 * <p>The caller supplies exact processed state and completeness evidence. This
 * helper performs no storage, Timeline transport, alias resolution, or hidden
 * processor invocation.</p>
 */
public final class OperationMandateEligibility {
    private OperationMandateEligibility() {
    }

    public static MandateEligibilityDecision evaluate(Evidence evidence) {
        return evaluate(
                evidence,
                CoordinationHostQuotaSession.disabled());
    }

    /**
     * Evaluates exact feeder evidence while recording named host predicates in
     * the caller-owned nonportable quota session.
     *
     * @param evidence exact processed mandate, event, and history evidence
     * @param hostQuotas invocation-local host quota session
     * @return deterministic eligibility decision for the supplied evidence
     */
    public static MandateEligibilityDecision evaluate(
            Evidence evidence,
            CoordinationHostQuotaSession hostQuotas) {
        CoordinationHostQuotaSession quotas =
                Objects.requireNonNull(
                        hostQuotas, "hostQuotas");
        quotas.recordOperationMandatePredicate(
                "/evidence",
                "evidence-present");
        if (evidence == null) {
            return MandateEligibilityDecision.suspended(
                    "mandate-evidence-unavailable");
        }
        quotas.recordOperationMandatePredicate(
                "/historyCompleteAtEventTime",
                "history-complete");
        if (!Boolean.TRUE.equals(evidence.historyCompleteAtEventTime)) {
            return MandateEligibilityDecision.suspended(
                    "mandate-history-incomplete");
        }
        quotas.recordOperationMandatePredicate(
                "/mandateState",
                "exact-state-and-event");
        if (evidence.mandateState == null
                || evidence.mandateState.isReferenceOnly()
                || evidence.event == null
                || evidence.event.isReferenceOnly()) {
            return MandateEligibilityDecision.suspended(
                    "mandate-state-or-event-unavailable");
        }
        try (MandateEligibilityNodes.MatchingContext matching =
                     MandateEligibilityNodes
                             .fixedRepositoryMatchingContext()) {
            quotas.recordOperationMandatePredicate(
                    "/mandateState/type",
                    "operation-mandate-type");
            MandateEligibilityNodes.Match mandateType =
                    matching.operationMandateType(
                            evidence.mandateState);
            if (mandateType
                    == MandateEligibilityNodes.Match.UNAVAILABLE) {
                return MandateEligibilityDecision.suspended(
                        "mandate-type-evidence-unavailable");
            }
            requireValidTypeEvidence(mandateType);
            if (mandateType
                    != MandateEligibilityNodes.Match.MATCH) {
                return MandateEligibilityDecision.ineligible(
                        "operation-mandate-type-mismatch");
            }
            quotas.recordOperationMandatePredicate(
                    "/event/timestamp",
                    "event-timestamp");
            BigInteger eventTimestamp = requiredInteger(
                    MandateEligibilityNodes.property(
                            evidence.event, "timestamp"));
            if (eventTimestamp == null) {
                return MandateEligibilityDecision.ineligible(
                        "event-timestamp-invalid");
            }
            quotas.recordOperationMandatePredicate(
                    "/mandateState/status",
                    "active-window");
            MandateEligibilityDecision state = activeAt(
                    matching,
                    evidence.mandateState,
                    eventTimestamp);
            if (state != null) {
                return state;
            }
            quotas.recordOperationMandatePredicate(
                    "/mandateState/contracts",
                    "participants");
            MandateEligibilityDecision participants =
                    operationParticipantsMatch(
                            evidence, matching);
            if (participants != null) {
                return participants;
            }
            quotas.recordOperationMandatePredicate(
                    "/mandateState/target",
                    "target");
            MandateEligibilityDecision target = targetMatches(evidence);
            if (target != null) {
                return target;
            }
            quotas.recordOperationMandatePredicate(
                    "/event/message/document",
                    "current-document");
            MandateEligibilityDecision precondition =
                    currentDocumentPrecondition(evidence);
            if (precondition != null) {
                return precondition;
            }
            Node message = MandateEligibilityNodes.property(
                    evidence.event, "message");
            Node request = MandateEligibilityNodes.property(
                    message, "request");
            quotas.recordOperationMandatePredicate(
                    "/mandateState/validation",
                    "request-validation");
            MandateEligibilityDecision validation =
                    validateRequest(
                            matching,
                            evidence.mandateState,
                            request,
                            evidence.validationEvidence);
            if (validation != null) {
                return validation;
            }
            return MandateEligibilityDecision.eligible(
                    "active-operation-mandate",
                    MandateEligibilityNodes.exactBlueId(
                            evidence.mandateState,
                            "processed Operation Mandate state"));
        } catch (IllegalArgumentException invalidEvidence) {
            return MandateEligibilityDecision.ineligible(
                    "invalid-exact-mandate-evidence");
        }
    }

    static MandateEligibilityDecision activeAt(
            MandateEligibilityNodes.MatchingContext matching,
            Node mandateState,
            BigInteger timestamp) {
        Node status = MandateEligibilityNodes.property(
                mandateState, "status");
        if (status == null) {
            return MandateEligibilityDecision.ineligible(
                    "mandate-status-missing");
        }
        if (status.isReferenceOnly()) {
            return MandateEligibilityDecision.suspended(
                    "mandate-status-unavailable");
        }
        MandateEligibilityNodes.Match activeType =
                matching.activeStatusType(status);
        if (activeType
                == MandateEligibilityNodes.Match.UNAVAILABLE) {
            return MandateEligibilityDecision.suspended(
                    "mandate-status-unavailable");
        }
        requireValidTypeEvidence(activeType);
        if (activeType
                != MandateEligibilityNodes.Match.MATCH) {
            return MandateEligibilityDecision.ineligible(
                    "mandate-not-active");
        }
        Node activatedNode = MandateEligibilityNodes.property(
                mandateState, "activatedAt");
        if (activatedNode != null && activatedNode.isReferenceOnly()) {
            return MandateEligibilityDecision.suspended(
                    "mandate-activation-evidence-unavailable");
        }
        BigInteger activatedAt = requiredInteger(activatedNode);
        if (activatedAt == null
                || activatedAt.compareTo(timestamp) > 0) {
            return MandateEligibilityDecision.ineligible(
                    "mandate-not-active-at-event-time");
        }
        Node terminatedNode = MandateEligibilityNodes.property(
                mandateState, "terminatedAt");
        if (terminatedNode != null && terminatedNode.isReferenceOnly()) {
            return MandateEligibilityDecision.suspended(
                    "mandate-termination-evidence-unavailable");
        }
        if (terminatedNode != null) {
            BigInteger terminatedAt = requiredInteger(terminatedNode);
            if (terminatedAt == null) {
                return MandateEligibilityDecision.ineligible(
                        "mandate-termination-timestamp-invalid");
            }
            if (terminatedAt.compareTo(timestamp) <= 0) {
                return MandateEligibilityDecision.ineligible(
                        "mandate-terminated-at-event-time");
            }
        }
        return null;
    }

    static MandateEligibilityDecision validateRequest(
            MandateEligibilityNodes.MatchingContext matching,
            Node mandateState,
            Node request,
            MandateValidationEvidence validationEvidence) {
        Node validation = MandateEligibilityNodes.property(
                mandateState, "validation");
        if (validation == null) {
            return null;
        }
        if (validation.isReferenceOnly()) {
            return MandateEligibilityDecision.suspended(
                    "mandate-validation-unavailable");
        }
        Node requestPattern = MandateEligibilityNodes.property(
                validation, "request");
        if (requestPattern != null) {
            MandateEligibilityNodes.Match match =
                    MandateEligibilityNodes.matchesPattern(
                            matching,
                            request,
                            requestPattern);
            if (match == MandateEligibilityNodes.Match.UNAVAILABLE) {
                return MandateEligibilityDecision.suspended(
                        "mandate-request-evidence-unavailable");
            }
            if (match == MandateEligibilityNodes.Match.INVALID) {
                return MandateEligibilityDecision.ineligible(
                        "mandate-request-evidence-invalid");
            }
            if (match == MandateEligibilityNodes.Match.NO_MATCH) {
                return MandateEligibilityDecision.ineligible(
                        "mandate-request-pattern-mismatch");
            }
        }
        Node function = MandateEligibilityNodes.property(
                validation, "function");
        if (function == null) {
            return null;
        }
        if (request == null || request.isReferenceOnly()
                || validationEvidence == null
                || validationEvidence.outcome()
                == MandateValidationEvidence.Outcome.UNAVAILABLE) {
            return MandateEligibilityDecision.suspended(
                    validationEvidence != null
                            ? validationEvidence.reason()
                            : "mandate-validation-evidence-unavailable");
        }
        if (!validationEvidence.isBoundTo(function, request)) {
            return MandateEligibilityDecision.suspended(
                    "mandate-validation-evidence-mismatch");
        }
        if (validationEvidence.outcome()
                == MandateValidationEvidence.Outcome.REJECTED) {
            return MandateEligibilityDecision.ineligible(
                    validationEvidence.reason());
        }
        return null;
    }

    private static MandateEligibilityDecision operationParticipantsMatch(
            Evidence evidence,
            MandateEligibilityNodes.MatchingContext matching) {
        Node guarantorChannel = MandateEligibilityNodes.participant(
                evidence.mandateState, "mandateGuarantorChannel");
        Node holderChannel = MandateEligibilityNodes.participant(
                evidence.mandateState, "authorityHolderChannel");
        Node authorizedChannel = MandateEligibilityNodes.participant(
                evidence.mandateState, "authorizedActorChannel");
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
        Node eventActor = MandateEligibilityNodes.property(
                evidence.event, "actor");
        if (eventActor == null
                || !MandateEligibilityNodes.sameExact(
                eventActor, authorized)) {
            return MandateEligibilityDecision.ineligible(
                    "authorized-actor-mismatch");
        }
        Node authority = MandateEligibilityNodes.property(
                evidence.event, "onBehalfOf");
        if (authority == null || authority.isReferenceOnly()) {
            return MandateEligibilityDecision.suspended(
                    "mandate-authority-evidence-unavailable");
        }
        MandateEligibilityNodes.Match authorityType =
                matching.mandateAuthorityType(authority);
        if (authorityType
                == MandateEligibilityNodes.Match.UNAVAILABLE) {
            return MandateEligibilityDecision.suspended(
                    "mandate-authority-evidence-unavailable");
        }
        requireValidTypeEvidence(authorityType);
        if (authorityType
                != MandateEligibilityNodes.Match.MATCH) {
            return MandateEligibilityDecision.ineligible(
                    "mandate-authority-type-mismatch");
        }
        Node claimedHolder = MandateEligibilityNodes.property(
                authority, "actor");
        if (claimedHolder == null
                || !MandateEligibilityNodes.sameExact(
                claimedHolder, holder)) {
            return MandateEligibilityDecision.ineligible(
                    "authority-holder-mismatch");
        }
        Node claimedInitialMandate =
                MandateEligibilityNodes.property(
                        authority, "initialMandateDocument");
        if (evidence.initialMandateDocument == null
                || claimedInitialMandate == null) {
            return MandateEligibilityDecision.suspended(
                    "initial-mandate-document-evidence-unavailable");
        }
        if (!MandateEligibilityNodes.sameExact(
                claimedInitialMandate,
                evidence.initialMandateDocument)) {
            return MandateEligibilityDecision.ineligible(
                    "initial-mandate-document-mismatch");
        }
        return null;
    }

    private static MandateEligibilityDecision targetMatches(
            Evidence evidence) {
        Node target = MandateEligibilityNodes.property(
                evidence.mandateState, "target");
        Node message = MandateEligibilityNodes.property(
                evidence.event, "message");
        if (target == null || message == null
                || target.isReferenceOnly()
                || message.isReferenceOnly()) {
            return MandateEligibilityDecision.suspended(
                    "mandate-target-or-request-unavailable");
        }
        Node initialDocument = MandateEligibilityNodes.property(
                target, "initialDocument");
        if (evidence.targetInitialDocument == null
                || initialDocument == null) {
            return MandateEligibilityDecision.suspended(
                    "target-initial-document-evidence-unavailable");
        }
        if (!MandateEligibilityNodes.sameExact(
                initialDocument, evidence.targetInitialDocument)) {
            return MandateEligibilityDecision.ineligible(
                    "target-initial-document-mismatch");
        }
        String mandatedChannel = MandateEligibilityNodes.text(
                MandateEligibilityNodes.property(target, "channel"));
        String requestedChannel = MandateEligibilityNodes.text(
                MandateEligibilityNodes.property(message, "channel"));
        if (mandatedChannel == null
                || !mandatedChannel.equals(requestedChannel)) {
            return MandateEligibilityDecision.ineligible(
                    "target-channel-mismatch");
        }
        String mandatedOperation = MandateEligibilityNodes.text(
                MandateEligibilityNodes.property(target, "operation"));
        String requestedOperation = MandateEligibilityNodes.text(
                MandateEligibilityNodes.property(message, "operation"));
        if (mandatedOperation == null
                || !mandatedOperation.equals(requestedOperation)) {
            return MandateEligibilityDecision.ineligible(
                    "target-operation-mismatch");
        }
        return null;
    }

    private static MandateEligibilityDecision currentDocumentPrecondition(
            Evidence evidence) {
        Node message = MandateEligibilityNodes.property(
                evidence.event, "message");
        Node exactVersionNode = MandateEligibilityNodes.property(
                message, "requireExactDocumentVersion");
        if (exactVersionNode != null && exactVersionNode.isReferenceOnly()) {
            return MandateEligibilityDecision.suspended(
                    "document-version-policy-unavailable");
        }
        Object exactVersion = exactVersionNode != null
                ? exactVersionNode.getValue()
                : null;
        if (exactVersionNode != null
                && !(exactVersion instanceof Boolean)) {
            return MandateEligibilityDecision.ineligible(
                    "require-exact-document-version-invalid");
        }

        Node requestDocument = null;
        if (Boolean.TRUE.equals(exactVersion)) {
            requestDocument = MandateEligibilityNodes.property(
                    message, "document");
            if (requestDocument == null) {
                return MandateEligibilityDecision.ineligible(
                        "operation-request-document-required");
            }
        }

        if (requestDocument == null
                && evidence.expectedCurrentDocument == null) {
            return null;
        }
        if (evidence.currentDocument == null) {
            return MandateEligibilityDecision.suspended(
                    "current-document-evidence-unavailable");
        }
        if (requestDocument != null
                && !MandateEligibilityNodes.sameExact(
                requestDocument, evidence.currentDocument)) {
            return MandateEligibilityDecision.ineligible(
                    "current-document-precondition-mismatch");
        }
        if (evidence.expectedCurrentDocument != null
                && !MandateEligibilityNodes.sameExact(
                evidence.expectedCurrentDocument,
                evidence.currentDocument)) {
            return MandateEligibilityDecision.ineligible(
                    "current-document-precondition-mismatch");
        }
        return null;
    }

    private static BigInteger requiredInteger(Node node) {
        return node != null && !node.isReferenceOnly()
                ? MandateEligibilityNodes.integer(node)
                : null;
    }

    private static void requireValidTypeEvidence(
            MandateEligibilityNodes.Match match) {
        if (match == MandateEligibilityNodes.Match.INVALID) {
            throw new IllegalArgumentException(
                    "invalid fixed Mandate type evidence");
        }
    }

    public static final class Evidence {
        private final Node mandateState;
        private final Node initialMandateDocument;
        private final Node event;
        private final Node targetInitialDocument;
        private final Node expectedCurrentDocument;
        private final Node currentDocument;
        private final Boolean historyCompleteAtEventTime;
        private final MandateValidationEvidence validationEvidence;

        private Evidence(Builder builder) {
            this.mandateState = cloneNode(builder.mandateState);
            this.initialMandateDocument =
                    cloneNode(builder.initialMandateDocument);
            this.event = cloneNode(builder.event);
            this.targetInitialDocument =
                    cloneNode(builder.targetInitialDocument);
            this.expectedCurrentDocument =
                    cloneNode(builder.expectedCurrentDocument);
            this.currentDocument = cloneNode(builder.currentDocument);
            this.historyCompleteAtEventTime =
                    builder.historyCompleteAtEventTime;
            this.validationEvidence = builder.validationEvidence;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private Node mandateState;
            private Node initialMandateDocument;
            private Node event;
            private Node targetInitialDocument;
            private Node expectedCurrentDocument;
            private Node currentDocument;
            private Boolean historyCompleteAtEventTime;
            private MandateValidationEvidence validationEvidence;

            public Builder mandateState(Node value) {
                this.mandateState = value;
                return this;
            }

            public Builder initialMandateDocument(Node value) {
                this.initialMandateDocument = value;
                return this;
            }

            public Builder event(Node value) {
                this.event = value;
                return this;
            }

            public Builder targetInitialDocument(Node value) {
                this.targetInitialDocument = value;
                return this;
            }

            public Builder expectedCurrentDocument(Node value) {
                this.expectedCurrentDocument = value;
                return this;
            }

            public Builder currentDocument(Node value) {
                this.currentDocument = value;
                return this;
            }

            public Builder historyCompleteAtEventTime(boolean value) {
                this.historyCompleteAtEventTime = Boolean.valueOf(value);
                return this;
            }

            public Builder validationEvidence(
                    MandateValidationEvidence value) {
                this.validationEvidence = value;
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
