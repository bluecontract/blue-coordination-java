# Managed-graph causal equivalence

> **Status:** REQUIRED PHASE-1 CORRECTION / PROOF · **Revision:** 15.12  
> [Independent lineages](18-independent-lineage-processing.md) · [Causal model](17-causal-processing-model.md) · [Catch-up](13-deterministic-catch-up-repair.md) · [Scenarios](09-scenarios.md)

## The equality being proved

This is the retained equivalence requirement. Phase1 library implementation and scoped Phase2
bridges are verified in the [readiness record](implementation/phase-1-2-readiness.md); the complete
durable differential/fault/performance campaign remains in [Phase3](25-phase-3-integration-plan.md).
The scenarios below are proof obligations, not a blanket passing-coverage claim.

The primary reference is an eager-managed execution of the same semantic graph: identical authored
managed lineages, public Roots, admission and attachment policies, external causes, environment and
execution policy, with required material immediately available.

The reference independently derives local source/consumer operation boundaries and dependency order
from the revised semantics in18. The lazy/durable candidate must match them; copying the candidate's
grouping into the oracle is not proof. Independent sibling physical commit order may differ.

[21](21-semantic-equivalence-and-source-reuse.md) separates three proof obligations:
same-input materialization invariance, ordinary/managed logical-scope correspondence, and an explicit
independent source/consumer ownership change. The latter can change legacy combined rollback/IDs;
it does not waive unchanged handler routing, reads, update continuations or queue order.
Include initialization authority/gas, terminal failure progress, creator work and passive cycles.

Compare the exact committed semantic trace:

- ordered per-document revisions, exact states and BlueIds;
- work/update/validation/event occurrences, paths, multiplicity and order;
- semantic checkpoints and public outputs;
- application, invocation, result and semantic receipt identities;
- per-invocation gas trace and rollback;
- final causal progress, readiness and dependency blockers.

For chronological replay, compare direct historical child reads separately from event-derived parent
shadow fields. Require the complete original target-entry sequence and exact per-entry disposition/
application/delivery counts, not only a sampled observation or final state. Managed imports do not
replace missing original entries. These controls prevent a correct `/counterB` from hiding a future
`/child/counter` read, or a later assignment from hiding a missing, duplicate or reordered A entry.

Exclude only operational attempts, physical pages, caches, leases, database layout, transport retries
and elapsed time. Equal final values alone are insufficient.

Ordinary and managed representations need an explicit correspondence of logical ownership, scopes,
history, IDs and gas. An autonomous lineage is not silently substituted for arbitrary inline data;
conversely "different input" is not a blanket exclusion for the very routing/observation behavior
factorization must preserve. Use ordinary PROCESS fixtures for those common laws and hand-derived
graph traces for sharing/cycles. Two PROCESS_CLOSURE runs can share the same conformance gap.

## Preserve one Contracts invocation

Contracts owns each defined local operation's complete input, direct seed order, event-time local
snapshots, work/event queues, ordinals, tentative effects, gas, rollback and reuse maps. The host
never splits its result. Owning-library changes must instead exclude ordinary observing Orders from
Agreement's operation and support subsequent active-occurrence receipt consumption.

NeedsResources exports no partial semantic state. Availability-only evidence may preserve the closed
input; new identity-bearing occurrence evidence rebuilds input/InvocationId under the same stable
application intent. Retry and crash replay restart Contracts from the beginning.

Current Contracts is also not its own independent oracle. An eager/lazy differential using it on
both sides verifies integration. Small hand-worked semantic traces and constructor/gas conformance
tests separately verify the rules shared by both sides.

## Immutable recipients, current execution facts

A source commits its exact retained result and durable delivery/discovery basis, not an eagerly
materialized global application partition. Each consumer proves the exact due receipt, target-local
predecessor, temporal occurrence eligibility and complete local deliveries. Preserve raw occurrence
ordinals and identities, including multiple placements; receipt reuse cannot erase required reactions.

Recipient membership is derived at semantic time. A source atT30 cannot use a stale current index to
override an Order's T20 removal or omit a valid attachment. Bounded queries need complete lifecycle
frontiers before delivery closure. Unknown future consumer execution payload does not block source
commit; missing proof of the source's own local result or independence still does.

Subsequent local invocations read coherent scoped heads/cursors and pinned source versions.
Unrelated sibling commits cannot enter semantic identity or force perpetual global-generation retries.
A new ordinary observer creates its own receipt obligations rather than merging previous commits.
Actual returning dependencies use22's complete SCC at the logical cut with monotone tentative
expansion, one shared gas ledger and atomic projections. Missing evidence causes an honest hold,
not a fabricated Contracts status or permission to split the coupled result.

Whole-cause success additionally proves complete recipient/lifecycle coverage and exact required
outcomes. A complete local source result is not evidence that fanout finished. No empty-current-queue,
recomputed-hash or current-registration-count shortcut proves closure.

Dependency validation is action- and scope-specific. Every actual managed access, work, rebind and
write is covered, including demand-discovered SCC/ancestor expansion. Same-root ownership is not a
blanket readiness exemption. NEW_AUTHORED members need exact content-derived identity, fenced absence,
valid occurrence/lifecycle evidence and complete terminal projection; existing identical lineages
are reused and discarded prospective candidates do not force creation.

## Chronology and causal feedback

FULL_HISTORY initial embedding reconstructs observations at the correct original cause: S@10,
P-read@20, S@30 yields the t10 S value in P's t20 observation. Later attachment has its own declared
selection semantics. Same-original-cause source+parent grouping must be proved independently.

