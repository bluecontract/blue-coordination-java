package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Coordination spec §10.6: a Timeline Channel installed by entry H accepts
 * entries ordered after H. §15.6 and §17.12: the active source surface is
 * recomputed before the next entry, and a remainder of an old window must not
 * be finalized under stale topology. The outcome of one journal must not
 * depend on whether H happened to wait for a resource.
 */
final class SdkHeldEntryTopologyTest {
    private static final DocumentId X = DocumentId.of("sdk-held-topology-x");
    private static final String A_TIMELINE = "sdk/held-topology/a";
    private static final String B_TIMELINE = "sdk/held-topology/b";
    /** Ordered strictly after the SDK-authored install entry. */
    private static final long PING_TIMESTAMP = 2_100_000_000_000_001L;

    @Test
    void controlRunDeliversEntryToChannelInstalledByEarlierEntry() {
        // given
        ExactBlueValue gate = providerValue("7");
        MapProvider provider = new MapProvider().put(gate);
        try (BlueCoordination blue = coordination(provider)) {
            Handles h = Handles.open(blue, gate);
            EntryHandle install = h.submitInstall();
            EntryHandle ping = h.submitPing();

            // when
            DrainResult drained = blue.processing().drain();

            // then
            assertEquals(EntryDisposition.APPLIED,
                    drained.entry(install).disposition(),
                    drained.entry(install).diagnostic().toString());
            assertEquals(EntryDisposition.APPLIED,
                    drained.entry(ping).disposition(),
                    drained.entry(ping).diagnostic().toString());
            assertEquals(1L, h.x.snapshot().longAt("/pings"));
        }
    }

    @Test
    void entryScannedPastHeldInstallIsStillDeliveredAfterInstallCommits() {
        // given
        ExactBlueValue gate = providerValue("7");
        MapProvider provider = new MapProvider();
        try (BlueCoordination blue = coordination(provider)) {
            Handles h = Handles.open(blue, gate);
            EntryHandle install = h.submitInstall();
            EntryHandle ping = h.submitPing();
            DrainResult held = blue.processing().drain();
            assertEquals(EntryDisposition.NEEDS_RESOURCES,
                    held.entry(install).disposition(),
                    held.entry(install).diagnostic().toString());
            String pingWhileHeld = held.find(ping)
                    .map(result -> result.disposition() + "/"
                            + result.diagnostic().code())
                    .orElse("not reported");

            // when
            provider.put(gate);
            DrainResult resumed = blue.processing().drain();
            DrainResult again = blue.processing().drain();

            // then
            assertEquals(EntryDisposition.APPLIED,
                    resumed.entry(install).disposition(),
                    resumed.entry(install).diagnostic().toString());
            Optional<EntryResult> pingAfter = resumed.find(ping)
                    .or(() -> again.find(ping));
            assertEquals(Optional.of(EntryDisposition.APPLIED),
                    pingAfter.map(EntryResult::disposition),
                    "the ping entry is ordered after the install entry and "
                            + "must reach the channel the install created; "
                            + "while install was held it was reported as "
                            + pingWhileHeld);
            assertEquals(1L, h.x.snapshot().longAt("/pings"),
                    "same journal as the control run, same outcome");
        }
    }

    private record Handles(
            BlueCoordination blue,
            TimelineHandle a,
            TimelineHandle b,
            DocumentHandle x,
            ExactBlueValue channel,
            ExactBlueValue handler) {

        static Handles open(BlueCoordination blue, ExactBlueValue gate) {
            TimelineHandle a = blue.timelines().register(A_TIMELINE, "alice");
            TimelineHandle b = blue.timelines().register(B_TIMELINE, "bob");
            DocumentHandle x = blue.documents().admit(
                    ManagedDocument.yaml(X, documentYaml(gate.blueId()))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue channel = blue.values().yaml("""
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: bob
                    """.formatted(B_TIMELINE));
            ExactBlueValue handler = blue.values().yaml("""
                    type: Coordination/Sequential Workflow Operation
                    channel: chB
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /pings
                              val: {$add: [{$document: /pings}, 1]}
                          - $return: true
                    """);
            return new Handles(blue, a, b, x, channel, handler);
        }

        /** Installs chB and ping on X; reading /gate demands an exact node. */
        EntryHandle submitInstall() {
            return blue.operations()
                    .on(x)
                    .from(a)
                    .call("install")
                    .through("ownerChannel")
                    .request(request -> {
                        request.exact("channel", channel);
                        request.exact("handler", handler);
                    })
                    .submit();
        }

        /** A complete exact entry on B, ordered after the install entry. */
        EntryHandle submitPing() {
            return blue.events().from(b).exact(blue.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    timestamp: %d
                    actor:
                      type: MyOS/Principal Actor
                      accountId: bob
                    message:
                      type: Coordination/Operation Request
                      operation: ping
                      channel: chB
                      request: {}
                    """.formatted(B_TIMELINE, PING_TIMESTAMP))).submit();
        }
    }

    private static String documentYaml(String gateBlueId) {
        return """
                documentId: %s
                pings: 0
                gate:
                  blueId: %s
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  install:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      channel: {}
                      handler: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /gateSeen
                              val: {$document: /gate/value}
                          - $appendChange:
                              op: add
                              path: /contracts/chB
                              val: {$binding: event/message/request/channel}
                          - $appendChange:
                              op: add
                              path: /contracts/ping
                              val: {$binding: event/message/request/handler}
                          - $return: true
                """.formatted(X.value(), gateBlueId, A_TIMELINE);
    }

    private static BlueCoordination coordination(ExactNodeProvider provider) {
        return BlueCoordination.builder().exactNodeProvider(provider).build();
    }

    private static ExactBlueValue providerValue(String yaml) {
        try (BlueCoordination verifier = BlueCoordination.inMemory()) {
            return verifier.values().providerContentYaml(yaml);
        }
    }

    private static final class MapProvider implements ExactNodeProvider {
        private final Map<String, ExactNodeEvidence> evidence =
                new LinkedHashMap<>();

        MapProvider put(ExactBlueValue value) {
            evidence.put(value.blueId(),
                    ExactNodeEvidence.ordinary(value.json()));
            return this;
        }

        @Override
        public Optional<String> findExactContent(String blueId) {
            return findExactEvidence(blueId)
                    .map(ExactNodeEvidence::exactContent);
        }

        @Override
        public Optional<ExactNodeEvidence> findExactEvidence(String blueId) {
            return Optional.ofNullable(evidence.get(blueId));
        }
    }
}
