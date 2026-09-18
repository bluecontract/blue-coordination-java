package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureResourceDemand;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.BiConsumer;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Original terminal publications and feeder rejections; no current-state recapture or processor execution. */
final class PublicationReceiptStorageCodec {
    private static final String PUBLICATION = "blue-coordination/publication-receipt-storage/1";
    private static final String REJECTION = "blue-coordination/declared-birth-rejection-storage/1";
    private final int maximumBytes;
    private final SessionRecordCodec rows;
    private final CoreReceiptStorageCodec core;
    private final CohortInvocationStorageCodec cohorts;
    private final OperationPlanStorageCodec plans;
    private final ManagedWorkStorageCodec works;
    private final ClosureExecutionEvidenceStorageCodec evidence;
    private final BiConsumer<String, Object> frameEncoded;

    PublicationReceiptStorageCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, null);
    }

    PublicationReceiptStorageCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache) {
        this(maximumBytes, maximumDepth, cache, (kind, value) -> { });
    }

    /** Package-local measurement of actual full frames, not nested result/snapshot counts. */
    PublicationReceiptStorageCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache,
            BiConsumer<String, Object> frameEncoded) {
        this.maximumBytes = maximumBytes;
        rows = new SessionRecordCodec(maximumBytes, maximumDepth);
        core = new CoreReceiptStorageCodec(maximumBytes, maximumDepth, cache);
        cohorts = new CohortInvocationStorageCodec(maximumBytes, maximumDepth, cache);
        plans = new OperationPlanStorageCodec(maximumBytes, maximumDepth);
        works = new ManagedWorkStorageCodec(maximumBytes, maximumDepth, cache);
        evidence = new ClosureExecutionEvidenceStorageCodec(maximumBytes, maximumDepth,
                new StoredClosureResultCodec(maximumBytes, maximumDepth, cache).configured());
        this.frameEncoded = Objects.requireNonNull(frameEncoded);
    }

    byte[] encodePublication(ContractsClosurePublicationReceipt value, Function<RootedDocumentView, String> retainView) {
        return encodePublication(value, retainView, new PublicationFrames());
    }

    private byte[] encodePublication(ContractsClosurePublicationReceipt value,
            Function<RootedDocumentView, String> retainView, PublicationFrames frames) {
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(PUBLICATION); out.text(value.publicationIdentity()); list(out, value.documentIds(), (w, id) -> w.text(id.value()));
            core.attempt(out, value.attempt()); out.longValue(value.automaticRetryCount()); surface(out, value.managedSurfaceEvidence(), frames);
            optional(out, value.rootedTerminalEvidence(), (w, terminal) -> terminal(w, terminal, retainView, frames));
            out.bool(value.rejectedDraftPlan() != null);
            if (value.rejectedDraftPlan() != null) {
                if (value.rootedTerminalEvidence() == null) plans.draftPlan(out, value.rejectedDraftPlan());
                else require(value.rejectedDraftPlan() == value.rootedTerminalEvidence().storedState().managedDraftPlan(),
                        "Rejected publication lost its original shared plan");
            }
        });
    }

    ContractsClosurePublicationReceipt decodePublication(byte[] bytes, DocumentSessionStorage.OpenScope scope) {
        return physical(() -> {
            var frames = new PublicationInputs();
            var value = SessionStorageWire.decode(bytes, maximumBytes, in -> {
                require(PUBLICATION.equals(text(in)), "Wrong publication receipt format");
                String identity = text(in); var members = list(in, r -> DocumentId.of(text(r)));
                var attempt = core.attempt(in); long retries = in.longValue(); var surface = surface(in, frames);
                var terminal = optional(in, r -> terminal(r, scope, frames));
                var rejected = in.bool() ? terminal == null ? plans.draftPlan(in) : terminal.storedState().managedDraftPlan() : null;
                return new ContractsClosurePublicationReceipt(identity, members, attempt, retries, surface, rejected, terminal);
            });
            require(Arrays.equals(bytes, encodePublication(value, scope::addressOf, frames.canonical)),
                    "Noncanonical publication receipt"); return value;
        });
    }

    byte[] encodeRejection(RootedDeclaredBirthRejection value, Function<RootedDocumentView, String> retainView) {
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(REJECTION); var state = value.storedState();
            require(state.selected().managedDraftPlan() == state.executed().managedDraftPlan(), "Rejection changed shared plan identity");
            out.bytes(cohorts.encode(state.selected(), retainView));
            out.bytes(cohorts.encodeAttempt(state.executed(), state.attempt(), null, retainView));
            list(out, state.issues(), (w, issue) -> {
                w.integer(position(state.attempt().resourceDemands(), issue.demand()));
                w.text(issue.status().name()); w.text(issue.diagnostic());
            });
            out.longValue(state.retries());
        });
    }

    RootedDeclaredBirthRejection decodeRejection(byte[] bytes, DocumentSessionStorage.OpenScope scope) {
        return physical(() -> {
            var value = SessionStorageWire.decode(bytes, maximumBytes, in -> {
                require(REJECTION.equals(text(in)), "Wrong declared rejection format");
                var selected = cohorts.decode(in.bytes(maximumBytes), scope);
                var executed = cohorts.decodeAttempt(in.bytes(maximumBytes), scope);
                var plan = Objects.requireNonNull(executed.invocation().managedDraftPlan(), "Missing rejected birth plan");
                require(Arrays.equals(plan(selected.managedDraftPlan()), plan(plan)), "Rejection cohorts retained different plans");
                // One packet-local original plan alias, after exact byte equality. Never a global interner.
                var shared = withPlan(selected, plan);
                var issues = list(in, r -> new ManagedOccurrenceResolver.UnresolvedDemand(
                        select(executed.attempt().resourceDemands(), r.integer()),
                        ManagedOccurrenceResolver.ResolutionStatus.valueOf(text(r)), text(r)));
                return RootedDeclaredBirthRejection.restoreStored(new RootedDeclaredBirthRejection.StoredState(
                        shared, executed.invocation(), executed.attempt(), issues, in.longValue()));
            });
            require(Arrays.equals(bytes, encodeRejection(value, scope::addressOf)), "Noncanonical declared birth rejection"); return value;
        });
    }

    private byte[] plan(ContractsManagedDraftPlan plan) {
        return SessionStorageWire.encode(maximumBytes, out -> plans.draftPlan(out, Objects.requireNonNull(plan)));
    }
    private ContractsClosureAdapter.CohortInvocation withPlan(ContractsClosureAdapter.CohortInvocation value, ContractsManagedDraftPlan plan) {
        return new ContractsClosureAdapter.CohortInvocation(value.members(), value.directDeliveries(), value.input(), value.retryInput(),
                value.documents(), plan, value.automaticExpansion(), value.publicationIdentityMembers(), value.publicationIdentityPublicRoots(),
                value.rootedAnchor(), value.rootedEvidence());
    }
    private int position(List<ClosureResourceDemand> demands, ClosureResourceDemand selected) {
        int position = -1;
        for (int i = 0; i < demands.size(); i++) if (demands.get(i) == selected) {
            require(position == -1, "Repeated identical retained demand"); position = i;
        }
        require(position >= 0, "Rejected issue is not the original attempt demand object"); return position;
    }
    private ClosureResourceDemand select(List<ClosureResourceDemand> demands, int position) {
        require(position >= 0 && position < demands.size(), "Rejected issue points outside its original attempt"); return demands.get(position);
    }

    private void terminal(Writer out, RootedTerminalEvidence value, Function<RootedDocumentView, String> retainView,
            PublicationFrames frames) {
        var state = value.storedState(); out.bytes(frames.invocation(state.input()));
        optional(out, state.managedDraftPlan(), plans::draftPlan); cohorts.rooted(out, state.rooted(), retainView);
        out.text(state.executedInvocationIdentity()); out.nullableText(state.historicalWorkIdentity());
        optional(out, state.historicalWork(), works::work); strings(out, new TreeSet<>(state.requiredTimelineIds()));
    }
    private RootedTerminalEvidence terminal(Reader in, DocumentSessionStorage.OpenScope scope, PublicationInputs frames) {
        var input = frames.invocation(in.bytes(maximumBytes)); var plan = optional(in, plans::draftPlan);
        var rooted = cohorts.rooted(in, input, scope::view);
        return RootedTerminalEvidence.restoreStored(new RootedTerminalEvidence.StoredState(input, plan, rooted,
                text(in), nullableText(in), optional(in, works::work), stringSet(in)));
    }

    private void surface(Writer out, ManagedSurfacePublicationEvidence value, PublicationFrames frames) {
        list(out, value.resolvedOccurrences(), (w, row) -> {
            w.text(row.demandIdentity()); StoreIndexCodecs.occurrence(w, row.occurrence()); w.text(row.targetKind().name());
            optional(w, row.authoredInitial(), rows::exact);
        });
        list(out, value.inputComponents(), rows::component); list(out, value.operationRouteChanges(), this::routeChange);
        optional(out, value.originalInvocation(), (w, input) -> w.bytes(frames.invocation(input)));
    }

    /**
     * One synchronous envelope capture only: the same original input appears in
     * multiple receipt roles. Keep its first complete frame, never a proof or an object
     * identity cache across calls. Equal-but-distinct evidence is encoded independently;
     * cold readers still validate every occurrence and the complete canonical envelope.
     */
    private final class PublicationFrames {
        private final IdentityHashMap<ClosureInvocationInput, byte[]> inputs = new IdentityHashMap<>();

        byte[] invocation(ClosureInvocationInput input) {
            return inputs.computeIfAbsent(input, value -> {
                byte[] bytes = evidence.encodeInvocation(value);
                frameEncoded.accept("invocation-encode", value);
                return bytes;
            });
        }
    }
    /** Exact packet bytes only; the first decoder still verifies its complete private input. */
    private final class PublicationInputs {
        private byte[] firstFrame;
        private ClosureInvocationInput firstInput;
        private final PublicationFrames canonical = new PublicationFrames();

        ClosureInvocationInput invocation(byte[] bytes) {
            if (firstFrame != null && Arrays.equals(firstFrame, bytes)) return firstInput;
            var input = evidence.decodeInvocation(bytes);
            // The complete Language invocation decoder already performed its own
            // exact canonical byte comparison. Preserve that private frame for
            // this enclosing receipt's comparison only; do not encode it again.
            canonical.inputs.put(input, bytes.clone());
            frameEncoded.accept("invocation-decode", input);
            if (firstFrame == null) { firstFrame = bytes; firstInput = input; }
            return input;
        }
    }

    private ManagedSurfacePublicationEvidence surface(Reader in, PublicationInputs frames) {
        return new ManagedSurfacePublicationEvidence(list(in, r -> new ManagedSurfacePublicationEvidence.ResolvedOccurrence(
                text(r), StoreIndexCodecs.occurrence(r), ManagedOccurrenceResolver.TargetKind.valueOf(text(r)), optional(r, rows::exact))),
                list(in, rows::component), list(in, this::routeChange), optional(in, r -> frames.invocation(r.bytes(maximumBytes))));
    }

    private void routeChange(Writer out, OperationRouteIndex.OperationRouteChange value) {
        out.text(value.kind().name()); out.text(value.documentId().value());
        optional(out, value.before().orElse(null), this::route); optional(out, value.after().orElse(null), this::route);
    }
    private OperationRouteIndex.OperationRouteChange routeChange(Reader in) {
        return new OperationRouteIndex.OperationRouteChange(OperationRouteIndex.OperationRouteChangeKind.valueOf(text(in)), DocumentId.of(text(in)),
                java.util.Optional.ofNullable(optional(in, this::route)), java.util.Optional.ofNullable(optional(in, this::route)));
    }
    private void route(Writer out, OperationRouteIndex.OperationRouteState value) {
        out.text(value.scopePath()); out.text(value.operation()); out.text(value.channel());
        list(out, value.acceptedSources(), (w, source) -> { w.text(source.timelineId()); w.text(source.actorId()); });
    }
    private OperationRouteIndex.OperationRouteState route(Reader in) {
        return new OperationRouteIndex.OperationRouteState(text(in), text(in), text(in),
                list(in, r -> new RoutingSurface.SourceAddress(text(r), text(r))));
    }
}
