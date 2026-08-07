package blue.language.processor.model;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-runtime compatibility for published Language 3.1 checkpoint mapping.
 *
 * <p>The published reflective mapper writes {@code null} into an omitted or
 * empty map field after construction. The upstream model assumes its field
 * initializer survives mapping. These accessors preserve the model's stated
 * null-means-empty contract until the corrected Language artifact is pinned.</p>
 */
@TypeBlueId(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT)
public class ChannelEventCheckpoint extends MarkerContract {

    private Map<String, CheckpointEntry> entries =
            new LinkedHashMap<String, CheckpointEntry>();

    public ChannelEventCheckpoint() {
    }

    public Map<String, CheckpointEntry> getEntries() {
        Map<String, CheckpointEntry> current = entries != null
                ? entries
                : Collections.<String, CheckpointEntry>emptyMap();
        return Collections.unmodifiableMap(
                new LinkedHashMap<String, CheckpointEntry>(current));
    }

    public ChannelEventCheckpoint entries(
            Map<String, CheckpointEntry> replacement) {
        entries = new LinkedHashMap<String, CheckpointEntry>();
        if (replacement != null) {
            entries.putAll(replacement);
        }
        return this;
    }

    public CheckpointEntry entry(String rawChannelKey) {
        return entries != null ? entries.get(rawChannelKey) : null;
    }

    public ChannelEventCheckpoint putEntry(
            String rawChannelKey,
            String domainBlueId,
            String subjectBlueId) {
        if (rawChannelKey == null || rawChannelKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "Raw channel key must not be empty");
        }
        if (domainBlueId == null || domainBlueId.isEmpty()
                || subjectBlueId == null || subjectBlueId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Checkpoint domain and subject BlueIds must not be empty");
        }
        if (entries == null) {
            entries = new LinkedHashMap<String, CheckpointEntry>();
        }
        entries.put(
                rawChannelKey,
                new CheckpointEntry()
                        .domain(new Node().blueId(domainBlueId))
                        .subject(new Node().blueId(subjectBlueId)));
        return this;
    }

    public ChannelEventCheckpoint removeEntry(String rawChannelKey) {
        if (entries != null) {
            entries.remove(rawChannelKey);
        }
        return this;
    }
}
