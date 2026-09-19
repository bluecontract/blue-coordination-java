package blue.coordination.internal;

import blue.coordination.api.*;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/** Selected original-row checks are below the existing index APIs, including their snapshot readers. */
final class StoredDocumentReadChecks implements AutoCloseable {
    private final StoredIndexProjections maps;
    private final InMemoryDocumentStore.StoreState original;
    private final StoredOccurrenceIndexes occurrences;
    private final StoredTopologyIndexes topology;
    private final StoredComponentStateIndexes components;
    private final StoredSubscriptionIndexes subscriptions;
    private final StoredManagedEpochIndexes receipts;
    private final StoredCatchUpPlanIndexes plans;
    private final StoredCatchUpWorkIndexes works;
    private final Function<DocumentId, StoredDocumentIndexes.SelectedDocument> selected;
    private final boolean controlledNamespace;
    private boolean closed;

    StoredDocumentReadChecks(int maximumMaps, InMemoryDocumentStore.StoreState original,
            StoredOccurrenceIndexes occurrences, StoredTopologyIndexes topology, StoredComponentStateIndexes components,
            StoredSubscriptionIndexes subscriptions, StoredManagedEpochIndexes receipts, StoredCatchUpPlanIndexes plans,
            StoredCatchUpWorkIndexes works, Function<DocumentId, StoredDocumentIndexes.SelectedDocument> selected) {
        this(maximumMaps, original, occurrences, topology, components, subscriptions, receipts, plans, works, selected, false);
    }

    StoredDocumentReadChecks(int maximumMaps, InMemoryDocumentStore.StoreState original,
            StoredOccurrenceIndexes occurrences, StoredTopologyIndexes topology, StoredComponentStateIndexes components,
            StoredSubscriptionIndexes subscriptions, StoredManagedEpochIndexes receipts, StoredCatchUpPlanIndexes plans,
            StoredCatchUpWorkIndexes works, Function<DocumentId, StoredDocumentIndexes.SelectedDocument> selected,
            boolean controlledNamespace) {
        maps = new StoredIndexProjections(maximumMaps); this.original = original; this.occurrences = occurrences;
        this.topology = topology; this.components = components; this.subscriptions = subscriptions; this.receipts = receipts;
        this.plans = plans; this.works = works; this.selected = selected; this.controlledNamespace = controlledNamespace;
    }

    private <K, V> PersistentOrderedMap<K, V> checked(String family, PersistentOrderedMap<K, V> source,
            BiFunction<K, V, V> reader) { return maps.open(family, "root", source, reader, (k, v) -> v); }
    private <K, V> PersistentOrderedMap<K, V> checked(String family, PersistentOrderedMap<K, V> source,
            BiFunction<K, V, V> reader, BiFunction<K, V, V> writer) {
        return maps.open(family, "root", source, reader, writer);
    }

    InMemoryDocumentStore.StoreState open() { return transform(original, false); }
    InMemoryDocumentStore.StoreState stage(InMemoryDocumentStore.StoreState working) { return transform(working, true); }
    private InMemoryDocumentStore.StoreState transform(InMemoryDocumentStore.StoreState s, boolean stage) {
        return InMemoryDocumentStore.StoreState.trustedTransition(s.sessionIndex(), lineage(s.lineageIndex(), stage),
                occurrence(s.occurrenceInventory(), stage), s.occurrenceInventoryGeneration(), topology(s.componentIndex(), stage),
                s.componentIndexGeneration(), generations(s.graphGenerations(), stage), component(s.componentStateInventory(), stage),
                subscription(s.closureSubscriptions(), stage), s.outboxLog(), s.checkpointEvidenceLog(),
                s.publicationReceiptIndex(), s.admissionReceiptIndex(), s.closurePublicationReceiptIndex(),
                s.rootedProviderFrontiers(),
                s.closureApplicationResults(),
                receipt(s.managedEpochReceipts(), stage), catchUp(s.catchUpPlans(), stage));
    }

