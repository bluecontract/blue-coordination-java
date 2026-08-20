package blue.coordination.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.provider.NodeProvider;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Release gate: one PROCESS opens only its selected exact workflow body. */
final class SelectedWorkflowBodyLocalityTest {

    @Test
    void processReadsOnlyTheSelectedWorkflowBodyByExactBlueId() {
        // given
        RecordingBodyProvider bodies = new RecordingBodyProvider();
        try (CoordinationTestRuntime runtime = CoordinationTestRuntime.create(
                BlueRepository.current(),
                bodies,
                BlueCachePolicy.boundedDefaults())) {
            ExactBody selected = body(runtime, """
                    - type: Coordination/Compute
                      do:
                        - $appendChange:
                            op: replace
                            path: /counter
                            val: 1
                        - $return: true
                    """);
            ExactBody rejected = body(runtime, """
                    - type: Coordination/Compute
                      do:
                        - $return: true
                    """);
            bodies.put(selected);
            bodies.put(rejected);
            DocumentProcessingResult initialized = runtime.initializeDocument(
                    runtime.yamlToNode(document(
                            selected.blueId(), rejected.blueId())));
            ExternalOrderKey activation = ExternalOrderKey.of(List.of(0L));
            SubscriptionDelta initial = runtime.contracts()
                    .subscriptionSurfaceProjection().projectInitial(
                            initialized.document(), 0L, activation);
            Node event = runtime.yamlToNode(selectedEvent());
            ExternalOrderKey eventOrder = ExternalOrderKey.of(List.of(
                    1_700_000_000_000_000L,
                    "gate/selected",
                    DirectBlueIdCalculator.calculateBlueId(event)));

            // when
            runtime.clearResolvedSnapshotCache();
            bodies.resetReads();
            ExternalDeliveryPlan plan = runtime.contracts()
                    .currentRootDeliveryPlanDeriver(
                            0L, eventOrder, initial.added())
                    .derive(initialized.document(), event);
            PlatformProcessingResult processed = runtime.contracts()
                    .processForPlatformCommit(
                            initialized.document(),
                            event,
                            PlatformProcessInvocation.builder()
                                    .deliveryPlan(plan)
                                    .nodeProvider(runtime.nodeProvider())
                                    .build());

            // then
            assertEquals(ProcessorStatus.SUCCESS, initialized.status());
            assertEquals(ProcessorStatus.SUCCESS,
                    processed.processResult().status());
            assertEquals(BigInteger.ONE, processed.processResult().document()
                    .getProperties().get("counter").getValue());
            assertEquals(List.of(selected.blueId()), bodies.bodyReads());
            assertEquals(1, plan.deliveries().size());
            assertEquals("selectedChannel",
                    plan.deliveries().get(0).channelKey());
            assertFalse(bodies.bodyReads().contains(rejected.blueId()));
        }
    }

    private static ExactBody body(
            CoordinationTestRuntime runtime,
            String yaml) {
        ResolvedSnapshot snapshot = runtime.resolveToSnapshot(
                runtime.yamlToNode(yaml));
        return new ExactBody(
                snapshot.blueId(), snapshot.frozenCanonicalRoot().toNode());
    }

    private static String document(String selected, String rejected) {
        return """
                counter: 0
                contracts:
                  selectedChannel:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: gate/selected}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  rejectedChannel:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: gate/rejected}
                    actor: {type: MyOS/Principal Actor, accountId: bob}
                  selected:
                    type: Coordination/Sequential Workflow Operation
                    channel: selectedChannel
                    request: {}
                    steps: {blueId: %s}
                  rejected:
                    type: Coordination/Sequential Workflow Operation
                    channel: rejectedChannel
                    request: {}
                    steps: {blueId: %s}
                """.formatted(selected, rejected);
    }

    private static String selectedEvent() {
        return """
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: gate/selected}
                timestamp: 1700000000000000
                actor: {type: MyOS/Principal Actor, accountId: alice}
                message:
                  type: Coordination/Operation Request
                  operation: selected
                  channel: selectedChannel
                  request: {}
                """;
    }

    private record ExactBody(String blueId, Node node) {
    }

    private static final class RecordingBodyProvider implements NodeProvider {
        private final Map<String, Node> bodies = new LinkedHashMap<>();
        private final List<String> reads = new ArrayList<>();

        private void put(ExactBody body) {
            bodies.put(body.blueId(), body.node());
        }

        private void resetReads() {
            reads.clear();
        }

        private List<String> bodyReads() {
            return List.copyOf(reads);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node body = bodies.get(blueId);
            if (body == null) {
                return null;
            }
            reads.add(blueId);
            return List.of(body.clone());
        }
    }
}
