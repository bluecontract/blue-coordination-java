package blue.coordination.external;

import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.*;

/**
 * Durable data-only receipt for a completed external-state operation. The expected
 * receipt digest must come from a committed, library-validated host operation.
 * Transport hashes establish byte integrity, not execution authority. Physical
 * fences, worker identities and public/private hosting roles are not serialized.
 */
public final class OperationReceiptCodec {
    static final String FORMAT = "blue-coordination-operation-receipt-poc-1";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private OperationReceiptCodec() { }

    public record Encoded(String receiptIdentity, Optional<String> sourceProgramIdentity) { }

    /** Body identity is a transport fragment root, not the document BlueId. */
    public record OwnedState(DocumentId lineage, String precedingOperation,
                             String beforeBlueId, String afterBlueId, long beforeEpoch, long afterEpoch,
                             boolean initialized, boolean terminated, String componentIdentity,
                             String componentStateIdentity, Long memberIndex, String bodyIdentity) { }

    public record Input(String timelineId, long timestampMicros, String entryBlueId,
                        String eventBlueId, String entryIdentity, String eventIdentity) { }

    /** Group receipts have a canonical group ordinal and intrinsic identity, but no global encounter ordinal. */
    public record Event(long ordinal, Long occurrenceOrdinal, DocumentId lineage,
                        String occurrenceIdentity, String blueId, String bodyIdentity) { }

    public record Diagnostic(String category, String message, Map<String, String> details) {
        public Diagnostic { details = Map.copyOf(details); }
    }

    /** The consumer's successful pin before a consumed offer, not the provider's current head. */
    public record ObservedSource(DocumentId consumer, DocumentId lineage, String blueId, long epoch) { }

    /** Historical exact dependency view, not another mutable lineage or owned result. */
    public record ReadPin(DocumentId lineage, String blueId, String bodyIdentity, List<String> cyclicProofFragments) {
        public ReadPin { cyclicProofFragments = List.copyOf(cyclicProofFragments); }
    }

    /** Trace fragments are individually addressed so large traces need not be copied into every receipt. */
    public record GasSettlement(long total, String traceIdentity, List<String> traceFragments,
                                Optional<String> rejectedChargeFragment, Optional<String> rejectedWorkIdentity,
                                Optional<Diagnostic> diagnostic) {
        public GasSettlement { traceFragments = List.copyOf(traceFragments); }
    }

    public record RestoredGas(List<GasTraceEntry> trace, Optional<RejectedCharge> rejectedCharge,
                              Optional<SameOriginRejectedChargeEvidence> sameOriginRejectedCharge,
                              Optional<RejectedAdmission> rejectedAdmission) {
        public RestoredGas { trace = List.copyOf(trace); }
    }

    public record AdmissionContribution(Set<DocumentId> members, long admitted, long reserved,
                                        Map<String, Long> admittedLocal, Map<String, Long> reservedLocal) {
        public AdmissionContribution { members = Set.copyOf(members); admittedLocal = Map.copyOf(admittedLocal); reservedLocal = Map.copyOf(reservedLocal); }
    }
    public record RejectedAdmission(long limit, String localDocument, long localLimit, List<AdmissionContribution> contributions) {
        public RejectedAdmission { contributions = List.copyOf(contributions); }
    }
    /** Original seed/work identities remain distinct from final settlement authority. */
    public record SameOriginSettlement(SameOriginGroupEvidence group, Optional<String> failureSite,
                                       Optional<SameOriginRejectedChargeEvidence> rejectedCharge,
                                       Optional<RejectedAdmission> rejectedAdmission) { }

    public record Effects(List<ManagedOccurrenceBinding> bindings, List<GraphChange> graphChanges,
                          List<SubscriptionDelta> subscriptions, List<CheckpointWrite> checkpoints) {
        public Effects { bindings = List.copyOf(bindings); graphChanges = List.copyOf(graphChanges);
            subscriptions = List.copyOf(subscriptions); checkpoints = List.copyOf(checkpoints); }
    }

    public record Receipt(String operationId, CoordinationCore.OperationKind kind,
                          CoordinationCore.Disposition disposition, ProcessorStatus status,
                          String causeIdentity, Optional<Input> input, List<OwnedState> states,
                          List<CoordinationCore.SourcePin> sourcePins, List<String> consumedSourceOperations,
                          List<ObservedSource> observedSources, Map<String, String> semanticPredecessors,
                          Map<String, String> executionIdentities,
                          List<ReadPin> readPins,
                          GasSettlement gas, List<Event> events, Map<String, String> resultIdentities,
                          String effectsIdentity,
                          Optional<String> sourceFailureIdentity,
                          Optional<String> sourceProgramIdentity,
                          Optional<String> sameOriginIdentity,
                          Optional<String> managedReactionIdentity,
                          Optional<String> managedLanesIdentity) {
        public Receipt {
            states = List.copyOf(states); sourcePins = List.copyOf(sourcePins);
            consumedSourceOperations = List.copyOf(consumedSourceOperations);
            observedSources = List.copyOf(observedSources); semanticPredecessors = Map.copyOf(semanticPredecessors);
            executionIdentities = Map.copyOf(executionIdentities);
            readPins = List.copyOf(readPins);
            events = List.copyOf(events); resultIdentities = Map.copyOf(resultIdentities);
        }
    }

    public static Encoded encode(CoordinationCore.PreparedOperation operation,
                                 FrozenNodeEvidenceCodec.Writer writer, FrozenNodeEvidenceCodec.Limits limits) {
        Objects.requireNonNull(operation);
        FrozenNodeEvidenceCodec.Encoder encoder = new FrozenNodeEvidenceCodec.Encoder(writer, limits);
        return encode(operation, encoder);
    }

    /** Retains one actual independently atomic group, never a synthetic whole-closure result. */
    public static Encoded encode(SameOriginOperationResult operation,
                                 FrozenNodeEvidenceCodec.Writer writer, FrozenNodeEvidenceCodec.Limits limits) {
        return SameOriginReceiptSupport.encode(Objects.requireNonNull(operation), new FrozenNodeEvidenceCodec.Encoder(writer, limits));
    }

