package blue.coordination.examples.scenarios;

import blue.coordination.examples.documents.MandateOperationDocuments;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoAuthority;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;
import blue.coordination.processor.mandate.MandateEligibilityDecision;

/** Business verbs for the feeder-owned Operation Mandate example. */
public final class OperationMandateScenario implements AutoCloseable {

    public static final String TARGET = "delegated-counter";
    public static final String MANDATE = "increment-mandate";

    private final MyOsDemoRuntime demo;
    private final MyOsDemoTimeline admin;
    private final MyOsDemoTimeline agent;
    private final MyOsDemoAuthority authority;

    private OperationMandateScenario() {
        demo = MyOsDemoRuntime.create("operation-mandate");
        demo.addDocument(TARGET, MandateOperationDocuments.DELEGATED_COUNTER);
        demo.addDocument(MANDATE, MandateOperationDocuments.INCREMENT_MANDATE);
        admin = demo.timeline(
                "examples/mandate-operation/myos-admin",
                MyOsDemoActor.admin());
        agent = demo.timeline(
                "examples/mandate-operation/alice-agent",
                MyOsDemoActor.agent("alice-agent"));
        authority = new MyOsDemoAuthority(
                MyOsDemoActor.principal("alice"),
                demo.document(MANDATE).initialBlueId());
    }

    public static OperationMandateScenario create() {
        return new OperationMandateScenario();
    }

    public MyOsDemoRuntime demo() {
        return demo;
    }

    public MyOsDemoResult confirmAuthority() {
        MyOsDemoEntry entry = demo.append(
                admin,
                MyOsDemoOperation.operation("confirmMandateAuthority")
                        .through("mandateGuarantorChannel")
                        .build());
        return demo.process(entry).onlyResult();
    }

    public RequestedOperation requestIncrement(int amount) {
        MyOsDemoEntry entry = demo.append(
                agent,
                MyOsDemoOperation.operation("increment")
                        .from("agentChannel")
                        .to("holderChannel")
                        .request("""
                                amount: %d
                                """.formatted(amount))
                        .onBehalfOf(authority)
                        .build());
        MandateEligibilityDecision decision = demo.mandateDecision(
                TARGET, MANDATE, entry);
        return new RequestedOperation(entry, decision);
    }

    public MyOsDemoResult deliverEligible(RequestedOperation request) {
        return demo.deliverMandateTargetWhenEligible(
                TARGET, MANDATE, request.entry());
    }

    public MyOsDemoResult terminate() {
        MyOsDemoEntry entry = demo.append(
                admin,
                MyOsDemoOperation.operation("terminateMandate")
                        .through("mandateGuarantorChannel")
                        .request("""
                                reason: Guided scenario complete
                                """)
                        .build());
        return demo.process(entry).onlyResult();
    }

    @Override
    public void close() {
        demo.close();
    }

    /** Exact authored request and the feeder decision made before PROCESS. */
    public record RequestedOperation(
            MyOsDemoEntry entry,
            MandateEligibilityDecision decision) {
    }
}
