package blue.coordination.processor.workflow;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Allocation/scaling comparison for the per-step result-state boundary. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class WorkflowExecutionStateBenchmark {

    @Param({"100", "500", "1000"})
    public int steps;

    @Benchmark
    public void revisionedReadOnlyViews(Blackhole blackhole) {
        WorkflowExecutionState state = new WorkflowExecutionState();
        for (int index = 0; index < steps; index++) {
            WorkflowExecutionState.Snapshot view = state.snapshotView();
            blackhole.consume(view.size());
            state.record("Step" + index, Integer.valueOf(index), (index & 1) == 0);
        }
        blackhole.consume(state.snapshotView());
    }

    @Benchmark
    public void legacyWholeMapCopies(Blackhole blackhole) {
        Map<String, Object> results = new LinkedHashMap<String, Object>();
        for (int index = 0; index < steps; index++) {
            Map<String, Object> snapshot = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Object>(results));
            blackhole.consume(snapshot.size());
            results.put("Step" + index, Integer.valueOf(index));
        }
        blackhole.consume(results);
    }
}
