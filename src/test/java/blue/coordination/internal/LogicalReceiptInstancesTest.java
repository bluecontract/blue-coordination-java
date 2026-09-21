package blue.coordination.internal;

import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.coordination.sdk.ActivationPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real canonical receipts can be shared while numbered execution histories remain separate. */
final class LogicalReceiptInstancesTest {
    private static final Bytes EVIDENCE = new Bytes(new byte[] {1});
    private static final PersistentMapStorage.Limits LIMITS = RootedEngineStorageTest.LIMITS.indexes();

    @Test void replacementHistoryStartsIndependentlyAndReusesExactCanonicalReceiptBytes() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var root = f.start(DocumentSessionStorageTest.resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(root, f.append(root, "rcp2/source", "tick"));
            var actual = f.engine.documents().storedState().managedEpochReceipts();
            var zero = actual.exactEvidence(root.id(), 0); var one = actual.exactEvidence(root.id(), 1);
            var original = LogicalDocumentInstances.initialReference(root.id());
            var replacement = new DocumentInstanceRef(root.id(), "replacement-receipts");
            var records = new LogicalRecordMapTest.Store();
            var objects = new DocumentSessionStorageTest.Bytes();
            var indexes = new StoredManagedEpochIndexes(objects, LIMITS);
            try (var attempt = records.attempt()) {
                var context = new LogicalRecordContext(attempt); context.instances(LIMITS.valueBytes()).start(original);
                var store = indexes.openLogical(context).withReceipt(zero.receipt(), zero.transitionReceipt())
                        .withReceipt(one.receipt(), one.transitionReceipt());
                indexes.selectLogical(store); context.flush();
                assertTrue(records.publish(attempt.prepare("a1-history", List.of(), EVIDENCE)));
            }
            var canonical = new HashMap<Key, Value>();
            records.data.forEach((key, value) -> { if (key.family() == Family.RECEIPT_IDENTITY && key.scope().equals(LogicalRecordContext.runtimeScope())) canonical.put(key, value); });
            assertEquals(2, canonical.size());
            Publication oldReader;
            try (var attempt = records.attempt()) {
                var context = new LogicalRecordContext(attempt);
                var history = indexes.retainedLogicalHistory(context, original);
                assertEquals(one.receipt().receiptIdentity(), history.receipt(1).publicReceipt().receiptIdentity());
                oldReader = attempt.prepare("old-numbered-receipt", List.of(), EVIDENCE);
                assertTrue(oldReader.points().stream().noneMatch(point -> point.key().family() == Family.INSTANCE_BINDING));
                var headScope = new Bytes(OrderedRecordKey.text().encode("runtime/2/receipt-heads"));
                assertTrue(oldReader.points().stream().noneMatch(point -> point.key().scope().equals(headScope)),
                        "Exact numbered reads cannot acquire mutable head dependencies");
            }
            // when
            try (var attempt = records.attempt()) {
                var context = new LogicalRecordContext(attempt); var ledger = context.instances(LIMITS.valueBytes());
                ledger.retire(ledger.requireActive(original)); ledger.start(replacement);
                var store = indexes.openLogical(context);
                // then
                assertEquals(-1, store.latestEpoch(root.id()));
                store = store.withReceipt(zero.receipt(), zero.transitionReceipt());
                assertEquals(0, store.latestEpoch(root.id()));
                assertFalse(store.exact(root.id(), 1).found(), "A2 must not inherit A1's completed receipt progress");
                indexes.selectLogical(store); context.flush();
                assertTrue(records.publish(attempt.prepare("a2-basis", List.of(), EVIDENCE)));
            }
            assertTrue(records.publish(oldReader));
            try (var attempt = records.attempt()) {
                var context = new LogicalRecordContext(attempt); var store = indexes.openLogical(context);
                assertEquals(0, store.latestEpoch(root.id()));
                assertEquals(1, indexes.retainedLogicalHistory(context, original).latestEpoch());
                store = store.withReceipt(one.receipt(), one.transitionReceipt());
                assertEquals(1, store.latestEpoch(root.id())); indexes.selectLogical(store); context.flush();
                assertTrue(records.publish(attempt.prepare("a2-same-canonical-receipt", List.of(), EVIDENCE)));
            }
            canonical.forEach((key, value) -> assertEquals(value, records.data.get(key),
                    "Canonical identity/content remains one immutable shared row, with no host instance in the receipt"));
            try (var attempt = records.attempt()) {
                var context = new LogicalRecordContext(attempt);
                assertEquals(one.receipt().receiptIdentity(), indexes.retainedLogicalHistory(context, original).receipt(1).publicReceipt().receiptIdentity());
                assertEquals(one.receipt().receiptIdentity(), indexes.retainedLogicalHistory(context, replacement).receipt(1).publicReceipt().receiptIdentity());
            }
        }
    }
}
