package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureWorkOccurrence;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.WorkKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Deterministic mixed-lane identity evidence from public Coordination tests
 * and one explicitly labeled package-private bounded-compatibility proof.
 */
final class CyclicTopologyIdentityEvidenceTest {
    private static final String WRITE_MODE_ENV =
            "BLUE_CYCLIC_TOPOLOGY_IDENTITY_ARTIFACT_MODE";
    private static final String WRITE_MODE = "WRITE";
    private static final Path ARTIFACT_DIRECTORY = Path.of(
            "stabilization", "rooted-checkpoint", "retained-topology-identities");
    private static final Path JSON_ARTIFACT = ARTIFACT_DIRECTORY.resolve(
            "full-lifecycle-admission-identities.json");
    private static final Path MARKDOWN_ARTIFACT = ARTIFACT_DIRECTORY.resolve(
            "full-lifecycle-admission-identities.md");
    private static final ThreadLocal<Recorder> ACTIVE = new ThreadLocal<>();
    private static final List<String> REQUIRED_SCENARIO_IDS = List.of(
            "P2.1.finite-three-member-ring",
            "P2.3.three-direct-seeds",
            "P2.4.shared-gas-rollback",
            "P3.1.shared-anchor-BASELINE",
            "P3.1.shared-anchor-REVERSED_MATERIALIZED",
            "P3.3.disjoint-BOTH-cohort-0",
            "P3.3.disjoint-BOTH-cohort-1",
            "P3.3.disjoint-FIRST_ONLY-cohort-0",
            "P4.initial-five-member-cycle",
            "P4.pre-detach-gas-rollback",
            "P4.1.partial-detach",
            "P4.2.full-dissolution",
            "P4.3.post-detach-gas-success",
            "P4.5.re-add-retired-edge",
            "P4.5.reformed-cycle-probe",
            "P4.4.frozen-edge-delivery",
            "P4.4.later-occurrence-uses-new-graph",
            "P5.1.merge-two-cycles",
            "P5.2.split-four-member-cycle",
            "P5.3.split-to-ordinary-singletons",
            "P5.4.dissolve-self-cycle",
            "P5.5.late-failure-rollback",
            "P6.static-three-member-admission",
            "P6.static-order-DECLARED",
            "P6.static-order-REVERSED",
            "P6.dynamic-topology-DECLARED",
            "P6.dynamic-topology-REVERSED",
            "P6.late-initialization-failure",
            "P6.c-clo-08-bounded-compatibility",
            "P7.ordinary-nested-scope",
            "P7.cyclic-root-only-admission",
            "P7.cyclic-root-only-operation");
    private static final Map<String, Integer> EXPECTED_REPEAT_COUNTS = Map.of(
            "P2.1.finite-three-member-ring", 6,
            "P2.4.shared-gas-rollback", 1);
    private static final String SUPERSEDED_EXECUTION_STATUS =
            "UNAVAILABLE_SUPERSEDED_BY_LATER_COHORT";

    @Test
    void exactRuntimeIdentitiesMatchTheCommittedArtifacts() throws Exception {
        // given

        Recorder recorder = new Recorder();
        if (ACTIVE.get() != null) {
            throw new IllegalStateException(
                    "Cyclic identity evidence capture is already active");
        }
        ACTIVE.set(recorder);
        try {
            runEvidenceScenarios();
        } finally {
            ACTIVE.remove();
        }

        recorder.validateComplete();
        Map<String, Object> document = recorder.document();
        String json = Json.render(document) + "\n";
        String markdown = renderMarkdown(document);
        String mode = System.getenv(WRITE_MODE_ENV);

        // when
        if (mode == null) {

            // then
            assertEquals(readRequired(JSON_ARTIFACT), json,
                    "regenerate explicitly with " + WRITE_MODE_ENV
                            + "=" + WRITE_MODE);
            assertEquals(readRequired(MARKDOWN_ARTIFACT), markdown,
                    "regenerate explicitly with " + WRITE_MODE_ENV
                            + "=" + WRITE_MODE);
            return;
        }
        if (!WRITE_MODE.equals(mode)) {
            throw new IllegalStateException(
                    WRITE_MODE_ENV + " must be absent or exactly "
                            + WRITE_MODE);
        }
        writeAtomically(JSON_ARTIFACT, json);
        writeAtomically(MARKDOWN_ARTIFACT, markdown);
    }

