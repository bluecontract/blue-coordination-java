# Campaign F2 implementation and integration status

The selected Git root is `/Users/piotr/data/blue-contract-java`, whose origin is
`bluecontract/blue-coordination-java`. The isolated branch is
`codex/r2-coordination`, based on `be9142c2bd89fe1ce04bff2972dadb6456db7f09`.
No dependency stage without A2's `READY_FOR_DOWNSTREAM` marker is consumed.

## Collection routing

The actual published Contracts descriptor
`registry/blue-contracts-1.0/EmbeddedNodeChannel.blue` declares `sourcePath` an
optional scope-relative matcher. The published Coordination rc.5 JAR rejects
its absence with `Embedded Node Channel has no exact sourcePath at everyChild`.
The selected source base already includes the newer `ALL_DESCENDANTS` fix,
plus exact and direct/descendant collection selectors. Preserve those fixes.

`SdkCatchAllCollectionRoutingTest` uses one compiled Embedded Node Channel
without sourcePath over two occurrences of one source and a nested source.
It requires duplicate-preserving deliveries, stable source attribution,
declaration-order independence, detach/re-add generation 2, retarget generation
3, and no delivery from a detached/irrelevant source. It enumerates no listeners.
Existing selection tests cover decoded pointer escapes and scope-relative
path composition through intermediate managed documents.

The selection algorithm now retains its reverse frontier over one immutable
inventory capture. Adding a selected parent and its forward branches opens
new ancestors without rescanning incoming rows of previously known ancestors.
The regression expects a second observing parent discovered only through a
newly included sibling branch. Demand matching and canonical sorting remain
unchanged.

## Historical births: integration pending

The former publication guard in `ManagedEpochApplicationExecutor.publish`
rejected any resulting document without a captured existing head. The catch
in that executor and the mapping in `DefaultCoordinationEngine` retained a
typed blocked plan/barrier. `ManagedOccurrenceResolver` separately refuses an
unknown progressed state and only admits exact authored candidates; it does
not prove INITIALIZE. Together these checks prevent invented history, missing
birth emissions, partial topology publication and source re-execution.

Prepared publication now uses the ordinary managed-expansion transaction:
absent-member fences, existing head and graph fences, authenticated input and
result member sets, epoch-zero session/receipt staging, complete event sequence,
subscriptions, occurrence generations, routes and cursor in one state swap.
The source epoch remains immutable. A missing complete transition/birth receipt
still blocks publication. No Contracts receipt constructor or signing authority
was exposed to manufacture a birth.

`SdkNestedNewLineageCatchUpTest` replaces the old unsupported-as-success test
with a strict success requirement: attach source epoch zero, consume its
retained source event, create `/children/new`, publish one verified initialization
receipt, finish the consumer cursor, and preserve source history across retry.
This is **BLOCKED_UPSTREAM**, not a completed feature. The prepared adapter must
be reconciled with E2's actual integrated `contracts-api.json` before it is ready.
It currently uses existing Contracts types; no unannounced E2 API is assumed.

## Provider, recovery and boundedness

The [provider boundary](../reference/retained-history-provider.md) has a genuine
published runtime producer and independently configured signature verifier.
Its tests cover complete bounded pages, source extension between pages,
duplicate event payloads, tampered head/signature, wrong producer, conflicting
lineage anchor, request replay, missing epochs, out-of-order delivery and retry.
The delayed-evidence case expects exactly seven receipt reads across three
requests (2 failed, then 3 and 2), with consumer positions 0 → 2 → 4.

`ManagedEpochApplicationAtomicRollbackTest` now injects failure at all four
store boundaries: after CAS, after staged documents, after staged topology,
and before swap. Each failure preserves every durable surface; retry commits
one consumer epoch/cursor and leaves previously committed source history intact.
Response-loss reconciliation and receipt tamper/gap checks retain their existing
independent expectations. The isolated `publishedRecovery` task checks those
contracts against the unchanged published runtime.

Existing authored-initial/epoch-zero/explicit/current-head, repeated occurrence,
live extension, reciprocal reaction, exact-content delay and budget fairness
suites remain required candidate checks. No catch-up target is frozen and no
semantic gas charge is equated with a host work or time budget. Full candidate
results and actual counter measurements must be recorded after binding A2.

## Required handoff and unresolved blockers

The unchanged published tuple resolves successfully, but this source base
does not compile with it: `RuntimeBlueIds.EMBEDDED_COLLECTION_EVENT_CHANNEL`
is absent from Contracts 3.1.0-rc.23. The new collection/birth tests and complete
source gates therefore remain **BLOCKED_UPSTREAM**. Strict tests are not skipped
or weakened to convert this into readiness.

Consume a candidate only when the actual Language worktree contains
`build/campaign/r2/handoff/READY_FOR_DOWNSTREAM`, `candidate.json` declares
`READY_FOR_DOWNSTREAM` and `releaseReadinessClaimed=false`, and its exact
`contracts-api.json` describes E2's committed birth contract. Prefer A2's
dependency-binding commit, verify all manifest bytes, and rerun strict tests
first. Record both tuples. The early `/private/tmp/blue-r2-artifacts` staging
directories are not authoritative handoffs.

Labs 05/09 public construction and external runtime history import remain
unimplemented. The provider transport is a bounded prerequisite, not a substitute
for those capabilities. Fresh-process restoration remains C2-owned and requires
atomic restoration of source/application receipts, exact/proof content, plans,
barriers, cursors, graph/route generations and outbox evidence.

Exact command outcomes, dependency hashes and slice commits belong in the
ignored `build/campaign/r2/` receipts. Frozen historical release receipts and
the other lanes' worktrees are unchanged.
