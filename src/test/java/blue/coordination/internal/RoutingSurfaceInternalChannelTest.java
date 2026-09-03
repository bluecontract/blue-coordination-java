package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.EffectiveContractSnapshotTestFixture;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.TimelineChannel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Source-planning boundaries for processor-managed internal Channels. */
final class RoutingSurfaceInternalChannelTest {

    @Test
    void processorManagedChannelNeverBecomesAnExternalRoute() {
        // given
        EffectiveContractSnapshot internalChannel =
                EffectiveContractSnapshot.builder("/", "fromCollection")
                        .sourceContribution("internal-channel-contribution")
                        .effectiveTypeBlueId("future-collection-channel-id")
                        .role(EffectiveContractSnapshotConstants.Role
                                .PROCESSOR_CHANNEL)
                        .build();
        EffectiveContractSnapshot handler =
                EffectiveContractSnapshot.builder("/", "onCollectionEvent")
                        .sourceContribution("handler-contribution")
                        .effectiveTypeBlueId("handler-id")
                        .role(EffectiveContractSnapshotConstants.Role.HANDLER)
                        .dispatchField(
                                EffectiveContractSnapshotConstants
                                        .DispatchField.CHANNEL,
                                "fromCollection")
                        .build();

        // when
        RoutingSurface surface = RoutingSurface.fromManagedRootContracts(
                List.of(internalChannel, handler));

        // then
        assertEquals(List.of(), surface.definitions());
        assertEquals(List.of(), surface.operationDefinitions());
        assertEquals(List.of(), surface.externalTimelineIds());
    }

    @Test
    void onlyTimelineChannelEntersMixedExternalProviderSurface() {
        // given
        EffectiveContractSnapshot timelineChannel =
                EffectiveContractSnapshotTestFixture.withHeaders(
                        EffectiveContractSnapshot.builder(
                                        "/", "ownerTimeline")
                                .sourceContribution("timeline-contribution")
                                .effectiveTypeBlueId(TimelineChannel.blueId())
                                .role(EffectiveContractSnapshotConstants.Role
                                        .EXTERNAL_CHANNEL),
                        Map.of(
                                "timeline", FrozenNode.fromNode(new Node()
                                        .properties("timelineId",
                                                new Node().value(
                                                        "timeline/owner"))),
                                "actor", FrozenNode.fromNode(new Node()
                                        .properties("accountId",
                                                new Node().value("alice")))));
        EffectiveContractSnapshot onTimeline = handler(
                "onTimeline", "ownerTimeline");
        EffectiveContractSnapshot embeddedNode = internalChannel(
                "embeddedNode", "embedded-node-channel-id");
        EffectiveContractSnapshot embeddedCollection = internalChannel(
                "embeddedCollection",
                "embedded-collection-event-channel-id");
        EffectiveContractSnapshot documentUpdate = internalChannel(
                "documentUpdate", "document-update-channel-id");
        EffectiveContractSnapshot triggeredEvent = internalChannel(
                "triggeredEvent", "triggered-event-channel-id");

        // when
        RoutingSurface surface = RoutingSurface.fromManagedRootContracts(
                List.of(
                        timelineChannel,
                        embeddedNode,
                        embeddedCollection,
                        documentUpdate,
                        triggeredEvent,
                        onTimeline,
                        handler("onEmbeddedNode", "embeddedNode"),
                        handler("onEmbeddedCollection", "embeddedCollection"),
                        handler("onDocumentUpdate", "documentUpdate"),
                        handler("onTriggeredEvent", "triggeredEvent")));

        // then
        assertEquals(List.of(new RoutingSurface.Definition(
                        "/",
                        "onTimeline",
                        "ownerTimeline",
                        "timeline/owner",
                        "alice")),
                surface.definitions());
        assertEquals(List.of("timeline/owner"),
                surface.externalTimelineIds());
        assertEquals(List.of(), surface.operationDefinitions());
    }

