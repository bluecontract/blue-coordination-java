# Rooted transport-only journal selection

## Scenario

After an operation is removed, an exact broadcast naming that operation has no
receiving Root. It still requires one terminal transport result: `NO_MATCH`,
diagnostic `NONE`, zero processing gas, and unchanged document history.

On Coordination `4eebc41`, the rooted selector returned `NONE` when its Root scan
had no executable heads. `drainJournalThrough` then rejected the call with
`PROCESSING_SELECTION_MISMATCH` before the existing transport-completion phase
could report the entry. MyOS's unchanged dynamic-contract HTTP example therefore
returned 500 after its install/run/remove assertions had already succeeded.

## Correction and boundaries

When the Root scan has no executable heads, selection now asks whether the
existing transport phase can complete an unreported entry. Such work is a
`JOURNAL` turn, not a new rooted invocation. The read-only query shares the
completion phase's terminal-membership and order predicate. A blocked Root still
prevents transport completion; an executable historical prerequisite retains its
existing selection priority and mismatch guard.

The query uses actual unreported entry membership, not only `processedThrough`:
an independently imported Timeline entry can precede that transport frontier.
Completion still applies the exact caller cutoff, preserves source-order output,
and reports each terminal entry once. Selection itself writes no progress.

No protocol input, document epoch, gas counter, history, receiving set, resource
wait, or public API changes. The existing full journal/Root scan costs remain;
this correction makes no locality or throughput claim.

Rejected shortcuts: swallowing the mismatch in MyOS would hide genuinely stale
or historical selections; manufacturing `NO_MATCH` in the host would omit the
library's terminal evidence; allowing every `NONE` through the journal guard
would erase the blocked-root distinction.

## Qualification

The new `RootedTransportSelectionTest` first failed on unchanged `4eebc41` with
`expected JOURNAL, actual NONE`. Its XML and initial source are retained outside
the worktree at `rooted-transport-selection-repro.FVzhKd`.

The first expanded gate was interrupted during an existing costly cyclic
recovery fixture. Its four new failures were an invalid test comparison of SDK
receipt-wrapper object identities; the corrected oracle compares all public
receipt fields, exact document/source/event content, ordered events, and gas.
That interrupted gate is not qualification evidence.

Focused qualification passed: all eight tests in `RootedTransportSelectionTest`
(4), unchanged `RootedJournalCutoffTest` (2), and unchanged
`RootedJoinPrerequisiteNegativeTest` (2), with no failures or skips. The Java 17
run used one Gradle worker, no parallel execution, and the exact immutable
upstream tuple from `rooted-baseline-development.sM3UmD/EXPORT-COMMANDS.md`.
It completed in 56 seconds; this is a functional result, not a performance claim.
The controls cover unmatched broadcast completion, exact cutoff, once-only
completion across resident store restart, late imported transport, and real
unavailable historical work. Fresh-process external storage and the complete
application corpus are outside this patch.