    private ManagedLineageIndex lineage(ManagedLineageIndex value, boolean stage) {
        var s = value.storedState(); var raw = original.lineageIndex();
        var primary = stage ? maps.stage(s.documents()) : checked("lineage/documents", s.documents(), (id, row) -> {
            require(id.equals(row.documentId()), "Lineage key differs from its exact owner");
            StoredDocumentIndexes.requireLineageMembership(raw, row, controlledNamespace); return row;
        });
        return ManagedLineageIndex.restoreStored(new ManagedLineageIndex.StoredState(primary,
                lineageBuckets("authored", s.authored(), stage, ManagedLineageIndex.Lineage::authoredInitialBlueId),
                lineageBuckets("initialized", s.initialized(), stage, ManagedLineageIndex.Lineage::initializedBlueId),
                stage ? maps.stage(s.retained()) : checked("lineage/retained", s.retained(), (blueId, bucket) ->
                        maps.open("lineage/retained-bucket", blueId, bucket, (key, row) -> {
                            var owner = raw.byDocumentId(row.documentId());
                            require(key.equals(new ManagedLineageIndex.RetainedKey(row.documentId(), row.epoch()))
                                    && blueId.equals(row.blueId()) && owner != null && row.epoch() < owner.retainedStates().size()
                                    && row.equals(owner.retainedStates().get(Math.toIntExact(row.epoch()))), "Retained lineage membership differs");
                            return row;
                        }, (k, v) -> v), (k, bucket) -> maps.stage(bucket)),
                lineageBuckets("current", s.current(), stage, ManagedLineageIndex.Lineage::currentBlueId), s.copiedNodes()));
    }
    private PersistentOrderedMap<String, PersistentOrderedMap<DocumentId, ManagedLineageIndex.Lineage>> lineageBuckets(
            String kind, PersistentOrderedMap<String, PersistentOrderedMap<DocumentId, ManagedLineageIndex.Lineage>> source,
            boolean stage, Function<ManagedLineageIndex.Lineage, String> identity) {
        if (stage) return maps.stage(source);
        return checked("lineage/" + kind, source, (blueId, bucket) -> maps.open("lineage/" + kind + "-bucket", blueId, bucket, (id, row) -> {
            require(id.equals(row.documentId()) && blueId.equals(identity.apply(row))
                    && StoredDocumentIndexes.sameLineage(row, original.lineageIndex().byDocumentId(id), controlledNamespace),
                    "Lineage reverse bucket differs from primary");
            return row;
        }, (k, v) -> v), (k, bucket) -> maps.stage(bucket));
    }
    private ClosureGraphGenerationInventory generations(ClosureGraphGenerationInventory value, boolean stage) {
        var s = value.storedState();
        return ClosureGraphGenerationInventory.restoreStored(new ClosureGraphGenerationInventory.StoredState(
                stage ? maps.stage(s.generations()) : checked("graph/generation", s.generations(), (id, generation) -> {
                    require(selected.apply(id).retainedGraphGeneration() == generation, "Selected graph generation differs"); return generation;
                }), s.comparisons(), s.copiedNodes()));
    }

