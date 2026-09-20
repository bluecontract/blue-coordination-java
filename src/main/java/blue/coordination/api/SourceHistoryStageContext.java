package blue.coordination.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Frozen source prerequisite and complete known owners before its independent execution. */
public record SourceHistoryStageContext(SourceHistoryPrerequisite prerequisite, List<Owner> entryOwners,
        List<String> invocationIdentities) {
    /** An absent predecessor is an exact unadmitted lineage, not an omitted read. */
    public record Owner(DocumentId documentId, Optional<ProcessingStageContext.Owner> predecessor) {
        /** Binds any existing head to the same owner identity. */
        public Owner {
            Objects.requireNonNull(documentId); Objects.requireNonNull(predecessor);
            predecessor.ifPresent(value -> {
                if (!documentId.equals(value.documentId())) throw new IllegalArgumentException("Source owner predecessor differs");
            });
        }
    }
    /** Retains canonical owners; external witnesses and the requesting parent are not implicit owners. */
    public SourceHistoryStageContext {
        Objects.requireNonNull(prerequisite); entryOwners = List.copyOf(entryOwners);
        invocationIdentities = List.copyOf(invocationIdentities);
        var identities = entryOwners.stream().map(Owner::documentId).toList();
        if (identities.isEmpty() || !identities.equals(identities.stream().distinct().sorted().toList())
                || !identities.contains(prerequisite.sourceDocumentId()))
            throw new IllegalArgumentException("Source stage requires its complete ordered known owners");
        if (invocationIdentities.stream().anyMatch(value -> value == null || value.isBlank()))
            throw new IllegalArgumentException("Empty source invocation identity");
    }
}
