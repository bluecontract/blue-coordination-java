package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.SelectedExecutableBody;
import blue.language.processor.util.PointerUtils;
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

    String definitionPointer(FrozenNode stepNode, StepExecutionContext context) {
        FrozenNode definition = FrozenNodeUtil.property(stepNode, "definition");
        if (definition == null || definition.getReferenceBlueId() != null) {
            return null;
        }
        String text = FrozenNodeUtil.text(definition);
        return text != null && !text.trim().isEmpty() ? resolvePointer(text.trim(), context) : null;
    }

    /** Presence in exact selected contributions, not equality with effective type defaults. */
    int declaredMaps(String pointer, StepExecutionContext context) {
        if (pointer == null) return 0;
        int present = 0;
        for (FrozenNode source : context.workingDocument().sourceContributionsAt(pointer)) {
            if (FrozenNodeUtil.property(source, "constants") != null) present |= 1;
            if (FrozenNodeUtil.property(source, "functions") != null) present |= 2;
        }
        return present;
    }

    FrozenNode resolve(FrozenNode stepNode,
                       StepExecutionContext context,
                       BexProcessingMetrics invocationMetrics) {
        FrozenNode definition = FrozenNodeUtil.property(stepNode, "definition");
        /*
         * BEX defines the separate definition as optional executable input.
         * An exact empty object remains a present empty execution definition.
         * Its identity is retained just like other exact input. The authored step is
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
        String pointer = definitionPointer(stepNode, context);
        if (pointer != null) {
            // This lookup is deliberately performed against the current
            // WorkingDocument on every invocation. The exact returned frozen
            // identity participates in the Compute plan key, so a definition
            // changed by an earlier step can never reuse a stale plan.
            FrozenNode frozen = context.workingResolvedAt(pointer);
            if (frozen == null) {
                context.throwFatal("Compute definition not found: " + FrozenNodeUtil.text(definition));
                return null;
            }
            incrementFrozenDirectHit(invocationMetrics);
            return frozen;
        }
        incrementFrozenDirectHit(invocationMetrics);
        return definition;
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
        // A named definition is a sibling of the executing workflow contract.
        String key = context.processorContext().contractKey();
        String scope = context.processorContext().scopePath();
        String parent = key != null && !key.trim().isEmpty()
                ? PointerUtils.appendPointer(scope == null || scope.trim().isEmpty() ? "/" : scope, "contracts")
                : parentPointer(scope);
        return PointerUtils.appendPointer(parent, reference);
    }

    private void incrementFrozenDirectHit(BexProcessingMetrics invocationMetrics) {
        BexProcessingMetrics activeMetrics = invocationMetrics != null
                ? invocationMetrics
                : metrics;
        if (activeMetrics != null) {
            activeMetrics.incrementComputeDefinitionFrozenDirectHits();
        }
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
}