    private ManagedOccurrenceInventory occurrence(ManagedOccurrenceInventory value, boolean stage) {
        var s = value.storedIndexes(); var raw = original.occurrenceInventory();
        var paths = stage ? maps.stage(s.paths()) : checked("occurrence/path", s.paths(), (key, row) -> {
            require(key.equals(occurrenceKey(row)), "Occurrence primary key differs"); occurrences.verify(raw, row); return row;
        });
        var ordered = stage ? maps.stage(s.ordered()) : checked("occurrence/ordered", s.ordered(), (key, row) -> {
            require(key.equals(ManagedOccurrenceInventory.RowOrderKey.from(row)), "Occurrence ordered key differs"); occurrences.verify(raw, row); return row;
        });
        var active = stage ? maps.stage(s.active()) : checked("occurrence/active", s.active(), (key, row) -> {
            require(row.active() && key.equals(ManagedOccurrenceInventory.RowOrderKey.from(row)), "Active occurrence key differs"); occurrences.verify(raw, row); return row;
        });
        return ManagedOccurrenceInventory.restoreIndexes(new ManagedOccurrenceInventory.StoredIndexes(paths, ordered, active,
                stage ? maps.stage(s.occurrenceKeys()) : checked("occurrence/id", s.occurrenceKeys(), (id, key) -> {
                    var row = raw.storedIndexes().paths().get(key);
                    require(row != null && id.equals(row.occurrenceIdentity()), "Occurrence identity selects another row"); occurrences.verify(raw, row); return key;
                }), stage ? maps.stage(s.bindingKeys()) : checked("occurrence/binding", s.bindingKeys(), (id, key) -> {
                    var row = raw.storedIndexes().paths().get(key);
                    require(row != null && id.equals(row.bindingIdentity()), "Occurrence binding selects another row"); occurrences.verify(raw, row); return key;
                }), occurrenceBuckets("document", s.documents(), stage), occurrenceBuckets("source", s.sources(), stage),
                occurrenceBuckets("active-source", s.activeSources(), stage)));
    }
    private PersistentOrderedMap<DocumentId, PersistentOrderedMap<ManagedOccurrenceInventory.RowOrderKey, ManagedOccurrenceBinding>> occurrenceBuckets(
            String kind, PersistentOrderedMap<DocumentId, PersistentOrderedMap<ManagedOccurrenceInventory.RowOrderKey, ManagedOccurrenceBinding>> source, boolean stage) {
        if (stage) return maps.stage(source);
        return checked("occurrence/" + kind, source, (id, bucket) -> maps.open("occurrence/" + kind + "-bucket", id, bucket, (key, row) -> {
            require(key.equals(ManagedOccurrenceInventory.RowOrderKey.from(row))
                    && (id.value().equals(row.sourceDocumentId().value()) || kind.equals("document") && id.value().equals(row.targetDocumentId().value()))
                    && (!kind.equals("active-source") || row.active()), "Occurrence bucket contains a foreign row");
            occurrences.verify(original.occurrenceInventory(), row); return row;
        }, (k, v) -> v), (k, bucket) -> maps.stage(bucket));
    }
    private static ManagedOccurrenceInventory.OccurrenceKey occurrenceKey(ManagedOccurrenceBinding row) {
        return ManagedOccurrenceInventory.OccurrenceKey.of(DocumentId.of(row.sourceDocumentId().value()), row.sourceAddress().path());
    }

    private ProcessEmbeddedComponentIndex topology(ProcessEmbeddedComponentIndex value, boolean stage) {
        var s = value.storedIndexes(); var joins = s.joins().storedIndexes(); var raw = original.componentIndex();
        var component = stage ? maps.stage(s.components()) : checked("topology/component", s.components(), (id, row) -> {
            require(row.equals(checkedTopologyComponent(raw, id).orElseThrow()), "Selected topology component differs"); return row;
        });
        var targets = topologyBuckets("targets", s.targets(), stage, (owner, member) -> requireMembership(raw.storedIndexes().sources(), member, owner));
        var sources = topologyBuckets("sources", s.sources(), stage, (owner, member) -> requireMembership(raw.storedIndexes().targets(), member, owner));
        var members = topologyBuckets("join-members", joins.members(), stage, (owner, member) ->
                requireMembership(raw.storedIndexes().joins().storedIndexes().roots(), member, owner));
        var roots = topologyBuckets("join-roots", joins.roots(), stage, (member, owner) -> {
            requireMembership(raw.storedIndexes().joins().storedIndexes().members(), owner, member);
            topology.requireJoinRoot(raw, owner, selected.apply(owner).retainedView());
        });
        return ProcessEmbeddedComponentIndex.restoreIndexes(new ProcessEmbeddedComponentIndex.StoredIndexes(component, targets, sources,
                s.rootedViews(), RootedJoinCandidateIndex.restoreIndexes(new RootedJoinCandidateIndex.StoredIndexes(members, roots))));
    }
    private PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> topologyBuckets(String kind,
            PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> source, boolean stage,
            java.util.function.BiConsumer<DocumentId, DocumentId> check) {
        if (stage) return maps.stage(source);
        return checked("topology/" + kind, source, (owner, bucket) -> {
            if (bucket.isLogical()) return maps.open("topology/" + kind + "-bucket", owner, bucket, (member, present) -> {
                if (kind.equals("targets") || kind.equals("sources"))
                    require(checkedTopologyComponent(original.componentIndex(), owner).isPresent(), "Topology edge owner is missing");
                if (kind.equals("join-members")) topology.requireJoinRoot(original.componentIndex(), owner, selected.apply(owner).retainedView());
                require(Boolean.TRUE.equals(present), "Topology membership is not true"); check.accept(owner, member); return present;
            }, (key, present) -> present);
            // These APIs enumerate keys. Validate the complete selected membership bucket at its value boundary,
            // not by mapping descendant values (which keys() deliberately never opens).
            if (kind.equals("targets") || kind.equals("sources"))
                require(topology.component(original.componentIndex(), owner).isPresent(), "Topology edge owner is missing");
            if (kind.equals("join-members")) topology.requireJoinRoot(original.componentIndex(), owner, selected.apply(owner).retainedView());
            for (var entry : bucket.entries()) {
                require(Boolean.TRUE.equals(entry.getValue()), "Topology membership is not true"); check.accept(owner, entry.getKey());
            }
            return bucket;
        }, (owner, bucket) -> maps.stage(bucket));
    }
    private static void requireMembership(PersistentOrderedMap<DocumentId, PersistentOrderedMap<DocumentId, Boolean>> source,
            DocumentId owner, DocumentId member) {
        var bucket = source.get(owner); require(bucket != null && Boolean.TRUE.equals(bucket.get(member)), "Selected topology reverse membership is missing");
    }

