package blue.coordination.engine.api;

import java.util.Objects;

/** Safe structural-sharing key for one canonical direct fragment. */
public final class CoordinationFragmentEvidenceCacheKey {

    private final String environmentIdentity;
    private final String fragmentationProfileIdentity;
    private final String languageGenerationIdentity;
    private final String providerGenerationIdentity;
    private final String fragmentBlueId;

    public CoordinationFragmentEvidenceCacheKey(
            String environmentIdentity,
            String fragmentationProfileIdentity,
            String languageGenerationIdentity,
            String providerGenerationIdentity,
            String fragmentBlueId) {
        this.environmentIdentity = requireText(
                environmentIdentity, "environmentIdentity");
        this.fragmentationProfileIdentity = requireText(
                fragmentationProfileIdentity,
                "fragmentationProfileIdentity");
        this.languageGenerationIdentity = requireText(
                languageGenerationIdentity,
                "languageGenerationIdentity");
        this.providerGenerationIdentity = requireText(
                providerGenerationIdentity,
                "providerGenerationIdentity");
        this.fragmentBlueId = requireText(fragmentBlueId, "fragmentBlueId");
    }

    public String environmentIdentity() {
        return environmentIdentity;
    }

    public String fragmentationProfileIdentity() {
        return fragmentationProfileIdentity;
    }

    public String languageGenerationIdentity() {
        return languageGenerationIdentity;
    }

    public String providerGenerationIdentity() {
        return providerGenerationIdentity;
    }

    public String fragmentBlueId() {
        return fragmentBlueId;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof CoordinationFragmentEvidenceCacheKey)) {
            return false;
        }
        CoordinationFragmentEvidenceCacheKey other =
                (CoordinationFragmentEvidenceCacheKey) candidate;
        return environmentIdentity.equals(other.environmentIdentity)
                && fragmentationProfileIdentity.equals(
                        other.fragmentationProfileIdentity)
                && languageGenerationIdentity.equals(
                        other.languageGenerationIdentity)
                && providerGenerationIdentity.equals(
                        other.providerGenerationIdentity)
                && fragmentBlueId.equals(other.fragmentBlueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                environmentIdentity,
                fragmentationProfileIdentity,
                languageGenerationIdentity,
                providerGenerationIdentity,
                fragmentBlueId);
    }

    @Override
    public String toString() {
        return "CoordinationFragmentEvidenceCacheKey{"
                + environmentIdentity + ", "
                + fragmentationProfileIdentity + ", "
                + languageGenerationIdentity + ", "
                + providerGenerationIdentity + ", "
                + fragmentBlueId + "}";
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
