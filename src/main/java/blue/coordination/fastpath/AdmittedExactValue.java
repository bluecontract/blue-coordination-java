package blue.coordination.fastpath;

import java.util.Objects;
import java.util.function.Function;

/**
 * Identity-verified read lease for an engine-owned immutable-by-convention
 * object. The expensive identity calculation happens once at admission, not
 * again for every plan, evidence object, transition and cache installation.
 */
public final class AdmittedExactValue<T> {
    private final String blueId;
    private final String inventoryIdentity;
    private final T value;

    private AdmittedExactValue(String blueId, String inventoryIdentity, T value) {
        this.blueId = blueId;
        this.inventoryIdentity = inventoryIdentity;
        this.value = value;
    }

    public static <T> AdmittedExactValue<T> verifyAndAdmit(
            String expectedBlueId,
            String inventoryIdentity,
            T value,
            Function<? super T, String> identityCalculator) {
        String expected = AdmittedOccurrence.text(expectedBlueId, "expectedBlueId");
        String inventory = AdmittedOccurrence.text(
                inventoryIdentity, "inventoryIdentity");
        T exact = Objects.requireNonNull(value, "value");
        String calculated = AdmittedOccurrence.text(
                Objects.requireNonNull(identityCalculator, "identityCalculator")
                        .apply(exact),
                "calculatedBlueId");
        if (!expected.equals(calculated)) {
            throw new IllegalArgumentException(
                    "admitted exact value identity mismatch: expected="
                            + expected + ", actual=" + calculated);
        }
        return new AdmittedExactValue<T>(expected, inventory, exact);
    }

    public String blueId() { return blueId; }
    public String inventoryIdentity() { return inventoryIdentity; }

    /** Internal read-only access; callers must not expose or mutate this value. */
    public T retainedValue() { return value; }

    public void requireBinding(String expectedBlueId, String expectedInventory) {
        if (!blueId.equals(expectedBlueId)
                || !inventoryIdentity.equals(expectedInventory)) {
            throw new IllegalArgumentException("admitted exact value binding mismatch");
        }
    }
}
