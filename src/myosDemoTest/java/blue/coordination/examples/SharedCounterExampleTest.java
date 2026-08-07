package blue.coordination.examples;

import blue.coordination.examples.documents.SharedCounterDocuments;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** One immutable Timeline Entry, two independent authoritative Roots. */
final class SharedCounterExampleTest {

    @Test
    void shouldProcessOneCanonicalEntryIndependentlyInTwoSessions() {
        // given
        try (MyOsDemoRuntime demo =
                     MyOsDemoRuntime.create("shared-counter")) {
            demo.addDocument("counter-a", SharedCounterDocuments.COUNTER_A);
            demo.addDocument("counter-b", SharedCounterDocuments.COUNTER_B);
            MyOsDemoTimeline alice = demo.timeline(
                    "examples/shared-counter/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoEntry shared = demo.append(
                    alice,
                    MyOsDemoOperation.operation("increment")
                            .through("ownerChannel")
                            .request("""
                                    amount: 2
                                    """)
                            .build());

            // when
            List<MyOsDemoResult> results = demo.process(shared).deliveries();

            // then
            results.forEach(MyOsDemoAssertions::assertSuccessful);
            results.forEach(result -> {
                assertEquals(shared.blueId(), result.entry().blueId(),
                        "both deliveries must use the exact authored entry");
                MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                        demo, result);
                MyOsDemoAssertions.assertSelectedScopes(result, "/");
            });
            MyOsDemoAssertions.assertValue(demo, "counter-a", "/counter", 2);
            MyOsDemoAssertions.assertValue(demo, "counter-b", "/counter", 2);
            MyOsDemoAssertions.assertValue(
                    demo,
                    "counter-a",
                    "/contracts/checkpoint/entries/ownerChannel/subject/timestamp",
                    shared.timestampMicros());
            MyOsDemoAssertions.assertValue(
                    demo,
                    "counter-b",
                    "/contracts/checkpoint/entries/ownerChannel/subject/timestamp",
                    shared.timestampMicros());
            assertEquals(1, demo.authoredEntries().size(),
                    "the Timeline owns one immutable entry");
            assertEquals(1L, demo.currentEpoch("counter-a"));
            assertEquals(1L, demo.currentEpoch("counter-b"));
            assertNotEquals(
                    demo.currentRootBlueId("counter-a"),
                    demo.currentRootBlueId("counter-b"),
                    "equal delivery does not merge independent Root sessions");
        }
    }
}