    private Optional<ProcessEmbeddedComponentIndex.Component> checkedTopologyComponent(ProcessEmbeddedComponentIndex index,
            DocumentId owner) {
        return topology.component(index, owner, controlledNamespace && index.storedIndexes().components().isLogical());
    }

    private ComponentStateInventory component(ComponentStateInventory value, boolean stage) {
        var s = value.storedIndexes();
        return ComponentStateInventory.restoreIndexes(new ComponentStateInventory.StoredIndexes(
                stage ? maps.stage(s.lineages()) : checked("component/lineage", s.lineages(), (key, row) -> {
                    require(key.equals(row.componentIdentity()) && !row.orderedMemberDocumentIds().isEmpty(), "Component lineage key differs");
                    checkedComponent(DocumentId.of(row.orderedMemberDocumentIds().get(0).value())); return row;
                }), stage ? maps.stage(s.states()) : checked("component/state", s.states(), (key, lineage) -> {
                    var row = original.componentStateInventory().storedIndexes().lineages().get(lineage);
                    require(row != null && key.equals(row.componentStateIdentity()), "Component state identity selects another lineage");
                    checkedComponent(DocumentId.of(row.orderedMemberDocumentIds().get(0).value())); return lineage;
                }), stage ? maps.stage(s.documents()) : checked("component/document", s.documents(), (id, lineage) -> {
                    require(lineage.equals(checkedComponent(id).componentIdentity()), "Component document membership differs"); return lineage;
                })));
    }
    private blue.language.processor.closure.ComponentSnapshot checkedComponent(DocumentId id) {
        var exact = selected.apply(id); var view = exact.retainedView();
        require(view != null, "Selected component has no retained rooted publication");
        return components.forDocument(original.componentStateInventory(), id, view.snapshot().components()).orElseThrow();
    }

