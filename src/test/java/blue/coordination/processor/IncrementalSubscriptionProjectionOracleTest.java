package blue.coordination.processor;

import blue.coordination.fastpath.DeltaProjectionApplier;
import blue.coordination.fastpath.FastPathWorkMetrics;
import blue.coordination.round4.Round4ParityReceipt;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Complete-snapshot oracle for commit-local subscription delta publication. */
final class IncrementalSubscriptionProjectionOracleTest {

    @Test
    void shouldMatchOneThousandCompleteProjectionOracles() {
        // given
        CoordinationDeltaSubscriptionProjector projector =
                new CoordinationDeltaSubscriptionProjector();

        // when
        for (int iteration = 0; iteration < 1_000; iteration++) {
            String beforeRoot = "root-before-" + iteration;
            String afterRoot = "root-after-" + iteration;
            CoordinationSubscriptionOccurrence before = occurrence(
                    "/", beforeRoot, "root-channel", iteration,
                    1L, order(1L));
            CoordinationSubscriptionOccurrence after =
                    before.withScopeBlueId(afterRoot);
            CoordinationSubscriptionSnapshot previous = snapshot(
                    beforeRoot,
                    1L,
                    order(1L),
                    Collections.<String, List<String>>emptyMap(),
                    Collections.<String>emptySet(),
                    before);
            CoordinationCommitProjectionEvidence evidence = evidence(
                    afterRoot,
                    2L,
                    order(2L),
                    SubscriptionDelta.empty(),
                    Collections.singletonList(after),
                    Collections.singleton(before.occurrenceKey()),
                    Collections.<String, List<String>>emptyMap(),
                    Collections.<String>emptySet(),
                    null,
                    true);
            CoordinationSubscriptionSnapshot oracle = snapshot(
                    afterRoot,
                    2L,
                    order(2L),
                    Collections.<String, List<String>>emptyMap(),
                    Collections.<String>emptySet(),
                    after);
            CoordinationSubscriptionSnapshot actual = projector.apply(
                    previous, evidence).snapshot();

            // then
            assertEquals(
                    oracle.toMap(),
                    actual.toMap(),
                    "projection mismatch at iteration " + iteration);
            assertEquals(
                    oracle.digest(),
                    actual.digest(),
                    "projection identity mismatch at iteration "
                            + iteration);
        }
        Round4ParityReceipt.write(
                "projectionComparisons", 1_000L, 0L);
    }

