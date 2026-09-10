package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual eventless containing-reference changes retain a strict independent proof owner. */
final class RootedReadOnlyReferenceRebindTest {
    @Test void unmatchedSourceEventsRequireAuthenticatedSameEpochParentRebinds() throws Exception {
        // given
        String sourceYaml = RootedSdkFixture.resource("source.yaml") + """
                  emitUnmatched:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: RCP2/Unmatched
                          - $return: true
                """;
        // when
        try (var f = new RootedSdkFixture()) {
            var source = f.startYaml(sourceYaml, "rcp2/source");
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of("child", f.retain(source)));
            var sourceHead = source.snapshot();
            var sourceHistory = f.history(source);
            long parentEpoch = parent.snapshot().epoch();
            var parentHistory = f.history(parent);
            var entries = new ArrayList<EntryHandle>();
            for (long timestamp : List.of(100L, 200L)) {
                var before = parent.snapshot().blueId();
                var entry = f.append(source, "rcp2/source", "emitUnmatched", timestamp, "{}");
                entries.add(entry);
                var drained = f.blue.processing().processNext(parent);
                var applied = drained.entry(entry);
                // then
                assertEquals(EntryDisposition.APPLIED, applied.disposition());
                assertTrue(drained.quiescent());
                assertTrue(applied.publicEvents().isEmpty());
                assertNotEquals(before, parent.snapshot().blueId());
                assertEquals(parentEpoch, parent.snapshot().epoch());
                assertEquals(parentHistory, f.history(parent));
                assertEquals(sourceHead.blueId(), source.snapshot().blueId());
                assertEquals(sourceHistory, f.history(source));
                assertEquals(0L, parent.snapshot().longAt("/seen"));
                assertEquals(List.of(), parent.snapshot().valueAt("/log").copyNode().getItems());
            }
            assertEquals(2, verifyReferenceProofNegatives(f, parent),
                    "Both real read-only-source inputs must exercise every reference-proof negative");
            String parentHead = parent.snapshot().blueId();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(parentHead, parent.snapshot().blueId());
            assertEquals(parentHistory, f.history(parent));
            assertEquals(sourceHistory, f.history(source));
            assertTrue(f.blue.processing().processNext(parent).quiescent());
            for (var entry : entries) {
                var actual = f.blue.processing().processNext(source).entry(entry);
                assertEquals(EntryDisposition.APPLIED, actual.disposition());
                assertEquals(1, actual.publicEvents().size());
                assertEquals(parentHead, parent.snapshot().blueId());
                assertEquals(parentHistory, f.history(parent));
            }
            assertEquals(3, f.history(source).size());
            var publishedSourceHistory = f.history(source);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(f.blue.processing().processNext(source).quiescent());
            assertTrue(f.blue.processing().processNext(parent).quiescent());
            assertEquals(publishedSourceHistory, f.history(source));
            assertEquals(parentHead, parent.snapshot().blueId());
            assertEquals(parentHistory, f.history(parent));
        }
    }

    private static int verifyReferenceProofNegatives(RootedSdkFixture f, DocumentHandle owner) {
        var terminals = f.control.retainedTerminals(owner.id());
        var id = new blue.language.processor.closure.DocumentId(owner.id().value());
        int verified = 0;
        for (var terminal : terminals) {
            var result = terminal.result();
            var before = terminal.input().snapshot().managedDocument(id);
            var after = result.resultingDocuments().stream().filter(doc -> doc.documentId().equals(id)).findFirst();
            var targets = terminal.input().directDeliveries().stream().map(delivery -> delivery.targetDocumentId())
                    .distinct().sorted().toList();
            if (!result.commits() || before == null || after.isEmpty() || before.epoch() != after.orElseThrow().epoch()
                    || before.blueId().equals(after.orElseThrow().afterBlueId()) || targets.isEmpty()
                    || targets.contains(id) || !result.rootedProjection().owns(id)
                    || targets.stream().anyMatch(target -> result.rootedProjection().owns(target))) continue;
            var direct = targets.stream().map(target -> blue.coordination.api.DocumentId.of(target.value())).toList();
            assertTrue(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, owner.id(), direct));
            assertFalse(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, owner.id(), List.of()));
            assertFalse(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, owner.id(), List.of(owner.id())));
            var extra = new java.util.ArrayList<>(direct); extra.add(owner.id());
            assertFalse(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, owner.id(), extra));
            var duplicate = new java.util.ArrayList<>(direct); duplicate.add(direct.get(0));
            assertFalse(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, owner.id(), duplicate));
            assertFalse(f.control.verifiesRetainedReferenceRebind(terminal.identity(), result, direct.get(0), direct));
            var other = terminals.stream().filter(value -> !value.identity().equals(terminal.identity())).findFirst().orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> f.control.verifiesRetainedReferenceRebind(
                    terminal.identity(), other.result(), owner.id(), direct));
            verified++;
        }
        return verified;
    }

}
