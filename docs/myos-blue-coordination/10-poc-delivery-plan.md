# First POC delivery plan

> **Status:** PROVISIONAL / EXPERIMENTAL · **Revision:** 15.12
> [Scenario cards](09-scenarios.md) · [Validation](11-validation-plan.md) · [Gates](12-risks-and-review-gates.md)

## Objective

Plan the smallest credible test of deterministic graph processing with PostgreSQL-backed MyOS Mini,
without an authoritative fully resident document runtime. The user has authorized Phase1 and Phase2
implementation and automated verification in parallel. [23](23-first-implementation-execution-plan.md)
defines agent ownership, incremental tests, supervision and readiness for Phase3. Phase1/2 have now
passed the scoped [readiness checks](implementation/phase-1-2-readiness.md); [24](24-phase-1-library-summary.md)
records delivered changes and refinements. The sections below retain the original phase scope, not
a claim of formal all-catalog acceptance. [25](25-phase-3-integration-plan.md) is the current next-step plan.

The first loop has three phases. Phase 3 is expected to reveal changes that send us back to Phase 1
or 2. No release, migration, compatibility profile or production-readiness claim is required.

Implement [22](22-processing-kernel.md)'s selected independent-lineage kernel:
historical visibility, composed reaction origins, identity/gas, activation and cyclic scope. Earlier
outcomes are historical, not authority for superseded grouping. The first implementation verifies
the selected rules; DTOs or whichever implementation passes first cannot choose different semantics.
The retained r15.2 experiment is historical auxiliary evidence, not the current Phase1 result.
[21](21-semantic-equivalence-and-source-reuse.md) records the current equivalence/source-reuse
obligations. Earlier concrete pin/relay/creation shortcuts are withdrawn, not implementation defaults.

## Phase 1 — Libraries and semantic foundation

### 1A — Tiny executable counterexamples first

Run isolated in-process unit/integration tests using the
actual current Coordination and Language/Contracts implementations. Independent PostgreSQL host work
can proceed in parallel, but cannot claim these semantics before library proof. Independently specify the small
expected traces, expose current failures and repair them in the owning library. A mock processor,
model-only simulation or final-state assertion does not establish the semantic foundation. Make
[17](17-causal-processing-model.md)'s proposed laws concrete:

1. **Chronological initial history:** S10/P20/S30 under C40 records 1 at P20, both through a direct
   read of the embedded child and separately through the parent's event-derived field. For attachment
   T1, a direct T3 child read sees initial 0. Assert the complete original-event subsequence exactly
   once, including T7 in the T10-attachment case; equal values cannot hide a skipped event.
2. **Same original cause:** independently specify the source and parent local applications, exact
   source-receipt reuse, order between direct and derived inputs, multiplicity and local gas. Current
   connected-closure identities/rollback are source facts to change, not the target oracle.
3. **Dynamic attachment:** B emits `CounterChanged(5)` at T5; A's handler sets `counterB`. Attachment
   at T10 leaves A unset before T10 and sets it to 5 through that attachment's catch-up. Attachment
   at T1 sets A to 5 in response to the T5 event. Assert the different histories despite equal final
   values, preserved T5 provenance, and unchanged semantic attachment time under late reconstruction.
   A later attachment is not initial FULL_HISTORY; missing content is not missing historical progress.
   Add historical A@T20 removal and replacement of a still-pending B occurrence after B@T10 but
   before retained B@T30 is due. Retire only obsolete occurrence-owned work, preserve independent
   obligations and derive the replacement's own declared history. Do not import B@T30 early to make
   the old occurrence active. Coordinate any required Contracts reconciliation change explicitly.
4. **Causal source feedback:** final/non-final imports, eventless receipts, target advancement and
   unrelated future exclusion require an explicit local-scope rule. Feedback/shared writes/cyclic
   identity questions remain visible in [18](18-independent-lineage-processing.md); an unsupported
   case is not automatically repaired by putting every connected document in one invocation.
