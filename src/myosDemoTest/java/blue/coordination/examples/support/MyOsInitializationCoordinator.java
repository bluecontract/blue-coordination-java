package blue.coordination.examples.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Concurrent initialize-once with retryable failures and real evidence. */
public final class MyOsInitializationCoordinator<T> {

    public record Evidence(long attempts, long successes, long failures) { }

    public enum TerminalStatus { SUCCEEDED, FAILED }

    /** Exact stable result evidence produced inside the initialization call. */
    public record Completed<T>(T value, String resultRootBlueId) {
        public Completed {
            Objects.requireNonNull(value, "value");
            resultRootBlueId = requireText(
                    resultRootBlueId, "resultRootBlueId");
        }
    }

    /** One terminal receipt for the latest attempt of one logical document. */
    public record Receipt(
            MyOsDocumentIdentity identity,
            String sessionId,
            String inputDocumentBlueId,
            long attempt,
            TerminalStatus status,
            String resultRootBlueId,
            String failureClass) {
        public Receipt {
            Objects.requireNonNull(identity, "identity");
            sessionId = requireText(sessionId, "sessionId");
            inputDocumentBlueId = requireText(
                    inputDocumentBlueId, "inputDocumentBlueId");
            Objects.requireNonNull(status, "status");
            if (attempt <= 0L
                    || !identity.initialDocumentBlueId().equals(
                            inputDocumentBlueId)) {
                throw new IllegalArgumentException(
                        "Initialization receipt input is inconsistent");
            }
            if (status == TerminalStatus.SUCCEEDED) {
                resultRootBlueId = requireText(
                        resultRootBlueId, "resultRootBlueId");
                if (failureClass != null) {
                    throw new IllegalArgumentException(
                            "Successful initialization has a failure class");
                }
            } else {
                failureClass = requireText(failureClass, "failureClass");
                if (resultRootBlueId != null) {
                    throw new IllegalArgumentException(
                            "Failed initialization has a result Root");
                }
            }
        }
    }

    private final Map<MyOsDocumentIdentity, CompletableFuture<T>> successful =
            new ConcurrentHashMap<>();
    private final LongAdder attempts = new LongAdder();
    private final LongAdder successes = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final Map<MyOsDocumentIdentity, Long> attemptsByIdentity =
            new ConcurrentHashMap<>();
    private final Map<MyOsDocumentIdentity, Receipt> terminalReceipts =
            new ConcurrentHashMap<>();

    public T initialize(
            MyOsDocumentIdentity identity,
            String sessionId,
            Supplier<Completed<T>> operation) {
        Objects.requireNonNull(identity, "identity");
        String checkedSessionId = requireText(sessionId, "sessionId");
        Objects.requireNonNull(operation, "operation");
        for (;;) {
            CompletableFuture<T> created = new CompletableFuture<>();
            CompletableFuture<T> selected = successful.putIfAbsent(
                    identity, created);
            if (selected != null) {
                return join(selected);
            }
            attempts.increment();
            long attempt = attemptsByIdentity.merge(
                    identity, 1L, Math::addExact);
            try {
                Completed<T> completed = Objects.requireNonNull(
                        operation.get(), "initialization result");
                T value = completed.value();
                successes.increment();
                terminalReceipts.put(
                        identity,
                        new Receipt(
                                identity,
                                checkedSessionId,
                                identity.initialDocumentBlueId(),
                                attempt,
                                TerminalStatus.SUCCEEDED,
                                completed.resultRootBlueId(),
                                null));
                created.complete(value);
                return value;
            } catch (Throwable failure) {
                failures.increment();
                terminalReceipts.put(
                        identity,
                        new Receipt(
                                identity,
                                checkedSessionId,
                                identity.initialDocumentBlueId(),
                                attempt,
                                TerminalStatus.FAILED,
                                null,
                                failure.getClass().getName()));
                created.completeExceptionally(failure);
                successful.remove(identity, created);
                throw propagate(failure);
            }
        }
    }

    public Evidence evidence() {
        return new Evidence(attempts.sum(), successes.sum(), failures.sum());
    }

    public List<Receipt> terminalReceipts() {
        List<Receipt> result = new ArrayList<>(terminalReceipts.values());
        result.sort((left, right) ->
                left.identity().compareTo(right.identity()));
        return Collections.unmodifiableList(result);
    }

    public Receipt requireTerminalReceipt(MyOsDocumentIdentity identity) {
        Receipt receipt = terminalReceipts.get(
                Objects.requireNonNull(identity, "identity"));
        if (receipt == null) {
            throw new IllegalArgumentException(
                    "No terminal initialization receipt for " + identity);
        }
        return receipt;
    }

    public int initializedCount() {
        return Math.toIntExact(successful.values().stream()
                .filter(CompletableFuture::isDone)
                .filter(value -> !value.isCompletedExceptionally())
                .count());
    }

    public boolean initialized(MyOsDocumentIdentity identity) {
        CompletableFuture<T> result = successful.get(
                Objects.requireNonNull(identity, "identity"));
        return result != null
                && result.isDone()
                && !result.isCompletedExceptionally();
    }

    /**
     * Copies only a quiescent initialize-once registry. Completed immutable
     * values are shared; an in-flight initialization makes capture fail
     * closed instead of manufacturing success evidence.
     */
    public MyOsInitializationCoordinator<T> copyAtQuiescence() {
        MyOsInitializationCoordinator<T> result =
                new MyOsInitializationCoordinator<>();
        for (Map.Entry<MyOsDocumentIdentity, CompletableFuture<T>> entry
                : successful.entrySet()) {
            CompletableFuture<T> future = entry.getValue();
            if (!future.isDone() || future.isCompletedExceptionally()) {
                throw new IllegalStateException(
                        "Cannot checkpoint in-flight initialization for "
                                + entry.getKey());
            }
            result.successful.put(
                    entry.getKey(), CompletableFuture.completedFuture(
                            join(future)));
        }
        Evidence captured = evidence();
        result.attempts.add(captured.attempts());
        result.successes.add(captured.successes());
        result.failures.add(captured.failures());
        result.attemptsByIdentity.putAll(attemptsByIdentity);
        result.terminalReceipts.putAll(terminalReceipts);
        return result;
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            throw propagate(failure.getCause());
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("Initialization failed", failure);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank() || !checked.equals(checked.trim())) {
            throw new IllegalArgumentException(label + " must be exact text");
        }
        return checked;
    }
}