    private ClosureSubscriptionInventory subscription(ClosureSubscriptionInventory value, boolean stage) {
        var s = value.storedIndexes(); var raw = original.closureSubscriptions();
        return ClosureSubscriptionInventory.restoreIndexes(new ClosureSubscriptionInventory.StoredIndexes(
                stage ? maps.stage(s.slots()) : checked("subscription/slots", s.slots(), (key, row) -> {
                    require(key.equals(ClosureSubscriptionInventory.Slot.from(row)), "Subscription slot key differs"); checkSubscription(row); return row;
                }), stage ? maps.stage(s.identities()) : checked("subscription/identity", s.identities(), (id, slot) -> {
                    var row = raw.storedIndexes().slots().get(slot); require(row != null && id.equals(row.subscriptionIdentity()), "Subscription identity selects another slot");
                    checkSubscription(row); return slot;
                }), stage ? maps.stage(s.documents()) : checked("subscription/documents", s.documents(), (id, bucket) ->
                        maps.open("subscription/document-bucket", id, bucket, (channel, row) -> {
                            var slot = ClosureSubscriptionInventory.Slot.from(row);
                            require(id.equals(slot.documentId()) && channel.equals(slot.rawChannelKey()), "Subscription document bucket differs"); checkSubscription(row); return row;
                        }, (k, v) -> v), (k, bucket) -> maps.stage(bucket)),
                stage ? maps.stage(s.demands()) : checked("subscription/demands", s.demands(), (id, bucket) ->
                        maps.open("subscription/demand-bucket", id, bucket, (channel, row) -> {
                            require(channel.equals(row.rawChannelKey()), "Embedded demand slot differs"); return row;
                        }, (k, v) -> v), (k, bucket) -> maps.stage(bucket)), s.comparisons(), s.copiedNodes(), s.visitedRows()));
    }
    private void checkSubscription(blue.language.processor.closure.SubscriptionState row) {
        var id = DocumentId.of(row.channelOccurrence().managedDocumentId().value()); var exact = selected.apply(id);
        subscriptions.statesFor(original.closureSubscriptions(), id);
        require(exact.retainedLineage().currentBlueId().equals(row.documentBlueId())
                && exact.retainedGraphGeneration() == row.graphGeneration()
                && checkedComponent(id).componentGeneration() == row.componentGeneration(), "Subscription retained head/generations differ");
    }

