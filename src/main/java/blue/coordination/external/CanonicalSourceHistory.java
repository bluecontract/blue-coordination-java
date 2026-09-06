package blue.coordination.external;

import blue.language.identity.BlueIds;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ClosureResourceDemand;
import blue.language.processor.closure.SourceExecutionBasis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

/**
 * One bounded canonical source-history step over the real evaluator. Preparation
 * only writes immutable evidence; it creates no public lineage, gas settlement,
 * cursor authority or output. The host atomically activates/reuses verified
 * prefixes. A resumed root must come from authenticated source-prefix authority,
 * not merely from an arbitrary self-hashed document.
 *
 * <p>Source construction always starts at intrinsic FULL_HISTORY. An observer's
 * FROM selector never enters source execution. The requested cut only limits how
 * far to prepare; it does not change an individual source operation's identity.</p>
 */
public final class CanonicalSourceHistory {
    private static final String FORMAT = "blue-canonical-source-prefix-step-poc-1";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final CoordinationCore core;
    public enum RecordKind { SEMANTIC_OPERATION, METADATA_PROGRESS }

    public CanonicalSourceHistory(CoordinationCore core) { this.core = Objects.requireNonNull(core); }

    /** Exact inclusive canonical Timeline position, never a wall-clock NOW. */
    public record Request(DocumentId source, ExternalOrderKey inclusiveCut, Map<String, String> originalAdmissionRoots) {
        /** No original input authority: initialization can proceed; selected external input returns a named Need. */
        public Request(DocumentId source, ExternalOrderKey inclusiveCut) { this(source, inclusiveCut, Map.of()); }
        public Request {
            Objects.requireNonNull(source); requireOrder(inclusiveCut); originalAdmissionRoots = Map.copyOf(originalAdmissionRoots);
            originalAdmissionRoots.forEach((entry, root) -> {
                BlueIds.requireBlueIdOrCyclicMember(entry, "originalEntry"); requireDigest(root);
            });
        }
    }

    /** A successful immutable source view, not a mutable host lineage row. */
    public record View(DocumentId source, String blueId, long epoch, boolean terminated, String receiptIdentity) {
        public View {
            Objects.requireNonNull(source); BlueIds.requireBlueIdOrCyclicMember(blueId, "source view");
            if (epoch < 0L) throw invalid("Negative source epoch"); requireDigest(receiptIdentity);
        }
    }

    /** No observer identity, activation mode, attachment cutoff, worker or host fence. */
    public static final class Cursor {
        private final DocumentId source;
        private final String basisIdentity, recordIdentity, semanticPredecessor, originalAdmissionIdentity;
        private final View initialView, successfulView;
        private final ExternalOrderKey handledThrough;
        private final long records, operations;
        private final List<Publication> publications;
        private final RecordKind recordKind;
        private Cursor(DocumentId source, String basisIdentity, String recordIdentity, String semanticPredecessor,
                       View initialView, View successfulView, ExternalOrderKey handledThrough, long records, long operations,
                       List<Publication> publications, RecordKind recordKind, String originalAdmissionIdentity) {
            this.source = source; this.basisIdentity = basisIdentity; this.recordIdentity = recordIdentity;
            this.semanticPredecessor = semanticPredecessor; this.initialView = initialView;
            this.successfulView = successfulView; this.handledThrough = handledThrough;
            this.records = records; this.operations = operations;
            this.publications = List.copyOf(publications);
            this.recordKind = recordKind;
            this.originalAdmissionIdentity = originalAdmissionIdentity;
        }
        public DocumentId source() { return source; }
        public String basisIdentity() { return basisIdentity; }
        public Optional<String> recordIdentity() { return Optional.ofNullable(recordIdentity); }
        public Optional<String> semanticPredecessor() { return Optional.ofNullable(semanticPredecessor); }
        public Optional<View> initialView() { return Optional.ofNullable(initialView); }
        public Optional<View> successfulView() { return Optional.ofNullable(successfulView); }
        public Optional<ExternalOrderKey> handledThrough() { return Optional.ofNullable(handledThrough); }
        public long records() { return records; }
        public long operations() { return operations; }
        /** All fresh publications at this record, dependency-first, not one aggregate operation. */
        public List<Publication> publications() { return publications; }
        public Optional<RecordKind> recordKind() { return Optional.ofNullable(recordKind); }
        public Optional<String> originalAdmissionIdentity() { return Optional.ofNullable(originalAdmissionIdentity); }
    }

