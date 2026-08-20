package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.List;

/** Live read handle for one independently managed document lineage. */
public interface DocumentHandle {
    /** Stable managed lineage identity. */
    DocumentId id();

    /** Current READY-only application snapshot. */
    DocumentSnapshot snapshot();

    /** Immutable committed revision history in epoch order. */
    List<DocumentRevision> history();

    /** Current exact document value. */
    ExactBlueValue exact();
}
