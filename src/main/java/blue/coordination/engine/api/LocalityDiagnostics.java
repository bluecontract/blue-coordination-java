package blue.coordination.engine.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable nonportable diagnostics for request-local physical reads. */
public final class LocalityDiagnostics {

    private final List<String> requestedBlueIds;
    private final List<String> backendLoadedBlueIds;
    private final int batchCount;
    private final int fallbackReadCount;
    private final long loadedBytes;
    private final List<String> prefetchedButUnusedBlueIds;
    private final List<String> causallySelectedBlueIds;
    private final int forbiddenReadCount;

    public LocalityDiagnostics(
            Collection<String> requestedBlueIds,
            Collection<String> backendLoadedBlueIds,
            int batchCount,
            int fallbackReadCount,
            long loadedBytes,
            Collection<String> prefetchedButUnusedBlueIds,
            Collection<String> causallySelectedBlueIds,
            int forbiddenReadCount) {
        this.requestedBlueIds = immutableText(
                requestedBlueIds, "requestedBlueIds");
        this.backendLoadedBlueIds = immutableText(
                backendLoadedBlueIds, "backendLoadedBlueIds");
        this.prefetchedButUnusedBlueIds = immutableText(
                prefetchedButUnusedBlueIds, "prefetchedButUnusedBlueIds");
        this.causallySelectedBlueIds = immutableText(
                causallySelectedBlueIds, "causallySelectedBlueIds");
        if (batchCount < 0 || fallbackReadCount < 0 || loadedBytes < 0L
                || forbiddenReadCount < 0) {
            throw new IllegalArgumentException(
                    "Locality counters must be non-negative");
        }
        this.batchCount = batchCount;
        this.fallbackReadCount = fallbackReadCount;
        this.loadedBytes = loadedBytes;
        this.forbiddenReadCount = forbiddenReadCount;
    }

    public List<String> requestedBlueIds() { return requestedBlueIds; }
    public List<String> backendLoadedBlueIds() {
        return backendLoadedBlueIds;
    }
    public int batchCount() { return batchCount; }
    public int fallbackReadCount() { return fallbackReadCount; }
    public long loadedBytes() { return loadedBytes; }
    public List<String> prefetchedButUnusedBlueIds() {
        return prefetchedButUnusedBlueIds;
    }
    public List<String> causallySelectedBlueIds() {
        return causallySelectedBlueIds;
    }
    public int forbiddenReadCount() { return forbiddenReadCount; }

    public static LocalityDiagnostics empty() {
        return new LocalityDiagnostics(
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                0,
                0L,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0);
    }

    private static List<String> immutableText(
            Collection<String> source,
            String label) {
        List<String> result = new ArrayList<String>(
                Objects.requireNonNull(source, label));
        for (String value : result) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(
                        label + " entries must be non-empty");
            }
        }
        return Collections.unmodifiableList(result);
    }
}
