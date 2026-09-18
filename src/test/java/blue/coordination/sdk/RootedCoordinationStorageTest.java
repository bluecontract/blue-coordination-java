package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.ColdStorageJournalFixture;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.coordination.internal.InsertionOrderedStorage;
import blue.coordination.internal.RootedEngineStorage;
import blue.coordination.internal.RootedCalculationFixture;
import blue.language.processor.NoncommittingExecutionException;
import blue.language.processor.closure.ClosureProcessResultStorageCodec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual complete SDK/engine installation; all cold state comes from immutable bytes plus the exact journal. */
final class RootedCoordinationStorageTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final RootedCoordinationStorage.Limits LIMITS = new RootedCoordinationStorage.Limits(
            // Same complete-receipt capacities as RootedEngineStorageTest;
            // a cyclic publication contains more than a 256 KiB scalar row.
            new RootedEngineStorage.Limits(40 * 1024 * 1024, 64 * 1024, MAX, 8192, 32,
                    MAX, 256, 256L * 1024 * 1024, 100, 100, 100, 256L * 1024 * 1024,
                    40 * 1024 * 1024, MAX, 16),
            new RootedCoordinationStorage.SdkLimits(new InsertionOrderedStorage.Limits(512 * 1024, 64 * 1024,
                    64 * 1024, 8192, 32, 256 * 1024, 4L * 1024 * 1024, 100), MAX, 128 * 1024, MAX, 100, 256 * 1024));
    private static final SdkStorageCodec CODEC = new SdkStorageCodec(new Object(), MAX);

    @Test void completedStageCanBeStoredAndColdReopenedBeforeReadiness() throws Exception {
        var objects = new Bytes();
        RootedCoordinationStorage.Selection selected;
        ColdStorageJournalFixture.Snapshot journalBytes;
        DocumentId id; byte[] expectedHistory, expectedResult; String expectedHead, entryId;
        try (var original = new RootedSdkFixture()) {
            var document = original.start("source.yaml", "rcp2/source", Map.of());
            id = document.id();
            var entry = original.append(document, "rcp2/source", "setCounter", 10, "counterValue: 5");
            entryId = entry.blueId();
            var engine = (DefaultCoordinationEngine) original.blue.advanced().rawEngine();
            var control = CoordinationTestControl.attach(engine);
            control.failOnceAt(CoordinationTestControl.FailurePoint.BEFORE_ROOTED_READINESS);
            var stage = original.blue.processing().processNextStage(document);
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, stage.disposition());
            assertEquals(1, stage.stats().committedTransitions());
            expectedResult = CODEC.encode(stage.entry(entry));
            expectedHistory = history(document); expectedHead = document.snapshot().blueId();
            selected = RootedCoordinationStorage.retainPartition(original.blue, objects, LIMITS);
            journalBytes = ColdStorageJournalFixture.retain(engine);
            var failure = assertThrows(RuntimeException.class,
                    () -> original.blue.processing().processNext(document));
            assertTrue(control.isInjectedFailure(failure), "Storage must not consume the armed readiness fault");
        }
        var journal = ColdStorageJournalFixture.open(journalBytes);
        try (var scope = RootedCoordinationStorage.open(objects.fresh(), LIMITS, selected,
                ExactNodeProvider.empty(), journal)) {
            var document = scope.documentHandle(id).orElseThrow();
            assertEquals(expectedHead, document.snapshot().blueId());
            assertArrayEquals(expectedHistory, history(document));
            assertEquals(5, document.snapshot().longAt("/counter"));
            assertArrayEquals(expectedResult, CODEC.encode(scope.coordination().runtimeForStorage().storedMaps().results().get(entryId)));
            var next = scope.coordination().processing().processNext(document);
            assertEquals(0, next.stats().committedTransitions());
            assertArrayEquals(expectedHistory, history(document));
            assertEquals(0, journal.mutations(), "Reopening must not reappend the accepted input");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zeroMatches", "wrongPath"})
    void coldRejectedDraftSurvivesValidSameChildAndSecondReopen(String operation) throws Exception {
        var checkpoints = new ColdRejectionCheckpoints();
        var evidence = RootedManagedDraftRejectionTest.run(false, operation, checkpoints);
        assertEquals(2, evidence.entries().size());
        assertEquals(2, checkpoints.reopens);
    }

    @Test void coldCounterReusesExactHistoryAndHandlesThenRunsTheSameSecondInvocation() throws Exception {
        // given
        var objects = new Bytes();
        RootedCoordinationStorage.Selection selected; ColdStorageJournalFixture.Snapshot journalBytes;
        DocumentId id; DocumentHandle oldDocument; EntryHandle oldEntry;
        byte[] firstResult, firstHistory, secondResult, secondHistory; String firstHead, secondHead;
        try (var original = new RootedSdkFixture()) {
            oldDocument = original.start("source.yaml", "rcp2/source", Map.of()); id = oldDocument.id();
            original.blue.timelines().register("empty-agent", "agent", TimelineActorKind.AGENT);
            var first = set(original.blue, oldDocument, 5); oldEntry = first.entry(); firstResult = CODEC.encode(first);
            firstHead = oldDocument.snapshot().blueId(); firstHistory = history(oldDocument);
            selected = RootedCoordinationStorage.retainPartition(original.blue, objects, LIMITS);
            journalBytes = ColdStorageJournalFixture.retain((DefaultCoordinationEngine) original.blue.advanced().rawEngine());
            var second = set(original.blue, oldDocument, 7); secondResult = CODEC.encode(second);
            secondHead = oldDocument.snapshot().blueId(); secondHistory = history(oldDocument);
        }
        var reads = new AtomicInteger(); var coldObjects = objects.fresh();
        var journal = ColdStorageJournalFixture.open(journalBytes);
        RootedCoordinationStorage.Selection firstStaged, next;
        try (var scope = RootedCoordinationStorage.open(coldObjects, LIMITS, selected, key -> {
            reads.incrementAndGet(); throw new AssertionError("Cold stored content must not be fetched: " + key);
        }, journal)) {
            // when
            var blue = scope.coordination();
            // then
            assertEquals(List.of(id), scope.documentIds());
            assertEquals(List.of("empty-agent", "rcp2/source"), scope.timelineIds());
            assertEquals(0, coldObjects.sessionBodyReads, "Identity inventories do not hydrate documents");
            assertEquals(0, journal.bodyReads(), "opening does not materialize the journal");
            assertEquals(0, journal.mutations()); assertEquals(0, coldObjects.writes);
            var metrics = CoordinationTestControl.attach(blue.advanced().rawEngine());
            assertEquals(0L, metrics.metricsSnapshot().phaseNanos().getOrDefault("contracts.closure.processor", 0L));
            var document = scope.documentHandle(id).orElseThrow();
            assertEquals(0, coldObjects.sessionBodyReads);
            assertEquals(firstHead, document.snapshot().blueId()); assertEquals(5, document.snapshot().longAt("/counter"));
            assertArrayEquals(firstHistory, history(document));
            var restoredResult = blue.runtimeForStorage().storedMaps().results().get(oldEntry.blueId());
            assertArrayEquals(firstResult, CODEC.encode(restoredResult)); assertNotEquals(oldEntry, restoredResult.entry());
            assertThrows(IllegalArgumentException.class, () -> blue.processing().process(oldDocument, restoredResult.entry()));
            assertThrows(IllegalArgumentException.class, () -> blue.processing().process(document, oldEntry));
            assertEquals(TimelineActorKind.AGENT, scope.timelineHandle("empty-agent").orElseThrow().actorKind());
            assertTrue(scope.timelineHandle("absent-timeline").isEmpty());
            assertEquals(0, reads.get());
            firstStaged = scope.stage();
            var second = set(blue, document, 7);
            assertArrayEquals(secondResult, CODEC.encode(second)); assertEquals(secondHead, document.snapshot().blueId());
            assertArrayEquals(secondHistory, history(document)); assertEquals(7, document.snapshot().longAt("/counter"));
            assertEquals(1, journal.mutations(), "only the new input appends, never the retained first input");
            next = scope.stage();
        }
        try (var prefix = RootedCoordinationStorage.open(coldObjects.fresh(), LIMITS, firstStaged, ExactNodeProvider.empty(),
                ColdStorageJournalFixture.open(journalBytes))) {
            var document = prefix.coordination().documents().require(id);
            assertEquals(firstHead, document.snapshot().blueId()); assertArrayEquals(firstHistory, history(document));
        }
        try (var scope = RootedCoordinationStorage.open(coldObjects.fresh(), LIMITS, next, key -> {
            throw new AssertionError("Second cold open must use retained exact evidence");
        }, ColdStorageJournalFixture.open(journal.snapshot()))) {
            var document = scope.coordination().documents().require(id);
            assertEquals(secondHead, document.snapshot().blueId()); assertArrayEquals(secondHistory, history(document));
            assertEquals(0L, CoordinationTestControl.attach(scope.coordination().advanced().rawEngine()).metricsSnapshot()
                    .phaseNanos().getOrDefault("contracts.closure.processor", 0L));
        }
    }

    @Test void missingEngineFamilyAndWrongActualConfigurationRejectBeforeAnySubmission() {
        // given
        var objects = new Bytes(); RootedCoordinationStorage.Selection selected;
        try (var original = BlueCoordination.inMemory()) { selected = RootedCoordinationStorage.retainPartition(original, objects, LIMITS); }
        var omitted = new LinkedHashMap<>(selected.slots());
        omitted.remove(omitted.keySet().stream().filter(name -> name.startsWith("engine/")).findFirst().orElseThrow());
        // when
        var journal = ColdStorageJournalFixture.empty();
        // then
        assertThrows(NoncommittingExecutionException.class, () -> RootedCoordinationStorage.open(objects.fresh(), LIMITS,
                new RootedCoordinationStorage.Selection(omitted), ExactNodeProvider.empty(), journal));
        var transport = new SdkSelectionStorage(LIMITS.sdk().maximumCodecBytes()); var parts = transport.decode(selected.slots());
        var config = parts.configuration();
        var wrong = new SdkStorageCodec.Configuration(config.language(), config.contracts(), config.bundledRelease(),
                config.contentDerivedDocumentIds(), blue.coordination.api.ContractsExecutionPolicy.exactSharedGas(1234, "foreign-policy"),
                config.bundledIdentities());
        var relabeled = new RootedCoordinationStorage.Selection(transport.encode(new SdkSelectionStorage.Parts(wrong, parts.maps(), parts.engine())));
        assertThrows(NoncommittingExecutionException.class, () -> RootedCoordinationStorage.open(objects.fresh(), LIMITS,
                relabeled, ExactNodeProvider.empty(), journal));
        assertEquals(0, journal.mutations()); assertEquals(0, journal.bodyReads());
    }

    @Test void coldStoppedParentAndCompletedSourceKeepOriginalEvidenceAcrossTwoOwners() throws Exception {
        // given
        var objects = new Bytes(); RootedCoordinationStorage.Selection selected;
        ColdStorageJournalFixture.Snapshot journalBytes; SourceHistoryPrerequisite admission;
        DocumentId parentId; String parentHead, attachId, sourceEntryId; byte[] stoppedBytes;
        byte[] expectedParentHistory, expectedResume; String expectedParentHead; List<String> expectedHistorySteps;
        Map<String, String> supplied;
        try (var original = new RootedSdkFixture()) {
            var parent = original.start("parent.yaml", "rcp2/parent", Map.of()); parentId = parent.id();
            var authoredSource = original.blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            original.exact.put(authoredSource.blueId(), authoredSource.json());
            original.timelines.put("rcp2/source", original.blue.timelines().register("rcp2/source", "alice"));
            sourceEntryId = original.appendReference(authoredSource.blueId(), "rcp2/source", "setCounter", 15,
                    "counterValue: 5", false).blueId();
            var attach = original.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + authoredSource.blueId());
            attachId = attach.blueId();
            // when
            var stopped = original.blue.processing().processNext(parent).entry(attach);
            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES, stopped.disposition()); stoppedBytes = CODEC.encode(stopped);
            admission = original.blue.advanced().sourceHistoryPrerequisites(parent).get(0); parentHead = parent.snapshot().blueId();
            selected = RootedCoordinationStorage.retainPartition(original.blue, objects, LIMITS);
            journalBytes = ColdStorageJournalFixture.retain((DefaultCoordinationEngine) original.blue.advanced().rawEngine());
            supplied = Map.copyOf(original.exact);
            assertTrue(original.blue.advanced().processSourceHistoryPrerequisite(admission).admission().orElseThrow().published());
            var expectedLive = original.blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            original.blue.advanced().processSourceHistoryPrerequisite(expectedLive);
            var expectedResumed = original.blue.processing().processNext(parent).entries().stream()
                    .filter(entry -> entry.entry().blueId().equals(attachId)).findFirst().orElseThrow();
            assertTrue(expectedResumed.applied());
            assertEquals(parentHead, parent.snapshot().blueId(), "Pending attachment still exposes the prior READY head");
            expectedHistorySteps = consumeSourceHistory(original.blue, parent, expectedLive.sourceDocumentId());
            expectedParentHead = parent.snapshot().blueId(); expectedParentHistory = history(parent); expectedResume = CODEC.encode(expectedResumed);
            assertNotEquals(parentHead, expectedParentHead);
        }
        var providerReads = new AtomicInteger(); ExactNodeProvider provider = key -> {
            providerReads.incrementAndGet(); return Optional.ofNullable(supplied.get(key));
        };
        var journal = ColdStorageJournalFixture.open(journalBytes);
        var coldObjects = objects.fresh(); SourceHistoryPrerequisite live; byte[] sourceResult;
        try (var scope = RootedCoordinationStorage.open(coldObjects, LIMITS, selected, provider, journal)) {
            assertEquals(0, providerReads.get()); var blue = scope.coordination();
            var parent = scope.documentHandle(parentId).orElseThrow();
            assertTrue(scope.documentHandle(new DocumentId("missing-document")).isEmpty());
            assertEquals(0, coldObjects.sessionBodyReads, "Retained lookup does not load the selected session body");
            assertArrayEquals(stoppedBytes, CODEC.encode(blue.runtimeForStorage().storedMaps().results().get(attachId)));
            assertEquals(parentHead, parent.snapshot().blueId());
            assertTrue(blue.advanced().processSourceHistoryPrerequisite(admission).admission().orElseThrow().published());
            live = blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            blue.advanced().processSourceHistoryPrerequisite(live);
            var result = blue.advanced().sourceHistoryProcessingResult(live).orElseThrow();
            assertTrue(result.entries().stream().anyMatch(entry -> entry.entry().blueId().equals(sourceEntryId) && entry.applied()));
            sourceResult = CODEC.encode(result);
            assertEquals(5, blue.documents().require(live.sourceDocumentId()).snapshot().longAt("/counter"));
            assertEquals(parentHead, parent.snapshot().blueId(), "Independent source execution never reruns the stopped parent");
            selected = scope.stage();
        }
        int readsBefore = providerReads.get();
        try (var scope = RootedCoordinationStorage.open(coldObjects.fresh(), LIMITS, selected, provider,
                ColdStorageJournalFixture.open(journal.snapshot()))) {
            assertEquals(readsBefore, providerReads.get()); var blue = scope.coordination();
            assertArrayEquals(sourceResult, CODEC.encode(blue.advanced().sourceHistoryProcessingResult(live).orElseThrow()));
            var engineMetrics = CoordinationTestControl.attach(blue.advanced().rawEngine());
            assertTrue(blue.advanced().processSourceHistoryPrerequisite(live).replayed());
            assertEquals(0L, engineMetrics.metricsSnapshot().phaseNanos().getOrDefault("contracts.closure.processor", 0L));
            var parent = blue.documents().require(parentId); assertEquals(parentHead, parent.snapshot().blueId());
            var source = blue.documents().require(live.sourceDocumentId());
            assertEquals(5, source.snapshot().longAt("/counter"));
            var sourceHead = source.snapshot().blueId(); var sourceHistory = history(source);
            var resumed = blue.processing().processNext(parent);
            assertTrue(resumed.entries().stream().anyMatch(entry -> entry.entry().blueId().equals(attachId) && entry.applied()));
            assertArrayEquals(expectedResume, CODEC.encode(resumed.entries().stream()
                    .filter(entry -> entry.entry().blueId().equals(attachId)).findFirst().orElseThrow()));
            assertEquals(parentHead, parent.snapshot().blueId(), "Cold pending attachment exposes the same prior READY head");
            assertEquals(expectedHistorySteps, consumeSourceHistory(blue, parent, live.sourceDocumentId()));
            assertEquals(expectedParentHead, parent.snapshot().blueId()); assertArrayEquals(expectedParentHistory, history(parent));
            assertNotEquals(parentHead, parent.snapshot().blueId());
            assertEquals(sourceHead, parent.snapshot().valueAt("/child").blueId());
            assertEquals(sourceHead, source.snapshot().blueId()); assertArrayEquals(sourceHistory, history(source));
        }
    }

    @Test void sdkOrScopeCloseRetiresTheEntireActualOwnerAndDoesNotPublish() {
        // given
        for (boolean sdkFirst : List.of(true, false)) {
            var objects = new Bytes(); RootedCoordinationStorage.Selection selection;
            try (var original = BlueCoordination.inMemory()) { selection = RootedCoordinationStorage.retainPartition(original, objects, LIMITS); }
            var journal = ColdStorageJournalFixture.empty();
            var scope = RootedCoordinationStorage.open(objects.fresh(), LIMITS, selection, ExactNodeProvider.empty(), journal);
            var blue = scope.coordination();
            if (sdkFirst)
            // when
            blue.close(); else scope.close();
            // then
            assertThrows(NoncommittingExecutionException.class, scope::stage);
            assertThrows(NoncommittingExecutionException.class, () -> scope.documentHandle(new DocumentId("missing-document")));
            assertThrows(NoncommittingExecutionException.class, () -> scope.timelineHandle("missing-timeline"));
            assertThrows(NoncommittingExecutionException.class, scope::documentIds);
            assertThrows(NoncommittingExecutionException.class, scope::timelineIds);
            scope.close(); blue.close();
            assertThrows(NoncommittingExecutionException.class, scope::coordination);
            assertEquals(0, journal.mutations());
        }
    }

    @Test void coldReciprocalCycleMatchesFullFiniteAndSplitEvidenceAfterProducerCloses() throws Exception {
        // given
        var objects = new Bytes(); RootedCoordinationStorage.Selection selected;
        ColdStorageJournalFixture.Snapshot journalBytes;
        DocumentId aId, bId; String connectId, finiteId; CycleEvidence finite, split;
        List<String> joinedHeads; Map<String, String> supplied; String noWorkEvidence;
        try (var original = new RootedSdkFixture()) {
            var blue = original.blue;
            var b = original.start("cycle-b.yaml", "rcp2/cycle", Map.of()); bId = b.id();
            String aYaml = RootedSdkFixture.resource("cycle-a.yaml") + "\npeer:\n  blueId: " + b.snapshot().blueId() + "\n";
            var authoredA = blue.values().yaml(aYaml); original.exact.put(authoredA.blueId(), authoredA.json());
            var a = original.startYaml(aYaml, "rcp2/cycle"); aId = a.id();
            var connect = cycleAppend(blue, b, null, "connectA", 90, "a:\n  blueId: " + authoredA.blueId()); connectId = connect.blueId();
            // when
            var join = blue.processing().processNext(b);
            // then
            assertEquals(EntryDisposition.APPLIED, join.entry(connect).disposition()); assertFalse(join.quiescent());
            var joinTerminal = blue.advanced().closureExecution(join.entry(connect).closures().get(0).closureId()).orElseThrow();
            System.out.println("COLD_CYCLE_JOIN_COMPLETE_RESULT_BYTES="
                    + new ClosureProcessResultStorageCodec(MAX, 256).encode(joinTerminal).length);
            assertFalse(blue.advanced().auditManagedOccurrence(bId, "/peer").orElseThrow().active());
            var pendingSelection = RootedCoordinationStorage.retainPartition(blue, objects, LIMITS);
            var pendingObjects = objects.fresh();
            try (var pendingScope = RootedCoordinationStorage.open(pendingObjects, LIMITS, pendingSelection,
                    ExactNodeProvider.empty(), ColdStorageJournalFixture.open(
                            ColdStorageJournalFixture.retain((DefaultCoordinationEngine) blue.advanced().rawEngine())))) {
                var pendingHandle = pendingScope.documentHandle(bId).orElseThrow();
                assertEquals(java.util.stream.Stream.of(aId, bId).sorted(java.util.Comparator.comparing(DocumentId::value)).toList(),
                        pendingScope.documentIds(), "Pending documents remain catalog members");
                assertEquals(bId, pendingHandle.id());
                assertTrue(pendingScope.documentHandle(new DocumentId("missing-document")).isEmpty());
                assertEquals(0, pendingObjects.sessionBodyReads, "Pending membership must not hydrate the non-ready document");
                assertFalse(pendingScope.coordination().advanced().rawEngine().auditManagedDocumentReadiness(bId).orElseThrow().ready());
                assertTrue(pendingObjects.sessionBodyReads > 0, "The later explicit body/readiness audit does hydrate");
            }
            var genesis = blue.processing().processNext(b);
            assertEquals(1, genesis.managedEpochApplications().size());
            assertEquals(aId, genesis.managedEpochApplicationAttempts().get(0).work().sourceDocumentId());
            assertEquals(0, genesis.managedEpochApplicationAttempts().get(0).work().sourceEpoch());
            assertTrue(genesis.quiescent());
            assertTrue(blue.advanced().auditManagedOccurrence(aId, "/peer").orElseThrow().active());
            assertTrue(blue.advanced().auditManagedOccurrence(bId, "/peer").orElseThrow().active());
            joinedHeads = List.of(a.snapshot().blueId(), b.snapshot().blueId());
            selected = RootedCoordinationStorage.retainPartition(blue, objects, LIMITS);
            journalBytes = ColdStorageJournalFixture.retain((DefaultCoordinationEngine) blue.advanced().rawEngine());
            supplied = Map.copyOf(original.exact);
            var subject = cycleAppend(blue, a, connectId, "startFinite", 100, "{}"); finiteId = subject.blueId();
            var result = blue.processing().processNext(a).entry(subject);
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            assertEquals("done", a.snapshot().textAt("/phase")); assertEquals("relayed", b.snapshot().textAt("/phase"));
            finite = cycleEvidence(blue, a, b, result);
            var detach = cycleAppend(blue, b, finiteId, "detachA", 110, "{}");
            var detached = blue.processing().processNext(b).entry(detach);
            assertEquals(EntryDisposition.APPLIED, detached.disposition()); split = cycleEvidence(blue, a, b, detached);
            assertEquals("detached", b.snapshot().textAt("/phase"));
            assertExistingExplicitPostSplitReplayLimitation(blue, a, b, detached, split);
            noWorkEvidence = assertQuiescentWithoutPublication(blue);
            assertEquals(split, cycleEvidence(blue, a, b, detached));
        }
        var providerReads = new AtomicInteger(); ExactNodeProvider provider = key -> {
            providerReads.incrementAndGet(); return Optional.ofNullable(supplied.get(key));
        };
        var journal = ColdStorageJournalFixture.open(journalBytes); var coldObjects = objects.fresh();
        try (var scope = RootedCoordinationStorage.open(coldObjects, LIMITS, selected, provider, journal)) {
            assertEquals(0, providerReads.get()); assertEquals(0, journal.bodyReads());
            var blue = scope.coordination(); var a = blue.documents().require(aId); var b = blue.documents().require(bId);
            assertEquals(joinedHeads, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
            assertEquals(0L, CoordinationTestControl.attach(blue.advanced().rawEngine()).metricsSnapshot()
                    .phaseNanos().getOrDefault("contracts.closure.processor", 0L));
            var subject = cycleAppend(blue, a, connectId, "startFinite", 100, "{}");
            assertEquals(finiteId, subject.blueId()); var result = blue.processing().processNext(a).entry(subject);
            assertEquals(finite, cycleEvidence(blue, a, b, result));
            assertEquals("done", a.snapshot().textAt("/phase")); assertEquals("relayed", b.snapshot().textAt("/phase"));
            selected = scope.stage();
        }
        try (var scope = RootedCoordinationStorage.open(coldObjects.fresh(), LIMITS, selected, provider,
                ColdStorageJournalFixture.open(journal.snapshot()))) {
            var blue = scope.coordination(); var a = blue.documents().require(aId); var b = blue.documents().require(bId);
            var retained = blue.runtimeForStorage().storedMaps().results().get(finiteId);
            assertEquals(finite, cycleEvidence(blue, a, b, retained));
            var replay = blue.processing().process(a, retained.entry());
            assertEquals(0, replay.stats().committedTransitions());
            assertEquals(finite, cycleEvidence(blue, a, b, retained));
            var detach = cycleAppend(blue, b, finiteId, "detachA", 110, "{}");
            var detached = blue.processing().processNext(b).entry(detach);
            assertEquals(split, cycleEvidence(blue, a, b, detached));
            assertEquals("detached", b.snapshot().textAt("/phase"));
            assertFalse(blue.advanced().auditManagedOccurrence(bId, "/peer").orElseThrow().active());
            assertFalse(a.snapshot().blueId().contains("#")); assertFalse(b.snapshot().blueId().contains("#"));
            assertExistingExplicitPostSplitReplayLimitation(blue, a, b, detached, split);
            assertEquals(noWorkEvidence, assertQuiescentWithoutPublication(blue));
            // The canonical drain may retain a newer empty acknowledgement in
            // the SDK convenience entry map; original exact publication lookup
            // remains independently authoritative and is checked here.
            assertEquals(split, cycleEvidence(blue, a, b, detached));
        }
    }

    private static CycleEvidence cycleEvidence(BlueCoordination blue, DocumentHandle a, DocumentHandle b, EntryResult result) {
        var terminal = blue.advanced().closureExecution(result.closures().get(0).closureId()).orElseThrow();
        assertNotNull(terminal.rootedProjection());
        var control = new RootedCalculationFixture(blue.advanced().rawEngine());
        return new CycleEvidence(result.entry().blueId(), base64(CODEC.encode(result)),
                base64(new ClosureProcessResultStorageCodec(MAX, 256).encode(terminal)),
                List.of(a.snapshot().blueId(), b.snapshot().blueId()), List.of(base64(history(a)), base64(history(b))),
                control.historyBasis(a.id()), control.historyBasis(b.id()));
    }
    private record CycleEvidence(String entry, String result, String completeTerminal, List<String> heads,
            List<String> completePublicHistory, Map<String, Object> aCheckpointHistory, Map<String, Object> bCheckpointHistory) { }
    private static String base64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private static List<String> consumeSourceHistory(BlueCoordination blue, DocumentHandle parent, DocumentId source) {
        var evidence = new java.util.ArrayList<String>();
        // The authored cursor -1 requires exactly the fixture's already committed
        // source epochs 0 and 1. Each remains a separate selected application.
        for (long epoch : List.of(0L, 1L)) {
            var step = blue.processing().processNext(parent);
            assertEquals(1, step.managedEpochApplicationAttempts().size());
            var attempt = step.managedEpochApplicationAttempts().get(0);
            assertEquals(source, attempt.work().sourceDocumentId()); assertEquals(epoch, attempt.work().sourceEpoch());
            assertTrue(attempt.published()); assertEquals(1, step.managedEpochApplications().size());
            evidence.add(stableDrain(step));
        }
        assertTrue(blue.advanced().rawEngine().auditManagedDocumentReadiness(parent.id()).orElseThrow().ready());
        evidence.add(assertQuiescentWithoutPublication(blue)); return List.copyOf(evidence);
    }
    private static String assertQuiescentWithoutPublication(BlueCoordination blue) {
        var result = blue.processing().drain();
        assertTrue(result.quiescent()); assertEquals(0, result.stats().committedTransitions());
        assertEquals(0, result.stats().gas());
        result.entries().forEach(entry -> {
            assertTrue(entry.closures().isEmpty()); assertTrue(entry.publicEvents().isEmpty());
            assertEquals(0, entry.stats().committedTransitions()); assertEquals(0, entry.stats().gas());
        });
        assertTrue(result.managedEpochApplicationAttempts().isEmpty());
        assertTrue(result.rootedRetainedApplications().isEmpty());
        return stableDrain(result);
    }
    private static String stableDrain(DrainResult step) {
        var stats = step.stats();
        // Only the aggregate physical elapsed clock differs between runs;
        // every semantic stat, order, complete attempt and receipt is exact.
        var stable = new DrainResult(step.entries(), new ProcessingStats(stats.gas(), stats.committedTransitions(),
                stats.documentsOpened(), 0L, stats.documentStepOrder(), stats.counters()), step.quiescent(), step.paused(),
                step.diagnostic(), step.managedEpochApplications(), step.managedEpochApplicationAttempts(),
                step.managedEpochEvidenceFailures(), step.rootedRetainedApplications());
        return base64(CODEC.encode(stable));
    }
    /** Separate existing-Main reproduction, not a successful cold-storage replay claim. */
    private static void assertExistingExplicitPostSplitReplayLimitation(BlueCoordination blue, DocumentHandle a,
            DocumentHandle b, EntryResult detached, CycleEvidence before) {
        var failure = assertThrows(blue.coordination.api.CoordinationException.class,
                () -> blue.processing().process(b, detached.entry()));
        assertInstanceOf(java.util.NoSuchElementException.class, failure.getCause());
        assertEquals(before, cycleEvidence(blue, a, b, detached), "Unsupported explicit replay publishes nothing");
    }
    private static EntryHandle cycleAppend(BlueCoordination blue, DocumentHandle target, String previous,
            String operation, long timestamp, String request) {
        String yaml = """
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: rcp2/cycle
                timestamp: %d
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
                message:
                  type: Coordination/Operation Request
                  document:
                    blueId: %s
                  requireExactDocumentVersion: false
                  operation: %s
                  channel: ownerChannel
                  request:
                %s
                """.formatted(timestamp, target.snapshot().blueId(), operation, request.indent(4));
        if (previous != null) yaml += "\nprevEntry:\n  blueId: " + previous + "\n";
        return blue.events().from(blue.timelines().register("rcp2/cycle", "alice")).exact(blue.values().yaml(yaml)).submit();
    }

    private static EntryResult set(BlueCoordination blue, DocumentHandle document, int value) {
        var result = blue.operations().on(document).from(blue.timelines().register("rcp2/source", "alice"))
                .call("setCounter").through("owner").requestYaml("counterValue: " + value).execute();
        assertEquals(EntryDisposition.APPLIED, result.disposition()); return result;
    }
    private static byte[] history(DocumentHandle document) {
        // Every public revision field is preserved; the canonical receipt identity
        // binds its complete independently validated managed-source evidence.
        return CODEC.encode(document.history().stream().map(row -> List.of(row.documentId(), row.epoch(), row.kind().name(),
                row.before(), row.after(), row.sourceEntry(), row.publicEvents(), row.processingGas(),
                row.managedEpochReceipt().map(ManagedEpochReceipt::receiptIdentity))).toList());
    }
    /** Only the checkpoint strategy differs from the maintained resident scenario. */
    private static final class ColdRejectionCheckpoints implements RootedManagedDraftRejectionTest.RuntimeCheckpoints {
        private Bytes objects = new Bytes();
        private BlueCoordination blue = BlueCoordination.inMemory();
        private RootedCoordinationStorage.Scope scope;
        private ColdStorageJournalFixture journal;
        private int reopens;

        @Override public BlueCoordination current() { return blue; }

        @Override public BlueCoordination restart() {
            var selection = scope == null
                    ? RootedCoordinationStorage.retainPartition(blue, objects, LIMITS)
                    : scope.stage();
            var journalBytes = scope == null
                    ? ColdStorageJournalFixture.retain((DefaultCoordinationEngine) blue.advanced().rawEngine())
                    : journal.snapshot();
            var detached = objects.fresh();
            close();
            objects = detached;
            journal = ColdStorageJournalFixture.open(journalBytes);
            scope = RootedCoordinationStorage.open(objects, LIMITS, selection, key -> {
                throw new AssertionError("Rejected-draft reopen must use retained exact content: " + key);
            }, journal);
            blue = scope.coordination();
            reopens++;
            assertEquals(0, objects.writes, "Reopening alone never publishes immutable objects");
            assertEquals(0, journal.mutations(), "Reopening never replays or resubmits an entry");
            assertEquals(0L, CoordinationTestControl.attach(blue.advanced().rawEngine()).metricsSnapshot()
                    .phaseNanos().getOrDefault("contracts.closure.processor", 0L));
            return blue;
        }

        @Override public void close() {
            if (scope != null) scope.close();
            else blue.close();
        }
    }

    private static final class Bytes implements CoordinationImmutableObjectStore {
        private final Map<String, byte[]> values = new LinkedHashMap<>(); private int writes; private int sessionBodyReads;
        @Override public byte[] putIfAbsent(String address, byte[] bytes) {
            writes++; var original = values.putIfAbsent(address, bytes.clone()); return original == null ? bytes.clone() : original.clone();
        }
        @Override public Optional<byte[]> get(String address, int maximumBytes) {
            var bytes = values.get(address);
            if (bytes != null && bytes.length > maximumBytes) throw new CoordinationObjectStorageException("Oversized test row");
            if (isSessionFrame(bytes)) sessionBodyReads++;
            return Optional.ofNullable(bytes == null ? null : bytes.clone());
        }
        private static boolean isSessionFrame(byte[] bytes) {
            // Test-only identification of the closed SessionStorageWire header,
            // not an alternate decoder or source of runtime authority.
            return isSessionFrame(bytes, "1") || isSessionFrame(bytes, "2");
        }
        private static boolean isSessionFrame(byte[] bytes, String version) {
            byte[] format = ("blue-coordination/document-session-storage/" + version).getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
            if (bytes == null || bytes.length < Integer.BYTES + format.length) return false;
            var input = java.nio.ByteBuffer.wrap(bytes);
            if (input.getInt() != format.length) return false;
            for (byte expected : format) if (input.get() != expected) return false;
            return true;
        }
        private Bytes fresh() { var copy = new Bytes(); values.forEach((key, bytes) -> copy.values.put(key, bytes.clone())); return copy; }
    }
}
