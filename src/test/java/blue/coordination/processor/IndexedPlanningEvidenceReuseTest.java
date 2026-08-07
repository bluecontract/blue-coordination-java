package blue.coordination.processor;

import blue.coordination.engine.CoordinationProcessingEngine.AdmittedPlanningAuthority;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.provider.NodeProvider;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Differential and capability proof for admitted indexed planning evidence. */
final class IndexedPlanningEvidenceReuseTest {

    @Test
    void shouldMatchTheUntrustedPlannerWithoutProviderRootOrEventVerification()
            throws Exception {
        // given
        BlueRepository repository = BlueRepository.current();
        try (CoordinationTestRuntime blue = CoordinationTestResources
                .configuredBlue(repository)) {
            Node root = initializedRoot(blue, repository);
            String rootBlueId = blueId(root);
            long revision = 11L;
            CoordinationSubscriptionSnapshot snapshot =
                    CoordinationDeliveryPlanning.subscriptionProjector(
                            blue.processor(), blue.contracts()).projectCurrent(
                            root,
                            revision,
                            ExternalOrderKey.of(Collections.emptyList()));
            Node event = TestTimelineProvider.timelineEntry(
                    blue,
                    repository,
                    "matching",
                    2,
                    TestTimelineProvider.chatMessage("planning-oracle"));
            String eventBlueId = blueId(event);
            ExternalOrderKey eventOrder = eventOrder(event);
            List<String> candidates = candidateKeys(snapshot, "matching");
            RecordingProvider coldProvider = provider(
                    rootBlueId, root, eventBlueId, event);
            CoordinationPreparedDelivery untrusted =
                    CoordinationDeliveryPlanning.indexed(
                            blue.processor(), blue.contracts()).prepare(
                            rootBlueId,
                            eventBlueId,
                            snapshot,
                            candidates,
                            coldProvider,
                            revision,
                            eventOrder);
            AdmittedPlanningAuthority authority = authority(
                    blue.processor(), blue.contracts());
            CoordinationIndexedDeliveryPlanner admittedPlanner =
                    CoordinationDeliveryPlanning.indexed(
                            blue.processor(), blue.contracts(), authority);
            RecordingProvider admittedProvider = provider(
                    rootBlueId, root, eventBlueId, event);

            // when
            CoordinationPreparedDelivery admitted =
                    admittedPlanner.prepareAdmitted(
                            authority,
                            rootBlueId,
                            root,
                            eventBlueId,
                            event,
                            snapshot,
                            candidates,
                            admittedProvider,
                            revision,
                            eventOrder);

            // then
            assertEquals(
                    Arrays.asList(rootBlueId, eventBlueId),
                    coldProvider.requests(),
                    "the public boundary defensively fetches and verifies both values");
            assertTrue(admittedProvider.requests().isEmpty(),
                    "the admitted boundary reuses its exact Root/event binding");
            assertEquivalent(untrusted, admitted);
            assertEquals(rootBlueId, admitted.evidence().rootBlueId());
            assertEquals(eventBlueId, admitted.evidence().eventBlueId());
            assertEquals(snapshot.digest(),
                    admitted.subscriptionSnapshotIdentity());
        }
    }

