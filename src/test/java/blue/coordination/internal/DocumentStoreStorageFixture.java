package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Inspects an actual SDK-owned StoreState; does not replace its external captured cohort/control families. */
public final class DocumentStoreStorageFixture {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits SESSIONS = new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final PersistentMapStorage.Limits MAPS = new PersistentMapStorage.Limits(40 * 1024 * 1024, 4096, MAX, 4096, 16);
    private static final PersistentAppendLogStorage.Limits LOGS = new PersistentAppendLogStorage.Limits(40 * 1024 * 1024, MAX, 4096, 8);
    private final DefaultCoordinationEngine engine;
    public DocumentStoreStorageFixture(CoordinationEngine engine) { this.engine = (DefaultCoordinationEngine) engine; }

    public int verifyCurrent(boolean rejectMissingMembership) {
        var original = engine.documents().storedState(); var bytes = new DocumentSessionStorageTest.Bytes();
        var storage = new StoredDocumentStore(bytes, MAPS, LOGS, SESSIONS); var selected = storage.retainPartition(original);
        var raw = original.catchUpPlans().storedState(); var codec = new ManagedApplicationStorageCodec(MAX, 256);
        var planCodec = new StoredCatchUpPlanIndexes(bytes, MAPS); var workCodec = new ManagedWorkStorageCodec(MAX, 256);
        var phases = engine.metricsSnapshot().phaseNanos();
        try (var cold = new StoredDocumentStore(bytes.copy(), MAPS, LOGS, SESSIONS).open(selected, 8, 256)) {
            var current = cold.state().catchUpPlans().storedState();
            for (var entry : raw.plans().storedIndexes().identities().entries())
                assertArrayEquals(planCodec.planCodec.encode(entry.getValue()), planCodec.planCodec.encode(current.plans().exact(entry.getKey()).plan()));
            for (var entry : raw.barriers().entries()) assertArrayEquals(planCodec.barrierCodec.encode(entry.getValue()), planCodec.barrierCodec.encode(current.barriers().get(entry.getKey())));
            for (var entry : raw.work().storedIndexes().work().entries())
                assertArrayEquals(workCodec.encode(entry.getValue().work()), workCodec.encode(current.work().work(entry.getKey()).work()));
            for (var entry : raw.work().storedIndexes().applications().entries()) {
                var row = current.work().storedIndexes().applications().get(entry.getKey());
                assertArrayEquals(codec.encode(entry.getValue().receipt(), entry.getValue().work()), codec.encode(row.receipt(), row.work()));
                assertSame(current.work(), current.work().withApplication(row.work(), row.receipt()));
            }
            var beforeDue = raw.work().nextDueWork(); var afterDue = current.work().nextDueWork();
            assertEquals(beforeDue.found(), afterDue.found());
            if (beforeDue.found()) assertEquals(beforeDue.work().workIdentity(), afterDue.work().workIdentity());
            assertEquals(beforeDue.indexRowsRead(), afterDue.indexRowsRead()); assertEquals(beforeDue.workRowsRead(), afterDue.workRowsRead());
        }
        assertEquals(phases, engine.metricsSnapshot().phaseNanos(), "Cold store inspection does not invoke PROCESS or provider phases");
        if (rejectMissingMembership) {
            var row = raw.work().storedIndexes().applications().values().get(0);
            var workStorage = new StoredCatchUpWorkIndexes(bytes, MAPS, 256);
            var storedWork = workStorage.open(key -> selected.root(StoredDocumentStore.Root.valueOf("WORK_" + key)), 0, 0);
            var roots = new EnumMap<StoredDocumentStore.Root, byte[]>(StoredDocumentStore.Root.class);
            for (var key : StoredDocumentStore.Root.values()) roots.put(key, selected.root(key));
            roots.put(StoredDocumentStore.Root.WORK_APPLICATION_BY_WORK,
                    storedWork.storedIndexes().applicationsByWork().remove(row.work().workIdentity()).map().storedRootDescriptor());
            try (var cold = storage.open(new StoredDocumentStore.Selection(roots, selected.metadata()), 8, 256)) {
                assertThrows(CoordinationObjectStorageException.class,
                        () -> cold.state().catchUpPlans().storedState().work().application(row.receipt().applicationReceiptIdentity()));
            }
            // A malformed physical minimum must fail before the ordinary selector's semantic consistency guard.
            var registered = storedWork.storedIndexes().work().get(row.work().workIdentity()); var key = registered.dueKey();
            var forged = new ManagedCatchUpWorkIndex.DueKey(blue.language.processor.ExternalOrderKey.of(List.of(-1L)), key.sourceOrder(),
                    key.sourceDocumentId(), key.sourceEpoch(), key.consumerDocumentId(), key.targetPath(), key.activationGeneration());
            assertNotEquals(key, forged);
            for (var root : StoredDocumentStore.Root.values()) roots.put(root, selected.root(root));
            roots.put(StoredDocumentStore.Root.WORK_DUE, storedWork.storedIndexes().due().put(forged, row.work().workIdentity()).map().storedRootDescriptor());
            try (var cold = storage.open(new StoredDocumentStore.Selection(roots, selected.metadata()), 8, 256)) {
                assertThrows(CoordinationObjectStorageException.class, () -> cold.state().catchUpPlans().storedState().work().nextDueWork());
            }
        }
        return raw.work().applicationCount();
    }
}
