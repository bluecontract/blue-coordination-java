package blue.coordination.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalReleaseTruthfulnessTest {
    private static final Path PROJECT_DIRECTORY =
            Paths.get(
                            System.getProperty(
                                    "user.dir"))
                    .toAbsolutePath()
                    .normalize();
    private static final ObjectMapper JSON =
            new ObjectMapper();

    @Test
    void shouldApplyTheDedicatedReleaseScriptAndGatePublication()
            throws Exception {
        // Given
        String build = read("build.gradle");

        // When
        boolean appliesDedicatedScript =
                build.contains(
                        "apply from: "
                                + "'gradle/coordination-release.gradle'");
        int publicationGates =
                occurrences(
                        build,
                        "dependsOn tasks.named("
                                + "'finalCoordinationVerification')");

        // Then
        assertTrue(appliesDedicatedScript);
        assertEquals(
                4,
                publicationGates,
                "remote, local, aggregate, and release publication "
                        + "must all use the hard release gate");
    }

    @Test
    void shouldPreserveTheDurablePreEditBaselineAfterClean()
            throws Exception {
        // Given
        JsonNode baseline =
                json(
                        "gradle/"
                                + "coordination-release-baseline.json");
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        JsonNode fullTest =
                baseline.path("fullTest");

        // Then
        assertEquals(
                "blue.coordination/release-baseline/1.0",
                baseline.path("schema").asText());
        assertEquals(
                "before-release-ready-production-edits",
                baseline.path("sourcePhase").asText());
        assertEquals(781, fullTest.path("total").asInt());
        assertEquals(542, fullTest.path("passed").asInt());
        assertEquals(239, fullTest.path("failed").asInt());
        assertEquals(
                123,
                fullTest.path(
                                "failedBecauseOfCoordinationBehavior")
                        .asInt());
        assertEquals(
                116,
                fullTest.path(
                                "failedBeforeCoordinationBehavior"
                                        + "BecauseOfDependencyEvidence")
                        .asInt());
        assertEquals(0, fullTest.path("skipped").asInt());
        assertEquals(0, fullTest.path("notExecuted").asInt());
        assertFalse(
                baseline.path("releaseEligible")
                        .asBoolean());
        assertFalse(
                baseline.path("sourceLock")
                        .path(
                                "allSiblingWorkingTreesReleaseClean")
                        .asBoolean());
        assertTrue(
                release.contains(
                        "file('gradle/"
                                + "coordination-release-baseline.json')"));
        assertTrue(
                release.contains(
                        "'reports/coordination-release/"
                                + "baseline.json'"));
        assertTrue(
                release.contains(
                        "target.bytes = baselineSource.bytes"));
    }

    @Test
    void shouldCaptureAllTestsAsSameRunReleaseEvidence()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        boolean ownsFullTestClasspath =
                release.contains(
                        "testClassesDirs =\n"
                                + "            "
                                + "sourceSets.test.output.classesDirs")
                        && release.contains(
                        "classpath =\n"
                                + "            "
                                + "sourceSets.test.runtimeClasspath");
        boolean rerunsAndRetainsRedEvidence =
                release.contains("ignoreFailures = true")
                        && occurrences(
                        release,
                        "outputs.upToDateWhen { false }")
                        >= 3;
        boolean finalReportReadsSameRunXml =
                release.contains(
                        "'test-results/"
                                + "coordinationReleaseEvidenceTest'")
                        && release.contains(
                        "readReleaseJUnit(\n"
                                + "                        "
                                + "releaseTestResults");
        boolean clearsDerivedEvidenceBeforeTests =
                release.contains(
                        "doFirst {\n"
                                + "        delete(\n"
                                + "                "
                                + "releaseFlagshipEvidence")
                        && release.contains(
                        "releaseLoopEvidence\n"
                                + "                        "
                                + ".get().asFile,")
                        && release.contains(
                        "releaseFixedRepositoryEvidence\n"
                                + "                        "
                                + ".get().asFile)");
        boolean bindsFixedAuditToEvidenceRun =
                release.contains(
                        "'coordination.fixed.repository.report'")
                        && release.contains(
                        "releaseFixedRepositoryEvidence\n"
                                + "                    "
                                + ".get().asFile.absolutePath");

        // Then
        assertTrue(
                release.contains(
                        "'coordinationReleaseEvidenceTest'"));
        assertTrue(ownsFullTestClasspath);
        assertTrue(rerunsAndRetainsRedEvidence);
        assertTrue(finalReportReadsSameRunXml);
        assertTrue(clearsDerivedEvidenceBeforeTests);
        assertTrue(bindsFixedAuditToEvidenceRun);
        assertTrue(
                release.contains(
                        "if (tests.failed != 0L\n"
                                + "                "
                                + "|| tests.skipped != 0L)"));
    }

    @Test
    void shouldRequireExactSameRunConformanceAndFlagshipEvidence()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        boolean requiresConformance =
                release.contains(
                        "'CoordinationBehaviorFixtureHarnessTest',\n"
                                + "                65L,")
                        && release.contains(
                        "'CoordinationDirectPortableGas"
                                + "MicrofixtureTest',\n"
                                + "                14L,")
                        && release.contains(
                        "'CoordinationHostQuotaFixtureTest',\n"
                                + "                7L,")
                        && release.contains(
                        "required   : 86L");
        boolean excludesSupportTests =
                release.contains(
                        "String exactNamePattern ->")
                        && release.contains(
                        "pattern.matcher(\n"
                                + "                    it.name)\n"
                                + "                    .matches()")
                        && release.contains(
                        "'^[0-9]+: coord-(?:chan|e2e|fail|mand|route|"
                                + "split|time|wf)-[0-9]+@[a-z0-9-]+$'")
                        && release.contains(
                        "'^shouldExecuteDirectPortableGasMicrofixture'")
                        && release.contains(
                        "'^coordination-host-[a-z0-9-]+ '");
        boolean requiresExactExecutionCounts =
                release.contains(
                        "if (behavior.executed != 65L")
                        && release.contains(
                        "|| portableGas.executed != 14L")
                        && release.contains(
                        "|| hostQuota.executed != 7L")
                        && release.contains(
                        "|| totalConformance.executed != 86L");
        boolean requiresFlagship =
                release.contains(
                        "'CoordinationComplexEmbedded"
                                + "DeterminismFlagshipTest'")
                        && release.contains(
                        "flagshipRuns != 32L");
        boolean requiresRepeatedCounterTrace =
                release.contains(
                        "'CoordinationRuntimeGasScalingTest'")
                        && release.contains(
                        "traceEntries.longValue()\n"
                                + "                        "
                                + "== 516L");
        boolean requiresProjectionRuntimeIdentities =
                release.contains(
                        "coordination."
                                + "subscriptionProjectionAlgorithmIdentity=")
                        && release.contains(
                        "coordination.runtimeRegistryIdentity=")
                        && release.contains(
                        "projectionAlgorithmIdentity == null\n"
                                + "                "
                                + "|| coordinationRuntimeRegistryIdentity "
                                + "== null");

        // Then
        assertTrue(requiresConformance);
        assertTrue(excludesSupportTests);
        assertTrue(requiresExactExecutionCounts);
        assertTrue(requiresFlagship);
        assertTrue(requiresRepeatedCounterTrace);
        assertTrue(requiresProjectionRuntimeIdentities);
    }

    @Test
    void shouldFailClosedWhenConformanceGasManifestBindingsAreStale()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        boolean readsBothDeclaredBindings =
                release.contains(
                        "'portableGasRawSha256'")
                        && release.contains(
                        "'hostQuotaRawSha256'");
        boolean comparesBothObservedManifests =
                release.contains(
                        "portableGasManifestBindingMatches")
                        && release.contains(
                        "hostQuotaManifestBindingMatches")
                        && release.contains(
                        "observedPortableGasRawSha256")
                        && release.contains(
                        "observedHostQuotaRawSha256");
        boolean reportsMismatchAsBlocker =
                release.contains(
                        "The conformance package gas-manifest byte "
                                + "bindings ")
                        && release.contains(
                        "are missing or stale:");

        // Then
        assertTrue(readsBothDeclaredBindings);
        assertTrue(comparesBothObservedManifests);
        assertTrue(reportsMismatchAsBlocker);
    }

    @Test
    void shouldClassifyOnlyExplicitDependencyEvidenceAsPreCoordination()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        boolean classifierUsesTestIdentity =
                release.contains(
                        "String className,\n"
                                + "    String testName,\n"
                                + "    String message ->")
                        && release.contains(
                        "testCase.@classname")
                        && release.contains(
                        "testCase.@name");
        boolean hasExplicitAttribution =
                release.contains(
                        "fixedRepositoryAudit")
                        && release.contains(
                        "fixedMandateEvidence")
                        && release.contains(
                        "explicitlyAttributedLanguageFailure")
                        && release.contains(
                        "Language invalid-execution-evidence ")
                        && release.contains(
                        "Language Process Embedded routing defect:")
                        && release.contains(
                        "Language handler-match reference "
                                + "materialization ")
                        && release.contains(
                        "Language flagship external-delivery "
                                + "evidence drift:")
                        && release.contains(
                        "Language hosted BEX semantic-output "
                                + "provenance defect:")
                        && release.contains(
                        "Language Embedded Node Channel bridge defect:")
                        && release.contains(
                        "BEX admitted-exact canonical "
                                + "materialization defect:")
                        && release.contains(
                        "Language pure-reference Root transition defect:")
                        && release.contains(
                        "fixedBexConformanceFailure");
        boolean usesExactTestAllowLists =
                release.contains(
                        "String testId =")
                        && release.contains(
                        "fixedRepositoryAuditTestIds")
                        && release.contains(
                        "checkpointCoalescingTestIds")
                        && release.contains(
                        "invalidExecutionEvidenceTestIds")
                        && release.contains(
                        "processEmbeddedRoutingTestIds")
                        && release.contains(
                        "handlerMaterializationTestIds")
                        && release.contains(
                        "flagshipDeliveryEvidenceTestIds")
                        && release.contains(
                        "hostedBexOutputTestIds")
                        && release.contains(
                        "embeddedBridgeTestIds")
                        && release.contains(
                        "admittedExactBexTestIds")
                        && release.contains(
                        "pureReferenceRootTransitionTestIds")
                        && release.contains(
                        "fixedRepositoryAuditTestIds.contains")
                        && release.contains(
                        "checkpointCoalescingTestIds.contains")
                        && release.contains(
                        "processEmbeddedRoutingTestIds.contains");

        // Then
        assertTrue(classifierUsesTestIdentity);
        assertTrue(hasExplicitAttribution);
        assertTrue(usesExactTestAllowLists);
        assertFalse(
                release.contains(
                        "def dependencyMarkers"));
        assertFalse(
                release.contains(
                        "'ExecutionEvidenceUnavailableException',"));
        assertFalse(
                release.contains(
                        "'Schema validation failed',"));
    }

    @Test
    void shouldRejectDocumentedBinaryCompatibilityBreaks()
            throws Exception {
        // Given
        String build = read("build.gradle");
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        boolean binaryTaskFailsAllBreaks =
                build.contains(
                        "compatible=${normalizedProblems.isEmpty()}")
                        && build.contains(
                        "if (!normalizedProblems.isEmpty())")
                        && build.contains(
                        "documentedPreFinalRemoval=");
        boolean finalReceiptSurfacesBreaks =
                release.contains(
                        "binaryCompatibilityBreaks")
                        && release.contains(
                        "'documentedPreFinalRemoval='")
                        && release.contains(
                        "binaryCompatibilityBreaks\n"
                                + "                        "
                                + ".isEmpty()");

        // Then
        assertTrue(binaryTaskFailsAllBreaks);
        assertTrue(finalReceiptSurfacesBreaks);
        assertFalse(
                build.contains(
                        "compatible=${unexpectedProblems.isEmpty()}"));
    }

    @Test
    void shouldBindConformanceReceiptToHostQuotaManifestBytes()
            throws Exception {
        // Given
        String build = read("build.gradle");
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");
        JsonNode schema =
                json(
                        "src/test/resources/coordination/"
                                + "conformance-result.schema.json");

        // When
        JsonNode required =
                schema.path("required");
        JsonNode properties =
                schema.path("properties");

        // Then
        assertTrue(
                build.contains(
                        "'hostQuotaSchedule'"));
        assertTrue(
                build.contains(
                        "'hostQuotaManifestSha256'"));
        assertTrue(
                release.contains(
                        "hostQuotaSchedule:\n"
                                + "                        "
                                + "hostQuotaScheduleIdentity"));
        assertTrue(
                release.contains(
                        "hostQuotaManifestSha256:\n"
                                + "                        "
                                + "artifacts.hostQuotaManifestSha256"));
        assertTrue(
                containsText(
                        required,
                        "hostQuotaSchedule"));
        assertTrue(
                containsText(
                        required,
                        "hostQuotaManifestSha256"));
        assertEquals(
                "blue-coordination/host-quotas/1.0",
                properties.path(
                                "hostQuotaSchedule")
                        .path("const")
                        .asText());
        assertTrue(
                properties.path(
                                "hostQuotaManifestSha256")
                        .path("const")
                        .asText()
                        .matches("[0-9a-f]{64}"));
    }

    @Test
    void shouldKeepManifestCompatibilitySeparateFromTheCatalogAudit()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        boolean requiresCompleteCatalogAudit =
                release.contains(
                        "fixedCatalog.total == 1107L")
                        && release.contains(
                        "fixedCatalog.verified\n"
                                + "                        "
                                + "== fixedCatalog.total")
                        && release.contains(
                        "fixedCatalog.failed == 0L")
                        && release.contains(
                        "fixedCatalog.cyclicSetCount == 10L")
                        && release.contains(
                        "fixedCatalog.cyclicMemberCount == 27L")
                        && release.contains(
                        "BOUND_SOURCE_CONTENT audit is not green");
        int fixedRepository =
                release.indexOf("fixedRepository:");
        int expectedManifest =
                release.indexOf(
                        "expectedManifestBlueId:",
                        fixedRepository);
        int observedManifest =
                release.indexOf(
                        "observedManifestBlueId:",
                        expectedManifest);
        int manifestCompatible =
                release.indexOf(
                        "manifestCompatible:",
                        observedManifest);
        int catalogAudit =
                release.indexOf(
                        "catalogAudit:",
                        manifestCompatible);

        // Then
        assertTrue(requiresCompleteCatalogAudit);
        assertTrue(fixedRepository >= 0);
        assertTrue(expectedManifest > fixedRepository);
        assertTrue(observedManifest > expectedManifest);
        assertTrue(manifestCompatible > observedManifest);
        assertTrue(catalogAudit > manifestCompatible);
        assertTrue(
                release.contains(
                        "releaseFixedRepositoryEvidence\n"
                                + "                        "
                                + ".get().asFile"));
    }

    @Test
    void shouldBindFixedRepositoryAuditToExactSameRunIdentities()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");
        String writer =
                read(
                        "src/test/java/blue/coordination/processor/"
                                + "FixedRepositoryBoundSourceProviderTest.java");

        // When
        boolean validatesAuditEnvelope =
                release.contains(
                        "fixedCatalog.schema")
                        && release.contains(
                        "fixedCatalog.status == 'verified'")
                        && release.contains(
                        "fixedCatalog.providerMode\n"
                                + "                        "
                                + "== 'BOUND_SOURCE_CONTENT'");
        boolean validatesRepositoryIdentity =
                release.contains(
                        "fixedCatalog.repositoryCoordinate\n"
                                + "                        "
                                + "== coordinates.repository.coordinate")
                        && release.contains(
                        "fixedCatalog.repositoryVersion\n"
                                + "                        "
                                + "== repositoryManifestValue."
                                + "repositoryVersion")
                        && release.contains(
                        "fixedCatalog.repositoryManifestBlueId")
                        && release.contains(
                        "fixedCatalog.repositoryManifestSha256\n"
                                + "                        "
                                + "== artifacts."
                                + "fixedRepositoryManifestSha256")
                        && release.contains(
                        "fixedCatalog.repositoryCommit\n"
                                + "                        "
                                + "== coordinates.repository.commit")
                        && release.contains(
                        "fixedCatalog.repositoryArtifactSha256\n"
                                + "                        "
                                + "== artifacts.repositoryJarSha256");
        boolean writerEmitsRequiredFields =
                writer.contains(
                        "\"status\",\n"
                                + "                audit.failed() == 0")
                        && writer.contains(
                        "\"repositoryManifestSha256\",\n"
                                + "                "
                                + "repositoryManifestSha256()")
                        && writer.contains(
                        "\"providerMode\",\n"
                                + "                "
                                + "\"BOUND_SOURCE_CONTENT\"")
                        && writer.contains(
                        "\"cyclicSetCount\"")
                        && writer.contains(
                        "\"cyclicMemberCount\"");

        // Then
        assertTrue(validatesAuditEnvelope);
        assertTrue(validatesRepositoryIdentity);
        assertTrue(writerEmitsRequiredFields);
        assertTrue(
                release.contains(
                        "sameRunIdentityMatch:\n"
                                + "                                        "
                                + "fixedCatalogIdentityMatches"));
    }

    @Test
    void shouldRejectMissingOrMalformedDependencyArtifactDigests()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        boolean validatesAllDependencyDigests =
                release.contains(
                        "language  : artifacts.languageJarSha256")
                        && release.contains(
                        "bex       : artifacts.bexJarSha256")
                        && release.contains(
                        "repository: artifacts.repositoryJarSha256")
                        && release.contains(
                        "if (!(value instanceof String)\n"
                                + "                    "
                                + "|| !(value ==~ /[0-9a-f]{64}/))")
                        && release.contains(
                        "dependency artifact SHA-256 is ")
                        && release.contains(
                        "missing or malformed.");

        // Then
        assertTrue(validatesAllDependencyDigests);
    }

    @Test
    void shouldDeriveTimelineAndFragmentIdentitiesFromProjectSources()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        boolean derivesTimelineIdentity =
                release.contains(
                        "javaReleaseStringConstant(\n"
                                + "                        "
                                + "timelineProjectionSource,\n"
                                + "                        "
                                + "'VERSION')")
                        && release.contains(
                        "yamlProjectionVersionRelease(\n"
                                + "                        "
                                + "projectionCatalog,\n"
                                + "                        "
                                + "'timeline-entry-subscription')")
                        && release.contains(
                        "timelineEntryProjectionIdentity\n"
                                + "                "
                                + "!= catalogTimelineEntryProjectionIdentity");
        boolean derivesFragmentIdentity =
                release.contains(
                        "javaReleaseStringConstant(\n"
                                + "                        "
                                + "documentSplitterSource,\n"
                                + "                        "
                                + "'FRAGMENTATION_PROFILE_ID')")
                        && release.contains(
                        "fragmentationProfileIdentity:\n"
                                + "                        "
                                + "fragmentationProfileIdentity");

        // Then
        assertTrue(derivesTimelineIdentity);
        assertTrue(derivesFragmentIdentity);
        assertFalse(
                release.contains(
                        "timelineEntryProjectionIdentity:\n"
                                + "                        "
                                + "'blue.coordination/"));
        assertFalse(
                release.contains(
                        "fragmentationProfileIdentity:\n"
                                + "                        "
                                + "'blue.coordination/"));
    }

    @Test
    void shouldWriteExactDynamicReleaseReportsForGreenAndRedCandidates()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        boolean usesExactReportPaths =
                release.contains(
                        "'reports/coordination-release/final.json'")
                        && release.contains(
                        "'reports/coordination-release/final.md'");
        boolean derivesReleaseStateFromBlockers =
                release.contains(
                        "boolean releaseEligible =\n"
                                + "                "
                                + "blockers.isEmpty()")
                        && release.contains(
                        "releaseEligible\n"
                                + "                                "
                                + "? 'complete'\n"
                                + "                                "
                                + ": 'blocked'")
                        && release.contains(
                        "blockingReasons:\n"
                                + "                        "
                                + "new ArrayList<String>");
        boolean emitsDynamicIdentities =
                release.contains(
                        "gasManifestIdentity:\n"
                                + "                        "
                                + "gasPackageIdentity")
                        && release.contains(
                        "hostQuotaScheduleIdentity:\n"
                                + "                        "
                                + "hostQuotaScheduleIdentity")
                        && release.contains(
                        "fixturePackageIdentity:\n"
                                + "                        "
                                + "fixturePackageIdentity")
                        && release.contains(
                        "coordinationRuntimeRegistry:\n"
                                + "                                        "
                                + "coordinationRuntimeRegistryIdentity")
                        && release.contains(
                        "subscriptionProjectionAlgorithmIdentity:\n"
                                + "                        "
                                + "projectionAlgorithmIdentity");
        boolean writesBothReports =
                release.contains(
                        "File jsonFile =\n"
                                + "                "
                                + "finalJsonReport.get().asFile")
                        && release.contains(
                        "File markdownFile =\n"
                                + "                "
                                + "finalMarkdownReport.get().asFile");

        // Then
        assertTrue(usesExactReportPaths);
        assertTrue(
                release.contains(
                        "'blue.coordination/release-result/1.0'"));
        assertTrue(derivesReleaseStateFromBlockers);
        assertTrue(emitsDynamicIdentities);
        assertTrue(writesBothReports);
        assertFalse(
                release.contains(
                        "reports/coordination-final/report.json"));
        assertFalse(
                release.contains(
                        "reports/coordination-final/report.md"));
    }

    @Test
    void shouldWriteCurrentReportAfterHardGateFailureAndFailClosed()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");
        String gateInventory =
                between(
                        release,
                        "def releaseRequiredGateTaskNames",
                        "def sha256FileRelease");
        String reportConfiguration =
                between(
                        release,
                        "def generateCoordinationReleaseFinalReport",
                        "outputs.files(");
        String normalized =
                release.replaceAll(
                        "\\s+",
                        " ");

        // When
        boolean inventoriesEveryIndependentGate =
                gateInventory.contains("'clean'")
                        && gateInventory.contains(
                        "'generateCoordinationBaselineReport'")
                        && gateInventory.contains(
                        "'coordinationReleaseEvidenceTest'")
                        && gateInventory.contains(
                        "'binaryCompatibilityCheck'")
                        && gateInventory.contains(
                        "'verifyJava8Bytecode'")
                        && gateInventory.contains(
                        "'verifyReproducibleArchives'")
                        && gateInventory.contains(
                        "'verifyPublishedDependencyAlignment'")
                        && gateInventory.contains("'jmh'")
                        && gateInventory.contains("'jar'")
                        && gateInventory.contains("'sourcesJar'")
                        && gateInventory.contains("'javadocJar'")
                        && gateInventory.contains("'sourceArchive'");
        boolean reportRunsAsFailureSafeFinalizer =
                normalized.contains(
                        "releaseRequiredGateTaskNames.each "
                                + "{ taskName -> tasks.named(taskName)."
                                + "configure { finalizedBy( "
                                + "generateCoordinationReleaseFinalReport) "
                                + "} }")
                        && normalized.contains(
                        "finalizedBy "
                                + "generateCoordinationReleaseFinalReport")
                        && !normalized.contains(
                        "mustRunAfter( "
                                + "releaseRequiredGateTaskNames.collect")
                        && normalized.contains(
                        "shouldRunAfter( "
                                + "releaseRequiredGateTaskNames.collect")
                        && normalized.contains(
                        "gradle.taskGraph.hasTask( "
                                + "finalCoordinationVerification.get())");
        boolean replacesStaleReportsAndFallsBack =
                normalized.contains(
                        "delete( finalJsonReport.get().asFile, "
                                + "finalMarkdownReport.get().asFile)")
                        && normalized.contains(
                        "catch (Exception reportingFailure)")
                        && normalized.contains(
                        "writeReleaseReportingFailure( "
                                + "reportingFailure)")
                        && normalized.contains(
                        "This fail-closed receipt replaced any "
                                + "previous ")
                        && normalized.contains(
                        "report from an earlier invocation.");
        boolean recordsActualTaskOutcomes =
                normalized.contains(
                        "def state = task.state")
                        && normalized.contains(
                        "state.failure != null")
                        && normalized.contains(
                        "status = 'not-executed'")
                        && normalized.contains(
                        "requiredReleaseGates: releaseGates");
        boolean validatesFinalReport =
                release.contains(
                        "if (!reportFile.isFile())")
                        && normalized.contains(
                        "report.releaseEligible != true "
                                + "|| !(report.blockingReasons "
                                + "instanceof List) "
                                + "|| !report.blockingReasons.isEmpty()")
                        && normalized.contains(
                        "report.requiredReleaseGates.values().any "
                                + "{ it.status != 'passed' }")
                        && release.contains(
                        "Coordination release remains blocked");

        // Then
        assertTrue(inventoriesEveryIndependentGate);
        assertFalse(
                gateInventory.contains(
                        "'generateCoordinationFinalReport'"),
                "the retired report must not be a release gate");
        assertFalse(
                reportConfiguration.contains("dependsOn"),
                "a report dependency can suppress red-candidate evidence");
        assertTrue(reportRunsAsFailureSafeFinalizer);
        assertTrue(replacesStaleReportsAndFallsBack);
        assertTrue(recordsActualTaskOutcomes);
        assertTrue(validatesFinalReport);
    }

    @Test
    void shouldRequireTheCompleteJmhLocalityMatrixInReleaseEvidence()
            throws Exception {
        // Given
        String release =
                read(
                        "gradle/"
                                + "coordination-release.gradle");

        // When
        boolean requiresProjectionAndSparsePlanningScales =
                release.contains(
                        "SubscriptionProjectionPlanningBenchmark."
                                + "projectCurrent")
                        && release.contains(
                        "SubscriptionProjectionPlanningBenchmark."
                                + "planSparseIndexedEvent")
                        && release.contains(
                        "parameter: 'channelCount'")
                        && release.contains("'10000'");
        boolean requiresAdmissionAndHostedExecution =
                release.contains(
                        "FragmentAdmissionBenchmark."
                                + "splitAndAdmitFreshInventory")
                        && release.contains(
                        "FragmentAdmissionBenchmark."
                                + "admitRepeatedInventory")
                        && release.contains(
                        "ResolvedProcessingHostStoryBenchmark."
                                + "resolveInitializeAndProcessFiveEvents")
                        && release.contains(
                        "ComputeEffectPlanBenchmark."
                                + "processComputeEffects");
        boolean requiresSemanticAndAllocationMetrics =
                release.contains("'plannerCandidates'")
                        && release.contains(
                        "'providerDemandCount'")
                        && release.contains(
                        "'providerDemandBytes'")
                        && release.contains(
                        "'snapshotOccurrences'")
                        && release.contains(
                        "'fragmentCount'")
                        && release.contains(
                        "'gc.alloc.rate'");

        // Then
        assertTrue(
                requiresProjectionAndSparsePlanningScales);
        assertTrue(
                requiresAdmissionAndHostedExecution);
        assertTrue(
                requiresSemanticAndAllocationMetrics);
    }

    private static JsonNode json(String relative)
            throws Exception {
        return JSON.readTree(
                PROJECT_DIRECTORY
                        .resolve(relative)
                        .toFile());
    }

    private static String read(String relative)
            throws Exception {
        return new String(
                Files.readAllBytes(
                        PROJECT_DIRECTORY.resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static boolean containsText(
            JsonNode values,
            String expected) {
        for (JsonNode value : values) {
            if (expected.equals(
                    value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String between(
            String source,
            String start,
            String end) {
        int startIndex =
                source.indexOf(start);
        int endIndex =
                source.indexOf(
                        end,
                        startIndex);
        assertTrue(
                startIndex >= 0,
                "missing start marker: " + start);
        assertTrue(
                endIndex > startIndex,
                "missing end marker: " + end);
        return source.substring(
                startIndex,
                endIndex);
    }

    private static int occurrences(
            String source,
            String needle) {
        int count = 0;
        int offset = 0;
        while (true) {
            int found =
                    source.indexOf(
                            needle,
                            offset);
            if (found < 0) {
                return count;
            }
            count++;
            offset =
                    found
                            + needle.length();
        }
    }
}
