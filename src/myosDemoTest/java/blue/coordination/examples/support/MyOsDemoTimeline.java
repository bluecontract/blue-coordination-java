package blue.coordination.examples.support;

import blue.coordination.processor.CoordinationTimelineRouteProjection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic append-only Timeline used by the executable examples.
 *
 * <p>The same exact entry may be delivered to several document sessions. The
 * Timeline is therefore the owner of entry construction, while each managed
 * Root owns its own delivery progress and checkpoint.</p>
 */
public final class MyOsDemoTimeline {

    private final MyOsDemoRuntime runtime;
    private final String timelineId;
    private final MyOsDemoActor actor;
    private final List<String> subscriptionKeys;
    private final LinkedHashSet<String> entryBlueIds = new LinkedHashSet<>();
    private String previousEntryBlueId;
    private MyOsTimelineBinding binding;
    private long publicationVersion;

    MyOsDemoTimeline(
            MyOsDemoRuntime runtime,
            String timelineId,
            MyOsDemoActor actor) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.timelineId = Objects.requireNonNull(timelineId, "timelineId");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.subscriptionKeys =
                CoordinationTimelineRouteProjection
                        .exactEventSubscriptionKeys(
                        timelineId, actor.actorId());
    }

    static MyOsDemoTimeline restore(
            MyOsDemoRuntime runtime,
            MyOsTimelineCheckpoint checkpoint) {
        MyOsTimelineCheckpoint checked = Objects.requireNonNull(
                checkpoint, "checkpoint");
        MyOsDemoTimeline result = new MyOsDemoTimeline(
                runtime, checked.timelineId(), checked.actor());
        result.entryBlueIds.addAll(checked.entryBlueIds());
        result.previousEntryBlueId = checked.previousEntryBlueId();
        result.binding = checked.binding();
        result.publicationVersion = result.entryBlueIds.size();
        if ((result.binding == null) != result.entryBlueIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Timeline binding and append history disagree");
        }
        return result;
    }

    MyOsTimelineCheckpoint checkpoint() {
        return new MyOsTimelineCheckpoint(
                timelineId,
                actor,
                List.copyOf(entryBlueIds),
                previousEntryBlueId,
                binding);
    }

    PendingTimelineAppend prepare(MyOsDemoOperation operation) {
        Objects.requireNonNull(operation, "operation");
        long timestamp = runtime.peekNextTimelineTimestampMicros();
        String expectedPrevious = previousEntryBlueId;
        MyOsPreparedEntryTemplate template =
                MyOsPreparedEntryTemplates.require(
                        runtime,
                        this,
                        operation,
                        expectedPrevious);
        PendingTimelineAppend pending = template.instantiate(
                this,
                runtime,
                operation,
                timestamp,
                expectedPrevious);
        if (binding != null
                && !binding.equals(pending.entry().binding())) {
            throw new IllegalStateException(
                    "Timeline header identity changed while appending");
        }
        return pending;
    }

    /**
     * Prepares this exact next entry and its event split without advancing the
     * Timeline or publishing any authoritative state.
     */
    public void prime(MyOsDemoOperation operation) {
        MyOsDemoOperation checked = Objects.requireNonNull(
                operation, "operation");
        long timestamp = runtime.peekNextTimelineTimestampMicros();
        MyOsPreparedEntryTemplate template = preparedTemplate(checked);
        runtime.primeEventAdmission(template.instantiate(
                this,
                runtime,
                checked,
                timestamp,
                previousEntryBlueId).entry());
    }

    /** Warms only the recurring entry shape, not this exact event artifact. */
    public void primeTemplate(MyOsDemoOperation operation) {
        MyOsDemoOperation checked = Objects.requireNonNull(
                operation, "operation");
        preparedTemplate(checked);
    }

    private MyOsPreparedEntryTemplate preparedTemplate(
            MyOsDemoOperation operation) {
        return MyOsPreparedEntryTemplates.require(
                runtime,
                this,
                operation,
                previousEntryBlueId);
    }

    void commit(PendingTimelineAppend pending) {
        validate(pending);
        publish(pending);
    }

    void validate(PendingTimelineAppend pending) {
        PendingTimelineAppend checked = Objects.requireNonNull(
                pending, "pending");
        if (checked.owner() != this
                || checked.expectedPublicationVersion()
                        != publicationVersion
                || !Objects.equals(
                        previousEntryBlueId,
                        checked.expectedPreviousBlueId())) {
            throw new IllegalStateException("stale Timeline append");
        }
        MyOsDemoEntry entry = checked.entry();
        if (binding != null && !binding.equals(entry.binding())) {
            throw new IllegalStateException(
                    "Timeline header identity changed while committing");
        }
    }

    long publicationVersion() {
        return publicationVersion;
    }

    long nextPublicationVersion() {
        return Math.addExact(publicationVersion, 1L);
    }

    void publish(PendingTimelineAppend pending) {
        PendingTimelineAppend checked = Objects.requireNonNull(
                pending, "pending");
        MyOsDemoEntry entry = checked.entry();
        binding = entry.binding();
        previousEntryBlueId = entry.blueId();
        entryBlueIds.add(entry.blueId());
        publicationVersion = checked.resultingPublicationVersion();
    }

    public String timelineId() {
        return timelineId;
    }

    public MyOsDemoActor actor() {
        return actor;
    }

    List<String> subscriptionKeys() {
        return subscriptionKeys;
    }

    public MyOsTimelineBinding binding() {
        if (binding == null) {
            throw new IllegalStateException(
                    "Timeline has no authored entries yet: " + timelineId);
        }
        return binding;
    }

    boolean hasBinding() {
        return binding != null;
    }

    boolean belongsTo(MyOsDemoRuntime candidate) {
        return runtime == candidate;
    }

    boolean hasCompleteHistoryThrough(String entryBlueId) {
        return entryBlueIds.contains(entryBlueId);
    }

    String eventYaml(
            MyOsDemoOperation operation,
            long timestamp,
            String previousBlueId) {
        String previous = previousBlueId == null
                ? ""
                : """
                  prevEntry:
                    blueId: %s
                  """.formatted(previousBlueId);
        String actorYaml = MyOsDemoYaml.indent(
                actor.toYaml(0).stripTrailing(), 2) + "\n";
        String authority = operation.authority() == null
                ? ""
                : """
                  onBehalfOf:
                  %s
                  """.formatted(MyOsDemoYaml.indent(
                        operation.authority().toYaml(0).stripTrailing(),
                        2));
        String request = operation.requestYaml().equals("{}")
                ? "  request: {}\n"
                : "  request:\n"
                        + MyOsDemoYaml.indent(
                                operation.requestYaml(), 4)
                        + "\n";
        return """
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: %s
                %stimestamp: %d
                actor:
                %s%smessage:
                  type: Coordination/Operation Request
                  operation: %s
                  channel: %s
                %s
                """.formatted(
                timelineId,
                previous,
                timestamp,
                actorYaml,
                authority,
                operation.operation(),
                operation.handlerChannel(),
                request);
    }
}
