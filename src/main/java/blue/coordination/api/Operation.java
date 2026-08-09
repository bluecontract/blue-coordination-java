package blue.coordination.api;

import java.util.Objects;
import java.util.Optional;

/** One operation request before exact Timeline Entry construction. */
public final class Operation {
    private final String operation;
    private final String channel;
    private final String requestYaml;
    private final ExactValue exactRequest;

    private Operation(
            String operation,
            String channel,
            String requestYaml,
            ExactValue exactRequest) {
        this.operation = requireText(operation, "operation");
        this.channel = requireText(channel, "channel");
        if ((requestYaml == null) == (exactRequest == null)) {
            throw new IllegalArgumentException(
                    "Exactly one request representation is required");
        }
        this.requestYaml = requestYaml == null
                ? null
                : normalizeYaml(requestYaml);
        this.exactRequest = exactRequest;
    }

    /** Creates an operation whose request is resolved from source YAML. */
    public static Operation yaml(
            String operation,
            String channel,
            String requestYaml) {
        return new Operation(operation, channel, requestYaml, null);
    }

    /** Creates an operation that reuses an already retained exact request. */
    public static Operation exact(
            String operation,
            String channel,
            ExactValue request) {
        return new Operation(
                operation,
                channel,
                null,
                Objects.requireNonNull(request, "request"));
    }

    /** Returns the authored operation name used by exact route matching. */
    public String operation() { return operation; }

    /** Returns the authored channel name used by exact route matching. */
    public String channel() { return channel; }

    /** Returns unresolved YAML when this operation uses the source path. */
    public Optional<String> requestYaml() {
        return Optional.ofNullable(requestYaml);
    }

    /** Returns the retained exact request when this operation uses reuse. */
    public Optional<ExactValue> exactRequest() {
        return Optional.ofNullable(exactRequest);
    }

    private static String normalizeYaml(String value) {
        String checked = Objects.requireNonNull(value, "requestYaml").strip();
        return checked.isEmpty() ? "{}" : checked;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
