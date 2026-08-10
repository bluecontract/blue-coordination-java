package blue.coordination.api;

import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ExternalOrderKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Stable immutable read model for one managed document. */
public record DocumentSnapshot(
        DocumentId documentId,
        long epoch,
        SessionStatus status,
        ExternalOrderKey readyThrough,
        String authoredInitialBlueId,
        ExactValue current,
        Map<String, ExactValue> physicalObjects,
        Map<String, DocumentId> embeddedChildren,
        List<String> processEmbeddedBoundaries,
        List<String> routingDefinitions,
        int physicalObjectCount,
        String processingRootBlueId) {
    /** Defensively copies the read model and validates exact identity fields. */
    public DocumentSnapshot {
        documentId = Objects.requireNonNull(documentId, "documentId");
        status = Objects.requireNonNull(status, "status");
        authoredInitialBlueId = requireText(
                authoredInitialBlueId, "authoredInitialBlueId");
        current = Objects.requireNonNull(current, "current");
        physicalObjects = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(
                        physicalObjects, "physicalObjects")));
        embeddedChildren = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(
                        embeddedChildren, "embeddedChildren")));
        processEmbeddedBoundaries = List.copyOf(Objects.requireNonNull(
                processEmbeddedBoundaries, "processEmbeddedBoundaries"));
        routingDefinitions = List.copyOf(Objects.requireNonNull(
                routingDefinitions, "routingDefinitions"));
        processingRootBlueId = requireText(
                processingRootBlueId, "processingRootBlueId");
        if (epoch < 0L || physicalObjectCount <= 0) {
            throw new IllegalArgumentException(
                    "Snapshot epoch and physical object count are invalid");
        }
    }

    /** Returns the BlueId of the current exact document state. */
    public String blueId() {
        return current.blueId();
    }

    /** Returns evidence of the complete journal frontier when available. */
    public Optional<ExternalOrderKey> readyThroughEvidence() {
        return Optional.ofNullable(readyThrough);
    }

    /** Selects and verifies one exact value by canonical JSON Pointer. */
    public ExactValue valueAt(String pointer) {
        Node selected = NodePathEditor.getOrNull(
                current.copyNode(), Objects.requireNonNull(pointer, "pointer"));
        if (selected == null) {
            throw new CoordinationException(
                    CoordinationErrorCode.INVALID_DOCUMENT_IDENTITY,
                    "No value at " + documentId + pointer);
        }
        return ExactValue.verified(selected);
    }

    /** Returns one retained physical whole object by its canonical scope. */
    public ExactValue physicalObject(String scopePath) {
        ExactValue selected = physicalObjects.get(Objects.requireNonNull(
                scopePath, "scopePath"));
        if (selected == null) {
            throw new CoordinationException(
                    CoordinationErrorCode.INVALID_DOCUMENT_IDENTITY,
                    "No physical object at " + documentId + scopePath);
        }
        return selected;
    }

    private static String requireText(String value, String label) {
        String exact = Objects.requireNonNull(value, label);
        if (exact.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return exact;
    }
}
