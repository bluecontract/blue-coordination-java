# Host Timeline history and bundled MyOS channel

The immutable bundled runtime registers the generated MyOS Timeline Channel
subtype using standard Coordination Timeline semantics. The class comes from
the already pinned Blue Repository. Exact Language type evidence remains
authoritative. Rooted SDK configuration includes the actual processor
registration identity and rejects a mismatched cold configuration.

For an external finite provider, open native logical storage with
`RootedCoordinationStorage.openLogicalWithHistoryCoverage`. The
`TimelineHistoryCoverage` callback is bound to that one coherent attempt. It
receives the library-selected Timeline set, an exact full-order boundary and
an inclusive flag. A null boundary requires unbounded completeness.

Complete evidence certifies that all required original entries in the requested
range are already retained, provider completeness covers the range, and access
is authorized. A page, latest observed row or empty query is not sufficient.
The host owns coherent reads and final transaction validation of those facts.
It must not append or run semantics during the callback. Import missing exact
entries and reopen after durable scheduling. No host field enters Blue content.

Root inputs require inclusive coverage. Source prerequisites require exclusive
coverage. Timestamp-only exclusive frontier T normally needs T greater than the
cutoff timestamp to cover all full-order ties, even for an exclusive cutoff.
Finite coverage cannot authorize unbounded selection or global readiness.

An unavailable source range becomes native WAIT. Unavailable root coverage
throws `TimelineHistoryUnavailableException`; discard that owner and retry
with new evidence. Invalid evidence, callback exceptions or null responses
fail closed. Complete source evidence binds the external identity and exact
retained prefix. Existing closed standalone storage retains its original
completeness contract; the finite-provider entry point does not change that
contract or install a second journal.
