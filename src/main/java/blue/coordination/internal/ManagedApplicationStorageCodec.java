package blue.coordination.internal;

import blue.coordination.api.*;
import blue.language.processor.closure.ManagedRepresentationCursor;
import java.util.Arrays;
import java.util.Objects;
import static blue.coordination.internal.SessionStorageWire.*;
import static blue.coordination.internal.SessionRecordCodec.*;

/** Complete application plus its original work; callers supply retained work, never infer a cause. */
final class ManagedApplicationStorageCodec {
    private static final String FORMAT = "blue-coordination/managed-application-storage/1";
    private final int maximumBytes;
    private final ManagedWorkStorageCodec works;

    ManagedApplicationStorageCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, null);
    }

    ManagedApplicationStorageCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache) {
        this.maximumBytes = maximumBytes;
        works = new ManagedWorkStorageCodec(maximumBytes, maximumDepth, cache);
    }

    byte[] encode(ManagedEpochApplicationReceipt receipt, ManagedEpochApplicationWork work) {
        var row = new ManagedCatchUpWorkIndex.RegisteredApplication(receipt, work);
        return SessionStorageWire.encode(maximumBytes, out -> { out.text(FORMAT); application(out, row); });
    }

    ManagedCatchUpWorkIndex.RegisteredApplication decode(byte[] bytes) {
        return physical(() -> {
            var row = SessionStorageWire.decode(bytes, maximumBytes, in -> {
                require(FORMAT.equals(text(in)), "Wrong application storage format"); return application(in);
            });
            require(Arrays.equals(bytes, encode(row.receipt(), row.work())), "Noncanonical application record");
            return row;
        });
    }

    void application(Writer out, ManagedCatchUpWorkIndex.RegisteredApplication value) {
        var a = value.receipt(); works.work(out, value.work());
        out.text(a.applicationReceiptIdentity()); out.text(a.workIdentity()); out.text(a.planIdentity()); out.text(a.sourceReceiptIdentity());
        out.text(a.contractsInvocationIdentity()); out.text(a.contractsResultIdentity()); out.text(a.commitCompanionIdentity());
        out.text(a.consumerDocumentId().value()); out.longValue(a.consumerRevisionEpoch()); out.text(a.consumerRevisionReceiptIdentity());
        out.text(a.consumerCommittedBlueId()); out.longValue(a.resultingSourceCursor());
        out.nullableText(a.representationCauseIdentity().orElse(null)); out.nullableText(a.successorRepresentationCauseIdentity().orElse(null));
        optional(out, a.resultingRepresentationCursor().orElse(null), (w, c) -> {
            w.text(c.anchorReceiptIdentity()); w.text(c.positionIdentity()); w.text(c.targetPositionIdentity()); w.nullableText(c.nextRevisionReceiptIdentity());
        });
    }

    ManagedCatchUpWorkIndex.RegisteredApplication application(Reader in) {
        var work = works.work(in); String identity = text(in);
        var coordinates = ManagedEpochApplicationReceipt.identified(text(in), text(in), text(in), text(in), text(in), text(in),
                DocumentId.of(text(in)), in.longValue(), text(in), text(in), in.longValue());
        String representation = nullableText(in); String successor = nullableText(in);
        var cursor = optional(in, r -> new ManagedRepresentationCursor(text(r), text(r), text(r), nullableText(r)));
        require(representation == null || successor == null, "Application receipt has incompatible cause roles");
        var receipt = representation != null ? ManagedEpochApplicationReceipt.identifiedRepresentation(coordinates, work, cursor)
                : successor != null ? ManagedEpochApplicationReceipt.identifiedWithSuccessorRepresentationCause(coordinates, work, cursor) : coordinates;
        require(identity.equals(receipt.applicationReceiptIdentity())
                && Objects.equals(representation, receipt.representationCauseIdentity().orElse(null))
                && Objects.equals(successor, receipt.successorRepresentationCauseIdentity().orElse(null))
                && Objects.equals(cursor, receipt.resultingRepresentationCursor().orElse(null)), "Application differs from its original work and exact result coordinates");
        return new ManagedCatchUpWorkIndex.RegisteredApplication(receipt, work);
    }
}
