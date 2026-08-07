package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.coordination.engine.fastpath.VerifiedProcessOutput;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.PlatformCommitCompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Exact revision-bound authoritative transaction proposed by the engine. */
public final class CoordinationAtomicCommitPlan {

    private final DocumentSessionId sessionId;
    private final long expectedEpoch;
    private final String expectedRootBlueId;
    private final String expectedInitialDocumentBlueId;
    private final String expectedEnvironmentIdentity;
    private final ExternalOrderKey expectedCommittedFrontier;
    private final String expectedFragmentInventoryIdentity;
    private final String expectedSubscriptionSnapshotIdentity;
    private final long resultingEpoch;
    private final String resultingRootBlueId;
    private final String eventBlueId;
    private final ExternalOrderKey eventOrderKey;
    private final DocumentProcessingResult processResult;
    private final PlatformCommitCompanion commitCompanion;
    private final CoordinationFragmentTransition fragmentTransition;
    private final CoordinationSubscriptionUpdate subscriptionUpdate;
    private final List<String> rootOutboxEventBlueIds;
    private final String transitionIdentity;
    private final ManagedDocumentSnapshot resultingSession;
    private final DocumentEpochSnapshot resultingEpochSnapshot;
    private final VerifiedProcessOutput verifiedProcessOutput;

    /**
     * @deprecated an exact expected committed frontier cannot be inferred
     * from the legacy argument set; use the fully session-bound constructor
     */
    @Deprecated
    public CoordinationAtomicCommitPlan(
            DocumentSessionId sessionId,
            long expectedEpoch,
            String expectedRootBlueId,
            long resultingEpoch,
            String resultingRootBlueId,
            String eventBlueId,
            ExternalOrderKey eventOrderKey,
            DocumentProcessingResult processResult,
            PlatformCommitCompanion commitCompanion,
            CoordinationFragmentTransition fragmentTransition,
            CoordinationSubscriptionUpdate subscriptionUpdate,
            List<String> rootOutboxEventBlueIds,
            String transitionIdentity,
            ManagedDocumentSnapshot resultingSession,
            DocumentEpochSnapshot resultingEpochSnapshot) {
        throw new IllegalArgumentException(
                "Expected session environment, initial document, and "
                        + "committed frontier are required");
    }

    /**
     * Creates an exact current-session-bound atomic commit proposal.
     *
     * <p>The expected environment, initial document, and committed frontier
     * are part of the authoritative compare-and-set condition. They cannot be
     * inferred safely from a resulting snapshot, especially for
     * progress-only commits that preserve the Root epoch.</p>
     */
    public CoordinationAtomicCommitPlan(
            DocumentSessionId sessionId,
            long expectedEpoch,
            String expectedRootBlueId,
            String expectedInitialDocumentBlueId,
            String expectedEnvironmentIdentity,
            ExternalOrderKey expectedCommittedFrontier,
            String expectedFragmentInventoryIdentity,
            String expectedSubscriptionSnapshotIdentity,
            long resultingEpoch,
            String resultingRootBlueId,
            String eventBlueId,
            ExternalOrderKey eventOrderKey,
            DocumentProcessingResult processResult,
            PlatformCommitCompanion commitCompanion,
            CoordinationFragmentTransition fragmentTransition,
            CoordinationSubscriptionUpdate subscriptionUpdate,
            List<String> rootOutboxEventBlueIds,
            String transitionIdentity,
            ManagedDocumentSnapshot resultingSession,
            DocumentEpochSnapshot resultingEpochSnapshot) {
        this(
                sessionId,
                expectedEpoch,
                expectedRootBlueId,
                expectedInitialDocumentBlueId,
                expectedEnvironmentIdentity,
                expectedCommittedFrontier,
                expectedFragmentInventoryIdentity,
                expectedSubscriptionSnapshotIdentity,
                resultingEpoch,
                resultingRootBlueId,
                eventBlueId,
                eventOrderKey,
                processResult,
                commitCompanion,
                fragmentTransition,
                subscriptionUpdate,
                rootOutboxEventBlueIds,
                transitionIdentity,
                resultingSession,
                resultingEpochSnapshot,
                null);
    }

