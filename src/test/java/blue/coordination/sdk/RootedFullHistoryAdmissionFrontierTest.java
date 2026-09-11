package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** FULL_HISTORY's beginning marker cannot replace an admission-selected source publication. */
final class RootedFullHistoryAdmissionFrontierTest {
    @ParameterizedTest
    @ValueSource(strings = {"authored-reference", "authored-inline", "epoch-one", "current"})
    void selectedSourceImportsItsExactOrderedInterval(String selected) throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var setup = threeSourceChanges(f);
            String child = switch (selected) {
                case "authored-inline" -> setup.authoredYaml().indent(2);
                case "authored-reference" -> reference(setup.source().id().value());
                case "epoch-one" -> reference(setup.epochOne());
                case "current" -> reference(setup.source().snapshot().blueId());
                default -> throw new AssertionError(selected);
            };
            var parent = f.startYaml(parent(child), "rcp2/parent");
            var sourceBefore = receiptState(f, setup.source());
            String sourceHead = setup.source().snapshot().exact().json();
            int selectedEpoch = selected.startsWith("authored") ? -1 : selected.equals("epoch-one") ? 1 : 3;
            List<Long> expectedImported = selectedEpoch < 0 ? List.of(0L, 1L, 2L, 3L)
                    : selectedEpoch == 1 ? List.of(2L, 3L) : List.of();

            // when
            restart(f);
            assertEquals(expectedImported, finishNumbered(f, parent, selectedEpoch, 3));