    public sealed interface Result permits Await, Step, Complete, Blocked { }
    public record Await(List<String> keys, List<ClosureResourceDemand> resourceDemands) implements Result {
        public Await(List<String> keys) { this(keys, List.of()); }
        public Await {
            var need = new CoordinationCore.NeedEvidence(keys, resourceDemands);
            keys = need.keys(); resourceDemands = need.resourceDemands();
        }
    }
    public record Publication(String operationIdentity, Set<DocumentId> ownedLineages, String receiptIdentity) {
        public Publication {
            if (operationIdentity == null || !operationIdentity.matches("sha256:[0-9a-f]{64}")) throw invalid("Invalid publication operation identity");
            ownedLineages = Set.copyOf(ownedLineages); if (ownedLineages.isEmpty()) throw invalid("Publication requires owned lineages");
            requireDigest(receiptIdentity);
        }
    }
    /** One source record retains every independently atomic prerequisite/group proposal; none is discarded. */
    public record Step(Cursor before, Cursor after, CoordinationCore.EvaluationResult evaluation,
                       String receiptIdentity, List<Publication> publications) implements Result {
        public Step {
            Objects.requireNonNull(before); Objects.requireNonNull(after); Objects.requireNonNull(evaluation); requireDigest(receiptIdentity);
            publications = List.copyOf(publications);
            if (!after.publications().equals(publications)) throw invalid("Prefix step lost its retained publication linkage");
            if (evaluation instanceof CoordinationCore.PreparedOperations groups) {
                if (groups.operations().size() != publications.size()) throw invalid("Prefix step omitted a fresh prerequisite group");
                for (int i = 0; i < publications.size(); i++) {
                    var actual = groups.operations().get(i); var publication = publications.get(i);
                    if (!actual.operationId().equals(publication.operationIdentity()) || !actual.ownedLineages().equals(publication.ownedLineages()))
                        throw invalid("Prefix publication differs from the complete owning group result");
                }
            } else if (evaluation instanceof CoordinationCore.PreparedOperation operation) {
                if (publications.size() != 1 || !operation.operationId().equals(publications.get(0).operationIdentity())
                        || !operation.ownedLineages().equals(publications.get(0).ownedLineages())) throw invalid("Prefix initialization publication mismatch");
            } else if (!publications.isEmpty()) throw invalid("Metadata-only prefix record cannot create a publication");
        }
    }
    /** No new operation: completeness only certifies that no selected input was omitted through the cut. */
    public record Complete(Boundary boundary) implements Result { }
    /** Failed initialization/non-consumable result cannot create a usable source prefix. */
    public record Blocked(CoordinationCore.PreparedOperation attempt, String receiptIdentity) implements Result { }

    public static final class Boundary {
        private final Cursor cursor;
        private final ExternalOrderKey cut;
        private final Map<String, Long> completeBefore;
        private Boundary(Cursor cursor, ExternalOrderKey cut, Map<String, Long> completeBefore) {
            this.cursor = cursor; this.cut = cut; this.completeBefore = Map.copyOf(completeBefore);
        }
        public Cursor cursor() { return cursor; }
        public ExternalOrderKey cut() { return cut; }
        public Map<String, Long> completeBefore() { return completeBefore; }
    }

    public Cursor start(DocumentId source) {
        return new Cursor(Objects.requireNonNull(source), basis(source), null, null, null, null, null, 0L, 0L, List.of(), null, null);
    }

