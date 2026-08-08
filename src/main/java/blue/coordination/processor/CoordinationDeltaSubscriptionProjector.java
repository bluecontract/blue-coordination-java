package blue.coordination.processor;

import blue.coordination.fastpath.DeltaProjectionApplier;
import blue.coordination.fastpath.FastPathWorkMetrics;
import blue.coordination.fastpath.PathDependencyIndex;
import blue.language.processor.SubscriptionDelta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * O(active + delta) in-memory assembly with no Root traversal, resolution,
 * catalog construction or full semantic subscription projection.
 *
 * <p>The O(active) portion is inexpensive immutable snapshot publication. The
 * expensive fields for unchanged occurrences are structurally shared. A
 * producer that cannot prove a complete affected set must invoke the existing
 * cold projector instead.</p>
 */
public final class CoordinationDeltaSubscriptionProjector {
    private final FastPathWorkMetrics metrics;

    public CoordinationDeltaSubscriptionProjector() {
        this(new FastPathWorkMetrics());
    }

    public CoordinationDeltaSubscriptionProjector(
            FastPathWorkMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public CoordinationSubscriptionUpdate apply(
            CoordinationSubscriptionSnapshot previous,
            CoordinationCommitProjectionEvidence supplied) {
        CoordinationSubscriptionSnapshot prior = Objects.requireNonNull(
                previous, "previous");
        CoordinationCommitProjectionEvidence evidence = Objects.requireNonNull(
                supplied, "evidence");
        if (!evidence.complete()) {
            throw new DeltaProjectionApplier.ColdProjectionRequiredException(
                    "commit projection evidence is incomplete");
        }
        if (evidence.resultingRootRevision() != prior.rootRevision() + 1L) {
            throw new IllegalArgumentException(
                    "resulting revision must be the exact successor");
        }
        if (evidence.transitionOrderKey().compareTo(
                prior.activationFrontier()) <= 0) {
            throw new IllegalArgumentException(
                    "transition order must advance the projection frontier");
        }

        Map<String, CoordinationSubscriptionOccurrence> refreshed =
                byInternalKey(evidence.currentEvidence());
        Set<String> affectedPublic = evidence.affectedRetainedOccurrenceKeys();
        Set<String> consumedPublic = new LinkedHashSet<String>();
        Set<String> removedPublic = new LinkedHashSet<String>();
        Set<String> removedInternal = new LinkedHashSet<String>();
        List<CoordinationSubscriptionOccurrence> retired =
                new ArrayList<CoordinationSubscriptionOccurrence>();
        PathDependencyIndex dependencyIndex =
                prior.dependencyIndexForSuccessor();
        CoordinationSubscriptionMerkleIndex merkleIndex =
                prior.merkleIndexForSuccessor();
        for (SubscriptionDelta.Entry removal
                : evidence.membershipDelta().removed()) {
            if (!Long.valueOf(evidence.resultingRootRevision()).equals(
                    removal.endAtRootRevision())) {
                throw new IllegalArgumentException(
                        "retirement does not close at resulting revision");
            }
            String internal = internalKey(removal);
            CoordinationSubscriptionOccurrence old =
                    prior.occurrenceByInternalKey(
                            removal.scopePath(), removal.channelKey());
            if (old == null || !removedInternal.add(internal)
                    || !removedPublic.add(old.occurrenceKey())) {
                throw new IllegalArgumentException(
                        "membership delta retires inactive occurrence at " + internal);
            }
            requireSameMembership(old.toSubscriptionDeltaEntry(), removal, true);
            retired.add(old.withScopeAndInterval(old.scopeBlueId(), removal));
            dependencyIndex = dependencyIndex.updated(
                    old.occurrenceKey(),
                    CoordinationSubscriptionSnapshot.exactDependencyPaths(old),
                    Collections.<String>emptySet());
            merkleIndex = merkleIndex.updated(old, null);
        }

        Map<String, CoordinationSubscriptionOccurrence> replacements =
                new LinkedHashMap<String, CoordinationSubscriptionOccurrence>();
        for (String publicKey : affectedPublic) {
            CoordinationSubscriptionOccurrence old = prior.occurrence(publicKey);
            if (old == null || removedPublic.contains(publicKey)) {
                throw new IllegalArgumentException(
                        "affected set contains unknown occurrence: " + publicKey);
            }
            CoordinationSubscriptionOccurrence current = refreshed.remove(
                    internalKey(old.toSubscriptionDeltaEntry()));
            if (current == null) {
                throw new DeltaProjectionApplier.ColdProjectionRequiredException(
                        "affected retained occurrence lacks current evidence: "
                                + publicKey);
            }
            requireRetainedInterval(old, current);
            replacements.put(publicKey, current);
            consumedPublic.add(publicKey);
            dependencyIndex = dependencyIndex.updated(
                    publicKey,
                    CoordinationSubscriptionSnapshot.exactDependencyPaths(old),
                    CoordinationSubscriptionSnapshot.exactDependencyPaths(current));
            merkleIndex = merkleIndex.updated(old, current);
        }

        CoordinationSubscriptionMerkleIndex retainedIndex = merkleIndex;
        List<CoordinationSubscriptionOccurrence> added =
                new ArrayList<CoordinationSubscriptionOccurrence>();
        for (SubscriptionDelta.Entry addition
                : evidence.membershipDelta().added()) {
            String internal = internalKey(addition);
            CoordinationSubscriptionOccurrence existing =
                    prior.occurrenceByInternalKey(
                            addition.scopePath(), addition.channelKey());
            if (existing != null && !removedInternal.contains(internal)) {
                throw new IllegalArgumentException(
                        "membership delta adds active occurrence at " + internal);
            }
            CoordinationSubscriptionOccurrence current = refreshed.remove(internal);
            if (current == null) {
                throw new DeltaProjectionApplier.ColdProjectionRequiredException(
                        "new active occurrence lacks exact current evidence at " + internal);
            }
            requireSameMembership(current.toSubscriptionDeltaEntry(), addition, false);
            added.add(current);
            dependencyIndex = dependencyIndex.updated(
                    current.occurrenceKey(),
                    Collections.<String>emptySet(),
                    CoordinationSubscriptionSnapshot.exactDependencyPaths(current));
            merkleIndex = merkleIndex.updated(null, current);
        }
        if (!refreshed.isEmpty()) {
            throw new IllegalArgumentException(
                    "current evidence contains unaffected occurrence(s): "
                            + refreshed.keySet());
        }
        if (consumedPublic.size() != affectedPublic.size()) {
            throw new IllegalStateException(
                    "affected occurrence accounting is inconsistent");
        }

        List<CoordinationSubscriptionOccurrence> unchanged =
                retainedIndex.occurrences();

        CoordinationSubscriptionSnapshot snapshot =
                new CoordinationSubscriptionSnapshot(
                        prior.languageRuntimeRegistryIdentity(),
                        prior.coordinationRuntimeRegistryIdentity(),
                        evidence.resultingRootBlueId(),
                        evidence.resultingRootRevision(),
                        evidence.transitionOrderKey(),
                        Collections.<CoordinationSubscriptionOccurrence>emptyList(),
                        evidence.processEmbeddedRoutes(),
                        evidence.prunedScopePaths(),
                        dependencyIndex,
                        merkleIndex,
                        metrics);
        metrics.candidatesLookedUp(
                affectedPublic.size()
                        + evidence.membershipDelta().removed().size()
                        + evidence.membershipDelta().added().size());
        metrics.deltaProjectionUpdated(
                affectedPublic.size(),
                replacements.size(),
                0L);
        metrics.merkleOccurrencesUpdated(
                removedPublic.size() + replacements.size() + added.size());
        return new CoordinationSubscriptionUpdate(
                snapshot,
                added,
                retired,
                unchanged,
                evidence.transitionOrderKey(),
                evidence.fragmentationCatalog());
    }

    private static Map<String, CoordinationSubscriptionOccurrence> byInternalKey(
            List<CoordinationSubscriptionOccurrence> values) {
        Map<String, CoordinationSubscriptionOccurrence> result =
                new LinkedHashMap<String, CoordinationSubscriptionOccurrence>();
        for (CoordinationSubscriptionOccurrence value : values) {
            String key = internalKey(value.toSubscriptionDeltaEntry());
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate occurrence at " + key);
            }
        }
        return result;
    }

