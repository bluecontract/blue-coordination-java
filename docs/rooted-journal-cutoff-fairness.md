# Bounded Journal continuation versus a future LIVE input

Status: isolated regression candidate; not yet executed. Base: Coordination
`9d6d8c36dba2993943391e74bf6d1222d2ddd40c`. No production change accompanies
this test. The frozen baseline remains unchanged.

## Scenario and required behavior

A is an independent source. Its T101 and T102 ticks commit A1 and A2. At T200,
B attaches exact initialized A0 and must import A1 then A2, observing counters
1 then 2. Independent C has a submitted, unexecuted tick at T300. Finishing
B's already accepted T200 execution must not require permission to execute C.

After B's attachment LIVE operation, the global selector chooses B's first
historical application. That application succeeds: B observes 1, with A2 still
pending. The next bounded continuation must eventually make B observe 2 and
report completion through T200 while C remains at counter 0. A later explicit
invocation through T300 may then change C to 1.

`RootedJournalCutoffFairnessTest` arranges this using the existing
`RootedSdkFixture`, `historical-a.yaml`, and `historical-b.yaml`; it changes no
processor handler or private field. Adjacent controls remove the future input
and check terminal NO_MATCH, distinguishing genuine zero-transition completion
from a fair-turn pause. No actual history is used to construct expectations.

## Static mechanism to verify

- `DefaultCoordinationEngine.auditNextProcessingSelection` asks
  `RootedProcessingSchedule` to select from an unbounded rooted scan.
- After a historical application, `RootedProcessingSchedule.completed` resets
  `historicalTurn`. `next` can consequently prefer C's LIVE T300 input even
  while B's older retained step exists.
- `drainJournalThrough(T200)` restricts the scan to its inclusive cutoff and
  allows only the ordinary Journal lane. C's LIVE head is absent from this
  restricted scan; B's retained head remains.
- `RootedDrainCoordinator` refuses that historical head when
  `managedAllowed == false`, and can return zero transitions, nonquiescent,
  paused. No actual execution called `RootedProcessingSchedule.completed`, so
  the next unrestricted audit can select the same future LIVE turn again.

This is a predicted liveness gap, not an observed failure yet. A bounded
zero-transition turn cannot be treated as completion solely to avoid it.
`quiescent` from the owner's exact-cutoff Journal receipt is positive completion
evidence; an original-entry acknowledgment, transport frontier, or root-local
quiescence is not a substitute.

## Fix boundary if reproduced

First evaluate a narrow retained fairness yield when the bounded ordinary lane
cannot run but exact retained work at/before its cutoff can. The subsequent
public selector must expose the appropriate existing managed-work lane; do not
execute managed work inside a Journal-only call, reorder a root's history, or
cross T200. Preserve replay of this scheduling turn and add regression coverage.
No new public API is assumed necessary before the red result is inspected.

Rejected workarounds:

- An unbounded drain would execute an input outside the original authority.
- Repeated identical no-progress commands would create an autonomous hot loop.
- Declaring the original execution complete would abandon legitimate A2 work.
- Executing arbitrary managed work despite a contradictory SDK selector would
  hide the selection/execution disagreement in the host.

MyOS must retain the original execution owner/cutoff and durable continuation
links. This possible library issue does not relax SUBMIT_ONLY, root-only scope,
failure, gas, or historical-import rules. A separate issue—blocked roots being
collected before cutoff filtering—is not claimed covered or fixed by this case.
