package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TriggerEvent;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialWorkflowPlanCacheTest {
    @Test
    void exactEquivalentFrozenContractsReusePlanAndExecutorSelection() {
        FrozenNode firstContract = contract("Same", "Run");
        FrozenNode equivalentContract = contract("Same", "Run");
        AtomicInteger supportsCalls = new AtomicInteger();
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(supportsCalls));
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(8, 1024L * 1024L, null);
        AtomicInteger builds = new AtomicInteger();

        SequentialWorkflowPlan first = cache.getOrBuild(firstContract.resolvedStructuralKey(),
                planFactory(firstContract, executors, builds));
        SequentialWorkflowPlan reused = cache.getOrBuild(equivalentContract.resolvedStructuralKey(),
                planFactory(equivalentContract, executors, builds));

        assertSame(first, reused);
        assertEquals(1, builds.get());
        assertEquals(1, supportsCalls.get());
        assertEquals("Run", reused.step(0).key());
        assertTrue(reused.step(0).matches(new TriggerEvent()));
    }

    @Test
    void changedContractIdentityBuildsIndependentPlan() {
        FrozenNode firstContract = contract("First", "Run");
        FrozenNode changedContract = contract("Changed", "Run");
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(8, 1024L * 1024L, null);
        AtomicInteger builds = new AtomicInteger();

        SequentialWorkflowPlan first = cache.getOrBuild(firstContract.resolvedStructuralKey(),
                planFactory(firstContract, executors, builds));
        SequentialWorkflowPlan changed = cache.getOrBuild(changedContract.resolvedStructuralKey(),
                planFactory(changedContract, executors, builds));

        assertNotSame(first, changed);
        assertEquals(2, builds.get());
        assertEquals(2, cache.size());
    }

    @Test
    void entryBoundUsesAccessOrderAndEvictedPlanRebuilds() {
        FrozenNode firstContract = contract("First", "One");
        FrozenNode secondContract = contract("Second", "Two");
        FrozenNode thirdContract = contract("Third", "Three");
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(2, Long.MAX_VALUE, null);
        AtomicInteger builds = new AtomicInteger();

        SequentialWorkflowPlan first = cache.getOrBuild(firstContract.resolvedStructuralKey(),
                planFactory(firstContract, executors, builds));
        SequentialWorkflowPlan second = cache.getOrBuild(secondContract.resolvedStructuralKey(),
                planFactory(secondContract, executors, builds));
        assertSame(first, cache.getOrBuild(firstContract.resolvedStructuralKey(),
                planFactory(firstContract, executors, builds)));
        cache.getOrBuild(thirdContract.resolvedStructuralKey(),
                planFactory(thirdContract, executors, builds));
        SequentialWorkflowPlan rebuiltSecond = cache.getOrBuild(secondContract.resolvedStructuralKey(),
                planFactory(secondContract, executors, builds));

        assertNotSame(second, rebuiltSecond);
        assertEquals(4, builds.get());
        assertEquals(2, cache.size());
    }

    @Test
    void weightBoundEvictsAndOversizedPlansAreNotRetained() {
        FrozenNode firstContract = contract("First", "One");
        FrozenNode secondContract = contract("Second", "Two");
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlan firstPlan = buildPlan(firstContract, executors);
        SequentialWorkflowPlan secondPlan = buildPlan(secondContract, executors);
        long firstEntryWeight = firstPlan.approximateWeightBytes() + 64L;
        long secondEntryWeight = secondPlan.approximateWeightBytes() + 64L;
        long oneEntryLimit = Math.max(firstEntryWeight, secondEntryWeight);
        SequentialWorkflowPlanCache bounded = new SequentialWorkflowPlanCache(8, oneEntryLimit, null);

        bounded.getOrBuild(firstContract.resolvedStructuralKey(), () -> firstPlan);
        bounded.getOrBuild(secondContract.resolvedStructuralKey(), () -> secondPlan);

        assertEquals(1, bounded.size());
        assertTrue(bounded.weightBytes() <= oneEntryLimit);

        SequentialWorkflowPlanCache oversized = new SequentialWorkflowPlanCache(8,
                firstPlan.approximateWeightBytes(),
                null);
        AtomicInteger oversizedBuilds = new AtomicInteger();
        oversized.getOrBuild(firstContract.resolvedStructuralKey(),
                countingFactory(firstPlan, oversizedBuilds));
        oversized.getOrBuild(firstContract.resolvedStructuralKey(),
                countingFactory(firstPlan, oversizedBuilds));
        assertEquals(2, oversizedBuilds.get());
        assertEquals(0, oversized.size());
        assertEquals(0L, oversized.weightBytes());
    }

    @Test
    void clearAndCloseReleaseRetainedWeightAndPreventRepopulation() {
        FrozenNode contract = contract("Clear", "Run");
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(8, 1024L * 1024L, null);
        cache.getOrBuild(contract.resolvedStructuralKey(),
                planFactory(contract, executors, new AtomicInteger()));

        assertEquals(1, cache.size());
        assertTrue(cache.weightBytes() > 0L);
        cache.close();
        assertTrue(cache.isClosed());
        assertEquals(0, cache.size());
        assertEquals(0L, cache.weightBytes());

        AtomicInteger buildsAfterClose = new AtomicInteger();
        SequentialWorkflowPlan uncached = cache.getOrBuild(contract.resolvedStructuralKey(),
                planFactory(contract, executors, buildsAfterClose));
        assertEquals(contract.resolvedStructuralKey(), uncached.contractIdentity());
        assertEquals(1, buildsAfterClose.get());
        assertEquals(0, cache.size());
        assertEquals(0L, cache.weightBytes());
    }

    @Test
    void cachePublishesHitsMissesBuildsEvictionsLookupsAndCurrentWeight() {
        FrozenNode firstContract = contract("First metrics", "One");
        FrozenNode secondContract = contract("Second metrics", "Two");
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(1, 1024L * 1024L, metrics);

        cache.getOrBuild(firstContract.resolvedStructuralKey(),
                metricsPlanFactory(firstContract, executors, metrics));
        cache.getOrBuild(contract("First metrics", "One").resolvedStructuralKey(),
                metricsPlanFactory(firstContract, executors, metrics));
        cache.getOrBuild(secondContract.resolvedStructuralKey(),
                metricsPlanFactory(secondContract, executors, metrics));

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
    void concurrentMissBuildsOnlyOnce() throws Exception {
        FrozenNode contract = contract("Concurrent", "Run");
        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors =
                Collections.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>singletonList(
                        countingTriggerExecutor(new AtomicInteger()));
        SequentialWorkflowPlan expected = buildPlan(contract, executors);
        SequentialWorkflowPlanCache cache = new SequentialWorkflowPlanCache(8, 1024L * 1024L, null);
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            @SuppressWarnings("unchecked")
            Future<SequentialWorkflowPlan>[] futures = new Future[8];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = pool.submit(() -> {
                    start.await();
                    return cache.getOrBuild(contract.resolvedStructuralKey(),
                            countingFactory(expected, builds));
                });
            }
            start.countDown();
            for (Future<SequentialWorkflowPlan> future : futures) {
                assertSame(expected, future.get(10L, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, builds.get());
    }

    private static SequentialWorkflowPlanCache.PlanFactory planFactory(
            final FrozenNode contract,
            final List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
            final AtomicInteger builds) {
        return () -> {
            builds.incrementAndGet();
            return buildPlan(contract, executors);
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
            final FrozenNode contract,
            final List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
            final BexProcessingMetrics metrics) {
        return () -> SequentialWorkflowPlan.build(contract,
                Collections.<SequentialWorkflowStep>singletonList(new TriggerEvent()),
                executors,
                metrics);
    }

    private static SequentialWorkflowPlan buildPlan(
            FrozenNode contract,
            List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors) {
        return SequentialWorkflowPlan.build(contract,
                Collections.<SequentialWorkflowStep>singletonList(new TriggerEvent()),
                executors,
                null);
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
}
