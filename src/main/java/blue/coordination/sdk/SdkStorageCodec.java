package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.DocumentId;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.ExactValueStorageCodec;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.Timeline;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.NoncommittingExecutionException;
import java.math.BigInteger;
import java.util.Collections;
import blue.language.snapshot.ExactNodeStorageCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;

import static blue.language.snapshot.ExactNodeStorageCodec.*;

/**
 * Closed SDK metadata storage selected through a trusted engine/host root.
 * DTO field mappings selectively reuse the earlier experimental SDK codec; the
 * old engine checkpoint and unrooted transport are not reused. No class loading,
 * reflection, handler execution or authority construction occurs here.
 */
final class SdkStorageCodec {
    static final String FORMAT = "blue-coordination/rooted-sdk-metadata/1";
    private final Object owner;
    private final ExactNodeStorageCodec envelope;
    private final ExactValueStorageCodec exact;

    SdkStorageCodec(Object owner, int maximumBytes) {
        this.owner = Objects.requireNonNull(owner, "owner");
        envelope = new ExactNodeStorageCodec(maximumBytes, 128);
        exact = new ExactValueStorageCodec(maximumBytes, 128);
    }

    record Configuration(String language, String contracts, boolean bundledRelease,
            boolean contentDerivedDocumentIds, ContractsExecutionPolicy policy,
            Map<String, String> bundledIdentities) {
        Configuration {
            Objects.requireNonNull(language); Objects.requireNonNull(contracts); Objects.requireNonNull(policy);
            bundledIdentities = immutable(bundledIdentities);
        }
    }

    record Metadata(Configuration configuration, Map<String, TimelineHandle> timelines,
            Map<String, SdkCoordinationRuntime.EntryIntent> intents, Map<String, EntryResult> results,
            Map<String, CoreEntrySnapshot> entries, Map<SourceHistoryPrerequisite, DrainResult> sourceResults) {
        Metadata {
            Objects.requireNonNull(configuration);
            timelines = immutable(timelines); intents = immutable(intents); results = immutable(results);
            entries = immutable(entries); sourceResults = immutable(sourceResults);
        }
    }

    // This is data, not a TimelineEntry authority factory. Install must resolve the
    // corresponding row through the supplied engine's verified journal and compare it.
    record CoreEntrySnapshot(ExactBlueValue exactEvent, Optional<ExactBlueValue> request,
            ExternalOrderKey journalOrderKey, ExternalOrderKey sourceOrderKey, Timeline timeline,
            String operation, String channel, long timestampMicros, long globalSequence, long timelineSequence) {
        CoreEntrySnapshot {
            Objects.requireNonNull(exactEvent); Objects.requireNonNull(request); Objects.requireNonNull(journalOrderKey);
            Objects.requireNonNull(sourceOrderKey); Objects.requireNonNull(timeline); Objects.requireNonNull(operation);
            Objects.requireNonNull(channel);
            if (timestampMicros <= 0 || globalSequence <= 0 || timelineSequence <= 0)
                throw invalid("Invalid stored entry coordinates");
        }
        static CoreEntrySnapshot from(TimelineEntry entry) {
            return new CoreEntrySnapshot(ExactBlueValue.wrap(entry.exactEvent()), entry.request().map(ExactBlueValue::wrap),
                    entry.journalOrderKey(), entry.sourceOrderKey(), entry.timeline(), entry.operation(), entry.channel(),
                    entry.timestampMicros(), entry.globalSequence(), entry.timelineSequence());
        }
    }

