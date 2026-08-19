package blue.coordination.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable handle for one atomically admitted complete closure. */
public final class ClosureHandle {
    private final Object owner;
    private final String id;
    private final Map<String, DocumentHandle> documents;
    private final Set<String> publicRootAliases;

    ClosureHandle(
            Object owner,
            String id,
            Map<String, DocumentHandle> documents,
            Set<String> publicRootAliases) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.id = SdkPreconditions.requireText(id, "id");
        Map<String, DocumentHandle> copied = new LinkedHashMap<>();
        Objects.requireNonNull(documents, "documents").forEach(
                (alias, handle) -> copied.put(
                        SdkPreconditions.requireText(alias, "alias"),
                        Objects.requireNonNull(handle, "handle")));
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(
                    "A closure handle requires at least one document");
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (String alias : Objects.requireNonNull(
                publicRootAliases, "publicRootAliases")) {
            String checked = SdkPreconditions.requireText(alias, "alias");
            if (!copied.containsKey(checked)) {
                throw new IllegalArgumentException(
                        "Unknown public Root alias: " + checked);
            }
            roots.add(checked);
        }
        this.documents = Collections.unmodifiableMap(copied);
        this.publicRootAliases = Collections.unmodifiableSet(roots);
    }

    /** Exact closure identity authenticated during admission. */
    public String id() {
        return id;
    }

    /** Documents indexed by the immutable definition aliases. */
    public Map<String, DocumentHandle> documents() {
        return documents;
    }

    /** Requires one admitted member by its immutable definition alias. */
    public DocumentHandle document(String alias) {
        DocumentHandle handle = documents.get(
                SdkPreconditions.requireText(alias, "alias"));
        if (handle == null) {
            throw new IllegalArgumentException(
                    "Unknown closure document alias: " + alias);
        }
        return handle;
    }

    /** Public Root aliases in deterministic definition order. */
    public Set<String> publicRootAliases() {
        return publicRootAliases;
    }

    /** Public Root handles in deterministic definition order. */
    public List<DocumentHandle> publicRoots() {
        return publicRootAliases.stream().map(documents::get).toList();
    }

    Object owner() {
        return owner;
    }
}
