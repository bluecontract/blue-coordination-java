package blue.coordination.processor;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.processor.CoordinationCurrentRootDeliveryPlanDeriver;
import blue.language.processor.CoordinationIndexedDeliveryEngine;
import blue.language.processor.CoordinationProcessHeaderBridge;
import blue.language.processor.CoordinationSubscriptionProjectionBridge;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalDeliveryPlanDeriver;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes the intentionally narrow production surface required by the
 * release API report.
 */
final class CoordinationPublicApiSurfaceTest {

    @Test
    void shouldKeepRoutingMatchersAndPlanCachesInternal()
            throws ClassNotFoundException {
        // Given
        String[] implementationTypes = {
                "blue.coordination.processor.AllTimelinesExternalSubscriptionFunctions",
                "blue.coordination.processor.CompositeTimelineExternalSubscriptionFunctions",
                "blue.coordination.processor.CoordinationEventNodes",
                "blue.coordination.processor.CoordinationRuntimeRegistrations",
                "blue.coordination.processor.CoordinationSubscriptionSerialization",
                "blue.coordination.processor.FixedRepositoryBoundSourceProvider",
                "blue.coordination.processor.HandlerChannelResolver",
                "blue.coordination.processor.OperationRequestMatcher",
                "blue.coordination.processor.OperationRequestRoutingFunctions",
                "blue.coordination.processor.SequentialWorkflowEventMatcher",
                "blue.coordination.processor.TimelineExternalSubscriptionFunctions",
                "blue.coordination.processor.TimelineMemberSubscriptions",
                "blue.coordination.processor.TimelineSubscriptionProjection",
                "blue.coordination.processor.bex.ScopedProcessorExecutionContextBexDocumentView",
                "blue.coordination.processor.workflow.ComputeDefinitionResolver",
                "blue.coordination.processor.workflow.ComputeEffectPlan",
                "blue.coordination.processor.workflow.ComputeProgramNormalizer",
                "blue.coordination.processor.workflow.ComputeProgramPlan",
                "blue.coordination.processor.workflow.ComputeProgramPlanCache",
                "blue.coordination.processor.workflow.ComputeResultEmitter",
                "blue.coordination.processor.workflow.SequentialWorkflowPlan",
                "blue.coordination.processor.workflow.SequentialWorkflowPlanCache",
                "blue.coordination.processor.workflow.StaticUpdatePlan",
                "blue.coordination.processor.workflow.WorkflowBexGasLedgerHost",
                "blue.coordination.processor.workflow.WorkflowExecutionState",
                "blue.coordination.processor.workflow.WorkflowPatchEntry"
        };

        // When
        Set<String> exposed =
                publiclyExposed(implementationTypes);

        // Then
        assertTrue(
                exposed.isEmpty(),
                "Implementation-only production types entered the public "
                        + "API: " + exposed);
    }

    @Test
    void shouldKeepMetricsFanOutPrivateWhileRetainingBaselineSink() {
        // Given
        Class<?> baselineSink =
                BexProcessingMetrics.class;

        // When
        Class<?> fanOut = declaredClass(
                CoordinationProcessors.class,
                "CompositeProcessingMetricsSink");

        // Then
        assertTrue(
                Modifier.isPublic(
                        baselineSink.getModifiers()),
                "The pre-existing metrics sink is retained for binary "
                        + "compatibility");
        assertNotNull(fanOut);
        assertTrue(
                Modifier.isPrivate(fanOut.getModifiers()));
        assertTrue(
                Modifier.isStatic(fanOut.getModifiers()));
        assertTrue(
                Modifier.isFinal(fanOut.getModifiers()));
    }

    @Test
    void shouldKeepNecessaryLanguageBridgesNarrow() {
        // Given
        Set<String> expectedProcessMethods =
                names(
                        "canonicalExactCopy",
                        "hasSemanticOutputBoundary",
                        "materializeVerifiedExactReference");
        Set<String> expectedProjectionMethods =
                names(
                        "languageRuntimeRegistryIdentity",
                        "projectCurrent",
                        "projectUpdate");

        // When
        Set<String> processMethods =
                publicMethodNames(
                        CoordinationProcessHeaderBridge.class);
        Set<String> projectionMethods =
                publicMethodNames(
                        CoordinationSubscriptionProjectionBridge.class);
        Set<String> publicProjectionValues =
                publicNestedTypeNames(
                        CoordinationSubscriptionProjectionBridge.class);

        // Then
        assertEquals(
                expectedProcessMethods,
                processMethods);
        assertEquals(
                expectedProjectionMethods,
                projectionMethods);
        assertEquals(
                names("HeaderProjection", "Projection"),
                publicProjectionValues);
        assertEquals(
                0L,
                publicConstructorCount(
                        CoordinationProcessHeaderBridge.class));
        assertEquals(
                1L,
                publicConstructorCount(
                        CoordinationSubscriptionProjectionBridge.class));
    }