5. **Independent consumers:** Agreement commits before Orders, including crash before fan-out
   discovery and after 17 of 1000 Orders. A slow/unavailable/gas-failing Order neither rolls back
   the source nor strands healthy Orders. Test shared neutral X as valid reuse, not a merge error.
6. **Sharing, exact views and membership:** one source computation, bounded discovery without all
   parent bodies; source T10=1/T20=2 with Order@T15 observing1. Source-ahead T30 plus parent removal
   T20 must not deliver B@30 through the retired occurrence. Cover attachment registration/scan
   races, multiple paths, dropped/duplicate hints, semantic completeness and receipt retention.
7. **Finite and looping feedback:** one logical feedback operation has shared gas and atomic
   effects/rollback. No fresh-budget receipt hops. Separately authorized future operations may be
   paused operationally with their already committed prefix intact.
8. **Provenance versus history position:** two successive consumer receipts at distinct logical
   reaction/attachment positions can inherit the same `sourceOrder`; converging receipts at one
   origin/position compose one operation. An import after K100 can inherit E10. Assert the contiguous receipt chain
   and exclude that K100 effect from a cutoff-50 history; neither timestamp sorting nor fake time fixes it.
   Pair it with the nested positive: C@10 leads initially authored B to record 1 during reconstruction
   under cutoff 40; initially authored A reads B at T20 during reconstruction under cutoff 60 and must
   observe 1. Admission cutoff alone must not hide reconstructed history. Separately prove the scope
   of any new WORK in pre-existing authoritative sources; do not backdate all replay-produced effects.
9. **Initialization authority versus reuse (EQ6):** distinguish authored, initialized and historical
   exact inputs; freeze authorized admission/history before warm/cold/concurrent permutations.
   Derive source/parent invocation causes, rollback, IDs and gas. No cache discount, cache-driven
   rebirth or blindly reused receipt gas partition; a preparation policy is not an equality proof.
10. **Observation evidence (EQ1–EQ4):** triggered reads [1,2], buffered-effects [2,2] control,
    per-patch/net-zero updates and P1-before-F2 FIFO admission. Define minimal authenticated evidence
    and its verifier from these traces before fixing public DTOs or all-pins/group phases.
    Contrast two external entries and their distinct successful epochs (Root sees F1→1, F2→2)
    with E1/E2 emitted within one invocation: Parent sets y=1/y=2 and queues F1/F2 behind E2, so
    later Root handlers for F1/F2 read [2,2], while an immutable F1 payload may still contain 1.
11. **Failure and forward progress (EQ8):** external gas failure rolls back without a successful
    epoch and records a terminal input outcome; later repair works. Define the corresponding managed
    failure frontier separately from successful imported state. The selected proposed extension
    handles r1 `GAS_LIMIT_EXCEEDED` or certified semantic `RUNTIME_FATAL` terminally with no successful
    view/epoch; r2 runs from that state. Tighten current broad exception-to-runtime-fatal mappings
    first: cancellation, I/O/timeouts, unexpected implementation exceptions and JVM Errors must not
    become consumed semantic failures merely because they occurred inside a handler/BEX call.
    For source r0=0, r1=1, r2=2, r2 first aligns the actual consumer pin0→before1 with ordinary
    metered update reactions, then interprets its original1→2 program. Alignment may fail and roll
    back too. Do not replay r1 events or forge a source r0→r2 receipt.
    Prove both-gas-fail-then-D30 repair and ordinary successful catch-up without coalescing entries.
    Preserve exact failed dispositions and legitimate consumed mixed-operation policy outcomes in
    continuity evidence; no blanket rule consumes other statuses. Failed initialization still has
    no usable initialized source. Test typed semantic faults against operational/unclassified failures.
12. **Completion ownership and passive cycles:** K100 imports E10 and causes F; K100 owns the new
    work. Same-epoch cyclic representation must not manufacture business receipts/feedback.
13. **Cross-model routing (EQ5):** ordinary A→B→C original E reaches A without B re-emitting.
    Define scope/identity correspondence and preserve frozen paths/admission order under managed
    factorization; do not validate only two adapters sharing the same immediate-parent gap.
