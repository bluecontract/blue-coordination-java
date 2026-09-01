package blue.coordination.api;

import blue.language.identity.BlueIds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable committed-versus-ready audit evidence for one managed document. */
public final class ManagedDocumentReadiness {
    private static final String IDENTITY_DOMAIN =
            "blue-coordination-managed-document-readiness/1.0";

    private final String readinessIdentity;
    private final DocumentId documentId;
    private final long committedEpoch;
    private final String committedBlueId;
    private final Long readyEpoch;
    private final String readyBlueId;
    private final SessionStatus status;
    private final String waitingCode;
    private final String waitingMessage;
    private final List<String> activeBarrierIdentities;

    /** Creates and verifies one immutable readiness audit snapshot. */
    public ManagedDocumentReadiness(
            String readinessIdentity,
            DocumentId documentId,
            long committedEpoch,
            String committedBlueId,
            Long readyEpoch,
            String readyBlueId,
            SessionStatus status,
            String waitingCode,
            String waitingMessage,
            List<String> activeBarrierIdentities) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.committedEpoch = ManagedIdentity.requireSafeInteger(
                committedEpoch, "committedEpoch");
        this.committedBlueId = BlueIds.requireBlueIdOrCyclicMember(
                committedBlueId, "/committedBlueId");
        if ((readyEpoch == null) != (readyBlueId == null)) {
            throw new IllegalArgumentException(
                    "readyEpoch and readyBlueId must be present together");
        }
        this.readyEpoch = readyEpoch == null ? null
                : Long.valueOf(ManagedIdentity.requireSafeInteger(
                        readyEpoch.longValue(), "readyEpoch"));
        this.readyBlueId = readyBlueId == null ? null
                : BlueIds.requireBlueIdOrCyclicMember(
                        readyBlueId, "/readyBlueId");
        this.status = Objects.requireNonNull(status, "status");
        this.waitingCode = waitingCode == null ? null
                : ManagedIdentity.requireText(waitingCode, "waitingCode");
        this.waitingMessage = waitingMessage == null ? null
                : ManagedIdentity.requireText(waitingMessage, "waitingMessage");
        this.activeBarrierIdentities = canonicalIdentities(
                activeBarrierIdentities);
        requireCoherentHeads();
        this.readinessIdentity = ManagedIdentity.verify(
                readinessIdentity,
                IDENTITY_DOMAIN,
                identityValue(),
                "readinessIdentity");
    }

    /** Derives one exact readiness snapshot identity. */
    public static ManagedDocumentReadiness identified(
            DocumentId documentId,
            long committedEpoch,
            String committedBlueId,
            Long readyEpoch,
            String readyBlueId,
            SessionStatus status,
            String waitingCode,
            String waitingMessage,
            List<String> activeBarrierIdentities) {
        List<String> barriers = canonicalIdentities(activeBarrierIdentities);
        Map<String, Object> value = identityValue(
                documentId,
                committedEpoch,
                committedBlueId,
                readyEpoch,
                readyBlueId,
                status,
                waitingCode,
                waitingMessage,
                barriers);
        return new ManagedDocumentReadiness(
                ManagedIdentity.identify(IDENTITY_DOMAIN, value),
                documentId,
                committedEpoch,
                committedBlueId,
                readyEpoch,
                readyBlueId,
                status,
                waitingCode,
                waitingMessage,
                barriers);
    }

    public String readinessIdentity() { return readinessIdentity; }

    public DocumentId documentId() { return documentId; }

    public long committedEpoch() { return committedEpoch; }

    public String committedBlueId() { return committedBlueId; }

    public OptionalLong readyEpoch() {
        return readyEpoch == null
                ? OptionalLong.empty()
                : OptionalLong.of(readyEpoch.longValue());
    }

    public Optional<String> readyBlueId() {
        return Optional.ofNullable(readyBlueId);
    }

    public SessionStatus status() { return status; }

    public Optional<String> waitingCode() {
        return Optional.ofNullable(waitingCode);
    }

    public Optional<String> waitingMessage() {
        return Optional.ofNullable(waitingMessage);
    }

    public List<String> activeBarrierIdentities() {
        return activeBarrierIdentities;
    }

    public boolean ready() {
        return readyEpoch != null
                && readyEpoch.longValue() == committedEpoch
                && readyBlueId.equals(committedBlueId)
                && status == SessionStatus.READY;
    }

    private void requireCoherentHeads() {
        if (readyEpoch != null && readyEpoch.longValue() > committedEpoch) {
            throw new IllegalArgumentException(
                    "ready epoch cannot exceed committed epoch");
        }
        if (status == SessionStatus.READY && !ready()) {
            throw new IllegalArgumentException(
                    "READY requires identical committed and ready heads");
        }
        if (status == SessionStatus.READY
                && !activeBarrierIdentities.isEmpty()) {
            throw new IllegalArgumentException(
                    "READY cannot retain an active catch-up barrier");
        }
        if (waitingMessage != null && waitingCode == null) {
            throw new IllegalArgumentException(
                    "waitingMessage requires waitingCode");
        }
    }

    private Map<String, Object> identityValue() {
        return identityValue(
                documentId,
                committedEpoch,
                committedBlueId,
                readyEpoch,
                readyBlueId,
                status,
                waitingCode,
                waitingMessage,
                activeBarrierIdentities);
    }

    private static Map<String, Object> identityValue(
            DocumentId documentId,
            long committedEpoch,
            String committedBlueId,
            Long readyEpoch,
            String readyBlueId,
            SessionStatus status,
            String waitingCode,
            String waitingMessage,
            List<String> activeBarrierIdentities) {
        return ManagedIdentity.fields(
                "documentId", documentId.value(),
                "committedEpoch", committedEpoch,
                "committedBlueId", committedBlueId,
                "readyEpoch", readyEpoch,
                "readyBlueId", readyBlueId,
                "status", status.name(),
                "waitingCode", waitingCode,
                "waitingMessage", waitingMessage,
                "activeBarrierIdentities", activeBarrierIdentities);
    }

    private static List<String> canonicalIdentities(List<String> values) {
        ArrayList<String> sorted = new ArrayList<>();
        for (String value : Objects.requireNonNull(
                values, "activeBarrierIdentities")) {
            sorted.add(ManagedIdentity.requireSha256(
                    value, "activeBarrierIdentity"));
        }
        sorted.sort(Comparator.naturalOrder());
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).equals(sorted.get(index))) {
                throw new IllegalArgumentException(
                        "activeBarrierIdentities must be distinct");
            }
        }
        return List.copyOf(sorted);
    }
}
