package blue.coordination.examples.support;

import blue.language.model.wire.JsonPointer;

import java.util.Objects;

/** Explicit host declaration that one parent path contains a managed child. */
public record MyOsManagedEmbedding(String relativePath, String childKey) {

    public MyOsManagedEmbedding {
        relativePath = JsonPointer.canonicalize(
                Objects.requireNonNull(relativePath, "relativePath"));
        if (relativePath.isEmpty()) {
            throw new IllegalArgumentException("Managed child path is Root");
        }
        if (Objects.requireNonNull(childKey, "childKey").isBlank()) {
            throw new IllegalArgumentException("childKey is blank");
        }
    }

    public static MyOsManagedEmbedding at(String path, String childKey) {
        return new MyOsManagedEmbedding(path, childKey);
    }
}
