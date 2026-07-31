package blue.coordination.processor;

import blue.language.processor.GasChargeContext;
import blue.language.processor.GasMeter;
import blue.language.processor.RuntimeWorkSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Manifest-backed Coordination runtime gas ledger.
 *
 * <p>The processor owns session lifecycle. This adapter opens deterministic
 * one-use physical ledgers and records only counters declared by the bundled
 * Coordination gas manifest. Nested Coordination components reuse the
 * invocation's active physical ledger. The outermost component submits that
 * ledger exactly once, so a member aggregate does not consume one runtime
 * namespace for every nested charge.</p>
 */
public final class CoordinationRuntimeGas {
    public static final String RESOURCE =
            "blue/coordination/processor/coordination-gas-1.0.yaml";
    public static final String NAMESPACE = "coordination";

    private static final Map<String, Long> WEIGHTS =
            loadWeights();
    private static final Map<RuntimeWorkSession, Integer> NEXT_SEQUENCE =
            new WeakHashMap<RuntimeWorkSession, Integer>();
    private static final Map<RuntimeWorkSession, WeakReference<ActiveLedger>>
            ACTIVE_LEDGERS =
            new WeakHashMap<
                    RuntimeWorkSession,
                    WeakReference<ActiveLedger>>();

    private CoordinationRuntimeGas() {
    }

    /**
     * Opens one live Coordination ledger owned by {@code session}.
     *
     * @param session processor-owned runtime work session
     * @return live ledger that must be submitted exactly once
     */
    public static Ledger open(RuntimeWorkSession session) {
        RuntimeWorkSession exact =
                Objects.requireNonNull(session, "session");
        return acquire(exact);
    }

    /**
     * Runs one Coordination component against a shared nested ledger.
     *
     * <p>A component may synchronously invoke other Coordination components.
     * Every nested call writes to the same live-bounded physical ledger; only
     * the outermost successful boundary submits it. Gas exhaustion leaves the
     * admitted prefix unsubmitted so the processor can propagate and retain
     * that exact prefix through its normal session lifecycle.</p>
     *
     * @param session processor-owned runtime work session
     * @param work component work performed after the ledger is open
     * @param <T> component result type
     * @return component result
     */
    static <T> T inComponent(
            RuntimeWorkSession session,
            ComponentWork<T> work) {
        Objects.requireNonNull(work, "work");
        Ledger ledger = open(session);
        Throwable failure = null;
        try {
            return work.run();
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (failure != null
                    || !ledger.isSessionOpen()) {
                ledger.abandon();
            } else {
                ledger.submit();
            }
        }
    }

    /**
     * Charges and submits one isolated unit of Coordination-owned work.
     *
     * @param session processor-owned runtime work session
     * @param counter Coordination gas counter to charge
     * @param quantity number of counter units to charge
     * @param context semantic context recorded with the charge
     */
    public static void charge(
            RuntimeWorkSession session,
            String counter,
            long quantity,
            GasChargeContext context) {
        if (quantity == 0L) {
            return;
        }
        Ledger ledger = open(session);
        boolean submitted = false;
        try {
            ledger.charge(counter, quantity, context);
            ledger.submit();
            submitted = true;
        } finally {
            /*
             * A rejected charge is already retained by RuntimeWorkSession.
             * Do not submit a ledger whose attempted work did not complete.
             */
            if (!submitted) {
                ledger.abandon();
            }
        }
    }

    /**
     * Returns the immutable manifest catalog for verification.
     *
     * @return immutable mapping from counter names to gas weights
     */
    public static Map<String, Long> counterWeights() {
        return WEIGHTS;
    }

    private static synchronized int nextSequence(
            RuntimeWorkSession session) {
        Integer current = NEXT_SEQUENCE.get(session);
        int sequence = current != null
                ? current.intValue()
                : 0;
        if (sequence == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Coordination runtime ledger sequence exhausted");
        }
        NEXT_SEQUENCE.put(
                session,
                Integer.valueOf(sequence + 1));
        return sequence;
    }

    private static synchronized Ledger acquire(
            RuntimeWorkSession session) {
        WeakReference<ActiveLedger> reference =
                ACTIVE_LEDGERS.get(session);
        ActiveLedger active = reference != null
                ? reference.get()
                : null;
        if (active != null
                && (active.submitted
                || active.handles == 0)) {
            ACTIVE_LEDGERS.remove(session);
            active = null;
        }
        if (active != null && active.abandoned) {
            throw new IllegalStateException(
                    "Abandoned Coordination runtime ledger is still closing");
        }
        Thread owner = Thread.currentThread();
        if (active != null && active.owner != owner) {
            throw new IllegalStateException(
                    "Concurrent Coordination runtime ledger ownership is not "
                            + "supported for one work session");
        }
        if (active == null) {
            int sequence = nextSequence(session);
            String physicalNamespace =
                    NAMESPACE + "." + String.format(
                    java.util.Locale.ROOT,
                    "%08d",
                    Integer.valueOf(sequence));
            active = new ActiveLedger(
                    session.openLedger(
                            physicalNamespace,
                            WEIGHTS),
                    owner);
            ACTIVE_LEDGERS.put(
                    session,
                    new WeakReference<ActiveLedger>(
                            active));
        }
        active.handles++;
        return new Ledger(
                session,
                active);
    }

