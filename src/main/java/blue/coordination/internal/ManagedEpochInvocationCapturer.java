package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Captures one fenced Contracts invocation for retained managed-epoch work.
 *
 * <p>The capturer owns canonical due-order validation, affected-closure
 * capture, immutable source-evidence verification, and invocation-only
 * provider projection. It never executes PROCESS and never publishes state.</p>
 */
final class ManagedEpochInvocationCapturer {
    private final ContractsClosureAdapter host;
    private final BlueRuntime runtime;
    private final WholeObjectStore objects;
    private final InMemoryDocumentStore documents;
    private final ContractsClosureProfile profile;
    private final ClosureEnvironment environment;
    private final ManagedEpochSourceEvidenceVerifier evidenceVerifier;

    ManagedEpochInvocationCapturer(
            ContractsClosureAdapter host,
            BlueRuntime runtime,
            WholeObjectStore objects,
            InMemoryDocumentStore documents,
            ContractsClosureProfile profile,
            ClosureEnvironment environment) {
        this.host = Objects.requireNonNull(host, "host");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.evidenceVerifier = new ManagedEpochSourceEvidenceVerifier(
                objects, documents);
    }

    Capture capture(
            ManagedEpochApplicationWork work,
            Set<DocumentId> excludedConsumers) {
        ManagedEpochApplicationWork canonical = documents
                .nextCatchUpWorkExcluding(excludedConsumers)
                .orElseThrow(() -> new IllegalStateException(
                        "No retained managed epoch application is due"));
        if (!canonical.workIdentity().equals(work.workIdentity())) {
            throw ContractsClosureAdapter.stale(
                    "Managed epoch application is outside canonical due "
                            + "order " + work.workIdentity());
        }
        ManagedOccurrenceCatchUpPlan plan = documents
                .catchUpPlan(work.planIdentity())
                .orElseThrow(() -> new IllegalStateException(
                        "Managed application plan is absent "
                                + work.planIdentity()));
        runtime.metrics().increment(
                ManagedEpochApplicationExecutor.PLANS_OPENED);
        if (!plan.barrierIdentity().equals(work.barrierIdentity())
                || !plan.consumerDocumentId().equals(
                        work.consumerDocumentId())
                || !plan.sourceDocumentId().equals(
                        work.sourceDocumentId())
                || plan.nextSourceEpoch() != work.sourceEpoch()
                || plan.activationGeneration()
                        != work.activationGeneration()
                || !plan.targetOccurrenceIdentity().equals(
                        work.targetOccurrenceIdentity())
                || !plan.targetPath().equals(work.targetPath())) {
            throw ContractsClosureAdapter.stale(
                    "Managed application no longer owns its exact plan");
        }
        InMemoryDocumentStore.ManagedEpochEvidence retainedEvidence = documents
                .managedEpochEvidence(
                        work.sourceDocumentId(), work.sourceEpoch());
        ManagedEpochSourceEvidenceVerifier.VerifiedSourceEvidence
                sourceEvidence = evidenceVerifier.verify(
                        work, retainedEvidence, plan);
        ManagedEpochReceipt sourceReceipt = sourceEvidence.receipt();
        ManagedDocumentTransitionReceipt sourceTransition =
                sourceEvidence.transitionReceipt();
        CyclicSetProof afterCyclicProof =
                sourceEvidence.afterCyclicProof();
        runtime.metrics().increment(
                ManagedEpochApplicationExecutor.SOURCE_RECEIPTS_READ);

        InMemoryDocumentStore.ClosureTopologySnapshot topology =
                documents.closureTopologySnapshot();
        ManagedOccurrenceBinding target = topology.occurrenceInventory()
                .find(work.consumerDocumentId(), work.targetPath())
                .orElseThrow(() -> ContractsClosureAdapter.stale(
                        "Managed application occurrence retired before "
                                + work.workIdentity()));
        long fromEpoch = Math.subtractExact(work.sourceEpoch(), 1L);
        String sourceBeforeBlueId = sourceTransition == null
                ? sourceReceipt.beforeBlueId().orElseThrow(() ->
                        new IllegalStateException(
                                "A transition-free managed epoch requires "
                                        + "before state"))
                : sourceTransition.beforeBlueId();
        boolean historicalPending = !target.active()
                && target.pendingHistoricalEpoch() != null
                && target.pendingHistoricalEpoch().longValue() == fromEpoch;
        if (!target.occurrenceIdentity().equals(
                    work.targetOccurrenceIdentity())
                || target.activationGeneration()
                        != work.activationGeneration()
                || !historicalPending
                || !target.targetDocumentId().value().equals(
                        work.sourceDocumentId().value())
                || !target.expectedTargetBlueId().equals(
                        sourceBeforeBlueId)) {
            throw ContractsClosureAdapter.stale(
                    "Managed application occurrence cursor changed for "
                            + work.workIdentity());
        }

        ContractsClosureAdapter.ConnectedSelection connected =
                ContractsClosureAdapter.initialConnectedSelection(
                        topology.occurrenceInventory(),
                        topology.closureSubscriptions(),
                        work.consumerDocumentId());
        runtime.metrics().add(
                ContractsClosureAdapter.OCCURRENCE_ROWS_EXAMINED,
                connected.rowsExamined());
        // Catch-up owns a verified pending occurrence, which ordinary active
        // selection deliberately excludes. Include its source and complete
        // forward inventory without discovering unrelated reverse consumers.
        Set<DocumentId> members = ContractsClosureAdapter.forwardExistingMembers(
                connected.members(), List.of(work.sourceDocumentId()),
                topology.occurrenceInventory(), runtime.metrics());
        InMemoryDocumentStore.ClosureSnapshot publication = documents
                .closureSnapshot(members, topology);
        InMemoryDocumentStore.DocumentHead consumerHead = publication
                .requireHead(work.consumerDocumentId());
        if (consumerHead.epoch()
                        != work.expectedConsumerCommittedEpoch()
                || !consumerHead.blueId().equals(
                        work.expectedConsumerCommittedBlueId())) {
            throw ContractsClosureAdapter.stale(
                    "Managed application input fences changed for "
                            + work.workIdentity());
        }

        TreeMap<DocumentId, ContractsClosureAdapter.CapturedDocument>
                captured = new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId documentId : members) {
            captured.put(documentId, host.captureDocument(
                    documentId,
                    publication.requireHead(documentId),
                    members));
            runtime.metrics().increment(
                    ManagedEpochApplicationExecutor
                            .AFFECTED_DOCUMENTS_OPENED);
        }
        ContractsClosureAdapter.CapturedDocument capturedConsumer = captured
                .get(work.consumerDocumentId());
        if (capturedConsumer == null
                || capturedConsumer.graphGeneration()
                        != work.expectedGraphGeneration()) {
            throw ContractsClosureAdapter.stale(
                    "Managed application graph generation changed for "
                            + work.workIdentity());
        }
        long graphGeneration = ContractsClosureAdapter
                .maximumCapturedGraphGeneration(captured.values());
        Map<DocumentId, Long> componentGenerations = new LinkedHashMap<>();
        for (ComponentSnapshot component : publication.componentStates()) {
            runtime.metrics().increment(
                    ContractsClosureAdapter.COMPONENT_STATES_READ);
            for (blue.language.processor.closure.DocumentId documentId
                    : component.orderedMemberDocumentIds()) {
                componentGenerations.put(
                        ContractsClosureAdapter.coordinationId(documentId),
                        component.componentGeneration());
            }
        }
        ArrayList<ManagedDocumentSnapshot> managedDocuments =
                new ArrayList<>();
        ArrayList<blue.language.processor.closure.DocumentId> publicRoots =
                new ArrayList<>();
        ManagedLineageIndex lineageIndex = documents.lineageIndex();
        for (ContractsClosureAdapter.CapturedDocument document
                : captured.values()) {
            blue.language.processor.closure.DocumentId closureDocumentId =
                    ContractsClosureAdapter.closureId(document.documentId());
            boolean publicRoot = profile.isPublicRoot(document.documentId());
            managedDocuments.add(new ManagedDocumentSnapshot(
                    closureDocumentId,
                    document.head().blueId(),
                    providerBackedInvocationDocument(
                            document.current(), lineageIndex),
                    document.initialized(),
                    document.terminated(),
                    publicRoot,
                    document.head().epoch(),
                    ContractsClosureAdapter.requireComponentGeneration(
                            componentGenerations,
                            document.documentId())));
            if (publicRoot) {
                publicRoots.add(closureDocumentId);
            }
        }
        List<ManagedOccurrenceBinding> occurrences = new ArrayList<>();
        for (DocumentId member : members) {
            List<ManagedOccurrenceBinding> rows = topology.occurrenceInventory()
                    .rowsFrom(member);
            runtime.metrics().add(
                    ContractsClosureAdapter.OCCURRENCE_ROWS_EXAMINED, rows.size());
            occurrences.addAll(rows);
        }
        occurrences.sort(java.util.Comparator.naturalOrder());
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        graphGeneration,
                        managedDocuments,
                        occurrences,
                        publication.componentStates(),
                        publicRoots);
        // One invocation must expose one deterministic wire representation
        // for each exact BlueId, including a same-state historical source.
        Node invocationSourceAfter = providerBackedInvocationDocument(
                sourceReceipt.afterDocument(), lineageIndex);
        ManagedRevisionCause cause = sourceTransition == null
                ? ClosureEvidenceFactory.managedRevisionCause(
                        work.targetOccurrenceIdentity(),
                        ContractsClosureAdapter.closureId(
                                work.sourceDocumentId()),
                        fromEpoch,
                        work.sourceEpoch(),
                        sourceReceipt.beforeBlueId().orElseThrow(() ->
                                new IllegalStateException(
                                        "A transition-free managed source "
                                                + "epoch requires before "
                                                + "state")),
                        sourceReceipt.afterBlueId(),
                        invocationSourceAfter,
                        sourceReceipt.originalCauseIdentity(),
                        afterCyclicProof)
                : ClosureEvidenceFactory.managedRevisionCause(
                        work.targetOccurrenceIdentity(),
                        fromEpoch,
                        work.sourceEpoch(),
                        invocationSourceAfter,
                        sourceTransition,
                        afterCyclicProof);
        ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(
                snapshot,
                cause,
                List.of(),
                profile.executionPolicy(),
                environment);
        List<DocumentId> canonicalMembers = canonical(members);
        ContractsClosureAdapter.CohortInvocation invocation =
                new ContractsClosureAdapter.CohortInvocation(
                        canonicalMembers,
                        List.of(),
                        input,
                        null,
                        captured,
                        null,
                        null,
                        List.of(work.consumerDocumentId()),
                        profile.isPublicRoot(work.consumerDocumentId())
                                ? List.of(work.consumerDocumentId())
                                : List.of());
        return new Capture(
                work, sourceReceipt, sourceTransition, invocation);
    }

    /**
     * Builds an invocation-only representation that keeps already retained
     * exact descendants behind their verified provider identities.
     */
    private Node providerBackedInvocationDocument(
            ExactValue current,
            ManagedLineageIndex lineageIndex) {
        ExactValue exact = Objects.requireNonNull(current, "current");
        ManagedLineageIndex lineages = Objects.requireNonNull(
                lineageIndex, "lineageIndex");
        if (exact.isCyclicMember()) {
            // Re-projecting one cyclic member would discard its set-wide
            // representation context; the retained proof authenticates it.
            return objects.requireProviderDocument(exact);
        }
        FrozenNode original = exact.frozen();
        FrozenNode projected = original;
        ArrayList<List<String>> substituted = new ArrayList<>();
        for (Map.Entry<String, FrozenNode> entry
                : original.pathIndex().entrySet()) {
            String pointer = entry.getKey();
            if (JsonPointer.ROOT.equals(pointer)) {
                continue;
            }
            List<String> segments = JsonPointer.split(pointer);
            if (belowSubstitutedPath(segments, substituted)) {
                continue;
            }
            FrozenNode descendant = entry.getValue();
            if (descendant.isReferenceOnly()
                    || !isRetainedManagedState(
                            lineages, descendant.blueId())
                    || !objects.hasCompleteOrdinaryProviderBody(
                            descendant.blueId())) {
                continue;
            }
            projected = replaceAt(
                    projected,
                    segments,
                    FrozenNode.fromNode(
                            new Node().blueId(descendant.blueId())));
            substituted.add(List.copyOf(segments));
        }
        if (!original.blueId().equals(projected.blueId())) {
            throw new IllegalStateException(
                    "Invocation provider projection changed durable Root "
                            + exact.blueId());
        }
        if (!substituted.isEmpty()) {
            objects.preferProviderRepresentation(
                    projected,
                    "managed-epoch-invocation-provider-shell");
            runtime.metrics().add(
                    "managedEpoch.catchUp.providerReferenceSubstitutions",
                    substituted.size());
        }
        return projected.toNode();
    }

    private static boolean isRetainedManagedState(
            ManagedLineageIndex lineages,
            String blueId) {
        return !lineages.authoredInitialMatches(blueId).isEmpty()
                || !lineages.initializedMatches(blueId).isEmpty()
                || !lineages.retainedMatches(blueId).isEmpty()
                || !lineages.currentMatches(blueId).isEmpty();
    }

    private static boolean belowSubstitutedPath(
            List<String> candidate,
            List<List<String>> substituted) {
        for (List<String> ancestor : substituted) {
            if (candidate.size() > ancestor.size()
                    && candidate.subList(0, ancestor.size())
                            .equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private static FrozenNode replaceAt(
            FrozenNode root,
            List<String> segments,
            FrozenNode replacement) {
        if (segments.isEmpty()) {
            return Objects.requireNonNull(replacement, "replacement");
        }
        String head = segments.get(0);
        List<String> tail = segments.subList(1, segments.size());
        if (root.getItems() != null) {
            int index;
            try {
                index = Integer.parseInt(head);
            } catch (NumberFormatException failure) {
                throw new IllegalStateException(
                        "List path segment is not an index: " + head,
                        failure);
            }
            if (index < 0 || index >= root.getItems().size()) {
                throw new IllegalStateException(
                        "List index out of range: " + index);
            }
            ArrayList<FrozenNode> items = new ArrayList<>(root.getItems());
            items.set(index, replaceAt(
                    items.get(index), tail, replacement));
            return root.withItems(items);
        }
        FrozenNode child = root.property(head);
        if (child == null) {
            throw new IllegalStateException(
                    "Cannot replace absent property " + head);
        }
        return root.withProperty(
                head, replaceAt(child, tail, replacement));
    }

    private static List<DocumentId> canonical(Set<DocumentId> members) {
        return members.stream()
                .sorted(EmbeddingBinding.DOCUMENT_ORDER)
                .toList();
    }

    /** Exact captured input and immutable evidence for one application. */
    record Capture(
            ManagedEpochApplicationWork work,
            ManagedEpochReceipt sourceReceipt,
            ManagedDocumentTransitionReceipt sourceTransitionReceipt,
            ContractsClosureAdapter.CohortInvocation invocation) {
        Capture {
            work = Objects.requireNonNull(work, "work");
            sourceReceipt = Objects.requireNonNull(
                    sourceReceipt, "sourceReceipt");
            invocation = Objects.requireNonNull(invocation, "invocation");
        }
    }
}
