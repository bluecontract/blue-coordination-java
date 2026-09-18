package blue.coordination.api.storage;

import java.util.List;
import blue.coordination.api.storage.CoordinationRecords.*;

/**
 * Host persistence of library-encoded logical records. All writers to a namespace
 * must use the same atomic condition protocol, including administrative writers.
 * Immutable bodies are stored separately through {@link CoordinationImmutableObjectStore}.
 * No lock protecting writes may span semantic execution.
 */
public interface CoordinationRecordStore {
    /**
     * Opens a coherent lazy view of exactly one namespace and execution instance.
     * Reads must all belong to one database snapshot, never successive latest reads.
     * The caller closes the view before attempting publication. Implementations
     * document connection capacity and enforce their physical resource bounds.
     * @param address exact host namespace and non-reusable execution instance
     * @return new thread-confined view; no mutable state is shared with other views
     */
    ReadScope open(Address address);

    /**
     * Atomically validates every condition and artifact, installs all mutations,
     * and records identity/digest/evidence. Validation must prevent write skew and
     * range phantoms against every writer, not only conflicting same-row writes.
     * An identical committed identity returns ALREADY_COMMITTED before comparing
     * obsolete conditions. Reusing an identity with other bytes is an integrity error.
     * A transport failure whose outcome cannot be established returns UNKNOWN.
     * Retrying this exact detached packet must never execute semantic work.
     * @param publication closed immutable packet
     * @return durable resolution, or UNKNOWN requiring reconciliation
     */
    Resolution publish(Publication publication);

    /**
     * Resolves a possibly still-running original transaction. Absence alone is not
     * proof of rollback: an implementation must establish that the original can
     * no longer commit before returning NOT_COMMITTED. It may return UNKNOWN on
     * bounded lock/transport failure. A different stored digest is an integrity error.
     * @param address exact namespace and instance
     * @param publicationId original stable logical publication identity
     * @param digest expected canonical packet digest
     * @return COMMITTED, NOT_COMMITTED or UNKNOWN
     */
    Reconciliation reconcile(Address address, String publicationId, Bytes digest);

    /** Coherent read-only scope, closed or thread-foreign access must fail. */
    interface ReadScope extends AutoCloseable {
        /** Exact namespace/instance of every record returned by this scope. */
        Address address();
        /**
         * Returns the value or a versioned tombstone; never-seen absence has
         * revision zero. Deletion does not reset a key's revision.
         * @param key complete logical key
         * @return detached value/version from the pinned snapshot
         */
        Value read(Key key);
        /**
         * Complete live membership of the given half-open range in unsigned byte
         * order. No partial page or truncated result is permitted. Exceeding a
         * physical bound throws; it cannot be reported as an empty/complete range.
         * @param range exact family, scope and bounds
         * @return detached ordered rows from the same snapshot as point reads
         */
        List<Row> query(Range range);
        /** Releases snapshot resources; idempotent on the owning thread. */
        @Override void close();
    }

    /** Publication outcome; conflict permits a new selection, unknown does not. */
    enum Resolution { COMMITTED, ALREADY_COMMITTED, CONFLICT, UNKNOWN }
    /** Outcome after excluding any still-running original transaction. */
    enum Reconciliation { COMMITTED, NOT_COMMITTED, UNKNOWN }
}
