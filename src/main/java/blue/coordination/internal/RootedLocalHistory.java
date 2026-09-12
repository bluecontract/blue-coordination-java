package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.TimelineEntry;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRepresentationCause;
import blue.language.processor.closure.ProcessingCause;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Selects retained prerequisites in calculated dependency views without acquiring their source heads. */
final class RootedLocalHistory {
    private RootedLocalHistory() { }

    static List<ManagedOccurrenceBinding> pending(ContractsClosureAdapter.RootedCapturedState state,
            DocumentId root) {
        return pending(state.snapshot(), root);
    }

    static List<ManagedOccurrenceBinding> pending(blue.language.processor.closure.AffectedClosureSnapshot snapshot,
            DocumentId root) {
        var reachable = new LinkedHashSet<blue.language.processor.closure.DocumentId>();
        var queue = new ArrayDeque<blue.language.processor.closure.DocumentId>();
        queue.add(ContractsClosureAdapter.closureId(root));
        while (!queue.isEmpty()) {
            var source = queue.removeFirst();
            if (!reachable.add(source)) continue;
            snapshot.occurrences().stream().filter(row -> row.active() && row.sourceDocumentId().equals(source))
                    .forEach(row -> queue.addLast(row.targetDocumentId()));
        }
        return snapshot.occurrences().stream().filter(row -> !row.active()
                && row.pendingHistoricalEpoch() != null && reachable.contains(row.sourceDocumentId())
                && !snapshot.publicRootDocumentIds().contains(row.sourceDocumentId())).sorted().toList();
    }

    static Selection select(DocumentId root, ContractsClosureAdapter.RootedCapturedState state,
            List<TimelineEntry> entries, InMemoryDocumentStore documents, WholeObjectStore objects,
            ExecutionPolicy policy, ClosureEnvironment environment) {
        var pending = pending(state, root);
        if (pending.isEmpty()) return new Selection(false, null);
        ExternalOrderKey boundary = Objects.requireNonNull(state.view().logicalBoundary(),
                "A pending local attachment requires its committed logical boundary");
        TimelineEntry anchor = entries.stream().filter(entry -> entry.sourceOrderKey().equals(boundary))
                .findFirst().orElseThrow(() -> new IllegalStateException("Retained local barrier has no exact causal entry"));
        var choices = new ArrayList<Step>();
        for (var target : pending) {
            if (requiresIndependentReturnSettlement(state, target, boundary, documents)) return new Selection(true, null);
            Step next = capture(root, state, target, anchor, documents, objects, policy, environment);
            if (next == null) return new Selection(true, null);
            choices.add(next);
        }
        choices.sort(Comparator.comparing(Step::sourceOrder)
                .thenComparing(step -> step.target().occurrenceIdentity())
                .thenComparingLong(step -> step.work().sourceEpoch()));
        return new Selection(true, choices.get(0));
    }

    /** A pending return to this owner is a real cyclic prerequisite, not one-way observer lag. */
    private static boolean requiresIndependentReturnSettlement(ContractsClosureAdapter.RootedCapturedState state,
            ManagedOccurrenceBinding target, ExternalOrderKey boundary, InMemoryDocumentStore documents) {
        if (!state.snapshot().publicRootDocumentIds().contains(target.targetDocumentId())) return false;
        var consumer = ContractsClosureAdapter.coordinationId(target.sourceDocumentId());
        var session = documents.find(consumer).orElse(null);
        if (session == null || session.rootedView() == null) return false;
        var current = session.rootedView();
        current.requirePublishedHead(consumer, session.epoch(), session.currentRepresentation().blueId());
        var pending = current.snapshot().occurrences().stream().filter(row ->
                row.occurrenceIdentity().equals(target.occurrenceIdentity())
                        && row.activationGeneration() == target.activationGeneration()
                        && row.sourceDocumentId().equals(target.sourceDocumentId())
                        && row.targetDocumentId().equals(target.targetDocumentId())
                        && !row.active() && row.pendingHistoricalEpoch() != null).findFirst().orElse(null);
        if (pending == null) return false;
        return documents.catchUpPlans(consumer).stream().filter(plan ->
                plan.targetOccurrenceIdentity().equals(pending.occurrenceIdentity())
                        && plan.activationGeneration() == pending.activationGeneration()
                        && plan.sourceDocumentId().value().equals(pending.targetDocumentId().value())
                        && plan.targetPath().equals(pending.sourcePath())
                        && plan.nextSourceEpoch() == pending.pendingHistoricalEpoch() + 1L
                        && plan.status() != ManagedCatchUpStatus.COMPLETE
                        && plan.status() != ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED)
                .anyMatch(plan -> documents.catchUpBarrier(plan.barrierIdentity()).filter(barrier ->
                        barrier.consumerDocumentId().equals(consumer)
                                && barrier.planIdentities().contains(plan.planIdentity())
                                && barrier.causeOrder().compareTo(boundary) <= 0).isPresent());
    }

