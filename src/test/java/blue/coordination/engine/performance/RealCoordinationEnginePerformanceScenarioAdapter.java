package blue.coordination.engine.performance;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DeliveryPlanningMode;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.ProcessRequest;
import blue.coordination.engine.memory.InMemoryCoordinationFragmentStore;
import blue.coordination.engine.memory.InMemoryCoordinationProcessingBundleLoader;
import blue.coordination.engine.memory.InMemoryCoordinationSessionStore;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.CacheState;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.CellKey;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.ComparisonMode;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Metric;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Phase;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Sample;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.Scenario;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.ScenarioAdapter;
import blue.coordination.engine.performance.CoordinationEnginePerformanceHarness.SemanticFingerprint;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.coordination.processor.RepositoryIndependentCoordinationTestRuntime;
import blue.coordination.processor.RepositoryIndependentCoordinationTypes;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.ProcessingMetricId;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Real repository-independent adapter for the strict 9 x 3 x 2 receipt.
 *
 * <p>Every measured engine sample invokes the public engine and its public
 * Contracts PROCESS boundary. Indexed candidates are derived in an isolated
 * compatibility run before measurement. The inline control derives and uses
 * the public current-Root plan against exact inline Root/event values. A cold
 * cell has no prior PROCESS in its generation; a warm cell primes a distinct
 * session in the same immutable generation and fragment store.</p>
 */
