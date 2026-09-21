package blue.coordination.api;

import blue.language.processor.ExternalOrderKey;
import java.util.Objects;
import java.util.Set;

/**
 * Attempt-bound external completeness authority for the native retained journal.
 * This callback supplies physical evidence, never selects or executes an input.
 * All requested original entries must already be retained without gaps or page
 * truncation. The host must read coverage, permissions and entries coherently and
 * validate their facts in the same final transaction as the native publication.
 */
@FunctionalInterface
public interface TimelineHistoryCoverage {
    /**
     * Checks every named Timeline through the exact full-order boundary.
     * A null boundary requests unbounded completeness; a finite timestamp
     * guarantee cannot establish it. Inclusive boundaries include all equal-order
     * input, and an exclusive timestamp T does not cover arbitrary ties at T.
     */
    Evidence inspect(Set<String> timelineIds, ExternalOrderKey boundary, boolean inclusive);

    /** Complete evidence identity is host-authenticated, immutable and bound to this exact request. */
    record Evidence(TimelineJournalStore.Availability availability, String identity) {
        public Evidence {
            Objects.requireNonNull(availability, "availability");
            if (identity == null || identity.isBlank()) throw new IllegalArgumentException("coverage identity is required");
        }
        public static Evidence complete(String identity) {
            return new Evidence(TimelineJournalStore.Availability.available(), identity);
        }
        public static Evidence unavailable(String identity, String diagnostic) {
            return new Evidence(new TimelineJournalStore.Availability(
                    TimelineJournalStore.AvailabilityKind.UNAVAILABLE, diagnostic), identity);
        }
        public static Evidence invalid(String identity, String diagnostic) {
            return new Evidence(new TimelineJournalStore.Availability(
                    TimelineJournalStore.AvailabilityKind.INVALID_EVIDENCE, diagnostic), identity);
        }
    }
}
