package blue.coordination.sdk;

import blue.coordination.api.ContractsClosureDispatchAttempt;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.TimelineEntry;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureResourceDemand;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DocumentTransitionEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.SubscriptionState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Translates retained engine evidence into stable SDK entry outcomes. */
final class SdkDrainResultMapper {
    private final SdkCoordinationRuntime runtime;
    private final DefaultCoordinationEngine engine;

    SdkDrainResultMapper(
            SdkCoordinationRuntime runtime,
            DefaultCoordinationEngine engine) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    DrainResult map(ProcessingDrainReceipt receipt) {
        ProcessingDrainReceipt drained = Objects.requireNonNull(
                receipt, "receipt");
        ArrayList<EntryResult> entries = new ArrayList<>();
        LinkedHashSet<String> mapped = new LinkedHashSet<>();
        for (TimelineEntry entry : drained.processedEntries()) {
            entries.add(mapEntry(
                    entry,
                    drained.contractsAttemptsFor(entry.blueId())));
            mapped.add(entry.blueId());
        }
        drained.contractsAttemptsByEntry().forEach((entryBlueId, attempts) -> {
            if (mapped.contains(entryBlueId)) {
                return;
            }
            TimelineEntry retained = runtime.retainedCoreEntry(entryBlueId);
            if (retained != null) {
                entries.add(mapEntry(retained, attempts));
                mapped.add(entryBlueId);
            }
        });
        if (mapped.size() != entries.size()) {
            throw new IllegalStateException(
                    "SDK drain result contains duplicate entry evidence");
        }
        ProcessingStats stats = aggregateDrainStats(entries, drained);
        Diagnostic diagnostic = drained.paused()
                ? new Diagnostic(
                        "PROCESSING_PAUSED",
                        "Processing stopped at its deterministic budget",
                        Map.of())
                : drained.blocked()
                ? new Diagnostic(
                        "PROCESSING_BLOCKED",
                        "Processing is waiting on exact prerequisite evidence",
                        Map.of())
                : Diagnostic.none();
        return new DrainResult(
                entries,
                stats,
                drained.quiescent(),
                drained.paused(),
                diagnostic,
                drained.managedEpochApplications().stream()
                        .map(SdkDrainResultMapper
                                ::managedEpochApplicationReceipt)
                        .toList(),
                drained.managedEpochApplicationAttempts().stream()
                        .map(SdkDrainResultMapper
                                ::managedEpochApplicationAttempt)
                        .toList(),
                drained.managedEpochEvidenceFailures().stream()
                        .map(SdkDrainResultMapper
                                ::managedEpochEvidenceFailure)
                        .toList());
    }

    private static ManagedEpochEvidenceFailure managedEpochEvidenceFailure(
            blue.coordination.api.ManagedEpochEvidenceFailure retained) {
        return new ManagedEpochEvidenceFailure(
                managedEpochApplicationWork(retained.work()),
                ManagedEpochEvidenceFailure.Status.valueOf(
                        retained.status().name()),
                retained.code(),
                retained.diagnostic());
    }

    private static ManagedEpochApplicationAttempt
            managedEpochApplicationAttempt(
                    blue.coordination.api.ManagedEpochApplicationAttempt
                            retained) {
        ClosureAttemptResult attempt = retained.attempt();
        ManagedEpochApplicationAttempt.ProcessorAttempt projected;
        if (attempt.isComplete()) {
            ClosureProcessResult result = attempt.processResult();
            projected = new ManagedEpochApplicationAttempt.ProcessorAttempt(
                    true,
                    managedEpochProcessResult(result),
                    List.of(),
                    List.of());
        } else {
            projected = new ManagedEpochApplicationAttempt.ProcessorAttempt(
                    false,
                    null,
                    attempt.resourceDemands().stream()
                            .map(SdkDrainResultMapper
                                    ::managedEpochResourceDemand)
                            .toList(),
                    attempt.requiredExactBlueIds());
        }
        return new ManagedEpochApplicationAttempt(
                managedEpochApplicationWork(retained.work()),
                projected,
                retained.published(),
                retained.replayed(),
                retained.receipt().map(SdkDrainResultMapper
                        ::managedEpochApplicationReceipt),
                retained.automaticRetryCount(),
                retained.automaticResolutionStopReason()
                        .map(reason -> ManagedEpochApplicationAttempt
                                .AutomaticResolutionStopReason.valueOf(
                                        reason.name())),
                retained.managedOccurrenceResolutionIssues().stream()
                        .map(SdkDrainResultMapper
                                ::managedEpochResolutionIssue)
                        .toList(),
                retained.publicationFailure().map(failure ->
                        new ManagedEpochApplicationAttempt.PublicationFailure(
                                ManagedEpochApplicationAttempt
                                        .PublicationFailureCode.valueOf(
                                                failure.code().name()),
                                failure.message(),
                                failure.details())),
                retained.published()
                        ? managedSurfaceEvidence(retained.attempt().processResult(),
                                retained.managedOccurrenceResolutions(), retained.inputComponents(),
                                retained.operationRouteChanges())
                        : ManagedSurfaceEvidence.empty());
    }

