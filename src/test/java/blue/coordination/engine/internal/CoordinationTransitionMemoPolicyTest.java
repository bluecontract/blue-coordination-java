package blue.coordination.engine.internal;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Safety tests for exact whole-transition memo admission. */
final class CoordinationTransitionMemoPolicyTest {

    @Test
    void shouldNotMemoizeAResourceLikeCapabilityFailure() {
        // given
        DocumentProcessingResult capabilityFailure =
                DocumentProcessingResult.capabilityFailure(
                        new Node().properties(
                                "root", new Node().value("unchanged")),
                        "provider is temporarily unavailable");

        // when
        boolean permitted = CoordinationTransitionMemoPolicy.permits(
                capabilityFailure);

        // then
        assertFalse(permitted);
    }

    @Test
    void shouldMemoizeACompletedDeterministicNonCommittingResult() {
        // given
        DocumentProcessingResult noMatch =
                DocumentProcessingResult.nonCommitting(
                        new Node().properties(
                                "root", new Node().value("unchanged")),
                        7L,
                        ProcessorStatus.NO_MATCH,
                        null);

        // when
        boolean permitted = CoordinationTransitionMemoPolicy.permits(
                noMatch);

        // then
        assertTrue(permitted);
    }

    @Test
    void shouldMemoizeACompletedCommittingResult() {
        // given
        DocumentProcessingResult success = DocumentProcessingResult.of(
                new Node().properties(
                        "root", new Node().value("changed")),
                Collections.<Node>emptyList(),
                11L);

        // when
        boolean permitted = CoordinationTransitionMemoPolicy.permits(
                success);

        // then
        assertTrue(permitted);
    }
}
