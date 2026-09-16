package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.ExactValue;
import blue.coordination.api.SessionStatus;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.coordination.sdk.TimelineHandle;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact original commerce inputs; current-at-activation is independent of physical processing order. */
final class RootedBorrowedCycleReadinessTest {
    private static final String AGREEMENT = "EVvLMNHBdoccndkoYszjykKcTG4iVVP5Tj36r7iDmoHh";
    private static final String ORDER = "Ek9B25yEnC7CskGquhSXHT4RRQneUqcQBSxwcscQ5ffT";
    private static final String PAYMENT = "HKnjCgpNByogrNY8ZSALQin5kS8HBT9QYxWUrLPveWnk";
    private static final String ORDER_ZERO = "2xaPPKNPahtSFatjULiiwEBRq2AMjjLsy7WBBds7GGLg";
    private static final String PAYMENT_ZERO = "ANGwUqG4S9xui7RVuTvW7U3zdFWmx3g1DnMjP59rG6T";
    private static final String ATTACH_ORDER = "8oYVKXfbWvqzEEoKWzT4XnrTQQLpj1ehTC3U1yPjhkb5";
    private static final String ATTACH_PAYMENT = "7a8jT8RAWLd7uYMKjDAW5RmYLnQnygF42B2scxN3nxWQ";

    @Test void oneWayObserverSettlesItsBorrowedCycleWithoutPublishingIndependentSources() throws Exception {
        // given
        Evidence observerFirst = execute(false);
        // when
        Evidence sourceFirst = execute(true);
        // then
        assertEquals(observerFirst.head(), sourceFirst.head());
        assertEquals(observerFirst.history(), sourceFirst.history());
        assertFullResult(observerFirst.result(), sourceFirst.result());
        assertEquals(observerFirst.input().snapshot().closureIdentity(), sourceFirst.input().snapshot().closureIdentity());
        assertFullResult(coldMaterialized(sourceFirst.input(), sourceFirst.resources()), sourceFirst.result());
    }

    @Test void sameBoundaryLaterSourcePublicationIsNotAnActivationAnchor() throws Exception {
        // given
        try (var f = new Commerce()) {
            f.prefix();
            f.processSource();
            var current = f.capture(f.attachPayment);
            var resolution = f.resolve(current);
            var occurrence = paymentOccurrence(resolution);
            assertEquals(ManagedOccurrenceResolver.TargetKind.EXISTING_INITIALIZED_EPOCH_ZERO, occurrence.targetKind());
            var selected = RootedAttachmentCapture.select(current, resolution, f.engine.documents());
            var anchor = selected.get(f.payment.id());
            assertNotNull(anchor);
            assertEquals(PAYMENT_ZERO, anchor.retainedSnapshot().managedDocument(ContractsClosureAdapter.closureId(f.payment.id())).blueId());
            var later = f.engine.documents().require(f.payment.id()).rootedView();
            assertEquals(f.engine.auditTimelineEntry(f.attachPayment.blueId()).orElseThrow().sourceOrderKey(), later.logicalBoundary());
            assertNotEquals(anchor.result().invocationIdentity(), later.result().invocationIdentity());
            // when
            var classified = RootedAttachmentCapture.classifyAtBoundary(current, resolution, selected, f.engine.documents());
            var wrongPosition = RootedAttachmentCapture.classifyAtBoundary(current, resolution,
                    Map.of(f.payment.id(), later), f.engine.documents());
            // then
            assertEquals(ManagedOccurrenceResolver.TargetKind.CURRENT_EXISTING, paymentOccurrence(classified).targetKind());
            assertNull(paymentOccurrence(classified).pendingHistoricalEpoch());
            assertSame(occurrence.demand(), paymentOccurrence(classified).demand(), "Preserve actual processor-issued demand authority");
            assertSame(resolution, wrongPosition, "A genuine later publication at the same input is still outside the activation anchor");

            var routes = new LinkedHashMap<DocumentId, List<blue.language.processor.SubscriptionDelta.Entry>>();
            anchor.snapshot().managedDocuments().forEach(row -> routes.put(ContractsClosureAdapter.coordinationId(row.documentId()),
                    anchor.routes(ContractsClosureAdapter.coordinationId(row.documentId()))));
            var reconstructed = new RootedDocumentView(anchor.result(), anchor.subscriptions(), routes, anchor.logicalBoundary())
                    .withPublishedHeads(Map.of(f.payment.id(), new InMemoryDocumentStore.DocumentHead(0L, PAYMENT_ZERO)));
            assertNotSame(anchor, reconstructed);
            var reopened = RootedAttachmentCapture.classifyAtBoundary(current, resolution,
                    Map.of(f.payment.id(), reconstructed), f.engine.documents());
            assertEquals(ManagedOccurrenceResolver.TargetKind.CURRENT_EXISTING, paymentOccurrence(reopened).targetKind(),
                    "The same authenticated publication must not depend on Java object identity");
        }
    }

