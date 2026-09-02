package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.SelectedExecutableBody;
import blue.language.snapshot.FrozenNode;

/**
 * Resolves an inline Compute definition, a working-document pointer, or an
 * exact definition reference exposed by the selected workflow body.
 *
 * <p>Pointer definitions are looked up for every invocation because an
 * earlier workflow step may have changed the target. The resolved frozen
 * identity then participates in the Compute plan key, preventing a cached
 * program from observing stale definition content. Pure BlueId references
 * are opened only through Language's invocation-bound verified selected-body
 * capability.</p>
 */
final class ComputeDefinitionResolver {
    private final BexProcessingMetrics metrics;

    ComputeDefinitionResolver() {
        this(null);
    }

    ComputeDefinitionResolver(BexProcessingMetrics metrics) {
        this.metrics = metrics;
    }

    FrozenNode resolve(FrozenNode stepNode, StepExecutionContext context) {
        return resolve(stepNode, context, metrics);
    }

    FrozenNode resolve(FrozenNode stepNode,
                       StepExecutionContext context,
                       BexProcessingMetrics invocationMetrics) {
        FrozenNode definition = FrozenNodeUtil.property(stepNode, "definition");
        /*
         * BEX defines the separate definition as optional executable input.
         * An exact empty object contributes no constants or functions, so it
         * projects to no execution definition. The authored step itself is
         * still retained in the Compute plan key; this projection must not
         * collapse its exact identity or audit representation.
         */
        if (definition == null || FrozenNodeUtil.isEmpty(definition)) {
            return null;
        }
        if (definition.getReferenceBlueId() != null) {
            return materializeExactDefinition(
                    definition,
                    context,
                    invocationMetrics);
        }
        String text = FrozenNodeUtil.text(definition);
        if (text != null && !text.trim().isEmpty()) {
            String pointer = resolvePointer(text.trim(), context);
            // This lookup is deliberately performed against the current
            // WorkingDocument on every invocation. The exact returned frozen
            // identity participates in the Compute plan key, so a definition
            // changed by an earlier step can never reuse a stale plan.
            FrozenNode frozen = context.workingResolvedAt(pointer);
            if (frozen == null) {
                context.throwFatal("Compute definition not found: " + text);
                return null;
            }
            incrementFrozenDirectHit(invocationMetrics);
            return frozen;
        }
        incrementFrozenDirectHit(invocationMetrics);
        return definition;
    }

    FrozenNode resolve(Node stepNode, StepExecutionContext context) {
        Node definition = NodeUtil.property(stepNode, "definition");
        if (definition == null || NodeUtil.isEmpty(definition)) {
            return null;
        }
        if (definition.getBlueId() != null) {
            return materializeExactDefinition(
                    FrozenNode.fromNode(definition),
                    context,
                    metrics);
        }
        String text = NodeUtil.text(definition);
        if (text != null && !text.trim().isEmpty()) {
            String pointer = resolvePointer(text.trim(), context);
            FrozenNode frozen = context.workingResolvedAt(pointer);
            if (frozen == null) {
                context.throwFatal("Compute definition not found: " + text);
                return null;
            }
            incrementFrozenDirectHit(metrics);
            return frozen;
        }
        if (metrics != null) {
            metrics.incrementComputeDefinitionMaterializations();
        }
        return FrozenNode.fromResolvedNode(definition);
    }

    private FrozenNode materializeExactDefinition(
            FrozenNode reference,
            StepExecutionContext context,
            BexProcessingMetrics invocationMetrics) {
        SelectedExecutableBody selectedBody =
                context.processorContext()
                        .selectedExecutableBody("steps");
        if (selectedBody == null) {
            context.throwFatal(
                    "Compute definition reference requires the selected "
                            + "workflow steps capability");
            return null;
        }
        /*
         * Effective workflow bodies may retain their exact provider BlueId
         * beside resolved fields.  Reopen that identity through Language's
         * selected-body capability instead of giving hosted BEX the expanded
         * view: static BEX literals must retain the provider-authored pure
         * reference shape for their nested type values.
         */
        FrozenNode exactReference = reference.isReferenceOnly()
                ? reference
                : FrozenNode.fromNode(
                        new Node().blueId(
                                reference.getReferenceBlueId()));
        FrozenNode materialized =
                selectedBody.materializeExactReference(exactReference);
        incrementFrozenDirectHit(invocationMetrics);
        return materialized;
    }

    String resolvePointer(String reference, StepExecutionContext context) {
        if (reference.startsWith("/")) {
            return reference;
        }
        String parent = parentPointer(currentContractPointer(context));
        if (parent == null || parent.isEmpty()) {
            parent = "/";
        }
        return appendPointer(parent, reference);
    }

    private void incrementFrozenDirectHit(BexProcessingMetrics invocationMetrics) {
        BexProcessingMetrics activeMetrics = invocationMetrics != null
                ? invocationMetrics
                : metrics;
        if (activeMetrics != null) {
            activeMetrics.incrementComputeDefinitionFrozenDirectHits();
        }
    }

    private String currentContractPointer(StepExecutionContext context) {
        String key = context.processorContext().contractKey();
        if (key == null || key.trim().isEmpty()) {
            return context.processorContext().scopePath();
        }
        String scope = context.processorContext().scopePath();
        String contracts = appendPointer(scope == null || scope.trim().isEmpty() ? "/" : scope, "contracts");
        return appendPointer(contracts, key.trim());
    }

    private String parentPointer(String pointer) {
        if (pointer == null || pointer.isEmpty() || "/".equals(pointer)) {
            return "/";
        }
        int last = pointer.lastIndexOf('/');
        if (last <= 0) {
            return "/";
        }
        return pointer.substring(0, last);
    }

    private String appendPointer(String parent, String segment) {
        String escaped = escapePointerSegment(segment);
        if (parent == null || parent.isEmpty() || "/".equals(parent)) {
            return "/" + escaped;
        }
        return parent + "/" + escaped;
    }

    private String escapePointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}
