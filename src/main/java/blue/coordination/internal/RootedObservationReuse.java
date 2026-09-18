package blue.coordination.internal;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Non-owning reuse within an exact runtime observation, never a reservation or durable authority. */
final class RootedObservationReuse {
    private static final int MAXIMUM_KEYS = 32;
    private Object fence;
    // Weak values cannot retain another graph/history outside the host cache budget.
    private final LinkedHashMap<Object, WeakReference<Object>> values = new LinkedHashMap<>(16, .75f, true);

    synchronized <T> T read(Object expectedFence, Object key, Supplier<T> compute,
            Supplier<Object> resultingFence, java.util.function.Predicate<T> reusable, Runnable hit) {
        resetIfChanged(expectedFence);
        var previous = values.get(key);
        Object known = previous == null ? null : previous.get();
        if (known != null) {
            hit.run();
            @SuppressWarnings("unchecked") T selected = (T) known;
            return selected;
        }
        T result = Objects.requireNonNull(compute.get());
        // Capture may retain exact provider bodies. Fence its completed observation.
        resetIfChanged(resultingFence.get());
        if (reusable.test(result)) {
            while (values.size() >= MAXIMUM_KEYS) values.remove(values.keySet().iterator().next());
            values.put(key, new WeakReference<>(result));
        }
        return result;
    }

    private void resetIfChanged(Object selected) {
        if (!Objects.equals(fence, selected)) { values.clear(); fence = selected; }
    }

    synchronized void clear() { values.clear(); fence = null; }
}
