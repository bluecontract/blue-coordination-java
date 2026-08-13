package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.repo.coordination.Actor;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single current generated Repository identity profile used by Coordination.
 *
 * <p>The profile is derived directly from the generated current Repository
 * classes. It deliberately has no version switch, legacy alias, fallback
 * coordinate, or compatibility lookup. Explicitly validated custom semantic
 * identities remain a separate isolated-runtime facility in
 * {@link CoordinationSemanticTypeIdentities}.</p>
 */
public final class CoordinationCurrentRepositoryIdentities {

    private static final CoordinationCurrentRepositoryIdentities CURRENT =
            new CoordinationCurrentRepositoryIdentities(
                    TimelineEntry.blueId(),
                    OperationRequest.blueId(),
                    Timeline.blueId(),
                    Actor.blueId(),
                    TimelineChannel.blueId(),
                    AllTimelinesChannel.blueId(),
                    CompositeTimelineChannel.blueId());

    private final String timelineEntryBlueId;
    private final String operationRequestBlueId;
    private final String timelineBlueId;
    private final String actorBlueId;
    private final String timelineChannelBlueId;
    private final String allTimelinesChannelBlueId;
    private final String compositeTimelineChannelBlueId;
    private final String profileIdentity;

    private CoordinationCurrentRepositoryIdentities(
            String timelineEntryBlueId,
            String operationRequestBlueId,
            String timelineBlueId,
            String actorBlueId,
            String timelineChannelBlueId,
            String allTimelinesChannelBlueId,
            String compositeTimelineChannelBlueId) {
        this.timelineEntryBlueId = requireText(
                timelineEntryBlueId, "timelineEntryBlueId");
        this.operationRequestBlueId = requireText(
                operationRequestBlueId, "operationRequestBlueId");
        this.timelineBlueId = requireText(timelineBlueId, "timelineBlueId");
        this.actorBlueId = requireText(actorBlueId, "actorBlueId");
        this.timelineChannelBlueId = requireText(
                timelineChannelBlueId, "timelineChannelBlueId");
        this.allTimelinesChannelBlueId = requireText(
                allTimelinesChannelBlueId, "allTimelinesChannelBlueId");
        this.compositeTimelineChannelBlueId = requireText(
                compositeTimelineChannelBlueId,
                "compositeTimelineChannelBlueId");
        this.profileIdentity = DirectBlueIdCalculator.calculateBlueId(
                new Node()
                        .properties("kind", new Node().value(
                                "blue.coordination/current-repository-ids/1"))
                        .properties("timelineEntry", new Node().value(
                                this.timelineEntryBlueId))
                        .properties("operationRequest", new Node().value(
                                this.operationRequestBlueId))
                        .properties("timeline", new Node().value(
                                this.timelineBlueId))
                        .properties("actor", new Node().value(
                                this.actorBlueId))
                        .properties("timelineChannel", new Node().value(
                                this.timelineChannelBlueId))
                        .properties("allTimelinesChannel", new Node().value(
                                this.allTimelinesChannelBlueId))
                        .properties(
                                "compositeTimelineChannel",
                                new Node().value(
                                        this.compositeTimelineChannelBlueId)));
    }

    /** Returns the process-wide immutable current generated profile. */
    public static CoordinationCurrentRepositoryIdentities current() {
        return CURRENT;
    }

    public String timelineEntryBlueId() {
        return timelineEntryBlueId;
    }

    public String operationRequestBlueId() {
        return operationRequestBlueId;
    }

    public String timelineBlueId() {
        return timelineBlueId;
    }

    public String actorBlueId() {
        return actorBlueId;
    }

    public String timelineChannelBlueId() {
        return timelineChannelBlueId;
    }

    public String allTimelinesChannelBlueId() {
        return allTimelinesChannelBlueId;
    }

    public String compositeTimelineChannelBlueId() {
        return compositeTimelineChannelBlueId;
    }

    /** Identity of the complete immutable seven-value profile. */
    public String profileIdentity() {
        return profileIdentity;
    }

    /** Stable diagnostics view; live code should use the typed accessors. */
    public Map<String, String> asMap() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("TimelineEntry", timelineEntryBlueId);
        result.put("OperationRequest", operationRequestBlueId);
        result.put("Timeline", timelineBlueId);
        result.put("Actor", actorBlueId);
        result.put("TimelineChannel", timelineChannelBlueId);
        result.put("AllTimelinesChannel", allTimelinesChannelBlueId);
        result.put("CompositeTimelineChannel", compositeTimelineChannelBlueId);
        return Collections.unmodifiableMap(result);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty() || !checked.equals(checked.trim())) {
            throw new IllegalArgumentException(
                    label + " must be exact non-blank text");
        }
        return checked;
    }
}
