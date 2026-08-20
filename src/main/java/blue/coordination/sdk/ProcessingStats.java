package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable semantic gas, work order, and bounded structural measurements. */
public record ProcessingStats(
        long gas,
        long committedTransitions,
        long documentsOpened,
        long elapsedNanos,
        List<DocumentId> documentStepOrder,
        Map<String, Long> counters) {
    private static final ProcessingStats ZERO = new ProcessingStats(
            0L, 0L, 0L, 0L, List.of(), Map.of());

    /** Defensively copies all collections and rejects negative measurements. */
    public ProcessingStats {
        SdkPreconditions.requireNonNegative(gas, "gas");
        SdkPreconditions.requireNonNegative(
                committedTransitions, "committedTransitions");
        SdkPreconditions.requireNonNegative(
                documentsOpened, "documentsOpened");
        SdkPreconditions.requireNonNegative(elapsedNanos, "elapsedNanos");
        documentStepOrder = List.copyOf(Objects.requireNonNull(
                documentStepOrder, "documentStepOrder"));
        Map<String, Long> copied = new LinkedHashMap<>();
        Objects.requireNonNull(counters, "counters").forEach(
                (name, value) -> copied.put(
                        SdkPreconditions.requireText(name, "counter name"),
                        SdkPreconditions.requireNonNegative(
                                Objects.requireNonNull(value,
                                        "counter value"),
                                "counter " + name)));
        counters = Collections.unmodifiableMap(copied);
    }

    /** Empty measurements for an entry that performed no processing work. */
    public static ProcessingStats zero() {
        return ZERO;
    }

    /** Returns a known counter, treating an absent optional counter as zero. */
    public long counter(String name) {
        return counters.getOrDefault(
                SdkPreconditions.requireText(name, "counter name"), 0L);
    }
}
