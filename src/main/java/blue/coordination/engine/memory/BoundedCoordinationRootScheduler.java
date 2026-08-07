package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

/**
 * Starts expensive work for independent Root sessions concurrently, but
 * publishes successful transitions in the immutable route order.
 *
 * <p>This class intentionally does not own delivery receipts. The surrounding
 * dispatch ledger claims a target immediately before {@link Result#commit}
 * and records the authoritative evidence returned by that call. That keeps
 * retry semantics unchanged while avoiding an IN_FLIGHT receipt for a future
 * which is merely waiting in a queue.</p>
 */
public final class BoundedCoordinationRootScheduler<P> {

    private static final Comparator<IndexedSessionCandidates> TARGET_ORDER =
            Comparator.naturalOrder();

    private final ExecutorService executor;
    private final CoordinationTwoPhaseDeliveryExecutor<P> deliveryExecutor;
    private final CoordinationParallelismPolicy policy;
    private final CoordinationRootPreparationObserver observer;
    private final Lock lifecycleReadLock;
    private final Runnable requireOpen;
    private final AtomicInteger activePreparations = new AtomicInteger();
    private final AtomicInteger peakPreparations = new AtomicInteger();
    private final AtomicInteger outstandingResults = new AtomicInteger();

    public BoundedCoordinationRootScheduler(
            ExecutorService executor,
            CoordinationTwoPhaseDeliveryExecutor<P> deliveryExecutor,
            CoordinationParallelismPolicy policy,
            CoordinationRootPreparationObserver observer) {
        this(executor, deliveryExecutor, policy, observer, null, null);
    }

