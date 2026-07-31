package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.repo.BlueRepository;
import blue.repo.bootstrap.DocumentBootstrap;
import blue.repo.common.CryptoEd25519Verify;
import blue.repo.coordination.APICall;
import blue.repo.coordination.Actor;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.Authority;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.Compute;
import blue.repo.coordination.ComputeDefinition;
import blue.repo.coordination.DocumentStatus;
import blue.repo.coordination.Event;
import blue.repo.coordination.Operation;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.Request;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.StatusCompleted;
import blue.repo.coordination.StatusFailed;
import blue.repo.coordination.StatusInProgress;
import blue.repo.coordination.StatusPending;
import blue.repo.coordination.TerminateProcessing;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.coordination.TriggerEvent;
import blue.repo.coordination.UpdateDocument;
import blue.repo.mandate.DocumentResponderMandate;
import blue.repo.mandate.Mandate;
import blue.repo.mandate.MandateActivated;
import blue.repo.mandate.MandateAuthority;
import blue.repo.mandate.MandateAuthorityConfirmed;
import blue.repo.mandate.MandateTerminated;
import blue.repo.mandate.OperationMandate;
import blue.repo.mandate.StatusActive;
import blue.repo.mandate.StatusAuthorityConfirmed;
import blue.repo.mandate.StatusTerminated;
import blue.repo.myos.MyOSAdminActor;
import blue.repo.myos.MyOSAgentActor;
import blue.repo.myos.MyOSDocumentBootstrapMandate;
import blue.repo.myos.MyOSDocumentOperationMandate;
import blue.repo.myos.MyOSSessionSubscriptionMandate;
import blue.repo.myos.MyOSTimeline;
import blue.repo.myos.MyOSTimelineChannel;
import blue.repo.myos.PrincipalActor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-closed smoke for the exact local Repository/Language integration.
 *
 * <p>This deliberately validates provider content through the configured
 * Language boundary. A generated constant agreeing with the manifest is not
 * sufficient when the body stored under that identity hashes differently.</p>
 */
final class LocalFixedRepositoryCompatibilityTest {
    private static final String FIXED_REPOSITORY_VERSION =
            "1.3.0";
    private static final String FIXED_REPOSITORY_VERSION_BLUE_ID =
            "msCV6VLe4Y1hayq2RnPbuzqZbroowpfBKexXoXBirZq";

    @Test
    void shouldExposeTheExactFixedRepositoryManifestIdentity() {
        // Given
        BlueRepository repository =
                BlueRepository.latest();

        // When
        String version =
                repository.repositoryVersion();
        String versionBlueId =
                repository.repositoryVersionBlueId();

        // Then
        assertEquals(
                FIXED_REPOSITORY_VERSION,
                version);
        assertEquals(
                FIXED_REPOSITORY_VERSION_BLUE_ID,
                versionBlueId);
    }

    @Test
    void shouldResolveEveryRequiredGeneratedTypeAtItsManifestBlueId() {
        // Given
        BlueRepository repository =
                BlueRepository.latest();
        Blue blue =
                new Blue();
        FixedRepositoryBoundSourceProvider.configureReleaseRuntime(
                repository,
                blue);
        List<RequiredType> requiredTypes =
                requiredTypes();
        List<String> failures =
                new ArrayList<String>();

        // When
        try {
            for (RequiredType requiredType : requiredTypes) {
                inspectRequiredType(
                        repository,
                        blue,
                        requiredType,
                        failures);
            }
        } finally {
            blue.close();
        }

        // Then
        assertFalse(requiredTypes.isEmpty());
        assertTrue(
                failures.isEmpty(),
                "Local fixed Repository content is incompatible "
                        + "with the local Language verifier:\n"
                        + String.join("\n", failures));
    }

    private static void inspectRequiredType(
            BlueRepository repository,
            Blue blue,
            RequiredType requiredType,
            List<String> failures) {
        String manifestBlueId;
        try {
            manifestBlueId =
                    repository.blueId(
                            requiredType.qualifiedName);
        } catch (RuntimeException missingDefinition) {
            failures.add(
                    requiredType.qualifiedName
                            + ": manifest lookup failed: "
                            + missingDefinition.getMessage());
            return;
        }
        if (!requiredType.generatedBlueId.equals(
                manifestBlueId)) {
            failures.add(
                    requiredType.qualifiedName
                            + ": generated BlueId "
                            + requiredType.generatedBlueId
                            + " differs from manifest BlueId "
                            + manifestBlueId);
            return;
        }
        try {
            List<Node> content =
                    blue.getNodeProvider()
                            .fetchByBlueId(
                                    manifestBlueId);
            if (content == null
                    || content.isEmpty()) {
                failures.add(
                        requiredType.qualifiedName
                                + ": no provider content for "
                                + manifestBlueId);
            }
        } catch (RuntimeException invalidEvidence) {
            failures.add(
                    requiredType.qualifiedName
                            + ": "
                            + invalidEvidence.getMessage());
        }
    }

