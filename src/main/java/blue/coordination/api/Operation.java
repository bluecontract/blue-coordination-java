package blue.coordination.api;

import java.util.Objects;
import java.util.Optional;

/** One operation request before exact Timeline Entry construction. */
public final class Operation {
    private final String operation;
    private final String channel;
    private final String requestYaml;
    private final ExactValue exactRequest;
    private final ExactValue targetDocument;
    private final boolean requireExactDocumentVersion;

    private Operation(
            String operation,
            String channel,
            String requestYaml,
            ExactValue exactRequest,
            ExactValue targetDocument,
            boolean requireExactDocumentVersion) {
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
        this.targetDocument = targetDocument;
        this.requireExactDocumentVersion = requireExactDocumentVersion;
        if (requireExactDocumentVersion && targetDocument == null) {
            throw new IllegalArgumentException(
                    "An exact-version requirement needs a document target");
        }
    }

    /** Creates an operation whose request is resolved from source YAML. */
    public static Operation yaml(
            String operation,
            String channel,
            String requestYaml) {
        return new Operation(
                operation, channel, requestYaml, null, null, false);
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
                Objects.requireNonNull(request, "request"),
                null,
                false);
    }

    /**
     * Returns an operation constrained to one managed document state.
     *
     * <p>The target remains environment-verified routing evidence. It does not
     * supply a recipient set: the route index still derives the one accepting
     * document from the selected profile and exact Timeline Entry.</p>
     *
     * @param document retained document state used for lineage targeting
     * @param requireExactVersion whether processing requires this exact head
     * @return a new immutable targeted operation
     */
    public Operation targeting(
            ExactValue document,
            boolean requireExactVersion) {
        return new Operation(
                operation,
                channel,
                requestYaml,
                exactRequest,
                Objects.requireNonNull(document, "document"),
                requireExactVersion);
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

    /** Exact managed state used to constrain routing, when targeted. */
    public Optional<ExactValue> targetDocument() {
        return Optional.ofNullable(targetDocument);
    }

    /** Whether the target must still be the document's current exact head. */
    public boolean requireExactDocumentVersion() {
        return requireExactDocumentVersion;
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
