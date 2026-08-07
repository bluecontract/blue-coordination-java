package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.CoordinationFragmentationCatalogHarness;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoordinationHostQuotaRuntimeTest {
    @Test
    void shouldTraceSplitterWorkInExactDeterministicOrder() {
        // given
        Node root =
                CoordinationHostQuotaTestSupport.embeddedRoot(1);
        CoordinationDocumentSplitter splitter =
                CoordinationFragmentationCatalogHarness.splitter(
                        root,
                        Collections.emptyMap());
        CoordinationHostQuotaSession session =
                CoordinationHostQuotaSession.observing();
        CoordinationHostQuotaSession repeatedSession =
                CoordinationHostQuotaSession.observing();

        // when
        CoordinationDocumentSplitter.SplitGraph split;
        CoordinationDocumentSplitter.SplitGraph repeated;
        split = splitter.splitDocument(root, session);
        repeated = splitter.splitDocument(root, repeatedSession);

        // then
        List<CoordinationHostQuotaTraceEntry> trace =
                session.trace();
        assertEquals(
                trace,
                repeatedSession.trace());
        assertEquals(
                split.fragments().size(),
                session.quantity(
                        CoordinationHostQuotaSchedule
                                .SPLITTER_FRAGMENT_ADMITTED));
        assertEquals(
                split.edgeOccurrences().size(),
                session.quantity(
                        CoordinationHostQuotaSchedule
                                .FRAGMENT_EDGE_METADATA_PRODUCED));
        assertEquals(
                split.fragments().keySet(),
                repeated.fragments().keySet());
        assertEquals(
                split.edgeOccurrences(),
                repeated.edgeOccurrences());
        assertEntry(
                trace, 0,
                "splitterCatalogEntryVisited",
                "/",
                "effective-scope");
        assertEntry(
                trace, 1,
                "splitterCatalogEntryVisited",
                "/child1",
                "effective-scope");
        assertEntry(
                trace, 2,
                "splitterCatalogEntryVisited",
                "/child1",
                "embedded-path");
        assertEntry(
                trace, 3,
                "splitterCutValidated",
                "/child1",
                "embedded-root");
        assertEntry(
                trace, 4,
                "splitterCatalogEntryVisited",
                "/contracts/embedded",
                "effective-contract");
    }

    @Test
    void shouldExposeOnlyTheAdmittedSplitterPrefixAtTheCutLimit() {
        // given
        Node root =
                CoordinationHostQuotaTestSupport.embeddedRoot(3);
        CoordinationDocumentSplitter splitter =
                CoordinationFragmentationCatalogHarness.splitter(
                        root,
                        Collections.emptyMap());
        CoordinationHostQuotaSession session =
                CoordinationHostQuotaSession.observing(
                        CoordinationHostQuotaTestSupport
                                .schedule(2, 4));

        // when
        CoordinationHostQuotaExceededException failure;
        failure = assertThrows(
                CoordinationHostQuotaExceededException.class,
                () -> splitter.splitDocument(root, session));

        // then
        assertEquals("maxSplitterCuts", failure.limitName());
        assertEquals(2L, failure.limit());
        assertEquals(3L, failure.attemptedQuantity());
        assertEquals(2L, failure.admittedQuantity());
        assertEquals(
                2L,
                session.quantity(
                        CoordinationHostQuotaSchedule
                                .SPLITTER_CUT_VALIDATED));
        assertEquals(
                0L,
                session.quantity(
                        CoordinationHostQuotaSchedule
                                .SPLITTER_FRAGMENT_ADMITTED));
        List<CoordinationHostQuotaTraceEntry> trace =
                session.trace();
        assertEquals(9, trace.size());
        assertEntry(
                trace, 5,
                "splitterCutValidated",
                "/child1",
                "embedded-root");
        assertEntry(
                trace, 7,
                "splitterCutValidated",
                "/child2",
                "embedded-root");
        assertEntry(
                trace, 8,
                "splitterCatalogEntryVisited",
                "/child3",
                "embedded-path");
    }

    @Test
    void shouldRejectSplitterDiscoveryBeforeOverLimitCatalogEntryIsAdmitted() {
        // given
        Node root =
                CoordinationHostQuotaTestSupport.embeddedRoot(1);
        CoordinationDocumentSplitter splitter =
                CoordinationFragmentationCatalogHarness.splitter(
                        root,
                        Collections.emptyMap());
        CoordinationHostQuotaSession session =
                CoordinationHostQuotaSession.observing(
                        CoordinationHostQuotaTestSupport
                                .limitedCatalogEntries(1));

        // when
        CoordinationHostQuotaExceededException failure;
        failure = assertThrows(
                CoordinationHostQuotaExceededException.class,
                () -> splitter.splitDocument(root, session));

        // then
        assertEquals(
                "maxSplitterCatalogEntriesPerSplit",
                failure.limitName());
        assertEquals(1L, failure.limit());
        assertEquals(2L, failure.attemptedQuantity());
        assertEquals(1L, failure.admittedQuantity());
        assertEquals(
                1L,
                session.quantity(
                        CoordinationHostQuotaSchedule
                                .SPLITTER_CATALOG_ENTRY_VISITED));
        assertEquals(1, session.trace().size());
    }

    @Test
    void shouldRejectPhysicalFragmentAdmissionBeforeTheOverLimitFragment() {
        // given
        Node event = eventWithTwoChildren();
        CoordinationHostQuotaSession session =
                CoordinationHostQuotaSession.observing(
                        CoordinationHostQuotaTestSupport
                                .limitedSplitterFragments(1));

        // when
        CoordinationHostQuotaExceededException failure =
                assertThrows(
                        CoordinationHostQuotaExceededException.class,
                        () -> CoordinationDocumentSplitter
                                .forEventSplitting()
                                .splitEvent(event, session));

        // then
        assertEquals(
                "maxSplitterFragmentsPerSplit",
                failure.limitName());
        assertEquals(2L, failure.attemptedQuantity());
        assertEquals(1L, failure.admittedQuantity());
        assertEquals(
                1L,
                session.quantity(
                        CoordinationHostQuotaSchedule
                                .SPLITTER_FRAGMENT_ADMITTED));
        assertEquals(
                0L,
                session.quantity(
                        CoordinationHostQuotaSchedule
                                .FRAGMENT_EDGE_METADATA_PRODUCED));
    }

    @Test
    void shouldRejectFragmentMetadataBeforeTheOverLimitEdgeIsAdmitted() {
        // given
        Node event = eventWithTwoChildren();
        CoordinationHostQuotaSession session =
                CoordinationHostQuotaSession.observing(
                        CoordinationHostQuotaTestSupport
                                .limitedFragmentEdges(1));

        // when
        CoordinationHostQuotaExceededException failure =
                assertThrows(
                        CoordinationHostQuotaExceededException.class,
                        () -> CoordinationDocumentSplitter
                                .forEventSplitting()
                                .splitEvent(event, session));

        // then
        assertEquals(
                "maxFragmentEdgeOccurrencesPerSplit",
                failure.limitName());
        assertEquals(2L, failure.attemptedQuantity());
        assertEquals(1L, failure.admittedQuantity());
        assertEquals(
                1L,
                session.quantity(
                        CoordinationHostQuotaSchedule
                                .FRAGMENT_EDGE_METADATA_PRODUCED));
        CoordinationHostQuotaTraceEntry admitted =
                session.trace().get(
                        session.trace().size() - 1);
        assertEquals("split-event", admitted.operation());
        assertEquals(
                "fragmentEdgeMetadataProduced",
                admitted.counter());
    }

    @Test
    void shouldRejectPrefetchConstructionBeforeTheOverLimitIdentityIsAdmitted() {
        // given
        CoordinationHostQuotaSession session =
                CoordinationHostQuotaSession.observing(
                        CoordinationHostQuotaTestSupport
                                .limitedPrefetchIdentities(1));

        // when
        session.recordPrefetchIdentity(0);
        CoordinationHostQuotaExceededException failure =
                assertThrows(
                        CoordinationHostQuotaExceededException.class,
                        () -> session.recordPrefetchIdentity(1));

        // then
        assertEquals(
                "maxPrefetchIdentitiesPerPlan",
                failure.limitName());
        assertEquals(2L, failure.attemptedQuantity());
        assertEquals(1L, failure.admittedQuantity());
        assertEquals(
                1L,
                session.quantity(
                        CoordinationHostQuotaSchedule
                                .PREFETCH_IDENTITY_CONSTRUCTED));
    }

    private static Node eventWithTwoChildren() {
        return new Node()
                .properties(
                        "first",
                        new Node().value(1))
                .properties(
                        "second",
                        new Node().value(2));
    }

    private static void assertEntry(
            List<CoordinationHostQuotaTraceEntry> trace,
            int index,
            String counter,
            String path,
            String reason) {
        CoordinationHostQuotaTraceEntry entry =
                trace.get(index);
        assertEquals((long) index, entry.sequence());
        assertEquals(counter, entry.counter());
        assertEquals(1L, entry.quantity());
        assertEquals("split-document", entry.operation());
        assertEquals(path, entry.logicalPath());
        assertEquals(reason, entry.reason());
    }
}