    public static Encoded encode(CoordinationCore.PreparedGroupOperation operation,
            FrozenNodeEvidenceCodec.Writer writer, FrozenNodeEvidenceCodec.Limits limits) {
        return encode(operation, new FrozenNodeEvidenceCodec.Encoder(writer, limits));
    }

    /** Optional already-captured complete metadata; serialization never runs an application or a header projector. */
    public static Encoded encodeWithReadCut(CoordinationCore.PreparedOperation operation, AffectedClosureSnapshot exactOutput,
            Collection<RootChannelMetadata> metadata, FrozenNodeEvidenceCodec.Writer writer, FrozenNodeEvidenceCodec.Limits limits) {
        if (operation.disposition() != CoordinationCore.Disposition.CONSUMED) throw invalid("Unconsumed attempt cannot publish reusable output authority");
        var encoder = new FrozenNodeEvidenceCodec.Encoder(writer, limits);
        return encode(operation, encoder, retainedAuthorities(operation.projections(), operation.ownedOccurrenceBindings(), exactOutput, metadata, encoder));
    }

    public static Encoded encodeWithReadCut(CoordinationCore.PreparedGroupOperation operation, AffectedClosureSnapshot exactOutput,
            Collection<RootChannelMetadata> metadata, FrozenNodeEvidenceCodec.Writer writer, FrozenNodeEvidenceCodec.Limits limits) {
        if (operation.kind() != CoordinationCore.OperationKind.EXTERNAL_INPUT || operation.disposition() != CoordinationCore.Disposition.CONSUMED
                || operation.input().isEmpty()) throw invalid("Prepared group needs its exact consumed external input");
        var encoder = new FrozenNodeEvidenceCodec.Encoder(writer, limits);
        return SameOriginReceiptSupport.encode(operation.result(), operation.input(), encoder,
                retainedAuthorities(operation.projections(), operation.ownedOccurrenceBindings(), exactOutput, metadata, encoder));
    }

    private static List<Object> retainedAuthorities(List<CoordinationCore.LineageProjection> projections,
            List<ManagedOccurrenceBinding> expectedBindings, AffectedClosureSnapshot output, Collection<RootChannelMetadata> metadata,
            FrozenNodeEvidenceCodec.Encoder encoder) {
        Set<DocumentId> owners = new TreeSet<>(); Map<DocumentId, String> bodyIdentities = new TreeMap<>();
        for (var projection : projections) {
            var state = output.managedDocument(projection.lineage()); var result = projection.result();
            if (!owners.add(projection.lineage()) || state == null || !state.blueId().equals(projection.afterBlueId())
                    || state.epoch() != projection.afterEpoch() || state.initialized() != result.initialized()
                    || state.terminated() != result.terminated()) throw invalid("Read-cut metadata is not the exact owning operation output");
            String body = blue.language.identity.DirectBlueIdCalculator.calculateBlueId(result.document());
            if (!body.equals(blue.language.identity.DirectBlueIdCalculator.calculateBlueId(state.document())))
                throw invalid("Read-cut body differs from owning output");
            bodyIdentities.put(projection.lineage(), body);
            var component = output.components().stream().filter(c -> c.orderedMemberDocumentIds().contains(projection.lineage())).findFirst().orElseThrow();
            if (!component.componentIdentity().equals(result.componentIdentity()) || !component.componentStateIdentity().equals(result.componentStateIdentity()))
                throw invalid("Read-cut component differs from owning result");
        }
        List<String> expected = expectedBindings.stream().filter(b -> owners.contains(b.sourceDocumentId())).map(OperationReceiptCodec::bindingState).sorted().toList();
        List<String> actual = output.occurrences().stream().filter(b -> owners.contains(b.sourceDocumentId())).map(OperationReceiptCodec::bindingState).sorted().toList();
        if (!expected.equals(actual)) throw invalid("Read-cut outgoing topology differs from owning result");
        // This owning verification is physical evidence checking, not execution or semantic gas.
        AffectedClosureSnapshot verified = output.retainResidentBodies(Set.of(), metadata);
        List<Object> references = new ArrayList<>(); Set<DocumentId> covered = new TreeSet<>();
        for (var component : verified.components()) {
            if (Collections.disjoint(component.orderedMemberDocumentIds(), owners)) continue;
            if (!owners.containsAll(component.orderedMemberDocumentIds())) throw invalid("A receipt cannot claim a read-only member's component authority");
            var authority = verified.managedDocument(component.orderedMemberDocumentIds().get(0)).reusableAuthority().orElseThrow();
            Map<String, String> bodies = new TreeMap<>();
            for (DocumentId member : component.orderedMemberDocumentIds()) {
                if (authority.memberHeader(member).rootMetadata().isEmpty()) throw invalid("Read-cut publication requires complete owned Root Channel metadata");
                bodies.put(member.value(), bodyIdentities.get(member)); covered.add(member);
            }
            references.add(map("authority", ReusableComponentAuthorityCodec.encode(authority, encoder),
                    "component", component.componentIdentity(), "state", component.componentStateIdentity(), "bodies", bodies));
        }
        if (!covered.equals(owners)) throw invalid("Read-cut authority omitted an owned output");
        return references;
    }

    private static String bindingState(ManagedOccurrenceBinding row) {
        return row.occurrenceIdentity() + ":" + row.bindingIdentity() + ":" + row.active() + ":" + row.pendingHistoricalEpoch();
    }

