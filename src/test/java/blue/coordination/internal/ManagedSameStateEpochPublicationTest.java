package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedDocumentReadiness;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedEventOccurrence;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.SessionStatus;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.ClosureHandle;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DrainBudget;
import blue.coordination.sdk.DrainResult;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.coordination.sdk.ExactBlueValue;
import blue.coordination.sdk.ManagedClosure;
import blue.coordination.sdk.ManagedDocument;
import blue.coordination.sdk.ManagedEpochSelector;
import blue.coordination.sdk.TimelineHandle;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRootEventOccurrence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Same-state source and consumer epoch regressions at the Contracts boundary. */
public final class ManagedSameStateEpochPublicationTest {
    private static final String ACTOR = "alice";
    private static final DocumentId SOURCE = DocumentId.of(
            "same-state-managed-source");
    private static final Method IDENTIFY_TRANSITION = identifyTransition();
    private static final Object CLOSURE_IDENTITIES = closureIdentities();
    private static final Method EVENT_OCCURRENCE_IDENTITY =
            eventOccurrenceIdentityMethod();
    private static final Method SAME_STATE_ADVANCE =
            sameStateAdvanceMethod();

    @Test
    void validatorRecognizesReceiptBackedEventOnlyAndEmbeddedAdvances() {
        // given
        DocumentRevision.Kind eventOnly = DocumentRevision.Kind.EVENT_ONLY;
        DocumentRevision.Kind embedded =
                DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION;

        // when
        boolean eventOnlyRecognized = recognized(eventOnly);
        boolean embeddedRecognized = recognized(embedded);

        // then
        assertTrue(eventOnlyRecognized);
        assertTrue(embeddedRecognized);
    }

    @Test
    void noReactionPublishesReceiptBackedConsumerRevisionWithCursor() {
        DocumentId consumerId = DocumentId.of(
                "same-state-no-reaction-consumer");
        try (Prepared scenario = prepared(consumerId, false)) {
            // given
            InMemoryDocumentStore documents = scenario.engine().documents();
            ManagedEpochApplicationWork work = documents.nextCatchUpWork()
                    .orElseThrow();
            DocumentSession consumerBefore = documents.require(consumerId);
            long consumerEpochBefore = consumerBefore.epoch();
            String consumerBlueIdBefore = consumerBefore.currentRevision()
                    .after().blueId();
            int consumerHistoryBefore = scenario.engine().history(
                    consumerId).size();
            ManagedEpochReceipt consumerHeadReceipt = documents
                    .managedEpochReceipt(consumerId, consumerEpochBefore)
                    .orElseThrow();
            List<String> sourceHistoryBefore = sourceHistory(scenario.source());

            // when
            ContractsClosureAdapter.ManagedApplicationOutcome outcome =
                    scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(work);

            // then
            assertTrue(outcome.published());
            assertFalse(outcome.replayed());
            ManagedEpochApplicationReceipt application = outcome.receipt()
                    .orElseThrow();
            assertEquals(consumerEpochBefore + 1L,
                    application.consumerRevisionEpoch());
            assertEquals(consumerBlueIdBefore,
                    application.consumerCommittedBlueId());
            assertEquals(2L, application.resultingSourceCursor());
            DocumentRevision revision = documents.require(consumerId)
                    .currentRevision();
            assertEquals(consumerEpochBefore + 1L, revision.epoch());
            assertEquals(
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    revision.kind());
            assertEquals(consumerBlueIdBefore,
                    revision.before().orElseThrow().blueId());
            assertEquals(consumerBlueIdBefore, revision.after().blueId());
            assertTrue(revision.emittedEvents().isEmpty());
            ManagedEpochReceipt revisionReceipt = revision
                    .managedEpochReceipt().orElseThrow();
            assertFalse(consumerHeadReceipt.receiptIdentity().equals(
                    revisionReceipt.receiptIdentity()));
            assertEquals(work.workIdentity(),
                    revisionReceipt.originalCauseIdentity());
            assertEquals(application.consumerRevisionReceiptIdentity(),
                    revisionReceipt.receiptIdentity());
            assertTrue(documents.managedTransitionReceipt(
                    consumerId, revision.epoch()).isEmpty(),
                    "an unchanged eventless Contracts result has no false "
                            + "document-transition receipt");
            assertEquals(consumerHistoryBefore + 1,
                    scenario.engine().history(consumerId).size());
            assertEquals(sourceHistoryBefore, sourceHistory(scenario.source()),
                    "catch-up must consume the retained receipt, not process "
                            + "the source again");
            assertCompletedAndReady(scenario, consumerId);

            scenario.engine().restartFromStores();
            ContractsClosureAdapter.ManagedApplicationOutcome replay =
                    scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(work);
            assertTrue(replay.published());
            assertTrue(replay.replayed());
            assertEquals(revision.epoch(),
                    documents.require(consumerId).epoch());
            assertEquals(consumerHistoryBefore + 1,
                    scenario.engine().history(consumerId).size());
            assertEquals(application.applicationReceiptIdentity(),
                    replay.receipt().orElseThrow()
                            .applicationReceiptIdentity());
        }
    }

