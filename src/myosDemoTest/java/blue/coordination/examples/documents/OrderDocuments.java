package blue.coordination.examples.documents;

/** Complete participant-bound Blue documents for the Wadowice Hotel and Dinner example. */
public final class OrderDocuments {

    private OrderDocuments() {
    }

    /** Authored package-paynote document. */
    public static final String PACKAGE_PAYNOTE = """
        name: ACME Hotel & Dinner PayNote
        status: Awaiting Product Conditions
        attachedBy: Alice
        validationMethod: "Order policy: exact amount, PLN, ACME guarantor"
        payer: {actorId: alice, name: Alice}
        payee: {actorId: bob, name: Travel Agency}
        guarantor: {actorId: myos-admin, name: Acme Bank}
        currency: PLN
        authorizationAuthorizedAmountMinorState: 0
        authorizationCountState: 0
        hotelConditionAttachedState: false
        restaurantConditionAttachedState: false
        hotelConfirmedState: false
        restaurantConfirmedState: false
        captureReadinessConfirmedState: 0
        captureRequestedState: false
        captureRequestedAtState: 0
        captureCompletedState: false
        refundRequestedState: false
        refundCompletedState: false
        refundRequestIdState: none
        refundAmountMinorState: 0
        refundReasonState: none
        capturedAmountMinorState: 0
        amount:
          expectedTotal: 130000
          expected: 130000
          captured: 0
          currency: PLN
        authorization:
          state: Not Authorized
          authorizationId:
          authorizedAmountMinor: 0
          currency: PLN
          authorizedAt:
          authorizationCount: 0
        attachedConditions: {hotel: false, restaurant: false}
        captureReadiness: {confirmed: 0, required: 2}
        capture:
          requested: false
          requestCount: 0
          requestId:
          requestedAt:
          completed: false
          completedAt:
          capturedBy:
        refund:
          requested: false
          requestId:
          amountMinor: 0
          reason:
          completed: false
          completedAt:
        productConditions:
          hotel:
            sourceProductPath: /product/products/hotel
            expectedProductKey: hotel
            expectedProductName: Hotel Mlyn Jacka Stay
            expectedProductIdentity: wadowice-order-2026-v1:hotel:v1
            sourceOrderId: wadowice-order-2026-v1
            status: Listening
            deliveryStatus: Awaiting live confirmation
            confirmed: false
            done: false
            captureConditionSatisfied: false
            lastProcessedSourceTimestamp:
            product:
              name: Hotel Mlyn Jacka Stay Condition Listener
              productKey: hotel
              sourceOrderId: wadowice-order-2026-v1
              confirmed: false
              done: false
              contracts:
                providerChannel:
                  description: Live Wadowice Hotel Product Timeline
                  type: Coordination/Timeline Channel
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/order/celine
                  actor:
                    type: MyOS/Principal Actor
                    accountId: celine
                confirmProduct:
                  type: Coordination/Sequential Workflow Operation
                  channel: providerChannel
                  request:
                    confirmationReference: {type: Text}
                  steps:
                    - name: Apply Live Hotel Confirmation
                      type: Coordination/Compute
                      do:
                        - $appendChange: {op: replace, path: /confirmed, val: true}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Condition Product Confirmed
                            productKey: hotel
                            sourcePath: /product/products/hotel
                            sourceActorId: celine
                            sourceTimestamp: {$binding: event/timestamp}
                        - $return: true
                completeProduct:
                  type: Coordination/Sequential Workflow Operation
                  channel: providerChannel
                  request:
                    confirmationCode: {type: Text}
                    note: {type: Text}
                  steps:
                    - name: Apply Live Hotel Completion
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond: {$eq: [$binding: event/message/request/confirmationCode, WAD-7429]}
                            then:
                              - $appendChange: {op: replace, path: /done, val: true}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: PayNote/Condition Product Done
                                  productKey: hotel
                                  sourcePath: /product/products/hotel
                                  sourceActorId: celine
                                  sourceTimestamp: {$binding: event/timestamp}
                        - $return: true
          restaurant:
            sourceProductPath: /product/products/restaurant
            expectedProductKey: restaurant
            expectedProductName: Old Town Restaurant Dinner
            expectedProductIdentity: wadowice-order-2026-v1:restaurant:v1
            sourceOrderId: wadowice-order-2026-v1
            status: Listening
            deliveryStatus: Awaiting live confirmation
            confirmed: false
            done: false
            cancelled: false
            discountApplied: false
            captureConditionSatisfied: false
            lastProcessedSourceTimestamp:
            product:
              name: Old Town Restaurant Condition Listener
              productKey: restaurant
              sourceOrderId: wadowice-order-2026-v1
              confirmed: false
              done: false
              cancelled: false
              discountApplied: false
              contracts:
                providerChannel:
                  description: Live Old Town Restaurant Product Timeline
                  type: Coordination/Timeline Channel
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/order/david
                  actor:
                    type: MyOS/Principal Actor
                    accountId: david
                customerChannel:
                  description: Live Alice Restaurant cancellation Timeline
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/order/alice
                  actor:
                    type: MyOS/Principal Actor
                    accountId: alice
                  type: Coordination/Timeline Channel
                confirmProduct:
                  type: Coordination/Sequential Workflow Operation
                  channel: providerChannel
                  request:
                    confirmationReference: {type: Text}
                  steps:
                    - name: Apply Live Restaurant Confirmation
                      type: Coordination/Compute
                      do:
                        - $appendChange: {op: replace, path: /confirmed, val: true}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Condition Product Confirmed
                            productKey: restaurant
                            sourcePath: /product/products/restaurant
                            sourceActorId: david
                            sourceTimestamp: {$binding: event/timestamp}
                        - $return: true
                completeProduct:
                  type: Coordination/Sequential Workflow Operation
                  channel: providerChannel
                  request:
                    confirmationCode: {type: Text}
                    note: {type: Text}
                  steps:
                    - name: Apply Live Restaurant Completion
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond: {$eq: [$binding: event/message/request/confirmationCode, WAD-7429]}
                            then:
                              - $appendChange: {op: replace, path: /done, val: true}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: PayNote/Condition Product Done
                                  productKey: restaurant
                                  sourcePath: /product/products/restaurant
                                  sourceActorId: david
                                  sourceTimestamp: {$binding: event/timestamp}
                        - $return: true
                completeWithDiscount:
                  type: Coordination/Sequential Workflow Operation
                  channel: providerChannel
                  request:
                    confirmationCode: {type: Text}
                    note: {type: Text}
                  steps:
                    - name: Apply Live Restaurant Discount Completion
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond: {$eq: [$binding: event/message/request/confirmationCode, WAD-7429]}
                            then:
                              - $appendChange: {op: replace, path: /done, val: true}
                              - $appendChange: {op: replace, path: /discountApplied, val: true}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: PayNote/Condition Product Done
                                  productKey: restaurant
                                  sourcePath: /product/products/restaurant
                                  sourceActorId: david
                                  sourceTimestamp: {$binding: event/timestamp}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: PayNote/Condition Product Discount Applied
                                  productKey: restaurant
                                  sourcePath: /product/products/restaurant
                                  sourceActorId: david
                                  sourceTimestamp: {$binding: event/timestamp}
                                  discountPercent: 10
                                  amountMinor: 3800
                        - $return: true
                cancelWithinRange:
                  type: Coordination/Sequential Workflow Operation
                  channel: customerChannel
                  request:
                    reason: {type: Text}
                  steps:
                    - name: Apply Live Restaurant Cancellation
                      type: Coordination/Compute
                      do:
                        - $appendChange: {op: replace, path: /cancelled, val: true}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Condition Product Cancelled
                            productKey: restaurant
                            sourcePath: /product/products/restaurant
                            sourceActorId: alice
                            sourceTimestamp: {$binding: event/timestamp}
                            refundable: true
                            amountMinor: 38000
                            reason: {$binding: event/message/request/reason}
                        - $return: true
        contracts:
          payerChannel:
            description: Alice PayNote Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/order/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          payeeChannel:
            description: Travel Agency PayNote Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/order/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          customerChannel:
            description: Alice Order payment Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/order/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          merchantChannel:
            description: Travel Agency payment Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/order/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          guarantorChannel:
            description: ACME guarantor Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/order/myos-admin
            actor:
              type: MyOS/MyOS Admin Actor
              accountId: myos-admin
          authorizeAmount:
            name: Authorize PayNote Amount
            description: Record one immutable ACME authorization decision from the guarantor Timeline.
            type: Coordination/Sequential Workflow Operation
            channel: guarantorChannel
            request:
              authorizationId: {type: Text}
              amountMinor: {type: Integer}
              currency: {type: Text}
            steps:
              - name: Apply Amount Authorization
                type: Coordination/Compute
                do:
                  - $let:
                      order:
                        - authorizationId
                        - amountMinor
                        - requestedCurrency
                      vars:
                        authorizationId: {$binding: event/message/request/authorizationId}
                        amountMinor: {$binding: event/message/request/amountMinor}
                        requestedCurrency: {$binding: event/message/request/currency}
                  - $if:
                      cond:
                        $or:
                          - $not:
                              $truthy: {$var: authorizationId}
                          - $lte: [$var: amountMinor, 0]
                          - $ne: [$var: requestedCurrency, $document: /currency]
                      then:
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Validation Error
                            message: Amount authorization requires a non-empty id, a positive amount, and the PayNote currency.
                  - $if:
                      cond:
                        $and:
                          - $truthy: {$var: authorizationId}
                          - $not:
                              $lte: [$var: amountMinor, 0]
                          - $eq: [$var: requestedCurrency, $document: /currency]
                      then:
                        - $if:
                            cond: {$eq: [$document: /authorizationCountState, 1]}
                            then:
                              - $appendChange:
                                  op: replace
                                  path: /authorization
                                  val:
                                    state: Authorized
                                    authorizationId: {$var: authorizationId}
                                    authorizedAmountMinor:
                                      $add:
                                        - $document: /authorizationAuthorizedAmountMinorState
                                        - $var: amountMinor
                                    currency: {$var: requestedCurrency}
                                    authorizedAt: {$binding: event/timestamp}
                                    authorizationCount: {$add: [$document: /authorizationCountState, 1]}
                        - $appendChange:
                            op: replace
                            path: /authorizationAuthorizedAmountMinorState
                            val:
                              $add:
                                - $document: /authorizationAuthorizedAmountMinorState
                                - $var: amountMinor
                        - $appendChange:
                            op: replace
                            path: /authorizationCountState
                            val: {$add: [$document: /authorizationCountState, 1]}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Amount Authorized
                            authorizationId: {$var: authorizationId}
                            amountMinor: {$var: amountMinor}
                            currency: {$var: requestedCurrency}
                            authorizedBy: myos-admin
                            authorizedAt: {$binding: event/timestamp}
                  - $return: true
          hotelConditionEvents:
            type: Embedded Node Channel
            childPath: /productConditions/hotel/product
          restaurantConditionEvents:
            type: Embedded Node Channel
            childPath: /productConditions/restaurant/product
          attachHotelCondition:
            name: Attach Wadowice Hotel as Capture Condition
            description: Attach the trusted Hotel view for later provider entries.
            type: Coordination/Sequential Workflow Operation
            channel: merchantChannel
            request:
              productKey: {type: Text}
              sourceProductPath: {type: Text}
              expectedProductName: {type: Text}
              expectedProductIdentity: {type: Text}
              sourceOrderId: {type: Text}
            steps:
              - name: Attach Hotel Product Condition
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /hotelConditionAttachedState, false]
                          - $eq: [$binding: event/message/request/productKey, hotel]
                          - $eq: [$binding: event/message/request/sourceProductPath, /product/products/hotel]
                          - $eq: [$binding: event/message/request/expectedProductName, Hotel Mlyn Jacka Stay]
                          - $eq: [$binding: event/message/request/expectedProductIdentity, "wadowice-order-2026-v1:hotel:v1"]
                          - $eq: [$binding: event/message/request/sourceOrderId, wadowice-order-2026-v1]
                      then:
                        - $appendChange:
                            op: replace
                            path: /attachedConditions
                            val:
                              hotel: true
                              restaurant: {$document: /restaurantConditionAttachedState}
                        - $appendChange: {op: replace, path: /hotelConditionAttachedState, val: true}
                        - $appendChange: {op: replace, path: /status, val: Awaiting Product Confirmations}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Product Condition Attached
                            productKey: hotel
                            sourcePath: /product/products/hotel
                  - $return: true
          attachRestaurantCondition:
            name: Attach Old Town Restaurant as Capture Condition
            description: Attach the trusted Restaurant view for later provider entries.
            type: Coordination/Sequential Workflow Operation
            channel: merchantChannel
            request:
              productKey: {type: Text}
              sourceProductPath: {type: Text}
              expectedProductName: {type: Text}
              expectedProductIdentity: {type: Text}
              sourceOrderId: {type: Text}
            steps:
              - name: Attach Restaurant Product Condition
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /restaurantConditionAttachedState, false]
                          - $eq: [$binding: event/message/request/productKey, restaurant]
                          - $eq: [$binding: event/message/request/sourceProductPath, /product/products/restaurant]
                          - $eq: [$binding: event/message/request/expectedProductName, Old Town Restaurant Dinner]
                          - $eq: [$binding: event/message/request/expectedProductIdentity, "wadowice-order-2026-v1:restaurant:v1"]
                          - $eq: [$binding: event/message/request/sourceOrderId, wadowice-order-2026-v1]
                      then:
                        - $appendChange:
                            op: replace
                            path: /attachedConditions
                            val:
                              hotel: {$document: /hotelConditionAttachedState}
                              restaurant: true
                        - $appendChange: {op: replace, path: /restaurantConditionAttachedState, val: true}
                        - $appendChange: {op: replace, path: /status, val: Awaiting Product Confirmations}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Product Condition Attached
                            productKey: restaurant
                            sourcePath: /product/products/restaurant
                  - $return: true
          observeHotelConfirmed:
            type: Coordination/Sequential Workflow
            channel: hotelConditionEvents
            event: {type: Coordination/Event, kind: PayNote/Condition Product Confirmed}
            steps:
              - name: Apply Hotel Confirmation Condition
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$eq: [$document: /hotelConfirmedState, false]}
                      then:
                        # Keep leaf patches here: this condition object owns an active
                        # Process Embedded `product`, so replacing the parent from a
                        # frozen $document view can rewind the child's live state.
                        - $appendChange: {op: replace, path: /productConditions/hotel/confirmed, val: true}
                        - $appendChange: {op: replace, path: /hotelConfirmedState, val: true}
                        - $appendChange: {op: replace, path: /productConditions/hotel/captureConditionSatisfied, val: true}
                        - $appendChange: {op: replace, path: /productConditions/hotel/status, val: Confirmed}
                        - $appendChange: {op: replace, path: /productConditions/hotel/deliveryStatus, val: Live confirmation
                              received}
                        - $appendChange:
                            op: replace
                            path: /productConditions/hotel/lastProcessedSourceTimestamp
                            val: {$binding: event/sourceTimestamp}
                        - $appendChange:
                            op: replace
                            path: /captureReadiness
                            val:
                              confirmed: {$add: [$document: /captureReadinessConfirmedState, 1]}
                              required: 2
                        - $appendChange:
                            op: replace
                            path: /captureReadinessConfirmedState
                            val: {$add: [$document: /captureReadinessConfirmedState, 1]}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Product Condition Satisfied
                            productKey: hotel
                            sourcePath: /product/products/hotel
                  - $return: true
              - name: Request Capture after Hotel Condition
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /hotelConfirmedState, true]
                          - $eq: [$document: /restaurantConfirmedState, true]
                          - $eq: [$document: /captureRequestedState, false]
                      then:
                        - $appendChange:
                            op: replace
                            path: /capture
                            val:
                              requested: true
                              requestCount: 1
                              requestId: package-capture-001
                              requestedAt: {$binding: event/sourceTimestamp}
                              completed: false
                              completedAt:
                              capturedBy:
                        - $appendChange: {op: replace, path: /captureRequestedState, val: true}
                        - $appendChange:
                            op: replace
                            path: /captureRequestedAtState
                            val: {$binding: event/sourceTimestamp}
                        - $appendChange: {op: replace, path: /status, val: Awaiting ACME Capture}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Capture Funds Requested
                            requestId: package-capture-001
                            requestedOperation: capturePayment
                            requestedOperationScopedKey: /payNotes/packagePayment::capturePayment
                            sourceDocumentPath: /payNotes/packagePayment/productConditions/hotel/product
                            targetDocumentPath: /payNotes/packagePayment
                            recipientActorId: myos-admin
                            amount: {amountMinor: 130000, currency: PLN}
                  - $return: true
          observeRestaurantConfirmed:
            type: Coordination/Sequential Workflow
            channel: restaurantConditionEvents
            event: {type: Coordination/Event, kind: PayNote/Condition Product Confirmed}
            steps:
              - name: Apply Restaurant Confirmation Condition
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$eq: [$document: /restaurantConfirmedState, false]}
                      then:
                        - $appendChange: {op: replace, path: /productConditions/restaurant/confirmed, val: true}
                        - $appendChange: {op: replace, path: /restaurantConfirmedState, val: true}
                        - $appendChange: {op: replace, path: /productConditions/restaurant/captureConditionSatisfied, val: true}
                        - $appendChange: {op: replace, path: /productConditions/restaurant/status, val: Confirmed}
                        - $appendChange: {op: replace, path: /productConditions/restaurant/deliveryStatus, val: Live
                              confirmation received}
                        - $appendChange:
                            op: replace
                            path: /productConditions/restaurant/lastProcessedSourceTimestamp
                            val: {$binding: event/sourceTimestamp}
                        - $appendChange:
                            op: replace
                            path: /captureReadiness
                            val:
                              confirmed: {$add: [$document: /captureReadinessConfirmedState, 1]}
                              required: 2
                        - $appendChange:
                            op: replace
                            path: /captureReadinessConfirmedState
                            val: {$add: [$document: /captureReadinessConfirmedState, 1]}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Product Condition Satisfied
                            productKey: restaurant
                            sourcePath: /product/products/restaurant
                  - $return: true
              - name: Request Capture after Restaurant Condition
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /hotelConfirmedState, true]
                          - $eq: [$document: /restaurantConfirmedState, true]
                          - $eq: [$document: /captureRequestedState, false]
                      then:
                        - $appendChange:
                            op: replace
                            path: /capture
                            val:
                              requested: true
                              requestCount: 1
                              requestId: package-capture-001
                              requestedAt: {$binding: event/sourceTimestamp}
                              completed: false
                              completedAt:
                              capturedBy:
                        - $appendChange: {op: replace, path: /captureRequestedState, val: true}
                        - $appendChange:
                            op: replace
                            path: /captureRequestedAtState
                            val: {$binding: event/sourceTimestamp}
                        - $appendChange: {op: replace, path: /status, val: Awaiting ACME Capture}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Capture Funds Requested
                            requestId: package-capture-001
                            requestedOperation: capturePayment
                            requestedOperationScopedKey: /payNotes/packagePayment::capturePayment
                            sourceDocumentPath: /payNotes/packagePayment/productConditions/restaurant/product
                            targetDocumentPath: /payNotes/packagePayment
                            recipientActorId: myos-admin
                            amount: {amountMinor: 130000, currency: PLN}
                  - $return: true
          observeHotelDone:
            type: Coordination/Sequential Workflow
            channel: hotelConditionEvents
            event: {type: Coordination/Event, kind: PayNote/Condition Product Done}
            steps:
              - name: Apply Hotel Completion
                type: Coordination/Compute
                do:
                  - $appendChange: {op: replace, path: /productConditions/hotel/done, val: true}
                  - $appendChange: {op: replace, path: /productConditions/hotel/status, val: Done}
                  - $return: true
          observeRestaurantDone:
            type: Coordination/Sequential Workflow
            channel: restaurantConditionEvents
            event: {type: Coordination/Event, kind: PayNote/Condition Product Done}
            steps:
              - name: Apply Restaurant Completion
                type: Coordination/Compute
                do:
                  - $appendChange: {op: replace, path: /productConditions/restaurant/done, val: true}
                  - $appendChange: {op: replace, path: /productConditions/restaurant/status, val: Done}
                  - $return: true
          observeRestaurantCancellation:
            type: Coordination/Sequential Workflow
            channel: restaurantConditionEvents
            event: {type: Coordination/Event, kind: PayNote/Condition Product Cancelled}
            steps:
              - name: Request Refund for Restaurant Cancellation
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$eq: [$document: /refundRequestedState, false]}
                      then:
                        - $appendChange: {op: replace, path: /productConditions/restaurant/cancelled, val: true}
                        - $appendChange: {op: replace, path: /productConditions/restaurant/status, val: Cancelled - Refund
                              Requested}
                        - $appendChange:
                            op: replace
                            path: /refund
                            val:
                              requested: true
                              requestId: restaurant-refund-001
                              amountMinor: 38000
                              reason: Restaurant cancelled within refund window
                              completed: false
                              completedAt:
                        - $appendChange: {op: replace, path: /refundRequestedState, val: true}
                        - $appendChange: {op: replace, path: /refundRequestIdState, val: restaurant-refund-001}
                        - $appendChange: {op: replace, path: /refundAmountMinorState, val: 38000}
                        - $appendChange: {op: replace, path: /refundReasonState, val: Restaurant cancelled within refund
                              window}
                        - $appendChange: {op: replace, path: /status, val: Restaurant Refund Requested}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Refund Requested
                            requestId: restaurant-refund-001
                            requestedOperation: refundPayment
                            requestedOperationScopedKey: /payNotes/packagePayment::refundPayment
                            recipientActorId: myos-admin
                            amount: {amountMinor: 38000, currency: PLN}
                            reason: Restaurant cancelled within refund window
                  - $return: true
          observeRestaurantDiscount:
            type: Coordination/Sequential Workflow
            channel: restaurantConditionEvents
            event: {type: Coordination/Event, kind: PayNote/Condition Product Discount Applied}
            steps:
              - name: Request Restaurant Discount Refund
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$eq: [$document: /refundRequestedState, false]}
                      then:
                        - $appendChange: {op: replace, path: /productConditions/restaurant/discountApplied, val: true}
                        - $appendChange:
                            op: replace
                            path: /refund
                            val:
                              requested: true
                              requestId: restaurant-discount-001
                              amountMinor: 3800
                              reason: Restaurant 10% service discount
                              completed: false
                              completedAt:
                        - $appendChange: {op: replace, path: /refundRequestedState, val: true}
                        - $appendChange: {op: replace, path: /refundRequestIdState, val: restaurant-discount-001}
                        - $appendChange: {op: replace, path: /refundAmountMinorState, val: 3800}
                        - $appendChange: {op: replace, path: /refundReasonState, val: Restaurant 10% service discount}
                        - $appendChange: {op: replace, path: /status, val: Restaurant Discount Refund Requested}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Refund Requested
                            requestId: restaurant-discount-001
                            requestedOperation: refundPayment
                            requestedOperationScopedKey: /payNotes/packagePayment::refundPayment
                            recipientActorId: myos-admin
                            amount: {amountMinor: 3800, currency: PLN}
                            reason: Restaurant 10% service discount
                  - $return: true
          capturePayment:
            name: Confirm Payment Guarantee
            description: Acme Bank confirms the Hotel and Restaurant payment guarantee.
            type: Coordination/Sequential Workflow Operation
            channel: guarantorChannel
            request:
              requestId: {type: Text}
              amountMinor: {type: Integer}
              currency: {type: Text}
            steps:
              - name: Capture Package Payment
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /captureRequestedState, true]
                          - $eq: [$document: /captureCompletedState, false]
                          - $eq: [$document: /hotelConfirmedState, true]
                          - $eq: [$document: /restaurantConfirmedState, true]
                          - $eq: [$binding: event/message/request/requestId, package-capture-001]
                          - $eq: [$binding: event/message/request/amountMinor, 130000]
                          - $eq: [$binding: event/message/request/currency, PLN]
                      then:
                        - $appendChange:
                            op: replace
                            path: /capture
                            val:
                              requested: true
                              requestCount: 1
                              requestId: package-capture-001
                              requestedAt: {$document: /captureRequestedAtState}
                              completed: true
                              completedAt: {$binding: event/timestamp}
                              capturedBy: Acme Bank
                        - $appendChange: {op: replace, path: /captureCompletedState, val: true}
                        - $appendChange:
                            op: replace
                            path: /amount
                            val: {expectedTotal: 130000, expected: 130000, captured: 130000, currency: PLN}
                        - $appendChange: {op: replace, path: /capturedAmountMinorState, val: 130000}
                        - $appendChange: {op: replace, path: /status, val: Completed}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Payment Completed
                            requestId: package-capture-001
                            actorId: myos-admin
                            amount: {amountMinor: 130000, currency: PLN}
                  - $return: true
          refundPayment:
            name: Confirm Partial Refund
            description: Acme Bank returns the requested Restaurant adjustment to Alice.
            type: Coordination/Sequential Workflow Operation
            channel: guarantorChannel
            request:
              requestId: {type: Text}
              amountMinor: {type: Integer}
              currency: {type: Text}
            steps:
              - name: Refund Restaurant Adjustment
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /refundRequestedState, true]
                          - $eq: [$document: /refundCompletedState, false]
                          - $eq: [$binding: event/message/request/requestId, $document: /refundRequestIdState]
                          - $eq: [$binding: event/message/request/amountMinor, $document: /refundAmountMinorState]
                          - $eq: [$binding: event/message/request/currency, PLN]
                      then:
                        - $appendChange:
                            op: replace
                            path: /refund
                            val:
                              requested: true
                              requestId: {$document: /refundRequestIdState}
                              amountMinor: {$document: /refundAmountMinorState}
                              reason: {$document: /refundReasonState}
                              completed: true
                              completedAt: {$binding: event/timestamp}
                        - $appendChange: {op: replace, path: /refundCompletedState, val: true}
                        - $appendChange:
                            op: replace
                            path: /amount
                            val:
                              expectedTotal: 130000
                              expected: 130000
                              captured: {$subtract: [$document: /capturedAmountMinorState, $document: /refundAmountMinorState]}
                              currency: PLN
                        - $appendChange:
                            op: replace
                            path: /capturedAmountMinorState
                            val: {$subtract: [$document: /capturedAmountMinorState, $document: /refundAmountMinorState]}
                        - $appendChange: {op: replace, path: /status, val: Partial Refund Completed}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Refund Completed
                            requestId: {$binding: event/message/request/requestId}
                            amount:
                              amountMinor: {$binding: event/message/request/amountMinor}
                              currency: PLN
                  - $return: true
        """;

