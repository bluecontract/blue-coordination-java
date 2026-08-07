package blue.coordination.engine;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DeliveryPlanningMode;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentAdmissionStatus;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.ProcessRequest;
import blue.coordination.engine.memory.InMemoryCoordinationFragmentStore;
import blue.coordination.engine.memory.InMemoryCoordinationProcessingBundleLoader;
import blue.coordination.engine.memory.InMemoryCoordinationSessionStore;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationSubscriptionOccurrence;
import blue.coordination.processor.RepositoryIndependentCoordinationTestRuntime;
import blue.coordination.processor.RepositoryIndependentCoordinationTypes;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository-independent engine acceptance over ten stable-key collections,
 * each containing ten stable-key child scopes.
 */
final class CoordinationProcessingEngineTenByTenCampaignTest {

    private static final String A25 = "/agreements/A2/processes/A25";
    private static final String A73 = "/agreements/A7/processes/A73";
    private static final String A211 = "/agreements/A2/processes/A211";
    private static final String A2 = "/agreements/A2";
    private static final ExternalOrderKey ACTIVATION_ORDER =
            order(10L, "activation");

    @Test
    void shouldCommitConsecutiveLeavesAcrossEveryPrefetchPolicy() {
        // given
        List<PrefetchPolicy> policies = Arrays.asList(
                PrefetchPolicy.MINIMUM_BYTES,
                PrefetchPolicy.BALANCED,
                PrefetchPolicy.MINIMUM_ROUND_TRIPS);
        List<ConsecutiveExecution> executions =
                new ArrayList<ConsecutiveExecution>();

        // when
        for (PrefetchPolicy policy : policies) {
            try (Harness harness = Harness.open(Representation.INLINE)) {
                executions.add(harness.executeConsecutiveLeaves(
                        DocumentSessionId.of(
                                "ten-by-ten-consecutive-" + policy.name()),
                        policy));
            }
        }

        // then
        ConsecutiveExecution expected = executions.get(0);
        for (int index = 0; index < executions.size(); index++) {
            ConsecutiveExecution actual = executions.get(index);
            assertEquals(DocumentAdmissionStatus.CREATED,
                    actual.admissionStatus,
                    "admission status for " + policies.get(index));
            assertEquals(103, actual.initialOccurrenceCount,
                    "initial subscription count for " + policies.get(index));
            assertTrue(actual.initialA211Absent,
                    "A211 must start inactive for " + policies.get(index));
            assertTrue(actual.referencesOnly,
                    "PROCESS inputs must be references for "
                            + policies.get(index));
            assertTrue(actual.selectedA25,
                    "A25 must be selected for " + policies.get(index));
            assertTrue(actual.selectedA73,
                    "A73 must be selected for " + policies.get(index));
            assertTrue(actual.secondPlanUsesFirstInventory,
                    "the second event must use the first committed inventory "
                            + "for " + policies.get(index));
            assertEquals(
                    Arrays.asList(
                            ProcessorStatus.SUCCESS,
                            ProcessorStatus.SUCCESS),
                    actual.processStatuses,
                    "PROCESS status for " + policies.get(index));
            assertEquals(
                    Arrays.asList(
                            CommitStatus.COMMITTED,
                            CommitStatus.COMMITTED),
                    actual.commitStatuses,
                    "commit status for " + policies.get(index));
            assertEquals(CommitStatus.ALREADY_COMMITTED,
                    actual.retryStatus,
                    "retry status for " + policies.get(index));
            assertTrue(actual.retryHasNoDuplicates,
                    "retry must not duplicate progress or outbox for "
                            + policies.get(index));
            assertEquals(2L, actual.finalEpoch,
                    "final epoch for " + policies.get(index));
            assertEquals(2, actual.epochReceiptCount,
                    "transition receipts for " + policies.get(index));
            assertTrue(actual.epochReceiptsBindRoots,
                    "transition receipts must bind resulting Roots for "
                            + policies.get(index));
            assertTrue(actual.finalRootMatchesProcess,
                    "the current Root must equal the second PROCESS result "
                            + "for " + policies.get(index));
            assertEquals(2, actual.terminalProgress.size(),
                    "terminal progress for " + policies.get(index));
            assertEquals(actual.expectedTerminalProgress,
                    actual.terminalProgress,
                    "terminal progress identity for "
                            + policies.get(index));
            assertEquals(actual.expectedRootOutbox, actual.rootOutbox,
                    "Root outbox for " + policies.get(index));
            assertEquals(0, actual.forbiddenReadCount,
                    "forbidden reads for " + policies.get(index));
            assertEquals(expected.finalRootBlueId,
                    actual.finalRootBlueId,
                    "semantic Root drift for " + policies.get(index));
            assertEquals(expected.totalGas, actual.totalGas,
                    "gas drift for " + policies.get(index));
            assertEquals(expected.expectedRootOutbox,
                    actual.expectedRootOutbox,
                    "Root-event drift for " + policies.get(index));
        }
    }

