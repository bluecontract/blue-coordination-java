package blue.coordination.engine.api;

import java.util.Objects;

/** Stable host identity of one independently managed document session. */
public final class DocumentSessionId implements Comparable<DocumentSessionId> {

    private final String value;

    private DocumentSessionId(String value) {
        String checked = Objects.requireNonNull(value, "value");
        if (checked.isEmpty() || !checked.equals(checked.trim())) {
            throw new IllegalArgumentException(
                    "Document session identity must be non-empty and cannot "
                            + "have surrounding whitespace");
        }
        this.value = checked;
    }

    /** Creates an identity supplied by the host, never inferred from BlueId. */
    public static DocumentSessionId of(String value) {
        return new DocumentSessionId(value);
    }

    /** Returns the exact host identity. */
    public String value() {
        return value;
    }

    @Override
    public int compareTo(DocumentSessionId other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof DocumentSessionId
                && value.equals(((DocumentSessionId) other).value));
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
