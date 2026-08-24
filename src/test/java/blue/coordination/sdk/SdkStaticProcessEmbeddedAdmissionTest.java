package blue.coordination.sdk;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
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
            assertEquals(List.of(child.blueId()), reads);
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
}
