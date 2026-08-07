package blue.coordination.processor;

import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of one revision-bound subscription projection update.
 *
 * <p>A changed domain, header, dependency set, type, or subscription-key set
 * appears as one retirement and one addition. Unchanged occurrences preserve
 * their activation interval. The contained snapshot is the exact resulting
 * active surface.</p>
 */
public final class CoordinationSubscriptionUpdate {
    private final CoordinationSubscriptionSnapshot snapshot;
    private final List<CoordinationSubscriptionOccurrence> added;
    private final List<CoordinationSubscriptionOccurrence> retired;
    private final List<CoordinationSubscriptionOccurrence> unchanged;
    private final ExternalOrderKey transitionOrderKey;
    private final EffectiveFragmentationCatalog fragmentationCatalog;

    CoordinationSubscriptionUpdate(
            CoordinationSubscriptionSnapshot snapshot,
            List<CoordinationSubscriptionOccurrence> added,
            List<CoordinationSubscriptionOccurrence> retired,
            List<CoordinationSubscriptionOccurrence> unchanged,
            ExternalOrderKey transitionOrderKey) {
        this(
                snapshot,
                added,
                retired,
                unchanged,
                transitionOrderKey,
                null);
    }

    CoordinationSubscriptionUpdate(
            CoordinationSubscriptionSnapshot snapshot,
            List<CoordinationSubscriptionOccurrence> added,
            List<CoordinationSubscriptionOccurrence> retired,
            List<CoordinationSubscriptionOccurrence> unchanged,
            ExternalOrderKey transitionOrderKey,
            EffectiveFragmentationCatalog fragmentationCatalog) {
        this.snapshot =
                Objects.requireNonNull(snapshot, "snapshot");
        this.added = immutable(added, "added");
        this.retired = immutable(retired, "retired");
        this.unchanged =
                immutable(unchanged, "unchanged");
        this.transitionOrderKey =
                Objects.requireNonNull(
                        transitionOrderKey,
                        "transitionOrderKey");
        this.fragmentationCatalog = fragmentationCatalog;
        if (fragmentationCatalog != null
                && !snapshot.rootBlueId().equals(
                        fragmentationCatalog.rootBlueId())) {
            throw new IllegalArgumentException(
                    "Fragmentation catalog does not match the resulting "
                            + "subscription Root");
        }
    }

    /**
     * Creates the exact no-change projection used by a progress-only commit.
     *
     * <p>The retained snapshot is not re-projected and its activation
     * frontier remains unchanged. The supplied order belongs to terminal
     * delivery progress, not to a new Root observation.</p>
     */
    public static CoordinationSubscriptionUpdate unchanged(
            CoordinationSubscriptionSnapshot snapshot,
            ExternalOrderKey transitionOrderKey) {
        return new CoordinationSubscriptionUpdate(
                Objects.requireNonNull(snapshot, "snapshot"),
                Collections.<CoordinationSubscriptionOccurrence>emptyList(),
                Collections.<CoordinationSubscriptionOccurrence>emptyList(),
                snapshot.occurrences(),
                Objects.requireNonNull(
                        transitionOrderKey, "transitionOrderKey"));
    }

    /** @return exact resulting active subscription snapshot */
    public CoordinationSubscriptionSnapshot snapshot() {
        return snapshot;
    }

    /** @return newly activated occurrences in canonical order */
    public List<CoordinationSubscriptionOccurrence> added() {
        return added;
    }

    /** @return retired occurrences in canonical order */
    public List<CoordinationSubscriptionOccurrence> retired() {
        return retired;
    }

    /** @return retained occurrences in canonical order */
    public List<CoordinationSubscriptionOccurrence> unchanged() {
        return unchanged;
    }

    /** @return exact order key closing/opening the intervals */
    public ExternalOrderKey transitionOrderKey() {
        return transitionOrderKey;
    }

    /**
     * Returns the immutable effective catalog already established while
     * projecting this resulting Root, when available.
     *
     * <p>Legacy and manually constructed updates do not carry this optional
     * planning evidence. Consumers must retain their ordinary catalog lookup
     * as a fallback and must independently verify the catalog's Root binding
     * before use.</p>
     *
     * @return optional Root-bound effective fragmentation catalog
     */
    public Optional<EffectiveFragmentationCatalog> fragmentationCatalog() {
        return Optional.ofNullable(fragmentationCatalog);
    }

    private static List<CoordinationSubscriptionOccurrence> immutable(
            List<CoordinationSubscriptionOccurrence> supplied,
            String label) {
        List<CoordinationSubscriptionOccurrence> copy =
                new ArrayList<
                        CoordinationSubscriptionOccurrence>(
                        Objects.requireNonNull(
                                supplied, label));
        for (CoordinationSubscriptionOccurrence occurrence
                : copy) {
            Objects.requireNonNull(
                    occurrence,
                    label + " occurrence");
        }
        Collections.sort(
                copy,
                CoordinationSubscriptionOccurrence
                        .CANONICAL_ORDER);
        return Collections.unmodifiableList(copy);
    }
}
