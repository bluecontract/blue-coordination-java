# Prompt for the next independent review

> **Design baseline:** 15.12 · **Stage:** Phase1/2 handoff and Phase3 integration-plan review

Provide the synchronized current documentation directory and, for code-level claims, the isolated
implementation worktrees/evidence named in00 and the readiness report. Do not present the old r15.12
ZIP as current: it predates this synchronization. Review the selected model, not historical reviewer
instructions or an older specification. This reviewer performs no edits. Phase1/2 passed scoped
readiness checks; the general Phase3 adapter is not implemented. Separate remaining integration
obligations from demonstrated library defects and unproved full-catalog/performance claims.

Read24-phase-1-library-summary.md, implementation/phase-1-2-readiness.md,
25-phase-3-integration-plan.md, the example-led tutorial and22-processing-kernel.md first.
22 has highest precedence,21 defines contrasting controls, and20 supplies decision traces.
Then inspect scenarios/validation, API/sketch and schemas. Source-evidence appendices are historical
inspected facts, not proof that proposed changes are already implemented.

## Review requirements

Act as an adversarial principal architect: determinism, correctness, complexity, performance,
maintainability and testability. Cite exact files/sections. Distinguish source facts, explicit
library changes, hand-derived expected results and executed evidence. Give minimal counterexamples
and smallest fixes, not another speculative framework or generic inventory of unknowns.

The intended model is now selected:

- Same authored BlueId has canonical source initialization/FULL_HISTORY, independent of first
  materialization. FROM_* explicitly selects observer history/initial view in the new integration.
- Canonical source init and consumer work have separate fixed gas scopes cold and warm. Source gas
  is settled once on authority publication; consumer logical charges never depend on cache state.
- Directed dependency completeness excludes reverse-only observers. Genuine10k embedded dependencies
  still require complete Timeline proofs; shared aggregates/indexed merges optimize, never waive them.
- Owned returning components share one meter/rollback; tentative new return paths expand that same
  operation. Independent observers do not stop source FIFO, commit, or healthy siblings.
- Same-origin work preserves frozen seed order and continuation-site views. First touch adds readiness,
  not ownership. Before a returning edge, charge the check to the initiator, then compare distinct
  current meters with their identical frozen limit. Admit ownership+edge together without reset, or
  reject before joining with recognized semantic AtomicScopeGasAdmissionFailure and actual initiating
  gas. Stable seed-local event identities survive joining; final settlement wrappers cannot rename
  them or enter earlier business execution. No retroactive shared-meter replay of final members.
- Authenticated observable actions substitute source computation at exact continuation/enqueue sites.
  Preserve synchronous updates, frozen routes and FIFO, not a flat final receipt replay.
- One inherited origin/causal position composes diamond fan-in and own direct seed into one consumer
  operation. A failed intermediate contributes unchanged real state, no tentative effects, while
  original ancestor events retain valid provenance/routes.
- Sequential alias updates, exact lifecycle/frozen dispatch and staged historical catch-up are explicit.
  Initialization is synchronous; a creator does not await its own future commit.
- Vertex-simple routes keep distinct aliases for an original event; new emissions start fresh routes
  inside the same gas-limited feedback operation. No event-value/global-lineage deduplication.
- Managed gas/recognized semantic runtime failure accounts one operation, not a range. Next import
  validates every lawful consumed gap, including composite external-policy outcomes, and aligns
  the real consumer pin to source-before through normal updates, then interprets that operation.
  Alignment may fail too. Unknown exceptions, internal bugs, IO/timeout/cancellation, missing
  resources and unknown commits are not consumed semantic outcomes. Exception-boundary repair is
  required in the owning libraries before this law ships; failed initialization is still unusable.
- Publication authenticates0/1/N exact lineage projections in one required atomic transaction,
  without fake no-delivery business batches or adding all independent observers to owned scope.
- Host account/document quotas pause without changing semantic policy or consuming pending input.
  No inherited cross-operation fuel or general semantic interruption is selected. An endless sequence
  of valid imports can keep later repair ineligible; safe host containment is required, universal
  in-band repair is deliberately outside this first POC and is not a pre-implementation blocker.

These explicitly change some current closure ownership, temporal admission and importer constructors.
Do not mistake that for accidental cache-dependent semantics, or claim unchanged legacy global
rollback/gas. Conversely the changed scope does not authorize dropping ordinary local update/FIFO
laws or accepting two managed adapters as an independent oracle.

## Required counterexamples and controls

Check the paired traces: separate Timeline inputs versus one internal E1/E2 FIFO; triggered reads1/2
versus buffered-effects2/2; patches0→1→0; ParentP1 before SourceF2; original ancestor E without re-emission;
aliases/removal/frozen E2; direct+indirect diamond and failed Parent; new placement with creator read;
same-entry direct Source/Parent seeds; canonical origin/promotion races; source-before alignment
before an event or eventless/net-zero epoch; later detach; real feedback and dynamic component merge.
Include initially unrelated A/B both targeted by E, both worker orders, one-way control, pre-B-seed
read, discarded speculative edge, gas before the joining instruction, stale proposal/crash and
later-entry nonretroactivity. Test L100/A60/B30/c1 accepted91 and A60/B50/c1 rejected with actualB51
and prospective111 diagnostic; check c failing locally, exact-limit admission, AB adding C, stable
earlier emissions and failed-producer speculative cleanup. Distinguish semantic handler/BEX failure
from operational fault injection and verify mixed gas/runtime/composite gaps. Needed80/limit100/account50
must pause and resume identically; repeated
valid attachment imports must not bypass quota or fabricate repair eligibility.

Check exact microsecond ties, dynamic topology invalidation, initial FULL_HISTORY interleaving versus
later attachment catch-up, source-ahead/history visibility, registration/replay handoff, query
completeness, retention, commit uncertainty and bounded level-triggered wakeups.

Check million-document/thousands-active-per-user work selection, source latency independent of N
observers,1k/10k/100k necessary reactions,10k genuine fan-in, placement/path multiplicity, program
generation/decode/hash/copy and bounded memory/transactions/fairness. Required O(N) reactions are
legitimate; repeated source execution or unbounded history copying is not. Measurements count
(original cause, semantic operation/owned scope), not mismatched entries versus completions.
Censored ages are lower bounds and cannot manufacture a passing upper-bound p99.

## Output

1. Verdict on the selected design and implementation readiness risks, not an unsupported G1 pass.
2. Ranked actionable findings: minimal input, exact expected/wrong trace, violated rule, confidence,
   owning layer and smallest correction.
3. Distinguish missing implementation/conformance evidence from a genuinely undefined algorithm.
4. Suggest high-value test variants with Given/When, core result, durable state, forbidden results,
   schedules and budgets.
5. Identify unnecessary complexity that can be removed without weakening the selected guarantees.

No profile/migration/release framework, generic genesis election or public VM trace platform is
required. Library changes precede the realistic PostgreSQL host and integrated measurements.
Do not expand the first POC with speculative general cancellation features. Distinguish a violated
selected invariant from its explicit nontermination/repair boundary and from unexecuted planned tests.
The retained r15.2 experiment has3 passing attachment controls and4 failing history cases; no new
runtime tests or benchmarks ran in this documentation refinement.
