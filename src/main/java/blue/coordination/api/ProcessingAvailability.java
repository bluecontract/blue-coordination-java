package blue.coordination.api;

/**
 * Immutable host availability supplied only while auditing the next fair
 * processing lane.
 *
 * <p>Availability does not append, reserve, or make journal work drainable.
 * It lets a host ask whether an ordinary entry that it can admit now should
 * receive the next fair turn before managed fallback work.</p>
 */
public record ProcessingAvailability(boolean journalAdmissionAvailable) {

    /** Returns availability with the supplied ordinary-journal admission. */
    public static ProcessingAvailability of(
            boolean journalAdmissionAvailable) {
        return new ProcessingAvailability(journalAdmissionAvailable);
    }

    /** Returns availability with no host journal admission ready. */
    public static ProcessingAvailability none() {
        return new ProcessingAvailability(false);
    }
}
