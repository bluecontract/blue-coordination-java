package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureInvocationInput;

/** Test-only observation of the actual processor demand; no publication or fabricated evidence. */
public final class RootedRetargetAttemptProbe {
    private RootedRetargetAttemptProbe() { }

    public static ClosureAttemptResult firstAttempt(CoordinationEngine engine, ClosureInvocationInput input) {
        return new BlueClosureContracts(((DefaultCoordinationEngine) engine).runtime().documentProcessor())
                .processClosure(input);
    }

    /** Holds the exact original operation, rather than capturing a new input after the head advances. */
    public static FrozenAttempt capture(CoordinationEngine engine, blue.coordination.api.DocumentId root,
            String entryId, blue.coordination.api.ContractsExecutionPolicy policy) {
        var actual = (DefaultCoordinationEngine) engine;
        var entry = actual.auditTimelineEntries().stream().filter(value -> value.blueId().equals(entryId))
                .findFirst().orElseThrow();
        var adapter = actual.contractsClosureAdapter();
        return new FrozenAttempt(adapter, adapter.captureRoot(root, entry, policy));
    }

    public static final class FrozenAttempt {
        private final ContractsClosureAdapter adapter;
        private final ContractsClosureAdapter.FrozenBatch batch;

        private FrozenAttempt(ContractsClosureAdapter adapter, ContractsClosureAdapter.FrozenBatch batch) {
            this.adapter = adapter;
            this.batch = batch;
            if (batch.invocations().size() != 1) throw new IllegalArgumentException("Expected one exact rooted input");
        }

        /** Requires the actual idempotent publication lookup, including its original PROCESS result. */
        public blue.language.processor.closure.ClosureProcessResult replay() {
            var outcome = adapter.executeAndPublish(batch, batch.invocations().get(0));
            if (!outcome.published() || !outcome.replayed() || !outcome.attempt().isComplete())
                throw new IllegalStateException("Original captured operation did not replay its committed receipt");
            return outcome.attempt().processResult();
        }
    }
}
