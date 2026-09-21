package blue.coordination.internal;

import blue.coordination.api.*;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Authenticated native source-request context and immutable original descriptor reconciliation. */
final class LogicalSourceRequests {
    private static final String FORMAT = "blue-coordination/source-request/1";
    private final LogicalRecordContext context;
    private final LogicalDocumentInstances instances;
    private final SourceDiscoveryStorageCodec codec;
    private final int maximumBytes;
    private final Map<Key, Bytes> pending = new HashMap<>();
    LogicalSourceRequests(LogicalRecordContext context, SourceDiscoveryStorageCodec codec, int maximumBytes) {
        this.context = context; this.codec = codec; this.maximumBytes = maximumBytes;
        instances = context.instances(maximumBytes);
    }
    String bind(SourceHistoryRequest request) {
        return context.protect(() -> {
            authenticate(request); var bytes = encodeRequest(request); var key = requestKey(request);
            var prior = read(key);
            if (prior.present() && !prior.content().equals(bytes)) throw new IllegalStateException("Source context has different selection operands");
            if (!prior.present()) {
                if (prior.revision() != 0) throw new IllegalStateException("Source context was deleted");
                put(key, bytes);
            }
            var original = originalKey(request.prerequisite()); var old = read(original);
            if (!old.present()) {
                if (old.revision() != 0) throw new IllegalStateException("Original source context was deleted");
                put(original, bytes);
            } else if (!decodeRequest(old.content()).prerequisite().equals(request.prerequisite()))
                throw new IllegalStateException("Source selection identity has different canonical operands");
            return storageKey(request);
        });
    }
    SourceHistoryRequest original(SourceHistoryPrerequisite descriptor) {
        return context.protect(() -> findOriginal(descriptor).orElseThrow(() -> new IllegalArgumentException("Unknown original source request")));
    }
    Optional<SourceHistoryRequest> findOriginal(SourceHistoryPrerequisite descriptor) {
        return context.protect(() -> {
            var value = read(originalKey(descriptor));
            if (!value.present()) {
                if (value.revision() != 0) throw new IllegalStateException("Original source context was deleted");
                return Optional.empty();
            }
            var request = decodeRequest(value.content());
            if (!request.prerequisite().equals(descriptor)) return Optional.empty();
            require(request); return Optional.of(request);
        });
    }
    SourceHistoryRequest byStorageKey(String key) {
        return context.protect(() -> {
            var value = read(key("contexts", key));
            if (!value.present()) throw new IllegalStateException("Source selection lacks its native execution context");
            var request = decodeRequest(value.content());
            if (!storageKey(request).equals(key)) throw new IllegalStateException("Source selection context key differs");
            return request;
        });
    }
    void require(SourceHistoryRequest request) {
        context.protect(() -> {
            var selected = read(requestKey(request));
            if (!selected.present() || !selected.content().equals(encodeRequest(request)))
                throw new IllegalArgumentException("Unknown or mixed source execution context");
            authenticate(request); return null;
        });
    }
    boolean currentRequesters(SourceHistoryRequest request) {
        return context.protect(() -> {
            require(request);
            return request.requestingInstances().stream().allMatch(ref -> instances.select(ref.documentId()).instance().equals(Optional.of(ref)));
        });
    }
    void requireExecutable(SourceHistoryRequest request) {
        context.protect(() -> {
            require(request); request.requestingInstances().forEach(instances::requireActive);
            var binding = instances.select(request.sourceInstance().documentId());
            if (request.prerequisite().kind() == SourceHistoryPrerequisite.Kind.ADMISSION && binding.generation() == 0) {
                if (!request.sourceInstance().equals(LogicalDocumentInstances.initialReference(binding.document())))
                    throw new IllegalStateException("Unhosted source requires original initial instance");
            } else instances.requireActive(request.sourceInstance());
            return null;
        });
    }
    void retainStage(SourceHistoryRequest request, SourceHistoryStageContext stage) {
        context.protect(() -> {
            require(request);
            if (!stage.prerequisite().equals(request.prerequisite())) throw new IllegalStateException("Source stage descriptor differs");
            var bytes = new Bytes(encode(maximumBytes, w -> {
                w.text(FORMAT + "/stage"); w.integer(stage.entryOwners().size());
                for (var owner : stage.entryOwners()) {
                    w.text(owner.documentId().value()); w.bool(owner.predecessor().isPresent());
                    owner.predecessor().ifPresent(prior -> {
                        w.longValue(prior.epoch()); w.text(prior.headBlueId()); w.text(prior.historyIdentity());
                        w.longValue(prior.graphGeneration()); w.text(prior.closureIdentity());
                    });
                }
                w.integer(stage.invocationIdentities().size()); stage.invocationIdentities().forEach(w::text);
            }));
            var key = key("stage", storageKey(request)); var old = read(key);
            if (old.present() && !old.content().equals(bytes)) throw new IllegalStateException("Source stage changed its original fences");
            if (!old.present()) { if (old.revision() != 0) throw new IllegalStateException("Source stage was deleted"); put(key, bytes); }
            return null;
        });
    }
    SourceHistoryStageContext stage(SourceHistoryRequest request) {
        return context.protect(() -> {
            require(request); var value = read(key("stage", storageKey(request)));
            if (!value.present()) throw new IllegalStateException("Original source stage authority is missing");
            return decode(value.content().copy(), maximumBytes, r -> {
                SessionStorageWire.require((FORMAT + "/stage").equals(text(r)), "Invalid source stage format");
                var owners = new ArrayList<SourceHistoryStageContext.Owner>();
                for (int n = r.count(maximumBytes, 5); n > 0; n--) {
                    var id = DocumentId.of(text(r));
                    var previous = r.bool() ? Optional.of(new ProcessingStageContext.Owner(id, r.longValue(), text(r), text(r), r.longValue(), text(r)))
                            : Optional.<ProcessingStageContext.Owner>empty();
                    owners.add(new SourceHistoryStageContext.Owner(id, previous));
                }
                var ids = new ArrayList<String>(); for (int n = r.count(maximumBytes, 4); n > 0; n--) ids.add(text(r));
                return new SourceHistoryStageContext(request.prerequisite(), owners, ids);
            });
        });
    }
    private Value read(Key key) { return pending.containsKey(key) ? new Value(1, pending.get(key)) : context.read(key); }
    private void put(Key key, Bytes bytes) { pending.put(key, bytes); context.select(key, bytes); }
    private void authenticate(SourceHistoryRequest request) {
        request.requestingInstances().forEach(instances::requireRetained);
        if (request.sourceInstance().equals(LogicalDocumentInstances.initialReference(request.sourceInstance().documentId())))
            instances.retainedInitial(request.sourceInstance().documentId());
        else instances.requireRetained(request.sourceInstance());
    }
    static String storageKey(SourceHistoryRequest request) {
        return contextKey(request.requestingInstances(), request.sourceInstance()) + "/" + request.prerequisite().selectionIdentity();
    }
    static String contextKey(List<DocumentInstanceRef> owners, DocumentInstanceRef source) {
        var refs = new ArrayList<>(owners); refs.add(source);
        return "instances:" + blue.coordination.api.storage.CoordinationRecords.sha256(new Bytes(encode(1_000_000, w -> {
            w.integer(refs.size()); refs.forEach(ref -> { w.text(ref.documentId().value()); w.text(ref.instanceId()); });
        }))).hex();
    }
    static String canonicalSelection(String key) { return key.startsWith("instances:") ? key.substring(key.indexOf('/') + 1) : key; }
    private Key requestKey(SourceHistoryRequest request) { return key("contexts", storageKey(request)); }
    private Key originalKey(SourceHistoryPrerequisite descriptor) { return key("original", descriptor.selectionIdentity()); }
    private static Key key(String kind, String id) {
        return new Key(Family.SOURCE_SUBMITTED, new Bytes(OrderedRecordKey.text().encode(FORMAT + "/" + kind)),
                new Bytes(OrderedRecordKey.text().encode(id)));
    }
    private Bytes encodeRequest(SourceHistoryRequest request) {
        return new Bytes(encode(maximumBytes, w -> {
            w.text(FORMAT); codec.descriptor(w, request.prerequisite()); w.integer(request.requestingInstances().size());
            request.requestingInstances().forEach(ref -> reference(w, ref)); reference(w, request.sourceInstance());
        }));
    }
    private SourceHistoryRequest decodeRequest(Bytes bytes) {
        var result = decode(bytes.copy(), maximumBytes, r -> {
            SessionStorageWire.require(FORMAT.equals(text(r)), "Invalid source request format");
            var descriptor = codec.descriptor(r); var owners = new ArrayList<DocumentInstanceRef>();
            for (int n = r.count(maximumBytes, 8); n > 0; n--) owners.add(reference(r));
            return new SourceHistoryRequest(descriptor, owners, reference(r));
        });
        authenticate(result); return result;
    }
    private static void reference(Writer w, DocumentInstanceRef ref) { w.text(ref.documentId().value()); w.text(ref.instanceId()); }
    private static DocumentInstanceRef reference(Reader r) { return new DocumentInstanceRef(DocumentId.of(text(r)), text(r)); }
    private static String text(Reader r) { return r.text(r.remaining()); }
}
