package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
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
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);

            // when
            EntryResult result = increment(blue, counter, alice, 3).execute();

            // then
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
    void agentTimelineAuthorsAgentEvidenceAndMatchesAgentRoute() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle agent = blue.timelines().register(
                    "agent-timeline",
                    "agent-account",
                    TimelineActorKind.AGENT);
            String source = COUNTER
                    .replace("timelineId: alice", "timelineId: agent-timeline")
                    .replace("type: MyOS/Principal Actor",
                            "type: MyOS/MyOS Agent Actor")
                    .replace("accountId: alice", "accountId: agent-account");
            DocumentHandle counter = blue.documents()
                    .admit(ManagedDocument.yaml(COUNTER_ID, source)
                            .publicRoot()
                            .fromNow());

            // when
            EntryResult result = increment(blue, counter, agent, 2).execute();
            TimelineEntrySnapshot entry = blue.advanced()
                    .auditTimelineEntry(result.entry().blueId())
                    .orElseThrow();

            // then
            assertEquals(TimelineActorKind.AGENT, agent.actorKind());
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            assertEquals(2L, counter.snapshot().longAt("/counter"));
            Node actor = NodePathEditor.getOrNull(
                    entry.exact().copyNode(), "/actor");
            assertEquals(
                    "C2Fyt8obT6QR8WWFCPF5G36VXZLwzaM1SLtMKKAp4pwd",
                    actor.getType().getBlueId());
            assertEquals("agent-account", NodePathEditor.getOrNull(
                    entry.exact().copyNode(),
                    "/actor/accountId").getValue());
        }
    }

    @Test
    void timelineAuditReadsCanonicalJournalIncludingRawEngineAppends() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            Timeline rawTimeline = blue.advanced().rawEngine()
                    .registerTimeline("raw-timeline", "raw-actor");

            // when
            TimelineEntry rawEntry = blue.advanced().rawEngine().append(
                    rawTimeline,
                    Operation.yaml("raw-operation", "raw-channel", "{}"));
            TimelineEntrySnapshot audited = blue.advanced()
                    .auditTimelineEntry(rawEntry.blueId())
                    .orElseThrow();

            // then
            assertEquals(rawEntry.blueId(), audited.blueId());
            assertEquals("raw-timeline", audited.timeline().id());
            assertEquals("raw-actor", audited.timeline().accountId());
            assertEquals(TimelineActorKind.PRINCIPAL,
                    audited.timeline().actorKind());
            assertEquals("raw-operation", audited.operation());
            assertEquals("raw-channel", audited.channel());
            assertEquals(List.of(audited),
                    blue.advanced().auditTimeline("raw-timeline"));
            assertEquals(List.of(audited),
                    blue.advanced().auditTimelineEntries());
            assertThrows(UnsupportedOperationException.class,
                    () -> blue.advanced().auditTimelineEntries().clear());
        }
    }

    @Test
    void timelineAuditDistinguishesAbsentAndExactEmptyRequests() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            Timeline timeline = blue.advanced().rawEngine()
                    .registerTimeline("request-audit", "alice");

            // when
            TimelineEntry absent = blue.advanced().rawEngine().append(
                    timeline,
                    Operation.withoutRequest("touch", "ownerChannel"));
            TimelineEntry empty = blue.advanced().rawEngine().append(
                    timeline,
                    Operation.yaml("touch", "ownerChannel", "{}"));
            TimelineEntrySnapshot absentAudit = blue.advanced()
                    .auditTimelineEntry(absent.blueId())
                    .orElseThrow();
            TimelineEntrySnapshot emptyAudit = blue.advanced()
                    .auditTimelineEntry(empty.blueId())
                    .orElseThrow();

            // then
            assertTrue(absentAudit.request().isEmpty());
            assertTrue(emptyAudit.request().isPresent());
            assertEquals(
                    "5ajuwjHoLj33yG5t5UFsJtUb3vnRaJQEMPqSLz6VyoHK",
                    emptyAudit.request().orElseThrow().blueId());
            assertFalse(absentAudit.blueId().equals(emptyAudit.blueId()));
        }
    }

    @Test
    void missingTargetAndOperationReturnPreciseRejectedResults() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);

            // when
            EntryResult missingTarget = blue.operations()
                    .on(DocumentId.of("missing"))
                    .from(alice)
                    .call("increment")
                    .through("aliceChannel")
                    .requestYaml("amount: 1")
                    .execute();
            EntryResult missingOperation = blue.operations()
                    .on(counter)
                    .from(alice)
                    .call("doesNotExist")
                    .through("aliceChannel")
                    .requestYaml("{}")
                    .execute();

            // then
            assertEquals(EntryDisposition.REJECTED,
                    missingTarget.disposition());
            assertEquals("TARGET_DOCUMENT_NOT_FOUND",
                    missingTarget.diagnostic().code());
            assertEquals(EntryDisposition.REJECTED,
                    missingOperation.disposition());
            assertEquals("OPERATION_NOT_FOUND",
                    missingOperation.diagnostic().code());
        }
    }

    @Test
    void exactTargetCapturedBeforeAnotherCommitReturnsStale() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);
            OperationCall captured = increment(
                    blue, counter, alice, 10);

            // when
            boolean interveningApplied = increment(blue, counter, alice, 1)
                    .execute().applied();
            EntryResult stale = captured.execute();

            // then
            assertTrue(interveningApplied);
            assertEquals(EntryDisposition.STALE, stale.disposition());
            assertEquals("STALE_TARGET_DOCUMENT",
                    stale.diagnostic().code());
            assertEquals(1L, counter.snapshot().longAt("/counter"));
        }
    }

    @Test
    void submitIsAppendOnlyAndExplicitDrainReturnsSameTypedResult() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);

            // when
            EntryHandle submitted = increment(
                    blue, counter, alice, 4).submit();
            long counterBeforeDrain = counter.snapshot().longAt("/counter");
            DrainResult drained = blue.processing().drain();
            EntryResult result = drained.entry(submitted);

            // then
            assertEquals(0L, counterBeforeDrain);
            assertTrue(result.applied());
            assertEquals(4L, counter.snapshot().longAt("/counter"));
        }
    }

    @Test
    void validBroadcastWithNoAcceptingOperationIsNoMatch() {
        // given
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

            // when
            EntryResult result = blue.events()
                    .from(alice)
                    .exact(event)
                    .execute();

            // then
            assertEquals(EntryDisposition.NO_MATCH,
                    result.disposition());
            assertFalse(result.diagnostic().present());
            assertTrue(result.closures().isEmpty());
        }
    }

    @Test
    void managedRequestAndExpectationMustBeCoherentBeforeAppend() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);
            ManagedDocumentDraft draft = blue.documents().draft(
                    DocumentId.of("child"),
                    blue.values().yaml("documentId: child\nstate: 1"));
            int entriesBefore = blue.advanced().rawEngine()
                    .metrics().journalEntryCount();

            // when
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

            // then
            assertTrue(missingExpectation.getMessage().startsWith(
                    "MANAGED_DRAFT_NOT_EXPECTED:"));
            assertTrue(missingRequest.getMessage().startsWith(
                    "MANAGED_OCCURRENCE_DRAFT_NOT_REQUESTED:"));
            assertEquals(entriesBefore, blue.advanced().rawEngine()
                    .metrics().journalEntryCount());
        }
    }

    @Test
    void managedDraftOwnershipAndImportPolicyFailBeforeAppend() {
        // given
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

            // when
            IllegalArgumentException ownerFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> managedCall(
                            blue, counter, alice, foreignDraft).submit());
            UnsupportedOperationException importFailure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> managedCall(
                            blue, counter, alice, imported).submit());

            // then
            assertTrue(ownerFailure.getMessage().startsWith(
                    "MANAGED_DRAFT_OWNER_MISMATCH:"));
            assertTrue(importFailure.getMessage().startsWith(
                    "UNSUPPORTED_MANAGED_DRAFT_IMPORT:"));
            assertEquals(entriesBefore, blue.advanced().rawEngine()
                    .metrics().journalEntryCount());
        }
    }

    @Test
    void defaultOccurrencePolicyUsesFinalCallActivationBeforeAppend() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle alice = blue.timelines().local("alice");
            DocumentHandle counter = admitCounter(blue);
            ManagedDocumentDraft draft = blue.documents().draft(
                    DocumentId.of("child"),
                    blue.values().yaml("documentId: child\nstate: 1"));
            int entriesBefore = blue.advanced().rawEngine()
                    .metrics().journalEntryCount();

            // when
            UnsupportedOperationException failure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> managedCall(blue, counter, alice, draft)
                            .activation(ActivationPolicy.importFullHistory())
                            .submit());

            // then
            assertTrue(failure.getMessage().startsWith(
                    "UNSUPPORTED_MANAGED_DRAFT_ACTIVATION_POLICY:"));
            assertEquals(entriesBefore, blue.advanced().rawEngine()
                    .metrics().journalEntryCount());
        }
    }

    @Test
    void advancedAuditProjectsRetainedManagedOccurrenceLineage() {
        // given
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
            // when
            blue.documents().admit(closure);
            ManagedOccurrenceAudit occurrence = blue.advanced()
                    .auditManagedOccurrence(b, "/peer")
                    .orElseThrow();
            boolean missing = blue.advanced()
                    .auditManagedOccurrence(b, "/missing")
                    .isEmpty();

            // then
            assertEquals(new ManagedOccurrenceAudit(a, 1L, true), occurrence);
            assertTrue(missing);
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
