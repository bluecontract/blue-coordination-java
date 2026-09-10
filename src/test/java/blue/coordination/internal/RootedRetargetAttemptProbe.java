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
}
