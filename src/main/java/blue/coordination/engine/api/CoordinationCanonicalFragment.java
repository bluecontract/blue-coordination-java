package blue.coordination.engine.api;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** Immutable canonical fragment plus evidence calculated exactly once. */
public final class CoordinationCanonicalFragment {

    private final String blueId;
    private final String canonicalWireFingerprint;
    private final FrozenNode exactFragment;

    CoordinationCanonicalFragment(
            String blueId,
            String canonicalWireFingerprint,
            Node exactFragment) {
        this.blueId = requireText(blueId, "blueId");
        this.canonicalWireFingerprint = requireText(
                canonicalWireFingerprint,
                "canonicalWireFingerprint");
        this.exactFragment = FrozenNode.fromNode(
                Objects.requireNonNull(exactFragment, "exactFragment"));
    }

    public String blueId() {
        return blueId;
    }

    public String canonicalWireFingerprint() {
        return canonicalWireFingerprint;
    }

    /** Returns a caller-owned mutable materialization. */
    public Node materialize() {
        return exactFragment.toNode();
    }

    /** Retained immutable representation for trusted in-process stores. */
    public FrozenNode frozen() {
        return exactFragment;
    }

    /** Conservative immutable graph weight used by bounded admission caches. */
    long approximateRetainedWeightBytes() {
        return exactFragment.approximateRetainedWeightBytes();
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
