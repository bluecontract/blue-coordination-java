# MyOS Mini and Coordination POC

> **Design baseline:** 15.12 · **Documentation sync:** 2026-09-06 · **Status:** final implementation review found five open blockers; Phase3 remains planned

This package defines the first POC's processing algorithm, API shape, invariants and planned tests.
The authorized Phase1/2 implementation passed its baseline integration-readiness checks; see the
[library summary](24-phase-1-library-summary.md) and [verification record](implementation/phase-1-2-readiness.md).
The subsequent [code-review remediation](implementation/pre-phase-3-review-remediation.md) records
the completed repair gate and new verification, with F05 and complete F07 selection explicitly
assigned to Phase3. Earlier green tests are not substituted for those new checks.
The subsequent [final implementation review](review/final-implementation-review-2026-09-06.md)
reproduces five further correctness/progress defects on that repaired candidate. Its report and
separate Java witnesses qualify the earlier readiness claims; those defects are not repaired yet.
[25](25-phase-3-integration-plan.md) plans the next phase; implementation of that phase has not begun.
This is not a production release or formal acceptance of every scenario in the full catalog.
Code and documentation are consolidated on `codex/coordination-external-state-poc`.
The [source commit map](implementation/source-commits.md) identifies all four repositories;
the [r15.2 experiment archive](implementation/r15.2-experiment/README.md) preserves the old sources
without adding them to current test gates.

## Start here

Start with the [example-led tutorial](tutorial/README.md).
[22 — Selected processing kernel](22-processing-kernel.md) is the current algorithm authority;
[21](21-semantic-equivalence-and-source-reuse.md) gives its semantic controls.
Read the [revision outcome](review/revision-15-12-review-outcome.md) for the decisions and explicit
changes relative to the inspected baseline libraries. [24](24-phase-1-library-summary.md) records
what was actually implemented and refined. Earlier snapshots do not define alternative profiles.

## Design baseline and implementation handoff

Revision 15.12 closes conditional-attempt invalidation and multi-producer Entry-site alignment, and
clarifies logical versus network publication order. The user authorized two parallel implementation
tracks: owning libraries and target-shaped PostgreSQL MyOS Simple. Their incremental tests,
supervised reviews and joint readiness handshake are complete for the recorded baseline scope. Active chapters,
tutorials and API references are synchronized with that handoff. Full Phase3 integration and
measurements follow; host quotas and the deferred general interruption mechanism are unchanged.

## Selected first-POC model

- Directed Timeline completeness: Order needs its own and Agreement's relevant inputs; Agreement
  does not wait for observers' Timelines. Genuine 10,000-document fan-in retains complete guarantees.
- One continuation/FIFO interpreter, using authenticated source observation programs instead of
  final-only state or flat event replay. Inherited reaction origin composes diamond fan-in.
- Complete directed component ownership for returning workflows, including tentative expansion;
  independent observers retain their own commit, failure and gas boundaries.
- Dynamic same-origin joins preserve seed order and views. Charge/check budget before the returning
  edge; only an accepted join combines current meters/rollback. Stable seed-local events are not
  renamed. Rejected joins fail their initiating owned group; stale proposals cannot publish.
- Canonical source origin and FULL_HISTORY, independent of first materialization. FROM_* explicitly
  selects observer history; canonical source initialization and parent consumption have separate,
  fixed logical gas scopes on both cold and warm hosts.
- Terminal gas or recognized semantic runtime failure consumes one operation, not a whole range.
  The next import validates every lawful consumed gap, aligns its real pin to source-before and
  runs its own observations under one meter. Host failures never masquerade as semantic outcomes.
- Exact 0/1/N lineage/result/publication projections; no fake business batch for metadata-only work.
- Concrete planned controls for aliases, new placements, failed intermediates, dynamic dependencies,
  recovery and performance. Structural checks are not substitutes for executing them.
- Account/document quotas pause work without changing semantic gas or skipping input. No inherited
  cross-operation fuel or general semantic cancellation is added to the first POC.

## Review and implementation sequence

1. Review22 and the [decision traces](20-decision-traces.md), [scenario cards](09-scenarios.md) and
   [validation plan](11-validation-plan.md).
