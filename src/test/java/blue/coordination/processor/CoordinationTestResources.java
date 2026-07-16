package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.repo.BlueRepository;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.myos.MyOSPrincipalActor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CoordinationTestResources {
    private static final String LEGACY_SAMPLE_NAMESPACE = "My" + "OS";
    private static final String LEGACY_SAMPLE_CAMEL = "my" + "Os";
    private static final String LEGACY_SAMPLE_LOWER = "my" + "os";

    private CoordinationTestResources() {
    }

    public static String readResource(String resourcePath) {
        String normalizedPath = normalizeResourcePath(resourcePath);
        InputStream stream = CoordinationTestResources.class.getClassLoader()
                .getResourceAsStream(normalizedPath);
        if (stream == null) {
            throw new IllegalArgumentException("Missing test resource: " + resourcePath);
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read test resource: " + resourcePath, ex);
        }
    }

    public static Node yamlResource(Blue blue, BlueRepository repository, String resourcePath) {
        Node node = blue.parseSourceYaml(readResource(resourcePath));
        node.blue(repository.typeAliasBlue());
        Node aliasesResolved = new RepositoryTypeAliasPreprocessor(testTypeAliases(repository)).preprocess(node);
        return blue.preprocess(aliasesResolved);
    }

    public static Map<String, String> testTypeAliases(BlueRepository repository) {
        Map<String, String> aliases = repository != null && repository.typeAliases() != null
                ? new LinkedHashMap<String, String>(repository.typeAliases())
                : new LinkedHashMap<String, String>();
        Map<String, String> additionalAliases = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String alias = entry.getKey();
            String neutralAlias = neutralSampleAlias(alias);
            if (!alias.equals(neutralAlias)) {
                additionalAliases.put(neutralAlias, entry.getValue());
            }
        }
        aliases.putAll(additionalAliases);
        return aliases;
    }

    public static Blue configuredBlue(BlueRepository repository) {
        return repository.configure(new Blue());
    }

    public static String simpleTimelineChannelYaml(String key, String timelineId, int indent) {
        String base = spaces(indent);
        String child = spaces(indent + 2);
        return String.join("\n",
                base + key + ":",
                child + "type: " + TimelineChannel.qualifiedName(),
                child + "timeline:",
                spaces(indent + 4) + "type: " + Timeline.qualifiedName(),
                spaces(indent + 4) + "timelineId: " + timelineId,
                child + "actor:",
                spaces(indent + 4) + "type: " + MyOSPrincipalActor.qualifiedName(),
                spaces(indent + 4) + "accountId: " + timelineId);
    }

    public static Node operationRequest(String operation, String channel, Node request) {
        Node safeRequest = request != null ? request : new Node();
        return new Node()
                .type("Coordination/Operation Request")
                .properties("operation", new Node().value(operation))
                .properties("channel", new Node().value(channel))
                .properties("request", safeRequest);
    }

    public static Node operationRequestEvent(Blue blue,
                                             BlueRepository repository,
                                             String timelineId,
                                             int timestamp,
                                             String operation,
                                             String channel,
                                             Node request) {
        Node requestWithResolvedAliases = new RepositoryTypeAliasPreprocessor(
                testTypeAliases(repository)).preprocess(
                request != null ? request.clone() : new Node());
        return TestTimelineProvider.timelineEntry(blue,
                repository,
                timelineId,
                timestamp,
                operationRequest(operation, channel, requestWithResolvedAliases));
    }

    private static String normalizeResourcePath(String resourcePath) {
        if (resourcePath == null) {
            throw new IllegalArgumentException("resourcePath must not be null");
        }
        return resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
    }

    private static String neutralSampleAlias(String alias) {
        return alias.replace(LEGACY_SAMPLE_NAMESPACE, "Sample")
                .replace(LEGACY_SAMPLE_CAMEL, "sample")
                .replace(LEGACY_SAMPLE_LOWER, "sample");
    }

    private static String spaces(int count) {
        if (count <= 0) {
            return "";
        }
        char[] chars = new char[count];
        Arrays.fill(chars, ' ');
        return new String(chars);
    }
}
