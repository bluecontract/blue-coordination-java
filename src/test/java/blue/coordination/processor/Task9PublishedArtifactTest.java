package blue.coordination.processor;

import blue.language.model.Node;
import blue.repo.BlueRepository;
import blue.repo.RepositoryDefinition;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;
import blue.repo.mandate.Mandate;
import blue.repo.types.CoordinationTypes;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Task9PublishedArtifactTest {
    private static final String REPOSITORY_AGGREGATE =
            "DPkjPHvEASr115BcLA4CMXRYKaJRAfmqTyodkndGJ4FY";
    private static final String TERMINATE_PROCESSING_BLUE_ID =
            "DacNQ6C6PgsEiE4QfUHmaWBztpEvo2YyXxUcP86ze77w";
    private static final String CONTRACTS_FIXTURE_IDENTITY =
            "sha256:e35f94c329850f39c705cc3c0222c431e8d6f07142740e39e6b529c228fc96e5";

    @Test
    void repositoryRc10PreviewTerminateContractIsBoundForCompatibility() {
        BlueRepository repository = BlueRepository.latest();
        RepositoryDefinition definition = repository.definition(TerminateProcessing.qualifiedName())
                .orElseThrow(() -> new AssertionError("Terminate Processing manifest entry is missing"));
        Node contract = repository.nodeByBlueId(TerminateProcessing.blueId())
                .orElseThrow(() -> new AssertionError("Terminate Processing definition is missing"));

        assertEquals(REPOSITORY_AGGREGATE, repository.repositoryVersionBlueId());
        assertEquals(TERMINATE_PROCESSING_BLUE_ID, TerminateProcessing.blueId());
        assertEquals(TERMINATE_PROCESSING_BLUE_ID, definition.blueId());
        assertEquals("blue/repo/definitions/Coordination/TerminateProcessing.json",
                definition.resourcePath());
        assertEquals(CoordinationTypes.TERMINATE_PROCESSING, TerminateProcessing.repositoryType());
        assertTrue(SequentialWorkflowStep.class.isAssignableFrom(TerminateProcessing.class));
        assertEquals("static", new TerminateProcessing().reason("static").getReason());
        assertFalse(contract.getProperties().containsKey("cause"),
                "rc10 is a preview dependency; final Contracts 1.0 requires cause");
        assertNotNull(Task9PublishedArtifactTest.class.getClassLoader()
                .getResource(definition.resourcePath()));
    }

    @Test
    void generatedMandateStillUsesPreviewReasonOnlyTermination() {
        Node mandate = BlueRepository.latest().nodeByBlueId(Mandate.blueId())
                .orElseThrow(() -> new AssertionError("Published Mandate definition is missing"));
        Node steps = mandate.getAsNode("/contracts/applyMandateTermination/steps");

        assertEquals(1, steps.getItems().size());
        assertEquals(Compute.blueId(), steps.getItems().get(0).getType().getBlueId());
        assertEquals("terminationReason", mandate.get(
                "/contracts/mandateLifecycleDefinition/functions/applyMandateTermination/do/2/$return/termination/reason/$var"));
        assertFalse(mandate.getAsNode(
                "/contracts/mandateLifecycleDefinition/functions/applyMandateTermination/do/2/$return/termination")
                .getProperties().containsKey("cause"),
                "rc10 Mandate must be regenerated from the final Coordination registry");
    }

    @Test
    void publishedLanguageAdvertisesReviewedContractsFixtureIdentity() throws IOException {
        String manifest = resourceText("registry/blue-contracts-1.0/manifest.yaml");

        assertTrue(manifest.contains(
                "fixturePackageIdentity: " + CONTRACTS_FIXTURE_IDENTITY));
    }

    private static String resourceText(String path) throws IOException {
        InputStream stream = Task9PublishedArtifactTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing published resource " + path);
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
