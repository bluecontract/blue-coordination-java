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
    void managedRequestAndExpectationMustBeCoherentBeforeAppend() {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);
            ManagedDocumentDraft draft = blue.documents().draft(
                    DocumentId.of("child"),
                    blue.values().yaml("documentId: child\nstate: 1"));
            int entriesBefore = blue.advanced().rawEngine()
                    .metrics().journalEntryCount();

            IllegalArgumentException missingExpectation = assertThrows(
                    IllegalArgumentException.class,
                    () -> blue.operations()
                            .on(counter)
                            .from(alice)
                            .call("increment")
                            .through("aliceChannel")
                            .request(request -> request.managed(
                                    "child", draft))
                            .submit());
            assertTrue(missingExpectation.getMessage().startsWith(
                    "MANAGED_DRAFT_NOT_EXPECTED:"));

            IllegalArgumentException missingRequest = assertThrows(
                    IllegalArgumentException.class,
                    () -> blue.operations()
                            .on(counter)
                            .from(alice)
                            .call("increment")
                            .through("aliceChannel")
                            .requestYaml("{}")
                            .expectOccurrence("/child", draft)
                            .submit());
            assertTrue(missingRequest.getMessage().startsWith(
                    "MANAGED_OCCURRENCE_DRAFT_NOT_REQUESTED:"));
            assertEquals(entriesBefore, blue.advanced().rawEngine()
                    .metrics().journalEntryCount());
        }
    }

    @Test
    void managedDraftOwnershipAndImportPolicyFailBeforeAppend() {
        try (BlueCoordination blue = BlueCoordination.inMemory();
                BlueCoordination foreign = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);
            ExactBlueValue initial = blue.values().yaml(
                    "documentId: child\nstate: 1");
            ManagedDocumentDraft imported = blue.documents().draft(
                    DocumentId.of("child"), initial).atEpoch(4L);
            ManagedDocumentDraft foreignDraft = foreign.documents().draft(
                    DocumentId.of("foreign-child"),
                    foreign.values().yaml(
                            "documentId: foreign-child\nstate: 1"));
            int entriesBefore = blue.advanced().rawEngine()
                    .metrics().journalEntryCount();

            IllegalArgumentException ownerFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> managedCall(
                            blue, counter, alice, foreignDraft).submit());
            assertTrue(ownerFailure.getMessage().startsWith(
                    "MANAGED_DRAFT_OWNER_MISMATCH:"));

            UnsupportedOperationException importFailure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> managedCall(
                            blue, counter, alice, imported).submit());
            assertTrue(importFailure.getMessage().startsWith(
                    "UNSUPPORTED_MANAGED_DRAFT_IMPORT:"));
            assertEquals(entriesBefore, blue.advanced().rawEngine()
                    .metrics().journalEntryCount());
        }
    }

    @Test
    void defaultOccurrencePolicyUsesFinalCallActivationBeforeAppend() {
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
                    () -> managedCall(blue, counter, alice, draft)
                            .activation(ActivationPolicy.importFullHistory())
                            .submit());

            assertTrue(failure.getMessage().startsWith(
                    "UNSUPPORTED_MANAGED_DRAFT_ACTIVATION_POLICY:"));
            assertEquals(entriesBefore, blue.advanced().rawEngine()
                    .metrics().journalEntryCount());
        }
    }

    @Test
    void advancedAuditProjectsRetainedManagedOccurrenceLineage() {
        DocumentId a = DocumentId.of("audit-occurrence-a");
        DocumentId b = DocumentId.of("audit-occurrence-b");
        ManagedClosure closure = ManagedClosure.builder()
                .document("a", a, occurrenceAuditDocument(a))
                .document("b", b, occurrenceAuditDocument(b))
                .bindOccurrence("a", "/peer", "b")
                .bindOccurrence("b", "/peer", "a")
                .publicRoot("a")
                .fromNow()
                .build();

        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            blue.documents().admit(closure);

            assertEquals(new ManagedOccurrenceAudit(a, 1L, true),
                    blue.advanced()
                            .auditManagedOccurrence(b, "/peer")
                            .orElseThrow());
            assertTrue(blue.advanced()
                    .auditManagedOccurrence(b, "/missing")
                    .isEmpty());
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

    private static OperationCall managedCall(
            BlueCoordination blue,
            DocumentHandle target,
            TimelineHandle timeline,
            ManagedDocumentDraft draft) {
        return blue.operations()
                .on(target)
                .from(timeline)
                .call("increment")
                .through("aliceChannel")
                .request(request -> request.managed("child", draft))
                .expectOccurrence("/child", draft);
    }

    private static String occurrenceAuditDocument(DocumentId id) {
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                """.formatted(id.value());
    }
}
