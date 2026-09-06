package blue.coordination.external;

import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.ManagedReactionContext;
import java.util.*;

/** Complete due membership of one owned consumer reaction position, independent of SQL page size. */
public final class ManagedImportSelection {
    private final List<ManagedImportLane.Due> due;
    private final ManagedReactionContext context;
    private final DocumentId consumer;

    public ManagedImportSelection(Collection<ManagedImportLane.Due> due) {
        TreeMap<String, ManagedImportLane.Due> canonical = new TreeMap<>();
        for (var value : Objects.requireNonNull(due)) {
            var selected = Objects.requireNonNull(value);
            if (canonical.put(selected.cursor().descriptor().occurrenceIdentity(), selected) != null)
                throw ManagedImportLane.invalid("A selected reaction repeats an occurrence activation");
        }
        if (canonical.isEmpty()) throw ManagedImportLane.invalid("A managed reaction requires exact due membership");
        this.due = List.copyOf(canonical.values());
        var first = this.due.get(0); var basis = first.cursor().descriptor(); consumer = basis.consumerLineage();
        List<ManagedReactionContext.DueOccurrence> occurrences = new ArrayList<>();
        for (var selected : this.due) {
            var lane = selected.cursor().descriptor();
            if (!consumer.equals(lane.consumerLineage()) || !basis.creatingOperationIdentity().equals(lane.creatingOperationIdentity())
                    || !basis.creatorExecutionSeedIdentity().equals(lane.creatorExecutionSeedIdentity()) || !basis.creationSiteIdentity().equals(lane.creationSiteIdentity())
                    || !basis.selection().activationCut().equals(lane.selection().activationCut())
                    || !first.source().reactionPositionIdentity().equals(selected.source().reactionPositionIdentity()))
                throw ManagedImportLane.invalid("Distinct attachment or source reaction positions require separate operations");
            occurrences.add(new ManagedReactionContext.DueOccurrence(lane.occurrenceIdentity(), lane.consumerLineage(), lane.sourceLineage(),
                    selected.source().operationIdentity(), selected.cursor().positionIdentity()));
        }
        context = new ManagedReactionContext(basis.creatingOperationIdentity(), basis.creatorExecutionSeedIdentity(), basis.creationSiteIdentity(),
                basis.selection().activationCut(), first.source().reactionPositionIdentity(), occurrences);
    }
    public DocumentId consumer() { return consumer; }
    public List<ManagedImportLane.Due> due() { return due; }
    public ManagedReactionContext context() { return context; }
}
