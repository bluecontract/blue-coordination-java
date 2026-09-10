package blue.coordination.sdk;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Known-red reproduction: a second historical role for already-frozen C is not transportable yet. */
final class RootedFrozenHistoricalRetargetReproductionTest {
    @Test
    void selectedC0MustRejectWithoutReplacingAlreadyFrozenC1() throws Exception {
        try (var f = new RootedSdkFixture()) {
            var b = f.start("historical-a.yaml", "rcp2/a", Map.of());
            var c = f.startYaml(RootedSdkFixture.resource("historical-a.yaml")
                    .replace("RCP2 Historical A", "Retarget source C")
                    .replace("rcp2/a", "rcp2/c"), "rcp2/c");
            String savedC0 = c.snapshot().blueId();
            var parent = f.startYaml(RootedRetargetInputTest.consumer() + """
                  attachOther:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {child: {}}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendChange:
                          op: add
                          path: /orders/other
                          val: {$binding: event/message/request/child}
                      - $return: true
                """, "rcp2/b");
            applied(f, parent, f.append(parent, "rcp2/b", "attach", 100, reference(b.snapshot().blueId())));
            applied(f, c, f.append(c, "rcp2/c", "tick", 200, "{}"));
            f.retain(c);
            applied(f, parent, f.append(parent, "rcp2/b", "attachOther", 300, reference(c.snapshot().blueId())));
            applied(f, parent, f.append(parent, "rcp2/b", "remove", 400, "{}"));
            var reserved = f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow();
            var other = f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/other").orElseThrow();
            assertFalse(reserved.active());
            assertEquals(b.id(), reserved.targetDocumentId());
            assertTrue(other.active());
            assertEquals(c.id(), other.targetDocumentId());
            var heads = List.of(parent.snapshot().blueId(), b.snapshot().blueId(), c.snapshot().blueId());
            var histories = List.of(f.history(parent), f.history(b), f.history(c));
            var entry = f.blue.operations().on(parent).from(f.timelines.get("rcp2/b"))
                    .call("attach").through("owner").requestYaml(reference(savedC0))
                    .selectManagedEpoch(ManagedEpochSelector.exact(c.id(), 0L, savedC0, "/orders/same")).submit();

            var result = f.blue.processing().processNext(parent).entry(entry);

            assertEquals(heads, List.of(parent.snapshot().blueId(), b.snapshot().blueId(), c.snapshot().blueId()));
            assertEquals(histories, List.of(f.history(parent), f.history(b), f.history(c)));
            assertEquals(reserved, f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/same").orElseThrow());
            assertEquals(other, f.blue.advanced().auditManagedOccurrence(parent.id(), "/orders/other").orElseThrow());
            // Keep the normative expectation: NEEDS_RESOURCES is the remaining gap, not success.
            assertEquals(EntryDisposition.REJECTED, result.disposition(), result.diagnostic().toString());
            assertEquals("MANAGED_OCCURRENCE_BINDING_MISSING", result.diagnostic().code());
        }
    }

    private static String reference(String id) { return "child:\n  blueId: " + id; }
    private static void applied(RootedSdkFixture f, DocumentHandle root, EntryHandle entry) {
        var result = f.blue.processing().processNext(root).entry(entry);
        assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
    }
}
