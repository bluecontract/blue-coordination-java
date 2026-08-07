package blue.coordination.engine.memory;

import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.coordination.processor.RepositoryIndependentCoordinationTestRuntime;
import blue.coordination.processor.RepositoryIndependentCoordinationTypes;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class InMemoryCoordinationCheckpointWarmRestoreTest {

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
        return InMemoryCoordinationEnvironment.builder()
                .contracts(runtime.contracts())
                .documentProcessor(runtime.platformProcessor())
                .fragmentStore(store)
                .environmentIdentity("test:checkpoint-warm-restore")
                .build();
    }

    private static InMemoryCoordinationEnvironment restoredEnvironment(
            RepositoryIndependentCoordinationTestRuntime runtime,
            InMemoryCoordinationCheckpoint checkpoint) {
        return InMemoryCoordinationEnvironment.builder()
                .contracts(runtime.contracts())
                .documentProcessor(runtime.platformProcessor())
                .checkpoint(checkpoint)
                .environmentIdentity("test:checkpoint-warm-restore")
                .build();
    }

    private static Node initializedRoot(
            RepositoryIndependentCoordinationTestRuntime runtime) {
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
                .properties("counter", new Node().value(0))
                .properties("contracts", new Node().properties(contracts));
        DocumentProcessingResult initialized =
                runtime.initializeDocument(authored);
        assertEquals(
                ProcessorStatus.SUCCESS,
                initialized.status(),
                ProcessingResultTestSupport.diagnosticMessage(initialized));
        return initialized.document();
    }
}
