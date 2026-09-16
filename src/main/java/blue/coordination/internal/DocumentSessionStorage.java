package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.processor.closure.AffectedClosureSnapshotStorageCodec;
import blue.language.provider.CyclicSetProof;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import static blue.coordination.internal.SessionStorageWire.*;
import static blue.coordination.internal.SessionRecordCodec.*;

/**
 * Private per-session recovery component. Retention prewrites immutable bytes only;
 * the caller publishes the returned reference together with its other runtime state.
 * Neither this class nor an open scope supplies an engine/SDK publication authority.
 */
final class DocumentSessionStorage {
    private static final String LEGACY_SESSION = "blue-coordination/document-session-storage/1";
    private static final String SESSION = "blue-coordination/document-session-storage/2";
    static final String INDEXED_SESSION = "blue-coordination/document-session-storage/3";
    private static final String VIEW = "blue-coordination/rooted-document-view-storage/1";
    private final CoordinationImmutableObjectStore objects;
    private final Limits limits;
    private final SessionRecordCodec rows;
    private final StoredClosureResultCodec results;
    private final AffectedClosureSnapshotStorageCodec snapshots;
    private final Runnable fullViewEncodingObserver;
    private final RootedStorageCache cache;

    /** Bounds physical records and all revision/view dependencies in one explicit restoration scope. */
    record Limits(int maximumRecordBytes, int maximumDepth, long maximumScopeBytes) {
        Limits {
            if (maximumRecordBytes < 1024 || maximumDepth < 1 || maximumDepth > 256
                    || maximumScopeBytes < maximumRecordBytes) throw new IllegalArgumentException("Invalid session storage limits");
        }
    }

    DocumentSessionStorage(CoordinationImmutableObjectStore objects, Limits limits) {
        this(objects, limits, null, null);
    }

    DocumentSessionStorage(CoordinationImmutableObjectStore objects, Limits limits, RootedStorageCache cache) {
        this(objects, limits, null, cache);
    }

    /** Package-only test observation of actual full encodes; production retains no counter or history. */
    DocumentSessionStorage(CoordinationImmutableObjectStore objects, Limits limits, Runnable fullViewEncodingObserver) {
        this(objects, limits, fullViewEncodingObserver, null);
    }

    private DocumentSessionStorage(CoordinationImmutableObjectStore objects, Limits limits,
            Runnable fullViewEncodingObserver, RootedStorageCache cache) {
        this.objects = Objects.requireNonNull(objects); this.limits = Objects.requireNonNull(limits);
        this.fullViewEncodingObserver = fullViewEncodingObserver;
        this.cache = cache;
        rows = new SessionRecordCodec(limits.maximumRecordBytes(), limits.maximumDepth(), cache);
        results = new StoredClosureResultCodec(limits.maximumRecordBytes(), limits.maximumDepth(), cache);
        snapshots = new AffectedClosureSnapshotStorageCodec(limits.maximumRecordBytes(), limits.maximumDepth());
    }

    String retain(DocumentSession session) { return retain(session, ignored -> null); }

    /** Proof lookup is retention-only. Restore never invokes it or an external provider. */
    String retain(DocumentSession session, Function<String, CyclicSetProof> externalProofs) {
        if (RootedEngineStorage.isControlledNamespace(objects)) {
            // Conversion must not attach a short-lived physical owner to the caller's resident session.
            try (var scope = openScope()) {
                scope.retentionProofs = Objects.requireNonNull(externalProofs);
                return scope.retainIndexed(session.copyForAtomicPublication());
            }
        }
        return retain(session, externalProofs, ignored -> null, (view, address) -> { },
                ignored -> null, (revision, address) -> { });
    }

