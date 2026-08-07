package blue.coordination.examples;

import blue.coordination.examples.documents.EmbeddedCounterDocuments;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Deep-child operation, ancestor reaction, and Root-only public output. */
final class EmbeddedCounterExampleTest {

    @Test
    void shouldProcessTheEmbeddedCounterAndLetTheParentObserveItsEvent() {
        // given
        try (MyOsDemoRuntime demo =
                     MyOsDemoRuntime.create("embedded-counter")) {
            demo.addDocument("counter", EmbeddedCounterDocuments.COUNTER);
            demo.addDocument(
                    "embedded-counter",
                    EmbeddedCounterDocuments.EMBEDDED_COUNTER);
            MyOsDemoTimeline alice = demo.timeline(
                    "examples/embedded-counter/alice",
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
            MyOsDemoDispatch dispatch = demo.process(entry);
            MyOsDemoResult result = dispatch.require("embedded-counter");

            // then
            MyOsDemoAssertions.assertSuccessful(result);
            assertEquals(Set.of("counter", "embedded-counter"),
                    dispatch.documentKeys());
            MyOsDemoAssertions.assertSuccessful(dispatch.require("counter"));
            MyOsDemoAssertions.assertValue(demo, "counter", "/counter", 1);
            MyOsDemoAssertions.assertSelectedScopes(result, "/counter");
            MyOsDemoAssertions.assertValue(
                    demo, "embedded-counter", "/counter/counter", 1);
            MyOsDemoAssertions.assertValue(
                    demo,
                    "embedded-counter",
                    "/lastEmbeddedEvent",
                    "Embedded counter incremented");
            assertEquals(
                    List.of("Parent observed embedded counter increment"),
                    result.delivery().transition().platformResult()
                            .processResult().events().stream()
                            .map(event -> demo.value(event, "/message"))
                            .toList(),
                    "only the Root-emitted parent observation is public");
        }
    }
}
