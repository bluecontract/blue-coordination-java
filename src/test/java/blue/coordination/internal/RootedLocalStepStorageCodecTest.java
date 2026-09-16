package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.language.processor.closure.ManagedRevisionCause;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static org.junit.jupiter.api.Assertions.*;

/** The qualified diamond's fresh terminal retains both its historical source and selected peer guards. */
final class RootedLocalStepStorageCodecTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final String TIMELINE = "witness-forwarding/alice";

    @Test void coldTerminalRetainsOriginalCaptureAndSelectedPeerFencesWithoutInventingRoutes() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var roots = new LinkedHashMap<String, DocumentHandle>();
            var earlier = new LinkedHashMap<DocumentId, String>();
            for (String name : List.of("A", "B", "D", "C")) {
                String yaml = resource("node-graph.template.json").replace("<NODE>", name)
                        .replace("<NAMESPACE>", "witness-forwarding").replace("<TIMELINE>", TIMELINE);
                var authored = f.blue.values().yaml(yaml); f.exact.put(authored.blueId(), authored.json());
                var root = f.start(yaml, TIMELINE, ActivationPolicy.importFullHistory());
                roots.put(name, root); earlier.put(root.id(), f.storage.retain(f.engine.documents().require(root.id())));
            }
            var a = roots.get("A"); var b = roots.get("B"); var d = roots.get("D"); var c = roots.get("C");
            prefix(f, d, "attach", 100, attachment("c", c)); prefix(f, b, "attach", 125, attachment("c", c));
            prefix(f, a, "attach", 150, attachment("b", b)); prefix(f, a, "attach", 175, attachment("d", d));
            prefix(f, a, "emit", 200, "to: C\nnext: B"); prefix(f, a, "emit", 210, "to: C\nnext: D");
            prefix(f, a, "touch", 250, "{}");
            assertEquals(List.of(11L, 2L, 2L, 0L), epochs(f, roots));
            var entry = append(f, c, "attach", 300, attachment("a", a));
            assertEquals("5G1qitMzxCuQ3UpCuJyh2fZimmbdRNwsthMYJUWZBjVL", entry.blueId());
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(c, entry).entry(entry).disposition());
            var safety = new RootedJoinPublicationSafetyProbe(f.engine);
            boolean terminal = false;
            for (int i = 0; i < 32; i++) {
                if (safety.registeredInput(c.id()).cause() instanceof ManagedRevisionCause cause
                        && cause.fromEpoch() == 10L && cause.toEpoch() == 11L
                        && cause.successorRepresentationCause().isEmpty()) { terminal = true; break; }
                assertFalse(f.blue.processing().processNext(c).quiescent());
            }
            assertTrue(terminal);
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(d, entry).entry(entry).disposition());
            var probe = new RootedDiamondAcquisitionProbe(f.blue);
            terminal = false;
            for (int i = 0; i < 64; i++) {
                if (probe.atRegisteredTerminal(d.id(), c.id())) { terminal = true; break; }
                assertFalse(f.blue.processing().processNext(d).quiescent());
            }
            assertTrue(terminal); assertEquals(List.of(11L, 2L, 14L, 12L), epochs(f, roots));
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(b).entry(entry).disposition());
            var driver = new RootedCheckpointDriver(f.engine.documents(), f.engine.contractsClosureAdapter());
            boolean verified = false;
            for (int i = 0; i < 64; i++) {
                var root = List.of(b, a, d, c).stream().filter(candidate -> f.blue.advanced()
                        .auditNextRootProcessingSelection(candidate).kind() != blue.coordination.api.ProcessingSelection.Kind.NONE)
                        .findFirst().orElseThrow();
                var selected = driver.select(root.id(), f.engine.auditTimelineEntries());
                var local = selected.localHistorical();
                if (local != null && !local.capturedState().peerPrefixesForStorage().isEmpty()) {
                    assertNotNull(selected.historical(), "Selected peer acquisition belongs to the actual registered terminal");
                    assertNotNull(local.capturedState().originalCaptureForStorage());
                    var snapshot = local.invocation().input().snapshot();
                    assertEquals(11L, snapshot.managedDocument(ContractsClosureAdapter.closureId(a.id())).epoch());
                    assertEquals(14L, snapshot.managedDocument(ContractsClosureAdapter.closureId(d.id())).epoch());
                    assertColdProof(f, local, earlier);
                    verified = true; break;
                }
                assertFalse(f.blue.processing().processNext(root).quiescent());
            }
            assertTrue(verified, "The actual terminal must exercise a selected peer, not an empty synthetic capture");
        }
    }

    private static void assertColdProof(DocumentSessionStorageTest.Fixture f, RootedLocalHistory.Step original,
            Map<DocumentId, String> earlier) {
        original.requireCurrentInput(f.engine.documents());
        var codec = new RootedLocalStepStorageCodec(MAX, 128);
        var bytes = codec.encode(original, f.storage::retainView);
        var state = f.engine.documents().storedState();
        var addresses = new LinkedHashMap<DocumentId, String>();
        state.sessions().forEach((id, session) -> addresses.put(id, f.storage.retain(session)));
        var storage = new DocumentSessionStorage(f.bytes.copy(),
                new DocumentSessionStorage.Limits(MAX, 128, 128L * 1024 * 1024));
        int reads = f.providerReads.get();
        try (var scope = storage.openScope()) {
            var restored = codec.decode(bytes, scope);
            var sessions = new LinkedHashMap<DocumentId, DocumentSession>();
            addresses.forEach((id, address) -> sessions.put(id, scope.open(id, address)));
            var cold = new InMemoryDocumentStore(new EngineMetrics(), state.withSessions(sessions,
                    state.lineageIndex(), state.componentIndex(), state.componentIndexGeneration()));
            assertNotSame(original, restored);
            assertDoesNotThrow(() -> restored.requireCurrentInput(cold));
            assertArrayEquals(bytes, codec.encode(restored, scope::addressOf));
            assertNotNull(restored.capturedState().originalCaptureForStorage());
            assertEquals(original.capturedState().peerPrefixesForStorage().keySet(),
                    restored.capturedState().peerPrefixesForStorage().keySet());
            assertThrows(RuntimeException.class, restored.capturedState()::routes);
            var owners = new java.util.LinkedHashSet<>(restored.capturedState().peerPrefixesForStorage().keySet());
            owners.add(restored.capturedState().anchor());
            for (DocumentId owner : owners) {
                var stale = new LinkedHashMap<>(sessions);
                stale.put(owner, scope.open(owner, earlier.get(owner)));
                // Isolated session-only lookup for the actual Step guard, not a
                // claimed full durable store with newer receipts and stale heads.
                var changed = new InMemoryDocumentStore();
                stale.values().forEach(changed::insert);
                assertThrows(RuntimeException.class, () -> restored.requireCurrentInput(changed),
                        "A cold proof cannot hide changed anchor/peer publication " + owner);
            }
        }
        assertEquals(reads, f.providerReads.get(), "Cold proof and publication guards never resolve provider content");
        assertDoesNotThrow(() -> original.requireCurrentInput(f.engine.documents()));
        assertNotNull(original.capturedState().routes(), "Fresh selected routing remains available only on the live capture");
    }

    private static List<Long> epochs(DocumentSessionStorageTest.Fixture f, Map<String, DocumentHandle> roots) {
        return roots.values().stream().map(root -> f.engine.documents().require(root.id()).epoch()).toList();
    }
    private static String attachment(String edge, DocumentHandle source) {
        return "edge: " + edge + "\nsource: {blueId: " + source.id().value() + "}";
    }
    private static void prefix(DocumentSessionStorageTest.Fixture f, DocumentHandle root,
            String operation, long time, String request) {
        var entry = append(f, root, operation, time, request);
        assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(root, entry).entry(entry).disposition());
        for (int i = 0; i < 16; i++) {
            var next = f.blue.processing().processNext(root); assertFalse(next.blocked());
            if (next.quiescent()) return;
        }
        fail("Exact prefix did not settle");
    }
    private static EntryHandle append(DocumentSessionStorageTest.Fixture f, DocumentHandle root,
            String operation, long time, String request) {
        String yaml = """
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: %s}
                timestamp: %d
                actor: {type: MyOS/Principal Actor, accountId: alice}
                message:
                  type: Coordination/Operation Request
                  document: {blueId: %s}
                  requireExactDocumentVersion: true
                  operation: %s
                  channel: owner
                  request:
                %s
                """.formatted(TIMELINE, time, root.snapshot().blueId(), operation, request.indent(4));
        if (f.previous.containsKey(TIMELINE)) yaml += "\nprevEntry: {blueId: " + f.previous.get(TIMELINE) + "}\n";
        var result = f.blue.events().from(f.timelines.get(TIMELINE)).exact(f.blue.values().yaml(yaml)).submit();
        f.previous.put(TIMELINE, result.blueId()); return result;
    }
}