    /**
     * Restores only authority explicitly associated with this authenticated owning receipt.
     * Missing metadata is an evidence need, never an assertion that a Root has no Channels.
     * Application state and cyclic proof payloads are not read by this method.
     */
    public static List<ReusableComponentAuthority> restoreReadCutAuthorities(String authenticatedReceiptIdentity,
            blue.language.processor.DocumentProcessor processor, FrozenNodeEvidenceCodec.Reader reader, FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        Receipt receipt = decode(authenticatedReceiptIdentity, decoder);
        JsonNode root = json(decoder.blob(authenticatedReceiptIdentity)); JsonNode references = root.get("readCutAuthorities");
        if (references == null || references.isNull() || references.isArray() && references.isEmpty())
            throw new blue.language.processor.ExecutionEvidenceUnavailableException("Receipt has no retained Root read-cut authority", Set.of(authenticatedReceiptIdentity));
        if (!references.isArray() || receipt.disposition() != CoordinationCore.Disposition.CONSUMED)
            throw invalid("Invalid reusable authority receipt association");
        Map<DocumentId, OwnedState> owned = new TreeMap<>(); receipt.states().forEach(s -> owned.put(s.lineage(), s));
        Set<DocumentId> covered = new TreeSet<>(); Set<String> roots = new HashSet<>();
        List<ReusableComponentAuthority> result = new ArrayList<>();
        for (JsonNode reference : references) {
            String identity = text(reference, "authority"); Map<String, String> bodies = stringMap(reference.get("bodies"));
            if (!roots.add(identity) || bodies.isEmpty()) throw invalid("Repeated or empty read-cut authority reference");
            Set<DocumentId> members = new TreeSet<>();
            for (String member : bodies.keySet()) {
                DocumentId id = new DocumentId(member); OwnedState state = owned.get(id);
                if (state == null || !covered.add(id) || !state.componentIdentity().equals(text(reference, "component"))
                        || !state.componentStateIdentity().equals(text(reference, "state")))
                    throw invalid("Read-cut authority reference claims a foreign or different owning output");
                members.add(id);
            }
            // Only after the enclosing authenticated receipt establishes the reference and owners.
            var authority = ReusableComponentAuthorityCodec.decode(identity, decoder, processor);
            var component = authority.component();
            if (!new TreeSet<>(component.orderedMemberDocumentIds()).equals(members)
                    || !component.componentIdentity().equals(text(reference, "component"))
                    || !component.componentStateIdentity().equals(text(reference, "state")))
                throw invalid("Retained authority differs from its receipt component association");
            for (DocumentId member : members) {
                var header = authority.memberHeader(member); var state = owned.get(member);
                if (!header.blueId().equals(state.afterBlueId()) || header.epoch() != state.afterEpoch()
                        || header.initialized() != state.initialized() || header.terminated() != state.terminated()
                        || !authority.exactBodyIdentity(member).equals(bodies.get(member.value())))
                    throw invalid("Retained member is not the receipt's exact owned state");
                var metadata = header.rootMetadata().orElseThrow(() -> invalid("Published read-cut authority omits Root Channel metadata"));
                metadata.verifyState(header); metadata.verifyRegistry(receipt.executionIdentities().get("registry"));
            }
            result.add(authority);
        }
        if (!covered.equals(owned.keySet())) throw invalid("Read-cut authority omitted an owned result member");
        List<String> actualBindings = result.stream().flatMap(a -> a.outgoingBindings().stream()).map(OperationReceiptCodec::bindingState).sorted().toList();
        List<String> expectedBindings = OperationEffectCodec.decode(receipt.effectsIdentity(), decoder, owned.keySet()).bindings().stream()
                .map(OperationReceiptCodec::bindingState).sorted().toList();
        if (!actualBindings.equals(expectedBindings)) throw invalid("Read-cut outgoing topology differs from authenticated receipt effects");
        return List.copyOf(result);
    }

    static Encoded encode(CoordinationCore.PreparedGroupOperation operation, FrozenNodeEvidenceCodec.Encoder encoder) {
        Objects.requireNonNull(operation);
        if (operation.kind() != CoordinationCore.OperationKind.EXTERNAL_INPUT || operation.disposition() != CoordinationCore.Disposition.CONSUMED
                || operation.input().isEmpty()) throw invalid("Prepared group needs its exact consumed external input");
        return SameOriginReceiptSupport.encode(operation.result(), operation.input(), encoder);
    }

    static Encoded encode(CoordinationCore.PreparedOperation operation, FrozenNodeEvidenceCodec.Encoder encoder) {
        return encode(operation, encoder, List.of());
    }

