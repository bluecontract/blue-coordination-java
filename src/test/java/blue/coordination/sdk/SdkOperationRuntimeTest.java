package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused public-SDK operation, outcome, and append-order acceptance. */
final class SdkOperationRuntimeTest {
    private static final DocumentId COUNTER_ID = DocumentId.of("counter");
    private static final String COUNTER = """
            documentId: counter
            name: Counter
            counter: 0
            contracts:
              aliceChannel:
                type: Coordination/Timeline Channel
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: alice
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
              increment:
                type: Coordination/Sequential Workflow Operation
                channel: aliceChannel
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
            """;

    @Test
    void targetedOperationAppliesAndReportsExactWorkOrder() {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);

            EntryResult result = increment(blue, counter, alice, 3).execute();

            assertEquals(EntryDisposition.APPLIED, result.disposition());
            assertTrue(result.applied());
            assertEquals(3L, counter.snapshot().longAt("/counter"));
            assertEquals(List.of(COUNTER_ID),
                    result.stats().documentStepOrder());
            assertEquals(1, result.closures().size());
            assertEquals(1, result.closures().get(0).changes().size());
        }
    }

    @Test
    void missingTargetAndOperationReturnPreciseRejectedResults() {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);

            EntryResult missingTarget = blue.operations()
                    .on(DocumentId.of("missing"))
                    .from(alice)
                    .call("increment")
                    .through("aliceChannel")
                    .requestYaml("amount: 1")
                    .execute();
            assertEquals(EntryDisposition.REJECTED,
                    missingTarget.disposition());
            assertEquals("TARGET_DOCUMENT_NOT_FOUND",
                    missingTarget.diagnostic().code());

            EntryResult missingOperation = blue.operations()
                    .on(counter)
                    .from(alice)
                    .call("doesNotExist")
                    .through("aliceChannel")
                    .requestYaml("{}")
                    .execute();
            assertEquals(EntryDisposition.REJECTED,
                    missingOperation.disposition());
            assertEquals("OPERATION_NOT_FOUND",
                    missingOperation.diagnostic().code());
        }
    }

    @Test
    void exactTargetCapturedBeforeAnotherCommitReturnsStale() {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);
            OperationCall captured = increment(
                    blue, counter, alice, 10);

            assertTrue(increment(blue, counter, alice, 1)
                    .execute().applied());
            EntryResult stale = captured.execute();

            assertEquals(EntryDisposition.STALE, stale.disposition());
            assertEquals("STALE_TARGET_DOCUMENT",
                    stale.diagnostic().code());
            assertEquals(1L, counter.snapshot().longAt("/counter"));
        }
    }

    @Test
    void submitIsAppendOnlyAndExplicitDrainReturnsSameTypedResult() {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);

            EntryHandle submitted = increment(
                    blue, counter, alice, 4).submit();
            assertEquals(0L, counter.snapshot().longAt("/counter"));

            DrainResult drained = blue.processing().drain();
            EntryResult result = drained.entry(submitted);
            assertTrue(result.applied());
            assertEquals(4L, counter.snapshot().longAt("/counter"));
        }
    }

    @Test
    void validBroadcastWithNoAcceptingOperationIsNoMatch() {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            admitCounter(blue);
            ExactBlueValue event = blue.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: alice
                    timestamp: 1
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                    message:
                      type: Coordination/Operation Request
                      operation: ignored
                      channel: aliceChannel
                      request: {}
                    """);

            EntryResult result = blue.events()
                    .from(alice)
                    .exact(event)
                    .execute();

            assertEquals(EntryDisposition.NO_MATCH,
                    result.disposition());
            assertFalse(result.diagnostic().present());
            assertTrue(result.closures().isEmpty());
        }
    }

    @Test
    void managedDraftAdmissionFailsClosedBeforeAppend() {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);
            ManagedDocumentDraft draft = blue.documents().draft(
                    DocumentId.of("child"),
                    blue.values().yaml("documentId: child\nstate: 1"));
            int entriesBefore = blue.advanced().rawEngine()
                    .metrics().journalEntryCount();

            UnsupportedOperationException failure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> blue.operations()
                            .on(counter)
                            .from(alice)
                            .call("increment")
                            .through("aliceChannel")
                            .request(request -> request.managed(
                                    "child", draft))
                            .expectOccurrence("/child", draft)
                            .execute());

            assertTrue(failure.getMessage().startsWith(
                    "UNSUPPORTED_MANAGED_DRAFT_ADMISSION:"));
            assertEquals(entriesBefore, blue.advanced().rawEngine()
                    .metrics().journalEntryCount());
        }
    }

    private static DocumentHandle admitCounter(BlueCoordination blue) {
        return blue.documents().admit(
                ManagedDocument.yaml(COUNTER_ID, COUNTER)
                        .publicRoot()
                        .fromNow());
    }

    private static OperationCall increment(
            BlueCoordination blue,
            DocumentHandle counter,
            TimelineHandle alice,
            long amount) {
        return blue.operations()
                .on(counter)
                .from(alice)
                .call("increment")
                .through("aliceChannel")
                .requestYaml("amount: " + amount);
    }
}
