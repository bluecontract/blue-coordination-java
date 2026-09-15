package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochReceipt;
import blue.language.processor.closure.ManagedRepresentationCursor;
import blue.language.processor.closure.ManagedRepresentationCause;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRepresentationTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;

/** Authenticates ordered same-epoch steps from the original atomic publications. */
final class ManagedRepresentationHistory {
    private final InMemoryDocumentStore documents;
    private final ContractsClosurePublicationReceipt stagedPublication;
    private final Map<DocumentId, ManagedCatchUpPlanner.Head> stagedHeads;
    private final Map<DocumentId, ManagedEpochReceipt> stagedReceipts;
    private final ContractsClosureAdapter.RootedCapturedState capturedRoot;
    private final RootedAdmissionSources admissionSources;
    private final DocumentId consumer;
    private final RootedDocumentView stagedRootedView;
    ManagedRepresentationHistory(InMemoryDocumentStore documents) {
        this(documents, null, Map.of(), Map.of(), null, RootedAdmissionSources.NONE, null, null);
    }
    private ManagedRepresentationHistory(InMemoryDocumentStore documents,
            ContractsClosurePublicationReceipt publication, Map<DocumentId, ManagedCatchUpPlanner.Head> heads,
            Map<DocumentId, ManagedEpochReceipt> receipts, ContractsClosureAdapter.RootedCapturedState capturedRoot,
            RootedAdmissionSources admissionSources, DocumentId consumer, RootedDocumentView stagedRootedView) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.stagedPublication = publication;
        this.stagedHeads = Map.copyOf(heads);
        this.stagedReceipts = Map.copyOf(receipts);
        this.capturedRoot = capturedRoot;
        this.admissionSources = Objects.requireNonNull(admissionSources);
        this.consumer = consumer;
        this.stagedRootedView = stagedRootedView;
    }
    ManagedRepresentationHistory forAdmission(RootedAdmissionSources sources) {
        return new ManagedRepresentationHistory(documents, stagedPublication, stagedHeads, stagedReceipts, capturedRoot,
                sources, consumer, stagedRootedView);
    }
    ManagedRepresentationHistory forConsumer(DocumentId consumer) {
        var session = documents.find(consumer).orElse(null);
        return session == null ? this : new ManagedRepresentationHistory(documents, stagedPublication, stagedHeads,
                stagedReceipts, null, session.requireRootedHistory().admissionSources(), consumer, stagedRootedView);
    }
    ManagedRepresentationHistory forCapturedRoot(ContractsClosureAdapter.RootedCapturedState captured) {
        if (captured == null) return this;
        captured.requireCurrentView(documents);
        return new ManagedRepresentationHistory(documents, stagedPublication, stagedHeads, stagedReceipts, captured,
                documents.require(captured.anchor()).requireRootedHistory().admissionSources(), null, stagedRootedView);
    }
    /** Used only to prepare work inside the same atomic publication; execution reauthenticates durable membership. */
    ManagedRepresentationHistory afterPublication(ContractsClosurePublicationReceipt publication,
            Map<DocumentId, ManagedCatchUpPlanner.Head> heads, Map<DocumentId, ManagedEpochReceipt> receipts) {
        return afterPublication(publication, heads, receipts, null);
    }
    /** The exact view staged in the same transaction; never a separately selected source head. */
    ManagedRepresentationHistory afterPublication(ContractsClosurePublicationReceipt publication,
            Map<DocumentId, ManagedCatchUpPlanner.Head> heads, Map<DocumentId, ManagedEpochReceipt> receipts,
            RootedDocumentView view) {
        if (view != null) {
            if (view.result().rootedProjection() == null || view.result() != publication.attempt().processResult())
                throw new IllegalArgumentException("Staged source view must retain the exact publication result");
            view.requireProcessingBoundary(Objects.requireNonNull(publication.rootedTerminalEvidence(),
                    "Staged rooted publication evidence"), documents.catchUpPlansSnapshot());
            var owners = new java.util.LinkedHashSet<>(RootedResultScope.members(view.result()));
            if (!heads.keySet().equals(owners))
                throw new IllegalArgumentException("Staged source view requires the complete derived owner-head set");
            var published = new java.util.LinkedHashMap<DocumentId, InMemoryDocumentStore.DocumentHead>();
            for (DocumentId owner : owners) {
                var changed = heads.get(owner);
                var stagedReceipt = receipts.get(owner);
                long exactEpoch = stagedReceipt == null ? documents.require(owner).epoch() : stagedReceipt.epoch();
                if (changed.epoch() != exactEpoch || (stagedReceipt != null
                        && (!stagedReceipt.documentId().equals(owner)
                            || !stagedReceipt.afterBlueId().equals(changed.blueId())
                            || !stagedReceipt.commitCompanionIdentity().equals(view.result().commitCompanion().companionIdentity()))))
                    throw new IllegalArgumentException("Staged owner head differs from its exact numbered receipt or unchanged epoch");
                published.put(owner, new InMemoryDocumentStore.DocumentHead(changed.epoch(), changed.blueId()));
            }
            view = view.withPublishedHeads(published);
        }
        return new ManagedRepresentationHistory(documents, Objects.requireNonNull(publication), heads, receipts, capturedRoot,
                admissionSources, consumer, view);
    }
    private RootedDocumentView consumerView() {
        if (capturedRoot != null) {
            capturedRoot.requireCurrentView(documents);
            return capturedRoot.view();
        }
        if (consumer == null) return null;
        if (stagedRootedView != null && stagedRootedView.result().rootedProjection().owns(
                ContractsClosureAdapter.closureId(consumer))) return stagedRootedView;
        var session = documents.require(consumer);
        var view = session.rootedView();
        if (view != null) {
            view.requirePublishedHead(consumer, session.epoch(), session.currentRepresentation().blueId());
            session.rootedPublicationPrefix(view);
        }
        return view;
    }
    /** Authenticate committed membership, plus only this transaction's exact owned publication. */
    private java.util.Set<String> publicationPrefix(DocumentId source, RootedDocumentView view) {
        if (view != stagedRootedView) return documents.require(source).rootedPublicationPrefix(view);
        if (stagedPublication == null || !stagedPublication.commits()
                || !RootedResultScope.members(view.result()).contains(source)
                || view.result() != stagedPublication.attempt().processResult())
            throw new IllegalArgumentException("Staged representation view lacks exact owned publication evidence");
        var prefix = new java.util.LinkedHashSet<String>();
        var session = documents.find(source).orElse(null);
        if (session != null && session.rootedView() != null)
            prefix.addAll(session.rootedPublicationPrefix(session.rootedView()));
        prefix.add(view.result().invocationIdentity());
        return java.util.Set.copyOf(prefix);
    }
    /** A co-owned causal position, committed or this publisher's private staged proposal; never an ambient future head. */
    private RootedDocumentView sourceView(DocumentId source, blue.language.processor.ExternalOrderKey boundary) {
        DocumentSession session = documents.require(source);
        var rootView = consumerView();
        var rootSnapshot = capturedRoot != null ? capturedRoot.snapshot()
                : rootView == null ? null : rootView.retainedSnapshot();
        DocumentId rootAnchor = capturedRoot != null ? capturedRoot.anchor() : consumer;
        if (rootView != null && boundary.equals(rootView.logicalBoundary())
                && rootView.result().rootedProjection() != null
                && rootView.result().rootedProjection().owns(ContractsClosureAdapter.closureId(source))) {
            var selected = rootSnapshot.managedDocument(ContractsClosureAdapter.closureId(source));
            rootView.requirePublishedHead(source, selected.epoch(), selected.blueId());
            // Reject a constructed result/view even when all its endpoints and hashes agree.
            publicationPrefix(source, rootView);
            return rootView;
        }
        if (rootView != null && boundary.equals(rootView.logicalBoundary())) {
            var selected = rootSnapshot.managedDocument(ContractsClosureAdapter.closureId(source));
            if (selected != null) {
                var evidence = documents.managedEpochEvidence(source, selected.epoch());
                if (evidence.receipt() != null && evidence.transitionReceipt() != null
                        && evidence.receipt().afterBlueId().equals(selected.blueId())
                        && evidence.receipt().sourceOrder().filter(boundary::equals).isPresent()) {
                    var original = session.rootedViewForInvocation(evidence.transitionReceipt().sourceInvocationIdentity());
                    var projection = original.result().rootedProjection();
                    if (boundary.equals(original.logicalBoundary()) && projection != null
                            && projection.owns(ContractsClosureAdapter.closureId(source))
                            && projection.owns(ContractsClosureAdapter.closureId(rootAnchor))
                            && publicationPrefix(rootAnchor, rootView)
                                    .contains(original.result().invocationIdentity())) {
                        // A split keeps the source position that this root already
                        // co-published. Later source-only positions are not imported.
                        original.requirePublishedHead(source, selected.epoch(), selected.blueId());
                        return original;
                    }
                }
            }
        }
        RootedDocumentView admitted = admissionSources.selected(source, boundary, documents);
        return admitted != null ? admitted : session.rootedViewBefore(boundary);
    }
    private ManagedEpochReceipt receipt(DocumentId id, long epoch) {
        ManagedEpochReceipt staged = stagedReceipts.get(id);
        return staged != null && staged.epoch() == epoch ? staged : documents.managedEpochEvidence(id, epoch).receipt();
    }
    boolean provesReplayable(ManagedLineageIndex.Lineage lineage, long epoch) {
        if (epoch < -1L || epoch >= lineage.currentEpoch()) return false;
        try {
            for (long position = Math.max(0L, epoch); position <= lineage.currentEpoch(); position++) at(lineage.documentId(), position);
            return true;
        } catch (IllegalArgumentException | IllegalStateException unproved) {
            return false;
        }
    }

    Chain at(DocumentId documentId, long epoch) {
        DocumentSession session = documents.find(documentId).orElse(null);
        ManagedCatchUpPlanner.Head head = stagedHeads.get(documentId);
        if (head == null && session != null) head = new ManagedCatchUpPlanner.Head(session.epoch(), session.currentRepresentation().blueId());
        if (head == null) throw new IllegalArgumentException("Historical representation source is absent");
        ManagedEpochReceipt anchor = receipt(documentId, epoch);
        if (anchor == null) throw new IllegalArgumentException("Representation epoch anchor is unavailable");
        List<ManagedRepresentationTransition> transitions = new ArrayList<>();
        String predecessorPosition = anchor.receiptIdentity();
        String beforeBlueId = anchor.afterBlueId();
        List<DocumentSession.ComponentRepresentationTransition> rows = new ArrayList<>(session == null ? List.of() : session.representationTransitions());
        if (session != null && stagedPublication != null && head.epoch() == session.epoch()
                && !head.blueId().equals(session.currentRepresentation().blueId())) {
            var transition = stagedPublication.attempt().processResult().managedTransitionReceipts().stream()
                    .filter(item -> item.documentId().value().equals(documentId.value())).findFirst().orElseThrow();
            rows.add(new DocumentSession.ComponentRepresentationTransition(head.epoch(),
                    session.currentRepresentation().blueId(), head.blueId(), transition.transitionReceiptIdentity(),
                    stagedPublication.publicationIdentity()));
        }
        for (DocumentSession.ComponentRepresentationTransition row : rows) {
            if (row.epoch() != epoch) continue;
            if (!row.beforeBlueId().equals(beforeBlueId) || row.originalPublicationIdentity() == null) {
                throw new IllegalArgumentException("Unproved ordered representation predecessor");
            }
            ContractsClosurePublicationReceipt publication = (stagedPublication != null
                    && stagedPublication.publicationIdentity().equals(row.originalPublicationIdentity())
                    ? Optional.of(stagedPublication) : documents.closurePublicationReceipt(row.originalPublicationIdentity()))
                    .filter(ContractsClosurePublicationReceipt::commits)
                    .orElseThrow(() -> new IllegalArgumentException("Original representation commit is unavailable"));
            if (!publication.documentIds().contains(documentId)
                    || publication.managedSurfaceEvidence().originalInvocation() == null) {
                throw new IllegalArgumentException("Original representation classification input is unavailable");
            }
            ManagedRepresentationTransition proved = new ManagedRepresentationTransition(
                    ContractsClosureAdapter.closureId(documentId), epoch,
                    anchor.receiptIdentity(), predecessorPosition,
                    publication.managedSurfaceEvidence().originalInvocation(),
                    publication.attempt().processResult(), row.transitionReceiptIdentity());
            if (proved.rootedCheckpointReferenceProofIdentity().isPresent()) {
                RootedTerminalEvidence rooted = publication.rootedTerminalEvidence();
                if (rooted == null) throw new IllegalArgumentException("Original rooted representation authority is unavailable");
                rooted.requireCheckpointReferencePosition(publication.attempt().processResult(), documentId,
                        proved.rootedCheckpointReferenceProofIdentity().orElseThrow());
            }
            if (!proved.transitionReceipt().beforeBlueId().equals(row.beforeBlueId())
                    || !proved.transitionReceipt().afterBlueId().equals(row.afterBlueId())) {
                throw new IllegalArgumentException("Representation commit differs from its durable source history");
            }
            transitions.add(proved);
            predecessorPosition = proved.positionIdentity();
            beforeBlueId = row.afterBlueId();
        }
        ManagedEpochReceipt next = receipt(documentId, epoch + 1L);
        if (next != null && !next.beforeBlueId().orElseThrow().equals(beforeBlueId)) {
            throw new IllegalArgumentException("Unproved gap before the next immutable source receipt");
        }
        if (next == null && (epoch != head.epoch()
                || !head.blueId().equals(beforeBlueId))) {
            throw new IllegalArgumentException("Unproved terminal representation head");
        }
        return new Chain(documentId, epoch, anchor, List.copyOf(transitions),
                predecessorPosition, next == null ? null : next.contractsTransitionReceiptIdentity());
    }

    /** Captured tails never grow to chase representation changes made by their own reconciliation. */
    Chain atCaptured(DocumentId documentId, long epoch, ManagedRepresentationCursor cursor) {
        return captured(at(documentId, epoch), cursor);
    }

    /** Rooted history may exclude a genuine next receipt only by its authenticated frozen source view/order. */
    Chain atRootedCaptured(DocumentId documentId, long epoch, ManagedRepresentationCursor cursor,
            blue.language.processor.ExternalOrderKey boundary) {
        Chain full = at(documentId, epoch);
        if (full.nextRevisionReceiptIdentity() != null && (cursor == null || cursor.nextRevisionReceiptIdentity() == null)) {
            var view = sourceView(documentId, boundary);
            var selected = view.retainedSnapshot().managedDocument(ContractsClosureAdapter.closureId(documentId));
            var next = receipt(documentId, Math.addExact(epoch, 1L));
            if (selected != null && selected.epoch() == epoch
                    && next.sourceOrder().orElseThrow(() -> new IllegalArgumentException("Next source receipt lacks exact order"))
                        .compareTo(boundary) >= 0) {
                full = new Chain(documentId, epoch, full.anchor(), full.transitions(), full.targetPositionIdentity(), null);
            }
        }
        if (cursor == null && full.nextRevisionReceiptIdentity() == null) {
            var view = sourceView(documentId, boundary);
            var selected = view.retainedSnapshot().managedDocument(ContractsClosureAdapter.closureId(documentId));
            if (selected != null && selected.epoch() == epoch) {
                var prefix = prefixAt(full, view, boundary);
                String target = prefix.isEmpty() ? full.anchor().receiptIdentity()
                        : prefix.get(prefix.size() - 1).positionIdentity();
                String lastBlue = prefix.isEmpty() ? full.anchor().afterBlueId()
                        : prefix.get(prefix.size() - 1).transitionReceipt().afterBlueId();
                if (!lastBlue.equals(selected.blueId())) {
                    throw new IllegalArgumentException("Frozen source view omits an authenticated terminal position");
                }
                full = new Chain(documentId, epoch, full.anchor(), prefix, target, null);
            }
        }
        return captured(full, cursor);
    }

    private static Chain captured(Chain full, ManagedRepresentationCursor cursor) {
        if (cursor == null) return full;
        if (!full.anchor().receiptIdentity().equals(cursor.anchorReceiptIdentity())
                || !Objects.equals(full.nextRevisionReceiptIdentity(), cursor.nextRevisionReceiptIdentity())) {
            throw new IllegalArgumentException("Captured representation anchor or immutable next revision changed");
        }
        int target = -1;
        for (int i = 0; i < full.transitions().size(); i++) {
            if (full.transitions().get(i).positionIdentity().equals(cursor.targetPositionIdentity())) target = i;
        }
        if (target < 0) throw new IllegalArgumentException("Captured representation target was not committed");
        if (cursor.nextRevisionReceiptIdentity() != null && target != full.transitions().size() - 1) {
            throw new IllegalArgumentException("Intermediate target is not the exact next revision predecessor");
        }
        return new Chain(full.documentId(), full.epoch(), full.anchor(), List.copyOf(full.transitions().subList(0, target + 1)),
                cursor.targetPositionIdentity(), cursor.nextRevisionReceiptIdentity());
    }

    Optional<ManagedRepresentationCause> terminalSuccessor(DocumentId source, long epoch, String occurrence,
            blue.language.processor.ExternalOrderKey boundary,
            java.util.function.Function<String, blue.language.provider.CyclicSetProof> proofs) {
        var view = sourceView(source, boundary);
        var selected = Objects.requireNonNull(view.retainedSnapshot().managedDocument(ContractsClosureAdapter.closureId(source)));
        return terminalSuccessor(source, epoch, occurrence, boundary, selected, proofs);
    }

    /** Captures the terminal tail at an exact source-owned publication before the attachment boundary. */
    Optional<ManagedRepresentationCause> terminalSuccessor(DocumentId source, long epoch, String occurrence,
            blue.language.processor.ExternalOrderKey boundary,
            blue.language.processor.closure.ManagedDocumentSnapshot selected,
            java.util.function.Function<String, blue.language.provider.CyclicSetProof> proofs) {
        if (selected.epoch() != epoch) return Optional.empty();
        Chain complete = atRootedCaptured(source, epoch, null, boundary);
        if (complete.nextRevisionReceiptIdentity() != null) {
            if (!complete.transitions().isEmpty()) {
                throw new IllegalArgumentException("A frozen terminal tail cannot conceal a later numbered source receipt");
            }
            return Optional.empty();
        }
        DocumentSession session = documents.require(source);
        RootedDocumentView sourceView = sourceView(source, boundary);
        var sourceAtView = sourceView.retainedSnapshot().managedDocument(ContractsClosureAdapter.closureId(source));
        if (sourceAtView == null || sourceAtView.epoch() != epoch || !sourceAtView.blueId().equals(selected.blueId())) {
            throw new IllegalArgumentException("Numbered terminal source differs from its frozen source publication: source="
                    + source + " epoch=" + epoch + " selected=" + selected.blueId() + " boundary=" + boundary
                    + " frozenEpoch=" + (sourceAtView == null ? null : sourceAtView.epoch())
                    + " frozenBlueId=" + (sourceAtView == null ? null : sourceAtView.blueId())
                    + " sourcePublication=" + sourceView.result().invocationIdentity());
        }
        List<ManagedRepresentationTransition> prefix = prefixAt(complete, sourceView, boundary);
        String lastBlueId = prefix.isEmpty() ? complete.anchor().afterBlueId()
                : prefix.get(prefix.size() - 1).transitionReceipt().afterBlueId();
        if (!lastBlueId.equals(sourceAtView.blueId())) {
            throw new IllegalArgumentException("Frozen source publication has an unproved positional tail");
        }
        if (prefix.isEmpty()) return Optional.empty();
        var first = prefix.get(0);
        return Optional.of(new ManagedRepresentationCause(occurrence, first,
                prefix.get(prefix.size() - 1).positionIdentity(), null,
                proofs.apply(first.transitionReceipt().afterBlueId())));
    }

    /** Revalidates the captured goal as an actual prefix; later same-epoch publications cannot extend it. */
    void verifySuccessor(blue.coordination.api.ManagedEpochApplicationWork work,
            blue.language.processor.closure.ManagedDocumentSnapshot selected,
            blue.language.processor.ExternalOrderKey boundary) {
        var successor = work.successorRepresentationCause().orElseThrow();
        var anchor = receipt(work.sourceDocumentId(), work.sourceEpoch());
        if (anchor == null || !anchor.receiptIdentity().equals(work.sourceReceiptIdentity())
                || !anchor.afterBlueId().equals(successor.beforeBlueId())
                || selected.epoch() != work.sourceEpoch() || successor.nextRevisionReceiptIdentity() != null) {
            throw new IllegalArgumentException("Numbered successor changed its immutable source anchor or epoch");
        }
        var cursor = new ManagedRepresentationCursor(anchor.receiptIdentity(), anchor.receiptIdentity(),
                successor.targetPositionIdentity(), null);
        Chain captured = atRootedCaptured(work.sourceDocumentId(), work.sourceEpoch(), cursor, boundary);
        if (captured.transitions().isEmpty()
                || !captured.transitions().get(0).positionIdentity().equals(successor.transition().positionIdentity())) {
            throw new IllegalArgumentException("Numbered successor omitted its first exact position");
        }
        var last = captured.transitions().get(captured.transitions().size() - 1);
        RootedDocumentView targetView = documents.require(work.sourceDocumentId())
                .rootedViewForInvocation(last.originalResult().invocationIdentity());
        var positioned = prefixAt(at(work.sourceDocumentId(), work.sourceEpoch()), targetView, boundary);
        if (!positioned.stream().map(ManagedRepresentationTransition::positionIdentity).toList()
                .equals(captured.transitions().stream().map(ManagedRepresentationTransition::positionIdentity).toList())
                || !last.transitionReceipt().afterBlueId().equals(selected.blueId())) {
            throw new IllegalArgumentException("Numbered successor differs from its captured source-view position");
        }
        verifySupplied(successor.transition());
    }

    private List<ManagedRepresentationTransition> prefixAt(Chain chain, RootedDocumentView view,
            blue.language.processor.ExternalOrderKey boundary) {
        // A view's stored position also clamps earlier causal dates to its existing frontier.
        // Comparing with rootedViewBefore proves that it is no later than this exact cutoff.
        var eligible = publicationPrefix(chain.documentId(), sourceView(chain.documentId(), boundary));
        if (!eligible.contains(view.result().invocationIdentity())) {
            throw new IllegalArgumentException("Representation target is after the frozen attachment boundary");
        }
        var membership = publicationPrefix(chain.documentId(), view);
        var prefix = new ArrayList<ManagedRepresentationTransition>();
        boolean outside = false;
        for (var transition : chain.transitions()) {
            boolean included = membership.contains(transition.originalResult().invocationIdentity());
            if (outside && included) throw new IllegalArgumentException("Source view skips an earlier representation publication");
            if (included) prefix.add(transition); else outside = true;
        }
        return List.copyOf(prefix);
    }

    /** Host admission proves both original publication membership and the captured next position. */
    void verifyCause(ManagedRepresentationCause cause, ManagedOccurrenceBinding occurrence) {
        verifyCause(cause, occurrence, null);
    }

    void verifyCause(ManagedRepresentationCause cause, ManagedOccurrenceBinding occurrence,
            blue.language.processor.ExternalOrderKey rootedBoundary) {
        if (occurrence.active() || occurrence.pendingHistoricalEpoch() == null
                || !occurrence.targetDocumentId().equals(cause.childDocumentId())
                || occurrence.pendingHistoricalEpoch().longValue() != cause.fromEpoch()
                || !occurrence.occurrenceIdentity().equals(cause.targetOccurrenceIdentity())) {
            throw new IllegalArgumentException("Representation cause does not own the pending occurrence");
        }
        Chain chain = rootedBoundary == null
                ? atCaptured(DocumentId.of(cause.childDocumentId().value()), cause.fromEpoch(), occurrence.pendingRepresentationCursor())
                : atRootedCaptured(DocumentId.of(cause.childDocumentId().value()), cause.fromEpoch(),
                        occurrence.pendingRepresentationCursor(), rootedBoundary);
        var next = chain.next(occurrence.pendingRepresentationCursor(), occurrence.expectedTargetBlueId())
                .orElseThrow(() -> new IllegalArgumentException("Captured representation chain is already complete"));
        if (!next.positionIdentity().equals(cause.transition().positionIdentity())
                || !chain.targetPositionIdentity().equals(cause.targetPositionIdentity())
                || !Objects.equals(chain.nextRevisionReceiptIdentity(), cause.nextRevisionReceiptIdentity())) {
            throw new IllegalArgumentException("Representation cause skips or changes its authenticated history target");
        }
        verifySupplied(cause.transition());
    }

    /** Supplied recomputable evidence has no authority without exact durable membership. */
    void verifySupplied(ManagedRepresentationTransition supplied) {
        Chain chain = at(DocumentId.of(supplied.documentId().value()), supplied.epoch());
        ManagedRepresentationTransition actual = chain.transitions().stream()
                .filter(row -> row.positionIdentity().equals(supplied.positionIdentity()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Supplied representation position was not committed"));
        if (!actual.rootedCheckpointReferenceProofIdentity().equals(supplied.rootedCheckpointReferenceProofIdentity())) {
            throw new IllegalArgumentException("Supplied checkpoint proof differs from its original rooted publication");
        }
        if (actual.rootedCheckpointReferenceProofIdentity().isPresent()) {
            var original = actual.originalResult().rootedProjection();
            var proposed = supplied.originalResult().rootedProjection();
            if (original == null || proposed == null || !original.context().identity().equals(proposed.context().identity())
                    || !original.deliveryBasisIdentity().equals(proposed.deliveryBasisIdentity())
                    || !original.invocationIdentity().equals(proposed.invocationIdentity())
                    || !original.companionIdentity().equals(proposed.companionIdentity())) {
                throw new IllegalArgumentException("Supplied checkpoint position changes original rooted ownership or companion");
            }
        }
        if (!actual.originalInput().invocationIdentity().equals(supplied.originalInput().invocationIdentity())
                || !actual.originalResult().outputClosureIdentity().equals(supplied.originalResult().outputClosureIdentity())
                || !actual.originalResult().platformCommitCompanion().companionIdentity().equals(
                        supplied.originalResult().platformCommitCompanion().companionIdentity())) {
            throw new IllegalArgumentException("Supplied representation evidence differs from the durable commit");
        }
    }

    record Chain(DocumentId documentId, long epoch, ManagedEpochReceipt anchor,
            List<ManagedRepresentationTransition> transitions, String targetPositionIdentity,
            String nextRevisionReceiptIdentity) {
        Optional<ManagedRepresentationTransition> next(ManagedRepresentationCursor cursor, String currentBlueId) {
            String position = cursor == null ? anchor.receiptIdentity() : cursor.positionIdentity();
            if (cursor != null && (!cursor.anchorReceiptIdentity().equals(anchor.receiptIdentity())
                    || !cursor.targetPositionIdentity().equals(targetPositionIdentity)
                    || !Objects.equals(cursor.nextRevisionReceiptIdentity(), nextRevisionReceiptIdentity))) {
                throw new IllegalArgumentException("Historical representation target changed");
            }
            String expected = anchor.afterBlueId();
            for (ManagedRepresentationTransition transition : transitions) {
                if (position.equals(transition.predecessorPositionIdentity())) {
                    if (!currentBlueId.equals(expected)) throw new IllegalArgumentException("Historical representation exact predecessor changed");
                    return Optional.of(transition);
                }
                expected = transition.transitionReceipt().afterBlueId();
            }
            if (!position.equals(targetPositionIdentity) || !currentBlueId.equals(expected)) {
                throw new IllegalArgumentException("Historical representation position is unknown or incomplete");
            }
            return Optional.empty();
        }
    }
}
