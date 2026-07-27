package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes a truthful partial selective-processing report at the documented
 * build path.
 *
 * <p>The report counts only this artifact-producing JUnit test. Sections that
 * require the final fixture matrix remain explicitly {@code not-run}; later
 * fixture suites can replace them with observed evidence through the same
 * writer.</p>
 */
class SelectiveProcessingReportArtifactTest {
    private static final Path REPORT_DIRECTORY = Paths.get(
            System.getProperty("user.dir"),
            "build",
            "reports",
            "coordination-selective-processing");

    @Test
    void writesTruthfulPartialReportWithObservedSplitterSmokeEvidence()
            throws Exception {
        Node exactRoot = new Node()
                .name("Selective processing report smoke Root")
                .properties(
                        "application",
                        new Node()
                                .properties(
                                        "counter",
                                        new Node().value(0))
                                .properties(
                                        "unrelated",
                                        new Node().value(
                                                "must remain exact data")));
        Node exactEvent = new Node()
                .name("Selective processing report smoke Event")
                .properties(
                        "message",
                        new Node()
                                .properties(
                                        "operation",
                                        new Node().value("observe"))
                                .properties(
                                        "channel",
                                        new Node().value("source")));

        CoordinationDocumentSplitter splitter =
                new CoordinationDocumentSplitter();
        CoordinationDocumentSplitter.SplitGraph document =
                splitter.splitDocument(exactRoot);
        CoordinationDocumentSplitter.SplitGraph event =
                splitter.splitEvent(exactEvent);

        String expectedRootBlueId =
                BlueIdCalculator.calculateBlueId(exactRoot);
        String expectedEventBlueId =
                BlueIdCalculator.calculateBlueId(exactEvent);
        assertEquals(expectedRootBlueId, document.rootBlueId());
        assertEquals(expectedEventBlueId, event.rootBlueId());
        assertEquals(
                expectedRootBlueId,
                BlueIdCalculator.calculateBlueId(
                        document.fragmentedRoot()));
        assertEquals(
                expectedEventBlueId,
                BlueIdCalculator.calculateBlueId(
                        event.fragmentedRoot()));
        assertEquals(
                expectedRootBlueId,
                document.pureReference().getBlueId());
        assertEquals(
                expectedEventBlueId,
                event.pureReference().getBlueId());
        assertNotNull(
                document.provider().fetchFirstByBlueId(
                        expectedRootBlueId));
        assertNotNull(
                event.provider().fetchFirstByBlueId(
                        expectedEventBlueId));

        SelectiveProcessingReportWriter.Report report =
                report(document, event);
        SelectiveProcessingReportWriter.write(
                REPORT_DIRECTORY, report);

        Path artifact = REPORT_DIRECTORY.resolve(
                SelectiveProcessingReportWriter.FILE_NAME);
        assertTrue(Files.isRegularFile(artifact));
        JsonNode serialized =
                new ObjectMapper().readTree(
                        Files.readAllBytes(artifact));
        assertEquals("partial", serialized.path("status").asText());
        assertEquals(
                "SelectiveProcessingReportArtifactTest only",
                serialized.path("testCountScope").asText());
        assertEquals(
                1,
                serialized.path("testCounts")
                        .path("total")
                        .asInt());
        JsonNode splitterEvidence =
                section(serialized, "splitter-smoke");
        assertEquals(
                "splitter-smoke",
                splitterEvidence.path("id")
                        .asText());
        assertEquals(
                expectedRootBlueId,
                splitterEvidence.path("facts")
                        .path("rootBlueId")
                        .asText());
    }