    @Test
    void shouldKeepCurrentRootLanguageBridgeNarrow()
            throws NoSuchMethodException {
        // Given
        Set<String> expectedMethods =
                names(
                        "derive",
                        "forProcessor");
        Constructor<?> constructor =
                CoordinationCurrentRootDeliveryPlanDeriver.class
                        .getDeclaredConstructor(
                                DocumentProcessor.class);
        Method factory =
                CoordinationCurrentRootDeliveryPlanDeriver.class
                        .getDeclaredMethod(
                                "forProcessor",
                                DocumentProcessor.class);

        // When
        Set<String> publicMethods =
                publicMethodNames(
                        CoordinationCurrentRootDeliveryPlanDeriver.class);
        Set<String> publicNestedTypes =
                publicNestedTypeNames(
                        CoordinationCurrentRootDeliveryPlanDeriver.class);
        int constructorModifiers =
                constructor.getModifiers();

        // Then
        assertEquals(
                expectedMethods,
                publicMethods);
        assertEquals(
                expectedMethods.size(),
                publicMethodCount(
                        CoordinationCurrentRootDeliveryPlanDeriver.class));
        assertTrue(
                publicNestedTypes.isEmpty());
        assertEquals(
                0L,
                publicConstructorCount(
                        CoordinationCurrentRootDeliveryPlanDeriver.class));
        assertFalse(
                Modifier.isPublic(
                        constructorModifiers));
        assertFalse(
                Modifier.isProtected(
                        constructorModifiers));
        assertFalse(
                Modifier.isPrivate(
                        constructorModifiers));
        assertEquals(
                ExternalDeliveryPlanDeriver.class,
                factory.getReturnType());
    }

    @Test
    void shouldKeepIndexedDeliveryLanguageBridgeNarrow()
            throws NoSuchMethodException {
        // Given
        Set<String> expectedEngineMethods =
                names(
                        "languageOccurrenceKey",
                        "prepare");
        Set<String> expectedPreparedMethods =
                names(
                        "diagnostics",
                        "evidence",
                        "occurrenceOrder",
                        "plan",
                        "planIdentity");
        Method internalRuntimeIdentity =
                CoordinationIndexedDeliveryEngine.class
                        .getDeclaredMethod(
                                "runtimeRegistryIdentity");

        // When
        Set<String> engineMethods =
                publicMethodNames(
                        CoordinationIndexedDeliveryEngine.class);
        Set<String> preparedMethods =
                publicMethodNames(
                        CoordinationIndexedDeliveryEngine
                                .Prepared.class);
        int runtimeIdentityModifiers =
                internalRuntimeIdentity
                        .getModifiers();

        // Then
        assertEquals(
                expectedEngineMethods,
                engineMethods);
        assertEquals(
                expectedEngineMethods.size(),
                publicMethodCount(
                        CoordinationIndexedDeliveryEngine.class));
        assertEquals(
                names("Prepared"),
                publicNestedTypeNames(
                        CoordinationIndexedDeliveryEngine.class));
        assertEquals(
                1L,
                publicConstructorCount(
                        CoordinationIndexedDeliveryEngine.class));
        assertEquals(
                expectedPreparedMethods,
                preparedMethods);
        assertEquals(
                expectedPreparedMethods.size(),
                publicMethodCount(
                        CoordinationIndexedDeliveryEngine
                                .Prepared.class));
        assertEquals(
                0L,
                publicConstructorCount(
                        CoordinationIndexedDeliveryEngine
                                .Prepared.class));
        assertFalse(
                Modifier.isPublic(
                        runtimeIdentityModifiers));
        assertFalse(
                Modifier.isProtected(
                        runtimeIdentityModifiers));
        assertFalse(
                Modifier.isPrivate(
                        runtimeIdentityModifiers));
    }

