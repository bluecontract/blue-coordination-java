package blue.coordination.processor;

import blue.coordination.processor.support.CoordinationRuntimeGasSupport;
import blue.language.processor.GasChargeContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.RuntimeWorkSession;

import java.util.Map;
import java.util.Objects;

/**
 * Stable public facade for the manifest-backed Coordination runtime ledger.
 *
 * <p>All handles delegate to one package-neutral implementation. Workflow and
 * facade callers therefore share physical ledgers and exactly-once submission
 * state without a workflow-to-facade package dependency.</p>
 */
public final class CoordinationRuntimeGas {
    public static final String RESOURCE =
            CoordinationRuntimeGasSupport.RESOURCE;
    public static final String NAMESPACE =
            CoordinationRuntimeGasSupport.NAMESPACE;

    private CoordinationRuntimeGas() {
    }

    public static Ledger open(RuntimeWorkSession session) {
        return new Ledger(CoordinationRuntimeGasSupport.open(session));
    }

    public static Ledger open(ProcessorExecutionContext context) {
        return new Ledger(CoordinationRuntimeGasSupport.open(context));
    }

    static <T> T inComponent(
            RuntimeWorkSession session,
            final ComponentWork<T> work) {
        Objects.requireNonNull(work, "work");
        return CoordinationRuntimeGasSupport.inComponent(
                session,
                new CoordinationRuntimeGasSupport.ComponentWork<T>() {
                    @Override
                    public T run() {
                        return work.run();
                    }
                });
    }

    static <T> T inComponent(
            ProcessorExecutionContext context,
            final ComponentWork<T> work) {
        Objects.requireNonNull(work, "work");
        return CoordinationRuntimeGasSupport.inComponent(
                context,
                new CoordinationRuntimeGasSupport.ComponentWork<T>() {
                    @Override
                    public T run() {
                        return work.run();
                    }
                });
    }

    public static void charge(
            RuntimeWorkSession session,
            String counter,
            long quantity,
            GasChargeContext context) {
        CoordinationRuntimeGasSupport.charge(
                session, counter, quantity, context);
    }

    public static void charge(
            ProcessorExecutionContext context,
            String counter,
            long quantity,
            GasChargeContext gasContext) {
        CoordinationRuntimeGasSupport.charge(
                context, counter, quantity, gasContext);
    }

    public static Map<String, Long> counterWeights() {
        return CoordinationRuntimeGasSupport.counterWeights();
    }

    /** Public compatibility handle over the shared runtime ledger. */
    public static final class Ledger {
        private final CoordinationRuntimeGasSupport.Ledger delegate;

        private Ledger(CoordinationRuntimeGasSupport.Ledger delegate) {
            this.delegate = delegate;
        }

        public void charge(
                String counter,
                long quantity,
                GasChargeContext context) {
            delegate.charge(counter, quantity, context);
        }

        public void submit() {
            delegate.submit();
        }

        public boolean isSessionOpen() {
            return delegate.isSessionOpen();
        }
    }

    interface ComponentWork<T> {
        T run();
    }
}
