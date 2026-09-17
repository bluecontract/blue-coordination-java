package blue.coordination.internal;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import static blue.coordination.internal.SessionStorageWire.require;

/** Library-derived point selection of a publication by its exact successful result and companion. */
final class ClosureApplicationResultIndex {
    /** Two canonical witnesses suffice to retain the original ambiguity rejection, regardless of fan-out. */
    record Publications(List<String> identities) {
        Publications {
            identities = List.copyOf(identities);
            require(!identities.isEmpty() && identities.size() <= 2, "Invalid application-result witnesses");
            for (int i = 0; i < identities.size(); i++) {
                require(!identities.get(i).isBlank(), "Blank application-result publication");
                if (i > 0) require(EmbeddingBinding.TEXT_ORDER.compare(identities.get(i - 1), identities.get(i)) < 0,
                        "Application-result witnesses are not canonical");
            }
        }
        Publications with(String identity) {
            var next = new java.util.TreeSet<String>(EmbeddingBinding.TEXT_ORDER);
            next.addAll(identities); next.add(identity);
            return new Publications(next.stream().limit(2).toList());
        }
    }

    private final PersistentOrderedMap<String, Publications> rows;
    private Supplier<ClosureApplicationResultIndex> strictVerification;

    private ClosureApplicationResultIndex(PersistentOrderedMap<String, Publications> rows) {
        this.rows = Objects.requireNonNull(rows);
    }

    static ClosureApplicationResultIndex empty() {
        return new ClosureApplicationResultIndex(PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER));
    }

    /** Unambiguous framing, independent of punctuation within either identity. */
    static String key(String resultIdentity, String companionIdentity) {
        Objects.requireNonNull(resultIdentity); Objects.requireNonNull(companionIdentity);
        return resultIdentity.length() + ":" + resultIdentity + companionIdentity;
    }

    static ClosureApplicationResultIndex from(Iterable<ContractsClosurePublicationReceipt> receipts) {
        var result = empty();
        for (var receipt : receipts) result = result.withReceipt(receipt);
        return result;
    }

    static ClosureApplicationResultIndex restoreStored(PersistentOrderedMap<String, Publications> rows) {
        return new ClosureApplicationResultIndex(rows);
    }

    /** Raw storage cannot establish missing/duplicate membership from a single selected witness. */
    static ClosureApplicationResultIndex restoreStrict(PersistentOrderedMap<String, Publications> rows,
            Supplier<ClosureApplicationResultIndex> verification) {
        var result = restoreStored(rows);
        result.strictVerification = Objects.requireNonNull(verification);
        return result;
    }

    PersistentOrderedMap<String, Publications> rows() { requireVerified(); return rows; }

    ClosureApplicationResultIndex withReceipt(ContractsClosurePublicationReceipt receipt) {
        requireVerified();
        var result = Objects.requireNonNull(receipt).attempt().processResult();
        // Preserve the original selector: a successful PROCESS can still have a rejected publication draft.
        if (!result.commits()) return this;
        String key = key(result.outputClosureIdentity(), result.commitCompanion().companionIdentity());
        var old = rows.get(key);
        var next = old == null ? new Publications(List.of(receipt.publicationIdentity()))
                : old.with(receipt.publicationIdentity());
        return next.equals(old) ? this : new ClosureApplicationResultIndex(rows.put(key, next).map());
    }

    String publication(String resultIdentity, String companionIdentity) {
        requireVerified();
        var selected = rows.get(key(resultIdentity, companionIdentity));
        if (selected == null) return null;
        if (selected.identities().size() > 1)
            throw new IllegalStateException("Ambiguous retained rooted application result");
        return selected.identities().get(0);
    }

    void requireMatches(ClosureApplicationResultIndex expected) {
        var actualRows = rows.range(null, null);
        var expectedRows = expected.rows.range(null, null);
        while (actualRows.hasNext() && expectedRows.hasNext())
            require(actualRows.next().equals(expectedRows.next()), "Application-result projection differs from retained receipts");
        require(!actualRows.hasNext() && !expectedRows.hasNext(), "Application-result projection is incomplete");
    }

    private synchronized void requireVerified() {
        if (strictVerification != null) {
            requireMatches(Objects.requireNonNull(strictVerification.get()));
            strictVerification = null;
        }
    }
}
