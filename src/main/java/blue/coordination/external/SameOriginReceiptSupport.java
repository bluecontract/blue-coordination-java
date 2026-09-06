package blue.coordination.external;

import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static blue.coordination.external.OperationReceiptCodec.*;

/** Direct codec bridge from one real group; no global result or semantic scope is reconstructed. */
final class SameOriginReceiptSupport {
    private static final String FORMAT = "blue-same-origin-receipt-evidence-poc-1";
    private SameOriginReceiptSupport() { }

    static Encoded encode(SameOriginOperationResult group, FrozenNodeEvidenceCodec.Encoder encoder) {
        return encode(group, Optional.empty(), encoder);
    }

    static Encoded encode(SameOriginOperationResult group, Optional<CoordinationCore.TimelineInput> input, FrozenNodeEvidenceCodec.Encoder encoder) {
        return encode(group, input, encoder, List.of());
    }

    static Encoded encode(SameOriginOperationResult group, Optional<CoordinationCore.TimelineInput> input,
                         FrozenNodeEvidenceCodec.Encoder encoder, List<Object> authorities) {
        ClosureInvocationInput origin = group.origin();
        if (origin.cause().kind() != ProcessingCause.Kind.EXTERNAL) throw invalid("Group requires its exact external origin");
        input.ifPresent(value -> {
            ExternalEventCause cause = (ExternalEventCause) origin.cause();
            if (!cause.eventBlueId().equals(value.entry().blueId()) || !cause.sourceOrder().equals(value.order()))
                throw invalid("Prepared group input differs from its original exact cause");
        });
        String program = group.sourceProgram().map(value -> SourceObservationProgramCodec.encode(value, encoder)).orElse(null);
        String failure = group.failure().isPresent() ? SourceOperationFailureCodec.encode(SourceOperationFailure.fromSameOrigin(group), encoder) : null;
        Map<DocumentId, ManagedDocumentSnapshot> before = new TreeMap<>();
        for (var state : group.predecessors()) if (before.put(state.documentId(), state) != null) throw invalid("Duplicate group predecessor");
        if (!before.keySet().equals(group.ownedDocumentIds())) throw invalid("Group predecessor ownership mismatch");
        List<Object> states = new ArrayList<>();
        for (var result : group.resultingDocuments()) {
            var prior = before.get(result.documentId());
            states.add(map("lineage", result.documentId().value(), "precedingOperation", origin.semanticPredecessors().get(result.documentId()),
                    "beforeBlueId", prior.blueId(), "afterBlueId", result.afterBlueId(), "beforeEpoch", prior.epoch(), "afterEpoch", result.epoch(),
                    "initialized", result.initialized(), "terminated", result.terminated(), "componentIdentity", result.componentIdentity(),
                    "componentStateIdentity", result.componentStateIdentity(), "memberIndex", result.memberIndex(),
                    "body", encoder.node(FrozenNode.fromResolvedNode(result.document()))));
        }
        List<Object> pins = new ArrayList<>(), observed = new ArrayList<>(), reads = new ArrayList<>();
        Map<DocumentId, SourceObservationProgram.SourceState> sourceResults = new TreeMap<>();
        group.sourceProgram().ifPresent(value -> value.sourceResults().forEach(state -> sourceResults.put(state.documentId(), state)));
        Set<List<DocumentId>> observationKeys = new HashSet<>();
        for (var prior : group.observedSources()) {
            if (!group.ownedDocumentIds().contains(prior.consumerDocumentId())
                    || !group.consumedSourceOperations().containsKey(prior.documentId())
                    || !observationKeys.add(List.of(prior.consumerDocumentId(), prior.documentId())))
                throw invalid("Group has an invalid authenticated consumer/source observation");
            observed.add(map("consumer", prior.consumerDocumentId().value(), "lineage", prior.documentId().value(), "blueId", prior.blueId(), "epoch", prior.epoch()));
        }
        for (var dependency : group.consumedSourceOperations().entrySet()) {
            if (group.status() == ProcessorStatus.SUCCESS) {
                var result = sourceResults.get(dependency.getKey());
                if (result == null) throw invalid("Successful group omits consumed source view");
                pins.add(map("lineage", dependency.getKey().value(), "operation", dependency.getValue(), "blueId", result.blueId(), "epoch", result.epoch()));
            }
        }
        // A rolled-back attempt accepts no new installation. Its owned predecessor
        // bodies and exact binding refs are authoritative; provider/read-pin cache
        // residency must not alter a failure receipt or force unrelated body reads.
        List<ManagedReadPin> retainedPins = group.sourceProgram().map(SourceObservationProgram::sourceReadPins)
                .orElse(List.of());
        for (var pin : retainedPins) {
            List<String> proof = new ArrayList<>();
            pin.cyclicProof().ifPresent(value -> value.declaredPlaceholderSet().forEach(member -> proof.add(encoder.node(FrozenNode.fromResolvedNode(member)))));
            reads.add(map("lineage", pin.documentId().value(), "blueId", pin.blueId(), "body", encoder.node(pin.frozenDocument()), "cyclicProof", proof));
        }
        Map<String, String> predecessors = new TreeMap<>();
        for (DocumentId owner : group.ownedDocumentIds()) if (origin.semanticPredecessors().containsKey(owner))
            predecessors.put(owner.value(), origin.semanticPredecessors().get(owner));
        var environment = origin.environment();
        Object execution = map("language", environment.blueLanguageSpecificationIdentity(), "contracts", environment.contractsSpecificationIdentity(),
                "registry", environment.runtimeRegistryIdentity(), "gasManifest", environment.gasManifestIdentity(), "gasPolicy", origin.executionPolicy().identity(),
                "lineagePolicy", environment.managedDocumentIdentityPolicyIdentity(), "bindingPolicy", environment.managedBindingPolicyIdentity(),
                "providerDomain", environment.exactNodeProviderDomainIdentity(), "orderPolicy", environment.externalOrderPolicyIdentity(),
                "portableLimits", environment.portableLimitPolicyIdentity(), "cyclicFinalizer", environment.cyclicFinalizerIdentity(), "cyclicVerifier", environment.cyclicProofVerifierIdentity());
        List<Object> events = new ArrayList<>();
        for (var event : group.events()) events.add(map("ordinal", event.ordinal(), "occurrenceOrdinal", null,
                "lineage", event.sourceDocumentId().value(), "occurrence", event.occurrenceIdentity(), "blueId", event.eventBlueId(), "body", encoder.node(event.event())));
        var diagnostic = group.failure().map(SameOriginOperationResult.Failure::diagnostic).orElse(null);
        Object gas = map("total", group.totalGas(), "traceIdentity", group.gasTraceIdentity(), "trace", trace(group.gasTrace(), encoder),
                "rejectedCharge", null, "rejectedWork", null, "diagnostic", diagnostic == null ? null : map(
                        "category", diagnostic.category().name(), "message", diagnostic.message(), "details", diagnostic.details()));
        String groupEvidence = encodeEvidence(group, encoder);
        Object root = map("format", OperationReceiptCodec.FORMAT, "operation", group.operationIdentity(), "kind", CoordinationCore.OperationKind.EXTERNAL_INPUT.name(),
                "disposition", CoordinationCore.Disposition.CONSUMED.name(), "status", group.status().name(), "cause", origin.cause().causeIdentity(),
                "input", input.map(value -> input(value, encoder)).orElse(null), "states", states, "sourcePins", pins, "consumedSources", new ArrayList<>(new TreeSet<>(group.consumedSourceOperations().values())),
                "observedSources", observed, "semanticPredecessors", predecessors, "executionIdentities", execution, "readPins", reads, "readCutAuthorities", authorities,
                "gas", gas, "events", events, "resultIdentities", Map.of(), "effects", OperationEffectCodec.encode(group, encoder),
                "sourceFailure", failure, "sourceProgram", program, "sameOrigin", groupEvidence,
                "managedReaction", origin.managedReaction().map(context -> ManagedReactionContextCodec.encode(context, encoder)).orElse(null));
        return new Encoded(encoder.blob(bytes(root)), Optional.ofNullable(program));
    }