    @Test
    void coordCollection05RuntimeCatalogAuthenticatesMixedChannelRoles() {
        // given
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        try (BlueRuntime runtime = BlueRuntime.create(objects, metrics)) {
            ExactValue root = runtime.exactSource("""
                    documentId: mixed-channel-role-proof
                    orders: {}
                    contracts:
                      embedded:
                        type: Process Embedded
                        collectionPaths:
                          - /orders
                      ownerTimeline:
                        type: Coordination/Timeline Channel
                        timeline:
                          type: MyOS/MyOS Timeline
                          timelineId: mixed/channel/owner
                        actor:
                          type: MyOS/Principal Actor
                          accountId: alice
                      embeddedNode:
                        type: Embedded Node Channel
                      embeddedCollection:
                        type: Embedded Collection Event Channel
                        collectionPath: /orders
                      documentUpdate:
                        type: Document Update Channel
                      triggeredEvent:
                        type: Triggered Event Channel
                      onOwnerTimeline:
                        type: Coordination/Sequential Workflow Operation
                        channel: ownerTimeline
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                      onEmbeddedNode:
                        type: Coordination/Sequential Workflow
                        channel: embeddedNode
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                      onEmbeddedCollection:
                        type: Coordination/Sequential Workflow
                        channel: embeddedCollection
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                      onDocumentUpdate:
                        type: Coordination/Sequential Workflow
                        channel: documentUpdate
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                      onTriggeredEvent:
                        type: Coordination/Sequential Workflow
                        channel: triggeredEvent
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                    """, objects, "mixed-channel-role-proof");

            // when
            EffectiveFragmentationCatalog catalog =
                    runtime.effectiveFragmentationCatalog(root.blueId());
            Map<String, String> roles = new LinkedHashMap<>();
            for (EffectiveContractSnapshot contract
                    : catalog.effectiveContractsByScope().get("/")) {
                roles.put(contract.key(), contract.role());
            }
            RoutingSurface surface = RoutingSurface.from(catalog, List.of());

            // then
            assertEquals(EffectiveContractSnapshotConstants.Role
                            .EXTERNAL_CHANNEL,
                    roles.get("ownerTimeline"));
            assertEquals(EffectiveContractSnapshotConstants.Role
                            .PROCESSOR_CHANNEL,
                    roles.get("embeddedNode"));
            assertEquals(EffectiveContractSnapshotConstants.Role
                            .PROCESSOR_CHANNEL,
                    roles.get("embeddedCollection"));
            assertEquals(EffectiveContractSnapshotConstants.Role
                            .PROCESSOR_CHANNEL,
                    roles.get("documentUpdate"));
            assertEquals(EffectiveContractSnapshotConstants.Role
                            .PROCESSOR_CHANNEL,
                    roles.get("triggeredEvent"));
            assertEquals(List.of(new RoutingSurface.Definition(
                            "/",
                            "onOwnerTimeline",
                            "ownerTimeline",
                            "mixed/channel/owner",
                            "alice")),
                    surface.definitions());
            assertEquals(List.of("mixed/channel/owner"),
                    surface.externalTimelineIds());
        }
    }

    private static EffectiveContractSnapshot internalChannel(
            String key,
            String typeBlueId) {
        return EffectiveContractSnapshot.builder("/", key)
                .sourceContribution(key + "-contribution")
                .effectiveTypeBlueId(typeBlueId)
                .role(EffectiveContractSnapshotConstants.Role
                        .PROCESSOR_CHANNEL)
                .build();
    }

    private static EffectiveContractSnapshot handler(
            String key,
            String channel) {
        return EffectiveContractSnapshot.builder("/", key)
                .sourceContribution(key + "-contribution")
                .effectiveTypeBlueId("handler-id")
                .role(EffectiveContractSnapshotConstants.Role.HANDLER)
                .dispatchField(
                        EffectiveContractSnapshotConstants.DispatchField
                                .CHANNEL,
                        channel)
                .build();
    }
}
