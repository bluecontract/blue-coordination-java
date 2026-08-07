package blue.coordination.examples.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Atomically maintained bidirectional Timeline/document membership index. */
public final class MyOsTimelineDocumentIndex {

    private final Map<MyOsTimelineBinding, Set<MyOsDocumentIdentity>>
            documentsByTimeline = new LinkedHashMap<>();
    private final Map<MyOsDocumentIdentity, Set<MyOsTimelineBinding>>
            timelinesByDocument = new LinkedHashMap<>();
    private long publicationVersion;

    public synchronized void replaceDocumentBindings(
            MyOsDocumentIdentity document,
            Set<MyOsTimelineBinding> desired) {
        Map<MyOsDocumentIdentity, Set<MyOsTimelineBinding>> one =
                new LinkedHashMap<>();
        one.put(Objects.requireNonNull(document, "document"),
                Objects.requireNonNull(desired, "desired"));
        publish(prepareDocumentBindings(one));
    }

    /** Prepares only the forward/inverse rows touched by this replacement. */
    synchronized PreparedReplacement prepareDocumentBindings(
            Map<MyOsDocumentIdentity, Set<MyOsTimelineBinding>> desired) {
        Map<MyOsDocumentIdentity, Set<MyOsTimelineBinding>> checkedDesired =
                Objects.requireNonNull(desired, "desired");
        List<MyOsDocumentIdentity> documents = new ArrayList<>(
                checkedDesired.keySet());
        Collections.sort(documents);
        Map<MyOsDocumentIdentity, Set<MyOsTimelineBinding>>
                documentReplacements = new LinkedHashMap<>();
        Map<MyOsTimelineBinding, Set<MyOsDocumentIdentity>>
                timelineReplacements = new LinkedHashMap<>();

        for (MyOsDocumentIdentity document : documents) {
            MyOsDocumentIdentity checked = Objects.requireNonNull(
                    document, "document");
            Set<MyOsTimelineBinding> replacement = orderedTimelines(
                    Objects.requireNonNull(
                            checkedDesired.get(checked), "desired bindings"));
            Set<MyOsTimelineBinding> prior =
                    timelinesByDocument.getOrDefault(checked, Set.of());
            if (prior.equals(replacement)) {
                continue;
            }
            documentReplacements.put(
                    checked,
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(replacement)));
            Set<MyOsTimelineBinding> affected = new LinkedHashSet<>(prior);
            affected.addAll(replacement);
            for (MyOsTimelineBinding timeline : affected) {
                Set<MyOsDocumentIdentity> timelineDocuments =
                        timelineReplacements.get(timeline);
                if (timelineDocuments == null) {
                    timelineDocuments = new LinkedHashSet<>(
                            documentsByTimeline.getOrDefault(
                                    timeline, Set.of()));
                } else {
                    timelineDocuments = new LinkedHashSet<>(
                            timelineDocuments);
                }
                if (replacement.contains(timeline)) {
                    timelineDocuments.add(checked);
                } else {
                    timelineDocuments.remove(checked);
                }
                timelineReplacements.put(
                        timeline,
                        Collections.unmodifiableSet(timelineDocuments));
            }
        }
        return new PreparedReplacement(
                this,
                publicationVersion,
                documentReplacements.isEmpty()
                        ? publicationVersion
                        : Math.addExact(publicationVersion, 1L),
                documentReplacements,
                timelineReplacements);
    }

    synchronized void validate(PreparedReplacement replacement) {
        PreparedReplacement checked = Objects.requireNonNull(
                replacement, "replacement");
        if (checked.owner != this) {
            throw new IllegalArgumentException(
                    "Prepared route update belongs to another index");
        }
        if (checked.basePublicationVersion != publicationVersion) {
            throw new IllegalStateException("Prepared route update is stale");
        }
    }

    synchronized void publish(PreparedReplacement replacement) {
        validate(replacement);
        publishPreparedUnchecked(replacement);
    }

    synchronized void publishPreparedUnchecked(
            PreparedReplacement replacement) {
        for (Map.Entry<MyOsDocumentIdentity, Set<MyOsTimelineBinding>> entry
                : replacement.documentReplacements.entrySet()) {
            if (entry.getValue().isEmpty()) {
                timelinesByDocument.remove(entry.getKey());
            } else {
                timelinesByDocument.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<MyOsTimelineBinding, Set<MyOsDocumentIdentity>> entry
                : replacement.timelineReplacements.entrySet()) {
            if (entry.getValue().isEmpty()) {
                documentsByTimeline.remove(entry.getKey());
            } else {
                documentsByTimeline.put(entry.getKey(), entry.getValue());
            }
        }
        if (!replacement.documentReplacements.isEmpty()) {
            publicationVersion = replacement.resultingPublicationVersion;
        }
    }

    public synchronized Set<MyOsDocumentIdentity> documents(
            MyOsTimelineBinding timeline) {
        List<MyOsDocumentIdentity> ordered = new ArrayList<>(
                documentsByTimeline.getOrDefault(
                        Objects.requireNonNull(timeline), Set.of()));
        Collections.sort(ordered);
        return Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
    }

    public synchronized Set<MyOsTimelineBinding> timelines(
            MyOsDocumentIdentity document) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                orderedTimelines(timelinesByDocument.getOrDefault(
                        Objects.requireNonNull(document), Set.of()))));
    }

    public synchronized void verifySymmetry() {
        documentsByTimeline.forEach((timeline, documents) ->
                documents.forEach(document -> {
                    if (!timelinesByDocument.getOrDefault(
                            document, Set.of()).contains(timeline)) {
                        throw new IllegalStateException("Broken inverse index");
                    }
                }));
        timelinesByDocument.forEach((document, timelines) ->
                timelines.forEach(timeline -> {
                    if (!documentsByTimeline.getOrDefault(
                            timeline, Set.of()).contains(document)) {
                        throw new IllegalStateException("Broken forward index");
                    }
                }));
    }

    public synchronized MyOsTimelineDocumentIndex copy() {
        MyOsTimelineDocumentIndex result = new MyOsTimelineDocumentIndex();
        documentsByTimeline.forEach((timeline, documents) ->
                result.documentsByTimeline.put(
                        timeline, new LinkedHashSet<>(documents)));
        timelinesByDocument.forEach((document, timelines) ->
                result.timelinesByDocument.put(
                        document, new LinkedHashSet<>(timelines)));
        result.publicationVersion = publicationVersion;
        return result;
    }

    static final class PreparedReplacement {
        private final MyOsTimelineDocumentIndex owner;
        private final long basePublicationVersion;
        private final long resultingPublicationVersion;
        private final Map<MyOsDocumentIdentity, Set<MyOsTimelineBinding>>
                documentReplacements;
        private final Map<MyOsTimelineBinding, Set<MyOsDocumentIdentity>>
                timelineReplacements;

        private PreparedReplacement(
                MyOsTimelineDocumentIndex owner,
                long basePublicationVersion,
                long resultingPublicationVersion,
                Map<MyOsDocumentIdentity, Set<MyOsTimelineBinding>>
                        documentReplacements,
                Map<MyOsTimelineBinding, Set<MyOsDocumentIdentity>>
                        timelineReplacements) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.basePublicationVersion = basePublicationVersion;
            this.resultingPublicationVersion = resultingPublicationVersion;
            this.documentReplacements = Collections.unmodifiableMap(
                    new LinkedHashMap<>(documentReplacements));
            this.timelineReplacements = Collections.unmodifiableMap(
                    new LinkedHashMap<>(timelineReplacements));
        }
    }

    private static Set<MyOsTimelineBinding> orderedTimelines(
            Set<MyOsTimelineBinding> values) {
        List<MyOsTimelineBinding> ordered = new ArrayList<>(values);
        ordered.sort((left, right) -> {
            int timeline = blue.language.processor.ExternalOrderKey
                    .compareTextCodePoints(
                            left.timelineHeaderBlueId(),
                            right.timelineHeaderBlueId());
            return timeline != 0 ? timeline
                    : blue.language.processor.ExternalOrderKey
                    .compareTextCodePoints(
                            left.actorHeaderBlueId(),
                            right.actorHeaderBlueId());
        });
        return new LinkedHashSet<>(ordered);
    }

}