    @Test
    void shouldKeepConformanceEvidenceCollectorOutOfProductionArtifact() {
        // Given
        Path productionCollector = Paths.get(
                "src", "main", "java", "blue", "coordination",
                "processor", "bex",
                "ProcessingEventIdentityEvidence.java");
        Path testCollector = Paths.get(
                "src", "test", "java", "blue", "coordination",
                "processor", "bex",
                "ProcessingEventIdentityEvidence.java");

        // When
        boolean productionExists =
                Files.exists(productionCollector);
        boolean testExists =
                Files.isRegularFile(testCollector);

        // Then
        assertFalse(
                productionExists,
                "Fixture evidence must not enter the production JAR");
        assertTrue(
                testExists,
                "Executable conformance retains its test-only evidence");
    }

    @Test
    void shouldKeepIdentityObserverOptionOutsidePublicApi()
            throws NoSuchMethodException {
        // Given
        Method getter =
                CoordinationProcessorOptions.class
                        .getDeclaredMethod(
                                "processingEventIdentityObserver");
        Method setter =
                CoordinationProcessorOptions.Builder.class
                        .getDeclaredMethod(
                                "processingEventIdentityObserver",
                                blue.coordination.processor.bex
                                        .ProcessingEventIdentityObserver.class);

        // When
        int getterModifiers =
                getter.getModifiers();
        int setterModifiers =
                setter.getModifiers();

        // Then
        assertFalse(Modifier.isPublic(getterModifiers));
        assertFalse(Modifier.isProtected(getterModifiers));
        assertFalse(Modifier.isPublic(setterModifiers));
        assertFalse(Modifier.isProtected(setterModifiers));
    }

    @Test
    void shouldCreatePlanningFacadesOnlyThroughPublicDeliveryPlanning() {
        // Given
        Class<?>[] factoryOwnedFacades = {
                CoordinationSubscriptionProjector.class,
                CoordinationIndexedDeliveryPlanner.class
        };

        // When
        Set<String> publicConstructors =
                publicConstructorOwners(
                        factoryOwnedFacades);

        // Then
        assertTrue(
                publicConstructors.isEmpty(),
                "Factory-owned planning facades exported constructors: "
                        + publicConstructors);
    }

    private static Set<String> publiclyExposed(
            String[] names) throws ClassNotFoundException {
        Set<String> exposed =
                new TreeSet<String>();
        ClassLoader loader =
                CoordinationPublicApiSurfaceTest.class
                        .getClassLoader();
        for (String name : names) {
            Class<?> type =
                    Class.forName(
                            name,
                            false,
                            loader);
            int modifiers = type.getModifiers();
            if (Modifier.isPublic(modifiers)
                    || Modifier.isProtected(modifiers)) {
                exposed.add(name);
            }
        }
        return exposed;
    }

    private static Set<String> publicMethodNames(
            Class<?> type) {
        Set<String> result =
                new TreeSet<String>();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(
                    method.getModifiers())
                    && !method.isSynthetic()) {
                result.add(method.getName());
            }
        }
        return result;
    }

    private static long publicMethodCount(
            Class<?> type) {
        long result = 0L;
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(
                    method.getModifiers())
                    && !method.isSynthetic()) {
                result++;
            }
        }
        return result;
    }

    private static Set<String> publicNestedTypeNames(
            Class<?> type) {
        Set<String> result =
                new TreeSet<String>();
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (Modifier.isPublic(
                    nested.getModifiers())) {
                result.add(nested.getSimpleName());
            }
        }
        return result;
    }

    private static long publicConstructorCount(
            Class<?> type) {
        long result = 0L;
        for (Constructor<?> constructor
                : type.getDeclaredConstructors()) {
            if (Modifier.isPublic(
                    constructor.getModifiers())) {
                result++;
            }
        }
        return result;
    }

    private static Set<String> publicConstructorOwners(
            Class<?>[] types) {
        Set<String> result =
                new TreeSet<String>();
        for (Class<?> type : types) {
            if (publicConstructorCount(type) > 0L) {
                result.add(type.getName());
            }
        }
        return result;
    }

    private static Class<?> declaredClass(
            Class<?> owner,
            String simpleName) {
        for (Class<?> candidate
                : owner.getDeclaredClasses()) {
            if (simpleName.equals(
                    candidate.getSimpleName())) {
                return candidate;
            }
        }
        return null;
    }

    private static Set<String> names(
            String... values) {
        return new TreeSet<String>(
                Arrays.asList(values));
    }
}
