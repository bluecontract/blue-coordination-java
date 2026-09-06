# Risks and implementation gates

> **Status:** PROVISIONAL / EXPERIMENTAL · **Revision:** 15.12
> [Validation](11-validation-plan.md) · [First loop](10-poc-delivery-plan.md) · [Review package](review/README.md)

## Decisions versus outstanding proof

We have selected the architectural direction: deterministic independent-lineage processing, atomic
local operations, committed source receipts, asynchronous bounded consumer catch-up, durable MyOS
authority and lazy exact reads. [18](18-independent-lineage-processing.md) replaces the earlier
all-connected atomic default; Phase1 changes the inspected baseline's broader boundary where required.
We have **not** proved every next-action/grouping/activation rule by writing document 17 or compiling
the reference Java. Phase1/2 passed their scoped integration-readiness checks; actual results and
limitations are in [the readiness record](implementation/phase-1-2-readiness.md), not inferred from
these gate descriptions. This is not formal all-catalog G1/G2 acceptance. General integration and
performance proof remain [Phase3](25-phase-3-integration-plan.md) work.
[22](22-processing-kernel.md) has highest precedence and selects the kernel, canonical source birth,
composed reaction origin, SCC ownership and terminal-import alignment. [20](20-decision-traces.md)
contains the concrete traces. [24](24-phase-1-library-summary.md) records implemented changes;
the broader risk table below remains a validation checklist, not a blanket test-pass assertion.
A resource/evidence hold protects safety; it is not a passing substitute for a required positive case.

