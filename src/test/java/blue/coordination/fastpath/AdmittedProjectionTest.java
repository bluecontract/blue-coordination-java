package blue.coordination.fastpath;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdmittedProjectionTest {
    @Test
    void selectsOnlyRequestedRowsAndUsesPrecomputedScopeChains() {
        AdmittedProjection projection = FastPathFixtures.projection(1000, 7L);
        AdmittedProjection.SelectedSurface selected = projection.select(
                Arrays.asList("public-10", "public-11"));

        assertEquals(Arrays.asList("public-10", "public-11"), selected.publicKeys());
        assertEquals(2, selected.scopeChains().size());
        assertTrue(selected.requiredIdentities().contains("dependency-10"));
        assertTrue(selected.requiredIdentities().contains("dependency-11"));
    }

    @Test
    void rejectsStaleAndDuplicateCandidates() {
        AdmittedProjection projection = FastPathFixtures.projection(20, 1L);
        assertThrows(IllegalArgumentException.class,
                () -> projection.select(Collections.singletonList("missing")));
        assertThrows(IllegalArgumentException.class,
                () -> projection.select(Arrays.asList("public-1", "public-1")));
    }

    @Test
    void preservesWadowiceDeepFirstAuthoritativeCandidateOrder() {
        AdmittedOccurrence packagePayment = FastPathFixtures.occurrence(
                1, "/payNotes/packagePayment");
        AdmittedOccurrence refund = FastPathFixtures.occurrence(
                2, "/payNotes/packagePayment/refund");
        AdmittedOccurrence restaurant = FastPathFixtures.occurrence(
                3, "/product/products/restaurant");
        AdmittedProjection projection = new AdmittedProjection(
                FastPathFixtures.generation(1L),
                Arrays.asList(packagePayment, refund, restaurant));

        AdmittedProjection.SelectedSurface selected = projection.select(
                Arrays.asList(
                        refund.publicKey(),
                        packagePayment.publicKey(),
                        restaurant.publicKey()));

        assertEquals(
                Arrays.asList(
                        refund.publicKey(),
                        packagePayment.publicKey(),
                        restaurant.publicKey()),
                selected.publicKeys());
        assertEquals(
                Arrays.asList(
                        refund.languageKey(),
                        packagePayment.languageKey(),
                        restaurant.languageKey()),
                selected.languageKeys());
    }

    @Test
    void exactSubscriptionIndexProducesCanonicalUnionWithoutScanning() {
        AdmittedProjection projection = FastPathFixtures.projection(20, 1L);
        assertEquals(
                Arrays.asList("public-1", "public-13", "public-17", "public-5", "public-9"),
                projection.candidatesForSubscriptionKeys(
                        Collections.singletonList("timeline:1")));
    }

    @Test
    void projectionIdentityIsIndependentOfInputIterationOrder() {
        AdmittedOccurrence first = FastPathFixtures.occurrence(1, "/a");
        AdmittedOccurrence second = FastPathFixtures.occurrence(2, "/b");
        assertEquals(
                new AdmittedProjection(FastPathFixtures.generation(1L),
                        Arrays.asList(first, second)).projectionIdentity(),
                new AdmittedProjection(FastPathFixtures.generation(1L),
                        Arrays.asList(second, first)).projectionIdentity());
    }

    @Test
    void changedPathInvalidationMatchesAncestorsAndDescendants() {
        AdmittedProjection projection = new AdmittedProjection(
                FastPathFixtures.generation(1L),
                Arrays.asList(
                        FastPathFixtures.occurrence(1, "/orders/a"),
                        FastPathFixtures.occurrence(2, "/orders/a/lines/one"),
                        FastPathFixtures.occurrence(3, "/orders/b")));
        assertEquals(
                Arrays.asList("public-1", "public-2"),
                new java.util.ArrayList<String>(projection.affectedOccurrences(
                        Collections.singletonList("/orders/a/lines"))));
    }
}
