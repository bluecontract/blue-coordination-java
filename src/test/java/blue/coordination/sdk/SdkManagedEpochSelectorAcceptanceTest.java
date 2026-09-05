package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused operation-triggered exact managed-epoch selector acceptance.
 *
 * <p>Ordinary SDK processing retains distinct exact epoch states, so this
 * public acceptance selects one unique historical epoch. Duplicate-BlueId
 * ambiguity and occurrence-specific typed selection are seeded and proved at
 * the resolver boundary by
 * {@code ManagedOccurrenceResolverTest.repeatedHistoricalBlueIdIsAmbiguous}
 * and {@code exactSelectorChoosesOneRepeatedHistoricalEpoch}; that suite
 * retains both fail-closed ambiguity and exact duplicate-selection coverage.</p>
 */
final class SdkManagedEpochSelectorAcceptanceTest {
    private static final String TIMELINE = "sdk/managed-epoch-selector";
    private static final String ACTOR = "selector-actor";
    private static final DocumentId HOST = DocumentId.of("selector-host");
    private static final DocumentId SOURCE = DocumentId.of("selector-source");

    @Test
    void operationSelectorChoosesExactHistoricalSourceEpoch() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            TimelineHandle timeline = coordination.timelines().register(
                    TIMELINE, ACTOR);
            ClosureHandle closure = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document("host", HOST, hostDocument())
                            .document("source", SOURCE, sourceDocument())
                            .publicRoot("host")
                            .publicRoot("source")
                            .fromNow()
                            .build());
            DocumentHandle host = closure.document("host");
            DocumentHandle source = closure.document("source");

            assertTrue(setState(
                    coordination, source, timeline, 1L).execute().applied());
            assertTrue(setState(
                    coordination, source, timeline, 2L).execute().applied());
            ExactBlueValue selected = source.history().get(1).after();

            // when
            EntryHandle retarget = coordination.operations()
                    .on(host)
                    .from(timeline)
                    .call("retarget")
                    .through("hostChannel")
                    .request(request -> request.exact("child", selected))
                    .selectManagedEpoch(
                            "/children/selected",
                            SOURCE,
                            1L,
                            selected.blueId())
                    .submit();
            DrainResult admitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then
            EntryResult retargetResult = admitted.entry(retarget);
            assertEquals(EntryDisposition.APPLIED,
                    retargetResult.disposition(),
                    retargetResult.toString());
            assertTrue(admitted.managedEpochApplications().isEmpty());
            ManagedOccurrenceAudit occurrence = coordination.advanced()
                    .auditManagedOccurrence(HOST, "/children/selected")
                    .orElseThrow();
            assertEquals(SOURCE, occurrence.targetDocumentId());
            assertFalse(occurrence.active());
            List<ManagedOccurrenceCatchUpPlan> plans = coordination
                    .advanced().auditManagedCatchUpPlans(HOST);
            assertEquals(1, plans.size());
            ManagedOccurrenceCatchUpPlan pending = plans.get(0);
            assertEquals(SOURCE, pending.sourceDocumentId());
            assertEquals(1L, pending.admittedSourceEpoch());
            assertEquals(selected.blueId(), pending.admittedSourceBlueId());
            assertEquals(2L, pending.nextSourceEpoch());
            assertEquals(2L, pending.requiredThroughSourceEpoch());

            DrainResult caughtUp = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(1, caughtUp.managedEpochApplications().size());
            assertEquals(coordination.advanced()
                            .auditManagedEpoch(SOURCE, 2L)
                            .orElseThrow().receiptIdentity(),
                    caughtUp.managedEpochApplications().get(0)
                            .sourceReceiptIdentity());
            ManagedOccurrenceCatchUpPlan complete = coordination.advanced()
                    .auditManagedCatchUpPlan(pending.planIdentity())
                    .orElseThrow();
            assertEquals(ManagedCatchUpStatus.COMPLETE, complete.status());
            assertEquals(3L, complete.nextSourceEpoch());
            assertEquals(source.snapshot().blueId(),
                    host.snapshot().valueAt(
                            "/children/selected").blueId());
            assertTrue(coordination.advanced()
                    .auditManagedOccurrence(HOST, "/children/selected")
                    .orElseThrow().active());
        }
    }

    private static OperationCall setState(
            BlueCoordination coordination,
            DocumentHandle source,
            TimelineHandle timeline,
            long value) {
        return coordination.operations()
                .on(source)
                .from(timeline)
                .call("setState")
                .through("sourceChannel")
                .requestYaml("desiredState: " + value);
    }

    private static String hostDocument() {
        return """
                documentId: selector-host
                children: {}
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /children
                  hostChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: sdk/managed-epoch-selector
                    actor:
                      type: MyOS/Principal Actor
                      accountId: selector-actor
                  retarget:
                    type: Coordination/Sequential Workflow Operation
                    channel: hostChannel
                    request:
                      child: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /children/selected
                              val: {$binding: event/message/request/child}
                          - $return: true
                """;
    }

    private static String sourceDocument() {
        return """
                documentId: selector-source
                state: 0
                contracts:
                  sourceChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: sdk/managed-epoch-selector
                    actor:
                      type: MyOS/Principal Actor
                      accountId: selector-actor
                  setState:
                    type: Coordination/Sequential Workflow Operation
                    channel: sourceChannel
                    request:
                      desiredState: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /state
                              val: {$binding: event/message/request/desiredState}
                          - $return: true
                """;
    }

}
