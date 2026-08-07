package blue.coordination.engine.api;

/** Durable state of one event-to-session delivery in a host dispatch ledger. */
public enum CoordinationDeliveryStatus {
    PENDING,
    IN_FLIGHT,
    FAILED,
    COMMITTED
}
