package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ExternalEventCause;
import java.util.List;
import java.util.Objects;

/** Durable feeder precondition rejection; the retained processor attempt remains suspended and rolled back. */
final class RootedDeclaredBirthRejection {
    private final ContractsClosureAdapter.CohortInvocation selected;
    private final ContractsClosureAdapter.CohortInvocation executed;
    private final ClosureAttemptResult attempt;
    private final List<ManagedOccurrenceResolver.UnresolvedDemand> issues;
    private final long retries;

    private RootedDeclaredBirthRejection(ContractsClosureAdapter.CohortInvocation selected,
            ContractsClosureAdapter.CohortInvocation executed, ClosureAttemptResult attempt,
            List<ManagedOccurrenceResolver.UnresolvedDemand> issues, long retries) {
        this.selected = selected;
        this.executed = executed;
        this.attempt = attempt;
        this.issues = List.copyOf(issues);
        this.retries = retries;
        var plan = Objects.requireNonNull(executed.managedDraftPlan(), "declared birth plan");
        var rooted = Objects.requireNonNull(executed.rootedEvidence(), "rooted invocation");
        if (attempt.isComplete() || !(executed.input().cause() instanceof ExternalEventCause)
                || !rooted.context().entryOwners().contains(ContractsClosureAdapter.closureId(plan.targetDocumentId()))
                || selected.managedDraftPlan() != plan || retries < 0L) {
            throw new IllegalArgumentException("Declared birth rejection requires its captured external owned operation");
        }
        var host = executed.input().snapshot().managedDocument(ContractsClosureAdapter.closureId(plan.targetDocumentId()));
        if (host.epoch() != plan.targetEpoch() || !host.blueId().equals(plan.targetBlueId())) {
            throw new IllegalArgumentException("Declared birth rejection differs from its captured host");
        }
        var demandIdentities = new java.util.HashSet<String>();
        for (var issue : issues) {
            if (!attempt.resourceDemands().contains(issue.demand())
                    || !demandIdentities.add(issue.demand().demandIdentity())) {
                throw new IllegalArgumentException("Declared birth rejection changed or repeated its captured demands");
            }
            if (issue.status() != ManagedOccurrenceResolver.ResolutionStatus.REJECTED_MANAGED_DECLARATION) continue;
            if (!(issue.demand() instanceof blue.language.processor.closure.ManagedOccurrenceEvidenceDemand demand)) {
                throw new IllegalArgumentException("Declared birth rejection requires an actual managed-occurrence demand");
            }
            var expected = plan.expectedOccurrences().stream().filter(row -> row.path().equals(demand.sourcePath()))
                    .findFirst().orElseThrow();
            var draft = plan.drafts().get(expected.targetDocumentId());
            if (!attempt.resourceDemands().contains(demand)
                    || !demand.sourceDocumentId().value().equals(plan.targetDocumentId().value())
                    || draft.initial().blueId().equals(demand.suppliedValueBlueId())) {
                throw new IllegalArgumentException("Rejected declaration is not an exact captured demand contradiction");
            }
            // Reuse Contracts' pure input authenticator to check its nonforgeable emitted-demand authority.
            // This prepared input is discarded: no retry, birth, initialization, portable gas or publication occurs.
            blue.language.processor.closure.ClosureEvidenceFactory.withProspectiveBirths(executed.input(), List.of(
                    new blue.language.processor.closure.ManagedDocumentBirth(demand,
                            ContractsClosureAdapter.closureId(draft.documentId()), demand.suppliedExactValue().orElseThrow())));
        }
        requireSameObligation(selected);
    }

    static RootedDeclaredBirthRejection capture(ContractsClosureAdapter.CohortInvocation selected,
            ContractsClosureAdapter.CohortInvocation executed, ClosureAttemptResult attempt,
            List<ManagedOccurrenceResolver.UnresolvedDemand> issues, long retries) {
        if (issues.stream().noneMatch(issue -> issue.status()
                == ManagedOccurrenceResolver.ResolutionStatus.REJECTED_MANAGED_DECLARATION)) return null;
        return new RootedDeclaredBirthRejection(selected, executed, attempt, issues, retries);
    }

    String terminalKey() { return executed.rootedEvidence().terminalKey(); }

    void requireSameObligation(ContractsClosureAdapter.CohortInvocation current) {
        if (current.rootedEvidence() == null || !terminalKey().equals(current.rootedEvidence().terminalKey())
                || !executed.input().cause().causeIdentity().equals(current.input().cause().causeIdentity())
                || !executed.rootedEvidence().context().identity().equals(current.rootedEvidence().context().identity())) {
            throw new IllegalArgumentException("Declared birth terminal belongs to another frozen obligation");
        }
    }

    void requireCurrentFences(InMemoryDocumentStore documents) {
        for (var owner : executed.rootedEvidence().context().entryOwners()) {
            var id = ContractsClosureAdapter.coordinationId(owner);
            var fence = executed.rootedEvidence().publicationFence(id);
            var session = documents.require(id);
            if (session.epoch() != fence.head().epoch()
                    || !session.currentRepresentation().blueId().equals(fence.head().blueId())
                    || documents.graphGeneration(id) != fence.graphGeneration()) {
                throw ContractsClosureAdapter.stale("Declared birth rejection lost its captured owner fence " + id);
            }
        }
    }

    ContractsClosureAdapter.CohortOutcome outcome(boolean replayed) {
        return new ContractsClosureAdapter.CohortOutcome(selected.members(), executed.members(), attempt,
                false, terminalKey(), replayed, retries, ManagedSurfacePublicationEvidence.empty(), issues, null, this);
    }

    void requireOutcome(List<DocumentId> members, List<DocumentId> publicationMembers, ClosureAttemptResult actual,
            boolean published, String identity, long retryCount, ManagedSurfacePublicationEvidence surface,
            List<ManagedOccurrenceResolver.UnresolvedDemand> unresolved) {
        if (!selected.members().equals(members) || !executed.members().equals(publicationMembers)
                || attempt != actual || published || !terminalKey().equals(identity) || retryCount != retries
                || surface.present() || !issues.equals(unresolved)) {
            throw new IllegalArgumentException("Declared birth outcome differs from its authenticated feeder decision");
        }
    }
}
