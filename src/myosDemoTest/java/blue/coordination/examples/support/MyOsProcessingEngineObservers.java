package blue.coordination.examples.support;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.ProcessRequest;
import blue.coordination.engine.spi.CoordinationProcessingEngineObserver;
import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.language.processor.PlatformProcessingResult;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Failure-isolated observer composition for the executable MyOS host. */
final class MyOsProcessingEngineObservers {

    private MyOsProcessingEngineObservers() { }

    static CoordinationProcessingEngineObserver compose(
            CoordinationProcessingEngineObserver... supplied) {
        List<CoordinationProcessingEngineObserver> observers = Arrays.stream(
                        Objects.requireNonNull(supplied, "supplied"))
                .map(observer -> Objects.requireNonNull(observer, "observer"))
                .toList();
        return new CoordinationProcessingEngineObserver() {
            @Override
            public void onAdmission(
                    DocumentRegistration registration,
                    DocumentAdmissionResult result) {
                notifyEach(observers, value -> value.onAdmission(
                        registration, result));
            }

            @Override
            public void onPlan(CoordinationProcessingPlan plan) {
                notifyEach(observers, value -> value.onPlan(plan));
            }

            @Override
            public void onPlanTiming(
                    ProcessRequest request,
                    CoordinationProcessingPlan plan,
                    long elapsedNanos) {
                notifyEach(observers, value -> value.onPlanTiming(
                        request, plan, elapsedNanos));
            }

            @Override
            public void onIndexedPlanTiming(
                    CoordinationProcessingPlan plan,
                    long elapsedNanos) {
                notifyEach(observers, value -> value.onIndexedPlanTiming(
                        plan, elapsedNanos));
            }

            @Override
            public void onBatchLoad(
                    CoordinationProcessingPlan plan,
                    LoadedProcessingBundle bundle) {
                notifyEach(observers, value -> value.onBatchLoad(plan, bundle));
            }

            @Override
            public void onBundleLoadTiming(
                    CoordinationProcessingPlan plan,
                    LoadedProcessingBundle bundle,
                    long elapsedNanos) {
                notifyEach(observers, value -> value.onBundleLoadTiming(
                        plan, bundle, elapsedNanos));
            }

            @Override
            public void onProcessInputMaterializationTiming(
                    CoordinationProcessingPlan plan,
                    long elapsedNanos) {
                notifyEach(observers, value ->
                        value.onProcessInputMaterializationTiming(
                                plan, elapsedNanos));
            }

            @Override
            public void onPlatformProcessTiming(
                    CoordinationProcessingPlan plan,
                    PlatformProcessingResult result,
                    long elapsedNanos) {
                notifyEach(observers, value -> value.onPlatformProcessTiming(
                        plan, result, elapsedNanos));
            }

            @Override
            public void onHybridFrontierProofTiming(
                    CoordinationProcessingPlan plan,
                    long elapsedNanos) {
                notifyEach(observers, value ->
                        value.onHybridFrontierProofTiming(
                                plan, elapsedNanos));
            }

            @Override
            public void onRetainedReferenceMaterializationTiming(
                    CoordinationProcessingPlan plan,
                    long elapsedNanos) {
                notifyEach(observers, value ->
                        value.onRetainedReferenceMaterializationTiming(
                                plan, elapsedNanos));
            }

            @Override
            public void onSubscriptionProjectionTiming(
                    CoordinationProcessingPlan plan,
                    long elapsedNanos) {
                notifyEach(observers, value ->
                        value.onSubscriptionProjectionTiming(
                                plan, elapsedNanos));
            }

            @Override
            public void onSubscriptionProjectionColdFallback(
                    CoordinationProcessingPlan plan,
                    String reason) {
                notifyEach(observers, value ->
                        value.onSubscriptionProjectionColdFallback(
                                plan, reason));
            }

            @Override
            public void onFragmentTransitionPlanningTiming(
                    CoordinationProcessingPlan plan,
                    long elapsedNanos) {
                notifyEach(observers, value ->
                        value.onFragmentTransitionPlanningTiming(
                                plan, elapsedNanos));
            }

            @Override
            public void onPreparedResultContextTiming(
                    CoordinationProcessingPlan plan,
                    long elapsedNanos) {
                notifyEach(observers, value ->
                        value.onPreparedResultContextTiming(
                                plan, elapsedNanos));
            }

            @Override
            public void onSubscriptionAndFragmentTransitionTiming(
                    CoordinationProcessingPlan plan,
                    CoordinationSubscriptionUpdate subscriptionUpdate,
                    CoordinationFragmentTransition fragmentTransition,
                    long elapsedNanos) {
                notifyEach(observers, value ->
                        value.onSubscriptionAndFragmentTransitionTiming(
                                plan,
                                subscriptionUpdate,
                                fragmentTransition,
                                elapsedNanos));
            }

            @Override
            public void onProcessComplete(CoordinationTransition transition) {
                notifyEach(observers, value ->
                        value.onProcessComplete(transition));
            }

            @Override
            public void onFragmentTransition(
                    CoordinationFragmentTransition transition) {
                notifyEach(observers, value ->
                        value.onFragmentTransition(transition));
            }

            @Override
            public void onCommit(CommitOutcome outcome) {
                notifyEach(observers, value -> value.onCommit(outcome));
            }

            @Override
            public void onCommitTiming(
                    CoordinationTransition transition,
                    CommitOutcome outcome,
                    long elapsedNanos) {
                notifyEach(observers, value -> value.onCommitTiming(
                        transition, outcome, elapsedNanos));
            }

            @Override
            public void onProcessAndCommitTiming(
                    ProcessRequest request,
                    CommitOutcome outcome,
                    long elapsedNanos) {
                notifyEach(observers, value -> value.onProcessAndCommitTiming(
                        request, outcome, elapsedNanos));
            }
        };
    }

    private static void notifyEach(
            List<CoordinationProcessingEngineObserver> observers,
            Consumer<CoordinationProcessingEngineObserver> notification) {
        for (CoordinationProcessingEngineObserver observer : observers) {
            try {
                notification.accept(observer);
            } catch (RuntimeException ignored) {
                // Diagnostic observers cannot change processing semantics.
            }
        }
    }
}
