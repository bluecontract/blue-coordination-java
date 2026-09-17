package blue.coordination.internal;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RootedObservationReuseTest {
    @Test void identicalObservationReusesAndEveryChangedFenceRecomputes() {
        var reuse = new RootedObservationReuse(); var loads = new AtomicInteger(); var hits = new AtomicInteger();
        Object[] fence = {new Object()};
        java.util.function.Supplier<Object> read = () -> reuse.read(fence[0], "root", () -> {
            loads.incrementAndGet(); return new Object();
        }, () -> fence[0], ignored -> true, hits::incrementAndGet);
        Object first = read.get();
        for (int n = 0; n < 100; n++) assertSame(first, read.get());
        assertEquals(1, loads.get()); assertEquals(100, hits.get());
        fence[0] = new Object(); Object changed = read.get();
        assertNotSame(first, changed); assertEquals(2, loads.get());
        reuse.clear(); assertNotSame(changed, read.get()); assertEquals(3, loads.get());
    }

    @Test void unavailableAndFailedReadsNeverBecomeNegativeEvidence() {
        var reuse = new RootedObservationReuse(); var loads = new AtomicInteger();
        Object fence = new Object();
        for (int n = 0; n < 2; n++) reuse.read(fence, "blocked", () -> {
            loads.incrementAndGet(); return new Object();
        }, () -> fence, ignored -> false, () -> fail("Blocked work cannot be reused"));
        assertEquals(2, loads.get());
        assertThrows(IllegalStateException.class, () -> reuse.read(fence, "blocked", () -> {
            throw new IllegalStateException("missing immutable data");
        }, () -> fence, ignored -> true, () -> fail()));
        assertEquals("ready", reuse.read(fence, "blocked", () -> "ready", () -> fence, ignored -> true, () -> fail()));
    }

    @Test void boundedKeysAndCaptureSideEffectsUseTheCompletedFence() {
        var reuse = new RootedObservationReuse(); var strong = new ArrayList<Object>();
        Object before = new Object(), after = new Object(); Object value = new Object();
        assertSame(value, reuse.read(before, "first", () -> value, () -> after, ignored -> true, () -> fail()));
        assertSame(value, reuse.read(after, "first", () -> { fail(); return null; }, () -> after, ignored -> true, () -> {}));
        for (int n = 0; n < 33; n++) {
            Object selected = new Object(); strong.add(selected);
            reuse.read(after, n, () -> selected, () -> after, ignored -> true, () -> fail());
        }
        assertNotSame(value, reuse.read(after, "first", Object::new, () -> after, ignored -> true, () -> fail()));
        assertEquals(33, strong.size());
    }
}
