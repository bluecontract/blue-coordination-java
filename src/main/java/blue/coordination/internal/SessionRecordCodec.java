package blue.coordination.internal;

import blue.coordination.api.*;
import blue.language.model.Node;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.closure.ChannelOccurrence;
import blue.language.processor.closure.SubscriptionState;
import blue.language.snapshot.ExactNodeStorageCodec;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.FrozenNodeStorageCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/** Closed rows belonging to a session, not a new processor serialization or execution layer. */
final class SessionRecordCodec {
    private static final String REVISION = "blue-coordination/document-revision-storage/1";
    final int maximumBytes;
    private final int maximumDepth;
    private final ExactValueStorageCodec exactValues;
    private final FrozenNodeStorageCodec frozenNodes;
    private final ExactNodeStorageCodec nodes;
    private final RootedStorageCache cache;
    private final String exactValueFamily;
    private final String revisionFamily;

    SessionRecordCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, null);
    }

    SessionRecordCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache) {
        this.maximumBytes = maximumBytes; this.maximumDepth = maximumDepth;
        this.cache = cache;
        this.exactValueFamily = ExactValueStorageCodec.cacheFamily(maximumBytes, maximumDepth);
        this.revisionFamily = REVISION + "/" + maximumBytes + "/" + maximumDepth;
        exactValues = new ExactValueStorageCodec(maximumBytes, maximumDepth);
        frozenNodes = new FrozenNodeStorageCodec(maximumBytes, maximumDepth);
        nodes = new ExactNodeStorageCodec(maximumBytes, maximumDepth);
    }

    static String text(Reader in) { return in.text(in.remaining()); }
    static String nullableText(Reader in) { return in.nullableText(in.remaining()); }
    static <T> void list(Writer out, Collection<T> rows, BiConsumer<Writer, T> encode) {
        out.integer(rows.size()); rows.forEach(row -> encode.accept(out, Objects.requireNonNull(row)));
    }
    static <T> List<T> list(Reader in, Function<Reader, T> decode) {
        int count = in.count(Integer.MAX_VALUE, 1); var rows = new ArrayList<T>();
        for (int i = 0; i < count; i++) rows.add(Objects.requireNonNull(decode.apply(in)));
        return List.copyOf(rows);
    }
    static <T> void optional(Writer out, T value, BiConsumer<Writer, T> encode) {
        out.bool(value != null); if (value != null) encode.accept(out, value);
    }
    static <T> T optional(Reader in, Function<Reader, T> decode) { return in.bool() ? decode.apply(in) : null; }
    static <T> void map(Writer out, Map<String, T> rows, BiConsumer<Writer, T> encode) {
        out.integer(rows.size()); new TreeMap<>(rows).forEach((key, value) -> { out.text(key); encode.accept(out, value); });
    }
    static <T> Map<String, T> map(Reader in, Function<Reader, T> decode) {
        int count = in.count(Integer.MAX_VALUE, 5); var rows = new LinkedHashMap<String, T>();
        String prior = null;
        for (int i = 0; i < count; i++) {
            String key = text(in); require(prior == null || prior.compareTo(key) < 0, "Unordered or duplicate map key");
            rows.put(key, decode.apply(in)); prior = key;
        }
        return Collections.unmodifiableMap(rows);
    }
    static <T> void orderedMap(Writer out, Map<String, T> rows, BiConsumer<Writer, T> encode) {
        out.integer(rows.size()); rows.forEach((key, value) -> { out.text(key); encode.accept(out, value); });
    }
    static <T> Map<String, T> orderedMap(Reader in, Function<Reader, T> decode) {
        int count = in.count(Integer.MAX_VALUE, 5); var rows = new LinkedHashMap<String, T>();
        for (int i = 0; i < count; i++) {
            String key = text(in); require(!rows.containsKey(key), "Duplicate ordered map key"); rows.put(key, decode.apply(in));
        }
        return Collections.unmodifiableMap(rows);
    }
    static <T> void documents(Writer out, Map<DocumentId, T> rows, BiConsumer<Writer, T> encode) {
        var keys = new TreeMap<String, T>(); rows.forEach((key, value) -> keys.put(key.value(), value)); map(out, keys, encode);
    }
    static <T> Map<DocumentId, T> documents(Reader in, Function<Reader, T> decode) {
        var rows = new LinkedHashMap<DocumentId, T>(); map(in, decode).forEach((key, value) -> rows.put(DocumentId.of(key), value));
        return Collections.unmodifiableMap(rows);
    }
    static void strings(Writer out, Collection<String> rows) { list(out, rows, Writer::text); }
    static List<String> strings(Reader in) { return list(in, SessionRecordCodec::text); }
    static void stringSet(Writer out, Set<String> rows) { strings(out, rows); }
    static Set<String> stringSet(Reader in) {
        List<String> rows = strings(in); var result = new LinkedHashSet<>(rows);
        require(result.size() == rows.size(), "Duplicate exact set member");
        return Collections.unmodifiableSet(result);
    }
    void exact(Writer out, ExactValue value) {
        byte[] certified = cache == null ? null : cache.canonicalEncoding(exactValueFamily, value);
        out.bytes(certified == null ? exactValues.encode(value) : certified);
    }
    ExactValue exact(Reader in) {
        // A complete self-contained frame, not an enclosing session or a proof of current membership.
        // Its first load retains the ordinary codec's full identity, proof and canonical checks.
        byte[] frame = in.bytes(maximumBytes);
        return cache == null ? exactValues.decode(frame)
                : cache.decodeCanonical(exactValueFamily, frame, exactValues::decode, exactValues::encode);
    }
    void frozen(Writer out, FrozenNode value) { out.bytes(frozenNodes.encode(value)); }
    FrozenNode frozen(Reader in) { return frozenNodes.decode(in.bytes(maximumBytes)); }
    void node(Writer out, Node value) { out.bytes(nodes.encode(value)); }
    Node node(Reader in) { return nodes.decode(in.bytes(maximumBytes)); }

    void component(Writer out, blue.language.processor.closure.ComponentSnapshot row) {
        out.text(row.componentIdentity()); out.text(row.componentStateIdentity()); out.longValue(row.componentGeneration()); out.text(row.kind().name());
        list(out, row.orderedMemberDocumentIds(), (w, id) -> w.text(id.value())); strings(out, row.orderedMemberBlueIds());
        out.nullableText(row.masterBlueId()); out.nullableText(row.cyclicProofIdentity());
        optional(out, row.completeCyclicProof(), (w, proof) -> list(w, proof.declaredPlaceholderSet(), this::node));
    }
    blue.language.processor.closure.ComponentSnapshot component(Reader in) {
        String lineage = text(in), state = text(in); long generation = in.longValue();
        var kind = blue.language.processor.closure.ComponentKind.valueOf(text(in));
        var members = list(in, r -> new blue.language.processor.closure.DocumentId(text(r))); var blueIds = strings(in);
        String master = nullableText(in), proofIdentity = nullableText(in);
        var proof = optional(in, r -> blue.language.provider.CyclicSetProof.fromDeclaredPlaceholderSet(list(r, this::node)));
        return new blue.language.processor.closure.ComponentSnapshot(lineage, state, generation, kind, members, blueIds, master, proof, proofIdentity);
    }

    /** Complete immutable revision artifact; never a session or current-membership certificate. */
    byte[] encodeRevision(DocumentRevision row) {
        byte[] certified = cache == null ? null : cache.canonicalEncoding(revisionFamily, row);
        return certified == null ? encodeRevisionRaw(row) : certified;
    }

    DocumentRevision decodeRevision(byte[] frame) {
        return cache == null ? decodeRevisionCanonical(frame)
                : cache.decodeVerifiedCanonical(revisionFamily, frame, this::decodeRevisionCanonical,
                        value -> value, ignored -> 0, ignored -> true);
    }

    private byte[] encodeRevisionRaw(DocumentRevision row) {
        return encode(maximumBytes, out -> { out.text(REVISION); revision(out, row); });
    }

    private DocumentRevision decodeRevisionCanonical(byte[] frame) {
        DocumentRevision decoded = decode(frame, maximumBytes, in -> {
            require(REVISION.equals(text(in)), "Wrong revision storage format");
            return revision(in);
        });
        require(Arrays.equals(frame, encodeRevisionRaw(decoded)), "Noncanonical or incomplete revision record");
        return decoded;
    }

    void revision(Writer out, DocumentRevision row) {
        out.text(row.documentId().value()); out.longValue(row.epoch()); out.longValue(row.rootApplicationOrder());
        out.text(row.kind().name()); optional(out, row.before().orElse(null), this::exact); exact(out, row.after());
        optional(out, row.sourceEntry().orElse(null), this::entry);
        optional(out, row.sourceOrderKey().orElse(null), SessionStorageWire::order);
        out.nullableText(row.causalEntryBlueId().orElse(null));
        optional(out, row.catchUpCause().orElse(null), (w, cause) -> {
            w.text(cause.parentDocumentId().value()); w.text(cause.attachmentEntryBlueId());
            w.text(cause.occurrencePath()); w.longValue(cause.attachmentTimestampMicros());
        });
        list(out, row.emittedEvents(), this::node); out.longValue(row.processingGas());
        optional(out, row.managedEpochReceipt().orElse(null), this::receipt);
    }
    DocumentRevision revision(Reader in) {
        return new DocumentRevision(DocumentId.of(text(in)), in.longValue(), in.longValue(),
                DocumentRevision.Kind.valueOf(text(in)), optional(in, this::exact), exact(in), optional(in, this::entry),
                optional(in, SessionStorageWire::order), nullableText(in), optional(in, r -> new DocumentRevision.CatchUpCause(
                    DocumentId.of(text(r)), text(r), text(r), r.longValue())), list(in, this::node), in.longValue(),
                optional(in, this::receipt));
    }
    void entry(Writer out, TimelineEntry row) {
        exact(out, row.exactEvent()); optional(out, row.request().orElse(null), this::exact);
        order(out, row.journalOrderKey()); order(out, row.sourceOrderKey());
        out.text(row.timeline().timelineId()); out.text(row.timeline().actorId()); out.text(row.operation()); out.text(row.channel());
        out.longValue(row.timestampMicros()); out.longValue(row.globalSequence()); out.longValue(row.timelineSequence());
    }
    TimelineEntry entry(Reader in) {
        return new TimelineEntry(exact(in), Optional.ofNullable(optional(in, this::exact)), order(in), order(in),
                new Timeline(text(in), text(in)), text(in), text(in), in.longValue(), in.longValue(), in.longValue());
    }
    void receipt(Writer out, ManagedEpochReceipt row) {
        out.text(row.receiptIdentity()); out.text(row.documentId().value()); out.longValue(row.epoch()); out.text(row.kind().name());
        out.nullableText(row.beforeBlueId().orElse(null)); exact(out, row.afterDocument()); out.text(row.originalCauseIdentity());
        optional(out, row.sourceEntry().orElse(null), this::entry); optional(out, row.sourceOrder().orElse(null), SessionStorageWire::order);
        out.text(row.contractsTransitionReceiptIdentity()); out.text(row.commitCompanionIdentity());
        list(out, row.emittedEvents(), (w, event) -> {
            w.text(event.managedEventIdentity()); w.longValue(event.ordinal()); w.longValue(event.eventOccurrenceOrdinal());
            w.text(event.sourceDocumentId().value()); w.text(event.eventOccurrenceIdentity()); exact(w, event.exactEvent());
            w.bool(event.publicAtSource());
        });
        out.longValue(row.processingGas());
    }
    ManagedEpochReceipt receipt(Reader in) {
        return new ManagedEpochReceipt(text(in), DocumentId.of(text(in)), in.longValue(), DocumentRevision.Kind.valueOf(text(in)),
                nullableText(in), exact(in), text(in), optional(in, this::entry), optional(in, SessionStorageWire::order), text(in), text(in),
                list(in, r -> new ManagedEventOccurrence(text(r), r.longValue(), r.longValue(), DocumentId.of(text(r)),
                        text(r), exact(r), r.bool())), in.longValue());
    }

    void layout(Writer out, EmbeddedOnlyLayout value) {
        exact(out, value.semanticRoot()); frozen(out, value.processingFrozen());
        var shells = new LinkedHashMap<String, ExactValue>(); value.scopePaths().forEach(path -> shells.put(path, value.stored(path)));
        orderedMap(out, shells, this::exact);
        list(out, value.boundaries(), (w, row) -> {
            w.text(row.parentScopePath()); w.text(row.childScopePath()); w.text(row.childBlueId());
            w.text(row.origin().name()); w.bool(row.physicalCutCreated());
        });
        list(out, value.directOccurrences(), (w, row) -> { w.text(row.scopePath()); w.text(row.childDocumentId().value()); exact(w, row.suppliedState()); });
        EmbeddedLayoutPlan plan = value.plan();
        orderedMap(out, plan.storedIdentities(), (w, row) -> { w.text(row.typeBlueId()); w.text(row.contractsBlueId()); });
        routing(out, plan.routingSurface());
        orderedMap(out, plan.rulesByScope(), (w, row) -> { w.text(row.scopePath()); strings(w, row.explicitAbsolutePaths()); strings(w, row.collectionAbsolutePaths()); });
        list(out, plan.collectionAudits(), (w, row) -> {
            w.text(row.collectionPath()); w.text(row.state().name()); w.integer(row.currentMemberCount()); w.text(row.detail());
        });
    }
    EmbeddedOnlyLayout layout(Reader in) {
        ExactValue semantic = exact(in); FrozenNode processing = frozen(in); var shells = orderedMap(in, this::exact);
        var boundaries = list(in, r -> new EmbeddedBoundary(text(r), text(r), text(r), EmbeddedScopePlanView.Origin.valueOf(text(r)), r.bool()));
        var occurrences = list(in, r -> new EmbeddedOccurrence(text(r), DocumentId.of(text(r)), exact(r)));
        var identities = orderedMap(in, r -> new EmbeddedLayoutPlan.AuthoredScopeIdentity(text(r), text(r)));
        RoutingSurface routes = routing(in);
        var rules = orderedMap(in, r -> new EmbeddedLayoutPlan.ScopeRule(text(r), strings(r), strings(r)));
        var audits = list(in, r -> new EmbeddedCollectionPlanningAudit(text(r), EmbeddedCollectionPlanningAudit.State.valueOf(text(r)), r.integer(), text(r)));
        return new EmbeddedOnlyLayout(semantic, processing, shells, boundaries, occurrences, new EmbeddedLayoutPlan(identities, routes, rules, audits));
    }
    private void sources(Writer out, List<RoutingSurface.SourceAddress> values) {
        list(out, values, (w, source) -> { w.text(source.timelineId()); w.text(source.actorId()); });
    }
    private List<RoutingSurface.SourceAddress> sources(Reader in) { return list(in, r -> new RoutingSurface.SourceAddress(text(r), text(r))); }
    private void routing(Writer out, RoutingSurface routes) {
        list(out, routes.definitions(), (w, row) -> { w.text(row.scopePath()); w.text(row.operation()); w.text(row.channelKey()); sources(w, row.sources()); });
        list(out, routes.operationDefinitions(), (w, row) -> {
            w.text(row.scopePath()); w.text(row.operation()); w.text(row.channelKey()); optional(w, row.requestPattern(), this::frozen); sources(w, row.sources());
        });
        out.bool(routes.deliversEmbeddedRevisionEvents());
    }
    private RoutingSurface routing(Reader in) {
        return new RoutingSurface(list(in, r -> new RoutingSurface.Definition(text(r), text(r), text(r), sources(r))),
                list(in, r -> new RoutingSurface.OperationDefinition(text(r), text(r), text(r), optional(r, this::frozen), sources(r))), in.bool());
    }

    void subscription(Writer out, SubscriptionDelta.Entry row) {
        out.text(row.scopePath()); out.text(row.channelKey()); out.text(row.effectiveTypeBlueId()); strings(out, row.sourceContributionNodeBlueIds());
        out.integer(row.order()); strings(out, row.subscriptionKeys()); out.text(row.checkpointDomainBlueId()); dependencies(out, row.dependencies());
        optional(out, row.activationRootRevision(), Writer::longValue); optional(out, row.startAfterExternalOrderKey(), SessionStorageWire::order);
        optional(out, row.endAtRootRevision(), Writer::longValue);
    }
    SubscriptionDelta.Entry subscription(Reader in) {
        return new SubscriptionDelta.Entry(text(in), text(in), text(in), strings(in), in.integer(), strings(in), text(in), dependencies(in),
                optional(in, Reader::longValue), optional(in, SessionStorageWire::order), optional(in, Reader::longValue));
    }
    private void dependencies(Writer out, ExternalChannelDependencySnapshot value) {
        strings(out, value.intrinsicNodeBlueIds());
        list(out, value.entries(), (w, row) -> {
            w.text(row.channelKey()); w.integer(row.order()); w.text(row.effectiveTypeBlueId()); strings(w, row.sourceContributionNodeBlueIds());
            strings(w, row.deterministicDependencyNodeBlueIds()); w.text(row.checkpointDomainBlueId());
        });
        list(out, value.typeFamilies(), (w, row) -> {
            w.text(row.excludingChannelKey()); w.text(row.effectiveTypeBlueId()); w.text(row.matchMode().name());
            list(w, row.members(), (x, member) -> {
                x.text(member.channelKey()); x.integer(member.order()); x.nullableText(member.effectiveTypeBlueId());
                strings(x, member.sourceContributionNodeBlueIds()); strings(x, member.deterministicDependencyNodeBlueIds());
            });
        });
        out.bool(value.wholeSameScopeExternalSurface());
        list(out, value.channelEntries(), (w, row) -> {
            w.text(row.channelKey()); w.integer(row.order()); w.text(row.effectiveTypeBlueId()); w.text(row.role());
            strings(w, row.sourceContributionNodeBlueIds()); strings(w, row.deterministicDependencyNodeBlueIds()); w.text(row.headerIdentityBlueId());
        });
        out.bool(value.wholeSameScopeChannelCatalog()); strings(out, value.channelCatalogContractKeys());
    }
    private ExternalChannelDependencySnapshot dependencies(Reader in) {
        return new ExternalChannelDependencySnapshot(strings(in),
                list(in, r -> new ExternalChannelDependencySnapshot.Entry(text(r), r.integer(), text(r), strings(r), strings(r), text(r))),
                list(in, r -> new ExternalChannelDependencySnapshot.TypeFamily(text(r), text(r), ExternalChannelDependencySnapshot.TypeMatchMode.valueOf(text(r)),
                    list(r, x -> new ExternalChannelDependencySnapshot.Member(text(x), x.integer(), nullableText(x), strings(x), strings(x))))), in.bool(),
                list(in, r -> new ExternalChannelDependencySnapshot.ChannelEntry(text(r), r.integer(), text(r), text(r), strings(r), strings(r), text(r))),
                in.bool(), strings(in));
    }
    void inventory(Writer out, ClosureSubscriptionInventory value) {
        var state = value.storedState();
        list(out, state.states(), this::closureSubscription);
        documents(out, state.demands(), (w, rows) -> list(w, rows, (x, row) -> {
            x.text(row.rawChannelKey()); x.text(row.selectorPath()); x.text(row.mode().name()); x.text(row.effectiveRuntimeContributionBlueId());
        }));
        out.integer(state.comparisons()); out.integer(state.copiedNodes()); out.integer(state.visitedRows());
    }
    ClosureSubscriptionInventory inventory(Reader in) {
        var states = list(in, this::closureSubscription);
        var demands = documents(in, r -> list(r, x -> new ClosureSubscriptionInventory.EmbeddedDemand(text(x), text(x),
                ClosureSubscriptionInventory.EmbeddedDemandMode.valueOf(text(x)), text(x))));
        return ClosureSubscriptionInventory.restoreStored(new ClosureSubscriptionInventory.StoredState(states, demands, in.integer(), in.integer(), in.integer()));
    }

    void closureSubscription(Writer out, SubscriptionState row) {
        out.text(row.subscriptionIdentity()); var channel = row.channelOccurrence();
        out.text(channel.channelOccurrenceIdentity()); out.text(channel.managedDocumentId().value()); out.text(channel.scopePath());
        out.longValue(channel.scopeActivationGeneration()); out.text(channel.rawChannelKey()); out.text(channel.effectiveRuntimeContributionBlueId());
        out.text(channel.subscriptionHeaderBlueId()); out.text(row.documentBlueId()); out.longValue(row.graphGeneration()); out.longValue(row.componentGeneration());
    }

    SubscriptionState closureSubscription(Reader in) {
        return new SubscriptionState(text(in), new ChannelOccurrence(text(in),
                new blue.language.processor.closure.DocumentId(text(in)), text(in), in.longValue(), text(in), text(in), text(in)),
                text(in), in.longValue(), in.longValue());
    }

    void history(Writer out, RootedDocumentHistory history, Function<RootedDocumentView, String> address) {
        value(out, history.descriptor()); out.text(history.identity()); out.text(history.admissionInvocationIdentity());
        out.text(history.admissionCompanionIdentity());
        optional(out, history.admissionSources().storedBoundary(), SessionStorageWire::order);
        documents(out, history.admissionSources().storedViews(), (w, view) -> w.text(address.apply(view)));
    }

    @SuppressWarnings("unchecked")
    RootedDocumentHistory history(Reader in, Function<String, RootedDocumentView> view) {
        Object descriptor = value(in); require(descriptor instanceof Map<?, ?>, "History descriptor is not an exact map");
        return new RootedDocumentHistory((Map<String, Object>) descriptor, text(in), text(in), text(in),
                RootedAdmissionSources.restoreStored(optional(in, SessionStorageWire::order),
                        documents(in, r -> view.apply(text(r))), (Map<String, Object>) descriptor));
    }

    void value(Writer out, Object value) { value(out, value, 0); }
    private void value(Writer out, Object value, int depth) {
        require(depth <= maximumDepth, "Descriptor storage traversal bound exceeded");
        if (value == null) out.integer(0);
        else if (value instanceof String text) { out.integer(1); out.text(text); }
        else if (value instanceof Boolean bool) { out.integer(2); out.bool(bool); }
        else if (value instanceof Integer integer) { out.integer(3); out.integer(integer); }
        else if (value instanceof Long number) { out.integer(4); out.longValue(number); }
        else if (value instanceof BigInteger number) { out.integer(5); out.text(number.toString()); }
        else if (value instanceof BigDecimal number) { out.integer(6); out.text(number.toString()); }
        else if (value instanceof List<?> rows) { out.integer(7); list(out, rows, (w, row) -> value(w, row, depth + 1)); }
        else if (value instanceof Map<?, ?> rows) {
            out.integer(8); var checked = new LinkedHashMap<String, Object>();
            rows.forEach((key, row) -> { require(key instanceof String, "Descriptor key is not text"); checked.put((String) key, row); });
            map(out, checked, (w, row) -> value(w, row, depth + 1));
        } else throw new IllegalArgumentException("Unsupported exact descriptor scalar");
    }
    Object value(Reader in) { return value(in, 0); }
    private Object value(Reader in, int depth) {
        require(depth <= maximumDepth, "Descriptor storage traversal bound exceeded");
        return switch (in.integer()) {
            case 0 -> null; case 1 -> text(in); case 2 -> in.bool(); case 3 -> in.integer(); case 4 -> in.longValue();
            case 5 -> new BigInteger(text(in)); case 6 -> new BigDecimal(text(in));
            case 7 -> list(in, r -> value(r, depth + 1)); case 8 -> map(in, r -> value(r, depth + 1));
            default -> throw new IllegalArgumentException("Unknown descriptor value kind");
        };
    }
}
