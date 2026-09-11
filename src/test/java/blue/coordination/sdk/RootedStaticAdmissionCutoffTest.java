package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A FROM_NOW admission reuses the source position already committed at its activation entry. */
final class RootedStaticAdmissionCutoffTest {
    @Test void inlineSourceAtAdmissionFrontierKeepsItsExistingHistory() throws Exception {
        // given
        boolean reference = false;
        // when
        org.junit.jupiter.api.function.Executable scenario = () -> reuse(reference);
        // then
        assertDoesNotThrow(scenario);
    }

    @Test void referencedSourceAtAdmissionFrontierKeepsItsExistingHistory() throws Exception {
        // given
        boolean reference = true;
        // when
        org.junit.jupiter.api.function.Executable scenario = () -> reuse(reference);
        // then
        assertDoesNotThrow(scenario);
    }

    @Test void laterNumberedPublicationCannotExtendAdmissionInterval() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            String yaml = RootedSdkFixture.resource("source.yaml");
            var source = f.startYaml(yaml, "rcp2/source");
            var first = f.append(source, "rcp2/source", "setCounter", 10L, "counterValue: 3");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, first).entry(first).disposition());
            var parent = f.blue.documents().admitStaticProcessEmbedded(observer(yaml),
                    ActivationPolicy.fromNow()).document("root");
            assertEquals(1L, f.blue.advanced().auditManagedCatchUpPlans(parent.id()).get(0).requiredThroughSourceEpoch());
            var future = f.append(source, "rcp2/source", "setCounter", 20L, "counterValue: 9");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, future).entry(future).disposition());
            var sourceHistory = f.history(source);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            // when
            finishHistory(f, parent);
            // then
            assertEquals(3L, ((Number) f.selected(parent, "/child").scalarAt("/counter")).longValue());
            assertEquals(9L, source.snapshot().longAt("/counter"));
            assertEquals(sourceHistory, f.history(source));
            assertEquals(1L, f.blue.advanced().auditManagedCatchUpPlans(parent.id()).get(0).requiredThroughSourceEpoch());
        }
    }

    @Test void sameEpochAdmissionPositionDoesNotChaseLaterRepresentation() throws Exception {
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
            String parentYaml = RootedSdkFixture.resource("parent.yaml")
                    + "\nchild: {blueId: " + f.retain(source) + "}\n";
            var parent = f.startYaml(parentYaml, "rcp2/parent");
            String initial = parent.snapshot().blueId();
            var first = f.append(source, "rcp2/source", "emitUnmatched", 100L, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(parent, first).entry(first).disposition());
            String frozen = parent.snapshot().blueId();
            assertNotEquals(initial, frozen);
            assertEquals(0L, parent.snapshot().epoch());
            var parentHistory = f.history(parent);
            // Static admission requires the same actual durable forward heads; materializing S
            // does not grant authority to replace P's independently retained checkpoint position.
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, first).entry(first).disposition());
            var consumer = f.blue.documents().admitStaticProcessEmbedded(observer(parentYaml),
                    ActivationPolicy.fromNow()).document("root");
            var firstWork = f.control.registeredOwnedHistory(consumer.id());
            assertEquals(0L, firstWork.sourceEpoch());
            assertTrue(firstWork.successorRepresentationCause().isPresent());
            String target = firstWork.successorRepresentationCause().orElseThrow().targetPositionIdentity();

            var future = f.append(source, "rcp2/source", "emitUnmatched", 200L, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(parent, future).entry(future).disposition());
            String later = parent.snapshot().blueId();
            assertNotEquals(frozen, later);
            assertEquals(0L, parent.snapshot().epoch());
            assertEquals(parentHistory, f.history(parent));
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(target, f.control.registeredOwnedHistory(consumer.id()).successorRepresentationCause()
                    .orElseThrow().targetPositionIdentity());
            // when
            finishHistory(f, consumer);
            // then
            assertEquals(frozen, consumer.snapshot().valueAt("/child").blueId());
            assertEquals(later, parent.snapshot().blueId());
            assertEquals(parentHistory, f.history(parent));
            var consumerHistory = f.history(consumer);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(consumerHistory, f.history(consumer));
            assertEquals(frozen, consumer.snapshot().valueAt("/child").blueId());
        }
    }

    private static String observer(String child) {
        return "name: Later static observer\nchild:\n" + child.indent(2)
                + "contracts:\n  embedded:\n    type: Process Embedded\n    paths: [/child]\n";
    }

    private static void finishHistory(RootedSdkFixture f, DocumentHandle document) {
        int applications = 0;
        while (!f.blue.advanced().auditManagedDocumentReadiness(document.id()).orElseThrow().ready()) {
            assertTrue(applications++ < 8, "Static source interval must terminate");
            var input = f.control.captureRegisteredOwnedHistory(document.id());
            var reference = RootedCalculationFixture.materializedReference(input);
            assertTrue(reference.commits());
            var drained = f.blue.processing().processNext(document);
            assertFalse(drained.blocked());
            assertEquals(1, drained.managedEpochApplicationAttempts().size());
            var actual = drained.managedEpochApplicationAttempts().get(0).attempt().processResult();
            assertTrue(actual.commits());
            assertEquals(reference.totalGas(), actual.totalGas());
            assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
            assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
        }
        assertTrue(applications > 0);
    }

    private static void reuse(boolean reference) throws Exception {
        try (var f = new RootedSdkFixture()) {
            String sourceYaml = RootedSdkFixture.resource("source.yaml");
            f.timelines.put("rcp2/source", f.blue.timelines().register("rcp2/source", "alice"));
            var source = f.blue.documents().admitStaticProcessEmbedded(sourceYaml,
                    ActivationPolicy.fromNow()).document("root");
            f.exact.put(source.id().value(), f.blue.values().yaml(sourceYaml).json());
            var entry = f.append(source, "rcp2/source", "setCounter", 10L, "counterValue: 3");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(source, entry).entry(entry).disposition());
            var sourceBefore = source.snapshot().exact().json();
            var historyBefore = f.history(source);
            var parent = f.blue.documents().admitStaticProcessEmbedded("name: Later static observer\nchild:\n"
                    + (reference ? "  blueId: " + source.id().value() + "\n" : sourceYaml.indent(2))
                    + "contracts:\n  embedded:\n    type: Process Embedded\n    paths: [/child]\n",
                    ActivationPolicy.fromNow()).document("root");
            // This admission's exact frontier is the already-applied source entry, not a later input.
            var basis = f.control.historyBasis(parent.id());
            assertTrue(basis.toString().contains(entry.blueId()));
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            var drained = f.blue.processing().drain();
            assertFalse(drained.blocked());
            assertEquals(3L, ((Number) f.selected(parent, "/child").scalarAt("/counter")).longValue());
            assertEquals(sourceBefore, source.snapshot().exact().json());
            assertEquals(historyBefore, f.history(source));
            assertEquals(List.of(0L, 1L), f.blue.advanced().auditManagedEpochs(source.id()).stream()
                    .map(ManagedEpochReceipt::epoch).toList());
            assertFalse(drained.managedEpochApplications().isEmpty());
            var parentHistory = f.history(parent);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(parentHistory, f.history(parent));
            assertEquals(historyBefore, f.history(source));
            assertTrue(f.blue.processing().drain().managedEpochApplications().isEmpty());
        }
    }
}