    public Result prepareNext(Request request, Cursor cursor, CoordinationCore.EvaluationEvidence evidence,
                              FrozenNodeEvidenceCodec.Writer writer, FrozenNodeEvidenceCodec.Limits limits) {
        Objects.requireNonNull(request); Objects.requireNonNull(cursor); Objects.requireNonNull(evidence);
        if (!request.source().equals(cursor.source) || !basis(request.source()).equals(cursor.basisIdentity))
            throw invalid("Source cursor belongs to another canonical source basis");
        if (cursor.handledThrough != null && cursor.handledThrough.compareTo(request.inclusiveCut()) > 0)
            throw invalid("An ahead source cursor is not the historical view at an earlier attachment cut");
        ManagedDocumentSnapshot target = evidence.snapshot().managedDocument(request.source());
        if (target == null) return new Await(List.of("lineage:" + request.source().value()));
        if (cursor.successfulView == null && target.initialized())
            return new Await(List.of("canonical-source-prefix:" + request.source().value()));
        if (cursor.successfulView == null && (!target.documentId().value().equals(target.blueId())
                || target.epoch() != 0L || target.terminated()))
            throw invalid("Canonical source birth requires the exact authored lineage origin at epoch zero");
        if (cursor.successfulView == null) {
            for (var component : evidence.snapshot().components()) {
                if (!component.orderedMemberDocumentIds().contains(request.source())) continue;
                for (DocumentId member : component.orderedMemberDocumentIds()) {
                    ManagedDocumentSnapshot initial = evidence.snapshot().managedDocument(member);
                    if (initial == null || initial.initialized() || initial.terminated() || initial.epoch() != 0L
                            || !initial.documentId().value().equals(initial.blueId()))
                        throw invalid("Every newly initialized cyclic member requires its exact authored origin");
                }
            }
        }
        if (cursor.successfulView != null && (!target.initialized()
                || !target.blueId().equals(cursor.successfulView.blueId()) || target.epoch() != cursor.successfulView.epoch()
                || target.terminated() != cursor.successfulView.terminated()))
            throw invalid("Source read cut does not match its exact successful prefix view");
        Map<DocumentId, String> predecessors = new TreeMap<>(evidence.precedingOperations());
        if (cursor.semanticPredecessor == null) predecessors.remove(request.source());
        else predecessors.put(request.source(), cursor.semanticPredecessor);
        List<CoordinationCore.TimelinePrefix> bounded = evidence.prefixes().stream().map(prefix ->
                new CoordinationCore.TimelinePrefix(prefix.timelineId(), prefix.exclusiveCompleteBeforeMicros(),
                        prefix.inputs().stream().filter(input -> input.order().compareTo(request.inclusiveCut()) <= 0).toList())).toList();
        var selected = evidence.withHistoryCut(bounded, cursor.handledThrough(), predecessors);
        var kind = cursor.successfulView == null ? CoordinationCore.OperationKind.INITIALIZATION : CoordinationCore.OperationKind.EXTERNAL_INPUT;
        var result = core.evaluateCanonicalSource(new CoordinationCore.WorkIntent(request.source(), kind), selected,
                request.originalAdmissionRoots(), cursor.basisIdentity);
        if (result instanceof CoordinationCore.NeedEvidence need) return new Await(need.keys(), need.resourceDemands());
        if (result instanceof CoordinationCore.Idle) return complete(request, cursor, evidence);
        FrozenNodeEvidenceCodec.Encoder encoder = new FrozenNodeEvidenceCodec.Encoder(writer, limits);
        String receipt, semanticPredecessor = cursor.semanticPredecessor;
        View initial = cursor.initialView, successful = cursor.successfulView;
        ExternalOrderKey through = cursor.handledThrough;
        long operationCount = cursor.operations;
        List<Publication> publications = new ArrayList<>();
        RecordKind recordKind = RecordKind.SEMANTIC_OPERATION;
        if (result instanceof CoordinationCore.PreparedOperation operation) {
            receipt = OperationReceiptCodec.encode(operation, encoder).receiptIdentity();
            if (operation.disposition() != CoordinationCore.Disposition.CONSUMED)
                return new Blocked(operation, receipt);
            if (!operation.invocation().environment().runtimeRegistryIdentity().equals(core.environment().runtimeRegistryIdentity())
                    || !operation.invocation().executionPolicy().identity().equals(core.executionPolicy().identity()))
                throw invalid("Source operation changed its canonical execution basis");
            var projection = operation.projections().stream().filter(p -> p.lineage().equals(request.source())).findFirst()
                    .orElseThrow(() -> invalid("Source operation omitted the requested lineage"));
            if (cursor.successfulView == null && !operation.result().commits())
                throw invalid("Failed initialization cannot create source history");
            if (cursor.successfulView == null && operation.projections().stream()
                    .anyMatch(p -> !p.result().initialized() || p.result().terminated()))
                return new Blocked(operation, receipt); // Graceful termination is not a reusable init0.
            if (operation.result().commits()) successful = new View(request.source(), projection.afterBlueId(), projection.afterEpoch(),
                    projection.result().terminated(), receipt);
            else verifyFailedProjection(successful, projection);
            if (initial == null) initial = successful;
            semanticPredecessor = operation.operationId(); operationCount = Math.addExact(operationCount, 1L);
            through = operation.input().map(CoordinationCore.TimelineInput::order).orElse(through);
            publications.add(new Publication(operation.operationId(), operation.ownedLineages(), receipt));
        } else if (result instanceof CoordinationCore.PreparedOperations groups) {
            CoordinationCore.PreparedGroupOperation selectedGroup = null; String selectedReceipt = null;
            for (var group : groups.operations()) {
                if (!group.invocation().environment().runtimeRegistryIdentity().equals(core.environment().runtimeRegistryIdentity())
                        || !group.invocation().executionPolicy().identity().equals(core.executionPolicy().identity())
                        || group.disposition() != CoordinationCore.Disposition.CONSUMED)
                    throw invalid("Source group changed canonical execution basis or is not consumable");
                String groupReceipt = OperationReceiptCodec.encode(group, encoder).receiptIdentity();
                publications.add(new Publication(group.operationId(), group.ownedLineages(), groupReceipt));
                if (group.ownedLineages().contains(request.source())) {
                    if (selectedGroup != null) throw invalid("Two independent operations claim the requested source");
                    selectedGroup = group; selectedReceipt = groupReceipt;
                }
            }
            if (successful == null) throw invalid("External source groups require an initialized source prefix");
            if (groups.targetProgress().isPresent()) {
                var progress = groups.targetProgress().orElseThrow();
                if (selectedGroup != null || !progress.lineage().equals(request.source())) throw invalid("Source metadata conflicts with a fresh owning group");
                recordKind = RecordKind.METADATA_PROGRESS; through = progress.input().order();
                receipt = metadataReceipt(progress, cursor, semanticPredecessor, encoder);
            } else {
                if (selectedGroup == null) throw invalid("Source group wrapper omitted its explicit target disposition");
                var group = selectedGroup;
                var projection = group.projections().stream().filter(p -> p.lineage().equals(request.source())).findFirst()
                        .orElseThrow(() -> invalid("Source group omitted its owning projection"));
                receipt = selectedReceipt;
                if (group.result().status() == blue.language.processor.ProcessorStatus.SUCCESS)
                    successful = new View(request.source(), projection.afterBlueId(), projection.afterEpoch(), projection.result().terminated(), receipt);
                else verifyFailedProjection(successful, projection);
                semanticPredecessor = group.operationId(); operationCount = Math.addExact(operationCount, 1L);
                through = group.input().orElseThrow(() -> invalid("Source group has no exact Timeline position")).order();
            }
        } else if (result instanceof CoordinationCore.MetadataProgress progress) {
            if (successful == null) throw invalid("Uninitialized source cannot consume Timeline progress");
            through = progress.input().order();
            recordKind = RecordKind.METADATA_PROGRESS;
            receipt = metadataReceipt(progress, cursor, semanticPredecessor, encoder);
        } else throw invalid("Unexpected canonical source result");
        long records = Math.addExact(cursor.records, 1L);
        String originalAdmission = null;
        Map<String, Object> originalAdmissionData = null;
        if (kind == CoordinationCore.OperationKind.EXTERNAL_INPUT) {
            String root = request.originalAdmissionRoots().get(through.components().get(1));
            var admitted = selected.sourceInputAdmissions().stream().filter(value -> value.identity().equals(root)).findFirst()
                    .orElseThrow(() -> invalid("Selected source input lost its verified original admission"));
            originalAdmission = admitted.identity(); originalAdmissionData = admitted.value();
        }
        Map<String, Object> row = map("format", FORMAT, "source", request.source().value(), "basis", cursor.basisIdentity,
                "previous", cursor.recordIdentity, "receipt", receipt, "semanticPredecessor", semanticPredecessor,
                "initial", view(initial), "successful", view(successful), "through", order(through),
                "records", records, "operations", operationCount, "recordKind", recordKind.name(),
                "publicationCount", publications.size(), "publications", publications(publications),
                "originalAdmission", originalAdmission, "originalAdmissionData", originalAdmissionData,
                "originalInputPredecessor", cursor.semanticPredecessor);
        String record = encoder.blob(bytes(row));
        Cursor after = new Cursor(request.source(), cursor.basisIdentity, record, semanticPredecessor,
                initial, successful, through, records, operationCount, publications, recordKind, originalAdmission);
        return new Step(cursor, after, result, receipt, publications);
    }

