# Initialization, receipts and recovery: precise edge contracts

> **Design baseline:** 15.12 · **Status:** semantic contract synchronized with Phase1; Phase3 integration pending
> [Independent lineages](18-independent-lineage-processing.md) · [Current source evidence](review/revision-15-7-source-evidence.md) · [Scenarios](09-scenarios.md)

This document narrows the independent-lineage proposal under the highest-precedence
[22 — Selected processing kernel](22-processing-kernel.md).
[20 — Diagnostic decision traces](20-decision-traces.md) preserves cases, not r15.6's withdrawn
all-pins, post-commit-new-placement and compulsory-relay rules. Correct Contracts semantics remain
authoritative; required corrections belong in the owning library, not PostgreSQL-specific workarounds.

## 1. Initialization is not a cache miss

Three different costs must not be confused:

| Logical action | Accounting rule |
|---|---|
| Initialize authored Agreement X | Gas and identity under its exact authorized initialization/history basis; canonical reuse is implemented in Phase1, with the verified scope in [24](24-phase-1-library-summary.md). |
| Order attaches/imports X and reacts | That Order's own deterministic verification, reference-update and reaction charges. |
| Host fetches or reconstructs missing material | Physical work/latency metrics; no semantic rebirth or extra initialization charge. |

For the same exact input, accepted history, logical ownership/execution policy and environment,
warm cache, cold cache, eviction, reconstruction and reversed workers preserve results and gas.
For genuinely independently committed source work, its original `admittedGas` is not charged again
as consumer execution. Reuse within the same logical invocation still preserves its required source
charges. This grants no cache credits or claim that every import is cheaper than every initialization.

Baseline NEW_AUTHORED activation initialized in the introducing invocation. Its work, events, gas and
rollback were owned there. Reusing a retained source and importing its receipt was another baseline
invocation path with different receipt/cursor and epoch/identity evidence. Equal authored bytes do
not prove those paths interchangeable. This distinction must be visible in the paired reference.

A fixed `sourceInitializationExecutionPolicy`, non-authoritative preparation and idempotent creation
publication implement the selected canonical-origin rule in22. Source initialization/history is
source-owned and uses FULL_HISTORY independently of its first observer. Independent source80 and
parent20 under separate limits90 succeed cold and warm; genuinely coupled workflows retain one meter.
This deliberately changes current NEW_AUTHORED ownership rather than renaming its old gas partition.

For equal accepted semantic inputs, two workers may recompute evidence without changing its meaning.
A cache hit cannot waive logical work or a cache miss create another semantic birth. A failed
introducing operation publishes no authoritative orphan child. Reusing already authoritative X does
not erase its accepted admission. If a candidate/companion is retained, it must authenticate X-owned
initial events and commit authority, not invent Parent re-emissions. Standalone admission needs no
fictitious parent. No private partial processor queue or generic genesis framework is introduced.