| Risk | Required proof or decision before the dependent implementation |
|---|---|
| Initial FULL_HISTORY sees a source's future | S10/P20/S30 records 1; correct same-original-cause grouping/order/gas is independently specified. |
| A copied parent value hides an incorrect historical child read or a skipped original event | Assert direct child and event-derived observations separately, plus the full original target-entry subsequence exactly once, including equal-valued T3/T7 observations. |
| External history changes an embedding while old source history remains pending | Specify A@T20 remove/retarget after B@T10 without importing B@T30 early; retire only obsolete occurrence-owned work. Scope Contracts reconciliation and Coordination eligibility explicitly; do not silently reduce FULL_HISTORY to read-only replay. |
| Inherited old `sourceOrder` makes a later effect appear in earlier history | Accept equal/decreasing provenance across contiguous epochs; prove producer-visible prefixes, including K100 importing E10 excluded at cutoff 50. Never infer visibility from `sourceOrder` alone. |
| Frozen source target rejects valid feedback or never activates | Final/non-final feedback traces account for authenticated owned receipts; unrelated future input remains excluded. |
| Removing blanket phases permits another ordering defect | Scoped prerequisite/next-action algorithm passes interacting-cohort and nested-history traces; same-root ownership is not blanket readiness. |
| Merely sharing a child couples all consumer gas/failure/commit scopes | Source commits once; read-only consumers commit independently. Prove a neutral shared child does not force a merge; separately expose feedback/shared-write/cyclic-identity boundaries. |
| Cache state chooses initialization/gas | Fix exact input, authorized source history and logical invocation first; compare warm/cold/eviction/concurrent materialization. Derive gas, cause and ownership, including creator failure. A frozen candidate policy or unique row is not equivalence proof. |
| Admission policy is confused with cache discovery | Canonical source FULL_HISTORY retains E15 for first materialization atT10 orT20. FROM_NOW/FROM_FRONTIER select observer history by explicit library change; never source birth by physical first writer. |
| Final-after replay loses observable source steps | EQ1 triggered [1,2], EQ2 buffered [2,2], EQ3 per-patch/net-zero updates and EQ4 FIFO admission distinguish sufficient evidence from a final snapshot/flat batch. |
| Pages or invented pin barriers choose observations | Verify22's ordered per-placement synchronous updates and causal enqueue interpreter. Keep complete membership and gas/rollback; do not replace them with all-pins-first. |
| New placement work is deferred outside its required creator operation | Freeze original direct recipients without suppressing creator-local initialization/caused work. EQ7 proves reads, rollback, retirement and legitimately later attachment. |
| Managed factorization drops ordinary ancestor delivery | EQ5 A→B→C original E reaches A without B re-emitting. Prove exact routes, frozen recipients, views, event identity and admission order; a committed Parent relay is not a proven solution. |
| E1 removal suppresses a baseline-frozen E2 delivery | Preserve valid within-operation frozen delivery semantics; separately apply retirement to later inter-operation work, not a blanket latest-index filter. |
| Historical import reopens an already settled original cause | K100 importing closed E10 and emitting new F owns F's completion obligations; E10 is provenance, not a reopened completion owner. |
| Failure either fabricates successful state or prevents repair forever | Prove EQ8 for gas and certified semantic RUNTIME_FATAL: r1 keeps view0/no epoch; r2 aligns0→before1 with metered updates, then interprets original1→2. Continuity accepts exact legitimate mixed-operation dispositions too; alignment may fail. No r1 event replay, forged receipt, generic exception consumption or usable result from failed initialization. |
| Passive cyclic representation becomes synthetic business feedback | Authenticate the allowed same-epoch representation companion, with no new business receipt/event/fan-out or handled-input advance. Do not forbid this narrow exception or generalize it to unauthenticated state-only imports. |
| Frozen global facts cause permanent conflict | Disjoint predecessor changes allow scoped re-evaluation under a proved noninterference check. |
| Moving proof/ingress heads starve an already eligible cause | Stronger guarantees and above-boundary appends preserve accepted immutable coverage; genuine reservation/auth/scope changes still fence attempts. |
| Locality claim hides all-parent preload or a complete-component scan before source commit | Bounded discovery pages and exact source receipt views; measure metadata and bodies separately, including N=1000. |
| N-consumer scaling hides a huge single-consumer group | Vary P placements separately at 1/2/10/100/larger supported value and an operational-cap control. One group cannot split gas by page; insufficient host capacity is an honest hold, not semantic failure or an assumed successful retry loop. |
| Source runs ahead of unprocessed attachment/removal history | Bind discovery to semantic activation intervals and completeness frontiers; T30 source plus T20 removal yields no stale T30 delivery. Current index emptiness is not closure proof. |
| Earlier accepted but not materialized Roots disappear from membership proof | Lifecycle coverage includes accepted admission/registration work; fenced source-history/live handoff has no gap or duplicate under append/discovery races. |
| Crash after source commit loses fan-out | Commit recoverable discovery basis with source receipt; crash before discovery and after 17/1000 consumers; healthy completed prefixes survive. |
| One slow or failed Order stalls source and siblings | Independent local cursors/fences/gas/failure and parallel worker tests; only real semantic dependencies block. Aggregate cause accounting is not a scheduler barrier. |
| Receipt GC discards evidence still owed to a lagging or undiscovered consumer | Keep receipts until semantic membership/discovery closure and all entitled obligations are proved accounted for; retention is not only a latest-head cache. |
| Eventless history is discarded | Preserve current authenticated source application/result/companion/gas evidence; reject only unproved shortcuts. |
| Feedback is split into endlessly renewed budgets | Actual workflow feedback has one required atomic scope/shared gas. Only genuinely separate future logical invocations may have separate budgets; physical receipt hops cannot create them. |
| Same-entry dynamic edges introduce gas/scope circularity or join after publication | Apply22 §2.1: frozen pre-origin order, local admission-check charge, then distinct-group sum under fixed L before mutation. Acceptance retains gas/ownership; rejection is typed AtomicScopeGasAdmissionFailure for the initiating group only. No retroactive shared-meter replay or first-touch merge. Test60/30/1 versus60/50/1, multi-work fences, one-way1000 Orders and later-entry controls. |
| Joining rewrites earlier event identities | Stable execution-seed/local occurrence IDs precede joining; final settlement identity is separate. Re-entry preserves canonical IDs and rejects speculative output, not reanchors earlier emissions around a discovered group. |
| Operational or implementation exceptions are consumed as semantic runtime failures | Phase1 narrows current handler/BEX catch boundaries. Only reviewed deterministic RUNTIME_FATAL categories are terminally consumed by ordinary managed imports; cancellation/I/O/timeouts, unknown RuntimeException and JVM Errors remain non-consuming operational/implementation failures. Preserve actual gas and portable diagnostics, not arbitrary exception text. |
| Host quota changes a deterministic result or silently skips pending history | Needed80/limit100/allowance50 pauses without a processor disposition. Replenishment/retry preserves exact result and one settlement. Bound repeated valid imports without inherited semantic fuel or a false claim that later repair is eligible. |
| Shared eager/lazy bug passes differential | Independent hand-derived traces plus ordinary/managed common-law comparison; do not exclude the routing/observation mismatch as merely different input. |
| Stable application identity collides or duplicates after replan | R1/R2/R3, evidence growth, policy/environment mutation and create-only settlement tests. |
| Readiness/failure cleanup loses ownership | Exact local failure ledgers and remaining obligations; healthy work continues and aggregate cause success cannot hide a failed/pending consumer. |
| Hash/fence is mistaken for query completeness | Trusted query contract plus raw-state index audit, including coherent omitted rows with recomputed hashes. |
| Resource already exists but waiter sleeps forever | Supply/register race, storage-before-notification crash and no-hint recovery through bounded durable rechecking. |
| Successor body unavailability delays an already complete predecessor | Proved selection plus missing execution-only M2 body permits M1's atomic commit and durable M2 suspension; missing current-result/selection evidence still holds. |
| A generic nonnegative codec rejects valid signed declaration order | Preserve declaration `order=-1,0,1` through routing/cold/PG tests without permitting negative platform counters or changing Blue content domains. |
| Commit uncertainty duplicates semantic apply | Reconcile under work fence, not an unfenced absence lookup; receipt and full state/progress/outbox remain atomic. |
| Outbox or progress data causes quadratic host work | Immutable definitions once; indexed successor and deltas; B/2B, A/2A and fixed-A multi-commit owned-work growth counters, including full serialization/WAL. |
| Restart repeatedly verifies growing processor input | Separate unavoidable/current processor work from avoidable host copying; optimize based on measured evidence. |
| Normal small graph remains slow | Preregister seconds-scale targets and measure PostgreSQL end-to-end, not RAM-only control. |
| First-application timing is mislabeled or fan-out growth becomes a global latency promise | Freeze source/consumer measurement scope. Optional aggregate reporting joins discovery and dispositions, not a mandatory global receipt. Total fan-out time may grow with N; independent source latency, bounded resources and fair progress remain required. |
| Ready-work discovery scans the platform or every active user document | Transactionally maintained historical dependency, ready-work and reverse-wait indexes; concrete PostgreSQL queries/plans at millions of documents and thousands active per user, plus 1k/10k/100k fan-out and overload/drain. |
| Censored short ages fabricate a passing p99 | Lower bounds cannot prove an upper-bound PASS while unresolved tails could violate it. Keep incomplete quantiles inconclusive; include 98 fast plus two late-running samples as a negative control. |

