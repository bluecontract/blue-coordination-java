package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real compiled Contracts descriptors; no expanded per-child listeners. */
final class SdkCatchAllCollectionRoutingTest {
    @Test
    void omittedSourcePathRoutesRepeatedOccurrencesAndNestedEmissions() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle timeline = blue.timelines().register("f2/source", "alice");
            ClosureHandle closure = blue.documents().admit(closure(false));
            DocumentHandle root = closure.document("root");

            // when
            EntryResult direct = emit(blue, closure.document("source"), timeline);
            EntryResult nested = emit(blue, closure.document("nested"), timeline);

            // then
            assertEquals(EntryDisposition.APPLIED, direct.disposition());
            assertEquals(EntryDisposition.APPLIED, nested.disposition());
            assertEquals(8L, root.snapshot().longAt("/observed"));
            assertEquals(List.of("observed", "observed", "observed", "observed"),
                    kinds(direct));
            assertEquals(kinds(direct), kinds(nested));
            var first = blue.advanced().auditManagedOccurrence(root.id(),
                    "/members/first").orElseThrow();
            var second = blue.advanced().auditManagedOccurrence(root.id(),
                    "/members/second").orElseThrow();
            assertEquals(first.targetDocumentId(), second.targetDocumentId());
            assertEquals(1L, first.activationGeneration());
            assertEquals(1L, second.activationGeneration());
            var events = blue.advanced().auditManagedEpoch(
                    closure.document("source").id(), 1L).orElseThrow()
                    .emittedEvents();
            assertEquals(2, events.size());
            assertEquals(events.get(0).eventBlueId(), events.get(1).eventBlueId());
            assertNotEquals(events.get(0).eventOccurrenceIdentity(),
                    events.get(1).eventOccurrenceIdentity());
        }
    }

    @Test
    void discoveryOrderDoesNotChangePublicationOrderOrExactRoot() {
        // given
        List<String> forward = run(false);

        // when
        List<String> reverse = run(true);

        // then
        assertEquals(forward, reverse);
    }

    @Test
    void removingReaddingAndRetargetingPreservesOccurrenceGenerations() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle timeline = blue.timelines().register("f2/source", "alice");
            ClosureHandle closure = blue.documents().admit(closure(false));
            DocumentHandle root = closure.document("root");
            DocumentHandle source = closure.document("source");
            DocumentHandle nested = closure.document("nested");
            ExactBlueValue empty = blue.values().yaml("{}");

            // when
            replaceMembers(blue, root, timeline, empty);
            EntryResult detached = emit(blue, source, timeline);
            replaceMembers(blue, root, timeline, ExactBlueValue.wrap(
                    ExactValue.verified(
                    new blue.language.model.Node().properties(java.util.Map.of(
                            "first", source.snapshot().exact().copyNode())))));
            var readded = blue.advanced().auditManagedOccurrence(root.id(),
                    "/members/first").orElseThrow();
            EntryResult readdedEvent = emit(blue, source, timeline);
            replaceMembers(blue, root, timeline, ExactBlueValue.wrap(
                    ExactValue.verified(
                    new blue.language.model.Node().properties(java.util.Map.of(
                            "first", nested.snapshot().exact().copyNode())))));
            var retargeted = blue.advanced().auditManagedOccurrence(root.id(),
                    "/members/first").orElseThrow();
            EntryResult irrelevant = emit(blue, source, timeline);
            EntryResult retargetedEvent = emit(blue, nested, timeline);

            // then
            assertTrue(detached.publicEvents().isEmpty());
            assertEquals(2, readdedEvent.publicEvents().size());
            assertTrue(irrelevant.publicEvents().isEmpty());
            assertEquals(2, retargetedEvent.publicEvents().size());
            assertEquals(2L, readded.activationGeneration());
            assertEquals(3L, retargeted.activationGeneration());
            assertEquals(nested.id(), retargeted.targetDocumentId());
            assertFalse(blue.advanced().auditManagedOccurrence(root.id(),
                    "/members/second").orElseThrow().active());
            assertEquals(4L, root.snapshot().longAt("/observed"));
        }
    }

    private static List<String> run(boolean reverse) {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle timeline = blue.timelines().register("f2/source", "alice");
            ClosureHandle closure = blue.documents().admit(closure(reverse));
            EntryResult result = emit(blue, closure.document("source"), timeline);
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            java.util.ArrayList<String> values = new java.util.ArrayList<>(
                    result.publicEvents().stream().map(event -> event.exact().blueId())
                            .toList());
            values.add(closure.document("root").snapshot().blueId());
            return values;
        }
    }

    private static EntryResult emit(BlueCoordination blue, DocumentHandle source,
            TimelineHandle timeline) {
        return blue.operations().on(source).from(timeline).call("emit")
                .through("owner").request(request -> { }).execute();
    }

    private static void replaceMembers(BlueCoordination blue, DocumentHandle root,
            TimelineHandle timeline, ExactBlueValue members) {
        EntryResult result = blue.operations().on(root).from(timeline)
                .call("replaceMembers").through("owner")
                .request(request -> request.exact("members", members)).execute();
        assertEquals(EntryDisposition.APPLIED, result.disposition(),
                result.diagnostic().toString());
    }

    private static List<String> kinds(EntryResult result) {
        return result.publicEvents().stream()
                .map(event -> (String) event.exact().scalarAt("/kind")).toList();
    }

    private static ManagedClosure closure(boolean reverse) {
        var builder = ManagedClosure.builder();
        for (String key : reverse ? List.of("nested", "source", "root")
                : List.of("root", "source", "nested")) {
            builder.document(key, DocumentId.of("f2-" + key),
                    key.equals("root") ? root() : source(key));
        }
        return builder.bindOccurrence("root", "/members/first", "source")
                .bindOccurrence("root", "/members/second", "source")
                .bindOccurrence("source", "/nested", "nested")
                .publicRoot("root").fromNow().build();
    }

    private static String owner() {
        return """
                  owner:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: f2/source}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                """;
    }

    private static String root() {
        return """
                observed: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths: [/members]
                  everyChild:
                    type: Embedded Node Channel
                    event: {type: Coordination/Event, kind: signal}
                  observe:
                    type: Coordination/Sequential Workflow
                    channel: everyChild
                    event: {type: Coordination/Event, kind: signal}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observed
                              val: {$add: [{$document: /observed}, 1]}
                          - $appendEvent: {type: Coordination/Event, kind: observed}
                          - $return: true
                  replaceMembers:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {members: {}}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /members
                              val: {$event: /request/members}
                          - $return: true
                """ + owner();
    }

    private static String source(String key) {
        return "contracts:\n" + (key.equals("source") ? """
                  embedded:
                    type: Process Embedded
                    paths: [/nested]
                """ : "") + owner() + """
                  emit:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent: {type: Coordination/Event, kind: signal}
                          - $appendEvent: {type: Coordination/Event, kind: signal}
                          - $return: true
                """;
    }
}
