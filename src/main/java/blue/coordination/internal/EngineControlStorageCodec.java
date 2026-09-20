package blue.coordination.internal;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.DocumentId;
import blue.coordination.api.Timeline;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.NoncommittingExecutionException;
import blue.language.snapshot.ExactNodeStorageCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static blue.language.snapshot.ExactNodeStorageCodec.*;

/** Private bounded physical control component, not a complete engine restore API. */
final class EngineControlStorageCodec {
    static final String FORMAT = "blue-coordination/rooted-engine-control/2";
    private final ExactNodeStorageCodec envelope;

    EngineControlStorageCodec(int maximumBytes) { envelope = new ExactNodeStorageCodec(maximumBytes, 128); }

    record State(DefaultCoordinationEngine.ContractsRuntimeBinding binding,
            Map<String, Timeline> timelines, Map<String, String> actorKinds,
            long logicalClockMicros, long applicationClockMicros, List<DocumentId> publicRoots,
            RootedProcessingSchedule.StorageState rootedSchedule, List<DocumentId> deferredManagedConsumers,
            List<DocumentId> isolatedManagedConsumers, boolean managedEpochTurn,
            ContractsRootFeederWindow.StorageState feeder, ContractsJournalDrainCoordinator.StorageState journal) {
        State {
            Objects.requireNonNull(binding); Objects.requireNonNull(rootedSchedule);
            Objects.requireNonNull(feeder); Objects.requireNonNull(journal);
            if (!binding.languageSpecificationIdentity().matches("sha256:[0-9a-f]{64}")
                    || !binding.contractsSpecificationIdentity().equals(ContractsClosureProfile.ROOTED_CONTRACTS_SPECIFICATION))
                throw invalid("Control binding is not an exact rooted Contracts profile");
            timelines = owned(timelines); actorKinds = owned(actorKinds);
            publicRoots = unique(publicRoots); deferredManagedConsumers = unique(deferredManagedConsumers);
            isolatedManagedConsumers = unique(isolatedManagedConsumers);
            if (!timelines.keySet().equals(actorKinds.keySet())) throw invalid("Timeline actor-kind coverage differs");
            for (var entry : timelines.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().timelineId()) || actorKinds.get(entry.getKey()).isBlank())
                    throw invalid("Invalid Timeline registration");
            }
            if (logicalClockMicros < 1_800_000_000_000_000L || applicationClockMicros < 1_800_000_000_000_000L)
                throw invalid("Invalid retained engine clocks");
        }
    }

    byte[] encode(State state) {
        return physical(() -> envelope.encodeEnvelope(FORMAT, out -> {
            var b = state.binding();
            writeText(out, b.languageSpecificationIdentity()); writeText(out, b.contractsSpecificationIdentity());
            out.writeLong(b.executionPolicy().sharedGasLimit()); writeText(out, b.executionPolicy().label());
            out.writeInt(state.timelines().size());
            for (var e : state.timelines().entrySet()) {
                writeText(out, e.getKey()); writeText(out, e.getValue().actorId()); writeText(out, state.actorKinds().get(e.getKey()));
            }
            out.writeLong(state.logicalClockMicros()); out.writeLong(state.applicationClockMicros());
            documents(out, state.publicRoots());
            out.writeBoolean(state.rootedSchedule().historicalTurn()); documents(out, state.rootedSchedule().yielded());
            out.writeInt(state.rootedSchedule().isolated().size());
            for (var e : state.rootedSchedule().isolated().entrySet()) { document(out, e.getKey()); writeText(out, e.getValue()); }
            documents(out, state.deferredManagedConsumers()); documents(out, state.isolatedManagedConsumers());
            out.writeBoolean(state.managedEpochTurn());
            out.writeInt(state.feeder().terminalProgress().size());
            for (var terminal : state.feeder().terminalProgress()) {
                var t = terminal.ticket(); writeText(out, t.entryBlueId()); order(out, t.sourceOrder());
                out.writeInt(t.cohortIndex()); lane(out, t.lane()); writeText(out, t.invocationIdentity()); documents(out, t.members());
                out.writeBoolean(terminal.commits()); out.writeBoolean(terminal.published());
            }
            out.writeInt(state.feeder().frontiers().size());
            for (var e : state.feeder().frontiers().entrySet()) { lane(out, e.getKey()); order(out, e.getValue()); }
            out.writeInt(state.journal().terminalEntries().size());
            for (var e : state.journal().terminalEntries()) { writeText(out, e.entryBlueId()); order(out, e.sourceOrder()); }
            order(out, state.journal().processedThrough());
        }));
    }

    State decode(byte[] bytes) {
        return physical(() -> {
            State state = envelope.decodeEnvelope(bytes, FORMAT, in -> {
                var binding = new DefaultCoordinationEngine.ContractsRuntimeBinding(requiredText(in), requiredText(in),
                        ContractsExecutionPolicy.exactSharedGas(in.readLong(), requiredText(in)));
                Map<String, Timeline> timelines = new LinkedHashMap<>(); Map<String, String> kinds = new LinkedHashMap<>();
                int count = count(in);
                for (int i = 0; i < count; i++) {
                    String id = requiredText(in); put(timelines, id, new Timeline(id, requiredText(in)));
                    put(kinds, id, requiredText(in));
                }
                long logical = in.readLong(), application = in.readLong(); List<DocumentId> roots = documents(in);
                boolean historical = bool(in); List<DocumentId> yielded = documents(in);
                Map<DocumentId, String> isolated = new LinkedHashMap<>(); count = count(in);
                for (int i = 0; i < count; i++) put(isolated, document(in), requiredText(in));
                var schedule = new RootedProcessingSchedule.StorageState(historical, yielded, isolated);
                var deferred = documents(in); var managedIsolated = documents(in); boolean managedTurn = bool(in);
                List<ContractsRootFeederWindow.TerminalProgress> terminal = new ArrayList<>(); count = count(in);
                for (int i = 0; i < count; i++) {
                    var ticket = new ContractsRootFeederWindow.AttemptTicket(requiredText(in), requiredOrder(in),
                            in.readInt(), lane(in), requiredText(in), documents(in));
                    terminal.add(new ContractsRootFeederWindow.TerminalProgress(ticket, bool(in), bool(in)));
                }
                Map<ContractsRootFeederWindow.LaneId, ExternalOrderKey> frontiers = new LinkedHashMap<>(); count = count(in);
                for (int i = 0; i < count; i++) put(frontiers, lane(in), requiredOrder(in));
                List<ContractsJournalDrainCoordinator.EntryKey> entries = new ArrayList<>(); count = count(in);
                for (int i = 0; i < count; i++) entries.add(new ContractsJournalDrainCoordinator.EntryKey(requiredText(in), requiredOrder(in)));
                return new State(binding, timelines, kinds, logical, application, roots, schedule, deferred, managedIsolated,
                        managedTurn, new ContractsRootFeederWindow.StorageState(terminal, frontiers),
                        new ContractsJournalDrainCoordinator.StorageState(entries, order(in)));
            });
            if (!Arrays.equals(bytes, encode(state))) throw invalid("Noncanonical engine control storage");
            return state;
        });
    }

    private static void lane(DataOutputStream out, ContractsRootFeederWindow.LaneId lane) throws IOException {
        out.writeBoolean(lane.publicLane()); documents(out, lane.roots());
    }
    private static ContractsRootFeederWindow.LaneId lane(DataInputStream in) throws IOException {
        return new ContractsRootFeederWindow.LaneId(bool(in), documents(in));
    }
    private static void document(DataOutputStream out, DocumentId id) throws IOException { writeText(out, id.value()); }
    private static DocumentId document(DataInputStream in) throws IOException { return DocumentId.of(requiredText(in)); }
    private static void documents(DataOutputStream out, List<DocumentId> ids) throws IOException {
        out.writeInt(ids.size()); for (var id : ids) document(out, id);
    }
    private static List<DocumentId> documents(DataInputStream in) throws IOException {
        int count = count(in); List<DocumentId> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) ids.add(document(in)); return List.copyOf(ids);
    }
    private static void order(DataOutputStream out, ExternalOrderKey key) throws IOException {
        out.writeBoolean(key != null); if (key == null) return;
        out.writeInt(key.components().size());
        for (var item : key.components()) {
            if (item instanceof BigInteger integer) { out.writeByte(1); writeBytes(out, integer.toByteArray()); }
            else if (item instanceof String text) { out.writeByte(2); writeText(out, text); }
            else throw invalid("Unsupported external-order component");
        }
    }
    private static ExternalOrderKey order(DataInputStream in) throws IOException {
        if (!bool(in)) return null; int count = count(in); List<Object> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            switch (in.readUnsignedByte()) {
                case 1 -> items.add(new BigInteger(readBytes(in)));
                case 2 -> items.add(requiredText(in));
                default -> throw invalid("Unknown external-order component");
            }
        }
        return ExternalOrderKey.of(items);
    }
    private static ExternalOrderKey requiredOrder(DataInputStream in) throws IOException {
        return Objects.requireNonNull(order(in), "order");
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte(); if (value > 1) throw invalid("Invalid boolean"); return value == 1;
    }
    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 0 || count > in.available()) throw invalid("Invalid bounded control count"); return count;
    }
    private static <T> List<T> unique(List<T> values) {
        List<T> result = List.copyOf(values);
        if (new LinkedHashSet<>(result).size() != result.size()) throw invalid("Repeated control item"); return result;
    }
    private static <K,V> Map<K,V> owned(Map<K,V> map) {
        Map<K,V> result = new LinkedHashMap<>(); map.forEach((k,v) -> put(result, k, v));
        return Collections.unmodifiableMap(result);
    }
    private static <K,V> void put(Map<K,V> map, K key, V value) {
        if (map.putIfAbsent(Objects.requireNonNull(key), Objects.requireNonNull(value)) != null) throw invalid("Repeated control key");
    }
    private static <T> T physical(java.util.function.Supplier<T> action) {
        try { return action.get(); } catch (NoncommittingExecutionException failure) { throw failure; }
        catch (RuntimeException failure) { throw new CoordinationObjectStorageException("Engine control storage failed", failure); }
    }
}
