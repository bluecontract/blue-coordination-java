package blue.coordination.examples.documents;

/** Complete participant-bound Blue documents for the dynamic activation example. */
public final class DynamicActivationDocuments {

    private DynamicActivationDocuments() {
    }

    /** Authored dynamic-activation document. */
    public static final String DYNAMIC_ACTIVATION = """
        name: Dynamic Activation Counter
        teachingStatus: dormant-child
        counter: 0
        child:
          name: Late-Activated Counter
          counter: 0
          creatingEventCount: 0
          activated: false
          contracts:
            ownerChannel:
              description: Alice's channel becomes active for this scope only after embedding is committed
              type: Coordination/Timeline Channel
              timeline:
                type: MyOS/MyOS Timeline
                timelineId: examples/dynamic-activation/alice
              actor:
                type: MyOS/Principal Actor
                accountId: alice
            increment:
              description: Increment the activated child for later eligible entries
              type: Coordination/Sequential Workflow Operation
              channel: ownerChannel
              request:
                amount:
                  type: Integer
              steps:
                - name: Increment child
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
        contracts:
          embedded:
            description: An absent exact target keeps the declaration valid until Bob switches it to /child
            type: Process Embedded
            paths: [/inactiveChild]
          ownerChannel:
            description: Alice's root channel is active from the start
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/dynamic-activation/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          increment:
            description: Increment the root before or after child activation
            type: Coordination/Sequential Workflow Operation
            channel: ownerChannel
            request:
              amount:
                type: Integer
            steps:
              - name: Increment root
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
          attacherChannel:
            description: Bob controls when the child becomes an active processing scope
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/dynamic-activation/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          attachChild:
              description: Activate the dormant child for strictly later eligible entries
              type: Coordination/Sequential Workflow Operation
              channel: attacherChannel
              request:
                child: {}
              steps:
                - name: Activate the child processing scope
                  type: Coordination/Compute
                  do:
                    - $appendChange:
                        op: replace
                        path: /child
                        val:
                          $binding: event/message/request/child
                    - $appendChange:
                        op: replace
                        path: /contracts/embedded/paths
                        val: [/child]
                    - $appendChange:
                        op: replace
                        path: /teachingStatus
                        val: child-active-awaiting-later-entry
                    - $return: true
        """;

}
