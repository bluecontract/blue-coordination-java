package blue.coordination.engine.fastpath;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WarmProcessKernelTest {
    @Test
    void invokesEverySemanticPhaseExactlyOnce() {
        FastPathMetrics metrics = new FastPathMetrics();
        WarmProcessKernel<String, String, String, String, String> kernel =
                new WarmProcessKernel<>(metrics);
        AtomicInteger processCalls = new AtomicInteger();

        String committed = kernel.execute("plan", new Steps(processCalls));

        assertEquals("committed", committed);
        assertEquals(1, processCalls.get(), "PROCESS must never be replayed");
        FastPathMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(1L, snapshot.calls(FastPathMetrics.Phase.BUNDLE_BIND));
        assertEquals(1L, snapshot.calls(
                FastPathMetrics.Phase.CONTRACTS_PROCESS));
        assertEquals(1L, snapshot.calls(
                FastPathMetrics.Phase.RETAINED_RESOLUTION));
        assertEquals(1L, snapshot.calls(FastPathMetrics.Phase.PROJECTION));
        assertEquals(1L, snapshot.calls(FastPathMetrics.Phase.TRANSITION));
        assertEquals(1L, snapshot.calls(FastPathMetrics.Phase.COMMIT));
    }

    private static final class Steps implements WarmProcessKernel.Steps<
            String, String, String, String, String> {
        private final AtomicInteger processCalls;

        private Steps(AtomicInteger processCalls) {
            this.processCalls = processCalls;
        }

        @Override
        public PreparedProcessInput bind(String plan) {
            return new PreparedProcessInput("root-inventory", "event-inventory",
                    Collections.<String, ExactNodeHandle>emptyMap(),
                    Collections.<String>emptySet(),
                    0L);
        }

        @Override
        public String process(String plan, PreparedProcessInput input) {
            processCalls.incrementAndGet();
            return "output";
        }

        @Override
        public String resolveRetained(String plan, String output) {
            return output;
        }

        @Override
        public String project(String plan, String output) {
            return "subscriptions";
        }

        @Override
        public String transition(
                String plan, String output, String subscriptions) {
            return "transition";
        }

        @Override
        public String commit(
                String plan,
                String output,
                String subscriptions,
                String transition) {
            return "committed";
        }
    }
}
