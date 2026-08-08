package blue.coordination.engine;

import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.ProcessingBundlePlanBinding;
import blue.coordination.engine.internal.CoordinationFragmentTransitionPlanner;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stable reflection contract for the storage-neutral engine facade. */
final class CoordinationProcessingEngineApiTest {

    @Test
    void shouldKeepTheCompletePublicEngineFacadeExact() {
        // given
        Class<CoordinationProcessingEngine> engine =
                CoordinationProcessingEngine.class;
        Set<String> expected = signatures(
                "addDocument(blue.coordination.engine.api.DocumentRegistration)"
                        + "->blue.coordination.engine.api.DocumentAdmissionResult",
                "builder()->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "checkpointCurrentRootViews(java.util.Collection)"
                        + "->java.util.Map",
                "close()->void",
                "commit(blue.coordination.engine.api.CoordinationTransition)"
                        + "->blue.coordination.engine.api.CommitOutcome",
                "environmentIdentity()->java.lang.String",
                "eventAdmissionMetrics()"
                        + "->blue.coordination.engine.memory."
                        + "CoordinationEventAdmissionMetrics$Snapshot",
                "epoch(blue.coordination.engine.api.DocumentSessionId,long)"
                        + "->blue.coordination.engine.api.DocumentEpochSnapshot",
                "execute(blue.coordination.engine.api.CoordinationProcessingPlan)"
                        + "->blue.coordination.engine.api.CoordinationTransition",
                "installPreparedRootContextAfterPublication("
                        + "blue.coordination.engine.api.CoordinationTransition,"
                        + "blue.coordination.engine.api.CommitOutcome)"
                        + "->boolean",
                "plan(blue.coordination.engine.api.ProcessRequest)"
                        + "->blue.coordination.engine.api.CoordinationProcessingPlan",
                "planIndexed(blue.coordination.engine.api.DocumentSessionId,long,"
                        + "blue.coordination.engine.api.StoredCoordinationEvent,"
                        + "java.util.List,blue.coordination.engine.api.PrefetchPolicy)"
                        + "->blue.coordination.engine.api.CoordinationProcessingPlan",
                "prepareEvent(blue.language.model.Node,"
                        + "blue.language.processor.ExternalOrderKey)"
                        + "->blue.coordination.engine.api.StoredCoordinationEvent",
                "prepareEvent(java.lang.String,blue.language.model.Node,"
                        + "blue.language.processor.ExternalOrderKey)"
                        + "->blue.coordination.engine.api.StoredCoordinationEvent",
                "prepareRootContext(blue.coordination.engine.api."
                        + "ManagedDocumentSnapshot)->void",
                "prepareRootContextFromCheckpoint("
                        + "blue.coordination.engine.api."
                        + "ManagedDocumentSnapshot,java.util.Map)->void",
                "primeEventAdmission(java.lang.String,"
                        + "blue.language.model.Node)->void",
                "processAndCommit(blue.coordination.engine.api.ProcessRequest)"
                        + "->blue.coordination.engine.api.CommitOutcome",
                "projectionFastPathMetrics()"
                        + "->blue.coordination.fastpath."
                        + "FastPathWorkMetrics$Snapshot",
                "removeDocument(blue.coordination.engine.api.DocumentSessionId,long)"
                        + "->blue.coordination.engine.api.DocumentRemovalResult",
                "rootViewCacheSnapshot()"
                        + "->blue.coordination.engine.api."
                        + "CoordinationRootViewCacheSnapshot",
                "session(blue.coordination.engine.api.DocumentSessionId)"
                        + "->blue.coordination.engine.api.ManagedDocumentSnapshot");

        // when
        Set<String> actual = publicMethodSignatures(engine);

        // then
        assertTrue(Modifier.isPublic(engine.getModifiers()));
        assertTrue(Modifier.isFinal(engine.getModifiers()));
        assertTrue(AutoCloseable.class.isAssignableFrom(engine));
        assertEquals(expected, actual);
    }

