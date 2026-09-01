package blue.coordination.processor.bex;

import blue.bex.api.BexDocumentView;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * BEX document view that resolves authored pointers against the active processor scope.
 */
final class ScopedProcessorExecutionContextBexDocumentView implements BexDocumentView {
    private final FrozenAccess access;
    private final BexProcessingMetrics metrics;

    ScopedProcessorExecutionContextBexDocumentView(
            BexWorkflowStepContext context) {
        this(context, null);
    }

    ScopedProcessorExecutionContextBexDocumentView(
            BexWorkflowStepContext context,
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
        return cursorAt(access.resolvePointer(pointer));
    }

    @Override
    public BexValue resolvedAt(String pointer) {
        return cursorAt(access.resolvePointer(pointer));
    }

    @Override
    public String currentScopePath() {
        return access.currentScopePath();
    }

    private BexValue cursorAt(String absolutePointer) {
        BexValue exact = exactAt(absolutePointer);
        return cursorFor(absolutePointer, exact);
    }

    private BexValue cursorFor(
            String absolutePointer,
            BexValue value) {
        if (value.isUndefined()
                || hasTerminalSemanticContent(value)) {
            return value;
        }
        return new ProcessorDocumentCursor(
                absolutePointer,
                value);
    }

    private boolean hasTerminalSemanticContent(
            BexValue value) {
        try {
            return value.isNull()
                    || value.isScalar();
        } catch (RuntimeException failure) {
            if (isUnavailableExactSemantic(failure)) {
                return false;
            }
            throw failure;
        }
    }

    private boolean isUnavailableExactSemantic(
            RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.contains(
                    "Semantic content is unavailable for exact Blue reference")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
        Node processorDocument = access.processorDocumentAt(
                absolutePointer);
        if (processorDocument != null
                && !processorDocument.isReferenceOnly()) {
            if (metrics != null) {
                metrics.incrementBexDocumentViewFrozenDirectHits();
            }
            FrozenNode demanded = FrozenNode.fromNode(processorDocument);
            return authoritativeExact(
                    workingCanonical != null
                            ? workingCanonical
                            : processorCanonical,
                    demanded);
        }
        BexValue scoped = resolvedFromCurrentScope(
                absolutePointer);
        if (scoped != null) {
            if (metrics != null) {
                metrics.incrementBexDocumentViewFrozenDirectHits();
            }
            return scoped;
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
            return authoritativeExact(
                    unresolvedCanonical,
                    unresolvedResolved);
        }
        if (metrics != null) {
            metrics.incrementBexDocumentViewUndefinedHits();
        }
        return BexValues.undefined();
    }

    private BexValue resolvedFromCurrentScope(
            String absolutePointer) {
        String scopePath = JsonPointer.canonicalize(
                access.currentScopePath());
        if (JsonPointer.ROOT.equals(scopePath)) {
            return null;
        }
        List<String> scopeSegments = JsonPointer.split(
                scopePath);
        List<String> absoluteSegments = JsonPointer.split(
                absolutePointer);
        if (absoluteSegments.size() <= scopeSegments.size()
                || !absoluteSegments.subList(
                0, scopeSegments.size()).equals(scopeSegments)) {
            return null;
        }
        FrozenNode resolvedScope = access.processorResolvedAt(
                scopePath);
        if (!hasResolvedSemantics(resolvedScope)) {
            return null;
        }
        FrozenNode canonicalScope = access.processorCanonicalAt(
                scopePath);
        BexValue descendant = authoritativeExact(
                canonicalScope,
                resolvedScope).at(absoluteSegments.subList(
                scopeSegments.size(), absoluteSegments.size()));
        return !descendant.isUndefined()
                && hasSemanticContent(descendant)
                ? descendant
                : null;
    }

