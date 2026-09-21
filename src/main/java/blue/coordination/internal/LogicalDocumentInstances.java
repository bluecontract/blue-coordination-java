package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecordAttempt;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.Objects;
import java.util.Optional;
import static blue.coordination.internal.SessionStorageWire.*;

/**
 * Native per-document authority ledger in one account address. This component
 * does not assess topology or authorize retirement; its caller must first add
 * the complete lifecycle/dependency conditions to the same attempt.
 */
final class LogicalDocumentInstances {
    private static final String FORMAT = "blue-coordination/document-instance/1";
    private static final Bytes SCOPE = new Bytes(OrderedRecordKey.text().encode(FORMAT));
    private final CoordinationRecordAttempt attempt;
    private final int maximumRecordBytes;

    LogicalDocumentInstances(CoordinationRecordAttempt attempt, int maximumRecordBytes) {
        this.attempt = Objects.requireNonNull(attempt);
        if (maximumRecordBytes < 1) throw new IllegalArgumentException("Nonpositive instance record bound");
        this.maximumRecordBytes = maximumRecordBytes;
    }

    /** Exact active or absent/tombstoned selection; no account-wide generation is read. */
    Binding select(DocumentId document) {
        Objects.requireNonNull(document);
        Value value = attempt.read(bindingKey(document));
        if (!value.present()) return new Binding(document, value.revision(), Optional.empty());
        DocumentInstanceRef reference = decodeReference(value.content());
        require(document.equals(reference.documentId()), "Instance binding has another semantic document");
        requireRetained(reference);
        return new Binding(document, value.revision(), Optional.of(reference));
    }

    /** Initial execution only; a tombstoned binding always requires explicit lifecycle admission. */
    DocumentInstanceRef requireOrCreateInitial(DocumentId document) {
        var selected = select(document);
        if (selected.instance().isPresent()) return selected.instance().orElseThrow();
        if (selected.generation() != 0) throw new IllegalStateException("Retired document requires explicit instance admission");
        return start(initialReference(document)).instance().orElseThrow();
    }

    static DocumentInstanceRef initialReference(DocumentId document) {
        return new DocumentInstanceRef(document, "initial/" + blue.coordination.api.storage.CoordinationRecords.sha256(
                new Bytes(OrderedRecordKey.document().encode(document))).hex());
    }

    /** A never-hosted canonical source may later acquire its first execution, never a replacement. */
    Optional<DocumentInstanceRef> retainedInitial(DocumentId document) {
        var expected = initialReference(document);
        var value = attempt.read(identityKey(expected.instanceId()));
        if (!value.present()) {
            require(value.revision() == 0, "Initial instance association was deleted");
            return Optional.empty();
        }
        require(expected.equals(decodeReference(value.content())), "Initial source instance has another association");
        return Optional.of(expected);
    }

    /** Captures this exact active instance condition for every selected mutable owner. */
    Binding requireActive(DocumentInstanceRef expected) {
        Objects.requireNonNull(expected);
        Binding selected = select(expected.documentId());
        if (!selected.instance().equals(Optional.of(expected)))
            throw new IllegalStateException("Execution instance is no longer active: " + expected);
        return selected;
    }

    /**
     * Authenticates retained association without reading the mutable active binding.
     * Retained history readers must not conflict merely because another instance starts.
     */
    void requireRetained(DocumentInstanceRef expected) {
        Objects.requireNonNull(expected);
        Value value = attempt.read(identityKey(expected.instanceId()));
        require(value.present() && expected.equals(decodeReference(value.content())),
                "Unknown or incorrectly associated execution instance");
    }

