# Complete saved-original rings through the ordinary public driver

## Problem

The native Java 17 run on Coordination `905ad89e` and Language `e28ce805`
failed two `RootedSavedOriginalGraphTest` variants at `run:246`:
`threeNodeRingCompletesAfterExactPreAnchorSourcePrerequisites` and
`threeNodeRingWaitsForEarlierSourceWorkBeforeFreezingItsJoinAnchor`.

With A → B → C and a new C → A attachment at T300, the tests first prove A's
exact earlier prerequisites: B's T200 input and a root-local C0 import. C's
attachment then succeeds, and C imports A through cursor 3. Terminal A4 waits
for B's original same-cause work. The old helper repeatedly invoked only C
and incorrectly required every such call to make progress.

`RootedJoinScheduling.select` checks the original receivers' prerequisites and
entry-owner scope. An eventually acquired C is not an alternate entrypoint for
B's original calculation. `RootedJoinSccEntrypointTest` independently requires
this C-only wait to preserve state and consume zero gas. The ordinary-driver
saved-ring sibling already completed; pre-anchor source readiness and final
join eligibility are different conditions.

## Correction and rationale

Complete the final attachment in all three ring variants using the existing
public `drain(new DrainBudget(1, 1))` driver. Do not force B, invent a scheduler,
move a source head, or change the logical cutoff. Keep the explicit two source
prerequisites, suspended/reconstructed retry, full history prefixes, active
occurrences, event counts, later operations and restart comparisons.

A shared 32-call budget counts initial calls, pre-anchor work, retries and the
final readiness confirmation. This is stricter total accounting than the old
32-iteration loop plus outside calls; the numerical cap is not increased.
The three variants enter the final driver after 1, 3 or 5 actual calls.

An already successful initial C operation seeds the completion assertion.
Later transport reconciliation cannot substitute for that application.
Every entry must identify C's supplied T300 input or an actual earlier
attachment. Inspect all live, local and managed outcomes, including successful
closures inside `NO_MATCH`; reject failure instead of masking it with aggregate
progress. Preserve the check against repeating a committed closure.

## Scope and verification

Only test/helper mechanics change. No runtime, gas, ordering, admission,
topology, history or public API rule changes. The five-case saved-graph owner
and original-SCC entrypoint control are included in the affected group.
Execution results belong to the exact successor's external qualification
receipts; the original failed breadth result is retained unchanged.
