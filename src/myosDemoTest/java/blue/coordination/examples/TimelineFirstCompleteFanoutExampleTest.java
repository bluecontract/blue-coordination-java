package blue.coordination.examples;

import blue.coordination.examples.documents.CompleteFanoutDocuments;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Proves operation names never change complete environment-owned fan-out. */
final class TimelineFirstCompleteFanoutExampleTest {

    private static final Set<String> ROOTS =
            Set.of("root-a", "root-b", "root-c");

    @Test
    void shouldSelectEveryMatchingRootForThreeUnrelatedOperationNames() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "timeline-first-complete-fanout")) {
            ROOTS.forEach(key -> demo.addDocument(
                    key, CompleteFanoutDocuments.ROOT));
            MyOsDemoTimeline alice = demo.timeline(
                    "examples/complete-fanout/alice",
                    MyOsDemoActor.principal("alice"));
            List<String> operations = List.of(
                    "authorizeAmount", "unrelatedAlpha", "unrelatedBeta");
            List<MyOsDemoDispatch> dispatches = new ArrayList<>();
            var engineBefore = demo.engineWorkSnapshot();

            // when
            for (String operation : operations) {
                dispatches.add(demo.process(demo.append(
                        alice,
                        MyOsDemoOperation.operation(operation)
                                .through("ownerChannel")
                                .request("amount: 1")
                                .build())));
            }

            // then
            assertEquals(3, dispatches.size());
            dispatches.forEach(dispatch -> {
                assertEquals(ROOTS, dispatch.documentKeys());
                assertEquals(3, dispatch.deliveries().size());
                dispatch.deliveries().forEach(result -> {
                    MyOsDemoAssertions.assertSuccessful(result);
                    MyOsDemoAssertions.assertSelectedScopes(result, "/");
                });
            });
            assertEquals(9L, demo.engineWorkSnapshot()
                    .minus(engineBefore).processCompletions());
            ROOTS.forEach(key ->
                    MyOsDemoAssertions.assertValue(demo, key, "/counter", 3));
            assertEquals(3, demo.journalEntryCount());
            assertEquals(3, demo.storedEventInventoryCount());
        }
    }
}
