package blue.coordination.engine.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class CoordinationEventAdmissionCacheKeyTest {

    @Test
    void everyEvidenceDomainParticipatesInEquality() {
        CoordinationEventAdmissionCacheKey base = key(
                "env", "profile", "language", "provider", "event");
        assertEquals(base, key(
                "env", "profile", "language", "provider", "event"));
        assertNotEquals(base, key(
                "other", "profile", "language", "provider", "event"));
        assertNotEquals(base, key(
                "env", "other", "language", "provider", "event"));
        assertNotEquals(base, key(
                "env", "profile", "other", "provider", "event"));
        assertNotEquals(base, key(
                "env", "profile", "language", "other", "event"));
        assertNotEquals(base, key(
                "env", "profile", "language", "provider", "other"));
    }

    @Test
    void diagnosticIdentityIsUnambiguousForEmbeddedSeparators() {
        assertNotEquals(
                key("a:b", "c", "d", "e", "f")
                        .diagnosticIdentity(),
                key("a", "b:c", "d", "e", "f")
                        .diagnosticIdentity());
    }

    private static CoordinationEventAdmissionCacheKey key(
            String environment,
            String profile,
            String language,
            String provider,
            String event) {
        return new CoordinationEventAdmissionCacheKey(
                environment, profile, language, provider, event);
    }
}
