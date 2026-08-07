package blue.coordination.engine.api;

import blue.coordination.engine.internal.CoordinationProcessingViews;
import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationEventAdmissionCompilerTest {

    @Test
    void canonicalSplitVerifiesAClaimedIdentityWithoutAPreSplitRehash() {
        CoordinationEventAdmissionMetrics metrics =
                new CoordinationEventAdmissionMetrics();
        CoordinationEventAdmissionCompiler compiler = compiler(metrics);
        Node event = event(1L);
        String blueId = DirectBlueIdCalculator.calculateBlueId(event);

        CoordinationVerifiedEventAdmission first =
                compiler.compile(blueId, event);

        CoordinationEventAdmissionMetrics.Snapshot firstWork =
                metrics.snapshot();
        assertEquals(1L, firstWork.fullEventSplits());
        assertEquals(0L, firstWork.blueIdCalculations(),
                "the canonical split already verifies a first-seen claim");
        assertTrue(first.processingViews().isEmpty(),
                "event splits use only canonical fragment views");

        CoordinationVerifiedEventAdmission second =
                compiler.compile(blueId, event);

        assertSame(first, second);
        assertEquals(1L, metrics.snapshot().fullEventSplits());
        assertEquals(1L, metrics.snapshot().blueIdCalculations(),
                "a cache hit must still bind untrusted exact input");
    }

    @Test
    void failedClaimIsNotCached() {
        CoordinationEventAdmissionMetrics metrics =
                new CoordinationEventAdmissionMetrics();
        CoordinationEventAdmissionCompiler compiler = compiler(metrics);

        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile("wrong-event-blue-id", event(2L)));

        assertEquals(0, compiler.cachedEventCount());
        assertEquals(1L, metrics.snapshot().fullEventSplits());
    }

    @Test
    void fragmentBodiesRemainDefensiveWhileIdentityEnumerationIsBodyFree() {
        CoordinationDocumentSplitter.SplitGraph graph =
                CoordinationDocumentSplitter.forEventSplitting()
                        .splitEvent(event(3L));
        assertTrue(CoordinationProcessingViews.collect(graph).isEmpty(),
                "the portable scan confirms event providers have no views");
        String fragmentBlueId = graph.fragmentBlueIds().get(0);
        Node first = graph.fragment(fragmentBlueId);
        String wireIdentity = DirectBlueIdCalculator.calculateBlueId(first);

        first.value("mutated");

        Node second = graph.fragment(fragmentBlueId);
        assertEquals(fragmentBlueId,
                DirectBlueIdCalculator.calculateBlueId(second));
        assertEquals(fragmentBlueId, wireIdentity);
    }

    @Test
    void shouldReturnButNotRetainAnEventArtifactOverTheByteBound() {
        CoordinationEventAdmissionMetrics metrics =
                new CoordinationEventAdmissionMetrics();
        CoordinationEventAdmissionCompiler compiler =
                new CoordinationEventAdmissionCompiler(
                        "test-environment",
                        "test-language-generation",
                        "test-provider-generation",
                        CoordinationDocumentSplitter.forEventSplitting(),
                        8,
                        1L,
                        64,
                        1L,
                        metrics);
        Node event = event(4L);

        compiler.compile(event);
        compiler.compile(event);

        assertEquals(0, compiler.cachedEventCount());
        assertEquals(0L, compiler.eventCacheMetrics().retainedWeight());
        assertEquals(2L, metrics.snapshot().fullEventSplits());
        assertEquals(2L, compiler.eventCacheMetrics().evictions());
    }

    @Test
    void independentFirstSeenEventsReuseOnlyVerifiedStaticDescendants() {
        String environment =
                "shared-fragment-evidence-regression-environment";
        CoordinationEventAdmissionMetrics firstMetrics =
                new CoordinationEventAdmissionMetrics();
        CoordinationEventAdmissionCompiler first = compiler(
                firstMetrics, environment);
        Node firstEvent = eventWithSharedMessage(41L);
        first.compile(firstEvent);

        CoordinationEventAdmissionMetrics secondMetrics =
                new CoordinationEventAdmissionMetrics();
        CoordinationEventAdmissionCompiler second = compiler(
                secondMetrics, environment);
        Node secondEvent = eventWithSharedMessage(42L);
        CoordinationDocumentSplitter.SplitGraph secondGraph =
                CoordinationDocumentSplitter.forEventSplitting()
                        .splitEvent(secondEvent);
        assertTrue(secondGraph.fragmentBlueIds().size() > 1,
                "the fixture must contain an independent static fragment");

        second.compile(secondEvent);

        CoordinationEventAdmissionMetrics.Snapshot work =
                secondMetrics.snapshot();
        assertTrue(work.fragmentEvidenceHits() > 0L,
                "static descendants should reuse JVM-shared exact evidence");
        assertTrue(work.fragmentEvidenceMisses() > 0L,
                "the exact first-seen event Root must remain unshared");
        assertTrue(work.wireFingerprints()
                        < secondGraph.fragmentBlueIds().size(),
                "reused descendants must not be wire-fingerprinted again");
        assertEquals(1L, work.fullEventSplits(),
                "subtree reuse must not disguise exact-event priming");
    }

    private static CoordinationEventAdmissionCompiler compiler(
            CoordinationEventAdmissionMetrics metrics) {
        return compiler(metrics, "test-environment");
    }

    private static CoordinationEventAdmissionCompiler compiler(
            CoordinationEventAdmissionMetrics metrics,
            String environment) {
        return new CoordinationEventAdmissionCompiler(
                environment,
                "test-language-generation",
                "test-provider-generation",
                CoordinationDocumentSplitter.forEventSplitting(),
                8,
                64,
                metrics);
    }

    private static Node event(long sequence) {
        Map<String, Node> nested = new LinkedHashMap<String, Node>();
        nested.put("stable", new Node().value("value"));
        nested.put("sequence", new Node().value(sequence));
        Map<String, Node> root = new LinkedHashMap<String, Node>();
        root.put("type", new Node().value("event"));
        root.put("request", new Node().properties(nested));
        return new Node().properties(root);
    }

    private static Node eventWithSharedMessage(long sequence) {
        Node message = new Node().properties(
                "operation", new Node().value("attachPayNote"),
                "channel", new Node().value("customerChannel"),
                "request", new Node().properties(
                        "documentRef",
                        new Node().value("stable-paynote")));
        return new Node().properties(
                "timeline", new Node().value(sequence),
                "actor", new Node().value("alice"),
                "message", message);
    }
}
