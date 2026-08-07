package blue.coordination.processor;

import blue.bex.api.BexEngine;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.BlueContracts;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasScheduleConstants;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ProcessingConformanceTrace;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessingTraceConstants;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.repo.BlueRepository;
import blue.repo.coordination.Compute;
import blue.repo.coordination.Event;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TriggerEvent;
import blue.repo.coordination.UpdateDocument;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end safety coverage for Coordination workflows that would otherwise
 * keep the generic Contracts reaction engine live indefinitely.
 *
 * <p>The fixtures use an exact test-only external Channel and verified
 * delivery evidence. All subsequent work is performed by generated
 * Coordination workflow types and the real Contracts event/update/embedded
 * routing machinery.</p>
 */
final class CoordinationInfiniteLoopSafetyTest {

    private static final String EXACT_CHANNEL_BLUE_ID =
            "BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L";
    private static final String EXACT_CHANNEL_KEY = "incoming";
    private static final String EXACT_CHANNEL_DISCRIMINATOR =
            "coordination-loop-safety";
    private static final String LOGICAL_SOURCE_A_KEY = "source-a";
    private static final String LOGICAL_SOURCE_B_KEY = "source-b";
    private static final String LOGICAL_TARGET_KEY = "target";
    private static final String SHARED_LOGICAL_DELIVERY_KEY =
            "shared-loop-delivery";
    private static final long LOOP_GAS_LIMIT = 6_000L;
    private static final long FULL_GAS_LIMIT = 100_000L;
    private static final int EVIDENCE_GAS_PREFIX = 32;
    private static final int EVIDENCE_RECORD_PREFIX = 24;
    private static final Map<String, LoopEvidence> LOOP_EVIDENCE =
            new TreeMap<String, LoopEvidence>();

    @Test
    void shouldStopTriggeredEventSelfLoopAtLiveGasAndRollbackDeterministically() {
        // given
        Harness harness = new Harness();
        Node input = harness.initialize(harness.triggeredEventLoopDocument());
        Node event = externalEvent("/", "triggered-event-loop");

        // when
        ProcessingDebugResult first =
                harness.process(input, event, LOOP_GAS_LIMIT);
        ProcessingDebugResult replay =
                harness.process(input, event, LOOP_GAS_LIMIT);

        // then
        assertGasRollbackAndDeterministicTrace(
                "triggered-event-self-loop",
                input,
                LOOP_GAS_LIMIT,
                first,
                replay);
        assertTrue(counterQuantity(
                        first.trace(),
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .TRIGGERED_EVENT_DELIVERED) >= 2L,
                "the admitted trace must prove repeated Triggered Event delivery");
        assertTrue(records(first.trace(), ProcessingTraceRecord.Kind.EVENT_DEQUEUED) >= 2,
                "the invocation FIFO must dequeue the repeating events");
    }

    @Test
    void shouldStopDocumentUpdateSelfLoopAtLiveGasAndRollbackDeterministically() {
        // given
        Harness harness = new Harness();
        Node input = harness.initialize(harness.documentUpdateLoopDocument());
        Node event = externalEvent("/", "document-update-loop");

        // when
        ProcessingDebugResult first =
                harness.process(input, event, LOOP_GAS_LIMIT);
        ProcessingDebugResult replay =
                harness.process(input, event, LOOP_GAS_LIMIT);

        // then
        assertGasRollbackAndDeterministicTrace(
                "document-update-self-loop",
                input,
                LOOP_GAS_LIMIT,
                first,
                replay);
        assertTrue(counterQuantity(
                        first.trace(),
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .DOCUMENT_UPDATE_DELIVERED) >= 2L,
                "the admitted trace must prove a live Document Update cascade");
        assertTrue(records(first.trace(), ProcessingTraceRecord.Kind.DOCUMENT_UPDATE) >= 2,
                "the trace must retain repeated update construction/delivery");
    }

    @Test
    void shouldStopCrossScopeUpdateEventLoopAtLiveGasAndRollbackDeterministically() {
        // given
        Harness harness = new Harness();
        Node input = harness.initialize(harness.crossScopeUpdateEventLoopDocument());
        Node event = externalEvent("/child", "cross-scope-update-event-loop");

        // when
        ProcessingDebugResult first =
                harness.process(input, event, LOOP_GAS_LIMIT);
        ProcessingDebugResult replay =
                harness.process(input, event, LOOP_GAS_LIMIT);

        // then
        assertGasRollbackAndDeterministicTrace(
                "cross-scope-update-event-loop",
                input,
                LOOP_GAS_LIMIT,
                first,
                replay);
        assertTrue(counterQuantity(
                        first.trace(),
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_EVENT_DELIVERED) >= 2L,
                "repeating child emissions must cross the Embedded Node Channel");
        assertTrue(counterQuantity(
                        first.trace(),
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .DOCUMENT_UPDATE_DELIVERED) >= 4L,
                "the rooted loop must retain repeated child and ancestor updates");
        assertTrue(hasHandlerExecution(first.trace(), "/child", "childSeed"),
                "the child must seed the reaction");
        assertTrue(hasHandlerExecution(
                        first.trace(), "/child", "childUpdateToEvent"),
                "a child update must emit the next child event");
        assertTrue(hasHandlerExecution(
                        first.trace(), "/child", "childEventToUpdate"),
                "a child event must perform the next child update");
        assertTrue(hasHandlerExecution(first.trace(), "/", "ancestorRecord"),
                "the ancestor must update its own state for every child event");
        assertTrue(recordsAtScope(
                        first.trace(),
                        ProcessingTraceRecord.Kind.DOCUMENT_UPDATE,
                        "/child") >= 2,
                "the admitted trace must retain repeated child updates");
        assertTrue(recordsAtScope(
                        first.trace(),
                        ProcessingTraceRecord.Kind.DOCUMENT_UPDATE,
                        "/") >= 2,
                "the admitted trace must retain repeated ancestor updates");
    }

    @Test
    void shouldStopEmbeddedChildAncestorEventLoopAtLiveGasAndRollbackDeterministically() {
        // given
        Harness harness = new Harness();
        Node input = harness.initialize(
                harness.embeddedChildAncestorEventLoopDocument());
        Node event = externalEvent(
                "/child", "embedded-child-ancestor-event-loop");

        // when
        ProcessingDebugResult first =
                harness.process(input, event, LOOP_GAS_LIMIT);
        ProcessingDebugResult replay =
                harness.process(input, event, LOOP_GAS_LIMIT);

        // then
        assertGasRollbackAndDeterministicTrace(
                "embedded-child-ancestor-event-loop",
                input,
                LOOP_GAS_LIMIT,
                first,
                replay);
        assertTrue(counterQuantity(
                        first.trace(),
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .TRIGGERED_EVENT_DELIVERED) >= 2L,
                "the child Triggered channel must repeat the event locally");
        assertTrue(counterQuantity(
                        first.trace(),
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_EVENT_DELIVERED) >= 2L,
                "the ancestor must receive every repeating child event");
        assertTrue(handlerExecutions(
                        first.trace(), "/child", "childRepeat") >= 2,
                "the admitted trace must retain repeated child handlers");
        assertTrue(handlerExecutions(
                        first.trace(), "/", "ancestorObserve") >= 2,
                "the admitted trace must retain repeated ancestor observation");
        assertEquals(0,
                records(
                        first.trace(),
                        ProcessingTraceRecord.Kind.DOCUMENT_UPDATE),
                "the embedded child/ancestor case must remain a pure event loop");
    }

