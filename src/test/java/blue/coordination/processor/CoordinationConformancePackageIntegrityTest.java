package blue.coordination.processor;

import blue.language.utils.UncheckedObjectMapper;
import blue.repo.BlueRepository;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.Operation;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;
import blue.repo.coordination.TimelineChannel;
import blue.repo.myos.MyOSTimelineChannel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integrity checks for the fail-closed Coordination 1.0 conformance
 * candidate.
 *
 * <p>Inventory and package identity are not behavior conformance. The
 * candidate stays non-release-eligible until the independent execution
 * harness produces a complete same-run receipt.</p>
 */
final class CoordinationConformancePackageIntegrityTest {
    private static final Path PROJECT =
            Paths.get(System.getProperty("user.dir"))
                    .toAbsolutePath()
                    .normalize();
    private static final Path PACKAGE =
            PROJECT
                    .resolve(
                            "src/test/resources/coordination/conformance");

    @Test
    void shouldBindCandidateIntegrityToTheExactFixedRepository()
            throws Exception {
        // Given
        String manifest = read("manifest.yaml");

        // When
        String calculated =
                "sha256:" + packageIdentity();

        // Then
        assertTrue(manifest.contains("status: candidate"));
        assertTrue(manifest.contains("releaseEligible: false"));
        assertTrue(manifest.contains(
                "normativeExecutionComplete: false"));
        assertTrue(manifest.contains(
                "receiptWritten: false"));
        assertFalse(manifest.contains("status: closed"));
        assertTrue(manifest.contains(
                "fixedRepositoryVersion: 1.3.0"));
        assertTrue(manifest.contains(
                "fixedRepositoryVersionBlueId: "
                        + "msCV6VLe4Y1hayq2RnPbuzqZbroowpfBKexXoXBirZq"));
        assertEquals(
                calculated,
                manifestValue(
                        manifest,
                        "packageIdentity"));
    }