2. Review the [minimal API](05-poc-api.md), [Java sketch](reference-api/00-poc-minimal-boundary.java),
   storage and [delivery plan](10-poc-delivery-plan.md).
3. Read the completed [Phase1/2 handoff](24-phase-1-library-summary.md), its verification limits and
   the [review remediation status](implementation/pre-phase-3-review-remediation.md), then the
   [Phase3 integration/measurement plan](25-phase-3-integration-plan.md). Expect further iterations.

The [independent-review prompt](review/gpt-pro-review-prompt.md) asks for counterexamples to the
selected algorithm, not another list of choices or an alternative historical specification.

## Technical documents

| Document | Purpose |
|---|---|
| [00 — Provenance](00-conventions-and-provenance.md) | Current local baseline and terminology. |
| [01 — Current state](01-current-state-and-gaps.md) | Current handoff matrix and clearly separated historical baseline gap analysis. |
| [02 — Invariants](02-invariants-and-workload.md) | Correctness, progress and cost. |
| [03 — Decisions](03-options-and-decision-record.md) | Selected boundaries. |
| [04 — Architecture](04-target-architecture.md) | Library and host responsibilities. |
| [05 — Minimal API](05-poc-api.md) · [rationale](05-api-contract.md) | Actual library boundary, conceptual host mapping and remaining integration work. |
| [06 — Storage](06-storage-and-transactions.md) | Trusted reads and atomic publication. |
| [07 — Recovery](07-lazy-loading-and-recovery.md) | Lazy content, evidence, waits and restart. |
| [08 — Concurrency](08-concurrency-partitioning-security.md) | Isolation, fences and publication. |
| [09 — Scenarios](09-scenarios.md) | Human-reviewable planned witnesses. |
| [10 — Delivery](10-poc-delivery-plan.md) | Three phases, followed by iteration. |
| [11 — Validation](11-validation-plan.md) | Independent oracles and fault/performance tests. |
| [12 — Risks](12-risks-and-review-gates.md) | Implementation proof and phase exits. |
| [13 — Catch-up](13-deterministic-catch-up-repair.md) | Chronology and source advancement. |
| [14 — Equivalence](14-managed-graph-equivalence-repair.md) | Eager/lazy reference mapping. |
| [15 — Timelines](15-myos-timeline-foundation.md) | Directional membership and provider guarantees. |
| [16 — Run records](16-scenario-run-manifest.md) | Reproducible scenarios and measurements. |
| [17 — Causal model](17-causal-processing-model.md) | Chronological dependencies. |
| [18 — Independent lineages](18-independent-lineage-processing.md) | Source and observer responsibility. |
| [19 — Edge contracts](19-semantic-edge-contracts.md) | Identity, gas, recovery and representation. |
| [20 — Traces](20-decision-traces.md) | Selected outcomes and rejected shortcuts. |
| [21 — Semantic laws](21-semantic-equivalence-and-source-reuse.md) | Contrasting observation controls. |
| [22 — Processing kernel](22-processing-kernel.md) | Highest-precedence selected algorithm. |
| [23 — Implementation execution](23-first-implementation-execution-plan.md) | Parallel ownership, checkpoints, efficient tests and Phase3-readiness gate. |
| [24 — Library implementation summary](24-phase-1-library-summary.md) | High-level changes and implementation refinements relative to the authorized plan. |
| [25 — Phase3 integration plan](25-phase-3-integration-plan.md) | Runnable graph adapter, readable correctness stories, durable faults, measurements and next iterations. |

[101](101-processing-model.md) and [202](202-durable-host-integration.md) remain optional technical
summaries. [Review material](review/README.md) includes schemas and immutable earlier outcomes.
Existing review ZIPs are historical snapshots; they have not been regenerated by this documentation
sync. Use this directory for the current proposal and the linked worktrees for runtime implementation.

The retained [r15.2 experiment](implementation/phase-1-chronology.md) reports three passing attachment
controls and four failing settled-history cases on that earlier candidate; it is not the current
implementation result. Current integration-readiness evidence is in the
[Phase1/2 record](implementation/phase-1-2-readiness.md). This does not constitute formal all-catalog
G1 acceptance. One local stack; no release.