    private String retain(DocumentSession session, Function<String, CyclicSetProof> externalProofs,
            Function<RootedDocumentView, byte[]> selectedBytes, BiConsumer<RootedDocumentView, String> retained,
            Function<DocumentRevision, byte[]> selectedRevisions, BiConsumer<DocumentRevision, String> retainedRevision) {
        return physical(() -> {
            var records = new LinkedHashMap<String, byte[]>();
            var views = new IdentityHashMap<RootedDocumentView, String>();
            var revisions = new IdentityHashMap<DocumentRevision, String>();
            var authenticatedDependencies = new HashSet<String>();
            long[] bytes = {0};
            Function<byte[], String> stage = encoded -> {
                String address = digest(encoded);
                byte[] prior = records.putIfAbsent(address, encoded);
                if (prior == null) {
                    bytes[0] = Math.addExact(bytes[0], encoded.length);
                    require(bytes[0] <= limits.maximumScopeBytes(), "Session retention scope byte bound exceeded");
                } else require(Arrays.equals(prior, encoded), "Conflicting exact physical record");
                return address;
            };
            Function<RootedDocumentView, String> view = selected -> views.computeIfAbsent(selected, value -> {
                byte[] known = selectedBytes.apply(value);
                String address = stage.apply(known == null ? encodeView(value, externalProofs) : known);
                if (known != null) authenticatedDependencies.add(address);
                return address;
            });
            Function<DocumentRevision, String> revision = selected -> revisions.computeIfAbsent(selected, value -> {
                byte[] known = selectedRevisions.apply(value);
                String address = stage.apply(known == null ? rows.encodeRevision(value) : known);
                if (known != null) authenticatedDependencies.add(address);
                return address;
            });
            byte[] encoded = encodeSession(session.storedState(), view, revision);
            String address = stage.apply(encoded);
            // No writes occur before every record has serialized successfully. Only immutable
            // dependencies freshly authenticated in this call already have retained exact bytes.
            // The mutable session is always serialized above and acknowledged below.
            records.forEach((key, value) -> {
                if (key.equals(address) || !authenticatedDependencies.contains(key)) write(key, value);
            });
            // Only a complete successful retention lends transport evidence to this one stage.
            views.forEach(retained);
            revisions.forEach(retainedRevision);
            return address;
        });
    }

    /** One selected-runtime identity scope, shared by its independently opened sessions. */
    OpenScope openScope() { return new OpenScope(); }

    /** Prewrites one immutable view for a separately published cohort or index record. */
    String retainView(RootedDocumentView view) {
        return physical(() -> {
            byte[] encoded = encodeView(Objects.requireNonNull(view), ignored -> null);
            String address = digest(encoded); write(address, encoded); return address;
        });
    }

    /** Pure address derivation for canonical index encoding after explicit dependency retention. */
    String viewAddress(RootedDocumentView view) {
        return physical(() -> digest(encodeView(Objects.requireNonNull(view), ignored -> null)));
    }

    final class OpenScope implements AutoCloseable {
        private final Map<String, RootedDocumentView> views = new HashMap<>();
        private final Map<RootedDocumentView, String> viewAddresses = new IdentityHashMap<>();
        private final Map<String, DocumentRevision> revisions = new HashMap<>();
        private final Map<DocumentRevision, String> revisionAddresses = new IdentityHashMap<>();
        private final Map<RootedDocumentView, byte[]> preparedViews = new IdentityHashMap<>();
        private long retainedDependencyBytes;
        private boolean closed;
        private Map<String, ViewReference> capturedViews;
        private Function<String, CyclicSetProof> retentionProofs = ignored -> null;
        private final IndexedSessionStorage.Payloads indexedPayloads = new IndexedSessionStorage.Payloads() {
            @Override public String retainView(RootedDocumentView view) { return retainIndexedView(view); }
            @Override public String retainRevision(DocumentRevision revision) { return retainIndexedRevision(revision); }
            @Override public String revisionIdentity(DocumentRevision revision) { return indexedRevisionIdentity(revision); }
            @Override public String addressForRetention(RootedDocumentView view) { return indexedViewIdentity(view); }
            @Override public String retainedAddress(RootedDocumentView view) { return viewAddresses.get(view); }
            @Override public RootedDocumentView view(String address) { return OpenScope.this.view(address); }
            @Override public DocumentRevision revision(String address) { return OpenScope.this.revision(address); }
        };
        private final IndexedSessionStorage indexed = new IndexedSessionStorage(objects, limits.maximumRecordBytes(), rows, indexedPayloads);

