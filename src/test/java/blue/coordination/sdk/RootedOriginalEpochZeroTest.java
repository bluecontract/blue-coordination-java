package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Saved authored references import initialization without republishing source zero. */
final class RootedOriginalEpochZeroTest {
    @Test
    void separatelyStartedPairUsesBothSavedOriginalsAndRestarts() throws IOException {
        // given
        boolean inline = false;
        // when
        var phases = pair(inline);
        // then
        assertEquals(java.util.List.of("done", "relayed"), phases);
    }

    @Test
    void inlineSavedSourceStartsBeforeAnyEntryAndCanBeReattachedAfterTheJoin() throws IOException {
        // given
        boolean inline = true;
        // when
        var phases = pair(inline);
        // then
        assertEquals(java.util.List.of("done", "relayed"), phases);
    }

    private static java.util.List<String> pair(boolean inline) throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            String bYaml = RootedSdkFixture.resource("cycle-b.yaml").replace("ownerChannel", "owner");
            String aYaml = RootedSdkFixture.resource("cycle-a.yaml").replace("ownerChannel", "owner");
            if (inline) aYaml += "\npeer:\n" + bYaml.indent(2);
            var originalA = blue.values().yaml(aYaml);
            var originalB = blue.values().yaml(bYaml);
            fixture.exact.put(originalA.blueId(), originalA.json());
            fixture.exact.put(originalB.blueId(), originalB.json());
            var b = fixture.startYaml(bYaml, "rcp2/cycle");
            var a = fixture.startYaml(aYaml, "rcp2/cycle");
            assertEquals(originalA.blueId(), a.id().value());
            assertEquals(originalB.blueId(), b.id().value());
            var initialBHistory = fixture.history(b);
            if (inline) {
                var initial = blue.processing().processNext(a);
                assertEquals(1, initial.managedEpochApplications().size());
                assertTrue(initial.quiescent());
                assertEquals(initialBHistory, fixture.history(b));
                assertTrue(blue.advanced().auditTimelineEntries().isEmpty());
            } else {
                var attach = fixture.append(a, "rcp2/cycle", "attachB", 10, "b:\n  blueId: " + originalB.blueId());
                assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(a).entry(attach).disposition());
                var initialized = blue.processing().processNext(a);
                assertEquals(1, initialized.managedEpochApplications().size());
                assertTrue(initialized.quiescent());
                assertEquals(initialBHistory, fixture.history(b));
            }
            var connect = fixture.append(b, "rcp2/cycle", "connectA", 20, "a:\n  blueId: " + originalA.blueId());
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(b).entry(connect).disposition());
            for (int i = 0; i < 8 && !blue.advanced().auditManagedOccurrence(b.id(), "/peer").orElseThrow().active(); i++)
                assertEquals(1, blue.processing().processNext(b).managedEpochApplications().size());
            assertTrue(blue.advanced().auditManagedOccurrence(a.id(), "/peer").orElseThrow().active());
            assertTrue(blue.advanced().auditManagedOccurrence(b.id(), "/peer").orElseThrow().active());
            assertTrue(blue.processing().processNext(a).quiescent());
            if (inline) {
                var reattach = fixture.append(a, "rcp2/cycle", "attachB", 30, "b:\n  blueId: " + originalB.blueId());
                assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(a).entry(reattach).disposition());
                for (int i = 0; i < 8 && !blue.advanced().auditManagedOccurrence(a.id(), "/peer").orElseThrow().active(); i++)
                    assertEquals(1, blue.processing().processNext(a).managedEpochApplications().size());
                assertTrue(blue.advanced().auditManagedOccurrence(a.id(), "/peer").orElseThrow().active());
            }
            for (var doc : java.util.List.of(a, b))
                assertTrue(blue.advanced().auditManagedEpochs(doc.id()).stream().allMatch(e -> e.emittedEvents().isEmpty()));
            var finite = fixture.append(a, "rcp2/cycle", "startFinite", 40, "{}");
            var propagated = blue.processing().processNext(a).entry(finite);
            assertEquals(EntryDisposition.APPLIED, propagated.disposition());
            assertEquals("done", a.snapshot().textAt("/phase"));
            assertEquals("relayed", b.snapshot().textAt("/phase"));
            assertEquals(2, propagated.publicEvents().size());
            var heads = java.util.List.of(a.snapshot().blueId(), b.snapshot().blueId());
            var histories = java.util.List.of(fixture.history(a), fixture.history(b));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(heads, java.util.List.of(a.snapshot().blueId(), b.snapshot().blueId()));
            assertEquals(histories, java.util.List.of(fixture.history(a), fixture.history(b)));
            assertTrue(blue.processing().processNext(a).quiescent());
            assertTrue(blue.processing().processNext(b).quiescent());
            return java.util.List.of(a.snapshot().textAt("/phase"), b.snapshot().textAt("/phase"));
        }
    }

    @Test
    void savedAuthoredSourceAtZeroPreservesItsRoutesHistoryAndNextLiveInput() throws IOException {
        // given
        try (var fixture = new RootedSdkFixture()) {
            // when
            var blue = fixture.blue;
            var sourceValue = blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            fixture.exact.put(sourceValue.blueId(), sourceValue.json());
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            // then
            assertEquals(sourceValue.blueId(), source.id().value());
            String sourceHead = source.snapshot().blueId();
            var sourceHistory = fixture.history(source);
            var attach = fixture.append(parent, "rcp2/parent", "attach", 20,
                    "child:\n  blueId: " + sourceValue.blueId());
            var accepted = blue.processing().processNext(parent);
            assertEquals(EntryDisposition.APPLIED, accepted.entry(attach).disposition());
            assertFalse(accepted.quiescent());
            var pending = blue.advanced().auditManagedOccurrence(parent.id(), "/child").orElseThrow();
            assertFalse(pending.active());
            var binding = blue.advanced().closureExecution(accepted.entry(attach).closures().get(0).closureId())
                    .orElseThrow().occurrenceBindings().stream().filter(row -> row.sourceDocumentId().value()
                            .equals(parent.id().value()) && row.sourcePath().equals("/child")).findFirst().orElseThrow();
            assertEquals(Long.valueOf(-1), binding.pendingHistoricalEpoch());
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            var imported = blue.processing().processNext(parent);
            assertEquals(1, imported.managedEpochApplications().size());
            assertEquals(0L, imported.managedEpochApplicationAttempts().get(0).work().sourceEpoch());
            assertTrue(imported.quiescent());
            assertTrue(blue.advanced().auditManagedOccurrence(parent.id(), "/child").orElseThrow().active());
            assertEquals(sourceHead, source.snapshot().blueId());
            assertEquals(sourceHistory, fixture.history(source));
            assertEquals(0L, blue.advanced().auditDocument(source.id()).epoch());
            assertTrue(blue.advanced().auditManagedEpoch(parent.id(), imported.managedEpochApplications()
                    .get(0).consumerRevisionEpoch()).orElseThrow().emittedEvents().isEmpty());
            var tick = fixture.append(source, "rcp2/source", "tick", 30, "{}");
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(parent).entry(tick).disposition());
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            assertEquals(sourceHistory, fixture.history(source));
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(source).entry(tick).disposition());
            assertEquals(1L, source.snapshot().longAt("/counter"));
            String parentHead = parent.snapshot().blueId();
            var parentHistory = fixture.history(parent);
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(parentHead, parent.snapshot().blueId());
            assertEquals(parentHistory, fixture.history(parent));
            assertTrue(blue.processing().processNext(parent).quiescent());
            assertTrue(blue.processing().processNext(source).quiescent());
        }
    }
}
