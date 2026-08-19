package blue.coordination.internal;

import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.TentativeFinalization;

import java.util.Objects;
import java.util.Optional;

/**
 * Non-semantic projection of completed closure execution evidence.
 *
 * <p>The observer records only raw implementation diagnostics. A non-fatal
 * metrics failure is deliberately isolated from Contracts execution, while
 * the exact immutable evidence remains available to test-fixture controls.</p>
 */
final class ContractsClosureExecutionMetricsObserver
        implements ClosureExecutionObserver {
    static final String ACCEPTED_WORK_OCCURRENCES =
            "contracts.closure.acceptedWorkOccurrences";
    static final String ISOLATED_DOCUMENT_STEPS =
            "contracts.closure.isolatedDocumentSteps";
    static final String TENTATIVE_COMPONENT_FINALIZATIONS =
            "contracts.closure.tentativeComponentFinalizations";
    static final String CANONICAL_CYCLIC_BYTES =
            "contracts.closure.canonicalCyclicBytes";

    private final EngineMetrics metrics;
    private ClosureImplementationEvidence lastEvidence;

    ContractsClosureExecutionMetricsObserver(EngineMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** Clears evidence before an execution which may suspend without it. */
    synchronized void beginAttempt() {
        lastEvidence = null;
    }

    /** Returns the exact immutable evidence from the latest completed attempt. */
    synchronized Optional<ClosureImplementationEvidence> lastEvidence() {
        return Optional.ofNullable(lastEvidence);
    }

    @Override
    public synchronized void onExecutionEvidence(
            ClosureImplementationEvidence evidence) {
        if (evidence == null) {
            return;
        }
        lastEvidence = evidence;
        try {
            long canonicalBytes = 0L;
            for (TentativeFinalization finalization
                    : evidence.tentativeFinalizations()) {
                canonicalBytes = Math.addExact(
                        canonicalBytes, finalization.canonicalBytes());
            }
            metrics.add(
                    ACCEPTED_WORK_OCCURRENCES,
                    evidence.workTrace().size());
            metrics.add(
                    ISOLATED_DOCUMENT_STEPS,
                    evidence.documentStepTrace().size());
            metrics.add(
                    TENTATIVE_COMPONENT_FINALIZATIONS,
                    evidence.tentativeFinalizations().size());
            metrics.add(CANONICAL_CYCLIC_BYTES, canonicalBytes);
        } catch (ThreadDeath failure) {
            throw failure;
        } catch (VirtualMachineError failure) {
            throw failure;
        } catch (Throwable ignored) {
            // Operational diagnostics must not alter Contracts semantics.
        }
    }
}
