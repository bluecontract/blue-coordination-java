package blue.coordination.internal;

import blue.coordination.api.*;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GeneralTimelineHistoricalEvidenceTest {
    @Test void oneGeneralHistoryItemIsNotACompletenessCertificateAndUnavailableEvidenceNeverBecomesEmpty() {
        // given
        var metrics = new EngineMetrics(); var objects = new WholeObjectStore(metrics);
        try (var runtime = BlueRuntime.create(objects)) {
            var journal = new DefaultTimelineJournal(new WholeRequestEntryFactory(runtime, objects, metrics,
                    ignored -> "MyOS/Principal Actor", true), metrics, new InMemoryTimelineJournalStore());
            var timeline = new Timeline("general-entry", "alice");
            var exact = GeneralTimelineEntryMother.event(new Node().properties("kind", new Node().value("CreditRecorded")))
                    .properties("timestamp", new Node().value(400L));
            var general = journal.appendExact(timeline, ExactValue.verified(exact));
            var operation = journal.append(timeline, Operation.withoutRequest("tick", "owner"), 700L);
            var cutoff = ExternalOrderKey.of(List.of(BigInteger.valueOf(1000), "upper"));
            // when
            var first = assertInstanceOf(HistoricalStep.EligibleEntry.class,
                    journal.nextHistoricalStep(null, cutoff, null, ignored -> true, 1, 1, () -> "surface"));
            // then
            assertEquals(general.blueId(), first.entry().blueId());
            assertTrue(first.entry().operationDetails().isEmpty());
            journal.makeHistoricalUnavailable("remaining page unavailable");
            assertInstanceOf(HistoricalStep.Unavailable.class,
                    journal.nextHistoricalStep(first.nextExclusive(), cutoff, null, ignored -> true, 1, 1, () -> "surface"));
            journal.invalidateHistoricalEvidence("remaining evidence corrupt");
            assertInstanceOf(HistoricalStep.InvalidEvidence.class,
                    journal.nextHistoricalStep(first.nextExclusive(), cutoff, null, ignored -> true, 1, 1, () -> "surface"));
            journal.makeHistoricalAvailable();
            var second = assertInstanceOf(HistoricalStep.EligibleEntry.class,
                    journal.nextHistoricalStep(first.nextExclusive(), cutoff, null, ignored -> true, 1, 1, () -> "surface"));
            assertEquals(operation.blueId(), second.entry().blueId());
            assertTrue(second.entry().operationDetails().isPresent());
            assertInstanceOf(HistoricalStep.CompleteEmpty.class,
                    journal.nextHistoricalStep(second.nextExclusive(), cutoff, null, ignored -> true, 1, 1, () -> "surface"));
        }
    }
}