    private static void runEvidenceScenarios() throws Exception {
        ContractsPublicThreeMemberCycleTest three =
                new ContractsPublicThreeMemberCycleTest();
        three.finiteReverseContainmentRingExecutesRequestedBusinessFlow();
        three.canonicalAdmissionAndDiscoveryIgnoreEveryAuthoredOrderVariant();
        three.sameEntryUsesCanonicalDirectSeedsAndClosesEachContinuation();
        three.threeMemberLoopRollbackIsIdenticalAcrossFreshEngineRuns();

        ContractsPublicBranchingCollectionCycleTest branching =
                new ContractsPublicBranchingCollectionCycleTest();
        branching.sharedAnchorCollectionCycleConvergesOnceInCanonicalOrder();
        branching.disjointCyclesRemainSeparateForBothAndSingleTargetEntries();

        ContractsPublicCycleDetachmentTest detachment =
                new ContractsPublicCycleDetachmentTest();
        detachment.splitDissolveAndReaddChangeRealCausalityAndLineage();
        detachment.retiredEdgeStillServesItsAlreadyFrozenSecondDelivery();

        ContractsPublicComponentMergeSplitTest mergeSplit =
                new ContractsPublicComponentMergeSplitTest();
        mergeSplit.twoTwoMemberCyclesMergeIntoOneFourMemberCycle();
        mergeSplit.oneFourMemberCycleSplitsIntoTwoTwoMemberCycles();
        mergeSplit.oneTwoMemberCycleSplitsIntoTwoOrdinarySingletons();
        mergeSplit.selfCycleDissolvesIntoOneOrdinaryDocument();
        mergeSplit.laterHandlerFailureRollsBackAlreadyStagedSplitExactly();

        ContractsPublicInitializationTopologyTest initialization =
                new ContractsPublicInitializationTopologyTest();
        initialization
                .staticThreeMemberCycleInitializesOnceInCanonicalOrderAndPublishes();
        initialization
                .staticInitializationOrderAndIdentitiesIgnoreInputPermutation();
        initialization
                .dynamicTopologyPatchInsideCycleFailsAtManagedBindingBoundary();
        initialization
                .laterMemberInitializationFailureRollsBackEveryMarkerAndPublication();
        initialization
                .cClo08BoundedCompatibilityPreservesHistoricalFailClosedEvidence();

        ContractsPublicNestedScopeBoundaryTest nested =
                new ContractsPublicNestedScopeBoundaryTest();
        nested.ordinaryPublicEngineExecutesTheNestedScopeNormally();
        nested.contractsDirectSeedsAndManagedStepsRemainPreciselyRootOnly();
    }

    static void capture(
            String scenarioId,
            DefaultCoordinationEngine engine,
            ClosureProcessResult result,
            ProcessingDrainReceipt drain,
            Map<String, ?> assertedFacts) {
        Recorder recorder = ACTIVE.get();
        if (recorder == null) {
            return;
        }
        recorder.capture(
                scenarioId,
                engine,
                result,
                drain,
                assertedFacts);
    }

    static boolean isActive() {
        return ACTIVE.get() != null;
    }

    static void captureLatest(
            String scenarioId,
            DefaultCoordinationEngine engine,
            ProcessingDrainReceipt drain,
            Map<String, ?> assertedFacts) {
        Recorder recorder = ACTIVE.get();
        if (recorder == null) {
            return;
        }
        Optional<ClosureProcessResult> latest = engine.documents()
                .publicationSnapshot()
                .closurePublicationReceipts()
                .values()
                .stream()
                .map(receipt -> receipt.attempt().processResult())
                .reduce((previous, next) -> next);
        recorder.capture(
                scenarioId,
                engine,
                latest.orElseThrow(),
                drain,
                assertedFacts);
    }

    static void captureHost(
            String scenarioId,
            Map<String, ?> assertedFacts) {
        Recorder recorder = ACTIVE.get();
        if (recorder != null) {
            recorder.captureHost(scenarioId, assertedFacts);
        }
    }

