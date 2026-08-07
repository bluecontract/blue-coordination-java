package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.BlueContracts;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.IndexedDeliveryPreparation;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public host-commit coverage for stable-key collection subscriptions. */
final class CoordinationPublicCollectionPlatformLifecycleTest {

    private static final Node CHANNEL_TYPE =
            new Node().name("Platform collection lifecycle Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final Node MUTATION_HANDLER_TYPE =
            new Node().name("Platform collection mutation Handler");
    private static final String MUTATION_HANDLER_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    MUTATION_HANDLER_TYPE);

    @Test
    void shouldActivateAddedCollectionMemberOnlyAfterPlatformCommit() {
        // given
        try (Fixture fixture = Fixture.open()) {
            Node initialRoot = fixture.initialRoot;

            // when
            ActivationScenario scenario = fixture.activateMember();

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    scenario.commit.processResult().status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            scenario.commit.processResult()));
            assertTrue(scenario.commit.commitCompanion()
                    .commitsRootAndOutbox());
            assertEquals(2L,
                    scenario.commit.commitCompanion()
                            .resultingRootRevision());
            assertEquals(1,
                    scenario.commit.commitCompanion()
                            .subscriptionDelta().added().size());
            SubscriptionDelta.Entry added = scenario.commit
                    .commitCompanion()
                    .subscriptionDelta().added().get(0);
            assertEquals("/lessons/lesson-b", added.scopePath());
            assertEquals(Long.valueOf(2L),
                    added.activationRootRevision());
            assertEquals(order(20L),
                    added.startAfterExternalOrderKey());
            assertNotNull(scenario.commit.processResult().document()
                    .getAsNode("/lessons/lesson-b"));
            assertNull(nodeAtOrNull(initialRoot, "/lessons/lesson-b"));
        }
    }

    @Test
    void shouldExcludeCreatingEventAndProcessNextPureReferenceEvent() {
        // given
        try (Fixture fixture = Fixture.open()) {
            String addedScope = "/lessons/lesson-b";

            // when
            MemberDeliveryScenario scenario =
                    fixture.processFirstMemberEvent();

            // then
            assertEquals(
                    Collections.singletonList("/@add"),
                    scenario.addDeliverySubscriptionKeys);
            assertFalse(scenario.addDeliveryScopes.contains(addedScope));
            assertNull(nodeAtOrNull(
                    scenario.activationCommit.processResult().document(),
                    addedScope
                            + "/contracts/checkpoint/entries/"
                            + "member-source/subject"));
            assertTrue(scenario.resplitReconstructedExactly);
            assertTrue(scenario.readmittedRootReference);
            assertTrue(scenario.readmittedEventReference);
            assertEquals(
                    Collections.singletonList(addedScope),
                    scenario.memberDeliveryScopes);
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    scenario.commit.processResult().status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            scenario.commit.processResult()));
            assertNotNull(scenario.checkpointSubjectBlueId);
        }
    }

    @Test
    void shouldRetireAndReaddSameKeyAsFreshIntervalAndCheckpointLineage() {
        // given
        try (Fixture historicalFixture = Fixture.open();
                Fixture lifecycleFixture = Fixture.open()) {
            String memberScope = "/lessons/lesson-b";

            // when
            MemberDeliveryScenario historical =
                    historicalFixture.processFirstMemberEvent();
            RetirementScenario scenario =
                    lifecycleFixture.retireAndReaddMember();

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    scenario.removeCommit.processResult().status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            scenario.removeCommit.processResult()));
            assertNull(nodeAtOrNull(
                    scenario.removeCommit.processResult().document(),
                    memberScope));
            assertEquals(1,
                    scenario.removeCommit.commitCompanion()
                            .subscriptionDelta().removed().size());
            SubscriptionDelta.Entry retired = scenario.removeCommit
                    .commitCompanion().subscriptionDelta().removed().get(0);
            assertEquals(memberScope, retired.scopePath());
            assertEquals(Long.valueOf(2L),
                    retired.activationRootRevision());
            assertEquals(order(20L),
                    retired.startAfterExternalOrderKey());
            assertEquals(Long.valueOf(3L),
                    retired.endAtRootRevision());
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    scenario.readdCommit.processResult().status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            scenario.readdCommit.processResult()));
            assertNotNull(nodeAtOrNull(
                    scenario.readdCommit.processResult().document(),
                    memberScope));
            assertEquals(1,
                    scenario.readdCommit.commitCompanion()
                            .subscriptionDelta().added().size());
            SubscriptionDelta.Entry readded = scenario.readdCommit
                    .commitCompanion().subscriptionDelta().added().get(0);
            assertEquals(memberScope, readded.scopePath());
            assertEquals(retired.channelKey(), readded.channelKey());
            assertEquals(retired.checkpointDomainBlueId(),
                    readded.checkpointDomainBlueId());
            assertEquals(Long.valueOf(4L),
                    readded.activationRootRevision());
            assertEquals(order(40L),
                    readded.startAfterExternalOrderKey());
            assertNull(readded.endAtRootRevision());
            assertNull(nodeAtOrNull(
                    scenario.readdCommit.processResult().document(),
                    memberScope
                            + "/contracts/checkpoint/entries/"
                            + "member-source/subject"));
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    scenario.freshMemberCommit.processResult().status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            scenario.freshMemberCommit.processResult()));
            assertNotNull(scenario.freshMemberCheckpoint);
            assertNotEquals(
                    historical.checkpointSubjectBlueId,
                    scenario.freshMemberCheckpoint);
        }
    }

    @Test
    void shouldRetireAndReaddSameKeyAcrossFragmentedAdmissions() {
        // given
        try (Fixture fixture = Fixture.open()) {
            String memberScope = "/lessons/lesson-b";

            // when
            FragmentedRetirementScenario scenario =
                    fixture.retireAndReaddMemberAcrossFragmentedAdmissions();

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    scenario.removeCommit.processResult().status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            scenario.removeCommit.processResult()));
            assertNull(nodeAtOrNull(
                    scenario.removeCommit.processResult().document(),
                    memberScope));
            assertTrue(scenario.removeRootReconstructedExactly);
            assertTrue(scenario.removeRootReferenceReadmitted);
            assertTrue(scenario.readdEventReferenceReadmitted);
            assertEquals(
                    scenario.referenceCanonicalEmbeddedIdentity,
                    scenario.inlineCanonicalEmbeddedIdentity);
            assertEquals(
                    scenario.referenceBeforeEmbeddedIdentity,
                    scenario.inlineBeforeEmbeddedIdentity);
            assertEquals(
                    scenario.inlineBeforeEmbeddedIdentity,
                    scenario.manualAfterEmbeddedIdentity);
            assertNotEquals(
                    scenario.referenceCanonicalEmbeddedIdentity,
                    scenario.referenceBeforeEmbeddedIdentity,
                    "the fixture must retain the authored-versus-resolved "
                            + "representation boundary that triggered the "
                            + "protected-state regression");
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    scenario.readdCommit.processResult().status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            scenario.readdCommit.processResult())
                            + "; reference-before="
                            + scenario.referenceBeforeEmbeddedIdentity
                            + "; reference-canonical="
                            + scenario.referenceCanonicalEmbeddedIdentity
                            + "; inline-before="
                            + scenario.inlineBeforeEmbeddedIdentity
                            + "; inline-canonical="
                            + scenario.inlineCanonicalEmbeddedIdentity
                            + "; manual-after="
                            + scenario.manualAfterEmbeddedIdentity);
            assertNotNull(nodeAtOrNull(
                    scenario.readdCommit.processResult().document(),
                    memberScope));
            assertEquals(1,
                    scenario.readdCommit.commitCompanion()
                            .subscriptionDelta().added().size());
            SubscriptionDelta.Entry readded = scenario.readdCommit
                    .commitCompanion().subscriptionDelta().added().get(0);
            assertEquals(memberScope, readded.scopePath());
            assertEquals(Long.valueOf(4L),
                    readded.activationRootRevision());
            assertEquals(order(40L),
                    readded.startAfterExternalOrderKey());
            assertNull(readded.endAtRootRevision());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final BlueLanguage language;
        private final BlueContracts contracts;
        private final CoordinationContractsHost host;
        private final NodeProvider provider;
        private final Node initialRoot;
        private final Map<String, blue.language.processor.ExternalDeliveryPlan>
                verifiedPlans;

        private Fixture(
                BlueLanguage language,
                BlueContracts contracts,
                NodeProvider provider,
                Node initialRoot,
                Map<String, blue.language.processor.ExternalDeliveryPlan>
                        verifiedPlans) {
            this.language = language;
            this.contracts = contracts;
            this.host = new CoordinationContractsHost(contracts);
            this.provider = provider;
            this.initialRoot = initialRoot;
            this.verifiedPlans = verifiedPlans;
        }

        private static Fixture open(NodeProvider... additionalProviders) {
            ContractProcessorRegistry registry =
                    ContractProcessorRegistryBuilder.create()
                            .registerDefaults()
                            .register(
                                    CHANNEL_TYPE_BLUE_ID,
                                    CHANNEL_TYPE.clone(),
                                    new LifecycleChannelProcessor())
                            .register(
                                    MUTATION_HANDLER_TYPE_BLUE_ID,
                                    MUTATION_HANDLER_TYPE.clone(),
                                    new MutationHandlerProcessor())
                            .build();
            List<NodeProvider> providers = new ArrayList<>();
            providers.addAll(Arrays.asList(additionalProviders));
            providers.add(BlueRuntimeTypeRegistry.getDefault()
                    .asProcessorSnapshotProvider());
            providers.add(registry.exactTypeProvider());
            NodeProvider provider = new SequentialNodeProvider(
                    providers.toArray(new NodeProvider[providers.size()]));
            BlueLanguage language = BlueLanguage.builder()
                    .nodeProvider(provider)
                    .build();
            Map<String, blue.language.processor.ExternalDeliveryPlan>
                    verifiedPlans = new LinkedHashMap<>();
            BlueContracts contracts = BlueContracts.builder(
                            language.processing())
                    .runtimeRegistry(registry)
                    .deliveryPlanDeriver((root, event) -> {
                        blue.language.processor.ExternalDeliveryPlan plan =
                                verifiedPlans.get(
                                        DirectBlueIdCalculator
                                                .calculateBlueId(event));
                        if (plan == null) {
                            throw new IllegalStateException(
                                    "No verified public indexed plan for "
                                            + DirectBlueIdCalculator
                                                    .calculateBlueId(event));
                        }
                        return plan;
                    })
                    .build();
            return new Fixture(
                    language,
                    contracts,
                    provider,
                    root(),
                    verifiedPlans);
        }

        private ActivationScenario activateMember() {
            SubscriptionDelta initial = host.projectInitialSubscriptions(
                    initialRoot,
                    1L,
                    order(10L));
            String addSubscriptionKey = subscriptionKey(
                    initial.added(), "/", "add-source");
            Node addEvent = event(
                    "add", 20L, addSubscriptionKey);
            IndexedDeliveryPreparation addIndexed = indexed(
                    initialRoot,
                    addEvent,
                    1L,
                    order(20L),
                    initial.added(),
                    addSubscriptionKey);
            PlatformProcessingResult addCommit = commit(
                    initialRoot,
                    addEvent,
                    addIndexed);
            List<SubscriptionDelta.Entry> afterAdd = applyDelta(
                    initial.added(),
                    addCommit.commitCompanion().subscriptionDelta());

            return new ActivationScenario(
                    addCommit,
                    afterAdd,
                    deliveryScopes(addIndexed),
                    deliverySubscriptionKeys(addIndexed));
        }

        private MemberDeliveryScenario processFirstMemberEvent() {
            ActivationScenario activation = activateMember();

            CoordinationDocumentSplitter.SplitGraph rootGraph =
                    new CoordinationDocumentSplitter(contracts)
                            .splitDocument(
                                    activation.commit.processResult()
                                            .document());
            String memberSubscriptionKey = subscriptionKey(
                    activation.activeIntervals,
                    "/lessons/lesson-b",
                    "member-source");
            Node memberEvent = event(
                    "lesson-b", 21L, memberSubscriptionKey);
            CoordinationDocumentSplitter.SplitGraph eventGraph =
                    CoordinationDocumentSplitter.forEventSplitting()
                            .splitEvent(memberEvent);
            boolean reconstructedExactly = rootGraph.rootBlueId().equals(
                    DirectBlueIdCalculator.calculateBlueId(
                            rootGraph.reconstruct()));

            try (Fixture admitted = Fixture.open(
                    rootGraph.provider(),
                    eventGraph.provider())) {
                Node rootReference = rootGraph.pureReference();
                Node eventReference = eventGraph.pureReference();
                boolean rootReferenceAvailable = admitted.host
                        .materializeVerifiedExactReference(rootReference)
                        .isEstablished();
                boolean eventReferenceAvailable = admitted.host
                        .materializeVerifiedExactReference(eventReference)
                        .isEstablished();
                IndexedDeliveryPreparation memberIndexed = admitted.indexed(
                        rootReference,
                        eventReference,
                        2L,
                        order(21L),
                        activation.activeIntervals,
                        memberSubscriptionKey);
                PlatformProcessingResult memberCommit = admitted.commit(
                        rootReference,
                        eventReference,
                        memberIndexed);
                String checkpointSubjectBlueId = memberIndexed.deliveryPlan()
                        .deliveries().get(0).checkpointSubjectBlueId();
                return new MemberDeliveryScenario(
                        activation.commit,
                        memberCommit,
                        activation.deliveryScopes,
                        activation.deliverySubscriptionKeys,
                        deliveryScopes(memberIndexed),
                        reconstructedExactly,
                        rootReferenceAvailable,
                        eventReferenceAvailable,
                        checkpointSubjectBlueId);
            }
        }

        private RetirementScenario retireAndReaddMember() {
            ActivationScenario activation = activateMember();
            // Each transition consumes the authoritative exact Root committed
            // by the prior transition. Fragmented pure-reference admission is
            // exercised independently by processFirstMemberEvent().
            Node rootAfterActivation =
                    activation.commit.processResult().document();
            String removeSubscriptionKey = subscriptionKey(
                    activation.activeIntervals,
                    "/",
                    "remove-source");
            Node removeEvent = event(
                    "remove", 30L, removeSubscriptionKey);
            IndexedDeliveryPreparation removeIndexed = indexed(
                    rootAfterActivation,
                    removeEvent,
                    2L,
                    order(30L),
                    activation.activeIntervals,
                    removeSubscriptionKey);
            PlatformProcessingResult removeCommit = commit(
                    rootAfterActivation,
                    removeEvent,
                    removeIndexed);
            requireSuccessfulCommit("remove", removeCommit);
            List<SubscriptionDelta.Entry> intervalsAfterRemoval =
                    applyDelta(
                            activation.activeIntervals,
                            removeCommit.commitCompanion()
                                    .subscriptionDelta());

            Node rootAfterRemoval =
                    removeCommit.processResult().document();
            String readdSubscriptionKey = subscriptionKey(
                    intervalsAfterRemoval,
                    "/",
                    "readd-source");
            Node readdEvent = event(
                    "readd", 40L, readdSubscriptionKey);
            IndexedDeliveryPreparation readdIndexed = indexed(
                    rootAfterRemoval,
                    readdEvent,
                    3L,
                    order(40L),
                    intervalsAfterRemoval,
                    readdSubscriptionKey);
            PlatformProcessingResult readdCommit = commit(
                    rootAfterRemoval,
                    readdEvent,
                    readdIndexed);
            requireSuccessfulCommit("readd", readdCommit);
            requireMemberReactivated(readdCommit);
            List<SubscriptionDelta.Entry> intervalsAfterReadd =
                    applyDelta(
                            intervalsAfterRemoval,
                            readdCommit.commitCompanion()
                                    .subscriptionDelta());

            Node rootAfterReadd =
                    readdCommit.processResult().document();
            String freshMemberSubscriptionKey = subscriptionKey(
                    intervalsAfterReadd,
                    "/lessons/lesson-b",
                    "member-source");
            Node freshMemberEvent = event(
                    "lesson-b",
                    41L,
                    freshMemberSubscriptionKey);
            IndexedDeliveryPreparation freshMemberIndexed = indexed(
                    rootAfterReadd,
                    freshMemberEvent,
                    4L,
                    order(41L),
                    intervalsAfterReadd,
                    freshMemberSubscriptionKey);
            PlatformProcessingResult freshMemberCommit = commit(
                    rootAfterReadd,
                    freshMemberEvent,
                    freshMemberIndexed);
            requireSuccessfulCommit(
                    "fresh member",
                    freshMemberCommit);
            String freshMemberCheckpoint = freshMemberIndexed
                    .deliveryPlan().deliveries().get(0)
                    .checkpointSubjectBlueId();

            return new RetirementScenario(
                    removeCommit,
                    readdCommit,
                    freshMemberCommit,
                    freshMemberCheckpoint);
        }

        private FragmentedRetirementScenario
        retireAndReaddMemberAcrossFragmentedAdmissions() {
            ActivationScenario activation = activateMember();
            Node rootAfterActivation =
                    activation.commit.processResult().document();
            String removeSubscriptionKey = subscriptionKey(
                    activation.activeIntervals,
                    "/",
                    "remove-source");
            Node removeEvent = event(
                    "remove", 30L, removeSubscriptionKey);
            CoordinationDocumentSplitter.SplitGraph activationGraph =
                    new CoordinationDocumentSplitter(contracts)
                            .splitDocument(rootAfterActivation);
            CoordinationDocumentSplitter.SplitGraph removeEventGraph =
                    CoordinationDocumentSplitter.forEventSplitting()
                            .splitEvent(removeEvent);

            try (Fixture removeAdmission = Fixture.open(
                    activationGraph.provider(),
                    removeEventGraph.provider())) {
                Node activationReference = activationGraph.pureReference();
                Node removeEventReference =
                        removeEventGraph.pureReference();
                IndexedDeliveryPreparation removeIndexed =
                        removeAdmission.indexed(
                                activationReference,
                                removeEventReference,
                                2L,
                                order(30L),
                                activation.activeIntervals,
                                removeSubscriptionKey);
                PlatformProcessingResult removeCommit =
                        removeAdmission.commit(
                                activationReference,
                                removeEventReference,
                                removeIndexed);
                requireSuccessfulCommit("fragmented remove", removeCommit);
                List<SubscriptionDelta.Entry> intervalsAfterRemoval =
                        applyDelta(
                                activation.activeIntervals,
                                removeCommit.commitCompanion()
                                        .subscriptionDelta());

                CoordinationDocumentSplitter.SplitGraph removalGraph =
                        new CoordinationDocumentSplitter(
                                removeAdmission.contracts)
                                .splitDocument(
                                        removeCommit.processResult()
                                                .document());
                String readdSubscriptionKey = subscriptionKey(
                        intervalsAfterRemoval,
                        "/",
                        "readd-source");
                Node readdEvent = event(
                        "readd", 40L, readdSubscriptionKey);
                CoordinationDocumentSplitter.SplitGraph readdEventGraph =
                        CoordinationDocumentSplitter.forEventSplitting()
                                .splitEvent(readdEvent);

                try (Fixture readdAdmission = Fixture.open(
                        removalGraph.provider(),
                        activationGraph.provider(),
                        removeEventGraph.provider(),
                        readdEventGraph.provider())) {
                    Node removalReference =
                            removalGraph.pureReference();
                    Node readdEventReference =
                            readdEventGraph.pureReference();
                    Node materializedRemoval = readdAdmission.host
                            .materializeVerifiedExactReference(
                                    removalReference)
                            .requireEstablished()
                            .toNode();
                    boolean rootReferenceAvailable = true;
                    boolean eventReferenceAvailable = readdAdmission.host
                            .materializeVerifiedExactReference(
                                    readdEventReference)
                            .isEstablished();
                    String referenceBeforeEmbeddedIdentity =
                            effectiveEmbeddedIdentity(
                                    readdAdmission.host.runtimeAccess()
                                            .resolveTransient(
                                                    materializedRemoval));
                    String referenceCanonicalEmbeddedIdentity =
                            embeddedIdentity(materializedRemoval);
                    Node reconstructedRemoval =
                            removalGraph.reconstruct();
                    String inlineBeforeEmbeddedIdentity =
                            effectiveEmbeddedIdentity(
                                    readdAdmission.host.runtimeAccess()
                                            .resolveTransient(
                                                    reconstructedRemoval));
                    String inlineCanonicalEmbeddedIdentity =
                            embeddedIdentity(reconstructedRemoval);
                    Node manualReaddition =
                            reconstructedRemoval.clone();
                    NodePathEditor.put(
                            manualReaddition,
                            "/lessons/lesson-b",
                            lesson("lesson-b"));
                    String manualAfterEmbeddedIdentity =
                            effectiveEmbeddedIdentity(
                                    readdAdmission.host.runtimeAccess()
                                            .resolveTransient(
                                                    manualReaddition));
                    IndexedDeliveryPreparation readdIndexed =
                            readdAdmission.indexed(
                                    removalReference,
                                    readdEventReference,
                                    3L,
                                    order(40L),
                                    intervalsAfterRemoval,
                                    readdSubscriptionKey);
                    PlatformProcessingResult readdCommit =
                            readdAdmission.commit(
                                    removalReference,
                                    readdEventReference,
                                    readdIndexed);
                    return new FragmentedRetirementScenario(
                            removeCommit,
                            readdCommit,
                            removalGraph.rootBlueId().equals(
                                    DirectBlueIdCalculator.calculateBlueId(
                                            removalGraph.reconstruct())),
                            rootReferenceAvailable,
                            eventReferenceAvailable,
                            referenceBeforeEmbeddedIdentity,
                            referenceCanonicalEmbeddedIdentity,
                            inlineBeforeEmbeddedIdentity,
                            inlineCanonicalEmbeddedIdentity,
                            manualAfterEmbeddedIdentity);
                }
            }
        }

        private PlatformProcessingResult commit(
                Node root,
                Node exactEvent,
                IndexedDeliveryPreparation indexed) {
            PlatformProcessInvocation invocation =
                    host.preparePlatformCommitInvocation(
                            indexed,
                            provider);
            return host.processForPlatformCommit(
                    root,
                    exactEvent,
                    invocation);
        }

        private static void requireSuccessfulCommit(
                String stage,
                PlatformProcessingResult result) {
            if (result.processResult().status()
                    != ProcessorStatus.SUCCESS) {
                throw new IllegalStateException(
                        stage + " commit failed: "
                                + result.processResult().status()
                                + " / "
                                + ProcessingResultTestSupport
                                        .diagnosticCategory(
                                                result.processResult())
                                + " / "
                                + ProcessingResultTestSupport
                                        .diagnosticMessage(
                                                result.processResult()));
            }
        }

        private static void requireMemberReactivated(
                PlatformProcessingResult readdCommit) {
            if (nodeAtOrNull(
                    readdCommit.processResult().document(),
                    "/lessons/lesson-b") == null) {
                throw new IllegalStateException(
                        "readd commit did not restore lesson-b");
            }
            int added = readdCommit.commitCompanion()
                    .subscriptionDelta().added().size();
            if (added != 1) {
                throw new IllegalStateException(
                        "readd commit emitted " + added
                                + " added intervals instead of one");
            }
        }

        private IndexedDeliveryPreparation indexed(
                Node root,
                Node exactEvent,
                long revision,
                ExternalOrderKey eventOrder,
                List<SubscriptionDelta.Entry> active,
                String subscriptionKey) {
            List<ExternalSubscriptionOccurrenceKey> candidates =
                    new ArrayList<>();
            for (SubscriptionDelta.Entry interval : active) {
                if (interval.subscriptionKeys().contains(subscriptionKey)) {
                    candidates.add(ExternalSubscriptionOccurrenceKey.of(
                            interval.scopePath(),
                            interval.channelKey()));
                }
            }
            IndexedDeliveryPreparation prepared =
                    host.prepareIndexedDelivery(
                    root,
                    exactEvent,
                    revision,
                    eventOrder,
                    active,
                    candidates);
            verifiedPlans.put(
                    DirectBlueIdCalculator.calculateBlueId(exactEvent),
                    prepared.deliveryPlan());
            return prepared;
        }

        @Override
        public void close() {
            contracts.close();
            language.close();
        }
    }

    private static final class ActivationScenario {
        private final PlatformProcessingResult commit;
        private final List<SubscriptionDelta.Entry> activeIntervals;
        private final List<String> deliveryScopes;
        private final List<String> deliverySubscriptionKeys;

        private ActivationScenario(
                PlatformProcessingResult commit,
                List<SubscriptionDelta.Entry> activeIntervals,
                List<String> deliveryScopes,
                List<String> deliverySubscriptionKeys) {
            this.commit = commit;
            this.activeIntervals = activeIntervals;
            this.deliveryScopes = deliveryScopes;
            this.deliverySubscriptionKeys = deliverySubscriptionKeys;
        }
    }

    private static final class MemberDeliveryScenario {
        private final PlatformProcessingResult activationCommit;
        private final PlatformProcessingResult commit;
        private final List<String> addDeliveryScopes;
        private final List<String> addDeliverySubscriptionKeys;
        private final List<String> memberDeliveryScopes;
        private final boolean resplitReconstructedExactly;
        private final boolean readmittedRootReference;
        private final boolean readmittedEventReference;
        private final String checkpointSubjectBlueId;

        private MemberDeliveryScenario(
                PlatformProcessingResult activationCommit,
                PlatformProcessingResult commit,
                List<String> addDeliveryScopes,
                List<String> addDeliverySubscriptionKeys,
                List<String> memberDeliveryScopes,
                boolean resplitReconstructedExactly,
                boolean readmittedRootReference,
                boolean readmittedEventReference,
                String checkpointSubjectBlueId) {
            this.activationCommit = activationCommit;
            this.commit = commit;
            this.addDeliveryScopes = addDeliveryScopes;
            this.addDeliverySubscriptionKeys =
                    addDeliverySubscriptionKeys;
            this.memberDeliveryScopes = memberDeliveryScopes;
            this.resplitReconstructedExactly =
                    resplitReconstructedExactly;
            this.readmittedRootReference = readmittedRootReference;
            this.readmittedEventReference = readmittedEventReference;
            this.checkpointSubjectBlueId = checkpointSubjectBlueId;
        }
    }

    private static final class RetirementScenario {
        private final PlatformProcessingResult removeCommit;
        private final PlatformProcessingResult readdCommit;
        private final PlatformProcessingResult freshMemberCommit;
        private final String freshMemberCheckpoint;

        private RetirementScenario(
                PlatformProcessingResult removeCommit,
                PlatformProcessingResult readdCommit,
                PlatformProcessingResult freshMemberCommit,
                String freshMemberCheckpoint) {
            this.removeCommit = removeCommit;
            this.readdCommit = readdCommit;
            this.freshMemberCommit = freshMemberCommit;
            this.freshMemberCheckpoint = freshMemberCheckpoint;
        }
    }

    private static final class FragmentedRetirementScenario {
        private final PlatformProcessingResult removeCommit;
        private final PlatformProcessingResult readdCommit;
        private final boolean removeRootReconstructedExactly;
        private final boolean removeRootReferenceReadmitted;
        private final boolean readdEventReferenceReadmitted;
        private final String referenceBeforeEmbeddedIdentity;
        private final String referenceCanonicalEmbeddedIdentity;
        private final String inlineBeforeEmbeddedIdentity;
        private final String inlineCanonicalEmbeddedIdentity;
        private final String manualAfterEmbeddedIdentity;

        private FragmentedRetirementScenario(
                PlatformProcessingResult removeCommit,
                PlatformProcessingResult readdCommit,
                boolean removeRootReconstructedExactly,
                boolean removeRootReferenceReadmitted,
                boolean readdEventReferenceReadmitted,
                String referenceBeforeEmbeddedIdentity,
                String referenceCanonicalEmbeddedIdentity,
                String inlineBeforeEmbeddedIdentity,
                String inlineCanonicalEmbeddedIdentity,
                String manualAfterEmbeddedIdentity) {
            this.removeCommit = removeCommit;
            this.readdCommit = readdCommit;
            this.removeRootReconstructedExactly =
                    removeRootReconstructedExactly;
            this.removeRootReferenceReadmitted =
                    removeRootReferenceReadmitted;
            this.readdEventReferenceReadmitted =
                    readdEventReferenceReadmitted;
            this.referenceBeforeEmbeddedIdentity =
                    referenceBeforeEmbeddedIdentity;
            this.referenceCanonicalEmbeddedIdentity =
                    referenceCanonicalEmbeddedIdentity;
            this.inlineBeforeEmbeddedIdentity =
                    inlineBeforeEmbeddedIdentity;
            this.inlineCanonicalEmbeddedIdentity =
                    inlineCanonicalEmbeddedIdentity;
            this.manualAfterEmbeddedIdentity =
                    manualAfterEmbeddedIdentity;
        }
    }

    public static final class MutationHandler extends HandlerContract {
        private String operation;
        private String path;
        private Node value;

        public MutationHandler() {
        }

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Node getValue() {
            return value;
        }

        public void setValue(Node value) {
            this.value = value;
        }
    }

    private static final class MutationHandlerProcessor
            implements HandlerProcessor<MutationHandler> {
        @Override
        public Class<MutationHandler> contractType() {
            return MutationHandler.class;
        }

        @Override
        public String deriveChannel(
                MutationHandler contract,
                HandlerRegistrationContext context) {
            if ("add-handler".equals(context.handlerKey())) {
                return "add-source";
            }
            if ("remove-handler".equals(context.handlerKey())) {
                return "remove-source";
            }
            if ("readd-handler".equals(context.handlerKey())) {
                return "readd-source";
            }
            if ("member-handler".equals(context.handlerKey())) {
                return "member-source";
            }
            return null;
        }

        @Override
        public void execute(
                MutationHandler contract,
                ProcessorExecutionContext context) {
            Node exactHandler = context.contractNode();
            String operation = String.valueOf(
                    exactHandler.get("/operation"));
            String path = String.valueOf(
                    exactHandler.get("/path"));
            String scopedPath = context.resolvePointer(path);
            if ("add".equals(operation)) {
                context.applyPatch(JsonPatch.add(
                        scopedPath,
                        lesson("lesson-b")));
                return;
            }
            if ("remove".equals(operation)) {
                context.applyPatch(JsonPatch.remove(
                        scopedPath));
                return;
            }
            if ("touch".equals(operation)) {
                context.applyPatch(JsonPatch.replace(
                        scopedPath,
                        new Node().value(1)));
                return;
            }
            throw new IllegalArgumentException(
                    "Unsupported collection mutation: "
                            + operation);
        }
    }

    public static final class LifecycleChannel extends ChannelContract {
        private String binding;

        public LifecycleChannel() {
        }

        public String getBinding() {
            return binding;
        }

        public void setBinding(String binding) {
            this.binding = binding;
        }
    }

    private static final class LifecycleChannelProcessor
            implements ChannelProcessor<LifecycleChannel> {
        private static final ExternalChannelSubscriptionFunctions<
                LifecycleChannel> FUNCTIONS =
                new ExternalChannelSubscriptionFunctions<LifecycleChannel>() {
                    @Override
                    public List<String> channelKeys(
                            LifecycleChannel contract) {
                        return Collections.singletonList(
                                contract.getBinding());
                    }

                    @Override
                    public List<String> channelKeys(
                            LifecycleChannel contract,
                            ExternalChannelFunctionContext context) {
                        return Collections.singletonList(
                                context.scopePath() + "@"
                                        + contract.getBinding());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            LifecycleChannel contract) {
                        return contract.getBinding();
                    }
                };

        @Override
        public Class<LifecycleChannel> contractType() {
            return LifecycleChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<LifecycleChannel>
        externalSubscriptionFunctions() {
            return FUNCTIONS;
        }

        @Override
        public boolean matches(
                LifecycleChannel contract,
                ChannelEvaluationContext context) {
            return true;
        }
    }

    private static Node root() {
        Map<String, Node> lessons = new LinkedHashMap<>();
        lessons.put("lesson-a", lesson("lesson-a"));
        Map<String, Node> contracts = new LinkedHashMap<>();
        contracts.put("add-source", channel("add"));
        contracts.put("remove-source", channel("remove"));
        contracts.put("readd-source", channel("readd"));
        contracts.put(
                "add-handler",
                mutationHandler(
                        "add-source",
                        "add",
                        "/lessons/lesson-b",
                        lesson("lesson-b")));
        contracts.put(
                "remove-handler",
                mutationHandler(
                        "remove-source",
                        "remove",
                        "/lessons/lesson-b",
                        null));
        contracts.put(
                "readd-handler",
                mutationHandler(
                        "readd-source",
                        "add",
                        "/lessons/lesson-b",
                        lesson("lesson-b")));
        contracts.put("embedded", processEmbeddedCollections("/lessons"));
        return new Node()
                .name("Public collection platform lifecycle")
                .properties("lessons", new Node().properties(lessons))
                .properties("contracts", new Node().properties(contracts));
    }

    private static Node lesson(String binding) {
        return new Node()
                .properties("observed", new Node().value(0))
                .contracts(new Node().properties(
                        "member-source", channel(binding),
                        "member-handler", mutationHandler(
                                "member-source",
                                "touch",
                                "/observed",
                                null)));
    }

    private static Node channel(String binding) {
        return new Node()
                .type(new Node().blueId(CHANNEL_TYPE_BLUE_ID))
                .properties("binding", new Node().value(binding));
    }

    private static Node mutationHandler(
            String channel,
            String operation,
            String path,
            Node value) {
        return new Node()
                .type(new Node().blueId(
                        MUTATION_HANDLER_TYPE_BLUE_ID))
                .properties(
                        "channel", new Node().value(channel),
                        "operation", new Node().value(operation),
                        "path", new Node().value(path));
    }

    private static Node processEmbeddedCollections(String... paths) {
        List<Node> collectionPaths = new ArrayList<>();
        for (String path : paths) {
            collectionPaths.add(new Node().value(path));
        }
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "collectionPaths",
                        new Node().items(collectionPaths));
    }

    private static Node event(
            String binding,
            long sequence,
            String subscriptionKey) {
        return new Node()
                .properties(
                        "id", new Node().value(binding + "-" + sequence),
                        "subscriptionKey",
                        new Node().value(subscriptionKey),
                        "sequence", new Node().value(sequence));
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(Collections.singletonList(
                BigInteger.valueOf(value)));
    }

    private static String subscriptionKey(
            List<SubscriptionDelta.Entry> active,
            String scopePath,
            String channelKey) {
        for (SubscriptionDelta.Entry interval : active) {
            if (scopePath.equals(interval.scopePath())
                    && channelKey.equals(interval.channelKey())) {
                if (interval.subscriptionKeys().size() != 1) {
                    throw new IllegalStateException(
                            "Lifecycle occurrence must expose one key: "
                                    + scopePath + "/" + channelKey);
                }
                return interval.subscriptionKeys().get(0);
            }
        }
        throw new IllegalStateException(
                "Lifecycle occurrence is absent: "
                        + scopePath + "/" + channelKey);
    }

    private static List<SubscriptionDelta.Entry> applyDelta(
            List<SubscriptionDelta.Entry> prior,
            SubscriptionDelta delta) {
        Map<String, SubscriptionDelta.Entry> active =
                new LinkedHashMap<>();
        for (SubscriptionDelta.Entry entry : prior) {
            active.put(occurrenceKey(entry), entry);
        }
        for (SubscriptionDelta.Entry entry : delta.removed()) {
            active.remove(occurrenceKey(entry));
        }
        for (SubscriptionDelta.Entry entry : delta.added()) {
            active.put(occurrenceKey(entry), entry);
        }
        return new SubscriptionDelta(
                new ArrayList<>(active.values()),
                Collections.<SubscriptionDelta.Entry>emptyList())
                .added();
    }

    private static String occurrenceKey(SubscriptionDelta.Entry entry) {
        return entry.scopePath() + "\u0000" + entry.channelKey();
    }

    private static List<String> deliveryScopes(
            IndexedDeliveryPreparation indexed) {
        List<String> scopes = new ArrayList<>();
        for (ExternalDeliverySnapshot delivery
                : indexed.deliveryPlan().deliveries()) {
            scopes.add(delivery.scopePath());
        }
        return scopes;
    }

    private static List<String> deliverySubscriptionKeys(
            IndexedDeliveryPreparation indexed) {
        List<String> keys = new ArrayList<>();
        for (ExternalDeliverySnapshot delivery
                : indexed.deliveryPlan().deliveries()) {
            keys.addAll(delivery.subscriptionKeys());
        }
        return keys;
    }

    private static Node nodeAtOrNull(Node root, String pointer) {
        return NodePathEditor.getOrNull(root, pointer);
    }

    private static String effectiveEmbeddedIdentity(
            ResolvedSnapshot snapshot) {
        return embeddedIdentity(snapshot.resolvedRoot());
    }

    private static String embeddedIdentity(Node root) {
        Node embedded = root.getAsNode("/contracts/embedded").clone();
        if (embedded.getProperties() != null) {
            embedded.getProperties().remove("paths");
            embedded.getProperties().remove("collectionPaths");
        }
        NodeToBlueIdInput.stripResolvedBlueIdMetadata(embedded);
        return DirectBlueIdCalculator.calculateBlueId(embedded);
    }
}
