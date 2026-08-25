package blue.coordination.sdk;

import blue.coordination.api.ContractsClosureDispatchAttempt;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.TimelineEntry;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureResourceDemand;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.PublicEventOccurrence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Translates retained engine evidence into stable SDK entry outcomes. */
final class SdkDrainResultMapper {
    private final SdkCoordinationRuntime runtime;
    private final DefaultCoordinationEngine engine;

    SdkDrainResultMapper(
            SdkCoordinationRuntime runtime,
            DefaultCoordinationEngine engine) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    DrainResult map(ProcessingDrainReceipt receipt) {
        ProcessingDrainReceipt drained = Objects.requireNonNull(
                receipt, "receipt");
        ArrayList<EntryResult> entries = new ArrayList<>();
        LinkedHashSet<String> mapped = new LinkedHashSet<>();
        for (TimelineEntry entry : drained.processedEntries()) {
            entries.add(mapEntry(
                    entry,
                    drained.contractsAttemptsFor(entry.blueId())));
            mapped.add(entry.blueId());
        }
        drained.contractsAttemptsByEntry().forEach((entryBlueId, attempts) -> {
            if (mapped.contains(entryBlueId)) {
                return;
            }
            TimelineEntry retained = runtime.retainedCoreEntry(entryBlueId);
            if (retained != null) {
                entries.add(mapEntry(retained, attempts));
                mapped.add(entryBlueId);
            }
        });
        if (mapped.size() != entries.size()) {
            throw new IllegalStateException(
                    "SDK drain result contains duplicate entry evidence");
        }
        ProcessingStats stats = aggregateDrainStats(entries, drained);
        Diagnostic diagnostic = drained.paused()
                ? new Diagnostic(
                        "PROCESSING_PAUSED",
                        "Processing stopped at its deterministic budget",
                        Map.of())
                : drained.blocked()
                ? new Diagnostic(
                        "PROCESSING_BLOCKED",
                        "Processing is waiting on exact prerequisite evidence",
                        Map.of())
                : Diagnostic.none();
        return new DrainResult(
                entries,
                stats,
                drained.quiescent(),
                drained.paused(),
                diagnostic);
    }

    private EntryResult mapEntry(
            TimelineEntry entry,
            List<ContractsClosureDispatchAttempt> attempts) {
        EntryHandle handle = runtime.handle(entry);
        if (attempts.isEmpty()) {
            TargetOutcome target = diagnoseZeroAttempts(
                    runtime.intent(entry.blueId()));
            return new EntryResult(
                    handle,
                    target.disposition(),
                    List.of(),
                    List.of(),
                    ProcessingStats.zero(),
                    target.diagnostic());
        }

        ArrayList<ClosureResult> closures = new ArrayList<>();
        for (int index = 0; index < attempts.size(); index++) {
            closures.add(mapClosure(entry, attempts.get(index), index));
        }
        EntryDisposition disposition = aggregateDisposition(closures);
        List<PublicEvent> publicEvents = closures.stream()
                .flatMap(closure -> closure.publicEvents().stream())
                .toList();
        ProcessingStats stats = aggregateClosureStats(closures);
        Diagnostic diagnostic = aggregateDiagnostic(
                disposition, closures);
        return new EntryResult(
                handle,
                disposition,
                closures,
                publicEvents,
                stats,
                diagnostic);
    }

    private ClosureResult mapClosure(
            TimelineEntry entry,
            ContractsClosureDispatchAttempt retained,
            int index) {
        ClosureAttemptResult attempt = retained.attempt();
        if (!attempt.isComplete()) {
            Diagnostic diagnostic = new Diagnostic(
                    "REQUIRED_EXACT_RESOURCES",
                    "Closure processing requires unavailable exact values",
                    Map.of(
                            "requiredExactBlueIds",
                            String.join(",", attempt.requiredExactBlueIds())));
            return new ClosureResult(
                    closureId(entry, retained, index),
                    EntryDisposition.NEEDS_RESOURCES,
                    List.of(),
                    List.of(),
                    ProcessingStats.zero(),
                    diagnostic,
                    resourceDemands(attempt.resourceDemands()),
                    processorAttemptCount(retained));
        }

        ClosureProcessResult result = attempt.processResult();
        EntryDisposition disposition = disposition(result.status());
        List<PublicEvent> events = publicEvents(result);
        List<DocumentChange> changes = retained.published()
                ? changes(entry, retained, result, events)
                : List.of();
        ProcessingStats stats = stats(result, retained, changes);
        Diagnostic diagnostic = diagnostic(result);
        return new ClosureResult(
                closureId(entry, retained, index),
                disposition,
                changes,
                events,
                stats,
                diagnostic,
                List.of(),
                processorAttemptCount(retained));
    }

