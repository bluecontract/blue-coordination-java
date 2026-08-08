package blue.coordination.fastpath;

import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Exact cache key for a semantically verified indexed plan. */
public final class PlanCacheKey {
    private final ProjectionGenerationKey generation;
    private final String sessionId;
    private final String eventBlueId;
    private final String eventInventoryIdentity;
    private final ExternalOrderKey eventOrderKey;
    private final List<String> orderedCandidates;
    private final String planningPolicyIdentity;
    private final int hashCode;

    public PlanCacheKey(
            ProjectionGenerationKey generation,
            String sessionId,
            String eventBlueId,
            String eventInventoryIdentity,
            ExternalOrderKey eventOrderKey,
            Collection<String> orderedCandidates,
            String planningPolicyIdentity) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.sessionId = text(sessionId, "sessionId");
        this.eventBlueId = text(eventBlueId, "eventBlueId");
        this.eventInventoryIdentity = text(
                eventInventoryIdentity, "eventInventoryIdentity");
        this.eventOrderKey = Objects.requireNonNull(
                eventOrderKey, "eventOrderKey");
        this.planningPolicyIdentity = text(planningPolicyIdentity, "planningPolicyIdentity");
        List<String> copy = new ArrayList<String>(
                Objects.requireNonNull(orderedCandidates, "orderedCandidates"));
        for (String candidate : copy) text(candidate, "candidate");
        this.orderedCandidates = Collections.unmodifiableList(copy);
        this.hashCode = Objects.hash(
                this.generation,
                this.sessionId,
                this.eventBlueId,
                this.eventInventoryIdentity,
                this.eventOrderKey,
                this.orderedCandidates,
                this.planningPolicyIdentity);
    }

    public ProjectionGenerationKey generation() { return generation; }
    public String sessionId() { return sessionId; }
    public String eventBlueId() { return eventBlueId; }
    public String eventInventoryIdentity() { return eventInventoryIdentity; }
    public ExternalOrderKey eventOrderKey() { return eventOrderKey; }
    public List<String> orderedCandidates() { return orderedCandidates; }
    public String planningPolicyIdentity() { return planningPolicyIdentity; }

    @Override
    public boolean equals(Object supplied) {
        if (this == supplied) return true;
        if (!(supplied instanceof PlanCacheKey)) return false;
        PlanCacheKey other = (PlanCacheKey) supplied;
        return generation.equals(other.generation)
                && sessionId.equals(other.sessionId)
                && eventBlueId.equals(other.eventBlueId)
                && eventInventoryIdentity.equals(
                        other.eventInventoryIdentity)
                && eventOrderKey.equals(other.eventOrderKey)
                && orderedCandidates.equals(other.orderedCandidates)
                && planningPolicyIdentity.equals(other.planningPolicyIdentity);
    }

    @Override
    public int hashCode() { return hashCode; }

    private static String text(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
        return value;
    }
}
