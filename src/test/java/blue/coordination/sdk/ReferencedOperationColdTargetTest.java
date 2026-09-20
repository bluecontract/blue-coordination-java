package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationRecords.Bytes;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

final class ReferencedOperationColdTargetTest {
    private static final RootedCoordinationStorage.Limits LIMITS = RootedCoordinationStorageTest.LIMITS;
    @ParameterizedTest @ValueSource(strings = {"absent", "empty", "inline", "referenced", "subtype", "missing", "corrupt", "missing-type", "corrupt-type"})
    void coldRoutingAuthenticatesOriginalMessageAndRetainsOnlyItsTarget(String mode) {
        // given
        Map<String, String> provider = new HashMap<>();
        ExactNodeProvider exactProvider = id -> Optional.ofNullable(provider.get(id));
        RootedCoordinationStorage.Configuration config;
        try (var blue = BlueCoordination.inMemory()) { config = RootedCoordinationStorage.configuration(blue, LIMITS); }
        var records = new SdkRuntimePointMapsTest.LogicalRecords(); var objects = new SdkRuntimePointMapsTest.Bytes();
        DocumentId a, c; String entryId, eventJson, messageId, initialC; String typeId = null;
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, config, attempt, exactProvider)) {
            var blue = scope.coordination(); var timeline = blue.timelines().register("review/shared", "alice");
            var first = blue.documents().admitStaticProcessEmbedded(ReferencedOperationTargetTest.document("A"), ActivationPolicy.importFullHistory()).document("root");
            var other = blue.documents().admitStaticProcessEmbedded(ReferencedOperationTargetTest.document("C"), ActivationPolicy.importFullHistory()).document("root");
            a = first.id(); c = other.id(); initialC = other.snapshot().blueId();
            String type = "Coordination/Operation Request";
            if (mode.equals("subtype") || mode.endsWith("-type")) {
                var subtype = blue.values().providerContentYaml("name: TargetedCredit\ntype: Coordination/Operation Request");
                typeId = subtype.blueId(); provider.put(subtype.blueId(), subtype.json()); type = "{blueId: " + subtype.blueId() + "}";
            }
            String request = "";
            if (mode.equals("empty")) request = "\nrequest: {}";
            if (mode.equals("inline")) request = "\nrequest: {amount: 7}";
            if (mode.equals("referenced")) {
                var body = blue.values().providerContentYaml("amount: 7"); provider.put(body.blueId(), body.json());
                request = "\nrequest: {blueId: " + body.blueId() + "}";
            }
            var message = blue.values().providerContentYaml("type: " + type + "\noperation: credit\nchannel: owner\n"
                    + "document: {blueId: " + first.snapshot().blueId() + "}\nrequireExactDocumentVersion: true" + request);
            messageId = message.blueId(); provider.put(messageId, message.json());
            var event = ReferencedOperationTargetTest.event(blue, "blueId: " + messageId); eventJson = event.json();
            entryId = blue.events().from(timeline).exact(event).submit().blueId();
            scope.stage(); assertTrue(records.publish(attempt.prepare("admit-targeted", List.of(), new Bytes(new byte[]{1}))));
        }
        String evidenceId = mode.endsWith("-type") ? typeId : messageId;
        String original = provider.get(evidenceId);
        if (mode.startsWith("missing")) provider.remove(evidenceId);
        if (mode.startsWith("corrupt")) provider.put(evidenceId, "{\"amount\":999}");
        // when
        if (mode.startsWith("missing") || mode.startsWith("corrupt")) {
            var fresh = objects.fresh();
            assertThrows(RuntimeException.class, () -> {
                try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(fresh)
                        .openLogical(LIMITS, config, attempt, exactProvider)) {
                    scope.coordination().processing().processNextStage(scope.documentHandle(c).orElseThrow());
                }
            }, "Unavailable or corrupt message evidence cannot become broadcast");
            provider.put(evidenceId, original);
        }
        objects = objects.fresh();
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, config, attempt, exactProvider)) {
            var blue = scope.coordination(); var first = scope.documentHandle(a).orElseThrow(); var other = scope.documentHandle(c).orElseThrow();
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, blue.processing().processNextStage(other).disposition());
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, blue.processing().processNextStage(first).disposition());
            scope.stage(); assertTrue(records.publish(attempt.prepare("apply-targeted", List.of(), new Bytes(new byte[]{2}))));
        }
        // then
        objects = objects.fresh();
        try (var attempt = records.attempt(); var scope = RootedCoordinationStorage.controlledRepository(objects)
                .openLogical(LIMITS, config, attempt, exactProvider)) {
            var blue = scope.coordination(); var first = scope.documentHandle(a).orElseThrow(); var other = scope.documentHandle(c).orElseThrow();
            assertEquals(7, scope.documentHandle(a).orElseThrow().snapshot().longAt("/counter"));
            assertEquals(0, scope.documentHandle(c).orElseThrow().snapshot().longAt("/counter"));
            assertEquals(initialC, scope.documentHandle(c).orElseThrow().snapshot().blueId());
            assertEquals(2, blue.advanced().auditManagedEpochs(a).size()); assertEquals(1, blue.advanced().auditManagedEpochs(c).size());
            var audit = blue.advanced().auditTimelineEntry(entryId).orElseThrow(); assertEquals(eventJson, audit.exact().json());
            assertEquals(Set.of("empty", "inline", "referenced").contains(mode), audit.request().isPresent());
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, blue.processing().processNextStage(first).disposition());
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, blue.processing().processNextStage(other).disposition());
        }
    }
}
