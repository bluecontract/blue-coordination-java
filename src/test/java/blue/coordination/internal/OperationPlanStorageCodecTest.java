package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ActivationMode;
import blue.coordination.api.ExactValue;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.ManagedEpochSelector;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OperationPlanStorageCodecTest {
    private static final int MAX = 8 * 1024 * 1024;

    @Test void actualSdkCreationPlanRetainsExactAuthoredEvidenceWithoutInitializingChild() throws Exception {
        // given
        byte[] stored; String entryId; String childId;
        try (var blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            var timeline = blue.timelines().register("rcp2/parent", "alice");
            var parent = blue.documents().admitStaticProcessEmbedded(resource("parent.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            var initial = blue.values().yaml(resource("source.yaml"));
            var draft = blue.documents().draft(DocumentId.of(initial.blueId()), initial);
            var entry = blue.operations().on(parent).from(timeline).call("attach").through("owner")
                    .request(request -> request.managed("child", draft)).expectOccurrence("/child", draft)
                    .activation(ActivationPolicy.fromNow()).submit();
            entryId = entry.blueId(); childId = draft.id().value();
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            var adapter = engine.contractsClosureAdapter(); var before = parent.snapshot().blueId();
            var plan = adapter.operationPlanForStorage(entryId).orElseThrow();
            var codec = new OperationPlanStorageCodec(MAX, 128); stored = codec.encode(plan);
            // when
            var restored = codec.decode(entryId, stored);
            // then
            assertEquals(plan.draft().targetDocumentId(), restored.draft().targetDocumentId());
            assertEquals(plan.draft().managedRequestFields(), restored.draft().managedRequestFields());
            assertEquals(plan.draft().expectedOccurrences(), restored.draft().expectedOccurrences());
            assertTrue(restored.draft().drafts().get(draft.id()).initial().sameExactValue(plan.draft().drafts().get(draft.id()).initial()));
            assertTrue(restored.draft().drafts().get(draft.id()).contentDerivedIdentity());
            assertEquals(before, parent.snapshot().blueId());
            assertThrows(blue.coordination.api.CoordinationException.class, () -> blue.documents().require(draft.id()));
            assertTrue(adapter.operationPlanForStorage(entryId).isPresent());
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(parent).entry(entry).disposition());
            // Root-owned processing does not itself discard the append-time evidence.
            assertArrayEquals(stored, codec.encode(adapter.operationPlanForStorage(entryId).orElseThrow()));
        }
        // No producer engine/provider is alive: decoding only restores exact retained instructions.
        var restored = new OperationPlanStorageCodec(MAX, 128).decode(entryId, stored);
        assertEquals(childId, restored.draft().drafts().values().iterator().next().initial().blueId());
    }

    @Test void actualSdkEpochSelectorRemainsBoundToItsOriginalPositionAndEntry() throws Exception {
        // given
        try (var blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            var timeline = blue.timelines().register("rcp2/parent", "alice");
            blue.timelines().register("rcp2/source", "alice");
            var parent = blue.documents().admitStaticProcessEmbedded(resource("parent.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            var source = blue.documents().admitStaticProcessEmbedded(resource("source.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            var selected = source.snapshot();
            var entry = blue.operations().on(parent).from(timeline).call("attach").through("owner")
                    .requestYaml("child:\n  blueId: " + selected.blueId())
                    .selectManagedEpoch(new ManagedEpochSelector(source.id(), 0L, selected.blueId(), "/child")).submit();
            // when
            var plan = ((DefaultCoordinationEngine) blue.advanced().rawEngine()).contractsClosureAdapter()
                    .operationPlanForStorage(entry.blueId()).orElseThrow();
            // then
            assertNull(plan.draft());
            var codec = new OperationPlanStorageCodec(MAX, 128); byte[] bytes = codec.encode(plan);
            var restored = codec.decode(entry.blueId(), bytes);
            assertEquals(plan.selection().selections(), restored.selection().selections());
            assertEquals(0, restored.selection().selections().get(0).sourceEpoch());
            assertEquals(selected.blueId(), restored.selection().selections().get(0).expectedSourceBlueId());
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(selected.blueId(), bytes));
            assertArrayEquals(bytes, codec.encode(restored));
            assertThrows(UnsupportedOperationException.class, () -> restored.selection().selections().clear());
        }
    }

    @Test void malformedBoundedRowsAndConflictingCapturedTargetsFailClosed() {
            // given
            var initial = ExactValue.verified(new blue.language.model.Node().name("authored"));
            var other = ExactValue.verified(new blue.language.model.Node().name("other"));
            var id = DocumentId.of(initial.blueId()); var child = DocumentId.of(other.blueId());
            var draft = new ContractsManagedDraftPlan.ManagedDraft(child, other, null, true);
            var plan = new ContractsManagedDraftPlan(id, 2, initial.blueId(), Map.of(child, draft), Map.of("child", child),
                    List.of(new ContractsManagedDraftPlan.ExpectedOccurrence("/child", child, ActivationMode.BIRTH_AT_ATTACHMENT)));
            var codec = new OperationPlanStorageCodec(MAX, 128);
            // when
            byte[] bytes = codec.encode(new OperationPlanStorageCodec.Plans(initial.blueId(), plan, null));
            // then
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(initial.blueId(), Arrays.copyOf(bytes, bytes.length - 1)));
            assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(initial.blueId(), Arrays.copyOf(bytes, bytes.length + 1)));
            assertThrows(CoordinationObjectStorageException.class, () -> new OperationPlanStorageCodec(bytes.length - 1, 128).decode(initial.blueId(), bytes));
            assertThrows(CoordinationObjectStorageException.class, () -> new OperationPlanStorageCodec(128, 128).encode(
                    new OperationPlanStorageCodec.Plans(initial.blueId(), plan, null)));
            var selection = new ContractsManagedEpochSelectionPlan(id, 3, initial.blueId(),
                    List.of(new ContractsManagedEpochSelectionPlan.Selection(child, -1, other.blueId(), "/child")));
            assertThrows(CoordinationObjectStorageException.class, () -> new OperationPlanStorageCodec.Plans(initial.blueId(), plan, selection));
    }

    private static String resource(String name) throws Exception {
        try (var in = OperationPlanStorageCodecTest.class.getResourceAsStream("/rooted/" + name)) {
            return new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