Historical read eligibility is not blanket permission to process CATCHING_UP state. A's direct
historical read can require a pending exact B view even when authoritative B is newer; every actual
use still needs its scoped prerequisites and valid initialization. The intended first scope also
includes ordinary same-scope removal/retargeting at E20 while B@30 is retained but not due. The
reviewed rule must preserve the original E20 position, retire the old activation's not-yet-due suffix,
prevent stale old-occurrence delivery, and create exact new binding/basis evidence when retargeted.
It cannot import B@30 first, delete B's history, or inherit an obsolete cursor through path reuse.
Already valid in-invocation work and event-time frozen deliveries still follow the complete Contracts
queue; suffix retirement is not permission to cancel them.
Phase1 repairs the baseline Contracts retarget restriction and retirement handling in the owning
library, without a silent read-only scope reduction; see [13](13-deterministic-catch-up-repair.md).

A target's selected operation excludes unrelated future input, while the one-way source may already
have advanced. Consumer views remain pinned to the due historical prefix. Genuine feedback creates
new explicitly ordered source work; it cannot be backdated or chosen by worker completion order.
Review final/nonfinal activation, receiver completeness and cyclic identity separately; see18 and13.

There is no universal ROOT_APPLICATION/MANAGED/HISTORY phase hierarchy. The next-action certificate
must prove the selected predecessor, chronological position, due prerequisites and permitted scope.
A finite supported computation with eventually available resources must not deadlock on its own
administrative gates. Infinite causal computation is not made finite by per-invocation gas.

## Compute-once and locality

For one exact Agreement transition referenced by N Orders, the logical source application/revision
is one; observable per-Order routes and reactions may scale with N. Shared source work must not be
recomputed once per parent merely because storage is separate.

Count tentative demand-discovery, completed execution and crash replay separately under matched
schedules. One logical receipt does not prove bounded physical calls after repeated input expansion.
Do not deduplicate distinct same-application work occurrences targeting the same lineage.

Measure topology rows, decoded content bytes, source calls, repeated Contracts hashing/verification,
host evidence bookkeeping, WAL and round trips separately. The large connected-DAG case must prove
which dormant branches remain unopened and the actual scope/partition algorithm's cost. Complete
authenticated SCC membership/metadata authority remains mandatory. Managed bodies and exact proof
payloads may be loaded on demand where the owning API permits it; missing authority cannot be
treated as a smaller component. Measure that distinction rather than requiring every body resident.

The EQ1–EQ8 variants in [validation](11-validation-plan.md) require intermediate observation,
buffered-effects control, per-patch/net-zero updates, FIFO admission, three-level original routing,
source reuse/gas, creator boundary and failed-input recovery. Exact constructors/evidence derivation
remain obligations. A self-hashed flat batch or protective hold does not prove them.

## Required vectors

| ID | Vector | Required result |
|---|---|---|
| GE1 | same managed graph, eager versus lazy/durable | independently derived exact committed trace equality |
| GE2 | Child → Parent → Root event | complete causal propagation, local commits, composed paths and multiplicity; no global ancestor transaction |
| GE3 | nested Handler emits another event | current Contracts work/event order |
| GE4 | shared source across 1/10/100/1000 parents | independent source commit; no parent-body hydration; full fanout; slow/failed parent isolation and measured calls |
| GE5 | independent local applications, including shared source observers | exact local boundaries; reversed physical commits preserve IDs/history; complete causal aggregate |
| GE6 | live R1, later replay R2/R3 | isolated target applications and settlements |
| GE7 | empty live surface | typed no-recipient decision, no empty Contracts closure |
| GE8 | replay candidate has no target delivery | cursor-only decision, no fabricated gas |
| GE9 | self-cycle, A↔B, branching cycle | no global visited shortcut; exact invocation gas |
| GE10 | SCC/ancestor changes and same-epoch rebind | complete exact proof and correct epoch rule |
| GE11 | availability versus identity-bearing evidence retry | correct application/InvocationId behavior |
| GE12 | source commit, crash after17/1000 Orders, ACK loss and local failure | source/successful siblings authoritative; no duplicate/lost obligation; healthy progress and honest failed aggregate |
| GE13 | initial chronological history and same-cause source+parent | direct child and shadow observations separately; complete original-entry order/count; no future-state leakage; grouping independently proved |
| GE14 | changing dependencies, including historical E20 removal/retarget with B@30 retained | no artificial deadlock, silent partition merge or future activation shortcut; exact retirement/new occurrence; stale old-suffix work cannot reattach or misdeliver |

| GE15 | EQ1–EQ4 source observation controls | preserve actual intermediate/final read boundaries, updates and FIFO admission |
| GE16 | EQ5 ordinary versus managed chain | original E reaches entitled ancestor without a required new Parent emission |
| GE17 | EQ6–EQ8 reuse, creation and terminal failure | materialization-invariant gas/authority, creator atomicity and positive repair |

The planned acceptance includes the feedback, eventless, noninterference and endless-computation
traces from [17](17-causal-processing-model.md#8-mandatory-implementation-spike-traces), with cold cache,
fresh JVM, page sizes 1/2/default, reversed claims and relevant two-worker schedules. The current
readiness record, rather than this catalog, determines which runs have actually been completed.
Define and verify observation evidence, origin/admission, grouping, failure continuity and the actual
feedback atomic scope before dependent implementation. Unsupported cases
must be explicit before publication, not silently dropped from comparison; ordinary same-scope
removal/retargeting is an intended requirement, not an automatic exclusion.
