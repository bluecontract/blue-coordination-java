package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.merge.ResolvedSnapshotStorageCodec;
import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.ExactNodeStorageCodec;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.FrozenNodeStorageCodec;
import java.util.Arrays;
import java.util.Objects;
import static blue.language.snapshot.ExactNodeStorageCodec.*;

/**
 * Internal shared transport for complete exact-value lanes under an authenticated
 * pinned host reference. Decoding is not an admission endpoint for user-supplied
 * bytes and does not create provider or processing authority. No provider replay.
 */
public final class ExactValueStorageCodec {
    private static final String FORMAT = "blue-coordination/exact-value-storage/1";
    private final ExactNodeStorageCodec nodes;
    private final FrozenNodeStorageCodec frozen;
    private final ResolvedSnapshotStorageCodec snapshots;

    static String cacheFamily(int maximumBytes, int maximumDepth) {
        return FORMAT + "/" + maximumBytes + "/" + maximumDepth;
    }

    /**
     * Creates operational storage bounds, not semantic processing limits.
     * @param maximumBytes complete encoded byte bound, at least 128
     * @param maximumDepth physical codec traversal bound, from 1 through 256
     */
    public ExactValueStorageCodec(int maximumBytes, int maximumDepth) {
        nodes = new ExactNodeStorageCodec(maximumBytes, maximumDepth);
        frozen = new FrozenNodeStorageCodec(maximumBytes, maximumDepth);
        snapshots = new ResolvedSnapshotStorageCodec(maximumBytes, maximumDepth);
    }

    /**
     * Retains the original exact value and complete resolver or cyclic evidence.
     * @param value value already established by the normal runtime
     * @return detached, bounded, versioned bytes
     */
    public byte[] encode(ExactValue value) {
        Objects.requireNonNull(value, "value");
        try {
            return nodes.encodeEnvelope(FORMAT, out -> {
                writeText(out, value.blueId());
                if (value.snapshot().isPresent()) {
                    out.writeByte(1);
                    writeBytes(out, snapshots.encode(value.snapshot().orElseThrow()));
                } else {
                    out.writeByte(value.isCyclicMember() ? 2 : 0);
                    writeBytes(out, frozen.encode(value.frozen()));
                    if (value.isCyclicMember()) writeBytes(out, nodes.encode(new Node()
                            .items(value.cyclicSetProof().orElseThrow().declaredPlaceholderSet())));
                }
            });
        } catch (RuntimeException failure) {
            throw physical(failure);
        }
    }

    /**
     * Restores exact lanes without provider resolution or handler execution.
     * @param bytes bytes selected through an authenticated immutable host reference
     * @return detached exact value with original evidence
     */
    public ExactValue decode(byte[] bytes) {
        try {
            ExactValue value = nodes.decodeEnvelope(bytes, FORMAT, in -> {
                String identity = readText(in);
                int lane = in.readUnsignedByte();
                if (lane > 2) throw invalid("Unknown exact value storage lane");
                ExactValue restored;
                if (lane == 1) {
                    restored = ExactValue.fromSnapshot(snapshots.decode(readBytes(in)));
                } else {
                    byte[] encoded = readBytes(in);
                    FrozenNode body = frozen.decode(encoded);
                    restored = lane == 2
                            ? ExactValue.fromVerifiedProviderEvidence(identity, body.toNode(),
                                CyclicSetProof.fromDeclaredPlaceholderSet(nodes.decode(readBytes(in)).getItems()))
                            : ExactValue.fromFrozen(body);
                    if (!Arrays.equals(encoded, frozen.encode(restored.frozen())))
                        throw invalid("Exact value changed frozen construction or body during restoration");
                }
                if (!Objects.equals(identity, restored.blueId())) throw invalid("Stored exact value identity differs");
                return restored;
            });
            if (!Arrays.equals(bytes, encode(value))) throw invalid("Noncanonical exact value storage");
            return value;
        } catch (RuntimeException failure) {
            throw physical(failure);
        }
    }

    private static CoordinationObjectStorageException physical(RuntimeException failure) {
        return failure instanceof CoordinationObjectStorageException storage ? storage
                : new CoordinationObjectStorageException("Exact value storage failed", failure);
    }
}