These are localized proof obligations, not reasons to design a second specification profile or an
entire production distributed system before starting.

## Gate definitions

These are implementation acceptance gates, distinct from the user's authorization to start Phase1/2
in parallel. [23](23-first-implementation-execution-plan.md) controls execution. The retained r15.2
experiment does not pass G1; actual new results are recorded separately, not inferred from these docs.

### G0 — Reproducible local baseline

The current Language/Contracts, BEX and Coordination commits/patches build together. Record exact
host/library provenance and fixtures. No release, migration or final-version requirement.

### G1 — Supported semantic slice

[Phase 1](10-poc-delivery-plan.md) proves chronology, source/consumer local scopes and activation,
scoped re-evaluation, target settlement, failure ownership, eventless evidence, exact environment,
gas/queue order, identities and lazy retry. Unsupported interactions are explicitly detected and
not presented as successful equivalence.
This includes initialization reuse, operation-kind-specific failure/recovery, complete consumer
groups, EQ1–EQ8 observable-step/routing/reuse/creation/recovery controls, import-produced work
ownership and passive-cycle controls before
dependent semantic host integration; independent host mechanics proceed in parallel. Existing live-loop recovery does not prove recovery from
a failed managed-receipt import.
Recognized semantic runtime-fatal continuation cannot ship before exception classification is fixed.
Dynamic-join proof includes accepted and rejected atomic-scope admission, stable earlier event IDs,
authentic locally charged gas and independent one-way source controls; replay under a retroactively
shared budget is not an acceptable substitute.
The E1a subexit may establish a smaller initial slice, but it does not award G1. G1 still requires
the named CORE positive feedback/cycle fixtures. Explicit unsupported boundaries apply outside the
required positives; they do not convert a missing positive into passing equivalence.