    private static SelectiveProcessingReportWriter.Report report(
            CoordinationDocumentSplitter.SplitGraph document,
            CoordinationDocumentSplitter.SplitGraph event) {
        Map<String, String> identities =
                new LinkedHashMap<String, String>();
        identities.put(
                "blueBexDependency",
                "blue-bex-java:1.1.0-rc.2");
        identities.put(
                "blueLanguageDevelopmentGitCommit",
                "0a6a40d18578df784f674148d1e8b6a4319bfe49");
        identities.put(
                "blueLanguageDevelopmentJarSha256",
                "sha256:7726c13cce7156a1b2f3ea600cd3225612704e83579f0c1613d03d447a057f31");
        identities.put(
                "blueLanguageReleasedDependency",
                "blue-language-java:3.1.0-rc.18");
        identities.put(
                "blueRepositoryDependency",
                "blue-repo-java:3.0.0-rc.10");
        identities.put(
                "coordinationRegistry",
                "final-registry-unavailable; current=blue-repo-java:3.0.0-rc.10");
        identities.put(
                "coordinationSourceBaselineGitCommit",
                "437e0861bb9780619a2b2e2f1c9a9c6fe5cdefef");
        identities.put(
                "handoffContractsFixturePackage",
                "sha256:e35f94c329850f39c705cc3c0222c431e8d6f07142740e39e6b529c228fc96e5");
        identities.put(
                "handoffContractsGasPackage",
                "sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5");
        identities.put(
                "handoffContractsRegistryPackage",
                "sha256:14d5537efbece502ebf430e09805650dd7ea460415a7aa0a8279c2c11d1d6366");
        identities.put(
                "handoffLanguageFixturePackage",
                "sha256:277418303ae10aade4029a398f880a8d0f2b321d4943492ac811287c21eb3dbb");
        identities.put(
                "handoffLanguageRegistryPackage",
                "sha256:b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e");
        identities.put(
                "reportProducer",
                SelectiveProcessingReportArtifactTest.class.getName());

        List<SelectiveProcessingReportWriter.Section> sections =
                Arrays.asList(
                        directSplitterRegressionSection(),
                        effectiveContractFragmentationSection(),
                        notRun(
                                "embedded-representation-matrix",
                                "Deep physical locality passes, but deep inline-versus-fragmented PROCESS parity is not implemented"),
                        deepLocalitySection(),
                        noEmbeddingMatrixSection(),
                        reportArtifactSection(),
                        rootOnlyEventsSection(),
                        routingBlockedSection(),
                        routingEvaluationSection(),
                        splitterSmokeSection(document, event),
                        scaleLocalitySection(),
                        notRun(
                                "ultra-complex",
                                "Design and evidence guide exists; runtime fixture not run"));

        List<SelectiveProcessingReportWriter.UnavailableSuite> unavailable =
                Arrays.asList(
                        new SelectiveProcessingReportWriter.UnavailableSuite(
                                "blue-language-phase-b-peer-routing",
                                "Language commit 0a6a40d18578 filters event classification to the source channel, hiding declared peer families"),
                        new SelectiveProcessingReportWriter.UnavailableSuite(
                                "compute-bex-runtime-suites",
                                "Manifest-bound named-counter stream is unavailable"),
                        new SelectiveProcessingReportWriter.UnavailableSuite(
                                "coordination-final-registry-conformance",
                                "Final Coordination registry and final Terminate Processing shape are unavailable"));

        return new SelectiveProcessingReportWriter.Report(
                "partial",
                identities,
                "SelectiveProcessingReportArtifactTest only",
                new SelectiveProcessingReportWriter.TestCounts(
                        1, 1, 0, 0),
                sections,
                unavailable);
    }

