package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-SDK coverage for targeted diagnostics and mixed cohort results. */
final class SdkEdgeResultTest {
    private static final String ACTOR = "alice";

    @Test
    void missingTargetChannelReturnsPreciseRejectedResult() {
        DocumentId counterId = DocumentId.of("sdk-edge-counter");
        String timelineId = "sdk/edge/counter";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            DocumentHandle counter = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    counterId,
                                    successDocument(counterId, timelineId))
                            .publicRoot()
                            .fromNow());
            String before = counter.snapshot().blueId();

            EntryResult result = coordination.operations()
                    .on(counter)
                    .from(timeline)
                    .call("advance")
                    .through("missingChannel")
                    .requestYaml("amount: 1")
                    .execute();

            assertEquals(EntryDisposition.REJECTED, result.disposition());
            assertEquals("TARGET_CHANNEL_NOT_FOUND",
                    result.diagnostic().code());
            assertEquals(Map.of(
                            "documentId", counterId.value(),
                            "operation", "advance",
                            "channel", "missingChannel"),
                    result.diagnostic().details());
            assertTrue(result.closures().isEmpty());
            assertTrue(result.publicEvents().isEmpty());
            assertEquals(ProcessingStats.zero(), result.stats());
            assertEquals(before, counter.snapshot().blueId());
            assertEquals(0L, counter.snapshot().epoch());
            assertEquals(0L, counter.snapshot().longAt("/counter"));
        }
    }

    @Test
    void disconnectedSuccessAndFailureProduceMixedResult() {
        DocumentId successId = DocumentId.of("sdk-edge-a-success");
        DocumentId failureId = DocumentId.of("sdk-edge-z-failure");
        String timelineId = "sdk/edge/mixed";
        ManagedClosure definition = ManagedClosure.builder()
                .document("success", successId,
                        successDocument(successId, timelineId))
                .document("failure", failureId,
                        failureDocument(failureId, timelineId))
                .publicRoot("success")
                .publicRoot("failure")
                .fromNow()
                .build();

        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            ClosureHandle admitted = coordination.documents().admit(
                    definition);
            DocumentHandle success = admitted.document("success");
            DocumentHandle failure = admitted.document("failure");
            String failureBefore = failure.snapshot().blueId();
            ExactBlueValue event = coordination.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    timestamp: 2100000000000201
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                    message:
                      type: Coordination/Operation Request
                      operation: advance
                      channel: ownerChannel
                      request:
                        amount: 1
                    """.formatted(timelineId, ACTOR));

            EntryResult result = coordination.events().from(timeline)
                    .exact(event)
                    .execute();

            assertEquals(EntryDisposition.MIXED, result.disposition());
            assertFalse(result.applied());
            assertEquals("MIXED_CLOSURE_OUTCOMES",
                    result.diagnostic().code());
            assertEquals(Map.of("closureCount", "2"),
                    result.diagnostic().details());
            assertEquals(2, result.closures().size());

            ClosureResult applied = result.closures().get(0);
            ClosureResult rejected = result.closures().get(1);
            assertEquals(EntryDisposition.APPLIED, applied.disposition());
            assertTrue(applied.applied());
            assertFalse(applied.diagnostic().present());
            assertEquals(Set.of(successId), changedDocuments(applied));
            assertEquals(List.of(successId),
                    applied.stats().documentStepOrder());
            assertEquals(EntryDisposition.REJECTED,
                    rejected.disposition());
            assertFalse(rejected.applied());
            assertTrue(rejected.diagnostic().present());
            assertTrue(rejected.changes().isEmpty());
            assertEquals(List.of(failureId),
                    rejected.stats().documentStepOrder());

            assertEquals(List.of(successId, failureId),
                    result.stats().documentStepOrder());
            assertEquals(1L, result.stats().committedTransitions());
            assertEquals(2L, result.stats().documentsOpened());
            assertTrue(result.stats().gas() > 0L);
            assertEquals(result.publicEvents(), result.closures().stream()
                    .flatMap(closure -> closure.publicEvents().stream())
                    .toList());

            assertEquals(1L, success.snapshot().epoch());
            assertEquals(1L, success.snapshot().longAt("/counter"));
            assertEquals(0L, failure.snapshot().epoch());
            assertEquals(0L, failure.snapshot().longAt("/counter"));
            assertEquals(failureBefore, failure.snapshot().blueId());
        }
    }

    private static Set<DocumentId> changedDocuments(ClosureResult result) {
        return result.changes().stream()
                .map(DocumentChange::documentId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String successDocument(
            DocumentId documentId,
            String timelineId) {
        return """
                documentId: %s
                counter: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  advance:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val:
                                $add:
                                  - $document: /counter
                                  - $binding: event/message/request/amount
                          - $return: true
                """.formatted(documentId.value(), timelineId, ACTOR);
    }

    private static String failureDocument(
            DocumentId documentId,
            String timelineId) {
        return """
                documentId: %s
                counter: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  advance:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Trigger Event
                """.formatted(documentId.value(), timelineId, ACTOR);
    }
}
