package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal pinned physical read view under the existing whole-object machine.
 *
 * <p>One view selects immutable representations, not just equivalent BlueIds.
 * The owner authenticates its root and every stored row and supplies complete
 * point/range indexes. Missing known bytes, failed I/O, or a changed pinned
 * view must throw, never return absence. Opening must not materialize bodies.
 * The owner closes the view after the whole processing attempt, not a read.
 * This is not a public SDK restoration surface or a transaction coordinator.</p>
 */
interface WholeObjectBacking {
    Optional<Entry> find(String blueId);
    Optional<CyclicSetProof> proof(String masterBlueId);
    /** Complete retained provider-member keys for this master only. */
    Iterable<String> cyclicMembers(String masterBlueId);
    int size();

    /** Optional closed-storage path: fresh owned body/proof selection plus pure pair verification. */
    default Optional<Node> verifiedCyclicProviderDocument(String blueId) { return Optional.empty(); }

    /** Canonical and provider representations are deliberately separate. */
    record Entry(ExactValue canonical, ExactValue provider,
                 Node cyclicProviderBody, String purpose) {
        public Entry {
            Objects.requireNonNull(canonical, "canonical");
            Objects.requireNonNull(purpose, "purpose");
            if (provider != null && !canonical.blueId().equals(provider.blueId())) {
                throw new IllegalArgumentException("Object representations disagree on identity");
            }
            if (cyclicProviderBody != null && !canonical.isCyclicMember()) {
                throw new IllegalArgumentException("Ordinary object cannot carry a cyclic provider body");
            }
            cyclicProviderBody = cyclicProviderBody == null ? null : cyclicProviderBody.clone();
        }
        @Override public Node cyclicProviderBody() {
            return cyclicProviderBody == null ? null : cyclicProviderBody.clone();
        }
    }

    /** Attempt-local delta. Publish together with the existing fenced result. */
    record Changes(Map<String, Entry> entries, Map<String, CyclicSetProof> proofs) {
        public Changes {
            entries = Map.copyOf(entries);
            proofs = Map.copyOf(proofs);
        }
    }

    WholeObjectBacking EMPTY = new WholeObjectBacking() {
        @Override public Optional<Entry> find(String blueId) { return Optional.empty(); }
        @Override public Optional<CyclicSetProof> proof(String masterBlueId) { return Optional.empty(); }
        @Override public Iterable<String> cyclicMembers(String masterBlueId) { return List.of(); }
        @Override public int size() { return 0; }
    };
}
