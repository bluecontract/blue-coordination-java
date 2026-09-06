package blue.coordination.external;

import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static blue.coordination.external.OperationReceiptCodec.*;

/** Data-only lane deltas, authenticated by their enclosing consumer receipt. */
final class ManagedImportLaneCodec {
    private static final String FORMAT = "blue-managed-import-lane-deltas-poc-1";
    private ManagedImportLaneCodec() { }

    static String encode(List<ManagedImportLane.Delta> deltas, ManagedReactionContext context,
                         String owner, Set<DocumentId> owned, FrozenNodeEvidenceCodec.Encoder encoder) {
        verify(deltas, context, owner, owned);
        List<Object> rows = new ArrayList<>();
        for (var delta : deltas) {
            var cursor = delta.nextCursor(); var lane = cursor.descriptor();
            Object descriptor = map("identity", lane.identity(), "consumer", lane.consumerLineage().value(),
                    "occurrence", lane.occurrenceIdentity(), "source", lane.sourceLineage().value(),
                    "creation", lane.creatingOperationIdentity(), "seed", lane.creatorExecutionSeedIdentity(), "site", lane.creationSiteIdentity(),
                    "mode", lane.selection().mode().name(), "cut", order(lane.selection().activationCut()),
                    "frontier", lane.selection().frontier().map(ManagedImportLaneCodec::order).orElse(null),
                    "basis", lane.canonicalSourceBasis(), "installedOperation", lane.installedSourceOperationIdentity(),
                    "installedBlueId", lane.installedBlueId(), "installedEpoch", lane.installedEpoch());
            rows.add(map("lane", delta.laneIdentity(), "expected", delta.expectedPositionIdentity(), "descriptor", descriptor,
                    "position", cursor.positionIdentity(), "terminalSource", cursor.lastTerminalSourceOperationIdentity(),
                    "successfulBlueId", cursor.successfulBlueId(), "successfulEpoch", cursor.successfulEpoch(),
                    "hadFailures", cursor.hadFailures(), "complete", cursor.complete(), "outcome", delta.outcome().name()));
        }
        return encoder.blob(bytes(map("format", FORMAT, "deltas", rows)));
    }

    static List<ManagedImportLane.Delta> decode(String identity, ManagedReactionContext context,
            String owner, Set<DocumentId> owned, FrozenNodeEvidenceCodec.Decoder decoder) {
        JsonNode root = json(decoder.blob(identity));
        if (!FORMAT.equals(text(root, "format"))) throw invalid("Unexpected managed lane receipt format");
        List<ManagedImportLane.Delta> deltas = new ArrayList<>();
        try {
            for (JsonNode row : array(root, "deltas")) {
                JsonNode d = row.get("descriptor");
                var selection = new ObserverAttachmentPlan.Selection(ObserverAttachmentPlan.Mode.valueOf(text(d, "mode")),
                        order(d.get("cut")), d.get("frontier").isNull() ? Optional.empty() : Optional.of(order(d.get("frontier"))));
                var lane = new ManagedImportLane.Descriptor(new DocumentId(text(d, "consumer")), text(d, "occurrence"),
                        new DocumentId(text(d, "source")), text(d, "creation"), text(d, "seed"), text(d, "site"), selection,
                        text(d, "basis"), text(d, "installedOperation"), text(d, "installedBlueId"), integer(d, "installedEpoch"));
                if (!lane.identity().equals(text(d, "identity"))) throw invalid("Lane descriptor identity mismatch");
                var cursor = new ManagedImportLane.Cursor(lane, text(row, "position"), text(row, "terminalSource"),
                        text(row, "successfulBlueId"), integer(row, "successfulEpoch"), bool(row, "hadFailures"), bool(row, "complete"));
                deltas.add(new ManagedImportLane.Delta(text(row, "lane"), text(row, "expected"), cursor,
                        ManagedImportLane.Outcome.valueOf(text(row, "outcome"))));
            }
            verify(deltas, context, owner, owned);
        } catch (IllegalArgumentException exception) { throw invalid("Invalid managed lane receipt: " + exception.getMessage()); }
        return List.copyOf(deltas);
    }

    private static void verify(List<ManagedImportLane.Delta> deltas, ManagedReactionContext context, String owner, Set<DocumentId> owned) {
        if (context == null) throw invalid("Managed lane receipt needs its exact reaction context");
        Map<String, ManagedReactionContext.DueOccurrence> due = new HashMap<>();
        context.dueOccurrences().forEach(value -> due.put(value.occurrenceIdentity(), value));
        for (var delta : deltas) {
            delta.verifyConsumerOperation(owner);
            var lane = delta.nextCursor().descriptor(); var expected = due.remove(lane.occurrenceIdentity());
            if (expected == null || !owned.contains(lane.consumerLineage())
                    || !expected.consumerLineage().equals(lane.consumerLineage()) || !expected.sourceLineage().equals(lane.sourceLineage())
                    || !expected.expectedLanePositionIdentity().equals(delta.expectedPositionIdentity())
                    || !expected.sourceOperationIdentity().equals(delta.nextCursor().lastTerminalSourceOperationIdentity())
                    || !context.creatingOperationIdentity().equals(lane.creatingOperationIdentity())
                    || !context.creatorExecutionSeedIdentity().equals(lane.creatorExecutionSeedIdentity())
                    || !context.creationSiteIdentity().equals(lane.creationSiteIdentity())
                    || !context.activationCut().equals(lane.selection().activationCut())) throw invalid("Lane delta differs from its exact reaction authority");
        }
        if (!due.isEmpty()) throw invalid("Managed receipt omits due occurrence lanes");
    }

    private static Object order(ExternalOrderKey key) { return map("micros", key.components().get(0), "entry", key.components().get(1)); }
    private static ExternalOrderKey order(JsonNode row) { return ExternalOrderKey.of(List.of(integer(row, "micros"), text(row, "entry"))); }
}