    @Test
    void shouldKeepTheCompletePublicEngineBuilderExact() {
        // given
        Class<CoordinationProcessingEngine.Builder> builder =
                CoordinationProcessingEngine.Builder.class;
        Set<String> expected = signatures(
                "build()->blue.coordination.engine.CoordinationProcessingEngine",
                "bundleLoader(blue.coordination.engine.spi."
                        + "CoordinationProcessingBundleLoader)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "contracts(blue.language.processor.BlueContracts)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "documentProcessor(blue.language.processor.DocumentProcessor)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "environmentIdentity(java.lang.String)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "externalOrderPolicyIdentity(java.lang.String)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "fragmentStore(blue.coordination.engine.spi."
                        + "CoordinationFragmentStore)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "gasScheduleIdentity(java.lang.String)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "hostQuotaSchedule(blue.coordination.processor."
                        + "CoordinationHostQuotaSchedule)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "initialSubscriptionPolicyIdentity(java.lang.String)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "maximumCachedEventAdmissionWeightBytes(long)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "maximumCachedEventAdmissions(int)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "maximumCachedFragmentEvidence(int)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "maximumCachedFragmentEvidenceWeightBytes(long)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "observer(blue.coordination.engine.spi."
                        + "CoordinationProcessingEngineObserver)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "providerEvidenceDomain(java.lang.String)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "retainedRootViews(java.util.Map)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "rootViewCacheMaximumSize(int)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "sessionStore(blue.coordination.engine.spi."
                        + "CoordinationSessionStore)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "transferRuntimeOwnership(boolean)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder",
                "transitionMemoStore(blue.coordination.engine.spi."
                        + "CoordinationTransitionMemoStore)"
                        + "->blue.coordination.engine."
                        + "CoordinationProcessingEngine$Builder");

        // when
        Set<String> actual = publicMethodSignatures(builder);

        // then
        assertTrue(Modifier.isPublic(builder.getModifiers()));
        assertTrue(Modifier.isFinal(builder.getModifiers()));
        assertEquals(expected, actual);
        assertEquals(1, publicConstructorSignatures(builder).size());
        assertEquals(signatures("()"), publicConstructorSignatures(builder));
    }

    @Test
    void shouldKeepIncrementalPlannerConstructionAndOperationExact() {
        // given
        Class<CoordinationFragmentTransitionPlanner> planner =
                CoordinationFragmentTransitionPlanner.class;
        Set<String> expectedConstructors = signatures(
                "(blue.coordination.processor.CoordinationDocumentSplitter)",
                "(blue.coordination.processor.CoordinationDocumentSplitter,"
                        + "blue.language.provider.NodeProvider)");
        Set<String> expectedMethods = signatures(
                "plan(blue.coordination.engine.api."
                        + "CoordinationFragmentInventory,blue.language.model.Node,"
                        + "blue.coordination.processor.CoordinationPreparedDelivery,"
                        + "blue.coordination.processor."
                        + "CoordinationSubscriptionUpdate)"
                        + "->blue.coordination.engine.api."
                        + "CoordinationFragmentTransition",
                "planVerified(blue.coordination.engine."
                        + "CoordinationProcessingEngine$"
                        + "VerifiedNodeAccessAuthority,"
                        + "blue.coordination.engine.api."
                        + "CoordinationFragmentInventory,"
                        + "blue.language.model.Node,java.lang.String,"
                        + "blue.coordination.engine.fastpath."
                        + "VerifiedFragmentTransitionFrontier,"
                        + "blue.coordination.processor."
                        + "CoordinationPreparedDelivery,"
                        + "blue.coordination.processor."
                        + "CoordinationSubscriptionUpdate)"
                        + "->blue.coordination.engine.api."
                        + "CoordinationFragmentTransition",
                "workSnapshot()->blue.coordination.engine.api."
                        + "CoordinationFragmentTransitionWorkSnapshot");

        // when
        Set<String> constructors = publicConstructorSignatures(planner);
        Set<String> methods = publicMethodSignatures(planner);

        // then
        assertEquals(expectedConstructors, constructors);
        assertEquals(expectedMethods, methods);
        assertEquals(
                0,
                CoordinationProcessingEngine.VerifiedNodeAccessAuthority
                        .class.getConstructors().length);
    }

    @Test
    void shouldRetainTheLegacyBundleConstructorAndExposeExactPlanBinding() {
        // given
        Set<String> expectedBundleConstructors = signatures(
                "(blue.language.provider.NodeProvider,java.util.Collection,"
                        + "java.util.Collection,int,long)",
                "(blue.language.provider.NodeProvider,java.util.Collection,"
                        + "java.util.Collection,int,long,"
                        + "blue.coordination.engine.api."
                        + "ProcessingBundlePlanBinding)");
        Set<String> expectedBundleMethods = signatures(
                "backendLoadedBlueIds()->java.util.Set",
                "batchCount()->int",
                "exactProvider()->blue.language.provider.NodeProvider",
                "loadedBytes()->long",
                "planBinding()->java.util.Optional",
                "prefetchedBlueIds()->java.util.List");
        Set<String> expectedBindingMethods = signatures(
                "environmentIdentity()->java.lang.String",
                "epoch()->long",
                "eventBlueId()->java.lang.String",
                "planIdentity()->java.lang.String",
                "rootBlueId()->java.lang.String",
                "sessionId()->blue.coordination.engine.api.DocumentSessionId",
                "subscriptionDigest()->java.lang.String");

        // when
        Set<String> bundleConstructors = publicConstructorSignatures(
                LoadedProcessingBundle.class);
        Set<String> bundleMethods = publicMethodSignatures(
                LoadedProcessingBundle.class);
        Set<String> bindingConstructors = publicConstructorSignatures(
                ProcessingBundlePlanBinding.class);
        Set<String> bindingMethods = publicMethodSignatures(
                ProcessingBundlePlanBinding.class);

        // then
        assertEquals(expectedBundleConstructors, bundleConstructors);
        assertEquals(expectedBundleMethods, bundleMethods);
        assertEquals(signatures(
                        "(blue.coordination.engine.api.DocumentSessionId,long,"
                                + "java.lang.String,java.lang.String,"
                                + "java.lang.String,java.lang.String,"
                                + "java.lang.String)"),
                bindingConstructors);
        assertEquals(expectedBindingMethods, bindingMethods);
    }

    private static Set<String> publicMethodSignatures(Class<?> type) {
        Set<String> result = new TreeSet<String>();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && !method.isSynthetic()) {
                result.add(methodSignature(method));
            }
        }
        return result;
    }

    private static Set<String> publicConstructorSignatures(Class<?> type) {
        Set<String> result = new TreeSet<String>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers())
                    && !constructor.isSynthetic()) {
                result.add(parameterSignature(
                        constructor.getParameterTypes()));
            }
        }
        return result;
    }

    private static String methodSignature(Method method) {
        return method.getName()
                + parameterSignature(method.getParameterTypes())
                + "->"
                + method.getReturnType().getName();
    }

    private static String parameterSignature(Class<?>[] parameterTypes) {
        StringBuilder result = new StringBuilder("(");
        for (int index = 0; index < parameterTypes.length; index++) {
            if (index > 0) result.append(',');
            result.append(parameterTypes[index].getName());
        }
        return result.append(')').toString();
    }

    private static Set<String> signatures(String... values) {
        return new TreeSet<String>(Arrays.asList(values));
    }
}
