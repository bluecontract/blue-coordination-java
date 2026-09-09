package blue.coordination.internal;

import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.SubscriptionDelta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact interval-evidence tests for dynamic external subscriptions. */
final class DocumentTransitionProcessorSubscriptionDeltaTest {
    private static final ExternalOrderKey ADMISSION =
            ExternalOrderKey.of(List.of(0L, "admission"));
    private static final ExternalOrderKey TRANSITION =
            ExternalOrderKey.of(List.of(1L, "transition"));

    @Test
    void appliesAdditionAndRemovalAsOneDeterministicGeneration() {
        // given

        SubscriptionDelta.Entry alice = active(
                "aliceChannel", "alice-key", "alice-domain", 0L, ADMISSION);
        SubscriptionDelta.Entry bob = active(
                "bobChannel", "bob-key", "bob-domain", 1L, TRANSITION);
        EngineMetrics metrics = new EngineMetrics();

        // when
        List<SubscriptionDelta.Entry> afterAddition = apply(
                List.of(alice),
                new SubscriptionDelta(List.of(bob), List.of()),
                metrics);

        // then
        assertEquals(List.of(alice, bob), afterAddition);
        assertEquals(1L, metrics.counter(
                "process.dynamicSubscriptionsAdded"));

        List<SubscriptionDelta.Entry> afterRemoval = apply(
                afterAddition,
                new SubscriptionDelta(
                        List.of(), List.of(retired(bob, 1L))),
                metrics);
        assertEquals(List.of(alice), afterRemoval);
        assertEquals(1L, metrics.counter(
                "process.dynamicSubscriptionsRemoved"));
        assertEquals(2L, metrics.counter(
                "process.commitCompanionDeltasApplied"));
    }

    @Test
    void replacesChangedMembershipAtTheSameOccurrence() {
        // given

        SubscriptionDelta.Entry before = active(
                "ownerChannel", "old-key", "old-domain", 0L, ADMISSION);
        SubscriptionDelta.Entry after = active(
                "ownerChannel", "new-key", "new-domain", 1L, TRANSITION);
        EngineMetrics metrics = new EngineMetrics();

        // when
        List<SubscriptionDelta.Entry> result = apply(
                List.of(before),
                new SubscriptionDelta(
                        List.of(after), List.of(retired(before, 1L))),
                metrics);

        // then
        assertEquals(List.of(after), result);
        assertEquals(1L, metrics.counter(
                "process.dynamicSubscriptionsAdded"));
        assertEquals(1L, metrics.counter(
                "process.dynamicSubscriptionsRemoved"));
        assertEquals(1L, metrics.counter(
                "process.dynamicSubscriptionsReplaced"));
    }

    @Test
    void retainsVerifiedIntervalForConservativeHeaderOnlyReplacement() {
        // given

        SubscriptionDelta.Entry established = active(
                "ownerChannel", "stable-key", "verified-domain", 0L,
                ADMISSION);
        SubscriptionDelta.Entry invocationLocal = active(
                "ownerChannel", "stable-key", "invocation-domain", 1L,
                TRANSITION);
        EngineMetrics metrics = new EngineMetrics();

        // when
        List<SubscriptionDelta.Entry> result = apply(
                List.of(established),
                new SubscriptionDelta(
                        List.of(invocationLocal),
                        List.of(retired(established, 1L))),
                metrics);

        // then
        assertEquals(1, result.size());
        assertSame(established, result.get(0));
        assertEquals(1L, metrics.counter(
                "process.companionReplacementsRetained"));
        assertEquals(0L, metrics.counter(
                "process.dynamicSubscriptionsAdded"));
        assertEquals(0L, metrics.counter(
                "process.dynamicSubscriptionsRemoved"));
    }

    @Test
    void rejectsSameOccurrenceSemanticReplacementMissingFromProjection() {
        // given

        SubscriptionDelta.Entry before = active(
                "ownerChannel", "old-key", "old-domain", 0L, ADMISSION);
        SubscriptionDelta.Entry after = active(
                "ownerChannel", "new-key", "new-domain", 1L, TRANSITION);

        // when
        SubscriptionDelta companion = new SubscriptionDelta(
                List.of(after), List.of(retired(before, 1L)));

        // then
        assertThrows(InvalidExecutionEvidenceException.class, () ->
                DocumentTransitionProcessor.requireSameOwnedTransition(
                        companion,
                        new SubscriptionDelta(List.of(), List.of()),
                        List.of(),
                        List.of()));
    }

