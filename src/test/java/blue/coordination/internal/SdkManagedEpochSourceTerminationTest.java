package blue.coordination.internal;

import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DrainBudget;
import blue.coordination.sdk.DrainResult;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.coordination.sdk.EntryResult;
import blue.coordination.sdk.ExactBlueValue;
import blue.coordination.sdk.ManagedDocument;
import blue.coordination.sdk.ManagedEpochApplicationReceipt;
import blue.coordination.sdk.ManagedEpochReceipt;
import blue.coordination.sdk.ManagedEventOccurrence;
import blue.coordination.sdk.TimelineHandle;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedRootEventOccurrence;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK acceptance for catch-up through an exact terminating source epoch. */
final class SdkManagedEpochSourceTerminationTest {
    private static final String ACTOR = "alice";
    private static final String SOURCE_PROCESS_CALLS =
            "managedEpoch.catchUp.sourceProcessCalls";
    private static final String TERMINATED_MARKER_BLUE_ID =
            "4c1aabU6a3idKpWPzTRS4upLjCb6eZh3F1PDXkNh7i6v";
    private static final DocumentId CONSUMER = DocumentId.of(
            "sdk-terminating-source-consumer");
    private static final DocumentId SOURCE = DocumentId.of(
            "sdk-terminating-source");
    private static final String CONSUMER_TIMELINE =
            "sdk/terminating-source/consumer";
    private static final Method IDENTIFY_TRANSITION = identifyTransition();
    private static final Object CLOSURE_IDENTITIES = closureIdentities();
    private static final Method EVENT_OCCURRENCE_IDENTITY =
            eventOccurrenceIdentityMethod();
    private static final Method COMPONENT_STATE_IDENTITY =
            componentStateIdentityMethod();

    @Test
    void productionTerminationPublishesOneTerminalEpochAndEvent() {
        // given
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    CONSUMER_TIMELINE, ACTOR);
            DocumentHandle source = coordination.documents().admit(
                    ManagedDocument.yaml(SOURCE, terminatingSourceYaml())
                            .publicRoot()
                            .fromNow());

