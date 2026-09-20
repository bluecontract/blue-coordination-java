package blue.coordination.sdk;

import blue.coordination.api.storage.CoordinationObjectStorageException;
import java.util.Arrays;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Generic SDK audit and explicit new-format-only storage through real accepted entries. */
final class GeneralTimelineAuditStorageTest {
    private static final int MAX = 32 * 1024 * 1024;

    @Test void preservesGeneralAuditWithoutTreatingBusinessRequestAsOperationMetadata() {
        // given
        try (var blue = BlueCoordination.inMemory()) {
            var timeline = blue.timelines().register("general-entry", "alice");
            var exact = blue.values().yaml("""
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: general-entry
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
                timestamp: 1800000000000000
                message:
                  kind: CreditRecorded
                  amount: 7
                  request: {}
                """);
            // when
            var accepted = blue.events().from(timeline).exact(exact).submit();
            var audit = blue.advanced().auditTimelineEntry(accepted.blueId()).orElseThrow();
            byte[] bytes = blue.advanced().storageMetadata(MAX);
            var codec = new SdkStorageCodec(new Object(), MAX);
            var reopened = codec.decode(bytes, SdkStorageCodec.Metadata.class);
            // then
            assertEquals(exact.blueId(), audit.exact().blueId());
            assertTrue(audit.operationDetails().isEmpty());
            assertTrue(audit.request().isEmpty());
            assertThrows(NoSuchElementException.class, audit::operation);
            assertThrows(NoSuchElementException.class, audit::channel);
            var restored = reopened.entries().get(accepted.blueId());
            assertEquals(exact.blueId(), restored.exactEvent().blueId());
            assertTrue(restored.operationDetails().isEmpty());
            assertEquals(1, restored.globalSequence());
            assertEquals(1, restored.timelineSequence());
            assertArrayEquals(bytes, codec.encode(reopened));
        }
    }

    @Test void rejectsPrecedingOperationMetadataWithoutMigration() throws Exception {
        // given: fixture emitted by merged bfcc821's unmodified candidate JAR.
        byte[] previous;
        try (var resource = getClass().getResourceAsStream("/general-entry/sdk-operation-v1.bin")) {
            previous = java.util.Objects.requireNonNull(resource).readAllBytes();
        }
        var codec = new SdkStorageCodec(new Object(), MAX);
        // when / then
        assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(previous, SdkStorageCodec.Metadata.class));
        assertThrows(CoordinationObjectStorageException.class,
                () -> codec.decode(Arrays.copyOf(previous, previous.length - 1), SdkStorageCodec.Metadata.class));
    }
}
