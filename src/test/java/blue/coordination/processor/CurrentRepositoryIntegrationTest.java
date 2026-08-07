package blue.coordination.processor;

import blue.language.model.Node;
import blue.repo.BlueRepository;
import blue.repo.coordination.TimelineChannel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CurrentRepositoryIntegrationTest {

    @Test
    void shouldExposeTheVerifiedCurrentDictionary() {
        // given
        BlueRepository repository = BlueRepository.current();

        // when
        String timelineChannelBlueId = repository.blueId(
                TimelineChannel.qualifiedName());

        // then
        assertEquals(
                CoordinationTestResources.CURRENT_REPOSITORY_BLUE_ID,
                repository.repositoryBlueId());
        assertEquals(1107, repository.manifest().definitions().size());
        assertEquals(TimelineChannel.blueId(), timelineChannelBlueId);
        assertNotNull(repository.nodeProvider()
                .fetchFirstByBlueId(timelineChannelBlueId));
    }

    @Test
    void shouldPreprocessQualifiedTypesThroughCurrentImports() {
        // given
        BlueRepository repository = BlueRepository.current();
        String yaml = "name: Current repository smoke\n"
                + "contracts:\n"
                + "  timeline:\n"
                + "    type: Coordination/Timeline Channel\n"
                + "    timeline:\n"
                + "      type: Coordination/Timeline\n"
                + "      providerId: smoke\n"
                + "      timelineId: smoke\n";

        // when
        Node preprocessed;
        try (CoordinationTestRuntime runtime =
                     CoordinationTestRuntime.create(repository)) {
            Node authored = runtime.parseSourceYaml(yaml)
                    .blue(repository.importsDirective());
            preprocessed = runtime.preprocess(authored);
        }

        // then
        Node timeline = preprocessed.getContracts()
                .getProperties()
                .get("timeline");
        assertEquals(TimelineChannel.blueId(), timeline.getType().getBlueId());
        assertNull(preprocessed.getBlue());
    }

    @Test
    void shouldFailClosedForAnUnknownRepositoryIdentity() {
        // given
        BlueRepository repository = BlueRepository.current();
        String unknown = "11111111111111111111111111111111";

        // when
        Node resolved = repository.nodeProvider().fetchFirstByBlueId(unknown);

        // then
        assertNull(resolved);
        assertTrue(!repository.blueIds().contains(unknown));
    }
}
