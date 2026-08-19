package blue.coordination.consumer;

import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.ManagedDocument;
import blue.coordination.sdk.TimelineHandle;

/** Standalone staged-artifact smoke consumer of the supported SDK surface. */
public final class StagedSdkConsumer {
    private StagedSdkConsumer() {
    }

    /** Runs one bundled Contracts 1.0 counter operation. */
    public static void main(String[] args) {
        String timelineId = "consumer/sdk-counter/alice";
        String documentId = "consumer-sdk-counter";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, "alice");
            DocumentHandle counter = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    documentId,
                                    counterYaml(documentId, timelineId))
                            .publicRoot()
                            .fromNow());

            var result = coordination.operations().on(counter)
                    .from(timeline)
                    .call("increment")
                    .through("ownerChannel")
                    .requestYaml("amount: 3")
                    .execute();

            require(result.disposition() == EntryDisposition.APPLIED,
                    "operation was not applied");
            require(counter.snapshot().longAt("/counter") == 3L,
                    "counter value differs");
            require(counter.snapshot().epoch() == 1L,
                    "counter epoch differs");
            require(result.stats().gas() > 0L,
                    "operation did not consume gas");
            System.out.println(
                    "STAGED_SDK_CONSUMER_PASS counter=3 epoch=1");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String counterYaml(String id, String timelineId) {
        return """
                documentId: %s
                counter: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val:
                                $add:
                                  - $document: /counter
                                  - $binding: event/message/request/amount
                          - $return: true
                """.formatted(id, timelineId);
    }
}