    private static String encodeEvidence(SameOriginOperationResult group, FrozenNodeEvidenceCodec.Encoder encoder) {
        var evidence = group.groupEvidence();
        List<Object> admissions = new ArrayList<>();
        for (var admission : evidence.admissions()) admissions.add(map("site", admission.canonicalSite(), "members", ids(admission.members())));
        Object charge = group.failure().flatMap(SameOriginOperationResult.Failure::rejectedCharge).map(ignored -> {
            var exact = SameOriginRejectedChargeEvidence.fromOperation(group); return map("identity", exact.identity(), "value", exact.canonicalValue());
        }).orElse(null);
        Object union = group.failure().flatMap(SameOriginOperationResult.Failure::rejectedAdmission).map(rejected -> {
            List<Object> contributions = new ArrayList<>();
            for (var contribution : rejected.contributions()) contributions.add(map("members", ids(contribution.members()), "admitted", contribution.admitted(),
                    "reserved", contribution.reserved(), "admittedLocal", contribution.admittedLocal(), "reservedLocal", contribution.reservedLocal()));
            return map("limit", rejected.limit(), "localDocument", rejected.localDocumentId(), "localLimit", rejected.localLimit(), "contributions", contributions);
        }).orElse(null);
        return encoder.blob(bytes(map("format", FORMAT, "operation", group.operationIdentity(), "seeds", identities(evidence.originalSeedByMember()),
                "admissions", admissions, "consumed", identities(evidence.consumedSourceOperations()),
                "failureSite", group.failure().map(SameOriginOperationResult.Failure::canonicalSite).orElse(null), "rejectedCharge", charge, "rejectedAdmission", union)));
    }