    private boolean hasSemanticContent(
            BexValue value) {
        try {
            value.isNull();
            return true;
        } catch (RuntimeException failure) {
            if (isUnavailableExactSemantic(failure)) {
                return false;
            }
            throw failure;
        }
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
            FrozenNode identity = canonical != null
                    ? canonical
                    : resolved;
            return BexValues.exact(
                    canonical,
                    resolved,
                    identity != null ? identity.blueId() : null);
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
        if (canonical != null
                && canonical.isReferenceOnly()
                && BlueIds.hasCyclicMemberSeparator(
                        canonical.getReferenceBlueId())) {
            return new VerifiedSemanticExactBexValue(
                    canonical.getReferenceBlueId(),
                    semantic);
        }
        return BexValues.admittedExact(
                resolved,
                identity.blueId(),
                semantic);
    }
    private static final class VerifiedSemanticExactBexValue
            implements BexValue {
        private final String blueId;
        private final BexValue semantic;
        private VerifiedSemanticExactBexValue(String blueId,
                BexValue semantic) {
            this.blueId = Objects.requireNonNull(blueId, "blueId");
            this.semantic = Objects.requireNonNull(semantic, "semantic");
        }
        @Override public boolean isExact() { return true; }
        @Override public String exactBlueId() { return blueId; }
        @Override public boolean isUndefined() { return semantic.isUndefined(); }
        @Override public boolean isNull() { return semantic.isNull(); }
        @Override public boolean isScalar() { return semantic.isScalar(); }
        @Override public boolean isObject() { return semantic.isObject(); }
        @Override public boolean isList() { return semantic.isList(); }
        @Override public BexValue get(String key) { return semantic.get(key); }
        @Override public BexValue at(List<String> segments) { return semantic.at(segments); }
        @Override public BexValue at(String pointer) { return semantic.at(pointer); }
        @Override public String asText() { return semantic.asText(); }
        @Override public BigInteger asInteger() { return semantic.asInteger(); }
        @Override public BigDecimal asNumber() { return semantic.asNumber(); }
        @Override public boolean asBoolean() { return semantic.asBoolean(); }
        @Override public List<String> keys() { return semantic.keys(); }
        @Override public int size() { return semantic.size(); }
        @Override public Node toNode() { return semantic.toNode(); }
        @Override public Object toSimple() { return semantic.toSimple(); }
    }
    /**
     * Keeps BEX pointer traversal on the invocation-owned processor view.
     * A fragmented descendant is therefore demanded through the strict
     * request-local provider instead of a BEX engine's construction provider.
     */
    private final class ProcessorDocumentCursor implements BexValue {
        private final String absolutePointer;
        private final BexValue delegate;
        private ProcessorDocumentCursor(String absolutePointer,
                BexValue delegate) {
            this.absolutePointer = Objects.requireNonNull(
                    absolutePointer, "absolutePointer");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public boolean isExact() { return delegate.isExact(); }
        @Override public String exactBlueId() { return delegate.exactBlueId(); }
        @Override public boolean isUndefined() { return delegate.isUndefined(); }
        @Override public boolean isNull() { return delegate.isNull(); }
        @Override public boolean isScalar() { return delegate.isScalar(); }
        @Override public boolean isObject() { return delegate.isObject(); }
        @Override public boolean isList() { return delegate.isList(); }

        @Override
        public BexValue get(String key) {
            String childPointer = JsonPointer.append(absolutePointer, key);
            BexValue local;
            try {
                local = delegate.get(key);
            } catch (RuntimeException failure) {
                if (!isUnavailableExactSemantic(failure)) throw failure;
                local = null;
            }
            if (local != null
                    && !local.isUndefined()
                    && hasSemanticContent(local))
                return cursorFor(childPointer, local);
            return cursorAt(childPointer);
        }
        private boolean hasSemanticContent(BexValue value) {
            return ScopedProcessorExecutionContextBexDocumentView.this
                    .hasSemanticContent(value);
        }
        @Override public BexValue at(List<String> pointerSegments) {
            BexValue current = this;
            for (String segment : pointerSegments) {
                current = current.get(segment);
                if (current.isUndefined()) return current;
            }
            return current;
        }
        @Override public BexValue at(String pointer) {
            return at(JsonPointer.split(pointer)); }
        @Override public String asText() { return delegate.asText(); }
        @Override public BigInteger asInteger() { return delegate.asInteger(); }
        @Override public BigDecimal asNumber() { return delegate.asNumber(); }
        @Override public boolean asBoolean() { return delegate.asBoolean(); }
        @Override public List<String> keys() { return delegate.keys(); }
        @Override public int size() { return delegate.size(); }
        @Override public Node toNode() { return delegate.toNode(); }
        @Override public Object toSimple() { return delegate.toSimple(); }
    }

    interface FrozenAccess {
        String resolvePointer(String authoredPointer);

        String currentScopePath();

        FrozenNode workingCanonicalAt(String absolutePointer);

        FrozenNode workingResolvedAt(String absolutePointer);

        FrozenNode processorCanonicalAt(String absolutePointer);

        FrozenNode processorResolvedAt(String absolutePointer);

        default Node processorDocumentAt(String absolutePointer) {
            return null;
        }

        FrozenNode workingCanonicalRoot();

        FrozenNode workingResolvedRoot();
    }

    private static final class StepContextFrozenAccess
            implements FrozenAccess {
        private final BexWorkflowStepContext stepContext;
        private final ProcessorExecutionContext processorContext;

        private StepContextFrozenAccess(
                BexWorkflowStepContext stepContext) {
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
        public Node processorDocumentAt(
                String absolutePointer) {
            return processorContext.documentAt(
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
