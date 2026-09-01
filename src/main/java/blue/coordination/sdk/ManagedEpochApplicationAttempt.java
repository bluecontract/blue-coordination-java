package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable SDK processor evidence for one managed epoch application attempt.
 *
 * <p>The SDK projection deliberately owns its complete public type graph. It
 * exposes no processor implementation classes while retaining exact work,
 * completion, gas, rollback, failure, event, and resource evidence.</p>
 */
public record ManagedEpochApplicationAttempt(
        ManagedEpochApplicationWork work,
        ProcessorAttempt attempt,
        boolean published,
        boolean replayed,
        Optional<ManagedEpochApplicationReceipt> receipt,
        long automaticRetryCount,
        Optional<AutomaticResolutionStopReason>
                automaticResolutionStopReason,
        List<ManagedOccurrenceResolutionIssue>
                managedOccurrenceResolutionIssues,
        Optional<PublicationFailure> publicationFailure) {

    /**
     * Preserves the original constructor for callers that do not consume
     * automatic occurrence-resolution evidence.
     */
    public ManagedEpochApplicationAttempt(
            ManagedEpochApplicationWork work,
            ProcessorAttempt attempt,
            boolean published,
            boolean replayed,
            Optional<ManagedEpochApplicationReceipt> receipt) {
        this(work, attempt, published, replayed, receipt, 0L,
                Optional.empty(), List.of(), Optional.empty());
    }

    /**
     * Preserves the retry-and-issue constructor added before typed automatic
     * stop evidence was retained.
     */
    public ManagedEpochApplicationAttempt(
            ManagedEpochApplicationWork work,
            ProcessorAttempt attempt,
            boolean published,
            boolean replayed,
            Optional<ManagedEpochApplicationReceipt> receipt,
            long automaticRetryCount,
            List<ManagedOccurrenceResolutionIssue>
                    managedOccurrenceResolutionIssues) {
        this(
                work,
                attempt,
                published,
                replayed,
                receipt,
                automaticRetryCount,
                inferredStopReason(managedOccurrenceResolutionIssues),
                managedOccurrenceResolutionIssues,
                Optional.empty());
    }

    /** Preserves the complete pre-publication-failure constructor. */
    public ManagedEpochApplicationAttempt(
            ManagedEpochApplicationWork work,
            ProcessorAttempt attempt,
            boolean published,
            boolean replayed,
            Optional<ManagedEpochApplicationReceipt> receipt,
            long automaticRetryCount,
            Optional<AutomaticResolutionStopReason>
                    automaticResolutionStopReason,
            List<ManagedOccurrenceResolutionIssue>
                    managedOccurrenceResolutionIssues) {
        this(
                work,
                attempt,
                published,
                replayed,
                receipt,
                automaticRetryCount,
                automaticResolutionStopReason,
                managedOccurrenceResolutionIssues,
                Optional.empty());
    }

    /** Validates publication and receipt coherence. */
    public ManagedEpochApplicationAttempt {
        work = Objects.requireNonNull(work, "work");
        attempt = Objects.requireNonNull(attempt, "attempt");
        receipt = Objects.requireNonNull(receipt, "receipt");
        automaticResolutionStopReason = Objects.requireNonNull(
                automaticResolutionStopReason,
                "automaticResolutionStopReason");
        publicationFailure = Objects.requireNonNull(
                publicationFailure, "publicationFailure");
        managedOccurrenceResolutionIssues = List.copyOf(
                Objects.requireNonNull(
                        managedOccurrenceResolutionIssues,
                        "managedOccurrenceResolutionIssues"));
        SdkPreconditions.requireNonNegative(
                automaticRetryCount, "automaticRetryCount");
        if (published != receipt.isPresent()) {
            throw new IllegalArgumentException(
                    "Published attempts require one SDK receipt");
        }
        if (published && (!attempt.isComplete()
                || !attempt.processResult().commits())) {
            throw new IllegalArgumentException(
                    "Only a committing attempt can be published");
        }
        if (receipt.isPresent()
                && !receipt.orElseThrow().workIdentity().equals(
                        work.workIdentity())) {
            throw new IllegalArgumentException(
                    "Attempt receipt identifies different work");
        }
        if (replayed && !published) {
            throw new IllegalArgumentException(
                    "A replayed attempt must already be published");
        }
        if ((published || attempt.isComplete())
                && !managedOccurrenceResolutionIssues.isEmpty()) {
            throw new IllegalArgumentException(
                    "Only a suspended unpublished attempt may expose "
                            + "unresolved matching evidence");
        }
        if ((published || attempt.isComplete())
                && automaticResolutionStopReason.isPresent()) {
            throw new IllegalArgumentException(
                    "Only a suspended unpublished attempt may expose an "
                            + "automatic resolution stop reason");
        }
        if (publicationFailure.isPresent()
                && (published
                || replayed
                || !attempt.isComplete()
                || !attempt.processResult().commits())) {
            throw new IllegalArgumentException(
                    "A publication failure requires one complete committing "
                            + "but unpublished SDK attempt");
        }
        if (publicationFailure.isPresent()
                && (automaticResolutionStopReason.isPresent()
                || !managedOccurrenceResolutionIssues.isEmpty())) {
            throw new IllegalArgumentException(
                    "A publication failure cannot also be an automatic "
                            + "resolution stop");
        }
        boolean unresolvedStop = automaticResolutionStopReason
                .filter(reason -> reason
                        == AutomaticResolutionStopReason.UNRESOLVED_DEMANDS)
                .isPresent();
        if (unresolvedStop
                != !managedOccurrenceResolutionIssues.isEmpty()) {
            throw new IllegalArgumentException(
                    "UNRESOLVED_DEMANDS must identify at least one unresolved "
                            + "managed occurrence demand");
        }
        Set<String> demandIdentities = attempt.resourceDemands().stream()
                .map(ResourceDemand::demandIdentity)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> issueIdentities = new LinkedHashSet<>();
        for (ManagedOccurrenceResolutionIssue issue
                : managedOccurrenceResolutionIssues) {
            if (!demandIdentities.contains(issue.demandIdentity())) {
                throw new IllegalArgumentException(
                        "Unresolved matching evidence does not identify a "
                                + "retained SDK resource demand "
                                + issue.demandIdentity());
            }
            if (!issueIdentities.add(issue.demandIdentity())) {
                throw new IllegalArgumentException(
                        "Unresolved matching evidence repeats demand "
                                + issue.demandIdentity());
            }
        }
    }

    /** Stable owning-layer failure after Contracts completed but before commit. */
    public record PublicationFailure(
            PublicationFailureCode code,
            String message,
            Map<String, String> details) {

        /** Retains immutable exact failure fields for durable host evidence. */
        public PublicationFailure {
            code = Objects.requireNonNull(code, "code");
            message = text(message, "message");
            details = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            details, "details")));
            details.forEach((key, value) -> {
                text(key, "detail key");
                text(value, "detail value");
            });
        }
    }

    /** Closed SDK-owned managed-application publication failure vocabulary. */
    public enum PublicationFailureCode {
        /** Retained processing attempted to author a nested document lineage. */
        UNSUPPORTED_NESTED_NEW_LINEAGE
    }

    /** Closed reason why automatic resolution returned a suspended attempt. */
    public enum AutomaticResolutionStopReason {
        /** One or more demands lacked exact managed occurrence evidence. */
        UNRESOLVED_DEMANDS,
        /** The admitted portable expansion bound was reached. */
        EXPANSION_LIMIT,
        /** The same complete automatic-resolution progress repeated. */
        REPEATED_PROGRESS
    }

    /** Closed completed Contracts processor status vocabulary. */
    public enum Status {
        /** Processing completed and committed. */
        SUCCESS("success"),
        /** No eligible handler matched. */
        NO_MATCH("no-match"),
        /** Supplied ordering or revision evidence was stale. */
        STALE("stale"),
        /** Processing observed a terminal marker. */
        TERMINATED("terminated"),
        /** The processing document was invalid. */
        INVALID_PROCESSING_DOCUMENT("invalid-processing-document"),
        /** A required capability was unavailable or invalid. */
        CAPABILITY_FAILURE("capability-failure"),
        /** Runtime execution ended in a deterministic fatal failure. */
        RUNTIME_FATAL("runtime-fatal"),
        /** Processing exhausted the admitted gas budget. */
        GAS_LIMIT_EXCEEDED("gas-limit-exceeded"),
        /** Processing exceeded a portable cardinality or size limit. */
        PORTABLE_LIMIT_EXCEEDED("portable-limit-exceeded"),
        /** Processing produced an invalid subscription surface. */
        SUBSCRIPTION_SURFACE_INVALID("subscription-surface-invalid");

        private final String wireValue;

        Status(String wireValue) {
            this.wireValue = wireValue;
        }

        /** Returns the stable Contracts wire value. */
        public String wireValue() {
            return wireValue;
        }

        /** Returns whether this status commits tentative state and events. */
        public boolean commits() {
            return this == SUCCESS;
        }
    }

    /** Completion or exact-resource suspension evidence. */
    public record ProcessorAttempt(
            boolean complete,
            ProcessResult processResult,
            List<ResourceDemand> resourceDemands,
            List<String> requiredExactBlueIds) {

        /** Validates the mutually exclusive completion branches. */
        public ProcessorAttempt {
            resourceDemands = List.copyOf(Objects.requireNonNull(
                    resourceDemands, "resourceDemands"));
            requiredExactBlueIds = List.copyOf(Objects.requireNonNull(
                    requiredExactBlueIds, "requiredExactBlueIds"));
            if (complete != (processResult != null)) {
                throw new IllegalArgumentException(
                        "A complete attempt requires one process result");
            }
            if (complete && (!resourceDemands.isEmpty()
                    || !requiredExactBlueIds.isEmpty())) {
                throw new IllegalArgumentException(
                        "A completed attempt cannot require resources");
            }
            if (!complete && resourceDemands.isEmpty()) {
                throw new IllegalArgumentException(
                        "A suspended attempt requires typed resources");
            }
        }

        /** Returns whether the processor attempt completed. */
        public boolean isComplete() {
            return complete;
        }
    }

    /** Complete processor result without implementation-type leakage. */
    public record ProcessResult(
            Status status,
            boolean commits,
            boolean atomic,
            boolean rollbackToInput,
            String invocationIdentity,
            String inputClosureIdentity,
            String outputClosureIdentity,
            long graphGeneration,
            long totalGas,
            List<GasCharge> gasTrace,
            String gasTraceIdentity,
            RejectedWorkOccurrence rejectedWorkOccurrence,
            RejectedCharge rejectedCharge,
            List<PublicEvent> publicEvents,
            Diagnostic diagnostic) {

        /** Validates the immutable completed-result projection. */
        public ProcessResult {
            status = Objects.requireNonNull(status, "status");
            invocationIdentity = text(
                    invocationIdentity, "invocationIdentity");
            inputClosureIdentity = text(
                    inputClosureIdentity, "inputClosureIdentity");
            outputClosureIdentity = text(
                    outputClosureIdentity, "outputClosureIdentity");
            SdkPreconditions.requireNonNegative(
                    graphGeneration, "graphGeneration");
            SdkPreconditions.requireNonNegative(totalGas, "totalGas");
            gasTrace = List.copyOf(Objects.requireNonNull(
                    gasTrace, "gasTrace"));
            gasTraceIdentity = text(
                    gasTraceIdentity, "gasTraceIdentity");
            publicEvents = List.copyOf(Objects.requireNonNull(
                    publicEvents, "publicEvents"));
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
            if (commits != status.commits()) {
                throw new IllegalArgumentException(
                        "Commit evidence disagrees with processor status");
            }
            if (rollbackToInput
                    && !inputClosureIdentity.equals(outputClosureIdentity)) {
                throw new IllegalArgumentException(
                        "Rollback output must equal the input closure");
            }
            if (rollbackToInput && !publicEvents.isEmpty()) {
                throw new IllegalArgumentException(
                        "Rollback cannot expose public events");
            }
        }
    }

    /** One admitted exact gas charge. */
    public record GasCharge(
            long sequence,
            String namespace,
            String counter,
            long quantity,
            long weight,
            long subtotal,
            DocumentId documentId,
            String scopePath,
            Long activationGeneration,
            Long componentGeneration,
            String contractKey,
            String logicalPath,
            String workOccurrenceIdentity,
            String reason) {

        /** Validates required gas fields and retains nullable context. */
        public GasCharge {
            SdkPreconditions.requireNonNegative(sequence, "sequence");
            namespace = text(namespace, "namespace");
            counter = text(counter, "counter");
            if (quantity <= 0L || weight < 0L || subtotal < 0L) {
                throw new IllegalArgumentException(
                        "Gas quantities must be non-negative and non-zero");
            }
            if (Math.multiplyExact(quantity, weight) != subtotal) {
                throw new IllegalArgumentException(
                        "Gas subtotal must equal quantity times weight");
            }
            if ((scopePath == null) != (activationGeneration == null)) {
                throw new IllegalArgumentException(
                        "Gas scope and activation generation must be paired");
            }
        }
    }

    /** Exact work occurrence rejected by a failed processor charge. */
    public record RejectedWorkOccurrence(
            long ordinal,
            String kind,
            DocumentId targetDocumentId,
            String channelKey,
            String eventBlueId,
            Long occurrenceOrdinal,
            String targetManagedScopeIdentity,
            String sourceOccurrenceIdentity,
            String workIdentity) {

        /** Validates the immutable rejected-work projection. */
        public RejectedWorkOccurrence {
            SdkPreconditions.requireNonNegative(ordinal, "ordinal");
            kind = text(kind, "kind");
            targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
            channelKey = Objects.requireNonNull(channelKey, "channelKey");
            targetManagedScopeIdentity = text(
                    targetManagedScopeIdentity,
                    "targetManagedScopeIdentity");
            sourceOccurrenceIdentity = text(
                    sourceOccurrenceIdentity, "sourceOccurrenceIdentity");
            workIdentity = text(workIdentity, "workIdentity");
        }
    }

    /** Exact gas charge rejected before trace admission. */
    public record RejectedCharge(
            String rejectedChargeIdentity,
            String namespace,
            String counter,
            long quantity,
            long weight,
            long subtotal,
            String capKind,
            DocumentId capDocumentId,
            long remainingBeforeCharge,
            String ownerKind,
            String workOccurrenceIdentity,
            Long finalizationOrdinal,
            String componentIdentity,
            Long componentGeneration) {

        /** Validates the required rejected-charge fields. */
        public RejectedCharge {
            rejectedChargeIdentity = text(
                    rejectedChargeIdentity, "rejectedChargeIdentity");
            namespace = text(namespace, "namespace");
            counter = text(counter, "counter");
            capKind = text(capKind, "capKind");
            ownerKind = text(ownerKind, "ownerKind");
            if (quantity <= 0L || weight < 0L || subtotal < 0L
                    || remainingBeforeCharge < 0L) {
                throw new IllegalArgumentException(
                        "Rejected gas quantities are invalid");
            }
            if (Math.multiplyExact(quantity, weight) != subtotal
                    || subtotal <= remainingBeforeCharge) {
                throw new IllegalArgumentException(
                        "Rejected gas charge does not exceed its allowance");
            }
        }
    }

    /** Exact typed resource required to resume a suspended attempt. */
    public record ResourceDemand(
            String kind,
            String demandIdentity,
            DocumentId sourceDocumentId,
            String sourcePath,
            String suppliedValueBlueId) {

        /** Validates the complete resource-demand projection. */
        public ResourceDemand {
            kind = text(kind, "kind");
            demandIdentity = text(demandIdentity, "demandIdentity");
            sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            suppliedValueBlueId = text(
                    suppliedValueBlueId, "suppliedValueBlueId");
        }
    }

    /** Closed SDK-owned occurrence-resolution failure vocabulary. */
    public enum ResolutionStatus {
        /** The supplied pure reference or partial value is unavailable. */
        MISSING_EXACT_CONTENT,
        /** More than one managed lineage matches the supplied exact value. */
        AMBIGUOUS_MANAGED_LINEAGE,
        /** More than one retained epoch matches within the selected lineage. */
        AMBIGUOUS_MANAGED_EPOCH,
        /** The value appears progressed but no retained lineage proves it. */
        UNPROVEN_MANAGED_HISTORY,
        /** Explicit selection evidence disagrees with retained exact state. */
        EXACT_STATE_MISMATCH,
        /** Authored content cannot initialize a valid managed document. */
        INVALID_AUTHORED_DOCUMENT
    }

    /** Typed host-persistable SDK evidence for one unresolved demand. */
    public record ManagedOccurrenceResolutionIssue(
            String demandIdentity,
            ResolutionStatus status,
            String diagnostic) {

        /** Validates immutable SDK matching evidence. */
        public ManagedOccurrenceResolutionIssue {
            demandIdentity = text(demandIdentity, "demandIdentity");
            status = Objects.requireNonNull(status, "status");
            diagnostic = text(diagnostic, "diagnostic");
        }
    }

    private static String text(String value, String label) {
        return SdkPreconditions.requireText(value, label);
    }

    private static Optional<AutomaticResolutionStopReason> inferredStopReason(
            List<ManagedOccurrenceResolutionIssue> issues) {
        return Objects.requireNonNull(
                        issues, "managedOccurrenceResolutionIssues")
                .isEmpty()
                ? Optional.empty()
                : Optional.of(
                        AutomaticResolutionStopReason.UNRESOLVED_DEMANDS);
    }
}
