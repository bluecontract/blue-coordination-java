package blue.coordination.processor.delivery;

import java.util.List;

/** Read-only delivery evidence consumed by the public Coordination facade. */
public interface CoordinationDeliveryDiagnosticView {
    String occurrenceKey();

    String scopePath();

    String sourceChannelKey();

    String sourceEffectiveTypeBlueId();

    String sourceHeaderBlueId();

    List<String> sourceContributionBlueIds();

    String checkpointDomainBlueId();

    String checkpointSubjectBlueId();

    String payloadBlueId();

    String targetChannelKey();

    String targetEffectiveTypeBlueId();

    String targetHeaderBlueId();

    List<String> targetContributionBlueIds();

    String logicalDeliveryKey();

    List<String> dependencyBlueIds();
}
