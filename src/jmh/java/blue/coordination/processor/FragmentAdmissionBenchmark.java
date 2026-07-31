package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Measures canonical Event splitting, first admission, and idempotent repeat
 * admission into an immutable content-addressed store.
 *
 * <p>Every measured result is checked against the precomputed fragmentation
 * inventory identity. Allocation distributions are supplied by the configured
 * JMH GC profiler; logical fragment and byte totals are emitted as auxiliary
 * evidence.</p>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class FragmentAdmissionBenchmark {

    @Param({"10", "100", "1000"})
    public int leafCount;

    private CoordinationDocumentSplitter splitter;
    private Node exactEvent;
    private CoordinationDocumentSplitter.SplitGraph expectedSplit;
    private Map<String, Node> expectedFragments;
    private MemoryFragmentStore preloadedStore;
    private String expectedInventoryIdentity;
    private long inventoryBytes;
    private String lastInventoryIdentity;
    private int lastAdmitted;
    private int lastDuplicates;

    @Setup(Level.Trial)
    public void setUpTrial() {
        splitter =
                CoordinationDocumentSplitter
                        .forEventSplitting();
        exactEvent = event(leafCount);
        expectedSplit =
                splitter.splitEvent(exactEvent);
        expectedFragments =
                expectedSplit.fragments();
        expectedInventoryIdentity =
                expectedSplit.inventoryIdentity();
        inventoryBytes =
                encodedBytes(expectedFragments);
        preloadedStore =
                new MemoryFragmentStore();
        AdmissionTally preload =
                admitAll(
                        expectedSplit,
                        expectedFragments,
                        preloadedStore);
        if (preload.admitted
                != expectedFragments.size()
                || preload.duplicates != 0) {
            throw new IllegalStateException(
                    "Could not preload the immutable admission fixture");
        }
    }

    @Setup(Level.Iteration)
    public void setUpIteration() {
        lastInventoryIdentity = null;
        lastAdmitted = 0;
        lastDuplicates = 0;
    }

    /**
     * Measures Event splitting followed by first-writer fragment admission.
     *
     * @return deterministic split inventory identity
     */
    @Benchmark
    public String splitAndAdmitFreshInventory(
            AdmissionCounters evidence) {
        CoordinationDocumentSplitter.SplitGraph split =
                splitter.splitEvent(exactEvent);
        Map<String, Node> fragments =
                split.fragments();
        AdmissionTally tally =
                admitAll(
                        split,
                        fragments,
                        new MemoryFragmentStore());
        lastInventoryIdentity =
                split.inventoryIdentity();
        record(evidence, tally, fragments.size());
        return lastInventoryIdentity;
    }

    /**
     * Measures first-writer admission of an already split inventory.
     *
     * @return deterministic split inventory identity
     */
    @Benchmark
    public String admitFreshInventory(
            AdmissionCounters evidence) {
        AdmissionTally tally =
                admitAll(
                        expectedSplit,
                        expectedFragments,
                        new MemoryFragmentStore());
        lastInventoryIdentity =
                expectedInventoryIdentity;
        record(
                evidence,
                tally,
                expectedFragments.size());
        return lastInventoryIdentity;
    }

    /**
     * Measures byte verification of an idempotent repeated admission.
     *
     * @return deterministic split inventory identity
     */
    @Benchmark
    public String admitRepeatedInventory(
            AdmissionCounters evidence) {
        AdmissionTally tally =
                admitAll(
                        expectedSplit,
                        expectedFragments,
                        preloadedStore);
        lastInventoryIdentity =
                expectedInventoryIdentity;
        record(
                evidence,
                tally,
                expectedFragments.size());
        return lastInventoryIdentity;
    }

    @TearDown(Level.Iteration)
    public void verifyIteration() {
        if (lastInventoryIdentity != null
                && !expectedInventoryIdentity.equals(
                        lastInventoryIdentity)) {
            throw new IllegalStateException(
                    "Fragment inventory identity changed during measurement");
        }
        int total =
                lastAdmitted + lastDuplicates;
        if (lastInventoryIdentity != null
                && total != expectedFragments.size()) {
            throw new IllegalStateException(
                    "Measured admission omitted fragments");
        }
    }

    @TearDown(Level.Trial)
    public void reportFixture() {
        System.out.println(
                "Coordination fragment admission fixture: leaves="
                        + leafCount
                        + ", fragments="
                        + expectedFragments.size()
                        + ", inventoryBytes="
                        + inventoryBytes
                        + ", inventoryIdentity="
                        + expectedInventoryIdentity);
    }

    /**
     * Logical admission evidence emitted as JMH secondary metrics. These
     * values are correctness observations, not elapsed-time assertions.
     */
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class AdmissionCounters {
        public long admittedFragments;
        public long fragmentCount;
        public long idempotentFragments;
        public long inventoryBytes;

        @Setup(Level.Iteration)
        public void reset() {
            admittedFragments = 0L;
            fragmentCount = 0L;
            idempotentFragments = 0L;
            inventoryBytes = 0L;
        }
    }

    private void record(
            AdmissionCounters evidence,
            AdmissionTally tally,
            int fragments) {
        lastAdmitted = tally.admitted;
        lastDuplicates = tally.duplicates;
        evidence.admittedFragments +=
                tally.admitted;
        evidence.idempotentFragments +=
                tally.duplicates;
        evidence.fragmentCount += fragments;
        evidence.inventoryBytes +=
                inventoryBytes;
    }

    private static AdmissionTally admitAll(
            CoordinationDocumentSplitter.SplitGraph split,
            Map<String, Node> fragments,
            MemoryFragmentStore store) {
        int admitted = 0;
        int duplicates = 0;
        for (Map.Entry<String, Node> fragment
                : fragments.entrySet()) {
            CoordinationFragmentAdmissionVerifier
                    .AdmissionStatus status =
                    CoordinationFragmentAdmissionVerifier
                            .admit(
                                    split.fragmentationProfileIdentity(),
                                    fragment.getKey(),
                                    fragment.getValue(),
                                    store);
            if (status
                    == CoordinationFragmentAdmissionVerifier
                    .AdmissionStatus.ADMITTED) {
                admitted++;
            } else {
                duplicates++;
            }
        }
        return new AdmissionTally(
                admitted,
                duplicates);
    }

    private static Node event(
            int leaves) {
        Map<String, Node> payload =
                new LinkedHashMap<String, Node>();
        for (int index = 0;
                index < leaves;
                index++) {
            payload.put(
                    String.format(
                            java.util.Locale.ROOT,
                            "leaf-%05d",
                            Integer.valueOf(index)),
                    new Node()
                            .properties(
                                    "ordinal",
                                    new Node().value(index))
                            .properties(
                                    "payloadValue",
                                    new Node().value(
                                            payload(index))));
        }
        return new Node()
                .name("Fragment admission " + leaves)
                .properties(
                        "payload",
                        new Node().properties(
                                payload));
    }

    private static String payload(
            int index) {
        String unit =
                Integer.toHexString(index)
                        + "-0123456789abcdef";
        StringBuilder result =
                new StringBuilder(256);
        while (result.length() < 256) {
            result.append(unit);
        }
        return result.substring(0, 256);
    }

    private static long encodedBytes(
            Map<String, Node> fragments) {
        long result = 0L;
        for (Node fragment : fragments.values()) {
            result +=
                    UncheckedObjectMapper.JSON_MAPPER
                            .writeValueAsString(
                                    NodeToMapListOrValue.get(
                                            fragment))
                            .getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .length;
        }
        return result;
    }

    private static final class AdmissionTally {
        private final int admitted;
        private final int duplicates;

        private AdmissionTally(
                int admitted,
                int duplicates) {
            this.admitted = admitted;
            this.duplicates = duplicates;
        }
    }

    private static final class MemoryFragmentStore
            implements CoordinationFragmentAdmissionVerifier
            .ImmutableFragmentStore {
        private final Map<String, Node> fragments =
                new LinkedHashMap<String, Node>();

        @Override
        public Node read(
                String profileIdentity,
                String blueId) {
            requireProfile(profileIdentity);
            Node stored = fragments.get(blueId);
            return stored != null
                    ? stored.clone()
                    : null;
        }

        @Override
        public boolean putIfAbsent(
                String profileIdentity,
                String blueId,
                Node exactFragment) {
            requireProfile(profileIdentity);
            if (fragments.containsKey(blueId)) {
                return false;
            }
            fragments.put(
                    blueId,
                    exactFragment.clone());
            return true;
        }

        private static void requireProfile(
                String profileIdentity) {
            if (!CoordinationDocumentSplitter
                    .FRAGMENTATION_PROFILE_ID
                    .equals(profileIdentity)) {
                throw new IllegalArgumentException(
                        "Unexpected fragmentation profile");
            }
        }
    }
}
