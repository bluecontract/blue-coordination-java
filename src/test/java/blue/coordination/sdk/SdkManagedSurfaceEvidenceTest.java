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

    @Test
    void dynamicFeatureInstallAndRemovalExposeExactRouteChanges() {
        // given
        DocumentId id = DocumentId.of("sdk-managed-surface-feature");
        String ownerTimelineId = "sdk/managed-surface/feature/owner";
        String featureTimelineId = "sdk/managed-surface/feature/caller";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle owner = coordination.timelines().register(
                    ownerTimelineId, ACTOR);
            TimelineHandle featureCaller = coordination.timelines().register(
                    featureTimelineId, "feature-caller");
            DocumentHandle document = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    id,
                                    dynamicFeatureDocument(
                                            id, ownerTimelineId))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue featureChannel = coordination.values().yaml("""
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: sdk/managed-surface/feature/caller
                    actor:
                      type: MyOS/Principal Actor
                      accountId: feature-caller
                    """);
            ExactBlueValue featureOperation = coordination.values().yaml("""
                    type: Coordination/Sequential Workflow Operation
                    channel: featureChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /featureInvoked
                              val: true
                          - $return: true
                    """);

            // when
            // Install the dynamic route and invoke it through its new source.
            EntryResult installed = coordination.operations()
                    .on(document)
                    .from(owner)
                    .call("installFeature")
                    .through("ownerChannel")
                    .request(request -> {
                        request.exact("featureChannel", featureChannel);
                        request.exact("featureOperation", featureOperation);
                    })
                    .execute();
            EntryResult invoked = coordination.operations()
                    .on(document)
                    .from(featureCaller)
                    .call("dynamicFeature")
                    .through("featureChannel")
                    .requestYaml("{}")
                    .execute();

            // then
            // The prepared index reports exactly one logical add.
            assertEquals(EntryDisposition.APPLIED, installed.disposition(),
                    installed.diagnostic().toString());
            assertEquals(EntryDisposition.APPLIED, invoked.disposition(),
                    invoked.diagnostic().toString());
            assertEquals(List.of(
                            ManagedSurfaceEvidence.OperationRouteChangeKind.ADD),
                    installed.closures().get(0).managedSurfaceEvidence()
                            .operationRouteChanges().stream()
                            .map(ManagedSurfaceEvidence.OperationRouteChange
                                    ::kind)
                            .toList());
            ManagedSurfaceEvidence.OperationRouteState added = installed
                    .closures().get(0).managedSurfaceEvidence()
                    .operationRouteChanges().get(0).after().orElseThrow();
            assertEquals("dynamicFeature", added.operation());
            assertEquals("featureChannel", added.channel());
            assertEquals(List.of(new TimelineSourceSnapshot(
                            featureTimelineId, "feature-caller")),
                    added.acceptedSources());

            // when: remove
            EntryResult removed = coordination.operations()
                    .on(document)
                    .from(owner)
                    .call("removeFeature")
                    .through("ownerChannel")
                    .requestYaml("{}")
                    .execute();

            // then: the same route retires and committed ordinals restart at 0
            assertEquals(EntryDisposition.APPLIED, removed.disposition(),
                    removed.diagnostic().toString());
            List<ManagedSurfaceEvidence.OperationRouteChange> routeChanges =
                    removed.closures().get(0).managedSurfaceEvidence()
                            .operationRouteChanges();
            assertEquals(1, routeChanges.size());
            assertEquals(0L, routeChanges.get(0).ordinal());
            assertEquals(
                    ManagedSurfaceEvidence.OperationRouteChangeKind.REMOVE,
                    routeChanges.get(0).kind());
            assertEquals("dynamicFeature",
                    routeChanges.get(0).before().orElseThrow().operation());
            assertTrue(routeChanges.get(0).after().isEmpty());
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

    private static String dynamicFeatureDocument(
            DocumentId id,
            String ownerTimelineId) {
        return """
                documentId: %s
                name: Dynamic feature route evidence
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  installFeature:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      featureChannel: {}
                      featureOperation: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /contracts/featureChannel
                              val: {$binding: event/message/request/featureChannel}
                          - $appendChange:
                              op: add
                              path: /contracts/dynamicFeature
                              val: {$binding: event/message/request/featureOperation}
                          - $return: true
                  removeFeature:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: remove
                              path: /contracts/dynamicFeature
                          - $appendChange:
                              op: remove
                              path: /contracts/featureChannel
                          - $return: true
                """.formatted(id.value(), ownerTimelineId, ACTOR);
    }

}
