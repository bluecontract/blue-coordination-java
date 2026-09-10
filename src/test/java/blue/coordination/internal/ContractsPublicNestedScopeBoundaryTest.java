package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentSnapshot;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public characterization of ordinary and Contracts nested-scope routing. */
@ExtendWith(CyclicTopologyIdentityEvidenceTest.CollectionExtension.class)
final class ContractsPublicNestedScopeBoundaryTest {
    private static final DocumentId DOCUMENT =
            DocumentId.of("nested-scope-boundary");
    private static final DocumentId CHILD =
            DocumentId.of("nested-scope-child");
    private static final DocumentId PEER =
            DocumentId.of("nested-scope-peer");
    private static final String ROOT_TIMELINE =
            "nested-scope-boundary/root";
    private static final String NESTED_TIMELINE =
            "nested-scope-boundary/nested";
    private static final String ACTOR = "nested-scope-owner";
    private static final long T0 = 1_950_000_000_000_000L;

    @Test
    void ordinaryPublicEngineExecutesTheNestedScopeNormally()
            throws Exception {
        // given

        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            engine.startDocument(DOCUMENT, ordinaryNestedDocument());
            String beforeBlueId = null;
            Long beforeEpoch = null;
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                beforeBlueId = engine.document(DOCUMENT).blueId();
                beforeEpoch = engine.document(DOCUMENT).epoch();
            }

            Timeline nested = engine.registerTimeline(
                    NESTED_TIMELINE, ACTOR);

            // when
            TimelineEntry entry = engine.appendAt(
                    nested,
                    Operation.yaml(
                            "nestedTouch", "nestedChannel", "amount: 2"),
                    T0);

