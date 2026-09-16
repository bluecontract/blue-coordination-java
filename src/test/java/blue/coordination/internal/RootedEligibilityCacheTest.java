package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.DirectLogicalDelivery;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedScopeKey;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The memo is exact physical reuse, never an asserted BlueId or terminal authority. */
final class RootedEligibilityCacheTest {
    private static final String SHA = "sha256:" + "a".repeat(64);
    private static final DocumentId ROOT = new DocumentId("root");
    private static final String ASSERTED_HEAD = ExactValue.verified(new Node().value("head")).blueId();

    @Test void completeSuccessfulMaskReusesReallocatedExactInputsAndReturnsCurrentDeliveries() {
        // given
        var cache = new RootedEligibilityCache();
        var calls = new AtomicInteger();
        boolean[] originalMask = {false, true};
        var first = List.of(delivery("a", "one", 0), delivery("b", "two", 1));
        var second = List.of(delivery("a", "one", 0), delivery("b", "two", 1));
        // when
        var initial = cache.select(snapshot("same"), first, event("one"), () -> {
            calls.incrementAndGet(); return originalMask;
        });
        originalMask[0] = true;
        var repeated = cache.select(snapshot("same"), second, event("one"), () -> {
            fail("The entire successful call should be reused"); return new boolean[2];
        });
        // then
        assertEquals(1, calls.get());
        assertEquals(List.of(first.get(1)), initial);
        assertEquals(List.of(second.get(1)), repeated);
        assertSame(second.get(1), repeated.get(0));
        assertNotSame(initial.get(0), repeated.get(0));
    }

    @Test void changedExactBodyAtTheSameAssertedEpochAndBlueIdIsAMiss() {
        // given
        var cache = new RootedEligibilityCache();
        var calls = new AtomicInteger();
        var before = snapshot("checkpoint-before");
        var after = snapshot("checkpoint-after");
        var deliveries = List.of(delivery("owner", "one", 0));
        // when
        var oldResult = cache.select(before, deliveries, event("one"), () -> {
            calls.incrementAndGet(); return new boolean[]{true};
        });
        var newResult = cache.select(after, deliveries, event("one"), () -> {
            calls.incrementAndGet(); return new boolean[]{false};
        });
        // then
        assertEquals(before.managedDocument(ROOT).epoch(), after.managedDocument(ROOT).epoch());
        assertEquals(before.managedDocument(ROOT).blueId(), after.managedDocument(ROOT).blueId());
        assertEquals(2, calls.get());
        assertEquals(deliveries, oldResult);
        assertTrue(newResult.isEmpty());
    }

    @Test void completedAllFalseAndEmptyMasksAreReusableResults() {
        // given
        var cache = new RootedEligibilityCache();
        var calls = new AtomicInteger();
        // when
        for (var deliveries : List.of(List.of(delivery("owner", "one", 0)), List.<DirectLogicalDelivery>of())) {
            assertTrue(cache.select(snapshot("same"), deliveries, event("one"), () -> {
                calls.incrementAndGet(); return new boolean[deliveries.size()];
            }).isEmpty());
            assertTrue(cache.select(snapshot("same"), deliveries, event("one"), () -> {
                fail("A completed empty result is not a cache miss"); return new boolean[deliveries.size()];
            }).isEmpty());
        }
        // then
        assertEquals(2, calls.get());
        assertEquals(2, cache.entryCount());
    }

    @Test void exactEventConstructionModeAndIdentityBothParticipate() {
        // given
        var cache = new RootedEligibilityCache();
        var calls = new AtomicInteger();
        var strict = event(ExactValue.fromFrozen(FrozenNode.fromNode(new Node().value("one"))));
        var resolved = event(ExactValue.fromFrozen(FrozenNode.fromResolvedNode(new Node().value("one"))));
        var deliveries = List.of(delivery("owner", "one", 0));
        // when
        for (var input : List.of(strict, resolved, event("two"))) {
            cache.select(snapshot("same"), deliveries, input, () -> {
                calls.incrementAndGet(); return new boolean[]{true};
            });
        }
        // then
        assertEquals(strict.blueId(), resolved.blueId(), "Semantic identity alone cannot key exact wire modes");
        assertNotEquals(strict.exactEvent().frozen().resolvedStructuralKey(),
                resolved.exactEvent().frozen().resolvedStructuralKey());
        assertNotEquals(strict.blueId(), event("two").blueId());
        assertEquals(3, calls.get());
    }

    @Test void channelLogicalKeyOccurrenceOrderAndDeliveryOrderAreExact() {
        // given
        var cache = new RootedEligibilityCache();
        var calls = new AtomicInteger();
        var a = delivery("a", "one", 0);
        var b = delivery("b", "two", 1);
        var variations = List.of(List.of(a, b), List.of(b, a),
                List.of(delivery("other", "one", 0), b),
                List.of(delivery("a", "other", 0), b),
                List.of(delivery("a", "one", 2), b));
        // when
        for (var input : variations) cache.select(snapshot("same"), input, event("one"), () -> {
            calls.incrementAndGet(); return new boolean[]{true, false};
        });
        // then
        assertEquals(variations.size(), calls.get());
        assertEquals(variations.size(), cache.entryCount());
    }

