package blue.coordination.basic;

import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Minimal two-actor Counter running through the optimized myOS environment. */
final class BasicCounterTest {
    private static final String COUNTER_KEY = "counter";

    @Test
    void aliceAddsThreeAndBobSubtractsOne() throws Exception {
        try (BasicTestMetrics metrics = BasicTestMetrics.start(
                "counter", "Basic Counter");
             BasicTestMetrics.MeasuredResource<MyOsDemoRuntime> runtime =
                     metrics.manage(
                             "11 environment close",
                             metrics.measure(
                                     "01 environment start",
                                     () -> MyOsDemoRuntime.create(
                                             "basic-counter")))) {
            MyOsDemoRuntime env = runtime.value();
            // given
            MyOsDemoTimeline alice = metrics.measure(
                    "02 add Alice timeline",
                    () -> env.timeline(
                            "examples/basic-counter/alice",
                            MyOsDemoActor.principal("alice")));

            MyOsDemoTimeline bob = metrics.measure(
                    "03 add Bob timeline",
                    () -> env.timeline(
                            "examples/basic-counter/bob",
                            MyOsDemoActor.principal("bob")));

            String counterDocument = metrics.measure(
                    "04 load Counter resource",
                    () -> BasicTestResources.read(
                            "examples/basic-counter.yaml"));

            metrics.measure(
                    "05 start Counter",
                    () -> env.addDocument(COUNTER_KEY, counterDocument));

            // when
            MyOsDemoEntry increment = metrics.measure(
                    "06 Alice append +3",
                    () -> env.append(
                            alice,
                            operation("increment", "aliceChannel", 3)));

            MyOsDemoResult incremented = metrics.measure(
                    "07 Alice PROCESS +3 (cold)",
                    () -> env.process(increment).onlyResult());

            MyOsDemoEntry decrement = metrics.measure(
                    "08 Bob append -1",
                    () -> env.append(
                            bob,
                            operation("decrement", "bobChannel", 1)));

            MyOsDemoResult decremented = metrics.measure(
                    "09 Bob PROCESS -1 (warm)",
                    () -> env.process(decrement).onlyResult());

            // then
            metrics.measure("10 verify counter == 2", () -> {
                MyOsDemoAssertions.assertSuccessful(incremented);
                MyOsDemoAssertions.assertSuccessful(decremented);
                MyOsDemoAssertions.assertValue(
                        env, COUNTER_KEY, "/counter", 2);
                assertEquals(2L, env.currentEpoch(COUNTER_KEY));
                assertEquals(2, env.authoredEntries().size());
            });
        }
    }

    private static MyOsDemoOperation operation(
            String name,
            String channel,
            int amount) {
        return MyOsDemoOperation.operation(name)
                .through(channel)
                .request("amount: " + amount)
                .build();
    }

}