            // when
            EntryResult finished = coordination.operations()
                    .on(source)
                    .from(timeline)
                    .call("finish")
                    .through("sourceChannel")
                    .request(request -> { })
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED, finished.disposition(),
                    String.valueOf(finished.diagnostic()));
            assertEquals(SessionStatus.TERMINATED,
                    coordination.advanced().auditDocument(SOURCE).status());
            assertEquals(List.of(0L, 1L), coordination.advanced()
                    .auditManagedEpochs(SOURCE).stream()
                    .map(ManagedEpochReceipt::epoch)
                    .toList());
            assertEquals(2, source.history().size());
            ManagedEpochReceipt terminal = coordination.advanced()
                    .auditManagedEpoch(SOURCE, 1L).orElseThrow();
            assertEquals(1, terminal.emittedEvents().size());
            assertTrue(terminal.sourceEntry().isPresent());
            com.fasterxml.jackson.databind.JsonNode exact =
                    blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER
                            .readTree(source.exact().json());
            assertTrue(exact.at("/finished/value").asBoolean());
            assertEquals(TERMINATED_MARKER_BLUE_ID,
                    exact.at("/contracts/terminated/type/blueId").asText());
        }
    }

    @Test
    void terminatingEpochCompletesItsConsumerPlanWithoutSourceReprocessing() {
        // given
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            CoordinationTestControl control = CoordinationTestControl.attach(
                    coordination.advanced().rawEngine());
            TimelineHandle consumerTimeline = coordination.timelines()
                    .register(CONSUMER_TIMELINE, ACTOR);
            ExactBlueValue authoredSource = coordination.values().yaml(
                    sourceYaml());
            DocumentHandle consumer = coordination.documents().admit(
                    ManagedDocument.yaml(CONSUMER, consumerYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle source = coordination.documents().admit(
                    ManagedDocument.yaml(SOURCE, sourceYaml())
                            .publicRoot()
                            .fromNow());

            assertEquals(SessionStatus.READY,
                    coordination.advanced().auditDocument(SOURCE).status());
            assertEquals(List.of(0L), coordination.advanced()
                    .auditManagedEpochs(SOURCE).stream()
                    .map(ManagedEpochReceipt::epoch)
                    .toList());
            ManagedEpochReceipt initializationReceipt = coordination
                    .advanced()
                    .auditManagedEpoch(SOURCE, 0L).orElseThrow();
            assertEquals(
                    blue.coordination.sdk.DocumentRevision.Kind.INITIALIZATION,
                    initializationReceipt.kind());
            assertTrue(initializationReceipt.beforeBlueId().isEmpty());
            assertEquals(source.snapshot().blueId(),
                    initializationReceipt.afterBlueId());
            assertTrue(initializationReceipt.sourceEntry().isEmpty());
            assertTrue(initializationReceipt.emittedEvents().isEmpty());
            assertNotEquals(authoredSource.blueId(),
                    initializationReceipt.afterBlueId());

            EntryHandle attachment = coordination.operations()
                    .on(consumer)
                    .from(consumerTimeline)
                    .call("attach")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "child", authoredSource))
                    .submit();
            DrainResult admitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertApplied(admitted, attachment);
            assertTrue(admitted.managedEpochApplications().isEmpty());
            ManagedOccurrenceCatchUpPlan pending = onlyPlan(coordination);
            assertEquals(SOURCE, pending.sourceDocumentId());
            assertEquals(-1L, pending.admittedSourceEpoch());
            assertEquals(authoredSource.blueId(),
                    pending.admittedSourceBlueId());
            assertEquals(0L, pending.nextSourceEpoch());
            assertEquals(0L, pending.requiredThroughSourceEpoch());

            long processCallsBeforeTermination = externalProcessCalls(
                    coordination);
            DefaultCoordinationEngine engine = (DefaultCoordinationEngine)
                    coordination.advanced().rawEngine();
            appendSyntheticTerminalEpoch(engine, SOURCE);
            assertEquals(processCallsBeforeTermination,
                    externalProcessCalls(coordination));
            assertEquals(SessionStatus.TERMINATED,
                    coordination.advanced().auditDocument(SOURCE).status());
            assertEquals(List.of(0L, 1L), coordination.advanced()
                    .auditManagedEpochs(SOURCE).stream()
                    .map(ManagedEpochReceipt::epoch)
                    .toList());
            ManagedEpochReceipt terminalReceipt = coordination.advanced()
                    .auditManagedEpoch(SOURCE, 1L).orElseThrow();
            assertEquals(
                    blue.coordination.sdk.DocumentRevision.Kind.TIMELINE_ENTRY,
                    terminalReceipt.kind());
            assertEquals(initializationReceipt.afterBlueId(),
                    terminalReceipt.beforeBlueId().orElseThrow());
            assertEquals(source.snapshot().blueId(),
                    terminalReceipt.afterBlueId());
            assertEquals(1, terminalReceipt.emittedEvents().size());
            ManagedEventOccurrence terminalEvent = terminalReceipt
                    .emittedEvents().get(0);
            assertTrue(terminalReceipt.sourceEntry().isPresent());
            assertTrue(terminalReceipt.sourceOrder().isPresent());
            assertEquals(SOURCE, terminalEvent.sourceDocumentId());
            assertTrue(terminalEvent.publicAtSource());
            assertEquals(coordination.values().yaml("""
                    type: Coordination/Event
                    kind: Termination/Finished
                    """).blueId(), terminalEvent.eventBlueId());
            ManagedOccurrenceCatchUpPlan extended = onlyPlan(coordination);
            assertEquals(0L, extended.nextSourceEpoch());
            assertEquals(1L, extended.requiredThroughSourceEpoch());
            List<String> sourceHistory = history(source);
            List<String> sourceReceipts = receiptIdentities(coordination);
            List<String> sourceEvents = eventIdentities(coordination);
            List<String> publicOutbox = publicOutbox(engine);
            long processCallsBefore = externalProcessCalls(coordination);
            CoordinationTestControl.MetricsSnapshot metricsBefore =
                    control.metricsSnapshot();

            // when
            DrainResult initialized = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then: epoch zero is applied before the terminal epoch.
            assertEquals(List.of(0L), sourceEpochs(
                    coordination, initialized));
            assertEquals(1, initialized.managedEpochApplications().size());
            ManagedEpochApplicationReceipt initializationApplication =
                    initialized.managedEpochApplications().get(0);
            assertEquals(initializationReceipt.receiptIdentity(),
                    initializationApplication.sourceReceiptIdentity());
            assertEquals(1L,
                    initializationApplication.resultingSourceCursor());
            ManagedOccurrenceCatchUpPlan afterInitialization = onlyPlan(
                    coordination);
            assertEquals(ManagedCatchUpStatus.RUNNING,
                    afterInitialization.status());
            assertEquals(1L, afterInitialization.nextSourceEpoch());
            assertEquals(1L,
                    afterInitialization.requiredThroughSourceEpoch());
            assertEquals(0L, consumer.snapshot().longAt(
                    "/observedTerminations"));
            assertEquals(SessionStatus.CATCHING_UP, coordination.advanced()
                    .auditManagedDocumentReadiness(CONSUMER)
                    .orElseThrow().status());

            // when: the next bounded drain applies the exact terminal receipt.
            DrainResult termination = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then
            assertEquals(List.of(1L), sourceEpochs(
                    coordination, termination), () -> "attempts="
                    + termination.managedEpochApplicationAttempts()
                    + ", evidenceFailures="
                    + termination.managedEpochEvidenceFailures()
                    + ", diagnostic=" + termination.diagnostic()
                    + ", paused=" + termination.paused()
                    + ", quiescent=" + termination.quiescent());
            assertEquals(1, termination.managedEpochApplications().size());
            ManagedEpochApplicationReceipt terminatingApplication =
                    termination.managedEpochApplications().get(0);
            assertEquals(terminalReceipt.receiptIdentity(),
                    terminatingApplication.sourceReceiptIdentity());
            assertEquals(2L,
                    terminatingApplication.resultingSourceCursor());
            ManagedOccurrenceCatchUpPlan complete = onlyPlan(coordination);
            assertEquals(ManagedCatchUpStatus.COMPLETE, complete.status());
            assertEquals(2L, complete.nextSourceEpoch());
            assertEquals(1L, complete.requiredThroughSourceEpoch());
            assertEquals(1L, consumer.snapshot().longAt(
                    "/observedTerminations"));
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(CONSUMER)
                    .orElseThrow().ready());
            assertEquals(SessionStatus.TERMINATED,
                    coordination.advanced().auditDocument(SOURCE).status());
            var sourceReadiness = coordination.advanced()
                    .auditManagedDocumentReadiness(SOURCE).orElseThrow();
            assertEquals(sourceReadiness.committedEpoch(),
                    sourceReadiness.readyEpoch().orElseThrow());
            assertEquals(sourceReadiness.committedBlueId(),
                    sourceReadiness.readyBlueId().orElseThrow());
            assertTrue(sourceReadiness.activeBarrierIdentities().isEmpty());
            assertEquals(sourceHistory, history(source));
            assertEquals(sourceReceipts, receiptIdentities(coordination));
            assertEquals(sourceEvents, eventIdentities(coordination));
            assertEquals(publicOutbox, publicOutbox(engine));
            assertEquals(processCallsBefore,
                    externalProcessCalls(coordination));
            assertEquals(0L, counterDelta(
                    metricsBefore,
                    control.metricsSnapshot(),
                    SOURCE_PROCESS_CALLS));
        }
    }

    /**
     * Publishes one self-identifying retained terminal epoch without opening
     * the production operation-time termination lane.
     */
    private static void appendSyntheticTerminalEpoch(
            DefaultCoordinationEngine engine,
            DocumentId documentId) {
        InMemoryDocumentStore store = engine.documents();
        DocumentSession session = store.require(documentId);
        InMemoryDocumentStore.PublicationSnapshot snapshot =
                store.publicationSnapshot();
        SyntheticTerminalEpoch epoch = syntheticTerminalEpoch(
                engine, session);
        ComponentSnapshot component = snapshot.componentStates().stream()
                .filter(candidate -> candidate.orderedMemberDocumentIds()
                        .stream().anyMatch(member -> member.value().equals(
                                documentId.value())))
                .findFirst()
                .orElseThrow();
        ComponentSnapshot terminalComponent = rebindAcyclicComponent(
                component, epoch.revision().after().blueId());
        CatchUpPlanStore beforePlans = store.catchUpPlansSnapshot();
        ManagedCatchUpPlanner.PlanningResult catchUp =
                ManagedCatchUpPlanner.afterPublication(
                        beforePlans,
                        snapshot.occurrenceInventory(),
                        snapshot.occurrenceInventory(),
                        List.of(documentId),
                        List.of(epoch.receipt()),
                        "synthetic-terminal-" + documentId.value(),
                        epoch.sourceOrder(),
                        selected -> {
                            if (selected.equals(documentId)) {
                                return new ManagedCatchUpPlanner.Head(
                                        epoch.revision().epoch(),
                                        epoch.revision().after().blueId());
                            }
                            DocumentSession current = store.require(selected);
                            return new ManagedCatchUpPlanner.Head(
                                    current.epoch(),
                                    current.currentRepresentation().blueId());
                        },
                        (selected, selectedEpoch) -> selected.equals(documentId)
                                && selectedEpoch == epoch.receipt().epoch()
                                ? epoch.receipt()
                                : store.managedEpochReceipt(
                                                selected, selectedEpoch)
                                        .orElse(null),
                        store::graphGeneration);
        store.beginAtomicPublication(
                        "synthetic-terminal-" + documentId.value(),
                        snapshot.occurrenceInventoryGeneration(),
                        snapshot.componentIndexGeneration())
                .expectHead(
                        documentId,
                        session.epoch(),
                        session.currentRevision().after().blueId())
                .expectComponentState(component)
                .stageDocument(
                        epoch.revision(),
                        epoch.terminalLayout(),
                        epoch.sourceOrder(),
                        session.activeSubscriptions(),
                        true,
                        "synthetic-terminal|" + documentId.value())
                .stageComponentStates(List.of(terminalComponent))
                .stageOutbox(List.of(epoch.publicEvent()))
                .stageManagedEpochReceipt(
                        epoch.receipt(), epoch.transition())
                .stageCatchUpPlans(beforePlans, catchUp.plans())
                .commit();
        DocumentSession published = store.require(documentId);
        published.markGraphPublished();
        published.markTerminated();
    }

    private static SyntheticTerminalEpoch syntheticTerminalEpoch(
            DefaultCoordinationEngine engine,
            DocumentSession session) {
        ExactValue current = session.currentRevision().after();
        ExactValue terminal = terminatedValue(current);
        ExactValue event = engine.exactValue("""
                type: Coordination/Event
                kind: Termination/Finished
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
                        true);
        PublicEventOccurrence publicEvent = new PublicEventOccurrence(
                0L,
                0L,
                contractsDocumentId,
                occurrenceIdentity,
                IntegrationEventEvidence.verify(
                        event.copyNode(), event.blueId()));
        ManagedDocumentTransitionReceipt transition = transition(
                invocationIdentity,
                contractsDocumentId,
                causeIdentity,
                current.blueId(),
                terminal.blueId(),
                contractsEvent);
        blue.coordination.api.ManagedEventOccurrence managedEvent =
                blue.coordination.api.ManagedEventOccurrence.identified(
                        0L,
                        0L,
                        session.documentId(),
                        occurrenceIdentity,
                        event,
                        true);
        ExternalOrderKey sourceOrder = session.currentRevision()
                .sourceOrderKey().orElseThrow();
        TimelineEntry sourceEntry = new TimelineEntry(
                event,
                java.util.Optional.of(ExactValue.verified(new Node().value(
                        "retained-terminal-fixture"))),
                sourceOrder,
                sourceOrder,
                new Timeline("synthetic/terminal/source", "fixture"),
                "appendTerminalEpoch",
                "fixtureChannel",
                1L,
                1L,
                1L);
        blue.coordination.api.ManagedEpochReceipt receipt =
                blue.coordination.api.ManagedEpochReceipt.identified(
                        session.documentId(),
                        Math.addExact(session.epoch(), 1L),
                        blue.coordination.api.DocumentRevision.Kind.TIMELINE_ENTRY,
                        current.blueId(),
                        terminal,
                        causeIdentity,
                        sourceEntry,
                        sourceOrder,
                        transition.transitionReceiptIdentity(),
                        hash('c'),
                        List.of(managedEvent),
                        1L);
        blue.coordination.api.DocumentRevision revision =
                new blue.coordination.api.DocumentRevision(
                        session.documentId(),
                        Math.addExact(session.epoch(), 1L),
                        session.nextApplicationOrder(),
                        blue.coordination.api.DocumentRevision.Kind.TIMELINE_ENTRY,
                        current,
                        terminal,
                        sourceEntry,
                        sourceOrder,
                        sourceEntry.blueId(),
                        null,
                        List.of(event.copyNode()),
                        1L,
                        receipt);
        EmbeddedOnlyLayout beforeLayout = session.layout();
        EmbeddedOnlyLayout terminalLayout = new EmbeddedOnlyLayout(
                terminal,
                terminal.frozen(),
                Map.of("/", terminal),
                beforeLayout.boundaries(),
                beforeLayout.directOccurrences(),
                beforeLayout.plan());
        return new SyntheticTerminalEpoch(
                revision,
                receipt,
                transition,
                sourceOrder,
                publicEvent,
                terminalLayout);
    }

    private static ManagedDocumentTransitionReceipt transition(
            String invocationIdentity,
            blue.language.processor.closure.DocumentId documentId,
            String causeIdentity,
            String beforeBlueId,
            String afterBlueId,
            ManagedRootEventOccurrence event) {
        try {
            return (ManagedDocumentTransitionReceipt)
                    IDENTIFY_TRANSITION.invoke(
                            null,
                            invocationIdentity,
                            0L,
                            documentId,
                            causeIdentity,
                            beforeBlueId,
                            afterBlueId,
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

    private static Method componentStateIdentityMethod() {
        try {
            Method method = CLOSURE_IDENTITIES.getClass().getDeclaredMethod(
                    "componentStateIdentity", ComponentSnapshot.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static ExactValue terminatedValue(ExactValue current) {
        Node body = current.copyNode();
        Node contracts = body.getContracts();
        if (contracts == null) {
            contracts = new Node();
            body.contracts(contracts);
        }
        contracts.properties(
                "terminated",
                new Node()
                        .type(new Node().blueId(TERMINATED_MARKER_BLUE_ID))
                        .properties("cause", new Node().value("finished"))
                        .properties(
                                "reason",
                                new Node().value("retained-source-fixture")));
        return ExactValue.verified(body);
    }

    private static ComponentSnapshot rebindAcyclicComponent(
            ComponentSnapshot before,
            String afterBlueId) {
        ComponentSnapshot provisional = new ComponentSnapshot(
                before.componentIdentity(),
                hash('a'),
                before.componentGeneration(),
                before.kind(),
                before.orderedMemberDocumentIds(),
                List.of(afterBlueId),
                null,
                null,
                null);
        try {
            String stateIdentity = (String) COMPONENT_STATE_IDENTITY.invoke(
                    CLOSURE_IDENTITIES, provisional);
            return new ComponentSnapshot(
                    before.componentIdentity(),
                    stateIdentity,
                    before.componentGeneration(),
                    before.kind(),
                    before.orderedMemberDocumentIds(),
                    List.of(afterBlueId),
                    null,
                    null,
                    null);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }

    private static String hash(char digit) {
        char[] digits = new char[64];
        Arrays.fill(digits, digit);
        return "sha256:" + new String(digits);
    }

    private static ManagedOccurrenceCatchUpPlan onlyPlan(
            BlueCoordination coordination) {
        List<ManagedOccurrenceCatchUpPlan> plans = coordination.advanced()
                .auditManagedCatchUpPlans(CONSUMER);
        assertEquals(1, plans.size());
        return plans.get(0);
    }

    private static List<Long> sourceEpochs(
            BlueCoordination coordination,
            DrainResult result) {
        return result.managedEpochApplications().stream()
                .map(receipt -> coordination.advanced()
                        .auditManagedEpochReceipt(
                                receipt.sourceReceiptIdentity())
                        .orElseThrow().epoch())
                .toList();
    }

    private static long externalProcessCalls(
            BlueCoordination coordination) {
        return coordination.advanced().rawEngine().metrics().counter(
                CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS);
    }

    private static List<String> history(DocumentHandle source) {
        return source.history().stream()
                .map(revision -> revision.epoch()
                        + ":" + revision.kind()
                        + ":" + revision.after().blueId()
                        + ":" + revision.managedEpochReceipt()
                                .map(ManagedEpochReceipt::receiptIdentity)
                                .orElse("-"))
                .toList();
    }

    private static List<String> receiptIdentities(
            BlueCoordination coordination) {
        return coordination.advanced().auditManagedEpochs(SOURCE).stream()
                .map(ManagedEpochReceipt::receiptIdentity)
                .toList();
    }

    private static List<String> eventIdentities(
            BlueCoordination coordination) {
        return coordination.advanced().auditManagedEpochs(SOURCE).stream()
                .flatMap(receipt -> receipt.emittedEvents().stream())
                .map(ManagedEventOccurrence::managedEventIdentity)
                .toList();
    }

    private static List<String> publicOutbox(
            DefaultCoordinationEngine engine) {
        return engine.documents().publicationSnapshot().outbox().stream()
                .map(event -> event.publicRootDocumentId().value()
                        + ":" + event.eventOccurrenceIdentity()
                        + ":" + event.eventBlueId())
                .toList();
    }

    private static long counterDelta(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot after,
            String counter) {
        return Math.subtractExact(
                after.counters().getOrDefault(counter, 0L),
                before.counters().getOrDefault(counter, 0L));
    }

    private static void assertApplied(
            DrainResult result,
            EntryHandle entry) {
        EntryResult applied = result.entry(entry);
        assertEquals(EntryDisposition.APPLIED, applied.disposition(),
                applied.diagnostic().toString());
    }

    private static String consumerYaml() {
        return """
                documentId: %s
                observedTerminations: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /child
                  fromTerminated:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event:
                      type: Coordination/Event
                      kind: Termination/Finished
                  observeTerminated:
                    type: Coordination/Sequential Workflow
                    channel: fromTerminated
                    event:
                      type: Coordination/Event
                      kind: Termination/Finished
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedTerminations
                              val: {$add: [{$document: /observedTerminations}, 1]}
                          - $return: true
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
                """.formatted(CONSUMER.value(), CONSUMER_TIMELINE, ACTOR);
    }

    private static String sourceYaml() {
        return """
                documentId: %s
                initialized: false
                finished: false
                """.formatted(SOURCE.value());
    }

    private static String terminatingSourceYaml() {
        return """
                documentId: %s
                initialized: false
                finished: false
                contracts:
                  sourceChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  finish:
                    type: Coordination/Sequential Workflow Operation
                    channel: sourceChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /finished
                              val: true
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Termination/Finished
                          - $return: true
                      - type: Coordination/Terminate Processing
                        reason: retained-source-fixture
                """.formatted(
                        SOURCE.value(), CONSUMER_TIMELINE, ACTOR);
    }

    private record SyntheticTerminalEpoch(
            blue.coordination.api.DocumentRevision revision,
            blue.coordination.api.ManagedEpochReceipt receipt,
            ManagedDocumentTransitionReceipt transition,
            ExternalOrderKey sourceOrder,
            PublicEventOccurrence publicEvent,
            EmbeddedOnlyLayout terminalLayout) {
    }
}
