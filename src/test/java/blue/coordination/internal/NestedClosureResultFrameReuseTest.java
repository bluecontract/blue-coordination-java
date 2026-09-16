package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.sdk.ActivationPolicy;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureProcessResultStorageCodec;
import blue.language.processor.closure.ManagedRepresentationCause;
import blue.language.processor.closure.ManagedRepresentationTransition;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static org.junit.jupiter.api.Assertions.*;

/** Physical work/cause reuse plus a separate SDK-selected historical work control. */
final class NestedClosureResultFrameReuseTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final int DEPTH = 256;

    @Test void differentCausesShareTheirNestedResultWithoutInterningTheOuterCause() throws Exception {
        var pair = pair(); var raw = new ClosureExecutionEvidenceStorageCodec(MAX, DEPTH);
        byte[] a = raw.encodeProcessingCause(pair.first()), b = raw.encodeProcessingCause(pair.second());
        assertFalse(Arrays.equals(a, b));
        var decodes = new AtomicInteger();
        try (var cache = cache(8)) {
            var results = new StoredClosureResultCodec(MAX, DEPTH, cache, decodes::incrementAndGet, null);
            var codec = new ClosureExecutionEvidenceStorageCodec(MAX, DEPTH, results.configured());
            var first = (ManagedRepresentationCause) codec.decodeProcessingCause(a);
            var second = (ManagedRepresentationCause) codec.decodeProcessingCause(b);
            assertNotSame(first, second); assertNotEquals(first.causeIdentity(), second.causeIdentity());
            assertSame(first.transition().originalResult(), second.transition().originalResult());
            assertEquals(1, decodes.get(), "One actual complete-result cold loader across different outer bytes");
            assertArrayEquals(a, codec.encodeProcessingCause(first)); assertArrayEquals(b, raw.encodeProcessingCause(second));
            byte[] complete = new ClosureProcessResultStorageCodec(MAX, DEPTH).encode(pair.result());
            assertSame(first.transition().originalResult(), results.decode(complete));
            assertEquals(1, decodes.get(), "Direct and nested paths use the same host-weighted entry");
        }
    }

    @Test void actualWorkReadersReuseInnerResultButStillValidateTheirOwnCoordinates() throws Exception {
        var pair = pair(); var raw = new ManagedWorkStorageCodec(MAX, DEPTH);
        var a = work(pair.first());
        var b = work(pair.second());
        byte[] bytesA = raw.encode(a), bytesB = raw.encode(b); assertFalse(Arrays.equals(bytesA, bytesB));
        try (var cache = cache(8)) {
            var codec = new ManagedWorkStorageCodec(MAX, DEPTH, cache);
            var first = codec.decode(bytesA); var second = codec.decode(bytesB);
            assertNotSame(first, second); assertNotEquals(first.workIdentity(), second.workIdentity());
            assertSame(first.representationCause().orElseThrow().transition().originalResult(),
                    second.representationCause().orElseThrow().transition().originalResult());
            assertEquals(1, cache.statistics().loads(), "There is no outer work/cause cache in this fixture");
            assertArrayEquals(bytesA, raw.encode(first)); assertArrayEquals(bytesB, codec.encode(second));
            byte[] wrongIdentity = bytesA.clone();
            // The first workIdentity is UTF-16 text in the ordinary work envelope. Change one ASCII hex digit.
            byte[] identity = a.workIdentity().getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
            int position = indexOf(wrongIdentity, identity); assertTrue(position >= 0);
            wrongIdentity[position + identity.length - 1] ^= 1;
            assertThrows(RuntimeException.class, () -> codec.decode(wrongIdentity));
            assertEquals(1, cache.statistics().loads(), "Warm inner evidence does not excuse invalid outer coordinates");
        }
    }

    @Test void warmNestedResultDoesNotRelaxOuterCorruptionOrCodecProfiles() throws Exception {
        var pair = pair(); byte[] frame = new ClosureExecutionEvidenceStorageCodec(MAX, DEPTH).encodeProcessingCause(pair.first());
        try (var cache = cache(8)) {
            var normal = new ClosureExecutionEvidenceStorageCodec(MAX, DEPTH,
                    new StoredClosureResultCodec(MAX, DEPTH, cache).configured());
            var first = (ManagedRepresentationCause) normal.decodeProcessingCause(frame);
            byte[] corrupt = frame.clone(); corrupt[corrupt.length / 2] ^= 1;
            assertThrows(RuntimeException.class, () -> normal.decodeProcessingCause(corrupt));
            var narrower = new ClosureExecutionEvidenceStorageCodec(MAX, DEPTH - 1,
                    new StoredClosureResultCodec(MAX, DEPTH - 1, cache).configured());
            var second = (ManagedRepresentationCause) narrower.decodeProcessingCause(frame);
            assertNotSame(first.transition().originalResult(), second.transition().originalResult());
            assertEquals(2, cache.statistics().loads());
            assertThrows(IllegalArgumentException.class, () -> new ClosureExecutionEvidenceStorageCodec(MAX, DEPTH,
                    new StoredClosureResultCodec(MAX, DEPTH - 1, cache).configured()));
        }
    }

    @Test void parallelDifferentCausesShareOneCompleteResultColdLoad() throws Exception {
        var pair = pair(); var raw = new ClosureExecutionEvidenceStorageCodec(MAX, DEPTH);
        byte[] a = raw.encodeProcessingCause(pair.first()), b = raw.encodeProcessingCause(pair.second());
        var pool = Executors.newFixedThreadPool(2); var decodes = new AtomicInteger();
        try (var cache = cache(8)) {
            var codec = new ClosureExecutionEvidenceStorageCodec(MAX, DEPTH,
                    new StoredClosureResultCodec(MAX, DEPTH, cache, decodes::incrementAndGet, null).configured());
            var one = pool.submit(() -> (ManagedRepresentationCause) codec.decodeProcessingCause(a));
            var two = pool.submit(() -> (ManagedRepresentationCause) codec.decodeProcessingCause(b));
            assertSame(one.get(30, TimeUnit.SECONDS).transition().originalResult(),
                    two.get(30, TimeUnit.SECONDS).transition().originalResult());
            assertEquals(1, decodes.get());
        } finally { pool.shutdownNow(); }
    }

    @Test void opaqueFrameOwnBytesAreWeightedAndClearDropsItsEncodingIdentity() throws Exception {
        var pair = pair(); byte[] frame = new ClosureProcessResultStorageCodec(MAX, DEPTH).encode(pair.result());
        long required = RootedStorageCache.estimatedWeight(frame.length, frame.length);
        try (var cache = new RootedStorageCache(required, 8, required)) {
            var encodes = new AtomicInteger();
            var codec = new StoredClosureResultCodec(MAX, DEPTH, cache, null, encodes::incrementAndGet);
            var result = codec.decode(frame); assertEquals(required, cache.statistics().retainedWeightBytes());
            codec.encode(result); assertEquals(0, encodes.get());
            cache.clear(); assertEquals(0, cache.statistics().retainedWeightBytes());
            codec.encode(result); assertEquals(1, encodes.get(), "No reverse identity survives clear");
        }
        try (var cache = new RootedStorageCache(required - 1, 8, required - 1)) {
            var codec = new StoredClosureResultCodec(MAX, DEPTH, cache);
            assertNotSame(codec.decode(frame), codec.decode(frame));
            assertEquals(0, cache.statistics().retainedEntries());
        }
    }

    @Test void actualSdkSelectedPendingRepresentationWorkSharesDirectResultEvidence() throws Exception {
        var observed = new AtomicInteger();
        try (var graph = new HistoricalRepresentationApplicationIntegrationTest.Graph("A", "B"); var cache = cache(64)) {
            graph.representationObserver = work -> {
                if (observed.getAndIncrement() != 0) return;
                var raw = new ManagedWorkStorageCodec(MAX, DEPTH);
                var shared = new ManagedWorkStorageCodec(MAX, DEPTH, cache);
                byte[] bytes = raw.encode(work);
                var restored = shared.decode(bytes);
                long loads = cache.statistics().loads();
                assertTrue(loads > 0, "Real pending work contains complete nested result evidence");
                var again = shared.decode(bytes);
                assertEquals(loads, cache.statistics().loads());
                var sourceResult = restored.representationCause().orElseThrow().transition().originalResult();
                assertSame(sourceResult, again.representationCause().orElseThrow().transition().originalResult());
                byte[] complete = new ClosureProcessResultStorageCodec(MAX, DEPTH).encode(
                        work.representationCause().orElseThrow().transition().originalResult());
                assertSame(sourceResult, new StoredClosureResultCodec(MAX, DEPTH, cache).decode(complete));
                assertEquals(work.workIdentity(), restored.workIdentity());
                assertArrayEquals(bytes, shared.encode(restored));
                assertArrayEquals(bytes, raw.encode(restored));
            };
            graph.attach("A", "b", "B"); graph.attach("B", "a", "A");
            graph.operation("B", "emit", "{to: A, next: stop}");
            graph.operation("A", "detach", "{edge: b}");
            graph.operation("B", "emit", "{to: A, next: stop}");
            graph.attach("A", "b", "B");
            assertTrue(observed.get() > 0, "SDK selected a real retained historical representation work item");
            assertEquals(3L, graph.observed("A"));
        }
    }

    private record Pair(ManagedRepresentationCause first, ManagedRepresentationCause second, ClosureProcessResult result) { }
    private static Pair pair() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml") + """
                      emitUnmatched:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request: {}
                        steps:
                        - type: Coordination/Compute
                          do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: RCP2/Unmatched
                          - $return: true
                    """, "rcp2/source", ActivationPolicy.fromNow());
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.fromNow());
            var anchor = f.engine.documents().managedEpochEvidence(parent.id(), 0).transitionReceipt();
            var entry = f.append(source, "rcp2/source", "emitUnmatched");
            var captured = f.engine.contractsClosureAdapter().captureRoot(parent.id(),
                    f.engine.auditTimelineEntry(entry.blueId()).orElseThrow()).invocations().get(0);
            var processor = new BlueClosureContracts(f.engine.runtime().documentProcessor());
            var attempt = captured.retryInput() == null ? processor.processClosure(captured.input())
                    : processor.processClosureRetry(captured.retryInput());
            assertTrue(attempt.isComplete()); var result = attempt.processResult(); assertTrue(result.commits());
            var receipt = result.managedTransitionReceipts().stream()
                    .filter(row -> row.documentId().value().equals(parent.id().value())).findFirst().orElseThrow();
            var transition = new ManagedRepresentationTransition(receipt.documentId(), 0, anchor.transitionReceiptIdentity(),
                    anchor.transitionReceiptIdentity(), captured.input(), result, receipt.transitionReceiptIdentity());
            return new Pair(new ManagedRepresentationCause("sha256:" + "b".repeat(64), transition, transition.positionIdentity(), null, null),
                    new ManagedRepresentationCause("sha256:" + "e".repeat(64), transition, transition.positionIdentity(), null, null), result);
        }
    }
    private static ManagedEpochApplicationWork work(ManagedRepresentationCause cause) {
        var t = cause.transition();
        var coordinates = ManagedEpochApplicationWork.identified("sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64),
                t.anchorReceiptIdentity(), DocumentId.of(t.documentId().value()), t.epoch(), DocumentId.of("independent-consumer"),
                cause.targetOccurrenceIdentity(), "/child", 1, 0, t.originalResult().resultingDocuments().get(0).afterBlueId(), 1);
        return ManagedEpochApplicationWork.identifiedRepresentation(coordinates, cause);
    }
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
    private static RootedStorageCache cache(int entries) { return new RootedStorageCache(512L * 1024 * 1024, entries, 256L * 1024 * 1024); }
}
