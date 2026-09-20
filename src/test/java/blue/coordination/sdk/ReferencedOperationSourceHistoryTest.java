package blue.coordination.sdk;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

final class ReferencedOperationSourceHistoryTest {
    static Stream<Arguments> targets() {
        return Stream.of(false, true).flatMap(reference -> Stream.of("current-exact", "current-lineage",
                "foreign-exact", "foreign-lineage", "old-exact", "old-lineage")
                .map(target -> Arguments.of(reference, target)));
    }
    @ParameterizedTest @MethodSource("targets")
    void earlierSourceRequirementHonorsResolvedTarget(boolean reference, String mode) throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            String before = f.retain(source);
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of());
            String target = mode.startsWith("foreign") ? f.blue.values().providerContentYaml("name: unrelated").blueId()
                    : mode.startsWith("old") ? f.blue.advanced().auditDocument(source.id()).authoredInitialBlueId() : before;
            var message = f.blue.values().providerContentYaml("type: Coordination/Operation Request\noperation: tick\nchannel: owner\n"
                    + "document: {blueId: " + target + "}\nrequireExactDocumentVersion: " + mode.endsWith("exact") + "\nrequest: {}");
            f.exact.put(message.blueId(), message.json());
            var event = f.blue.values().providerContentYaml("""
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/source}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    timestamp: 20
                    message:
                    """ + (reference ? "blueId: " + message.blueId() : message.json()).indent(2));
            var entry = f.blue.events().from(f.timelines.get("rcp2/source")).exact(event).submit();
            var attach = f.append(parent, "rcp2/parent", "attach", 30, "child: {blueId: " + before + "}");
            boolean eligible = mode.startsWith("current") || mode.equals("old-lineage");
            // when
            var outcome = f.blue.processing().processNextStage(parent).entry(attach);
            // then
            assertEquals(eligible ? EntryDisposition.NEEDS_RESOURCES : EntryDisposition.APPLIED, outcome.disposition());
            var prerequisites = f.blue.advanced().sourceHistoryPrerequisites(parent);
            assertEquals(eligible ? 1 : 0, prerequisites.size());
            assertEquals(before, source.snapshot().blueId());
            if (eligible) {
                assertEquals(entry.blueId(), prerequisites.get(0).entryBlueId());
                var completed = f.blue.advanced().processSourceHistoryPrerequisite(prerequisites.get(0));
                assertEquals(1, completed.processing().orElseThrow().processedEntries().size());
                assertEquals(1L, source.snapshot().longAt("/counter"));
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNextStage(parent).entry(attach).disposition());
            } else assertEquals(0L, source.snapshot().longAt("/counter"));
            assertEquals(event.json(), f.blue.advanced().auditTimelineEntry(entry.blueId()).orElseThrow().exact().json());
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void exactTargetUsesRetainedViewAfterSourcePublishes(boolean reference) throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var source = f.start("source.yaml", "rcp2/source", Map.of());
            String selected = f.retain(source);
            var parent = f.start("parent.yaml", "rcp2/parent", Map.of("child", selected));
            var message = f.blue.values().providerContentYaml("type: Coordination/Operation Request\noperation: tick\nchannel: owner\n"
                    + "document: {blueId: " + selected + "}\nrequireExactDocumentVersion: true\nrequest: {}");
            f.exact.put(message.blueId(), message.json());
            var event = f.blue.values().providerContentYaml("""
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/source}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    timestamp: 100
                    message:
                    """ + (reference ? "blueId: " + message.blueId() : message.json()).indent(2));
            var input = f.blue.events().from(f.timelines.get("rcp2/source")).exact(event).submit();
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNextStage(source).entry(input).disposition());
            String advanced = source.snapshot().blueId(); var history = f.history(source);
            // when
            var retained = f.blue.processing().processNextStage(parent);
            // then
            assertEquals(EntryDisposition.APPLIED, retained.entry(input).disposition());
            assertEquals(1L, parent.snapshot().longAt("/seen"));
            assertEquals(advanced, source.snapshot().blueId()); assertEquals(history, f.history(source));
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, f.blue.processing().processNextStage(source).disposition());
            assertEquals(event.json(), f.blue.advanced().auditTimelineEntry(input.blueId()).orElseThrow().exact().json());
        }
    }
}