    private static List<ClosureResult.ResourceDemand> resourceDemands(
            List<ClosureResourceDemand> demands) {
        return Objects.requireNonNull(demands, "demands").stream()
                .map(demand -> new ClosureResult.ResourceDemand(
                        demand.kind().name(),
                        demand.demandIdentity(),
                        demand.suppliedValueBlueId(),
                        DocumentId.of(demand.sourceDocumentId().value()),
                        demand.sourcePath()))
                .toList();
    }

    private static long processorAttemptCount(
            ContractsClosureDispatchAttempt retained) {
        return Math.addExact(retained.automaticRetryCount(), 1L);
    }

    private List<DocumentChange> changes(
            TimelineEntry entry,
            ContractsClosureDispatchAttempt retained,
            ClosureProcessResult result,
            List<PublicEvent> events) {
        ArrayList<DocumentChange> changes = new ArrayList<>();
        for (DocumentId documentId : retained.documentIds()) {
            List<blue.coordination.api.DocumentRevision> matching =
                    engine.history(documentId).stream()
                            .filter(revision -> revision.causalEntryBlueId()
                                    .filter(entry.blueId()::equals)
                                    .isPresent())
                            .toList();
            if (!matching.isEmpty()) {
                blue.coordination.api.DocumentRevision first =
                        matching.get(0);
                blue.coordination.api.DocumentRevision last =
                        matching.get(matching.size() - 1);
                List<PublicEvent> documentEvents = events.stream()
                        .filter(event -> event.sourceDocument()
                                .filter(documentId::equals)
                                .isPresent())
                        .toList();
                changes.add(new DocumentChange(
                        documentId,
                        last.epoch(),
                        first.before().map(ExactBlueValue::wrap)
                                .orElse(null),
                        ExactBlueValue.wrap(last.after()),
                        documentEvents));
                continue;
            }
            result.resultingDocuments().stream()
                    .filter(document -> document.documentId().value()
                            .equals(documentId.value()))
                    .filter(document -> !document.beforeBlueId()
                            .equals(document.afterBlueId()))
                    .findFirst()
                    .ifPresent(document -> changes.add(new DocumentChange(
                            documentId,
                            document.epoch(),
                            null,
                            ExactBlueValue.wrap(
                                    ExactValue.fromVerifiedClosureResult(
                                            result, documentId)),
                            events.stream()
                                    .filter(event -> event.sourceDocument()
                                            .filter(documentId::equals)
                                            .isPresent())
                                    .toList())));
        }
        return List.copyOf(changes);
    }

    private static List<PublicEvent> publicEvents(
            ClosureProcessResult result) {
        return result.publicEvents().stream()
                .map(SdkDrainResultMapper::publicEvent)
                .toList();
    }

    private static PublicEvent publicEvent(PublicEventOccurrence occurrence) {
        return new PublicEvent(
                ExactBlueValue.wrap(ExactValue.verified(
                        occurrence.eventBlueId(), occurrence.event())),
                DocumentId.of(occurrence.publicRootDocumentId().value()),
                null);
    }

    private static ProcessingStats stats(
            ClosureProcessResult result,
            ContractsClosureDispatchAttempt retained,
            List<DocumentChange> changes) {
        LinkedHashMap<String, Long> counters = new LinkedHashMap<>();
        for (GasTraceEntry charge : result.gasTrace()) {
            String name = charge.namespace().wireValue()
                    + "." + charge.counter();
            counters.merge(name, charge.subtotal(), Math::addExact);
        }
        return new ProcessingStats(
                result.totalGas(),
                changes.size(),
                retained.documentIds().size(),
                0L,
                documentStepOrder(result),
                counters);
    }

