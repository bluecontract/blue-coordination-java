package blue.coordination.internal;

import blue.coordination.api.*;
import blue.language.processor.closure.ClosureProcessResult;
import java.util.*;
import java.util.stream.Stream;

/** Library-derived authority for a frozen stage; never accepts a host owner allowlist. */
final class RootedStageCapture {
    private RootedStageCapture() { }

    static ProcessingStageContext describe(DocumentId root, RootedCheckpointDriver.Selection selection,
            InMemoryDocumentStore documents) {
        var causes = new ArrayList<String>(); var invocations = new ArrayList<String>();
        var owners = new TreeSet<DocumentId>(Comparator.comparing(DocumentId::value));
        if (!selection.consumers().included()) throw new IllegalStateException("Stage requires included owners");
        owners.addAll(selection.consumers().documents());
        var kind = selection.blocked() ? ProcessingStageContext.Kind.WAITING : ProcessingStageContext.Kind.NONE;
        if (selection.live() != null) {
            kind = ProcessingStageContext.Kind.JOURNAL; causes.add(selection.live().entry().blueId());
            selection.live().invocations().forEach(invocation -> capture(invocation, owners, invocations));
        }
        if (selection.localHistorical() != null) {
            kind = ProcessingStageContext.Kind.LOCAL_HISTORY;
            causes.add(selection.localHistorical().anchor().blueId()); causes.add(selection.localHistorical().work().workIdentity());
            capture(selection.localHistorical().invocation(), owners, invocations);
        }
        if (selection.historical() != null) {
            kind = selection.localHistorical() == null ? ProcessingStageContext.Kind.MANAGED_HISTORY : ProcessingStageContext.Kind.JOIN;
            causes.add(selection.historical().workIdentity());
        }
        var predecessors = owners.stream().map(id -> {
            var session = documents.require(id);
            return new ProcessingStageContext.Owner(id, session.epoch(), session.currentRepresentation().blueId(),
                    session.requireRootedHistory().identity(), documents.graphGeneration(id), session.rootedView().snapshot().closureIdentity());
        }).toList();
        return new ProcessingStageContext(kind, root, causes, predecessors, invocations);
    }

    static Set<DocumentId> owners(DocumentId root, InMemoryDocumentStore documents) {
        return new LinkedHashSet<>(documents.require(root).rootedView().snapshot().components().stream()
                .filter(component -> component.orderedMemberDocumentIds().contains(ContractsClosureAdapter.closureId(root)))
                .findFirst().orElseThrow().orderedMemberDocumentIds().stream().map(ContractsClosureAdapter::coordinationId).toList());
    }

    private static void capture(ContractsClosureAdapter.CohortInvocation invocation, Set<DocumentId> owners, List<String> identities) {
        var rooted = Objects.requireNonNull(invocation.rootedEvidence(), "Stage requires rooted authority");
        rooted.context().entryOwners().stream().map(ContractsClosureAdapter::coordinationId).forEach(owners::add);
        identities.add(rooted.context().identity()); identities.add(rooted.deliveryBasisIdentity()); identities.add(rooted.invocationIdentity());
    }

    static List<DocumentId> resultOwners(ProcessingStageContext context, ProcessingDrainReceipt result) {
        var owners = new TreeSet<DocumentId>(Comparator.comparing(DocumentId::value));
        context.entryOwners().forEach(owner -> owners.add(owner.documentId()));
        results(result).forEach(value -> owners.addAll(RootedResultScope.members(value)));
        return List.copyOf(owners);
    }
    static boolean invalidatesSelection(ProcessingDrainReceipt result) {
        return results(result).anyMatch(value -> !value.graphChanges().isEmpty());
    }
    static Set<DocumentId> publishedOwners(ProcessingDrainReceipt result) {
        var owners = new TreeSet<DocumentId>(); results(result).forEach(value -> owners.addAll(RootedResultScope.members(value)));
        return owners;
    }

    static SourceHistoryStageContext source(SourceHistoryPrerequisite expected,
            RootedSourceDiscoveryCoordinator.Prepared selected, SourceHistoryPrerequisiteResult replay,
            InMemoryDocumentStore documents) {
        var owners = new TreeSet<DocumentId>(); owners.add(expected.sourceDocumentId());
        var invocations = new ArrayList<String>();
        if (selected != null && selected.step() != null) {
            var step = selected.step();
            // The legacy resident selector's exclusion form identifies the same
            // root component; the durable envelope always names included owners.
            var included = step.consumers().included() ? step.consumers()
                    : CatchUpConsumerScope.owners(owners(expected.sourceDocumentId(), documents));
            var context = describe(expected.sourceDocumentId(), new RootedCheckpointDriver.Selection(
                    step.live(), step.historical(), included, step.blocked(), step.localHistorical()), documents);
            context.entryOwners().forEach(owner -> owners.add(owner.documentId()));
            invocations.addAll(context.invocationIdentities());
        } else if (selected != null && selected.admission() != null) {
            invocations.add(selected.admission().invocation().invocationIdentity());
        }
        if (replay != null) {
            replay.admission().ifPresent(receipt -> owners.addAll(receipt.documentIds()));
            replay.processing().ifPresent(receipt -> owners.addAll(publishedOwners(receipt)));
        }
        var predecessors = owners.stream().map(id -> new SourceHistoryStageContext.Owner(id,
                documents.find(id).map(session -> new ProcessingStageContext.Owner(id, session.epoch(),
                        session.currentRepresentation().blueId(), session.requireRootedHistory().identity(),
                        documents.graphGeneration(id), session.rootedView().snapshot().closureIdentity())))).toList();
        return new SourceHistoryStageContext(expected, predecessors, invocations);
    }
    private static Stream<ClosureProcessResult> results(ProcessingDrainReceipt receipt) {
        return Stream.concat(Stream.concat(receipt.contractsAttemptsByEntry().values().stream().flatMap(List::stream),
                        receipt.rootedRetainedAttempts().stream().map(ProcessingDrainReceipt.RootedRetainedAttempt::attempt))
                .filter(ContractsClosureDispatchAttempt::published).map(value -> value.attempt().processResult()),
                receipt.managedEpochApplicationAttempts().stream().filter(ManagedEpochApplicationAttempt::published)
                        .map(value -> value.attempt().processResult()));
    }
}
