package blue.coordination.external;

import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.closure.ClosureResourceDemand;
import blue.language.processor.closure.ClosureResourceDemandCodec;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lossless portable needs only: no gas ledger, queue, or interpreter continuation. */
public final class CanonicalSourceAwaitCodec {
    private static final String FORMAT = "blue-canonical-source-await-poc-1";

    private CanonicalSourceAwaitCodec() { }

    public static String encode(CanonicalSourceHistory.Await await, FrozenNodeEvidenceCodec.Writer writer,
                                FrozenNodeEvidenceCodec.Limits limits) {
        return encode(await, new FrozenNodeEvidenceCodec.Encoder(writer, limits));
    }

    private static String encode(CanonicalSourceHistory.Await await, FrozenNodeEvidenceCodec.Encoder encoder) {
        List<String> demands = await.resourceDemands().stream()
                .map(demand -> ClosureResourceDemandCodec.encode(demand, encoder)).toList();
        return encoder.blob(OperationReceiptCodec.bytes(
                Map.of("format", FORMAT, "keys", await.keys(), "demands", demands)));
    }

    /** Restore the exact retained Need root; this does not authenticate any demanded resource. */
    public static CanonicalSourceHistory.Await decode(String retainedIdentity, FrozenNodeEvidenceCodec.Reader reader,
                                                       FrozenNodeEvidenceCodec.Limits limits) {
        var decoder = new FrozenNodeEvidenceCodec.Decoder(reader, limits);
        var canonical = new FrozenNodeEvidenceCodec.Encoder((key, bytes) -> { }, limits);
        var value = OperationReceiptCodec.json(decoder.blob(retainedIdentity));
        if (!FORMAT.equals(value.path("format").asText()) || !value.path("keys").isArray() || !value.path("demands").isArray())
            throw new InvalidExecutionEvidenceException("Invalid canonical source Await record");
        List<String> keys = new ArrayList<>();
        for (var key : value.path("keys")) {
            if (!key.isTextual()) throw new InvalidExecutionEvidenceException("Await key must be text");
            keys.add(key.textValue());
        }
        List<ClosureResourceDemand> demands = new ArrayList<>();
        for (var root : value.path("demands")) {
            if (!root.isTextual()) throw new InvalidExecutionEvidenceException("Await demand root must be text");
            demands.add(ClosureResourceDemandCodec.decode(root.textValue(), decoder, canonical));
        }
        var restored = new CanonicalSourceHistory.Await(keys, demands);
        if (!retainedIdentity.equals(encode(restored, canonical)))
            throw new InvalidExecutionEvidenceException("Await is not a closed canonical record");
        return restored;
    }
}