    private static Encoded encode(CoordinationCore.PreparedOperation operation, FrozenNodeEvidenceCodec.Encoder encoder, List<Object> authorities) {
        Objects.requireNonNull(operation);
        if (!operation.managedReaction().map(ManagedReactionContext::identity).equals(operation.invocation().managedReaction().map(ManagedReactionContext::identity)))
            throw invalid("Prepared operation differs from its invocation's managed reaction");
        String lanes = operation.managedReaction().map(context -> ManagedImportLaneCodec.encode(operation.laneDeltas(), context,
                operation.operationId(), operation.ownedLineages(), encoder)).orElse(null);
        if (lanes == null && !operation.laneDeltas().isEmpty()) throw invalid("Lane deltas require a managed reaction");
        String program = operation.sourceProgram().map(value -> SourceObservationProgramCodec.encode(value, encoder)).orElse(null);
        String failure = operation.kind() != CoordinationCore.OperationKind.INITIALIZATION
                && (operation.result().status() == ProcessorStatus.GAS_LIMIT_EXCEEDED || operation.result().status() == ProcessorStatus.RUNTIME_FATAL)
                ? SourceOperationFailureCodec.encode(SourceOperationFailure.fromProcessClosure(operation.invocation(), operation.result(),
                        operation.ownedLineages()), encoder) : null;
        List<Object> states = new ArrayList<>();
        for (var projection : operation.projections()) {
            ResultingDocument result = projection.result();
            states.add(map("lineage", projection.lineage().value(), "precedingOperation", projection.precedingOperation(),
                    "beforeBlueId", projection.beforeBlueId(), "afterBlueId", projection.afterBlueId(),
                    "beforeEpoch", projection.beforeEpoch(), "afterEpoch", projection.afterEpoch(),
                    "initialized", result.initialized(), "terminated", result.terminated(),
                    "componentIdentity", result.componentIdentity(), "componentStateIdentity", result.componentStateIdentity(),
                    "memberIndex", result.memberIndex(), "body", encoder.node(FrozenNode.fromResolvedNode(result.document()))));
        }
        List<Object> pins = new ArrayList<>();
        for (var pin : operation.sourcePins()) pins.add(map("lineage", pin.lineage().value(),
                "operation", pin.sourceOperation(), "blueId", pin.blueId(), "epoch", pin.epoch()));
        List<Object> observedSources = managedFailedObservations(operation);
        Map<String, String> predecessors = new TreeMap<>();
        operation.invocation().semanticPredecessors().forEach((id, predecessor) -> predecessors.put(id.value(), predecessor));
        var environment = operation.invocation().environment();
        Map<String, Object> execution = map("language", environment.blueLanguageSpecificationIdentity(),
                "contracts", environment.contractsSpecificationIdentity(), "registry", environment.runtimeRegistryIdentity(),
                "gasManifest", environment.gasManifestIdentity(), "gasPolicy", operation.invocation().executionPolicy().identity(),
                "lineagePolicy", environment.managedDocumentIdentityPolicyIdentity(), "bindingPolicy", environment.managedBindingPolicyIdentity(),
                "providerDomain", environment.exactNodeProviderDomainIdentity(), "orderPolicy", environment.externalOrderPolicyIdentity(),
                "portableLimits", environment.portableLimitPolicyIdentity(), "cyclicFinalizer", environment.cyclicFinalizerIdentity(),
                "cyclicVerifier", environment.cyclicProofVerifierIdentity());
        List<Object> events = new ArrayList<>();
        for (PublicEventOccurrence event : operation.result().publicEvents()) events.add(map(
                "ordinal", event.publicEventOrdinal(), "occurrenceOrdinal", event.eventOccurrenceOrdinal(),
                "lineage", event.publicRootDocumentId().value(), "occurrence", event.eventOccurrenceIdentity(),
                "blueId", event.eventBlueId(), "body", encoder.node(FrozenNode.fromResolvedNode(event.event()))));
        ClosureProcessResult result = operation.result();
        List<Object> readPins = new ArrayList<>();
        for (ManagedReadPin pin : operation.sourceProgram().map(SourceObservationProgram::sourceReadPins).orElse(List.of())) {
            List<String> proof = new ArrayList<>();
            if (pin.cyclicProof().isPresent()) for (var member : pin.cyclicProof().orElseThrow().declaredPlaceholderSet())
                proof.add(encoder.node(FrozenNode.fromResolvedNode(member)));
            readPins.add(map("lineage", pin.documentId().value(), "blueId", pin.blueId(),
                    "body", encoder.node(pin.frozenDocument()), "cyclicProof", proof));
        }
        Map<String, Object> identities = map("inputClosure", result.inputClosureIdentity(), "outputClosure", result.outputClosureIdentity(),
                "bindings", result.occurrenceBindingSetIdentity(), "graphChanges", result.graphChangesIdentity(),
                "subscriptions", result.subscriptionDeltasIdentity(), "checkpoints", result.checkpointWritesIdentity(),
                "publicEvents", result.publicEventsIdentity());
        Map<String, Object> root = map("format", FORMAT, "operation", operation.operationId(), "kind", operation.kind().name(),
                "disposition", operation.disposition().name(), "status", result.status().name(),
                "cause", operation.invocation().cause().causeIdentity(),
                "input", operation.input().map(value -> input(value, encoder)).orElse(null), "states", states,
                "sourcePins", pins, "consumedSources", operation.consumedSourceOperations(),
                "observedSources", observedSources, "semanticPredecessors", predecessors, "executionIdentities", execution,
                "readPins", readPins, "readCutAuthorities", authorities,
                "gas", gas(result, encoder), "events", events, "resultIdentities", identities,
                "effects", OperationEffectCodec.encode(operation, encoder), "sourceFailure", failure, "sourceProgram", program,
                "managedReaction", operation.invocation().managedReaction().map(context -> ManagedReactionContextCodec.encode(context, encoder)).orElse(null),
                "managedLanes", lanes);
        return new Encoded(encoder.blob(bytes(root)), Optional.ofNullable(program));
    }

    private static List<Object> managedFailedObservations(CoordinationCore.PreparedOperation operation) {
        if (operation.result().status() != ProcessorStatus.GAS_LIMIT_EXCEEDED
                && operation.result().status() != ProcessorStatus.RUNTIME_FATAL) return List.of();
        Map<DocumentId, Map<DocumentId, ObservedSource>> byConsumer = new TreeMap<>();
        for (var delta : operation.laneDeltas()) {
            var cursor = delta.nextCursor(); var lane = cursor.descriptor();
            if (!operation.ownedLineages().contains(lane.consumerLineage())
                    || delta.outcome() != ManagedImportLane.Outcome.GAS_LIMIT_EXCEEDED
                    && delta.outcome() != ManagedImportLane.Outcome.RUNTIME_FATAL)
                throw invalid("Failed managed observation lacks its exact consumer lane");
            var observation = new ObservedSource(lane.consumerLineage(), lane.sourceLineage(), cursor.successfulBlueId(), cursor.successfulEpoch());
            var previous = byConsumer.computeIfAbsent(observation.consumer(), ignored -> new TreeMap<>()).put(observation.lineage(), observation);
            if (previous != null && !previous.equals(observation)) throw invalid("Conflicting successful pins for one consumer/source");
        }
        List<Object> rows = new ArrayList<>();
        for (var sources : byConsumer.values()) for (var value : sources.values()) rows.add(map(
                "consumer", value.consumer().value(), "lineage", value.lineage().value(), "blueId", value.blueId(), "epoch", value.epoch()));
        return rows;
    }

    /** Decodes bounded receipt metadata. State and program fragments remain lazy until requested. */
    public static Receipt decode(String authenticatedReceiptIdentity, FrozenNodeEvidenceCodec.Reader reader,
                                 FrozenNodeEvidenceCodec.Limits limits) {
        return decode(authenticatedReceiptIdentity, new FrozenNodeEvidenceCodec.Decoder(reader, limits));
    }

