package blue.coordination.processor.bex;

import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexStepResults;
import blue.bex.contracts.BexContractsExecutionContext;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.snapshot.FrozenNode;

import java.util.Map;

/**
 * Adapts the current workflow step and processor-owned semantic boundary into
 * a hosted BEX execution context.
 *
 * <p>The resulting context exposes the original processing event, current
 * contract, prior step results, and the live document view without creating a
 * separate document-processing session.</p>
 */
public final class BexWorkflowContextFactory {
    private final BexProcessingMetrics metrics;
    private final ProcessingEventIdentityObserver
            processingEventIdentityObserver;

    public BexWorkflowContextFactory() {
        this(null, null);
    }

    public BexWorkflowContextFactory(BexProcessingMetrics metrics) {
        this(metrics, null);
    }

    public BexWorkflowContextFactory(
            BexProcessingMetrics metrics,
            ProcessingEventIdentityObserver
                    processingEventIdentityObserver) {
        this.metrics = metrics;
        this.processingEventIdentityObserver =
                processingEventIdentityObserver;
    }

    BexProcessingMetrics metrics() {
        return metrics;
    }

    public BexExecutionContext create(
            BexWorkflowStepContext context,
            long gasLimit) {
        return create(
                context,
                gasLimit,
                true);
    }

    /**
     * Compatibility bridge for callers compiled against the concrete
     * workflow context signature.
     *
     * @deprecated use {@link #create(BexWorkflowStepContext, long)} so hosted
     *     BEX depends on the capability role rather than the workflow
     *     implementation.
     */
    @Deprecated
    public BexExecutionContext create(
            blue.coordination.processor.workflow.StepExecutionContext context,
            long gasLimit) {
        return create((BexWorkflowStepContext) context, gasLimit);
    }

    /**
     * Creates a hosted context without materializing the Root processing
     * event when the immutable Compute plan proves the binding is unused.
     */
    public BexExecutionContext create(
            BexWorkflowStepContext context,
            long gasLimit,
            boolean processingEventRequired) {
        /*
         * The channelized event may contain exact pure-reference descendants
         * (for example an Operation Request document). A transient Node cursor
         * cannot carry that identity through BEX output admission. Snapshot
         * the invocation-owned event once so exact descendants remain exact
         * while computed event aggregates still cross the hosted semantic
         * boundary normally.
         */
        ProcessorExecutionContext processorContext = context.processorContext();
        FrozenNode exactHandlerEvent = processorContext.frozenEvent();
        BexValue event = exactHandlerEvent != null
                ? BexValues.frozen(exactHandlerEvent)
                : BexValues.nodeSnapshot(context.eventRef());
        BexValue currentContract = currentContractBinding(context);
        BexStepResults steps = stepResults(context.stepResults());
        FrozenNode processingEventSnapshot =
                processingEventRequired
                        && processorContext.hasProcessEvent()
                        ? processorContext.frozenProcessEvent()
                        : null;
        BexValue processingEvent = processingEventSnapshot != null
                ? BexValues.frozen(processingEventSnapshot)
                : BexValues.undefined();
        if (processingEventIdentityObserver != null
                && processingEventSnapshot != null) {
            processingEventIdentityObserver.observe(
                    processingEventSnapshot,
                    processingEvent.exactBlueId(),
                    ProcessingEventIdentityObserver.Boundary
                            .BEX_BINDING);
        }
        return BexContractsExecutionContext
                .configure(
                        BexExecutionContext.builder(),
                        processorContext)
                .document(new ScopedProcessorExecutionContextBexDocumentView(context, metrics))
                .event(event)
                .processingEvent(processingEvent)
                .currentContract(currentContract)
                .steps(steps)
                .gasLedgerHost(context.bexGasLedgerHost())
                .gasLimit(gasLimit)
                .build();
    }

    public BexStepResults stepResults(Map<String, Object> workflowStepResults) {
        BexStepResults.Builder builder = BexStepResults.builder();
        if (workflowStepResults == null) {
            return builder.build();
        }
        for (Map.Entry<String, Object> entry : workflowStepResults.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof BexExecutionResult) {
                BexExecutionResult execution =
                        (BexExecutionResult) value;
                /*
                 * A hosted execution has already crossed BEX's root Blue
                 * output boundary. Preserve that admitted exact value for the
                 * next workflow step instead of re-exposing its transient
                 * pre-admission cursor.
                 */
                builder.put(
                        name,
                        execution.output() != null
                                ? execution.output()
                                        .semanticValue()
                                : execution.value());
            } else if (value instanceof Node) {
                builder.put(name, BexValues.nodeCursorTrustedImmutable((Node) value));
            } else {
                builder.put(name, BexValues.fromSimple(value));
            }
        }
        return builder.build();
    }

    public BexValue currentContractBinding(
            BexWorkflowStepContext context) {
        FrozenNode resolved =
                context.currentContractFrozenNode();
        if (resolved == null) {
            return BexValues.nodeCursorTrustedImmutable(
                    context.currentContractNodeRef());
        }
        String contractKey =
                context.processorContext()
                        .contractKey();
        FrozenNode canonical =
                contractKey != null
                        ? context.processorContext()
                                .canonicalFrozenAt(
                                        context.processorContext()
                                                .resolvePointer(
                                                        "/contracts/"
                                                                + escapePointerSegment(
                                                                        contractKey)))
                        : null;
        return canonical != null
                ? BexValues.exact(
                        canonical,
                        resolved)
                : BexValues.frozen(
                        resolved);
    }

    /**
     * Compatibility bridge for callers compiled against the concrete
     * workflow context signature.
     *
     * @deprecated use {@link #currentContractBinding(BexWorkflowStepContext)}
     *     so hosted BEX depends on the capability role rather than the
     *     workflow implementation.
     */
    @Deprecated
    public BexValue currentContractBinding(
            blue.coordination.processor.workflow.StepExecutionContext context) {
        return currentContractBinding((BexWorkflowStepContext) context);
    }

    private String escapePointerSegment(String value) {
        return value.replace("~", "~0")
                .replace("/", "~1");
    }
}
