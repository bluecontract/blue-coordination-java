package blue.coordination.examples.support;

import blue.language.model.wire.JsonPointer;

import java.util.Objects;

/** One explicit, versioned, host-owned managed-embedding relationship. */
public record MyOsTopologyLink(
        MyOsDocumentIdentity parent,
        String relativePath,
        MyOsDocumentIdentity child,
        long activationJournalSequence,
        long parentGeneration) {

    public MyOsTopologyLink {
        Objects.requireNonNull(parent, "parent");
        relativePath = JsonPointer.canonicalize(
                Objects.requireNonNull(relativePath, "relativePath"));
        if (relativePath.isEmpty()) {
            throw new IllegalArgumentException("A child cannot replace Root");
        }
        Objects.requireNonNull(child, "child");
        if (activationJournalSequence < 0L || parentGeneration < 0L) {
            throw new IllegalArgumentException("Negative topology position");
        }
    }
}
