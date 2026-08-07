package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete persistable provenance of one exact direct fragment edge. */
public final class FragmentEdgeRecord implements Comparable<FragmentEdgeRecord> {

    private final String schemaIdentity;
    private final CoordinationDocumentSplitter.FragmentRootKind rootKind;
    private final String rootBlueId;
    private final String ownerNodeBlueId;
    private final String ownerScopePath;
    private final String absolutePointer;
    private final String ownerRelativePointer;
    private final String childBlueId;
    private final CoordinationDocumentSplitter.EdgeKind edgeKind;
    private final boolean originalPureReference;
    private final boolean splitterCreated;
    private final String declaringScopePath;
    private final CoordinationDocumentSplitter.EmbeddedEdgeOrigin embeddedOrigin;
    private final String explicitDeclarationPath;
    private final String collectionDeclarationPath;
    private final String collectionMemberKey;
    private final String handlerEffectiveTypeBlueId;
    private final String executableBodyField;
    private final List<String> sourceContributionBlueIds;

    public FragmentEdgeRecord(
            String schemaIdentity,
            CoordinationDocumentSplitter.FragmentRootKind rootKind,
            String rootBlueId,
            String ownerNodeBlueId,
            String ownerScopePath,
            String absolutePointer,
            String ownerRelativePointer,
            String childBlueId,
            CoordinationDocumentSplitter.EdgeKind edgeKind,
            boolean originalPureReference,
            boolean splitterCreated,
            String declaringScopePath,
            CoordinationDocumentSplitter.EmbeddedEdgeOrigin embeddedOrigin,
            String explicitDeclarationPath,
            String collectionDeclarationPath,
            String collectionMemberKey,
            String handlerEffectiveTypeBlueId,
            String executableBodyField,
            List<String> sourceContributionBlueIds) {
        this.schemaIdentity = requireText(schemaIdentity, "schemaIdentity");
        this.rootKind = Objects.requireNonNull(rootKind, "rootKind");
        this.rootBlueId = requireText(rootBlueId, "rootBlueId");
        this.ownerNodeBlueId = requireText(
                ownerNodeBlueId, "ownerNodeBlueId");
        this.ownerScopePath = canonicalOptional(ownerScopePath);
        this.absolutePointer = canonical(absolutePointer, "absolutePointer");
        this.ownerRelativePointer = canonical(
                ownerRelativePointer, "ownerRelativePointer");
        this.childBlueId = requireText(childBlueId, "childBlueId");
        this.edgeKind = Objects.requireNonNull(edgeKind, "edgeKind");
        this.originalPureReference = originalPureReference;
        this.splitterCreated = splitterCreated;
        if (originalPureReference == splitterCreated) {
            throw new IllegalArgumentException(
                    "Exactly one physical-edge origin must be true");
        }
        this.declaringScopePath = canonicalOptional(declaringScopePath);
        this.embeddedOrigin = Objects.requireNonNull(
                embeddedOrigin, "embeddedOrigin");
        this.explicitDeclarationPath = canonicalOptional(
                explicitDeclarationPath);
        this.collectionDeclarationPath = canonicalOptional(
                collectionDeclarationPath);
        this.collectionMemberKey = collectionMemberKey;
        this.handlerEffectiveTypeBlueId = handlerEffectiveTypeBlueId;
        this.executableBodyField = executableBodyField;
        List<String> sourceIds = new ArrayList<String>(
                Objects.requireNonNull(
                        sourceContributionBlueIds,
                        "sourceContributionBlueIds"));
        for (String sourceId : sourceIds) {
            requireText(sourceId, "sourceContributionBlueId");
        }
        this.sourceContributionBlueIds = Collections.unmodifiableList(sourceIds);
        // Reuse the lower-level constructor as the authoritative provenance
        // validator, including stable-key collection pointer escaping.
        toEdgeOccurrence(CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID);
    }

    static FragmentEdgeRecord from(
            CoordinationDocumentSplitter.EdgeOccurrence edge) {
        return fromVerifiedOccurrence(edge);
    }

    /**
     * Copies a fully validated splitter occurrence without replaying its
     * canonical pointer and BlueId validation in this persistence adapter.
     */
    public static FragmentEdgeRecord fromVerifiedOccurrence(
            CoordinationDocumentSplitter.EdgeOccurrence supplied) {
        CoordinationDocumentSplitter.EdgeOccurrence edge =
                Objects.requireNonNull(supplied, "edge");
        return new FragmentEdgeRecord(
                edge.schemaIdentity(),
                edge.rootKind(),
                edge.rootBlueId(),
                edge.ownerNodeBlueId(),
                edge.ownerScopePath(),
                edge.absolutePointer(),
                edge.ownerRelativePointer(),
                edge.childBlueId(),
                edge.edgeKind(),
                edge.originalPureReference(),
                edge.splitterCreated(),
                edge.declaringScopePath(),
                edge.embeddedOrigin(),
                edge.explicitDeclarationPath(),
                edge.collectionDeclarationPath(),
                edge.collectionMemberKey(),
                edge.handlerEffectiveTypeBlueId(),
                edge.executableBodyField(),
                edge.sourceContributionBlueIds(),
                ValidatedOccurrence.INSTANCE);
    }