        /** Explicit transport-only stage; no OpenScope lock is held while the caller selects owners. */
        synchronized RetentionStage openRetentionStage() {
            require(!closed, "Session restoration scope is closed");
            return new RetentionStage();
        }

        /** Opaque, owner-bound addresses from successful encoding, never decoded/publication authority. */
        final class RetentionStage implements AutoCloseable {
            private final Map<RootedDocumentView, String> retained = new IdentityHashMap<>();
            private final Map<DocumentRevision, String> retainedRevisions = new IdentityHashMap<>();
            private boolean closed;
            private RetentionStage() { }
            synchronized String retain(DocumentSession session) {
                require(!closed, "View retention stage is closed");
                return OpenScope.this.retain(session, retained, retainedRevisions);
            }
            @Override public synchronized void close() { closed = true; retained.clear(); retainedRevisions.clear(); }
        }

        /** Always returns a detached mutable session; only complete immutable artifacts are shared. */
        synchronized DocumentSession open(DocumentId expectedDocument, String address) {
            return physical(() -> {
                require(!closed, "Session restoration scope is closed");
                byte[] encoded = read(address);
                // Read only the bounded format tag; the full decoder separately consumes the frame.
                String format = formatTag(encoded, limits.maximumRecordBytes());
                if (INDEXED_SESSION.equals(format)) {
                    var state = indexed.decode(encoded);
                    require(expectedDocument.equals(state.documentId()), "Selected indexed session belongs to another document");
                    require(Arrays.equals(encoded, indexed.encode(state)), "Noncanonical indexed session descriptor");
                    return RootedEngineStorage.isControlledNamespace(objects)
                            ? DocumentSession.restoreControlledIndexed(state, indexedPayloads)
                            : DocumentSession.restoreIndexed(state, indexedPayloads);
                }
                DecodedSession decoded = decode(encoded, limits.maximumRecordBytes(), in -> {
                    String selectedFormat = text(in);
                    require(SESSION.equals(selectedFormat) || LEGACY_SESSION.equals(selectedFormat), "Wrong session storage format");
                    boolean legacy = LEGACY_SESSION.equals(selectedFormat);
                    return new DecodedSession(session(in, this::view, legacy ? null : this::revision), legacy);
                });
                DocumentSession.StoredState state = decoded.state();
                require(expectedDocument.equals(state.documentId()), "Selected session address belongs to another document");
                DocumentSession session = DocumentSession.restoreStored(state);
                require(Arrays.equals(encoded, encodeSession(session.storedState(), selected -> {
                    // Restored references are guaranteed to come from this exact scope.
                    String selectedAddress = viewAddresses.get(selected);
                    require(selectedAddress != null, "Rooted view escaped its restoration scope"); return selectedAddress;
                }, decoded.legacy() ? null : selected -> {
                    String selectedAddress = revisionAddresses.get(selected);
                    require(selectedAddress != null, "Revision escaped its restoration scope"); return selectedAddress;
                })), "Noncanonical or incomplete session record");
                return session;
            });
        }

        synchronized RootedDocumentView view(String address) {
            return physical(() -> {
                require(!closed, "Session restoration scope is closed");
                var selected = loadView(address);
                if (capturedViews != null && !capturedViews.containsKey(address))
                    capturedViews.put(address, new ViewReference(address, read(address), selected));
                return selected;
            });
        }

        /** Records every immutable view reference used by one fully decoded enclosing frame. */
        synchronized <T> WithViews<T> captureViews(Supplier<T> fullyVerifiedDecode) {
            require(!closed, "Session restoration scope is closed");
            require(capturedViews == null, "Nested enclosing view capture is unsupported");
            capturedViews = new LinkedHashMap<>();
            try {
                T decoded = fullyVerifiedDecode.get();
                return new WithViews<>(decoded, new ViewManifest(List.copyOf(capturedViews.values())));
            } finally {
                capturedViews = null;
            }
        }

