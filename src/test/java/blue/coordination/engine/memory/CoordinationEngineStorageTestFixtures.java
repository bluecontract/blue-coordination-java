package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationAtomicCommitPlan;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DocumentAdmissionCommit;
import blue.coordination.engine.api.DocumentEpochSnapshot;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.LocalityDiagnostics;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.ManagedDocumentStatus;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.RegistrationMode;
import blue.coordination.engine.api.TransitionMemoKey;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationEngineProcessorTestFixtures;
import blue.coordination.processor.CoordinationPreparedDelivery;
import blue.coordination.processor.CoordinationSubscriptionSnapshot;
import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.CoordinationEngineLanguageTestFixtures;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.VerifiedExecutionEvidence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class CoordinationEngineStorageTestFixtures {

    static final String PROFILE =
            CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID;

    private CoordinationEngineStorageTestFixtures() {
    }

    static FragmentGraph graph(String label) {
        Node exact = new Node().properties(
                "kind", new Node().value("storage-tck"),
                "label", new Node().value(label),
                "payload", new Node().properties(
                        "counter", new Node().value(label.length()),
                        "first", new Node().value(label + "-a"),
                        "second", new Node().value(label + "-b")));
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitter.forEventSplitting()
                        .splitEvent(exact);
        return new FragmentGraph(
                exact,
                split,
                CoordinationFragmentInventory.from(split));
    }

    static InMemoryCoordinationFragmentStore fragmentStore(
            FragmentGraph... graphs) {
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(PROFILE);
        for (FragmentGraph graph : graphs) {
            store.putAllIfAbsent(PROFILE, graph.split.fragments());
        }
        return store;
    }

    static AdmissionFixture admission(
            String sessionValue,
            String documentLabel) {
        return admission(
                sessionValue,
                graph(documentLabel),
                RegistrationMode.OPEN_OR_CREATE,
                null);
    }

    static AdmissionFixture admission(
            String sessionValue,
            FragmentGraph graph,
            RegistrationMode mode,
            Long claimedEpoch) {
        DocumentSessionId sessionId = DocumentSessionId.of(sessionValue);
        ExternalOrderKey frontier = order(0L);
        CoordinationSubscriptionSnapshot subscriptions =
                CoordinationEngineProcessorTestFixtures.emptySnapshot(
                        graph.inventory.rootBlueId(),
                        0L,
                        frontier);
        ManagedDocumentSnapshot session = new ManagedDocumentSnapshot(
                sessionId,
                graph.inventory.rootBlueId(),
                graph.inventory.rootBlueId(),
                0L,
                "environment-test",
                frontier,
                graph.inventory.inventoryIdentity(),
                subscriptions,
                ManagedDocumentStatus.ACTIVE);
        DocumentEpochSnapshot epochZero = new DocumentEpochSnapshot(
                sessionId,
                0L,
                graph.inventory.rootBlueId(),
                null,
                null,
                null,
                graph.inventory.inventoryIdentity(),
                subscriptions.digest(),
                Collections.<String>emptyList(),
                0L,
                "admission:" + sessionValue + ":"
                        + graph.inventory.rootBlueId());
        DocumentRegistration registration = new DocumentRegistration(
                sessionId,
                graph.exact,
                frontier,
                mode,
                claimedEpoch);
        return new AdmissionFixture(
                graph,
                session,
                epochZero,
                new DocumentAdmissionCommit(
                        registration,
                        session,
                        epochZero,
                        graph.inventory));
    }

    static CommitFixture successfulCommit(
            AdmissionFixture admitted,
            String changeLabel,
            String transitionIdentity) {
        FragmentGraph event = graph("event-" + changeLabel);
        FragmentGraph after = graph("root-" + changeLabel);
        ExternalOrderKey order = order(1L);
        Node rootEvent = new Node().properties(
                "kind", new Node().value("root-event"),
                "change", new Node().value(changeLabel));
        DocumentProcessingResult processResult = DocumentProcessingResult.of(
                after.exact,
                Collections.singletonList(rootEvent),
                17L);
        PlatformProcessingResult platformResult = platformResult(
                admitted.session,
                event,
                order,
                processResult);
        CoordinationSubscriptionSnapshot subscriptions =
                CoordinationEngineProcessorTestFixtures.emptySnapshot(
                        after.inventory.rootBlueId(),
                        admitted.session.currentEpoch() + 1L,
                        order);
        ManagedDocumentSnapshot resultingSession =
                new ManagedDocumentSnapshot(
                        admitted.session.sessionId(),
                        admitted.session.initialDocumentBlueId(),
                        after.inventory.rootBlueId(),
                        admitted.session.currentEpoch() + 1L,
                        admitted.session.environmentIdentity(),
                        order,
                        after.inventory.inventoryIdentity(),
                        subscriptions,
                        ManagedDocumentStatus.ACTIVE);
        CoordinationFragmentTransition fragmentTransition =
                new CoordinationFragmentTransition(
                        after.inventory,
                        after.split.fragments(),
                        Collections.<String>emptyList(),
                        after.inventory.edges(),
                        Collections.emptyList(),
                        Collections.emptyList());
        CoordinationSubscriptionUpdate subscriptionUpdate =
                CoordinationSubscriptionUpdate.unchanged(
                        subscriptions, order);
        List<String> rootEventBlueIds = Collections.singletonList(
                DirectBlueIdCalculator.calculateBlueId(rootEvent));
        DocumentEpochSnapshot resultingEpoch = new DocumentEpochSnapshot(
                admitted.session.sessionId(),
                admitted.session.currentEpoch() + 1L,
                after.inventory.rootBlueId(),
                admitted.session.currentRootBlueId(),
                event.inventory.rootBlueId(),
                order,
                after.inventory.inventoryIdentity(),
                subscriptions.digest(),
                rootEventBlueIds,
                processResult.totalGas(),
                transitionIdentity);
        CoordinationAtomicCommitPlan commitPlan =
                new CoordinationAtomicCommitPlan(
                        admitted.session.sessionId(),
                        admitted.session.currentEpoch(),
                        admitted.session.currentRootBlueId(),
                        admitted.session.initialDocumentBlueId(),
                        admitted.session.environmentIdentity(),
                        admitted.session.committedFrontier(),
                        admitted.session.fragmentInventoryIdentity(),
                        admitted.session.subscriptions().digest(),
                        admitted.session.currentEpoch() + 1L,
                        after.inventory.rootBlueId(),
                        event.inventory.rootBlueId(),
                        order,
                        processResult,
                        platformResult.commitCompanion(),
                        fragmentTransition,
                        subscriptionUpdate,
                        rootEventBlueIds,
                        transitionIdentity,
                        resultingSession,
                        resultingEpoch);
        CoordinationProcessingPlan processingPlan = processingPlan(
                admitted,
                event,
                order);
        CoordinationTransition transition = new CoordinationTransition(
                processingPlan,
                platformResult,
                fragmentTransition,
                subscriptionUpdate,
                commitPlan,
                LocalityDiagnostics.empty());
        return new CommitFixture(
                event,
                after,
                platformResult,
                commitPlan,
                transition);
    }

    static CommitFixture progressOnlyCommit(
            AdmissionFixture admitted,
            String eventLabel,
            String transitionIdentity) {
        return progressOnlyCommit(
                admitted,
                eventLabel,
                transitionIdentity,
                1L);
    }

    static CommitFixture progressOnlyCommit(
            AdmissionFixture admitted,
            String eventLabel,
            String transitionIdentity,
            long orderValue) {
        FragmentGraph event = graph("event-" + eventLabel);
        ExternalOrderKey order = order(orderValue);
        DocumentProcessingResult processResult =
                DocumentProcessingResult.capabilityFailure(
                        admitted.graph.exact,
                        "expected test-only capability failure");
        PlatformProcessingResult platformResult = platformResult(
                admitted.session,
                event,
                order,
                processResult);
        ManagedDocumentSnapshot resultingSession =
                new ManagedDocumentSnapshot(
                        admitted.session.sessionId(),
                        admitted.session.initialDocumentBlueId(),
                        admitted.session.currentRootBlueId(),
                        admitted.session.currentEpoch(),
                        admitted.session.environmentIdentity(),
                        order,
                        admitted.session.fragmentInventoryIdentity(),
                        admitted.session.subscriptions(),
                        ManagedDocumentStatus.ACTIVE);
        CoordinationFragmentTransition fragmentTransition =
                new CoordinationFragmentTransition(
                        admitted.graph.inventory,
                        Collections.<String, Node>emptyMap(),
                        admitted.graph.inventory.fragmentBlueIds(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList());
        CoordinationSubscriptionUpdate subscriptionUpdate =
                CoordinationSubscriptionUpdate.unchanged(
                        admitted.session.subscriptions(), order);
        CoordinationAtomicCommitPlan commitPlan =
                new CoordinationAtomicCommitPlan(
                        admitted.session.sessionId(),
                        admitted.session.currentEpoch(),
                        admitted.session.currentRootBlueId(),
                        admitted.session.initialDocumentBlueId(),
                        admitted.session.environmentIdentity(),
                        admitted.session.committedFrontier(),
                        admitted.session.fragmentInventoryIdentity(),
                        admitted.session.subscriptions().digest(),
                        admitted.session.currentEpoch(),
                        admitted.session.currentRootBlueId(),
                        event.inventory.rootBlueId(),
                        order,
                        processResult,
                        platformResult.commitCompanion(),
                        fragmentTransition,
                        subscriptionUpdate,
                        Collections.<String>emptyList(),
                        transitionIdentity,
                        resultingSession,
                        null);
        CoordinationTransition transition = new CoordinationTransition(
                processingPlan(admitted, event, order),
                platformResult,
                fragmentTransition,
                subscriptionUpdate,
                commitPlan,
                LocalityDiagnostics.empty());
        return new CommitFixture(
                event,
                admitted.graph,
                platformResult,
                commitPlan,
                transition);
    }

    static TransitionMemoKey memoKey(
            DocumentSessionId sessionId,
            FragmentGraph root,
            FragmentGraph event) {
        return new TransitionMemoKey(
                sessionId,
                root.inventory.rootBlueId(),
                event.inventory.rootBlueId(),
                "execution-evidence-test",
                "environment-test",
                "gas-schedule-test");
    }

    static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(Arrays.<Object>asList(value, "storage-tck"));
    }

    private static PlatformProcessingResult platformResult(
            ManagedDocumentSnapshot session,
            FragmentGraph event,
            ExternalOrderKey order,
            DocumentProcessingResult processResult) {
        VerifiedExecutionEvidence evidence = VerifiedExecutionEvidence
                .builder(
                        session.currentRootBlueId(),
                        event.inventory.rootBlueId())
                .revisions(session.currentEpoch(), session.currentEpoch())
                .runtimeRegistryIdentity("language-runtime-test")
                .eventOrderKey(order)
                .activeSubscriptionIntervals(Collections.emptyList())
                .availableExactNode(session.currentRootBlueId())
                .availableExactNode(event.inventory.rootBlueId())
                .requiredExactNode(session.currentRootBlueId())
                .requiredExactNode(event.inventory.rootBlueId())
                .build();
        return CoordinationEngineLanguageTestFixtures.platformResult(
                evidence, processResult);
    }

    private static CoordinationProcessingPlan processingPlan(
            AdmissionFixture admitted,
            FragmentGraph event,
            ExternalOrderKey order) {
        CoordinationPreparedDelivery prepared =
                CoordinationEngineProcessorTestFixtures
                        .emptyPreparedDelivery(
                                admitted.session.currentRootBlueId(),
                                event.inventory.rootBlueId(),
                                admitted.session.currentEpoch(),
                                order,
                                admitted.session.subscriptions().digest());
        List<String> seeds = new ArrayList<String>();
        seeds.add(admitted.session.currentRootBlueId());
        seeds.add(event.inventory.rootBlueId());
        return new CoordinationProcessingPlan(
                admitted.session,
                new Node().blueId(admitted.session.currentRootBlueId()),
                new Node().blueId(event.inventory.rootBlueId()),
                prepared,
                admitted.graph.inventory,
                event.inventory,
                seeds,
                Collections.<String>emptyList(),
                prepared.demandBoundary(),
                "processing-plan-test",
                PrefetchPolicy.BALANCED);
    }

    static final class FragmentGraph {
        final Node exact;
        final CoordinationDocumentSplitter.SplitGraph split;
        final CoordinationFragmentInventory inventory;

        FragmentGraph(
                Node exact,
                CoordinationDocumentSplitter.SplitGraph split,
                CoordinationFragmentInventory inventory) {
            this.exact = exact.clone();
            this.split = split;
            this.inventory = inventory;
        }
    }

    static final class AdmissionFixture {
        final FragmentGraph graph;
        final ManagedDocumentSnapshot session;
        final DocumentEpochSnapshot epochZero;
        final DocumentAdmissionCommit commit;

        AdmissionFixture(
                FragmentGraph graph,
                ManagedDocumentSnapshot session,
                DocumentEpochSnapshot epochZero,
                DocumentAdmissionCommit commit) {
            this.graph = graph;
            this.session = session;
            this.epochZero = epochZero;
            this.commit = commit;
        }
    }

    static final class CommitFixture {
        final FragmentGraph event;
        final FragmentGraph after;
        final PlatformProcessingResult platformResult;
        final CoordinationAtomicCommitPlan plan;
        final CoordinationTransition transition;

        CommitFixture(
                FragmentGraph event,
                FragmentGraph after,
                PlatformProcessingResult platformResult,
                CoordinationAtomicCommitPlan plan,
                CoordinationTransition transition) {
            this.event = event;
            this.after = after;
            this.platformResult = platformResult;
            this.plan = plan;
            this.transition = transition;
        }
    }
}
