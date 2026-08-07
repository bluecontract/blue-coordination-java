package blue.coordination.processor.delivery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Package-owned immutable implementation of verified delivery evidence. */
final class ImmutableCoordinationDeliveryDiagnostic
        implements CoordinationDeliveryDiagnosticView {
    private final String occurrenceKey;
    private final String scopePath;
    private final String sourceChannelKey;
    private final String sourceEffectiveTypeBlueId;
    private final String sourceHeaderBlueId;
    private final List<String> sourceContributionBlueIds;
    private final String checkpointDomainBlueId;
    private final String checkpointSubjectBlueId;
    private final String payloadBlueId;
    private final String targetChannelKey;
    private final String targetEffectiveTypeBlueId;
    private final String targetHeaderBlueId;
    private final List<String> targetContributionBlueIds;
    private final String logicalDeliveryKey;
    private final List<String> dependencyBlueIds;

    ImmutableCoordinationDeliveryDiagnostic(
            String occurrenceKey,
            String scopePath,
            String sourceChannelKey,
            String sourceEffectiveTypeBlueId,
            String sourceHeaderBlueId,
            List<String> sourceContributionBlueIds,
            String checkpointDomainBlueId,
            String checkpointSubjectBlueId,
            String payloadBlueId,
            String targetChannelKey,
            String targetEffectiveTypeBlueId,
            String targetHeaderBlueId,
            List<String> targetContributionBlueIds,
            String logicalDeliveryKey,
            List<String> dependencyBlueIds) {
        this.occurrenceKey = requireText(occurrenceKey, "occurrenceKey");
        this.scopePath = requireText(scopePath, "scopePath");
        this.sourceChannelKey = requireText(
                sourceChannelKey, "sourceChannelKey");
        this.sourceEffectiveTypeBlueId = requireText(
                sourceEffectiveTypeBlueId, "sourceEffectiveTypeBlueId");
        this.sourceHeaderBlueId = requireText(
                sourceHeaderBlueId, "sourceHeaderBlueId");
        this.sourceContributionBlueIds = immutableText(
                sourceContributionBlueIds, "source contribution BlueId");
        this.checkpointDomainBlueId = requireText(
                checkpointDomainBlueId, "checkpointDomainBlueId");
        this.checkpointSubjectBlueId = requireText(
                checkpointSubjectBlueId, "checkpointSubjectBlueId");
        this.payloadBlueId = nullableText(payloadBlueId, "payloadBlueId");
        this.targetChannelKey = nullableText(
                targetChannelKey, "targetChannelKey");
        this.targetEffectiveTypeBlueId = nullableText(
                targetEffectiveTypeBlueId, "targetEffectiveTypeBlueId");
        this.targetHeaderBlueId = nullableText(
                targetHeaderBlueId, "targetHeaderBlueId");
        this.targetContributionBlueIds = immutableText(
                targetContributionBlueIds, "target contribution BlueId");
        this.logicalDeliveryKey = nullableText(
                logicalDeliveryKey, "logicalDeliveryKey");
        this.dependencyBlueIds = immutableText(
                dependencyBlueIds, "dependency BlueId");
        validateTarget();
    }

    public String occurrenceKey() { return occurrenceKey; }

    public String scopePath() { return scopePath; }

    public String sourceChannelKey() { return sourceChannelKey; }

    public String sourceEffectiveTypeBlueId() {
        return sourceEffectiveTypeBlueId;
    }

    public String sourceHeaderBlueId() { return sourceHeaderBlueId; }

    public List<String> sourceContributionBlueIds() {
        return sourceContributionBlueIds;
    }

    public String checkpointDomainBlueId() {
        return checkpointDomainBlueId;
    }

    public String checkpointSubjectBlueId() {
        return checkpointSubjectBlueId;
    }

    public String payloadBlueId() { return payloadBlueId; }

    public String targetChannelKey() { return targetChannelKey; }

    public String targetEffectiveTypeBlueId() {
        return targetEffectiveTypeBlueId;
    }

    public String targetHeaderBlueId() { return targetHeaderBlueId; }

    public List<String> targetContributionBlueIds() {
        return targetContributionBlueIds;
    }

    public String logicalDeliveryKey() { return logicalDeliveryKey; }

    public List<String> dependencyBlueIds() { return dependencyBlueIds; }

    private void validateTarget() {
        boolean routed = targetChannelKey != null;
        if (routed != (targetEffectiveTypeBlueId != null)
                || routed != (targetHeaderBlueId != null)) {
            throw new IllegalArgumentException(
                    "A routed target requires its key, type, and header "
                            + "identity together");
        }
        if (!routed && !targetContributionBlueIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "An unrouted delivery cannot carry target contributions");
        }
    }

    private static List<String> immutableText(
            List<String> source,
            String label) {
        Objects.requireNonNull(source, label + " list");
        List<String> copy = new ArrayList<>(source.size());
        for (String value : source) {
            copy.add(requireText(value, label));
        }
        return Collections.unmodifiableList(copy);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }

    private static String nullableText(String value, String label) {
        return value == null ? null : requireText(value, label);
    }
}
