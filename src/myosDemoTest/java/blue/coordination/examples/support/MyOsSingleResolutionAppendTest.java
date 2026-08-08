package blue.coordination.examples.support;

import blue.coordination.engine.api.CoordinationEventShapeMetrics;
import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.examples.documents.OrderDocuments;
import blue.language.model.NodeWireForm;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Acceptance proof for the single-resolution Timeline append boundary. */
final class MyOsSingleResolutionAppendTest {

    @Test
    void shouldResolveANormalEntryOnceAndReuseItsResolvedHeaderIdentities() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "single-resolution-append", "normal-entry")) {
            MyOsDemoTimeline timeline = demo.timeline(
                    "acceptance/single-resolution/normal/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = MyOsDemoOperation
                    .operation("increment")
                    .through("ownerChannel")
                    .request("amount: 1")
                    .build();

            // when
            assertSingleResolutionAppend(demo, timeline, operation);

            // then
            // The shared oracle verifies identity, work, and exact wire parity.
        }
    }

    @Test
    void shouldResolveTheLargePayNoteEntryOnceAndReuseItsResolvedHeaders() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "single-resolution-append", "large-paynote-entry")) {
            demo.addDocument("acceptance-paynote",
                    OrderDocuments.PACKAGE_PAYNOTE);
            MyOsDemoTimeline timeline = demo.timeline(
                    "acceptance/single-resolution/paynote/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = attachPayNoteOperation(
                    demo, "acceptance-paynote");

            assertSingleResolutionAppend(demo, timeline, operation);
        }
    }

    private static void assertSingleResolutionAppend(
            MyOsDemoRuntime demo,
            MyOsDemoTimeline timeline,
            MyOsDemoOperation operation) {
        long timestamp = demo.peekNextTimelineTimestampMicros();
        ResolvedSnapshot portable = demo.resolvedExactEvent(
                timeline.eventYaml(operation, timestamp, null));
        MyOsTimelineBinding expectedBinding = new MyOsTimelineBinding(
                requiredBlueId(portable, "/timeline"),
                requiredBlueId(portable, "/actor"));
        MyOsAppendTemplateMetrics.Snapshot templateBefore =
                MyOsDemoRuntime.appendTemplateMetrics();
        CoordinationEventAdmissionMetrics.Snapshot admissionBefore =
                demo.eventAdmissionMetrics();
        CoordinationEventShapeMetrics.Snapshot shapeBefore =
                demo.eventShapeMetrics();
        MyOsWorkSnapshot workBefore = demo.work().snapshot();

        // when
        MyOsDemoEntry appended = demo.append(timeline, operation);

        // then
        MyOsAppendTemplateMetrics.Snapshot template = minus(
                MyOsDemoRuntime.appendTemplateMetrics(), templateBefore);
        CoordinationEventAdmissionMetrics.Snapshot admission =
                demo.eventAdmissionMetrics().minus(admissionBefore);
        CoordinationEventShapeMetrics.Snapshot shapeAfter =
                demo.eventShapeMetrics();
        MyOsWorkSnapshot work = demo.work().snapshot().minus(workBefore);
        assertEquals(1L, template.canonicalCompilations(),
                "one template compilation is the parse/preprocess/resolve "
                        + "pipeline for the unprepared entry");
        assertEquals(1L, template.exactMaterializations());
        assertEquals(1L, template.rootBlueIdCalculations(),
                "the event identity is calculated once after composition");
        assertEquals(1L, admission.fullEventSplits(),
                "the unprepared operation compiles one authoritative "
                        + "prototype shape");
        assertEquals(1L, admission.blueIdCalculations(),
                "prototype compilation calculates its canonical Root once");
        assertEquals(0L, work.eventSplits(),
                "the exact shape instance must be admitted without a full "
                        + "event split");
        assertEquals(1L,
                shapeAfter.templatesCompiled()
                        - shapeBefore.templatesCompiled());
        assertEquals(1L,
                shapeAfter.instancesCompiled()
                        - shapeBefore.instancesCompiled());
        assertEquals(1L,
                shapeAfter.exactGraphsMaterialized()
                        - shapeBefore.exactGraphsMaterialized());
        assertEquals(0L,
                shapeAfter.fullSplitterOracleRuns()
                        - shapeBefore.fullSplitterOracleRuns());
        assertEquals(0L,
                shapeAfter.oracleFailures() - shapeBefore.oracleFailures());
        assertEquals(expectedBinding, appended.binding(),
                "timeline and actor identities must come from that snapshot");
        assertEquals(expectedBinding, timeline.binding());
        assertEquals(NodeWireForm.get(portable.canonicalRoot()),
                NodeWireForm.get(appended.exactEntry()));
        assertEquals(demo.directBlueId(portable.canonicalRoot()),
                appended.blueId());
    }

    private static String requiredBlueId(
            ResolvedSnapshot snapshot,
            String path) {
        FrozenNode selected = snapshot.resolvedAt(path);
        if (selected == null) {
            throw new AssertionError("Resolved entry lacks " + path);
        }
        return selected.blueId();
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
