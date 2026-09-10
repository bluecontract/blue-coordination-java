package blue.coordination.sdk;

import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.internal.CoordinationTestControl;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Proposed admission negatives: all inputs and histories come from real SDK work. */
final class RootedJoinPrerequisiteNegativeTest {
    @Test void unavailableEarlierSourceBarrierCannotBecomeAFrozenJoinAnchor() throws Exception {
        // given
        try (var s = new Scenario(250L)) {
            // when
            var work = s.work;
            // then
            assertEquals(1L, work.sourceEpoch());
            assertEquals("250", s.f.control.registeredHistoryBarrierOrder(work).components().get(0).toString());
            assertEquals("100", s.f.blue.advanced().auditManagedEpoch(s.x.id(), 1L).orElseThrow()
                    .sourceOrder().orElseThrow().components().get(0).toString());
            var before = s.records();
            s.f.control.deferRegisteredOwnedHistory(work);
            assertEquals(before, s.records(), "Availability deferral must not edit heads, exact views or histories");
            var join = s.appendJoin();
            assertUnchangedWait(s, join, "EARLIER_SOURCE_HISTORY_UNAVAILABLE", work.barrierIdentity());
            CoordinationTestControl.attach(s.f.blue.advanced().rawEngine()).restartFromStores();
            assertUnchangedWait(s, join, "EARLIER_SOURCE_HISTORY_UNAVAILABLE", work.barrierIdentity());
        }
    }

    @Test void requiredTimelineCompletenessRejectsBackdatedJoinBeforeProcessing() throws Exception {
        // given
        try (var s = new Scenario(500L)) {
            // when
            assertEquals("500", s.f.control.registeredHistoryBarrierOrder(s.work).components().get(0).toString());
            // then
            assertEquals("100", s.f.blue.advanced().auditManagedEpoch(s.x.id(), 1L).orElseThrow()
                    .sourceOrder().orElseThrow().components().get(0).toString());
            var before = s.records();
            var journalBefore = s.f.blue.advanced().auditTimelineEntries().stream()
                    .map(TimelineEntrySnapshot::blueId).toList();
            String workBefore = s.f.control.registeredOwnedHistory(s.a.id()).workIdentity();
            for (boolean restart : List.of(false, true)) {
                if (restart) CoordinationTestControl.attach(s.f.blue.advanced().rawEngine()).restartFromStores();
                assertEquals(before, s.records());
                var failure = assertThrows(IllegalArgumentException.class, s::appendJoin);
                assertTrue(failure.getMessage().contains("Timeline Entry order [300,"), failure.getMessage());
                assertTrue(failure.getMessage().contains("not after its required Timeline completeness frontier [500,"),
                        failure.getMessage());
                assertEquals(before, s.records());
                assertEquals(journalBefore, s.f.blue.advanced().auditTimelineEntries().stream()
                        .map(TimelineEntrySnapshot::blueId).toList());
                assertEquals(workBefore, s.f.control.registeredOwnedHistory(s.a.id()).workIdentity());
                assertTrue(s.f.blue.advanced().auditManagedOccurrence(s.c.id(), "/peers/a").isEmpty());
            }
        }
    }

    private static EntryResult assertUnchangedWait(Scenario s, EntryHandle join, String reason, String anchor) {
        var before = s.records();
        var drained = s.f.blue.processing().processNext(s.c);
        var entry = drained.entry(join);
        assertEquals(EntryDisposition.NEEDS_RESOURCES, entry.disposition());
        assertTrue(drained.blocked());
        assertTrue(entry.closures().stream().flatMap(value -> value.resourceDemands().stream())
                .anyMatch(demand -> demand.managedResolutionStatus().orElse(null) == ClosureResult.ManagedResolutionStatus.UNPROVEN_MANAGED_HISTORY
                        && demand.managedResolutionDiagnostic().orElse("").contains(reason)
                        && demand.managedResolutionDiagnostic().orElse("").contains(anchor)), diagnostics(entry).toString());
        assertEquals(before, s.records());
        assertTrue(s.f.blue.advanced().auditManagedOccurrence(s.c.id(), "/peers/a").isEmpty());
        assertTrue(entry.publicEvents().isEmpty());
        return entry;
    }

    private static List<String> diagnostics(EntryResult result) {
        return result.closures().stream().flatMap(value -> value.resourceDemands().stream())
                .flatMap(demand -> demand.managedResolutionDiagnostic().stream()).toList();
    }