    private static void verifyFailedProjection(View successful, CoordinationCore.LineageProjection projection) {
        if (successful == null || !successful.source().equals(projection.lineage()) || !successful.blueId().equals(projection.afterBlueId())
                || successful.epoch() != projection.afterEpoch() || successful.terminated() != projection.result().terminated())
            throw invalid("Failed source operation changed its last successful canonical view");
    }

    /** Loads one authenticated prefix head, never walks/reloads its entire history. */
    public Cursor resume(DocumentId source, String authenticatedRecordIdentity,
                         FrozenNodeEvidenceCodec.Reader reader, FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        JsonNode row = json(decoder.blob(authenticatedRecordIdentity));
        if (!FORMAT.equals(text(row, "format")) || !source.value().equals(text(row, "source"))
                || !basis(source).equals(text(row, "basis"))) throw invalid("Wrong canonical source prefix basis");
        requireDigest(text(row, "receipt"));
        String previous = nullableText(row, "previous"); if (previous != null) requireDigest(previous);
        long records = nonnegative(row, "records"), operations = nonnegative(row, "operations");
        if (records < 1 || operations < 1 || operations > records || (records == 1) != (previous == null))
            throw invalid("Invalid source-prefix physical/semantic progress counts");
        String predecessor = text(row, "semanticPredecessor");
        if (!predecessor.matches("sha256:[0-9a-f]{64}")) throw invalid("Malformed source semantic predecessor");
        View initial = parseView(source, row.path("initial")), successful = parseView(source, row.path("successful"));
        if (initial.epoch() > successful.epoch()) throw invalid("Source successful epoch precedes initialization");
        ExternalOrderKey through = row.path("through").isNull() ? null : parseOrder(row.path("through"));
        if ((records == 1) != (through == null)) throw invalid("Source input disposition position missing");
        String originalAdmission = nullableText(row, "originalAdmission");
        if (through == null) {
            if (originalAdmission != null) throw invalid("Intrinsic initialization has no external input admission");
        } else {
            if (originalAdmission == null) throw invalid("External source prefix lost original input admission authority");
            SourceInputAdmission.restoreInline(originalAdmission, row.get("originalAdmissionData")).verifyPrefix(source, basis(source), through,
                    nullableText(row, "originalInputPredecessor"));
        }
        List<Publication> publications = parsePublications(row);
        RecordKind recordKind = RecordKind.valueOf(text(row, "recordKind"));
        if (recordKind == RecordKind.SEMANTIC_OPERATION) {
            Publication owner = publications.stream().filter(value -> value.ownedLineages().contains(source)).findFirst()
                    .orElseThrow(() -> invalid("Source record publications omit its owning group"));
            if (!owner.operationIdentity().equals(predecessor) || !owner.receiptIdentity().equals(text(row, "receipt")))
                throw invalid("Source prefix head differs from its retained owning receipt");
        } else if (publications.stream().anyMatch(value -> value.ownedLineages().contains(source))) {
            throw invalid("Metadata-only source record cannot own a fresh semantic publication");
        }
        return new Cursor(source, basis(source), authenticatedRecordIdentity, predecessor, initial, successful,
                through, records, operations, publications, recordKind, originalAdmission);
    }

