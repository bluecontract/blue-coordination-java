package blue.coordination.examples.documents;

/** Complete participant-bound Blue documents for the Counter basics example. */
public final class BasicsCounterDocuments {

    private BasicsCounterDocuments() {
    }

    /** Authored counter document. */
    public static final String COUNTER = """
        name: Counter
        counter: 0
        contracts:
          ownerChannel:
            description: Alice's append-only counter Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/basics-counter/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          increment:
            description: Increment the counter by the requested amount
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