    private static final class Scenario implements AutoCloseable {
        final RootedSdkFixture f = new RootedSdkFixture();
        final Map<String, DocumentHandle> documents = new LinkedHashMap<>();
        final Map<String, ExactBlueValue> originals = new LinkedHashMap<>();
        final DocumentHandle a, b, c, x;
        final ManagedEpochApplicationWork work;

        Scenario(long xAttachmentOrder) throws Exception {
            String template;
            try (var in = RootedSavedOriginalGraphTest.class.getResourceAsStream("/rooted/node-graph.template.json")) {
                template = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String name : List.of("A", "B", "C")) {
                String yaml = template.replace("<NODE>", name).replace("<NAMESPACE>", "rooted-join-negative")
                        .replace("<TIMELINE>", "rcp/join-negative/" + name);
                var authored = f.blue.values().yaml(yaml);
                originals.put(name, authored); f.exact.put(authored.blueId(), authored.json());
                documents.put(name, f.startYaml(yaml, "rcp/join-negative/" + name));
            }
            a = documents.get("A"); b = documents.get("B"); c = documents.get("C");
            String xYaml = RootedSdkFixture.resource("source.yaml");
            var authoredX = f.blue.values().yaml(xYaml); originals.put("X", authoredX);
            f.exact.put(authoredX.blueId(), authoredX.json());
            x = f.startYaml(xYaml, "rcp2/source"); documents.put("X", x);
            var tick = f.append(x, "rcp2/source", "tick", 100L, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(x).entry(tick).disposition());
            attachAndSettle(a, "A", "b", "B", 100L);
            var bAttachment = attachAndSettle(b, "B", "c", "C", 150L);
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(a).entry(bAttachment).disposition());
            var local = f.blue.processing().processNext(a);
            assertEquals(1, local.rootedRetainedApplications().size());
            assertTrue(local.quiescent());
            var selectedB = f.control.selectedView(a.id()).managedDocument(
                    new blue.language.processor.closure.DocumentId(b.id().value()));
            assertEquals(b.snapshot().blueId(), selectedB.blueId());
            assertEquals(b.snapshot().epoch(), selectedB.epoch());
            var attachX = f.append(a, "rcp/join-negative/A", "attach", xAttachmentOrder,
                    "edge: x\nsource: {blueId: " + originals.get("X").blueId() + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(a).entry(attachX).disposition());
            var initializedX = f.blue.processing().processNext(a);
            assertEquals(1, initializedX.managedEpochApplications().size());
            assertFalse(initializedX.quiescent());
            work = f.control.registeredOwnedHistory(a.id());
            assertEquals(a.id(), work.consumerDocumentId()); assertEquals(x.id(), work.sourceDocumentId());
            assertEquals(1L, work.sourceEpoch());
        }

        private EntryHandle attachAndSettle(DocumentHandle root, String name, String path, String target, long order) {
            var entry = f.append(root, "rcp/join-negative/" + name, "attach", order,
                    "edge: " + path + "\nsource: {blueId: " + originals.get(target).blueId() + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(root).entry(entry).disposition());
            boolean done = false;
            for (int i = 0; i < 32; i++) {
                var before = records();
                var result = f.blue.processing().processNext(root);
                for (var old : before.entrySet()) {
                    var prefix = (List<?>) ((List<?>) old.getValue()).get(3);
                    assertEquals(prefix, f.history(documents.get(old.getKey())).subList(0, prefix.size()));
                }
                assertFalse(result.blocked());
                if (result.quiescent()) { done = true; break; }
            }
            assertTrue(done); return entry;
        }

        EntryHandle appendJoin() {
            return f.append(c, "rcp/join-negative/C", "attach", 300L,
                    "edge: a\nsource: {blueId: " + originals.get("A").blueId() + "}");
        }

        Map<String, Object> records() {
            var result = new LinkedHashMap<String, Object>();
            documents.forEach((name, handle) -> {
                var audit = f.blue.advanced().auditDocument(handle.id());
                result.put(name, List.of(audit.epoch(), audit.current().blueId(), ExactBlueValue.wrap(audit.current()).json(),
                        f.history(handle), f.control.selectedView(handle.id()).closureIdentity()));
            });
            return result;
        }
        @Override public void close() { f.close(); }
    }
}
