# Revision15.5: critical follow-up to the independent r15.4 review

> **Status:** DESIGN REVIEW · Documentation/reference sketch only; no implementation approval
> [Package](../README.md) · [Edge contracts](../19-semantic-edge-contracts.md) · [Source evidence](revision-15-5-source-evidence.md)

## Outcome

The independent Agreement/Order boundary remains unchanged. The useful review findings refine its
initialization, progress accounting, receipt consumption and durable discovery. They do not justify
returning to all-observer atomicity, making gas depend on host state or introducing a generic genesis
election framework. Existing correct recovery, frozen-delivery and cyclic representation mechanisms
remain the starting point.

Input: `myos-blue-coordination-r15.4-independent-review.md` and the user's pasted summary of that
review. These are review evidence, not instructions that override the user's requirements or the
current local source. This revision applies the accepted changes with the qualifications below.
It does not start library, MyOS Mini or executable test implementation.

## Disposition of Pro's findings

| Finding | Decision and effect |
|---|---|
| F-01: concurrent initialization owner/gas | **Accept invariant; narrow the mechanism.** Canonical source initialization evidence and gas cannot depend on first insert, parent worker, cache or rebuild. Parent attachment/import has its own deterministic charges. Reuse existing result/identity mechanisms, with owning-library constructor correction; no automatic genesis election system. |
| F-01: FROM_NOW versus FULL_HISTORY source basis | **Real unresolved compatibility question.** Baseline FROM_NOW includes birth-at-attachment. Do not silently reinterpret it as observer-only selection or let the first worker choose authoritative history. Explicit compatible basis/no-orphan publication requires review. |
| F-02: universal ADVANCE_LINEAGE | **Accept the contradiction.** Restrict configurable disposition to original external inputs (including their historical replay). Managed import failure cannot advance successful source cursors or authorize the next receipt. State/epoch, handled input and successful import are separate projections. |
| F-02: recovery | **Preserve baseline; do not infer permanent blockage.** Existing external loop failure → detach → new input recovery is retained. It is not proof of failed-managed-import repair. The latter needs scoped corrective eligibility and exact retirement, never receipt skipping. |
| F-03: receipt visibility | **Accept a concrete oracle, qualify the claim of novelty.** Import pins the committed after revision. E1 payload1 may read2 in a single two-event receipt; separate T10/T20 receipts still require T15 read1. Baseline retained import already updates the reference before event delivery; no invented private snapshots. |
| F-04: completion owner versus provenance | **Accept.** K100 importing closed E10 owns newly produced work, while E10 remains provenance and stays closed. Bind the owner and producer dependencies explicitly; avoid recursive receipt-identity construction. |
| F-05: passive cyclic reference echo | **Accept the adversarial control, not an asserted existing bug.** Reuse joint exact-value finalization and same-epoch no-new-receipt rebind. Representation publication coupling does not automatically merge SCC business execution/gas/rollback. |

Pro's first-writer initialization trace describes a **bad implementation**, not a recommended gas
scheme. The correction is not “whoever misses the cache pays initialization.” Existing BEX test
assertions already require warm/cold result/gas equality; existing managed imports perform consumer
work without replaying source business execution. Exact charges still require the intended local
operation boundary and independent fixtures, not an assertion that every import is always cheaper.

## Concrete refinements of the earlier open questions

