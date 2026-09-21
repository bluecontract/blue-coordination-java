package blue.coordination.api;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Native execution authority alongside an unchanged canonical source prerequisite.
 * Possession of this value grants no authority: the engine authenticates it against
 * the actual suspended request and original occurrence association before use.
 * @param prerequisite exact unchanged source selection operands
 * @param requestingInstances complete original requesting owner instances
 * @param sourceInstance original source instance, including a not-yet-hosted initial source
 */
public record SourceHistoryRequest(SourceHistoryPrerequisite prerequisite,
        List<DocumentInstanceRef> requestingInstances, DocumentInstanceRef sourceInstance) {
    /** Validates and defensively copies the distinct, canonically ordered owner list. */
    public SourceHistoryRequest {
        Objects.requireNonNull(prerequisite); Objects.requireNonNull(sourceInstance);
        requestingInstances = List.copyOf(requestingInstances);
        if (requestingInstances.isEmpty() || requestingInstances.stream().map(DocumentInstanceRef::documentId).distinct().count() != requestingInstances.size()
                || !requestingInstances.equals(requestingInstances.stream().sorted(Comparator.comparing(ref -> ref.documentId().value())).toList())
                || requestingInstances.stream().noneMatch(ref -> ref.documentId().equals(prerequisite.requestingRoot()))
                || !sourceInstance.documentId().equals(prerequisite.sourceDocumentId()))
            throw new IllegalArgumentException("Source request instances do not match its logical owners");
    }
}
