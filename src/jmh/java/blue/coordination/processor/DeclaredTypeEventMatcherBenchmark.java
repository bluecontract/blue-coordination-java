package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguage;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

/**
 * Measures the closest public modular Language matching boundary for the
 * former Coordination declared-lineage fixture.
 *
 * <p>Language currently exposes structural type matching but not the
 * declared-lineage-only predicate previously reached by placing this
 * benchmark in {@code blue.language.processor}. Results from this benchmark
 * are therefore a structural control and must not be reported as the missing
 * Coordination gate. The fixture records that limitation explicitly and no
 * split-package access or local lineage implementation is installed.</p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class DeclaredTypeEventMatcherBenchmark {
    private static final int FAN_OUT = 32;
    private static final int REPOSITORY_SCALE_EDGES = 2_212;

    @Param({"exact", "childWarm", "unrelatedPure", "unrelatedMaterialized", "untyped"})
    public String relation;

    private Map<String, Node> definitions;
    private String expectedId;
    private String childId;
    private String grandchildId;
    private String siblingId;
    private String unrelatedId;

    private CountingMapProvider provider;
    private BlueLanguage language;
    private Node event;
    private Node expectedPattern;
    private Node structuralPattern;
    private boolean expectedPublicMatch;
    private boolean expectedStructuralMatch;

    private List<Node> fanOutPatterns;
    private CountingMapProvider fanOutProvider;
    private BlueLanguage fanOutLanguage;
    private Node fanOutEvent;
    private int expectedFanOutMatches;

    private List<Node> repositoryScaleEvents;
    private Node repositoryScalePattern;
    private CountingMapProvider repositoryScaleProvider;
    private BlueLanguage repositoryScaleLanguage;
    private int expectedRepositoryScaleMatches;

    @Setup(Level.Trial)
    public void setUp() {
        buildTypeGraph();
        setUpParameterizedPath();
        setUpFanOut();
        setUpRepositoryScale();
    }

    @Setup(Level.Iteration)
    public void verifyFixtures() {
        if (language.matching().matches(event, expectedPattern)
                != expectedPublicMatch) {
            throw new IllegalStateException(
                    "Public Language matching changed for " + relation);
        }
        if (language.matching().matches(event, structuralPattern)
                != expectedStructuralMatch) {
            throw new IllegalStateException(
                    "Structural baseline changed for " + relation);
        }
        provider.resetLookupCount();
        if (language.matching().matches(event, expectedPattern)
                != expectedPublicMatch) {
            throw new IllegalStateException(
                    "Warm public Language match changed for " + relation);
        }

        if (runFanOut(fanOutLanguage, fanOutEvent)
                != expectedFanOutMatches) {
            throw new IllegalStateException(
                    "Warm fan-out fixture has unexpected match count");
        }
        fanOutProvider.resetLookupCount();

        if (runRepositoryScale() != expectedRepositoryScaleMatches) {
            throw new IllegalStateException(
                    "Repository-scale public matching fixture is invalid");
        }
        repositoryScaleProvider.resetLookupCount();
    }

    @TearDown(Level.Trial)
    public void closeRuntimes() {
        System.out.println(
                "Declared-lineage benchmark uses public structural control "
                        + "only: relation=" + relation
                        + ", publicMatch=" + expectedPublicMatch
                        + ", fanOutMatches=" + expectedFanOutMatches
                        + ", repositoryScaleMatches="
                        + expectedRepositoryScaleMatches
                        + "; declared-lineage-only public API unavailable");
        repositoryScaleLanguage.close();
        fanOutLanguage.close();
        language.close();
    }

    @Benchmark
    public boolean coordinationDeclaredTypeFilter() {
        return language.matching().matches(event, expectedPattern);
    }

    @Benchmark
    public boolean genericStructuralMatcherBaseline() {
        return language.matching().matches(event, structuralPattern);
    }

    @Benchmark
    public boolean childCold() {
        CountingMapProvider coldProvider =
                new CountingMapProvider(definitions);
        try (BlueLanguage coldLanguage = language(coldProvider)) {
            return coldLanguage.matching().matches(
                    event(childId),
                    eventPattern(expectedId));
        }
    }

    @Benchmark
    public boolean providerRecovery() {
        MutableMapProvider recoveringProvider = new MutableMapProvider();
        try (BlueLanguage recoveringLanguage = language(recoveringProvider)) {
            boolean unavailable;
            try {
                unavailable = recoveringLanguage.matching().matches(
                        event(childId),
                        eventPattern(expectedId));
            } catch (IllegalArgumentException missingEvidence) {
                unavailable = false;
            }
            recoveringProvider.put(childId, definitions.get(childId));
            recoveringProvider.put(expectedId, definitions.get(expectedId));
            boolean recovered = recoveringLanguage.matching().matches(
                    event(childId),
                    eventPattern(expectedId));
            return !unavailable && recovered;
        }
    }

    @Benchmark
    public int fanOutCold() {
        CountingMapProvider coldProvider =
                new CountingMapProvider(definitions);
        try (BlueLanguage coldLanguage = language(coldProvider)) {
            return runFanOut(coldLanguage, event(grandchildId));
        }
    }

    @Benchmark
    public int fanOutWarmSingleThread() {
        return runFanOut(fanOutLanguage, fanOutEvent);
    }

    @Benchmark
    @Threads(8)
    public int fanOutWarmEightThreads() {
        return runFanOut(fanOutLanguage, fanOutEvent);
    }

    @Benchmark
    public int repositoryScaleWarmLineage() {
        return runRepositoryScale();
    }

    private void buildTypeGraph() {
        Node expected = definition("Expected Event");
        expectedId = directBlueId(expected);
        Node child = definition("Child Event").type(reference(expectedId));
        childId = directBlueId(child);
        Node grandchild = definition("Grandchild Event")
                .type(reference(childId));
        grandchildId = directBlueId(grandchild);
        Node common = definition("Common Event");
        String commonId = directBlueId(common);
        Node sibling = definition("Sibling Event")
                .type(reference(commonId));
        siblingId = directBlueId(sibling);
        Node unrelated = definition("Unrelated Same Shape Event");
        unrelatedId = directBlueId(unrelated);

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
        language = language(provider);
        event = eventForRelation();
        expectedPattern = eventPattern(expectedId);
        structuralPattern = new Node().properties(
                "kind", new Node().value("accepted"));
        language.matching().matches(event, expectedPattern);
        language.matching().matches(event, structuralPattern);
        expectedPublicMatch = language.matching().matches(
                event, expectedPattern);
        expectedStructuralMatch = language.matching().matches(
                event, structuralPattern);
        provider.resetLookupCount();
    }

    private Node eventForRelation() {
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
            return language.resolution().resolve(event(unrelatedId));
        }
        return new Node().properties(
                "kind", new Node().value("accepted"));
    }

    private void setUpFanOut() {
        fanOutProvider = new CountingMapProvider(definitions);
        fanOutLanguage = language(fanOutProvider);
        fanOutEvent = event(grandchildId);
        fanOutPatterns = new ArrayList<Node>(FAN_OUT);
        for (int index = 0; index < FAN_OUT; index++) {
            fanOutPatterns.add(eventPattern(fanOutExpectedType(index)));
        }
        expectedFanOutMatches = runFanOut(
                fanOutLanguage, fanOutEvent);
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

    private int runFanOut(
            BlueLanguage matchingLanguage,
            Node candidate) {
        int matches = 0;
        for (Node pattern : fanOutPatterns) {
            if (matchingLanguage.matching().matches(candidate, pattern)) {
                matches++;
            }
        }
        return matches;
    }

    private void setUpRepositoryScale() {
        Node root = new Node().name("Repository-scale root");
        String rootId = directBlueId(root);
        Map<String, Node> scaleDefinitions =
                new LinkedHashMap<String, Node>();
        scaleDefinitions.put(rootId, root);
        repositoryScaleEvents =
                new ArrayList<Node>(REPOSITORY_SCALE_EDGES);
        for (int index = 0; index < REPOSITORY_SCALE_EDGES; index++) {
            Node child = new Node()
                    .name("Repository-scale child " + index)
                    .type(reference(rootId));
            String childTypeId = directBlueId(child);
            scaleDefinitions.put(childTypeId, child);
            repositoryScaleEvents.add(event(childTypeId));
        }

        repositoryScaleProvider =
                new CountingMapProvider(scaleDefinitions);
        repositoryScaleLanguage = language(repositoryScaleProvider);
        repositoryScalePattern = eventPattern(rootId);
        expectedRepositoryScaleMatches = runRepositoryScale();
        repositoryScaleProvider.resetLookupCount();
    }

    private int runRepositoryScale() {
        int matches = 0;
        for (Node scaleEvent : repositoryScaleEvents) {
            if (repositoryScaleLanguage.matching().matches(
                    scaleEvent,
                    repositoryScalePattern)) {
                matches++;
            }
        }
        return matches;
    }

    private static Node eventPattern(String expectedTypeId) {
        return new Node().type(reference(expectedTypeId));
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

    private static String directBlueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static BlueLanguage language(NodeProvider provider) {
        return BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
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

        private void resetLookupCount() {
            lookupCount.set(0);
        }
    }

    private static final class MutableMapProvider implements NodeProvider {
        private final Map<String, Node> definitions =
                new ConcurrentHashMap<String, Node>();

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
