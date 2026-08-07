package blue.coordination.engine.memory;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.spi.CoordinationProcessingEngineObserver;

import java.util.concurrent.atomic.LongAdder;

/** Exact engine-lifecycle work counters for deterministic performance gates. */
public final class CoordinationEngineWorkRecorder
        implements CoordinationProcessingEngineObserver {

    private final LongAdder plans = new LongAdder();
    private final LongAdder bundleLoads = new LongAdder();
    private final LongAdder bundleBatches = new LongAdder();
    private final LongAdder loadedFragmentIdentities = new LongAdder();
    private final LongAdder loadedBytes = new LongAdder();
    private final LongAdder processCompletions = new LongAdder();
    private final LongAdder commitAttempts = new LongAdder();
    private final LongAdder committed = new LongAdder();
    private final LongAdder alreadyCommitted = new LongAdder();
    private final LongAdder conflicts = new LongAdder();

    @Override
    public void onPlan(CoordinationProcessingPlan plan) {
        plans.increment();
    }

    @Override
    public void onBatchLoad(
            CoordinationProcessingPlan plan,
            LoadedProcessingBundle bundle) {
        bundleLoads.increment();
        bundleBatches.add(bundle.batchCount());
        loadedFragmentIdentities.add(bundle.backendLoadedBlueIds().size());
        loadedBytes.add(bundle.loadedBytes());
    }

    @Override
    public void onProcessComplete(CoordinationTransition transition) {
        processCompletions.increment();
    }

    @Override
    public void onCommit(CommitOutcome outcome) {
        commitAttempts.increment();
        if (outcome.status() == CommitStatus.COMMITTED) {
            committed.increment();
        } else if (outcome.status() == CommitStatus.ALREADY_COMMITTED) {
            alreadyCommitted.increment();
        } else if (outcome.status() == CommitStatus.CONFLICT) {
            conflicts.increment();
        }
    }

    /** Returns one immutable monotonic snapshot. */
    public CoordinationEngineWorkSnapshot snapshot() {
        return new CoordinationEngineWorkSnapshot(
                plans.sum(),
                bundleLoads.sum(),
                bundleBatches.sum(),
                loadedFragmentIdentities.sum(),
                loadedBytes.sum(),
                processCompletions.sum(),
                commitAttempts.sum(),
                committed.sum(),
                alreadyCommitted.sum(),
                conflicts.sum());
    }
}