    @Test void secondNewPathToAnAlreadyBorrowedSourceUsesTheSameLogicalAnchor() throws Exception {
        // given
        var observerFirst = execute(false, true);
        // when
        var sourceFirst = execute(true, true);
        // then
        assertEquals(observerFirst.head(), sourceFirst.head());
        assertEquals(observerFirst.history(), sourceFirst.history());
        assertFullResult(observerFirst.result(), sourceFirst.result());
        assertFullResult(coldMaterialized(sourceFirst.input(), sourceFirst.resources()), sourceFirst.result());
    }

    @Test void actualOlderNumberedSourceStillRequiresItsHistoricalInterval() throws Exception {
        // given
        try (var f = new Commerce()) {
            f.prefix();
            var savedPayment = f.body(f.payment);
            f.processSource();
            f.processObserver();
            var beforeHeads = List.of(f.head(f.agreement), f.head(f.order), f.head(f.payment));
            var beforeHistories = List.of(f.history(f.agreement), f.history(f.order), f.history(f.payment));
            var later = f.append(f.agreement, "attachOrder", 3L,
                    Map.of("orderKey", "saved-payment", "order", savedPayment));
            var current = f.capture(later);
            var resolution = f.resolve(current);
            var occurrence = paymentOccurrence(resolution, AGREEMENT, "/orders/saved-payment");
            assertEquals(0L, occurrence.admittedSourceEpoch());
            assertEquals(PAYMENT_ZERO, occurrence.expectedTargetBlueId());
            var selected = RootedAttachmentCapture.select(current, resolution, f.engine.documents());
            var boundary = f.engine.auditTimelineEntry(later.blueId()).orElseThrow().sourceOrderKey();
            assertEquals(1L, f.engine.documents().require(f.payment.id()).rootedViewBefore(boundary).retainedEpoch(f.payment.id()));
            // when
            var classified = RootedAttachmentCapture.classifyAtBoundary(current, resolution, selected, f.engine.documents());
            // then
            assertSame(resolution, classified);
            assertEquals(Long.valueOf(0L), paymentOccurrence(classified, AGREEMENT, "/orders/saved-payment").pendingHistoricalEpoch());
            assertEquals(beforeHeads, List.of(f.head(f.agreement), f.head(f.order), f.head(f.payment)));
            assertEquals(beforeHistories, List.of(f.history(f.agreement), f.history(f.order), f.history(f.payment)),
                    "The ready-state classifier negative must not publish its new suspended attempt");
        }
    }