            // then
            assertEquals(3L, ((Number) f.selected(parent, "/child").scalarAt("/counter")).longValue());
            assertEquals(expectedLog(selectedEpoch, 3), log(latest(f, parent)));
            assertEquals(sourceHead, setup.source().snapshot().exact().json());
            assertEquals(sourceBefore, receiptState(f, setup.source()));
            var parentBefore = receiptState(f, parent);
            restart(f);
            assertEquals(parentBefore, receiptState(f, parent));
            assertEquals(sourceBefore, receiptState(f, setup.source()));
            assertEquals(expectedLog(selectedEpoch, 3), log(parent.snapshot().exact()));
        }
    }

    @Test
    void laterSourcePublicationRemainsLiveAfterTheFrozenImportAndRestart() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var setup = threeSourceChanges(f);
            var source = setup.source();
            String frozen = source.snapshot().blueId();
            var parent = f.startYaml(parent(reference(source.id().value())), "rcp2/parent");
            assertFrozenEnd(f, parent, 3);
            var future = f.append(source, "rcp2/source", "tick", 400L, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, future).entry(future).disposition());
            var futureSource = receiptState(f, source);

            // when
            restart(f);
            assertEquals(List.of(0L, 1L, 2L, 3L), finishNumbered(f, parent, -1, 3));
            // then
            assertFrozenEnd(f, parent, 3);
            assertEquals(frozen, parent.snapshot().valueAt("/child").blueId());
            assertEquals(List.of(1L, 2L, 3L), log(parent.snapshot().exact()));
            assertEquals(4L, source.snapshot().longAt("/counter"));
            assertEquals(futureSource, receiptState(f, source));
            var beforeLive = receiptState(f, parent);
            restart(f);
            assertEquals(beforeLive, receiptState(f, parent));

            var live = f.blue.processing().processNext(parent);
            assertEquals(EntryDisposition.APPLIED, live.entry(future).disposition());
            assertTrue(live.managedEpochApplicationAttempts().isEmpty());
            assertEquals(List.of(1L, 2L, 3L, 4L), log(parent.snapshot().exact()));
            assertEquals(source.snapshot().blueId(), parent.snapshot().valueAt("/child").blueId());
            assertEquals(futureSource, receiptState(f, source));
        }
    }

    @Test
    void fullHistoryKeepsTwoSelectedSameEpochPositionsButNotTheLaterThird() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            String sourceYaml = RootedSdkFixture.resource("source.yaml") + """
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
                    """;
            var source = f.startYaml(sourceYaml, "rcp2/source");
            String parentYaml = parent(reference(f.retain(source)));
            var authoredParent = f.blue.values().yaml(parentYaml);
            f.exact.put(authoredParent.blueId(), authoredParent.json());
            var parent = f.startYaml(parentYaml, "rcp2/parent");
            var numbered = receiptState(f, parent);
            var representations = new ArrayList<String>();
            representations.add(parent.snapshot().blueId());
            for (int index = 1; index <= 2; index++) {
                var entry = f.append(source, "rcp2/source", "emitUnmatched", index * 100L, "{}");
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(parent, entry).entry(entry).disposition());
                representations.add(parent.snapshot().blueId());
                // Keep the exact durable forward members usable for static admission;
                // this does not republish the parent's own checkpoint representation.
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, entry).entry(entry).disposition());
            }
            assertEquals(3L, representations.stream().distinct().count());
            assertEquals(0L, parent.snapshot().epoch());
            assertEquals(numbered, receiptState(f, parent));
            String frozen = parent.snapshot().blueId();
            var consumer = f.startYaml("name: FULL_HISTORY same-epoch observer\nchild:\n"
                    + reference(authoredParent.blueId())
                    + "contracts:\n  embedded:\n    type: Process Embedded\n    paths: [/child]\n", "rcp2/consumer");
            var first = f.control.registeredOwnedHistory(consumer.id());
            assertEquals(0L, first.sourceEpoch());
            String target = first.successorRepresentationCause().orElseThrow().targetPositionIdentity();

            // when
            var future = f.append(source, "rcp2/source", "emitUnmatched", 300L, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(parent, future).entry(future).disposition());
            String later = parent.snapshot().blueId();
            assertNotEquals(frozen, later);
            assertEquals(numbered, receiptState(f, parent));
            var sourceHistory = receiptState(f, source);
            restart(f);
            assertEquals(target, f.control.registeredOwnedHistory(consumer.id())
                    .successorRepresentationCause().orElseThrow().targetPositionIdentity());

            var appliedPositions = new ArrayList<String>();
            int numberedApplications = 0;
            int selections = 0;
            while (!ready(f, consumer)) {
                assertTrue(selections++ < 8, "The frozen H0 plus two positions must finish");
                var work = f.control.registeredOwnedHistory(consumer.id());
                if (work.representationCause().isEmpty()) numberedApplications++;
                work.representationCause().ifPresent(cause -> appliedPositions.add(cause.transition().positionIdentity()));
                assertMaterializedNext(f, consumer);
                assertEquals(numbered, receiptState(f, parent));
                assertEquals(later, parent.snapshot().blueId());
                assertEquals(sourceHistory, receiptState(f, source));
            }
            // then
            assertEquals(1, numberedApplications);
            assertEquals(2, appliedPositions.size());
            assertEquals(2L, appliedPositions.stream().distinct().count());
            assertEquals(target, appliedPositions.get(1));
            assertEquals(frozen, consumer.snapshot().valueAt("/child").blueId());
            var imported = receiptState(f, consumer);
            restart(f);
            assertEquals(imported, receiptState(f, consumer));
            assertEquals(frozen, consumer.snapshot().valueAt("/child").blueId());
            var live = f.blue.processing().processNext(consumer);
            assertEquals(EntryDisposition.APPLIED, live.entry(future).disposition());
            assertEquals(later, consumer.snapshot().valueAt("/child").blueId());
            assertEquals(numbered, receiptState(f, parent));
            assertEquals(sourceHistory, receiptState(f, source));
        }
    }

    private static SourceSetup threeSourceChanges(RootedSdkFixture f) throws Exception {
        String yaml = RootedSdkFixture.resource("source.yaml");
        var source = f.startYaml(yaml, "rcp2/source");
        f.exact.put(source.id().value(), f.blue.values().yaml(yaml).json());
        String first = null;
        for (int epoch = 1; epoch <= 3; epoch++) {
            var entry = f.append(source, "rcp2/source", "tick", epoch * 100L, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, entry).entry(entry).disposition());
            String retained = f.retain(source);
            if (epoch == 1) first = retained;
            assertEquals(epoch, source.snapshot().epoch());
        }
        return new SourceSetup(source, yaml, first);
    }

    private static List<Long> finishNumbered(RootedSdkFixture f, DocumentHandle parent, int selectedEpoch, int end)
            throws Exception {
        var epochs = new ArrayList<Long>();
        while (!ready(f, parent)) {
            assertTrue(epochs.size() < 8, "Bounded frozen source interval");
            var work = f.control.registeredOwnedHistory(parent.id());
            assertTrue(work.representationCause().isEmpty());
            assertTrue(work.successorRepresentationCause().isEmpty());
            assertEquals(Math.max(0, selectedEpoch + 1) + epochs.size(), work.sourceEpoch());
            assertFrozenEnd(f, parent, end);
            assertMaterializedNext(f, parent);
            epochs.add(work.sourceEpoch());
            assertEquals(expectedLog(selectedEpoch, (int) work.sourceEpoch()), log(latest(f, parent)),
                    "Each imported receipt delivers only its exact ordered source event");
        }
        return List.copyOf(epochs);
    }

    private static void assertMaterializedNext(RootedSdkFixture f, DocumentHandle root) {
        var work = f.control.registeredOwnedHistory(root.id());
        var input = f.control.captureRegisteredOwnedHistory(root.id());
        var sourceReceipt = f.blue.advanced().auditManagedEpoch(work.sourceDocumentId(), work.sourceEpoch()).orElseThrow();
        assertEquals(work.sourceReceiptIdentity(), sourceReceipt.receiptIdentity());
        // The input snapshot has the predecessor. The exact successor and its
        // event/request resources are authenticated by this one selected receipt,
        // not by a read through the materialized runtime into the current host.
        var causeValues = new ArrayList<blue.coordination.api.ExactValue>();
        causeValues.add(sourceReceipt.afterDocument().unwrap());
        sourceReceipt.sourceEntry().flatMap(TimelineEntrySnapshot::request)
                .ifPresent(request -> causeValues.add(request.unwrap()));
        sourceReceipt.emittedEvents().forEach(event -> causeValues.add(event.exactEvent().unwrap()));
        System.out.println("FULL_HISTORY_REFERENCE_CAUSE source=" + work.sourceDocumentId()
                + " epoch=" + work.sourceEpoch() + " after=" + sourceReceipt.afterBlueId()
                + " events=" + sourceReceipt.emittedEvents().stream().map(ManagedEventOccurrence::eventBlueId).toList()
                + " exactCauseValues=" + causeValues.stream().map(blue.coordination.api.ExactValue::blueId).toList());
        var reference = RootedCalculationFixture.materializedReference(input, List.copyOf(causeValues));
        assertTrue(reference.commits());
        var next = f.blue.processing().processNext(root);
        assertFalse(next.blocked());
        assertEquals(1, next.managedEpochApplicationAttempts().size());
        var actual = next.managedEpochApplicationAttempts().get(0).attempt().processResult();
        assertTrue(actual.commits());
        assertEquals(reference.totalGas(), actual.totalGas());
        assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
        assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
        var retained = f.control.retainedTerminals(root.id()).stream()
                .filter(terminal -> terminal.result().invocationIdentity().equals(actual.invocationIdentity())).toList();
        assertEquals(1, retained.size(), "The SDK attempt must identify one actual retained Contracts result");
        var contracts = retained.get(0).result();
        assertEquals(actual.outputClosureIdentity(), contracts.outputClosureIdentity());
        assertEquals(reference.publicEventsIdentity(), contracts.publicEventsIdentity());
        assertEquals(reference.managedTransitionReceiptsIdentity(), contracts.managedTransitionReceiptsIdentity());
    }

    private static void assertFrozenEnd(RootedSdkFixture f, DocumentHandle parent, long end) {
        var plans = f.blue.advanced().auditManagedCatchUpPlans(parent.id());
        assertEquals(1, plans.size());
        assertEquals(end, plans.get(0).requiredThroughSourceEpoch());
    }

    private static boolean ready(RootedSdkFixture f, DocumentHandle document) {
        return f.blue.advanced().auditManagedDocumentReadiness(document.id()).orElseThrow().ready();
    }

    private static ExactBlueValue latest(RootedSdkFixture f, DocumentHandle document) {
        var epochs = f.blue.advanced().auditManagedEpochs(document.id());
        return epochs.get(epochs.size() - 1).afterDocument();
    }

    private static List<Long> log(ExactBlueValue document) {
        var items = document.valueAt("/log").copyNode().getItems();
        assertNotNull(items);
        var values = new ArrayList<Long>();
        for (var item : items) values.add(((Number) item.getValue()).longValue());
        return List.copyOf(values);
    }

    private static List<Long> expectedLog(int selectedEpoch, int through) {
        var values = new ArrayList<Long>();
        for (long epoch = Math.max(1, selectedEpoch + 1); epoch <= through; epoch++) values.add(epoch);
        return List.copyOf(values);
    }

    private static List<List<Object>> receiptState(RootedSdkFixture f, DocumentHandle document) {
        return f.blue.advanced().auditManagedEpochs(document.id()).stream().map(receipt -> List.<Object>of(
                receipt.receiptIdentity(), receipt.documentId().value(), receipt.epoch(), receipt.kind(),
                receipt.beforeBlueId(), receipt.afterBlueId(), receipt.afterDocument().json(),
                receipt.originalCauseIdentity(), receipt.sourceOrder().map(SourceOrder::components),
                receipt.contractsTransitionReceiptIdentity(), receipt.commitCompanionIdentity(), receipt.processingGas(),
                receipt.sourceEntry().map(entry -> List.of(entry.exact().json(), entry.request().map(ExactBlueValue::json),
                        entry.previousEntryBlueId(), entry.operation(), entry.channel(), entry.timestampMicros(),
                        entry.globalSequence(), entry.timelineSequence())),
                receipt.emittedEvents().stream().map(event -> List.of(event.managedEventIdentity(), event.ordinal(),
                        event.eventOccurrenceOrdinal(), event.sourceDocumentId().value(), event.eventOccurrenceIdentity(),
                        event.eventBlueId(), event.exactEvent().json(), event.publicAtSource())).toList())).toList();
    }

    private static String parent(String child) throws Exception {
        return RootedSdkFixture.resource("parent.yaml") + "\nchild:\n" + child;
    }

    private static String reference(String blueId) { return "  blueId: " + blueId + "\n"; }
    private static void restart(RootedSdkFixture f) { CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores(); }
    private record SourceSetup(DocumentHandle source, String authoredYaml, String epochOne) { }
}
