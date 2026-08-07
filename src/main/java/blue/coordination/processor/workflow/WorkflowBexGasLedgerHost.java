package blue.coordination.processor.workflow;

import blue.bex.api.BexGasLedgerHost;
import blue.bex.contracts.ProcessorExecutionContextBexGasLedgerHost;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLedgerCapability;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexHostGasExhaustion;
import blue.bex.gas.BexSharedGasBudget;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorFailureException;
import blue.language.processor.RuntimeWorkSession;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Allocates one final BEX session adapter per Compute execution in a
 * sequential-workflow handler invocation.
 *
 * <p>Every execution receives a unique deterministic physical namespace.
 * Primary and intrinsic ledgers opened by that execution therefore share the
 * final BEX invocation budget without colliding with a later Compute step.
 * Successful ledgers are submitted to the generic Language work session
 * immediately; the enclosing processor session still owns the single atomic
 * merge, deterministic-prefix retention, or transient discard.</p>
 */
final class WorkflowBexGasLedgerHost implements BexGasLedgerHost {
    private static final Map<Object, Integer> NEXT_WORKFLOW =
            new WeakHashMap<Object, Integer>();

    private final ProcessorExecutionContext processorContext;
    private final RuntimeWorkSession workSession;
    private final String workflowNamespace;
    private final Map<BexGasLedgerCapability, BexGasLedgerHost> owners =
            new IdentityHashMap<BexGasLedgerCapability, BexGasLedgerHost>();
    private final Set<BexGasLedgerCapability> activeLedgers =
            Collections.newSetFromMap(
                    new IdentityHashMap<BexGasLedgerCapability, Boolean>());
    private BexGasLedgerHost activeHost;
    private BexSharedGasBudget activeBudget;
    private int nextExecution;
    private boolean finalized;

    WorkflowBexGasLedgerHost(ProcessorExecutionContext processorContext) {
        this.processorContext = Objects.requireNonNull(
                processorContext, "processorContext");
        this.workSession = null;
        this.workflowNamespace = "bex.workflow."
                + sequence(nextWorkflow(
                        processorContext));
    }

    WorkflowBexGasLedgerHost(RuntimeWorkSession workSession) {
        this.processorContext = null;
        this.workSession = Objects.requireNonNull(
                workSession, "workSession");
        this.workflowNamespace = "bex.workflow."
                + sequence(nextWorkflow(workSession));
    }

    @Override
    public BexSharedGasBudget openSharedBudget(long maximumGas) {
        if (maximumGas < 0L) {
            throw new IllegalArgumentException(
                    "Shared BEX gas budget must be non-negative");
        }
        ensureExecutionCanStart();
        if (workSession == null) {
            // The public ProcessorExecutionContext boundary exposes live
            // parent ledgers but intentionally not its internal shared-budget
            // session. BEX retains the one aggregate local precheck in this
            // hosted mode and every admitted charge still enters Contracts
            // exactly once through the official adapter.
            return null;
        }
        beginExecution();
        try {
            activeBudget =
                    activeHost.openSharedBudget(maximumGas);
            return activeBudget;
        } catch (RuntimeException | Error failure) {
            clearUnopenedExecution();
            throw failure;
        }
    }

    @Override
    public BexGasLedgerCapability open(
            String requestedNamespace,
            Map<String, Long> requestedWeights) {
        if (activeBudget != null) {
            throw new IllegalStateException(
                    "Every ledger in this BEX execution must attach to "
                            + "its shared runtime work budget");
        }
        return openInternal(
                requestedNamespace,
                requestedWeights,
                null);
    }

    @Override
    public BexGasLedgerCapability open(
            String requestedNamespace,
            Map<String, Long> requestedWeights,
            BexSharedGasBudget sharedBudget) {
        BexSharedGasBudget exactBudget =
                Objects.requireNonNull(
                        sharedBudget, "sharedBudget");
        if (activeHost == null
                || activeBudget != exactBudget) {
            throw new IllegalArgumentException(
                    "Shared BEX budget must be opened by the active "
                            + "workflow Compute execution");
        }
        return openInternal(
                requestedNamespace,
                requestedWeights,
                exactBudget);
    }

    @Override
    public void submit(BexGasLedgerCapability submittedLedger) {
        finishLedger(
                submittedLedger,
                new LedgerAction() {
                    @Override
                    public void apply(
                            BexGasLedgerHost owner,
                            BexGasLedgerCapability ledger) {
                        owner.submit(ledger);
                    }
                },
                "submitted");
    }

    @Override
    public void failedDeterministically(
            BexGasLedgerCapability failedLedger) {
        finishLedger(
                failedLedger,
                new LedgerAction() {
                    @Override
                    public void apply(
                            BexGasLedgerHost owner,
                            BexGasLedgerCapability ledger) {
                        owner.failedDeterministically(ledger);
                    }
                },
                "failed");
    }

    @Override
    public void evidenceUnavailable(
            BexGasLedgerCapability unavailableLedger) {
        finishLedger(
                unavailableLedger,
                new LedgerAction() {
                    @Override
                    public void apply(
                            BexGasLedgerHost owner,
                            BexGasLedgerCapability ledger) {
                        owner.evidenceUnavailable(ledger);
                    }
                },
                "became unavailable");
    }

    @Override
    public RuntimeException localGasLimitExceeded(
            BexGasLimitExceededException exhaustion,
            RuntimeException originalFailure) {
        BexGasLimitExceededException exact =
                Objects.requireNonNull(exhaustion, "exhaustion");
        Objects.requireNonNull(
                originalFailure, "originalFailure");
        return new ProcessorFailureException(
                ProcessorErrorCategory.GasLimitExceeded,
                exact.getMessage(),
                exact);
    }

