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
    private final Map<String, ExactBlueValue> authoredDocuments;
    private final List<ClosureOccurrenceSnapshot> occurrences;

    ClosureHandle(
            Object owner,
            String id,
            Map<String, DocumentHandle> documents,
            Set<String> publicRootAliases) {
        this(owner, id, documents, publicRootAliases, Map.of(), List.of());
    }

    ClosureHandle(
            Object owner,
            String id,
            Map<String, DocumentHandle> documents,
            Set<String> publicRootAliases,
            Map<String, ExactBlueValue> authoredDocuments,
            List<ClosureOccurrenceSnapshot> occurrences) {
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
        LinkedHashMap<String, ExactBlueValue> authored = new LinkedHashMap<>();
        Objects.requireNonNull(authoredDocuments, "authoredDocuments")
                .forEach((alias, value) -> {
                    String checked = SdkPreconditions.requireText(alias, "alias");
                    if (!copied.containsKey(checked)) {
                        throw new IllegalArgumentException(
                                "Unknown authored document alias: " + checked);
                    }
                    authored.put(checked, Objects.requireNonNull(
                            value, "authored document"));
                });
        this.authoredDocuments = Collections.unmodifiableMap(authored);
        this.occurrences = List.copyOf(Objects.requireNonNull(
                occurrences, "occurrences"));
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

    /**
     * Exact pre-initialization authored values by admission alias.
     *
     * <p>The map is populated when the admission compiler retained this
     * evidence. Values are immutable wrappers and
     * {@link ExactBlueValue#json()} returns detached serialized content.</p>
     */
    public Map<String, ExactBlueValue> authoredDocuments() {
        return authoredDocuments;
    }

    /** Requires one exact pre-initialization authored value by alias. */
    public ExactBlueValue authoredDocument(String alias) {
        ExactBlueValue value = authoredDocuments.get(
                SdkPreconditions.requireText(alias, "alias"));
        if (value == null) {
            throw new IllegalArgumentException(
                    "No authored admission evidence for alias: " + alias);
        }
        return value;
    }

    /** Managed occurrence evidence retained by this admission. */
    public List<ClosureOccurrenceSnapshot> occurrences() {
        return occurrences;
    }

    Object owner() {
        return owner;
    }
}
