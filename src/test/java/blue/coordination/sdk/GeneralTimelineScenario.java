package blue.coordination.sdk;

/** Authored exact fixtures only; expected results come from independently run references. */
final class GeneralTimelineScenario {
    private GeneralTimelineScenario() { }
    static String document(String timeline) {
        return """
            counter: 0
            contracts:
              owner:
                type: Coordination/Timeline Channel
                timeline: {type: MyOS/MyOS Timeline, timelineId: %s}
                actor: {type: MyOS/Principal Actor, accountId: alice}
              onCredit:
                type: Coordination/Sequential Workflow
                channel: owner
                event:
                  message:
                    kind: CreditRecorded
                steps:
                  - type: Coordination/Compute
                    do:
                      - $appendChange:
                          op: replace
                          path: /counter
                          val: {$event: /message/amount}
                      - $return: true
            """.formatted(timeline);
    }
    static ExactBlueValue event(BlueCoordination blue, String timeline, long timestamp, int amount) {
        return blue.values().yaml("""
            type: Coordination/Timeline Entry
            timeline: {type: MyOS/MyOS Timeline, timelineId: %s}
            actor: {type: MyOS/Principal Actor, accountId: alice}
            timestamp: %d
            message:
              kind: CreditRecorded
              amount: %d
            """.formatted(timeline, timestamp, amount));
    }
}
