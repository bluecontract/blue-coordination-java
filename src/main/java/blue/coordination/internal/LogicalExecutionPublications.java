package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Actual terminal execution receipts are selected by one observer instance; canonical first results remain intact. */
final class LogicalExecutionPublications {
    private static final Bytes SCOPE = new Bytes(OrderedRecordKey.text().encode("blue-coordination/execution-closure/1"));
    private record Execution(DocumentInstanceRef observer, String identity) { }
    private record Completed(ContractsClosurePublicationReceipt receipt,
            PersistentOrderedMap<DocumentId, DocumentSession> sessions) { }
    private final LogicalRecordContext context;
    private final LogicalDocumentInstances instances;
    private final LogicalPublicationInstances histories;
    private final StoredPublicationIndexes codecs;
    private final Map<Execution, Completed> pending = new LinkedHashMap<>();

    LogicalExecutionPublications(LogicalRecordContext context, LogicalDocumentInstances instances,
            LogicalPublicationInstances histories, StoredPublicationIndexes codecs) {
        this.context = context; this.instances = instances; this.histories = histories; this.codecs = codecs;
    }

    boolean contains(DocumentId observer, String identity) {
        return context.protect(() -> {
            var selected = selection(observer, identity);
            return pending.containsKey(selected) || context.read(key(selected)).present();
        });
    }

    Optional<ContractsClosurePublicationReceipt> get(DocumentId observer, String identity) {
        return context.protect(() -> get(instances.requireOrCreateInitial(observer), identity));
    }
    Optional<ContractsClosurePublicationReceipt> get(DocumentInstanceRef observer, String identity) {
        return context.protect(() -> {
            instances.requireRetained(observer);
            var selected = new Execution(observer, identity); var complete = pending.get(selected);
            if (complete != null) return Optional.of(complete.receipt()); // Authenticated by the completed store transaction.
            var value = context.read(key(selected));
            if (!value.present()) return Optional.empty();
            var receipt = codecs.decodeExecutionReceipt(value.content().copy());
            require(identity.equals(receipt.publicationIdentity()), "Execution receipt canonical identity differs");
            require(receipt.rootedTerminalEvidence() != null && receipt.rootedTerminalEvidence().entryOwners().contains(observer.documentId()),
                    "Execution receipt does not complete this observer");
            InMemoryDocumentStore.StoreState.requireRetainedClosureReceipt(receipt,
                    histories.openExecution(selected.observer(), identity, receipt.documentIds()), "Instance execution receipt");
            return Optional.of(receipt);
        });
    }

    List<blue.coordination.api.DocumentRevision> causal(DocumentInstanceRef observer, String identity, DocumentId member, String entry) {
        return context.protect(() -> {
            var receipt = get(observer, identity).orElseThrow(() -> new IllegalStateException("Source outcome lacks its original execution publication"));
            if (!receipt.documentIds().contains(member)) throw new IllegalArgumentException("Outcome member is not an original publication owner");
            var staged = pending.get(new Execution(observer, identity));
            var session = staged == null ? histories.openExecution(observer, identity, receipt.documentIds()).get(member)
                    : staged.sessions().get(member);
            return Objects.requireNonNull(session, "Original execution member is absent").causalRevisionEndpoints(entry);
        });
    }

    /** Called after complete result validation, before the nonthrowing state swap. */
    void completed(ContractsClosurePublicationReceipt receipt, InMemoryDocumentStore.StoreState replacement) {
        if (receipt == null || receipt.rootedTerminalEvidence() == null) return;
        context.protect(() -> {
            var sessions = PersistentOrderedMap.<DocumentId, DocumentSession>empty(EmbeddingBinding.DOCUMENT_ORDER);
            for (var owner : receipt.documentIds()) {
                var session = replacement.sessionIndex().get(owner);
                if (session != null) sessions = sessions.put(owner, session.copyForAtomicPublication()).map();
            }
            var completed = new Completed(receipt, sessions);
            var selections = receipt.rootedTerminalEvidence().entryOwners().stream()
                    .map(owner -> selection(owner, receipt.publicationIdentity())).toList();
            for (var selected : selections) {
                require(!pending.containsKey(selected) && !context.read(key(selected)).present(), "Repeated instance execution publication");
            }
            selections.forEach(selected -> pending.put(selected, completed));
            return null;
        });
    }

    void stage() {
        context.protect(() -> {
            for (var entry : pending.entrySet()) {
                var selected = entry.getKey(); var completed = entry.getValue();
                // Preparing the actual result also registers its exact outbox/checkpoint object identities.
                context.select(key(selected), new Bytes(codecs.encodeExecutionReceipt(completed.receipt())));
                histories.retainExecution(selected.observer(), selected.identity(), completed.receipt().documentIds(), completed.sessions());
            }
            return null;
        });
    }

    private Execution selection(DocumentId observer, String identity) {
        return new Execution(instances.requireOrCreateInitial(observer), Objects.requireNonNull(identity));
    }
    private static Key key(Execution selected) {
        return new Key(Family.CLOSURE, SCOPE, new Bytes(OrderedRecordKey.tuple(
                InstanceSourceKeys.INSTANCE.encode(selected.observer()), OrderedRecordKey.text().encode(selected.identity()))));
    }
}
