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
    private List<DocumentRevision> revisions = SessionHistoryList.empty();
    private SessionHistorySet<String> terminalEntryBlueIds = SessionHistorySet.empty(String::compareTo);
    private SessionHistorySet<String> transitionReceipts = SessionHistorySet.empty(String::compareTo);
    private List<ComponentRepresentationTransition> componentRepresentationTransitions = SessionHistoryList.empty();
    private RootedDocumentHistory rootedHistory;
    private RootedDocumentView rootedView;
    private List<RootedViewPosition> rootedViewPositions = SessionHistoryList.empty();
    // Derived, session-local indexes. A value-equal view is not retained publication authority.
    private SessionIdentityPositions<RootedDocumentView> rootedViewFirstPositions = new SessionIdentityPositions<>();
    // Runtime-only aliases already retained/selected by this owner. Backing replacement must not
    // replace captured object identities with another scope-interned object at the same address.
    private PersistentOrderedMap<Long, RootedDocumentView> ownedViewPositions = PersistentOrderedMap.empty(Long::compare);
    private PersistentOrderedMap<String, Long> rootedInvocationFirstPositions = PersistentOrderedMap.empty(String::compareTo);
    private final StateEpochs stateEpochs = new StateEpochs();
    private PersistentOrderedMap<Long, ManagedLineageIndex.RetainedState> retainedStates = PersistentOrderedMap.empty(Long::compare);
    private PersistentOrderedMap<String, Long> sourceEntryEpochs = PersistentOrderedMap.empty(String::compareTo);
    private PersistentOrderedMap<Long, EpochRange> representationRanges = PersistentOrderedMap.empty(Long::compare);
    private PersistentOrderedMap<EpochState, Boolean> representationStatePositions = PersistentOrderedMap.empty(EpochState::compareTo);
    private PersistentOrderedMap<String, Boolean> representationStates = PersistentOrderedMap.empty(String::compareTo);
    private PersistentOrderedMap<String, Long> representationReceiptPositions = PersistentOrderedMap.empty(String::compareTo);
    private SessionHistoryMap<String, Long> viewAddressFirstPositions = SessionHistoryMap.empty(String::compareTo);
    private StorageViewAccess storageViewAccess;
    private long lastAnchoredNonReplayableEpoch = -1L;
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
        indexRevision(initializationRevision);
        this.transitionReceipts.add(
                "initialization|" + documentId.value());
    }

    private DocumentSession(DocumentSession source) {
        this.documentId = source.documentId;
        this.authoredInitialBlueId = source.authoredInitialBlueId;
        this.rootedHistory = source.rootedHistory;
        this.activeSubscriptions = source.activeSubscriptions;
        this.revisions = SessionHistoryList.copyOf(source.revisions);
        this.terminalEntryBlueIds = source.terminalEntryBlueIds.copy();
        this.transitionReceipts = source.transitionReceipts.copy();
        this.componentRepresentationTransitions = SessionHistoryList.copyOf(source.componentRepresentationTransitions);
        this.retainedStates = source.retainedStates;
        this.sourceEntryEpochs = source.sourceEntryEpochs;
        this.representationRanges = source.representationRanges;
        this.representationStatePositions = source.representationStatePositions;
        this.representationStates = source.representationStates;
        this.representationReceiptPositions = source.representationReceiptPositions;
        this.lastAnchoredNonReplayableEpoch = source.lastAnchoredNonReplayableEpoch;
        this.viewAddressFirstPositions = source.viewAddressFirstPositions.copy();
        this.storageViewAccess = source.storageViewAccess;
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
        copy.rootedViewPositions = SessionHistoryList.copyOf(rootedViewPositions);
        copy.rootedViewFirstPositions = rootedViewFirstPositions.copy();
        copy.ownedViewPositions = ownedViewPositions;
        copy.rootedInvocationFirstPositions = rootedInvocationFirstPositions;
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
        var view = position.view();
        if (!position.invocationIdentity().equals(view.result().invocationIdentity()))
            throw new IllegalArgumentException("Retained position names a different invocation");
        Long first = rootedViewFirstPositions.get(view);
        long ownedFirst = first == null ? index : first;
        if (storageViewAccess != null) {
            String address = storageViewAccess.addressForRetention(view);
            Long storedFirst = viewAddressFirstPositions.putIfAbsent(address, (long) index);
            first = storedFirst == null ? index : storedFirst;
        }
        rootedViewPositions.add(position.withFirstPosition(first == null ? index : first));
        rootedViewFirstPositions.putIfAbsent(view, ownedFirst);
        ownedViewPositions = ownedViewPositions.put((long) index, view).map();
        if (!rootedInvocationFirstPositions.containsKey(position.invocationIdentity()))
            rootedInvocationFirstPositions = rootedInvocationFirstPositions.put(position.invocationIdentity(), (long) index).map();
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
        if (low > 0) return selectRootedView(low - 1);
        throw new ProjectionUnavailableException("No authenticated rooted source view before attachment boundary " + boundary);
    }

    /** The exact retained object position, not an endpoint or invocation-identity approximation. */
    synchronized void requireRetainedRootedView(RootedDocumentView selected) {
        retainedRootedViewPosition(selected);
    }

    /** Constant-time membership in a frozen publication prefix; later appends cannot extend it. */
    synchronized boolean rootedPublicationIncludes(RootedDocumentView selected, String invocationIdentity) {
        long selectedPosition = retainedRootedViewPosition(selected);
        Long publicationPosition = rootedInvocationFirstPositions.get(invocationIdentity);
        return publicationPosition != null && publicationPosition <= selectedPosition;
    }

    private long retainedRootedViewPosition(RootedDocumentView selected) {
        Long position = rootedViewFirstPositions.get(selected);
        if (position == null && storageViewAccess != null) {
            String address = storageViewAccess.retainedAddress(selected);
            if (address != null) {
                position = viewAddressFirstPositions.get(address);
                if (position != null) rootedViewFirstPositions.putIfAbsent(selected, position);
            }
        }
        if (position == null) {
            throw new ProjectionUnavailableException("Source view is not an actual retained publication position");
        }
        return position;
    }

    /** Exact publication membership through a retained view, independent of repeated endpoint identities. */
    synchronized java.util.Set<String> rootedPublicationPrefix(RootedDocumentView selected) {
        var invocations = new java.util.LinkedHashSet<String>();
        long selectedPosition = retainedRootedViewPosition(selected);
        for (int index = 0; index <= selectedPosition; index++) {
            invocations.add(rootedViewPositions.get(index).view().result().invocationIdentity());
        }
        return java.util.Set.copyOf(invocations);
    }

    synchronized RootedDocumentView rootedViewForInvocation(String invocationIdentity) {
        Long position = rootedInvocationFirstPositions.get(invocationIdentity);
        if (position == null) throw new ProjectionUnavailableException("Original source publication view is unavailable");
        return selectRootedView(Math.toIntExact(position));
    }

    private RootedDocumentView selectRootedView(int ordinal) {
        RootedViewPosition selected = rootedViewPositions.get(ordinal);
        var view = ownedViewPositions.get((long) ordinal);
        boolean alreadyOwned = view != null;
        if (view == null) view = selected.view();
        if (!selected.invocationIdentity().equals(view.result().invocationIdentity()))
            throw new IllegalArgumentException("Selected view differs from its retained invocation");
        long first = selected.firstPosition() < 0 ? ordinal : selected.firstPosition();
        if (first > ordinal) throw new IllegalArgumentException("Retained view first position follows selected position");
        if (selected.storageAddress() != null && storageViewAccess != null) {
            String address = storageViewAccess.retainedAddress(view);
            Long indexedFirst = viewAddressFirstPositions.get(selected.storageAddress());
            if (!selected.storageAddress().equals(address) || indexedFirst == null || indexedFirst != first)
                throw new IllegalArgumentException("Selected view differs from its exact interner-owned position");
        }
        rootedViewFirstPositions.putIfAbsent(view, first);
        if (!alreadyOwned) ownedViewPositions = ownedViewPositions.put((long) ordinal, view).map();
        return view;
    }

    /** Position metadata can be read without opening its independently retained view payload. */
    static final class RootedViewPosition {
        private volatile RootedDocumentView view;
        private final ExternalOrderKey boundary;
        private final String invocationIdentity;
        private final long firstPosition;
        private final String storageAddress;
        private final java.util.function.Supplier<RootedDocumentView> loader;
        RootedViewPosition(RootedDocumentView view, ExternalOrderKey boundary) {
            this(view, boundary, Objects.requireNonNull(view).result().invocationIdentity(), -1L, null, null);
        }
        private RootedViewPosition(RootedDocumentView view, ExternalOrderKey boundary, String invocationIdentity,
                long firstPosition, String storageAddress, java.util.function.Supplier<RootedDocumentView> loader) {
            this.view = view; this.boundary = boundary; this.invocationIdentity = Objects.requireNonNull(invocationIdentity);
            this.firstPosition = firstPosition; this.storageAddress = storageAddress; this.loader = loader;
        }
        static RootedViewPosition stored(ExternalOrderKey boundary, String invocationIdentity, long firstPosition,
                String address, java.util.function.Supplier<RootedDocumentView> loader) {
            if (firstPosition < 0) throw new IllegalArgumentException("Negative retained view position");
            return new RootedViewPosition(null, boundary, invocationIdentity, firstPosition, Objects.requireNonNull(address), Objects.requireNonNull(loader));
        }
        RootedViewPosition withFirstPosition(long first) {
            return new RootedViewPosition(view, boundary, invocationIdentity, first, storageAddress, loader);
        }
        RootedDocumentView view() {
            var selected = view;
            if (selected == null) synchronized (this) {
                selected = view;
                if (selected == null) view = selected = Objects.requireNonNull(loader.get(), "Missing selected retained view");
            }
            return selected;
        }
        ExternalOrderKey boundary() { return boundary; }
        String invocationIdentity() { return invocationIdentity; }
        long firstPosition() { return firstPosition; }
        String storageAddress() { return storageAddress; }
        @Override public boolean equals(Object other) {
            return other instanceof RootedViewPosition position && view() == position.view() && Objects.equals(boundary, position.boundary);
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(view()) + Objects.hashCode(boundary); }
    }

    /** Codec-owned live interner access, never a host-supplied content-identity assertion. */
    interface StorageViewAccess {
        String addressForRetention(RootedDocumentView view);
        /** Null unless this exact object is owned by the current storage interner. */
        String retainedAddress(RootedDocumentView view);
    }

    record EpochRange(long first, long after) {
        EpochRange { if (first < 0 || after <= first) throw new IllegalArgumentException("Invalid representation range"); }
    }

    record EpochState(long epoch, String blueId) implements Comparable<EpochState> {
        EpochState { Objects.requireNonNull(blueId); if (epoch < 0) throw new IllegalArgumentException("Negative epoch"); }
        @Override public int compareTo(EpochState other) {
            int compared = Long.compare(epoch, other.epoch);
            return compared == 0 ? blueId.compareTo(other.blueId) : compared;
        }
    }

    /** Private lane snapshot; roots are immutable, wrappers are detached append cursors. */
    record IndexedState(DocumentId documentId, String authoredInitialBlueId,
            List<SubscriptionDelta.Entry> activeSubscriptions, SessionHistoryList<DocumentRevision> revisions,
            SessionHistorySet<String> terminalEntryBlueIds, SessionHistorySet<String> transitionReceipts,
            SessionHistoryList<ComponentRepresentationTransition> representationTransitions,
            RootedDocumentHistory rootedHistory, RootedDocumentView rootedView,
            SessionHistoryList<RootedViewPosition> rootedViewPositions, SessionHistoryMap<String, Long> stateEpochs,
            SessionHistorySet<String> ambiguousStates, PersistentOrderedMap<Long, ManagedLineageIndex.RetainedState> retainedStates,
            PersistentOrderedMap<String, Long> sourceEntryEpochs, PersistentOrderedMap<Long, EpochRange> representationRanges,
            PersistentOrderedMap<EpochState, Boolean> representationStatePositions,
            PersistentOrderedMap<String, Boolean> representationStates, PersistentOrderedMap<String, Long> representationReceiptPositions,
            PersistentOrderedMap<String, Long> invocationFirstPositions, SessionHistoryMap<String, Long> viewAddressFirstPositions,
            long lastAnchoredNonReplayableEpoch, EmbeddedOnlyLayout layout, EmbeddedOnlyLayout readyLayout,
            Map<String, DocumentId> readyEmbeddedChildren, SessionStatus status, ExternalOrderKey readyThrough,
            long epoch, long readyEpoch, long graphPublishedEpoch, long applicationSequence) { }

    synchronized IndexedState indexedState() {
        return new IndexedState(documentId, authoredInitialBlueId, activeSubscriptions, SessionHistoryList.copyOf(revisions),
                terminalEntryBlueIds.copy(), transitionReceipts.copy(), SessionHistoryList.copyOf(componentRepresentationTransitions),
                rootedHistory, rootedView, SessionHistoryList.copyOf(rootedViewPositions), stateEpochs.first.copy(),
                stateEpochs.ambiguous.copy(), retainedStates, sourceEntryEpochs, representationRanges, representationStatePositions,
                representationStates, representationReceiptPositions, rootedInvocationFirstPositions, viewAddressFirstPositions.copy(),
                lastAnchoredNonReplayableEpoch, layout, readyLayout, readyEmbeddedChildren, status, readyThrough,
                epoch, readyEpoch, graphPublishedEpoch, applicationSequence);
    }

    synchronized PersistentOrderedMap<Long, ManagedLineageIndex.RetainedState> retainedStateIndex() { return retainedStates; }
    synchronized long lastAnchoredNonReplayableEpoch() { return lastAnchoredNonReplayableEpoch; }

    /** Only the library-controlled storage boundary may establish this prefix invariant. */
    static DocumentSession restoreControlledIndexed(IndexedState state, StorageViewAccess views) {
        return new DocumentSession(state, views);
    }

    /** Arbitrary external indexed bytes retain the exhaustive legacy checks and all derived-index checks. */
    static DocumentSession restoreIndexed(IndexedState state, StorageViewAccess views) {
        Objects.requireNonNull(state);
        var checked = restoreStored(new StoredState(state.documentId(), state.authoredInitialBlueId(), state.activeSubscriptions(),
                state.revisions(), state.terminalEntryBlueIds(), state.transitionReceipts(), state.representationTransitions(),
                state.rootedHistory(), state.rootedView(), state.rootedViewPositions(), state.stateEpochs(), state.ambiguousStates(),
                state.layout(), state.readyLayout(), state.readyEmbeddedChildren(), state.status(), state.readyThrough(),
                state.epoch(), state.readyEpoch(), state.graphPublishedEpoch(), state.applicationSequence()));
        requireIndexEqual(checked.retainedStates, state.retainedStates(), "retained numbered metadata");
        requireIndexEqual(checked.sourceEntryEpochs, state.sourceEntryEpochs(), "source entry positions");
        requireIndexEqual(checked.representationRanges, state.representationRanges(), "representation ranges");
        requireIndexEqual(checked.representationStatePositions, state.representationStatePositions(), "representation state positions");
        requireIndexEqual(checked.representationStates, state.representationStates(), "representation state membership");
        requireIndexEqual(checked.representationReceiptPositions, state.representationReceiptPositions(), "representation receipt positions");
        requireIndexEqual(checked.rootedInvocationFirstPositions, state.invocationFirstPositions(), "first invocation positions");
        requireOrderedIndexEqual(checked.stateEpochs.first, state.stateEpochs(), "first state epochs");
        requireOrderedIndexEqual(checked.stateEpochs.ambiguous.map(), state.ambiguousStates().map(), "ambiguous states");
        requireOrderedIndexEqual(checked.terminalEntryBlueIds.map(), state.terminalEntryBlueIds().map(), "terminal entries");
        requireOrderedIndexEqual(checked.transitionReceipts.map(), state.transitionReceipts().map(), "transition receipts");
        if (checked.lastAnchoredNonReplayableEpoch != state.lastAnchoredNonReplayableEpoch())
            throw new IllegalArgumentException("Stored anchored history discontinuity differs");
        var addresses = SessionHistoryMap.<String, Long>empty(String::compareTo);
        for (int ordinal = 0; ordinal < state.rootedViewPositions().size(); ordinal++) {
            var position = state.rootedViewPositions().get(ordinal);
            var expectedPosition = checked.rootedViewPositions.get(ordinal);
            if (position.firstPosition() != expectedPosition.firstPosition()
                    || !position.invocationIdentity().equals(expectedPosition.invocationIdentity()))
                throw new IllegalArgumentException("Stored retained view position differs from its exact object history");
            String address = position.storageAddress();
            if (address == null && views != null) address = views.addressForRetention(position.view());
            if (address != null) {
                Long first = addresses.putIfAbsent(address, (long) ordinal);
                if (position.firstPosition() != (first == null ? ordinal : first))
                    throw new IllegalArgumentException("Stored view first position differs from its exact retained address");
            }
        }
        requireOrderedIndexEqual(addresses, state.viewAddressFirstPositions(), "first view-address positions");
        var restored = new DocumentSession(state, views);
        restored.rootedViewFirstPositions = checked.rootedViewFirstPositions.copy();
        return restored;
    }

    private static <K, V> void requireIndexEqual(PersistentOrderedMap<K, V> expected, PersistentOrderedMap<K, V> supplied, String name) {
        if (!expected.entries().equals(supplied.entries())) throw new IllegalArgumentException("Stored " + name + " index differs from complete history");
    }

    private static <K, V> void requireOrderedIndexEqual(SessionHistoryMap<K, V> expected, SessionHistoryMap<K, V> supplied, String name) {
        requireIndexEqual(expected.valuesRoot(), supplied.valuesRoot(), name);
        requireIndexEqual(expected.orderRoot(), supplied.orderRoot(), name + " insertion order");
    }

    private DocumentSession(IndexedState state, StorageViewAccess views) {
        this.documentId = Objects.requireNonNull(state.documentId());
        this.authoredInitialBlueId = Objects.requireNonNull(state.authoredInitialBlueId());
        this.activeSubscriptions = List.copyOf(state.activeSubscriptions());
        this.rootedHistory = state.rootedHistory(); this.rootedView = state.rootedView();
        this.layout = Objects.requireNonNull(state.layout()); this.readyLayout = Objects.requireNonNull(state.readyLayout());
        this.readyEmbeddedChildren = copyChildren(state.readyEmbeddedChildren());
        this.epoch = state.epoch(); this.applicationSequence = state.applicationSequence();
        installIndexedLanes(state, views);
        restoreCoordinationState(state.status(), state.readyThrough(), state.readyEpoch(), state.graphPublishedEpoch());
        validateIndexedTips();
    }

    private void installIndexedLanes(IndexedState state, StorageViewAccess views) {
        revisions = state.revisions().copy(); terminalEntryBlueIds = state.terminalEntryBlueIds().copy();
        transitionReceipts = state.transitionReceipts().copy(); componentRepresentationTransitions = state.representationTransitions().copy();
        rootedViewPositions = state.rootedViewPositions().copy(); stateEpochs.first = state.stateEpochs().copy();
        stateEpochs.ambiguous = state.ambiguousStates().copy(); retainedStates = state.retainedStates();
        sourceEntryEpochs = state.sourceEntryEpochs(); representationRanges = state.representationRanges();
        representationStatePositions = state.representationStatePositions(); representationStates = state.representationStates();
        representationReceiptPositions = state.representationReceiptPositions(); rootedInvocationFirstPositions = state.invocationFirstPositions();
        viewAddressFirstPositions = state.viewAddressFirstPositions().copy(); lastAnchoredNonReplayableEpoch = state.lastAnchoredNonReplayableEpoch();
        storageViewAccess = views;
    }

    /** Install only successfully retained equivalent backing roots, while the caller owns this session monitor. */
    synchronized void installRetainedIndexedState(IndexedState state, StorageViewAccess views) {
        if (!documentId.equals(state.documentId()) || !authoredInitialBlueId.equals(state.authoredInitialBlueId())
                || epoch != state.epoch() || applicationSequence != state.applicationSequence() || readyEpoch != state.readyEpoch()
                || graphPublishedEpoch != state.graphPublishedEpoch() || status != state.status()
                || !readyThrough.equals(state.readyThrough()) || !layout.rootBlueId().equals(state.layout().rootBlueId())
                || !readyLayout.rootBlueId().equals(state.readyLayout().rootBlueId()) || rootedView != state.rootedView()
                || revisions.size() != state.revisions().size() || componentRepresentationTransitions.size() != state.representationTransitions().size()
                || rootedViewPositions.size() != state.rootedViewPositions().size())
            throw new IllegalStateException("Retained history no longer describes this session");
        installIndexedLanes(state, views);
        validateIndexedTips();
    }

    private void validateIndexedTips() {
        if (epoch < 0 || revisions.size() != epoch + 1L || retainedStates.size() != revisions.size() || applicationSequence != epoch
                || !transitionReceipts.contains("initialization|" + documentId.value())
                || transitionReceipts.size() != (long) revisions.size() + componentRepresentationTransitions.size()
                || terminalEntryBlueIds.size() != sourceEntryEpochs.size()
                || lastAnchoredNonReplayableEpoch < -1L || lastAnchoredNonReplayableEpoch >= epoch)
            throw new IllegalArgumentException("Invalid indexed session bounds");
        var initial = retainedStateAt(0L);
        if (!stateEpochs.first(initial.blueId()).isPresent() || stateEpochs.first(initial.blueId()).getAsLong() != 0L)
            throw new IllegalArgumentException("Indexed initialization is outside the state index");
        var current = retainedStateAt(epoch);
        EpochRange currentRange = representationRanges.get(epoch);
        String currentBlueId = currentRange == null ? current.blueId()
                : componentRepresentationTransitions.get(Math.toIntExact(currentRange.after() - 1L)).afterBlueId();
        if (!currentBlueId.equals(layout.rootBlueId()) || !storedRepresentationAt(readyEpoch, readyLayout.rootBlueId()))
            throw new IllegalArgumentException("Indexed current or READY state is outside retained history");
        if (rootedHistory != null && (!authoredInitialBlueId.equals(rootedHistory.descriptor().get("initialDocumentBlueId"))
                || !documentId.value().equals(rootedHistory.descriptor().get("documentId"))))
            throw new IllegalArgumentException("Indexed rooted history belongs to another document");
        if (rootedViewPositions.isEmpty()) {
            if (rootedView != null) throw new IllegalArgumentException("Indexed current view has no retained position");
        } else {
            int last = rootedViewPositions.size() - 1;
            if (selectRootedView(last) != rootedView) throw new IllegalArgumentException("Indexed current view is not its final retained position");
            rootedView.requirePublishedHead(documentId, epoch, layout.rootBlueId());
            ExternalOrderKey expected = rootedView.logicalBoundary();
            ExternalOrderKey previous = last == 0 ? null : rootedViewPositions.get(last - 1).boundary();
            if (previous != null && (expected == null || previous.compareTo(expected) > 0)) expected = previous;
            if (!Objects.equals(expected, rootedViewPositions.get(last).boundary()))
                throw new IllegalArgumentException("Indexed final view boundary differs from its predecessor");
        }
    }

    private ManagedLineageIndex.RetainedState retainedStateAt(long selectedEpoch) {
        var row = Objects.requireNonNull(retainedStates.get(selectedEpoch), "Missing selected numbered metadata");
        if (!row.documentId().equals(documentId) || row.epoch() != selectedEpoch)
            throw new IllegalArgumentException("Selected numbered metadata belongs to another position");
        return row;
    }

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
                storedRootedViewPositions(), Collections.unmodifiableMap(new LinkedHashMap<>(stateEpochs.first)),
                Collections.unmodifiableSet(new LinkedHashSet<>(stateEpochs.ambiguous)), layout, readyLayout,
                readyEmbeddedChildren, status, readyThrough, epoch, readyEpoch, graphPublishedEpoch, applicationSequence);
    }

    /** Exhaustive legacy snapshot keeps live aliases; indexed storage never serializes this overlay. */
    private List<RootedViewPosition> storedRootedViewPositions() {
        var positions = new ArrayList<RootedViewPosition>(rootedViewPositions.size());
        for (int ordinal = 0; ordinal < rootedViewPositions.size(); ordinal++) {
            var position = rootedViewPositions.get(ordinal);
            positions.add(new RootedViewPosition(selectRootedView(ordinal), position.boundary()));
        }
        return List.copyOf(positions);
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
            indexRevision(revision);
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
        long representationOrdinal = 0L;
        for (ComponentRepresentationTransition transition : componentRepresentationTransitions) {
            if (transition.epoch() < priorEpoch || transition.epoch() > epoch
                    || !transition.beforeBlueId().equals(represented.getOrDefault(transition.epoch(),
                            revision(transition.epoch()).after().blueId()))
                    || !transitionReceipts.contains(transition.transitionReceiptIdentity())
                    || !representationReceipts.add(transition.transitionReceiptIdentity())) {
                throw new IllegalArgumentException("Stored same-epoch representation history is invalid");
            }
            represented.put(transition.epoch(), transition.afterBlueId()); priorEpoch = transition.epoch();
            indexRepresentation(transition, representationOrdinal++);
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
        return selectedEpoch >= 0 && selectedEpoch <= epoch && (retainedStateAt(selectedEpoch).blueId().equals(blueId)
                || representationStatePositions.containsKey(new EpochState(selectedEpoch, blueId)));
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
        return revision(epoch);
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
        if (!documentId.equals(revision.documentId()) || revision.epoch() != childEpoch
                || revision.rootApplicationOrder() != childEpoch
                || !revision.after().blueId().equals(retainedStateAt(childEpoch).blueId())) {
            throw new IllegalStateException("Selected revision differs from its exact retained session position");
        }
        revision.sourceEntry().ifPresent(entry -> {
            Long first = sourceEntryEpochs.get(entry.blueId());
            if (!terminalEntryBlueIds.contains(entry.blueId()) || first == null || first < 0 || first > childEpoch)
                throw new IllegalStateException("Selected revision source entry differs from its retained index");
        });
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
        Long selectedEpoch = sourceEntryEpochs.get(identity);
        if (selectedEpoch == null) return Optional.empty();
        var selected = revision(selectedEpoch);
        if (selected.sourceEntry().filter(entry -> entry.blueId().equals(identity)).isEmpty())
            throw new IllegalStateException("Selected entry index does not name its exact revision");
        return Optional.of(selected);
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
        Long ordinal = representationReceiptPositions.get(transitionReceiptIdentity);
        if (ordinal == null) return false;
        var row = componentRepresentationTransitions.get(Math.toIntExact(ordinal));
        return row.epoch() == transitionEpoch && row.beforeBlueId().equals(beforeBlueId)
                && row.afterBlueId().equals(afterBlueId) && row.transitionReceiptIdentity().equals(transitionReceiptIdentity);
    }

    public synchronized OptionalLong epochForState(String exactBlueId) {
        return stateEpochs.first(exactBlueId);
    }

    /** Authenticated operation lineage includes representation steps without inventing numbered epochs. */
    synchronized boolean recognizesOperationTarget(String exactBlueId) {
        return authoredInitialBlueId.equals(exactBlueId) || stateEpochs.first(exactBlueId).isPresent()
                || representationStates.containsKey(exactBlueId);
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
        indexRevision(revision);
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
        var transition = new ComponentRepresentationTransition(
                        epoch,
                        beforeBlueId,
                        replacement.rootBlueId(),
                        receipt, originalPublicationIdentity);
        long ordinal = componentRepresentationTransitions.size();
        componentRepresentationTransitions.add(transition);
        indexRepresentation(transition, ordinal);
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
        if (selectedEpoch < 0) return List.of();
        EpochRange range = representationRanges.get(selectedEpoch);
        return range == null ? List.of() : componentRepresentationTransitions.subList(Math.toIntExact(range.first()), Math.toIntExact(range.after()));
    }

    private void indexRevision(DocumentRevision revision) {
        long selectedEpoch = revision.epoch();
        if (selectedEpoch > 0) {
            var previous = Objects.requireNonNull(retainedStates.get(selectedEpoch - 1), "Missing numbered predecessor metadata");
            if (!revision.before().orElseThrow(() -> new IllegalArgumentException("Numbered successor has no before state"))
                    .blueId().equals(previous.blueId())) lastAnchoredNonReplayableEpoch = selectedEpoch - 1;
        }
        retainedStates = retainedStates.put(selectedEpoch,
                new ManagedLineageIndex.RetainedState(documentId, selectedEpoch, revision.after().blueId())).map();
        revision.sourceEntry().ifPresent(entry -> {
            if (!sourceEntryEpochs.containsKey(entry.blueId())) sourceEntryEpochs = sourceEntryEpochs.put(entry.blueId(), selectedEpoch).map();
        });
    }

    private void indexRepresentation(ComponentRepresentationTransition transition, long ordinal) {
        EpochRange prior = representationRanges.get(transition.epoch());
        if (prior != null && prior.after() != ordinal) throw new IllegalArgumentException("Representation epoch range is not contiguous");
        representationRanges = representationRanges.put(transition.epoch(),
                new EpochRange(prior == null ? ordinal : prior.first(), ordinal + 1L)).map();
        representationStatePositions = representationStatePositions.put(new EpochState(transition.epoch(), transition.beforeBlueId()), Boolean.TRUE)
                .map().put(new EpochState(transition.epoch(), transition.afterBlueId()), Boolean.TRUE).map();
        representationStates = representationStates.put(transition.beforeBlueId(), Boolean.TRUE).map()
                .put(transition.afterBlueId(), Boolean.TRUE).map();
        if (representationReceiptPositions.containsKey(transition.transitionReceiptIdentity()))
            throw new IllegalArgumentException("Repeated representation receipt");
        representationReceiptPositions = representationReceiptPositions.put(transition.transitionReceiptIdentity(), ordinal).map();
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
        private SessionHistoryMap<String, Long> first = SessionHistoryMap.empty(String::compareTo);
        private SessionHistorySet<String> ambiguous = SessionHistorySet.empty(String::compareTo);

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
            first = source.first.copy();
            ambiguous = source.ambiguous.copy();
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
