package blue.coordination.examples.support;

import blue.coordination.engine.memory.BoundedSingleFlightCache;
import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.examples.documents.OrderDocuments;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance proof for target-free append from a prepared operation shape. */
final class MyOsPreparedOperationAppendTest {

    @Test
    void shouldComposeThePreparedLargeRequestWithoutResolvingItAgain() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "prepared-operation-append", "large-paynote-entry")) {
            String payNoteKey = "prepared-operation-paynote";
            demo.addDocument(payNoteKey, OrderDocuments.PACKAGE_PAYNOTE);
            MyOsDemoTimeline timeline = demo.timeline(
                    "acceptance/prepared-operation/paynote/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = attachPayNoteOperation(
                    demo, payNoteKey);
            MyOsAppendTemplateMetrics.Snapshot beforePreparation =
                    MyOsDemoRuntime.appendTemplateMetrics();
            timeline.primeTemplate(operation);
            MyOsAppendTemplateMetrics.Snapshot preparation = minus(
                    MyOsDemoRuntime.appendTemplateMetrics(),
                    beforePreparation);
            assertEquals(1L, preparation.canonicalCompilations());

            long timestamp = demo.peekNextTimelineTimestampMicros();
            Node portable = demo.resolvedExactEvent(
                    timeline.eventYaml(operation, timestamp, null))
                    .canonicalRoot();
            String portableBlueId = demo.directBlueId(portable);
            MyOsAppendTemplateMetrics.Snapshot templateBeforeAppend =
                    MyOsDemoRuntime.appendTemplateMetrics();
            CoordinationEventAdmissionMetrics.Snapshot admissionBefore =
                    demo.eventAdmissionMetrics();

            // when
            MyOsDemoEntry appended = demo.append(timeline, operation);

            // then
            MyOsAppendTemplateMetrics.Snapshot append = minus(
                    MyOsDemoRuntime.appendTemplateMetrics(),
                    templateBeforeAppend);
            CoordinationEventAdmissionMetrics.Snapshot admission =
                    demo.eventAdmissionMetrics().minus(admissionBefore);
            assertEquals(0L, append.canonicalCompilations(),
                    "prepared append must perform no YAML resolution");
            assertEquals(1L, append.hits());
            assertEquals(1L, append.exactMaterializations(),
                    "one structurally shared prototype is composed");
            assertEquals(1L, append.patchedLeaves());
            assertEquals(1L, append.rootBlueIdCalculations());
            assertEquals(1L, admission.fullEventSplits());
            assertEquals(0L, admission.blueIdCalculations());
            assertEquals(NodeWireForm.get(portable),
                    NodeWireForm.get(appended.exactEntry()),
                    "prepared and fresh unprepared construction must be "
                            + "canonically identical");
            assertEquals(portableBlueId, appended.blueId());
            assertTrue(demo.authoredEntries().contains(appended));
            BoundedSingleFlightCache.Snapshot templateCache =
                    MyOsPreparedEntryTemplates.cacheMetrics();
            assertEquals(
                    64L * 1024L * 1024L,
                    templateCache.maximumWeight());
            assertTrue(templateCache.retainedWeight() > 0L);
            assertTrue(
                    templateCache.retainedWeight()
                            <= templateCache.maximumWeight());
        }
    }

    private static MyOsDemoOperation attachPayNoteOperation(
            MyOsDemoRuntime demo,
            String documentKey) {
        MyOsDemoDocument payNote = demo.document(documentKey);
        return MyOsDemoOperation.operation("attachPayNoteAsCustomer")
                .through("customerChannel")
                .request("""
                        document:
                        %s
                        documentRef:
                          blueId: %s
                        """.formatted(
                        MyOsDemoYaml.indent(
                                payNote.authoredYaml().stripTrailing(), 2),
                        payNote.initialBlueId()))
                .build();
    }

    private static MyOsAppendTemplateMetrics.Snapshot minus(
            MyOsAppendTemplateMetrics.Snapshot after,
            MyOsAppendTemplateMetrics.Snapshot before) {
        return new MyOsAppendTemplateMetrics.Snapshot(
                after.hits() - before.hits(),
                after.misses() - before.misses(),
                after.canonicalCompilations()
                        - before.canonicalCompilations(),
                after.exactMaterializations()
                        - before.exactMaterializations(),
                after.patchedLeaves() - before.patchedLeaves(),
                after.rootBlueIdCalculations()
                        - before.rootBlueIdCalculations());
    }
}
