package blue.coordination.examples.support;

import blue.coordination.engine.api.CoordinationEventShapeMetrics;
import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that first-seen exact entries do not invoke the full event splitter. */
final class MyOsShapeCompiledEventAdmissionTest {

    @Test
    void shouldKeepPreparedShapesInsideTheirEventAdmissionDomain() {
        try (MyOsDemoRuntime first = MyOsDemoRuntime.create(
                        "round4-shape-event", "domain-owner-a");
                MyOsDemoRuntime second = MyOsDemoRuntime.create(
                        "round4-shape-event", "domain-owner-b")) {
            MyOsDemoActor actor = MyOsDemoActor.principal("alice");
            MyOsDemoTimeline firstTimeline = first.timeline(
                    "round4/shape/shared/alice", actor);
            MyOsDemoTimeline secondTimeline = second.timeline(
                    "round4/shape/shared/alice", actor);
            MyOsDemoOperation operation = MyOsDemoOperation
                    .operation("increment")
                    .through("ownerChannel")
                    .request("amount: 1\n")
                    .build();

            firstTimeline.primeTemplate(operation);
            assertNotEquals(
                    first.eventAdmissionDomainIdentity(),
                    second.eventAdmissionDomainIdentity());
            MyOsWorkSnapshot before = second.work().snapshot();

            MyOsDemoEntry firstEntry = second.append(
                    secondTimeline, operation);
            MyOsDemoEntry secondEntry = second.append(
                    secondTimeline, operation);

            assertNotEquals(firstEntry.blueId(), secondEntry.blueId());
            assertEquals(2, second.journalEntryCount());
            assertEquals(2, second.canonicalStoredEventCount());
            MyOsWorkSnapshot delta = second.work().snapshot().minus(before);
            assertEquals(2L, delta.eventPreparations());
            assertEquals(0L, delta.eventSplits(),
                    "shape instances never invoke the full event splitter");
        }
    }

    @Test
    void shouldCompileOneShapeAndIncrementallyAdmitEveryExactEntry() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "round4-shape-event", "first-seen")) {
            MyOsDemoTimeline timeline = demo.timeline(
                    "round4/shape/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = MyOsDemoOperation
                    .operation("increment")
                    .through("ownerChannel")
                    .request("amount: 1\n")
                    .build();
            CoordinationEventAdmissionMetrics.Snapshot admissionBefore =
                    demo.eventAdmissionMetrics();
            CoordinationEventShapeMetrics.Snapshot shapeBefore =
                    demo.eventShapeMetrics();

            // when
            for (int index = 0; index < 32; index++) {
                MyOsDemoEntry entry = demo.append(timeline, operation);
                assertEquals(
                        demo.directBlueId(entry.exactEntry()),
                        entry.blueId(),
                        "full Language identity remains the test oracle");
            }

            // then
            CoordinationEventAdmissionMetrics.Snapshot admissionAfter =
                    demo.eventAdmissionMetrics();
            CoordinationEventShapeMetrics.Snapshot shapeAfter =
                    demo.eventShapeMetrics();
            assertTrue(
                    admissionAfter.fullEventSplits()
                            - admissionBefore.fullEventSplits() <= 2L,
                    "only no-prev and with-prev sentinel shapes may split");
            assertEquals(
                    32L,
                    shapeAfter.instancesCompiled()
                            - shapeBefore.instancesCompiled());
            assertEquals(
                    32L,
                    shapeAfter.exactGraphsMaterialized()
                            - shapeBefore.exactGraphsMaterialized());
            assertTrue(
                    shapeAfter.directFragmentsRehashed()
                            > shapeBefore.directFragmentsRehashed());
            assertTrue(
                    shapeAfter.staticFragmentsReused()
                            > shapeBefore.staticFragmentsReused());
            assertEquals(32, demo.journalEntryCount());
            assertEquals(32, demo.canonicalStoredEventCount());
        }
    }

    @Test
    void cachedWithPreviousShapeUsesOnlySentinelsAndCannotCrossContaminate() {
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "round4-shape-event", "no-cheating-isolation")) {
            MyOsDemoTimeline timeline = demo.timeline(
                    "round4/shape/no-cheating/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = MyOsDemoOperation
                    .operation("increment")
                    .through("ownerChannel")
                    .request("amount: 1\n")
                    .build();
            String previousA = demo.directBlueId(
                    new Node().value("unpublished-previous-a"));
            String previousB = demo.directBlueId(
                    new Node().value("unpublished-previous-b"));

            MyOsPreparedEntryTemplate templateA =
                    MyOsPreparedEntryTemplates.require(
                            demo, timeline, operation, previousA);
            MyOsPreparedEntryTemplate templateB =
                    MyOsPreparedEntryTemplates.require(
                            demo, timeline, operation, previousB);

            assertSame(templateA, templateB,
                    "previous identity is not part of a stable shape key");
            Node sentinel = templateA.sentinelPrototypeForAudit();
            assertEquals(
                    MyOsPreparedEntryTemplates
                            .prototypeTimestampMicrosForAudit(),
                    ((Number) NodePathEditor.getOrNull(
                            sentinel, "/timestamp").getValue()).longValue());
            String sentinelPrevious = NodePathEditor.getOrNull(
                    sentinel, "/prevEntry").getBlueId();
            assertEquals(
                    MyOsPreparedEntryTemplates
                            .prototypePreviousBlueIdForAudit(),
                    sentinelPrevious);
            assertNotEquals(previousA, sentinelPrevious);
            assertNotEquals(previousB, sentinelPrevious);

            PendingTimelineAppend pendingA = templateA.instantiate(
                    timeline, demo, operation, 41_001L, previousA);
            PendingTimelineAppend pendingB = templateB.instantiate(
                    timeline, demo, operation, 41_002L, previousB);
            Node exactA = pendingA.entry().exactEntry();
            Node exactB = pendingB.entry().exactEntry();

            assertExactVolatileValues(exactA, 41_001L, previousA);
            assertExactVolatileValues(exactB, 41_002L, previousB);
            assertNotEquals(
                    NodePathEditor.getOrNull(
                            exactA, "/prevEntry").getBlueId(),
                    NodePathEditor.getOrNull(
                            exactB, "/prevEntry").getBlueId());
            assertEquals(
                    demo.directBlueId(exactA),
                    pendingA.entry().blueId());
            assertEquals(
                    demo.directBlueId(exactB),
                    pendingB.entry().blueId());
            assertNotEquals(
                    pendingA.entry().blueId(),
                    pendingB.entry().blueId());
        }
    }

    private static void assertExactVolatileValues(
            Node exact,
            long timestamp,
            String previousBlueId) {
        assertEquals(
                timestamp,
                ((Number) NodePathEditor.getOrNull(
                        exact, "/timestamp").getValue()).longValue());
        assertEquals(
                previousBlueId,
                NodePathEditor.getOrNull(
                        exact, "/prevEntry").getBlueId());
    }
}