    static Receipt decode(String identity, FrozenNodeEvidenceCodec.Decoder decoder) {
        JsonNode root = json(decoder.blob(identity));
        if (!FORMAT.equals(text(root, "format"))) throw invalid("Unexpected operation receipt format");
        List<OwnedState> states = new ArrayList<>(); Set<DocumentId> owners = new HashSet<>();
        for (JsonNode row : array(root, "states")) {
            DocumentId lineage = new DocumentId(text(row, "lineage"));
            if (!owners.add(lineage)) throw invalid("Duplicate receipt owner");
            states.add(new OwnedState(lineage, nullable(row, "precedingOperation"), text(row, "beforeBlueId"),
                    text(row, "afterBlueId"), integer(row, "beforeEpoch"), integer(row, "afterEpoch"),
                    bool(row, "initialized"), bool(row, "terminated"), text(row, "componentIdentity"),
                    text(row, "componentStateIdentity"), optionalInteger(row, "memberIndex"), text(row, "body")));
        }
        List<CoordinationCore.SourcePin> pins = new ArrayList<>(); Set<DocumentId> pinned = new HashSet<>();
        List<String> consumed = strings(root, "consumedSources");
        for (JsonNode row : array(root, "sourcePins")) {
            var pin = new CoordinationCore.SourcePin(new DocumentId(text(row, "lineage")), text(row, "operation"),
                    text(row, "blueId"), integer(row, "epoch"));
            if (owners.contains(pin.lineage()) || !pinned.add(pin.lineage()) || !consumed.contains(pin.sourceOperation()))
                throw invalid("Receipt source pin has inconsistent ownership or dependency");
            pins.add(pin);
        }
        List<Event> events = new ArrayList<>();
        for (JsonNode row : array(root, "events")) {
            Event event = new Event(integer(row, "ordinal"), optionalInteger(row, "occurrenceOrdinal"), new DocumentId(text(row, "lineage")),
                    text(row, "occurrence"), text(row, "blueId"), text(row, "body"));
            if (!owners.contains(event.lineage())) throw invalid("Receipt cannot republish an unowned source event");
            events.add(event);
        }
        JsonNode in = root.get("input");
        Optional<Input> input = in == null || in.isNull() ? Optional.empty() : Optional.of(new Input(text(in, "timeline"),
                integer(in, "timestampMicros"), text(in, "entryBlueId"), text(in, "eventBlueId"), text(in, "entry"), text(in, "event")));
        List<ObservedSource> observedSources = new ArrayList<>(); Set<List<DocumentId>> observed = new HashSet<>();
        for (JsonNode row : array(root, "observedSources")) {
            var source = new ObservedSource(new DocumentId(text(row, "consumer")), new DocumentId(text(row, "lineage")), text(row, "blueId"), integer(row, "epoch"));
            if (!owners.contains(source.consumer()) || owners.contains(source.lineage())
                    || !observed.add(List.of(source.consumer(), source.lineage()))) throw invalid("Invalid observed source ownership");
            observedSources.add(source);
        }
        List<ReadPin> readPins = new ArrayList<>(); Set<String> pinKeys = new HashSet<>();
        for (JsonNode row : array(root, "readPins")) {
            ReadPin pin = new ReadPin(new DocumentId(text(row, "lineage")), text(row, "blueId"), text(row, "body"), strings(row, "cyclicProof"));
            if (!pinKeys.add(pin.lineage().value() + "\u0000" + pin.blueId())) throw invalid("Duplicate exact read pin");
            readPins.add(pin);
        }
        Receipt receipt = new Receipt(text(root, "operation"), CoordinationCore.OperationKind.valueOf(text(root, "kind")),
                CoordinationCore.Disposition.valueOf(text(root, "disposition")), ProcessorStatus.valueOf(text(root, "status")),
                text(root, "cause"), input, states, pins, consumed, observedSources, stringMap(root.get("semanticPredecessors")),
                stringMap(root.get("executionIdentities")), readPins, gas(root.get("gas")), events,
                stringMap(root.get("resultIdentities")), text(root, "effects"), Optional.ofNullable(nullable(root, "sourceFailure")),
                Optional.ofNullable(nullable(root, "sourceProgram")), Optional.ofNullable(nullable(root, "sameOrigin")), Optional.ofNullable(nullable(root, "managedReaction")),
                Optional.ofNullable(nullable(root, "managedLanes")));
        if (receipt.status() != ProcessorStatus.SUCCESS && (!pins.isEmpty() || !events.isEmpty() || receipt.sourceProgramIdentity().isPresent()))
            throw invalid("Failed receipt cannot publish successful source effects");
        if (receipt.sourceFailureIdentity().isPresent() && (receipt.status() == ProcessorStatus.SUCCESS
                || receipt.kind() == CoordinationCore.OperationKind.INITIALIZATION || receipt.disposition() != CoordinationCore.Disposition.CONSUMED))
            throw invalid("Receipt cannot expose this result as a terminal source failure");
        return receipt;
    }

    /** Restores an owned lineage. Hosting role and physical generation are supplied by the host separately. */
    public static ManagedDocumentSnapshot restoreState(String authenticatedReceiptIdentity, DocumentId lineage,
            boolean publicRoot, long componentGeneration, FrozenNodeEvidenceCodec.Reader reader,
            FrozenNodeEvidenceCodec.Limits limits) {
        FrozenNodeEvidenceCodec.Decoder decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        OwnedState state = decode(authenticatedReceiptIdentity, decoder).states().stream()
                .filter(value -> value.lineage().equals(lineage)).findFirst().orElseThrow(() -> invalid("Receipt does not own lineage"));
        return new ManagedDocumentSnapshot(lineage, state.afterBlueId(), decoder.materialize(state.bodyIdentity()),
                state.initialized(), state.terminated(), publicRoot, state.afterEpoch(), componentGeneration);
    }

    /** Binds the retained source program to this committed operation and its exact owned results. */
    public static SourceObservationProgram restoreSourceProgram(String authenticatedReceiptIdentity,
            FrozenNodeEvidenceCodec.Reader reader, FrozenNodeEvidenceCodec.Limits limits) {
        FrozenNodeEvidenceCodec.Decoder decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        return restoreSourceProgram(decode(authenticatedReceiptIdentity, decoder), decoder);
    }

