package blue.coordination.examples.documents;

/** Complete participant-bound Blue documents for the PawStart Full Plan example. */
public final class VetExtDocuments {

    private VetExtDocuments() {
    }

    /** Authored pawstart-plan-order document. */
    private static final String PAWSTART_PLAN_ORDER_PART_1 = """
        name: PawStart Full Plan Order
        scenarioId: pawstart-full-plan-2026-v1
        commerceType: Commerce/Order
        status: Active
        paymentState: Payment Completed
        customer: Maya
        merchant: East Side Veterinary Clinic
        retailTotalMinor: 144600
        orderTotalMinor: 129900
        savingsMinor: 14700
        savingsPercent: 10
        currency: USD
        initialization:
          orderStarted: true
          puppsOrderAttached: true
          agreementAttached: false
          payNoteAttached: false
          paymentCompleted: true
          stage: PUPPS Order active; Agreement and PayNote ready to attach
        planItems:
          clinicalCarePlan:
            name: East Side puppy clinical care plan
            provider: East Side Veterinary Clinic
            retailAmountMinor: 72500
            amountMinor: 67500
            currency: USD
            status: Active
          daycareStarter:
            name: PUPS daycare starter
            provider: PUPPS
            retailAmountMinor: 24500
            amountMinor: 21900
            currency: USD
            status: Active
          puppyTraining:
            name: PUPS puppy training
            provider: PUPPS
            retailAmountMinor: 33500
            amountMinor: 27100
            currency: USD
            status: Not scheduled
          insuranceEstimate:
            name: Pets Best 90-day premium insurance estimate
            provider: East Side Veterinary Clinic
            retailAmountMinor: 14100
            amountMinor: 13400
            currency: USD
            status: Active
        trainingVisit:
          requestState: Not scheduled
          confirmationState: Not confirmed
          productState: Not scheduled
          outcome: None
          requestId:
          preferredDate:
          preferredTime:
          date:
          time:
          trainer:
          satisfaction:
            score: 0
            comment: ""
        product:
          name: PawStart Full Plan
          commerceType: Commerce/Bundle Product
          status: Active
          retailTotalMinor: 144600
          amountMinor: 129900
          savingsMinor: 14700
          savingsPercent: 10
          currency: USD
          products:
            clinicalCarePlan:
              name: East Side puppy clinical care plan
              commerceType: Commerce/Fixed Product
              retailAmountMinor: 72500
              amountMinor: 67500
              currency: USD
              status: Active
            insuranceEstimate:
              name: Pets Best 90-day premium insurance estimate
              commerceType: Commerce/Fixed Product
              retailAmountMinor: 14100
              amountMinor: 13400
              currency: USD
              status: Active
            puppsOrder:
              name: PUPPS Grooming Order
              commerceType: Commerce/Bundle Product
              status: Active - training not scheduled
              terminalOutcome: None
              provider: PUPPS
              providerActorId: celine
              products:
                daycareStarter:
                  name: PUPS daycare starter
                  commerceType: Commerce/Fixed Product
                  retailAmountMinor: 24500
                  amountMinor: 21900
                  currency: USD
                  status: Active
                puppyTraining:
                  name: PUPS puppy training
                  commerceType: Commerce/Bookable Product
                  retailAmountMinor: 33500
                  amountMinor: 27100
                  currency: USD
                  status: Not scheduled
                  done: false
                  cancelled: false
                  satisfaction:
                    score: 0
                    comment: ""
                  cancellationPolicy:
                    onTime:
                      cutoffHoursBefore: 24
                      customerRefundAmountMinor: 27100
                      providerSettlementAmountMinor: 0
                    lateOrNoShow:
                      customerRefundAmountMinor: 0
                      providerSettlementAmountMinor: 27100
                  satisfactionPolicy:
                    lowScoreThreshold: 50
                    serviceAdjustmentPercent: 10
                    serviceAdjustmentAmountMinor: 2710
              pendingVisit:
                status: Not scheduled
                serviceKey:
                preferredDate:
                preferredTime:
                reason:
                requestId:
                requestedAt:
              confirmedVisit:
                confirmed: false
                date:
                time:
                trainer:
                notes:
                inResponseTo:
                confirmedAt:
              visitHistory: []
              outcomeCounts:
                requested: 0
                confirmed: 0
                completed: 0
                cancelledOnTime: 0
                lateCancellationOrNoShow: 0
                lowSatisfaction: 0
              contracts:
                customerChannel:
                  description: Maya's effective PUPPS scheduling Timeline
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/vet-ext/alice
                  actor:
                    type: MyOS/Principal Actor
                    accountId: alice
                  type: Coordination/Timeline Channel
                customerAgentChannel:
                  description: Maya's agent Timeline, eligible only through the scheduling Operation Mandate
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/vet-ext/alice-agent
                  actor:
                    type: MyOS/MyOS Agent Actor
                    accountId: alice-agent
                  type: Coordination/Timeline Channel
                vetChannel:
                  description: East Side Veterinary Clinic coordination Timeline
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/vet-ext/bob
                  actor:
                    type: MyOS/Principal Actor
                    accountId: bob
                  type: Coordination/Timeline Channel
                vetAgentChannel:
                  description: Vet's Synchrony coordination Timeline
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/vet-ext/bob-agent
                  actor:
                    type: MyOS/MyOS Agent Actor
                    accountId: bob-agent
                  type: Coordination/Timeline Channel
                trainerChannel:
                  description: PUPPS visit-confirmation Timeline
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/vet-ext/celine
                  actor:
                    type: MyOS/Principal Actor
                    accountId: celine
                  type: Coordination/Timeline Channel
                trainerAgentChannel:
                  description: PUPPS Synchrony fulfilment Timeline
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/vet-ext/celine-agent
                  actor:
                    type: MyOS/MyOS Agent Actor
                    accountId: celine-agent
                  type: Coordination/Timeline Channel
                scheduleVisit:
                  description: Schedule the puppy-training visit included in Maya's PawStart Full Plan.
                  type: Coordination/Sequential Workflow Operation
                  channel: customerChannel
                  request:
                    serviceKey:
                      type: Text
                    preferredDate:
                      type: Text
                    preferredTime:
                      type: Text
                    reason:
                      type: Text
                    requestId:
                      type: Text
                  steps:
                    - name: Request included puppy-training visit
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond:
                              $and:
                                - $eq:
                                    - $document: /terminalOutcome
                                    - None
                                - $eq:
                                    - $document: /pendingVisit/status
                                    - Not scheduled
                                - $eq:
                                    - $binding: event/message/request/serviceKey
                                    - puppyTraining
                            then:
                              - $appendChange: {op: replace, path: /status, val: Visit requested}
                              - $appendChange:
                                  op: replace
                                  path: /pendingVisit
                                  val:
                                    $merge:
                                      - $document: /pendingVisit
                                      - status: Visit requested
                                        serviceKey: {$binding: event/message/request/serviceKey}
                                        preferredDate: {$binding: event/message/request/preferredDate}
                                        preferredTime: {$binding: event/message/request/preferredTime}
                                        reason: {$binding: event/message/request/reason}
                                        requestId: {$binding: event/message/request/requestId}
                                        requestedAt: {$binding: event/timestamp}
                              - $appendChange: {op: replace, path: /products/puppyTraining/status, val: Visit requested}
                              - $appendChange:
                                  op: replace
                                  path: /outcomeCounts/requested
                                  val: {$add: [$document: /outcomeCounts/requested, 1]}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Visit Scheduling Requested
                                  serviceKey: {$binding: event/message/request/serviceKey}
                                  preferredDate: {$binding: event/message/request/preferredDate}
                                  preferredTime: {$binding: event/message/request/preferredTime}
                                  reason: {$binding: event/message/request/reason}
                                  requestId: {$binding: event/message/request/requestId}
                                  requestedOperation: confirmVisit
                                  requestedOperationScopedKey: /product/products/puppsOrder::confirmVisit
                                  sourceDocumentPath: /product/products/puppsOrder
                                  targetDocumentPath: /product/products/puppsOrder
                                  recipientActorId: celine
                        - $return: true
                confirmVisit:
                  description: PUPPS confirms the exact pending visit request.
                  type: Coordination/Sequential Workflow Operation
                  channel: trainerChannel
                  request:
                    date: {type: Text}
                    time: {type: Text}
                    trainer:
                      type: Text
                    notes: {type: Text}
                    inResponseTo: {type: Text}
                  steps:
                    - name: Confirm requested puppy-training visit
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond:
                              $and:
                                - $eq: [$document: /terminalOutcome, None]
                                - $eq: [$document: /confirmedVisit/confirmed, false]
                                - $eq:
                                    - $document: /pendingVisit/requestId
                                    - $binding: event/message/request/inResponseTo
                            then:
                              - $appendChange: {op: replace, path: /status, val: Training confirmed}
                              - $appendChange:
                                  op: replace
                                  path: /confirmedVisit
                                  val:
                                    $merge:
                                      - $document: /confirmedVisit
                                      - confirmed: true
                                        date: {$binding: event/message/request/date}
                                        time: {$binding: event/message/request/time}
                                        trainer: {$binding: event/message/request/trainer}
                                        notes: {$binding: event/message/request/notes}
                                        inResponseTo: {$binding: event/message/request/inResponseTo}
                                        confirmedAt: {$binding: event/timestamp}
                              - $appendChange: {op: replace, path: /products/puppyTraining/status, val: Training confirmed}
                              - $appendChange:
                                  op: replace
                                  path: /outcomeCounts/confirmed
                                  val: {$add: [$document: /outcomeCounts/confirmed, 1]}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Visit Confirmed
                                  requestId: {$binding: event/message/request/inResponseTo}
                                  inResponseTo: {$binding: event/message/request/inResponseTo}
                                  date: {$binding: event/message/request/date}
                                  time: {$binding: event/message/request/time}
                                  trainer: {$binding: event/message/request/trainer}
                                  notes: {$binding: event/message/request/notes}
                        - $return: true
                confirmVisitHappened:
                  description: Maya confirms normal fulfilment of the puppy-training visit.
                  type: Coordination/Sequential Workflow Operation
                  channel: customerChannel
                  request:
                    confirmationCode: {type: Text}
                    comment: {type: Text}
                  steps:
                    - name: Complete puppy-training Product exactly once
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond:
                              $and:
                                - $eq: [$document: /terminalOutcome, None]
                                - $eq: [$document: /confirmedVisit/confirmed, true]
                            then:
                              - $appendChange: {op: replace, path: /status, val: Training completed}
                              - $appendChange: {op: replace, path: /terminalOutcome, val: Completed}
                              - $appendChange: {op: replace, path: /products/puppyTraining/status, val: Done}
                              - $appendChange: {op: replace, path: /products/puppyTraining/done, val: true}
                              - $appendChange:
                                  op: replace
                                  path: /outcomeCounts/completed
                                  val: {$add: [$document: /outcomeCounts/completed, 1]}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Commerce/Product Done
                                  outcomeId: pupps-training-completed-001
                                  requestId: {$document: /confirmedVisit/inResponseTo}
                                  confirmationCode: {$binding: event/message/request/confirmationCode}
                                  comment: {$binding: event/message/request/comment}
                                  sourceProductPath: /product/products/puppsOrder/products/puppyTraining
                        - $return: true
                cancelVisitWithinWindow:
                  description: Maya selects the explicit on-time cancellation policy branch.
                  type: Coordination/Sequential Workflow Operation
                  channel: customerChannel
                  request:
                    reason: {type: Text}
                    requestId: {type: Text}
                  steps:
                    - name: Cancel puppy training under the on-time policy
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond:
                              $and:
                                - $eq: [$document: /terminalOutcome, None]
                                - $eq: [$document: /confirmedVisit/confirmed, true]
                            then:
                              - $appendChange: {op: replace, path: /status, val: Training cancelled on time}
                              - $appendChange: {op: replace, path: /terminalOutcome, val: CancelledOnTime}
                              - $appendChange: {op: replace, path: /products/puppyTraining/status, val: Cancelled on time}
                              - $appendChange: {op: replace, path: /products/puppyTraining/cancelled, val: true}
                              - $appendChange:
                                  op: replace
                                  path: /outcomeCounts/cancelledOnTime
                                  val: {$add: [$document: /outcomeCounts/cancelledOnTime, 1]}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Commerce/Product Cancelled
                                  outcomeId: {$binding: event/message/request/requestId}
                                  requestId: {$binding: event/message/request/requestId}
                                  policyBranch: onTime
                                  reason: {$binding: event/message/request/reason}
                                  customerRefundAmountMinor: 27100
                                  providerSettlementAmountMinor: 0
                                  sourceProductPath: /product/products/puppsOrder/products/puppyTraining
                        - $return: true
                recordLateCancellationOrNoShow:
                  description: PUPPS Synchrony Agent records an authoritative attendance outcome without claiming delivery.
                  type: Coordination/Sequential Workflow Operation
                  channel: trainerAgentChannel
                  request:
                    reason: {type: Text}
                    outcome:
                      type: Text
                  steps:
                    - name: Record no-show or late cancellation
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond:
                              $and:
                                - $eq: [$document: /terminalOutcome, None]
                                - $eq: [$document: /confirmedVisit/confirmed, true]
                            then:
                              - $appendChange: {op: replace, path: /status, val: No-show / late cancellation recorded}
                              - $appendChange:
                                  op: replace
                                  path: /terminalOutcome
                                  val: {$binding: event/message/request/outcome}
                              - $appendChange: {op: replace, path: /products/puppyTraining/status, val: No-show / late
                                    cancellation}
                              - $appendChange:
                                  op: replace
                                  path: /outcomeCounts/lateCancellationOrNoShow
                                  val: {$add: [$document: /outcomeCounts/lateCancellationOrNoShow, 1]}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Commerce/No Show Recorded
                                  outcomeId: pupps-training-attendance-001
                                  outcome: {$binding: event/message/request/outcome}
                                  reason: {$binding: event/message/request/reason}
                                  customerRefundAmountMinor: 0
                                  providerSettlementAmountMinor: 27100
                                  sourceProductPath: /product/products/puppsOrder/products/puppyTraining
                        - $return: true
                confirmVisitWithLowSatisfaction:
                  description: Maya confirms delivery and requests the deterministic 10% service adjustment.
                  type: Coordination/Sequential Workflow Operation
                  channel: customerChannel
                  request:
                    score: {type: Integer}
                    comment: {type: Text}
                    requestAdjustment: {type: Boolean}
                  steps:
                    - name: Complete training with a low-satisfaction issue
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond:
                              $and:
                                - $eq: [$document: /terminalOutcome, None]
                                - $eq: [$document: /confirmedVisit/confirmed, true]
                                - $eq: [$binding: event/message/request/requestAdjustment, true]
                                - $eq: [$binding: event/message/request/score, 35]
                            then:
                              - $appendChange: {op: replace, path: /status, val: Training completed - experience issue open}
                              - $appendChange: {op: replace, path: /terminalOutcome, val: CompletedLowSatisfaction}
                              - $appendChange: {op: replace, path: /products/puppyTraining/status, val: Done}
                              - $appendChange: {op: replace, path: /products/puppyTraining/done, val: true}
                              - $appendChange:
                                  op: replace
                                  path: /products/puppyTraining/satisfaction/score
                                  val: {$binding: event/message/request/score}
                              - $appendChange:
                                  op: replace
                                  path: /products/puppyTraining/satisfaction/comment
                                  val: {$binding: event/message/request/comment}
                              - $appendChange:
                                  op: replace
                                  path: /outcomeCounts/completed
                                  val: {$add: [$document: /outcomeCounts/completed, 1]}
                              - $appendChange:
                                  op: replace
                                  path: /outcomeCounts/lowSatisfaction
                                  val: {$add: [$document: /outcomeCounts/lowSatisfaction, 1]}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Commerce/Product Done
                                  outcomeId: pupps-training-low-satisfaction-done-001
                                  requestId: {$document: /confirmedVisit/inResponseTo}
                                  sourceProductPath: /product/products/puppsOrder/products/puppyTraining
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Commerce/Satisfaction Submitted
                                  outcomeId: pupps-training-low-satisfaction-001
                                  score: {$binding: event/message/request/score}
                                  comment: {$binding: event/message/request/comment}
                                  requestAdjustment: {$binding: event/message/request/requestAdjustment}
                                  serviceAdjustmentPercent: 10
                                  serviceAdjustmentAmountMinor: 2710
                                  sourceProductPath: /product/products/puppsOrder/products/puppyTraining
                        - $return: true
        partnerAgreements:
          pupps:
            name: Vet–PUPPS Agreement
            agreementType: Commerce/Partner Agreement
            status: Active
            requestedVisitCount: 0
            confirmedVisitCount: 0
            completedVisitCount: 0
            onTimeCancellationCount: 0
            lateCancellationOrNoShowCount: 0
            lowSatisfactionCount: 0
            visitRequestId:
            visitConfirmed: false
            terminalOutcome: None
            lastPuppsOutcome:
            puppyTrainingSettlementState: Not earned
            puppyTrainingAmountMinor: 27100
            currency: USD
            openIssues:
              puppyTrainingLowSatisfaction:
                open: false
                score: 0
                comment: ""
            contracts:
              customerChannel:
                description: Maya's Vet–PUPPS Agreement outcome Timeline
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: examples/vet-ext/alice
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
                type: Coordination/Timeline Channel
              customerAgentChannel:
                description: Maya's agent Timeline for attributable delegated scheduling history
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: examples/vet-ext/alice-agent
                actor:
                  type: MyOS/MyOS Agent Actor
                  accountId: alice-agent
                type: Coordination/Timeline Channel
              vetChannel:
                description: East Side Veterinary Clinic Agreement Timeline
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: examples/vet-ext/bob
                actor:
                  type: MyOS/Principal Actor
                  accountId: bob
                type: Coordination/Timeline Channel
              vetAgentChannel:
                description: Vet's Synchrony Agreement Timeline
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: examples/vet-ext/bob-agent
                actor:
                  type: MyOS/MyOS Agent Actor
                  accountId: bob-agent
                type: Coordination/Timeline Channel
              trainerChannel:
                description: PUPPS Agreement Timeline
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: examples/vet-ext/celine
                actor:
                  type: MyOS/Principal Actor
                  accountId: celine
                type: Coordination/Timeline Channel
              trainerAgentChannel:
                description: PUPPS Synchrony Agreement Timeline
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: examples/vet-ext/celine-agent
                actor:
                  type: MyOS/MyOS Agent Actor
                  accountId: celine-agent
                type: Coordination/Timeline Channel
              observeVisitRequest:
                description: Record Maya's exact puppy-training request in the Agreement.
                type: Coordination/Sequential Workflow
                channel: customerAgentChannel
                event:
                  message:
                    type: Coordination/Operation Request
                    operation: scheduleVisit
                    channel: customerChannel
                steps:
                  - name: Record requested PUPPS visit
                    type: Coordination/Compute
                    do:
                      - $if:
                          cond:
                            $and:
                              - $eq: [$document: /requestedVisitCount, 0]
                              - $eq: [$binding: event/message/request/serviceKey, puppyTraining]
                          then:
                            - $appendChange:
                                op: replace
                                path: /visitRequestId
                                val: {$binding: event/message/request/requestId}
                            - $appendChange:
                                op: replace
                                path: /requestedVisitCount
                                val: {$add: [$document: /requestedVisitCount, 1]}
                            - $appendChange: {op: replace, path: /lastPuppsOutcome, val: Visit Scheduling Requested}
                      - $return: true
              observeVisitConfirmation:
                description: Record PUPPS's confirmation once and correlate it to Maya's request.
                type: Coordination/Sequential Workflow
                channel: trainerChannel
                event:
                  message:
                    type: Coordination/Operation Request
                    operation: confirmVisit
                    channel: trainerChannel
                steps:
                  - name: Record confirmed PUPPS visit
                    type: Coordination/Compute
                    do:
                      - $if:
                          cond:
                            $and:
                              - $eq: [$document: /visitConfirmed, false]
                              - $eq: [$document: /terminalOutcome, None]
                              - $eq:
                                  - $document: /visitRequestId
                                  - $binding: event/message/request/inResponseTo
                          then:
                            - $appendChange: {op: replace, path: /visitConfirmed, val: true}
                            - $appendChange:
                                op: replace
                                path: /confirmedVisitCount
                                val: {$add: [$document: /confirmedVisitCount, 1]}
                            - $appendChange: {op: replace, path: /lastPuppsOutcome, val: Visit Confirmed}
                      - $return: true
              observeVisitDone:
                description: Earn the PUPPS settlement for normal completion exactly once.
                type: Coordination/Sequential Workflow
                channel: customerChannel
                event:
                  message:
                    type: Coordination/Operation Request
                    operation: confirmVisitHappened
                    channel: customerChannel
                steps:
                  - name: Settle completed PUPPS visit
                    type: Coordination/Compute
                    do:
                      - $if:
                          cond:
                            $and:
                              - $eq: [$document: /visitConfirmed, true]
                              - $eq: [$document: /terminalOutcome, None]
                          then:
                            - $appendChange: {op: replace, path: /terminalOutcome, val: Completed}
                            - $appendChange:
                                op: replace
                                path: /completedVisitCount
                                val: {$add: [$document: /completedVisitCount, 1]}
                            - $appendChange: {op: replace, path: /lastPuppsOutcome, val: Commerce/Product Done}
                            - $appendChange: {op: replace, path: /puppyTrainingSettlementState, val: Earned}
                      - $return: true
              observeOnTimeCancellation:
                description: Record an on-time cancellation with no provider settlement.
                type: Coordination/Sequential Workflow
                channel: customerChannel
                event:
                  message:
                    type: Coordination/Operation Request
                    operation: cancelVisitWithinWindow
                    channel: customerChannel
                steps:
                  - name: Settle on-time PUPPS cancellation
                    type: Coordination/Compute
                    do:
                      - $if:
                          cond:
                            $and:
                              - $eq: [$document: /visitConfirmed, true]
                              - $eq: [$document: /terminalOutcome, None]
                          then:
                            - $appendChange: {op: replace, path: /terminalOutcome, val: CancelledOnTime}
                            - $appendChange:
                                op: replace
                                path: /onTimeCancellationCount
                                val: {$add: [$document: /onTimeCancellationCount, 1]}
                            - $appendChange: {op: replace, path: /lastPuppsOutcome, val: Commerce/Product Cancelled}
                            - $appendChange: {op: replace, path: /puppyTrainingSettlementState, val: Not earned}
                      - $return: true
              observeNoShowOrLateCancellation:
                description: Preserve the PUPPS settlement without describing the Product as delivered.
                type: Coordination/Sequential Workflow
                channel: trainerAgentChannel
                event:
                  message:
                    type: Coordination/Operation Request
                    operation: recordLateCancellationOrNoShow
                    channel: trainerAgentChannel
                steps:
                  - name: Settle PUPPS attendance outcome
                    type: Coordination/Compute
                    do:
                      - $if:
                          cond:
                            $and:
                              - $eq: [$document: /visitConfirmed, true]
                              - $eq: [$document: /terminalOutcome, None]
                          then:
                            - $appendChange:
                                op: replace
                                path: /terminalOutcome
                                val: {$binding: event/message/request/outcome}
                            - $appendChange:
                                op: replace
                                path: /lateCancellationOrNoShowCount
                                val: {$add: [$document: /lateCancellationOrNoShowCount, 1]}
                            - $appendChange:
                                op: replace
                                path: /lastPuppsOutcome
                                val: {$binding: event/message/request/outcome}
                            - $appendChange: {op: replace, path: /puppyTrainingSettlementState, val: Earned}
                      - $return: true
              observeLowSatisfaction:
                description: Earn settlement while opening the deterministic service-quality issue.
                type: Coordination/Sequential Workflow
                channel: customerChannel
                event:
                  message:
                    type: Coordination/Operation Request
                    operation: confirmVisitWithLowSatisfaction
                    channel: customerChannel
                steps:
                  - name: Record completed PUPPS visit quality issue
                    type: Coordination/Compute
                    do:
                      - $if:
                          cond:
                            $and:
                              - $eq: [$document: /visitConfirmed, true]
                              - $eq: [$document: /terminalOutcome, None]
                              - $eq: [$binding: event/message/request/requestAdjustment, true]
                              - $eq: [$binding: event/message/request/score, 35]
                          then:
                            - $appendChange: {op: replace, path: /terminalOutcome, val: CompletedLowSatisfaction}
                            - $appendChange:
                                op: replace
                                path: /completedVisitCount
                                val: {$add: [$document: /completedVisitCount, 1]}
                            - $appendChange:
                                op: replace
                                path: /lowSatisfactionCount
                                val: {$add: [$document: /lowSatisfactionCount, 1]}
                            - $appendChange: {op: replace, path: /lastPuppsOutcome, val: Commerce/Satisfaction Submitted}
                            - $appendChange: {op: replace, path: /puppyTrainingSettlementState, val: Earned}
                            - $appendChange: {op: replace, path: /openIssues/puppyTrainingLowSatisfaction/open, val: true}
                            - $appendChange:
                                op: replace
                                path: /openIssues/puppyTrainingLowSatisfaction/score
                                val: {$binding: event/message/request/score}
                            - $appendChange:
                                op: replace
                                path: /openIssues/puppyTrainingLowSatisfaction/comment
                                val: {$binding: event/message/request/comment}
                      - $return: true
        payNote:
          name: PawStart Full Plan PayNote
          payNoteType: PayNote/PayNote
          status: Payment Completed
          payer: Maya
          payee: East Side Veterinary Clinic
          guarantor: Synchrony
          amount:
            expectedTotalMinor: 129900
            capturedMinor: 129900
            refundedMinor: 0
            currency: USD
          capture:
            requested: true
            completed: true
            requestId: pawstart-payment-001
          refund:
            requested: false
            adjustment: false
            requestId:
            amountMinor: 0
            reason:
            completed: false
            completedAt:
          trainingVisit:
            confirmed: false
            terminalOutcome: None
          contracts:
            customerChannel:
              description: Maya's PawStart PayNote outcome Timeline
              timeline:
                type: MyOS/MyOS Timeline
                timelineId: examples/vet-ext/alice
              actor:
                type: MyOS/Principal Actor
                accountId: alice
              type: Coordination/Timeline Channel
            customerAgentChannel:
              description: Maya's agent Timeline for attributable delegated history
              timeline:
                type: MyOS/MyOS Timeline
                timelineId: examples/vet-ext/alice-agent
              actor:
                type: MyOS/MyOS Agent Actor
                accountId: alice-agent
              type: Coordination/Timeline Channel
            vetChannel:
              description: East Side Veterinary Clinic commercial Timeline
              timeline:
                type: MyOS/MyOS Timeline
                timelineId: examples/vet-ext/bob
              actor:
                type: MyOS/Principal Actor
                accountId: bob
              type: Coordination/Timeline Channel
            trainerChannel:
              description: PUPPS confirmation Timeline
              timeline:
                type: MyOS/MyOS Timeline
                timelineId: examples/vet-ext/celine
              actor:
                type: MyOS/Principal Actor
                accountId: celine
              type: Coordination/Timeline Channel
            trainerAgentChannel:
              description: PUPPS Synchrony attendance Timeline
              timeline:
                type: MyOS/MyOS Timeline
                timelineId: examples/vet-ext/celine-agent
              actor:
                type: MyOS/MyOS Agent Actor
                accountId: celine-agent
              type: Coordination/Timeline Channel
            providerChannel:
              description: Synchrony refund and adjustment Timeline
              timeline:
                type: MyOS/MyOS Timeline
                timelineId: examples/vet-ext/acme-bank-rep
              actor:
                type: MyOS/Principal Actor
                accountId: acme-bank-rep
              type: Coordination/Timeline Channel
            observeVisitConfirmation:
              description: Record the exact PUPPS confirmation covered by this PayNote.
              type: Coordination/Sequential Workflow
              channel: trainerChannel
              event:
                message:
                  type: Coordination/Operation Request
                  operation: confirmVisit
                  channel: trainerChannel
              steps:
                - name: Record confirmed visit coverage
                  type: Coordination/Compute
                  do:
                    - $if:
                        cond:
                          $eq: [$document: /trainingVisit/confirmed, false]
                        then:
                          - $appendChange: {op: replace, path: /trainingVisit/confirmed, val: true}
                    - $return: true
            observeVisitDone:
              description: Close completed visit coverage without changing PayNote money.
              type: Coordination/Sequential Workflow
              channel: customerChannel
              event:
                message:
                  type: Coordination/Operation Request
                  operation: confirmVisitHappened
                  channel: customerChannel
              steps:
                - name: Record normal completion
                  type: Coordination/Compute
                  do:
                    - $if:
                        cond:
                          $and:
                            - $eq: [$document: /trainingVisit/confirmed, true]
                            - $eq: [$document: /trainingVisit/terminalOutcome, None]
                        then:
                          - $appendChange: {op: replace, path: /trainingVisit/terminalOutcome, val: Completed}
                    - $return: true
            observeOnTimeCancellation:
              description: Request the exact puppy-training component refund once.
              type: Coordination/Sequential Workflow
              channel: customerChannel
              event:
                message:
                  type: Coordination/Operation Request
                  operation: cancelVisitWithinWindow
                  channel: customerChannel
              steps:
                - name: Request the on-time cancellation refund
                  type: Coordination/Compute
                  do:
                    - $if:
                        cond:
                          $and:
                            - $eq: [$document: /trainingVisit/confirmed, true]
                            - $eq: [$document: /trainingVisit/terminalOutcome, None]
                            - $eq: [$document: /refund/requested, false]
                        then:
                          - $appendChange: {op: replace, path: /trainingVisit/terminalOutcome, val: CancelledOnTime}
                          - $appendChange: {op: replace, path: /refund/requested, val: true}
                          - $appendChange: {op: replace, path: /refund/adjustment, val: false}
                          - $appendChange:
                              op: replace
                              path: /refund/requestId
                              val: {$binding: event/message/request/requestId}
                          - $appendChange: {op: replace, path: /refund/amountMinor, val: 27100}
                          - $appendChange:
                              op: replace
                              path: /refund/reason
                              val: {$binding: event/message/request/reason}
                          - $appendChange: {op: replace, path: /status, val: Refund Requested}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: PayNote/Refund Requested
                              requestId: {$binding: event/message/request/requestId}
                              requestedOperation: refundPayment
                              requestedOperationScopedKey: /payNote::refundPayment
                              sourceDocumentPath: /product/products/puppsOrder
                              targetDocumentPath: /payNote
                              recipientActorId: acme-bank-rep
                              amount:
                                amountMinor: 27100
                                currency: USD
                              reason: {$binding: event/message/request/reason}
                              policyBranch: onTime
                    - $return: true
            observeNoShowOrLateCancellation:
              description: Preserve provider settlement and request no refund for a late cancellation or no-show.
              type: Coordination/Sequential Workflow
              channel: trainerAgentChannel
              event:
                message:
                  type: Coordination/Operation Request
                  operation: recordLateCancellationOrNoShow
                  channel: trainerAgentChannel
              steps:
                - name: Close PayNote coverage without refund
                  type: Coordination/Compute
                  do:
                    - $if:
                        cond:
                          $and:
                            - $eq: [$document: /trainingVisit/confirmed, true]
                            - $eq: [$document: /trainingVisit/terminalOutcome, None]
                        then:
                          - $appendChange:
                              op: replace
                              path: /trainingVisit/terminalOutcome
                              val: {$binding: event/message/request/outcome}
                    - $return: true
            observeLowSatisfaction:
              description: Request the deterministic 10% puppy-training service adjustment once.
              type: Coordination/Sequential Workflow
              channel: customerChannel
              event:
                message:
                  type: Coordination/Operation Request
                  operation: confirmVisitWithLowSatisfaction
                  channel: customerChannel
              steps:
                - name: Request low-satisfaction service adjustment
                  type: Coordination/Compute
                  do:
                    - $if:
                        cond:
                          $and:
                            - $eq: [$document: /trainingVisit/confirmed, true]
                            - $eq: [$document: /trainingVisit/terminalOutcome, None]
                            - $eq: [$document: /refund/requested, false]
                            - $eq: [$binding: event/message/request/requestAdjustment, true]
                            - $eq: [$binding: event/message/request/score, 35]
                        then:
                          - $appendChange: {op: replace, path: /trainingVisit/terminalOutcome, val: CompletedLowSatisfaction}
                          - $appendChange: {op: replace, path: /refund/requested, val: true}
                          - $appendChange: {op: replace, path: /refund/adjustment, val: true}
                          - $appendChange: {op: replace, path: /refund/requestId, val: pupps-training-adjustment-001}
                          - $appendChange: {op: replace, path: /refund/amountMinor, val: 2710}
                          - $appendChange: {op: replace, path: /refund/reason, val: PUPS puppy training 10% service adjustment}
                          - $appendChange: {op: replace, path: /status, val: Service Adjustment Requested}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: PayNote/Refund Requested
                              requestId: pupps-training-adjustment-001
                              requestedOperation: refundPayment
                              requestedOperationScopedKey: /payNote::refundPayment
                              sourceDocumentPath: /product/products/puppsOrder
                              targetDocumentPath: /payNote
                              recipientActorId: acme-bank-rep
                              amount:
                                amountMinor: 2710
                                currency: USD
                              reason: PUPS puppy training 10% service adjustment
                              adjustmentPercent: 10
                    - $return: true
            refundPayment:
              description: Synchrony completes the exact requested refund or service adjustment.
              type: Coordination/Sequential Workflow Operation
              channel: providerChannel
              request:
                requestId: {type: Text}
                amountMinor: {type: Integer}
                currency:
                  type: Text
                note: {type: Text}
              steps:
                - name: Complete requested refund or adjustment exactly once
                  type: Coordination/Compute
                  do:
                    - $if:
                        cond:
                          $and:
                            - $eq: [$document: /refund/requested, true]
                            - $eq: [$document: /refund/completed, false]
                            - $eq:
                                - $document: /refund/requestId
                                - $binding: event/message/request/requestId
                            - $eq:
                                - $document: /refund/amountMinor
                                - $binding: event/message/request/amountMinor
                            - $eq: [$binding: event/message/request/currency, USD]
                        then:
                          - $appendChange: {op: replace, path: /refund/completed, val: true}
                          - $appendChange:
                              op: replace
                              path: /refund/completedAt
                              val: {$binding: event/timestamp}
                          - $appendChange:
                              op: replace
                              path: /amount/refundedMinor
                              val:
                                $add:
                                  - $document: /amount/refundedMinor
                                  - $document: /refund/amountMinor
                          - $appendChange: {op: replace, path: /status, val: Partial Refund Completed}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: PayNote/Refund Completed
                              requestId: {$binding: event/message/request/requestId}
                              amount:
                                amountMinor: {$binding: event/message/request/amountMinor}
                                currency: USD
                              note: {$binding: event/message/request/note}
                    - $return: true
        escalations:
          puppyTrainingLowSatisfaction:
            open: false
            score: 0
            comment: ""
        contracts:
          customerChannel:
            description: Maya's PawStart Order setup Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          vetChannel:
            description: East Side Veterinary Clinic PawStart setup Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          embedded:
            description: Process PUPPS initially, then activate Agreement and PayNote before their relevant live entries.
            type: Process Embedded
            paths:
              - /product/products/puppsOrder
          puppsOrderEvents:
            description: Bridge PUPPS Product outcomes into the customer-facing Order.
            type: Embedded Node Channel
            childPath: /product/products/puppsOrder
          payNoteEvents:
            description: Bridge PayNote financial outcomes into the customer-facing Order audit stream.
            type: Embedded Node Channel
            childPath: /payNote
          attachVetPuppsAgreement:
            description: Attach the exact Vet–PUPPS Agreement for later live processing.
            type: Coordination/Sequential Workflow Operation
            channel: vetChannel
            request:
              reason: {type: Text}
            steps:
              - name: Activate Agreement processing
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $eq: [$document: /initialization/agreementAttached, false]
                      then:
                        - $appendChange: {op: replace, path: /initialization/agreementAttached, val: true}
                        - $appendChange: {op: replace, path: /initialization/stage, val: Agreement attached}
                        - $appendChange:
                            op: replace
                            path: /contracts/embedded/paths
                            val: [/product/products/puppsOrder, /partnerAgreements/pupps]
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PawStart/Partner Agreement Attached
                            documentPath: /partnerAgreements/pupps
                            reason: {$binding: event/message/request/reason}
                  - $return: true
          attachPawStartPayNote:
            description: Attach the exact Synchrony-guaranteed PayNote for later live processing.
            type: Coordination/Sequential Workflow Operation
            channel: customerChannel
            request:
              reason: {type: Text}
            steps:
              - name: Activate PayNote processing
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /initialization/agreementAttached, true]
                          - $eq: [$document: /initialization/payNoteAttached, false]
                      then:
                        - $appendChange: {op: replace, path: /initialization/payNoteAttached, val: true}
                        - $appendChange: {op: replace, path: /initialization/stage, val: Ready}
                        - $appendChange:
                            op: replace
                            path: /contracts/embedded/paths
                            val: [/product/products/puppsOrder, /partnerAgreements/pupps, /payNote]
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PawStart/PayNote Attached
                            documentPath: /payNote
                            reason: {$binding: event/message/request/reason}
                  - $return: true
          recordVisitRequest:
            description: Journal the child request on the root Order.
            type: Coordination/Sequential Workflow
            channel: puppsOrderEvents
            event:
              type: Coordination/Event
              kind: Visit Scheduling Requested
            steps:
              - name: Record requested visit
                type: Coordination/Compute
                do:
                  - $appendChange: {op: replace, path: /trainingVisit/requestState, val: Requested}
                  - $appendChange:
                      op: replace
                      path: /trainingVisit/requestId
                      val: {$binding: event/requestId}
                  - $appendChange:
                      op: replace
                      path: /trainingVisit/preferredDate
                      val: {$binding: event/preferredDate}
                  - $appendChange:
                      op: replace
                      path: /trainingVisit/preferredTime
                      val: {$binding: event/preferredTime}
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /product/products/puppsOrder
                  - $return: true
          recordVisitConfirmation:
            description: Journal the child confirmation on the root Order.
            type: Coordination/Sequential Workflow
            channel: puppsOrderEvents
            event:
              type: Coordination/Event
              kind: Visit Confirmed
            steps:
              - name: Record confirmed visit
                type: Coordination/Compute
                do:
                  - $appendChange: {op: replace, path: /trainingVisit/confirmationState, val: Confirmed}
                  - $appendChange:
                      op: replace
                      path: /trainingVisit/date
                      val: {$binding: event/date}
                  - $appendChange:
                      op: replace
                      path: /trainingVisit/time
                      val: {$binding: event/time}
                  - $appendChange:
                      op: replace
                      path: /trainingVisit/trainer
                      val: {$binding: event/trainer}
                  - $appendChange: {op: replace, path: /status, val: Active - Training confirmed}
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /product/products/puppsOrder
                  - $return: true
          recordVisitDone:
            description: Journal normal Product completion on the root Order.
            type: Coordination/Sequential Workflow
            channel: puppsOrderEvents
            event:
              type: Coordination/Event
              kind: Commerce/Product Done
            steps:
              - name: Record completed Product
                type: Coordination/Update Document
                changeset:
                  - {op: replace, path: /trainingVisit/outcome, val: Completed}
                  - {op: replace, path: /trainingVisit/productState, val: Done}
                  - {op: replace, path: /status, val: Active - Training completed}
              - name: Publish completed Product outcome
                type: Coordination/Compute
                do:
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /product/products/puppsOrder
                  - $return: true
          recordOnTimeCancellation:
            description: Journal the component-specific cancellation on the root Order.
            type: Coordination/Sequential Workflow
            channel: puppsOrderEvents
            event:
              type: Coordination/Event
              kind: Commerce/Product Cancelled
            steps:
              - name: Record cancelled Product
                type: Coordination/Update Document
                changeset:
                  - {op: replace, path: /trainingVisit/outcome, val: Cancelled on time}
                  - {op: replace, path: /trainingVisit/productState, val: Cancelled}
                  - {op: replace, path: /status, val: Active - Puppy training cancelled}
              - name: Publish cancelled Product outcome
                type: Coordination/Compute
                do:
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /product/products/puppsOrder
                  - $return: true
          recordNoShow:
            description: Journal attendance failure without claiming delivery.
            type: Coordination/Sequential Workflow
            channel: puppsOrderEvents
            event:
              type: Coordination/Event
              kind: Commerce/No Show Recorded
            steps:
              - name: Record attendance outcome
        """;