    @Test
    void shouldMatchTheCompleteProjectionForRefreshAddRetireAndTopology()
            throws Exception {
        // given
        CoordinationSubscriptionOccurrence root = occurrence(
                "/", "root-1", "root-channel", 0, 1L, order(1L));
        CoordinationSubscriptionOccurrence refreshedBefore = occurrence(
                "/orders/one", "scope-one-v1", "one-channel", 1,
                1L, order(1L));
        CoordinationSubscriptionOccurrence untouched = occurrence(
                "/orders/two", "scope-two", "two-channel", 2,
                1L, order(1L));
        CoordinationSubscriptionOccurrence retiredBefore = occurrence(
                "/orders/old", "scope-old", "old-channel", 3,
                1L, order(1L));
        Map<String, List<String>> oldRoutes = Collections.singletonMap(
                "/", Arrays.asList("/orders/one", "/orders/two", "/orders/old"));
        CoordinationSubscriptionSnapshot previous = snapshot(
                "root-1",
                1L,
                order(1L),
                oldRoutes,
                Collections.<String>emptySet(),
                root,
                refreshedBefore,
                untouched,
                retiredBefore);
        CoordinationSubscriptionOccurrence refreshed = refreshedBefore
                .withScopeBlueId("scope-one-v2");
        CoordinationSubscriptionOccurrence rootRefreshed = root
                .withScopeBlueId("root-2");
        CoordinationSubscriptionOccurrence added = occurrence(
                "/orders/new", "scope-new", "new-channel", 4,
                2L, order(2L));
        SubscriptionDelta.Entry retirement = closeAt(
                retiredBefore, 2L);
        SubscriptionDelta delta = new SubscriptionDelta(
                Collections.singletonList(added.toSubscriptionDeltaEntry()),
                Collections.singletonList(retirement));
        Map<String, List<String>> routes = Collections.singletonMap(
                "/", Arrays.asList("/orders/new", "/orders/one", "/orders/two"));
        Set<String> pruned = Collections.singleton("/orders/old");
        EffectiveFragmentationCatalog catalog = catalog("root-2");
        CoordinationCommitProjectionEvidence evidence = evidence(
                "root-2",
                2L,
                order(2L),
                delta,
                Arrays.asList(rootRefreshed, refreshed, added),
                new LinkedHashSet<String>(Arrays.asList(
                        root.occurrenceKey(),
                        refreshedBefore.occurrenceKey())),
                routes,
                pruned,
                catalog,
                true);
        CoordinationSubscriptionSnapshot completeOracle = snapshot(
                "root-2",
                2L,
                order(2L),
                routes,
                pruned,
                rootRefreshed,
                refreshed,
                untouched,
                added);

        // when
        FastPathWorkMetrics metrics = new FastPathWorkMetrics();
        FastPathWorkMetrics.Snapshot beforeWork = metrics.snapshot();
        CoordinationSubscriptionUpdate actual =
                new CoordinationDeltaSubscriptionProjector(metrics).apply(
                        previous, evidence);
        FastPathWorkMetrics.Snapshot work =
                metrics.snapshot().minus(beforeWork);

        // then
        assertEquals(0L, work.snapshotSerializations(),
                "successor publication must not serialize all occurrences");
        assertEquals(4L, work.merkleOccurrenceUpdates());
        assertEquals(2L, work.affectedOccurrences());
        assertEquals(2L, work.refreshedOccurrences());
        assertEquals(0L, work.unrelatedOccurrences(),
                "incremental projection must not visit unrelated rows");
        assertEquals(0L, actual.snapshot().planningMetrics()
                .constructionOccurrenceValidationCount(),
                "trusted persistent successor must not revalidate all rows");
        assertEquals(completeOracle.toMap(), actual.snapshot().toMap());
        assertEquals(completeOracle.digest(), actual.snapshot().digest());
        assertEquals(routes, actual.snapshot().processEmbeddedRoutes());
        assertEquals(pruned, actual.snapshot().prunedScopePaths());
        assertEquals(Collections.singletonList(added), actual.added());
        assertEquals(1, actual.retired().size());
        assertEquals(Long.valueOf(2L),
                actual.retired().get(0).endAtRootRevision());
        assertEquals(refreshed.headerIdentityBlueId(),
                actual.snapshot().occurrence(
                        refreshed.occurrenceKey()).headerIdentityBlueId());
        assertSame(untouched, actual.snapshot().occurrence(
                untouched.occurrenceKey()),
                "non-intersecting evidence must remain shared by identity");
        assertSame(refreshed, actual.snapshot().occurrence(
                refreshed.occurrenceKey()),
                "the one affected occurrence uses the supplied exact evidence");
        assertSame(catalog, actual.fragmentationCatalog().orElseThrow(
                () -> new AssertionError("catalog missing")));
        assertFalse(actual.snapshot().occurrences().contains(retiredBefore));
    }

