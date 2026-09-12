# Exact selected-root work audit

## Observed problem

MyOS `RootedProcessingApiIntegrationTest`
`boundedExecutionResumesPreExistingRootLocalHistoryWithoutConsumingFutureSubmit`
reached `IllegalStateException: Managed work lacks its invocation-owned
lease/attempt sha256:b0a751cec2462e238d486a52f9242eb90a286df9bf5b150d205a9952e1335bb8`
in `ManagedCatchUpProjectionService.requireBoundWork`, through `persistAttempt`,
`persistDrainEvidence`, `ProjectionSynchronizer` and `CommandFinalizer`. The
retained evidence includes the second deeper writer-stack capture in the parent campaign;
its archived evidence SHA-256 is
`f87c2fc022116079e3787f46aa9355a1861cb24a4756960d071cf6b57c9614dc`.

The setup is A → B; B attaches C whose C0–C3 history already exists. The global
fair selector reports `JOURNAL` for A. The explicitly queued
`processing().processNext(B)` instead selects the next managed application
B ← C1. The host had audited only global selection and therefore never acquired
that managed work's invocation-owned lease before execution. Finalization
correctly rejects missing authority. Replaying prior applied results cannot
repair the missing pre-execution ownership.

## Minimal API correction

`advanced().auditNextRootProcessingSelection(DocumentHandle root)` returns the
existing `ProcessingSelection` for exactly the driver used by
`processing().processNext(root)`, without global fair-lane selection:

| Actual selected step | Audit result |
| --- | --- |
| Root-local retained history only | `MANAGED_EPOCH_APPLICATION`, exact work and `rootedRetainedRoot` |
| Independently owned history or a joining historical application | `MANAGED_EPOCH_APPLICATION`, exact work, no local-only root marker |
| Root's eligible LIVE input | `JOURNAL` |
| No runnable step, including a dependency wait | `NONE` |

`NONE` is **not** a completeness or readiness proof. Selection does not execute,
reserve work, consume entries, update heads/history or advance the global fair
turn. Repeated audits may reuse existing internal read caches; their operational
cost is not logical gas. The SDK enforces the same owned-handle, known-document
and open-runtime requirements as `processNext(root)`.

The host must serialize audit, its durable ownership acquisition and execution
against competing writes. This API is not an atomic reservation token. It also
does not authorize a new command, inherit another root's execution cutoff, or
replace MyOS's original accepted invocation, lease and attempt checks.

## Why not the old API or after-the-fact binding?

`auditNextProcessingSelection()` intentionally predicts a **global fair** turn;
it cannot describe a host-selected root while another root owns that turn.
Changing its semantics would break existing scheduling. The availability-hint
overload does not identify a root either. Deriving work from the execution result
is too late for a durable pre-execution claim and weakens recovery ownership.

The SDK delegates through a package-private runtime method to one new method on
the existing internal `DefaultCoordinationEngine`, whose existing public
`processNextRoot` already provides the execution seam. No `CoordinationEngine`
interface method, new public value type, selector/comparator, scheduling rule,
processing operation, gas rule or persistence format is added.

## Verification scope

Authored here; no JVM/tests were run while preparing this patch:

- `RootedExplicitSelectionAuditTest`: actual managed C1 versus unrelated global
  LIVE selection; repeated audits and restart preserve histories, selected view,
  journal and global turn; actual `processNext(B)` executes exactly the audited
  work. LIVE/NONE agreement and foreign/null/missing/closed input controls.
- `RootedLocalHistoryRecoveryTest.retainedCauseDoesNotRepeatItsOriginalOperationSelectors`:
  repeated explicit-root audit, local root/work identity versus the actual
  retained result; existing complete trace, gas and reference equality retained.
- `RootedTransportSelectionTest.unavailableHistoricalWorkCannotBeBypassedByUnmatchedTransport`:
  blocked root reports `NONE` but remains non-quiescent and preserves histories.
- `RootedJoinSccEntrypointTest.eitherOriginalOwnerSelectsTheSameJointTerminalButAcquiredConsumerStillWaits`:
  both original SCC handles report the same joint managed work (not local-only
  retained work), while the acquired consumer remains blocked; existing actual
  publication/reference and unchanged-state assertions are retained.

The maintained global selection, sliced fairness, automatic join and source
prerequisite suites remain relevant unchanged controls. The MyOS missing-lease
scenario must also pass with correct host ownership binding; the library audit
alone is not an application acceptance claim.

Source shape against `9afcffe98040d397676b6628485958db0190e2cd`: 254 production
sources and 87 public API/SDK source types unchanged; 74,231 → 74,262 lines
(+31). The two public additions are the SDK method and its existing internal
engine seam; the runtime bridge is package-private. The shape guard records
that exact delta, not additional implementation headroom.
