package blue.coordination.examples;

import blue.coordination.examples.documents.MyOsDemoDocumentCatalog;
import blue.coordination.examples.documents.MyOsDemoDocumentCatalog.DocumentSource;
import blue.coordination.examples.documents.NestedTopologyDocuments;
import blue.coordination.examples.support.MyOsDemoDocument;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsManagedEmbedding;
import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portable source review followed by real Repository-backed identity,
 * preprocessing, initialization, and session admission.
 */
final class MyOsDemoDocumentIntegrityTest {

    private static final Set<String> CURRENT_ACTOR_TYPES = Set.of(
            "MyOS/Principal Actor",
            "MyOS/MyOS Agent Actor",
            "MyOS/MyOS Admin Actor");

    @Test
    void shouldParseAndIdentifyEveryPortableBlueDocument() throws IOException {
        // given
        List<DocumentSource> catalogSources = MyOsDemoDocumentCatalog.all();
        List<DocumentSource> sources = new ArrayList<>(catalogSources);
        Map<String, String> initialBlueIds = new LinkedHashMap<>();
        List<Map<String, Object>> manifest = new ArrayList<>();

        // when
        try (MyOsDemoRuntime runtime =
                     MyOsDemoRuntime.create("document-integrity");
             BlueLanguage language = BlueLanguage.builder().build()) {
            for (DocumentSource source : catalogSources) {
                admitAndRecord(
                        runtime,
                        language,
                        source,
                        initialBlueIds,
                        manifest);
            }

            DocumentSource emb2Source = generatedFixture(
                    "nested-topology-emb2",
                    NestedTopologyDocuments.EMB2,
                    "NestedTopologyDocuments.EMB2",
                    List.of());
            sources.add(emb2Source);
            MyOsDemoDocument emb2 = admitAndRecord(
                    runtime,
                    language,
                    emb2Source,
                    initialBlueIds,
                    manifest);

            DocumentSource emb1Source = generatedFixture(
                    "nested-topology-emb1",
                    NestedTopologyDocuments.emb1Linking(
                            emb2.initialBlueId()),
                    "NestedTopologyDocuments.emb1Linking(emb2InitialBlueId)",
                    List.of(emb2.initialBlueId()));
            sources.add(emb1Source);
            MyOsDemoDocument emb1 = admitAndRecord(
                    runtime,
                    language,
                    emb1Source,
                    initialBlueIds,
                    manifest,
                    List.of(MyOsManagedEmbedding.at(
                            "/emb2", emb2Source.documentKey())));

            DocumentSource rootSource = generatedFixture(
                    "nested-topology-root",
                    NestedTopologyDocuments.rootLinking(
                            emb1.initialBlueId()),
                    "NestedTopologyDocuments.rootLinking(emb1InitialBlueId)",
                    List.of(emb1.initialBlueId()));
            sources.add(rootSource);
            admitAndRecord(
                    runtime,
                    language,
                    rootSource,
                    initialBlueIds,
                    manifest,
                    List.of(MyOsManagedEmbedding.at(
                            "/emb1", emb1Source.documentKey())));
        }
        int generatedFixtureCount = sources.size() - catalogSources.size();
        writeManifest(
                manifest,
                catalogSources.size(),
                generatedFixtureCount);

        // then
        assertEquals(
                catalogSources.size() + generatedFixtureCount,
                sources.size());
        assertEquals(sources.size(), initialBlueIds.size());
        assertEquals(sources.size(), manifest.size());
        assertEquals(
                sources.stream().map(DocumentSource::documentKey).toList(),
                manifest.stream().map(entry -> entry.get("documentKey")).toList());
    }

    private static DocumentSource generatedFixture(
            String documentKey,
            String authoredYaml,
            String sourceConstant,
            List<String> sourceDependencies) {
        return new DocumentSource(
                "embedded-counter",
                documentKey,
                authoredYaml,
                sourceConstant,
                MyOsDemoDocumentCatalog.GENERATED_FIXTURE_SOURCE_KIND,
                sourceDependencies);
    }

    private static MyOsDemoDocument admitAndRecord(
            MyOsDemoRuntime runtime,
            BlueLanguage language,
            DocumentSource source,
            Map<String, String> initialBlueIds,
            List<Map<String, Object>> manifest) throws IOException {
        return admitAndRecord(
                runtime,
                language,
                source,
                initialBlueIds,
                manifest,
                List.of());
    }