    /** Successful canonical initialization capability bound to its authenticated source receipt. */
    public static SourceInitialization restoreSourceInitialization(String authenticatedReceiptIdentity,
            FrozenNodeEvidenceCodec.Reader reader, FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        Receipt receipt = decode(authenticatedReceiptIdentity, decoder);
        if (receipt.kind() != CoordinationCore.OperationKind.INITIALIZATION
                || receipt.status() != ProcessorStatus.SUCCESS || receipt.disposition() != CoordinationCore.Disposition.CONSUMED)
            throw invalid("Canonical initialization requires a successful source initialization receipt");
        return SourceInitialization.fromProgram(restoreSourceProgram(receipt, decoder));
    }

    static SourceObservationProgram restoreSourceProgram(Receipt receipt, FrozenNodeEvidenceCodec.Decoder decoder) {
        SourceObservationProgram program = SourceObservationProgramCodec.decode(receipt.sourceProgramIdentity()
                .orElseThrow(() -> invalid("Receipt has no successful source program")), decoder);
        Set<DocumentId> owners = new TreeSet<>();
        for (OwnedState state : receipt.states()) {
            owners.add(state.lineage());
            SourceObservationProgram.SourceState actual = program.sourceResults().stream()
                    .filter(value -> value.documentId().equals(state.lineage())).findFirst()
                    .orElseThrow(() -> invalid("Source program omits receipt owner"));
            if (!actual.blueId().equals(state.afterBlueId()) || actual.epoch() != state.afterEpoch())
                throw invalid("Source program result differs from receipt projection");
        }
        if (!receipt.operationId().equals(program.invocationIdentity()) || !receipt.causeIdentity().equals(program.causeIdentity())
                || !owners.equals(program.ownedDocumentIds())) throw invalid("Source program is not owned by this operation");
        verifyManagedReaction(receipt, program.managedReaction(), decoder);
        return program;
    }

    /** Restores a consumed failure gap from two independently authenticated committed receipts. */
    public static SourceObservationGap restoreSourceGap(String authenticatedFailureReceipt, DocumentId consumer, DocumentId source,
            String authenticatedOfferedSourceReceipt, FrozenNodeEvidenceCodec.Reader reader, FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        Receipt failure = decode(authenticatedFailureReceipt, decoder);
        Receipt offered = decode(authenticatedOfferedSourceReceipt, decoder);
        if (failure.disposition() != CoordinationCore.Disposition.CONSUMED
                || failure.kind() == CoordinationCore.OperationKind.INITIALIZATION
                || failure.status() != ProcessorStatus.GAS_LIMIT_EXCEEDED && failure.status() != ProcessorStatus.RUNTIME_FATAL
                || failure.states().stream().noneMatch(state -> state.lineage().equals(consumer))
                || !failure.consumedSourceOperations().contains(offered.operationId()))
            throw invalid("Receipt does not establish a consumed managed source failure");
        ObservedSource observed = failure.observedSources().stream().filter(state -> state.consumer().equals(consumer) && state.lineage().equals(source)).findFirst()
                .orElseThrow(() -> invalid("Failed receipt has no observed source pin"));
        SourceObservationProgram program = restoreSourceProgram(offered, decoder);
        return SourceObservationGap.fromAuthenticatedManagedFailure(consumer, source, failure.operationId(), failure.causeIdentity(),
                failure.status(), observed.blueId(), observed.epoch(), program);
    }

    public static SourceOperationFailure restoreSourceFailure(String authenticatedReceiptIdentity,
            FrozenNodeEvidenceCodec.Reader reader, FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        Receipt receipt = decode(authenticatedReceiptIdentity, decoder);
        return restoreSourceFailure(receipt, decoder);
    }

    static SourceOperationFailure restoreSourceFailure(Receipt receipt, FrozenNodeEvidenceCodec.Decoder decoder) {
        SourceOperationFailure failure = SourceOperationFailureCodec.decode(receipt.sourceFailureIdentity()
                .orElseThrow(() -> invalid("Receipt has no consumed source processing failure")), decoder);
        Set<DocumentId> owners = new HashSet<>();
        for (OwnedState state : receipt.states()) {
            owners.add(state.lineage());
            SourceObservationProgram.SourceState before = failure.sourcePredecessors().stream()
                    .filter(value -> value.documentId().equals(state.lineage())).findFirst().orElseThrow(() -> invalid("Missing failed source owner"));
            if (!before.blueId().equals(state.beforeBlueId()) || !before.blueId().equals(state.afterBlueId())
                    || before.epoch() != state.beforeEpoch() || before.epoch() != state.afterEpoch())
                throw invalid("Failed source receipt changes its successful state");
        }
        if (!receipt.operationId().equals(failure.invocationIdentity()) || !receipt.causeIdentity().equals(failure.causeIdentity())
                || receipt.status() != failure.status() || !owners.equals(failure.ownedDocumentIds())
                || receipt.gas().total() != failure.totalGas() || !receipt.gas().traceIdentity().equals(failure.gasTraceIdentity()))
            throw invalid("Source failure does not match its committed receipt");
        if (receipt.sameOriginIdentity().isPresent()) {
            SameOriginSettlement group = SameOriginReceiptSupport.decode(receipt, decoder);
            if (!Objects.equals(failure.rejectedChargeIdentity(), group.rejectedCharge().map(SameOriginRejectedChargeEvidence::identity).orElse(null)))
                throw invalid("Group failure rejected charge differs from its receipt");
        }
        verifyManagedReaction(receipt, failure.managedReaction(), decoder);
        return failure;
    }

    /** Consumer producing position, distinct from the original source's external cause/provenance. */
    public static Optional<ManagedReactionContext> restoreManagedReaction(String authenticatedReceiptIdentity,
            FrozenNodeEvidenceCodec.Reader reader, FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        return decode(authenticatedReceiptIdentity, decoder).managedReactionIdentity().map(identity -> ManagedReactionContextCodec.decode(identity, decoder));
    }