    private static Step capture(DocumentId root, ContractsClosureAdapter.RootedCapturedState state,
            ManagedOccurrenceBinding target, TimelineEntry anchor, InMemoryDocumentStore documents,
            WholeObjectStore objects, ExecutionPolicy policy, ClosureEnvironment environment) {
        var source = ContractsClosureAdapter.coordinationId(target.targetDocumentId());
        var consumer = ContractsClosureAdapter.coordinationId(target.sourceDocumentId());
        var selectedSource = Objects.requireNonNull(state.snapshot().managedDocument(target.targetDocumentId()),
                "Pending local occurrence has no complete source view");
        var selectedConsumer = Objects.requireNonNull(state.snapshot().managedDocument(target.sourceDocumentId()));
        long from = target.pendingHistoricalEpoch();
        ManagedRepresentationCause representation = null;
        if (from >= 0) {
            var history = new ManagedRepresentationHistory(documents).forCapturedRoot(state);
            var chain = history.atRootedCaptured(source, from, target.pendingRepresentationCursor(), anchor.sourceOrderKey());
            var next = chain.next(target.pendingRepresentationCursor(), target.expectedTargetBlueId());
            if (next.isPresent()) {
                var transition = next.orElseThrow();
                // Intermediate chains end at the immutable next receipt predecessor.
                // A terminal chain must end at this root's frozen exact source view.
                if (from == selectedSource.epoch() && !chain.transitions().get(chain.transitions().size() - 1)
                        .transitionReceipt().afterBlueId().equals(selectedSource.blueId())) return null;
                representation = new ManagedRepresentationCause(target.occurrenceIdentity(), transition,
                        chain.targetPositionIdentity(), chain.nextRevisionReceiptIdentity(),
                        objects.cyclicSetProofFor(transition.transitionReceipt().afterBlueId()).proof().orElse(null));
                history.verifyCause(representation, target, anchor.sourceOrderKey());
            }
        }
        long epoch = representation == null ? Math.addExact(from, 1L) : from;
        if (epoch > selectedSource.epoch()) return null;
        var evidence = documents.managedEpochEvidence(source, epoch);
        if (evidence.receipt() == null) return null;
        var receipt = evidence.receipt();
        ExternalOrderKey order = receipt.sourceOrder().orElseThrow();
        if (order.compareTo(anchor.sourceOrderKey()) > 0) {
            // An imported older channel entry can discover a return path to an
            // already processed entry owner. Its exact frozen head is required
            // input, not a newer independent source selected from ambient state.
            // Traverse only that authenticated existing prefix; other sources
            // remain bounded by the attachment's original external order.
            var frozenOwner = state.view().result().rootedProjection();
            var currentOwner = documents.require(source);
            if (frozenOwner == null || !frozenOwner.owns(target.targetDocumentId())
                    || !state.snapshot().publicRootDocumentIds().contains(target.targetDocumentId())
                    || currentOwner.epoch() != selectedSource.epoch()
                    || !currentOwner.currentRepresentation().blueId().equals(selectedSource.blueId())) return null;
        }

        // These are ephemeral selection coordinates, not newly committed source evidence.
        // The original source receipt and companion remain owned by their original operation.
        var barrier = ManagedCatchUpBarrier.identified(consumer, state.view().result().invocationIdentity(),
                anchor.sourceOrderKey(), List.of(), ManagedCatchUpBarrierStatus.OPEN, null, null);
        var plan = ManagedOccurrenceCatchUpPlan.identified(barrier.barrierIdentity(), consumer,
                target.occurrenceIdentity(), target.sourcePath(), target.activationGeneration(), source,
                from, target.expectedTargetBlueId(), Math.addExact(from, 1L), selectedSource.epoch(),
                state.view().result().invocationIdentity(), ManagedCatchUpStatus.PENDING, null, null);
        var work = ManagedEpochApplicationWork.identified(plan.planIdentity(), barrier.barrierIdentity(),
                receipt.receiptIdentity(), source, epoch, consumer, target.occurrenceIdentity(), target.sourcePath(),
                target.activationGeneration(), selectedConsumer.epoch(), selectedConsumer.blueId(), state.snapshot().graphGeneration());
        if (representation != null) work = ManagedEpochApplicationWork.identifiedRepresentation(work, representation);
        else if (epoch == selectedSource.epoch()) {
            var history = new ManagedRepresentationHistory(documents).forCapturedRoot(state);
            var successor = history.terminalSuccessor(source, epoch, target.occurrenceIdentity(),
                    anchor.sourceOrderKey(), selectedSource,
                    id -> objects.cyclicSetProofFor(id).proof().orElse(null));
            if (successor.isPresent()) {
                work = ManagedEpochApplicationWork.identifiedWithSuccessorRepresentationCause(work, successor.orElseThrow());
                history.verifySuccessor(work, selectedSource, anchor.sourceOrderKey());
            }
        }
        var verified = new ManagedEpochSourceEvidenceVerifier(objects, documents).verify(work, evidence, plan);
        ProcessingCause cause = representation != null ? representation : verified.transitionReceipt() == null
                ? ClosureEvidenceFactory.managedRevisionCause(target.occurrenceIdentity(), target.targetDocumentId(), from,
                        epoch, receipt.beforeBlueId().orElseThrow(), receipt.afterBlueId(), receipt.afterDocument().copyNode(),
                        receipt.originalCauseIdentity(), verified.afterCyclicProof())
                : ClosureEvidenceFactory.managedRevisionCause(target.occurrenceIdentity(), from, epoch,
                        receipt.afterDocument().copyNode(), verified.transitionReceipt(), verified.afterCyclicProof());
        if (work.successorRepresentationCause().isPresent()) {
            cause = ((blue.language.processor.closure.ManagedRevisionCause) cause)
                    .withSuccessorRepresentationCause(work.successorRepresentationCause().orElseThrow());
        }
        var input = ClosureEvidenceFactory.processClosure(state.snapshot(), cause, List.of(), policy, environment);
        String position = target.pendingRepresentationCursor() != null ? target.pendingRepresentationCursor().positionIdentity()
                : from < 0 ? documents.require(source).requireRootedHistory().identity()
                        : documents.managedEpochEvidence(source, from).receipt().receiptIdentity();
        var rooted = RootedInvocationEvidence.retainedLocal(state, input, documents, target, position, work);
        var members = state.documents().keySet().stream().sorted(EmbeddingBinding.DOCUMENT_ORDER).toList();
        var owners = state.snapshot().publicRootDocumentIds().stream().map(ContractsClosureAdapter::coordinationId).toList();
        var base = new ContractsClosureAdapter.CohortInvocation(members, List.of(), input, null,
                state.documents(), null, null, owners, owners, state.anchor());
        return new Step(root, target, work, order, anchor, base, state, rooted);
    }

