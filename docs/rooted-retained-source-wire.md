# Retained source wire consistency at a terminal reciprocal join

## Problem and exact example

The reciprocal MyOS lab has two independently initialized documents. A keeps the
full authored B as ordinary, nonmanaged `/candidate` content, then imports B0/B1
at `/embeddedCandidates/selected`. B subsequently imports authored A through
`/peer/a`. Under the canonical schedule A0, A1, A2, A3, the terminal A3 import
must join the reciprocal component without reprocessing either source history.

On Coordination `dd719fb6ec38a953b5814e6b08e6a93c29c90fa2` and Language
`7ec0fdaafff41ad8e41387e7e5647d5a800d807c`, A3 instead stopped with
`INVALID_PROCESSING_DOCUMENT / MANAGED_OCCURRENCE_BINDING_MISSING`:
"Imported managed event target no longer proves the source transition".
The failure consumed 463 gas. This remained after correcting the earlier live
input overtaking defect: the selected source, physical source, and publication
fence all identified A epoch 3, `HbW4zad9gcjk82tzdsqP6eGvR8Ep6XjNRHeVsxetX51n`.

The source receipt and frozen rooted snapshot represented the *same exact A3*
with different transport forms. The capturer independently collapsed the cause's
ordinary `/candidate` to a provider reference, while the original frozen source
retained full inline B. The inline value's direct BlueId is exactly the reference
`3NrFVZKSNGapns9RYLe77NEkf2NiJwuuWDEDkyJtZSkK`. After lawful cyclic finalization,
the imported-target guard compares local source content with managed references
normalized; the remaining nonmanaged wire difference correctly prevented it
from concluding that these differently captured operands were identical.

## Narrow solution and rationale

`ManagedEpochInvocationCapturer` first performs the existing complete retained
source-receipt verification. Only in rooted mode, and only when both the source
document ID and exact receipt successor BlueId equal the source in the frozen
input snapshot, the managed-revision cause now reuses that frozen document body.
An older or otherwise nonmatching receipt still uses its own authenticated body
through the existing provider-backed representation path. No latest-head lookup,
epoch-only match, new evidence role, public API, or Language change is involved.

The snapshot, original occurrence rows, receipt identity, before/after identities,
epoch cursor, source order, canonical selector, original-input guard, imported-event
binding guard, and final publication checks are unchanged. This restores one
wire representation for one exact source *within a single captured invocation*;
it does not make arbitrary wire forms equivalent at a validation boundary.

Terminal finalization may rebase A's physical representation to a cyclic member
at the same source epoch. The test checks that precise result-backed representation
and unchanged numbered source history, rather than wrongly requiring the old
acyclic physical BlueId after a successful SCC join.

Rejected shortcuts include weakening `sameManagedLocalRepresentation`, skipping
the binding guard for empty event lists, comparing only lineage/epoch, replacing
historical input by a host head, globally normalizing arbitrary document fields,
or deleting the ordinary candidate from the fixture. The exact original lab YAML
is retained under `src/test/resources/rooted/retained-source-wire/`.

## Focused evidence and its limits

`RootedRetainedSourceWireTest` drives actual SDK admission, operation submission,
canonical B0/B1 then A0/A1/A2/A3 catch-up, and publication. Its two tests cover:

- A matching terminal source: exact cause/frozen body equality, complete cold
  result/body/receipt/event/gas-trace equality, source history preservation,
  consumer history prefix preservation, restart, canonical rejection of an
  already-consumed selector, and idempotent retained-publication reconciliation.
- A nonmatching older source: A0's own receipt remains the cause even though the
  selected source is A3; the older import still publishes successfully.

The terminal calculation consumes 463 gas. An explicitly different 462-gas
calculation rejects its final charge and rolls back with no companion, writes,
transition receipts, or events. Its full trace/rejected-charge identity is
reproduced in another fresh runtime. Across different policies, ordered charge
operands are compared without pretending that invocation-bound work IDs are
equal. This is a processor-level tight-budget control, not a low-budget SDK
setup or a claim of low-budget host publication.

The cold reference retains the *original authenticated rooted context and witness
roles*. It materializes exact resources captured before publication in a fresh
object store/runtime, with no host, external provider, history lookup, or result
cache during execution. Removing the rooted binding is not an equivalent oracle
for this input: it turns an immutable historical witness into a calculating
source and requests additional managed-occurrence evidence. We do not claim an
unrooted base-only comparison for this reciprocal witness case.

The focused gate also runs existing receipt tampering/missing-evidence controls,
atomic rollback, response-loss, join eligibility, single-view prerequisite, and
local retained-history tests. Exact commands, hashes, counts, and source-stability
records are linked from `rooted-retained-source-wire-evidence.json`. The initial
two-test red/green pair is separate from later strengthened tests and must not be
summed as independent coverage. No Language diagnostic overlay is used in any
qualifying gate; prior logging-only diagnosis is not qualification evidence.

This focused library evidence does not by itself qualify a MyOS HTTP scenario or
publish/select an immutable downstream artifact.