    private static List<RequiredType> requiredTypes() {
        return Arrays.asList(
                required(
                        "Bootstrap/Document Bootstrap",
                        DocumentBootstrap.blueId()),
                required(
                        "Common/Crypto Ed25519 Verify",
                        CryptoEd25519Verify.blueId()),
                required(
                        "Coordination/API Call",
                        APICall.blueId()),
                required(
                        "Coordination/Actor",
                        Actor.blueId()),
                required(
                        "Coordination/Authority",
                        Authority.blueId()),
                required(
                        "Coordination/Chat Message",
                        ChatMessage.blueId()),
                required(
                        "Coordination/Document Status",
                        DocumentStatus.blueId()),
                required(
                        "Coordination/Event",
                        Event.blueId()),
                required(
                        "Coordination/Request",
                        Request.blueId()),
                required(
                        "Coordination/Timeline",
                        Timeline.blueId()),
                required(
                        "Coordination/Timeline Channel",
                        TimelineChannel.blueId()),
                required(
                        "Coordination/Timeline Entry",
                        TimelineEntry.blueId()),
                required(
                        "Coordination/Composite Timeline Channel",
                        CompositeTimelineChannel.blueId()),
                required(
                        "Coordination/All Timelines Channel",
                        AllTimelinesChannel.blueId()),
                required(
                        "Coordination/Operation",
                        Operation.blueId()),
                required(
                        "Coordination/Operation Request",
                        OperationRequest.blueId()),
                required(
                        "Coordination/Sequential Workflow",
                        SequentialWorkflow.blueId()),
                required(
                        "Coordination/Sequential Workflow Operation",
                        SequentialWorkflowOperation.blueId()),
                required(
                        "Coordination/Sequential Workflow Step",
                        SequentialWorkflowStep.blueId()),
                required(
                        "Coordination/Chat Workflow Operation",
                        ChatWorkflowOperation.blueId()),
                required(
                        "Coordination/Update Document",
                        UpdateDocument.blueId()),
                required(
                        "Coordination/Trigger Event",
                        TriggerEvent.blueId()),
                required(
                        "Coordination/Terminate Processing",
                        TerminateProcessing.blueId()),
                required(
                        "Coordination/Compute",
                        Compute.blueId()),
                required(
                        "Coordination/Compute Definition",
                        ComputeDefinition.blueId()),
                required(
                        "Coordination/Status Completed",
                        StatusCompleted.blueId()),
                required(
                        "Coordination/Status Failed",
                        StatusFailed.blueId()),
                required(
                        "Coordination/Status In Progress",
                        StatusInProgress.blueId()),
                required(
                        "Coordination/Status Pending",
                        StatusPending.blueId()),
                required(
                        "Mandate/Mandate",
                        Mandate.blueId()),
                required(
                        "Mandate/Mandate Activated",
                        MandateActivated.blueId()),
                required(
                        "Mandate/Mandate Authority",
                        MandateAuthority.blueId()),
                required(
                        "Mandate/Mandate Authority Confirmed",
                        MandateAuthorityConfirmed.blueId()),
                required(
                        "Mandate/Mandate Terminated",
                        MandateTerminated.blueId()),
                required(
                        "Mandate/Operation Mandate",
                        OperationMandate.blueId()),
                required(
                        "Mandate/Document Responder Mandate",
                        DocumentResponderMandate.blueId()),
                required(
                        "Mandate/Status Active",
                        StatusActive.blueId()),
                required(
                        "Mandate/Status Authority Confirmed",
                        StatusAuthorityConfirmed.blueId()),
                required(
                        "Mandate/Status Terminated",
                        StatusTerminated.blueId()),
                required(
                        "MyOS/MyOS Admin Actor",
                        MyOSAdminActor.blueId()),
                required(
                        "MyOS/MyOS Agent Actor",
                        MyOSAgentActor.blueId()),
                required(
                        "MyOS/Principal Actor",
                        PrincipalActor.blueId()),
                required(
                        "MyOS/MyOS Timeline",
                        MyOSTimeline.blueId()),
                required(
                        "MyOS/MyOS Timeline Channel",
                        MyOSTimelineChannel.blueId()),
                required(
                        "MyOS/MyOS Document Operation Mandate",
                        MyOSDocumentOperationMandate.blueId()),
                required(
                        "MyOS/MyOS Document Bootstrap Mandate",
                        MyOSDocumentBootstrapMandate.blueId()),
                required(
                        "MyOS/MyOS Session Subscription Mandate",
                        MyOSSessionSubscriptionMandate.blueId()));
    }

    private static RequiredType required(
            String qualifiedName,
            String generatedBlueId) {
        return new RequiredType(
                qualifiedName,
                generatedBlueId);
    }

    private static final class RequiredType {
        private final String qualifiedName;
        private final String generatedBlueId;

        private RequiredType(
                String qualifiedName,
                String generatedBlueId) {
            this.qualifiedName =
                    qualifiedName;
            this.generatedBlueId =
                    generatedBlueId;
        }
    }
}
