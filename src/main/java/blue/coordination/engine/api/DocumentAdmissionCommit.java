package blue.coordination.engine.api;

import java.util.Objects;

/** Atomic epoch-zero session-store input produced after fragment admission. */
public final class DocumentAdmissionCommit {

    private final DocumentRegistration registration;
    private final ManagedDocumentSnapshot session;
    private final DocumentEpochSnapshot epochZero;
    private final CoordinationFragmentInventory inventory;

    public DocumentAdmissionCommit(
            DocumentRegistration registration,
            ManagedDocumentSnapshot session,
            DocumentEpochSnapshot epochZero,
            CoordinationFragmentInventory inventory) {
        this.registration = Objects.requireNonNull(
                registration, "registration");
        this.session = Objects.requireNonNull(session, "session");
        this.epochZero = Objects.requireNonNull(epochZero, "epochZero");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        if (!registration.sessionId().equals(session.sessionId())
                || !session.sessionId().equals(epochZero.sessionId())
                || epochZero.epoch() != 0L
                || !session.currentRootBlueId().equals(inventory.rootBlueId())
                || !session.currentRootBlueId().equals(epochZero.rootBlueId())
                || !session.fragmentInventoryIdentity().equals(
                        inventory.inventoryIdentity())) {
            throw new IllegalArgumentException(
                    "Epoch-zero admission values do not bind exactly");
        }
    }

    public DocumentRegistration registration() { return registration; }
    public ManagedDocumentSnapshot session() { return session; }
    public DocumentEpochSnapshot epochZero() { return epochZero; }
    public CoordinationFragmentInventory inventory() { return inventory; }
}