    private static List<DocumentId> documentStepOrder(
            ClosureProcessResult result) {
        ArrayList<DocumentId> order = new ArrayList<>();
        String previousWorkOccurrence = null;
        for (GasTraceEntry charge : result.gasTrace()) {
            if (!"closureWorkOccurrenceDequeued".equals(charge.counter())
                    || charge.documentId() == null
                    || charge.workOccurrenceId() == null
                    || charge.workOccurrenceId().equals(
                            previousWorkOccurrence)) {
                continue;
            }
            order.add(DocumentId.of(charge.documentId().value()));
            previousWorkOccurrence = charge.workOccurrenceId();
        }
        return List.copyOf(order);
    }

    private TargetOutcome diagnoseZeroAttempts(
            SdkCoordinationRuntime.EntryIntent intent) {
        if (!intent.targeted()) {
            return new TargetOutcome(
                    EntryDisposition.NO_MATCH, Diagnostic.none());
        }
        if (!intent.targetPresentAtSubmission()) {
            return rejected(
                    "TARGET_DOCUMENT_NOT_FOUND",
                    "The selected target document is not managed",
                    intent);
        }
        blue.coordination.api.DocumentSnapshot snapshot;
        try {
            snapshot = engine.auditDocument(intent.targetId());
        } catch (CoordinationException failure) {
            return rejected(
                    "TARGET_DOCUMENT_NOT_FOUND",
                    "The selected target document is no longer managed",
                    intent);
        }
        if (!snapshot.blueId().equals(intent.expectedTargetBlueId())) {
            return new TargetOutcome(
                    EntryDisposition.STALE,
                    new Diagnostic(
                            "STALE_TARGET_DOCUMENT",
                            "The selected exact target state is no longer current",
                            Map.of(
                                    "documentId", intent.targetId().value(),
                                    "expectedBlueId",
                                    intent.expectedTargetBlueId(),
                                    "currentBlueId", snapshot.blueId())));
        }
        String operationPrefix = intent.operation() + "|";
        String channelPrefix = intent.operation()
                + "|" + intent.channel() + "|";
        boolean operationExists = snapshot.routingDefinitions().stream()
                .anyMatch(route -> route.startsWith(operationPrefix));
        if (!operationExists) {
            return rejected(
                    "OPERATION_NOT_FOUND",
                    "The target has no operation with this name",
                    intent);
        }
        List<String> channelRoutes = snapshot.routingDefinitions().stream()
                .filter(route -> route.startsWith(channelPrefix))
                .toList();
        if (channelRoutes.isEmpty()) {
            return rejected(
                    "TARGET_CHANNEL_NOT_FOUND",
                    "The operation is not bound to the requested target Channel",
                    intent);
        }
        boolean sourceAccepted = channelRoutes.stream().anyMatch(route ->
                route.contains("timelineId=" + intent.timelineId())
                        && route.contains("actorId=" + intent.actorId()));
        if (!sourceAccepted) {
            return rejected(
                    "TARGET_CHANNEL_SOURCE_MISMATCH",
                    "The selected Timeline is not accepted by the target Channel",
                    intent);
        }
        return rejected(
                "TARGET_NOT_SELECTED",
                "Exact target evidence did not select a processing cohort",
                intent);
    }

