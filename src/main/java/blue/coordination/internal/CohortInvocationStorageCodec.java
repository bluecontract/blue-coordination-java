package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureResourceDemand;
import blue.language.processor.closure.ManagedOccurrenceEvidenceDemand;
import blue.language.processor.closure.ManagedRevisionCause;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Exact captured host cohort; no recapture, provider resolution or parent execution at restoration. */
final class CohortInvocationStorageCodec {
    private static final String FORMAT = "blue-coordination/cohort-invocation-storage/1";
    private static final String ATTEMPT = "blue-coordination/cohort-attempt-storage/1";
    private final int maximumBytes;
    private final SessionRecordCodec rows;
    private final OperationPlanStorageCodec plans;
    private final ManagedWorkStorageCodec works;
    private final ClosureExecutionEvidenceStorageCodec evidence;

    CohortInvocationStorageCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, null);
    }

    CohortInvocationStorageCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache) {
        this.maximumBytes = maximumBytes;
        rows = new SessionRecordCodec(maximumBytes, maximumDepth);
        plans = new OperationPlanStorageCodec(maximumBytes, maximumDepth);
        works = new ManagedWorkStorageCodec(maximumBytes, maximumDepth, cache);
        evidence = new ClosureExecutionEvidenceStorageCodec(maximumBytes, maximumDepth,
                new StoredClosureResultCodec(maximumBytes, maximumDepth, cache).configured());
    }

    byte[] encode(ContractsClosureAdapter.CohortInvocation value, Function<RootedDocumentView, String> viewAddress) {
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(FORMAT); invocation(out, Objects.requireNonNull(value), Objects.requireNonNull(viewAddress));
        });
    }

    ContractsClosureAdapter.CohortInvocation decode(byte[] bytes, DocumentSessionStorage.OpenScope scope) {
        return physical(() -> {
            var value = SessionStorageWire.decode(bytes, maximumBytes, in -> {
                require(FORMAT.equals(text(in)), "Wrong cohort invocation format"); return invocation(in, scope::view);
            });
            require(Arrays.equals(bytes, encode(value, scope::addressOf)), "Noncanonical captured cohort");
            return value;
        });
    }

    record StoredAttempt(ContractsClosureAdapter.CohortInvocation invocation, ClosureAttemptResult attempt,
            ClosureResourceDemand selectedDemand) { }

    byte[] encodeAttempt(ContractsClosureAdapter.CohortInvocation invocation, ClosureAttemptResult attempt,
            ClosureResourceDemand selectedDemand, Function<RootedDocumentView, String> viewAddress) {
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(ATTEMPT); out.bytes(encode(invocation, viewAddress));
            out.bytes(evidence.encodeAttempt(invocation.input(), invocation.retryInput(), attempt, selectedDemand));
        });
    }

    StoredAttempt decodeAttempt(byte[] bytes, DocumentSessionStorage.OpenScope scope) {
        return physical(() -> {
            var value = SessionStorageWire.decode(bytes, maximumBytes, in -> {
                require(ATTEMPT.equals(text(in)), "Wrong cohort attempt format");
                var invocation = decode(in.bytes(maximumBytes), scope);
                var attempt = evidence.decodeAttempt(in.bytes(maximumBytes));
                require(Arrays.equals(evidence.encodeInvocation(invocation.input()), evidence.encodeInvocation(attempt.input())),
                        "Cohort attempt changed its original input");
                require((invocation.retryInput() == null) == (attempt.retry() == null), "Cohort attempt changed retry presence");
                if (attempt.retry() != null) require(Arrays.equals(evidence.encodeRetry(invocation.retryInput()), evidence.encodeRetry(attempt.retry())),
                        "Cohort attempt changed its prepared retry");
                return new StoredAttempt(invocation, attempt.attempt(), attempt.selectedDemand());
            });
            require(Arrays.equals(bytes, encodeAttempt(value.invocation(), value.attempt(), value.selectedDemand(), scope::addressOf)),
                    "Noncanonical captured cohort attempt");
            return value;
        });
    }

    private void invocation(Writer out, ContractsClosureAdapter.CohortInvocation value,
            Function<RootedDocumentView, String> viewAddress) {
        requireAssociation(value);
        out.bytes(evidence.encodeInvocation(value.input()));
        optional(out, value.retryInput(), (w, retry) -> w.bytes(evidence.encodeRetry(retry)));
        list(out, value.members(), (w, id) -> w.text(id.value()));
        list(out, value.directDeliveries(), (w, delivery) -> {
            int selected = -1;
            for (int i = 0; i < value.input().directDeliveries().size(); i++)
                if (delivery.compareTo(value.input().directDeliveries().get(i)) == 0) {
                    require(selected == -1, "Ambiguous captured direct delivery"); selected = i;
                }
            require(selected >= 0, "Captured delivery is absent from exact input"); w.integer(selected);
        });
        documents(out, value.documents(), this::captured);
        optional(out, value.managedDraftPlan(), plans::draftPlan);
        optional(out, value.automaticExpansion(), this::expansion);
        list(out, value.publicationIdentityMembers(), (w, id) -> w.text(id.value()));
        list(out, value.publicationIdentityPublicRoots(), (w, id) -> w.text(id.value()));
        optional(out, value.rootedAnchor(), (w, id) -> w.text(id.value()));
        optional(out, value.rootedEvidence(), (w, rooted) -> rooted(w, rooted, viewAddress));
    }

    private ContractsClosureAdapter.CohortInvocation invocation(Reader in, Function<String, RootedDocumentView> view) {
        var input = evidence.decodeInvocation(in.bytes(maximumBytes));
        var retry = optional(in, r -> evidence.decodeRetry(r.bytes(maximumBytes)));
        var members = list(in, r -> DocumentId.of(text(r)));
        var deliveries = list(in, r -> {
            int index = r.integer(); require(index >= 0 && index < input.directDeliveries().size(), "Invalid captured delivery position");
            return input.directDeliveries().get(index);
        });
        var captured = documents(in, this::captured);
        var draft = optional(in, plans::draftPlan);
        var expansion = optional(in, this::expansion);
        var publicationMembers = list(in, r -> DocumentId.of(text(r)));
        var publicationRoots = list(in, r -> DocumentId.of(text(r)));
        var anchor = optional(in, r -> DocumentId.of(text(r)));
        var rooted = optional(in, r -> rooted(r, input, view));
        var value = new ContractsClosureAdapter.CohortInvocation(members, deliveries, input, retry, captured, draft,
                expansion, publicationMembers, publicationRoots, anchor, rooted);
        requireAssociation(value); return value;
    }

    private void requireAssociation(ContractsClosureAdapter.CohortInvocation value) {
        if (value.retryInput() != null) require(Arrays.equals(evidence.encodeInvocation(value.input()),
                evidence.encodeInvocation(value.retryInput().baseInvocation())), "Cohort retry has another exact base input");
        var binding = evidence.originalRootedBinding(value.input());
        var rooted = value.rootedEvidence();
        require((binding == null) == (rooted == null), "Cohort omitted or invented original rooted evidence");
        if (rooted != null) {
            require(binding.context().identity().equals(rooted.context().identity())
                    && binding.deliveryBasisIdentity().equals(rooted.deliveryBasisIdentity())
                    && binding.entryInvocationIdentity().equals(rooted.baseInvocationIdentity()), "Cohort changed its original rooted association");
            if (rooted.historicalWork() != null) {
                require(rooted.historicalOrigin() != null, "Local historical work lost its captured view");
                var work = rooted.historicalWork();
                work.representationCause().ifPresent(cause -> require(Arrays.equals(evidence.encodeProcessingCause(cause),
                        evidence.encodeProcessingCause(value.input().cause())), "Historical work differs from executed representation cause"));
                work.successorRepresentationCause().ifPresent(cause -> {
                    require(value.input().cause() instanceof ManagedRevisionCause, "Successor work has no numbered cause");
                    var actual = ((ManagedRevisionCause) value.input().cause()).successorRepresentationCause().orElseThrow();
                    require(Arrays.equals(evidence.encodeProcessingCause(cause), evidence.encodeProcessingCause(actual)), "Historical work changed captured successor");
                });
            }
        }
        value.documents().forEach((id, document) -> require(id.equals(document.documentId()), "Captured document stored under another key"));
    }

    void captured(Writer out, ContractsClosureAdapter.CapturedDocument value) {
        out.text(value.documentId().value()); out.longValue(value.head().epoch()); out.text(value.head().blueId());
        out.longValue(value.graphGeneration()); rows.exact(out, value.current()); rows.layout(out, value.layout());
        list(out, value.activeSubscriptions(), rows::subscription); out.longValue(value.nextApplicationOrder());
        out.bool(value.initialized()); out.bool(value.terminated()); list(out, value.closureSubscriptions(), rows::closureSubscription);
    }

    ContractsClosureAdapter.CapturedDocument captured(Reader in) {
        return new ContractsClosureAdapter.CapturedDocument(DocumentId.of(text(in)),
                new InMemoryDocumentStore.DocumentHead(in.longValue(), text(in)), in.longValue(), rows.exact(in), rows.layout(in),
                list(in, rows::subscription), in.longValue(), in.bool(), in.bool(), list(in, rows::closureSubscription));
    }

    private void expansion(Writer out, AutomaticManagedOccurrenceExpansion value) {
        documents(out, value.drafts(), plans::draft);
        list(out, value.occurrences(), (w, occurrence) -> {
            w.bytes(evidence.encodeResourceDemand(occurrence.demand())); w.text(occurrence.targetDocumentId().value());
            w.text(occurrence.expectedTargetBlueId()); w.text(occurrence.targetKind().name()); w.longValue(occurrence.admittedSourceEpoch());
            optional(w, occurrence.newDraft(), plans::draft);
        });
        stringSet(out, value.prospectiveOccurrenceIdentities());
    }

    private AutomaticManagedOccurrenceExpansion expansion(Reader in) {
        var drafts = documents(in, plans::draft);
        var occurrences = list(in, r -> {
            var demand = evidence.decodeResourceDemand(r.bytes(maximumBytes));
            require(demand instanceof ManagedOccurrenceEvidenceDemand, "Resolved occurrence lacks managed demand evidence");
            return new ManagedOccurrenceResolver.ResolvedOccurrence((ManagedOccurrenceEvidenceDemand) demand, DocumentId.of(text(r)), text(r),
                    ManagedOccurrenceResolver.TargetKind.valueOf(text(r)), r.longValue(), optional(r, plans::draft));
        });
        return new AutomaticManagedOccurrenceExpansion(drafts, occurrences, stringSet(in));
    }

    void rooted(Writer out, RootedInvocationEvidence value, Function<RootedDocumentView, String> viewAddress) {
        out.text(value.context().identity()); out.text(value.deliveryBasisIdentity()); out.text(value.invocationIdentity());
        out.text(value.baseInvocationIdentity()); documents(out, value.histories(), (w, history) -> rows.history(w, history, viewAddress));
        optional(out, value.historicalOrigin(), (w, view) -> w.text(viewAddress.apply(view)));
        optional(out, value.historicalWork(), works::work);
        documents(out, value.publicationFences(), (w, fence) -> {
            w.longValue(fence.head().epoch()); w.text(fence.head().blueId()); w.longValue(fence.graphGeneration());
        });
    }

    RootedInvocationEvidence rooted(Reader in, ClosureInvocationInput input, Function<String, RootedDocumentView> view) {
        String contextIdentity = text(in), delivery = text(in), invocation = text(in), base = text(in);
        var binding = evidence.originalRootedBinding(input);
        require(binding != null && contextIdentity.equals(binding.context().identity())
                && delivery.equals(binding.deliveryBasisIdentity()) && base.equals(binding.entryInvocationIdentity()),
                "Stored rooted envelope differs from original private input binding");
        return new RootedInvocationEvidence(binding.context(), delivery, invocation, base,
                documents(in, r -> rows.history(r, view)), optional(in, r -> view.apply(text(r))), optional(in, works::work),
                documents(in, r -> new RootedInvocationEvidence.PublicationFence(
                        new InMemoryDocumentStore.DocumentHead(r.longValue(), text(r)), r.longValue())));
    }
}
