package blue.coordination.internal;

import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
