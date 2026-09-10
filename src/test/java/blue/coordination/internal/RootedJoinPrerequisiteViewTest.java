package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RootedJoinPrerequisiteViewTest {
    @Test void retainedIntermediateReturnPathRequiresItsEarlierInputBeforeTheJoinFreezes() throws Exception {
        try (var f = new Fixture()) {
            var a = f.start("A"); var b = f.start("B"); var x = f.start("X");
            f.attach(x, "b", b); f.settle(x);
            f.attach(a, "x", x); f.settle(a);
            var detached = f.append(x, "detach", "edge: b");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(x).entry(detached).disposition());
            f.settle(x);
            assertFalse(f.engine.documents().require(x.id()).rootedView().snapshot().occurrences().stream()
                    .anyMatch(row -> row.active() && row.sourceDocumentId().value().equals(x.id().value())
                            && row.targetDocumentId().value().equals(b.id().value())));
            assertTrue(f.engine.documents().require(a.id()).rootedView().snapshot().occurrences().stream()
                    .anyMatch(row -> row.active() && row.sourceDocumentId().value().equals(x.id().value())
                            && row.targetDocumentId().value().equals(b.id().value())));
            var join = f.append(b, "attach", "edge: a\nsource: {blueId: " + f.authored.get(a.id()) + "}");
            var before = List.of(f.history(a), f.history(b), f.history(x));
            var waiting = f.blue.processing().processNext(b);
            assertEquals(EntryDisposition.NEEDS_RESOURCES, waiting.entry(join).disposition());
            assertEquals(before, List.of(f.history(a), f.history(b), f.history(x)));
            assertTrue(f.blue.advanced().auditManagedCatchUpPlans(b.id()).isEmpty(), "No frozen source plan may be admitted ahead of the prerequisite");
            CoordinationTestControl.attach(f.engine).restartFromStores();
            var earlier = f.blue.processing().processNext(a);
            assertEquals(EntryDisposition.APPLIED, earlier.entry(detached).disposition());
            assertEquals(before.get(2), f.history(x), "Source-owned progress is never replayed into a second X receipt");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(b).entry(join).disposition());
            f.settle(b);
            assertTrue(f.blue.advanced().auditManagedDocumentReadiness(b.id()).orElseThrow().ready());
            assertEquals(before.get(2), f.history(x));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Map<String, String> exact = new LinkedHashMap<>();
        final Map<DocumentId, String> authored = new LinkedHashMap<>();
        final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds()
                .exactNodeProvider(id -> Optional.ofNullable(exact.get(id))).build();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        final blue.coordination.sdk.TimelineHandle timeline = blue.timelines().register("rooted/join-eligibility", "alice");

        DocumentHandle start(String name) throws Exception {
            String template;
            try (var in = getClass().getResourceAsStream("/rooted/node-graph.template.json")) {
                template = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            }
            var source = template.replace("<NODE>", name).replace("<NAMESPACE>", "join-eligibility").replace("<TIMELINE>", "rooted/join-eligibility");
            var value = blue.values().yaml(source);
            exact.put(value.blueId(), value.json());
            var handle = blue.documents().admitStaticProcessEmbedded(source, ActivationPolicy.fromNow()).document("root");
            authored.put(handle.id(), value.blueId());
            return handle;
        }

        EntryHandle append(DocumentHandle target, String operation, String request) {
            return blue.operations().on(target).from(timeline).call(operation).through("owner").requestYaml(request).submit();
        }

        EntryHandle attach(DocumentHandle owner, String path, DocumentHandle source) {
            var entry = append(owner, "attach", "edge: " + path + "\nsource: {blueId: " + authored.get(source.id()) + "}");
            assertEquals(EntryDisposition.APPLIED, blue.processing().processNext(owner).entry(entry).disposition());
            return entry;
        }

        void settle(DocumentHandle owner) {
            for (int i = 0; i < 24; i++) {
                var result = blue.processing().processNext(owner);
                assertFalse(result.blocked(), result.diagnostic().toString());
                if (result.quiescent()) return;
            }
            fail("Bounded actual fixture did not settle");
        }

        List<String> history(DocumentHandle handle) {
            return blue.advanced().auditManagedEpochs(handle.id()).stream().map(receipt -> receipt.receiptIdentity()).toList();
        }
        @Override public void close() { blue.close(); }
    }
}
