package blue.coordination.processor;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingMetricId;
import blue.language.processor.ProcessingObservation;
import blue.language.processor.ProcessingObserver;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;
import blue.repo.BlueRepository;
import blue.repo.coordination.Actor;
import blue.repo.coordination.ActorPolicy;
import blue.repo.coordination.ComputeDefinition;
import blue.repo.coordination.DocumentAnchors;
import blue.repo.coordination.DocumentLinks;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineEntry;
import blue.repo.coordination.TimelineChannel;
import blue.repo.myos.MyOSTimelineChannel;
import blue.repo.myos.SearchContract;
import blue.repo.workflows.ContractsChangePolicy;
import blue.repo.workflows.DocumentSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Current immutable builder and observer coverage for Coordination wiring. */
final class CoordinationProcessorsTest {

    @Test
    void shouldConfigureStandaloneProcessorBuilderWithoutMutableRuntimeState() {
        // given
        CoordinationTestRuntime runtime =
                CoordinationTestResources.configuredBlue(
                        BlueRepository.current());

        // when
        DocumentProcessor successor =
                CoordinationProcessors.configure(
                                DocumentProcessor.Builder.from(
                                        runtime.processor()))
                        .build();

        // then
        assertNotNull(successor);
        assertNotSame(runtime.processor(), successor);
        successor.close();
        runtime.close();
    }

    @Test
    void shouldRegisterCoordinationChannelsInCurrentContractsRegistry() {
        // given
        ContractProcessorRegistryBuilder builder =
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults();

        // when
        ContractProcessorRegistry registry =
                CoordinationProcessors.configure(builder).build();

        // then
        assertTrue(registry.lookupChannel(
                TimelineChannel.blueId()).isPresent());
    }

    @Test
    void shouldRegisterEveryCurrentRepositoryMarkerCapability() {
        // given
        ContractProcessorRegistry registry = CoordinationProcessors.configure(
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults()).build();

        // when
        List<String> registered = new ArrayList<String>();
        if (registry.lookupMarker(ActorPolicy.blueId()).isPresent()) {
            registered.add(ActorPolicy.blueId());
        }
        if (registry.lookupMarker(ComputeDefinition.blueId()).isPresent()) {
            registered.add(ComputeDefinition.blueId());
        }
        if (registry.lookupMarker(DocumentAnchors.blueId()).isPresent()) {
            registered.add(DocumentAnchors.blueId());
        }
        if (registry.lookupMarker(DocumentLinks.blueId()).isPresent()) {
            registered.add(DocumentLinks.blueId());
        }
        if (registry.lookupMarker(SearchContract.blueId()).isPresent()) {
            registered.add(SearchContract.blueId());
        }
        if (registry.lookupMarker(
                ContractsChangePolicy.blueId()).isPresent()) {
            registered.add(ContractsChangePolicy.blueId());
        }
        if (registry.lookupMarker(DocumentSection.blueId()).isPresent()) {
            registered.add(DocumentSection.blueId());
        }

        // then
        assertEquals(7, registered.size());
    }

    @Test
    void shouldRegisterTimelineSubtypeInSuccessorRegistryGeneration() {
        // given
        ContractProcessorRegistryBuilder builder =
                CoordinationProcessors.configure(
                        ContractProcessorRegistryBuilder.create()
                                .registerDefaults());

        // when
        ContractProcessorRegistry registry =
                CoordinationProcessors.registerTimelineSubtype(
                                builder,
                                MyOSTimelineChannel.class)
                        .build();

        // then
        assertTrue(registry.lookupChannel(
                MyOSTimelineChannel.blueId()).isPresent());
    }

    @Test
    void shouldFanOutTypedObservationsToBothObservers() {
        // given
        List<ProcessingObservation> first =
                new ArrayList<ProcessingObservation>();
        List<ProcessingObservation> second =
                new ArrayList<ProcessingObservation>();
        ProcessingObserver observer = CoordinationProcessors.observers(
                first::add,
                second::add);
        ProcessingObservation observation = ProcessingObservation.of(
                ProcessingMetricId.BLUE_ID_CALCULATIONS,
                3L);

        // when
        observer.record(observation);

        // then
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(observation, first.get(0));
        assertEquals(observation, second.get(0));
    }

    @Test
    void shouldIsolateOneFailingObserverFromTheOther() {
        // given
        AtomicInteger retainedCalls = new AtomicInteger();
        ProcessingObserver observer = CoordinationProcessors.observers(
                observation -> {
                    throw new IllegalStateException("diagnostic failure");
                },
                observation -> retainedCalls.incrementAndGet());

        // when
        observer.record(ProcessingObservation.of(
                ProcessingMetricId.BLUE_ID_CALCULATIONS,
                1L));

        // then
        assertEquals(1, retainedCalls.get());
    }

    @Test
    void shouldRetainLanguageMetricsThroughCurrentObserverBoundary() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ProcessingObservation observation = ProcessingObservation.of(
                ProcessingMetricId.BLUE_ID_CALCULATIONS,
                2L);

        // when
        metrics.record(observation);

        // then
        assertEquals(
                Long.valueOf(2L),
                metrics.languageCounters().get(
                        "blueIdCalculations"));
    }

    @Test
    void shouldKeepPublishedSemanticTypeIdentitiesAsTheDefaultProfile() {
        // given
        CoordinationSemanticTypeIdentities defaults =
                CoordinationSemanticTypeIdentities.publishedDefaults();

        // when
        String profileIdentity = defaults.profileIdentity();

        // then
        assertEquals(TimelineEntry.blueId(),
                defaults.timelineEntryBlueId());
        assertEquals(OperationRequest.blueId(),
                defaults.operationRequestBlueId());
        assertEquals(Timeline.blueId(), defaults.timelineBlueId());
        assertEquals(Actor.blueId(), defaults.actorBlueId());
        assertEquals(profileIdentity,
                CoordinationSemanticTypeIdentities
                        .publishedDefaults().profileIdentity());
    }

    @Test
    void shouldRejectCustomSemanticIdentityWithMismatchedProviderContent() {
        // given
        Node timelineEntry = new Node().name("Test Timeline Entry");
        Node operationRequest = new Node().name("Test Operation Request");
        Node timeline = new Node().name("Test Timeline");
        Node actor = new Node().name("Test Actor");
        CoordinationSemanticTypeIdentities custom =
                CoordinationSemanticTypeIdentities.exact(
                        DirectBlueIdCalculator.calculateBlueId(timelineEntry),
                        DirectBlueIdCalculator.calculateBlueId(operationRequest),
                        DirectBlueIdCalculator.calculateBlueId(timeline),
                        DirectBlueIdCalculator.calculateBlueId(actor));
        Map<String, Node> content = new LinkedHashMap<String, Node>();
        content.put(custom.timelineEntryBlueId(),
                new Node().name("Mismatched Timeline Entry"));
        content.put(custom.operationRequestBlueId(), operationRequest);
        content.put(custom.timelineBlueId(), timeline);
        content.put(custom.actorBlueId(), actor);
        BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(blueId -> {
                    Node node = content.get(blueId);
                    return node == null
                            ? null
                            : Collections.singletonList(node.clone());
                })
                .build();

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CoordinationProcessorOptions.builder()
                        .language(language)
                        .semanticTypeIdentities(custom)
                        .build());

        // then
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("Timeline Entry"),
                failure.getMessage());
        language.close();
    }
}
