package blue.coordination.processor.bex;

import blue.bex.api.BexDocumentView;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.coordination.processor.workflow.StepExecutionContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.util.Objects;

/**
 * BEX document view that resolves authored pointers against the active processor scope.
 */
final class ScopedProcessorExecutionContextBexDocumentView implements BexDocumentView {
    private final FrozenAccess access;
    private final BexProcessingMetrics metrics;

    ScopedProcessorExecutionContextBexDocumentView(StepExecutionContext context) {
        this(context, null);
    }

    ScopedProcessorExecutionContextBexDocumentView(StepExecutionContext context,
                                                   BexProcessingMetrics metrics) {
        this(new StepContextFrozenAccess(context), metrics);
    }

    ScopedProcessorExecutionContextBexDocumentView(
            FrozenAccess access,
            BexProcessingMetrics metrics) {
        this.access =
                Objects.requireNonNull(
                        access, "access");
        this.metrics = metrics;
    }

    @Override
    public String resolvePointer(String authoredPointer) {
        return access.resolvePointer(authoredPointer);
    }

    @Override
    public BexValue canonicalAt(String pointer) {
        return exactAt(
                access.resolvePointer(pointer));
    }

    @Override
    public BexValue resolvedAt(String pointer) {
        return exactAt(
                access.resolvePointer(pointer));
    }

    @Override
    public String currentScopePath() {
        return access.currentScopePath();
    }

    private BexValue exactAt(String absolutePointer) {
        FrozenNode workingCanonical =
                access.workingCanonicalAt(
                        absolutePointer);
        FrozenNode workingResolved =
                access.workingResolvedAt(
                        absolutePointer);
        if (hasResolvedSemantics(
                workingResolved)) {
            if (metrics != null) {
                metrics.incrementBexDocumentViewFrozenDirectHits();
            }
            return authoritativeExact(
                    workingCanonical,
                    workingResolved);
        }
        FrozenNode processorCanonical =
                access.processorCanonicalAt(
                        absolutePointer);
        FrozenNode processorResolved =
                access.processorResolvedAt(
                        absolutePointer);
        if (hasResolvedSemantics(
                processorResolved)) {
            if (metrics != null) {
                metrics.incrementBexDocumentViewFrozenDirectHits();
            }
            return authoritativeExact(
                    workingCanonical != null
                            ? workingCanonical
                            : processorCanonical,
                    processorResolved);
        }
        FrozenNode canonicalRoot =
                access.workingCanonicalRoot();
        FrozenNode resolvedRoot =
                access.workingResolvedRoot();
        if (hasResolvedSemantics(
                resolvedRoot)) {
            if (metrics != null) {
                metrics.incrementBexDocumentViewFrozenRootFallbackHits();
            }
            return authoritativeExact(
                            canonicalRoot,
                            resolvedRoot)
                    .at(JsonPointer.split(
                            absolutePointer));
        }
        FrozenNode unresolvedCanonical =
                workingCanonical != null
                        ? workingCanonical
                        : processorCanonical != null
                                ? processorCanonical
                                : canonicalRoot;
        FrozenNode unresolvedResolved =
                workingResolved != null
                        ? workingResolved
                        : processorResolved != null
                                ? processorResolved
                                : resolvedRoot;
        if (unresolvedCanonical != null
                || unresolvedResolved != null) {
            if (metrics != null) {
                metrics.incrementBexDocumentViewFrozenDirectHits();
            }
            return BexValues.exact(
                    unresolvedCanonical,
                    unresolvedResolved);
        }
        if (metrics != null) {
            metrics.incrementBexDocumentViewUndefinedHits();
        }
        return BexValues.undefined();
    }

    private static boolean hasResolvedSemantics(
            FrozenNode resolved) {
        return resolved != null
                && !resolved.isReferenceOnly();
    }

    private static BexValue authoritativeExact(
            FrozenNode canonical,
            FrozenNode resolved) {
        if (resolved == null
                || resolved.isReferenceOnly()) {
            return BexValues.exact(
                    canonical, resolved);
        }
        FrozenNode identity =
                canonical != null
                        ? canonical
                        : resolved;
        /*
         * Hosted PROCESS has already established both lanes. Retain the
         * canonical identity, but make the authoritative resolved cursor the
         * structural value exposed to BEX. Returning an admitted exact value
         * also prevents BexRuntime from attaching its standalone default Blue
         * as a reference materializer and reopening a persisted scalar or
         * object that this snapshot has already resolved.
         */
        BexValue semantic =
                BexValues.frozen(
                        resolved);
        return BexValues.admittedExact(
                resolved,
                identity.blueId(),
                semantic);
    }

    interface FrozenAccess {
        String resolvePointer(String authoredPointer);

        String currentScopePath();

        FrozenNode workingCanonicalAt(String absolutePointer);

        FrozenNode workingResolvedAt(String absolutePointer);

        FrozenNode processorCanonicalAt(String absolutePointer);

        FrozenNode processorResolvedAt(String absolutePointer);

        FrozenNode workingCanonicalRoot();

        FrozenNode workingResolvedRoot();
    }

    private static final class StepContextFrozenAccess
            implements FrozenAccess {
        private final StepExecutionContext stepContext;
        private final ProcessorExecutionContext processorContext;

        private StepContextFrozenAccess(
                StepExecutionContext stepContext) {
            this.stepContext =
                    Objects.requireNonNull(
                            stepContext, "context");
            this.processorContext =
                    stepContext.processorContext();
        }

        @Override
        public String resolvePointer(
                String authoredPointer) {
            return processorContext.resolvePointer(
                    authoredPointer);
        }

        @Override
        public String currentScopePath() {
            return processorContext.scopePath();
        }

        @Override
        public FrozenNode workingCanonicalAt(
                String absolutePointer) {
            return stepContext.workingCanonicalAt(
                    absolutePointer);
        }

        @Override
        public FrozenNode workingResolvedAt(
                String absolutePointer) {
            return stepContext.workingResolvedAt(
                    absolutePointer);
        }

        @Override
        public FrozenNode processorCanonicalAt(
                String absolutePointer) {
            return processorContext.canonicalFrozenAt(
                    absolutePointer);
        }

        @Override
        public FrozenNode processorResolvedAt(
                String absolutePointer) {
            return processorContext.resolvedFrozenAt(
                    absolutePointer);
        }

        @Override
        public FrozenNode workingCanonicalRoot() {
            return stepContext.workingDocument()
                    .canonicalRoot();
        }

        @Override
        public FrozenNode workingResolvedRoot() {
            return stepContext.workingDocument()
                    .resolvedRoot();
        }
    }
}