        /**
         * Rebinds cached immutable dependencies to this owner, never the old owner itself.
         * Current physical reads, exact bytes and complete scope capacity are checked first;
         * an existing different local object identity uses the ordinary decoder instead.
         */
        synchronized boolean acceptViews(ViewManifest manifest) {
            return physical(() -> {
                require(!closed, "Session restoration scope is closed");
                for (var reference : manifest.references) {
                    var current = views.get(reference.address());
                    if (current != null && current != reference.view()) return false;
                }
                long additionalBytes = 0;
                for (var reference : manifest.references) {
                    require(Arrays.equals(reference.bytes(), read(reference.address())),
                            "Cached rooted view differs from selected immutable bytes");
                    if (!views.containsKey(reference.address()))
                        additionalBytes = Math.addExact(additionalBytes, reference.bytes().length);
                }
                require(additionalBytes <= limits.maximumScopeBytes() - retainedDependencyBytes,
                        "Session restoration scope byte bound exceeded");
                for (var reference : manifest.references) {
                    views.put(reference.address(), reference.view());
                    viewAddresses.put(reference.view(), reference.address());
                }
                retainedDependencyBytes += additionalBytes;
                return true;
            });
        }

        synchronized String addressOf(RootedDocumentView view) {
            require(!closed, "Session restoration scope is closed");
            String address = viewAddresses.get(view);
            require(address != null, "Rooted view escaped its restoration scope");
            return address;
        }

        /** Prewrite through this owner; controlled storage may reuse an acknowledged exact object. */
        synchronized String retainView(RootedDocumentView view) {
            require(!closed, "Session restoration scope is closed");
            return physical(() -> RootedEngineStorage.isControlledNamespace(objects)
                    ? retainIndexedView(view) : DocumentSessionStorage.this.retainView(view));
        }

        /**
         * Pure physical identity for a new view. This is not retained membership
         * or permission to publish it. Kept scope-local so append and stage do
         * not encode the same new immutable view twice.
         */
        synchronized String indexedViewIdentity(RootedDocumentView view) {
            require(!closed, "Session restoration scope is closed");
            String known = viewAddresses.get(view);
            if (known != null) return known;
            byte[] bytes = preparedViews.get(view);
            if (bytes == null) {
                bytes = encodeView(view, retentionProofs);
                require(bytes.length <= limits.maximumScopeBytes() - retainedDependencyBytes,
                        "Session restoration scope byte bound exceeded");
                preparedViews.put(view, bytes);
                retainedDependencyBytes += bytes.length;
            }
            return digest(bytes);
        }

        /** Only unchanged records in an explicitly controlled writer namespace use address reuse. */
        synchronized String retainIndexedView(RootedDocumentView view) {
            require(!closed, "Session restoration scope is closed");
            String known = viewAddresses.get(view);
            if (known != null) return known;
            String address = indexedViewIdentity(view);
            byte[] bytes = preparedViews.get(view);
            write(address, bytes);
            viewAddresses.put(view, address);
            views.putIfAbsent(address, view);
            preparedViews.remove(view);
            return address;
        }

        synchronized String indexedRevisionIdentity(DocumentRevision revision) {
            require(!closed, "Session restoration scope is closed");
            String known = revisionAddresses.get(revision);
            return known == null ? digest(rows.encodeRevision(revision)) : known;
        }

        synchronized String retainIndexedRevision(DocumentRevision revision) {
            require(!closed, "Session restoration scope is closed");
            String known = revisionAddresses.get(revision);
            if (known != null) return known;
            byte[] bytes = rows.encodeRevision(revision);
            require(bytes.length <= limits.maximumScopeBytes() - retainedDependencyBytes,
                    "Session restoration scope byte bound exceeded");
            String address = digest(bytes); write(address, bytes);
            revisionAddresses.put(revision, address); revisions.putIfAbsent(address, revision);
            retainedDependencyBytes += bytes.length;
            return address;
        }

