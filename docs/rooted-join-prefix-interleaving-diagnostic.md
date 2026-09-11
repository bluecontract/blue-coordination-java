# Original LIVE prefix before a cyclic terminal join

Status: test-only probe02 completed with the eligibility failure below. Production remains byte-identical to
Coordination `cf35914e`; the existing cycle03 and acyclic tests are unchanged.
The missing B reaction is a bug. This diagnostic chooses an implementation
route; it does not ask whether the reaction is required.

`RootedJoinPrefixInterleavingTest` repeats the same five authored inputs and
asserts their exact BlueIds. It records B's original LIVE300 input, processes C
only through the nonterminal A4-to-A5 import, and stops before the registered
C application of A5-to-A6. C has emitted its new token, while B is still zero.
Capturing B again must retain the identical original semantic input despite
C's independent progress. This is ordinary capture, not mutation or replacement
of any previously captured publication fence.

The probe then uses public processing for that exact B300 input and B's normal
local prefix, stopping before its own A5-to-A6 calculation. It records all plan
identities/statuses and compares selected versus independent heads, epochs,
bodies, rows, graph generations and component identities. The fixture evaluates
the real next local input and applies the unchanged prospective-owner publication
guard, without publishing anything. A fresh runtime executes that identical
rooted input for complete result/event/receipt/checkpoint/companion and gas/trace
comparison. Independently published A/C histories and all existing plans must
remain unchanged during B's nonterminal prefix. B must already have received
the forwarding token before any terminal publication is considered.

If the guard passes, the result establishes exact owner alignment for this
prefix only. It does **not** prove a complete repair: B's local work and C's
separately registered application still require exact atomic plan/application
reconciliation. The test deliberately does not publish or mark that work done.
If public processing blocks or an owner fence fails, the diagnostic retains the
actual failure rather than bypassing selection, substituting current heads,
rewriting a Language result, or replaying an event at join. That identifies the
remaining causal-view boundary for the implementation.

No tariff, gas limit, frozen source endpoint, input, processor, witness role,
publication fence or production API is changed. Run only this diagnostic class
in the parent-owned serial verification lane using the same immutable
Language34e9/BEX/Catalog tuple as cycle03.

## First run: READY-view observation error

The parent-owned first run compiled and executed one test, failing before B's
prefix at the intermediate C counter assertion (11.627 JUnit seconds). The
probe read `DocumentHandle.snapshot()`, which deliberately returns the earlier
application-visible READY head while C is CATCHING_UP. It did not read the
current committed processing state. `SdkCoordinationRuntime.snapshot` calls
`engine.document`, while `advanced().auditDocument` exposes the committed head.
The next captured input already passed the exact A5-to-A6 revision assertions;
this failure does not establish missing representation processing or a changed
forwarding result.

Intermediate counter and head comparisons now use the committed audit view;
compact diagnostics explicitly print both committed and READY positions.
Selection stops only at the actual complete terminal cause: a numbered step
at its selected endpoint without a successor carrier, or a representation step
whose authenticated target position is reached. Earlier same-epoch work is
processed through ordinary selection. The required B1 assertion is unchanged,
and C's actual retained forwarding event is also checked before B runs.

First-run source/result archive:
`legal-detached-retarget-evidence.fKnxrU/join-prefix-01.tar.gz`, SHA-256
`aa064664ff095fd2674603f4b26f45353161533c4d5a563a2263ad13d3ac667e`.
No B-prefix, owner-alignment or publication conclusion is attributed to it.

## Second run: same-boundary eligibility blocks the original LIVE prefix

The corrected probe reaches committed C7 with `observed=1` (READY remains C0),
before the genuine terminal A5-to-A6 application. B's original LIVE300 invocation
identity is unchanged. Its public processing returns `NEEDS_RESOURCES` /
`PROCESSING_BLOCKED`; root selection has no selected LIVE, managed or local work.
The test fails before executing B's prefix, so owner alignment is still unknown.

The immediate blocker is `RootedCheckpointDriver`'s call to
`RootedJoinEligibility.blocks`: the selected LIVE order equals the pending join's
boundary, and the current no-overtake predicate includes equality. This happens
before processor source-discovery prerequisites are evaluated. Existing
`RootedJoinEligibilityTest` explicitly protects equality, including an unavailable
join, so replacing the comparator alone is not a qualified correction.

Parent-owned source/result archive:
`legal-detached-retarget-evidence.fKnxrU/join-prefix-02.tar.gz`, SHA-256
`e35a5d4cd1e1ffc722325c84057c5e25e6708a62e37ab7523be684a8c456076f`.
