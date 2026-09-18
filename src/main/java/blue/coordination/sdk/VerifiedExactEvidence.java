package blue.coordination.sdk;

import blue.coordination.api.ExactValue;
import blue.language.codec.jackson.UncheckedObjectMapper;
import java.util.List;
import java.util.Optional;

/**
 * Library-issued immutable transport of one already authenticated exact value.
 * Unlike {@link ExactNodeEvidence}, callers cannot construct a claimed proof.
 * This proves content only, never current storage membership or publication authority.
 * Persisted text must be authenticated again after a cold restart; this object is
 * an in-process capability, not a signature or a durable trust flag.
 */
public final class VerifiedExactEvidence {
    private final String blueId;
    private final String exactContent;
    private final List<String> declaredPlaceholderSet;

    private VerifiedExactEvidence(ExactValue value) {
        blueId = value.blueId();
        exactContent = UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(value.copyNode());
        declaredPlaceholderSet = value.cyclicSetProof().map(proof -> proof.declaredPlaceholderSet().stream()
                .map(UncheckedObjectMapper.JSON_MAPPER::writeValueAsString).toList()).orElse(null);
    }

    static VerifiedExactEvidence issue(ExactValue value) { return new VerifiedExactEvidence(value); }

    /** Returns the exact identity authenticated together with this body and proof. */
    public String blueId() { return blueId; }

    /** Returns exactly the SDK JSON representation, without host-specific key sorting. */
    public String exactContent() { return exactContent; }

    /** Returns the complete immutable ordered proof, or empty for an ordinary value. */
    public Optional<List<String>> declaredPlaceholderSet() { return Optional.ofNullable(declaredPlaceholderSet); }

    long textBytes() {
        long bytes = 2L * (blueId.length() + (long) exactContent.length());
        if (declaredPlaceholderSet != null) for (String member : declaredPlaceholderSet)
            bytes = ManagedEpochHistory.addWeight(bytes, 2L * member.length());
        return bytes;
    }
}