    @Test void failedOrIncompleteCallsNeverPublishAPartialMask() {
        // given
        var cache = new RootedEligibilityCache();
        var visited = new AtomicInteger();
        var deliveries = List.of(delivery("a", "one", 0), delivery("b", "two", 1));
        var unavailable = new IllegalStateException("Exact input temporarily unavailable at second comparison");
        // when
        var failure = assertThrows(IllegalStateException.class, () ->
                cache.select(snapshot("same"), deliveries, event("one"), () -> {
                    visited.incrementAndGet(); throw unavailable;
                }));
        var retried = cache.select(snapshot("same"), deliveries, event("one"), () -> {
            visited.addAndGet(2); return new boolean[]{true, true};
        });
        // then
        assertSame(unavailable, failure);
        assertEquals(3, visited.get(), "Retry must classify the entire call, not just its previously failing suffix");
        assertEquals(deliveries, retried);
        cache.clear();
        assertThrows(IllegalStateException.class, () -> cache.select(snapshot("same"), deliveries, event("one"),
                () -> new boolean[]{true}));
        assertEquals(0, cache.entryCount());
        assertEquals(0L, cache.retainedWeightBytes());
    }

    @Test void leastRecentlyUsedEvictionRespectsTheEntryBound() {
        // given
        var cache = new RootedEligibilityCache(new RootedEligibilityCache.Limits(2, Long.MAX_VALUE, Long.MAX_VALUE));
        var calls = new AtomicInteger();
        // when
        select(cache, "a", calls); select(cache, "b", calls); select(cache, "a", calls);
        select(cache, "c", calls); select(cache, "a", calls); select(cache, "b", calls);
        // then
        assertEquals(4, calls.get());
        assertEquals(2, cache.entryCount());
        assertTrue(cache.retainedWeightBytes() > 0L);
    }

    @Test void byteBoundEvictsAndOversizedEntriesExecuteWithoutEvictingUsefulEntries() {
        // given
        var measured = new RootedEligibilityCache();
        var calls = new AtomicInteger();
        select(measured, "a", calls);
        long one = measured.retainedWeightBytes();
        measured.clear();
        select(measured, "b", calls);
        one = Math.max(one, measured.retainedWeightBytes());
        var cache = new RootedEligibilityCache(new RootedEligibilityCache.Limits(10, one, one));
        calls.set(0);
        // when
        select(cache, "a", calls); select(cache, "b", calls); select(cache, "a", calls);
        select(cache, "x".repeat(20_000), calls); select(cache, "x".repeat(20_000), calls);
        select(cache, "a", calls);
        // then
        assertEquals(5, calls.get());
        assertEquals(1, cache.entryCount());
        assertTrue(cache.retainedWeightBytes() <= one);
        cache.clear();
        assertEquals(0L, cache.retainedWeightBytes());
        select(cache, "a", calls);
        assertEquals(6, calls.get());
    }

    @Test void individualEntryLimitAndDisabledCacheDoNotLimitValidCalculations() {
        // given
        var calls = new AtomicInteger();
        var oversized = new RootedEligibilityCache(new RootedEligibilityCache.Limits(10, Long.MAX_VALUE, 1));
        var disabled = new RootedEligibilityCache(new RootedEligibilityCache.Limits(0, 0, 0));
        // when
        for (var cache : List.of(oversized, disabled)) {
            select(cache, "a", calls); select(cache, "a", calls);
            // then
            assertEquals(0, cache.entryCount());
            assertEquals(0L, cache.retainedWeightBytes());
        }
        assertEquals(4, calls.get());
    }

    @Test void disabledOrUnrepresentableKeysDeferToTheOriginalClassifier() {
        // given
        var enabled = new RootedEligibilityCache();
        var disabled = new RootedEligibilityCache(new RootedEligibilityCache.Limits(0, 0, 0));
        var deliveries = List.of(delivery("owner", "one", 0));
        var originalFailure = new IllegalArgumentException("Original classifier's event validation");
        var calls = new AtomicInteger();
        // when
        for (var cache : List.of(enabled, disabled)) {
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    cache.select(null, deliveries, null, () -> {
                        calls.incrementAndGet(); throw originalFailure;
                    }));
            // then
            assertSame(originalFailure, failure, "Key capture must not replace original validation failures");
            assertEquals(0, cache.entryCount());
        }
        // The original empty-delivery calculation need not open any target.
        assertTrue(disabled.select(null, List.of(), event("one"), () -> {
            calls.incrementAndGet(); return new boolean[0];
        }).isEmpty());
        assertEquals(3, calls.get());
    }

    private static void select(RootedEligibilityCache cache, String event, AtomicInteger calls) {
        var deliveries = List.of(delivery("owner", "one", 0));
        assertEquals(deliveries, cache.select(snapshot("same"), deliveries, event(event), () -> {
            calls.incrementAndGet(); return new boolean[]{true};
        }));
    }

    private static AffectedClosureSnapshot snapshot(String body) {
        // Deliberately shape-valid asserted identities, not PROCESS authority:
        // the cache must inspect exact content even if these assertions coincide.
        var document = new ManagedDocumentSnapshot(ROOT, ASSERTED_HEAD, new Node().value(body), true, false, true, 7, 0);
        return new AffectedClosureSnapshot(SHA, 0, List.of(document), List.of(), SHA,
                List.of(ClosureEvidenceFactory.acyclicComponent(document)), List.of(ROOT));
    }

    private static DirectLogicalDelivery delivery(String channel, String logical, long order) {
        return new DirectLogicalDelivery(ManagedScopeKey.root(ROOT), channel, logical, order);
    }

    private static TimelineEntry event(String value) { return event(ExactValue.verified(new Node().value(value))); }

    private static TimelineEntry event(ExactValue value) {
        var order = ExternalOrderKey.of(List.of(1L));
        return new TimelineEntry(value, Optional.empty(), order, order, new Timeline("timeline", "actor"),
                "operation", "owner", 1, 1, 1);
    }
}