    private static <K,V> Map<K,V> immutable(Map<K,V> values) {
        LinkedHashMap<K,V> owned = new LinkedHashMap<>();
        Objects.requireNonNull(values).forEach((key, value) -> owned.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        return Collections.unmodifiableMap(owned);
    }

    byte[] encode(Object value) {
        return physical(() -> envelope.encodeEnvelope(FORMAT, out -> new W(out).w(value)));
    }
    <T> T decode(byte[] bytes, Class<T> type) {
        return physical(() -> {
            T value = type.cast(envelope.decodeEnvelope(bytes, FORMAT, in -> new R(in).r()));
            if (!Arrays.equals(bytes, encode(value))) throw invalid("Noncanonical SDK evidence");
            return value;
        });
    }
    private static <T> T physical(Supplier<T> operation) {
        try { return operation.get(); }
        catch (NoncommittingExecutionException failure) { throw failure; }
        catch (RuntimeException failure) { throw new CoordinationObjectStorageException("Invalid SDK storage evidence", failure); }
    }
    private static CoordinationObjectStorageException invalid(String message) {
        return new CoordinationObjectStorageException(message);
    }

    private final class W {
        private final DataOutputStream out;
        private int depth;
        W(DataOutputStream out) { this.out = out; }
        void fields(int tag, Object... fields) throws IOException {
            out.writeInt(tag); for (Object field : fields) w(field);
        }
        void w(Object value) throws IOException {
            if (++depth > 128) throw invalid("SDK evidence depth bound exceeded");
            try { write(value); } finally { depth--; }
        }
        void write(Object value) throws IOException {
            if (value == null) { out.writeInt(0); return; }
            if (value instanceof String v) { out.writeInt(1); writeText(out, v); return; }
            if (value instanceof Long v) { out.writeInt(2); out.writeLong(v); return; }
            if (value instanceof Integer v) { out.writeInt(3); out.writeInt(v); return; }
            if (value instanceof Boolean v) { out.writeInt(4); out.writeBoolean(v); return; }
            if (value instanceof byte[] v) { out.writeInt(5); writeBytes(out, v); return; }
            if (value instanceof List<?> v) {
                out.writeInt(6); out.writeInt(v.size()); for (Object item : v) w(item); return;
            }
            if (value instanceof Map<?, ?> v) {
                out.writeInt(7); out.writeInt(v.size());
                for (Map.Entry<?, ?> item : v.entrySet()) { w(item.getKey()); w(item.getValue()); } return;
            }
            if (value instanceof Optional<?> v) { fields(8, v.orElse(null)); return; }
            if (value instanceof DocumentId v) { fields(9, v.value()); return; }
            if (value instanceof ExactBlueValue v) { out.writeInt(10); writeBytes(out, exact.encode(v.unwrap())); return; }
            if (value instanceof TimelineHandle v) { fields(12, v.id(), v.accountId(), v.actorKind()); return; }
            if (value instanceof EntryHandle v) {
                fields(13, v.blueId(), v.timeline().orElse(null),
                        v.globalSequence().isPresent() ? v.globalSequence().getAsLong() : null,
                        v.timelineSequence().isPresent() ? v.timelineSequence().getAsLong() : null); return;
            }
            if (value instanceof SdkCoordinationRuntime.EntryIntent v) {
                fields(14, v.targeted(), v.targetId(), v.expectedTargetBlueId(), v.targetPresentAtSubmission(),
                        v.operation(), v.channel(), v.timelineId(), v.actorId()); return;
            }
            if (value instanceof EntryResult v) { fields(15, v.entry(), v.disposition(), v.closures(), v.publicEvents(), v.stats(), v.diagnostic()); return; }
            if (value instanceof ClosureResult v) {
                fields(16, v.closureId(), v.disposition(), v.changes(), v.publicEvents(), v.stats(), v.diagnostic(),
                        v.resourceDemands(), v.processorAttemptCount(), v.managedSurfaceEvidence()); return;
            }
            if (value instanceof DocumentChange v) { fields(17, v.documentId(), v.epoch(), v.before().orElse(null), v.after(), v.publicEvents()); return; }
            if (value instanceof PublicEvent v) { fields(18, v.exact(), v.sourceDocument().orElse(null), v.occurrencePath().orElse(null)); return; }
            if (value instanceof ProcessingStats v) { fields(19, v.gas(), v.committedTransitions(), v.documentsOpened(), v.elapsedNanos(), v.documentStepOrder(), v.counters()); return; }
            if (value instanceof Diagnostic v) { fields(20, v.code(), v.message(), v.details()); return; }
            if (value instanceof ClosureResult.ResourceDemand v) {
                fields(21, v.kind(), v.demandIdentity(), v.blueId(), v.sourceDocumentId(), v.sourcePath(),
                        v.managedResolutionStatus(), v.managedResolutionDiagnostic()); return;
            }
            if (value instanceof ManagedSurfaceEvidence v) {
                fields(22, v.graphGeneration(), v.resolvedOccurrences(), v.graphChanges(), v.componentTransitions(),
                        v.subscriptionChanges(), v.documentTransitions(), v.operationRouteChanges()); return;
            }
            if (value instanceof ManagedSurfaceEvidence.OccurrenceResolution v) {
                fields(23, v.demandIdentity(), v.occurrenceIdentity(), v.bindingIdentity(), v.occurrence(), v.kind(), v.authoredInitial()); return;
            }
            if (value instanceof ClosureOccurrenceSnapshot v) {
                fields(24, v.sourceDocumentId(), v.sourcePath(), v.activationGeneration(), v.targetDocumentId(), v.expectedTargetBlueId(), v.active()); return;
            }
            if (value instanceof ManagedSurfaceEvidence.GraphSide v) {
                fields(25, v.activationGeneration(), v.occurrenceIdentity(), v.bindingIdentity(), v.targetDocumentId(), v.targetBlueId()); return;
            }
            if (value instanceof ManagedSurfaceEvidence.GraphChange v) { fields(26, v.ordinal(), v.kind(), v.sourceDocumentId(), v.sourcePath(), v.before(), v.after()); return; }
            if (value instanceof ManagedSurfaceEvidence.ComponentState v) {
                fields(27, v.componentIdentity(), v.componentStateIdentity(), v.componentGeneration(), v.kind(), v.memberDocumentIds(), v.memberBlueIds(), v.masterBlueId(), v.cyclicProofIdentity()); return;
            }
            if (value instanceof ManagedSurfaceEvidence.ComponentTransition v) { fields(28, v.kind(), v.before(), v.after()); return; }
            if (value instanceof ManagedSurfaceEvidence.OperationRouteState v) { fields(29, v.scopePath(), v.operation(), v.channel(), v.acceptedSources()); return; }
            if (value instanceof TimelineSourceSnapshot v) { fields(30, v.timelineId(), v.actorId()); return; }
            if (value instanceof ManagedSurfaceEvidence.OperationRouteChange v) { fields(31, v.ordinal(), v.kind(), v.documentId(), v.before(), v.after()); return; }
            if (value instanceof ManagedSurfaceEvidence.SubscriptionState v) {
                fields(32, v.subscriptionIdentity(), v.channelOccurrenceIdentity(), v.managedDocumentId(), v.scopePath(),
                        v.scopeActivationGeneration(), v.rawChannelKey(), v.effectiveRuntimeContributionBlueId(),
                        v.subscriptionHeaderBlueId(), v.documentBlueId(), v.graphGeneration(), v.componentGeneration()); return;
            }
            if (value instanceof ManagedSurfaceEvidence.SubscriptionChange v) { fields(33, v.ordinal(), v.operation(), v.targetManagedScopeIdentity(), v.channelOccurrenceIdentity(), v.before(), v.after()); return; }
            if (value instanceof ManagedSurfaceEvidence.ContractPatch v) { fields(34, v.operation(), v.path(), v.authoredValueBlueId(), v.beforeValueBlueId(), v.afterValueBlueId()); return; }
            if (value instanceof ManagedSurfaceEvidence.GeneralizationWrite v) { fields(35, v.path(), v.valueBlueId(), v.requiringPatchIndex()); return; }
            if (value instanceof ManagedSurfaceEvidence.DocumentTransition v) {
                fields(36, v.documentId(), v.workOccurrenceIdentity(), v.beforeDocumentBlueId(), v.afterDocumentBlueId(),
                        v.beforeEffectiveTypeBlueId(), v.afterEffectiveTypeBlueId(), v.authoredContractPatches(), v.generatedGeneralizationWrites()); return;
            }
            if (value instanceof Metadata v) { fields(37, v.configuration(), v.timelines(), v.intents(), v.results(), v.entries(), v.sourceResults()); return; }
            if (value instanceof ContractsExecutionPolicy v) { fields(38, v.sharedGasLimit(), v.label()); return; }
            if (value instanceof TimelineActorKind v) { fields(50, v.name()); return; }
            if (value instanceof EntryDisposition v) { fields(51, v.name()); return; }
            if (value instanceof ClosureResult.ManagedResolutionStatus v) { fields(52, v.name()); return; }
            if (value instanceof ManagedSurfaceEvidence.ResolutionKind v) { fields(53, v.name()); return; }
            if (value instanceof ManagedSurfaceEvidence.GraphChangeKind v) { fields(54, v.name()); return; }
            if (value instanceof ManagedSurfaceEvidence.ComponentKind v) { fields(55, v.name()); return; }
            if (value instanceof ManagedSurfaceEvidence.ComponentTransitionKind v) { fields(56, v.name()); return; }
            if (value instanceof ManagedSurfaceEvidence.OperationRouteChangeKind v) { fields(57, v.name()); return; }
            if (value instanceof ManagedSurfaceEvidence.SubscriptionOperation v) { fields(58, v.name()); return; }
            if (value instanceof ManagedSurfaceEvidence.ContractPatchOperation v) { fields(59, v.name()); return; }
            if (value instanceof Configuration v) { fields(39, v.language(), v.contracts(), v.bundledRelease(), v.contentDerivedDocumentIds(), v.policy(), v.bundledIdentities()); return; }
            if (value instanceof CoreEntrySnapshot v) { fields(40, v.exactEvent(), v.request(), v.journalOrderKey(), v.sourceOrderKey(), v.timeline(), v.operation(), v.channel(), v.timestampMicros(), v.globalSequence(), v.timelineSequence()); return; }
            if (value instanceof Timeline v) { fields(41, v.timelineId(), v.actorId()); return; }
            if (value instanceof SourceHistoryPrerequisite v) { fields(42, v.selectionIdentity(), v.requestingRoot(), v.requestingInvocationIdentity(), v.demandIdentity(), v.sourceDocumentId(), v.authoredBlueId(), v.cutoffExclusive(), v.kind(), v.sourceEpoch(), v.sourceBlueId(), v.workIdentity(), v.entryBlueId(), v.journalRevision(), v.routeGeneration(), v.sourceSurfaceIdentity(), v.diagnostic()); return; }
            if (value instanceof DrainResult v) { fields(43, v.entries(), v.stats(), v.quiescent(), v.paused(), v.diagnostic(), v.managedEpochApplications(), v.managedEpochApplicationAttempts(), v.managedEpochEvidenceFailures(), v.rootedRetainedApplications()); return; }
            if (value instanceof DrainResult.RootedRetainedApplication v) { fields(44, v.rootDocumentId(), v.work(), v.result()); return; }
            if (value instanceof ManagedEpochApplicationWork v) { fields(60, v.workIdentity(), v.planIdentity(), v.barrierIdentity(), v.sourceReceiptIdentity(), v.sourceDocumentId(), v.sourceEpoch(), v.consumerDocumentId(), v.targetOccurrenceIdentity(), v.targetPath(), v.activationGeneration(), v.expectedConsumerCommittedEpoch(), v.expectedConsumerCommittedBlueId(), v.expectedGraphGeneration(), v.representationStep(), v.successorRepresentationStep()); return; }
            if (value instanceof ManagedEpochApplicationWork.Position v) { fields(61, v.anchorReceiptIdentity(), v.positionIdentity(), v.targetPositionIdentity(), v.nextRevisionReceiptIdentity()); return; }
            if (value instanceof ManagedEpochApplicationWork.RepresentationStep v) { fields(62, v.causeIdentity(), v.beforeBlueId(), v.afterBlueId(), v.before(), v.after(), v.terminal()); return; }
            if (value instanceof ManagedEpochApplicationReceipt v) { fields(63, v.applicationReceiptIdentity(), v.workIdentity(), v.planIdentity(), v.sourceReceiptIdentity(), v.contractsInvocationIdentity(), v.contractsResultIdentity(), v.commitCompanionIdentity(), v.consumerDocumentId(), v.consumerRevisionEpoch(), v.consumerRevisionReceiptIdentity(), v.consumerCommittedBlueId(), v.resultingSourceCursor(), v.representationCauseIdentity(), v.resultingRepresentationCursor(), v.successorRepresentationCauseIdentity()); return; }
            if (value instanceof ManagedEpochApplicationAttempt v) { fields(64, v.work(), v.attempt(), v.published(), v.replayed(), v.receipt(), v.automaticRetryCount(), v.automaticResolutionStopReason(), v.managedOccurrenceResolutionIssues(), v.publicationFailure(), v.managedSurfaceEvidence()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.PublicationFailure v) { fields(65, v.code(), v.message(), v.details()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.ProcessorAttempt v) { fields(66, v.complete(), v.processResult(), v.resourceDemands(), v.requiredExactBlueIds()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.ProcessResult v) { fields(67, v.status(), v.commits(), v.atomic(), v.rollbackToInput(), v.invocationIdentity(), v.inputClosureIdentity(), v.outputClosureIdentity(), v.graphGeneration(), v.totalGas(), v.gasTrace(), v.gasTraceIdentity(), v.rejectedWorkOccurrence(), v.rejectedCharge(), v.publicEvents(), v.diagnostic()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.GasCharge v) { fields(68, v.sequence(), v.namespace(), v.counter(), v.quantity(), v.weight(), v.subtotal(), v.documentId(), v.scopePath(), v.activationGeneration(), v.componentGeneration(), v.contractKey(), v.logicalPath(), v.workOccurrenceIdentity(), v.reason()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.RejectedWorkOccurrence v) { fields(69, v.ordinal(), v.kind(), v.targetDocumentId(), v.channelKey(), v.eventBlueId(), v.occurrenceOrdinal(), v.targetManagedScopeIdentity(), v.sourceOccurrenceIdentity(), v.workIdentity()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.RejectedCharge v) { fields(70, v.rejectedChargeIdentity(), v.namespace(), v.counter(), v.quantity(), v.weight(), v.subtotal(), v.capKind(), v.capDocumentId(), v.remainingBeforeCharge(), v.ownerKind(), v.workOccurrenceIdentity(), v.finalizationOrdinal(), v.componentIdentity(), v.componentGeneration()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.ResourceDemand v) { fields(71, v.kind(), v.demandIdentity(), v.sourceDocumentId(), v.sourcePath(), v.suppliedValueBlueId()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.ManagedOccurrenceResolutionIssue v) { fields(72, v.demandIdentity(), v.status(), v.diagnostic()); return; }
            if (value instanceof ManagedEpochEvidenceFailure v) { fields(73, v.work(), v.status(), v.code(), v.diagnostic()); return; }
            if (value instanceof ExternalOrderKey v) { fields(45, v.components()); return; }
            if (value instanceof BigInteger v) { out.writeInt(46); writeBytes(out, v.toByteArray()); return; }
            if (value instanceof SourceHistoryPrerequisite.Kind v) { fields(90, v.name()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.PublicationFailureCode v) { fields(91, v.name()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.AutomaticResolutionStopReason v) { fields(92, v.name()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.Status v) { fields(93, v.name()); return; }
            if (value instanceof ManagedEpochApplicationAttempt.ResolutionStatus v) { fields(94, v.name()); return; }
            if (value instanceof ManagedEpochEvidenceFailure.Status v) { fields(95, v.name()); return; }
            throw invalid("Unsupported SDK evidence type " + value.getClass().getName());
        }
    }

    private final class R {
        private final DataInputStream in;
        private int depth;
        R(DataInputStream in) { this.in = in; }
        @SuppressWarnings("unchecked") <T> T r() throws IOException {
            if (++depth > 128) throw invalid("SDK evidence depth bound exceeded");
            try { return (T) read(); } finally { depth--; }
        }
        int count(int minimumBytes) throws IOException {
            int n = in.readInt(); if (n < 0 || n > in.available() / minimumBytes) throw invalid("Invalid SDK collection length"); return n;
        }
        long number() throws IOException { return this.<Long>r(); }
        boolean flag() throws IOException { return this.<Boolean>r(); }
        Object read() throws IOException {
            switch (in.readInt()) {
                case 0: return null;
                case 1: return readText(in);
                case 2: return in.readLong();
                case 3: return in.readInt();
                case 4: return readBoolean(in);
                case 5: return readBytes(in);
                case 6: { int n = count(4); List<Object> list = new ArrayList<>(); for (int i = 0; i < n; i++) list.add(r()); return list; }
                case 7: {
                    int n = count(8); Map<Object, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < n; i++) { Object key = r(); if (map.containsKey(key)) throw invalid("Duplicate SDK map key"); map.put(key, r()); } return map;
                }
                case 8: return Optional.ofNullable(r());
                case 9: return new DocumentId(r());
                case 10: return ExactBlueValue.wrap(exact.decode(readBytes(in)));
                case 12: return new TimelineHandle(owner, r(), r(), r());
                case 13: {
                    String blueId = r(); TimelineHandle timeline = r(); Long global = r(); Long local = r();
                    if (timeline == null) {
                        if (global != null || local != null) throw invalid("Partial SDK handle evidence");
                        return new EntryHandle(owner, blueId);
                    }
                    if (global == null || local == null) throw invalid("Partial SDK handle evidence");
                    return new EntryHandle(owner, timeline, blueId, global.longValue(), local.longValue());
                }
                case 14: return new SdkCoordinationRuntime.EntryIntent(flag(), r(), r(), flag(), r(), r(), r(), r());
                case 15: return new EntryResult(r(), r(), r(), r(), r(), r());
                case 16: return new ClosureResult(r(), r(), r(), r(), r(), r(), r(), number(), r());
                case 17: return new DocumentChange(r(), number(), r(), r(), r());
                case 18: return new PublicEvent(r(), r(), r());
                case 19: return new ProcessingStats(number(), number(), number(), number(), r(), r());
                case 20: return new Diagnostic(r(), r(), r());
                case 21: return new ClosureResult.ResourceDemand(r(), r(), r(), r(), r(), r(), r());
                case 22: return new ManagedSurfaceEvidence(number(), r(), r(), r(), r(), r(), r());
                case 23: return new ManagedSurfaceEvidence.OccurrenceResolution(r(), r(), r(), r(), r(), r());
                case 24: return new ClosureOccurrenceSnapshot(r(), r(), number(), r(), r(), flag());
                case 25: return new ManagedSurfaceEvidence.GraphSide(number(), r(), r(), r(), r());
                case 26: return new ManagedSurfaceEvidence.GraphChange(number(), r(), r(), r(), r(), r());
                case 27: return new ManagedSurfaceEvidence.ComponentState(r(), r(), number(), r(), r(), r(), r(), r());
                case 28: return new ManagedSurfaceEvidence.ComponentTransition(r(), r(), r());
                case 29: return new ManagedSurfaceEvidence.OperationRouteState(r(), r(), r(), r());
                case 30: return new TimelineSourceSnapshot(r(), r());
                case 31: return new ManagedSurfaceEvidence.OperationRouteChange(number(), r(), r(), r(), r());
                case 32: return new ManagedSurfaceEvidence.SubscriptionState(r(), r(), r(), r(), number(), r(), r(), r(), r(), number(), number());
                case 33: return new ManagedSurfaceEvidence.SubscriptionChange(number(), r(), r(), r(), r(), r());
                case 34: return new ManagedSurfaceEvidence.ContractPatch(r(), r(), r(), r(), r());
                case 35: return new ManagedSurfaceEvidence.GeneralizationWrite(r(), r(), this.<Integer>r());
                case 36: return new ManagedSurfaceEvidence.DocumentTransition(r(), r(), r(), r(), r(), r(), r(), r());
                case 37: return new Metadata(r(), r(), r(), r(), r(), r());
                case 38: return new ContractsExecutionPolicy(number(), r());
                case 50: return TimelineActorKind.valueOf(this.<String>r());
                case 51: return EntryDisposition.valueOf(this.<String>r());
                case 52: return ClosureResult.ManagedResolutionStatus.valueOf(this.<String>r());
                case 53: return ManagedSurfaceEvidence.ResolutionKind.valueOf(this.<String>r());
                case 54: return ManagedSurfaceEvidence.GraphChangeKind.valueOf(this.<String>r());
                case 55: return ManagedSurfaceEvidence.ComponentKind.valueOf(this.<String>r());
                case 56: return ManagedSurfaceEvidence.ComponentTransitionKind.valueOf(this.<String>r());
                case 57: return ManagedSurfaceEvidence.OperationRouteChangeKind.valueOf(this.<String>r());
                case 58: return ManagedSurfaceEvidence.SubscriptionOperation.valueOf(this.<String>r());
                case 59: return ManagedSurfaceEvidence.ContractPatchOperation.valueOf(this.<String>r());
                case 39: return new Configuration(r(), r(), flag(), flag(), r(), r());
                case 40: return new CoreEntrySnapshot(r(), r(), r(), r(), r(), r(), r(), number(), number(), number());
                case 41: return new Timeline(r(), r());
                case 42: return new SourceHistoryPrerequisite(r(), r(), r(), r(), r(), r(), r(), r(), number(), r(), r(), r(), number(), number(), r(), r());
                case 43: return new DrainResult(r(), r(), flag(), flag(), r(), r(), r(), r(), r());
                case 44: return new DrainResult.RootedRetainedApplication(r(), r(), r());
                case 60: return new ManagedEpochApplicationWork(r(), r(), r(), r(), r(), number(), r(), r(), r(), number(), number(), r(), number(), r(), r());
                case 61: return new ManagedEpochApplicationWork.Position(r(), r(), r(), r());
                case 62: return new ManagedEpochApplicationWork.RepresentationStep(r(), r(), r(), r(), r(), flag());
                case 63: return new ManagedEpochApplicationReceipt(r(), r(), r(), r(), r(), r(), r(), r(), number(), r(), r(), number(), r(), r(), r());
                case 64: return new ManagedEpochApplicationAttempt(r(), r(), flag(), flag(), r(), number(), r(), r(), r(), r());
                case 65: return new ManagedEpochApplicationAttempt.PublicationFailure(r(), r(), r());
                case 66: return new ManagedEpochApplicationAttempt.ProcessorAttempt(flag(), r(), r(), r());
                case 67: return new ManagedEpochApplicationAttempt.ProcessResult(r(), flag(), flag(), flag(), r(), r(), r(), number(), number(), r(), r(), r(), r(), r(), r());
                case 68: return new ManagedEpochApplicationAttempt.GasCharge(number(), r(), r(), number(), number(), number(), r(), r(), r(), r(), r(), r(), r(), r());
                case 69: return new ManagedEpochApplicationAttempt.RejectedWorkOccurrence(number(), r(), r(), r(), r(), r(), r(), r(), r());
                case 70: return new ManagedEpochApplicationAttempt.RejectedCharge(r(), r(), r(), number(), number(), number(), r(), r(), number(), r(), r(), r(), r(), r());
                case 71: return new ManagedEpochApplicationAttempt.ResourceDemand(r(), r(), r(), r(), r());
                case 72: return new ManagedEpochApplicationAttempt.ManagedOccurrenceResolutionIssue(r(), r(), r());
                case 73: return new ManagedEpochEvidenceFailure(r(), r(), r(), r());
                case 45: return ExternalOrderKey.of(this.<List<?>>r());
                case 46: return new BigInteger(readBytes(in));
                case 90: return SourceHistoryPrerequisite.Kind.valueOf(this.<String>r());
                case 91: return ManagedEpochApplicationAttempt.PublicationFailureCode.valueOf(this.<String>r());
                case 92: return ManagedEpochApplicationAttempt.AutomaticResolutionStopReason.valueOf(this.<String>r());
                case 93: return ManagedEpochApplicationAttempt.Status.valueOf(this.<String>r());
                case 94: return ManagedEpochApplicationAttempt.ResolutionStatus.valueOf(this.<String>r());
                case 95: return ManagedEpochEvidenceFailure.Status.valueOf(this.<String>r());
                default: throw invalid("Unknown SDK evidence tag");
            }
        }
    }
}
