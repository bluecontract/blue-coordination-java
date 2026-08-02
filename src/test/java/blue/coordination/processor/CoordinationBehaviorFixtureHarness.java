package blue.coordination.processor;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.gas.BexGasSchedule;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValues;
import blue.coordination.processor.bex.ProcessingEventIdentityEvidence;
import blue.coordination.processor.mandate.DocumentResponderMandateEligibility;
import blue.coordination.processor.mandate.MandateEligibilityDecision;
import blue.coordination.processor.mandate.MandateValidationEvidence;
import blue.coordination.processor.mandate.OperationMandateEligibility;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.CoordinationConfiguredProcessorFactory;
import blue.language.processor.CoordinationProcessHeaderBridge;
import blue.language.processor.CoordinationRoutingHarness;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ProcessingConformanceTrace;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingTraceConstants;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;
import blue.repo.BlueRepository;
import blue.repo.mandate.OperationMandate;
import blue.repo.myos.MyOSTimelineChannel;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Strict, implementation-independent dispatcher for the authored Coordination
 * behavior fixtures.
 *
 * <p>The harness does not map fixture identifiers to JUnit methods. It parses
 * the declared Blue inputs and calls the corresponding production API. A
 * fixture that needs undeclared provider state, an unimplemented
 * representation transform, or a projection unavailable at a public runtime
 * boundary fails explicitly. Such failures keep the package a candidate and
 * can never be converted into a passed receipt record.</p>
 */
final class CoordinationBehaviorFixtureHarness {
    private static final int BATCH_PREFETCH_LIMIT = 16;
    private static final Path PACKAGE =
            Paths.get(System.getProperty("user.dir"))
                    .toAbsolutePath()
                    .normalize()
                    .resolve("src/test/resources/coordination/conformance");
    private static final BexGasSchedule BEX_GAS_SCHEDULE =
            BexGasSchedule.defaults();
    private static final Set<String> BEHAVIOR_DIRECTORIES =
            immutableSet(
                    "channel",
                    "e2e",
                    "fail",
                    "mandate",
                    "routing",
                    "splitter",
                    "timeline",
                    "workflow");
    private static final Set<String> TOP_LEVEL_FIELDS =
            immutableSet(
                    "fixtureSchema",
                    "id",
                    "vectors",
                    "category",
                    "description",
                    "operation",
                    "input",
                    "expected");
    private static final Set<String> ASSERTION_FIELDS =
            immutableSet(
                    "actual",
                    "op",
                    "expected",
                    "expectedProjection");
    private static final Set<String> EXPECTED_FIELDS =
            immutableSet("assertions");
    private static final Set<String> EXPECTED_PROJECTIONS =
            immutableSet(
                    "input.root",
                    "input.initializedRoot",
                    "splitter.selectedBytes");
    private static final Set<String> VARIANT_FIELDS =
            immutableSet(
                    "name",
                    "rootForm",
                    "eventForm",
                    "cache",
                    "batching",
                    "rootEmits",
                    "mandateDocumentForm");
    private static final Set<String> SPLITTER_FIELDS =
            immutableSet(
                    "mode",
                    "targetScope",
                    "operationKey",
                    "sourceChildPath",
                    "allowedBodyKeys",
                    "forbiddenBodyKeys",
                    "strict");
    private static final Set<String> FEEDER_FIELDS =
            immutableSet(
                    "managedRootRevision",
                    "indexedRootRevision",
                    "eligibleSourceChannelKeys",
                    "initialDocument",
                    "initialMandateDocument",
                    "mandateHistoryCompleteAtEventTime");
    private static final Set<String> PROVIDER_MANDATE_FIELDS =
            immutableSet(
                    "mandateState",
                    "historyCompleteAtRequestTime");
    private static final Set<String> PROJECTIONS =
            immutableSet(
                    "feeder.checkpointOwnerKeys",
                    "feeder.eligibleSourceChannelKeys",
                    "feeder.handlerChannelKey",
                    "feeder.logicalDeliveryCount",
                    "feeder.missingCompleteness",
                    "feeder.orderedEntryIds",
                    "feeder.reason",
                    "feeder.status",
                    "mandate.activatedAt",
                    "mandate.authorityConfirmedAt",
                    "mandate.eligible",
                    "mandate.reason",
                    "mandate.status",
                    "mandate.terminatedAt",
                    "result.diagnostic.category",
                    "result.document",
                    "result.document.seen",
                    "result.document.state",
                    "result.document.sum",
                    "result.events",
                    "result.status",
                    "result.totalGas",
                    "runtime.namedLedgerMergedOnce",
                    "runtime.opaqueGasAccepted",
                    "runtime.recursiveSizeCounterPresent",
                    "splitter.fragmentCount",
                    "splitter.fragmentMetadata",
                    "splitter.opaqueCyclicEdges",
                    "splitter.totalGraphBytes",
                    "trace.bexChildMergeCount",
                    "trace.checkpointWrites",
                    "trace.documentUpdateOrder",
                    "trace.externalDeliveryOrder",
                    "trace.forbiddenDemands",
                    "trace.handlerExecutionLocations",
                    "trace.handlerExecutions",
                    "trace.internalEventOrder",
                    "trace.namedGas",
                    "trace.processingEventBlueIdStable",
                    "trace.semanticDemands",
                    "trace.workflowSteps");

    List<FixtureCase> loadCases() {
        List<FixtureCase> result =
                new ArrayList<FixtureCase>();
        for (Path path : behaviorResources()) {
            Fixture fixture = decode(path);
            if (fixture.variants.isEmpty()) {
                result.add(new FixtureCase(
                        fixture,
                        Variant.defaultVariant()));
            } else {
                for (Variant variant : fixture.variants) {
                    result.add(new FixtureCase(
                            fixture, variant));
                }
            }
        }
        Collections.sort(
                result,
                Comparator.comparing(FixtureCase::caseId));
        return Collections.unmodifiableList(result);
    }

    Audit auditAll() {
        List<FixtureCase> cases = loadCases();
        Map<String, Execution> executions =
                new LinkedHashMap<String, Execution>();
        Map<String, String> failures =
                new LinkedHashMap<String, String>();
        for (FixtureCase fixtureCase : cases) {
            try {
                Execution execution =
                        executeAndAssert(fixtureCase);
                executions.put(
                        fixtureCase.caseId(),
                        execution);
            } catch (RuntimeException failure) {
                failures.put(
                        fixtureCase.caseId(),
                        diagnostic(failure));
            }
        }
        compareVariantAssertions(
                cases, executions, failures);
        return new Audit(
                cases.size(),
                executions,
                failures);
    }

    Execution executeAndAssert(
            FixtureCase fixtureCase) {
        Objects.requireNonNull(
                fixtureCase, "fixtureCase");
        try (Runtime runtime = new Runtime(
                fixtureGasLimit(fixtureCase))) {
            Execution execution;
            switch (fixtureCase.fixture.operation) {
                case PROCESS:
                case CHANNEL_CLASSIFY:
                    execution = executeProcess(
                            runtime, fixtureCase, false);
                    break;
                case GAS_INTEGRATION:
                    execution = executeProcess(
                            runtime, fixtureCase, true);
                    break;
                case SPLIT:
                    execution = executeSplit(
                            runtime, fixtureCase);
                    break;
                case TIMELINE_ORDER:
                    execution = executeTimelineOrder(
                            runtime, fixtureCase);
                    break;
                case MANDATE_ELIGIBILITY:
                    execution = executeMandateEligibility(
                            runtime, fixtureCase);
                    break;
                case PROVIDER_ELIGIBILITY:
                    execution = executeProviderEligibility(
                            runtime, fixtureCase);
                    break;
                default:
                    throw unsupported(
                            fixtureCase,
                            "operation",
                            fixtureCase.fixture.operation
                                    .wireValue);
            }
            try {
                assertFixture(
                        runtime, fixtureCase, execution);
            } catch (FixtureExecutionException failure) {
                throw failure.withExecution(
                        execution);
            }
            return execution;
        }
    }

    Execution executeAndAssertWithVariantGroup(
            FixtureCase fixtureCase) {
        Objects.requireNonNull(
                fixtureCase, "fixtureCase");
        if (!fixtureCase.fixture
                .hasVariantAssertions()) {
            return executeAndAssert(fixtureCase);
        }

        List<FixtureCase> variants =
                fixtureCases(fixtureCase.fixture);
        Map<String, Execution> executions =
                new LinkedHashMap<String, Execution>();
        Map<String, String> failures =
                new LinkedHashMap<String, String>();
        for (FixtureCase variant : variants) {
            try {
                executions.put(
                        variant.caseId(),
                        executeAndAssert(variant));
            } catch (RuntimeException failure) {
                failures.put(
                        variant.caseId(),
                        diagnostic(failure));
            }
        }
        compareVariantAssertions(
                variants, executions, failures);
        if (!failures.isEmpty()) {
            throw new FixtureExecutionException(
                    fixtureCase.fixture.id
                            + ": representation group failed: "
                            + failures);
        }
        Execution execution =
                executions.get(fixtureCase.caseId());
        if (execution == null) {
            throw new FixtureExecutionException(
                    fixtureCase.caseId()
                            + ": representation group produced "
                            + "no execution");
        }
        return execution;
    }

    private static List<FixtureCase> fixtureCases(
            Fixture fixture) {
        if (fixture.variants.isEmpty()) {
            return Collections.singletonList(
                    new FixtureCase(
                            fixture,
                            Variant.defaultVariant()));
        }
        List<FixtureCase> result =
                new ArrayList<FixtureCase>();
        for (Variant variant : fixture.variants) {
            result.add(new FixtureCase(
                    fixture, variant));
        }
        return result;
    }

    private static Long fixtureGasLimit(
            FixtureCase fixtureCase) {
        Node authoredLimit = property(
                fixtureCase.fixture.input,
                "gasLimit");
        if (authoredLimit == null) {
            return null;
        }
        BigInteger exact = integer(authoredLimit);
        if (exact.signum() < 0) {
            throw new FixtureExecutionException(
                    fixtureCase.caseId()
                            + ": input.gasLimit must be non-negative");
        }
        try {
            return Long.valueOf(exact.longValueExact());
        } catch (ArithmeticException outOfRange) {
            throw new FixtureExecutionException(
                    fixtureCase.caseId()
                            + ": input.gasLimit exceeds the runtime range",
                    outOfRange);
        }
    }

    private Execution executeProcess(
            Runtime runtime,
            FixtureCase fixtureCase,
            boolean gasIntegration) {
        Node input = fixtureCase.fixture.input;
        validateProcessInputEvidence(
                runtime, fixtureCase, input);
        if (gasIntegration) {
            BigInteger parentRemaining =
                    integer(
                            requiredProperty(
                                    input,
                                    "parentRemainingGas"));
            BigInteger gasLimit =
                    integer(
                            requiredProperty(
                                    input, "gasLimit"));
            if (!parentRemaining.equals(gasLimit)) {
                throw unsupported(
                        fixtureCase,
                        "input.parentRemainingGas",
                        "the fixture requests a child budget "
                                + "different from its live parent budget");
            }
        }
        Node authoredRoot =
                requiredProperty(input, "root");
        Node root =
                runtime.bindInlineRootType(
                        runtime.materialize(
                                authoredRoot));
        Node event = runtime.materialize(
                requiredProperty(input, "event"));
        DocumentProcessingResult initialized =
                runtime.processor.initializeDocument(
                        root);
        if (!initialized.status().commits()) {
            return processExecution(
                    fixtureCase,
                    runtime,
                    initialized,
                    ProcessingConformanceTrace.empty(),
                    Collections.<String>emptySet());
        }
        Node initializedRoot =
                initialized.document();

        Node splitterControl =
                property(input, "splitter");
        CoordinationDocumentSplitter.SplitGraph
                documentGraph = null;
        CoordinationDocumentSplitter.SplitGraph
                eventGraph = null;
        if (splitterControl != null
                || requiresFragmentGraph(
                fixtureCase.variant)) {
            CoordinationDocumentSplitter splitter =
                    splitterFor(
                            runtime, initializedRoot);
            documentGraph =
                    splitter.splitDocument(
                            initializedRoot);
            eventGraph =
                    splitter.splitEvent(event);
            if (splitterControl != null) {
                validateSplitterEvidence(
                        fixtureCase,
                        splitterControl,
                        documentGraph);
            }
        }
        ProcessInputs processInputs =
                prepareProcessInputs(
                        runtime,
                        fixtureCase,
                        initializedRoot,
                        event,
                        documentGraph,
                        eventGraph,
                        splitterControl);
        return executePreparedProcess(
                runtime,
                fixtureCase,
                processInputs.document,
                processInputs.event,
                processInputs.evidence,
                processInputs.preservedBodyPaths,
                processInputs.forbiddenBodyBlueIds);
    }

    private Execution executePreparedProcess(
            Runtime runtime,
            FixtureCase fixtureCase,
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence,
            Set<String> preservedBodyPaths,
            Set<String> forbiddenBodyBlueIds) {
        runtime.installExecutionEvidencePlan(
                Objects.requireNonNull(
                        evidence, "evidence"));
        ProcessingDebugResult debug;
        if (preservedBodyPaths.isEmpty()) {
            /*
             * Evidence is bound to the exact PROCESS inputs. Re-resolving an
             * already initialized Root here would substitute a different
             * representation before Language can admit and verify that
             * binding. The ordinary Node entry point owns its own exact
             * admission and resolution.
             */
            debug = runtime.processor
                    .processDocumentWithTrace(
                            root,
                            event,
                            evidence);
        } else {
            Node snapshotRoot =
                    root.isReferenceOnly()
                            ? exactPartialRootFragment(
                                    root.getBlueId(),
                                    runtime.blue
                                            .getNodeProvider())
                            : root;
            debug = runtime.processor
                    .processDocumentWithTrace(
                            runtime.blue
                                    .resolveToSnapshotPreservingPaths(
                                            snapshotRoot,
                                            preservedBodyPaths),
                            event,
                            evidence);
        }
        return processExecution(
                fixtureCase,
                runtime,
                debug.processResult(),
                debug.trace(),
                forbiddenBodyBlueIds);
    }

    static ProcessingDebugResult
    processDocumentWithVerifiedEvidence(
            DocumentProcessor processor,
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        return Objects.requireNonNull(
                processor, "processor")
                .processDocumentWithTrace(
                        Objects.requireNonNull(
                                document, "document"),
                        Objects.requireNonNull(
                                event, "event"),
                        Objects.requireNonNull(
                                evidence, "evidence"));
    }