        /**
         * Reuse decoded dependencies or the same immutable dependency retained earlier in the explicit stage.
         * Re-read and authenticate their bytes without rewriting these existing dependencies;
         * normal staging bounds and acknowledgements for every actual new/session write remain.
         */
        synchronized String retain(DocumentSession session) {
            return retain(session, null, null);
        }

        private synchronized String retain(DocumentSession session, Map<RootedDocumentView, String> retained,
                Map<DocumentRevision, String> retainedRevisions) {
            return physical(() -> {
                require(!closed, "Session restoration scope is closed");
                if (RootedEngineStorage.isControlledNamespace(objects)) return retainIndexed(session);
                return DocumentSessionStorage.this.retain(session, ignored -> null, view -> {
                    String address = viewAddresses.get(view);
                    if (address == null && retained != null) address = retained.get(view);
                    return address == null ? null : read(address);
                }, (view, address) -> {
                    if (retained != null) retained.put(view, address);
                }, revision -> {
                    String address = revisionAddresses.get(revision);
                    if (address == null && retainedRevisions != null) address = retainedRevisions.get(revision);
                    return address == null ? null : read(address);
                }, (revision, address) -> {
                    if (retainedRevisions != null) retainedRevisions.put(revision, address);
                });
            });
        }

        private synchronized String retainIndexed(DocumentSession session) {
            return physical(() -> {
                require(!closed, "Session restoration scope is closed");
                synchronized (session) {
                    var state = indexed.retain(session.indexedState());
                    byte[] encoded = indexed.encode(state);
                    String address = digest(encoded); write(address, encoded);
                    session.installRetainedIndexedState(state, indexedPayloads);
                    return address;
                }
            });
        }

        private RootedDocumentView loadView(String address) {
            RootedDocumentView cached = views.get(address);
            if (cached != null) return cached;
            byte[] encoded = read(address);
            require(encoded.length <= limits.maximumScopeBytes() - retainedDependencyBytes, "Session restoration scope byte bound exceeded");
            RootedDocumentView restored = cache == null ? decodeView(encoded)
                    : cache.decode(VIEW + "/" + limits.maximumRecordBytes() + "/" + limits.maximumDepth(),
                            encoded, () -> decodeView(encoded));
            views.put(address, restored); viewAddresses.put(restored, address); retainedDependencyBytes += encoded.length;
            return restored;
        }

        private DocumentRevision revision(String address) {
            // Controlled immutable storage preserves the exact owner-local artifact after
            // its first authenticated read. A fresh owner still reads the selected bytes.
            DocumentRevision selected = revisions.get(address);
            if (selected != null && RootedEngineStorage.isControlledNamespace(objects)) return selected;
            // Generic storage preserves its existing authenticate-on-read contract.
            byte[] encoded = read(address);
            if (selected != null) return selected;
            require(encoded.length <= limits.maximumScopeBytes() - retainedDependencyBytes,
                    "Session restoration scope byte bound exceeded");
            DocumentRevision restored = rows.decodeRevision(encoded);
            revisions.put(address, restored); revisionAddresses.put(restored, address);
            retainedDependencyBytes += encoded.length;
            return restored;
        }

        /** Close only when its selected runtime is retired; live sessions retain their shared views. */
        @Override public synchronized void close() {
            closed = true; views.clear(); viewAddresses.clear(); revisions.clear(); revisionAddresses.clear();
            preparedViews.clear();
            retainedDependencyBytes = 0; capturedViews = null;
        }
    }

    /** Opaque complete-frame dependencies; all byte arrays are privately owned. */
    private record ViewReference(String address, byte[] bytes, RootedDocumentView view) { }

    static final class ViewManifest {
        private final List<ViewReference> references;
        private ViewManifest(List<ViewReference> references) { this.references = List.copyOf(references); }
        long encodedBytes() {
            long total = 0;
            for (var reference : references) total = Math.addExact(total, reference.bytes().length);
            return total;
        }
    }

