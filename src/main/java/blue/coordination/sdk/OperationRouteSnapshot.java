package blue.coordination.sdk;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable read-only view of one compiled external operation route. */
public record OperationRouteSnapshot(
        String scopePath,
        String operation,
        String channel,
        Optional<ExactBlueValue> requestPattern,
        List<TimelineSourceSnapshot> acceptedSources) {
    /** Validates route identity and retains the immutable compiler order. */
    public OperationRouteSnapshot {
        scopePath = requireScopePath(scopePath);
        operation = SdkPreconditions.requireText(operation, "operation");
        channel = SdkPreconditions.requireText(channel, "channel");
        requestPattern = Objects.requireNonNull(
                requestPattern, "requestPattern");
        acceptedSources = List.copyOf(Objects.requireNonNull(
                acceptedSources, "acceptedSources"));
    }

    /** Compatibility constructor for callers that do not retain request evidence. */
    public OperationRouteSnapshot(
            String scopePath,
            String operation,
            String channel,
            List<TimelineSourceSnapshot> acceptedSources) {
        this(scopePath, operation, channel, Optional.empty(), acceptedSources);
    }

    private static String requireScopePath(String value) {
        String checked = SdkPreconditions.requireText(value, "scopePath");
        if (!checked.startsWith("/")) {
            throw new IllegalArgumentException(
                    "scopePath must use the compiled absolute scope form");
        }
        return checked;
    }
}
