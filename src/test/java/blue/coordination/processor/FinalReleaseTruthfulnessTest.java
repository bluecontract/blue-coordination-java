package blue.coordination.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

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
        String working =
                read(
                        "gradle/"
                                + "coordination-working.gradle");

        // When
        boolean derivesRequiredConformance =
                release.contains(
                        "sameRun.conformance\n"
                                + "                        "
                                + "?.behavior?.required")
                        && release.contains(
                        "sameRun.conformance\n"
                                + "                        "
                                + "?.portableGas?.required")
                        && release.contains(
                        "sameRun.conformance\n"
                                + "                        "
                                + "?.hostQuota?.required")
                        && release.contains(
                        "sameRun.conformance\n"
                                + "                        "
                                + "?.total?.required");
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
                        "behavior.executed\n"
                                + "                "
                                + "!= requiredBehavior")
                        && release.contains(
                        "portableGas.executed\n"
                                + "                "
                                + "!= requiredPortableGas")
                        && release.contains(
                        "hostQuota.executed\n"
                                + "                "
                                + "!= requiredHostQuota")
                        && release.contains(
                        "totalConformance.executed\n"
                                + "                "
                                + "!= requiredTotal");
        boolean derivesFlagshipAndTraceRequirements =
                release.contains(
                        "'CoordinationComplexEmbedded"
                                + "DeterminismFlagshipTest'")
                        && release.contains(
                        "'CoordinationRuntimeGasScalingTest'")
                        && release.contains(
                        "sameRun.flagship\n"
                                + "                        "
                                + "?.requiredVariants")
                        && release.contains(
                        "sameRun.runtimeTrace\n"
                                + "                        "
                                + "?.requiredEntries");
        boolean provesExactPartition =
                working.contains(
                        "'coordinationFullSuitePartitionVerification'")
                        && working.contains(
                        "full.total == 899L")
                        && working.contains(
                        "surface.total == 842L")
                        && working.contains(
                        "probes.total == 57L")
                        && working.contains(
                        "fullInventory\n"
                                + "                        "
                                + "== combinedInventory")
                        && working.contains(
                        "'generateCoordinationSameRunEvidenceReport'")
                        && release.contains(
                        "'reports/coordination-working/"
                                + "test-partition.json'")
                        && release.contains(
                        "'reports/coordination-working/"
                                + "same-run-evidence.json'");
        boolean bindsReportsToPathsAndDigests =
                working.contains(
                        "def workingEvidenceSource")
                        && release.contains(
                        "def releaseEvidenceSource")
                        && working.contains(
                        "evidenceSources:")
                        && release.contains(
                        "evidenceSources:")
                        && release.contains(
                        "sameRunMetricsMatch");
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
        assertTrue(derivesRequiredConformance);
        assertTrue(excludesSupportTests);
        assertTrue(requiresExactExecutionCounts);
        assertTrue(derivesFlagshipAndTraceRequirements);
        assertTrue(provesExactPartition);
        assertTrue(bindsReportsToPathsAndDigests);
        assertTrue(requiresProjectionRuntimeIdentities);
        assertFalse(
                release.contains(
                        "required   : 86L"));
        assertFalse(
                release.contains(
                        "flagshipRuns != 32L"));
        assertFalse(
                release.contains(
                        "== 516L"));
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
        String working =
                read(
                        "gradle/"
                                + "coordination-working.gradle");
        JsonNode catalog =
                json(
                        "gradle/"
                                + "coordination-external-blockers.json");

        // When
        boolean classifierUsesTestIdentity =
                release.contains(
                        "String className,\n"
                                + "    String testName,\n"
                                + "    String failureType,\n"
                                + "    String message ->")
                        && release.contains(
                        "testCase.@classname")
                        && release.contains(
                        "testCase.@name")
                        && release.contains(
                        "String testId =");
        boolean stripsOnlyTheJUnitTypeWrapper =
                release.contains(
                        "String wrapper =\n"
                                + "                "
                                + "failureType + ': '")
                        && release.contains(
                        "logicalMessage.substring(\n"
                                + "                            "
                                + "wrapper.length())");
        boolean usesExactCatalogPrefixes =
                release.contains(
                        "logicalMessage.startsWith(\n"
                                + "                        "
                                + "it.fingerprintPrefix)")
                        && release.contains(
                        "releaseExternalProbes.find")
                        && release.contains(
                        "'dependency-evidence-before-coordination'")
                        && working.contains(
                        "record.logicalMessage.startsWith(\n"
                                + "                    "
                                + "probe.fingerprintPrefix)")
                        && release.contains(
                        "'coordination-behavior-or-evidence'");
        Set<String> prefixes =
                new HashSet<String>();
        Set<String> tests =
                new HashSet<String>();
        int probeCount = 0;
        boolean catalogHasOnlyTestProbes = true;
        for (JsonNode blocker :
                catalog.path("blockers")) {
            String prefix =
                    blocker.path(
                                    "fingerprintPrefix")
                            .asText();
            prefixes.add(prefix);
            for (JsonNode probe :
                    blocker.path("probes")) {
                probeCount++;
                catalogHasOnlyTestProbes &=
                        probe.size() == 1
                                && probe.has("test")
                                && tests.add(
                                probe.path("test")
                                        .asText());
            }
        }
        boolean removedStaleClassifiers =
                !release.contains(
                        "fixedRepositoryAuditTestIds")
                        && !release.contains(
                        "fixedMandateEvidence")
                        && !release.contains(
                        "checkpointCoalescingTestIds")
                        && !release.contains(
                        "explicitlyAttributedLanguageFailure")
                        && !release.contains(
                        "fixedBexConformanceFailure")
                        && !release.contains(
                        "messageContains")
                        && !working.contains(
                        "messageContains");

        // Then
        assertTrue(classifierUsesTestIdentity);
        assertTrue(stripsOnlyTheJUnitTypeWrapper);
        assertTrue(usesExactCatalogPrefixes);
        assertEquals(
                "blue-coordination/external-blockers/1.1",
                catalog.path("schema")
                        .asText());
        assertEquals(
                15,
                catalog.path("blockers")
                        .size());
        assertEquals(15, prefixes.size());
        assertEquals(57, probeCount);
        assertEquals(57, tests.size());
        assertTrue(
                catalogHasOnlyTestProbes);
        assertTrue(removedStaleClassifiers);
        assertTrue(
                prefixes.stream()
                        .allMatch(
                                prefix ->
                                        !prefix.isEmpty()
                                                && prefix.endsWith(":")));
        assertFalse(
                release.contains(
                        "value.contains("));
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
        boolean requiresExactRequiredClosure =
                release.contains(
                        "sameRun.fixedRepository\n"
                                + "                        "
                                + "?.total")
                        && release.contains(
                        "fixedRequiredClosure.total\n"
                                + "                        "
                                + "== requiredFixedTotal")
                        && release.contains(
                        "fixedRequiredClosure.verified\n"
                                + "                        "
                                + "== fixedRequiredClosure.total")
                        && release.contains(
                        "fixedRequiredClosure.missing == 0L")
                        && release.contains(
                        "fixedRequiredClosure.invalidEvidence == 0L")
                        && release.contains(
                        "fixedRequiredClosure.unavailable == 0L")
                        && release.contains(
                        "fixedRequiredClosure.eligible == true")
                        && release.contains(
                        "fixedCatalog.status == 'informative'")
                        && release.contains(
                        "fixedCatalogDiagnosticComplete")
                        && release.contains(
                        "required fixed Repository closure");
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
        int requiredClosure =
                release.indexOf(
                        "requiredClosure:",
                        manifestCompatible);
        int catalogAudit =
                release.indexOf(
                        "catalogAudit:",
                        requiredClosure);

        // Then
        assertTrue(requiresExactRequiredClosure);
        assertTrue(fixedRepository >= 0);
        assertTrue(expectedManifest > fixedRepository);
        assertTrue(observedManifest > expectedManifest);
        assertTrue(manifestCompatible > observedManifest);
        assertTrue(requiredClosure > manifestCompatible);
        assertTrue(catalogAudit > requiredClosure);
        assertTrue(
                release.contains(
                        "releaseFixedRepositoryEvidence\n"
                                + "                        "
                                + ".get().asFile"));
        assertFalse(
                release.contains(
                        "fixedCatalog.total == 1107L"));
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
                        "fixedCatalog.status == 'informative'")
                        && release.contains(
                        "fixedCatalog.releaseEligibilityBasis")
                        && release.contains(
                        "fixedCatalog.releaseEligible\n"
                                + "                        "
                                + "== fixedRequiredClosure.eligible")
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
                        "fixedCatalog\n"
                                + "                        "
                                + ".observedLoadedManifestSha256")
                        && release.contains(
                        ".immutableHeadExpectedManifestSha256")
                        && release.contains(
                        "fixedCatalog.immutableHeadCommit")
                        && release.contains(
                        ".selectedRepositoryArtifactSha256")
                        && release.contains(
                        "fixedRequiredClosure\n"
                                + "                        "
                                + ".repositoryManifestSha256")
                        && release.contains(
                        "fixedRequiredClosure.repositoryHeadCommit");
        boolean writerEmitsRequiredFields =
                writer.contains(
                        "\"status\",\n"
                                + "                \"informative\"")
                        && writer.contains(
                        "\"releaseEligibilityBasis\",\n"
                                + "                "
                                + "\"requiredClosure\"")
                        && writer.contains(
                        "\"observedLoadedManifestSha256\",\n"
                                + "                "
                                + "loadedRepositoryManifestSha256()")
                        && writer.contains(
                        "\"immutableHeadCommit\"")
                        && writer.contains(
                        "\"selectedRepositoryArtifactSha256\"")
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