    private static ManagedEpochApplicationAttempt
            .ManagedOccurrenceResolutionIssue managedEpochResolutionIssue(
                    blue.coordination.api.ManagedEpochApplicationAttempt
                            .ManagedOccurrenceResolutionIssue issue) {
        return new ManagedEpochApplicationAttempt
                .ManagedOccurrenceResolutionIssue(
                issue.demandIdentity(),
                ManagedEpochApplicationAttempt.ResolutionStatus.valueOf(
                        issue.status().name()),
                issue.diagnostic());
    }

    private static ManagedEpochApplicationAttempt.ProcessResult
            managedEpochProcessResult(ClosureProcessResult result) {
        return new ManagedEpochApplicationAttempt.ProcessResult(
                ManagedEpochApplicationAttempt.Status.valueOf(
                        result.status().name()),
                result.commits(),
                result.atomic(),
                result.rollbackToInput(),
                result.invocationIdentity(),
                result.inputClosureIdentity(),
                result.outputClosureIdentity(),
                result.graphGeneration(),
                result.totalGas(),
                result.gasTrace().stream()
                        .map(SdkDrainResultMapper::managedEpochGasCharge)
                        .toList(),
                result.gasTraceIdentity(),
                result.rejectedWorkOccurrence() == null
                        ? null
                        : managedEpochRejectedWork(
                                result.rejectedWorkOccurrence()),
                result.rejectedCharge() == null
                        ? null
                        : managedEpochRejectedCharge(
                                result.rejectedCharge()),
                publicEvents(result),
                diagnostic(result));
    }

    private static ManagedEpochApplicationAttempt.GasCharge
            managedEpochGasCharge(GasTraceEntry charge) {
        return new ManagedEpochApplicationAttempt.GasCharge(
                charge.sequence(),
                charge.namespace().wireValue(),
                charge.counter(),
                charge.quantity(),
                charge.weight(),
                charge.subtotal(),
                charge.documentId() == null
                        ? null
                        : DocumentId.of(charge.documentId().value()),
                charge.scopePath(),
                charge.activationGeneration(),
                charge.componentGeneration(),
                charge.contractKey(),
                charge.logicalPath(),
                charge.workOccurrenceId(),
                charge.reason());
    }

    private static ManagedEpochApplicationAttempt.RejectedWorkOccurrence
            managedEpochRejectedWork(
                    blue.language.processor.closure.ClosureWorkOccurrence
                            work) {
        return new ManagedEpochApplicationAttempt.RejectedWorkOccurrence(
                work.ordinal(),
                work.kind().name(),
                DocumentId.of(work.targetDocumentId().value()),
                work.channelKey(),
                work.eventBlueId(),
                work.occurrenceOrdinal(),
                work.targetManagedScopeIdentity(),
                work.sourceOccurrenceIdentity(),
                work.workIdentity());
    }

    private static ManagedEpochApplicationAttempt.RejectedCharge
            managedEpochRejectedCharge(RejectedCharge charge) {
        return new ManagedEpochApplicationAttempt.RejectedCharge(
                charge.rejectedChargeIdentity(),
                charge.namespace().wireValue(),
                charge.counter(),
                charge.quantity(),
                charge.weight(),
                charge.subtotal(),
                charge.applicableCap().kind().name(),
                charge.applicableCap().documentId() == null
                        ? null
                        : DocumentId.of(
                                charge.applicableCap().documentId().value()),
                charge.remainingBeforeCharge(),
                charge.owner().kind().name(),
                charge.owner().workOccurrenceIdentity(),
                charge.owner().finalizationOrdinal(),
                charge.owner().componentIdentity(),
                charge.owner().componentGeneration());
    }

    private static ManagedEpochApplicationAttempt.ResourceDemand
            managedEpochResourceDemand(ClosureResourceDemand demand) {
        return new ManagedEpochApplicationAttempt.ResourceDemand(
                demand.kind().name(),
                demand.demandIdentity(),
                DocumentId.of(demand.sourceDocumentId().value()),
                demand.sourcePath(),
                demand.suppliedValueBlueId());
    }

    private static ManagedEpochApplicationWork managedEpochApplicationWork(
            blue.coordination.api.ManagedEpochApplicationWork work) {
        return new ManagedEpochApplicationWork(
                work.workIdentity(),
                work.planIdentity(),
                work.barrierIdentity(),
                work.sourceReceiptIdentity(),
                work.sourceDocumentId(),
                work.sourceEpoch(),
                work.consumerDocumentId(),
                work.targetOccurrenceIdentity(),
                work.targetPath(),
                work.activationGeneration(),
                work.expectedConsumerCommittedEpoch(),
                work.expectedConsumerCommittedBlueId(),
                work.expectedGraphGeneration());
    }

    private static ManagedEpochApplicationReceipt
            managedEpochApplicationReceipt(
                    blue.coordination.api.ManagedEpochApplicationReceipt
                            receipt) {
        return new ManagedEpochApplicationReceipt(
                receipt.applicationReceiptIdentity(),
                receipt.workIdentity(),
                receipt.planIdentity(),
                receipt.sourceReceiptIdentity(),
                receipt.contractsInvocationIdentity(),
                receipt.contractsResultIdentity(),
                receipt.commitCompanionIdentity(),
                receipt.consumerDocumentId(),
                receipt.consumerRevisionEpoch(),
                receipt.consumerRevisionReceiptIdentity(),
                receipt.consumerCommittedBlueId(),
                receipt.resultingSourceCursor());
    }

