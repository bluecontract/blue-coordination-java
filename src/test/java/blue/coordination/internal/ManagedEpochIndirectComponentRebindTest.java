package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.ClosureHandle;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DrainBudget;
import blue.coordination.sdk.DrainResult;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.coordination.sdk.EntryResult;
import blue.coordination.sdk.ExactBlueValue;
import blue.coordination.sdk.ManagedClosure;
import blue.coordination.sdk.ManagedDocument;
import blue.coordination.sdk.ManagedEpochEvidenceFailure;
import blue.coordination.sdk.TimelineHandle;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.provider.CyclicSetProof;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retained cyclic-source proof failures leave catch-up exactly retryable. */
final class ManagedEpochIndirectComponentRebindTest {
    private static final String ACTOR = "alice";
    private static final DocumentId A = DocumentId.of(
            "managed-indirect-rebind-a");
    private static final DocumentId B = DocumentId.of(
            "managed-indirect-rebind-b");
    private static final DocumentId C = DocumentId.of(
            "managed-indirect-rebind-c");
    private static final String A_TIMELINE =
            "managed-indirect-rebind/a";
    private static final String B_TIMELINE =
            "managed-indirect-rebind/b";

    @Test
    void retainedInvocationCarriesExactHistoricalBodyBindingAndCyclicProof() {
        try (Scenario scenario = prepared(true)) {
            // given
            // A attached B epoch 0 only after B reached epoch 2, so the first
            // due source epoch is genuinely historical.
            assertEquals(2L, scenario.b().snapshot().epoch());

            DefaultCoordinationEngine engine = scenario.engine();
            InMemoryDocumentStore documents = engine.documents();
            ManagedEpochApplicationWork work = scenario.work();
            assertEquals(1L, work.sourceEpoch());
            assertEquals(work.workIdentity(), documents.nextCatchUpWork()
                    .orElseThrow().workIdentity());
            ManagedEpochReceipt historical = documents.managedEpochEvidence(
                    work.sourceDocumentId(), work.sourceEpoch())
                    .receipt();
            assertNotEquals(scenario.b().snapshot().blueId(),
                    historical.afterBlueId(),
                    "the selected source epoch must precede the current head");

            // when
            // Capture the immutable input immediately before the executor
            // passes it to BlueClosureContracts.processClosure.
            ManagedEpochInvocationCapturer.Capture capture =
                    invocationCapturer(engine.contractsClosureAdapter())
                            .capture(work, Set.of());
            ClosureInvocationInput input = capture.invocation().input();
            ManagedRevisionCause cause = (ManagedRevisionCause) input.cause();

            // then
            // The cause carries and authenticates the exact retained epoch-1
            // body, not B's current epoch-2 body.
            assertEquals(work.targetOccurrenceIdentity(),
                    cause.targetOccurrenceIdentity());
            assertEquals(0L, cause.fromEpoch());
            assertEquals(1L, cause.toEpoch());
            assertEquals(historical.beforeBlueId().orElseThrow(),
                    cause.beforeBlueId());
            assertEquals(historical.afterBlueId(), cause.afterBlueId());
            assertEquals(historical.receiptIdentity(),
                    capture.sourceReceipt().receiptIdentity());
            CyclicSetProof proof = cause.afterCyclicProof().orElseThrow();
            ExactValue authenticated = ExactValue
                    .fromVerifiedProviderEvidence(
                            cause.afterBlueId(),
                            cause.afterDocument(),
                            proof);
            assertTrue(historical.afterDocument()
                    .sameExactValue(authenticated));

            // The same input binds A's exact /child occurrence to the
            // predecessor BlueId and to the retained B lineage.
            ManagedOccurrenceBinding binding = input.snapshot().occurrences()
                    .stream()
                    .filter(row -> row.occurrenceIdentity().equals(
                            work.targetOccurrenceIdentity()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(ContractsClosureAdapter.closureId(A),
                    binding.sourceDocumentId());
            assertEquals(work.targetPath(), binding.sourcePath());
            assertEquals(ContractsClosureAdapter.closureId(B),
                    binding.targetDocumentId());
            assertEquals(cause.beforeBlueId(),
                    binding.expectedTargetBlueId());
            assertEquals(work.activationGeneration(),
                    binding.activationGeneration());
            assertFalse(binding.active());
            assertEquals(Long.valueOf(cause.fromEpoch()),
                    binding.pendingHistoricalEpoch());

            // The canonical consumer remains compact: the complete body and
            // proof live in the managed-revision evidence, while /child is
            // still the exact pure reference authenticated by the row.
            ManagedDocumentSnapshot consumer = input.snapshot()
                    .managedDocument(ContractsClosureAdapter.closureId(A));
            assertNotNull(consumer);
            Node child = NodePathEditor.getOrNull(
                    consumer.document(), work.targetPath());
            assertNotNull(child);
            assertTrue(child.isReferenceOnly());
            assertEquals(binding.expectedTargetBlueId(),
                    child.getBlueId());
        }
    }

    @Test
    void verifiedSourceCapsuleSharesProofWithoutExposingMutableMembers() {
        try (Scenario scenario = prepared(true)) {
            DefaultCoordinationEngine engine = scenario.engine();
            InMemoryDocumentStore documents = engine.documents();
            ManagedEpochApplicationWork work = scenario.work();
            var retained = documents.managedEpochEvidence(
                    work.sourceDocumentId(), work.sourceEpoch());
            CyclicSetProof providerProof = engine.objects()
                    .cyclicSetProofFor(retained.receipt().afterBlueId())
                    .proof().orElseThrow();
            List<Node> suppliedMembers = providerProof.declaredPlaceholderSet();
            CyclicSetProof capsule = CyclicSetProof.fromDeclaredPlaceholderSet(suppliedMembers);
            List<Object> originalMembers = capsule.declaredPlaceholderSet().stream()
                    .map(NodeWireForm::get).toList();
            var evidence = new ManagedEpochSourceEvidenceVerifier.VerifiedSourceEvidence(
                    retained.receipt(), retained.transitionReceipt(), capsule);
            assertSame(capsule, evidence.afterCyclicProof());
            assertSame(evidence.afterCyclicProof(), evidence.afterCyclicProof());

            DurableImage before = DurableImage.capture(scenario);
            var capturer = invocationCapturer(engine.contractsClosureAdapter());
            ClosureInvocationInput originalInput = capturer.capture(work, Set.of()).invocation().input();

            // Construction owns the supplied nodes; returning the capsule does
            // not expose that owned graph through either member extraction.
            suppliedMembers.get(0).properties("callerMutation", new Node().value(true));
            List<Node> extracted = evidence.afterCyclicProof().declaredPlaceholderSet();
            List<Node> secondExtraction = evidence.afterCyclicProof().declaredPlaceholderSet();
            assertNotSame(extracted.get(0), secondExtraction.get(0));
            assertThrows(UnsupportedOperationException.class,
                    () -> extracted.add(new Node()));
            assertFalse(extracted.get(0).getProperties().isEmpty());
            extracted.get(0).getProperties().values().iterator().next()
                    .value("attempted nested mutation");
            assertEquals(originalMembers, evidence.afterCyclicProof().declaredPlaceholderSet()
                    .stream().map(NodeWireForm::get).toList());
            assertEquals(originalMembers, secondExtraction.stream().map(NodeWireForm::get).toList());
            assertTrue(retained.receipt().afterDocument().sameExactValue(
                    ExactValue.fromVerifiedProviderEvidence(retained.receipt().afterBlueId(),
                            retained.receipt().afterDocument().copyNode(), evidence.afterCyclicProof())));

            ClosureInvocationInput recaptured = capturer.capture(work, Set.of()).invocation().input();
            assertEquals(originalInput.invocationIdentity(), recaptured.invocationIdentity());
            assertEquals(originalInput.cause().causeIdentity(), recaptured.cause().causeIdentity());
            assertEquals(originalInput.snapshot().closureIdentity(), recaptured.snapshot().closureIdentity());
            assertEquals(before, DurableImage.capture(scenario));
        }
    }

    @Test
    void missingRetainedCyclicSourceProofWaitsWithoutAdvancingCursor() {
        // given
        ManagedCatchUpStatus expected =
                ManagedCatchUpStatus.WAITING_FOR_HISTORY;

        // when
        ManagedCatchUpStatus actual = assertCyclicProofFailure(
                CyclicProofFault.NOT_FOUND,
                expected,
                ManagedEpochEvidenceFailure.Status.WAITING_FOR_HISTORY,
                ManagedEpochEvidenceException.CYCLIC_PROOF_MISSING);

        // then
        assertEquals(expected, actual);
    }

    @Test
    void unavailableRetainedCyclicSourceProofWaitsWithoutAdvancingCursor() {
        // given
        ManagedCatchUpStatus expected =
                ManagedCatchUpStatus.WAITING_FOR_HISTORY;

        // when
        ManagedCatchUpStatus actual = assertCyclicProofFailure(
                CyclicProofFault.UNAVAILABLE,
                expected,
                ManagedEpochEvidenceFailure.Status.WAITING_FOR_HISTORY,
                ManagedEpochEvidenceException.CYCLIC_PROOF_UNAVAILABLE);

        // then
        assertEquals(expected, actual);
    }

    @Test
    void invalidRetainedCyclicSourceProofBlocksWithoutAdvancingCursor() {
        // given
        ManagedCatchUpStatus expected = ManagedCatchUpStatus.BLOCKED;

        // when
        ManagedCatchUpStatus actual = assertCyclicProofFailure(
                CyclicProofFault.INVALID_EVIDENCE,
                expected,
                ManagedEpochEvidenceFailure.Status.BLOCKED,
                ManagedEpochEvidenceException.CYCLIC_PROOF_INVALID);

        // then
        assertEquals(expected, actual);
    }

    @Test
    void componentMergeThatWouldAdvanceSourceEpochFailsClosed() {
        // Reference-only finalization no longer advances the historical target.
        // This negative must actually change that target's local business state.
        try (Scenario scenario = prepared(false, true)) {
            // given
            DefaultCoordinationEngine engine = scenario.engine();
            InMemoryDocumentStore documents = engine.documents();
            DurableImage before = DurableImage.capture(scenario);

            // when
            ContractsClosureAdapter.ProjectionUnavailableException failure =
                    assertThrows(
                            ContractsClosureAdapter
                                    .ProjectionUnavailableException.class,
                            engine.contractsClosureAdapter()
                                    ::processNextManagedEpochApplication);

            // then
            assertTrue(failure.getMessage().contains(
                    "cannot append or reinterpret its immutable source"));
            assertEquals(before, DurableImage.capture(scenario));
            assertEquals(scenario.work().workIdentity(),
                    documents.nextCatchUpWork().orElseThrow().workIdentity());
            engine.restartFromStores();
            assertEquals(before, DurableImage.capture(scenario));
        }
    }

    private static ManagedCatchUpStatus assertCyclicProofFailure(
            CyclicProofFault fault,
            ManagedCatchUpStatus expectedPlanStatus,
            ManagedEpochEvidenceFailure.Status expectedSdkStatus,
            String expectedCode) {
        try (Scenario scenario = prepared()) {
            DefaultCoordinationEngine engine = scenario.engine();
            InMemoryDocumentStore documents = engine.documents();
            ManagedEpochApplicationWork work = scenario.work();
            ManagedEpochReceipt sourceReceipt = documents
                    .managedEpochEvidence(
                            work.sourceDocumentId(), work.sourceEpoch())
                    .receipt();
            String cyclicAfterBlueId = sourceReceipt.afterBlueId();
            assertTrue(BlueIds.hasCyclicMemberSeparator(cyclicAfterBlueId));
            assertEquals(NodeProviderOutcome.FOUND,
                    engine.objects().cyclicSetProofFor(
                            cyclicAfterBlueId).outcome());

            ManagedOccurrenceCatchUpPlan planBefore = documents.catchUpPlan(
                    work.planIdentity()).orElseThrow();
            var verifier = new ManagedEpochSourceEvidenceVerifier(engine.objects(), documents);
            var retainedEvidence = documents.managedEpochEvidence(work.sourceDocumentId(), work.sourceEpoch());
            var acquired = verifier.verify(work, retainedEvidence, planBefore);
            assertNotNull(acquired.afterCyclicProof());
            assertSame(acquired.afterCyclicProof(), acquired.afterCyclicProof());
            DocumentSession consumerBefore = documents.require(
                    work.consumerDocumentId());
            long consumerEpochBefore = consumerBefore.epoch();
            String consumerBlueIdBefore = consumerBefore
                    .currentRepresentation().blueId();
            var occurrenceBefore = documents.occurrenceInventory().find(
                    work.consumerDocumentId(), work.targetPath())
                    .orElseThrow();
            List<String> aHistoryBefore = history(engine, A);
            List<String> bHistoryBefore = history(engine, B);
            List<String> cHistoryBefore = history(engine, C);
            List<String> aReceiptsBefore = receiptIdentities(documents, A);
            List<String> bReceiptsBefore = receiptIdentities(documents, B);
            List<String> cReceiptsBefore = receiptIdentities(documents, C);
            long processCallsBefore = counter(
                    engine, ManagedEpochApplicationExecutor.PROCESS_CALLS);
            DurableImage durableBefore = DurableImage.capture(scenario);

            applyProofFault(engine.objects(), cyclicAfterBlueId, fault);
            try {
                // Sharing a previously acquired immutable capsule must not
                // memoize a later provider outcome, even in the same verifier.
                ManagedEpochEvidenceException reacquired = assertThrows(
                        ManagedEpochEvidenceException.class,
                        () -> verifier.verify(work, retainedEvidence, planBefore));
                assertEquals(expectedPlanStatus, reacquired.planStatus());
                assertEquals(expectedCode, reacquired.code());
                ManagedEpochEvidenceException direct = assertThrows(
                        ManagedEpochEvidenceException.class,
                        () -> engine.contractsClosureAdapter()
                                .executeManagedEpochApplication(work));
                assertEquals(expectedPlanStatus, direct.planStatus());
                assertEquals(expectedCode, direct.code());
                assertEquals(processCallsBefore, counter(
                        engine,
                        ManagedEpochApplicationExecutor.PROCESS_CALLS));
                assertEquals(work.workIdentity(), documents
                        .nextCatchUpWork().orElseThrow().workIdentity());
                assertEquals(durableBefore, DurableImage.capture(scenario));

                engine.restartFromStores();
                ManagedEpochEvidenceException restarted = assertThrows(
                        ManagedEpochEvidenceException.class,
                        () -> engine.contractsClosureAdapter()
                                .executeManagedEpochApplication(work));
                assertEquals(direct.code(), restarted.code());
                assertEquals(direct.planStatus(), restarted.planStatus());
                assertEquals(direct.message(), restarted.message());
                assertEquals(durableBefore, DurableImage.capture(scenario));

                DrainResult drained = scenario.coordination().processing()
                        .drain(new DrainBudget(1L, 1L));

                assertEquals(1,
                        drained.managedEpochEvidenceFailures().size());
                ManagedEpochEvidenceFailure exposed = drained
                        .managedEpochEvidenceFailures().get(0);
                assertEquals(expectedSdkStatus, exposed.status());
                assertEquals(direct.code(), exposed.code());
                assertEquals(work.workIdentity(),
                        exposed.work().workIdentity());
                assertTrue(drained.managedEpochApplications().isEmpty());
                assertTrue(drained.managedEpochApplicationAttempts()
                        .isEmpty());

                ManagedOccurrenceCatchUpPlan waiting = documents.catchUpPlan(
                        work.planIdentity()).orElseThrow();
                assertEquals(planBefore.nextSourceEpoch(),
                        waiting.nextSourceEpoch());
                assertEquals(planBefore.requiredThroughSourceEpoch(),
                        waiting.requiredThroughSourceEpoch());
                assertEquals(expectedPlanStatus, waiting.status());
                assertEquals(direct.code(),
                        waiting.waitingCode().orElseThrow());
                assertTrue(documents.nextCatchUpWork().isEmpty());
                assertTrue(documents.catchUpApplicationByWork(
                        work.workIdentity()).isEmpty());

                assertSame(consumerBefore, documents.require(
                        work.consumerDocumentId()));
                assertEquals(consumerEpochBefore,
                        documents.require(A).epoch());
                assertEquals(consumerBlueIdBefore,
                        documents.require(A).currentRepresentation().blueId());
                assertEquals(occurrenceBefore,
                        documents.occurrenceInventory().find(
                                work.consumerDocumentId(), work.targetPath())
                                .orElseThrow());
                assertEquals(aHistoryBefore, history(engine, A));
                assertEquals(bHistoryBefore, history(engine, B));
                assertEquals(cHistoryBefore, history(engine, C));
                assertEquals(aReceiptsBefore,
                        receiptIdentities(documents, A));
                assertEquals(bReceiptsBefore,
                        receiptIdentities(documents, B));
                assertEquals(cReceiptsBefore,
                        receiptIdentities(documents, C));
                assertEquals(processCallsBefore, counter(
                        engine,
                        ManagedEpochApplicationExecutor.PROCESS_CALLS));

                engine.restartFromStores();
                ManagedOccurrenceCatchUpPlan reconstructed = documents
                        .catchUpPlan(work.planIdentity()).orElseThrow();
                assertEquals(waiting.snapshotIdentity(),
                        reconstructed.snapshotIdentity());
                assertEquals(expectedCode,
                        reconstructed.waitingCode().orElseThrow());
                assertEquals(planBefore.nextSourceEpoch(),
                        reconstructed.nextSourceEpoch());
                assertEquals(planBefore.requiredThroughSourceEpoch(),
                        reconstructed.requiredThroughSourceEpoch());
                assertEquals(consumerEpochBefore,
                        documents.require(A).epoch());
                assertEquals(consumerBlueIdBefore,
                        documents.require(A).currentRepresentation().blueId());
                assertEquals(occurrenceBefore,
                        documents.occurrenceInventory().find(
                                work.consumerDocumentId(), work.targetPath())
                                .orElseThrow());
                assertEquals(aHistoryBefore, history(engine, A));
                assertEquals(bHistoryBefore, history(engine, B));
                assertEquals(cHistoryBefore, history(engine, C));
                assertEquals(aReceiptsBefore,
                        receiptIdentities(documents, A));
                assertEquals(bReceiptsBefore,
                        receiptIdentities(documents, B));
                assertEquals(cReceiptsBefore,
                        receiptIdentities(documents, C));
                assertTrue(documents.catchUpApplicationByWork(
                        work.workIdentity()).isEmpty());
                assertEquals(processCallsBefore, counter(
                        engine,
                        ManagedEpochApplicationExecutor.PROCESS_CALLS));
                return reconstructed.status();
            } finally {
                if (fault == CyclicProofFault.UNAVAILABLE) {
                    engine.objects().restoreProviderAvailability(
                            cyclicAfterBlueId);
                }
            }
        }
    }

    private static void applyProofFault(
            WholeObjectStore objects,
            String cyclicMemberBlueId,
            CyclicProofFault fault) {
        if (fault == CyclicProofFault.UNAVAILABLE) {
            objects.forceProviderUnavailable(cyclicMemberBlueId);
            assertEquals(NodeProviderOutcome.UNAVAILABLE,
                    objects.cyclicSetProofFor(cyclicMemberBlueId).outcome());
            return;
        }
        Map<String, CyclicSetProof> proofs = retainedCyclicProofs(objects);
        String masterBlueId = BlueIds.cyclicSetMasterBlueId(
                cyclicMemberBlueId);
        if (fault == CyclicProofFault.NOT_FOUND) {
            assertTrue(proofs.remove(masterBlueId) != null,
                    "the fixture must remove one retained source proof");
            assertEquals(NodeProviderOutcome.NOT_FOUND,
                    objects.cyclicSetProofFor(cyclicMemberBlueId).outcome());
            return;
        }
        CyclicSetProof invalid = CyclicSetProof.fromDeclaredPlaceholderSet(
                List.of(new Node().properties(
                        "corrupt", new Node().value(true))));
        assertTrue(proofs.put(masterBlueId, invalid) != null,
                "the fixture must replace one retained source proof");
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                objects.cyclicSetProofFor(cyclicMemberBlueId).outcome());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CyclicSetProof> retainedCyclicProofs(
            WholeObjectStore objects) {
        try {
            Field field = WholeObjectStore.class.getDeclaredField(
                    "cyclicProofByMasterBlueId");
            field.setAccessible(true);
            return (Map<String, CyclicSetProof>) field.get(objects);
        } catch (ReflectiveOperationException inaccessible) {
            throw new AssertionError(
                    "Cannot fault-inject retained cyclic proof state",
                    inaccessible);
        }
    }

    private enum CyclicProofFault {
        NOT_FOUND,
        UNAVAILABLE,
        INVALID_EVIDENCE
    }

    private static Scenario prepared() {
        return prepared(false);
    }

    private static Scenario prepared(boolean advanceBeforeAttachment) {
        return prepared(advanceBeforeAttachment, false);
    }

    private static Scenario prepared(
            boolean advanceBeforeAttachment, boolean mutateHistoricalSource) {
        BlueCoordination coordination = LegacyContracts10TestProfile.sdkBuilder().build();
        try {
            TimelineHandle aTimeline = coordination.timelines().register(
                    A_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(A, consumerYaml(mutateHistoricalSource))
                            .publicRoot()
                            .fromNow());
            ClosureHandle sourceClosure = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document("b", B, sourceYaml(mutateHistoricalSource))
                            .document("c", C, peerYaml())
                            .bindOccurrence("b", "/peer", "c")
                            .bindOccurrence("c", "/peer", "b")
                            .publicRoot("b")
                            .fromNow()
                            .build());
            DocumentHandle b = sourceClosure.document("b");
            DocumentHandle c = sourceClosure.document("c");
            assertFalse(a.exact().cyclicMember());
            assertTrue(b.exact().cyclicMember());
            assertTrue(c.exact().cyclicMember());
            ExactBlueValue bEpochZero = b.history().get(0).after();

            EntryResult sourceAdvanced = coordination.operations()
                    .on(b)
                    .from(bTimeline)
                    .call("attachParent")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "parent", a.snapshot().exact()))
                    .execute();
            assertEquals(EntryDisposition.APPLIED,
                    sourceAdvanced.disposition(),
                    sourceAdvanced.diagnostic().toString());
            assertEquals(1L, b.snapshot().epoch());
            assertFalse(a.exact().cyclicMember());
            if (advanceBeforeAttachment) {
                EntryResult later = coordination.operations()
                        .on(b)
                        .from(bTimeline)
                        .call("advance")
                        .through("ownerChannel")
                        .requestYaml("{}")
                        .execute();
                assertEquals(EntryDisposition.APPLIED,
                        later.disposition(), later.diagnostic().toString());
                assertEquals(2L, b.snapshot().epoch());
            }

            EntryHandle attachment = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attachChild")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "child", bEpochZero))
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());
            assertTrue(attached.managedEpochApplications().isEmpty());
            assertFalse(a.exact().cyclicMember(),
                    "the historical B representation has no parent edge");

            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) coordination.advanced()
                            .rawEngine();
            ManagedEpochApplicationWork work = engine.documents()
                    .nextCatchUpWork().orElseThrow();
            assertEquals(A, work.consumerDocumentId());
            assertEquals(B, work.sourceDocumentId());
            assertEquals(1L, work.sourceEpoch());
            return new Scenario(
                    coordination, engine, a, b, c,
                    aTimeline, bTimeline, work);
        } catch (RuntimeException | Error failure) {
            coordination.close();
            throw failure;
        }
    }

    private static List<String> history(
            DefaultCoordinationEngine engine,
            DocumentId documentId) {
        return engine.history(documentId).stream()
                .map(revision -> revision.epoch()
                        + "|" + revision.kind()
                        + "|" + revision.before()
                                .map(value -> value.blueId())
                                .orElse("-")
                        + "|" + revision.after().blueId()
                        + "|" + revision.managedEpochReceipt()
                                .map(ManagedEpochReceipt::receiptIdentity)
                                .orElse("-"))
                .toList();
    }

    private static List<String> receiptIdentities(
            InMemoryDocumentStore documents,
            DocumentId documentId) {
        return documents.managedEpochReceipts(documentId).stream()
                .map(ManagedEpochReceipt::receiptIdentity)
                .toList();
    }

    private static long counter(
            DefaultCoordinationEngine engine,
            String name) {
        return engine.metricsSnapshot().counters().getOrDefault(name, 0L);
    }

    private static ManagedEpochInvocationCapturer invocationCapturer(
            ContractsClosureAdapter adapter) {
        try {
            Field executorField = ContractsClosureAdapter.class
                    .getDeclaredField("managedEpochApplicationExecutor");
            executorField.setAccessible(true);
            ManagedEpochApplicationExecutor executor =
                    (ManagedEpochApplicationExecutor) executorField.get(
                            adapter);
            Field capturerField = ManagedEpochApplicationExecutor.class
                    .getDeclaredField("invocationCapturer");
            capturerField.setAccessible(true);
            return (ManagedEpochInvocationCapturer) capturerField.get(
                    executor);
        } catch (ReflectiveOperationException inaccessible) {
            throw new AssertionError(
                    "Cannot inspect retained invocation boundary",
                    inaccessible);
        }
    }

    private static String consumerYaml(boolean mutateHistoricalSource) {
        String yaml = consumerYaml();
        return mutateHistoricalSource ? yaml.replace(
                "val: {$add: [{$document: /finiteReactions}, 1]}",
                "val: {$add: [{$document: /finiteReactions}, 1]}\n"
                        + "          - $appendEvent: {type: Coordination/Event, kind: Cycle/Mutation}")
                : yaml;
    }

    private static String sourceYaml(boolean mutateHistoricalSource) {
        return sourceYaml() + (mutateHistoricalSource ? """
                  mutationFromParent:
                    type: Embedded Node Channel
                    sourcePath: /parent
                    event: {type: Coordination/Event, kind: Cycle/Mutation}
                  mutateSource:
                    type: Coordination/Sequential Workflow
                    channel: mutationFromParent
                    event: {type: Coordination/Event, kind: Cycle/Mutation}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: {$add: [{$document: /counter}, 1]}
                          - $return: true
                """ : "");
    }

    private static String consumerYaml() {
        return """
                documentId: %s
                finiteReactions: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /child
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachChild:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      child: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /child
                              val: {$binding: event/message/request/child}
                          - $return: true
                  detachChild:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /child}
                          - $return: true
                  fromFinite:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event: {type: Coordination/Event, kind: Cycle/Finite}
                  settleFinite:
                    type: Coordination/Sequential Workflow
                    channel: fromFinite
                    event: {type: Coordination/Event, kind: Cycle/Finite}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /finiteReactions
                              val: {$add: [{$document: /finiteReactions}, 1]}
                          - $return: true
                """.formatted(A.value(), A_TIMELINE, ACTOR);
    }

    private static String sourceYaml() {
        return """
                documentId: %s
                counter: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                      - /parent
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachParent:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      parent: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /parent
                              val: {$binding: event/message/request/parent}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Cycle/Finite
                          - $return: true
                  advance:
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
                          - $return: true
                """.formatted(B.value(), B_TIMELINE, ACTOR);
    }

    private static String peerYaml() {
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                """.formatted(C.value());
    }

    private record Scenario(
            BlueCoordination coordination,
            DefaultCoordinationEngine engine,
            DocumentHandle a,
            DocumentHandle b,
            DocumentHandle c,
            TimelineHandle aTimeline,
            TimelineHandle bTimeline,
            ManagedEpochApplicationWork work) implements AutoCloseable {
        @Override
        public void close() {
            coordination.close();
        }
    }

    private record DurableImage(
            InMemoryDocumentStore.PublicationSnapshot publication,
            List<String> aHistory,
            List<String> bHistory,
            List<String> cHistory,
            List<String> aReceipts,
            List<String> bReceipts,
            List<String> cReceipts,
            String aRepresentation,
            String bRepresentation,
            String cRepresentation,
            String planSnapshotIdentity,
            int workCount,
            int applicationCount) {
        private DurableImage {
            aHistory = List.copyOf(aHistory);
            bHistory = List.copyOf(bHistory);
            cHistory = List.copyOf(cHistory);
            aReceipts = List.copyOf(aReceipts);
            bReceipts = List.copyOf(bReceipts);
            cReceipts = List.copyOf(cReceipts);
        }

        static DurableImage capture(Scenario scenario) {
            DefaultCoordinationEngine engine = scenario.engine();
            InMemoryDocumentStore documents = engine.documents();
            CatchUpPlanStore plans = documents.catchUpPlansSnapshot();
            return new DurableImage(
                    documents.publicationSnapshot(),
                    history(engine, A),
                    history(engine, B),
                    history(engine, C),
                    receiptIdentities(documents, A),
                    receiptIdentities(documents, B),
                    receiptIdentities(documents, C),
                    documents.require(A).currentRepresentation().blueId(),
                    documents.require(B).currentRepresentation().blueId(),
                    documents.require(C).currentRepresentation().blueId(),
                    documents.catchUpPlan(
                                    scenario.work().planIdentity())
                            .orElseThrow().snapshotIdentity(),
                    plans.workCount(),
                    plans.applicationCount());
        }
    }
}
