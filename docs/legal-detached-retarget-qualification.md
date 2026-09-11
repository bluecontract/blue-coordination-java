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

Rejected alternatives: rewriting B's original input reservation; accepting old
demand identities on a new base; borrowing B's identity for C; replacing the
newer C primary with C0; restoring the MyOS B-reactivation workaround.

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

The corrected Language focused suite has passed the legal current/historical
cases, but its complete focused gate, new source/manifest binding, downstream
SDK tests and the final MyOS acceptance tuple are not yet qualified. The
independent cyclic-join B-reaction bug also remains a merge gate. No golden
business result, logical gas rule, publication fence, or acceptance deadline
is relaxed by this correction.
