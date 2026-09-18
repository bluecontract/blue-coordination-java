package blue.coordination.internal;

/** Compatibility facade over the existing journal machine's default store. */
final class InMemoryTimelineJournal extends DefaultTimelineJournal {
    InMemoryTimelineJournal(WholeRequestEntryFactory entryFactory, EngineMetrics metrics) {
        super(entryFactory, metrics, new InMemoryTimelineJournalStore());
    }

    InMemoryTimelineJournal(WholeRequestEntryFactory entryFactory, EngineMetrics metrics,
            HistoricalAvailabilityControl historicalAvailability) {
        super(entryFactory, metrics, new InMemoryTimelineJournalStore(historicalAvailability));
    }

    InMemoryTimelineJournal(WholeRequestEntryFactory entryFactory, EngineMetrics metrics,
            blue.coordination.api.TimelineJournalStore store) {
        super(entryFactory, metrics, store);
    }
}
