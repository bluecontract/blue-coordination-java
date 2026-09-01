package blue.coordination.processor.bex;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedProcessorExecutionContextBexDocumentViewTest {

    @Test
    void shouldPreserveResolvedSemanticsForWorkingDocumentDirectRead() {
        // given
        ExactValue exact = exactInteger(7);
        RecordingFrozenAccess access =
                new RecordingFrozenAccess();
        access.workingCanonical = exact.canonical;
        access.workingResolved = exact.resolved;
        ScopedProcessorExecutionContextBexDocumentView view =
                new ScopedProcessorExecutionContextBexDocumentView(
                        access, null);

        // when
        BexValue canonicalRead =
                view.canonicalAt("/counter");
        BexValue resolvedRead =
                view.resolvedAt("/counter");

        // then
        assertExactInteger(
                canonicalRead, exact.blueId, 7);
        assertExactInteger(
                resolvedRead, exact.blueId, 7);
        assertEquals(4, access.workingDirectReads);
        assertEquals(0, access.processorDirectReads);
        assertEquals(0, access.rootReads);
    }

    @Test
    void shouldUseProcessorSnapshotPairWhenWorkingValueIsCollapsedReference() {
        // given
        ExactValue exact = exactObject(
                "processor snapshot");
        RecordingFrozenAccess access =
                new RecordingFrozenAccess();
        access.workingCanonical = exact.canonical;
        access.workingResolved = exact.canonical;
        access.processorCanonical = exact.canonical;
        access.processorResolved = exact.resolved;
        ScopedProcessorExecutionContextBexDocumentView view =
                new ScopedProcessorExecutionContextBexDocumentView(
                        access, null);

        // when
        BexValue read =
                view.resolvedAt("/status");

        // then
        assertTrue(read.isExact());
        assertEquals(exact.blueId, read.exactBlueId());
        assertEquals(
                "processor snapshot",
                read.get("marker").asText());
        assertEquals(2, access.workingDirectReads);
        assertEquals(2, access.processorDirectReads);
        assertEquals(0, access.rootReads);
    }

    @Test
    void shouldExposeVerifiedCyclicMemberSemanticBody() {
        // given
        ExactValue body = exactObject("verified cyclic member");
        String memberBlueId = body.blueId + "#0";
        RecordingFrozenAccess access = new RecordingFrozenAccess();
        access.workingCanonical = FrozenNode.fromNode(
                new Node().blueId(memberBlueId));
        access.workingResolved = body.resolved;
        ScopedProcessorExecutionContextBexDocumentView view =
                new ScopedProcessorExecutionContextBexDocumentView(
                        access, null);

        // when
        BexValue read = view.resolvedAt("/counterValue");

        // then
        assertTrue(read.isExact());
        assertEquals(memberBlueId, read.exactBlueId());
        assertTrue(read.isObject());
        assertEquals("verified cyclic member",
                read.get("marker").asText());
    }

    @Test
    void shouldPairCanonicalAndResolvedWorkingRootsDuringFallback() {
        // given
        ExactValue exact = exactObject(
                "root fallback");
        RecordingFrozenAccess access =
                new RecordingFrozenAccess();
        access.workingCanonicalRoot =
                FrozenNode.fromNode(
                        new Node().properties(
                                "nested",
                                new Node().blueId(
                                        exact.blueId)));
        access.workingResolvedRoot =
                FrozenNode.fromResolvedNode(
                        new Node().properties(
                                "nested",
                                exact.resolved.toNode()));
        ScopedProcessorExecutionContextBexDocumentView view =
                new ScopedProcessorExecutionContextBexDocumentView(
                        access, null);

        // when
        BexValue read =
                view.canonicalAt("/nested");

        // then
        assertTrue(read.isExact());
        assertEquals(exact.blueId, read.exactBlueId());
        assertEquals(
                "root fallback",
                read.get("marker").asText());
        assertEquals(2, access.workingDirectReads);
        assertEquals(2, access.processorDirectReads);
        assertEquals(2, access.rootReads);
    }

    @Test
    void shouldDemandProcessorChildBeforeUsingCollapsedRootChild() {
        // given
        ExactValue counter = exactInteger(7);
        CollapsedChildFrozenAccess access =
                new CollapsedChildFrozenAccess(
                        "counter", counter);
        ScopedProcessorExecutionContextBexDocumentView view =
                new ScopedProcessorExecutionContextBexDocumentView(
                        access, null);

        // when
        BexValue read = view.resolvedAt("/")
                .get("counter");

        // then
        assertExactInteger(
                read, counter.blueId, 7);
        assertEquals(1, access.processorResolvedReads);
    }

    @Test
    void shouldNotPairCanonicalChildIdentityWithDifferentResolvedBody() {
        // given
        FrozenNode canonicalChild = FrozenNode.fromNode(
                new Node().properties(
                        "marker",
                        new Node().value("canonical")));
        FrozenNode resolvedChild = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "marker",
                        new Node().value("resolved")));
        CanonicalChildFrozenAccess access =
                new CanonicalChildFrozenAccess(
                        "candidateC",
                        canonicalChild,
                        resolvedChild);
        ScopedProcessorExecutionContextBexDocumentView view =
                new ScopedProcessorExecutionContextBexDocumentView(
                        access, null);

        // when
        BexValue child = view.resolvedAt("/")
                .get("candidateC");

        // then
        assertTrue(child.isExact());
        assertEquals(resolvedChild.blueId(), child.exactBlueId());
        assertEquals("resolved", child.get("marker").asText());
    }

    @Test
    void shouldExposeResolvedBooleanWithoutCursorScalarCoercion() {
        // given
        ExactValue exact = exactBoolean(false);
        RecordingFrozenAccess access =
                new RecordingFrozenAccess();
        access.workingCanonical = exact.canonical;
        access.workingResolved = exact.resolved;
        ScopedProcessorExecutionContextBexDocumentView view =
                new ScopedProcessorExecutionContextBexDocumentView(
                        access, null);

        // when
        BexValue read = view.resolvedAt("/payNoteAttached");

        // then
        assertTrue(read.isScalar());
        assertTrue(BexValues.equal(
                read,
                BexValues.scalar(false)));
    }

    @Test
    void shouldReadCollapsedLeafThroughResolvedProcessorScope() {
        // given
        ExactValue amount = exactInteger(0);
        ResolvedScopeFrozenAccess access =
                new ResolvedScopeFrozenAccess(amount);
        ScopedProcessorExecutionContextBexDocumentView view =
                new ScopedProcessorExecutionContextBexDocumentView(
                        access, null);

        // when
        BexValue read = view.resolvedAt(
                "/embedded/authorization/amount");

        // then
        assertExactInteger(read, amount.blueId, 0);
        assertEquals(1, access.scopeResolvedReads);
    }

    @Test
    void shouldFallBackWhenCollapsedExactParentCannotOpenChild() {
        // given
        ExactValue parent = exactObject("verified child");
        CollapsedExactParentFrozenAccess access =
                new CollapsedExactParentFrozenAccess(parent);
        ScopedProcessorExecutionContextBexDocumentView view =
                new ScopedProcessorExecutionContextBexDocumentView(
                        access, null);

        // when
        BexValue child = view.resolvedAt("/counterValue")
                .get("marker");

        // then
        assertEquals("verified child", child.asText());
        assertEquals(1, access.materializedChildReads);
    }

    private static void assertExactInteger(
            BexValue actual,
            String expectedBlueId,
            int expectedValue) {
        assertTrue(actual.isExact());
        assertEquals(
                expectedBlueId,
                actual.exactBlueId());
        assertEquals(
                BigInteger.valueOf(expectedValue),
                actual.asInteger());
    }

    private static ExactValue exactInteger(
            int value) {
        return exact(
                new Node().value(value));
    }

    private static ExactValue exactBoolean(
            boolean value) {
        return exact(
                new Node().value(value));
    }

    private static ExactValue exactObject(
            String marker) {
        return exact(
                new Node().properties(
                        "marker",
                        new Node().value(marker)));
    }

    private static ExactValue exact(
            Node resolvedNode) {
        FrozenNode resolved =
                FrozenNode.fromResolvedNode(
                        resolvedNode);
        String blueId =
                resolved.blueId();
        return new ExactValue(
                FrozenNode.fromNode(
                        new Node().blueId(
                                blueId)),
                resolved,
                blueId);
    }

    private static final class ExactValue {
        private final FrozenNode canonical;
        private final FrozenNode resolved;
        private final String blueId;

        private ExactValue(
                FrozenNode canonical,
                FrozenNode resolved,
                String blueId) {
            this.canonical = canonical;
            this.resolved = resolved;
            this.blueId = blueId;
        }
    }

    private static final class RecordingFrozenAccess
            implements ScopedProcessorExecutionContextBexDocumentView
                    .FrozenAccess {
        private FrozenNode workingCanonical;
        private FrozenNode workingResolved;
        private FrozenNode processorCanonical;
        private FrozenNode processorResolved;
        private FrozenNode workingCanonicalRoot;
        private FrozenNode workingResolvedRoot;
        private int workingDirectReads;
        private int processorDirectReads;
        private int rootReads;

        @Override
        public String resolvePointer(
                String authoredPointer) {
            return authoredPointer;
        }

        @Override
        public String currentScopePath() {
            return "/";
        }

        @Override
        public FrozenNode workingCanonicalAt(
                String absolutePointer) {
            workingDirectReads++;
            return workingCanonical;
        }

        @Override
        public FrozenNode workingResolvedAt(
                String absolutePointer) {
            workingDirectReads++;
            return workingResolved;
        }

        @Override
        public FrozenNode processorCanonicalAt(
                String absolutePointer) {
            processorDirectReads++;
            return processorCanonical;
        }

        @Override
        public FrozenNode processorResolvedAt(
                String absolutePointer) {
            processorDirectReads++;
            return processorResolved;
        }

        @Override
        public FrozenNode workingCanonicalRoot() {
            rootReads++;
            return workingCanonicalRoot;
        }

        @Override
        public FrozenNode workingResolvedRoot() {
            rootReads++;
            return workingResolvedRoot;
        }
    }

    private static final class CollapsedChildFrozenAccess
            implements ScopedProcessorExecutionContextBexDocumentView
                    .FrozenAccess {
        private final String childPointer;
        private final ExactValue child;
        private final FrozenNode collapsedRoot;
        private int processorResolvedReads;

        private CollapsedChildFrozenAccess(
                String childKey,
                ExactValue child) {
            this.childPointer = "/" + childKey;
            this.child = child;
            this.collapsedRoot = FrozenNode.fromResolvedNode(
                    new Node().properties(
                            childKey,
                            new Node().blueId(
                                    child.blueId)));
        }

        @Override
        public String resolvePointer(
                String authoredPointer) {
            return authoredPointer;
        }

        @Override
        public String currentScopePath() {
            return "/";
        }

        @Override
        public FrozenNode workingCanonicalAt(
                String absolutePointer) {
            return "/".equals(absolutePointer)
                    ? collapsedRoot
                    : childPointer.equals(absolutePointer)
                            ? child.canonical
                            : null;
        }

        @Override
        public FrozenNode workingResolvedAt(
                String absolutePointer) {
            return "/".equals(absolutePointer)
                    ? collapsedRoot
                    : childPointer.equals(absolutePointer)
                            ? child.canonical
                            : null;
        }

        @Override
        public FrozenNode processorCanonicalAt(
                String absolutePointer) {
            return childPointer.equals(absolutePointer)
                    ? child.canonical
                    : null;
        }

        @Override
        public FrozenNode processorResolvedAt(
                String absolutePointer) {
            processorResolvedReads++;
            return childPointer.equals(absolutePointer)
                    ? child.resolved
                    : null;
        }

        @Override
        public FrozenNode workingCanonicalRoot() {
            return collapsedRoot;
        }

        @Override
        public FrozenNode workingResolvedRoot() {
            return collapsedRoot;
        }
    }

    private static final class CanonicalChildFrozenAccess
            implements ScopedProcessorExecutionContextBexDocumentView
                    .FrozenAccess {
        private final String childPointer;
        private final FrozenNode canonicalChild;
        private final FrozenNode resolvedChild;
        private final FrozenNode canonicalRoot;
        private final FrozenNode resolvedRoot;

        private CanonicalChildFrozenAccess(
                String childKey,
                FrozenNode canonicalChild,
                FrozenNode resolvedChild) {
            this.childPointer = "/" + childKey;
            this.canonicalChild = canonicalChild;
            this.resolvedChild = resolvedChild;
            this.canonicalRoot = FrozenNode.fromNode(
                    new Node().properties(
                            childKey,
                            new Node().blueId(
                                    canonicalChild.blueId())));
            this.resolvedRoot = FrozenNode.fromResolvedNode(
                    new Node().properties(
                            childKey,
                            resolvedChild.toNode()));
        }

        @Override
        public String resolvePointer(
                String authoredPointer) {
            return authoredPointer;
        }

        @Override
        public String currentScopePath() {
            return "/";
        }

        @Override
        public FrozenNode workingCanonicalAt(
                String absolutePointer) {
            if ("/".equals(absolutePointer)) {
                return canonicalRoot;
            }
            return childPointer.equals(absolutePointer)
                    ? FrozenNode.fromNode(
                            new Node().blueId(
                                    canonicalChild.blueId()))
                    : null;
        }

        @Override
        public FrozenNode workingResolvedAt(
                String absolutePointer) {
            if ("/".equals(absolutePointer)) {
                return resolvedRoot;
            }
            return childPointer.equals(absolutePointer)
                    ? resolvedChild
                    : null;
        }

        @Override
        public FrozenNode processorCanonicalAt(
                String absolutePointer) {
            return null;
        }

        @Override
        public FrozenNode processorResolvedAt(
                String absolutePointer) {
            return null;
        }

        @Override
        public FrozenNode workingCanonicalRoot() {
            return canonicalRoot;
        }

        @Override
        public FrozenNode workingResolvedRoot() {
            return resolvedRoot;
        }
    }

    private static final class ResolvedScopeFrozenAccess
            implements ScopedProcessorExecutionContextBexDocumentView
                    .FrozenAccess {
        private static final String SCOPE = "/embedded";
        private static final String LEAF =
                "/embedded/authorization/amount";

        private final ExactValue amount;
        private final FrozenNode resolvedScope;
        private int scopeResolvedReads;

        private ResolvedScopeFrozenAccess(
                ExactValue amount) {
            this.amount = amount;
            this.resolvedScope = FrozenNode.fromResolvedNode(
                    new Node().properties(
                            "authorization",
                            new Node().properties(
                                    "amount",
                                    amount.resolved.toNode())));
        }

        @Override
        public String resolvePointer(
                String authoredPointer) {
            return authoredPointer;
        }

        @Override
        public String currentScopePath() {
            return SCOPE;
        }

        @Override
        public FrozenNode workingCanonicalAt(
                String absolutePointer) {
            return LEAF.equals(absolutePointer)
                    ? amount.canonical
                    : null;
        }

        @Override
        public FrozenNode workingResolvedAt(
                String absolutePointer) {
            return LEAF.equals(absolutePointer)
                    ? amount.canonical
                    : null;
        }

        @Override
        public FrozenNode processorCanonicalAt(
                String absolutePointer) {
            return LEAF.equals(absolutePointer)
                    ? amount.canonical
                    : null;
        }

        @Override
        public FrozenNode processorResolvedAt(
                String absolutePointer) {
            if (SCOPE.equals(absolutePointer)) {
                scopeResolvedReads++;
                return resolvedScope;
            }
            return LEAF.equals(absolutePointer)
                    ? amount.canonical
                    : null;
        }

        @Override
        public FrozenNode workingCanonicalRoot() {
            return null;
        }

        @Override
        public FrozenNode workingResolvedRoot() {
            return null;
        }
    }

    private static final class CollapsedExactParentFrozenAccess
            implements ScopedProcessorExecutionContextBexDocumentView
                    .FrozenAccess {
        private static final String PARENT = "/counterValue";
        private static final String CHILD = "/counterValue/marker";

        private final FrozenNode collapsed;
        private final FrozenNode child;
        private int materializedChildReads;

        private CollapsedExactParentFrozenAccess(ExactValue parent) {
            collapsed = parent.canonical;
            child = parent.resolved.property("marker");
        }

        @Override
        public String resolvePointer(String authoredPointer) {
            return authoredPointer;
        }

        @Override
        public String currentScopePath() {
            return "/";
        }

        @Override
        public FrozenNode workingCanonicalAt(String absolutePointer) {
            return PARENT.equals(absolutePointer) ? collapsed : null;
        }

        @Override
        public FrozenNode workingResolvedAt(String absolutePointer) {
            return PARENT.equals(absolutePointer) ? collapsed : null;
        }

        @Override
        public FrozenNode processorCanonicalAt(String absolutePointer) {
            return PARENT.equals(absolutePointer) ? collapsed : null;
        }

        @Override
        public FrozenNode processorResolvedAt(String absolutePointer) {
            if (CHILD.equals(absolutePointer)) {
                materializedChildReads++;
                return child;
            }
            return PARENT.equals(absolutePointer) ? collapsed : null;
        }

        @Override
        public FrozenNode workingCanonicalRoot() {
            return null;
        }

        @Override
        public FrozenNode workingResolvedRoot() {
            return null;
        }
    }
}
