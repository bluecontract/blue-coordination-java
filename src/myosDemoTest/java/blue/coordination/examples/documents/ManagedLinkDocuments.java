package blue.coordination.examples.documents;

/** Authored parent whose explicit managed-child slot changes through PROCESS. */
public final class ManagedLinkDocuments {

    private ManagedLinkDocuments() {
    }

    /**
     * The slot is deliberately absent at admission. Host topology evidence
     * names its logical child before PROCESS is allowed to populate it.
     */
    public static final String DYNAMIC_PARENT = """
        name: Dynamic Managed Parent
        contracts:
          embedded:
            description: Process the managed child only while its slot exists
            type: Process Embedded
            paths:
              - /managedChild
          controllerChannel:
            description: Bob controls the managed-child relationship
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/managed-links/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          attachManagedChild:
            description: Publish the explicitly declared managed child
            type: Coordination/Sequential Workflow Operation
            channel: controllerChannel
            request:
              child: {}
            steps:
              - name: Attach the managed child
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: add
                      path: /managedChild
                      val:
                        $binding: event/message/request/child
                  - $return: true
          detachManagedChild:
            description: Remove the managed child from the committed inventory
            type: Coordination/Sequential Workflow Operation
            channel: controllerChannel
            request: {}
            steps:
              - name: Detach the managed child
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: remove
                      path: /managedChild
                  - $return: true
        """;
}
