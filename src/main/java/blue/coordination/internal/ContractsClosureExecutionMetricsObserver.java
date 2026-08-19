package blue.coordination.internal;

import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.TentativeFinalization;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    static final String UNRELATED_COMPONENT_FINALIZATIONS =
            "contracts.closure.unrelatedComponentFinalizations";

    private final EngineMetrics metrics;
    private ClosureImplementationEvidence lastEvidence;
    private Set<String> allowedDocumentIds = Set.of();

    ContractsClosureExecutionMetricsObserver(EngineMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** Clears evidence before an execution which may suspend without it. */
    synchronized void beginAttempt(Collection<String> documentIds) {
        lastEvidence = null;
        allowedDocumentIds = Set.copyOf(new LinkedHashSet<>(
                Objects.requireNonNull(documentIds, "documentIds")));
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
            long unrelatedFinalizations = 0L;
            for (TentativeFinalization finalization
                    : evidence.tentativeFinalizations()) {
                canonicalBytes = Math.addExact(
                        canonicalBytes, finalization.canonicalBytes());
                if (finalization.memberBlueIds().keySet().stream()
                        .map(documentId -> documentId.value())
                        .anyMatch(documentId -> !allowedDocumentIds.contains(
                                documentId))) {
                    unrelatedFinalizations = Math.addExact(
                            unrelatedFinalizations, 1L);
                }
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
            metrics.add(
                    UNRELATED_COMPONENT_FINALIZATIONS,
                    unrelatedFinalizations);
        } catch (ThreadDeath failure) {
            throw failure;
        } catch (VirtualMachineError failure) {
            throw failure;
        } catch (Throwable ignored) {
            // Operational diagnostics must not alter Contracts semantics.
        }
    }
}
