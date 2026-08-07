package blue.coordination.examples;

import blue.coordination.examples.documents.VetDocuments;
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

/** A single request and confirmation reused across legitimate document roots. */
final class VetVisitExampleTest {

    @Test
    void shouldRequestAndConfirmOnePuppsVisitAcrossSharedTimelines() {
        // given
        try (MyOsDemoRuntime demo =
                     MyOsDemoRuntime.create("vet-visit")) {
            demo.addDocument("vet-order", VetDocuments.VET_ORDER);
            demo.addDocument(
                    "vet-order-paynote", VetDocuments.VET_ORDER_PAYNOTE);
            demo.addDocument(
                    "vet-trainer-agreement",
                    VetDocuments.VET_TRAINER_AGREEMENT);
            demo.addDocument("pupps-order", VetDocuments.PUPPS_ORDER);
            MyOsDemoTimeline maya = demo.timeline(
                    "examples/vet/alice",
                    MyOsDemoActor.principal("alice"));
            MyOsDemoTimeline pupps = demo.timeline(
                    "examples/vet/celine",
                    MyOsDemoActor.principal("celine"));

            // when
            MyOsDemoEntry request = demo.append(
                    maya,
                    MyOsDemoOperation.operation("scheduleVisit")
                            .through("customerChannel")
                            .request("""
                                    preferredDate: "2026-08-03"
                                    preferredTime: "15:00"
                                    reason: Puppy training consultation
                                    """)
                            .build());
            MyOsDemoDispatch requestDispatch = demo.process(request);
            List<MyOsDemoResult> requestResults =
                    requestDispatch.deliveries();
            MyOsDemoEntry confirmation = demo.append(
                    pupps,
                    MyOsDemoOperation.operation("confirmVisit")
                            .through("trainerChannel")
                            .request("""
                                    date: "2026-08-03"
                                    time: "15:00"
                                    trainer: Alex
                                    notes: Bring vaccination records
                                    """)
                            .build());
            MyOsDemoDispatch confirmationDispatch =
                    demo.process(confirmation);
            List<MyOsDemoResult> confirmationResults =
                    confirmationDispatch.deliveries();

            // then
            requestResults.forEach(MyOsDemoAssertions::assertSuccessful);
            confirmationResults.forEach(MyOsDemoAssertions::assertSuccessful);
            assertEquals(
                    Set.of("pupps-order", "vet-order"),
                    requestDispatch.documentKeys(),
                    "request fanout roots");
            assertEquals(
                    Set.of(
                            "pupps-order",
                            "vet-order",
                            "vet-trainer-agreement"),
                    confirmationDispatch.documentKeys(),
                    "confirmation fanout roots");
            requestResults.forEach(result -> {
                assertEquals(request.blueId(), result.entry().blueId(),
                        "each request delivery must retain one exact entry");
                MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                        demo, result);
                MyOsDemoAssertions.assertSelectedScopes(result, "/");
            });
            confirmationResults.forEach(result -> {
                assertEquals(confirmation.blueId(), result.entry().blueId(),
                        "each confirmation delivery must retain one exact entry");
                MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                        demo, result);
                MyOsDemoAssertions.assertSelectedScopes(result, "/");
            });
            MyOsDemoAssertions.assertValue(
                    demo, "pupps-order", "/pendingVisit/status", "confirmed");
            MyOsDemoAssertions.assertValue(
                    demo,
                    "pupps-order",
                    "/pendingVisit/preferredDate",
                    "2026-08-03");
            MyOsDemoAssertions.assertValue(
                    demo,
                    "pupps-order",
                    "/pendingVisit/preferredTime",
                    "15:00");
            MyOsDemoAssertions.assertValue(
                    demo,
                    "pupps-order",
                    "/lastConfirmedVisit/status",
                    "confirmed");
            MyOsDemoAssertions.assertValue(
                    demo, "pupps-order", "/confirmedVisitCount", 1);
            MyOsDemoAssertions.assertValue(
                    demo, "vet-order", "/confirmedVisitCount", 1);
            MyOsDemoAssertions.assertValue(
                    demo,
                    "vet-trainer-agreement",
                    "/confirmedVisitCount",
                    1);
            assertConfirmedVisitDetails(demo, "pupps-order");
            assertConfirmedVisitDetails(demo, "vet-order");
            assertConfirmedVisitDetails(demo, "vet-trainer-agreement");
            assertEquals(4, demo.documentCount());
            assertEquals(2, demo.authoredEntries().size());
        }
    }

    private static void assertConfirmedVisitDetails(
            MyOsDemoRuntime demo,
            String documentKey) {
        MyOsDemoAssertions.assertValue(
                demo, documentKey, "/lastConfirmedVisit/date", "2026-08-03");
        MyOsDemoAssertions.assertValue(
                demo, documentKey, "/lastConfirmedVisit/time", "15:00");
        MyOsDemoAssertions.assertValue(
                demo, documentKey, "/lastConfirmedVisit/trainer", "Alex");
        MyOsDemoAssertions.assertValue(
                demo,
                documentKey,
                "/lastConfirmedVisit/notes",
                "Bring vaccination records");
    }
}
