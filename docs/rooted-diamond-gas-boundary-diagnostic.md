# Actual admitted diamond gas boundary (diagnostic)

This test-only branch starts from `7c1fbde7d27bd1901eee8c6eae02ee1359423d50`.
It changes no runtime, policy, tariff, scheduling or receipt rule. No test or
build has run here; this is authored diagnostic coverage, not qualification.

The existing gas-1 paired control passed at the same blocked endpoint. It did
not establish behavior around the successful original's final charge. The new
`RootedDiamondPeerSchedulingTest.resolvedOriginalGasBoundaryMatchesAcrossCompletePhysicalSchedules`
calibrates **G** from the actual retained B-original `ClosureProcessResult`
after ordinary public C-original/C-prefix and D-original/D-prefix processing.
Calibration uses the finite release-default policy, verifies that the actual
resolved invocation includes frozen A and independently published D, and checks
that B alone is its entry owner. It does not use a raw capture missing A/D, an
unlimited processor, a substituted retry, or a test-only publication path.

For each of G−1, G and G+1, two identical authored cases use the same explicit
B-original limit and policy label. One calls B before C; the other prepares C/D
first. Both then call B with that same policy and use ordinary bounded drain.
Intermediate wait timing is recorded, not assumed equal. If no B terminal is
available, the diagnostic stops at the observed wait rather than letting a
default-budget drain admit that original. A terminal B result is never retried
under a larger policy. Every actual call's reported gas is summed; retained
evidence is not used to deduplicate genuine failed charges. Aggregate call gas
is recorded rather than required equal: repeated genuine completed attempts
may add charges without changing the retained logical result. Exact retained
gas traces and results remain part of the schedule-equivalence assertions.

The comparisons retain complete accepted gas entries and rejected-charge
identity/details; original input, cause, policy, status and output identity;
owned before/after values and commit companion; all new retained terminal
inputs/results; final document/receipt/gas/plan inventories and event counts;
actual blocked/quiet disposition; and unchanged evidence after restart. The
prepared case must fail at G−1 and succeed with exact total G at G and G+1.
All three comparisons run through `assertAll`; the gas-1 test is not selected.
The maintained `RootedGasEvidence` writer also extracts lossless B-original
charge records to `build/rooted-evidence/gas/diamond-boundary-*.json`, with
distinct calibration/schedule/budget names. Console evidence records actual
call dispositions and the complete final terminal inventory.

## Continuation policy is separate and explicit

`ContractsClosureAdapter.captureRoot` accepts the B-original per-call policy.
By contrast, `nextRootLocalHistory` passes `profile.executionPolicy()` through
`RootedLocalHistory.select/capture` into each new retained input. The joined
terminal uses the actual local invocation selected by `captureRootedJoin`.
Thus this fixture's later B-local and joint operations use **100000 /
release-default**, not the earlier B-original G±1 policy. Their actual retained
policy identities, limits and labels are recorded and compared across matching
schedules. This diagnostic does not call the whole multi-invocation history a
single G-budget operation or invent policy inheritance.

The original same-high-gas comparison and dormant-primary reconnect remain
separate owners. A pass here would not by itself close either one or qualify
the original MyOS ring, full library gates, or arbitrary gas boundaries.
