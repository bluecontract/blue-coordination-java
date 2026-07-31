package blue.coordination.processor.mandate;

import blue.coordination.processor.CoordinationHostQuotaSession;
import blue.coordination.processor.CoordinationHostQuotaTraceEntry;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.repo.coordination.Authority;
import blue.repo.coordination.StatusInProgress;
import blue.repo.mandate.DocumentResponderMandate;
import blue.repo.mandate.MandateAuthority;
import blue.repo.mandate.OperationMandate;
import blue.repo.mandate.StatusActive;
import blue.repo.myos.MyOSDocumentOperationMandate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationMandateEligibilityTest {
    @Test
    void shouldRecordEligibleMandatePredicatesInExactOrder() {
        // Given
        Fixture fixture = new Fixture();
        CoordinationHostQuotaSession session =
                CoordinationHostQuotaSession.observing();

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder().build(),
                        session);
        List<String> paths = new ArrayList<String>();
        List<String> reasons = new ArrayList<String>();
        for (CoordinationHostQuotaTraceEntry entry :
                session.trace()) {
            paths.add(entry.logicalPath());
            reasons.add(entry.reason());
        }

        // Then
        assertTrue(decision.isEligible(), decision.reason());
        assertEquals(
                Arrays.asList(
                        "/evidence",
                        "/historyCompleteAtEventTime",
                        "/mandateState",
                        "/mandateState/type",
                        "/event/timestamp",
                        "/mandateState/status",
                        "/mandateState/contracts",
                        "/mandateState/target",
                        "/event/message/document",
                        "/mandateState/validation"),
                paths);
        assertEquals(
                Arrays.asList(
                        "evidence-present",
                        "history-complete",
                        "exact-state-and-event",
                        "operation-mandate-type",
                        "event-timestamp",
                        "active-window",
                        "participants",
                        "target",
                        "current-document",
                        "request-validation"),
                reasons);
    }

    @Test
    void shouldAuthorizeFixtureShapedOperationWithActiveExactMandate() {
        // Given
        Fixture fixture = new Fixture();

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder().build());

        // Then
        assertTrue(decision.isEligible(), decision.reason());
        assertEquals("active-operation-mandate", decision.reason());
        assertEquals(
                BlueIdCalculator.calculateBlueId(fixture.mandate),
                decision.selectedMandateBlueId());
    }

    @Test
    void shouldRejectOperationWhenAuthorizedActorDoesNotMatch() {
        // Given
        Fixture fixture = new Fixture();
        Node malloryEvent = fixture.event(actor("mallory"), fixture.request);

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .event(malloryEvent)
                                .build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals("authorized-actor-mismatch", decision.reason());
    }

    @Test
    void shouldRejectOperationWhenCurrentDocumentDoesNotMatch() {
        // Given
        Fixture fixture = new Fixture();

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .expectedCurrentDocument(
                                        new Node().properties(
                                                "revision",
                                                new Node().value(1)))
                                .currentDocument(
                                        new Node().properties(
                                                "revision",
                                                new Node().value(2)))
                                .build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "current-document-precondition-mismatch",
                decision.reason());
    }

    @Test
    void shouldDeriveExactVersionMismatchWithoutCallerPrecondition() {
        // Given
        Fixture fixture = new Fixture();
        Node requestedDocument = documentRevision(1);
        Node currentDocument = documentRevision(2);

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .event(fixture.exactVersionEvent(
                                        requestedDocument))
                                .currentDocument(currentDocument)
                                .build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "current-document-precondition-mismatch",
                decision.reason());
    }

    @Test
    void shouldSuspendExactVersionRequestWhenCurrentStateIsUnavailable() {
        // Given
        Fixture fixture = new Fixture();

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .event(fixture.exactVersionEvent(
                                        documentRevision(1)))
                                .build());

        // Then
        assertTrue(decision.isSuspended());
        assertEquals(
                "current-document-evidence-unavailable",
                decision.reason());
    }

    @Test
    void shouldAcceptExactVersionRequestAcrossInlineAndReferenceForms() {
        // Given
        Fixture fixture = new Fixture();
        Node currentDocument = documentRevision(1);

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .event(fixture.exactVersionEvent(
                                        reference(currentDocument)))
                                .currentDocument(currentDocument)
                                .build());

        // Then
        assertTrue(decision.isEligible());
    }

    @Test
    void shouldNotRequireCurrentStateWhenExactVersionFlagIsFalse() {
        // Given
        Fixture fixture = new Fixture();
        Node falseFlagEvent = fixture.event(
                fixture.alice, fixture.request);
        falseFlagEvent.getAsNode("/message").properties(
                "document", documentRevision(1));
        falseFlagEvent.getAsNode("/message").properties(
                "requireExactDocumentVersion",
                new Node().value(false));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .event(falseFlagEvent)
                                .build());

        // Then
        assertTrue(decision.isEligible());
    }

    @Test
    void shouldNotRequireCurrentStateWhenExactVersionFlagIsAbsent() {
        // Given
        Fixture fixture = new Fixture();

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder().build());

        // Then
        assertTrue(decision.isEligible());
    }

    @Test
    void shouldRequireDocumentWhenExactVersionIsRequested() {
        // Given
        Fixture fixture = new Fixture();
        Node missingDocument = fixture.event(
                fixture.alice, fixture.request);
        missingDocument.getAsNode("/message").properties(
                "requireExactDocumentVersion",
                new Node().value(true));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .event(missingDocument)
                                .currentDocument(documentRevision(1))
                                .build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "operation-request-document-required",
                decision.reason());
    }

    @Test
    void shouldRejectNonBooleanExactVersionPolicy() {
        // Given
        Fixture fixture = new Fixture();
        Node malformedPolicy = fixture.event(
                fixture.alice, fixture.request);
        malformedPolicy.getAsNode("/message").properties(
                "requireExactDocumentVersion",
                new Node().value("true"));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .event(malformedPolicy)
                                .currentDocument(documentRevision(1))
                                .build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "require-exact-document-version-invalid",
                decision.reason());
    }

    @Test
    void shouldTreatInlineAndPureReferenceInitialDocumentsAsEquivalent() {
        // Given
        Fixture fixture = new Fixture();
        Node event = fixture.event(fixture.alice, fixture.request);
        event.getAsNode("/onBehalfOf").getProperties().put(
                "initialMandateDocument",
                reference(fixture.initialMandate));
        fixture.mandate.getAsNode("/target").getProperties().put(
                "initialDocument",
                reference(fixture.targetInitialDocument));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .event(event)
                                .initialMandateDocument(
                                        fixture.initialMandate)
                                .targetInitialDocument(
                                        fixture.targetInitialDocument)
                                .build());

        // Then
        assertTrue(decision.isEligible());
    }

    @Test
    void shouldAuthorizeWhenStaticPatternAndBoundValidationEvidencePass() {
        // Given
        Fixture fixture = new Fixture();
        Node function = validationFunction();
        Node requestPattern = new Node().properties(
                "amount", new Node().value(7));
        fixture.mandate.properties(
                "validation",
                new Node()
                        .properties("request", requestPattern)
                        .properties("function", function));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .validationEvidence(
                                        MandateValidationEvidence.passed(
                                                function,
                                                fixture.request))
                                .build());

        // Then
        assertTrue(decision.isEligible());
    }

    @Test
    void shouldRejectWhenBoundValidationEvidenceRejectsRequest() {
        // Given
        Fixture fixture = new Fixture();
        Node function = validationFunction();
        fixture.mandate.properties(
                "validation",
                new Node().properties("function", function));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .validationEvidence(
                                        MandateValidationEvidence.rejected(
                                                function,
                                                fixture.request,
                                                "mandate-validation-function-rejected"))
                                .build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "mandate-validation-function-rejected",
                decision.reason());
    }

    @Test
    void shouldRejectWhenStaticRequestPatternDoesNotMatch() {
        // Given
        Fixture fixture = new Fixture();
        Node function = validationFunction();
        Node requestPattern = new Node().properties(
                "amount", new Node().value(7));
        fixture.mandate.properties(
                "validation",
                new Node()
                        .properties("request", requestPattern)
                        .properties("function", function));
        Node mismatchingRequest = new Node().properties(
                "amount", new Node().value(8));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .event(fixture.event(
                                        fixture.alice,
                                        mismatchingRequest))
                                .validationEvidence(
                                        MandateValidationEvidence.passed(
                                                function,
                                                mismatchingRequest))
                                .build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "mandate-request-pattern-mismatch",
                decision.reason());
    }

    @Test
    void shouldSuspendWhenMandateHistoryIsIncomplete() {
        // Given
        Fixture fixture = new Fixture();

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .historyCompleteAtEventTime(false)
                                .build());

        // Then
        assertTrue(decision.isSuspended());
        assertEquals("mandate-history-incomplete", decision.reason());
    }

    @Test
    void shouldSuspendWhenValidationEvidenceIsUnavailable() {
        // Given
        Fixture fixture = new Fixture();
        Node function = validationFunction();
        fixture.mandate.properties(
                "validation",
                new Node().properties("function", function));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder().build());

        // Then
        assertTrue(decision.isSuspended());
        assertEquals(
                "mandate-validation-evidence-unavailable",
                decision.reason());
    }

    @Test
    void shouldSuspendWhenParticipantChannelIsReferenceBacked() {
        // Given
        Fixture fixture = new Fixture();
        fixture.mandate.getContracts().getProperties().put(
                "authorizedActorChannel",
                reference(channel(fixture.alice)));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder().build());

        // Then
        assertTrue(decision.isSuspended());
        assertEquals(
                "mandate-participant-channel-unavailable",
                decision.reason());
    }

    @Test
    void shouldRejectMandateActivatedAfterOriginalEventTime() {
        // Given
        Fixture fixture = new Fixture();
        fixture.mandate.properties(
                "activatedAt", new Node().value(101));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder().build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "mandate-not-active-at-event-time",
                decision.reason());
    }

    @Test
    void shouldRejectMandateTerminatedAtOriginalEventTime() {
        // Given
        Fixture fixture = new Fixture();
        fixture.mandate.properties(
                "activatedAt", new Node().value(50));
        fixture.mandate.properties(
                "terminatedAt", new Node().value(100));

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder().build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "mandate-terminated-at-event-time",
                decision.reason());
    }

    @Test
    void shouldAuthorizeVerifiedOperationMandateSubtype() {
        // Given
        Fixture fixture = new Fixture();
        fixture.mandate.type(
                MyOSDocumentOperationMandate
                        .repositoryType()
                        .reference());

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder().build());

        // Then
        assertTrue(decision.isEligible(), decision.reason());
    }

    @Test
    void shouldRejectDifferentFixedMandateType() {
        // Given
        Fixture fixture = new Fixture();
        fixture.mandate.type(
                DocumentResponderMandate
                        .repositoryType()
                        .reference());

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder().build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "operation-mandate-type-mismatch",
                decision.reason());
    }

    @Test
    void shouldRejectStatusParentAsActiveStatus() {
        // Given
        Fixture fixture = new Fixture();
        fixture.mandate.getAsNode("/status").type(
                StatusInProgress
                        .repositoryType()
                        .reference());

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder().build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals("mandate-not-active", decision.reason());
    }

    @Test
    void shouldRejectAuthorityParentAsMandateAuthority() {
        // Given
        Fixture fixture = new Fixture();
        fixture.event.getAsNode("/onBehalfOf").type(
                Authority.repositoryType().reference());

        // When
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        fixture.evidenceBuilder()
                                .event(fixture.event)
                                .build());

        // Then
        assertTrue(decision.isIneligible());
        assertEquals(
                "mandate-authority-type-mismatch",
                decision.reason());
    }

    private static final class Fixture {
        private final Node operationMandateType =
                OperationMandate.repositoryType().reference();
        private final Node activeStatusType =
                StatusActive.repositoryType().reference();
        private final Node mandateAuthorityType =
                MandateAuthority.repositoryType().reference();
        private final Node alice = actor("alice");
        private final Node bob = actor("bob");
        private final Node admin = actor("admin");
        private final Node targetInitialDocument =
                new Node().name("Target").properties(
                        "state", new Node().value(0));
        private final Node initialMandate =
                new Node().name("Initial Mandate").properties(
                        "serial", new Node().value("M1"));
        private final Node request = new Node().properties(
                "amount", new Node().value(7));
        private final Node mandate = mandate();
        private final Node event = event(alice, request);

        private Node mandate() {
            return new Node()
                    .type(operationMandateType.clone())
                    .properties(
                            "status",
                            new Node().type(activeStatusType.clone()))
                    .properties(
                            "activatedAt",
                            new Node().value(50))
                    .properties(
                            "target",
                            new Node()
                                    .properties(
                                            "initialDocument",
                                            targetInitialDocument.clone())
                                    .properties(
                                            "channel",
                                            new Node().value("bob"))
                                    .properties(
                                            "operation",
                                            new Node().value("approve")))
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
                                            channel(alice)));
        }

        private Node event(Node eventActor, Node eventRequest) {
            return new Node()
                    .properties("timestamp", new Node().value(100))
                    .properties("actor", eventActor.clone())
                    .properties(
                            "message",
                            new Node()
                                    .properties(
                                            "channel",
                                            new Node().value("bob"))
                                    .properties(
                                            "operation",
                                            new Node().value("approve"))
                                    .properties(
                                            "request",
                                            eventRequest.clone()))
                    .properties(
                            "onBehalfOf",
                            new Node()
                                    .type(mandateAuthorityType.clone())
                                    .properties(
                                            "actor",
                                            bob.clone())
                                    .properties(
                                            "initialMandateDocument",
                                            initialMandate.clone()));
        }

        private Node exactVersionEvent(Node document) {
            Node exactVersionEvent = event(alice, request);
            exactVersionEvent.getAsNode("/message")
                    .properties("document", document.clone())
                    .properties(
                            "requireExactDocumentVersion",
                            new Node().value(true));
            return exactVersionEvent;
        }

        private OperationMandateEligibility.Evidence.Builder
        evidenceBuilder() {
            return OperationMandateEligibility.Evidence.builder()
                    .mandateState(mandate)
                    .initialMandateDocument(initialMandate)
                    .event(event)
                    .targetInitialDocument(targetInitialDocument)
                    .historyCompleteAtEventTime(true);
        }
    }

    private static Node validationFunction() {
        return new Node()
                .properties("entry", new Node().value("validate"))
                .properties(
                        "functions",
                        new Node().properties(
                                "validate",
                                new Node().properties(
                                        "expr",
                                        new Node().value(true))));
    }

    private static Node documentRevision(int revision) {
        return new Node().properties(
                "revision", new Node().value(revision));
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
