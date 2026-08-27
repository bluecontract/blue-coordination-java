package blue.coordination.sdk;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable SDK-native canonical external source-order tuple. */
public record SourceOrder(List<Object> components)
        implements Comparable<SourceOrder> {

    /** Validates and canonicalizes every Integer or Text component. */
    public SourceOrder {
        ArrayList<Object> canonical = new ArrayList<>();
        for (Object component : Objects.requireNonNull(
                components, "components")) {
            canonical.add(canonicalComponent(component));
        }
        components = List.copyOf(canonical);
    }

    /** Compares tuples lexicographically using canonical scalar semantics. */
    @Override
    public int compareTo(SourceOrder other) {
        SourceOrder right = Objects.requireNonNull(other, "other");
        int shared = Math.min(components.size(), right.components.size());
        for (int index = 0; index < shared; index++) {
            int compared = compareComponent(
                    components.get(index), right.components.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(components.size(), right.components.size());
    }

    private static Object canonicalComponent(Object component) {
        Object selected = Objects.requireNonNull(
                component, "source-order component");
        if (selected instanceof BigInteger integer) {
            return integer;
        }
        if (selected instanceof Byte || selected instanceof Short
                || selected instanceof Integer || selected instanceof Long) {
            return BigInteger.valueOf(((Number) selected).longValue());
        }
        if (selected instanceof String) {
            return selected;
        }
        throw new IllegalArgumentException(
                "Source-order components must be Integer or Text");
    }

    private static int compareComponent(Object left, Object right) {
        if (left instanceof BigInteger leftInteger
                && right instanceof BigInteger rightInteger) {
            return leftInteger.compareTo(rightInteger);
        }
        if (left instanceof String leftText
                && right instanceof String rightText) {
            return compareTextCodePoints(leftText, rightText);
        }
        return left instanceof BigInteger ? -1 : 1;
    }

    private static int compareTextCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) {
                return Integer.compare(leftPoint, rightPoint);
            }
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(
                left.length() - leftIndex,
                right.length() - rightIndex);
    }
}
