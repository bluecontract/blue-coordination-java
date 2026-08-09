package blue.coordination.basic.engine;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.provider.NodeProvider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory whole-object store.
 *
 * <p>Requests, Timeline Entries, semantic Roots, and Process Embedded documents
 * are retained as whole immutable values. The semantic value retained for API
 * reads is intentionally separate from the representation returned through the
 * Language provider. A provider representation may replace an autonomous child
 * with a pure reference while preserving the exact same BlueId; this lets the
 * frozen runtime use representation invariance without losing the fully
 * materialized semantic value held by the document session.</p>
 */
public final class WholeObjectStore implements NodeProvider {
    private final Map<String, ExactNodeValue> canonicalByBlueId =
            new LinkedHashMap<>();
    private final Map<String, ExactNodeValue> providerByBlueId =
            new LinkedHashMap<>();
    private final Map<String, String> purposeByBlueId = new LinkedHashMap<>();
    private final EngineMetrics metrics;

    public WholeObjectStore(EngineMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ExactNodeValue put(Node exact, String purpose) {
        return put(ExactNodeValue.verified(exact), purpose);
    }

    /** Retains a resolver-owned immutable snapshot without re-freezing it. */
    public ExactNodeValue put(ResolvedSnapshot snapshot, String purpose) {
        return put(ExactNodeValue.fromSnapshot(snapshot), purpose);
    }

    /** Retains an already strict canonical frozen value without materializing. */
    public ExactNodeValue put(FrozenNode frozen, String purpose) {
        return put(ExactNodeValue.fromFrozen(frozen), purpose);
    }

    public synchronized ExactNodeValue put(
            ExactNodeValue value,
            String purpose) {
        ExactNodeValue checked = Objects.requireNonNull(value, "value");
        ExactNodeValue existing = canonicalByBlueId.get(checked.blueId());
        if (existing != null) {
            if (existing.frozen().isReferenceOnly()
                    && !checked.frozen().isReferenceOnly()) {
                canonicalByBlueId.put(checked.blueId(), checked);
                ExactNodeValue provider = providerByBlueId.get(checked.blueId());
                if (provider == null || provider.frozen().isReferenceOnly()) {
                    providerByBlueId.put(checked.blueId(), checked);
                }
                purposeByBlueId.put(checked.blueId(), sanitize(purpose));
                metrics.increment("wholeObjectStore.providerBodiesUpgraded");
                return checked;
            }
            metrics.increment(existing.frozen() == checked.frozen()
                    ? "wholeObjectStore.duplicates"
                    : "wholeObjectStore.representationVariants");
            // The canonical map keeps the richest semantic value, while the
            // caller keeps the exact representation it supplied. This matters
            // for compact Process Embedded shells that intentionally share the
            // semantic object's BlueId.
            return checked;
        }
        String normalizedPurpose = sanitize(purpose);
        canonicalByBlueId.put(checked.blueId(), checked);
        providerByBlueId.put(checked.blueId(), checked);
        purposeByBlueId.put(checked.blueId(), normalizedPurpose);
        metrics.increment("wholeObjectStore.insertions");
        metrics.increment("wholeObjectStore.purpose." + normalizedPurpose);
        return checked;
    }

    /**
     * Selects an identity-equivalent representation for provider-backed frozen
     * calls without replacing the fully materialized semantic value.
     */
    public synchronized void preferProviderRepresentation(
            FrozenNode representation,
            String purpose) {
        ExactNodeValue preferred = ExactNodeValue.fromFrozen(
                Objects.requireNonNull(representation, "representation"));
        ExactNodeValue canonical = canonicalByBlueId.get(preferred.blueId());
        if (canonical == null) {
            throw new IllegalStateException(
                    "Cannot prefer an unknown exact object "
                            + preferred.blueId());
        }
        if (preferred.frozen().isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Provider preference must contain an exact object body");
        }
        providerByBlueId.put(preferred.blueId(), preferred);
        purposeByBlueId.put(preferred.blueId(), sanitize(purpose));
        metrics.increment("wholeObjectStore.providerRepresentationsPreferred");
    }

    public synchronized ExactNodeValue require(String blueId) {
        ExactNodeValue value = canonicalByBlueId.get(Objects.requireNonNull(
                blueId, "blueId"));
        if (value == null) {
            throw new IllegalArgumentException("Unknown exact object " + blueId);
        }
        metrics.increment("wholeObjectStore.reads");
        return value;
    }

    public synchronized boolean contains(String blueId) {
        return canonicalByBlueId.containsKey(Objects.requireNonNull(
                blueId, "blueId"));
    }

    public synchronized int size() {
        return canonicalByBlueId.size();
    }

    public synchronized Map<String, ExactNodeValue> snapshot() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(canonicalByBlueId));
    }

    @Override
    public synchronized List<Node> fetchByBlueId(String blueId) {
        ExactNodeValue value = providerByBlueId.get(blueId);
        if (value == null) {
            return Collections.emptyList();
        }
        metrics.increment("wholeObjectStore.providerReads");
        String purpose = purposeByBlueId.getOrDefault(blueId, "unknown");
        metrics.increment("wholeObjectStore.providerReads." + purpose);
        return Collections.singletonList(value.copyNode());
    }

    private static String sanitize(String purpose) {
        String checked = Objects.requireNonNull(purpose, "purpose").trim();
        if (checked.isEmpty()) {
            return "unspecified";
        }
        return checked.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
