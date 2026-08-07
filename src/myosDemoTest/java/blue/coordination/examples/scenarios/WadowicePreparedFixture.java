package blue.coordination.examples.scenarios;

import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoCheckpoint;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsMeasuredWork;

import java.util.List;
import java.util.Objects;

/**
 * Three purposeful checkpoints built by one linear Wadowice preparation.
 *
 * <p>The JVM-shared fixture admits documents once, attaches the PayNote once,
 * continues through attached conditions once, and continues again through the
 * restaurant outcome once. Tests fork the latest checkpoint preceding their
 * measured action, so no common prefix is replayed and no test shares mutable
 * session state.</p>
 */
public final class WadowicePreparedFixture implements AutoCloseable {

    private final MyOsDemoCheckpoint payNoteAttached;
    private final MyOsDemoCheckpoint conditionsAttached;
    private final MyOsDemoCheckpoint restaurantOutcome;
    private final MyOsMeasuredWork preparationWork;
    private boolean closed;

    private WadowicePreparedFixture(
            MyOsDemoCheckpoint payNoteAttached,
            MyOsDemoCheckpoint conditionsAttached,
            MyOsDemoCheckpoint restaurantOutcome,
            MyOsMeasuredWork preparationWork) {
        this.payNoteAttached = Objects.requireNonNull(
                payNoteAttached, "payNoteAttached");
        this.conditionsAttached = Objects.requireNonNull(
                conditionsAttached, "conditionsAttached");
        this.restaurantOutcome = Objects.requireNonNull(
                restaurantOutcome, "restaurantOutcome");
        this.preparationWork = Objects.requireNonNull(
                preparationWork, "preparationWork");
    }

    /** Builds all checkpoints in one source runtime and closes that runtime. */
    public static WadowicePreparedFixture prepare() {
        try (WadowiceHotelDinnerScenario source =
                     WadowiceHotelDinnerScenario.create(
                             "wadowice-prepared-source")) {
            MyOsDemoAssertions.assertSuccessful(source.attachPayNote());
            MyOsDemoCheckpoint payNoteAttached = source.demo().checkpoint(
                    "pay-note-attached");

            assertSuccessful(source.authorize(
                    "wadowice-auth-50000", 50000));
            assertSuccessful(source.authorize(
                    "wadowice-auth-80000", 80000));
            MyOsDemoAssertions.assertSuccessful(
                    source.createServiceOrders());
            MyOsDemoAssertions.assertSuccessful(
                    source.linkServiceOrders());
            MyOsDemoAssertions.assertSuccessful(
                    source.attachHotelCondition());
            MyOsDemoAssertions.assertSuccessful(
                    source.attachRestaurantCondition());
            MyOsDemoCheckpoint conditionsAttached =
                    source.demo().checkpoint("conditions-attached");

            MyOsDemoAssertions.assertSuccessful(
                    source.confirmRestaurant());
            MyOsDemoAssertions.assertSuccessful(source.confirmHotel());
            MyOsDemoAssertions.assertSuccessful(source.capturePayment());
            MyOsDemoAssertions.assertSuccessful(
                    source.completeHotelStay());
            MyOsDemoCheckpoint restaurantOutcome =
                    source.demo().checkpoint("restaurant-outcome");
            return new WadowicePreparedFixture(
                    payNoteAttached,
                    conditionsAttached,
                    restaurantOutcome,
                    source.demo().measuredWork());
        }
    }

    /** One lazily prepared fixture shared by every Wadowice test class. */
    public static WadowicePreparedFixture shared() {
        return SharedHolder.INSTANCE;
    }

    public synchronized WadowiceHotelDinnerScenario branch(String caseId) {
        return fork(restaurantOutcome, caseId);
    }

    public synchronized WadowiceHotelDinnerScenario conditionsBranch(
            String caseId) {
        return fork(conditionsAttached, caseId);
    }

    public synchronized WadowiceHotelDinnerScenario payNoteBranch(
            String caseId) {
        return fork(payNoteAttached, caseId);
    }

    public MyOsDemoCheckpoint checkpoint() { return restaurantOutcome; }

    public MyOsDemoCheckpoint conditionsCheckpoint() {
        return conditionsAttached;
    }

    public MyOsDemoCheckpoint payNoteCheckpoint() {
        return payNoteAttached;
    }

    public MyOsMeasuredWork preparationWork() { return preparationWork; }

    /** The fixture's three checkpoints came from exactly one source run. */
    public int preparationExecutions() { return 1; }

    private WadowiceHotelDinnerScenario fork(
            MyOsDemoCheckpoint checkpoint,
            String caseId) {
        if (closed) {
            throw new IllegalStateException("Prepared fixture is closed");
        }
        return WadowiceHotelDinnerScenario.fork(
                checkpoint,
                requireText(caseId, "caseId"));
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private static void assertSuccessful(List<MyOsDemoResult> results) {
        results.forEach(MyOsDemoAssertions::assertSuccessful);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " is blank");
        }
        return checked;
    }

    private static final class SharedHolder {
        private static final WadowicePreparedFixture INSTANCE = create();

        private static WadowicePreparedFixture create() {
            WadowicePreparedFixture fixture =
                    WadowicePreparedFixture.prepare();
            Runtime.getRuntime().addShutdownHook(new Thread(
                    fixture::close,
                    "wadowice-prepared-fixture-close"));
            return fixture;
        }
    }
}
