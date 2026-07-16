package blue.language.processor;

import blue.coordination.processor.SequentialWorkflowProcessor;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.model.MarkerContract;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeProviderWrapper;
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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

/** Compares the Coordination declared-type gate with its structural matcher baseline. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class DeclaredTypeEventMatcherBenchmark {
    @Param({"exact", "subtype", "unrelatedSameShape", "untyped"})
    public String relation;

    private SequentialWorkflowProcessor processor;
    private SequentialWorkflow workflow;
    private HandlerMatchContext context;

    @Setup(Level.Trial)
    public void setUp() {
        Node expected = definition("Expected Event");
        String expectedId = BlueIdCalculator.calculateBlueId(expected);
        Node child = definition("Child Event").type(reference(expectedId));
        Node unrelated = definition("Unrelated Same Shape Event");
        String childId = BlueIdCalculator.calculateBlueId(child);
        String unrelatedId = BlueIdCalculator.calculateBlueId(unrelated);
        Map<String, Node> definitions = new LinkedHashMap<String, Node>();
        definitions.put(expectedId, expected);
        definitions.put(childId, child);
        definitions.put(unrelatedId, unrelated);
        Blue blue = new Blue(NodeProviderWrapper.unverified(new MapProvider(definitions)));
        Node event = event(relation, expectedId, childId, unrelatedId);
        Node pattern = new Node().type(reference(expectedId));

        workflow = new SequentialWorkflow();
        workflow.setEvent(pattern);
        processor = new SequentialWorkflowProcessor();
        context = new HandlerMatchContext(
                "/",
                "handler",
                "channel",
                event,
                Collections.<String, MarkerContract>emptyMap(),
                new ContractMatchingService(blue));
    }

    @Setup(Level.Iteration)
    public void verifyFixture() {
        boolean expectedCoordination = !"unrelatedSameShape".equals(relation);
        if (processor.matches(workflow, context) != expectedCoordination) {
            throw new IllegalStateException("Unexpected Coordination result for " + relation);
        }
        if (!context.matchesEventPattern(workflow.getEvent())) {
            throw new IllegalStateException("Structural baseline changed for " + relation);
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

    private static Node event(String relation,
                              String expectedId,
                              String childId,
                              String unrelatedId) {
        Node event = new Node().properties("kind", new Node().value("accepted"));
        if ("exact".equals(relation)) {
            return event.type(reference(expectedId));
        }
        if ("subtype".equals(relation)) {
            return event.type(reference(childId));
        }
        if ("unrelatedSameShape".equals(relation)) {
            return event.type(reference(unrelatedId));
        }
        return event;
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

    private static final class MapProvider implements NodeProvider {
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
}
