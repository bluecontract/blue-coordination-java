package blue.coordination.api;

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
public final class ExactValue {
    private final String blueId;
    private final FrozenNode frozen;
    private final ResolvedSnapshot snapshot;

    private ExactValue(
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

    /** Freezes a detached mutable Node and verifies its exact BlueId. */
    public static ExactValue verified(Node exact) {
        FrozenNode frozen = FrozenNode.fromNode(
                Objects.requireNonNull(exact, "exact"));
        return new ExactValue(frozen.blueId(), frozen, null);
    }

    /** Freezes a Node and checks it against an expected exact BlueId. */
    public static ExactValue verified(String expectedBlueId, Node exact) {
        String expected = requireText(expectedBlueId, "expectedBlueId");
        FrozenNode frozen = FrozenNode.fromNode(
                Objects.requireNonNull(exact, "exact"));
        String actual = frozen.blueId();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Exact value identity mismatch: expected " + expected
                            + ", actual " + actual);
        }
        return new ExactValue(expected, frozen, null);
    }

    /** Retains an existing immutable Language snapshot without re-freezing it. */
    public static ExactValue fromSnapshot(ResolvedSnapshot snapshot) {
        ResolvedSnapshot exact = Objects.requireNonNull(snapshot, "snapshot");
        return new ExactValue(
                exact.blueId(), exact.frozenCanonicalRoot(), exact);
    }

    /** Retains an already strict canonical frozen value without materializing. */
    public static ExactValue fromFrozen(FrozenNode frozen) {
        FrozenNode exact = Objects.requireNonNull(frozen, "frozen");
        return new ExactValue(exact.blueId(), exact, null);
    }

    /** Returns the content-addressed identity of the whole exact value. */
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

    /** Returns the shareable immutable frozen representation. */
    public FrozenNode frozen() {
        return frozen;
    }

    /** Returns the retained resolver snapshot when one was available. */
    public Optional<ResolvedSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    /** Selects an immutable canonical value by JSON Pointer. */
    public FrozenNode canonicalAt(String pointer) {
        String canonical = JsonPointer.canonicalize(
                Objects.requireNonNull(pointer, "pointer"));
        return snapshot != null
                ? snapshot.canonicalAt(canonical)
                : frozen.pathIndex().get(canonical);
    }

    /** Returns the selected canonical BlueId, or null when the path is absent. */
    public String canonicalBlueIdAt(String pointer) {
        FrozenNode selected = canonicalAt(pointer);
        return selected == null ? null : selected.blueId();
    }

    /** Compares exact identity and resolved immutable structure. */
    public boolean sameExactValue(ExactValue other) {
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
