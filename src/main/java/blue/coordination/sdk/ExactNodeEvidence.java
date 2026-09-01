package blue.coordination.sdk;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One serialized whole exact value and, when cyclic, its complete verified
 * placeholder-set proof.
 *
 * <p>The proof is evidence only. Coordination authenticates it together with
 * the requested member identity and returned body before admitting the value;
 * an application-supplied {@code MASTER#n} claim is never trusted directly.</p>
 */
public final class ExactNodeEvidence {
    private final String exactContent;
    private final List<String> declaredPlaceholderSet;

    private ExactNodeEvidence(
            String exactContent,
            List<String> declaredPlaceholderSet) {
        this.exactContent = requireText(exactContent, "exactContent");
        if (declaredPlaceholderSet == null) {
            this.declaredPlaceholderSet = null;
        } else {
            if (declaredPlaceholderSet.isEmpty()) {
                throw new IllegalArgumentException(
                        "completeDeclaredPlaceholderSet must not be empty");
            }
            this.declaredPlaceholderSet = declaredPlaceholderSet.stream()
                    .map(value -> requireText(
                            value, "declaredPlaceholderSet member"))
                    .toList();
        }
    }

    /** Creates ordinary acyclic provider evidence. */
    public static ExactNodeEvidence ordinary(String exactContent) {
        return new ExactNodeEvidence(exactContent, null);
    }

    /** Creates cyclic-member evidence carrying the complete placeholder set. */
    public static ExactNodeEvidence cyclic(
            String exactContent,
            List<String> completeDeclaredPlaceholderSet) {
        return new ExactNodeEvidence(
                exactContent,
                Objects.requireNonNull(
                        completeDeclaredPlaceholderSet,
                        "completeDeclaredPlaceholderSet"));
    }

    /** Returns the serialized exact body. */
    public String exactContent() {
        return exactContent;
    }

    /**
     * Returns the claimed complete placeholder-set bodies for
     * re-verification, when cyclic.
     */
    public Optional<List<String>> declaredPlaceholderSet() {
        return Optional.ofNullable(declaredPlaceholderSet);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