    @Test
    void rejectsUnknownOrForgedIntervalEvidenceWithoutPublishingMetrics() {
        // given
        SubscriptionDelta.Entry established = active(
                "ownerChannel", "stable-key", "verified-domain", 0L,
                ADMISSION);

        // when
        SubscriptionDelta retiredOnly = new SubscriptionDelta(
                List.of(), List.of(retired(established, 1L)));

        // then
        assertInvalid(List.of(), retiredOnly);
        assertInvalid(List.of(established), new SubscriptionDelta(
                List.of(),
                List.of(retired(active(
                        "ownerChannel", "forged-key", "verified-domain",
                        0L, ADMISSION), 1L))));
        assertInvalid(List.of(established), new SubscriptionDelta(
                List.of(active(
                        "newChannel", "new-key", "new-domain", 2L,
                        TRANSITION)),
                List.of()));
        assertInvalid(List.of(established), new SubscriptionDelta(
                List.of(active(
                        "ownerChannel", "stable-key", "verified-domain",
                        1L, TRANSITION)),
                List.of()));
    }

    @Test
    void unchangedRouteProofRejectsMissingExtraAndAlteredHeaders() {
        var id = blue.coordination.api.DocumentId.of("unchanged-route");
        var established = active("ownerChannel", "stable-key", "verified-domain", 0L, ADMISSION);
        ContractsClosureAdapter.requireUnchangedRouteSurface(id, List.of(established), List.of(established));
        for (var desired : List.of(List.<SubscriptionDelta.Entry>of(),
                List.of(established, active("extra", "extra-key", "extra-domain", 0L, ADMISSION)),
                List.of(active("ownerChannel", "changed-key", "verified-domain", 0L, ADMISSION)),
                List.of(active("ownerChannel", "stable-key", "changed-domain", 0L, ADMISSION)))) {
            assertThrows(ContractsClosureAdapter.ProjectionUnavailableException.class, () ->
                    ContractsClosureAdapter.requireUnchangedRouteSurface(id, List.of(established), desired));
        }
    }

    @Test
    void actualManagedRouteTransitionsStillRequirePositiveRevision() {
        var established = active("ownerChannel", "stable-key", "verified-domain", 0L, ADMISSION);
        var metrics = new EngineMetrics();
        for (long invalidRevision : List.of(-1L, 0L)) {
            assertThrows(IllegalArgumentException.class, () -> DocumentTransitionProcessor.applyManagedRootSubscriptionDelta(
                    List.of(established), new SubscriptionDelta(List.of(), List.of()), invalidRevision, TRANSITION, metrics));
        }
        assertEquals(0L, metrics.counter("process.commitCompanionDeltasApplied"));
    }

    private static List<SubscriptionDelta.Entry> apply(
            List<SubscriptionDelta.Entry> previous,
            SubscriptionDelta delta,
            EngineMetrics metrics) {
        return DocumentTransitionProcessor.applyActiveSubscriptionDelta(
                previous,
                delta,
                List.of(),
                List.of(),
                1L,
                TRANSITION,
                metrics);
    }

    private static void assertInvalid(
            List<SubscriptionDelta.Entry> previous,
            SubscriptionDelta delta) {
        EngineMetrics metrics = new EngineMetrics();
        assertThrows(InvalidExecutionEvidenceException.class,
                () -> apply(previous, delta, metrics));
        assertEquals(0L, metrics.counter(
                "process.commitCompanionDeltasApplied"));
    }

    private static SubscriptionDelta.Entry active(
            String channel,
            String subscriptionKey,
            String checkpointDomain,
            long activationRevision,
            ExternalOrderKey startAfter) {
        return new SubscriptionDelta.Entry(
                "/",
                channel,
                "timeline-channel-type",
                List.of("source-" + channel),
                0,
                List.of(subscriptionKey),
                checkpointDomain,
                ExternalChannelDependencySnapshot.none(),
                activationRevision,
                startAfter,
                null);
    }

    private static SubscriptionDelta.Entry retired(
            SubscriptionDelta.Entry entry,
            long retirementRevision) {
        return new SubscriptionDelta.Entry(
                entry.scopePath(),
                entry.channelKey(),
                entry.effectiveTypeBlueId(),
                entry.sourceContributionNodeBlueIds(),
                entry.order(),
                entry.subscriptionKeys(),
                entry.checkpointDomainBlueId(),
                entry.dependencies(),
                entry.activationRootRevision(),
                entry.startAfterExternalOrderKey(),
                retirementRevision);
    }
}
