package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Release gate: cache warmth cannot alter committed semantics or gas. */
final class CacheSemanticParityIntegrationTest {
    private static final long TIMESTAMP = 1_720_000_000_000_000L;

    @Test
    void coldAndWarmRetryCommitIdenticalRevisionsEventsCausalityAndGas()
            throws Exception {
        try (TestEngine cold = TestEngine.create();
             TestEngine warm = TestEngine.create()) {
            // given
            String source = resource("examples/clean/counter.yaml");
            Timeline coldTimeline = cold.timeline(
                    "examples/clean-counter/alice", "alice");
            Timeline warmTimeline = warm.timeline(
                    "examples/clean-counter/alice", "alice");
            cold.start("counter", source);
            warm.start("counter", source);
            TimelineEntry coldEntry = cold.appendAt(
                    coldTimeline, increment(), TIMESTAMP);
            TimelineEntry warmEntry = warm.appendAt(
                    warmTimeline, increment(), TIMESTAMP);
            assertEquals(coldEntry.blueId(), warmEntry.blueId());

            EngineMetrics.MetricsSnapshot beforeWarmup =
                    warm.metricsSnapshot();
            warm.failOnceAt(TestEngine.FailurePoint
                    .AFTER_FROZEN_BEFORE_STAGE);
            assertThrows(TestEngine.InjectedFailureException.class,
                    () -> warm.dispatch(warmEntry));
            assertEquals(1L, delta(beforeWarmup, warm.metricsSnapshot())
                    .counter("frozenProcessCalls"),
                    "the failed attempt must finish one cache-warming PROCESS");
            assertEquals(1, warm.history("counter").size(),
                    "the cache-warming attempt must not commit a revision");
            assertTrue(warm.languageCacheStats().entries() > 0,
                    "the retry must run against a populated runtime cache");
            warm.clearFailureInjection();

            // when
            cold.dispatch(coldEntry);
            warm.dispatch(warmEntry);

            List<RevisionEvidence> coldTrace = evidence(
                    cold.history("counter"));
            List<RevisionEvidence> warmTrace = evidence(
                    warm.history("counter"));

            // then
            assertEquals(coldTrace, warmTrace);
            assertEquals(cold.session("counter").current().blueId(),
                    warm.session("counter").current().blueId());
            assertTrue(coldTrace.stream()
                    .allMatch(revision -> revision.processingGas() > 0L));
            assertEquals(1, coldTrace.get(1).eventBlueIds().size());
            assertEquals(coldEntry.blueId(),
                    coldTrace.get(1).causalEntryBlueId());
        }
    }

    private static Operation increment() {
        return Operation.yaml(
                "increment", "aliceChannel", "amount: 3");
    }

    private static List<RevisionEvidence> evidence(
            List<DocumentRevision> revisions) {
        return revisions.stream().map(revision -> new RevisionEvidence(
                revision.documentId().value(),
                revision.epoch(),
                revision.rootApplicationOrder(),
                revision.kind(),
                revision.before().map(value -> value.blueId()).orElse(null),
                revision.after().blueId(),
                revision.sourceEntry().map(TimelineEntry::blueId)
                        .orElse(null),
                revision.sourceOrderKey().orElse(null),
                revision.causalEntryBlueId().orElse(null),
                revision.catchUpCause().orElse(null),
                revision.emittedEvents().stream()
                        .map(DirectBlueIdCalculator::calculateBlueId)
                        .toList(),
                revision.processingGas())).toList();
    }

    private record RevisionEvidence(
            String documentId,
            long epoch,
            long rootApplicationOrder,
            DocumentRevision.Kind kind,
            String beforeBlueId,
            String afterBlueId,
            String sourceEntryBlueId,
            ExternalOrderKey sourceOrder,
            String causalEntryBlueId,
            DocumentRevision.CatchUpCause catchUpCause,
            List<String> eventBlueIds,
            long processingGas) {
    }
}
