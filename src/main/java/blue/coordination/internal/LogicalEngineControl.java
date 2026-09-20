package blue.coordination.internal;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.DocumentId;
import blue.coordination.api.Timeline;
import blue.coordination.api.storage.CoordinationRecords.Family;
import blue.coordination.internal.ContractsJournalDrainCoordinator.EntryKey;
import blue.language.processor.ExternalOrderKey;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Explicit control records; opening reads only the stable runtime configuration. */
final class LogicalEngineControl {
    private static final long BASE = 1_800_000_000_000_000L;
    private final LogicalPointStorage logical;
    private final StoredInsertionOrderedMap.Limits limits;
    private final DefaultCoordinationEngine.ContractsRuntimeBinding binding;
    private final Map<String, Long> clocks;
    private final Map<String, Boolean> managedTurn;
    private final Map<String, Timeline> timelines;
    private final Map<String, String> actorKinds;
    private final RootedProcessingSchedule schedule;
    private final Set<DocumentId> deferred;
    private final Set<DocumentId> isolated;
    private final ContractsJournalDrainCoordinator.DurableState journal;
    private final PersistentMapCodec<String> text;
    private final PersistentMapCodec<Boolean> bool;
    private final PersistentMapCodec<DocumentId> document;

    LogicalEngineControl(LogicalPointStorage logical, StoredInsertionOrderedMap.Limits limits,
            DefaultCoordinationEngine.ContractsRuntimeBinding expected) {
        this.logical = logical; this.limits = limits;
        text = codec("text", Writer::text, SessionRecordCodec::text);
        bool = codec("boolean", Writer::bool, Reader::bool);
        document = codec("document", (w, id) -> w.text(id.value()), r -> DocumentId.of(text(r)));
        var config = codec("configuration", (Writer w, DefaultCoordinationEngine.ContractsRuntimeBinding b) -> {
            w.text(b.languageSpecificationIdentity()); w.text(b.contractsSpecificationIdentity());
            w.longValue(b.executionPolicy().sharedGasLimit()); w.text(b.executionPolicy().label());
        }, r -> new DefaultCoordinationEngine.ContractsRuntimeBinding(text(r), text(r),
                ContractsExecutionPolicy.exactSharedGas(r.longValue(), text(r))));
        var configuration = map(Family.CONFIGURATION, "runtime-binding", text, config);
        binding = Objects.requireNonNull(expected);
        var prior = configuration.putIfAbsent("profile", binding);
        require(prior == null || prior.equals(binding), "Logical runtime configuration differs from selected release");
        // Constructing the baseline also validates the exact rooted profile and policy.
        initialState();
        clocks = map(Family.CONFIGURATION, "clocks", text, codec("clock", Writer::longValue, r -> {
            long value = r.longValue(); require(value >= BASE, "Invalid retained engine clock"); return value;
        }));
        timelines = map(Family.TIMELINE, "registrations", text,
                codec("timeline", (Writer w, Timeline t) -> { w.text(t.timelineId()); w.text(t.actorId()); },
                        r -> new Timeline(text(r), text(r))))
                .validateRows((key, value) -> require(key.equals(value.timelineId()), "Foreign Timeline registration"));
        actorKinds = map(Family.TIMELINE, "actor-kinds", text, text);
        var turns = map(Family.ROOT_SCHEDULE, "turns", document, bool);
        var rounds = map(Family.ROOT_SCHEDULE, "rounds", document, codec("round", Writer::longValue, r -> {
            long value = r.longValue(); require(value >= 0, "Negative fairness round"); return value;
        }));
        schedule = new RootedProcessingSchedule(turns, rounds, Set.of(),
                map(Family.ROOT_SCHEDULE, "isolated", document, text));
        deferred = membership(Family.MANAGED_SCHEDULE, "deferred");
        isolated = membership(Family.MANAGED_SCHEDULE, "isolated");
        managedTurn = map(Family.MANAGED_SCHEDULE, "turn", text, bool);
        var entryKeys = codec("journal-terminal-key", (Writer w, EntryKey key) -> {
            w.text(key.entryBlueId()); order(w, key.sourceOrder());
        }, r -> new EntryKey(text(r), order(r)));
        var orders = codec("journal-frontier", (Writer w, ExternalOrderKey value) -> order(w, value),
                r -> Objects.requireNonNull(order(r)));
        journal = ContractsJournalDrainCoordinator.DurableState.logical(
                new LogicalRecordSet<>(map(Family.JOURNAL_TERMINAL, "entries", entryKeys, bool)
                        .validateRows((key, value) -> require(Boolean.TRUE.equals(value), "False journal terminal membership"))),
                map(Family.JOURNAL_FRONTIER, "frontier", text, orders));
    }
    EngineControlStorageCodec.State initialState() {
        return new EngineControlStorageCodec.State(binding, Map.of(), Map.of(), BASE, BASE, List.of(),
                new RootedProcessingSchedule.StorageState(false, List.of(), Map.of()), List.of(), List.of(), false,
                new ContractsRootFeederWindow.StorageState(List.of(), Map.of()),
                new ContractsJournalDrainCoordinator.StorageState(List.of(), null));
    }
    Map<String, Timeline> timelines() { return timelines; }
    Map<String, String> actorKinds() { return actorKinds; }
    long clock(String name) { return clocks.getOrDefault(name, BASE); }
    void clock(String name, long value) { require(value >= BASE, "Invalid logical clock"); clocks.put(name, value); }
    boolean managedTurn() { return Boolean.TRUE.equals(managedTurn.get("historical")); }
    void managedTurn(boolean value) { managedTurn.put("historical", value); }
    DefaultCoordinationEngine.ContractsRecoveryState recovery(ContractsRootFeederWindow.DurableState.StoredMaps feeder) {
        return DefaultCoordinationEngine.ContractsRecoveryState.logical(schedule,
                LogicalFeederControl.open(logical, feeder, limits), journal, deferred, isolated, this);
    }
    private Set<DocumentId> membership(Family family, String name) {
        return new LogicalRecordSet<>(map(family, name, document, bool)
                .validateRows((key, value) -> require(Boolean.TRUE.equals(value), "False membership row")));
    }
    private <K, V> LogicalPointStorage.Scope<K, V> map(Family family, String name,
            PersistentMapCodec<K> keys, PersistentMapCodec<V> values) {
        return logical.open(family, "engine/control/1/" + name, keys, values, limits);
    }
    private <T> PersistentMapCodec<T> codec(String name, BiConsumer<Writer, T> write, Function<Reader, T> read) {
        return new PersistentMapCodec<>() {
            public String identity() { return "blue-coordination/control-record/1/" + name; }
            public byte[] encode(T value) { return SessionStorageWire.encode(limits.maximumRecordBytes(), w -> {
                w.text(identity()); write.accept(w, value);
            }); }
            public T decode(byte[] bytes) { return SessionStorageWire.decode(bytes, limits.maximumRecordBytes(), r -> {
                require(identity().equals(text(r)), "Wrong logical control record"); return read.apply(r);
            }); }
        };
    }
}
