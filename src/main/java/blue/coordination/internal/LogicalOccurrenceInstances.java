package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import java.util.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Native execution association of a canonical retained occurrence; it never edits processor payloads. */
final class LogicalOccurrenceInstances {
    private static final String FORMAT = "blue-coordination/occurrence-instances/1";
    private static final Bytes SCOPE = new Bytes(OrderedRecordKey.text().encode(FORMAT));
    private final LogicalRecordContext context;
    private final LogicalDocumentInstances instances;
    private final int maximumBytes;
    private final Map<Key, Bytes> pending = new LinkedHashMap<>();

    record Target(boolean known, Optional<DocumentInstanceRef> instance) { }

    LogicalOccurrenceInstances(LogicalRecordContext context, LogicalDocumentInstances instances, int maximumBytes) {
        this.context = context; this.instances = instances; this.maximumBytes = maximumBytes;
    }

    void capture(DocumentInstanceRef observer, DocumentSession session) {
        instances.requireActive(observer);
        for (var occurrence : session.rootedView().snapshot().occurrences()) {
            var key = key(observer, occurrence);
            var prior = read(key);
            if (prior != null) { decode(observer, occurrence, prior, true); continue; }
            var target = DocumentId.of(occurrence.targetDocumentId().value());
            var generationKey = generationKey(observer, occurrence);
            var generation = read(generationKey);
            var selected = generation == null ? instances.select(target).instance()
                    : decode(observer, occurrence, generation, false);
            if (generation == null && selected.isPresent() && !selected.get().equals(LogicalDocumentInstances.initialReference(target)))
                throw new ContractsClosureAdapter.ProjectionUnavailableException(
                        "EXPLICIT_REPLACEMENT_TARGET_SELECTION_REQUIRED observer=" + observer + " occurrence="
                                + occurrence.occurrenceIdentity() + " binding=" + occurrence.bindingIdentity() + " target=" + selected.get());
            pending.put(key, encode(observer, occurrence, selected, true));
            if (generation == null) pending.put(generationKey, encode(observer, occurrence, selected, false));
        }
    }

    List<String> startingLiveRoleBlocks(DocumentInstanceRef original, DocumentSession basis) {
        var blocks = new ArrayList<String>();
        for (var row : basis.rootedView().snapshot().occurrences()) {
            if (!row.active()) continue;
            var role = target(original, row);
            require(role.known(), "Starting basis has no original occurrence association");
            var target = DocumentId.of(row.targetDocumentId().value());
            var current = instances.select(target).instance();
            if (!current.equals(role.instance())) blocks.add("STARTING_LIVE_SOURCE_ROLE source=" + target
                    + " retained=" + role.instance() + " current=" + current + " occurrence=" + row.occurrenceIdentity());
        }
        return List.copyOf(blocks);
    }

    void inheritStartingRoles(DocumentInstanceRef next, DocumentInstanceRef original, DocumentSession basis) {
        instances.requireActive(next); instances.requireRetained(original);
        for (var row : basis.rootedView().snapshot().occurrences()) {
            var role = target(original, row);
            require(role.known(), "Starting basis has no original occurrence association");
            pending.put(key(next, row), encode(next, row, role.instance(), true));
            pending.put(generationKey(next, row), encode(next, row, role.instance(), false));
        }
    }

    Target target(DocumentInstanceRef observer, ManagedOccurrenceBinding occurrence) {
        return context.protect(() -> {
            instances.requireRetained(observer);
            var value = read(key(observer, occurrence));
            return value == null ? new Target(false, Optional.empty()) : new Target(true, decode(observer, occurrence, value, true));
        });
    }

    private Bytes encode(DocumentInstanceRef observer, ManagedOccurrenceBinding occurrence,
            Optional<DocumentInstanceRef> selected, boolean binding) {
        return new Bytes(SessionStorageWire.encode(maximumBytes, writer -> {
            writer.text(FORMAT); writer.text(observer.documentId().value()); writer.text(observer.instanceId());
            writer.text(occurrence.occurrenceIdentity()); writer.bool(binding);
            writer.text(binding ? occurrence.bindingIdentity() : Long.toString(occurrence.activationGeneration()));
            writer.text(occurrence.sourceDocumentId().value()); writer.text(occurrence.sourcePath());
            writer.text(occurrence.targetDocumentId().value());
            writer.bool(selected.isPresent()); if (selected.isPresent()) writer.text(selected.get().instanceId());
        }));
    }

    private Optional<DocumentInstanceRef> decode(DocumentInstanceRef observer, ManagedOccurrenceBinding occurrence, Bytes bytes, boolean binding) {
        return SessionStorageWire.decode(bytes.copy(), maximumBytes, reader -> {
            require(FORMAT.equals(reader.text(reader.remaining())) && observer.documentId().value().equals(reader.text(reader.remaining()))
                    && observer.instanceId().equals(reader.text(reader.remaining()))
                    && occurrence.occurrenceIdentity().equals(reader.text(reader.remaining()))
                    && binding == reader.bool()
                    && (binding ? occurrence.bindingIdentity() : Long.toString(occurrence.activationGeneration())).equals(reader.text(reader.remaining()))
                    && occurrence.sourceDocumentId().value().equals(reader.text(reader.remaining()))
                    && occurrence.sourcePath().equals(reader.text(reader.remaining()))
                    && occurrence.targetDocumentId().value().equals(reader.text(reader.remaining())), "Occurrence instance association differs");
            // A role captured before local hosting can resolve only the first canonical source execution.
            // This never follows the active binding and cannot select a later replacement.
            if (!reader.bool()) return instances.retainedInitial(DocumentId.of(occurrence.targetDocumentId().value()));
            var target = new DocumentInstanceRef(DocumentId.of(occurrence.targetDocumentId().value()), reader.text(reader.remaining()));
            instances.requireRetained(target); return Optional.of(target);
        });
    }

    private Bytes read(Key key) { return pending.containsKey(key) ? pending.get(key) : context.read(key).content(); }
    void stage() { pending.forEach(context::select); }
    private static Key generationKey(DocumentInstanceRef observer, ManagedOccurrenceBinding occurrence) {
        return new Key(Family.INSTANCE_OCCURRENCE, new Bytes(OrderedRecordKey.tuple(SCOPE.copy(), new byte[] {1})),
                new Bytes(OrderedRecordKey.tuple(InstanceSourceKeys.INSTANCE.encode(observer),
                        OrderedRecordKey.text().encode(occurrence.occurrenceIdentity()),
                        OrderedRecordKey.signedLong().encode(occurrence.activationGeneration()))));
    }
    private static Key key(DocumentInstanceRef observer, ManagedOccurrenceBinding occurrence) {
        return new Key(Family.INSTANCE_OCCURRENCE, SCOPE, new Bytes(OrderedRecordKey.tuple(InstanceSourceKeys.INSTANCE.encode(observer),
                OrderedRecordKey.text().encode(occurrence.occurrenceIdentity()), OrderedRecordKey.text().encode(occurrence.bindingIdentity()))));
    }
}
