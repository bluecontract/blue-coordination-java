package blue.coordination.processor;

import blue.coordination.fastpath.DeltaProjectionApplier;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CoordinationDeltaSubscriptionProjectorTest {
    @Test
    void additionIsNotAlsoReportedAsUnchanged() {
        CoordinationSubscriptionOccurrence retained = occurrence(
                "/", "root", 0, 1L);
        CoordinationSubscriptionSnapshot previous = snapshot(retained);
        CoordinationSubscriptionOccurrence added = occurrence(
                "/child", "child", 1, 2L);
        CoordinationCommitProjectionEvidence evidence = evidence(
                new SubscriptionDelta(
                        Collections.singletonList(
                                added.toSubscriptionDeltaEntry()),
                        Collections.<SubscriptionDelta.Entry>emptyList()),
                Collections.singletonList(added),
                true);

        CoordinationSubscriptionUpdate result =
                new CoordinationDeltaSubscriptionProjector().apply(
                        previous, evidence);

        assertEquals(Collections.singletonList(added), result.added());
        assertEquals(Collections.singletonList(retained), result.unchanged());
        assertSame(retained, result.unchanged().get(0));
        assertEquals(2, result.snapshot().occurrences().size());
    }

    @Test
    void incompleteCommitEvidenceFailsWithTypedColdPathSignal() {
        CoordinationSubscriptionOccurrence retained = occurrence(
                "/", "root", 0, 1L);

        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> new CoordinationDeltaSubscriptionProjector().apply(
                        snapshot(retained),
                        evidence(
                                SubscriptionDelta.empty(),
                                Collections.<CoordinationSubscriptionOccurrence>emptyList(),
                                false)));
    }

    private static CoordinationSubscriptionSnapshot snapshot(
            CoordinationSubscriptionOccurrence occurrence) {
        return new CoordinationSubscriptionSnapshot(
                "language-runtime",
                "coordination-runtime",
                "root-1",
                1L,
                order(1L),
                Collections.singletonList(occurrence),
                Collections.<String, java.util.List<String>>emptyMap(),
                Collections.<String>emptySet());
    }

    private static CoordinationCommitProjectionEvidence evidence(
            SubscriptionDelta delta,
            java.util.List<CoordinationSubscriptionOccurrence> current,
            boolean complete) {
        return new CoordinationCommitProjectionEvidence(
                "root-2",
                2L,
                order(2L),
                delta,
                current,
                Collections.<String>emptyList(),
                Collections.<String, java.util.List<String>>emptyMap(),
                Collections.<String>emptySet(),
                null,
                complete);
    }

    private static CoordinationSubscriptionOccurrence occurrence(
            String scope,
            String channel,
            int index,
            long activationRevision) {
        boolean root = "/".equals(scope);
        return new CoordinationSubscriptionOccurrence(
                scope,
                "scope-" + index,
                "/",
                root
                        ? CoordinationSubscriptionOccurrence.Origin.ROOT
                        : CoordinationSubscriptionOccurrence.Origin.EXPLICIT,
                root ? null : scope,
                null,
                null,
                channel,
                Collections.singletonList("source-" + index),
                "type-" + index,
                index,
                "checkpoint-" + index,
                "header-" + index,
                Collections.singletonMap("timeline", "timeline-" + index),
                Collections.singletonList("timeline:" + index),
                Long.valueOf(activationRevision),
                order(activationRevision),
                null,
                ExternalChannelDependencySnapshot.none());
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(Arrays.asList(BigInteger.valueOf(value)));
    }
}