    static Map<String, Object> facts(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "facts require alternating key and value arguments");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            String key = Objects.requireNonNull(
                    (String) keyValues[index], "fact key");
            if (result.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate fact " + key);
            }
            result.put(key, keyValues[index + 1]);
        }
        return result;
    }

    private static String readRequired(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "Missing committed Phase 2 identity artifact " + path);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void writeAtomically(Path target, String value)
            throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(
                target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(
                    temporary, value, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String renderMarkdown(Map<String, Object> document) {
        StringBuilder result = new StringBuilder();
        result.append("# Full-lifecycle admission Phase 2 identity evidence\n\n")
                .append("This mixed evidence artifact is generated from real ")
                .append("public Coordination admission/topology tests and one ")
                .append("explicitly labeled package-private bounded-")
                .append("compatibility proof. No identity below is ")
                .append("hand-authored; the bounded record states its lane ")
                .append("and that it does not exercise the public API.\n\n")
                .append("`implementationConformanceClaimed = false`\n\n")
                .append("## Frozen inputs\n\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs =
                (Map<String, Object>) document.get("inputs");
        inputs.forEach((name, identity) -> result.append("- ")
                .append(name)
                .append(": `")
                .append(identity)
                .append("`\n"));
        result.append("\n")
                .append("## Boundary facts\n\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> boundaries =
                (List<Map<String, Object>>) document.get("boundaryFacts");
        for (Map<String, Object> boundary : boundaries) {
            result.append("- **")
                    .append(boundary.get("status"))
                    .append(" — ")
                    .append(boundary.get("id"))
                    .append(":** ")
                    .append(boundary.get("fact"))
                    .append('\n');
        }
        result.append("\n## Exact scenario evidence\n\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios =
                (List<Map<String, Object>>) document.get("scenarios");
        for (Map<String, Object> scenario : scenarios) {
            result.append("### ")
                    .append(scenario.get("id"))
                    .append("\n\n```json\n")
                    .append(Json.render(scenario))
                    .append("\n```\n\n");
        }
        if (!result.isEmpty()
                && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    private static final class Recorder {
        private final LinkedHashMap<String, Map<String, Object>> scenarios =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> repeatCounts =
                new LinkedHashMap<>();

        void capture(
                String scenarioId,
                DefaultCoordinationEngine engine,
                ClosureProcessResult result,
                ProcessingDrainReceipt drain,
                Map<String, ?> assertedFacts) {
            LinkedHashMap<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("id", scenarioId);
            scenario.put("assertedFacts", normalizeMap(assertedFacts));
            scenario.put("result", projectResult(result, drain));
            scenario.put("execution", projectExecution(engine, result));
            scenario.put("durableState", projectDurableState(engine));
            add(scenarioId, scenario);
        }

        void captureHost(
                String scenarioId,
                Map<String, ?> assertedFacts) {
            LinkedHashMap<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("id", scenarioId);
            scenario.put("assertedFacts", normalizeMap(assertedFacts));
            add(scenarioId, scenario);
        }

        private void add(String scenarioId, Map<String, Object> scenario) {
            Map<String, Object> existing = scenarios.putIfAbsent(
                    scenarioId, scenario);
            if (existing != null) {
                assertEquals(
                        Json.render(existing),
                        Json.render(scenario),
                        "repeated identity capture differs for " + scenarioId);
                repeatCounts.merge(scenarioId, 1, Integer::sum);
            }
        }

        void validateComplete() {
            List<String> capturedIds = List.copyOf(scenarios.keySet());
            if (!REQUIRED_SCENARIO_IDS.equals(capturedIds)) {
                throw new IllegalStateException(
                        "Identity evidence scenario inventory mismatch; "
                                + "required=" + REQUIRED_SCENARIO_IDS
                                + ", captured=" + capturedIds);
            }
            for (String id : REQUIRED_SCENARIO_IDS) {
                int expected = EXPECTED_REPEAT_COUNTS.getOrDefault(id, 0);
                int actual = repeatCounts.getOrDefault(id, 0);
                if (actual != expected) {
                    throw new IllegalStateException(
                            "Identity evidence repeat count mismatch for "
                                    + id + ": required exactly " + expected
                                    + ", captured " + actual);
                }
            }
            scenarios.forEach(Recorder::validateExecutionEvidence);
        }

        @SuppressWarnings("unchecked")
        private static void validateExecutionEvidence(
                String scenarioId,
                Map<String, Object> scenario) {
            if ("P7.ordinary-nested-scope".equals(scenarioId)) {
                if (scenario.containsKey("execution")
                        || scenario.containsKey("result")) {
                    throw new IllegalStateException(
                            "Ordinary nested evidence unexpectedly contains "
                                    + "Contracts execution data");
                }
                return;
            }
            Map<String, Object> execution = (Map<String, Object>) scenario
                    .get("execution");
            if (execution == null) {
                throw new IllegalStateException(
                        "Missing execution evidence for " + scenarioId);
            }
            Map<String, Object> result = (Map<String, Object>) scenario
                    .get("result");
            if ("P3.3.disjoint-BOTH-cohort-0".equals(scenarioId)) {
                if (!SUPERSEDED_EXECUTION_STATUS.equals(
                        execution.get("captureStatus"))) {
                    throw new IllegalStateException(
                            "The first disjoint cohort must explicitly record "
                                    + SUPERSEDED_EXECUTION_STATUS);
                }
                if (!Objects.equals(result.get("invocationIdentity"),
                        execution.get("requestedInvocationIdentity"))) {
                    throw new IllegalStateException(
                            "Superseded cohort requested identity mismatch");
                }
                if (Objects.equals(
                        execution.get("requestedInvocationIdentity"),
                        execution.get("latestInvocationIdentity"))) {
                    throw new IllegalStateException(
                            "Superseded cohort must identify a later distinct "
                                    + "execution");
                }
                return;
            }
            if (execution.containsKey("captureStatus")) {
                throw new IllegalStateException(
                        "Unavailable execution evidence for required scenario "
                                + scenarioId + ": "
                                + execution.get("captureStatus"));
            }
            if (!Objects.equals(result.get("invocationIdentity"),
                    execution.get("invocationIdentity"))) {
                throw new IllegalStateException(
                        "Execution/result invocation mismatch for "
                                + scenarioId);
            }
            if (!Boolean.TRUE.equals(execution.get("complete"))) {
                throw new IllegalStateException(
                        "Incomplete execution evidence for " + scenarioId);
            }
        }

        Map<String, Object> document() {
            BundledContracts10Release.Manifest manifest =
                    BundledContracts10Release.manifest();
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion",
                    "full-lifecycle-admission-phase2-identities/1.0");
            result.put("implementationConformanceClaimed", false);
            result.put("source",
                    "Current runtime executing retained broad-closure topology tests, "
                            + "bundled-release initialization tests and the labeled bounded-compatibility proof; "
                            + "this artifact does not claim all scenarios use rooted processing");
            result.put("inputs", normalizeMap(Map.of(
                    "blueLanguageSpecification",
                    manifest.blueLanguageSpecification(),
                    "contractsSpecification",
                    manifest.contractsSpecification(),
                    "contractsRelease",
                    manifest.contractsRelease(),
                    "fixturePackage",
                    manifest.fixturePackage(),
                    "gasManifest",
                    manifest.gasManifest(),
                    "cyclicFinalizer",
                    manifest.cyclicFinalizer(),
                    "cyclicProofVerifier",
                    manifest.cyclicProofVerifier())));
            result.put("profileSelection", normalizeMap(Map.of(
                    "retainedLanguageSpecification", LegacyContracts10TestProfile.LANGUAGE,
                    "retainedContractsSpecification", LegacyContracts10TestProfile.CONTRACTS,
                    "P2-P5", "Explicit LegacyContracts10TestProfile.configuration in the retained fixture owners",
                    "P6", "BundledContracts10Release.configuration; C-CLO-08 remains the separately labeled bounded proof",
                    "P7", "Ordinary nested processing plus explicit LegacyContracts10TestProfile cyclic boundaries")));
            result.put("boundaryFacts", boundaryFacts());
            List<Map<String, Object>> values = new ArrayList<>();
            scenarios.forEach((id, scenario) -> {
                LinkedHashMap<String, Object> value =
                        new LinkedHashMap<>(scenario);
                value.put("verifiedIdenticalRepeatCount",
                        repeatCounts.getOrDefault(id, 0));
                values.add(value);
            });
            result.put("scenarios", values);
            return result;
        }

        private static List<Map<String, Object>> boundaryFacts() {
            return List.of(
                    boundary(
                            "rawBexResultFingerprint",
                            "UNOBSERVABLE",
                            "Raw BEX result fingerprint is not observable at "
                                    + "the public Coordination boundary; the "
                                    + "exact public observable projection is "
                                    + "recorded instead."),
                    boundary(
                            "phase6DynamicInitialization",
                            "BLOCKED",
                            "Full-lifecycle admission reaches dynamic topology "
                                    + "evolution, but automatic managed-"
                                    + "occurrence evidence is not yet "
                                    + "available; the exact missing-binding "
                                    + "rollback identities are recorded "
                                    + "instead."),
                    boundary(
                            "phase7NestedCyclicScope",
                            "BLOCKED",
                            "Positive nested cyclic work identity is not "
                                    + "extractable because the Contracts 1.0 "
                                    + "affected-closure profile is Root-only; "
                                    + "ordinary nested success and the cyclic "
                                    + "route miss/Root work are recorded instead."),
                    boundary(
                            "gasRejectionBoundary",
                            "CHARACTERIZED",
                            "The observed rejected handlerCall and "
                                    + "handlerCandidateTested charges belong to "
                                    + "already-started work occurrences; "
                                    + "this round does not claim "
                                    + "rejection before that work begins."));
        }

        private static Map<String, Object> boundary(
                String id,
                String status,
                String fact) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("status", status);
            result.put("fact", fact);
            return result;
        }

        private static Map<String, Object> projectResult(
                ClosureProcessResult result,
                ProcessingDrainReceipt drain) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("status", result.status().name());
            value.put("commits", result.commits());
            value.put("atomic", result.atomic());
            value.put("rollbackToInput", result.rollbackToInput());
            value.put("invocationIdentity", result.invocationIdentity());
            value.put("inputClosureIdentity",
                    result.inputClosureIdentity());
            value.put("outputClosureIdentity",
                    result.outputClosureIdentity());
            value.put("graphGeneration", result.graphGeneration());
            value.put("resultingDocuments", result.resultingDocuments()
                    .stream()
                    .map(Recorder::projectDocument)
                    .toList());
            value.put("resultingComponents", result.resultingComponents()
                    .stream()
                    .map(Recorder::projectComponent)
                    .toList());
            value.put("occurrenceBindingSetIdentity",
                    result.occurrenceBindingSetIdentity());
            value.put("occurrenceBindings", projectOccurrences(
                    result.occurrenceBindings()));
            value.put("graphChangesIdentity", result.graphChangesIdentity());
            value.put("graphChanges", result.graphChanges().stream()
                    .map(Recorder::projectGraphChange)
                    .toList());
            value.put("subscriptionDeltasIdentity",
                    result.subscriptionDeltasIdentity());
            value.put("subscriptionDeltas", result.subscriptionDeltas()
                    .stream()
                    .map(Recorder::projectSubscription)
                    .toList());
            value.put("checkpointWritesIdentity",
                    result.checkpointWritesIdentity());
            value.put("checkpointWrites", result.checkpointWrites().stream()
                    .map(Recorder::projectCheckpoint)
                    .toList());
            value.put("publicEventsIdentity", result.publicEventsIdentity());
            value.put("publicEvents", result.publicEvents().stream()
                    .map(Recorder::projectPublicEvent)
                    .toList());
            value.put("gas", projectGas(result));
            List<GasTraceEntry> dequeues = result.gasTrace().stream()
                    .filter(entry -> "closureWorkOccurrenceDequeued"
                            .equals(entry.counter()))
                    .toList();
            value.put("workOccurrenceCount", dequeues.size());
            value.put("workOrder", dequeues.stream()
                    .map(entry -> entry.documentId().value())
                    .toList());
            value.put("workIdentities", dequeues.stream()
                    .map(GasTraceEntry::workOccurrenceId)
                    .toList());
            value.put("rejectedWork", projectWork(
                    result.rejectedWorkOccurrence()));
            value.put("rejectedCharge", projectRejectedCharge(
                    result.rejectedCharge()));
            value.put("changedDocumentCount", result.resultingDocuments()
                    .stream()
                    .filter(document -> !document.beforeBlueId().equals(
                            document.afterBlueId()))
                    .count());
            if (drain != null) {
                value.put("committedProcessTransitions",
                        drain.committedProcessTransitions());
                value.put("processedEntryBlueIds", drain.processedEntries()
                        .stream()
                        .map(entry -> entry.blueId())
                        .toList());
                value.put("quiescent", drain.quiescent());
                value.put("paused", drain.paused());
            }
            if (result.diagnostic() != null) {
                LinkedHashMap<String, Object> diagnostic =
                        new LinkedHashMap<>();
                diagnostic.put("category",
                        result.diagnostic().category().name());
                diagnostic.put("message", result.diagnostic().message());
                diagnostic.put("details",
                        normalizeMap(result.diagnostic().details()));
                value.put("diagnostic", diagnostic);
            } else {
                value.put("diagnostic", null);
            }
            return value;
        }

        private static Map<String, Object> projectExecution(
                DefaultCoordinationEngine engine,
                ClosureProcessResult result) {
            Optional<ClosureImplementationEvidence> selected = engine
                    .contractsClosureAdapter()
                    .lastExecutionEvidence();
            if (selected.isEmpty()) {
                selected = engine.contractsClosureAdmissionAdapter()
                        .lastExecutionEvidence();
            }
            if (selected.isEmpty()) {
                LinkedHashMap<String, Object> unavailable =
                        new LinkedHashMap<>();
                unavailable.put(
                        "captureStatus",
                        "UNAVAILABLE_NO_EXECUTION_EVIDENCE");
                return unavailable;
            }
            ClosureImplementationEvidence evidence = selected.orElseThrow();
            if (!result.invocationIdentity().equals(
                    evidence.invocationIdentity())) {
                LinkedHashMap<String, Object> unavailable =
                        new LinkedHashMap<>();
                unavailable.put(
                        "captureStatus", SUPERSEDED_EXECUTION_STATUS);
                unavailable.put(
                        "requestedInvocationIdentity",
                        result.invocationIdentity());
                unavailable.put(
                        "latestInvocationIdentity",
                        evidence.invocationIdentity());
                return unavailable;
            }
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("invocationIdentity", evidence.invocationIdentity());
            value.put("complete", evidence.complete());
            value.put("nonConformanceCode", evidence.nonConformanceCode());
            value.put("acceptedWorkOccurrenceCount",
                    evidence.workTrace().size());
            value.put("workTrace", evidence.workTrace().stream()
                    .map(Recorder::projectWork)
                    .toList());
            value.put("directSeedOrder", evidence.workTrace().stream()
                    .filter(work -> work.kind()
                            == WorkKind.EXTERNAL_DELIVERY)
                    .map(work -> work.targetDocumentId().value())
                    .toList());
            value.put("directSeedWorkIdentities", evidence.workTrace().stream()
                    .filter(work -> work.kind()
                            == WorkKind.EXTERNAL_DELIVERY)
                    .map(ClosureWorkOccurrence::workIdentity)
                    .toList());
            value.put("documentStepCount",
                    evidence.documentStepTrace().size());
            value.put("documentSteps", evidence.documentStepTrace().stream()
                    .map(Recorder::projectDocumentStep)
                    .toList());
            return value;
        }

        private static Map<String, Object> projectDurableState(
                DefaultCoordinationEngine engine) {
            InMemoryDocumentStore.PublicationSnapshot snapshot = engine
                    .documents().publicationSnapshot();
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("occurrenceInventoryGeneration",
                    snapshot.occurrenceInventoryGeneration());
            value.put("componentIndexGeneration",
                    snapshot.componentIndexGeneration());
            List<Map<String, Object>> heads = snapshot.documentHeads()
                    .entrySet()
                    .stream()
                    .sorted(Comparator.comparing(
                            entry -> entry.getKey().value()))
                    .map(entry -> {
                        LinkedHashMap<String, Object> head =
                                new LinkedHashMap<>();
                        head.put("documentId", entry.getKey().value());
                        head.put("epoch", entry.getValue().epoch());
                        head.put("blueId", entry.getValue().blueId());
                        head.put("graphGeneration", snapshot
                                .graphGenerations()
                                .require(entry.getKey()));
                        return (Map<String, Object>) head;
                    })
                    .toList();
            value.put("documentHeads", heads);
            value.put("components", snapshot.componentStates().stream()
                    .map(Recorder::projectComponent)
                    .toList());
            value.put("occurrences", projectOccurrences(
                    snapshot.occurrenceInventory().rows()));
            return value;
        }

        private static Map<String, Object> projectDocument(
                ResultingDocument document) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("documentId", document.documentId().value());
            value.put("beforeBlueId", document.beforeBlueId());
            value.put("afterBlueId", document.afterBlueId());
            value.put("changed", !document.beforeBlueId().equals(
                    document.afterBlueId()));
            value.put("epoch", document.epoch());
            value.put("componentGeneration",
                    document.componentGeneration());
            value.put("componentIdentity", document.componentIdentity());
            value.put("componentStateIdentity",
                    document.componentStateIdentity());
            value.put("memberIndex", document.memberIndex());
            value.put("initialized", document.initialized());
            value.put("terminated", document.terminated());
            value.put("publicRoot", document.publicRoot());
            return value;
        }

        private static Map<String, Object> projectComponent(
                ComponentSnapshot component) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("componentIdentity", component.componentIdentity());
            value.put("componentStateIdentity",
                    component.componentStateIdentity());
            value.put("componentGeneration",
                    component.componentGeneration());
            value.put("kind", component.kind().name());
            value.put("members", component.orderedMemberDocumentIds().stream()
                    .map(document -> document.value())
                    .toList());
            value.put("memberBlueIds", component.orderedMemberBlueIds());
            value.put("masterBlueId", component.masterBlueId());
            value.put("cyclicProofIdentity",
                    component.cyclicProofIdentity());
            return value;
        }

        private static List<Map<String, Object>> projectOccurrences(
                Collection<ManagedOccurrenceBinding> occurrences) {
            return occurrences.stream()
                    .sorted(Comparator
                            .comparing((ManagedOccurrenceBinding row) ->
                                    row.sourceDocumentId().value())
                            .thenComparing(
                                    ManagedOccurrenceBinding::sourcePath)
                            .thenComparingLong(
                                    ManagedOccurrenceBinding
                                            ::activationGeneration)
                            .thenComparing(
                                    ManagedOccurrenceBinding
                                            ::bindingIdentity))
                    .map(Recorder::projectOccurrence)
                    .toList();
        }

        private static Map<String, Object> projectOccurrence(
                ManagedOccurrenceBinding row) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("occurrenceIdentity", row.occurrenceIdentity());
            value.put("bindingIdentity", row.bindingIdentity());
            value.put("bindingPolicyIdentity",
                    row.bindingPolicyIdentity());
            value.put("sourceDocumentId", row.sourceDocumentId().value());
            value.put("sourcePath", row.sourcePath());
            value.put("activationGeneration",
                    row.activationGeneration());
            value.put("targetDocumentId", row.targetDocumentId().value());
            value.put("expectedTargetBlueId",
                    row.expectedTargetBlueId());
            value.put("active", row.active());
            value.put("pendingHistoricalEpoch",
                    row.pendingHistoricalEpoch());
            return value;
        }

        private static Map<String, Object> projectWork(
                ClosureWorkOccurrence work) {
            if (work == null) {
                return null;
            }
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("ordinal", work.ordinal());
            value.put("kind", work.kind().name());
            value.put("targetDocumentId",
                    work.targetDocumentId().value());
            value.put("channelKey", work.channelKey());
            value.put("eventBlueId", work.eventBlueId());
            value.put("occurrenceOrdinal", work.occurrenceOrdinal());
            value.put("targetManagedScopeIdentity",
                    work.targetManagedScopeIdentity());
            value.put("sourceOccurrenceIdentity",
                    work.sourceOccurrenceIdentity());
            value.put("workIdentity", work.workIdentity());
            return value;
        }

        private static Map<String, Object> projectDocumentStep(
                DocumentStepEvidence step) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("stepOrdinal", step.stepOrdinal());
            value.put("workOrdinal", step.workOrdinal());
            value.put("targetDocumentId",
                    step.targetDocumentId().value());
            value.put("executionRootDocumentId",
                    step.executionRootDocumentId().value());
            value.put("scopePath", step.scopePath());
            value.put("executionMode", step.executionMode());
            value.put("ambientContainingDocumentIds",
                    step.ambientContainingDocumentIds().stream()
                            .map(document -> document.value())
                            .toList());
            return value;
        }

        private static Map<String, Object> projectGas(
                ClosureProcessResult result) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("gasTraceIdentity", result.gasTraceIdentity());
            value.put("totalGas", result.totalGas());
            value.put("entryCount", result.gasTrace().size());
            LinkedHashMap<String, Long> byWork = new LinkedHashMap<>();
            for (GasTraceEntry entry : result.gasTrace()) {
                if (entry.workOccurrenceId() != null) {
                    byWork.merge(
                            entry.workOccurrenceId(),
                            entry.subtotal(),
                            Long::sum);
                }
            }
            value.put("admittedGasByWorkIdentity", byWork);
            if (result.rejectedWorkOccurrence() != null) {
                String rejected = result.rejectedWorkOccurrence()
                        .workIdentity();
                value.put("rejectedWorkAdmittedCounters",
                        result.gasTrace().stream()
                                .filter(entry -> rejected.equals(
                                        entry.workOccurrenceId()))
                                .map(GasTraceEntry::counter)
                                .toList());
            } else {
                value.put("rejectedWorkAdmittedCounters", List.of());
            }
            return value;
        }

        private static Map<String, Object> projectRejectedCharge(
                RejectedCharge charge) {
            if (charge == null) {
                return null;
            }
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("rejectedChargeIdentity",
                    charge.rejectedChargeIdentity());
            value.put("namespace", charge.namespace().name());
            value.put("counter", charge.counter());
            value.put("quantity", charge.quantity());
            value.put("weight", charge.weight());
            value.put("subtotal", charge.subtotal());
            value.put("remainingBeforeCharge",
                    charge.remainingBeforeCharge());
            value.put("applicableCap", charge.applicableCap().kind().name());
            value.put("applicableCapDocumentId",
                    charge.applicableCap().documentId() == null
                            ? null
                            : charge.applicableCap().documentId().value());
            value.put("ownerKind", charge.owner().kind().name());
            value.put("ownerWorkOccurrenceIdentity",
                    charge.owner().workOccurrenceIdentity());
            value.put("ownerFinalizationOrdinal",
                    charge.owner().finalizationOrdinal());
            value.put("ownerComponentIdentity",
                    charge.owner().componentIdentity());
            value.put("ownerComponentGeneration",
                    charge.owner().componentGeneration());
            return value;
        }

        private static Map<String, Object> projectGraphChange(
                GraphChange change) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("ordinal", change.graphChangeOrdinal());
            value.put("kind", change.changeKind().name());
            value.put("sourceDocumentId",
                    change.sourceDocumentId().value());
            value.put("sourcePath", change.sourcePath());
            value.put("beforeActivationGeneration",
                    change.beforeActivationGeneration());
            value.put("beforeOccurrenceIdentity",
                    change.beforeOccurrenceIdentity());
            value.put("beforeBindingIdentity",
                    change.beforeBindingIdentity());
            value.put("beforeTargetDocumentId",
                    change.beforeTargetDocumentId() == null
                            ? null
                            : change.beforeTargetDocumentId().value());
            value.put("beforeTargetBlueId",
                    change.beforeTargetBlueId());
            value.put("afterActivationGeneration",
                    change.afterActivationGeneration());
            value.put("afterOccurrenceIdentity",
                    change.afterOccurrenceIdentity());
            value.put("afterBindingIdentity",
                    change.afterBindingIdentity());
            value.put("afterTargetDocumentId",
                    change.afterTargetDocumentId() == null
                            ? null
                            : change.afterTargetDocumentId().value());
            value.put("afterTargetBlueId", change.afterTargetBlueId());
            return value;
        }

        private static Map<String, Object> projectSubscription(
                SubscriptionDelta delta) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("ordinal", delta.subscriptionDeltaOrdinal());
            value.put("operation", delta.operation().name());
            value.put("targetManagedScopeIdentity",
                    delta.targetManagedScopeIdentity());
            value.put("channelOccurrenceIdentity",
                    delta.channelOccurrenceIdentity());
            value.put("beforeSubscriptionIdentity",
                    delta.beforeSubscriptionIdentity());
            value.put("afterSubscriptionIdentity",
                    delta.afterSubscriptionIdentity());
            value.put("beforeDocumentBlueId",
                    delta.beforeDocumentBlueId());
            value.put("afterDocumentBlueId",
                    delta.afterDocumentBlueId());
            value.put("beforeGraphGeneration",
                    delta.beforeGraphGeneration());
            value.put("afterGraphGeneration", delta.afterGraphGeneration());
            value.put("beforeComponentGeneration",
                    delta.beforeComponentGeneration());
            value.put("afterComponentGeneration",
                    delta.afterComponentGeneration());
            return value;
        }

        private static Map<String, Object> projectCheckpoint(
                CheckpointWrite write) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("ordinal", write.checkpointWriteOrdinal());
            value.put("targetManagedScopeIdentity",
                    write.targetManagedScopeIdentity());
            value.put("rawChannelKey", write.rawChannelKey());
            value.put("beforePresent", write.beforePresent());
            value.put("beforeDomainBlueId", write.beforeDomainBlueId());
            value.put("beforeSubjectBlueId", write.beforeSubjectBlueId());
            value.put("afterPresent", write.afterPresent());
            value.put("afterDomainBlueId", write.afterDomainBlueId());
            value.put("afterSubjectBlueId", write.afterSubjectBlueId());
            return value;
        }

        private static Map<String, Object> projectPublicEvent(
                PublicEventOccurrence event) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("publicEventOrdinal", event.publicEventOrdinal());
            value.put("eventOccurrenceOrdinal",
                    event.eventOccurrenceOrdinal());
            value.put("publicRootDocumentId",
                    event.publicRootDocumentId().value());
            value.put("eventOccurrenceIdentity",
                    event.eventOccurrenceIdentity());
            value.put("eventBlueId", event.eventBlueId());
            return value;
        }

        private static Map<String, Object> normalizeMap(Map<?, ?> source) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            source.forEach((key, value) -> sorted.put(
                    String.valueOf(key), normalize(value)));
            return new LinkedHashMap<>(sorted);
        }

        private static Object normalize(Object value) {
            if (value == null
                    || value instanceof String
                    || value instanceof Number
                    || value instanceof Boolean) {
                return value;
            }
            if (value instanceof DocumentId documentId) {
                return documentId.value();
            }
            if (value instanceof blue.language.processor.closure.DocumentId
                    documentId) {
                return documentId.value();
            }
            if (value instanceof Enum<?> enumeration) {
                return enumeration.name();
            }
            if (value instanceof Map<?, ?> map) {
                return normalizeMap(map);
            }
            if (value instanceof Collection<?> collection) {
                return collection.stream().map(Recorder::normalize).toList();
            }
            throw new IllegalArgumentException(
                    "Unsupported evidence value " + value.getClass());
        }
    }

    private static final class Json {
        private Json() {
        }

        static String render(Object value) {
            StringBuilder result = new StringBuilder();
            append(result, value, 0);
            return result.toString();
        }

        private static void append(
                StringBuilder result,
                Object value,
                int depth) {
            if (value == null) {
                result.append("null");
            } else if (value instanceof String string) {
                appendString(result, string);
            } else if (value instanceof Number || value instanceof Boolean) {
                result.append(value);
            } else if (value instanceof Map<?, ?> map) {
                appendMap(result, map, depth);
            } else if (value instanceof Collection<?> collection) {
                appendCollection(result, collection, depth);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported JSON value " + value.getClass());
            }
        }

        private static void appendMap(
                StringBuilder result,
                Map<?, ?> map,
                int depth) {
            if (map.isEmpty()) {
                result.append("{}");
                return;
            }
            result.append("{\n");
            int position = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(result, depth + 1);
                appendString(result, String.valueOf(entry.getKey()));
                result.append(": ");
                append(result, entry.getValue(), depth + 1);
                if (++position < map.size()) {
                    result.append(',');
                }
                result.append('\n');
            }
            indent(result, depth);
            result.append('}');
        }

        private static void appendCollection(
                StringBuilder result,
                Collection<?> collection,
                int depth) {
            if (collection.isEmpty()) {
                result.append("[]");
                return;
            }
            result.append("[\n");
            int position = 0;
            for (Object element : collection) {
                indent(result, depth + 1);
                append(result, element, depth + 1);
                if (++position < collection.size()) {
                    result.append(',');
                }
                result.append('\n');
            }
            indent(result, depth);
            result.append(']');
        }

        private static void appendString(
                StringBuilder result,
                String value) {
            result.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"' -> result.append("\\\"");
                    case '\\' -> result.append("\\\\");
                    case '\b' -> result.append("\\b");
                    case '\f' -> result.append("\\f");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            result.append(String.format(
                                    "\\u%04x", (int) character));
                        } else {
                            result.append(character);
                        }
                    }
                }
            }
            result.append('"');
        }

        private static void indent(StringBuilder result, int depth) {
            result.append("  ".repeat(depth));
        }
    }
}
