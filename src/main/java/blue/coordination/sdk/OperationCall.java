package blue.coordination.sdk;

import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Fluent immutable-target operation builder. */
public final class OperationCall {
    private final SdkCoordinationRuntime runtime;
    private final SdkCoordinationRuntime.TargetSelection target;
    private TimelineHandle timeline;
    private String operation;
    private String channel;
    private String requestYaml;
    private RequestBuilder request;
    private final List<OccurrenceExpectation> expectations = new ArrayList<>();
    private ActivationPolicy activation = ActivationPolicy.fromNow();
    private boolean consumed;

    OperationCall(
            SdkCoordinationRuntime runtime,
            SdkCoordinationRuntime.TargetSelection target) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.target = Objects.requireNonNull(target, "target");
    }

    public OperationCall from(TimelineHandle selectedTimeline) {
        requireMutable();
        timeline = Objects.requireNonNull(selectedTimeline, "timeline");
        return this;
    }

    public OperationCall call(String operationName) {
        requireMutable();
        operation = requireText(operationName, "operation");
        return this;
    }

    public OperationCall through(String channelName) {
        requireMutable();
        channel = requireText(channelName, "channel");
        return this;
    }

    public OperationCall requestYaml(String yaml) {
        requireMutable();
        if (request != null) {
            throw new IllegalStateException(
                    "A structured request is already configured");
        }
        requestYaml = Objects.requireNonNull(yaml, "yaml");
        return this;
    }

    public OperationCall request(Consumer<RequestBuilder> declaration) {
        requireMutable();
        if (requestYaml != null || request != null) {
            throw new IllegalStateException("A request is already configured");
        }
        RequestBuilder builder = new RequestBuilder();
        Objects.requireNonNull(declaration, "declaration").accept(builder);
        request = builder;
        return this;
    }

    public OperationCall expectOccurrence(
            String path,
            ManagedDocumentDraft draft) {
        requireMutable();
        expectations.add(new OccurrenceExpectation(
                canonicalOccurrencePath(path),
                Objects.requireNonNull(draft, "draft"),
                null));
        return this;
    }

    public OperationCall expectOccurrence(
            String path,
            ManagedDocumentDraft draft,
            ActivationPolicy policy) {
        requireMutable();
        expectations.add(new OccurrenceExpectation(
                canonicalOccurrencePath(path),
                Objects.requireNonNull(draft, "draft"),
                Objects.requireNonNull(policy, "policy")));
        return this;
    }

    public OperationCall activation(ActivationPolicy policy) {
        requireMutable();
        activation = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /** Appends the call without processing it. */
    public EntryHandle submit() {
        requireReady();
        consumed = true;
        return runtime.submitOperation(this);
    }

    /** Appends and drains canonically through this call. */
    public EntryResult execute() {
        requireReady();
        consumed = true;
        return runtime.executeOperation(this);
    }

    SdkCoordinationRuntime.TargetSelection target() { return target; }

    TimelineHandle timeline() { return timeline; }

    String operation() { return operation; }

    String channel() { return channel; }

    String requestYaml() { return requestYaml; }

    RequestBuilder request() { return request; }

    List<OccurrenceExpectation> expectations() { return List.copyOf(expectations); }

    ActivationPolicy activation() { return activation; }

    private void requireReady() {
        requireMutable();
        Objects.requireNonNull(timeline, "from timeline");
        requireText(operation, "operation");
        requireText(channel, "channel");
    }

    private void requireMutable() {
        if (consumed) {
            throw new IllegalStateException(
                    "An operation call can be submitted only once");
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static String canonicalOccurrencePath(String path) {
        String canonical = JsonPointer.canonicalize(
                Objects.requireNonNull(path, "path"));
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException(
                    "A managed occurrence cannot replace the document Root");
        }
        return canonical;
    }

    record OccurrenceExpectation(
            String path,
            ManagedDocumentDraft draft,
            ActivationPolicy explicitPolicy) {
        ActivationPolicy resolvedPolicy(ActivationPolicy callPolicy) {
            return explicitPolicy == null
                    ? Objects.requireNonNull(callPolicy, "callPolicy")
                    : explicitPolicy;
        }
    }
}