    private static final String PAWSTART_PLAN_ORDER_PART_2 = """
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: replace
                      path: /trainingVisit/outcome
                      val: {$binding: event/outcome}
                  - $appendChange: {op: replace, path: /trainingVisit/productState, val: Attendance issue}
                  - $appendChange: {op: replace, path: /status, val: Active - Attendance outcome recorded}
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /product/products/puppsOrder
                  - $return: true
          recordLowSatisfaction:
            description: Journal the deterministic low-satisfaction issue.
            type: Coordination/Sequential Workflow
            channel: puppsOrderEvents
            event:
              type: Coordination/Event
              kind: Commerce/Satisfaction Submitted
            steps:
              - name: Record service issue
                type: Coordination/Compute
                do:
                  - $appendChange: {op: replace, path: /trainingVisit/outcome, val: Completed with low satisfaction}
                  - $appendChange: {op: replace, path: /trainingVisit/productState, val: Done}
                  - $appendChange:
                      op: replace
                      path: /trainingVisit/satisfaction/score
                      val: {$binding: event/score}
                  - $appendChange:
                      op: replace
                      path: /trainingVisit/satisfaction/comment
                      val: {$binding: event/comment}
                  - $appendChange: {op: replace, path: /escalations/puppyTrainingLowSatisfaction/open, val: true}
                  - $appendChange:
                      op: replace
                      path: /escalations/puppyTrainingLowSatisfaction/score
                      val: {$binding: event/score}
                  - $appendChange:
                      op: replace
                      path: /escalations/puppyTrainingLowSatisfaction/comment
                      val: {$binding: event/comment}
                  - $appendChange: {op: replace, path: /status, val: Active - Training issue open}
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /product/products/puppsOrder
                  - $return: true
          publishRefundRequested:
            description: Publish the embedded PayNote request as a root audit event.
            type: Coordination/Sequential Workflow
            channel: payNoteEvents
            event:
              type: Coordination/Event
              kind: PayNote/Refund Requested
            steps:
              - name: Publish refund request
                type: Coordination/Compute
                do:
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /payNote
                  - $return: true
          publishRefundCompleted:
            description: Publish the embedded PayNote completion as a root audit event.
            type: Coordination/Sequential Workflow
            channel: payNoteEvents
            event:
              type: Coordination/Event
              kind: PayNote/Refund Completed
            steps:
              - name: Publish refund completion
                type: Coordination/Compute
                do:
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /payNote
                  - $return: true
          customerAgentChannel:
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/alice-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: alice-agent
          vetAgentChannel:
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/bob-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: bob-agent
          trainerChannel:
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/celine
            actor:
              type: MyOS/Principal Actor
              accountId: celine
          trainerAgentChannel:
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/celine-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: celine-agent
          providerChannel:
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/acme-bank-rep
            actor:
              type: MyOS/Principal Actor
              accountId: acme-bank-rep
        """;

