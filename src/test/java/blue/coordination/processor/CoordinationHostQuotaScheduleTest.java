package blue.coordination.processor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationHostQuotaScheduleTest {
    @Test
    void shouldLoadEverySupportedCounterAndLimitFromTheManifest() {
        // given
        CoordinationHostQuotaSchedule schedule =
                CoordinationHostQuotaSchedule.defaults();

        // when
        String rawManifestIdentity =
                schedule.manifestSha256();

        // then
        assertEquals(
                Arrays.asList(
                        "splitterCatalogEntryVisited",
                        "splitterFragmentAdmitted",
                        "splitterCutValidated",
                        "mandatePredicateEvaluated",
                        "responderMandateCandidateTested",
                        "subscriptionOccurrenceProjected",
                        "indexedCandidateValidated",
                        "prefetchIdentityConstructed",
                        "fragmentEdgeMetadataProduced"),
                schedule.counterNames());
        assertEquals(16384, schedule.maxSplitterCuts());
        assertEquals(
                4096,
                schedule.maxMandateCandidatesPerDecision());
        assertEquals(
                65536,
                schedule.maxSplitterCatalogEntriesPerSplit());
        assertEquals(
                65536,
                schedule.maxSplitterFragmentsPerSplit());
        assertEquals(
                262144,
                schedule.maxFragmentEdgeOccurrencesPerSplit());
        assertEquals(
                65536,
                schedule.maxSubscriptionOccurrencesPerProjection());
        assertEquals(
                65536,
                schedule.maxIndexedCandidatesPerPlan());
        assertEquals(
                65536,
                schedule.maxPrefetchIdentitiesPerPlan());
        assertEquals(
                "48ebee7646e0bdcf75743944e5d5c11aa9055f39e39a5444d5a03db0b6044f74",
                rawManifestIdentity);
    }

    @Test
    void shouldRejectUnknownManifestFields() {
        // given
        String manifest =
                CoordinationHostQuotaTestSupport
                        .manifest(2, 3)
                        .replace(
                                "description: Exact test host quota schedule.",
                                "unknownHeader: true");

        // when
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> load(manifest));

        // then
        assertTrue(
                failure.getMessage().contains(
                        "unknown header unknownHeader"));
    }

    @Test
    void shouldRejectUnsupportedCounters() {
        // given
        String manifest =
                CoordinationHostQuotaTestSupport
                        .manifest(2, 3)
                        .replace(
                                "splitterCutValidated",
                                "unsupportedCounter");

        // when
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> load(manifest));

        // then
        assertTrue(
                failure.getMessage().contains(
                        "counters must be exactly"));
    }

    @Test
    void shouldRejectNonPositiveManifestLimits() {
        // given
        String manifest =
                CoordinationHostQuotaTestSupport
                        .manifest(0, 3);

        // when
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> load(manifest));

        // then
        assertTrue(
                failure.getMessage().contains(
                        "maxSplitterCuts must be positive"));
    }

    private static CoordinationHostQuotaSchedule load(
            String manifest) {
        return CoordinationHostQuotaSchedule.load(
                new ByteArrayInputStream(
                        manifest.getBytes(
                                StandardCharsets.UTF_8)));
    }
}
