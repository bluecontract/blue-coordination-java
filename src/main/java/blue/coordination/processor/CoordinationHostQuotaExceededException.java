package blue.coordination.processor;

/**
 * Deterministic rejection of host work beyond a manifest-backed quota.
 */
public final class CoordinationHostQuotaExceededException
        extends IllegalArgumentException {
    private final String limitName;
    private final long limit;
    private final long attemptedQuantity;
    private final long admittedQuantity;

    CoordinationHostQuotaExceededException(
            String limitName,
            long limit,
            long attemptedQuantity,
            long admittedQuantity) {
        super("Coordination host quota "
                + limitName
                + " is "
                + limit
                + "; attempted "
                + attemptedQuantity
                + " after admitting "
                + admittedQuantity);
        this.limitName = limitName;
        this.limit = limit;
        this.attemptedQuantity = attemptedQuantity;
        this.admittedQuantity = admittedQuantity;
    }

    public String limitName() {
        return limitName;
    }

    public long limit() {
        return limit;
    }

    public long attemptedQuantity() {
        return attemptedQuantity;
    }

    public long admittedQuantity() {
        return admittedQuantity;
    }
}
