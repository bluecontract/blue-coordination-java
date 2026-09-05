package blue.coordination.api;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.ManagedEpochReceipt;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Objects;

/** Signs only evidence read from the owning runtime's committed store. */
final class RuntimeRetainedHistoryProvider implements RetainedHistoryProvider {
    private final CoordinationEngine engine;
    private final KeyPair keys;

    RuntimeRetainedHistoryProvider(CoordinationEngine engine, KeyPair keys) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override
    public RetainedHistoryPage read(Request request) {
        Objects.requireNonNull(request, "request");
        var head = engine.auditDocument(request.documentId());
        if (request.firstEpoch() > head.epoch() + 1) {
            throw new Unavailable("Requested epoch is beyond the observed source head");
        }
        String predecessor = request.firstEpoch() == 0
                ? head.authoredInitialBlueId()
                : required(request, request.firstEpoch() - 1).afterBlueId();
        long count = Math.min(request.limit(), head.epoch() - request.firstEpoch() + 1);
        ArrayList<ManagedEpochReceipt> receipts = new ArrayList<>((int) count);
        for (int offset = 0; offset < count; offset++) {
            receipts.add(required(request, request.firstEpoch() + offset));
        }
        var current = engine.auditDocument(request.documentId());
        if (current.epoch() != head.epoch() || !current.blueId().equals(head.blueId())) {
            throw new Unavailable("Source advanced while capturing a retained page; retry");
        }
        RetainedHistoryPage page = RetainedHistoryPage.signed(request,
                head.authoredInitialBlueId(), head.epoch(), head.blueId(),
                predecessor, receipts, keys.getPrivate());
        page.verify(request, head.authoredInitialBlueId(), predecessor, keys.getPublic());
        return page;
    }

    private ManagedEpochReceipt required(Request request, long epoch) {
        return engine.auditManagedEpoch(request.documentId(), epoch)
                .orElseThrow(() -> new Unavailable(
                        "Missing retained source evidence at epoch " + epoch));
    }
}