The selected next-action algorithm and its comparator must be executable. DTO shapes may change
to fit that algorithm. Reference compilation and prose review alone do not pass G1.

### G2 — Target-shaped host

Real PostgreSQL, reused MyOS Timeline invariants, complete indexed reads and semantic membership
frontiers, per-lineage/occurrence fences, cold reconstruction, atomic local commit, work-fenced reconciliation, bounded ordered publication,
level-triggered recovery and authorization/cache isolation pass their host tests.
Concrete historical-dependency/ready-work/reverse-wait indexes and query plans prove efficient work
selection at the stated corpus/user scale; source commits retain durable propagation responsibility
without enumerating all recipients. Bounded transactions/pages/concurrency and fairness/backpressure
are host acceptance properties, not optional post-POC optimizations.

### G3 — Integrated correctness

Applicable A–T scenarios and mandatory manifest rows pass with independent expected semantics.
Auditor finds no duplicate application, cursor gap, partial invocation, omitted graph route,
orphaned obligation, leaked reservation/readiness or coherent index omission.

### G4 — Performance evidence

Preregistered decisive run meets its scoped source/consumer latency, throughput/error/amplification
and bounded-resource/fairness budgets. No universal all-N seconds-scale fan-out target. Report normal,
cold and fault paths separately. Correct but out-of-budget is a performance failure, not overall PASS.

### G5 — Next iteration

Classify results by owning layer, choose the next changes and rerun affected gates. Phase 3 is
expected to lead here, not to a final public Coordination API.

## Deliberately deferred

- Production deployment, releases, data migration and rolling compatibility.
- Multiple specification profiles.
- Dynamic multi-provider membership; preserve exact Timeline identity now.
- Portable mid-invocation continuation.
- Administrative semantic skip/abort and distributed cross-domain commit.
- General in-band interruption/repair of an endless sequence of individually successful attachment
  imports. Host quotas must contain it and preserve pending authority, but need not make later repair
  eligible through a never-ending earlier lane. No cross-operation semantic fuel is selected.
- Final Java module/type hierarchy, wire schema, sharding, HA/DR and production GC.

Receipt retention until owed deliveries are safe is a POC correctness requirement, not deferred
production GC. Feedback/shared writes/cyclic identity use22's selected owned scope and atomic
projections, not all-observer coupling. An unsupported implementation boundary must be detected and
cannot receive a passing equivalence result; required positive cases still have to work.

Do not defer a semantic defect merely because its code lives in another library. Conversely, do not
expand the POC to implement all foreseeable extensions before a scenario needs them.

This explicit liveness limit is not another blocker for starting the first POC. Required finite
gas-failure→later-detach recovery and dynamic same-origin joins are still in scope. Additional
administrative cancellation semantics belong to a later, evidence-driven iteration if needed.

## Review questions

Can the proposed implementation show, not merely name, the next legal action? Can a tiny trace
distinguish its answer from the wrong one? Can every wait name an external resource or a legal
internal releaser? Can a complete invocation commit without all its effects, or a root complete with
an obligation left behind? Can input arriving later in wall time change an earlier result?

Finally: is the cost proportional to genuinely needed processing, or did a whole-history/whole-graph
operation move behind a cleaner API? The measurements must answer this separately for processor,
topology queries, content loading, mutable bookkeeping and publication.
