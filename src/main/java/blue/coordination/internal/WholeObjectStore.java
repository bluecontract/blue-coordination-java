package blue.coordination.internal;

import blue.coordination.api.Timeline;

import blue.coordination.api.ExactValue;

import blue.language.identity.BlueIds;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory whole-object store.
 *
 * <p>Requests, Timeline Entries, semantic Roots, and Process Embedded documents
 * are retained as whole immutable values. The semantic value retained for API
 * reads is intentionally separate from the representation returned through the
 * Language provider. A provider representation may replace a managed child
 * with a pure reference while preserving the exact same BlueId; this lets the
 * frozen runtime use representation invariance without losing the fully
 * materialized semantic value held by the document session.</p>
 */
final class WholeObjectStore implements NodeProvider, CyclicAwareNodeProvider {
    private final Map<String, ExactValue> canonicalByBlueId =
            new LinkedHashMap<>();
    private final Map<String, ExactValue> providerByBlueId =
            new LinkedHashMap<>();
    private final Map<String, String> purposeByBlueId = new LinkedHashMap<>();
    private final Map<String, CyclicSetProof> cyclicProofByMasterBlueId =
            new LinkedHashMap<>();
    private final java.util.Set<String> unavailableProviderBlueIds =
            new LinkedHashSet<>();
    private final List<Mark> activeMarks = new ArrayList<>();
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
                recordBeforeMutation(checked.blueId());
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
        recordBeforeMutation(checked.blueId());
        canonicalByBlueId.put(checked.blueId(), checked);
        providerByBlueId.put(checked.blueId(), checked);
        purposeByBlueId.put(checked.blueId(), normalizedPurpose);
        metrics.increment("wholeObjectStore.insertions");
        metrics.increment("wholeObjectStore.purpose." + normalizedPurpose);
        return checked;
    }

    /**
     * Retains exact-node retry evidence without stripping cyclic proof.
     *
     * <p>The resolver has already crossed a verifying provider boundary, but
     * this durable cache boundary independently authenticates a cyclic value
     * again before retaining both its member body and complete proof under the
     * same object-store savepoint. Ordinary values remain subject to their
     * direct exact identity.</p>
     */
    synchronized ExactValue putVerifiedProviderEvidence(
            ExactValue value,
            CyclicSetProof cyclicProof,
            String purpose) {
        ExactValue selected = Objects.requireNonNull(value, "value");
        if (!selected.isCyclicMember()) {
            if (cyclicProof != null) {
                throw new IllegalArgumentException(
                        "Ordinary provider evidence cannot carry cyclic proof");
            }
            return put(selected, purpose);
        }
        ExactValue authenticated = ExactValue.fromVerifiedProviderEvidence(
                selected.blueId(),
                selected.copyNode(),
                Objects.requireNonNull(cyclicProof, "cyclicProof"));
        if (!authenticated.sameExactValue(selected)) {
            throw new IllegalArgumentException(
                    "Cyclic retry evidence changed after proof verification");
        }
        ExactValue retained = put(authenticated, purpose);
        String masterBlueId = BlueIds.cyclicSetMasterBlueId(
                selected.blueId());
        recordProofBeforeMutation(masterBlueId);
        cyclicProofByMasterBlueId.put(
                masterBlueId,
                CyclicSetProof.fromDeclaredPlaceholderSet(
                        cyclicProof.declaredPlaceholderSet()));
        metrics.increment("wholeObjectStore.cyclicProviderProofsRetained");
        return retained;
    }

    /**
     * Retains the materialized semantic representation selected by the
     * embedded-layout boundary without changing the compact provider view.
     *
     * <p>Reference substitution permits a managed document body and a shell
     * containing exact child references to share one BlueId. A processor may
     * encounter the shell first while restoring an exact patch. Once the
     * layout has authenticated and materialized every declared managed child,
     * that richer representation must become the canonical API/read model;
     * the independently selected provider representation remains unchanged.</p>
     */
    synchronized ExactValue preferCanonicalRepresentation(
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
                    "Canonical preference must contain an exact object body");
        }
        if (canonical.frozen().sameResolvedStructure(preferred.frozen())) {
            return canonical;
        }
        recordBeforeMutation(preferred.blueId());
        canonicalByBlueId.put(preferred.blueId(), preferred);
        purposeByBlueId.put(preferred.blueId(), sanitize(purpose));
        metrics.increment("wholeObjectStore.canonicalRepresentationsPreferred");
        return preferred;
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
        recordBeforeMutation(preferred.blueId());
        providerByBlueId.put(preferred.blueId(), preferred);
        purposeByBlueId.put(preferred.blueId(), sanitize(purpose));
        metrics.increment("wholeObjectStore.providerRepresentationsPreferred");
    }

    /**
     * Retains the complete cyclic proof already authenticated by one committed
     * Contracts result. The proof shares the surrounding object-store
     * savepoint, so a failed Coordination publication cannot leak provider
     * evidence for an unpublished component state.
     */
    synchronized void retainVerifiedClosureProofs(
            ClosureProcessResult result) {
        ClosureProcessResult verified = Objects.requireNonNull(
                result, "result");
        if (!verified.commits()) {
            throw new IllegalArgumentException(
                    "Only a committed closure result can retain cyclic proof");
        }
        for (ComponentSnapshot component : verified.resultingComponents()) {
            if (component.kind() != ComponentKind.CYCLIC) {
                continue;
            }
            String masterBlueId = component.masterBlueId();
            CyclicSetProof proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                    component.completeCyclicProof()
                            .declaredPlaceholderSet());
            recordProofBeforeMutation(masterBlueId);
            cyclicProofByMasterBlueId.put(masterBlueId, proof);
            metrics.increment("wholeObjectStore.cyclicProofsRetained");
        }
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

    /**
     * Returns whether the verified provider owns complete ordinary content
     * for one exact identity.
     *
     * <p>This deliberately excludes pure references and cyclic members.  A
     * caller may use the positive result to substitute an inline ordinary
     * exact subtree with its representation-equivalent pure reference; a
     * cyclic member has no independently verifiable ordinary body.</p>
     */
    synchronized boolean hasCompleteOrdinaryProviderBody(String blueId) {
        ExactValue provider = providerByBlueId.get(Objects.requireNonNull(
                blueId, "blueId"));
        return provider != null
                && !provider.isCyclicMember()
                && !provider.frozen().isReferenceOnly()
                && provider.blueId().equals(provider.frozen().blueId());
    }

    /** Returns the retained provider body under its authoritative identity. */
    synchronized ExactValue requireProviderRepresentation(
            ExactValue authoritative) {
        ExactValue selected = Objects.requireNonNull(
                authoritative, "authoritative");
        ExactValue provider = providerByBlueId.get(selected.blueId());
        if (provider == null || provider.frozen().isReferenceOnly()) {
            throw new IllegalStateException(
                    "No complete provider representation for "
                            + selected.blueId());
        }
        if (!selected.isCyclicMember()) {
            return provider;
        }
        CyclicSetProof proof = cyclicProofByMasterBlueId.get(
                BlueIds.cyclicSetMasterBlueId(selected.blueId()));
        if (proof == null) {
            throw new IllegalStateException(
                    "No retained complete cyclic proof for "
                            + selected.blueId());
        }
        try {
            return ExactValue.fromVerifiedProviderEvidence(
                    selected.blueId(), provider.copyNode(), proof);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "Retained provider representation is inconsistent with "
                            + selected.blueId(),
                    invalid);
        }
    }

    public synchronized int size() {
        return canonicalByBlueId.size();
    }

    synchronized void forceProviderUnavailable(String blueId) {
        unavailableProviderBlueIds.add(Objects.requireNonNull(
                blueId, "blueId"));
    }

    synchronized void restoreProviderAvailability(String blueId) {
        unavailableProviderBlueIds.remove(Objects.requireNonNull(
                blueId, "blueId"));
    }

    /** Opens an O(1) nested savepoint; only later changed keys are journaled. */
    public synchronized Mark mark() {
        Mark mark = new Mark();
        activeMarks.add(mark);
        metrics.increment("wholeObjectStore.marksOpened");
        return mark;
    }

    /** Commits one nested savepoint without copying or rewriting stored bodies. */
    public synchronized void commit(Mark mark) {
        requireTopMark(mark);
        activeMarks.remove(activeMarks.size() - 1);
        mark.close();
        metrics.add("wholeObjectStore.markKeysCommitted",
                mark.changedKeyCount());
    }

    /** Restores only keys changed after the supplied nested savepoint. */
    public synchronized void rollbackTo(Mark mark) {
        requireTopMark(mark);
        List<Map.Entry<String, PriorState>> changes = new ArrayList<>(
                mark.priorByBlueId.entrySet());
        for (int index = changes.size() - 1; index >= 0; index--) {
            Map.Entry<String, PriorState> change = changes.get(index);
            restore(canonicalByBlueId, change.getKey(),
                    change.getValue().canonical());
            restore(providerByBlueId, change.getKey(),
                    change.getValue().provider());
            restore(purposeByBlueId, change.getKey(),
                    change.getValue().purpose());
        }
        List<Map.Entry<String, Prior<CyclicSetProof>>> proofChanges =
                new ArrayList<>(mark.priorProofByMasterBlueId.entrySet());
        for (int index = proofChanges.size() - 1; index >= 0; index--) {
            Map.Entry<String, Prior<CyclicSetProof>> change =
                    proofChanges.get(index);
            restore(cyclicProofByMasterBlueId,
                    change.getKey(), change.getValue());
        }
        activeMarks.remove(activeMarks.size() - 1);
        mark.close();
        metrics.add("wholeObjectStore.markKeysRolledBack",
                Math.addExact(changes.size(), proofChanges.size()));
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

    @Override
    public synchronized NodeProviderResult fetchResultByBlueId(
            String blueId) {
        if (unavailableProviderBlueIds.contains(Objects.requireNonNull(
                blueId, "blueId"))) {
            return NodeProviderResult.unavailable(
                    "Test-controlled exact resource is unavailable");
        }
        return NodeProvider.super.fetchResultByBlueId(blueId);
    }

    @Override
    public synchronized boolean hasVerifiedContentForBlueId(String blueId) {
        String selected = Objects.requireNonNull(blueId, "blueId");
        ExactValue provider = providerByBlueId.get(selected);
        if (unavailableProviderBlueIds.contains(selected)
                || provider == null
                || provider.frozen().isReferenceOnly()) {
            return false;
        }
        if (!BlueIds.hasCyclicMemberSeparator(selected)) {
            return true;
        }
        return cyclicProofByMasterBlueId.containsKey(
                        BlueIds.cyclicSetMasterBlueId(selected));
    }

    @Override
    public synchronized CyclicSetProofResult cyclicSetProofFor(
            String blueId) {
        String selected = Objects.requireNonNull(blueId, "blueId");
        if (unavailableProviderBlueIds.contains(selected)) {
            return CyclicSetProofResult.unavailable(
                    "Test-controlled exact resource is unavailable");
        }
        CyclicSetProof proof = cyclicProofByMasterBlueId.get(
                BlueIds.cyclicSetMasterBlueId(selected));
        if (proof == null) {
            return CyclicSetProofResult.notFound();
        }
        ExactValue provider = providerByBlueId.get(selected);
        if (provider != null && !provider.frozen().isReferenceOnly()) {
            try {
                ExactValue.fromVerifiedProviderEvidence(
                        selected, provider.copyNode(), proof);
            } catch (IllegalArgumentException invalid) {
                return CyclicSetProofResult.invalidEvidence(
                        "Retained cyclic proof is inconsistent with "
                                + selected + ": " + invalid.getMessage());
            }
        }
        metrics.increment("wholeObjectStore.cyclicProofReads");
        return CyclicSetProofResult.found(
                CyclicSetProof.fromDeclaredPlaceholderSet(
                        proof.declaredPlaceholderSet()));
    }

    private static String sanitize(String purpose) {
        String checked = Objects.requireNonNull(purpose, "purpose").trim();
        if (checked.isEmpty()) {
            return "unspecified";
        }
        return checked.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void recordBeforeMutation(String blueId) {
        if (activeMarks.isEmpty()) {
            return;
        }
        PriorState prior = new PriorState(
                prior(canonicalByBlueId, blueId),
                prior(providerByBlueId, blueId),
                prior(purposeByBlueId, blueId));
        for (Mark mark : activeMarks) {
            mark.record(blueId, prior);
        }
    }

    private void recordProofBeforeMutation(String masterBlueId) {
        if (activeMarks.isEmpty()) {
            return;
        }
        Prior<CyclicSetProof> prior = prior(
                cyclicProofByMasterBlueId, masterBlueId);
        for (Mark mark : activeMarks) {
            mark.recordProof(masterBlueId, prior);
        }
    }

    private void requireTopMark(Mark mark) {
        Mark checked = Objects.requireNonNull(mark, "mark");
        if (activeMarks.isEmpty()
                || activeMarks.get(activeMarks.size() - 1) != checked
                || !checked.active) {
            throw new IllegalStateException(
                    "Whole-object savepoints must close in nested order");
        }
    }

    private static <T> Prior<T> prior(Map<String, T> source, String key) {
        return new Prior<>(source.containsKey(key), source.get(key));
    }

    private static <T> void restore(
            Map<String, T> target,
            String key,
            Prior<T> prior) {
        if (prior.present()) {
            target.put(key, prior.value());
        } else {
            target.remove(key);
        }
    }

    /** Delta-scoped nested object-store savepoint. */
    static final class Mark {
        private final Map<String, PriorState> priorByBlueId =
                new LinkedHashMap<>();
        private final Map<String, Prior<CyclicSetProof>>
                priorProofByMasterBlueId = new LinkedHashMap<>();
        private boolean active = true;

        private void record(String blueId, PriorState prior) {
            if (!active) {
                throw new IllegalStateException("Object-store mark is closed");
            }
            priorByBlueId.putIfAbsent(blueId, prior);
        }

        private void recordProof(
                String masterBlueId,
                Prior<CyclicSetProof> prior) {
            if (!active) {
                throw new IllegalStateException("Object-store mark is closed");
            }
            priorProofByMasterBlueId.putIfAbsent(masterBlueId, prior);
        }

        int changedKeyCount() {
            return Math.addExact(
                    priorByBlueId.size(),
                    priorProofByMasterBlueId.size());
        }

        private void close() {
            active = false;
        }
    }

    private record Prior<T>(boolean present, T value) {
    }

    private record PriorState(
            Prior<ExactValue> canonical,
            Prior<ExactValue> provider,
            Prior<String> purpose) {
    }
}
