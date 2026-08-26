package blue.coordination.sdk;

import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-SDK acceptance for operation-created managed lineages. */
final class SdkManagedDraftAcceptanceTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";

    private static final DocumentId ORDER_HOST = DocumentId.of(
            "sdk-managed-order-host");
    private static final DocumentId ORDER = DocumentId.of(
            "sdk-managed-order-456");
    private static final String ORDER_TIMELINE =
            "sdk/managed-order/alice";

    private static final DocumentId MULTIPLICITY_HOST = DocumentId.of(
            "sdk-managed-multiplicity-host");
    private static final DocumentId ALPHA = DocumentId.of(
            "sdk-managed-child-alpha");
    private static final DocumentId BETA = DocumentId.of(
            "sdk-managed-child-beta");
    private static final DocumentId GAMMA = DocumentId.of(
            "sdk-managed-child-gamma");
    private static final String MULTIPLICITY_TIMELINE =
            "sdk/managed-multiplicity/alice";

    @Test
    void createOrderDraftInitializesOnceAndPublishesAtomically() {
        // given
        Submission submission = Submission.EXECUTE;

        // when
        RunEvidence evidence = runSingleDraft(submission);

        // then
        assertEquals(EntryDisposition.APPLIED, evidence.disposition());
    }

    @Test
    void managedSubmitIsAppendOnlyAndMatchesExecuteExactly() {
        // given
        Submission execute = Submission.EXECUTE;
        Submission submit = Submission.SUBMIT;

        // when
        RunEvidence executed = runSingleDraft(execute);
        RunEvidence submitted = runSingleDraft(submit);

        // then
        assertEquals(executed, submitted);
    }

    @Test
    void fiveOccurrencesInitializeThreeLineagesAndDeliverFiveEvents() {
        // given
        Variant firstVariant = Variant.SCRAMBLED;
        Variant secondVariant = Variant.REVERSED;

        // when
        RunEvidence scrambled = runMultiplicity(firstVariant);
        RunEvidence reversed = runMultiplicity(secondVariant);

        // then
        assertEquals(scrambled, reversed,
                "request, expectation, and object authoring order must not "
                        + "change exact managed-publication evidence");
    }

    @Test
    void managedFailureMatrixRollsBackHostAndEveryDraft() {
        // given
        List<TerminalFailure> failures = List.of(TerminalFailure.values());

        // when
        List<TerminalFailureEvidence> evidence = failures.stream()
                .map(SdkManagedDraftAcceptanceTest::runTerminalFailure)
                .toList();

        // then
        assertEquals(TerminalFailure.values().length, evidence.size());
        evidence.forEach(item -> {
            assertEquals(EntryDisposition.REJECTED, item.disposition(),
                    item.failure().name());
            assertEquals(item.failure().diagnosticCode,
                    item.diagnosticCode(), item.failure().name());
            assertTrue(item.rollbackPreserved(), item.failure().name());
            assertTrue(item.retryRecovered(), item.failure().name());
        });
    }

    @Test
    void extraOccurrencesUseAutomaticExpansionWithoutReselectingTheFeeder() {
        // given
        // Dynamic occurrence resolution supersedes the legacy closed-surface
        // rejection for SINGLE_PATCH_EXTRA and SEQUENTIAL_EXTRA.  Supplying
        // one advanced explicit expectation must remain semantically equal to
        // the ordinary automatic path: the additional occurrence is resolved
        // in one bounded retry and the Timeline Entry is published once.
        List<String> operations = List.of(
                "singlePatchExtra", "sequentialExtra");

        // when
        for (String operation : operations) {
            String suffix = operation.toLowerCase(Locale.ROOT);
            DocumentId hostId = DocumentId.of(
                    "sdk-managed-extra-host-" + suffix);
            DocumentId childId = DocumentId.of(
                    "sdk-managed-extra-child-" + suffix);
            String timelineId = "sdk/managed-extra/" + suffix;
            try (BlueCoordination coordination = BlueCoordination.inMemory()) {
                TimelineHandle timeline = coordination.timelines().register(
                        timelineId, ACTOR);
                DocumentHandle host = coordination.documents().admit(
                        ManagedDocument.yaml(
                                        hostId,
                                        failureHost(hostId, timelineId))
                                .publicRoot()
                                .fromNow());
                ManagedDocumentDraft child = draft(
                        coordination, childId, false);
                DocumentId automaticChildId = DocumentId.of(
                        child.initial().blueId());

                EntryResult result = coordination.operations()
                        .on(host)
                        .from(timeline)
                        .call(operation)
                        .through("ownerChannel")
                        .request(request -> request.managed("order", child))
                        .expectOccurrence("/orders/expected", child)
                        .execute();

                // then
                assertEquals(EntryDisposition.APPLIED, result.disposition(),
                        operation);
                assertEquals(1, result.closures().size(), operation);
                ClosureResult closure = result.closures().get(0);
                assertEquals(2L, closure.processorAttemptCount(), operation);
                assertEquals(1L, closure.automaticRetryCount(), operation);
                ManagedSurfaceEvidence surface =
                        closure.managedSurfaceEvidence();
                assertTrue(surface.present(), operation);
                assertEquals(1, surface.resolvedOccurrences().size(),
                        operation);
                ManagedSurfaceEvidence.OccurrenceResolution resolution =
                        surface.resolvedOccurrences().get(0);
                assertEquals(
                        ManagedSurfaceEvidence.ResolutionKind.NEW_AUTHORED,
                        resolution.kind(), operation);
                assertEquals(hostId,
                        resolution.occurrence().sourceDocumentId(),
                        operation);
                assertEquals("/orders/extra",
                        resolution.occurrence().sourcePath(), operation);
                assertEquals(automaticChildId,
                        resolution.occurrence().targetDocumentId(),
                        operation);
                assertTrue(resolution.occurrence().active(), operation);
                assertEquals(child.initial().blueId(),
                        resolution.authoredInitial().orElseThrow().blueId(),
                        operation);
                assertTrue(surface.graphChanges().stream().anyMatch(
                        change -> change.kind()
                                        == ManagedSurfaceEvidence
                                                .GraphChangeKind.ADD
                                && change.sourceDocumentId().equals(hostId)
                                && change.sourcePath().equals(
                                        "/orders/extra")
                                && change.after().orElseThrow()
                                        .targetDocumentId()
                                        .equals(automaticChildId)),
                        operation);
                assertFalse(surface.componentTransitions().isEmpty(),
                        operation);
                assertFalse(surface.subscriptionChanges().isEmpty(),
                        operation);
                assertEquals(Set.of(hostId, childId, automaticChildId),
                        closure.changes().stream()
                                .map(DocumentChange::documentId)
                                .collect(java.util.stream.Collectors.toSet()),
                        operation);
                assertEquals(3L, closure.stats().documentsOpened(), operation);
                assertEquals(1L, closure.stats().documentStepOrder().stream()
                        .filter(hostId::equals)
                        .count(), operation);

                DocumentHandle explicitChild = coordination.documents()
                        .require(childId);
                DocumentHandle automaticChild = coordination.documents()
                        .require(automaticChildId);
                assertEquals(1L, explicitChild.snapshot()
                        .longAt("/initializationCount"), operation);
                assertEquals(1L, automaticChild.snapshot()
                        .longAt("/initializationCount"), operation);
                assertEquals(1, explicitChild.history().size(), operation);
                assertEquals(1, automaticChild.history().size(), operation);
                assertEquals(explicitChild.snapshot().blueId(),
                        host.snapshot().valueAt("/orders/expected").blueId(),
                        operation);
                assertEquals(automaticChild.snapshot().blueId(),
                        host.snapshot().valueAt("/orders/extra").blueId(),
                        operation);

                int hostHistory = host.history().size();
                DrainResult replayFence = coordination.processing().drain();
                assertTrue(replayFence.entries().isEmpty(), operation);
                assertTrue(replayFence.quiescent(), operation);
                assertEquals(hostHistory, host.history().size(), operation);
                assertEquals(1, explicitChild.history().size(), operation);
                assertEquals(1, automaticChild.history().size(), operation);
            }
        }
    }

    @Test
    void automaticOccurrenceResolutionReusesTheExactCurrentLineage() {
        // given

        DocumentId hostId = DocumentId.of(
                "sdk-managed-current-reuse-host");
        DocumentId existingId = DocumentId.of(
                "sdk-managed-current-reuse-child");
        String timelineId = "sdk/managed-current-reuse/alice";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    hostId,
                                    singleDraftHost(
                                            hostId,
                                            timelineId,
                                            "attachCurrent"))
                            .publicRoot()
                            .fromNow());
            DocumentHandle existing = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    existingId,
                                    lifecycleDocument(existingId, false))
                            .publicRoot()
                            .fromNow());
            String currentBlueId = existing.snapshot().blueId();
            int historyBefore = existing.history().size();

            // when

            EntryResult result = coordination.operations()
                    .on(host)
                    .from(timeline)
                    .call("attachCurrent")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "order", existing.exact()))
                    .execute();

            // then

            assertEquals(EntryDisposition.APPLIED, result.disposition());
            ClosureResult closure = result.closures().get(0);
            assertEquals(2L, closure.processorAttemptCount());
            ManagedSurfaceEvidence surface =
                    closure.managedSurfaceEvidence();
            assertEquals(1, surface.resolvedOccurrences().size());
            ManagedSurfaceEvidence.OccurrenceResolution resolution =
                    surface.resolvedOccurrences().get(0);
            assertEquals(
                    ManagedSurfaceEvidence.ResolutionKind.CURRENT_EXISTING,
                    resolution.kind());
            assertTrue(resolution.authoredInitial().isEmpty());
            assertEquals(hostId,
                    resolution.occurrence().sourceDocumentId());
            assertEquals("/orders/order-456",
                    resolution.occurrence().sourcePath());
            assertEquals(existingId,
                    resolution.occurrence().targetDocumentId());
            assertEquals(currentBlueId,
                    resolution.occurrence().expectedTargetBlueId());
            assertTrue(resolution.occurrence().active());
            assertEquals(currentBlueId, existing.snapshot().blueId());
            assertEquals(historyBefore, existing.history().size());
            assertEquals(currentBlueId,
                    host.snapshot().valueAt("/orders/order-456").blueId());
        }
    }

    @Test
    void malformedManagedEvidenceFailsBeforeTheFirstAppend() {
        // given
        DocumentId hostId = DocumentId.of("sdk-managed-preflight-host");
        DocumentId childId = DocumentId.of("sdk-managed-preflight-child");
        DocumentId otherId = DocumentId.of(
                "sdk-managed-preflight-other");
        String timelineId = "sdk/managed-preflight/alice";
        try (BlueCoordination coordination = BlueCoordination.inMemory();
                BlueCoordination foreign = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    hostId,
                                    singleDraftHost(
                                            hostId,
                                            timelineId,
                                            "validCreate"))
                            .publicRoot()
                            .fromNow());
            ManagedDocumentDraft child = draft(
                    coordination, childId, false);
            ManagedDocumentDraft other = draft(
                    coordination, otherId, false);
            ManagedDocumentDraft foreignChild = draft(
                    foreign,
                    DocumentId.of("sdk-managed-preflight-foreign"),
                    false);
            String before = host.snapshot().blueId();

            // when
            IllegalArgumentException duplicate = assertThrows(
                    IllegalArgumentException.class,
                    () -> managedCall(
                            coordination,
                            host,
                            timeline,
                            "validCreate",
                            child,
                            "/orders/order-456")
                            .expectOccurrence("/orders/order-456", child)
                            .submit());

            // then
            assertTrue(duplicate.getMessage().startsWith(
                    "DUPLICATE_MANAGED_OCCURRENCE_PATH:"));

            IllegalArgumentException conflicting = assertThrows(
                    IllegalArgumentException.class,
                    () -> coordination.operations()
                            .on(host)
                            .from(timeline)
                            .call("validCreate")
                            .through("ownerChannel")
                            .request(request -> request
                                    .managed("order", child)
                                    .managed("other", other))
                            .expectOccurrence(
                                    "/orders/order-456", child)
                            .expectOccurrence(
                                    "/orders/order-456", other)
                            .submit());
            assertTrue(conflicting.getMessage().startsWith(
                    "DUPLICATE_MANAGED_OCCURRENCE_PATH:"));

            IllegalArgumentException wrongOwner = assertThrows(
                    IllegalArgumentException.class,
                    () -> managedCall(
                            coordination,
                            host,
                            timeline,
                            "validCreate",
                            foreignChild,
                            "/orders/order-456")
                            .submit());
            assertTrue(wrongOwner.getMessage().startsWith(
                    "MANAGED_DRAFT_OWNER_MISMATCH:"));

            ManagedDocumentDraft imported = child.atEpoch(0L);
            UnsupportedOperationException importedState = assertThrows(
                    UnsupportedOperationException.class,
                    () -> managedCall(
                            coordination,
                            host,
                            timeline,
                            "validCreate",
                            imported,
                            "/orders/order-456")
                            .submit());
            assertTrue(importedState.getMessage().startsWith(
                    "UNSUPPORTED_MANAGED_DRAFT_IMPORT:"));

            UnsupportedOperationException activation = assertThrows(
                    UnsupportedOperationException.class,
                    () -> managedCall(
                            coordination,
                            host,
                            timeline,
                            "validCreate",
                            child,
                            "/orders/order-456")
                            .activation(ActivationPolicy
                                    .attachCurrentState())
                            .submit());
            assertTrue(activation.getMessage().startsWith(
                    "UNSUPPORTED_MANAGED_DRAFT_ACTIVATION_POLICY:"));

            IllegalArgumentException undeclared = assertThrows(
                    IllegalArgumentException.class,
                    () -> managedCall(
                            coordination,
                            host,
                            timeline,
                            "validCreate",
                            child,
                            "/outside/order-456")
                            .submit());
            assertTrue(undeclared.getMessage().contains(
                    "Process Embedded"));

            assertEquals(before, host.snapshot().blueId());
            assertEquals(0L, host.snapshot().epoch());
            assertDocumentAbsent(coordination, childId);
            assertDocumentAbsent(coordination, otherId);

            EntryHandle valid = managedCall(
                    coordination,
                    host,
                    timeline,
                    "validCreate",
                    child,
                    "/orders/order-456")
                    .submit();
            assertEquals(1L, valid.globalSequence().orElseThrow());
            assertEquals(1L, valid.timelineSequence().orElseThrow());
            assertEquals(before, host.snapshot().blueId());
            assertDocumentAbsent(coordination, childId);

            DrainResult drained = coordination.processing().drain();
            assertTrue(drained.quiescent());
            assertEquals(EntryDisposition.APPLIED,
                    drained.entry(valid).disposition());
            assertEquals(1L, coordination.documents().require(childId)
                    .snapshot().longAt("/initializationCount"));
        }
    }

    @Test
    void cyclicManagedDraftTargetFailsBeforeAppend() {
        // given
        DocumentId cyclicA = DocumentId.of(
                "sdk-managed-cyclic-preflight-a");
        DocumentId cyclicB = DocumentId.of(
                "sdk-managed-cyclic-preflight-b");
        DocumentId childId = DocumentId.of(
                "sdk-managed-cyclic-preflight-child");
        String timelineId = "sdk/managed-cyclic-preflight/alice";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            ClosureHandle closure = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document(
                                    "a",
                                    cyclicA,
                                    cyclicDraftHost(
                                            cyclicA, timelineId))
                            .document(
                                    "b",
                                    cyclicB,
                                    cyclicDraftPeer(cyclicB))
                            .bindOccurrence("a", "/peer", "b")
                            .bindOccurrence("b", "/peer", "a")
                            .publicRoot("a")
                            .fromNow()
                            .build());
            DocumentHandle host = closure.document("a");
            ManagedDocumentDraft child = draft(
                    coordination, childId, false);
            String before = host.snapshot().blueId();

            // when
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> managedCall(
                            coordination,
                            host,
                            timeline,
                            "createOrder",
                            child,
                            "/orders/order-456")
                            .submit());
            EntryHandle next = coordination.operations()
                    .on(host)
                    .from(timeline)
                    .call("touch")
                    .through("ownerChannel")
                    .submit();

            // then
            assertTrue(host.exact().cyclicMember());
            assertTrue(failure.getMessage().contains("cyclic-set member"));
            assertEquals(before, host.snapshot().blueId());
            assertEquals(0L, host.snapshot().epoch());
            assertEquals(1L, next.globalSequence().orElseThrow());
            assertEquals(1L, next.timelineSequence().orElseThrow());
            assertDocumentAbsent(coordination, childId);
        }
    }

    @Test
    void managedDraftCatalogCrossingACycleFailsBeforeAppend() {
        // given
        DocumentId hostId = DocumentId.of(
                "sdk-managed-cycle-crossing-host");
        DocumentId cyclicA = DocumentId.of(
                "sdk-managed-cycle-crossing-a");
        DocumentId cyclicB = DocumentId.of(
                "sdk-managed-cycle-crossing-b");
        DocumentId childId = DocumentId.of(
                "sdk-managed-cycle-crossing-child");
        String timelineId = "sdk/managed-cycle-crossing/alice";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            ClosureHandle closure = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document(
                                    "host",
                                    hostId,
                                    cyclicDraftHost(
                                            hostId, timelineId))
                            .document(
                                    "a",
                                    cyclicA,
                                    cyclicDraftPeer(cyclicA))
                            .document(
                                    "b",
                                    cyclicB,
                                    cyclicDraftPeer(cyclicB))
                            .bindOccurrence("host", "/peer", "a")
                            .bindOccurrence("a", "/peer", "b")
                            .bindOccurrence("b", "/peer", "a")
                            .publicRoot("host")
                            .fromNow()
                            .build());
            DocumentHandle host = closure.document("host");
            ManagedDocumentDraft child = draft(
                    coordination, childId, false);
            String before = host.snapshot().blueId();

            // when
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> managedCall(
                            coordination,
                            host,
                            timeline,
                            "createOrder",
                            child,
                            "/orders/order-456")
                            .submit());
            EntryHandle next = coordination.operations()
                    .on(host)
                    .from(timeline)
                    .call("touch")
                    .through("ownerChannel")
                    .submit();

            // then
            assertFalse(host.exact().cyclicMember());
            assertTrue(closure.document("a").exact().cyclicMember());
            assertTrue(closure.document("b").exact().cyclicMember());
            assertTrue(failure.getMessage().contains("cyclic-set member"));
            assertEquals(before, host.snapshot().blueId());
            assertEquals(0L, host.snapshot().epoch());
            assertEquals(1L, next.globalSequence().orElseThrow());
            assertEquals(1L, next.timelineSequence().orElseThrow());
            assertDocumentAbsent(coordination, childId);
        }
    }

    @Test
    void appliedManagedParentCanCreateAManagedGrandchild() {
        // given
        DocumentId hostId = DocumentId.of("sdk-managed-growth-host");
        DocumentId parentId = DocumentId.of("sdk-managed-growth-parent");
        DocumentId childId = DocumentId.of("sdk-managed-growth-child");
        String hostTimelineId = "sdk/managed-growth/host";
        String parentTimelineId = "sdk/managed-growth/parent";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle hostTimeline = coordination.timelines().register(
                    hostTimelineId, ACTOR);
            TimelineHandle parentTimeline = coordination.timelines().register(
                    parentTimelineId, ACTOR);
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    hostId,
                                    nestedGrowthHost(
                                            hostId, hostTimelineId))
                            .publicRoot()
                            .fromNow());
            ManagedDocumentDraft parentDraft = coordination.documents()
                    .draft(
                            parentId,
                            coordination.values().yaml(nestedGrowthParent(
                                    parentId, parentTimelineId)));
            ManagedDocumentDraft childDraft = draft(
                    coordination, childId, false);

            // when
            EntryResult parentCreated = coordination.operations()
                    .on(host)
                    .from(hostTimeline)
                    .call("createParent")
                    .through("ownerChannel")
                    .request(request -> request.managed(
                            "parent", parentDraft))
                    .expectOccurrence("/parents/primary", parentDraft)
                    .activation(ActivationPolicy.fromNow())
                    .execute();
            DocumentHandle parent = coordination.documents().require(
                    parentId);
            String parentBeforeChild = parent.snapshot().blueId();
            String hostBeforeChild = host.snapshot().blueId();
            int hostHistoryBeforeChild = host.history().size();
            long parentEpochBeforePromotion = parent.snapshot().epoch();
            int parentHistoryBeforePromotion = parent.history().size();
            int entriesBeforePromotion = coordination.advanced().rawEngine()
                    .metrics().journalEntryCount();
            DocumentHandle promoted = coordination.documents()
                    .promotePublicRoot(parentId);
            assertEquals(parentId, promoted.id());
            assertEquals(parentBeforeChild, parent.snapshot().blueId());
            assertEquals(parentEpochBeforePromotion, parent.snapshot().epoch());
            assertEquals(parentHistoryBeforePromotion, parent.history().size());
            assertEquals(entriesBeforePromotion, coordination.advanced()
                    .rawEngine().metrics().journalEntryCount());
            EntryResult childCreated = coordination.operations()
                    .on(parent)
                    .from(parentTimeline)
                    .call("createChild")
                    .through("ownerChannel")
                    .request(request -> request.managed(
                            "child", childDraft))
                    .expectOccurrence("/children/primary", childDraft)
                    .activation(ActivationPolicy.fromNow())
                    .execute();
            DocumentHandle child = coordination.documents().require(childId);

            // then
            assertApplied(parentCreated);
            assertApplied(childCreated);
            // The promoted parent is an independent Root. Its child command
            // opens only the forward parent -> child closure; the host keeps
            // the exact retained parent occurrence created by the first call.
            assertEquals(parentBeforeChild,
                    host.snapshot().valueAt("/parents/primary").blueId());
            assertEquals(hostBeforeChild, host.snapshot().blueId());
            assertEquals(hostHistoryBeforeChild, host.history().size());
            assertEquals(child.snapshot().blueId(),
                    parent.snapshot().valueAt("/children/primary").blueId());
            assertNotEquals(parentBeforeChild, parent.snapshot().blueId());
            assertTrue(changedDocuments(parentCreated).containsAll(
                    Set.of(hostId, parentId)));
            assertTrue(changedDocuments(childCreated).containsAll(
                    Set.of(parentId, childId)));
            assertTrue(host.snapshot().ready());
            assertTrue(parent.snapshot().ready());
            assertTrue(child.snapshot().ready());
            assertFalse(host.exact().cyclicMember());
            assertFalse(parent.exact().cyclicMember());
            assertFalse(child.exact().cyclicMember());
            assertEquals(List.of(
                            DocumentRevision.Kind.INITIALIZATION,
                            DocumentRevision.Kind.TIMELINE_ENTRY),
                    parent.history().stream()
                            .map(DocumentRevision::kind)
                            .toList());
            assertEquals(List.of(DocumentRevision.Kind.INITIALIZATION),
                    child.history().stream()
                            .map(DocumentRevision::kind)
                            .toList());
        }
    }

    private static RunEvidence runSingleDraft(Submission submission) {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    ORDER_TIMELINE, ACTOR);
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    ORDER_HOST,
                                    singleDraftHost(
                                            ORDER_HOST,
                                            ORDER_TIMELINE,
                                            "createOrder"))
                            .publicRoot()
                            .fromNow());
            ManagedDocumentDraft order = draft(
                    coordination, ORDER, false);
            String hostBefore = host.snapshot().blueId();
            String draftBlueId = order.initial().blueId();

            OperationCall call = managedCall(
                    coordination,
                    host,
                    timeline,
                    "createOrder",
                    order,
                    "/orders/order-456");
            EntryResult result;
            if (submission == Submission.SUBMIT) {
                EntryHandle submitted = call.submit();
                assertEquals(1L,
                        submitted.globalSequence().orElseThrow());
                assertEquals(1L,
                        submitted.timelineSequence().orElseThrow());
                assertEquals(hostBefore, host.snapshot().blueId());
                assertEquals(0L, host.snapshot().epoch());
                assertDocumentAbsent(coordination, ORDER);
                DrainResult drain = coordination.processing().drain();
                assertTrue(drain.quiescent());
                result = drain.entry(submitted);
            } else {
                result = call.execute();
            }

            DocumentHandle child = coordination.documents().require(ORDER);
            assertSingleDraftResult(
                    result,
                    host,
                    child,
                    hostBefore,
                    draftBlueId);
            return evidence(result, List.of(host, child));
        }
    }

    private static void assertSingleDraftResult(
            EntryResult result,
            DocumentHandle host,
            DocumentHandle child,
            String hostBefore,
            String draftBlueId) {
        assertApplied(result);
        assertEquals(1L,
                result.entry().globalSequence().orElseThrow());
        assertEquals(1L,
                result.entry().timelineSequence().orElseThrow());
        assertEquals(List.of(ORDER_HOST, ORDER, ORDER),
                result.stats().documentStepOrder());
        assertEquals(2L, result.stats().committedTransitions());
        assertEquals(2L, result.stats().documentsOpened());
        assertCounterUnits(
                result.stats(),
                "processor.closureWorkOccurrenceEnqueued",
                3L,
                5L);
        assertCounterUnits(
                result.stats(),
                "processor.closureWorkOccurrenceDequeued",
                3L,
                5L);
        assertCounterUnits(
                result.stats(),
                "processor.processorMarkerWritten",
                1L,
                20L);
        assertCounterUnits(
                result.stats(),
                "processor.lifecycleDelivered",
                1L,
                30L);
        assertEquals(0L, result.stats().counter(
                "processor.internalEventEnqueued"));
        assertEquals(0L, result.stats().counter(
                "processor.internalEventDequeued"));
        assertEquals(0L, result.stats().counter(
                "processor.rootEventRecorded"));
        assertGasIsFullyAccounted(result.stats());
        assertEquals(Set.of(ORDER_HOST, ORDER), changedDocuments(result));
        assertTrue(result.publicEvents().isEmpty());

        assertEquals(1L, host.snapshot().epoch());
        assertEquals(0L, child.snapshot().epoch());
        assertEquals(1L, child.snapshot().longAt(
                "/initializationCount"));
        assertEquals(child.snapshot().blueId(),
                host.snapshot().valueAt("/orders/order-456").blueId());
        assertNotEquals(draftBlueId, child.snapshot().blueId());
        assertNotEquals(hostBefore, host.snapshot().blueId());
        assertFalse(host.exact().cyclicMember());
        assertFalse(child.exact().cyclicMember());
        assertTrue(host.snapshot().publicEvents().isEmpty());
        assertTrue(child.snapshot().publicEvents().isEmpty());

        assertHistory(
                host,
                List.of(0L, 1L),
                List.of(
                        DocumentRevision.Kind.INITIALIZATION,
                        DocumentRevision.Kind.TIMELINE_ENTRY));
        assertHistory(
                child,
                List.of(0L),
                List.of(DocumentRevision.Kind.INITIALIZATION));
        assertTrue(host.history().get(0).sourceEntry().isEmpty());
        assertEquals(result.entry(),
                host.history().get(1).sourceEntry().orElseThrow());
        assertTrue(child.history().get(0).sourceEntry().isEmpty());
        assertTrue(child.history().get(0).publicEvents().isEmpty());
        assertEquals(result.stats().gas(),
                host.history().get(1).processingGas()
                        + child.history().get(0).processingGas());
    }

    private static RunEvidence runMultiplicity(Variant variant) {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    MULTIPLICITY_TIMELINE, ACTOR);
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    MULTIPLICITY_HOST,
                                    multiplicityHost(variant))
                            .publicRoot()
                            .fromNow());
            ManagedDocumentDraft alpha = draft(
                    coordination, ALPHA, true);
            ManagedDocumentDraft beta = draft(
                    coordination, BETA, true);
            ManagedDocumentDraft gamma = draft(
                    coordination, GAMMA, true);
            Map<DocumentId, String> draftBlueIds = Map.of(
                    ALPHA, alpha.initial().blueId(),
                    BETA, beta.initial().blueId(),
                    GAMMA, gamma.initial().blueId());

            OperationCall call = coordination.operations()
                    .on(host)
                    .from(timeline)
                    .call("createChildren")
                    .through("ownerChannel")
                    .request(request -> addManagedRequestFields(
                            request, variant, alpha, beta, gamma));
            addOccurrenceExpectations(
                    call, variant, alpha, beta, gamma);
            EntryResult result = call.execute();

            DocumentHandle alphaHandle = coordination.documents()
                    .require(ALPHA);
            DocumentHandle betaHandle = coordination.documents()
                    .require(BETA);
            DocumentHandle gammaHandle = coordination.documents()
                    .require(GAMMA);
            assertMultiplicityResult(
                    coordination,
                    result,
                    host,
                    List.of(alphaHandle, betaHandle, gammaHandle),
                    draftBlueIds);
            return evidence(
                    result,
                    List.of(
                            host,
                            alphaHandle,
                            betaHandle,
                            gammaHandle));
        }
    }

    private static void assertMultiplicityResult(
            BlueCoordination coordination,
            EntryResult result,
            DocumentHandle host,
            List<DocumentHandle> children,
            Map<DocumentId, String> draftBlueIds) {
        assertApplied(result);
        List<DocumentId> expectedWork = List.of(
                MULTIPLICITY_HOST,
                ALPHA,
                ALPHA,
                MULTIPLICITY_HOST,
                MULTIPLICITY_HOST,
                BETA,
                BETA,
                MULTIPLICITY_HOST,
                MULTIPLICITY_HOST,
                GAMMA,
                GAMMA,
                MULTIPLICITY_HOST);
        assertEquals(expectedWork, result.stats().documentStepOrder());
        assertEquals(4L, result.stats().committedTransitions());
        assertEquals(4L, result.stats().documentsOpened());
        assertCounterUnits(
                result.stats(),
                "processor.closureWorkOccurrenceEnqueued",
                12L,
                5L);
        assertCounterUnits(
                result.stats(),
                "processor.closureWorkOccurrenceDequeued",
                12L,
                5L);
        assertCounterUnits(
                result.stats(),
                "processor.processorMarkerWritten",
                3L,
                20L);
        assertCounterUnits(
                result.stats(),
                "processor.lifecycleDelivered",
                3L,
                30L);
        assertCounterUnits(
                result.stats(),
                "processor.embeddedEventDelivered",
                5L,
                10L);
        assertCounterUnits(
                result.stats(),
                "processor.internalEventEnqueued",
                8L,
                20L);
        assertCounterUnits(
                result.stats(),
                "processor.internalEventDequeued",
                8L,
                10L);
        assertCounterUnits(
                result.stats(),
                "processor.rootEventRecorded",
                5L,
                5L);
        assertGasIsFullyAccounted(result.stats());
        assertEquals(Set.of(
                        MULTIPLICITY_HOST,
                        ALPHA,
                        BETA,
                        GAMMA),
                changedDocuments(result));

        assertEquals(5L, host.snapshot().longAt("/observedTotal"));
        assertEquals(2L, host.snapshot().longAt("/observedAlpha"));
        assertEquals(2L, host.snapshot().longAt("/observedBeta"));
        assertEquals(1L, host.snapshot().longAt("/observedGamma"));
        assertEquals(1L, host.snapshot().epoch());
        assertHistory(
                host,
                List.of(0L, 1L),
                List.of(
                        DocumentRevision.Kind.INITIALIZATION,
                        DocumentRevision.Kind.TIMELINE_ENTRY));
        assertEquals(result.entry(),
                host.history().get(1).sourceEntry().orElseThrow());
        assertFalse(host.exact().cyclicMember());

        Map<DocumentId, DocumentHandle> byId = new LinkedHashMap<>();
        for (DocumentHandle child : children) {
            byId.put(child.id(), child);
            assertEquals(0L, child.snapshot().epoch());
            assertEquals(1L, child.snapshot().longAt(
                    "/initializationCount"));
            assertNotEquals(
                    draftBlueIds.get(child.id()),
                    child.snapshot().blueId());
            assertFalse(child.exact().cyclicMember());
            assertHistory(
                    child,
                    List.of(0L),
                    List.of(DocumentRevision.Kind.INITIALIZATION));
            assertTrue(child.history().get(0).sourceEntry().isEmpty());
            assertTrue(child.history().get(0).publicEvents().isEmpty());
            assertTrue(child.snapshot().publicEvents().isEmpty());
        }

        assertEquals(byId.get(ALPHA).snapshot().blueId(),
                host.snapshot().valueAt(
                        "/children/alphaFirst").blueId());
        assertEquals(byId.get(ALPHA).snapshot().blueId(),
                host.snapshot().valueAt(
                        "/children/alphaSecond").blueId());
        assertEquals(byId.get(BETA).snapshot().blueId(),
                host.snapshot().valueAt(
                        "/children/betaFirst").blueId());
        assertEquals(byId.get(BETA).snapshot().blueId(),
                host.snapshot().valueAt(
                        "/children/betaSecond").blueId());
        assertEquals(byId.get(GAMMA).snapshot().blueId(),
                host.snapshot().valueAt("/children/gamma").blueId());

        List<String> expectedEvents = List.of(
                observationBlueId(coordination, ALPHA),
                observationBlueId(coordination, ALPHA),
                observationBlueId(coordination, BETA),
                observationBlueId(coordination, BETA),
                observationBlueId(coordination, GAMMA));
        assertEquals(expectedEvents, eventBlueIds(result.publicEvents()));
        assertEquals(3L, result.publicEvents().stream()
                .map(PublicEvent::blueId)
                .distinct()
                .count());
        result.publicEvents().forEach(event -> {
            assertEquals(MULTIPLICITY_HOST,
                    event.sourceDocument().orElseThrow());
            assertTrue(event.occurrencePath().isEmpty());
        });
        assertEquals(expectedEvents,
                eventBlueIds(host.snapshot().publicEvents()));
        assertEquals(expectedEvents,
                eventBlueIds(host.history().get(1).publicEvents()));

        long revisionGas = host.history().get(1).processingGas();
        for (DocumentHandle child : children) {
            revisionGas += child.history().get(0).processingGas();
        }
        assertEquals(result.stats().gas(), revisionGas);
    }

    private static TerminalFailureEvidence runTerminalFailure(
            TerminalFailure failure) {
        String suffix = failure.name().toLowerCase(Locale.ROOT);
        DocumentId hostId = DocumentId.of(
                "sdk-managed-failure-host-" + suffix);
        DocumentId childId = DocumentId.of(
                "sdk-managed-failure-child-" + suffix);
        String timelineId = "sdk/managed-failure/" + suffix;
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    hostId,
                                    failureHost(hostId, timelineId))
                            .publicRoot()
                            .fromNow());
            ManagedDocumentDraft child = draft(
                    coordination, childId, false);
            ExactBlueValue wrong = coordination.values().yaml(
                    lifecycleDocument(childId, false)
                            .replace("state: draft", "state: altered"));
            String hostBefore = host.snapshot().blueId();
            int historyBefore = host.history().size();

            OperationCall call = coordination.operations()
                    .on(host)
                    .from(timeline)
                    .call(failure.operation)
                    .through("ownerChannel")
                    .request(request -> {
                        request.managed("order", child);
                        if (failure == TerminalFailure.WRONG_EXACT_STATE) {
                            request.exact("wrong", wrong);
                        }
                    })
                    .expectOccurrence("/orders/expected", child);
            EntryResult result = call.execute();

            assertEquals(EntryDisposition.REJECTED,
                    result.disposition(), failure.name());
            assertEquals(failure.diagnosticCode,
                    result.diagnostic().code(), failure.name());
            assertEquals(1, result.closures().size(), failure.name());
            assertEquals(EntryDisposition.REJECTED,
                    result.closures().get(0).disposition(), failure.name());
            assertEquals(failure.diagnosticCode,
                    result.closures().get(0).diagnostic().code(),
                    failure.name());
            assertTrue(result.closures().get(0).changes().isEmpty(),
                    failure.name());
            assertTrue(result.closures().get(0).publicEvents().isEmpty(),
                    failure.name());
            assertFalse(result.closures().get(0)
                    .managedSurfaceEvidence().present(), failure.name());
            assertTrue(result.publicEvents().isEmpty(), failure.name());
            assertEquals(0L, result.stats().committedTransitions(),
                    failure.name());
            assertEquals(2L, result.stats().documentsOpened(),
                    failure.name());
            assertEquals(List.of(hostId),
                    result.stats().documentStepOrder(), failure.name());
            assertCounterUnits(
                    result.stats(),
                    "processor.closureWorkOccurrenceEnqueued",
                    1L,
                    5L);
            assertCounterUnits(
                    result.stats(),
                    "processor.closureWorkOccurrenceDequeued",
                    1L,
                    5L);
            assertEquals(0L, result.stats().counter(
                    "processor.processorMarkerWritten"), failure.name());
            assertEquals(0L, result.stats().counter(
                    "processor.internalEventEnqueued"), failure.name());
            assertEquals(0L, result.stats().counter(
                    "processor.internalEventDequeued"), failure.name());
            assertEquals(0L, result.stats().counter(
                    "processor.rootEventRecorded"), failure.name());
            assertGasIsFullyAccounted(result.stats());

            assertEquals(hostBefore, host.snapshot().blueId(),
                    failure.name());
            assertEquals(0L, host.snapshot().epoch(), failure.name());
            assertEquals(historyBefore, host.history().size(),
                    failure.name());
            assertDocumentAbsent(coordination, childId);
            boolean rollbackPreserved = hostBefore.equals(
                    host.snapshot().blueId())
                    && host.snapshot().epoch() == 0L
                    && host.history().size() == historyBefore;
            boolean retryRecovered = true;

            if (failure == TerminalFailure.ZERO_MATCHES) {
                EntryResult retry = managedCall(
                        coordination,
                        host,
                        timeline,
                        "validCreate",
                        child,
                        "/orders/expected")
                        .execute();
                assertEquals(2L,
                        retry.entry().globalSequence().orElseThrow());
                assertEquals(2L,
                        retry.entry().timelineSequence().orElseThrow());
                assertEquals(EntryDisposition.APPLIED,
                        retry.disposition());
                assertEquals(List.of(hostId, childId, childId),
                        retry.stats().documentStepOrder());
                assertEquals(1L, coordination.documents()
                        .require(childId)
                        .snapshot()
                        .longAt("/initializationCount"));
                retryRecovered = retry.disposition()
                        == EntryDisposition.APPLIED;
            }
            return new TerminalFailureEvidence(
                    failure,
                    result.disposition(),
                    result.diagnostic().code(),
                    rollbackPreserved,
                    retryRecovered);
        }
    }

    private static OperationCall managedCall(
            BlueCoordination coordination,
            DocumentHandle host,
            TimelineHandle timeline,
            String operation,
            ManagedDocumentDraft draft,
            String occurrencePath) {
        return coordination.operations()
                .on(host)
                .from(timeline)
                .call(operation)
                .through("ownerChannel")
                .request(request -> request.managed("order", draft))
                .expectOccurrence(occurrencePath, draft);
    }

    private static ManagedDocumentDraft draft(
            BlueCoordination coordination,
            DocumentId id,
            boolean emitInitializationEvent) {
        return coordination.documents().draft(
                id,
                coordination.values().yaml(lifecycleDocument(
                        id, emitInitializationEvent)));
    }

    private static void addManagedRequestFields(
            RequestBuilder request,
            Variant variant,
            ManagedDocumentDraft alpha,
            ManagedDocumentDraft beta,
            ManagedDocumentDraft gamma) {
        if (variant == Variant.SCRAMBLED) {
            request.managed("gamma", gamma)
                    .managed("alpha", alpha)
                    .managed("beta", beta);
        } else {
            request.managed("beta", beta)
                    .managed("gamma", gamma)
                    .managed("alpha", alpha);
        }
    }

    private static void addOccurrenceExpectations(
            OperationCall call,
            Variant variant,
            ManagedDocumentDraft alpha,
            ManagedDocumentDraft beta,
            ManagedDocumentDraft gamma) {
        if (variant == Variant.SCRAMBLED) {
            call.expectOccurrence("/children/gamma", gamma)
                    .expectOccurrence("/children/betaSecond", beta)
                    .expectOccurrence("/children/alphaFirst", alpha)
                    .expectOccurrence("/children/betaFirst", beta)
                    .expectOccurrence("/children/alphaSecond", alpha);
        } else {
            call.expectOccurrence("/children/alphaSecond", alpha)
                    .expectOccurrence("/children/betaFirst", beta)
                    .expectOccurrence("/children/alphaFirst", alpha)
                    .expectOccurrence("/children/betaSecond", beta)
                    .expectOccurrence("/children/gamma", gamma);
        }
    }

    private static void assertApplied(EntryResult result) {
        assertEquals(EntryDisposition.APPLIED, result.disposition());
        assertFalse(result.diagnostic().present());
        assertEquals(1, result.closures().size());
        assertEquals(EntryDisposition.APPLIED,
                result.closures().get(0).disposition());
        assertFalse(result.closures().get(0).diagnostic().present());
    }

    private static void assertHistory(
            DocumentHandle document,
            List<Long> epochs,
            List<DocumentRevision.Kind> kinds) {
        assertEquals(epochs, document.history().stream()
                .map(DocumentRevision::epoch)
                .toList());
        assertEquals(kinds, document.history().stream()
                .map(DocumentRevision::kind)
                .toList());
    }

    private static void assertCounterUnits(
            ProcessingStats stats,
            String counter,
            long units,
            long unitCost) {
        assertEquals(Math.multiplyExact(units, unitCost),
                stats.counter(counter), counter);
    }

    private static void assertGasIsFullyAccounted(ProcessingStats stats) {
        assertTrue(stats.gas() > 0L);
        assertEquals(stats.gas(), stats.counters().values().stream()
                .reduce(0L, Math::addExact));
    }

    private static void assertDocumentAbsent(
            BlueCoordination coordination,
            DocumentId id) {
        assertThrows(CoordinationException.class,
                () -> coordination.documents().require(id));
    }

    private static Set<DocumentId> changedDocuments(EntryResult result) {
        return result.closures().stream()
                .flatMap(closure -> closure.changes().stream())
                .map(DocumentChange::documentId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static List<String> eventBlueIds(List<PublicEvent> events) {
        return events.stream().map(PublicEvent::blueId).toList();
    }

    private static String observationBlueId(
            BlueCoordination coordination,
            DocumentId child) {
        return coordination.values().yaml(observationEvent(child)).blueId();
    }

    private static RunEvidence evidence(
            EntryResult result,
            List<DocumentHandle> documents) {
        ClosureResult closure = result.closures().get(0);
        ArrayList<ChangeEvidence> changes = new ArrayList<>();
        closure.changes().forEach(change -> changes.add(new ChangeEvidence(
                change.documentId(),
                change.epoch(),
                change.before().map(ExactBlueValue::blueId).orElse(null),
                change.after().blueId(),
                eventBlueIds(change.publicEvents()))));
        LinkedHashMap<DocumentId, SnapshotEvidence> snapshots =
                new LinkedHashMap<>();
        LinkedHashMap<DocumentId, List<RevisionEvidence>> histories =
                new LinkedHashMap<>();
        for (DocumentHandle document : documents) {
            DocumentSnapshot snapshot = document.snapshot();
            snapshots.put(document.id(), new SnapshotEvidence(
                    snapshot.epoch(),
                    snapshot.blueId(),
                    eventBlueIds(snapshot.publicEvents())));
            histories.put(document.id(), document.history().stream()
                    .map(revision -> new RevisionEvidence(
                            revision.epoch(),
                            revision.kind(),
                            revision.before()
                                    .map(ExactBlueValue::blueId)
                                    .orElse(null),
                            revision.after().blueId(),
                            revision.sourceEntry()
                                    .map(EntryHandle::blueId)
                                    .orElse(null),
                            eventBlueIds(revision.publicEvents()),
                            revision.processingGas()))
                    .toList());
        }
        return new RunEvidence(
                result.entry().blueId(),
                result.entry().globalSequence().orElseThrow(),
                result.entry().timelineSequence().orElseThrow(),
                result.disposition(),
                result.diagnostic().code(),
                closure.closureId(),
                result.stats().gas(),
                result.stats().committedTransitions(),
                result.stats().documentsOpened(),
                result.stats().documentStepOrder(),
                result.stats().counters(),
                changes,
                result.publicEvents().stream()
                        .map(event -> new EventEvidence(
                                event.blueId(),
                                event.sourceDocument().orElse(null),
                                event.occurrencePath().orElse(null)))
                        .toList(),
                snapshots,
                histories);
    }

    private static String singleDraftHost(
            DocumentId id,
            String timelineId,
            String validOperationName) {
        return """
                documentId: %s
                orders: {}
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /orders
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  %s:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      order: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /orders/order-456
                              val: {$binding: event/message/request/order}
                          - $return: true
                """.formatted(
                id.value(), timelineId, ACTOR, validOperationName);
    }

    private static String cyclicDraftHost(
            DocumentId id,
            String timelineId) {
        return """
                documentId: %s
                orders: {}
                touched: false
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                    collectionPaths:
                      - /orders
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  createOrder:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      order: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /orders/order-456
                              val: {$binding: event/message/request/order}
                          - $return: true
                  touch:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /touched
                              val: true
                          - $return: true
                """.formatted(id.value(), timelineId, ACTOR);
    }

    private static String cyclicDraftPeer(DocumentId id) {
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                """.formatted(id.value());
    }

    private static String nestedGrowthHost(
            DocumentId id,
            String timelineId) {
        return """
                documentId: %s
                parents: {}
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /parents
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  createParent:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      parent: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /parents/primary
                              val: {$binding: event/message/request/parent}
                          - $return: true
                """.formatted(id.value(), timelineId, ACTOR);
    }

    private static String nestedGrowthParent(
            DocumentId id,
            String timelineId) {
        return """
                documentId: %s
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
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  createChild:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      child: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /children/primary
                              val: {$binding: event/message/request/child}
                          - $return: true
                """.formatted(id.value(), timelineId, ACTOR);
    }

    private static String multiplicityHost(Variant variant) {
        String childObject = switch (variant) {
            case SCRAMBLED -> """
                    gamma: {$binding: event/message/request/gamma}
                    alphaSecond: {$binding: event/message/request/alpha}
                    betaFirst: {$binding: event/message/request/beta}
                    alphaFirst: {$binding: event/message/request/alpha}
                    betaSecond: {$binding: event/message/request/beta}
                    """;
            case REVERSED -> """
                    betaSecond: {$binding: event/message/request/beta}
                    alphaFirst: {$binding: event/message/request/alpha}
                    betaFirst: {$binding: event/message/request/beta}
                    alphaSecond: {$binding: event/message/request/alpha}
                    gamma: {$binding: event/message/request/gamma}
                    """;
        };
        String handlers = observationHandler(
                "AlphaFirst",
                "/children/alphaFirst",
                ALPHA,
                "/observedAlpha")
                + observationHandler(
                        "AlphaSecond",
                        "/children/alphaSecond",
                        ALPHA,
                        "/observedAlpha")
                + observationHandler(
                        "BetaFirst",
                        "/children/betaFirst",
                        BETA,
                        "/observedBeta")
                + observationHandler(
                        "BetaSecond",
                        "/children/betaSecond",
                        BETA,
                        "/observedBeta")
                + observationHandler(
                        "Gamma",
                        "/children/gamma",
                        GAMMA,
                        "/observedGamma");
        return """
                documentId: %s
                children: {}
                observedTotal: 0
                observedAlpha: 0
                observedBeta: 0
                observedGamma: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /children
                %s
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  createChildren:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      alpha: {}
                      beta: {}
                      gamma: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /children
                              val:
                %s
                          - $return: true
                """.formatted(
                MULTIPLICITY_HOST.value(),
                handlers.indent(2).stripTrailing(),
                MULTIPLICITY_TIMELINE,
                ACTOR,
                childObject.indent(16).stripTrailing());
    }

    private static String observationHandler(
            String suffix,
            String sourcePath,
            DocumentId child,
            String lineageCounterPath) {
        return """
                from%s:
                  type: Embedded Node Channel
                  sourcePath: %s
                  event:
                    type: Coordination/Event
                    kind: SDK/Child Initialized
                on%s:
                  type: Coordination/Sequential Workflow
                  channel: from%s
                  event:
                    type: Coordination/Event
                    kind: SDK/Child Initialized
                  steps:
                    - type: Coordination/Compute
                      do:
                        - $appendChange:
                            op: replace
                            path: /observedTotal
                            val: {$add: [{$document: /observedTotal}, 1]}
                        - $appendChange:
                            op: replace
                            path: %s
                            val: {$add: [{$document: %s}, 1]}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: SDK/Child Initialization Observed
                            childDocumentId: %s
                        - $return: true
                """.formatted(
                suffix,
                sourcePath,
                suffix,
                suffix,
                lineageCounterPath,
                lineageCounterPath,
                child.value());
    }

    private static String failureHost(
            DocumentId id,
            String timelineId) {
        return """
                documentId: %s
                orders: {}
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /orders
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  zeroMatches:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {order: {}}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $return: true
                  wrongPath:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {order: {}}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /wrongOrder
                              val: {$binding: event/message/request/order}
                          - $return: true
                  wrongExactState:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {order: {}, wrong: {}}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /orders/expected
                              val: {$binding: event/message/request/wrong}
                          - $return: true
                  singlePatchExtra:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {order: {}}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /orders
                              val:
                                expected: {$binding: event/message/request/order}
                                extra: {$binding: event/message/request/order}
                          - $return: true
                  sequentialExtra:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {order: {}}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /orders/expected
                              val: {$binding: event/message/request/order}
                          - $appendChange:
                              op: add
                              path: /orders/extra
                              val: {$binding: event/message/request/order}
                          - $return: true
                  validCreate:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {order: {}}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /orders/expected
                              val: {$binding: event/message/request/order}
                          - $return: true
                """.formatted(id.value(), timelineId, ACTOR);
    }

    private static String lifecycleDocument(
            DocumentId id,
            boolean emitInitializationEvent) {
        if (emitInitializationEvent) {
            return """
                    documentId: %s
                    state: draft
                    initializationCount: 0
                    contracts:
                      lifecycleChannel:
                        type:
                          blueId: %s
                        order: 0
                        event:
                          type:
                            blueId: %s
                      onProcessingInitiated:
                        type: Coordination/Sequential Workflow
                        channel: lifecycleChannel
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
                                  kind: SDK/Child Initialized
                                  childDocumentId: {$document: /documentId}
                              - $return: true
                    """.formatted(
                    id.value(),
                    LIFECYCLE_CHANNEL_BLUE_ID,
                    LIFECYCLE_EVENT_BLUE_ID);
        }
        return """
                documentId: %s
                state: draft
                initializationCount: 0
                contracts:
                  lifecycleChannel:
                    type:
                      blueId: %s
                    order: 0
                    event:
                      type:
                        blueId: %s
                  onProcessingInitiated:
                    type: Coordination/Sequential Workflow
                    channel: lifecycleChannel
                    order: 0
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /initializationCount
                              val: {$add: [{$document: /initializationCount}, 1]}
                          - $return: true
                """.formatted(
                id.value(),
                LIFECYCLE_CHANNEL_BLUE_ID,
                LIFECYCLE_EVENT_BLUE_ID);
    }

    private static String observationEvent(DocumentId child) {
        return """
                type: Coordination/Event
                kind: SDK/Child Initialization Observed
                childDocumentId: %s
                """.formatted(child.value());
    }

    private enum Submission {
        EXECUTE,
        SUBMIT
    }

    private enum Variant {
        SCRAMBLED,
        REVERSED
    }

    private enum TerminalFailure {
        ZERO_MATCHES(
                "zeroMatches",
                "MANAGED_OCCURRENCE_BINDING_MISSING"),
        WRONG_PATH(
                "wrongPath",
                "MANAGED_OCCURRENCE_BINDING_MISSING"),
        WRONG_EXACT_STATE(
                "wrongExactState",
                "MANAGED_OCCURRENCE_BINDING_MISSING");

        private final String operation;
        private final String diagnosticCode;

        TerminalFailure(String operation, String diagnosticCode) {
            this.operation = operation;
            this.diagnosticCode = diagnosticCode;
        }
    }

    private record TerminalFailureEvidence(
            TerminalFailure failure,
            EntryDisposition disposition,
            String diagnosticCode,
            boolean rollbackPreserved,
            boolean retryRecovered) {
    }

    private record RunEvidence(
            String entryBlueId,
            long globalSequence,
            long timelineSequence,
            EntryDisposition disposition,
            String diagnosticCode,
            String closureId,
            long gas,
            long committedTransitions,
            long documentsOpened,
            List<DocumentId> documentStepOrder,
            Map<String, Long> counters,
            List<ChangeEvidence> changes,
            List<EventEvidence> events,
            Map<DocumentId, SnapshotEvidence> snapshots,
            Map<DocumentId, List<RevisionEvidence>> histories) {
        private RunEvidence {
            documentStepOrder = List.copyOf(documentStepOrder);
            counters = Map.copyOf(counters);
            changes = List.copyOf(changes);
            events = List.copyOf(events);
            snapshots = Map.copyOf(snapshots);
            histories = Map.copyOf(histories);
        }
    }

    private record ChangeEvidence(
            DocumentId documentId,
            long epoch,
            String beforeBlueId,
            String afterBlueId,
            List<String> eventBlueIds) {
        private ChangeEvidence {
            eventBlueIds = List.copyOf(eventBlueIds);
        }
    }

    private record EventEvidence(
            String blueId,
            DocumentId sourceDocument,
            String occurrencePath) {
    }

    private record SnapshotEvidence(
            long epoch,
            String blueId,
            List<String> eventBlueIds) {
        private SnapshotEvidence {
            eventBlueIds = List.copyOf(eventBlueIds);
        }
    }

    private record RevisionEvidence(
            long epoch,
            DocumentRevision.Kind kind,
            String beforeBlueId,
            String afterBlueId,
            String sourceEntryBlueId,
            List<String> eventBlueIds,
            long processingGas) {
        private RevisionEvidence {
            eventBlueIds = List.copyOf(eventBlueIds);
        }
    }
}
