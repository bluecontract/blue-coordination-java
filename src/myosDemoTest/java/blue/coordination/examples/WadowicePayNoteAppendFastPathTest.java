package blue.coordination.examples;

import blue.coordination.engine.api.CoordinationEventShapeMetrics;
import blue.coordination.examples.scenarios.WadowiceHotelDinnerScenario;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsWorkSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exact acceptance proof for the formerly 1.355-second PayNote append. */
final class WadowicePayNoteAppendFastPathTest {

    @Test
    void shouldAdmitTheFirstSeenPayNoteFromItsCachedShape() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     WadowiceHotelDinnerScenario.create(
                             "paynote-append-first-seen")) {
            long splitsBefore = scenario.demo()
                    .eventAdmissionMetrics().fullEventSplits();
            MyOsWorkSnapshot workBefore = scenario.demo().work().snapshot();
            CoordinationEventShapeMetrics.Snapshot shapeBefore =
                    scenario.demo().eventShapeMetrics();
            scenario.demo().labelNextOperationTimingSample(
                    "firstSeenExactEvent");

            // when
            MyOsDemoEntry entry = scenario.appendPayNoteEntry();
            MyOsDemoDispatch dispatch = scenario.demo().process(entry);

            // then
            assertEquals(Set.of(
                    WadowiceHotelDinnerScenario.ORDER,
                    WadowiceHotelDinnerScenario.PAYNOTE),
                    dispatch.documentKeys());
            dispatch.deliveries().forEach(
                    MyOsDemoAssertions::assertSuccessful);
            assertEquals(1, scenario.demo().journalEntryCount());
            assertEquals(1, scenario.demo().canonicalStoredEventCount());
            assertEquals(splitsBefore,
                    scenario.demo().eventAdmissionMetrics()
                            .fullEventSplits(),
                    "cached-shape exact admission must not split the event");
            assertEquals(0L, scenario.demo().eventAdmissionMetrics()
                    .winnerReadBacks());
            assertEquals(0L, scenario.demo().work().snapshot()
                    .minus(workBefore).eventSplits());
            assertOneCachedShapeInstance(
                    shapeBefore,
                    scenario.demo().eventShapeMetrics());
        }
    }

    @Test
    void shouldReuseTheCachedShapeForAnExplicitlyPrimedPayNote() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     WadowiceHotelDinnerScenario.create(
                             "paynote-append-primed")) {
            scenario.primePayNoteAppend();
            long splitsBefore = scenario.demo()
                    .eventAdmissionMetrics().fullEventSplits();
            MyOsWorkSnapshot workBefore = scenario.demo().work().snapshot();
            CoordinationEventShapeMetrics.Snapshot shapeBefore =
                    scenario.demo().eventShapeMetrics();
            scenario.demo().labelNextOperationTimingSample("primed");

            // when
            MyOsDemoEntry entry = scenario.appendPayNoteEntry();
            MyOsDemoDispatch dispatch = scenario.demo().process(entry);

            // then
            assertEquals(Set.of(
                    WadowiceHotelDinnerScenario.ORDER,
                    WadowiceHotelDinnerScenario.PAYNOTE),
                    dispatch.documentKeys());
            dispatch.deliveries().forEach(
                    MyOsDemoAssertions::assertSuccessful);
            assertEquals(splitsBefore,
                    scenario.demo().eventAdmissionMetrics()
                            .fullEventSplits());
            assertTrue(scenario.demo().eventAdmissionMetrics()
                    .templateHits() >= 1L);
            assertEquals(0L, scenario.demo().work().snapshot()
                    .minus(workBefore).eventSplits());
            assertOneCachedShapeInstance(
                    shapeBefore,
                    scenario.demo().eventShapeMetrics());
        }
    }

    @Test
    @Tag("performance")
    void shouldKeepTheFirstSeenPayNoteAppendBelowTwoHundredFiftyMilliseconds() {
        assumeTrue(Boolean.getBoolean("coordination.performance.gates"));
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     WadowiceHotelDinnerScenario.create(
                             "paynote-append-first-seen-budget")) {
            long splitsBefore = scenario.demo()
                    .eventAdmissionMetrics().fullEventSplits();
            CoordinationEventShapeMetrics.Snapshot shapeBefore =
                    scenario.demo().eventShapeMetrics();
            scenario.demo().labelNextOperationTimingSample(
                    "firstSeenExactEvent");

            // when
            assertTimeout(
                    Duration.ofMillis(250),
                    scenario::appendPayNoteEntry);

            // then
            assertEquals(splitsBefore,
                    scenario.demo().eventAdmissionMetrics()
                            .fullEventSplits());
            assertOneCachedShapeInstance(
                    shapeBefore,
                    scenario.demo().eventShapeMetrics());
        }
    }

    @Test
    @Tag("performance")
    void shouldKeepAnExplicitlyPrimedPayNoteAppendBelowOneHundredMilliseconds() {
        assumeTrue(Boolean.getBoolean("coordination.performance.gates"));
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     WadowiceHotelDinnerScenario.create(
                             "paynote-append-primed-budget")) {
            scenario.primePayNoteAppend();
            long splitsBefore = scenario.demo()
                    .eventAdmissionMetrics().fullEventSplits();
            CoordinationEventShapeMetrics.Snapshot shapeBefore =
                    scenario.demo().eventShapeMetrics();
            scenario.demo().labelNextOperationTimingSample("primed");

            // when
            assertTimeout(
                    Duration.ofMillis(100),
                    scenario::appendPayNoteEntry);

            // then
            assertEquals(splitsBefore,
                    scenario.demo().eventAdmissionMetrics()
                            .fullEventSplits());
            assertOneCachedShapeInstance(
                    shapeBefore,
                    scenario.demo().eventShapeMetrics());
        }
    }

    private static void assertOneCachedShapeInstance(
            CoordinationEventShapeMetrics.Snapshot before,
            CoordinationEventShapeMetrics.Snapshot after) {
        assertEquals(0L,
                after.templatesCompiled() - before.templatesCompiled(),
                "the operation shape must already be cached");
        assertEquals(1L,
                after.instancesCompiled() - before.instancesCompiled(),
                "compile exactly one exact event instance");
        assertEquals(1L,
                after.exactGraphsMaterialized()
                        - before.exactGraphsMaterialized(),
                "materialize exactly one exact event graph");
        assertTrue(after.directFragmentsRehashed()
                        > before.directFragmentsRehashed(),
                "the volatile path spine must be rehashed");
        assertTrue(after.staticFragmentsReused()
                        > before.staticFragmentsReused(),
                "static event fragments must be reused");
        assertEquals(0L,
                after.fullSplitterOracleRuns()
                        - before.fullSplitterOracleRuns());
        assertEquals(0L,
                after.oracleFailures() - before.oracleFailures());
    }
}
