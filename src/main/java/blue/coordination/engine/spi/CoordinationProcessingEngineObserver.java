package blue.coordination.engine.spi;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.ProcessRequest;
import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.language.processor.PlatformProcessingResult;

/** Failure-isolated, non-semantic lifecycle observer for engine diagnostics. */
public interface CoordinationProcessingEngineObserver {
    default void onAdmission(
            DocumentRegistration registration,
            DocumentAdmissionResult result) { }
    default void onPlan(CoordinationProcessingPlan plan) { }
    /** Exact elapsed time of one successful public {@code plan} call. */
    default void onPlanTiming(
            ProcessRequest request,
            CoordinationProcessingPlan plan,
            long elapsedNanos) { }
    /** Exact elapsed time of one successful stored-event indexed plan. */
    default void onIndexedPlanTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) { }
    default void onBatchLoad(
            CoordinationProcessingPlan plan,
            LoadedProcessingBundle bundle) { }
    /** Exact elapsed time spent in the configured request-local bundle load. */
    default void onBundleLoadTiming(
            CoordinationProcessingPlan plan,
            LoadedProcessingBundle bundle,
            long elapsedNanos) { }
    /** Exact elapsed time preparing exact Root/event PROCESS inputs. */
    default void onProcessInputMaterializationTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) { }
    /** Exact elapsed time of the single public Contracts PROCESS call. */
    default void onPlatformProcessTiming(
            CoordinationProcessingPlan plan,
            PlatformProcessingResult result,
            long elapsedNanos) { }
    /** Exact elapsed time spent proving retained hybrid-result bindings. */
    default void onHybridFrontierProofTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) { }
    /** Exact elapsed time spent expanding retained PROCESS-result references. */
    default void onRetainedReferenceMaterializationTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) { }
    /** Exact elapsed time spent projecting the committed subscription state. */
    default void onSubscriptionProjectionTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) { }
    /** Typed cold-path diagnostic emitted only for a deliberate fallback. */
    default void onSubscriptionProjectionColdFallback(
            CoordinationProcessingPlan plan,
            String reason) { }
    /** Exact elapsed time spent planning the resulting fragment transition. */
    default void onFragmentTransitionPlanningTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) { }
    /** Exact elapsed time preparing the immutable next-epoch warm context. */
    default void onPreparedResultContextTiming(
            CoordinationProcessingPlan plan,
            long elapsedNanos) { }
    /** Exact combined subscription-projection and fragment-transition time. */
    default void onSubscriptionAndFragmentTransitionTiming(
            CoordinationProcessingPlan plan,
            CoordinationSubscriptionUpdate subscriptionUpdate,
            CoordinationFragmentTransition fragmentTransition,
            long elapsedNanos) { }
    default void onProcessComplete(CoordinationTransition transition) { }
    default void onFragmentTransition(
            CoordinationFragmentTransition transition) { }
    default void onCommit(CommitOutcome outcome) { }
    /** Exact elapsed time of one successful public {@code commit} call. */
    default void onCommitTiming(
            CoordinationTransition transition,
            CommitOutcome outcome,
            long elapsedNanos) { }
    /** Exact elapsed time of one successful convenience end-to-end call. */
    default void onProcessAndCommitTiming(
            ProcessRequest request,
            CommitOutcome outcome,
            long elapsedNanos) { }

    /** Returns an observer that deliberately performs no work. */
    static CoordinationProcessingEngineObserver none() {
        return new CoordinationProcessingEngineObserver() { };
    }
}
