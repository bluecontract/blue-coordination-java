package blue.coordination.processor.bex;

import blue.bex.value.BexValue;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedProcessorExecutionContextBexDocumentViewTest {

    @Test
    void shouldPreserveResolvedSemanticsForWorkingDocumentDirectRead() {
        // Given
        ExactValue exact = exactInteger(7);
        RecordingFrozenAccess access =
                new RecordingFrozenAccess();
        access.workingCanonical = exact.canonical;
        access.workingResolved = exact.resolved;
        ScopedProcessorExecutionContextBexDocumentView view =
                new ScopedProcessorExecutionContextBexDocumentView(
                        access, null);

        // When
        BexValue canonicalRead =
                view.canonicalAt("/counter");
        BexValue resolvedRead =
                view.resolvedAt("/counter");

        // Then
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
        // Given
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

        // When
        BexValue read =
                view.resolvedAt("/status");

        // Then
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
    void shouldPairCanonicalAndResolvedWorkingRootsDuringFallback() {
        // Given
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

        // When
        BexValue read =
                view.canonicalAt("/nested");

        // Then
        assertTrue(read.isExact());
        assertEquals(exact.blueId, read.exactBlueId());
        assertEquals(
                "root fallback",
                read.get("marker").asText());
        assertEquals(2, access.workingDirectReads);
        assertEquals(2, access.processorDirectReads);
        assertEquals(2, access.rootReads);
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
}
