package blue.coordination.examples.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every incremental entry identity must equal frozen Language's full result. */
final class MyOsIncrementalEntryIdentityParityTest {
    @Test
    void shouldMatchTheAuthoritativeCalculatorAcrossTimelineHistory() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "round4-entry-identity", "parity")) {
            MyOsDemoTimeline timeline = demo.timeline(
                    "identity/parity",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoOperation operation = MyOsDemoOperation
                    .operation("increment")
                    .through("ownerChannel")
                    .request("amount: 1\n")
                    .build();

            // when
            MyOsDemoEntry[] entries = new MyOsDemoEntry[64];
            for (int index = 0; index < 64; index++) {
                entries[index] = demo.append(timeline, operation);
            }

            // then
            for (int index = 0; index < entries.length; index++) {
                assertEquals(
                        demo.directBlueId(entries[index].exactEntry()),
                        entries[index].blueId(),
                        "incremental identity diverged at entry " + index);
            }
        }
    }
}
