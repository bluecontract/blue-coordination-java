package blue.coordination.examples.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Monotonic phase timings captured by one successful document start. */
public record MyOsDocumentStartTiming(
        String documentKey,
        long totalNanos,
        long resolveReferencesNanos,
        long parseSourceNanos,
        long canonicalIdentityNanos,
        long admissionPlanningNanos,
        long initializeOnceNanos,
        long hostPublicationNanos,
        Initialization initialization) {

    public MyOsDocumentStartTiming {
        documentKey = requireText(documentKey, "documentKey");
        requireNonNegative(totalNanos, "totalNanos");
        requireNonNegative(resolveReferencesNanos,
                "resolveReferencesNanos");
        requireNonNegative(parseSourceNanos, "parseSourceNanos");
        requireNonNegative(canonicalIdentityNanos,
                "canonicalIdentityNanos");
        requireNonNegative(admissionPlanningNanos,
                "admissionPlanningNanos");
        requireNonNegative(initializeOnceNanos,
                "initializeOnceNanos");
        requireNonNegative(hostPublicationNanos,
                "hostPublicationNanos");
        initialization = Objects.requireNonNull(
                initialization, "initialization");
        if (initialization.attributedNanos() > initializeOnceNanos) {
            throw new IllegalArgumentException(
                    "initialization phases exceed initialize-once time");
        }
        if (topLevelAttributedNanos(
                resolveReferencesNanos,
                parseSourceNanos,
                canonicalIdentityNanos,
                admissionPlanningNanos,
                initializeOnceNanos,
                hostPublicationNanos) > totalNanos) {
            throw new IllegalArgumentException(
                    "document-start phases exceed total time");
        }
    }

    /**
     * Ordered, non-overlapping phases whose values sum to
     * {@code totalNanos}.
     */
    public Map<String, Long> detailedPhases() {
        Map<String, Long> phases = new LinkedHashMap<>();
        phases.put("resolve initial references", resolveReferencesNanos);
        phases.put("parse authored YAML", parseSourceNanos);
        phases.put("canonical identity and exact registration",
                canonicalIdentityNanos);
        phases.put("embedding and topology preflight",
                admissionPlanningNanos);
        phases.putAll(initialization.detailedPhases());
        phases.put("initialize-once coordination overhead",
                initializeOnceNanos - initialization.attributedNanos());
        phases.put("host publication, routing, and evidence",
                hostPublicationNanos);
        phases.put("document-start timing overhead",
                totalNanos - topLevelAttributedNanos());
        return Collections.unmodifiableMap(phases);
    }

    private long topLevelAttributedNanos() {
        return topLevelAttributedNanos(
                resolveReferencesNanos,
                parseSourceNanos,
                canonicalIdentityNanos,
                admissionPlanningNanos,
                initializeOnceNanos,
                hostPublicationNanos);
    }

    private static long topLevelAttributedNanos(
            long resolveReferences,
            long parseSource,
            long canonicalIdentity,
            long admissionPlanning,
            long initializeOnce,
            long hostPublication) {
        return Math.addExact(
                Math.addExact(
                        Math.addExact(resolveReferences, parseSource),
                        Math.addExact(canonicalIdentity,
                                admissionPlanning)),
                Math.addExact(initializeOnce, hostPublication));
    }

    /** Timings captured inside the initialize-once operation. */
    public record Initialization(
            long preprocessNanos,
            long resolveSourceSnapshotNanos,
            long evidencePreparationNanos,
            long frozenInitializeNanos,
            long resolveInitializedSnapshotNanos,
            long engineAdmissionNanos,
            long bookkeepingNanos) {

        public Initialization {
            requireNonNegative(preprocessNanos, "preprocessNanos");
            requireNonNegative(resolveSourceSnapshotNanos,
                    "resolveSourceSnapshotNanos");
            requireNonNegative(evidencePreparationNanos,
                    "evidencePreparationNanos");
            requireNonNegative(frozenInitializeNanos,
                    "frozenInitializeNanos");
            requireNonNegative(resolveInitializedSnapshotNanos,
                    "resolveInitializedSnapshotNanos");
            requireNonNegative(engineAdmissionNanos,
                    "engineAdmissionNanos");
            requireNonNegative(bookkeepingNanos, "bookkeepingNanos");
        }

        public long attributedNanos() {
            long first = Math.addExact(
                    Math.addExact(preprocessNanos,
                            resolveSourceSnapshotNanos),
                    Math.addExact(evidencePreparationNanos,
                            frozenInitializeNanos));
            long second = Math.addExact(
                    Math.addExact(resolveInitializedSnapshotNanos,
                            engineAdmissionNanos),
                    bookkeepingNanos);
            return Math.addExact(first, second);
        }

        private Map<String, Long> detailedPhases() {
            Map<String, Long> phases = new LinkedHashMap<>();
            phases.put("preprocess authored document", preprocessNanos);
            phases.put("resolve initialization snapshot",
                    resolveSourceSnapshotNanos);
            phases.put("prepare initialization evidence",
                    evidencePreparationNanos);
            phases.put("frozen Contracts initialization",
                    frozenInitializeNanos);
            phases.put("resolve initialized snapshot",
                    resolveInitializedSnapshotNanos);
            phases.put("epoch-zero split, store, and index admission",
                    engineAdmissionNanos);
            phases.put("cache initialized document", bookkeepingNanos);
            return phases;
        }
    }

    private static void requireNonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    label + " must be non-negative");
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank() || !checked.equals(checked.trim())) {
            throw new IllegalArgumentException(label + " must be exact text");
        }
        return checked;
    }
}
