package blue.coordination.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.UncheckedObjectMapper;
import blue.repo.BlueRepository;
import blue.repo.coordination.PrincipalActor;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import org.openjdk.jmh.annotations.AuxCounters;
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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Measures complete subscription projection and exact sparse indexed
 * planning over the release scale points.
 *
 * <p>Exactly one Timeline Channel matches at every scale. The planner still
 * validates the complete persisted subscription surface, while its exact
 * provider is limited to the Root and Event identities. Auxiliary counters
 * retain the logical fixture sizes beside JMH's elapsed-time and allocation
 * distributions.</p>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SubscriptionProjectionPlanningBenchmark {
    private static final long ROOT_REVISION = 17L;
    private static final String SELECTED_CHANNEL = "selected";
    private static final String SELECTED_TIMELINE = "selected-timeline";

    @Param({"10", "100", "1000", "10000"})
    public int channelCount;

    private Blue blue;
    private Node root;
    private String rootBlueId;
    private Node event;
    private String eventBlueId;
    private ExternalOrderKey eventOrder;
    private CoordinationSubscriptionProjector projector;
    private CoordinationIndexedDeliveryPlanner planner;
    private CoordinationSubscriptionSnapshot expectedSnapshot;
    private List<String> candidates;
    private CountingExactProvider exactProvider;
    private long rootBytes;
    private long snapshotBytes;
    private long expectedProviderDemandCount;
    private long expectedProviderDemandBytes;
    private String expectedProjectionDigest;
    private String expectedPlanIdentity;
    private String lastProjectionDigest;
    private String lastPlanIdentity;
    private long lastProviderDemandCount;
    private long lastProviderDemandBytes;

    @Setup(Level.Trial)
    public void setUpTrial() {
        BlueRepository repository = BlueRepository.latest();
        blue = repository.configure(new Blue());
        CoordinationProcessors.registerWith(
                blue,
                CoordinationProcessorOptions.builder().build());

        Node exact = blue.preprocess(
                document(repository, channelCount));
        DocumentProcessingResult initialized =
                blue.initializeDocument(exact);
        requireSuccess(initialized, "benchmark initialization");
        root = initialized.document();
        rootBlueId = BlueIdCalculator.calculateBlueId(root);
        rootBytes = encodedBytes(root);

        projector = CoordinationDeliveryPlanning
                .subscriptionProjector(
                        blue.getDocumentProcessor());
        expectedSnapshot = projector.projectCurrent(
                root,
                ROOT_REVISION,
                ExternalOrderKey.of(
                        Collections.emptyList()));
        if (expectedSnapshot.occurrences().size()
                != channelCount) {
            throw new IllegalStateException(
                    "Expected "
                            + channelCount
                            + " projected Channels but observed "
                            + expectedSnapshot.occurrences().size());
        }
        expectedProjectionDigest =
                expectedSnapshot.digest();
        snapshotBytes =
                UncheckedObjectMapper.JSON_MAPPER
                        .writeValueAsString(
                                expectedSnapshot.toMap())
                        .getBytes(
                                StandardCharsets.UTF_8)
                        .length;

        event = timelineEntry(
                blue,
                repository,
                SELECTED_TIMELINE,
                23);
        eventBlueId =
                BlueIdCalculator.calculateBlueId(event);
        eventOrder = eventOrder(event);
        candidates = selectedCandidate(
                expectedSnapshot);
        planner = CoordinationDeliveryPlanning.indexed(
                blue.getDocumentProcessor());

        Map<String, Node> exactNodes =
                new LinkedHashMap<String, Node>();
        exactNodes.put(rootBlueId, root);
        exactNodes.put(eventBlueId, event);
        exactProvider =
                new CountingExactProvider(
                        blue,
                        exactNodes);
        CoordinationPreparedDelivery warm =
                planner.prepare(
                        rootBlueId,
                        eventBlueId,
                        expectedSnapshot,
                        candidates,
                        exactProvider,
                        ROOT_REVISION,
                        eventOrder);
        expectedPlanIdentity =
                warm.deliveryPlanIdentity();
        expectedProviderDemandCount =
                exactProvider.demandCount();
        expectedProviderDemandBytes =
                exactProvider.returnedBytes();
        exactProvider.reset();
    }

    @Setup(Level.Iteration)
    public void setUpIteration() {
        lastProjectionDigest = null;
        lastPlanIdentity = null;
        lastProviderDemandCount = 0L;
        lastProviderDemandBytes = 0L;
    }

    /**
     * Measures one complete initial projection of the current exact Root.
     *
     * @return immutable identity-bearing subscription snapshot
     */
    @Benchmark
    public CoordinationSubscriptionSnapshot projectCurrent(
            EvidenceCounters evidence) {
        CoordinationSubscriptionSnapshot projected =
                projector.projectCurrent(
                        root,
                        ROOT_REVISION,
                        ExternalOrderKey.of(
                                Collections.emptyList()));
        lastProjectionDigest = projected.digest();
        evidence.snapshotOccurrences +=
                expectedSnapshot.occurrences().size();
        evidence.snapshotBytes += snapshotBytes;
        return projected;
    }

    /**
     * Measures exact event planning where one indexed candidate matches a
     * much larger active subscription surface.
     *
     * @return verified Root/event-bound delivery preparation
     */
    @Benchmark
    public CoordinationPreparedDelivery planSparseIndexedEvent(
            EvidenceCounters evidence) {
        exactProvider.reset();
        CoordinationPreparedDelivery prepared =
                planner.prepare(
                        rootBlueId,
                        eventBlueId,
                        expectedSnapshot,
                        candidates,
                        exactProvider,
                        ROOT_REVISION,
                        eventOrder);
        lastPlanIdentity =
                prepared.deliveryPlanIdentity();
        lastProviderDemandCount =
                exactProvider.demandCount();
        lastProviderDemandBytes =
                exactProvider.returnedBytes();
        evidence.snapshotOccurrences +=
                expectedSnapshot.occurrences().size();
        evidence.snapshotBytes += snapshotBytes;
        evidence.plannerCandidates +=
                candidates.size();
        evidence.providerDemandCount +=
                lastProviderDemandCount;
        evidence.providerDemandBytes +=
                lastProviderDemandBytes;
        return prepared;
    }

    @TearDown(Level.Iteration)
    public void verifyIteration() {
        if (lastProjectionDigest != null
                && !expectedProjectionDigest.equals(
                        lastProjectionDigest)) {
            throw new IllegalStateException(
                    "Projection identity changed during measurement");
        }
        if (lastPlanIdentity != null
                && !expectedPlanIdentity.equals(
                        lastPlanIdentity)) {
            throw new IllegalStateException(
                    "Delivery-plan identity changed during measurement");
        }
        if (lastPlanIdentity != null
                && (lastProviderDemandCount
                != expectedProviderDemandCount
                || lastProviderDemandBytes
                != expectedProviderDemandBytes)) {
            throw new IllegalStateException(
                    "Exact provider demand changed during measurement");
        }
    }

    @TearDown(Level.Trial)
    public void reportFixture() {
        System.out.println(
                "Coordination projection/planning fixture: channels="
                        + channelCount
                        + ", rootBytes="
                        + rootBytes
                        + ", snapshotOccurrences="
                        + expectedSnapshot.occurrences().size()
                        + ", snapshotBytes="
                        + snapshotBytes
                        + ", plannerCandidates="
                        + candidates.size()
                        + ", providerDemandCount="
                        + expectedProviderDemandCount
                        + ", providerDemandBytes="
                        + expectedProviderDemandBytes
                        + ", projectionDigest="
                        + expectedProjectionDigest
                        + ", planIdentity="
                        + expectedPlanIdentity);
        blue.close();
    }

    /**
     * Logical evidence emitted as JMH secondary metrics. These counters are
     * not performance gates.
     */
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class EvidenceCounters {
        public long plannerCandidates;
        public long providerDemandBytes;
        public long providerDemandCount;
        public long snapshotBytes;
        public long snapshotOccurrences;

        @Setup(Level.Iteration)
        public void reset() {
            plannerCandidates = 0L;
            providerDemandBytes = 0L;
            providerDemandCount = 0L;
            snapshotBytes = 0L;
            snapshotOccurrences = 0L;
        }
    }

    private static Node document(
            BlueRepository repository,
            int channels) {
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                SELECTED_CHANNEL,
                timelineChannel(
                        SELECTED_TIMELINE));
        for (int index = 1;
                index < channels;
                index++) {
            contracts.put(
                    String.format(
                            java.util.Locale.ROOT,
                            "decoy-%05d",
                            Integer.valueOf(index)),
                    timelineChannel(
                            "decoy-timeline-" + index));
        }
        return new Node()
                .blue(repository.typeAliasBlue())
                .name("Subscription projection scale " + channels)
                .properties(
                        "contracts",
                        new Node().properties(
                                contracts));
    }

    private static Node timelineChannel(
            String timelineId) {
        return new Node()
                .type(typeReference(
                        TimelineChannel.blueId()))
                .properties(
                        "timeline",
                        new Node()
                                .type(typeReference(
                                        Timeline.blueId()))
                                .properties(
                                        "providerId",
                                        new Node().value(
                                                "benchmark-provider"))
                                .properties(
                                        "timelineId",
                                        new Node().value(
                                                timelineId)))
                .properties(
                        "actor",
                        new Node().type(
                                typeReference(
                                        PrincipalActor.blueId())));
    }

    private static Node timelineEntry(
            Blue blue,
            BlueRepository repository,
            String timelineId,
            int timestamp) {
        BigInteger exactTimestamp =
                BigInteger.valueOf(timestamp);
        TimelineEntry entry =
                new TimelineEntry()
                        .timeline(
                                new Timeline()
                                        .timelineId(
                                                timelineId))
                        .actor(
                                new PrincipalActor())
                        .timestamp(exactTimestamp);
        Node authored =
                blue.objectToNode(entry)
                        .properties(
                                "timestamp",
                                new Node().value(
                                        exactTimestamp))
                        .properties(
                                "message",
                                new Node().value(
                                        "sparse-match"))
                        .blue(repository.typeAliasBlue());
        return blue.preprocess(authored)
                .blue(null);
    }

    private static ExternalOrderKey eventOrder(
            Node event) {
        List<Object> components =
                new ArrayList<Object>();
        Object timestamp =
                event.getProperties()
                        .get("timestamp")
                        .getValue();
        components.add(
                timestamp instanceof BigInteger
                        ? timestamp
                        : BigInteger.valueOf(
                                ((Number) timestamp)
                                        .longValue()));
        components.add(
                BlueIdCalculator.calculateBlueId(
                        event.getProperties()
                                .get("timeline")));
        components.add(
                BlueIdCalculator.calculateBlueId(
                        event));
        return ExternalOrderKey.of(components);
    }

    private static List<String> selectedCandidate(
            CoordinationSubscriptionSnapshot snapshot) {
        CoordinationSubscriptionOccurrence selected =
                null;
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            if (SELECTED_CHANNEL.equals(
                    occurrence.channelKey())) {
                selected = occurrence;
                break;
            }
        }
        if (selected == null) {
            throw new IllegalStateException(
                    "Selected benchmark Channel was not projected");
        }
        return Collections.singletonList(
                selected.occurrenceKey());
    }

    private long encodedBytes(Node node) {
        return blue.nodeToJson(node)
                .getBytes(StandardCharsets.UTF_8)
                .length;
    }

    private static Node typeReference(
            String blueId) {
        return new Node().blueId(blueId);
    }

    private static void requireSuccess(
            DocumentProcessingResult result,
            String phase) {
        if (result == null
                || result.status()
                != ProcessorStatus.SUCCESS) {
            throw new IllegalStateException(
                    phase
                            + " failed: "
                            + (result != null
                            && result.diagnostic() != null
                            ? result.diagnostic().message()
                            : "missing result"));
        }
    }

    private static final class CountingExactProvider
            implements NodeProvider {
        private final Map<String, Node> nodes;
        private final Map<String, Long> encodedBytes;
        private long demandCount;
        private long returnedBytes;

        private CountingExactProvider(
                Blue blue,
                Map<String, Node> source) {
            nodes = new LinkedHashMap<String, Node>();
            encodedBytes =
                    new LinkedHashMap<String, Long>();
            for (Map.Entry<String, Node> entry
                    : source.entrySet()) {
                Node exact = entry.getValue().clone();
                nodes.put(entry.getKey(), exact);
                encodedBytes.put(
                        entry.getKey(),
                        Long.valueOf(
                                blue.nodeToJson(exact)
                                        .getBytes(
                                                StandardCharsets.UTF_8)
                                        .length));
            }
        }

        @Override
        public List<Node> fetchByBlueId(
                String blueId) {
            demandCount++;
            Node node = nodes.get(blueId);
            if (node == null) {
                return Collections.emptyList();
            }
            returnedBytes +=
                    encodedBytes.get(blueId)
                            .longValue();
            return Collections.singletonList(
                    node.clone());
        }

        private long demandCount() {
            return demandCount;
        }

        private long returnedBytes() {
            return returnedBytes;
        }

        private void reset() {
            demandCount = 0L;
            returnedBytes = 0L;
        }
    }
}
