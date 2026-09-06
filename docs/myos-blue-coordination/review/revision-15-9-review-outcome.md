# Revision15.9: select the processing rules

> **Status:** design refinement, not implementation or G1 acceptance

This revision acts on the user's instruction to resolve the already identified algorithm choices.
It adds [22 — Selected processing kernel](../22-processing-kernel.md), which takes precedence over
earlier candidate text. No runtime implementation has been authorized or started by this refinement.

## Decisions

| Topic | Selected rule | Why |
|---|---|---|
| Timeline completeness | Exact directed operation-relevant membership; trusted universe is separate. | An Order's TO cannot block independent Agreement TA. |
| Large genuine fan-in | Shared indexed membership/frontier aggregation and incremental candidate merge. |10k relevant inputs are real dependencies, not a reason to waive completeness. |
| Observable processing | One synchronous-continuation/FIFO interpreter with authenticated source action substitution. | Final state and flat event lists fail intermediate-read and enqueue-order controls. |
| Atomic ownership | Complete directed returning component, with monotone tentative scope expansion. | Preserve shared gas/rollback without adding all reverse observers. |
| Origin and IDs | Canonical authored source initialization/FULL_HISTORY; source-local constructors. | Worker/first-parent/cache state cannot select history or source charges. |
| Attachment | FROM_* selects observer history/view; initialization and historical convergence are distinct. | One source authority, explicit consumer differences, no creator self-wait. |
| Failed import | Terminal per-operation gas outcome; explicit source-before alignment inside successor. | Uniform live/history progress without range skips or hidden pin jumps. |
| Publication | Typed0/1/N lineage/stream authority in one complete transaction. | Metadata-only, ordinary and coupled-existing-lineage cases all fit. |

Inherited reaction origin also closes same-cause fan-in: Source and Parent projections are composed
inside Root's one corresponding operation. Parent's own new emissions retain their own producing
identity. A terminal failed Parent contributes unchanged state and no tentative effects; valid
original Source ancestor delivery remains distinct.

## Explicit corrections to previous proposals

- Replace r15.8 simple consumer0→2 after failed r1 with alignment0→1, then r2's normal1→2 program.
  Alignment is real new consumer update work, can emit/change topology/fail, and shares r2 rollback.
  Original r1 source events are not replayed. A later direct read before r2 still reads actual0.
- Reject aborting an arbitrary import suffix on first gas failure. Account for each canonical
  operation; a fixed catch-up cut may finish with failures. Continuing is not guaranteed business
  success and may rerun a problematic update handler during explicit successor alignment.
- Replace open source BIRTH with canonical source origin. Current FROM_NOW/admission composition
  changes explicitly: source history remains full; observer selection determines its own import.
- Replace cache-sensitive combined initialization context with two fixed logical scopes where
  source/consumer are independent. Source80 and parent20 with separate limits90 pass cold and warm;
  this is not a claim of equivalence to the old combined100 invocation.
- Replace unnamed graph scope/grouping with directed ownership, inherited origin and selected route/
  placement rules. Cyclic ancestor transport uses vertex-simple routes per original occurrence;
  genuine new emissions still repeat under one gas meter.
- Replace configuration-wide immutable Timeline completeness with exact relevant operation evidence.
  Adding an authorized same-provider Timeline does not require a stack reset.

## Evidence and remaining work

The rules are selected; implementation and conformance evidence remain to be produced. New/updated
scenario cards are explicitly PLANNED, with independent expected results and fault/performance variants.
They are not a test result or a claim that current constructors support these semantics.

The inspected baseline still has managed successful-predecessor validation and current temporal
admission/closure behavior. The existing external-input gas→detach example supports terminal progress,
but does not implement the new managed alignment rule. Earlier source-evidence appendices remain
unchanged. This revision uses no new remote/specification freshness claim.

Structural QA checks local links/anchors, JSON schemas, Java17 sketch compilation, preserved Mermaid
blocks and archive integrity. It does not execute processing, PostgreSQL or benchmark tests.
Runtime/test/build artifacts and earlier review snapshots are preserved.

Structural QA for this package passed:47 active Markdown/map/review documents,389 local links and46
anchor links; all three JSON schemas validate against Draft2020-12; the standalone sketch compiles
with Java17 and all lint warnings enabled. All14 existing Mermaid blocks remain byte-identical.
SHA-256 checks preserve488 runtime/test/build/historical-evidence artifacts and the existing tracked
patch. Mermaid was not re-rendered and no semantic/host tests were executed. Archive members are
verified against the regenerated SHA-256 manifest after packaging.

The retained r15.2 experiment remains3 passing attachment controls and4 failing settled-history
cases. **G1 is not accepted.** Next is the user's final independent review/implementation decision,
then library work, realistic MyOS host, integrated verification and subsequent iterations.
