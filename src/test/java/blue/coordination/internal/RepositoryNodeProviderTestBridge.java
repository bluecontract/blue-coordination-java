package blue.coordination.internal;

import blue.language.provider.NodeProvider;
import blue.repo.BlueRepository;

import java.util.Objects;

/** Test-source bridge sharing production's demand-seeded Repository leaves. */
public final class RepositoryNodeProviderTestBridge {
    private RepositoryNodeProviderTestBridge() {
    }

    public static Providers create(BlueRepository repository) {
        BlueRuntime.RepositoryNodeProviders providers =
                BlueRuntime.repositoryNodeProviders(
                        Objects.requireNonNull(repository, "repository"));
        return new Providers(
                providers.repositoryProvider(),
                providers.exactNodes());
    }

    /** Adjacent generated and exact-cache leaves in production order. */
    public record Providers(
            NodeProvider repositoryProvider,
            NodeProvider exactNodes) {
    }
}