    private EntryResult mapEntry(
            TimelineEntry entry,
            List<ContractsClosureDispatchAttempt> attempts) {
        EntryHandle handle = runtime.handle(entry);
        if (attempts.isEmpty()) {
            TargetOutcome target = diagnoseZeroAttempts(
                    runtime.intent(entry.blueId()));
            return new EntryResult(
                    handle,
                    target.disposition(),
                    List.of(),
                    List.of(),
                    ProcessingStats.zero(),
                    target.diagnostic());
        }

        ArrayList<ClosureResult> closures = new ArrayList<>();
        for (int index = 0; index < attempts.size(); index++) {
            closures.add(mapClosure(entry, attempts.get(index), index));
        }
        EntryDisposition disposition = aggregateDisposition(closures);
        if (disposition == EntryDisposition.APPLIED
                && attempts.stream().allMatch(attempt -> operationDisposition(
                        attempt.attempt().processResult(), runtime.intent(entry.blueId()))
                        == EntryDisposition.NO_MATCH)) {
            disposition = EntryDisposition.NO_MATCH;
        }
        List<PublicEvent> publicEvents = closures.stream()
                .flatMap(closure -> closure.publicEvents().stream())
                .toList();
        ProcessingStats stats = aggregateClosureStats(closures);
        Diagnostic diagnostic = aggregateDiagnostic(
                disposition, closures);
        return new EntryResult(
                handle,
                disposition,
                closures,
                publicEvents,
                stats,
                diagnostic);
    }

    private ClosureResult mapClosure(
            TimelineEntry entry,
            ContractsClosureDispatchAttempt retained,
            int index) {
        ClosureAttemptResult attempt = retained.attempt();
        if (!attempt.isComplete()) {
            Diagnostic diagnostic = new Diagnostic(
                    "REQUIRED_EXACT_RESOURCES",
                    "Closure processing requires unavailable exact values",
                    Map.of(
                            "requiredExactBlueIds",
                            String.join(",", attempt.requiredExactBlueIds())));
            return new ClosureResult(
                    closureId(entry, retained, index),
                    EntryDisposition.NEEDS_RESOURCES,
                    List.of(),
                    List.of(),
                    ProcessingStats.zero(),
                    diagnostic,
                    resourceDemands(
                            attempt.resourceDemands(),
                            retained.managedOccurrenceResolutionIssues()),
                    processorAttemptCount(retained),
                    ManagedSurfaceEvidence.empty());
        }

        ClosureProcessResult result = attempt.processResult();
        boolean hostRejected = result.commits() && !retained.published();
        if (hostRejected && retained.publicationIdentity() == null) {
            throw new IllegalStateException("Unpublished committing attempt has no durable host decision");
        }
        EntryDisposition disposition = hostRejected ? EntryDisposition.REJECTED : disposition(result.status());
        List<PublicEvent> events = hostRejected ? List.of() : publicEvents(result);
        List<DocumentChange> changes = retained.published()
                ? changes(entry, retained, result, events)
                : List.of();
        ProcessingStats stats = stats(result, retained, changes);
        Diagnostic diagnostic = hostRejected
                ? new Diagnostic("MANAGED_OCCURRENCE_BINDING_MISSING", "Expected managed occurrence was not established; no effects were published", Map.of())
                : diagnostic(result);
        return new ClosureResult(
                closureId(entry, retained, index),
                disposition,
                changes,
                events,
                stats,
                diagnostic,
                List.of(),
                processorAttemptCount(retained),
                retained.published()
                        ? managedSurfaceEvidence(retained, result)
                        : ManagedSurfaceEvidence.empty());
    }

