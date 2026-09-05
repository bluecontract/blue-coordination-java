package blue.coordination.sdk;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.internal.CoordinationTestControl;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicSetProof;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK boundary proofs for demand-driven business-value references. */
final class SdkReferenceTransparentExecutionTest {
    private static final DocumentId COUNTER =
            DocumentId.of("reference-transparent-counter");
    private static final String TIMELINE =
            "sdk/reference-transparent/alice";

    @Test
    void missingAdmissionTypeIsTypedAndCanBeSuppliedForRetry() {
        // given
        ExactBlueValue definition = providerValue("name: Later SDK label\ntype: Text\n");
        String source = "label:\n  type:\n    blueId: " + definition.blueId()
                + "\n  value: ready\n";
        for (String admission : List.of("document", "static", "closure")) {
            RecordingProvider provider = new RecordingProvider();
            try (BlueCoordination blue = coordination(provider)) {
                // when
                CoordinationException blocked = assertThrows(CoordinationException.class,
                        () -> admitTypedLabel(blue, source, admission));
                // then
                assertEquals(CoordinationErrorCode.NEEDS_RESOURCES, blocked.code());
                assertEquals(definition.blueId(), blocked.details().get("blueId"));
                provider.put(definition);
                DocumentHandle document = admitTypedLabel(blue, source, admission);
                assertEquals(0L, document.snapshot().epoch());
                assertEquals("ready", document.snapshot().textAt("/label"));
                assertEquals(1, document.history().size());
            }
        }
    }

    private static DocumentHandle admitTypedLabel(BlueCoordination blue,
                                                  String source, String admission) {
        return switch (admission) {
            case "static" -> blue.documents().admitStaticProcessEmbedded(source).document("root");
            case "closure" -> blue.documents().admit(ManagedClosure.builder()
                    .document("root", COUNTER, source).publicRoot("root").fromNow().build())
                    .document("root");
            default -> blue.documents().admit(ManagedDocument.yaml(COUNTER, source)
                    .publicRoot().fromNow());
        };
    }

    @Test
    void referencedCatalogMoneyRetainsTypeEvidenceDuringNestedPatch() throws Exception {
        // given
        ExactBlueValue money = providerValue(resource("01-finos-money-content.yaml"));
        RecordingProvider provider = new RecordingProvider().put(money);
        // when
        try (BlueCoordination blue = coordination(provider)) {
            TimelineHandle timeline = timeline(blue);
            String source = resource("02-finos-margin-host.yaml")
                    .replace("finos-money-margin-proof", COUNTER.value())
                    .replace("playtest/reference-transparent/finos-margin/alice", TIMELINE);
            DocumentHandle host = blue.documents().admit(
                    ManagedDocument.yaml(COUNTER, source).publicRoot().fromNow());
            EntryResult result = blue.operations().on(host).from(timeline)
                    .call("setMarginRequirement").through("ownerChannel")
                    .requestYaml("amount: 1250000.0").execute();
            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
            assertEquals("1250000", host.snapshot().exact().scalarAt("/marginRequirement/val").toString());
        }
    }

    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/rc/" + name)) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void missingCounterValueDemandsExactNodeAndSameEntryResumesAfterRestart() {
        // given
        ExactBlueValue counterValue = providerValue("7");
        RecordingProvider provider = new RecordingProvider();