    @Test
    void shouldStopNestedComputeEventLoopAtLiveGasAndRollbackDeterministically() {
        // given
        Harness harness = new Harness();
        Node input = harness.initialize(harness.nestedComputeEventLoopDocument());
        Node event = externalEvent("/", "nested-compute-event-loop");

        // when
        ProcessingDebugResult first =
                harness.process(input, event, LOOP_GAS_LIMIT);
        ProcessingDebugResult replay =
                harness.process(input, event, LOOP_GAS_LIMIT);

        // then
        assertGasRollbackAndDeterministicTrace(
                "nested-compute-event-loop",
                input,
                LOOP_GAS_LIMIT,
                first,
                replay);
        assertTrue(counterQuantity(
                        first.trace(),
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .TRIGGERED_EVENT_DELIVERED) >= 2L,
                "Compute emissions must repeatedly re-enter Triggered delivery");
        assertTrue(records(
                        first.trace(),
                        ProcessingTraceRecord.Kind.EVENT_DEQUEUED) >= 2,
                "the invocation FIFO must dequeue repeated Compute emissions");
        assertTrue(hasHandlerExecution(first.trace(), "/", "repeatCompute"),
                "a Compute emission must re-enter the Compute workflow");
        assertTrue(counterQuantityByPrefix(
                        first.trace(),
                        "coordination.",
                        "workflowStepExecuted") >= 3L,
                "the seed and repeated Compute steps must execute");
        assertTrue(counterQuantityByPrefix(
                        first.trace(),
                        "bex.workflow.",
                        "functionCalled") >= 3L,
                "every nested re-entry must execute through the hosted BEX ledger");
    }

    @Test
    void shouldShareGasAcrossCoalescedMultiSourceLogicalDeliveryAndRollbackDeterministically() {
        // given
        Harness harness = new Harness();
        Node input = harness.initialize(
                harness.multiSourceLogicalDeliveryLoopDocument());
        Node event = externalEvent(
                "/", "multi-source-logical-delivery-loop");

        // when
        ProcessingDebugResult first =
                harness.processWithCurrentRootPlan(
                        input, event, LOOP_GAS_LIMIT);
        ProcessingDebugResult replay =
                harness.processWithCurrentRootPlan(
                        input, event, LOOP_GAS_LIMIT);

        // then
        assertGasRollbackAndDeterministicTrace(
                "multi-source-logical-delivery-loop",
                input,
                LOOP_GAS_LIMIT,
                first,
                replay);
        assertEquals(
                2L,
                counterQuantity(
                        first.trace(),
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .CHANNEL_ACCEPTED),
                "both raw sources must share the one invocation gas ledger");
        assertEquals(
                2,
                records(
                        first.trace(),
                        ProcessingTraceRecord.Kind
                                .EXTERNAL_DELIVERY));
        List<ProcessingTraceRecord> logicalGroups =
                first.trace().records(
                        ProcessingTraceRecord.Kind
                                .LOGICAL_DELIVERY_GROUP);
        assertEquals(1, logicalGroups.size());
        ProcessingTraceRecord group = logicalGroups.get(0);
        assertEquals(LOGICAL_TARGET_KEY, group.contractKey());
        assertEquals(
                SHARED_LOGICAL_DELIVERY_KEY,
                group.logicalPath());
        assertEquals(
                "2",
                group.details().get(
                        ProcessingTraceConstants
                                .FIELD_SOURCE_COUNT));
        assertEquals(
                LOGICAL_SOURCE_A_KEY,
                group.details().get(
                        ProcessingTraceConstants
                                .sourceField(0)));
        assertEquals(
                LOGICAL_SOURCE_B_KEY,
                group.details().get(
                        ProcessingTraceConstants
                                .sourceField(1)));
        assertEquals(
                1,
                handlerExecutions(
                        first.trace(), "/", "seed"),
                "coalesced sources must invoke the routed target once");
        assertTrue(
                handlerExecutions(
                        first.trace(), "/", "repeat") >= 2,
                "the routed target must enter the repeating event loop");
        assertEquals(
                0,
                records(
                        first.trace(),
                        ProcessingTraceRecord.Kind
                                .CHECKPOINT_WRITE),
                "neither participating source checkpoint may commit");
    }

    @Test
    void shouldStopLargeFiniteBexIterationAtExactParentChildBudgetPrefix() {
        // given
        Harness harness = new Harness();
        final int itemCount = 48;
        Node input = harness.initialize(
                harness.largeFiniteBexDocument(itemCount));
        Node event = externalEvent("/", "large-finite-bex");
        ProcessingDebugResult successful =
                harness.process(input, event, FULL_GAS_LIMIT);
        assertEquals(ProcessorStatus.SUCCESS,
                successful.processResult().status(),
                ProcessingResultTestSupport.diagnosticMessage(
                        successful.processResult()));
        assertEquals(itemCount,
                counterQuantityByPrefix(
                        successful.trace(),
                        "bex.workflow.",
                        "collectionItemVisited"));
        int rejectedIndex = nthGasIndex(
                successful.trace(),
                GasScheduleConstants.Namespace.PROCESSOR,
                GasScheduleConstants.ProcessorCounter
                        .CHECKPOINT_WRITTEN,
                1);
        assertTrue(rejectedIndex > 0,
                "the successful control must reach the source checkpoint");
        long exactPrefixBudget =
                admittedGasBefore(successful.trace(), rejectedIndex);

        // when
        ProcessingDebugResult first =
                harness.process(input, event, exactPrefixBudget);
        ProcessingDebugResult replay =
                harness.process(input, event, exactPrefixBudget);

        // then
        assertGasRollbackAndDeterministicTrace(
                "large-finite-bex-parent-child-budget",
                input,
                exactPrefixBudget,
                first,
                replay);
        assertEquals(
                GasScheduleConstants.ProcessorCounter.CHECKPOINT_WRITTEN,
                first.processResult().diagnostic().details().get("counter"));
        assertEquals(itemCount,
                counterQuantityByPrefix(
                        first.trace(),
                        "bex.workflow.",
                        "collectionItemVisited"),
                "the complete finite iteration must be admitted before parent exhaustion");
        assertEquals(
                gasProjection(successful.trace()).subList(0, rejectedIndex),
                gasProjection(first.trace()),
                "the rejected parent charge must be absent after merging child gas");
        assertTrue(isPrefix(
                        recordProjection(first.trace()),
                        recordProjection(successful.trace())),
                "the failed reaction record must be an exact successful prefix");
    }

