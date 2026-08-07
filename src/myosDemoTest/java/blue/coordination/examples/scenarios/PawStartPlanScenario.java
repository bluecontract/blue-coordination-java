package blue.coordination.examples.scenarios;

import blue.coordination.examples.documents.VetExtDocuments;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoAuthority;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;

import java.util.List;

/**
 * Executable PawStart Full Plan scenario from the MyOS demo catalog.
 *
 * <p>The class intentionally expresses business actions, not processor
 * plumbing. Every document and Timeline Entry is authored as Blue YAML text;
 * the runtime performs parsing, preprocessing, mandate eligibility, indexed
 * delivery, fragment loading, PROCESS, and atomic commit.</p>
 */
public final class PawStartPlanScenario implements AutoCloseable {

    public static final String ORDER = "pawstart-plan-order";
    public static final String PAYNOTE = "pawstart-plan-paynote";
    public static final String AGREEMENT = "vet-pupps-agreement";
    public static final String GROOMING_ORDER = "pupps-grooming-order";
    public static final String MANDATE = "scheduling-mandate";

    private final MyOsDemoRuntime demo;
    private final MyOsDemoTimeline customer;
    private final MyOsDemoTimeline customerAgent;
    private final MyOsDemoTimeline vet;
    private final MyOsDemoTimeline trainer;
    private final MyOsDemoTimeline trainerAgent;
    private final MyOsDemoTimeline provider;
    private final MyOsDemoTimeline admin;
    private final MyOsDemoAuthority schedulingAuthority;

    private PawStartPlanScenario() {
        demo = MyOsDemoRuntime.create("pawstart-plan");
        demo.addDocument(ORDER, VetExtDocuments.PAWSTART_PLAN_ORDER);
        demo.addDocument(PAYNOTE, VetExtDocuments.PAWSTART_PLAN_PAYNOTE);
        demo.addDocument(AGREEMENT, VetExtDocuments.VET_PUPPS_AGREEMENT);
        demo.addDocument(GROOMING_ORDER, VetExtDocuments.PUPPS_GROOMING_ORDER);
        demo.addDocument(MANDATE, VetExtDocuments.SCHEDULING_MANDATE);

        customer = demo.timeline(
                "examples/vet-ext/alice",
                MyOsDemoActor.principal("alice"));
        customerAgent = demo.timeline(
                "examples/vet-ext/alice-agent",
                MyOsDemoActor.agent("alice-agent"));
        vet = demo.timeline(
                "examples/vet-ext/bob",
                MyOsDemoActor.principal("bob"));
        trainer = demo.timeline(
                "examples/vet-ext/celine",
                MyOsDemoActor.principal("celine"));
        trainerAgent = demo.timeline(
                "examples/vet-ext/celine-agent",
                MyOsDemoActor.agent("celine-agent"));
        provider = demo.timeline(
                "examples/vet-ext/acme-bank-rep",
                MyOsDemoActor.principal("acme-bank-rep"));
        admin = demo.timeline(
                "examples/vet-ext/myos-admin",
                MyOsDemoActor.admin());
        schedulingAuthority = new MyOsDemoAuthority(
                MyOsDemoActor.principal("alice"),
                demo.document(MANDATE).initialBlueId());
    }

    public static PawStartPlanScenario create() {
        return new PawStartPlanScenario();
    }

    public MyOsDemoRuntime demo() {
        return demo;
    }

    public MyOsDemoResult attachAgreement() {
        return invoke(
                vet,
                ORDER,
                MyOsDemoOperation.operation("attachVetPuppsAgreement")
                        .through("vetChannel")
                        .request("""
                                reason: Activate B2B performance and settlement tracking.
                                """)
                        .build());
    }

    public MyOsDemoResult attachPayNote() {
        return invoke(
                customer,
                ORDER,
                MyOsDemoOperation.operation("attachPawStartPayNote")
                        .through("customerChannel")
                        .request("""
                                reason: Activate financial outcome tracking for puppy training.
                                """)
                        .build());
    }

    public MyOsDemoResult confirmSchedulingAuthority() {
        return invoke(
                admin,
                MANDATE,
                MyOsDemoOperation.operation("confirmMandateAuthority")
                        .through("mandateGuarantorChannel")
                        .build());
    }

    public MyOsDemoResult scheduleTrainingAsAgent() {
        MyOsDemoEntry entry = demo.append(
                customerAgent,
                MyOsDemoOperation.operation("scheduleVisit")
                        .from("customerAgentChannel")
                        .to("customerChannel")
                        .request("""
                                serviceKey: puppyTraining
                                preferredDate: "2026-07-20"
                                preferredTime: "14:00"
                                reason: Schedule the puppy-training visit included in Maya's PawStart Full Plan.
                                requestId: pupps-training-visit-001
                                """)
                        .onBehalfOf(schedulingAuthority)
                        .build());
        return demo.deliverMandateTargetWhenEligible(
                ORDER,
                MANDATE,
                entry);
    }

