package blue.coordination.internal;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Timeline;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.math.BigInteger;

/** Exact processor-owned child epoch input; it is never appended to a Timeline. */
record EmbeddedEpochInput(
        String inputId,
        EmbeddingBinding binding,
        long fromChildEpoch,
        long toChildEpoch,
        String beforeChildBlueId,
        String afterChildBlueId,
        ExternalOrderKey sourceOrder,
        ExternalOrderKey applicationOrder,
        String originalEntryBlueId,
        List<EventOccurrence> eventOccurrences,
        ExactValue exactEvent,
        ExactValue exactRequest,
        Timeline transportTimeline,
        String operation,
        String channel,
        long applicationTimestampMicros) {
    static final String INTERNAL_OPERATION =
            "coordinationApplyEmbeddedRevision";
    static final String INTERNAL_CHANNEL = "coordinationEmbeddedChannel";

    EmbeddedEpochInput {
        inputId = requireText(inputId, "inputId");
        binding = Objects.requireNonNull(binding, "binding");
        if (fromChildEpoch < -1L || toChildEpoch != fromChildEpoch + 1L) {
            throw new IllegalArgumentException(
                    "Embedded child epoch interval must be contiguous");
        }
        beforeChildBlueId = requireText(
                beforeChildBlueId, "beforeChildBlueId");
        afterChildBlueId = requireText(
                afterChildBlueId, "afterChildBlueId");
        sourceOrder = Objects.requireNonNull(sourceOrder, "sourceOrder");
        applicationOrder = Objects.requireNonNull(
                applicationOrder, "applicationOrder");
        eventOccurrences = List.copyOf(Objects.requireNonNull(
                eventOccurrences, "eventOccurrences"));
        exactEvent = Objects.requireNonNull(exactEvent, "exactEvent");
        exactRequest = Objects.requireNonNull(exactRequest, "exactRequest");
        transportTimeline = Objects.requireNonNull(
                transportTimeline, "transportTimeline");
        operation = requireText(operation, "operation");
        channel = requireText(channel, "channel");
        if (applicationTimestampMicros <= 0L) {
            throw new IllegalArgumentException(
                    "applicationTimestampMicros must be positive");
        }
    }

    static EmbeddedEpochInput create(
            WholeRequestEntryFactory entryFactory,
            WholeObjectStore objects,
            EmbeddingBinding binding,
            DocumentRevision childRevision,
            long applicationTimestampMicros,
            String previousInputBlueId) {
        Objects.requireNonNull(entryFactory, "entryFactory");
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(childRevision, "childRevision");
        String beforeBlueId = childRevision.before()
                .map(ExactValue::blueId)
                .orElse(binding.admittedChildBlueId());
        List<EventOccurrence> occurrences = eventOccurrences(
                objects, childRevision.emittedEvents());
        ExactValue request = request(
                objects,
                binding,
                childRevision,
                beforeBlueId,
                occurrences);
        Timeline timeline = new Timeline(
                "coordination/internal/"
                        + binding.parentDocumentId().value(),
                "coordination");
        ExactValue event = entryFactory.createProcessorOwnedEvent(
                timeline,
                previousInputBlueId,
                applicationTimestampMicros,
                request);
        ExternalOrderKey applicationOrder = ExternalOrderKey.of(List.of(
                BigInteger.valueOf(applicationTimestampMicros),
                timeline.timelineId(),
                event.blueId()));
        String inputId = "embedded|" + binding.bindingId() + "|"
                + childRevision.epoch();
        return new EmbeddedEpochInput(
                inputId,
                binding,
                childRevision.epoch() - 1L,
                childRevision.epoch(),
                beforeBlueId,
                childRevision.after().blueId(),
                childRevision.sourceOrderKey().orElse(
                        binding.attachmentOrder()),
                applicationOrder,
                childRevision.causalEntryBlueId().orElse(null),
                occurrences,
                event,
                request,
                timeline,
                INTERNAL_OPERATION,
                INTERNAL_CHANNEL,
                applicationTimestampMicros);
    }

    private static ExactValue request(
            WholeObjectStore objects,
            EmbeddingBinding binding,
            DocumentRevision revision,
            String beforeBlueId,
            List<EventOccurrence> occurrences) {
        List<Node> occurrenceNodes = new ArrayList<>();
        for (EventOccurrence occurrence : occurrences) {
            occurrenceNodes.add(new Node().properties(new LinkedHashMap<>(Map.of(
                    "occurrenceIndex", new Node().value(
                            occurrence.occurrenceIndex()),
                    "eventBlueId", scalar(occurrence.eventBlueId()),
                    "eventTypeBlueId", scalar(
                            occurrence.eventTypeBlueId())))));
        }
        ExactValue exactOccurrences = objects.put(
                new Node().items(occurrenceNodes),
                "embedded-event-occurrences");
        Map<String, Node> fields = new LinkedHashMap<>();
        fields.put("parentDocumentId", scalar(
                binding.parentDocumentId().value()));
        fields.put("occurrencePath", scalar(binding.absolutePath()));
        fields.put("activationGeneration", new Node().value(
                binding.activationGeneration()));
        fields.put("childDocumentId", scalar(
                binding.childDocumentId().value()));
        fields.put("fromChildEpoch", new Node().value(revision.epoch() - 1L));
        fields.put("childEpoch", new Node().value(revision.epoch()));
        fields.put("beforeChildBlueId", scalar(beforeBlueId));
        fields.put("afterChildBlueId", scalar(revision.after().blueId()));
        revision.before().ifPresent(before ->
                fields.put("before", before.referenceNode()));
        fields.put("after", revision.after().referenceNode());
        fields.put("emittedEventCount", new Node().value(occurrences.size()));
        fields.put("eventOccurrences", exactOccurrences.referenceNode());
        // Retain the legacy ordered event value for frozen workflows while the
        // exact indexed occurrence list remains the normative identity proof.
        List<Node> eventReferences = occurrences.stream()
                .map(occurrence -> new Node().blueId(
                        occurrence.eventBlueId()))
                .toList();
        ExactValue exactEvents = objects.put(
                new Node().items(eventReferences), "embedded-events");
        fields.put("emittedEvents", exactEvents.referenceNode());
        if (revision.causalEntryBlueId().isPresent()) {
            fields.put("originalEntryBlueId", scalar(
                    revision.causalEntryBlueId().orElseThrow()));
        }
        fields.put("attachmentEntryBlueId", scalar(
                binding.attachmentEntryBlueId()));
        return objects.put(
                new Node().properties(fields), "embedded-epoch-request");
    }

    private static List<EventOccurrence> eventOccurrences(
            WholeObjectStore objects,
            List<Node> events) {
        List<EventOccurrence> result = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            ExactValue event = objects.put(
                    Objects.requireNonNull(events.get(index), "event"),
                    "emitted-event");
            FrozenNode type = event.frozen().getType();
            if (type == null || type.isEmptyNode()) {
                throw new IllegalStateException(
                        "Child event occurrence " + index
                                + " has no exact effective type");
            }
            String typeBlueId = type.blueId();
            if (typeBlueId == null || typeBlueId.isBlank()) {
                throw new IllegalStateException(
                        "Child event occurrence " + index
                                + " has no established type BlueId");
            }
            result.add(new EventOccurrence(
                    index, event.blueId(), typeBlueId));
        }
        return List.copyOf(result);
    }

    record EventOccurrence(
            int occurrenceIndex,
            String eventBlueId,
            String eventTypeBlueId) {
        EventOccurrence {
            if (occurrenceIndex < 0) {
                throw new IllegalArgumentException(
                        "occurrenceIndex must be non-negative");
            }
            eventBlueId = requireText(eventBlueId, "eventBlueId");
            eventTypeBlueId = requireText(
                    eventTypeBlueId, "eventTypeBlueId");
        }
    }

    private static Node scalar(String value) {
        return new Node().value(requireText(value, "value")).inlineValue(true);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
