package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.TimelineEntry;
import blue.language.model.NodeWireForm;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.ResultingDocument;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** Standalone, non-cacheable cyclic correctness and performance campaign. */
public final class CyclicPerformanceAcceptance {
    private static final String OUTPUT_PROPERTY =
            "blue.coordination.cyclicPerformance.output";
    private static final String WARMUPS_PROPERTY =
            "blue.coordination.cyclicPerformance.warmups";
    private static final String SAMPLES_PROPERTY =
            "blue.coordination.cyclicPerformance.samples";
    private static final int DEFAULT_WARMUPS = 20;
    private static final int DEFAULT_SAMPLES = 50;
    private static final long EXPECTED_MAX_HEAP_BYTES =
            2L * 1024L * 1024L * 1024L;
    private static final long HOST_OVERHEAD_LIMIT_NANOS = 100_000_000L;
    private static final double LOCALITY_OVERHEAD_LIMIT = 0.10d;
    private static final Path HARDWARE_BASELINE_PATH = Path.of(
            "stabilization/cyclic-topology-round/baseline.json");

    private static final String APPEND_PHASE = "append.total";
    private static final String ROUTE_PHASE = "process.routeLookup";
    private static final List<String> RAW_PHASES = List.of(
            APPEND_PHASE,
            ROUTE_PHASE,
            ContractsClosureAdapter.PLAN_CONSTRUCTION_PHASE,
            ContractsClosureAdapter.PROCESSOR_PHASE,
            ContractsClosureAdapter.RESULT_VALIDATION_PHASE,
            ContractsClosureAdapter.PUBLICATION_PHASE,
            ContractsClosureExecutionMetricsObserver
                    .MANAGED_DOCUMENT_STEP_INCLUSIVE_PHASE,
            ContractsClosureExecutionMetricsObserver
                    .MANAGED_DOCUMENT_STEP_EXCLUSIVE_PHASE,
            ContractsClosureExecutionMetricsObserver
                    .COMPONENT_FINALIZATION_PROOF_PHASE,
            ContractsClosureExecutionMetricsObserver
                    .SUCCESSFUL_RESULT_ASSEMBLY_PHASE);
    private static final List<String> HOST_RESIDUAL_PHASES = List.of(
            ROUTE_PHASE,
            ContractsClosureAdapter.PLAN_CONSTRUCTION_PHASE,
            ContractsClosureAdapter.PROCESSOR_PHASE,
            ContractsClosureAdapter.RESULT_VALIDATION_PHASE,
            ContractsClosureAdapter.PUBLICATION_PHASE);
    private static final List<String> REQUIRED_OPERATION_PHASES = List.of(
            "operation.wall",
            APPEND_PHASE,
            ROUTE_PHASE,
            ContractsClosureAdapter.PLAN_CONSTRUCTION_PHASE,
            ContractsClosureAdapter.PROCESSOR_PHASE,
            ContractsClosureExecutionMetricsObserver
                    .MANAGED_DOCUMENT_STEP_INCLUSIVE_PHASE,
            ContractsClosureExecutionMetricsObserver
                    .MANAGED_DOCUMENT_STEP_EXCLUSIVE_PHASE,
            ContractsClosureExecutionMetricsObserver
                    .COMPONENT_FINALIZATION_PROOF_PHASE,
            ContractsClosureExecutionMetricsObserver
                    .SUCCESSFUL_RESULT_ASSEMBLY_PHASE,
            ContractsClosureAdapter.RESULT_VALIDATION_PHASE,
            ContractsClosureAdapter.PUBLICATION_PHASE,
            "host.residual");
    private static final List<String> REQUIRED_COUNTERS = List.of(
            "journal.entriesStoredWhole",
            "routing.closureDeliveriesSelected",
            OperationRouteIndex.DIRECT_ROUTE_SNAPSHOTS,
            OperationRouteIndex.DIRECT_ROUTE_REVALIDATION_SNAPSHOTS,
            WholeRequestEntryFactory.REQUEST_SOURCES_PARSED,
            ContractsClosureAdapter.PLAN_CONSTRUCTIONS,
            ContractsClosureAdapter.COHORTS_SELECTED,
            ContractsClosureAdapter.DOCUMENT_OPENS,
            ContractsClosureAdapter.UNRELATED_DOCUMENT_OPENS,
            ContractsClosureAdapter.OCCURRENCE_ROWS_EXAMINED,
            ContractsClosureAdapter.COMPONENT_STATES_READ,
            ContractsClosureAdapter.RESULTING_COMPONENTS,
            InMemoryDocumentStore.FULL_ENVIRONMENT_SCANS,
            InMemoryDocumentStore.CLOSURE_TOPOLOGY_SNAPSHOTS,
            InMemoryDocumentStore.CLOSURE_HEADS_CAPTURED,
            InMemoryDocumentStore.CLOSURE_COMPONENT_STATES_CAPTURED,
            ContractsClosureExecutionMetricsObserver
                    .ACCEPTED_WORK_OCCURRENCES,
            ContractsClosureExecutionMetricsObserver.ISOLATED_DOCUMENT_STEPS,
            ContractsClosureExecutionMetricsObserver
                    .TENTATIVE_COMPONENT_FINALIZATIONS,
            ContractsClosureExecutionMetricsObserver.CANONICAL_CYCLIC_BYTES,
            ContractsClosureExecutionMetricsObserver
                    .UNRELATED_COMPONENT_FINALIZATIONS,
            BlueRuntime.PROVIDER_EXACT_NODE_READS,
            ContractsStructuralWorkMetrics.GLOBAL_STATE_PASSES,
            ContractsStructuralWorkMetrics.GLOBAL_STATE_ENTRIES_TRAVERSED,
            ContractsStructuralWorkMetrics.GLOBAL_SESSION_ENTRIES_TRAVERSED,
            ContractsStructuralWorkMetrics.GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED,
            ContractsStructuralWorkMetrics.GLOBAL_COMPONENT_ENTRIES_TRAVERSED,
            ContractsStructuralWorkMetrics.GLOBAL_GRAPH_ENTRIES_TRAVERSED,
            ContractsStructuralWorkMetrics
                    .GLOBAL_SUBSCRIPTION_ENTRIES_TRAVERSED,
            ContractsStructuralWorkMetrics.GLOBAL_RECEIPT_ENTRIES_TRAVERSED,
            ContractsStructuralWorkMetrics.GLOBAL_ROUTE_ENTRIES_TRAVERSED,
            ContractsStructuralWorkMetrics.GLOBAL_EVIDENCE_ENTRIES_TRAVERSED);

    private CyclicPerformanceAcceptance() {
    }

