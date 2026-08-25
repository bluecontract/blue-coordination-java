package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused stable-SDK proofs for processor-owned managed-surface evidence. */
final class SdkManagedSurfaceEvidenceTest {
    private static final String ACTOR = "owner";

    @Test
    void exposesExactContractComponentAndSubscriptionTransitionEvidence() {
        // given

        DocumentId id = DocumentId.of("sdk-managed-surface-contracts");
        String timelineId = "sdk/managed-surface/contracts/owner";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            DocumentHandle document = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    id,
                                    contractEvolutionDocument(id, timelineId))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue addedChannel = coordination.values().yaml("""
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: sdk/managed-surface/contracts/added
                    actor:
                      type: MyOS/Principal Actor
                      accountId: added
                    """);
            ExactBlueValue addedHandler = coordination.values().yaml("""
                    type: Coordination/Sequential Workflow Operation
                    channel: addedChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $return: true
                    """);
            String before = document.snapshot().blueId();

            // when

            EntryResult result = coordination.operations()
                    .on(document)
                    .from(timeline)
                    .call("reconfigure")
                    .through("ownerChannel")
                    .request(request -> {
                        request.exact("addedChannel", addedChannel);
                        request.exact("addedHandler", addedHandler);
                    })
                    .execute();

            // then

            assertEquals(EntryDisposition.APPLIED, result.disposition(),
                    result.diagnostic().toString());
            ManagedSurfaceEvidence evidence = result.closures().get(0)
                    .managedSurfaceEvidence();
            assertTrue(evidence.present());
            assertTrue(evidence.graphChanges().isEmpty());
            assertEquals(1, evidence.componentTransitions().size());
            assertEquals(ManagedSurfaceEvidence.ComponentTransitionKind
                            .UNCHANGED_MEMBERSHIP,
                    evidence.componentTransitions().get(0).kind());
            assertEquals(List.of(id), evidence.componentTransitions().get(0)
                    .before().get(0).memberDocumentIds());
            assertEquals(List.of(id), evidence.componentTransitions().get(0)
                    .after().get(0).memberDocumentIds());

            assertEquals(EnumSet.allOf(
                            ManagedSurfaceEvidence.SubscriptionOperation.class),
                    evidence.subscriptionChanges().stream()
                            .map(ManagedSurfaceEvidence.SubscriptionChange
                                    ::operation)
                            .collect(Collectors.toCollection(
                                    () -> EnumSet.noneOf(
                                            ManagedSurfaceEvidence
                                                    .SubscriptionOperation
                                                    .class))));
            evidence.subscriptionChanges().forEach(change -> {
                assertTrue(change.before().isPresent()
                                || change.after().isPresent());
                change.before().ifPresent(state -> assertEquals(
                        id, state.managedDocumentId()));
                change.after().ifPresent(state -> assertEquals(
                        id, state.managedDocumentId()));
            });

            ManagedSurfaceEvidence.DocumentTransition transition = evidence
                    .documentTransitions().stream()
                    .filter(item -> item.documentId().equals(id))
                    .filter(item -> !item.authoredContractPatches().isEmpty())
                    .findFirst()
                    .orElseThrow();
            assertEquals(before, transition.beforeDocumentBlueId());
            assertFalse(before.equals(transition.afterDocumentBlueId()));
            Map<String, ManagedSurfaceEvidence.ContractPatch> patches =
                    transition.authoredContractPatches().stream()
                            .collect(Collectors.toMap(
                                    ManagedSurfaceEvidence.ContractPatch::path,
                                    item -> item));
            assertEquals(Set.of(
                            "/contracts/addedChannel",
                            "/contracts/addedOperation",
                            "/contracts/retiringOperation",
                            "/contracts/retiringChannel"),
                    patches.keySet());
            assertAdd(patches.get("/contracts/addedChannel"),
                    addedChannel.blueId());
            assertAdd(patches.get("/contracts/addedOperation"),
                    addedHandler.blueId());
            assertRemove(patches.get("/contracts/retiringOperation"));
            assertRemove(patches.get("/contracts/retiringChannel"));
        }
    }

    private static void assertAdd(
            ManagedSurfaceEvidence.ContractPatch patch,
            String expectedAuthoredBlueId) {
        assertEquals(ManagedSurfaceEvidence.ContractPatchOperation.ADD,
                patch.operation());
        assertEquals(Optional.of(expectedAuthoredBlueId),
                patch.authoredValueBlueId());
        assertTrue(patch.beforeValueBlueId().isEmpty());
        assertTrue(patch.afterValueBlueId().isPresent());
        assertFalse(patch.authoredValueBlueId().equals(
                patch.afterValueBlueId()));
    }

    private static void assertRemove(
            ManagedSurfaceEvidence.ContractPatch patch) {
        assertEquals(ManagedSurfaceEvidence.ContractPatchOperation.REMOVE,
                patch.operation());
        assertTrue(patch.authoredValueBlueId().isEmpty());
        assertTrue(patch.beforeValueBlueId().isPresent());
        assertTrue(patch.afterValueBlueId().isEmpty());
    }

    private static String contractEvolutionDocument(
            DocumentId id,
            String timelineId) {
        return """
                documentId: %s
                state: initial
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  retiringChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: sdk/managed-surface/contracts/retiring
                    actor:
                      type: MyOS/Principal Actor
                      accountId: retiring
                  retiringOperation:
                    type: Coordination/Sequential Workflow Operation
                    channel: retiringChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $return: true
                  reconfigure:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      addedChannel: {}
                      addedHandler: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /contracts/addedChannel
                              val: {$binding: event/message/request/addedChannel}
                          - $appendChange:
                              op: add
                              path: /contracts/addedOperation
                              val: {$binding: event/message/request/addedHandler}
                          - $appendChange:
                              op: remove
                              path: /contracts/retiringOperation
                          - $appendChange:
                              op: remove
                              path: /contracts/retiringChannel
                          - $appendChange:
                              op: replace
                              path: /state
                              val: updated
                          - $return: true
                """.formatted(id.value(), timelineId, ACTOR);
    }

}
