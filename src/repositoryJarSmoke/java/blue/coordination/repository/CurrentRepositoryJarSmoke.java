package blue.coordination.repository;

import blue.language.BlueRuntime;
import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.repo.BlueRepository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class CurrentRepositoryJarSmoke {
    private CurrentRepositoryJarSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Expected report path, Repository BlueId, and JAR SHA-256");
        }
        BlueRepository repository = BlueRepository.current();
        if (!args[1].equals(repository.repositoryBlueId())) {
            throw new IllegalStateException(
                    "Repository BlueId differs from the verified receipt");
        }
        String timelineBlueId = repository.blueId(
                "Coordination/Timeline Channel");
        Node processed;
        try (BlueRuntime runtime = repository.runtimeBuilder().build()) {
            Node authored = runtime.language().codec().parseSource(
                    "name: Local JAR smoke\n"
                            + "contracts:\n"
                            + "  timeline:\n"
                            + "    type: Coordination/Timeline Channel\n",
                    BlueFormat.YAML)
                    .blue(repository.importsDirective());
            processed = runtime.language().preprocessing()
                    .preprocess(authored);
        }
        Node timeline = processed.getContracts()
                .getProperties()
                .get("timeline");
        if (timeline == null
                || timeline.getType() == null
                || !timelineBlueId.equals(
                timeline.getType().getBlueId())) {
            throw new IllegalStateException(
                    "Published Language did not admit the current Repository type");
        }
        File report = new File(args[0]);
        report.getParentFile().mkdirs();
        String json = "{\n"
                + "  \"schema\": \"blue.coordination/current-repository-jar-smoke/1.0\",\n"
                + "  \"status\": \"passed\",\n"
                + "  \"repositoryBlueId\": \"" + args[1] + "\",\n"
                + "  \"repositoryJarSha256\": \"" + args[2] + "\",\n"
                + "  \"languageVersion\": \"3.1.0-rc.20\",\n"
                + "  \"admittedType\": \"Coordination/Timeline Channel\",\n"
                + "  \"admittedBlueId\": \"" + timelineBlueId + "\"\n"
                + "}\n";
        Files.write(
                report.toPath(),
                json.getBytes(StandardCharsets.UTF_8));
    }
}