14. **Creator and cycle boundaries (EQ7/EQ8):** new placements cannot rewrite frozen original
    recipients, but required initialization/caused work stays in its creator operation. Genuine
    feedback loops retain atomic scope/shared gas; no fresh budget per receipt hop. Prove source-once
    independent fanout separately under the explicit source/consumer ownership contract.
15. **Dynamic same-origin joining (J-DYNAMIC-SAME-ORIGIN):** E directly targets initially unrelated
    A/B, which request a returning dependency. Freeze pre-origin seed order and one compatible policy/L.
    Before the topology mutation charge check cost c locally; ordinary charge failure creates no join.
    Otherwise admit ownership only if distinct-group consumed gas including c fits L. Accepted joining
    retains all gas/rollback ownership; rejection fails only its initiating current group with semantic
    `RUNTIME_FATAL`/`AtomicScopeGasAdmissionFailure` and actual gas plus required-sum/limit diagnostic.
    Prove A60/B30/c1/L100 accepts at91; A60/B50/c1 rejects B with actual51 and required111/100, not a
    retroactive shared-meter replay. Stable execution-seed/local event IDs survive joining; final
    settlement identity is separate. Fence obsolete proposals and discard failed-producer tentative
    effects from independent consumers. A read before B's seed sees B's pre-seed view. Test workers,
    waits, further joins, one-way1000-Order attachments and later-entry controls; the existing
    A-already-embeds-B cycle test does not prove this admission rule. Ownership is monotone within
    a valid attempt: B failure invalidates the entire conditional AC attempt, including further
    admissions/meters/candidate failures, while retaining B's canonical rejection explanation and
    restoring A/C's still-due own seeds. The AC61+B51=112 example is a required control.

Multi-producer failed-gap recovery aligns at each producer's first canonical Entry, not in an
all-source prelude: RootA0/B0, A1→2 before B1→2 yields B0 in both /a callbacks. Earlier own Root
work sees0/0; alias/retirement and alignment-emission FIFO controls are required.

For each, write the small expected trace by hand before using existing Coordination as an oracle.
Record selected cause, source receipt, observed value, binding activation, invocation boundary, gas,
committed prefix and forbidden alternatives. Explore all practical tiny scheduling permutations.
A failure changes the design/code in the owning library; it is not patched around in SQL.
The reviewed grouping, visibility and activation decisions are verified here, not delegated to SQL
or deferred until Phase 3. Any counterexample reopens the owning design decision explicitly; it does
not authorize silently changing the expected semantics. Database integration later tests that the
established semantics survive durable storage, failures and measured workloads.

**Exit E1a:** precise executable grouping/next-action/activation rules for the supported first slice.
Any excluded interaction has an explicit detected hold/rejection boundary, never a silent semantic
shortcut. A compiling interface alone does not pass this exit.
E1a is an intermediate exit, not a smaller passing G1 profile. G1 still requires the named CORE
positive feedback/cycle fixtures; an unsupported general interaction outside those positives can
remain excluded, but an unresolved required positive does not pass the full library gate.

### 1B — Repair and expose the minimum boundary

Implement the proven orchestration rules in current Coordination, changing Language/Contracts/BEX
only where evidence requires it. Preserve existing in-memory capabilities and reuse valid
target-bearing settlement/retry machinery.

Include exact Timeline identity/order/completeness, target-scoped admission replay, stable application
settlement, lossless source/result/public-event evidence, current gas/environment constructors,
dynamic graph routing/finalization and prospective content-derived initialization.

Preserve signed declaration `order`: the positive routing/codec vector `-1, 0, 1` must survive cold
reads and PostgreSQL round trips. Negative platform counters remain invalid except their documented
sentinels; do not widen unrelated semantic-configuration fields.

The [reference boundary](05-poc-api.md) is deliberately a spike sketch. Replace its placeholders
with the current actual types and pure verification; do not turn every explanatory record into a
public API. Keep source-mediated/cyclic seams where known needs require them, but implement only
the forms exercised by the chosen scenarios.

