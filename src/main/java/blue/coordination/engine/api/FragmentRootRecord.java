package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.model.wire.JsonPointer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Persistable descriptor of one independently retained exact graph root. */
public final class FragmentRootRecord implements Comparable<FragmentRootRecord> {

    private final String blueId;
    private final CoordinationDocumentSplitter.FragmentRootKind kind;
    private final String absolutePath;

    public FragmentRootRecord(
            String blueId,
            CoordinationDocumentSplitter.FragmentRootKind kind,
            String absolutePath) {
        this.blueId = requireText(blueId, "blueId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.absolutePath = JsonPointer.canonicalize(
                Objects.requireNonNull(absolutePath, "absolutePath"));
    }

    static FragmentRootRecord from(
            CoordinationDocumentSplitter.FragmentRoot root) {
        return new FragmentRootRecord(
                root.blueId(), root.kind(), root.absolutePath());
    }

    /** Converts this persistence record to the lower-level reconstruction value. */
    public CoordinationDocumentSplitter.FragmentRoot toFragmentRoot() {
        return new CoordinationDocumentSplitter.FragmentRoot(
                blueId, kind, absolutePath);
    }

    public String blueId() { return blueId; }
    public CoordinationDocumentSplitter.FragmentRootKind kind() {
        return kind;
    }
    public String absolutePath() { return absolutePath; }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("blueId", blueId);
        map.put("kind", kind.name());
        map.put("absolutePath", absolutePath);
        return map;
    }

    static FragmentRootRecord rehydrate(Map<String, ?> map) {
        CoordinationFragmentInventory.requireFields(
                map, "fragment root", "blueId", "kind", "absolutePath");
        return new FragmentRootRecord(
                CoordinationFragmentInventory.text(map, "blueId"),
                CoordinationFragmentInventory.enumValue(
                        map,
                        "kind",
                        CoordinationDocumentSplitter.FragmentRootKind.class),
                CoordinationFragmentInventory.text(map, "absolutePath"));
    }

    @Override
    public int compareTo(FragmentRootRecord other) {
        int compared = kind.name().compareTo(other.kind.name());
        if (compared != 0) return compared;
        compared = absolutePath.compareTo(other.absolutePath);
        return compared != 0 ? compared : blueId.compareTo(other.blueId);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof FragmentRootRecord)) return false;
        FragmentRootRecord that = (FragmentRootRecord) other;
        return blueId.equals(that.blueId)
                && kind == that.kind
                && absolutePath.equals(that.absolutePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blueId, kind, absolutePath);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