    @Test
    void noReactionPublishesReceiptBackedSameStateCyclicConsumer() {
        DocumentId consumerId = DocumentId.of(
                "same-state-cyclic-consumer");
        DocumentId peerId = DocumentId.of(
                "same-state-cyclic-peer");
        try (Prepared scenario = preparedCyclic(consumerId, peerId)) {
            // given
            InMemoryDocumentStore documents = scenario.engine().documents();
            ManagedEpochApplicationWork work = documents.nextCatchUpWork()
                    .orElseThrow();
            DocumentSession consumerBefore = documents.require(consumerId);
            long consumerEpochBefore = consumerBefore.epoch();
            String consumerBlueIdBefore = consumerBefore
                    .currentRepresentation().blueId();
            assertTrue(BlueIds.hasCyclicMemberSeparator(
                    consumerBlueIdBefore));

            // when
            ContractsClosureAdapter.ManagedApplicationOutcome outcome =
                    scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(work);

            // then
            assertTrue(outcome.published());
            assertFalse(outcome.replayed());
            ComponentSnapshot resultingComponent = outcome.attempt()
                    .processResult().resultingComponents().stream()
                    .filter(component -> component
                            .orderedMemberDocumentIds().stream()
                            .anyMatch(member -> member.value().equals(
                                    consumerId.value())))
                    .findFirst()
                    .orElseThrow();
            int memberIndex = resultingComponent
                    .orderedMemberDocumentIds().indexOf(
                            ContractsClosureAdapter.closureId(consumerId));
            assertEquals(consumerBlueIdBefore,
                    resultingComponent.orderedMemberBlueIds().get(
                            memberIndex));
            assertTrue(resultingComponent.completeCyclicProof() != null);

            DocumentRevision revision = documents.require(consumerId)
                    .currentRevision();
            assertEquals(consumerEpochBefore + 1L, revision.epoch());
            assertEquals(
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    revision.kind());
            assertEquals(consumerBlueIdBefore,
                    revision.before().orElseThrow().blueId());
            assertEquals(consumerBlueIdBefore, revision.after().blueId());
            assertTrue(revision.after().isCyclicMember());
            assertTrue(revision.emittedEvents().isEmpty());
            assertTrue(documents.managedTransitionReceipt(
                    consumerId, revision.epoch()).isEmpty());
            ManagedEpochReceipt receipt = revision.managedEpochReceipt()
                    .orElseThrow();
            assertEquals(consumerBlueIdBefore, receipt.afterBlueId());
            assertEquals(receipt.receiptIdentity(), outcome.receipt()
                    .orElseThrow().consumerRevisionReceiptIdentity());
            assertCompletedAndReady(scenario, consumerId);

            scenario.engine().restartFromStores();
            ContractsClosureAdapter.ManagedApplicationOutcome replay =
                    scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(work);
            assertTrue(replay.published());
            assertTrue(replay.replayed());
            assertEquals(revision.epoch(),
                    documents.require(consumerId).epoch());
            assertEquals(consumerBlueIdBefore,
                    documents.require(consumerId)
                            .currentRepresentation().blueId());
        }
    }

