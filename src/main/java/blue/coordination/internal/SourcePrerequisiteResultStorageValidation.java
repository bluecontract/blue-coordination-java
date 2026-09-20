package blue.coordination.internal;

import blue.coordination.api.*;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ClosureProcessResult;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Cross-links trusted retained responses to the original selected action, without selecting or executing it again. */
final class SourcePrerequisiteResultStorageValidation {
    private SourcePrerequisiteResultStorageValidation() { }

    static void require(RootedSourceDiscoveryCoordinator.Prepared original, SourceHistoryPrerequisiteResult completed,
            Function<String, ContractsClosureAdmissionReceipt> originalAdmission, CoreReceiptStorageCodec receipts) {
        check(original.descriptor().equals(completed.selection()), "Response changed its selected descriptor");
        switch (original.descriptor().kind()) {
            case WAIT -> throw new IllegalArgumentException("A waiting prerequisite has no completed action");
            case ADMISSION -> admission(original, completed.admission().orElseThrow(), originalAdmission, receipts);
            case LIVE -> live(original, completed.processing().orElseThrow());
            case MANAGED_HISTORY -> managed(original, completed.processing().orElseThrow());
            case ROOTED_RETAINED -> retained(original, completed.processing().orElseThrow());
        }
    }

    private static void admission(RootedSourceDiscoveryCoordinator.Prepared original, ContractsClosureAdmissionReceipt receipt,
            Function<String, ContractsClosureAdmissionReceipt> originalAdmission, CoreReceiptStorageCodec receipts) {
        var selected = Objects.requireNonNull(original.admission());
        var input = selected.invocation();
        check(selected.activationInputs().policy() == CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                "Discovered admission must retain FULL_HISTORY");
        // The existing admission ledger uses this portable beginning frontier, not the provider cutoff.
        var frontier = ExternalOrderKey.of(List.of(BigInteger.valueOf(-9_007_199_254_740_991L),
                "contracts-full-history-admission", input.invocationIdentity()));
        String publication = ContractsClosureAdmissionAdapter.publicationIdentity(input,
                CoordinationEngine.AdmissionPolicy.FULL_HISTORY, frontier);
        check(receipt.published() && receipt.publicationIdentity().equals(publication), "Admission belongs to another publication");
        // Admission may legitimately expand its input/member set. The independently keyed ledger
        // retains the actual result published after all original admission/expansion guards ran.
        var retained = originalAdmission.apply(publication);
        check(retained != null && retained.published() && publication.equals(retained.publicationIdentity()),
                "Admission response lost its independently retained publication");
        check(Arrays.equals(receipts.encodeAdmission(publishedObservation(receipt)),
                receipts.encodeAdmission(publishedObservation(retained))), "Admission response changed its exact published evidence");
    }

    private static ContractsClosureAdmissionReceipt publishedObservation(ContractsClosureAdmissionReceipt receipt) {
        return new ContractsClosureAdmissionReceipt(receipt.attempt(), receipt.publicationIdentity(),
                ContractsClosureAdmissionReceipt.PublicationOutcome.PUBLISHED, receipt.documentIds());
    }

    private static void live(RootedSourceDiscoveryCoordinator.Prepared original, ProcessingDrainReceipt drain) {
        var batch = Objects.requireNonNull(original.step().live());
        check(batch.invocations().size() == 1, "A source action requires one rooted invocation");
        var entry = batch.entry();
        check(drain.managedEpochApplications().isEmpty() && drain.managedEpochApplicationAttempts().isEmpty()
                && drain.managedEpochEvidenceFailures().isEmpty() && drain.rootedRetainedAttempts().isEmpty()
                && drain.processedEntries().size() == 1 && sameEntry(entry, drain.processedEntries().get(0))
                && drain.processedThrough().filter(entry.sourceOrderKey()::equals).isPresent()
                && drain.contractsAttemptsByEntry().keySet().equals(Set.of(entry.blueId()))
                && drain.outcomesByEntry().keySet().equals(Set.of(entry.blueId()))
                && drain.contractsAttemptsFor(entry.blueId()).size() == 1, "Live response changed its selected entry or action kind");
        dispatch(batch.invocations().get(0), entry.blueId(), drain.contractsAttemptsFor(entry.blueId()).get(0), drain);
    }

    private static void retained(RootedSourceDiscoveryCoordinator.Prepared original, ProcessingDrainReceipt drain) {
        var step = Objects.requireNonNull(original.step().localHistorical());
        historicalShape(drain);
        check(drain.managedEpochApplications().isEmpty() && drain.managedEpochApplicationAttempts().isEmpty()
                && drain.rootedRetainedAttempts().size() == 1, "Retained response changed its action kind");
        var retained = drain.rootedRetainedAttempts().get(0);
        check(retained.rootDocumentId().equals(step.root()) && retained.work().workIdentity().equals(step.work().workIdentity()),
                "Retained response belongs to another root or work item");
        dispatch(step.invocation(), step.anchor().blueId(), retained.attempt(), drain);
    }

