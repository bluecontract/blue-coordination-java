package blue.coordination.examples.documents;

/** Complete participant-bound Blue documents for the Operation Mandate example. */
public final class MandateOperationDocuments {

    private MandateOperationDocuments() {
    }

    /** Authored delegated-counter document. */
    public static final String DELEGATED_COUNTER = """
        name: Delegated Counter
        counter: 0
        contracts:
          holderChannel:
            description: Alice's principal Timeline and the increment operation's effective channel
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/mandate-operation/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          agentChannel:
            description: Alice's agent Timeline, eligible only through a verified Operation Mandate
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/mandate-operation/alice-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: alice-agent
          increment:
            description: Increment through Alice's effective channel, directly or by bounded delegation
            type: Coordination/Sequential Workflow Operation
            channel: holderChannel
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

    /** Authored increment-mandate document. */
    public static final String INCREMENT_MANDATE = """
        name: Increment Mandate
        type: Mandate/Operation Mandate
        activateOnAuthorityConfirmation: true
        target:
          initialDocument:
            blueId: "{{initialBlueId:delegated-counter}}"
          channel: holderChannel
          operation: increment
        validation:
          request:
            amount: 1
        contracts:
          initializeMandate:
            event:
              document:
                type: Common/Document
          terminateMandate:
            request:
              reason: Guided scenario complete
          applyMandateTermination:
            event:
              reason: Guided scenario complete
          mandateLifecycleDefinition:
            type: Coordination/Compute Definition
            constants:
              authorityConfirmedMessageType:
                type: Mandate/Mandate Authority Confirmed
                timestampUs: 0
              terminatedMessageType:
                type: Mandate/Mandate Terminated
                reason: authored-template
          mandateGuarantorChannel:
            description: MyOS Admin's guarantor Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/mandate-operation/myos-admin
            actor:
              type: MyOS/MyOS Admin Actor
              accountId: myos-admin
          authorityHolderChannel:
            description: Alice's authority-holder Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/mandate-operation/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          authorizedActorChannel:
            description: Alice's agent Timeline receiving the bounded increment authority
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/mandate-operation/alice-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: alice-agent
        """;

}
