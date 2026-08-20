package blue.coordination.api;

import blue.coordination.internal.DefaultCoordinationEngine;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ClosureInvocationInput;

import java.util.List;
import java.util.Set;

/**
 * Deterministic single-process Coordination environment.
 *
 * <p>The first production implementation is deliberately in-memory. Closing
 * the engine releases its borrowed Language, Contracts, and BEX runtimes.</p>
 */
public interface CoordinationEngine extends AutoCloseable {
    /**
     * Creates the legacy single-document in-memory engine.
     *
     * @deprecated normal applications should use
     *         {@link blue.coordination.sdk.BlueCoordination#inMemory()};
     *         advanced compatibility callers should name
     *         {@link #legacyInMemory()} explicitly
     */
    @Deprecated(since = "3.0.0-rc.2", forRemoval = false)
    static CoordinationEngine inMemory() {
        return legacyInMemory();
    }

    /** Creates the explicitly named legacy in-memory compatibility engine. */
    static CoordinationEngine legacyInMemory() {
        return builder().inMemory().build();
    }

    /**
     * Creates the in-memory Contracts 1.0 engine for exact release artifacts.
     *
     * @param configuration final artifact identities and public Root lineages
     * @return a new Contracts 1.0 engine
     */
    static CoordinationEngine inMemoryContracts10(
            Contracts10Configuration configuration) {
        return DefaultCoordinationEngine.createContracts10(configuration);
    }

    /** Starts configuration of a Coordination engine. */
    static Builder builder() {
        return new Builder();
    }

    /** Registers one authenticated append-only Timeline. */
    Timeline registerTimeline(String timelineId, String actorId);

    /** Admits one authored document atomically and returns its initial state. */
    DocumentSnapshot startDocument(DocumentId documentId, String authoredYaml);

    /**
     * Admits one authored document under an explicit temporal history policy.
     * A verified frontier is required only for {@link AdmissionPolicy#FROM_FRONTIER}.
     */
    DocumentSnapshot startDocument(
            DocumentId documentId,
            String authoredYaml,
            AdmissionPolicy policy,
            ExternalOrderKey verifiedFrontier);

    /**
     * Verifies and atomically admits one complete Contracts 1.0 closure.
     *
     * <p>This explicit multi-document boundary is available only on an engine
     * created by {@link #inMemoryContracts10(Contracts10Configuration)}.
     * Cyclic member bodies remain authenticated by the supplied complete
     * closure proof; this method never degrades them into independent legacy
     * document starts.</p>
     *
     * @param input exact typed {@code ADMIT_CLOSURE} invocation
     * @param policy host temporal admission policy for every new member
     * @param verifiedFrontier retained frontier required by
     *        {@link AdmissionPolicy#FROM_FRONTIER}, otherwise {@code null}
     * @return exact Contracts attempt and publication receipt
     */
    ContractsClosureAdmissionReceipt admitContractsClosure(
            ClosureInvocationInput input,
            AdmissionPolicy policy,
            ExternalOrderKey verifiedFrontier);

    /**
     * Registers host-owned temporal admission evidence for future occurrences
     * of one embedded DocumentId. Process Embedded itself remains limited to
     * paths and collectionPaths.
     */
    void configureEmbeddedAdmission(
            DocumentId documentId,
            ActivationMode mode,
            ExternalOrderKey verifiedCompleteThrough);

    /**
     * Registers one attachment-specific admission plan. The plan must match
     * the exact parent occurrence, child state, and retained attachment entry;
     * it is consumed only when that occurrence is published successfully.
     * A null epoch permits resolution only when the state identifies one epoch.
     */
    void configureEmbeddedAdmission(
            DocumentId parentDocumentId,
            String absoluteChildPath,
            DocumentId childDocumentId,
            String admittedStateBlueId,
            Long admittedEpoch,
            ActivationMode mode,
            ExternalOrderKey verifiedCompleteThrough,
            String completenessProofIdentity,
            String expectedAttachmentEntryBlueId);

    /** Resolves and retains one complete exact YAML value. */
    ExactValue exactValue(String sourceYaml);

    /** Builds a structurally shared request that refers to an exact value. */
    ExactValue referenceRequest(String field, ExactValue exactValue);

    /** Atomically appends one operation without dispatching it. */
    TimelineEntry append(Timeline timeline, Operation operation);

    /** Appends one operation at an explicit positive logical timestamp. */
    TimelineEntry appendAt(
            Timeline timeline,
            Operation operation,
            long timestampMicros);

    /**
     * Validates and appends one exact external Timeline Entry without routing
     * it or invoking any document processor.
     */
    TimelineAppendReceipt appendTimelineEntry(Node exactEntry);

    /** Selects and processes canonical external work until quiescent. */
    ProcessingDrainReceipt drain();

    /** Processes deterministic work without exceeding the supplied limits. */
    ProcessingDrainReceipt drain(DrainBudget budget);

    /**
     * Drains every eligible entry through the inclusive canonical cutoff.
     * The cutoff cannot force a named entry to overtake earlier work.
     */
    ProcessingDrainReceipt drainThrough(ExternalOrderKey inclusiveCutoff);

    /** Returns the number of managed documents selected by the route index. */
    int routeTargetCount(TimelineEntry entry);

    /** Reads a coherent READY document; intermediate state fails closed. */
    DocumentSnapshot document(DocumentId documentId);

    /** Reads the latest committed state for audit and recovery tooling. */
    DocumentSnapshot auditDocument(DocumentId documentId);

    /** Reads the immutable ordered revision stream of one document. */
    List<DocumentRevision> history(DocumentId documentId);

    /** Returns source Timeline IDs reachable by a document and its children. */
    Set<String> effectiveTimelineIds(DocumentId documentId);

    /** Captures immutable counters, phase timers, and in-memory gauges. */
    CoordinationMetrics metrics();

    /** Releases runtime resources; repeated close calls are harmless. */
    @Override
    void close();

    /** Builder kept intentionally small until a durable host boundary exists. */
    final class Builder {
        private boolean inMemory;

        /** Selects the supported in-memory implementation. */
        public Builder inMemory() {
            inMemory = true;
            return this;
        }

        /** Validates the configuration and creates the engine. */
        public CoordinationEngine build() {
            if (!inMemory) {
                throw new CoordinationException(
                        CoordinationErrorCode.ATOMIC_COMMIT_FAILED,
                        "Select the supported in-memory engine");
            }
            return DefaultCoordinationEngine.create();
        }
    }

    /** Temporal policy for admitting a top-level managed document. */
    enum AdmissionPolicy {
        /** Replay every eligible source fact already present in the journal. */
        FULL_HISTORY,
        /** Replay entries strictly after a verified persisted frontier. */
        FROM_FRONTIER,
        /** The document is born at the current environment frontier. */
        FROM_NOW
    }

    /** Deterministic limits enforced between entries and PROCESS commits. */
    record DrainBudget(
            long maxCommittedProcessTransitions,
            long maxSelectedEntries) {
        public DrainBudget {
            if (maxCommittedProcessTransitions <= 0L
                    || maxSelectedEntries <= 0L) {
                throw new IllegalArgumentException(
                        "Drain limits must be positive");
            }
        }

        public static DrainBudget unlimited() {
            return new DrainBudget(Long.MAX_VALUE, Long.MAX_VALUE);
        }
    }
}
