package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.util.Comparator;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;
import static blue.coordination.internal.SessionRecordCodec.*;

/** Bounded session descriptor; numbered history and representation positions have separate roots. */
final class IndexedSessionStorage {
    interface Payloads extends DocumentSession.StorageViewAccess {
        String retainView(RootedDocumentView view);
        String retainRevision(DocumentRevision revision);
        String revisionIdentity(DocumentRevision revision);
        RootedDocumentView view(String address);
        DocumentRevision revision(String address);
    }

    private final SessionHistoryStorage history;
    private final SessionRecordCodec rows;
    private final int maximumBytes;
    private final Payloads payloads;
    private final PersistentMapCodec<DocumentRevision> revisions;
    private final PersistentMapCodec<DocumentSession.RootedViewPosition> positions;
    private final PersistentMapCodec<DocumentSession.ComponentRepresentationTransition> representations;
    private final PersistentMapCodec<DocumentSession.EpochRange> ranges;
    private final PersistentMapCodec<DocumentSession.EpochState> epochStates;

    IndexedSessionStorage(CoordinationImmutableObjectStore objects, int maximumBytes,
            SessionRecordCodec rows, Payloads payloads) {
        this.history = new SessionHistoryStorage(objects, maximumBytes);
        this.rows = rows; this.maximumBytes = maximumBytes; this.payloads = payloads;
        revisions = new PersistentMapCodec<>() {
            @Override public String identity() { return "blue-coordination/session-history/1/revision-address"; }
            @Override public PreparedEncoding<DocumentRevision> prepareEncoding(DocumentRevision value) {
                return PreparedEncoding.encoded(encodeAddress(payloads.retainRevision(value)));
            }
            @Override public byte[] encode(DocumentRevision value) { return encodeAddress(payloads.revisionIdentity(value)); }
            @Override public DocumentRevision decode(byte[] bytes) {
                return payloads.revision(decodeAddress(bytes));
            }
        };
        positions = new PersistentMapCodec<>() {
            @Override public String identity() { return "blue-coordination/session-history/1/view-position"; }
            @Override public PreparedEncoding<DocumentSession.RootedViewPosition> prepareEncoding(DocumentSession.RootedViewPosition value) {
                String address = value.storageAddress();
                if (address == null) address = payloads.retainView(value.view());
                return PreparedEncoding.encoded(encodePosition(value, address));
            }
            @Override public byte[] encode(DocumentSession.RootedViewPosition value) {
                String address = value.storageAddress();
                if (address == null) address = payloads.addressForRetention(value.view());
                return encodePosition(value, address);
            }
            @Override public DocumentSession.RootedViewPosition decode(byte[] bytes) {
                return SessionStorageWire.decode(bytes, maximumBytes, in -> {
                    String address = text(in); requireAddress(address);
                    var boundary = optional(in, SessionStorageWire::order);
                    String invocation = text(in); long first = in.longValue();
                    return DocumentSession.RootedViewPosition.stored(boundary, invocation, first,
                            address, () -> payloads.view(address));
                });
            }
        };
        representations = history.codec("representation", (out, value) -> {
            out.longValue(value.epoch()); out.text(value.beforeBlueId()); out.text(value.afterBlueId());
            out.text(value.transitionReceiptIdentity()); out.nullableText(value.originalPublicationIdentity());
        }, in -> new DocumentSession.ComponentRepresentationTransition(in.longValue(), text(in), text(in), text(in), nullableText(in)));
        ranges = history.codec("epoch-range", (out, value) -> { out.longValue(value.first()); out.longValue(value.after()); },
                in -> new DocumentSession.EpochRange(in.longValue(), in.longValue()));
        epochStates = history.codec("epoch-state", (out, value) -> { out.longValue(value.epoch()); out.text(value.blueId()); },
                in -> new DocumentSession.EpochState(in.longValue(), text(in)));
    }

