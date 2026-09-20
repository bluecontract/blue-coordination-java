package blue.coordination.sdk;
import blue.coordination.sdk.BlueCoordination;
import java.nio.file.Files;
import java.nio.file.Path;
public class CaptureOperationMetadata {
    public static void main(String[] args) throws Exception {
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
                  type: Coordination/Operation Request
                  operation: credit
                  channel: owner
                  request: {}
                """);
            var entry = blue.events().from(timeline).exact(exact).submit();
            Files.write(Path.of(args[0]), blue.advanced().storageMetadata(32 * 1024 * 1024));
            System.out.println("entry=" + entry.blueId());
        }
    }
}
