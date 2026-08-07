package blue.coordination.examples.support;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Exact representation-independent Timeline/actor header identity pair. */
public record MyOsTimelineBinding(
        String timelineHeaderBlueId,
        String actorHeaderBlueId)
        implements Comparable<MyOsTimelineBinding> {

    public MyOsTimelineBinding {
        timelineHeaderBlueId = requireText(
                timelineHeaderBlueId, "timelineHeaderBlueId");
        actorHeaderBlueId = requireText(
                actorHeaderBlueId, "actorHeaderBlueId");
    }

    @Override
    public int compareTo(MyOsTimelineBinding other) {
        MyOsTimelineBinding checked = Objects.requireNonNull(other, "other");
        int compared = ExternalOrderKey.compareTextCodePoints(
                timelineHeaderBlueId,
                checked.timelineHeaderBlueId);
        return compared != 0
                ? compared
                : ExternalOrderKey.compareTextCodePoints(
                        actorHeaderBlueId,
                        checked.actorHeaderBlueId);
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank() || !checked.equals(checked.trim())) {
            throw new IllegalArgumentException(
                    name + " must be non-blank without outer whitespace");
        }
        return checked;
    }
}

