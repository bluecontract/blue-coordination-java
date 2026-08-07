package blue.coordination.fastpath;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acceptance proof for exact, generation-bound admitted planning inputs. */
final class AdmittedPlanningInputTest {

    @Test
    void shouldRejectEveryStalePlanningGenerationDimension() {
        // given
        AdmittedProjection authoritative = FastPathFixtures.projection(24, 7L);
        ProjectionGenerationKey generation = authoritative.generation();
        List<ProjectionGenerationKey> stale = Arrays.asList(
                generation(
                        "other-environment", generation.sessionId(),
                        generation.rootBlueId(), generation.rootRevision(),
                        generation.inventoryIdentity(),
                        generation.subscriptionDigest(),
                        generation.runtimeIdentity()),
                generation(
                        generation.environmentIdentity(), "other-session",
                        generation.rootBlueId(), generation.rootRevision(),
                        generation.inventoryIdentity(),
                        generation.subscriptionDigest(),
                        generation.runtimeIdentity()),
                generation(
                        generation.environmentIdentity(), generation.sessionId(),
                        "other-root", generation.rootRevision(),
                        generation.inventoryIdentity(),
                        generation.subscriptionDigest(),
                        generation.runtimeIdentity()),
                generation(
                        generation.environmentIdentity(), generation.sessionId(),
                        generation.rootBlueId(), generation.rootRevision() + 1L,
                        generation.inventoryIdentity(),
                        generation.subscriptionDigest(),
                        generation.runtimeIdentity()),
                generation(
                        generation.environmentIdentity(), generation.sessionId(),
                        generation.rootBlueId(), generation.rootRevision(),
                        "other-inventory", generation.subscriptionDigest(),
                        generation.runtimeIdentity()),
                generation(
                        generation.environmentIdentity(), generation.sessionId(),
                        generation.rootBlueId(), generation.rootRevision(),
                        generation.inventoryIdentity(), "other-subscriptions",
                        generation.runtimeIdentity()),
                generation(
                        generation.environmentIdentity(), generation.sessionId(),
                        generation.rootBlueId(), generation.rootRevision(),
                        generation.inventoryIdentity(),
                        generation.subscriptionDigest(), "other-runtime"));
        PlanningFastPath<String> planning = new PlanningFastPath<String>(
                16, 4096L, String::length);

        // when
        List<IllegalArgumentException> failures = new ArrayList<>();
        for (ProjectionGenerationKey rejected : stale) {
            PlanCacheKey key = new PlanCacheKey(
                    rejected,
                    "event",
                    "event-inventory",
                    ExternalOrderKey.of(Arrays.<Object>asList("order")),
                    Arrays.asList("public-10", "public-11"),
                    "policy");
            failures.add(assertThrows(
                    IllegalArgumentException.class,
                    () -> planning.prepare(
                            key, authoritative, ignored -> "forged")));
        }

        // then
        assertEquals(stale.size(), failures.size());
        assertTrue(failures.stream().allMatch(failure -> failure.getMessage()
                .contains("generations differ")));
        assertEquals(0L, planning.metrics().loads(),
                "a rejected generation must never reach semantic planning");
    }

    @Test
    void shouldCalculateExactIdentityOnlyAtAdmissionAndBindItsInventory() {
        // given
        AtomicInteger identityCalculations = new AtomicInteger();
        Object exactRoot = new Object();
        AdmittedExactValue<Object> admitted = AdmittedExactValue.verifyAndAdmit(
                "root-id",
                "inventory-id",
                exactRoot,
                ignored -> {
                    identityCalculations.incrementAndGet();
                    return "root-id";
                });

        // when
        for (int index = 0; index < 1_000; index++) {
            admitted.requireBinding("root-id", "inventory-id");
            assertSame(exactRoot, admitted.retainedValue());
        }
        IllegalArgumentException wrongInventory = assertThrows(
                IllegalArgumentException.class,
                () -> admitted.requireBinding("root-id", "other-inventory"));
        IllegalArgumentException wrongIdentity = assertThrows(
                IllegalArgumentException.class,
                () -> AdmittedExactValue.verifyAndAdmit(
                        "claimed", "inventory-id", exactRoot,
                        ignored -> "calculated"));

        // then
        assertEquals(1, identityCalculations.get());
        assertTrue(wrongInventory.getMessage().contains("binding mismatch"));
        assertTrue(wrongIdentity.getMessage().contains("identity mismatch"));
    }

