package blue.coordination.engine.api;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;

import java.util.Objects;

/** One exact authored leaf replacement in a precompiled event shape. */
public final class CoordinationEventShapePatch {
    private final String pointer;
    private final Node replacement;

    public CoordinationEventShapePatch(String pointer, Node replacement) {
        this.pointer = JsonPointer.canonicalize(
                Objects.requireNonNull(pointer, "pointer"));
        this.replacement = Objects.requireNonNull(
                replacement, "replacement").clone();
    }

    /** Creates a patch for one authored scalar leaf. */
    public static CoordinationEventShapePatch scalar(
            String pointer,
            Object value) {
        return new CoordinationEventShapePatch(
                pointer,
                new Node().value(Objects.requireNonNull(value, "value")));
    }

    /** Creates a patch for one authored exact-reference leaf. */
    public static CoordinationEventShapePatch reference(
            String pointer,
            String blueId) {
        String checked = Objects.requireNonNull(blueId, "blueId");
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException("blueId is blank");
        }
        return new CoordinationEventShapePatch(
                pointer, new Node().blueId(checked));
    }

    public String pointer() { return pointer; }

    public Node replacement() { return replacement.clone(); }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof CoordinationEventShapePatch)) return false;
        CoordinationEventShapePatch other =
                (CoordinationEventShapePatch) value;
        return pointer.equals(other.pointer)
                && replacement.equals(other.replacement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pointer, replacement);
    }

    @Override
    public String toString() {
        return "CoordinationEventShapePatch{pointer='" + pointer + "'}";
    }
}
