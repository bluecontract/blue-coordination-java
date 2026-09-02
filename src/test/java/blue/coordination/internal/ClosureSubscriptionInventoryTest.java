package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.ChannelOccurrence;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.SubscriptionState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact Contracts subscription-state and copy-on-write publication proofs. */
final class ClosureSubscriptionInventoryTest {
    private static final long RANDOM_SEED = 0xC105_5EEDL;
    private static final String LANGUAGE_SPECIFICATION_IDENTITY =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CONTRACTS_SPECIFICATION_IDENTITY =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final DocumentId A = DocumentId.of("subscription-a");
    private static final DocumentId B = DocumentId.of("subscription-b");
    private static final String OWNER_TIMELINE =
            "closure-subscription-inventory/owner";
    private static final String OWNER_ACTOR = "owner";
    private static final String ADDED_TIMELINE =
            "closure-subscription-inventory/added";
    private static final String ADDED_ACTOR = "added";

    private static Fixture fixture;

    @BeforeAll
    static void executeGenuineContractsBatch() {
        fixture = executeFixture();
    }

    @Test
    void appliesVerifiedAddReplaceAndRemoveWithoutErasingDisconnectedRows() {
        // given

        ClosureProcessResult resultA = fixture.result(A);

        // when
        ClosureProcessResult resultB = fixture.result(B);

        // then
        assertEquals(EnumSet.allOf(SubscriptionDelta.Operation.class),
                operations(resultA));
        assertEquals(EnumSet.allOf(SubscriptionDelta.Operation.class),
                operations(resultB));
        assertEquals(3, resultA.subscriptionDeltas().size());
        assertEquals(3, resultB.subscriptionDeltas().size());

        assertFinalRows(resultA, fixture.after().closureSubscriptions());
        assertFinalRows(resultB, fixture.after().closureSubscriptions());
        assertEquals(4,
                fixture.after().closureSubscriptions().states().size());
        assertEquals(Set.of(A.value(), B.value()),
                fixture.after().closureSubscriptions().states().stream()
                        .map(state -> state.channelOccurrence()
                                .managedDocumentId().value())
                        .collect(java.util.stream.Collectors.toSet()));

        assertTrue(fixture.after().publicationReceipts().contains(
                fixture.publicationIdentities().get(A)));
        assertTrue(fixture.after().publicationReceipts().contains(
                fixture.publicationIdentities().get(B)));
        assertEquals(0L, fixture.before().requireHead(A).epoch());
        assertEquals(0L, fixture.before().requireHead(B).epoch());
        assertEquals(1L, fixture.after().requireHead(A).epoch());
        assertEquals(1L, fixture.after().requireHead(B).epoch());
    }

    @Test
    void rejectsMismatchedBeforeStateAndStaleDurableHead() {
        // given

        ClosureProcessResult result = fixture.result(A);
        SubscriptionState before = delta(
                result, SubscriptionDelta.Operation.REPLACE)
                .beforeSubscription();

        // when
        SubscriptionState conflicting = SubscriptionState.identified(
                before.channelOccurrence(),
                before.documentBlueId(),
                before.graphGeneration(),
                before.componentGeneration() + 1L);

        // then
        IllegalStateException stateFailure = assertThrows(
                IllegalStateException.class,
                () -> ClosureSubscriptionInventory.of(List.of(conflicting))
                        .apply(result));
        assertTrue(stateFailure.getMessage().contains(
                "before-state CAS mismatch"));

        InMemoryDocumentStore.PublicationSnapshot beforeAttempt =
                fixture.store().publicationSnapshot();
        ResultingDocument document = resultingDocument(result, A);
        MultiDocumentPublicationTransaction.AtomicPublicationCasException
                headFailure = assertThrows(
                        MultiDocumentPublicationTransaction
                                .AtomicPublicationCasException.class,
                        () -> fixture.store().beginAtomicPublication(
                                        "stale-subscription-head",
                                        beforeAttempt
                                                .occurrenceInventoryGeneration(),
                                        beforeAttempt
                                                .componentIndexGeneration())
                                .expectHead(A, 0L, document.beforeBlueId())
                                .expectGraphGeneration(
                                        A,
                                        beforeAttempt.graphGenerations()
                                                .require(A))
                                .stageClosureSubscriptionDeltas(result)
                                .commit());
        assertTrue(headFailure.getMessage().contains("Stale document head"));
        assertEquals(beforeAttempt,
                fixture.store().publicationSnapshot(),
                "a rejected COW attempt must not publish any surface");
    }