    /** Runs the configured campaign and deliberately fails after artifacts. */
    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 0) {
            throw new IllegalArgumentException(
                    "cyclicPerformanceAcceptance takes no arguments");
        }
        int warmups = integerProperty(WARMUPS_PROPERTY, DEFAULT_WARMUPS, 0);
        int samples = integerProperty(SAMPLES_PROPERTY, DEFAULT_SAMPLES, 1);
        RuntimeIdentity runtime = RuntimeIdentity.capture();
        BaselineBinding baseline = BaselineBinding.capture(
                HARDWARE_BASELINE_PATH, runtime);
        List<String> authorityReasons = authorityReasons(
                warmups, samples, runtime, baseline);
        boolean authoritative = authorityReasons.isEmpty();

        ArrayList<ShapeRun> shapes = new ArrayList<>();
        for (CyclicPerformanceScenarios.Shape shape
                : CyclicPerformanceScenarios.Shape.values()) {
            shapes.add(runShape(shape, warmups, samples, authoritative));
        }

        List<Gate> campaignGates = campaignGates(
                shapes,
                authoritative,
                authorityReasons,
                baseline,
                warmups == DEFAULT_WARMUPS && samples == DEFAULT_SAMPLES);
        GateStatus overall = overallStatus(shapes, campaignGates);
        Path output = Path.of(System.getProperty(
                OUTPUT_PROPERTY,
                "build/reports/cyclic-performance"))
                .toAbsolutePath().normalize();
        Map<String, Object> report = report(
                warmups,
                samples,
                runtime,
                baseline,
                authoritative,
                authorityReasons,
                shapes,
                campaignGates,
                overall);
        writeAtomically(
                output.resolve("cyclic-performance.json"),
                Json.render(report) + System.lineSeparator());
        writeAtomically(
                output.resolve("cyclic-performance.md"),
                markdown(
                        report,
                        baseline,
                        runtime,
                        shapes,
                        campaignGates,
                        overall));

        if (overall != GateStatus.PASS) {
            throw new IllegalStateException(
                    "Cyclic acceptance is " + overall
                            + "; evidence was written to " + output);
        }
    }

    private static ShapeRun runShape(
            CyclicPerformanceScenarios.Shape shape,
            int warmupCount,
            int sampleCount,
            boolean authoritative) {
        ArrayList<IterationRun> warmups = new ArrayList<>();
        ArrayList<IterationRun> measured = new ArrayList<>();
        for (int index = 0; index < warmupCount; index++) {
            IterationRun iteration = runIteration(shape, "warmup", index);
            warmups.add(iteration);
            if (!iteration.completed()) {
                break;
            }
        }
        if (warmups.size() == warmupCount
                && warmups.stream().allMatch(IterationRun::completed)) {
            for (int index = 0; index < sampleCount; index++) {
                IterationRun iteration = runIteration(
                        shape, "measured", index);
                measured.add(iteration);
                if (!iteration.completed()) {
                    break;
                }
            }
        }
        return ShapeRun.create(
                shape,
                warmupCount,
                sampleCount,
                warmups,
                measured,
                authoritative);
    }

    private static IterationRun runIteration(
            CyclicPerformanceScenarios.Shape shape,
            String role,
            int index) {
        try (CyclicPerformanceScenarios.Prepared prepared =
                CyclicPerformanceScenarios.prepare(shape)) {
            String admissionSemantic = admissionProjection(
                    prepared.admissions(), false);
            String admissionGas = admissionProjection(
                    prepared.admissions(), true);
            ArrayList<OperationRun> operations = new ArrayList<>();
            for (CyclicPerformanceScenarios.OperationSpec operation
                    : prepared.operations()) {
                operations.add(runOperation(prepared, operation));
            }
            String affectedSemantic = digest(operations.stream()
                    .map(OperationRun::semanticFingerprint)
                    .toList().toString());
            String affectedGas = digest(operations.stream()
                    .map(OperationRun::gasFingerprint)
                    .toList().toString());
            String semantic = digest(admissionSemantic + affectedSemantic);
            String gas = digest(admissionGas + affectedGas);
            String bexProjection = digest(operations.stream()
                    .map(OperationRun::bexProjectionFingerprint)
                    .toList().toString());
            return new IterationRun(
                    role,
                    index,
                    true,
                    prepared.engineConstructionNanos(),
                    prepared.admissionNanos(),
                    prepared.admissions().size(),
                    prepared.unrelatedDocumentCount(),
                    semantic,
                    gas,
                    affectedSemantic,
                    affectedGas,
                    bexProjection,
                    operations,
                    null);
        } catch (RuntimeException failure) {
            return new IterationRun(
                    role,
                    index,
                    false,
                    null,
                    null,
                    0,
                    shape == CyclicPerformanceScenarios.Shape
                            .FIVE_MEMBER_PLUS_1000
                            ? CyclicPerformanceScenarios.UNRELATED_DOCUMENTS
                            : 0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    failure.getClass().getName() + ": "
                            + String.valueOf(failure.getMessage()));
        }
    }

    private static OperationRun runOperation(
            CyclicPerformanceScenarios.Prepared prepared,
            CyclicPerformanceScenarios.OperationSpec operation) {
        DefaultCoordinationEngine engine = prepared.engine();
        Set<String> priorReceiptKeys = Set.copyOf(engine.documents()
                .closureSnapshot(prepared.relevantDocuments())
                .closurePublicationReceipts().keySet());
        EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

        long operationStarted = System.nanoTime();
        long appendStarted = System.nanoTime();
        TimelineEntry entry = engine.appendAt(
                operation.timeline(),
                operation.operation(),
                operation.timestampMicros());
        long appendWallNanos = System.nanoTime() - appendStarted;
        long drainStarted = System.nanoTime();
        ProcessingDrainReceipt drain = engine.drain();
        long drainWallNanos = System.nanoTime() - drainStarted;
        long operationWallNanos = System.nanoTime() - operationStarted;
        EngineMetrics.MetricsSnapshot after = engine.metricsSnapshot();

        InMemoryDocumentStore.ClosureSnapshot closure = engine.documents()
                .closureSnapshot(prepared.relevantDocuments());
        List<ContractsClosurePublicationReceipt> receipts = closure
                .closurePublicationReceipts().entrySet().stream()
                .filter(item -> !priorReceiptKeys.contains(item.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        Map<String, Long> rawCounters = counterDeltas(before, after);
        long derivedResultingComponents = receipts.stream()
                .map(ContractsClosurePublicationReceipt::attempt)
                .map(attempt -> attempt.processResult())
                .mapToLong(result -> result.resultingComponents().size())
                .sum();
        LinkedHashMap<String, Long> expandedCounters = new LinkedHashMap<>(
                rawCounters);
        expandedCounters.put(
                "campaign.derived.resultingComponents",
                derivedResultingComponents);
        Map<String, Long> counters = Collections.unmodifiableMap(
                expandedCounters);
        Map<String, PhaseValue> phases = phaseValues(
                before,
                after,
                appendWallNanos,
                drainWallNanos,
                operationWallNanos,
                drain.elapsedNanos());
        List<List<String>> actualPartition = partition(
                closure.componentStates());
        List<List<String>> expectedPartition = partitionIds(
                operation.expectedPartition());
        int expectedReceipts = prepared.shape()
                == CyclicPerformanceScenarios.Shape.TWO_DISJOINT ? 2 : 1;
        List<String> dequeueWorkIds = dequeueWorkIds(receipts);
        long accepted = counter(counters,
                ContractsClosureExecutionMetricsObserver
                        .ACCEPTED_WORK_OCCURRENCES);
        long isolated = counter(counters,
                ContractsClosureExecutionMetricsObserver
                        .ISOLATED_DOCUMENT_STEPS);
        long actualChanged = drain.outcomesFor(entry.blueId()).size();

        ArrayList<Gate> gates = new ArrayList<>();
        gates.add(exactGate(
                "one-whole-timeline-entry",
                counter(counters, "journal.entriesStoredWhole"), 1L));
        gates.add(exactGate(
                "one-direct-route-snapshot",
                counter(counters,
                        OperationRouteIndex.DIRECT_ROUTE_SNAPSHOTS),
                1L));
        gates.add(exactGate(
                "no-direct-route-revalidation",
                counter(counters,
                        OperationRouteIndex
                                .DIRECT_ROUTE_REVALIDATION_SNAPSHOTS),
                0L));
        gates.add(exactGate(
                "exact-index-selected-direct-seeds",
                counter(counters, "routing.closureDeliveriesSelected"),
                operation.expectedDirectSeeds()));
        gates.add(new Gate(
                "zero-caller-selected-targets",
                GateStatus.PASS,
                true,
                0L,
                0L,
                "Proved by the campaign path: it exposes no caller-target "
                        + "seam, never invokes routeTargetCount, and submits "
                        + "only appendAt followed by drain."));
        gates.add(exactGate(
                "one-request-source-parse",
                counter(counters,
                        WholeRequestEntryFactory.REQUEST_SOURCES_PARSED),
                1L));
        gates.add(exactGate(
                "one-closure-plan",
                counter(counters,
                        ContractsClosureAdapter.PLAN_CONSTRUCTIONS),
                1L));
        gates.add(exactGate(
                "expected-cohorts",
                counter(counters, ContractsClosureAdapter.COHORTS_SELECTED),
                expectedReceipts));
        gates.add(exactGate(
                "typed-publication-receipts",
                receipts.size(),
                expectedReceipts));
        gates.add(booleanGate(
                "successful-atomic-publication",
                receipts.stream().allMatch(receipt -> receipt.commits()
                        && receipt.attempt().isComplete()
                        && receipt.attempt().processResult().atomic()
                        && receipt.attempt().processResult().status()
                                == ProcessorStatus.SUCCESS),
                "every added typed receipt must be complete SUCCESS"));
        gates.add(booleanGate(
                "quiescent-unpaused-drain",
                drain.quiescent() && !drain.paused() && !drain.blocked(),
                "quiescent=" + drain.quiescent()
                        + ", paused=" + drain.paused()
                        + ", blocked=" + drain.blocked()));
        gates.add(exactGate(
                "one-processed-entry",
                drain.processedEntries().size(),
                1L));
        gates.add(exactGate(
                "expected-changed-documents",
                actualChanged,
                operation.expectedChangedDocuments()));
        gates.add(booleanGate(
                "expected-component-partition",
                actualPartition.equals(expectedPartition),
                "expected=" + expectedPartition + ", actual="
                        + actualPartition));
        gates.add(exactGate(
                "exact-derived-resulting-components",
                derivedResultingComponents,
                operation.expectedPartition().size()));
        gates.add(exactGate(
                "narrow-full-environment-scans",
                counter(counters, InMemoryDocumentStore.FULL_ENVIRONMENT_SCANS),
                0L));
        gates.add(new Gate(
                "broad-global-state-traversals",
                counter(counters,
                        ContractsStructuralWorkMetrics.GLOBAL_STATE_PASSES)
                        == 0L ? GateStatus.PASS : GateStatus.FAIL,
                true,
                counter(counters,
                        ContractsStructuralWorkMetrics.GLOBAL_STATE_PASSES),
                0L,
                "This is the release-blocking broad traversal gate; the "
                        + "narrow FULL_ENVIRONMENT_SCANS counter is not a "
                        + "substitute."));
        gates.add(exactGate(
                "broad-global-state-entries-traversed",
                counter(counters, ContractsStructuralWorkMetrics
                        .GLOBAL_STATE_ENTRIES_TRAVERSED),
                0L));
        gates.add(exactGate(
                "no-unrelated-document-opens",
                counter(counters,
                        ContractsClosureAdapter.UNRELATED_DOCUMENT_OPENS),
                0L));
        gates.add(exactGate(
                "no-unrelated-component-finalizations",
                counter(counters, ContractsClosureExecutionMetricsObserver
                        .UNRELATED_COMPONENT_FINALIZATIONS),
                0L));
        gates.add(booleanGate(
                "accepted-work-is-isolated-one-document-at-a-time",
                accepted == isolated,
                "accepted=" + accepted + ", isolated=" + isolated));
        gates.add(exactGate(
                "exact-accepted-work-occurrences",
                accepted,
                operation.expectedAcceptedWork()));
        gates.add(exactGate(
                "exact-dequeued-work-occurrences",
                dequeueWorkIds.size(),
                operation.expectedAcceptedWork()));
        gates.add(booleanGate(
                "no-duplicate-dequeued-work-occurrences",
                dequeueWorkIds.size() == accepted
                        && new LinkedHashSet<>(dequeueWorkIds).size()
                                == dequeueWorkIds.size(),
                "accepted=" + accepted + ", dequeues="
                        + dequeueWorkIds.size() + ", unique="
                        + new LinkedHashSet<>(dequeueWorkIds).size()));
        gates.add(new Gate(
                "raw-bex-result-equality-observability",
                GateStatus.UNOBSERVABLE,
                true,
                null,
                null,
                "No raw BEX result fingerprint is exposed at the public "
                        + "engine boundary; only the exact observable closure "
                        + "projection is compared."));

        PhaseValue hostResidual = phases.get("host.residual");
        Gate hostGate;
        if (hostResidual.status() != GateStatus.PASS) {
            hostGate = new Gate(
                    "host-overhead-observed",
                    GateStatus.UNOBSERVABLE,
                    false,
                    null,
                    HOST_OVERHEAD_LIMIT_NANOS,
                    "Required non-overlapping top-level phase spans are "
                            + "missing; nested Language phases are never "
                            + "double-subtracted.");
        } else {
            hostGate = new Gate(
                    "host-overhead-observed",
                    hostResidual.nanos() <= HOST_OVERHEAD_LIMIT_NANOS
                            ? GateStatus.PASS : GateStatus.FAIL,
                    false,
                    hostResidual.nanos(),
                    HOST_OVERHEAD_LIMIT_NANOS,
                    "drain elapsed minus route, plan, processor, result "
                            + "validation, and publication");
        }
        gates.add(hostGate);
        for (String phase : REQUIRED_OPERATION_PHASES) {
            PhaseValue observation = phases.get(phase);
            GateStatus status = observation == null
                    ? GateStatus.UNOBSERVABLE : observation.status();
            gates.add(new Gate(
                    "required-phase-" + phase.replace('.', '-'),
                    status,
                    true,
                    observation == null ? null : observation.nanos(),
                    "positive operation-local emission",
                    status == GateStatus.PASS
                            ? "The phase emitted during this exact operation."
                            : "The required phase did not emit a valid "
                                    + "operation-local span."));
        }

        String semanticProjection = resultProjection(
                receipts, actualPartition, drain, entry, false);
        String gasProjection = resultProjection(
                receipts, actualPartition, drain, entry, true);
        String bexProjection = bexObservableProjection(receipts);
        return new OperationRun(
                operation.id(),
                entry.blueId(),
                operationWallNanos,
                drain.elapsedNanos(),
                counters,
                phases,
                actualPartition,
                receipts.size(),
                operation.expectedDirectSeeds(),
                operation.expectedAcceptedWork(),
                digest(semanticProjection),
                digest(gasProjection),
                digest(bexProjection),
                gates);
    }

    private static Map<String, PhaseValue> phaseValues(
            EngineMetrics.MetricsSnapshot before,
            EngineMetrics.MetricsSnapshot after,
            long appendWallNanos,
            long drainWallNanos,
            long operationWallNanos,
            long drainReportedNanos) {
        LinkedHashMap<String, PhaseValue> result = new LinkedHashMap<>();
        result.put("operation.wall", PhaseValue.observed(operationWallNanos));
        result.put("append.wall", PhaseValue.observed(appendWallNanos));
        result.put("drain.wall", PhaseValue.observed(drainWallNanos));
        result.put("drain.reported", PhaseValue.observed(drainReportedNanos));
        for (String phase : RAW_PHASES) {
            boolean present = before.phaseNanos().containsKey(phase)
                    || after.phaseNanos().containsKey(phase);
            long nanos = delta(
                    before.phaseNanos(), after.phaseNanos(), phase);
            result.put(phase, present && nanos > 0L
                    ? PhaseValue.observed(nanos)
                    : PhaseValue.unobservable());
        }
        boolean complete = HOST_RESIDUAL_PHASES.stream()
                .allMatch(phase -> result.get(phase).status()
                        == GateStatus.PASS);
        if (!complete) {
            result.put("host.residual", PhaseValue.unobservable());
        } else {
            long residual = drainReportedNanos;
            for (String phase : HOST_RESIDUAL_PHASES) {
                residual = Math.subtractExact(
                        residual, result.get(phase).nanos());
            }
            result.put("host.residual", residual < 0L
                    ? new PhaseValue(GateStatus.FAIL, residual)
                    : PhaseValue.observed(residual));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Long> counterDeltas(
            EngineMetrics.MetricsSnapshot before,
            EngineMetrics.MetricsSnapshot after) {
        TreeSet<String> names = new TreeSet<>();
        names.addAll(before.counters().keySet());
        names.addAll(after.counters().keySet());
        names.addAll(REQUIRED_COUNTERS);
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (String name : names) {
            long value = delta(before.counters(), after.counters(), name);
            if (value != 0L || importantCounter(name)) {
                result.put(name, value);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean importantCounter(String name) {
        return name.equals("journal.entriesStoredWhole")
                || name.equals("routing.closureDeliveriesSelected")
                || name.equals(OperationRouteIndex.DIRECT_ROUTE_SNAPSHOTS)
                || name.equals(OperationRouteIndex
                        .DIRECT_ROUTE_REVALIDATION_SNAPSHOTS)
                || name.equals(WholeRequestEntryFactory.REQUEST_SOURCES_PARSED)
                || name.equals(ContractsClosureAdapter.PLAN_CONSTRUCTIONS)
                || name.equals(ContractsClosureAdapter.COHORTS_SELECTED)
                || name.equals(ContractsClosureAdapter.DOCUMENT_OPENS)
                || name.equals(ContractsClosureAdapter.UNRELATED_DOCUMENT_OPENS)
                || name.equals(ContractsClosureAdapter
                        .OCCURRENCE_ROWS_EXAMINED)
                || name.equals(ContractsClosureAdapter.COMPONENT_STATES_READ)
                || name.equals(ContractsClosureAdapter.RESULTING_COMPONENTS)
                || name.equals(InMemoryDocumentStore.FULL_ENVIRONMENT_SCANS)
                || name.equals(InMemoryDocumentStore
                        .CLOSURE_TOPOLOGY_SNAPSHOTS)
                || name.equals(InMemoryDocumentStore.CLOSURE_HEADS_CAPTURED)
                || name.equals(InMemoryDocumentStore
                        .CLOSURE_COMPONENT_STATES_CAPTURED)
                || name.equals(ContractsStructuralWorkMetrics
                        .GLOBAL_STATE_PASSES)
                || name.equals(ContractsStructuralWorkMetrics
                        .GLOBAL_STATE_ENTRIES_TRAVERSED)
                || name.equals(ContractsClosureExecutionMetricsObserver
                        .ACCEPTED_WORK_OCCURRENCES)
                || name.equals(ContractsClosureExecutionMetricsObserver
                        .ISOLATED_DOCUMENT_STEPS)
                || name.equals(ContractsClosureExecutionMetricsObserver
                        .TENTATIVE_COMPONENT_FINALIZATIONS)
                || name.equals(ContractsClosureExecutionMetricsObserver
                        .CANONICAL_CYCLIC_BYTES)
                || name.equals(ContractsClosureExecutionMetricsObserver
                        .UNRELATED_COMPONENT_FINALIZATIONS)
                || name.equals(BlueRuntime.PROVIDER_EXACT_NODE_READS)
                || name.startsWith("contracts.publication.global");
    }

    private static long delta(
            Map<String, Long> before,
            Map<String, Long> after,
            String name) {
        long result = Math.subtractExact(
                after.getOrDefault(name, 0L),
                before.getOrDefault(name, 0L));
        if (result < 0L) {
            throw new IllegalStateException(
                    "Cumulative metric decreased: " + name);
        }
        return result;
    }

    private static long counter(Map<String, Long> counters, String name) {
        return counters.getOrDefault(name, 0L);
    }

    private static String admissionProjection(
            List<ContractsClosureAdmissionReceipt> receipts,
            boolean gasOnly) {
        ArrayList<String> values = new ArrayList<>();
        for (ContractsClosureAdmissionReceipt receipt : receipts) {
            ClosureProcessResult result = receipt.attempt().processResult();
            values.add(gasOnly
                    ? exactGasTraceProjection(result)
                    : receipt.publicationIdentity()
                            + '|' + receipt.publicationOutcome()
                            + '|' + receipt.documentIds()
                            + '|' + exactSemanticProjection(result));
        }
        return values.toString();
    }

    private static String resultProjection(
            List<ContractsClosurePublicationReceipt> receipts,
            List<List<String>> partition,
            ProcessingDrainReceipt drain,
            TimelineEntry entry,
            boolean gasOnly) {
        ArrayList<String> values = new ArrayList<>();
        for (ContractsClosurePublicationReceipt receipt : receipts) {
            ClosureProcessResult result = receipt.attempt().processResult();
            values.add(gasOnly
                    ? exactGasTraceProjection(result)
                    : receipt.publicationIdentity()
                            + '|' + receipt.documentIds()
                            + '|' + exactSemanticProjection(result));
        }
        if (gasOnly) {
            return values.toString();
        }
        return entry.blueId() + '|' + drain.quiescent() + '|'
                + drain.committedProcessTransitions() + '|'
                + drain.outcomesFor(entry.blueId()).stream()
                        .map(outcome -> outcome.documentId().value())
                        .toList()
                + '|' + partition + '|' + values;
    }

    private static String exactSemanticProjection(ClosureProcessResult result) {
        ArrayList<String> documents = new ArrayList<>();
        for (ResultingDocument document : result.resultingDocuments()) {
            documents.add(document.documentId().value()
                    + '|' + document.beforeBlueId()
                    + '|' + document.afterBlueId()
                    + '|' + NodeWireForm.get(document.document())
                    + '|' + document.initialized()
                    + '|' + document.terminated()
                    + '|' + document.publicRoot()
                    + '|' + document.epoch()
                    + '|' + document.componentGeneration()
                    + '|' + document.componentIdentity()
                    + '|' + document.componentStateIdentity()
                    + '|' + document.memberIndex());
        }
        ArrayList<String> components = new ArrayList<>();
        for (ComponentSnapshot component : result.resultingComponents()) {
            components.add(component.componentIdentity()
                    + '|' + component.componentStateIdentity()
                    + '|' + component.componentGeneration()
                    + '|' + component.kind()
                    + '|' + component.orderedMemberDocumentIds()
                    + '|' + component.orderedMemberBlueIds()
                    + '|' + component.masterBlueId()
                    + '|' + component.cyclicProofIdentity());
        }
        return result.status()
                + "|commits=" + result.commits()
                + "|atomic=" + result.atomic()
                + "|invocation=" + result.invocationIdentity()
                + "|input=" + result.inputClosureIdentity()
                + "|output=" + result.outputClosureIdentity()
                + "|generation=" + result.graphGeneration()
                + "|documents=" + documents
                + "|components=" + components
                + "|occurrences=" + result.occurrenceBindingSetIdentity()
                + "|graphChanges=" + result.graphChangesIdentity()
                + "|subscriptions=" + result.subscriptionDeltasIdentity()
                + "|checkpoints=" + result.checkpointWritesIdentity()
                + "|publicEvents=" + result.publicEventsIdentity()
                + "|companion=" + (result.platformCommitCompanion() == null
                        ? null
                        : result.platformCommitCompanion()
                                .companionIdentity());
    }

    private static String exactGasTraceProjection(ClosureProcessResult result) {
        ArrayList<String> entries = new ArrayList<>();
        for (GasTraceEntry entry : result.gasTrace()) {
            entries.add(entry.sequence()
                    + "|" + entry.namespace().wireValue()
                    + "|" + entry.counter()
                    + "|" + entry.quantity()
                    + "|" + entry.weight()
                    + "|" + entry.subtotal()
                    + "|" + nullableDocument(entry.documentId())
                    + "|" + entry.scopePath()
                    + "|" + entry.activationGeneration()
                    + "|" + entry.componentGeneration()
                    + "|" + entry.contractKey()
                    + "|" + entry.logicalPath()
                    + "|" + entry.workOccurrenceId()
                    + "|" + entry.reason());
        }
        return result.totalGas() + "|" + result.gasTraceIdentity()
                + "|" + entries;
    }

    private static String nullableDocument(
            blue.language.processor.closure.DocumentId documentId) {
        return documentId == null ? null : documentId.value();
    }

    private static String bexObservableProjection(
            List<ContractsClosurePublicationReceipt> receipts) {
        ArrayList<String> values = new ArrayList<>();
        for (ContractsClosurePublicationReceipt receipt : receipts) {
            ClosureProcessResult result = receipt.attempt().processResult();
            values.add(result.status()
                    + "|" + result.outputClosureIdentity()
                    + "|" + result.resultingDocuments().stream()
                            .map(document -> document.documentId().value()
                                    + "=" + document.afterBlueId())
                            .toList()
                    + "|" + result.publicEventsIdentity()
                    + "|" + result.gasTraceIdentity()
                    + "|" + result.totalGas());
        }
        return values.toString();
    }

    private static List<String> dequeueWorkIds(
            List<ContractsClosurePublicationReceipt> receipts) {
        ArrayList<String> result = new ArrayList<>();
        for (ContractsClosurePublicationReceipt receipt : receipts) {
            for (GasTraceEntry entry
                    : receipt.attempt().processResult().gasTrace()) {
                if ("closureWorkOccurrenceDequeued".equals(entry.counter())) {
                    result.add(entry.workOccurrenceId());
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<List<String>> partition(
            List<ComponentSnapshot> components) {
        ArrayList<List<String>> result = new ArrayList<>();
        for (ComponentSnapshot component : components) {
            result.add(component.orderedMemberDocumentIds().stream()
                    .map(blue.language.processor.closure.DocumentId::value)
                    .sorted()
                    .toList());
        }
        result.sort(Comparator.comparing(Object::toString));
        return List.copyOf(result);
    }

    private static List<List<String>> partitionIds(
            List<List<DocumentId>> components) {
        ArrayList<List<String>> result = new ArrayList<>();
        for (List<DocumentId> component : components) {
            result.add(component.stream()
                    .map(DocumentId::value)
                    .sorted()
                    .toList());
        }
        result.sort(Comparator.comparing(Object::toString));
        return List.copyOf(result);
    }

    private static Gate exactGate(String id, long actual, long expected) {
        return new Gate(
                id,
                actual == expected ? GateStatus.PASS : GateStatus.FAIL,
                true,
                actual,
                expected,
                "expected exact equality");
    }

    private static Gate booleanGate(
            String id,
            boolean passed,
            String detail) {
        return new Gate(
                id,
                passed ? GateStatus.PASS : GateStatus.FAIL,
                true,
                passed,
                true,
                detail);
    }

    private static List<Gate> campaignGates(
            List<ShapeRun> shapes,
            boolean authoritative,
            List<String> authorityReasons,
            BaselineBinding baseline,
            boolean defaultIterationCounts) {
        ArrayList<Gate> gates = new ArrayList<>();
        gates.add(new Gate(
                "authoritative-reference-configuration",
                !defaultIterationCounts
                        ? GateStatus.NOT_APPLICABLE
                        : authoritative ? GateStatus.PASS : GateStatus.FAIL,
                true,
                defaultIterationCounts ? authoritative : null,
                true,
                !defaultIterationCounts
                        ? "Iteration-count overrides are smoke-only and "
                                + "cannot be authoritative."
                        : authoritative
                                ? "The 20/50 run uses the required Java 17, "
                                        + "2 GiB heap, G1, locale/timezone, "
                                        + "and frozen reference machine."
                                : "The default 20/50 run is not on the "
                                        + "authoritative reference "
                                        + "configuration: "
                                        + authorityReasons));
        gates.add(new Gate(
                "hardware-baseline-binding",
                baseline.status() == GateStatus.PASS
                        ? GateStatus.PASS
                        : defaultIterationCounts
                                ? GateStatus.FAIL
                                : GateStatus.UNOBSERVABLE,
                defaultIterationCounts,
                baseline.sha256(),
                "readable SHA-256",
                baseline.status() == GateStatus.PASS
                        ? "Runtime hardware/JVM evidence is bound to "
                                + baseline.relativePath() + "."
                        : baseline.failure()));
        ShapeRun base = shape(shapes,
                CyclicPerformanceScenarios.Shape.FIVE_MEMBER);
        ShapeRun locality = shape(shapes,
                CyclicPerformanceScenarios.Shape.FIVE_MEMBER_PLUS_1000);
        if (!authoritative) {
            gates.add(new Gate(
                    "plus-1000-warm-total-wall-overhead",
                    GateStatus.NOT_APPLICABLE,
                    true,
                    null,
                    LOCALITY_OVERHEAD_LIMIT,
                    "Non-default iteration counts make this smoke evidence "
                            + "non-authoritative."));
        } else if (base.operationWallDistribution() == null
                || locality.operationWallDistribution() == null) {
            gates.add(new Gate(
                    "plus-1000-warm-total-wall-overhead",
                    GateStatus.INCOMPLETE,
                    true,
                    null,
                    LOCALITY_OVERHEAD_LIMIT,
                    "A complete measured distribution is unavailable."));
        } else {
            long baseP95 = base.operationWallDistribution().p95();
            long localityP95 = locality.operationWallDistribution().p95();
            double overhead = baseP95 == 0L
                    ? Double.POSITIVE_INFINITY
                    : ((double) localityP95 - (double) baseP95)
                            / (double) baseP95;
            gates.add(new Gate(
                    "plus-1000-warm-total-wall-overhead",
                    overhead <= LOCALITY_OVERHEAD_LIMIT
                            ? GateStatus.PASS : GateStatus.FAIL,
                    true,
                    overhead,
                    LOCALITY_OVERHEAD_LIMIT,
                    "p95 locality end-to-end operation wall versus p95 "
                            + "five-member end-to-end operation wall"));
        }
        gates.add(pairedEqualityGate(
                "plus-1000-affected-semantic-equality",
                base,
                locality,
                IterationRun::affectedSemanticFingerprint));
        gates.add(pairedEqualityGate(
                "plus-1000-affected-gas-equality",
                base,
                locality,
                IterationRun::affectedGasFingerprint));
        gates.add(pairedEqualityGate(
                "plus-1000-observable-result-equality",
                base,
                locality,
                IterationRun::bexProjectionFingerprint));
        gates.add(new Gate(
                "raw-bex-cold-warm-equality",
                GateStatus.UNOBSERVABLE,
                true,
                null,
                null,
                "Raw BEX results are not exposed; every shape separately "
                        + "gates its exact observable BEX projection."));
        gates.add(new Gate(
                "implementation-conformance-claim",
                GateStatus.NOT_APPLICABLE,
                false,
                false,
                false,
                "Campaign-local gates cannot promote the global "
                        + "implementation-conformance claim; the required "
                        + "staged/published exact-package lane is disabled "
                        + "by policy."));
        return List.copyOf(gates);
    }

    private static ShapeRun shape(
            List<ShapeRun> shapes,
            CyclicPerformanceScenarios.Shape selected) {
        return shapes.stream()
                .filter(shape -> shape.shape() == selected)
                .findFirst()
                .orElseThrow();
    }

    private static GateStatus overallStatus(
            List<ShapeRun> shapes,
            List<Gate> campaignGates) {
        List<Gate> gates = allGates(shapes, campaignGates);
        if (gates.stream().anyMatch(gate -> gate.hard()
                && gate.status() == GateStatus.FAIL)) {
            return GateStatus.FAIL;
        }
        if (gates.stream().anyMatch(gate -> gate.hard()
                && (gate.status() == GateStatus.INCOMPLETE
                        || gate.status() == GateStatus.UNOBSERVABLE))) {
            return GateStatus.INCOMPLETE;
        }
        return GateStatus.PASS;
    }

    private static List<Gate> allGates(
            List<ShapeRun> shapes,
            List<Gate> campaignGates) {
        ArrayList<Gate> gates = new ArrayList<>(campaignGates);
        shapes.forEach(shape -> gates.addAll(shape.gates()));
        shapes.stream()
                .flatMap(shape -> shape.allIterations().stream())
                .flatMap(iteration -> iteration.operations().stream())
                .forEach(operation -> gates.addAll(operation.gates()));
        return List.copyOf(gates);
    }

    private static List<Map<String, Object>> observedBlockers(
            List<ShapeRun> shapes,
            List<Gate> campaignGates) {
        LinkedHashMap<String, Gate> unique = new LinkedHashMap<>();
        for (Gate gate : allGates(shapes, campaignGates)) {
            if (gate.hard()
                    && gate.status() != GateStatus.PASS
                    && gate.status() != GateStatus.NOT_APPLICABLE
                    && !gate.id().equals(
                            "implementation-conformance-claim")) {
                unique.putIfAbsent(gate.id(), gate);
            }
        }
        return unique.values().stream().map(Gate::toMap).toList();
    }

    private static Map<String, Object> report(
            int warmups,
            int samples,
            RuntimeIdentity runtime,
            BaselineBinding baseline,
            boolean authoritative,
            List<String> authorityReasons,
            List<ShapeRun> shapes,
            List<Gate> campaignGates,
            GateStatus overall) {
        return map(
                "schema", "blue.coordination/cyclic-performance/v1",
                "generatedAt", Instant.now().toString(),
                "overallStatus", overall,
                "authoritative", authoritative,
                "implementationConformanceClaimed",
                false,
                "frozenInputs", map(
                        "languageSpecification",
                        CyclicPerformanceScenarios.LANGUAGE_SPEC,
                        "contractsSpecification",
                        CyclicPerformanceScenarios.CONTRACTS_SPEC,
                        "contractsReleaseIdentity",
                        CyclicPerformanceScenarios.CONTRACTS_RELEASE),
                "configuration", map(
                        "warmupsPerShape", warmups,
                        "measuredSamplesPerShape", samples,
                        "defaultWarmups", DEFAULT_WARMUPS,
                        "defaultMeasuredSamples", DEFAULT_SAMPLES,
                        "freshPublicEngineAndStatePerIteration", true,
                        "authorityReasons", authorityReasons),
                "runtime", runtime.toMap(),
                "hardwareBaseline", baseline.toMap(),
                "observability", map(
                        "rawBexResult", map(
                                "status", GateStatus.UNOBSERVABLE,
                                "hardBlocker", true,
                                "reason", "No raw BEX result fingerprint is "
                                        + "exposed at this boundary."),
                        "bexObservableProjection", map(
                                "status", GateStatus.PASS,
                                "fields", List.of(
                                        "processor status",
                                        "output closure identity",
                                        "resulting document BlueIds",
                                        "public event sequence identity",
                                        "gas trace identity and total")),
                        "phaseCatalog", reportedPhases(),
                        "releaseAndLocalityWallBasis",
                        "warm measured operationWallNanos (append + drain)",
                        "hostResidualFormula", "drain.reported - "
                                + String.join(" - ", HOST_RESIDUAL_PHASES),
                        "nestedLanguagePhasesDoubleSubtracted", false),
                "knownBlockers", observedBlockers(shapes, campaignGates),
                "campaignGates", campaignGates.stream()
                        .map(Gate::toMap).toList(),
                "shapes", shapes.stream().map(ShapeRun::toMap).toList());
    }

    private static String markdown(
            Map<String, Object> report,
            BaselineBinding baseline,
            RuntimeIdentity runtime,
            List<ShapeRun> shapes,
            List<Gate> campaignGates,
            GateStatus overall) {
        StringBuilder text = new StringBuilder();
        text.append("# Cyclic performance acceptance\n\n")
                .append("- Overall: **").append(overall).append("**\n")
                .append("- Authoritative: **")
                .append(report.get("authoritative")).append("**\n")
                .append("- Implementation conformance claimed: **")
                .append(report.get("implementationConformanceClaimed"))
                .append("**\n")
                .append("- Hardware baseline: `")
                .append(baseline.relativePath()).append("` (`")
                .append(baseline.sha256()).append("`, ")
                .append(baseline.status()).append(")\n")
                .append("- Generated: ").append(report.get("generatedAt"))
                .append("\n\n")
                .append("## Frozen inputs\n\n")
                .append("- Language specification: `")
                .append(CyclicPerformanceScenarios.LANGUAGE_SPEC)
                .append("`\n- Contracts specification: `")
                .append(CyclicPerformanceScenarios.CONTRACTS_SPEC)
                .append("`\n- Contracts release: `")
                .append(CyclicPerformanceScenarios.CONTRACTS_RELEASE)
                .append("`\n\n")
                .append("## Hardware and JVM identity\n\n")
                .append("- Runtime OS: ").append(runtime.osName())
                .append(' ').append(runtime.osVersion()).append(" (`")
                .append(runtime.osArchitecture()).append("`)\n")
                .append("- Runtime JVM: ").append(runtime.javaVendor())
                .append(' ').append(runtime.javaVersion()).append(" (`")
                .append(runtime.vmName()).append("`)\n")
                .append("- Runtime processors / max heap: ")
                .append(runtime.availableProcessors()).append(" / ")
                .append(runtime.maxHeapBytes()).append(" bytes\n")
                .append("- JVM arguments: `")
                .append(runtime.inputArguments()).append("`\n")
                .append("- Baseline comparison: **")
                .append(baseline.status()).append("**; mismatches: `")
                .append(baseline.mismatches()).append("`\n")
                .append("- Actual hardware: ")
                .append(baseline.actual() == null
                        ? "UNOBSERVABLE"
                        : baseline.actual().modelName() + " "
                                + baseline.actual().modelIdentifier() + ", "
                                + baseline.actual().chip() + ", "
                                + baseline.actual().logicalCores()
                                + " logical cores, "
                                + baseline.actual().memoryReported())
                .append("\n- Actual OS build / JDK home: ")
                .append(baseline.actual() == null
                        ? "UNOBSERVABLE"
                        : baseline.actual().osProduct() + " "
                                + baseline.actual().osVersion() + " ("
                                + baseline.actual().osBuild() + ") / "
                                + baseline.actual().jdkHome())
                .append("\n\n")
                .append("The raw BEX result is **UNOBSERVABLE** at this "
                        + "boundary. The exact observable result projection "
                        + "is compared without representing it as raw BEX "
                        + "equality.\n\n")
                .append("## Shape results\n\n")
                .append("| Shape | Measured | Setup p95 | Admission p95 | "
                        + "Process p50 | Process p95 | Total p50 | Total p95 | "
                        + "Release gate | Semantic | Gas | BEX projection |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|\n");
        for (ShapeRun shape : shapes) {
            Distribution distribution = shape.processWallDistribution();
            Distribution total = shape.operationWallDistribution();
            text.append('|').append(shape.shape().id())
                    .append('|').append(shape.measured().size())
                    .append('|').append(shape.setupDistribution() == null
                            ? "n/a" : shape.setupDistribution().p95())
                    .append('|').append(shape.admissionDistribution() == null
                            ? "n/a" : shape.admissionDistribution().p95())
                    .append('|').append(distribution == null
                            ? "n/a" : distribution.p50())
                    .append('|').append(distribution == null
                            ? "n/a" : distribution.p95())
                    .append('|').append(total == null
                            ? "n/a" : total.p50())
                    .append('|').append(total == null
                            ? "n/a" : total.p95())
                    .append('|').append(shape.releaseGate().status())
                    .append('|').append(shape.semanticEquality().status())
                    .append('|').append(shape.gasEquality().status())
                    .append('|').append(shape.bexProjectionEquality().status())
                    .append("|\n");
        }
        text.append("\n## Phase distributions\n\n");
        for (ShapeRun shape : shapes) {
            text.append("### ").append(shape.shape().id()).append("\n\n")
                    .append("| Phase | Status | p50 | p95 | max |\n")
                    .append("|---|---|---:|---:|---:|\n");
            for (String phase : reportedPhases()) {
                Map<String, Object> evidence = object(
                        shape.phaseDistributions().get(phase),
                        shape.shape().id() + " phase " + phase);
                Object status = evidence.get("status");
                Map<String, Object> distribution =
                        evidence.get("distribution") == null
                                ? null
                                : object(evidence.get("distribution"),
                                        shape.shape().id()
                                                + " distribution " + phase);
                text.append('|').append(phase).append('|')
                        .append(status)
                        .append('|').append(distribution == null
                                ? "n/a" : distribution.get("p50"))
                        .append('|').append(distribution == null
                                ? "n/a" : distribution.get("p95"))
                        .append('|').append(distribution == null
                                ? "n/a" : distribution.get("max"))
                        .append("|\n");
            }
            text.append('\n');
        }
        text.append("\n## Campaign gates\n\n");
        for (Gate gate : campaignGates) {
            text.append("- **").append(gate.status()).append("** `")
                    .append(gate.id()).append("`: ")
                    .append(gate.detail()).append('\n');
        }
        text.append("\n## Observed blockers\n\n");
        List<Map<String, Object>> blockers = observedBlockers(
                shapes, campaignGates);
        if (blockers.isEmpty()) {
            text.append("No hard blocker was observed.\n");
        } else {
            for (Map<String, Object> blocker : blockers) {
                text.append("- **").append(blocker.get("status"))
                        .append("** `").append(blocker.get("id"))
                        .append("`: ").append(blocker.get("detail"))
                        .append('\n');
            }
        }
        text.append("\nRaw samples, phase observability, counters, exact "
                + "fingerprints, machine/JVM identity, and every gate "
                + "are retained in `cyclic-performance.json`.\n");
        return text.toString();
    }

    private static int integerProperty(
            String name,
            int fallback,
            int minimum) {
        String raw = System.getProperty(name);
        if (raw == null) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    name + " must be an integer", failure);
        }
        if (value < minimum) {
            throw new IllegalArgumentException(
                    name + " must be >= " + minimum);
        }
        return value;
    }

    private static List<String> authorityReasons(
            int warmups,
            int samples,
            RuntimeIdentity runtime,
            BaselineBinding baseline) {
        ArrayList<String> reasons = new ArrayList<>();
        if (warmups != DEFAULT_WARMUPS || samples != DEFAULT_SAMPLES) {
            reasons.add("Iteration-count override: authoritative evidence "
                    + "requires exactly 20 warmups and 50 measured samples.");
        }
        if (Runtime.version().feature() != 17) {
            reasons.add("The campaign JVM is not Java 17.");
        }
        if (!runtime.garbageCollectors().stream()
                .anyMatch(name -> name.contains("G1"))) {
            reasons.add("The campaign JVM is not using G1 GC.");
        }
        List<String> arguments = runtime.inputArguments();
        if (!arguments.contains("-Xms2g") || !arguments.contains("-Xmx2g")) {
            reasons.add("The campaign JVM does not declare both -Xms2g and "
                    + "-Xmx2g.");
        }
        if (runtime.maxHeapBytes() != EXPECTED_MAX_HEAP_BYTES) {
            reasons.add("The campaign JVM effective max heap is "
                    + runtime.maxHeapBytes() + " bytes; authoritative "
                    + "evidence requires exactly " + EXPECTED_MAX_HEAP_BYTES
                    + " bytes (2 GiB).");
        }
        if (!arguments.contains("-Duser.language=en")
                || !arguments.contains("-Duser.country=US")
                || !arguments.contains("-Duser.timezone=UTC")) {
            reasons.add("The campaign JVM does not declare the fixed "
                    + "en-US/UTC locale and timezone.");
        }
        if (!"en".equals(runtime.userLanguage())
                || !"US".equals(runtime.userCountry())
                || !"UTC".equals(runtime.userTimezone())) {
            reasons.add("The campaign JVM did not apply the fixed "
                    + "en-US/UTC locale and timezone.");
        }
        if (baseline.status() != GateStatus.PASS) {
            reasons.add("The runtime did not match a readable frozen hardware "
                    + "baseline: " + baseline.failure() + " "
                    + baseline.mismatches());
        }
        return List.copyOf(reasons);
    }

    private static String digest(String value) {
        return "sha256:" + sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void writeAtomically(Path target, String value)
            throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(
                target.getParent(), target.getFileName().toString(), ".tmp");
        Files.writeString(temporary, value, StandardCharsets.UTF_8);
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static LinkedHashMap<String, Object> map(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("map requires key/value pairs");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private enum GateStatus {
        PASS,
        FAIL,
        INCOMPLETE,
        NOT_APPLICABLE,
        UNOBSERVABLE
    }

    private record Gate(
            String id,
            GateStatus status,
            boolean hard,
            Object observed,
            Object limit,
            String detail) {
        private Gate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
        }

        private Map<String, Object> toMap() {
            return map(
                    "id", id,
                    "status", status,
                    "hard", hard,
                    "observed", observed,
                    "limit", limit,
                    "detail", detail);
        }
    }

    private record PhaseValue(GateStatus status, Long nanos) {
        private static PhaseValue observed(long nanos) {
            return new PhaseValue(GateStatus.PASS, nanos);
        }

        private static PhaseValue unobservable() {
            return new PhaseValue(GateStatus.UNOBSERVABLE, null);
        }

        private Map<String, Object> toMap() {
            return map("status", status, "nanos", nanos);
        }
    }

    private record OperationRun(
            String id,
            String entryBlueId,
            long operationWallNanos,
            long processWallNanos,
            Map<String, Long> counters,
            Map<String, PhaseValue> phases,
            List<List<String>> resultingPartition,
            int processReceiptCount,
            long expectedDirectSeeds,
            long expectedAcceptedWork,
            String semanticFingerprint,
            String gasFingerprint,
            String bexProjectionFingerprint,
            List<Gate> gates) {
        private OperationRun {
            counters = Map.copyOf(counters);
            phases = Map.copyOf(phases);
            resultingPartition = List.copyOf(resultingPartition);
            gates = List.copyOf(gates);
        }

        private Map<String, Object> toMap() {
            LinkedHashMap<String, Object> phaseMap = new LinkedHashMap<>();
            phases.forEach((name, value) -> phaseMap.put(
                    name, value.toMap()));
            return map(
                    "id", id,
                    "entryBlueId", entryBlueId,
                    "operationWallNanos", operationWallNanos,
                    "processWallNanos", processWallNanos,
                    "counters", counters,
                    "phases", phaseMap,
                    "resultingPartition", resultingPartition,
                    "processReceiptCount", processReceiptCount,
                    "expectedDirectSeeds", expectedDirectSeeds,
                    "expectedAcceptedWork", expectedAcceptedWork,
                    "semanticFingerprint", semanticFingerprint,
                    "gasFingerprint", gasFingerprint,
                    "bexObservableProjectionFingerprint",
                    bexProjectionFingerprint,
                    "rawBexFingerprint", null,
                    "rawBexFingerprintStatus", GateStatus.UNOBSERVABLE,
                    "rawBexFingerprintHardBlocker", true,
                    "gates", gates.stream().map(Gate::toMap).toList());
        }
    }

    private record IterationRun(
            String role,
            int index,
            boolean completed,
            Long engineConstructionNanos,
            Long admissionNanos,
            int admissionReceiptCount,
            int unrelatedDocumentCount,
            String semanticFingerprint,
            String gasFingerprint,
            String affectedSemanticFingerprint,
            String affectedGasFingerprint,
            String bexProjectionFingerprint,
            List<OperationRun> operations,
            String failure) {
        private IterationRun {
            operations = List.copyOf(operations);
        }

        private long processWallNanos() {
            return operations.stream()
                    .mapToLong(OperationRun::processWallNanos)
                    .sum();
        }

        private long operationWallNanos() {
            return operations.stream()
                    .mapToLong(OperationRun::operationWallNanos)
                    .sum();
        }

        private Long phaseTotal(String phase) {
            if (!completed || operations.isEmpty()) {
                return null;
            }
            long result = 0L;
            for (OperationRun operation : operations) {
                PhaseValue value = operation.phases().get(phase);
                if (value == null || value.status() != GateStatus.PASS) {
                    return null;
                }
                result = Math.addExact(result, value.nanos());
            }
            return result;
        }

        private Map<String, Object> toMap() {
            return map(
                    "role", role,
                    "index", index,
                    "completed", completed,
                    "engineConstructionNanos", engineConstructionNanos,
                    "admissionNanos", admissionNanos,
                    "admissionReceiptCount", admissionReceiptCount,
                    "unrelatedDocumentCount", unrelatedDocumentCount,
                    "semanticFingerprint", semanticFingerprint,
                    "gasFingerprint", gasFingerprint,
                    "affectedSemanticFingerprint",
                    affectedSemanticFingerprint,
                    "affectedGasFingerprint", affectedGasFingerprint,
                    "bexObservableProjectionFingerprint",
                    bexProjectionFingerprint,
                    "processWallNanos", completed
                            ? processWallNanos() : null,
                    "operationWallNanos", completed
                            ? operationWallNanos() : null,
                    "operations", operations.stream()
                            .map(OperationRun::toMap).toList(),
                    "failure", failure);
        }
    }

    private record ShapeRun(
            CyclicPerformanceScenarios.Shape shape,
            int expectedWarmups,
            int expectedSamples,
            List<IterationRun> warmups,
            List<IterationRun> measured,
            IterationRun coldReference,
            Distribution engineConstructionDistribution,
            Distribution admissionDistribution,
            Distribution setupDistribution,
            Distribution processWallDistribution,
            Distribution operationWallDistribution,
            Map<String, Object> phaseDistributions,
            Gate semanticEquality,
            Gate gasEquality,
            Gate bexProjectionEquality,
            Gate releaseGate,
            Gate aspirationalGate,
            List<Gate> gates) {
        private static ShapeRun create(
                CyclicPerformanceScenarios.Shape shape,
                int expectedWarmups,
                int expectedSamples,
                List<IterationRun> warmups,
                List<IterationRun> measured,
                boolean authoritative) {
            IterationRun cold = !warmups.isEmpty()
                    ? warmups.get(0)
                    : (measured.isEmpty() ? null : measured.get(0));
            boolean complete = warmups.size() == expectedWarmups
                    && measured.size() == expectedSamples
                    && warmups.stream().allMatch(IterationRun::completed)
                    && measured.stream().allMatch(IterationRun::completed);
            Gate completeness = new Gate(
                    "complete-iteration-counts",
                    complete ? GateStatus.PASS : GateStatus.INCOMPLETE,
                    true,
                    map("warmups", warmups.size(),
                            "measured", measured.size()),
                    map("warmups", expectedWarmups,
                            "measured", expectedSamples),
                    "Every configured iteration must use a fresh engine and "
                            + "complete all operations.");
            Gate semantic = equalityGate(
                    "exact-contracts-semantic-cold-warm-equality",
                    cold,
                    all(warmups, measured),
                    IterationRun::semanticFingerprint,
                    complete);
            Gate gas = equalityGate(
                    "exact-contracts-gas-cold-warm-equality",
                    cold,
                    all(warmups, measured),
                    IterationRun::gasFingerprint,
                    complete);
            Gate bex = equalityGate(
                    "exact-observable-bex-projection-cold-warm-equality",
                    cold,
                    all(warmups, measured),
                    IterationRun::bexProjectionFingerprint,
                    complete);
            Distribution processDistribution = complete
                    ? Distribution.of(measured.stream()
                            .mapToLong(IterationRun::processWallNanos)
                            .boxed().toList())
                    : null;
            Distribution operationDistribution = complete
                    ? Distribution.of(measured.stream()
                            .mapToLong(IterationRun::operationWallNanos)
                            .boxed().toList())
                    : null;
            Distribution engineDistribution = complete
                    ? Distribution.of(measured.stream()
                            .map(IterationRun::engineConstructionNanos)
                            .toList())
                    : null;
            Distribution admissionDistribution = complete
                    ? Distribution.of(measured.stream()
                            .map(IterationRun::admissionNanos)
                            .toList())
                    : null;
            Distribution setupDistribution = complete
                    ? Distribution.of(measured.stream()
                            .map(iteration -> Math.addExact(
                                    iteration.engineConstructionNanos(),
                                    iteration.admissionNanos()))
                            .toList())
                    : null;
            Gate engineObservability = distributionObservabilityGate(
                    "required-phase-engine-construction",
                    engineDistribution);
            Gate admissionObservability = distributionObservabilityGate(
                    "required-phase-admission",
                    admissionDistribution);
            Gate setupObservability = distributionObservabilityGate(
                    "required-phase-setup",
                    setupDistribution);
            Map<String, Object> phaseDistributions =
                    CyclicPerformanceAcceptance.phaseDistributions(
                            measured, complete);
            Gate release = latencyGate(
                    "release-warm-total-wall-p95",
                    shape.releaseTargetNanos(),
                    operationDistribution,
                    authoritative,
                    true);
            Gate aspirational = latencyGate(
                    "aspirational-warm-total-wall-p95",
                    shape.aspirationalTargetNanos(),
                    operationDistribution,
                    authoritative,
                    false);
            Gate host = hostDistributionGate(
                    measured, complete, authoritative);
            return new ShapeRun(
                    shape,
                    expectedWarmups,
                    expectedSamples,
                    List.copyOf(warmups),
                    List.copyOf(measured),
                    cold,
                    engineDistribution,
                    admissionDistribution,
                    setupDistribution,
                    processDistribution,
                    operationDistribution,
                    phaseDistributions,
                    semantic,
                    gas,
                    bex,
                    release,
                    aspirational,
                    List.of(
                            completeness,
                            engineObservability,
                            admissionObservability,
                            setupObservability,
                            semantic,
                            gas,
                            bex,
                            release,
                            aspirational,
                            host));
        }

        private List<IterationRun> allIterations() {
            return all(warmups, measured);
        }

        private Map<String, Object> toMap() {
            return map(
                    "id", shape.id(),
                    "graph", shape.graph(),
                    "expectedWarmups", expectedWarmups,
                    "expectedMeasuredSamples", expectedSamples,
                    "releaseTargetNanos", shape.releaseTargetNanos(),
                    "releaseTargetBasis",
                    "warm measured operationWallNanos (append + drain)",
                    "aspirationalTargetNanos",
                    shape.aspirationalTargetNanos(),
                    "coldReference", coldReference == null ? null : map(
                            "role", coldReference.role(),
                            "index", coldReference.index()),
                    "engineConstructionDistribution",
                    engineConstructionDistribution == null
                            ? null : engineConstructionDistribution.toMap(),
                    "admissionDistribution", admissionDistribution == null
                            ? null : admissionDistribution.toMap(),
                    "setupDistribution", setupDistribution == null
                            ? null : setupDistribution.toMap(),
                    "setupDistributionFormula",
                    "engineConstructionNanos + admissionNanos",
                    "processWallDistribution", processWallDistribution == null
                            ? null : processWallDistribution.toMap(),
                    "operationWallDistribution",
                    operationWallDistribution == null
                            ? null : operationWallDistribution.toMap(),
                    "phaseDistributions", phaseDistributions,
                    "gates", gates.stream().map(Gate::toMap).toList(),
                    "warmups", warmups.stream()
                            .map(IterationRun::toMap).toList(),
                    "measured", measured.stream()
                            .map(IterationRun::toMap).toList());
        }
    }

    private interface Fingerprint {
        String get(IterationRun iteration);
    }

    private static Gate pairedEqualityGate(
            String id,
            ShapeRun base,
            ShapeRun locality,
            Fingerprint fingerprint) {
        List<IterationRun> baseIterations = base.allIterations();
        List<IterationRun> localityIterations = locality.allIterations();
        if (baseIterations.isEmpty()
                || baseIterations.size() != localityIterations.size()
                || baseIterations.stream().anyMatch(
                        iteration -> !iteration.completed())
                || localityIterations.stream().anyMatch(
                        iteration -> !iteration.completed())) {
            return new Gate(
                    id,
                    GateStatus.INCOMPLETE,
                    true,
                    map("base", baseIterations.size(),
                            "plus1000", localityIterations.size()),
                    "same non-zero completed iteration count",
                    "The controlled five-member pair requires corresponding "
                            + "fresh-engine iterations.");
        }
        boolean equal = true;
        for (int index = 0; index < baseIterations.size(); index++) {
            IterationRun left = baseIterations.get(index);
            IterationRun right = localityIterations.get(index);
            if (!left.role().equals(right.role())
                    || left.index() != right.index()
                    || !Objects.equals(
                            fingerprint.get(left), fingerprint.get(right))) {
                equal = false;
                break;
            }
        }
        return new Gate(
                id,
                equal ? GateStatus.PASS : GateStatus.FAIL,
                true,
                equal,
                true,
                "Corresponding iterations use identical affected IDs, "
                        + "timeline, timestamp, operation, and closure; only "
                        + "the 1,000 unrelated documents differ.");
    }

    private static Gate equalityGate(
            String id,
            IterationRun cold,
            List<IterationRun> iterations,
            Fingerprint fingerprint,
            boolean complete) {
        if (!complete || cold == null || !cold.completed()) {
            return new Gate(
                    id,
                    GateStatus.INCOMPLETE,
                    true,
                    null,
                    "exact equality",
                    "A complete cold reference and every configured "
                            + "iteration are required.");
        }
        if (iterations.size() < 2) {
            return new Gate(
                    id,
                    GateStatus.UNOBSERVABLE,
                    true,
                    iterations.size(),
                    ">= 2 distinct iterations",
                    "Cold/warm equality cannot be established by comparing "
                            + "one iteration with itself.");
        }
        String reference = fingerprint.get(cold);
        boolean equal = iterations.stream()
                .allMatch(iteration -> Objects.equals(
                        reference, fingerprint.get(iteration)));
        return new Gate(
                id,
                equal ? GateStatus.PASS : GateStatus.FAIL,
                true,
                equal,
                true,
                "cold reference=" + cold.role() + '[' + cold.index() + ']');
    }

    private static List<IterationRun> all(
            List<IterationRun> warmups,
            List<IterationRun> measured) {
        ArrayList<IterationRun> result = new ArrayList<>(warmups);
        result.addAll(measured);
        return List.copyOf(result);
    }

    private static Gate latencyGate(
            String id,
            Long target,
            Distribution distribution,
            boolean authoritative,
            boolean hard) {
        if (target == null) {
            return new Gate(
                    id,
                    GateStatus.NOT_APPLICABLE,
                    hard,
                    null,
                    null,
                    "This shape has no independent wall target.");
        }
        if (!authoritative) {
            return new Gate(
                    id,
                    GateStatus.NOT_APPLICABLE,
                    hard,
                    distribution == null ? null : distribution.p95(),
                    target,
                    "Non-default iteration counts make this smoke evidence "
                            + "non-authoritative.");
        }
        if (distribution == null) {
            return new Gate(
                    id,
                    GateStatus.INCOMPLETE,
                    hard,
                    null,
                    target,
                    "The measured distribution is incomplete.");
        }
        return new Gate(
                id,
                distribution.p95() <= target
                        ? GateStatus.PASS : GateStatus.FAIL,
                hard,
                distribution.p95(),
                target,
                "nearest-rank warm measured p95 end-to-end operation wall "
                        + "(append plus drain)");
    }

    private static Gate distributionObservabilityGate(
            String id,
            Distribution distribution) {
        boolean observed = distribution != null
                && distribution.count() > 0
                && distribution.min() > 0L;
        return new Gate(
                id,
                observed ? GateStatus.PASS : GateStatus.UNOBSERVABLE,
                true,
                distribution == null ? null : distribution.toMap(),
                "positive measured distribution",
                observed
                        ? "Every measured iteration emitted this setup phase."
                        : "A required setup/admission phase distribution is "
                                + "unavailable.");
    }

    private static Gate hostDistributionGate(
            List<IterationRun> measured,
            boolean complete,
            boolean authoritative) {
        if (!authoritative) {
            return new Gate(
                    "host-overhead-p95",
                    GateStatus.NOT_APPLICABLE,
                    true,
                    null,
                    HOST_OVERHEAD_LIMIT_NANOS,
                    "Non-default iteration counts make this smoke evidence "
                            + "non-authoritative.");
        }
        if (!complete) {
            return new Gate(
                    "host-overhead-p95",
                    GateStatus.INCOMPLETE,
                    true,
                    null,
                    HOST_OVERHEAD_LIMIT_NANOS,
                    "The measured distribution is incomplete.");
        }
        ArrayList<Long> values = new ArrayList<>();
        for (IterationRun iteration : measured) {
            Long value = iteration.phaseTotal("host.residual");
            if (value == null || value.longValue() < 0L) {
                return new Gate(
                        "host-overhead-p95",
                        GateStatus.UNOBSERVABLE,
                        true,
                        null,
                        HOST_OVERHEAD_LIMIT_NANOS,
                        "At least one sample lacks a valid host residual.");
            }
            values.add(value);
        }
        Distribution distribution = Distribution.of(values);
        return new Gate(
                "host-overhead-p95",
                distribution.p95() <= HOST_OVERHEAD_LIMIT_NANOS
                        ? GateStatus.PASS : GateStatus.FAIL,
                true,
                distribution.p95(),
                HOST_OVERHEAD_LIMIT_NANOS,
                "nearest-rank measured p95 host residual");
    }

    private static Map<String, Object> phaseDistributions(
            List<IterationRun> measured,
            boolean complete) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (String phase : reportedPhases()) {
            if (!complete) {
                result.put(phase, map(
                        "status", GateStatus.INCOMPLETE,
                        "distribution", null));
                continue;
            }
            ArrayList<Long> values = new ArrayList<>();
            boolean observed = true;
            for (IterationRun iteration : measured) {
                Long value = iteration.phaseTotal(phase);
                if (value == null) {
                    observed = false;
                    break;
                }
                values.add(value);
            }
            result.put(phase, observed
                    ? map("status", GateStatus.PASS,
                            "distribution", Distribution.of(values).toMap())
                    : map("status", GateStatus.UNOBSERVABLE,
                            "distribution", null));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> reportedPhases() {
        ArrayList<String> phases = new ArrayList<>();
        phases.add("operation.wall");
        phases.add("append.wall");
        phases.add("drain.wall");
        phases.add("drain.reported");
        phases.addAll(RAW_PHASES);
        phases.add("host.residual");
        return List.copyOf(phases);
    }

    private record Distribution(
            int count,
            long min,
            long p50,
            long p95,
            long max,
            double mean) {
        private static Distribution of(List<Long> supplied) {
            if (supplied.isEmpty()) {
                throw new IllegalArgumentException(
                        "A distribution requires at least one value");
            }
            ArrayList<Long> values = new ArrayList<>(supplied);
            values.sort(Long::compareTo);
            long total = 0L;
            for (long value : values) {
                if (value < 0L) {
                    throw new IllegalArgumentException(
                            "Timing values must be non-negative");
                }
                total = Math.addExact(total, value);
            }
            return new Distribution(
                    values.size(),
                    values.get(0),
                    percentile(values, 0.50d),
                    percentile(values, 0.95d),
                    values.get(values.size() - 1),
                    (double) total / (double) values.size());
        }

        private static long percentile(List<Long> values, double percentile) {
            int rank = (int) Math.ceil(percentile * values.size());
            return values.get(Math.max(0, rank - 1));
        }

        private Map<String, Object> toMap() {
            return map(
                    "count", count,
                    "min", min,
                    "p50", p50,
                    "p95", p95,
                    "max", max,
                    "mean", mean,
                    "method", "nearest-rank");
        }
    }

    private record RuntimeIdentity(
            String javaVersion,
            String javaVendor,
            String vmName,
            String vmVersion,
            List<String> inputArguments,
            List<String> garbageCollectors,
            String osName,
            String osVersion,
            String osArchitecture,
            int availableProcessors,
            long maxHeapBytes,
            long totalMemoryBytes,
            String userLanguage,
            String userCountry,
            String userTimezone,
            String workingDirectory) {
        private static RuntimeIdentity capture() {
            List<String> collectors = ManagementFactory
                    .getGarbageCollectorMXBeans().stream()
                    .map(GarbageCollectorMXBean::getName)
                    .sorted()
                    .toList();
            Runtime runtime = Runtime.getRuntime();
            return new RuntimeIdentity(
                    System.getProperty("java.version"),
                    System.getProperty("java.vendor"),
                    System.getProperty("java.vm.name"),
                    System.getProperty("java.vm.version"),
                    List.copyOf(ManagementFactory.getRuntimeMXBean()
                            .getInputArguments()),
                    collectors,
                    System.getProperty("os.name"),
                    System.getProperty("os.version"),
                    System.getProperty("os.arch"),
                    runtime.availableProcessors(),
                    runtime.maxMemory(),
                    runtime.totalMemory(),
                    System.getProperty("user.language"),
                    System.getProperty("user.country"),
                    System.getProperty("user.timezone"),
                    Path.of("").toAbsolutePath().normalize().toString());
        }

        private Map<String, Object> toMap() {
            return map(
                    "javaVersion", javaVersion,
                    "javaVendor", javaVendor,
                    "vmName", vmName,
                    "vmVersion", vmVersion,
                    "inputArguments", inputArguments,
                    "garbageCollectors", garbageCollectors,
                    "osName", osName,
                    "osVersion", osVersion,
                    "osArchitecture", osArchitecture,
                    "availableProcessors", availableProcessors,
                    "maxHeapBytes", maxHeapBytes,
                    "initialCommittedHeapBytes", totalMemoryBytes,
                    "userLanguage", userLanguage,
                    "userCountry", userCountry,
                    "userTimezone", userTimezone,
                    "workingDirectory", workingDirectory);
        }
    }

    private record BaselineBinding(
            String relativePath,
            GateStatus status,
            String sha256,
            BaselineMachine expected,
            ActualMachine actual,
            List<String> mismatches,
            String failure) {
        private static BaselineBinding capture(
                Path relative,
                RuntimeIdentity runtime) {
            Path selected = Objects.requireNonNull(relative, "relative")
                    .normalize();
            String relativeText = selected.toString().replace('\\', '/');
            if (selected.isAbsolute() || relativeText.startsWith("../")) {
                return new BaselineBinding(
                        relativeText,
                        GateStatus.UNOBSERVABLE,
                        null,
                        null,
                        null,
                        List.of(),
                        "Hardware baseline path must remain project-relative");
            }
            Path absolute = Path.of("").toAbsolutePath().normalize()
                    .resolve(selected).normalize();
            if (!Files.isRegularFile(absolute)) {
                return new BaselineBinding(
                        relativeText,
                        GateStatus.UNOBSERVABLE,
                        null,
                        null,
                        null,
                        List.of(),
                        "Hardware baseline is absent or not a regular file");
            }
            byte[] source;
            try {
                source = Files.readAllBytes(absolute);
            } catch (IOException failure) {
                return new BaselineBinding(
                        relativeText,
                        GateStatus.UNOBSERVABLE,
                        null,
                        null,
                        null,
                        List.of(),
                        failure.getClass().getName() + ": "
                                + String.valueOf(failure.getMessage()));
            }
            String baselineSha256 = CyclicPerformanceAcceptance.sha256(source);
            try {
                BaselineMachine expected = BaselineMachine.from(
                        Json.parse(new String(source, StandardCharsets.UTF_8)));
                ActualMachine actual = ActualMachine.capture(runtime);
                List<String> mismatches = expected.mismatches(actual);
                return new BaselineBinding(
                        relativeText,
                        mismatches.isEmpty()
                                ? GateStatus.PASS : GateStatus.FAIL,
                        baselineSha256,
                        expected,
                        actual,
                        mismatches,
                        mismatches.isEmpty()
                                ? null
                                : "Runtime differs from the frozen machine "
                                        + "baseline");
            } catch (IOException | RuntimeException failure) {
                return new BaselineBinding(
                        relativeText,
                        GateStatus.UNOBSERVABLE,
                        baselineSha256,
                        null,
                        null,
                        List.of(),
                        failure.getClass().getName() + ": "
                                + String.valueOf(failure.getMessage()));
            }
        }

        private Map<String, Object> toMap() {
            return map(
                    "relativePath", relativePath,
                    "sha256", sha256,
                    "status", status,
                    "machineEvidenceJsonPointer", "$.machine",
                    "expectedMachine", expected == null
                            ? null : expected.toMap(),
                    "actualMachine", actual == null
                            ? null : actual.toMap(),
                    "mismatches", mismatches,
                    "failure", failure);
        }
    }

    private record BaselineMachine(
            String modelName,
            String modelIdentifier,
            String modelNumber,
            String chip,
            String architecture,
            long logicalCores,
            String memoryReported,
            String osProduct,
            String osVersion,
            String osBuild,
            String jdkVersion,
            String jdkArchitecture,
            String jdkVendor,
            String jdkHome) {
        private static BaselineMachine from(Object parsed) {
            Map<String, Object> root = object(parsed, "baseline root");
            Map<String, Object> machine = object(
                    root.get("machine"), "$.machine");
            Map<String, Object> cores = object(
                    machine.get("cores"), "$.machine.cores");
            Map<String, Object> os = object(
                    machine.get("os"), "$.machine.os");
            List<Object> jdks = array(
                    machine.get("requiredComparisonJdks"),
                    "$.machine.requiredComparisonJdks");
            Map<String, Object> jdk17 = null;
            for (Object candidate : jdks) {
                Map<String, Object> jdk = object(
                        candidate, "requiredComparisonJdks entry");
                if (string(jdk, "version").startsWith("17.")) {
                    jdk17 = jdk;
                    break;
                }
            }
            if (jdk17 == null) {
                throw new IllegalArgumentException(
                        "Hardware baseline has no Java 17 comparison JDK");
            }
            return new BaselineMachine(
                    string(machine, "modelName"),
                    string(machine, "modelIdentifier"),
                    string(machine, "modelNumber"),
                    string(machine, "chip"),
                    string(machine, "architecture"),
                    integer(cores, "logicalAvailable"),
                    string(machine, "memoryReported"),
                    string(os, "product"),
                    string(os, "version"),
                    string(os, "build"),
                    string(jdk17, "version"),
                    string(jdk17, "architecture"),
                    string(jdk17, "vendor"),
                    string(jdk17, "javaHome"));
        }

        private List<String> mismatches(ActualMachine actual) {
            ArrayList<String> result = new ArrayList<>();
            mismatch(result, "modelName", modelName, actual.modelName());
            mismatch(result, "modelIdentifier", modelIdentifier,
                    actual.modelIdentifier());
            mismatch(result, "modelNumber", modelNumber,
                    actual.modelNumber());
            mismatch(result, "chip", chip, actual.chip());
            mismatch(result, "architecture",
                    normalizedArchitecture(architecture),
                    normalizedArchitecture(actual.architecture()));
            mismatch(result, "logicalCores", logicalCores,
                    actual.logicalCores());
            mismatch(result, "memoryReported", memoryReported,
                    actual.memoryReported());
            mismatch(result, "osProduct", osProduct, actual.osProduct());
            mismatch(result, "osVersion", osVersion, actual.osVersion());
            mismatch(result, "osBuild", osBuild, actual.osBuild());
            mismatch(result, "jdkVersion", jdkVersion,
                    actual.jdkVersion());
            mismatch(result, "jdkArchitecture",
                    normalizedArchitecture(jdkArchitecture),
                    normalizedArchitecture(actual.jdkArchitecture()));
            mismatch(result, "jdkVendor", jdkVendor, actual.jdkVendor());
            mismatch(result, "jdkHome", Path.of(jdkHome).normalize().toString(),
                    Path.of(actual.jdkHome()).normalize().toString());
            return List.copyOf(result);
        }

        private Map<String, Object> toMap() {
            return machineMap(
                    modelName,
                    modelIdentifier,
                    modelNumber,
                    chip,
                    architecture,
                    logicalCores,
                    memoryReported,
                    osProduct,
                    osVersion,
                    osBuild,
                    jdkVersion,
                    jdkArchitecture,
                    jdkVendor,
                    jdkHome);
        }
    }

    private record ActualMachine(
            String modelName,
            String modelIdentifier,
            String modelNumber,
            String chip,
            String architecture,
            long logicalCores,
            String memoryReported,
            String osProduct,
            String osVersion,
            String osBuild,
            String jdkVersion,
            String jdkArchitecture,
            String jdkVendor,
            String jdkHome) {
        private static ActualMachine capture(RuntimeIdentity runtime)
                throws IOException {
            Object hardwareJson = Json.parse(runCommand(
                    "/usr/sbin/system_profiler",
                    "SPHardwareDataType",
                    "-json",
                    "-detailLevel",
                    "mini"));
            Map<String, Object> hardwareRoot = object(
                    hardwareJson, "system_profiler root");
            List<Object> hardwareRows = array(
                    hardwareRoot.get("SPHardwareDataType"),
                    "SPHardwareDataType");
            if (hardwareRows.size() != 1) {
                throw new IllegalArgumentException(
                        "system_profiler returned " + hardwareRows.size()
                                + " hardware rows");
            }
            Map<String, Object> hardware = object(
                    hardwareRows.get(0), "SPHardwareDataType[0]");
            Map<String, String> os = colonProperties(runCommand(
                    "/usr/bin/sw_vers"));
            return new ActualMachine(
                    string(hardware, "machine_name"),
                    string(hardware, "machine_model"),
                    string(hardware, "model_number"),
                    string(hardware, "chip_type"),
                    runtime.osArchitecture(),
                    runtime.availableProcessors(),
                    string(hardware, "physical_memory"),
                    required(os, "ProductName"),
                    required(os, "ProductVersion"),
                    required(os, "BuildVersion"),
                    runtime.javaVersion(),
                    runtime.osArchitecture(),
                    runtime.javaVendor(),
                    System.getProperty("java.home"));
        }

        private Map<String, Object> toMap() {
            return machineMap(
                    modelName,
                    modelIdentifier,
                    modelNumber,
                    chip,
                    architecture,
                    logicalCores,
                    memoryReported,
                    osProduct,
                    osVersion,
                    osBuild,
                    jdkVersion,
                    jdkArchitecture,
                    jdkVendor,
                    jdkHome);
        }
    }

    private static Map<String, Object> machineMap(
            String modelName,
            String modelIdentifier,
            String modelNumber,
            String chip,
            String architecture,
            long logicalCores,
            String memoryReported,
            String osProduct,
            String osVersion,
            String osBuild,
            String jdkVersion,
            String jdkArchitecture,
            String jdkVendor,
            String jdkHome) {
        return map(
                "modelName", modelName,
                "modelIdentifier", modelIdentifier,
                "modelNumber", modelNumber,
                "chip", chip,
                "architecture", architecture,
                "logicalCores", logicalCores,
                "memoryReported", memoryReported,
                "os", map(
                        "product", osProduct,
                        "version", osVersion,
                        "build", osBuild),
                "jdk17", map(
                        "version", jdkVersion,
                        "architecture", jdkArchitecture,
                        "vendor", jdkVendor,
                        "javaHome", jdkHome));
    }

    private static void mismatch(
            List<String> result,
            String name,
            Object expected,
            Object actual) {
        if (!Objects.equals(expected, actual)) {
            result.add(name + ": expected=" + expected + ", actual="
                    + actual);
        }
    }

    private static String normalizedArchitecture(String architecture) {
        String normalized = Objects.requireNonNull(architecture,
                "architecture").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "aarch64", "arm64" -> "arm64";
            default -> normalized;
        };
    }

    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> supplied)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : supplied.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        label + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Object> array(Object value, String label) {
        if (!(value instanceof List<?> supplied)) {
            throw new IllegalArgumentException(label + " must be an array");
        }
        return List.copyOf(supplied);
    }

    private static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be text");
        }
        return text;
    }

    private static long integer(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Long number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return number.longValue();
    }

    private static String runCommand(String... command) throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean finished;
        try {
            finished = process.waitFor(30L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while capturing machine identity",
                    interrupted);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "Timed out capturing machine identity: "
                            + String.join(" ", command));
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "Machine identity command failed ("
                            + process.exitValue() + "): " + output);
        }
        return output;
    }

    private static Map<String, String> colonProperties(String value) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String line : value.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                result.put(
                        line.substring(0, separator).trim(),
                        line.substring(separator + 1).trim());
            }
        }
        return result;
    }

    private static String required(
            Map<String, String> values,
            String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing machine value " + key);
        }
        return value;
    }

    private static final class Json {
        private Json() {
        }

        private static String render(Object value) {
            StringBuilder output = new StringBuilder();
            append(output, value, 0);
            return output.toString();
        }

        private static Object parse(String source) {
            return new Parser(source).parse();
        }

        private static void append(
                StringBuilder output,
                Object value,
                int indentation) {
            if (value == null) {
                output.append("null");
            } else if (value instanceof String string) {
                quote(output, string);
            } else if (value instanceof Enum<?> enumeration) {
                quote(output, enumeration.name());
            } else if (value instanceof Boolean || value instanceof Number) {
                if (value instanceof Double number
                        && !Double.isFinite(number.doubleValue())) {
                    output.append("null");
                } else {
                    output.append(value);
                }
            } else if (value instanceof Map<?, ?> object) {
                appendObject(output, object, indentation);
            } else if (value instanceof Iterable<?> array) {
                appendArray(output, array, indentation);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported JSON value " + value.getClass());
            }
        }

        private static void appendObject(
                StringBuilder output,
                Map<?, ?> values,
                int indentation) {
            output.append('{');
            if (!values.isEmpty()) {
                output.append('\n');
                int index = 0;
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    indent(output, indentation + 2);
                    quote(output, (String) entry.getKey());
                    output.append(": ");
                    append(output, entry.getValue(), indentation + 2);
                    if (++index < values.size()) {
                        output.append(',');
                    }
                    output.append('\n');
                }
                indent(output, indentation);
            }
            output.append('}');
        }

        private static void appendArray(
                StringBuilder output,
                Iterable<?> values,
                int indentation) {
            ArrayList<Object> copied = new ArrayList<>();
            values.forEach(copied::add);
            output.append('[');
            if (!copied.isEmpty()) {
                output.append('\n');
                for (int index = 0; index < copied.size(); index++) {
                    indent(output, indentation + 2);
                    append(output, copied.get(index), indentation + 2);
                    if (index + 1 < copied.size()) {
                        output.append(',');
                    }
                    output.append('\n');
                }
                indent(output, indentation);
            }
            output.append(']');
        }

        private static void quote(StringBuilder output, String value) {
            output.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\b' -> output.append("\\b");
                    case '\f' -> output.append("\\f");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            output.append(String.format(
                                    Locale.ROOT,
                                    "\\u%04x",
                                    (int) character));
                        } else {
                            output.append(character);
                        }
                    }
                }
            }
            output.append('"');
        }

        private static void indent(StringBuilder output, int indentation) {
            output.append(" ".repeat(indentation));
        }

        private static final class Parser {
            private final String source;
            private int index;

            private Parser(String source) {
                this.source = Objects.requireNonNull(source, "source");
            }

            private Object parse() {
                skipWhitespace();
                Object result = value();
                skipWhitespace();
                if (index != source.length()) {
                    throw error("Trailing content");
                }
                return result;
            }

            private Object value() {
                if (index >= source.length()) {
                    throw error("Unexpected end of JSON");
                }
                return switch (source.charAt(index)) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            }

            private Map<String, Object> object() {
                expect('{');
                skipWhitespace();
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                if (take('}')) {
                    return result;
                }
                while (true) {
                    skipWhitespace();
                    if (index >= source.length()
                            || source.charAt(index) != '"') {
                        throw error("Object key must be a string");
                    }
                    String key = string();
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    if (result.containsKey(key)) {
                        throw error("Duplicate object key " + key);
                    }
                    result.put(key, value());
                    skipWhitespace();
                    if (take('}')) {
                        return result;
                    }
                    expect(',');
                }
            }

            private List<Object> array() {
                expect('[');
                skipWhitespace();
                ArrayList<Object> result = new ArrayList<>();
                if (take(']')) {
                    return result;
                }
                while (true) {
                    skipWhitespace();
                    result.add(value());
                    skipWhitespace();
                    if (take(']')) {
                        return result;
                    }
                    expect(',');
                }
            }

            private String string() {
                expect('"');
                StringBuilder result = new StringBuilder();
                while (index < source.length()) {
                    char character = source.charAt(index++);
                    if (character == '"') {
                        return result.toString();
                    }
                    if (character == '\\') {
                        if (index >= source.length()) {
                            throw error("Incomplete string escape");
                        }
                        char escaped = source.charAt(index++);
                        switch (escaped) {
                            case '"', '\\', '/' -> result.append(escaped);
                            case 'b' -> result.append('\b');
                            case 'f' -> result.append('\f');
                            case 'n' -> result.append('\n');
                            case 'r' -> result.append('\r');
                            case 't' -> result.append('\t');
                            case 'u' -> result.append(unicode());
                            default -> throw error(
                                    "Unsupported string escape " + escaped);
                        }
                    } else {
                        if (character < 0x20) {
                            throw error("Control character in string");
                        }
                        result.append(character);
                    }
                }
                throw error("Unterminated string");
            }

            private char unicode() {
                if (index + 4 > source.length()) {
                    throw error("Incomplete unicode escape");
                }
                int value;
                try {
                    value = Integer.parseInt(
                            source.substring(index, index + 4), 16);
                } catch (NumberFormatException failure) {
                    throw error("Invalid unicode escape");
                }
                index += 4;
                return (char) value;
            }

            private Object literal(String text, Object value) {
                if (!source.startsWith(text, index)) {
                    throw error("Expected " + text);
                }
                index += text.length();
                return value;
            }

            private Number number() {
                int start = index;
                take('-');
                digits();
                boolean decimal = false;
                if (take('.')) {
                    decimal = true;
                    digits();
                }
                if (take('e') || take('E')) {
                    decimal = true;
                    if (!take('+')) {
                        take('-');
                    }
                    digits();
                }
                String text = source.substring(start, index);
                try {
                    if (decimal) {
                        return Double.valueOf(text);
                    }
                    return Long.valueOf(text);
                } catch (NumberFormatException failure) {
                    throw error("Invalid number " + text);
                }
            }

            private void digits() {
                int start = index;
                while (index < source.length()
                        && Character.isDigit(source.charAt(index))) {
                    index++;
                }
                if (start == index) {
                    throw error("Expected digits");
                }
            }

            private void skipWhitespace() {
                while (index < source.length()
                        && Character.isWhitespace(source.charAt(index))) {
                    index++;
                }
            }

            private boolean take(char expected) {
                if (index < source.length()
                        && source.charAt(index) == expected) {
                    index++;
                    return true;
                }
                return false;
            }

            private void expect(char expected) {
                if (!take(expected)) {
                    throw error("Expected '" + expected + "'");
                }
            }

            private IllegalArgumentException error(String message) {
                return new IllegalArgumentException(
                        message + " at JSON offset " + index);
            }
        }
    }
}
