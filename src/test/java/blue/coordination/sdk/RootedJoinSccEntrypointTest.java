package blue.coordination.sdk;

import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import blue.coordination.internal.RootedJoinSccEntrypointProbe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A point handle in the original receiver SCC is not a request to execute an independent root. */
final class RootedJoinSccEntrypointTest {
    private static final String TIMELINE = "witness-forwarding/alice";

    @Test void eitherOriginalOwnerSelectsTheSameJointTerminalButAcquiredConsumerStillWaits() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var roots = new LinkedHashMap<String, DocumentHandle>();
            var resources = new ArrayList<ExactValue>();
            String template = RootedSdkFixture.resource("node-graph.template.json");
            for (String name : List.of("A", "B", "D", "C")) {
                String authored = template.replace("<NODE>", name).replace("<NAMESPACE>", "witness-forwarding")
                        .replace("<TIMELINE>", TIMELINE);
                var exact = f.blue.values().yaml(authored);
                resources.add(exact.unwrap()); f.exact.put(exact.blueId(), exact.json());
                roots.put(name, f.startYaml(authored, TIMELINE));
            }
            var a = roots.get("A"); var b = roots.get("B"); var d = roots.get("D"); var c = roots.get("C");
            prefix(f, b, "attach", 100, attachment("d", d));
            prefix(f, d, "attach", 125, attachment("b", b));
            prefix(f, b, "attach", 150, attachment("c", c));
            prefix(f, a, "attach", 175, attachment("b", b));
            prefix(f, a, "emit", 200, "to: C\nnext: B");
            prefix(f, a, "touch", 250, "{}");
            var entry = f.append(c, TIMELINE, "attach", 300, attachment("a", a), true);
            var originalB = f.control.capture(b.id(), entry.blueId(), null);
            var originalD = f.control.capture(d.id(), entry.blueId(), null);
            assertEquals(originalB.invocationIdentity(), originalD.invocationIdentity(),
                    "The established receiver SCC has one original calculation through either member");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(c, entry).entry(entry).disposition());
            boolean stopped = false;
            for (int step = 0; step < 64; step++) {
                var next = f.blue.processing().processNext(c);
                if (next.blocked()) { stopped = true; break; }
                assertFalse(next.quiescent(), "C cannot swallow the unexecuted receiving SCC's original input");
            }
            assertTrue(stopped);
            assertEquals(List.of(0L, 0L, 0L, 1L), counts(f, roots));
            assertEquals(originalB.invocationIdentity(), f.control.capture(b.id(), entry.blueId(), null).invocationIdentity());
            assertEquals(originalD.invocationIdentity(), f.control.capture(d.id(), entry.blueId(), null).invocationIdentity());
            var receivers = List.of(b, d).stream().sorted(java.util.Comparator.comparing(DocumentHandle::id)).toList();
            var canonical = receivers.get(0); var alternate = receivers.get(1);
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(canonical, entry).entry(entry).disposition());
            var probe = new RootedJoinSccEntrypointProbe(f.blue.advanced().rawEngine());
            for (int step = 0; step < 64 && !probe.select(canonical.id()).joint(); step++) {
                var input = probe.select(canonical.id()).input();
                assertNotNull(input, "The receiver prefix must have its actual selected local input");
                assertTrue(f.control.retainedTerminals(canonical.id()).stream()
                        .noneMatch(terminal -> terminal.result().invocationIdentity().equals(input.invocationIdentity())));
                var next = f.blue.processing().processNext(canonical);
                var retained = f.control.retainedTerminals(canonical.id()).stream()
                        .filter(terminal -> terminal.result().invocationIdentity().equals(input.invocationIdentity())).toList();
                assertEquals(1, retained.size(), "Progress must publish the exact selected terminal, even without a numbered revision");
                assertEquals(input.invocationIdentity(), retained.get(0).input().invocationIdentity());
                assertTrue(retained.get(0).result().commits());
                assertFalse(next.quiescent(), "The still-open independent C plan needs the real joint terminal");
            }
            var selected = probe.select(canonical.id());
            assertTrue(selected.joint()); assertFalse(selected.blocked());
            assertEquals(Set.of(b.id(), d.id()), Set.copyOf(selected.entryOwners()));
            assertEquals(canonical.id(), selected.localRoot());
            assertEquals(c.id(), selected.work().consumerDocumentId());
            assertEquals(List.of(0L, 1L, 0L, 1L), counts(f, roots));
            var before = state(f, roots);
            var cSelection = probe.select(c.id());
            assertTrue(cSelection.blocked()); assertFalse(cSelection.joint());
            var blocked = f.blue.processing().processNext(c);
            assertTrue(blocked.blocked()); assertEquals(0L, blocked.stats().committedTransitions());
            assertEquals(0L, blocked.stats().gas()); assertEquals(before, state(f, roots));
            var same = probe.select(alternate.id());
            assertFalse(same.blocked(), "A noncanonical handle in the same original SCC is a valid entrypoint");
            assertTrue(same.joint());
            assertEquals(selected.localRoot(), same.localRoot());
            assertEquals(selected.entryOwners(), same.entryOwners());
            assertEquals(selected.work().workIdentity(), same.work().workIdentity());
            assertEquals(selected.input().invocationIdentity(), same.input().invocationIdentity());
            var publicSelection = f.blue.advanced().auditNextRootProcessingSelection(canonical);
            assertEquals(blue.coordination.api.ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION, publicSelection.kind());
            assertEquals(selected.work(), publicSelection.managedEpochApplicationWork().orElseThrow());
            assertTrue(publicSelection.rootedRetainedRoot().isEmpty(), "Joint publication is not local-only retained work");
            assertEquals(publicSelection, f.blue.advanced().auditNextRootProcessingSelection(alternate));
            assertEquals(blue.coordination.api.ProcessingSelection.none(), f.blue.advanced().auditNextRootProcessingSelection(c));
            assertEquals(before, state(f, roots), "Comparing entrypoint selections is read-only");
            var histories = roots.values().stream().map(f::history).toList();
            for (var root : roots.values()) for (var receipt : f.blue.advanced().auditManagedEpochs(root.id())) {
                resources.add(receipt.afterDocument().unwrap());
                receipt.emittedEvents().forEach(event -> resources.add(event.exactEvent().unwrap()));
                receipt.sourceEntry().flatMap(TimelineEntrySnapshot::request).ifPresent(value -> resources.add(value.unwrap()));
            }
            var reference = RootedCalculationFixture.freshRootedReference(same.input(), resources);
            assertTrue(reference.commits());
            assertEquals(Set.of(b.id().value(), d.id().value()), reference.rootedProjection().context()
                    .entryOwners().stream().map(id -> id.value()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(roots.values().stream().map(root -> root.id().value()).collect(java.util.stream.Collectors.toSet()),
                    reference.rootedProjection().ownedDocumentIds().stream().map(id -> id.value()).collect(java.util.stream.Collectors.toSet()));
            // when
            var published = f.blue.processing().processNext(alternate);
            // then
            assertFalse(published.blocked(), String.valueOf(published.diagnostic()));
            var actual = published.managedEpochApplicationAttempts().get(0);
            assertTrue(actual.published()); assertFalse(actual.replayed());
            assertEquals(selected.work().workIdentity(), actual.work().workIdentity());
            var projection = actual.attempt().processResult();
            assertTrue(projection.commits());
            var retained = f.control.retainedTerminals(alternate.id()).stream()
                    .filter(terminal -> terminal.result().invocationIdentity().equals(projection.invocationIdentity())).toList();
            assertEquals(1, retained.size());
            assertEquals(same.input().invocationIdentity(), retained.get(0).input().invocationIdentity());
            var result = retained.get(0).result();
            assertEquals(projection.outputClosureIdentity(), result.outputClosureIdentity());
            assertEquals(projection.totalGas(), result.totalGas());
            assertEquals(projection.gasTraceIdentity(), result.gasTraceIdentity());
            var receipt = actual.receipt().orElseThrow();
            assertEquals(selected.work().workIdentity(), receipt.workIdentity());
            assertEquals(result.invocationIdentity(), receipt.contractsInvocationIdentity());
            assertEquals(List.of(receipt), published.managedEpochApplications());
            assertEquals(same.input().invocationIdentity(), result.invocationIdentity());
            assertEquals(reference.outputClosureIdentity(), result.outputClosureIdentity());
            assertEquals(reference.publicEventsIdentity(), result.publicEventsIdentity());
            assertEquals(reference.managedTransitionReceiptsIdentity(), result.managedTransitionReceiptsIdentity());
            assertEquals(reference.checkpointWritesIdentity(), result.checkpointWritesIdentity());
            assertEquals(reference.commitCompanion().companionIdentity(), result.commitCompanion().companionIdentity());
            assertEquals(reference.totalGas(), result.totalGas()); assertEquals(reference.gasTraceIdentity(), result.gasTraceIdentity());
            assertEquals(List.of(0L, 1L, 0L, 1L), counts(f, roots));
            assertEquals(1L, f.blue.advanced().auditManagedEpochs(c.id()).stream()
                    .flatMap(sourceReceipt -> sourceReceipt.emittedEvents().stream()).filter(event ->
                            "B".equals(event.exactEvent().scalarAt("/to")) && "stop".equals(event.exactEvent().scalarAt("/next"))).count());
            int index = 0;
            for (var root : roots.values()) {
                var prefix = histories.get(index++);
                assertEquals(prefix, f.history(root).subList(0, prefix.size()));
                assertTrue(f.blue.advanced().auditManagedCatchUpPlans(root.id()).stream()
                        .allMatch(plan -> plan.status() == ManagedCatchUpStatus.COMPLETE));
                assertTrue(f.blue.advanced().auditManagedDocumentReadiness(root.id()).orElseThrow().ready());
            }
            var settled = state(f, roots);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            for (var receiver : receivers) {
                assertTrue(f.blue.processing().processNext(receiver).quiescent());
                assertEquals(settled, state(f, roots));
            }
        }
    }

    private static void prefix(RootedSdkFixture f, DocumentHandle root, String operation, long time, String request) {
        var entry = f.append(root, TIMELINE, operation, time, request, true);
        assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(root, entry).entry(entry).disposition());
        for (int step = 0; step < 64; step++) {
            var next = f.blue.processing().processNext(root);
            assertFalse(next.blocked(), String.valueOf(next.diagnostic()));
            if (next.quiescent()) return;
        }
        fail("Unchanged setup prefix did not settle");
    }

    private static String attachment(String edge, DocumentHandle source) {
        return "edge: " + edge + "\nsource: {blueId: " + source.id().value() + "}";
    }
    private static List<Long> counts(RootedSdkFixture f, Map<String, DocumentHandle> roots) {
        return roots.values().stream().map(root -> ((Number) ExactBlueValue.wrap(f.blue.advanced()
                .auditDocument(root.id()).current()).scalarAt("/observed")).longValue()).toList();
    }
    private static List<Object> state(RootedSdkFixture f, Map<String, DocumentHandle> roots) {
        return roots.values().stream().map(root -> (Object) List.of(f.blue.advanced().auditDocument(root.id()).epoch(),
                f.blue.advanced().auditDocument(root.id()).blueId(), f.history(root),
                f.blue.advanced().auditManagedCatchUpPlans(root.id()).stream().map(plan -> plan.snapshotIdentity()).toList())).toList();
    }
}