    private Execution executeSplit(
            Runtime runtime,
            FixtureCase fixtureCase) {
        Node input = fixtureCase.fixture.input;
        Node splitterControl =
                requiredProperty(input, "splitter");
        requireFields(
                splitterControl,
                SPLITTER_FIELDS,
                immutableSet(
                        "mode",
                        "allowedBodyKeys",
                        "forbiddenBodyKeys",
                        "strict"),
                fixtureCase.caseId()
                        + ".input.splitter");
        String mode = text(
                requiredProperty(
                        splitterControl, "mode"));
        if (!Arrays.asList(
                "external-operation",
                "embedded-reaction",
                "admission-index").contains(mode)) {
            throw unsupported(
                    fixtureCase,
                    "input.splitter.mode",
                    mode);
        }

        Node authoredRoot =
                requiredProperty(input, "root");
        Node root =
                runtime.bindInlineRootType(
                        runtime.materialize(
                                authoredRoot));
        CoordinationDocumentSplitter splitter =
                splitterFor(
                        runtime, root);
        CoordinationDocumentSplitter.SplitGraph
                documentGraph =
                splitter.splitDocument(root);
        List<CoordinationDocumentSplitter.SplitGraph>
                graphs =
                new ArrayList<CoordinationDocumentSplitter.SplitGraph>();
        graphs.add(documentGraph);
        Node event = property(input, "event");
        CoordinationDocumentSplitter.SplitGraph
                eventGraph = null;
        if (event != null) {
            event = runtime.materialize(event);
            eventGraph = splitter.splitEvent(event);
            graphs.add(eventGraph);
        }
        validateSplitterEvidence(
                fixtureCase,
                splitterControl,
                documentGraph);

        int fragmentCount = 0;
        long totalGraphBytes = 0L;
        Set<String> opaqueEdges =
                new LinkedHashSet<String>();
        List<String> fragmentMetadata =
                new ArrayList<String>();
        for (CoordinationDocumentSplitter.SplitGraph graph
                : graphs) {
            fragmentCount += graph.fragments().size();
            for (Map.Entry<String, Node> fragment
                    : graph.fragments().entrySet()) {
                totalGraphBytes += runtime.blue
                        .nodeToJson(fragment.getValue())
                        .getBytes(StandardCharsets.UTF_8)
                        .length;
            }
            for (CoordinationDocumentSplitter.FragmentMetadata
                    metadata : graph.metadata()) {
                fragmentMetadata.add(
                        metadata.kind().name()
                                + "|"
                                + Objects.toString(
                                metadata.scopePath(), "")
                                + "|"
                                + Objects.toString(
                                metadata.pointer(), ""));
            }
            for (CoordinationDocumentSplitter.EdgeOccurrence
                    edge : graph.edgeOccurrences()) {
                if (edge.originalPureReference()
                        && edge.childBlueId()
                        .contains("#")) {
                    opaqueEdges.add(
                            edge.childBlueId());
                }
            }
        }
        Map<String, Object> projections =
                new LinkedHashMap<String, Object>();
        projections.put(
                "splitter.fragmentCount",
                Integer.valueOf(fragmentCount));
        projections.put(
                "splitter.totalGraphBytes",
                Long.valueOf(totalGraphBytes));
        projections.put(
                "splitter.opaqueCyclicEdges",
                Collections.unmodifiableList(
                        new ArrayList<String>(
                                opaqueEdges)));
        projections.put(
                "splitter.fragmentMetadata",
                Collections.unmodifiableList(
                        fragmentMetadata));
        if (eventGraph != null
                && !"admission-index".equals(mode)) {
            DocumentProcessingResult initialized =
                    runtime.processor.initializeDocument(
                            root);
            if (!initialized.status().commits()) {
                Execution processExecution =
                        processExecution(
                                fixtureCase,
                                runtime,
                                initialized,
                                ProcessingConformanceTrace
                                        .empty(),
                                Collections
                                        .<String>emptySet());
                projections.putAll(
                        processExecution.projections);
                return new Execution(
                        fixtureCase.caseId(),
                        projections);
            }
            CoordinationDocumentSplitter.SplitGraph
                    initializedDocumentGraph =
                    splitterFor(
                            runtime,
                            initialized.document())
                            .splitDocument(
                                    initialized.document());
            ProcessInputs processInputs =
                    prepareProcessInputs(
                            runtime,
                            fixtureCase,
                            initialized.document(),
                            event,
                            initializedDocumentGraph,
                            eventGraph,
                            splitterControl);
            Execution processExecution =
                    executePreparedProcess(
                            runtime,
                            fixtureCase,
                            processInputs.document,
                            processInputs.event,
                            processInputs.evidence,
                            processInputs.preservedBodyPaths,
                            processInputs.forbiddenBodyBlueIds);
            projections.putAll(
                    processExecution.projections);
        }
        return new Execution(
                fixtureCase.caseId(),
                projections);
    }

    private static void validateProcessInputEvidence(
            Runtime runtime,
            FixtureCase fixtureCase,
            Node input) {
        Node feeder = property(input, "feeder");
        if (feeder != null) {
            authoredFeederEvidence(
                    input,
                    fixtureCase.caseId());
            for (String exactNode : Arrays.asList(
                    "initialDocument",
                    "initialMandateDocument")) {
                Node authored =
                        property(feeder, exactNode);
                if (authored != null) {
                    runtime.materialize(authored);
                }
            }
            Node history = property(
                    feeder,
                    "mandateHistoryCompleteAtEventTime");
            if (history != null) {
                booleanScalar(history);
            }
        }

        Node authoredMandate =
                property(input, "mandateState");
        if (authoredMandate == null) {
            return;
        }
        Node mandate =
                runtime.materialize(
                        authoredMandate);
        Node mandateType = mandate.getType();
        if (mandate.isReferenceOnly()
                || mandateType == null
                || !OperationMandate.blueId().equals(
                mandateType.getBlueId())) {
            throw new FixtureExecutionException(
                    fixtureCase.caseId()
                            + ": input.mandateState must be "
                            + "an exact Operation Mandate state");
        }
        Node initialDocument =
                property(feeder, "initialDocument");
        Node mandatedInitialDocument =
                property(
                        property(mandate, "target"),
                        "initialDocument");
        if (initialDocument == null
                || mandatedInitialDocument == null) {
            throw unsupported(
                    fixtureCase,
                    "input.mandateState",
                    "the feeder did not author both target "
                            + "and Mandate initial-document evidence");
        }
        if (!equivalent(
                runtime,
                runtime.materialize(
                        initialDocument),
                mandatedInitialDocument)) {
            throw new FixtureExecutionException(
                    fixtureCase.caseId()
                            + ": Mandate target initial document "
                            + "does not match feeder evidence");
        }
    }

    static AuthoredFeederEvidence authoredFeederEvidence(
            Node input,
            String caseId) {
        Objects.requireNonNull(input, "input");
        String exactCaseId =
                Objects.requireNonNull(
                        caseId, "caseId");
        Node feeder = property(input, "feeder");
        if (feeder == null) {
            return null;
        }
        Node managed = property(
                feeder, "managedRootRevision");
        Node indexed = property(
                feeder, "indexedRootRevision");
        Node eligible = property(
                feeder, "eligibleSourceChannelKeys");
        boolean anyExecutionEvidence =
                managed != null
                        || indexed != null
                        || eligible != null;
        if (!anyExecutionEvidence) {
            return null;
        }
        if (managed == null
                || indexed == null
                || eligible == null) {
            throw new FixtureExecutionException(
                    exactCaseId
                            + ": feeder execution evidence requires "
                            + "managedRootRevision, indexedRootRevision, "
                            + "and eligibleSourceChannelKeys");
        }
        BigInteger managedRevision =
                integer(managed);
        BigInteger indexedRevision =
                integer(indexed);
        if (managedRevision.signum() < 0
                || indexedRevision.signum() < 0) {
            throw new FixtureExecutionException(
                    exactCaseId
                            + ": feeder revisions must be "
                            + "non-negative");
        }
        if (!managedRevision.equals(
                indexedRevision)) {
            throw new FixtureExecutionException(
                    exactCaseId
                            + ": feeder evidence is not "
                            + "revision-complete");
        }
        long exactRevision;
        try {
            exactRevision =
                    managedRevision.longValueExact();
        } catch (ArithmeticException outOfRange) {
            throw new FixtureExecutionException(
                    exactCaseId
                            + ": feeder revision exceeds the "
                            + "Language execution-evidence range",
                    outOfRange);
        }
        return new AuthoredFeederEvidence(
                exactRevision,
                exactRevision,
                stringList(
                        eligible,
                        exactCaseId
                                + ".input.feeder"
                                + ".eligibleSourceChannelKeys"));
    }

    private static boolean requiresFragmentGraph(
            Variant variant) {
        return !"inline".equals(
                variant.rootForm)
                || !"inline".equals(
                variant.eventForm)
                || "warm".equals(
                variant.cache);
    }

    private static ProcessInputs prepareProcessInputs(
            Runtime runtime,
            FixtureCase fixtureCase,
            Node exactRoot,
            Node exactEvent,
            CoordinationDocumentSplitter.SplitGraph
                    documentGraph,
            CoordinationDocumentSplitter.SplitGraph
                    eventGraph,
            Node splitterControl) {
        Variant variant = fixtureCase.variant;
        boolean providerBacked =
                requiresFragmentGraph(variant);
        if (providerBacked
                && (documentGraph == null
                || eventGraph == null)) {
            throw new FixtureExecutionException(
                    fixtureCase.caseId()
                            + ": provider-backed representation "
                            + "was not split exactly");
        }
        NodeProvider documentProvider = null;
        NodeProvider eventProvider = null;
        if (providerBacked) {
            documentProvider =
                    splitterControl != null
                            ? selectedBodyProvider(
                            documentGraph,
                            splitterControl)
                            : documentGraph.provider();
            eventProvider = eventGraph.provider();
            int prefetchLimit =
                    "batched".equals(variant.batching)
                            ? BATCH_PREFETCH_LIMIT
                            : 1;
            if ("batched".equals(variant.batching)
                    || "warm".equals(variant.cache)) {
                documentProvider =
                        new BoundedPrefetchProvider(
                                documentProvider,
                                admittedFragmentBlueIds(
                                        documentGraph,
                                        splitterControl),
                                prefetchLimit);
                eventProvider =
                        new BoundedPrefetchProvider(
                                eventProvider,
                                eventGraph.fragments().keySet(),
                                prefetchLimit);
            }
            if ("warm".equals(variant.cache)) {
                prefetchExactRoot(
                        documentProvider,
                        documentGraph.rootBlueId());
                prefetchExactRoot(
                        eventProvider,
                        eventGraph.rootBlueId());
            }
        }

        Node representedRoot =
                representation(
                        fixtureCase,
                        "rootForm",
                        variant.rootForm,
                        exactRoot,
                        documentGraph,
                        documentProvider);
        Node representedEvent =
                representation(
                        fixtureCase,
                        "eventForm",
                        variant.eventForm,
                        exactEvent,
                        eventGraph,
                        eventProvider);
        AuthoredFeederEvidence authoredEvidence =
                authoredFeederEvidence(
                        fixtureCase.fixture.input,
                        fixtureCase.caseId());
        if (authoredEvidence == null) {
            throw new FixtureExecutionException(
                    fixtureCase.caseId()
                            + ": PROCESS requires authored feeder "
                            + "revisions and exact eligible source "
                            + "occurrences");
        }
        CoordinationRoutingHarness.DeliveryOccurrence[]
                occurrences =
                authoredEvidence.deliveryOccurrences(
                        fixtureCase.caseId());
        VerifiedExecutionEvidence evidence =
                CoordinationRoutingHarness.evidence(
                        runtime.processor,
                        exactRoot,
                        representedRoot,
                        exactEvent,
                        representedEvent,
                        authoredEvidence.managedRootRevision(),
                        authoredEvidence.indexedRootRevision(),
                        occurrences);

        if (providerBacked) {
            /*
             * Feeder evidence is derived from the exact initialized Root
             * before the strict PROCESS provider is installed. The evidence
             * remains bound to the represented Root/Event BlueIds, and
             * Language independently verifies every retained header through
             * that strict provider during PROCESS.
             */
            runtime.installFragmentProvider(
                    new SequentialNodeProvider(
                            documentProvider,
                            eventProvider));
        }

        return new ProcessInputs(
                representedRoot,
                representedEvent,
                evidence,
                executableBodyPaths(
                        documentGraph),
                splitterControl != null
                        ? bodyBlueIdsForKeys(
                        documentGraph,
                        stringList(
                                requiredProperty(
                                        splitterControl,
                                        "forbiddenBodyKeys"),
                                fixtureCase.caseId()
                                        + ".input.splitter"
                                        + ".forbiddenBodyKeys"))
                        : Collections.<String>emptySet());
    }

    private static Set<String> executableBodyPaths(
            CoordinationDocumentSplitter.SplitGraph graph) {
        if (graph == null) {
            return Collections.emptySet();
        }
        Set<String> paths =
                new LinkedHashSet<String>();
        for (CoordinationDocumentSplitter.FragmentMetadata
                metadata : graph.metadata()) {
            if (metadata.kind()
                    == CoordinationDocumentSplitter
                    .FragmentKind.EXECUTABLE_BODY
                    && metadata.pointer() != null) {
                paths.add(metadata.pointer());
            }
        }
        return Collections.unmodifiableSet(
                paths);
    }

    private static Node representation(
            FixtureCase fixtureCase,
            String field,
            String form,
            Node exact,
            CoordinationDocumentSplitter.SplitGraph graph,
            NodeProvider provider) {
        if ("inline".equals(form)) {
            return exact.clone();
        }
        if ("reference".equals(form)) {
            return graph.pureReference();
        }
        if ("fragmented".equals(form)) {
            return graph.processingRootView();
        }
        if ("partial".equals(form)) {
            return exactPartialRootFragment(
                    graph.rootBlueId(),
                    Objects.requireNonNull(
                            provider,
                            "partial provider"));
        }
        throw unsupported(
                fixtureCase,
                "input.variants." + field,
                form);
    }

    private static void prefetchExactRoot(
            NodeProvider provider,
            String rootBlueId) {
        exactPartialRootFragment(
                rootBlueId, provider);
    }

    static Node exactPartialRootFragment(
            String rootBlueId,
            NodeProvider provider) {
        NodeProviderResult result =
                Objects.requireNonNull(
                        provider, "provider")
                        .fetchResultByBlueId(
                                Objects.requireNonNull(
                                        rootBlueId,
                                        "rootBlueId"));
        if (result.outcome()
                != NodeProviderOutcome.FOUND) {
            throw new FixtureExecutionException(
                    "Partial representation requires exact "
                            + "root-fragment evidence for "
                            + rootBlueId
                            + " but provider outcome was "
                            + result.outcome());
        }
        List<Node> candidates = result.nodes();
        if (candidates.size() != 1) {
            throw new FixtureExecutionException(
                    "Partial representation requires one exact "
                            + "root fragment for "
                            + rootBlueId
                            + " but provider returned "
                            + candidates.size());
        }
        Node fragment = candidates.get(0);
        String actualBlueId =
                BlueIdCalculator.calculateBlueId(
                        fragment);
        if (!rootBlueId.equals(actualBlueId)) {
            throw new FixtureExecutionException(
                    "Partial representation root fragment "
                            + "changed BlueId from "
                            + rootBlueId
                            + " to " + actualBlueId);
        }
        return fragment;
    }

