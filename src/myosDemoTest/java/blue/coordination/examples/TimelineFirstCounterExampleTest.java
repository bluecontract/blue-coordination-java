package blue.coordination.examples;

import blue.coordination.examples.documents.BasicsCounterDocuments;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Required target-free API proof. */
final class TimelineFirstCounterExampleTest {

    @Test
    void shouldAppendToTheTimelineThenLetTheEnvironmentFindTheCounter() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "timeline-first-counter")) {
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
            var workBeforeProcess = demo.engineWorkSnapshot();

            // when
            MyOsDemoDispatch dispatch = demo.process(entry);
            MyOsDemoResult result = dispatch.onlyResult();
            var processWork = demo.engineWorkSnapshot()
                    .minus(workBeforeProcess);

            // then
            MyOsDemoAssertions.assertSuccessful(result);
            assertEquals(BigInteger.ONE, demo.value("counter", "/counter"));
            assertEquals(1, demo.journalEntryCount());
            assertEquals(1, demo.storedEventInventoryCount());
            assertEquals(0, dispatch.work().fullRootReconstructions());
            assertEquals(1, processWork.processCompletions());
        }
    }
}
