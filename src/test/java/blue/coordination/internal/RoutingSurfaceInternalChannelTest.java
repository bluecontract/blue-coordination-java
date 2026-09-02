package blue.coordination.internal;

import blue.language.model.Node;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.EffectiveContractSnapshotTestFixture;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.TimelineChannel;
import org.junit.jupiter.api.Test;

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