    record Selection(boolean pending, Step step) { }

    /** Closed pair of the original verified capture and its Language-bound execution input. */
    static final class Step {
        private final DocumentId root;
        private final ManagedOccurrenceBinding target;
        private final ManagedEpochApplicationWork work;
        private final ExternalOrderKey sourceOrder;
        private final TimelineEntry anchor;
        private final ContractsClosureAdapter.CohortInvocation invocation;
        private final ContractsClosureAdapter.RootedCapturedState capturedState;
        private final blue.language.processor.closure.ClosureInvocationInput originalInput;

        private Step(DocumentId root, ManagedOccurrenceBinding target, ManagedEpochApplicationWork work,
                ExternalOrderKey sourceOrder, TimelineEntry anchor, ContractsClosureAdapter.CohortInvocation base,
                ContractsClosureAdapter.RootedCapturedState capturedState, RootedInvocationEvidence rooted) {
            this.root = root; this.target = target; this.work = work; this.sourceOrder = sourceOrder; this.anchor = anchor;
            this.capturedState = capturedState;
            this.originalInput = base.input();
            if (rooted.historicalOrigin() != capturedState.view() || rooted.historicalWork() != work
                    || !rooted.baseInvocationIdentity().equals(originalInput.invocationIdentity())) {
                throw new IllegalArgumentException("Local step must bind its original verified capture");
            }
            // Language adds witness roles to a new snapshot here. Do not ask the bound
            // snapshot to be the same Java object as the pre-binding capture it proves.
            this.invocation = base.withRootedEvidence(rooted);
        }

        void requireCurrentInput(InMemoryDocumentStore documents) {
            capturedState.requireRetainedInput(originalInput, documents);
        }

        DocumentId root() { return root; }
        ManagedOccurrenceBinding target() { return target; }
        ManagedEpochApplicationWork work() { return work; }
        ExternalOrderKey sourceOrder() { return sourceOrder; }
        TimelineEntry anchor() { return anchor; }
        ContractsClosureAdapter.CohortInvocation invocation() { return invocation; }
        ContractsClosureAdapter.RootedCapturedState capturedState() { return capturedState; }
    }
}