    /** Restores terminal historical lanes without moving an ordinary Timeline input cursor. */
    public static List<ManagedImportLane.Delta> restoreManagedLanes(String authenticatedReceiptIdentity,
            FrozenNodeEvidenceCodec.Reader reader, FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        Receipt receipt = decode(authenticatedReceiptIdentity, decoder);
        if (receipt.managedLanesIdentity().isEmpty()) return List.of();
        ManagedReactionContext context = ManagedReactionContextCodec.decode(receipt.managedReactionIdentity()
                .orElseThrow(() -> invalid("Historical lanes lack their managed reaction context")), decoder);
        Set<DocumentId> owners = new HashSet<>(); receipt.states().forEach(state -> owners.add(state.lineage()));
        var deltas = ManagedImportLaneCodec.decode(receipt.managedLanesIdentity().orElseThrow(), context, receipt.operationId(), owners, decoder);
        for (var delta : deltas) {
            boolean matches = switch (receipt.status()) {
                case SUCCESS -> delta.outcome() == ManagedImportLane.Outcome.APPLIED || delta.outcome() == ManagedImportLane.Outcome.RETIRED
                        || delta.outcome() == ManagedImportLane.Outcome.SOURCE_FAILURE;
                case GAS_LIMIT_EXCEEDED -> delta.outcome() == ManagedImportLane.Outcome.GAS_LIMIT_EXCEEDED;
                case RUNTIME_FATAL -> delta.outcome() == ManagedImportLane.Outcome.RUNTIME_FATAL;
                default -> false;
            };
            if (!matches) throw invalid("Managed lane outcome differs from its consumer operation");
        }
        return deltas;
    }

    private static void verifyManagedReaction(Receipt receipt, Optional<ManagedReactionContext> actual, FrozenNodeEvidenceCodec.Decoder decoder) {
        Optional<String> expected = receipt.managedReactionIdentity().map(identity -> ManagedReactionContextCodec.decode(identity, decoder).identity());
        if (!expected.equals(actual.map(ManagedReactionContext::identity))) throw invalid("Source receipt producing position differs from retained capability");
    }

    static Object input(CoordinationCore.TimelineInput input, FrozenNodeEvidenceCodec.Encoder encoder) {
        return map("timeline", input.timelineId(), "timestampMicros", input.timestampMicros(),
                "entryBlueId", input.entry().blueId(), "eventBlueId", input.event().blueId(),
                "entry", encoder.node(FrozenNode.fromResolvedNode(input.entry().copyNode())),
                "event", encoder.node(FrozenNode.fromResolvedNode(input.event().copyNode())));
    }

    /** Loads the exact admitted trace and rejected next charge without any execution or settlement. */
    public static RestoredGas restoreGas(String authenticatedReceiptIdentity, FrozenNodeEvidenceCodec.Reader reader,
                                         FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        var receipt = decode(authenticatedReceiptIdentity, decoder);
        Set<DocumentId> owners = new HashSet<>(); receipt.states().forEach(state -> owners.add(state.lineage()));
        List<GasTraceEntry> trace = new ArrayList<>(); long total = 0;
        for (String fragment : receipt.gas().traceFragments()) {
            JsonNode row = json(decoder.blob(fragment)); String document = nullable(row, "document");
            GasTraceEntry entry = new GasTraceEntry(integer(row, "sequence"), GasTraceEntry.Namespace.valueOf(text(row, "namespace")),
                    text(row, "counter"), integer(row, "quantity"), integer(row, "weight"), integer(row, "subtotal"),
                    document == null ? null : new DocumentId(document), nullable(row, "scope"), optionalInteger(row, "activation"),
                    optionalInteger(row, "component"), nullable(row, "contract"), nullable(row, "path"), nullable(row, "work"), nullable(row, "reason"));
            if (entry.sequence() != trace.size()) throw invalid("Noncontiguous receipt gas trace");
            if (receipt.sameOriginIdentity().isPresent() && entry.documentId() != null && !owners.contains(entry.documentId()))
                throw invalid("Atomic group gas cannot be attributed to an unowned lineage");
            total = Math.addExact(total, entry.subtotal()); trace.add(entry);
        }
        if (total != receipt.gas().total() || !GasTraceEntry.identityOfTrace(trace).equals(receipt.gas().traceIdentity()))
            throw invalid("Receipt gas total or identity differs from admitted trace");
        Optional<RejectedCharge> rejected = receipt.gas().rejectedChargeFragment().map(fragment -> {
            JsonNode row = json(decoder.blob(fragment));
            RejectedCharge.ApplicableCap cap = switch (text(row, "cap")) {
                case "SHARED" -> RejectedCharge.ApplicableCap.shared();
                case "LOCAL" -> RejectedCharge.ApplicableCap.local(new DocumentId(text(row, "capDocument")));
                default -> throw invalid("Unknown rejected gas cap");
            };
            RejectedCharge.Owner owner = switch (text(row, "owner")) {
                case "INVOCATION" -> RejectedCharge.Owner.invocation();
                case "WORK" -> RejectedCharge.Owner.work(text(row, "work"));
                case "FINALIZATION" -> RejectedCharge.Owner.finalization(integer(row, "finalizationOrdinal"),
                        text(row, "component"), integer(row, "componentGeneration"));
                default -> throw invalid("Unknown rejected gas owner");
            };
            return new RejectedCharge(text(row, "identity"), GasTraceEntry.Namespace.valueOf(text(row, "namespace")),
                    text(row, "counter"), integer(row, "quantity"), integer(row, "weight"), integer(row, "subtotal"),
                    cap, integer(row, "remaining"), owner);
        });
        Optional<SameOriginSettlement> group = receipt.sameOriginIdentity().isPresent()
                ? Optional.of(SameOriginReceiptSupport.decode(receipt, decoder)) : Optional.empty();
        return new RestoredGas(trace, rejected, group.flatMap(SameOriginSettlement::rejectedCharge), group.flatMap(SameOriginSettlement::rejectedAdmission));
    }

    /** Loads only bounded group identity/admission/failure metadata, not document/program graphs. */
    public static SameOriginSettlement restoreSameOrigin(String authenticatedReceiptIdentity, FrozenNodeEvidenceCodec.Reader reader,
                                                         FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        return SameOriginReceiptSupport.decode(decode(authenticatedReceiptIdentity, decoder), decoder);
    }

    public static Effects restoreEffects(String authenticatedReceiptIdentity, FrozenNodeEvidenceCodec.Reader reader,
                                         FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        Receipt receipt = decode(authenticatedReceiptIdentity, decoder);
        Set<DocumentId> owners = new HashSet<>(); for (OwnedState state : receipt.states()) owners.add(state.lineage());
        return OperationEffectCodec.decode(receipt.effectsIdentity(), decoder, owners);
    }

