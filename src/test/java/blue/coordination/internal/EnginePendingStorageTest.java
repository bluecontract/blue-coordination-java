package blue.coordination.internal;

import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.DocumentId;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.SourceDiscoveryStorageCodecTest.key;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;

final class EnginePendingStorageTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits SESSION = new DocumentSessionStorage.Limits(MAX, 128, 128L * 1024 * 1024);
    private static final StoredInsertionOrderedMap.Limits MAP = new StoredInsertionOrderedMap.Limits(
            new PersistentMapStorage.Limits(65536, 8192, 32768, 8192, 64), MAX, 128L * 1024 * 1024, 64);
    private static final Function<String, ManagedEpochApplicationWork> NO_WORK = id -> {
        throw new AssertionError("This source transition does not apply historical work: " + id);
    };
    private static final Function<String, ContractsClosureAdmissionReceipt> NO_ADMISSION = id -> {
        throw new AssertionError("This action has no admission publication: " + id);
    };

    @Test void coldAdmissionMapsPreserveDemandAndOriginalSubmittedSelection() throws Exception {
        sourceRoundTrip(false, false);
    }

    @Test void actualCommittedAdmissionResponseSurvivesProducerClosure() throws Exception {
        sourceRoundTrip(false, true);
    }

    @Test void actualCommittedLiveResponseSurvivesProducerClosure() throws Exception {
        sourceRoundTrip(true, true);
    }

    private void sourceRoundTrip(boolean known, boolean publish) throws Exception {
        EnginePendingStorage.Snapshot snapshot; DocumentSessionStorageTest.Bytes copied;
        SourceHistoryPrerequisite descriptor; byte[] preparedPacket; byte[] responsePacket = null;
        Map<String, byte[]> admissions = new java.util.HashMap<>();
        try (var scenario = new SourceDiscoveryStorageCodecTest.Scenario(known)) {
            descriptor = scenario.selection(); var c = scenario.coordinator();
            assertEquals(known ? SourceHistoryPrerequisite.Kind.LIVE : SourceHistoryPrerequisite.Kind.ADMISSION, descriptor.kind());
            preparedPacket = new SourceDiscoveryStorageCodec(MAX, 128).encodePrepared(c.requireSelection(descriptor), scenario.f.storage::retainView);
            if (publish) {
                var parent = scenario.f.completeEvidence(scenario.parent.id());
                var result = scenario.f.blue.advanced().processSourceHistoryPrerequisite(descriptor);
                assertEquals(parent, scenario.f.completeEvidence(scenario.parent.id()), "Source publication must not publish parent");
                var codec = new CoreReceiptStorageCodec(MAX, 128);
                responsePacket = known ? codec.encodeDrain(result.processing().orElseThrow(), NO_WORK)
                        : codec.encodeAdmission(result.admission().orElseThrow());
            }
            scenario.f.engine.documents().publicationSnapshot().admissionReceipts().forEach((id, receipt) ->
                    admissions.put(id, new CoreReceiptStorageCodec(MAX, 128).encodeAdmission(receipt)));
            try (var views = scenario.f.storage.openScope(); var selected = storage(scenario.f.bytes, scenario.f.storage).empty(views, NO_WORK,
                    id -> scenario.f.engine.documents().admissionReceipt(id).orElse(null))) {
                selected.retain(scenario.f.engine.contractsClosureAdapter().storedPlans(), c.storedMaps());
                assertSame(c.storedMaps().pending().get(key(descriptor)), selected.sources().pending().get(key(descriptor)));
                snapshot = selected.snapshot(); copied = scenario.f.bytes.copy();
            }
        }
        var sessions = new DocumentSessionStorage(copied, SESSION); int writes = copied.writes;
        try (var views = sessions.openScope(); var selected = storage(copied, sessions).open(snapshot, views, NO_WORK,
                id -> admissions.containsKey(id) ? new CoreReceiptStorageCodec(MAX, 128).decodeAdmission(admissions.get(id)) : null)) {
            var pending = selected.sources().pending().get(key(descriptor));
            assertFalse(pending.attempt().isComplete());
            assertTrue(pending.attempt().resourceDemands().stream().anyMatch(d -> d == pending.demand()));
            var submitted = selected.sources().submitted().get(descriptor.selectionIdentity());
            assertEquals(descriptor, submitted.descriptor());
            assertSame(submitted, selected.sources().submitted().get(descriptor.selectionIdentity()), "Original selected value is identity-pinned");
            assertArrayEquals(preparedPacket, new SourceDiscoveryStorageCodec(MAX, 128).encodePrepared(submitted, views::addressOf));
            if (publish) {
                var result = selected.sources().completed().get(descriptor.selectionIdentity());
                assertEquals(descriptor, result.selection()); var codec = new CoreReceiptStorageCodec(MAX, 128);
                assertArrayEquals(responsePacket, known ? codec.encodeDrain(result.processing().orElseThrow(), NO_WORK)
                        : codec.encodeAdmission(result.admission().orElseThrow()));
            } else assertTrue(selected.sources().completed().isEmpty());
            assertEquals(writes, copied.writes, "Cold reads must never retain or publish records");
        }
    }

    @Test void completedResponseCannotOutliveItsOriginalSubmittedRecordAndIncompleteRootSetIsRejected() throws Exception {
        try (var scenario = new SourceDiscoveryStorageCodecTest.Scenario(false)) {
            var descriptor = scenario.selection(); var c = scenario.coordinator();
            scenario.f.blue.advanced().processSourceHistoryPrerequisite(descriptor);
            try (var views = scenario.f.storage.openScope(); var selected = storage(scenario.f.bytes, scenario.f.storage).empty(views, NO_WORK,
                    id -> scenario.f.engine.documents().admissionReceipt(id).orElse(null));
                    var empty = storage(scenario.f.bytes, scenario.f.storage).empty(views, NO_WORK, NO_ADMISSION)) {
                selected.retain(scenario.f.engine.contractsClosureAdapter().storedPlans(), c.storedMaps());
                var roots = new EnumMap<EnginePendingStorage.Kind, StoredInsertionOrderedMap.Snapshot>(selected.snapshot().roots());
                roots.put(EnginePendingStorage.Kind.SOURCE_SUBMITTED, empty.snapshot().roots().get(EnginePendingStorage.Kind.SOURCE_SUBMITTED));
                try (var corrupt = storage(scenario.f.bytes, scenario.f.storage).open(new EnginePendingStorage.Snapshot(roots), views, NO_WORK, NO_ADMISSION)) {
                    assertThrows(CoordinationObjectStorageException.class,
                            () -> corrupt.sources().completed().get(descriptor.selectionIdentity()));
                }
                roots.remove(EnginePendingStorage.Kind.DRAFT);
                assertThrows(CoordinationObjectStorageException.class, () -> new EnginePendingStorage.Snapshot(roots));
            }
        }
    }

    @SuppressWarnings("try")
    @Test void actualDraftPreservesPutIfAbsentRollbackIdentityAndFailedWriteDoesNotReplaceIt() throws Exception {
        var objects = new DocumentSessionStorageTest.Bytes(); var sessions = new DocumentSessionStorage(objects, SESSION);
        EnginePendingStorage.Snapshot snapshot; String key;
        try (var blue = BlueCoordination.builder().contentDerivedDocumentIds().build(); var views = sessions.openScope();
                var selected = storage(objects, sessions).empty(views, NO_WORK, NO_ADMISSION)) {
            var timeline = blue.timelines().register("rcp2/parent", "alice");
            var parent = blue.documents().admitStaticProcessEmbedded(resource("parent.yaml"), ActivationPolicy.importFullHistory()).document("root");
            var initial = blue.values().yaml(resource("source.yaml"));
            var draft = blue.documents().draft(DocumentId.of(initial.blueId()), initial);
            var entry = blue.operations().on(parent).from(timeline).call("attach").through("owner")
                    .request(request -> request.managed("child", draft)).expectOccurrence("/child", draft)
                    .activation(ActivationPolicy.fromNow()).submit();
            key = entry.blueId(); var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            selected.retain(engine.contractsClosureAdapter().storedPlans(), RootedSourceDiscoveryCoordinator.StoredMaps.empty());
            var original = selected.plans().drafts().get(key);
            var copy = new OperationPlanStorageCodec(MAX, 128).decode(key, new OperationPlanStorageCodec(MAX, 128)
                    .encode(new OperationPlanStorageCodec.Plans(key, original, null))).draft();
            assertNotSame(original, copy); assertSame(original, selected.plans().drafts().putIfAbsent(key, copy));
            assertFalse(selected.plans().drafts().remove(key, copy), "Failed caller cannot remove another retained plan instance");
            snapshot = selected.snapshot(); objects.failAtWrite = objects.writes + 1;
            assertThrows(CoordinationObjectStorageException.class, () -> selected.plans().drafts().put(key, copy));
            assertSame(original, selected.plans().drafts().get(key));
            assertArrayEquals(snapshot.roots().get(EnginePendingStorage.Kind.DRAFT).keys(), selected.snapshot().roots().get(EnginePendingStorage.Kind.DRAFT).keys());
            objects.failAtWrite = -1;
        }
        var copied = objects.copy(); var coldSessions = new DocumentSessionStorage(copied, SESSION);
        try (var views = coldSessions.openScope(); var cold = storage(copied, coldSessions).open(snapshot, views, NO_WORK, NO_ADMISSION)) {
            var value = cold.plans().drafts().get(key); assertNotNull(value); assertSame(value, cold.plans().drafts().get(key));
            assertTrue(cold.plans().drafts().remove(key, value)); assertTrue(cold.plans().drafts().isEmpty());
            var maps = cold.plans(); cold.close();
            assertThrows(CoordinationObjectStorageException.class, () -> maps.drafts().get(key));
        }
    }

    private static EnginePendingStorage storage(DocumentSessionStorageTest.Bytes objects, DocumentSessionStorage sessions) {
        return new EnginePendingStorage(objects, MAP, sessions, 128);
    }
}