    record WithViews<T>(T value, ViewManifest views) { }

    private record DecodedSession(DocumentSession.StoredState state, boolean legacy) { }

    /** Null revision addressing is used only to verify the legacy inline format on read. */
    private byte[] encodeSession(DocumentSession.StoredState state, Function<RootedDocumentView, String> address,
            Function<DocumentRevision, String> revisionAddress) {
        return encode(limits.maximumRecordBytes(), out -> {
            out.text(revisionAddress == null ? LEGACY_SESSION : SESSION);
            out.text(state.documentId().value()); out.text(state.authoredInitialBlueId());
            list(out, state.activeSubscriptions(), rows::subscription);
            if (revisionAddress == null) list(out, state.revisions(), rows::revision);
            else list(out, state.revisions(), (w, row) -> w.text(revisionAddress.apply(row)));
            stringSet(out, state.terminalEntryBlueIds()); stringSet(out, state.transitionReceipts());
            list(out, state.representationTransitions(), (w, row) -> {
                w.longValue(row.epoch()); w.text(row.beforeBlueId()); w.text(row.afterBlueId());
                w.text(row.transitionReceiptIdentity()); w.nullableText(row.originalPublicationIdentity());
            });
            optional(out, state.rootedHistory(), (w, history) -> rows.history(w, history, address));
            optional(out, state.rootedView(), (w, view) -> w.text(address.apply(view)));
            list(out, state.rootedViewPositions(), (w, position) -> {
                w.text(address.apply(position.view())); optional(w, position.boundary(), SessionStorageWire::order);
            });
            orderedMap(out, state.stateEpochs(), Writer::longValue); stringSet(out, state.ambiguousStates());
            rows.layout(out, state.layout()); rows.layout(out, state.readyLayout());
            orderedMap(out, state.readyEmbeddedChildren(), (w, id) -> w.text(id.value())); out.text(state.status().name());
            order(out, state.readyThrough()); out.longValue(state.epoch()); out.longValue(state.readyEpoch());
            out.longValue(state.graphPublishedEpoch()); out.longValue(state.applicationSequence());
        });
    }

    @SuppressWarnings("unchecked")
    private DocumentSession.StoredState session(Reader in, Function<String, RootedDocumentView> view,
            Function<String, DocumentRevision> revision) {
        return new DocumentSession.StoredState(DocumentId.of(text(in)), text(in), list(in, rows::subscription),
                revision == null ? list(in, rows::revision) : list(in, r -> revision.apply(text(r))), stringSet(in), stringSet(in),
                list(in, r -> new DocumentSession.ComponentRepresentationTransition(r.longValue(), text(r), text(r), text(r), nullableText(r))),
                optional(in, r -> rows.history(r, view)), optional(in, r -> view.apply(text(r))),
                list(in, r -> new DocumentSession.RootedViewPosition(view.apply(text(r)), optional(r, SessionStorageWire::order))),
                orderedMap(in, Reader::longValue), stringSet(in), rows.layout(in), rows.layout(in), orderedMap(in, r -> DocumentId.of(text(r))),
                SessionStatus.valueOf(text(in)), order(in), in.longValue(), in.longValue(), in.longValue(), in.longValue());
    }

    private byte[] encodeView(RootedDocumentView view, Function<String, CyclicSetProof> externalProofs) {
        if (fullViewEncodingObserver != null) fullViewEncodingObserver.run();
        return encodeView(view, externalProofs, null);
    }

    /** Only decodeView supplies payloads, after each nested codec and all cross-links have passed. */
    private byte[] encodeView(RootedDocumentView view, Function<String, CyclicSetProof> externalProofs,
            VerifiedViewPayloads verified) {
        var state = view.storedState();
        return encode(limits.maximumRecordBytes(), out -> {
            out.text(VIEW); out.bytes(verified == null ? results.encode(state.result(), externalProofs) : verified.result());
            optional(out, state.logicalBoundary(), SessionStorageWire::order);
            out.bytes(verified == null ? snapshots.encode(state.snapshot()) : verified.snapshot());
            rows.inventory(out, state.subscriptions());
            documents(out, state.routes(), (w, routes) -> list(w, routes, rows::subscription));
            documents(out, state.publishedHeads(), (w, head) -> { w.longValue(head.epoch()); w.text(head.blueId()); });
            out.bytes(verified == null ? snapshots.encode(state.retainedSnapshot()) : verified.retainedSnapshot());
        });
    }

