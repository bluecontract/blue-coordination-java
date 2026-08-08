package blue.coordination.engine.fastpath;

import blue.coordination.engine.CoordinationProcessingEngine
        .VerifiedNodeAccessAuthority;
import blue.coordination.processor.CoordinationFragmentAdmissionVerifier;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

import java.util.Objects;

/**
 * Engine-private ownership token for a Node whose direct BlueId was verified
 * once.  A handle must never cross a public boundary because {@link Node} is
 * mutable. Public callers receive a defensive copy. Engine components need
 * both the matching ownership token and the engine's unforgeable
 * {@link VerifiedNodeAccessAuthority} to use the zero-copy path.
 */
public final class ExactNodeHandle {
    private final String blueId;
    private final Node node;
    private final Object owner;
    private volatile CoordinationFragmentAdmissionVerifier
            .PhysicalFragmentEvidence physicalEvidence;

    private ExactNodeHandle(String blueId, Node node, Object owner) {
        this.blueId = requireText(blueId, "blueId");
        this.node = Objects.requireNonNull(node, "node");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** Copies and verifies an untrusted value exactly once. */
    public static ExactNodeHandle copyAndVerify(
            String expectedBlueId, Node supplied, Object owner) {
        String expected = requireText(expectedBlueId, "expectedBlueId");
        Node copy = Objects.requireNonNull(supplied, "supplied").clone();
        String actual = DirectBlueIdCalculator.calculateBlueId(copy);
        if (copy.isReferenceOnly() || !expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Node does not match expected identity " + expected);
        }
        return new ExactNodeHandle(actual, copy, owner);
    }

    /**
     * Adopts a value produced inside one engine request. The caller supplies
     * the identity already calculated while constructing the result. The
     * adoption boundary performs the one mandatory verification.
     */
    public static ExactNodeHandle adoptAndVerify(
            String expectedBlueId, Node requestOwned, Object owner) {
        String expected = requireText(expectedBlueId, "expectedBlueId");
        Node checked = Objects.requireNonNull(requestOwned, "requestOwned");
        String actual = DirectBlueIdCalculator.calculateBlueId(checked);
        if (checked.isReferenceOnly() || !expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Request-owned Node identity mismatch for "
                            + expected);
        }
        return new ExactNodeHandle(actual, checked, owner);
    }

    /** Adopts a value already verified by the request's digest memo. */
    static ExactNodeHandle adoptBound(
            String blueId,
            Node requestOwned,
            Object owner,
            RequestDigestMemo digests) {
        Objects.requireNonNull(digests, "digests").requireBound(
                requestOwned, blueId);
        if (requestOwned.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "An expanded handle cannot contain a pure reference");
        }
        return new ExactNodeHandle(blueId, requestOwned, owner);
    }

    public String blueId() {
        return blueId;
    }

    public Node copy() {
        return node.clone();
    }

    /**
     * Returns a defensive copy after checking the supplied ownership token.
     *
     * <p>This method used to expose the verified mutable instance itself.
     * Keeping the signature while returning a copy preserves source
     * compatibility without allowing a public caller that created its own
     * handle to invalidate the retained identity proof.</p>
     */
    public Node borrow(Object expectedOwner) {
        requireOwner(expectedOwner);
        return node.clone();
    }

    /** Engine-only zero-copy read guarded by an unforgeable authority. */
    public Node borrowVerified(
            Object expectedOwner,
            VerifiedNodeAccessAuthority accessAuthority) {
        requireOwner(expectedOwner);
        Objects.requireNonNull(accessAuthority, "accessAuthority");
        return node;
    }

    /** Package-private zero-copy access for the sealed fast-path layer. */
    Node borrowTrusted(Object expectedOwner) {
        requireOwner(expectedOwner);
        return node;
    }

    public boolean belongsTo(Object expectedOwner) {
        return owner == expectedOwner;
    }

    /**
     * Shares one already verified engine-private immutable value with a new
     * ownership domain. Possession of the current owner capability is
     * required; no public Node or unverifiable identity crosses the boundary.
     */
    public ExactNodeHandle rebind(
            Object expectedOwner, Object newOwner) {
        requireOwner(expectedOwner);
        ExactNodeHandle rebound = new ExactNodeHandle(
                blueId,
                node,
                Objects.requireNonNull(newOwner, "newOwner"));
        rebound.physicalEvidence = physicalEvidence;
        return rebound;
    }

    /**
     * Returns immutable canonical-wire evidence without exposing the Node.
     * The first request serializes once; all later storage checks reuse the
     * retained fingerprint and encoded byte count.
     */
    public CoordinationFragmentAdmissionVerifier.PhysicalFragmentEvidence
            physicalEvidence(
                    Object expectedOwner,
                    VerifiedNodeAccessAuthority accessAuthority) {
        requireOwner(expectedOwner);
        Objects.requireNonNull(accessAuthority, "accessAuthority");
        CoordinationFragmentAdmissionVerifier.PhysicalFragmentEvidence
                current = physicalEvidence;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = physicalEvidence;
            if (current == null) {
                current = CoordinationFragmentAdmissionVerifier
                        .physicalFragmentEvidence(node);
                physicalEvidence = current;
            }
            return current;
        }
    }

    private void requireOwner(Object expectedOwner) {
        if (owner != Objects.requireNonNull(expectedOwner, "expectedOwner")) {
            throw new IllegalArgumentException(
                    "Exact Node belongs to another engine ownership domain");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }
}
