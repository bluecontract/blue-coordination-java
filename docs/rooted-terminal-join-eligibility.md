# Related LIVE work cannot overtake a pending terminal join

## Problem and witness

In the real reciprocal attachment sequence A→B, then B→A, a global sliced
driver interleaved A's LIVE observation after B consumed A0 but before B's
remaining historical successors. A's independently owned same-epoch
representation consequently changed. At B's terminal A2 join, the frozen
selected A2 was `4fdzo5ubrRkHauX4xuSwzcWftrVMGygu8tsskrmc18mA`, while the
current A2 was `45tkpbhgCnEiKQN11fCWx2aKxc6awkzqpFbgTWczXKgf` (both graph 2).
The existing owned-head CAS correctly rejected publication. Running B's
canonical root-local historical steps before the related A LIVE turn already
succeeded on the unchanged parent library.

RCP-HISTORY-05 requires terminal reconciliation and finalization of the real
cycle before subsequent affected work. Contracts C §16.9 explicitly forbids
later live/public work overtaking this barrier. Cross-root fairness therefore
cannot treat these prospective co-owners as independent roots.

## Narrow correction

`RootedJoinEligibility` examines an exact retained pending history row R→S.
Only when its selected active graph has a return path S→…→R does it protect
the vertices on that prospective cycle. For a root-local pending consumer,
the root-to-consumer approach is included as well. Reachable side branches
and incoming observers are not included.

The original owning barrier supplies the cutoff, validated against the exact
occurrence, activation generation, source, path, pending cursor, and plan.
The root-local case retains its original logical boundary. A related LIVE
input at or after that boundary cannot overtake; equality belongs to the
creating barrier. Earlier prerequisites remain eligible. One-way sources
without a return path are not frozen. Each root still selects its original
next historical/local/LIVE head before cross-root fairness is considered.

### Point lookup and private derived metadata

A current forward walk is not a sufficient candidate locator: independently
retained rooted graphs need not agree. `RootedJoinCandidateIndex` therefore
indexes prospective affected document IDs to retained root candidates, with
reverse membership for removal. The existing immutable component index
replaces it atomically from each complete owned rooted result. A complete
store reconstruction derives it again from retained session views. It is a
locator, not authority: point selection still opens the exact retained view
and validates the plan/barrier evidence. Stale path membership is removed on
replacement, retirement, or completed join.

Only the affected result's metadata and indexed buckets are changed; no
additional realm walk is introduced into point eligibility. Cost is
proportional to that selected graph's pending-path analysis and changed
membership, with persistent-map index updates. Current path analysis traverses
the selected graph per pending row. The existing global driver still scans
sessions, and its separate excluded-consumer catalog traversal is unchanged.
This is correctness qualification, not a bounded-memory or scaling proof, nor a new
external-state API nor proof of whole-runtime selected-root storage locality.

## Guards and rejected alternatives

Owned-head/current-state CAS, process receipt before-state, representation
transition chaining, exact history/receipt order, gas, terminal idempotency,
and borrowed-state authority are unchanged. Replacing the frozen input with
today's body or relaxing its before-state fence would create an invalid
representation predecessor, not repair scheduling.

An additional LIVE identity/capture API was investigated and removed. Its
motivating old-view fixture had been incorrectly admitted because of a
separate prerequisite traversal defect; see
[the selected-source prerequisite correction](rooted-selected-source-prerequisite-view.md).
Invalidly admitted state is not used as permission to bypass capture checks.

## Focused evidence and limits

The source-stable joined gate passed 20 tests. New controls cover:

- actual terminal global execution versus unchanged root-local execution,
  comparing complete receipt metadata, exact documents, source entries,
  ordered duplicate-preserving events/outbox, current representations,
  terminal identities, total gas and gas-trace identities, plus restart;
- boundary equality and earlier-order eligibility, an unavailable same-cursor
  join across restart, and independent C progress without changing A/B history;
- one-way source progress and exclusion of side branches/incoming observers;
- failed atomic publication retaining the old locator and histories, retry
  of the same input, completed-join retirement, reconstruction, and later LIVE
  progress; and immutable index replacement/removal.

Existing authority, journal cutoff, global-driver and sliced scheduling
owners also passed, including real gas-failed retained history with independent
progress/restart and rejection of a genuinely changed publication owner.
An additional source-stable 19-test existing prerequisite/query gate passed
unchanged in 44 seconds after the joined gate.

The original compact owner-fence red is retained as
`coordination9807-owner-fence-red.tar.gz`, SHA-256
`1c7d25f2ff618ade80e8be47fe8bad753564817aa7637ee8ae73bb98d9bc1c4e`.
The 20-test pass archive is `coordination9807-join-focused-20-pass.tar.gz`,
SHA-256 `e5858b65fde8bc0b9a179b8298e89e042cef5656b523289b8c2381bc84a4f911`,
outside the checkout in `rooted-terminal-owner-evidence.3RSNqs`.
The immutable tuple is the one recorded in the prerequisite correction.
The separate MyOS A3 imported-event-target reproduction and original saved-A5
application owner require an unchanged downstream rerun; this gate does not
claim those outcomes or full baseline acceptance.