    @Test
    void eventlessApplicationEpochRemainsReplayableAsSourceEvidence() {
        DocumentId consumerId = DocumentId.of(
                "same-state-transitive-source");
        DocumentId parentId = DocumentId.of(
                "same-state-transitive-parent");
        try (Prepared scenario = prepared(consumerId, false)) {
            // given
            InMemoryDocumentStore documents = scenario.engine().documents();
            ManagedEpochApplicationWork first = documents.nextCatchUpWork()
                    .orElseThrow();
            long retainedConsumerEpoch = documents.require(consumerId).epoch();
            String retainedConsumerBlueId = documents.require(consumerId)
                    .currentRevision().after().blueId();

            ContractsClosureAdapter.ManagedApplicationOutcome sourceEpoch =
                    scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(first);
            assertTrue(sourceEpoch.published());
            long eventlessEpoch = retainedConsumerEpoch + 1L;
            ExactBlueValue retainedConsumer = scenario.consumer().exact();
            DocumentRevision eventlessRevision = documents.require(consumerId)
                    .currentRevision();
            assertEquals(eventlessEpoch, eventlessRevision.epoch());
            assertEquals(retainedConsumerBlueId, retainedConsumer.blueId());
            assertEquals(retainedConsumer.blueId(),
                    eventlessRevision.before().orElseThrow().blueId());
            assertEquals(retainedConsumer.blueId(),
                    eventlessRevision.after().blueId());
            assertTrue(documents.managedTransitionReceipt(
                    consumerId, eventlessEpoch).isEmpty());

            String timelineId = "same-state/" + parentId.value();
            TimelineHandle timeline = scenario.coordination().timelines()
                    .register(timelineId, ACTOR);
            DocumentHandle parent = scenario.coordination().documents().admit(
                    ManagedDocument.yaml(
                                    parentId,
                                    consumerYaml(
                                            parentId, timelineId, false))
                            .publicRoot()
                            .fromNow());
            EntryHandle attachment = scenario.coordination().operations()
                    .on(parent)
                    .from(timeline)
                    .call("attach")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "child", retainedConsumer))
                    .selectManagedEpoch(ManagedEpochSelector.exact(
                            consumerId,
                            retainedConsumerEpoch,
                            retainedConsumer.blueId(),
                            "/child"))
                    .submit();
            DrainResult attached = scenario.coordination().processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());

            ManagedEpochApplicationWork transitive = documents
                    .nextCatchUpWork().orElseThrow();
            assertEquals(consumerId, transitive.sourceDocumentId());
            assertEquals(eventlessEpoch, transitive.sourceEpoch());
            assertEquals(parentId, transitive.consumerDocumentId());

            // when
            ContractsClosureAdapter.ManagedApplicationOutcome applied =
                    scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(transitive);

            // then
            assertTrue(applied.published());
            DocumentRevision parentRevision = documents.require(parentId)
                    .currentRevision();
            assertEquals(
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    parentRevision.kind());
            assertEquals(parentRevision.before().orElseThrow().blueId(),
                    parentRevision.after().blueId());
            assertTrue(parentRevision.emittedEvents().isEmpty());
            assertEquals(parentRevision.managedEpochReceipt().orElseThrow()
                            .receiptIdentity(),
                    applied.receipt().orElseThrow()
                            .consumerRevisionReceiptIdentity());
        }
    }

    @Test
    void eventlessCyclicApplicationEpochRemainsReplayableAsSourceEvidence() {
        DocumentId consumerId = DocumentId.of(
                "same-state-cyclic-transitive-source");
        DocumentId peerId = DocumentId.of(
                "same-state-cyclic-transitive-peer");
        DocumentId parentId = DocumentId.of(
                "same-state-cyclic-transitive-parent");
        try (Prepared scenario = preparedCyclic(consumerId, peerId)) {
            // given
            InMemoryDocumentStore documents = scenario.engine().documents();
            ManagedEpochApplicationWork first = documents.nextCatchUpWork()
                    .orElseThrow();
            long retainedConsumerEpoch = documents.require(consumerId).epoch();
            String retainedConsumerBlueId = documents.require(consumerId)
                    .currentRepresentation().blueId();
            assertTrue(BlueIds.hasCyclicMemberSeparator(
                    retainedConsumerBlueId));

            ContractsClosureAdapter.ManagedApplicationOutcome sourceEpoch =
                    scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(first);
            assertTrue(sourceEpoch.published());
            long eventlessEpoch = retainedConsumerEpoch + 1L;
            ExactBlueValue retainedConsumer = scenario.consumer().exact();
            DocumentRevision eventlessRevision = documents.require(consumerId)
                    .currentRevision();
            assertEquals(eventlessEpoch, eventlessRevision.epoch());
            assertEquals(retainedConsumerBlueId, retainedConsumer.blueId());
            assertTrue(retainedConsumer.cyclicMember());
            assertEquals(retainedConsumer.blueId(),
                    eventlessRevision.before().orElseThrow().blueId());
            assertEquals(retainedConsumer.blueId(),
                    eventlessRevision.after().blueId());
            assertTrue(documents.managedTransitionReceipt(
                    consumerId, eventlessEpoch).isEmpty());
            assertEquals(NodeProviderOutcome.FOUND,
                    scenario.engine().objects().cyclicSetProofFor(
                            retainedConsumer.blueId()).outcome());
            List<String> retainedSourceHistory = sourceHistory(
                    scenario.consumer());

            String timelineId = "same-state/" + parentId.value();
            TimelineHandle timeline = scenario.coordination().timelines()
                    .register(timelineId, ACTOR);
            DocumentHandle parent = scenario.coordination().documents().admit(
                    ManagedDocument.yaml(
                                    parentId,
                                    consumerYaml(
                                            parentId, timelineId, false))
                            .publicRoot()
                            .fromNow());
            EntryHandle attachment = scenario.coordination().operations()
                    .on(parent)
                    .from(timeline)
                    .call("attach")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "child", retainedConsumer))
                    .selectManagedEpoch(ManagedEpochSelector.exact(
                            consumerId,
                            retainedConsumerEpoch,
                            retainedConsumer.blueId(),
                            "/child"))
                    .submit();
            DrainResult attached = scenario.coordination().processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());
            assertTrue(attached.managedEpochApplications().isEmpty());

            ManagedEpochApplicationWork transitive = documents
                    .nextCatchUpWork().orElseThrow();
            assertEquals(consumerId, transitive.sourceDocumentId());
            assertEquals(eventlessEpoch, transitive.sourceEpoch());
            assertEquals(parentId, transitive.consumerDocumentId());
            InMemoryDocumentStore.ManagedEpochEvidence evidence = documents
                    .managedEpochEvidence(consumerId, eventlessEpoch);
            assertNull(evidence.transitionReceipt());
            assertEquals(retainedConsumer.blueId(),
                    evidence.receipt().afterBlueId());

            scenario.engine().restartFromStores();
            assertEquals(NodeProviderOutcome.FOUND,
                    scenario.engine().objects().cyclicSetProofFor(
                            retainedConsumer.blueId()).outcome());

            // when
            ContractsClosureAdapter.ManagedApplicationOutcome applied =
                    scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(transitive);

            // then
            assertTrue(applied.published());
            assertFalse(applied.replayed());
            DocumentRevision parentRevision = documents.require(parentId)
                    .currentRevision();
            assertEquals(
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    parentRevision.kind());
            assertEquals(parentRevision.before().orElseThrow().blueId(),
                    parentRevision.after().blueId());
            assertTrue(parentRevision.emittedEvents().isEmpty());
            assertEquals(parentRevision.managedEpochReceipt().orElseThrow()
                            .receiptIdentity(),
                    applied.receipt().orElseThrow()
                            .consumerRevisionReceiptIdentity());
            assertEquals(retainedSourceHistory,
                    sourceHistory(scenario.consumer()),
                    "transitive catch-up must not process the cyclic source");

            long parentEpoch = parentRevision.epoch();
            scenario.engine().restartFromStores();
            ContractsClosureAdapter.ManagedApplicationOutcome replay =
                    scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(transitive);
            assertTrue(replay.published());
            assertTrue(replay.replayed());
            assertEquals(parentEpoch, documents.require(parentId).epoch());
            assertEquals(retainedSourceHistory,
                    sourceHistory(scenario.consumer()));
        }
    }

    @Test
    void eventOnlyReactionPublishesSameStateEmbeddedRevisionAndReceipt() {
        DocumentId consumerId = DocumentId.of(
                "same-state-event-reaction-consumer");
        try (Prepared scenario = prepared(consumerId, true)) {
            // given
            InMemoryDocumentStore documents = scenario.engine().documents();
            ManagedEpochApplicationWork work = documents.nextCatchUpWork()
                    .orElseThrow();
            DocumentSession consumerBefore = documents.require(consumerId);
            long consumerEpochBefore = consumerBefore.epoch();
            String consumerBlueIdBefore = consumerBefore.currentRevision()
                    .after().blueId();

            // when
            ContractsClosureAdapter.ManagedApplicationOutcome outcome =
                    scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(work);

            // then
            assertTrue(outcome.published());
            DocumentRevision revision = documents.require(consumerId)
                    .currentRevision();
            assertEquals(consumerEpochBefore + 1L, revision.epoch());
            assertEquals(
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    revision.kind());
            assertEquals(consumerBlueIdBefore,
                    revision.before().orElseThrow().blueId());
            assertEquals(consumerBlueIdBefore, revision.after().blueId());
            assertEquals(1, revision.emittedEvents().size());
            ManagedEpochReceipt revisionReceipt = revision
                    .managedEpochReceipt().orElseThrow();
            assertEquals(DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    revisionReceipt.kind());
            assertEquals(consumerBlueIdBefore,
                    revisionReceipt.beforeBlueId().orElseThrow());
            assertEquals(consumerBlueIdBefore,
                    revisionReceipt.afterBlueId());
            assertEquals(1, revisionReceipt.emittedEvents().size());
            ManagedEpochApplicationReceipt application = outcome.receipt()
                    .orElseThrow();
            assertEquals(revision.epoch(),
                    application.consumerRevisionEpoch());
            assertEquals(revisionReceipt.receiptIdentity(),
                    application.consumerRevisionReceiptIdentity());
            assertEquals(2L, application.resultingSourceCursor());
            assertCompletedAndReady(scenario, consumerId);
        }
    }

    private static boolean recognized(DocumentRevision.Kind kind) {
        DocumentId documentId = DocumentId.of(
                "same-state-validator-" + kind.name().toLowerCase());
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession session = engine.start(documentId, """
                    documentId: %s
                    state: unchanged
                    """.formatted(documentId.value()));
            InMemoryDocumentStore store = engine.documents();
            InMemoryDocumentStore.PublicationSnapshot snapshot =
                    store.publicationSnapshot();
            SameStateEpoch epoch = sameStateEpoch(engine, session, kind);
            MultiDocumentPublicationTransaction transaction = store
                    .beginAtomicPublication(
                            "validator-" + kind.name().toLowerCase(),
                            snapshot.occurrenceInventoryGeneration(),
                            snapshot.componentIndexGeneration())
                    .expectHead(
                            documentId,
                            session.epoch(),
                            session.currentRevision().after().blueId())
                    .stageDocument(
                            epoch.revision(),
                            session.layout(),
                            epoch.sourceOrder(),
                            session.activeSubscriptions(),
                            "validator|" + kind.name())
                    .stageManagedEpochReceipt(
                            epoch.receipt(), epoch.transition());

            return invokeSameStateAdvance(
                    transaction,
                    documentId,
                    snapshot.requireHead(documentId));
        }
    }

    private static Prepared prepared(
            DocumentId consumerId,
            boolean reactWithEvent) {
        BlueCoordination coordination = LegacyContracts10TestProfile.sdkBuilder().build();
        try {
            String consumerTimelineId = "same-state/"
                    + consumerId.value();
            TimelineHandle consumerTimeline = coordination.timelines()
                    .register(consumerTimelineId, ACTOR);
            DocumentHandle consumer = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    consumerId,
                                    consumerYaml(
                                            consumerId,
                                            consumerTimelineId,
                                            reactWithEvent))
                            .publicRoot()
                            .fromNow());
            DocumentHandle source = coordination.documents().admit(
                    ManagedDocument.yaml(SOURCE, sourceYaml())
                            .publicRoot()
                            .fromNow());
            ExactBlueValue epochZero = source.history().get(0).after();
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) coordination.advanced()
                            .rawEngine();
            appendSyntheticEventOnlyEpoch(engine, SOURCE);
            assertEquals(List.of(0L, 1L), engine.history(SOURCE).stream()
                    .map(DocumentRevision::epoch)
                    .toList());
            assertEquals(epochZero.blueId(),
                    engine.history(SOURCE).get(1).after().blueId());
            assertEquals(DocumentRevision.Kind.EVENT_ONLY,
                    engine.history(SOURCE).get(1).kind());

            EntryHandle attachment = coordination.operations()
                    .on(consumer)
                    .from(consumerTimeline)
                    .call("attach")
                    .through("ownerChannel")
                    .request(request -> request.exact("child", epochZero))
                    .selectManagedEpoch(ManagedEpochSelector.exact(
                            SOURCE, 0L, epochZero.blueId(), "/child"))
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());
            assertTrue(attached.managedEpochApplications().isEmpty());
            ManagedOccurrenceCatchUpPlan plan = engine
                    .auditManagedCatchUpPlans(consumerId).get(0);
            assertEquals(0L, plan.admittedSourceEpoch());
            assertEquals(1L, plan.nextSourceEpoch());
            assertEquals(1L, plan.requiredThroughSourceEpoch());
            return new Prepared(
                    coordination, engine, consumer, source, plan);
        } catch (RuntimeException | Error failure) {
            coordination.close();
            throw failure;
        }
    }

    private static Prepared preparedCyclic(
            DocumentId consumerId,
            DocumentId peerId) {
        BlueCoordination coordination = LegacyContracts10TestProfile.sdkBuilder().build();
        try {
            String consumerTimelineId = "same-state/"
                    + consumerId.value();
            TimelineHandle consumerTimeline = coordination.timelines()
                    .register(consumerTimelineId, ACTOR);
            ClosureHandle closure = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document(
                                    "consumer",
                                    consumerId,
                                    cyclicConsumerYaml(
                                            consumerId,
                                            consumerTimelineId))
                            .document(
                                    "peer",
                                    peerId,
                                    cyclicPeerYaml(peerId))
                            .bindOccurrence("consumer", "/peer", "peer")
                            .bindOccurrence("peer", "/peer", "consumer")
                            .publicRoot("consumer")
                            .fromNow()
                            .build());
            DocumentHandle consumer = closure.document("consumer");
            assertTrue(consumer.exact().cyclicMember());
            assertTrue(closure.document("peer").exact().cyclicMember());
            DocumentHandle source = coordination.documents().admit(
                    ManagedDocument.yaml(SOURCE, sourceYaml())
                            .publicRoot()
                            .fromNow());
            ExactBlueValue epochZero = source.history().get(0).after();
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) coordination.advanced()
                            .rawEngine();
            appendSyntheticEventOnlyEpoch(engine, SOURCE);

            EntryHandle attachment = coordination.operations()
                    .on(consumer)
                    .from(consumerTimeline)
                    .call("attach")
                    .through("ownerChannel")
                    .request(request -> request.exact("child", epochZero))
                    .selectManagedEpoch(ManagedEpochSelector.exact(
                            SOURCE, 0L, epochZero.blueId(), "/child"))
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());
            assertTrue(attached.managedEpochApplications().isEmpty());
            assertTrue(consumer.exact().cyclicMember());
            ManagedOccurrenceCatchUpPlan plan = engine
                    .auditManagedCatchUpPlans(consumerId).get(0);
            assertEquals(0L, plan.admittedSourceEpoch());
            assertEquals(1L, plan.nextSourceEpoch());
            assertEquals(1L, plan.requiredThroughSourceEpoch());
            return new Prepared(
                    coordination, engine, consumer, source, plan);
        } catch (RuntimeException | Error failure) {
            coordination.close();
            throw failure;
        }
    }

    public static void appendSyntheticEventOnlyEpoch(
            DefaultCoordinationEngine engine,
            DocumentId documentId) {
        InMemoryDocumentStore store = engine.documents();
        DocumentSession session = store.require(documentId);
        InMemoryDocumentStore.PublicationSnapshot snapshot =
                store.publicationSnapshot();
        SameStateEpoch epoch = sameStateEpoch(
                engine, session, DocumentRevision.Kind.EVENT_ONLY);
        ComponentSnapshot component = snapshot.componentStates().stream()
                .filter(candidate -> candidate.orderedMemberDocumentIds()
                        .stream().anyMatch(member -> member.value().equals(
                                documentId.value())))
                .findFirst()
                .orElseThrow();
        store.beginAtomicPublication(
                        "synthetic-event-only-" + documentId.value(),
                        snapshot.occurrenceInventoryGeneration(),
                        snapshot.componentIndexGeneration())
                .expectHead(
                        documentId,
                        session.epoch(),
                        session.currentRevision().after().blueId())
                .expectComponentState(component)
                .stageDocument(
                        epoch.revision(),
                        session.layout(),
                        epoch.sourceOrder(),
                        session.activeSubscriptions(),
                        "synthetic-event-only|" + documentId.value())
                .stageComponentStates(List.of(component))
                .stageManagedEpochReceipt(epoch.receipt(), epoch.transition())
                .commit();
        DocumentSession published = store.require(documentId);
        published.markGraphPublished();
        published.markReady(epoch.sourceOrder());
    }

    private static SameStateEpoch sameStateEpoch(
            DefaultCoordinationEngine engine,
            DocumentSession session,
            DocumentRevision.Kind kind) {
        ExactValue current = session.currentRevision().after();
        ExactValue event = engine.exactValue("""
                type: Coordination/Event
                kind: CatchUp/Source Event
                """);
        String invocationIdentity = hash('e');
        String causeIdentity = hash('f');
        String occurrenceIdentity = eventOccurrenceIdentity(
                invocationIdentity, 0L, event.blueId());
        blue.language.processor.closure.DocumentId contractsDocumentId =
                new blue.language.processor.closure.DocumentId(
                        session.documentId().value());
        ManagedRootEventOccurrence contractsEvent =
                new ManagedRootEventOccurrence(
                        0L,
                        0L,
                        contractsDocumentId,
                        occurrenceIdentity,
                        IntegrationEventEvidence.verify(
                                event.copyNode(), event.blueId()),
                        false);
        ManagedDocumentTransitionReceipt transition = transition(
                invocationIdentity,
                contractsDocumentId,
                causeIdentity,
                current.blueId(),
                contractsEvent);
        ManagedEventOccurrence managedEvent = ManagedEventOccurrence.identified(
                0L,
                0L,
                session.documentId(),
                occurrenceIdentity,
                event,
                false);
        ExternalOrderKey sourceOrder = session.currentRevision()
                .sourceOrderKey().orElseThrow();
        ManagedEpochReceipt receipt = ManagedEpochReceipt.identified(
                session.documentId(),
                session.epoch() + 1L,
                kind,
                current.blueId(),
                current,
                causeIdentity,
                null,
                sourceOrder,
                transition.transitionReceiptIdentity(),
                hash('c'),
                List.of(managedEvent),
                1L);
        DocumentRevision revision = new DocumentRevision(
                session.documentId(),
                session.epoch() + 1L,
                session.nextApplicationOrder(),
                kind,
                current,
                current,
                null,
                sourceOrder,
                session.currentRevision().causalEntryBlueId().orElseThrow(),
                null,
                List.of(event.copyNode()),
                1L,
                receipt);
        return new SameStateEpoch(
                revision, receipt, transition, sourceOrder);
    }

    private static void assertCompletedAndReady(
            Prepared scenario,
            DocumentId consumerId) {
        ManagedOccurrenceCatchUpPlan completed = scenario.engine()
                .auditManagedCatchUpPlans(consumerId).get(0);
        assertEquals(scenario.plan().planIdentity(),
                completed.planIdentity());
        assertEquals(2L, completed.nextSourceEpoch());
        assertTrue(completed.status().terminal());
        ManagedOccurrenceBinding occurrence = scenario.engine().documents()
                .occurrenceInventory().find(consumerId, "/child")
                .orElseThrow();
        assertTrue(occurrence.active());
        assertNull(occurrence.pendingHistoricalEpoch());
        ManagedDocumentReadiness readiness = scenario.engine()
                .auditManagedDocumentReadiness(consumerId).orElseThrow();
        assertEquals(SessionStatus.READY, readiness.status());
        assertTrue(readiness.ready());
        assertEquals(readiness.committedEpoch(),
                readiness.readyEpoch().orElseThrow());
    }

    private static List<String> sourceHistory(DocumentHandle source) {
        return source.history().stream()
                .map(revision -> revision.epoch() + "|" + revision.kind()
                        + "|" + revision.after().blueId())
                .toList();
    }

    private static String sourceYaml() {
        return """
                documentId: %s
                state: unchanged
                """.formatted(SOURCE.value());
    }

    private static String consumerYaml(
            DocumentId documentId,
            String timelineId,
            boolean reactWithEvent) {
        String reaction = reactWithEvent ? """
                  fromSourceEvent:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Event
                  onSourceEvent:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceEvent
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Event
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: CatchUp/Consumer Observed
                          - $return: true
                """ : "";
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /child
                %s  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
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
                              path: /child
                              val: {$binding: event/message/request/child}
                          - $return: true
                """.formatted(
                        documentId.value(), reaction, timelineId, ACTOR);
    }

    private static String cyclicConsumerYaml(
            DocumentId documentId,
            String timelineId) {
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /child
                      - /peer
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
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
                              path: /child
                              val: {$binding: event/message/request/child}
                          - $return: true
                """.formatted(documentId.value(), timelineId, ACTOR);
    }

    private static String cyclicPeerYaml(DocumentId documentId) {
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                """.formatted(documentId.value());
    }

    private static ManagedDocumentTransitionReceipt transition(
            String invocationIdentity,
            blue.language.processor.closure.DocumentId documentId,
            String causeIdentity,
            String blueId,
            ManagedRootEventOccurrence event) {
        try {
            return (ManagedDocumentTransitionReceipt)
                    IDENTIFY_TRANSITION.invoke(
                            null,
                            invocationIdentity,
                            0L,
                            documentId,
                            causeIdentity,
                            blueId,
                            blueId,
                            List.of(event),
                            1L);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }

    private static String eventOccurrenceIdentity(
            String invocationIdentity,
            long occurrenceOrdinal,
            String eventBlueId) {
        try {
            return (String) EVENT_OCCURRENCE_IDENTITY.invoke(
                    CLOSURE_IDENTITIES,
                    invocationIdentity,
                    occurrenceOrdinal,
                    eventBlueId);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }

    private static boolean invokeSameStateAdvance(
            MultiDocumentPublicationTransaction transaction,
            DocumentId documentId,
            InMemoryDocumentStore.DocumentHead before) {
        try {
            return (Boolean) SAME_STATE_ADVANCE.invoke(
                    transaction, documentId, before);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }

    private static Method identifyTransition() {
        try {
            Method method = ManagedDocumentTransitionReceipt.class
                    .getDeclaredMethod(
                            "identified",
                            String.class,
                            long.class,
                            blue.language.processor.closure.DocumentId.class,
                            String.class,
                            String.class,
                            String.class,
                            List.class,
                            long.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Object closureIdentities() {
        try {
            Class<?> type = Class.forName(
                    "blue.language.processor.closure.ClosureIdentityService");
            Field field = type.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Method eventOccurrenceIdentityMethod() {
        try {
            Method method = CLOSURE_IDENTITIES.getClass().getDeclaredMethod(
                    "eventOccurrenceIdentity",
                    String.class,
                    long.class,
                    String.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Method sameStateAdvanceMethod() {
        try {
            Method method = MultiDocumentPublicationTransaction.class
                    .getDeclaredMethod(
                            "stagesReceiptBackedSameStateAdvance",
                            DocumentId.class,
                            InMemoryDocumentStore.DocumentHead.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String hash(char digit) {
        char[] digits = new char[64];
        Arrays.fill(digits, digit);
        return "sha256:" + new String(digits);
    }

    private record SameStateEpoch(
            DocumentRevision revision,
            ManagedEpochReceipt receipt,
            ManagedDocumentTransitionReceipt transition,
            ExternalOrderKey sourceOrder) {
    }

    private record Prepared(
            BlueCoordination coordination,
            DefaultCoordinationEngine engine,
            DocumentHandle consumer,
            DocumentHandle source,
            ManagedOccurrenceCatchUpPlan plan) implements AutoCloseable {
        @Override
        public void close() {
            coordination.close();
        }
    }
}
