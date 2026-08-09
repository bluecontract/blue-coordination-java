package blue.coordination.basic.engine;

import blue.language.processor.EmbeddedScopePlanView;

import java.util.Objects;

/** One physical boundary created only for an effective Process Embedded path. */
public record EmbeddedBoundary(
        String parentScopePath,
        String childScopePath,
        String childBlueId,
        EmbeddedScopePlanView.Origin origin,
        boolean splitterCreated) {
    public EmbeddedBoundary {
        parentScopePath = requireText(parentScopePath, "parentScopePath");
        childScopePath = requireText(childScopePath, "childScopePath");
        childBlueId = requireText(childBlueId, "childBlueId");
        origin = Objects.requireNonNull(origin, "origin");
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank() && !"/".equals(checked)) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
