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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Task9PublishedArtifactTest {
    private static final String REPOSITORY_AGGREGATE =
            "F7mowRpvfyh7PF2tSNPLEitCFynYjzhj2269nBYqamyM";
    private static final String TERMINATE_PROCESSING_BLUE_ID =
            "DacNQ6C6PgsEiE4QfUHmaWBztpEvo2YyXxUcP86ze77w";
    private static final String CONTRACTS_FIXTURE_IDENTITY =
            "sha256:22713df4d50a38b91762aea1e1a360019c1d16c2584ca1bac022305edb4c66d1";

    @Test
    void repositoryRc8ContainsGeneratedTerminateProcessingContract() {
        BlueRepository repository = BlueRepository.latest();
        RepositoryDefinition definition = repository.definition(TerminateProcessing.qualifiedName())
                .orElseThrow(() -> new AssertionError("Terminate Processing manifest entry is missing"));

        assertEquals(REPOSITORY_AGGREGATE, repository.repositoryVersionBlueId());
        assertEquals(TERMINATE_PROCESSING_BLUE_ID, TerminateProcessing.blueId());
        assertEquals(TERMINATE_PROCESSING_BLUE_ID, definition.blueId());
        assertEquals("blue/repo/definitions/Coordination/TerminateProcessing.json",
                definition.resourcePath());
        assertEquals(CoordinationTypes.TERMINATE_PROCESSING, TerminateProcessing.repositoryType());
        assertTrue(SequentialWorkflowStep.class.isAssignableFrom(TerminateProcessing.class));
        assertEquals("static", new TerminateProcessing().reason("static").getReason());
        assertNotNull(Task9PublishedArtifactTest.class.getClassLoader()
                .getResource(definition.resourcePath()));
    }

    @Test
    void generatedMandateUsesOneComputeStepReturningTermination() {
        Node mandate = BlueRepository.latest().nodeByBlueId(Mandate.blueId())
                .orElseThrow(() -> new AssertionError("Published Mandate definition is missing"));
        Node steps = mandate.getAsNode("/contracts/applyMandateTermination/steps");

        assertEquals(1, steps.getItems().size());
        assertEquals(Compute.blueId(), steps.getItems().get(0).getType().getBlueId());
        assertEquals("Mandate terminated", mandate.get(
                "/contracts/mandateLifecycleDefinition/functions/applyMandateTermination/do/2/$return/termination/reason"));
    }

    @Test
    void publishedLanguageAdvertisesReviewedContractsFixtureIdentity() throws IOException {
        String manifest = resourceText("registry/blue-contracts-1.0/manifest.yaml");

        assertTrue(manifest.contains(
                "conformanceFixturePackageIdentity: \"" + CONTRACTS_FIXTURE_IDENTITY + "\""));
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