    private ManagedEpochReceiptStore receipt(ManagedEpochReceiptStore value, boolean stage) {
        var s = value.storedState(); var raw = original.managedEpochReceipts();
        var documents = stage ? maps.stage(s.documents()) : checked("receipt/documents", s.documents(), (id, history) -> {
            require(id.equals(history.documentId()), "Receipt history owner differs from its key");
            var epochs = maps.open("receipt/epochs", id, history.receiptMap(), (epoch, row) -> {
                require(row.publicReceipt().documentId().equals(id) && row.publicReceipt().epoch() == epoch, "Receipt epoch key differs");
                receipts.exact(raw, id, epoch); return row;
            }, (k, v) -> v, epoch -> require(epoch < 0 || epoch > history.latestEpoch(), "Missing selected in-range receipt"));
            return history.withReceipts(epochs);
        }, (id, history) -> history.withReceipts(maps.stage(history.receiptMap())));
        var identities = stage ? maps.stage(s.identities()) : checked("receipt/identity", s.identities(), (id, row) -> {
            require(id.equals(row.publicReceipt().receiptIdentity()), "Receipt identity key differs");
            var selected = receipts.exact(raw, row.publicReceipt().documentId(), row.publicReceipt().epoch());
            require(selected.found() && id.equals(selected.receipt().receiptIdentity()), "Receipt identity has no exact numbered row"); return row;
        });
        return ManagedEpochReceiptStore.restoreStored(new ManagedEpochReceiptStore.StoredState(documents, identities, s.comparisons(), s.copiedNodes()));
    }
    private CatchUpPlanStore catchUp(CatchUpPlanStore value, boolean stage) {
        var s = value.storedState(); var p = s.plans().storedIndexes(); var raw = original.catchUpPlans().storedState();
        var identities = stage ? maps.stage(p.identities()) : checked("plan/identities", p.identities(), (id, row) -> {
            require(id.equals(row.planIdentity()), "Plan identity key differs"); plans.requireMembership(raw.plans(), row); return row;
        });
        var consumers = stage ? maps.stage(p.consumers()) : checked("plan/consumers", p.consumers(), (id, bucket) -> { plans.forConsumer(raw.plans(), id); return bucket; });
        var sources = stage ? maps.stage(p.sources()) : checked("plan/sources", p.sources(), (id, bucket) -> { plans.forSource(raw.plans(), id); return bucket; });
        var occurrence = stage ? maps.stage(p.occurrences()) : checked("plan/occurrences", p.occurrences(), (id, bucket) -> { plans.forOccurrence(raw.plans(), id); return bucket; });
        var barriers = stage ? maps.stage(p.barriers()) : checked("plan/barriers", p.barriers(), (id, bucket) -> { plans.forBarrier(raw.plans(), id); return bucket; });
        var active = stage ? maps.stage(p.activeSources()) : checked("plan/active-sources", p.activeSources(), (id, count) -> { plans.forSource(raw.plans(), id); return count; });
        var plan = ManagedCatchUpPlanIndex.restoreIndexes(new ManagedCatchUpPlanIndex.StoredIndexes(identities, consumers, sources, occurrence, barriers, active, p.comparisons(), p.copiedNodes()));
        var barrierRows = stage ? maps.stage(s.barriers()) : checked("barriers", s.barriers(), (id, row) -> { plans.barrier(raw.plans(), raw.barriers(), id); return row; });
        return CatchUpPlanStore.restoreStored(new CatchUpPlanStore.StoredState(plan, barrierRows, work(s.work(), stage), s.activeBarriers(), s.comparisons(), s.copiedNodes()));
    }
    private ManagedCatchUpWorkIndex work(ManagedCatchUpWorkIndex value, boolean stage) {
        var s = value.storedIndexes(); var raw = original.catchUpPlans().storedState();
        var work = stage ? maps.stage(s.work()) : checked("work/identity", s.work(), (id, row) -> {
            works.requireWork(raw.work(), id, row.work());
            checkWorkOrder(row);
            return row;
        });
        var pending = stage ? maps.stage(s.pending()) : checked("work/pending", s.pending(), (plan, id) -> { works.pending(raw.work(), plan); return id; });
        var applications = stage ? maps.stage(s.applications()) : checked("work/applications", s.applications(), (id, row) -> {
            works.requireApplication(raw.work(), id, row.receipt()); return row;
        });
        var byWork = stage ? maps.stage(s.applicationsByWork()) : checked("work/application-by-work", s.applicationsByWork(), (id, application) -> {
            works.applicationByWork(raw.work(), id); return application;
        });
        var due = stage ? s.due() : s.due().withRetainedReadCheck((key, id) -> {
            require(!closed, "Selected index scope is closed");
            var retained = raw.work().storedIndexes().due().read(key);
            // New/replaced working rows are already validated by the unchanged registration algorithm.
            if (!retained.found() || !id.equals(retained.value())) return;
            var registered = raw.work().storedIndexes().work().get(id);
            require(registered != null && key.equals(registered.dueKey()), "Selected due key differs from registered work");
            works.requirePending(raw.work(), registered.work()); checkWorkOrder(registered);
        });
        return ManagedCatchUpWorkIndex.restoreIndexes(new ManagedCatchUpWorkIndex.StoredIndexes(work, pending, applications, byWork, due, s.comparisons(), s.copiedNodes()));
    }
    private void checkWorkOrder(ManagedCatchUpWorkIndex.RegisteredWork row) {
        var raw = original.catchUpPlans().storedState(); var item = row.work();
        var barrier = plans.barrier(raw.plans(), raw.barriers(), item.barrierIdentity());
        var source = receipts.exact(original.managedEpochReceipts(), item.sourceDocumentId(), item.sourceEpoch());
        require(barrier != null && source.found() && source.receipt().receiptIdentity().equals(item.sourceReceiptIdentity())
                && barrier.consumerDocumentId().equals(item.consumerDocumentId()) && barrier.planIdentities().contains(item.planIdentity())
                && barrier.causeOrder().equals(row.dueKey().barrierCauseOrder())
                && source.receipt().sourceOrder().orElseThrow().equals(row.dueKey().sourceOrder()), "Work order differs from exact barrier/source");
    }
    @Override public void close() { closed = true; maps.close(); }
}
