package blue.coordination.engine.fastpath;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Isolates the Coordination-owned clone/hash work seen in the PayNote and
 * Order Roots. This benchmark is a regression detector, not a substitute for
 * the end-to-end Wadowice latency gate.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@State(Scope.Thread)
public class ProcessHostFastPathBenchmark {

    @Param({"622", "1369"})
    public int identityCount;

    private List<Node> nodes;
    private List<ExactNodeHandle> handles;
    private Object owner;

    @Setup(Level.Trial)
    public void setup() {
        nodes = new ArrayList<Node>(identityCount);
        handles = new ArrayList<ExactNodeHandle>(identityCount);
        owner = new Object();
        for (int index = 0; index < identityCount; index++) {
            Node node = new Node().properties(
                    "ordinal", new Node().value(index),
                    "payload", new Node().value(
                            "wadowice-fragment-" + index));
            String blueId = DirectBlueIdCalculator.calculateBlueId(node);
            nodes.add(node);
            handles.add(ExactNodeHandle.copyAndVerify(
                    blueId, node, owner));
        }
    }

    /** Models repeated DTO/store validation: clone plus hash each body. */
    @Benchmark
    public void legacyCloneAndRehashEveryLayer(Blackhole sink) {
        for (Node node : nodes) {
            Node copy = node.clone();
            sink.consume(DirectBlueIdCalculator.calculateBlueId(copy));
        }
    }

    /** Internal path: use the verified handle and its cached identity. */
    @Benchmark
    public void preparedHandleIdentity(Blackhole sink) {
        for (ExactNodeHandle handle : handles) {
            sink.consume(handle.blueId());
            sink.consume(handle.borrow(owner));
        }
    }

    /** Public request boundaries still make one defensive snapshot. */
    @Benchmark
    public void oneBoundaryCopyWithoutRehash(Blackhole sink) {
        for (ExactNodeHandle handle : handles) {
            sink.consume(handle.copy());
        }
    }
}
