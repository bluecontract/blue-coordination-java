package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The normal public journal driver owns scheduling across separately admitted roots. */
final class RootedSavedOriginalJournalRingTest {
    @Test void savedOriginalRingCompletesWithOrderedJournalSchedulingAndRestart() throws Exception {
        // given
        String template;
        // when
        try (var in = getClass().getResourceAsStream("/rooted/node-graph.template.json")) {
            template = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var f = new RootedSdkFixture()) {
            var originals = new LinkedHashMap<String, ExactBlueValue>();
            var roots = new LinkedHashMap<String, DocumentHandle>();
            for (String name : List.of("A", "B", "C")) {
                String yaml = template.replace("<NODE>", name).replace("<NAMESPACE>", "rooted-journal-ring")
                        .replace("<TIMELINE>", "rcp/journal/" + name);
                var exact = f.blue.values().yaml(yaml);
                originals.put(name, exact); f.exact.put(exact.blueId(), exact.json());
                roots.put(name, f.startYaml(yaml, "rcp/journal/" + name));
                // then
                assertEquals(exact.blueId(), roots.get(name).id().value());
            }
            int ordinal = 0;
            for (String[] edge : new String[][]{{"A", "b", "B"}, {"B", "c", "C"}, {"C", "a", "A"}}) {
                var entry = f.append(roots.get(edge[0]), "rcp/journal/" + edge[0], "attach", ++ordinal * 100L,
                        "edge: " + edge[1] + "\nsource: {blueId: " + originals.get(edge[2]).blueId() + "}");
                boolean observed = false, done = false;
                for (int step = 0; step < 32; step++) {
                    var prefixes = roots.values().stream().map(f::history).toList();
                    var result = f.blue.processing().drain(new DrainBudget(1L, 1L));
                    System.out.println("JOURNAL_RING " + String.join("/", edge) + " step=" + step
                            + " entries=" + result.entries().size() + " historical=" + result.managedEpochApplications().size()
                            + " local=" + result.rootedRetainedResults().size() + " diagnostic=" + result.diagnostic());
                    assertFalse(result.blocked(), result.diagnostic().toString());
                    var actual = result.find(entry);
                    if (actual.isPresent()) {
                        var value = actual.orElseThrow();
                        if (value.disposition() == EntryDisposition.APPLIED) observed = true;
                        else {
                            assertTrue(observed, "Transport completion cannot replace the actual successful input result");
                            assertEquals(EntryDisposition.NO_MATCH, value.disposition());
                            assertTrue(value.closures().isEmpty(), "A later transport marker performs no second invocation");
                        }
                    }
                    int i = 0;
                    for (var root : roots.values()) { var prefix = prefixes.get(i++); assertEquals(prefix, f.history(root).subList(0, prefix.size())); }
                    if (result.quiescent()) { done = true; break; }
                }
                assertTrue(done, "Ring attachment must finish within 32 actual journal selections");
                assertTrue(observed, "Original submitted input must be accounted for");
                assertTrue(f.blue.advanced().auditManagedOccurrence(roots.get(edge[0]).id(), "/peers/" + edge[1]).orElseThrow().active());
                for (var root : roots.values()) assertTrue(f.blue.advanced().auditManagedEpochs(root.id()).stream()
                        .allMatch(receipt -> receipt.emittedEvents().isEmpty()));
            }
            var heads = roots.values().stream().map(root -> root.snapshot().blueId()).toList();
            var histories = roots.values().stream().map(f::history).toList();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(heads, roots.values().stream().map(root -> root.snapshot().blueId()).toList());
            assertEquals(histories, roots.values().stream().map(f::history).toList());
            assertTrue(f.blue.processing().drain(new DrainBudget(1L, 1L)).quiescent());
            var event = f.append(roots.get("C"), "rcp/journal/C", "emit", 400L, "to: B\nnext: A");
            var delivered = f.blue.processing().drain();
            assertEquals(EntryDisposition.APPLIED, delivered.entry(event).disposition());
            assertTrue(delivered.quiescent());
            assertEquals(1L, roots.get("A").snapshot().longAt("/observed"));
            assertEquals(1L, roots.get("B").snapshot().longAt("/observed"));
            assertEquals(0L, roots.get("C").snapshot().longAt("/observed"));
            var after = roots.values().stream().map(f::history).toList();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertTrue(f.blue.processing().drain().quiescent());
            assertEquals(after, roots.values().stream().map(f::history).toList());
        }
    }
}
