package blue.coordination.sdk;

import blue.coordination.api.ProcessingStageContext;
import blue.coordination.api.DocumentId;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact selection ownership and detached evidence without a second semantic invocation. */
final class SelectedProcessingStageTest {
    private static final int BYTES = 32 * 1024 * 1024;

    @Test void globalReadinessDistinguishesAnUnrelatedRunnableRootFromAnEmptyRootWithoutExecutingEither() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            fixture.append(source, "rcp2/source", "tick", 100, "{}");
            // when
            var local = fixture.blue.processing().selectNextStage(parent).execute();
            var global = fixture.blue.processing().inspectReadiness();
            // then
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, local.disposition());
            assertFalse(global.quiescent()); assertTrue(global.paused()); assertFalse(global.blocked());
            assertEquals(0, source.snapshot().longAt("/counter"));
            fixture.blue.processing().processNextStage(source);
            var complete = fixture.blue.processing().inspectReadiness();
            assertTrue(complete.quiescent()); assertFalse(complete.paused()); assertFalse(complete.blocked());
            assertThrows(IllegalArgumentException.class, () -> new blue.coordination.api.ProcessingReadiness(true, true));
        }
    }

    @Test void globalReadinessDoesNotMistakeRetriableResourceWaitingInputForQuiescence() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            var source = fixture.blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            fixture.exact.put(source.blueId(), source.json());
            fixture.timelines.put("rcp2/source", fixture.blue.timelines().register("rcp2/source", "alice"));
            fixture.appendReference(source.blueId(), "rcp2/source", "tick", 10, "{}", false);
            fixture.append(parent, "rcp2/parent", "attach", 20, "child: {blueId: " + source.blueId() + "}");
            var waiting = fixture.blue.processing().processNextStage(parent);
            var before = parent.snapshot().blueId();
            // when
            var readiness = fixture.blue.processing().inspectReadiness();
            // then
            assertEquals(ProcessingStageResult.Disposition.WAITING, waiting.disposition());
            assertFalse(readiness.quiescent());
            // The retained LIVE input is still a protocol scheduling head. The host's
            // dependency wait independently prevents repeating this same failed attempt.
            assertTrue(readiness.paused()); assertFalse(readiness.blocked());
            assertEquals(before, parent.snapshot().blueId());
        }
    }

    @Test void exactManagedFairTurnIsFrozenWithoutFutureReadinessOrForeignOwnerClaims() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            String original = fixture.retain(source);
            var tick = fixture.append(source, "rcp2/source", "tick", 100, "{}");
            fixture.blue.processing().process(source, tick);
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            fixture.append(parent, "rcp2/parent", "attach", 200, "child: {blueId: " + original + "}");
            fixture.blue.processing().drainJournal(DrainBudget.unlimited());
            var work = fixture.blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow();
            var sourceHistory = fixture.history(source);
            var parentHistory = fixture.history(parent);
            var control = blue.coordination.internal.CoordinationTestControl.attach(fixture.blue.advanced().rawEngine());
            control.failOnceAt(blue.coordination.internal.CoordinationTestControl.FailurePoint.BEFORE_ROOTED_READINESS);
            // when
            var mismatch = assertThrows(blue.coordination.api.CoordinationException.class,
                    () -> fixture.blue.processing().selectManagedEpochApplicationStage("sha256:" + "0".repeat(64)));
            assertEquals(blue.coordination.api.CoordinationErrorCode.PROCESSING_SELECTION_MISMATCH, mismatch.code());
            assertEquals(parentHistory, fixture.history(parent));
            var selected = fixture.blue.processing().selectManagedEpochApplicationStage(work.workIdentity());
            var completed = selected.execute();
            // then
            assertEquals(ProcessingStageContext.Kind.MANAGED_HISTORY, selected.context().kind());
            assertEquals(List.of(parent.id()), selected.context().entryOwners().stream().map(ProcessingStageContext.Owner::documentId).toList());
            assertTrue(selected.context().causes().contains(work.workIdentity()));
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, completed.disposition());
            assertEquals(1, completed.managedEpochApplications().size());
            assertEquals(work.workIdentity(), completed.managedEpochApplicationAttempts().get(0).work().workIdentity());
            assertEquals(1, parent.snapshot().longAt("/seen"));
            assertEquals(sourceHistory, fixture.history(source));
            assertThrows(IllegalStateException.class, selected::execute);
            var fault = assertThrows(RuntimeException.class, () -> fixture.blue.processing().processNext(parent));
            assertTrue(control.isInjectedFailure(fault));
        }
    }

    @Test void acceptedCutoffIncludesEarlierStagesAndNeverConsumesLaterInput() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var root = fixture.start("source.yaml", "rcp2/source", Map.of());
            var first = fixture.append(root, "rcp2/source", "setCounter", 10, "counterValue: 1");
            var cutoff = fixture.append(root, "rcp2/source", "setCounter", 20, "counterValue: 2");
            var later = fixture.append(root, "rcp2/source", "setCounter", 30, "counterValue: 3");
            // when
            var earlier = fixture.blue.processing().selectNextStageThrough(root, cutoff).execute();
            var included = fixture.blue.processing().selectNextStageThrough(root, cutoff).execute();
            var complete = fixture.blue.processing().selectNextStageThrough(root, cutoff).execute();
            // then
            assertEquals(List.of(first.blueId()), earlier.selection().causes());
            assertEquals(List.of(cutoff.blueId()), included.selection().causes());
            assertEquals(cutoff.blueId(), complete.selection().inclusiveEntryBlueId());
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, complete.disposition());
            assertEquals(2, root.snapshot().longAt("/counter"));
            byte[] encoded = ProcessingStageStorage.encode(complete, BYTES);
            assertEquals(complete.selection(), ProcessingStageStorage.decode(encoded, BYTES).selection());
            assertArrayEquals(encoded, ProcessingStageStorage.encode(ProcessingStageStorage.decode(encoded, BYTES), BYTES));
            var next = fixture.blue.processing().selectNextStageThrough(root, later).execute();
            assertEquals(List.of(later.blueId()), next.selection().causes());
            assertEquals(3, root.snapshot().longAt("/counter"));
        }
    }

    @Test void frozenSelectionRequiresExecutionBeforeMutationAndExecutesOnlyOnce() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var root = fixture.start("source.yaml", "rcp2/source", Map.of());
            var input = fixture.append(root, "rcp2/source", "setCounter", 10, "counterValue: 23");
            var head = root.snapshot().blueId(); var rootId = root.id();
            var selected = fixture.blue.processing().selectNextStage(root);
            // when
            assertThrows(IllegalStateException.class, () -> fixture.blue.timelines().register("intervening", "alice"));
            var completed = selected.execute();
            // then
            assertEquals(ProcessingStageContext.Kind.JOURNAL, selected.context().kind());
            assertEquals(List.of(input.blueId()), selected.context().causes());
            assertEquals(List.of(rootId), selected.context().entryOwners().stream().map(ProcessingStageContext.Owner::documentId).toList());
            assertEquals(head, selected.context().entryOwners().get(0).headBlueId());
            assertEquals(selected.context(), completed.selection());
            assertEquals(List.of(rootId), completed.resultOwners());
            assertFalse(completed.selectionInvalidated());
            assertTrue(completed.entry(input).applied());
            assertEquals(23, root.snapshot().longAt("/counter"));
            assertThrows(IllegalStateException.class, selected::execute);
        }
    }

    @Test void foreignThreadAndClosedScopeCannotConsumeSelectedAuthority() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var root = fixture.start("source.yaml", "rcp2/source", Map.of());
            fixture.append(root, "rcp2/source", "setCounter", 10, "counterValue: 23");
            var selected = fixture.blue.processing().selectNextStage(root);
            var executor = Executors.newSingleThreadExecutor();
            // when
            try { executor.submit(() -> assertThrows(IllegalStateException.class, selected::execute)).get(10, TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); }
            fixture.blue.close();
            // then
            assertThrows(IllegalStateException.class, selected::execute);
        }
    }

    @Test void detachedStageSurvivesOwnerRetirementAndRejectsMalformedOrOversizedEvidence() throws Exception {
        // given
        ProcessingStageResult original; byte[] encoded; String identity;
        try (var fixture = new RootedSdkFixture()) {
            var root = fixture.start("source.yaml", "rcp2/source", Map.of());
            fixture.append(root, "rcp2/source", "setCounter", 10, "counterValue: 23");
            original = fixture.blue.processing().processNextStage(root);
            encoded = ProcessingStageStorage.encode(original, BYTES);
            identity = ProcessingStageStorage.selectionIdentity(original.selection(), BYTES);
        }
        // when
        var restored = ProcessingStageStorage.decode(encoded, BYTES);
        // then
        assertArrayEquals(encoded, ProcessingStageStorage.encode(restored, BYTES));
        assertEquals(identity, ProcessingStageStorage.selectionIdentity(restored.selection(), BYTES));
        assertEquals(original.selection(), restored.selection());
        assertEquals(original.stats(), restored.stats());
        assertEquals(original.entries().get(0).entry().blueId(), restored.entries().get(0).entry().blueId());
        assertEquals(original.resultOwners(), restored.resultOwners());
        assertThrows(RuntimeException.class, () -> ProcessingStageStorage.decode(Arrays.copyOf(encoded, encoded.length - 1), BYTES));
        assertThrows(RuntimeException.class, () -> ProcessingStageStorage.decode(encoded, 16));
        assertThrows(RuntimeException.class, () -> ProcessingStageStorage.decode(Arrays.copyOf(encoded, encoded.length + 1), BYTES));
    }

    @Test void resultIdentityExcludesWallTimeButBindsTheCompletedResult() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var root = fixture.start("source.yaml", "rcp2/source", Map.of());
            fixture.append(root, "rcp2/source", "setCounter", 10, "counterValue: 23");
            var result = fixture.blue.processing().processNextStage(root); var stats = result.stats(); var evidence = result.evidence();
            var measured = new ProcessingStats(stats.gas(), stats.committedTransitions(), stats.documentsOpened(),
                    stats.elapsedNanos() + 1, stats.documentStepOrder(), stats.counters());
            var copied = new DrainResult(result.entries(), measured, evidence.quiescent(), evidence.paused(), result.diagnostic(),
                    result.managedEpochApplications(), result.managedEpochApplicationAttempts(), result.managedEpochEvidenceFailures(),
                    result.rootedRetainedApplications());
            var other = new ProcessingStageResult(result.disposition(), copied, result.selection(), result.resultOwners(), result.selectionInvalidated());
            // when
            var identity = ProcessingStageStorage.resultIdentity(result, BYTES);
            // then
            assertEquals(identity, ProcessingStageStorage.resultIdentity(other, BYTES));
            assertFalse(Arrays.equals(ProcessingStageStorage.encode(result, BYTES), ProcessingStageStorage.encode(other, BYTES)));
            assertNotEquals(identity, ProcessingStageStorage.resultIdentity(fixture.blue.processing().processNextStage(root), BYTES));
        }
    }

    @Test void attachmentInvalidatesSelectionWithoutAcquiringIndependentSourceOwner() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            fixture.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + source.snapshot().blueId());
            // when
            var result = fixture.blue.processing().processNextStage(parent);
            // then
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, result.disposition());
            assertTrue(result.selectionInvalidated());
            assertEquals(List.of(parent.id()), result.resultOwners());
            assertEquals(List.of(parent.id()), result.selection().entryOwners().stream().map(ProcessingStageContext.Owner::documentId).toList());
            assertFalse(result.resultOwners().contains(source.id()));
            assertArrayEquals(ProcessingStageStorage.encode(result, BYTES),
                    ProcessingStageStorage.encode(ProcessingStageStorage.decode(ProcessingStageStorage.encode(result, BYTES), BYTES), BYTES));
        }
    }

    @Test void cyclicJoinExpandsOwnersAndSplitRetainsBothUntilPublication() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var b = fixture.startYaml(RootedSdkFixture.resource("cycle-b.yaml").replace("ownerChannel", "owner"), "rcp2/cycle");
            String aYaml = RootedSdkFixture.resource("cycle-a.yaml").replace("ownerChannel", "owner") + "\npeer:\n  blueId: " + b.snapshot().blueId() + "\n";
            var authoredA = blue.values().yaml(aYaml); fixture.exact.put(authoredA.blueId(), authoredA.json());
            var a = fixture.startYaml(aYaml, "rcp2/cycle");
            fixture.append(b, "rcp2/cycle", "connectA", 90, "a:\n  blueId: " + authoredA.blueId());
            var first = blue.processing().processNextStage(b);
            // when
            var join = blue.processing().processNextStage(b);
            var owners = List.of(a.id(), b.id()).stream().sorted(java.util.Comparator.comparing(DocumentId::value)).toList();
            fixture.append(b, "rcp2/cycle", "detachA", 110, "{}");
            var split = blue.processing().processNextStage(b);
            // then
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, first.disposition());
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, join.disposition());
            assertEquals(owners, join.resultOwners());
            assertEquals(owners, split.selection().entryOwners().stream().map(ProcessingStageContext.Owner::documentId).toList());
            assertEquals(owners, split.resultOwners());
            assertTrue(split.selectionInvalidated());
            assertArrayEquals(ProcessingStageStorage.encode(split, BYTES),
                    ProcessingStageStorage.encode(ProcessingStageStorage.decode(ProcessingStageStorage.encode(split, BYTES), BYTES), BYTES));
        }
    }

    @Test void noWorkIsSeparateFromCompletedInputAndRetainsItsExactPredecessor() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var root = fixture.start("source.yaml", "rcp2/source", Map.of());
            var selected = fixture.blue.processing().selectNextStage(root);
            // when
            var result = selected.execute();
            // then
            assertEquals(ProcessingStageContext.Kind.NONE, result.selection().kind());
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, result.disposition());
            assertTrue(result.selection().causes().isEmpty());
            assertEquals(root.snapshot().blueId(), result.selection().entryOwners().get(0).headBlueId());
            assertTrue(result.entries().isEmpty());
            assertEquals(result.selection(), ProcessingStageStorage.decode(ProcessingStageStorage.encode(result, BYTES), BYTES).selection());
        }
    }
}
