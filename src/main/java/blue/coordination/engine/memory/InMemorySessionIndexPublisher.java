package blue.coordination.engine.memory;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.spi.CoordinationTargetCursor;
import blue.language.processor.ExternalOrderKey;

import java.util.List;
import java.util.Objects;

/**
 * One observable in-memory publication boundary for session and route state.
 *
 * <p>Both backing objects are locked before authoritative state changes. The
 * environment-owned target-cursor boundary acquires the same monitors in the
 * same order, giving route freeze one linearization point across session and
 * route state. Separate public reads of the two backing stores are not a
 * combined snapshot and must retain their ordinary revision checks.</p>
 */
public final class InMemorySessionIndexPublisher {

    private final CoordinationProcessingEngine engine;
    private final InMemoryCoordinationSessionStore sessionStore;
    private final InMemoryCoordinationSubscriptionIndex subscriptionIndex;
    private final PublicationHook publicationHook;

    public InMemorySessionIndexPublisher(
            CoordinationProcessingEngine engine,
            InMemoryCoordinationSessionStore sessionStore,
            InMemoryCoordinationSubscriptionIndex subscriptionIndex) {
        this(
                engine,
                sessionStore,
                subscriptionIndex,
                new PublicationHook() {
                    @Override
                    public void afterAuthoritativeSessionChange(
                            ManagedDocumentSnapshot snapshot) {
                        // Production publication has no intermediate action.
                    }
                });
    }

    InMemorySessionIndexPublisher(
            CoordinationProcessingEngine engine,
            InMemoryCoordinationSessionStore sessionStore,
            InMemoryCoordinationSubscriptionIndex subscriptionIndex,
            PublicationHook publicationHook) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.sessionStore = Objects.requireNonNull(
                sessionStore, "sessionStore");
        this.subscriptionIndex = Objects.requireNonNull(
                subscriptionIndex, "subscriptionIndex");
        this.publicationHook = Objects.requireNonNull(
                publicationHook, "publicationHook");
    }

    public DocumentAdmissionResult admitAndPublish(
            DocumentRegistration registration) {
        synchronized (sessionStore) {
            synchronized (subscriptionIndex) {
                DocumentAdmissionResult result = engine.addDocument(
                        Objects.requireNonNull(
                                registration, "registration"));
                if (result.succeeded()) {
                    publish(result.session().get());
                }
                return result;
            }
        }
    }

    public DemoTransition commitAndPublish(
            CoordinationTransition transition) {
        CoordinationTransition checked = Objects.requireNonNull(
                transition, "transition");
        CommitOutcome outcome;
        DemoTransition committed;
        synchronized (sessionStore) {
            synchronized (subscriptionIndex) {
                outcome = engine.commit(checked);
                if (!outcome.committed()) {
                    throw new IllegalStateException(
                            "Session CAS failed: " + outcome.status());
                }
                /* An exact retry may return ALREADY_COMMITTED with the
                 * snapshot captured by the original transition. A newer
                 * transition can have advanced this session since then, so
                 * republishing the outcome snapshot would regress derived
                 * route rows to a historical epoch. The store is already
                 * locked here; publish its current authoritative value. */
                ManagedDocumentSnapshot authoritative = sessionStore
                        .findSession(checked.plan().session().sessionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Committed session is absent after CAS"));
                publish(authoritative);
                committed = new DemoTransition(checked, outcome);
            }
        }
        engine.installPreparedRootContextAfterPublication(checked, outcome);
        return committed;
    }

    /**
     * Opens one immutable route cursor while authoritative session and derived
     * route state are known to belong to the same publication boundary.
     *
     * <p>The monitors are released after the index has captured its immutable
     * generation. A later legitimate publication can therefore make a frozen
     * target stale; delivery remains responsible for its exact revision check.</p>
     */
    CoordinationTargetCursor openAuthoritativeCandidates(
            List<String> exactEventSubscriptionKeys,
            String sourceChannel,
            ExternalOrderKey eventOrderKey) {
        synchronized (sessionStore) {
            synchronized (subscriptionIndex) {
                return subscriptionIndex.openCandidates(
                        Objects.requireNonNull(
                                exactEventSubscriptionKeys,
                                "exactEventSubscriptionKeys"),
                        Objects.requireNonNull(sourceChannel, "sourceChannel"),
                        Objects.requireNonNull(eventOrderKey, "eventOrderKey"));
            }
        }
    }

    private void publish(ManagedDocumentSnapshot authoritative) {
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        try {
            publicationHook.afterAuthoritativeSessionChange(authoritative);
        } catch (RuntimeException failure) {
            runtimeFailure = failure;
        } catch (Error failure) {
            errorFailure = failure;
        }
        subscriptionIndex.replaceSession(authoritative);
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        if (errorFailure != null) {
            throw errorFailure;
        }
    }

    /**
     * Deterministic seam invoked after the session CAS and before route rows.
     *
     * <p>The callback runs while both publication monitors are held. It is
     * package-owned so tests can prove the invisible intermediate state
     * without exposing a production lifecycle extension point.</p>
     */
    interface PublicationHook {
        void afterAuthoritativeSessionChange(
                ManagedDocumentSnapshot snapshot);
    }
}
