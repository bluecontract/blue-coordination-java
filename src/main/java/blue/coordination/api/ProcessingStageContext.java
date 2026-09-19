package blue.coordination.api;

import java.util.List;
import java.util.Objects;

/** Exact selected cause and known publication authority, captured before PROCESS. */
public record ProcessingStageContext(Kind kind, DocumentId root, List<String> causes,
        List<Owner> entryOwners, List<String> invocationIdentities, String inclusiveEntryBlueId) {
    /** Unbounded or explicitly supplied-input selection, retained for existing callers. */
    public ProcessingStageContext(Kind kind, DocumentId root, List<String> causes,
            List<Owner> entryOwners, List<String> invocationIdentities) {
        this(kind, root, causes, entryOwners, invocationIdentities, null);
    }
    /** Protocol-selected work, including a coherent absence or prerequisite wait. */
    public enum Kind { JOURNAL, LOCAL_HISTORY, MANAGED_HISTORY, JOIN, WAITING, NONE }
    /** Owner predecessor and topology/history binding. Embedded witnesses are not owners. */
    public record Owner(DocumentId documentId, long epoch, String headBlueId, String historyIdentity,
            long graphGeneration, String closureIdentity) {
        /** Validates the exact immutable predecessor tuple. */
        public Owner {
            Objects.requireNonNull(documentId); text(headBlueId); text(historyIdentity); text(closureIdentity);
            if (epoch < 0 || graphGeneration < 0) throw new IllegalArgumentException("Negative owner coordinate");
        }
    }
    /** Retains a complete, canonically ordered owner set and exact cause identities. */
    public ProcessingStageContext {
        Objects.requireNonNull(kind); Objects.requireNonNull(root);
        if (inclusiveEntryBlueId != null) text(inclusiveEntryBlueId);
        causes = List.copyOf(causes); entryOwners = List.copyOf(entryOwners);
        invocationIdentities = List.copyOf(invocationIdentities);
        causes.forEach(ProcessingStageContext::text); invocationIdentities.forEach(ProcessingStageContext::text);
        if (entryOwners.isEmpty()) throw new IllegalArgumentException("Stage requires publication owners");
        String previous = null;
        for (var owner : entryOwners) {
            String current = owner.documentId().value();
            if (previous != null && previous.compareTo(current) >= 0)
                throw new IllegalArgumentException("Stage owners must be distinct and ordered");
            previous = current;
        }
        if (entryOwners.stream().noneMatch(owner -> owner.documentId().equals(root)))
            throw new IllegalArgumentException("Stage root is not an entry owner");
        if ((kind == Kind.NONE || kind == Kind.WAITING) != causes.isEmpty())
            throw new IllegalArgumentException("Selected work requires its exact cause");
    }
    private static void text(String value) {
        if (Objects.requireNonNull(value).isBlank()) throw new IllegalArgumentException("Empty stage identity");
    }
}
