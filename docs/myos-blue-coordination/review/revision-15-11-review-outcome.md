# Revision15.11: pre-edge scope admission and semantic failure continuation

> **Status:** documentation/API-plan refinement; no runtime implementation approval or conformance claim

This applies the agreed corrections to the selected design. It does not introduce another execution
profile, a generic failure-policy framework or a new general cancellation feature. [22](../22-processing-kernel.md)
remains the algorithm authority; earlier revision outcomes are preserved as historical records.

## 1. Dynamic coupling without retroactive gas or identity changes

Keep one-way Agreement/Order work independent. First touch establishes readiness, not ownership.
Freeze pre-origin execution-seed order and exact continuation-site views. Before a new returning
edge joins currently independent same-origin groups:

1. Require the identical frozen processing policy/limit L for the first POC.
2. Charge the normal admission-check cost c on the initiating current group before the check. If
   that charge cannot fit, fail that group with ordinary GAS_LIMIT_EXCEEDED; do not admit the edge.
3. Sum distinct current group gas, including c once, with overflow-safe arithmetic. If it fits L,
   admit the edge and shared ownership together. No refund, reset or additional allowance follows.
4. Otherwise fail only the initiating already-owned group before mutation, using the proposed
   recognized semantic RUNTIME_FATAL category AtomicScopeGasAdmissionFailure. Retain its actual
   gas and a separate required-scope-sum/limit diagnostic, not an over-limit settled ledger.

| Limit100; c1; B attempts the returning edge | Required outcome |
|---|---|
| A60 and B30 | Admit at91; remaining9. Subsequent failure rolls back A+B. |
| A60 and B50 | Reject before joining; B fails with actual51. Required111/limit100 is diagnostic. A is not acquired. |
| Already joined AB attempts to add C | Rejection rolls back AB only; admission followed by failure rolls back ABC. |

Decisions belong to canonical attempted prefixes, not the graph remaining after rollback. Discard
dependent speculative observations of a failed producer and rebuild the dependent against its
authenticated failed outcome; never publish those effects or their gas. Do not reinterpret the
attempted admission from that final rollback graph. Worker order cannot define prefixes or scope.

Execution-seed and seed-local emitted-occurrence identities survive a later join. Final atomic
operation/settlement authority is derived separately from selected seeds/predecessors and accepted
admissions. It is not visible to earlier business execution and cannot rename prior emissions.
This replaces r15.10's retroactive final-anchor/shared-meter reconstruction, which could exhaust gas
before the very edge used to discover the final group. It is an explicit owning-library algorithm
and constructor change, not a host wrapper or demonstrated current behavior.

## 2. RUNTIME_FATAL is semantic, not a catch-all exception bucket

The local Contracts specification defines runtime-fatal as deterministic processing failure after
admission. Given the same exact input, runtime, environment and policy, a recognized semantic fatal
error should be treated like deterministic gas failure for managed live/historical delivery:

- roll back the operation, retain the exact outcome/gas, create no successful epoch or imported pin;
- terminally account its selected deliveries, then permit the next canonically eligible input;
- validate original contiguous source history and all lawful consumed gap outcomes, including
  composite external-policy failures, before the successor's explicit source-before alignment;
- do not consume an unknown/nonterminal gap or invent successful continuity.

This depends on repairing the owning-library exception boundary first. Reviewed typed semantic
errors get portable diagnostics. Unknown RuntimeException/internal bugs, IO, cancellation and
wall-clock timeout are operational failures or typed waits, without terminal input consumption.
An external consumption policy cannot legitimize misclassified operational failure. Failed
initialization still establishes no usable source; it does not gain a skip-and-continue rule.

Inspected local evidence (not newly executed tests):

- `blue-language-java/.../specifications/blue-contracts-and-processor-specification-1.0.md:4847`
  defines runtime-fatal; sections12.2–12.4 require portable diagnostics, charge-before-work, rollback
  and a separate resource-wait boundary. There is no portable attemptedWork ledger.
- `blue-language-java/.../processor/ScopeHandlerDispatcher.java:307` catches broad RuntimeException;
  `ExecutionLifecycleCoordinator.java:84` converts runtime failure to RUNTIME_FATAL. Existing
  wrapping therefore needs narrower classification; the enum alone is not sufficient evidence.
- `blue-bex-java/.../ProcessorExecutionContextBexGasLedgerHost.java:116` maps local BEX gas exhaustion
  to ProcessorFailureException/GasLimitExceeded. A recognized deterministic runtime-fatal need not
  mean JVM failure or only top-level gas exhaustion.
- `myos-simple/.../ManagedCatchUpContinuationPolicy.java:17` includes runtime/gas/portable failures
  in a retained rollback/retry predicate. This is not the proposed terminal next-input continuation
  and does not mean an unchanged retry repairs deterministic failure.

## 3. Supporting consistency corrections

- Target reactions compose the full producer set at one inherited origin plus any own direct seed;
  they are not separate target epochs per upstream receipt. Different source operations remain
  ordered separately. The two-event/new-placement controls check exact frozen delivery timing.
- Accepted FROM_NOW/history cuts survive availability-only retry. A changed unaccepted candidate
  may be recaptured; it is not permission to shift an already accepted semantic boundary.
- Host locks include every primary/joined work row in canonical order. Retain the complete joined
  plan by CommitKey and superseded-work aliases for uncertain-commit reconciliation after restart.
- Trusted OutboxPort.readNext verifies interior activated-prefix membership against its immutable
  root with bounded evidence. Endpoint inclusion alone is not proof; no extra public proof DTO.
- Timeline entries use1..MAX_SAFE−1 and exclusive completeness guarantees may reach MAX_SAFE.
  Operational timestamps keep their own domain. Endpoint admission/rejection is explicitly tested.

The tutorial, API sketch, invariants, failure/storage contracts and phase plan are synchronized.
The existing53 schedules/nine named subcases remain; J/L and EQ variants add accepted/rejected join,
stable-ID, fault-classification, mixed-gap and boundary witnesses without a new catalog hierarchy.

## 4. Scope and evidence

Phase1 implements and independently tests the owning-library changes. Phase2 supplies target-shaped
PostgreSQL/Timeline MyOS Mini. Phase3 integrates correctness checks and measurements, then informs
further library/host iterations. Only the user authorizes implementation.

Fixed operation gas, independent source progress, bounded host scheduling/quotas and the deliberate
deferral of general interruption for an endless sequence of successful imports are unchanged.
No runtime/test/build files were changed for this refinement. No processing test, PostgreSQL test
or benchmark was run. The retained r15.2 experiment remains3 passing attachment controls and4
failing settled-history cases; G1 is not accepted. Structural checks cannot prove semantic correctness.

Structural QA passed for47 active Markdown/map/outcome documents,400 local links,52 anchor links
and three Draft2020-12 schemas. The Java sketch compiles with Java17 targeting and all lint warnings
enabled. All14 existing Mermaid blocks and20 historical review/experiment files match r15.10;
unchanged diagrams were not re-rendered. The runtime/test/build content hash is unchanged, including
the pre-existing build patch. The final manifest and r15.11 archive are verified against the payload.
