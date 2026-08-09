package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import blue.coordination.api.Timeline;

import blue.coordination.api.Operation;

import blue.coordination.api.ExactValue;

import blue.coordination.api.DocumentRevision;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts one already-committed child revision into one exact processor-managed
 * parent input without YAML, preprocessing, resolution, or child-state copying.
 */
final class InternalRevisionEventFactory {
    public static final String INTERNAL_CHANNEL =
            "coordinationEmbeddedChannel";
    public static final String INTERNAL_OPERATION =
            "coordinationApplyEmbeddedRevision";

    private final WholeObjectStore objects;
    private final InMemoryTimelineJournal journal;
    private final EngineMetrics metrics;

    public InternalRevisionEventFactory(
            WholeObjectStore objects,
            InMemoryTimelineJournal journal,
            EngineMetrics metrics) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public TimelineEntry append(
            DocumentSession parent,
            EmbeddedLink link,
            DocumentRevision childRevision,
            long applicationTimestampMicros) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(childRevision, "childRevision");
        if (!link.parentDocumentId().equals(parent.documentId())) {
            throw new IllegalArgumentException("Link belongs to another parent");
        }
        ExactValue request = metrics.timed(
                "catchUp.buildRevisionRequestWhole",
                () -> request(link, childRevision));
        Timeline internalTimeline = new Timeline(
                "coordination/internal/" + parent.documentId().value(),
                "coordination");
        ExternalOrderKey originalSourceOrder = childRevision.sourceOrderKey()
                .orElse(link.cutoffOrderKey());
        return journal.appendProcessorManaged(
                internalTimeline,
                Operation.exact(
                        INTERNAL_OPERATION,
                        INTERNAL_CHANNEL,
                        request),
                applicationTimestampMicros,
                parent.documentId(),
                link.cause(),
                originalSourceOrder);
    }

    private ExactValue request(
            EmbeddedLink link,
            DocumentRevision revision) {
        ExactValue events = eventList(revision.emittedEvents());
        Map<String, Node> sourceFields = new LinkedHashMap<>();
        sourceFields.put("entryBlueId", scalar(revision.sourceEntry()
                .map(TimelineEntry::blueId)
                .orElse("initialization")));
        sourceFields.put("timelineId", scalar(revision.sourceEntry()
                .map(entry -> entry.timeline().timelineId())
                .orElse("coordination/initialization")));
        sourceFields.put("timestampMicros", new Node().value(
                revision.sourceEntry()
                        .map(TimelineEntry::timestampMicros)
                        .orElse(link.cause().attachmentTimestampMicros())));

        Map<String, Node> fields = new LinkedHashMap<>();
        fields.put("occurrencePath", scalar(link.occurrencePath()));
        fields.put("childDocumentId", scalar(
                link.childDocumentId().value()));
        fields.put("childEpoch", new Node().value(revision.epoch()));
        fields.put("revisionKind", scalar(revision.kind().name()));
        revision.before().ifPresent(before ->
                fields.put("before", before.referenceNode()));
        fields.put("after", revision.after().referenceNode());
        fields.put("source", new Node().properties(sourceFields));
        fields.put("causedByEntryBlueId", scalar(
                link.cause().attachmentEntryBlueId()));
        fields.put("cutoffTimestampMicros", new Node().value(
                link.cause().attachmentTimestampMicros()));
        fields.put("emittedEventCount", new Node().value(
                revision.emittedEvents().size()));
        fields.put("emittedEvents", events.referenceNode());

        // The request is already an exact canonical object composed only of
        // scalars and verified whole-object references. Re-resolving it through
        // Language would add no semantic information and would discard the
        // immutable proof carried by those references.
        metrics.increment("catchUp.revisionRequestsBuiltDirectly");
        return objects.put(
                new Node().properties(fields),
                "embedded-revision-request");
    }

    private ExactValue eventList(List<Node> emittedEvents) {
        List<Node> items = new ArrayList<>();
        for (Node event : emittedEvents) {
            ExactValue exact = objects.put(event, "emitted-event");
            items.add(exact.referenceNode());
        }
        return objects.put(
                new Node().items(items), "emitted-event-list");
    }

    private static Node scalar(String value) {
        return new Node().value(Objects.requireNonNull(value, "value"))
                .inlineValue(true);
    }
}
