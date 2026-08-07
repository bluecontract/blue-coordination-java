package blue.coordination.processor;

import blue.language.processor.CoordinationRoutingHarness;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.model.ChannelContract;
import blue.language.identity.DirectBlueIdCalculator;
import blue.repo.BlueRepository;
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
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-e2e-01@inline");

        // when
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssertWithVariantGroup(
                        fixtureCase);

        // then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldProcessPureReferenceTimelineHeadersWithSelectiveEvidence() {
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-e2e-01@references");

        // when
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldRouteReferenceBackedEndToEndCasesToBobWithoutDemandingOpaqueMandateDocument() {
        // given
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

        // when
        for (String caseId : caseIds) {
            executions.add(
                    harness.executeAndAssert(
                            fixtureCase(
                                    harness,
                                    caseId)));
        }

        // then
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
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-e2e-02@references");

        // when
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldAvoidDemandingDecoyBodyForReferenceSplitProcessing() {
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-split-02@references");

        // when
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldAvoidDemandingDecoyBodiesWhenDescendantsEmitNoRootEvent() {
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-split-08@no-root-emission");

        // when
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldAvoidDemandingDecoyBodiesWhenRootEmitsPublicEvents() {
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-split-09@root-emits");

        // when
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // then
        assertEquals(
                fixtureCase.caseId(),
                execution.caseId());
    }

    @Test
    void shouldRecordSelectedDeepHandlerLocation() {
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase fixtureCase =
                fixtureCase(harness, "coord-split-03@default");

        // when
        CoordinationBehaviorFixtureHarness.Execution execution =
                harness.executeAndAssert(fixtureCase);

        // then
        assertEquals(fixtureCase.caseId(), execution.caseId());
    }

    @Test
    void shouldKeepRootOnlyOperationOutOfEmbeddedScopes() {
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase fixtureCase =
                fixtureCase(harness, "coord-split-04@default");

        // when
        CoordinationBehaviorFixtureHarness.Execution execution =
                harness.executeAndAssert(fixtureCase);

        // then
        assertEquals(fixtureCase.caseId(), execution.caseId());
    }

    @Test
    void shouldRecordDirectChildReactiveHandlerLocations() {
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase fixtureCase =
                fixtureCase(harness, "coord-split-05@default");

        // when
        CoordinationBehaviorFixtureHarness.Execution execution =
                harness.executeAndAssert(fixtureCase);

        // then
        assertEquals(fixtureCase.caseId(), execution.caseId());
    }

    @Test
    void shouldSplitInheritedEffectiveContracts() {
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase fixtureCase =
                fixtureCase(harness, "coord-split-06@default");

        // when
        CoordinationBehaviorFixtureHarness.Execution execution =
                harness.executeAndAssert(fixtureCase);

        // then
        assertEquals(fixtureCase.caseId(), execution.caseId());
    }

    @Test
    void shouldComparePureReferenceWithCanonicalScalar() {
        // given
        BigInteger expected = BigInteger.valueOf(7L);
        Node actual = new Node().blueId(
                DirectBlueIdCalculator.INSTANCE
                        .directBlueIdFromCanonicalInput(expected));

        // when
        boolean equivalent =
                CoordinationBehaviorFixtureHarness
                        .equivalentValues(
                                actual, expected);

        // then
        assertTrue(equivalent);
    }

    @Test
    void shouldComparePureReferenceWithCanonicalStructuredValue() {
        // given
        Map<String, Object> expected =
                new LinkedHashMap<String, Object>();
        expected.put(
                "values",
                Arrays.<Object>asList(
                        BigInteger.ONE,
                        "two"));
        Node actual = new Node().blueId(
                DirectBlueIdCalculator.INSTANCE
                        .directBlueIdFromCanonicalInput(expected));

        // when
        boolean equivalent =
                CoordinationBehaviorFixtureHarness
                        .equivalentValues(
                                actual, expected);

        // then
        assertTrue(equivalent);
    }

    @Test
    void shouldRejectUnresolvedNonScalarComparison() {
        // given
        Node unresolved =
                new Node().type(
                        new Node().blueId(
                                "8aohWT7jcoaC1j2siQzBxoKM8HhQ4HF13BkZDNnq5UHf"));

        // when
        boolean equivalent =
                CoordinationBehaviorFixtureHarness
                        .equivalentValues(
                                unresolved, "alice");

        // then
        assertFalse(equivalent);
    }

    @Test
    void shouldStrictlyDecodeAllAuthoredBehaviorExecutionCases() {
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();

        // when
        List<CoordinationBehaviorFixtureHarness.FixtureCase>
                cases = harness.loadCases();

        // then
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
        // given
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

        // when
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssertWithVariantGroup(
                        fixtureCase);

        // then
        assertEquals(
                "coord-chan-07@default",
                execution.caseId());
    }

    @Test
    void shouldExecuteMandateAndTimelineCasesIndependently() {
        // given
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

        // when
        CoordinationBehaviorFixtureHarness.Execution
                mandateExecution =
                harness.executeAndAssertWithVariantGroup(
                        mandateCase);
        CoordinationBehaviorFixtureHarness.Execution
                timelineChannelExecution =
                harness.executeAndAssertWithVariantGroup(
                        timelineChannelCase);

        // then
        assertEquals(
                "coord-mand-07@default",
                mandateExecution.caseId());
        assertEquals(
                "coord-chan-01@default",
                timelineChannelExecution.caseId());
    }

    @Test
    void shouldRollbackDocumentUpdateLoopToExactInitializedRoot() {
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();
        CoordinationBehaviorFixtureHarness.FixtureCase
                fixtureCase =
                fixtureCase(
                        harness,
                        "coord-fail-02@default");

        // when
        CoordinationBehaviorFixtureHarness.Execution
                execution =
                harness.executeAndAssert(
                        fixtureCase);

        // then
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
        // given
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();

        // when
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

        // then
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
        // given
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

        // when
        boolean ownsReceiptWriter =
                methods.stream()
                        .map(Method::getName)
                        .anyMatch(name ->
                                name.toLowerCase(
                                        java.util.Locale.ROOT)
                                        .contains("receipt"));

        // then
        assertFalse(ownsReceiptWriter);
        assertTrue(manifest.contains(
                "status: candidate"));
        assertTrue(behaviorInventory.contains(
                "receiptWritten: false"));
    }

    @Test
    void shouldProjectOnlyForbiddenBodyDemandsInObservedTraceOrder() {
        // given
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

        // when
        List<String> projection =
                CoordinationBehaviorFixtureHarness
                        .forbiddenDemandProjection(
                                semanticDemands,
                                forbiddenBlueIds);

        // then
        assertEquals(
                Arrays.asList(
                        "forbidden-blue-id-2",
                        "forbidden-blue-id-1"),
                projection);
    }

    @Test
    void shouldBuildPartialRepresentationFromOneExactRootFetch() {
        // given
        Node fragment =
                new Node().properties(
                        "state",
                        new Node().value("ready"));
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        fragment);
        List<String> demands =
                new ArrayList<String>();
        NodeProvider provider = blueId -> {
            demands.add(blueId);
            return Collections.singletonList(
                    fragment);
        };

        // when
        Node partial =
                CoordinationBehaviorFixtureHarness
                        .exactPartialRootFragment(
                                rootBlueId,
                                provider);

        // then
        assertEquals(
                Collections.singletonList(
                        rootBlueId),
                demands);
        assertFalse(partial.isReferenceOnly());
        assertEquals(
                rootBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        partial));
    }

    @Test
    void shouldRejectPartialRepresentationWithMismatchedRootIdentity() {
        // given
        Node expected =
                new Node().value("expected");
        Node mismatched =
                new Node().value("mismatched");
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        expected);
        NodeProvider provider =
                ignored ->
                        Collections.singletonList(
                                mismatched);

        // when
        CoordinationBehaviorFixtureHarness
                .FixtureExecutionException failure =
                assertThrows(
                        CoordinationBehaviorFixtureHarness
                                .FixtureExecutionException.class,
                        () -> CoordinationBehaviorFixtureHarness
                                .exactPartialRootFragment(
                                        rootBlueId,
                                        provider));

        // then
        assertTrue(failure.getMessage()
                .contains("changed BlueId"));
    }

    @Test
    void shouldPrefetchOnlyTheDeterministicBoundedWindow() {
        // given
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

        // when
        provider.fetchByBlueId("c");
        provider.fetchByBlueId("d");
        provider.fetchByBlueId("a");

        // then
        assertEquals(
                Arrays.asList(
                        "c", "d", "a", "b"),
                backendFetches);
    }

    @Test
    void shouldSelectStructuralAndAllowedBodyFragmentsIndependently() {
        // given
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

        // when
        Set<String> selected =
                CoordinationBehaviorFixtureHarness
                        .selectedFragmentBlueIds(
                                Arrays.asList(
                                        "root-fragment",
                                        "scope-fragment"),
                                bodyKeysByBlueId,
                                Collections.singleton(
                                        "selected"));

        // then
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
        // given
        Map<String, Set<String>>
                bodyKeysByBlueId =
                Collections.singletonMap(
                        "known-body",
                        Collections.singleton(
                                "known"));

        // when
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

        // then
        assertTrue(failure.getMessage()
                .contains("absent from SplitGraph metadata"));
    }

    @Test
    void shouldPassAuthoredRevisionEvidenceToLanguageThreeArgumentProcess() {
        // given
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

        // when
        ProcessingDebugResult debug =
                CoordinationBehaviorFixtureHarness
                        .processDocumentWithVerifiedEvidence(
                                processor,
                                runtime.initialized.document(),
                                runtime.event,
                                evidence);

        // then
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
        // given
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

        // when
        ProcessingDebugResult debug =
                CoordinationBehaviorFixtureHarness
                        .processDocumentWithVerifiedEvidence(
                                processor,
                                runtime.initialized.document(),
                                runtime.event,
                                stale);

        // then
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
        // given
        ProbeRuntime runtime =
                ProbeRuntime.create();

        // when
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> runtime.evidence(
                                29L,
                                "rejected"));

        // then
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
        // given
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

        // when
        CoordinationBehaviorFixtureHarness
                .FixtureExecutionException failure =
                assertThrows(
                        CoordinationBehaviorFixtureHarness
                                .FixtureExecutionException.class,
                        () -> CoordinationBehaviorFixtureHarness
                                .authoredFeederEvidence(
                                        input,
                                        "revision-mismatch"));

        // then
        assertTrue(failure.getMessage()
                .contains("revision-complete"));
    }

    @Test
    void shouldParseAuthoredRevisionAndSourceEvidence() {
        // given
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

        // when
        CoordinationBehaviorFixtureHarness
                .AuthoredFeederEvidence evidence =
                CoordinationBehaviorFixtureHarness
                        .authoredFeederEvidence(
                                input,
                                "authored-evidence");

        // then
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
        // given
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

        // when
        CoordinationBehaviorFixtureHarness
                .FixtureExecutionException failure =
                assertThrows(
                        CoordinationBehaviorFixtureHarness
                                .FixtureExecutionException.class,
                        () -> CoordinationBehaviorFixtureHarness
                                .authoredFeederEvidence(
                                        input,
                                        "incomplete-evidence"));

        // then
        assertTrue(failure.getMessage()
                .contains(
                        "managedRootRevision, "
                                + "indexedRootRevision, and "
                                + "eligibleSourceChannelKeys"));
    }

    private static final class ProbeRuntime
            implements AutoCloseable {
        private final CoordinationTestRuntime blue;
        private final Node contractSurface;
        private final Node event;
        private final DocumentProcessingResult initialized;

        private ProbeRuntime(
                CoordinationTestRuntime blue,
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
            CoordinationTestRuntime blue =
                    CoordinationTestResources.configuredBlue(
                            BlueRepository.current());
            Node type =
                    new Node().name(
                            ProbeChannel.class
                                    .getSimpleName());
            String typeBlueId =
                    DirectBlueIdCalculator
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
                    blue.processor()
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
                            blue.processor(),
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
