package blue.coordination.api;

import java.util.Objects;

/** Stable identity of one independently managed document process. */
public record DocumentId(String value) implements Comparable<DocumentId> {
    /** Validates a non-blank stable identity. */
    public DocumentId {
        value = requireText(value, "value");
    }

    /** Creates a validated document identity. */
    public static DocumentId of(String value) {
        return new DocumentId(value);
    }

    @Override
    public int compareTo(DocumentId other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
