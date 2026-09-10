package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedDocumentReadiness;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.SessionStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-SDK acceptance for retained states in static authored admission. */
final class SdkStaticRetainedManagedEpochAdmissionTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";
    private static final DocumentId SOURCE = DocumentId.of(
            "sdk-static-retained-source");
    private static final String SOURCE_TIMELINE =
            "sdk/static-retained/source";

    @Test
    void inlineStaticAdmissionMatchesEveryUnambiguousRetainedPosition() {
        // given
        try (BlueCoordination coordination = LegacyContracts10TestProfile.builder().build()) {
            List<SourceHistory> sources = prepareIndependentSources(
                    coordination);

            // when
            exerciseEveryRetainedPosition(
                    coordination, sources, Representation.INLINE, null);

            // then
            sources.forEach(source -> assertSourceUntouched(
                    coordination, source));
        }
    }

    @Test
    void pureReferenceStaticAdmissionUsesExactProviderContentForEveryPosition() {
        // given
        Map<String, String> exactContent = new LinkedHashMap<>();
        List<String> providerReads = new ArrayList<>();
        ExactNodeProvider provider = blueId -> {
            providerReads.add(blueId);
            return Optional.ofNullable(exactContent.get(blueId));
        };

        // when
        try (BlueCoordination coordination = LegacyContracts10TestProfile.builder()
                .exactNodeProvider(provider)
                .build()) {
            List<SourceHistory> sources = prepareIndependentSources(
                    coordination);
            selectedCases(sources).forEach(selected -> {
                assertEquals(selected.value().blueId(), coordination.values()
                        .providerContentYaml(selected.value().json())
                        .blueId());
                exactContent.put(
                        selected.value().blueId(), selected.value().json());
            });

            exerciseEveryRetainedPosition(
                    coordination,
                    sources,
                    Representation.PURE_REFERENCE,
                    providerReads);

            // then
            assertEquals(selectedCases(sources).stream()
                            .map(selected -> selected.value().blueId())
                            .toList(),
                    providerReads,
                    "each pure reference must perform one exact lookup");
        }
    }

    @Test
    void collectionPathsShareOneAtomicBarrierUntilEverySuffixCompletes() {
        // given
        try (BlueCoordination coordination = LegacyContracts10TestProfile.builder().build()) {
            SourceHistory source = prepareSource(coordination);
            RetainedCase authored = source.cases().get(0);
            RetainedCase retained = source.cases().get(2);
            String rootYaml = collectionRootYaml(
                    authored.value(), retained.value());

            // when
            ClosureHandle closure = coordination.documents()
                    .admitStaticProcessEmbedded(rootYaml);
            DocumentId root = closure.document("root").id();
            List<ManagedOccurrenceCatchUpPlan> plans = coordination.advanced()
                    .auditManagedCatchUpPlans(root);

            assertEquals(2, plans.size());
            assertEquals(List.of("/children/first", "/children/second"),
                    plans.stream()
                            .map(ManagedOccurrenceCatchUpPlan::targetPath)
                            .sorted()
                            .toList());
            assertEquals(List.of(-1L, 1L), plans.stream()
                    .map(ManagedOccurrenceCatchUpPlan::admittedSourceEpoch)
                    .sorted()
                    .toList());
            assertEquals(List.of(0L, 2L), plans.stream()
                    .map(ManagedOccurrenceCatchUpPlan::nextSourceEpoch)
                    .sorted()
                    .toList());
            assertEquals(1, plans.stream()
                    .map(ManagedOccurrenceCatchUpPlan::barrierIdentity)
                    .distinct()
                    .count());
            String barrierIdentity = plans.get(0).barrierIdentity();
            ManagedCatchUpBarrier open = coordination.advanced()
                    .auditManagedCatchUpBarrier(barrierIdentity)
                    .orElseThrow();
            assertEquals(ManagedCatchUpBarrierStatus.OPEN, open.status());
            assertEquals(2, open.planIdentities().size());

            DrainResult first = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            assertEquals(1, first.managedEpochApplications().size());
            ManagedDocumentReadiness partial = coordination.advanced()
                    .auditManagedDocumentReadiness(root).orElseThrow();
            assertEquals(SessionStatus.CATCHING_UP, partial.status());
            assertFalse(partial.ready(),
                    "one completed path must not promote a shared barrier");

            DrainResult completed = coordination.processing().drain();

            // then
            assertEquals(3, completed.managedEpochApplications().size());
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(root)
                    .orElseThrow().ready());
            assertEquals(ManagedCatchUpBarrierStatus.COMPLETE,
                    coordination.advanced()
                            .auditManagedCatchUpBarrier(barrierIdentity)
                            .orElseThrow().status());
            assertEquals(1L, closure.document("root").snapshot()
                    .longAt("/firstInitializations"));
            assertEquals(2L, closure.document("root").snapshot()
                    .longAt("/firstChanges"));
            assertEquals(1L, closure.document("root").snapshot()
                    .longAt("/secondChanges"));
            assertSourceUntouched(coordination, source);
        }
    }

    @Test
    void typedSelectorBindsAuthoredInitialAtStaticAdmission() {
        // given
        try (BlueCoordination coordination = LegacyContracts10TestProfile.builder().build()) {
            SourceHistory source = prepareSource(coordination);
            RetainedCase authored = source.cases().get(0);
            String rootYaml = rootYaml(
                    "selected authored", authored.value(),
                    Representation.INLINE);

            // when
            ClosureHandle closure = coordination.documents()
                    .admitStaticProcessEmbedded(
                            rootYaml,
                            List.of(ManagedEpochSelector.exact(
                                    SOURCE,
                                    -1L,
                                    authored.value().blueId(),
                                    "/child")));
            DocumentId root = closure.document("root").id();
            ManagedOccurrenceCatchUpPlan plan = coordination.advanced()
                    .auditManagedCatchUpPlans(root).get(0);
            assertEquals(-1L, plan.admittedSourceEpoch());
            assertEquals(0L, plan.nextSourceEpoch());
            assertEquals(2L, plan.requiredThroughSourceEpoch());

            DrainResult drained = coordination.processing().drain();

            // then
            assertEquals(List.of(1L, 2L, 3L), drained
                    .managedEpochApplications().stream()
                    .filter(receipt -> receipt.consumerDocumentId().equals(
                            root))
                    .map(ManagedEpochApplicationReceipt
                            ::resultingSourceCursor)
                    .toList());
            assertEquals(2L, closure.document("root").snapshot()
                    .longAt("/observedChanges"));
            assertEquals(1L, closure.document("root").snapshot()
                    .longAt("/observedInitializations"));
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(root)
                    .orElseThrow().ready());
            assertSourceUntouched(coordination, source);
            assertTrue(coordination.advanced().auditManagedEpochs(SOURCE)
                    .stream().allMatch(receipt -> receipt.epoch() >= 0L));
        }
    }

    private static void exerciseEveryRetainedPosition(
            BlueCoordination coordination,
            List<SourceHistory> sources,
            Representation representation,
            List<String> providerReads) {
        List<RetainedCase> selectedCases = selectedCases(sources);
        for (int index = 0; index < selectedCases.size(); index++) {
            SourceHistory source = sources.get(index);
            RetainedCase selected = selectedCases.get(index);
            int readsBefore = providerReads == null
                    ? 0 : providerReads.size();
            String rootYaml = rootYaml(selected.label(), selected.value(),
                    representation);
            ClosureHandle closure = coordination.documents()
                    .admitStaticProcessEmbedded(rootYaml);
            DocumentId root = closure.document("root").id();

            assertEquals(2, closure.documents().size());
            assertEquals(source.source().id(),
                    closure.document("embedded-0").id());
            assertEquals(source.source().id(), closure.occurrences().get(0)
                    .targetDocumentId());
            assertEquals(selected.value().blueId(), closure.occurrences()
                    .get(0).expectedTargetBlueId());
            if (providerReads != null) {
                assertEquals(readsBefore + 1, providerReads.size());
                assertEquals(selected.value().blueId(),
                        providerReads.get(readsBefore));
            }

            List<ManagedOccurrenceCatchUpPlan> plans = coordination.advanced()
                    .auditManagedCatchUpPlans(root);
            if (selected.current()) {
                assertTrue(plans.isEmpty(),
                        "current exact state must not create catch-up work");
                assertTrue(closure.occurrences().get(0).active());
                assertTrue(coordination.advanced()
                        .auditManagedDocumentReadiness(root)
                        .orElseThrow().ready());
            } else {
                assertEquals(1, plans.size());
                ManagedOccurrenceCatchUpPlan plan = plans.get(0);
                assertEquals(source.source().id(), plan.sourceDocumentId());
                assertEquals("/child", plan.targetPath());
                assertEquals(selected.admittedSourceEpoch(),
                        plan.admittedSourceEpoch());
                assertEquals(selected.admittedSourceEpoch() + 1L,
                        plan.nextSourceEpoch());
                assertEquals(2L, plan.requiredThroughSourceEpoch());
                assertFalse(closure.occurrences().get(0).active());
                ManagedDocumentReadiness readiness = coordination.advanced()
                        .auditManagedDocumentReadiness(root).orElseThrow();
                assertEquals(SessionStatus.CATCHING_UP, readiness.status());
                assertFalse(readiness.ready());
                assertEquals(List.of(plan.barrierIdentity()),
                        readiness.activeBarrierIdentities());
            }

            if (representation == Representation.INLINE) {
                int rootHistorySize = closure.document("root").history().size();
                List<String> planIdentities = plans.stream()
                        .map(ManagedOccurrenceCatchUpPlan::planIdentity)
                        .toList();

                ClosureHandle replayed = coordination.documents()
                        .admitStaticProcessEmbedded(rootYaml);

                assertEquals(closure.id(), replayed.id());
                assertEquals(root, replayed.document("root").id());
                assertEquals(source.source().id(),
                        replayed.document("embedded-0").id());
                assertEquals(rootHistorySize,
                        replayed.document("root").history().size(),
                        "admission replay must not duplicate the new Root");
                assertEquals(planIdentities, coordination.advanced()
                        .auditManagedCatchUpPlans(root).stream()
                        .map(ManagedOccurrenceCatchUpPlan::planIdentity)
                        .toList(),
                        "admission replay must not duplicate catch-up work");
            }

            assertSourceUntouched(coordination, source);
            DrainResult drain = coordination.processing().drain();
            List<ManagedEpochApplicationReceipt> applications = drain
                    .managedEpochApplications().stream()
                    .filter(receipt -> receipt.consumerDocumentId().equals(
                            root))
                    .toList();
            if (selected.current()) {
                assertTrue(applications.isEmpty());
                assertTrue(coordination.advanced().auditManagedCatchUpPlans(
                        root).isEmpty());
            } else {
                ManagedOccurrenceCatchUpPlan admittedPlan = plans.get(0);
                assertEquals(selected.sourceEpochSuffix().stream()
                                .map(source.receiptIdentities()::get)
                                .toList(),
                        applications.stream()
                                .map(ManagedEpochApplicationReceipt
                                        ::sourceReceiptIdentity)
                                .toList());
                assertEquals(selected.sourceEpochSuffix().stream()
                                .map(epoch -> epoch + 1L)
                                .toList(),
                        applications.stream()
                                .map(ManagedEpochApplicationReceipt
                                        ::resultingSourceCursor)
                                .toList());
                assertTrue(applications.stream().allMatch(receipt ->
                        receipt.planIdentity().equals(
                                admittedPlan.planIdentity())));
                ManagedOccurrenceCatchUpPlan complete = coordination
                        .advanced().auditManagedCatchUpPlans(
                                root).get(0);
                assertEquals(3L, complete.nextSourceEpoch());
                assertTrue(complete.status().terminal());
            }
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(root)
                    .orElseThrow().ready());
            DocumentSnapshot snapshot = coordination.documents()
                    .require(root).snapshot();
            assertEquals(selected.expectedInitializations(),
                    snapshot.longAt("/observedInitializations"));
            assertEquals(selected.expectedChanges(),
                    snapshot.longAt("/observedChanges"));
            assertSourceUntouched(coordination, source);
        }
    }

    private static List<RetainedCase> selectedCases(
            List<SourceHistory> sources) {
        ArrayList<RetainedCase> selected = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            selected.add(sources.get(index).cases().get(index));
        }
        return List.copyOf(selected);
    }

    private static List<SourceHistory> prepareIndependentSources(
            BlueCoordination coordination) {
        ArrayList<SourceHistory> sources = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            sources.add(prepareSource(
                    coordination,
                    DocumentId.of(SOURCE.value() + "-" + index),
                    SOURCE_TIMELINE + "/" + index));
        }
        return List.copyOf(sources);
    }

    private static SourceHistory prepareSource(BlueCoordination coordination) {
        return prepareSource(coordination, SOURCE, SOURCE_TIMELINE);
    }

    private static SourceHistory prepareSource(
            BlueCoordination coordination,
            DocumentId sourceId,
            String sourceTimeline) {
        TimelineHandle timeline = coordination.timelines().register(
                sourceTimeline, ACTOR);
        String sourceYaml = sourceYaml(sourceId, sourceTimeline);
        ExactBlueValue authored = coordination.values().yaml(sourceYaml);
        DocumentHandle source = coordination.documents().admit(
                ManagedDocument.yaml(sourceId, sourceYaml)
                        .publicRoot()
                        .fromNow());
        ExactBlueValue epochZero = source.snapshot().exact();
        increment(coordination, source, timeline).execute();
        ExactBlueValue epochOne = source.snapshot().exact();
        increment(coordination, source, timeline).execute();
        ExactBlueValue current = source.snapshot().exact();
        List<String> historyBlueIds = source.history().stream()
                .map(revision -> revision.after().blueId())
                .toList();
        Map<Long, String> receiptIdentities = new LinkedHashMap<>();
        coordination.advanced().auditManagedEpochs(sourceId).forEach(receipt ->
                receiptIdentities.put(
                        receipt.epoch(), receipt.receiptIdentity()));
        assertEquals(List.of(0L, 1L, 2L),
                new ArrayList<>(receiptIdentities.keySet()));
        return new SourceHistory(
                source,
                List.of(
                        new RetainedCase(
                                "authored", authored, -1L,
                                List.of(0L, 1L, 2L), 1L, 2L, false),
                        new RetainedCase(
                                "epoch-zero", epochZero, 0L,
                                List.of(1L, 2L), 0L, 2L, false),
                        new RetainedCase(
                                "retained", epochOne, 1L,
                                List.of(2L), 0L, 1L, false),
                        new RetainedCase(
                                "current", current, 2L,
                                List.of(), 0L, 0L, true)),
                historyBlueIds,
                Map.copyOf(receiptIdentities));
    }

    private static OperationCall increment(
            BlueCoordination coordination,
            DocumentHandle source,
            TimelineHandle timeline) {
        return coordination.operations()
                .on(source)
                .from(timeline)
                .call("increment")
                .through("ownerChannel")
                .request(request -> { });
    }

    private static void assertSourceUntouched(
            BlueCoordination coordination,
            SourceHistory source) {
        assertEquals(1L, source.source().snapshot()
                .longAt("/initializationCount"));
        assertEquals(2L, source.source().snapshot().longAt("/counter"));
        assertEquals(source.historyBlueIds(), source.source().history().stream()
                .map(revision -> revision.after().blueId())
                .toList(), "static reuse must not reprocess source history");
        assertEquals(source.receiptIdentities(), coordination.advanced()
                .auditManagedEpochs(source.source().id()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ManagedEpochReceipt::epoch,
                        ManagedEpochReceipt::receiptIdentity,
                        (left, right) -> left,
                        LinkedHashMap::new)));
    }

    private static String rootYaml(
            String label,
            ExactBlueValue child,
            Representation representation) {
        String childYaml = switch (representation) {
            case INLINE -> child.json();
            case PURE_REFERENCE -> "{\"blueId\":\""
                    + child.blueId() + "\"}";
        };
        return "name: static retained " + label + "\n"
                + "child: " + childYaml + "\n"
                + "observedInitializations: 0\n"
                + "observedChanges: 0\n"
                + "contracts:\n"
                + "  embedded:\n"
                + "    type: Process Embedded\n"
                + "    paths: [/child]\n"
                + "  fromChildInitialized:\n"
                + "    type: Embedded Node Channel\n"
                + "    sourcePath: /child\n"
                + "    event:\n"
                + "      type: Coordination/Event\n"
                + "      kind: CatchUp/B Initialized\n"
                + "  onChildInitialized:\n"
                + "    type: Coordination/Sequential Workflow\n"
                + "    channel: fromChildInitialized\n"
                + "    event:\n"
                + "      type: Coordination/Event\n"
                + "      kind: CatchUp/B Initialized\n"
                + "    steps:\n"
                + "      - type: Coordination/Compute\n"
                + "        do:\n"
                + "          - $appendChange:\n"
                + "              op: replace\n"
                + "              path: /observedInitializations\n"
                + "              val: {$add: [{$document: "
                + "/observedInitializations}, 1]}\n"
                + "          - $return: true\n"
                + "  fromChildChanged:\n"
                + "    type: Embedded Node Channel\n"
                + "    sourcePath: /child\n"
                + "    event:\n"
                + "      type: Coordination/Event\n"
                + "      kind: CatchUp/B Changed\n"
                + "  onChildChanged:\n"
                + "    type: Coordination/Sequential Workflow\n"
                + "    channel: fromChildChanged\n"
                + "    event:\n"
                + "      type: Coordination/Event\n"
                + "      kind: CatchUp/B Changed\n"
                + "    steps:\n"
                + "      - type: Coordination/Compute\n"
                + "        do:\n"
                + "          - $appendChange:\n"
                + "              op: replace\n"
                + "              path: /observedChanges\n"
                + "              val: {$add: [{$document: "
                + "/observedChanges}, 1]}\n"
                + "          - $return: true\n";
    }

    private static String collectionRootYaml(
            ExactBlueValue first,
            ExactBlueValue second) {
        return "name: static retained collection\n"
                + "children:\n"
                + "  first: " + first.json() + "\n"
                + "  second: " + second.json() + "\n"
                + "firstInitializations: 0\n"
                + "firstChanges: 0\n"
                + "secondChanges: 0\n"
                + "contracts:\n"
                + "  embedded:\n"
                + "    type: Process Embedded\n"
                + "    collectionPaths: [/children]\n"
                + collectionHandler(
                        "FirstInitialized",
                        "/children/first",
                        "CatchUp/B Initialized",
                        "/firstInitializations")
                + collectionHandler(
                        "FirstChanged",
                        "/children/first",
                        "CatchUp/B Changed",
                        "/firstChanges")
                + collectionHandler(
                        "SecondChanged",
                        "/children/second",
                        "CatchUp/B Changed",
                        "/secondChanges");
    }

    private static String collectionHandler(
            String label,
            String sourcePath,
            String eventKind,
            String counterPath) {
        return "  from" + label + ":\n"
                + "    type: Embedded Node Channel\n"
                + "    sourcePath: " + sourcePath + "\n"
                + "    event:\n"
                + "      type: Coordination/Event\n"
                + "      kind: " + eventKind + "\n"
                + "  on" + label + ":\n"
                + "    type: Coordination/Sequential Workflow\n"
                + "    channel: from" + label + "\n"
                + "    event:\n"
                + "      type: Coordination/Event\n"
                + "      kind: " + eventKind + "\n"
                + "    steps:\n"
                + "      - type: Coordination/Compute\n"
                + "        do:\n"
                + "          - $appendChange:\n"
                + "              op: replace\n"
                + "              path: " + counterPath + "\n"
                + "              val: {$add: [{$document: "
                + counterPath + "}, 1]}\n"
                + "          - $return: true\n";
    }

    private static String sourceYaml() {
        return sourceYaml(SOURCE, SOURCE_TIMELINE);
    }

    private static String sourceYaml(
            DocumentId sourceId,
            String sourceTimeline) {
        return """
                documentId: %s
                initializationCount: 0
                counter: 0
                contracts:
                  lifecycleChannel:
                    type:
                      blueId: %s
                    order: 0
                  onProcessingInitiated:
                    type: Coordination/Sequential Workflow
                    channel: lifecycleChannel
                    event:
                      type:
                        blueId: %s
                    order: 0
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /initializationCount
                              val: {$add: [{$document: /initializationCount}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: CatchUp/B Initialized
                          - $return: true
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: {$add: [{$document: /counter}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: CatchUp/B Changed
                          - $return: true
                """.formatted(
                sourceId.value(),
                LIFECYCLE_CHANNEL_BLUE_ID,
                LIFECYCLE_EVENT_BLUE_ID,
                sourceTimeline,
                ACTOR);
    }

    private enum Representation {
        INLINE,
        PURE_REFERENCE
    }

    private record RetainedCase(
            String label,
            ExactBlueValue value,
            long admittedSourceEpoch,
            List<Long> sourceEpochSuffix,
            long expectedInitializations,
            long expectedChanges,
            boolean current) {
    }

    private record SourceHistory(
            DocumentHandle source,
            List<RetainedCase> cases,
            List<String> historyBlueIds,
            Map<Long, String> receiptIdentities) {
    }

}