    /** Called only after native admission has established the supported semantic history basis. */
    Binding start(DocumentInstanceRef reference) {
        Objects.requireNonNull(reference);
        Bytes encoded = encodeReference(reference);
        Binding prior = select(reference.documentId());
        if (prior.instance().isPresent()) throw new IllegalStateException("Document already has an active instance");
        Key identity = identityKey(reference.instanceId());
        // Revision, not presence, prevents reuse even if a damaged writer deleted this row.
        if (attempt.read(identity).revision() != 0)
            throw new IllegalStateException("Execution instance identity has already been used");
        if (prior.generation() == Long.MAX_VALUE)
            throw new IllegalStateException("Execution instance binding generation is exhausted");
        attempt.put(identity, encoded);
        attempt.put(bindingKey(reference.documentId()), encoded);
        return select(reference.documentId());
    }

    /** Called only after a complete native eligibility assessment, on its exact selected binding. */
    void retire(Binding expected) {
        Objects.requireNonNull(expected);
        if (expected.instance().isEmpty()) throw new IllegalArgumentException("Cannot retire an absent binding");
        Binding actual = requireActive(expected.instance().orElseThrow());
        if (!actual.equals(expected)) throw new IllegalStateException("Execution instance binding generation changed");
        if (actual.generation() == Long.MAX_VALUE)
            throw new IllegalStateException("Execution instance binding generation is exhausted");
        attempt.put(retirementKey(expected.instance().orElseThrow()), encodeReference(expected.instance().orElseThrow()));
        attempt.delete(bindingKey(expected.document()));
    }

    /** Original-instance lifecycle selection never follows a replacement's mutable binding. */
    boolean retired(DocumentInstanceRef reference) {
        requireRetained(reference);
        var value = attempt.read(retirementKey(reference));
        if (!value.present()) { require(value.revision() == 0, "Original retirement marker was deleted"); return false; }
        require(reference.equals(decodeReference(value.content())), "Retirement marker belongs to another instance");
        return true;
    }
    /** Aggregate legacy result maps cannot reconcile several execution instances of one canonical entry. */
    void requireGlobalDrainSupported() {
        var range = new Range(Family.INSTANCE_IDENTITY,
                new Bytes(OrderedRecordKey.text().encode(FORMAT + "/retired")), null, null);
        if (attempt.first(range).isPresent()) throw new blue.coordination.api.CoordinationException(
                blue.coordination.api.CoordinationErrorCode.PROCESSING_SELECTION_MISMATCH,
                "INSTANCE_SCOPED_PROCESSING_REQUIRED: aggregate drain is unavailable after native retirement; use rooted stages and instance-bound source requests");
    }
    private static Key retirementKey(DocumentInstanceRef reference) {
        return new Key(Family.INSTANCE_IDENTITY, new Bytes(OrderedRecordKey.text().encode(FORMAT + "/retired")),
                new Bytes(OrderedRecordKey.text().encode(reference.instanceId())));
    }

    private Bytes encodeReference(DocumentInstanceRef reference) {
        return new Bytes(encode(maximumRecordBytes, writer -> {
            writer.text(FORMAT); writer.text(reference.documentId().value()); writer.text(reference.instanceId());
        }));
    }

    private DocumentInstanceRef decodeReference(Bytes bytes) {
        return decode(bytes.copy(), maximumRecordBytes, reader -> {
            require(FORMAT.equals(reader.text(reader.remaining())), "Unknown execution instance record format");
            return new DocumentInstanceRef(DocumentId.of(reader.text(reader.remaining())), reader.text(reader.remaining()));
        });
    }

    private static Key bindingKey(DocumentId document) {
        return new Key(Family.INSTANCE_BINDING, SCOPE, new Bytes(OrderedRecordKey.document().encode(document)));
    }
    private static Key identityKey(String instance) {
        return new Key(Family.INSTANCE_IDENTITY, SCOPE, new Bytes(OrderedRecordKey.text().encode(instance)));
    }

    record Binding(DocumentId document, long generation, Optional<DocumentInstanceRef> instance) {
        Binding {
            Objects.requireNonNull(document); Objects.requireNonNull(instance);
            if (generation < 0 || instance.filter(ref -> !ref.documentId().equals(document)).isPresent()
                    || generation == 0 && instance.isPresent()) throw new IllegalArgumentException("Invalid instance binding");
        }
    }
}