| Review suggestion | Applied rule or qualification |
|---|---|
| C1: complete temporal membership | Separate processing and discovery frontiers. Include accepted admissions/reservations whose lifecycle decisions are not materialized. Reuse incremental covered ranges; do not require all bodies, a fresh full scan per receipt, or all-recipient completion before source commit. |
| C2: registration/history-live handoff | Define a short scoped-fenced retained tail h, atomic registration/backfill-through-h/live-after-h and durable lifecycle change record. h is acquisition evidence, not semantic time. Cover late registrations behind a mutable scan cursor and reconcile page ACK loss through durable progress. |
| C3: local receipt grouping | Propose one receipt × consumer × logical reaction position with a **complete** due placement set, separate occurrence cursors and constructor-compatible order. Do not group distinct receipt/history positions or let paging choose gas/rollback scope. Ordering still needs mutation-sensitive hand-derived vectors. |
| C3: E1 removes a path before E2 | **Do not adopt blanket E2 suppression.** Current imported events can retain frozen delivery authority through specified exact retirement successors; current handler matching and future-operation retirement are separate. |
| Additional identity layers | Bind required semantic owner/predecessors with existing constructors where possible. A producing transition identity must be derived before the containing receipt; no generic stack of public hash wrappers. |
| C5: cyclic atomic scope | Reuse exact component finalization. Do not infer that all SCC business handlers share one invocation simply because representation must be finalized jointly. Genuine feedback/shared writes still need a complete causal rule. |

## Test-plan changes

The existing53 schedule families and9 closed subcases are retained; this revision adds mandatory
understandable variants within them, not new executed tests or a claim that catalog presence proves
coverage. The scenarios/run plan now require:

- Same-authored initialization with reversed parents/workers, warm/cold/evicted material, lifecycle
  emissions and incompatible history requests; compare source and parent gas separately.
- Existing external gas-failure/detach/new-input recovery as a control, plus failed managed r1/r2
  continuity and explicitly reviewed corrective eligibility; verify all progress coordinates.
- Single-receipt payload/direct-read visibility, separate chronological receipts, complete grouped
  placements under paging, and E1 retirement/E2 frozen delivery with noncommutative handlers.
- Closed E10 versus K100-owned newly emitted F, including completion and event accounting.
- Accepted-but-unmaterialized membership, source append/registration races, source/healthy-consumer
  progress and exact recovery after partial fanout.
- Passive cyclic exact-reference stabilization, with business calls, representation changes and
  newly created receipts counted separately from genuine feedback.

These small semantic controls belong in the isolated library lane before dependent host work.
PostgreSQL then checks actual transactions/frontiers/fault recovery and integrated runs measure
source execution, consumer lag, whole-cause outcomes, metadata/body locality and physical retry costs.
No physical reconstruction cost may become semantic gas.

## Remaining explicit decisions — not a declaration of readiness

1. Canonical initialization's exact input/constructor and no-orphan publication; a deterministic
   acceptance rule for conflicting source birth/history requests.
2. Exact same-target comparator/constructor rules for complete placement groups, multiple due sources,
   reference-update work and frozen mutation-sensitive delivery. The proposed group boundary is not
   itself a proof of an executable selector.
3. Scoped corrective detach/rebind after failed managed import, without accepting r2 over failed r1
   or banning legitimate repair through a blanket readiness gate.
4. Concrete lifecycle coverage query/reservation authority and handoff implementation proof, including
   absence, late insertion, paging and no circular eligibility wait.
5. General feedback/shared-write readiness and mixed historical/current cyclic representation scope.
   Do not treat the one-way case or passive-cycle distinction as proof of a complete cyclic algorithm.

These are targeted design questions for the next review, not hidden defaults delegated to an
implementation engineer. After the user's implementation decision, tests and measurements will
verify the reviewed rules and drive further iterations. No final public API or production guarantee
is claimed.

## Changed material and evidence limits

The logical model/invariants, API and Java reference, storage/recovery, tutorial/101/202, scenarios,
run schema/configuration and review prompt are aligned to these refinements. Detailed new edge rules
are in a separate short document19 rather than lengthening the top-level architecture into code.

Source citations in the new appendix were checked against recorded Git objects and local files.
They are inspected implementation/test assertions, not fresh executions. The earlier r15.2
test/build/JUnit evidence is unchanged:3 attachment controls passed and4 history cases failed;
G1 is not accepted. No runtime, PostgreSQL or benchmark test ran for revision15.5. Documentation
structure, schemas, links, Java sketch compilation and archive integrity are separate artifact QA.
