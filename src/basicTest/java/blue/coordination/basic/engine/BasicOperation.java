package blue.coordination.basic.engine;

import java.util.Objects;
import java.util.Optional;

/** One operation request before exact Timeline Entry construction. */
public final class BasicOperation {
    private final String operation;
    private final String channel;
    private final String requestYaml;
    private final ExactNodeValue exactRequest;

    private BasicOperation(
            String operation,
            String channel,
            String requestYaml,
            ExactNodeValue exactRequest) {
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

    public static BasicOperation of(
            String operation,
            String channel,
            String requestYaml) {
        return new BasicOperation(operation, channel, requestYaml, null);
    }

    public static BasicOperation exact(
            String operation,
            String channel,
            ExactNodeValue request) {
        return new BasicOperation(
                operation,
                channel,
                null,
                Objects.requireNonNull(request, "request"));
    }

    public String operation() { return operation; }
    public String channel() { return channel; }
    public Optional<String> requestYaml() {
        return Optional.ofNullable(requestYaml);
    }
    public Optional<ExactNodeValue> exactRequest() {
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