    @Test
    void validatesFinalDocumentGraphAndComponentGenerations() {
        // given

        ClosureProcessResult result = fixture.result(A);

        // when
        ResultingDocument document = resultingDocument(result, A);

        // then
        assertFinalStateRejected(
                result,
                state -> SubscriptionState.identified(
                        state.channelOccurrence(),
                        document.beforeBlueId(),
                        result.graphGeneration(),
                        document.componentGeneration()));
        assertFinalStateRejected(
                result,
                state -> SubscriptionState.identified(
                        state.channelOccurrence(),
                        document.afterBlueId(),
                        result.graphGeneration() + 1L,
                        document.componentGeneration()));
        assertFinalStateRejected(
                result,
                state -> SubscriptionState.identified(
                        state.channelOccurrence(),
                        document.afterBlueId(),
                        result.graphGeneration(),
                        document.componentGeneration() + 1L));
    }

    @Test
    void rebasesUnmentionedRowOnlyFromItsExactCapturedGraphFence() {
        // given
        ClosureProcessResult result = ContractsPublicComponentMergeSplitTest
                .topologyChangingResultForInventoryTest();
        SubscriptionState exemplar = result.subscriptionDeltas().stream()
                .map(delta -> delta.afterSubscription() != null
                        ? delta.afterSubscription()
                        : delta.beforeSubscription())
                .findFirst()
                .orElseThrow();
        DocumentId documentId = DocumentId.of(exemplar.channelOccurrence()
                .managedDocumentId().value());
        ResultingDocument document = resultingDocument(result, documentId);
        ChannelOccurrence retainedOccurrence = ChannelOccurrence.root(
                exemplar.channelOccurrence().managedDocumentId(),
                "retainedUnmentionedChannel",
                exemplar.channelOccurrence()
                        .effectiveRuntimeContributionBlueId(),
                exemplar.channelOccurrence().subscriptionHeaderBlueId());
        long capturedGeneration = result.platformCommitCompanion()
                .expectedInputGraphGeneration();
        assertTrue(capturedGeneration < result.graphGeneration());
        LinkedHashMap<DocumentId, Long> exactGraphFences =
                new LinkedHashMap<>();
        result.platformCommitCompanion().expectedInputDocuments().forEach(
                input -> exactGraphFences.put(
                        DocumentId.of(input.documentId().value()),
                        capturedGeneration));
        SubscriptionState retained = SubscriptionState.identified(
                retainedOccurrence,
                document.afterBlueId(),
                capturedGeneration,
                document.componentGeneration());

        // when
        ClosureSubscriptionInventory rebased =
                ClosureSubscriptionInventory.of(List.of(retained)).apply(
                        result, exactGraphFences);

        // then
        SubscriptionState resulting = rebased.statesFor(documentId).stream()
                .filter(state -> state.channelOccurrence().rawChannelKey()
                        .equals("retainedUnmentionedChannel"))
                .findFirst()
                .orElseThrow();
        assertEquals(retainedOccurrence.channelOccurrenceIdentity(),
                resulting.channelOccurrence().channelOccurrenceIdentity());
        assertEquals(document.afterBlueId(), resulting.documentBlueId());
        assertEquals(result.graphGeneration(), resulting.graphGeneration());
        assertEquals(document.componentGeneration(),
                resulting.componentGeneration());
        assertFalse(retained.subscriptionIdentity().equals(
                resulting.subscriptionIdentity()));

        IllegalStateException staleFence = assertThrows(
                IllegalStateException.class,
                () -> {
                    LinkedHashMap<DocumentId, Long> staleGraphFences =
                            new LinkedHashMap<>(exactGraphFences);
                    staleGraphFences.put(
                            documentId,
                            Math.addExact(capturedGeneration, 1L));
                    ClosureSubscriptionInventory.of(List.of(retained))
                            .apply(result, staleGraphFences);
                });
        assertTrue(staleFence.getMessage().contains(
                "stale after publication"));
    }

