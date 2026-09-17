package blue.coordination.sdk;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TimelineJournalPositionAuditTest {
    @Test void sdkPositionMatchesFullAuditWithoutChangingDocumentOrJournal() throws Exception {
        try (var f = new RootedSdkFixture()) {
            var document = f.start("historical-a.yaml", "rcp2/a", Map.of());
            var state = document.snapshot().blueId();
            f.timelines.put("other", f.blue.timelines().register("other", "alice"));
            var first = f.append(document, "rcp2/a", "tick", 900, "{}");
            var second = f.append(document, "other", "tick", 100, "{}");
            var position = f.blue.advanced().auditTimelinePosition("other");
            assertEquals(second.blueId(), position.head().orElseThrow().blueId());
            assertEquals(900, position.maximumTimestampMicros());
            assertEquals(2, position.journalRevision());
            assertEquals(f.blue.advanced().auditTimeline("other").get(0), position.head().orElseThrow());
            assertEquals(first.blueId(), f.blue.advanced().auditTimelinePosition("rcp2/a").head().orElseThrow().blueId());
            assertEquals(state, document.snapshot().blueId(), "Audit does not process either entry");
            assertThrows(IllegalArgumentException.class, () -> f.blue.advanced().auditTimelinePosition(" "));
        }
    }
}
