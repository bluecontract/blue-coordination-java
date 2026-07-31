package blue.coordination.processor;

import java.util.Objects;

/**
 * One deterministic, nonportable Coordination host-work observation.
 *
 * <p>Trace entries intentionally contain no elapsed time, serialized size, or
 * ambient host state. They are diagnostics and never contribute to portable
 * {@code PROCESS} gas.</p>
 */
public final class CoordinationHostQuotaTraceEntry {
    private final long sequence;
    private final String counter;
    private final long quantity;
    private final String operation;
    private final String logicalPath;
    private final String reason;

    CoordinationHostQuotaTraceEntry(
            long sequence,
            String counter,
            long quantity,
            String operation,
            String logicalPath,
            String reason) {
        this.sequence = sequence;
        this.counter = requireText(
                counter, "counter");
        if (quantity <= 0L) {
            throw new IllegalArgumentException(
                    "quantity must be positive");
        }
        this.quantity = quantity;
        this.operation = requireText(
                operation, "operation");
        this.logicalPath = requireText(
                logicalPath, "logicalPath");
        this.reason = requireText(
                reason, "reason");
    }

    public long sequence() {
        return sequence;
    }

    public String counter() {
        return counter;
    }

    public long quantity() {
        return quantity;
    }

    public String operation() {
        return operation;
    }

    public String logicalPath() {
        return logicalPath;
    }

    public String reason() {
        return reason;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other
                instanceof CoordinationHostQuotaTraceEntry)) {
            return false;
        }
        CoordinationHostQuotaTraceEntry that =
                (CoordinationHostQuotaTraceEntry) other;
        return sequence == that.sequence
                && quantity == that.quantity
                && counter.equals(that.counter)
                && operation.equals(that.operation)
                && logicalPath.equals(that.logicalPath)
                && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                Long.valueOf(sequence),
                counter,
                Long.valueOf(quantity),
                operation,
                logicalPath,
                reason);
    }

    @Override
    public String toString() {
        return sequence
                + ":"
                + counter
                + "["
                + quantity
                + "]@"
                + operation
                + ":"
                + logicalPath
                + "("
                + reason
                + ")";
    }

    private static String requireText(
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
}