    public static final String PAWSTART_PLAN_ORDER = String.join(
            "",
            PAWSTART_PLAN_ORDER_PART_1,
            PAWSTART_PLAN_ORDER_PART_2
    );

    /** Authored pawstart-plan-paynote document. */
    public static final String PAWSTART_PLAN_PAYNOTE = """
        name: PawStart Full Plan PayNote
        payNoteType: PayNote/PayNote
        status: Payment Completed
        payer: Maya
        payee: East Side Veterinary Clinic
        guarantor: Synchrony
        amount:
          expectedTotalMinor: 129900
          capturedMinor: 129900
          refundedMinor: 0
          currency: USD
        capture:
          requested: true
          completed: true
          requestId: pawstart-payment-001
        refund:
          requested: false
          adjustment: false
          requestId:
          amountMinor: 0
          reason:
          completed: false
          completedAt:
        trainingVisit:
          confirmed: false
          terminalOutcome: None
        contracts:
          customerChannel:
            description: Maya's PawStart PayNote outcome Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          customerAgentChannel:
            description: Maya's agent Timeline for attributable delegated history
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/alice-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: alice-agent
          vetChannel:
            description: East Side Veterinary Clinic commercial Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          trainerChannel:
            description: PUPPS confirmation Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/celine
            actor:
              type: MyOS/Principal Actor
              accountId: celine
          trainerAgentChannel:
            description: PUPPS Synchrony attendance Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/celine-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: celine-agent
          providerChannel:
            description: Synchrony refund and adjustment Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/acme-bank-rep
            actor:
              type: MyOS/Principal Actor
              accountId: acme-bank-rep
          observeVisitConfirmation:
            description: Record the exact PUPPS confirmation covered by this PayNote.
            type: Coordination/Sequential Workflow
            channel: trainerChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: confirmVisit
                channel: trainerChannel
            steps:
              - name: Record confirmed visit coverage
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $eq: [$document: /trainingVisit/confirmed, false]
                      then:
                        - $appendChange: {op: replace, path: /trainingVisit/confirmed, val: true}
                  - $return: true
          observeVisitDone:
            description: Close completed visit coverage without changing PayNote money.
            type: Coordination/Sequential Workflow
            channel: customerChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: confirmVisitHappened
                channel: customerChannel
            steps:
              - name: Record normal completion
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /trainingVisit/confirmed, true]
                          - $eq: [$document: /trainingVisit/terminalOutcome, None]
                      then:
                        - $appendChange: {op: replace, path: /trainingVisit/terminalOutcome, val: Completed}
                  - $return: true
          observeOnTimeCancellation:
            description: Request the exact puppy-training component refund once.
            type: Coordination/Sequential Workflow
            channel: customerChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: cancelVisitWithinWindow
                channel: customerChannel
            steps:
              - name: Request the on-time cancellation refund
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /trainingVisit/confirmed, true]
                          - $eq: [$document: /trainingVisit/terminalOutcome, None]
                          - $eq: [$document: /refund/requested, false]
                      then:
                        - $appendChange: {op: replace, path: /trainingVisit/terminalOutcome, val: CancelledOnTime}
                        - $appendChange: {op: replace, path: /refund/requested, val: true}
                        - $appendChange: {op: replace, path: /refund/adjustment, val: false}
                        - $appendChange:
                            op: replace
                            path: /refund/requestId
                            val: {$binding: event/message/request/requestId}
                        - $appendChange: {op: replace, path: /refund/amountMinor, val: 27100}
                        - $appendChange:
                            op: replace
                            path: /refund/reason
                            val: {$binding: event/message/request/reason}
                        - $appendChange: {op: replace, path: /status, val: Refund Requested}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Refund Requested
                            requestId: {$binding: event/message/request/requestId}
                            requestedOperation: refundPayment
                            requestedOperationScopedKey: /payNote::refundPayment
                            sourceDocumentPath: /product/products/puppsOrder
                            targetDocumentPath: /payNote
                            recipientActorId: acme-bank-rep
                            amount:
                              amountMinor: 27100
                              currency: USD
                            reason: {$binding: event/message/request/reason}
                            policyBranch: onTime
                  - $return: true
          observeNoShowOrLateCancellation:
            description: Preserve provider settlement and request no refund for a late cancellation or no-show.
            type: Coordination/Sequential Workflow
            channel: trainerAgentChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: recordLateCancellationOrNoShow
                channel: trainerAgentChannel
            steps:
              - name: Close PayNote coverage without refund
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /trainingVisit/confirmed, true]
                          - $eq: [$document: /trainingVisit/terminalOutcome, None]
                      then:
                        - $appendChange:
                            op: replace
                            path: /trainingVisit/terminalOutcome
                            val: {$binding: event/message/request/outcome}
                  - $return: true
          observeLowSatisfaction:
            description: Request the deterministic 10% puppy-training service adjustment once.
            type: Coordination/Sequential Workflow
            channel: customerChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: confirmVisitWithLowSatisfaction
                channel: customerChannel
            steps:
              - name: Request low-satisfaction service adjustment
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /trainingVisit/confirmed, true]
                          - $eq: [$document: /trainingVisit/terminalOutcome, None]
                          - $eq: [$document: /refund/requested, false]
                          - $eq: [$binding: event/message/request/requestAdjustment, true]
                          - $eq: [$binding: event/message/request/score, 35]
                      then:
                        - $appendChange: {op: replace, path: /trainingVisit/terminalOutcome, val: CompletedLowSatisfaction}
                        - $appendChange: {op: replace, path: /refund/requested, val: true}
                        - $appendChange: {op: replace, path: /refund/adjustment, val: true}
                        - $appendChange: {op: replace, path: /refund/requestId, val: pupps-training-adjustment-001}
                        - $appendChange: {op: replace, path: /refund/amountMinor, val: 2710}
                        - $appendChange: {op: replace, path: /refund/reason, val: PUPS puppy training 10% service adjustment}
                        - $appendChange: {op: replace, path: /status, val: Service Adjustment Requested}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Refund Requested
                            requestId: pupps-training-adjustment-001
                            requestedOperation: refundPayment
                            requestedOperationScopedKey: /payNote::refundPayment
                            sourceDocumentPath: /product/products/puppsOrder
                            targetDocumentPath: /payNote
                            recipientActorId: acme-bank-rep
                            amount:
                              amountMinor: 2710
                              currency: USD
                            reason: PUPS puppy training 10% service adjustment
                            adjustmentPercent: 10
                  - $return: true
          refundPayment:
            description: Synchrony completes the exact requested refund or service adjustment.
            type: Coordination/Sequential Workflow Operation
            channel: providerChannel
            request:
              requestId: {type: Text}
              amountMinor: {type: Integer}
              currency:
                type: Text
              note: {type: Text}
            steps:
              - name: Complete requested refund or adjustment exactly once
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /refund/requested, true]
                          - $eq: [$document: /refund/completed, false]
                          - $eq:
                              - $document: /refund/requestId
                              - $binding: event/message/request/requestId
                          - $eq:
                              - $document: /refund/amountMinor
                              - $binding: event/message/request/amountMinor
                          - $eq: [$binding: event/message/request/currency, USD]
                      then:
                        - $appendChange: {op: replace, path: /refund/completed, val: true}
                        - $appendChange:
                            op: replace
                            path: /refund/completedAt
                            val: {$binding: event/timestamp}
                        - $appendChange:
                            op: replace
                            path: /amount/refundedMinor
                            val:
                              $add:
                                - $document: /amount/refundedMinor
                                - $document: /refund/amountMinor
                        - $appendChange: {op: replace, path: /status, val: Partial Refund Completed}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Refund Completed
                            requestId: {$binding: event/message/request/requestId}
                            amount:
                              amountMinor: {$binding: event/message/request/amountMinor}
                              currency: USD
                            note: {$binding: event/message/request/note}
                  - $return: true
        """;

