package blue.coordination.examples.documents;

/** Complete participant-bound Blue documents for the embedded Counter example. */
public final class EmbeddedCounterDocuments {

    private EmbeddedCounterDocuments() {
    }

    /** Authored counter document. */
    public static final String COUNTER = """
        name: Counter
        counter: 0
        teachingStatus: direct-root-baseline
        contracts:
          ownerChannel:
            description: Alice's direct counter Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/embedded-counter/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          increment:
            description: Increment the direct root counter
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

    /** Authored embedded-counter document. */
    public static final String EMBEDDED_COUNTER = """
        name: Embedded Counter
        teachingStatus: active-embedded-processing
        lastEmbeddedEvent: none
        counter:
          name: Executable Embedded Counter
          counter: 0
          contracts:
            ownerChannel:
              description: Alice's shared counter Timeline inside the embedded scope
              type: Coordination/Timeline Channel
              timeline:
                type: MyOS/MyOS Timeline
                timelineId: examples/embedded-counter/alice
              actor:
                type: MyOS/Principal Actor
                accountId: alice
            increment:
              description: Increment the embedded counter
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
                - name: Announce increment
                  type: Coordination/Trigger Event
                  event:
                    type: Coordination/Chat Message
                    message: Embedded counter incremented
        contracts:
          embedded:
            description: Process the nested counter as an independent child scope
            type: Process Embedded
            paths:
              - /counter
          embeddedCounterEvents:
            description: Bridge emissions from the executable counter into this root
            type: Embedded Node Channel
            childPath: /counter
          recordEmbeddedIncrement:
            description: Record that the root observed the child emission
            type: Coordination/Sequential Workflow
            channel: embeddedCounterEvents
            event:
              type: Coordination/Chat Message
              message: Embedded counter incremented
            steps:
              - name: Record observation
                type: Coordination/Update Document
                changeset:
                  - op: replace
                    path: /lastEmbeddedEvent
                    val: Embedded counter incremented
              - name: Publish parent observation
                type: Coordination/Trigger Event
                event:
                  type: Coordination/Chat Message
                  message: Parent observed embedded counter increment
          observerChannel:
            description: Bob's Timeline on the parent root
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/embedded-counter/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
        """;

}