    private byte[] encodeAddress(String address) {
        requireAddress(address); return SessionStorageWire.encode(maximumBytes, out -> out.text(address));
    }
    private String decodeAddress(byte[] bytes) {
        return SessionStorageWire.decode(bytes, maximumBytes, in -> {
            String address = text(in); requireAddress(address); return address;
        });
    }
    private static void requireAddress(String address) {
        require(address != null && address.matches("[0-9a-f]{64}"), "Invalid indexed session payload address");
    }
    private byte[] encodePosition(DocumentSession.RootedViewPosition value, String address) {
        requireAddress(address);
        require(value.firstPosition() >= 0, "Unbound indexed view position");
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(address); optional(out, value.boundary(), SessionStorageWire::order);
            out.text(value.invocationIdentity()); out.longValue(value.firstPosition());
        });
    }

    /** One-time resident conversion is exhaustive; subsequent stored roots retain only append paths. */
    DocumentSession.IndexedState retain(DocumentSession.IndexedState state) {
        var viewPositions = state.rootedViewPositions();
        var firstAddresses = state.viewAddressFirstPositions().copy();
        if (!viewPositions.root().isStored()) {
            var prepared = SessionHistoryList.<DocumentSession.RootedViewPosition>empty();
            for (int index = 0; index < viewPositions.size(); index++) {
                var position = viewPositions.get(index);
                String address = payloads.addressForRetention(position.view());
                Long prior = firstAddresses.putIfAbsent(address, (long) index);
                // Retain once, then record the exact scope-owned address without forcing it on reads.
                require(address.equals(payloads.retainView(position.view())), "Prepared view address changed");
                prepared.add(DocumentSession.RootedViewPosition.stored(position.boundary(), position.invocationIdentity(),
                        prior == null ? index : prior, address, () -> payloads.view(address)));
            }
            viewPositions = prepared;
        }
        // The finite admission basis and current tip are explicit descriptor dependencies.
        if (state.rootedHistory() != null)
            state.rootedHistory().admissionSources().storedViews().values().forEach(payloads::retainView);
        if (state.rootedView() != null) payloads.retainView(state.rootedView());
        return new DocumentSession.IndexedState(state.documentId(), state.authoredInitialBlueId(), state.activeSubscriptions(),
                retainList("revisions", state.revisions(), revisions), retainSet("terminal-entries", state.terminalEntryBlueIds()),
                retainSet("transition-receipts", state.transitionReceipts()),
                retainList("representations", state.representationTransitions(), representations),
                state.rootedHistory(), state.rootedView(), retainList("view-positions", viewPositions, positions),
                retainMap("state-epochs", state.stateEpochs(), history.ordinals), retainSet("ambiguous-states", state.ambiguousStates()),
                history.retain("retained-states", state.retainedStates(), history.ordinals, history.retainedStates),
                history.retain("entry-epochs", state.sourceEntryEpochs(), history.strings, history.ordinals),
                history.retain("representation-ranges", state.representationRanges(), history.ordinals, ranges),
                history.retain("representation-positions", state.representationStatePositions(), epochStates, history.membership),
                history.retain("representation-states", state.representationStates(), history.strings, history.membership),
                history.retain("representation-receipts", state.representationReceiptPositions(), history.strings, history.ordinals),
                history.retain("invocation-positions", state.invocationFirstPositions(), history.strings, history.ordinals),
                retainMap("view-address-positions", firstAddresses, history.ordinals), state.lastAnchoredNonReplayableEpoch(),
                state.layout(), state.readyLayout(), state.readyEmbeddedChildren(), state.status(), state.readyThrough(),
                state.epoch(), state.readyEpoch(), state.graphPublishedEpoch(), state.applicationSequence());
    }

    byte[] encode(DocumentSession.IndexedState state) {
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(DocumentSessionStorage.INDEXED_SESSION);
            out.text(state.documentId().value()); out.text(state.authoredInitialBlueId());
            list(out, state.activeSubscriptions(), rows::subscription);
            SessionHistoryStorage.root(out, state.revisions().root());
            roots(out, state.terminalEntryBlueIds().map()); roots(out, state.transitionReceipts().map());
            SessionHistoryStorage.root(out, state.representationTransitions().root());
            optional(out, state.rootedHistory(), (w, value) -> rows.history(w, value, payloads::addressForRetention));
            optional(out, state.rootedView(), (w, value) -> w.text(payloads.addressForRetention(value)));
            SessionHistoryStorage.root(out, state.rootedViewPositions().root());
            roots(out, state.stateEpochs()); roots(out, state.ambiguousStates().map());
            SessionHistoryStorage.root(out, state.retainedStates()); SessionHistoryStorage.root(out, state.sourceEntryEpochs());
            SessionHistoryStorage.root(out, state.representationRanges()); SessionHistoryStorage.root(out, state.representationStatePositions());
            SessionHistoryStorage.root(out, state.representationStates()); SessionHistoryStorage.root(out, state.representationReceiptPositions());
            SessionHistoryStorage.root(out, state.invocationFirstPositions()); roots(out, state.viewAddressFirstPositions());
            out.longValue(state.lastAnchoredNonReplayableEpoch());
            rows.layout(out, state.layout()); rows.layout(out, state.readyLayout());
            orderedMap(out, state.readyEmbeddedChildren(), (w, id) -> w.text(id.value())); out.text(state.status().name());
            order(out, state.readyThrough()); out.longValue(state.epoch()); out.longValue(state.readyEpoch());
            out.longValue(state.graphPublishedEpoch()); out.longValue(state.applicationSequence());
        });
    }

    DocumentSession.IndexedState decode(byte[] bytes) {
        return SessionStorageWire.decode(bytes, maximumBytes, in -> {
            require(DocumentSessionStorage.INDEXED_SESSION.equals(text(in)), "Wrong indexed session format");
            return new DocumentSession.IndexedState(DocumentId.of(text(in)), text(in), list(in, rows::subscription),
                    openList(in, "revisions", revisions), openSet(in, "terminal-entries"), openSet(in, "transition-receipts"),
                    openList(in, "representations", representations), optional(in, r -> rows.history(r, payloads::view)),
                    optional(in, r -> payloads.view(text(r))), openList(in, "view-positions", positions),
                    openMap(in, "state-epochs", history.ordinals), openSet(in, "ambiguous-states"),
                    history.open(in, "retained-states", Long::compare, history.ordinals, history.retainedStates),
                    history.open(in, "entry-epochs", String::compareTo, history.strings, history.ordinals),
                    history.open(in, "representation-ranges", Long::compare, history.ordinals, ranges),
                    history.open(in, "representation-positions", Comparator.naturalOrder(), epochStates, history.membership),
                    history.open(in, "representation-states", String::compareTo, history.strings, history.membership),
                    history.open(in, "representation-receipts", String::compareTo, history.strings, history.ordinals),
                    history.open(in, "invocation-positions", String::compareTo, history.strings, history.ordinals),
                    openMap(in, "view-address-positions", history.ordinals), in.longValue(), rows.layout(in), rows.layout(in),
                    orderedMap(in, r -> DocumentId.of(text(r))), SessionStatus.valueOf(text(in)), order(in),
                    in.longValue(), in.longValue(), in.longValue(), in.longValue());
        });
    }

    private <T> SessionHistoryList<T> retainList(String lane, SessionHistoryList<T> values, PersistentMapCodec<T> codec) {
        return SessionHistoryList.fromRoot(history.retain(lane, values.root(), history.ordinals, codec));
    }
    private <T> SessionHistoryList<T> openList(Reader in, String lane, PersistentMapCodec<T> codec) {
        return SessionHistoryList.fromRoot(history.open(in, lane, Long::compare, history.ordinals, codec));
    }
    private <T> SessionHistoryMap<String, T> retainMap(String lane, SessionHistoryMap<String, T> values, PersistentMapCodec<T> codec) {
        return SessionHistoryMap.fromRoots(history.retain(lane + "/values", values.valuesRoot(), history.strings, codec),
                history.retain(lane + "/order", values.orderRoot(), history.ordinals, history.strings));
    }
    private <T> SessionHistoryMap<String, T> openMap(Reader in, String lane, PersistentMapCodec<T> codec) {
        return SessionHistoryMap.fromRoots(history.open(in, lane + "/values", String::compareTo, history.strings, codec),
                history.open(in, lane + "/order", Long::compare, history.ordinals, history.strings));
    }
    private SessionHistorySet<String> retainSet(String lane, SessionHistorySet<String> values) {
        return SessionHistorySet.fromMap(retainMap(lane, values.map(), history.membership));
    }
    private SessionHistorySet<String> openSet(Reader in, String lane) {
        return SessionHistorySet.fromMap(openMap(in, lane, history.membership));
    }
    private static void roots(Writer out, SessionHistoryMap<?, ?> values) {
        SessionHistoryStorage.root(out, values.valuesRoot()); SessionHistoryStorage.root(out, values.orderRoot());
    }
}
