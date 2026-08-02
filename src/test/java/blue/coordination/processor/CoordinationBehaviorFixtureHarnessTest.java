package blue.coordination.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.CoordinationConfiguredProcessorFactory;
import blue.language.processor.CoordinationRoutingHarness;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.model.ChannelContract;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationBehaviorFixtureHarnessTest {
    @Test
    void shouldKeepMandateBackedEndToEndResultStableAcrossRepresentations() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-e2e-01@inline");

        // When
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssertWithVariantGroup(
                        fixtureCase);

        // Then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldProcessPureReferenceTimelineHeadersWithSelectiveEvidence() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-e2e-01@references");

        // When
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // Then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldRouteReferenceBackedEndToEndCasesToBobWithoutDemandingOpaqueMandateDocument() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        List<String> caseIds =
                Arrays.asList(
                        "coord-e2e-01@references",
                        "coord-e2e-01@partial",
                        "coord-e2e-01@fragmented");
        List<CoordinationBehaviorFixtureHarness.Execution>
                executions =
                new ArrayList<CoordinationBehaviorFixtureHarness.Execution>();

        // When
        for (String caseId : caseIds) {
            executions.add(
                    harness.executeAndAssert(
                            fixtureCase(
                                    harness,
                                    caseId)));
        }

        // Then
        for (CoordinationBehaviorFixtureHarness.Execution
                execution : executions) {
            assertEquals(
                    "bob",
                    execution.projection(
                            "feeder.handlerChannelKey"),
                    execution.caseId());
            assertEquals(
                    Collections.singletonList(
                            "approve"),
                    execution.projection(
                            "trace.handlerExecutions"),
                    execution.caseId());
            assertEquals(
                    Boolean.TRUE,
                    execution.projection(
                            "trace.processingEventBlueIdStable"),
                    execution.caseId());
            Object semanticDemands =
                    execution.projection(
                            "trace.semanticDemands");
            assertTrue(
                    semanticDemands instanceof List,
                    execution.caseId());
            assertFalse(
                    ((List<?>) semanticDemands).contains(
                            "CwqzJwwpNCJZmb51FjL2JUQ8ijhExr9FSFrLQz2zJg7j"),
                    execution.caseId());
        }
    }

    @Test
    void shouldAvoidDemandingDecoyBodiesForReferenceEndToEndProcessing() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-e2e-02@references");

        // When
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // Then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldAvoidDemandingDecoyBodyForReferenceSplitProcessing() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-split-02@references");

        // When
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // Then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldAvoidDemandingDecoyBodiesWhenDescendantsEmitNoRootEvent() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-split-08@no-root-emission");

        // When
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // Then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldAvoidDemandingDecoyBodiesWhenRootEmitsPublicEvents() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-split-09@root-emits");

        // When
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // Then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldRecordSelectedDeepHandlerLocation() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase fixtureCase =
                fixtureCase(harness, "coord-split-03@default");

        // When
        CoordinationBehaviorFixtureHarness.Execution execution =
                harness.executeAndAssert(fixtureCase);

        // Then
        assertEquals(fixtureCase.caseId(), execution.caseId());
    }

    @Test
    void shouldKeepRootOnlyOperationOutOfEmbeddedScopes() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase fixtureCase =
                fixtureCase(harness, "coord-split-04@default");

        // When
        CoordinationBehaviorFixtureHarness.Execution execution =
                harness.executeAndAssert(fixtureCase);

        // Then
        assertEquals(fixtureCase.caseId(), execution.caseId());
    }

    @Test
    void shouldRecordDirectChildReactiveHandlerLocations() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase fixtureCase =
                fixtureCase(harness, "coord-split-05@default");

        // When
        CoordinationBehaviorFixtureHarness.Execution execution =
                harness.executeAndAssert(fixtureCase);

        // Then
        assertEquals(fixtureCase.caseId(), execution.caseId());
    }

    @Test
    void shouldSplitInheritedEffectiveContracts() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase fixtureCase =
                fixtureCase(harness, "coord-split-06@default");

        // When
        CoordinationBehaviorFixtureHarness.Execution execution =
                harness.executeAndAssert(fixtureCase);

        // Then
        assertEquals(fixtureCase.caseId(), execution.caseId());
    }

    @Test
    void shouldComparePureReferenceWithCanonicalScalar() {
        // Given
        BigInteger expected = BigInteger.valueOf(7L);
        Node actual = new Node().blueId(
                BlueIdCalculator.INSTANCE
                        .calculate(expected));

        // When
        boolean equivalent =
                CoordinationBehaviorFixtureHarness
                        .equivalentValues(
                                actual, expected);

        // Then
        assertTrue(equivalent);
    }

    @Test
    void shouldComparePureReferenceWithCanonicalStructuredValue() {
        // Given
        Map<String, Object> expected =
                new LinkedHashMap<String, Object>();
        expected.put(
                "values",
                Arrays.<Object>asList(
                        BigInteger.ONE,
                        "two"));
        Node actual = new Node().blueId(
                BlueIdCalculator.INSTANCE
                        .calculate(expected));

        // When
        boolean equivalent =
                CoordinationBehaviorFixtureHarness
                        .equivalentValues(
                                actual, expected);

        // Then
        assertTrue(equivalent);
    }

    @Test
    void shouldRejectUnresolvedNonScalarComparison() {
        // Given
        Node unresolved =
                new Node().type(
                        new Node().blueId(
                                "8aohWT7jcoaC1j2siQzBxoKM8HhQ4HF13BkZDNnq5UHf"));

        // When
        boolean equivalent =
                CoordinationBehaviorFixtureHarness
                        .equivalentValues(
                                unresolved, "alice");

        // Then
        assertFalse(equivalent);
    }

    @Test
    void shouldStrictlyDecodeAllAuthoredBehaviorExecutionCases() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();

        // When
        List<CoordinationBehaviorFixtureHarness.FixtureCase>
                cases = harness.loadCases();

        // Then
        assertEquals(65, cases.size());
        assertEquals(
                65,
                cases.stream()
                        .map(CoordinationBehaviorFixtureHarness
                                .FixtureCase::caseId)
                        .distinct()
                        .count());
        assertEquals(
                55,
                cases.stream()
                        .map(CoordinationBehaviorFixtureHarness
                                .FixtureCase::resource)
                        .distinct()
                        .count());
    }

    @Test
    void shouldExecuteAllCompositeAndDirectMyOsSourcesInCanonicalOrder() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                harness.loadCases()
                        .stream()
                        .filter(candidate ->
                                "coord-chan-07@default"
                                        .equals(
                                                candidate
                                                        .caseId()))
                        .findFirst()
                        .orElseThrow(() ->
                                new AssertionError(
                                        "Missing MyOS "
                                                + "conformance "
                                                + "fixture"));

        // When
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssertWithVariantGroup(
                        fixtureCase);

        // Then
        assertEquals(
                "coord-chan-07@default",
                execution.caseId());
    }

    @Test
    void shouldExecuteMandateAndTimelineCasesIndependently() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                mandateCase =
                fixtureCase(
                        harness,
                        "coord-mand-07@default");
        CoordinationBehaviorFixtureHarness.FixtureCase
                timelineChannelCase =
                fixtureCase(
                        harness,
                        "coord-chan-01@default");

        // When
        CoordinationBehaviorFixtureHarness.Execution
                mandateExecution =
                harness.executeAndAssertWithVariantGroup(
                        mandateCase);
        CoordinationBehaviorFixtureHarness.Execution
                timelineChannelExecution =
                harness.executeAndAssertWithVariantGroup(
                        timelineChannelCase);

        // Then
        assertEquals(
                "coord-mand-07@default",
                mandateExecution.caseId());
        assertEquals(
                "coord-chan-01@default",
                timelineChannelExecution.caseId());
    }

    @Test
    void shouldRollbackDocumentUpdateLoopToExactInitializedRoot() {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-fail-02@default");

        // When
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // Then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
        assertEquals(
                "gas-limit-exceeded",
                execution.projection(
                        "result.status"));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("behaviorCases")
    void shouldExecuteOneAuthoredBehaviorCaseAgainstProductionApis(
            CoordinationBehaviorFixtureHarness.FixtureCase
                    fixtureCase) {
        // Given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();

        // When
        CoordinationBehaviorFixtureHarness.Execution
                execution;
        try {
            execution =
                    harness.executeAndAssertWithVariantGroup(
                            fixtureCase);
        } catch (CoordinationBehaviorFixtureHarness
                 .FixtureExecutionException failure) {
            if (isMandateRefreshProbe(
                    fixtureCase.caseId())) {
                classifyMandateRefreshFixtureFailure(
                        fixtureCase,
                        failure);
            }
            throw failure;
        }

        // Then
        assertNotNull(execution);
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    private static boolean isMandateRefreshProbe(
            String caseId) {
        return Arrays.asList(
                "coord-mand-02@default",
                "coord-mand-03@default",
                "coord-mand-04@default",
                "coord-mand-05@default",
                "coord-mand-06@default")
                .contains(caseId);
    }

    private static void classifyMandateRefreshFixtureFailure(
            CoordinationBehaviorFixtureHarness.FixtureCase
                    fixtureCase,
            CoordinationBehaviorFixtureHarness
                    .FixtureExecutionException failure) {
        CoordinationBehaviorFixtureHarness.Execution
                execution = failure.execution();
        String caseId = fixtureCase.caseId();
        boolean exactDefect = false;
        if (execution != null
                && Arrays.asList(
                "coord-mand-02@default",
                "coord-mand-03@default",
                "coord-mand-04@default",
                "coord-mand-05@default")
                .contains(caseId)) {
            Map<String, String> expectedStatus =
                    new LinkedHashMap<String, String>();
            expectedStatus.put(
                    "coord-mand-02@default",
                    "Coordination/Status Failed");
            expectedStatus.put(
                    "coord-mand-03@default",
                    "Mandate/Status Active");
            expectedStatus.put(
                    "coord-mand-04@default",
                    "Mandate/Status Authority Confirmed");
            expectedStatus.put(
                    "coord-mand-05@default",
                    "Mandate/Status Terminated");
            exactDefect =
                    "success".equals(
                            execution.projection(
                                    "result.status"))
                            && execution.projection(
                            "result.diagnostic.category")
                            == null
                            && "Coordination/Status Pending"
                            .equals(
                                    execution.projection(
                                            "mandate.status"))
                            && execution.projection(
                            "feeder.status") == null
                            && execution.projection(
                            "feeder.reason") == null
                            && failure.getMessage()
                            .contains(
                                    "mandate.status equals expected "
                                            + expectedStatus
                                            .get(caseId)
                                            + " but was "
                                            + "Coordination/Status Pending");
        } else if (execution != null
                && "coord-mand-06@default"
                .equals(caseId)) {
            exactDefect =
                    "runtime-fatal".equals(
                            execution.projection(
                                    "result.status"))
                            && "TypeGeneralizationFailure"
                            .equals(
                                    execution.projection(
                                            "result.diagnostic.category"))
                            && String.valueOf(
                            execution.projection(
                                    "result.diagnostic.message"))
                            .contains(
                                    "Source node value: terminated, "
                                            + "target node value: pending")
                            && Collections.emptyList()
                            .equals(
                                    execution.projection(
                                            "feeder."
                                                    + "eligibleSourceChannelKeys"))
                            && failure.getMessage()
                            .contains(
                                    "feeder selected source keys "
                                            + "[authorityHolderChannel, "
                                            + "mandateTerminationChannel] "
                                            + "but the public delivery "
                                            + "trace reported []");
        }
        if (exactDefect) {
            ExternalBlockerProbeAssertions.knownDefect(
                    "Language mandate effective-contract refresh defect:",
                    caseId + ": "
                            + "status="
                            + execution.projection(
                            "result.status")
                            + ", category="
                            + execution.projection(
                            "result.diagnostic.category")
                            + ", diagnostic="
                            + execution.projection(
                            "result.diagnostic.message")
                            + ", mandate.status="
                            + execution.projection(
                            "mandate.status")
                            + ", handlerExecutions="
                            + execution.projection(
                            "trace.handlerExecutions")
                            + ", sourceKeys="
                            + execution.projection(
                            "feeder."
                                    + "eligibleSourceChannelKeys"));
        }
        ExternalBlockerProbeAssertions.invalidProbe(
                "mandate-effective-contract-type-refresh",
                caseId + ": " + failure.getMessage()
                        + ", execution="
                        + (execution != null
                        ? "status="
                        + execution.projection(
                        "result.status")
                        + ", category="
                        + execution.projection(
                        "result.diagnostic.category")
                        + ", mandate.status="
                        + execution.projection(
                        "mandate.status")
                        : "unavailable"));
    }

    @Test
    void shouldKeepCandidateExecutorFreeOfReceiptWriting() {
        // Given
        List<Method> methods = Arrays.asList(
                CoordinationBehaviorFixtureHarness
                        .class.getDeclaredMethods());
        String manifest =
                CoordinationTestResources.readResource(
                        "coordination/conformance/"
                                + "manifest.yaml");
        String behaviorInventory =
                CoordinationTestResources.readResource(
                        "coordination/conformance/"
                                + "behavior-fixtures.yaml");

        // When
        boolean ownsReceiptWriter =
                methods.stream()
                        .map(Method::getName)
                        .anyMatch(name ->
                                name.toLowerCase(
                                        java.util.Locale.ROOT)
                                        .contains("receipt"));

        // Then
        assertFalse(ownsReceiptWriter);
        assertTrue(manifest.contains(
                "status: candidate"));
        assertTrue(behaviorInventory.contains(
                "receiptWritten: false"));
    }

    @Test
    void shouldProjectOnlyForbiddenBodyDemandsInObservedTraceOrder() {
        // Given
        List<String> semanticDemands =
                Arrays.asList(
                        "/",
                        "allowed-blue-id",
                        "forbidden-blue-id-2",
                        "/contracts/allowed",
                        "forbidden-blue-id-1");
        Set<String> forbiddenBlueIds =
                new LinkedHashSet<String>(
                        Arrays.asList(
                                "forbidden-blue-id-1",
                                "forbidden-blue-id-2"));

        // When
        List<String> projection =
                CoordinationBehaviorFixtureHarness
                        .forbiddenDemandProjection(
                                semanticDemands,
                                forbiddenBlueIds);

        // Then
        assertEquals(
                Arrays.asList(
                        "forbidden-blue-id-2",
                        "forbidden-blue-id-1"),
                projection);
    }

    @Test
    void shouldBuildPartialRepresentationFromOneExactRootFetch() {
        // Given
        Node fragment =
                new Node().properties(
                        "state",
                        new Node().value("ready"));
        String rootBlueId =
                BlueIdCalculator.calculateBlueId(
                        fragment);
        List<String> demands =
                new ArrayList<String>();
        NodeProvider provider = blueId -> {
            demands.add(blueId);
            return Collections.singletonList(
                    fragment);
        };

        // When
        Node partial =
                CoordinationBehaviorFixtureHarness
                        .exactPartialRootFragment(
                                rootBlueId,
                                provider);

        // Then
        assertEquals(
                Collections.singletonList(
                        rootBlueId),
                demands);
        assertFalse(partial.isReferenceOnly());
        assertEquals(
                rootBlueId,
                BlueIdCalculator.calculateBlueId(
                        partial));
    }

    @Test
    void shouldRejectPartialRepresentationWithMismatchedRootIdentity() {
        // Given
        Node expected =
                new Node().value("expected");
        Node mismatched =
                new Node().value("mismatched");
        String rootBlueId =
                BlueIdCalculator.calculateBlueId(
                        expected);
        NodeProvider provider =
                ignored ->
                        Collections.singletonList(
                                mismatched);

        // When
        CoordinationBehaviorFixtureHarness
                .FixtureExecutionException failure =
                assertThrows(
                        CoordinationBehaviorFixtureHarness
                                .FixtureExecutionException.class,
                        () -> CoordinationBehaviorFixtureHarness
                                .exactPartialRootFragment(
                                        rootBlueId,
                                        provider));

        // Then
        assertTrue(failure.getMessage()
                .contains("changed BlueId"));
    }

    @Test
    void shouldPrefetchOnlyTheDeterministicBoundedWindow() {
        // Given
        List<String> backendFetches =
                new ArrayList<String>();
        NodeProvider backend = blueId -> {
            backendFetches.add(blueId);
            return Collections.singletonList(
                    new Node().value(blueId));
        };
        CoordinationBehaviorFixtureHarness
                .BoundedPrefetchProvider provider =
                new CoordinationBehaviorFixtureHarness
                        .BoundedPrefetchProvider(
                                backend,
                                Arrays.asList(
                                        "d", "b", "a", "c"),
                                2);

        // When
        provider.fetchByBlueId("c");
        provider.fetchByBlueId("d");
        provider.fetchByBlueId("a");

        // Then
        assertEquals(
                Arrays.asList(
                        "c", "d", "a", "b"),
                backendFetches);
    }

    @Test
    void shouldSelectStructuralAndAllowedBodyFragmentsIndependently() {
        // Given
        Map<String, Set<String>>
                bodyKeysByBlueId =
                new LinkedHashMap<String, Set<String>>();
        bodyKeysByBlueId.put(
                "shared-body",
                new LinkedHashSet<String>(
                        Arrays.asList(
                                "selected",
                                "shared-alias")));
        bodyKeysByBlueId.put(
                "decoy-body",
                Collections.singleton("decoy"));

        // When
        Set<String> selected =
                CoordinationBehaviorFixtureHarness
                        .selectedFragmentBlueIds(
                                Arrays.asList(
                                        "root-fragment",
                                        "scope-fragment"),
                                bodyKeysByBlueId,
                                Collections.singleton(
                                        "selected"));

        // Then
        assertEquals(
                new LinkedHashSet<String>(
                        Arrays.asList(
                                "root-fragment",
                                "scope-fragment",
                                "shared-body")),
                selected);
    }

    @Test
    void shouldRejectUnknownAllowedBodyKeyForSelectedBytes() {
        // Given
        Map<String, Set<String>>
                bodyKeysByBlueId =
                Collections.singletonMap(
                        "known-body",
                        Collections.singleton(
                                "known"));

        // When
        CoordinationBehaviorFixtureHarness
                .FixtureExecutionException failure =
                assertThrows(
                        CoordinationBehaviorFixtureHarness
                                .FixtureExecutionException.class,
                        () -> CoordinationBehaviorFixtureHarness
                                .selectedFragmentBlueIds(
                                        Collections.singleton(
                                                "root-fragment"),
                                        bodyKeysByBlueId,
                                        Collections.singleton(
                                                "unknown")));

        // Then
        assertTrue(failure.getMessage()
                .contains("absent from SplitGraph metadata"));
    }

    @Test
    void shouldPassAuthoredRevisionEvidenceToLanguageThreeArgumentProcess() {
        // Given
        ProbeRuntime runtime =
                ProbeRuntime.create();
        VerifiedExecutionEvidence evidence =
                runtime.evidence(
                        29L, "accepted");
        DocumentProcessor processor =
                CoordinationConfiguredProcessorFactory
                        .withExecutionEvidencePlan(
                                runtime.blue,
                                null,
                                evidence);

        // When
        ProcessingDebugResult debug =
                CoordinationBehaviorFixtureHarness
                        .processDocumentWithVerifiedEvidence(
                                processor,
                                runtime.initialized.document(),
                                runtime.event,
                                evidence);

        // Then
        assertEquals(
                ProcessorStatus.SUCCESS,
                runtime.initialized.status());
        assertEquals(
                ProcessorStatus.SUCCESS,
                debug.processResult().status());
        assertNotNull(
                debug.platformCommitCompanion());
        assertEquals(
                29L,
                debug.platformCommitCompanion()
                        .expectedRootRevision());
        processor.close();
        runtime.close();
    }

    @Test
    void shouldRejectAuthoredRevisionThatDiffersFromTheVerifiedPlan() {
        // Given
        ProbeRuntime runtime =
                ProbeRuntime.create();
        VerifiedExecutionEvidence retained =
                runtime.evidence(
                        29L, "accepted");
        VerifiedExecutionEvidence stale =
                runtime.evidence(
                        30L, "accepted");
        DocumentProcessor processor =
                CoordinationConfiguredProcessorFactory
                        .withExecutionEvidencePlan(
                                runtime.blue,
                                null,
                                retained);

        // When
        ProcessingDebugResult debug =
                CoordinationBehaviorFixtureHarness
                        .processDocumentWithVerifiedEvidence(
                                processor,
                                runtime.initialized.document(),
                                runtime.event,
                                stale);

        // Then
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                debug.processResult().status());
        assertEquals(
                ProcessorErrorCategory
                        .InvalidExternalChannelSnapshot,
                debug.processResult()
                        .diagnostic()
                        .category());
        assertNull(
                debug.platformCommitCompanion());
        processor.close();
        runtime.close();
    }

    @Test
    void shouldRejectAuthoredSourceThatDoesNotAcceptTheExactEvent() {
        // Given
        ProbeRuntime runtime =
                ProbeRuntime.create();

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> runtime.evidence(
                                29L,
                                "rejected"));

        // Then
        assertTrue(failure.getMessage()
                .contains(
                        "exact accepting source sequence"));
        assertTrue(failure.getMessage()
                .contains("/:accepted"));
        assertTrue(failure.getMessage()
                .contains("/:rejected"));
        runtime.close();
    }

    @Test
    void shouldRejectMismatchedAuthoredFeederRevisionPair() {
        // Given
        Node input = new Node().properties(
                "feeder",
                new Node()
                        .properties(
                                "managedRootRevision",
                                new Node().value(7))
                        .properties(
                                "indexedRootRevision",
                                new Node().value(8))
                        .properties(
                                "eligibleSourceChannelKeys",
                                new Node().items(
                                        new Node().value(
                                                "accepted"))));

        // When
        CoordinationBehaviorFixtureHarness
                .FixtureExecutionException failure =
                assertThrows(
                        CoordinationBehaviorFixtureHarness
                                .FixtureExecutionException.class,
                        () -> CoordinationBehaviorFixtureHarness
                                .authoredFeederEvidence(
                                        input,
                                        "revision-mismatch"));

        // Then
        assertTrue(failure.getMessage()
                .contains("revision-complete"));
    }

    @Test
    void shouldParseAuthoredRevisionAndSourceEvidence() {
        // Given
        Node input = new Node().properties(
                "feeder",
                new Node()
                        .properties(
                                "managedRootRevision",
                                new Node().value(9))
                        .properties(
                                "indexedRootRevision",
                                new Node().value(9))
                        .properties(
                                "eligibleSourceChannelKeys",
                                new Node().items(
                                        new Node().value(
                                                "root"),
                                        new Node().value(
                                                "/child:embedded"))));

        // When
        CoordinationBehaviorFixtureHarness
                .AuthoredFeederEvidence evidence =
                CoordinationBehaviorFixtureHarness
                        .authoredFeederEvidence(
                                input,
                                "authored-evidence");

        // Then
        assertNotNull(evidence);
        assertEquals(
                9L,
                evidence.managedRootRevision());
        assertEquals(
                9L,
                evidence.indexedRootRevision());
        assertEquals(
                Arrays.asList(
                        "root",
                        "/child:embedded"),
                evidence
                        .eligibleSourceChannelKeys());
        assertEquals(
                2,
                evidence.deliveryOccurrences(
                        "authored-evidence")
                        .length);
    }

    @Test
    void shouldRejectIncompleteAuthoredFeederEvidence() {
        // Given
        Node input = new Node().properties(
                "feeder",
                new Node()
                        .properties(
                                "managedRootRevision",
                                new Node().value(9))
                        .properties(
                                "eligibleSourceChannelKeys",
                                new Node().items(
                                        new Node().value(
                                                "root"))));

        // When
        CoordinationBehaviorFixtureHarness
                .FixtureExecutionException failure =
                assertThrows(
                        CoordinationBehaviorFixtureHarness
                                .FixtureExecutionException.class,
                        () -> CoordinationBehaviorFixtureHarness
                                .authoredFeederEvidence(
                                        input,
                                        "incomplete-evidence"));

        // Then
        assertTrue(failure.getMessage()
                .contains(
                        "managedRootRevision, "
                                + "indexedRootRevision, and "
                                + "eligibleSourceChannelKeys"));
    }

    private static final class ProbeRuntime
            implements AutoCloseable {
        private final Blue blue;
        private final Node contractSurface;
        private final Node event;
        private final DocumentProcessingResult initialized;

        private ProbeRuntime(
                Blue blue,
                Node contractSurface,
                Node event,
                DocumentProcessingResult initialized) {
            this.blue = blue;
            this.contractSurface =
                    contractSurface;
            this.event = event;
            this.initialized = initialized;
        }

        private static ProbeRuntime create() {
            Blue blue = new Blue();
            Node type =
                    new Node().name(
                            ProbeChannel.class
                                    .getSimpleName());
            String typeBlueId =
                    BlueIdCalculator
                            .calculateBlueId(type);
            blue.registerExternalContractType(
                    typeBlueId,
                    type,
                    new ProbeChannelProcessor());
            Map<String, Node> channels =
                    new LinkedHashMap<String, Node>();
            channels.put(
                    "accepted",
                    channel(
                            typeBlueId,
                            "accepted"));
            channels.put(
                    "rejected",
                    channel(
                            typeBlueId,
                            "rejected"));
            Node root = new Node().properties(
                    "contracts",
                    new Node().properties(
                            channels));
            Node event = new Node()
                    .properties(
                            "id",
                            new Node().value(
                                    "probe-event"))
                    .properties(
                            "subscriptionKey",
                            new Node().value(
                                    "accepted"));
            DocumentProcessingResult initialized =
                    blue.getDocumentProcessor()
                            .initializeDocument(root);
            return new ProbeRuntime(
                    blue,
                    root,
                    event,
                    initialized);
        }

        private VerifiedExecutionEvidence evidence(
                long revision,
                String authoredSource) {
            if (!ProcessorStatus.SUCCESS.equals(
                    initialized.status())) {
                throw new AssertionError(
                        "Probe initialization failed: "
                                + initialized.diagnostic());
            }
            return CoordinationRoutingHarness
                    .evidence(
                            blue.getDocumentProcessor(),
                            contractSurface,
                            initialized.document(),
                            event,
                            revision,
                            revision,
                            CoordinationRoutingHarness
                                    .DeliveryOccurrence
                                    .at(
                                            "/",
                                            authoredSource));
        }

        private static Node channel(
                String typeBlueId,
                String subscriptionKey) {
            return new Node()
                    .type(
                            new Node().blueId(
                                    typeBlueId))
                    .properties(
                            "subscriptionKey",
                            new Node().value(
                                    subscriptionKey));
        }

        @Override
        public void close() {
            blue.close();
        }
    }

    public static final class ProbeChannel
            extends ChannelContract {
        private String subscriptionKey;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey =
                    subscriptionKey;
        }
    }

    private static final class ProbeChannelProcessor
            implements ChannelProcessor<ProbeChannel> {
        @Override
        public Class<ProbeChannel> contractType() {
            return ProbeChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<ProbeChannel>
        externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<
                    ProbeChannel>() {
                @Override
                public List<String> channelKeys(
                        ProbeChannel immutableContractSnapshot) {
                    return Collections.singletonList(
                            immutableContractSnapshot
                                    .getSubscriptionKey());
                }

                @Override
                public String checkpointDomainDiscriminator(
                        ProbeChannel immutableContractSnapshot) {
                    return "coordination-harness-probe";
                }
            };
        }

        @Override
        public boolean matches(
                ProbeChannel contract,
                ChannelEvaluationContext context) {
            Object subscriptionKey =
                    context.event()
                            .get("/subscriptionKey");
            return contract
                    .getSubscriptionKey()
                    .equals(subscriptionKey);
        }

        @Override
        public String eventId(
                ProbeChannel contract,
                ChannelEvaluationContext context) {
            Object id = context.event()
                    .get("/id");
            return id != null
                    ? id.toString()
                    : null;
        }
    }

    private static Stream<CoordinationBehaviorFixtureHarness.FixtureCase>
    behaviorCases() {
        Stream<CoordinationBehaviorFixtureHarness.FixtureCase> cases =
                new CoordinationBehaviorFixtureHarness()
                .loadCases()
                .stream();
        String included =
                System.getProperty(
                        "coordination.behavior.includeCaseIds");
        String excluded =
                System.getProperty(
                        "coordination.behavior.excludeCaseIds");
        if (included != null
                && !included.trim().isEmpty()) {
            final Set<String> caseIds =
                    new LinkedHashSet<String>(
                            Arrays.asList(
                                    included.split(",")));
            cases = cases.filter(candidate ->
                    caseIds.contains(
                            candidate.caseId()));
        }
        if (excluded != null
                && !excluded.trim().isEmpty()) {
            final Set<String> caseIds =
                    new LinkedHashSet<String>(
                            Arrays.asList(
                                    excluded.split(",")));
            cases = cases.filter(candidate ->
                    !caseIds.contains(
                            candidate.caseId()));
        }
        return cases;
    }

    private static CoordinationBehaviorFixtureHarness.FixtureCase
    fixtureCase(
            CoordinationBehaviorFixtureHarness harness,
            String caseId) {
        return harness.loadCases()
                .stream()
                .filter(candidate ->
                        caseId.equals(
                                candidate.caseId()))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError(
                                "Missing fixture case "
                                        + caseId));
    }
}
