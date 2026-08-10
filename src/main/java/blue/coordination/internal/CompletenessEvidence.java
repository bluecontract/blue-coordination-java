package blue.coordination.internal;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Immutable proof identity for one exact, complete historical query. */
record CompletenessEvidence(
        long journalRevision,
        long routeIndexGeneration,
        long graphGeneration,
        ExternalOrderKey cutoffExclusive,
        String sourceSurfaceIdentity) {
    CompletenessEvidence {
        if (journalRevision < 0L || routeIndexGeneration < 0L
                || graphGeneration < 0L) {
            throw new IllegalArgumentException(
                    "completeness generations must be non-negative");
        }
        cutoffExclusive = Objects.requireNonNull(
                cutoffExclusive, "cutoffExclusive");
        sourceSurfaceIdentity = requireText(
                sourceSurfaceIdentity, "sourceSurfaceIdentity");
    }

    /** True only for the exact journal, route, graph, cutoff, and surface. */
    boolean isCurrentFor(
            long expectedJournalRevision,
            long expectedRouteIndexGeneration,
            long expectedGraphGeneration,
            ExternalOrderKey expectedCutoffExclusive,
            String expectedSourceSurfaceIdentity) {
        return journalRevision == expectedJournalRevision
                && routeIndexGeneration == expectedRouteIndexGeneration
                && graphGeneration == expectedGraphGeneration
                && cutoffExclusive.equals(Objects.requireNonNull(
                expectedCutoffExclusive, "expectedCutoffExclusive"))
                && sourceSurfaceIdentity.equals(requireText(
                expectedSourceSurfaceIdentity,
                "expectedSourceSurfaceIdentity"));
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
