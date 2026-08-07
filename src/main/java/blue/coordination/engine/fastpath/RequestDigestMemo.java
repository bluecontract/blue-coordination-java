package blue.coordination.engine.fastpath;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Request-scoped identity memo. It is deliberately not global: Node is
 * mutable and caching its digest beyond the engine-owned request would be
 * unsound. The same result/root/fragment object may be hashed by several
 * validation layers, which all share this memo instead.
 */
public final class RequestDigestMemo {
    private final Map<Node, String> values = new IdentityHashMap<Node, String>();
    private long calculations;
    private long hits;

    public String blueId(Node node) {
        Node checked = Objects.requireNonNull(node, "node");
        String cached = values.get(checked);
        if (cached != null) {
            hits++;
            return cached;
        }
        String calculated = DirectBlueIdCalculator.calculateBlueId(checked);
        values.put(checked, calculated);
        calculations++;
        return calculated;
    }

    public void bindVerified(Node node, String blueId) {
        Node checked = Objects.requireNonNull(node, "node");
        String identity = requireText(blueId, "blueId");
        String previous = values.putIfAbsent(checked, identity);
        if (previous != null && !previous.equals(identity)) {
            throw new IllegalStateException(
                    "One request Node was bound to two identities");
        }
    }

    void requireBound(Node node, String blueId) {
        String retained = values.get(Objects.requireNonNull(node, "node"));
        if (!Objects.equals(retained, blueId)) {
            throw new IllegalArgumentException(
                    "Node identity was not verified by this request");
        }
    }

    public long calculations() {
        return calculations;
    }

    public long hits() {
        return hits;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }
}
