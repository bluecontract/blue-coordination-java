package blue.coordination.basic.engine;

import java.util.Objects;

/** Persistable deterministic plan for one newly linked occurrence. */
public final class CatchUpPlan {
    public enum Status {
        PENDING_INITIALIZATION,
        REPLAYING,
        COMPLETE,
        BLOCKED
    }

    private final String planId;
    private final EmbeddedLink link;
    private final EnvironmentFrontier cutoff;
    private Status status;
    private long nextChildEpoch;
    private String diagnostic;

    public CatchUpPlan(String planId, EmbeddedLink link) {
        this.planId = requireText(planId, "planId");
        this.link = Objects.requireNonNull(link, "link");
        this.cutoff = link.cutoff();
        this.status = Status.PENDING_INITIALIZATION;
        this.nextChildEpoch = link.appliedChildEpoch() + 1L;
    }

    public String planId() {
        return planId;
    }

    public EmbeddedLink link() {
        return link;
    }

    public EnvironmentFrontier cutoff() {
        return cutoff;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized long nextChildEpoch() {
        return nextChildEpoch;
    }

    public synchronized String diagnostic() {
        return diagnostic;
    }

    public synchronized void beginReplay() {
        if (status != Status.PENDING_INITIALIZATION
                && status != Status.REPLAYING) {
            throw new IllegalStateException("Cannot begin replay from " + status);
        }
        status = Status.REPLAYING;
    }

    public synchronized void markApplied(long childEpoch) {
        if (childEpoch != nextChildEpoch) {
            throw new IllegalStateException(
                    "Catch-up cursor expected child epoch " + nextChildEpoch
                            + " but received " + childEpoch);
        }
        link.markApplied(childEpoch);
        nextChildEpoch = Math.addExact(nextChildEpoch, 1L);
    }

    public synchronized void complete() {
        if (status == Status.BLOCKED) {
            throw new IllegalStateException("Blocked plan cannot complete");
        }
        status = Status.COMPLETE;
    }

    public synchronized void block(String reason) {
        diagnostic = requireText(reason, "reason");
        status = Status.BLOCKED;
    }

    synchronized CatchUpPlan copyWith(EmbeddedLink replacementLink) {
        CatchUpPlan copy = new CatchUpPlan(planId, replacementLink);
        copy.status = status;
        copy.nextChildEpoch = nextChildEpoch;
        copy.diagnostic = diagnostic;
        return copy;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
