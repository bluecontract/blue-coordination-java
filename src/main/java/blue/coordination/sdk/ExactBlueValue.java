package blue.coordination.sdk;

import blue.coordination.api.ExactValue;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;

import java.util.Objects;

/** Immutable, content-addressed Blue value exposed by the developer SDK. */
public final class ExactBlueValue {
    private final ExactValue value;
    private volatile VerifiedExactEvidence verifiedEvidence;

    ExactBlueValue(ExactValue value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    static ExactBlueValue wrap(ExactValue value) {
        return new ExactBlueValue(value);
    }

    ExactValue unwrap() {
        return value;
    }

    /** Returns the authoritative exact BlueId. */
    public String blueId() {
        return value.blueId();
    }

    /** Returns whether this is a verified member identity of a cyclic set. */
    public boolean cyclicMember() {
        return value.isCyclicMember();
    }

    /** Returns the verified exact value as detached Blue JSON. */
    public String json() {
        VerifiedExactEvidence retained = verifiedEvidence;
        return retained == null ? UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(value.copyNode())
                : retained.exactContent();
    }

    /**
     * Retains the library's authenticated immutable body and complete proof.
     * This content artifact grants no current storage or publication authority.
     * @return a stable immutable transport artifact for this exact value
     */
    public synchronized VerifiedExactEvidence verifiedEvidence() {
        if (verifiedEvidence == null) verifiedEvidence = VerifiedExactEvidence.issue(value);
        return verifiedEvidence;
    }

    /** Package-private provider transport from authenticated internal state. */
    ExactNodeEvidence providerEvidence() {
        VerifiedExactEvidence retained = verifiedEvidence();
        String content = json();
        return retained.declaredPlaceholderSet()
                .map(proof -> ExactNodeEvidence.cyclic(content, proof))
                .orElseGet(() -> ExactNodeEvidence.ordinary(content));
    }

    /** Package-private mutable copy used only by the SDK implementation. */
    Node copyNode() {
        return value.copyNode();
    }

    ExactBlueValue valueAt(String pointer) {
        Node selected = NodePathEditor.getOrNull(
                value.copyNode(), Objects.requireNonNull(pointer, "pointer"));
        if (selected == null) {
            throw new IllegalArgumentException("No exact value at " + pointer);
        }
        return wrap(ExactValue.verified(selected));
    }

    Object scalarAt(String pointer) {
        Node selected = NodePathEditor.getOrNull(
                value.copyNode(), Objects.requireNonNull(pointer, "pointer"));
        if (selected == null || selected.getValue() == null) {
            throw new IllegalArgumentException("No scalar value at " + pointer);
        }
        return selected.getValue();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ExactBlueValue exact
                && value.sameExactValue(exact.value);
    }

    @Override
    public int hashCode() {
        return blueId().hashCode();
    }

    @Override
    public String toString() {
        return blueId();
    }
}