    /** Authored pupps-grooming-order document. */
    public static final String PUPPS_GROOMING_ORDER = """
        name: PUPPS Grooming Order
        commerceType: Commerce/Bundle Product
        status: Active - training not scheduled
        terminalOutcome: None
        provider: PUPPS
        providerActorId: celine
        products:
          daycareStarter:
            name: PUPS daycare starter
            commerceType: Commerce/Fixed Product
            retailAmountMinor: 24500
            amountMinor: 21900
            currency: USD
            status: Active
          puppyTraining:
            name: PUPS puppy training
            commerceType: Commerce/Bookable Product
            retailAmountMinor: 33500
            amountMinor: 27100
            currency: USD
            status: Not scheduled
            done: false
            cancelled: false
            satisfaction:
              score: 0
              comment: ""
            cancellationPolicy:
              onTime:
                cutoffHoursBefore: 24
                customerRefundAmountMinor: 27100
                providerSettlementAmountMinor: 0
              lateOrNoShow:
                customerRefundAmountMinor: 0
                providerSettlementAmountMinor: 27100
            satisfactionPolicy:
              lowScoreThreshold: 50
              serviceAdjustmentPercent: 10
              serviceAdjustmentAmountMinor: 2710
        pendingVisit:
          status: Not scheduled
          serviceKey:
          preferredDate:
          preferredTime:
          reason:
          requestId:
          requestedAt:
        confirmedVisit:
          confirmed: false
          date:
          time:
          trainer:
          notes:
          inResponseTo:
          confirmedAt:
        visitHistory: []
        outcomeCounts:
          requested: 0
          confirmed: 0
          completed: 0
          cancelledOnTime: 0
          lateCancellationOrNoShow: 0
          lowSatisfaction: 0
        contracts:
          customerChannel:
            description: Maya's effective PUPPS scheduling Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          customerAgentChannel:
            description: Maya's agent Timeline, eligible only through the scheduling Operation Mandate
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/alice-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: alice-agent
          vetChannel:
            description: East Side Veterinary Clinic coordination Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          vetAgentChannel:
            description: Vet's Synchrony coordination Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/bob-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: bob-agent
          trainerChannel:
            description: PUPPS visit-confirmation Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/celine
            actor:
              type: MyOS/Principal Actor
              accountId: celine
          trainerAgentChannel:
            description: PUPPS Synchrony fulfilment Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/celine-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: celine-agent
          scheduleVisit:
            description: Schedule the puppy-training visit included in Maya's PawStart Full Plan.
            type: Coordination/Sequential Workflow Operation
            channel: customerChannel
            request:
              serviceKey:
                type: Text
              preferredDate:
                type: Text
              preferredTime:
                type: Text
              reason:
                type: Text
              requestId:
                type: Text
            steps:
              - name: Request included puppy-training visit
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq:
                              - $document: /terminalOutcome
                              - None
                          - $eq:
                              - $document: /pendingVisit/status
                              - Not scheduled
                          - $eq:
                              - $binding: event/message/request/serviceKey
                              - puppyTraining
                      then:
                        - $appendChange: {op: replace, path: /status, val: Visit requested}
                        - $appendChange:
                            op: replace
                            path: /pendingVisit
                            val:
                              $merge:
                                - $document: /pendingVisit
                                - status: Visit requested
                                  serviceKey: {$binding: event/message/request/serviceKey}
                                  preferredDate: {$binding: event/message/request/preferredDate}
                                  preferredTime: {$binding: event/message/request/preferredTime}
                                  reason: {$binding: event/message/request/reason}
                                  requestId: {$binding: event/message/request/requestId}
                                  requestedAt: {$binding: event/timestamp}
                        - $appendChange: {op: replace, path: /products/puppyTraining/status, val: Visit requested}
                        - $appendChange:
                            op: replace
                            path: /outcomeCounts/requested
                            val: {$add: [$document: /outcomeCounts/requested, 1]}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Visit Scheduling Requested
                            serviceKey: {$binding: event/message/request/serviceKey}
                            preferredDate: {$binding: event/message/request/preferredDate}
                            preferredTime: {$binding: event/message/request/preferredTime}
                            reason: {$binding: event/message/request/reason}
                            requestId: {$binding: event/message/request/requestId}
                            requestedOperation: confirmVisit
                            requestedOperationScopedKey: /product/products/puppsOrder::confirmVisit
                            sourceDocumentPath: /product/products/puppsOrder
                            targetDocumentPath: /product/products/puppsOrder
                            recipientActorId: celine
                  - $return: true
          confirmVisit:
            description: PUPPS confirms the exact pending visit request.
            type: Coordination/Sequential Workflow Operation
            channel: trainerChannel
            request:
              date: {type: Text}
              time: {type: Text}
              trainer:
                type: Text
              notes: {type: Text}
              inResponseTo: {type: Text}
            steps:
              - name: Confirm requested puppy-training visit
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /terminalOutcome, None]
                          - $eq: [$document: /confirmedVisit/confirmed, false]
                          - $eq:
                              - $document: /pendingVisit/requestId
                              - $binding: event/message/request/inResponseTo
                      then:
                        - $appendChange: {op: replace, path: /status, val: Training confirmed}
                        - $appendChange:
                            op: replace
                            path: /confirmedVisit
                            val:
                              $merge:
                                - $document: /confirmedVisit
                                - confirmed: true
                                  date: {$binding: event/message/request/date}
                                  time: {$binding: event/message/request/time}
                                  trainer: {$binding: event/message/request/trainer}
                                  notes: {$binding: event/message/request/notes}
                                  inResponseTo: {$binding: event/message/request/inResponseTo}
                                  confirmedAt: {$binding: event/timestamp}
                        - $appendChange: {op: replace, path: /products/puppyTraining/status, val: Training confirmed}
                        - $appendChange:
                            op: replace
                            path: /outcomeCounts/confirmed
                            val: {$add: [$document: /outcomeCounts/confirmed, 1]}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Visit Confirmed
                            requestId: {$binding: event/message/request/inResponseTo}
                            inResponseTo: {$binding: event/message/request/inResponseTo}
                            date: {$binding: event/message/request/date}
                            time: {$binding: event/message/request/time}
                            trainer: {$binding: event/message/request/trainer}
                            notes: {$binding: event/message/request/notes}
                  - $return: true
          confirmVisitHappened:
            description: Maya confirms normal fulfilment of the puppy-training visit.
            type: Coordination/Sequential Workflow Operation
            channel: customerChannel
            request:
              confirmationCode: {type: Text}
              comment: {type: Text}
            steps:
              - name: Complete puppy-training Product exactly once
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /terminalOutcome, None]
                          - $eq: [$document: /confirmedVisit/confirmed, true]
                      then:
                        - $appendChange: {op: replace, path: /status, val: Training completed}
                        - $appendChange: {op: replace, path: /terminalOutcome, val: Completed}
                        - $appendChange: {op: replace, path: /products/puppyTraining/status, val: Done}
                        - $appendChange: {op: replace, path: /products/puppyTraining/done, val: true}
                        - $appendChange:
                            op: replace
                            path: /outcomeCounts/completed
                            val: {$add: [$document: /outcomeCounts/completed, 1]}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/Product Done
                            outcomeId: pupps-training-completed-001
                            requestId: {$document: /confirmedVisit/inResponseTo}
                            confirmationCode: {$binding: event/message/request/confirmationCode}
                            comment: {$binding: event/message/request/comment}
                            sourceProductPath: /product/products/puppsOrder/products/puppyTraining
                  - $return: true
          cancelVisitWithinWindow:
            description: Maya selects the explicit on-time cancellation policy branch.
            type: Coordination/Sequential Workflow Operation
            channel: customerChannel
            request:
              reason: {type: Text}
              requestId: {type: Text}
            steps:
              - name: Cancel puppy training under the on-time policy
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /terminalOutcome, None]
                          - $eq: [$document: /confirmedVisit/confirmed, true]
                      then:
                        - $appendChange: {op: replace, path: /status, val: Training cancelled on time}
                        - $appendChange: {op: replace, path: /terminalOutcome, val: CancelledOnTime}
                        - $appendChange: {op: replace, path: /products/puppyTraining/status, val: Cancelled on time}
                        - $appendChange: {op: replace, path: /products/puppyTraining/cancelled, val: true}
                        - $appendChange:
                            op: replace
                            path: /outcomeCounts/cancelledOnTime
                            val: {$add: [$document: /outcomeCounts/cancelledOnTime, 1]}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/Product Cancelled
                            outcomeId: {$binding: event/message/request/requestId}
                            requestId: {$binding: event/message/request/requestId}
                            policyBranch: onTime
                            reason: {$binding: event/message/request/reason}
                            customerRefundAmountMinor: 27100
                            providerSettlementAmountMinor: 0
                            sourceProductPath: /product/products/puppsOrder/products/puppyTraining
                  - $return: true
          recordLateCancellationOrNoShow:
            description: PUPPS Synchrony Agent records an authoritative attendance outcome without claiming delivery.
            type: Coordination/Sequential Workflow Operation
            channel: trainerAgentChannel
            request:
              reason: {type: Text}
              outcome:
                type: Text
            steps:
              - name: Record no-show or late cancellation
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /terminalOutcome, None]
                          - $eq: [$document: /confirmedVisit/confirmed, true]
                      then:
                        - $appendChange: {op: replace, path: /status, val: No-show / late cancellation recorded}
                        - $appendChange:
                            op: replace
                            path: /terminalOutcome
                            val: {$binding: event/message/request/outcome}
                        - $appendChange: {op: replace, path: /products/puppyTraining/status, val: No-show / late cancellation}
                        - $appendChange:
                            op: replace
                            path: /outcomeCounts/lateCancellationOrNoShow
                            val: {$add: [$document: /outcomeCounts/lateCancellationOrNoShow, 1]}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/No Show Recorded
                            outcomeId: pupps-training-attendance-001
                            outcome: {$binding: event/message/request/outcome}
                            reason: {$binding: event/message/request/reason}
                            customerRefundAmountMinor: 0
                            providerSettlementAmountMinor: 27100
                            sourceProductPath: /product/products/puppsOrder/products/puppyTraining
                  - $return: true
          confirmVisitWithLowSatisfaction:
            description: Maya confirms delivery and requests the deterministic 10% service adjustment.
            type: Coordination/Sequential Workflow Operation
            channel: customerChannel
            request:
              score: {type: Integer}
              comment: {type: Text}
              requestAdjustment: {type: Boolean}
            steps:
              - name: Complete training with a low-satisfaction issue
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /terminalOutcome, None]
                          - $eq: [$document: /confirmedVisit/confirmed, true]
                          - $eq: [$binding: event/message/request/requestAdjustment, true]
                          - $eq: [$binding: event/message/request/score, 35]
                      then:
                        - $appendChange: {op: replace, path: /status, val: Training completed - experience issue open}
                        - $appendChange: {op: replace, path: /terminalOutcome, val: CompletedLowSatisfaction}
                        - $appendChange: {op: replace, path: /products/puppyTraining/status, val: Done}
                        - $appendChange: {op: replace, path: /products/puppyTraining/done, val: true}
                        - $appendChange:
                            op: replace
                            path: /products/puppyTraining/satisfaction/score
                            val: {$binding: event/message/request/score}
                        - $appendChange:
                            op: replace
                            path: /products/puppyTraining/satisfaction/comment
                            val: {$binding: event/message/request/comment}
                        - $appendChange:
                            op: replace
                            path: /outcomeCounts/completed
                            val: {$add: [$document: /outcomeCounts/completed, 1]}
                        - $appendChange:
                            op: replace
                            path: /outcomeCounts/lowSatisfaction
                            val: {$add: [$document: /outcomeCounts/lowSatisfaction, 1]}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/Product Done
                            outcomeId: pupps-training-low-satisfaction-done-001
                            requestId: {$document: /confirmedVisit/inResponseTo}
                            sourceProductPath: /product/products/puppsOrder/products/puppyTraining
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/Satisfaction Submitted
                            outcomeId: pupps-training-low-satisfaction-001
                            score: {$binding: event/message/request/score}
                            comment: {$binding: event/message/request/comment}
                            requestAdjustment: {$binding: event/message/request/requestAdjustment}
                            serviceAdjustmentPercent: 10
                            serviceAdjustmentAmountMinor: 2710
                            sourceProductPath: /product/products/puppsOrder/products/puppyTraining
                  - $return: true
        """;