    private static String metadataReceipt(CoordinationCore.MetadataProgress progress, Cursor cursor, String semanticPredecessor,
            FrozenNodeEvidenceCodec.Encoder encoder) {
        return encoder.blob(bytes(map("format", "blue-canonical-source-metadata-progress-poc-1", "source", progress.lineage().value(),
                "basis", cursor.basisIdentity, "semanticPredecessor", semanticPredecessor, "timeline", progress.input().timelineId(),
                "order", progress.input().order().components(), "entry", progress.input().entry().blueId(),
                "entryBody", encoder.node(blue.language.snapshot.FrozenNode.fromResolvedNode(progress.input().entry().copyNode())),
                "consumedSourceOperations", progress.consumedSourceOperations())));
    }

    private static List<Object> publications(List<Publication> publications) {
        return publications.stream().map(publication -> (Object) map("operation", publication.operationIdentity(),
                "owners", publication.ownedLineages().stream().sorted().map(DocumentId::value).toList(), "receipt", publication.receiptIdentity())).toList();
    }
    private static List<Publication> parsePublications(JsonNode root) {
        JsonNode rows = root.get("publications"); if (rows == null || !rows.isArray()) throw invalid("Missing prefix publication linkage");
        if (nonnegative(root, "publicationCount") != rows.size()) throw invalid("Prefix omitted a retained prerequisite publication");
        List<Publication> result = new ArrayList<>(); Set<String> operations = new HashSet<>(); Set<DocumentId> owners = new HashSet<>();
        for (JsonNode row : rows) {
            JsonNode members = row.get("owners"); if (members == null || !members.isArray()) throw invalid("Invalid prefix publication owners");
            Set<DocumentId> own = new TreeSet<>();
            for (JsonNode member : members) {
                if (!member.isTextual() || !own.add(new DocumentId(member.textValue()))) throw invalid("Duplicate or invalid prefix publication owner");
            }
            Publication value = new Publication(text(row, "operation"), own, text(row, "receipt"));
            if (!operations.add(value.operationIdentity()) || !Collections.disjoint(owners, own)) throw invalid("Prefix publications overlap operation or lineage ownership");
            owners.addAll(own); result.add(value);
        }
        return List.copyOf(result);
    }