    /** Private decode-local bytes: no cache, publication authority, mutable session or scope escape. */
    private record VerifiedViewPayloads(byte[] result, byte[] snapshot, byte[] retainedSnapshot) { }
    private record DecodedView(RootedDocumentView view, VerifiedViewPayloads payloads) { }

    private RootedDocumentView decodeView(byte[] encoded) {
        DecodedView decoded = decode(encoded, limits.maximumRecordBytes(), in -> {
            require(VIEW.equals(text(in)), "Wrong rooted-view storage format");
            byte[] resultBytes = in.bytes(limits.maximumRecordBytes());
            var result = results.decode(resultBytes);
            var boundary = optional(in, SessionStorageWire::order);
            byte[] snapshotBytes = in.bytes(limits.maximumRecordBytes());
            var snapshot = snapshots.decode(snapshotBytes);
            if (result.rootedProjection() != null) require(Arrays.equals(snapshotBytes,
                    snapshots.encode(result.rootedProjection().resultingSnapshot())), "Original rooted witness snapshot differs from result");
            var subscriptions = rows.inventory(in);
            var routes = documents(in, r -> list(r, rows::subscription));
            var heads = documents(in, r -> new InMemoryDocumentStore.DocumentHead(r.longValue(), text(r)));
            byte[] retainedBytes = in.bytes(limits.maximumRecordBytes());
            var retained = snapshots.decode(retainedBytes);
            var restored = RootedDocumentView.restoreStored(new RootedDocumentView.StoredState(result, boundary, snapshot,
                    subscriptions, routes, heads, retained));
            // Validation only: preserve the explicitly stored snapshot, including its private witness roles.
            // This oracle starts from the complete original processor result, never flat ambient heads.
            var epochs = new LinkedHashMap<blue.language.processor.closure.DocumentId, Long>();
            restored.storedState().publishedHeads().forEach((id, head) -> epochs.put(ContractsClosureAdapter.closureId(id), head.epoch()));
            var expected = result.rootedProjection() == null ? snapshot
                    : blue.language.processor.closure.ClosureEvidenceFactory.rootedRetainedSnapshot(result, epochs);
            require(Arrays.equals(snapshots.encode(expected), retainedBytes),
                    "Retained rooted witness roles or positions differ from original result");
            return new DecodedView(restored, new VerifiedViewPayloads(resultBytes, snapshotBytes, retainedBytes));
        });
        // Nested decoders already proved each exact payload's canonical round trip. Reusing those
        // private bytes preserves the whole-envelope check without re-verifying the same graphs.
        require(Arrays.equals(encoded, encodeView(decoded.view(), ignored -> null, decoded.payloads())),
                "Noncanonical or incomplete rooted-view record");
        return decoded.view();
    }

    private void write(String address, byte[] encoded) {
        byte[] acknowledged = objects.putIfAbsent(address, encoded.clone());
        require(acknowledged != null && Arrays.equals(encoded, acknowledged), "Session immutable-byte acknowledgment differs");
    }
    private byte[] read(String address) {
        require(address != null && address.matches("[0-9a-f]{64}"), "Invalid session physical address");
        byte[] returned = objects.get(address, limits.maximumRecordBytes()).orElseThrow(() ->
                new CoordinationObjectStorageException("Missing selected session record " + address));
        require(returned.length <= limits.maximumRecordBytes(), "Selected session record exceeds byte limit");
        byte[] encoded = returned.clone(); require(address.equals(digest(encoded)), "Selected session record digest differs"); return encoded;
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