final class RealCoordinationEnginePerformanceScenarioAdapter
        implements ScenarioAdapter {

    static final String CLASS_NAME =
            "blue.coordination.engine.performance."
                    + "RealCoordinationEnginePerformanceScenarioAdapter";

    private static final ExternalOrderKey ACTIVATION_ORDER =
            ExternalOrderKey.of(Arrays.<Object>asList(
                    10L, "performance-activation", 0L));
    private final Map<Scenario, List<List<String>>> indexedCandidates =
            new LinkedHashMap<Scenario, List<List<String>>>();

    @Override
    public void warmUp(CellKey cell, int iteration) throws Exception {
        execute(Objects.requireNonNull(cell, "cell"));
    }

    @Override
    public Sample measure(CellKey cell, int iteration) throws Exception {
        return execute(Objects.requireNonNull(cell, "cell"));
    }

    private Sample execute(CellKey cell) throws Exception {
        ScenarioDefinition definition = ScenarioDefinition.create(
                cell.scenario());
        if (cell.mode() == ComparisonMode.FULL_INLINE_CONTROL) {
            return executeInline(definition, cell.cache());
        }
        return executeEngine(definition, cell.mode(), cell.cache());
    }

    private Sample executeEngine(
            ScenarioDefinition definition,
            ComparisonMode mode,
            CacheState cacheState) throws Exception {
        List<List<String>> candidates = mode
                == ComparisonMode.FRAGMENT_NATIVE_INDEXED
                ? indexedCandidates(definition)
                : emptyCandidates(definition.events.size());
        CoordinationEnginePerformanceTimingObserver observer =
                new CoordinationEnginePerformanceTimingObserver();
        try (EngineEnvironment environment =
                     new EngineEnvironment(observer)) {
            Node initialized = environment.initialize(definition.root);
            if (cacheState == CacheState.WARM) {
                environment.admit(
                        DocumentSessionId.of(
                                "performance-prime-"
                                        + definition.scenario.id()),
                        initialized);
                environment.execute(
                        DocumentSessionId.of(
                                "performance-prime-"
                                        + definition.scenario.id()),
                        definition,
                        mode,
                        candidates,
                        AllocationProbe.unavailable());
                observer.reset();
            }
            DocumentSessionId measuredSession = DocumentSessionId.of(
                    "performance-measured-" + definition.scenario.id());
            environment.admit(measuredSession, initialized);
            long handlersBefore = environment.handlersExecuted();
            AllocationProbe allocation = AllocationProbe.start();
            SemanticRun run = environment.execute(
                    measuredSession,
                    definition,
                    mode,
                    candidates,
                    allocation);
            observer.recordAuthoritativeMetric(
                    Metric.SELECTED_BODY_COUNT,
                    environment.handlersExecuted() - handlersBefore);
            long allocationBytes = allocation.delta();
            if (allocationBytes >= 0L) {
                observer.recordAuthoritativeMetric(
                        Metric.ALLOCATION_BYTES,
                        allocationBytes);
            }
            return observer.sample(
                    definition.datasetSha256(initialized),
                    run.fingerprint());
        }
    }

    private Sample executeInline(
            ScenarioDefinition definition,
            CacheState cacheState) throws Exception {
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open()) {
            BexProcessingMetrics metrics = new BexProcessingMetrics();
            runtime.configure(CoordinationProcessorOptions.builder()
                    .processingMetrics(metrics)
                    .build());
            Node initialized = initialize(runtime, definition.root);
            if (cacheState == CacheState.WARM) {
                executeInlineSequence(
                        runtime,
                        initialized,
                        definition,
                        false);
            }
            long handlersBefore = handlersExecuted(metrics);
            AllocationProbe allocation = AllocationProbe.start();
            InlineExecution execution = executeInlineSequence(
                    runtime,
                    initialized,
                    definition,
                    true);
            long selectedBodyCount = handlersExecuted(metrics)
                    - handlersBefore;
            long allocationBytes = allocation.delta();
            Sample.Builder sample = Sample.builder(
                    definition.datasetSha256(initialized),
                    execution.run.fingerprint());
            sample.unavailable(
                    Phase.PLAN,
                    "full-inline-control-does-not-use-engine-plan");
            sample.unavailable(
                    Phase.BUNDLE_LOAD,
                    "full-inline-control-does-not-load-a-fragment-bundle");
            sample.phase(Phase.PROCESS, execution.processNanos);
            sample.unavailable(
                    Phase.FRAGMENT_TRANSITION,
                    "full-inline-control-does-not-transition-fragments");
            sample.unavailable(
                    Phase.COMMIT,
                    "full-inline-control-has-no-engine-session-commit");
            sample.phase(Phase.END_TO_END, execution.endToEndNanos);
            for (Metric metric : Metric.values()) {
                if (metric == Metric.SELECTED_BODY_COUNT) {
                    sample.metric(metric, selectedBodyCount);
                } else if (metric == Metric.ALLOCATION_BYTES
                        && allocationBytes >= 0L) {
                    sample.metric(metric, allocationBytes);
                } else {
                    sample.unavailable(metric, inlineMetricReason(metric));
                }
            }
            return sample.build();
        }
    }

    private synchronized List<List<String>> indexedCandidates(
            ScenarioDefinition definition) throws Exception {
        List<List<String>> retained = indexedCandidates.get(
                definition.scenario);
        if (retained != null) {
            return retained;
        }
        CoordinationEnginePerformanceTimingObserver ignored =
                new CoordinationEnginePerformanceTimingObserver();
        try (EngineEnvironment environment =
                     new EngineEnvironment(ignored)) {
            Node initialized = environment.initialize(definition.root);
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "performance-index-oracle-"
                            + definition.scenario.id());
            environment.admit(sessionId, initialized);
            List<List<String>> selected =
                    new ArrayList<List<String>>();
            for (ScenarioEvent event : definition.events) {
                ProcessRequest request = request(
                        environment.engine,
                        sessionId,
                        event,
                        DeliveryPlanningMode.CURRENT_ROOT_COMPATIBILITY,
                        Collections.<String>emptyList());
                CoordinationProcessingPlan plan =
                        environment.engine.plan(request);
                selected.add(Collections.unmodifiableList(
                        new ArrayList<String>(
                                plan.preparedDelivery()
                                        .preselectedOccurrenceOrder())));
                CoordinationTransition transition =
                        environment.engine.execute(plan);
                requireSuccessful(transition.platformResult()
                        .processResult());
                requireCommitted(environment.engine.commit(transition));
            }
            retained = immutableNested(selected);
            indexedCandidates.put(definition.scenario, retained);
            return retained;
        }
    }

    private static InlineExecution executeInlineSequence(
            RepositoryIndependentCoordinationTestRuntime runtime,
            Node initialized,
            ScenarioDefinition definition,
            boolean measure) {
        Node current = initialized.clone();
        long rootRevision = 1L;
        List<SubscriptionDelta.Entry> active = new ArrayList<
                SubscriptionDelta.Entry>(runtime
                .subscriptionSurfaceProjection()
                .projectInitial(
                        current,
                        rootRevision,
                        ACTIVATION_ORDER)
                .added());
        SemanticRun run = new SemanticRun();
        long processNanos = 0L;
        long endToEndNanos = 0L;
        for (ScenarioEvent event : definition.events) {
            long endToEndStarted = System.nanoTime();
            ExternalDeliveryPlan plan = runtime
                    .currentRootDeliveryPlanDeriver(
                            rootRevision,
                            event.order,
                            active)
                    .derive(current, event.event);
            NodeProvider invocationProvider = exactProvider(
                    runtime.nodeProvider(), current, event.event);
            PlatformProcessInvocation invocation =
                    PlatformProcessInvocation.builder()
                            .deliveryPlan(plan)
                            .nodeProvider(invocationProvider)
                            .build();
            long processStarted = System.nanoTime();
            PlatformProcessingResult platform = runtime.contracts()
                    .processForPlatformCommit(
                            current,
                            event.event,
                            invocation);
            long processElapsed = elapsed(processStarted);
            DocumentProcessingResult result = platform.processResult();
            requireSuccessful(result);
            SubscriptionDelta delta = platform.commitCompanion()
                    .subscriptionDelta();
            run.observe(event, result, delta);
            current = result.document();
            active = apply(active, delta);
            rootRevision++;
            if (measure) {
                processNanos = addExact(
                        processNanos,
                        processElapsed,
                        "inline PROCESS time");
                endToEndNanos = addExact(
                        endToEndNanos,
                        elapsed(endToEndStarted),
                        "inline end-to-end time");
            }
        }
        run.finish(current);
        return new InlineExecution(run, processNanos, endToEndNanos);
    }

    private static ProcessRequest request(
            CoordinationProcessingEngine engine,
            DocumentSessionId sessionId,
            ScenarioEvent event,
            DeliveryPlanningMode mode,
            List<String> candidates) {
        return new ProcessRequest(
                sessionId,
                engine.session(sessionId).currentEpoch(),
                event.event,
                event.order,
                mode,
                candidates,
                PrefetchPolicy.BALANCED,
                true);
    }

    private static List<SubscriptionDelta.Entry> apply(
            List<SubscriptionDelta.Entry> active,
            SubscriptionDelta delta) {
        Map<String, SubscriptionDelta.Entry> retained =
                new LinkedHashMap<String, SubscriptionDelta.Entry>();
        for (SubscriptionDelta.Entry entry : active) {
            retained.put(intervalKey(entry), entry);
        }
        for (SubscriptionDelta.Entry removed : delta.removed()) {
            retained.remove(intervalKey(removed));
        }
        for (SubscriptionDelta.Entry added : delta.added()) {
            retained.put(intervalKey(added), added);
        }
        return new ArrayList<SubscriptionDelta.Entry>(retained.values());
    }

    private static String intervalKey(SubscriptionDelta.Entry entry) {
        return entry.scopePath() + "\u0000" + entry.channelKey();
    }

    private static NodeProvider exactProvider(
            NodeProvider runtimeProvider,
            Node... roots) {
        Map<String, Node> exact = new LinkedHashMap<String, Node>();
        for (Node root : roots) {
            indexExact(root, exact);
        }
        NodeProvider supplied = blueId -> {
            Node found = exact.get(blueId);
            return found == null
                    ? null
                    : Collections.singletonList(found.clone());
        };
        return new SequentialNodeProvider(
                Arrays.asList(supplied, runtimeProvider));
    }

    private static void indexExact(Node node, Map<String, Node> exact) {
        if (node == null || node.isReferenceOnly()) {
            return;
        }
        String blueId = DirectBlueIdCalculator.calculateBlueId(node);
        if (!exact.containsKey(blueId)) {
            exact.put(blueId, node.clone());
        }
        indexExact(node.getType(), exact);
        indexExact(node.getItemType(), exact);
        indexExact(node.getKeyType(), exact);
        indexExact(node.getValueType(), exact);
        indexExact(node.getBlue(), exact);
        indexExact(node.getContracts(), exact);
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                indexExact(child, exact);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                indexExact(child, exact);
            }
        }
    }

    private static Node initialize(
            RepositoryIndependentCoordinationTestRuntime runtime,
            Node root) {
        DocumentProcessingResult initialized = runtime.initializeDocument(
                root.clone());
        requireSuccessful(initialized);
        return initialized.document();
    }

    private static void requireSuccessful(DocumentProcessingResult result) {
        if (result.status() != ProcessorStatus.SUCCESS) {
            throw new IllegalStateException(
                    "Performance scenario PROCESS failed: "
                            + result.status() + " "
                            + ProcessingResultTestSupport
                                    .diagnosticMessage(result));
        }
    }

    private static void requireCommitted(CommitOutcome outcome) {
        if (outcome.status() != CommitStatus.COMMITTED) {
            throw new IllegalStateException(
                    "Performance scenario commit failed: "
                            + outcome.status());
        }
    }

    private static String inlineMetricReason(Metric metric) {
        if (metric == Metric.MATERIALIZED_NODE_COUNT
                || metric == Metric.ALLOCATION_BYTES
                || metric == Metric.RETAINED_HEAP_BYTES) {
            return "authoritative-profiler-not-attached";
        }
        if (metric == Metric.SELECTED_BODY_COUNT) {
            return "authoritative-selected-body-counter-not-exposed";
        }
        return "full-inline-control-has-no-request-local-fragment-metric";
    }

    private static List<List<String>> emptyCandidates(int size) {
        List<List<String>> result = new ArrayList<List<String>>();
        for (int index = 0; index < size; index++) {
            result.add(Collections.<String>emptyList());
        }
        return result;
    }

    private static List<List<String>> immutableNested(
            List<List<String>> source) {
        List<List<String>> copy = new ArrayList<List<String>>();
        for (List<String> value : source) {
            copy.add(Collections.unmodifiableList(
                    new ArrayList<String>(value)));
        }
        return Collections.unmodifiableList(copy);
    }

    private static long elapsed(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }

    private static long handlersExecuted(BexProcessingMetrics metrics) {
        Long value = metrics.snapshot().languageCounters.get(
                ProcessingMetricId.HANDLERS_EXECUTED.externalName());
        return value == null ? 0L : value.longValue();
    }

    private static long addExact(long left, long right, String label) {
        if (Long.MAX_VALUE - left < right) {
            throw new IllegalStateException(label + " overflow");
        }
        return left + right;
    }

    private static final class AllocationProbe {
        private final com.sun.management.ThreadMXBean bean;
        private final long threadId;
        private final long before;
        private long after = -1L;

        private AllocationProbe(
                com.sun.management.ThreadMXBean bean,
                long threadId,
                long before) {
            this.bean = bean;
            this.threadId = threadId;
            this.before = before;
        }

        private static AllocationProbe start() {
            java.lang.management.ThreadMXBean candidate =
                    ManagementFactory.getThreadMXBean();
            if (!(candidate instanceof com.sun.management.ThreadMXBean)) {
                return unavailable();
            }
            com.sun.management.ThreadMXBean allocationBean =
                    (com.sun.management.ThreadMXBean) candidate;
            try {
                if (!allocationBean.isThreadAllocatedMemorySupported()) {
                    return unavailable();
                }
                if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                    allocationBean.setThreadAllocatedMemoryEnabled(true);
                }
                long threadId = Thread.currentThread().getId();
                long before = allocationBean.getThreadAllocatedBytes(threadId);
                return before < 0L
                        ? unavailable()
                        : new AllocationProbe(
                                allocationBean, threadId, before);
            } catch (RuntimeException unavailable) {
                return unavailable();
            }
        }

        private static AllocationProbe unavailable() {
            return new AllocationProbe(null, -1L, -1L);
        }

        private long delta() {
            if (bean == null) {
                return -1L;
            }
            long observedAfter = after >= 0L
                    ? after
                    : bean.getThreadAllocatedBytes(threadId);
            return observedAfter < before
                    ? -1L
                    : observedAfter - before;
        }

        private void stop() {
            if (bean != null && after < 0L) {
                after = bean.getThreadAllocatedBytes(threadId);
            }
        }
    }

    private static final class EngineEnvironment implements AutoCloseable {
        private final RepositoryIndependentCoordinationTestRuntime runtime;
        private final InMemoryCoordinationFragmentStore fragmentStore;
        private final CoordinationProcessingEngine engine;
        private final BexProcessingMetrics metrics;
        private final CoordinationEnginePerformanceTimingObserver observer;

        private EngineEnvironment(
                CoordinationEnginePerformanceTimingObserver observer) {
            this.observer = Objects.requireNonNull(observer, "observer");
            runtime = RepositoryIndependentCoordinationTestRuntime.open();
            fragmentStore = new InMemoryCoordinationFragmentStore(
                    CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID);
            runtime.addNodeProvider(fragmentStore);
            metrics = new BexProcessingMetrics();
            runtime.configure(CoordinationProcessorOptions.builder()
                    .processingMetrics(metrics)
                    .build());
            InMemoryCoordinationSessionStore sessionStore =
                    new InMemoryCoordinationSessionStore();
            engine = CoordinationProcessingEngine.builder()
                    .contracts(runtime.contracts())
                    .documentProcessor(runtime.platformProcessor())
                    .fragmentStore(fragmentStore)
                    .sessionStore(sessionStore)
                    .bundleLoader(
                            new InMemoryCoordinationProcessingBundleLoader(
                                    fragmentStore,
                                    runtime.nodeProvider()))
                    .observer(observer)
                    .providerEvidenceDomain(
                            "test:real-engine-performance-adapter")
                    .build();
        }

        private Node initialize(Node root) {
            return RealCoordinationEnginePerformanceScenarioAdapter
                    .initialize(runtime, root);
        }

        private void admit(DocumentSessionId sessionId, Node initialized) {
            DocumentAdmissionResult result = engine.addDocument(
                    DocumentRegistration.openOrCreate(
                            sessionId,
                            initialized,
                            ACTIVATION_ORDER));
            if (!result.succeeded()) {
                throw new IllegalStateException(
                        "Performance scenario admission failed: "
                                + result.status());
            }
        }

        private SemanticRun execute(
                DocumentSessionId sessionId,
                ScenarioDefinition definition,
                ComparisonMode mode,
                List<List<String>> candidates,
                AllocationProbe allocation) {
            long endToEndStarted = System.nanoTime();
            SemanticRun run = new SemanticRun();
            for (int index = 0;
                    index < definition.events.size();
                    index++) {
                ScenarioEvent event = definition.events.get(index);
                DeliveryPlanningMode planningMode = mode
                        == ComparisonMode.FRAGMENT_NATIVE_INDEXED
                        ? DeliveryPlanningMode.INDEXED
                        : DeliveryPlanningMode.CURRENT_ROOT_COMPATIBILITY;
                ProcessRequest request = request(
                        engine,
                        sessionId,
                        event,
                        planningMode,
                        candidates.get(index));
                CoordinationProcessingPlan plan = engine.plan(request);
                CoordinationTransition transition = engine.execute(plan);
                requireSuccessful(transition.platformResult()
                        .processResult());
                CommitOutcome outcome = engine.commit(transition);
                requireCommitted(outcome);
                run.observe(
                        event,
                        transition.platformResult().processResult(),
                        transition.platformResult()
                                .commitCompanion()
                                .subscriptionDelta());
            }
            long endToEndNanos = elapsed(endToEndStarted);
            allocation.stop();
            ManagedDocumentSnapshot session = engine.session(sessionId);
            CoordinationFragmentInventory inventory =
                    fragmentStore.requireInventory(
                            session.fragmentInventoryIdentity());
            run.finish(inventory.reconstruct(
                    fragmentStore.canonicalFragmentProvider()));
            observer.recordEndToEnd(endToEndNanos);
            return run;
        }

        private long handlersExecuted() {
            return RealCoordinationEnginePerformanceScenarioAdapter
                    .handlersExecuted(metrics);
        }

        @Override
        public void close() {
            engine.close();
            runtime.close();
        }
    }

    private static final class InlineExecution {
        private final SemanticRun run;
        private final long processNanos;
        private final long endToEndNanos;

        private InlineExecution(
                SemanticRun run,
                long processNanos,
                long endToEndNanos) {
            this.run = run;
            this.processNanos = processNanos;
            this.endToEndNanos = endToEndNanos;
        }
    }

    private static final class SemanticRun {
        private final List<String> rootEvents = new ArrayList<String>();
        private final List<String> namedTrace = new ArrayList<String>();
        private final List<String> subscriptionDeltas =
                new ArrayList<String>();
        private long gas;
        private String status;
        private Node finalRoot;

        private void observe(
                ScenarioEvent event,
                DocumentProcessingResult result,
                SubscriptionDelta delta) {
            status = result.status().wireValue();
            gas = addExact(gas, result.totalGas(), "semantic gas");
            List<String> emitted = nodeBlueIds(result.events());
            rootEvents.addAll(emitted);
            String rootBlueId = DirectBlueIdCalculator.calculateBlueId(
                    result.document());
            List<String> deltaProjection = deltaProjection(delta);
            subscriptionDeltas.addAll(deltaProjection);
            namedTrace.add(event.blueId + "|" + status
                    + "|" + result.totalGas()
                    + "|" + rootBlueId
                    + "|" + emitted
                    + "|" + deltaProjection);
        }

        private void finish(Node root) {
            finalRoot = Objects.requireNonNull(root, "root").clone();
        }

        private SemanticFingerprint fingerprint() {
            if (finalRoot == null || status == null) {
                throw new IllegalStateException(
                        "Performance semantic run is incomplete");
            }
            String rootBlueId = DirectBlueIdCalculator.calculateBlueId(
                    finalRoot);
            return new SemanticFingerprint(
                    status,
                    rootBlueId,
                    sha256(Collections.singletonList(rootBlueId)),
                    sha256(rootEvents),
                    gas,
                    sha256(namedTrace),
                    sha256(checkpointProjection(finalRoot)),
                    sha256(subscriptionDeltas));
        }
    }

    private static List<String> nodeBlueIds(Collection<Node> nodes) {
        List<String> result = new ArrayList<String>();
        for (Node node : nodes) {
            result.add(DirectBlueIdCalculator.calculateBlueId(node));
        }
        return result;
    }

    private static List<String> deltaProjection(SubscriptionDelta delta) {
        List<String> result = new ArrayList<String>();
        for (SubscriptionDelta.Entry entry : delta.added()) {
            result.add("added|" + intervalProjection(entry));
        }
        for (SubscriptionDelta.Entry entry : delta.removed()) {
            result.add("removed|" + intervalProjection(entry));
        }
        return result;
    }

    private static String intervalProjection(SubscriptionDelta.Entry entry) {
        return entry.scopePath()
                + "|" + entry.channelKey()
                + "|" + entry.effectiveTypeBlueId()
                + "|" + entry.sourceContributionNodeBlueIds()
                + "|" + entry.order()
                + "|" + entry.subscriptionKeys()
                + "|" + entry.checkpointDomainBlueId()
                + "|" + entry.dependencies()
                        .deterministicDependencyNodeBlueIds()
                + "|" + entry.activationRootRevision()
                + "|" + entry.startAfterExternalOrderKey()
                + "|" + entry.endAtRootRevision();
    }

    private static List<String> checkpointProjection(Node root) {
        Map<String, String> checkpoints = new TreeMap<String, String>();
        collectCheckpoints(root, "/", checkpoints);
        List<String> result = new ArrayList<String>();
        for (Map.Entry<String, String> entry : checkpoints.entrySet()) {
            result.add(entry.getKey() + "|" + entry.getValue());
        }
        return result;
    }

    private static void collectCheckpoints(
            Node node,
            String path,
            Map<String, String> checkpoints) {
        if (node == null || node.isReferenceOnly()) {
            return;
        }
        Node contracts = node.getContracts();
        if (contracts != null && contracts.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : new TreeMap<String, Node>(
                    contracts.getProperties()).entrySet()) {
                String contractPath = path + "contracts/" + entry.getKey();
                if ("checkpoint".equals(entry.getKey())) {
                    checkpoints.put(
                            contractPath,
                            DirectBlueIdCalculator.calculateBlueId(
                                    entry.getValue()));
                }
                collectCheckpoints(
                        entry.getValue(),
                        contractPath + "/",
                        checkpoints);
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : new TreeMap<String, Node>(
                    node.getProperties()).entrySet()) {
                collectCheckpoints(
                        entry.getValue(),
                        path + "properties/" + entry.getKey() + "/",
                        checkpoints);
            }
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                collectCheckpoints(
                        node.getItems().get(index),
                        path + "items/" + index + "/",
                        checkpoints);
            }
        }
    }

    private static String sha256(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNull(value, "value")
                        .getBytes(StandardCharsets.UTF_8);
                updateLong(digest, bytes.length);
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(
                        java.util.Locale.ROOT,
                        "%02x",
                        value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void updateLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static final class ScenarioDefinition {
        private final Scenario scenario;
        private final Node root;
        private final List<ScenarioEvent> events;

        private ScenarioDefinition(
                Scenario scenario,
                Node root,
                List<ScenarioEvent> events) {
            this.scenario = scenario;
            this.root = root;
            this.events = Collections.unmodifiableList(
                    new ArrayList<ScenarioEvent>(events));
        }

        private static ScenarioDefinition create(Scenario scenario) {
            Node root = authoredRoot();
            List<ScenarioEvent> events = new ArrayList<ScenarioEvent>();
            switch (scenario) {
                case SIMPLE_ROOT_EVENT:
                    events.add(event(scenario, 20L, 0,
                            timelineEvent(
                                    "root-simple", "root-actor", 20L,
                                    "simple")));
                    break;
                case SELECTED_DEPTH_TWO:
                    events.add(event(scenario, 20L, 0,
                            timelineEvent(
                                    "agreement-A2", "agreement-actor", 20L,
                                    "depth-two")));
                    break;
                case DEEP_A25_EVENT:
                    events.add(event(scenario, 20L, 0,
                            timelineEvent(
                                    "plain-A25", "actor-A25", 20L,
                                    "deep-A25")));
                    break;
                case COMPOSITE_CHANNEL_EVENT:
                    events.add(event(scenario, 20L, 0,
                            timelineEvent(
                                    "composite-A25", "actor-A25", 20L,
                                    "composite")));
                    break;
                case ALL_TIMELINES_CHANNEL_EVENT:
                    events.add(event(scenario, 20L, 0,
                            timelineEvent(
                                    "all-A25", "actor-A25", 20L,
                                    "all-timelines")));
                    break;
                case DOCUMENT_UPDATE_CASCADE:
                    events.add(event(scenario, 20L, 0,
                            timelineEvent(
                                    "document-cascade", "root-actor", 20L,
                                    "document-update")));
                    break;
                case TRIGGERED_EVENT_CASCADE:
                    events.add(event(scenario, 20L, 0,
                            timelineEvent(
                                    "trigger-cascade", "root-actor", 20L,
                                    "trigger")));
                    break;
                case COLLECTION_MEMBER_LIFECYCLE:
                    events.add(event(scenario, 20L, 0,
                            timelineEvent(
                                    "add-A211", "agreement-actor", 20L,
                                    "add")));
                    events.add(event(scenario, 30L, 1,
                            timelineEvent(
                                    "remove-A211", "agreement-actor", 30L,
                                    "remove")));
                    events.add(event(scenario, 40L, 2,
                            timelineEvent(
                                    "readd-A211", "agreement-actor", 40L,
                                    "readd")));
                    break;
                case TEN_CONSECUTIVE_DEEP_EVENTS:
                    for (int index = 0; index < 10; index++) {
                        long sequence = 20L + index;
                        events.add(event(scenario, sequence, index,
                                timelineEvent(
                                        "plain-A25",
                                        "actor-A25",
                                        sequence,
                                        "deep-" + index)));
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unknown performance scenario " + scenario);
            }
            return new ScenarioDefinition(scenario, root, events);
        }

        private String datasetSha256(Node initializedRoot) {
            List<String> identities = new ArrayList<String>();
            identities.add("dataset-v1");
            identities.add(scenario.id());
            identities.add(DirectBlueIdCalculator.calculateBlueId(
                    initializedRoot));
            for (ScenarioEvent event : events) {
                identities.add(event.blueId);
                identities.add(event.order.toString());
            }
            return sha256(identities);
        }
    }

    private static ScenarioEvent event(
            Scenario scenario,
            long sequence,
            int index,
            Node event) {
        return new ScenarioEvent(
                event,
                ExternalOrderKey.of(Arrays.<Object>asList(
                        sequence,
                        scenario.id(),
                        (long) index)));
    }

    private static final class ScenarioEvent {
        private final Node event;
        private final ExternalOrderKey order;
        private final String blueId;

        private ScenarioEvent(Node event, ExternalOrderKey order) {
            this.event = Objects.requireNonNull(event, "event").clone();
            this.order = Objects.requireNonNull(order, "order");
            this.blueId = DirectBlueIdCalculator.calculateBlueId(event);
        }
    }

    private static Node authoredRoot() {
        Node triggered = RepositoryIndependentCoordinationTypes.chatMessage(
                "triggered-cascade-event");
        Map<String, Node> rootContracts =
                new LinkedHashMap<String, Node>();
        rootContracts.put("embedded",
                processEmbeddedCollections("/agreements"));
        rootContracts.put("simple",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "root-simple", "root-actor"));
        rootContracts.put("simple-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "simple",
                        replace("/rootCounter", 1)));
        rootContracts.put("document-source",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "document-cascade", "root-actor"));
        rootContracts.put("document-seed",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "document-source",
                        replace("/documentValue", 1)));
        rootContracts.put("document-updates",
                typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL)
                        .properties("path", scalar("/documentValue")));
        rootContracts.put("document-reaction",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "document-updates",
                        replace("/updateAudit", 1)));
        rootContracts.put("trigger-source",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "trigger-cascade", "root-actor"));
        rootContracts.put("trigger-seed",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "trigger-source",
                        RepositoryIndependentCoordinationTypes
                                .triggerEventStep(triggered)));
        rootContracts.put("triggered",
                typed(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)
                        .properties("event", triggered.clone()));
        rootContracts.put("trigger-reaction",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "triggered",
                        replace("/triggeredCounter", 1)));

        Map<String, Node> agreementContracts =
                new LinkedHashMap<String, Node>();
        agreementContracts.put("embedded",
                processEmbeddedCollections("/processes"));
        agreementContracts.put("agreement",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "agreement-A2", "agreement-actor"));
        agreementContracts.put("agreement-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "agreement",
                        replace("/agreementCounter", 1)));
        agreementContracts.put("add-control",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "add-A211", "agreement-actor"));
        agreementContracts.put("add-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "add-control",
                        RepositoryIndependentCoordinationTypes
                                .updateDocumentStep(
                                        "add", "/processes/A211",
                                        leaf("A211"))));
        agreementContracts.put("remove-control",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "remove-A211", "agreement-actor"));
        agreementContracts.put("remove-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "remove-control",
                        remove("/processes/A211")));
        agreementContracts.put("readd-control",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "readd-A211", "agreement-actor"));
        agreementContracts.put("readd-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "readd-control",
                        RepositoryIndependentCoordinationTypes
                                .updateDocumentStep(
                                        "add", "/processes/A211",
                                        leaf("A211"))));

        Node agreement = new Node()
                .name("Agreement A2")
                .properties(
                        "agreementCounter", scalar(0),
                        "processes", new Node().properties(
                                "A25", leaf("A25")))
                .contracts(new Node().properties(agreementContracts));
        Map<String, Node> rootProperties =
                new LinkedHashMap<String, Node>();
        rootProperties.put("rootCounter", scalar(0));
        rootProperties.put("triggeredCounter", scalar(0));
        rootProperties.put("documentValue", scalar(0));
        rootProperties.put("updateAudit", scalar(0));
        rootProperties.put("agreements", new Node().properties(
                "A2", agreement));
        return new Node()
                .name("Coordination engine performance Root")
                .properties(rootProperties)
                .contracts(new Node().properties(rootContracts));
    }

    private static Node leaf(String key) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("plain",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "plain-" + key, "actor-" + key));
        contracts.put("plain-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "plain", replace("/counter", 1)));
        contracts.put("composite-child",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "composite-" + key, "actor-" + key));
        contracts.put("composite",
                typed(RepositoryIndependentCoordinationTypes
                        .COMPOSITE_TIMELINE_CHANNEL_BLUE_ID)
                        .properties("channels", new Node().items(
                                scalar("composite-child"))));
        contracts.put("composite-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "composite", replace("/compositeCounter", 1)));
        contracts.put("all-child",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "all-" + key, "actor-" + key));
        contracts.put("all",
                typed(RepositoryIndependentCoordinationTypes
                        .ALL_TIMELINES_CHANNEL_BLUE_ID));
        contracts.put("all-workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "all", replace("/allCounter", 1)));
        return new Node()
                .name("Process " + key)
                .properties(
                        "counter", scalar(0),
                        "compositeCounter", scalar(0),
                        "allCounter", scalar(0))
                .contracts(new Node().properties(contracts));
    }

    private static Node processEmbeddedCollections(String... paths) {
        List<Node> collectionPaths = new ArrayList<Node>();
        for (String path : paths) {
            collectionPaths.add(scalar(path));
        }
        return typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                .properties("collectionPaths",
                        new Node().items(collectionPaths));
    }

    private static Node timelineEvent(
            String timeline,
            String actor,
            long timestamp,
            String message) {
        return RepositoryIndependentCoordinationTypes.timelineEntry(
                timeline,
                actor,
                BigInteger.valueOf(timestamp),
                RepositoryIndependentCoordinationTypes.chatMessage(message));
    }

    private static Node replace(String path, Object value) {
        return RepositoryIndependentCoordinationTypes.updateDocumentStep(
                "replace", path, scalar(value));
    }

    private static Node remove(String path) {
        return typed(RepositoryIndependentCoordinationTypes
                .UPDATE_DOCUMENT_BLUE_ID)
                .properties("changeset", new Node().items(
                        new Node()
                                .properties("op", scalar("remove"))
                                .properties("path", scalar(path))));
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static Node scalar(Object value) {
        return new Node().value(value);
    }
}
