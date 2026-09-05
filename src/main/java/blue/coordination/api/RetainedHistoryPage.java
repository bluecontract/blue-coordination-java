package blue.coordination.api;

import blue.coordination.api.ManagedEpochReceipt;
import blue.language.identity.BlueIds;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.List;
import java.util.Objects;

/**
 * Signed complete range from one retained lineage. Signature verification
 * authenticates the configured producer, not arbitrary Contracts execution.
 * Consumers must separately establish that producer's publication authority.
 */
public final class RetainedHistoryPage {
    private final RetainedHistoryProvider.Request request;
    private final String authoredInitialBlueId;
    private final long headEpoch;
    private final String headBlueId;
    private final String predecessorBlueId;
    private final List<ManagedEpochReceipt> receipts;
    private final byte[] signature;

    /** Transport constructor; values become trusted only through verify(). */
    public RetainedHistoryPage(RetainedHistoryProvider.Request request,
            String authoredInitialBlueId, long headEpoch, String headBlueId,
            String predecessorBlueId, List<ManagedEpochReceipt> receipts,
            byte[] signature) {
        this.request = Objects.requireNonNull(request, "request");
        this.authoredInitialBlueId = BlueIds.requireBlueIdOrCyclicMember(
                authoredInitialBlueId, "authoredInitialBlueId");
        this.headBlueId = BlueIds.requireBlueIdOrCyclicMember(headBlueId, "headBlueId");
        this.predecessorBlueId = BlueIds.requireBlueIdOrCyclicMember(
                predecessorBlueId, "predecessorBlueId");
        if (headEpoch < 0 || headEpoch > 9_007_199_254_740_991L
                || request.firstEpoch() > headEpoch + 1) {
            throw new IllegalArgumentException("Page starts beyond the retained head");
        }
        this.headEpoch = headEpoch;
        Objects.requireNonNull(receipts, "receipts");
        if (receipts.size() != Math.min(request.limit(), headEpoch - request.firstEpoch() + 1)) {
            throw new IllegalArgumentException("Incomplete retained history page");
        }
        this.receipts = List.copyOf(receipts);
        this.signature = Objects.requireNonNull(signature, "signature").clone();
        if (signature.length != 64) {
            throw new IllegalArgumentException("Expected an Ed25519 signature");
        }
        verifyRange();
    }

    public RetainedHistoryProvider.Request request() { return request; }
    public String authoredInitialBlueId() { return authoredInitialBlueId; }
    public long headEpoch() { return headEpoch; }
    public String headBlueId() { return headBlueId; }
    public String predecessorBlueId() { return predecessorBlueId; }
    public List<ManagedEpochReceipt> receipts() { return receipts; }
    public byte[] signature() { return signature.clone(); }

    /**
     * Verifies authenticity, exact request, lineage anchor and predecessor.
     * expectedPredecessor must come from the consumer's retained cursor or
     * independently authenticated attachment, never from this response.
     */
    public Verified verify(RetainedHistoryProvider.Request expectedRequest,
            String expectedAuthoredInitialBlueId, String expectedPredecessor,
            PublicKey trustedProducer) {
        if (!request.equals(expectedRequest)
                || !authoredInitialBlueId.equals(expectedAuthoredInitialBlueId)
                || !predecessorBlueId.equals(expectedPredecessor)) {
            throw new IllegalArgumentException("Retained page does not bind the request and cursor");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(Objects.requireNonNull(trustedProducer, "trustedProducer"));
            verifier.update(transcript());
            if (!verifier.verify(signature)) {
                throw new IllegalArgumentException("Invalid retained history signature");
            }
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Cannot verify retained history signature", failure);
        }
        return new Verified(this);
    }

    // Only the owning runtime may sign already committed retained receipts.
    static RetainedHistoryPage signed(RetainedHistoryProvider.Request request,
            String authoredInitialBlueId, long headEpoch, String headBlueId,
            String predecessorBlueId, List<ManagedEpochReceipt> receipts,
            PrivateKey producerKey) {
        RetainedHistoryPage page = new RetainedHistoryPage(request,
                authoredInitialBlueId, headEpoch, headBlueId, predecessorBlueId,
                receipts, new byte[64]);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(producerKey);
            signer.update(page.transcript());
            return new RetainedHistoryPage(request, authoredInitialBlueId,
                    headEpoch, headBlueId, predecessorBlueId, receipts, signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Cannot sign retained history", failure);
        }
    }

    private void verifyRange() {
        long expectedCount = Math.min(request.limit(),
                headEpoch - request.firstEpoch() + 1);
        if (receipts.size() != expectedCount) {
            throw new IllegalArgumentException("Incomplete retained history page");
        }
        String before = predecessorBlueId;
        for (int i = 0; i < receipts.size(); i++) {
            ManagedEpochReceipt receipt = receipts.get(i);
            long epoch = request.firstEpoch() + i;
            if (!receipt.documentId().equals(request.documentId())
                    || receipt.epoch() != epoch
                    || (epoch == 0 ? receipt.beforeBlueId().isPresent()
                            || !before.equals(authoredInitialBlueId)
                            : !receipt.beforeBlueId().orElse("").equals(before))) {
                throw new IllegalArgumentException("Retained lineage, order or predecessor mismatch");
            }
            before = receipt.afterBlueId();
        }
        if (request.firstEpoch() + receipts.size() == headEpoch + 1
                && !before.equals(headBlueId)) {
            throw new IllegalArgumentException("Retained page does not reach its declared head");
        }
    }

    private byte[] transcript() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("blue-coordination-retained-history-page/1.0");
            out.writeUTF(request.documentId().value());
            out.writeLong(request.firstEpoch());
            out.writeInt(request.limit());
            out.writeUTF(request.challenge());
            out.writeUTF(authoredInitialBlueId);
            out.writeLong(headEpoch);
            out.writeUTF(headBlueId);
            out.writeUTF(predecessorBlueId);
            out.writeInt(receipts.size());
            for (ManagedEpochReceipt receipt : receipts) {
                out.writeUTF(receipt.receiptIdentity());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode retained history transcript", failure);
        }
    }

    /** Authenticated evidence for a host adapter; does not mutate a runtime. */
    public static final class Verified {
        private final RetainedHistoryPage page;

        private Verified(RetainedHistoryPage page) { this.page = page; }

        public List<ManagedEpochReceipt> receipts() { return page.receipts(); }
        public long nextEpoch() { return page.request.firstEpoch() + page.receipts.size(); }
        public long observedHeadEpoch() { return page.headEpoch(); }
        public String observedHeadBlueId() { return page.headBlueId(); }
    }
}
