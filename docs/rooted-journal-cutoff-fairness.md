# Bounded Journal continuation versus a future LIVE input

Status: defect reproduced; narrow correction awaits qualification. Base:
Coordination `9d6d8c36dba2993943391e74bf6d1222d2ddd40c`. All changes remain on
the isolated `codex/rooted-journal-cutoff-followup` branch; the frozen baseline
is unchanged.

The first isolated run (`journal-cutoff-red-01`) passed the terminal NO_MATCH
control; both history cases stopped in setup because they inspected B's public
READY-only snapshot while its import was still pending. That snapshot correctly
still showed 0; it did not establish the committed prefix was wrong, and this
run did not reach the suspected fairness boundary. Setup now inspects the
committed prefix through `auditDocument`, following the existing
`RootedSlicedSelectionTest`, and separately requires the READY snapshot to remain
at 0. It also verifies the source receipts contain the real RCP2/Tick events and
counter transitions. Final READY state and ordered log assertions are unchanged.

The corrected `journal-cutoff-red-02` run, test commit `0079e4a`, passed both
controls and reproduced the actual liveness defect: eight successive T200
Journal calls returned zero transitions, `quiescent=false`, `paused=true`, with
B's committed prefix still at 1, its READY snapshot at 0, and C unprocessed.
The archive `journal-cutoff-red-02-build.tar.gz` has SHA-256
`0fa5eb8893cc52a12b39f8359a0db32fe66d94f482598390255851aca9936e34`.
That is red evidence, not a pass for the correction below.

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

## Confirmed mechanism

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

The isolated regression confirms this liveness gap. A bounded zero-transition
turn cannot be treated as completion solely to avoid it.
`quiescent` from the owner's exact-cutoff Journal receipt is positive completion
evidence; an original-entry acknowledgment, transport frontier, or root-local
quiescence is not a substitute.

## Correction and rationale

When `RootedDrainCoordinator` cannot execute a selected retained head because
the current call is Journal-only, it asks `RootedProcessingSchedule` to retain
a historical yield before returning the existing paused result. The selected
head already belongs to this call's cutoff-filtered scan. This changes only
host scheduling state: no PROCESS, source receipt, epoch, gas charge or event
is created by the yield.

The schedule sets `historicalTurn` and removes only the selected root from the
current yielded round. Removing that root is necessary because it already
consumed A1; an unrelated historical root outside the bounded scan must not
keep this eligible root at the back of that round. Existing failure isolation
is checked and never cleared. Read-only audits remain read-only.

The next selector exposes the existing managed-work lane for A2. That separate
invocation uses the unchanged source/work identities and historical execution
path. A subsequent T200 Journal acknowledgment can report quiescence with no
new document commit. Only a later independently authorized T300 invocation
executes C.

The regression additionally requires the yield to have zero committed
transitions, no managed application and unchanged source/parent/C histories;
it must expose exact A2 work and preserve that selection across the existing
store-restart control. The final ordered history and future-input assertions
remain unchanged. The correction has no public API or protocol binding change.
Source-shape accounting increases the measured production-line allowance by
exactly 16 (74,206 to 74,222) across two existing private sources; the 254-source
and 87-public-type allowances are unchanged. These are build inventory limits,
not runtime processing limits.

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