    @Test
    void shouldRetireAndReAddA211AsAFreshActivationInterval() {
        // given
        try (Harness harness = Harness.open(Representation.INLINE)) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "ten-by-ten-a211-intervals");
            harness.admit(sessionId);
            CoordinationSubscriptionOccurrence before =
                    occurrence(harness, sessionId, A211);
            CoordinationProcessingPlan addPlan = harness.plan(
                    sessionId,
                    20L,
                    rootEvent("add", 20L),
                    PrefetchPolicy.MINIMUM_BYTES);

            // when
            CoordinationTransition add = harness.engine.execute(addPlan);
            CommitOutcome addCommit = harness.engine.commit(add);
            CoordinationSubscriptionOccurrence firstActivation =
                    occurrence(harness, sessionId, A211);
            int occurrencesAfterAdd = occurrenceCount(harness, sessionId);

            CoordinationProcessingPlan removePlan = harness.plan(
                    sessionId,
                    30L,
                    rootEvent("remove", 30L),
                    PrefetchPolicy.MINIMUM_ROUND_TRIPS);
            CoordinationTransition remove =
                    harness.engine.execute(removePlan);
            CoordinationSubscriptionOccurrence retired = findOccurrence(
                    remove.subscriptionUpdate().retired(), A211);
            CommitOutcome removeCommit = harness.engine.commit(remove);
            CoordinationSubscriptionOccurrence afterRemoval =
                    occurrence(harness, sessionId, A211);
            int occurrencesAfterRemoval = occurrenceCount(harness, sessionId);

            CoordinationProcessingPlan readdPlan = harness.plan(
                    sessionId,
                    40L,
                    rootEvent("readd", 40L),
                    PrefetchPolicy.MINIMUM_BYTES);
            CoordinationTransition readd = harness.engine.execute(readdPlan);
            CommitOutcome readdCommit = harness.engine.commit(readd);
            CoordinationSubscriptionOccurrence secondActivation =
                    occurrence(harness, sessionId, A211);
            int occurrencesAfterReadd = occurrenceCount(harness, sessionId);

            CoordinationProcessingPlan leafPlan = harness.plan(
                    sessionId,
                    50L,
                    leafEvent("A211", 50L),
                    PrefetchPolicy.BALANCED);
            CoordinationTransition leaf = harness.engine.execute(leafPlan);
            CommitOutcome leafCommit = harness.engine.commit(leaf);
            int occurrencesAfterLeaf = occurrenceCount(harness, sessionId);
            ManagedDocumentSnapshot finalSession =
                    harness.engine.session(sessionId);