    private static void validateSplitterEvidence(
            FixtureCase fixtureCase,
            Node splitterControl,
            CoordinationDocumentSplitter.SplitGraph graph) {
        String location =
                fixtureCase.caseId()
                        + ".input.splitter";
        String mode = scalarText(
                requiredProperty(
                        splitterControl, "mode"));
        List<String> allowed =
                stringList(
                        requiredProperty(
                                splitterControl,
                                "allowedBodyKeys"),
                        location + ".allowedBodyKeys");
        List<String> forbidden =
                stringList(
                        requiredProperty(
                                splitterControl,
                                "forbiddenBodyKeys"),
                        location + ".forbiddenBodyKeys");
        Set<String> overlap =
                new LinkedHashSet<String>(allowed);
        overlap.retainAll(forbidden);
        if (!overlap.isEmpty()) {
            throw new FixtureExecutionException(
                    location
                            + " declares body keys as both "
                            + "allowed and forbidden "
                            + overlap);
        }

        Set<String> known =
                new LinkedHashSet<String>();
        Set<String> scopes =
                new LinkedHashSet<String>();
        Set<String> scopedBodyKeys =
                new LinkedHashSet<String>();
        Set<String> allowedBlueIds =
                new LinkedHashSet<String>();
        Set<String> forbiddenBlueIds =
                new LinkedHashSet<String>();
        Set<String> structuralBlueIds =
                new LinkedHashSet<String>();
        for (CoordinationDocumentSplitter.FragmentMetadata
                metadata : graph.metadata()) {
            if (metadata.scopePath() != null) {
                scopes.add(metadata.scopePath());
            }
            if (metadata.kind()
                    != CoordinationDocumentSplitter
                    .FragmentKind.EXECUTABLE_BODY) {
                structuralBlueIds.add(
                        metadata.blueId());
                continue;
            }
            String key =
                    bodyContractKey(metadata);
            known.add(key);
            scopedBodyKeys.add(
                    metadata.scopePath()
                            + "\u0000" + key);
            if (allowed.contains(key)) {
                allowedBlueIds.add(
                        metadata.blueId());
            }
            if (forbidden.contains(key)) {
                forbiddenBlueIds.add(
                        metadata.blueId());
            }
        }
        Set<String> declared =
                new LinkedHashSet<String>(allowed);
        declared.addAll(forbidden);
        if (!known.containsAll(declared)) {
            Set<String> missing =
                    new LinkedHashSet<String>(
                            declared);
            missing.removeAll(known);
            throw new FixtureExecutionException(
                    location
                            + " names bodies absent from the "
                            + "effective fragmentation catalog "
                            + missing);
        }
        Set<String> ambiguousForbiddenBlueIds =
                new LinkedHashSet<String>(
                        forbiddenBlueIds);
        Set<String> admissibleBlueIds =
                new LinkedHashSet<String>(
                        allowedBlueIds);
        admissibleBlueIds.addAll(
                structuralBlueIds);
        ambiguousForbiddenBlueIds.retainAll(
                admissibleBlueIds);
        if (!ambiguousForbiddenBlueIds.isEmpty()) {
            throw new FixtureExecutionException(
                    location
                            + " cannot attribute forbidden demands because "
                            + "content identities alias admitted bodies or "
                            + "structural fragments "
                            + ambiguousForbiddenBlueIds);
        }

        if ("external-operation".equals(mode)) {
            String targetScope =
                    scalarText(
                            requiredProperty(
                                    splitterControl,
                                    "targetScope"));
            String operationKey =
                    scalarText(
                            requiredProperty(
                                    splitterControl,
                                    "operationKey"));
            if (!allowed.contains(
                    operationKey)
                    || !scopedBodyKeys.contains(
                    targetScope
                            + "\u0000"
                            + operationKey)) {
                throw new FixtureExecutionException(
                        location
                                + " does not admit the exact "
                                + "target operation body");
            }
        } else if ("embedded-reaction".equals(mode)) {
            String targetScope =
                    scalarText(
                            requiredProperty(
                                    splitterControl,
                                    "targetScope"));
            String sourceChildPath =
                    scalarText(
                            requiredProperty(
                                    splitterControl,
                                    "sourceChildPath"));
            if (!scopes.contains(targetScope)
                    || !scopes.contains(
                    sourceChildPath)) {
                throw new FixtureExecutionException(
                        location
                                + " names a scope absent from "
                                + "the effective fragmentation catalog");
            }
        } else if ("admission-index".equals(mode)
                && !allowed.isEmpty()) {
            throw new FixtureExecutionException(
                    location
                            + ".allowedBodyKeys must be empty "
                            + "for admission-index");
        }
    }

