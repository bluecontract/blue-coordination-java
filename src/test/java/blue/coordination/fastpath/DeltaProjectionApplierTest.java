package blue.coordination.fastpath;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
