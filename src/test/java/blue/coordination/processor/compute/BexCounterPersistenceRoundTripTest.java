package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.BlueRepository;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Scenario:
 * A production-style counter document is initialized once, stored as canonical data, then reloaded for
 * every incoming event before being processed and stored again.
 *
 * Main flow:
 * 1. Initialize the BEX counter document and serialize the canonical result.
 * 2. Run 100 independent {@code increment} operation requests, each adding 1 to {@code /counter}.
 * 3. Before each increment, deserialize the previously stored canonical document and load a snapshot.
 * 4. After each increment, serialize the new canonical document for the next iteration.
 *
 * Actors and operations:
 * - The owner timeline calls {@code increment}.
 * - {@code Coordination/Compute} builds and applies the returned changeset.
 */
class BexCounterPersistenceRoundTripTest {
    private static final int ITERATIONS = 100;
    private static final String COUNTER_RESOURCE = "coordination/compute/bex-counter-persistence.yaml";

    @Test
    void shouldReloadCanonicalDocumentAcrossOneHundredBexIncrements() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        CoordinationProcessorOptions options = CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build();
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(options);

        long start = System.nanoTime();

        // When
        long initializeStart = System.nanoTime();
        DocumentProcessingResult initialized = support.initialize(support.yamlResource(COUNTER_RESOURCE));
        long initializeNanos = System.nanoTime() - initializeStart;

        // Initialization assertions
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(initialized), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(initialized));
        assertNotNull(blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                support.blue, initialized));

        long initialSerializeStart = System.nanoTime();
        String storedCanonicalJson = serializeCanonical(support, initialized);
        long initialSerializeNanos = System.nanoTime() - initialSerializeStart;
        String storedBlueId =
                blue.coordination.processor.ProcessingResultTestSupport.blueId(
                        initialized);
        assertNotNull(storedBlueId);

        long totalProcessNanos = 0L;
        long totalDeserializeAndLoadSnapshotNanos = 0L;
        long totalSerializeNanos = 0L;

        // Repeated cold reload and increment
        for (int i = 1; i <= ITERATIONS; i++) {
            ComputeWorkflowTestSupport coldSupport = ComputeWorkflowTestSupport.create(options);
            long loadStart = System.nanoTime();
            ResolvedSnapshot snapshot = deserializeCanonicalAndLoadSnapshot(
                    coldSupport, storedCanonicalJson);
            totalDeserializeAndLoadSnapshotNanos += System.nanoTime() - loadStart;

            // Reload assertion
            assertNotNull(snapshot.blueId(), "stored snapshot should load at iteration " + i);
            storedBlueId = snapshot.blueId();

            long processStart = System.nanoTime();
            DocumentProcessingResult result = coldSupport.blue.processDocument(snapshot,
                    operationRequest(coldSupport.blue, coldSupport.repository, i));
            totalProcessNanos += System.nanoTime() - processStart;

            // Increment assertions
            assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
            assertNotNull(
                    blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                            coldSupport.blue, result),
                    "iteration " + i + " should return a snapshot");
            assertEquals(BigInteger.valueOf(i),
                    blue.coordination.processor.ProcessingResultTestSupport
                            .resolvedDocument(coldSupport.blue, result)
                            .get("/counter"));

            long serializeStart = System.nanoTime();
            storedCanonicalJson = serializeCanonical(coldSupport, result);
            storedBlueId =
                    blue.coordination.processor.ProcessingResultTestSupport.blueId(
                            result);
            totalSerializeNanos += System.nanoTime() - serializeStart;
        }

        long totalNanos = System.nanoTime() - start;
        ResolvedSnapshot finalSnapshot = deserializeCanonicalAndLoadSnapshot(
                ComputeWorkflowTestSupport.create(options), storedCanonicalJson);

        // Then
        assertEquals(BigInteger.valueOf(ITERATIONS), finalSnapshot.resolvedNodeAt("/counter").getValue());
        assertEquals(ITERATIONS, metrics.updateBatchPatchApplications());
        assertEquals(ITERATIONS, metrics.directBexChangesetHits());
        assertEquals(0L, metrics.updateIndividualPatchApplications());

        System.out.printf("BEX counter persistence round trip - iterations=%d, finalBlueId=%s, totalMs=%.3f, "
                        + "initializeMs=%.3f, initialSerializeMs=%.3f, deserializeLoadSnapshotMs=%.3f, "
                        + "processMs=%.3f, serializeMs=%.3f, "
                        + "batchPatchApplications=%d, bundleCacheHits=%d, bundleCacheMisses=%d%n",
                ITERATIONS,
                storedBlueId,
                nanosToMs(totalNanos),
                nanosToMs(initializeNanos),
                nanosToMs(initialSerializeNanos),
                nanosToMs(totalDeserializeAndLoadSnapshotNanos),
                nanosToMs(totalProcessNanos),
                nanosToMs(totalSerializeNanos),
                metrics.updateBatchPatchApplications(),
                metrics.bundleLoadCacheHits(),
                metrics.bundleLoadCacheMisses());
    }

    private static String serializeCanonical(ComputeWorkflowTestSupport support, DocumentProcessingResult result) {
        assertNotNull(result.document());
        return support.blue.nodeToJson(result.document());
    }

    private static ResolvedSnapshot deserializeCanonicalAndLoadSnapshot(ComputeWorkflowTestSupport support,
            String storedCanonicalJson) {
        Node storedCanonical = support.blue.parseSourceJson(storedCanonicalJson);
        // Canonical Identity Input is not a Resolved View. Reload it through the
        // Language resolver so context-derived types are restored before processing.
        return support.blue.loadSnapshot(storedCanonical);
    }

    private static Node operationRequest(Blue blue,
                                         BlueRepository repository,
                                         int timestamp) {
        Node message = new Node()
                .type("Coordination/Operation Request")
                .properties("operation", new Node().value("increment"))
                .properties("channel", new Node().value("ownerChannel"))
                .properties("request", new Node().value(1));
        Node event = new Node()
                .type("Coordination/Timeline Entry")
                .properties("timeline", timeline())
                .properties("actor", principalActor())
                .properties("timestamp", new Node().value(BigInteger.valueOf(timestamp)))
                .properties("message", message)
                .blue(repository.typeAliasBlue());
        return blue.preprocess(event).blue(null);
    }

    private static Node timeline() {
        return new Node()
                .type("Coordination/Timeline")
                .properties("providerId", new Node().value("test-provider"))
                .properties("timelineId", new Node().value("owner"));
    }

    private static Node principalActor() {
        return new Node().type("Coordination/Principal Actor");
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }
}
