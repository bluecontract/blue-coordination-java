package blue.coordination.external;

import blue.language.identity.BlueIds;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import blue.language.processor.closure.SameOriginAttachmentPolicy;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Set;

/**
 * Frozen choices from original logical input admission, not an observer's reconstruction options.
 * Encoding produces candidate bytes only. The independently admitted root must come from the
 * original input/policy authority; neither a preparation worker nor this decoder authenticates it.
 * A single original group input can be projected through any of its named source members.
 */
public final class SourceInputAdmission {
    private static final String FORMAT = "blue-original-source-input-admission-poc-1";
    private final String identity, timeline, entry, event;
    private final ExternalOrderKey order;
    private final Map<DocumentId, String> sourceBases, predecessors;
    private final SameOriginAttachmentPolicy selections;

    private SourceInputAdmission(String identity, String timeline, String entry, String event, ExternalOrderKey order,
                                 Map<DocumentId, String> sourceBases, Map<DocumentId, String> predecessors,
                                 SameOriginAttachmentPolicy selections) {
        this.identity = identity;
        this.timeline = Objects.requireNonNull(timeline);
        this.entry = BlueIds.requireBlueIdOrCyclicMember(entry, "entry");
        this.event = BlueIds.requireBlueIdOrCyclicMember(event, "event");
        CanonicalSourceHistory.requireOrder(order); this.order = order;
        if (!entry.equals(order.components().get(1))) throw invalid("Admission order does not identify its exact Entry");
        if (sourceBases.isEmpty()) throw invalid("Original source admission requires source ownership");
        sourceBases.values().forEach(SourceInputAdmission::digest);
        predecessors.values().forEach(SourceInputAdmission::sha);
        if (!sourceBases.keySet().containsAll(predecessors.keySet())) throw invalid("Admission has a foreign predecessor");
        this.sourceBases = Map.copyOf(sourceBases); this.predecessors = Map.copyOf(predecessors);
        this.selections = Objects.requireNonNull(selections);
        for (var choice : selections.entries()) if (!sourceBases.containsKey(choice.creatorLineage()))
            throw invalid("Attachment choice belongs to another original source input");
    }

    /** Candidate content is not authority. Original input admission must independently bind the returned root. */
    public static String encodeCandidate(CoordinationCore.TimelineInput input, Map<DocumentId, String> sourceBases,
                                         Map<DocumentId, String> originalPredecessors, SameOriginAttachmentPolicy selections,
                                         FrozenNodeEvidenceCodec.Writer writer, FrozenNodeEvidenceCodec.Limits limits) {
        var candidate = new SourceInputAdmission(null, input.timelineId(), input.entry().blueId(), input.event().blueId(),
                input.order(), sourceBases, originalPredecessors, selections);
        return new FrozenNodeEvidenceCodec.Encoder(writer, limits).blob(OperationReceiptCodec.bytes(candidate.value()));
    }

    /** Restore only against the original input's externally authenticated admission root. */
    public static SourceInputAdmission restore(String authenticatedOriginalAdmission, FrozenNodeEvidenceCodec.Reader reader,
                                               FrozenNodeEvidenceCodec.Limits limits) {
        return restore(authenticatedOriginalAdmission, new FrozenNodeEvidenceCodec.Decoder(reader, limits));
    }

    static SourceInputAdmission restore(String authenticatedOriginalAdmission, FrozenNodeEvidenceCodec.Decoder decoder) {
        digest(authenticatedOriginalAdmission);
        return restoreInline(authenticatedOriginalAdmission, OperationReceiptCodec.json(decoder.blob(authenticatedOriginalAdmission)));
    }

    static SourceInputAdmission restoreInline(String authenticatedOriginalAdmission, JsonNode root) {
        digest(authenticatedOriginalAdmission);
        if (root == null || !root.isObject()) throw invalid("Missing retained original admission payload");
        if (!FORMAT.equals(text(root, "format"))) throw invalid("Unexpected original source admission format");
        List<SameOriginAttachmentPolicy.Selection> choices = new ArrayList<>();
        if (!root.path("selections").isArray()) throw invalid("Missing original selections (empty must be explicit)");
        for (var choice : root.path("selections")) choices.add(new SameOriginAttachmentPolicy.Selection(
                SameOriginAttachmentPolicy.Mode.valueOf(text(choice, "mode")), new DocumentId(text(choice, "creator")),
                text(choice, "occurrence"), new DocumentId(text(choice, "target")), text(choice, "supplied"),
                choice.path("frontier").isNull() ? null : order(choice.get("frontier"))));
        var result = new SourceInputAdmission(authenticatedOriginalAdmission, text(root, "timeline"), text(root, "entry"),
                text(root, "event"), order(root.get("order")), documentMap(root.get("sourceBases")),
                documentMap(root.get("predecessors")), new SameOriginAttachmentPolicy(choices));
        if (!authenticatedOriginalAdmission.equals(FrozenNodeEvidenceCodec.digest(OperationReceiptCodec.bytes(result.value()))))
            throw invalid("Original source admission is not a closed canonical record");
        return result;
    }

