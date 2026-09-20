package blue.coordination.sdk;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Reference representation must not widen a verified operation's receiver set. */
final class ReferencedOperationTargetTest {
    private static final String TL = "review/shared";
    static Stream<Arguments> cases() {
        return Stream.of(false, true).flatMap(supplied -> Stream.of("broadcast", "target-lineage",
                "target-exact", "foreign-lineage", "foreign-exact", "stale-lineage", "stale-exact",
                "target-omitted").map(mode -> Arguments.of(mode, supplied)));
    }
    @ParameterizedTest @MethodSource("cases")
    void exactRepresentationPreservesReceiverRestrictions(String mode, boolean supplied) {
        // given
        var inline = run(false, mode, supplied);
        // when
        var referenced = run(true, mode, supplied);
        // then
        long expectedA = mode.startsWith("foreign") || mode.equals("stale-exact") ? 0 : 7;
        long expectedC = mode.equals("broadcast") ? 7 : 0;
        assertAll(() -> assertEquals(inline.identity(), referenced.identity(), "Same entire entry BlueId"),
                () -> assertEquals(expectedA, inline.a()), () -> assertEquals(expectedC, inline.c()),
                () -> assertEquals(expectedA, referenced.a()), () -> assertEquals(expectedC, referenced.c()));
    }
    private static Observation run(boolean referenced, String mode, boolean supplied) {
        Map<String, String> provider = new HashMap<>();
        try (var sdk = BlueCoordination.builder().contentDerivedDocumentIds()
                .exactNodeProvider(id -> Optional.ofNullable(provider.get(id))).build()) {
            var timeline = sdk.timelines().register(TL, "alice");
            var a = sdk.documents().admitStaticProcessEmbedded(document("A"), ActivationPolicy.importFullHistory()).document("root");
            var c = sdk.documents().admitStaticProcessEmbedded(document("C"), ActivationPolicy.importFullHistory()).document("root");
            String target = mode.startsWith("foreign") ? sdk.values().providerContentYaml("name: NonexistentTarget").blueId()
                    : mode.startsWith("stale") ? sdk.advanced().auditDocument(a.id()).authoredInitialBlueId() : a.snapshot().blueId();
            if (mode.startsWith("stale")) assertNotEquals(target, a.snapshot().blueId());
            String routing = mode.equals("broadcast") ? "" : "document: {blueId: " + target + "}\n"
                    + (mode.equals("target-omitted") ? "" : "requireExactDocumentVersion: " + mode.endsWith("exact") + "\n");
            var body = sdk.values().providerContentYaml("type: Coordination/Operation Request\noperation: credit\nchannel: owner\n" + routing);
            provider.put(body.blueId(), body.json());
            var exact = event(sdk, referenced ? "blueId: " + body.blueId() : body.json());
            var entry = sdk.events().from(timeline).exact(exact).submit();
            if (supplied) { sdk.processing().processStage(a, entry); sdk.processing().processStage(c, entry); }
            else { sdk.processing().processNextStage(a); sdk.processing().processNextStage(c); }
            var retained = sdk.advanced().auditTimelineEntry(entry.blueId()).orElseThrow();
            assertEquals(exact.json(), retained.exact().json(), "Original envelope retained unchanged");
            return new Observation(entry.blueId(), a.snapshot().longAt("/counter"), c.snapshot().longAt("/counter"));
        }
    }
    private record Observation(String identity, long a, long c) { }
    static String document(String name) {
        return """
            name: %s
            counter: 0
            contracts:
              owner:
                type: Coordination/Timeline Channel
                timeline: {type: MyOS/MyOS Timeline, timelineId: review/shared}
                actor: {type: MyOS/Principal Actor, accountId: alice}
              credit:
                type: Coordination/Sequential Workflow Operation
                channel: owner
                steps:
                  - type: Coordination/Compute
                    do:
                      - $appendChange: {op: replace, path: /counter, val: 7}
                      - $return: true
            """.formatted(name);
    }
    static ExactBlueValue event(BlueCoordination sdk, String message) {
        return sdk.values().providerContentYaml("""
            type: Coordination/Timeline Entry
            timeline: {type: MyOS/MyOS Timeline, timelineId: review/shared}
            actor: {type: MyOS/Principal Actor, accountId: alice}
            timestamp: 1700000000000000
            message:
            """ + message.indent(2));
    }
}
