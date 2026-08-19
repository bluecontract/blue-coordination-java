package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Immutable READY-only application view of one managed document. */
public record DocumentSnapshot(
        DocumentId id,
        long epoch,
        boolean ready,
        ExactBlueValue exact,
        List<PublicEvent> publicEvents) {
    /** Defensively copies events and rejects non-READY internal state. */
    public DocumentSnapshot {
        id = Objects.requireNonNull(id, "id");
        SdkPreconditions.requireNonNegative(epoch, "epoch");
        if (!ready) {
            throw new IllegalArgumentException(
                    "Normal SDK snapshots expose READY state only");
        }
        exact = Objects.requireNonNull(exact, "exact");
        publicEvents = List.copyOf(Objects.requireNonNull(
                publicEvents, "publicEvents"));
    }

    /** Exact current document BlueId. */
    public String blueId() {
        return exact.blueId();
    }

    /** Selects one exact value by canonical JSON Pointer. */
    public ExactBlueValue valueAt(String pointer) {
        return exact.valueAt(pointer);
    }

    /** Reads one integral scalar exactly by canonical JSON Pointer. */
    public long longAt(String pointer) {
        Object scalar = exact.scalarAt(pointer);
        if (scalar instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (scalar instanceof Byte || scalar instanceof Short
                || scalar instanceof Integer || scalar instanceof Long) {
            return ((Number) scalar).longValue();
        }
        throw new IllegalArgumentException(
                "Value at " + pointer + " is not an integral scalar");
    }

    /** Reads one text scalar exactly by canonical JSON Pointer. */
    public String textAt(String pointer) {
        Object scalar = exact.scalarAt(pointer);
        if (scalar instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(
                "Value at " + pointer + " is not text");
    }

    /** Reads one boolean scalar exactly by canonical JSON Pointer. */
    public boolean booleanAt(String pointer) {
        Object scalar = exact.scalarAt(pointer);
        if (scalar instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException(
                "Value at " + pointer + " is not boolean");
    }
}