    public String identity() { return identity; }
    public String entryBlueId() { return entry; }
    public Map<DocumentId, String> sourceBases() { return sourceBases; }
    public Map<DocumentId, String> originalPredecessors() { return predecessors; }
    public SameOriginAttachmentPolicy selections() { return selections; }

    void verifyPrefix(DocumentId source, String basis, ExternalOrderKey through, String originalPredecessor) {
        if (!basis.equals(sourceBases.get(source)) || !order.equals(through)
                || !Objects.equals(predecessors.get(source), originalPredecessor))
            throw invalid("Cold prefix does not retain its exact original source admission");
    }

    void verify(DocumentId source, String expectedBasis, CoordinationCore.TimelineInput selected,
                Map<DocumentId, String> actualPredecessors, Set<DocumentId> liveMembers, Set<DocumentId> originalOwned) {
        if (!liveMembers.containsAll(sourceBases.keySet()) || !sourceBases.keySet().containsAll(originalOwned))
            throw invalid("Original admission has foreign members or omits an original co-owned member");
        if (!expectedBasis.equals(sourceBases.get(source)) || !timeline.equals(selected.timelineId())
                || !entry.equals(selected.entry().blueId()) || !event.equals(selected.event().blueId())
                || !order.equals(selected.order())) throw invalid("Admission belongs to another source basis or original input");
        for (DocumentId member : sourceBases.keySet()) if (!Objects.equals(predecessors.get(member), actualPredecessors.get(member)))
            throw invalid("Original source admission predecessor changed");
    }

    Map<String, Object> value() {
        List<Object> choices = new ArrayList<>();
        for (var choice : selections.entries()) {
            Map<String, Object> row = new TreeMap<>();
            row.put("mode", choice.mode().name()); row.put("creator", choice.creatorLineage().value());
            row.put("occurrence", choice.occurrenceIdentity()); row.put("target", choice.targetLineage().value());
            row.put("supplied", choice.suppliedExactRefBlueId());
            row.put("frontier", choice.frontier().map(ExternalOrderKey::components).orElse(null)); choices.add(row);
        }
        return Map.of("format", FORMAT, "timeline", timeline, "entry", entry, "event", event, "order", order.components(),
                "sourceBases", strings(sourceBases), "predecessors", strings(predecessors), "selections", choices);
    }

    private static Map<String, String> strings(Map<DocumentId, String> values) {
        Map<String, String> result = new TreeMap<>(); values.forEach((id, value) -> result.put(id.value(), value)); return result;
    }
    private static Map<DocumentId, String> documentMap(JsonNode values) {
        if (values == null || !values.isObject()) throw invalid("Missing original admission context");
        Map<DocumentId, String> result = new TreeMap<>();
        values.fields().forEachRemaining(value -> {
            if (!value.getValue().isTextual()) throw invalid("Invalid original admission context value");
            result.put(new DocumentId(value.getKey()), value.getValue().textValue());
        }); return result;
    }
    private static ExternalOrderKey order(JsonNode value) {
        if (value == null || !value.isArray() || value.size() != 2 || !value.get(0).isIntegralNumber() || !value.get(1).isTextual())
            throw invalid("Invalid original admission order");
        var result = ExternalOrderKey.of(List.of(value.get(0).bigIntegerValue(), value.get(1).textValue()));
        CanonicalSourceHistory.requireOrder(result); return result;
    }
    private static String text(JsonNode value, String field) { return OperationReceiptCodec.text(value, field); }
    private static void digest(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid("Expected authenticated admission digest"); }
    private static void sha(String value) { if (value == null || !value.matches("sha256:[0-9a-f]{64}")) throw invalid("Expected original semantic predecessor"); }
    private static InvalidExecutionEvidenceException invalid(String message) { return new InvalidExecutionEvidenceException(message); }
}
