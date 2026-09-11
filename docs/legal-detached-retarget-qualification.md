# Legal detached-path attachment: candidate and qualification

This correction supersedes the inactive-retarget rejection in the
[baseline registry](rooted-baseline-release-fixes.md). CTO/user confirmation is
recorded in [confirmed behavior](rooted-confirmed-behavior-corrections.md).
The earlier qualified artifacts and rejection-only test archives remain
unchanged; they do not qualify the corrected behavior.

## Problem → solution → reason

P embeds B at `/orders/same`. P removes that value, committing an inactive B
reservation. Later P installs C at that same path. The former implementation
either rejected the valid operation or kept asking for evidence. MyOS worked
around it by first reactivating B; that is not the required user operation.

The input reservation stays unchanged. Coordination supplies authenticated C
content/history and a resolution of the processor's actual demand. Language
consumes that resolution at the effect boundary and derives the new C binding.
The reserved generation is reused; C gets a fresh occurrence identity because
its target identity differs. Current C activates with ADD. Saved C0 with C1
already available establishes C's own catch-up cursor, then imports C1 in the
normal historical lane. Nothing imports B's retired history into C.

Coordination no longer replaces a captured source view with a special old-state
"rejection witness". Another path already using C1 keeps its exact selected
view while this path requests C0. Independent B/C heads and receipts must not
change as a side effect of P's attachment or import.

Adding C to the read set changes the authenticated invocation input. A demand
from the preceding, smaller read set must not authorize the expanded input.
After expansion, PROCESS emits a fresh demand and the adapter resolves that
exact demand. It does not synthesize or rewrite demand identities. Resolutions
are retained only when the input identity itself is unchanged.

The automatic lineage resolver must also release the old target restriction
for a committed inactive slot. Previously it selected B before even searching
for C, so explicit C selectors worked while ordinary inline/BlueId C did not.
It now falls through to ordinary exact C discovery only when the supplied
value is absent from B's indexed current/authored/initialized/retained states.
If it belongs to B, B keeps its stable-lineage preference and ordinary replay
proof requirements. A pending import is not a detached slot; its existing
source/receipt restriction remains. Ambiguous foreign lineages still fail.

Rejected alternatives: rewriting B's original input reservation; accepting old
demand identities on a new base; borrowing B's identity for C; replacing the
newer C primary with C0; restoring the MyOS B-reactivation workaround.

The revised Contracts specification has SHA-256
`5cc29e91cd8d4aa4d3dca98214da5ceb49b2daa82554ac561260bd98ce5063b8`.
The Coordination profile, SDK release properties and two source-isolation
fixture inputs are bound to those exact bytes. This is an explicit new
Language/Coordination candidate pair, not permission to run the new selection
rule under the old specification identity. Full generated release bindings
and downstream qualification must use the same pair.

## Maintained regressions

- `RootedRecreatedCollectionOccurrenceTest`: committed B removal followed by
  current C, immediate activation without fictitious 0-to-0 history, source
  isolation and restart; existing same-lineage and gas controls remain.
- `RootedRetargetInputTest`: exact C0 selection, C1 catch-up and ordered parent
  reaction, original B input row, successful replay and restart; existing
  active-retarget and exact gas-boundary controls remain.
- `RootedFrozenHistoricalRetargetReproductionTest`: C1 is already present at a
  second path; C0 resolves the real demand without changing that frozen C1 or
  its binding. The retained result binds the actual retry identity.
- MyOS `RetainedManagedEpochCatchUpIntegrationTest` restores the original
  direct C attachment after B1, B2 and reserved generation3, expecting C3 and
  complete B/C history plans. Commit `2522fa3` contains the test restoration,
  not an application-runtime workaround.

## Evidence and remaining gates

Serial control run against the old qualified Language34e9: **8 tests, 5 pass,
3 fail**. The current-C, historical-C and frozen-C tests fail under the old
rule. Its intermediate draft also exposed the stale-demand/base mismatch,
which the adapter change above corrects. These are negative controls, not a
claim of successful integration.

Archive: `legal-detached-retarget-evidence.fKnxrU/coordination-old-language-control-red.tar.gz`
(sibling of the worktrees), SHA-256
`3f1b417ad5609e307ef24e63666f9c22ea9e39658b9c22ac2a46d3f993722341`.

The corrected Language focused gate passed **94/94** tests on source `1354663`
with the new specification binding. Archive `language-focused-03.tar.gz` in the
same evidence directory has SHA-256
`5edb19b42f5e970259be11ee89b15d053cdc6f2c99bc8a23eb1b4431c80d9b7e`.
Its generated release is
`sha256:56e69e4260d87261aafc5158a0c68815bb5451b6c33cf100795a820eccd490da`
and fixture package is
`sha256:9323cd0b2b4202c08d8165a99102aa6d8a52f3e718fc33647e34ba211859a60f`.
Coordination's SDK profile and exact binding assertions select those reviewed
identities; gas tariffs and cyclic identity/proof bindings do not change.
The full library gates, downstream SDK tests and final MyOS acceptance tuple
are not yet qualified. The
independent cyclic-join B-reaction bug also remains a merge gate. No golden
business result, logical gas rule, publication fence, or acceptance deadline
is relaxed by this correction.

The first SDK run on sealed Language `5a103afb` and Coordination `204babc`
completed 15 tests: eight passed, seven failed. Five exposed the remaining
automatic resolver restriction above. Two completed the intended attachment
and ordered catch-up, then failed a new test's incorrect replay expectation:
`process(root, entry)` evaluates that supplied input against the current view;
the old exact-version operation is `STALE_TARGET_DOCUMENT` after successful
advancement. Tests retain exact head/history and original-receipt checks while
requiring that stale result. Failed gas-boundary retries still use the unchanged
input and retain their exact failure/result/gas checks. No runtime replay rule
is changed to satisfy these assertions.

Archive `legal-retarget-sdk-01.tar.gz`, SHA-256
`5ace80b97f408f45c1fe66c160736b6afd77c5c53f2173fa0fcbd880fbd99b04`,
preserves the complete red run. The automatic-resolver correction and adjusted
API-level controls require a subsequent passing run; this is not a pass claim.
