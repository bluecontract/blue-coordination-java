package blue.language.processor;

import blue.coordination.processor.SequentialWorkflowProcessor;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.model.MarkerContract;
import blue.language.utils.BlueIdCalculator;
import blue.repo.coordination.SequentialWorkflow;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

/** Measures Coordination's declared-lineage gate and its direct-edge cache. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class DeclaredTypeEventMatcherBenchmark {
    private static final int FAN_OUT = 32;
    private static final int REPOSITORY_SCALE_EDGES = 2_212;

    @Param({"exact", "childWarm", "unrelatedPure", "unrelatedMaterialized", "untyped"})
    public String relation;

    private SequentialWorkflowProcessor processor;
    private SequentialWorkflow workflow;
    private HandlerMatchContext context;
    private CountingMapProvider provider;

    private Map<String, Node> definitions;
    private String expectedId;
    private String childId;
    private String grandchildId;
    private String siblingId;
    private String unrelatedId;

    private List<SequentialWorkflow> fanOutWorkflows;
    private HandlerMatchContext fanOutContext;
    private ContractMatchingService fanOutMatchingService;
    private CountingMapProvider fanOutProvider;

    private List<HandlerMatchContext> repositoryScaleContexts;
    private Node repositoryScaleExpected;
    private ContractMatchingService repositoryScaleMatchingService;
    private CountingMapProvider repositoryScaleProvider;

    @Setup(Level.Trial)
    public void setUp() {
        processor = new SequentialWorkflowProcessor();
        buildTypeGraph();
        setUpParameterizedPath();
        setUpFanOut();
        setUpRepositoryScale();
    }

    @Setup(Level.Iteration)
    public void verifyFixtures() {
        boolean expectedCoordination = !relation.startsWith("unrelated");
        if (processor.matches(workflow, context) != expectedCoordination) {
            throw new IllegalStateException("Unexpected Coordination result for " + relation);
        }
        if (!context.matchesEventPattern(workflow.getEvent())) {
            throw new IllegalStateException("Structural baseline changed for " + relation);
        }
        provider.resetLookupCount();
        if (processor.matches(workflow, context) != expectedCoordination
                || provider.lookupCount() != 0) {
            throw new IllegalStateException("Warm path performed provider work for " + relation);
        }
        provider.resetLookupCount();

        if (runFanOut(fanOutContext) != 24
                || fanOutProvider.lookupCount() != 0
                || fanOutMatchingService.declaredTypeLineageCacheSize() != 3) {
            throw new IllegalStateException("Warm fan-out fixture lost direct-edge reuse");
        }
        fanOutProvider.resetLookupCount();

        if (DeclaredTypeLineageMatcher.CACHE_INITIAL_CAPACITY > 64
                || repositoryScaleMatchingService.declaredTypeLineageCacheSize()
                != REPOSITORY_SCALE_EDGES + 1
                || repositoryScaleProvider.lookupCount() != 0) {
            throw new IllegalStateException("Repository-scale direct-edge fixture is invalid");
        }
    }

    @Benchmark
    public boolean coordinationDeclaredTypeFilter() {
        return processor.matches(workflow, context);
    }

    @Benchmark
    public boolean genericStructuralMatcherBaseline() {
        return context.matchesEventPattern(workflow.getEvent());
    }

    @Benchmark
    public boolean childCold() {
        CountingMapProvider coldProvider = new CountingMapProvider(definitions);
        ContractMatchingService coldMatching = new ContractMatchingService(new Blue(coldProvider));
        HandlerMatchContext coldContext = context(event(childId), coldMatching);
        return processor.matches(workflow(expectedId), coldContext);
    }

    @Benchmark
    public boolean providerRecovery() {
        MutableMapProvider recoveringProvider = new MutableMapProvider();
        ContractMatchingService recoveringMatching = new ContractMatchingService(
                new Blue(recoveringProvider));
        HandlerMatchContext recoveringContext = context(event(childId), recoveringMatching);
        SequentialWorkflow expected = workflow(expectedId);

        boolean unavailable = processor.matches(expected, recoveringContext);
        recoveringProvider.put(childId, definitions.get(childId));
        recoveringProvider.put(expectedId, definitions.get(expectedId));
        boolean recovered = processor.matches(expected, recoveringContext);
        return !unavailable && recovered;
    }

    @Benchmark
    public int fanOutCold() {
        CountingMapProvider coldProvider = new CountingMapProvider(definitions);
        ContractMatchingService coldMatching = new ContractMatchingService(new Blue(coldProvider));
        return runFanOut(context(event(grandchildId), coldMatching));
    }

    @Benchmark
    public int fanOutWarmSingleThread() {
        return runFanOut(fanOutContext);
    }

    @Benchmark
    @Threads(8)
    public int fanOutWarmEightThreads() {
        return runFanOut(fanOutContext);
    }

    @Benchmark
    public int repositoryScaleWarmLineage() {
        int matches = 0;
        for (HandlerMatchContext scaleContext : repositoryScaleContexts) {
            if (scaleContext.eventDeclaredTypeIsSameOrDescendantOf(repositoryScaleExpected)) {
                matches++;
            }
        }
        return matches;
    }

    private void buildTypeGraph() {
        Node expected = definition("Expected Event");
        expectedId = BlueIdCalculator.calculateBlueId(expected);
        Node child = definition("Child Event").type(reference(expectedId));
        childId = BlueIdCalculator.calculateBlueId(child);
        Node grandchild = definition("Grandchild Event").type(reference(childId));
        grandchildId = BlueIdCalculator.calculateBlueId(grandchild);
        Node common = definition("Common Event");
        String commonId = BlueIdCalculator.calculateBlueId(common);
        Node sibling = definition("Sibling Event").type(reference(commonId));
        siblingId = BlueIdCalculator.calculateBlueId(sibling);
        Node unrelated = definition("Unrelated Same Shape Event");
        unrelatedId = BlueIdCalculator.calculateBlueId(unrelated);

        definitions = new LinkedHashMap<String, Node>();
        definitions.put(expectedId, expected);
        definitions.put(childId, child);
        definitions.put(grandchildId, grandchild);
        definitions.put(commonId, common);
        definitions.put(siblingId, sibling);
        definitions.put(unrelatedId, unrelated);
    }

    private void setUpParameterizedPath() {
        provider = new CountingMapProvider(definitions);
        Blue blue = new Blue(provider);
        ContractMatchingService matchingService = new ContractMatchingService(blue);
        Node benchmarkEvent = eventForRelation(blue);
        workflow = workflow(expectedId);
        context = context(benchmarkEvent, matchingService);
    }

    private Node eventForRelation(Blue blue) {
        if ("exact".equals(relation)) {
            return event(expectedId);
        }
        if ("childWarm".equals(relation)) {
            return event(childId);
        }
        if ("unrelatedPure".equals(relation)) {
            return event(unrelatedId);
        }
        if ("unrelatedMaterialized".equals(relation)) {
            return blue.resolveToSnapshot(event(unrelatedId)).resolvedRoot();
        }
        return new Node().properties("kind", new Node().value("accepted"));
    }

    private void setUpFanOut() {
        fanOutProvider = new CountingMapProvider(definitions);
        fanOutMatchingService = new ContractMatchingService(new Blue(fanOutProvider));
        fanOutContext = context(event(grandchildId), fanOutMatchingService);
        fanOutWorkflows = new ArrayList<SequentialWorkflow>(FAN_OUT);
        for (int index = 0; index < FAN_OUT; index++) {
            fanOutWorkflows.add(workflow(fanOutExpectedType(index)));
        }
        if (runFanOut(fanOutContext) != 24) {
            throw new IllegalStateException("Fan-out fixture has unexpected handler count");
        }
        fanOutProvider.resetLookupCount();
    }

    private String fanOutExpectedType(int index) {
        switch (index % 4) {
            case 0:
                return grandchildId;
            case 1:
                return childId;
            case 2:
                return expectedId;
            default:
                return index % 8 == 3 ? siblingId : unrelatedId;
        }
    }

    private int runFanOut(HandlerMatchContext matchContext) {
        int matches = 0;
        for (SequentialWorkflow handler : fanOutWorkflows) {
            if (processor.matches(handler, matchContext)) {
                matches++;
            }
        }
        return matches;
    }

    private void setUpRepositoryScale() {
        Node root = new Node().name("Repository-scale root");
        String rootId = BlueIdCalculator.calculateBlueId(root);
        Map<String, Node> scaleDefinitions = new LinkedHashMap<String, Node>();
        scaleDefinitions.put(rootId, root);
        List<String> childIds = new ArrayList<String>(REPOSITORY_SCALE_EDGES);
        for (int index = 0; index < REPOSITORY_SCALE_EDGES; index++) {
            Node child = new Node()
                    .name("Repository-scale child " + index)
                    .type(reference(rootId));
            String childTypeId = BlueIdCalculator.calculateBlueId(child);
            scaleDefinitions.put(childTypeId, child);
            childIds.add(childTypeId);
        }

        repositoryScaleProvider = new CountingMapProvider(scaleDefinitions);
        repositoryScaleMatchingService = new ContractMatchingService(
                new Blue(repositoryScaleProvider));
        repositoryScaleExpected = reference(rootId);
        repositoryScaleContexts = new ArrayList<HandlerMatchContext>(REPOSITORY_SCALE_EDGES);
        for (String childTypeId : childIds) {
            HandlerMatchContext scaleContext = context(
                    new Node().type(reference(childTypeId)), repositoryScaleMatchingService);
            if (!scaleContext.eventDeclaredTypeIsSameOrDescendantOf(repositoryScaleExpected)) {
                throw new IllegalStateException("Repository-scale ancestry fixture failed");
            }
            repositoryScaleContexts.add(scaleContext);
        }
        if (repositoryScaleMatchingService.declaredTypeLineageCacheSize()
                != REPOSITORY_SCALE_EDGES + 1) {
            throw new IllegalStateException("Repository-scale cache evicted below its limit");
        }
        repositoryScaleProvider.resetLookupCount();
    }

    private static SequentialWorkflow workflow(String expectedTypeId) {
        SequentialWorkflow workflow = new SequentialWorkflow();
        workflow.setEvent(new Node().type(reference(expectedTypeId)));
        return workflow;
    }

    private static HandlerMatchContext context(Node event,
                                               ContractMatchingService matchingService) {
        return new HandlerMatchContext(
                "/",
                "handler",
                "channel",
                event,
                Collections.<String, MarkerContract>emptyMap(),
                matchingService);
    }

    private static Node event(String typeId) {
        return new Node()
                .type(reference(typeId))
                .properties("kind", new Node().value("accepted"));
    }

    private static Node definition(String name) {
        return new Node()
                .name(name)
                .properties("kind", new Node()
                        .type(reference(TEXT_TYPE_BLUE_ID))
                        .schema(new Schema().required(true)));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static class MapProvider implements NodeProvider {
        private final Map<String, Node> definitions;

        private MapProvider(Map<String, Node> definitions) {
            this.definitions = definitions;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node definition = definitions.get(blueId);
            return definition != null
                    ? Collections.singletonList(definition.clone())
                    : null;
        }
    }

    private static final class CountingMapProvider extends MapProvider {
        private final AtomicInteger lookupCount = new AtomicInteger();

        private CountingMapProvider(Map<String, Node> definitions) {
            super(definitions);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            lookupCount.incrementAndGet();
            return super.fetchByBlueId(blueId);
        }

        private int lookupCount() {
            return lookupCount.get();
        }

        private void resetLookupCount() {
            lookupCount.set(0);
        }
    }

    private static final class MutableMapProvider implements NodeProvider {
        private final Map<String, Node> definitions = new ConcurrentHashMap<String, Node>();

        private void put(String blueId, Node definition) {
            definitions.put(blueId, definition.clone());
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node definition = definitions.get(blueId);
            return definition != null
                    ? Collections.singletonList(definition.clone())
                    : null;
        }
    }
}
