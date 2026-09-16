package blue.coordination.internal;

import blue.coordination.api.*;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Complete immutable host result rows; these observations do not select or execute an invocation. */
final class CoreReceiptStorageCodec {
    private static final String ADMISSION = "blue-coordination/admission-receipt-storage/1";
    private static final String DRAIN = "blue-coordination/processing-drain-storage/1";
    private final int maximumBytes;
    private final ClosureExecutionEvidenceStorageCodec evidence;
    private final StoredClosureResultCodec results;
    private final SessionRecordCodec rows;
    private final ManagedWorkStorageCodec works;
    private final ManagedApplicationStorageCodec applications;

    CoreReceiptStorageCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, null);
    }

    CoreReceiptStorageCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache) {
        this.maximumBytes = maximumBytes;
        results = new StoredClosureResultCodec(maximumBytes, maximumDepth, cache);
        evidence = new ClosureExecutionEvidenceStorageCodec(maximumBytes, maximumDepth, results.configured());
        rows = new SessionRecordCodec(maximumBytes, maximumDepth);
        works = new ManagedWorkStorageCodec(maximumBytes, maximumDepth, cache);
        applications = new ManagedApplicationStorageCodec(maximumBytes, maximumDepth, cache);
    }

    byte[] encodeAdmission(ContractsClosureAdmissionReceipt receipt) {
        return SessionStorageWire.encode(maximumBytes, out -> { out.text(ADMISSION); admission(out, receipt); });
    }

    ContractsClosureAdmissionReceipt decodeAdmission(byte[] bytes) {
        return physical(() -> {
            var value = SessionStorageWire.decode(bytes, maximumBytes, in -> {
                require(ADMISSION.equals(text(in)), "Wrong admission receipt format"); return admission(in);
            });
            require(Arrays.equals(bytes, encodeAdmission(value)), "Noncanonical admission receipt"); return value;
        });
    }

    void admission(Writer out, ContractsClosureAdmissionReceipt value) {
        attempt(out, value.attempt()); out.text(value.publicationIdentity()); out.text(value.publicationOutcome().name());
        list(out, value.documentIds(), (w, id) -> w.text(id.value()));
    }

    ContractsClosureAdmissionReceipt admission(Reader in) {
        return new ContractsClosureAdmissionReceipt(attempt(in), text(in),
                ContractsClosureAdmissionReceipt.PublicationOutcome.valueOf(text(in)), list(in, r -> DocumentId.of(text(r))));
    }

    byte[] encodeDrain(ProcessingDrainReceipt value, Function<String, ManagedEpochApplicationWork> originalApplicationWork) {
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(DRAIN); list(out, value.processedEntries(), rows::entry);
            orderedMap(out, value.outcomesByEntry(), (w, outcomes) -> list(w, outcomes, this::outcome));
            orderedMap(out, value.contractsAttemptsByEntry(), (w, attempts) -> list(w, attempts, this::dispatch));
            optional(out, value.processedThrough().orElse(null), SessionStorageWire::order);
            out.bool(value.quiescent()); out.bool(value.paused()); out.longValue(value.committedProcessTransitions()); out.longValue(value.elapsedNanos());
            list(out, value.managedEpochApplications(), (w, receipt) -> w.bytes(applications.encode(receipt,
                    Objects.requireNonNull(originalApplicationWork.apply(receipt.workIdentity()), "Missing original application work"))));
            list(out, value.managedEpochApplicationAttempts(), this::managedAttempt);
            list(out, value.managedEpochEvidenceFailures(), (w, failure) -> {
                works.work(w, failure.work()); w.text(failure.status().name()); w.text(failure.code()); w.text(failure.diagnostic());
            });
            list(out, value.rootedRetainedAttempts(), (w, retained) -> {
                w.text(retained.rootDocumentId().value()); works.work(w, retained.work()); dispatch(w, retained.attempt());
            });
        });
    }

    ProcessingDrainReceipt decodeDrain(byte[] bytes) {
        return physical(() -> {
            Map<String, ManagedEpochApplicationWork> originalWorks = new HashMap<>();
            var value = SessionStorageWire.decode(bytes, maximumBytes, in -> {
                require(DRAIN.equals(text(in)), "Wrong processing drain format");
                var entries = list(in, rows::entry); var outcomes = orderedMap(in, r -> list(r, this::outcome));
                var attempts = orderedMap(in, r -> list(r, this::dispatch));
                var through = optional(in, SessionStorageWire::order); boolean quiescent = in.bool(), paused = in.bool();
                long transitions = in.longValue(), nanos = in.longValue();
                var committed = list(in, r -> {
                    var application = applications.decode(r.bytes(maximumBytes)); remember(originalWorks, application.work()); return application.receipt();
                });
                var managed = list(in, r -> managedAttempt(r, originalWorks));
                var failures = list(in, r -> new ManagedEpochEvidenceFailure(works.work(r), ManagedCatchUpStatus.valueOf(text(r)), text(r), text(r)));
                var retained = list(in, r -> new ProcessingDrainReceipt.RootedRetainedAttempt(DocumentId.of(text(r)), works.work(r), dispatch(r)));
                return new ProcessingDrainReceipt(entries, outcomes, attempts, through, quiescent, paused, transitions, nanos,
                        committed, managed, failures).withRootedRetainedAttempts(retained);
            });
            require(Arrays.equals(bytes, encodeDrain(value, originalWorks::get)), "Noncanonical processing drain"); return value;
        });
    }

    private void remember(Map<String, ManagedEpochApplicationWork> values, ManagedEpochApplicationWork value) {
        var old = values.putIfAbsent(value.workIdentity(), value);
        require(old == null || Arrays.equals(works.encode(old), works.encode(value)), "One drain changed original work under an equal identity");
    }
    private void outcome(Writer out, DocumentDispatchOutcome value) {
        out.text(value.documentId().value()); rows.revision(out, value.revision()); out.longValue(value.totalNanos());
    }
    private DocumentDispatchOutcome outcome(Reader in) { return new DocumentDispatchOutcome(DocumentId.of(text(in)), rows.revision(in), in.longValue()); }

    void dispatch(Writer out, ContractsClosureDispatchAttempt value) {
        out.text(value.entryBlueId()); list(out, value.documentIds(), (w, id) -> w.text(id.value())); attempt(out, value.attempt());
        out.bool(value.published()); out.nullableText(value.publicationIdentity()); out.bool(value.replayed()); out.longValue(value.automaticRetryCount());
        list(out, value.managedOccurrenceResolutions(), this::resolution); list(out, value.inputComponents(), rows::component);
        list(out, value.operationRouteChanges(), this::routeChange);
        list(out, value.managedOccurrenceResolutionIssues(), (w, issue) -> {
            w.text(issue.demandIdentity()); w.text(issue.status().name()); w.text(issue.diagnostic());
        });
    }
    ContractsClosureDispatchAttempt dispatch(Reader in) {
        return new ContractsClosureDispatchAttempt(text(in), list(in, r -> DocumentId.of(text(r))), attempt(in),
                in.bool(), nullableText(in), in.bool(), in.longValue(), list(in, this::resolution), list(in, rows::component),
                list(in, this::routeChange), list(in, r -> new ContractsClosureDispatchAttempt.ManagedOccurrenceResolutionIssue(
                        text(r), ContractsClosureDispatchAttempt.ResolutionStatus.valueOf(text(r)), text(r))));
    }

    private void managedAttempt(Writer out, ManagedEpochApplicationAttempt value) {
        works.work(out, value.work()); attempt(out, value.attempt()); out.bool(value.published()); out.bool(value.replayed());
        optional(out, value.receipt().orElse(null), (w, receipt) -> w.bytes(applications.encode(receipt, value.work())));
        out.longValue(value.automaticRetryCount());
        optional(out, value.automaticResolutionStopReason().orElse(null), (w, reason) -> w.text(reason.name()));
        list(out, value.managedOccurrenceResolutionIssues(), (w, issue) -> {
            w.text(issue.demandIdentity()); w.text(issue.status().name()); w.text(issue.diagnostic());
        });
        optional(out, value.publicationFailure().orElse(null), (w, failure) -> {
            w.text(failure.code().name()); w.text(failure.message()); orderedMap(w, failure.details(), Writer::text);
        });
        list(out, value.managedOccurrenceResolutions(), this::resolution); list(out, value.inputComponents(), rows::component);
        list(out, value.operationRouteChanges(), this::routeChange);
    }
    private ManagedEpochApplicationAttempt managedAttempt(Reader in, Map<String, ManagedEpochApplicationWork> originalWorks) {
        var work = works.work(in); var attempt = attempt(in); boolean published = in.bool(), replayed = in.bool();
        var receipt = optional(in, r -> {
            var application = applications.decode(r.bytes(maximumBytes));
            require(Arrays.equals(works.encode(work), works.encode(application.work())), "Managed attempt changed original application work");
            remember(originalWorks, application.work()); return application.receipt();
        });
        return new ManagedEpochApplicationAttempt(work, attempt, published, replayed, Optional.ofNullable(receipt), in.longValue(),
                Optional.ofNullable(optional(in, r -> ManagedEpochApplicationAttempt.AutomaticResolutionStopReason.valueOf(text(r)))),
                list(in, r -> new ManagedEpochApplicationAttempt.ManagedOccurrenceResolutionIssue(text(r),
                        ManagedEpochApplicationAttempt.ResolutionStatus.valueOf(text(r)), text(r))),
                Optional.ofNullable(optional(in, r -> new ManagedEpochApplicationAttempt.PublicationFailure(
                        CoordinationErrorCode.valueOf(text(r)), text(r), orderedMap(r, SessionRecordCodec::text)))),
                list(in, this::resolution), list(in, rows::component), list(in, this::routeChange));
    }

    private void resolution(Writer out, ContractsClosureDispatchAttempt.ManagedOccurrenceResolution value) {
        out.text(value.demandIdentity()); StoreIndexCodecs.occurrence(out, value.occurrence()); out.text(value.targetKind().name());
        optional(out, value.authoredInitial().orElse(null), rows::exact);
    }
    private ContractsClosureDispatchAttempt.ManagedOccurrenceResolution resolution(Reader in) {
        return new ContractsClosureDispatchAttempt.ManagedOccurrenceResolution(text(in), StoreIndexCodecs.occurrence(in),
                ContractsClosureDispatchAttempt.TargetKind.valueOf(text(in)), Optional.ofNullable(optional(in, rows::exact)));
    }
    private void routeChange(Writer out, ContractsClosureDispatchAttempt.OperationRouteChange value) {
        out.text(value.kind().name()); out.text(value.documentId().value());
        optional(out, value.before().orElse(null), this::route); optional(out, value.after().orElse(null), this::route);
    }
    private ContractsClosureDispatchAttempt.OperationRouteChange routeChange(Reader in) {
        return new ContractsClosureDispatchAttempt.OperationRouteChange(ContractsClosureDispatchAttempt.OperationRouteChangeKind.valueOf(text(in)),
                DocumentId.of(text(in)), Optional.ofNullable(optional(in, this::route)), Optional.ofNullable(optional(in, this::route)));
    }
    private void route(Writer out, ContractsClosureDispatchAttempt.OperationRouteState value) {
        out.text(value.scopePath()); out.text(value.operation()); out.text(value.channel());
        list(out, value.acceptedSources(), (w, source) -> { w.text(source.timelineId()); w.text(source.actorId()); });
    }
    private ContractsClosureDispatchAttempt.OperationRouteState route(Reader in) {
        return new ContractsClosureDispatchAttempt.OperationRouteState(text(in), text(in), text(in),
                list(in, r -> new Timeline(text(r), text(r))));
    }

    /** An observed DTO may lack original input; retain its issued markers but never infer an association. */
    void attempt(Writer out, ClosureAttemptResult value) {
        out.text(value.kind().name());
        if (value.isComplete()) out.bytes(results.encode(value.processResult()));
        else list(out, value.resourceDemands(), (w, demand) -> w.bytes(evidence.encodeResourceDemand(demand)));
    }

    ClosureAttemptResult attempt(Reader in) {
        var kind = ClosureAttemptResult.Kind.valueOf(text(in));
        if (kind == ClosureAttemptResult.Kind.COMPLETE)
            return ClosureAttemptResult.complete(results.decode(in.bytes(maximumBytes)));
        java.util.List<blue.language.processor.closure.ClosureResourceDemand> demands =
                list(in, r -> evidence.decodeResourceDemand(r.bytes(maximumBytes)));
        return ClosureAttemptResult.needsResources(demands);
    }
}
