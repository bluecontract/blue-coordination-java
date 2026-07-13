package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.snapshot.ResolvedSnapshot;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Scenario:
 * A main document dynamically creates embedded participant documents and then listens to those embedded
 * timelines through generated channels.
 *
 * Main flow:
 * 1. Alice calls {@code createEmbedded} five times.
 * 2. Each call adds one {@code /embedded_N} document, adds a simple timeline channel for it, and adds
 *    bridge/counter contracts that make the main document observe the embedded timeline.
 * 3. Each embedded participant calls {@code say}, which emits a chat message from the embedded document.
 * 4. The main document catches the embedded chat event, increments chat counters, and Bob calls
 *    {@code checkChatCount}.
 * 5. Bob's check sets {@code /success} once the main document has seen five chat messages.
 *
 * Actors and operations:
 * - Alice owns dynamic embedding through {@code createEmbedded}.
 * - Embedded participants own their own simple timeline {@code say} operations.
 * - Bob calls {@code checkChatCount} to mark success.
 * - All mutations are returned BEX Compute changesets applied through batch patches.
 */
class DynamicEmbeddedParticipantsWorkflowTest {
    private static final String DOCUMENT_RESOURCE =
            "coordination/compute/dynamic-embedded-participants-bex.yaml";
    private static final int EMBEDDED_PARTICIPANTS = 5;
    private static final int CHAT_MESSAGES = 5;

    @Test
    void aliceAddsEmbeddedParticipantDocumentsAndBobWaitsUntilMainDocumentCountsFiveChats() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder()
                        .processingMetrics(metrics)
                        .build());

        DocumentProcessingResult initialized = support.initialize(support.yamlResource(DOCUMENT_RESOURCE));
        ResolvedSnapshot current = initialized.snapshot();
        Node currentDocument = initialized.document();

        assertNotNull(currentDocument.getAsNode("/embeddedTemplate"));
        assertNotNull(currentDocument.getAsNode("/contractTemplates/embeddedTimeline"));
        assertNotNull(currentDocument.getAsNode("/contractTemplates/embeddedBridge"));
        assertNotNull(currentDocument.getAsNode("/contractTemplates/embeddedChatCounter"));
        assertFalse(currentDocument.getProperties().containsKey("embeddedTemplates"));

        for (int i = 1; i <= EMBEDDED_PARTICIPANTS; i++) {
            // Alice creates /embedded_i plus the root contracts that make this new document routable:
            // a simple timeline channel, an embedded-node bridge, a chat counter workflow, and a
            // composite-channel entry.
            DocumentProcessingResult result = support.blue.processDocument(current,
                    operationEvent(support, "alice", i, "createEmbedded"));
            assertFalse(result.capabilityFailure(), result.failureReason());
            current = result.snapshot();
            currentDocument = result.document();
        }

        assertEquals(BigInteger.valueOf(EMBEDDED_PARTICIPANTS), currentDocument.get("/nextEmbeddedNumber"));
        for (int i = 1; i <= EMBEDDED_PARTICIPANTS; i++) {
            assertEmbeddedParticipant(currentDocument, i);
            assertEquals("/embedded_" + i, currentDocument.get("/contracts/embeddedDocs/paths/" + (i - 1)));
            assertEquals("embedded_" + i + "_timeline",
                    currentDocument.get("/contracts/allEmbeddedTimelines/channels/" + (i - 1)));
            assertNotNull(currentDocument.getAsNode("/contracts/embedded_" + i + "_timeline"));
            assertNotNull(currentDocument.getAsNode("/contracts/embedded_" + i + "_bridge"));
            assertNotNull(currentDocument.getAsNode("/contracts/embedded_" + i + "_chatCounter"));
        }

        for (int i = 0; i < CHAT_MESSAGES; i++) {
            int participantNumber = i + 1;
            int timestamp = 10 + i;
            // The generated embedded participant calls its own say operation. That operation lives
            // inside /embedded_i and emits a chat message from the child document scope.
            DocumentProcessingResult chatResult = support.blue.processDocument(current,
                    operationEvent(support, "embedded-" + participantNumber, timestamp, "say"));
            assertFalse(chatResult.capabilityFailure(), chatResult.failureReason());
            current = chatResult.snapshot();
            currentDocument = chatResult.document();

            // Bob checks the root counter after each embedded chat. The check is intentionally a
            // separate operation so the test proves both automatic event counting and explicit user
            // operations can interact with the same state.
            DocumentProcessingResult bobCheck = support.blue.processDocument(current,
                    operationEvent(support, "bob", 100 + i, "checkChatCount"));
            assertFalse(bobCheck.capabilityFailure(), bobCheck.failureReason());
            current = bobCheck.snapshot();
            currentDocument = bobCheck.document();

            assertEquals(BigInteger.valueOf(i + 1), currentDocument.get("/chatMessagesSeen"));
            assertEquals(BigInteger.valueOf(i + 1), currentDocument.get("/embeddedTimelineEventsSeen"));
            assertEquals(Boolean.valueOf(i + 1 >= 5), currentDocument.get("/success"));
        }

        assertEquals(Boolean.TRUE, currentDocument.get("/success"));
        long expectedPatchApplications = EMBEDDED_PARTICIPANTS + (CHAT_MESSAGES * 3L);
        assertEquals(expectedPatchApplications, metrics.directBexChangesetHits(),
                "Every returned Compute changeset should use direct BEX changeset application");
        assertEquals(expectedPatchApplications, metrics.updateBatchPatchApplications(),
                "Alice creates, composite timeline counters, bridged chat counters, and Bob checks should batch apply");
        assertEquals(0L, metrics.updateIndividualPatchApplications());
    }

    private static void assertEmbeddedParticipant(Node document, int number) {
        String prefix = "/embedded_" + number;
        assertEquals("Embedded", document.get(prefix + "/name"));
        assertEquals("Embedded " + number, document.get(prefix + "/displayName"));
        assertEquals("embedded-" + number,
                document.get(prefix + "/contracts/participantChannel/timeline/timelineId"));
        assertEquals("embedded-" + number,
                document.get(prefix + "/contracts/participantChannel/actor/accountId"));
        assertNotNull(document.getAsNode(prefix + "/contracts/say"));
        assertNotNull(document.getAsNode(prefix + "/contracts/say"));
    }

    private static Node operationEvent(ComputeWorkflowTestSupport support,
                                       String timelineId,
                                       int timestamp,
                                       String operation) {
        return support.operationRequest(
                timelineId,
                timestamp,
                operation,
                new Node());
    }

}
