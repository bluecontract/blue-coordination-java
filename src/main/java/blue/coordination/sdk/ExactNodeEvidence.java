package blue.coordination.sdk;

import blue.language.provider.CyclicSetProof;

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
    private final CyclicSetProof cyclicSetProof;

    private ExactNodeEvidence(
            String exactContent,
            CyclicSetProof cyclicSetProof) {
        this.exactContent = requireText(exactContent, "exactContent");
        this.cyclicSetProof = cyclicSetProof;
    }

    /** Creates ordinary acyclic provider evidence. */
    public static ExactNodeEvidence ordinary(String exactContent) {
        return new ExactNodeEvidence(exactContent, null);
    }

    /** Creates cyclic-member evidence carrying the complete placeholder set. */
    public static ExactNodeEvidence cyclic(
            String exactContent,
            CyclicSetProof completeProof) {
        return new ExactNodeEvidence(
                exactContent,
                Objects.requireNonNull(completeProof, "completeProof"));
    }

    /** Returns the serialized exact body. */
    public String exactContent() {
        return exactContent;
    }

    /** Returns the claimed proof for re-verification, when cyclic. */
    public Optional<CyclicSetProof> cyclicSetProof() {
        return Optional.ofNullable(cyclicSetProof);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