    /** Authored package-order document. */
    public static final String PACKAGE_ORDER = """
        name: Wadowice Hotel & Dinner Order
        scenarioId: wadowice-order-2026-v1
        commerceType: Commerce/Order
        sourceOffer:
          id: wadowice-complete-package-offer-v1
        sourceOfferName: Wadowice Hotel & Dinner Offer
        customer:
          actorId: alice
          name: Alice
        merchant:
          actorId: bob
          name: Travel Agency
        amount:
          amountMinor: 130000
          currency: PLN
        confirmationCode: WAD-7429
        orderState: Order Created
        paymentState: Not Attached
        paymentInitiatedAt:
        payNoteAttached: false
        productsCreated: false
        productOrdersAttached: false
        product:
          name: Wadowice Hotel & Dinner Package
          commerceType: Commerce/Bundle Product
          status: In Progress
          products:
            hotel:
              name: Hotel Mlyn Jacka Stay
              commerceType: Commerce/Bookable Product
              productKey: hotel
              productIdentity: wadowice-order-2026-v1:hotel:v1
              sourceOrderId: wadowice-order-2026-v1
              sourcePath: /product/products/hotel
              provider: Wadowice Hotel
              providerActorId: celine
              amount: {amountMinor: 92000, currency: PLN}
              confirmationCode: WAD-7429
              selectedTerms: One night, breakfast included
              status: Pending
              confirmed: false
              done: false
              cancelled: false
              confirmedAt:
              doneAt:
              cancelledAt:
              confirmationReference:
              fulfillmentCodeVerified: false
              contracts:
                providerChannel:
                  description: Wadowice Hotel Product Timeline
                  type: Coordination/Timeline Channel
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/order/celine
                  actor:
                    type: MyOS/Principal Actor
                    accountId: celine
                customerChannel:
                  description: Alice Hotel Product Timeline
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/order/alice
                  actor:
                    type: MyOS/Principal Actor
                    accountId: alice
                  type: Coordination/Timeline Channel
                merchantChannel:
                  description: Travel Agency Hotel coordination Timeline
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/order/bob
                  actor:
                    type: MyOS/Principal Actor
                    accountId: bob
                  type: Coordination/Timeline Channel
                confirmProduct:
                  name: Accept Wadowice Hotel Booking
                  description: Wadowice Hotel accepts the exact stay and price before payment is guaranteed.
                  type: Coordination/Sequential Workflow Operation
                  channel: providerChannel
                  request:
                    confirmationReference: {type: Text}
                  steps:
                    - name: Confirm Hotel Product
                      type: Coordination/Compute
                      do:
                        - $appendChange: {op: replace, path: /confirmed, val: true}
                        - $appendChange: {op: replace, path: /status, val: Confirmed}
                        - $appendChange:
                            op: replace
                            path: /confirmedAt
                            val: {$binding: event/timestamp}
                        - $appendChange:
                            op: replace
                            path: /confirmationReference
                            val: {$binding: event/message/request/confirmationReference}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/Product Confirmed
                            productKey: hotel
                            productName: Hotel Mlyn Jacka Stay
                            sourcePath: /product/products/hotel
                            sourceActorId: celine
                            sourceTimestamp: {$binding: event/timestamp}
                            amountMinor: 92000
                            currency: PLN
                        - $return: true
                completeProduct:
                  name: Confirm Stay with Customer Code
                  description: Wadowice Hotel verifies the customer code and confirms fulfilment.
                  type: Coordination/Sequential Workflow Operation
                  channel: providerChannel
                  request:
                    confirmationCode: {type: Text}
                    note: {type: Text}
                  steps:
                    - name: Complete Hotel Product
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond: {$eq: [$binding: event/message/request/confirmationCode, WAD-7429]}
                            then:
                              - $appendChange: {op: replace, path: /done, val: true}
                              - $appendChange: {op: replace, path: /status, val: Stay Confirmed}
                              - $appendChange:
                                  op: replace
                                  path: /doneAt
                                  val: {$binding: event/timestamp}
                              - $appendChange: {op: replace, path: /fulfillmentCodeVerified, val: true}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Commerce/Product Done
                                  productKey: hotel
                                  productName: Hotel Mlyn Jacka Stay
                                  sourcePath: /product/products/hotel
                                  sourceActorId: celine
                                  sourceTimestamp: {$binding: event/timestamp}
                                  note: {$binding: event/message/request/note}
                        - $return: true
            restaurant:
              name: Old Town Restaurant Dinner
              commerceType: Commerce/Bookable Product
              productKey: restaurant
              productIdentity: wadowice-order-2026-v1:restaurant:v1
              sourceOrderId: wadowice-order-2026-v1
              sourcePath: /product/products/restaurant
              provider: Old Town Restaurant
              providerActorId: david
              amount: {amountMinor: 38000, currency: PLN}
              confirmationCode: WAD-7429
              selectedTerms: Dinner for two at 19:30
              status: Pending
              confirmed: false
              done: false
              cancelled: false
              cancellationRequested: false
              confirmedAt:
              doneAt:
              cancelledAt:
              cancellationRequestedAt:
              confirmationReference:
              fulfillmentCodeVerified: false
              discountPercent: 0
              discountAmountMinor: 0
              netAmountMinor: 38000
              contracts:
                providerChannel:
                  description: Old Town Restaurant Product Timeline
                  type: Coordination/Timeline Channel
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/order/david
                  actor:
                    type: MyOS/Principal Actor
                    accountId: david
                customerChannel:
                  description: Alice Restaurant Product Timeline
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/order/alice
                  actor:
                    type: MyOS/Principal Actor
                    accountId: alice
                  type: Coordination/Timeline Channel
                merchantChannel:
                  description: Travel Agency Restaurant coordination Timeline
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: examples/order/bob
                  actor:
                    type: MyOS/Principal Actor
                    accountId: bob
                  type: Coordination/Timeline Channel
                confirmProduct:
                  name: Accept Old Town Restaurant Booking
                  description: Old Town Restaurant accepts the exact dinner and price before payment is guaranteed.
                  type: Coordination/Sequential Workflow Operation
                  channel: providerChannel
                  request:
                    confirmationReference: {type: Text}
                  steps:
                    - name: Confirm Restaurant Product
                      type: Coordination/Compute
                      do:
                        - $appendChange: {op: replace, path: /confirmed, val: true}
                        - $appendChange: {op: replace, path: /status, val: Confirmed}
                        - $appendChange:
                            op: replace
                            path: /confirmedAt
                            val: {$binding: event/timestamp}
                        - $appendChange:
                            op: replace
                            path: /confirmationReference
                            val: {$binding: event/message/request/confirmationReference}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/Product Confirmed
                            productKey: restaurant
                            productName: Old Town Restaurant Dinner
                            sourcePath: /product/products/restaurant
                            sourceActorId: david
                            sourceTimestamp: {$binding: event/timestamp}
                            amountMinor: 38000
                            currency: PLN
                        - $return: true
                completeProduct:
                  name: Confirm Dinner with Customer Code
                  description: Old Town Restaurant verifies the customer code and confirms fulfilment.
                  type: Coordination/Sequential Workflow Operation
                  channel: providerChannel
                  request:
                    confirmationCode: {type: Text}
                    note: {type: Text}
                  steps:
                    - name: Complete Restaurant Product
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond: {$eq: [$binding: event/message/request/confirmationCode, WAD-7429]}
                            then:
                              - $appendChange: {op: replace, path: /done, val: true}
                              - $appendChange: {op: replace, path: /status, val: Dinner Confirmed}
                              - $appendChange:
                                  op: replace
                                  path: /doneAt
                                  val: {$binding: event/timestamp}
                              - $appendChange: {op: replace, path: /fulfillmentCodeVerified, val: true}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Commerce/Product Done
                                  productKey: restaurant
                                  productName: Old Town Restaurant Dinner
                                  sourcePath: /product/products/restaurant
                                  sourceActorId: david
                                  sourceTimestamp: {$binding: event/timestamp}
                                  note: {$binding: event/message/request/note}
                        - $return: true
                completeWithDiscount:
                  name: Confirm Dinner with 10% Discount
                  description: Old Town Restaurant confirms fulfilment and applies a 10% service adjustment.
                  type: Coordination/Sequential Workflow Operation
                  channel: providerChannel
                  request:
                    confirmationCode: {type: Text}
                    note: {type: Text}
                  steps:
                    - name: Complete Restaurant with Discount
                      type: Coordination/Compute
                      do:
                        - $if:
                            cond: {$eq: [$binding: event/message/request/confirmationCode, WAD-7429]}
                            then:
                              - $appendChange: {op: replace, path: /done, val: true}
                              - $appendChange: {op: replace, path: /status, val: Dinner Confirmed - 10% Discount}
                              - $appendChange:
                                  op: replace
                                  path: /doneAt
                                  val: {$binding: event/timestamp}
                              - $appendChange: {op: replace, path: /fulfillmentCodeVerified, val: true}
                              - $appendChange: {op: replace, path: /discountPercent, val: 10}
                              - $appendChange: {op: replace, path: /discountAmountMinor, val: 3800}
                              - $appendChange: {op: replace, path: /netAmountMinor, val: 34200}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Commerce/Product Done
                                  productKey: restaurant
                                  productName: Old Town Restaurant Dinner
                                  sourcePath: /product/products/restaurant
                                  sourceActorId: david
                                  sourceTimestamp: {$binding: event/timestamp}
                                  note: {$binding: event/message/request/note}
                              - $appendEvent:
                                  type: Coordination/Event
                                  kind: Commerce/Product Discount Applied
                                  productKey: restaurant
                                  sourcePath: /product/products/restaurant
                                  sourceActorId: david
                                  sourceTimestamp: {$binding: event/timestamp}
                                  discountPercent: 10
                                  amountMinor: 3800
                        - $return: true
                cancelWithinRange:
                  name: Cancel Within Refund Window
                  description: Alice cancels in range and requests the Restaurant amount back from the guarantor.
                  type: Coordination/Sequential Workflow Operation
                  channel: customerChannel
                  request:
                    reason: {type: Text}
                  steps:
                    - name: Cancel Restaurant inside Refund Window
                      type: Coordination/Compute
                      do:
                        - $appendChange: {op: replace, path: /cancelled, val: true}
                        - $appendChange: {op: replace, path: /cancellationRequested, val: true}
                        - $appendChange: {op: replace, path: /status, val: Cancelled - Refund Requested}
                        - $appendChange:
                            op: replace
                            path: /cancelledAt
                            val: {$binding: event/timestamp}
                        - $appendChange:
                            op: replace
                            path: /cancellationRequestedAt
                            val: {$binding: event/timestamp}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/Product Cancelled
                            productKey: restaurant
                            sourcePath: /product/products/restaurant
                            sourceActorId: alice
                            sourceTimestamp: {$binding: event/timestamp}
                            reason: {$binding: event/message/request/reason}
                            refundable: true
                            amountMinor: 38000
                        - $return: true
                cancelOutsideRange:
                  name: Cancel Too Late or Record No-show
                  description: The requested change is outside the allowed range, so no Order or payment state changes.
                  type: Coordination/Sequential Workflow Operation
                  channel: customerChannel
                  request:
                    reason: {type: Text}
                  steps:
                    - name: Decline Late Restaurant Change
                      type: Coordination/Compute
                      do:
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/Change Declined
                            productKey: restaurant
                            sourcePath: /product/products/restaurant
                            sourceActorId: alice
                            sourceTimestamp: {$binding: event/timestamp}
                            reason: {$binding: event/message/request/reason}
                            stateChanged: false
                        - $return: true
          productStates:
            hotel: {confirmed: false, done: false, lastOutcome: null}
            restaurant: {confirmed: false, done: false, cancelled: false, discountApplied: false, lastOutcome: null}
          contracts:
            embedded:
              description: Process both provider Products as independent child scopes.
              type: Process Embedded
              paths: [/products/hotel, /products/restaurant]
            hotelEvents:
              description: Bridge Hotel Product events to the package.
              type: Embedded Node Channel
              childPath: /products/hotel
            restaurantEvents:
              description: Bridge Restaurant Product events to the package.
              type: Embedded Node Channel
              childPath: /products/restaurant
            observeHotelConfirmed:
              type: Coordination/Sequential Workflow
              channel: hotelEvents
              event: {type: Coordination/Event, kind: Commerce/Product Confirmed}
              steps:
                - name: Report Hotel Confirmation
                  type: Coordination/Compute
                  do:
                    - $if:
                        cond: {$eq: [$document: /productStates/hotel/confirmed, false]}
                        then:
                          - $appendChange:
                              op: replace
                              path: /productStates/hotel
                              val:
                                $merge:
                                  - $document: /productStates/hotel
                                  - confirmed: true
                                    lastOutcome: Commerce/Product Confirmed
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Commerce/Outcome Reported
                              outcomeKind: Commerce/Product Confirmed
                              productKey: hotel
                              sourcePath: /product/products/hotel
                              sourceTimestamp: {$binding: event/sourceTimestamp}
                          - $appendEvent:
                              $merge:
                                - $binding: event
                                - sourceScopePath: /product/products/hotel
                    - $return: true
            observeRestaurantConfirmed:
              type: Coordination/Sequential Workflow
              channel: restaurantEvents
              event: {type: Coordination/Event, kind: Commerce/Product Confirmed}
              steps:
                - name: Report Restaurant Confirmation
                  type: Coordination/Compute
                  do:
                    - $if:
                        cond: {$eq: [$document: /productStates/restaurant/confirmed, false]}
                        then:
                          - $appendChange:
                              op: replace
                              path: /productStates/restaurant
                              val:
                                $merge:
                                  - $document: /productStates/restaurant
                                  - confirmed: true
                                    lastOutcome: Commerce/Product Confirmed
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Commerce/Outcome Reported
                              outcomeKind: Commerce/Product Confirmed
                              productKey: restaurant
                              sourcePath: /product/products/restaurant
                              sourceTimestamp: {$binding: event/sourceTimestamp}
                          - $appendEvent:
                              $merge:
                                - $binding: event
                                - sourceScopePath: /product/products/restaurant
                    - $return: true
            observeHotelDone:
              type: Coordination/Sequential Workflow
              channel: hotelEvents
              event: {type: Coordination/Event, kind: Commerce/Product Done}
              steps:
                - name: Report Hotel Completion
                  type: Coordination/Compute
                  do:
                    - $if:
                        cond: {$eq: [$document: /productStates/hotel/done, false]}
                        then:
                          - $appendChange:
                              op: replace
                              path: /productStates/hotel
                              val:
                                $merge:
                                  - $document: /productStates/hotel
                                  - done: true
                                    lastOutcome: Commerce/Product Done
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Commerce/Outcome Reported
                              outcomeKind: Commerce/Product Done
                              productKey: hotel
                              sourcePath: /product/products/hotel
                              sourceTimestamp: {$binding: event/sourceTimestamp}
                          - $appendEvent:
                              $merge:
                                - $binding: event
                                - sourceScopePath: /product/products/hotel
                    - $return: true
            observeRestaurantDone:
              type: Coordination/Sequential Workflow
              channel: restaurantEvents
              event: {type: Coordination/Event, kind: Commerce/Product Done}
              steps:
                - name: Report Restaurant Completion
                  type: Coordination/Compute
                  do:
                    - $if:
                        cond: {$eq: [$document: /productStates/restaurant/done, false]}
                        then:
                          - $appendChange:
                              op: replace
                              path: /productStates/restaurant
                              val:
                                $merge:
                                  - $document: /productStates/restaurant
                                  - done: true
                                    lastOutcome: Commerce/Product Done
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Commerce/Outcome Reported
                              outcomeKind: Commerce/Product Done
                              productKey: restaurant
                              sourcePath: /product/products/restaurant
                              sourceTimestamp: {$binding: event/sourceTimestamp}
                          - $appendEvent:
                              $merge:
                                - $binding: event
                                - sourceScopePath: /product/products/restaurant
                    - $return: true
            observeRestaurantCancelled:
              type: Coordination/Sequential Workflow
              channel: restaurantEvents
              event: {type: Coordination/Event, kind: Commerce/Product Cancelled}
              steps:
                - name: Report Restaurant Cancellation
                  type: Coordination/Compute
                  do:
                    - $appendChange:
                        op: replace
                        path: /productStates/restaurant
                        val:
                          $merge:
                            - $document: /productStates/restaurant
                            - cancelled: true
                              lastOutcome: Commerce/Product Cancelled
                    - $appendEvent:
                        type: Coordination/Event
                        kind: Commerce/Outcome Reported
                        outcomeKind: Commerce/Product Cancelled
                        productKey: restaurant
                        sourcePath: /product/products/restaurant
                        sourceTimestamp: {$binding: event/sourceTimestamp}
                        reason: {$binding: event/reason}
                        amountMinor: {$binding: event/amountMinor}
                    - $appendEvent:
                        $merge:
                          - $binding: event
                          - sourceScopePath: /product/products/restaurant
                    - $return: true
            observeRestaurantDiscount:
              type: Coordination/Sequential Workflow
              channel: restaurantEvents
              event: {type: Coordination/Event, kind: Commerce/Product Discount Applied}
              steps:
                - name: Report Restaurant Discount
                  type: Coordination/Compute
                  do:
                    - $appendChange:
                        op: replace
                        path: /productStates/restaurant
                        val:
                          $merge:
                            - $document: /productStates/restaurant
                            - discountApplied: true
                              lastOutcome: Commerce/Product Discount Applied
                    - $appendEvent:
                        type: Coordination/Event
                        kind: Commerce/Outcome Reported
                        outcomeKind: Commerce/Product Discount Applied
                        productKey: restaurant
                        sourcePath: /product/products/restaurant
                        sourceTimestamp: {$binding: event/sourceTimestamp}
                        amountMinor: {$binding: event/amountMinor}
                        discountPercent: {$binding: event/discountPercent}
                    - $appendEvent:
                        $merge:
                          - $binding: event
                          - sourceScopePath: /product/products/restaurant
                    - $return: true
            publishRestaurantChangeDeclined:
              type: Coordination/Sequential Workflow
              channel: restaurantEvents
              event: {type: Coordination/Event, kind: Commerce/Change Declined}
              steps:
                - name: Publish Declined Restaurant Change
                  type: Coordination/Compute
                  do:
                    - $appendEvent:
                        $merge:
                          - $binding: event
                          - sourceScopePath: /product/products/restaurant
                    - $return: true
        payNotes: {}
        outcomeJournal:
          hotel: {confirmed: false, done: false, lastOutcome: null}
          restaurant: {confirmed: false, done: false, cancelled: false, discountApplied: false, lastOutcome: null}
        publicEventJournal:
          productConfirmedAt:
          productDoneAt:
          productCancelledAt:
          productDiscountAppliedAt:
          changeDeclinedAt:
        contracts:
          customerChannel:
            description: Alice Order Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/order/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          merchantChannel:
            description: Travel Agency Order Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/order/bob
            actor:
              type: MyOS/Principal Actor
              accountId: bob
          embedded:
            description: Product is active initially; PayNote is activated by its attachment workflow.
            type: Process Embedded
            paths: [/product]
          bundleEvents:
            type: Embedded Node Channel
            childPath: /product
          payNoteEvents:
            type: Embedded Node Channel
            childPath: /payNotes/packagePayment
          attachPayNoteAsCustomer:
            name: Attach PayNote to Order
            description: Alice supplies the compact, complete pre-initialization ACME PayNote plus a content-addressed identity
              witness; both remain in the Timeline Entry and the Order embeds the complete P0 document.
            type: Coordination/Sequential Workflow Operation
            channel: customerChannel
            request:
              document:
                description: Complete pre-initialization PayNote document supplied by the customer.
              documentRef:
                description: Pure blueId reference whose identity must equal the submitted document.
            steps:
              - name: Validate and Attach ACME PayNote
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $or:
                          - $ne: [$binding: event/message/request/document/name, ACME Hotel & Dinner PayNote]
                          - $ne: [$binding: event/message/request/document/status, Awaiting Product Conditions]
                          - $ne: [$binding: event/message/request/document/attachedBy, Alice]
                          - $ne:
                              - $binding: event/message/request/document/validationMethod
                              - "Order policy: exact amount, PLN, ACME guarantor"
                          - $ne: [$binding: event/message/request/document/payer/actorId, alice]
                          - $ne: [$binding: event/message/request/document/payer/name, Alice]
                          - $ne: [$binding: event/message/request/document/payee/actorId, bob]
                          - $ne: [$binding: event/message/request/document/payee/name, Travel Agency]
                          - $ne: [$binding: event/message/request/document/guarantor/actorId, myos-admin]
                          - $ne: [$binding: event/message/request/document/guarantor/name, Acme Bank]
                          - $ne: [$binding: event/message/request/document/currency, PLN]
                          - $ne: [$binding: event/message/request/document/amount/expectedTotal, 130000]
                          - $ne: [$binding: event/message/request/document/amount/expected, 130000]
                          - $ne: [$binding: event/message/request/document/amount/captured, 0]
                          - $ne: [$binding: event/message/request/document/amount/currency, PLN]
                          - $ne: [$binding: event/message/request/document/authorization/state, Not Authorized]
                          - $ne: [$binding: event/message/request/document/authorization/authorizedAmountMinor, 0]
                          - $ne: [$binding: event/message/request/document/authorization/currency, PLN]
                          - $ne: [$binding: event/message/request/document/authorization/authorizationCount, 0]
                          - $ne: [$binding: event/message/request/document/attachedConditions/hotel, false]
                          - $ne: [$binding: event/message/request/document/attachedConditions/restaurant, false]
                          - $ne: [$binding: event/message/request/document/capture/requested, false]
                          - $ne: [$binding: event/message/request/document/capture/requestCount, 0]
                          - $ne: [$binding: event/message/request/document/capture/completed, false]
                          - $ne: [$binding: event/message/request/document/refund/requested, false]
                          - $ne: [$binding: event/message/request/document/refund/amountMinor, 0]
                          - $ne: [$binding: event/message/request/document/refund/completed, false]
                          - $ne: [$binding: event/message/request/document/contracts/payerChannel/actor/accountId, alice]
                          - $ne: [$binding: event/message/request/document/contracts/payeeChannel/actor/accountId, bob]
                          - $ne:
                              - $binding: event/message/request/document/contracts/guarantorChannel/actor/accountId
                              - myos-admin
                          - $exists: {$binding: event/message/request/document/contracts/initialized}
                          - $exists: {$binding: event/message/request/document/contracts/checkpoint}
                      then:
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Validation Error
                            message: Order policy requires the complete, exact, pre-initialization ACME PayNote document.
                            validationMethod: initial PayNote document policy
                  - $if:
                      cond:
                        $and:
                          - $eq: [$binding: event/message/request/document/name, ACME Hotel & Dinner PayNote]
                          - $eq: [$binding: event/message/request/document/status, Awaiting Product Conditions]
                          - $eq: [$binding: event/message/request/document/attachedBy, Alice]
                          - $eq:
                              - $binding: event/message/request/document/validationMethod
                              - "Order policy: exact amount, PLN, ACME guarantor"
                          - $eq: [$binding: event/message/request/document/payer/actorId, alice]
                          - $eq: [$binding: event/message/request/document/payee/actorId, bob]
                          - $eq: [$binding: event/message/request/document/guarantor/actorId, myos-admin]
                          - $eq: [$binding: event/message/request/document/currency, PLN]
                          - $eq: [$binding: event/message/request/document/amount/expectedTotal, 130000]
                          - $eq: [$binding: event/message/request/document/amount/expected, 130000]
                          - $eq: [$binding: event/message/request/document/amount/captured, 0]
                          - $eq: [$binding: event/message/request/document/amount/currency, PLN]
                          - $eq: [$binding: event/message/request/document/authorization/state, Not Authorized]
                          - $eq: [$binding: event/message/request/document/authorization/authorizedAmountMinor, 0]
                          - $eq: [$binding: event/message/request/document/authorization/currency, PLN]
                          - $eq: [$binding: event/message/request/document/authorization/authorizationCount, 0]
                          - $eq: [$binding: event/message/request/document/attachedConditions/hotel, false]
                          - $eq: [$binding: event/message/request/document/attachedConditions/restaurant, false]
                          - $eq: [$binding: event/message/request/document/capture/requested, false]
                          - $eq: [$binding: event/message/request/document/capture/requestCount, 0]
                          - $eq: [$binding: event/message/request/document/capture/completed, false]
                          - $eq: [$binding: event/message/request/document/refund/requested, false]
                          - $eq: [$binding: event/message/request/document/refund/amountMinor, 0]
                          - $eq: [$binding: event/message/request/document/refund/completed, false]
                          - $eq: [$binding: event/message/request/document/contracts/payerChannel/actor/accountId, alice]
                          - $eq: [$binding: event/message/request/document/contracts/payeeChannel/actor/accountId, bob]
                          - $eq:
                              - $binding: event/message/request/document/contracts/guarantorChannel/actor/accountId
                              - myos-admin
                          - $not:
                              $exists: {$binding: event/message/request/document/contracts/initialized}
                          - $not:
                              $exists: {$binding: event/message/request/document/contracts/checkpoint}
                          - $eq: [$document: /payNoteAttached, false]
                      then:
                        - $appendChange:
                            op: add
                            path: /payNotes/packagePayment
                            val: {$binding: event/message/request/document}
                        - $appendChange:
                            op: add
                            path: /payNotes/packagePayment/contracts/embedded
                            val:
                              description: Product listeners active only inside the attached Order PayNote.
                              type: Process Embedded
                              paths:
                                - /productConditions/hotel/product
                                - /productConditions/restaurant/product
                        - $appendChange:
                            op: add
                            path: /contracts/embedded/paths/-
                            val: /payNotes/packagePayment
                        - $appendChange: {op: replace, path: /payNoteAttached, val: true}
                        - $appendChange: {op: replace, path: /paymentState, val: Payment Initiated - Conditions Pending}
                        - $appendChange:
                            op: replace
                            path: /paymentInitiatedAt
                            val: {$binding: event/timestamp}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/PayNote Attached
                            attachedBy: Alice
                            payNotePath: /payNotes/packagePayment
                            sourceActorId: alice
                            sourceTimestamp: {$binding: event/timestamp}
                  - $return: true
          createServiceOrders:
            name: Create Hotel and Restaurant Orders
            description: The Travel Agency creates the two service orders selected by Alice.
            type: Coordination/Sequential Workflow Operation
            channel: merchantChannel
            request: {}
            steps:
              - name: Create Service Orders
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$eq: [$document: /productsCreated, false]}
                      then:
                        - $appendChange: {op: replace, path: /productsCreated, val: true}
                        - $appendChange: {op: replace, path: /orderState, val: Service Orders Created}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/Service Orders Created
                            productKeys: [hotel, restaurant]
                  - $return: true
          attachServiceOrders:
            name: Link Service Orders to Order
            description: The Travel Agency links Hotel and Restaurant after the PayNote is attached.
            type: Coordination/Sequential Workflow Operation
            channel: merchantChannel
            request: {}
            steps:
              - name: Link Service Orders
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$document: /productsCreated, true]
                          - $eq: [$document: /payNoteAttached, true]
                          - $eq: [$document: /productOrdersAttached, false]
                      then:
                        - $appendChange: {op: replace, path: /productOrdersAttached, val: true}
                        - $appendChange: {op: replace, path: /orderState, val: Awaiting Provider Confirmation}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: Commerce/Service Orders Linked
                            orderPath: /
                            productPaths: [/product/products/hotel, /product/products/restaurant]
                  - $return: true
          observeHotelOutcome:
            type: Coordination/Sequential Workflow
            channel: bundleEvents
            event: {type: Coordination/Event, kind: Commerce/Outcome Reported, productKey: hotel}
            steps:
              - name: Journal Hotel Outcome
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$eq: [$binding: event/outcomeKind, Commerce/Product Confirmed]}
                      then:
                        - $appendChange: {op: replace, path: /outcomeJournal/hotel/confirmed, val: true}
                  - $if:
                      cond: {$eq: [$binding: event/outcomeKind, Commerce/Product Done]}
                      then:
                        - $appendChange: {op: replace, path: /outcomeJournal/hotel/done, val: true}
                  - $appendChange:
                      op: replace
                      path: /outcomeJournal/hotel/lastOutcome
                      val: {$binding: event/outcomeKind}
                  - $return: true
              - name: Confirm Entire Order after Hotel Outcome
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$binding: event/outcomeKind, Commerce/Product Done]
                          - $eq: [$document: /outcomeJournal/hotel/done, true]
                          - $eq: [$document: /outcomeJournal/restaurant/done, true]
                          - $eq: [$document: /outcomeJournal/restaurant/cancelled, false]
                      then:
                        - $appendChange: {op: replace, path: /orderState, val: Confirmed}
                  - $return: true
          observeRestaurantOutcome:
            type: Coordination/Sequential Workflow
            channel: bundleEvents
            event: {type: Coordination/Event, kind: Commerce/Outcome Reported, productKey: restaurant}
            steps:
              - name: Journal Restaurant Outcome
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$eq: [$binding: event/outcomeKind, Commerce/Product Confirmed]}
                      then:
                        - $appendChange: {op: replace, path: /outcomeJournal/restaurant/confirmed, val: true}
                  - $if:
                      cond: {$eq: [$binding: event/outcomeKind, Commerce/Product Done]}
                      then:
                        - $appendChange: {op: replace, path: /outcomeJournal/restaurant/done, val: true}
                  - $if:
                      cond: {$eq: [$binding: event/outcomeKind, Commerce/Product Cancelled]}
                      then:
                        - $appendChange: {op: replace, path: /outcomeJournal/restaurant/cancelled, val: true}
                        - $appendChange: {op: replace, path: /orderState, val: Restaurant Cancelled - Refund Pending}
                  - $if:
                      cond: {$eq: [$binding: event/outcomeKind, Commerce/Product Discount Applied]}
                      then:
                        - $appendChange: {op: replace, path: /outcomeJournal/restaurant/discountApplied, val: true}
                  - $appendChange:
                      op: replace
                      path: /outcomeJournal/restaurant/lastOutcome
                      val: {$binding: event/outcomeKind}
                  - $return: true
              - name: Confirm Entire Order after Restaurant Outcome
                type: Coordination/Compute
                do:
                  - $if:
                      cond:
                        $and:
                          - $eq: [$binding: event/outcomeKind, Commerce/Product Done]
                          - $eq: [$document: /outcomeJournal/hotel/done, true]
                          - $eq: [$document: /outcomeJournal/restaurant/done, true]
                          - $eq: [$document: /outcomeJournal/restaurant/cancelled, false]
                      then:
                        - $appendChange: {op: replace, path: /orderState, val: Confirmed}
                  - $return: true
          publishProductConfirmedAudit:
            type: Coordination/Sequential Workflow
            channel: bundleEvents
            event: {type: Coordination/Event, kind: Commerce/Product Confirmed}
            steps:
              - name: Publish Confirmed Product Once
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$ne: [$document: /publicEventJournal/productConfirmedAt, $binding: event/sourceTimestamp]}
                      then:
                        - $appendChange:
                            op: replace
                            path: /publicEventJournal/productConfirmedAt
                            val: {$binding: event/sourceTimestamp}
                        - $appendEvent:
                            $merge:
                              - $binding: event
                              - sourceScopePath: {$binding: event/sourcePath}
                  - $return: true
          publishProductDoneAudit:
            type: Coordination/Sequential Workflow
            channel: bundleEvents
            event: {type: Coordination/Event, kind: Commerce/Product Done}
            steps:
              - name: Publish Completed Product Once
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$ne: [$document: /publicEventJournal/productDoneAt, $binding: event/sourceTimestamp]}
                      then:
                        - $appendChange:
                            op: replace
                            path: /publicEventJournal/productDoneAt
                            val: {$binding: event/sourceTimestamp}
                        - $appendEvent:
                            $merge:
                              - $binding: event
                              - sourceScopePath: {$binding: event/sourcePath}
                  - $return: true
          publishProductCancellationAudit:
            type: Coordination/Sequential Workflow
            channel: bundleEvents
            event: {type: Coordination/Event, kind: Commerce/Product Cancelled}
            steps:
              - name: Publish Cancelled Product Once
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$ne: [$document: /publicEventJournal/productCancelledAt, $binding: event/sourceTimestamp]}
                      then:
                        - $appendChange:
                            op: replace
                            path: /publicEventJournal/productCancelledAt
                            val: {$binding: event/sourceTimestamp}
                        - $appendEvent:
                            $merge:
                              - $binding: event
                              - sourceScopePath: {$binding: event/sourcePath}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Refund Requested
                            requestId: restaurant-refund-001
                            requestedOperation: refundPayment
                            requestedOperationScopedKey: /payNotes/packagePayment::refundPayment
                            recipientActorId: myos-admin
                            amount: {amountMinor: 38000, currency: PLN}
                            reason: Restaurant cancelled within refund window
                  - $return: true
          publishProductDiscountAudit:
            type: Coordination/Sequential Workflow
            channel: bundleEvents
            event: {type: Coordination/Event, kind: Commerce/Product Discount Applied}
            steps:
              - name: Publish Discounted Product Once
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$ne: [$document: /publicEventJournal/productDiscountAppliedAt, $binding: event/sourceTimestamp]}
                      then:
                        - $appendChange:
                            op: replace
                            path: /publicEventJournal/productDiscountAppliedAt
                            val: {$binding: event/sourceTimestamp}
                        - $appendEvent:
                            $merge:
                              - $binding: event
                              - sourceScopePath: {$binding: event/sourcePath}
                        - $appendEvent:
                            type: Coordination/Event
                            kind: PayNote/Refund Requested
                            requestId: restaurant-discount-001
                            requestedOperation: refundPayment
                            requestedOperationScopedKey: /payNotes/packagePayment::refundPayment
                            recipientActorId: myos-admin
                            amount: {amountMinor: 3800, currency: PLN}
                            reason: Restaurant 10% service discount
                  - $return: true
          publishChangeDeclinedAudit:
            type: Coordination/Sequential Workflow
            channel: bundleEvents
            event: {type: Coordination/Event, kind: Commerce/Change Declined}
            steps:
              - name: Publish Declined Change Once
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$ne: [$document: /publicEventJournal/changeDeclinedAt, $binding: event/sourceTimestamp]}
                      then:
                        - $appendChange:
                            op: replace
                            path: /publicEventJournal/changeDeclinedAt
                            val: {$binding: event/sourceTimestamp}
                        - $appendEvent:
                            $merge:
                              - $binding: event
                              - sourceScopePath: {$binding: event/sourcePath}
                  - $return: true
          observeCaptureRequest:
            type: Coordination/Sequential Workflow
            channel: payNoteEvents
            event: {type: Coordination/Event, kind: PayNote/Capture Funds Requested}
            steps:
              - name: Record Capture Request on Order
                type: Coordination/Compute
                do:
                  - $appendChange: {op: replace, path: /paymentState, val: Capture Requested}
                  - $appendChange: {op: replace, path: /orderState, val: Awaiting ACME Capture}
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /payNotes/packagePayment
                  - $return: true
          observePaymentCompleted:
            type: Coordination/Sequential Workflow
            channel: payNoteEvents
            event: {type: Coordination/Event, kind: PayNote/Payment Completed}
            steps:
              - name: Record Completed Payment on Order
                type: Coordination/Compute
                do:
                  - $appendChange: {op: replace, path: /paymentState, val: Completed}
                  - $appendChange: {op: replace, path: /orderState, val: Ready to Use}
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /payNotes/packagePayment
                  - $return: true
          observeRefundRequest:
            type: Coordination/Sequential Workflow
            channel: payNoteEvents
            event: {type: Coordination/Event, kind: PayNote/Refund Requested}
            steps:
              - name: Record Refund Request on Order
                type: Coordination/Compute
                do:
                  - $if:
                      cond: {$eq: [$binding: event/requestId, restaurant-refund-001]}
                      then:
                        - $appendChange: {op: replace, path: /orderState, val: Restaurant Cancelled - Refund Pending}
                  - $return: true
          observeRefundCompleted:
            type: Coordination/Sequential Workflow
            channel: payNoteEvents
            event: {type: Coordination/Event, kind: PayNote/Refund Completed}
            steps:
              - name: Record Partial Refund on Order
                type: Coordination/Compute
                do:
                  - $appendChange: {op: replace, path: /paymentState, val: Partially Refunded}
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /payNotes/packagePayment
                  - $return: true
          publishProductConditionAttachedAudit:
            type: Coordination/Sequential Workflow
            channel: payNoteEvents
            event: {type: Coordination/Event, kind: PayNote/Product Condition Attached}
            steps:
              - name: Publish Attached Product Condition Audit
                type: Coordination/Compute
                do:
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /payNotes/packagePayment
                  - $return: true
          publishProductConditionSatisfiedAudit:
            type: Coordination/Sequential Workflow
            channel: payNoteEvents
            event: {type: Coordination/Event, kind: PayNote/Product Condition Satisfied}
            steps:
              - name: Publish Satisfied Product Condition Audit
                type: Coordination/Compute
                do:
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /payNotes/packagePayment
                  - $return: true
          publishProductCompletionObservedAudit:
            type: Coordination/Sequential Workflow
            channel: payNoteEvents
            event: {type: Coordination/Event, kind: PayNote/Product Completion Observed}
            steps:
              - name: Publish Observed Product Completion Audit
                type: Coordination/Compute
                do:
                  - $appendEvent:
                      $merge:
                        - $binding: event
                        - sourceScopePath: /payNotes/packagePayment
                  - $return: true
        """;

}
