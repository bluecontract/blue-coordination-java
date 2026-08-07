package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TriggerEvent;
import blue.repo.coordination.UpdateDocument;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialWorkflowPlanCacheTest {
    @Test
    void shouldPublishAndReuseExecutorSelectionOnlyAfterStepAdmission() {
        // given
        FrozenNode firstContract = contract("Same", "Run");
        FrozenNode equivalentContract = contract("Same", "Run");
        AtomicInteger supportsCalls = new AtomicInteger();
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(supportsCalls));
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(8, 1024L * 1024L, null);
        AtomicInteger builds = new AtomicInteger();

        // when
        SequentialWorkflowPlan first = cache.getOrBuild(firstContract.resolvedStructuralKey(),
                planFactory(firstContract, executors, builds));
        SequentialWorkflowPlan reused = cache.getOrBuild(equivalentContract.resolvedStructuralKey(),
                planFactory(equivalentContract, executors, builds));
        int supportsCallsBeforeAdmission = supportsCalls.get();
        SequentialWorkflowPlan.PlannedStep admitted =
                reused.planAdmittedStep(
                        new TriggerEvent(),
                        0,
                        executors,
                        null);
        cache.refreshWeight(reused);
        SequentialWorkflowPlan.PlannedStep warmed =
                reused.planAdmittedStep(
                        new TriggerEvent(),
                        0,
                        executors,
                        null);

        // then
        assertSame(first, reused);
        assertEquals(1, builds.get());
        assertEquals(0, supportsCallsBeforeAdmission);
        assertEquals(1, supportsCalls.get());
        assertTrue(admitted.published());
        assertFalse(warmed.published());
        assertSame(admitted.step(), warmed.step());
        assertEquals("Run", reused.step(0).key());
        assertTrue(reused.step(0).matches(new TriggerEvent()));
    }

    @Test
    void shouldIncreaseRetainedWeightOnlyWhenAdmittedStepIsPublished() {
        // given
        FrozenNode contract = contract("Lazy weight", "Run");
        AtomicInteger supportsCalls = new AtomicInteger();
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(supportsCalls));
        SequentialWorkflowPlanCache cache =
                new SequentialWorkflowPlanCache(
                        8,
                        1024L * 1024L,
                        null);
        SequentialWorkflowPlan plan =
                cache.getOrBuild(
                        contract.resolvedStructuralKey(),
                        planFactory(
                                contract,
                                executors,
                                new AtomicInteger()));
        long shellWeight = cache.weightBytes();

        // when
        SequentialWorkflowPlan.PlannedStep admitted =
                plan.planAdmittedStep(
                        new TriggerEvent(),
                        0,
                        executors,
                        null);
        cache.refreshWeight(plan);

        // then
        assertTrue(admitted.published());
        assertEquals(1, supportsCalls.get());
        assertTrue(cache.weightBytes() > shellWeight);
    }

    @Test
    void shouldNotReuseExactStepOrStaticChangesetAcrossIdentityEquivalentProcessRepresentations() {
        // given
        FrozenNode firstChangeset = changeset(7);
        FrozenNode secondChangeset = FrozenNode.fromNode(
                firstChangeset.toNode());
        FrozenNode firstExactStep = updateStep(firstChangeset);
        FrozenNode secondExactStep = FrozenNode.fromNode(
                firstExactStep.toNode());
        FrozenNode inlineSelection = firstExactStep;
        FrozenNode referenceSelection = FrozenNode.fromNode(
                new Node().blueId(firstExactStep.blueId()));
        AtomicInteger supportsCalls = new AtomicInteger();
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingUpdateExecutor(supportsCalls));
        SequentialWorkflowPlan plan = SequentialWorkflowPlan.build(
                contract("Representation", "Update"),
                Collections.<SequentialWorkflowStep>singletonList(
                        new UpdateDocument()));

        // when
        SequentialWorkflowPlan.PlannedStep first =
                plan.planAdmittedStep(
                        new UpdateDocument(),
                        firstExactStep,
                        inlineSelection,
                        0,
                        executors,
                        null,
                        () -> firstChangeset);
        SequentialWorkflowPlan.PlannedStep second =
                plan.planAdmittedStep(
                        new UpdateDocument(),
                        secondExactStep,
                        referenceSelection,
                        0,
                        executors,
                        null,
                        () -> secondChangeset);

        // then
        assertEquals(
                firstExactStep.blueId(),
                referenceSelection.getReferenceBlueId());
        assertEquals(
                firstExactStep.resolvedStructuralKey(),
                secondExactStep.resolvedStructuralKey());
        assertFalse(
                inlineSelection.resolvedStructuralKey().equals(
                        referenceSelection.resolvedStructuralKey()));
        assertTrue(first.published());
        assertFalse(second.published());
        assertNotSame(first.step(), second.step());
        assertNotSame(
                first.step().staticUpdatePlan(),
                second.step().staticUpdatePlan());
        assertSame(secondExactStep, second.exactStep());
        assertEquals(2, supportsCalls.get());
    }

    @Test
    void shouldSkipChangesetMaterializationOnExactRepresentationHit() {
        // given
        FrozenNode materializedChangeset = changeset(11);
        FrozenNode changesetReference = FrozenNode.fromNode(
                new Node().blueId(materializedChangeset.blueId()));
        FrozenNode firstExactStep = updateStep(changesetReference);
        FrozenNode secondExactStep = FrozenNode.fromNode(
                firstExactStep.toNode());
        AtomicInteger materializations = new AtomicInteger();
        AtomicInteger supportsCalls = new AtomicInteger();
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingUpdateExecutor(supportsCalls));
        SequentialWorkflowPlan plan = SequentialWorkflowPlan.build(
                contract("Lazy changeset", "Update"),
                Collections.<SequentialWorkflowStep>singletonList(
                        new UpdateDocument()));

        // when
        SequentialWorkflowPlan.PlannedStep first =
                plan.planAdmittedStep(
                        new UpdateDocument(),
                        firstExactStep,
                        firstExactStep,
                        0,
                        executors,
                        null,
                        () -> {
                            materializations.incrementAndGet();
                            return materializedChangeset;
                        });
        SequentialWorkflowPlan.PlannedStep warmed =
                plan.planAdmittedStep(
                        new UpdateDocument(),
                        secondExactStep,
                        secondExactStep,
                        0,
                        executors,
                        null,
                        () -> {
                            throw new AssertionError(
                                    "cache hit materialized referenced changeset");
                        });

        // then
        assertTrue(first.published());
        assertFalse(warmed.published());
        assertSame(first.step(), warmed.step());
        assertSame(secondExactStep, warmed.exactStep());
        assertEquals(1, materializations.get());
        assertEquals(1, supportsCalls.get());
    }

    @Test
    void shouldBuildIndependentPlanForChangedContractIdentity() {
        // given
        FrozenNode firstContract = contract("First", "Run");
        FrozenNode changedContract = contract("Changed", "Run");
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(8, 1024L * 1024L, null);
        AtomicInteger builds = new AtomicInteger();

        // when
        SequentialWorkflowPlan first = cache.getOrBuild(firstContract.resolvedStructuralKey(),
                planFactory(firstContract, executors, builds));
        SequentialWorkflowPlan changed = cache.getOrBuild(changedContract.resolvedStructuralKey(),
                planFactory(changedContract, executors, builds));

        // then
        assertNotSame(first, changed);
        assertEquals(2, builds.get());
        assertEquals(2, cache.size());
    }

    @Test
    void shouldUseAccessOrderForEntryBoundAndRebuildEvictedPlan() {
        // given
        FrozenNode firstContract = contract("First", "One");
        FrozenNode secondContract = contract("Second", "Two");
        FrozenNode thirdContract = contract("Third", "Three");
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(2, Long.MAX_VALUE, null);
        AtomicInteger builds = new AtomicInteger();

        // when
        SequentialWorkflowPlan first = cache.getOrBuild(firstContract.resolvedStructuralKey(),
                planFactory(firstContract, executors, builds));
        SequentialWorkflowPlan second = cache.getOrBuild(secondContract.resolvedStructuralKey(),
                planFactory(secondContract, executors, builds));
        SequentialWorkflowPlan touchedFirst =
                cache.getOrBuild(firstContract.resolvedStructuralKey(),
                        planFactory(firstContract, executors, builds));
        cache.getOrBuild(thirdContract.resolvedStructuralKey(),
                planFactory(thirdContract, executors, builds));
        SequentialWorkflowPlan rebuiltSecond = cache.getOrBuild(secondContract.resolvedStructuralKey(),
                planFactory(secondContract, executors, builds));

        // then
        assertSame(first, touchedFirst);
        assertNotSame(second, rebuiltSecond);
        assertEquals(4, builds.get());
        assertEquals(2, cache.size());
    }

    @Test
    void shouldEvictPlanWhenLiveWeightExceedsBound() {
        // given
        FrozenNode contract = contract("Live weight", "One");
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlan plan =
                buildPlan(contract);
        long shellEntryWeight =
                plan.approximateWeightBytes() + 64L;
        SequentialWorkflowPlanCache bounded =
                new SequentialWorkflowPlanCache(
                        8,
                        shellEntryWeight,
                        null);
        bounded.getOrBuild(
                contract.resolvedStructuralKey(),
                () -> plan);
        int sizeBeforeAdmission = bounded.size();

        // when
        plan.planAdmittedStep(
                new TriggerEvent(),
                0,
                executors,
                null);
        bounded.refreshWeight(plan);

        // then
        assertEquals(1, sizeBeforeAdmission);
        assertEquals(0, bounded.size());
        assertEquals(0L, bounded.weightBytes());
    }

    @Test
    void shouldNotRetainPlanThatExceedsWeightBound() {
        // given
        FrozenNode contract = contract("Oversized", "One");
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlan plan = buildPlan(contract);
        SequentialWorkflowPlanCache oversized = new SequentialWorkflowPlanCache(8,
                plan.approximateWeightBytes(),
                null);
        AtomicInteger builds = new AtomicInteger();

        // when
        oversized.getOrBuild(contract.resolvedStructuralKey(),
                countingFactory(plan, builds));
        oversized.getOrBuild(contract.resolvedStructuralKey(),
                countingFactory(plan, builds));

        // then
        assertEquals(2, builds.get());
        assertEquals(0, oversized.size());
        assertEquals(0L, oversized.weightBytes());
    }

    @Test
    void shouldEstimateRetainedStepWeightWithoutTraversingTriggerPayload() {
        // given
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        FrozenNode small = triggerContract(new Node().value("small"));
        Node largeEvent = new Node().value("leaf");
        for (int index = 0; index < 128; index++) {
            largeEvent = new Node().properties("nested", largeEvent);
        }
        FrozenNode large = triggerContract(largeEvent);

        // when
        SequentialWorkflowPlan smallPlan =
                buildPlan(small);
        SequentialWorkflowPlan largePlan =
                buildPlan(large);
        assertNull(smallPlan.step(0));
        assertNull(largePlan.step(0));
        smallPlan.planAdmittedStep(
                new TriggerEvent(),
                0,
                executors,
                null);
        largePlan.planAdmittedStep(
                new TriggerEvent(),
                0,
                executors,
                null);
        long smallWeight =
                smallPlan.approximateWeightBytes();
        long largeWeight =
                largePlan.approximateWeightBytes();

        // then
        assertEquals(
                smallWeight,
                largeWeight);
    }

    @Test
    void shouldCloseCacheReleaseRetainedWeightAndPreventRepopulation() {
        // given
        FrozenNode contract = contract("Clear", "Run");
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(8, 1024L * 1024L, null);
        cache.getOrBuild(contract.resolvedStructuralKey(),
                planFactory(contract, executors, new AtomicInteger()));
        long weightBeforeClose = cache.weightBytes();

        // when
        cache.close();
        AtomicInteger buildsAfterClose = new AtomicInteger();
        SequentialWorkflowPlan uncached = cache.getOrBuild(contract.resolvedStructuralKey(),
                planFactory(contract, executors, buildsAfterClose));

        // then
        assertTrue(weightBeforeClose > 0L);
        assertTrue(cache.isClosed());
        assertEquals(contract.resolvedStructuralKey(), uncached.contractIdentity());
        assertEquals(1, buildsAfterClose.get());
        assertEquals(0, cache.size());
        assertEquals(0L, cache.weightBytes());
    }

    @Test
    void shouldPublishCacheHitsMissesBuildsEvictionsLookupsAndCurrentWeight() {
        // given
        FrozenNode firstContract = contract("First metrics", "One");
        FrozenNode secondContract = contract("Second metrics", "Two");
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(1, 1024L * 1024L, metrics);

        // when
        SequentialWorkflowPlan first =
                cache.getOrBuild(
                        firstContract.resolvedStructuralKey(),
                        metricsPlanFactory(
                                firstContract));
        first.planAdmittedStep(
                new TriggerEvent(),
                0,
                executors,
                metrics);
        cache.refreshWeight(first);
        SequentialWorkflowPlan warmed =
                cache.getOrBuild(
                        contract("First metrics", "One")
                                .resolvedStructuralKey(),
                        metricsPlanFactory(
                                firstContract));
        warmed.planAdmittedStep(
                new TriggerEvent(),
                0,
                executors,
                metrics);
        SequentialWorkflowPlan second =
                cache.getOrBuild(
                        secondContract.resolvedStructuralKey(),
                        metricsPlanFactory(
                                secondContract));
        second.planAdmittedStep(
                new TriggerEvent(),
                0,
                executors,
                metrics);
        cache.refreshWeight(second);

        // then
        assertEquals(2L, metrics.workflowPlansBuilt());
        assertEquals(1L, metrics.workflowPlanCacheHits());
        assertEquals(2L, metrics.workflowPlanCacheMisses());
        assertEquals(1L, metrics.workflowPlanCacheEvictions());
        assertEquals(2L, metrics.workflowExecutorLookups());
        assertEquals(cache.weightBytes(), metrics.workflowPlanWeightBytes());
        cache.clear();
        assertEquals(0L, metrics.workflowPlanWeightBytes());
    }

    @Test
    void shouldBuildOnlyOnceForConcurrentMisses() throws Exception {
        // given
        FrozenNode contract = contract("Concurrent", "Run");
        AtomicInteger supportsCalls = new AtomicInteger();
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(supportsCalls));
        SequentialWorkflowPlan expected = buildPlan(contract);
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(8, 1024L * 1024L, null);
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);

        // when
        try {
            @SuppressWarnings("unchecked")
            Future<SequentialWorkflowPlan>[] futures = new Future[8];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = pool.submit(() -> {
                    start.await();
                    SequentialWorkflowPlan plan =
                            cache.getOrBuild(
                                    contract.resolvedStructuralKey(),
                                    countingFactory(
                                            expected,
                                            builds));
                    SequentialWorkflowPlan.PlannedStep step =
                            plan.planAdmittedStep(
                                    new TriggerEvent(),
                                    0,
                                    executors,
                                    null);
                    cache.refreshWeight(plan);
                    return step.step() != null
                            ? plan
                            : null;
                });
            }
            start.countDown();
            for (Future<SequentialWorkflowPlan> future : futures) {
                assertSame(expected, future.get(10L, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        // then
        assertEquals(1, builds.get());
        assertEquals(1, supportsCalls.get());
    }

    private static SequentialWorkflowPlanCache.PlanFactory planFactory(
            final FrozenNode contract,
            final List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
            final AtomicInteger builds) {
        return () -> {
            builds.incrementAndGet();
            return buildPlan(contract);
        };
    }

    private static SequentialWorkflowPlanCache.PlanFactory countingFactory(
            final SequentialWorkflowPlan plan,
            final AtomicInteger builds) {
        return () -> {
            builds.incrementAndGet();
            return plan;
        };
    }

    private static SequentialWorkflowPlanCache.PlanFactory metricsPlanFactory(
            final FrozenNode contract) {
        return () -> SequentialWorkflowPlan.build(contract,
                Collections.<SequentialWorkflowStep>singletonList(new TriggerEvent()));
    }

    private static SequentialWorkflowPlan buildPlan(
            FrozenNode contract) {
        return SequentialWorkflowPlan.build(contract,
                Collections.<SequentialWorkflowStep>singletonList(new TriggerEvent()));
    }

    private static WorkflowStepExecutor<TriggerEvent> countingTriggerExecutor(AtomicInteger supportsCalls) {
        return new WorkflowStepExecutor<TriggerEvent>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
                supportsCalls.incrementAndGet();
                return step instanceof TriggerEvent;
            }

            @Override
            public WorkflowStepResult execute(TriggerEvent step, StepExecutionContext context) {
                return WorkflowStepResult.none();
            }
        };
    }

    private static WorkflowStepExecutor<UpdateDocument>
    countingUpdateExecutor(AtomicInteger supportsCalls) {
        return new WorkflowStepExecutor<UpdateDocument>() {
            @Override
            public boolean supports(SequentialWorkflowStep step) {
                supportsCalls.incrementAndGet();
                return step instanceof UpdateDocument;
            }

            @Override
            public WorkflowStepResult execute(
                    UpdateDocument step,
                    StepExecutionContext context) {
                return WorkflowStepResult.none();
            }
        };
    }

    private static FrozenNode changeset(int value) {
        return FrozenNode.fromNode(
                new Node().items(
                        new Node()
                                .properties(
                                        "op",
                                        new Node().value(
                                                "replace"))
                                .properties(
                                        "path",
                                        new Node().value(
                                                "/counter"))
                                .properties(
                                        "val",
                                        new Node().value(
                                                value))));
    }

    private static FrozenNode updateStep(
            FrozenNode changeset) {
        return FrozenNode.fromNode(
                new Node()
                        .type(new Node().blueId(
                                UpdateDocument.blueId()))
                        .properties(
                                "changeset",
                                changeset.toNode()));
    }

    private static FrozenNode contract(String description, String stepName) {
        Node step = new Node()
                .name(stepName)
                .type("Coordination/Trigger Event")
                .properties("event", new Node().properties("value", new Node().value("event")));
        Node contract = new Node()
                .description(description)
                .properties("steps", new Node().items(step));
        return FrozenNode.fromResolvedNode(contract);
    }

    private static FrozenNode triggerContract(Node event) {
        Node step = new Node()
                .name("Run")
                .type("Coordination/Trigger Event")
                .properties("event", event);
        return FrozenNode.fromResolvedNode(
                new Node().properties("steps", new Node().items(step)));
    }
}
