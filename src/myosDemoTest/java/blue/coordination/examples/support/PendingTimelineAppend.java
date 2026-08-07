package blue.coordination.examples.support;

import java.util.Objects;

/**
 * Fully canonicalized Timeline append that has not yet been made visible.
 *
 * <p>The exact event and its order are immutable.  Preparing this value does
 * not advance either the Timeline head or the runtime timestamp sequence.</p>
 */
final class PendingTimelineAppend {

    private final MyOsDemoTimeline owner;
    private final MyOsDemoEntry entry;
    private final String expectedPreviousBlueId;
    private final long expectedPublicationVersion;
    private final long resultingPublicationVersion;

    PendingTimelineAppend(
            MyOsDemoTimeline owner,
            MyOsDemoEntry entry,
            String expectedPreviousBlueId) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.expectedPreviousBlueId = expectedPreviousBlueId;
        this.expectedPublicationVersion = owner.publicationVersion();
        this.resultingPublicationVersion = owner.nextPublicationVersion();
    }

    MyOsDemoTimeline owner() {
        return owner;
    }

    MyOsDemoEntry entry() {
        return entry;
    }

    String expectedPreviousBlueId() {
        return expectedPreviousBlueId;
    }

    long expectedPublicationVersion() {
        return expectedPublicationVersion;
    }

    long resultingPublicationVersion() {
        return resultingPublicationVersion;
    }
}
