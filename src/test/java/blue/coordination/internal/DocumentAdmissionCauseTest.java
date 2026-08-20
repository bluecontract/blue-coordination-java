package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Top-level initialization uses one retained, content-addressed admission cause. */
final class DocumentAdmissionCauseTest {
    @Test
    void causeBlueIdBindsAdmissionTupleAndIsStableAcrossRestart() {
        // given

        DocumentId id = DocumentId.of("admission-cause-document");

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession session = engine.start(id, """
                    documentId: admission-cause-document
                    state: authored
                    """);
            DocumentRevision revision = session.revision(0L);
            String causeId = revision.causalEntryBlueId().orElseThrow();

            // when
            WholeObjectStore objects = engine.objects();

            // then
            assertFalse(causeId.startsWith("admission|"));
            assertTrue(objects.contains(causeId));
            ExactValue cause = objects.require(causeId);
            assertEquals(id.value(), cause.frozen().at(
                    "/documentId").getValue());
            assertEquals(revision.before().orElseThrow().blueId(),
                    cause.frozen().at("/authoredState").getReferenceBlueId());
            assertEquals("FROM_NOW", cause.frozen().at(
                    "/admissionPolicy").getValue());
            assertEquals(BigInteger.ZERO,
                    cause.frozen().at("/admissionFrontier/0").getValue());
            engine.restartFromStores();
            assertEquals(causeId, engine.documents().require(id)
                    .revision(0L).causalEntryBlueId().orElseThrow());

            ExactValue authored = revision.before().orElseThrow();
            ExternalOrderKey frontier = revision.sourceOrderKey().orElseThrow();
            assertEquals(causeId, DocumentTransitionProcessor
                    .retainAdmissionCause(objects, id, authored,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            frontier));
            assertNotEquals(causeId, DocumentTransitionProcessor
                    .retainAdmissionCause(objects, DocumentId.of("other"),
                            authored, CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            frontier));
            assertNotEquals(causeId, DocumentTransitionProcessor
                    .retainAdmissionCause(objects, id,
                            ExactValue.verified(new Node().value("different")),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            frontier));
            assertNotEquals(causeId, DocumentTransitionProcessor
                    .retainAdmissionCause(objects, id, authored,
                            CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                            frontier));
            assertNotEquals(causeId, DocumentTransitionProcessor
                    .retainAdmissionCause(objects, id, authored,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            ExternalOrderKey.of(List.of(1L, "different"))));
        }
    }
}
