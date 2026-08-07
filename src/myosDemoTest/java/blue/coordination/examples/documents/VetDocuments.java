package blue.coordination.examples.documents;

/** Complete participant-bound Blue documents for the PUPPS visit example. */
public final class VetDocuments {

    private VetDocuments() {
    }

    /** Authored vet-order document. */
    public static final String VET_ORDER = """
        name: Vet Order
        status: active
        clinic: East Side Veterinary Clinic
        customer: Maya
        carePlan: Puppy training coordination
        visitStatus: No visit requested yet
        pendingVisit:
          status: not-requested
          requestedBy:
          preferredDate:
          preferredTime:
          reason:
        lastConfirmedVisit:
          status: not-confirmed
          confirmedBy:
          date:
          time:
          trainer:
          notes:
        confirmedVisitCount: 0
        note: Visit request and confirmation tracking are executable; PayNote and broader order lifecycles are not simulated.
        contracts:
          customerChannel:
            description: Maya's visit-request Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          vetChannel:
            description: Vet response Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          trainerChannel:
            description: PUPPS visit-confirmation Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/celine
            actor:
              type: MyOS/Principal Actor
              accountId: celine
          scheduleVisit:
            description: Record Maya's PUPPS visit request in the Vet Order
            type: Coordination/Sequential Workflow Operation
            channel: customerChannel
            request:
              preferredDate:
                type: Text
              preferredTime:
                type: Text
              reason:
                type: Text
            steps:
              - name: Record requested visit
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/status
                      val: requested
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/requestedBy
                      val: Maya
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/preferredDate
                      val:
                        $binding: event/message/request/preferredDate
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/preferredTime
                      val:
                        $binding: event/message/request/preferredTime
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/reason
                      val:
                        $binding: event/message/request/reason
                  - $appendChange:
                      op: replace
                      path: /visitStatus
                      val: Visit requested from PUPPS
                  - $return: true
          confirmVisit:
            description: Record the shared PUPPS confirmation in the Vet Order
            type: Coordination/Sequential Workflow Operation
            channel: trainerChannel
            request:
              date:
                type: Text
              time:
                type: Text
              trainer:
                type: Text
              notes:
                type: Text
            steps:
              - name: Record confirmed visit
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/status
                      val: confirmed
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/confirmedBy
                      val: PUPPS
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/date
                      val:
                        $binding: event/message/request/date
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/time
                      val:
                        $binding: event/message/request/time
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/trainer
                      val:
                        $binding: event/message/request/trainer
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/notes
                      val:
                        $binding: event/message/request/notes
                  - $appendChange:
                      op: replace
                      path: /confirmedVisitCount
                      val:
                        $add:
                          - $document: /confirmedVisitCount
                          - 1
                  - $appendChange:
                      op: replace
                      path: /visitStatus
                      val: 1 confirmed visit
                  - $return: true
        """;

    /** Authored vet-order-paynote document. */
    public static final String VET_ORDER_PAYNOTE = """
        name: Vet Order PayNote
        status: initialized-unfunded
        guaranteeActive: false
        note: This PayNote remains a non-executable teaching placeholder; no guarantee or payment lifecycle state is applied.
        contracts:
          vetChannel:
            description: Vet commercial Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          providerChannel:
            description: Synchrony provider Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/acme-bank-rep
            actor:
              type: MyOS/Principal Actor
              accountId: acme-bank-rep
        """;

