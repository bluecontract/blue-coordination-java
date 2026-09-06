# Revision15.4: source commit once, independent consumer progress

> **Status:** DESIGN REVIEW · No implementation approval or new runtime evidence
> [Boundary decision](../18-independent-lineage-processing.md) · [Tutorial](../tutorial/README.md)

## Why this revision exists

The user challenged tutorial02's suggestion that Agreement and every reacting Order must commit
together. The common intended case is one Agreement shared by1000 Orders: process/commit Agreement
once, then let Orders consume the change independently while preserving exact deterministic history.

The previous documentation had converted current active-observer closure behavior into a blanket
design requirement. That was too strong for this goal. Revision15.4 changes the proposed semantic
boundary, not only the tutorial wording. Historical r14–r15.3 statements demanding all-observer live
atomicity, immutable global application partitions or root-wide progress barriers are superseded.

This is the next review snapshot of the one evolving local design, not a second compatibility profile.
Current local Coordination/Language/BEX remain the implementation baseline; owning-library changes
are in scope once the user authorizes implementation. No such implementation starts in this revision.

## Applied decisions

| Concern | Revised rule |
|---|---|
| Common one-way Order → Agreement | Source commits independently; each Order consumes retained source receipts and commits its own local result. |
| What stays atomic | One defined local operation: state/history, local gas/result, cursor and outgoing obligations. Do not split an existing Contracts result in the host. |
| Slow or failed Order | Source and independent siblings continue. Preserve local failure and block actual dependents; no source rollback or false successful aggregate. |
| Source ahead of consumer | Read the exact historical source view due at the consumer's semantic position, never latest-at-worker-start. |
| Live lag versus attachment | Same retained-result principle; different lifecycle/history selection. Lag does not create a new attachment or require equality with a moving source head. |
| Durable fanout | Source receipt plus bounded recoverable discovery/registration basis, not an eagerly loaded1000-parent closure. |
| Temporal membership | Account for target lifecycle history and frontiers; source atT30 cannot route through an Order's semantically retired T20 occurrence merely because its worker is behind. |
| Ordering/identity | Local predecessors and producer dependencies, not global physical sibling commit order. Audit actual Contracts scope/gas/identity constructors. |
| Publication and completion | Local per-stream publication with causal prerequisites; complete-cause settlement separately proves coverage and outcomes. |
| Oracle | Eager/lazy runs of the same revised boundaries must have exact histories/gas/IDs. Do not pretend legacy whole-fanout rollback and InvocationIds stay unchanged. |

The design explicitly permits source progress before all recipients are discovered/applied, but not
successful whole-cause closure without complete temporal membership and outcome evidence. Retain
source history for outstanding delivery, replay and recovery. The first POC does not introduce history
garbage collection to make these proofs disappear.

## Concrete remaining review questions

The ordinary fixed one-way graph is the primary required case. Independence must be established from
permitted writes/routing and complete dependency evidence, not inferred from an empty feedback queue.

1. Specify the minimal complete temporal lifecycle/discovery frontier and registration/append handoff.
   Prove the source-ahead T30 / target-removal T20 case and a concurrent valid attachment without
   imposing all-Order body loading or a root-wide progress barrier.
2. Finalize same-cause/multi-source/path operation grouping and constructor-compatible ordering.
   The proposed one-way order is source production, due parent receipt consumption, then original
   parent direct input at that cause position. Verify exact multiplicity, gas and identities.
3. Define the smallest owning-library constructor/scope change for stable source/consumer identities
   and active subscribed-but-lagging occurrences. Existing inactive historical import is not enough.
4. Define competing feedback/shared-write readiness and cyclic exact-value finalization. Feedback is
   new receiver work; no backdating or worker-order arbitration. A true coupled cyclic proof boundary
   is distinct from requiring every ordinary observer in the same transaction.

These are design questions now, not waived as implementation details. The package does not claim a
complete general cyclic algorithm or that tests of one-way fanout prove it. It also does not exclude
ordinary removal/retargeting or restore the old all-observer closure to avoid defining the rules.

## Test and performance plan

The catalog remains53 schedule families and9 closed subcases. Required variants within existing
families cover source-before-fanout commit, crash after17/1000 consumers, one unavailable/failing
consumer, source/healthy-sibling progress, exactT15 reads with sourceT20 retained, multiple placements,
stale lifecycle indexes, registration/append races and retention safety. Reversed worker completion,
paging, retries and restart must preserve each lineage's exact trace and all required deliveries.

Measure source commit latency, per-consumer lag, whole-cause success latency/throughput and publication
lag separately. Source computation/body memory must not depend on loading all parent bodies; total
required consumer reactions still scale withN. New bounded materialization limits must page through
large fanout, not reject it as an oversized global application plan.

The API/reference sketch, transaction/recovery model, tutorial/101/202, invariants, implementation
plan, manifest and schemas have been aligned. This remains a design package. The earlier r15.2
chronology experiment and its test/build/JUnit evidence are unchanged:3 attachment controls passed,
4 settled-history requirements failed; G1 is not accepted. No new runtime test or benchmark ran.

## Suggested reading order

Read tutorial03/04/06/10 for the logical change, then18 and17. Review API/storage and scenario
requirements against those rules, not the historical combined-closure assumptions. The
[review prompt](gpt-pro-review-prompt.md) focuses the next independent review on this boundary and
its remaining algorithm questions. [Current source excerpts](revision-15-4-source-evidence.md)
separate inspected baseline constraints from the proposed behavior.
