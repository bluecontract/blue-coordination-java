package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.model.wire.JsonPointer;

import java.util.Objects;

/**
 * Identity-derived change for the Root or one exact embedded occurrence.
 *
 * <p>The Root uses path {@code /}, origin {@code NONE}, and no activation
 * interval identity. Embedded occurrences retain their declaration origin
 * and interval identity.</p>
 */
public final class CoordinationScopeTransition {

    private final String scopePath;
    private final ChangeKind kind;
    private final String beforeBlueId;
    private final String afterBlueId;
    private final CoordinationDocumentSplitter.EmbeddedEdgeOrigin origin;
    private final String activationIntervalIdentity;

    public CoordinationScopeTransition(
            String scopePath,
            ChangeKind kind,
            String beforeBlueId,
            String afterBlueId,
            CoordinationDocumentSplitter.EmbeddedEdgeOrigin origin,
            String activationIntervalIdentity) {
        this.scopePath = JsonPointer.canonicalize(
                Objects.requireNonNull(scopePath, "scopePath"));
        this.kind = Objects.requireNonNull(kind, "kind");
        this.beforeBlueId = beforeBlueId;
        this.afterBlueId = afterBlueId;
        this.origin = Objects.requireNonNull(origin, "origin");
        this.activationIntervalIdentity = activationIntervalIdentity;
        if ((kind == ChangeKind.ADDED) != (beforeBlueId == null)
                || (kind == ChangeKind.REMOVED) != (afterBlueId == null)) {
            throw new IllegalArgumentException(
                    "Scope transition endpoints disagree with change kind");
        }
        if ((kind == ChangeKind.CHANGED || kind == ChangeKind.UNCHANGED)
                && (beforeBlueId == null || afterBlueId == null)) {
            throw new IllegalArgumentException(
                    "Retained scope transitions require both identities");
        }
    }

    public String scopePath() { return scopePath; }
    public ChangeKind kind() { return kind; }
    public String beforeBlueId() { return beforeBlueId; }
    public String afterBlueId() { return afterBlueId; }
    public CoordinationDocumentSplitter.EmbeddedEdgeOrigin origin() {
        return origin;
    }
    public String activationIntervalIdentity() {
        return activationIntervalIdentity;
    }
}
