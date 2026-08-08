package blue.coordination.examples.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that first-seen performance campaigns cannot reuse an event. */
final class FirstSeenEventGuardTest {

    @Test
    void shouldRejectDuplicatesWithoutInflatingTheExactCount() {
        // given
        FirstSeenEventGuard guard = new FirstSeenEventGuard();
        guard.requireFirstSeen("event-1");
        guard.requireFirstSeen("event-2");
        guard.requireFirstSeen("event-3");

        // when
        IllegalStateException duplicate = assertThrows(
                IllegalStateException.class,
                () -> guard.requireFirstSeen("event-2"));

        // then
        assertTrue(duplicate.getMessage().contains("event-2"));
        assertEquals(3, guard.observedCount());
    }
}
