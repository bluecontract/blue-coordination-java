package blue.coordination.internal;

import java.lang.reflect.Field;
import java.util.List;

/** Test-only replacement of an append-only image, including its epoch range index. */
final class RepresentationRowCorruption implements AutoCloseable {
    private final DocumentSession session;
    private final Field rows = DocumentSession.class.getDeclaredField("componentRepresentationTransitions");
    private final Field ranges = DocumentSession.class.getDeclaredField("representationRanges");
    private final Object originalRows;
    private final Object originalRanges;

    RepresentationRowCorruption(DocumentSession session) throws ReflectiveOperationException {
        this.session = session;
        rows.setAccessible(true);
        ranges.setAccessible(true);
        originalRows = rows.get(session);
        originalRanges = ranges.get(session);
    }

    void replace(List<DocumentSession.ComponentRepresentationTransition> replacement) throws IllegalAccessException {
        var index = PersistentOrderedMap.<Long, DocumentSession.EpochRange>empty(Long::compare);
        for (int i = 0; i < replacement.size(); i++) {
            long epoch = replacement.get(i).epoch();
            var prior = index.get(epoch);
            index = index.put(epoch, new DocumentSession.EpochRange(prior == null ? i : prior.first(), i + 1L)).map();
        }
        // Keep the lookup index consistent so the semantic authority check, not
        // an unsupported List.remove or a stale range bound, rejects the image.
        rows.set(session, SessionHistoryList.copyOf(replacement));
        ranges.set(session, index);
    }

    @Override public void close() throws IllegalAccessException {
        rows.set(session, originalRows);
        ranges.set(session, originalRanges);
    }
}
