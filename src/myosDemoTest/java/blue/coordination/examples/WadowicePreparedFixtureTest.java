package blue.coordination.examples;

import blue.coordination.examples.scenarios.WadowiceHotelDinnerScenario;
import blue.coordination.examples.scenarios.WadowicePreparedFixture;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoCheckpoint;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsMeasuredWork;
import blue.coordination.engine.fastpath.ReferenceCutMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves prepared forks are replay-free and mutable branch state is private. */
final class WadowicePreparedFixtureTest {

    private static final WadowicePreparedFixture FIXTURE =
            WadowicePreparedFixture.shared();

    @Test
    void shouldBuildAllPurposefulCheckpointsInOneLinearPreparation() {
        // given
        WadowicePreparedFixture fixture = FIXTURE;

        // when
        MyOsMeasuredWork preparationWork = fixture.preparationWork();

        // then
        assertEquals(1, fixture.preparationExecutions());
        assertEquals(2, fixture.checkpoint().documentCount());
        assertEquals(5, fixture.checkpoint().timelineCount());
        assertEquals(11, fixture.checkpoint().journalEntryCount());
        assertEquals(7, fixture.conditionsCheckpoint().journalEntryCount());
        assertEquals(1, fixture.payNoteCheckpoint().journalEntryCount());
        assertEquals(0,
                fixture.beforePayNoteCheckpoint().journalEntryCount());
        assertEquals(19L, preparationWork
                .engine().processCompletions());
    }

    @Test
    void shouldGiveFirstSeenPayNoteForksUniqueCanonicalCursors() {
        // given
        try (WadowiceHotelDinnerScenario shapeWarmup =
                     FIXTURE.beforePayNoteBranch(
                             "first-seen-shape-warmup", 10_000L)) {
            shapeWarmup.appendPayNoteCampaignCursor();
            shapeWarmup.primePayNoteShape();
        }

        try (WadowiceHotelDinnerScenario first =
                     FIXTURE.beforePayNoteBranch(
                             "first-seen-fork-a", 20_000L);
             WadowiceHotelDinnerScenario second =
                     FIXTURE.beforePayNoteBranch(
                             "first-seen-fork-b", 30_000L)) {
            MyOsDemoEntry firstPrevious =
                    first.appendPayNoteCampaignCursor();
            MyOsDemoEntry secondPrevious =
                    second.appendPayNoteCampaignCursor();
            assertTrue(first.demo().process(firstPrevious)
                    .deliveries().isEmpty());
            assertTrue(second.demo().process(secondPrevious)
                    .deliveries().isEmpty());

            // when
            MyOsDemoEntry firstEntry = first.appendPayNoteEntry();
            MyOsMeasuredWork beforeFirstSeen =
                    first.demo().measuredWork();
            var firstDispatch = first.demo().process(firstEntry);
            ReferenceCutMetrics.Snapshot sparse = first.demo()
                    .measuredWork()
                    .minus(beforeFirstSeen)
                    .referenceCuts();
            MyOsDemoEntry secondEntry = second.appendPayNoteEntry();

            // then
            first.requirePayNoteAttachmentObservable(firstDispatch);
            assertTrue(sparse.compilations() <= 1L, sparse.toString());
            assertEquals(sparse.compilations(),
                    sparse.inventoryCompilations(), sparse.toString());
            assertEquals(4L, sparse.decisions(), sparse.toString());
            assertEquals(4L, sparse.sparseUses(), sparse.toString());
            assertEquals(0L, sparse.fullRootUses(), sparse.toString());
            assertTrue(sparse.cacheHits() >= 2L, sparse.toString());
            assertTrue(sparse.canonicalBatchReads() <= 1L,
                    sparse.toString());
            assertEquals(0L, sparse.canonicalSingleReads(),
                    sparse.toString());
            assertEquals(0L, sparse.plannedArtifactFallbacks(),
                    sparse.toString());
            assertEquals(2L,
                    sparse.plannedArtifactReuses()
                            + sparse.plannedArtifactNotApplicable(),
                    sparse.toString());
            assertEquals(2L, sparse.processRootSelections(),
                    sparse.toString());
            assertEquals(0L, sparse.identityFailures(), sparse.toString());
            assertTrue(sparse.fragmentMaterializationFraction() <= 0.20d,
                    sparse.toString());
            assertTrue(sparse.processFragmentMaterializationFraction()
                            <= 0.20d,
                    sparse.toString());
            assertNotEquals(firstPrevious.blueId(),
                    secondPrevious.blueId());
            assertNotEquals(firstEntry.blueId(), secondEntry.blueId());
            assertNotEquals(firstEntry.timestampMicros(),
                    secondEntry.timestampMicros());
            assertEquals(firstPrevious.blueId(), firstEntry.exactEntry()
                    .getAsNode("/prevEntry").getBlueId());
            assertEquals(secondPrevious.blueId(), secondEntry.exactEntry()
                    .getAsNode("/prevEntry").getBlueId());
        }
    }

