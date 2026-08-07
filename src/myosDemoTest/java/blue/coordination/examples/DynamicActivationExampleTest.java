package blue.coordination.examples;

import blue.coordination.examples.documents.DynamicActivationDocuments;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Current Contracts semantics: activate after commit, never replay old entries. */
final class DynamicActivationExampleTest {

    @Test
    void shouldActivateTheChildOnlyForTheFirstLaterEligibleEntry() {
        // given
        try (MyOsDemoRuntime demo =
                     MyOsDemoRuntime.create("dynamic-activation")) {
            demo.addDocument(
                    "dynamic-activation",
                    DynamicActivationDocuments.DYNAMIC_ACTIVATION);
            MyOsDemoTimeline alice = demo.timeline(
                    "examples/dynamic-activation/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoTimeline bob = demo.timeline(
                    "examples/dynamic-activation/bob",
                    MyOsDemoActor.principal("bob"));

            // when
            MyOsDemoEntry rootOnlyEntry = demo.append(
                    alice,
                    MyOsDemoOperation.operation("increment")
                            .through("ownerChannel")
                            .request("""
                                    amount: 1
                                    """)
                            .build());
            MyOsDemoResult rootOnly = demo.process(
                    rootOnlyEntry).onlyResult();
            MyOsDemoResult activation = demo.process(
                    demo.append(
                            bob,
                            MyOsDemoOperation.operation("attachChild")
                                    .through("attacherChannel")
                                    .request("""
                                            child:
                                              name: Late-Activated Counter
                                              counter: 0
                                              creatingEventCount: 0
                                              activated: false
                                              contracts:
                                                ownerChannel:
                                                  description: Alice's channel becomes active for this scope only after embedding is committed
                                                  type: Coordination/Timeline Channel
                                                  timeline:
                                                    type: MyOS/MyOS Timeline
                                                    timelineId: examples/dynamic-activation/alice
                                                  actor:
                                                    type: MyOS/Principal Actor
                                                    accountId: alice
                                                increment:
                                                  description: Increment the activated child for later eligible entries
                                                  type: Coordination/Sequential Workflow Operation
                                                  channel: ownerChannel
                                                  request:
                                                    amount:
                                                      type: Integer
                                                  steps:
                                                    - name: Increment child
                                                      type: Coordination/Compute
                                                      do:
                                                        - $appendChange:
                                                            op: replace
                                                            path: /counter
                                                            val:
                                                              $add:
                                                                - $document: /counter
                                                                - $binding: event/message/request/amount
                                                        - $return: true
                                            """)
                                    .build())).onlyResult();
            MyOsDemoEntry firstLaterEntry = demo.append(
                    alice,
                    MyOsDemoOperation.operation("increment")
                            .through("ownerChannel")
                            .request("""
                                    amount: 1
                                    """)
                            .build());
            MyOsDemoResult later = demo.process(
                    firstLaterEntry).onlyResult();

            // then
            List.of(rootOnly, activation, later)
                    .forEach(MyOsDemoAssertions::assertSuccessful);
            List.of(rootOnly, activation, later).forEach(result ->
                    MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                            demo, result));
            MyOsDemoAssertions.assertValue(
                    demo,
                    "dynamic-activation",
                    "/contracts/embedded/paths/0",
                    "/child");
            MyOsDemoAssertions.assertSelectedScopes(rootOnly, "/");
            MyOsDemoAssertions.assertSelectedScopes(activation, "/");
            MyOsDemoAssertions.assertSelectedScopes(later, "/child", "/");
            MyOsDemoAssertions.assertValue(
                    demo, "dynamic-activation", "/counter", 2);
            MyOsDemoAssertions.assertValue(
                    demo, "dynamic-activation", "/child/counter", 1);
            MyOsDemoAssertions.assertValue(
                    demo,
                    "dynamic-activation",
                    "/child/creatingEventCount",
                    0);
            assertEquals(3L, demo.currentEpoch("dynamic-activation"));
            assertTrue(demo.environment().engine()
                    .session(demo.document("dynamic-activation").sessionId())
                    .subscriptions().occurrences().stream()
                    .anyMatch(item -> item.scopePath().equals("/child")
                            && item.channelKey().equals("ownerChannel")));
        }
    }
}