    private FragmentEdgeRecord(
            String schemaIdentity,
            CoordinationDocumentSplitter.FragmentRootKind rootKind,
            String rootBlueId,
            String ownerNodeBlueId,
            String ownerScopePath,
            String absolutePointer,
            String ownerRelativePointer,
            String childBlueId,
            CoordinationDocumentSplitter.EdgeKind edgeKind,
            boolean originalPureReference,
            boolean splitterCreated,
            String declaringScopePath,
            CoordinationDocumentSplitter.EmbeddedEdgeOrigin embeddedOrigin,
            String explicitDeclarationPath,
            String collectionDeclarationPath,
            String collectionMemberKey,
            String handlerEffectiveTypeBlueId,
            String executableBodyField,
            List<String> sourceContributionBlueIds,
            ValidatedOccurrence ignored) {
        this.schemaIdentity = schemaIdentity;
        this.rootKind = rootKind;
        this.rootBlueId = rootBlueId;
        this.ownerNodeBlueId = ownerNodeBlueId;
        this.ownerScopePath = ownerScopePath;
        this.absolutePointer = absolutePointer;
        this.ownerRelativePointer = ownerRelativePointer;
        this.childBlueId = childBlueId;
        this.edgeKind = edgeKind;
        this.originalPureReference = originalPureReference;
        this.splitterCreated = splitterCreated;
        this.declaringScopePath = declaringScopePath;
        this.embeddedOrigin = embeddedOrigin;
        this.explicitDeclarationPath = explicitDeclarationPath;
        this.collectionDeclarationPath = collectionDeclarationPath;
        this.collectionMemberKey = collectionMemberKey;
        this.handlerEffectiveTypeBlueId = handlerEffectiveTypeBlueId;
        this.executableBodyField = executableBodyField;
        this.sourceContributionBlueIds = Collections.unmodifiableList(
                new ArrayList<String>(sourceContributionBlueIds));
    }

    private enum ValidatedOccurrence { INSTANCE }

    /** Converts this persistence record to the canonical splitter edge value. */
    public CoordinationDocumentSplitter.EdgeOccurrence toEdgeOccurrence(
            String profileIdentity) {
        return new CoordinationDocumentSplitter.EdgeOccurrence(
                profileIdentity,
                schemaIdentity,
                rootKind,
                rootBlueId,
                ownerNodeBlueId,
                ownerScopePath,
                absolutePointer,
                ownerRelativePointer,
                childBlueId,
                edgeKind,
                originalPureReference,
                splitterCreated,
                declaringScopePath,
                embeddedOrigin,
                explicitDeclarationPath,
                collectionDeclarationPath,
                collectionMemberKey,
                handlerEffectiveTypeBlueId,
                executableBodyField,
                sourceContributionBlueIds);
    }

    public String schemaIdentity() { return schemaIdentity; }
    public CoordinationDocumentSplitter.FragmentRootKind rootKind() {
        return rootKind;
    }
    public String rootBlueId() { return rootBlueId; }
    public String ownerNodeBlueId() { return ownerNodeBlueId; }
    public String ownerScopePath() { return ownerScopePath; }
    public String absolutePointer() { return absolutePointer; }
    public String ownerRelativePointer() { return ownerRelativePointer; }
    public String childBlueId() { return childBlueId; }
    public CoordinationDocumentSplitter.EdgeKind edgeKind() { return edgeKind; }
    public boolean originalPureReference() { return originalPureReference; }
    public boolean splitterCreated() { return splitterCreated; }
    public String declaringScopePath() { return declaringScopePath; }
    public CoordinationDocumentSplitter.EmbeddedEdgeOrigin embeddedOrigin() {
        return embeddedOrigin;
    }
    public String explicitDeclarationPath() { return explicitDeclarationPath; }
    public String collectionDeclarationPath() {
        return collectionDeclarationPath;
    }
    public String collectionMemberKey() { return collectionMemberKey; }
    public String handlerEffectiveTypeBlueId() {
        return handlerEffectiveTypeBlueId;
    }
    public String executableBodyField() { return executableBodyField; }
    public List<String> sourceContributionBlueIds() {
        return sourceContributionBlueIds;
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("schemaIdentity", schemaIdentity);
        map.put("rootKind", rootKind.name());
        map.put("rootBlueId", rootBlueId);
        map.put("ownerNodeBlueId", ownerNodeBlueId);
        map.put("ownerScopePath", ownerScopePath);
        map.put("absolutePointer", absolutePointer);
        map.put("ownerRelativePointer", ownerRelativePointer);
        map.put("childBlueId", childBlueId);
        map.put("edgeKind", edgeKind.name());
        map.put("originalPureReference", originalPureReference);
        map.put("splitterCreated", splitterCreated);
        map.put("declaringScopePath", declaringScopePath);
        map.put("embeddedOrigin", embeddedOrigin.name());
        map.put("explicitDeclarationPath", explicitDeclarationPath);
        map.put("collectionDeclarationPath", collectionDeclarationPath);
        map.put("collectionMemberKey", collectionMemberKey);
        map.put("handlerEffectiveTypeBlueId", handlerEffectiveTypeBlueId);
        map.put("executableBodyField", executableBodyField);
        map.put("sourceContributionBlueIds", sourceContributionBlueIds);
        return map;
    }