            // then
            assertNull(before);
            assertSelectedScope(addPlan, A2);
            assertSelectedScope(leafPlan, A211);
            assertSelectedScope(removePlan, A2);
            assertSelectedScope(readdPlan, A2);
            assertEquals(ProcessorStatus.SUCCESS, add.status());
            assertEquals(ProcessorStatus.SUCCESS, leaf.status());
            assertEquals(ProcessorStatus.SUCCESS, remove.status());
            assertEquals(ProcessorStatus.SUCCESS, readd.status());
            assertEquals(CommitStatus.COMMITTED, addCommit.status());
            assertEquals(CommitStatus.COMMITTED, leafCommit.status());
            assertEquals(CommitStatus.COMMITTED, removeCommit.status());
            assertEquals(CommitStatus.COMMITTED, readdCommit.status());
            assertNotNull(firstActivation);
            assertNotNull(retired);
            assertNull(afterRemoval);
            assertNotNull(secondActivation);
            assertEquals(104, occurrencesAfterAdd);
            assertEquals(103, occurrencesAfterRemoval);
            assertEquals(104, occurrencesAfterReadd);
            assertEquals(104, occurrencesAfterLeaf);
            assertEquals(firstActivation.occurrenceKey(),
                    retired.occurrenceKey());
            assertEquals(Long.valueOf(2L),
                    firstActivation.activationRootRevision());
            assertEquals(firstActivation.activationRootRevision(),
                    retired.activationRootRevision());
            assertEquals(Long.valueOf(3L),
                    retired.endAtRootRevision());
            assertEquals(firstActivation.occurrenceKey(),
                    secondActivation.occurrenceKey());
            assertEquals(Long.valueOf(4L),
                    secondActivation.activationRootRevision());
            assertTrue(secondActivation.activationRootRevision()
                            > firstActivation.activationRootRevision(),
                    "re-addition must open a fresh activation interval");
            assertEquals(order(20L, "event"),
                    firstActivation.activationFrontier());
            assertEquals(order(40L, "event"),
                    secondActivation.activationFrontier());
            assertEquals(4L, finalSession.currentEpoch());
            assertEquals(5L, finalSession.subscriptions().rootRevision());
            assertEquals(104,
                    finalSession.subscriptions().occurrences().size());
        }
    }

    @Test
    void shouldApplyPlatformCommitCompanionDeltaWithoutCollapsingSameScopeTimelines() {
        // given
        try (Harness harness = Harness.open(Representation.INLINE)) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "ten-by-ten-platform-companion-delta");
            harness.admit(sessionId);
            ManagedDocumentSnapshot before = harness.engine.session(
                    sessionId);
            CoordinationProcessingPlan plan = harness.plan(
                    sessionId,
                    20L,
                    rootEvent("add", 20L),
                    PrefetchPolicy.MINIMUM_BYTES);

            // when
            CoordinationTransition transition = harness.engine.execute(plan);
            SubscriptionDelta companion = transition.platformResult()
                    .commitCompanion()
                    .subscriptionDelta();
            CommitOutcome committed = harness.engine.commit(transition);
            ManagedDocumentSnapshot after = harness.engine.session(sessionId);
            blue.language.processor.ProcessorDiagnostic diagnostic =
                    transition.platformResult()
                            .processResult().diagnostic();

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    transition.status(),
                    diagnostic == null
                            ? "no diagnostic"
                            : diagnostic.category() + ": "
                            + diagnostic.message() + " "
                            + diagnostic.details());
            assertEquals(CommitStatus.COMMITTED, committed.status());
            Set<String> siblingChannels =
                    new LinkedHashSet<String>(Arrays.asList(
                            "add-control",
                            "remove-control",
                            "readd-control"));
            assertTrue(companion.removed().isEmpty());
            assertEquals(
                    Collections.singleton("timeline"),
                    channelKeysAtScope(companion.added(), A211));
            assertEquals(
                    siblingChannels,
                    channelKeysAtScope(
                            deltaEntries(
                                    before.subscriptions().occurrences()),
                            A2));
            assertEquals(
                    siblingChannels,
                    channelKeysAtScope(
                            deltaEntries(
                                    after.subscriptions().occurrences()),
                            A2));
            assertEquals(
                    siblingChannels,
                    channelKeysAtScope(
                            deltaEntries(
                                    transition.subscriptionUpdate()
                                            .unchanged()),
                            A2));
            assertEquals(
                    companion.removed(),
                    deltaEntries(transition.subscriptionUpdate().retired()));
            assertEquals(
                    companion.added(),
                    deltaEntries(transition.subscriptionUpdate().added()));
            assertEquals(
                    applyDelta(before, companion),
                    activeEntriesByKey(after));
            for (SubscriptionDelta.Entry added : companion.added()) {
                assertEquals(Long.valueOf(2L),
                        added.activationRootRevision());
                assertEquals(order(20L, "event"),
                        added.startAfterExternalOrderKey());
                assertNull(added.endAtRootRevision());
            }
        }
    }

    @Test
    void shouldKeepEqualTenByTenRootsIndependentAcrossSessions() {
        // given
        try (Harness harness = Harness.open(Representation.INLINE)) {
            DocumentSessionId firstSession = DocumentSessionId.of(
                    "ten-by-ten-session-a");
            DocumentSessionId secondSession = DocumentSessionId.of(
                    "ten-by-ten-session-b");
            harness.admit(firstSession);
            int fragmentsAfterFirst =
                    harness.fragmentStore.physicalFragmentCount();
            harness.admit(secondSession);
            int fragmentsAfterSecond =
                    harness.fragmentStore.physicalFragmentCount();

            CoordinationProcessingPlan firstPlan = harness.plan(
                    firstSession,
                    20L,
                    leafEvent("A25", 20L),
                    PrefetchPolicy.MINIMUM_ROUND_TRIPS);
            String originalRootBlueId = harness.engine.session(firstSession)
                    .currentRootBlueId();

            // when
            CoordinationTransition transition =
                    harness.engine.execute(firstPlan);
            CommitOutcome committed = harness.engine.commit(transition);

            // then
            assertEquals(ProcessorStatus.SUCCESS, transition.status());
            assertEquals(CommitStatus.COMMITTED, committed.status());
            assertEquals(fragmentsAfterFirst, fragmentsAfterSecond);
            assertEquals(1L,
                    harness.engine.session(firstSession).currentEpoch());
            assertEquals(0L,
                    harness.engine.session(secondSession).currentEpoch());
            assertFalse(originalRootBlueId.equals(
                    harness.engine.session(firstSession)
                            .currentRootBlueId()));
            assertEquals(originalRootBlueId,
                    harness.engine.session(secondSession)
                            .currentRootBlueId());
            assertEquals(2, harness.sessionStore.sessionCount());
            assertEquals(eventBlueIds(transition),
                    harness.sessionStore.rootOutbox(firstSession));
            assertEquals(Collections.emptyList(),
                    harness.sessionStore.rootOutbox(secondSession));
            assertEquals(Collections.singletonList(
                            firstPlan.eventReference().getBlueId()),
                    harness.sessionStore.terminalProgress(firstSession));
            assertEquals(Collections.emptyList(),
                    harness.sessionStore.terminalProgress(secondSession));
            assertEquals(transition.afterRootBlueId(),
                    harness.engine.epoch(firstSession, 1L).rootBlueId());
            assertEquals(originalRootBlueId,
                    harness.engine.epoch(secondSession, 0L).rootBlueId());
        }
    }

    @Test
    void shouldRejectAStaleTenByTenTransitionWithoutPartialWrites() {
        // given
        try (Harness harness = Harness.open(Representation.INLINE)) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "ten-by-ten-cas-conflict");
            harness.admit(sessionId);
            CoordinationProcessingPlan winnerPlan = harness.plan(
                    sessionId,
                    20L,
                    leafEvent("A25", 20L),
                    PrefetchPolicy.MINIMUM_BYTES);
            CoordinationProcessingPlan stalePlan = harness.plan(
                    sessionId,
                    21L,
                    leafEvent("A73", 21L),
                    PrefetchPolicy.BALANCED);
            CoordinationTransition winner =
                    harness.engine.execute(winnerPlan);
            CoordinationTransition stale = harness.engine.execute(stalePlan);

            // when
            CommitOutcome winningCommit = harness.engine.commit(winner);
            ManagedDocumentSnapshot afterWinner =
                    harness.engine.session(sessionId);
            List<String> outboxAfterWinner =
                    harness.sessionStore.rootOutbox(sessionId);
            List<String> progressAfterWinner =
                    harness.sessionStore.terminalProgress(sessionId);
            int fragmentsAfterWinner =
                    harness.fragmentStore.physicalFragmentCount();
            CommitOutcome staleCommit = harness.engine.commit(stale);

            // then
            assertEquals(ProcessorStatus.SUCCESS, winner.status());
            assertEquals(ProcessorStatus.SUCCESS, stale.status());
            assertEquals(CommitStatus.COMMITTED, winningCommit.status());
            assertEquals(CommitStatus.CONFLICT, staleCommit.status());
            assertEquals(afterWinner.currentEpoch(),
                    harness.engine.session(sessionId).currentEpoch());
            assertEquals(afterWinner.currentRootBlueId(),
                    harness.engine.session(sessionId).currentRootBlueId());
            assertEquals(afterWinner.fragmentInventoryIdentity(),
                    harness.engine.session(sessionId)
                            .fragmentInventoryIdentity());
            assertEquals(outboxAfterWinner,
                    harness.sessionStore.rootOutbox(sessionId));
            assertEquals(progressAfterWinner,
                    harness.sessionStore.terminalProgress(sessionId));
            assertEquals(fragmentsAfterWinner,
                    harness.fragmentStore.physicalFragmentCount());
            assertEquals(winner.afterRootBlueId(),
                    harness.engine.epoch(sessionId, 1L).rootBlueId());
            assertThrows(IllegalArgumentException.class,
                    () -> harness.engine.epoch(sessionId, 2L));
        }
    }

    @Test
    void shouldPreservePlanningAcrossRootEventRepresentationsAndPrefetch() {
        // given
        List<CampaignVariant> variants = Arrays.asList(
                new CampaignVariant(
                        Representation.INLINE, PrefetchPolicy.MINIMUM_BYTES),
                new CampaignVariant(
                        Representation.INLINE, PrefetchPolicy.BALANCED),
                new CampaignVariant(
                        Representation.INLINE,
                        PrefetchPolicy.MINIMUM_ROUND_TRIPS),
                new CampaignVariant(
                        Representation.PURE_REFERENCE,
                        PrefetchPolicy.MINIMUM_BYTES),
                new CampaignVariant(
                        Representation.PURE_REFERENCE,
                        PrefetchPolicy.BALANCED),
                new CampaignVariant(
                        Representation.PURE_REFERENCE,
                        PrefetchPolicy.MINIMUM_ROUND_TRIPS));
        List<PlanningProjection> observed =
                new ArrayList<PlanningProjection>();

        // when
        for (CampaignVariant variant : variants) {
            try (Harness harness = Harness.open(variant.representation)) {
                DocumentSessionId sessionId = DocumentSessionId.of(
                        "variant-" + observed.size());
                harness.admit(sessionId);
                CoordinationProcessingPlan plan = harness.plan(
                        sessionId,
                        20L,
                        leafEvent("A25", 20L),
                        variant.prefetchPolicy);
                observed.add(PlanningProjection.from(plan));
            }
        }

        // then
        PlanningProjection expected = observed.get(0);
        for (int index = 0; index < observed.size(); index++) {
            assertEquals(expected, observed.get(index),
                    "planning drift for " + variants.get(index));
        }
    }

    private static void assertSelectedScope(
            CoordinationProcessingPlan plan,
            String expectedScope) {
        assertTrue(selectedScope(plan, expectedScope),
                "missing selected scope " + expectedScope + " in "
                        + plan.preparedDelivery()
                                .selectedScopeChainIdentities().keySet());
    }

    private static boolean selectedScope(
            CoordinationProcessingPlan plan,
            String expectedScope) {
        return plan.preparedDelivery()
                .selectedScopeChainIdentities()
                .containsKey(expectedScope);
    }

    private static int occurrenceCount(
            Harness harness,
            DocumentSessionId sessionId) {
        return harness.engine.session(sessionId)
                .subscriptions().occurrences().size();
    }

    private static CoordinationSubscriptionOccurrence occurrence(
            Harness harness,
            DocumentSessionId sessionId,
            String scopePath) {
        return findOccurrence(
                harness.engine.session(sessionId)
                        .subscriptions().occurrences(),
                scopePath);
    }

    private static CoordinationSubscriptionOccurrence findOccurrence(
            Collection<CoordinationSubscriptionOccurrence> occurrences,
            String scopePath) {
        for (CoordinationSubscriptionOccurrence occurrence : occurrences) {
            if (scopePath.equals(occurrence.scopePath())
                    && "timeline".equals(occurrence.channelKey())) {
                return occurrence;
            }
        }
        return null;
    }

    private static List<SubscriptionDelta.Entry> deltaEntries(
            Collection<CoordinationSubscriptionOccurrence> occurrences) {
        List<SubscriptionDelta.Entry> result =
                new ArrayList<SubscriptionDelta.Entry>();
        for (CoordinationSubscriptionOccurrence occurrence : occurrences) {
            result.add(occurrence.toSubscriptionDeltaEntry());
        }
        return new SubscriptionDelta(
                result,
                Collections.<SubscriptionDelta.Entry>emptyList())
                .added();
    }

    private static Set<String> channelKeysAtScope(
            Collection<SubscriptionDelta.Entry> entries,
            String scopePath) {
        Set<String> result = new LinkedHashSet<String>();
        for (SubscriptionDelta.Entry entry : entries) {
            if (scopePath.equals(entry.scopePath())) {
                result.add(entry.channelKey());
            }
        }
        return result;
    }

    private static Map<String, SubscriptionDelta.Entry> applyDelta(
            ManagedDocumentSnapshot before,
            SubscriptionDelta delta) {
        Map<String, SubscriptionDelta.Entry> result =
                activeEntriesByKey(before);
        for (SubscriptionDelta.Entry removed : delta.removed()) {
            assertNotNull(result.remove(deltaKey(removed)));
        }
        for (SubscriptionDelta.Entry added : delta.added()) {
            assertNull(result.put(deltaKey(added), added));
        }
        return result;
    }

    private static Map<String, SubscriptionDelta.Entry> activeEntriesByKey(
            ManagedDocumentSnapshot snapshot) {
        Map<String, SubscriptionDelta.Entry> result =
                new LinkedHashMap<String, SubscriptionDelta.Entry>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.subscriptions().occurrences()) {
            SubscriptionDelta.Entry entry =
                    occurrence.toSubscriptionDeltaEntry();
            assertNull(result.put(deltaKey(entry), entry));
        }
        return result;
    }

    private static String deltaKey(SubscriptionDelta.Entry entry) {
        return entry.scopePath() + "\u0000" + entry.channelKey();
    }

    private static List<String> eventBlueIds(
            CoordinationTransition transition) {
        List<String> result = new ArrayList<String>();
        for (Node event : transition.platformResult()
                .processResult().events()) {
            result.add(DirectBlueIdCalculator.calculateBlueId(event));
        }
        return result;
    }

    private static List<String> concatenated(
            Collection<String> first,
            Collection<String> second) {
        List<String> result = new ArrayList<String>(first);
        result.addAll(second);
        return result;
    }

    private static Node authoredTenByTenRoot() {
        Map<String, Node> agreements = new LinkedHashMap<String, Node>();
        for (int agreement = 1; agreement <= 10; agreement++) {
            Map<String, Node> processes = new LinkedHashMap<String, Node>();
            for (int child = 1; child <= 10; child++) {
                String key = "A" + agreement + child;
                processes.put(key, leaf(key));
            }
            Map<String, Node> agreementContracts =
                    new LinkedHashMap<String, Node>();
            agreementContracts.put("embedded",
                    processEmbeddedCollections("/processes"));
            if (agreement == 2) {
                agreementContracts.putAll(a211LifecycleContracts());
            }
            agreements.put(
                    "A" + agreement,
                    new Node()
                            .name("Agreement A" + agreement)
                            .properties(
                                    "processes",
                                    new Node().properties(processes),
                                    "decoy",
                                    new Node().value(decoy(
                                            "agreement-" + agreement)))
                            .contracts(new Node().properties(
                                    agreementContracts)));
        }

        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put("embedded",
                processEmbeddedCollections("/agreements"));

        return new Node()
                .name("Storage-neutral ten by ten campaign Root")
                .properties(
                        "agreements", new Node().properties(agreements),
                        "rootCounter", new Node().value(0),
                        "largeUnselectedRootBranch",
                        new Node().value(decoy("root")))
                .contracts(new Node().properties(contracts));
    }

    private static Map<String, Node> a211LifecycleContracts() {
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put("add-control",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "root-add", "root-actor"));
        contracts.put("add-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "add-control",
                        RepositoryIndependentCoordinationTypes
                                .updateDocumentStep(
                                        "add",
                                        "/processes/A211",
                                        leaf("A211"))));
        contracts.put("remove-control",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "root-remove", "root-actor"));
        contracts.put("remove-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "remove-control",
                        removeStep("/processes/A211")));
        contracts.put("readd-control",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "root-readd", "root-actor"));
        contracts.put("readd-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "readd-control",
                        RepositoryIndependentCoordinationTypes
                                .updateDocumentStep(
                                        "add",
                                        "/processes/A211",
                                        leaf("A211"))));
        return contracts;
    }

    private static Node leaf(String key) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("timeline",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "timeline-" + key, "actor-" + key));
        if ("A25".equals(key)
                || "A73".equals(key)
                || "A211".equals(key)) {
            contracts.put("workflow",
                    RepositoryIndependentCoordinationTypes
                            .sequentialWorkflow(
                                    "timeline",
                                    RepositoryIndependentCoordinationTypes
                                            .updateDocumentStep(
                                                    "/counter",
                                                    new Node().value(1))));
        }
        return new Node()
                .name("Process " + key)
                .properties(
                        "counter", new Node().value(0),
                        "largeUnselectedBody",
                        new Node().value(decoy("body-" + key)))
                .contracts(new Node().properties(contracts));
    }

    private static Node processEmbeddedCollections(String... paths) {
        List<Node> collectionPaths = new ArrayList<Node>();
        for (String path : paths) {
            collectionPaths.add(new Node().value(path));
        }
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties("collectionPaths",
                        new Node().items(collectionPaths));
    }

    private static Node removeStep(String path) {
        return RepositoryIndependentCoordinationTypes.typed(
                        RepositoryIndependentCoordinationTypes
                                .UPDATE_DOCUMENT_BLUE_ID)
                .properties("changeset", new Node().items(
                        new Node()
                                .properties("op",
                                        new Node().value("remove"))
                                .properties("path",
                                        new Node().value(path))));
    }

    private static Node leafEvent(String key, long sequence) {
        return RepositoryIndependentCoordinationTypes.timelineEntry(
                "timeline-" + key,
                "actor-" + key,
                BigInteger.valueOf(sequence),
                RepositoryIndependentCoordinationTypes.chatMessage(
                        "invoke " + key));
    }

    private static Node rootEvent(String operation, long sequence) {
        return RepositoryIndependentCoordinationTypes.timelineEntry(
                "root-" + operation,
                "root-actor",
                BigInteger.valueOf(sequence),
                RepositoryIndependentCoordinationTypes.chatMessage(
                        operation + " A211"));
    }

    private static String decoy(String label) {
        StringBuilder value = new StringBuilder();
        while (value.length() < 128) {
            value.append(label).append('|');
        }
        return value.toString();
    }

    private static ExternalOrderKey order(long sequence, String label) {
        return ExternalOrderKey.of(Arrays.<Object>asList(sequence, label));
    }

    private enum Representation {
        INLINE,
        PURE_REFERENCE
    }

    private static final class CampaignVariant {
        private final Representation representation;
        private final PrefetchPolicy prefetchPolicy;

        private CampaignVariant(
                Representation representation,
                PrefetchPolicy prefetchPolicy) {
            this.representation = Objects.requireNonNull(
                    representation, "representation");
            this.prefetchPolicy = Objects.requireNonNull(
                    prefetchPolicy, "prefetchPolicy");
        }

        @Override
        public String toString() {
            return representation + "/" + prefetchPolicy;
        }
    }

    private static final class ConsecutiveExecution {
        private final DocumentAdmissionStatus admissionStatus;
        private final int initialOccurrenceCount;
        private final boolean initialA211Absent;
        private final boolean referencesOnly;
        private final boolean selectedA25;
        private final boolean selectedA73;
        private final boolean secondPlanUsesFirstInventory;
        private final List<ProcessorStatus> processStatuses;
        private final List<CommitStatus> commitStatuses;
        private final CommitStatus retryStatus;
        private final boolean retryHasNoDuplicates;
        private final long finalEpoch;
        private final int epochReceiptCount;
        private final boolean epochReceiptsBindRoots;
        private final boolean finalRootMatchesProcess;
        private final List<String> terminalProgress;
        private final List<String> expectedTerminalProgress;
        private final List<String> rootOutbox;
        private final List<String> expectedRootOutbox;
        private final int forbiddenReadCount;
        private final String finalRootBlueId;
        private final List<Long> totalGas;

        private ConsecutiveExecution(
                DocumentAdmissionStatus admissionStatus,
                int initialOccurrenceCount,
                boolean initialA211Absent,
                boolean referencesOnly,
                boolean selectedA25,
                boolean selectedA73,
                boolean secondPlanUsesFirstInventory,
                List<ProcessorStatus> processStatuses,
                List<CommitStatus> commitStatuses,
                CommitStatus retryStatus,
                boolean retryHasNoDuplicates,
                long finalEpoch,
                int epochReceiptCount,
                boolean epochReceiptsBindRoots,
                boolean finalRootMatchesProcess,
                List<String> terminalProgress,
                List<String> expectedTerminalProgress,
                List<String> rootOutbox,
                List<String> expectedRootOutbox,
                int forbiddenReadCount,
                String finalRootBlueId,
                List<Long> totalGas) {
            this.admissionStatus = admissionStatus;
            this.initialOccurrenceCount = initialOccurrenceCount;
            this.initialA211Absent = initialA211Absent;
            this.referencesOnly = referencesOnly;
            this.selectedA25 = selectedA25;
            this.selectedA73 = selectedA73;
            this.secondPlanUsesFirstInventory =
                    secondPlanUsesFirstInventory;
            this.processStatuses = processStatuses;
            this.commitStatuses = commitStatuses;
            this.retryStatus = retryStatus;
            this.retryHasNoDuplicates = retryHasNoDuplicates;
            this.finalEpoch = finalEpoch;
            this.epochReceiptCount = epochReceiptCount;
            this.epochReceiptsBindRoots = epochReceiptsBindRoots;
            this.finalRootMatchesProcess = finalRootMatchesProcess;
            this.terminalProgress = terminalProgress;
            this.expectedTerminalProgress = expectedTerminalProgress;
            this.rootOutbox = rootOutbox;
            this.expectedRootOutbox = expectedRootOutbox;
            this.forbiddenReadCount = forbiddenReadCount;
            this.finalRootBlueId = finalRootBlueId;
            this.totalGas = totalGas;
        }
    }

    private static final class PlanningProjection {
        private final String rootBlueId;
        private final String eventBlueId;
        private final String deliveryPlanIdentity;
        private final String subscriptionSnapshotIdentity;
        private final List<String> occurrenceOrder;
        private final Map<String, List<String>> selectedScopeChains;
        private final Set<String> requiredSeeds;

        private PlanningProjection(
                String rootBlueId,
                String eventBlueId,
                String deliveryPlanIdentity,
                String subscriptionSnapshotIdentity,
                List<String> occurrenceOrder,
                Map<String, List<String>> selectedScopeChains,
                Set<String> requiredSeeds) {
            this.rootBlueId = rootBlueId;
            this.eventBlueId = eventBlueId;
            this.deliveryPlanIdentity = deliveryPlanIdentity;
            this.subscriptionSnapshotIdentity =
                    subscriptionSnapshotIdentity;
            this.occurrenceOrder = occurrenceOrder;
            this.selectedScopeChains = selectedScopeChains;
            this.requiredSeeds = requiredSeeds;
        }

        private static PlanningProjection from(
                CoordinationProcessingPlan plan) {
            return new PlanningProjection(
                    plan.rootReference().getBlueId(),
                    plan.eventReference().getBlueId(),
                    plan.preparedDelivery().deliveryPlanIdentity(),
                    plan.preparedDelivery().subscriptionSnapshotIdentity(),
                    plan.preparedDelivery().preselectedOccurrenceOrder(),
                    plan.preparedDelivery().selectedScopeChainIdentities(),
                    plan.preparedDelivery()
                            .requiredSeedFragmentIdentities());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PlanningProjection)) {
                return false;
            }
            PlanningProjection value = (PlanningProjection) other;
            return rootBlueId.equals(value.rootBlueId)
                    && eventBlueId.equals(value.eventBlueId)
                    && deliveryPlanIdentity.equals(
                            value.deliveryPlanIdentity)
                    && subscriptionSnapshotIdentity.equals(
                            value.subscriptionSnapshotIdentity)
                    && occurrenceOrder.equals(value.occurrenceOrder)
                    && selectedScopeChains.equals(
                            value.selectedScopeChains)
                    && requiredSeeds.equals(value.requiredSeeds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    rootBlueId,
                    eventBlueId,
                    deliveryPlanIdentity,
                    subscriptionSnapshotIdentity,
                    occurrenceOrder,
                    selectedScopeChains,
                    requiredSeeds);
        }

        @Override
        public String toString() {
            return "PlanningProjection{" + deliveryPlanIdentity
                    + ", scopes=" + selectedScopeChains.keySet() + "}";
        }
    }

    private static final class Harness implements AutoCloseable {
        private final Representation representation;
        private final RepositoryIndependentCoordinationTestRuntime runtime;
        private final InMemoryCoordinationFragmentStore fragmentStore;
        private final InMemoryCoordinationSessionStore sessionStore;
        private final CoordinationProcessingEngine engine;
        private final Node exactRoot;
        private final Map<String, Node> externalExactNodes;

        private Harness(Representation representation) {
            this.representation = Objects.requireNonNull(
                    representation, "representation");
            runtime = RepositoryIndependentCoordinationTestRuntime.open();
            exactRoot = authoredTenByTenRoot();
            externalExactNodes = new LinkedHashMap<String, Node>();
            if (representation == Representation.PURE_REFERENCE) {
                retainExternal(exactRoot);
                retainExternal(leafEvent("A25", 20L));
                runtime.addNodeProvider(externalProvider(
                        externalExactNodes));
            }
            fragmentStore = new InMemoryCoordinationFragmentStore(
                    CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID);
            runtime.addNodeProvider(fragmentStore);
            sessionStore = new InMemoryCoordinationSessionStore();
            engine = CoordinationProcessingEngine.builder()
                    .contracts(runtime.contracts())
                    .documentProcessor(runtime.platformProcessor())
                    .fragmentStore(fragmentStore)
                    .sessionStore(sessionStore)
                    .bundleLoader(
                            new InMemoryCoordinationProcessingBundleLoader(
                                    fragmentStore,
                                    runtime.platformProcessor()
                                            .administration()
                                            .runtimeAccess()
                                            .languageRuntime()
                                            .getNodeProvider()))
                    .providerEvidenceDomain(
                            "test:ten-by-ten-engine-fragment-store")
                    .build();
        }

        private static Harness open(Representation representation) {
            return new Harness(representation);
        }

        private DocumentAdmissionResult admit(DocumentSessionId sessionId) {
            Node supplied = representation == Representation.PURE_REFERENCE
                    ? new Node().blueId(
                            DirectBlueIdCalculator.calculateBlueId(
                                    exactRoot))
                    : exactRoot.clone();
            return engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId, supplied, ACTIVATION_ORDER));
        }

        private Node suppliedEvent(Node exactEvent) {
            if (representation != Representation.PURE_REFERENCE) {
                return exactEvent;
            }
            String blueId = DirectBlueIdCalculator.calculateBlueId(
                    exactEvent);
            if (!externalExactNodes.containsKey(blueId)) {
                throw new IllegalStateException(
                        "Pure-reference event was not retained before the "
                                + "immutable runtime generation was built: "
                                + blueId);
            }
            return new Node().blueId(blueId);
        }

        private CoordinationProcessingPlan plan(
                DocumentSessionId sessionId,
                long sequence,
                Node exactEvent,
                PrefetchPolicy prefetchPolicy) {
            ProcessRequest request = new ProcessRequest(
                    sessionId,
                    engine.session(sessionId).currentEpoch(),
                    suppliedEvent(exactEvent),
                    order(sequence, "event"),
                    DeliveryPlanningMode.CURRENT_ROOT_COMPATIBILITY,
                    Collections.<String>emptyList(),
                    prefetchPolicy,
                    true);
            return engine.plan(request);
        }

        private ConsecutiveExecution executeConsecutiveLeaves(
                DocumentSessionId sessionId,
                PrefetchPolicy prefetchPolicy) {
            DocumentAdmissionResult admission = admit(sessionId);
            int initialOccurrences = occurrenceCount(this, sessionId);
            boolean a211Absent = occurrence(this, sessionId, A211) == null;

            CoordinationProcessingPlan firstPlan = plan(
                    sessionId,
                    20L,
                    leafEvent("A25", 20L),
                    prefetchPolicy);
            CoordinationTransition first = engine.execute(firstPlan);
            CommitOutcome firstCommit = engine.commit(first);
            List<String> outboxBeforeRetry =
                    sessionStore.rootOutbox(sessionId);
            List<String> progressBeforeRetry =
                    sessionStore.terminalProgress(sessionId);
            CommitOutcome retry = engine.commit(first);
            boolean retryHasNoDuplicates = outboxBeforeRetry.equals(
                    sessionStore.rootOutbox(sessionId))
                    && progressBeforeRetry.equals(
                            sessionStore.terminalProgress(sessionId));
            ManagedDocumentSnapshot afterFirst = engine.session(sessionId);

            CoordinationProcessingPlan secondPlan = plan(
                    sessionId,
                    30L,
                    leafEvent("A73", 30L),
                    prefetchPolicy);
            boolean secondUsesFirstInventory =
                    afterFirst.fragmentInventoryIdentity().equals(
                            secondPlan.rootInventory()
                                    .inventoryIdentity())
                            && afterFirst.currentRootBlueId().equals(
                                    secondPlan.rootReference().getBlueId());
            CoordinationTransition second = engine.execute(secondPlan);
            CommitOutcome secondCommit = engine.commit(second);
            ManagedDocumentSnapshot result = engine.session(sessionId);

            boolean epochReceiptsBindRoots =
                    first.afterRootBlueId().equals(
                            engine.epoch(sessionId, 1L).rootBlueId())
                            && second.afterRootBlueId().equals(
                                    engine.epoch(sessionId, 2L)
                                            .rootBlueId());
            boolean finalRootMatchesProcess =
                    DirectBlueIdCalculator.calculateBlueId(
                            second.platformResult()
                                    .processResult().document())
                            .equals(result.currentRootBlueId());
            List<String> expectedProgress = Arrays.asList(
                    firstPlan.eventReference().getBlueId(),
                    secondPlan.eventReference().getBlueId());
            List<String> expectedOutbox = concatenated(
                    eventBlueIds(first), eventBlueIds(second));

            return new ConsecutiveExecution(
                    admission.status(),
                    initialOccurrences,
                    a211Absent,
                    firstPlan.rootReference().isReferenceOnly()
                            && firstPlan.eventReference().isReferenceOnly()
                            && secondPlan.rootReference().isReferenceOnly()
                            && secondPlan.eventReference().isReferenceOnly(),
                    selectedScope(firstPlan, A25),
                    selectedScope(secondPlan, A73),
                    secondUsesFirstInventory,
                    Arrays.asList(first.status(), second.status()),
                    Arrays.asList(
                            firstCommit.status(), secondCommit.status()),
                    retry.status(),
                    retryHasNoDuplicates,
                    result.currentEpoch(),
                    2,
                    epochReceiptsBindRoots,
                    finalRootMatchesProcess,
                    sessionStore.terminalProgress(sessionId),
                    expectedProgress,
                    sessionStore.rootOutbox(sessionId),
                    expectedOutbox,
                    first.locality().forbiddenReadCount()
                            + second.locality().forbiddenReadCount(),
                    result.currentRootBlueId(),
                    Arrays.asList(
                            first.platformResult()
                                    .processResult().totalGas(),
                            second.platformResult()
                                    .processResult().totalGas()));
        }

        private void retainExternal(Node exact) {
            String blueId = DirectBlueIdCalculator.calculateBlueId(exact);
            externalExactNodes.put(blueId, exact.clone());
        }

        @Override
        public void close() {
            engine.close();
            runtime.close();
        }
    }

    private static NodeProvider externalProvider(
            Map<String, Node> exactNodes) {
        final Map<String, Node> retained =
                new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : exactNodes.entrySet()) {
            retained.put(entry.getKey(), entry.getValue().clone());
        }
        return blueId -> {
            Node exact = retained.get(blueId);
            return exact == null
                    ? null
                    : Collections.singletonList(exact.clone());
        };
    }
}
