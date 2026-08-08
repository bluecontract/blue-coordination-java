package blue.coordination.engine.memory;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationRootViewCacheSnapshot;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.engine.fastpath.ReferenceCutConfiguration;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.coordination.processor.RepositoryIndependentCoordinationTestRuntime;
import blue.coordination.processor.RepositoryIndependentCoordinationTypes;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProviderResult;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InMemoryCoordinationCheckpointWarmRestoreTest {

    @Test
    void shouldShareImmutableDerivedStateAcrossIndependentUsableForks() {
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open()) {
            InMemoryCoordinationFragmentStore sourceStore =
                    new InMemoryCoordinationFragmentStore(
                            CoordinationDocumentSplitter
                                    .FRAGMENTATION_PROFILE_ID);
            runtime.addNodeProvider(sourceStore);
            Node exactRoot = initializedRoot(runtime);
            try (InMemoryCoordinationEnvironment source = environment(
                    runtime, sourceStore)) {
                source.addDocument(exactRoot);
                source.addDocument(exactRoot);
                InMemoryCoordinationCheckpoint checkpoint =
                        source.checkpoint();

                try (InMemoryCoordinationEnvironment first =
                             restoredEnvironment(runtime, checkpoint);
                     InMemoryCoordinationEnvironment second =
                             restoredEnvironment(runtime, checkpoint)) {
                    int restoredSessions = checkpoint.sessionCount();
                    assertEquals(restoredSessions,
                            first.engine()
                                    .checkpointPreparedContextReuseCount());
                    assertEquals(restoredSessions,
                            second.engine()
                                    .checkpointPreparedContextReuseCount());
                    assertEquals(0L, first.engine()
                            .checkpointPreparedContextFallbackCount());
                    assertEquals(0L, second.engine()
                            .checkpointPreparedContextFallbackCount());
                    assertEquals(0L, first.engine()
                            .checkpointPreparedContextRebuildCount());
                    assertEquals(0L, second.engine()
                            .checkpointPreparedContextRebuildCount());
                    assertTrue(first.fragmentStore()
                            .checkpointPreparedRepresentationReuseCount()
                            > 0L);
                    assertTrue(second.fragmentStore()
                            .checkpointPreparedRepresentationReuseCount()
                            > 0L);
                    assertTrue(first.fragmentStore()
                            .checkpointPreparedFingerprintReuseCount() > 0L);
                    assertTrue(second.fragmentStore()
                            .checkpointPreparedFingerprintReuseCount() > 0L);
                    assertEquals(0L, first.fragmentStore()
                            .checkpointPreparedRepresentationRebuildCount());
                    assertEquals(0L, second.fragmentStore()
                            .checkpointPreparedRepresentationRebuildCount());
                    assertTrue(first.engine()
                            .reusesReferenceCutCheckpointKernel(
                                    checkpoint.preparedRootState));
                    assertTrue(second.engine()
                            .reusesReferenceCutCheckpointKernel(
                                    checkpoint.preparedRootState));
                    assertTrue(first.engine()
                            .reusesPlanningProjectionCheckpointKernel(
                                    checkpoint.preparedRootState));
                    assertTrue(second.engine()
                            .reusesPlanningProjectionCheckpointKernel(
                                    checkpoint.preparedRootState));

                    for (ManagedDocumentSnapshot session
                            : first.sessionStore().sessions()) {
                        assertTrue(first.engine()
                                .reusesPreparedCheckpointContext(
                                        session,
                                        checkpoint.preparedRootState),
                                "first fork must install the exact checkpoint "
                                        + "context reference");
                    }
                    for (ManagedDocumentSnapshot session
                            : second.sessionStore().sessions()) {
                        assertTrue(second.engine()
                                .reusesPreparedCheckpointContext(
                                        session,
                                        checkpoint.preparedRootState),
                                "second fork must install the exact checkpoint "
                                        + "context reference");
                    }

                    String fragmentBlueId = checkpoint.fragments.keySet()
                            .iterator().next();
                    Node escaped = first.fragmentStore()
                            .fetchByBlueId(fragmentBlueId).get(0);
                    escaped.value("public mutation");
                    assertEquals(fragmentBlueId,
                            DirectBlueIdCalculator.calculateBlueId(
                                    first.fragmentStore()
                                            .fetchByBlueId(fragmentBlueId)
                                            .get(0)));
                    assertEquals(fragmentBlueId,
                            DirectBlueIdCalculator.calculateBlueId(
                                    second.fragmentStore()
                                            .fetchByBlueId(fragmentBlueId)
                                            .get(0)));

                    first.addDocument(exactRoot);
                    assertEquals(restoredSessions + 1,
                            first.sessionStore().sessions().size());
                    assertEquals(restoredSessions,
                            second.sessionStore().sessions().size());
                    second.addDocument(exactRoot);
                    assertEquals(restoredSessions + 1,
                            second.sessionStore().sessions().size());

                    InMemoryCoordinationCheckpoint firstAdvanced =
                            first.checkpoint();
                    InMemoryCoordinationCheckpoint secondAdvanced =
                            second.checkpoint();
                    assertTrue(firstAdvanced.sharesImmutableContentWith(
                            secondAdvanced));
                    assertFalse(firstAdvanced.sharesMutableStateWith(
                            secondAdvanced));
                }
            }
        }
    }

    @Test
    void shouldRebuildExactlyForEveryIncompatibleCheckpointBinding() {
        InMemoryCoordinationCheckpoint checkpoint;
        try (RepositoryIndependentCoordinationTestRuntime sourceRuntime =
                     RepositoryIndependentCoordinationTestRuntime.open()) {
            InMemoryCoordinationFragmentStore sourceStore =
                    new InMemoryCoordinationFragmentStore(
                            CoordinationDocumentSplitter
                                    .FRAGMENTATION_PROFILE_ID);
            sourceRuntime.addNodeProvider(sourceStore);
            try (InMemoryCoordinationEnvironment source = environment(
                    sourceRuntime, sourceStore)) {
                source.addDocument(initializedRoot(sourceRuntime));
                checkpoint = source.checkpoint();
            }

            try (InMemoryCoordinationEnvironment environmentMismatch =
                         restoredEnvironment(
                                 sourceRuntime,
                                 checkpoint,
                                 "test:other-environment",
                                 ReferenceCutConfiguration.disabled())) {
                assertExactContextFallback(
                        environmentMismatch,
                        checkpoint,
                        checkpoint.sessionCount());
            }
            try (InMemoryCoordinationEnvironment algorithmMismatch =
                         restoredEnvironment(
                                 sourceRuntime,
                                 checkpoint,
                                 "test:checkpoint-warm-restore",
                                 ReferenceCutConfiguration
                                         .verifiedDefaults())) {
                assertExactContextFallback(
                        algorithmMismatch,
                        checkpoint,
                        checkpoint.sessionCount());
            }
            try (InMemoryCoordinationEnvironment cacheBoundMismatch =
                         restoredEnvironment(
                                 sourceRuntime,
                                 checkpoint,
                                 "test:checkpoint-warm-restore",
                                 ReferenceCutConfiguration.disabled(),
                                 CoordinationProcessingEngine
                                         .DEFAULT_ROOT_VIEW_CACHE_MAXIMUM_SIZE
                                         + 1)) {
                assertExactContextFallback(
                        cacheBoundMismatch,
                        checkpoint,
                        checkpoint.sessionCount());
            }

            InMemoryCoordinationCheckpoint storageMismatch =
                    withStorageGeneration(
                            checkpoint,
                            "test:incompatible-storage-generation");
            assertFalse(checkpoint
                    .canonicalFragmentStorageGenerationAuthority.equals(
                            storageMismatch
                                    .canonicalFragmentStorageGenerationAuthority));
            try (InMemoryCoordinationEnvironment restored =
                         restoredEnvironment(sourceRuntime, storageMismatch)) {
                assertExactContextFallback(
                        restored,
                        storageMismatch,
                        checkpoint.sessionCount());
                assertEquals(0L, restored.fragmentStore()
                        .checkpointPreparedRepresentationReuseCount());
                assertTrue(restored.fragmentStore()
                        .checkpointPreparedRepresentationRebuildCount() > 0L);
                assertTrue(restored.fragmentStore()
                        .checkpointPreparedFingerprintRebuildCount() > 0L);
            }
        }

        try (RepositoryIndependentCoordinationTestRuntime otherRuntime =
                     RepositoryIndependentCoordinationTestRuntime.open();
             InMemoryCoordinationEnvironment runtimeMismatch =
                     restoredEnvironment(otherRuntime, checkpoint)) {
            assertExactContextFallback(
                    runtimeMismatch,
                    checkpoint,
                    checkpoint.sessionCount());
        }
    }

    @Test
    void shouldCheckpointOnlyBoundedWarmStateAndRebuildColdRootsLazily() {
        final int cacheBound = 2;
        final int sessionCount = 5;
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open()) {
            InMemoryCoordinationFragmentStore sourceStore =
                    new InMemoryCoordinationFragmentStore(
                            CoordinationDocumentSplitter
                                    .FRAGMENTATION_PROFILE_ID);
            runtime.addNodeProvider(sourceStore);
            try (InMemoryCoordinationEnvironment source = environment(
                    runtime, sourceStore, cacheBound)) {
                for (int index = 0; index < sessionCount; index++) {
                    source.addDocument(initializedRoot(runtime, index));
                }
                assertBoundedRootCache(source, cacheBound);
                sourceStore.resetReadCounts();

                InMemoryCoordinationCheckpoint checkpoint =
                        source.checkpoint();

                assertEquals(0L, sourceStore.singleReadCount());
                assertEquals(0L, sourceStore.batchReadCount(),
                        "checkpoint capture must not reconstruct cold Roots");
                assertEquals(cacheBound, checkpoint.currentRootViews.size());

                try (InMemoryCoordinationEnvironment restored =
                             restoredEnvironment(
                                     runtime,
                                     checkpoint,
                                     "test:checkpoint-warm-restore",
                                     ReferenceCutConfiguration.disabled(),
                                     cacheBound)) {
                    assertEquals(sessionCount,
                            restored.sessionStore().sessions().size());
                    assertEquals(cacheBound, restored.engine()
                            .checkpointPreparedContextReuseCount());
                    assertEquals(sessionCount - cacheBound,
                            restored.engine()
                                    .checkpointPreparedContextFallbackCount());
                    assertEquals(0L, restored.engine()
                            .checkpointPreparedContextRebuildCount());
                    assertEquals(0L, restored.fragmentStore().batchReadCount(),
                            "restore must leave non-retained sessions cold");
                    assertBoundedRootCache(restored, cacheBound);

                    ManagedDocumentSnapshot cold = coldSession(
                            restored, checkpoint);
                    CoordinationRootViewCacheSnapshot before =
                            restored.rootViewCacheSnapshot();
                    restored.engine().prepareRootContext(cold);
                    CoordinationRootViewCacheSnapshot after =
                            restored.rootViewCacheSnapshot();

                    assertTrue(restored.fragmentStore().batchReadCount() > 0L,
                            "the first cold request must reconstruct exactly "
                                    + "from authoritative fragments");
                    assertEquals(before.missCount() + 1L,
                            after.missCount());
                    assertBoundedRootCache(restored, cacheBound);
                    long readsAfterFirst =
                            restored.fragmentStore().batchReadCount();
                    restored.engine().prepareRootContext(cold);
                    assertEquals(readsAfterFirst,
                            restored.fragmentStore().batchReadCount(),
                            "the rebuilt context must serve the next request");
                    assertEquals(cold.currentRootBlueId(),
                            restored.sessionStore()
                                    .findSession(cold.sessionId()).get()
                                    .currentRootBlueId());
                }
            }
        }
    }

    @Test
    void shouldFailClosedWhenPortableStoreOmitsStorageAuthority() {
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open()) {
            InMemoryCoordinationFragmentStore backing =
                    new InMemoryCoordinationFragmentStore(
                            CoordinationDocumentSplitter
                                    .FRAGMENTATION_PROFILE_ID);
            CoordinationFragmentStore portable =
                    new AuthorityOmittingFragmentStore(backing);
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> CoordinationProcessingEngine.builder()
                            .contracts(runtime.contracts())
                            .documentProcessor(runtime.platformProcessor())
                            .fragmentStore(portable)
                            .sessionStore(
                                    new InMemoryCoordinationSessionStore())
                            .bundleLoader(
                                    new InMemoryCoordinationProcessingBundleLoader(
                                            portable,
                                            runtime.platformProcessor()
                                                    .administration()
                                                    .runtimeAccess()
                                                    .languageRuntime()
                                                    .getNodeProvider()))
                            .environmentIdentity(
                                    "test:missing-storage-authority")
                            .build());
            assertTrue(failure.getMessage().contains(
                    "storage generation authority"));
        }
    }

    @Test
    void shouldRestoreEveryPreparedContextWithoutReadingTheFragmentStore() {
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open()) {
            InMemoryCoordinationFragmentStore sourceStore =
                    new InMemoryCoordinationFragmentStore(
                            CoordinationDocumentSplitter
                                    .FRAGMENTATION_PROFILE_ID);
            runtime.addNodeProvider(sourceStore);
            Node exactRoot = initializedRoot(runtime);
            InMemoryCoordinationCheckpoint checkpoint;
            try (InMemoryCoordinationEnvironment source = environment(
                    runtime, sourceStore)) {
                source.addDocument(exactRoot);
                source.addDocument(exactRoot);
                checkpoint = source.checkpoint();
            }

            try (InMemoryCoordinationEnvironment restored =
                         restoredEnvironment(runtime, checkpoint)) {
                assertEquals(2, restored.sessionStore().sessions().size());
                assertEquals(2L, restored.engine()
                        .checkpointPreparedContextReuseCount());
                assertEquals(0L, restored.engine()
                        .checkpointPreparedContextFallbackCount());
                assertEquals(0L, restored.engine()
                        .checkpointPreparedContextRebuildCount());
                assertTrue(restored.engine()
                        .reusesReferenceCutCheckpointKernel(
                                checkpoint.preparedRootState));
                assertTrue(restored.engine()
                        .reusesPlanningProjectionCheckpointKernel(
                                checkpoint.preparedRootState));
                assertEquals(0L, restored.fragmentStore().batchReadCount());

                for (ManagedDocumentSnapshot session
                        : restored.sessionStore().sessions()) {
                    restored.engine().prepareRootContext(session);
                }

                assertEquals(0L, restored.fragmentStore().batchReadCount(),
                        "every restored generation must already be warm");
            }
        }
    }

    @Test
    void shouldFailClosedBeforeReadingForIncompleteOrTamperedProcessViews() {
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open()) {
            InMemoryCoordinationFragmentStore sourceStore =
                    new InMemoryCoordinationFragmentStore(
                            CoordinationDocumentSplitter
                                    .FRAGMENTATION_PROFILE_ID);
            runtime.addNodeProvider(sourceStore);
            InMemoryCoordinationCheckpoint checkpoint;
            try (InMemoryCoordinationEnvironment source = environment(
                    runtime, sourceStore)) {
                source.addDocument(initializedRoot(runtime));
                checkpoint = source.checkpoint();
            }

            try (InMemoryCoordinationEnvironment restored =
                         restoredEnvironment(runtime, checkpoint)) {
                ManagedDocumentSnapshot session = restored.sessionStore()
                        .sessions().iterator().next();
                Map<String, Node> retained =
                        checkpoint.completeProcessingViewsForRestore(
                                session.fragmentInventoryIdentity());

                assertThrows(IllegalArgumentException.class,
                        () -> restored.engine()
                                .prepareRootContextFromCheckpoint(
                                        session,
                                        Collections.<String, Node>emptyMap()));

                Map<String, Node> tampered =
                        new LinkedHashMap<String, Node>(retained);
                String firstBlueId = tampered.keySet().iterator().next();
                tampered.put(firstBlueId, new Node().value("tampered"));
                assertThrows(IllegalArgumentException.class,
                        () -> restored.engine()
                                .prepareRootContextFromCheckpoint(
                                        session,
                                        tampered));

                assertEquals(0L, restored.fragmentStore().batchReadCount(),
                        "checkpoint validation must never hide a store read");
                restored.engine().prepareRootContext(session);
                assertEquals(0L, restored.fragmentStore().batchReadCount(),
                        "failed imports must not evict the valid warm context");
            }
        }
    }

    private static InMemoryCoordinationEnvironment environment(
            RepositoryIndependentCoordinationTestRuntime runtime,
            InMemoryCoordinationFragmentStore store) {
        return environment(runtime, store, null);
    }

    private static InMemoryCoordinationEnvironment environment(
            RepositoryIndependentCoordinationTestRuntime runtime,
            InMemoryCoordinationFragmentStore store,
            Integer rootViewCacheMaximumSize) {
        InMemoryCoordinationEnvironment.Builder builder =
                InMemoryCoordinationEnvironment.builder()
                        .contracts(runtime.contracts())
                        .documentProcessor(runtime.platformProcessor())
                        .fragmentStore(store)
                        .environmentIdentity(
                                "test:checkpoint-warm-restore");
        if (rootViewCacheMaximumSize != null) {
            builder.rootViewCacheMaximumSize(
                    rootViewCacheMaximumSize.intValue());
        }
        return builder.build();
    }

    private static InMemoryCoordinationEnvironment restoredEnvironment(
            RepositoryIndependentCoordinationTestRuntime runtime,
            InMemoryCoordinationCheckpoint checkpoint) {
        return restoredEnvironment(
                runtime,
                checkpoint,
                "test:checkpoint-warm-restore",
                ReferenceCutConfiguration.disabled());
    }

    private static InMemoryCoordinationEnvironment restoredEnvironment(
            RepositoryIndependentCoordinationTestRuntime runtime,
            InMemoryCoordinationCheckpoint checkpoint,
            String environmentIdentity,
            ReferenceCutConfiguration referenceCutConfiguration) {
        return restoredEnvironment(
                runtime,
                checkpoint,
                environmentIdentity,
                referenceCutConfiguration,
                null);
    }

    private static InMemoryCoordinationEnvironment restoredEnvironment(
            RepositoryIndependentCoordinationTestRuntime runtime,
            InMemoryCoordinationCheckpoint checkpoint,
            String environmentIdentity,
            ReferenceCutConfiguration referenceCutConfiguration,
            Integer rootViewCacheMaximumSize) {
        InMemoryCoordinationEnvironment.Builder builder =
                InMemoryCoordinationEnvironment.builder()
                .contracts(runtime.contracts())
                .documentProcessor(runtime.platformProcessor())
                .checkpoint(checkpoint)
                .environmentIdentity(environmentIdentity)
                .referenceCutConfiguration(referenceCutConfiguration);
        if (rootViewCacheMaximumSize != null) {
            builder.rootViewCacheMaximumSize(
                    rootViewCacheMaximumSize.intValue());
        }
        return builder.build();
    }

    private static void assertExactContextFallback(
            InMemoryCoordinationEnvironment restored,
            InMemoryCoordinationCheckpoint checkpoint,
            int expectedContexts) {
        assertEquals(0L,
                restored.engine().checkpointPreparedContextReuseCount());
        assertEquals(expectedContexts,
                restored.engine().checkpointPreparedContextFallbackCount());
        assertEquals(0L,
                restored.engine().checkpointPreparedContextRebuildCount());
        assertEquals(0L, restored.fragmentStore().batchReadCount(),
                "restore must not eagerly rebuild rejected acceleration");
        assertFalse(restored.engine().reusesReferenceCutCheckpointKernel(
                checkpoint.preparedRootState));
        assertFalse(restored.engine()
                .reusesPlanningProjectionCheckpointKernel(
                        checkpoint.preparedRootState));

        if (expectedContexts > 0) {
            ManagedDocumentSnapshot first =
                    restored.sessionStore().sessions().get(0);
            CoordinationRootViewCacheSnapshot before =
                    restored.rootViewCacheSnapshot();
            restored.engine().prepareRootContext(first);
            CoordinationRootViewCacheSnapshot after =
                    restored.rootViewCacheSnapshot();
            assertEquals(
                    before.hitCount() + before.missCount() + 1L,
                    after.hitCount() + after.missCount(),
                    "the first request must lazily build the missing context");
            long reads = restored.fragmentStore().batchReadCount();
            restored.engine().prepareRootContext(first);
            CoordinationRootViewCacheSnapshot repeated =
                    restored.rootViewCacheSnapshot();
            assertEquals(after.hitCount() + after.missCount(),
                    repeated.hitCount() + repeated.missCount(),
                    "the rebuilt context must satisfy the repeated request");
            assertEquals(reads, restored.fragmentStore().batchReadCount());
        }
    }

    private static ManagedDocumentSnapshot coldSession(
            InMemoryCoordinationEnvironment restored,
            InMemoryCoordinationCheckpoint checkpoint) {
        for (ManagedDocumentSnapshot session
                : restored.sessionStore().sessions()) {
            if (!restored.engine().reusesPreparedCheckpointContext(
                    session, checkpoint.preparedRootState)) {
                return session;
            }
        }
        throw new AssertionError("expected at least one cold restored session");
    }

    private static void assertBoundedRootCache(
            InMemoryCoordinationEnvironment environment,
            int expectedBound) {
        CoordinationRootViewCacheSnapshot snapshot =
                environment.rootViewCacheSnapshot();
        assertEquals(expectedBound, snapshot.maximumSize());
        assertTrue(snapshot.currentSize() <= expectedBound,
                "Root-view occupancy must remain within its hard bound");
    }

    private static InMemoryCoordinationCheckpoint withStorageGeneration(
            InMemoryCoordinationCheckpoint checkpoint,
            String storageGeneration) {
        return new InMemoryCoordinationCheckpoint(
                checkpoint.profileIdentity,
                checkpoint.immutableContentSharingToken,
                storageGeneration,
                checkpoint.preparedRepresentationStorageGenerationAuthority,
                checkpoint.fragments,
                checkpoint.fragmentHandles,
                checkpoint.fragmentEncodedSizes,
                checkpoint.fragmentWireFingerprints,
                checkpoint.processingViews,
                checkpoint.processingViewsByInventory,
                checkpoint.processingViewHandlesByInventory,
                checkpoint.processingViewEncodedSizesByInventory,
                checkpoint.processingViewWireFingerprintsByInventory,
                checkpoint.inventories,
                checkpoint.currentRootViews,
                checkpoint.preparedRootState,
                checkpoint.sessions,
                checkpoint.epochs,
                checkpoint.committedTransitions,
                checkpoint.rootOutboxes,
                checkpoint.terminalProgress,
                checkpoint.committedDeliveries,
                checkpoint.storedEvents,
                checkpoint.dispatchLedger,
                checkpoint.sessionSequence);
    }

    private static Node initializedRoot(
            RepositoryIndependentCoordinationTestRuntime runtime) {
        return initializedRoot(runtime, 0);
    }

    private static Node initializedRoot(
            RepositoryIndependentCoordinationTestRuntime runtime,
            int counter) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put(
                "timeline",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "timeline-a", "actor-a"));
        contracts.put(
                "workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        "timeline",
                        RepositoryIndependentCoordinationTypes
                                .updateDocumentStep(
                                        "/counter",
                                        new Node().value(7))));
        Node authored = new Node()
                .properties("counter", new Node().value(counter))
                .properties("contracts", new Node().properties(contracts));
        DocumentProcessingResult initialized =
                runtime.initializeDocument(authored);
        assertEquals(
                ProcessorStatus.SUCCESS,
                initialized.status(),
                ProcessingResultTestSupport.diagnosticMessage(initialized));
        return initialized.document();
    }

    /** Portable wrapper intentionally relying on the fail-closed SPI default. */
    private static final class AuthorityOmittingFragmentStore
            implements CoordinationFragmentStore {
        private final CoordinationFragmentStore delegate;

        private AuthorityOmittingFragmentStore(
                CoordinationFragmentStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public String fragmentationProfileIdentity() {
            return delegate.fragmentationProfileIdentity();
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return delegate.fetchByBlueId(blueId);
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            return delegate.fetchResultByBlueId(blueId);
        }

        @Override
        public Map<String, NodeProviderResult> readAll(
                Collection<String> blueIds) {
            return delegate.readAll(blueIds);
        }

        @Override
        public void putProcessingViews(
                Map<String, Node> exactProcessingViews) {
            delegate.putProcessingViews(exactProcessingViews);
        }

        @Override
        public void putProcessingViews(
                String inventoryIdentity,
                Map<String, Node> exactProcessingViews) {
            delegate.putProcessingViews(
                    inventoryIdentity, exactProcessingViews);
        }

        @Override
        public void putInventory(CoordinationFragmentInventory inventory) {
            delegate.putInventory(inventory);
        }

        @Override
        public CoordinationFragmentInventory requireInventory(
                String inventoryIdentity) {
            return delegate.requireInventory(inventoryIdentity);
        }

        @Override
        public Node read(String profileIdentity, String blueId) {
            return delegate.read(profileIdentity, blueId);
        }

        @Override
        public boolean putIfAbsent(
                String profileIdentity,
                String blueId,
                Node exactFragment) {
            return delegate.putIfAbsent(
                    profileIdentity, blueId, exactFragment);
        }

        @Override
        public boolean putAllIfAbsent(
                String profileIdentity,
                Map<String, Node> exactFragments) {
            return delegate.putAllIfAbsent(
                    profileIdentity, exactFragments);
        }
    }
}
