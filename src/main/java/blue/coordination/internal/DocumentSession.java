package blue.coordination.internal;

import blue.coordination.internal.ContractsClosureAdapter.ProjectionUnavailableException;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.SessionStatus;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

final class DocumentSession {
    private final DocumentId documentId;
    private final String authoredInitialBlueId;
    private List<SubscriptionDelta.Entry> activeSubscriptions;
    private final List<DocumentRevision> revisions = new ArrayList<>();
    private final Set<String> terminalEntryBlueIds = new LinkedHashSet<>();
    private final Set<String> transitionReceipts = new LinkedHashSet<>();
    private final List<ComponentRepresentationTransition>
            componentRepresentationTransitions = new ArrayList<>();
    private RootedDocumentHistory rootedHistory;
    private RootedDocumentView rootedView;
    private final List<RootedViewPosition> rootedViewPositions = new ArrayList<>();
    // Derived, session-local indexes. A value-equal view is not retained publication authority.
    private final Map<RootedDocumentView, Integer> rootedViewFirstPositions = new IdentityHashMap<>();
    private final Map<String, Integer> rootedInvocationFirstPositions = new LinkedHashMap<>();
    private final StateEpochs stateEpochs = new StateEpochs();
    private EmbeddedOnlyLayout layout;
    private EmbeddedOnlyLayout readyLayout;
    private Map<String, DocumentId> readyEmbeddedChildren;
    private SessionStatus status;
    private ExternalOrderKey readyThrough;
    private long epoch;
    private long readyEpoch;
    private long graphPublishedEpoch;
    private long applicationSequence;

