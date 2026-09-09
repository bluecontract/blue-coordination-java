package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Literal RUN-023: one exact A operation through either member, followed by splitting. */
final class RootedCycleEntrypointTest {
    @Test void alternateCycleViewsPreserveTheSameOperationAndSplitEvidence() throws IOException {
        assertEquals(run(false), run(true));
    }

    private static Outcome run(boolean throughB) throws IOException {
        try (var fixture = new RootedSdkFixture()) {
            var blue = fixture.blue;
            var b = fixture.start("cycle-b.yaml", "rcp2/cycle", Map.of());
            String aYaml = RootedSdkFixture.resource("cycle-a.yaml")
                    + "\npeer:\n  blueId: " + b.snapshot().blueId() + "\n";
            var authoredA = blue.values().yaml(aYaml);
            fixture.exact.put(authoredA.blueId(), authoredA.json());
            var a = fixture.startYaml(aYaml, "rcp2/cycle");
            var connect = append(fixture, b, "connectA", 90,
                    "a:\n  blueId: " + authoredA.blueId());
            var join = blue.processing().processNext(b);
            assertEquals(EntryDisposition.APPLIED, join.entry(connect).disposition(),
                    join.entry(connect).diagnostic().toString());
            // The saved authored value is cursor -1, not initialized A0. The
            // source's immutable epoch-zero receipt needs its own invocation.
            assertFalse(join.quiescent());
            var pending = blue.advanced().auditManagedOccurrence(b.id(), "/peer").orElseThrow();
            assertFalse(pending.active());
            var genesis = blue.processing().processNext(b);
            assertEquals(1, genesis.managedEpochApplications().size(), genesis.managedEpochApplicationAttempts().toString());
            var genesisWork = genesis.managedEpochApplicationAttempts().get(0).work();
            assertEquals(a.id(), genesisWork.sourceDocumentId());
            assertEquals(0L, genesisWork.sourceEpoch());
            assertTrue(genesis.quiescent(), "The exact epoch-zero successor must close the live join");
            assertTrue(blue.advanced().auditManagedOccurrence(a.id(), "/peer").orElseThrow().active());
            assertTrue(blue.advanced().auditManagedOccurrence(b.id(), "/peer").orElseThrow().active());
            var subject = append(fixture, a, "startFinite", 100, "{}");
            var result = blue.processing().processNext(throughB ? b : a).entry(subject);
            assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
            assertEquals("done", a.snapshot().textAt("/phase"));
            assertEquals("relayed", b.snapshot().textAt("/phase"));
            assertEquals(List.of("Tutorial/Cycle X", "Tutorial/Cycle Y"), result.publicEvents().stream()
                    .map(e -> e.exact().scalarAt("/kind")).toList());
            assertEquals(List.of(a.id(), b.id()), result.publicEvents().stream()
                    .map(e -> e.sourceDocument().orElseThrow()).toList());
            assertEquals(List.of(a.id(), b.id()).stream().sorted().toList(), result.closures().stream()
                    .flatMap(c -> c.changes().stream()).map(DocumentChange::documentId).sorted().toList());
            String terminal = result.closures().get(0).closureId();
            var retained = blue.advanced().closureExecution(terminal).orElseThrow();
            var input = blue.advanced().closureInvocation(terminal).orElseThrow();
            var rooted = retained.rootedProjection();
            assertNotNull(rooted);
            assertEquals(List.of(a.id().value(), b.id().value()).stream().sorted().toList(), rooted.context()
                    .entryOwners().stream().map(id -> id.value()).sorted().toList());
            var heads = List.of(a.snapshot().blueId(), b.snapshot().blueId());
            var history = List.of(fixture.history(a), fixture.history(b));
            var detach = append(fixture, b, "detachA", 110, "{}");
            var split = blue.processing().processNext(b).entry(detach);
            assertEquals(EntryDisposition.APPLIED, split.disposition(), split.diagnostic().toString());
            var splitResult = blue.advanced().closureExecution(split.closures().get(0).closureId()).orElseThrow();
            assertEquals(rooted.context().entryOwners(), splitResult.rootedProjection().context().entryOwners());
            assertEquals(rooted.ownedDocumentIds(), splitResult.rootedProjection().ownedDocumentIds());
            assertEquals("detached", b.snapshot().textAt("/phase"));
            var retired = blue.advanced().auditManagedOccurrence(b.id(), "/peer").orElseThrow();
            assertFalse(retired.active());
            assertEquals(a.id(), retired.targetDocumentId());
            assertEquals(pending.activationGeneration() + 1L, retired.activationGeneration());
            assertFalse(splitResult.resultingDocuments().stream().filter(d -> d.documentId().value().equals(b.id().value()))
                    .findFirst().orElseThrow().document().getProperties().containsKey("peer"));
            assertFalse(a.snapshot().blueId().contains("#"));
            assertFalse(b.snapshot().blueId().contains("#"));
            assertEquals(retained.commitCompanion().companionIdentity(), blue.advanced().closureExecution(terminal)
                    .orElseThrow().commitCompanion().companionIdentity());
            assertEquals(history.get(0), fixture.history(a).subList(0, history.get(0).size()));
            assertEquals(history.get(1), fixture.history(b).subList(0, history.get(1).size()));
            var splitHeads = List.of(a.snapshot().blueId(), b.snapshot().blueId());
            var splitHistory = List.of(fixture.history(a), fixture.history(b));
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(splitHeads, List.of(a.snapshot().blueId(), b.snapshot().blueId()));
            assertEquals(splitHistory, List.of(fixture.history(a), fixture.history(b)));
            assertTrue(blue.processing().processNext(a).quiescent());
            assertTrue(blue.processing().processNext(b).quiescent());
            return new Outcome(subject.blueId(), heads, history, input.invocationIdentity(),
                    retained.commitCompanion().companionIdentity(), retained.gasTraceIdentity(),
                    rooted.context().identity(), rooted.invocationIdentity(), rooted.deliveryBasisIdentity(),
                    rooted.companionIdentity(), splitHeads, splitHistory);
        }
    }

    private static EntryHandle append(RootedSdkFixture fixture, DocumentHandle target,
            String operation, long timestamp, String request) {
        String yaml = """
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: rcp2/cycle
                timestamp: %d
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
                message:
                  type: Coordination/Operation Request
                  document:
                    blueId: %s
                  requireExactDocumentVersion: false
                  operation: %s
                  channel: ownerChannel
                  request:
                %s
                """.formatted(timestamp, target.snapshot().blueId(), operation, request.indent(4));
        String previous = fixture.previousEntries.get("rcp2/cycle");
        if (previous != null) yaml += "\nprevEntry:\n  blueId: " + previous + "\n";
        var entry = fixture.blue.events().from(fixture.timelines.get("rcp2/cycle"))
                .exact(fixture.blue.values().yaml(yaml)).submit();
        fixture.previousEntries.put("rcp2/cycle", entry.blueId());
        return entry;
    }

    private record Outcome(String entry, List<String> heads, List<List<String>> history,
            String invocation, String companion, String gasTrace,
            String context, String rootedInvocation, String delivery, String rootedCompanion,
            List<String> splitHeads, List<List<String>> splitHistory) { }
}
