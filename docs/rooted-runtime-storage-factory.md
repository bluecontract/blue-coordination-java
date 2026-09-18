# Complete rooted runtime storage

## Problem and required behavior

An immutable session row alone cannot resume Coordination. For example, an Order
can be suspended while a separately owned Agreement admission is pending. After
the producing runtime is closed, a new runtime must recover the same selected
action, its frozen input, ordered pending work and publication evidence. Loading
the current Order body and selecting the action again is not equivalent.

The retained state must preserve the existing rooted algorithm: exact history,
event order, checkpoints, gas, failures, occurrence identities and independently
owned source publication. Persistence does not choose a different operation.

## Integration boundary

`RootedEngineStorage` assembles the existing engine from a complete, host-selected
set of independently named descriptors:

- document sessions, lineage, topology, catch-up work and publication indexes;
- exact whole-object representations and cyclic representation proofs;
- operation routes and active-source indexes;
- pending drafts, frozen selections and source prerequisite actions/results;
- suspended feeder demands, rejected-birth evidence and engine control state.

The document store owns one bounded view scope. Pending operations, receipts and
feeder evidence use that same scope to recover original object relationships;
they do not manufacture equivalent-looking replacements for identity-checked
execution evidence. A selected row is checked against its index key and original
operation context using the existing protocol checks.

The SDK factory adds its configuration and five runtime map families and creates
new handles belonging to the new SDK owner. Handles from a closed producer are
not transferable to the new runtime.

## Host-owned read, work and publication

1. Pin a coherent set of named state descriptors and the corresponding exact
   Timeline journal state. Record the read/absence fences required by the host.
2. Open an owned runtime scope. Opening does not replay commands, call a provider
   to rebuild history, or load every session body.
3. Execute the selected existing Coordination operation. Selected session and
   evidence rows are loaded through their storage adapters as needed.
4. Stage the resulting immutable records and named descriptors. Staging is **not
   publication**; failed prewrites do not advance the pinned state.
5. Atomically validate the host's fences and publish the applicable descriptors,
   journal changes, command outcome and durable work notifications. A losing
   attempt must not publish its stale selection.
6. Close the scope and retire its decoded objects and owner-bound handles.

A physical failure **during processing** requires discarding the whole attempt
scope. Existing multi-component transitions can have changed private working
control before a later storage read/write fails. Such an attempt must not be
staged or reported as a deterministic gas/runtime failure. Failure during the
separate immutable staging phase may be retried on the completed working result;
it still cannot advance the previously published selection by itself.

The host chooses the directory layout, scheduling, transactions and cache. The
factory does not provide a global root-CAS, database, worker queue, distributed
lease or notification fan-out implementation.

`retainPartition` is an explicit one-time conversion of an owned resident
partition. It walks that original state and captures bytes. Cold opening and
normal staging must not fall back to this conversion. An already storage-backed
engine must use its owning scope to stage changes.

## Scope and remaining scaling work

Named descriptors are not a claim that every current algorithmic access is
constant-time or partition-local. Root selection now reads catalog **IDs**, not
every unrelated session body, when constructing the existing exclusion set. That
is still a linear catalog-ID operation. Global audit/drain paths and some control
state remain broader; session records also retain full history. Their costs and
appropriate host work-selection/partition boundaries require measurement.

Cold opening exposed one constructor issue: the Contracts engine also constructed
the unused legacy sequential coordinator. That legacy constructor enumerated and
reconciled readiness for every session, so an unavailable unrelated document
could prevent opening a rooted scope. Contracts now constructs only its own
coordinators; the legacy-only coordinator and its recovery checks remain on the
legacy path. A regression removes unrelated session bodies and still opens and
processes the selected rooted document.

Physical cache and record limits are explicit host capacity controls. They do not
replace logical gas limits and do not justify skipping required evidence.

## Rejected shortcuts

- Restore only final document bodies and replay or reconstruct everything else.
- Treat a final child body plus total gas as a complete reusable execution result.
- Re-select a suspended action from a newer source head during recovery.
- Keep old SDK handles or a hidden producer runtime alive to make restart tests
  pass.
- Publish a partial descriptor family set or regard immutable prewrites as a
  committed document change.

Verification receipts are tracked separately from this integration contract.
Cold engine and SDK tests are intermediate gates, not PostgreSQL application E2E
or production performance acceptance.