    private static void dispatch(ContractsClosureAdapter.CohortInvocation original, String entry,
            ContractsClosureDispatchAttempt attempt, ProcessingDrainReceipt drain) {
        check(attempt.entryBlueId().equals(entry) && attempt.published() && !attempt.replayed()
                && attempt.attempt().isComplete() && attempt.attempt().processResult().commits(),
                "Completed response lacks its original committed dispatch");
        var rooted = Objects.requireNonNull(original.rootedEvidence());
        var result = attempt.attempt().processResult();
        check(rooted.terminalKey().equals(attempt.publicationIdentity()), "Dispatch belongs to another terminal publication");
        // Automatic expansion can change the executed input identity; the original rooted obligation is unchanged.
        RootedResultScope.require(result, rooted);
        check(RootedResultScope.members(result).equals(attempt.documentIds())
                && drain.committedProcessTransitions() > 0
                && drain.committedProcessTransitions() == RootedResultScope.processTransitionCount(result),
                "Dispatch changed its committed owners or transition count");
    }

    private static void managed(RootedSourceDiscoveryCoordinator.Prepared original, ProcessingDrainReceipt drain) {
        var work = Objects.requireNonNull(original.step().historical());
        historicalShape(drain);
        check(drain.rootedRetainedAttempts().isEmpty() && drain.managedEpochApplications().size() == 1
                && drain.managedEpochApplicationAttempts().size() == 1,
                "Managed response changed its action kind or count");
        var attempt = drain.managedEpochApplicationAttempts().get(0);
        var receipt = drain.managedEpochApplications().get(0);
        check(attempt.work().workIdentity().equals(work.workIdentity()) && attempt.published() && !attempt.replayed()
                && attempt.receipt().orElseThrow().applicationReceiptIdentity().equals(receipt.applicationReceiptIdentity()),
                "Managed response belongs to another work item or receipt");
        ManagedCatchUpWorkIndex.requireApplicationMatch(work, receipt);
        check(attempt.attempt().isComplete() && attempt.attempt().processResult().commits(),
                "Managed response omitted its complete committing result");
        ClosureProcessResult result = attempt.attempt().processResult();
        check(drain.committedProcessTransitions() == RootedResultScope.processTransitionCount(result)
                && drain.committedProcessTransitions() > 0, "Managed response changed its exact committed owner count");
        if (original.step().localHistorical() != null) {
            RootedResultScope.require(result, original.step().localHistorical().invocation().rootedEvidence());
        }
        check(receipt.contractsInvocationIdentity().equals(result.invocationIdentity())
                && receipt.contractsResultIdentity().equals(result.outputClosureIdentity())
                && result.platformCommitCompanion() != null
                && receipt.commitCompanionIdentity().equals(result.platformCommitCompanion().companionIdentity()),
                "Managed response changed its original processor publication");
    }

    private static void historicalShape(ProcessingDrainReceipt drain) {
        check(drain.processedEntries().isEmpty() && drain.processedThrough().isEmpty() && drain.outcomesByEntry().isEmpty()
                && drain.contractsAttemptsByEntry().isEmpty() && drain.managedEpochEvidenceFailures().isEmpty(),
                "Historical response contains an unrelated external dispatch");
    }

    /** Same exact row comparison as the journal; ExactValue intentionally has no value-based Object.equals. */
    private static boolean sameEntry(TimelineEntry left, TimelineEntry right) {
        return left.journalOrderKey().equals(right.journalOrderKey())
                && left.sourceOrderKey().equals(right.sourceOrderKey()) && left.timeline().equals(right.timeline())
                && left.operationDetails().map(TimelineEntry.OperationDetails::operation)
                        .equals(right.operationDetails().map(TimelineEntry.OperationDetails::operation))
                && left.operationDetails().map(TimelineEntry.OperationDetails::channel)
                        .equals(right.operationDetails().map(TimelineEntry.OperationDetails::channel))
                && left.timestampMicros() == right.timestampMicros() && left.globalSequence() == right.globalSequence()
                && left.timelineSequence() == right.timelineSequence() && left.request().isPresent() == right.request().isPresent()
                && left.exactEvent().sameExactValue(right.exactEvent())
                && (left.request().isEmpty() || left.request().orElseThrow().sameExactValue(right.request().orElseThrow()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
