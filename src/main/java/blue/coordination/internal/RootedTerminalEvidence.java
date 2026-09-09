package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ResultingDocument;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Frozen input provenance for a rooted terminal decision, including noncommitting failures. */
final class RootedTerminalEvidence {
    private final ClosureInvocationInput input;
    private final RootedInvocationEvidence rooted;
    private final String executedInvocationIdentity;
    private final String historicalWorkIdentity;

    private RootedTerminalEvidence(ContractsClosureAdapter.CohortInvocation invocation,
            blue.coordination.api.ManagedEpochApplicationWork work) {
        this.input = invocation.input();
        this.rooted = Objects.requireNonNull(invocation.rootedEvidence(), "rooted evidence");
        this.executedInvocationIdentity = invocation.executionInvocationIdentity();
        this.historicalWorkIdentity = work == null ? null : work.workIdentity();
        if (work != null) requireHistoricalCause(work);
    }

    static RootedTerminalEvidence capture(ContractsClosureAdapter.CohortInvocation invocation,
            ClosureProcessResult result) {
        if (invocation.rootedEvidence() == null) return null;
        RootedTerminalEvidence retained = new RootedTerminalEvidence(invocation, null);
        retained.requireResult(result, retained.rooted.terminalKey());
        return retained;
    }

    static RootedTerminalEvidence captureHistorical(ContractsClosureAdapter.CohortInvocation invocation,
            ClosureProcessResult result, blue.coordination.api.ManagedEpochApplicationWork work) {
        if (invocation.rootedEvidence() == null) return null;
        RootedTerminalEvidence retained = new RootedTerminalEvidence(invocation, work);
        retained.requireResult(result, retained.rooted.terminalKey());
        return retained;
    }

    boolean identifiesHistoricalWork(blue.coordination.api.ManagedEpochApplicationWork work) {
        return historicalWorkIdentity != null && historicalWorkIdentity.equals(work.workIdentity());
    }

    private void requireHistoricalCause(blue.coordination.api.ManagedEpochApplicationWork work) {
        String occurrence;
        String source;
        long epoch;
        if (input.cause() instanceof blue.language.processor.closure.ManagedRevisionCause revision) {
            occurrence = revision.targetOccurrenceIdentity(); source = revision.childDocumentId().value(); epoch = revision.toEpoch();
        } else if (input.cause() instanceof blue.language.processor.closure.ManagedRepresentationCause representation) {
            occurrence = representation.targetOccurrenceIdentity(); source = representation.childDocumentId().value(); epoch = representation.fromEpoch();
            if (!work.representationCause().map(cause -> cause.causeIdentity().equals(representation.causeIdentity())).orElse(false)) {
                throw new IllegalArgumentException("Historical terminal has a different representation transition");
            }
        } else throw new IllegalArgumentException("Historical terminal requires an exact managed cause");
        if (!occurrence.equals(work.targetOccurrenceIdentity()) || !source.equals(work.sourceDocumentId().value())
                || epoch != work.sourceEpoch() || !input.snapshot().occurrences().stream().anyMatch(row ->
                        row.occurrenceIdentity().equals(occurrence)
                                && row.sourceDocumentId().value().equals(work.consumerDocumentId().value()))) {
            throw new IllegalArgumentException("Historical terminal does not identify its exact consumer/source work");
        }
    }

    void requireResult(ClosureProcessResult result, String terminalKey) {
        if (!rooted.terminalKey().equals(terminalKey)
                || !executedInvocationIdentity.equals(result.invocationIdentity())
                || !input.snapshot().closureIdentity().equals(result.inputClosureIdentity())) {
            throw new IllegalArgumentException("Rooted terminal decision differs from its frozen invocation");
        }
        if (result.commits()) RootedResultScope.require(result, rooted);
        else if (!result.rollbackToInput() || result.commitCompanion() != null) {
            throw new IllegalArgumentException("Rooted failure must retain exact rollback evidence without a companion");
        }
    }

    void requireSameObligation(RootedInvocationEvidence current) {
        if (current == null || !rooted.context().identity().equals(current.context().identity())
                || !rooted.deliveryBasisIdentity().equals(current.deliveryBasisIdentity())) {
            throw new IllegalArgumentException("Rooted terminal receipt belongs to another logical obligation");
        }
    }

    List<DocumentId> entryOwners() {
        return rooted.context().entryOwners().stream().map(ContractsClosureAdapter::coordinationId).toList();
    }

    ClosureInvocationInput input() { return input; }

    Map<DocumentId, ResultingDocument> publicationDocuments(ClosureProcessResult result) {
        List<DocumentId> owners = result.commits() ? RootedResultScope.members(result) : entryOwners();
        Map<DocumentId, ResultingDocument> documents = new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        for (ResultingDocument document : result.resultingDocuments()) {
            DocumentId id = ContractsClosureAdapter.coordinationId(document.documentId());
            if (owners.contains(id)) documents.put(id, document);
        }
        if (documents.size() != owners.size()) throw new IllegalArgumentException("Rooted result omits an entry owner");
        return java.util.Collections.unmodifiableMap(documents);
    }
}
