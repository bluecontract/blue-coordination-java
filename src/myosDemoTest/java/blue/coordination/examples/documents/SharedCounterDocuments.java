package blue.coordination.examples.documents;

/** Complete participant-bound Blue documents for the shared Counter example. */
public final class SharedCounterDocuments {

    private SharedCounterDocuments() {
    }

    /** Authored counter-a document. */
    public static final String COUNTER_A = """
        name: Counter A
        counter: 0
        contracts:
          ownerChannel:
            description: Alice's shared Timeline, processed independently by both counters
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/shared-counter/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          increment:
            description: Increment both roots through one canonical Timeline Entry
            type: Coordination/Sequential Workflow Operation
            channel: ownerChannel
            request:
              amount:
                type: Integer
            steps:
              - name: Increment
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: replace
                      path: /counter
                      val:
                        $add:
                          - $document: /counter
                          - $binding: event/message/request/amount
                  - $return: true
        """;

    /** Authored counter-b document. */
    public static final String COUNTER_B = """
        name: Counter B
        counter: 0
        contracts:
          ownerChannel:
            description: Alice's shared Timeline, processed independently by both counters
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/shared-counter/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          increment:
            description: Increment both roots through one canonical Timeline Entry
            type: Coordination/Sequential Workflow Operation
            channel: ownerChannel
            request:
              amount:
                type: Integer
            steps:
              - name: Increment
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: replace
                      path: /counter
                      val:
                        $add:
                          - $document: /counter
                          - $binding: event/message/request/amount
                  - $return: true
        """;

}