        // when
        try (BlueCoordination blue = coordination(provider)) {
            CoordinationTestControl control = CoordinationTestControl.attach(
                    blue.advanced().rawEngine());
            TimelineHandle timeline = timeline(blue);
            DocumentHandle counter = admitCounter(
                    blue, collapsed(counterValue.blueId()), null);
            String beforeBlueId = counter.snapshot().blueId();
            EntryHandle retained = increment(
                    blue, counter, timeline, 3L).submit();

            DrainResult blocked = blue.processing().drain();

            // then
            EntryResult suspended = blocked.entry(retained);
            assertEquals(EntryDisposition.NEEDS_RESOURCES,
                    suspended.disposition(), suspended.toString());
            assertTrue(blocked.blocked(), blocked.diagnostic().toString());
            assertEquals(1, suspended.closures().size());
            ClosureResult suspendedClosure = suspended.closures().get(0);
            assertEquals(1, suspendedClosure.resourceDemands().size());
            ClosureResult.ResourceDemand demand =
                    suspendedClosure.resourceDemands().get(0);
            assertEquals("EXACT_NODE", demand.kind());
            assertEquals(counterValue.blueId(), demand.blueId());
            assertEquals(DocumentId.of(
                            "blue-contracts/exact-node-provider"),
                    demand.sourceDocumentId());
            assertEquals("/", demand.sourcePath());
            assertEquals(
                    Optional.of(ClosureResult.ManagedResolutionStatus
                            .MISSING_EXACT_CONTENT),
                    demand.managedResolutionStatus());
            assertEquals(0L, counter.snapshot().epoch());
            assertEquals(beforeBlueId, counter.snapshot().blueId());

            provider.put(counterValue);
            control.restartFromStores();
            DrainResult resumed = blue.processing().drain();
            EntryResult applied = resumed.entry(retained);

            assertEquals(EntryDisposition.APPLIED, applied.disposition(),
                    applied.diagnostic().toString());
            assertEquals(suspendedClosure.closureId(),
                    applied.closures().get(0).closureId(),
                    "restart must retry the same retained closure lane");
            assertEquals(1L, counter.snapshot().epoch());
            assertEquals(10L, counter.snapshot().longAt("/counterValue"));
            assertEquals(List.of(retained.blueId()),
                    blue.advanced().auditTimeline(TIMELINE).stream()
                            .map(TimelineEntrySnapshot::blueId)
                            .toList(),
                    "resource acquisition must not append a second command");
        }
    }

    @Test
    void verifiedCounterValueReferenceMatchesInlineResultAndGas() {
        // given
        ExactBlueValue counterValue = providerValue("7");
        ExactBlueValue unusedValue = providerValue("payload: cold");
        // when
        RunEvidence inline = runCounter(
                "7", null, unusedValue, 4L);
        RunEvidence referenced = runCounter(
                collapsed(counterValue.blueId()), counterValue,
                unusedValue, 4L);

        // then
        assertEquals(inline.result().disposition(),
                referenced.result().disposition());
        assertEquals(EntryDisposition.APPLIED,
                referenced.result().disposition(),
                referenced.result().diagnostic().toString());
        assertEquals(inline.result().stats().gas(),
                referenced.result().stats().gas());
        assertEquals(inline.afterBlueId(), referenced.afterBlueId());
        assertEquals(inline.afterExactJson(), referenced.afterExactJson());
        assertEquals(11L, referenced.afterValue());
        assertTrue(referenced.providerReads().contains(
                counterValue.blueId()));
        assertFalse(referenced.providerReads().contains(
                unusedValue.blueId()),
                "an unrelated ordinary reference must remain collapsed");
    }

    @Test
    void directContractsReferenceRemainsAdmissionBlocking() {
        // given
        ExactBlueValue contracts = providerValue("""
                ownerChannel:
                  type: Coordination/Timeline Channel
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: sdk/reference-transparent/alice
                  actor:
                    type: MyOS/Principal Actor
                    accountId: alice
                """);
        RecordingProvider provider = new RecordingProvider();

        // when
        try (BlueCoordination blue = coordination(provider)) {
            CoordinationException blocked = assertThrows(
                    CoordinationException.class,
                    () -> blue.documents().admit(ManagedDocument.yaml(
                                    COUNTER,
                                    """
                                    documentId: reference-transparent-counter
                                    name: Missing direct contracts
                                    contracts:
                                      blueId: %s
                                    """.formatted(contracts.blueId()))
                            .publicRoot()
                            .fromNow()));

            // then
            assertEquals(CoordinationErrorCode.NEEDS_RESOURCES,
                    blocked.code());
            assertFalse(blocked.details().containsKey(
                            "collectionPlanningState"),
                    "generic provider unavailability must not be mislabeled "
                            + "as collection planning");
            assertTrue(provider.reads(contracts.blueId()) > 0);
            assertThrows(CoordinationException.class,
                    () -> blue.documents().require(COUNTER));
        }
    }

    @Test
    void unusedOrdinaryBusinessReferenceRemainsCold() {
        // given
        ExactBlueValue unused = providerValue("payload: never-opened");
        RecordingProvider provider = new RecordingProvider().put(unused);

        // when
        try (BlueCoordination blue = coordination(provider)) {
            TimelineHandle timeline = timeline(blue);
            DocumentHandle counter = admitCounter(
                    blue, "7", collapsed(unused.blueId()));
            assertEquals(0, provider.reads(unused.blueId()),
                    "admission must retain an unused business reference");

            EntryResult touched = blue.operations()
                    .on(counter)
                    .from(timeline)
                    .call("touch")
                    .through("ownerChannel")
                    .requestYaml("{}")
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED, touched.disposition(),
                    touched.diagnostic().toString());
            assertEquals("touched", counter.snapshot().textAt("/phase"));
            assertEquals(0, provider.reads(unused.blueId()));
        }
    }

    @Test
    void cyclicProofSupportsReadWhilePatchBelowMemberRemainsGuarded() {
        // given
        CyclicFixture cyclic = cyclicFixture();
        RecordingProvider readable = new RecordingProvider()
                .put(cyclic.memberBlueId(), cyclic.memberEvidence());

        // when
        try (BlueCoordination blue = coordination(readable)) {
            TimelineHandle timeline = timeline(blue);
            DocumentHandle counter = admitCyclic(
                    blue, cyclic.memberBlueId());

            EntryResult read = blue.operations()
                    .on(counter)
                    .from(timeline)
                    .call("readCyclic")
                    .through("ownerChannel")
                    .requestYaml("{}")
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED, read.disposition(),
                    read.diagnostic().toString());
            assertEquals("member-a",
                    counter.snapshot().textAt("/observed"));
            assertTrue(readable.reads(cyclic.memberBlueId()) > 0,
                    "cyclic reads require the exact member body and proof");
        }

        RecordingProvider guarded = new RecordingProvider()
                .put(cyclic.memberBlueId(), cyclic.memberEvidence());
        try (BlueCoordination blue = coordination(guarded)) {
            TimelineHandle timeline = timeline(blue);
            DocumentHandle counter = admitCyclic(
                    blue, cyclic.memberBlueId());
            String beforeBlueId = counter.snapshot().blueId();

            EntryResult rejected = blue.operations()
                    .on(counter)
                    .from(timeline)
                    .call("patchCyclic")
                    .through("ownerChannel")
                    .requestYaml("{}")
                    .execute();

            assertEquals(EntryDisposition.REJECTED,
                    rejected.disposition(), rejected.diagnostic().toString());
            assertEquals("CYCLIC_SET_MUTATION_UNSUPPORTED",
                    rejected.diagnostic().code());
            assertTrue(rejected.diagnostic().message().toLowerCase()
                    .contains("cyclic"), rejected.diagnostic().toString());
            assertEquals(0L, counter.snapshot().epoch());
            assertEquals(beforeBlueId, counter.snapshot().blueId());
            assertEquals(0, guarded.reads(cyclic.memberBlueId()),
                    "the cyclic mutation guard must run before provider I/O");
        }
    }

    private static RunEvidence runCounter(
            String counterValue,
            ExactBlueValue suppliedCounter,
            ExactBlueValue unused,
            long amount) {
        RecordingProvider provider = new RecordingProvider().put(unused);
        if (suppliedCounter != null) {
            provider.put(suppliedCounter);
        }
        try (BlueCoordination blue = coordination(provider)) {
            TimelineHandle timeline = timeline(blue);
            DocumentHandle counter = admitCounter(
                    blue, counterValue, collapsed(unused.blueId()));
            EntryResult result = increment(
                    blue, counter, timeline, amount).execute();
            return new RunEvidence(
                    result,
                    counter.snapshot().blueId(),
                    counter.snapshot().exact().json(),
                    counter.snapshot().longAt("/counterValue"),
                    provider.readBlueIds());
        }
    }

    private static BlueCoordination coordination(
            ExactNodeProvider provider) {
        return BlueCoordination.builder()
                .exactNodeProvider(provider)
                .build();
    }

    private static TimelineHandle timeline(BlueCoordination blue) {
        return blue.timelines().register(TIMELINE, "alice");
    }

    private static DocumentHandle admitCounter(
            BlueCoordination blue,
            String counterValue,
            String unused) {
        return blue.documents().admit(ManagedDocument.yaml(
                        COUNTER,
                        counterYaml(counterValue, unused))
                .publicRoot()
                .fromNow());
    }

    private static DocumentHandle admitCyclic(
            BlueCoordination blue,
            String memberBlueId) {
        return blue.documents().admit(ManagedDocument.yaml(
                        COUNTER,
                        cyclicYaml(memberBlueId))
                .publicRoot()
                .fromNow());
    }

    private static OperationCall increment(
            BlueCoordination blue,
            DocumentHandle counter,
            TimelineHandle timeline,
            long amount) {
        return blue.operations()
                .on(counter)
                .from(timeline)
                .call("increment")
                .through("ownerChannel")
                .requestYaml("amount: " + amount);
    }

    private static String counterYaml(
            String counterValue,
            String unused) {
        String unusedField = unused == null
                ? ""
                : "unused:\n" + unused.indent(2);
        return """
                documentId: reference-transparent-counter
                name: Reference-transparent counter
                phase: initial
                counterValue:
                %s
                %scontracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: sdk/reference-transparent/alice
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counterValue/value
                              val:
                                $add:
                                  - $document: /counterValue/value
                                  - $binding: event/message/request/amount
                          - $return: true
                  touch:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: touched
                          - $return: true
                """.formatted(counterValue.indent(2), unusedField);
    }

    private static String cyclicYaml(String memberBlueId) {
        return """
                documentId: reference-transparent-counter
                name: Reference-transparent cyclic reader
                observed: initial
                counterValue:
                  blueId: %s
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: sdk/reference-transparent/alice
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  readCyclic:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observed
                              val: {$document: /counterValue/label}
                          - $return: true
                  patchCyclic:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counterValue/label
                              val: changed
                          - $return: true
                """.formatted(memberBlueId);
    }

    private static String collapsed(String blueId) {
        return "blueId: " + blueId + "\n";
    }

    private static ExactBlueValue providerValue(String yaml) {
        try (BlueCoordination verifier = BlueCoordination.inMemory()) {
            return verifier.values().providerContentYaml(yaml);
        }
    }

    private static CyclicFixture cyclicFixture() {
        Node first = new Node().name("sdk-reference-cycle-a")
                .properties("label", new Node().value("member-a"))
                .properties("peer", new Node().blueId("this#1"));
        Node second = new Node().name("sdk-reference-cycle-b")
                .properties("label", new Node().value("member-b"))
                .properties("peer", new Node().blueId("this#0"));
        BasicNodeProvider source = new BasicNodeProvider(
                new Node().items(List.of(first, second)));
        String memberBlueId = source.getBlueIdByName(
                "sdk-reference-cycle-a");
        CyclicSetProof proof = source.cyclicSetProofFor(memberBlueId)
                .proof().orElseThrow();
        ExactNodeEvidence evidence = ExactNodeEvidence.cyclic(
                json(source.fetchByBlueId(memberBlueId).get(0)),
                proof.declaredPlaceholderSet().stream()
                        .map(SdkReferenceTransparentExecutionTest::json)
                        .toList());
        return new CyclicFixture(memberBlueId, evidence);
    }

    private static String json(Node node) {
        return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(node);
    }

    private static final class RecordingProvider
            implements ExactNodeProvider {
        private final Map<String, ExactNodeEvidence> evidence =
                new LinkedHashMap<>();
        private final List<String> reads = new ArrayList<>();

        private RecordingProvider put(ExactBlueValue value) {
            return put(value.blueId(), ExactNodeEvidence.ordinary(
                    value.json()));
        }

        private RecordingProvider put(
                String blueId,
                ExactNodeEvidence value) {
            evidence.put(blueId, value);
            return this;
        }

        @Override
        public Optional<String> findExactContent(String blueId) {
            return findExactEvidence(blueId)
                    .map(ExactNodeEvidence::exactContent);
        }

        @Override
        public Optional<ExactNodeEvidence> findExactEvidence(
                String blueId) {
            reads.add(blueId);
            return Optional.ofNullable(evidence.get(blueId));
        }

        private int reads(String blueId) {
            return (int) reads.stream()
                    .filter(blueId::equals)
                    .count();
        }

        private List<String> readBlueIds() {
            return List.copyOf(reads);
        }
    }

    private record RunEvidence(
            EntryResult result,
            String afterBlueId,
            String afterExactJson,
            long afterValue,
            List<String> providerReads) {
    }

    private record CyclicFixture(
            String memberBlueId,
            ExactNodeEvidence memberEvidence) {
    }
}
