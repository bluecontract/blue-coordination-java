package blue.coordination.examples.documents;

/** One source admitted as three independent Roots for generic fan-out proof. */
public final class CompleteFanoutDocuments {

    private CompleteFanoutDocuments() {
    }

    public static final String ROOT = """
        name: Complete Fanout Counter
        counter: 0
        contracts:
          ownerChannel:
            description: Alice's complete fan-out Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/complete-fanout/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          authorizeAmount:
            description: Operation name formerly special-cased by the host
            type: Coordination/Sequential Workflow Operation
            channel: ownerChannel
            request:
              amount:
                type: Integer
            steps:
              - name: Add authorized amount
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
          unrelatedAlpha:
            description: First unrelated operation name
            type: Coordination/Sequential Workflow Operation
            channel: ownerChannel
            request:
              amount:
                type: Integer
            steps:
              - name: Add alpha amount
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
          unrelatedBeta:
            description: Second unrelated operation name
            type: Coordination/Sequential Workflow Operation
            channel: ownerChannel
            request:
              amount:
                type: Integer
            steps:
              - name: Add beta amount
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