    @Test
    void shouldMapParentBoundBexExhaustionToGasLimitExceeded() {
        // given
        Harness harness = new Harness();
        final int itemCount = 48;
        Node input = harness.initialize(
                harness.largeFiniteBexDocument(itemCount));
        Node event = externalEvent(
                "/", "parent-bound-bex-exhaustion");
        ProcessingDebugResult successful =
                harness.process(input, event, FULL_GAS_LIMIT);
        assertEquals(ProcessorStatus.SUCCESS,
                successful.processResult().status(),
                ProcessingResultTestSupport.diagnosticMessage(
                        successful.processResult()));
        int rejectedIndex = nthGasIndex(
                successful.trace(),
                "bex.workflow.",
                "collectionItemVisited",
                10);
        assertTrue(rejectedIndex > 0,
                "the successful control must visit at least ten BEX items");
        long exactPrefixBudget =
                admittedGasBefore(
                        successful.trace(), rejectedIndex);

        // when
        ProcessingDebugResult first =
                harness.process(
                        input, event, exactPrefixBudget);
        ProcessingDebugResult replay =
                harness.process(
                        input, event, exactPrefixBudget);

        // then
        assertGasRollbackAndDeterministicTrace(
                "parent-bound-bex-exhaustion",
                input,
                exactPrefixBudget,
                first,
                replay);
        long admittedItems = counterQuantityByPrefix(
                first.trace(),
                "bex.workflow.",
                "collectionItemVisited");
        assertTrue(admittedItems > 0L
                        && admittedItems < itemCount,
                "the parent budget must stop BEX after an admitted prefix");
        List<String> successfulBexGas =
                gasProjectionByNamespacePrefix(
                        successful.trace(),
                        "bex.workflow.");
        List<String> rejectedBexGas =
                gasProjectionByNamespacePrefix(
                        first.trace(),
                        "bex.workflow.");
        assertTrue(rejectedBexGas.size()
                        < successfulBexGas.size(),
                "parent exhaustion must truncate the BEX child trace");
        assertEquals(
                successfulBexGas.subList(
                        0, rejectedBexGas.size()),
                rejectedBexGas,
                "the over-budget BEX charge must be absent");
    }

