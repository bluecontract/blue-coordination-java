package blue.coordination.processor;

import blue.coordination.processor.merge.CoordinationMerging;
import blue.language.Blue;
import blue.language.model.Node;
import blue.repo.BlueRepository;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.myos.PrincipalActor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class CoordinationTestResources {
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
        return preprocessWithFixedRepository(
                blue,
                repository,
                node);
    }

    /**
     * Applies only the fixed Repository-authored preprocessing graph through
     * the Language runtime. No local alias map or recursive type rewrite is
     * permitted in Coordination fixtures.
     */
    public static Node preprocessWithFixedRepository(
            Blue blue,
            BlueRepository repository,
            Node authored) {
        if (blue == null) {
            throw new IllegalArgumentException(
                    "blue must not be null");
        }
        if (repository == null
                || !BlueRepository.LATEST.equals(
                repository.repositoryVersion())) {
            throw new IllegalArgumentException(
                    "repository must be the fixed "
                            + BlueRepository.LATEST
                            + " Repository release");
        }
        Node source =
                authored != null
                        ? authored.clone()
                        : new Node();
        source.blue(repository.typeAliasBlue());
        return blue.preprocess(source);
    }

    public static Blue configuredBlue(BlueRepository repository) {
        /*
         * Generic behavior fixtures intentionally remain independent from
         * the fixed-Repository release-evidence gate. The dedicated
         * fixedRepositoryBlue path below is the only lane that can satisfy
         * that gate.
        */
        Blue blue = repository.configure(new Blue());
        /*
         * Runtime registration installs this same workflow-AST adapter
         * idempotently. Install it before the host-owned delivery planner so
         * Language's configuration refresh cannot invalidate the planner.
         */
        CoordinationMerging.install(blue);
        CoordinationDeliveryPlanning.currentRootCompatibility(
                blue.getDocumentProcessor());
        return blue;
    }

    public static String simpleTimelineChannelYaml(String key, String timelineId, int indent) {
        String base = spaces(indent);
        String child = spaces(indent + 2);
        return String.join("\n",
                base + key + ":",
                child + "type: " + TimelineChannel.qualifiedName(),
                child + "timeline:",
                spaces(indent + 4) + "type: " + Timeline.qualifiedName(),
                spaces(indent + 4) + "providerId: test-provider",
                spaces(indent + 4) + "timelineId: " + timelineId,
                child + "actor:",
                spaces(indent + 4) + "type: " + PrincipalActor.qualifiedName(),
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
        return TestTimelineProvider.timelineEntry(blue,
                repository,
                timelineId,
                timestamp,
                operationRequest(
                        operation,
                        channel,
                        request != null
                                ? request.clone()
                                : new Node()));
    }

    private static String normalizeResourcePath(String resourcePath) {
        if (resourcePath == null) {
            throw new IllegalArgumentException("resourcePath must not be null");
        }
        return resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
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
