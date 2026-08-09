package blue.coordination.internal;

import blue.coordination.api.Timeline;

import blue.coordination.api.ExactValue;

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
final class WholeObjectStore implements NodeProvider {
    private final Map<String, ExactValue> canonicalByBlueId =
            new LinkedHashMap<>();
    private final Map<String, ExactValue> providerByBlueId =
            new LinkedHashMap<>();
    private final Map<String, String> purposeByBlueId = new LinkedHashMap<>();
    private final EngineMetrics metrics;

    public WholeObjectStore(EngineMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ExactValue put(Node exact, String purpose) {
        return put(ExactValue.verified(exact), purpose);
    }

    /** Retains a resolver-owned immutable snapshot without re-freezing it. */
    public ExactValue put(ResolvedSnapshot snapshot, String purpose) {
        return put(ExactValue.fromSnapshot(snapshot), purpose);
    }

    /** Retains an already strict canonical frozen value without materializing. */
    public ExactValue put(FrozenNode frozen, String purpose) {
        return put(ExactValue.fromFrozen(frozen), purpose);
    }

    public synchronized ExactValue put(
            ExactValue value,
            String purpose) {
        ExactValue checked = Objects.requireNonNull(value, "value");
        ExactValue existing = canonicalByBlueId.get(checked.blueId());
        if (existing != null) {
            if (existing.frozen().isReferenceOnly()
                    && !checked.frozen().isReferenceOnly()) {
                canonicalByBlueId.put(checked.blueId(), checked);
                ExactValue provider = providerByBlueId.get(checked.blueId());
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
        ExactValue preferred = ExactValue.fromFrozen(
                Objects.requireNonNull(representation, "representation"));
        ExactValue canonical = canonicalByBlueId.get(preferred.blueId());
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

    public synchronized ExactValue require(String blueId) {
        ExactValue value = canonicalByBlueId.get(Objects.requireNonNull(
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

    /** Captures admission-time visibility without copying immutable bodies. */
    public synchronized Mark mark() {
        return new Mark(
                canonicalByBlueId,
                providerByBlueId,
                purposeByBlueId);
    }

    /** Restores the exact provider-visible state before failed admission. */
    public synchronized void rollbackTo(Mark mark) {
        Objects.requireNonNull(mark, "mark");
        canonicalByBlueId.clear();
        canonicalByBlueId.putAll(mark.canonicalByBlueId());
        providerByBlueId.clear();
        providerByBlueId.putAll(mark.providerByBlueId());
        purposeByBlueId.clear();
        purposeByBlueId.putAll(mark.purposeByBlueId());
    }

    public synchronized Map<String, ExactValue> snapshot() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(canonicalByBlueId));
    }

    @Override
    public synchronized List<Node> fetchByBlueId(String blueId) {
        ExactValue value = providerByBlueId.get(blueId);
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

    /** Immutable admission mark; exact values remain structurally shared. */
    public record Mark(
            Map<String, ExactValue> canonicalByBlueId,
            Map<String, ExactValue> providerByBlueId,
            Map<String, String> purposeByBlueId) {
        public Mark {
            canonicalByBlueId = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            canonicalByBlueId, "canonicalByBlueId")));
            providerByBlueId = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            providerByBlueId, "providerByBlueId")));
            purposeByBlueId = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            purposeByBlueId, "purposeByBlueId")));
        }
    }
}