    @Test
    void shouldBindReceiptSchemaToCurrentLocalRepositoryManifest()
            throws Exception {
        // Given
        Path repositoryManifest =
                PROJECT.resolve(
                                "../blue-repository-java/src/main/resources/"
                                        + "blue/repo/manifest.json")
                        .normalize();
        JsonNode receiptSchema =
                new ObjectMapper().readTree(
                        PROJECT.resolve(
                                        "src/test/resources/coordination/"
                                                + "conformance-result.schema.json")
                                .toFile());

        // When
        String expectedManifestSha256 =
                hex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(
                                repositoryManifest)));
        String schemaManifestSha256 =
                receiptSchema.path("properties")
                        .path("fixedRepositoryManifestSha256")
                        .path("const")
                        .asText();

        // Then
        assertEquals(
                expectedManifestSha256,
                schemaManifestSha256);
    }

    @Test
    void shouldDeclareAuthoredAndExecutedCountsSeparately()
            throws Exception {
        // Given
        String manifest = read("manifest.yaml");

        // When
        List<String> authoredCounts = Arrays.asList(
                "authoredBehaviorFixtureCount: 55",
                "authoredPortableGasFixtureCount: 14",
                "authoredHostQuotaFixtureCount: 7",
                "authoredFixtureFileCount: 76",
                "authoredExecutionCaseCount: 86",
                "authoredVectorCount: 56");
        List<String> executedCounts = Arrays.asList(
                "executedBehaviorCaseCount: 0",
                "executedPortableGasCaseCount: 14",
                "executedHostQuotaCaseCount: 0");

        // Then
        for (String count : authoredCounts) {
            assertTrue(
                    manifest.contains(count),
                    "missing authored count: "
                            + count);
        }
        for (String count : executedCounts) {
            assertTrue(
                    manifest.contains(count),
                    "missing executed count: "
                            + count);
        }
    }

    @Test
    void shouldInventoryEveryCandidateArtifactExactly()
            throws Exception {
        // Given
        List<String> declared =
                manifestArtifacts();

        // When
        List<String> actual =
                packageArtifacts();
        List<String> sortedDeclared =
                new ArrayList<String>(declared);
        Collections.sort(sortedDeclared);

        // Then
        assertEquals(84, declared.size());
        assertEquals(
                declared.size(),
                new LinkedHashSet<String>(
                        declared).size());
        assertEquals(actual, sortedDeclared);
    }

    @Test
    void shouldDefineTheClosedBehaviorFixtureControlSurface()
            throws Exception {
        // Given
        JsonNode schema =
                new ObjectMapper()
                        .readTree(
                                PACKAGE.resolve(
                                        "fixture-schema.json")
                                        .toFile());

        // When
        List<String> required =
                textItems(
                        schema.path("required"));
        JsonNode properties =
                schema.path("properties");
        JsonNode assertion =
                schema.path("$defs")
                        .path("assertion");
        JsonNode processRule = null;
        for (JsonNode rule : schema.path("allOf")) {
            if ("process".equals(
                    rule.path("if")
                            .path("properties")
                            .path("operation")
                            .path("const")
                            .asText())) {
                processRule = rule;
                break;
            }
        }

        // Then
        assertFalse(
                schema.path("additionalProperties")
                        .asBoolean(true));
        assertEquals(
                Arrays.asList(
                        "schema",
                        "id",
                        "vectors",
                        "category",
                        "description",
                        "operation",
                        "input",
                        "expected"),
                required);
        assertTrue(properties.path("operation")
                .path("enum").size() == 7);
        assertFalse(
                assertion.path("additionalProperties")
                        .asBoolean(true));
        assertTrue(assertion.path("properties")
                .path("op")
                .path("enum").size() == 9);
        assertEquals(
                Arrays.asList(
                        "root",
                        "event",
                        "feeder"),
                textItems(
                        processRule.path("then")
                                .path("properties")
                                .path("input")
                                .path("required")));
        assertEquals(
                Arrays.asList(
                        "managedRootRevision",
                        "indexedRootRevision",
                        "eligibleSourceChannelKeys"),
                textItems(
                        processRule.path("then")
                                .path("properties")
                                .path("input")
                                .path("properties")
                                .path("feeder")
                                .path("required")));
    }

    @Test
    void shouldInventoryAllAuthoredBehaviorFixturesAndCases()
            throws Exception {
        // Given
        String inventory =
                read("behavior-fixtures.yaml");
        CoordinationBehaviorFixtureHarness harness =
                new CoordinationBehaviorFixtureHarness();

        // When
        List<CoordinationBehaviorFixtureHarness.FixtureCase>
                cases = harness.loadCases();
        long resources = cases.stream()
                .map(CoordinationBehaviorFixtureHarness
                        .FixtureCase::resource)
                .distinct()
                .count();

        // Then
        assertTrue(inventory.contains(
                "status: candidate"));
        assertTrue(inventory.contains(
                "normativeExecutionComplete: false"));
        assertTrue(inventory.contains(
                "authoredFixtureCount: 55"));
        assertTrue(inventory.contains(
                "expandedExecutionCaseCount: 65"));
        assertTrue(inventory.contains(
                "executedNormativeFixtureCount: 0"));
        assertTrue(inventory.contains(
                "receiptWritten: false"));
        assertEquals(55L, resources);
        assertEquals(65, cases.size());
    }

    @Test
    void shouldAuthorEveryRepositoryBackedFixtureTypeAsExactBlueIdReference()
            throws Exception {
        // Given
        BlueRepository repository =
                BlueRepository.latest();
        Set<String> repositoryAliases =
                repository.typeAliases().keySet();
        Set<String> repositoryBlueIds =
                new LinkedHashSet<String>(
                        repository.typeAliases()
                                .values());
        List<String> behaviorResources =
                behaviorFixtureResources();
        List<String> leakedAliases =
                new ArrayList<String>();
        List<String> nonCanonicalReferences =
                new ArrayList<String>();
        int[] exactReferences = new int[]{0};
        // When
        for (String resource : behaviorResources) {
            JsonNode input =
                    UncheckedObjectMapper.YAML_MAPPER
                            .readTree(
                            PACKAGE.resolve(resource)
                                    .toFile())
                    .path("input");
            inspectRepositoryTypeReferences(
                    input,
                    resource + "#/input",
                    repositoryAliases,
                    repositoryBlueIds,
                    leakedAliases,
                    nonCanonicalReferences,
                    exactReferences);
        }

        // Then
        assertEquals(55, behaviorResources.size());
        assertEquals(
                1121,
                exactReferences[0]);
        assertTrue(
                leakedAliases.isEmpty(),
                "fixture inputs still depend on repository aliases: "
                        + leakedAliases);
        assertTrue(
                nonCanonicalReferences.isEmpty(),
                "repository type references are not exact BlueId objects: "
                        + nonCanonicalReferences);
        assertTrue(read("manifest.yaml").contains(
                "behaviorRepositoryTypeReferenceMode: "
                        + "exact fixed manifest BlueId objects"));
        assertTrue(read("manifest.yaml").contains(
                "authoredBehaviorRepositoryTypeReferenceCount: 1121"));
        assertTrue(read("behavior-fixtures.yaml").contains(
                "repositoryTypeReferenceCount: 1121"));
        assertTrue(read("behavior-fixtures.yaml").contains(
                "repositoryTypeAliasShimRequired: false"));
        assertTrue(read("vector-coverage.yaml").contains(
                "repositoryTypeReferences: 1121"));
        assertTrue(read("vector-coverage.yaml").contains(
                "repositoryTypeAliasReferences: 0"));
    }

    @Test
    void shouldMapEveryPortableCounterToOneExecutableMicrofixture()
            throws Exception {
        // Given
        String fixtures =
                read("gas-fixtures.yaml");
        Map<String, Long> counters =
                CoordinationRuntimeGas.counterWeights();
        List<String> resources =
                fixtureResources("gas-micro");

        // When
        Set<String> missingCounters =
                new LinkedHashSet<String>();
        for (String counter : counters.keySet()) {
            if (!fixtures.contains(
                    "- counter: " + counter + "\n")) {
                missingCounters.add(counter);
            }
        }

        // Then
        assertEquals(14, counters.size());
        assertEquals(14, resources.size());
        assertTrue(
                missingCounters.isEmpty(),
                "portable counters without a fixture: "
                        + missingCounters);
        for (String resource : resources) {
            assertTrue(
                    fixtures.contains(
                            "resource: "
                                    + resource + "\n"),
                    "portable fixture absent from inventory: "
                            + resource);
        }
        assertTrue(fixtures.contains(
                "portableExecutionComplete: true"));
    }

    @Test
    void shouldKeepHostQuotaInventorySeparateFromPortableGas()
            throws Exception {
        // Given
        String fixtures =
                read("gas-fixtures.yaml");

        // When
        List<String> hostResources =
                fixtureResources("host-quota");

        // Then
        assertEquals(7, hostResources.size());
        assertTrue(fixtures.contains(
                "hostQuotaFixtureCount: 7"));
        assertTrue(fixtures.contains(
                "hostQuotaExecutionComplete: false"));
        for (String resource : hostResources) {
            assertTrue(
                    fixtures.contains(
                            "resource: "
                                    + resource + "\n"),
                    "host fixture absent from inventory: "
                            + resource);
        }
    }

    @Test
    void shouldBindEveryRuntimeRegistrationToItsGeneratedType()
            throws Exception {
        // Given
        String inventory =
                read("runtime-registrations.yaml");

        // When
        List<String> actual = Arrays.asList(
                new TimelineChannelProcessor()
                        .contractType().getName()
                        + "|" + TimelineChannel.blueId(),
                new CompositeTimelineChannelProcessor()
                        .contractType().getName()
                        + "|" + CompositeTimelineChannel.blueId(),
                new AllTimelinesChannelProcessor()
                        .contractType().getName()
                        + "|" + AllTimelinesChannel.blueId(),
                new OperationProcessor()
                        .contractType().getName()
                        + "|" + Operation.blueId(),
                new ChatWorkflowOperationProcessor()
                        .contractType().getName()
                        + "|" + ChatWorkflowOperation.blueId(),
                new SequentialWorkflowProcessor()
                        .contractType().getName()
                        + "|" + SequentialWorkflow.blueId(),
                new SequentialWorkflowOperationProcessor()
                        .contractType().getName()
                        + "|" + SequentialWorkflowOperation.blueId());
        TimelineChannelSubtypeProcessor<
                MyOSTimelineChannel> explicitSubtype =
                new TimelineChannelSubtypeProcessor<
                        MyOSTimelineChannel>(
                        MyOSTimelineChannel.class);

        // Then
        assertEquals(7, actual.size());
        for (String processor : Arrays.asList(
                TimelineChannelProcessor.class.getName(),
                CompositeTimelineChannelProcessor.class.getName(),
                AllTimelinesChannelProcessor.class.getName(),
                OperationProcessor.class.getName(),
                ChatWorkflowOperationProcessor.class.getName(),
                SequentialWorkflowProcessor.class.getName(),
                SequentialWorkflowOperationProcessor.class.getName())) {
            assertTrue(
                    inventory.contains(
                            "processor: " + processor),
                    "missing runtime registration "
                            + processor);
        }
        assertTrue(inventory.contains(
                "mode: explicit"));
        assertTrue(inventory.contains(
                "api: "
                        + CoordinationProcessors.class.getName()
                        + ".registerTimelineSubtype"));
        assertTrue(inventory.contains(
                "processor: "
                        + TimelineChannelSubtypeProcessor.class
                        .getName()));
        assertEquals(
                MyOSTimelineChannel.class,
                explicitSubtype.contractType());
        assertTrue(inventory.contains(
                "type: "
                        + MyOSTimelineChannel.qualifiedName()));
        for (String binding : actual) {
            assertFalse(
                    binding.endsWith("|null"),
                    "generated type has no exact BlueId: "
                            + binding);
        }
    }

    @Test
    void shouldPreserveVerifiedCrossTimelineOrderInPackageMetadata()
            throws Exception {
        // Given
        String projections =
                read("projection-catalog.yaml");
        String firstFixture =
                read("fixtures/timeline/coord-time-01.yaml");
        String tieFixture =
                read("fixtures/timeline/coord-time-03.yaml");

        // When
        boolean inventsCrossTimelineTieBreak =
                projections.contains(
                        "identity tie-break across Timelines")
                        || tieFixture.contains(
                        "ordered by exact Timeline identity");

        // Then
        assertFalse(inventsCrossTimelineTieBreak);
        assertTrue(projections.contains(
                "preserve verified platform order across Timelines"));
        assertTrue(firstFixture.indexOf("- A1")
                < firstFixture.indexOf("- B1"));
        assertTrue(tieFixture.indexOf("- B-tie")
                < tieFixture.indexOf("- A-tie"));
    }

    private static List<String> manifestArtifacts()
            throws Exception {
        List<String> artifacts =
                new ArrayList<String>();
        boolean inArtifacts = false;
        for (String line : read("manifest.yaml")
                .split("\\r?\\n")) {
            if ("artifacts:".equals(line)) {
                inArtifacts = true;
            } else if (inArtifacts
                    && line.startsWith("- ")) {
                artifacts.add(line.substring(2));
            } else if (inArtifacts
                    && line.matches(
                    "[A-Za-z][A-Za-z0-9]*:.*")) {
                break;
            } else if (inArtifacts
                    && !line.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Unexpected manifest artifact line: "
                                + line);
            }
        }
        return artifacts;
    }

    private static List<String> packageArtifacts()
            throws Exception {
        List<String> artifacts =
                new ArrayList<String>();
        try (Stream<Path> stream = Files.walk(PACKAGE)) {
            stream.filter(Files::isRegularFile)
                    .map(PACKAGE::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace(
                            java.io.File.separatorChar,
                            '/'))
                    .filter(path -> !"manifest.yaml"
                            .equals(path))
                    .forEach(artifacts::add);
        }
        Collections.sort(artifacts);
        return artifacts;
    }

    private static List<String> fixtureResources(
            String directory)
            throws Exception {
        List<String> resources =
                new ArrayList<String>();
        Path root = PACKAGE.resolve(
                "fixtures/" + directory);
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                    .map(PACKAGE::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace(
                            java.io.File.separatorChar,
                            '/'))
                    .forEach(resources::add);
        }
        Collections.sort(resources);
        return resources;
    }

    private static List<String> behaviorFixtureResources()
            throws Exception {
        List<String> resources =
                new ArrayList<String>();
        for (String artifact : manifestArtifacts()) {
            if (artifact.matches(
                    "fixtures/(channel|e2e|fail|mandate|routing|"
                            + "splitter|timeline|workflow)/"
                            + "[^/]+\\.yaml")) {
                resources.add(artifact);
            }
        }
        Collections.sort(resources);
        return resources;
    }

    private static void inspectRepositoryTypeReferences(
            JsonNode node,
            String path,
            Set<String> repositoryAliases,
            Set<String> repositoryBlueIds,
            List<String> leakedAliases,
            List<String> nonCanonicalReferences,
            int[] exactReferences) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>>
                    fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field =
                        fields.next();
                String fieldPath =
                        path + "/" + field.getKey();
                JsonNode value = field.getValue();
                if ("type".equals(field.getKey())
                        || "itemType".equals(
                                field.getKey())
                        || "keyType".equals(
                                field.getKey())
                        || "valueType".equals(
                                field.getKey())) {
                    inspectRepositoryTypeReference(
                            value,
                            fieldPath,
                            repositoryAliases,
                            repositoryBlueIds,
                            leakedAliases,
                            nonCanonicalReferences,
                            exactReferences);
                }
                inspectRepositoryTypeReferences(
                        value,
                        fieldPath,
                        repositoryAliases,
                        repositoryBlueIds,
                        leakedAliases,
                        nonCanonicalReferences,
                        exactReferences);
            }
        } else if (node.isArray()) {
            for (int index = 0;
                    index < node.size();
                    index++) {
                inspectRepositoryTypeReferences(
                        node.get(index),
                        path + "/" + index,
                        repositoryAliases,
                        repositoryBlueIds,
                        leakedAliases,
                        nonCanonicalReferences,
                        exactReferences);
            }
        }
    }

    private static void inspectRepositoryTypeReference(
            JsonNode reference,
            String path,
            Set<String> repositoryAliases,
            Set<String> repositoryBlueIds,
            List<String> leakedAliases,
            List<String> nonCanonicalReferences,
            int[] exactReferences) {
        if (reference.isTextual()
                && repositoryAliases.contains(
                        reference.asText())) {
            leakedAliases.add(
                    path + "=" + reference.asText());
            return;
        }
        JsonNode blueId =
                reference.path("blueId");
        if (!blueId.isTextual()
                || !repositoryBlueIds.contains(
                        blueId.asText())) {
            return;
        }
        if (reference.size() != 1) {
            nonCanonicalReferences.add(
                    path + "=" + reference);
            return;
        }
        exactReferences[0]++;
    }

    private static List<String> textItems(
            JsonNode array) {
        List<String> result =
                new ArrayList<String>();
        for (JsonNode item : array) {
            result.add(item.asText());
        }
        return result;
    }

    private static String manifestValue(
            String manifest,
            String key) {
        String prefix = key + ": ";
        for (String line : manifest
                .split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(
                        prefix.length());
            }
        }
        throw new IllegalArgumentException(
                "Manifest field is absent: "
                        + key);
    }

    private static String packageIdentity()
            throws Exception {
        List<Path> files =
                new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(PACKAGE)) {
            stream.filter(Files::isRegularFile)
                    .forEach(files::add);
        }
        Collections.sort(files);
        MessageDigest digest =
                MessageDigest.getInstance("SHA-256");
        for (Path file : files) {
            String relative =
                    PACKAGE.relativize(file)
                            .toString()
                            .replace(
                                    java.io.File.separatorChar,
                                    '/');
            byte[] content = Files.readAllBytes(file);
            if ("manifest.yaml".equals(relative)) {
                String normalized =
                        new String(
                                content,
                                StandardCharsets.UTF_8)
                                .replaceAll(
                                        "(?m)^packageIdentity:.*$",
                                        "packageIdentity: null");
                content = normalized.getBytes(
                        StandardCharsets.UTF_8);
            }
            digest.update(
                    relative.getBytes(
                            StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(content);
            digest.update((byte) 0);
        }
        return hex(digest.digest());
    }

    private static String read(String relative)
            throws Exception {
        return new String(
                Files.readAllBytes(
                        PACKAGE.resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result =
                new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(
                    String.format(
                            java.util.Locale.ROOT,
                            "%02x",
                            value & 0xff));
        }
        return result.toString();
    }
}
