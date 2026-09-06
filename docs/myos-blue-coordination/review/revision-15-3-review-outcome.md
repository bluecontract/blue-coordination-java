# Revision 15.3: final design snapshot for independent review

> **Date:** 2026-09-05 · **Status:** DESIGN REVIEW; implementation requires explicit user approval
> [Review prompt](gpt-pro-review-prompt.md) · [Causal model](../17-causal-processing-model.md)

## Purpose and authority

The current task is to define the algorithm, API shape, core invariants, test cases and documentation.
Another GPT Pro review is planned. The user decides when implementation begins; neither this package,
an earlier recommendation to start Phase 1, nor the presence of exploratory tests grants that approval.
Historical review outcomes describe earlier recommendations and do not override this current stage.

This is a final **review input snapshot**, not a final production specification, accepted runtime,
release or public API freeze. One current local baseline remains authoritative. No compatibility
profiles, migrations or parallel specification variants are introduced.

## Changes applied to the proposal

| Review finding | Revision-15.3 treatment | Evidence status |
|---|---|---|
| A parent-side copy can hide an incorrect historical child read | Require direct `/child/counter` observations at T20 and, after attachment T1, T3; retain event-derived observations as separate assertions. | Planned cases; existing fixtures unchanged. |
| Equal-valued observations can hide a skipped original event | Require the complete ordered exactly-once original-entry subsequence, including T7 in the T10-attachment case. | Planned assertion; existing test limitation recorded. |
| Historical external replacement/removal meets a pending-binding restriction | Specify A@T20 leaving B after B@T10, without importing future B@T30; retire only obsolete occurrence-owned work and preserve independent obligations. Define the targeted reconciliation/eligibility change before claiming support. | Current replacement restriction is source-backed; removal is a conditional risk, not a newly run failure. |
| Ordinary CATCHING_UP gate is not historical operation eligibility | Separate exact per-use authorization for replay from ordinary live processing; a pure read does not prove topology-changing replay. | Source-backed distinction; no new cause type is assumed necessary. |
| Empty live routing has no valid SelectedAction | Add an explicit no-live-recipient action to the documentation-only Java sketch; no fabricated application, InvocationId, gas or settlement. | Sketch corrected; not a runtime implementation. |
| First commit can be mistaken for completed processing | Bind cause latency to exact whole-cause terminal authority; distinguish failures/blockers and publication lag; count throughput events only in their declared window. | Measurement contract clarified; no benchmark run. |
| Moving completeness heads can invalidate stable accepted input forever | Stronger proofs and above-boundary ingress preserve the accepted immutable prefix; real reservation, authorization and scope changes still fence attempts. | Preventive design rule and planned adversarial variant, not a demonstrated current starvation bug. |

These refinements use the existing A–T cards and schedule families. The catalog remains 53 schedule
families, nine closed `subcaseId` refinements, and profile counts CORE 39 / HOST 49 / INTEGRATED 53 /
PERFORMANCE 8. Required variants are specified within existing schedules; they are not optional merely
because they have no new schema enum. The manifest coverage check must inspect their frozen fixtures
and exact oracle artifacts, not just the presence of the enclosing schedule ID.

## Historical embedding operations: intended first scope

The intended first scope is not read-only historical observation. Ordinary supported removal and
replacement of an embedding must occur at their own historical cause, including while an older
source suffix remains pending. This does not grant arbitrary access to partially reconstructed state
or enable an otherwise unsupported cross-cohort merge.

For the minimal same-scope replacement case, make C an exact valid, already available independent
lineage and avoid unrelated sharing/feedback. After A imports B@T10, A@T20 replaces the occurrence.
The old occurrence must not deliver B@T30 into A. B's authoritative lineage is not rewound or erased.
The new C occurrence follows its own exact identity, activation and declared history selection.
The removal control changes the effective Process Embedded declaration consistently; it is not an
invalid document with a declared path whose value was merely deleted.

The exact supported Contracts input/retry/reconciliation and retirement projection still need design
review. A current capability failure is evidence of that boundary, not proof that the intended
behavior is wrong. Do not solve it by importing B@T30 early, changing old cause timestamps, globally
dropping the CATCHING_UP gate, or silently declaring all historical mutations unsupported.

## Remaining algorithm questions for this review

These are explicit design questions, not implementation details waived until PostgreSQL:

1. **Chronological visibility and eligibility:** define the exact next historical candidate, due
   source-receipt prefix, permitted pending reads and removal/retargeting transition. Show the nested
   positive C10/B/A20 and K100-importing-E10 negative using the same rule.
2. **Same-cause grouping:** when one original cause targets source and parent, derive direct seeds,
   receipt reuse, event multiplicity/order and invocation gas/rollback boundaries independently.
3. **Feedback and activation:** define the next authorized source segment after final and nonfinal
   historical steps produce genuine source WORK. New authoritative source work is not backdated,
   and unrelated future input is not absorbed.
4. **Scope and partition:** derive complete required overlap before the first live application commits;
   explain legal scoped refresh and precisely detected unsupported late joins without full-component
   hydration or a fictitious connectivity proof.

For each, Pro should give a concrete transition rule or the smallest counterexample showing what
must change. Naming a certificate, replay cursor or obligation is not a solution. Future authorized
tests verify the reviewed rule; implementation must not silently choose semantics from current output.

## Existing experiment and its limits

The [r15.2 chronology report](../implementation/phase-1-chronology.md) and its unchanged source/build
appendix retain three passing attachment controls and four failing settled-history replay cases.
**Prototype requirement gate: FAIL; G1 is not accepted.** No complete manifest-driven G1 run,
PostgreSQL test or integrated performance result is claimed.

The four red cases are useful evidence that target-owned replay is missing. The tests do not yet
establish direct historical child reads or the complete T10-case original-entry sequence. Turning
those unchanged tests green alone would not meet the new requirements. Source evidence for the
pending-operation boundary is included in [this extract](revision-15-3-source-evidence.md); distinguish
observed branches, inferred risks and intended behavior.

The earlier `implementation/` directory name is retained for link and evidence continuity; it does
not indicate that implementation is currently approved. This revision changes documentation and the
reference Java sketch only. Runtime code, executable test sources, Gradle support and raw JUnit
evidence are unchanged. No new implementation test or benchmark was run for this revision.

## Decisions retained

- Exact settled histories, order, gas, events, checkpoints and identities are non-negotiable.
- Compare the same managed graph under eager/lazy execution, with independent expected grouping.
- Preserve one complete Contracts invocation and atomic commit; no private portable continuation.
- Historical imports preserve source history; genuine new consumer emissions remain ordinary work.
- Keep immutable intent separate from current scoped execution facts and host concurrency revisions.
- No global visited shortcut, universal managed-first phase or unconditional whole-component scan.
- Trusted PostgreSQL queries are audited independently; hashes do not prove completeness.
- One shared source transition is distinct from N required parent reactions and physical retry cost.
- The future plan remains libraries, target-shaped MyOS Mini/PostgreSQL, integration/measurement,
  then further iterations. That sequence begins only when the user decides.