    /**
     * Loads accepted semantic read pins, independently verifying their exact views.
     * Rollback receipts do not retain optional dependency caches; their unchanged
     * owner references may require separate lazy exact-evidence acquisition.
     */
    public static List<ManagedReadPin> restoreReadPins(String authenticatedReceiptIdentity, FrozenNodeEvidenceCodec.Reader reader,
                                                     FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        Receipt receipt = decode(authenticatedReceiptIdentity, decoder);
        List<ManagedReadPin> pins = new ArrayList<>();
        for (ReadPin pin : receipt.readPins()) {
            List<blue.language.model.Node> members = new ArrayList<>();
            for (String member : pin.cyclicProofFragments()) members.add(decoder.materialize(member));
            var proof = members.isEmpty() ? null : blue.language.provider.CyclicSetProof.fromDeclaredPlaceholderSet(members);
            pins.add(ManagedReadPin.fromExactEvidence(pin.lineage(), pin.blueId(), decoder.materialize(pin.bodyIdentity()), proof));
        }
        return List.copyOf(pins);
    }

    private static Object gas(ClosureProcessResult result, FrozenNodeEvidenceCodec.Encoder encoder) {
        List<String> trace = trace(result.gasTrace(), encoder);
        ProcessorDiagnostic diagnostic = result.diagnostic();
        return map("total", result.totalGas(), "traceIdentity", result.gasTraceIdentity(), "trace", trace,
                "rejectedCharge", result.rejectedCharge() == null ? null : encoder.blob(bytes(rejected(result.rejectedCharge()))),
                "rejectedWork", result.rejectedWorkOccurrence() == null ? null : result.rejectedWorkOccurrence().workIdentity(),
                "diagnostic", diagnostic == null ? null : map("category", diagnostic.category().name(),
                        "message", diagnostic.message(), "details", diagnostic.details()));
    }

    static List<String> trace(List<GasTraceEntry> rows, FrozenNodeEvidenceCodec.Encoder encoder) {
        List<String> trace = new ArrayList<>();
        for (GasTraceEntry row : rows) trace.add(encoder.blob(bytes(map(
                "sequence", row.sequence(), "namespace", row.namespace().name(), "counter", row.counter(),
                "quantity", row.quantity(), "weight", row.weight(), "subtotal", row.subtotal(),
                "document", row.documentId() == null ? null : row.documentId().value(), "scope", row.scopePath(),
                "activation", row.activationGeneration(), "component", row.componentGeneration(), "contract", row.contractKey(),
                "path", row.logicalPath(), "work", row.workOccurrenceId(), "reason", row.reason()))));
        return trace;
    }

    private static Object rejected(RejectedCharge charge) {
        return map("identity", charge.rejectedChargeIdentity(), "namespace", charge.namespace().name(), "counter", charge.counter(),
                "quantity", charge.quantity(), "weight", charge.weight(), "subtotal", charge.subtotal(),
                "cap", charge.applicableCap().kind().name(), "capDocument", charge.applicableCap().documentId() == null
                        ? null : charge.applicableCap().documentId().value(), "remaining", charge.remainingBeforeCharge(),
                "owner", charge.owner().kind().name(), "work", charge.owner().workOccurrenceIdentity(),
                "finalizationOrdinal", charge.owner().finalizationOrdinal(), "component", charge.owner().componentIdentity(),
                "componentGeneration", charge.owner().componentGeneration());
    }

    private static GasSettlement gas(JsonNode row) {
        JsonNode diagnostic = row.get("diagnostic");
        return new GasSettlement(integer(row, "total"), text(row, "traceIdentity"), strings(row, "trace"),
                Optional.ofNullable(nullable(row, "rejectedCharge")), Optional.ofNullable(nullable(row, "rejectedWork")),
                diagnostic == null || diagnostic.isNull() ? Optional.empty() : Optional.of(new Diagnostic(
                        text(diagnostic, "category"), nullable(diagnostic, "message"), stringMap(diagnostic.get("details")))));
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new TreeMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.put((String) pairs[index], pairs[index + 1]);
        return values;
    }
    static byte[] bytes(Object value) {
        try { return JSON.writeValueAsBytes(value); }
        catch (IOException failure) { throw new IllegalStateException("Receipt encoding failed", failure); }
    }
    static JsonNode json(byte[] bytes) {
        try { return JSON.readTree(bytes); }
        catch (IOException failure) { throw invalid("Malformed receipt JSON"); }
    }
    static String text(JsonNode object, String key) {
        String value = nullable(object, key); if (value == null) throw invalid("Missing receipt field: " + key); return value;
    }
    static String nullable(JsonNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid("Expected receipt text: " + key); return value.textValue();
    }
    static long integer(JsonNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("Expected receipt integer: " + key);
        return value.longValue();
    }
    static Long optionalInteger(JsonNode object, String key) { return object.get(key) == null || object.get(key).isNull() ? null : integer(object, key); }
    static boolean bool(JsonNode object, String key) {
        JsonNode value = object.get(key); if (value == null || !value.isBoolean()) throw invalid("Expected receipt boolean: " + key); return value.booleanValue();
    }
    static JsonNode array(JsonNode object, String key) {
        JsonNode value = object.get(key); if (value == null || !value.isArray()) throw invalid("Expected receipt array: " + key); return value;
    }
    static List<String> strings(JsonNode object, String key) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : array(object, key)) { if (!value.isTextual()) throw invalid("Expected receipt text array"); result.add(value.textValue()); }
        return result;
    }
    private static Map<String, String> stringMap(JsonNode object) {
        if (object == null || !object.isObject()) throw invalid("Expected receipt text map");
        Map<String, String> result = new TreeMap<>(); object.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) throw invalid("Expected receipt map text value"); result.put(entry.getKey(), entry.getValue().textValue());
        }); return result;
    }
    static InvalidExecutionEvidenceException invalid(String message) { return new InvalidExecutionEvidenceException(message); }
}