The current active-observer closure captures source and parents under one gas/rollback/identity scope.
Changing only SQL commits is insufficient: Coordination owns independent live receipt delivery and
selection; Language/Contracts changes are in scope wherever local invocation identities, gas/failure
ownership or historical reference views require them. Preserve valid in-memory capabilities, not
the obsolete all-connected atomic default.

**Exit E1b:** identity/result/local-gas/chronology/lineage-scope tests pass in the library. Eager/lazy runs of the
same managed graph agree with independently established expectations. Known counterexamples are
tests, not waived limitations.

### 1C — Measure the library boundary before broad integration

Instrument source calls, demand rounds, bounded recipient discovery, decoded/hashed/copied evidence and
allocation. Exercise missing-content restart and complete SCC evidence. Add exact-key BEX loading/
coalescing where needed. Avoid exporting Contracts queues or a portable continuation.

**Exit E1c:** a bounded usable prototype contract and local test artifacts, not frozen production DTOs.

## Phase 2 — Target-shaped MyOS Mini (`myos-simple`)

The target is good enough to test real processing, not a replacement production MyOS.

- PostgreSQL authority for lineage state, receipts, cursors, obligations and outbox.
- MyOS Timeline provider mechanisms from [15](15-myos-timeline-foundation.md): row-locked append/
  guarantee, strict microseconds, predecessor chain, idempotency and exact exclusive completeness.
  Do not reuse the old document feeder's comparator.
- Transactionally maintained historical dependency/activation indexes, ready-work selection and
  reverse-wait indexes are primary execution structures, not optional caches repaired by hot-path
  history scans. Commit topology changes with the relevant index/work updates. Independently audit
  their complete-query answers against raw authority.
- Deliver concrete PostgreSQL DDL, representative indexed queries and EXPLAIN (ANALYZE, BUFFERS)
  evidence for recipient pages, exact next work, dependency wakeups and history/live registration.
  Exercise million-document corpora and thousands of active documents per user. A local update must
  not scan the platform or every active document of its user to discover runnable work.
- Per-lineage and occurrence fences preserve canonical local order while independent consumers
  can progress in parallel. Validate cross-scope reads and semantic membership frontiers explicitly;
  no domain-wide serial barrier or mutable revision shared by all independent writes.
- Source state+receipt+recoverable fan-out basis commit without waiting for consumer completion.
  Retain source evidence until all semantically entitled deliveries are accounted for; an empty
  current subscription table does not prove discovery complete or permit garbage collection.
- Temporal lifecycle coverage includes earlier accepted but not yet materialized Roots. Test the
  fenced history-to-live registration handoff with source append, delayed admission and fan-out
  scanning in both orders; no lost interval or duplicate receipt application.
- Immutable definitions once, small progress deltas/cursors and bounded exact successor reads.
- No semantic transaction/lock held through processor execution, external I/O or waiting.
- Complete local invocation effects, receipt, occurrence cursor and output obligations/outbox append
  in one fenced transaction; no source-plus-all-consumer transaction.
- Work-fenced CommitUnknown reconciliation; logical sink dedupe and ordered publication.
- Durable level-triggered resource/obligation rechecks, including supply-before-registration.
- Account/document quota pause and replenishment preserve fixed semantic gas, input eligibility and
  one logical settlement (L-QUOTA-PAUSE). Contain a continuing valid reattachment/import chain without
  exhausting the host (J-AUTOMATIC-REATTACHMENT-CHAIN); do not implement inherited cross-operation
  fuel or a generic semantic cancellation mechanism for this POC.
- Bounded fan-out pages, transaction size, materialization and worker concurrency; fair scheduling
  across users/lineages, backpressure and drain after overload. The host owns eventual accounting of
  every entitled reaction; concrete queue/index/partition choices remain host implementation choices.
- Cold reconstruction and bounded caches instead of whole-runtime restore/replay.

**Exit E2a-T:** Timeline conformance tests in [15](15-myos-timeline-foundation.md), including clocks
and guarantee lag. **Exit E2a:** database fence/idempotency/projection/fault tests.
**Exit E2b:** one real library action through read → evaluate → commit → publish → restart.
Include M1 complete with M2 selection proved but M2 body unavailable: M1 commits, M2 suspends,
and a fresh JVM resumes M2 without reapplying M1. Missing selection evidence is a separate hold control.
A PostgreSQL substitution under the old RAM authority does not pass Phase 2.

