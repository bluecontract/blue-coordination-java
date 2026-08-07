package blue.coordination.engine.memory;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationScopeTransition;
import blue.coordination.engine.api.CoordinationTransition;
import blue.language.processor.GasTraceEntry;

import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;

/** Human-readable wrapper over the exact transition and authoritative CAS. */
public final class DemoTransition {

    private final CoordinationTransition transition;
    private final CommitOutcome commitOutcome;

    DemoTransition(
            CoordinationTransition transition,
            CommitOutcome commitOutcome) {
        this.transition = Objects.requireNonNull(transition, "transition");
        this.commitOutcome = Objects.requireNonNull(
                commitOutcome, "commitOutcome");
    }

    public CoordinationTransition transition() { return transition; }
    public CommitOutcome commitOutcome() { return commitOutcome; }

    public void printSelectedScopeChains() {
        printSelectedScopeChains(System.out);
    }

    public void printSelectedScopeChains(PrintStream output) {
        PrintStream out = Objects.requireNonNull(output, "output");
        for (Map.Entry<String, java.util.List<String>> entry
                : transition.plan().preparedDelivery()
                        .selectedScopeChainIdentities().entrySet()) {
            out.println(entry.getKey() + " -> " + entry.getValue());
        }
    }

    public void printLoadedFragments() { printLoadedFragments(System.out); }

    public void printLoadedFragments(PrintStream output) {
        Objects.requireNonNull(output, "output").println(
                transition.locality().backendLoadedBlueIds());
    }

    public void printLoadedWorkflows() { printLoadedWorkflows(System.out); }

    public void printLoadedWorkflows(PrintStream output) {
        Objects.requireNonNull(output, "output").println(
                transition.locality().causallySelectedBlueIds());
    }

    public void printBeforeAfter() { printBeforeAfter(System.out); }

    public void printBeforeAfter(PrintStream output) {
        PrintStream out = Objects.requireNonNull(output, "output");
        out.println(transition.beforeRootBlueId()
                + " -> " + transition.afterRootBlueId());
        for (CoordinationScopeTransition scope
                : transition.fragmentTransition().scopeTransitions()) {
            out.println(scope.scopePath() + " " + scope.kind()
                    + " " + scope.beforeBlueId()
                    + " -> " + scope.afterBlueId());
        }
    }

    public void printEpochs() { printEpochs(System.out); }

    public void printEpochs(PrintStream output) {
        Objects.requireNonNull(output, "output").println(
                transition.beforeEpoch() + " -> " + transition.afterEpoch());
    }

    public void printGasTrace() { printGasTrace(System.out); }

    public void printGasTrace(PrintStream output) {
        PrintStream out = Objects.requireNonNull(output, "output");
        out.println("status=" + transition.status().wireValue()
                + ", totalGas="
                + transition.platformResult().processResult().totalGas());
        out.println("The immutable PROCESS observer owns any named trace; "
                + "the engine never replays PROCESS to obtain it.");
    }
}