    public MyOsDemoResult confirmVisit() {
        return invoke(
                trainer,
                ORDER,
                MyOsDemoOperation.operation("confirmVisit")
                        .through("trainerChannel")
                        .request("""
                                date: "2026-07-20"
                                time: "14:00"
                                trainer: Iris
                                notes: Confirmed PUPPS puppy-training visit.
                                inResponseTo: pupps-training-visit-001
                                """)
                        .build());
    }

    /** Runs the shared, non-branching setup through confirmed visit state. */
    public List<MyOsDemoResult> prepareConfirmedVisit() {
        return List.of(
                attachAgreement(),
                attachPayNote(),
                confirmSchedulingAuthority(),
                scheduleTrainingAsAgent(),
                confirmVisit());
    }

    public MyOsDemoResult completeVisitNormally() {
        return invoke(
                customer,
                ORDER,
                MyOsDemoOperation.operation("confirmVisitHappened")
                        .through("customerChannel")
                        .request("""
                                confirmationCode: PAW-2710
                                comment: The puppy-training visit happened as confirmed.
                                """)
                        .build());
    }

    public MyOsDemoResult cancelVisitOnTime() {
        return invoke(
                customer,
                ORDER,
                MyOsDemoOperation.operation("cancelVisitWithinWindow")
                        .through("customerChannel")
                        .request("""
                                reason: Cancel the puppy-training visit within the 24-hour policy window.
                                requestId: pupps-training-refund-001
                                """)
                        .build());
    }

    public MyOsDemoResult recordNoShow() {
        return invoke(
                trainerAgent,
                ORDER,
                MyOsDemoOperation.operation("recordLateCancellationOrNoShow")
                        .through("trainerAgentChannel")
                        .request("""
                                reason: Customer did not attend the confirmed puppy-training visit.
                                outcome: NoShow
                                """)
                        .build());
    }

    public MyOsDemoResult completeWithLowSatisfaction() {
        return invoke(
                customer,
                ORDER,
                MyOsDemoOperation.operation("confirmVisitWithLowSatisfaction")
                        .through("customerChannel")
                        .request("""
                                score: 35
                                comment: The visit happened but did not meet expectations.
                                requestAdjustment: true
                                """)
                        .build());
    }

    public MyOsDemoResult completeCancellationRefund() {
        return invoke(
                provider,
                ORDER,
                MyOsDemoOperation.operation("refundPayment")
                        .through("providerChannel")
                        .request("""
                                requestId: pupps-training-refund-001
                                amountMinor: 27100
                                currency: USD
                                note: On-time puppy-training cancellation refund.
                                """)
                        .build());
    }

    public MyOsDemoResult completeLowSatisfactionAdjustment() {
        return invoke(
                provider,
                ORDER,
                MyOsDemoOperation.operation("refundPayment")
                        .through("providerChannel")
                        .request("""
                                requestId: pupps-training-adjustment-001
                                amountMinor: 2710
                                currency: USD
                                note: 10% puppy-training service adjustment.
                                """)
                        .build());
    }

    public MyOsDemoResult terminateSchedulingAuthority() {
        return invoke(
                admin,
                MANDATE,
                MyOsDemoOperation.operation("terminateMandate")
                        .through("mandateGuarantorChannel")
                        .request("""
                                reason: Customer ended autonomous scheduling access.
                                """)
                        .build());
    }

    private MyOsDemoResult invoke(
            MyOsDemoTimeline timeline,
            String authoritativeDocumentKey,
            MyOsDemoOperation operation) {
        MyOsDemoEntry entry = demo.append(timeline, operation);
        MyOsDemoDispatch dispatch = demo.process(entry);
        for (var delivery : dispatch.deliveriesByDocument().entrySet()) {
            MyOsDemoResult result = delivery.getValue();
            var transition = result.delivery().transition();
            var process = transition.platformResult().processResult();
            if (!process.commits()
                    || !result.delivery().commitOutcome().committed()) {
                throw new IllegalStateException(
                        operation.operation() + " delivery to "
                                + delivery.getKey() + " failed: "
                                + (process.diagnostic() == null
                                ? transition.status().wireValue()
                                : process.diagnostic().category()
                                + " - " + process.diagnostic().message()
                                + " " + process.diagnostic().details()));
            }
        }
        return dispatch.require(authoritativeDocumentKey);
    }

    @Override
    public void close() {
        demo.close();
    }
}