    @Test
    void shouldRejectStaleIncompleteAndUnderSpecifiedChangeEvidence() {
        // given
        CoordinationSubscriptionOccurrence retained = occurrence(
                "/", "root-1", "root-channel", 0, 1L, order(1L));
        CoordinationSubscriptionSnapshot previous = snapshot(
                "root-1",
                1L,
                order(1L),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptySet(),
                retained);
        CoordinationDeltaSubscriptionProjector projector =
                new CoordinationDeltaSubscriptionProjector();
        CoordinationCommitProjectionEvidence incomplete = evidence(
                "root-2", 2L, order(2L), SubscriptionDelta.empty(),
                Collections.<CoordinationSubscriptionOccurrence>emptyList(),
                Collections.<String>emptySet(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptySet(), null, false);
        CoordinationCommitProjectionEvidence missingRefresh = evidence(
                "root-2", 2L, order(2L), SubscriptionDelta.empty(),
                Collections.<CoordinationSubscriptionOccurrence>emptyList(),
                Collections.singleton(retained.occurrenceKey()),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptySet(), null, true);
        CoordinationCommitProjectionEvidence staleRevision = evidence(
                "root-stale", 1L, order(2L), SubscriptionDelta.empty(),
                Collections.<CoordinationSubscriptionOccurrence>emptyList(),
                Collections.<String>emptySet(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptySet(), null, true);
        CoordinationCommitProjectionEvidence staleOrder = evidence(
                "root-2", 2L, order(1L), SubscriptionDelta.empty(),
                Collections.<CoordinationSubscriptionOccurrence>emptyList(),
                Collections.<String>emptySet(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptySet(), null, true);

        // when
        DeltaProjectionApplier.ColdProjectionRequiredException incompleteFailure =
                assertThrows(
                        DeltaProjectionApplier.ColdProjectionRequiredException.class,
                        () -> projector.apply(previous, incomplete));
        DeltaProjectionApplier.ColdProjectionRequiredException refreshFailure =
                assertThrows(
                        DeltaProjectionApplier.ColdProjectionRequiredException.class,
                        () -> projector.apply(previous, missingRefresh));
        IllegalArgumentException revisionFailure = assertThrows(
                IllegalArgumentException.class,
                () -> projector.apply(previous, staleRevision));
        IllegalArgumentException orderFailure = assertThrows(
                IllegalArgumentException.class,
                () -> projector.apply(previous, staleOrder));

        // then
        assertTrue(incompleteFailure.getMessage().contains("incomplete"));
        assertTrue(refreshFailure.getMessage().contains("lacks current evidence"));
        assertTrue(revisionFailure.getMessage().contains("exact successor"));
        assertTrue(orderFailure.getMessage().contains("advance"));
    }

    @Test
    void shouldRejectUnaffectedEvidenceAndCatalogBoundToAnotherRoot()
            throws Exception {
        // given
        CoordinationSubscriptionOccurrence retained = occurrence(
                "/", "root-1", "root-channel", 0, 1L, order(1L));
        CoordinationSubscriptionSnapshot previous = snapshot(
                "root-1",
                1L,
                order(1L),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptySet(),
                retained);
        CoordinationCommitProjectionEvidence extraEvidence = evidence(
                "root-2", 2L, order(2L), SubscriptionDelta.empty(),
                Collections.singletonList(retained),
                Collections.<String>emptySet(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptySet(), null, true);
        EffectiveFragmentationCatalog wrongCatalog = catalog("another-root");

        // when
        IllegalArgumentException extraFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinationDeltaSubscriptionProjector().apply(
                        previous, extraEvidence));
        IllegalArgumentException catalogFailure = assertThrows(
                IllegalArgumentException.class,
                () -> evidence(
                        "root-2", 2L, order(2L), SubscriptionDelta.empty(),
                        Collections.<CoordinationSubscriptionOccurrence>emptyList(),
                        Collections.<String>emptySet(),
                        Collections.<String, List<String>>emptyMap(),
                        Collections.<String>emptySet(), wrongCatalog, true));

        // then
        assertTrue(extraFailure.getMessage().contains("unaffected"));
        assertTrue(catalogFailure.getMessage().contains("Root mismatch"));
    }

    @Test
    void shouldProduceHistoryIndependentMerkleIdentity() {
        CoordinationSubscriptionOccurrence one = occurrence(
                "/one", "one-v1", "one-channel", 1, 1L, order(1L));
        CoordinationSubscriptionOccurrence oneRefreshed =
                one.withScopeBlueId("one-v2");
        CoordinationSubscriptionOccurrence two = occurrence(
                "/two", "two", "two-channel", 2, 1L, order(1L));
        CoordinationSubscriptionOccurrence three = occurrence(
                "/three", "three", "three-channel", 3, 1L, order(1L));
        CoordinationSubscriptionOccurrence retired = occurrence(
                "/retired", "retired", "retired-channel", 4,
                1L, order(1L));

        CoordinationSubscriptionMerkleIndex history =
                CoordinationSubscriptionMerkleIndex.empty()
                        .updated(null, retired)
                        .updated(null, two)
                        .updated(null, one)
                        .updated(one, oneRefreshed)
                        .updated(null, three)
                        .updated(retired, null);
        CoordinationSubscriptionMerkleIndex rebuilt =
                CoordinationSubscriptionMerkleIndex.empty()
                        .updated(null, three)
                        .updated(null, oneRefreshed)
                        .updated(null, two);

        assertEquals(3, history.size());
        assertEquals(rebuilt.digest(), history.digest());
        assertEquals(
                CoordinationSubscriptionMerkleIndex.from(Arrays.asList(
                        two, three, oneRefreshed)).digest(),
                history.digest());
    }

    private static CoordinationSubscriptionSnapshot snapshot(
            String rootBlueId,
            long revision,
            ExternalOrderKey frontier,
            Map<String, List<String>> routes,
            Set<String> pruned,
            CoordinationSubscriptionOccurrence... occurrences) {
        return new CoordinationSubscriptionSnapshot(
                "language-runtime",
                "coordination-runtime",
                rootBlueId,
                revision,
                frontier,
                Arrays.asList(occurrences),
                routes,
                pruned);
    }

    private static CoordinationCommitProjectionEvidence evidence(
            String rootBlueId,
            long revision,
            ExternalOrderKey order,
            SubscriptionDelta delta,
            List<CoordinationSubscriptionOccurrence> current,
            Set<String> affected,
            Map<String, List<String>> routes,
            Set<String> pruned,
            EffectiveFragmentationCatalog catalog,
            boolean complete) {
        return new CoordinationCommitProjectionEvidence(
                rootBlueId,
                revision,
                order,
                delta,
                current,
                affected,
                routes,
                pruned,
                catalog,
                complete);
    }

    private static CoordinationSubscriptionOccurrence occurrence(
            String scope,
            String scopeBlueId,
            String channel,
            int index,
            long activationRevision,
            ExternalOrderKey activationOrder) {
        boolean root = "/".equals(scope);
        Map<String, String> headerFields = new LinkedHashMap<>();
        headerFields.put("channel", "header-field-" + index);
        return new CoordinationSubscriptionOccurrence(
                scope,
                scopeBlueId,
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
                headerFields,
                Collections.singletonList("timeline:" + index),
                Long.valueOf(activationRevision),
                activationOrder,
                null,
                ExternalChannelDependencySnapshot.none());
    }

    private static SubscriptionDelta.Entry closeAt(
            CoordinationSubscriptionOccurrence occurrence,
            long revision) {
        SubscriptionDelta.Entry active = occurrence.toSubscriptionDeltaEntry();
        return new SubscriptionDelta.Entry(
                active.scopePath(),
                active.channelKey(),
                active.effectiveTypeBlueId(),
                active.sourceContributionNodeBlueIds(),
                active.order(),
                active.subscriptionKeys(),
                active.checkpointDomainBlueId(),
                active.dependencies(),
                active.activationRootRevision(),
                active.startAfterExternalOrderKey(),
                Long.valueOf(revision));
    }

    @SuppressWarnings("unchecked")
    private static EffectiveFragmentationCatalog catalog(String rootBlueId)
            throws Exception {
        Constructor<EffectiveFragmentationCatalog> constructor =
                EffectiveFragmentationCatalog.class.getDeclaredConstructor(
                        String.class, Map.class, Map.class);
        constructor.setAccessible(true);
        Map<String, List<String>> paths = Collections.singletonMap(
                "/", Collections.<String>emptyList());
        Map<String, List<Object>> contracts = Collections.singletonMap(
                "/", Collections.emptyList());
        return constructor.newInstance(rootBlueId, paths, contracts);
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(
                Collections.singletonList(BigInteger.valueOf(value)));
    }
}
