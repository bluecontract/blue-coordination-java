package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationRecords.Family;
import blue.coordination.internal.ContractsRootFeederWindow.*;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.BiConsumer;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Lane-local terminal facts and frontiers, bound to the same publication as suspended work. */
final class LogicalFeederControl {
    static DurableState open(LogicalPointStorage logical, DurableState.StoredMaps progress,
            StoredInsertionOrderedMap.Limits limits) {
        var lanes = codec("lane", limits.indexes().keyBytes(), LogicalFeederControl::lane, LogicalFeederControl::lane);
        var events = codec("event-lane", limits.indexes().keyBytes(), (Writer w, EventLaneKey value) -> {
            w.text(value.entryBlueId()); order(w, value.sourceOrder()); lane(w, value.lane());
        }, r -> new EventLaneKey(text(r), order(r), lane(r)));
        var terminals = codec("terminal", limits.maximumRecordBytes(), (Writer w, TerminalProgress value) -> {
            var ticket = value.ticket(); w.text(ticket.entryBlueId()); order(w, ticket.sourceOrder());
            w.integer(ticket.cohortIndex()); lane(w, ticket.lane()); w.text(ticket.invocationIdentity());
            list(w, ticket.members(), (out, id) -> out.text(id.value()));
            w.bool(value.commits()); w.bool(value.published());
        }, r -> new TerminalProgress(new AttemptTicket(text(r), order(r), r.integer(), lane(r), text(r),
                list(r, in -> DocumentId.of(text(in)))), r.bool(), r.bool()));
        var orders = codec("frontier", limits.maximumRecordBytes(), SessionStorageWire::order,
                r -> Objects.requireNonNull(order(r)));
        var terminal = logical.open(Family.FEEDER_TERMINAL, "engine/feeder-control/1", events, terminals, limits)
                .validateRows((key, value) -> require(key.equals(value.ticket().eventLaneKey()), "Foreign feeder terminal key"));
        var frontier = logical.open(Family.FEEDER_FRONTIER, "engine/feeder-control/1", lanes, orders, limits);
        return DurableState.logical(progress, terminal, frontier);
    }
    private static void lane(Writer out, LaneId value) {
        out.bool(value.publicLane()); list(out, value.roots(), (w, id) -> w.text(id.value()));
    }
    private static LaneId lane(Reader in) { return new LaneId(in.bool(), list(in, r -> DocumentId.of(text(r)))); }
    private static <T> PersistentMapCodec<T> codec(String name, int maximumBytes,
            BiConsumer<Writer, T> write, Function<Reader, T> read) {
        return new PersistentMapCodec<>() {
            public String identity() { return "blue-coordination/feeder-control/1/" + name; }
            public byte[] encode(T value) { return SessionStorageWire.encode(maximumBytes, w -> { w.text(identity()); write.accept(w, value); }); }
            public T decode(byte[] bytes) { return SessionStorageWire.decode(bytes, maximumBytes, r -> {
                require(identity().equals(text(r)), "Wrong feeder control codec"); return read.apply(r);
            }); }
        };
    }
}
