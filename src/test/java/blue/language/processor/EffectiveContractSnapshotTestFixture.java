package blue.language.processor;

import blue.language.snapshot.FrozenNode;

/** Test-only access to package-scoped effective-header construction. */
public final class EffectiveContractSnapshotTestFixture {
    private EffectiveContractSnapshotTestFixture() {
    }

    public static EffectiveContractSnapshot withHeaders(
            EffectiveContractSnapshot.Builder builder,
            java.util.Map<String, FrozenNode> headers) {
        headers.forEach(builder::headerField);
        return builder.build();
    }
}
