package blue.coordination.external;

import blue.language.processor.closure.*;
import java.util.*;
import java.util.stream.Collectors;
import static blue.coordination.external.OperationReceiptCodec.*;

/** Terminal historical-lane progress, explicitly not a business operation or Timeline consumption. */
public final class ManagedProgressReceiptCodec {
    private static final String FORMAT = "blue-managed-progress-receipt-poc-1";
    private ManagedProgressReceiptCodec() { }

    public record Receipt(ManagedReactionContext managedReaction, List<ManagedImportLane.Delta> laneDeltas) {
        public Receipt { Objects.requireNonNull(managedReaction); laneDeltas = List.copyOf(laneDeltas); }
    }

    /** Read fences are physical publication guards and remain in the host plan, not semantic receipt bytes. */
    public static String encode(CoordinationCore.ManagedProgress progress, FrozenNodeEvidenceCodec.Writer writer,
                                FrozenNodeEvidenceCodec.Limits limits) {
        var encoder = new FrozenNodeEvidenceCodec.Encoder(writer, limits);
        var context = progress.managedReaction();
        verify(progress.laneDeltas());
        String deltas = ManagedImportLaneCodec.encode(progress.laneDeltas(), context, context.identity(), owners(context), encoder);
        return encoder.blob(bytes(map("format", FORMAT, "reaction", ManagedReactionContextCodec.encode(context, encoder), "lanes", deltas)));
    }

    public static Receipt decode(String authenticatedIdentity, FrozenNodeEvidenceCodec.Reader reader, FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        var root = json(decoder.blob(authenticatedIdentity));
        if (!FORMAT.equals(text(root, "format"))) throw invalid("Unexpected managed progress receipt format");
        var context = ManagedReactionContextCodec.decode(text(root, "reaction"), decoder);
        var deltas = ManagedImportLaneCodec.decode(text(root, "lanes"), context, context.identity(), owners(context), decoder);
        verify(deltas);
        return new Receipt(context, deltas);
    }

    private static Set<DocumentId> owners(ManagedReactionContext context) {
        return context.dueOccurrences().stream().map(ManagedReactionContext.DueOccurrence::consumerLineage).collect(Collectors.toSet());
    }
    private static void verify(List<ManagedImportLane.Delta> deltas) {
        if (deltas.stream().anyMatch(delta -> delta.outcome() != ManagedImportLane.Outcome.SOURCE_FAILURE))
            throw invalid("Metadata-only reaction cannot claim a consumer business outcome");
    }
}