FROM_NOW and FROM_FRONTIER are observer attachment selections in the selected target, an explicit
owning-library change from current birth-at-attachment semantics. They do not change X's canonical
source history: X still contains applicable E15 whether its first observer appears atT10 orT20.
Compatible concurrent publication reuses the same canonical results; conflicting origin/history
evidence is rejected. A failed creator publishes no new source authority/events/charges, while an
already independently admitted source survives. See the selected [BIRTH trace](20-decision-traces.md#d-initialization-policy-and-publication-birth-selection-remains-open).

Evicting reconstructible material is not deleting history. Rebuilding from retained authoritative
evidence preserves initialization and charges. Losing that evidence is data loss, not permission to
treat an absent row as a never-created document.

## 2. Failure has several independent progress coordinates

| Operation/outcome | Handled original input | Business state/epoch | Successful source-import cursor |
|---|---|---|---|
| Successful external operation | Its defined terminal disposition | Exact Contracts result; no invented epoch | Only actual successful import effects, if any |
| External gas failure with consumed-input disposition | May advance | Rollback; failure gas/diagnostics retained | No fictitious successful import |
| Managed receipt import with terminal GAS_LIMIT_EXCEEDED or recognized semantic RUNTIME_FATAL | Consumes that delivery, not an unrelated external input | Rollback; failure retained; no successful epoch | Stays at the exact last successful view |
| Operational resource wait | No semantic settlement | No tentative publication | No advance |
| Representation-only cyclic rebind | Not a new external business input | Preserve the specified same-epoch rules | Only the defined representation projection; no fabricated receipt |

Historical replay of an original external input remains an external-input operation. Historical
receipt import remains a managed import. The word “historical” alone cannot select failure policy.
There is no configurable universal `ADVANCE_LINEAGE` after any processor status.

Baseline live-loop recovery is a useful control: A/B at epoch0 receive a looping input; gas failure
preserves state and epochs. A later detach input succeeds and breaks the cycle; a **new** operation
can then succeed. This is neither continuation of the failed private queue nor a changed result for
the identical input/pre-state/environment. The MyOS command can be handled while its entry result is
GAS_LIMIT_EXCEEDED; these are different dispositions.

**Selected continuation:** terminal `GAS_LIMIT_EXCEEDED` or recognized deterministic `RUNTIME_FATAL`
consumes that exact managed operation and
its owned deliveries, without a successful import or epoch. Then select the next canonically due
operation, not mechanically the next receipt in a SQL page. Successful later operations may produce
their normal business effects. There is no stop-at-first-failure rule for a physical page or historical
range, and no permission to discard that range's remaining work. Live and historical delivery use
the same rule; an import range is not one transaction or one gas budget. Other processor statuses
retain their exact operation-kind disposition; no general consume-on-error policy is selected.
The library must distinguish reviewed semantic failures from unknown exceptions, IO, timeout,
cancellation and internal bugs before this law ships. Operational failures never consume input.
Failed initialization still creates no usable source; see22 §6.2.

**Uniform alignment for a successor after failed deliveries:** authenticate the original contiguous
source history, the consumer's actual successful reference/cursor and every intervening terminal
delivery disposition, including lawful composite external-policy failures, not only gas failures.
Validate exact original kind/policy/classification and shared settlement. A nonterminal gap rejects.
The next import owns these steps in one invocation:

1. If the actual consumer reference differs from the next receipt's exact source-before value,
   align it to that exact source-before value through a normal containing-reference Document Update.
   Complete its required synchronous continuations and retain their ordinary FIFO/lifecycle effects.
   This is real, metered consumer work of the new operation, not a silent resolver override.
2. Replay the selected receipt's authenticated observable transition sequence from that source-before
   value, preserving its update/event/read boundaries and the existing queue. Do not rerun source
   business handlers or enqueue original events from the failed preceding receipts.
3. Commit the complete result only on success. Alignment and replay share one gas ledger and rollback;
   alignment has no separate commit, consumer epoch or successful imported-source cursor.

For consumer r0/value 0, failed r1:0→1 and next r2:1→2, this means an observable consumer alignment
0→1 followed by r2's required 1→2 transition, all within one consumer operation. This intentionally
replaces r15.8's simple direct 0→2 rule. The unchanged r2 receipt/event still describes its original
source history. Alignment creates no fake source receipt or successful consumer r1 epoch. It can
invoke the same containing-update handler that ran unsuccessfully before, but as **new r2 work**:
neither r1's source events nor its failed private continuation are replayed.

The rule also covers an r2 event that observes source1 before a later change to2: the event sees1.
An r2 with no business change still aligns to its exact source-before value before replaying its
defined work. Ordinary successful contiguous catch-up adds no alignment update when the reference
already matches; preserve its normal operation boundaries and every required intra-operation
observation. Never replace this with final-state-only or multi-receipt batching.

Alignment itself can exceed gas, including when moving to1 triggers the same authored loop again.
The whole r2 operation then rolls back to actual view0, records its own terminal gas disposition and
allows selection of the next due operation. Continuing the ordered history guarantees accounting and
forward selection, not business success of later inputs. A later detach D@T30 can run only after
earlier due deliveries have terminal outcomes; it cannot overtake unresolved r2 or erase its work.
Conversely, a pending later receipt cannot block an earlier canonically due external operation. If
r1@T10 failed and the next input is a local read@T15, the unchanged embedded reference reads0, not
the independently committed source1; no successful cursor1 or silent refresh is required. Missing
data and provider/resource waits are nonterminal and do not permit advancement.

This extends the baseline importer and its broad CATCHING_UP gate. Phase1 implements exact failure
evidence, successor alignment and constructor validation; general durable integration remains. Keep
the ordinary beforeBlueId guard: the explicit continuation validates the failure gap and performs
the alignment instead of bypassing that guard in MyOS. Agreement and healthy Orders remain
committed under a proved independent boundary. Real atomic cycles still have one shared gas ledger
and one complete rollback; this independent-consumer rule does not split their work.
See [20E](20-decision-traces.md#e-failed-r1-pending-r2-later-detach).

## 3. One source receipt exposes a committed revision, not private snapshots

This heading describes what the baseline final receipt contained, not a claim that it contained every
observable transition. r15.6's universal final-after visibility rule is withdrawn.

If an original Handler queues E1/E2 and Source Triggered E1 sets x=1 while E2 sets x=2, ordinary
ancestor delivery observes [1,2]. Importing only final2 and the flat batch yields [2,2]. Source
Document Updates 0→1→2, or 0→1→0, can also cause intermediate parent reactions that a final-only
rewrite loses. Conversely, one Handler may finish its patches to2 before its emitted events drain;
those events may correctly observe2. The logical Contracts boundaries decide, not an assumed
snapshot per emission or one final pin for the whole operation.

The proposed reuse path therefore needs minimum authenticated observable transition evidence:
required exact intermediate views, updates, event FIFO/provenance, frozen delivery authority and
continuity/final-result proof. Compute source work once and retain enough to reproduce consumer
observations without rerunning source handlers. [22](22-processing-kernel.md) selects the observation
program, causal enqueue sites and interpreter; codec/constructor implementation and conformance remain
library work. This is not a public VM trace framework or a flat receipt FIFO.

Across two source operations, x=1 atT10 and x=2 atT20 still require a parent's originalT15 input to
see1, even if T20 is stored. Keep this chronological control beside the within-operation controls.
In the simple two-Timeline-input chain, Source n+1 and n+2 cause Parent's separate y=1/F1 and y=2/F2
results: Root's corresponding F1/F2 reads are 1/2. By contrast, if E1 and E2 are internal events already
queued within one operation, Parent's resulting F1/F2 join behind E2. FIFO E1,E2,F1,F2 then allows
F1's later document read to see y=2, although its immutable value 1 payload stays 1. Internal events
do not independently create epochs; document reads and event payloads must not be conflated.

One inherited reaction origin/position × owned consumer scope is one complete operation. Compose its
converging producer projections, own direct seed and eligible placement set under22, preserving
cursors and routing multiplicity rather than making each received receipt another operation.
Paging cannot choose gas/rollback granularity. Same-target fan-in cannot be ordered by host scans or
an arbitrary path/hash sort. [20A](20-decision-traces.md#a-one-receipt-two-placements-all-read-pins-first)
withdraws all-pins-first and retains mutation-sensitive alias/dispatch tests instead.

A new occurrence cannot join an already frozen direct external recipient set. That restriction does
not postpone required initialization or caused update/event work until after creator commit.
[20B](20-decision-traces.md#b-creating-a-placement-while-consuming-a-receipt) withdraws the unconditional
post-commit successor rule. The selected rule makes initialization/installed-view observations
synchronous and historical epochs separate ordered catch-up operations. A creator reads its actual
installed view; later converged use belongs to a later operation, never a wait for its own commit.
Rollback or create-then-retire leaves no orphan live work.

Preserve frozen event-delivery authority. Current Contracts queues imported
events with the captured binding before draining them and recognizes specified retirement successors.
Therefore “E1 removes the path, so always drop E2” is not the rule. A frozen E2 may remain valid under
the current exact successor rules; current handler matching is a separate question. Retirement does
exclude obsolete **future** operations. Verify E1 mutation/E2 delivery/handler selection together,
including reference-update work, rather than imposing a new unconditional event-major ordering.

## 4. Provenance does not own completion

E10 finishes and its cause is closed. At K100 an Order attaches historical Agreement and imports E10.
That import emits a genuinely new event F. Its original source provenance can still name E10, but
the new import/F obligations belong to K100's completion scope. E10 is not reopened. Already closed
source work reused as a prerequisite is not newly counted as work owned by K100.

Bind the completion owner explicitly in durable intent/obligation evidence and propagate it to new
effects. Do not infer it solely from `originalCauseIdentity`, SQL transaction order or the oldest
dependency. Fan-in may read several completed producers without transferring their completion scopes.
Publication records preserve original observations separately from newly emitted F. The logical
queue remains authoritative: earlier reference-update G and already queued E2 cannot be displaced
by a later F. [20C](20-decision-traces.md#c-nested-relay-is-not-a-new-emission) now records the ordinary/
managed ancestor-observation gap; it does not require an intermediate-result relay companion or a
per-hop cursor transaction. Minimum durable evidence must prove original provenance, composed paths,
required read views and delivery authority without manufacturing new Parent emissions.

Identity construction must be acyclic: derive semantic operation/transition identity from its exact
inputs and predecessors before forming an output receipt that refers to it. Do not define
`producingSemanticTransitionIdentity` as the hash of the very receipt containing that field. Reuse
existing owning-library constructors where possible; no speculative stack of public hash wrappers.

## 5. Passive cyclic representation is not a business loop

A reference-only A ↔ B change must not create endless synthetic business epochs merely because each
member's exact reference contains the other's changed identity. Baseline already has joint cyclic
exact-value finalization and same-epoch representation rebinds that create no new business receipt.
Preserve and extend that distinction; the component finalizer does not execute business handlers.
The representation companion belongs to the triggering complete result/delta and binds required
members, before/after values, unchanged business epochs/source-receipt positions and finalization
proof. Publish that required representation scope atomically; create no new business receipt,
synthetic event/fanout or gas reset. 'Every revision has a receipt' means new business/source-history
revisions, not this verified representation-only exception.

Three counters must be observed separately: business execution, representation finalization and
new retained business receipts. A passive-cycle fixture must stabilize the exact representation
without a reference-echo receipt loop. A genuine cyclic workflow retains one logical invocation,
shared gas and atomic rollback. Fresh meters or delayed Timeline rounds are not equivalent. Equal
event values explicitly re-emitted by handlers are distinct occurrences; a global visited set is
not a substitute for metering their real work.

The selected owned workflow scope is the complete SCC at the logical cut, expanded monotonically
for new returning dependencies. Independent observing Orders outside that scope do not join it.
Representation-only rebinds retain their narrow same-epoch rule. Active bindings and historical pins
need not describe one physical-head graph;22 governs exact cut/readiness and atomic projections.
Mixed-cut cyclic conformance remains mandatory: a DAG test alone does not prove implementation.
