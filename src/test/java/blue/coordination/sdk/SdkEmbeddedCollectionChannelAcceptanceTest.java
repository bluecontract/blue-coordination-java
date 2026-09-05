package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.EmbeddedCollectionPlanningAudit;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** End-to-end Coordination coverage for collection-member event routing. */
final class SdkEmbeddedCollectionChannelAcceptanceTest {
    private static final String ACTOR = "alice";

    @Test
    void collectionAuditDistinguishesAbsenceAndEmptyRepresentationParity() {
        // given
        String emptyCollection = "members: {}";

        // when
        CollectionAuditProbe absent = auditCollection(null, false);
        CollectionAuditProbe inlineEmpty = auditCollection(emptyCollection, false);
        CollectionAuditProbe referencedEmpty = auditCollection(null, true);

        // then
        assertEquals(List.of(new EmbeddedCollectionPlanningAudit(
                        "/members",
                        EmbeddedCollectionPlanningAudit.State.ABSENT,
                        0,
                        "declared collection is absent")),
                absent.audits());
        EmbeddedCollectionPlanningAudit expectedEmpty =
                new EmbeddedCollectionPlanningAudit(
                        "/members",
                        EmbeddedCollectionPlanningAudit.State.PRESENT_EMPTY,
                        0,
                        "declared collection is present and empty");
        assertEquals(List.of(expectedEmpty), inlineEmpty.audits());
        assertEquals(List.of(expectedEmpty), referencedEmpty.audits());
        assertNotEquals(absent.rootBlueId(), inlineEmpty.rootBlueId());
        assertEquals(inlineEmpty.rootBlueId(), referencedEmpty.rootBlueId(),
                "inline and verified-reference {} must plan identically");
    }

    @Test
    void coordCollection06RoutesDirectMembersAndDescendantsEndToEnd() {
        // given
        DocumentId rootId = DocumentId.of("collection-channel-root");
        DocumentId memberId = DocumentId.of("collection-channel-member");
        DocumentId nestedId = DocumentId.of("collection-channel-nested");
        String memberTimelineId = "collection/channel/member/alice";
        String nestedTimelineId = "collection/channel/nested/alice";
        ManagedClosure closure = ManagedClosure.builder()
                .document("root", rootId, root(rootId))
                .document("member", memberId,
                        member(memberId, memberTimelineId))
                .document("nested", nestedId,
                        nested(nestedId, nestedTimelineId))
                .bindOccurrence("root", "/members/member", "member")
                .bindOccurrence("member", "/nested", "nested")
                .publicRoot("root")
                .fromNow()
                .build();

        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle memberTimeline = coordination.timelines().register(
                    memberTimelineId, ACTOR);
            TimelineHandle nestedTimeline = coordination.timelines().register(
                    nestedTimelineId, ACTOR);
            ClosureHandle admitted = coordination.documents().admit(closure);

            // when
            EntryResult direct = coordination.operations()
                    .on(admitted.document("member"))
                    .from(memberTimeline)
                    .call("emitSignal")
                    .through("ownerChannel")
                    .request(request -> { })
                    .execute();
            EntryResult nested = coordination.operations()
                    .on(admitted.document("nested"))
                    .from(nestedTimeline)
                    .call("emitSignal")
                    .through("ownerChannel")
                    .request(request -> { })
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED, direct.disposition(),
                    direct.diagnostic().toString());
            assertFalse(direct.diagnostic().present());
            assertEquals(EntryDisposition.APPLIED, nested.disposition(),
                    nested.diagnostic().toString());
            assertFalse(nested.diagnostic().present());
            assertEquals(1L, admitted.document("root").snapshot()
                    .longAt("/directMemberEvents"));
            assertEquals(2L, admitted.document("root").snapshot()
                    .longAt("/descendantEvents"));
            assertEquals(List.of("direct-observed", "descendant-observed"),
                    eventKinds(direct));
            assertEquals(List.of("descendant-observed"), eventKinds(nested));
            Node currentRoot = admitted.document("root").snapshot()
                    .exact().copyNode();
            assertNull(NodePathEditor.getOrNull(
                    currentRoot, "/contracts/checkpoint"),
                    "processor-managed collection Channels must not own "
                            + "checkpoint state");
            assertEquals(List.of(new EmbeddedCollectionPlanningAudit(
                            "/members",
                            EmbeddedCollectionPlanningAudit.State
                                    .PRESENT_MEMBERS,
                            1,
                            "declared collection has current members")),
                    coordination.advanced()
                            .auditEmbeddedCollections(rootId));
        }
    }

    private static List<String> eventKinds(EntryResult result) {
        return result.publicEvents().stream()
                .map(event -> (String) event.exact().scalarAt("/kind"))
                .toList();
    }

    private static CollectionAuditProbe auditCollection(
            String collectionYaml,
            boolean referenceEmpty) {
        DocumentId id = DocumentId.of("collection-audit-root");
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            String collection = collectionYaml;
            if (referenceEmpty) {
                String emptyBlueId = coordination.values().yaml("{}")
                        .blueId();
                collection = "members:\n  blueId: " + emptyBlueId;
            }
            String source = """
                    documentId: collection-audit-root
                    %s
                    contracts:
                      embedded:
                        type: Process Embedded
                        collectionPaths:
                          - /members
                    """.formatted(collection == null ? "" : collection);
            DocumentHandle handle = coordination.documents().admit(
                    ManagedDocument.yaml(id, source)
                            .publicRoot()
                            .fromNow());
            return new CollectionAuditProbe(
                    handle.snapshot().blueId(),
                    coordination.advanced().auditEmbeddedCollections(id));
        }
    }

    private static String root(DocumentId id) {
        return """
                documentId: %s
                directMemberEvents: 0
                descendantEvents: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /members
                  directMembers:
                    type: Embedded Collection Event Channel
                    order: 10
                    collectionPath: /members
                    event: {type: Coordination/Event, kind: collection-signal}
                  countDirectMembers:
                    type: Coordination/Sequential Workflow
                    order: 10
                    channel: directMembers
                    event: {type: Coordination/Event, kind: collection-signal}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /directMemberEvents
                              val: {$add: [{$document: /directMemberEvents}, 1]}
                          - $appendEvent: {type: Coordination/Event, kind: direct-observed}
                          - $return: true
                  allCollectionDescendants:
                    type: Embedded Collection Event Channel
                    order: 20
                    collectionPath: /members
                    includeDescendants: true
                    event: {type: Coordination/Event, kind: collection-signal}
                  countCollectionDescendants:
                    type: Coordination/Sequential Workflow
                    order: 20
                    channel: allCollectionDescendants
                    event: {type: Coordination/Event, kind: collection-signal}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /descendantEvents
                              val: {$add: [{$document: /descendantEvents}, 1]}
                          - $appendEvent: {type: Coordination/Event, kind: descendant-observed}
                          - $return: true
                """.formatted(id.value());
    }

    private static String member(DocumentId id, String timelineId) {
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /nested
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  emitSignal:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent: {type: Coordination/Event, kind: collection-signal}
                          - $return: true
                """.formatted(id.value(), timelineId, ACTOR);
    }

    private static String nested(DocumentId id, String timelineId) {
        return """
                documentId: %s
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  emitSignal:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent: {type: Coordination/Event, kind: collection-signal}
                          - $return: true
                """.formatted(id.value(), timelineId, ACTOR);
    }

    private record CollectionAuditProbe(
            String rootBlueId,
            List<EmbeddedCollectionPlanningAudit> audits) {
        private CollectionAuditProbe {
            audits = List.copyOf(audits);
        }
    }
}
