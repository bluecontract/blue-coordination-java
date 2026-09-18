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
    private static Stream<ClosureProcessResult> results(ProcessingDrainReceipt receipt) {
        return Stream.concat(Stream.concat(receipt.contractsAttemptsByEntry().values().stream().flatMap(List::stream),
                        receipt.rootedRetainedAttempts().stream().map(ProcessingDrainReceipt.RootedRetainedAttempt::attempt))
                .filter(ContractsClosureDispatchAttempt::published).map(value -> value.attempt().processResult()),
                receipt.managedEpochApplicationAttempts().stream().filter(ManagedEpochApplicationAttempt::published)
                        .map(value -> value.attempt().processResult()));
    }
}
