package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ChannelOccurrence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.DirectLogicalDelivery;
import blue.language.processor.closure.RootedProcessingContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen draft.2 context derived from durable admission facts and exact input channels. */
record RootedInvocationEvidence(RootedProcessingContext context, String deliveryBasisIdentity,
        String invocationIdentity, String baseInvocationIdentity,
        Map<DocumentId, RootedDocumentHistory> histories, RootedDocumentView historicalOrigin,
        blue.coordination.api.ManagedEpochApplicationWork historicalWork, Map<DocumentId, PublicationFence> publicationFences) {
    RootedInvocationEvidence(RootedProcessingContext context, String deliveryBasisIdentity,
            String invocationIdentity, String baseInvocationIdentity, Map<DocumentId, RootedDocumentHistory> histories) {
        this(context, deliveryBasisIdentity, invocationIdentity, baseInvocationIdentity, histories, null, null, Map.of());
    }

    RootedInvocationEvidence {
        Objects.requireNonNull(context, "context");
        histories = Map.copyOf(histories);
        publicationFences = Map.copyOf(publicationFences);
        if (!context.invocationIdentity(baseInvocationIdentity, deliveryBasisIdentity).equals(invocationIdentity)) {
            throw new IllegalArgumentException("Rooted invocation envelope does not bind its exact base input");
        }
    }

    static RootedInvocationEvidence live(DocumentId anchor, ClosureInvocationInput input,
            InMemoryDocumentStore documents, ClosureSubscriptionInventory subscriptions) {
        ContextEvidence entry = entryContext(anchor, input, documents);
        RootedProcessingContext context = entry.context();
        Map<DocumentId, RootedDocumentHistory> histories = entry.histories();
        List<ChannelOccurrence> receiving = new ArrayList<>();
        for (DirectLogicalDelivery delivery : input.directDeliveries()) {
            List<ChannelOccurrence> matches = subscriptions.statesFor(
                            ContractsClosureAdapter.coordinationId(delivery.targetDocumentId())).stream()
                    .map(state -> state.channelOccurrence())
                    .filter(channel -> channel.scopePath().equals("/")
                            && channel.rawChannelKey().equals(delivery.channelKey())).toList();
            if (matches.size() != 1) {
                throw new IllegalArgumentException("Direct delivery lacks one exact checkpoint-owning channel");
            }
            receiving.add(matches.get(0));
        }
        String delivery = context.deliveryBasisIdentity(input.cause().causeIdentity(), "LIVE", receiving, null);
        return new RootedInvocationEvidence(context, delivery,
                context.invocationIdentity(input.invocationIdentity(), delivery), input.invocationIdentity(), histories)
                .capturePublicationFences(input, documents);
    }

    static RootedInvocationEvidence retained(DocumentId anchor, ClosureInvocationInput input,
            InMemoryDocumentStore documents, blue.language.processor.closure.ManagedOccurrenceBinding target,
            String sourcePositionIdentity) {
        ContextEvidence entry = entryContext(anchor, input, documents);
        String delivery = entry.context().retainedDeliveryBasisIdentity(input.cause(), target, sourcePositionIdentity);
        return new RootedInvocationEvidence(entry.context(), delivery,
                entry.context().invocationIdentity(input.invocationIdentity(), delivery), input.invocationIdentity(), entry.histories())
                .capturePublicationFences(input, documents);
    }

    static RootedInvocationEvidence retainedLocal(ContractsClosureAdapter.RootedCapturedState state, ClosureInvocationInput input,
            InMemoryDocumentStore documents, blue.language.processor.closure.ManagedOccurrenceBinding target,
            String sourcePositionIdentity, blue.coordination.api.ManagedEpochApplicationWork work) {
        state.requireRetainedInput(input, documents);
        var retained = retained(state.anchor(), input, documents, target, sourcePositionIdentity);
        return new RootedInvocationEvidence(retained.context(), retained.deliveryBasisIdentity(),
                retained.invocationIdentity(), retained.baseInvocationIdentity(), retained.histories(), state.view(), Objects.requireNonNull(work), retained.publicationFences());
    }

    /** Operational source fences never replace selected values or enter their semantic identity. */
    RootedInvocationEvidence capturePublicationFences(ClosureInvocationInput input, InMemoryDocumentStore documents) {
        Map<DocumentId, PublicationFence> fences = new LinkedHashMap<>(publicationFences);
        for (var selected : input.snapshot().managedDocuments()) {
            DocumentId id = ContractsClosureAdapter.coordinationId(selected.documentId());
            if (fences.containsKey(id)) continue;
            documents.find(id).ifPresent(session -> fences.put(id, new PublicationFence(
                    new InMemoryDocumentStore.DocumentHead(session.epoch(), session.currentRepresentation().blueId()),
                    documents.graphGeneration(id))));
        }
        return new RootedInvocationEvidence(context, deliveryBasisIdentity, invocationIdentity,
                baseInvocationIdentity, histories, historicalOrigin, historicalWork, fences);
    }

    PublicationFence publicationFence(DocumentId id) {
        return Objects.requireNonNull(publicationFences.get(id), "No captured publication fence for " + id);
    }

    record PublicationFence(InMemoryDocumentStore.DocumentHead head, long graphGeneration) { }

    private static ContextEvidence entryContext(DocumentId anchor, ClosureInvocationInput input,
            InMemoryDocumentStore documents) {
        Map<blue.language.processor.closure.DocumentId, String> identities = new LinkedHashMap<>();
        Map<DocumentId, RootedDocumentHistory> histories = new LinkedHashMap<>();
        var selectedRoot = ContractsClosureAdapter.closureId(anchor);
        var owner = input.snapshot().components().stream()
                .filter(component -> component.orderedMemberDocumentIds().contains(selectedRoot))
                .findFirst().orElseThrow();
        for (var member : owner.orderedMemberDocumentIds()) {
            DocumentId id = ContractsClosureAdapter.coordinationId(member);
            RootedDocumentHistory history = documents.require(id).requireRootedHistory();
            if (!BundledContracts10Release.manifest().contractsRelease().equals(
                    history.descriptor().get("runtimeSemanticsIdentity"))) {
                throw new IllegalArgumentException("Rooted operation cannot relabel another runtime's history");
            }
            identities.put(member, history.identity());
            histories.put(id, history);
        }
        RootedProcessingContext context = RootedProcessingContext.derive(input.snapshot(), selectedRoot, identities);
        return new ContextEvidence(context, histories);
    }

    private record ContextEvidence(RootedProcessingContext context, Map<DocumentId, RootedDocumentHistory> histories) { }

    String terminalKey() { return context.terminalKey(deliveryBasisIdentity); }

    String companionIdentity(ClosureProcessResult result) {
        if (!result.commits() || result.platformCommitCompanion() == null
                || !result.invocationIdentity().equals(baseInvocationIdentity)) {
            throw new IllegalArgumentException("Rooted companion requires the exact successful base result");
        }
        return context.commitCompanionIdentity(result.platformCommitCompanion().companionIdentity(), invocationIdentity);
    }
}