    @Test
    void shouldRejectACapabilityFromAnotherProcessorAndContractsGeneration()
            throws Exception {
        // given
        BlueRepository repository = BlueRepository.current();
        try (CoordinationTestRuntime first = CoordinationTestResources
                .configuredBlue(repository);
             CoordinationTestRuntime second = CoordinationTestResources
                     .configuredBlue(repository)) {
            AdmittedPlanningAuthority foreign = authority(
                    second.processor(), second.contracts());

            // when
            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> CoordinationDeliveryPlanning.indexed(
                            first.processor(), first.contracts(), foreign));

            // then
            assertTrue(failure.getMessage().contains("another processor"));
        }
    }

    private static Node initializedRoot(
            CoordinationTestRuntime blue, BlueRepository repository) {
        Map<String, Node> channels = new LinkedHashMap<>();
        channels.put("matching", TestTimelineProvider.channel("matching"));
        channels.put("other", TestTimelineProvider.channel("other"));
        Node authored = new Node()
                .blue(repository.importsDirective())
                .name("Admitted indexed planning oracle")
                .properties("counter", new Node().value(0))
                .properties("contracts", new Node().properties(channels));
        Node exact = blue.preprocess(authored);
        DocumentProcessingResult initialized = blue.initializeDocument(exact);
        if (initialized.status() != ProcessorStatus.SUCCESS) {
            throw new AssertionError(
                    "fixture initialization failed: " + initialized.status());
        }
        return initialized.document();
    }

    private static List<String> candidateKeys(
            CoordinationSubscriptionSnapshot snapshot, String channelKey) {
        List<CoordinationSubscriptionOccurrence> matches = new ArrayList<>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            if (channelKey.equals(occurrence.channelKey())) {
                matches.add(occurrence);
            }
        }
        matches.sort(Comparator
                .comparingInt(CoordinationSubscriptionOccurrence::order)
                .thenComparing(CoordinationSubscriptionOccurrence::channelKey));
        List<String> result = new ArrayList<>();
        for (CoordinationSubscriptionOccurrence occurrence : matches) {
            result.add(occurrence.occurrenceKey());
        }
        return result;
    }

    private static RecordingProvider provider(
            String rootBlueId,
            Node root,
            String eventBlueId,
            Node event) {
        Map<String, Node> exact = new LinkedHashMap<>();
        exact.put(rootBlueId, root);
        exact.put(eventBlueId, event);
        return new RecordingProvider(exact);
    }

    private static void assertEquivalent(
            CoordinationPreparedDelivery expected,
            CoordinationPreparedDelivery actual) {
        assertEquals(
                planSignature(expected.deliveryPlan()),
                planSignature(actual.deliveryPlan()));
        assertEquals(
                expected.deliveryPlanIdentity(),
                actual.deliveryPlanIdentity());
        assertEquals(
                expected.preselectedOccurrenceOrder(),
                actual.preselectedOccurrenceOrder());
        assertEquals(
                diagnosticSignatures(expected.sourceDeliveries()),
                diagnosticSignatures(actual.sourceDeliveries()));
        assertEquals(
                expected.selectedScopeChainIdentities(),
                actual.selectedScopeChainIdentities());
        assertEquals(
                expected.requiredSeedFragmentIdentities(),
                actual.requiredSeedFragmentIdentities());
        assertEquals(
                expected.prefetchIdentities(),
                actual.prefetchIdentities());
        assertEquals(
                boundarySignature(expected.demandBoundary()),
                boundarySignature(actual.demandBoundary()));
        assertEquals(
                evidenceSignature(expected),
                evidenceSignature(actual));
    }

    private static List<String> planSignature(ExternalDeliveryPlan plan) {
        List<String> result = new ArrayList<>();
        result.add("revision=" + plan.managedRootRevision()
                + "/" + plan.indexedRootRevision());
        result.add("order=" + plan.eventOrderKey().components());
        result.add("exact=" + plan.exactRuntimeState());
        result.add("available=" + plan.availableExactNodeBlueIds());
        result.add("required=" + plan.requiredExactNodeBlueIds());
        for (SubscriptionDelta.Entry interval
                : plan.activeSubscriptionIntervals()) {
            result.add("interval=" + interval.scopePath()
                    + "|" + interval.channelKey()
                    + "|" + interval.effectiveTypeBlueId()
                    + "|" + interval.order()
                    + "|" + interval.sourceContributionNodeBlueIds()
                    + "|" + interval.subscriptionKeys()
                    + "|" + interval.checkpointDomainBlueId()
                    + "|" + interval.activationRootRevision()
                    + "|" + interval.startAfterExternalOrderKey()
                    + "|" + interval.endAtRootRevision()
                    + "|" + interval.dependencies()
                    .deterministicDependencyNodeBlueIds());
        }
        for (ExternalDeliverySnapshot delivery : plan.deliveries()) {
            result.add("delivery=" + delivery.scopePath()
                    + "|" + delivery.channelKey()
                    + "|" + delivery.effectiveTypeBlueId()
                    + "|" + delivery.order()
                    + "|" + delivery.sourceContributionNodeBlueIds()
                    + "|" + delivery.subscriptionKeys()
                    + "|" + delivery.checkpointDomainBlueId()
                    + "|" + delivery.checkpointSubjectBlueId()
                    + "|" + delivery.activationStartExclusive()
                    + "|" + delivery.activationEndInclusive());
        }
        return result;
    }

    private static List<String> diagnosticSignatures(
            List<CoordinationDeliveryDiagnostic> diagnostics) {
        List<String> result = new ArrayList<>();
        for (CoordinationDeliveryDiagnostic diagnostic : diagnostics) {
            result.add(diagnostic.occurrenceKey()
                    + "|" + diagnostic.scopePath()
                    + "|" + diagnostic.sourceChannelKey()
                    + "|" + diagnostic.sourceEffectiveTypeBlueId()
                    + "|" + diagnostic.sourceHeaderBlueId()
                    + "|" + diagnostic.sourceContributionBlueIds()
                    + "|" + diagnostic.checkpointDomainBlueId()
                    + "|" + diagnostic.checkpointSubjectBlueId()
                    + "|" + diagnostic.payloadBlueId()
                    + "|" + diagnostic.targetChannelKey()
                    + "|" + diagnostic.targetEffectiveTypeBlueId()
                    + "|" + diagnostic.targetHeaderBlueId()
                    + "|" + diagnostic.targetContributionBlueIds()
                    + "|" + diagnostic.logicalDeliveryKey()
                    + "|" + diagnostic.dependencyBlueIds());
        }
        return result;
    }

    private static List<Object> boundarySignature(
            CoordinationSemanticDemandBoundary boundary) {
        return Arrays.<Object>asList(
                boundary.rootBlueId(),
                boundary.eventBlueId(),
                boundary.selectedScopePaths(),
                boundary.requiredSeedBlueIds(),
                boundary.sourceHeaderBlueIds(),
                boundary.targetHeaderBlueIds(),
                boundary.targetChannelSelectors(),
                boundary.prefetchBlueIds());
    }

    private static List<Object> evidenceSignature(
            CoordinationPreparedDelivery prepared) {
        return Arrays.<Object>asList(
                prepared.evidence().rootBlueId(),
                prepared.evidence().eventBlueId(),
                prepared.evidence().managedRootRevision(),
                prepared.evidence().indexedRootRevision(),
                prepared.evidence().runtimeRegistryIdentity(),
                prepared.evidence().eventOrderKey(),
                planSignature(prepared.deliveryPlan()));
    }

    @SuppressWarnings("unchecked")
    private static AdmittedPlanningAuthority authority(
            DocumentProcessor processor, BlueContracts contracts)
            throws Exception {
        Constructor<AdmittedPlanningAuthority> constructor =
                AdmittedPlanningAuthority.class.getDeclaredConstructor(
                        DocumentProcessor.class, BlueContracts.class);
        constructor.setAccessible(true);
        return constructor.newInstance(processor, contracts);
    }

    private static ExternalOrderKey eventOrder(Node event) {
        List<Object> components = new ArrayList<>();
        Object timestamp = event.getProperties().get("timestamp").getValue();
        components.add(timestamp instanceof BigInteger
                ? timestamp
                : BigInteger.valueOf(((Number) timestamp).longValue()));
        components.add(blueId(event.getProperties().get("timeline")));
        components.add(blueId(event));
        return ExternalOrderKey.of(components);
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static final class RecordingProvider implements NodeProvider {
        private final Map<String, Node> exact;
        private final List<String> requests = new ArrayList<>();

        private RecordingProvider(Map<String, Node> exact) {
            this.exact = new LinkedHashMap<>(exact);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            requests.add(blueId);
            Node node = exact.get(blueId);
            return node == null
                    ? Collections.<Node>emptyList()
                    : Collections.singletonList(node.clone());
        }

        private List<String> requests() {
            return Collections.unmodifiableList(new ArrayList<>(requests));
        }
    }
}
