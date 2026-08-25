package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-SDK regression for conformance on the runtime processor generation. */
final class SdkRuntimeConformanceTest {

    @Test
    void exactSubtypeGeneralizesAndRejectPolicyRollsBack() throws Exception {
        assertNearestAncestorGeneralizes();
        assertRejectRollsBack();
    }

    private static void assertNearestAncestorGeneralizes() throws Exception {
        Scenario scenario = scenario("nearest-valid-ancestor", "nearest");
        try (BlueCoordination coordination = scenario.coordination()) {
            DocumentHandle document = scenario.document();
            assertTrue(coordination.advanced()
                    .auditOperationRoutes(document.id())
                    .stream()
                    .anyMatch(route -> "premiumOperation".equals(
                            route.operation())));

            EntryResult result = removePremium(
                    coordination, document, scenario.timeline());

            assertEquals(EntryDisposition.APPLIED, result.disposition(),
                    result.diagnostic().toString());
            assertEquals(1L, document.snapshot().epoch());
            assertEquals(scenario.parentBlueId(), typeBlueId(document));
            assertEquals("generalized", document.snapshot().textAt("/phase"));
            assertThrows(IllegalArgumentException.class,
                    () -> document.snapshot().valueAt("/premiumWorkflow"));
            ManagedSurfaceEvidence evidence = result.closures().get(0)
                    .managedSurfaceEvidence();
            assertTrue(evidence.documentTransitions().stream()
                    .flatMap(transition -> transition
                            .generatedGeneralizationWrites().stream())
                    .anyMatch(write -> "/type".equals(write.path())
                            && scenario.parentBlueId().equals(
                                    write.valueBlueId())));
            assertEquals(List.of(
                            ManagedSurfaceEvidence.OperationRouteChangeKind
                                    .REMOVE),
                    evidence.operationRouteChanges().stream()
                            .filter(change -> "premiumOperation".equals(
                                    change.before().orElseThrow().operation()))
                            .map(ManagedSurfaceEvidence.OperationRouteChange
                                    ::kind)
                            .toList());
            assertFalse(coordination.advanced()
                    .auditOperationRoutes(document.id())
                    .stream()
                    .anyMatch(route -> "premiumOperation".equals(
                            route.operation())));
        }
    }

    private static void assertRejectRollsBack() throws Exception {
        Scenario scenario = scenario("reject", "reject");
        try (BlueCoordination coordination = scenario.coordination()) {
            DocumentHandle document = scenario.document();
            String beforeBlueId = document.snapshot().blueId();
            String beforeExact = document.snapshot().exact().json();

            EntryResult result = removePremium(
                    coordination, document, scenario.timeline());

            assertEquals(EntryDisposition.REJECTED, result.disposition());
            assertEquals(0L, document.snapshot().epoch());
            assertEquals(beforeBlueId, document.snapshot().blueId());
            assertEquals(beforeExact, document.snapshot().exact().json());
            assertEquals(scenario.subtypeBlueId(), typeBlueId(document));
            assertFalse(document.snapshot().valueAt("/premiumWorkflow")
                    .json().isBlank());
            assertTrue(result.closures().get(0).changes().isEmpty());
            assertTrue(result.closures().get(0).managedSurfaceEvidence()
                    .operationRouteChanges().isEmpty());
            assertTrue(coordination.advanced()
                    .auditOperationRoutes(document.id())
                    .stream()
                    .anyMatch(route -> "premiumOperation".equals(
                            route.operation())));
        }
    }

    private static EntryResult removePremium(
            BlueCoordination coordination,
            DocumentHandle document,
            TimelineHandle timeline) {
        return coordination.operations().on(document)
                .from(timeline)
                .call("removePremium")
                .through("ownerChannel")
                .requestYaml("{}")
                .execute();
    }

    private static Scenario scenario(String mode, String suffix)
            throws Exception {
        Map<String, String> content = new LinkedHashMap<>();
        BlueCoordination coordination = BlueCoordination.builder()
                .exactNodeProvider(blueId -> java.util.Optional.ofNullable(
                        content.get(blueId)))
                .build();
        try {
            ExactBlueValue parent = coordination.values().providerContentYaml("""
                    name: Runtime conformance parent
                    phase:
                      type: Text
                    """);
            ExactBlueValue subtype = coordination.values().providerContentYaml("""
                    name: Runtime conformance premium subtype
                    type:
                      blueId: %s
                    premiumWorkflow:
                      schema:
                        required: true
                    contracts:
                      premiumChannel:
                        type: Coordination/Timeline Channel
                        timeline:
                          type: MyOS/MyOS Timeline
                          timelineId: sdk/runtime-conformance/premium
                        actor:
                          type: MyOS/Principal Actor
                          accountId: premium
                      premiumOperation:
                        type: Coordination/Sequential Workflow Operation
                        channel: premiumChannel
                        request: {}
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $return: true
                    """.formatted(parent.blueId()));
            content.put(parent.blueId(), parent.json());
            content.put(subtype.blueId(), subtype.json());

            String timelineId = "sdk/runtime-conformance/" + suffix;
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, "owner");
            DocumentHandle document = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    DocumentId.of(
                                            "sdk-runtime-conformance-" + suffix),
                                    documentYaml(
                                            subtype.blueId(), timelineId, mode))
                            .publicRoot()
                            .fromNow());
            return new Scenario(
                    coordination,
                    timeline,
                    document,
                    parent.blueId(),
                    subtype.blueId());
        } catch (Throwable failure) {
            coordination.close();
            throw failure;
        }
    }

    private static String documentYaml(
            String subtypeBlueId,
            String timelineId,
            String mode) {
        return """
                name: Runtime conformance premium instance
                type:
                  blueId: %s
                phase: premium
                premiumWorkflow:
                  tier: premium
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: owner
                  removePremium:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: remove
                              path: /premiumWorkflow
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: generalized
                          - $return: true
                  generalization:
                    type:
                      blueId: %s
                    defaultMode: %s
                """.formatted(
                        subtypeBlueId,
                        timelineId,
                        RuntimeBlueIds.TYPE_GENERALIZATION_POLICY,
                        mode);
    }

    private static String typeBlueId(DocumentHandle document)
            throws Exception {
        Node exact = UncheckedObjectMapper.JSON_MAPPER.readValue(
                document.snapshot().exact().json(), Node.class);
        return exact.getType().getBlueId();
    }

    private record Scenario(
            BlueCoordination coordination,
            TimelineHandle timeline,
            DocumentHandle document,
            String parentBlueId,
            String subtypeBlueId) {
    }
}
