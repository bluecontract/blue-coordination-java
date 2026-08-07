package blue.coordination.engine.api;

import java.util.Objects;

/**
 * Complete domain key for reusable event-preparation evidence.
 *
 * <p>An event BlueId alone is not a safe cache key. Physical evidence also
 * depends on the owning environment, fragmentation profile, Language
 * generation, and provider generation.</p>
 */
public final class CoordinationEventAdmissionCacheKey {

    private final String environmentIdentity;
    private final String fragmentationProfileIdentity;
    private final String languageGenerationIdentity;
    private final String providerGenerationIdentity;
    private final String eventBlueId;

    public CoordinationEventAdmissionCacheKey(
            String environmentIdentity,
            String fragmentationProfileIdentity,
            String languageGenerationIdentity,
            String providerGenerationIdentity,
            String eventBlueId) {
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
        this.eventBlueId = requireText(eventBlueId, "eventBlueId");
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

    public String eventBlueId() {
        return eventBlueId;
    }

    /** A compact, delimiter-safe diagnostic identity. */
    public String diagnosticIdentity() {
        return field(environmentIdentity)
                + field(fragmentationProfileIdentity)
                + field(languageGenerationIdentity)
                + field(providerGenerationIdentity)
                + field(eventBlueId);
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof CoordinationEventAdmissionCacheKey)) {
            return false;
        }
        CoordinationEventAdmissionCacheKey other =
                (CoordinationEventAdmissionCacheKey) candidate;
        return environmentIdentity.equals(other.environmentIdentity)
                && fragmentationProfileIdentity.equals(
                        other.fragmentationProfileIdentity)
                && languageGenerationIdentity.equals(
                        other.languageGenerationIdentity)
                && providerGenerationIdentity.equals(
                        other.providerGenerationIdentity)
                && eventBlueId.equals(other.eventBlueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                environmentIdentity,
                fragmentationProfileIdentity,
                languageGenerationIdentity,
                providerGenerationIdentity,
                eventBlueId);
    }

    @Override
    public String toString() {
        return "CoordinationEventAdmissionCacheKey{" + diagnosticIdentity()
                + "}";
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