    public DocumentSession(
            DocumentId documentId,
            ExactValue authoredInitialState,
            EmbeddedOnlyLayout initializedLayout,
            List<SubscriptionDelta.Entry> activeSubscriptions,
            ExternalOrderKey admissionFrontier,
            DocumentRevision initializationRevision) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.authoredInitialBlueId = Objects.requireNonNull(
                authoredInitialState, "authoredInitialState").blueId();
        this.layout = Objects.requireNonNull(
                initializedLayout, "initializedLayout");
        this.readyLayout = this.layout;
        this.readyEmbeddedChildren = childrenFromLayout(this.readyLayout);
        this.activeSubscriptions = List.copyOf(Objects.requireNonNull(
                activeSubscriptions, "activeSubscriptions"));
        this.status = SessionStatus.READY;
        this.readyThrough = Objects.requireNonNull(
                admissionFrontier, "admissionFrontier");
        this.epoch = 0L;
        this.readyEpoch = 0L;
        this.graphPublishedEpoch = -1L;
        this.applicationSequence = 0L;
        this.revisions.add(Objects.requireNonNull(
                initializationRevision, "initializationRevision"));
        if (!initializationRevision.documentId().equals(documentId)
                || initializationRevision.epoch() != 0L
                || initializationRevision.kind() != DocumentRevision.Kind.INITIALIZATION
                || !initializationRevision.after().blueId()
                        .equals(initializedLayout.rootBlueId())) {
            throw new IllegalArgumentException(
                    "Initialization revision does not belong to session");
        }
        stateEpochs.record(initializationRevision);
        this.transitionReceipts.add(
                "initialization|" + documentId.value());
    }

    private DocumentSession(DocumentSession source) {
        this.documentId = source.documentId;
        this.authoredInitialBlueId = source.authoredInitialBlueId;
        this.rootedHistory = source.rootedHistory;
        this.activeSubscriptions = source.activeSubscriptions;
        this.revisions.addAll(source.revisions);
        this.terminalEntryBlueIds.addAll(source.terminalEntryBlueIds);
        this.transitionReceipts.addAll(source.transitionReceipts);
        this.componentRepresentationTransitions.addAll(
                source.componentRepresentationTransitions);
        this.stateEpochs.copyFrom(source.stateEpochs);
        this.layout = source.layout;
        this.readyLayout = source.readyLayout;
        this.readyEmbeddedChildren = source.readyEmbeddedChildren;
        this.status = source.status;
        this.readyThrough = source.readyThrough;
        this.epoch = source.epoch;
        this.readyEpoch = source.readyEpoch;
        this.graphPublishedEpoch = source.graphPublishedEpoch;
        this.applicationSequence = source.applicationSequence;
    }

    /**
     * Returns an independent mutable session image for a store-level atomic
     * publication. Exact revisions, layouts, and subscription entries are
     * immutable and therefore remain structurally shared.
     */
    synchronized DocumentSession copyForAtomicPublication() {
        DocumentSession copy = new DocumentSession(this);
        copy.rootedView = rootedView;
        copy.rootedViewPositions.addAll(rootedViewPositions);
        copy.rootedViewFirstPositions.putAll(rootedViewFirstPositions);
        copy.rootedInvocationFirstPositions.putAll(rootedInvocationFirstPositions);
        return copy;
    }

    synchronized RootedDocumentView rootedView() { return rootedView; }

    synchronized void retainRootedView(RootedDocumentView view) {
        Objects.requireNonNull(view, "view").requirePublishedHead(documentId, epoch, currentRepresentation().blueId());
        ExternalOrderKey boundary = view.logicalBoundary();
        if (!rootedViewPositions.isEmpty()) {
            ExternalOrderKey prior = rootedViewPositions.get(rootedViewPositions.size() - 1).boundary();
            if (prior != null && (boundary == null || prior.compareTo(boundary) > 0)) boundary = prior;
        }
        appendRootedViewPosition(new RootedViewPosition(view, boundary));
        rootedView = view;
    }

    private void appendRootedViewPosition(RootedViewPosition position) {
        int index = rootedViewPositions.size();
        rootedViewPositions.add(position);
        rootedViewFirstPositions.putIfAbsent(position.view(), index);
        rootedInvocationFirstPositions.putIfAbsent(position.view().result().invocationIdentity(), index);
    }

    /** Exact committed view immediately before the attachment input, never the ambient latest head. */
    synchronized RootedDocumentView rootedViewBefore(ExternalOrderKey boundary) {
        Objects.requireNonNull(boundary, "attachment boundary");
        int low = 0, high = rootedViewPositions.size();
        while (low < high) {
            int middle = low + (high - low) / 2;
            ExternalOrderKey candidate = rootedViewPositions.get(middle).boundary();
            if (candidate == null || candidate.compareTo(boundary) < 0) low = middle + 1;
            else high = middle;
        }
        if (low > 0) return rootedViewPositions.get(low - 1).view();
        throw new ProjectionUnavailableException("No authenticated rooted source view before attachment boundary " + boundary);
    }

    /** The exact retained object position, not an endpoint or invocation-identity approximation. */
    synchronized void requireRetainedRootedView(RootedDocumentView selected) {
        retainedRootedViewPosition(selected);
    }

    /** Constant-time membership in a frozen publication prefix; later appends cannot extend it. */
    synchronized boolean rootedPublicationIncludes(RootedDocumentView selected, String invocationIdentity) {
        int selectedPosition = retainedRootedViewPosition(selected);
        Integer publicationPosition = rootedInvocationFirstPositions.get(invocationIdentity);
        return publicationPosition != null && publicationPosition <= selectedPosition;
    }

    private int retainedRootedViewPosition(RootedDocumentView selected) {
        Integer position = rootedViewFirstPositions.get(selected);
        if (position == null) {
            throw new ProjectionUnavailableException("Source view is not an actual retained publication position");
        }
        return position;
    }

    /** Exact publication membership through a retained view, independent of repeated endpoint identities. */
    synchronized java.util.Set<String> rootedPublicationPrefix(RootedDocumentView selected) {
        var invocations = new java.util.LinkedHashSet<String>();
        int selectedPosition = retainedRootedViewPosition(selected);
        for (int index = 0; index <= selectedPosition; index++) {
            invocations.add(rootedViewPositions.get(index).view().result().invocationIdentity());
        }
        return java.util.Set.copyOf(invocations);
    }

    synchronized RootedDocumentView rootedViewForInvocation(String invocationIdentity) {
        Integer position = rootedInvocationFirstPositions.get(invocationIdentity);
        if (position == null) throw new ProjectionUnavailableException("Original source publication view is unavailable");
        return rootedViewPositions.get(position).view();
    }

    record RootedViewPosition(RootedDocumentView view, ExternalOrderKey boundary) { }

    /** Complete private storage image; restoration never executes admission or a transition. */
    record StoredState(DocumentId documentId, String authoredInitialBlueId,
            List<SubscriptionDelta.Entry> activeSubscriptions, List<DocumentRevision> revisions,
            Set<String> terminalEntryBlueIds, Set<String> transitionReceipts,
            List<ComponentRepresentationTransition> representationTransitions,
            RootedDocumentHistory rootedHistory, RootedDocumentView rootedView,
            List<RootedViewPosition> rootedViewPositions, Map<String, Long> stateEpochs,
            Set<String> ambiguousStates, EmbeddedOnlyLayout layout, EmbeddedOnlyLayout readyLayout,
            Map<String, DocumentId> readyEmbeddedChildren, SessionStatus status, ExternalOrderKey readyThrough,
            long epoch, long readyEpoch, long graphPublishedEpoch, long applicationSequence) { }

    synchronized StoredState storedState() {
        return new StoredState(documentId, authoredInitialBlueId, activeSubscriptions, List.copyOf(revisions),
                Collections.unmodifiableSet(new LinkedHashSet<>(terminalEntryBlueIds)),
                Collections.unmodifiableSet(new LinkedHashSet<>(transitionReceipts)),
                List.copyOf(componentRepresentationTransitions), rootedHistory, rootedView,
                List.copyOf(rootedViewPositions), Collections.unmodifiableMap(new LinkedHashMap<>(stateEpochs.first)),
                Collections.unmodifiableSet(new LinkedHashSet<>(stateEpochs.ambiguous)), layout, readyLayout,
                readyEmbeddedChildren, status, readyThrough, epoch, readyEpoch, graphPublishedEpoch, applicationSequence);
    }

    static DocumentSession restoreStored(StoredState state) { return new DocumentSession(state); }

    private DocumentSession(StoredState state) {
        this.documentId = Objects.requireNonNull(state.documentId());
        this.authoredInitialBlueId = Objects.requireNonNull(state.authoredInitialBlueId());
        this.activeSubscriptions = List.copyOf(state.activeSubscriptions());
        this.revisions.addAll(state.revisions());
        this.terminalEntryBlueIds.addAll(state.terminalEntryBlueIds());
        this.transitionReceipts.addAll(state.transitionReceipts());
        this.componentRepresentationTransitions.addAll(state.representationTransitions());
        this.rootedHistory = state.rootedHistory();
        this.rootedView = state.rootedView();
        state.rootedViewPositions().forEach(this::appendRootedViewPosition);
        this.layout = Objects.requireNonNull(state.layout());
        this.readyLayout = Objects.requireNonNull(state.readyLayout());
        this.readyEmbeddedChildren = copyChildren(state.readyEmbeddedChildren());
        this.epoch = state.epoch();
        this.applicationSequence = state.applicationSequence();
        restoreCoordinationState(state.status(), state.readyThrough(), state.readyEpoch(), state.graphPublishedEpoch());
        var terminals = new LinkedHashSet<String>();
        if (epoch < 0 || epoch != revisions.size() - 1L || applicationSequence != epoch
                || revisions.get(0).kind() != DocumentRevision.Kind.INITIALIZATION) {
            throw new IllegalArgumentException("Stored session history is not complete and contiguous");
        }
        for (int i = 0; i < revisions.size(); i++) {
            DocumentRevision revision = revisions.get(i);
            if (!documentId.equals(revision.documentId()) || revision.epoch() != i
                    || revision.rootApplicationOrder() != i) {
                throw new IllegalArgumentException("Stored revision is outside its exact session position");
            }
            stateEpochs.record(revision);
            revision.sourceEntry().ifPresent(entry -> terminals.add(entry.blueId()));
        }
        if (!stateEpochs.first.equals(state.stateEpochs()) || !stateEpochs.ambiguous.equals(state.ambiguousStates())
                || !terminals.equals(terminalEntryBlueIds)
                || !transitionReceipts.contains("initialization|" + documentId.value())
                || transitionReceipts.size() != revisions.size() + componentRepresentationTransitions.size()) {
            throw new IllegalArgumentException("Stored session indexes differ from retained history");
        }
        long priorEpoch = -1;
        var represented = new LinkedHashMap<Long, String>();
        var representationReceipts = new LinkedHashSet<String>();
        for (ComponentRepresentationTransition transition : componentRepresentationTransitions) {
            if (transition.epoch() < priorEpoch || transition.epoch() > epoch
                    || !transition.beforeBlueId().equals(represented.getOrDefault(transition.epoch(),
                            revision(transition.epoch()).after().blueId()))
                    || !transitionReceipts.contains(transition.transitionReceiptIdentity())
                    || !representationReceipts.add(transition.transitionReceiptIdentity())) {
                throw new IllegalArgumentException("Stored same-epoch representation history is invalid");
            }
            represented.put(transition.epoch(), transition.afterBlueId()); priorEpoch = transition.epoch();
        }
        if (!layout.rootBlueId().equals(represented.getOrDefault(epoch, currentRevision().after().blueId()))
                || !storedRepresentationAt(readyEpoch, readyLayout.rootBlueId())) {
            throw new IllegalArgumentException("Stored current or READY layout differs from exact history");
        }
        if (rootedHistory != null && (!authoredInitialBlueId.equals(rootedHistory.descriptor().get("initialDocumentBlueId"))
                || !documentId.value().equals(rootedHistory.descriptor().get("documentId")))) {
            throw new IllegalArgumentException("Stored rooted history belongs to another document");
        }
        ExternalOrderKey prior = null;
        for (RootedViewPosition position : rootedViewPositions) {
            RootedDocumentView view = Objects.requireNonNull(position.view());
            long retainedEpoch = view.retainedEpoch(documentId);
            String head = view.snapshot().managedDocument(ContractsClosureAdapter.closureId(documentId)).blueId();
            view.requirePublishedHead(documentId, retainedEpoch, head);
            ExternalOrderKey expectedBoundary = view.logicalBoundary();
            if (prior != null && (expectedBoundary == null || prior.compareTo(expectedBoundary) > 0)) expectedBoundary = prior;
            if (!storedRepresentationAt(retainedEpoch, head)
                    || !Objects.equals(position.boundary(), expectedBoundary)) {
                throw new IllegalArgumentException("Stored rooted position is outside retained session history");
            }
            prior = position.boundary();
        }
        if (rootedViewPositions.isEmpty() ? rootedView != null
                : rootedViewPositions.get(rootedViewPositions.size() - 1).view() != rootedView) {
            throw new IllegalArgumentException("Current rooted view is not the final retained position");
        }
    }

    private boolean storedRepresentationAt(long selectedEpoch, String blueId) {
        return selectedEpoch >= 0 && selectedEpoch <= epoch && (revision(selectedEpoch).after().blueId().equals(blueId)
                || representationTransitionRange(selectedEpoch).stream().anyMatch(row ->
                        row.beforeBlueId().equals(blueId) || row.afterBlueId().equals(blueId)));
    }

    /** Exact historical position in the already validated numbered/representation chain; never a latest-state lookup. */
    synchronized boolean retainsStoredPosition(long selectedEpoch, String blueId) {
        return storedRepresentationAt(selectedEpoch, blueId);
    }

    public DocumentId documentId() {
        return documentId;
    }

    public String authoredInitialBlueId() {
        return authoredInitialBlueId;
    }

    synchronized void establishRootedHistory(RootedDocumentHistory history) {
        RootedDocumentHistory selected = Objects.requireNonNull(history, "history");
        if (rootedHistory != null || epoch != 0L
                || !authoredInitialBlueId.equals(selected.descriptor().get("initialDocumentBlueId"))
                || !documentId.value().equals(selected.descriptor().get("documentId"))) {
            throw new IllegalStateException("Rooted history must be established once at exact admission");
        }
        rootedHistory = selected;
    }

    synchronized RootedDocumentHistory requireRootedHistory() {
        if (rootedHistory == null) {
            throw new IllegalStateException("Document has no authenticated rooted history basis: " + documentId);
        }
        return rootedHistory;
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized long readyEpoch() {
        return readyEpoch;
    }

    public synchronized long graphPublishedEpoch() {
        return graphPublishedEpoch;
    }

    public synchronized boolean isLocallyReady() {
        return status == SessionStatus.READY
                && readyEpoch == epoch
                && graphPublishedEpoch == epoch;
    }

    public synchronized long nextApplicationOrder() {
        return Math.addExact(applicationSequence, 1L);
    }

    public synchronized EmbeddedOnlyLayout layout() {
        return layout;
    }

    /** Returns the exact currently published component representation. */
    public synchronized ExactValue currentRepresentation() {
        return layout.semanticRoot();
    }

    /** Returns the exact layout at the application-visible ready head. */
    public synchronized EmbeddedOnlyLayout readyLayout() {
        return readyLayout;
    }

    /** Returns the exact application-visible component representation. */
    public synchronized ExactValue readyRepresentation() {
        return readyLayout.semanticRoot();
    }

    /** Returns the topology published with the application-visible head. */
    synchronized Map<String, DocumentId> readyEmbeddedChildren() {
        return readyEmbeddedChildren;
    }

    /** Restores topology evidence already authenticated for the ready head. */
    synchronized void restoreReadyEmbeddedChildren(
            Map<String, DocumentId> embeddedChildren) {
        readyEmbeddedChildren = copyChildren(embeddedChildren);
    }

    public synchronized List<SubscriptionDelta.Entry> activeSubscriptions() {
        return activeSubscriptions;
    }

    public synchronized SessionStatus status() {
        return status;
    }

    public synchronized ExternalOrderKey readyThrough() {
        return readyThrough;
    }

    public synchronized DocumentRevision currentRevision() {
        return revisions.get(revisions.size() - 1);
    }

    /** Returns the exact revision exposed to normal application reads. */
    public synchronized DocumentRevision readyRevision() {
        return revision(readyEpoch);
    }

    public synchronized List<DocumentRevision> revisions() {
        return Collections.unmodifiableList(new ArrayList<>(revisions));
    }

    public synchronized DocumentRevision revision(long childEpoch) {
        if (childEpoch < 0L || childEpoch >= revisions.size()) {
            throw new IllegalArgumentException(
                    "Unknown revision epoch " + childEpoch);
        }
        DocumentRevision revision = revisions.get(Math.toIntExact(childEpoch));
        if (revision.epoch() != childEpoch) {
            throw new IllegalStateException("Revision history is not contiguous");
        }
        return revision;
    }

    public synchronized List<DocumentRevision> revisionsAfter(
            long epochExclusive) {
        long firstEpoch = Math.max(0L, Math.addExact(epochExclusive, 1L));
        if (firstEpoch >= revisions.size()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(
                revisions.subList(Math.toIntExact(firstEpoch), revisions.size())));
    }

    public synchronized boolean hasTerminalEntry(String entryBlueId) {
        return terminalEntryBlueIds.contains(entryBlueId);
    }

    public synchronized Optional<DocumentRevision> revisionForEntry(
            String entryBlueId) {
        String identity = Objects.requireNonNull(entryBlueId, "entryBlueId");
        return revisions.stream()
                .filter(revision -> revision.sourceEntry()
                        .map(entry -> entry.blueId().equals(identity))
                        .orElse(false))
                .findFirst();
    }

    public synchronized boolean hasTransitionReceipt(String receiptId) {
        return transitionReceipts.contains(Objects.requireNonNull(
                receiptId, "receiptId"));
    }

    synchronized boolean hasComponentRepresentationTransition(
            long transitionEpoch,
            String beforeBlueId,
            String afterBlueId,
            String transitionReceiptIdentity) {
        return representationTransitionRange(transitionEpoch).stream().anyMatch(row ->
                row.beforeBlueId().equals(beforeBlueId)
                        && row.afterBlueId().equals(afterBlueId)
                        && row.transitionReceiptIdentity().equals(transitionReceiptIdentity));
    }

    public synchronized OptionalLong epochForState(String exactBlueId) {
        return stateEpochs.first(exactBlueId);
    }

    /** Authenticated operation lineage includes representation steps without inventing numbered epochs. */
    synchronized boolean recognizesOperationTarget(String exactBlueId) {
        return authoredInitialBlueId.equals(exactBlueId) || stateEpochs.first(exactBlueId).isPresent()
                || componentRepresentationTransitions.stream().anyMatch(row -> row.beforeBlueId().equals(exactBlueId)
                        || row.afterBlueId().equals(exactBlueId));
    }

    synchronized long resolveAdmissionEpoch(
            String stateBlueId,
            Long exactEpoch) {
        String state = Objects.requireNonNull(stateBlueId, "stateBlueId");
        if (exactEpoch == null) {
            return stateEpochs.resolve(documentId, authoredInitialBlueId, state);
        }
        if (exactEpoch > epoch) {
            throw invalidAdmission("unknown epoch " + exactEpoch);
        }
        if (!revision(exactEpoch).after().blueId().equals(state)) {
            throw invalidAdmission("epoch does not match state " + state);
        }
        return exactEpoch;
    }

    public synchronized void markCatchingUp() {
        if (status == SessionStatus.BLOCKED
                || status == SessionStatus.TERMINATED) {
            throw new IllegalStateException("Cannot catch up from " + status);
        }
        status = SessionStatus.CATCHING_UP;
    }

    public synchronized void markBlocked() {
        status = SessionStatus.BLOCKED;
    }

    public synchronized void markGraphPublished() {
        graphPublishedEpoch = epoch;
    }

    /** Publishes one verified terminal head as the application-visible head. */
    public synchronized void markTerminated() {
        markTerminated(childrenFromLayout(layout));
    }

    /** Publishes a terminal head and its exact occurrence topology together. */
    synchronized void markTerminated(
            Map<String, DocumentId> embeddedChildren) {
        if (graphPublishedEpoch != epoch) {
            throw new IllegalStateException(
                    "Cannot publish TERMINATED before graph epoch " + epoch
                            + " is published for " + documentId);
        }
        status = SessionStatus.TERMINATED;
        readyEpoch = epoch;
        readyLayout = layout;
        readyEmbeddedChildren = copyChildren(embeddedChildren);
    }

    public synchronized void markReady(ExternalOrderKey frontier) {
        markReady(frontier, childrenFromLayout(layout));
    }

    /** Publishes one ready head and its exact occurrence topology together. */
    synchronized void markReady(
            ExternalOrderKey frontier,
            Map<String, DocumentId> embeddedChildren) {
        if (status == SessionStatus.BLOCKED
                || status == SessionStatus.TERMINATED) {
            throw new IllegalStateException(
                    "Cannot become ready from " + status);
        }
        if (graphPublishedEpoch != epoch) {
            throw new IllegalStateException(
                    "Cannot publish READY before graph epoch " + epoch
                            + " is published for " + documentId);
        }
        status = SessionStatus.READY;
        readyEpoch = epoch;
        readyLayout = layout;
        readyEmbeddedChildren = copyChildren(embeddedChildren);
        if (readyThrough == null || frontier.compareTo(readyThrough) > 0) {
            readyThrough = frontier;
        }
    }

    synchronized void restoreCoordinationState(
            SessionStatus restoredStatus,
            ExternalOrderKey restoredReadyThrough,
            long restoredReadyEpoch,
            long restoredGraphPublishedEpoch) {
        if (restoredReadyEpoch < 0L || restoredReadyEpoch > epoch
                || restoredGraphPublishedEpoch < -1L
                || restoredGraphPublishedEpoch > epoch) {
            throw new IllegalArgumentException(
                    "Invalid restored readiness evidence for " + documentId);
        }
        this.status = Objects.requireNonNull(
                restoredStatus, "restoredStatus");
        this.readyThrough = Objects.requireNonNull(
                restoredReadyThrough, "restoredReadyThrough");
        this.readyEpoch = restoredReadyEpoch;
        this.graphPublishedEpoch = restoredGraphPublishedEpoch;
    }

    public synchronized void commit(
            DocumentRevision revision,
            EmbeddedOnlyLayout nextLayout,
            ExternalOrderKey committedFrontier,
            List<SubscriptionDelta.Entry> nextSubscriptions,
            String transitionReceipt) {
        Objects.requireNonNull(revision, "revision");
        if (!revision.documentId().equals(documentId)) {
            throw new IllegalArgumentException(
                    "Revision belongs to another session");
        }
        long expectedEpoch = Math.addExact(epoch, 1L);
        if (revision.epoch() != expectedEpoch) {
            throw new IllegalStateException(
                    "Expected epoch " + expectedEpoch
                            + " but got " + revision.epoch());
        }
        if (revision.rootApplicationOrder()
                != Math.addExact(applicationSequence, 1L)) {
            throw new IllegalStateException(
                    "Application order is not contiguous");
        }
        String receipt = Objects.requireNonNull(
                transitionReceipt, "transitionReceipt");
        if (receipt.isBlank() || transitionReceipts.contains(receipt)) {
            throw new IllegalStateException(
                    "Duplicate or blank transition receipt " + receipt);
        }
        List<SubscriptionDelta.Entry> subscriptions = List.copyOf(
                Objects.requireNonNull(
                        nextSubscriptions, "nextSubscriptions"));
        this.layout = Objects.requireNonNull(nextLayout, "nextLayout");
        this.activeSubscriptions = subscriptions;
        this.epoch = expectedEpoch;
        this.applicationSequence = revision.rootApplicationOrder();
        this.status = SessionStatus.CATCHING_UP;
        this.revisions.add(revision);
        transitionReceipts.add(receipt);
        stateEpochs.record(revision);
        revision.sourceEntry().ifPresent(entry ->
                terminalEntryBlueIds.add(entry.blueId()));
        if (committedFrontier != null
                && (readyThrough == null
                || committedFrontier.compareTo(readyThrough) > 0)) {
            readyThrough = committedFrontier;
        }
    }

    /**
     * Publishes a Contracts-authenticated component representation without
     * inventing a document epoch or reprocessing the managed Root.
     *
     * <p>The retained revision list remains the immutable source-epoch lane.
     * A cyclic finalizer may nevertheless give that same source epoch a new
     * authoritative member BlueId. The surrounding store transaction owns the
     * transition/component proof and calls this method only on its detached
     * session image.</p>
     */
    synchronized void rebindComponentRepresentation(
            long expectedEpoch,
            EmbeddedOnlyLayout nextLayout,
            List<SubscriptionDelta.Entry> nextSubscriptions,
            String transitionReceipt) {
        rebindComponentRepresentation(expectedEpoch, nextLayout, nextSubscriptions, transitionReceipt, null);
    }

    synchronized void rebindComponentRepresentation(long expectedEpoch,
            EmbeddedOnlyLayout nextLayout, List<SubscriptionDelta.Entry> nextSubscriptions,
            String transitionReceipt, String originalPublicationIdentity) {
        if (expectedEpoch != epoch) {
            throw new IllegalStateException(
                    "Component representation epoch changed for " + documentId);
        }
        EmbeddedOnlyLayout replacement = Objects.requireNonNull(
                nextLayout, "nextLayout");
        String beforeBlueId = layout.rootBlueId();
        if (beforeBlueId.equals(replacement.rootBlueId())) {
            throw new IllegalArgumentException(
                    "Component representation rebind requires a new BlueId");
        }
        String receipt = Objects.requireNonNull(
                transitionReceipt, "transitionReceipt");
        if (receipt.isBlank() || transitionReceipts.contains(receipt)) {
            throw new IllegalStateException(
                    "Duplicate or blank transition receipt " + receipt);
        }
        if (status == SessionStatus.BLOCKED
                || status == SessionStatus.TERMINATED) {
            throw new IllegalStateException(
                    "Cannot rebind component representation from " + status);
        }
        this.layout = replacement;
        this.activeSubscriptions = List.copyOf(Objects.requireNonNull(
                nextSubscriptions, "nextSubscriptions"));
        this.status = SessionStatus.CATCHING_UP;
        this.graphPublishedEpoch = epoch - 1L;
        transitionReceipts.add(receipt);
        componentRepresentationTransitions.add(
                new ComponentRepresentationTransition(
                        epoch,
                        beforeBlueId,
                        replacement.rootBlueId(),
                        receipt, originalPublicationIdentity));
    }

    synchronized List<ComponentRepresentationTransition> representationTransitions() {
        return List.copyOf(componentRepresentationTransitions);
    }

    /**
     * Rows are appended only at the current epoch; stored restoration validates that order.
     * Binary bounds avoid scanning or copying transitions belonging to unrelated epochs.
     * The returned range is an immutable snapshot, including every same-epoch position.
     */
    synchronized List<ComponentRepresentationTransition> representationTransitionsAt(long selectedEpoch) {
        return List.copyOf(representationTransitionRange(selectedEpoch));
    }

    private List<ComponentRepresentationTransition> representationTransitionRange(long selectedEpoch) {
        int first = representationEpochBound(selectedEpoch, false);
        int after = representationEpochBound(selectedEpoch, true);
        return componentRepresentationTransitions.subList(first, after);
    }

    private int representationEpochBound(long selectedEpoch, boolean afterEqual) {
        int low = 0, high = componentRepresentationTransitions.size();
        while (low < high) {
            int middle = low + (high - low) / 2;
            long candidate = componentRepresentationTransitions.get(middle).epoch();
            if (candidate < selectedEpoch || (afterEqual && candidate == selectedEpoch)) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    record ComponentRepresentationTransition(
            long epoch,
            String beforeBlueId,
            String afterBlueId,
            String transitionReceiptIdentity,
            String originalPublicationIdentity) {
        ComponentRepresentationTransition {
            if (epoch < 0L) {
                throw new IllegalArgumentException(
                        "Component representation epoch must be non-negative");
            }
            beforeBlueId = Objects.requireNonNull(
                    beforeBlueId, "beforeBlueId");
            afterBlueId = Objects.requireNonNull(
                    afterBlueId, "afterBlueId");
            transitionReceiptIdentity = Objects.requireNonNull(
                    transitionReceiptIdentity,
                    "transitionReceiptIdentity");
            if (beforeBlueId.equals(afterBlueId)
                    || transitionReceiptIdentity.isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid component representation transition");
            }
        }
    }

    private static Map<String, DocumentId> childrenFromLayout(
            EmbeddedOnlyLayout selectedLayout) {
        LinkedHashMap<String, DocumentId> children = new LinkedHashMap<>();
        for (EmbeddedOccurrence occurrence : Objects.requireNonNull(
                selectedLayout, "layout").directOccurrences()) {
            DocumentId prior = children.putIfAbsent(
                    occurrence.scopePath(), occurrence.childDocumentId());
            if (prior != null && !prior.equals(occurrence.childDocumentId())) {
                throw new IllegalArgumentException(
                        "Layout occurrences disagree at "
                                + occurrence.scopePath());
            }
        }
        return Collections.unmodifiableMap(children);
    }

    private static Map<String, DocumentId> copyChildren(
            Map<String, DocumentId> supplied) {
        LinkedHashMap<String, DocumentId> children = new LinkedHashMap<>();
        Objects.requireNonNull(supplied, "embeddedChildren")
                .forEach((path, documentId) -> {
                    String selectedPath = Objects.requireNonNull(path, "path");
                    if (selectedPath.isBlank()) {
                        throw new IllegalArgumentException(
                                "Embedded child path must not be blank");
                    }
                    DocumentId duplicate = children.putIfAbsent(
                            selectedPath,
                            Objects.requireNonNull(
                                    documentId, "embedded child documentId"));
                    if (duplicate != null && !duplicate.equals(documentId)) {
                        throw new IllegalArgumentException(
                                "Embedded children disagree at "
                                        + selectedPath);
                    }
                });
        return Collections.unmodifiableMap(children);
    }

    static final class StateEpochs {
        private final Map<String, Long> first = new LinkedHashMap<>();
        private final Set<String> ambiguous = new LinkedHashSet<>();

        void record(DocumentRevision revision) {
            String state = revision.after().blueId();
            Long prior = first.putIfAbsent(state, revision.epoch());
            if (prior != null && prior != revision.epoch()) {
                ambiguous.add(state);
            }
        }

        OptionalLong first(String stateBlueId) {
            Long epoch = first.get(Objects.requireNonNull(
                    stateBlueId, "stateBlueId"));
            return epoch == null ? OptionalLong.empty() : OptionalLong.of(epoch);
        }

        void copyFrom(StateEpochs source) {
            Objects.requireNonNull(source, "source");
            first.putAll(source.first);
            ambiguous.addAll(source.ambiguous);
        }

        long resolve(
                DocumentId documentId,
                String authoredInitialBlueId,
                String stateBlueId) {
            OptionalLong known = first(stateBlueId);
            boolean authored = authoredInitialBlueId.equals(stateBlueId);
            if (ambiguous.contains(stateBlueId)
                    || authored && known.isPresent() && known.getAsLong() > 0L) {
                throw new IllegalStateException(
                        "Ambiguous historical state " + stateBlueId
                                + "; exact admitted epoch is required");
            }
            if (authored) {
                return -1L;
            }
            if (known.isEmpty()) {
                throw invalidAdmission("unknown state " + stateBlueId
                        + " for " + documentId);
            }
            return known.getAsLong();
        }
    }

    private static InvalidAdmissionEvidenceException invalidAdmission(
            String diagnostic) {
        return new InvalidAdmissionEvidenceException(diagnostic);
    }
}
