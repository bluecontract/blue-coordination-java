package blue.coordination.external;

import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Codec witnesses only. Actual import execution is covered by the owning Core lane tests. */
class ManagedProgressReceiptCodecTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();
    private static final DocumentId CONSUMER = new DocumentId("consumer"), SOURCE = new DocumentId("source");

    @Test void metadataOnlySourceFailureColdRoundTripPreservesTwoDistinctAliasLanes() {
        var fixture = fixture(); Map<String, byte[]> store = new HashMap<>();
        var deltas = fixture.due().stream().map(due -> due.finish(fixture.context().identity(),
                ManagedImportLane.Outcome.SOURCE_FAILURE, "not-installed", 99)).toList();
        var progress = new CoordinationCore.ManagedProgress(fixture.context(), List.of(), deltas);
        String key = ManagedProgressReceiptCodec.encode(progress, store::put, LIMITS);
        var cold = ManagedProgressReceiptCodec.decode(key, store::get, LIMITS);
        assertEquals(fixture.context().identity(), cold.managedReaction().identity());
        assertEquals(2, cold.laneDeltas().size());
        for (var delta : cold.laneDeltas()) {
            assertEquals("pin0", delta.nextCursor().successfulBlueId()); assertEquals(0, delta.nextCursor().successfulEpoch());
            assertTrue(delta.nextCursor().hadFailures()); assertTrue(delta.nextCursor().complete());
            assertEquals(hash('5'), delta.nextCursor().lastTerminalSourceOperationIdentity());
            delta.verifyConsumerOperation(cold.managedReaction().identity());
        }
        var root = OperationReceiptCodec.json(store.get(key));
        assertFalse(root.has("operation")); assertFalse(root.has("gas")); assertFalse(root.has("input"));
        assertNotEquals(cold.laneDeltas().get(0).laneIdentity(), cold.laneDeltas().get(1).laneIdentity());
        assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.decode(key, store::get, LIMITS));
    }

    @Test void metadataOnlyReceiptRejectsBusinessOutcomeOmittedAliasAndForeignPosition() throws Exception {
        var fixture = fixture(); Map<String, byte[]> store = new HashMap<>();
        var deltas = fixture.due().stream().map(due -> due.finish(fixture.context().identity(),
                ManagedImportLane.Outcome.SOURCE_FAILURE, null, 0)).toList();
        assertThrows(InvalidExecutionEvidenceException.class, () -> ManagedProgressReceiptCodec.encode(
                new CoordinationCore.ManagedProgress(fixture.context(), List.of(), List.of(deltas.get(0))), store::put, LIMITS));
        var business = fixture.due().stream().map(due -> due.finish(fixture.context().identity(), ManagedImportLane.Outcome.APPLIED, "pin1", 1)).toList();
        assertThrows(InvalidExecutionEvidenceException.class, () -> ManagedProgressReceiptCodec.encode(
                new CoordinationCore.ManagedProgress(fixture.context(), List.of(), business), store::put, LIMITS));
        String key = ManagedProgressReceiptCodec.encode(new CoordinationCore.ManagedProgress(fixture.context(), List.of(), deltas), store::put, LIMITS);
        ObjectMapper json = new ObjectMapper(); ObjectNode root = (ObjectNode) json.readTree(store.get(key));
        ObjectNode laneRoot = (ObjectNode) json.readTree(store.get(root.get("lanes").textValue()));
        ((ObjectNode) laneRoot.get("deltas").get(0)).put("position", hash('9'));
        root.put("lanes", retain(json.writeValueAsBytes(laneRoot), store));
        String altered = retain(json.writeValueAsBytes(root), store);
        assertThrows(InvalidExecutionEvidenceException.class, () -> ManagedProgressReceiptCodec.decode(altered, store::get, LIMITS));
    }

    @Test void businessLaneCodecBindsActualOwningOperationAndSharesAggregateReadBudget() {
        var fixture = fixture(); Map<String, byte[]> store = new HashMap<>(); String operation = hash('8');
        var deltas = fixture.due().stream().map(due -> due.finish(operation, ManagedImportLane.Outcome.APPLIED, "pin1", 1)).toList();
        var encoder = new FrozenNodeEvidenceCodec.Encoder(store::put, LIMITS);
        String key = ManagedImportLaneCodec.encode(deltas, fixture.context(), operation, Set.of(CONSUMER), encoder);
        var cold = ManagedImportLaneCodec.decode(key, fixture.context(), operation, Set.of(CONSUMER), new FrozenNodeEvidenceCodec.Decoder(store::get, LIMITS));
        assertEquals(2, cold.size()); assertEquals(1, cold.get(0).nextCursor().successfulEpoch());
        assertThrows(InvalidExecutionEvidenceException.class, () -> ManagedImportLaneCodec.decode(key, fixture.context(), hash('9'),
                Set.of(CONSUMER), new FrozenNodeEvidenceCodec.Decoder(store::get, LIMITS)));
        assertThrows(InvalidExecutionEvidenceException.class, () -> ManagedImportLaneCodec.decode(key, fixture.context(), operation,
                Set.of(SOURCE), new FrozenNodeEvidenceCodec.Decoder(store::get, LIMITS)));
        var metadata = fixture.due().stream().map(due -> due.finish(fixture.context().identity(), ManagedImportLane.Outcome.SOURCE_FAILURE, null, 0)).toList();
        String progress = ManagedProgressReceiptCodec.encode(new CoordinationCore.ManagedProgress(fixture.context(), List.of(), metadata), store::put, LIMITS);
        long total = store.get(progress).length;
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class, () -> ManagedProgressReceiptCodec.decode(progress, store::get,
                new FrozenNodeEvidenceCodec.Limits(16 * 1024 * 1024, total + 1, 256, 1_000_000)));
    }

    private record Fixture(ManagedReactionContext context, List<ManagedImportLane.Due> due) { }
    private static Fixture fixture() {
        var cut = ExternalOrderKey.of(List.of(100L, blue("cut-entry")));
        var header = new ManagedImportLane.Header(SOURCE, "basis", hash('5'), Optional.of(hash('4')),
                CoordinationCore.OperationKind.EXTERNAL_INPUT, ProcessorStatus.RUNTIME_FATAL, "pin0", 0, "pin0", 0,
                Optional.of(ExternalOrderKey.of(List.of(10L, blue("old-source-entry")))), hash('6'));
        List<ManagedImportLane.Due> due = new ArrayList<>(); List<ManagedReactionContext.DueOccurrence> occurrences = new ArrayList<>();
        for (char alias : List.of('a', 'b')) {
            var lane = new ManagedImportLane.Descriptor(CONSUMER, hash(alias), SOURCE, hash('1'), hash('2'), hash('3'),
                    ObserverAttachmentPlan.Selection.fullHistory(cut), "basis", hash('4'), "pin0", 0);
            var cursor = ManagedImportLane.Cursor.start(lane);
            due.add(new ManagedImportLane.Due(cursor, header, Optional.of(new ManagedImportLane.PrefixAuthority(lane.identity(), hash('5')))));
            occurrences.add(new ManagedReactionContext.DueOccurrence(lane.occurrenceIdentity(), CONSUMER, SOURCE, hash('5'), cursor.positionIdentity()));
        }
        return new Fixture(new ManagedReactionContext(hash('1'), hash('2'), hash('3'), cut, hash('6'), occurrences), due);
    }
    private static String hash(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
    private static String blue(String value) { return blue.language.identity.DirectBlueIdCalculator.calculateBlueId(new blue.language.model.Node().name(value)); }
    private static String retain(byte[] value, Map<String, byte[]> store) { String key = FrozenNodeEvidenceCodec.digest(value); store.put(key, value); return key; }
}
