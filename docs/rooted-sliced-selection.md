# Rooted scheduling across bounded host calls

## Problem

Source S has committed epochs 1 and 2. Parent P attaches S0 and must import
those epochs. After P imports epoch 1, an unrelated U receives a new entry.
The rooted audit previously advertised JOURNAL when the host had an admission
queued. After admission, the journal-only drain audited again without that
hint, selected P's epoch 2, and rejected U's command with
`PROCESSING_SELECTION_MISMATCH`. MyOS's failure-isolation and immutable-retry
scenarios exposed this mismatch.

There was a second problem: the full rooted drain deferred failed roots only
inside one call. Bounded managed calls retained neither fair turns nor failure
isolation. A failed historical root could therefore monopolize subsequent
calls even while an unrelated document had executable work.

## Correction

One internal schedule selects **between** the canonical heads produced by
`RootedCheckpointDriver`. That driver still determines each root/owned SCC's
earliest eligible LIVE or retained step. The schedule never searches past a
failed historical step for a later entry in the same root.

- A journal turn yields to a historical turn; a historical turn yields to
  independently eligible journal work. Historical owners rotate within rounds.
- Failed historical work is isolated by canonical owner and exact work identity.
  Other owners may progress. When no independent work remains, the unchanged
  failed work is exposed for an explicit retry or host parking.
- Audit is read-only. Only actual execution changes scheduling state. Global
  audit, journal slices, targeted managed slices and full drain use the same
  selector. Explicit root-local processing updates the corresponding owner turn.
- An admission hint neither reserves work nor proves that its target is ready.
  If the target's own next obligation is history, journal execution cannot skip
  or consume that obligation. The existing mismatch guard remains.
- A terminal failed **local retained** calculation is not a successful step.
  Only published retained results permit progress; otherwise full drain must
  defer that root too. A failed LIVE input remains governed by its existing
  terminal-input rules.
- Physical execution/publication failure is not a semantic decision to isolate
  or skip work. Thrown failures do not advance scheduling state; explicit
  publication-failure results retain their existing distinction.

No authored data, execution policy, public API, root-local order, invocation
scope or logical gas rule changes. Source histories and pending consumer work
are neither rewritten nor acknowledged by another owner's operation.

## Verification boundary

The direct SDK regression reproduces the audit/admit/drain mismatch on parent
`04a1d8f`. Controls cover successful historical interleaving, gas-failed history
with later same-root and independent inputs, read-only audits, exact-work retry,
and historical owner rotation. Historical results are compared with a fresh
materialized reference, including output closure, total gas and complete trace
identity. Explicit source-receipt content and request values supply the reference's
exact cause resources; it never consults the live host's provider or current head.

Store-restart controls preserve resident scheduling state. They are **not** a
fresh-process external-state restoration claim. The global resident driver's
existing scans are also not the scalable host work index planned for the POC.
MyOS's durable command/replay controls must be rerun with the exported correction.

## Recorded qualification

On the unchanged immutable upstream tuple (Language `8065364`, BEX `ab72af1`,
Catalog `0b68744`), the ten-owner SDK gate ran 27 tests: 26 passed and one
new two-owner test failed **before calculation**. Its reference helper selected
root-local work but supplied an empty exclusion set to the production capturer,
which correctly enforced global due order. The helper now passes the rooted
driver's actual exclusion set, exactly as production execution already does.
The guard and production calculation were not weakened.

The affected rerun passed all four tests (three sliced-scheduling cases and the
local retained-failure case), plus Javadoc. The latter also corrects the test's
`DrainBudget` argument order: one allowed commit and two allowed selections prove
that one failed retained input is not attempted twice in a full drain call.
Together these runs cover the 27-case gate; this is not a new full-suite result.

Exact source/results archives are retained in
`/Users/kamil/Documents/Projects/Blue/rooted-sliced-evidence.rnHUAx/`:

- `gate-01-results-and-source.tar.gz`, SHA-256
  `145a7f324eeab14195d904515e4ba002354efa791aef046a87d1d080cba7390d`.
- `gate-02-results-and-source.tar.gz`, SHA-256
  `c8de1c22babb2af43300320ad2c39d765a224970798f3e02e3b67e1c044d1173`.

The first run's 15m18s and rerun's 2m14s are qualification durations under
concurrent unrelated JVM load, not processing-performance measurements.