    static SameOriginSettlement decode(Receipt receipt, FrozenNodeEvidenceCodec.Decoder decoder) {
        JsonNode root = json(decoder.blob(receipt.sameOriginIdentity().orElseThrow(() -> invalid("Receipt has no same-origin group evidence"))));
        if (!FORMAT.equals(text(root, "format")) || !receipt.operationId().equals(text(root, "operation"))) throw invalid("Wrong group evidence");
        List<SameOriginGroupEvidence.Admission> admissions = new ArrayList<>();
        for (JsonNode row : array(root, "admissions")) admissions.add(new SameOriginGroupEvidence.Admission(text(row, "site"), documents(row, "members")));
        SameOriginGroupEvidence group;
        try { group = SameOriginGroupEvidence.fromExactEvidence(receipt.operationId(), identities(root.get("seeds")), admissions, identities(root.get("consumed"))); }
        catch (IllegalArgumentException failure) { throw invalid("Invalid same-origin settlement constructor: " + failure.getMessage()); }
        Set<DocumentId> owners = new TreeSet<>(); receipt.states().forEach(state -> owners.add(state.lineage()));
        if (!owners.equals(group.originalSeedByMember().keySet()) || !new TreeSet<>(receipt.consumedSourceOperations()).equals(new TreeSet<>(group.consumedSourceOperations().values())))
            throw invalid("Group evidence differs from receipt ownership/dependencies");
        String site = nullable(root, "failureSite");
        if (site != null && !site.matches("sha256:[0-9a-f]{64}")) throw invalid("Invalid canonical group failure site");
        SameOriginRejectedChargeEvidence charge = null;
        JsonNode charged = root.get("rejectedCharge");
        if (charged != null && !charged.isNull()) {
            try { charge = SameOriginRejectedChargeEvidence.fromExactEvidence(text(charged, "identity"), object(charged.get("value"))); }
            catch (IllegalArgumentException failure) { throw invalid("Invalid group rejected charge: " + failure.getMessage()); }
            if (!receipt.operationId().equals(charge.canonicalValue().get("operationIdentity")) || !Objects.equals(site, charge.canonicalValue().get("failureSite")))
                throw invalid("Rejected charge belongs to another group/site");
            String local = (String) charge.canonicalValue().get("localDocument");
            String document = (String) ((Map<?, ?>) charge.canonicalValue().get("context")).get("documentId");
            if (local != null && !owners.contains(new DocumentId(local)) || document != null && !owners.contains(new DocumentId(document)))
                throw invalid("Rejected charge cannot belong to an unowned lineage");
        }
        JsonNode joined = root.get("rejectedAdmission");
        RejectedAdmission union = joined == null || joined.isNull() ? null : union(joined, owners);
        boolean admissionDiagnostic = receipt.gas().diagnostic().map(value -> value.category().equals("AtomicScopeGasAdmissionFailure")).orElse(false);
        if ((union != null) != admissionDiagnostic) throw invalid("Atomic admission failure requires its exact rejected union and diagnostic");
        if (receipt.status() == ProcessorStatus.SUCCESS && (site != null || charge != null || union != null)
                || receipt.status() != ProcessorStatus.SUCCESS && site == null
                || receipt.status() == ProcessorStatus.GAS_LIMIT_EXCEEDED && (charge == null || union != null)
                || receipt.status() == ProcessorStatus.RUNTIME_FATAL && charge != null
                || receipt.status() != ProcessorStatus.SUCCESS && receipt.status() != ProcessorStatus.GAS_LIMIT_EXCEEDED && receipt.status() != ProcessorStatus.RUNTIME_FATAL)
            throw invalid("Group failure kind and rejected evidence disagree");
        if (receipt.status() != ProcessorStatus.SUCCESS) for (OwnedState state : receipt.states())
            if (!state.beforeBlueId().equals(state.afterBlueId()) || state.beforeEpoch() != state.afterEpoch()) throw invalid("Failed group changes successful state");
        return new SameOriginSettlement(group, Optional.ofNullable(site), Optional.ofNullable(charge), Optional.ofNullable(union));
    }