    private static TargetOutcome rejected(
            String code,
            String message,
            SdkCoordinationRuntime.EntryIntent intent) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("documentId", intent.targetId().value());
        if (intent.operation() != null) {
            details.put("operation", intent.operation());
        }
        if (intent.channel() != null) {
            details.put("channel", intent.channel());
        }
        return new TargetOutcome(
                EntryDisposition.REJECTED,
                new Diagnostic(code, message, details));
    }

    private static EntryDisposition disposition(ProcessorStatus status) {
        return switch (status) {
            case SUCCESS -> EntryDisposition.APPLIED;
            case NO_MATCH -> EntryDisposition.NO_MATCH;
            case STALE -> EntryDisposition.STALE;
            case GAS_LIMIT_EXCEEDED -> EntryDisposition.GAS_LIMIT_EXCEEDED;
            case PORTABLE_LIMIT_EXCEEDED ->
                    EntryDisposition.PORTABLE_LIMIT_EXCEEDED;
            default -> EntryDisposition.REJECTED;
        };
    }

    private static Diagnostic diagnostic(ClosureProcessResult result) {
        if (result.status() == ProcessorStatus.SUCCESS
                || result.status() == ProcessorStatus.NO_MATCH) {
            return Diagnostic.none();
        }
        ProcessorDiagnostic processor = result.diagnostic();
        if (processor == null) {
            return new Diagnostic(
                    result.status().name(),
                    "Contracts processing completed with "
                            + result.status().wireValue(),
                    Map.of());
        }
        return new Diagnostic(
                stableCode(processor.category().name()),
                processor.message() == null ? "" : processor.message(),
                processor.details());
    }

    private static String stableCode(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase();
    }

    private static String closureId(
            TimelineEntry entry,
            ContractsClosureDispatchAttempt retained,
            int index) {
        if (retained.publicationIdentity() != null) {
            return retained.publicationIdentity();
        }
        if (retained.attempt().isComplete()) {
            return retained.attempt().processResult().invocationIdentity();
        }
        return entry.blueId() + ":closure:" + index;
    }

    private static EntryDisposition aggregateDisposition(
            List<ClosureResult> closures) {
        LinkedHashSet<EntryDisposition> values = new LinkedHashSet<>();
        closures.forEach(closure -> values.add(closure.disposition()));
        return values.size() == 1
                ? values.iterator().next()
                : EntryDisposition.MIXED;
    }

    private static Diagnostic aggregateDiagnostic(
            EntryDisposition disposition,
            List<ClosureResult> closures) {
        if (disposition == EntryDisposition.APPLIED
                || disposition == EntryDisposition.NO_MATCH) {
            return Diagnostic.none();
        }
        if (disposition == EntryDisposition.MIXED) {
            return new Diagnostic(
                    "MIXED_CLOSURE_OUTCOMES",
                    "Disconnected affected closures completed differently",
                    Map.of("closureCount",
                            Integer.toString(closures.size())));
        }
        return closures.stream()
                .map(ClosureResult::diagnostic)
                .filter(Diagnostic::present)
                .findFirst()
                .orElse(Diagnostic.none());
    }

    private static ProcessingStats aggregateClosureStats(
            List<ClosureResult> closures) {
        long gas = 0L;
        long transitions = 0L;
        long opened = 0L;
        long elapsed = 0L;
        ArrayList<DocumentId> order = new ArrayList<>();
        LinkedHashMap<String, Long> counters = new LinkedHashMap<>();
        for (ClosureResult closure : closures) {
            ProcessingStats stats = closure.stats();
            gas = Math.addExact(gas, stats.gas());
            transitions = Math.addExact(
                    transitions, stats.committedTransitions());
            opened = Math.addExact(opened, stats.documentsOpened());
            elapsed = Math.addExact(elapsed, stats.elapsedNanos());
            order.addAll(stats.documentStepOrder());
            stats.counters().forEach((name, value) -> counters.merge(
                    name, value, Math::addExact));
        }
        return new ProcessingStats(
                gas, transitions, opened, elapsed, order, counters);
    }

    private static ProcessingStats aggregateDrainStats(
            List<EntryResult> entries,
            ProcessingDrainReceipt receipt) {
        long gas = 0L;
        long opened = 0L;
        ArrayList<DocumentId> order = new ArrayList<>();
        LinkedHashMap<String, Long> counters = new LinkedHashMap<>();
        for (EntryResult entry : entries) {
            ProcessingStats stats = entry.stats();
            gas = Math.addExact(gas, stats.gas());
            opened = Math.addExact(opened, stats.documentsOpened());
            order.addAll(stats.documentStepOrder());
            stats.counters().forEach((name, value) -> counters.merge(
                    name, value, Math::addExact));
        }
        return new ProcessingStats(
                gas,
                receipt.committedProcessTransitions(),
                opened,
                receipt.elapsedNanos(),
                order,
                counters);
    }

    private record TargetOutcome(
            EntryDisposition disposition,
            Diagnostic diagnostic) {
    }
}
