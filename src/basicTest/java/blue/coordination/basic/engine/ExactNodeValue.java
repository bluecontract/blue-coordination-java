package blue.coordination.basic.engine;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;
import java.util.Optional;

/**
 * One immutable whole exact Blue object.
 *
 * <p>The value keeps Language's shareable {@link FrozenNode}; when it originated
 * from a complete resolver run it also keeps that {@link ResolvedSnapshot} so
 * canonical/resolved roots, path indexes, provenance, and memoized BlueIds are
 * not discarded at the Coordination boundary.</p>
 */
public final class ExactNodeValue {
    private final String blueId;
    private final FrozenNode frozen;
    private final ResolvedSnapshot snapshot;

    private ExactNodeValue(
            String blueId,
            FrozenNode frozen,
            ResolvedSnapshot snapshot) {
        this.blueId = requireText(blueId, "blueId");
        this.frozen = Objects.requireNonNull(frozen, "frozen");
        this.snapshot = snapshot;
        if (!this.blueId.equals(this.frozen.blueId())) {
            throw new IllegalArgumentException(
                    "Frozen value does not match supplied BlueId");
        }
        if (snapshot != null && !this.blueId.equals(snapshot.blueId())) {
            throw new IllegalArgumentException(
                    "Snapshot does not match supplied BlueId");
        }
    }

    public static ExactNodeValue verified(Node exact) {
        FrozenNode frozen = FrozenNode.fromNode(
                Objects.requireNonNull(exact, "exact"));
        return new ExactNodeValue(frozen.blueId(), frozen, null);
    }

    public static ExactNodeValue verified(String expectedBlueId, Node exact) {
        String expected = requireText(expectedBlueId, "expectedBlueId");
        FrozenNode frozen = FrozenNode.fromNode(
                Objects.requireNonNull(exact, "exact"));
        String actual = frozen.blueId();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Exact value identity mismatch: expected " + expected
                            + ", actual " + actual);
        }
        return new ExactNodeValue(expected, frozen, null);
    }

    /** Retains an existing immutable Language snapshot without re-freezing it. */
    public static ExactNodeValue fromSnapshot(ResolvedSnapshot snapshot) {
        ResolvedSnapshot exact = Objects.requireNonNull(snapshot, "snapshot");
        return new ExactNodeValue(
                exact.blueId(), exact.frozenCanonicalRoot(), exact);
    }

    /** Retains an already strict canonical frozen value without materializing. */
    public static ExactNodeValue fromFrozen(FrozenNode frozen) {
        FrozenNode exact = Objects.requireNonNull(frozen, "frozen");
        return new ExactNodeValue(exact.blueId(), exact, null);
    }

    public String blueId() {
        return blueId;
    }

    /** Returns a detached mutable boundary copy for a frozen public API call. */
    public Node copyNode() {
        return frozen.toNode();
    }

    /** Returns a semantic pure reference to this whole exact object. */
    public Node referenceNode() {
        return new Node().blueId(blueId);
    }

    public FrozenNode frozen() {
        return frozen;
    }

    public Optional<ResolvedSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public FrozenNode canonicalAt(String pointer) {
        String canonical = JsonPointer.canonicalize(
                Objects.requireNonNull(pointer, "pointer"));
        return snapshot != null
                ? snapshot.canonicalAt(canonical)
                : frozen.pathIndex().get(canonical);
    }

    public String canonicalBlueIdAt(String pointer) {
        FrozenNode selected = canonicalAt(pointer);
        return selected == null ? null : selected.blueId();
    }

    public boolean sameExactValue(ExactNodeValue other) {
        return other != null
                && blueId.equals(other.blueId)
                && (frozen == other.frozen
                || frozen.sameResolvedStructure(other.frozen));
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
