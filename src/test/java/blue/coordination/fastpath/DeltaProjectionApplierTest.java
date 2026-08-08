package blue.coordination.fastpath;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DeltaProjectionApplierTest {
    @Test
    void refreshesOnlyAffectedRetainedOccurrence() {
        AdmittedProjection previous = new AdmittedProjection(
                FastPathFixtures.generation(1L),
                Arrays.asList(
                        FastPathFixtures.occurrence(1, "/orders/a"),
                        FastPathFixtures.occurrence(2, "/orders/b")));
        AdmittedOccurrence oldFirst = previous.requirePublic("public-1");
        ProjectionDelta delta = FastPathFixtures.dependencyRefresh(
                oldFirst, "/orders/a/contracts");

        AdmittedProjection result = new DeltaProjectionApplier().apply(
                previous, FastPathFixtures.generation(2L), delta);

        assertNotEquals(oldFirst.semanticFingerprint(),
                result.requirePublic("public-1").semanticFingerprint());
        assertEquals(previous.requirePublic("public-2"),
                result.requirePublic("public-2"));
    }

    @Test
    void refusesFastProjectionWhenAffectedRetainedEvidenceIsMissing() {
        AdmittedProjection previous = FastPathFixtures.projection(3, 1L);
        ProjectionDelta incomplete = new ProjectionDelta(
                Collections.<AdmittedOccurrence>emptyList(),
                Collections.<String>emptyList(),
                Collections.<AdmittedOccurrence>emptyList(),
                Collections.singletonList("/orders/order-1"),
                true);
        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> new DeltaProjectionApplier().apply(
                        previous, FastPathFixtures.generation(2L), incomplete));
    }

    @Test
    void refusesFastProjectionWhenCompanionEvidenceIsNotComplete() {
        AdmittedProjection previous = FastPathFixtures.projection(1, 1L);
        ProjectionDelta incomplete = new ProjectionDelta(
                Collections.<AdmittedOccurrence>emptyList(),
                Collections.<String>emptyList(),
                Collections.<AdmittedOccurrence>emptyList(),
                Collections.singletonList("/orders/order-0"),
                false);
        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> new DeltaProjectionApplier().apply(
                        previous, FastPathFixtures.generation(2L), incomplete));
    }

    @Test
    void mixedSparseSuccessorMatchesColdProjectionWithoutVisitingUnrelatedRows() {
        AdmittedProjection previous = FastPathFixtures.projection(64, 1L);
        AdmittedOccurrence oldRefreshed = previous.requirePublic("public-1");
        AdmittedOccurrence refreshed = oldRefreshed.withDependencyEvidence(
                "header-1-refreshed",
                "checkpoint-1-refreshed",
                Collections.singletonList("dependency-1-refreshed"),
                Collections.singletonList("/next/refreshed"));
        AdmittedOccurrence added = FastPathFixtures.occurrence(
                100, "/orders/new");
        ProjectionDelta delta = new ProjectionDelta(
                Collections.singletonList(added),
                Collections.singletonList("public-2"),
                Collections.singletonList(refreshed),
                Collections.singletonList("/orders/order-1/contracts/detail"),
                true);
        ProjectionGenerationKey generation = FastPathFixtures.generation(2L);
        List<AdmittedOccurrence> expectedRows =
                new ArrayList<AdmittedOccurrence>(previous.occurrences());
        expectedRows.remove(previous.requirePublic("public-2"));
        expectedRows.remove(oldRefreshed);
        expectedRows.add(refreshed);
        expectedRows.add(added);
        Collections.reverse(expectedRows);
        AdmittedProjection cold = new AdmittedProjection(
                generation, expectedRows);
        FastPathWorkMetrics metrics = new FastPathWorkMetrics();

        AdmittedProjection result = new DeltaProjectionApplier(metrics).apply(
                previous, generation, delta);
        FastPathWorkMetrics.Snapshot work = metrics.snapshot();

        assertEquals(cold.occurrences(), result.occurrences());
        assertEquals(cold.projectionIdentity(), result.projectionIdentity());
        assertEquals(cold.estimatedWeight(), result.estimatedWeight());
        assertSame(previous.requirePublic("public-63"),
                result.requirePublic("public-63"));
        assertSame(refreshed, result.requirePublic("public-1"));
        assertSame(refreshed, result.requireLanguage(refreshed.languageKey()));
        assertSame(added, result.requireLanguage(added.languageKey()));
        assertEquals(
                cold.candidatesForSubscriptionKeys(
                        Arrays.asList("timeline:0", "timeline:1")),
                result.candidatesForSubscriptionKeys(
                        Arrays.asList("timeline:1", "timeline:0")));
        assertTrue(result.affectedOccurrences(
                Collections.singletonList("/orders/order-1")).isEmpty());
        assertEquals(Collections.singleton("public-1"),
                result.affectedOccurrences(
                        Collections.singletonList("/next/refreshed/value")));
        assertEquals(Collections.singleton("public-100"),
                result.affectedOccurrences(
                        Collections.singletonList("/orders/new/contracts")));
        assertThrows(IllegalArgumentException.class,
                () -> result.requirePublic("public-2"));
        assertEquals(3L, work.candidateLookups());
        assertEquals(1L, work.deltaProjectionUpdates());
        assertEquals(1L, work.affectedOccurrences());
        assertEquals(1L, work.refreshedOccurrences());
        assertEquals(0L, work.unrelatedOccurrences());
        assertEquals(3L, work.merkleOccurrenceUpdates());
    }

    @Test
    void removesAllRefreshRowsBeforeInsertingCanonicalSwaps() {
        AdmittedOccurrence first = FastPathFixtures.occurrence(1, "/a");
        AdmittedOccurrence second = FastPathFixtures.occurrence(2, "/b");
        AdmittedProjection previous = new AdmittedProjection(
                FastPathFixtures.generation(1L), Arrays.asList(first, second));
        AdmittedOccurrence movedFirst = movedTo(first, second);
        AdmittedOccurrence movedSecond = movedTo(second, first);
        ProjectionDelta swap = new ProjectionDelta(
                Collections.<AdmittedOccurrence>emptyList(),
                Collections.<String>emptyList(),
                Arrays.asList(movedSecond, movedFirst),
                Collections.<String>emptyList(),
                true);
        ProjectionGenerationKey generation = FastPathFixtures.generation(2L);

        AdmittedProjection result = new DeltaProjectionApplier().apply(
                previous, generation, swap);
        AdmittedProjection cold = new AdmittedProjection(
                generation, Arrays.asList(movedFirst, movedSecond));

        assertEquals(cold.occurrences(), result.occurrences());
        assertEquals(cold.projectionIdentity(), result.projectionIdentity());
        assertSame(movedFirst, result.requirePublic(first.publicKey()));
        assertSame(movedSecond, result.requirePublic(second.publicKey()));
    }

    @Test
    void failedPersistentUpdateDoesNotPublishSuccessMetricsOrMutatePrior() {
        AdmittedOccurrence active = FastPathFixtures.occurrence(1, "/a");
        AdmittedProjection previous = new AdmittedProjection(
                FastPathFixtures.generation(1L),
                Collections.singletonList(active));
        AdmittedOccurrence duplicateLanguage = new AdmittedOccurrence(
                "different-public-key",
                active.scopePath(),
                active.scopeBlueId(),
                active.channelKey(),
                "different-type",
                active.order() + 1,
                "different-header",
                "different-checkpoint",
                active.scopeChainBlueIds(),
                active.sourceContributionBlueIds(),
                active.dependencyBlueIds(),
                active.subscriptionKeys(),
                Collections.singletonList("/different/dependency"));
        ProjectionDelta collision = new ProjectionDelta(
                Collections.singletonList(duplicateLanguage),
                Collections.<String>emptyList(),
                Collections.<AdmittedOccurrence>emptyList(),
                Collections.<String>emptyList(),
                true);
        FastPathWorkMetrics metrics = new FastPathWorkMetrics();
        String priorIdentity = previous.projectionIdentity();

        assertThrows(IllegalArgumentException.class,
                () -> new DeltaProjectionApplier(metrics).apply(
                        previous, FastPathFixtures.generation(2L), collision));

        FastPathWorkMetrics.Snapshot work = metrics.snapshot();
        assertEquals(0L, work.deltaProjectionUpdates());
        assertEquals(0L, work.candidateLookups());
        assertEquals(0L, work.merkleOccurrenceUpdates());
        assertEquals(priorIdentity, previous.projectionIdentity());
        assertSame(active, previous.requirePublic(active.publicKey()));
        assertFalse(previous.occurrences().contains(duplicateLanguage));
    }

    private static AdmittedOccurrence movedTo(
            AdmittedOccurrence occurrence,
            AdmittedOccurrence position) {
        return new AdmittedOccurrence(
                occurrence.publicKey(),
                position.scopePath(),
                position.scopeBlueId(),
                position.channelKey(),
                position.effectiveTypeBlueId(),
                position.order(),
                occurrence.headerIdentityBlueId() + "-moved",
                occurrence.checkpointDomainBlueId() + "-moved",
                position.scopeChainBlueIds(),
                occurrence.sourceContributionBlueIds(),
                occurrence.dependencyBlueIds(),
                occurrence.subscriptionKeys(),
                occurrence.dependencyPaths());
    }
}