    private static void requireSameMembership(
            SubscriptionDelta.Entry current,
            SubscriptionDelta.Entry delta,
            boolean retirement) {
        if (!current.scopePath().equals(delta.scopePath())
                || !current.channelKey().equals(delta.channelKey())
                || !current.effectiveTypeBlueId().equals(delta.effectiveTypeBlueId())
                || current.order() != delta.order()
                || !current.subscriptionKeys().equals(delta.subscriptionKeys())
                || !current.sourceContributionNodeBlueIds().equals(
                        delta.sourceContributionNodeBlueIds())
                || !current.checkpointDomainBlueId().equals(
                        delta.checkpointDomainBlueId())
                || !current.dependencies().equals(delta.dependencies())
                || !Objects.equals(current.activationRootRevision(),
                        delta.activationRootRevision())
                || !Objects.equals(current.startAfterExternalOrderKey(),
                        delta.startAfterExternalOrderKey())
                || (!retirement && delta.endAtRootRevision() != null)) {
            throw new IllegalArgumentException(
                    "membership evidence mismatch at " + internalKey(delta));
        }
    }

    private static void requireRetainedInterval(
            CoordinationSubscriptionOccurrence old,
            CoordinationSubscriptionOccurrence current) {
        if (!old.occurrenceKey().equals(current.occurrenceKey())
                || !old.scopePath().equals(current.scopePath())
                || !old.channelKey().equals(current.channelKey())
                || !old.effectiveTypeBlueId().equals(
                        current.effectiveTypeBlueId())
                || old.order() != current.order()
                || !old.subscriptionKeys().equals(
                        current.subscriptionKeys())
                || !old.sourceContributionNodeBlueIds().equals(
                        current.sourceContributionNodeBlueIds())
                || !Objects.equals(old.activationRootRevision(),
                        current.activationRootRevision())
                || !Objects.equals(old.activationFrontier(),
                        current.activationFrontier())
                || current.endAtRootRevision() != null) {
            throw new IllegalArgumentException(
                    "refreshed retained evidence changed activation interval: "
                            + old.occurrenceKey());
        }
    }

    private static String internalKey(SubscriptionDelta.Entry entry) {
        return entry.scopePath() + "\u001f" + entry.channelKey();
    }
}