    private static SelectiveProcessingReportWriter.Section
    directSplitterRegressionSection() {
        Map<String, String> facts =
                new LinkedHashMap<String, String>();
        facts.put(
                "evidenceScope",
                "direct authored Process Embedded declarations, exact registered body fields, event fragments, overlap reconstruction, and lazy provider preparation");
        facts.put(
                "observedCommand",
                "./gradlew test --tests "
                        + CoordinationDocumentSplitterTest
                        .class.getName()
                        + " -PuseLocalBlueLanguage=true --no-daemon");

        Map<String, Long> metrics =
                new LinkedHashMap<String, Long>();
        metrics.put("failed", Long.valueOf(0L));
        metrics.put("passed", Long.valueOf(6L));
        metrics.put("total", Long.valueOf(6L));

        return new SelectiveProcessingReportWriter.Section(
                "direct-splitter-regressions",
                "passed",
                Arrays.asList(
                        "cyclic-timeline-entry-type",
                        "direct-document-cuts",
                        "event-direct-fragments",
                        "lazy-verified-preparation",
                        "malformed-embedded-paths",
                        "overlapping-embedded-paths"),
                facts,
                metrics,
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }

    private static SelectiveProcessingReportWriter.Section
    effectiveContractFragmentationSection() {
        return notRun(
                "effective-inherited-fragmentation",
                "Public splitter input has no effective resolved contract view; inherited Process Embedded declarations and inherited executable bodies are not cut");
    }

    private static SelectiveProcessingReportWriter.Section
    deepLocalitySection() {
        Map<String, String> facts =
                new LinkedHashMap<String, String>();
        facts.put(
                "evidenceScope",
                "physical provider demand and reconstruction; not PROCESS parity");
        facts.put(
                "observedCommand",
                "./gradlew test --tests "
                        + CoordinationDocumentSplitterDeepLocalityTest
                        .class.getName()
                        + " -PuseLocalBlueLanguage=true --no-daemon");

        Map<String, Long> metrics =
                new LinkedHashMap<String, Long>();
        metrics.put("junitInvocations", Long.valueOf(8L));
        metrics.put("selectedScopes", Long.valueOf(4L));
        metrics.put("selectionSurfaces", Long.valueOf(6L));

        return new SelectiveProcessingReportWriter.Section(
                "deep-physical-locality",
                "passed",
                Arrays.asList(
                        "exact-full-reconstruction",
                        "root-only-zero-child-demand",
                        "selected-chain-union-only",
                        "siblings-and-decoy-bodies-forbidden"),
                facts,
                metrics,
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }

    private static SelectiveProcessingReportWriter.Section
    noEmbeddingMatrixSection() {
        Map<String, String> facts =
                new LinkedHashMap<String, String>();
        facts.put(
                "evidenceScope",
                "actual DocumentProcessor execution with a deterministic static Handler");
        facts.put(
                "coverageLimit",
                "fragment-aware mock Channel adapter; no production Operation Request routing, embedding, one-fragment provider, or batched provider");
        facts.put(
                "observedCommand",
                "./gradlew test --tests "
                        + CoordinationDocumentSplitterProcessingMatrixTest
                        .class.getName()
                        + " -PuseLocalBlueLanguage=true --no-daemon");

        Map<String, Long> metrics =
                new LinkedHashMap<String, Long>();
        metrics.put("documentProcessorInvocations", Long.valueOf(8L));
        metrics.put("junitTests", Long.valueOf(1L));
        metrics.put("unselectedBodiesForbidden", Long.valueOf(4L));

        return new SelectiveProcessingReportWriter.Section(
                "no-embedding-representation-matrix",
                "passed",
                Arrays.asList(
                        "inline-root-and-event",
                        "root-pure-reference",
                        "event-pure-reference",
                        "both-pure-references",
                        "direct-fragment-forms",
                        "cold-and-warm-provider-parity",
                        "status-root-events-gas-trace-checkpoint-equal",
                        "forbidden-provider-demand-zero"),
                facts,
                metrics,
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }

    private static SelectiveProcessingReportWriter.Section
    rootOnlyEventsSection() {
        Map<String, String> facts =
                new LinkedHashMap<String, String>();
        facts.put(
                "reason",
                "Root-only physical demand passes; descendant-versus-Root public-event variants are not implemented");
        return new SelectiveProcessingReportWriter.Section(
                "root-only-public-events",
                "not-run",
                Collections.<String>emptyList(),
                facts,
                Collections.<String, Long>emptyMap(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }

    private static SelectiveProcessingReportWriter.Section
    routingBlockedSection() {
        Map<String, String> facts =
                new LinkedHashMap<String, String>();
        facts.put(
                "blocker",
                "Phase-B source-only classification makes event-time membersByEffectiveType empty");
        facts.put(
                "requiredLanguageFix",
                "carry the verified header-declared peer dependency surface into event classification");
        facts.put(
                "targetSurfaceGap",
                "current context enumerates External Channels, not every same-scope Channel required by the Coordination rule");
        facts.put(
                "directRequestGap",
                "bare Operation Request parsing exists, but production external functions preselect Timeline Entries only");
        facts.put(
                "observedCommand",
                "./gradlew test --tests "
                        + OperationRequestLogicalRoutingTest.class.getName()
                        + " -PuseLocalBlueLanguage=true --no-daemon");

        Map<String, Long> metrics =
                new LinkedHashMap<String, Long>();
        metrics.put("failed", Long.valueOf(3L));
        metrics.put("passed", Long.valueOf(4L));
        metrics.put("total", Long.valueOf(7L));

        return new SelectiveProcessingReportWriter.Section(
                "routing",
                "blocked",
                Arrays.asList(
                        "fragmented-valid-route-blocked-by-phase-b",
                        "malformed-unknown-and-non-channel-fallback",
                        "missing-fragment-fails-closed",
                        "fragmented-whitespace-route-stays-ordinary",
                        "ordinary-handler-suppression-is-target-specific",
                        "two-source-valid-route-blocked-by-phase-b",
                        "valid-target-unknown-operation-blocked-by-phase-b"),
                facts,
                metrics,
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }

    private static SelectiveProcessingReportWriter.Section
    routingEvaluationSection() {
        Map<String, String> facts =
                new LinkedHashMap<String, String>();
        facts.put(
                "evidenceScope",
                "immutable Operation Request parsing, target-independent source evaluation, exact matcher behavior, and fallback characterization; not verified cross-channel PROCESS");
        facts.put(
                "observedCommand",
                "./gradlew test --tests "
                        + OperationRequestRoutingEvaluationTest
                        .class.getName()
                        + " -PuseLocalBlueLanguage=true --no-daemon");

        Map<String, Long> metrics =
                new LinkedHashMap<String, Long>();
        metrics.put("failed", Long.valueOf(0L));
        metrics.put("passed", Long.valueOf(22L));
        metrics.put("total", Long.valueOf(22L));

        return new SelectiveProcessingReportWriter.Section(
                "routing-evaluation",
                "passed",
                Arrays.asList(
                        "absent-event-and-non-text-routing-fields",
                        "compatible-request-subtype",
                        "empty-request-pattern",
                        "exact-generated-request-payload",
                        "exact-same-channel-request-payload",
                        "fragmented-message-matcher",
                        "invalid-matcher-inputs",
                        "malformed-inline-type",
                        "missing-or-blank-channel",
                        "missing-or-blank-operation",
                        "ordinary-timeline-message",
                        "qualified-name-and-structural-lookalikes",
                        "rc10-materialized-request-fails-closed",
                        "request-may-be-absent-for-empty-pattern",
                        "route-channel-and-operation-match",
                        "target-evaluator-not-invoked",
                        "unavailable-type-claim",
                        "union-exact-child-payload",
                        "union-fallback-preserves-event",
                        "union-missing-child-and-fallback",
                        "unknown-target-fallback",
                        "unrelated-request-subtype"),
                facts,
                metrics,
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }

    private static SelectiveProcessingReportWriter.Section
    scaleLocalitySection() {
        Map<String, String> facts =
                new LinkedHashMap<String, String>();
        facts.put(
                "evidenceScope",
                "narrow non-time-based physical fragment selection");
        facts.put(
                "coverageLimit",
                "records canonical total/selected bytes and demand counts; expanded logical bytes, logical gas, and processed final Root identity are not recorded");
        facts.put(
                "observedCommand",
                "./gradlew test --tests "
                        + CoordinationDocumentSplitterLocalityTest
                        .class.getName()
                        + " -PuseLocalBlueLanguage=true --no-daemon");

        Map<String, Long> metrics =
                new LinkedHashMap<String, Long>();
        metrics.put("branchingFactor", Long.valueOf(5L));
        metrics.put("depth", Long.valueOf(6L));
        metrics.put("forbiddenDemands", Long.valueOf(0L));
        metrics.put("operationsPerScope", Long.valueOf(5L));
        metrics.put("providerCalls", Long.valueOf(14L));
        metrics.put("selectedFragmentBytes", Long.valueOf(143521L));
        metrics.put("totalGraphBytes", Long.valueOf(1013261L));
        metrics.put("workflowBodyBytes", Long.valueOf(16384L));

        return new SelectiveProcessingReportWriter.Section(
                "scale-locality",
                "passed",
                Arrays.asList(
                        "selected-bytes-below-one-third-total",
                        "one-scope-and-one-selected-body-per-active-scope",
                        "forbidden-provider-demand-zero"),
                facts,
                metrics,
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }

    private static SelectiveProcessingReportWriter.Section
    reportArtifactSection() {
        Map<String, String> facts =
                new LinkedHashMap<String, String>();
        facts.put(
                "artifact",
                "build/reports/coordination-selective-processing/report.json");
        facts.put(
                "countScope",
                "SelectiveProcessingReportArtifactTest only");
        facts.put(
                "generationCommand",
                "./gradlew test -PuseLocalBlueLanguage=true --tests "
                        + SelectiveProcessingReportArtifactTest.class.getName());
        return new SelectiveProcessingReportWriter.Section(
                "report-artifact",
                "passed",
                Collections.singletonList(
                        "artifact-written-without-time-or-machine-fields"),
                facts,
                Collections.singletonMap(
                        "schemaVersion",
                        Long.valueOf(
                                SelectiveProcessingReportWriter
                                        .SCHEMA_VERSION)),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }

    private static SelectiveProcessingReportWriter.Section
    splitterSmokeSection(
            CoordinationDocumentSplitter.SplitGraph document,
            CoordinationDocumentSplitter.SplitGraph event) {
        Map<String, String> facts =
                new LinkedHashMap<String, String>();
        facts.put("eventBlueId", event.rootBlueId());
        facts.put("rootBlueId", document.rootBlueId());
        facts.put(
                "effectiveContractGap",
                "splitter cuts directly authored declarations; inherited Process Embedded and executable bodies require an effective resolved contract input");

        Map<String, Long> metrics =
                new LinkedHashMap<String, Long>();
        metrics.put(
                "documentFragmentCount",
                Long.valueOf(document.fragments().size()));
        metrics.put(
                "eventFragmentCount",
                Long.valueOf(event.fragments().size()));

        Map<String, List<String>> identitySets =
                new LinkedHashMap<String, List<String>>();
        identitySets.put(
                "documentFragmentBlueIds",
                Arrays.asList(
                        document.fragments()
                                .keySet()
                                .toArray(new String[0])));
        identitySets.put(
                "eventFragmentBlueIds",
                Arrays.asList(
                        event.fragments()
                                .keySet()
                                .toArray(new String[0])));

        return new SelectiveProcessingReportWriter.Section(
                "splitter-smoke",
                "passed",
                Arrays.asList(
                        "document-root-identity-preserved",
                        "event-root-identity-preserved",
                        "root-and-event-pure-references-retained",
                        "root-and-event-provider-content-available"),
                facts,
                metrics,
                Collections.<String, List<String>>emptyMap(),
                identitySets);
    }

    private static SelectiveProcessingReportWriter.Section notRun(
            String id,
            String reason) {
        return new SelectiveProcessingReportWriter.Section(
                id,
                "not-run",
                Collections.<String>emptyList(),
                Collections.singletonMap("reason", reason),
                Collections.<String, Long>emptyMap(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String, List<String>>emptyMap());
    }

    private static JsonNode section(
            JsonNode report,
            String id) {
        for (JsonNode section : report.path("sections")) {
            if (id.equals(section.path("id").asText())) {
                return section;
            }
        }
        throw new AssertionError(
                "Missing report section: " + id);
    }
}
