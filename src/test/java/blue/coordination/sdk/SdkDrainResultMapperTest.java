package blue.coordination.sdk;

import blue.coordination.api.ContractsClosureDispatchAttempt;
import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ExactNodeDemand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public result mapping for typed suspended-resource evidence. */
final class SdkDrainResultMapperTest {

    @Test
    void publicOperationReturnsTypedManagedOccurrenceBlocker() {
        // given
        DocumentId source = DocumentId.of("sdk-demand-host");
        String timelineId = "sdk/typed-occurrence-demand";
        try (BlueCoordination coordination = LegacyContracts10TestProfile.builder().build();
                BlueCoordination foreign = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, "alice");
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(source, """
                            documentId: sdk-demand-host
                            children: {}
                            contracts:
                              embedded:
                                type: Process Embedded
                                collectionPaths:
                                  - /children
                              ownerChannel:
                                type: Coordination/Timeline Channel
                                timeline:
                                  type: MyOS/MyOS Timeline
                                  timelineId: sdk/typed-occurrence-demand
                                actor:
                                  type: MyOS/Principal Actor
                                  accountId: alice
                              attach:
                                type: Coordination/Sequential Workflow Operation
                                channel: ownerChannel
                                request:
                                  child: {}
                                steps:
                                  - type: Coordination/Compute
                                    do:
                                      - $appendChange:
                                          op: add
                                          path: /children/one
                                          val: {$binding: event/message/request/child}
                                      - $return: true
                            """)
                            .publicRoot()
                            .fromNow());
            String unavailableBlueId = foreign.values().yaml(
                    "state: unavailable-here").blueId();
            String childYaml = """
                    documentId: ignored-authored-child-id
                    peer:
                      blueId: %s
                    contracts:
                      embedded:
                        type: Process Embedded
                        paths:
                          - /peer
                    """.formatted(unavailableBlueId);
            DocumentId childId = DocumentId.of(
                    coordination.values().yaml(childYaml).blueId());
            String before = host.snapshot().blueId();

