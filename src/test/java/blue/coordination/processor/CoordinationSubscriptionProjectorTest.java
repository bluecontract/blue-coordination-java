package blue.coordination.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.model.ProcessingTerminatedMarker;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.SequentialNodeProvider;
import blue.repo.BlueRepository;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.myos.MyOSTimelineChannel;
import blue.repo.myos.MyOSTimeline;
import blue.repo.myos.PrincipalActor;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationSubscriptionProjectorTest {

    @Test
    void shouldProjectNestedTimelineChannelAtItsSelectedScope() {
        // Given
        Fixture fixture = fixture();
        Node root = initialized(
                fixture,
                nestedDocument(
                        fixture.repository,
                        3,
                        TestTimelineProvider.channel(
                                "nested")));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());
        ExternalOrderKey frontier = order(100);
        CoordinationHostQuotaSession hostQuotas =
                CoordinationHostQuotaSession.observing();

        // When
        CoordinationSubscriptionSnapshot snapshot =
                projector.projectCurrent(
                        root.clone(),
                        7L,
                        frontier,
                        hostQuotas);

        // Then
        assertEquals(1, snapshot.occurrences().size());
        assertEquals(
                "/emb1/emb2/emb3",
                snapshot.occurrences().get(0).scopePath());
        assertEquals(
                "channel",
                snapshot.occurrences().get(0).channelKey());
        assertFalse(
                snapshot.occurrences().get(0)
                        .headerFieldBlueIds()
                        .isEmpty());
        assertFalse(
                snapshot.occurrences().get(0)
                        .dependencyNodeBlueIds()
                        .isEmpty());
        assertEquals(
                1L,
                hostQuotas.quantity(
                        CoordinationHostQuotaSchedule
                                .SUBSCRIPTION_OCCURRENCE_PROJECTED));
    }

    @Test
    void shouldProduceDeterministicSubscriptionSnapshotForRepeatedProjection() {
        // Given
        Fixture fixture = fixture();
        Node root = initialized(
                fixture,
                nestedDocument(
                        fixture.repository,
                        3,
                        TestTimelineProvider.channel(
                                "nested")));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());
        ExternalOrderKey frontier = order(100);

        // When
        CoordinationSubscriptionSnapshot first =
                projector.projectCurrent(
                        root.clone(),
                        7L,
                        frontier);
        CoordinationSubscriptionSnapshot second =
                projector.projectCurrent(
                        root.clone(),
                        7L,
                        frontier);

        // Then
        assertEquals(first.digest(), second.digest());
        assertEquals(first.toMap(), second.toMap());
        assertEquals(
                CoordinationSubscriptionSnapshot
                        .ALGORITHM_IDENTITY,
                first.algorithmIdentity());
        assertFalse(
                first.coordinationRuntimeRegistryIdentity()
                        .isEmpty());
    }

    @Test
    void shouldRehydratePersistedSubscriptionSnapshotWithoutIdentityDrift() {
        // Given
        Fixture fixture = fixture();
        Node root = initialized(
                fixture,
                nestedDocument(
                        fixture.repository,
                        3,
                        TestTimelineProvider.channel(
                                "nested")));
        CoordinationSubscriptionSnapshot projected =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor())
                        .projectCurrent(
                                root,
                                7L,
                                order(100));

        // When
        CoordinationSubscriptionSnapshot rehydrated =
                CoordinationSubscriptionSnapshot
                        .rehydrate(
                                projected.toMap());

        // Then
        assertEquals(
                projected.toMap(),
                rehydrated.toMap());
        assertEquals(
                projected.digest(),
                rehydrated.digest());
    }

    @Test
    void shouldProjectRootOnlyTimelineChannel() {
        // Given
        Fixture fixture = fixture();
        Node root = initialized(
                fixture,
                rootChannelDocument(
                        fixture.repository,
                        TestTimelineProvider.channel(
                                "root")));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());

        // When
        CoordinationSubscriptionSnapshot snapshot =
                projector.projectCurrent(
                        root,
                        1L,
                        order(1));

        // Then
        assertEquals(
                1,
                snapshot.occurrences().size());
        assertEquals(
                Collections.singletonList("/"),
                scopePaths(snapshot));
        assertEquals(
                "channel",
                snapshot.occurrences().get(0)
                        .channelKey());
        assertEquals(
                TimelineChannel.blueId(),
                snapshot.occurrences().get(0)
                        .effectiveTypeBlueId());
    }

    @Test
    void shouldProjectTimelineChannelFromOneEmbeddedScope() {
        // Given
        Fixture fixture = fixture();
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                "embedded",
                processEmbedded("/child"));
        Map<String, Node> properties =
                new LinkedHashMap<String, Node>();
        properties.put(
                "child",
                scopeWithChannel(
                        "childChannel",
                        TestTimelineProvider.channel(
                                "child")));
        Node root = initialized(
                fixture,
                document(
                        fixture.repository,
                        contracts,
                        properties));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());

        // When
        CoordinationSubscriptionSnapshot snapshot =
                projector.projectCurrent(
                        root,
                        1L,
                        order(1));

        // Then
        assertEquals(
                1,
                snapshot.occurrences().size());
        assertEquals(
                Collections.singletonList(
                        "/child"),
                scopePaths(snapshot));
        assertEquals(
                "childChannel",
                snapshot.occurrences().get(0)
                        .channelKey());
    }

    @Test
    void shouldProjectInheritedTimelineChannel() {
        // Given
        Fixture fixture = fixture();
        Node inheritedChannel =
                exactTimelineChannel(
                        "inherited");
        Node rootType = new Node()
                .name("Inherited subscription Root")
                .contracts(
                        new Node().properties(
                                "inheritedChannel",
                                inheritedChannel));
        String rootTypeBlueId =
                fixture.blue.calculateBlueId(
                        rootType);
        installProvider(
                fixture,
                exactProvider(
                        rootTypeBlueId,
                        rootType));
        Node root = initialized(
                fixture,
                document(
                        fixture.repository,
                        Collections
                                .<String, Node>emptyMap(),
                        Collections
                                .<String, Node>emptyMap())
                        .type(reference(
                                rootTypeBlueId)));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());

        // When
        CoordinationSubscriptionSnapshot snapshot =
                projector.projectCurrent(
                        root,
                        1L,
                        order(1));

        // Then
        assertEquals(
                1,
                snapshot.occurrences().size());
        assertEquals(
                "/",
                snapshot.occurrences().get(0)
                        .scopePath());
        assertEquals(
                "inheritedChannel",
                snapshot.occurrences().get(0)
                        .channelKey());
        assertEquals(
                Collections.singletonList(
                        fixture.blue.calculateBlueId(
                                inheritedChannel)),
                snapshot.occurrences().get(0)
                        .sourceContributionNodeBlueIds());
    }

    @Test
    void shouldFollowInheritedProcessEmbeddedPath() {
        // Given
        Fixture fixture = fixture();
        Node inheritedEmbedded =
                exactProcessEmbedded(
                        "/child");
        Node rootType = new Node()
                .name("Inherited embedded subscription Root")
                .contracts(
                        new Node().properties(
                                "embedded",
                                inheritedEmbedded));
        String rootTypeBlueId =
                fixture.blue.calculateBlueId(
                        rootType);
        installProvider(
                fixture,
                exactProvider(
                        rootTypeBlueId,
                        rootType));
        Map<String, Node> properties =
                new LinkedHashMap<String, Node>();
        properties.put(
                "child",
                scopeWithChannel(
                        "childChannel",
                        TestTimelineProvider.channel(
                                "child")));
        Node root = initialized(
                fixture,
                document(
                        fixture.repository,
                        Collections
                                .<String, Node>emptyMap(),
                        properties)
                        .type(reference(
                                rootTypeBlueId)));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());

        // When
        CoordinationSubscriptionSnapshot snapshot =
                projector.projectCurrent(
                        root,
                        1L,
                        order(1));

        // Then
        assertEquals(
                1,
                snapshot.occurrences().size());
        assertEquals(
                "/child",
                snapshot.occurrences().get(0)
                        .scopePath());
        assertEquals(
                "childChannel",
                snapshot.occurrences().get(0)
                        .channelKey());
        assertEquals(
                Collections.singletonList(
                        "/child"),
                processEmbeddedPaths(
                        snapshot,
                        "/contracts/embedded"));
    }

    @Test
    void shouldProduceEquivalentSnapshotsForInlineColdAndWarmProviderRepresentations() {
        // Given
        Fixture fixture = fixture();
        Node inlineRoot = initialized(
                fixture,
                rootChannelDocument(
                        fixture.repository,
                        TestTimelineProvider.channel(
                                "provider")));
        String rootBlueId =
                fixture.blue.calculateBlueId(
                        inlineRoot);
        List<String> providerRequests =
                new ArrayList<String>();
        installProvider(
                fixture,
                requestedBlueId -> {
                    providerRequests.add(
                            requestedBlueId);
                    return rootBlueId.equals(
                            requestedBlueId)
                            ? Collections.singletonList(
                            inlineRoot.clone())
                            : null;
                });
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());
        Node reference =
                reference(rootBlueId);

        // When
        CoordinationSubscriptionSnapshot cold =
                projector.projectCurrent(
                        reference.clone(),
                        4L,
                        order(4));
        int coldRootProviderRequests =
                Collections.frequency(
                        providerRequests,
                        rootBlueId);
        CoordinationSubscriptionSnapshot warm =
                projector.projectCurrent(
                        reference.clone(),
                        4L,
                        order(4));
        CoordinationSubscriptionSnapshot inline =
                projector.projectCurrent(
                        inlineRoot.clone(),
                        4L,
                        order(4));

        // Then
        assertTrue(
                coldRootProviderRequests > 0,
                "the first pure-reference projection must "
                        + "reach the configured provider");
        assertEquals(
                inline.toMap(),
                cold.toMap());
        assertEquals(
                cold.toMap(),
                warm.toMap());
    }

    @Test
    void shouldProduceExactSnapshotForPartiallyMaterializedNestedRoot() {
        // Given
        Fixture fixture = fixture();
        Node inlineRoot = initialized(
                fixture,
                nestedDocument(
                        fixture.repository,
                        3,
                        TestTimelineProvider.channel(
                                "partial-nested")));
        CoordinationDocumentSplitter.SplitGraph split =
                new CoordinationDocumentSplitter(
                        fixture.blue
                                .getDocumentProcessor())
                        .splitDocument(
                                inlineRoot.clone());
        List<String> providerRequests =
                new ArrayList<String>();
        installProvider(
                fixture,
                requestedBlueId -> {
                    providerRequests.add(
                            requestedBlueId);
                    return split.provider()
                            .fetchByBlueId(
                                    requestedBlueId);
                });
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());

        // When
        CoordinationSubscriptionSnapshot inline =
                projector.projectCurrent(
                        inlineRoot.clone(),
                        11L,
                        order(11));
        CoordinationSubscriptionSnapshot partial =
                projector.projectCurrent(
                        split.pureReference(),
                        11L,
                        order(11));

        // Then
        assertEquals(
                split.rootBlueId(),
                fixture.blue.calculateBlueId(
                        split.processingRootView()));
        assertTrue(
                split.fragmentedRoot()
                        .getAsNode(
                                "/emb1")
                        .isReferenceOnly());
        assertTrue(
                providerRequests.contains(
                        split.rootBlueId()),
                "partial nested projection must materialize "
                        + "the exact PROCESS header view");
        assertEquals(
                inline.digest(),
                partial.digest());
        assertEquals(
                inline.toMap(),
                partial.toMap());
    }

    @Test
    void shouldProduceExactSnapshotAcrossBatchedComposedProviderSegments() {
        // Given
        Fixture fixture = fixture();
        Node inlineRoot = initialized(
                fixture,
                nestedDocument(
                        fixture.repository,
                        3,
                        TestTimelineProvider.channel(
                                "batched-composed")));
        CoordinationDocumentSplitter.SplitGraph split =
                new CoordinationDocumentSplitter(
                        fixture.blue
                                .getDocumentProcessor())
                        .splitDocument(
                                inlineRoot.clone());
        String rootBlueId =
                split.rootBlueId();
        Set<String> firstSegment =
                Collections.singleton(
                        rootBlueId);
        Set<String> secondSegment =
                new LinkedHashSet<String>(
                        Arrays.asList(
                                RuntimeBlueIds.PROCESS_EMBEDDED,
                                TimelineChannel.blueId(),
                                Timeline.blueId(),
                                PrincipalActor.blueId()));
        List<String> firstSegmentRequests =
                new ArrayList<String>();
        List<String> secondSegmentRequests =
                new ArrayList<String>();
        NodeProvider existingProvider =
                fixture.blue.getNodeProvider();
        NodeProvider firstProvider =
                requestedBlueId -> {
                    if (!firstSegment.contains(
                            requestedBlueId)) {
                        return null;
                    }
                    firstSegmentRequests.add(
                            requestedBlueId);
                    return split.provider()
                            .fetchByBlueId(
                                    requestedBlueId);
                };
        NodeProvider secondProvider =
                requestedBlueId -> {
                    secondSegmentRequests.add(
                            requestedBlueId);
                    return existingProvider
                            .fetchByBlueId(
                                    requestedBlueId);
                };
        NodeProvider composedProvider =
                new SequentialNodeProvider(
                        new CoordinationBehaviorFixtureHarness
                                .BoundedPrefetchProvider(
                                firstProvider,
                                firstSegment,
                                1),
                        new CoordinationBehaviorFixtureHarness
                                .BoundedPrefetchProvider(
                                secondProvider,
                                secondSegment,
                                2));
        installProvider(
                fixture,
                composedProvider);
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());

        // When
        CoordinationSubscriptionSnapshot inline =
                projector.projectCurrent(
                        inlineRoot.clone(),
                        12L,
                        order(12));
        firstSegmentRequests.clear();
        secondSegmentRequests.clear();
        fixture.blue
                .clearResolvedSnapshotCache();
        CoordinationSubscriptionSnapshot segmented =
                projector.projectCurrent(
                        split.pureReference(),
                        12L,
                        order(12));

        // Then
        assertFalse(
                firstSegmentRequests.isEmpty(),
                "the first provider segment must serve "
                        + "the exact Root header view");
        assertFalse(
                secondSegmentRequests.isEmpty(),
                "the composed provider must continue into "
                        + "the exact header dependency batch");
        assertEquals(
                inline.digest(),
                segmented.digest());
        assertEquals(
                inline.toMap(),
                segmented.toMap());
    }

    @Test
    void shouldKeepCyclicMemberEdgeOpaqueDuringSubscriptionProjection() {
        // Given
        Fixture fixture = fixture();
        Node root = initialized(
                fixture,
                rootChannelDocument(
                        fixture.repository,
                        TestTimelineProvider.channel(
                                "cyclic")));
        String masterBlueId =
                fixture.blue.calculateBlueId(
                        new Node().value(
                                "cyclic subscription body"));
        String memberBlueId =
                masterBlueId + "#0";
        root.getAsNode(
                        "/contracts/channel")
                .properties(
                        "opaqueEdge",
                        reference(
                                memberBlueId));
        List<String> providerRequests =
                new ArrayList<String>();
        installProvider(
                fixture,
                requestedBlueId -> {
                    providerRequests.add(
                            requestedBlueId);
                    return null;
                });
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());

        // When
        CoordinationSubscriptionSnapshot snapshot =
                projector.projectCurrent(
                        root,
                        1L,
                        order(1));

        // Then
        assertEquals(
                1,
                snapshot.occurrences().size());
        assertFalse(
                providerRequests.contains(
                        memberBlueId),
                "subscription projection must not open "
                        + "an opaque cyclic member edge");
        assertEquals(
                memberBlueId,
                snapshot.occurrences().get(0)
                        .headerFieldBlueIds()
                        .get("opaqueEdge"));
        assertTrue(
                root.getAsNode(
                                "/contracts/channel/opaqueEdge")
                        .isReferenceOnly());
        assertEquals(
                memberBlueId,
                root.getAsNode(
                                "/contracts/channel/opaqueEdge")
                        .getBlueId());
    }

    @Test
    void shouldBindSnapshotIdentityToExplicitTimelineSubtypeRegistrations() {
        // Given
        Fixture base = fixture(false);
        Fixture extended = fixture(true);
        Node baseRoot = initialized(
                base,
                rootChannelDocument(
                        base.repository,
                        TestTimelineProvider.channel(
                                "timeline")));
        Node extendedRoot = initialized(
                extended,
                rootChannelDocument(
                        extended.repository,
                        TestTimelineProvider.channel(
                                "timeline")));

        // When
        CoordinationSubscriptionSnapshot baseSnapshot =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                base.blue
                                        .getDocumentProcessor())
                        .projectCurrent(
                                baseRoot,
                                1L,
                                order(1));
        CoordinationSubscriptionSnapshot
                extendedSnapshot =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                extended.blue
                                        .getDocumentProcessor())
                        .projectCurrent(
                                extendedRoot,
                                1L,
                                order(1));

        // Then
        assertNotEquals(
                baseSnapshot
                        .coordinationRuntimeRegistryIdentity(),
                extendedSnapshot
                        .coordinationRuntimeRegistryIdentity());
        assertNotEquals(
                baseSnapshot.digest(),
                extendedSnapshot.digest());
        assertTrue(
                CoordinationRuntimeRegistrations
                        .timelineSubtypeBlueIds(
                                base.blue
                                        .getDocumentProcessor())
                        .isEmpty());
        assertEquals(
                Collections.singletonList(
                        MyOSTimelineChannel.blueId()),
                CoordinationRuntimeRegistrations
                        .timelineSubtypeBlueIds(
                                extended.blue
                                        .getDocumentProcessor()));
    }

    @Test
    void shouldRejectUpdateAfterTimelineSubtypeRegistryChanges() {
        // Given
        Fixture fixture = fixture(false);
        Node root = initialized(
                fixture,
                rootChannelDocument(
                        fixture.repository,
                        TestTimelineProvider.channel(
                                "timeline")));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());
        CoordinationSubscriptionSnapshot initial =
                projector.projectCurrent(
                        root.clone(),
                        1L,
                        order(1));
        CoordinationProcessors.registerTimelineSubtype(
                fixture.blue,
                MyOSTimelineChannel.class);

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> projector.projectUpdate(
                                initial,
                                root.clone(),
                                2L,
                                order(2)));

        // Then
        assertTrue(
                failure.getMessage().contains(
                        "Coordination runtime registry identity "
                                + "mismatch"),
                failure.getMessage());
    }

    @Test
    void shouldKeepSameExactChildAtTwoPathsAsTwoOccurrences() {
        // Given
        Fixture fixture = fixture();
        Node child = scopeWithChannel(
                "shared",
                TestTimelineProvider.channel(
                        "shared"));
        Map<String, Node> properties =
                new LinkedHashMap<String, Node>();
        properties.put("left", child.clone());
        properties.put("right", child.clone());
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                "embedded",
                processEmbedded("/left", "/right"));
        Node root = initialized(
                fixture,
                document(
                        fixture.repository,
                        contracts,
                        properties));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());

        // When
        CoordinationSubscriptionSnapshot snapshot =
                projector.projectCurrent(
                        root, 1L, order(1));

        // Then
        assertEquals(2, snapshot.occurrences().size());
        assertEquals(
                Arrays.asList("/left", "/right"),
                scopePaths(snapshot));
        assertEquals(
                snapshot.occurrences().get(0)
                        .scopeBlueId(),
                snapshot.occurrences().get(1)
                        .scopeBlueId());
        assertNotEquals(
                snapshot.occurrences().get(0)
                        .occurrenceKey(),
                snapshot.occurrences().get(1)
                        .occurrenceKey());
    }

    @Test
    void shouldRejectProjectionBeforeTheOverLimitOccurrenceIsAdmitted() {
        // Given
        Fixture fixture = fixture();
        Node child = scopeWithChannel(
                "shared",
                TestTimelineProvider.channel(
                        "shared"));
        Map<String, Node> properties =
                new LinkedHashMap<String, Node>();
        properties.put("left", child.clone());
        properties.put("right", child.clone());
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                "embedded",
                processEmbedded("/left", "/right"));
        Node root = initialized(
                fixture,
                document(
                        fixture.repository,
                        contracts,
                        properties));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());
        CoordinationHostQuotaSession hostQuotas =
                CoordinationHostQuotaSession.observing(
                        CoordinationHostQuotaTestSupport
                                .limitedSubscriptionOccurrences(1));

        // When
        CoordinationHostQuotaExceededException failure =
                assertThrows(
                        CoordinationHostQuotaExceededException.class,
                        () -> projector.projectCurrent(
                                root,
                                1L,
                                order(1),
                                hostQuotas));

        // Then
        assertEquals(
                "maxSubscriptionOccurrencesPerProjection",
                failure.limitName());
        assertEquals(2L, failure.attemptedQuantity());
        assertEquals(1L, failure.admittedQuantity());
        assertEquals(
                1L,
                hostQuotas.quantity(
                        CoordinationHostQuotaSchedule
                                .SUBSCRIPTION_OCCURRENCE_PROJECTED));
        CoordinationHostQuotaTraceEntry admitted =
                hostQuotas.trace().get(0);
        assertEquals(
                "project-current-subscriptions",
                admitted.operation());
        assertEquals(
                "/occurrences/0",
                admitted.logicalPath());
    }

    @Test
    void shouldRejectDirectRootLowerBoundBeforeLanguageProjectionWork() {
        // Given
        Fixture fixture = fixture();
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                "left",
                new Node().type(
                        new Node().blueId(
                                TimelineChannel.blueId())));
        contracts.put(
                "right",
                new Node().type(
                        new Node().blueId(
                                TimelineChannel.blueId())));
        FailOnRepeatedContractsReadNode root =
                new FailOnRepeatedContractsReadNode(
                        new Node().properties(contracts));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());
        CoordinationHostQuotaSession hostQuotas =
                CoordinationHostQuotaSession.observing(
                        CoordinationHostQuotaTestSupport
                                .limitedSubscriptionOccurrences(1));

        // When
        CoordinationHostQuotaExceededException failure =
                assertThrows(
                        CoordinationHostQuotaExceededException.class,
                        () -> projector.projectCurrent(
                                root,
                                1L,
                                order(1),
                                hostQuotas));

        // Then
        assertEquals(
                "maxSubscriptionOccurrencesPerProjection",
                failure.limitName());
        assertEquals(2L, failure.attemptedQuantity());
        assertEquals(0L, failure.admittedQuantity());
        assertTrue(hostQuotas.trace().isEmpty());
        assertEquals(1, root.contractReads());
    }

    @Test
    void shouldRepresentRetypeAsRetireAddAndMatchFreshProjection() {
        // Given
        Fixture fixture = fixture();
        Node before = initialized(
                fixture,
                rootChannelDocument(
                        fixture.repository,
                        TestTimelineProvider.channel(
                                "timeline")));
        MyOSTimeline subtypeTimeline =
                new MyOSTimeline();
        subtypeTimeline.timelineId("timeline");
        MyOSTimelineChannel subtype =
                new MyOSTimelineChannel()
                        .accountId("timeline")
                        .email("timeline@example.test");
        subtype.timeline(subtypeTimeline);
        subtype.actor(
                new PrincipalActor()
                        .accountId("timeline"));
        Node subtypeChannel =
                fixture.blue.objectToNode(subtype);
        Node after = initialized(
                fixture,
                rootChannelDocument(
                        fixture.repository,
                        subtypeChannel));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());
        CoordinationSubscriptionSnapshot initial =
                projector.projectCurrent(
                        before, 1L, order(1));

        // When
        CoordinationSubscriptionUpdate update =
                projector.projectUpdate(
                        initial,
                        after,
                        2L,
                        order(2),
                        Collections.singleton(
                                "/contracts/channel"));
        CoordinationSubscriptionSnapshot fresh =
                projector.projectCurrent(
                        after.clone(),
                        2L,
                        order(2));

        // Then
        assertEquals(1, update.retired().size());
        assertEquals(1, update.added().size());
        assertTrue(update.unchanged().isEmpty());
        assertNotEquals(
                update.retired().get(0)
                        .effectiveTypeBlueId(),
                update.added().get(0)
                        .effectiveTypeBlueId());
        assertEquals(
                fresh.toMap(),
                update.snapshot().toMap());
    }

    @Test
    void shouldStartNewActivationIntervalAfterRemovalAndReaddition() {
        // Given
        Fixture fixture = fixture();
        Node present = initialized(
                fixture,
                rootChannelDocument(
                        fixture.repository,
                        TestTimelineProvider.channel(
                                "timeline")));
        Node absent = initialized(
                fixture,
                document(
                        fixture.repository,
                        Collections
                                .<String, Node>emptyMap(),
                        Collections
                                .<String, Node>emptyMap()));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());
        CoordinationSubscriptionSnapshot initial =
                projector.projectCurrent(
                        present.clone(),
                        1L,
                        order(1));

        // When
        CoordinationSubscriptionUpdate removal =
                projector.projectUpdate(
                        initial,
                        absent,
                        2L,
                        order(2),
                        Collections.singleton(
                                "/contracts/channel"));
        CoordinationSubscriptionUpdate readdition =
                projector.projectUpdate(
                        CoordinationSubscriptionSnapshot
                                .rehydrate(
                                        removal.snapshot()
                                                .toMap()),
                        present.clone(),
                        3L,
                        order(3),
                        Collections.singleton(
                                "/contracts/channel"));

        // Then
        assertEquals(1, removal.retired().size());
        assertTrue(removal.snapshot()
                .occurrences().isEmpty());
        assertEquals(1, readdition.added().size());
        assertEquals(
                Long.valueOf(3L),
                readdition.added().get(0)
                        .activationRootRevision());
        assertEquals(
                order(3),
                readdition.added().get(0)
                        .activationFrontier());
        assertNotEquals(
                initial.digest(),
                readdition.snapshot().digest());
    }

    @Test
    void shouldPruneTerminatedEmbeddedSubscriptionSubtree() {
        // Given
        Fixture fixture = fixture();
        Node child = scopeWithChannel(
                "childChannel",
                TestTimelineProvider.channel(
                        "child"));
        Map<String, Node> properties =
                new LinkedHashMap<String, Node>();
        properties.put("child", child);
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                "embedded",
                processEmbedded("/child"));
        Node root = initialized(
                fixture,
                document(
                        fixture.repository,
                        contracts,
                        properties));
        root.getAsNode("/child/contracts")
                .properties(
                        "terminated",
                        new ProcessingTerminatedMarker()
                                .cause("test-complete")
                                .toNode());
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue
                                        .getDocumentProcessor());

        // When
        CoordinationSubscriptionSnapshot snapshot =
                projector.projectCurrent(
                        root, 2L, order(2));

        // Then
        assertTrue(snapshot.occurrences().isEmpty());
        assertEquals(
                Collections.singleton("/child"),
                snapshot.prunedScopePaths());
    }

    private static Fixture fixture() {
        return fixture(true);
    }

    private static Fixture fixture(
            boolean registerMyosTimelineSubtype) {
        BlueRepository repository =
                BlueRepository.latest();
        Blue blue =
                CoordinationTestResources
                        .configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        if (registerMyosTimelineSubtype) {
            CoordinationProcessors
                    .registerTimelineSubtype(
                            blue,
                            MyOSTimelineChannel.class);
        }
        return new Fixture(repository, blue);
    }

    private static Node initialized(
            Fixture fixture,
            Node authored) {
        DocumentProcessingResult result =
                fixture.blue.initializeDocument(
                        fixture.blue.preprocess(authored));
        if (ProcessingResultTestSupport
                .isCapabilityFailure(result)) {
            throw new AssertionError(
                    ProcessingResultTestSupport
                            .diagnosticMessage(result));
        }
        return result.document();
    }

    private static Node rootChannelDocument(
            BlueRepository repository,
            Node channel) {
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put("channel", channel);
        return document(
                repository,
                contracts,
                Collections.<String, Node>emptyMap());
    }

    private static Node exactTimelineChannel(
            String timelineId) {
        return new Node()
                .type(reference(
                        TimelineChannel.blueId()))
                .properties(
                        "timeline",
                        new Node()
                                .type(reference(
                                        Timeline.blueId()))
                                .properties(
                                        "timelineId",
                                        new Node().value(
                                                timelineId)))
                .properties(
                        "actor",
                        new Node()
                                .type(reference(
                                        PrincipalActor.blueId()))
                                .properties(
                                        "accountId",
                                        new Node().value(
                                                timelineId)));
    }

    private static Node exactProcessEmbedded(
            String... paths) {
        Node embedded =
                processEmbedded(paths);
        embedded.type(
                reference(
                        RuntimeBlueIds.PROCESS_EMBEDDED));
        return embedded;
    }

    private static NodeProvider exactProvider(
            String blueId,
            Node exact) {
        return requestedBlueId ->
                blueId.equals(
                        requestedBlueId)
                        ? Collections.singletonList(
                        exact.clone())
                        : null;
    }

    private static void installProvider(
            Fixture fixture,
            NodeProvider provider) {
        fixture.blue.nodeProvider(
                new SequentialNodeProvider(
                        provider,
                        fixture.blue.getNodeProvider()));
    }

    private static Node nestedDocument(
            BlueRepository repository,
            int depth,
            Node channel) {
        Node current =
                scopeWithChannel("channel", channel);
        for (int index = depth;
             index >= 1;
             index--) {
            String child = "emb" + index;
            Map<String, Node> properties =
                    new LinkedHashMap<String, Node>();
            properties.put(child, current);
            Map<String, Node> contracts =
                    new LinkedHashMap<String, Node>();
            contracts.put(
                    "embedded",
                    processEmbedded("/" + child));
            current = new Node()
                    .properties(properties)
                    .properties(
                            "contracts",
                            new Node().properties(
                                    contracts));
        }
        current.blue(repository.typeAliasBlue());
        current.name("Nested subscriptions");
        return current;
    }

    private static Node scopeWithChannel(
            String key,
            Node channel) {
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(key, channel);
        return new Node()
                .properties(
                        "contracts",
                        new Node().properties(contracts));
    }

    private static Node processEmbedded(
            String... paths) {
        List<Node> items =
                new ArrayList<Node>();
        for (String path : paths) {
            items.add(new Node().value(path));
        }
        return new Node()
                .type("Process Embedded")
                .properties(
                        "paths",
                        new Node().items(items));
    }

    private static Node reference(
            String blueId) {
        return new Node().blueId(blueId);
    }

    private static Node document(
            BlueRepository repository,
            Map<String, Node> contracts,
            Map<String, Node> properties) {
        Node root = new Node()
                .blue(repository.typeAliasBlue())
                .name("Subscription projection")
                .properties(properties);
        root.properties(
                "contracts",
                new Node().properties(contracts));
        return root;
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(
                Collections.singletonList(
                        BigInteger.valueOf(value)));
    }

    private static List<String> scopePaths(
            CoordinationSubscriptionSnapshot snapshot) {
        List<String> result =
                new ArrayList<String>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            result.add(occurrence.scopePath());
        }
        return result;
    }

    private static List<String> processEmbeddedPaths(
            CoordinationSubscriptionSnapshot snapshot,
            String contractPath) {
        Object routes =
                snapshot.toMap().get(
                        "processEmbeddedRoutes");
        if (!(routes instanceof Map)) {
            throw new AssertionError(
                    "Missing processEmbeddedRoutes");
        }
        Object encoded =
                ((Map<?, ?>) routes).get(
                        contractPath);
        if (!(encoded instanceof List)) {
            throw new AssertionError(
                    "Missing Process Embedded route "
                            + contractPath);
        }
        List<String> result =
                new ArrayList<String>();
        for (Object path : (List<?>) encoded) {
            result.add(String.valueOf(path));
        }
        return result;
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final Blue blue;

        private Fixture(
                BlueRepository repository,
                Blue blue) {
            this.repository = repository;
            this.blue = blue;
        }
    }

    private static final class FailOnRepeatedContractsReadNode
            extends Node {
        private int contractReads;

        private FailOnRepeatedContractsReadNode(
                Node contracts) {
            contracts(contracts);
        }

        @Override
        public Node getContracts() {
            contractReads++;
            if (contractReads > 1) {
                throw new AssertionError(
                        "Language projection work began");
            }
            return super.getContracts();
        }

        private int contractReads() {
            return contractReads;
        }
    }
}