    @Test
    void shouldRejectRecursiveBexCompilationBeforeAnyEffectCommits() {
        // given
        Harness harness = new Harness();
        Node input = harness.initialize(harness.recursiveBexDocument());
        Node event = externalEvent("/", "recursive-bex");

        // when
        ProcessingDebugResult first =
                harness.process(input, event, FULL_GAS_LIMIT);
        ProcessingDebugResult replay =
                harness.process(input, event, FULL_GAS_LIMIT);

        // then
        DocumentProcessingResult result = first.processResult();
        String diagnostic = ProcessingResultTestSupport.diagnosticMessage(result);
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), diagnostic);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                ProcessingResultTestSupport.diagnosticCategory(result));
        assertTrue(diagnostic.toLowerCase(java.util.Locale.ROOT).contains("recursive"),
                diagnostic);
        assertNonCommittingExactRoot(input, result);
        assertEquals(gasProjection(first.trace()), gasProjection(replay.trace()));
        assertEquals(recordProjection(first.trace()), recordProjection(replay.trace()));
        assertEquals(result.diagnostic().details(),
                replay.processResult().diagnostic().details());
    }

    @Test
    void shouldCompleteRepresentativeLargeFiniteSequentialWorkflowBelowPortableLimit() {
        // given
        final int stepCount = 64;
        Harness harness = new Harness();
        Node input = harness.initialize(
                harness.largeFiniteSequentialWorkflowDocument(stepCount));
        Node event = externalEvent("/", "large-finite-workflow");

        // when
        ProcessingDebugResult first =
                harness.process(input, event, FULL_GAS_LIMIT);
        ProcessingDebugResult replay =
                harness.process(input, event, FULL_GAS_LIMIT);

        // then
        DocumentProcessingResult result = first.processResult();
        assertTrue(stepCount < CoordinationRuntimeLimits.MAX_WORKFLOW_STEPS);
        assertEquals(ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertTrue(result.commits());
        assertEquals(BigInteger.valueOf(stepCount), result.document().get("/counter"));
        assertTrue(result.events().isEmpty());
        assertEquals(stepCount,
                counterQuantityByPrefix(
                        first.trace(),
                        "coordination.",
                        "workflowStepExecuted"));
        assertEquals(first.processResult().totalGas(),
                replay.processResult().totalGas());
        assertEquals(gasProjection(first.trace()), gasProjection(replay.trace()));
        assertEquals(recordProjection(first.trace()), recordProjection(replay.trace()));
    }

    @AfterAll
    static void shouldWriteDeterministicExecutableLoopEvidence() throws IOException {
        if (LOOP_EVIDENCE.isEmpty()) {
            /*
             * Every scenario test already reports its primary setup/runtime
             * failure.  Do not add a derivative evidence-count failure when
             * an immutable upstream dependency prevents all scenarios from
             * reaching the evidence recorder.
             */
            return;
        }
        String reportPath =
                System.getProperty(
                        "coordination.loop.report");
        byte[] evidence =
                loopEvidenceJson().getBytes(
                        StandardCharsets.UTF_8);

        Path report = reportPath == null
                ? null
                : Paths.get(reportPath);
        if (report != null) {
            Files.createDirectories(report.getParent());
            Files.write(report, evidence);
        }

        assertEquals(8, LOOP_EVIDENCE.size());
        assertTrue(evidence.length > 0);
        if (report != null) {
            assertTrue(Files.isRegularFile(report));
            assertTrue(Files.size(report) > 0L);
        }
    }

    private static void assertGasRollbackAndDeterministicTrace(
            String caseName,
            Node input,
            long gasLimit,
            ProcessingDebugResult first,
            ProcessingDebugResult replay) {
        DocumentProcessingResult result = first.processResult();
        assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result)
                        + "\npublicEvents="
                        + result.events()
                        + "\nrecords="
                        + recordProjection(first.trace())
                        + "\ngas="
                        + gasProjection(first.trace()));
        assertEquals(ProcessorErrorCategory.GasLimitExceeded,
                ProcessingResultTestSupport.diagnosticCategory(result));
        assertNonCommittingExactRoot(input, result);
        assertTrue(result.totalGas() <= gasLimit,
                "only admitted gas may contribute to the terminal total");
        assertEquals(result.totalGas(), admittedGas(first.trace()),
                "the rejected charge must be absent from the canonical trace");
        assertConsecutiveGasSequence(first.trace());
        assertEquals(result.status(), replay.processResult().status());
        assertEquals(result.diagnostic().details(),
                replay.processResult().diagnostic().details());
        assertEquals(result.totalGas(), replay.processResult().totalGas());
        assertEquals(gasProjection(first.trace()), gasProjection(replay.trace()));
        assertEquals(recordProjection(first.trace()), recordProjection(replay.trace()));
        assertEquals(first.trace().semanticDemands(), replay.trace().semanticDemands());
        assertTrue(hasNoWorkAfterRejection(
                        input,
                        gasLimit,
                        first,
                        replay),
                "the rejected charge must be the terminal observable boundary");
        synchronized (LOOP_EVIDENCE) {
            LOOP_EVIDENCE.put(caseName,
                    LoopEvidence.from(
                            caseName,
                            input,
                            first,
                            replay,
                            gasLimit));
        }
    }

    private static boolean hasNoWorkAfterRejection(
            Node input,
            long gasLimit,
            ProcessingDebugResult first,
            ProcessingDebugResult replay) {
        DocumentProcessingResult firstResult = first.processResult();
        DocumentProcessingResult replayResult = replay.processResult();
        return firstResult.status() == ProcessorStatus.GAS_LIMIT_EXCEEDED
                && replayResult.status() == ProcessorStatus.GAS_LIMIT_EXCEEDED
                && !firstResult.commits()
                && !replayResult.commits()
                && input.toString().equals(firstResult.document().toString())
                && input.toString().equals(replayResult.document().toString())
                && firstResult.events().isEmpty()
                && replayResult.events().isEmpty()
                && firstResult.totalGas() <= gasLimit
                && replayResult.totalGas() <= gasLimit
                && firstResult.totalGas() == admittedGas(first.trace())
                && replayResult.totalGas() == admittedGas(replay.trace())
                && firstResult.totalGas() == replayResult.totalGas()
                && java.util.Objects.equals(
                        firstResult.diagnostic().details(),
                        replayResult.diagnostic().details())
                && gasProjection(first.trace()).equals(
                        gasProjection(replay.trace()))
                && recordProjection(first.trace()).equals(
                        recordProjection(replay.trace()))
                && first.trace().semanticDemands().equals(
                        replay.trace().semanticDemands());
    }

    private static void assertNonCommittingExactRoot(
            Node input,
            DocumentProcessingResult result) {
        assertFalse(result.commits());
        assertEquals(input.toString(),
                result.document().toString(),
                "failure must return the exact input Root");
        assertEquals(DirectBlueIdCalculator.calculateBlueId(input),
                DirectBlueIdCalculator.calculateBlueId(result.document()));
        assertTrue(result.events().isEmpty(),
                "tentative Root events must be discarded");
        assertNull(nodeOrNull(input, "/contracts/checkpoint"),
                "the initialized fixture must not pre-author a checkpoint");
        assertNull(nodeOrNull(result.document(), "/contracts/checkpoint"),
                "the source checkpoint must not commit on failure");
    }

    private static long admittedGas(ProcessingConformanceTrace trace) {
        long total = 0L;
        for (GasTraceEntry entry : trace.gas()) {
            total = Math.addExact(total, entry.subtotal());
        }
        return total;
    }

    private static long admittedGasBefore(
            ProcessingConformanceTrace trace,
            int index) {
        long total = 0L;
        for (int current = 0; current < index; current++) {
            total = Math.addExact(total, trace.gas().get(current).subtotal());
        }
        return total;
    }

    private static void assertConsecutiveGasSequence(
            ProcessingConformanceTrace trace) {
        for (int index = 0; index < trace.gas().size(); index++) {
            assertEquals(index, trace.gas().get(index).sequence(),
                    "gas entries must contain only consecutively admitted charges");
        }
    }

    private static long counterQuantity(
            ProcessingConformanceTrace trace,
            String namespace,
            String counter) {
        long quantity = 0L;
        for (GasTraceEntry entry : trace.gas()) {
            if (namespace.equals(entry.namespace())
                    && counter.equals(entry.counter())) {
                quantity += entry.quantity();
            }
        }
        return quantity;
    }

    private static long counterQuantityByPrefix(
            ProcessingConformanceTrace trace,
            String namespacePrefix,
            String counter) {
        long quantity = 0L;
        for (GasTraceEntry entry : trace.gas()) {
            if (entry.namespace().startsWith(namespacePrefix)
                    && counter.equals(entry.counter())) {
                quantity += entry.quantity();
            }
        }
        return quantity;
    }

    private static int nthGasIndex(
            ProcessingConformanceTrace trace,
            String namespacePrefix,
            String counter,
            int occurrence) {
        int seen = 0;
        for (int index = 0; index < trace.gas().size(); index++) {
            GasTraceEntry entry = trace.gas().get(index);
            if (entry.namespace().startsWith(namespacePrefix)
                    && counter.equals(entry.counter())) {
                seen++;
                if (seen == occurrence) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static int records(
            ProcessingConformanceTrace trace,
            ProcessingTraceRecord.Kind kind) {
        int count = 0;
        for (ProcessingTraceRecord record : trace.records()) {
            if (kind == record.kind()) {
                count++;
            }
        }
        return count;
    }

    private static int recordsAtScope(
            ProcessingConformanceTrace trace,
            ProcessingTraceRecord.Kind kind,
            String scopePath) {
        int count = 0;
        for (ProcessingTraceRecord record : trace.records()) {
            if (kind == record.kind()
                    && scopePath.equals(record.scopePath())) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasHandlerExecution(
            ProcessingConformanceTrace trace,
            String scopePath,
            String contractKey) {
        return handlerExecutions(
                trace, scopePath, contractKey) > 0;
    }

    private static int handlerExecutions(
            ProcessingConformanceTrace trace,
            String scopePath,
            String contractKey) {
        int count = 0;
        for (ProcessingTraceRecord record : trace.records()) {
            if (record.kind() == ProcessingTraceRecord.Kind.HANDLER_EXECUTION
                    && scopePath.equals(record.scopePath())
                    && contractKey.equals(record.contractKey())) {
                count++;
            }
        }
        return count;
    }

    private static List<String> gasProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection = new ArrayList<String>();
        for (GasTraceEntry entry : trace.gas()) {
            projection.add(entry.sequence()
                    + "|" + entry.namespace()
                    + "|" + entry.counter()
                    + "|" + entry.quantity()
                    + "|" + entry.weight()
                    + "|" + entry.subtotal()
                    + "|" + entry.scopePath()
                    + "|" + entry.contractKey()
                    + "|" + entry.logicalPath()
                    + "|" + entry.reason());
        }
        return projection;
    }

    private static List<String> gasProjectionByNamespacePrefix(
            ProcessingConformanceTrace trace,
            String namespacePrefix) {
        List<String> projection = new ArrayList<String>();
        for (GasTraceEntry entry : trace.gas()) {
            if (entry.namespace().startsWith(namespacePrefix)) {
                projection.add(entry.namespace()
                        + "|" + entry.counter()
                        + "|" + entry.quantity()
                        + "|" + entry.weight()
                        + "|" + entry.subtotal()
                        + "|" + entry.scopePath()
                        + "|" + entry.contractKey()
                        + "|" + entry.logicalPath()
                        + "|" + entry.reason());
            }
        }
        return projection;
    }

    private static List<String> recordProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection = new ArrayList<String>();
        for (ProcessingTraceRecord record : trace.records()) {
            Node node = record.node();
            projection.add(record.sequence()
                    + "|" + record.kind()
                    + "|" + encodedRecordField(
                    record.scopePath())
                    + "|" + encodedRecordField(
                    record.contractKey())
                    + "|" + encodedRecordField(
                    record.logicalPath())
                    + "|" + encodedRecordField(
                    canonicalRecordDetails(
                            record.details()))
                    + "|" + (node != null
                    ? DirectBlueIdCalculator.calculateBlueId(
                    node)
                    : "~"));
        }
        return projection;
    }

    private static String canonicalRecordDetails(
            Map<String, String> details) {
        StringBuilder canonical =
                new StringBuilder();
        for (Map.Entry<String, String> entry
                : new TreeMap<String, String>(
                details).entrySet()) {
            appendLengthPrefixed(
                    canonical,
                    entry.getKey());
            appendLengthPrefixed(
                    canonical,
                    entry.getValue());
        }
        return canonical.toString();
    }

    private static void appendLengthPrefixed(
            StringBuilder destination,
            String value) {
        if (value == null) {
            destination.append("-1:");
            return;
        }
        destination.append(value.length())
                .append(':')
                .append(value);
    }

    private static String encodedRecordField(
            String value) {
        if (value == null) {
            return "~";
        }
        if (value.isEmpty()) {
            return ".";
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        value.getBytes(
                                StandardCharsets.UTF_8));
    }

    private static boolean isPrefix(
            List<String> prefix,
            List<String> complete) {
        return prefix.size() <= complete.size()
                && prefix.equals(complete.subList(0, prefix.size()));
    }

    private static Node nodeOrNull(Node root, String pointer) {
        try {
            return root.getNode(pointer);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Node externalEvent(String targetScope, String id) {
        return new Node()
                .properties("id", new Node().value(id))
                .properties("targetScope", new Node().value(targetScope))
                .properties("subscriptionKey",
                        new Node().value(EXACT_CHANNEL_KEY));
    }

    private static ExternalDeliveryPlan deliveryPlan(Node root, Node event) {
        String scopePath = event.getAsText("/targetScope");
        Node scope = "/".equals(scopePath)
                ? root
                : root.getNode(scopePath);
        Node channel = scope.getContracts().getProperties()
                .get(EXACT_CHANNEL_KEY);
        String contributionBlueId =
                DirectBlueIdCalculator.calculateBlueId(channel);
        String domainBlueId = CheckpointDomain.derive(
                EXACT_CHANNEL_BLUE_ID,
                Collections.singletonList(contributionBlueId),
                EXACT_CHANNEL_DISCRIMINATOR);
        String subjectBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder(
                                scopePath, EXACT_CHANNEL_KEY)
                        .sourceContribution(contributionBlueId)
                        .effectiveTypeBlueId(EXACT_CHANNEL_BLUE_ID)
                        .subscriptionKey(EXACT_CHANNEL_KEY)
                        .checkpointDomainBlueId(domainBlueId)
                        .checkpointSubjectBlueId(subjectBlueId)
                        .build();
        SubscriptionDelta.Entry active =
                new SubscriptionDelta.Entry(
                        scopePath,
                        EXACT_CHANNEL_KEY,
                        EXACT_CHANNEL_BLUE_ID,
                        Collections.singletonList(contributionBlueId),
                        0,
                        Collections.singletonList(EXACT_CHANNEL_KEY),
                        domainBlueId,
                        0L,
                        null,
                        null);
        return ExternalDeliveryPlan.builder()
                .revisions(0L, 0L)
                .eventOrderKey(ExternalOrderKey.of(
                        Collections.singletonList(subjectBlueId)))
                .delivery(delivery)
                .activeSubscriptionInterval(active)
                .exactRuntimeState()
                .build();
    }

    private static String loopEvidenceJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"schema\": \"coordination-loop-evidence/1.0\",\n")
                .append("  \"cases\": [");
        boolean first = true;
        synchronized (LOOP_EVIDENCE) {
            for (LoopEvidence evidence : LOOP_EVIDENCE.values()) {
                if (!first) {
                    json.append(',');
                }
                json.append("\n    ").append(evidence.toJson());
                first = false;
            }
        }
        json.append("\n  ]\n}\n");
        return json.toString();
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format(
                                java.util.Locale.ROOT,
                                "\\u%04x",
                                Integer.valueOf(character)));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.append('"').toString();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(jsonString(values.get(index)));
        }
        return json.append(']').toString();
    }

    private static List<String> prefix(List<String> values, int limit) {
        return new ArrayList<String>(
                values.subList(0, Math.min(values.size(), limit)));
    }

    private static final class LoopEvidence {
        private final String caseName;
        private final String status;
        private final long gasLimit;
        private final long totalGas;
        private final int gasEntryCount;
        private final int recordCount;
        private final List<String> gasPrefix;
        private final List<String> recordPrefix;
        private final boolean exactInputRoot;
        private final boolean eventsEmpty;
        private final boolean checkpointAbsent;
        private final boolean rejectedChargeAbsent;
        private final boolean noWorkAfterRejection;

        private LoopEvidence(
                String caseName,
                String status,
                long gasLimit,
                long totalGas,
                int gasEntryCount,
                int recordCount,
                List<String> gasPrefix,
                List<String> recordPrefix,
                boolean exactInputRoot,
                boolean eventsEmpty,
                boolean checkpointAbsent,
                boolean rejectedChargeAbsent,
                boolean noWorkAfterRejection) {
            this.caseName = caseName;
            this.status = status;
            this.gasLimit = gasLimit;
            this.totalGas = totalGas;
            this.gasEntryCount = gasEntryCount;
            this.recordCount = recordCount;
            this.gasPrefix = gasPrefix;
            this.recordPrefix = recordPrefix;
            this.exactInputRoot = exactInputRoot;
            this.eventsEmpty = eventsEmpty;
            this.checkpointAbsent = checkpointAbsent;
            this.rejectedChargeAbsent = rejectedChargeAbsent;
            this.noWorkAfterRejection = noWorkAfterRejection;
        }

        private static LoopEvidence from(
                String caseName,
                Node input,
                ProcessingDebugResult first,
                ProcessingDebugResult replay,
                long gasLimit) {
            DocumentProcessingResult result = first.processResult();
            return new LoopEvidence(
                    caseName,
                    result.status().name(),
                    gasLimit,
                    result.totalGas(),
                    first.trace().gas().size(),
                    first.trace().records().size(),
                    prefix(gasProjection(first.trace()), EVIDENCE_GAS_PREFIX),
                    prefix(recordProjection(first.trace()), EVIDENCE_RECORD_PREFIX),
                    input.toString().equals(result.document().toString()),
                    result.events().isEmpty(),
                    nodeOrNull(result.document(), "/contracts/checkpoint") == null,
                    result.totalGas() == admittedGas(first.trace())
                            && result.totalGas() <= gasLimit,
                    hasNoWorkAfterRejection(
                            input,
                            gasLimit,
                            first,
                            replay));
        }

        private String toJson() {
            return new StringBuilder()
                    .append("{\"case\":").append(jsonString(caseName))
                    .append(",\"status\":").append(jsonString(status))
                    .append(",\"gasLimit\":").append(gasLimit)
                    .append(",\"totalGas\":").append(totalGas)
                    .append(",\"gasEntryCount\":").append(gasEntryCount)
                    .append(",\"recordCount\":").append(recordCount)
                    .append(",\"gasPrefix\":").append(jsonArray(gasPrefix))
                    .append(",\"recordPrefix\":").append(jsonArray(recordPrefix))
                    .append(",\"rollback\":{\"exactInputRoot\":")
                    .append(exactInputRoot)
                    .append(",\"publicEventsEmpty\":").append(eventsEmpty)
                    .append(",\"checkpointAbsent\":").append(checkpointAbsent)
                    .append(",\"rejectedChargeAbsent\":")
                    .append(rejectedChargeAbsent)
                    .append(",\"noWorkAfterRejection\":")
                    .append(noWorkAfterRejection)
                    .append("}}")
                    .toString();
        }
    }

    private static final class Harness {
        private final CoordinationTestRuntime blue =
                CoordinationTestResources.configuredBlue(
                        BlueRepository.current());

        private Node initialize(Node authored) {
            DocumentProcessingResult initialized =
                    processor(FULL_GAS_LIMIT)
                            .initializeDocument(authored.clone());
            assertEquals(ProcessorStatus.SUCCESS,
                    initialized.status(),
                    ProcessingResultTestSupport.diagnosticMessage(initialized));
            assertNull(nodeOrNull(initialized.document(),
                    "/contracts/checkpoint"));
            return initialized.document();
        }

        private ProcessingDebugResult process(
                Node input,
                Node event,
                long gasLimit) {
            return processor(gasLimit)
                    .processDocumentWithTrace(
                            input.clone(), event.clone());
        }

        private ProcessingDebugResult
        processWithCurrentRootPlan(
                Node input,
                Node event,
                long gasLimit) {
            ExternalOrderKey order = ExternalOrderKey.of(
                    Collections.<Object>singletonList(
                            DirectBlueIdCalculator.calculateBlueId(event)));
            try (DocumentProcessor processor = processor(gasLimit);
                 BlueContracts contracts = BlueContracts.builder(
                                 blue.language().processing())
                         .runtimeRegistry(processor.administration()
                                 .contractRegistry())
                         .gasLimit(gasLimit)
                         .build()) {
                SubscriptionDelta initial = contracts
                        .subscriptionSurfaceProjection()
                        .projectInitial(
                                input,
                                0L,
                                ExternalOrderKey.of(
                                        Collections.emptyList()));
                try (DocumentProcessor compatibility =
                             DocumentProcessor.Builder.from(processor)
                                     .deliveryPlanDeriver(
                                             CoordinationDeliveryPlanning
                                                     .currentRootCompatibilityDeriver(
                                                             contracts,
                                                             0L,
                                                             order,
                                                             initial.added()))
                                     .build()) {
                    return compatibility.processDocumentWithTrace(
                            input.clone(), event.clone());
                }
            }
        }

        private DocumentProcessor processor(long gasLimit) {
            BexProcessingMetrics metrics =
                    new BexProcessingMetrics();
            CoordinationProcessorOptions options =
                    CoordinationProcessorOptions.builder()
                            .bexEngine(BexEngine.builder()
                                    .intrinsics(
                                            CoordinationBexIntrinsics.common())
                                    .build())
                            .defaultComputeGasLimit(FULL_GAS_LIMIT)
                            .processingMetrics(metrics)
                            .build();
            DocumentProcessor.Builder builder =
                    DocumentProcessor.builder()
                            .gasLimit(gasLimit)
                            .snapshotStore(
                                    new ExactSnapshotManager())
                            .matchingService(
                                    new ContractMatchingService(
                                            blue.language()
                                                    .processing()
                                                    .runtimeAccess()))
                            .deliveryPlanDeriver(
                                    CoordinationInfiniteLoopSafetyTest
                                            ::deliveryPlan);
            CoordinationProcessors.configure(builder, options);
            return builder
                    .registerContractProcessor(
                            new ExactChannelProcessor())
                    .build();
        }

        private Node triggeredEventLoopDocument() {
            Node repeatingEvent = coordinationEvent("trigger-loop");
            Map<String, Node> contracts =
                    rootContracts();
            contracts.put("loopEvents",
                    triggeredEventChannel(
                            repeatingEvent.clone()));
            contracts.put("seed",
                    workflow(EXACT_CHANNEL_KEY,
                            null,
                            new TriggerEvent()
                                    .event(repeatingEvent.clone())));
            contracts.put("repeat",
                    workflow("loopEvents",
                            repeatingEvent.clone(),
                            new TriggerEvent()
                                    .event(repeatingEvent.clone())));
            return document("Triggered Event self-loop", contracts);
        }

        private Node documentUpdateLoopDocument() {
            Map<String, Node> contracts =
                    rootContracts();
            contracts.put("updates",
                    documentUpdateChannel("/items"));
            contracts.put("seed",
                    workflow(EXACT_CHANNEL_KEY,
                            null,
                            appendItem("seed")));
            contracts.put("repeat",
                    workflow("updates",
                            null,
                            appendItem("repeat")));
            return document("Document Update self-loop", contracts)
                    .properties("items",
                            new Node().items(
                                    Collections.<Node>emptyList()));
        }

        private Node crossScopeUpdateEventLoopDocument() {
            Node childEvent = coordinationEvent("child-loop");
            Map<String, Node> childContracts =
                    rootContracts();
            childContracts.put("childUpdates",
                    documentUpdateChannel("/items"));
            childContracts.put("childEvents",
                    triggeredEventChannel(
                            childEvent.clone()));
            childContracts.put("childSeed",
                    workflow(EXACT_CHANNEL_KEY,
                            null,
                            appendItem(
                                    "/items/-",
                                    "seed")));
            childContracts.put("childUpdateToEvent",
                    workflow("childUpdates",
                            null,
                            new TriggerEvent()
                                    .event(childEvent.clone())));
            childContracts.put("childEventToUpdate",
                    workflow("childEvents",
                            childEvent.clone(),
                            appendItem(
                                    "/items/-",
                                    "repeat")));
            Node child = document(
                    "Cross-scope update event child",
                    childContracts)
                    .properties("items",
                            new Node().items(
                                    Collections.<Node>emptyList()));

            Map<String, Node> rootContracts =
                    new LinkedHashMap<String, Node>();
            rootContracts.put("embedded",
                    processEmbedded("/child"));
            rootContracts.put("childEvents",
                    embeddedNodeChannel(
                            "/child", childEvent.clone()));
            rootContracts.put("ancestorRecord",
                    workflow("childEvents",
                            null,
                            appendItem(
                                    "/ancestorItems/-",
                                    "child-event")));
            return document(
                    "Cross-scope update event loop",
                    rootContracts)
                    .properties("ancestorItems",
                            new Node().items(
                                    Collections.<Node>emptyList()))
                    .properties("child", child);
        }

        private Node embeddedChildAncestorEventLoopDocument() {
            Node childEvent =
                    coordinationEvent("embedded-child-loop");
            Map<String, Node> childContracts =
                    rootContracts();
            childContracts.put("childEvents",
                    triggeredEventChannel(
                            childEvent.clone()));
            childContracts.put("childSeed",
                    workflow(EXACT_CHANNEL_KEY,
                            null,
                            new TriggerEvent()
                                    .event(childEvent.clone())));
            childContracts.put("childRepeat",
                    workflow("childEvents",
                            childEvent.clone(),
                            new TriggerEvent()
                                    .event(childEvent.clone())));
            Node child = document(
                    "Embedded child event source",
                    childContracts);

            Map<String, Node> rootContracts =
                    new LinkedHashMap<String, Node>();
            rootContracts.put("embedded",
                    processEmbedded("/child"));
            rootContracts.put("childEvents",
                    embeddedNodeChannel(
                            "/child", childEvent.clone()));
            rootContracts.put("ancestorObserve",
                    workflow("childEvents", null));
            return document(
                    "Embedded child ancestor event loop",
                    rootContracts)
                    .properties("child", child);
        }

        private Node nestedComputeEventLoopDocument() {
            Node computeEvent =
                    coordinationEvent("compute-loop");
            Node bexEvent =
                    new Node()
                            .properties(
                                    "type",
                                    new Node().blueId(
                                            computeEvent
                                                    .getType()
                                                    .getBlueId()))
                            .properties(
                                    "kind",
                                    new Node().value(
                                            "compute-loop"));
            Map<String, Node> contracts =
                    rootContracts();
            contracts.put("computeEvents",
                    triggeredEventChannel(
                            computeEvent.clone()));
            contracts.put("seedCompute",
                    workflow(EXACT_CHANNEL_KEY,
                            null,
                            computeEmitting(
                                    bexEvent.clone())));
            contracts.put("repeatCompute",
                    workflow("computeEvents",
                            computeEvent.clone(),
                            computeEmitting(
                                    bexEvent.clone())));
            return document(
                    "Nested hosted Compute event loop",
                    contracts);
        }

        private Node multiSourceLogicalDeliveryLoopDocument() {
            Node repeatingEvent =
                    coordinationEvent(
                            "multi-source-logical-loop");
            Map<String, Node> contracts =
                    new LinkedHashMap<String, Node>();
            contracts.put(
                    LOGICAL_SOURCE_A_KEY,
                    routedExactChannel(
                            LOGICAL_SOURCE_A_KEY));
            contracts.put(
                    LOGICAL_SOURCE_B_KEY,
                    routedExactChannel(
                            LOGICAL_SOURCE_B_KEY));
            contracts.put(
                    LOGICAL_TARGET_KEY,
                    triggeredEventChannel(
                            coordinationEvent(
                                    "logical-target-only")));
            contracts.put(
                    "loopEvents",
                    triggeredEventChannel(
                            repeatingEvent.clone()));
            contracts.put(
                    "seed",
                    workflow(
                            LOGICAL_TARGET_KEY,
                            null,
                            new TriggerEvent()
                                    .event(
                                            repeatingEvent.clone())));
            contracts.put(
                    "repeat",
                    workflow(
                            "loopEvents",
                            repeatingEvent.clone(),
                            new TriggerEvent()
                                    .event(
                                            repeatingEvent.clone())));
            return document(
                    "Multi-source logical-delivery loop",
                    contracts);
        }

        private Node largeFiniteBexDocument(int itemCount) {
            Map<String, Node> contracts =
                    rootContracts();
            contracts.put("largeBex",
                    workflow(EXACT_CHANNEL_KEY,
                            null,
                            finiteBexIteration(itemCount)));
            return document(
                    "Large finite BEX parent child budget",
                    contracts);
        }

        private Node recursiveBexDocument() {
            Map<String, Node> contracts =
                    rootContracts();
            contracts.put("recursiveBex",
                    workflow(EXACT_CHANNEL_KEY,
                            null,
                            recursiveBex()));
            return document(
                    "Recursive BEX compile rejection",
                    contracts);
        }

        private Node largeFiniteSequentialWorkflowDocument(
                int stepCount) {
            SequentialWorkflowStep[] steps =
                    new SequentialWorkflowStep[stepCount];
            for (int index = 0; index < stepCount; index++) {
                steps[index] = replaceCounter(index + 1);
            }
            Map<String, Node> contracts =
                    rootContracts();
            contracts.put("finiteWorkflow",
                    workflow(EXACT_CHANNEL_KEY,
                            null,
                            steps));
            return document(
                    "Large finite Sequential Workflow",
                    contracts)
                    .properties("counter", new Node().value(0));
        }

        private Map<String, Node> rootContracts() {
            Map<String, Node> contracts =
                    new LinkedHashMap<String, Node>();
            contracts.put(EXACT_CHANNEL_KEY,
                    typed(EXACT_CHANNEL_BLUE_ID));
            return contracts;
        }

        private Node routedExactChannel(
                String key) {
            return typed(EXACT_CHANNEL_BLUE_ID)
                    .name(key)
                    .properties(
                            "handlerChannelKey",
                            new Node().value(
                                    LOGICAL_TARGET_KEY))
                    .properties(
                            "logicalDeliveryKey",
                            new Node().value(
                                    SHARED_LOGICAL_DELIVERY_KEY));
        }

        private Node document(
                String name,
                Map<String, Node> contracts) {
            return new Node()
                    .name(name)
                    .properties("contracts",
                            new Node().properties(contracts));
        }

        private Node workflow(
                String channel,
                Node eventPattern,
                SequentialWorkflowStep... steps) {
            SequentialWorkflow workflow =
                    new SequentialWorkflow()
                            .steps(Arrays.asList(steps));
            workflow.setChannel(channel);
            workflow.setEvent(eventPattern);
            return blue.objectToNode(workflow);
        }

        private Node coordinationEvent(String kind) {
            return blue.objectToNode(new Event())
                    .properties("kind", new Node().value(kind));
        }

        private UpdateDocument appendItem(String value) {
            return appendItem("/items/-", value);
        }

        private UpdateDocument appendItem(
                String path,
                String value) {
            return update("add",
                    path,
                    new Node().value(value));
        }

        private UpdateDocument replaceCounter(int value) {
            return update("replace",
                    "/counter",
                    new Node().value(value));
        }

        private UpdateDocument update(
                String operation,
                String path,
                Node value) {
            Node patch = new Node()
                    .properties("op",
                            new Node().value(operation))
                    .properties("path",
                            new Node().value(path))
                    .properties("val", value);
            return new UpdateDocument()
                    .changeset(
                            Collections.singletonList(patch));
        }

        private Compute finiteBexIteration(int itemCount) {
            List<Node> items =
                    new ArrayList<Node>(itemCount);
            for (int index = 0; index < itemCount; index++) {
                items.add(new Node().value(index));
            }
            Node forEach = operation("$forEach",
                    new Node()
                            .properties("in",
                                    new Node().items(items))
                            .properties("item",
                                    new Node().value("item"))
                            .properties("index",
                                    new Node().value("index"))
                            .properties("do",
                                    new Node().items(
                                            Collections.<Node>emptyList())));
            Node appendedEvent = new Node()
                    .properties("kind",
                            new Node().value("finite-bex-complete"))
                    .properties("count",
                            new Node().value(itemCount));
            return new Compute()
                    .doValue(Arrays.asList(
                            forEach,
                            operation("$appendEvent",
                                    appendedEvent)))
                    .emitEvents(Boolean.TRUE)
                    .returnResult(Boolean.TRUE)
                    .gasLimit(BigInteger.valueOf(FULL_GAS_LIMIT));
        }

        private Compute computeEmitting(Node event) {
            return new Compute()
                    .doValue(Collections.singletonList(
                            operation("$appendEvent",
                                    event)))
                    .emitEvents(Boolean.TRUE)
                    .returnResult(Boolean.TRUE)
                    .gasLimit(BigInteger.valueOf(FULL_GAS_LIMIT));
        }

        private Compute recursiveBex() {
            Map<String, Node> functions =
                    new LinkedHashMap<String, Node>();
            functions.put("recurse",
                    new Node().properties("expr",
                            operation("$call",
                                    new Node()
                                            .properties("function",
                                                    new Node().value(
                                                            "recurse"))
                                            .properties("args",
                                                    new Node().properties(
                                                            new LinkedHashMap<String, Node>())))));
            return new Compute()
                    .entry("recurse")
                    .functions(functions)
                    .gasLimit(BigInteger.valueOf(FULL_GAS_LIMIT));
        }
    }

    private static Node operation(String name, Node value) {
        return new Node().properties(name, value);
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static Node triggeredEventChannel(Node event) {
        return typed(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)
                .properties("event", event);
    }

    private static Node documentUpdateChannel(String path) {
        return typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL)
                .properties("path", new Node().value(path));
    }

    private static Node processEmbedded(String path) {
        return typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                .properties("paths",
                        new Node().items(
                                new Node().value(path)));
    }

    private static Node embeddedNodeChannel(
            String sourcePath,
            Node event) {
        return typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                .properties("sourcePath",
                        new Node().value(sourcePath))
                .properties("event", event);
    }

    @TypeBlueId(EXACT_CHANNEL_BLUE_ID)
    public static final class ExactChannel
            extends ChannelContract {
        private String handlerChannelKey;
        private String logicalDeliveryKey;

        public String getHandlerChannelKey() {
            return handlerChannelKey;
        }

        public void setHandlerChannelKey(
                String handlerChannelKey) {
            this.handlerChannelKey =
                    handlerChannelKey;
        }

        public String getLogicalDeliveryKey() {
            return logicalDeliveryKey;
        }

        public void setLogicalDeliveryKey(
                String logicalDeliveryKey) {
            this.logicalDeliveryKey =
                    logicalDeliveryKey;
        }
    }

    private static final class ExactChannelProcessor
            implements ChannelProcessor<ExactChannel> {
        @Override
        public Class<ExactChannel> contractType() {
            return ExactChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<ExactChannel>
        externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<ExactChannel>() {
                @Override
                public List<String> channelKeys(
                        ExactChannel immutableContractSnapshot) {
                    return Collections.singletonList(
                            EXACT_CHANNEL_KEY);
                }

                @Override
                public List<String> channelKeys(
                        ExactChannel immutableContractSnapshot,
                        ExternalChannelFunctionContext context) {
                    String target =
                            immutableContractSnapshot
                                    .getHandlerChannelKey();
                    if (target != null
                            && !target.isEmpty()
                            && !target.equals(
                            immutableContractSnapshot
                                    .getKey())) {
                        context.dependOnSameScopeChannel(
                                target);
                    }
                    return channelKeys(
                            immutableContractSnapshot);
                }

                @Override
                public String checkpointDomainDiscriminator(
                        ExactChannel immutableContractSnapshot) {
                    return EXACT_CHANNEL_DISCRIMINATOR;
                }

                @Override
                public String handlerChannelKey(
                        ExactChannel immutableContractSnapshot,
                        Node exactEvent,
                        Node exactPayload,
                        ExternalChannelFunctionContext context) {
                    String target =
                            immutableContractSnapshot
                                    .getHandlerChannelKey();
                    return target == null
                            || target.isEmpty()
                            ? context.channelKey()
                            : target;
                }

                @Override
                public String logicalDeliveryKey(
                        ExactChannel immutableContractSnapshot,
                        Node exactEvent,
                        Node exactPayload,
                        ExternalChannelFunctionContext context) {
                    String logical =
                            immutableContractSnapshot
                                    .getLogicalDeliveryKey();
                    return logical == null
                            || logical.isEmpty()
                            ? context.channelKey()
                            : logical;
                }
            };
        }

        @Override
        public boolean matches(
                ExactChannel contract,
                ChannelEvaluationContext context) {
            return context.event() != null;
        }

        @Override
        public String eventId(
                ExactChannel contract,
                ChannelEvaluationContext context) {
            Object id = context.event().get("/id");
            return id != null
                    ? String.valueOf(id)
                    : "coordination-loop";
        }
    }

    /**
     * Exact snapshot seam used by the processor itself. It deliberately avoids
     * provider lookup for the test-only Channel while retaining real
     * canonical patching and immutable snapshots.
     */
    private static final class ExactSnapshotManager
            implements ProcessingSnapshotManager {
        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            FrozenNode canonical =
                    FrozenNode.fromUncheckedCanonicalNode(
                            document.clone());
            return new ResolvedSnapshot(
                    canonical,
                    FrozenNode.fromResolvedNode(
                            document.clone()),
                    canonical.blueId());
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(
                Node document) {
            return fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return fromDocument(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            CanonicalPatchResult patched =
                    new CanonicalOverlayPatchEngine(
                            snapshot.frozenCanonicalRoot())
                            .apply(patch);
            return new ResolvedSnapshot(
                    patched.root(),
                    FrozenNode.fromResolvedNode(
                            patched.root().toNode()),
                    patched.blueId());
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(
                ResolvedSnapshot snapshot) {
            return snapshot;
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            return new ExactSnapshotScope(this);
        }
    }

    private static final class ExactSnapshotScope
            implements ProcessingSnapshotManager {
        private final ExactSnapshotManager owner;

        private ExactSnapshotScope(
                ExactSnapshotManager owner) {
            this.owner = owner;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return owner.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(
                Node document) {
            return owner.fromDocumentTransient(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return owner.fromDocumentPreservingPaths(
                    document, preservedPaths);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return owner.applyPatch(snapshot, patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(
                ResolvedSnapshot snapshot) {
            return owner.cacheSnapshot(snapshot);
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            return this;
        }

        @Override
        public ProcessingSnapshotManager forkTransientSequence() {
            return owner.transientSequence();
        }

        @Override
        public void releaseTransientState() {
            // The immutable values are owned by the enclosing invocation.
        }
    }
}
