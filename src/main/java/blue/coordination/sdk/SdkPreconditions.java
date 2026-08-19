package blue.coordination.sdk;

import blue.language.model.wire.JsonPointer;

import java.util.Objects;

/** Shared validation for the small immutable SDK surface. */
final class SdkPreconditions {
    private SdkPreconditions() {
    }

    static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    static String requireOccurrencePath(String value) {
        String checked = JsonPointer.canonicalize(requireText(value, "path"));
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(
                    "path must be a non-root canonical JSON Pointer");
        }
        return checked;
    }

    static long requireNonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return value;
    }
}
