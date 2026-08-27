package blue.coordination.api;

import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable audit snapshot of the readiness barrier for one exact cause. */
public final class ManagedCatchUpBarrier {
    private static final String IDENTITY_DOMAIN =
            "blue-coordination-managed-catch-up-barrier/1.0";
    private static final String SNAPSHOT_IDENTITY_DOMAIN =
            "blue-coordination-managed-catch-up-barrier-snapshot/1.0";

    private final String barrierIdentity;
    private final String snapshotIdentity;
    private final DocumentId consumerDocumentId;
    private final String causedByIdentity;
    private final ExternalOrderKey causeOrder;
    private final List<String> planIdentities;
    private final ManagedCatchUpBarrierStatus status;
    private final String waitingCode;
    private final String waitingMessage;

    /** Creates and verifies one immutable barrier progress snapshot. */
    public ManagedCatchUpBarrier(
            String barrierIdentity,
            String snapshotIdentity,
            DocumentId consumerDocumentId,
            String causedByIdentity,
            ExternalOrderKey causeOrder,
            List<String> planIdentities,
            ManagedCatchUpBarrierStatus status,
            String waitingCode,
            String waitingMessage) {
        this.consumerDocumentId = Objects.requireNonNull(
                consumerDocumentId, "consumerDocumentId");
        this.causedByIdentity = ManagedIdentity.requireSha256(
                causedByIdentity, "causedByIdentity");
        this.causeOrder = Objects.requireNonNull(causeOrder, "causeOrder");
        this.planIdentities = canonicalPlanIdentities(planIdentities);
        this.status = Objects.requireNonNull(status, "status");
        this.waitingCode = waitingCode == null ? null
                : ManagedIdentity.requireText(waitingCode, "waitingCode");
        this.waitingMessage = waitingMessage == null ? null
                : ManagedIdentity.requireText(waitingMessage, "waitingMessage");
        requireWaitEvidence();
        this.barrierIdentity = ManagedIdentity.verify(
                barrierIdentity,
                IDENTITY_DOMAIN,
                definitionIdentityValue(),
                "barrierIdentity");
        this.snapshotIdentity = ManagedIdentity.verify(
                snapshotIdentity,
                SNAPSHOT_IDENTITY_DOMAIN,
                snapshotIdentityValue(),
                "snapshotIdentity");
    }

    /** Derives stable barrier and exact member/progress snapshot identities. */
    public static ManagedCatchUpBarrier identified(
            DocumentId consumerDocumentId,
            String causedByIdentity,
            ExternalOrderKey causeOrder,
            List<String> planIdentities,
            ManagedCatchUpBarrierStatus status,
            String waitingCode,
            String waitingMessage) {
        Map<String, Object> definition = definitionIdentityValue(
                consumerDocumentId, causedByIdentity, causeOrder);
        String barrierIdentity = ManagedIdentity.identify(
                IDENTITY_DOMAIN, definition);
        List<String> plans = canonicalPlanIdentities(planIdentities);
        Map<String, Object> snapshot = snapshotIdentityValue(
                barrierIdentity, plans, status, waitingCode, waitingMessage);
        return new ManagedCatchUpBarrier(
                barrierIdentity,
                ManagedIdentity.identify(SNAPSHOT_IDENTITY_DOMAIN, snapshot),
                consumerDocumentId,
                causedByIdentity,
                causeOrder,
                plans,
                status,
                waitingCode,
                waitingMessage);
    }

    public String barrierIdentity() { return barrierIdentity; }

    public String snapshotIdentity() { return snapshotIdentity; }

    public DocumentId consumerDocumentId() { return consumerDocumentId; }

    public String causedByIdentity() { return causedByIdentity; }

    public ExternalOrderKey causeOrder() { return causeOrder; }

    public List<String> planIdentities() { return planIdentities; }

    public ManagedCatchUpBarrierStatus status() { return status; }

    public Optional<String> waitingCode() {
        return Optional.ofNullable(waitingCode);
    }

    public Optional<String> waitingMessage() {
        return Optional.ofNullable(waitingMessage);
    }

    private void requireWaitEvidence() {
        boolean waiting = status == ManagedCatchUpBarrierStatus
                .WAITING_FOR_HISTORY
                || status == ManagedCatchUpBarrierStatus.BLOCKED;
        if (waiting != (waitingCode != null)) {
            throw new IllegalArgumentException(
                    "waiting and blocked barriers require one waiting code");
        }
        if (waitingMessage != null && waitingCode == null) {
            throw new IllegalArgumentException(
                    "waitingMessage requires waitingCode");
        }
    }

    private Map<String, Object> definitionIdentityValue() {
        return definitionIdentityValue(
                consumerDocumentId, causedByIdentity, causeOrder);
    }

    private static Map<String, Object> definitionIdentityValue(
            DocumentId consumerDocumentId,
            String causedByIdentity,
            ExternalOrderKey causeOrder) {
        return ManagedIdentity.fields(
                "consumerDocumentId", consumerDocumentId.value(),
                "causedByIdentity", causedByIdentity,
                "causeOrder", ManagedIdentity.canonicalOrderComponents(
                        causeOrder.components()));
    }

    private Map<String, Object> snapshotIdentityValue() {
        return snapshotIdentityValue(
                barrierIdentity,
                planIdentities,
                status,
                waitingCode,
                waitingMessage);
    }

    private static Map<String, Object> snapshotIdentityValue(
            String barrierIdentity,
            List<String> planIdentities,
            ManagedCatchUpBarrierStatus status,
            String waitingCode,
            String waitingMessage) {
        return ManagedIdentity.fields(
                "barrierIdentity", barrierIdentity,
                "planIdentities", planIdentities,
                "status", status.name(),
                "waitingCode", waitingCode,
                "waitingMessage", waitingMessage);
    }

    private static List<String> canonicalPlanIdentities(List<String> values) {
        ArrayList<String> sorted = new ArrayList<>();
        for (String value : Objects.requireNonNull(values, "planIdentities")) {
            sorted.add(ManagedIdentity.requireSha256(value, "planIdentity"));
        }
        sorted.sort(Comparator.naturalOrder());
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).equals(sorted.get(index))) {
                throw new IllegalArgumentException(
                        "planIdentities must not contain duplicates");
            }
        }
        return List.copyOf(sorted);
    }
}