            // when
            EntryResult result = coordination.operations()
                    .on(host)
                    .from(timeline)
                    .call("attach")
                    .through("ownerChannel")
                    .requestYaml("child:\n" + childYaml.indent(2))
                    .execute();

            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES,
                    result.disposition(), result.toString());
            assertEquals(1, result.closures().size());
            ClosureResult closure = result.closures().get(0);
            assertEquals(2L, closure.processorAttemptCount());
            assertEquals(1L, closure.automaticRetryCount());
            assertEquals(1, closure.resourceDemands().size());
            ClosureResult.ResourceDemand demand =
                    closure.resourceDemands().get(0);
            assertEquals("EXACT_NODE", demand.kind());
            assertEquals(unavailableBlueId, demand.blueId());
            assertEquals(childId, demand.sourceDocumentId());
            assertEquals("/peer", demand.sourcePath());
            assertEquals(
                    Optional.of(ClosureResult.ManagedResolutionStatus
                            .MISSING_EXACT_CONTENT),
                    demand.managedResolutionStatus());
            assertTrue(demand.managedResolutionDiagnostic().isPresent());
            assertEquals(before, host.snapshot().blueId());
            assertEquals(0L, host.snapshot().epoch());
        }
    }

    @Test
    void rootedOperationReportsSourceHistoryBeforeNestedExactResource() {
        // given
        DocumentId source = DocumentId.of("sdk-demand-host");
        String timelineId = "sdk/typed-occurrence-demand";
        try (BlueCoordination coordination = BlueCoordination.inMemory();
                BlueCoordination foreign = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, "alice");
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(source, """
                            documentId: sdk-demand-host
                            children: {}
                            contracts:
                              embedded:
                                type: Process Embedded
                                collectionPaths:
                                  - /children
                              ownerChannel:
                                type: Coordination/Timeline Channel
                                timeline:
                                  type: MyOS/MyOS Timeline
                                  timelineId: sdk/typed-occurrence-demand
                                actor:
                                  type: MyOS/Principal Actor
                                  accountId: alice
                              attach:
                                type: Coordination/Sequential Workflow Operation
                                channel: ownerChannel
                                request:
                                  child: {}
                                steps:
                                  - type: Coordination/Compute
                                    do:
                                      - $appendChange:
                                          op: add
                                          path: /children/one
                                          val: {$binding: event/message/request/child}
                                      - $return: true
                            """)
                            .publicRoot()
                            .fromNow());
            String unavailableBlueId = foreign.values().yaml(
                    "state: unavailable-here").blueId();
            String childYaml = """
                    documentId: ignored-authored-child-id
                    peer:
                      blueId: %s
                    contracts:
                      embedded:
                        type: Process Embedded
                        paths:
                          - /peer
                    """.formatted(unavailableBlueId);
            DocumentId childId = DocumentId.of(
                    coordination.values().yaml(childYaml).blueId());
            String before = host.snapshot().blueId();

            // when
            EntryResult result = coordination.operations()
                    .on(host)
                    .from(timeline)
                    .call("attach")
                    .through("ownerChannel")
                    .requestYaml("child:\n" + childYaml.indent(2))
                    .execute();

            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES,
                    result.disposition(), result.toString());
            assertEquals(1, result.closures().size());
            ClosureResult closure = result.closures().get(0);
            assertEquals(1L, closure.processorAttemptCount());
            assertEquals(0L, closure.automaticRetryCount());
            assertEquals(1, closure.resourceDemands().size());
            var demand = closure.resourceDemands().get(0);
            assertEquals("MANAGED_OCCURRENCE_EVIDENCE", demand.kind());
            assertEquals(childId.value(), demand.blueId());
            assertEquals(source, demand.sourceDocumentId());
            assertEquals("/children/one", demand.sourcePath());
            assertEquals(Optional.of(ClosureResult.ManagedResolutionStatus.UNPROVEN_MANAGED_HISTORY),
                    demand.managedResolutionStatus());
            assertEquals(0L, result.stats().committedTransitions());
            assertTrue(result.publicEvents().isEmpty());
            assertTrue(demand.managedResolutionDiagnostic().isPresent());
            assertEquals(before, host.snapshot().blueId());
            assertEquals(0L, host.snapshot().epoch());
        }
    }

    @Test
    void mapsRetainedResolutionStatusWithoutInterpretingDiagnosticText() {
        // given
        Object owner = new Object();
        try (SdkCoordinationRuntime runtime = SdkCoordinationRuntime.create(
                owner, null, null, ExactNodeProvider.empty(), false)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) runtime.engine();
            Timeline timeline = engine.registerTimeline(
                    "sdk/typed-resolution-status", "alice");
            TimelineEntry entry = engine.append(
                    timeline,
                    Operation.yaml("attach", "ownerChannel", "{}"));
            DocumentId source = DocumentId.of("sdk-resolution-source");
            String blueId = "sha256:" + "b".repeat(64);
            ExactNodeDemand demand = ExactNodeDemand.derived(
                    blueId,
                    new blue.language.processor.closure.DocumentId(
                            source.value()),
                    "/historical-peer");
            ClosureAttemptResult suspended =
                    ClosureAttemptResult.needsResources(List.of(demand));
            String opaqueDiagnostic = "opaque wording with no enum token";
            ContractsClosureDispatchAttempt retained =
                    new ContractsClosureDispatchAttempt(
                            entry.blueId(),
                            List.of(source),
                            suspended,
                            false,
                            null,
                            false,
                            0L,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(new ContractsClosureDispatchAttempt
                                    .ManagedOccurrenceResolutionIssue(
                                            demand.demandIdentity(),
                                            ContractsClosureDispatchAttempt
                                                    .ResolutionStatus
                                                    .AMBIGUOUS_MANAGED_EPOCH,
                                            opaqueDiagnostic)));
            ProcessingDrainReceipt receipt = new ProcessingDrainReceipt(
                    List.of(entry),
                    Map.of(),
                    Map.of(entry.blueId(), List.of(retained)),
                    null,
                    false,
                    false,
                    0L,
                    0L);

            // when
            ClosureResult.ResourceDemand projected =
                    new SdkDrainResultMapper(runtime, engine)
                            .map(receipt)
                            .entries().get(0)
                            .closures().get(0)
                            .resourceDemands().get(0);

            // then
            assertEquals(
                    Optional.of(ClosureResult.ManagedResolutionStatus
                            .AMBIGUOUS_MANAGED_EPOCH),
                    projected.managedResolutionStatus());
            assertEquals(Optional.of(opaqueDiagnostic),
                    projected.managedResolutionDiagnostic());
        }
    }

    @Test
    void suspendedClosureExposesTypedDemandAndAutomaticAttemptCount() {
        // given
        Object owner = new Object();
        try (SdkCoordinationRuntime runtime = SdkCoordinationRuntime.create(
                owner, null, null, ExactNodeProvider.empty(), false)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) runtime.engine();
            Timeline timeline = engine.registerTimeline(
                    "sdk/typed-demand", "alice");
            TimelineEntry entry = engine.append(
                    timeline,
                    Operation.yaml("attach", "ownerChannel", "{}"));
            DocumentId source = DocumentId.of("sdk-demand-source");
            String blueId = "sha256:" + "a".repeat(64);
            ExactNodeDemand demand = ExactNodeDemand.derived(
                    blueId,
                    new blue.language.processor.closure.DocumentId(
                            source.value()),
                    "/peer");
            ClosureAttemptResult suspended =
                    ClosureAttemptResult.needsResources(List.of(demand));
            ContractsClosureDispatchAttempt retained =
                    new ContractsClosureDispatchAttempt(
                            entry.blueId(),
                            List.of(source),
                            suspended,
                            false,
                            null,
                            false,
                            2L);
            ProcessingDrainReceipt receipt = new ProcessingDrainReceipt(
                    List.of(entry),
                    Map.of(),
                    Map.of(entry.blueId(), List.of(retained)),
                    null,
                    false,
                    false,
                    0L,
                    0L);

            // when
            DrainResult result = new SdkDrainResultMapper(runtime, engine)
                    .map(receipt);

            // then
            ClosureResult closure = result.entries().get(0)
                    .closures().get(0);
            assertEquals(EntryDisposition.NEEDS_RESOURCES,
                    closure.disposition());
            assertEquals(3L, closure.processorAttemptCount());
            assertEquals(2L, closure.automaticRetryCount());
            assertEquals(List.of(new ClosureResult.ResourceDemand(
                            demand.kind().name(),
                            demand.demandIdentity(),
                            blueId,
                            source,
                            "/peer")),
                    closure.resourceDemands());
            assertEquals(blueId, closure.diagnostic().details()
                    .get("requiredExactBlueIds"));
        }
    }
}
