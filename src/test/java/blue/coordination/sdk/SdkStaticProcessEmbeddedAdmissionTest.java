package blue.coordination.sdk;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused SDK parity and atomicity coverage for static admission. */
final class SdkStaticProcessEmbeddedAdmissionTest {
    @Test
    void acceptsExistingFullHistoryAdmissionPolicy() {
        // given
        String rootYaml = "name: static history root\ncount: 0\n";

        // when
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            ClosureHandle closure = blue.documents()
                    .admitStaticProcessEmbedded(
                            rootYaml,
                            ActivationPolicy.importFullHistory());

            // then
            assertEquals(exactId(rootYaml), closure.document("root").id());
            assertEquals(Set.of("root"), closure.publicRootAliases());
        }
    }

    @Test
    void recursivelyAdmitsInlineMembersWithContentDerivedIdentities() {
        // given
        String leafYaml = """
                name: leaf
                documentId:
                  misleading: nested-content-only
                count: 7
                """;
        String branchYaml = withChild(
                "branch", "ordinary-label", leafYaml);
        String rootYaml = withChild(
                "root", "caller-lineage-must-not-win", branchYaml);
        DocumentId expectedRoot = exactId(rootYaml);
        DocumentId expectedBranch = exactId(branchYaml);
        DocumentId expectedLeaf = exactId(leafYaml);

        // when
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            ClosureHandle closure = blue.documents()
                    .admitStaticProcessEmbedded(rootYaml);

            // then
            assertEquals(expectedRoot, closure.document("root").id());
            assertEquals(3, closure.documents().size());
            assertEquals(expectedRoot.value(),
                    closure.authoredDocument("root").blueId());
            assertEquals(expectedBranch.value(),
                    closure.authoredDocument("embedded-0").blueId());
            assertEquals(expectedLeaf.value(),
                    closure.authoredDocument("embedded-1").blueId());
            assertEquals(List.of("/child", "/child"),
                    closure.occurrences().stream()
                            .map(ClosureOccurrenceSnapshot::sourcePath)
                            .toList());
            assertEquals(Set.of(expectedBranch, expectedLeaf),
                    closure.occurrences().stream()
                            .map(ClosureOccurrenceSnapshot::targetDocumentId)
                            .collect(java.util.stream.Collectors.toSet()));
            assertEquals("caller-lineage-must-not-win",
                    closure.document("root").snapshot()
                            .textAt("/documentId"));
            assertEquals("ordinary-label",
                    blue.documents().require(expectedBranch).snapshot()
                            .textAt("/documentId"));
            assertEquals("nested-content-only",
                    blue.documents().require(expectedLeaf).snapshot()
                            .textAt("/documentId/misleading"));
            assertEquals(expectedBranch,
                    blue.advanced().auditManagedOccurrence(
                                    expectedRoot, "/child")
                            .orElseThrow().targetDocumentId());
            assertEquals(expectedLeaf,
                    blue.advanced().auditManagedOccurrence(
                                    expectedBranch, "/child")
                            .orElseThrow().targetDocumentId());
        }
    }

    @Test
    void collectionDuplicatesShareOneLineageAndKeepTwoOccurrences() {
        // given
        String childYaml = """
                name: shared child
                count: 11
                """;
        String rootYaml = "children:\n"
                + "  first:\n" + childYaml.indent(4)
                + "  second:\n" + childYaml.indent(4)
                + embeddedCollection("/children");
        DocumentId expectedRoot = exactId(rootYaml);
        DocumentId expectedChild = exactId(childYaml);

        // when
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            ClosureHandle closure = blue.documents()
                    .admitStaticProcessEmbedded(rootYaml);

            // then
            assertEquals(2, closure.documents().size());
            assertEquals(2, closure.occurrences().size());
            assertEquals(List.of("/children/first", "/children/second"),
                    closure.occurrences().stream()
                            .map(ClosureOccurrenceSnapshot::sourcePath)
                            .sorted()
                            .toList());
            assertEquals(List.of(expectedChild, expectedChild),
                    closure.occurrences().stream()
                            .map(ClosureOccurrenceSnapshot::targetDocumentId)
                            .toList());
            assertEquals(expectedChild,
                    blue.advanced().auditManagedOccurrence(
                                    expectedRoot, "/children/first")
                            .orElseThrow().targetDocumentId());
            assertEquals(expectedChild,
                    blue.advanced().auditManagedOccurrence(
                                    expectedRoot, "/children/second")
                            .orElseThrow().targetDocumentId());
            assertEquals(11L, blue.documents().require(expectedChild)
                    .snapshot().longAt("/count"));
        }
    }

    @Test
    void missingPureReferenceIsTypedAndWritesNoManagedState() {
        // given
        String childYaml = "name: provider child\ncount: 5\n";
        ExactBlueValue child = exact(childYaml);
        DocumentId childId = DocumentId.of(child.blueId());
        String rootYaml = """
                name: provider root
                child:
                  blueId: %s
                %s
                """.formatted(child.blueId(), embeddedPath("/child"));
        Map<String, String> available = new LinkedHashMap<>();
        List<String> reads = new ArrayList<>();
        ExactNodeProvider provider = blueId -> {
            reads.add(blueId);
            return Optional.ofNullable(available.get(blueId));
        };

        // when
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(provider)
                .build()) {
            CoordinationException missing = assertThrows(
                    CoordinationException.class,
                    () -> blue.documents()
                            .admitStaticProcessEmbedded(rootYaml));

            // then
            assertEquals(CoordinationErrorCode.NEEDS_RESOURCES,
                    missing.code());
            assertEquals(child.blueId(), missing.details().get("blueId"));
            assertEquals("/child", missing.details().get("sourcePath"));
            DocumentId rootId = DocumentId.of(
                    missing.details().get("sourceDocumentId"));
            assertEquals(List.of(child.blueId()), reads,
                    "one automatic resolution request must perform one "
                            + "provider lookup");
            assertThrows(CoordinationException.class,
                    () -> blue.documents().require(rootId));
            assertThrows(CoordinationException.class,
                    () -> blue.documents().require(childId));

            available.put(child.blueId(), child.json());
            ClosureHandle admitted = blue.documents()
                    .admitStaticProcessEmbedded(rootYaml);

            assertEquals(rootId, admitted.document("root").id());
            assertEquals(childId,
                    blue.advanced().auditManagedOccurrence(
                                    rootId, "/child")
                            .orElseThrow().targetDocumentId());
            assertEquals(5L, blue.documents().require(childId)
                    .snapshot().longAt("/count"));
        }
    }

    @Test
    void reusesExistingCurrentMemberAndReplaysTheSameRootAdmission() {
        // given
        String childYaml = "name: existing current child\ncount: 3\n";

        // when
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            ClosureHandle childClosure = blue.documents()
                    .admitStaticProcessEmbedded(childYaml);
            DocumentHandle child = childClosure.document("root");
            ExactBlueValue currentChild = child.snapshot().exact();
            String rootYaml = "name: parent of existing child\n"
                    + "child: " + currentChild.json() + "\n"
                    + embeddedPath("/child");

            ClosureHandle admitted = blue.documents()
                    .admitStaticProcessEmbedded(rootYaml);
            ClosureHandle replayed = blue.documents()
                    .admitStaticProcessEmbedded(rootYaml);

            // then
            assertEquals(2, admitted.documents().size());
            assertEquals(child.id(), admitted.document("embedded-0").id());
            assertEquals(child.id(), admitted.occurrences().get(0)
                    .targetDocumentId());
            assertEquals(1, child.history().size(),
                    "admission must not reinitialize an existing member");
            assertEquals(admitted.id(), replayed.id());
            assertEquals(admitted.document("root").id(),
                    replayed.document("root").id());
            assertEquals(admitted.document("embedded-0").id(),
                    replayed.document("embedded-0").id());
            assertEquals(1, replayed.document("root").history().size());
        }
    }

    @Test
    void preservesAllTypedDemandsWhenSeveralReferencesAreUnavailable() {
        // given
        ExactBlueValue first = exact("name: unavailable first\n");
        ExactBlueValue second = exact("name: unavailable second\n");
        String rootYaml = "first:\n  blueId: " + first.blueId() + "\n"
                + "second:\n  blueId: " + second.blueId() + "\n"
                + "contracts:\n"
                + "  embedded:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n"
                + "    paths: [/first, /second]\n";

        // when
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(ignored -> Optional.empty())
                .build()) {
            CoordinationException missing = assertThrows(
                    CoordinationException.class,
                    () -> blue.documents()
                            .admitStaticProcessEmbedded(rootYaml));

            // then
            assertEquals(CoordinationErrorCode.NEEDS_RESOURCES,
                    missing.code());
            assertTrue(missing.getMessage().contains(first.blueId()));
            assertTrue(missing.getMessage().contains(second.blueId()));
            assertEquals("/first", missing.details().get("sourcePath"));
        }
    }

    @Test
    void mismatchedProviderContentAlsoWritesNoManagedState() {
        // given
        ExactBlueValue expected = exact("name: expected\n");
        ExactBlueValue wrong = exact("name: wrong\n");
        String rootYaml = "child:\n  blueId: " + expected.blueId() + "\n"
                + embeddedPath("/child");
        // when
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(blueId -> blueId.equals(expected.blueId())
                        ? Optional.of(wrong.json())
                        : Optional.empty())
                .build()) {
            CoordinationException invalid = assertThrows(
                    CoordinationException.class,
                    () -> blue.documents()
                            .admitStaticProcessEmbedded(rootYaml));

            // then
            assertEquals(CoordinationErrorCode.INVALID_DOCUMENT_IDENTITY,
                    invalid.code());
            assertEquals(expected.blueId(), invalid.details().get("blueId"));
            assertEquals(wrong.blueId(),
                    invalid.details().get("actualBlueId"));
            assertThrows(CoordinationException.class,
                    () -> blue.documents().require(DocumentId.of(
                            invalid.details().get("sourceDocumentId"))));
        }
    }

    @Test
    void cyclicApplicationEvidenceAdmitsCompleteRecursiveComponent() {
        // given
        CyclicProviderFixture cycle = cyclicFixture("sdk-cycle-a", "sdk-cycle-b");
        ExactValue exact = ExactValue.fromVerifiedProviderEvidence(
                cycle.memberBlueId(), cycle.memberBody(), cycle.proof());
        ExactBlueValue retained = ExactBlueValue.wrap(exact);
        ExactNodeEvidence retainedEvidence = ExactNodeProvider.of(retained)
                .findExactEvidence(retained.blueId())
                .orElseThrow();
        String rootYaml = "child:\n  blueId: " + cycle.memberBlueId()
                + "\n" + embeddedPath("/child");

        // when
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(cycle.provider())
                .build()) {
            ClosureHandle closure = blue.documents()
                    .admitStaticProcessEmbedded(rootYaml);

            // then
            assertEquals(3, closure.documents().size());
            assertEquals(Set.of(cycle.memberBlueId(), cycle.peerBlueId()),
                    closure.authoredDocuments().values().stream()
                            .filter(ExactBlueValue::cyclicMember)
                            .map(ExactBlueValue::blueId)
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(retained.cyclicMember());
            assertTrue(retained.cyclicSetProof().isPresent());
            assertTrue(retainedEvidence.cyclicSetProof().isPresent());
        }
    }

    @Test
    void laterInvocationRetriesCyclicEvidenceAfterEarlierProviderMiss() {
        // given
        CyclicProviderFixture cycle = cyclicFixture("resume-a", "resume-b");
        Map<String, ExactNodeEvidence> available = new LinkedHashMap<>();
        ExactNodeProvider provider = ExactNodeProvider.withEvidence(
                requested -> Optional.ofNullable(available.get(requested)));
        String rootYaml = "child:\n  blueId: " + cycle.memberBlueId()
                + "\n" + embeddedPath("/child");

        // when
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(provider)
                .build()) {
            CoordinationException missing = assertThrows(
                    CoordinationException.class,
                    () -> blue.documents()
                            .admitStaticProcessEmbedded(rootYaml));
            available.putAll(cycle.evidenceByBlueId());
            ClosureHandle resumed = blue.documents()
                    .admitStaticProcessEmbedded(rootYaml);

            // then
            assertEquals(CoordinationErrorCode.NEEDS_RESOURCES,
                    missing.code());
            assertEquals(3, resumed.documents().size());
            assertEquals(Set.of(cycle.memberBlueId(), cycle.peerBlueId()),
                    resumed.authoredDocuments().values().stream()
                            .filter(ExactBlueValue::cyclicMember)
                            .map(ExactBlueValue::blueId)
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void cyclicStringProviderWithoutProofFailsWithTypedMissingProof() {
        // given
        CyclicProviderFixture cycle = cyclicFixture("missing-a", "missing-b");
        String serialized = json(cycle.memberBody());
        String rootYaml = "child:\n  blueId: " + cycle.memberBlueId()
                + "\n" + embeddedPath("/child");

        // when
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(ExactNodeProvider.of(
                        cycle.memberBlueId(), serialized))
                .build()) {
            CoordinationException failure = assertThrows(
                    CoordinationException.class,
                    () -> blue.documents()
                            .admitStaticProcessEmbedded(rootYaml));

            // then
            assertEquals(CoordinationErrorCode.MISSING_EXACT_VALUE_PROOF,
                    failure.code());
            assertEquals(cycle.memberBlueId(),
                    failure.details().get("blueId"));
        }
    }

    @Test
    void malformedStaleWrongOrTamperedCyclicEvidenceIsTypedInvalidProof() {
        // given
        CyclicProviderFixture expected = cyclicFixture("expected-a", "expected-b");
        CyclicProviderFixture unrelated = cyclicFixture("unrelated-a", "unrelated-b");
        CyclicProviderFixture stale = cyclicFixture(
                "expected-a", "expected-b", "older-representation");
        CyclicSetProof malformed = CyclicSetProof
                .fromDeclaredPlaceholderSet(List.of(
                        new Node().name("not-a-cyclic-placeholder-set")));
        Node tampered = expected.memberBody().clone();
        tampered.name("tampered-a");
        String rootYaml = "child:\n  blueId: " + expected.memberBlueId()
                + "\n" + embeddedPath("/child");

        // when / then
        assertInvalidCyclicProof(rootYaml, expected.memberBlueId(),
                expected.memberBody(), unrelated.proof());
        assertInvalidCyclicProof(rootYaml, expected.memberBlueId(),
                expected.memberBody(), stale.proof());
        assertInvalidCyclicProof(rootYaml, expected.memberBlueId(),
                expected.memberBody(), malformed);
        assertInvalidCyclicProof(rootYaml, expected.memberBlueId(),
                tampered, expected.proof());
    }

    @Test
    void typedProofProviderFailuresSurviveScopedAndStaticAdaptersUnchanged() {
        // given
        CyclicProviderFixture cycle = cyclicFixture("failure-a", "failure-b");
        String rootYaml = "child:\n  blueId: " + cycle.memberBlueId()
                + "\n" + embeddedPath("/child");

        // when / then
        for (CoordinationErrorCode code : List.of(
                CoordinationErrorCode.MISSING_EXACT_VALUE_PROOF,
                CoordinationErrorCode.INVALID_EXACT_VALUE_PROOF)) {
            CoordinationException original = new CoordinationException(
                    code,
                    "application evidence failure",
                    null,
                    Map.of("blueId", cycle.memberBlueId()));
            ExactNodeProvider provider = ExactNodeProvider.withEvidence(
                    ignored -> {
                        throw original;
                    });
            try (BlueCoordination blue = BlueCoordination.builder()
                    .exactNodeProvider(provider)
                    .build()) {
                CoordinationException observed = assertThrows(
                        CoordinationException.class,
                        () -> blue.documents()
                                .admitStaticProcessEmbedded(rootYaml));
                assertEquals(code, observed.code());
                assertEquals("application evidence failure",
                        observed.getMessage());
                assertEquals(cycle.memberBlueId(),
                        observed.details().get("blueId"));
            }
        }
    }

    @Test
    void publicRootInitializationEventPublishesAndReactsLocally() {
        // given
        DocumentId rootId = DocumentId.of("sdk-init-event-full-lifecycle");
        String rootYaml = """
                documentId: sdk-init-event-full-lifecycle
                initializationCount: 0
                reactionCount: 0
                contracts:
                  lifecycle:
                    type:
                      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo
                    order: 0
                    event:
                      type:
                        blueId: Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C
                  initializedEvent:
                    type:
                      blueId: %s
                    event:
                      type: Coordination/Event
                      kind: Lab/Initialization Event
                  initialize:
                    type: Coordination/Sequential Workflow
                    channel: lifecycle
                    order: 0
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /initializationCount
                              val: 1
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Lab/Initialization Event
                          - $return: true
                  react:
                    type: Coordination/Sequential Workflow
                    channel: initializedEvent
                    event:
                      type: Coordination/Event
                      kind: Lab/Initialization Event
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /reactionCount
                              val: {$add: [{$document: /reactionCount}, 1]}
                          - $return: true
                """.formatted(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL);

        // when
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            DocumentHandle admitted = blue.documents().admit(
                    ManagedDocument.yaml(rootId, rootYaml)
                            .publicRoot()
                            .fromNow());

            // then
            assertEquals(rootId, admitted.id());
            assertEquals(1L, admitted.snapshot()
                    .longAt("/initializationCount"));
            assertEquals(1L, admitted.snapshot().longAt("/reactionCount"));
            assertEquals(1, admitted.history().size());
            DocumentRevision initialization = admitted.history().get(0);
            assertEquals(DocumentRevision.Kind.INITIALIZATION,
                    initialization.kind());
            assertTrue(initialization.sourceEntry().isEmpty());
            assertEquals(1, initialization.publicEvents().size());
            assertTrue(blue.advanced().auditTimelineEntries().isEmpty(),
                    "admission is not an external Timeline Entry");
        }
    }

    private static String withChild(
            String name,
            String documentIdContent,
            String childYaml) {
        return "name: " + name + "\n"
                + "documentId: " + documentIdContent + "\n"
                + "child:\n" + childYaml.indent(2)
                + embeddedPath("/child");
    }

    private static String embeddedPath(String path) {
        return "contracts:\n"
                + "  embedded:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n"
                + "    paths:\n"
                + "      - " + path + "\n";
    }

    private static String embeddedCollection(String path) {
        return "contracts:\n"
                + "  embedded:\n"
                + "    type:\n"
                + "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n"
                + "    collectionPaths:\n"
                + "      - " + path + "\n";
    }

    private static DocumentId exactId(String yaml) {
        return DocumentId.of(exact(yaml).blueId());
    }

    private static ExactBlueValue exact(String yaml) {
        try (BlueCoordination verifier = BlueCoordination.inMemory()) {
            return verifier.values().yaml(yaml);
        }
    }

    private static void assertInvalidCyclicProof(
            String rootYaml,
            String memberBlueId,
            Node body,
            CyclicSetProof proof) {
        ExactNodeEvidence evidence = ExactNodeEvidence.cyclic(
                json(body), proof);
        ExactNodeProvider provider = ExactNodeProvider.withEvidence(
                requested -> memberBlueId.equals(requested)
                        ? Optional.of(evidence) : Optional.empty());
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(provider)
                .build()) {
            CoordinationException failure = assertThrows(
                    CoordinationException.class,
                    () -> blue.documents()
                            .admitStaticProcessEmbedded(rootYaml));
            assertEquals(CoordinationErrorCode.INVALID_EXACT_VALUE_PROOF,
                    failure.code());
            assertEquals(memberBlueId, failure.details().get("blueId"));
        }
    }

    private static CyclicProviderFixture cyclicFixture(
            String firstName,
            String secondName) {
        return cyclicFixture(firstName, secondName, null);
    }

    private static CyclicProviderFixture cyclicFixture(
            String firstName,
            String secondName,
            String representation) {
        Node first = new Node().name(firstName).properties(
                "peer", new Node().blueId("this#1"))
                .contracts(new Node().properties(
                        "embedded", processEmbedded("/peer")));
        Node second = new Node().name(secondName).properties(
                "peer", new Node().blueId("this#0"))
                .contracts(new Node().properties(
                        "embedded", processEmbedded("/peer")));
        if (representation != null) {
            first.properties("representation", new Node().value(
                    representation));
            second.properties("representation", new Node().value(
                    representation));
        }
        BasicNodeProvider source = new BasicNodeProvider(
                new Node().items(List.of(first, second)));
        String memberBlueId = source.getBlueIdByName(firstName);
        String peerBlueId = source.getBlueIdByName(secondName);
        CyclicSetProof proof = source.cyclicSetProofFor(memberBlueId)
                .proof().orElseThrow();
        return new CyclicProviderFixture(
                memberBlueId,
                source.fetchByBlueId(memberBlueId).get(0),
                peerBlueId,
                source.fetchByBlueId(peerBlueId).get(0),
                proof);
    }

    private static String json(Node node) {
        return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(node);
    }

    private static Node processEmbedded(String path) {
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties("paths", new Node().items(
                        new Node().value(path)));
    }

    private record CyclicProviderFixture(
            String memberBlueId,
            Node memberBody,
            String peerBlueId,
            Node peerBody,
            CyclicSetProof proof) {
        private Map<String, ExactNodeEvidence> evidenceByBlueId() {
            return Map.of(
                    memberBlueId,
                    ExactNodeEvidence.cyclic(json(memberBody), proof),
                    peerBlueId,
                    ExactNodeEvidence.cyclic(json(peerBody), proof));
        }

        private ExactNodeProvider provider() {
            Map<String, ExactNodeEvidence> evidence = evidenceByBlueId();
            return ExactNodeProvider.withEvidence(
                    requested -> Optional.ofNullable(evidence.get(requested)));
        }
    }
}
