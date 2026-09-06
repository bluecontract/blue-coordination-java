# Revision 14 refinement and validation

> **HISTORICAL:** the findings and checks below describe revision 14, not the current proposal.
> See [revision 15](revision-15-review-outcome.md) for the active design and validation outcome.
> References into active files may no longer have their historical meaning; use the preserved r14 ZIP.

> **Status:** refined candidate for the last pre-implementation review, not an API freeze  
> [Package](README.md) · [Minimal API](../05-poc-api.md) · [Delivery plan](../10-poc-delivery-plan.md)

This revision applies the final implementation-readiness review to the English proposal package.
It changes documentation, the standalone Java review boundary and acceptance requirements only.
No Contracts, Coordination, BEX or MyOS runtime implementation is claimed. After the final external
review, prepare the first implementation baseline and start the three-phase learning loop.

## Necessary corrections applied

| Finding | Refined contract | Required implementation proof |
|---|---|---|
| F1: pending-action priority was incorrectly used as an append-only outbox cursor | Terminal actions append a contiguous per-domain position against a fenced predecessor in their terminal transaction. Publication follows that chain; pending root/action keys remain selection/audit evidence. Empty batches participate and committed retries reuse their position. | Publish immediately after H1, newly created M1, nested M and H2; repeat with delayed/reversed publisher claims, empty batches, ACK loss and descending admission IDs at one unchanged cutoff. |
| F2: readiness evidence omitted legitimate fresh embedded children | A `FrozenProspectiveInitializationProof` binds the stable owner application, authored child identity, exact prospective occurrence/activation and absence evidence/fence. Terminal projection proves invocation-owned initialization. Existing identical lineages are reused and existing blockers cannot be bypassed. | Admission/live/nested initialization, existing-lineage reuse, forged/stale absence, rollback without orphan creation and retry without duplicate initialization. |
| F3: the 101 guide suggested separate child/parent commits for normal connected live processing | Connected live child/parent reactions share one invocation/gas/atomic commit. Later retained-history catch-up uses separate invocations. Scenario D and source failpoint applicability distinguish these cases explicitly. | Compare live gas/rollback with the connected reference, and retained catch-up with its separate-invocation reference; a source-only durable-before-fanout failpoint is not claimed for the live case. |
| A subscribed Timeline could fall outside the configured completeness set | Complete initial/result membership evidence produces a typed nonterminal supported-scope hold for an outside-set source. It cannot publish business state, create a ProcessorStatus or advance/release semantic state. | Outside-set admission/live mutation, inside-set Channel changes, missing/forged coverage and unchanged state after hold. |
| Reference value semantics and cache miss coalescing were insufficiently concrete implementation tasks | Phase 1 includes value-law tests and a BEX loading/coalescing seam using the existing engine's complete key. | Input/accessor mutation, content equality/hash, null Optional, canonical lists/overflow and concurrent cache misses under cancellation/limits. |

The append tail is not a SQL sequence or a publisher-assigned ordinal. Coordination still proves
the authorized action and its complete semantic predecessors. Host fences only serialize the
validated append; they cannot select a different cause/action. Prospective proof ownership uses
the stable application rather than a later-created child work/plan identity, avoiding a construction
cycle. Admission-member and prospective-activation paths retain their current baseline differences;
proof presence does not force creation when the permitted lifecycle discards/removes/terminates a
candidate. These are protocol requirements to implement and test, not claims established by Java compilation.

## Disposition of the older Pro review

The review was stale relative to the proposal, not generally based on an obsolete Contracts source.
Its current-source invocation and generic identity observations remain valid; its broader proposed
scope is not automatically required by this MyOS POC.

| Pro item | Disposition in this package |
|---|---|
| AR-01: invocation/gas/commit boundaries | Retained; F3 now makes connected live versus retained-history processing unambiguous. No alternate aggregate specification profile. |
| AR-02: generic DocumentId versus BlueId | Generic observation accepted; retain intentional MyOS authored-content-derived identities and existing SDK modes. No UUID/work-slot allocator or allocation-policy hierarchy. |
| AR-03: operational versus semantic completion | Existing typed dispositions/holds retained. No administration/quarantine-driven semantic skip. |
| AR-04: static Timeline set/serialization | Retain bounded POC scope, add explicit outside-set hold, measure completeness/serialization costs. Dynamic Channels inside the configured set remain supported; dynamic provider membership is deferred. |
| AR-05: complete environment | Existing eleven-field environment and separate operational build provenance retained. |
| AR-06: Java value safety | Explicit immutable-value/canonical/overflow tests added to Phase 1; no wrapper hierarchy for every scalar. |
| AR-07: duplicated storage family | Existing single family-bearing key and explicit wire-order rule retained. |
| AR-08: cache keys/concurrent misses | Reuse current BEX environment-bound key; assign missing loading/coalescing ownership. No competing Coordination compilation-key hierarchy. |
| AR-09: evidence amplification | Existing structural sharing/delta rules and measured growth budgets retained. Representation choices remain implementation work. |
| AR-10: archive completeness | Summary, English package and fresh integrity manifest included. Older Polish input and historical review reports remain outside this candidate. |

The older report's cause-only wording must also not overstate the existing code gap. Current
Coordination has target-bearing feeder lanes/publication identities; current InvocationId already
binds closure evidence. Phase 1 first exercises R1/R2/R3, retry/evidence growth and shared-child
overlap, then reconciles normative application settlement and changes the smallest proven code/
constructor surface. A universal current-runtime replay suppression bug has not been established.

## Validation boundary

Checks completed on the refined package:

- standalone Java boundary compiles on JDK 21 with `--release 21 -Xlint:all`;
- nine positive/negative append-position constructor smoke checks pass;
- all seven Mermaid diagrams parse and 172 local links/anchors resolve across 26 Markdown files;
- all three schemas validate as Draft 2020-12; 191 positive/negative structural checks cover the
  47 metric/unit/direction bindings, 39 schedule IDs, safe integers and Timeline-set shape;
- schema files are unchanged from revision 13: the refined scenario/failpoint matrix is enforced
  by the specified independent run validator and fixture metadata, not falsely claimed as schema-only validation;
- the archive and working package match the freshly generated 30-entry SHA-256 manifest.

These checks do not execute the proposed runtime or replace the independent cross-artifact scenario-run
validator that Phase 1–3 must implement. No PostgreSQL, correctness or performance acceptance gate
is awarded by this refinement.

## Handoff after external review

1. Resolve any remaining evidence-backed correctness blocker with its smallest regression vector;
   do not require production migrations, multi-provider deployment or a final public API to start.
2. Implement Phase 1 library/semantic foundations, including the corrections above; a small vertical
   debugging path can precede the complete acceptance harness, but phase exit cannot.
3. Build Phase 2's target-shaped PostgreSQL/Timeline host and execute Phase 3's real integrated
   scenarios, fault schedules and performance runs. Those results determine the next iteration.

The first POC's Handler-created cross-cohort edge restriction remains explicit and is not a
successful reference-equivalence result. If a required example needs it, return to Phase 1 rather
than relaxing the oracle. Correctness remains non-negotiable; performance and representation
choices are refined through measured iterations.