    @Test void sameEpochLocallyAdvancedPrimaryCannotBeMistakenForItsNumberedSourceAnchor() throws Exception {
        // given
        try (var f = new Commerce()) {
            var sourceTimeline = f.blue.timelines().register("rcp2/source", "alice");
            f.blue.timelines().register("rcp2/parent", "alice");
            var receiverTimeline = f.blue.timelines().register("rcp2/same-epoch-receiver", "alice");
            f.order = f.admitYaml(rootedResource("source.yaml") + """
                      emitUnmatched:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request: {}
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: RCP2/Unmatched
                          - $return: true
                    """);
            f.payment = f.admitYaml(rootedResource("parent.yaml") + "\nchild: {blueId: " + f.order.snapshot().blueId() + "}\n");
            String numberedBlueId = f.payment.snapshot().blueId();
            var savedParent = f.body(f.payment);
            f.agreement = f.admitYaml("""
                    name: Same-epoch source-position receiver
                    children:
                      first: {blueId: %s}
                    contracts:
                      embedded:
                        type: Process Embedded
                        collectionPaths: [/children]
                      owner:
                        type: Coordination/Timeline Channel
                        timeline:
                          type: MyOS/MyOS Timeline
                          timelineId: rcp2/same-epoch-receiver
                        actor:
                          type: MyOS/Principal Actor
                          accountId: alice
                      attach:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request:
                          child: {}
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $appendChange:
                              op: add
                              path: /children/second
                              val:
                                $binding: event/message/request/child
                          - $return: true
                    """.formatted(numberedBlueId));
            var independentHeads = List.of(f.head(f.order), f.head(f.payment));
            var independentHistories = List.of(f.history(f.order), f.history(f.payment));
            var unmatched = f.blue.operations().on(f.order).from(sourceTimeline).call("emitUnmatched")
                    .through("owner").requestYaml("{}").submit();
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(f.agreement, unmatched).entry(unmatched).disposition());
            assertEquals(SessionStatus.READY, f.engine.documents().require(f.agreement.id()).status());
            assertEquals(independentHeads, List.of(f.head(f.order), f.head(f.payment)));
            assertEquals(independentHistories, List.of(f.history(f.order), f.history(f.payment)));
            var beforeHeads = List.of(f.head(f.agreement), f.head(f.order), f.head(f.payment));
            var beforeHistories = List.of(f.history(f.agreement), f.history(f.order), f.history(f.payment));
            var later = f.blue.operations().on(f.agreement).from(receiverTimeline).call("attach").through("owner")
                    .requestYaml(f.json.writeValueAsString(Map.of("child", savedParent))).submit();
            var current = f.capture(later);
            var local = current.input().snapshot().managedDocument(ContractsClosureAdapter.closureId(f.payment.id()));
            var boundary = f.engine.auditTimelineEntry(later.blueId()).orElseThrow().sourceOrderKey();
            var anchor = f.engine.documents().require(f.payment.id()).rootedViewBefore(boundary);
            var numbered = anchor.retainedSnapshot().managedDocument(ContractsClosureAdapter.closureId(f.payment.id()));
            assertEquals(0L, numbered.epoch());
            assertEquals(numbered.epoch(), local.epoch(), "Unmatched-event checkpoint work must keep the actual numbered epoch");
            assertEquals(numberedBlueId, numbered.blueId());
            assertNotEquals(numbered.blueId(), local.blueId(), "Only the borrowed parent's checkpoint representation advanced");
            anchor.requirePublishedHead(f.payment.id(), 0L, numberedBlueId);
            var resolved = f.resolve(current);
            var original = occurrence(resolved, f.payment.id().value(), f.agreement.id().value(), "/children/second");
            assertEquals(ManagedOccurrenceResolver.TargetKind.CURRENT_EXISTING, original.targetKind());
            // Deliberately construct only an adversarial classifier discriminator. The local
            // state, source anchor and emitted demand all come from actual SDK execution.
            var historical = new ManagedOccurrenceResolver.ResolvedOccurrence(original.demand(), original.targetDocumentId(),
                    original.expectedTargetBlueId(), ManagedOccurrenceResolver.TargetKind.EXISTING_INITIALIZED_EPOCH_ZERO, 0L, null);
            var candidate = new ManagedOccurrenceResolver.Resolution(resolved.demands(), resolved.resolvedOccurrences().stream()
                    .map(row -> row == original ? historical : row).toList(), resolved.resolvedExactNodes(),
                    resolved.unresolvedDemands(), resolved.resolvedSelectorPaths());
            var selected = RootedAttachmentCapture.select(current, candidate, f.engine.documents());
            assertFalse(selected.containsKey(f.payment.id()), "The complete primary is already frozen; no replacement view is added");
            // when
            var classified = RootedAttachmentCapture.classifyAtBoundary(current, candidate, selected, f.engine.documents());
            // then
            assertSame(candidate, classified, "Equal epoch alone cannot make a different local position current-at-activation");
            assertSame(original.demand(), occurrence(classified, f.payment.id().value(), f.agreement.id().value(), "/children/second").demand());
            assertEquals(beforeHeads, List.of(f.head(f.agreement), f.head(f.order), f.head(f.payment)));
            assertEquals(beforeHistories, List.of(f.history(f.agreement), f.history(f.order), f.history(f.payment)));
        }
    }

    @Test void borrowedCycleCalculationRetainsExactGasBoundaryRollback() throws Exception {
        // given
        var successful = execute(true);
        long gas = successful.result().totalGas();
        assertTrue(gas > 1L);
        // when
        for (long delta : List.of(-1L, 0L, 1L)) {
            try (var f = new Commerce()) {
                f.prefix();
                f.processSource();
                var beforeHeads = List.of(f.head(f.agreement), f.head(f.order), f.head(f.payment));
                var beforeHistories = List.of(f.history(f.agreement), f.history(f.order), f.history(f.payment));
                var policy = ContractsExecutionPolicy.exactSharedGas(gas + delta, successful.input().executionPolicy().label());
                var batch = f.engine.contractsClosureAdapter().captureRoot(f.agreement.id(),
                        f.engine.auditTimelineEntry(f.attachPayment.blueId()).orElseThrow(), policy);
                assertEquals(1, batch.invocations().size());
                var outcomes = f.engine.contractsClosureAdapter().processAndPublish(batch);
                // then
                assertEquals(1, outcomes.size());
                var outcome = outcomes.get(0);
                assertTrue(outcome.attempt().isComplete());
                var actual = outcome.attempt().processResult();
                var terminal = f.engine.documents().closurePublicationReceipt(outcome.publicationIdentity()).orElseThrow();
                var exactInput = terminal.rootedTerminalEvidence().input();
                assertEquals(gas + delta, exactInput.executionPolicy().sharedLimit());
                var reference = coldMaterialized(exactInput, f.resources());
                assertEquals(reference.status(), actual.status());
                assertEquals(reference.inputClosureIdentity(), actual.inputClosureIdentity());
                assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
                assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
                assertEquals(fullTrace(reference), fullTrace(actual));
                assertEquals(beforeHeads.subList(1, 3), List.of(f.head(f.order), f.head(f.payment)));
                assertEquals(beforeHistories.subList(1, 3), List.of(f.history(f.order), f.history(f.payment)));
                if (delta < 0L) {
                    assertFalse(outcome.published());
                    assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, actual.status());
                    assertTrue(actual.rollbackToInput());
                    assertEquals(exactInput.snapshot().closureIdentity(), actual.outputClosureIdentity());
                    assertNull(actual.commitCompanion());
                    assertTrue(actual.publicEvents().isEmpty());
                    assertTrue(actual.managedTransitionReceipts().isEmpty());
                    assertTrue(actual.checkpointWrites().isEmpty());
                    assertNotNull(actual.rejectedCharge());
                    assertEquals(gas, Math.addExact(actual.totalGas(), actual.rejectedCharge().subtotal()));
                    assertEquals(reference.rejectedCharge().rejectedChargeIdentity(), actual.rejectedCharge().rejectedChargeIdentity());
                    assertEquals(beforeHeads, List.of(f.head(f.agreement), f.head(f.order), f.head(f.payment)));
                    assertEquals(beforeHistories, List.of(f.history(f.agreement), f.history(f.order), f.history(f.payment)));
                } else {
                    assertTrue(outcome.published());
                    assertTrue(actual.commits(), String.valueOf(actual.diagnostic()));
                    assertEquals(gas, actual.totalGas());
                    assertFullResult(reference, actual);
                    assertEquals(successful.result().outputClosureIdentity(), actual.outputClosureIdentity());
                    assertEquals(documents(successful.result()), documents(actual));
                    assertEquals(SessionStatus.READY, f.engine.documents().require(f.agreement.id()).status());
                    assertEquals(List.of(AGREEMENT), actual.rootedProjection().ownedDocumentIds().stream().map(id -> id.value()).toList());
                }
                var afterHeads = List.of(f.head(f.agreement), f.head(f.order), f.head(f.payment));
                var afterHistories = List.of(f.history(f.agreement), f.history(f.order), f.history(f.payment));
                f.engine.restartFromStores();
                var replayed = f.engine.contractsClosureAdapter().processAndPublish(batch).get(0);
                assertTrue(replayed.replayed(), "Retry the exact frozen obligation after scheduler reconstruction");
                assertEquals(outcome.publicationIdentity(), replayed.publicationIdentity());
                assertEquals(actual.gasTraceIdentity(), replayed.attempt().processResult().gasTraceIdentity());
                assertEquals(fullTrace(actual), fullTrace(replayed.attempt().processResult()));
                assertEquals(actual.status(), replayed.attempt().processResult().status());
                assertEquals(afterHeads, List.of(f.head(f.agreement), f.head(f.order), f.head(f.payment)));
                assertEquals(afterHistories, List.of(f.history(f.agreement), f.history(f.order), f.history(f.payment)));
            }
        }
    }

    private static Evidence execute(boolean sourceFirst) throws Exception {
        return execute(sourceFirst, false);
    }

    private static Evidence execute(boolean sourceFirst, boolean sharedSource) throws Exception {
        try (var f = new Commerce()) {
            if (sharedSource) f.sharedPrefix();
            else f.prefix();
            if (sourceFirst) f.processSource();
            if (sharedSource) {
                var frozen = f.capture(f.attachPayment).input().snapshot();
                assertTrue(frozen.contains(ContractsClosureAdapter.closureId(f.payment.id())));
                assertTrue(frozen.occurrences().stream().noneMatch(row -> row.sourceDocumentId().value().equals(ORDER)
                        && row.sourcePath().equals("/payment")), "Only the second new path is being classified");
                assertEquals(PAYMENT_ZERO, frozen.managedDocument(ContractsClosureAdapter.closureId(f.payment.id())).blueId());
            }
            var independentHistories = List.of(f.history(f.order), f.history(f.payment));
            var independentHeads = List.of(f.head(f.order), f.head(f.payment));
            var processed = f.blue.processing().process(f.agreement, f.attachPayment).entry(f.attachPayment);
            assertEquals(EntryDisposition.APPLIED, processed.disposition(), processed.diagnostic().toString());
            assertEquals(1, processed.closures().size());
            var terminal = f.engine.documents().closurePublicationReceipt(processed.closures().get(0).closureId()).orElseThrow();
            var actual = terminal.attempt().processResult();
            var input = terminal.rootedTerminalEvidence().input();
            var selectedPayment = input.snapshot().managedDocument(ContractsClosureAdapter.closureId(f.payment.id()));
            assertEquals(0L, selectedPayment.epoch());
            assertEquals(PAYMENT_ZERO, selectedPayment.blueId(), "Later independent processing cannot replace the exact logical input");
            assertEquals(independentHistories, List.of(f.history(f.order), f.history(f.payment)));
            assertEquals(independentHeads, List.of(f.head(f.order), f.head(f.payment)));
            var agreement = f.engine.documents().require(f.agreement.id());
            assertEquals(sharedSource ? 3L : 2L, agreement.epoch());
            assertEquals(SessionStatus.READY, agreement.status());
            assertEquals(agreement.epoch(), agreement.readyEpoch());
            var selected = agreement.rootedView().snapshot();
            assertEquals(List.of(AGREEMENT), selected.publicRootDocumentIds().stream().map(id -> id.value()).toList());
            for (var pair : List.of(List.of(AGREEMENT, ORDER), List.of(ORDER, PAYMENT), List.of(PAYMENT, ORDER))) {
                assertTrue(selected.occurrences().stream().anyMatch(row -> row.sourceDocumentId().value().equals(pair.get(0))
                        && row.targetDocumentId().value().equals(pair.get(1)) && row.active()
                        && row.pendingHistoricalEpoch() == null && row.pendingRepresentationCursor() == null), pair.toString());
            }
            assertTrue(RootedLocalHistory.pending(selected, f.agreement.id()).isEmpty());
            assertEquals(List.of(AGREEMENT), actual.rootedProjection().ownedDocumentIds().stream().map(id -> id.value()).toList());
            var outcome = new Evidence(input, actual, f.resources(), f.head(f.agreement), f.history(f.agreement));
            if (!sourceFirst) {
                f.processSource();
                assertEquals(outcome.head(), f.head(f.agreement));
                assertEquals(outcome.history(), f.history(f.agreement));
            }
            var histories = List.of(f.history(f.agreement), f.history(f.order), f.history(f.payment));
            var heads = List.of(f.head(f.agreement), f.head(f.order), f.head(f.payment));
            f.engine.restartFromStores();
            var again = f.blue.processing().processNext(f.agreement);
            assertTrue(again.quiescent(), again.diagnostic().toString());
            assertEquals(0L, again.stats().committedTransitions());
            assertEquals(histories, List.of(f.history(f.agreement), f.history(f.order), f.history(f.payment)));
            assertEquals(heads, List.of(f.head(f.agreement), f.head(f.order), f.head(f.payment)));
            assertEquals(SessionStatus.READY, f.engine.documents().require(f.agreement.id()).status());
            return outcome;
        }
    }

    private static ManagedOccurrenceResolver.ResolvedOccurrence paymentOccurrence(ManagedOccurrenceResolver.Resolution resolution) {
        return paymentOccurrence(resolution, ORDER, "/payment");
    }

    private static ManagedOccurrenceResolver.ResolvedOccurrence paymentOccurrence(ManagedOccurrenceResolver.Resolution resolution,
            String source, String path) {
        return occurrence(resolution, PAYMENT, source, path);
    }

    private static ManagedOccurrenceResolver.ResolvedOccurrence occurrence(ManagedOccurrenceResolver.Resolution resolution,
            String target, String source, String path) {
        return resolution.resolvedOccurrences().stream().filter(row -> row.targetDocumentId().value().equals(target)
                && row.demand().sourceDocumentId().value().equals(source) && row.demand().sourcePath().equals(path))
                .findFirst().orElseThrow();
    }

    private static String rootedResource(String name) throws Exception {
        try (var stream = RootedBorrowedCycleReadinessTest.class.getResourceAsStream("/rooted/" + name)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Evidence(ClosureInvocationInput input, ClosureProcessResult result, List<ExactValue> resources,
            List<Object> head, List<List<Object>> history) { }

    private static void assertFullResult(ClosureProcessResult expected, ClosureProcessResult actual) {
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.invocationIdentity(), actual.invocationIdentity());
        assertEquals(expected.inputClosureIdentity(), actual.inputClosureIdentity());
        assertEquals(expected.outputClosureIdentity(), actual.outputClosureIdentity());
        assertEquals(expected.commitCompanion().companionIdentity(), actual.commitCompanion().companionIdentity());
        assertEquals(expected.publicEventsIdentity(), actual.publicEventsIdentity());
        assertEquals(expected.checkpointWritesIdentity(), actual.checkpointWritesIdentity());
        assertEquals(expected.managedTransitionReceiptsIdentity(), actual.managedTransitionReceiptsIdentity());
        assertEquals(expected.totalGas(), actual.totalGas());
        assertEquals(expected.gasTraceIdentity(), actual.gasTraceIdentity());
        assertEquals(fullTrace(expected), fullTrace(actual));
        assertEquals(documents(expected), documents(actual));
        assertEquals(expected.occurrenceBindings().stream().map(row -> row.bindingIdentity()).toList(),
                actual.occurrenceBindings().stream().map(row -> row.bindingIdentity()).toList());
        assertEquals(expected.rootedProjection().context().identity(), actual.rootedProjection().context().identity());
        assertEquals(expected.rootedProjection().deliveryBasisIdentity(), actual.rootedProjection().deliveryBasisIdentity());
        assertEquals(expected.rootedProjection().companionIdentity(), actual.rootedProjection().companionIdentity());
        assertEquals(expected.rootedProjection().ownedDocumentIds(), actual.rootedProjection().ownedDocumentIds());
    }

    private static List<List<Object>> documents(ClosureProcessResult result) {
        return result.resultingDocuments().stream().map(document -> Arrays.<Object>asList(document.documentId(),
                document.beforeBlueId(), document.afterBlueId(), UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(document.document()),
                document.initialized(), document.terminated(), document.publicRoot(), document.epoch(), document.componentGeneration(),
                document.componentIdentity(), document.componentStateIdentity(), document.memberIndex())).toList();
    }

    private static List<List<Object>> fullTrace(ClosureProcessResult result) {
        return result.gasTrace().stream().map(charge -> Arrays.<Object>asList(charge.sequence(), charge.namespace(),
                charge.counter(), charge.quantity(), charge.weight(), charge.subtotal(), charge.documentId(), charge.scopePath(),
                charge.activationGeneration(), charge.componentGeneration(), charge.contractKey(), charge.logicalPath(),
                charge.workOccurrenceId(), charge.reason())).toList();
    }

    /** Reuses the exact original rooted authority with fresh physical objects; never reconstructs a flat unrooted oracle. */
    private static ClosureProcessResult coldMaterialized(ClosureInvocationInput input, List<ExactValue> resources) {
        var objects = new WholeObjectStore(new EngineMetrics());
        for (var value : resources) objects.putVerifiedProviderEvidence(value, value.copyNode(),
                value.cyclicSetProof().orElse(null), "borrowed cycle exact input resource");
        for (var document : input.snapshot().managedDocuments()) {
            var component = input.snapshot().components().stream()
                    .filter(value -> value.orderedMemberDocumentIds().contains(document.documentId())).findFirst().orElseThrow();
            var proof = component.completeCyclicProof();
            var value = proof == null ? ExactValue.verified(document.blueId(), document.document())
                    : ExactValue.fromVerifiedProviderEvidence(document.blueId(), document.document(), proof);
            objects.putVerifiedProviderEvidence(value, document.document(), proof, "borrowed cycle frozen snapshot");
        }
        try (var runtime = BlueRuntime.create(objects)) {
            var attempt = new BlueClosureContracts(runtime.documentProcessor()).processClosure(input);
            assertTrue(attempt.isComplete(), () -> "Cold exact calculation suspended: " + attempt.resourceDemands().stream()
                    .map(demand -> demand.kind() + ":" + demand.sourceDocumentId() + ":" + demand.sourcePath()).toList());
            return attempt.processResult();
        }
    }

    private static final class Commerce implements AutoCloseable {
        final Map<String, String> exact = new LinkedHashMap<>();
        final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds()
                .exactNodeProvider(id -> Optional.ofNullable(exact.get(id))).build();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        final TimelineHandle alice = blue.timelines().register("tutorial/business/alice", "alice");
        final TimelineHandle bob = blue.timelines().register("tutorial/business/bob", "bob");
        final ObjectMapper json = new ObjectMapper();
        DocumentHandle order;
        DocumentHandle agreement;
        DocumentHandle payment;
        EntryHandle attachPayment;
        String previous;
        boolean originalPins = true;

        void prefix() throws Exception {
            order = admit("order.yaml", "");
            agreement = admit("agreement.yaml", "");
            assertEquals(ORDER, order.id().value());
            assertEquals(AGREEMENT, agreement.id().value());
            assertEquals(ORDER_ZERO, order.snapshot().blueId());
            var attachOrder = append(agreement, "attachOrder", 1L, Map.of("orderKey", "order-001", "order", body(order)));
            assertEquals(ATTACH_ORDER, attachOrder.blueId());
            assertEquals(EntryDisposition.APPLIED, blue.processing().process(agreement, attachOrder).entry(attachOrder).disposition());
            assertEquals(SessionStatus.READY, engine.documents().require(agreement.id()).status());
            payment = admit("payment.yaml", "\norder:\n  blueId: " + ORDER_ZERO + "\n");
            assertEquals(PAYMENT, payment.id().value());
            assertEquals(PAYMENT_ZERO, payment.snapshot().blueId());
            attachPayment = append(order, "attachPayment", 2L, Map.of("payment", body(payment)));
            assertEquals(ATTACH_PAYMENT, attachPayment.blueId());
        }

        void processSource() {
            assertEquals(EntryDisposition.APPLIED, blue.processing().process(order, attachPayment).entry(attachPayment).disposition());
        }

        void processObserver() {
            assertEquals(EntryDisposition.APPLIED, blue.processing().process(agreement, attachPayment).entry(attachPayment).disposition());
            assertEquals(SessionStatus.READY, engine.documents().require(agreement.id()).status());
        }

        void sharedPrefix() throws Exception {
            originalPins = false;
            order = admit("order.yaml", "");
            agreement = admit("agreement.yaml", "");
            payment = admit("payment.yaml", "\norder:\n  blueId: " + ORDER_ZERO + "\n");
            assertEquals(ORDER_ZERO, order.snapshot().blueId());
            assertEquals(PAYMENT_ZERO, payment.snapshot().blueId());
            for (var pair : List.of(Map.entry("order-001", order), Map.entry("payment-witness", payment))) {
                long sequence = previous == null ? 1L : 2L;
                var entry = append(agreement, "attachOrder", sequence, Map.of("orderKey", pair.getKey(), "order", body(pair.getValue())));
                assertEquals(EntryDisposition.APPLIED, blue.processing().process(agreement, entry).entry(entry).disposition());
                assertEquals(SessionStatus.READY, engine.documents().require(agreement.id()).status());
            }
            attachPayment = append(order, "attachPayment", 3L, Map.of("payment", body(payment)));
        }

        ContractsClosureAdapter.CohortInvocation capture(EntryHandle entry) {
            var batch = engine.contractsClosureAdapter().captureRoot(agreement.id(), engine.auditTimelineEntry(entry.blueId()).orElseThrow());
            assertEquals(1, batch.invocations().size());
            return batch.invocations().get(0);
        }

        ManagedOccurrenceResolver.Resolution resolve(ContractsClosureAdapter.CohortInvocation current) {
            var attempt = new BlueClosureContracts(engine.runtime().documentProcessor()).processClosure(current.input());
            assertFalse(attempt.isComplete(), "The original processor must actually request new occurrence evidence");
            var resolver = new ManagedOccurrenceResolver(engine.runtime().nodeProvider(), new EngineMetrics(),
                    new ManagedRepresentationHistory(engine.documents()));
            var input = current.input();
            var result = resolver.resolve(new ManagedOccurrenceResolver.ResolutionRequest(input.cause().causeIdentity(),
                    input.snapshot().closureIdentity(), input.snapshot().graphGeneration(), new LinkedHashSet<>(current.members()),
                    attempt.resourceDemands(), engine.documents().occurrenceResolutionSnapshot()));
            assertTrue(result.complete(), result.unresolvedDemands().toString());
            return result;
        }

        DocumentHandle admit(String resource, String suffix) throws Exception {
            String yaml;
            try (var stream = getClass().getResourceAsStream("/rooted/commerce/" + resource)) {
                yaml = new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8) + suffix;
            }
            var authored = blue.values().yaml(yaml);
            exact.put(authored.blueId(), authored.json());
            var document = blue.documents().admitStaticProcessEmbedded(yaml, ActivationPolicy.importFullHistory()).document("root");
            exact.put(document.snapshot().blueId(), document.snapshot().exact().json());
            return document;
        }

        DocumentHandle admitYaml(String yaml) {
            var authored = blue.values().yaml(yaml);
            exact.put(authored.blueId(), authored.json());
            var document = blue.documents().admitStaticProcessEmbedded(yaml, ActivationPolicy.importFullHistory()).document("root");
            exact.put(document.snapshot().blueId(), document.snapshot().exact().json());
            return document;
        }

        Object body(DocumentHandle document) throws Exception {
            return json.readTree(document.snapshot().exact().json());
        }

        EntryHandle append(DocumentHandle target, String operation, long sequence, Object request) throws Exception {
            // Preserve the original public request as one exact whole-value reference.
            var entry = blue.operations().on(target).from(alice).call(operation).through("aliceChannel")
                    .requestYaml(json.writeValueAsString(request)).submit();
            var retained = engine.auditTimelineEntry(entry.blueId()).orElseThrow();
            assertEquals(1_800_000_000_000_000L + sequence, retained.timestampMicros());
            var requestReference = retained.exactEvent().canonicalAt("/message/request");
            assertTrue(requestReference.isReferenceOnly());
            if (originalPins && sequence <= 2L) assertEquals(List.of("EtaUaqgYkoU7AxojfL4TNQvX1vWL83w92pcPrjz2DDKX",
                    "F4BWriwLuLhk5KVVhqn9z69Y2HW2Anh43ecArHTKtX3f").get((int) sequence - 1),
                    requestReference.getReferenceBlueId(), "Original public whole-request identity");
            assertEquals(retained.request().orElseThrow().blueId(), requestReference.getReferenceBlueId());
            var predecessor = retained.exactEvent().canonicalAt("/prevEntry");
            assertEquals(previous, predecessor == null ? null : predecessor.getReferenceBlueId());
            previous = entry.blueId();
            return entry;
        }

        List<ExactValue> resources() {
            var result = new ArrayList<ExactValue>();
            exact.keySet().forEach(id -> result.add(engine.objects().require(id)));
            engine.auditTimelineEntries().forEach(entry -> entry.request().ifPresent(result::add));
            return List.copyOf(result);
        }

        List<Object> head(DocumentHandle document) {
            var session = engine.documents().require(document.id());
            return List.of(session.epoch(), session.currentRepresentation().blueId(),
                    session.currentRepresentation().frozen().resolvedStructuralKey());
        }

        List<List<Object>> history(DocumentHandle document) {
            return blue.advanced().auditManagedEpochs(document.id()).stream().map(receipt -> List.<Object>of(
                    receipt.receiptIdentity(), receipt.documentId(), receipt.epoch(), receipt.kind(), receipt.beforeBlueId(),
                    receipt.afterBlueId(), receipt.afterDocument().json(), receipt.originalCauseIdentity(),
                    receipt.sourceEntry().map(entry -> List.of(entry.exact().json(), entry.globalSequence(), entry.timelineSequence())),
                    receipt.sourceOrder(), receipt.contractsTransitionReceiptIdentity(), receipt.commitCompanionIdentity(),
                    receipt.emittedEvents().stream().map(event -> List.of(event.managedEventIdentity(), event.ordinal(),
                            event.eventOccurrenceOrdinal(), event.sourceDocumentId(), event.eventOccurrenceIdentity(),
                            event.eventBlueId(), event.exactEvent().json(), event.publicAtSource())).toList(),
                    receipt.processingGas())).toList();
        }

        @Override public void close() { blue.close(); }
    }
}
