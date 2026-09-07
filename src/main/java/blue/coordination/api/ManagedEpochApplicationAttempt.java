package blue.coordination.api;

import blue.language.processor.closure.ClosureAttemptResult;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact processor evidence for one attempted occurrence-specific catch-up
 * application, including attempts that atomically rolled back.
 */
public record ManagedEpochApplicationAttempt(
        ManagedEpochApplicationWork work,
        ClosureAttemptResult attempt,
        boolean published,
        boolean replayed,
        Optional<ManagedEpochApplicationReceipt> receipt,
        long automaticRetryCount,
        Optional<AutomaticResolutionStopReason>
                automaticResolutionStopReason,
        List<ManagedOccurrenceResolutionIssue>
                managedOccurrenceResolutionIssues,
        Optional<PublicationFailure> publicationFailure,
        List<ContractsClosureDispatchAttempt.ManagedOccurrenceResolution> managedOccurrenceResolutions,
        List<blue.language.processor.closure.ComponentSnapshot> inputComponents,
        List<ContractsClosureDispatchAttempt.OperationRouteChange> operationRouteChanges) {

    /**
     * Preserves the original constructor for hosts that do not retain
     * automatic occurrence-resolution evidence.
     */
    public ManagedEpochApplicationAttempt(
            ManagedEpochApplicationWork work,
            ClosureAttemptResult attempt,
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
            ClosureAttemptResult attempt,
            boolean published,
            boolean replayed,
            Optional<ManagedEpochApplicationReceipt> receipt,
            long automaticRetryCount,
            List<ManagedOccurrenceResolutionIssue>
                    managedOccurrenceResolutionIssues) {
        this(work, attempt, published, replayed, receipt, automaticRetryCount,
                inferredStopReason(managedOccurrenceResolutionIssues),
                managedOccurrenceResolutionIssues, Optional.empty());
    }

    /**
     * Preserves the complete pre-publication-failure constructor.
     */
    public ManagedEpochApplicationAttempt(
            ManagedEpochApplicationWork work,
            ClosureAttemptResult attempt,
            boolean published,
            boolean replayed,
            Optional<ManagedEpochApplicationReceipt> receipt,
            long automaticRetryCount,
            Optional<AutomaticResolutionStopReason>
                    automaticResolutionStopReason,
            List<ManagedOccurrenceResolutionIssue>
                    managedOccurrenceResolutionIssues) {
        this(work, attempt, published, replayed, receipt, automaticRetryCount,
                automaticResolutionStopReason, managedOccurrenceResolutionIssues,
                Optional.empty());
    }

    /** Preserves the constructor preceding committed managed-surface evidence. */
    public ManagedEpochApplicationAttempt(
            ManagedEpochApplicationWork work, ClosureAttemptResult attempt,
            boolean published, boolean replayed,
            Optional<ManagedEpochApplicationReceipt> receipt,
            long automaticRetryCount,
            Optional<AutomaticResolutionStopReason> automaticResolutionStopReason,
            List<ManagedOccurrenceResolutionIssue> managedOccurrenceResolutionIssues,
            Optional<PublicationFailure> publicationFailure) {
        this(work, attempt, published, replayed, receipt, automaticRetryCount,
                automaticResolutionStopReason, managedOccurrenceResolutionIssues,
                publicationFailure, List.of(), List.of(), List.of());
    }

    /** Validates the relationship between processor and publication evidence. */
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
        managedOccurrenceResolutions = List.copyOf(Objects.requireNonNull(
                managedOccurrenceResolutions, "managedOccurrenceResolutions"));
        inputComponents = List.copyOf(Objects.requireNonNull(inputComponents, "inputComponents"));
        operationRouteChanges = List.copyOf(Objects.requireNonNull(operationRouteChanges, "operationRouteChanges"));
        if (!published && (!managedOccurrenceResolutions.isEmpty()
                || !inputComponents.isEmpty() || !operationRouteChanges.isEmpty())) {
            throw new IllegalArgumentException("Unpublished managed attempts cannot expose committed surface evidence");
        }
        if (automaticRetryCount < 0L) {
            throw new IllegalArgumentException(
                    "automaticRetryCount must be non-negative");
        }
        if (published != receipt.isPresent()) {
            throw new IllegalArgumentException(
                    "Published managed attempts require one application receipt");
        }
        if (published && (!attempt.isComplete()
                || !attempt.processResult().commits())) {
            throw new IllegalArgumentException(
                    "Only a committing managed attempt can be published");
        }
        if (receipt.isPresent()
                && !receipt.orElseThrow().workIdentity().equals(
                        work.workIdentity())) {
            throw new IllegalArgumentException(
                    "Managed attempt receipt identifies different work");
        }
        if (replayed && !published) {
            throw new IllegalArgumentException(
                    "A replayed managed attempt must already be published");
        }
        if ((published || attempt.isComplete())
                && !managedOccurrenceResolutionIssues.isEmpty()) {
            throw new IllegalArgumentException(
                    "Only a suspended unpublished managed attempt may expose "
                            + "unresolved matching evidence");
        }
        if ((published || attempt.isComplete())
                && automaticResolutionStopReason.isPresent()) {
            throw new IllegalArgumentException(
                    "Only a suspended unpublished managed attempt may expose "
                            + "an automatic resolution stop reason");
        }
        if (publicationFailure.isPresent()
                && (published
                || replayed
                || !attempt.isComplete()
                || !attempt.processResult().commits())) {
            throw new IllegalArgumentException(
                    "A publication failure requires one complete committing "
                            + "but unpublished managed attempt");
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
                .map(demand -> demand.demandIdentity())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> issueIdentities = new LinkedHashSet<>();
        for (ManagedOccurrenceResolutionIssue issue
                : managedOccurrenceResolutionIssues) {
            if (!demandIdentities.contains(issue.demandIdentity())) {
                throw new IllegalArgumentException(
                        "Unresolved matching evidence does not identify a "
                                + "retained resource demand "
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
            CoordinationErrorCode code,
            String message,
            Map<String, String> details) {

        /** Retains immutable exact failure fields for durable host evidence. */
        public PublicationFailure {
            code = Objects.requireNonNull(code, "code");
            message = requireText(message, "message");
            details = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            details, "details")));
            details.forEach((key, value) -> {
                requireText(key, "detail key");
                requireText(value, "detail value");
            });
        }
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

    /** Closed automatic managed-occurrence matching failure vocabulary. */
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

    /** Typed host-persistable classification for one unresolved demand. */
    public record ManagedOccurrenceResolutionIssue(
            String demandIdentity,
            ResolutionStatus status,
            String diagnostic) {

        /** Validates immutable unresolved matching evidence. */
        public ManagedOccurrenceResolutionIssue {
            demandIdentity = requireText(
                    demandIdentity, "demandIdentity");
            status = Objects.requireNonNull(status, "status");
            diagnostic = requireText(diagnostic, "diagnostic");
        }
    }

    private static String requireText(String value, String label) {
        String selected = Objects.requireNonNull(value, label);
        if (selected.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return selected;
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