    private static synchronized void submit(
            Ledger handle) {
        handle.ensureHandleOpen();
        handle.closed = true;
        ActiveLedger active = handle.active;
        active.handles--;
        boolean lastHandle = active.handles == 0;
        if (lastHandle) {
            removeActive(handle.session, active);
        }
        if (active.abandoned) {
            throw new IllegalStateException(
                    "Coordination runtime ledger was abandoned by nested work");
        }
        if (!lastHandle) {
            return;
        }
        try {
            handle.session.submit(active.ledger);
            active.submitted = true;
        } catch (RuntimeException | Error failure) {
            active.abandoned = true;
            throw failure;
        }
    }

    private static synchronized void abandon(
            Ledger handle) {
        if (handle.closed) {
            return;
        }
        handle.closed = true;
        ActiveLedger active = handle.active;
        active.abandoned = true;
        active.handles--;
        if (active.handles == 0) {
            removeActive(handle.session, active);
        }
    }

    private static void removeActive(
            RuntimeWorkSession session,
            ActiveLedger expected) {
        WeakReference<ActiveLedger> reference =
                ACTIVE_LEDGERS.get(session);
        if (reference == null
                || reference.get() == expected) {
            ACTIVE_LEDGERS.remove(session);
        }
    }

    private static Map<String, Long> loadWeights() {
        InputStream input = CoordinationRuntimeGas.class
                .getClassLoader()
                .getResourceAsStream(RESOURCE);
        if (input == null) {
            throw new ExceptionInInitializerError(
                    "Missing Coordination gas manifest " + RESOURCE);
        }
        Map<String, Long> weights =
                new LinkedHashMap<String, Long>();
        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     input,
                                     StandardCharsets.UTF_8))) {
            String pendingName = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- name:")) {
                    pendingName = requiredText(
                            trimmed.substring(
                                    "- name:".length()),
                            "counter name");
                } else if (pendingName != null
                        && trimmed.startsWith("weight:")) {
                    String raw = requiredText(
                            trimmed.substring(
                                    "weight:".length()),
                            "counter weight");
                    long weight = Long.parseLong(raw);
                    if (weight < 0L
                            || weights.put(
                            pendingName,
                            Long.valueOf(weight)) != null) {
                        throw new IllegalArgumentException(
                                "Invalid or duplicate Coordination gas counter "
                                        + pendingName);
                    }
                    pendingName = null;
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        if (weights.isEmpty()) {
            throw new ExceptionInInitializerError(
                    "Coordination gas manifest contains no counters");
        }
        return Collections.unmodifiableMap(weights);
    }

    private static String requiredText(
            String value,
            String label) {
        String exact = value != null
                ? value.trim()
                : "";
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return exact;
    }

    /**
     * One logical handle on an exactly-once-submitted Coordination child
     * ledger.
     */
    public static final class Ledger {
        private final RuntimeWorkSession session;
        private final ActiveLedger active;
        private boolean closed;

        private Ledger(
                RuntimeWorkSession session,
                ActiveLedger active) {
            this.session = session;
            this.active = active;
        }

        public void charge(
                String counter,
                long quantity,
                GasChargeContext context) {
            ensureOpen();
            if (!WEIGHTS.containsKey(counter)) {
                throw new IllegalArgumentException(
                        "Unknown Coordination gas counter " + counter);
            }
            synchronized (active) {
                ensureOpen();
                active.ledger.charge(
                        counter,
                        quantity,
                        context != null
                                ? context
                                : GasChargeContext.empty());
            }
        }

        public void submit() {
            CoordinationRuntimeGas.submit(this);
        }

        public boolean isSessionOpen() {
            return session.isOpen();
        }

        private void abandon() {
            CoordinationRuntimeGas.abandon(this);
        }

        private void ensureOpen() {
            ensureHandleOpen();
            if (active.owner != Thread.currentThread()) {
                throw new IllegalStateException(
                        "Coordination runtime ledger belongs to a different "
                                + "execution thread");
            }
            if (active.submitted
                    || active.abandoned) {
                throw new IllegalStateException(
                        "Coordination runtime ledger is already closed");
            }
        }

        private void ensureHandleOpen() {
            if (closed
                    || active.submitted) {
                throw new IllegalStateException(
                        "Coordination runtime ledger is already closed");
            }
        }
    }

    /** Work executed inside one reusable Coordination component ledger. */
    interface ComponentWork<T> {
        T run();
    }

    private static final class ActiveLedger {
        private final GasMeter.ChildGasLedger ledger;
        private int handles;
        private boolean submitted;
        private boolean abandoned;
        private final Thread owner;

        private ActiveLedger(
                GasMeter.ChildGasLedger ledger,
                Thread owner) {
            this.ledger = ledger;
            this.owner = owner;
        }
    }
}