    private static List<ClosureResult.ResourceDemand> resourceDemands(
            List<ClosureResourceDemand> demands,
            List<ContractsClosureDispatchAttempt
                    .ManagedOccurrenceResolutionIssue> issues) {
        Map<String, ContractsClosureDispatchAttempt
                .ManagedOccurrenceResolutionIssue> issuesByDemand =
                new LinkedHashMap<>();
        for (ContractsClosureDispatchAttempt
                .ManagedOccurrenceResolutionIssue issue
                : Objects.requireNonNull(issues, "issues")) {
            ContractsClosureDispatchAttempt.ManagedOccurrenceResolutionIssue
                    duplicate = issuesByDemand.put(
                            issue.demandIdentity(), issue);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Retained managed matching evidence repeats demand "
                                + issue.demandIdentity());
            }
        }
        return Objects.requireNonNull(demands, "demands").stream()
                .map(demand -> resourceDemand(
                        demand, issuesByDemand.get(demand.demandIdentity())))
                .toList();
    }

    private static ClosureResult.ResourceDemand resourceDemand(
            ClosureResourceDemand demand,
            ContractsClosureDispatchAttempt.ManagedOccurrenceResolutionIssue
                    issue) {
        return new ClosureResult.ResourceDemand(
                demand.kind().name(),
                demand.demandIdentity(),
                demand.suppliedValueBlueId(),
                DocumentId.of(demand.sourceDocumentId().value()),
                demand.sourcePath(),
                Optional.ofNullable(issue).map(selected ->
                        ClosureResult.ManagedResolutionStatus.valueOf(
                                selected.status().name())),
                Optional.ofNullable(issue).map(
                        ContractsClosureDispatchAttempt
                                .ManagedOccurrenceResolutionIssue
                                ::diagnostic));
    }

    private static ManagedSurfaceEvidence managedSurfaceEvidence(
            ContractsClosureDispatchAttempt retained,
            ClosureProcessResult result) {
        return managedSurfaceEvidence(result, retained.managedOccurrenceResolutions(),
                retained.inputComponents(), retained.operationRouteChanges());
    }

    private static ManagedSurfaceEvidence managedSurfaceEvidence(
            ClosureProcessResult result,
            List<ContractsClosureDispatchAttempt.ManagedOccurrenceResolution> retainedResolutions,
            List<ComponentSnapshot> inputComponents,
            List<ContractsClosureDispatchAttempt.OperationRouteChange> operationRouteChanges) {
        if (!result.commits()) {
            return ManagedSurfaceEvidence.empty();
        }
        return new ManagedSurfaceEvidence(
                result.graphGeneration(),
                retainedResolutions.stream().map(SdkDrainResultMapper::occurrenceResolution).toList(),
                result.graphChanges().stream().map(SdkDrainResultMapper::graphChange).toList(),
                componentTransitions(inputComponents, result.resultingComponents()),
                result.subscriptionDeltas().stream().map(SdkDrainResultMapper::subscriptionChange).toList(),
                result.documentTransitionEvidence().stream().map(SdkDrainResultMapper::documentTransition).toList(),
                java.util.stream.IntStream.range(0, operationRouteChanges.size())
                        .mapToObj(index -> operationRouteChange(index, operationRouteChanges.get(index)))
                        .toList());
    }

    private static ManagedSurfaceEvidence.OperationRouteChange
            operationRouteChange(
                    long ordinal,
                    ContractsClosureDispatchAttempt.OperationRouteChange
                            change) {
        return new ManagedSurfaceEvidence.OperationRouteChange(
                ordinal,
                ManagedSurfaceEvidence.OperationRouteChangeKind.valueOf(
                        change.kind().name()),
                change.documentId(),
                change.before().map(
                        SdkDrainResultMapper::operationRouteState),
                change.after().map(
                        SdkDrainResultMapper::operationRouteState));
    }

    private static ManagedSurfaceEvidence.OperationRouteState
            operationRouteState(
                    ContractsClosureDispatchAttempt.OperationRouteState
                            state) {
        return new ManagedSurfaceEvidence.OperationRouteState(
                state.scopePath(),
                state.operation(),
                state.channel(),
                state.acceptedSources().stream()
                        .map(source -> new TimelineSourceSnapshot(
                                source.timelineId(), source.actorId()))
                        .toList());
    }

    private static ManagedSurfaceEvidence.OccurrenceResolution
            occurrenceResolution(
                    ContractsClosureDispatchAttempt
                            .ManagedOccurrenceResolution resolution) {
        ManagedOccurrenceBinding occurrence = resolution.occurrence();
        ClosureOccurrenceSnapshot snapshot = new ClosureOccurrenceSnapshot(
                DocumentId.of(occurrence.sourceDocumentId().value()),
                occurrence.sourcePath(),
                occurrence.activationGeneration(),
                DocumentId.of(occurrence.targetDocumentId().value()),
                occurrence.expectedTargetBlueId(),
                occurrence.active());
        return new ManagedSurfaceEvidence.OccurrenceResolution(
                resolution.demandIdentity(),
                occurrence.occurrenceIdentity(),
                occurrence.bindingIdentity(),
                snapshot,
                ManagedSurfaceEvidence.ResolutionKind.valueOf(
                        resolution.targetKind().name()),
                resolution.authoredInitial().map(ExactBlueValue::wrap));
    }

    private static ManagedSurfaceEvidence.GraphChange graphChange(
            GraphChange change) {
        return new ManagedSurfaceEvidence.GraphChange(
                change.graphChangeOrdinal(),
                ManagedSurfaceEvidence.GraphChangeKind.valueOf(
                        change.changeKind().name()),
                DocumentId.of(change.sourceDocumentId().value()),
                change.sourcePath(),
                Optional.ofNullable(change.before()).map(
                        SdkDrainResultMapper::graphSide),
                Optional.ofNullable(change.after()).map(
                        SdkDrainResultMapper::graphSide));
    }

    private static ManagedSurfaceEvidence.GraphSide graphSide(
            GraphChange.Side side) {
        return new ManagedSurfaceEvidence.GraphSide(
                side.activationGeneration(),
                side.occurrenceIdentity(),
                side.bindingIdentity(),
                DocumentId.of(side.targetDocumentId().value()),
                side.targetBlueId());
    }

    private static List<ManagedSurfaceEvidence.ComponentTransition>
            componentTransitions(
                    List<ComponentSnapshot> input,
                    List<ComponentSnapshot> resulting) {
        List<ComponentSnapshot> before = List.copyOf(input);
        List<ComponentSnapshot> after = List.copyOf(resulting);
        boolean[] visitedBefore = new boolean[before.size()];
        boolean[] visitedAfter = new boolean[after.size()];
        ArrayList<ManagedSurfaceEvidence.ComponentTransition> transitions =
                new ArrayList<>();
        for (int start = 0; start < before.size(); start++) {
            if (visitedBefore[start]) {
                continue;
            }
            LinkedHashSet<Integer> beforeGroup = new LinkedHashSet<>();
            LinkedHashSet<Integer> afterGroup = new LinkedHashSet<>();
            beforeGroup.add(start);
            boolean changed;
            do {
                changed = false;
                for (int afterIndex = 0;
                        afterIndex < after.size(); afterIndex++) {
                    final int candidateAfter = afterIndex;
                    if (afterGroup.contains(afterIndex)
                            || beforeGroup.stream().noneMatch(
                                    beforeIndex -> overlaps(
                                            before.get(beforeIndex),
                                            after.get(candidateAfter)))) {
                        continue;
                    }
                    afterGroup.add(afterIndex);
                    changed = true;
                }
                for (int beforeIndex = 0;
                        beforeIndex < before.size(); beforeIndex++) {
                    final int candidateBefore = beforeIndex;
                    if (beforeGroup.contains(beforeIndex)
                            || afterGroup.stream().noneMatch(
                                    afterIndex -> overlaps(
                                            before.get(candidateBefore),
                                            after.get(afterIndex)))) {
                        continue;
                    }
                    beforeGroup.add(beforeIndex);
                    changed = true;
                }
            } while (changed);
            beforeGroup.forEach(index -> visitedBefore[index] = true);
            afterGroup.forEach(index -> visitedAfter[index] = true);
            transitions.add(componentTransition(
                    beforeGroup.stream().map(before::get).toList(),
                    afterGroup.stream().map(after::get).toList()));
        }
        for (int index = 0; index < after.size(); index++) {
            if (!visitedAfter[index]) {
                transitions.add(componentTransition(
                        List.of(), List.of(after.get(index))));
            }
        }
        return List.copyOf(transitions);
    }

    private static boolean overlaps(
            ComponentSnapshot before,
            ComponentSnapshot after) {
        return !Collections.disjoint(componentMembers(before), componentMembers(after));
    }

    private static ManagedSurfaceEvidence.ComponentTransition
            componentTransition(
                    List<ComponentSnapshot> before,
                    List<ComponentSnapshot> after) {
        ManagedSurfaceEvidence.ComponentTransitionKind kind =
                componentTransitionKind(before, after);
        return new ManagedSurfaceEvidence.ComponentTransition(
                kind,
                before.stream().map(SdkDrainResultMapper::componentState)
                        .toList(),
                after.stream().map(SdkDrainResultMapper::componentState)
                        .toList());
    }

    private static ManagedSurfaceEvidence.ComponentTransitionKind
            componentTransitionKind(
                    List<ComponentSnapshot> before,
                    List<ComponentSnapshot> after) {
        if (before.isEmpty()) {
            return ManagedSurfaceEvidence.ComponentTransitionKind.CREATED;
        }
        if (after.isEmpty()) {
            return ManagedSurfaceEvidence.ComponentTransitionKind.RETIRED;
        }
        if (before.size() > 1 && after.size() == 1) {
            return ManagedSurfaceEvidence.ComponentTransitionKind.MERGED;
        }
        if (before.size() == 1 && after.size() > 1) {
            return ManagedSurfaceEvidence.ComponentTransitionKind.SPLIT;
        }
        if (before.size() > 1) {
            return ManagedSurfaceEvidence.ComponentTransitionKind
                    .REPARTITIONED;
        }
        Set<String> beforeMembers = componentMembers(before.get(0));
        Set<String> afterMembers = componentMembers(after.get(0));
        if (beforeMembers.equals(afterMembers)) {
            return ManagedSurfaceEvidence.ComponentTransitionKind
                    .UNCHANGED_MEMBERSHIP;
        }
        if (afterMembers.containsAll(beforeMembers)) {
            return ManagedSurfaceEvidence.ComponentTransitionKind.EXPANDED;
        }
        if (beforeMembers.containsAll(afterMembers)) {
            return ManagedSurfaceEvidence.ComponentTransitionKind.CONTRACTED;
        }
        return ManagedSurfaceEvidence.ComponentTransitionKind.REPARTITIONED;
    }

    private static Set<String> componentMembers(ComponentSnapshot component) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(component
                .orderedMemberDocumentIds().stream()
                .map(blue.language.processor.closure.DocumentId::value)
                .toList()));
    }

    private static ManagedSurfaceEvidence.ComponentState componentState(
            ComponentSnapshot component) {
        return new ManagedSurfaceEvidence.ComponentState(
                component.componentIdentity(),
                component.componentStateIdentity(),
                component.componentGeneration(),
                ManagedSurfaceEvidence.ComponentKind.valueOf(
                        component.kind().name()),
                component.orderedMemberDocumentIds().stream()
                        .map(documentId -> DocumentId.of(documentId.value()))
                        .toList(),
                component.orderedMemberBlueIds(),
                Optional.ofNullable(component.masterBlueId()),
                Optional.ofNullable(component.cyclicProofIdentity()));
    }

    private static ManagedSurfaceEvidence.SubscriptionChange
            subscriptionChange(SubscriptionDelta change) {
        return new ManagedSurfaceEvidence.SubscriptionChange(
                change.subscriptionDeltaOrdinal(),
                ManagedSurfaceEvidence.SubscriptionOperation.valueOf(
                        change.operation().name()),
                change.targetManagedScopeIdentity(),
                change.channelOccurrenceIdentity(),
                Optional.ofNullable(change.beforeSubscription()).map(
                        SdkDrainResultMapper::subscriptionState),
                Optional.ofNullable(change.afterSubscription()).map(
                        SdkDrainResultMapper::subscriptionState));
    }

    private static ManagedSurfaceEvidence.SubscriptionState subscriptionState(
            SubscriptionState state) {
        return new ManagedSurfaceEvidence.SubscriptionState(
                state.subscriptionIdentity(),
                state.channelOccurrence().channelOccurrenceIdentity(),
                DocumentId.of(state.channelOccurrence()
                        .managedDocumentId().value()),
                state.channelOccurrence().scopePath(),
                state.channelOccurrence().scopeActivationGeneration(),
                state.channelOccurrence().rawChannelKey(),
                state.channelOccurrence()
                        .effectiveRuntimeContributionBlueId(),
                state.channelOccurrence().subscriptionHeaderBlueId(),
                state.documentBlueId(),
                state.graphGeneration(),
                state.componentGeneration());
    }

    static ManagedSurfaceEvidence.DocumentTransition
            documentTransition(DocumentTransitionEvidence transition) {
        return new ManagedSurfaceEvidence.DocumentTransition(
                DocumentId.of(transition.documentId().value()),
                transition.workOccurrenceIdentity(),
                transition.beforeDocumentBlueId(),
                transition.afterDocumentBlueId(),
                transition.beforeEffectiveTypeBlueId(),
                transition.afterEffectiveTypeBlueId(),
                transition.authoredContractPatches().stream()
                        .map(SdkDrainResultMapper::contractPatch)
                        .toList(),
                transition.generatedGeneralizationWrites().stream()
                        .map(write -> new ManagedSurfaceEvidence
                                .GeneralizationWrite(
                                write.path(),
                                write.valueBlueId(),
                                write.requiringPatchIndex()))
                        .toList());
    }

    private static ManagedSurfaceEvidence.ContractPatch contractPatch(
            DocumentTransitionEvidence.AuthoredContractPatch patch) {
        return new ManagedSurfaceEvidence.ContractPatch(
                ManagedSurfaceEvidence.ContractPatchOperation.valueOf(
                        patch.operation().name()),
                patch.path(),
                patch.authoredValueBlueId(),
                patch.beforeValueBlueId(),
                patch.afterValueBlueId());
    }

    private static long processorAttemptCount(
            ContractsClosureDispatchAttempt retained) {
        return Math.addExact(retained.automaticRetryCount(), 1L);
    }

    private List<DocumentChange> changes(
            TimelineEntry entry,
            ContractsClosureDispatchAttempt retained,
            ClosureProcessResult result,
            List<PublicEvent> events) {
        ArrayList<DocumentChange> changes = new ArrayList<>();
        for (DocumentId documentId : retained.documentIds()) {
            List<PublicEvent> documentEvents = events.stream()
                    .filter(event -> event.sourceDocument().filter(documentId::equals).isPresent())
                    .toList();

            List<blue.coordination.api.DocumentRevision> matching =
                    engine.history(documentId).stream()
                            .filter(revision -> revision.causalEntryBlueId()
                                    .filter(entry.blueId()::equals)
                                    .isPresent())
                            .toList();
            if (!matching.isEmpty()) {
                blue.coordination.api.DocumentRevision first =
                        matching.get(0);
                blue.coordination.api.DocumentRevision last =
                        matching.get(matching.size() - 1);
                changes.add(new DocumentChange(
                        documentId,
                        last.epoch(),
                        first.before().map(ExactBlueValue::wrap)
                                .orElse(null),
                        ExactBlueValue.wrap(last.after()),
                        documentEvents));
                continue;
            }
            result.resultingDocuments().stream()
                    .filter(document -> document.documentId().value()
                            .equals(documentId.value()))
                    .filter(document -> !document.beforeBlueId()
                            .equals(document.afterBlueId()))
                    .findFirst()
                    .ifPresent(document -> changes.add(new DocumentChange(
                            documentId,
                            document.epoch(),
                            null,
                            ExactBlueValue.wrap(
                                    ExactValue.fromVerifiedClosureResult(
                                            result, documentId)),
                            documentEvents)));
        }
        return List.copyOf(changes);
    }

    private static List<PublicEvent> publicEvents(
            ClosureProcessResult result) {
        return result.publicEvents().stream()
                .map(SdkDrainResultMapper::publicEvent)
                .toList();
    }

    private static PublicEvent publicEvent(PublicEventOccurrence occurrence) {
        return new PublicEvent(
                ExactBlueValue.wrap(ExactValue.verified(
                        occurrence.eventBlueId(), occurrence.event())),
                DocumentId.of(occurrence.publicRootDocumentId().value()),
                null);
    }

    private static ProcessingStats stats(
            ClosureProcessResult result,
            ContractsClosureDispatchAttempt retained,
            List<DocumentChange> changes) {
        LinkedHashMap<String, Long> counters = new LinkedHashMap<>();
        for (GasTraceEntry charge : result.gasTrace()) {
            String name = charge.namespace().wireValue()
                    + "." + charge.counter();
            counters.merge(name, charge.subtotal(), Math::addExact);
        }
        return new ProcessingStats(
                result.totalGas(),
                changes.size(),
                retained.documentIds().size(),
                0L,
                documentStepOrder(result),
                counters);
    }

    private static List<DocumentId> documentStepOrder(
            ClosureProcessResult result) {
        ArrayList<DocumentId> order = new ArrayList<>();
        String previousWorkOccurrence = null;
        for (GasTraceEntry charge : result.gasTrace()) {
            if (!"closureWorkOccurrenceDequeued".equals(charge.counter())
                    || charge.documentId() == null
                    || charge.workOccurrenceId() == null
                    || charge.workOccurrenceId().equals(
                            previousWorkOccurrence)) {
                continue;
            }
            order.add(DocumentId.of(charge.documentId().value()));
            previousWorkOccurrence = charge.workOccurrenceId();
        }
        return List.copyOf(order);
    }

    private TargetOutcome diagnoseZeroAttempts(
            SdkCoordinationRuntime.EntryIntent intent) {
        if (!intent.targeted()) {
            return new TargetOutcome(
                    EntryDisposition.NO_MATCH, Diagnostic.none());
        }
        if (!intent.targetPresentAtSubmission()) {
            return rejected(
                    "TARGET_DOCUMENT_NOT_FOUND",
                    "The selected target document is not managed",
                    intent);
        }
        blue.coordination.api.DocumentSnapshot snapshot;
        try {
            snapshot = engine.auditDocument(intent.targetId());
        } catch (CoordinationException failure) {
            return rejected(
                    "TARGET_DOCUMENT_NOT_FOUND",
                    "The selected target document is no longer managed",
                    intent);
        }
        if (!snapshot.blueId().equals(intent.expectedTargetBlueId())) {
            return new TargetOutcome(
                    EntryDisposition.STALE,
                    new Diagnostic(
                            "STALE_TARGET_DOCUMENT",
                            "The selected exact target state is no longer current",
                            Map.of(
                                    "documentId", intent.targetId().value(),
                                    "expectedBlueId",
                                    intent.expectedTargetBlueId(),
                                    "currentBlueId", snapshot.blueId())));
        }
        String operationPrefix = intent.operation() + "|";
        String channelPrefix = intent.operation()
                + "|" + intent.channel() + "|";
        boolean operationExists = snapshot.routingDefinitions().stream()
                .anyMatch(route -> route.startsWith(operationPrefix));
        if (!operationExists) {
            return rejected(
                    "OPERATION_NOT_FOUND",
                    "The target has no operation with this name",
                    intent);
        }
        List<String> channelRoutes = snapshot.routingDefinitions().stream()
                .filter(route -> route.startsWith(channelPrefix))
                .toList();
        if (channelRoutes.isEmpty()) {
            return rejected(
                    "TARGET_CHANNEL_NOT_FOUND",
                    "The operation is not bound to the requested target Channel",
                    intent);
        }
        boolean sourceAccepted = channelRoutes.stream().anyMatch(route ->
                route.contains("timelineId=" + intent.timelineId())
                        && route.contains("actorId=" + intent.actorId()));
        if (!sourceAccepted) {
            return rejected(
                    "TARGET_CHANNEL_SOURCE_MISMATCH",
                    "The selected Timeline is not accepted by the target Channel",
                    intent);
        }
        return rejected(
                "TARGET_NOT_SELECTED",
                "Exact target evidence did not select a processing cohort",
                intent);
    }

    private static TargetOutcome rejected(
            String code,
            String message,
            SdkCoordinationRuntime.EntryIntent intent) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("documentId", intent.targetId().value());
        if (intent.operation() != null) {
            details.put("operation", intent.operation());
        }
        if (intent.channel() != null) {
            details.put("channel", intent.channel());
        }
        return new TargetOutcome(
                EntryDisposition.REJECTED,
                new Diagnostic(code, message, details));
    }

    private static EntryDisposition operationDisposition(
            ClosureProcessResult result,
            SdkCoordinationRuntime.EntryIntent intent) {
        EntryDisposition status = disposition(result.status());
        if (status != EntryDisposition.APPLIED || !intent.targeted()) {
            return status;
        }
        // A successful channel transition can commit its checkpoint even when
        // the requested operation's payload pattern rejected the event. The
        // complete authenticated trace records handler admission independently
        // of business mutations, so an executed no-op still reports APPLIED.
        boolean operationCalled = result.gasTrace().stream().anyMatch(charge ->
                charge.namespace() == GasTraceEntry.Namespace.PROCESSOR
                        && "handlerCall".equals(charge.counter())
                        && intent.operation().equals(charge.contractKey())
                        && charge.documentId() != null
                        && intent.targetId().value().equals(charge.documentId().value()));
        return operationCalled ? EntryDisposition.APPLIED : EntryDisposition.NO_MATCH;
    }

    private static EntryDisposition disposition(ProcessorStatus status) {
        return switch (status) {
            case SUCCESS -> EntryDisposition.APPLIED;
            case NO_MATCH -> EntryDisposition.NO_MATCH;
            case STALE -> EntryDisposition.STALE;
            case GAS_LIMIT_EXCEEDED -> EntryDisposition.GAS_LIMIT_EXCEEDED;
            case PORTABLE_LIMIT_EXCEEDED ->
                    EntryDisposition.PORTABLE_LIMIT_EXCEEDED;
            default -> EntryDisposition.REJECTED;
        };
    }

    private static Diagnostic diagnostic(ClosureProcessResult result) {
        if (result.status() == ProcessorStatus.SUCCESS
                || result.status() == ProcessorStatus.NO_MATCH) {
            return Diagnostic.none();
        }
        ProcessorDiagnostic processor = result.diagnostic();
        if (processor == null) {
            return new Diagnostic(
                    result.status().name(),
                    "Contracts processing completed with "
                            + result.status().wireValue(),
                    Map.of());
        }
        return new Diagnostic(
                stableCode(processor.category().name()),
                processor.message() == null ? "" : processor.message(),
                processor.details());
    }

    private static String stableCode(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase();
    }

    private static String closureId(
            TimelineEntry entry,
            ContractsClosureDispatchAttempt retained,
            int index) {
        if (retained.publicationIdentity() != null) {
            return retained.publicationIdentity();
        }
        if (retained.attempt().isComplete()) {
            return retained.attempt().processResult().invocationIdentity();
        }
        return entry.blueId() + ":closure:" + index;
    }

    private static EntryDisposition aggregateDisposition(
            List<ClosureResult> closures) {
        LinkedHashSet<EntryDisposition> values = new LinkedHashSet<>();
        closures.forEach(closure -> values.add(closure.disposition()));
        return values.size() == 1
                ? values.iterator().next()
                : EntryDisposition.MIXED;
    }

    private static Diagnostic aggregateDiagnostic(
            EntryDisposition disposition,
            List<ClosureResult> closures) {
        if (disposition == EntryDisposition.APPLIED
                || disposition == EntryDisposition.NO_MATCH) {
            return Diagnostic.none();
        }
        if (disposition == EntryDisposition.MIXED) {
            return new Diagnostic(
                    "MIXED_CLOSURE_OUTCOMES",
                    "Disconnected affected closures completed differently",
                    Map.of("closureCount",
                            Integer.toString(closures.size())));
        }
        return closures.stream()
                .map(ClosureResult::diagnostic)
                .filter(Diagnostic::present)
                .findFirst()
                .orElse(Diagnostic.none());
    }

    private static ProcessingStats aggregateClosureStats(
            List<ClosureResult> closures) {
        long gas = 0L;
        long transitions = 0L;
        long opened = 0L;
        long elapsed = 0L;
        ArrayList<DocumentId> order = new ArrayList<>();
        LinkedHashMap<String, Long> counters = new LinkedHashMap<>();
        for (ClosureResult closure : closures) {
            ProcessingStats stats = closure.stats();
            gas = Math.addExact(gas, stats.gas());
            transitions = Math.addExact(
                    transitions, stats.committedTransitions());
            opened = Math.addExact(opened, stats.documentsOpened());
            elapsed = Math.addExact(elapsed, stats.elapsedNanos());
            order.addAll(stats.documentStepOrder());
            stats.counters().forEach((name, value) -> counters.merge(
                    name, value, Math::addExact));
        }
        return new ProcessingStats(
                gas, transitions, opened, elapsed, order, counters);
    }

    private static ProcessingStats aggregateDrainStats(
            List<EntryResult> entries,
            ProcessingDrainReceipt receipt) {
        long gas = 0L;
        long opened = 0L;
        ArrayList<DocumentId> order = new ArrayList<>();
        LinkedHashMap<String, Long> counters = new LinkedHashMap<>();
        for (EntryResult entry : entries) {
            ProcessingStats stats = entry.stats();
            gas = Math.addExact(gas, stats.gas());
            opened = Math.addExact(opened, stats.documentsOpened());
            order.addAll(stats.documentStepOrder());
            stats.counters().forEach((name, value) -> counters.merge(
                    name, value, Math::addExact));
        }
        return new ProcessingStats(
                gas,
                receipt.committedProcessTransitions(),
                opened,
                receipt.elapsedNanos(),
                order,
                counters);
    }

    private record TargetOutcome(
            EntryDisposition disposition,
            Diagnostic diagnostic) {
    }
}
