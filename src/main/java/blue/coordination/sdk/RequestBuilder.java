package blue.coordination.sdk;

import blue.coordination.api.ExactValue;
import blue.coordination.api.CoordinationEngine;
import blue.language.model.Node;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds one structurally shared exact operation request. */
public final class RequestBuilder {
    private final Map<String, ExactBlueValue> fields = new LinkedHashMap<>();
    private final Map<String, ManagedDocumentDraft> managed =
            new LinkedHashMap<>();

    RequestBuilder() {
    }

    /** Adds an ordinary whole exact value under one request field. */
    public RequestBuilder exact(String field, ExactBlueValue value) {
        put(field, Objects.requireNonNull(value, "value"), null);
        return this;
    }

    /** Adds exact content plus managed-lineage invocation evidence. */
    public RequestBuilder managed(
            String field,
            ManagedDocumentDraft draft) {
        ManagedDocumentDraft selected = Objects.requireNonNull(draft, "draft");
        put(field, selected.initial(), selected);
        return this;
    }

    ExactValue exactRequest(CoordinationEngine engine) {
        CoordinationEngine selected = Objects.requireNonNull(
                engine, "engine");
        LinkedHashMap<String, Node> properties = new LinkedHashMap<>();
        fields.forEach((field, value) -> {
            selected.referenceRequest(field, value.unwrap());
            properties.put(field, value.unwrap().referenceNode());
        });
        return ExactValue.verified(new Node().properties(properties));
    }

    boolean hasManagedEvidence() { return !managed.isEmpty(); }

    Map<String, ManagedDocumentDraft> managedEvidence() {
        return Map.copyOf(managed);
    }

    private void put(
            String field,
            ExactBlueValue value,
            ManagedDocumentDraft draft) {
        String key = Objects.requireNonNull(field, "field").trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        if (fields.putIfAbsent(key, value) != null) {
            throw new IllegalArgumentException(
                    "Duplicate request field " + key);
        }
        if (draft != null) {
            managed.put(key, draft);
        }
    }
}