    @Test
    void publishesRoutesWithExactCheckpointAndStartAfterIntervals() {
        // given
        OperationRouteIndex routes = fixture.routes();

        // when
        var atBoundary = routes.selectDirectDeliveries(
                fixture.addedAtBoundary()).documentIds();
        var afterBoundary = routes.selectDirectDeliveries(
                fixture.addedAfterBoundary()).documentIds();
        var retiring = routes.selectDirectDeliveries(
                fixture.retiringAfterBoundary()).documentIds();

        // then
        assertEquals(List.of(), atBoundary,
                "a Channel added by an event cannot receive that event");
        assertEquals(List.of(A, B), afterBoundary);
        assertEquals(List.of(), retiring);
        assertTrue(routes.generation()
                > fixture.routeGenerationBefore());

        for (DocumentId documentId : List.of(A, B)) {
            Map<String, blue.language.processor.SubscriptionDelta.Entry>
                    before = legacySubscriptions(
                            fixture.subscriptionsBefore().get(documentId));
            Map<String, blue.language.processor.SubscriptionDelta.Entry>
                    after = legacySubscriptions(fixture.store()
                            .require(documentId).activeSubscriptions());
            assertEquals(Set.of("addedChannel", "ownerChannel"),
                    after.keySet());

            blue.language.processor.SubscriptionDelta.Entry ownerBefore =
                    before.get("ownerChannel");
            blue.language.processor.SubscriptionDelta.Entry ownerAfter =
                    after.get("ownerChannel");
            assertEquals(ownerBefore.activationRootRevision(),
                    ownerAfter.activationRootRevision());
            assertEquals(ownerBefore.startAfterExternalOrderKey(),
                    ownerAfter.startAfterExternalOrderKey());

            blue.language.processor.SubscriptionDelta.Entry added =
                    after.get("addedChannel");
            assertEquals(fixture.trigger().sourceOrderKey(),
                    added.startAfterExternalOrderKey());
            assertEquals(Long.valueOf(resultingDocument(
                            fixture.result(documentId), documentId).epoch()),
                    added.activationRootRevision());
            assertTrue(fixture.objects().contains(
                    ownerAfter.checkpointDomainBlueId()));
            assertTrue(fixture.objects().contains(
                    added.checkpointDomainBlueId()));
        }
    }

    @Test
    void graphGenerationUpdateCopiesOnlyTheAffectedPersistentPath() {
        // given
        ArrayList<DocumentId> documents = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            documents.add(DocumentId.of(
                    "ambient-graph-%04d".formatted(index)));
        }
        documents.add(A);
        ClosureGraphGenerationInventory before =
                ClosureGraphGenerationInventory.empty()
                        .retainingDocuments(documents);
        ClosureProcessResult result = fixture.result(A);

        // when
        ClosureGraphGenerationInventory after = before.apply(result);

