package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public Contracts proof for branching collection-backed cyclic topology. */
final class ContractsPublicBranchingCollectionCycleTest {
    private static final long ENTRY_TIME = 2_100_000_000_000_001L;
    private static final int UNRELATED_ADMISSION_BATCH_SIZE = 25;
    private static final BranchingIds BRANCHING = new BranchingIds(
            DocumentId.of("branching-a"),
            DocumentId.of("branching-b1"),
            DocumentId.of("branching-b2"),
            DocumentId.of("branching-c1"),
            DocumentId.of("branching-c2"));

    @Test
    void sharedAnchorCollectionCycleConvergesOnceInCanonicalOrder() {
        // given

        BranchingRun baseline = runBranching(
                BranchingVariant.BASELINE, 0);

        // when
        BranchingRun reversed = runBranching(
                BranchingVariant.REVERSED_MATERIALIZED, 0);

        // then
        assertEquals(baseline.semantic(), reversed.semantic());
        assertEquals(List.of(
                        BRANCHING.a().value(),
                        BRANCHING.c1().value(),
                        BRANCHING.b1().value(),
                        BRANCHING.a().value(),
                        BRANCHING.c2().value(),
                        BRANCHING.b2().value(),
                        BRANCHING.a().value()),
                baseline.semantic().workOrder());
        assertEquals(7, baseline.semantic().workIds().size());
        assertEquals(7,
                Set.copyOf(baseline.semantic().workIds()).size());
        assertEquals(List.of(
                        "branch-start-1",
                        "branch-ack",
                        "branch-start-2",
                        "branch-ack",
                        "branching-done"),
                baseline.semantic().publicEventKinds());
        assertEquals(5, baseline.semantic().publicEventBlueIds().size());
        assertEquals(
                baseline.semantic().publicEventBlueIds().get(1),
                baseline.semantic().publicEventBlueIds().get(3));
        assertNotEquals(
                baseline.semantic().publicEventOccurrenceIds().get(1),
                baseline.semantic().publicEventOccurrenceIds().get(3));
        assertEquals(5, Set.copyOf(
                baseline.semantic().publicEventOccurrenceIds()).size());
        assertEquals(5L, baseline.drain().committedProcessTransitions());
        assertEquals(1, baseline.routeTargetCount());
        assertEquals(1L, baseline.journalEntriesAdded());
        assertEquals(Set.of(
                        BRANCHING.a(),
                        BRANCHING.b1(),
                        BRANCHING.b2(),
                        BRANCHING.c1(),
                        BRANCHING.c2()),
                baseline.changedDocuments());
        assertEquals("done", baseline.finalPhases().get(BRANCHING.a()));
        assertEquals("contributed", baseline.finalPhases().get(
                BRANCHING.b1()));
        assertEquals("contributed", baseline.finalPhases().get(
                BRANCHING.b2()));
        assertEquals("observed", baseline.finalPhases().get(
                BRANCHING.c1()));
        assertEquals("observed", baseline.finalPhases().get(
                BRANCHING.c2()));
        assertEquals("done", baseline.branchStates().get("branch1"));
        assertEquals("done", baseline.branchStates().get("branch2"));
        assertTrue(baseline.semantic().totalGas() > 0L);
    }

    @Test
    void oneThousandUnrelatedDocumentsKeepCaptureLocalAndExposeGlobalBlocker() {
        // given

        BranchingRun base = runBranching(BranchingVariant.BASELINE, 0);

        // when
        BranchingRun withUnrelated = runBranching(
                BranchingVariant.BASELINE, 1_000);

        // then
        assertEquals(base.semantic(), withUnrelated.semantic());
        assertEquals(0L, withUnrelated.unrelatedDocumentOpens());
        assertEquals(0L, withUnrelated.unrelatedDocumentSteps());
        assertEquals(0L, withUnrelated.unrelatedMemberFinalizations());
        assertEquals(0L, withUnrelated.fullPublicationHeadSnapshots());
        assertEquals(0L, withUnrelated.unrelatedDocumentReads());
        assertEquals(1, withUnrelated.processResult()
                .resultingComponents().size());
        assertEquals(Set.of(
                        "branching-a",
                        "branching-b1",
                        "branching-b2",
                        "branching-c1",
                        "branching-c2"),
                withUnrelated.processResult()
                .resultingComponents().get(0)
                .orderedMemberDocumentIds().stream()
                .map(documentId -> documentId.value())
                .collect(Collectors.toSet()));
        assertEquals(1_005, withUnrelated.documentCount());
        assertEquals(5L, withUnrelated.drain()
                .committedProcessTransitions());
        assertEquals(1L, withUnrelated.journalEntriesAdded());
        assertEquals(
                withUnrelated.semantic().workIds().size(),
                Set.copyOf(withUnrelated.semantic().workIds()).size());

        StructuralMetrics structural = withUnrelated.structuralMetrics();
        assertEquals(1L, structural.directRouteSnapshots());
        assertEquals(0L, structural.directRouteRevalidationSnapshots());
        assertEquals(1L, structural.directDeliveriesSelected());
        assertEquals(1L, structural.planConstructions());
        assertEquals(1L, structural.cohortsSelected());
        assertEquals(1L, structural.topologySnapshots());
        // Planning and atomic publication each capture the five-head,
        // one-component closure; typed receipt lookup adds no third snapshot.
        assertEquals(2L * branchingDocuments().size(),
                structural.headsCaptured());
        assertEquals(5L, structural.documentOpens());
        assertEquals(0L, structural.unrelatedDocumentOpens());
        assertEquals(2L, structural.componentStatesCaptured());
        assertEquals(1L, structural.componentStatesRead());
        assertEquals(1L, structural.requestSourcesParsed());
        assertEquals(7L, structural.acceptedWorkOccurrences());
        assertEquals(
                structural.acceptedWorkOccurrences(),
                structural.isolatedDocumentSteps());
        assertEquals(0L, structural.unrelatedComponentFinalizations());
        assertTrue(structural.componentFinalizations() > 0L);
        assertTrue(structural.canonicalCyclicBytes() > 0L);
        assertTrue(structural.providerExactNodeReads() > 0L);
        assertTrue(structural.occurrenceRowsExamined() > 0L);
        assertEquals(
                base.structuralMetrics().occurrenceRowsExamined(),
                structural.occurrenceRowsExamined());
        assertEquals(1L, structural.resultingComponents());
        assertTrue(structural.globalStatePasses() > 0L);
        assertTrue(structural.globalStateEntriesTraversed() > 0L,
                "Known global publication traversals remain an explicit "
                        + "optimization blocker");
        assertTrue(structural.globalSessionEntriesTraversed() > 0L);
        assertTrue(structural.globalOccurrenceEntriesTraversed() > 0L);
        assertTrue(structural.globalComponentEntriesTraversed() > 0L);
        assertTrue(structural.globalGraphEntriesTraversed() > 0L);
        assertTrue(structural.globalSubscriptionEntriesTraversed() > 0L);
        assertTrue(structural.globalReceiptEntriesTraversed() > 0L);
        assertEquals(0L, structural.globalRouteEntriesTraversed(),
                "Route publication uses exact persistent-key updates; the "
                        + "remaining global counters identify other store "
                        + "locality blockers");
        assertTrue(structural.globalEvidenceEntriesTraversed() > 0L);
        StructuralMetrics baseStructural = base.structuralMetrics();
        assertTrue(structural.globalStateEntriesTraversed()
                > baseStructural.globalStateEntriesTraversed());
        assertTrue(structural.globalSessionEntriesTraversed()
                > baseStructural.globalSessionEntriesTraversed());
        assertTrue(structural.globalComponentEntriesTraversed()
                > baseStructural.globalComponentEntriesTraversed());
        assertTrue(structural.globalGraphEntriesTraversed()
                > baseStructural.globalGraphEntriesTraversed());
    }

