package blue.coordination.internal;

import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecordAttempt;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.coordination.sdk.ActivationPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;

/** Actual processor receipts with native original-owner association; runtime lifecycle eligibility is separate. */
final class LogicalPublicationInstancesTest {
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});
    private static final int MAX = 32 * 1024 * 1024;

    @Test void originalAdmissionAndClosureValidateAfterTheirOwnerBindingHasBeenReplaced() throws Exception {
        // given
        var records = new LogicalRecordMapTest.Store();
        var objects = new DocumentSessionStorageTest.Bytes();
        try (var fixture = new DocumentSessionStorageTest.Fixture()) {
            var root = fixture.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            var admitted = fixture.engine.documents().storedState();
            var admission = admitted.admissionReceiptIndex().values().iterator().next();
            try (var tx = new Transaction(records.attempt(), objects)) {
                tx.publications.retain(Family.ADMISSION, admission.publicationIdentity(), admission.documentIds(), admitted.sessionIndex());
                assertTrue(records.publish(tx.prepare("admitted")));
            }
            fixture.process(root, fixture.append(root, "rcp2/source", "tick"));
            var processed = fixture.engine.documents().storedState();
            var receipt = processed.closurePublicationReceiptIndex().values().iterator().next();
            try (var tx = new Transaction(records.attempt(), objects)) {
                tx.publications.retain(Family.CLOSURE, receipt.publicationIdentity(), receipt.documentIds(), processed.sessionIndex());
                assertTrue(records.publish(tx.prepare("processed")));
            }
            // when
            try (var tx = new Transaction(records.attempt(), objects)) {
                var original = LogicalDocumentInstances.initialReference(root.id());
                tx.instances.retire(tx.instances.requireActive(original));
                tx.instances.start(new DocumentInstanceRef(root.id(), "replacement"));
                assertTrue(records.publish(tx.prepare("replace-binding")));
            }
            // then
            try (var tx = new Transaction(records.attempt(), objects)) {
                var initial = tx.publications.open(Family.ADMISSION, admission.publicationIdentity(), admission.documentIds());
                var old = tx.publications.open(Family.CLOSURE, receipt.publicationIdentity(), receipt.documentIds());
                assertEquals(0, initial.get(root.id()).epoch());
                assertEquals(1, old.get(root.id()).epoch());
                InMemoryDocumentStore.StoreState.requireRetainedResult(admission.documentIds(), admission.attempt().processResult(), initial, "old admission");
                InMemoryDocumentStore.StoreState.requireRetainedClosureReceipt(receipt, old, "old process receipt");
                var publication = tx.prepare("retained-reader");
                assertTrue(publication.points().stream().noneMatch(point ->
                        point.key().family() == Family.INSTANCE_BINDING || point.key().family() == Family.SESSION));
                assertTrue(records.publish(publication));
            }
        }
    }

    @Test void aMissingOriginalAssociationPoisonsTheOwnedAttempt() {
        // given
        var records = new LogicalRecordMapTest.Store();
        // when
        try (var tx = new Transaction(records.attempt(), new DocumentSessionStorageTest.Bytes())) {
            // then
            assertThrows(RuntimeException.class, () -> tx.publications.open(Family.ADMISSION, "unknown", List.of()));
            assertThrows(IllegalStateException.class, () -> tx.attempt.prepare("missing", List.of(), EVIDENCE));
        }
    }

    private static final class Transaction implements AutoCloseable {
        final CoordinationRecordAttempt attempt;
        final LogicalRecordContext context;
        final LogicalDocumentInstances instances;
        final DocumentSessionStorage.OpenScope views;
        final LogicalPublicationInstances publications;
        Transaction(CoordinationRecordAttempt attempt, DocumentSessionStorageTest.Bytes objects) {
            this.attempt = attempt; context = new LogicalRecordContext(attempt); instances = context.instances(MAX);
            views = new DocumentSessionStorage(objects, new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024)).openScope();
            var history = new LogicalInstanceHistory(context, instances, views, MAX);
            publications = new LogicalPublicationInstances(context, instances, history, views, MAX);
        }
        Publication prepare(String id) { context.flush(); return attempt.prepare(id, List.of(), EVIDENCE); }
        public void close() { views.close(); attempt.close(); }
    }
}
