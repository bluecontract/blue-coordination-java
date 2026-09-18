# Rooted feeder progress storage

## Problem and example

The rooted feeder retains two maps beside its terminal control state: exact
resource suspensions per lane, and authenticated declared-birth rejection
decisions per frozen obligation. Capturing only terminal frontiers would lose a
blocked lane after restart or execute a rejected birth again. For example, A may
wait for an exact resource while B completes the same input and advances to the
next input. A must resume the same invocation; B must neither wait for A nor
repeat its completed turn.

## Solution

`StoredFeederProgress` binds each map to separate, caller-pinned key and insertion
order roots using `StoredInsertionOrderedMap`. Pending rows retain the complete
attempt ticket and the original ordered, duplicate-preserving typed demand list
through Language's closed execution-evidence codec. Rejection rows use the
existing complete publication-rejection codec, including original selected and
executed inputs, private demand authority, original draft-plan association,
issues, retry count, and any exact rooted view addresses. Reads receive the
caller's existing `DocumentSessionStorage.OpenScope`; no second view interner is
created. Selected rows must agree with the actual lane or terminal key.

`DurableState.StoredMaps` installs the actual owned mutable maps. Its existing
selection, terminal recording, non-overtake, fence, and rejection rules are
unchanged. `storageState()` still rejects nonempty extra maps. Only the explicit
complete capture path calls `storageState(false)` and stores the map snapshots
alongside that control state. The old `fromStorage(state)` remains control-only;
`fromStorage(state, maps)` supplies the complete maps without an eager scan.

## Rationale and boundaries

Opening authenticates bounded directory metadata, not every lane or rejection.
Point reads hydrate only selected rows and preserve each selected Java identity
until replacement, removal, or close. Codec encoding and canonical read checks
do not write; rejection view prewrites happen only during map mutation. Physical
write failure leaves an individual map's old roots intact. A failed multi-map
bootstrap retain closes its scope so partial roots cannot be published. Closing
this component never closes the shared view scope owned by the caller.

As with other storage-backed mutable families, a physical failure during a
larger feeder transition requires discarding the entire owning attempt. For
example, the existing terminal-recording order updates its resident terminal
control before removing the pending row. This component does not turn those
multiple changes into a separate transaction; staging the failed working owner
would be invalid even when its immutable backing roots are still intact.

The owner must atomically select and publish both maps, their insertion counters,
and terminal control state together. This component adds no global root CAS,
publication decision, scheduling policy, PROCESS call, provider lookup, logical
budget, or gas change. Map-root descriptors alone are not complete SDK recovery.
Selected row payloads and any referenced selected histories are materialized
within explicit physical limits; full iteration and existing `DurableState.copy`
remain exhaustive. This is not a bounded whole-runtime memory/scaling claim.

Dropping pending rows, reconstructing rejection authority from public IDs,
replaying the processor, replacing insertion order with sorted enumeration, and
silently enabling complete capture in the old control-only path were rejected.

## Focused evidence

The final frozen gate passed **27/27 controls plus Javadoc** in 18 seconds on
Language 8542285, unchanged BEX ab72af1 and catalog 0b68744, Java 17, one worker.
Source hashes before/after were identical:
`ff9f070927d3ee2d4c8a337c148a29770b6e2b8d474db84ef29c6831e8656625`.
Only this evidence paragraph changed afterward. New controls exercise a
closed-producer/recreated exact fixture, original blocked invocation and ordered
demands, independent B progress, terminal non-commit and old-root recovery,
actual processor-issued birth rejection, same-key identity, wrong-key rejection,
write rollback, partial-retain closure, selected corruption and swapped roots.
Original feeder, rejection, insertion-map and engine-control owners also run.

The first 27-control run had one fixture failure: a two-node AVL root removal
reused its child without any physical write, so the intended write fault did not
fire. The test now injects at a replacement's mandatory immutable-row write;
the root, identity and rollback assertions are preserved. Raw first-run evidence
is retained outside the worktree.

The final owner counts are StoredFeederProgress 4, ContractsRootFeederWindow 8,
RootedDeclaredBirthRejection 4, StoredInsertionOrderedMap 7, and
EngineControlStorageCodec 4. One intervening compile-only failure identified an
explicit try-with-resources close warning; lexical scope now performs that same
close before the shared-scope ownership assertion. No production correction was
needed after the first gate.