## Phase 3 — Integrated correctness and measurement

The scope remains these three phases followed by measured iterations. General in-band repair of an
endless sequence of successful imports is explicitly excluded, not an additional pre-implementation
design gate. Preserve pending authority and demonstrate safe host pause; finite gas-failure→detach
recovery and the required dynamic-join positives remain mandatory. Broader cancellation design can
be considered later if an actual workload requires it. The present authorized work targets readiness
for this phase; full integrated deployment and measurement remain the next delivery stage.

Run the [A–T cards](09-scenarios.md) using the [manifest protocol](16-scenario-run-manifest.md).
Choose useful tutorial/MyOS examples as fixture inputs, not assumed existing golden outputs.
First establish readable expected traces; then add fault schedules and scaling.

Required headline demonstrations:

- Same graph and history, same exact settled result across eager/cold/lazy/restart runs.
- One Agreement shared by 1000/10000/100000 Orders: independently committed source transition once logically,
  bounded paged fan-out, independent consumer completion/failure and no all-parent RAM preload.
  Source calls stay independent of N under matched schedules; parent/routing work is honestly O(N).
- Large dormant graph with small affected spine: bounded bodies and separately proved topology cost.
- Child catches up to the required point before its dependent read, without later-input absorption.
- Normal small related graphs settle within preregistered seconds-scale budgets.
- Fast independent source commit and bounded, scalable consumer processing are separate acceptance
  targets. Measure source latency, consumer throughput/lag, backlog and publication lag. N required
  reactions legitimately cost work/gas and total fan-out time may grow with N; no universal
  seconds-scale whole-fan-out deadline applies. Small related-graph budgets remain scoped to their
  preregistered workloads. Optional aggregate reports must account for discovery and all obligations,
  including failures; they need no mandatory global receipt and never block source/consumer commits.
- Under large fan-out, unrelated users and healthy consumers retain bounded service. Measure indexed
  work selection, transaction/page peaks and overload recovery, not just eventual total throughput.
- Vary observable transitions U=1/10/100/1000 within one source invocation separately from N/P/history:
  count necessary reaction work separately from avoidable repeated source calculation and evidence
  copies/decodes/hashes. Shared immutable evidence must not turn into one resolved snapshot per Order.
- Crashes, missing content and lost notifications recover without partial invocation publication.
- With A=1, grow finite nested/owned work across 10/100/1000 commits and measure cumulative control
  inspection/canonicalization/hash/serialization/WAL independently from blueprint and evidence-round costs.

**Exit E3:** correctness, fault and measurement artifacts; explicit failed/inconclusive rows.
An out-of-budget correct run is not a performance success.

## After Phase 3 — Plan the next loop from evidence

Classify every finding as semantic/library, host correctness, query/storage cost, processor cost or
API usability. Return to its owning phase, keep the counterexample and rerun affected gates.
Likely iterations include extending the supported causal interactions, source-progress representation,
scope discovery, batching, cache sizing and reducing repeated processor verification. They extend or
refine the proven first slice; they do not postpone its grouping and chronology decisions. No promise
that Coordination is complete after the first three phases.

## Required artifacts

- Exact checkout/patch provenance and bootstrap configuration.
- Tiny hand-worked semantic traces and executable tests.
- Scenario cards plus manifest rows, deterministic seeds and fault schedule.
- Read-only invariant audit and exact ordered semantic outputs.
- Query/CPU/heap/WAL/source-call/latency reports with eager/lazy controls.
- List of remaining limitations, supported-scope boundaries and next iteration decisions.

No test result or numerical performance claim is created by this documentation update.
The user has authorized Phase1/2 implementation and automated tests. Libraries use isolated branches
from local `next`; MyOS uses `feat/coordination-with-external-state` from local `main`. See23 for
actual workspaces, milestone execution, test scheduling and the joint readiness gate.
