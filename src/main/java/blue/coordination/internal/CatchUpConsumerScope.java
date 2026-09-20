package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import java.util.Objects;
import java.util.Set;

/** Exact included owners for a rooted stage, or the legacy drain's exclusions. */
record CatchUpConsumerScope(boolean included, Set<DocumentId> documents) {
    CatchUpConsumerScope { documents = Set.copyOf(Objects.requireNonNull(documents)); }
    static CatchUpConsumerScope owners(Set<DocumentId> owners) {
        if (owners.isEmpty()) throw new IllegalArgumentException("A rooted consumer scope needs an owner");
        return new CatchUpConsumerScope(true, owners);
    }
    static CatchUpConsumerScope excluding(Set<DocumentId> excluded) { return new CatchUpConsumerScope(false, excluded); }
    boolean contains(DocumentId document) { return included == documents.contains(document); }
}