    @Test
    void unrelatedEntryDoesNotSpendOneSelectedEntryBudget() {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(BRANCHING.a()))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            branchingBuilder(engine, BranchingVariant.BASELINE)
                    .admitTo(publicEngine);

            Timeline unrelatedTimeline = publicEngine.registerTimeline(
                    "outside/source", "mallory");
            TimelineEntry unrelated = publicEngine.appendAt(
                    unrelatedTimeline,
                    Operation.yaml("ignored", "outsideChannel", "{}"),
                    ENTRY_TIME);
            Timeline relevantTimeline = publicEngine.registerTimeline(
                    "branching/shared", "alice");
            TimelineEntry relevant = publicEngine.appendAt(
                    relevantTimeline,
                    Operation.yaml("start", "ownerChannel", "{}"),
                    ENTRY_TIME + 1L);

            EngineMetrics.MetricsSnapshot rawBefore =
                    engine.engineMetrics().snapshot();
            ProcessingDrainReceipt drained = publicEngine.drain(
                    new CoordinationEngine.DrainBudget(Long.MAX_VALUE, 1L));

            // when
            EngineMetrics.MetricsSnapshot rawAfter =
                    engine.engineMetrics().snapshot();

            // then
            assertTrue(drained.quiescent());
            assertFalse(drained.paused());
            assertEquals(List.of(unrelated, relevant),
                    drained.processedEntries());
            assertEquals(List.of(), drained.outcomesFor(unrelated.blueId()));
            assertEquals(List.of(
                            BRANCHING.a(),
                            BRANCHING.b1(),
                            BRANCHING.b2(),
                            BRANCHING.c1(),
                            BRANCHING.c2()),
                    drained.outcomesFor(relevant.blueId()).stream()
                            .map(outcome -> outcome.documentId())
                            .toList());
            assertEquals(5L, drained.committedProcessTransitions());
            assertEquals(1L, rawDelta(
                    rawBefore,
                    rawAfter,
                    OperationRouteIndex.DIRECT_ROUTE_SNAPSHOTS));
            assertEquals(1L, rawDelta(
                    rawBefore,
                    rawAfter,
                    ContractsClosureAdapter.PLAN_CONSTRUCTIONS));
        }
    }

    @Test
    void disjointCyclesRemainSeparateForBothAndSingleTargetEntries() {
        // given

        DisjointRun both = runDisjoint(DisjointEntry.BOTH);

        // when
        DisjointRun one = runDisjoint(DisjointEntry.FIRST_ONLY);

        // then
        assertEquals(2, both.routeTargetCount());
        assertEquals(4L, both.drain().committedProcessTransitions());
        assertEquals(List.of(
                        both.ids().a1(),
                        both.ids().b1(),
                        both.ids().a2(),
                        both.ids().b2()),
                both.outcomeOrder());
        assertEquals(List.of(
                        List.of(both.ids().a1(), both.ids().b1()),
                        List.of(both.ids().a2(), both.ids().b2())),
                both.cohortOrder());
        assertEquals(List.of(
                        both.ids().a1().value(),
                        both.ids().b1().value(),
                        both.ids().a2().value(),
                        both.ids().b2().value()),
                both.workOrder());
        assertEquals(2, both.componentStates().size());
        assertTrue(both.componentStates().stream()
                .allMatch(component -> component.kind()
                        == ComponentKind.CYCLIC));
        assertNotEquals(
                both.componentStates().get(0).masterBlueId(),
                both.componentStates().get(1).masterBlueId());
        assertEquals(1L, both.journalEntriesAdded());

        assertEquals(1, one.routeTargetCount());
        assertEquals(2L, one.drain().committedProcessTransitions());
        assertEquals(List.of(one.ids().a1(), one.ids().b1()),
                one.outcomeOrder());
        assertEquals(List.of(List.of(one.ids().a1(), one.ids().b1())),
                one.cohortOrder());
        assertEquals(List.of(
                        one.ids().a1().value(),
                        one.ids().b1().value()),
                one.workOrder());
        assertEquals(one.beforeUntargetedBlueIds(),
                one.afterUntargetedBlueIds());
        assertEquals(0L, one.untargetedEpochs().get(one.ids().a2()));
        assertEquals(0L, one.untargetedEpochs().get(one.ids().b2()));
        assertEquals(2, one.componentStates().size());
        assertEquals(1L, one.journalEntriesAdded());
    }

    private static BranchingRun runBranching(
            BranchingVariant variant,
            int unrelatedCount) {
        LinkedHashSet<DocumentId> publicRoots = new LinkedHashSet<>();
        publicRoots.add(BRANCHING.a());
        publicRoots.addAll(unrelatedAdmissionRoots(unrelatedCount));
        try (CoordinationEngine publicEngine = engine(publicRoots)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder builder = branchingBuilder(
                    engine, variant);
            Contracts10ScenarioBuilder.ScenarioRuntime admitted =
                    builder.admitTo(publicEngine);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.admissionReceipt().publicationOutcome());
            assertEquals(1, admitted.scenario().components().size());
            ComponentSnapshot admittedBranching = admitted.scenario()
                    .components().get(0);
            assertEquals(ComponentKind.CYCLIC,
                    admittedBranching.kind());
            assertEquals(branchingDocumentValues(), admittedBranching
                    .orderedMemberDocumentIds().stream()
                    .map(documentId -> documentId.value())
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
            assertEquals(5, admittedBranching.completeCyclicProof()
                    .declaredPlaceholderSet().size());

            admitUnrelatedDocuments(
                    engine, publicEngine, unrelatedCount);
            assertEquals(1 + unrelatedCount,
                    engine.documents().publicationSnapshot()
                            .componentStates().size());

            EngineMetrics.MetricsSnapshot rawBefore =
                    engine.engineMetrics().snapshot();
            CoordinationMetrics before = publicEngine.metrics();
            Timeline timeline = publicEngine.registerTimeline(
                    "branching/shared", "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("start", "ownerChannel", "{}"),
                    ENTRY_TIME);
            int routeTargets = publicEngine.routeTargetCount(entry);
            assertEquals(1, routeTargets);
            ProcessingDrainReceipt drained = publicEngine.drain();
            CoordinationMetrics after = publicEngine.metrics();
            EngineMetrics.MetricsSnapshot rawAfter =
                    engine.engineMetrics().snapshot();

            assertTrue(drained.quiescent());
            assertFalse(drained.paused());
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(List.of(
                            BRANCHING.a(),
                            BRANCHING.b1(),
                            BRANCHING.b2(),
                            BRANCHING.c1(),
                            BRANCHING.c2()),
                    drained.outcomesFor(entry.blueId()).stream()
                            .map(outcome -> outcome.documentId())
                            .toList());
            List<ContractsClosurePublicationReceipt> receipts =
                    processReceipts(engine);
            assertEquals(1, receipts.size());
            ClosureProcessResult result = receipts.get(0)
                    .attempt().processResult();
            assertTrue(result.commits());
            assertEquals(1, result.resultingComponents().size());
            ComponentSnapshot component = result.resultingComponents().get(0);
            assertEquals(ComponentKind.CYCLIC, component.kind());
            assertEquals(branchingDocumentValues(), component
                    .orderedMemberDocumentIds().stream()
                    .map(documentId -> documentId.value())
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
            assertEquals(component.masterBlueId(), result
                    .resultingDocuments().get(0).afterBlueId()
                    .substring(0, result.resultingDocuments().get(0)
                            .afterBlueId().indexOf('#')));
            assertTrue(result.resultingDocuments().stream()
                    .allMatch(document -> component.masterBlueId().equals(
                            master(document.afterBlueId()))));

            Set<String> unrelated = unrelatedIds(unrelatedCount).stream()
                    .map(DocumentId::value)
                    .collect(Collectors.toUnmodifiableSet());
            Map<DocumentId, String> finalBlueIds = branchingDocuments().stream()
                    .collect(Collectors.toMap(
                            documentId -> documentId,
                            documentId -> publicEngine.document(
                                    documentId).blueId(),
                            (left, right) -> left,
                            LinkedHashMap::new));
            Map<DocumentId, String> phases = branchingDocuments().stream()
                    .collect(Collectors.toMap(
                            documentId -> documentId,
                            documentId -> String.valueOf(property(
                                    publicEngine, documentId, "phase")),
                            (left, right) -> left,
                            LinkedHashMap::new));
            Map<String, String> branchStates = Map.of(
                    "branch1", String.valueOf(property(
                            publicEngine, BRANCHING.a(), "branch1")),
                    "branch2", String.valueOf(property(
                            publicEngine, BRANCHING.a(), "branch2")));
            BranchingSemanticEvidence semantic = new BranchingSemanticEvidence(
                    finalBlueIds,
                    component.componentIdentity(),
                    component.componentStateIdentity(),
                    component.masterBlueId(),
                    component.cyclicProofIdentity(),
                    dequeuedDocumentIds(result),
                    dequeuedWorkIds(result),
                    result.gasTraceIdentity(),
                    result.totalGas(),
                    result.publicEvents().stream()
                            .map(ContractsPublicBranchingCollectionCycleTest
                                    ::eventKind)
                            .toList(),
                    result.publicEvents().stream()
                            .map(PublicEventOccurrence::eventBlueId)
                            .toList(),
                    result.publicEvents().stream()
                            .map(PublicEventOccurrence
                                    ::eventOccurrenceIdentity)
                            .toList(),
                    result.outputClosureIdentity());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P3.1.shared-anchor-" + variant.name(),
                        engine,
                        result,
                        drained,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "entryBlueId", entry.blueId(),
                                "routeTargetCount", routeTargets,
                                "changedDocuments", drained.outcomesFor(
                                        entry.blueId()).stream()
                                        .map(outcome -> outcome.documentId())
                                        .toList(),
                                "publicEventKinds",
                                semantic.publicEventKinds(),
                                "publicEventBlueIds",
                                semantic.publicEventBlueIds(),
                                "publicEventOccurrenceIdentities",
                                semantic.publicEventOccurrenceIds()));
            }
            return new BranchingRun(
                    semantic,
                    result,
                    drained,
                    routeTargets,
                    after.journalEntryCount() - before.journalEntryCount(),
                    Set.copyOf(drained.outcomesFor(entry.blueId()).stream()
                            .map(outcome -> outcome.documentId()).toList()),
                    phases,
                    branchStates,
                    countGasForDocuments(
                            result, "managedDocumentOpened", unrelated),
                    countGasForDocuments(
                            result,
                            "closureWorkOccurrenceDequeued",
                            unrelated),
                    countGasForDocuments(
                            result, "cyclicMemberFinalized", unrelated),
                    counterDelta(
                            before,
                            after,
                            CoordinationMetrics.Counter
                                    .FULL_ENVIRONMENT_SCANS),
                    counterDelta(
                            before,
                            after,
                            CoordinationMetrics.Counter
                                    .UNRELATED_DOCUMENT_READS),
                    structuralDelta(rawBefore, rawAfter),
                    after.documentCount());
        }
    }

    private static Contracts10ScenarioBuilder branchingBuilder(
            DefaultCoordinationEngine engine,
            BranchingVariant variant) {
        Contracts10ScenarioBuilder builder =
                new Contracts10ScenarioBuilder(engine);
        List<Map.Entry<DocumentId, String>> authored = new ArrayList<>(List.of(
                Map.entry(BRANCHING.a(), branchingA()),
                Map.entry(BRANCHING.b1(), branchingB(
                        BRANCHING.b1(), "branch-c1", "branch-result-1")),
                Map.entry(BRANCHING.b2(), branchingB(
                        BRANCHING.b2(), "branch-c2", "branch-result-2")),
                Map.entry(BRANCHING.c1(), branchingC(
                        BRANCHING.c1(), "branch-start-1", "branch-c1")),
                Map.entry(BRANCHING.c2(), branchingC(
                        BRANCHING.c2(), "branch-start-2", "branch-c2"))));
        if (variant == BranchingVariant.REVERSED_MATERIALIZED) {
            Collections.reverse(authored);
        }
        for (Map.Entry<DocumentId, String> document : authored) {
            builder.document(document.getKey(), document.getValue());
        }

        if (variant == BranchingVariant.REVERSED_MATERIALIZED) {
            builder.processEmbeddedPath(
                            BRANCHING.c2(), "/root", BRANCHING.a())
                    .processEmbeddedPath(
                            BRANCHING.b2(), "/child", BRANCHING.c2())
                    .processEmbeddedCollectionMember(
                            BRANCHING.a(),
                            "/branches",
                            "b2",
                            BRANCHING.b2())
                    .processEmbeddedPath(
                            BRANCHING.c1(), "/root", BRANCHING.a())
                    .processEmbeddedPath(
                            BRANCHING.b1(), "/child", BRANCHING.c1())
                    .processEmbeddedCollectionMember(
                            BRANCHING.a(),
                            "/branches",
                            "b1",
                            BRANCHING.b1())
                    .occurrenceOrder(
                            Contracts10ScenarioBuilder.OccurrenceOrder
                                    .REVERSED)
                    .representation(
                            Contracts10ScenarioBuilder
                                    .ReferenceRepresentation.MATERIALIZED);
        } else {
            builder.processEmbeddedCollectionMember(
                            BRANCHING.a(),
                            "/branches",
                            "b1",
                            BRANCHING.b1())
                    .processEmbeddedPath(
                            BRANCHING.b1(), "/child", BRANCHING.c1())
                    .processEmbeddedPath(
                            BRANCHING.c1(), "/root", BRANCHING.a())
                    .processEmbeddedCollectionMember(
                            BRANCHING.a(),
                            "/branches",
                            "b2",
                            BRANCHING.b2())
                    .processEmbeddedPath(
                            BRANCHING.b2(), "/child", BRANCHING.c2())
                    .processEmbeddedPath(
                            BRANCHING.c2(), "/root", BRANCHING.a());
        }
        builder.publicRoot(BRANCHING.a())
                .expectedComponent(
                        BRANCHING.a(),
                        BRANCHING.b1(),
                        BRANCHING.b2(),
                        BRANCHING.c1(),
                        BRANCHING.c2())
                .admissionLabel("contracts-public-branching-collection");
        return builder;
    }

    private static void admitUnrelatedDocuments(
            DefaultCoordinationEngine engine,
            CoordinationEngine publicEngine,
            int unrelatedCount) {
        List<DocumentId> unrelated = unrelatedIds(unrelatedCount);
        for (int start = 0; start < unrelated.size();
                start += UNRELATED_ADMISSION_BATCH_SIZE) {
            int end = Math.min(
                    start + UNRELATED_ADMISSION_BATCH_SIZE,
                    unrelated.size());
            Contracts10ScenarioBuilder batch =
                    new Contracts10ScenarioBuilder(engine);
            for (DocumentId documentId : unrelated.subList(start, end)) {
                batch.document(documentId, unrelatedDocument(documentId))
                        .expectedComponent(documentId);
            }
            batch.publicRoot(unrelated.get(start))
                    .admissionLabel(
                            "contracts-public-unrelated-batch-" + start);
            Contracts10ScenarioBuilder.ScenarioRuntime admitted =
                    batch.admitTo(publicEngine);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.admissionReceipt().publicationOutcome(),
                    "unrelated admission batch starting at " + start + ": "
                            + describeAdmission(
                                    admitted.admissionReceipt()));
        }
    }

    private static String describeAdmission(
            ContractsClosureAdmissionReceipt receipt) {
        if (!receipt.attempt().isComplete()) {
            return "needs resources "
                    + receipt.attempt().requiredExactBlueIds();
        }
        ClosureProcessResult result = receipt.attempt().processResult();
        if (result.diagnostic() == null) {
            return result.status() + " with no diagnostic";
        }
        return result.status() + " " + result.diagnostic().category()
                + " " + result.diagnostic().message() + " "
                + result.diagnostic().details();
    }

    private static DisjointRun runDisjoint(DisjointEntry selection) {
        DisjointIds ids = new DisjointIds(
                DocumentId.of("branch-disjoint-a1"),
                DocumentId.of("branch-disjoint-b1"),
                DocumentId.of("branch-disjoint-a2"),
                DocumentId.of("branch-disjoint-b2"));
        try (CoordinationEngine publicEngine = engine(
                Set.of(ids.a1(), ids.a2()))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder builder =
                    new Contracts10ScenarioBuilder(engine)
                            .document(ids.b2(), disjointB(
                                    ids.b2(), "disjoint-two"))
                            .document(ids.a2(), disjointA(
                                    ids.a2(), "disjoint-two", false))
                            .document(ids.b1(), disjointB(
                                    ids.b1(), "disjoint-one"))
                            .document(ids.a1(), disjointA(
                                    ids.a1(), "disjoint-one", true))
                            .processEmbeddedPath(ids.b2(), "/a", ids.a2())
                            .processEmbeddedPath(ids.a2(), "/b", ids.b2())
                            .processEmbeddedPath(ids.b1(), "/a", ids.a1())
                            .processEmbeddedPath(ids.a1(), "/b", ids.b1())
                            .publicRoot(ids.a2())
                            .publicRoot(ids.a1())
                            .expectedComponent(ids.a1(), ids.b1())
                            .expectedComponent(ids.a2(), ids.b2())
                            .admissionLabel(
                                    "contracts-public-disjoint-cycles");
            Contracts10ScenarioBuilder.ScenarioRuntime admitted =
                    builder.admitTo(publicEngine);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.admissionReceipt().publicationOutcome());
            assertEquals(List.of(
                            List.of(ids.a1(), ids.b1()),
                            List.of(ids.a2(), ids.b2())),
                    admitted.scenario().componentMembers());

            Map<DocumentId, String> beforeUntargeted = Map.of(
                    ids.a2(), publicEngine.document(ids.a2()).blueId(),
                    ids.b2(), publicEngine.document(ids.b2()).blueId());
            CoordinationMetrics before = publicEngine.metrics();
            Timeline timeline = publicEngine.registerTimeline(
                    "disjoint/shared", "alice");
            String operation = selection == DisjointEntry.BOTH
                    ? "start"
                    : "solo";
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml(operation, "sharedChannel", "{}"),
                    ENTRY_TIME + selection.ordinal());
            int routeTargetCount = publicEngine.routeTargetCount(entry);
            ProcessingDrainReceipt drained = publicEngine.drain();
            CoordinationMetrics after = publicEngine.metrics();
            assertTrue(drained.quiescent());
            assertEquals(List.of(entry), drained.processedEntries());

            List<ContractsClosurePublicationReceipt> receipts =
                    processReceipts(engine);
            List<List<DocumentId>> cohorts = receipts.stream()
                    .map(ContractsClosurePublicationReceipt::documentIds)
                    .toList();
            List<String> workOrder = receipts.stream()
                    .flatMap(receipt -> dequeuedDocumentIds(
                            receipt.attempt().processResult()).stream())
                    .toList();
            Map<DocumentId, String> afterUntargeted = Map.of(
                    ids.a2(), publicEngine.document(ids.a2()).blueId(),
                    ids.b2(), publicEngine.document(ids.b2()).blueId());
            Map<DocumentId, Long> untargetedEpochs = Map.of(
                    ids.a2(), publicEngine.document(ids.a2()).epoch(),
                    ids.b2(), publicEngine.document(ids.b2()).epoch());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                for (int index = 0; index < receipts.size(); index++) {
                    ClosureProcessResult result = receipts.get(index)
                            .attempt().processResult();
                    CyclicTopologyIdentityEvidenceTest.capture(
                            "P3.3.disjoint-" + selection.name()
                                    + "-cohort-" + index,
                            engine,
                            result,
                            drained,
                            CyclicTopologyIdentityEvidenceTest.facts(
                                    "entryBlueId", entry.blueId(),
                                    "routeTargetCount", routeTargetCount,
                                    "cohortIndex", index,
                                    "cohortDocuments",
                                    receipts.get(index).documentIds(),
                                    "beforeUntargetedBlueIds",
                                    beforeUntargeted,
                                    "afterUntargetedBlueIds",
                                    afterUntargeted,
                                    "untargetedEpochs", untargetedEpochs));
                }
            }
            return new DisjointRun(
                    ids,
                    drained,
                    routeTargetCount,
                    drained.outcomesFor(entry.blueId()).stream()
                            .map(outcome -> outcome.documentId())
                            .toList(),
                    cohorts,
                    workOrder,
                    engine.documents().publicationSnapshot()
                            .componentStates(),
                    beforeUntargeted,
                    afterUntargeted,
                    untargetedEpochs,
                    after.journalEntryCount() - before.journalEntryCount());
        }
    }

    private static String branchingA() {
        return """
                documentId: branching-a
                phase: initial
                branch1: pending
                branch2: pending
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: branching/shared
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: started}
                          - $appendEvent: {type: Coordination/Event, kind: branch-start-1}
                          - $return: true
                  fromB1:
                    type: {blueId: %s}
                    sourcePath: /branches/b1
                    event: {type: Coordination/Event, kind: branch-result-1}
                  onB1:
                    type: Coordination/Sequential Workflow
                    channel: fromB1
                    event: {type: Coordination/Event, kind: branch-result-1}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /branch1, val: done}
                          - $appendEvent: {type: Coordination/Event, kind: branch-ack}
                          - $appendEvent: {type: Coordination/Event, kind: branch-start-2}
                          - $return: true
                  fromB2:
                    type: {blueId: %s}
                    sourcePath: /branches/b2
                    event: {type: Coordination/Event, kind: branch-result-2}
                  onB2:
                    type: Coordination/Sequential Workflow
                    channel: fromB2
                    event: {type: Coordination/Event, kind: branch-result-2}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /branch2, val: done}
                          - $appendChange: {op: replace, path: /phase, val: done}
                          - $appendEvent: {type: Coordination/Event, kind: branch-ack}
                          - $appendEvent: {type: Coordination/Event, kind: branching-done}
                          - $return: true
                """.formatted(
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String branchingB(
            DocumentId documentId,
            String incomingKind,
            String outgoingKind) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  fromChild:
                    type: {blueId: %s}
                    sourcePath: /child
                    event: {type: Coordination/Event, kind: %s}
                  contribute:
                    type: Coordination/Sequential Workflow
                    channel: fromChild
                    event: {type: Coordination/Event, kind: %s}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: contributed}
                          - $appendEvent: {type: Coordination/Event, kind: %s}
                          - $return: true
                """.formatted(
                documentId.value(),
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                incomingKind,
                incomingKind,
                outgoingKind);
    }

    private static String branchingC(
            DocumentId documentId,
            String incomingKind,
            String outgoingKind) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  fromRoot:
                    type: {blueId: %s}
                    sourcePath: /root
                    event: {type: Coordination/Event, kind: %s}
                  observe:
                    type: Coordination/Sequential Workflow
                    channel: fromRoot
                    event: {type: Coordination/Event, kind: %s}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: observed}
                          - $appendEvent: {type: Coordination/Event, kind: %s}
                          - $return: true
                """.formatted(
                documentId.value(),
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                incomingKind,
                incomingKind,
                outgoingKind);
    }

    private static String unrelatedDocument(DocumentId documentId) {
        return """
                documentId: %s
                phase: unrelated
                contracts: {}
                """.formatted(documentId.value());
    }

    private static String disjointA(
            DocumentId documentId,
            String eventKind,
            boolean includeSolo) {
        String solo = includeSolo
                ? """
                  solo:
                    type: Coordination/Sequential Workflow Operation
                    channel: sharedChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: direct}
                          - $appendEvent: {type: Coordination/Event, kind: %s}
                          - $return: true
                  """.formatted(eventKind).indent(2).stripTrailing()
                : "";
        return """
                documentId: %s
                phase: initial
                contracts:
                  sharedChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: disjoint/shared
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: sharedChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: direct}
                          - $appendEvent: {type: Coordination/Event, kind: %s}
                          - $return: true
                %s
                """.formatted(documentId.value(), eventKind, solo);
    }

    private static String disjointB(
            DocumentId documentId,
            String eventKind) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  fromA:
                    type: {blueId: %s}
                    sourcePath: /a
                    event: {type: Coordination/Event, kind: %s}
                  react:
                    type: Coordination/Sequential Workflow
                    channel: fromA
                    event: {type: Coordination/Event, kind: %s}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: reacted}
                          - $return: true
                """.formatted(
                documentId.value(),
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                eventKind,
                eventKind);
    }

    private static List<ContractsClosurePublicationReceipt> processReceipts(
            DefaultCoordinationEngine engine) {
        return List.copyOf(engine.documents().publicationSnapshot()
                .closurePublicationReceipts().values());
    }

    private static List<String> dequeuedDocumentIds(
            ClosureProcessResult result) {
        return result.gasTrace().stream()
                .filter(gas -> "closureWorkOccurrenceDequeued".equals(
                        gas.counter()))
                .map(GasTraceEntry::documentId)
                .map(documentId -> documentId.value())
                .toList();
    }

    private static List<String> dequeuedWorkIds(
            ClosureProcessResult result) {
        return result.gasTrace().stream()
                .filter(gas -> "closureWorkOccurrenceDequeued".equals(
                        gas.counter()))
                .map(GasTraceEntry::workOccurrenceId)
                .toList();
    }

    private static long countGasForDocuments(
            ClosureProcessResult result,
            String counter,
            Set<String> documentIds) {
        return result.gasTrace().stream()
                .filter(gas -> counter.equals(gas.counter()))
                .map(GasTraceEntry::documentId)
                .filter(documentId -> documentId != null
                        && documentIds.contains(documentId.value()))
                .count();
    }

    private static long counterDelta(
            CoordinationMetrics before,
            CoordinationMetrics after,
            CoordinationMetrics.Counter counter) {
        return after.counter(counter) - before.counter(counter);
    }

    private static StructuralMetrics structuralDelta(
            EngineMetrics.MetricsSnapshot before,
            EngineMetrics.MetricsSnapshot after) {
        return new StructuralMetrics(
                rawDelta(before, after,
                        OperationRouteIndex.DIRECT_ROUTE_SNAPSHOTS),
                rawDelta(before, after,
                        OperationRouteIndex
                                .DIRECT_ROUTE_REVALIDATION_SNAPSHOTS),
                rawDelta(before, after,
                        "routing.closureDeliveriesSelected"),
                rawDelta(before, after,
                        ContractsClosureAdapter.PLAN_CONSTRUCTIONS),
                rawDelta(before, after,
                        ContractsClosureAdapter.COHORTS_SELECTED),
                rawDelta(before, after,
                        InMemoryDocumentStore.CLOSURE_TOPOLOGY_SNAPSHOTS),
                rawDelta(before, after,
                        InMemoryDocumentStore.CLOSURE_HEADS_CAPTURED),
                rawDelta(before, after,
                        ContractsClosureAdapter.DOCUMENT_OPENS),
                rawDelta(before, after,
                        ContractsClosureAdapter.UNRELATED_DOCUMENT_OPENS),
                rawDelta(before, after,
                        InMemoryDocumentStore
                                .CLOSURE_COMPONENT_STATES_CAPTURED),
                rawDelta(before, after,
                        ContractsClosureAdapter.COMPONENT_STATES_READ),
                rawDelta(before, after,
                        WholeRequestEntryFactory.REQUEST_SOURCES_PARSED),
                rawDelta(before, after,
                        ContractsClosureExecutionMetricsObserver
                                .ACCEPTED_WORK_OCCURRENCES),
                rawDelta(before, after,
                        ContractsClosureExecutionMetricsObserver
                                .ISOLATED_DOCUMENT_STEPS),
                rawDelta(before, after,
                        ContractsClosureExecutionMetricsObserver
                                .TENTATIVE_COMPONENT_FINALIZATIONS),
                rawDelta(before, after,
                        ContractsClosureExecutionMetricsObserver
                                .UNRELATED_COMPONENT_FINALIZATIONS),
                rawDelta(before, after,
                        ContractsClosureExecutionMetricsObserver
                                .CANONICAL_CYCLIC_BYTES),
                rawDelta(before, after,
                        BlueRuntime.PROVIDER_EXACT_NODE_READS),
                rawDelta(before, after,
                        ContractsClosureAdapter.OCCURRENCE_ROWS_EXAMINED),
                rawDelta(before, after,
                        ContractsClosureAdapter.RESULTING_COMPONENTS),
                rawDelta(before, after,
                        ContractsStructuralWorkMetrics.GLOBAL_STATE_PASSES),
                rawDelta(before, after,
                        ContractsStructuralWorkMetrics
                                .GLOBAL_STATE_ENTRIES_TRAVERSED),
                rawDelta(before, after,
                        ContractsStructuralWorkMetrics
                                .GLOBAL_SESSION_ENTRIES_TRAVERSED),
                rawDelta(before, after,
                        ContractsStructuralWorkMetrics
                                .GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED),
                rawDelta(before, after,
                        ContractsStructuralWorkMetrics
                                .GLOBAL_COMPONENT_ENTRIES_TRAVERSED),
                rawDelta(before, after,
                        ContractsStructuralWorkMetrics
                                .GLOBAL_GRAPH_ENTRIES_TRAVERSED),
                rawDelta(before, after,
                        ContractsStructuralWorkMetrics
                                .GLOBAL_SUBSCRIPTION_ENTRIES_TRAVERSED),
                rawDelta(before, after,
                        ContractsStructuralWorkMetrics
                                .GLOBAL_RECEIPT_ENTRIES_TRAVERSED),
                rawDelta(before, after,
                        ContractsStructuralWorkMetrics
                                .GLOBAL_ROUTE_ENTRIES_TRAVERSED),
                rawDelta(before, after,
                        ContractsStructuralWorkMetrics
                                .GLOBAL_EVIDENCE_ENTRIES_TRAVERSED));
    }

    private static long rawDelta(
            EngineMetrics.MetricsSnapshot before,
            EngineMetrics.MetricsSnapshot after,
            String counter) {
        return after.counters().getOrDefault(counter, 0L)
                - before.counters().getOrDefault(counter, 0L);
    }

    private static String eventKind(PublicEventOccurrence event) {
        return String.valueOf(event.event().getProperties()
                .get("kind").getValue());
    }

    private static Object property(
            CoordinationEngine engine,
            DocumentId documentId,
            String key) {
        Node value = engine.document(documentId).current().copyNode()
                .getProperties().get(key);
        return value == null ? null : value.getValue();
    }

    private static Set<String> branchingDocumentValues() {
        return branchingDocuments().stream()
                .map(DocumentId::value)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<DocumentId> branchingDocuments() {
        return List.of(
                BRANCHING.a(),
                BRANCHING.b1(),
                BRANCHING.b2(),
                BRANCHING.c1(),
                BRANCHING.c2());
    }

    private static List<DocumentId> unrelatedIds(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> DocumentId.of(
                        "zz-unrelated-%04d".formatted(index)))
                .toList();
    }

    private static List<DocumentId> unrelatedAdmissionRoots(int count) {
        List<DocumentId> unrelated = unrelatedIds(count);
        ArrayList<DocumentId> roots = new ArrayList<>();
        for (int index = 0; index < unrelated.size();
                index += UNRELATED_ADMISSION_BATCH_SIZE) {
            roots.add(unrelated.get(index));
        }
        return List.copyOf(roots);
    }

    private static String master(String memberBlueId) {
        return memberBlueId.substring(0, memberBlueId.indexOf('#'));
    }

    private static CoordinationEngine engine(Set<DocumentId> publicRoots) {
        return CoordinationEngine.inMemoryContracts10(
                BundledContracts10Release.configuration(publicRoots));
    }

    private enum BranchingVariant {
        BASELINE,
        REVERSED_MATERIALIZED
    }

    private enum DisjointEntry {
        BOTH,
        FIRST_ONLY
    }

    private record BranchingIds(
            DocumentId a,
            DocumentId b1,
            DocumentId b2,
            DocumentId c1,
            DocumentId c2) {
    }

    private record BranchingSemanticEvidence(
            Map<DocumentId, String> finalBlueIds,
            String componentIdentity,
            String componentStateIdentity,
            String masterBlueId,
            String proofIdentity,
            List<String> workOrder,
            List<String> workIds,
            String gasTraceIdentity,
            long totalGas,
            List<String> publicEventKinds,
            List<String> publicEventBlueIds,
            List<String> publicEventOccurrenceIds,
            String outputClosureIdentity) {
        private BranchingSemanticEvidence {
            finalBlueIds = Map.copyOf(finalBlueIds);
            workOrder = List.copyOf(workOrder);
            workIds = List.copyOf(workIds);
            publicEventKinds = List.copyOf(publicEventKinds);
            publicEventBlueIds = List.copyOf(publicEventBlueIds);
            publicEventOccurrenceIds = List.copyOf(
                    publicEventOccurrenceIds);
        }
    }

    private record BranchingRun(
            BranchingSemanticEvidence semantic,
            ClosureProcessResult processResult,
            ProcessingDrainReceipt drain,
            int routeTargetCount,
            long journalEntriesAdded,
            Set<DocumentId> changedDocuments,
            Map<DocumentId, String> finalPhases,
            Map<String, String> branchStates,
            long unrelatedDocumentOpens,
            long unrelatedDocumentSteps,
            long unrelatedMemberFinalizations,
            long fullPublicationHeadSnapshots,
            long unrelatedDocumentReads,
            StructuralMetrics structuralMetrics,
            int documentCount) {
        private BranchingRun {
            changedDocuments = Set.copyOf(changedDocuments);
            finalPhases = Map.copyOf(finalPhases);
            branchStates = Map.copyOf(branchStates);
        }
    }

    private record StructuralMetrics(
            long directRouteSnapshots,
            long directRouteRevalidationSnapshots,
            long directDeliveriesSelected,
            long planConstructions,
            long cohortsSelected,
            long topologySnapshots,
            long headsCaptured,
            long documentOpens,
            long unrelatedDocumentOpens,
            long componentStatesCaptured,
            long componentStatesRead,
            long requestSourcesParsed,
            long acceptedWorkOccurrences,
            long isolatedDocumentSteps,
            long componentFinalizations,
            long unrelatedComponentFinalizations,
            long canonicalCyclicBytes,
            long providerExactNodeReads,
            long occurrenceRowsExamined,
            long resultingComponents,
            long globalStatePasses,
            long globalStateEntriesTraversed,
            long globalSessionEntriesTraversed,
            long globalOccurrenceEntriesTraversed,
            long globalComponentEntriesTraversed,
            long globalGraphEntriesTraversed,
            long globalSubscriptionEntriesTraversed,
            long globalReceiptEntriesTraversed,
            long globalRouteEntriesTraversed,
            long globalEvidenceEntriesTraversed) {
    }

    private record DisjointIds(
            DocumentId a1,
            DocumentId b1,
            DocumentId a2,
            DocumentId b2) {
    }

    private record DisjointRun(
            DisjointIds ids,
            ProcessingDrainReceipt drain,
            int routeTargetCount,
            List<DocumentId> outcomeOrder,
            List<List<DocumentId>> cohortOrder,
            List<String> workOrder,
            List<ComponentSnapshot> componentStates,
            Map<DocumentId, String> beforeUntargetedBlueIds,
            Map<DocumentId, String> afterUntargetedBlueIds,
            Map<DocumentId, Long> untargetedEpochs,
            long journalEntriesAdded) {
        private DisjointRun {
            outcomeOrder = List.copyOf(outcomeOrder);
            cohortOrder = cohortOrder.stream().map(List::copyOf).toList();
            workOrder = List.copyOf(workOrder);
            componentStates = List.copyOf(componentStates);
            beforeUntargetedBlueIds = Map.copyOf(
                    beforeUntargetedBlueIds);
            afterUntargetedBlueIds = Map.copyOf(afterUntargetedBlueIds);
            untargetedEpochs = Map.copyOf(untargetedEpochs);
        }
    }
}