    private static Set<String> bodyBlueIdsForKeys(
            CoordinationDocumentSplitter.SplitGraph graph,
            Collection<String> bodyKeys) {
        Set<String> result =
                new LinkedHashSet<String>();
        for (CoordinationDocumentSplitter.FragmentMetadata
                metadata : graph.metadata()) {
            if (metadata.kind()
                    == CoordinationDocumentSplitter
                    .FragmentKind.EXECUTABLE_BODY
                    && bodyKeys.contains(
                    bodyContractKey(metadata))) {
                result.add(metadata.blueId());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> admittedFragmentBlueIds(
            CoordinationDocumentSplitter.SplitGraph graph,
            Node splitterControl) {
        if (splitterControl == null) {
            return Collections.unmodifiableSet(
                    new LinkedHashSet<String>(
                            graph.fragments().keySet()));
        }
        List<String> allowedBodyKeys =
                stringList(
                        requiredProperty(
                                splitterControl,
                                "allowedBodyKeys"),
                        "input.splitter.allowedBodyKeys");
        Set<String> structuralBlueIds =
                new LinkedHashSet<String>();
        Map<String, Set<String>> bodyKeysByBlueId =
                new LinkedHashMap<String, Set<String>>();
        for (CoordinationDocumentSplitter.FragmentMetadata
                metadata : graph.metadata()) {
            if (metadata.kind()
                    == CoordinationDocumentSplitter
                    .FragmentKind.EXECUTABLE_BODY) {
                bodyKeysByBlueId
                        .computeIfAbsent(
                                metadata.blueId(),
                                ignored ->
                                        new LinkedHashSet<String>())
                        .add(bodyContractKey(metadata));
            } else {
                structuralBlueIds.add(
                        metadata.blueId());
            }
        }
        return selectedFragmentBlueIds(
                structuralBlueIds,
                bodyKeysByBlueId,
                allowedBodyKeys);
    }

    static Set<String> selectedFragmentBlueIds(
            Collection<String> structuralBlueIds,
            Map<String, ? extends Collection<String>>
                    bodyKeysByBlueId,
            Collection<String> allowedBodyKeys) {
        Set<String> allowed =
                new LinkedHashSet<String>(
                        Objects.requireNonNull(
                                allowedBodyKeys,
                                "allowedBodyKeys"));
        Set<String> known =
                new LinkedHashSet<String>();
        for (Collection<String> keys
                : Objects.requireNonNull(
                bodyKeysByBlueId,
                "bodyKeysByBlueId").values()) {
            known.addAll(keys);
        }
        if (!known.containsAll(allowed)) {
            Set<String> missing =
                    new LinkedHashSet<String>(allowed);
            missing.removeAll(known);
            throw new FixtureExecutionException(
                    "Selected-byte projection names body keys "
                            + "absent from SplitGraph metadata "
                            + missing);
        }

        Set<String> selected =
                new LinkedHashSet<String>(
                        Objects.requireNonNull(
                                structuralBlueIds,
                                "structuralBlueIds"));
        for (Map.Entry<String,
                ? extends Collection<String>> entry
                : bodyKeysByBlueId.entrySet()) {
            for (String key : entry.getValue()) {
                if (allowed.contains(key)) {
                    selected.add(entry.getKey());
                    break;
                }
            }
        }
        List<String> ordered =
                new ArrayList<String>(selected);
        Collections.sort(ordered);
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(ordered));
    }

    private static long selectedFragmentBytes(
            Runtime runtime,
            CoordinationDocumentSplitter.SplitGraph
                    documentGraph,
            CoordinationDocumentSplitter.SplitGraph
                    eventGraph,
            Node splitterControl) {
        long selected =
                encodedFragmentBytes(
                        runtime,
                        documentGraph,
                        admittedFragmentBlueIds(
                                documentGraph,
                                splitterControl));
        if (eventGraph != null) {
            selected = Math.addExact(
                    selected,
                    encodedFragmentBytes(
                            runtime,
                            eventGraph,
                            eventGraph.fragments()
                                    .keySet()));
        }
        return selected;
    }

    private static long encodedFragmentBytes(
            Runtime runtime,
            CoordinationDocumentSplitter.SplitGraph graph,
            Collection<String> selectedBlueIds) {
        long bytes = 0L;
        Map<String, Node> fragments =
                graph.fragments();
        for (String blueId : selectedBlueIds) {
            Node fragment = fragments.get(blueId);
            if (fragment == null) {
                throw new FixtureExecutionException(
                        "Selected-byte projection metadata "
                                + "names absent fragment "
                                + blueId);
            }
            bytes = Math.addExact(
                    bytes,
                    runtime.blue.nodeToJson(fragment)
                            .getBytes(
                                    StandardCharsets.UTF_8)
                            .length);
        }
        return bytes;
    }

    private static NodeProvider selectedBodyProvider(
            CoordinationDocumentSplitter.SplitGraph graph,
            Node splitterControl) {
        Set<String> allowed =
                new LinkedHashSet<String>(
                        stringList(
                                requiredProperty(
                                        splitterControl,
                                        "allowedBodyKeys"),
                                "input.splitter"
                                        + ".allowedBodyKeys"));
        Set<String> allowedBlueIds =
                new LinkedHashSet<String>();
        Set<String> bodyBlueIds =
                new LinkedHashSet<String>();
        Set<String> structuralBlueIds =
                new LinkedHashSet<String>();
        for (CoordinationDocumentSplitter.FragmentMetadata
                metadata : graph.metadata()) {
            if (metadata.kind()
                    == CoordinationDocumentSplitter
                    .FragmentKind.EXECUTABLE_BODY) {
                bodyBlueIds.add(metadata.blueId());
                if (allowed.contains(
                        bodyContractKey(metadata))) {
                    allowedBlueIds.add(
                            metadata.blueId());
                }
            } else {
                structuralBlueIds.add(
                        metadata.blueId());
            }
        }
        Set<String> blocked =
                new LinkedHashSet<String>(
                        bodyBlueIds);
        blocked.removeAll(allowedBlueIds);
        blocked.removeAll(structuralBlueIds);
        return new SelectedBodyProvider(
                graph.provider(), blocked);
    }

    private static CoordinationDocumentSplitter splitterFor(
            Runtime runtime,
            Node exactRoot) {
        NodeProvider configured =
                runtime.blue.getNodeProvider();
        Node suppliedInlineType =
                Objects.requireNonNull(
                        exactRoot, "exactRoot")
                        .getType();
        Node inlineType =
                suppliedInlineType != null
                        && !suppliedInlineType.isReferenceOnly()
                        ? CoordinationProcessHeaderBridge
                        .canonicalExactCopy(
                                suppliedInlineType)
                        : suppliedInlineType;
        Node inheritedContracts =
                inlineType != null
                        && !inlineType.isReferenceOnly()
                        ? inlineType.getContracts()
                        : null;
        if (inheritedContracts == null
                || inheritedContracts.getProperties() == null) {
            return new CoordinationDocumentSplitter(
                    runtime.processor,
                    configured);
        }

        Map<String, Node> exactSources =
                new LinkedHashMap<String, Node>();
        exactSources.put(
                BlueIdCalculator.calculateBlueId(
                        inlineType),
                inlineType.clone());
        for (Node contribution :
                inheritedContracts.getProperties().values()) {
            if (contribution == null
                    || contribution.isReferenceOnly()) {
                continue;
            }
            exactSources.put(
                    BlueIdCalculator.calculateBlueId(
                            contribution),
                    contribution.clone());
        }
        if (exactSources.isEmpty()) {
            return new CoordinationDocumentSplitter(
                    runtime.processor,
                    configured);
        }

        NodeProvider authoredSources = blueId -> {
            Node source = exactSources.get(blueId);
            return source != null
                    ? Collections.singletonList(
                    source.clone())
                    : null;
        };
        return new CoordinationDocumentSplitter(
                runtime.processor,
                new SequentialNodeProvider(
                        authoredSources,
                        configured));
    }

    private static String bodyContractKey(
            CoordinationDocumentSplitter.FragmentMetadata
                    metadata) {
        String pointer = metadata.pointer();
        if (pointer == null) {
            throw new FixtureExecutionException(
                    "Executable-body metadata has no pointer");
        }
        List<String> segments =
                JsonPointer.split(pointer);
        for (int index = 0;
             index + 1 < segments.size();
             index++) {
            if ("contracts".equals(
                    segments.get(index))) {
                return segments.get(index + 1);
            }
        }
        throw new FixtureExecutionException(
                "Executable-body metadata does not identify "
                        + "a contract: " + pointer);
    }

    private Execution executeTimelineOrder(
            Runtime runtime,
            FixtureCase fixtureCase) {
        requireDefaultVariant(fixtureCase);
        Node input = fixtureCase.fixture.input;
        List<Node> entries =
                materializeItems(
                        runtime,
                        requiredProperty(
                                input, "entries"));
        List<Node> activeTimelines =
                new ArrayList<Node>();
        Map<String, String> timelineIdByBlueId =
                new LinkedHashMap<String, String>();
        for (Node entry : entries) {
            Node timeline =
                    requiredProperty(
                            entry, "timeline");
            String timelineBlueId =
                    BlueIdCalculator.calculateBlueId(
                            timeline);
            if (!timelineIdByBlueId
                    .containsKey(timelineBlueId)) {
                activeTimelines.add(
                        timeline.clone());
                timelineIdByBlueId.put(
                        timelineBlueId,
                        scalarText(
                                requiredProperty(
                                        timeline,
                                        "timelineId")));
            }
        }

        Map<String, BigInteger> completeBefore =
                new LinkedHashMap<String, BigInteger>();
        Set<String> finalTimelineBlueIds =
                new LinkedHashSet<String>();
        for (Node item : items(
                requiredProperty(
                        input, "completeness"))) {
            requireFields(
                    item,
                    immutableSet(
                            "timelineId",
                            "completeBefore",
                            "final"),
                    immutableSet(
                            "timelineId",
                            "completeBefore"),
                    fixtureCase.caseId()
                            + ".input.completeness[]");
            String timelineId = scalarText(
                    requiredProperty(
                            item, "timelineId"));
            String timelineBlueId =
                    findTimelineBlueId(
                            timelineIdByBlueId,
                            timelineId);
            completeBefore.put(
                    timelineBlueId,
                    integer(
                            requiredProperty(
                                    item,
                                    "completeBefore")));
            Node finalNode = property(item, "final");
            if (finalNode != null) {
                Object finalValue = finalNode.getValue();
                if (!(finalValue instanceof Boolean)) {
                    throw new FixtureExecutionException(
                            fixtureCase.caseId()
                                    + ".input.completeness[].final "
                                    + "must be boolean");
                }
                if (((Boolean) finalValue).booleanValue()) {
                    finalTimelineBlueIds.add(
                            timelineBlueId);
                }
            }
        }

        for (Node entry : entries) {
            Node timeline =
                    requiredProperty(
                            entry, "timeline");
            String timelineBlueId =
                    BlueIdCalculator.calculateBlueId(
                            timeline);
            if (finalTimelineBlueIds.contains(
                    timelineBlueId)
                    && TimelineProviderSupport
                    .isBehindCommittedFrontier(
                            entry,
                            timeline,
                            completeBefore.get(
                                    timelineBlueId))) {
                Map<String, Object> projections =
                        new LinkedHashMap<String, Object>();
                projections.put(
                        "feeder.status",
                        "ineligible");
                projections.put(
                        "feeder.reason",
                        "provider-backdated-entry-behind-frontier");
                projections.put(
                        "feeder.orderedEntryIds",
                        Collections.emptyList());
                projections.put(
                        "feeder.missingCompleteness",
                        Collections.emptyList());
                return new Execution(
                        fixtureCase.caseId(),
                        projections);
            }
        }

        TimelineProviderSupport.CompletenessWindow
                window =
                TimelineProviderSupport
                        .evaluateCompletenessWindow(
                                entries,
                                activeTimelines,
                                completeBefore);
        Map<String, Object> projections =
                new LinkedHashMap<String, Object>();
        projections.put(
                "feeder.status",
                window.ready()
                        ? "ready"
                        : "suspended");
        projections.put(
                "feeder.reason",
                null);
        List<String> orderedEntryIds =
                new ArrayList<String>();
        for (Node entry : window.orderedEntries()) {
            orderedEntryIds.add(
                    scalarText(
                            requiredProperty(
                                    entry,
                                    "fixtureId")));
        }
        projections.put(
                "feeder.orderedEntryIds",
                orderedEntryIds);
        List<String> missing =
                new ArrayList<String>();
        for (String blueId
                : window.incompleteTimelineBlueIds()) {
            missing.add(
                    timelineIdByBlueId.get(blueId));
        }
        projections.put(
                "feeder.missingCompleteness",
                missing);
        return new Execution(
                fixtureCase.caseId(),
                projections);
    }

    private Execution executeMandateEligibility(
            Runtime runtime,
            FixtureCase fixtureCase) {
        requireMandateVariant(fixtureCase);
        Node input = fixtureCase.fixture.input;
        Node mandateState = runtime.materialize(
                requiredProperty(
                        input, "mandateState"));
        Node exactRoot = runtime.materialize(
                requiredProperty(input, "root"));
        Node root = exactRoot;
        Node validation =
                nodeAt(
                        mandateState,
                        "/validation/function");
        Node feeder =
                requiredProperty(input, "feeder");
        Node targetInitialDocument =
                runtime.materialize(
                        requiredProperty(
                                feeder,
                                "initialDocument"));
        Node initialMandateDocument =
                runtime.materialize(
                        requiredProperty(
                                feeder,
                                "initialMandateDocument"));
        boolean historyCompleteAtEventTime =
                booleanScalar(
                        requiredProperty(
                                feeder,
                                "mandateHistoryCompleteAtEventTime"));
        Node event = runtime.materialize(
                requiredProperty(input, "event"));
        if ("reference".equals(
                fixtureCase.variant
                        .mandateDocumentForm)) {
            Node authority =
                    requiredProperty(
                            event,
                            "onBehalfOf");
            authority.getProperties().put(
                    "initialMandateDocument",
                    new Node().blueId(
                            BlueIdCalculator.calculateBlueId(
                                    initialMandateDocument)));
        }
        Node request =
                property(
                        property(event, "message"),
                        "request");
        MandateValidationEvidence validationEvidence =
                validation != null
                        ? executeMandateValidation(
                                runtime,
                                root,
                                request,
                                validation)
                        : null;
        MandateEligibilityDecision decision =
                OperationMandateEligibility.evaluate(
                        OperationMandateEligibility
                                .Evidence.builder()
                                .mandateState(mandateState)
                                .initialMandateDocument(
                                        initialMandateDocument)
                                .event(event)
                                .targetInitialDocument(
                                        targetInitialDocument)
                                .currentDocument(root)
                                .historyCompleteAtEventTime(
                                        historyCompleteAtEventTime)
                                .validationEvidence(
                                        validationEvidence)
                                .build());
        return mandateExecution(
                runtime,
                fixtureCase,
                decision,
                mandateState);
    }

    private MandateValidationEvidence
    executeMandateValidation(
            Runtime runtime,
            Node root,
            Node request,
            Node function) {
        if (request == null
                || request.isReferenceOnly()
                || function.isReferenceOnly()) {
            return MandateValidationEvidence.unavailable(
                    "mandate-validation-evidence-unavailable");
        }
        BexEngine engine =
                BexEngine.builder()
                        .blue(runtime.blue)
                        .build();
        BexExecutionContext context =
                BexExecutionContext.builder()
                        .document(
                                new FrozenBexDocumentView(
                                        FrozenNode
                                                .fromResolvedNode(
                                                        root)))
                        .binding(
                                "request",
                                BexValues.nodeSnapshot(
                                        request))
                        .build();
        BexExecutionResult result =
                engine.compileAndExecute(
                        BexProgramSource.inline(
                                FrozenNode.fromResolvedNode(
                                        function)),
                        context);
        return result.value().asBoolean()
                ? MandateValidationEvidence.passed(
                        function, request)
                : MandateValidationEvidence.rejected(
                        function,
                        request,
                        "mandate-validation-function-rejected");
    }

    private Execution executeProviderEligibility(
            Runtime runtime,
            FixtureCase fixtureCase) {
        requireDefaultVariant(fixtureCase);
        Node input = fixtureCase.fixture.input;
        Node feeder = requiredProperty(input, "feeder");
        Node providerActor =
                runtime.materialize(
                        requiredProperty(
                                input, "providerActor"));
        BigInteger requestTimestamp =
                integer(
                        requiredProperty(
                                input, "requestTimestamp"));
        Node requestingInitialDocument =
                runtime.materialize(
                        requiredProperty(
                                feeder, "initialDocument"));
        Node request =
                runtime.materialize(
                        requiredProperty(
                                input, "request"));
        List<DocumentResponderMandateEligibility.Candidate>
                candidates =
                new ArrayList<DocumentResponderMandateEligibility
                        .Candidate>();
        for (Node authoredCandidate : items(
                requiredProperty(
                        input, "providerMandates"))) {
            requireFields(
                    authoredCandidate,
                    PROVIDER_MANDATE_FIELDS,
                    PROVIDER_MANDATE_FIELDS,
                    fixtureCase.caseId()
                            + ".input.providerMandates[]");
            Node mandate =
                    runtime.materialize(
                            requiredProperty(
                                    authoredCandidate,
                                    "mandateState"));
            boolean historyComplete =
                    booleanScalar(
                            requiredProperty(
                                    authoredCandidate,
                                    "historyCompleteAtRequestTime"));
            candidates.add(
                    historyComplete
                            ? DocumentResponderMandateEligibility
                            .Candidate.complete(
                                    mandate, null)
                            : DocumentResponderMandateEligibility
                            .Candidate.incomplete(
                                    mandate));
        }
        MandateEligibilityDecision decision =
                DocumentResponderMandateEligibility.evaluate(
                        DocumentResponderMandateEligibility
                                .Evidence.builder()
                                .requestTimestamp(
                                        requestTimestamp)
                                .providerActor(
                                        providerActor)
                                .requestingInitialDocument(
                                        requestingInitialDocument)
                                .request(request)
                                .candidates(candidates)
                                .build());
        return mandateExecution(
                runtime,
                fixtureCase,
                decision,
                null);
    }

    private Execution processExecution(
            FixtureCase fixtureCase,
            Runtime runtime,
            DocumentProcessingResult result,
            ProcessingConformanceTrace trace,
            Set<String> forbiddenBodyBlueIds) {
        Map<String, Object> projections =
                new LinkedHashMap<String, Object>();
        projections.put(
                "result.status",
                result.status().wireValue());
        projections.put(
                "result.document",
                result.document());
        projections.put(
                "result.events",
                result.events());
        projections.put(
                "result.totalGas",
                Long.valueOf(result.totalGas()));
        projections.put(
                "result.diagnostic.category",
                result.diagnostic() != null
                        ? result.diagnostic()
                        .category().name()
                        : null);
        projections.put(
                "result.diagnostic.message",
                result.diagnostic() != null
                        ? result.diagnostic().message()
                        : null);
        projections.put(
                "result.diagnostic.details",
                result.diagnostic() != null
                        ? result.diagnostic().details()
                        : null);
        putDocumentProjection(
                projections,
                result.document(),
                "state");
        putDocumentProjection(
                projections,
                result.document(),
                "seen");
        putDocumentProjection(
                projections,
                result.document(),
                "sum");
        putMandateProjection(
                runtime,
                projections,
                result.document());
        putTraceProjections(
                projections,
                trace,
                forbiddenBodyBlueIds);
        ProcessingEventIdentityEvidence.Snapshot
                processingEventIdentity =
                runtime.processingEventIdentityEvidence
                        .snapshot();
        projections.put(
                "trace.processingEventBlueIdStable",
                processingEventIdentity.observed()
                        ? Boolean.valueOf(
                        processingEventIdentity.stable())
                        : null);
        Execution execution = new Execution(
                fixtureCase.caseId(),
                projections);
        try {
            validateProcessOutputEvidence(
                    fixtureCase,
                    execution,
                    result);
        } catch (FixtureExecutionException failure) {
            throw failure.withExecution(
                    execution);
        }
        return execution;
    }

    private static void validateProcessOutputEvidence(
            FixtureCase fixtureCase,
            Execution execution,
            DocumentProcessingResult result) {
        Node feeder = property(
                fixtureCase.fixture.input,
                "feeder");
        Node authoredEligible =
                property(
                        feeder,
                        "eligibleSourceChannelKeys");
        if (authoredEligible != null) {
            List<String> expected =
                    stringList(
                            authoredEligible,
                            fixtureCase.caseId()
                                    + ".input.feeder"
                                    + ".eligibleSourceChannelKeys");
            Object actual =
                    execution.projections.get(
                            "feeder.eligibleSourceChannelKeys");
            if (!equivalent(
                    null, actual, expected)) {
                throw new FixtureExecutionException(
                        fixtureCase.caseId()
                                + ": feeder selected source keys "
                                + expected
                                + " but the public delivery trace "
                                + "reported " + actual
                                + "; PROCESS status="
                                + result.status().wireValue()
                                + ", diagnostic="
                                + diagnostic(result));
            }
        }

        Boolean rootEmits =
                fixtureCase.variant.rootEmits;
        if (rootEmits != null) {
            Object events =
                    execution.projections.get(
                            "result.events");
            if (!(events instanceof Collection)) {
                throw new FixtureExecutionException(
                        fixtureCase.caseId()
                                + ": result.events is not a "
                                + "collection");
            }
            boolean emitted =
                    !((Collection<?>) events)
                            .isEmpty();
            if (emitted
                    != rootEmits.booleanValue()) {
                throw new FixtureExecutionException(
                        fixtureCase.caseId()
                                + ": rootEmits="
                                + rootEmits
                                + " but PROCESS emitted "
                                + ((Collection<?>) events)
                                .size()
                                + " Root event(s); handlers="
                                + execution.projections.get(
                                "trace.handlerExecutionLocations")
                                + ", internalEvents="
                                + execution.projections.get(
                                "trace.internalEventOrder")
                                + ", status="
                                + result.status().wireValue()
                                + ", diagnostic="
                                + diagnostic(result));
            }
        }
    }

    private static String diagnostic(
            DocumentProcessingResult result) {
        if (result.diagnostic() == null) {
            return "none";
        }
        return result.diagnostic().category().name()
                + ":"
                + String.valueOf(
                        result.diagnostic().message())
                + " "
                + result.diagnostic().details();
    }

    private static void putDocumentProjection(
            Map<String, Object> projections,
            Node document,
            String property) {
        Node value = property(
                document, property);
        projections.put(
                "result.document." + property,
                value != null
                        ? scalarOrNode(value)
                        : null);
    }

    private static void putMandateProjection(
            Runtime runtime,
            Map<String, Object> projections,
            Node document) {
        Node status = property(
                document, "status");
        projections.put(
                "mandate.status",
                status != null
                        ? runtime.qualifiedType(status)
                        : null);
        for (String field : Arrays.asList(
                "authorityConfirmedAt",
                "activatedAt",
                "terminatedAt")) {
            Node value = property(
                    document, field);
            projections.put(
                    "mandate." + field,
                    value != null
                            ? scalarOrNode(value)
                            : null);
        }
    }

    private static void putTraceProjections(
            Map<String, Object> projections,
            ProcessingConformanceTrace trace,
            Set<String> forbiddenBodyBlueIds) {
        List<String> gas =
                new ArrayList<String>();
        for (GasTraceEntry entry : trace.gas()) {
            gas.add(entry.namespace()
                    + ":" + entry.counter()
                    + ":" + entry.quantity()
                    + ":" + entry.weight()
                    + ":" + entry.subtotal());
        }
        projections.put(
                "trace.namedGas", gas);
        projections.put(
                "trace.semanticDemands",
                trace.semanticDemands());
        projections.put(
                "trace.forbiddenDemands",
                forbiddenDemandProjection(
                        trace.semanticDemands(),
                        forbiddenBodyBlueIds));
        projections.put(
                "trace.externalDeliveryOrder",
                recordLocations(
                        trace,
                        ProcessingTraceRecord.Kind
                                .EXTERNAL_DELIVERY));
        projections.put(
                "trace.checkpointWrites",
                recordLocations(
                        trace,
                        ProcessingTraceRecord.Kind
                                .CHECKPOINT_WRITE));
        projections.put(
                "trace.handlerExecutions",
                recordKeys(
                        trace,
                        ProcessingTraceRecord.Kind
                                .HANDLER_EXECUTION));
        projections.put(
                "trace.handlerExecutionLocations",
                recordLocations(
                        trace,
                        ProcessingTraceRecord.Kind
                                .HANDLER_EXECUTION));
        projections.put(
                "trace.documentUpdateOrder",
                recordLocations(
                        trace,
                        ProcessingTraceRecord.Kind
                                .DOCUMENT_UPDATE));
        List<String> internalEvents =
                new ArrayList<String>();
        for (ProcessingTraceRecord record
                : trace.records()) {
            if (record.kind()
                    == ProcessingTraceRecord.Kind
                    .EVENT_ENQUEUED
                    || record.kind()
                    == ProcessingTraceRecord.Kind
                    .EVENT_DEQUEUED) {
                internalEvents.add(
                        recordLocation(record));
            }
        }
        projections.put(
                "trace.internalEventOrder",
                internalEvents);
        projections.put(
                "trace.workflowSteps",
                workflowSteps(trace));

        Set<String> bexNamespaces =
                new LinkedHashSet<String>();
        boolean opaqueGasAccepted = false;
        for (GasTraceEntry entry : trace.gas()) {
            if (entry.namespace().startsWith(
                    "bex.workflow.")) {
                bexNamespaces.add(
                        entry.namespace());
            }
            if (!isCataloguedGas(entry)) {
                opaqueGasAccepted = true;
            }
        }
        projections.put(
                "trace.bexChildMergeCount",
                Integer.valueOf(
                        bexNamespaces.size()));
        projections.put(
                "runtime.namedLedgerMergedOnce",
                Boolean.valueOf(
                        bexNamespaces.size() == 1));
        projections.put(
                "runtime.opaqueGasAccepted",
                Boolean.valueOf(
                        opaqueGasAccepted));
        projections.put(
                "runtime.recursiveSizeCounterPresent",
                Boolean.valueOf(
                        recursiveSizeCounterPresent()));

        List<String> eligible =
                recordLocations(
                        trace,
                        ProcessingTraceRecord.Kind
                                .EXTERNAL_DELIVERY);
        projections.put(
                "feeder.eligibleSourceChannelKeys",
                sourceKeys(eligible));
        List<ProcessingTraceRecord> groups =
                trace.records(
                        ProcessingTraceRecord.Kind
                                .LOGICAL_DELIVERY_GROUP);
        projections.put(
                "feeder.logicalDeliveryCount",
                Integer.valueOf(groups.size()));
        projections.put(
                "feeder.handlerChannelKey",
                groups.isEmpty()
                        ? null
                        : groups.get(0).detail(
                        ProcessingTraceConstants
                                .FIELD_HANDLER_CHANNEL_KEY));
        List<String> checkpointOwners =
                new ArrayList<String>();
        for (ProcessingTraceRecord record
                : trace.records(
                ProcessingTraceRecord.Kind
                        .CHECKPOINT_WRITE)) {
            checkpointOwners.add(
                    record.contractKey());
        }
        projections.put(
                "feeder.checkpointOwnerKeys",
                checkpointOwners);
    }

    static List<String> forbiddenDemandProjection(
            List<String> semanticDemands,
            Set<String> forbiddenBodyBlueIds) {
        Objects.requireNonNull(
                semanticDemands,
                "semanticDemands");
        Objects.requireNonNull(
                forbiddenBodyBlueIds,
                "forbiddenBodyBlueIds");
        List<String> result =
                new ArrayList<String>();
        for (String demand : semanticDemands) {
            if (forbiddenBodyBlueIds.contains(
                    demand)) {
                result.add(demand);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> workflowSteps(
            ProcessingConformanceTrace trace) {
        Map<String, Integer> nextIndex =
                new LinkedHashMap<String, Integer>();
        List<String> result =
                new ArrayList<String>();
        List<GasTraceEntry> gas = trace.gas();
        for (int index = 0;
             index < gas.size();
             index++) {
            GasTraceEntry executed =
                    gas.get(index);
            if (!"workflowStepExecuted".equals(
                    executed.counter())) {
                continue;
            }
            String stepKind =
                    index + 1 < gas.size()
                            ? workflowStepKind(
                            gas.get(index + 1))
                            : null;
            /*
             * A gas limit may admit workflowStepExecuted and reject the
             * immediately following kind counter. In that case no exact step
             * kind entered the admitted trace, so there is no step projection
             * to invent.
             */
            if (stepKind == null) {
                continue;
            }
            String contractKey =
                    executed.contractKey();
            if (contractKey == null) {
                throw new FixtureExecutionException(
                        "Workflow gas entry has no contract key");
            }
            String occurrence =
                    String.valueOf(
                            executed.scopePath())
                            + "\u0000"
                            + contractKey;
            Integer current =
                    nextIndex.get(occurrence);
            int stepIndex = current != null
                    ? current.intValue()
                    : 0;
            nextIndex.put(
                    occurrence,
                    Integer.valueOf(
                            stepIndex + 1));
            result.add(
                    contractKey
                            + ":" + stepIndex
                            + ":" + stepKind);
        }
        return Collections.unmodifiableList(
                result);
    }

    private static String workflowStepKind(
            GasTraceEntry entry) {
        if ("updateDocumentStep".equals(
                entry.counter())) {
            return "Update Document";
        }
        if ("triggerEventStep".equals(
                entry.counter())) {
            return "Trigger Event";
        }
        if ("terminateProcessingStep".equals(
                entry.counter())) {
            return "Terminate Processing";
        }
        if ("computeStepEntered".equals(
                entry.counter())) {
            return "Compute";
        }
        return null;
    }

    private static boolean isCataloguedGas(
            GasTraceEntry entry) {
        Map<String, Map<String, Long>> contracts =
                GasSchedule.contracts10()
                        .namespaces();
        Map<String, Long> contractCounters =
                contracts.get(entry.namespace());
        if (contractCounters != null) {
            return contractCounters.containsKey(
                    entry.counter());
        }
        if (entry.namespace().matches(
                "coordination\\.[0-9]{8}")) {
            return CoordinationRuntimeGas
                    .counterWeights()
                    .containsKey(
                            entry.counter());
        }
        if (entry.namespace().matches(
                "bex\\.workflow\\.[0-9]{8}"
                        + "\\.compute\\.[0-9]{8}"
                        + "(?:/[^/]+)*")) {
            return BEX_GAS_SCHEDULE
                    .counterWeights()
                    .containsKey(
                            entry.counter());
        }
        return false;
    }

    private static boolean
    recursiveSizeCounterPresent() {
        Set<String> names =
                new LinkedHashSet<String>();
        for (Map.Entry<String, Map<String, Long>>
                namespace
                : GasSchedule.contracts10()
                .namespaces().entrySet()) {
            for (String counter
                    : namespace.getValue().keySet()) {
                names.add(
                        namespace.getKey()
                                + "." + counter);
            }
        }
        names.addAll(
                CoordinationRuntimeGas
                        .counterWeights()
                        .keySet());
        names.addAll(
                BEX_GAS_SCHEDULE
                        .counterWeights()
                        .keySet());
        for (String name : names) {
            String normalized =
                    name.toLowerCase(
                            Locale.ROOT);
            if (normalized.contains("recursive")
                    || normalized.contains(
                    "serializedsize")
                    || normalized.contains(
                    "referencestate")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> recordLocations(
            ProcessingConformanceTrace trace,
            ProcessingTraceRecord.Kind kind) {
        List<String> result =
                new ArrayList<String>();
        for (ProcessingTraceRecord record
                : trace.records(kind)) {
            result.add(recordLocation(record));
        }
        return Collections.unmodifiableList(
                result);
    }

    private static List<String> recordKeys(
            ProcessingConformanceTrace trace,
            ProcessingTraceRecord.Kind kind) {
        List<String> result =
                new ArrayList<String>();
        for (ProcessingTraceRecord record
                : trace.records(kind)) {
            result.add(record.contractKey());
        }
        return Collections.unmodifiableList(
                result);
    }

    private static String recordLocation(
            ProcessingTraceRecord record) {
        return record.scopePath()
                + ":" + record.contractKey();
    }

    private static List<String> sourceKeys(
            List<String> locations) {
        boolean severalScopes = false;
        for (String location : locations) {
            if (!location.startsWith("/:")) {
                severalScopes = true;
                break;
            }
        }
        if (severalScopes) {
            return locations;
        }
        List<String> keys =
                new ArrayList<String>();
        for (String location : locations) {
            keys.add(location.substring(2));
        }
        return keys;
    }

    private static Execution mandateExecution(
            Runtime runtime,
            FixtureCase fixtureCase,
            MandateEligibilityDecision decision,
            Node mandateState) {
        Map<String, Object> projections =
                new LinkedHashMap<String, Object>();
        projections.put(
                "mandate.eligible",
                Boolean.valueOf(
                        decision.isEligible()));
        projections.put(
                "mandate.reason",
                decision.reason());
        Node status = property(
                mandateState, "status");
        projections.put(
                "mandate.status",
                status != null
                        ? runtime.qualifiedType(status)
                        : null);
        projections.put(
                "feeder.status",
                decision.outcome()
                        .name()
                        .toLowerCase(Locale.ROOT));
        projections.put(
                "feeder.reason",
                decision.reason());
        return new Execution(
                fixtureCase.caseId(),
                projections);
    }

    private void assertFixture(
            Runtime runtime,
            FixtureCase fixtureCase,
            Execution execution) {
        for (Assertion assertion
                : fixtureCase.fixture.assertions) {
            if (assertion.operator
                    == Operator.SAME_ACROSS_VARIANTS) {
                continue;
            }
            Object actual =
                    execution.projections.get(
                            assertion.projection);
            if (!execution.projections
                    .containsKey(
                            assertion.projection)) {
                throw unsupported(
                        fixtureCase,
                        "expected.assertions.actual",
                        assertion.projection
                                + " has no truthful projection "
                                + "at this production API boundary");
            }
            Object expected =
                    assertion.expectedProjection != null
                            ? projectedFixtureValue(
                            runtime,
                            fixtureCase,
                            assertion
                                    .expectedProjection)
                            : nodeValue(
                            assertion.expected);
            if (!assertion.operator.test(
                    runtime,
                    actual,
                    expected)) {
                throw new FixtureExecutionException(
                        fixtureCase.caseId()
                                + ": "
                                + assertion.projection
                                + " "
                                + assertion.operator
                                .wireValue
                                + " expected "
                                + printable(expected)
                                + " but was "
                                + printable(actual)
                                + statusDiagnosticContext(
                                        assertion.projection,
                                        execution.projections));
            }
        }
    }

    private static String statusDiagnosticContext(
            String projection,
            Map<String, Object> projections) {
        if ("result.status".equals(projection)) {
            return "; diagnostic.category="
                    + printable(
                            projections.get(
                                    "result.diagnostic.category"))
                    + ", diagnostic.message="
                    + printable(
                            projections.get(
                                    "result.diagnostic.message"))
                    + ", diagnostic.details="
                    + printable(
                            projections.get(
                                    "result.diagnostic.details"));
        }
        if (projection.startsWith("mandate.")) {
            return "; feeder.status="
                    + printable(
                    projections.get("feeder.status"))
                    + ", feeder.reason="
                    + printable(
                    projections.get("feeder.reason"));
        }
        if (projection.startsWith("trace.")) {
            return "; externalDeliveries="
                    + printable(
                            projections.get(
                                    "trace.externalDeliveryOrder"))
                    + ", handlers="
                    + printable(
                            projections.get(
                                    "trace.handlerExecutionLocations"))
                    + ", workflowSteps="
                    + printable(
                            projections.get(
                                    "trace.workflowSteps"))
                    + ", semanticDemands="
                    + printable(
                            projections.get(
                                    "trace.semanticDemands"));
        }
        return "";
    }

    private static Object projectedFixtureValue(
            Runtime runtime,
            FixtureCase fixtureCase,
            String projection) {
        if ("input.root".equals(projection)) {
            return runtime.materialize(
                    requiredProperty(
                            fixtureCase.fixture.input,
                            "root"));
        }
        if ("input.initializedRoot".equals(
                projection)) {
            Node authoredRoot =
                    requiredProperty(
                            fixtureCase.fixture.input,
                            "root");
            DocumentProcessingResult initialized =
                    runtime.initializeAuthored(
                            authoredRoot);
            if (!initialized.status().commits()) {
                throw new FixtureExecutionException(
                        fixtureCase.caseId()
                                + ": input.initializedRoot requires "
                                + "successful deterministic initialization");
            }
            return initialized.document();
        }
        if ("splitter.selectedBytes".equals(
                projection)) {
            Node input = fixtureCase.fixture.input;
            Node splitterControl =
                    requiredProperty(
                            input, "splitter");
            Node exactRoot =
                    runtime.materialize(
                            requiredProperty(
                                    input,
                                    "root"));
            CoordinationDocumentSplitter splitter =
                    splitterFor(
                            runtime,
                            exactRoot);
            CoordinationDocumentSplitter.SplitGraph
                    documentGraph =
                    splitter.splitDocument(
                            exactRoot);
            Node authoredEvent =
                    property(input, "event");
            CoordinationDocumentSplitter.SplitGraph
                    eventGraph =
                    authoredEvent != null
                            ? splitter.splitEvent(
                            runtime.materialize(
                                    authoredEvent))
                            : null;
            return Long.valueOf(
                    selectedFragmentBytes(
                            runtime,
                            documentGraph,
                            eventGraph,
                            splitterControl));
        }
        throw unsupported(
                fixtureCase,
                "expectedProjection",
                projection);
    }

    private static void compareVariantAssertions(
            List<FixtureCase> cases,
            Map<String, Execution> executions,
            Map<String, String> failures) {
        Map<String, List<FixtureCase>> byFixture =
                new TreeMap<String, List<FixtureCase>>();
        for (FixtureCase fixtureCase : cases) {
            byFixture.computeIfAbsent(
                    fixtureCase.fixture.id,
                    ignored ->
                            new ArrayList<FixtureCase>())
                    .add(fixtureCase);
        }
        for (List<FixtureCase> variants
                : byFixture.values()) {
            if (variants.size() < 2) {
                continue;
            }
            for (Assertion assertion
                    : variants.get(0)
                    .fixture.assertions) {
                if (assertion.operator
                        != Operator.SAME_ACROSS_VARIANTS) {
                    continue;
                }
                Object baseline = null;
                boolean baselineSet = false;
                String baselineCaseId = null;
                String groupFailure = null;
                for (FixtureCase variant : variants) {
                    Execution execution =
                            executions.get(
                                    variant.caseId());
                    if (execution == null) {
                        groupFailure =
                                assertion.projection
                                        + " cannot be compared because "
                                        + variant.caseId()
                                        + " did not execute successfully";
                        break;
                    }
                    if (!execution.projections
                            .containsKey(
                                    assertion.projection)) {
                        groupFailure =
                                assertion.projection
                                        + " has no truthful runtime "
                                        + "projection for "
                                        + variant.caseId();
                        break;
                    }
                    Object value =
                            execution.projections.get(
                                    assertion.projection);
                    if (!baselineSet) {
                        baseline = value;
                        baselineSet = true;
                        baselineCaseId =
                                variant.caseId();
                    } else if (!equivalent(
                            null, baseline, value)) {
                        groupFailure =
                                assertion.projection
                                        + " differs across representations: "
                                        + baselineCaseId
                                        + "="
                                        + comparisonDiagnostic(
                                                assertion.projection,
                                                executions.get(
                                                        baselineCaseId),
                                                baseline)
                                        + ", "
                                        + variant.caseId()
                                        + "="
                                        + comparisonDiagnostic(
                                                assertion.projection,
                                                execution,
                                                value);
                        break;
                    }
                }
                if (groupFailure != null) {
                    for (FixtureCase variant : variants) {
                        if (!failures.containsKey(
                                variant.caseId())) {
                            failures.put(
                                    variant.caseId(),
                                    groupFailure);
                        }
                    }
                }
            }
        }
        for (String failedCase
                : failures.keySet()) {
            executions.remove(failedCase);
        }
    }

    private static String comparisonDiagnostic(
            String projection,
            Execution execution,
            Object value) {
        String diagnostic =
                "result.status".equals(projection)
                        && execution != null
                        && execution.projections.get(
                                "result.diagnostic.message")
                        != null
                        ? ", diagnostic="
                        + execution.projections.get(
                                "result.diagnostic.category")
                        + ":"
                        + execution.projections.get(
                                "result.diagnostic.message")
                        + " "
                        + execution.projections.get(
                                "result.diagnostic.details")
                        + ", externalDeliveries="
                        + execution.projections.get(
                                "trace.externalDeliveryOrder")
                        + ", handlers="
                        + execution.projections.get(
                                "trace.handlerExecutionLocations")
                        + ", workflowSteps="
                        + execution.projections.get(
                                "trace.workflowSteps")
                        : "";
        if (value instanceof Node) {
            Node node = (Node) value;
            return node.isReferenceOnly()
                    ? "reference(" + node.getBlueId() + ")"
                    : "node("
                    + BlueIdCalculator.calculateBlueId(
                            node)
                    + ")" + diagnostic;
        }
        if (value instanceof Collection) {
            return "collection(size="
                    + ((Collection<?>) value).size()
                    + ")" + diagnostic;
        }
        if (value instanceof Map) {
            return "map(size="
                    + ((Map<?, ?>) value).size()
                    + ")" + diagnostic;
        }
        return Objects.toString(value)
                + diagnostic;
    }

    private Fixture decode(Path path) {
        String source = read(path);
        String schemaLine =
                "schema: blue-coordination-fixture/1.0";
        if (!source.startsWith(
                schemaLine + "\n")) {
            throw new FixtureExecutionException(
                    path + ": unknown or misplaced schema");
        }
        Node fixture;
        try (Blue parser = new Blue()) {
            fixture = parser.parseSourceYaml(
                    "fixtureSchema:"
                            + source.substring(
                            "schema:".length()));
        }
        requireFields(
                fixture,
                TOP_LEVEL_FIELDS,
                TOP_LEVEL_FIELDS,
                path.toString());
        if (fixture.getDescription() == null
                || fixture.getDescription()
                .trim().isEmpty()) {
            throw new FixtureExecutionException(
                    path
                            + ": description must be non-empty");
        }
        String schema = scalarText(
                requiredProperty(
                        fixture,
                        "fixtureSchema"));
        if (!"blue-coordination-fixture/1.0"
                .equals(schema)) {
            throw new FixtureExecutionException(
                    path + ": unknown schema "
                            + schema);
        }
        String id = scalarText(
                requiredProperty(
                        fixture, "id"));
        String category = scalarText(
                requiredProperty(
                        fixture, "category"));
        if (!BEHAVIOR_DIRECTORIES
                .contains(category)) {
            throw new FixtureExecutionException(
                    path + ": unknown category "
                            + category);
        }
        validateFixtureIdentity(
                path,
                fixture,
                id,
                category);
        Operation operation =
                Operation.fromWireValue(
                        scalarText(
                                requiredProperty(
                                        fixture,
                                        "operation")));
        Node input =
                requiredProperty(
                        fixture, "input");
        requireFields(
                input,
                operation.allowedInputFields,
                operation.requiredInputFields,
                id + ".input");
        Node feeder = property(
                input, "feeder");
        if (feeder != null) {
            requireFields(
                    feeder,
                    FEEDER_FIELDS,
                    Collections.<String>emptySet(),
                    id + ".input.feeder");
        }
        Node splitter = property(
                input, "splitter");
        if (splitter != null) {
            validateSplitterControl(
                    id, splitter);
        }
        List<Variant> variants =
                decodeVariants(
                        id, property(
                                input, "variants"));
        Node expected =
                requiredProperty(
                        fixture, "expected");
        requireFields(
                expected,
                EXPECTED_FIELDS,
                EXPECTED_FIELDS,
                id + ".expected");
        List<Assertion> assertions =
                decodeAssertions(
                        id,
                        requiredProperty(
                                expected,
                                "assertions"));
        boolean comparesVariants = false;
        for (Assertion assertion : assertions) {
            if (assertion.operator
                    == Operator.SAME_ACROSS_VARIANTS) {
                comparesVariants = true;
                break;
            }
        }
        if (comparesVariants
                && variants.size() < 2) {
            throw new FixtureExecutionException(
                    id
                            + ": sameAcrossVariants requires "
                            + "at least two variants");
        }
        return new Fixture(
                PACKAGE.relativize(path)
                        .toString()
                        .replace(
                                java.io.File.separatorChar,
                                '/'),
                id,
                operation,
                input,
                variants,
                assertions);
    }

    private static void validateFixtureIdentity(
            Path path,
            Node fixture,
            String id,
            String category) {
        Map<String, String> categoryCodes =
                new LinkedHashMap<String, String>();
        categoryCodes.put("channel", "chan");
        categoryCodes.put("e2e", "e2e");
        categoryCodes.put("fail", "fail");
        categoryCodes.put("mandate", "mand");
        categoryCodes.put("routing", "route");
        categoryCodes.put("splitter", "split");
        categoryCodes.put("timeline", "time");
        categoryCodes.put("workflow", "wf");
        String code = categoryCodes.get(category);
        if (code == null
                || !id.matches(
                "coord-" + code + "-[0-9]{2}")) {
            throw new FixtureExecutionException(
                    path
                            + ": id/category disagreement "
                            + id + "/" + category);
        }

        String relative =
                PACKAGE.relativize(path)
                        .toString()
                        .replace(
                                java.io.File.separatorChar,
                                '/');
        String expectedPath =
                "fixtures/" + category
                        + "/" + id + ".yaml";
        if (!expectedPath.equals(relative)) {
            throw new FixtureExecutionException(
                    path
                            + ": id/category/path disagreement; "
                            + "expected " + expectedPath);
        }

        String suffix =
                id.substring(
                        id.length() - 2);
        String vectorPrefix =
                "COORD-"
                        + code.toUpperCase(Locale.ROOT)
                        + "-";
        Set<String> vectors =
                new LinkedHashSet<String>();
        for (Node vectorNode : items(
                requiredProperty(
                        fixture, "vectors"))) {
            String vector = scalarText(vectorNode);
            if (!vector.matches(
                    "COORD-[A-Z0-9]+-[0-9]{2}")
                    || !vector.equals(
                    vectorPrefix + suffix)) {
                throw new FixtureExecutionException(
                        path
                                + ": vector/id/category "
                                + "disagreement " + vector);
            }
            if (!vectors.add(vector)) {
                throw new FixtureExecutionException(
                        path
                                + ": duplicate vector "
                                + vector);
            }
        }
        if (vectors.isEmpty()) {
            throw new FixtureExecutionException(
                    path + ": vectors must not be empty");
        }
    }

    private static void validateSplitterControl(
            String fixtureId,
            Node splitter) {
        String location =
                fixtureId + ".input.splitter";
        requireFields(
                splitter,
                SPLITTER_FIELDS,
                immutableSet(
                        "mode",
                        "allowedBodyKeys",
                        "forbiddenBodyKeys",
                        "strict"),
                location);
        String mode = enumText(
                splitter,
                "mode",
                immutableSet(
                        "external-operation",
                        "embedded-reaction",
                        "admission-index"));
        stringList(
                requiredProperty(
                        splitter, "allowedBodyKeys"),
                location + ".allowedBodyKeys");
        stringList(
                requiredProperty(
                        splitter, "forbiddenBodyKeys"),
                location + ".forbiddenBodyKeys");
        if (!booleanScalar(
                requiredProperty(
                        splitter, "strict"))) {
            throw new FixtureExecutionException(
                    location
                            + ".strict must be true");
        }
        validateAbsolutePath(
                property(splitter, "targetScope"),
                location + ".targetScope");
        validateAbsolutePath(
                property(splitter, "sourceChildPath"),
                location + ".sourceChildPath");
        Node operationKey =
                property(splitter, "operationKey");
        if (operationKey != null
                && scalarText(operationKey)
                .trim().isEmpty()) {
            throw new FixtureExecutionException(
                    location
                            + ".operationKey must not be empty");
        }
        if ("external-operation".equals(mode)) {
            requiredProperty(
                    splitter, "targetScope");
            requiredProperty(
                    splitter, "operationKey");
        } else if ("embedded-reaction".equals(mode)) {
            requiredProperty(
                    splitter, "targetScope");
            requiredProperty(
                    splitter, "sourceChildPath");
        }
    }

    private static void validateAbsolutePath(
            Node authored,
            String location) {
        if (authored != null
                && !scalarText(authored)
                .startsWith("/")) {
            throw new FixtureExecutionException(
                    location
                            + " must be an absolute path");
        }
    }

    private static List<String> stringList(
            Node node,
            String location) {
        List<String> result =
                new ArrayList<String>();
        Set<String> distinct =
                new LinkedHashSet<String>();
        for (Node item : items(node)) {
            String value = scalarText(item);
            if (value.trim().isEmpty()) {
                throw new FixtureExecutionException(
                        location
                                + " contains an empty value");
            }
            if (!distinct.add(value)) {
                throw new FixtureExecutionException(
                        location
                                + " contains duplicate value "
                                + value);
            }
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Variant> decodeVariants(
            String fixtureId,
            Node variantsNode) {
        if (variantsNode == null) {
            return Collections.emptyList();
        }
        List<Variant> result =
                new ArrayList<Variant>();
        Set<String> names =
                new LinkedHashSet<String>();
        for (Node item : items(variantsNode)) {
            requireFields(
                    item,
                    VARIANT_FIELDS,
                    immutableSet(
                            "rootForm",
                            "eventForm",
                            "cache",
                            "batching"),
                    fixtureId + ".input.variants[]");
            Variant variant =
                    new Variant(
                            requiredName(
                                    item,
                                    fixtureId
                                            + ".input.variants[]"),
                            enumText(
                                    item,
                                    "rootForm",
                                    immutableSet(
                                            "inline",
                                            "reference",
                                            "partial",
                                            "fragmented")),
                            enumText(
                                    item,
                                    "eventForm",
                                    immutableSet(
                                            "inline",
                                            "reference",
                                            "partial",
                                            "fragmented")),
                            enumText(
                                    item,
                                    "cache",
                                    immutableSet(
                                            "cold",
                                            "warm")),
                            enumText(
                                    item,
                                    "batching",
                                    immutableSet(
                                            "unbatched",
                                            "batched")),
                            optionalEnumText(
                                    item,
                                    "mandateDocumentForm",
                                    immutableSet(
                                            "inline",
                                            "reference"),
                                    "inline"),
                            optionalBoolean(
                                    item,
                                    "rootEmits"));
            if (!names.add(variant.name)) {
                throw new FixtureExecutionException(
                        fixtureId
                                + ": duplicate variant "
                                + variant.name);
            }
            result.add(variant);
        }
        if (result.isEmpty()) {
            throw new FixtureExecutionException(
                    fixtureId
                            + ": input.variants must not be empty");
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Assertion> decodeAssertions(
            String fixtureId,
            Node assertionsNode) {
        List<Assertion> result =
                new ArrayList<Assertion>();
        for (Node item : items(assertionsNode)) {
            requireFields(
                    item,
                    ASSERTION_FIELDS,
                    immutableSet(
                            "actual", "op"),
                    fixtureId
                            + ".expected.assertions[]");
            String projection =
                    scalarText(
                            requiredProperty(
                                    item, "actual"));
            if (!PROJECTIONS.contains(
                    projection)) {
                throw new FixtureExecutionException(
                        fixtureId
                                + ": unknown projection "
                                + projection);
            }
            Operator operator =
                    Operator.fromWireValue(
                            scalarText(
                                    requiredProperty(
                                            item, "op")));
            Node expected =
                    property(item, "expected");
            Node expectedProjectionNode =
                    property(
                            item,
                            "expectedProjection");
            String expectedProjection =
                    expectedProjectionNode != null
                            ? scalarText(
                            expectedProjectionNode)
                            : null;
            if (expected != null
                    && expectedProjection != null) {
                throw new FixtureExecutionException(
                        fixtureId + ": "
                                + operator.wireValue
                                + " accepts exactly one of expected "
                                + "and expectedProjection");
            }
            if (expectedProjection != null
                    && !EXPECTED_PROJECTIONS.contains(
                    expectedProjection)) {
                throw new FixtureExecutionException(
                        fixtureId
                                + ": unknown expectedProjection "
                                + expectedProjection);
            }
            if (operator
                    == Operator.EQUALS_PROJECTION
                    && expectedProjection == null) {
                throw new FixtureExecutionException(
                        fixtureId
                                + ": equalsProjection requires "
                                + "expectedProjection");
            }
            if (operator
                    != Operator.EQUALS_PROJECTION
                    && expectedProjection != null
                    && operator
                    != Operator.GREATER_THAN) {
                throw new FixtureExecutionException(
                        fixtureId + ": "
                                + operator.wireValue
                                + " does not accept "
                                + "expectedProjection");
            }
            if (operator.requiresExpected
                    && expected == null
                    && expectedProjection == null) {
                throw new FixtureExecutionException(
                        fixtureId + ": "
                                + operator.wireValue
                                + " requires expected "
                                + "or expectedProjection");
            }
            if (!operator.requiresExpected
                    && (expected != null
                    || expectedProjection != null)) {
                throw new FixtureExecutionException(
                        fixtureId + ": "
                                + operator.wireValue
                                + " does not accept expected");
            }
            result.add(new Assertion(
                    projection,
                    operator,
                    expected,
                    expectedProjection));
        }
        if (result.isEmpty()) {
            throw new FixtureExecutionException(
                    fixtureId
                            + ": expected.assertions must "
                            + "not be empty");
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Path> behaviorResources() {
        Path fixtures = PACKAGE.resolve(
                "fixtures");
        List<Path> result =
                new ArrayList<Path>();
        try (Stream<Path> stream =
                     Files.walk(fixtures, 2)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path
                            .getFileName()
                            .toString()
                            .endsWith(".yaml"))
                    .filter(path ->
                            BEHAVIOR_DIRECTORIES
                                    .contains(
                                            fixtures
                                                    .relativize(path)
                                                    .getName(0)
                                                    .toString()))
                    .forEach(result::add);
        } catch (IOException failure) {
            throw new FixtureExecutionException(
                    "Cannot inventory behavior fixtures",
                    failure);
        }
        Collections.sort(result);
        return result;
    }

    private static String read(Path path) {
        try {
            return new String(
                    Files.readAllBytes(path),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new FixtureExecutionException(
                    "Cannot read " + path,
                    failure);
        }
    }

    private static void requireMandateVariant(
            FixtureCase fixtureCase) {
        if (!"inline".equals(
                fixtureCase.variant.rootForm)
                || !"inline".equals(
                fixtureCase.variant.eventForm)
                || !"cold".equals(
                fixtureCase.variant.cache)
                || !"unbatched".equals(
                fixtureCase.variant.batching)
                || fixtureCase.variant.rootEmits
                != null
                || !Arrays.asList(
                "inline",
                "reference").contains(
                fixtureCase.variant
                        .mandateDocumentForm)) {
            throw unsupported(
                    fixtureCase,
                    "input.variants",
                    "unsupported Mandate representation "
                            + fixtureCase.variant.name);
        }
    }

    private static void requireDefaultVariant(
            FixtureCase fixtureCase) {
        if (!fixtureCase.variant
                .isDefault()) {
            throw unsupported(
                    fixtureCase,
                    "input.variants",
                    fixtureCase.variant.name);
        }
    }

    private static List<Node> materializeItems(
            Runtime runtime,
            Node node) {
        List<Node> result =
                new ArrayList<Node>();
        for (Node item : items(node)) {
            result.add(
                    runtime.materialize(item));
        }
        return result;
    }

    private static Node nodeAt(
            Node node,
            String pointer) {
        try {
            return node.getAsNode(pointer);
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    private static Node property(
            Node node,
            String name) {
        return node != null
                && node.getProperties() != null
                ? node.getProperties().get(name)
                : null;
    }

    private static Node requiredProperty(
            Node node,
            String name) {
        Node value = property(node, name);
        if (value == null) {
            throw new FixtureExecutionException(
                    "Missing property " + name);
        }
        return value;
    }

    private static List<Node> items(Node node) {
        if (node == null
                || node.getItems() == null) {
            throw new FixtureExecutionException(
                    "Expected a list node");
        }
        return node.getItems();
    }

    private static String text(Node node) {
        return scalarText(node);
    }

    private static String scalarText(Node node) {
        Object value = node != null
                ? node.getValue()
                : null;
        if (!(value instanceof String)) {
            throw new FixtureExecutionException(
                    "Expected text but was "
                            + printable(value));
        }
        return (String) value;
    }

    private static BigInteger integer(Node node) {
        Object value = node != null
                ? node.getValue()
                : null;
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return BigInteger.valueOf(
                    ((Number) value).longValue());
        }
        if (value instanceof BigDecimal) {
            try {
                return ((BigDecimal) value)
                        .toBigIntegerExact();
            } catch (ArithmeticException fractional) {
                throw new FixtureExecutionException(
                        "Expected integer but was "
                                + printable(value));
            }
        }
        if (value instanceof Number) {
            try {
                return new BigDecimal(
                        value.toString())
                        .toBigIntegerExact();
            } catch (NumberFormatException
                     | ArithmeticException invalid) {
                throw new FixtureExecutionException(
                        "Expected integer but was "
                                + printable(value));
            }
        }
        throw new FixtureExecutionException(
                "Expected integer but was "
                        + printable(value));
    }

    private static String enumText(
            Node node,
            String field,
            Set<String> allowed) {
        String value = scalarText(
                requiredProperty(node, field));
        if (!allowed.contains(value)) {
            throw new FixtureExecutionException(
                    "Unknown " + field
                            + " value " + value);
        }
        return value;
    }

    private static String optionalEnumText(
            Node node,
            String field,
            Set<String> allowed,
            String defaultValue) {
        Node authored = property(node, field);
        if (authored == null) {
            return defaultValue;
        }
        String value = scalarText(authored);
        if (!allowed.contains(value)) {
            throw new FixtureExecutionException(
                    "Unknown " + field
                            + " value " + value);
        }
        return value;
    }

    private static Boolean optionalBoolean(
            Node node,
            String field) {
        Node authored = property(node, field);
        return authored != null
                ? Boolean.valueOf(
                booleanScalar(authored))
                : null;
    }

    private static boolean booleanScalar(
            Node node) {
        Object value = node != null
                ? node.getValue()
                : null;
        if (!(value instanceof Boolean)) {
            throw new FixtureExecutionException(
                    "Expected boolean but was "
                            + printable(value));
        }
        return ((Boolean) value).booleanValue();
    }

    private static String requiredName(
            Node node,
            String location) {
        String name = node != null
                ? node.getName()
                : null;
        if (name == null
                || name.trim().isEmpty()) {
            throw new FixtureExecutionException(
                    location
                            + " requires a non-empty name");
        }
        return name;
    }

    private static void requireFields(
            Node node,
            Set<String> allowed,
            Set<String> required,
            String location) {
        if (node == null
                || node.getProperties() == null) {
            throw new FixtureExecutionException(
                    location
                            + " must be an object");
        }
        Set<String> actual =
                new LinkedHashSet<String>(
                        node.getProperties().keySet());
        addReservedFields(
                node, actual);
        if (!allowed.containsAll(actual)) {
            Set<String> unknown =
                    new LinkedHashSet<String>(
                            actual);
            unknown.removeAll(allowed);
            throw new FixtureExecutionException(
                    location
                            + " contains unknown controls "
                            + unknown);
        }
        if (!actual.containsAll(required)) {
            Set<String> missing =
                    new LinkedHashSet<String>(
                            required);
            missing.removeAll(actual);
            throw new FixtureExecutionException(
                    location
                            + " is missing required controls "
                            + missing);
        }
    }

    private static void addReservedFields(
            Node node,
            Set<String> actual) {
        if (node.getName() != null) {
            actual.add("name");
        }
        if (node.getDescription() != null) {
            actual.add("description");
        }
        if (node.getType() != null) {
            actual.add("type");
        }
        if (node.getItemType() != null) {
            actual.add("itemType");
        }
        if (node.getKeyType() != null) {
            actual.add("keyType");
        }
        if (node.getValueType() != null) {
            actual.add("valueType");
        }
        if (node.getRawValue() != null) {
            actual.add("value");
        }
        if (node.getItems() != null) {
            actual.add("items");
        }
        if (node.getContracts() != null) {
            actual.add("contracts");
        }
        if (node.getBlueId() != null) {
            actual.add("blueId");
        }
        if (node.getSchema() != null) {
            actual.add("schema");
        }
        if (node.getMergePolicy() != null) {
            actual.add("mergePolicy");
        }
        if (node.getPreviousBlueId() != null) {
            actual.add("$previous");
        }
        if (node.getPosition() != null) {
            actual.add("$pos");
        }
        if (node.getBlue() != null) {
            actual.add("blue");
        }
    }

    private static String findTimelineBlueId(
            Map<String, String> timelineIdByBlueId,
            String timelineId) {
        for (Map.Entry<String, String> entry
                : timelineIdByBlueId.entrySet()) {
            if (entry.getValue().equals(
                    timelineId)) {
                return entry.getKey();
            }
        }
        throw new FixtureExecutionException(
                "Completeness evidence names "
                        + "unknown Timeline "
                        + timelineId);
    }

    private static Object nodeValue(Node node) {
        if (node == null) {
            return null;
        }
        return scalarOrNode(node);
    }

    private static Object scalarOrNode(Node node) {
        if (node.getItems() != null) {
            List<Object> result =
                    new ArrayList<Object>();
            for (Node item : node.getItems()) {
                result.add(
                        scalarOrNode(item));
            }
            return result;
        }
        if (node.getProperties() != null) {
            Map<String, Object> result =
                    new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                result.put(
                        entry.getKey(),
                        scalarOrNode(
                                entry.getValue()));
            }
            return result;
        }
        return node.getValue() != null
                ? node.getValue()
                : node;
    }

    private static boolean equivalent(
            Runtime runtime,
            Object left,
            Object right) {
        return equivalentValues(left, right);
    }

    static boolean equivalentValues(
            Object left,
            Object right) {
        if (left instanceof Node
                && right instanceof Node) {
            String leftBlueId =
                    canonicalBlueId(left);
            String rightBlueId =
                    canonicalBlueId(right);
            return leftBlueId != null
                    && leftBlueId.equals(
                    rightBlueId);
        }
        if (left instanceof Node) {
            Node node = (Node) left;
            if (node.isReferenceOnly()) {
                return node.getBlueId().equals(
                        canonicalBlueId(right));
            }
            Object projected = scalarOrNode(node);
            return projected != node
                    && equivalentValues(
                    projected, right);
        }
        if (right instanceof Node) {
            Node node = (Node) right;
            if (node.isReferenceOnly()) {
                return node.getBlueId().equals(
                        canonicalBlueId(left));
            }
            Object projected = scalarOrNode(node);
            return projected != node
                    && equivalentValues(
                    left, projected);
        }
        if (left instanceof Number
                && right instanceof Number) {
            return decimal((Number) left)
                    .compareTo(
                            decimal((Number) right))
                    == 0;
        }
        if (left instanceof List
                && right instanceof List) {
            List<?> leftList =
                    (List<?>) left;
            List<?> rightList =
                    (List<?>) right;
            if (leftList.size()
                    != rightList.size()) {
                return false;
            }
            for (int index = 0;
                 index < leftList.size();
                 index++) {
                if (!equivalentValues(
                        leftList.get(index),
                        rightList.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Map
                && right instanceof Map) {
            Map<?, ?> leftMap =
                    (Map<?, ?>) left;
            Map<?, ?> rightMap =
                    (Map<?, ?>) right;
            if (!leftMap.keySet().equals(
                    rightMap.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> entry
                    : leftMap.entrySet()) {
                if (!equivalentValues(
                        entry.getValue(),
                        rightMap.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    private static String canonicalBlueId(
            Object value) {
        if (value instanceof Node) {
            Node node = (Node) value;
            if (node.isReferenceOnly()) {
                return node.getBlueId();
            }
            try {
                return BlueIdCalculator.calculateBlueId(
                        node.clone().blue(null));
            } catch (IllegalArgumentException
                     | NullPointerException unsupported) {
                return null;
            }
        }
        if (!(value instanceof String)
                && !(value instanceof Number)
                && !(value instanceof Boolean)
                && !(value instanceof List)
                && !(value instanceof Map)) {
            return null;
        }
        try {
            return BlueIdCalculator.INSTANCE
                    .calculate(value);
        } catch (IllegalArgumentException
                 | NullPointerException unsupported) {
            return null;
        }
    }

    private static BigDecimal decimal(
            Number number) {
        return new BigDecimal(number.toString());
    }

    private static boolean collectionContains(
            Runtime runtime,
            Collection<?> actual,
            Object expected) {
        for (Object candidate : actual) {
            if (equivalent(
                    runtime,
                    candidate,
                    expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean collectionContainsAll(
            Runtime runtime,
            Collection<?> actual,
            Collection<?> expected) {
        for (Object value : expected) {
            if (!collectionContains(
                    runtime, actual, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean collectionContainsAny(
            Runtime runtime,
            Collection<?> actual,
            Collection<?> expected) {
        for (Object value : expected) {
            if (collectionContains(
                    runtime, actual, value)) {
                return true;
            }
        }
        return false;
    }

    private static String printable(
            Object value) {
        return String.valueOf(value);
    }

    private static String diagnostic(
            Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current.getMessage() == null
                || current.getMessage()
                .trim().isEmpty())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message != null
                && !message.trim().isEmpty()
                ? ": " + message
                : "");
    }

    private static FixtureExecutionException
    unsupported(
            FixtureCase fixtureCase,
            String control,
            String value) {
        return new FixtureExecutionException(
                fixtureCase.caseId()
                        + ": unsupported "
                        + control + " ("
                        + value + ")");
    }

    @SafeVarargs
    private static <T> Set<T> immutableSet(
            T... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<T>(
                        Arrays.asList(values)));
    }

    static final class Audit {
        private final int caseCount;
        private final Map<String, Execution> passed;
        private final Map<String, String> failed;

        private Audit(
                int caseCount,
                Map<String, Execution> passed,
                Map<String, String> failed) {
            this.caseCount = caseCount;
            this.passed =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<String, Execution>(
                                    passed));
            this.failed =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<String, String>(
                                    failed));
        }

        int caseCount() {
            return caseCount;
        }

        int passedCount() {
            return passed.size();
        }

        int failedCount() {
            return failed.size();
        }

        Map<String, String> failures() {
            return failed;
        }
    }

    static final class FixtureCase {
        private final Fixture fixture;
        private final Variant variant;

        private FixtureCase(
                Fixture fixture,
                Variant variant) {
            this.fixture = fixture;
            this.variant = variant;
        }

        String caseId() {
            return fixture.id
                    + "@" + variant.name;
        }

        String resource() {
            return fixture.resource;
        }

        @Override
        public String toString() {
            return caseId();
        }
    }

    static final class Execution {
        private final String caseId;
        private final Map<String, Object> projections;

        private Execution(
                String caseId,
                Map<String, Object> projections) {
            this.caseId = caseId;
            this.projections =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<String, Object>(
                                    projections));
        }

        String caseId() {
            return caseId;
        }

        Object projection(String name) {
            return projections.get(name);
        }
    }

    private static final class ProcessInputs {
        private final Node document;
        private final Node event;
        private final VerifiedExecutionEvidence evidence;
        private final Set<String> preservedBodyPaths;
        private final Set<String> forbiddenBodyBlueIds;

        private ProcessInputs(
                Node document,
                Node event,
                VerifiedExecutionEvidence evidence,
                Set<String> preservedBodyPaths,
                Set<String> forbiddenBodyBlueIds) {
            this.document =
                    Objects.requireNonNull(
                            document, "document");
            this.event =
                    Objects.requireNonNull(
                            event, "event");
            this.evidence =
                    Objects.requireNonNull(
                            evidence, "evidence");
            this.preservedBodyPaths =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<String>(
                                    Objects.requireNonNull(
                                            preservedBodyPaths,
                                            "preservedBodyPaths")));
            this.forbiddenBodyBlueIds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<String>(
                                    Objects.requireNonNull(
                                            forbiddenBodyBlueIds,
                                            "forbiddenBodyBlueIds")));
        }
    }

    static final class AuthoredFeederEvidence {
        private final long managedRootRevision;
        private final long indexedRootRevision;
        private final List<String>
                eligibleSourceChannelKeys;

        private AuthoredFeederEvidence(
                long managedRootRevision,
                long indexedRootRevision,
                List<String> eligibleSourceChannelKeys) {
            this.managedRootRevision =
                    managedRootRevision;
            this.indexedRootRevision =
                    indexedRootRevision;
            this.eligibleSourceChannelKeys =
                    Collections.unmodifiableList(
                            new ArrayList<String>(
                                    eligibleSourceChannelKeys));
        }

        long managedRootRevision() {
            return managedRootRevision;
        }

        long indexedRootRevision() {
            return indexedRootRevision;
        }

        List<String> eligibleSourceChannelKeys() {
            return eligibleSourceChannelKeys;
        }

        CoordinationRoutingHarness.DeliveryOccurrence[]
        deliveryOccurrences(String caseId) {
            CoordinationRoutingHarness.DeliveryOccurrence[]
                    result =
                    new CoordinationRoutingHarness
                            .DeliveryOccurrence[
                            eligibleSourceChannelKeys.size()];
            for (int index = 0;
                 index < eligibleSourceChannelKeys.size();
                 index++) {
                String authored =
                        eligibleSourceChannelKeys.get(index);
                String scopePath = JsonPointer.ROOT;
                String sourceKey = authored;
                if (authored.startsWith("/")) {
                    int delimiter =
                            authored.lastIndexOf(':');
                    if (delimiter <= 0
                            || delimiter
                            == authored.length() - 1) {
                        throw new FixtureExecutionException(
                                caseId
                                        + ": invalid scoped source "
                                        + "occurrence " + authored);
                    }
                    scopePath =
                            authored.substring(
                                    0, delimiter);
                    sourceKey =
                            authored.substring(
                                    delimiter + 1);
                }
                result[index] =
                        CoordinationRoutingHarness
                                .DeliveryOccurrence
                                .at(
                                        scopePath,
                                        sourceKey);
            }
            return result;
        }
    }

    static final class BoundedPrefetchProvider
            implements NodeProvider {
        private final NodeProvider delegate;
        private final List<String> orderedBlueIds;
        private final int maximumPrefetch;
        private final Map<String, NodeProviderResult> cache =
                new LinkedHashMap<String, NodeProviderResult>();

        BoundedPrefetchProvider(
                NodeProvider delegate,
                Collection<String> candidateBlueIds,
                int maximumPrefetch) {
            this.delegate =
                    Objects.requireNonNull(
                            delegate, "delegate");
            if (maximumPrefetch <= 0) {
                throw new IllegalArgumentException(
                        "maximumPrefetch must be positive");
            }
            this.maximumPrefetch = maximumPrefetch;
            Set<String> distinct =
                    new LinkedHashSet<String>(
                            Objects.requireNonNull(
                                    candidateBlueIds,
                                    "candidateBlueIds"));
            if (distinct.contains(null)) {
                throw new IllegalArgumentException(
                        "candidateBlueIds must not contain null");
            }
            List<String> ordered =
                    new ArrayList<String>(distinct);
            Collections.sort(ordered);
            this.orderedBlueIds =
                    Collections.unmodifiableList(ordered);
        }

        @Override
        public List<Node> fetchByBlueId(
                String blueId) {
            NodeProviderResult result =
                    fetchResultByBlueId(blueId);
            if (result.outcome()
                    == NodeProviderOutcome.FOUND) {
                return result.nodes();
            }
            if (result.outcome()
                    == NodeProviderOutcome.INVALID_EVIDENCE) {
                throw new IllegalArgumentException(
                        result.diagnostic().orElse(
                                "Provider returned invalid evidence for "
                                        + blueId));
            }
            if (result.outcome()
                    == NodeProviderOutcome.UNAVAILABLE) {
                throw new IllegalStateException(
                        result.diagnostic().orElse(
                                "Provider unavailable for "
                                        + blueId));
            }
            return null;
        }

        @Override
        public synchronized NodeProviderResult
        fetchResultByBlueId(
                String blueId) {
            Objects.requireNonNull(
                    blueId, "blueId");
            NodeProviderResult retained =
                    cache.get(blueId);
            if (retained != null) {
                return retained;
            }
            int index =
                    Collections.binarySearch(
                            orderedBlueIds,
                            blueId);
            if (index < 0) {
                return delegate.fetchResultByBlueId(
                        blueId);
            }
            int first =
                    (index / maximumPrefetch)
                            * maximumPrefetch;
            int last =
                    Math.min(
                            first + maximumPrefetch,
                            orderedBlueIds.size());
            for (int current = first;
                 current < last;
                 current++) {
                String candidate =
                        orderedBlueIds.get(current);
                if (!cache.containsKey(candidate)) {
                    cache.put(
                            candidate,
                            Objects.requireNonNull(
                                    delegate
                                            .fetchResultByBlueId(
                                                    candidate),
                                    "provider result"));
                }
            }
            return cache.get(blueId);
        }
    }

    private static final class SelectedBodyProvider
            implements NodeProvider {
        private final NodeProvider delegate;
        private final Set<String> blockedBlueIds;

        private SelectedBodyProvider(
                NodeProvider delegate,
                Set<String> blockedBlueIds) {
            this.delegate =
                    Objects.requireNonNull(
                            delegate, "delegate");
            this.blockedBlueIds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<String>(
                                    blockedBlueIds));
        }

        @Override
        public List<Node> fetchByBlueId(
                String blueId) {
            if (blockedBlueIds.contains(
                    blueId)) {
                throw new IllegalArgumentException(
                        "Strict splitter selection rejected "
                                + "executable body "
                                + blueId);
            }
            return delegate.fetchByBlueId(
                    blueId);
        }

        @Override
        public NodeProviderResult
        fetchResultByBlueId(
                String blueId) {
            if (blockedBlueIds.contains(
                    blueId)) {
                return NodeProviderResult
                        .invalidEvidence(
                                "Strict splitter selection "
                                        + "rejected executable "
                                        + "body " + blueId);
            }
            return delegate.fetchResultByBlueId(
                    blueId);
        }
    }

    static final class FixtureExecutionException
            extends RuntimeException {
        private final Execution execution;

        private FixtureExecutionException(
                String message) {
            this(message, null, null);
        }

        private FixtureExecutionException(
                String message,
                Throwable cause) {
            this(message, cause, null);
        }

        private FixtureExecutionException(
                String message,
                Throwable cause,
                Execution execution) {
            super(message, cause);
            this.execution = execution;
        }

        private FixtureExecutionException withExecution(
                Execution exactExecution) {
            if (execution != null) {
                return this;
            }
            return new FixtureExecutionException(
                    getMessage(),
                    this,
                    exactExecution);
        }

        Execution execution() {
            return execution;
        }
    }

    private static final class Fixture {
        private final String resource;
        private final String id;
        private final Operation operation;
        private final Node input;
        private final List<Variant> variants;
        private final List<Assertion> assertions;

        private Fixture(
                String resource,
                String id,
                Operation operation,
                Node input,
                List<Variant> variants,
                List<Assertion> assertions) {
            this.resource = resource;
            this.id = id;
            this.operation = operation;
            this.input = input;
            this.variants = variants;
            this.assertions = assertions;
        }

        private boolean hasVariantAssertions() {
            for (Assertion assertion : assertions) {
                if (assertion.operator
                        == Operator.SAME_ACROSS_VARIANTS) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Variant {
        private final String name;
        private final String rootForm;
        private final String eventForm;
        private final String cache;
        private final String batching;
        private final String mandateDocumentForm;
        private final Boolean rootEmits;

        private Variant(
                String name,
                String rootForm,
                String eventForm,
                String cache,
                String batching,
                String mandateDocumentForm,
                Boolean rootEmits) {
            this.name = name;
            this.rootForm = rootForm;
            this.eventForm = eventForm;
            this.cache = cache;
            this.batching = batching;
            this.mandateDocumentForm =
                    mandateDocumentForm;
            this.rootEmits = rootEmits;
        }

        private static Variant defaultVariant() {
            return new Variant(
                    "default",
                    "inline",
                    "inline",
                    "cold",
                    "unbatched",
                    "inline",
                    null);
        }

        private boolean isDefault() {
            return "default".equals(name);
        }
    }

    private static final class Assertion {
        private final String projection;
        private final Operator operator;
        private final Node expected;
        private final String expectedProjection;

        private Assertion(
                String projection,
                Operator operator,
                Node expected,
                String expectedProjection) {
            this.projection = projection;
            this.operator = operator;
            this.expected = expected;
            this.expectedProjection =
                    expectedProjection;
        }
    }

    private enum Operation {
        CHANNEL_CLASSIFY(
                "channel-classify",
                immutableSet(
                        "root",
                        "event",
                        "feeder",
                        "mandateState"),
                immutableSet("root", "event")),
        PROCESS(
                "process",
                immutableSet(
                        "root",
                        "event",
                        "feeder",
                        "gasLimit",
                        "mandateState",
                        "splitter",
                        "variants"),
                immutableSet("root", "event")),
        GAS_INTEGRATION(
                "gas-integration",
                immutableSet(
                        "root",
                        "event",
                        "feeder",
                        "gasLimit",
                        "parentRemainingGas"),
                immutableSet(
                        "root",
                        "event",
                        "gasLimit",
                        "parentRemainingGas")),
        MANDATE_ELIGIBILITY(
                "mandate-eligibility",
                immutableSet(
                        "root",
                        "event",
                        "feeder",
                        "mandateState",
                        "variants"),
                immutableSet(
                        "root",
                        "event",
                        "feeder",
                        "mandateState")),
        PROVIDER_ELIGIBILITY(
                "provider-eligibility",
                immutableSet(
                        "providerMandates",
                        "providerActor",
                        "requestTimestamp",
                        "request",
                        "feeder"),
                immutableSet(
                        "providerMandates",
                        "providerActor",
                        "requestTimestamp",
                        "request",
                        "feeder")),
        SPLIT(
                "split",
                immutableSet(
                        "root",
                        "event",
                        "feeder",
                        "splitter",
                        "variants"),
                immutableSet(
                        "root",
                        "splitter")),
        TIMELINE_ORDER(
                "timeline-order",
                immutableSet(
                        "entries",
                        "completeness"),
                immutableSet(
                        "entries",
                        "completeness"));

        private final String wireValue;
        private final Set<String> allowedInputFields;
        private final Set<String> requiredInputFields;

        Operation(
                String wireValue,
                Set<String> allowedInputFields,
                Set<String> requiredInputFields) {
            this.wireValue = wireValue;
            this.allowedInputFields =
                    allowedInputFields;
            this.requiredInputFields =
                    requiredInputFields;
        }

        private static Operation fromWireValue(
                String value) {
            for (Operation operation : values()) {
                if (operation.wireValue.equals(
                        value)) {
                    return operation;
                }
            }
            throw new FixtureExecutionException(
                    "Unknown fixture operation "
                            + value);
        }
    }

    private enum Operator {
        ABSENT("absent", false) {
            @Override
            boolean test(
                    Runtime runtime,
                    Object actual,
                    Object expected) {
                return actual == null
                        || actual instanceof Collection
                        && ((Collection<?>) actual)
                        .isEmpty();
            }
        },
        CONTAINS("contains", true) {
            @Override
            boolean test(
                    Runtime runtime,
                    Object actual,
                    Object expected) {
                return actual instanceof Collection
                        && expected instanceof Collection
                        ? collectionContainsAll(
                                runtime,
                                (Collection<?>) actual,
                                (Collection<?>) expected)
                        : actual instanceof Collection
                        && collectionContains(
                                runtime,
                                (Collection<?>) actual,
                                expected);
            }
        },
        EQUALS("equals", true) {
            @Override
            boolean test(
                    Runtime runtime,
                    Object actual,
                    Object expected) {
                return equivalent(
                        runtime, actual, expected);
            }
        },
        EQUALS_PROJECTION(
                "equalsProjection", true) {
            @Override
            boolean test(
                    Runtime runtime,
                    Object actual,
                    Object expected) {
                return equivalent(
                        runtime, actual, expected);
            }
        },
        GREATER_THAN("greaterThan", true) {
            @Override
            boolean test(
                    Runtime runtime,
                    Object actual,
                    Object expected) {
                return actual instanceof Number
                        && expected instanceof Number
                        && new java.math.BigDecimal(
                        actual.toString())
                        .compareTo(
                                new java.math.BigDecimal(
                                        expected.toString()))
                        > 0;
            }
        },
        NOT_CONTAINS("notContains", true) {
            @Override
            boolean test(
                    Runtime runtime,
                    Object actual,
                    Object expected) {
                return actual instanceof Collection
                        && expected instanceof Collection
                        ? !collectionContainsAny(
                                runtime,
                                (Collection<?>) actual,
                                (Collection<?>) expected)
                        : actual instanceof Collection
                        && !collectionContains(
                                runtime,
                                (Collection<?>) actual,
                                expected);
            }
        },
        PRESENT("present", false) {
            @Override
            boolean test(
                    Runtime runtime,
                    Object actual,
                    Object expected) {
                return actual != null
                        && (!(actual
                        instanceof Collection)
                        || !((Collection<?>) actual)
                        .isEmpty());
            }
        },
        SAME_ACROSS_VARIANTS(
                "sameAcrossVariants", false) {
            @Override
            boolean test(
                    Runtime runtime,
                    Object actual,
                    Object expected) {
                throw new UnsupportedOperationException(
                        "deferred across variants");
            }
        },
        SEQUENCE_EQUALS(
                "sequenceEquals", true) {
            @Override
            boolean test(
                    Runtime runtime,
                    Object actual,
                    Object expected) {
                return actual instanceof List
                        && expected instanceof List
                        && equivalent(
                        runtime, actual, expected);
            }
        };

        private final String wireValue;
        private final boolean requiresExpected;

        Operator(
                String wireValue,
                boolean requiresExpected) {
            this.wireValue = wireValue;
            this.requiresExpected =
                    requiresExpected;
        }

        abstract boolean test(
                Runtime runtime,
                Object actual,
                Object expected);

        private static Operator fromWireValue(
                String value) {
            for (Operator operator : values()) {
                if (operator.wireValue.equals(
                        value)) {
                    return operator;
                }
            }
            throw new FixtureExecutionException(
                    "Unknown assertion operator "
                            + value);
        }
    }

    private static final class Runtime
            implements AutoCloseable {
        private final BlueRepository repository;
        private final Blue blue;
        private final Long gasLimit;
        private final ProcessingEventIdentityEvidence
                processingEventIdentityEvidence;
        private DocumentProcessor processor;

        private Runtime(Long gasLimit) {
            this.repository =
                    BlueRepository.latest();
            this.gasLimit = gasLimit;
            /*
             * This is the isolated behavior-conformance lane. It exercises
             * authored Coordination cases independently and never satisfies
             * or substitutes for the fail-closed fixed-Repository release
             * audit.
             */
            this.blue =
                    repository.configure(
                            new Blue());
            this.processingEventIdentityEvidence =
                    new ProcessingEventIdentityEvidence();
            CoordinationProcessors
                    .registerWith(
                            blue,
                            processorOptions());
            CoordinationProcessors
                    .registerTimelineSubtype(
                            blue,
                            MyOSTimelineChannel.class);
            this.processor = configuredProcessor();
        }

        private CoordinationProcessorOptions
        processorOptions() {
            return CoordinationProcessorOptions
                    .builder()
                    .processingEventIdentityObserver(
                            processingEventIdentityEvidence)
                    .build();
        }

        private DocumentProcessor
        configuredProcessor() {
            return gasLimit == null
                    ? blue.getDocumentProcessor()
                    : CoordinationConfiguredProcessorFactory
                    .withGasLimit(
                            blue,
                            gasLimit.longValue());
        }

        private void installFragmentProvider(
                NodeProvider fragmentProvider) {
            DocumentProcessor configured =
                    blue.getDocumentProcessor();
            if (processor != configured) {
                processor.close();
            }
            NodeProvider existing =
                    blue.getNodeProvider();
            blue.nodeProvider(
                    new SequentialNodeProvider(
                            Objects.requireNonNull(
                                    fragmentProvider,
                                    "fragmentProvider"),
                            existing));
            CoordinationProcessors
                    .registerWith(
                            blue,
                            processorOptions());
            CoordinationProcessors
                    .registerTimelineSubtype(
                            blue,
                            MyOSTimelineChannel.class);
            processor = configuredProcessor();
        }

        private void installExecutionEvidencePlan(
                VerifiedExecutionEvidence evidence) {
            DocumentProcessor configured =
                    blue.getDocumentProcessor();
            if (processor != configured) {
                processor.close();
            }
            processor =
                    CoordinationConfiguredProcessorFactory
                            .withExecutionEvidencePlan(
                                    blue,
                                    gasLimit,
                                    evidence);
        }

        private void warm(Node reference) {
            blue.resolve(
                    Objects.requireNonNull(
                            reference, "reference"));
        }

        private Node materialize(Node authored) {
            Node exactAuthoredNode =
                    authored.clone()
                            .blue(
                                    repository
                                            .typeAliasBlue());
            return blue.preprocess(
                    exactAuthoredNode);
        }

        private Node bindInlineRootType(
                Node exactRoot) {
            Node root =
                    Objects.requireNonNull(
                            exactRoot, "exactRoot");
            Node suppliedType = root.getType();
            if (suppliedType == null
                    || suppliedType.isReferenceOnly()) {
                return root;
            }
            Node exactType =
                    CoordinationProcessHeaderBridge
                            .canonicalExactCopy(
                                    suppliedType);
            String typeBlueId =
                    BlueIdCalculator.calculateBlueId(
                            exactType);
            Map<String, Node> exactSources =
                    new LinkedHashMap<String, Node>();
            exactSources.put(
                    typeBlueId,
                    exactType.clone());
            Node inheritedContracts =
                    exactType.getContracts();
            if (inheritedContracts != null
                    && inheritedContracts
                    .getProperties() != null) {
                for (Node contribution :
                        inheritedContracts
                                .getProperties()
                                .values()) {
                    if (contribution == null
                            || contribution
                            .isReferenceOnly()) {
                        continue;
                    }
                    Node exactContribution =
                            CoordinationProcessHeaderBridge
                                    .canonicalExactCopy(
                                            contribution);
                    exactSources.put(
                            BlueIdCalculator
                                    .calculateBlueId(
                                            exactContribution),
                            exactContribution);
                }
            }
            installFragmentProvider(blueId -> {
                Node source =
                        exactSources.get(blueId);
                return source != null
                        ? Collections.singletonList(
                                source.clone())
                        : null;
            });
            Node bound = root.clone()
                    .type(new Node().blueId(
                            typeBlueId));
            String expectedRootBlueId =
                    BlueIdCalculator.calculateBlueId(
                            root);
            String boundRootBlueId =
                    BlueIdCalculator.calculateBlueId(
                            bound);
            if (!expectedRootBlueId.equals(
                    boundRootBlueId)) {
                throw new FixtureExecutionException(
                        "Binding an authored inline Root type changed "
                                + "the exact Root identity from "
                                + expectedRootBlueId
                                + " to "
                                + boundRootBlueId);
            }
            return bound;
        }

        private DocumentProcessingResult initializeAuthored(
                Node authored) {
            return processor.initializeDocument(
                    bindInlineRootType(
                            materialize(
                                    authored)));
        }

        private String qualifiedType(Node node) {
            Node type = node.getType();
            if (type == null
                    || type.getBlueId() == null) {
                return null;
            }
            for (String qualifiedName
                    : repository.qualifiedNames()) {
                if (type.getBlueId().equals(
                        repository.blueId(
                                qualifiedName))) {
                    return qualifiedName;
                }
            }
            return type.getBlueId();
        }

        @Override
        public void close() {
            if (processor
                    != blue.getDocumentProcessor()) {
                processor.close();
            }
            blue.close();
        }
    }
}
