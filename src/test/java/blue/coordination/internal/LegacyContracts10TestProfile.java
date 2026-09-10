package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.DocumentId;
import blue.coordination.sdk.BlueCoordination;
import java.util.Set;

/**
 * Explicit profile for retained broad-closure and managed-representation tests.
 *
 * <p>These exact specification identities come from the accepted starting commit
 * {@code 44aa25b47a72d70ea3ce5e575b3a28def7559501}, resource
 * {@code blue/coordination/sdk/contracts-1.0-release.properties}. They select
 * the historical semantics through the existing public configuration API;
 * they do not replace the current bundled rooted profile or its tests.</p>
 */
final class LegacyContracts10TestProfile {
    static final String LANGUAGE =
            "sha256:77b48506ff7b5ddbab26b98ce9e060e943cb3babf1e6f5511085bbb3c31c4144";
    static final String CONTRACTS =
            "sha256:0d7496790fb87d4589628c81fa8ca5e72b7d955458e20bc393f7837115ecb3b7";

    private LegacyContracts10TestProfile() { }

    static Contracts10Configuration configuration(Set<DocumentId> roots) {
        return new Contracts10Configuration(LANGUAGE, CONTRACTS, roots);
    }

    static BlueCoordination.Builder sdkBuilder() {
        return BlueCoordination.builder().release(LANGUAGE, CONTRACTS);
    }
}
