package blue.coordination.sdk;

import java.util.Objects;

/** Stable application handle for one locally registered Timeline. */
public final class TimelineHandle {
    private final Object owner;
    private final String id;
    private final String accountId;

    TimelineHandle(Object owner, String id, String accountId) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.id = SdkPreconditions.requireText(id, "id");
        this.accountId = SdkPreconditions.requireText(
                accountId, "accountId");
    }

    /** Stable local Timeline identity. */
    public String id() {
        return id;
    }

    /** Authenticated account identity associated with the Timeline. */
    public String accountId() {
        return accountId;
    }

    Object owner() {
        return owner;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TimelineHandle handle
                && owner == handle.owner
                && id.equals(handle.id)
                && accountId.equals(handle.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(owner), id, accountId);
    }

    @Override
    public String toString() {
        return id;
    }
}