    /** Authored vet-trainer-agreement document. */
    public static final String VET_TRAINER_AGREEMENT = """
        name: Vet-Trainer Agreement
        status: visit-coordination-active
        agreementActive: false
        clinic: East Side Veterinary Clinic
        trainer: PUPPS Puppy Training
        confirmedVisitCount: 0
        lastConfirmedVisit:
          status: not-confirmed
          confirmedBy:
          date:
          time:
          trainer:
          notes:
        note: Visit confirmation tracking is executable; no commercial agreement activation is claimed.
        contracts:
          vetChannel:
            description: Vet agreement Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          trainerChannel:
            description: PUPPS agreement Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/celine
            actor:
              type: MyOS/Principal Actor
              accountId: celine
          confirmVisit:
            description: Record a PUPPS-confirmed visit for coordination purposes
            type: Coordination/Sequential Workflow Operation
            channel: trainerChannel
            request:
              date:
                type: Text
              time:
                type: Text
              trainer:
                type: Text
              notes:
                type: Text
            steps:
              - name: Record confirmed visit
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: replace
                      path: /confirmedVisitCount
                      val:
                        $add:
                          - $document: /confirmedVisitCount
                          - 1
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/status
                      val: confirmed
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/confirmedBy
                      val: PUPPS
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/date
                      val:
                        $binding: event/message/request/date
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/time
                      val:
                        $binding: event/message/request/time
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/trainer
                      val:
                        $binding: event/message/request/trainer
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/notes
                      val:
                        $binding: event/message/request/notes
                  - $return: true
        """;

    /** Authored pupps-order document. */
    public static final String PUPPS_ORDER = """
        name: PUPPS Order
        status: active
        provider: PUPPS Puppy Training
        customer: Maya
        clinic: East Side Veterinary Clinic
        pendingVisit:
          status: not-requested
          requestedBy:
          preferredDate:
          preferredTime:
          reason:
        lastConfirmedVisit:
          status: not-confirmed
          confirmedBy:
          date:
          time:
          trainer:
          notes:
        confirmedVisitCount: 0
        note: Visit request and confirmation are executable; payment and completed-fulfilment states are not simulated.
        contracts:
          customerChannel:
            description: Maya's PUPPS visit-request Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          vetChannel:
            description: Vet's PUPPS coordination Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          trainerChannel:
            description: PUPPS visit-confirmation Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/celine
            actor:
              type: MyOS/Principal Actor
              accountId: celine
          customerAgentChannel:
            description: Maya agent Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/alice-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: alice-agent
          vetAgentChannel:
            description: Vet agent Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/bob-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: bob-agent
          trainerAgentChannel:
            description: PUPPS agent Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet/celine-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: celine-agent
          scheduleVisit:
            description: Maya requests a PUPPS puppy training visit.
            type: Coordination/Sequential Workflow Operation
            channel: customerChannel
            request:
              preferredDate:
                type: Text
              preferredTime:
                type: Text
              reason:
                type: Text
            steps:
              - name: Request PUPPS visit
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/status
                      val: requested
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/requestedBy
                      val: Maya
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/preferredDate
                      val:
                        $binding: event/message/request/preferredDate
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/preferredTime
                      val:
                        $binding: event/message/request/preferredTime
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/reason
                      val:
                        $binding: event/message/request/reason
                  - $return: true
          confirmVisit:
            description: PUPPS confirms the visit date and time.
            type: Coordination/Sequential Workflow Operation
            channel: trainerChannel
            request:
              date:
                type: Text
              time:
                type: Text
              trainer:
                type: Text
              notes:
                type: Text
            steps:
              - name: Confirm PUPPS visit
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/status
                      val: confirmed
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/confirmedBy
                      val: PUPPS
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/date
                      val:
                        $binding: event/message/request/date
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/time
                      val:
                        $binding: event/message/request/time
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/trainer
                      val:
                        $binding: event/message/request/trainer
                  - $appendChange:
                      op: replace
                      path: /lastConfirmedVisit/notes
                      val:
                        $binding: event/message/request/notes
                  - $appendChange:
                      op: replace
                      path: /pendingVisit/status
                      val: confirmed
                  - $appendChange:
                      op: replace
                      path: /confirmedVisitCount
                      val:
                        $add:
                          - $document: /confirmedVisitCount
                          - 1
                  - $return: true
        """;

}