            // then
            assertEquals(1, engine.routeTargetCount(entry));
            ProcessingDrainReceipt drained = engine.drain();
            assertTrue(drained.quiescent());
            assertEquals(List.of(CHILD, DOCUMENT), drained.outcomesFor(
                            entry.blueId()).stream()
                    .map(outcome -> outcome.documentId())
                    .toList());
            assertEquals(2L, integer(
                    engine.document(DOCUMENT), "/nested/count"));
            assertEquals(0L, integer(
                    engine.document(DOCUMENT), "/rootCount"));
            assertEquals(2L, engine.document(DOCUMENT).epoch(),
                    "child execution and containing-document reaction commit");
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.captureHost(
                        "P7.ordinary-nested-scope",
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "entryBlueId", entry.blueId(),
                                "routeTargetCount", 1,
                                "outcomeOrder", drained.outcomesFor(
                                        entry.blueId()).stream()
                                        .map(outcome -> outcome.documentId())
                                        .toList(),
                                "beforeBlueId", beforeBlueId,
                                "afterBlueId",
                                engine.document(DOCUMENT).blueId(),
                                "beforeEpoch", beforeEpoch,
                                "afterEpoch",
                                engine.document(DOCUMENT).epoch(),
                                "nestedCount", integer(
                                        engine.document(DOCUMENT),
                                        "/nested/count"),
                                "rootCount", integer(
                                        engine.document(DOCUMENT),
                                        "/rootCount")));
            }
        }
    }

    @Test
    void contractsDirectSeedsAndManagedStepsRemainPreciselyRootOnly()
            throws Exception {
        // given

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(
                             LegacyContracts10TestProfile.configuration(
                                     Set.of(DOCUMENT)))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            ContractsClosureAdmissionReceipt admitted =
                    new Contracts10ScenarioBuilder(engine)
                            .document(DOCUMENT, nestedDocument())
                            .document(PEER, peerDocument())
                            .processEmbeddedPath(DOCUMENT, "/peer", PEER)
                            .processEmbeddedPath(PEER, "/owner", DOCUMENT)
                            .publicRoot(DOCUMENT)
                            .expectedComponent(DOCUMENT, PEER)
                            .admissionLabel(
                                    "coordination-nested-root-boundary")
                            .admitTo(publicEngine)
                            .admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertEquals(List.of(DOCUMENT, PEER), admitted.documentIds());
            assertEquals(ComponentKind.CYCLIC, admitted.attempt()
                    .processResult().resultingComponents().get(0).kind());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P7.cyclic-root-only-admission",
                        engine,
                        admitted.attempt().processResult(),
                        null,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "admissionPublicationIdentity",
                                admitted.publicationIdentity()));
            }
            DocumentSnapshot snapshot = publicEngine.document(DOCUMENT);
            assertTrue(snapshot.routingDefinitions().stream()
                    .anyMatch(definition -> definition.startsWith(
                            "rootTouch|rootChannel|")));
            assertFalse(snapshot.routingDefinitions().stream()
                    .anyMatch(definition -> definition.startsWith(
                            "nestedTouch|nestedChannel|")),
                    "the Contracts 1.0 managed route surface is Root-only");

            Timeline nested = publicEngine.registerTimeline(
                    NESTED_TIMELINE, ACTOR);
            TimelineEntry nestedEntry = publicEngine.appendAt(
                    nested,
                    Operation.yaml(
                            "nestedTouch", "nestedChannel", "amount: 2"),
                    T0);
            assertEquals(0, publicEngine.routeTargetCount(nestedEntry));
            ProcessingDrainReceipt nestedDrain = publicEngine.drain();
            assertTrue(nestedDrain.outcomesFor(
                    nestedEntry.blueId()).isEmpty());
            assertTrue(engine.contractsClosureAdapter()
                    .lastExecutionEvidence().isEmpty(),
                    "a nested legacy route must not become a closure seed");
            assertEquals(0L, integer(
                    publicEngine.document(DOCUMENT), "/nested/count"));

            Timeline root = publicEngine.registerTimeline(
                    ROOT_TIMELINE, ACTOR);
            TimelineEntry rootEntry = publicEngine.appendAt(
                    root,
                    Operation.yaml(
                            "rootTouch", "rootChannel", "amount: 1"),
                    T0 + 1L);
            assertEquals(1, publicEngine.routeTargetCount(rootEntry));
            ProcessingDrainReceipt rootDrain = publicEngine.drain();
            assertEquals(List.of(DOCUMENT, PEER), rootDrain.outcomesFor(
                            rootEntry.blueId()).stream()
                    .map(outcome -> outcome.documentId())
                    .toList());
            assertEquals(1L, integer(
                    publicEngine.document(DOCUMENT), "/rootCount"));
            assertEquals(0L, integer(
                    publicEngine.document(DOCUMENT), "/nested/count"));

            ClosureImplementationEvidence evidence = engine
                    .contractsClosureAdapter()
                    .lastExecutionEvidence().orElseThrow();
            assertTrue(evidence.complete());
            assertFalse(evidence.documentStepTrace().isEmpty());
            evidence.documentStepTrace().forEach(step -> {
                assertEquals(step.targetDocumentId(),
                        step.executionRootDocumentId());
                assertEquals(DOCUMENT.value(),
                        step.targetDocumentId().value());
                assertEquals("/", step.scopePath());
                assertEquals("ISOLATED_DOCUMENT", step.executionMode());
                assertTrue(step.ambientContainingDocumentIds().isEmpty());
            });
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.captureLatest(
                        "P7.cyclic-root-only-operation",
                        engine,
                        rootDrain,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "nestedEntryBlueId", nestedEntry.blueId(),
                                "nestedRouteTargetCount", 0,
                                "nestedOutcomeCount",
                                nestedDrain.outcomesFor(
                                        nestedEntry.blueId()).size(),
                                "rootEntryBlueId", rootEntry.blueId(),
                                "rootRouteTargetCount", 1,
                                "rootOutcomeOrder", rootDrain.outcomesFor(
                                        rootEntry.blueId()).stream()
                                        .map(outcome -> outcome.documentId())
                                        .toList(),
                                "workScopePaths",
                                evidence.documentStepTrace().stream()
                                        .map(step -> step.scopePath())
                                        .toList()));
            }
        }
    }

    private static String nestedDocument() {
        return """
                documentId: nested-scope-boundary
                rootCount: 0
                nested:
                  documentId: nested-scope-child
                  count: 0
                  contracts:
                    nestedChannel:
                      type: Coordination/Timeline Channel
                      timeline:
                        type: MyOS/MyOS Timeline
                        timelineId: nested-scope-boundary/nested
                      actor:
                        type: MyOS/Principal Actor
                        accountId: nested-scope-owner
                    nestedTouch:
                      type: Coordination/Sequential Workflow Operation
                      channel: nestedChannel
                      request:
                        amount: {type: Integer}
                      steps:
                        - type: Coordination/Compute
                          do:
                            - $appendChange:
                                op: replace
                                path: /count
                                val:
                                  $add:
                                    - $document: /count
                                    - $binding: event/message/request/amount
                            - $return: true
                contracts:
                  rootChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: nested-scope-boundary/root
                    actor:
                      type: MyOS/Principal Actor
                      accountId: nested-scope-owner
                  rootTouch:
                    type: Coordination/Sequential Workflow Operation
                    channel: rootChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /rootCount
                              val:
                                $add:
                                  - $document: /rootCount
                                  - $binding: event/message/request/amount
                          - $return: true
                """;
    }

    private static String peerDocument() {
        return """
                documentId: nested-scope-peer
                """;
    }

    private static String ordinaryNestedDocument() {
        String rootContracts = """
                contracts:
                  coordinationEmbeddedChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: coordination/internal/nested-scope-boundary
                    actor:
                      type: MyOS/Principal Actor
                      accountId: coordination
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - /nested
                  coordinationApplyEmbeddedRevision:
                    type: Coordination/Sequential Workflow Operation
                    channel: coordinationEmbeddedChannel
                    request:
                      occurrencePath: {type: Text}
                      childDocumentId: {type: Text}
                      childEpoch: {type: Integer}
                      after:
                        documentId: {type: Text}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /nested
                              val: {$binding: event/message/request/after}
                          - $return: true
                  rootChannel:
                """.formatted(RuntimeBlueIds.PROCESS_EMBEDDED)
                .stripTrailing();
        return nestedDocument().replace(
                "contracts:\n  rootChannel:", rootContracts);
    }

    private static long integer(
            DocumentSnapshot document,
            String pointer) {
        Object value = document.valueAt(pointer).copyNode().getValue();
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        return ((Number) value).longValue();
    }

}
