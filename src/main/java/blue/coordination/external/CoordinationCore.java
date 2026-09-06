package blue.coordination.external;

import blue.coordination.api.ExactValue;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.DirectLogicalDelivery;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.SourceObservationProgram;
import blue.language.processor.closure.SourceInitialization;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedReactionContext;
import blue.language.processor.closure.ExternalEventCause;
import blue.language.processor.closure.SourceOperationFailure;
import blue.language.processor.closure.SameOriginOperationResult;
import blue.language.processor.closure.SameOriginAttachmentPolicy;
import blue.language.processor.closure.SameOriginProcessAttempt;
import blue.language.processor.closure.SourceExecutionBasis;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stateless execution boundary over one immutable externally supplied read cut.
 * No lineage, input cursor, retained receipt, or publication authority resides
 * in this object. Re-entry supplies the same evidence to a fresh evaluator.
 *
 * <p>The read cut is a bounded directed slice supplied by the host's trusted
 * indexes. The evaluator selects the next exact input, derives its Contracts
 * invocation, and returns every projection in one immutable proposal.</p>
 */
public final class CoordinationCore {
    private final DocumentProcessor processor;
    private final ClosureEnvironment environment;
    private final ExecutionPolicy executionPolicy;

    public CoordinationCore(DocumentProcessor processor,
                            ClosureEnvironment environment,
                            ExecutionPolicy executionPolicy) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy");
    }

    public enum OperationKind { INITIALIZATION, EXTERNAL_INPUT, MANAGED_RECEIPT_IMPORT }

    ClosureEnvironment environment() { return environment; }
    ExecutionPolicy executionPolicy() { return executionPolicy; }
    public enum Disposition { CONSUMED, BLOCKED }

    /** Pending work identity precedes the eventual operation identity. */
    public record WorkIntent(DocumentId lineage, OperationKind kind) {
        public WorkIntent { Objects.requireNonNull(lineage); Objects.requireNonNull(kind); }
    }

    /** A host CAS token is opaque and is never an execution identity input. */
    public record ReadFence(String key, String revision) {
        public ReadFence { Objects.requireNonNull(key); Objects.requireNonNull(revision); }
    }

    /** Strict microseconds followed by canonical entry BlueId text. */
    public record TimelineInput(String timelineId, long timestampMicros,
                                ExactValue entry, ExactValue event,
                                List<DirectLogicalDelivery> deliveries) {
        public TimelineInput {
            Objects.requireNonNull(timelineId); Objects.requireNonNull(entry); Objects.requireNonNull(event);
            if (timestampMicros <= 0L || timestampMicros >= 9_007_199_254_740_991L) {
                throw new IllegalArgumentException("Timestamp must be a positive safe microsecond value with an exclusive successor");
            }
            validateTimelineEntry(timelineId, timestampMicros, entry, event);
            deliveries = List.copyOf(deliveries);
        }
        public ExternalOrderKey order() { return ExternalOrderKey.of(List.of(timestampMicros, entry.blueId())); }
    }

    /** Every relevant Timeline has a complete normalized prefix at this cut. */
    public record TimelinePrefix(String timelineId, long exclusiveCompleteBeforeMicros,
                                 List<TimelineInput> inputs) {
        public TimelinePrefix {
            Objects.requireNonNull(timelineId);
            inputs = List.copyOf(inputs);
            ExternalOrderKey previous = null;
            for (TimelineInput input : inputs) {
                if (!timelineId.equals(input.timelineId())
                        || previous != null && previous.compareTo(input.order()) >= 0) {
                    throw new IllegalArgumentException("Timeline prefix must have unique canonical ordered entries");
                }
                previous = input.order();
            }
        }
    }

    /** Immutable exact cut; absent facts return named waits before invocation. */
    public record EvaluationEvidence(AffectedClosureSnapshot snapshot,
                                     Set<String> relevantTimelines,
                                     List<TimelinePrefix> prefixes,
                                     Optional<ExternalOrderKey> handledThrough,
                                     List<ReadFence> fences,
                                     Map<DocumentId, String> precedingOperations,
                                     List<SourceObservationProgram> sourcePrograms,
                                     Map<DocumentId, List<blue.language.processor.closure.SourceObservationGap>> sourceGaps,
                                     List<blue.language.processor.closure.SourceOperationFailure> sourceFailures,
                                     List<SourceInitialization> sourceInitializations,
                                     Map<DocumentId, List<ReadFence>> operationFences,
                                     List<SourceFrontierSelection> sourceFrontiers,
                                     Map<DocumentId, String> expectedSourceBases,
                                     List<SourceInputAdmission> sourceInputAdmissions) {
        public EvaluationEvidence(AffectedClosureSnapshot snapshot, Set<String> relevantTimelines,
                                  List<TimelinePrefix> prefixes, Optional<ExternalOrderKey> handledThrough,
                                  List<ReadFence> fences, Map<DocumentId, String> precedingOperations,
                                  List<SourceObservationProgram> sourcePrograms,
                                  Map<DocumentId, List<blue.language.processor.closure.SourceObservationGap>> sourceGaps,
                                  List<SourceOperationFailure> sourceFailures, List<SourceInitialization> sourceInitializations,
                                  Map<DocumentId, List<ReadFence>> operationFences, List<SourceFrontierSelection> sourceFrontiers) {
            this(snapshot, relevantTimelines, prefixes, handledThrough, fences, precedingOperations, sourcePrograms,
                    sourceGaps, sourceFailures, sourceInitializations, operationFences, sourceFrontiers, Map.of(), List.of());
        }
        public EvaluationEvidence(AffectedClosureSnapshot snapshot, Set<String> relevantTimelines,
                                  List<TimelinePrefix> prefixes, Optional<ExternalOrderKey> handledThrough,
                                  List<ReadFence> fences, Map<DocumentId, String> precedingOperations,
                                  List<SourceObservationProgram> sourcePrograms,
                                  Map<DocumentId, List<blue.language.processor.closure.SourceObservationGap>> sourceGaps,
                                  List<SourceOperationFailure> sourceFailures, List<SourceInitialization> sourceInitializations,
                                  Map<DocumentId, List<ReadFence>> operationFences) {
            this(snapshot, relevantTimelines, prefixes, handledThrough, fences, precedingOperations, sourcePrograms,
                    sourceGaps, sourceFailures, sourceInitializations, operationFences, List.of());
        }
        public EvaluationEvidence(AffectedClosureSnapshot snapshot, Set<String> relevantTimelines,
                                  List<TimelinePrefix> prefixes, Optional<ExternalOrderKey> handledThrough,
                                  List<ReadFence> fences, Map<DocumentId, String> precedingOperations,
                                  List<SourceObservationProgram> sourcePrograms,
                                  Map<DocumentId, List<blue.language.processor.closure.SourceObservationGap>> sourceGaps,
                                  List<SourceOperationFailure> sourceFailures, List<SourceInitialization> sourceInitializations) {
            this(snapshot, relevantTimelines, prefixes, handledThrough, fences, precedingOperations, sourcePrograms,
                    sourceGaps, sourceFailures, sourceInitializations, Map.of());
        }
        public EvaluationEvidence(AffectedClosureSnapshot snapshot, Set<String> relevantTimelines,
                                  List<TimelinePrefix> prefixes, Optional<ExternalOrderKey> handledThrough,
                                  List<ReadFence> fences, Map<DocumentId, String> precedingOperations,
                                  List<SourceObservationProgram> sourcePrograms,
                                  Map<DocumentId, List<blue.language.processor.closure.SourceObservationGap>> sourceGaps,
                                  List<blue.language.processor.closure.SourceOperationFailure> sourceFailures) {
            this(snapshot, relevantTimelines, prefixes, handledThrough, fences, precedingOperations,
                    sourcePrograms, sourceGaps, sourceFailures, List.of());
        }
        public EvaluationEvidence(AffectedClosureSnapshot snapshot, Set<String> relevantTimelines,
                                  List<TimelinePrefix> prefixes, Optional<ExternalOrderKey> handledThrough,
                                  List<ReadFence> fences, Map<DocumentId, String> precedingOperations) {
            this(snapshot, relevantTimelines, prefixes, handledThrough, fences, precedingOperations, List.of(), Map.of());
        }
        public EvaluationEvidence(AffectedClosureSnapshot snapshot, Set<String> relevantTimelines,
                                  List<TimelinePrefix> prefixes, Optional<ExternalOrderKey> handledThrough,
                                  List<ReadFence> fences, Map<DocumentId, String> precedingOperations,
                                  List<SourceObservationProgram> sourcePrograms) {
            this(snapshot, relevantTimelines, prefixes, handledThrough, fences, precedingOperations, sourcePrograms, Map.of());
        }
        public EvaluationEvidence(AffectedClosureSnapshot snapshot, Set<String> relevantTimelines,
                                  List<TimelinePrefix> prefixes, Optional<ExternalOrderKey> handledThrough,
                                  List<ReadFence> fences, Map<DocumentId, String> precedingOperations,
                                  List<SourceObservationProgram> sourcePrograms,
                                  Map<DocumentId, List<blue.language.processor.closure.SourceObservationGap>> sourceGaps) {
            this(snapshot, relevantTimelines, prefixes, handledThrough, fences, precedingOperations, sourcePrograms, sourceGaps, List.of());
        }
        public EvaluationEvidence {
            Objects.requireNonNull(snapshot);
            relevantTimelines = Set.copyOf(relevantTimelines);
            prefixes = List.copyOf(prefixes);
            handledThrough = Objects.requireNonNull(handledThrough);
            fences = List.copyOf(fences);
            precedingOperations = Map.copyOf(precedingOperations);
            sourcePrograms = List.copyOf(sourcePrograms);
            sourceFailures = List.copyOf(sourceFailures);
            sourceInitializations = List.copyOf(sourceInitializations);
            sourceFrontiers = List.copyOf(sourceFrontiers);
            expectedSourceBases = Map.copyOf(expectedSourceBases);
            for (String basis : expectedSourceBases.values()) if (!basis.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Expected source basis must be an authenticated canonical digest");
            sourceInputAdmissions = List.copyOf(sourceInputAdmissions);
            if (sourceInputAdmissions.stream().map(SourceInputAdmission::identity).distinct().count() != sourceInputAdmissions.size())
                throw new IllegalArgumentException("Repeated original source admission");
            var copiedFences = new java.util.TreeMap<DocumentId, List<ReadFence>>();
            Map<String, String> exactRevisions = new java.util.HashMap<>();
            operationFences.forEach((owner, values) -> {
                if (snapshot.managedDocument(owner) == null) throw new IllegalArgumentException("Fence owner absent from read cut");
                for (ReadFence fence : values) {
                    String prior = exactRevisions.putIfAbsent(fence.key(), fence.revision());
                    if (prior != null && !prior.equals(fence.revision())) throw new IllegalArgumentException("One exact read cut has conflicting CAS revisions");
                }
                copiedFences.put(owner, List.copyOf(values));
            });
            operationFences = Map.copyOf(copiedFences);
            var copiedGaps = new java.util.TreeMap<DocumentId, List<blue.language.processor.closure.SourceObservationGap>>();
            sourceGaps.forEach((key, values) -> copiedGaps.put(key, List.copyOf(values)));
            sourceGaps = Map.copyOf(copiedGaps);
        }
        public EvaluationEvidence withSourceInitializations(List<SourceInitialization> initializations) {
            return new EvaluationEvidence(snapshot, relevantTimelines, prefixes, handledThrough, fences,
                    precedingOperations, sourcePrograms, sourceGaps, sourceFailures, initializations, operationFences, sourceFrontiers,
                    expectedSourceBases, sourceInputAdmissions);
        }
        /** Mutable CAS authority belongs to the named owner; source receipts are immutable dependencies. */
        public EvaluationEvidence withOperationFences(Map<DocumentId, List<ReadFence>> byOwner) {
            return new EvaluationEvidence(snapshot, relevantTimelines, prefixes, handledThrough, fences,
                    precedingOperations, sourcePrograms, sourceGaps, sourceFailures, sourceInitializations, byOwner, sourceFrontiers,
                    expectedSourceBases, sourceInputAdmissions);
        }
        public EvaluationEvidence withSourceFrontiers(List<SourceFrontierSelection> selections) {
            return new EvaluationEvidence(snapshot, relevantTimelines, prefixes, handledThrough, fences,
                    precedingOperations, sourcePrograms, sourceGaps, sourceFailures, sourceInitializations, operationFences, selections,
                    expectedSourceBases, sourceInputAdmissions);
        }
        /** Authority selected from original source admission/prefix context, never inferred from offered receipt fields. */
        public EvaluationEvidence withExpectedSourceBases(Map<DocumentId, String> bases) {
            return new EvaluationEvidence(snapshot, relevantTimelines, prefixes, handledThrough, fences, precedingOperations,
                    sourcePrograms, sourceGaps, sourceFailures, sourceInitializations, operationFences, sourceFrontiers, bases, sourceInputAdmissions);
        }
        public EvaluationEvidence withSourceInputAdmissions(List<SourceInputAdmission> admissions) {
            return new EvaluationEvidence(snapshot, relevantTimelines, prefixes, handledThrough, fences, precedingOperations,
                    sourcePrograms, sourceGaps, sourceFailures, sourceInitializations, operationFences, sourceFrontiers, expectedSourceBases, admissions);
        }
        EvaluationEvidence withHistoryCut(List<TimelinePrefix> bounded, Optional<ExternalOrderKey> through, Map<DocumentId, String> predecessors) {
            return new EvaluationEvidence(snapshot, relevantTimelines, bounded, through, fences, predecessors,
                    sourcePrograms, sourceGaps, sourceFailures, sourceInitializations, operationFences, sourceFrontiers,
                    expectedSourceBases, sourceInputAdmissions);
        }
    }

    public sealed interface EvaluationResult permits NeedEvidence, Idle, MetadataProgress, ManagedProgress, PreparedOperation, PreparedOperations { }
    public record NeedEvidence(List<String> keys,
                               List<blue.language.processor.closure.ClosureResourceDemand> resourceDemands) implements EvaluationResult {
        public NeedEvidence(List<String> keys) { this(keys, List.of()); }
        public NeedEvidence {
            keys = List.copyOf(keys); resourceDemands = List.copyOf(resourceDemands);
            if (keys.isEmpty()) throw new IllegalArgumentException("Named needs required");
            Set<String> unique = new HashSet<>();
            for (var demand : resourceDemands) {
                if (!keys.contains(demand.demandIdentity()) || !unique.add(demand.demandIdentity()))
                    throw new IllegalArgumentException("Typed demands must be unique and named by the exact need keys");
            }
        }
    }
    public record Idle() implements EvaluationResult { }

    /** No processor invocation, gas settlement, business epoch, or output stream. */
    public record MetadataProgress(DocumentId lineage, TimelineInput input, List<ReadFence> fences,
                                   List<String> consumedSourceOperations)
            implements EvaluationResult {
        public MetadataProgress(DocumentId lineage, TimelineInput input, List<ReadFence> fences) {
            this(lineage, input, fences, List.of());
        }
        public MetadataProgress { Objects.requireNonNull(lineage); Objects.requireNonNull(input); fences = List.copyOf(fences);
            consumedSourceOperations = List.copyOf(consumedSourceOperations); }
    }

    /** Terminal source failure accounting without an observer business invocation or Timeline advance. */
    public record ManagedProgress(ManagedReactionContext managedReaction, List<ReadFence> fences,
                                  List<ManagedImportLane.Delta> laneDeltas) implements EvaluationResult {
        public ManagedProgress { Objects.requireNonNull(managedReaction); fences = List.copyOf(fences); laneDeltas = List.copyOf(laneDeltas); }
    }

    /** A failed operation preserves its exact successful before-view and epoch. */
    public record LineageProjection(DocumentId lineage, String precedingOperation,
                                    String beforeBlueId, String afterBlueId,
                                    long beforeEpoch, long afterEpoch,
                                    boolean publicRoot, ResultingDocument result) { }

    /** Successful dependency observation; never a source lineage mutation. */
    public record SourcePin(DocumentId lineage, String sourceOperation, String blueId, long epoch) { }

    /** Dependency-first proposals, each independently atomic; never one aggregate transaction. */
    public static final class PreparedOperations implements EvaluationResult {
        private final List<PreparedGroupOperation> operations;
        private final Optional<MetadataProgress> targetProgress;
        private PreparedOperations(SameOriginProcessAttempt evaluated, List<PreparedGroupOperation> operations,
                Optional<MetadataProgress> targetProgress) {
            this.operations = List.copyOf(operations);
            this.targetProgress = Objects.requireNonNull(targetProgress);
            if (operations.isEmpty()) throw new IllegalArgumentException("Prepared operations cannot be empty");
            if (!evaluated.complete() || evaluated.operations().size() != operations.size())
                throw new IllegalArgumentException("Every freshly evaluated operation must be retained");
            Set<String> identities = new HashSet<>();
            Set<DocumentId> owners = new HashSet<>();
            Set<String> freshIdentities = evaluated.operations().stream().map(SameOriginOperationResult::operationIdentity)
                    .collect(java.util.stream.Collectors.toSet());
            for (int index = 0; index < operations.size(); index++) {
                PreparedGroupOperation operation = operations.get(index);
                if (operation.result() != evaluated.operations().get(index))
                    throw new IllegalArgumentException("Operation sequence is not the canonical interpreter output");
                for (String dependency : operation.consumedSourceOperations())
                    if (freshIdentities.contains(dependency) && !identities.contains(dependency))
                        throw new IllegalArgumentException("Fresh source must precede its consumer");
                if (!identities.add(operation.operationId())) throw new IllegalArgumentException("Repeated operation");
                for (DocumentId owner : operation.ownedLineages())
                    if (!owners.add(owner)) throw new IllegalArgumentException("Independent operations overlap ownership");
            }
        }
        public List<PreparedGroupOperation> operations() { return operations; }
        /** Present only when the requested lineage handled the origin without its own business operation. */
        public Optional<MetadataProgress> targetProgress() { return targetProgress; }
    }

    /** One actual same-origin group. The result's owned effects are already sparse authority. */
    public record PreparedGroupOperation(String operationId, OperationKind kind, Disposition disposition,
            Optional<TimelineInput> input, List<ReadFence> fences, ClosureInvocationInput invocation,
            SameOriginOperationResult result, List<LineageProjection> projections,
            List<SourcePin> sourcePins, List<String> consumedSourceOperations) {
        public PreparedGroupOperation {
            Objects.requireNonNull(operationId); Objects.requireNonNull(kind); Objects.requireNonNull(disposition);
            input = Objects.requireNonNull(input); fences = List.copyOf(fences);
            Objects.requireNonNull(invocation); Objects.requireNonNull(result);
            projections = List.copyOf(projections); sourcePins = List.copyOf(sourcePins);
            consumedSourceOperations = List.copyOf(consumedSourceOperations);
            if (!operationId.equals(result.operationIdentity()) || result.origin() != invocation)
                throw new IllegalArgumentException("Group proposal lost its owning interpreter result");
            if (kind != OperationKind.EXTERNAL_INPUT || disposition != Disposition.CONSUMED || input.isEmpty())
                throw new IllegalArgumentException("A complete same-origin operation consumes one exact external input");
            ExternalEventCause cause = (ExternalEventCause) invocation.cause();
            if (!cause.eventBlueId().equals(input.get().entry().blueId()) || cause.sourceOrder().compareTo(input.get().order()) != 0)
                throw new IllegalArgumentException("Group proposal names another external origin");
            Map<DocumentId, ResultingDocument> actual = new java.util.TreeMap<>();
            result.resultingDocuments().forEach(document -> actual.put(document.documentId(), document));
            Set<DocumentId> projected = new HashSet<>();
            for (LineageProjection projection : projections) {
                ResultingDocument document = actual.get(projection.lineage());
                ManagedDocumentSnapshot before = invocation.snapshot().managedDocument(projection.lineage());
                if (!projected.add(projection.lineage()) || document == null || document != projection.result()
                        || !projection.beforeBlueId().equals(before.blueId()) || !projection.afterBlueId().equals(document.afterBlueId())
                        || projection.beforeEpoch() != before.epoch() || projection.afterEpoch() != document.epoch()
                        || !Objects.equals(projection.precedingOperation(), invocation.semanticPredecessors().get(projection.lineage())))
                    throw new IllegalArgumentException("Group proposal has inconsistent owned projections");
            }
            if (!projected.equals(result.ownedDocumentIds())) throw new IllegalArgumentException("Group proposal omits owned lineages");
            List<String> expectedDependencies = result.consumedSourceOperations().values().stream().distinct().sorted().toList();
            if (!expectedDependencies.equals(consumedSourceOperations)) throw new IllegalArgumentException("Group proposal loses source dependencies");
            List<SourcePin> expectedPins = new ArrayList<>();
            if (result.sourceProgram().isPresent()) for (SourceObservationProgram.SourceState state : result.sourceProgram().get().sourceResults()) {
                String dependency = result.consumedSourceOperations().get(state.documentId());
                if (dependency != null) expectedPins.add(new SourcePin(state.documentId(), dependency, state.blueId(), state.epoch()));
            }
            expectedPins.sort(Comparator.comparing(SourcePin::lineage));
            if (!expectedPins.equals(sourcePins)) throw new IllegalArgumentException("Group source pins are not its exact consumed views");
        }
        public Set<DocumentId> ownedLineages() { return result.ownedDocumentIds(); }
        public Optional<SourceObservationProgram> sourceProgram() { return result.sourceProgram(); }
        public List<blue.language.processor.closure.GraphChange> ownedGraphChanges() { return result.graphChanges(); }
        public List<ManagedOccurrenceBinding> ownedOccurrenceBindings() { return result.occurrenceBindings(); }
        public List<blue.language.processor.closure.SubscriptionDelta> ownedSubscriptionDeltas() { return result.subscriptionDeltas(); }
        public List<blue.language.processor.closure.CheckpointWrite> ownedCheckpointWrites() { return result.checkpointWrites(); }
    }

    /** One complete proposal; host publication is an exact fenced transaction. */
    public record PreparedOperation(String operationId, OperationKind kind,
                                    Disposition disposition, Optional<TimelineInput> input,
                                    List<ReadFence> fences,
                                    ClosureInvocationInput invocation,
                                    ClosureProcessResult result,
                                    List<LineageProjection> projections,
                                    Optional<SourceObservationProgram> sourceProgram,
                                    List<SourcePin> sourcePins,
                                    List<String> consumedSourceOperations,
                                    Optional<ManagedReactionContext> managedReaction,
                                    List<ManagedImportLane.Delta> laneDeltas)
            implements EvaluationResult {
        public PreparedOperation(String operationId, OperationKind kind, Disposition disposition, Optional<TimelineInput> input,
                                 List<ReadFence> fences, ClosureInvocationInput invocation, ClosureProcessResult result,
                                 List<LineageProjection> projections, Optional<SourceObservationProgram> sourceProgram,
                                 List<SourcePin> sourcePins, List<String> consumedSourceOperations) {
            this(operationId, kind, disposition, input, fences, invocation, result, projections, sourceProgram,
                    sourcePins, consumedSourceOperations, Optional.empty(), List.of());
        }
        public PreparedOperation {
            Objects.requireNonNull(operationId); Objects.requireNonNull(kind); Objects.requireNonNull(disposition);
            input = Objects.requireNonNull(input); fences = List.copyOf(fences);
            Objects.requireNonNull(invocation); Objects.requireNonNull(result);
            projections = List.copyOf(projections); sourceProgram = Objects.requireNonNull(sourceProgram);
            sourcePins = List.copyOf(sourcePins); consumedSourceOperations = List.copyOf(consumedSourceOperations);
            managedReaction = Objects.requireNonNull(managedReaction); laneDeltas = List.copyOf(laneDeltas);
        }

        /** Sparse write authority is the operation's lineage set, never the full read snapshot. */
        public Set<DocumentId> ownedLineages() {
            return projections.stream().map(LineageProjection::lineage).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        public List<blue.language.processor.closure.GraphChange> ownedGraphChanges() {
            Set<DocumentId> owned = ownedLineages();
            return result.graphChanges().stream().filter(change -> owned.contains(change.sourceDocumentId())).toList();
        }

        public List<ManagedOccurrenceBinding> ownedOccurrenceBindings() {
            Set<DocumentId> owned = ownedLineages();
            return result.occurrenceBindings().stream().filter(binding -> owned.contains(binding.sourceDocumentId())).toList();
        }

        public List<blue.language.processor.closure.SubscriptionDelta> ownedSubscriptionDeltas() {
            Set<DocumentId> owned = ownedLineages();
            return result.subscriptionDeltas().stream().filter(delta -> {
                var state = delta.afterSubscription() != null ? delta.afterSubscription() : delta.beforeSubscription();
                return owned.contains(state.channelOccurrence().managedDocumentId());
            }).toList();
        }

        public List<blue.language.processor.closure.CheckpointWrite> ownedCheckpointWrites() {
            Set<DocumentId> owned = ownedLineages();
            return result.checkpointWrites().stream().filter(write -> owned.contains(write.targetManagedScopeKey().documentId())).toList();
        }
    }

    public EvaluationResult evaluate(WorkIntent intent, EvaluationEvidence evidence) {
        return evaluate(intent, evidence, SameOriginAttachmentPolicy.empty());
    }

    /** Explicit authored attachment choices; offered child evidence is not an active source seed. */
    public EvaluationResult evaluate(WorkIntent intent, EvaluationEvidence evidence, SameOriginAttachmentPolicy attachmentPolicy) {
        return evaluate(intent, evidence, attachmentPolicy, null, null);
    }

    EvaluationResult evaluateCanonicalSource(WorkIntent intent, EvaluationEvidence evidence,
                                              Map<String, String> originalAdmissionRoots, String sourceBasis) {
        return evaluate(intent, evidence, SameOriginAttachmentPolicy.empty(), originalAdmissionRoots, sourceBasis);
    }

    private EvaluationResult evaluate(WorkIntent intent, EvaluationEvidence evidence, SameOriginAttachmentPolicy attachmentPolicy,
                                      Map<String, String> originalAdmissionRoots, String sourceBasis) {
        Objects.requireNonNull(intent, "intent"); Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(attachmentPolicy, "attachmentPolicy");
        if (intent.kind() != OperationKind.EXTERNAL_INPUT && !attachmentPolicy.entries().isEmpty())
            throw new IllegalArgumentException("Attachment choices require an actual external creation origin");
        if (intent.kind() == OperationKind.MANAGED_RECEIPT_IMPORT) {
            return new NeedEvidence(List.of("managed-reaction-selection:" + intent.lineage().value()));
        }
        ManagedDocumentSnapshot target = evidence.snapshot().managedDocument(intent.lineage());
        if (target == null) return new NeedEvidence(List.of("lineage:" + intent.lineage().value()));
        Set<DocumentId> readRoots = new HashSet<>(); readRoots.add(intent.lineage());
        Set<DocumentId> liveMembers = directedMemberIds(readRoots, evidence.snapshot(), true);
        // Keep complete outgoing rows (including inactive prospective bindings) for
        // sealed component authority. Eligibility is a separate live-root set;
        // trimming the evidence itself would invalidate the selected topology.
        AffectedClosureSnapshot directed = canonicalSemanticSnapshot(directedSnapshot(readRoots, evidence.snapshot(),
                intent.kind() == OperationKind.INITIALIZATION));
        Set<DocumentId> owned = new HashSet<>();
        for (ComponentSnapshot component : directed.components()) {
            if (component.orderedMemberDocumentIds().contains(intent.lineage())) {
                owned.addAll(component.orderedMemberDocumentIds());
            }
        }
        Optional<TimelineInput> selected = Optional.empty();
        List<SourceObservationProgram> sourcePrograms = new ArrayList<>();
        List<SourceInitialization> sourceInitializations = new ArrayList<>();
        List<blue.language.processor.closure.SourceOperationFailure> sourceFailures = new ArrayList<>();
        ClosureInvocationInput invocation;
        if (intent.kind() == OperationKind.INITIALIZATION) {
            if (target.initialized()) return new Idle();
            List<String> prerequisiteBirths = directed.managedDocuments().stream()
                    .filter(d -> !owned.contains(d.documentId()) && !d.initialized())
                    .map(d -> "canonical-initialization:" + d.documentId().value()).toList();
            if (!prerequisiteBirths.isEmpty()) return new NeedEvidence(prerequisiteBirths);
            Set<DocumentId> suppliedBirths = new HashSet<>();
            for (SourceInitialization initialization : evidence.sourceInitializations()) {
                if (initialization.ownedDocumentIds().stream().noneMatch(id -> directed.managedDocument(id) != null)) continue;
                initialization.verifyInstallationBasis(directed, owned, environment, executionPolicy);
                for (DocumentId source : initialization.ownedDocumentIds())
                    if (!suppliedBirths.add(source)) throw new IllegalArgumentException("Repeated canonical source initialization");
                sourceInitializations.add(initialization);
                sourcePrograms.add(initialization.program());
            }
            List<SourceObservationProgram> completePrograms = expandBorrowedPrograms(sourcePrograms);
            sourcePrograms.clear(); sourcePrograms.addAll(completePrograms);
            for (SourceObservationProgram program : sourcePrograms) {
                if (program.causeKind() != blue.language.processor.closure.ProcessingCause.Kind.ADMISSION)
                    throw new IllegalArgumentException("Canonical initialization cannot borrow an external historical operation as init0");
                suppliedBirths.addAll(program.ownedDocumentIds());
            }
            // The invocation consumes a logical historical cut, not whatever head happens to
            // reside in a host row. The host may assemble these views from already loaded receipt
            // fragments; a named need here neither executes nor charges another source operation.
            List<String> missingInitializationViews = new ArrayList<>();
            for (SourceObservationProgram program : sourcePrograms) {
                for (SourceObservationProgram.SourceState view : program.sourceResults()) {
                    if (!program.ownedDocumentIds().contains(view.documentId())) continue;
                    ManagedDocumentSnapshot present = directed.managedDocument(view.documentId());
                    if (present == null || !present.initialized() || present.terminated()
                            || present.epoch() != view.epoch() || !present.blueId().equals(view.blueId()))
                        missingInitializationViews.add("canonical-initialization-view:" + view.documentId().value() + ":" + view.blueId());
                }
            }
            if (!missingInitializationViews.isEmpty()) return new NeedEvidence(missingInitializationViews.stream().distinct().sorted().toList());
            List<String> missingInitializations = directed.occurrences().stream()
                    .filter(binding -> binding.active() && owned.contains(binding.sourceDocumentId())
                            && !owned.contains(binding.targetDocumentId())
                            && binding.expectedTargetBlueId().equals(binding.targetDocumentId().value())
                            && !suppliedBirths.contains(binding.targetDocumentId()))
                    .map(binding -> "canonical-initialization:" + binding.targetDocumentId().value()).distinct().sorted().toList();
            if (!missingInitializations.isEmpty()) return new NeedEvidence(missingInitializations);
            // Canonical source birth has no host admission time or observer FROM_* input.
            var cause = SourceInitialization.canonicalAdmissionCause(owned);
            invocation = ClosureEvidenceFactory.admitClosure(directed, cause, null,
                    executionPolicy, environment);
        } else {
            DirectedTimelineMembership.Checked membership;
            Map<DocumentId, Set<String>> logicalHeaderOrigins;
            try (BlueClosureContracts selection = new BlueClosureContracts(processor)) {
                var headerSelection = logicalHeaderOrigins(directed, liveMembers, evidence, selection);
                if (!headerSelection.needs().isEmpty()) return new NeedEvidence(headerSelection.needs());
                logicalHeaderOrigins = headerSelection.origins();
                List<String> unaligned = logicalHeaderOrigins.entrySet().stream().filter(entry -> entry.getValue().isEmpty())
                        .map(entry -> "root-channel-logical-view:" + entry.getKey().value()).sorted().toList();
                if (!unaligned.isEmpty()) return new NeedEvidence(unaligned);
                membership = DirectedTimelineMembership.verify(directed, selection.captureRootMetadata(directed, liveMembers),
                        environment.runtimeRegistryIdentity(), evidence.relevantTimelines(), liveMembers);
            } catch (blue.language.processor.ExecutionEvidenceUnavailableException unavailable) {
                Set<String> needs = new TreeSet<>(unavailable.requiredExactBlueIds());
                if (needs.isEmpty()) needs.add("root-channel-metadata:" + intent.lineage().value());
                return new NeedEvidence(List.copyOf(needs));
            }
            if (!membership.needs().isEmpty()) return new NeedEvidence(membership.needs());
            Set<String> relevantTimelines = membership.timelines();
            Set<String> supplied = new TreeSet<>();
            for (TimelinePrefix prefix : evidence.prefixes()) {
                if (!supplied.add(prefix.timelineId())) throw new IllegalArgumentException("Duplicate Timeline prefix");
            }
            List<String> missing = relevantTimelines.stream().filter(t -> !supplied.contains(t))
                    .sorted().map(t -> "timeline-prefix:" + t).toList();
            if (!missing.isEmpty()) return new NeedEvidence(missing);
            selected = evidence.prefixes().stream()
                    .filter(p -> relevantTimelines.contains(p.timelineId()))
                    .flatMap(p -> p.inputs().stream())
                    .filter(i -> evidence.handledThrough().isEmpty()
                            || i.order().compareTo(evidence.handledThrough().get()) > 0)
                    .min(Comparator.comparing(TimelineInput::order));
            if (selected.isEmpty()) return new Idle();
            TimelineInput input = selected.get();
            List<String> unrelatedHeaderOrigins = logicalHeaderOrigins.entrySet().stream()
                    .filter(entry -> !entry.getValue().contains(input.entry().blueId()))
                    .map(entry -> "root-channel-logical-view:" + entry.getKey().value()).sorted().toList();
            if (!unrelatedHeaderOrigins.isEmpty()) return new NeedEvidence(unrelatedHeaderOrigins);
            List<String> incomplete = evidence.prefixes().stream()
                    .filter(p -> relevantTimelines.contains(p.timelineId()))
                    .filter(p -> p.exclusiveCompleteBeforeMicros() <= input.timestampMicros())
                    .map(p -> "timeline-complete-after:" + p.timelineId() + ":" + input.timestampMicros())
                    .sorted().toList();
            if (!incomplete.isEmpty()) return new NeedEvidence(incomplete);
            if (originalAdmissionRoots != null) {
                String admittedRoot = originalAdmissionRoots.get(input.entry().blueId());
                if (admittedRoot == null) return new NeedEvidence(List.of("source-input-admission:" + intent.lineage().value()
                        + ":" + input.entry().blueId()));
                SourceInputAdmission admitted = evidence.sourceInputAdmissions().stream()
                        .filter(value -> value.identity().equals(admittedRoot)).findFirst().orElse(null);
                if (admitted == null) return new NeedEvidence(List.of("source-input-admission-record:" + admittedRoot));
                admitted.verify(intent.lineage(), sourceBasis, input, evidence.precedingOperations(), liveMembers, owned);
                // Co-owned members execute one original invocation with one policy. Independent
                // directed dependencies are deliberately not compared to this consumer policy.
                for (DocumentId member : owned) if (!SourceExecutionBasis.identity(member, environment, executionPolicy)
                        .equals(admitted.sourceBases().get(member)))
                    throw new blue.language.processor.InvalidExecutionEvidenceException(
                            "Original admission changes a co-owned member's producer basis");
                attachmentPolicy = admitted.selections();
            }
            for (var choice : attachmentPolicy.entries()) {
                if (!liveMembers.contains(choice.creatorLineage()))
                    throw new IllegalArgumentException("Attachment policy creator is outside the selected live directed cut");
                if (directed.managedDocument(choice.targetLineage()) == null)
                    return new NeedEvidence(List.of("lineage:" + choice.targetLineage().value()));
            }
            attachmentPolicy.verifyBasis(directed);
            for (SourceObservationProgram program : expandBorrowedPrograms(evidence.sourcePrograms())) {
                if (program.causeKind() == blue.language.processor.closure.ProcessingCause.Kind.EXTERNAL
                        && program.externalCause() != null
                        && program.externalCause().eventBlueId().equals(input.entry().blueId())
                        && program.ownedDocumentIds().stream().anyMatch(liveMembers::contains)) {
                    if (program.externalCause().sourceOrder().compareTo(input.order()) != 0)
                        throw new IllegalArgumentException("Source program belongs to another canonical input position");
                    List<String> contextNeeds = verifySourceContext(program.ownedDocumentIds(), program.environment(),
                            program.executionPolicy(), evidence.expectedSourceBases());
                    if (!contextNeeds.isEmpty()) return new NeedEvidence(contextNeeds);
                    // Extra retained/cache evidence is not a causal dependency. Keep an
                    // eligible producing operation whole, including its authenticated owners.
                    sourcePrograms.add(program);
                }
            }
            Set<DocumentId> failedSourceOwners = new HashSet<>();
            for (var failure : evidence.sourceFailures()) {
                if (failure.externalCause() != null && failure.externalCause().eventBlueId().equals(input.entry().blueId())
                        && failure.ownedDocumentIds().stream().anyMatch(liveMembers::contains)) {
                    if (failure.externalCause().sourceOrder().compareTo(input.order()) != 0) {
                        throw new IllegalArgumentException("Failed source belongs to another canonical execution environment or position");
                    }
                    List<String> contextNeeds = verifySourceContext(failure.ownedDocumentIds(), failure.environment(),
                            failure.executionPolicy(), evidence.expectedSourceBases());
                    if (!contextNeeds.isEmpty()) return new NeedEvidence(contextNeeds);
                    failure.verifyObservationBasis(directed, owned, evidence.sourceGaps());
                    sourceFailures.add(failure);
                    for (DocumentId source : failure.ownedDocumentIds()) {
                        if (owned.contains(source) || directed.managedDocument(source) == null || !failedSourceOwners.add(source)) {
                            throw new IllegalArgumentException("Source failure has conflicting directed-scope ownership");
                        }
                    }
                }
            }
            List<DirectLogicalDelivery> deliveries;
            try (BlueClosureContracts selection = new BlueClosureContracts(processor)) {
                deliveries = selection.selectDirectDeliveries(directed, input.event().copyNode(), sourcePrograms, sourceFailures, liveMembers);
            }
            if (deliveries.isEmpty()) return new MetadataProgress(intent.lineage(), input, evidence.fences(), consumedSources(sourcePrograms, sourceFailures));
            Set<DocumentId> retainedSources = new HashSet<>();
            for (SourceObservationProgram program : sourcePrograms) {
                retainedSources.addAll(program.ownedDocumentIds());
            }
            for (DocumentId failedSource : failedSourceOwners) {
                if (!retainedSources.add(failedSource)) throw new IllegalArgumentException("A source cannot both succeed and fail for one origin");
            }
            Set<DocumentId> affectedProducers = new HashSet<>();
            for (DirectLogicalDelivery delivery : deliveries) {
                if (!failedSourceOwners.contains(delivery.targetDocumentId())) affectedProducers.add(delivery.targetDocumentId());
            }
            Map<DocumentId, List<DocumentId>> observers = new java.util.HashMap<>();
            for (ManagedOccurrenceBinding binding : directed.occurrences()) {
                if (binding.active()) observers.computeIfAbsent(binding.targetDocumentId(), ignored -> new ArrayList<>())
                        .add(binding.sourceDocumentId());
            }
            ArrayDeque<DocumentId> affectedQueue = new ArrayDeque<>(affectedProducers);
            while (!affectedQueue.isEmpty()) {
                for (DocumentId observer : observers.getOrDefault(affectedQueue.removeFirst(), List.of())) {
                    if (affectedProducers.add(observer)) affectedQueue.addLast(observer);
                }
            }
            if (affectedProducers.isEmpty()) return new MetadataProgress(intent.lineage(), input, evidence.fences(), consumedSources(sourcePrograms, sourceFailures));
            if (!evidence.fences().isEmpty() || !evidence.operationFences().isEmpty()) {
                List<String> missingFences = affectedProducers.stream().filter(d -> !retainedSources.contains(d))
                        .filter(d -> !evidence.operationFences().containsKey(d))
                        .map(d -> "group-read-fences:" + d.value()).sorted().toList();
                if (!missingFences.isEmpty()) return new NeedEvidence(missingFences);
            }
            var cause = ClosureEvidenceFactory.externalCause(input.event().copyNode(), input.event().blueId(),
                    input.order(), environment.externalOrderPolicyIdentity());
            invocation = ClosureEvidenceFactory.processClosure(directed, cause,
                    deliveries, executionPolicy, environment);
        }
        Map<DocumentId, String> selectedPredecessors = new java.util.TreeMap<>();
        evidence.precedingOperations().forEach((lineage, previous) -> {
            if (directed.managedDocument(lineage) != null) selectedPredecessors.put(lineage, previous);
        });
        for (SourceObservationProgram program : sourcePrograms) {
            for (DocumentId document : program.ownedDocumentIds()) {
                if (directed.managedDocument(document) != null) {
                    selectedPredecessors.put(document, program.invocationIdentity());
                }
            }
        }
        for (var failure : sourceFailures) {
            for (DocumentId source : failure.ownedDocumentIds()) selectedPredecessors.put(source, failure.invocationIdentity());
        }
        invocation = ClosureEvidenceFactory.withSemanticPredecessors(invocation, selectedPredecessors);
        if (intent.kind() == OperationKind.EXTERNAL_INPUT) {
            List<blue.language.processor.closure.SourceFrontierView> frontierViews = new ArrayList<>();
            Map<String, SourceFrontierSelection> frontiers = new HashMap<>();
            for (var frontier : evidence.sourceFrontiers()) {
                var choice = frontier.selectedView().selection();
                if (frontiers.put(choice.occurrenceIdentity(), frontier) != null)
                    throw new IllegalArgumentException("Repeated source frontier admission");
                frontier.verifySelection(attachmentPolicy.selection(choice.occurrenceIdentity())
                        .orElseThrow(() -> new IllegalArgumentException("Frontier admission has no original creator selection")));
            }
            List<String> frontierNeeds = new ArrayList<>();
            for (var choice : attachmentPolicy.entries()) {
                if (choice.mode() != SameOriginAttachmentPolicy.Mode.FROM_FRONTIER) continue;
                var frontier = frontiers.get(choice.occurrenceIdentity());
                if (frontier == null) frontierNeeds.add("source-frontier:" + choice.targetLineage().value() + ":" + choice.frontier().orElseThrow());
                else { frontier.selectedView().verifyInvocation(invocation); frontierViews.add(frontier.selectedView()); }
            }
            if (!frontierNeeds.isEmpty()) return new NeedEvidence(frontierNeeds);
            SameOriginProcessAttempt attempt;
            try (BlueClosureContracts contracts = new BlueClosureContracts(processor)) {
                attempt = contracts.processSameOrigin(invocation, attachmentPolicy,
                        sourcePrograms, evidence.sourceGaps(), sourceFailures, evidence.sourceInitializations(), frontierViews,
                        evidence.expectedSourceBases());
            }
            if (!attempt.complete()) {
                List<String> needs = new ArrayList<>(attempt.requiredExactBlueIds());
                attempt.resourceDemands().forEach(demand -> needs.add(demand.demandIdentity()));
                return new NeedEvidence(needs, attempt.resourceDemands());
            }
            if (attempt.operations().isEmpty())
                return new MetadataProgress(intent.lineage(), selected.orElseThrow(), evidence.fences(), consumedSources(sourcePrograms, sourceFailures));
            List<PreparedGroupOperation> prepared = new ArrayList<>();
            for (SameOriginOperationResult operation : attempt.operations()) {
                List<LineageProjection> projections = new ArrayList<>();
                for (ResultingDocument after : operation.resultingDocuments()) {
                    ManagedDocumentSnapshot before = evidence.snapshot().managedDocument(after.documentId());
                    projections.add(new LineageProjection(after.documentId(), evidence.precedingOperations().get(after.documentId()),
                            before.blueId(), after.afterBlueId(), before.epoch(), after.epoch(), before.publicRoot(), after));
                }
                List<SourcePin> pins = new ArrayList<>();
                if (operation.sourceProgram().isPresent()) {
                    for (SourceObservationProgram.SourceState view : operation.sourceProgram().get().sourceResults()) {
                        String sourceOperation = operation.consumedSourceOperations().get(view.documentId());
                        if (sourceOperation != null) pins.add(new SourcePin(view.documentId(), sourceOperation, view.blueId(), view.epoch()));
                    }
                }
                pins.sort(Comparator.comparing(SourcePin::lineage));
                Map<String, ReadFence> operationFences = new java.util.TreeMap<>();
                for (DocumentId owner : operation.ownedDocumentIds()) {
                    for (ReadFence fence : evidence.operationFences().getOrDefault(owner, List.of())) {
                        ReadFence prior = operationFences.putIfAbsent(fence.key(), fence);
                        if (prior != null && !prior.equals(fence)) throw new IllegalArgumentException("Conflicting group CAS revisions");
                    }
                }
                prepared.add(new PreparedGroupOperation(operation.operationIdentity(), intent.kind(), Disposition.CONSUMED,
                        selected, List.copyOf(operationFences.values()), invocation, operation, projections, pins,
                        operation.consumedSourceOperations().values().stream().distinct().sorted().toList()));
            }
            Optional<MetadataProgress> targetProgress = Optional.empty();
            if (prepared.stream().noneMatch(operation -> operation.ownedLineages().contains(intent.lineage()))) {
                Set<String> dependencies = new TreeSet<>(consumedSources(sourcePrograms, sourceFailures));
                prepared.forEach(operation -> dependencies.add(operation.operationId()));
                targetProgress = Optional.of(new MetadataProgress(intent.lineage(), selected.orElseThrow(),
                        evidence.operationFences().getOrDefault(intent.lineage(), List.of()), List.copyOf(dependencies)));
            }
            return new PreparedOperations(attempt, prepared, targetProgress);
        }
        Capture capture = new Capture();
        ClosureAttemptResult attempt;
        try (BlueClosureContracts contracts = new BlueClosureContracts(processor, capture)) {
            attempt = intent.kind() == OperationKind.INITIALIZATION
                    ? contracts.admitExternalScope(invocation, owned, sourceInitializations)
                    : contracts.processExternalScope(invocation, owned, sourcePrograms, evidence.sourceGaps(), sourceFailures);
        }
        if (!attempt.isComplete()) {
            Set<String> needs = new TreeSet<>(attempt.requiredExactBlueIds());
            attempt.resourceDemands().forEach(demand -> needs.add(demand.demandIdentity()));
            return new NeedEvidence(List.copyOf(needs), attempt.resourceDemands());
        }
        ClosureProcessResult result = attempt.processResult();
        Disposition disposition = result.commits() || result.status() == ProcessorStatus.GAS_LIMIT_EXCEEDED
                || result.status() == ProcessorStatus.RUNTIME_FATAL ? Disposition.CONSUMED : Disposition.BLOCKED;
        if (intent.kind() == OperationKind.INITIALIZATION && !result.commits()) disposition = Disposition.BLOCKED;
        List<LineageProjection> projections = new ArrayList<>();
        for (ResultingDocument after : result.resultingDocuments()) {
            if (!owned.contains(after.documentId())) continue;
            ManagedDocumentSnapshot before = evidence.snapshot().managedDocument(after.documentId());
            projections.add(new LineageProjection(after.documentId(),
                    evidence.precedingOperations().get(after.documentId()), before.blueId(),
                    after.afterBlueId(), before.epoch(), after.epoch(), before.publicRoot(), after));
        }
        List<SourcePin> sourcePins = new ArrayList<>();
        if (result.commits()) {
            for (SourceObservationProgram program : sourcePrograms) {
                for (SourceObservationProgram.SourceState state : program.sourceResults()) {
                    if (program.ownedDocumentIds().contains(state.documentId())) {
                        sourcePins.add(new SourcePin(state.documentId(), program.invocationIdentity(), state.blueId(), state.epoch()));
                    }
                }
            }
        }
        sourcePins.sort(Comparator.comparing(SourcePin::lineage));
        return new PreparedOperation(invocation.invocationIdentity(), intent.kind(), disposition,
                selected, evidence.fences(), invocation, result, projections, Optional.ofNullable(capture.program), sourcePins,
                consumedSources(sourcePrograms, sourceFailures));
    }

    /**
     * Consumes exactly one authenticated source-producing reaction position for
     * explicitly due occurrence lanes. Neither selection nor completion reads or
     * advances the consumer's ordinary external Timeline cursor.
     */
    public EvaluationResult evaluate(ManagedImportSelection selection, EvaluationEvidence evidence) {
        Objects.requireNonNull(selection, "selection"); Objects.requireNonNull(evidence, "evidence");
        ManagedDocumentSnapshot target = evidence.snapshot().managedDocument(selection.consumer());
        if (target == null || !target.initialized())
            return new NeedEvidence(List.of("initialized-lineage:" + selection.consumer().value()));
        Map<String, ManagedOccurrenceBinding> bindings = new java.util.HashMap<>();
        for (ManagedOccurrenceBinding binding : evidence.snapshot().occurrences()) bindings.put(binding.occurrenceIdentity(), binding);
        for (ManagedImportLane.Due due : selection.due()) {
            var lane = due.cursor().descriptor();
            ManagedOccurrenceBinding binding = bindings.get(lane.occurrenceIdentity());
            if (binding == null || !binding.sourceDocumentId().equals(lane.consumerLineage())
                    || !binding.targetDocumentId().equals(lane.sourceLineage())
                    || !binding.active() && binding.pendingHistoricalEpoch() == null
                    || binding.pendingHistoricalEpoch() != null && binding.pendingHistoricalEpoch() != due.cursor().successfulEpoch()
                    || !binding.expectedTargetBlueId().equals(due.cursor().successfulBlueId()))
                throw new IllegalArgumentException("Stale managed lane activation or successful occurrence pin");
        }
        Map<String, SourceObservationProgram> available = new java.util.HashMap<>();
        for (SourceObservationProgram program : expandBorrowedPrograms(evidence.sourcePrograms())) available.put(program.invocationIdentity(), program);
        Map<String, SourceOperationFailure> availableFailures = new java.util.HashMap<>();
        for (SourceOperationFailure failure : evidence.sourceFailures()) {
            if (availableFailures.put(failure.invocationIdentity(), failure) != null || available.containsKey(failure.invocationIdentity()))
                throw new IllegalArgumentException("Conflicting source operation disposition");
        }
        Map<String, SourceObservationProgram> roots = new java.util.TreeMap<>();
        Map<String, SourceOperationFailure> failed = new java.util.TreeMap<>();
        Map<DocumentId, String> sourceBases = new java.util.TreeMap<>(evidence.expectedSourceBases());
        List<String> missing = new ArrayList<>();
        for (ManagedImportLane.Due due : selection.due()) {
            var header = due.source();
            String previousBasis = sourceBases.putIfAbsent(header.sourceLineage(), header.canonicalSourceBasis());
            if (previousBasis != null && !previousBasis.equals(header.canonicalSourceBasis()))
                throw new IllegalArgumentException("Managed source authority conflicts with its original lane basis");
            if (header.status() == ProcessorStatus.SUCCESS) {
                SourceObservationProgram program = available.get(header.operationIdentity());
                if (program == null) missing.add("source-program:" + header.operationIdentity());
                else { header.verify(program); roots.put(header.operationIdentity(), program); }
            } else {
                SourceOperationFailure failure = availableFailures.get(header.operationIdentity());
                if (failure == null) missing.add("source-failure:" + header.operationIdentity());
                else { header.verify(failure); failed.put(header.operationIdentity(), failure); }
            }
        }
        if (!missing.isEmpty()) return new NeedEvidence(missing.stream().distinct().sorted().toList());
        List<SourceObservationProgram> retainedPrograms = expandBorrowedPrograms(List.copyOf(roots.values()));
        // A creator may retain a synchronously installed initialization in its DAG.
        // That is exact read/site evidence, never another historical business epoch.
        List<SourceObservationProgram> programs = retainedPrograms.stream()
                .filter(program -> program.causeKind() == blue.language.processor.closure.ProcessingCause.Kind.EXTERNAL).toList();
        List<SourceOperationFailure> failures = List.copyOf(failed.values());
        Set<DocumentId> readRoots = new HashSet<>(); readRoots.add(selection.consumer());
        for (SourceObservationProgram program : retainedPrograms) readRoots.addAll(program.ownedDocumentIds());
        for (SourceOperationFailure failure : failures) readRoots.addAll(failure.ownedDocumentIds());
        List<String> missingDocuments = readRoots.stream().filter(id -> evidence.snapshot().managedDocument(id) == null)
                .map(id -> "lineage:" + id.value()).sorted().toList();
        if (!missingDocuments.isEmpty()) return new NeedEvidence(missingDocuments);
        AffectedClosureSnapshot directed = canonicalSemanticSnapshot(directedSnapshot(readRoots, evidence.snapshot()));
        Set<DocumentId> owned = new HashSet<>();
        for (ComponentSnapshot component : directed.components())
            if (component.orderedMemberDocumentIds().contains(selection.consumer())) owned.addAll(component.orderedMemberDocumentIds());
        selection.context().verifyBasis(directed, owned, programs, failures);
        ExternalEventCause cause = null;
        for (SourceObservationProgram program : programs) {
            if (program.externalCause() == null) throw new IllegalArgumentException("Initialization is installed separately, not imported as a business epoch");
            cause = sameSourceCause(cause, program.externalCause());
            missing.addAll(verifySourceContext(program.ownedDocumentIds(), program.environment(), program.executionPolicy(), sourceBases));
        }
        for (SourceOperationFailure failure : failures) {
            cause = sameSourceCause(cause, failure.externalCause());
            missing.addAll(verifySourceContext(failure.ownedDocumentIds(), failure.environment(), failure.executionPolicy(), sourceBases));
            failure.verifyObservationBasis(directed, owned, evidence.sourceGaps());
        }
        if (!missing.isEmpty()) return new NeedEvidence(missing.stream().distinct().sorted().toList());
        if (programs.isEmpty()) {
            List<ManagedImportLane.Delta> deltas = selection.due().stream().map(due -> due.finish(selection.context().identity(),
                    ManagedImportLane.Outcome.SOURCE_FAILURE, due.cursor().successfulBlueId(), due.cursor().successfulEpoch())).toList();
            return new ManagedProgress(selection.context(), evidence.fences(), deltas);
        }
        List<DirectLogicalDelivery> deliveries;
        try (BlueClosureContracts contracts = new BlueClosureContracts(processor)) {
            deliveries = contracts.selectDirectDeliveries(directed, Objects.requireNonNull(cause).event(), programs, failures).stream()
                    .filter(delivery -> !owned.contains(delivery.targetDocumentId())).toList();
        }
        ClosureInvocationInput invocation = ClosureEvidenceFactory.processClosure(directed, Objects.requireNonNull(cause), deliveries, executionPolicy, environment);
        Map<DocumentId, String> predecessors = new java.util.TreeMap<>();
        evidence.precedingOperations().forEach((lineage, previous) -> { if (directed.managedDocument(lineage) != null) predecessors.put(lineage, previous); });
        for (SourceObservationProgram program : programs) for (DocumentId id : program.ownedDocumentIds()) predecessors.put(id, program.invocationIdentity());
        for (SourceOperationFailure failure : failures) for (DocumentId id : failure.ownedDocumentIds()) predecessors.put(id, failure.invocationIdentity());
        invocation = ClosureEvidenceFactory.withSemanticPredecessors(invocation, predecessors).withManagedReaction(selection.context());
        Capture capture = new Capture();
        ClosureAttemptResult attempt;
        try (BlueClosureContracts contracts = new BlueClosureContracts(processor, capture)) {
            attempt = contracts.processManagedReaction(invocation, owned, programs, evidence.sourceGaps(), failures, selection.context(), sourceBases);
        }
        if (!attempt.isComplete()) {
            Set<String> needs = new TreeSet<>(attempt.requiredExactBlueIds());
            attempt.resourceDemands().forEach(demand -> needs.add(demand.demandIdentity()));
            return new NeedEvidence(List.copyOf(needs), attempt.resourceDemands());
        }
        ClosureProcessResult result = attempt.processResult();
        Disposition disposition = result.commits() || result.status() == ProcessorStatus.GAS_LIMIT_EXCEEDED
                || result.status() == ProcessorStatus.RUNTIME_FATAL ? Disposition.CONSUMED : Disposition.BLOCKED;
        List<LineageProjection> projections = new ArrayList<>();
        for (ResultingDocument after : result.resultingDocuments()) {
            if (!owned.contains(after.documentId())) continue;
            ManagedDocumentSnapshot before = evidence.snapshot().managedDocument(after.documentId());
            projections.add(new LineageProjection(after.documentId(), evidence.precedingOperations().get(after.documentId()),
                    before.blueId(), after.afterBlueId(), before.epoch(), after.epoch(), before.publicRoot(), after));
        }
        Map<String, ManagedOccurrenceBinding> afterBindings = new java.util.HashMap<>();
        for (ManagedOccurrenceBinding binding : result.occurrenceBindings()) afterBindings.put(binding.occurrenceIdentity(), binding);
        List<ManagedImportLane.Delta> deltas = new ArrayList<>();
        Map<DocumentId, SourcePin> pins = new java.util.TreeMap<>();
        if (disposition == Disposition.CONSUMED) for (ManagedImportLane.Due due : selection.due()) {
            ManagedImportLane.Outcome outcome;
            if (!result.commits()) outcome = result.status() == ProcessorStatus.GAS_LIMIT_EXCEEDED
                    ? ManagedImportLane.Outcome.GAS_LIMIT_EXCEEDED : ManagedImportLane.Outcome.RUNTIME_FATAL;
            else if (due.source().status() != ProcessorStatus.SUCCESS) outcome = ManagedImportLane.Outcome.SOURCE_FAILURE;
            else {
                ManagedOccurrenceBinding after = afterBindings.get(due.cursor().descriptor().occurrenceIdentity());
                outcome = after == null || !after.targetDocumentId().equals(due.source().sourceLineage())
                        || !after.active() && after.pendingHistoricalEpoch() == null
                        ? ManagedImportLane.Outcome.RETIRED : ManagedImportLane.Outcome.APPLIED;
                if (outcome == ManagedImportLane.Outcome.APPLIED && (!after.expectedTargetBlueId().equals(due.source().afterBlueId())
                        || after.pendingHistoricalEpoch() != null && after.pendingHistoricalEpoch() != due.source().afterEpoch()))
                    throw new IllegalStateException("Successful managed reaction did not retain its selected exact source result");
            }
            deltas.add(due.finish(invocation.invocationIdentity(), outcome, due.source().afterBlueId(), due.source().afterEpoch()));
            if (outcome == ManagedImportLane.Outcome.APPLIED) pins.put(due.source().sourceLineage(), new SourcePin(due.source().sourceLineage(),
                    due.source().operationIdentity(), due.source().afterBlueId(), due.source().afterEpoch()));
        }
        return new PreparedOperation(invocation.invocationIdentity(), OperationKind.MANAGED_RECEIPT_IMPORT, disposition,
                Optional.empty(), evidence.fences(), invocation, result, projections, Optional.ofNullable(capture.program),
                List.copyOf(pins.values()), consumedSources(programs, failures), Optional.of(selection.context()), deltas);
    }

    private static ExternalEventCause sameSourceCause(ExternalEventCause previous, ExternalEventCause next) {
        if (next == null || previous != null && (!previous.causeIdentity().equals(next.causeIdentity())
                || !previous.sourceOrder().equals(next.sourceOrder())))
            throw new IllegalArgumentException("One managed reaction cannot merge distinct original source causes");
        return next;
    }

    /** An ambient head never supplies channels for an older exact occurrence pin without a selected source operation explaining that cut. */
    private record LogicalHeaderSelection(Map<DocumentId, Set<String>> origins, List<String> needs) { }

    private LogicalHeaderSelection logicalHeaderOrigins(AffectedClosureSnapshot cut, Set<DocumentId> live,
            EvaluationEvidence evidence, BlueClosureContracts contracts) {
        Map<DocumentId, Set<String>> explained = new HashMap<>();
        Set<String> missing = new TreeSet<>();
        for (ManagedOccurrenceBinding binding : cut.occurrences()) {
            if (binding.active() && live.contains(binding.sourceDocumentId()) && live.contains(binding.targetDocumentId())
                    && !binding.expectedTargetBlueId().equals(cut.managedDocument(binding.targetDocumentId()).blueId()))
                explained.putIfAbsent(binding.targetDocumentId(), new HashSet<>());
        }
        List<SourceObservationProgram> programs = explained.isEmpty() ? List.of() : expandBorrowedPrograms(evidence.sourcePrograms());
        for (DocumentId source : explained.keySet()) {
            ManagedDocumentSnapshot current = cut.managedDocument(source);
            for (SourceObservationProgram program : programs) {
                if (!program.ownedDocumentIds().contains(source) || program.externalCause() == null) continue;
                var before = program.sourcePredecessors().stream().filter(value -> value.documentId().equals(source)).findFirst().orElseThrow();
                var after = program.sourceResults().stream().filter(value -> value.documentId().equals(source)).findFirst().orElseThrow();
                boolean atBefore = current.blueId().equals(before.blueId()) && current.epoch() == before.epoch();
                boolean atAfter = current.blueId().equals(after.blueId()) && current.epoch() == after.epoch();
                if (!atBefore && !atAfter) continue;
                List<String> contextNeeds = verifySourceContext(program.ownedDocumentIds(), program.environment(),
                        program.executionPolicy(), evidence.expectedSourceBases());
                if (!contextNeeds.isEmpty()) { missing.addAll(contextNeeds); continue; }
                // If the completed operation changes membership, its post-state cannot
                // select its own earlier input. Request the exact predecessor header cut.
                if (atBefore || atAfter && before.initialized() == after.initialized()
                        && activeOutgoingCut(program.sourceBeforeBindings(), source)
                        .equals(activeOutgoingCut(program.sourceAfterBindings(), source))
                        && sameRootSelectionHeaders(contracts, before.document(), after.document()))
                    explained.get(source).add(program.externalCause().eventBlueId());
            }
            for (SourceOperationFailure failure : evidence.sourceFailures()) {
                if (!failure.ownedDocumentIds().contains(source) || failure.externalCause() == null) continue;
                var before = failure.sourcePredecessors().stream().filter(value -> value.documentId().equals(source)).findFirst().orElseThrow();
                if (current.blueId().equals(before.blueId()) && current.epoch() == before.epoch()) {
                    List<String> contextNeeds = verifySourceContext(failure.ownedDocumentIds(), failure.environment(),
                            failure.executionPolicy(), evidence.expectedSourceBases());
                    if (!contextNeeds.isEmpty()) { missing.addAll(contextNeeds); continue; }
                    explained.get(source).add(failure.externalCause().eventBlueId());
                }
            }
        }
        return new LogicalHeaderSelection(explained, List.copyOf(missing));
    }

    private static boolean hasTerminationMarker(blue.language.model.Node document) {
        return blue.language.model.NodePathEditor.getOrNull(document,
                blue.language.processor.util.ProcessorPointerConstants.RELATIVE_TERMINATED) != null;
    }

    private static boolean sameRootSelectionHeaders(BlueClosureContracts contracts,
            blue.language.model.Node before, blue.language.model.Node after) {
        return hasTerminationMarker(before) == hasTerminationMarker(after)
                && externalHeaders(contracts.projectRootSubscriptionSurface(before))
                .equals(externalHeaders(contracts.projectRootSubscriptionSurface(after)));
    }

    private static Map<String, List<String>> externalHeaders(blue.language.processor.ManagedRootSubscriptionSurface surface) {
        Map<String, List<String>> headers = new java.util.TreeMap<>();
        for (var channel : surface.channelOccurrences()) if (channel.externalSource())
            headers.put(channel.rawChannelKey(), List.of(channel.effectiveTypeBlueId(), channel.subscriptionHeaderBlueId()));
        return headers;
    }

    private static List<String> activeOutgoingCut(List<ManagedOccurrenceBinding> bindings, DocumentId source) {
        // An unchanged root channel list does not imply unchanged directed membership:
        // adding/removing an embedded child may add/remove all of that child's Timelines.
        // Include the selected exact reference as its own historical channels may differ.
        return bindings.stream().filter(binding -> binding.active() && source.equals(binding.sourceDocumentId()))
                .map(ManagedOccurrenceBinding::bindingIdentity).sorted().toList();
    }

    private List<String> verifySourceContext(Set<DocumentId> owners, ClosureEnvironment actual, ExecutionPolicy producerPolicy,
                                             Map<DocumentId, String> expectedBases) {
        SourceExecutionBasis.requireCompatibleEnvironment(environment, actual);
        List<String> needs = new ArrayList<>();
        for (DocumentId source : owners) {
            String basis = expectedBases.get(source);
            if (basis == null) needs.add("source-execution-basis:" + source.value());
            else SourceExecutionBasis.requireProducerBasis(basis, source, actual, producerPolicy);
        }
        return needs.stream().sorted().toList();
    }

    private static List<String> consumedSources(List<SourceObservationProgram> programs,
            List<blue.language.processor.closure.SourceOperationFailure> failures) {
        Set<String> operations = new TreeSet<>();
        for (SourceObservationProgram program : programs) operations.add(program.invocationIdentity());
        for (var failure : failures) operations.add(failure.invocationIdentity());
        return List.copyOf(operations);
    }

    /** Expand authenticated borrowed links once; this inventory order never selects execution order. */
    private static List<SourceObservationProgram> expandBorrowedPrograms(List<SourceObservationProgram> roots) {
        Map<String, SourceObservationProgram> selected = new java.util.TreeMap<>();
        Map<SourceObservationProgram, String> digests = new java.util.IdentityHashMap<>();
        ArrayDeque<SourceObservationProgram> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            SourceObservationProgram program = pending.removeFirst();
            SourceObservationProgram previous = selected.putIfAbsent(program.invocationIdentity(), program);
            if (previous != null) {
                if (previous != program) {
                    var limits = blue.language.processor.closure.FrozenNodeEvidenceCodec.Limits.defaults();
                    String before = digests.computeIfAbsent(previous, p -> blue.language.processor.closure.SourceObservationProgramCodec.encode(p, (id, bytes) -> { }, limits));
                    String offered = digests.computeIfAbsent(program, p -> blue.language.processor.closure.SourceObservationProgramCodec.encode(p, (id, bytes) -> { }, limits));
                    if (!before.equals(offered)) throw new IllegalArgumentException("Conflicting borrowed program for one source operation");
                }
                continue;
            }
            pending.addAll(program.borrowedPrograms());
        }
        return List.copyOf(selected.values());
    }

    private static AffectedClosureSnapshot directedSnapshot(DocumentId seed, AffectedClosureSnapshot supplied) {
        return directedSnapshot(Set.of(seed), supplied);
    }

    private static AffectedClosureSnapshot directedSnapshot(Set<DocumentId> seeds, AffectedClosureSnapshot supplied) {
        return directedSnapshot(seeds, supplied, true);
    }

    private static AffectedClosureSnapshot directedSnapshot(Set<DocumentId> seeds, AffectedClosureSnapshot supplied, boolean activeOnly) {
        Set<DocumentId> members = directedMemberIds(seeds, supplied, activeOnly);
        List<ManagedDocumentSnapshot> documents = supplied.managedDocuments().stream()
                .filter(d -> members.contains(d.documentId())).toList();
        List<ManagedOccurrenceBinding> bindings = supplied.occurrences().stream()
                .filter(b -> members.contains(b.sourceDocumentId()) && members.contains(b.targetDocumentId())).toList();
        List<ComponentSnapshot> components = supplied.components().stream()
                .filter(c -> members.containsAll(c.orderedMemberDocumentIds())).toList();
        List<DocumentId> publicRoots = supplied.publicRootDocumentIds().stream().filter(members::contains).toList();
        // A cut-local generation prevents unrelated host graph changes from
        // entering invocation identity; actual component generations remain exact.
        long generation = documents.stream().mapToLong(ManagedDocumentSnapshot::componentGeneration).max().orElse(0L);
        return ClosureEvidenceFactory.affectedClosure(generation, documents, bindings, components, publicRoots,
                supplied.readPins().stream().filter(pin -> members.contains(pin.documentId())).toList());
    }

    private static Set<DocumentId> directedMemberIds(Set<DocumentId> seeds, AffectedClosureSnapshot supplied, boolean activeOnly) {
        Set<DocumentId> members = new HashSet<>();
        Map<DocumentId, List<DocumentId>> dependencies = new java.util.HashMap<>();
        for (ManagedOccurrenceBinding binding : supplied.occurrences()) {
            if (!activeOnly || binding.active()) dependencies.computeIfAbsent(binding.sourceDocumentId(), ignored -> new ArrayList<>())
                    .add(binding.targetDocumentId());
        }
        ArrayDeque<DocumentId> pending = new ArrayDeque<>();
        pending.addAll(seeds);
        while (!pending.isEmpty()) {
            DocumentId current = pending.removeFirst();
            if (!members.add(current)) continue;
            pending.addAll(dependencies.getOrDefault(current, List.of()));
        }
        return members;
    }

    private static void validateTimelineEntry(String timeline, long micros, ExactValue entry, ExactValue event) {
        if (!entry.blueId().equals(event.blueId())) {
            throw new IllegalArgumentException("External event must be the exact complete Timeline Entry");
        }
        var node = entry.copyNode();
        if (node.getType() == null || !blue.repo.coordination.TimelineEntry.blueId().equals(node.getType().getBlueId())) {
            throw new IllegalArgumentException("Expected the current typed Coordination/Timeline Entry");
        }
        var time = blue.language.model.NodePathEditor.getOrNull(node, "/timestamp");
        var source = blue.language.model.NodePathEditor.getOrNull(node, "/timeline");
        var locator = blue.language.model.NodePathEditor.getOrNull(node, "/timeline/timelineId");
        if (time == null || !(time.getValue() instanceof java.math.BigInteger)
                || !java.math.BigInteger.valueOf(micros).equals(time.getValue())
                || source == null || source.getType() == null
                || !blue.repo.myos.MyOSTimeline.blueId().equals(source.getType().getBlueId())
                || locator == null || !timeline.equals(locator.getValue())) {
            throw new IllegalArgumentException("Timeline identity or timestamp disagrees with exact Entry content");
        }
        for (String field : List.of("/actor", "/message", "/source")) {
            if (blue.language.model.NodePathEditor.getOrNull(node, field) == null) {
                throw new IllegalArgumentException("Timeline Entry is missing " + field);
            }
        }
    }

    private static AffectedClosureSnapshot canonicalSemanticSnapshot(AffectedClosureSnapshot supplied) {
        return supplied.canonicalSemanticView();
    }

    private static final class Capture implements ClosureExecutionObserver {
        private SourceObservationProgram program;
        @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
        @Override public boolean capturesSourceObservationProgram() { return true; }
        @Override public void onSourceObservationProgram(SourceObservationProgram value) { program = value; }
    }
}