    /**
     * Creates a commit proposal bound to identities calculated once at the
     * verified PROCESS boundary.
     */
    public CoordinationAtomicCommitPlan(
            DocumentSessionId sessionId,
            long expectedEpoch,
            String expectedRootBlueId,
            String expectedInitialDocumentBlueId,
            String expectedEnvironmentIdentity,
            ExternalOrderKey expectedCommittedFrontier,
            String expectedFragmentInventoryIdentity,
            String expectedSubscriptionSnapshotIdentity,
            long resultingEpoch,
            String resultingRootBlueId,
            String eventBlueId,
            ExternalOrderKey eventOrderKey,
            DocumentProcessingResult processResult,
            PlatformCommitCompanion commitCompanion,
            CoordinationFragmentTransition fragmentTransition,
            CoordinationSubscriptionUpdate subscriptionUpdate,
            List<String> rootOutboxEventBlueIds,
            String transitionIdentity,
            ManagedDocumentSnapshot resultingSession,
            DocumentEpochSnapshot resultingEpochSnapshot,
            VerifiedProcessOutput verifiedProcessOutput) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (expectedEpoch < 0L || resultingEpoch < expectedEpoch) {
            throw new IllegalArgumentException("Invalid commit epochs");
        }
        this.expectedEpoch = expectedEpoch;
        this.expectedRootBlueId = requireText(
                expectedRootBlueId, "expectedRootBlueId");
        this.expectedInitialDocumentBlueId = requireText(
                expectedInitialDocumentBlueId,
                "expectedInitialDocumentBlueId");
        this.expectedEnvironmentIdentity = requireText(
                expectedEnvironmentIdentity,
                "expectedEnvironmentIdentity");
        this.expectedCommittedFrontier = Objects.requireNonNull(
                expectedCommittedFrontier,
                "expectedCommittedFrontier");
        this.expectedFragmentInventoryIdentity = requireText(
                expectedFragmentInventoryIdentity,
                "expectedFragmentInventoryIdentity");
        this.expectedSubscriptionSnapshotIdentity = requireText(
                expectedSubscriptionSnapshotIdentity,
                "expectedSubscriptionSnapshotIdentity");
        this.resultingEpoch = resultingEpoch;
        this.resultingRootBlueId = requireText(
                resultingRootBlueId, "resultingRootBlueId");
        this.eventBlueId = requireText(eventBlueId, "eventBlueId");
        this.eventOrderKey = Objects.requireNonNull(
                eventOrderKey, "eventOrderKey");
        this.processResult = Objects.requireNonNull(
                processResult, "processResult");
        this.commitCompanion = Objects.requireNonNull(
                commitCompanion, "commitCompanion");
        this.fragmentTransition = Objects.requireNonNull(
                fragmentTransition, "fragmentTransition");
        this.subscriptionUpdate = Objects.requireNonNull(
                subscriptionUpdate, "subscriptionUpdate");
        this.rootOutboxEventBlueIds = immutableText(
                rootOutboxEventBlueIds, "rootOutboxEventBlueIds");
        this.transitionIdentity = requireText(
                transitionIdentity, "transitionIdentity");
        this.resultingSession = Objects.requireNonNull(
                resultingSession, "resultingSession");
        this.resultingEpochSnapshot = resultingEpochSnapshot;
        this.verifiedProcessOutput = verifiedProcessOutput;
        validateBindings();
    }

    public DocumentSessionId sessionId() { return sessionId; }
    public long expectedEpoch() { return expectedEpoch; }
    public String expectedRootBlueId() { return expectedRootBlueId; }
    public String expectedInitialDocumentBlueId() {
        return expectedInitialDocumentBlueId;
    }
    public String expectedEnvironmentIdentity() {
        return expectedEnvironmentIdentity;
    }
    public ExternalOrderKey expectedCommittedFrontier() {
        return expectedCommittedFrontier;
    }
    public String expectedFragmentInventoryIdentity() {
        return expectedFragmentInventoryIdentity;
    }
    public String expectedSubscriptionSnapshotIdentity() {
        return expectedSubscriptionSnapshotIdentity;
    }
    public long resultingEpoch() { return resultingEpoch; }
    public String resultingRootBlueId() { return resultingRootBlueId; }
    public String eventBlueId() { return eventBlueId; }
    public ExternalOrderKey eventOrderKey() { return eventOrderKey; }
    public DocumentProcessingResult processResult() { return processResult; }
    public PlatformCommitCompanion commitCompanion() {
        return commitCompanion;
    }
    public CoordinationFragmentTransition fragmentTransition() {
        return fragmentTransition;
    }
    public CoordinationSubscriptionUpdate subscriptionUpdate() {
        return subscriptionUpdate;
    }
    public List<String> rootOutboxEventBlueIds() {
        return rootOutboxEventBlueIds;
    }
    public String transitionIdentity() { return transitionIdentity; }
    public ManagedDocumentSnapshot resultingSession() {
        return resultingSession;
    }
    public DocumentEpochSnapshot resultingEpochSnapshot() {
        return resultingEpochSnapshot;
    }

    private void validateBindings() {
        if (eventOrderKey.compareTo(expectedCommittedFrontier) <= 0) {
            throw new IllegalArgumentException(
                    "Commit event must advance the expected frontier");
        }
        if (!expectedRootBlueId.equals(commitCompanion.expectedRootBlueId())
                || !eventBlueId.equals(commitCompanion.eventBlueId())
                || !eventOrderKey.equals(commitCompanion.eventOrderKey())
                || processResult.commits()
                != commitCompanion.commitsRootAndOutbox()) {
            throw new IllegalArgumentException(
                    "Commit plan does not bind to platform companion");
        }
        if (verifiedProcessOutput != null
                && verifiedProcessOutput.platform().processResult()
                        != processResult) {
            throw new IllegalArgumentException(
                    "Verified PROCESS output belongs to another result");
        }
        String actualResultRoot = processResult.commits()
                ? verifiedProcessOutput == null
                        ? DirectBlueIdCalculator.calculateBlueId(
                                processResult.document())
                        : verifiedProcessOutput.resultingRootBlueId()
                : expectedRootBlueId;
        if (!resultingRootBlueId.equals(actualResultRoot)) {
            throw new IllegalArgumentException(
                    "Commit plan resulting Root differs from PROCESS result");
        }
        long expectedResultingEpoch = processResult.commits()
                ? expectedEpoch + 1L
                : expectedEpoch;
        if (resultingEpoch != expectedResultingEpoch) {
            throw new IllegalArgumentException(
                    "Commit plan epoch differs from platform semantics");
        }
        if (!resultingRootBlueId.equals(
                subscriptionUpdate.snapshot().rootBlueId())
                || commitCompanion.resultingRootRevision()
                        != subscriptionUpdate.snapshot().rootRevision()
                || commitCompanion.expectedRootRevision()
                        != (processResult.commits()
                                ? subscriptionUpdate.snapshot()
                                        .rootRevision() - 1L
                                : subscriptionUpdate.snapshot()
                                        .rootRevision())
                || !eventOrderKey.equals(
                        subscriptionUpdate.transitionOrderKey())) {
            throw new IllegalArgumentException(
                    "Commit plan subscription state differs from its "
                            + "resulting Root revision");
        }
        List<String> actualEvents;
        if (verifiedProcessOutput != null) {
            actualEvents = verifiedProcessOutput.emittedEventBlueIds();
        } else {
            actualEvents = new ArrayList<String>();
            for (Node event : processResult.events()) {
                actualEvents.add(
                        DirectBlueIdCalculator.calculateBlueId(event));
            }
        }
        if (!rootOutboxEventBlueIds.equals(actualEvents)) {
            throw new IllegalArgumentException(
                    "Commit plan outbox differs from Root PROCESS events");
        }
        if (!sessionId.equals(resultingSession.sessionId())
                || !expectedInitialDocumentBlueId.equals(
                        resultingSession.initialDocumentBlueId())
                || !expectedEnvironmentIdentity.equals(
                        resultingSession.environmentIdentity())
                || resultingSession.status()
                        != ManagedDocumentStatus.ACTIVE
                || resultingSession.currentEpoch() != resultingEpoch
                || !resultingRootBlueId.equals(
                        resultingSession.currentRootBlueId())
                || !eventOrderKey.equals(
                        resultingSession.committedFrontier())
                || !fragmentTransition.resultingInventory()
                        .inventoryIdentity()
                        .equals(resultingSession.fragmentInventoryIdentity())
                || resultingSession.subscriptions()
                        != subscriptionUpdate.snapshot()) {
            throw new IllegalArgumentException(
                    "Commit plan resulting session differs from its delta");
        }
        if (processResult.commits() != (resultingEpochSnapshot != null)) {
            throw new IllegalArgumentException(
                    "Only Root commits create a new epoch snapshot");
        }
        if (resultingEpochSnapshot != null
                && (!sessionId.equals(resultingEpochSnapshot.sessionId())
                || resultingEpochSnapshot.epoch() != resultingEpoch
                || !resultingRootBlueId.equals(
                        resultingEpochSnapshot.rootBlueId())
                || !expectedRootBlueId.equals(
                        resultingEpochSnapshot.priorRootBlueId())
                || !eventBlueId.equals(
                        resultingEpochSnapshot.causedByEventBlueId())
                || !eventOrderKey.equals(
                        resultingEpochSnapshot.eventOrderKey())
                || !fragmentTransition.resultingInventory()
                        .inventoryIdentity().equals(
                                resultingEpochSnapshot
                                        .fragmentInventoryIdentity())
                || !subscriptionUpdate.snapshot().digest().equals(
                        resultingEpochSnapshot
                                .subscriptionSnapshotIdentity())
                || !rootOutboxEventBlueIds.equals(
                        resultingEpochSnapshot.rootEventBlueIds())
                || processResult.totalGas()
                        != resultingEpochSnapshot.totalGas()
                || !transitionIdentity.equals(
                        resultingEpochSnapshot.transitionIdentity()))) {
            throw new IllegalArgumentException(
                    "Resulting epoch snapshot does not bind to transition");
        }
    }

    private static List<String> immutableText(
            List<String> source,
            String label) {
        List<String> result = new ArrayList<String>(
                Objects.requireNonNull(source, label));
        for (String value : result) requireText(value, label + " entry");
        return Collections.unmodifiableList(result);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }
}