    @Test
    void shouldDifferentiallyMatchTheUntrustedSelectedSurfaceOracle() {
        // given
        AdmittedProjection admitted = FastPathFixtures.projection(64, 3L);
        List<String> requested = Arrays.asList(
                "public-10", "public-11", "public-12");
        SelectedSurfaceOracle oracle = oracle(admitted, requested);

        // when
        AdmittedProjection.SelectedSurface selected = admitted.select(requested);

        // then
        assertEquals(oracle.publicKeys, selected.publicKeys());
        assertEquals(oracle.languageKeys, selected.languageKeys());
        assertEquals(oracle.scopeChains, selected.scopeChains());
        assertEquals(oracle.requiredIdentities, selected.requiredIdentities());
        assertEquals(oracle.prefetchIdentities, selected.prefetchIdentities());
    }

    @Test
    void shouldKeepTheEnginePlanningCapabilityNonForgeableByPublicCallers() {
        // given
        Constructor<?>[] constructors = CoordinationProcessingEngine
                .AdmittedPlanningAuthority.class.getDeclaredConstructors();

        // when
        boolean noPublicOrProtected = Arrays.stream(constructors)
                .noneMatch(constructor -> Modifier.isPublic(
                        constructor.getModifiers())
                        || Modifier.isProtected(constructor.getModifiers()));

        // then
        assertTrue(constructors.length > 0);
        assertEquals(0, CoordinationProcessingEngine
                .AdmittedPlanningAuthority.class.getConstructors().length);
        assertTrue(noPublicOrProtected,
                "only a CoordinationProcessingEngine may issue the capability");
    }

    private static ProjectionGenerationKey generation(
            String environment,
            String session,
            String root,
            long revision,
            String inventory,
            String subscriptions,
            String runtime) {
        return new ProjectionGenerationKey(
                environment,
                session,
                root,
                revision,
                inventory,
                subscriptions,
                runtime);
    }

    private static SelectedSurfaceOracle oracle(
            AdmittedProjection projection,
            List<String> requested) {
        List<AdmittedOccurrence> chosen = new ArrayList<>();
        for (String publicKey : requested) {
            chosen.add(projection.requirePublic(publicKey));
        }
        List<String> publicKeys = new ArrayList<>();
        List<String> languageKeys = new ArrayList<>();
        Map<String, List<String>> scopeChains = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        Set<String> prefetch = new java.util.TreeSet<>();
        required.add(projection.generation().rootBlueId());
        for (AdmittedOccurrence occurrence : chosen) {
            publicKeys.add(occurrence.publicKey());
            languageKeys.add(occurrence.languageKey());
            scopeChains.put(
                    occurrence.scopePath(), occurrence.scopeChainBlueIds());
            required.addAll(occurrence.scopeChainBlueIds());
            required.addAll(occurrence.sourceContributionBlueIds());
            required.addAll(occurrence.dependencyBlueIds());
            prefetch.addAll(occurrence.sourceContributionBlueIds());
            prefetch.addAll(occurrence.dependencyBlueIds());
        }
        prefetch.remove(projection.generation().rootBlueId());
        return new SelectedSurfaceOracle(
                publicKeys,
                languageKeys,
                scopeChains,
                required,
                new ArrayList<>(prefetch));
    }

    private static final class SelectedSurfaceOracle {
        private final List<String> publicKeys;
        private final List<String> languageKeys;
        private final Map<String, List<String>> scopeChains;
        private final Set<String> requiredIdentities;
        private final List<String> prefetchIdentities;

        private SelectedSurfaceOracle(
                List<String> publicKeys,
                List<String> languageKeys,
                Map<String, List<String>> scopeChains,
                Set<String> requiredIdentities,
                List<String> prefetchIdentities) {
            this.publicKeys = publicKeys;
            this.languageKeys = languageKeys;
            this.scopeChains = scopeChains;
            this.requiredIdentities = requiredIdentities;
            this.prefetchIdentities = prefetchIdentities;
        }
    }
}