    @Test
    void shouldForkWithoutParsingInitializingReadingOrReplayingHistory() {
        // given
        try (WadowiceHotelDinnerScenario branch =
                     FIXTURE.branch("fast-fork")) {

            // when
            MyOsMeasuredWork forkWork = branch.demo().measuredWork();

            // then
            assertEquals(0L, forkWork.sourceParses());
            assertEquals(0L, forkWork.documentInitializations());
            assertEquals(0L, forkWork.eventPreparations());
            assertEquals(0L, forkWork.eventSplits());
            assertEquals(0L, forkWork.routeIndexProbes());
            assertEquals(0L, forkWork.engine().plans());
            assertEquals(0L, forkWork.engine().processCompletions());
            assertEquals(0L, forkWork.storeSingleReads());
            assertEquals(0L, forkWork.storeBatchReads());
            assertEquals(FIXTURE.checkpoint().stateFingerprint(),
                    branch.demo().stateFingerprint());
            MyOsDemoCheckpoint branchCheckpoint = branch.demo().checkpoint();
            assertTrue(FIXTURE.checkpoint().sharesImmutableContentWith(
                    branchCheckpoint));
        }
    }

    @Test
    void shouldKeepOneMutatedBranchItsClosedSiblingAndSourceIsolated() {
        // given
        String preparedFingerprint = FIXTURE.checkpoint().stateFingerprint();
        try (WadowiceHotelDinnerScenario sibling =
                     FIXTURE.branch("isolated-sibling")) {
            String preparedHead = sibling.demo().currentRootBlueId(
                    WadowiceHotelDinnerScenario.ORDER);
            String siblingFingerprint = sibling.demo().stateFingerprint();

            try (WadowiceHotelDinnerScenario mutated =
                         FIXTURE.branch("isolated-mutated")) {
                assertEquals(preparedHead,
                        mutated.demo().currentRootBlueId(
                                WadowiceHotelDinnerScenario.ORDER));

                // when
                MyOsDemoEntry suffix = mutated.demo().append(
                        mutated.demo().timeline(
                                "examples/order/isolation-probe",
                                MyOsDemoActor.principal("isolation-probe")),
                        MyOsDemoOperation.operation("isolationProbe")
                                .through("isolationChannel")
                                .build());

                // then
                assertEquals(12, mutated.demo().journalEntryCount());
                assertEquals(suffix, mutated.demo().authoredEntries().get(11));
                assertEquals(preparedHead,
                        mutated.demo().currentRootBlueId(
                                WadowiceHotelDinnerScenario.ORDER));
                assertNotEquals(preparedFingerprint,
                        mutated.demo().stateFingerprint());
                MyOsDemoCheckpoint mutatedCheckpoint =
                        mutated.demo().checkpoint();
                assertTrue(FIXTURE.checkpoint().sharesImmutableContentWith(
                        mutatedCheckpoint));
                assertFalse(FIXTURE.checkpoint().sharesMutableStateWith(
                        mutatedCheckpoint));
            }

            // The mutated branch is closed. Its sibling and the immutable
            // source checkpoint remain independently usable and unchanged.
            MyOsDemoAssertions.assertValue(
                    sibling.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/done", false);
            MyOsDemoAssertions.assertValue(
                    sibling.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/amount/captured", 130000);
            assertEquals(siblingFingerprint,
                    sibling.demo().stateFingerprint());
            assertEquals(preparedFingerprint,
                    FIXTURE.checkpoint().stateFingerprint());
        }
    }
}
