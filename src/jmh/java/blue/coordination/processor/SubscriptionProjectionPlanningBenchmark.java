package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.language.processor.IndexedDeliveryEvaluator;
import blue.language.processor.IndexedDeliveryPreparation;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.SubscriptionSurfaceProjection;
import blue.language.provider.NodeProvider;
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
 * Measures the current public subscription-projection and sparse indexed
 * delivery boundaries over the release scale points.
 *
 * <p>Both measured paths call the public Contracts services directly. The
 * projection benchmark measures complete initial projection, while the
 * indexed benchmark acquires the exact Root and event from a host provider
 * and verifies the one sparse physical candidate against the complete active
 * interval surface. No benchmark-local evaluator, empty-plan shortcut, or
 * split-package access is used.</p>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SubscriptionProjectionPlanningBenchmark {
    private static final long ROOT_REVISION = 17L;
    private static final String SELECTED_CHANNEL = "selected";
    private static final String SELECTED_TIMELINE = "selected-timeline";

    @Param({"10", "100", "1000", "4096"})
    public int channelCount;

    private CoordinationBenchmarkRuntime runtime;
    private Node root;
    private String rootBlueId;
    private Node event;
    private String eventBlueId;
    private ExternalOrderKey eventOrder;
    private SubscriptionSurfaceProjection projectionBoundary;
    private IndexedDeliveryEvaluator indexedBoundary;
    private CountingExactProvider exactProvider;
    private List<SubscriptionDelta.Entry> activeIntervals;
    private List<ExternalSubscriptionOccurrenceKey> indexedCandidates;
    private long rootBytes;
    private long eventBytes;
    private long snapshotBytes;
    private SubscriptionDelta lastProjection;
    private IndexedDeliveryPreparation lastPlanning;

    @Setup(Level.Trial)
    public void setUpTrial() {
        runtime = CoordinationBenchmarkRuntime.create();
        Node exact = runtime.preprocess(document(channelCount));
        root = exact;
        rootBlueId = runtime.language().identity()
                .directBlueId(root);
        rootBytes = encodedBytes(root);

        event = timelineEntry(
                SELECTED_TIMELINE,
                23);
        eventBlueId = runtime.language().identity()
                .directBlueId(event);
        eventOrder = eventOrder(event);

        Map<String, Node> exactNodes =
                new LinkedHashMap<String, Node>();
        exactNodes.put(rootBlueId, root);
        exactNodes.put(eventBlueId, event);
        exactProvider = new CountingExactProvider(
                runtime,
                exactNodes);
        projectionBoundary = runtime.contracts()
                .subscriptionSurfaceProjection();
        indexedBoundary = runtime.contracts()
                .indexedDeliveryEvaluator();
        SubscriptionDelta initial = projectionBoundary.projectInitial(
                root,
                ROOT_REVISION,
                ExternalOrderKey.of(Collections.emptyList()));
        activeIntervals = initial.added();
        indexedCandidates = Collections.singletonList(
                selectedCandidate(activeIntervals));
        eventBytes = encodedBytes(event);
        snapshotBytes = encodedSubscriptionBytes(activeIntervals);
        exactProvider.reset();
    }

    @Setup(Level.Iteration)
    public void setUpIteration() {
        lastProjection = null;
        lastPlanning = null;
        exactProvider.reset();
    }

    /**
     * Measures complete initial projection through the public Contracts API.
     *
     * @return immutable subscription delta
     */
    @Benchmark
    public SubscriptionDelta projectCurrent(EvidenceCounters evidence) {
        lastProjection = projectionBoundary.projectInitial(
                root,
                ROOT_REVISION,
                ExternalOrderKey.of(Collections.emptyList()));
        evidence.requestedChannels += channelCount;
        evidence.snapshotOccurrences += lastProjection.added().size();
        evidence.snapshotBytes += snapshotBytes;
        return lastProjection;
    }

    /**
     * Measures exact sparse-candidate verification through the public API.
     *
     * @return verified indexed delivery preparation
     */
    @Benchmark
    public IndexedDeliveryPreparation planSparseIndexedEvent(
            EvidenceCounters evidence) {
        exactProvider.reset();
        Node exactRoot = exact(rootBlueId);
        Node exactEvent = exact(eventBlueId);
        lastPlanning = indexedBoundary.prepare(
                exactRoot,
                exactEvent,
                ROOT_REVISION,
                eventOrder,
                activeIntervals,
                indexedCandidates);
        evidence.requestedChannels += channelCount;
        evidence.snapshotOccurrences += activeIntervals.size();
        evidence.snapshotBytes += snapshotBytes;
        evidence.plannerCandidates += indexedCandidates.size();
        evidence.providerDemandCount += exactProvider.demandCount();
        evidence.providerDemandBytes += exactProvider.returnedBytes();
        return lastPlanning;
    }

    @TearDown(Level.Iteration)
    public void verifyIteration() {
        if (lastProjection != null
                && (lastProjection.added().size() != channelCount
                        || !lastProjection.removed().isEmpty())) {
            throw new IllegalStateException(
                    "Public projection did not return the complete initial "
                            + "surface: added="
                            + lastProjection.added().size()
                            + ", removed="
                            + lastProjection.removed().size());
        }
        if (lastPlanning != null
                && (lastPlanning.deliveryPlan().deliveries().size() != 1
                        || !SELECTED_CHANNEL.equals(
                        lastPlanning.deliveryPlan().deliveries()
                                .get(0).channelKey())
                        || lastPlanning.diagnostics().size()
                        != channelCount)) {
            throw new IllegalStateException(
                    "Public indexed planning did not select exactly the "
                            + "sparse Timeline Channel from the complete "
                            + "surface");
        }
        if (lastPlanning != null
                && (exactProvider.demandCount() != 2L
                        || exactProvider.returnedBytes()
                        != rootBytes + eventBytes)) {
            throw new IllegalStateException(
                    "Exact Root/event provider demand changed during "
                            + "public indexed planning");
        }
    }

    @TearDown(Level.Trial)
    public void reportFixture() {
        System.out.println(
                "Coordination projection/planning public-boundary fixture: "
                        + "channels=" + channelCount
                        + ", rootBytes=" + rootBytes
                        + ", eventBytes=" + eventBytes
                        + ", snapshotOccurrences="
                        + activeIntervals.size()
                        + ", snapshotBytes=" + snapshotBytes
                        + ", indexedCandidates="
                        + indexedCandidates.size()
                        + ", expectedDeliveries=1");
        runtime.close();
    }

    /** Logical evidence emitted as JMH secondary metrics. */
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class EvidenceCounters {
        public long plannerCandidates;
        public long providerDemandBytes;
        public long providerDemandCount;
        public long requestedChannels;
        public long snapshotBytes;
        public long snapshotOccurrences;

        @Setup(Level.Iteration)
        public void reset() {
            plannerCandidates = 0L;
            providerDemandBytes = 0L;
            providerDemandCount = 0L;
            requestedChannels = 0L;
            snapshotBytes = 0L;
            snapshotOccurrences = 0L;
        }
    }

    private Node exact(String blueId) {
        List<Node> matches = exactProvider.fetchByBlueId(blueId);
        if (matches == null || matches.size() != 1) {
            throw new IllegalStateException(
                    "Benchmark exact provider did not return one node for "
                            + blueId);
        }
        return matches.get(0);
    }

    private static ExternalSubscriptionOccurrenceKey selectedCandidate(
            List<SubscriptionDelta.Entry> intervals) {
        for (SubscriptionDelta.Entry interval : intervals) {
            if (SELECTED_CHANNEL.equals(interval.channelKey())) {
                return ExternalSubscriptionOccurrenceKey.of(
                        interval.scopePath(), interval.channelKey());
            }
        }
        throw new IllegalStateException(
                "Initial projection omitted the selected Timeline Channel");
    }

    private static long encodedSubscriptionBytes(
            List<SubscriptionDelta.Entry> intervals) {
        long bytes = 0L;
        for (SubscriptionDelta.Entry interval : intervals) {
            bytes += utf8Bytes(interval.scopePath());
            bytes += utf8Bytes(interval.channelKey());
            bytes += utf8Bytes(interval.effectiveTypeBlueId());
            bytes += utf8Bytes(interval.checkpointDomainBlueId());
            for (String key : interval.subscriptionKeys()) {
                bytes += utf8Bytes(key);
            }
            for (String source :
                    interval.sourceContributionNodeBlueIds()) {
                bytes += utf8Bytes(source);
            }
        }
        return bytes;
    }

    private static long utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Node document(
            int channels) {
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                SELECTED_CHANNEL,
                timelineChannel(SELECTED_TIMELINE));
        for (int index = 1; index < channels; index++) {
            contracts.put(
                    String.format(
                            java.util.Locale.ROOT,
                            "decoy-%05d",
                            Integer.valueOf(index)),
                    timelineChannel(
                            "decoy-timeline-" + index));
        }
        return new Node()
                .name("Subscription projection scale " + channels)
                .properties(
                        "contracts",
                        new Node().properties(contracts));
    }

    private static Node timelineChannel(String timelineId) {
        return new Node()
                .type(typeReference(TimelineChannel.blueId()))
                .properties(
                        "timeline",
                        new Node()
                                .type(typeReference(Timeline.blueId()))
                                .properties(
                                        "providerId",
                                        new Node().value(
                                                "benchmark-provider"))
                                .properties(
                                        "timelineId",
                                        new Node().value(timelineId)))
                .properties(
                        "actor",
                        new Node().type(
                                typeReference(
                                        PrincipalActor.blueId())));
    }

    private Node timelineEntry(
            String timelineId,
            int timestamp) {
        BigInteger exactTimestamp = BigInteger.valueOf(timestamp);
        Node authored = new Node()
                .type(typeReference(TimelineEntry.blueId()))
                .properties(
                        "timeline",
                        new Node()
                                .type(typeReference(Timeline.blueId()))
                                .properties(
                                        "timelineId",
                                        new Node().value(timelineId)))
                .properties(
                        "actor",
                        new Node().type(
                                typeReference(
                                        PrincipalActor.blueId())))
                .properties(
                        "timestamp",
                        new Node().value(exactTimestamp))
                .properties(
                        "message",
                        new Node().value("sparse-match"));
        return runtime.preprocess(authored).blue(null);
    }

    private ExternalOrderKey eventOrder(Node event) {
        List<Object> components = new ArrayList<Object>();
        Object timestamp = event.getProperties()
                .get("timestamp")
                .getValue();
        components.add(timestamp instanceof BigInteger
                ? timestamp
                : BigInteger.valueOf(
                        ((Number) timestamp).longValue()));
        components.add(
                runtime.language().identity().directBlueId(
                        event.getProperties().get("timeline")));
        components.add(
                runtime.language().identity().directBlueId(event));
        return ExternalOrderKey.of(components);
    }

    private long encodedBytes(Node node) {
        return runtime.nodeToJson(node)
                .getBytes(StandardCharsets.UTF_8)
                .length;
    }

    private static Node typeReference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class CountingExactProvider
            implements NodeProvider {
        private final Map<String, Node> nodes;
        private final Map<String, Long> encodedBytes;
        private long demandCount;
        private long returnedBytes;

        private CountingExactProvider(
                CoordinationBenchmarkRuntime runtime,
                Map<String, Node> source) {
            nodes = new LinkedHashMap<String, Node>();
            encodedBytes = new LinkedHashMap<String, Long>();
            for (Map.Entry<String, Node> entry : source.entrySet()) {
                Node exact = entry.getValue().clone();
                nodes.put(entry.getKey(), exact);
                encodedBytes.put(
                        entry.getKey(),
                        Long.valueOf(
                                runtime.nodeToJson(exact)
                                        .getBytes(StandardCharsets.UTF_8)
                                        .length));
            }
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            demandCount++;
            Node node = nodes.get(blueId);
            if (node == null) {
                return Collections.emptyList();
            }
            returnedBytes += encodedBytes.get(blueId).longValue();
            return Collections.singletonList(node.clone());
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