    private static MyOsDemoDocument admitAndRecord(
            MyOsDemoRuntime runtime,
            BlueLanguage language,
            DocumentSource source,
            Map<String, String> initialBlueIds,
            List<Map<String, Object>> manifest,
            List<MyOsManagedEmbedding> managedEmbeddings) throws IOException {
        Node authored = language.codec().parseSource(
                source.authoredYaml(), BlueFormat.YAML);
        assertPortableDocument(
                source,
                source.authoredYaml(),
                inspect(authored));
        MyOsDemoDocument admitted;
        try {
            admitted = runtime.addDocument(
                    source.documentKey(),
                    source.authoredYaml(),
                    managedEmbeddings);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Could not admit integrity document "
                            + source.documentKey()
                            + " from " + source.sourceConstant(),
                    failure);
        }
        String resolved = admitted.authoredYaml();
        assertFalse(resolved.contains("{{initialBlueId:"),
                source.documentKey());
        Node document = language.codec().parseSource(
                resolved, BlueFormat.YAML);
        String sourceBlueId = admitted.initialBlueId();
        String canonicalInputBlueId = runtime.directBlueId(
                admitted.exactInitialDocument());
        DocumentInspection inspection = inspect(document);
        assertPortableDocument(source, resolved, inspection);
        assertFalse(initialBlueIds.containsKey(source.documentKey()),
                source.documentKey());
        initialBlueIds.put(source.documentKey(), sourceBlueId);
        manifest.add(manifestEntry(
                source,
                sourceBlueId,
                canonicalInputBlueId,
                inspection));
        return admitted;
    }

    private static void assertPortableDocument(
            DocumentSource source,
            String resolved,
            DocumentInspection inspection) {
        assertFalse(resolved.contains("Playground/"), source.documentKey());
        assertFalse(resolved.contains("collectionGroups"), source.documentKey());
        assertFalse(inspection.embeddedPaths().stream()
                        .anyMatch(path -> path.equals("/contracts")
                                || path.startsWith("/contracts/")),
                source.documentKey());
        assertFalse(inspection.embeddedPaths().stream()
                        .anyMatch(path -> path.contains("*")),
                source.documentKey());
        for (ChannelBinding channel : inspection.channels()) {
            assertEquals("MyOS/MyOS Timeline", channel.timelineType(),
                    channel.location());
            assertFalse(channel.timelineId().isBlank(), channel.location());
            assertTrue(CURRENT_ACTOR_TYPES.contains(channel.actorType()),
                    channel.location());
            assertFalse(channel.actorId().isBlank(), channel.location());
        }
    }

    private static Map<String, Object> manifestEntry(
            DocumentSource source,
            String sourceBlueId,
            String canonicalInputBlueId,
            DocumentInspection inspection) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("exampleId", source.exampleId());
        entry.put("documentKey", source.documentKey());
        entry.put("sourceDocumentBlueId", sourceBlueId);
        entry.put("initialCanonicalIdentityInputBlueId", canonicalInputBlueId);
        entry.put("requiredParticipantTimelineIds", inspection.timelineIds());
        entry.put("requiredActorIds", inspection.actorIds());
        entry.put("directProcessEmbeddedPaths", inspection.embeddedPaths());
        entry.put("sourceConstant", source.sourceConstant());
        entry.put("sourceKind", source.sourceKind());
        entry.put("sourceDependencies", source.sourceDependencies());
        return entry;
    }

    private static DocumentInspection inspect(Node root) {
        List<ChannelBinding> channels = new ArrayList<>();
        List<String> embeddedPaths = new ArrayList<>();
        inspect(root, "", channels, embeddedPaths);
        Set<String> timelines = new LinkedHashSet<>();
        Set<String> actors = new LinkedHashSet<>();
        channels.forEach(channel -> {
            timelines.add(channel.timelineId());
            actors.add(channel.actorId());
        });
        return new DocumentInspection(
                List.copyOf(channels),
                List.copyOf(timelines),
                List.copyOf(actors),
                List.copyOf(embeddedPaths));
    }

    private static void inspect(
            Node node,
            String location,
            List<ChannelBinding> channels,
            List<String> embeddedPaths) {
        String type = typeName(node);
        if ("Coordination/Timeline Channel".equals(type)) {
            Node timeline = property(node, "timeline", location);
            Node actor = property(node, "actor", location);
            channels.add(new ChannelBinding(
                    location,
                    typeName(timeline),
                    scalar(property(timeline, "timelineId", location)),
                    typeName(actor),
                    scalar(property(actor, "accountId", location))));
        }
        if ("Process Embedded".equals(type)) {
            Node paths = property(node, "paths", location);
            assertNotNull(paths.getItems(), location + "/paths");
            paths.getItems().forEach(path -> embeddedPaths.add(scalar(path)));
        }
        if (node.getProperties() != null) {
            node.getProperties().forEach((key, child) -> inspect(
                    child, location + "/" + key, channels, embeddedPaths));
        }
        if (node.getContracts() != null) {
            inspect(node.getContracts(), location + "/contracts",
                    channels, embeddedPaths);
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                inspect(node.getItems().get(index), location + "/" + index,
                        channels, embeddedPaths);
            }
        }
    }

    private static Node property(Node node, String key, String location) {
        assertNotNull(node.getProperties(), location);
        Node value = node.getProperties().get(key);
        assertNotNull(value, location + "/" + key);
        return value;
    }

    private static String typeName(Node node) {
        if (node == null || node.getType() == null) {
            return "";
        }
        if (node.getType().getValue() != null) {
            return node.getType().getValue().toString();
        }
        return node.getType().getBlueId() == null
                ? ""
                : node.getType().getBlueId();
    }

    private static String scalar(Node node) {
        assertNotNull(node.getValue());
        return node.getValue().toString();
    }

    private static void writeManifest(
            List<Map<String, Object>> documents,
            int catalogDocumentCount,
            int generatedFixtureCount)
            throws IOException {
        String destination = java.lang.System.getProperty(
                "myos.demo.documentsEvidence");
        if (destination == null || destination.isBlank()) {
            return;
        }
        Path path = Path.of(destination);
        Files.createDirectories(path.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "blue.coordination/myos-demo-documents/1.0");
        report.put("status", "passed");
        report.put("catalogDocumentCount", catalogDocumentCount);
        report.put("generatedFixtureCount", generatedFixtureCount);
        report.put("documentCount", documents.size());
        report.put("documents", documents);
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(path.toFile(), report);
    }

    private record ChannelBinding(
            String location,
            String timelineType,
            String timelineId,
            String actorType,
            String actorId) {
    }

    private record DocumentInspection(
            List<ChannelBinding> channels,
            List<String> timelineIds,
            List<String> actorIds,
            List<String> embeddedPaths) {
    }
}
