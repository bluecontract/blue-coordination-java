package blue.coordination.examples.support;

import blue.coordination.examples.documents.BasicsCounterDocuments;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TimelineCanonicalAppendTest {

    @Test
    void shouldLeaveTimelineAndJournalUnchangedWhenAdmissionFails() {
        // given
        MyOsDemoOperation increment = increment();
        MyOsDemoEntry retried;
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "timeline-append-atomicity")) {
            MyOsDemoTimeline alice = alice(demo);
            demo.failNextEventAdmissionForTest(
                    new InjectedAdmissionFailure());

            // when
            assertThrows(
                    InjectedAdmissionFailure.class,
                    () -> demo.append(alice, increment));

            // then
            assertEquals(0, demo.journalEntryCount());
            assertEquals(0, demo.storedEventInventoryCount());
            assertEquals(0, demo.authoredEntries().size());

            retried = demo.append(alice, increment);
            assertEquals(1, demo.journalEntryCount());
            assertEquals(1, demo.storedEventInventoryCount());
        }

        try (MyOsDemoRuntime fresh = MyOsDemoRuntime.create(
                "timeline-append-fresh")) {
            MyOsDemoEntry firstAttempt = fresh.append(
                    alice(fresh), increment);
            assertEquals(firstAttempt.timestampMicros(),
                    retried.timestampMicros());
            assertEquals(firstAttempt.blueId(), retried.blueId());
        }
    }

    @Test
    void shouldUseCanonicalJournalMetadataInsteadOfForgedRecordFields() {
        // given
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "timeline-canonical-metadata")) {
            demo.addDocument("counter", BasicsCounterDocuments.COUNTER);
            MyOsDemoEntry canonical = demo.append(alice(demo), increment());
            MyOsDemoEntry forged = new MyOsDemoEntry(
                    canonical.exactEntry(),
                    canonical.blueId(),
                    ExternalOrderKey.of(Arrays.<Object>asList(
                            BigInteger.TEN, "forged")),
                    canonical.binding(),
                    "examples/forged",
                    "mallory",
                    "wrong-source",
                    "wrong-operation",
                    "wrong-handler",
                    canonical.timestampMicros());

            // when
            MyOsDemoDispatch dispatch = demo.process(forged);

            // then
            assertSame(canonical, dispatch.entry());
            assertEquals(BigInteger.ONE, demo.value("counter", "/counter"));
        }
    }

    @Test
    void shouldNotExposeTimelineMutationAsPublicApi() {
        // given
        java.lang.reflect.Method[] publicMethods =
                MyOsDemoTimeline.class.getMethods();

        // when
        boolean exposesAppend = Arrays.stream(publicMethods)
                .anyMatch(method -> method.getName().equals("append"));

        // then
        assertFalse(exposesAppend);
    }

    private static MyOsDemoTimeline alice(MyOsDemoRuntime demo) {
        return demo.timeline(
                "examples/basics-counter/alice",
                MyOsDemoActor.principal("alice"));
    }

    private static MyOsDemoOperation increment() {
        return MyOsDemoOperation.operation("increment")
                .through("ownerChannel")
                .request("""
                        amount: 1
                        """)
                .build();
    }

    private static final class InjectedAdmissionFailure
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
