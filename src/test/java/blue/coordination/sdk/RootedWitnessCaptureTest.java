package blue.coordination.sdk;

import blue.language.model.Node;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Reusing processor-owned witness provenance requires the complete unchanged selected view. */
final class RootedWitnessCaptureTest {
    @Test void changedCaptureCannotInheritAnImmutableWitnessProof() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var b = fixture.start("historical-b.yaml", "rcp2/b", Map.of());
            var a = fixture.start("historical-a.yaml", "rcp2/a", Map.of("peer", b.snapshot().blueId()));
            String savedA0 = a.snapshot().blueId();
            var tick = fixture.append(a, "rcp2/a", "tick", 100, "{}");
            // then
            assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().process(a, tick).entry(tick).disposition());
            var attach = fixture.append(b, "rcp2/b", "attach", 200, "child:\n  blueId: " + savedA0);
            assertEquals(EntryDisposition.APPLIED, fixture.blue.processing().process(b, attach).entry(attach).disposition());
            var view = fixture.control.selectedView(b.id());
            var docs = view.managedDocuments();
            var rows = view.occurrences();
            var roots = view.publicRootDocumentIds();
            assertTrue(fixture.control.matchesRetainedCapture(b.id(), view.graphGeneration(), docs, rows, view.components(), roots));
            // Verified inventory loading makes new Java objects with the same complete row values.
            var clonedRows = rows.stream().map(row -> ManagedOccurrenceBinding.derived(row.bindingPolicyIdentity(),
                    row.sourceDocumentId(), row.sourceAddress(), row.targetDocumentId(), row.expectedTargetBlueId(),
                    row.active(), row.pendingHistoricalEpoch()).withRepresentationCursor(row.pendingRepresentationCursor())).toList();
            assertTrue(fixture.control.matchesRetainedCapture(b.id(), view.graphGeneration(), docs, clonedRows, view.components(), roots));
            assertFalse(fixture.control.matchesRetainedCapture(b.id(), view.graphGeneration() + 1, docs, rows, view.components(), roots));
            assertFalse(fixture.control.matchesRetainedCapture(b.id(), view.graphGeneration(), docs.subList(0, 1), rows, view.components(), roots));
            assertFalse(fixture.control.matchesRetainedCapture(b.id(), view.graphGeneration(), List.of(docs.get(0), docs.get(0)), rows, view.components(), roots));
            assertFalse(fixture.control.matchesRetainedCapture(b.id(), view.graphGeneration(), docs, List.of(), view.components(), roots));
            assertFalse(fixture.control.matchesRetainedCapture(b.id(), view.graphGeneration(), docs, rows, List.of(), roots));
            assertFalse(fixture.control.matchesRetainedCapture(b.id(), view.graphGeneration(), docs, rows, view.components(), List.of()));
            for (var original : docs) {
                var altered = new ArrayList<ManagedDocumentSnapshot>();
                altered.add(copy(original, original.document().properties("uncommitted", new Node().value(true)),
                        original.epoch(), original.componentGeneration(), original.publicRoot(), original.initialized(), original.terminated()));
                altered.add(copy(original, original.document(), original.epoch() + 1, original.componentGeneration(),
                        original.publicRoot(), original.initialized(), original.terminated()));
                altered.add(copy(original, original.document(), original.epoch(), original.componentGeneration() + 1,
                        original.publicRoot(), original.initialized(), original.terminated()));
                altered.add(copy(original, original.document(), original.epoch(), original.componentGeneration(),
                        !original.publicRoot(), original.initialized(), original.terminated()));
                altered.add(copy(original, original.document(), original.epoch(), original.componentGeneration(),
                        original.publicRoot(), !original.initialized(), original.terminated()));
                altered.add(copy(original, original.document(), original.epoch(), original.componentGeneration(),
                        original.publicRoot(), original.initialized(), !original.terminated()));
                for (var mutation : altered) {
                    var proposed = docs.stream().map(doc -> doc.documentId().equals(original.documentId()) ? mutation : doc).toList();
                    assertFalse(fixture.control.matchesRetainedCapture(b.id(), view.graphGeneration(), proposed, rows, view.components(), roots));
                }
            }
            for (var original : rows) {
                var mutated = ManagedOccurrenceBinding.derived(original.bindingPolicyIdentity(), original.sourceDocumentId(),
                        original.sourceAddress(), original.targetDocumentId(), original.expectedTargetBlueId(),
                        !original.active(), original.active() ? 0L : null);
                var proposed = rows.stream().map(row -> row == original ? mutated : row).toList();
                assertFalse(fixture.control.matchesRetainedCapture(b.id(), view.graphGeneration(), docs, proposed, view.components(), roots));
            }
            assertEquals(view.closureIdentity(), fixture.control.selectedView(b.id()).closureIdentity());
            assertEquals(1L, fixture.blue.advanced().auditDocument(a.id()).epoch());
            assertEquals(1L, fixture.blue.advanced().auditDocument(b.id()).epoch());
        }
    }

    private static ManagedDocumentSnapshot copy(ManagedDocumentSnapshot original, Node body, long epoch,
            long generation, boolean publicRoot, boolean initialized, boolean terminated) {
        return new ManagedDocumentSnapshot(original.documentId(), original.blueId(), body, initialized, terminated,
                publicRoot, epoch, generation);
    }
}
