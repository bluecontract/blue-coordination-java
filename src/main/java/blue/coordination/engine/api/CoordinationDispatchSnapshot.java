package blue.coordination.engine.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable observable state of a resumable fan-out. */
public final class CoordinationDispatchSnapshot {

    private final CoordinationDispatchPlan plan;
    private final List<List<CoordinationDeliveryReceipt>> receiptPages;
    private final List<CoordinationDeliveryReceipt> receipts;

    /**
     * Compatibility constructor for callers holding a flat receipt vector.
     * The snapshot immediately stores it using the plan's page boundaries.
     */
    public CoordinationDispatchSnapshot(
            CoordinationDispatchPlan plan,
            List<CoordinationDeliveryReceipt> receipts) {
        this(plan, partitionReceipts(plan, receipts), true);
    }

    /** Creates complete receipt evidence without constructing a flat list. */
    public static CoordinationDispatchSnapshot fromReceiptPages(
            CoordinationDispatchPlan plan,
            List<List<CoordinationDeliveryReceipt>> receiptPages) {
        return new CoordinationDispatchSnapshot(
                plan, receiptPages, false);
    }

    private CoordinationDispatchSnapshot(
            CoordinationDispatchPlan plan,
            List<List<CoordinationDeliveryReceipt>> suppliedPages,
            boolean alreadyOwned) {
        this.plan = Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(suppliedPages, "receiptPages");
        if (suppliedPages.size() != plan.pageCount()) {
            throw new IllegalArgumentException(
                    "Receipt pages must match frozen target pages");
        }
        List<List<CoordinationDeliveryReceipt>> copied =
                new ArrayList<List<CoordinationDeliveryReceipt>>(
                        suppliedPages.size());
        for (int pageIndex = 0;
                pageIndex < suppliedPages.size();
                pageIndex++) {
            List<CoordinationDeliveryReceipt> suppliedPage =
                    Objects.requireNonNull(
                            suppliedPages.get(pageIndex), "receiptPage");
            List<IndexedSessionCandidates> targetPage =
                    plan.pages().get(pageIndex);
            if (suppliedPage.size() != targetPage.size()) {
                throw new IllegalArgumentException(
                        "Exactly one receipt is required for each target");
            }
            List<CoordinationDeliveryReceipt> receiptPage = alreadyOwned
                    ? suppliedPage
                    : Collections.unmodifiableList(
                            new ArrayList<CoordinationDeliveryReceipt>(
                                    suppliedPage));
            for (int offset = 0;
                    offset < receiptPage.size();
                    offset++) {
                requireBinding(
                        plan,
                        targetPage.get(offset),
                        Objects.requireNonNull(
                                receiptPage.get(offset), "receipt"),
                        pageIndex,
                        offset);
            }
            copied.add(receiptPage);
        }
        this.receiptPages = Collections.unmodifiableList(copied);
        this.receipts = new CoordinationPagedList<CoordinationDeliveryReceipt>(
                this.receiptPages);
    }

    public CoordinationDispatchPlan plan() { return plan; }

    /** Lazy flattened compatibility view over {@link #receiptPages()}. */
    public List<CoordinationDeliveryReceipt> receipts() { return receipts; }

    /** Complete immutable evidence, addressable one bounded page at a time. */
    public List<List<CoordinationDeliveryReceipt>> receiptPages() {
        return receiptPages;
    }

    public List<CoordinationDeliveryReceipt> receiptPage(int pageIndex) {
        return receiptPages.get(pageIndex);
    }

    public boolean complete() {
        for (List<CoordinationDeliveryReceipt> page : receiptPages) {
            for (CoordinationDeliveryReceipt receipt : page) {
                if (!receipt.succeeded()) return false;
            }
        }
        return true;
    }

    public int succeededCount() {
        int result = 0;
        for (List<CoordinationDeliveryReceipt> page : receiptPages) {
            for (CoordinationDeliveryReceipt receipt : page) {
                if (receipt.succeeded()) result++;
            }
        }
        return result;
    }

    private static List<List<CoordinationDeliveryReceipt>> partitionReceipts(
            CoordinationDispatchPlan plan,
            List<CoordinationDeliveryReceipt> supplied) {
        CoordinationDispatchPlan checkedPlan = Objects.requireNonNull(
                plan, "plan");
        List<CoordinationDeliveryReceipt> checked = Objects.requireNonNull(
                supplied, "receipts");
        if (checked.size() != checkedPlan.targetCount()) {
            throw new IllegalArgumentException(
                    "Exactly one receipt is required for each target");
        }
        List<List<CoordinationDeliveryReceipt>> result =
                new ArrayList<List<CoordinationDeliveryReceipt>>(
                        checkedPlan.pageCount());
        int receiptIndex = 0;
        for (List<IndexedSessionCandidates> targetPage
                : checkedPlan.pages()) {
            List<CoordinationDeliveryReceipt> receiptPage =
                    new ArrayList<CoordinationDeliveryReceipt>(
                            targetPage.size());
            for (int offset = 0;
                    offset < targetPage.size();
                    offset++) {
                receiptPage.add(checked.get(receiptIndex));
                receiptIndex++;
            }
            result.add(Collections.unmodifiableList(receiptPage));
        }
        return Collections.unmodifiableList(result);
    }

    private static void requireBinding(
            CoordinationDispatchPlan plan,
            IndexedSessionCandidates target,
            CoordinationDeliveryReceipt receipt,
            int pageIndex,
            int offset) {
        if (!plan.event().eventBlueId().equals(receipt.eventBlueId())
                || !target.sessionId().equals(receipt.sessionId())
                || target.plannedEpoch() != receipt.plannedEpoch()
                || !target.plannedRootBlueId().equals(
                        receipt.plannedRootBlueId())
                || !target.subscriptionSnapshotIdentity().equals(
                        receipt.plannedSubscriptionSnapshotIdentity())
                || !target.orderedOccurrenceKeys().equals(
                        receipt.orderedOccurrenceKeys())) {
            throw new IllegalArgumentException(
                    "Receipt does not bind to frozen target page "
                            + pageIndex + " offset " + offset);
        }
    }
}
