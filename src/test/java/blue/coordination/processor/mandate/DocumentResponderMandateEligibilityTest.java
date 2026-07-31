package blue.coordination.processor.mandate;

import blue.coordination.processor.CoordinationHostQuotaSchedule;
import blue.coordination.processor.CoordinationHostQuotaSession;
import blue.coordination.processor.CoordinationHostQuotaTraceEntry;
import blue.coordination.processor.CoordinationHostQuotas;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.repo.coordination.Request;
import blue.repo.mandate.DocumentResponderMandate;
import blue.repo.mandate.OperationMandate;
import blue.repo.mandate.StatusActive;
import blue.repo.myos.MyOSDocumentBootstrapMandate;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentResponderMandateEligibilityTest {
    @Test
    void shouldAuthorizeProviderWhenAtLeastOneExactCandidateIsActive() {
        // Given
        Fixture fixture = new Fixture();
        Node inactive = fixture.mandate(
                actor("mallory"),
                fixture.requestingInitialDocument,
                new Node().properties(
                        "requestId", new Node().value("other")));

        // When
        MandateEligibilityDecision decision =
                DocumentResponderMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .candidates(Arrays.asList(
                                        DocumentResponderMandateEligibility
                                                .Candidate.complete(
                                                inactive, null),
                                        fixture.candidate()))
                                .build());

        // Then
        assertTrue(decision.isEligible());
        assertEquals(
                "active-document-responder-mandate",
                decision.reason());
    }

    @Test
    void shouldAllowAdditionalExactFieldsBeyondTheRequestTypePattern() {
        // Given
        Fixture fixture = new Fixture();

        // When
        MandateEligibilityDecision decision =
                DocumentResponderMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .candidates(Collections.singletonList(
                                        fixture.candidate()))
                                .build());

        // Then
        assertTrue(decision.isEligible());
    }

    @Test
    void shouldMatchReferenceInitialDocumentAgainstInlineIdentity() {
        // Given
        Fixture fixture = new Fixture();
        Node mandate = fixture.mandate(
                fixture.alice,
                reference(fixture.requestingInitialDocument),
                new Node().type(fixture.requestType.clone()));

        // When
        MandateEligibilityDecision decision =
                DocumentResponderMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .candidates(Collections.singletonList(
                                        DocumentResponderMandateEligibility
                                                .Candidate.complete(
                                                mandate, null)))
                                .build());

        // Then
        assertTrue(decision.isEligible());
    }

    @Test
    void shouldSuspendWhenCandidateEvidenceIsUnresolved() {
        // Given
        Fixture fixture = new Fixture();

        // When
        MandateEligibilityDecision decision =
                DocumentResponderMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .candidates(Collections.singletonList(
                                        DocumentResponderMandateEligibility
                                                .Candidate.incomplete(
                                                reference(
                                                        fixture.mandate(
                                                                fixture.alice,
                                                                fixture.requestingInitialDocument,
                                                                new Node())))))
                                .build());

        // Then
        assertTrue(decision.isSuspended());
        assertEquals(
                "responder-mandate-history-incomplete",
                decision.reason());
    }

    @Test
    void shouldSuspendWhenParticipantChannelIsReferenceBacked() {
        // Given
        Fixture fixture = new Fixture();
        Node mandate = fixture.mandate(
                fixture.alice,
                fixture.requestingInitialDocument,
                new Node().type(fixture.requestType.clone()));
        mandate.getContracts().getProperties().put(
                "authorizedActorChannel",
                reference(channel(fixture.alice)));

        // When
        MandateEligibilityDecision decision =
                DocumentResponderMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .candidates(Collections.singletonList(
                                        DocumentResponderMandateEligibility
                                                .Candidate.complete(
                                                mandate, null)))
                                .build());

        // Then
        assertTrue(decision.isSuspended());
        assertEquals(
                "mandate-participant-channel-unavailable",
                decision.reason());
    }

    @Test
    void shouldRejectCandidateWhenAuthorizedActorDoesNotMatch() {
        // Given
        Fixture fixture = new Fixture();
        Node wrongActor = fixture.mandate(
                actor("mallory"),
                fixture.requestingInitialDocument,
                new Node().type(fixture.requestType.clone()));

        // When
        MandateEligibilityDecision decision =
                evaluateSingleCandidate(fixture, wrongActor);

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "no-matching-document-responder-mandate",
                decision.reason());
    }

    @Test
    void shouldRejectCandidateWhenRequestPatternDoesNotMatch() {
        // Given
        Fixture fixture = new Fixture();
        Node wrongPattern = fixture.mandate(
                fixture.alice,
                fixture.requestingInitialDocument,
                new Node().properties(
                        "requestId",
                        new Node().value("different")));

        // When
        MandateEligibilityDecision decision =
                evaluateSingleCandidate(fixture, wrongPattern);

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "no-matching-document-responder-mandate",
                decision.reason());
    }

    @Test
    void shouldFailClosedBeforeCandidateWorkWhenCandidateLimitIsExceeded() {
        // Given
        Fixture fixture = new Fixture();
        CoordinationHostQuotaSession session =
                CoordinationHostQuotaSession.observing();

        // When
        MandateEligibilityDecision decision =
                DocumentResponderMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .candidates(Collections.nCopies(
                                        CoordinationHostQuotas
                                                .MAX_MANDATE_CANDIDATES_PER_DECISION
                                                + 1,
                                        fixture.candidate()))
                                .build(),
                        session);

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "responder-mandate-candidate-limit-exceeded",
                decision.reason());
        assertTrue(
                session.trace().isEmpty(),
                "rejected candidates must perform and record no work");
    }

    @Test
    void shouldStopCandidateDiagnosticsAfterTheFirstEligibleMatch() {
        // Given
        Fixture fixture = new Fixture();
        CoordinationHostQuotaSession session =
                CoordinationHostQuotaSession.observing();

        // When
        MandateEligibilityDecision decision =
                DocumentResponderMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .candidates(Arrays.asList(
                                        fixture.candidate(),
                                        DocumentResponderMandateEligibility
                                                .Candidate.incomplete(null)))
                                .build(),
                        session);

        // Then
        assertTrue(decision.isEligible(), decision.reason());
        assertEquals(
                1L,
                session.quantity(
                        CoordinationHostQuotaSchedule
                                .RESPONDER_MANDATE_CANDIDATE_TESTED));
        List<CoordinationHostQuotaTraceEntry> trace =
                session.trace();
        assertEquals(9, trace.size());
        assertEquals(
                "/candidates/0/mandateState/validation",
                trace.get(trace.size() - 1).logicalPath());
        for (CoordinationHostQuotaTraceEntry entry : trace) {
            assertFalse(
                    entry.logicalPath().startsWith(
                            "/candidates/1"));
        }
    }

    @Test
    void shouldAuthorizeVerifiedDocumentResponderMandateSubtype() {
        // Given
        Fixture fixture = new Fixture();
        Node subtype = fixture.mandate(
                fixture.alice,
                fixture.requestingInitialDocument,
                new Node().type(
                        fixture.requestType.clone()));
        subtype.type(
                MyOSDocumentBootstrapMandate
                        .repositoryType()
                        .reference());

        // When
        MandateEligibilityDecision decision =
                evaluateSingleCandidate(fixture, subtype);

        // Then
        assertTrue(decision.isEligible());
    }

    @Test
    void shouldRejectDifferentFixedResponderMandateType() {
        // Given
        Fixture fixture = new Fixture();
        Node operationMandate = fixture.mandate(
                fixture.alice,
                fixture.requestingInitialDocument,
                new Node().type(
                        fixture.requestType.clone()));
        operationMandate.type(
                OperationMandate
                        .repositoryType()
                        .reference());

        // When
        MandateEligibilityDecision decision =
                evaluateSingleCandidate(
                        fixture, operationMandate);

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "no-matching-document-responder-mandate",
                decision.reason());
    }

    @Test
    void shouldAllowAbsentOptionalRequestPatternProperty() {
        // Given
        Fixture fixture = new Fixture();
        Node optionalPattern = new Node()
                .type(fixture.requestType.clone())
                .properties("optionalNote", new Node());
        Node mandate = fixture.mandate(
                fixture.alice,
                fixture.requestingInitialDocument,
                optionalPattern);

        // When
        MandateEligibilityDecision decision =
                evaluateSingleCandidate(fixture, mandate);

        // Then
        assertTrue(decision.isEligible());
    }

    private static MandateEligibilityDecision evaluateSingleCandidate(
            Fixture fixture,
            Node mandate) {
        return DocumentResponderMandateEligibility.evaluate(
                fixture.evidenceBuilder()
                        .candidates(Collections.singletonList(
                                DocumentResponderMandateEligibility
                                        .Candidate.complete(mandate, null)))
                        .build());
    }

    private static final class Fixture {
        private final Node responderMandateType =
                DocumentResponderMandate
                        .repositoryType()
                        .reference();
        private final Node activeStatusType =
                StatusActive.repositoryType().reference();
        private final Node requestType =
                Request.repositoryType().reference();
        private final Node alice = actor("alice");
        private final Node bob = actor("bob");
        private final Node admin = actor("admin");
        private final Node requestingInitialDocument =
                new Node().name("Requester");
        private final Node request = new Node()
                .type(requestType.clone())
                .properties(
                        "requestId", new Node().value("R1"));

        private Node mandate(
                Node authorizedActor,
                Node initialDocument,
                Node requestPattern) {
            return new Node()
                    .type(responderMandateType.clone())
                    .properties(
                            "status",
                            new Node().type(activeStatusType.clone()))
                    .properties(
                            "activatedAt",
                            new Node().value(10))
                    .properties(
                            "authorizedInitialDocument",
                            initialDocument.clone())
                    .properties(
                            "validation",
                            new Node().properties(
                                    "request",
                                    requestPattern.clone()))
                    .contracts(
                            new Node()
                                    .properties(
                                            "mandateGuarantorChannel",
                                            channel(admin))
                                    .properties(
                                            "authorityHolderChannel",
                                            channel(bob))
                                    .properties(
                                            "authorizedActorChannel",
                                            channel(authorizedActor)));
        }

        private DocumentResponderMandateEligibility.Candidate
        candidate() {
            return DocumentResponderMandateEligibility.Candidate.complete(
                    mandate(
                            alice,
                            requestingInitialDocument,
                            new Node().type(requestType.clone())),
                    null);
        }

        private DocumentResponderMandateEligibility.Evidence.Builder
        evidenceBuilder() {
            return DocumentResponderMandateEligibility.Evidence.builder()
                    .requestTimestamp(BigInteger.valueOf(100))
                    .providerActor(alice)
                    .requestingInitialDocument(
                            requestingInitialDocument)
                    .request(request);
        }
    }

    private static Node channel(Node actor) {
        return new Node().properties("actor", actor.clone());
    }

    private static Node actor(String accountId) {
        return new Node().properties(
                "accountId", new Node().value(accountId));
    }

    private static Node reference(Node exactNode) {
        return new Node().blueId(
                BlueIdCalculator.calculateBlueId(exactNode));
    }
}
