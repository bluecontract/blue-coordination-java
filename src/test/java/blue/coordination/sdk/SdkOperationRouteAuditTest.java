package blue.coordination.sdk;

import blue.language.model.wire.BlueLanguageConstants;

import blue.coordination.api.DocumentId;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused acceptance for typed read-only compiled operation route metadata. */
final class SdkOperationRouteAuditTest {
    @Test
    void directAndAggregateRoutesExposeExactDeterministicSources() {
        // given
        DocumentId id = DocumentId.of("route-audit-aggregate");
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            blue.timelines().register("examples/routing/alice", "alice");
            blue.timelines().register("examples/routing/bob", "bob");
            DocumentHandle document = admit(blue, id, """
                    documentId: route-audit-aggregate
                    contracts:
                      bobChannel:
                        type: Coordination/Timeline Channel
                        timeline:
                          type: MyOS/MyOS Timeline
                          timelineId: examples/routing/bob
                        actor:
                          type: MyOS/Principal Actor
                          accountId: bob
                      aliceChannel:
                        type: Coordination/Timeline Channel
                        timeline:
                          type: MyOS/MyOS Timeline
                          timelineId: examples/routing/alice
                        actor:
                          type: MyOS/Principal Actor
                          accountId: alice
                      aggregateChannel:
                        type: Coordination/Composite Timeline Channel
                        channels:
                          - bobChannel
                          - aliceChannel
                      add:
                        type: Coordination/Sequential Workflow Operation
                        channel: aggregateChannel
                        request:
                          amount: {type: Integer}
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                    """);
            int entriesBefore = blue.advanced().rawEngine()
                    .metrics().journalEntryCount();

            // when
            List<OperationRouteSnapshot> routes = blue.advanced()
                    .auditOperationRoutes(id);

            // then
            assertEquals(List.of(new OperationRouteSnapshot(
                    "/",
                    "add",
                    "aggregateChannel",
                    List.of(
                            new TimelineSourceSnapshot(
                                    "examples/routing/alice", "alice"),
                            new TimelineSourceSnapshot(
                                    "examples/routing/bob", "bob")))),
                    withoutRequests(routes));
            Node effective = routes.get(0).requestPattern()
                    .orElseThrow().copyNode();
            Node declared = document.snapshot().valueAt(
                    "/contracts/add/request").copyNode();
            assertEquals(declared.getProperties().keySet(),
                    effective.getProperties().keySet());
            assertEquals(declared.getDescription(), effective.getDescription());
            assertEquals(BlueLanguageConstants.INTEGER_TYPE_BLUE_ID,
                    effective.getAsNode("/amount/type").getBlueId());
            assertEquals(entriesBefore, blue.advanced().rawEngine()
                    .metrics().journalEntryCount());
            assertThrows(UnsupportedOperationException.class,
                    () -> routes.get(0).acceptedSources().clear());
        }
    }

    @Test
    void repositoryOperationAncestryIncludesChatButExcludesBaseHandlers() {
        // given
        DocumentId id = DocumentId.of("route-audit-ancestry");
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            blue.timelines().register("alice", "alice");
            admit(blue, id, """
                    documentId: route-audit-ancestry
                    contracts:
                      aliceChannel:
                        type: Coordination/Timeline Channel
                        timeline:
                          type: MyOS/MyOS Timeline
                          timelineId: alice
                        actor:
                          type: MyOS/Principal Actor
                          accountId: alice
                      concrete:
                        type: Coordination/Sequential Workflow Operation
                        channel: aliceChannel
                        request: {}
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                      derivedChat:
                        type: Coordination/Chat Workflow Operation
                        channel: aliceChannel
                        request:
                          message: placeholder
                      bareOperation:
                        type: Coordination/Operation
                        channel: aliceChannel
                        request: {}
                      genericWorkflow:
                        type: Coordination/Sequential Workflow
                        channel: aliceChannel
                        event:
                          type: Coordination/Event
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                    """);

            // when
            List<OperationRouteSnapshot> routes = blue.advanced()
                    .auditOperationRoutes(id);

            // then
            assertEquals(List.of("concrete", "derivedChat"),
                    routes.stream().map(OperationRouteSnapshot::operation)
                            .toList());
            assertEquals(List.of(
                            new TimelineSourceSnapshot("alice", "alice")),
                    routes.get(1).acceptedSources());
        }
    }

    @Test
    void auditKeepsAbsentAndExactEmptyRequestPatternsDistinct() {
        // given
        DocumentId id = DocumentId.of("route-audit-request-presence");
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            blue.timelines().register("request-presence", "alice");
            admit(blue, id, """
                    documentId: route-audit-request-presence
                    contracts:
                      ownerChannel:
                        type: Coordination/Timeline Channel
                        timeline:
                          type: MyOS/MyOS Timeline
                          timelineId: request-presence
                        actor:
                          type: MyOS/Principal Actor
                          accountId: alice
                      acceptAny:
                        type: Coordination/Sequential Workflow Operation
                        channel: ownerChannel
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                      emptyOnly:
                        type: Coordination/Sequential Workflow Operation
                        channel: ownerChannel
                        request: {}
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                    """);

            // when
            List<OperationRouteSnapshot> routes = blue.advanced()
                    .auditOperationRoutes(id);
            OperationRouteSnapshot acceptAny = routes.stream()
                    .filter(route -> route.operation().equals("acceptAny"))
                    .findFirst()
                    .orElseThrow();
            OperationRouteSnapshot emptyOnly = routes.stream()
                    .filter(route -> route.operation().equals("emptyOnly"))
                    .findFirst()
                    .orElseThrow();

            // then
            assertFalse(acceptAny.requestPattern().isPresent());
            assertTrue(emptyOnly.requestPattern().isPresent());
        }
    }

    private static DocumentHandle admit(
            BlueCoordination blue,
            DocumentId id,
            String authoredYaml) {
        return blue.documents().admit(
                ManagedDocument.yaml(id, authoredYaml)
                        .publicRoot()
                        .fromNow());
    }

    private static List<OperationRouteSnapshot> withoutRequests(
            List<OperationRouteSnapshot> routes) {
        return routes.stream()
                .map(route -> new OperationRouteSnapshot(
                        route.scopePath(),
                        route.operation(),
                        route.channel(),
                        route.acceptedSources()))
                .toList();
    }
}
