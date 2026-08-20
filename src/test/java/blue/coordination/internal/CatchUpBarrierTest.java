package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Persistence and defensive-copy tests for catch-up completion proofs. */
final class CatchUpBarrierTest {
    @Test
    void persistsPerBindingEvidenceAndCopiesItIndependently() {
        // given

        ExternalOrderKey cutoff = ExternalOrderKey.of(List.of(
                200L, "timeline", "attachment"));
        CatchUpBarrier barrier = new CatchUpBarrier(
                "barrier-1",
                DocumentId.of("parent"),
                "attachment-entry",
                cutoff);
        barrier.extend("binding-a");
        CompletenessEvidence evidence = new CompletenessEvidence(
                3L, 5L, 7L, cutoff, "surface-a");
        barrier.recordCompletenessEvidence("binding-a", evidence);
        barrier.defer("another source is unavailable");

        // when
        CatchUpBarrier copy = barrier.copy();

        // then
        assertEquals(evidence, copy.completenessEvidence("binding-a"));
        assertEquals(evidence,
                copy.completenessEvidence().get("binding-a"));
        assertEquals(CatchUpBarrier.Status.DEFERRED, copy.status());
        assertThrows(UnsupportedOperationException.class,
                () -> copy.completenessEvidence().clear());

        barrier.recordProgress(
                "binding-a",
                ExternalOrderKey.of(List.of(100L, "timeline", "history")));
        assertNull(barrier.completenessEvidence("binding-a"));
        assertEquals(evidence, copy.completenessEvidence("binding-a"));
    }

    @Test
    void rejectsEvidenceForAnotherBindingOrCutoff() {
        // given

        ExternalOrderKey cutoff = ExternalOrderKey.of(List.of(200L));
        CatchUpBarrier barrier = new CatchUpBarrier(
                "barrier-1",
                DocumentId.of("parent"),
                "attachment-entry",
                cutoff);

        // when
        barrier.extend("binding-a");

        // then
        assertThrows(IllegalArgumentException.class,
                () -> barrier.recordCompletenessEvidence(
                        "binding-b",
                        new CompletenessEvidence(
                                1L, 1L, 1L, cutoff, "surface")));
        assertThrows(IllegalArgumentException.class,
                () -> barrier.recordCompletenessEvidence(
                        "binding-a",
                        new CompletenessEvidence(
                                1L,
                                1L,
                                1L,
                                ExternalOrderKey.of(List.of(201L)),
                                "surface")));
    }
}
