# Revision 15: applied refinement and tutorial

> **Historical refinement record:** this describes the original r15 package and its checks.
> The active follow-up is [revision 15.1](revision-15-1-review-outcome.md); later changes do not
> retroactively change the counts or validation claims recorded below.

> **Date:** 2026-09-05 · **Status:** implementation-spike candidate, not a final API
> [Package map](../README.md) · [Beginner tutorial](../tutorial/README.md) · [Causal model](../17-causal-processing-model.md)

## Outcome

The first-principles corrections are applied to the English design package, reference sketch,
scenario cards and validation contract. No Coordination, Language, BEX or MyOS runtime code is changed.
The next step is the first executable library slice, not another general preimplementation freeze.

## Applied changes

| Earlier proposal problem | Revision-15 direction |
|---|---|
| Ambiguous inline/managed equality | Eager/lazy SAME managed graph and semantic inputs; independently derived expected invocation boundaries. |
| Import all source history before parent past events | Chronological FULL_HISTORY; S10/P20/S30 must record 1 at P20. Same-original-cause grouping is an explicit first spike. |
| Blanket source freeze and immutable final target | Fixed external input boundary/selection basis, plus authenticated same-root causal source progress. Final/non-final activation needs executable traces. |
| Universal action phases and gate exceptions | Committed prefix, operation-specific prerequisites and a recomputable next-action explanation. No generic same-root bypass. |
| Workflow-wide frozen execution snapshot | Immutable complete initial delivery partition plus refreshed scoped facts and explicit noninterference checks. |
| Unconditional whole weak-component scan | Prove sufficient cause-required grouping/scope; measure topology separately from exact content. No omission disguised as locality. |
| Eventless receipt exclusion | Preserve supported authenticated source application/result/companion/gas evidence. |
| Hashes treated as completeness proofs | Reviewed trusted query adapter with independent raw-state/index audit, including coherent omission. |
| Broad complex host fencing | One host-only serial-domain semantic revision covering all semantic writers; separate ingress/auth/reservation/resource/lease controls. |
| Growing plan/prefix/outbox rewrites | Immutable definitions once, indexed successors and small current deltas/cursors. |
| Wakeup depends on notification timing | Durable level-triggered recheck, including supply-before-registration and crash-before-notify. |
| Gas assumed to terminate the whole workflow | Gas bounds each invocation; an indefinitely self-extending workflow can be operationally paused without semantic fabrication. |

Live connected source+parent effects still have one atomic Contracts invocation. Historical imports
still have separate invocation boundaries. NeedsResources still exports no private continuation.
Stable application settlement, complete exact environment/limits, full result/event evidence,
ownership-sensitive failure and contiguous terminal outbox remain mandatory.

## Documentation structure

The new [tutorial](../tutorial/README.md) contains twelve short English chapters using Order/Agreement
examples, numbered steps, logical before/after tables and small diagrams. It explicitly explains:

- ordinary embedded content versus a separately managed document;
- child and ancestor reactions in a connected live change;
- one Agreement shared by many Orders;
- lazy exact-content reads and restart after missing evidence;
- Timeline completeness and waiting for a child to catch up to the required point T;
- later attachment versus reconstruction from the beginning;
- cycles, causal feedback, crashes and ordered notification.

The older 101/202 are now concise optional technical bridges. Document 17 owns the proposed causal
laws. The Java catalog shrank from 4,622 to 673 lines; it is a compiling spike sketch, not a public
implementation contract already backed by an algorithm. Run schemas retain exact provenance,
budget and result evidence and add the new counterexample/fault schedules.

## Validation of this refinement

- Standalone reference Java compiled with `javac --release 17`.
- All current Markdown Mermaid blocks parsed with Mermaid 11.12.1.
- Local Markdown links/anchors and balanced code fences checked across the package.
- All three JSON Schemas pass Draft 2020-12 meta-validation; 389 structural, negative,
  profile/catalog and metric-unit/direction checks passed.
- Independent cross-document review checked tutorial, causal laws, invariants, API and A–T cards.
- Active prose was checked for operative retired r14 source-gate/phase/frozen-cut rules.
  The retained r14 review outcome is explicitly historical.
- Package checksums and the r15 archive are generated after final validation. The existing r14 ZIP
  is retained unchanged.

These are **package checks**, not passing POC semantics, durable integration or performance results.
No fresh remote fetch is claimed. Baseline provenance remains the recorded local checkout.

## Still to prove in implementation

The concrete chronological grouping, source-target progression/activation, interacting-cohort
selection and complete selective closure algorithm remain Phase-1 work. A record named
NextActionCertificate does not establish them. The [first loop](../10-poc-delivery-plan.md) starts with
tiny independent traces that can falsify the proposed behavior, before the host schema depends on it.

The implementation then adds target-shaped PostgreSQL MyOS and runs the integrated suite.
Phase 3 is expected to produce another iteration backlog, including library/API changes and measured
optimizations. This revision is a more focused starting point, not a promise that the first POC
will finish Coordination.