    private Result complete(Request request, Cursor cursor, CoordinationCore.EvaluationEvidence evidence) {
        if (cursor.successfulView == null) throw invalid("Uninitialized source cannot be complete");
        Map<String, Long> guarantees = new TreeMap<>();
        for (var prefix : evidence.prefixes()) {
            if (evidence.relevantTimelines().contains(prefix.timelineId())) {
                if (guarantees.put(prefix.timelineId(), prefix.exclusiveCompleteBeforeMicros()) != null)
                    throw invalid("Duplicate source Timeline completeness");
            }
        }
        List<String> missing = new ArrayList<>();
        long timestamp = ((BigInteger) request.inclusiveCut().components().get(0)).longValueExact();
        for (String timeline : new TreeSet<>(evidence.relevantTimelines())) {
            if (!guarantees.containsKey(timeline)) missing.add("timeline-prefix:" + timeline);
            else if (guarantees.get(timeline) <= timestamp) missing.add("timeline-complete-after:" + timeline + ":" + timestamp);
        }
        return missing.isEmpty() ? new Complete(new Boundary(cursor, request.inclusiveCut(), guarantees)) : new Await(missing);
    }

    private String basis(DocumentId source) {
        return SourceExecutionBasis.identity(source, core.environment(), core.executionPolicy());
    }

    static void requireOrder(ExternalOrderKey order) {
        Objects.requireNonNull(order, "order"); var values = order.components();
        if (values.size() != 2 || !(values.get(0) instanceof BigInteger timestamp)
                || timestamp.signum() <= 0 || timestamp.compareTo(BigInteger.valueOf(9_007_199_254_740_991L)) >= 0
                || !(values.get(1) instanceof String)) throw invalid("Expected exact (microseconds, Entry BlueId) position");
        BlueIds.requirePlainBlueId((String) values.get(1), "Timeline entry position");
    }
    private static Object order(ExternalOrderKey value) { return value == null ? null : value.components(); }
    private static Object view(View value) { return map("blueId", value.blueId(), "epoch", value.epoch(),
            "terminated", value.terminated(), "receipt", value.receiptIdentity()); }
    private static View parseView(DocumentId source, JsonNode row) {
        if (!row.path("terminated").isBoolean()) throw invalid("Malformed source termination flag");
        return new View(source, text(row, "blueId"), nonnegative(row, "epoch"), row.path("terminated").booleanValue(), text(row, "receipt"));
    }
    private static ExternalOrderKey parseOrder(JsonNode row) {
        if (!row.isArray() || row.size() != 2 || !row.get(0).isIntegralNumber() || !row.get(1).isTextual())
            throw invalid("Malformed source position");
        ExternalOrderKey result = ExternalOrderKey.of(List.of(row.get(0).bigIntegerValue(), row.get(1).textValue()));
        requireOrder(result); return result;
    }
    private static long nonnegative(JsonNode node, String key) {
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToLong() || node.path(key).longValue() < 0)
            throw invalid("Invalid nonnegative prefix field " + key);
        return node.path(key).longValue();
    }
    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).textValue().isEmpty()) throw invalid("Missing prefix text " + key);
        return node.path(key).textValue();
    }
    private static String nullableText(JsonNode node, String key) {
        if (!node.has(key)) throw invalid("Missing prefix field " + key);
        return node.path(key).isNull() ? null : text(node, key);
    }
    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid("Expected exact immutable fragment digest");
    }
    private static Map<String, Object> map(Object... fields) {
        Map<String, Object> result = new TreeMap<>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    private static byte[] bytes(Object value) {
        try { return JSON.writeValueAsBytes(value); } catch (IOException failure) { throw invalid("Invalid prefix evidence: " + failure.getMessage()); }
    }
    private static JsonNode json(byte[] value) {
        try { return JSON.readTree(value); } catch (IOException failure) { throw invalid("Malformed prefix evidence: " + failure.getMessage()); }
    }
    private static InvalidExecutionEvidenceException invalid(String message) { return new InvalidExecutionEvidenceException(message); }
}
