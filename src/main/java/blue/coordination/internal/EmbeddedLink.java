package blue.coordination.internal;

import blue.coordination.api.TimelineEntry;

import blue.coordination.api.EnvironmentFrontier;

import blue.coordination.api.DocumentId;

import blue.coordination.api.ActivationMode;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Parent occurrence linked to one independently managed child session. */
final class EmbeddedLink {
    private final DocumentId parentDocumentId;
    private final String occurrencePath;
    private final DocumentId childDocumentId;
    private final ActivationMode activationMode;
    private final TimelineEntry.CatchUpCause cause;
    private final EnvironmentFrontier cutoff;
    private final ExternalOrderKey cutoffOrderKey;
    private long appliedChildEpoch;

    public EmbeddedLink(
            DocumentId parentDocumentId,
            String occurrencePath,
            DocumentId childDocumentId,
            ActivationMode activationMode,
            TimelineEntry.CatchUpCause cause,
            EnvironmentFrontier cutoff,
            ExternalOrderKey cutoffOrderKey,
            long appliedChildEpoch) {
        this.parentDocumentId = Objects.requireNonNull(
                parentDocumentId, "parentDocumentId");
        this.occurrencePath = requireText(occurrencePath, "occurrencePath");
        this.childDocumentId = Objects.requireNonNull(
                childDocumentId, "childDocumentId");
        this.activationMode = Objects.requireNonNull(
                activationMode, "activationMode");
        this.cause = Objects.requireNonNull(cause, "cause");
        this.cutoff = Objects.requireNonNull(cutoff, "cutoff");
        this.cutoffOrderKey = Objects.requireNonNull(
                cutoffOrderKey, "cutoffOrderKey");
        if (appliedChildEpoch < -1L) {
            throw new IllegalArgumentException(
                    "appliedChildEpoch must be at least -1");
        }
        this.appliedChildEpoch = appliedChildEpoch;
    }

    public DocumentId parentDocumentId() {
        return parentDocumentId;
    }

    public String occurrencePath() {
        return occurrencePath;
    }

    public DocumentId childDocumentId() {
        return childDocumentId;
    }

    public ActivationMode activationMode() {
        return activationMode;
    }

    public TimelineEntry.CatchUpCause cause() {
        return cause;
    }

    public EnvironmentFrontier cutoff() {
        return cutoff;
    }

    public ExternalOrderKey cutoffOrderKey() {
        return cutoffOrderKey;
    }

    public synchronized long appliedChildEpoch() {
        return appliedChildEpoch;
    }

    public synchronized void markApplied(long childEpoch) {
        if (childEpoch != appliedChildEpoch + 1L) {
            throw new IllegalStateException(
                    "Child revisions must be applied contiguously: current="
                            + appliedChildEpoch + ", next=" + childEpoch);
        }
        appliedChildEpoch = childEpoch;
    }

    public synchronized EmbeddedLink copy() {
        return new EmbeddedLink(
                parentDocumentId,
                occurrencePath,
                childDocumentId,
                activationMode,
                cause,
                cutoff,
                cutoffOrderKey,
                appliedChildEpoch);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