    BoundedCoordinationRootScheduler(
            ExecutorService executor,
            CoordinationTwoPhaseDeliveryExecutor<P> deliveryExecutor,
            CoordinationParallelismPolicy policy,
            CoordinationRootPreparationObserver observer,
            Lock lifecycleReadLock,
            Runnable requireOpen) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.deliveryExecutor = Objects.requireNonNull(
                deliveryExecutor, "deliveryExecutor");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.observer = Objects.requireNonNull(observer, "observer");
        if ((lifecycleReadLock == null) != (requireOpen == null)) {
            throw new IllegalArgumentException(
                    "Lifecycle lock and open check must be supplied together");
        }
        this.lifecycleReadLock = lifecycleReadLock;
        this.requireOpen = requireOpen;
    }

    /**
     * Schedules results and returns immediately in canonical session order.
     * Callers invoke {@link Result#awaitPrepared()}, claim the delivery, then
     * invoke {@link Result#commit()} in list order. This permits Root A to
     * commit before a later Root B preparation failure is observed, while no
     * queued future is incorrectly represented as an in-flight delivery.
     */
    public List<Result<P>> schedule(
            StoredCoordinationEvent event,
            List<IndexedSessionCandidates> targets,
            PrefetchPolicy prefetchPolicy) {
        enterLifecycle();
        try {
            return scheduleGuarded(event, targets, prefetchPolicy);
        } finally {
            exitLifecycle();
        }
    }

    private List<Result<P>> scheduleGuarded(
            StoredCoordinationEvent event,
            List<IndexedSessionCandidates> targets,
            PrefetchPolicy prefetchPolicy) {
        StoredCoordinationEvent checkedEvent = Objects.requireNonNull(
                event, "event");
        PrefetchPolicy checkedPolicy = Objects.requireNonNull(
                prefetchPolicy, "prefetchPolicy");
        List<IndexedSessionCandidates> canonical = canonicalTargets(targets);
        if (canonical.isEmpty()) {
            return Collections.emptyList();
        }

        Semaphore permits = new Semaphore(
                policy.maximumConcurrentPreparations());
        List<Future<Prepared<P>>> futures =
                new ArrayList<Future<Prepared<P>>>(canonical.size());
        outstandingResults.addAndGet(canonical.size());
        try {
            for (IndexedSessionCandidates target : canonical) {
                futures.add(executor.submit(task(
                        checkedEvent, target, checkedPolicy, permits)));
            }
        } catch (RejectedExecutionException failure) {
            cancel(futures, 0);
            outstandingResults.addAndGet(-canonical.size());
            throw failure;
        }

        List<Result<P>> results = new ArrayList<Result<P>>(canonical.size());
        for (int index = 0; index < futures.size(); index++) {
            results.add(new Result<P>(
                    checkedEvent.eventBlueId(),
                    canonical.get(index),
                    futures.get(index),
                    futures,
                    index,
                    deliveryExecutor,
                    policy,
                    observer,
                    outstandingResults));
        }
        return Collections.unmodifiableList(results);
    }

    private void enterLifecycle() {
        if (lifecycleReadLock == null) {
            return;
        }
        lifecycleReadLock.lock();
        boolean entered = false;
        try {
            requireOpen.run();
            entered = true;
        } finally {
            if (!entered) {
                lifecycleReadLock.unlock();
            }
        }
    }

    private void exitLifecycle() {
        if (lifecycleReadLock != null) {
            lifecycleReadLock.unlock();
        }
    }

    private Callable<Prepared<P>> task(
            final StoredCoordinationEvent event,
            final IndexedSessionCandidates target,
            final PrefetchPolicy prefetchPolicy,
            final Semaphore permits) {
        return new Callable<Prepared<P>>() {
            @Override
            public Prepared<P> call() throws Exception {
                permits.acquire();
                int active = activePreparations.incrementAndGet();
                updatePeak(active);
                long started = System.nanoTime();
                try {
                    P value = deliveryExecutor.prepare(
                            event, target, prefetchPolicy);
                    long elapsed = System.nanoTime() - started;
                    observer.prepared(target.sessionId(), elapsed);
                    return new Prepared<P>(value);
                } finally {
                    activePreparations.decrementAndGet();
                    permits.release();
                }
            }
        };
    }

    public int activePreparationCount() {
        return activePreparations.get();
    }

    public int peakPreparationCount() {
        return peakPreparations.get();
    }

    public int outstandingResultCount() {
        return outstandingResults.get();
    }

    public boolean isQuiescent() {
        return activePreparations.get() == 0
                && outstandingResults.get() == 0;
    }

    private void updatePeak(int active) {
        int observed = peakPreparations.get();
        while (active > observed
                && !peakPreparations.compareAndSet(observed, active)) {
            observed = peakPreparations.get();
        }
    }

    private static List<IndexedSessionCandidates> canonicalTargets(
            List<IndexedSessionCandidates> targets) {
        List<IndexedSessionCandidates> canonical =
                new ArrayList<IndexedSessionCandidates>(
                        Objects.requireNonNull(targets, "targets"));
        for (IndexedSessionCandidates target : canonical) {
            Objects.requireNonNull(target, "target");
        }
        canonical.sort(TARGET_ORDER);
        for (int index = 1; index < canonical.size(); index++) {
            if (canonical.get(index - 1).sessionId().equals(
                    canonical.get(index).sessionId())) {
                throw new IllegalArgumentException(
                        "Duplicate Root session target: "
                                + canonical.get(index).sessionId());
            }
        }
        return canonical;
    }

    private static void cancel(
            List<? extends Future<?>> futures,
            int first) {
        for (int index = first; index < futures.size(); index++) {
            futures.get(index).cancel(true);
        }
    }

    private static final class Prepared<P> {
        private final P value;

        private Prepared(P value) {
            this.value = Objects.requireNonNull(value, "prepared");
        }
    }

    /** One single-use prepared Root transition. */
    public static final class Result<P> {
        private enum State { SCHEDULED, PREPARED, COMMITTED, DISCARDED }

        private final String eventBlueId;
        private final IndexedSessionCandidates target;
        private final Future<Prepared<P>> future;
        private final List<? extends Future<?>> pageFutures;
        private final int pageIndex;
        private final CoordinationTwoPhaseDeliveryExecutor<P> executor;
        private final CoordinationParallelismPolicy policy;
        private final CoordinationRootPreparationObserver observer;
        private final AtomicInteger outstandingResults;
        private P prepared;
        private State state = State.SCHEDULED;

        private Result(
                String eventBlueId,
                IndexedSessionCandidates target,
                Future<Prepared<P>> future,
                List<? extends Future<?>> pageFutures,
                int pageIndex,
                CoordinationTwoPhaseDeliveryExecutor<P> executor,
                CoordinationParallelismPolicy policy,
                CoordinationRootPreparationObserver observer,
                AtomicInteger outstandingResults) {
            this.eventBlueId = Objects.requireNonNull(
                    eventBlueId, "eventBlueId");
            this.target = target;
            this.future = future;
            this.pageFutures = pageFutures;
            this.pageIndex = pageIndex;
            this.executor = executor;
            this.policy = policy;
            this.observer = observer;
            this.outstandingResults = outstandingResults;
        }

        public IndexedSessionCandidates target() {
            return target;
        }

        /**
         * Waits for only this canonical target. The caller must do this before
         * opening the ledger attempt. Earlier targets can already be committed
         * while later preparations continue in parallel.
         */
        public synchronized void awaitPrepared() {
            require(State.SCHEDULED);
            try {
                prepared = future.get().value;
                state = State.PREPARED;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancelLater();
                observer.failed(target.sessionId(), interrupted);
                throw new CoordinationParallelPreparationException(
                        target.sessionId(), interrupted);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause() == null
                        ? failure : failure.getCause();
                cancelLater();
                observer.failed(target.sessionId(), cause);
                throw new CoordinationParallelPreparationException(
                        target.sessionId(), cause);
            } catch (CancellationException cancelled) {
                cancelLater();
                observer.failed(target.sessionId(), cancelled);
                throw new CoordinationParallelPreparationException(
                        target.sessionId(), cancelled);
            }
        }

        public synchronized CoordinationCommittedDelivery commit() {
            require(State.PREPARED);
            long started = System.nanoTime();
            try {
                CoordinationCommittedDelivery committed =
                        Objects.requireNonNull(
                                executor.commit(prepared), "committed");
                requireBinding(committed);
                state = State.COMMITTED;
                outstandingResults.decrementAndGet();
                observer.committed(
                        target.sessionId(), System.nanoTime() - started);
                return committed;
            } catch (RuntimeException | Error failure) {
                observer.failed(target.sessionId(), failure);
                throw failure;
            }
        }

        synchronized void settleCommittedAfterReconciliation(
                CoordinationCommittedDelivery committed) {
            requireBinding(Objects.requireNonNull(
                    committed, "committed"));
            if (state == State.COMMITTED) {
                return;
            }
            if (state == State.DISCARDED) {
                throw new IllegalStateException(
                        "A discarded delivery cannot be reconciled");
            }
            if (state == State.SCHEDULED) {
                boolean cancelled = future.cancel(true);
                if (!cancelled) {
                    discardCompletedFuture();
                }
            }
            state = State.COMMITTED;
            outstandingResults.decrementAndGet();
        }

        public synchronized void discard() {
            if (state == State.DISCARDED) {
                return;
            }
            if (state == State.COMMITTED) {
                throw new IllegalStateException(
                        "A committed delivery cannot be discarded");
            }
            if (state == State.SCHEDULED) {
                boolean cancelled = future.cancel(true);
                if (!cancelled) {
                    discardCompletedFuture();
                }
            } else {
                executor.discard(prepared);
            }
            state = State.DISCARDED;
            outstandingResults.decrementAndGet();
            observer.discarded(target.sessionId());
        }

        private void cancelLater() {
            if (policy.stopAfterFirstCanonicalFailure()) {
                cancel(pageFutures, pageIndex + 1);
            }
        }

        private void requireBinding(
                CoordinationCommittedDelivery committed) {
            DocumentSessionId expectedSession = target.sessionId();
            if (!eventBlueId.equals(committed.eventBlueId())
                    || !expectedSession.equals(committed.sessionId())
                    || target.plannedEpoch() != committed.plannedEpoch()
                    || !target.plannedRootBlueId().equals(
                            committed.plannedRootBlueId())) {
                throw new IllegalStateException(
                        "Committed delivery does not bind to prepared target "
                                + expectedSession);
            }
        }

        private void discardCompletedFuture() {
            try {
                Prepared<P> completed = future.get();
                executor.discard(completed.value);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | CancellationException ignored) {
                // No prepared value exists to discard.
            }
        }

        private void require(State expected) {
            if (state != expected) {
                throw new IllegalStateException(
                        "Prepared delivery is " + state
                                + ", expected " + expected);
            }
        }
    }
}