    private static RejectedAdmission union(JsonNode root, Set<DocumentId> owners) {
        long limit = safe(root, "limit"), localLimit = safe(root, "localLimit"); String local = nullable(root, "localDocument");
        List<AdmissionContribution> contributions = new ArrayList<>(); Set<DocumentId> participants = new HashSet<>();
        java.math.BigInteger total = java.math.BigInteger.ZERO, localTotal = java.math.BigInteger.ZERO;
        for (JsonNode row : array(root, "contributions")) {
            Set<DocumentId> members = new TreeSet<>(documents(row, "members"));
            if (members.isEmpty() || !Collections.disjoint(participants, members)) throw invalid("Rejected union repeats or omits participants");
            participants.addAll(members);
            long admitted = safe(row, "admitted"), reserved = safe(row, "reserved");
            Map<String, Long> admittedLocal = amounts(row.get("admittedLocal")), reservedLocal = amounts(row.get("reservedLocal"));
            for (String id : admittedLocal.keySet()) if (!members.contains(new DocumentId(id))) throw invalid("Unowned rejected-union local contribution");
            for (String id : reservedLocal.keySet()) if (!members.contains(new DocumentId(id))) throw invalid("Unowned rejected-union reservation");
            java.math.BigInteger contribution = java.math.BigInteger.valueOf(admitted).add(java.math.BigInteger.valueOf(reserved));
            if (contribution.compareTo(java.math.BigInteger.valueOf(limit)) > 0 || sum(admittedLocal).compareTo(java.math.BigInteger.valueOf(admitted)) > 0
                    || sum(reservedLocal).compareTo(java.math.BigInteger.valueOf(reserved)) > 0) throw invalid("Rejected union contribution was not an admissible existing group");
            total = total.add(contribution);
            if (local != null) localTotal = localTotal.add(java.math.BigInteger.valueOf(admittedLocal.getOrDefault(local, 0L))).add(java.math.BigInteger.valueOf(reservedLocal.getOrDefault(local, 0L)));
            contributions.add(new AdmissionContribution(members, admitted, reserved, admittedLocal, reservedLocal));
        }
        if (contributions.size() < 2 || !contributions.get(0).members().equals(owners)
                || local == null && (localLimit != 0 || total.compareTo(java.math.BigInteger.valueOf(limit)) <= 0)
                || local != null && (!participants.contains(new DocumentId(local)) || total.compareTo(java.math.BigInteger.valueOf(limit)) > 0
                        || localTotal.compareTo(java.math.BigInteger.valueOf(localLimit)) <= 0)) throw invalid("Rejected union does not exceed its stated cap or has the wrong initiating group");
        return new RejectedAdmission(limit, local, localLimit, contributions);
    }
    private static List<String> ids(Collection<DocumentId> values) { return values.stream().sorted().map(DocumentId::value).toList(); }
    private static Map<String, String> identities(Map<DocumentId, String> values) {
        Map<String, String> result = new TreeMap<>(); values.forEach((id, value) -> result.put(id.value(), value)); return result;
    }
    private static Map<DocumentId, String> identities(JsonNode values) {
        if (values == null || !values.isObject()) throw invalid("Expected group identity map");
        Map<DocumentId, String> result = new TreeMap<>(); values.fields().forEachRemaining(row -> {
            if (!row.getValue().isTextual()) throw invalid("Expected group identity text"); result.put(new DocumentId(row.getKey()), row.getValue().textValue());
        }); return result;
    }
    private static List<DocumentId> documents(JsonNode row, String field) {
        List<DocumentId> result = strings(row, field).stream().map(DocumentId::new).toList();
        if (new HashSet<>(result).size() != result.size()) throw invalid("Duplicate group member"); return result;
    }
    private static long safe(JsonNode row, String key) {
        long value = integer(row, key); if (value < 0 || value > 9007199254740991L) throw invalid("Group amount outside safe integer range"); return value;
    }
    private static Map<String, Long> amounts(JsonNode values) {
        if (values == null || !values.isObject()) throw invalid("Expected group amount map");
        Map<String, Long> result = new TreeMap<>(); values.fieldNames().forEachRemaining(key -> result.put(key, safe(values, key))); return result;
    }
    private static java.math.BigInteger sum(Map<String, Long> values) {
        java.math.BigInteger total = java.math.BigInteger.ZERO;
        for (long value : values.values()) total = total.add(java.math.BigInteger.valueOf(value)); return total;
    }
    private static Map<String, Object> object(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("Expected closed rejected-charge object");
        Map<String, Object> result = new TreeMap<>(); node.fields().forEachRemaining(row -> {
            JsonNode value = row.getValue();
            Object exact;
            if (value.isNull()) exact = null;
            else if (value.isTextual()) exact = value.textValue();
            else if (value.isIntegralNumber() && value.canConvertToLong()) exact = value.longValue();
            else if (value.isObject()) exact = object(value);
            else throw invalid("Invalid rejected-charge primitive");
            result.put(row.getKey(), exact);
        }); return result;
    }
}