    @Override
    public void propagateGasExhaustion(
            BexGasLedgerCapability rejectedLedger,
            BexHostGasExhaustion exhaustion) {
        BexGasLedgerHost owner =
                requireOwner(rejectedLedger);
        owner.propagateGasExhaustion(
                rejectedLedger,
                Objects.requireNonNull(
                        exhaustion, "exhaustion"));
    }

    /**
     * Finalizes the workflow adapter. Actual ledger submission is delegated
     * to the final BEX session adapter on each successful execution.
     */
    void submitToParent() {
        if (finalized) {
            return;
        }
        finalized = true;
        if (!activeLedgers.isEmpty()) {
            throw new IllegalStateException(
                    "Workflow ended while a BEX execution still owned "
                            + activeLedgers.size()
                            + " runtime ledger(s)");
        }
        clearUnopenedExecution();
    }

    void discardForUnavailableEvidence() {
        ensureNotFinalized();
    }

    void submitToParent(Throwable primaryFailure) {
        try {
            submitToParent();
        } catch (RuntimeException | Error submitFailure) {
            if (primaryFailure != null
                    && primaryFailure != submitFailure) {
                primaryFailure.addSuppressed(submitFailure);
            } else {
                throw submitFailure;
            }
        }
    }

    private BexGasLedgerCapability openInternal(
            String requestedNamespace,
            Map<String, Long> requestedWeights,
            BexSharedGasBudget sharedBudget) {
        ensureNotFinalized();
        String logicalNamespace =
                requireNamespace(requestedNamespace);
        boolean primary =
                BexGasCounter.NAMESPACE.equals(logicalNamespace);
        if (activeHost == null) {
            if (!primary || sharedBudget != null) {
                throw new IllegalStateException(
                        "Every hosted BEX execution must open its primary "
                                + "ledger first");
            }
            beginExecution();
        } else if (activeLedgers.isEmpty() && !primary) {
            throw new IllegalStateException(
                    "Every hosted BEX execution must open its primary "
                            + "ledger first");
        } else if (!activeLedgers.isEmpty() && primary) {
            throw new IllegalStateException(
                    "A hosted BEX execution already opened its primary "
                            + "ledger");
        }
        BexGasLedgerCapability ledger;
        try {
            ledger = sharedBudget == null
                    ? activeHost.open(
                    logicalNamespace,
                    requestedWeights)
                    : activeHost.open(
                    logicalNamespace,
                    requestedWeights,
                    sharedBudget);
        } catch (RuntimeException | Error failure) {
            if (activeLedgers.isEmpty()) {
                clearUnopenedExecution();
            }
            throw failure;
        }
        if (!activeLedgers.add(ledger)) {
            throw new IllegalStateException(
                    "Final BEX adapter returned a duplicate live ledger");
        }
        owners.put(ledger, activeHost);
        return ledger;
    }

    private void finishLedger(
            BexGasLedgerCapability ledger,
            LedgerAction action,
            String verb) {
        ensureNotFinalized();
        BexGasLedgerHost owner =
                requireOwner(ledger);
        if (owner != activeHost
                || !activeLedgers.remove(ledger)) {
            throw new IllegalStateException(
                    "BEX " + verb
                            + " a workflow ledger it does not currently own");
        }
        try {
            action.apply(owner, ledger);
        } finally {
            if (activeLedgers.isEmpty()) {
                activeHost = null;
                activeBudget = null;
            }
        }
    }

    private BexGasLedgerHost requireOwner(
            BexGasLedgerCapability ledger) {
        Objects.requireNonNull(ledger, "ledger");
        BexGasLedgerHost owner = owners.get(ledger);
        if (owner == null) {
            throw new IllegalArgumentException(
                    "BEX reported a different workflow child ledger");
        }
        return owner;
    }

    private void beginExecution() {
        if (nextExecution == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Workflow BEX execution sequence exhausted");
        }
        String runtimeNamespace = workflowNamespace
                + ".compute."
                + sequence(nextExecution);
        activeHost = processorContext != null
                ? new ProcessorExecutionContextBexGasLedgerHost(
                        processorContext,
                        runtimeNamespace)
                : new ProcessorExecutionContextBexGasLedgerHost(
                        workSession,
                        runtimeNamespace);
        nextExecution++;
    }

    private void ensureExecutionCanStart() {
        ensureNotFinalized();
        if (activeHost != null
                || !activeLedgers.isEmpty()) {
            throw new IllegalStateException(
                    "A hosted BEX execution is already active");
        }
    }

    private void clearUnopenedExecution() {
        if (activeLedgers.isEmpty()) {
            activeHost = null;
            activeBudget = null;
        }
    }

    private void ensureNotFinalized() {
        if (finalized) {
            throw new IllegalStateException(
                    "Workflow BEX adapter was already finalized");
        }
    }

    private static String requireNamespace(String value) {
        String exact = Objects.requireNonNull(
                value, "requestedNamespace");
        if (exact.trim().isEmpty()
                || exact.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                    "BEX namespace must be non-empty and must not contain '/'");
        }
        return exact;
    }

    private static synchronized int nextWorkflow(
            Object invocationKey) {
        Integer current = NEXT_WORKFLOW.get(invocationKey);
        int sequence = current != null
                ? current.intValue()
                : 0;
        if (sequence == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Workflow BEX runtime sequence exhausted");
        }
        NEXT_WORKFLOW.put(
                invocationKey,
                Integer.valueOf(sequence + 1));
        return sequence;
    }

    private static String sequence(int value) {
        return String.format(
                java.util.Locale.ROOT,
                "%08d",
                Integer.valueOf(value));
    }

    private interface LedgerAction {
        void apply(
                BexGasLedgerHost owner,
                BexGasLedgerCapability ledger);
    }
}
