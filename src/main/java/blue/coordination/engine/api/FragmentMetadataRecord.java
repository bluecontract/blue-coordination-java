package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.model.wire.JsonPointer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Persistable non-semantic classification for one retained fragment. */
public final class FragmentMetadataRecord
        implements Comparable<FragmentMetadataRecord> {

    private final String blueId;
    private final CoordinationDocumentSplitter.FragmentKind kind;
    private final String scopePath;
    private final String pointer;
    private final String handlerTypeBlueId;
    private final String executableBodyField;

    public FragmentMetadataRecord(
            String blueId,
            CoordinationDocumentSplitter.FragmentKind kind,
            String scopePath,
            String pointer,
            String handlerTypeBlueId,
            String executableBodyField) {
        this.blueId = requireText(blueId, "blueId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.scopePath = canonicalOptional(scopePath);
        this.pointer = canonicalOptional(pointer);
        this.handlerTypeBlueId = handlerTypeBlueId;
        this.executableBodyField = executableBodyField;
    }

    static FragmentMetadataRecord from(
            CoordinationDocumentSplitter.FragmentMetadata metadata) {
        return new FragmentMetadataRecord(
                metadata.blueId(),
                metadata.kind(),
                metadata.scopePath(),
                metadata.pointer(),
                metadata.handlerTypeBlueId(),
                metadata.executableBodyField());
    }

    public String blueId() { return blueId; }
    public CoordinationDocumentSplitter.FragmentKind kind() { return kind; }
    public String scopePath() { return scopePath; }
    public String pointer() { return pointer; }
    public String handlerTypeBlueId() { return handlerTypeBlueId; }
    public String executableBodyField() { return executableBodyField; }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("blueId", blueId);
        map.put("kind", kind.name());
        map.put("scopePath", scopePath);
        map.put("pointer", pointer);
        map.put("handlerTypeBlueId", handlerTypeBlueId);
        map.put("executableBodyField", executableBodyField);
        return map;
    }

    static FragmentMetadataRecord rehydrate(Map<String, ?> map) {
        CoordinationFragmentInventory.requireFields(
                map,
                "fragment metadata",
                "blueId",
                "kind",
                "scopePath",
                "pointer",
                "handlerTypeBlueId",
                "executableBodyField");
        return new FragmentMetadataRecord(
                CoordinationFragmentInventory.text(map, "blueId"),
                CoordinationFragmentInventory.enumValue(
                        map,
                        "kind",
                        CoordinationDocumentSplitter.FragmentKind.class),
                CoordinationFragmentInventory.optionalText(map, "scopePath"),
                CoordinationFragmentInventory.optionalText(map, "pointer"),
                CoordinationFragmentInventory.optionalText(
                        map, "handlerTypeBlueId"),
                CoordinationFragmentInventory.optionalText(
                        map, "executableBodyField"));
    }

    @Override
    public int compareTo(FragmentMetadataRecord other) {
        int compared = blueId.compareTo(other.blueId);
        if (compared != 0) return compared;
        compared = kind.name().compareTo(other.kind.name());
        if (compared != 0) return compared;
        compared = nullToEmpty(scopePath).compareTo(
                nullToEmpty(other.scopePath));
        if (compared != 0) return compared;
        return nullToEmpty(pointer).compareTo(nullToEmpty(other.pointer));
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof FragmentMetadataRecord
                && toMap().equals(((FragmentMetadataRecord) other).toMap()));
    }

    @Override
    public int hashCode() {
        return toMap().hashCode();
    }

    private static String canonicalOptional(String value) {
        return value == null ? null : JsonPointer.canonicalize(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