    /** Authored scheduling-mandate document. */
    public static final String SCHEDULING_MANDATE = """
        name: Maya Puppy-Training Scheduling Mandate
        type: Mandate/Operation Mandate
        activateOnAuthorityConfirmation: true
        target:
          initialDocument:
            blueId: "{{initialBlueId:pawstart-plan-order}}"
          channel: customerChannel
          operation: scheduleVisit
        validation:
          request:
            serviceKey: puppyTraining
        contracts:
          initializeMandate:
            event:
              document:
                type: Common/Document
          terminateMandate:
            request:
              reason: Customer ended autonomous scheduling access.
          applyMandateTermination:
            event:
              reason: Customer ended autonomous scheduling access.
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
            description: MyOS Admin's mandate-guarantor Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/myos-admin
            actor:
              type: MyOS/MyOS Admin Actor
              accountId: myos-admin
          authorityHolderChannel:
            description: Maya's authority-holder Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          authorizedActorChannel:
            description: Maya's agent Timeline receiving one bounded scheduling authority
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/alice-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: alice-agent
        """;

    /** Authored vet-pupps-agreement document. */
    public static final String VET_PUPPS_AGREEMENT = """
        name: Vet–PUPPS Agreement
        agreementType: Commerce/Partner Agreement
        status: Active
        requestedVisitCount: 0
        confirmedVisitCount: 0
        completedVisitCount: 0
        onTimeCancellationCount: 0
        lateCancellationOrNoShowCount: 0
        lowSatisfactionCount: 0
        visitRequestId:
        visitConfirmed: false
        terminalOutcome: None
        lastPuppsOutcome:
        puppyTrainingSettlementState: Not earned
        puppyTrainingAmountMinor: 27100
        currency: USD
        openIssues:
          puppyTrainingLowSatisfaction:
            open: false
            score: 0
            comment: ""
        contracts:
          customerChannel:
            description: Maya's Vet–PUPPS Agreement outcome Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          customerAgentChannel:
            description: Maya's agent Timeline for attributable delegated scheduling history
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/alice-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: alice-agent
          vetChannel:
            description: East Side Veterinary Clinic Agreement Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          vetAgentChannel:
            description: Vet's Synchrony Agreement Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/bob-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: bob-agent
          trainerChannel:
            description: PUPPS Agreement Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/celine
            actor:
              type: MyOS/Principal Actor
              accountId: celine
          trainerAgentChannel:
            description: PUPPS Synchrony Agreement Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/vet-ext/celine-agent
            actor:
              type: MyOS/MyOS Agent Actor
              accountId: celine-agent
          observeVisitRequest:
            description: Record Maya's exact puppy-training request in the Agreement.
            type: Coordination/Sequential Workflow
            channel: customerAgentChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: scheduleVisit
                channel: customerChannel
            steps:
              - name: Record requested PUPPS visit
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /requestedVisitCount, 0]
                          - $eq: [$binding: event/message/request/serviceKey, puppyTraining]
                      then:
                        - $appendChange:
                            op: replace
                            path: /visitRequestId
                            val: {$binding: event/message/request/requestId}
                        - $appendChange:
                            op: replace
                            path: /requestedVisitCount
                            val: {$add: [$document: /requestedVisitCount, 1]}
                        - $appendChange: {op: replace, path: /lastPuppsOutcome, val: Visit Scheduling Requested}
                  - $return: true
          observeVisitConfirmation:
            description: Record PUPPS's confirmation once and correlate it to Maya's request.
            type: Coordination/Sequential Workflow
            channel: trainerChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: confirmVisit
                channel: trainerChannel
            steps:
              - name: Record confirmed PUPPS visit
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /visitConfirmed, false]
                          - $eq: [$document: /terminalOutcome, None]
                          - $eq:
                              - $document: /visitRequestId
                              - $binding: event/message/request/inResponseTo
                      then:
                        - $appendChange: {op: replace, path: /visitConfirmed, val: true}
                        - $appendChange:
                            op: replace
                            path: /confirmedVisitCount
                            val: {$add: [$document: /confirmedVisitCount, 1]}
                        - $appendChange: {op: replace, path: /lastPuppsOutcome, val: Visit Confirmed}
                  - $return: true
          observeVisitDone:
            description: Earn the PUPPS settlement for normal completion exactly once.
            type: Coordination/Sequential Workflow
            channel: customerChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: confirmVisitHappened
                channel: customerChannel
            steps:
              - name: Settle completed PUPPS visit
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /visitConfirmed, true]
                          - $eq: [$document: /terminalOutcome, None]
                      then:
                        - $appendChange: {op: replace, path: /terminalOutcome, val: Completed}
                        - $appendChange:
                            op: replace
                            path: /completedVisitCount
                            val: {$add: [$document: /completedVisitCount, 1]}
                        - $appendChange: {op: replace, path: /lastPuppsOutcome, val: Commerce/Product Done}
                        - $appendChange: {op: replace, path: /puppyTrainingSettlementState, val: Earned}
                  - $return: true
          observeOnTimeCancellation:
            description: Record an on-time cancellation with no provider settlement.
            type: Coordination/Sequential Workflow
            channel: customerChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: cancelVisitWithinWindow
                channel: customerChannel
            steps:
              - name: Settle on-time PUPPS cancellation
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /visitConfirmed, true]
                          - $eq: [$document: /terminalOutcome, None]
                      then:
                        - $appendChange: {op: replace, path: /terminalOutcome, val: CancelledOnTime}
                        - $appendChange:
                            op: replace
                            path: /onTimeCancellationCount
                            val: {$add: [$document: /onTimeCancellationCount, 1]}
                        - $appendChange: {op: replace, path: /lastPuppsOutcome, val: Commerce/Product Cancelled}
                        - $appendChange: {op: replace, path: /puppyTrainingSettlementState, val: Not earned}
                  - $return: true
          observeNoShowOrLateCancellation:
            description: Preserve the PUPPS settlement without describing the Product as delivered.
            type: Coordination/Sequential Workflow
            channel: trainerAgentChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: recordLateCancellationOrNoShow
                channel: trainerAgentChannel
            steps:
              - name: Settle PUPPS attendance outcome
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /visitConfirmed, true]
                          - $eq: [$document: /terminalOutcome, None]
                      then:
                        - $appendChange:
                            op: replace
                            path: /terminalOutcome
                            val: {$binding: event/message/request/outcome}
                        - $appendChange:
                            op: replace
                            path: /lateCancellationOrNoShowCount
                            val: {$add: [$document: /lateCancellationOrNoShowCount, 1]}
                        - $appendChange:
                            op: replace
                            path: /lastPuppsOutcome
                            val: {$binding: event/message/request/outcome}
                        - $appendChange: {op: replace, path: /puppyTrainingSettlementState, val: Earned}
                  - $return: true
          observeLowSatisfaction:
            description: Earn settlement while opening the deterministic service-quality issue.
            type: Coordination/Sequential Workflow
            channel: customerChannel
            event:
              message:
                type: Coordination/Operation Request
                operation: confirmVisitWithLowSatisfaction
                channel: customerChannel
            steps:
              - name: Record completed PUPPS visit quality issue
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /visitConfirmed, true]
                          - $eq: [$document: /terminalOutcome, None]
                          - $eq: [$binding: event/message/request/requestAdjustment, true]
                          - $eq: [$binding: event/message/request/score, 35]
                      then:
                        - $appendChange: {op: replace, path: /terminalOutcome, val: CompletedLowSatisfaction}
                        - $appendChange:
                            op: replace
                            path: /completedVisitCount
                            val: {$add: [$document: /completedVisitCount, 1]}
                        - $appendChange:
                            op: replace
                            path: /lowSatisfactionCount
                            val: {$add: [$document: /lowSatisfactionCount, 1]}
                        - $appendChange: {op: replace, path: /lastPuppsOutcome, val: Commerce/Satisfaction Submitted}
                        - $appendChange: {op: replace, path: /puppyTrainingSettlementState, val: Earned}
                        - $appendChange: {op: replace, path: /openIssues/puppyTrainingLowSatisfaction/open, val: true}
                        - $appendChange:
                            op: replace
                            path: /openIssues/puppyTrainingLowSatisfaction/score
                            val: {$binding: event/message/request/score}
                        - $appendChange:
                            op: replace
                            path: /openIssues/puppyTrainingLowSatisfaction/comment
                            val: {$binding: event/message/request/comment}
                  - $return: true
        """;

}
