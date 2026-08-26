package blue.coordination.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PersistentAppendLogTest {
    @Test
    void appendPreservesOrderAndSharesTheCompletePrefix() {
        // given
        PersistentAppendLog<Integer> empty = PersistentAppendLog.empty();
        PersistentAppendLog<Integer> first = empty.appendAll(List.of(1, 2));

        // when
        PersistentAppendLog<Integer> second = first.appendAll(List.of(3, 4));

        // then
        assertEquals(List.of(1, 2), first.values());
        assertEquals(List.of(1, 2, 3, 4), second.values());
        assertEquals(4, second.size());
        assertTrue(second.extendsLog(first));
        assertTrue(second.extendsLog(empty));
        assertFalse(first.extendsLog(second));
    }

    @Test
    void emptyAppendRetainsTheExactRoot() {
        // given
        PersistentAppendLog<String> log = PersistentAppendLog.of(
                List.of("retained"));

        // when
        PersistentAppendLog<String> retained = log.appendAll(List.of());

        // then
        assertSame(log, retained);
    }

    @Test
    void oneAppendAfterOneThousandEntriesDoesNotCopyThePrefix() {
        // given
        PersistentAppendLog<Integer> prefix = PersistentAppendLog.empty();
        for (int value = 0; value < 1_000; value++) {
            prefix = prefix.appendAll(List.of(value));
        }

        // when
        PersistentAppendLog<Integer> appended = prefix.appendAll(
                List.of(1_000));

        // then
        assertTrue(appended.extendsLog(prefix));
        assertEquals(1_001, appended.size());
        assertEquals(0, appended.values().get(0));
        assertEquals(1_000, appended.values().get(1_000));
    }
}