        // then
        assertEquals(0L, before.require(A));
        assertEquals(result.graphGeneration(), after.require(A));
        assertEquals(0L, after.require(documents.get(500)));
        assertEquals(before.documents(), after.documents());
        assertNotSame(
                before.rootIdentityForTesting(),
                after.rootIdentityForTesting());
        assertTrue(after.lastOperationComparisonsForTesting() < 32,
                () -> "unexpected exact-key comparisons: "
                        + after.lastOperationComparisonsForTesting());
        assertTrue(after.lastOperationCopiedNodesForTesting() < 16,
                () -> "unexpected copied graph nodes: "
                        + after.lastOperationCopiedNodesForTesting());
        assertTrue(before.sharedNodeCountForTesting(after) > 980,
                "the 1,000 unrelated graph rows must remain shared");
        assertTrue(after.lookupStepsForTesting(documents.get(500)) < 16);
        before.assertStructurallyValidForTesting();
        after.assertStructurallyValidForTesting();
    }

    @Test
    void randomizedGraphRetentionMatchesCanonicalMapWithoutAmbientReads() {
        // given
        ArrayList<DocumentId> documents = new ArrayList<>();
        for (int index = 0; index < 1_024; index++) {
            documents.add(DocumentId.of(
                    "random-graph-%04d".formatted(index)));
        }
        documents.add(A);
        ClosureGraphGenerationInventory source =
                ClosureGraphGenerationInventory.empty()
                        .retainingDocuments(documents)
                        .apply(fixture.result(A));
        Random random = new Random(RANDOM_SEED);

        // when
        for (int step = 0; step < 256; step++) {
            TreeSet<DocumentId> selected = new TreeSet<>(
                    EmbeddingBinding.DOCUMENT_ORDER);
            while (selected.size() < 16) {
                selected.add(documents.get(
                        random.nextInt(documents.size())));
            }
            ClosureGraphGenerationInventory retained =
                    source.retainingDocuments(selected);
            TreeMap<DocumentId, Long> expected = new TreeMap<>(
                    EmbeddingBinding.DOCUMENT_ORDER);
            for (DocumentId documentId : selected) {
                expected.put(
                        documentId,
                        documentId.equals(A)
                                ? fixture.result(A).graphGeneration()
                                : 0L);
            }
            assertEquals(expected, retained.generations());
            assertEquals(List.copyOf(expected.keySet()), retained.documents());
            assertTrue(retained.lastOperationComparisonsForTesting() < 512);
            assertTrue(retained.lastOperationCopiedNodesForTesting() < 512);
            retained.assertStructurallyValidForTesting();
        }

        // then
        assertEquals(fixture.result(A).graphGeneration(), source.require(A));
        assertEquals(0L, source.require(documents.get(700)));
        source.assertStructurallyValidForTesting();
    }

    @Test
    void subscriptionDeltaSharesUnrelatedRowsAndReportsAllIndexWork() {
        // given
        ClosureProcessResult result = fixture.result(A);
        SubscriptionState template = delta(
                result, SubscriptionDelta.Operation.REPLACE)
                .beforeSubscription();
        ArrayList<SubscriptionState> initial = new ArrayList<>();
        result.subscriptionDeltas().stream()
                .map(SubscriptionDelta::beforeSubscription)
                .filter(Objects::nonNull)
                .forEach(initial::add);
        ArrayList<SubscriptionState> ambient = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            SubscriptionState state = ambientState(
                    index, 0, template);
            ambient.add(state);
            initial.add(state);
        }
        ClosureSubscriptionInventory before =
                ClosureSubscriptionInventory.of(initial);

        // when
        ClosureSubscriptionInventory after = before.apply(result);

        // then
        assertFinalRows(result, after);
        DocumentId ambientDocument = ambientDocument(500);
        assertSame(
                ambient.get(500),
                after.statesFor(ambientDocument).get(0));
        assertNotSame(
                before.slotRootIdentityForTesting(),
                after.slotRootIdentityForTesting());
        assertTrue(after.lastOperationComparisonsForTesting() < 256,
                () -> "unexpected subscription-index comparisons: "
                        + after.lastOperationComparisonsForTesting());
        assertTrue(after.lastOperationCopiedNodesForTesting() < 192,
                () -> "unexpected copied subscription-index nodes: "
                        + after.lastOperationCopiedNodesForTesting());
        assertEquals(2, after.lastOperationVisitedRowsForTesting(),
                "final validation must visit only A's resulting rows");
        assertTrue(before.sharedSlotNodeCountForTesting(after) > 950,
                "the 1,000 unrelated subscription rows must remain shared");
        assertTrue(after.slotLookupStepsForTesting(
                ambientDocument, "ambient-channel-0") < 16);
        assertEquals(2, before.statesFor(A).size(),
                "the old persistent snapshot must remain unchanged");
        before.assertStructurallyValidForTesting();
        after.assertStructurallyValidForTesting();

        ClosureSubscriptionInventory one =
                before.retainingDocuments(List.of(ambientDocument));
        assertEquals(List.of(ambient.get(500)), one.states());
        assertTrue(one.lastOperationComparisonsForTesting() < 32,
                "one exact document lookup must ignore 1,000 ambient rows");
        assertTrue(one.lastOperationCopiedNodesForTesting() < 16);
        assertEquals(1, one.lastOperationVisitedRowsForTesting());
    }

    @Test
    void retainsProcessorOnlyEmbeddedDemandByExactSourcePath() {
        // given
        ClosureSubscriptionInventory before =
                ClosureSubscriptionInventory.empty()
                        .replaceEmbeddedDemands(
                                A,
                                List.of(new ClosureSubscriptionInventory
                                        .EmbeddedDemand(
                                                "fromChild",
                                                "/child",
                                                ClosureSubscriptionInventory
                                                        .EmbeddedDemandMode
                                                        .EXACT,
                                                "embedded-contribution")));

        // when
        ClosureSubscriptionInventory retained = before.retainingDocuments(
                List.of(A));
        ClosureSubscriptionInventory dropped = before.retainingDocuments(
                List.of(B));

        // then
        assertTrue(retained.hasEmbeddedDemand(A, "/child"));
        assertFalse(retained.hasEmbeddedDemand(A, "/other"));
        assertFalse(dropped.hasEmbeddedDemand(A, "/child"));
        assertTrue(retained.statesFor(A).isEmpty(),
                "processor-only demand must not require an external "
                        + "subscription row");
    }

    @Test
    void embeddedDemandModesMatchDecodedPointerSegments() {
        // given
        ClosureSubscriptionInventory.EmbeddedDemand exact = demand(
                "exact", "/orders/o1",
                ClosureSubscriptionInventory.EmbeddedDemandMode.EXACT);
        ClosureSubscriptionInventory.EmbeddedDemand all = demand(
                "all", "/",
                ClosureSubscriptionInventory.EmbeddedDemandMode
                        .ALL_DESCENDANTS);
        ClosureSubscriptionInventory.EmbeddedDemand direct = demand(
                "direct", "/teams/a~1b",
                ClosureSubscriptionInventory.EmbeddedDemandMode
                        .COLLECTION_DIRECT);
        ClosureSubscriptionInventory.EmbeddedDemand descendants = demand(
                "descendants", "/teams/a~0b",
                ClosureSubscriptionInventory.EmbeddedDemandMode
                        .COLLECTION_DESCENDANTS);

        // then
        assertTrue(exact.matches("/orders/o1"));
        assertFalse(exact.matches("/orders/o1/payment"));
        assertTrue(all.matches("/orders/o1/payment"));
        assertFalse(all.matches("/"));
        assertTrue(direct.matches("/teams/a~1b/member"));
        assertFalse(direct.matches("/teams/a/b/member"));
        assertFalse(direct.matches("/teams/a~1b/member/nested"));
        assertTrue(descendants.matches("/teams/a~0b/member/nested"));
        assertFalse(descendants.matches("/teams/a~0b"));
        assertFalse(descendants.matches("/teams/a~0b-old/member"));
    }

    @Test
    void sameCollectionPathRetainsEveryModeRegardlessOfInsertionOrder() {
        // given
        ClosureSubscriptionInventory.EmbeddedDemand direct = demand(
                "direct", "/orders",
                ClosureSubscriptionInventory.EmbeddedDemandMode
                        .COLLECTION_DIRECT);
        ClosureSubscriptionInventory.EmbeddedDemand descendants = demand(
                "descendants", "/orders",
                ClosureSubscriptionInventory.EmbeddedDemandMode
                        .COLLECTION_DESCENDANTS);

        // when
        ClosureSubscriptionInventory forward =
                ClosureSubscriptionInventory.empty()
                        .replaceEmbeddedDemands(
                                A, List.of(direct, descendants));
        ClosureSubscriptionInventory reverse =
                ClosureSubscriptionInventory.empty()
                        .replaceEmbeddedDemands(
                                A, List.of(descendants, direct));

        // then
        assertEquals(2, forward.embeddedDemandsFor(A).size());
        assertEquals(2, reverse.embeddedDemandsFor(A).size());
        assertTrue(forward.hasEmbeddedDemand(
                A, "/orders/o1/payment"));
        assertTrue(reverse.hasEmbeddedDemand(
                A, "/orders/o1/payment"));
        assertFalse(forward.hasEmbeddedDemand(A, "/orders-old/o1"));
        assertFalse(reverse.hasEmbeddedDemand(A, "/orders-old/o1"));
    }

    @Test
    void duplicateEmbeddedDemandChannelKeyFailsClosed() {
        // given
        ClosureSubscriptionInventory.EmbeddedDemand exact = demand(
                "same", "/orders/o1",
                ClosureSubscriptionInventory.EmbeddedDemandMode.EXACT);
        ClosureSubscriptionInventory.EmbeddedDemand collection = demand(
                "same", "/orders",
                ClosureSubscriptionInventory.EmbeddedDemandMode
                        .COLLECTION_DIRECT);

        // then
        assertThrows(IllegalArgumentException.class,
                () -> ClosureSubscriptionInventory.empty()
                        .replaceEmbeddedDemands(
                                A, List.of(exact, collection)));
    }

    @Test
    void randomizedSubscriptionRetentionMatchesCanonicalSlotProjection() {
        // given
        SubscriptionState template = delta(
                fixture.result(A), SubscriptionDelta.Operation.REPLACE)
                .beforeSubscription();
        ArrayList<SubscriptionState> rows = new ArrayList<>();
        ArrayList<DocumentId> documents = new ArrayList<>();
        for (int document = 0; document < 256; document++) {
            documents.add(ambientDocument(document));
            rows.add(ambientState(document, 0, template));
            rows.add(ambientState(document, 1, template));
        }
        ArrayList<SubscriptionState> insertionOrder =
                new ArrayList<>(rows);
        Collections.shuffle(
                insertionOrder, new Random(RANDOM_SEED ^ 0x1A_5E47L));
        ClosureSubscriptionInventory source =
                ClosureSubscriptionInventory.of(insertionOrder);
        Random random = new Random(RANDOM_SEED ^ 0x51_07L);

        // when
        for (int step = 0; step < 256; step++) {
            LinkedHashSet<DocumentId> selected = new LinkedHashSet<>();
            while (selected.size() < 16) {
                selected.add(documents.get(random.nextInt(documents.size())));
            }
            Set<String> selectedIds = selected.stream()
                    .map(DocumentId::value)
                    .collect(java.util.stream.Collectors.toSet());
            List<SubscriptionState> expected = source.states().stream()
                    .filter(state -> selectedIds.contains(
                            state.channelOccurrence()
                                    .managedDocumentId().value()))
                    .toList();
            ClosureSubscriptionInventory retained =
                    source.retainingDocuments(selected);
            assertEquals(expected, retained.states());
            for (DocumentId documentId : selected) {
                assertEquals(2, retained.statesFor(documentId).size());
            }
            assertTrue(retained.lastOperationComparisonsForTesting() < 2_048);
            assertTrue(retained.lastOperationCopiedNodesForTesting() < 2_048);
            assertEquals(32, retained.lastOperationVisitedRowsForTesting());
            retained.assertStructurallyValidForTesting();
        }

        // then
        assertEquals(rows, source.states());
        source.assertStructurallyValidForTesting();
    }

    private static void assertFinalRows(
            ClosureProcessResult result,
            ClosureSubscriptionInventory inventory) {
        DocumentId documentId = DocumentId.of(
                result.resultingDocuments().get(0).documentId().value());
        Map<String, SubscriptionState> actual = new LinkedHashMap<>();
        inventory.statesFor(documentId).forEach(state -> actual.put(
                state.channelOccurrence().rawChannelKey(), state));
        assertEquals(Set.of("addedChannel", "ownerChannel"),
                actual.keySet());
        assertFalse(actual.containsKey("retiringChannel"));
        for (SubscriptionDelta delta : result.subscriptionDeltas()) {
            if (delta.afterSubscription() == null) {
                assertFalse(actual.containsKey(delta.beforeSubscription()
                        .channelOccurrence().rawChannelKey()));
            } else {
                String channelKey = delta.afterSubscription()
                        .channelOccurrence().rawChannelKey();
                assertEquals(delta.afterSubscription().subscriptionIdentity(),
                        actual.get(channelKey).subscriptionIdentity());
            }
        }
    }

    private static DocumentId ambientDocument(int index) {
        return DocumentId.of("ambient-subscription-%04d".formatted(index));
    }

    private static SubscriptionState ambientState(
            int document,
            int channel,
            SubscriptionState template) {
        ChannelOccurrence occurrence = ChannelOccurrence.root(
                new blue.language.processor.closure.DocumentId(
                        ambientDocument(document).value()),
                "ambient-channel-" + channel,
                template.channelOccurrence()
                        .effectiveRuntimeContributionBlueId(),
                template.channelOccurrence().subscriptionHeaderBlueId());
        return SubscriptionState.identified(
                occurrence,
                template.documentBlueId(),
                template.graphGeneration(),
                template.componentGeneration());
    }

    private static void assertFinalStateRejected(
            ClosureProcessResult result,
            Function<SubscriptionState, SubscriptionState> staleState) {
        SubscriptionState exemplar = delta(
                result, SubscriptionDelta.Operation.ADD).afterSubscription();
        ChannelOccurrence occurrence = ChannelOccurrence.root(
                exemplar.channelOccurrence().managedDocumentId(),
                "unmentionedChannel",
                exemplar.channelOccurrence()
                        .effectiveRuntimeContributionBlueId(),
                exemplar.channelOccurrence().subscriptionHeaderBlueId());
        SubscriptionState validShape = SubscriptionState.identified(
                occurrence,
                exemplar.documentBlueId(),
                exemplar.graphGeneration(),
                exemplar.componentGeneration());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ClosureSubscriptionInventory.of(
                                List.of(staleState.apply(validShape)))
                        .apply(result));
        assertTrue(failure.getMessage().contains(
                "stale after publication"));
    }

    private static EnumSet<SubscriptionDelta.Operation> operations(
            ClosureProcessResult result) {
        EnumSet<SubscriptionDelta.Operation> operations =
                EnumSet.noneOf(SubscriptionDelta.Operation.class);
        result.subscriptionDeltas().forEach(
                delta -> operations.add(delta.operation()));
        return operations;
    }

    private static Map<String, blue.language.processor.SubscriptionDelta.Entry>
            legacySubscriptions(
                    List<blue.language.processor.SubscriptionDelta.Entry>
                            subscriptions) {
        Map<String, blue.language.processor.SubscriptionDelta.Entry> result =
                new LinkedHashMap<>();
        subscriptions.forEach(entry -> result.put(entry.channelKey(), entry));
        return result;
    }

    private static SubscriptionDelta delta(
            ClosureProcessResult result,
            SubscriptionDelta.Operation operation) {
        return result.subscriptionDeltas().stream()
                .filter(candidate -> candidate.operation() == operation)
                .findFirst()
                .orElseThrow();
    }

    private static ResultingDocument resultingDocument(
            ClosureProcessResult result,
            DocumentId documentId) {
        return result.resultingDocuments().stream()
                .filter(document -> document.documentId().value()
                        .equals(documentId.value()))
                .findFirst()
                .orElseThrow();
    }

    private static Fixture executeFixture() {
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        try (BlueRuntime runtime = BlueRuntime.create(objects, metrics)) {
            EmbeddedOnlyLayoutBuilder layouts =
                    new EmbeddedOnlyLayoutBuilder(runtime, objects, metrics);
            DocumentTransitionProcessor transitionProcessor =
                    new DocumentTransitionProcessor(
                            runtime,
                            objects,
                            layouts,
                            metrics,
                            ignored -> { });
            InMemoryDocumentStore store = new InMemoryDocumentStore();
            DocumentSession sessionA = admit(
                    transitionProcessor, A, 1_900_000_000_000_001L);
            DocumentSession sessionB = admit(
                    transitionProcessor, B, 1_900_000_000_000_002L);
            store.insert(sessionA);
            store.insert(sessionB);
            ManagedEpochReceiptTestFixtures.seedInitialization(
                    store, sessionA, 20_000L);
            ManagedEpochReceiptTestFixtures.seedInitialization(
                    store, sessionB, 30_000L);
            seedAcyclicComponent(store, runtime, sessionA);
            seedAcyclicComponent(store, runtime, sessionB);

            OperationRouteIndex routes = new OperationRouteIndex(
                    metrics,
                    documentId -> store.find(documentId).orElse(null));
            routes.replace(
                    A, sessionA.layout().routingSurface(),
                    sessionA.activeSubscriptions());
            routes.replace(
                    B, sessionB.layout().routingSurface(),
                    sessionB.activeSubscriptions());
            Map<DocumentId,
                    List<blue.language.processor.SubscriptionDelta.Entry>>
                    subscriptionsBefore = Map.of(
                            A, List.copyOf(sessionA.activeSubscriptions()),
                            B, List.copyOf(sessionB.activeSubscriptions()));
            WholeRequestEntryFactory entries = new WholeRequestEntryFactory(
                    runtime, objects, metrics);
            TimelineEntry entry = entries.create(
                            new Timeline(OWNER_TIMELINE, OWNER_ACTOR),
                            null,
                            Operation.yaml(
                                    "reconfigure",
                                    "ownerChannel",
                                    addedChannelRequest()),
                            1_900_000_000_000_003L,
                            1L,
                            1L);
            long routeGenerationBefore = routes.generation();

            InMemoryDocumentStore.PublicationSnapshot before =
                    store.publicationSnapshot();
            ContractsClosureProfile profile =
                    ContractsClosureProfile.release10(
                            LANGUAGE_SPECIFICATION_IDENTITY,
                            CONTRACTS_SPECIFICATION_IDENTITY,
                            List.of(A, B));
            List<ContractsClosureAdapter.CohortOutcome> outcomes;
            try (ContractsClosureAdapter adapter =
                    new ContractsClosureAdapter(
                            runtime,
                            objects,
                            layouts,
                            store,
                            routes,
                            profile)) {
                ContractsClosureAdapter.FrozenBatch batch =
                        adapter.capture(entry);
                assertEquals(2, batch.invocations().size());
                outcomes = adapter.processAndPublish(batch);
            }

            Map<DocumentId, ClosureProcessResult> results =
                    new LinkedHashMap<>();
            Map<DocumentId, String> publicationIdentities =
                    new LinkedHashMap<>();
            for (ContractsClosureAdapter.CohortOutcome outcome : outcomes) {
                assertTrue(outcome.published(), () -> outcome.attempt()
                        .isComplete()
                        ? outcome.attempt().processResult().status() + ": "
                        + outcome.attempt().processResult().diagnostic()
                        .message() + " " + outcome.attempt().processResult()
                        .diagnostic().details()
                        : "needs " + outcome.attempt()
                        .requiredExactBlueIds());
                assertTrue(outcome.attempt().isComplete());
                ClosureProcessResult result = outcome.attempt().processResult();
                assertEquals(ProcessorStatus.SUCCESS, result.status());
                assertTrue(result.commits());
                assertEquals(1, outcome.members().size());
                results.put(outcome.members().get(0), result);
                publicationIdentities.put(
                        outcome.members().get(0),
                        outcome.publicationIdentity());
            }
            assertEquals(Set.of(A, B), results.keySet());
            TimelineEntry addedAfterBoundary = entries.create(
                    new Timeline(ADDED_TIMELINE, ADDED_ACTOR),
                    null,
                    Operation.yaml(
                            "addedOperation", "addedChannel", "{}"),
                    1_900_000_000_000_004L,
                    2L,
                    1L);
            TimelineEntry retiringAfterBoundary = entries.create(
                    new Timeline(
                            "closure-subscription-inventory/retiring/"
                                    + A.value(),
                            "retiring"),
                    null,
                    Operation.yaml(
                            "retiringOperation", "retiringChannel", "{}"),
                    1_900_000_000_000_004L,
                    3L,
                    1L);
            return new Fixture(
                    store,
                    before,
                    store.publicationSnapshot(),
                    Map.copyOf(results),
                    Map.copyOf(publicationIdentities),
                    routes,
                    routeGenerationBefore,
                    subscriptionsBefore,
                    objects,
                    entry,
                    withSourceOrder(
                            addedAfterBoundary, entry.sourceOrderKey()),
                    addedAfterBoundary,
                    retiringAfterBoundary);
        }
    }

    private static DocumentSession admit(
            DocumentTransitionProcessor processor,
            DocumentId documentId,
            long sourceOrder) {
        return processor.admit(
                documentId,
                document(documentId),
                ExternalOrderKey.of(List.of(sourceOrder, "admission")),
                blue.coordination.api.CoordinationEngine.AdmissionPolicy
                        .FROM_NOW);
    }

    private static void seedAcyclicComponent(
            InMemoryDocumentStore store,
            BlueRuntime runtime,
            DocumentSession session) {
        InMemoryDocumentStore.PublicationSnapshot snapshot =
                store.publicationSnapshot();
        Node document = session.currentRevision().after().copyNode();
        ManagedDocumentSnapshot managed = new ManagedDocumentSnapshot(
                new blue.language.processor.closure.DocumentId(
                        session.documentId().value()),
                session.currentRevision().after().blueId(),
                document,
                runtime.documentProcessor().isInitialized(document),
                false,
                true,
                session.epoch(),
                0L);
        ComponentSnapshot component =
                ClosureEvidenceFactory.acyclicComponent(managed);
        store.beginAtomicPublication(
                        "seed-subscription-component|"
                                + session.documentId().value(),
                        snapshot.occurrenceInventoryGeneration(),
                        snapshot.componentIndexGeneration())
                .expectHead(
                        session.documentId(),
                        session.epoch(),
                        session.currentRevision().after().blueId())
                .stageComponentStates(List.of(component))
                .commit();
    }

    private static String document(DocumentId documentId) {
        return """
                documentId: %s
                state: initial
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  retiringChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: closure-subscription-inventory/retiring/%s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: retiring
                  retiringOperation:
                    type: Coordination/Sequential Workflow Operation
                    channel: retiringChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $return: true
                  reconfigure:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      addedChannel: {}
                      addedHandler: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /contracts/addedChannel
                              val: {$binding: event/message/request/addedChannel}
                          - $appendChange:
                              op: add
                              path: /contracts/addedOperation
                              val: {$binding: event/message/request/addedHandler}
                          - $appendChange:
                              op: remove
                              path: /contracts/retiringOperation
                          - $appendChange:
                              op: remove
                              path: /contracts/retiringChannel
                          - $appendChange:
                              op: replace
                              path: /state
                              val: updated
                          - $return: true
                """.formatted(
                documentId.value(),
                OWNER_TIMELINE,
                OWNER_ACTOR,
                documentId.value());
    }

    private static String addedChannelRequest() {
        return """
                addedChannel:
                  type: Coordination/Timeline Channel
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: %s
                  actor:
                    type: MyOS/Principal Actor
                    accountId: %s
                addedHandler:
                  type: Coordination/Sequential Workflow Operation
                  channel: addedChannel
                  request: {}
                  steps:
                    - type: Coordination/Compute
                      do:
                        - $return: true
                """.formatted(ADDED_TIMELINE, ADDED_ACTOR);
    }

    private static TimelineEntry withSourceOrder(
            TimelineEntry entry,
            ExternalOrderKey sourceOrder) {
        return new TimelineEntry(
                entry.exactEvent(),
                entry.exactRequest(),
                sourceOrder,
                sourceOrder,
                entry.timeline(),
                entry.operation(),
                entry.channel(),
                entry.timestampMicros(),
                entry.globalSequence(),
                entry.timelineSequence());
    }

    private static ClosureSubscriptionInventory.EmbeddedDemand demand(
            String rawChannelKey,
            String selectorPath,
            ClosureSubscriptionInventory.EmbeddedDemandMode mode) {
        return new ClosureSubscriptionInventory.EmbeddedDemand(
                rawChannelKey,
                selectorPath,
                mode,
                "embedded-contribution-" + rawChannelKey);
    }

    private record Fixture(
            InMemoryDocumentStore store,
            InMemoryDocumentStore.PublicationSnapshot before,
            InMemoryDocumentStore.PublicationSnapshot after,
            Map<DocumentId, ClosureProcessResult> results,
            Map<DocumentId, String> publicationIdentities,
            OperationRouteIndex routes,
            long routeGenerationBefore,
            Map<DocumentId,
                    List<blue.language.processor.SubscriptionDelta.Entry>>
                    subscriptionsBefore,
            WholeObjectStore objects,
            TimelineEntry trigger,
            TimelineEntry addedAtBoundary,
            TimelineEntry addedAfterBoundary,
            TimelineEntry retiringAfterBoundary) {
        ClosureProcessResult result(DocumentId documentId) {
            return results.get(documentId);
        }
    }
}
