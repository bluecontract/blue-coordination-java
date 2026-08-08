package blue.coordination.fastpath;

import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PlanningFastPathTest {
    @Test
    void exactRetryUsesVerifiedPlanButAnotherEventDoesNot() {
        AdmittedProjection projection = FastPathFixtures.projection(20, 1L);
        PlanningFastPath<String> fastPath = new PlanningFastPath<String>(
                8, 1024L, String::length);
        AtomicInteger semanticCalls = new AtomicInteger();
        PlanCacheKey first = new PlanCacheKey(
                projection.generation(), "session", "event-a", "inventory-a",
                order("order-a"),
                Arrays.asList("public-10", "public-11"), "policy");
        PlanCacheKey second = new PlanCacheKey(
                projection.generation(), "session", "event-b", "inventory-b",
                order("order-b"),
                Arrays.asList("public-10", "public-11"), "policy");

        assertEquals("planned", fastPath.prepare(first, projection, ignored -> {
            semanticCalls.incrementAndGet();
            return "planned";
        }));
        assertEquals("planned", fastPath.prepare(first, projection, ignored -> {
            semanticCalls.incrementAndGet();
            return "wrong";
        }));
        assertEquals("planned", fastPath.prepare(second, projection, ignored -> {
            semanticCalls.incrementAndGet();
            return "planned";
        }));
        assertEquals(2, semanticCalls.get());
    }

    @Test
    void planCannotCrossRootGeneration() {
        AdmittedProjection projection = FastPathFixtures.projection(20, 1L);
        PlanCacheKey foreign = new PlanCacheKey(
                FastPathFixtures.generation(2L), "session", "event", "inventory",
                order("order"),
                Arrays.asList("public-10", "public-11"), "policy");
        assertThrows(IllegalArgumentException.class,
                () -> new PlanningFastPath<String>(8, 1024L, String::length)
                        .prepare(foreign, projection, ignored -> "wrong"));
    }

    @Test
    void successfulCasInvalidatesOnlyObsoleteGeneration() {
        AdmittedProjection projection = FastPathFixtures.projection(20, 1L);
        PlanningFastPath<String> fastPath = new PlanningFastPath<String>(
                8, 1024L, String::length);
        PlanCacheKey key = new PlanCacheKey(
                projection.generation(), "session", "event", "inventory",
                order("order"),
                Arrays.asList("public-10", "public-11"), "policy");
        fastPath.prepare(key, projection, ignored -> "planned");
        assertEquals(1, fastPath.generationCommitted(
                "session", projection.generation()));
        assertEquals(0, fastPath.metrics().entries());
    }

    @Test
    void exactEventMemoRemainsSessionPrivateForSharedProjectionGeneration() {
        AdmittedProjection firstProjection = FastPathFixtures.projection(
                20, 1L);
        ProjectionGenerationKey firstGeneration =
                firstProjection.generation();
        PlanningFastPath<String> fastPath = new PlanningFastPath<String>(
                8, 1024L, String::length);
        AtomicInteger semanticCalls = new AtomicInteger();
        PlanCacheKey first = new PlanCacheKey(
                firstGeneration,
                "first-session",
                "event",
                "event-inventory",
                order("order"),
                Arrays.asList("public-10", "public-11"),
                "policy");
        PlanCacheKey second = new PlanCacheKey(
                firstGeneration,
                "second-session",
                "event",
                "event-inventory",
                order("order"),
                Arrays.asList("public-10", "public-11"),
                "policy");

        assertEquals("first", fastPath.prepare(
                first,
                firstProjection,
                ignored -> {
                    semanticCalls.incrementAndGet();
                    return "first";
                }));
        assertEquals("second", fastPath.prepare(
                second,
                firstProjection,
                ignored -> {
                    semanticCalls.incrementAndGet();
                    return "second";
                }));

        assertEquals(2, semanticCalls.get());
        assertEquals(1, fastPath.generationCommitted(
                "first-session", firstGeneration));
        assertEquals(1, fastPath.metrics().entries(),
                "another session's exact event memo must survive");
    }

    @Test
    void sameEventIdentityCannotReuseAnotherEventInventory() {
        AdmittedProjection projection = FastPathFixtures.projection(20, 1L);
        PlanningFastPath<String> fastPath = new PlanningFastPath<String>(
                8, 1024L, String::length);
        AtomicInteger semanticCalls = new AtomicInteger();
        PlanCacheKey first = new PlanCacheKey(
                projection.generation(),
                "session",
                "event",
                "event-inventory-a",
                order("order"),
                Arrays.asList("public-10", "public-11"),
                "policy");
        PlanCacheKey second = new PlanCacheKey(
                projection.generation(),
                "session",
                "event",
                "event-inventory-b",
                order("order"),
                Arrays.asList("public-10", "public-11"),
                "policy");

        fastPath.prepare(first, projection, ignored -> {
            semanticCalls.incrementAndGet();
            return "first";
        });
        fastPath.prepare(second, projection, ignored -> {
            semanticCalls.incrementAndGet();
            return "second";
        });

        assertEquals(2, semanticCalls.get());
    }

    @Test
    void authoritativeCandidateOrderRemainsPartOfTheExactPlanKey() {
        AdmittedProjection projection = FastPathFixtures.projection(20, 1L);
        PlanningFastPath<String> fastPath = new PlanningFastPath<String>(
                8, 1024L, String::length);
        AtomicInteger semanticCalls = new AtomicInteger();
        PlanCacheKey deepFirst = new PlanCacheKey(
                projection.generation(),
                "session",
                "event",
                "event-inventory",
                order("order"),
                Arrays.asList("public-11", "public-10"),
                "policy");
        PlanCacheKey shallowFirst = new PlanCacheKey(
                projection.generation(),
                "session",
                "event",
                "event-inventory",
                order("order"),
                Arrays.asList("public-10", "public-11"),
                "policy");

        fastPath.prepare(deepFirst, projection, selected -> {
            semanticCalls.incrementAndGet();
            return selected.publicKeys().toString();
        });
        fastPath.prepare(shallowFirst, projection, selected -> {
            semanticCalls.incrementAndGet();
            return selected.publicKeys().toString();
        });

        assertEquals(2, semanticCalls.get());
        assertEquals(2L, fastPath.metrics().loads());
    }

    @Test
    void exactValueIdentityIsCalculatedOnceAtAdmission() {
        AtomicInteger calculations = new AtomicInteger();
        AdmittedExactValue<String> admitted = AdmittedExactValue.verifyAndAdmit(
                "id", "inventory", "payload", ignored -> {
                    calculations.incrementAndGet();
                    return "id";
                });
        for (int index = 0; index < 1000; index++) {
            assertEquals("payload", admitted.retainedValue());
        }
        assertEquals(1, calculations.get());
    }

    private static ExternalOrderKey order(String value) {
        return ExternalOrderKey.of(Arrays.<Object>asList(value));
    }
}
