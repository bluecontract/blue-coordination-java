package blue.coordination.processor;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.delivery.CoordinationCurrentRootDeliveryPlanDeriver;
import blue.coordination.processor.delivery.CoordinationIndexedDeliveryEngine;
import blue.coordination.processor.merge.CoordinationMerging;
import blue.coordination.processor.subscription.CoordinationSubscriptionProjectionBridge;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessingObserver;
import blue.language.processor.SubscriptionDelta;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes the intentionally narrow production surface required by the
 * release API report.
 */
final class CoordinationPublicApiSurfaceTest {

    @Test
    void shouldKeepDeletedLanguageCompatibilityTypesOutOfCoordinationSurface() {
        // given
        Set<String> processorMethods =
                publicMethodNames(CoordinationProcessors.class);
        Set<String> mergingMethods =
                publicMethodNames(CoordinationMerging.class);
        Set<String> metricsInterfaces =
                typeNames(BexProcessingMetrics.class.getInterfaces());

        // when
        ClassNotFoundException legacyProvider = assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("blue.language.NodeProvider"));
        ClassNotFoundException legacyMetrics = assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName(
                        "blue.language.processor.ProcessingMetricsSink"));
        ClassNotFoundException compatibilityProvider = assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName(
                        "blue.coordination.processor."
                                + "CoordinationRepositoryCompatibilityNodeProvider"));

        // then
        assertFalse(processorMethods.contains("registerWith"));
        assertEquals(names("wrap"), mergingMethods);
        assertEquals(
                names(
                        "blue.bex.api.BexMetricsSink",
                        "blue.language.processor.ProcessingObserver"),
                metricsInterfaces);
        assertTrue(legacyProvider.getMessage()
                .contains("blue.language.NodeProvider"));
        assertTrue(legacyMetrics.getMessage()
                .contains("ProcessingMetricsSink"));
        assertTrue(compatibilityProvider.getMessage()
                .contains("CoordinationRepositoryCompatibilityNodeProvider"));
    }

    @Test
    void shouldKeepRoutingMatchersAndPlanCachesInternal()
            throws ClassNotFoundException {
        // given
        String[] implementationTypes = {
                "blue.coordination.processor.AllTimelinesExternalSubscriptionFunctions",
                "blue.coordination.processor.CompositeTimelineExternalSubscriptionFunctions",
                "blue.coordination.processor.CoordinationEventNodes",
                "blue.coordination.processor.CoordinationRuntimeRegistrations",
                "blue.coordination.processor.CoordinationSubscriptionSerialization",
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

        // when
        Set<String> exposed =
                publiclyExposed(implementationTypes);

        // then
        assertTrue(
                exposed.isEmpty(),
                "Implementation-only production types entered the public "
                        + "API: " + exposed);
    }

    @Test
    void shouldExposeObserverCompositionWithoutLegacyPrivateFanOut()
            throws NoSuchMethodException {
        // given
        Class<?> baselineSink =
                BexProcessingMetrics.class;
        Method observerFactory =
                CoordinationProcessors.class.getDeclaredMethod(
                        "observers",
                        ProcessingObserver.class,
                        ProcessingObserver.class);

        // when
        Class<?> fanOut = declaredClass(
                CoordinationProcessors.class,
                "CompositeProcessingMetricsSink");

        // then
        assertTrue(
                Modifier.isPublic(
                        baselineSink.getModifiers()),
                "The current typed observer remains public");
        assertTrue(Modifier.isPublic(observerFactory.getModifiers()));
        assertTrue(Modifier.isStatic(observerFactory.getModifiers()));
        assertNull(
                fanOut,
                "The removed mutable ProcessingMetricsSink fan-out must not "
                        + "re-enter the public or private implementation");
    }

    @Test
    void shouldKeepCoordinationOwnedPublicBridgesNarrow() {
        // given
        Set<String> expectedProcessMethods =
                names(
                        "canonicalExactCopy",
                        "materializeVerifiedExactReference");
        Set<String> expectedProjectionMethods =
                names(
                        "effectiveFragmentationCatalog",
                        "languageRuntimeRegistryIdentity",
                        "materializeExactRoot",
                        "projectCurrent",
                        "projectUpdate");

        // when
        Set<String> processMethods =
                publicMethodNames(
                        CoordinationProcessHeaderBridge.class);
        Set<String> projectionMethods =
                publicMethodNames(
                        CoordinationSubscriptionProjectionBridge.class);
        Set<String> publicProjectionValues =
                publicNestedTypeNames(
                        CoordinationSubscriptionProjectionBridge.class);

        // then
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
        assertTrue(Arrays.stream(
                        CoordinationSubscriptionProjectionBridge.class
                                .getConstructors())
                .allMatch(constructor -> Arrays.equals(
                        new Class<?>[]{BlueContracts.class},
                        constructor.getParameterTypes())));
        assertFalse(Arrays.stream(
                        CoordinationSubscriptionProjectionBridge.class
                                .getConstructors())
                .anyMatch(constructor -> Arrays.asList(
                                constructor.getParameterTypes())
                        .contains(DocumentProcessor.class)));
    }

    @Test
    void shouldKeepCurrentRootCoordinationBoundaryNarrow()
            throws NoSuchMethodException {
        // given
        Set<String> expectedMethods =
                names(
                        "derive",
                        "forContracts");
        Constructor<?> constructor =
                CoordinationCurrentRootDeliveryPlanDeriver.class
                        .getDeclaredConstructor(
                                BlueContracts.class,
                                long.class,
                                ExternalOrderKey.class,
                                java.util.List.class);
        Method factory =
                CoordinationCurrentRootDeliveryPlanDeriver.class
                        .getDeclaredMethod(
                                "forContracts",
                                BlueContracts.class,
                                long.class,
                                ExternalOrderKey.class,
                                java.util.List.class);

        // when
        Set<String> publicMethods =
                publicMethodNames(
                        CoordinationCurrentRootDeliveryPlanDeriver.class);
        Set<String> publicNestedTypes =
                publicNestedTypeNames(
                        CoordinationCurrentRootDeliveryPlanDeriver.class);
        int constructorModifiers =
                constructor.getModifiers();

        // then
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
        assertTrue(Modifier.isPrivate(constructorModifiers));
        assertEquals(
                ExternalDeliveryPlanDeriver.class,
                factory.getReturnType());
    }

    @Test
    void shouldRequirePublicContractsForOnlineDocumentSplitting() {
        // given
        Constructor<?>[] constructors =
                CoordinationDocumentSplitter.class.getConstructors();

        // when
        boolean allOnlineConstructorsUseContracts =
                Arrays.stream(constructors)
                        .allMatch(constructor ->
                                constructor.getParameterCount() > 0
                                        && constructor
                                        .getParameterTypes()[0]
                                        == BlueContracts.class);
        boolean retainsProcessorConstructor =
                Arrays.stream(constructors)
                        .anyMatch(constructor -> Arrays.asList(
                                        constructor.getParameterTypes())
                                .contains(DocumentProcessor.class));

        // then
        assertEquals(2, constructors.length);
        assertTrue(allOnlineConstructorsUseContracts);
        assertFalse(retainsProcessorConstructor);
    }

    @Test
    void shouldKeepTheIntentionalIncrementalSplitterOperationsExact() {
        // given
        Set<String> expectedIncremental = names(
                "describeRetainedDirectEdge(blue.coordination.processor."
                        + "CoordinationDocumentSplitter$"
                        + "DocumentFragmentationBlueprint,"
                        + "blue.coordination.processor."
                        + "CoordinationDocumentSplitter$FragmentRootKind,"
                        + "java.lang.String,java.lang.String,java.lang.String,"
                        + "java.lang.String,boolean,boolean)"
                        + "->blue.coordination.processor."
                        + "CoordinationDocumentSplitter$EdgeOccurrence",
                "documentFragmentationBlueprint(blue.language.model.Node)"
                        + "->blue.coordination.processor."
                        + "CoordinationDocumentSplitter$"
                        + "DocumentFragmentationBlueprint",
                "documentFragmentationBlueprint(blue.language.model.Node,"
                        + "blue.language.processor."
                        + "EffectiveFragmentationCatalog)"
                        + "->blue.coordination.processor."
                        + "CoordinationDocumentSplitter$"
                        + "DocumentFragmentationBlueprint",
                "inspectDirectChild(blue.coordination.processor."
                        + "CoordinationDocumentSplitter$"
                        + "DocumentFragmentationBlueprint,"
                        + "blue.coordination.processor."
                        + "CoordinationDocumentSplitter$FragmentRootKind,"
                        + "blue.coordination.processor."
                        + "CoordinationDocumentSplitter$DirectChildOccurrence,"
                        + "boolean)->blue.coordination.processor."
                        + "CoordinationDocumentSplitter$DirectNodeInspection",
                "inspectDirectNode(blue.coordination.processor."
                        + "CoordinationDocumentSplitter$"
                        + "DocumentFragmentationBlueprint,"
                        + "blue.coordination.processor."
                        + "CoordinationDocumentSplitter$FragmentRootKind,"
                        + "blue.language.model.Node,java.lang.String,boolean)"
                        + "->blue.coordination.processor."
                        + "CoordinationDocumentSplitter$DirectNodeInspection",
                "inspectPhysicalRoot(blue.coordination.processor."
                        + "CoordinationDocumentSplitter$"
                        + "DocumentFragmentationBlueprint,"
                        + "blue.coordination.processor."
                        + "CoordinationDocumentSplitter$PhysicalFragmentRoot,"
                        + "boolean)->blue.coordination.processor."
                        + "CoordinationDocumentSplitter$DirectNodeInspection");

        // when
        Set<String> actualIncremental = publicMethodSignatures(
                CoordinationDocumentSplitter.class);
        actualIncremental.retainAll(expectedIncremental);

        // then
        assertEquals(expectedIncremental, actualIncremental);
        assertEquals(
                names(
                        "describeRetainedDirectEdge",
                        "documentFragmentationBlueprint",
                        "forEventSplitting",
                        "fromEffectiveCatalog",
                        "inspectDirectChild",
                        "inspectDirectNode",
                        "inspectPhysicalRoot",
                        "prepareForProcessing",
                        "splitDocument",
                        "splitEvent"),
                publicMethodNames(CoordinationDocumentSplitter.class));
        assertEquals(13L,
                publicMethodCount(CoordinationDocumentSplitter.class));
    }

    @Test
    void shouldKeepTheIncrementalSplitterEvidenceTypesExact() {
        // given
        Set<String> expectedNestedTypes = names(
                "DirectChildOccurrence",
                "DirectNodeInspection",
                "DocumentFragmentationBlueprint",
                "EdgeKind",
                "EdgeOccurrence",
                "EmbeddedEdgeOrigin",
                "FragmentKind",
                "FragmentMetadata",
                "FragmentRoot",
                "FragmentRootKind",
                "PhysicalFragmentRoot",
                "PreparedProcessingInput",
                "SplitGraph");

        // when
        Set<String> blueprintMethods = publicMethodSignatures(
                CoordinationDocumentSplitter
                        .DocumentFragmentationBlueprint.class);
        Set<String> physicalRootMethods = publicMethodSignatures(
                CoordinationDocumentSplitter.PhysicalFragmentRoot.class);
        Set<String> inspectionMethods = publicMethodSignatures(
                CoordinationDocumentSplitter.DirectNodeInspection.class);
        Set<String> childMethods = publicMethodSignatures(
                CoordinationDocumentSplitter.DirectChildOccurrence.class);

        // then
        assertEquals(expectedNestedTypes,
                publicNestedTypeNames(CoordinationDocumentSplitter.class));
        assertEquals(names(
                        "exactRoot()->blue.language.model.Node",
                        "fragmentRoots()->java.util.List",
                        "metadata()->java.util.List",
                        "physicalRoots()->java.util.List",
                        "processHeaderViews()->java.util.Map",
                        "rootBlueId()->java.lang.String"),
                blueprintMethods);
        assertEquals(names(
                        "basePath()->java.lang.String",
                        "blueId()->java.lang.String",
                        "exactRoot()->blue.language.model.Node",
                        "rootKind()->blue.coordination.processor."
                                + "CoordinationDocumentSplitter$FragmentRootKind"),
                physicalRootMethods);
        assertEquals(names(
                        "assembledFragment()->boolean",
                        "children()->java.util.List",
                        "directFragment()->blue.language.model.Node",
                        "ownerBlueId()->java.lang.String"),
                inspectionMethods);
        assertEquals(names(
                        "edge()->blue.coordination.processor."
                                + "CoordinationDocumentSplitter$EdgeOccurrence",
                        "exactChild()->blue.language.model.Node"),
                childMethods);
        assertEquals(0L, publicConstructorCount(
                CoordinationDocumentSplitter
                        .DocumentFragmentationBlueprint.class));
        assertEquals(0L, publicConstructorCount(
                CoordinationDocumentSplitter.PhysicalFragmentRoot.class));
        assertEquals(0L, publicConstructorCount(
                CoordinationDocumentSplitter.DirectNodeInspection.class));
        assertEquals(0L, publicConstructorCount(
                CoordinationDocumentSplitter.DirectChildOccurrence.class));
    }

    @Test
    void shouldKeepIndexedDeliveryCoordinationBoundaryNarrow() {
        // given
        Set<String> expectedEngineMethods =
                names(
                        "forAdmittedPlanning",
                        "languageOccurrenceKey",
                        "prepare",
                        "prepareAdmitted",
                        "processForPlatformCommit",
                        "runtimeRegistryIdentity");
        Set<String> expectedPreparedMethods =
                names(
                        "diagnostics",
                        "evidence",
                        "occurrenceOrder",
                        "plan",
                        "planIdentity");
        Set<String> expectedActiveSurfaceMethods =
                names("from");
        // when
        Set<String> engineMethods =
                publicMethodNames(
                        CoordinationIndexedDeliveryEngine.class);
        Set<String> preparedMethods =
                publicMethodNames(
                        CoordinationIndexedDeliveryEngine
                                .Prepared.class);
        Set<String> activeSurfaceMethods =
                publicMethodNames(
                        CoordinationIndexedDeliveryEngine
                                .IndexedActiveSurface.class);
        // then
        assertEquals(
                expectedEngineMethods,
                engineMethods);
        assertEquals(
                expectedEngineMethods.size() + 2,
                publicMethodCount(
                        CoordinationIndexedDeliveryEngine.class));
        assertEquals(
                names("IndexedActiveSurface", "Prepared"),
                publicNestedTypeNames(
                        CoordinationIndexedDeliveryEngine.class));
        assertEquals(
                1L,
                publicConstructorCount(
                        CoordinationIndexedDeliveryEngine.class));
        assertTrue(Arrays.stream(
                        CoordinationIndexedDeliveryEngine.class
                                .getConstructors())
                .allMatch(constructor -> Arrays.equals(
                        new Class<?>[]{BlueContracts.class},
                        constructor.getParameterTypes())));
        assertFalse(Arrays.stream(
                        CoordinationIndexedDeliveryEngine.class
                                .getConstructors())
                .anyMatch(constructor -> Arrays.asList(
                                constructor.getParameterTypes())
                        .contains(DocumentProcessor.class)));
        assertEquals(
                0L,
                publicConstructorCount(
                        CoordinationProcessingEngine
                                .AdmittedPlanningAuthority.class));
        assertTrue(Arrays.stream(
                        CoordinationIndexedDeliveryEngine.class
                                .getDeclaredMethods())
                .filter(method -> "prepareAdmitted".equals(
                        method.getName()))
                .allMatch(method -> Arrays.asList(
                                method.getParameterTypes())
                        .contains(CoordinationProcessingEngine
                                .AdmittedPlanningAuthority.class)));
        assertFalse(Arrays.stream(
                        CoordinationIndexedDeliveryEngine.class
                                .getDeclaredMethods())
                .filter(method -> "prepareAdmitted".equals(
                        method.getName()))
                .anyMatch(method -> Arrays.asList(
                                method.getParameterTypes())
                        .contains(Object.class)));
        assertEquals(
                expectedPreparedMethods,
                preparedMethods);
        assertEquals(
                expectedActiveSurfaceMethods,
                activeSurfaceMethods);
        assertEquals(
                expectedPreparedMethods.size(),
                publicMethodCount(
                        CoordinationIndexedDeliveryEngine
                                .Prepared.class));
        assertEquals(
                1L,
                publicConstructorCount(
                        CoordinationIndexedDeliveryEngine
                                .Prepared.class));
        assertEquals(
                0L,
                publicConstructorCount(
                        CoordinationIndexedDeliveryEngine
                                .IndexedActiveSurface.class));
    }

    @Test
    void shouldNotDeclareCoordinationBridgesInLanguagePackages() {
        // given
        Class<?>[] coordinationBoundaries = {
                CoordinationProcessHeaderBridge.class,
                CoordinationSubscriptionProjectionBridge.class,
                CoordinationCurrentRootDeliveryPlanDeriver.class,
                CoordinationIndexedDeliveryEngine.class
        };
        Path[] removedSplitPackageSources = {
                Paths.get("src", "main", "java", "blue", "language",
                        "processor", "CoordinationProcessHeaderBridge.java"),
                Paths.get("src", "main", "java", "blue", "language",
                        "processor", "CoordinationSubscriptionProjectionBridge.java"),
                Paths.get("src", "main", "java", "blue", "language",
                        "processor", "CoordinationCurrentRootDeliveryPlanDeriver.java"),
                Paths.get("src", "main", "java", "blue", "language",
                        "processor", "CoordinationIndexedDeliveryEngine.java")
        };

        // when
        Set<String> misplacedTypes = new TreeSet<String>();
        for (Class<?> boundary : coordinationBoundaries) {
            if (boundary.getName().startsWith("blue.language.")) {
                misplacedTypes.add(boundary.getName());
            }
        }
        Set<String> retainedSplitPackageSources = new TreeSet<String>();
        for (Path source : removedSplitPackageSources) {
            if (Files.exists(source)) {
                retainedSplitPackageSources.add(source.toString());
            }
        }

        // then
        assertTrue(misplacedTypes.isEmpty(), misplacedTypes.toString());
        assertTrue(
                retainedSplitPackageSources.isEmpty(),
                retainedSplitPackageSources.toString());
    }

    @Test
    void shouldKeepConformanceEvidenceCollectorOutOfProductionArtifact() {
        // given
        Path productionCollector = Paths.get(
                "src", "main", "java", "blue", "coordination",
                "processor", "bex",
                "ProcessingEventIdentityEvidence.java");
        Path testCollector = Paths.get(
                "src", "test", "java", "blue", "coordination",
                "processor", "bex",
                "ProcessingEventIdentityEvidence.java");

        // when
        boolean productionExists =
                Files.exists(productionCollector);
        boolean testExists =
                Files.isRegularFile(testCollector);

        // then
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
        // given
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

        // when
        int getterModifiers =
                getter.getModifiers();
        int setterModifiers =
                setter.getModifiers();

        // then
        assertFalse(Modifier.isPublic(getterModifiers));
        assertFalse(Modifier.isProtected(getterModifiers));
        assertFalse(Modifier.isPublic(setterModifiers));
        assertFalse(Modifier.isProtected(setterModifiers));
    }

    @Test
    void shouldCreatePlanningFacadesOnlyThroughPublicDeliveryPlanning() {
        // given
        Class<?>[] factoryOwnedFacades = {
                CoordinationSubscriptionProjector.class,
                CoordinationIndexedDeliveryPlanner.class
        };

        // when
        Set<String> publicConstructors =
                publicConstructorOwners(
                        factoryOwnedFacades);

        // then
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

    private static Set<String> publicMethodSignatures(
            Class<?> type) {
        Set<String> result = new TreeSet<String>();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && !method.isSynthetic()) {
                StringBuilder signature = new StringBuilder(
                        method.getName()).append('(');
                Class<?>[] parameters = method.getParameterTypes();
                for (int index = 0; index < parameters.length; index++) {
                    if (index > 0) signature.append(',');
                    signature.append(parameters[index].getName());
                }
                signature.append(")->")
                        .append(method.getReturnType().getName());
                result.add(signature.toString());
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

    private static Set<String> typeNames(
            Class<?>[] types) {
        Set<String> result = new TreeSet<String>();
        for (Class<?> type : types) {
            result.add(type.getName());
        }
        return result;
    }
}
