package blue.coordination.examples;

import blue.coordination.examples.documents.BasicsCounterDocuments;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Smallest complete demonstration of Timeline-to-Root processing. */
final class CounterBasicsExampleTest {

    @Test
    void shouldIncrementOneCounterThroughOneExactTimelineEntry() {
        // given
        try (MyOsDemoRuntime demo =
                     MyOsDemoRuntime.create("counter-basics")) {
            demo.addDocument("counter", BasicsCounterDocuments.COUNTER);
            MyOsDemoTimeline alice = demo.timeline(
                    "examples/basics-counter/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoEntry entry = demo.append(
                    alice,
                    MyOsDemoOperation.operation("increment")
                            .through("ownerChannel")
                            .request("""
                                    amount: 1
                                    """)
                            .build());

            // when
            MyOsDemoResult result = demo.process(entry).onlyResult();

            // then
            MyOsDemoAssertions.assertSuccessful(result);
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    demo, result);
            MyOsDemoAssertions.assertSelectedScopes(result, "/");
            MyOsDemoAssertions.assertValue(demo, "counter", "/counter", 1);
            assertEquals(1L, demo.currentEpoch("counter"));
            assertEquals(1, demo.authoredEntries().size());
        }
    }
}