    static FragmentEdgeRecord rehydrate(Map<String, ?> map) {
        CoordinationFragmentInventory.requireFields(
                map,
                "fragment edge",
                "schemaIdentity",
                "rootKind",
                "rootBlueId",
                "ownerNodeBlueId",
                "ownerScopePath",
                "absolutePointer",
                "ownerRelativePointer",
                "childBlueId",
                "edgeKind",
                "originalPureReference",
                "splitterCreated",
                "declaringScopePath",
                "embeddedOrigin",
                "explicitDeclarationPath",
                "collectionDeclarationPath",
                "collectionMemberKey",
                "handlerEffectiveTypeBlueId",
                "executableBodyField",
                "sourceContributionBlueIds");
        return new FragmentEdgeRecord(
                CoordinationFragmentInventory.text(map, "schemaIdentity"),
                CoordinationFragmentInventory.enumValue(
                        map,
                        "rootKind",
                        CoordinationDocumentSplitter.FragmentRootKind.class),
                CoordinationFragmentInventory.text(map, "rootBlueId"),
                CoordinationFragmentInventory.text(map, "ownerNodeBlueId"),
                CoordinationFragmentInventory.optionalText(
                        map, "ownerScopePath"),
                CoordinationFragmentInventory.text(map, "absolutePointer"),
                CoordinationFragmentInventory.text(
                        map, "ownerRelativePointer"),
                CoordinationFragmentInventory.text(map, "childBlueId"),
                CoordinationFragmentInventory.enumValue(
                        map,
                        "edgeKind",
                        CoordinationDocumentSplitter.EdgeKind.class),
                CoordinationFragmentInventory.bool(
                        map, "originalPureReference"),
                CoordinationFragmentInventory.bool(map, "splitterCreated"),
                CoordinationFragmentInventory.optionalText(
                        map, "declaringScopePath"),
                CoordinationFragmentInventory.enumValue(
                        map,
                        "embeddedOrigin",
                        CoordinationDocumentSplitter.EmbeddedEdgeOrigin.class),
                CoordinationFragmentInventory.optionalText(
                        map, "explicitDeclarationPath"),
                CoordinationFragmentInventory.optionalText(
                        map, "collectionDeclarationPath"),
                CoordinationFragmentInventory.optionalText(
                        map, "collectionMemberKey"),
                CoordinationFragmentInventory.optionalText(
                        map, "handlerEffectiveTypeBlueId"),
                CoordinationFragmentInventory.optionalText(
                        map, "executableBodyField"),
                CoordinationFragmentInventory.textList(
                        map, "sourceContributionBlueIds"));
    }

    @Override
    public int compareTo(FragmentEdgeRecord other) {
        int compared = rootKind.name().compareTo(other.rootKind.name());
        if (compared != 0) return compared;
        compared = rootBlueId.compareTo(other.rootBlueId);
        if (compared != 0) return compared;
        compared = ownerNodeBlueId.compareTo(other.ownerNodeBlueId);
        if (compared != 0) return compared;
        compared = absolutePointer.compareTo(other.absolutePointer);
        if (compared != 0) return compared;
        compared = edgeKind.name().compareTo(other.edgeKind.name());
        return compared != 0 ? compared : childBlueId.compareTo(other.childBlueId);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof FragmentEdgeRecord
                && toMap().equals(((FragmentEdgeRecord) other).toMap()));
    }

    @Override
    public int hashCode() {
        return toMap().hashCode();
    }

    private static String canonical(String value, String label) {
        return JsonPointer.canonicalize(
                Objects.requireNonNull(value, label));
    }

    private static String canonicalOptional(String value) {
        return value == null ? null : JsonPointer.canonicalize(value);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
