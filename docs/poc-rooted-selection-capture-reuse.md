# POC follow-up: one rooted capture per paired selection

Status: implemented candidate, not yet qualified. This does not change the rooted reference calculation, scheduling rules, public API or storage format.

## Problem and example

One root-selection decision checked pending local history and then LIVE entries. Each path independently rebuilt `RootedCapturedState`: forward graph/SCC ownership, exact owner heads and subscriptions, borrowed document bodies/layouts, cyclic provider evidence and snapshot equality. A root with no pending local import but an eligible Timeline entry therefore opened and captured the same owner twice before doing any processing.

The closed `first-cycle15-11b` and `first-cycle15-14a` diagnostics each recorded 59 local-history captures and 56 LIVE captures in their exit-attributed intervals. Not every aggregate call is necessarily a duplicate. The source establishes the duplicate specifically when both paths capture within one base-selection decision. This is measured motivation, not a promised wall-clock speedup or completed-ring result.

## Solution and validity boundary

`ContractsClosureAdapter.nextRootInputCandidates` captures once and performs local-history then LIVE candidate preparation under the adapter monitor. `RootedCheckpointDriver` keeps final candidate ordering, equality/tie handling, join filtering and the selected work. Registered-work order remains a delayed, library-owned lookup: blocked local history returns before it is consulted. The standalone LIVE method remains lazy for empty entries or an excluding cutoff.

This is **one non-mutating selection under the existing engine/adapter synchronization**, not a host-lifetime cache. The private captured object is library-created verified evidence, not an asserted BlueId or user-provided trust flag. It is not reused by another decision, another root, after publication/rollback, or across restored runtime owners. Every next decision captures fresh state. Local input construction and LIVE dormant-dependency projection create their own immutable derived inputs; they do not mutate the shared original capture.

Existing root/owner head, epoch, graph-generation, exact retained-input and final publication fences remain unchanged. The first complete capture, source-history verification, Timeline ordering, BEX/Contracts processing and logical gas remain required. No selected result becomes permission to execute without ordinary re-selection/publication checks. The pair adds no permanent map or retained graph beyond objects the existing local step already owned.

## Verification scope

Two added methods in the existing `CohortInvocationStorageCodecTest` (7 → 9 tests):

- `pairedRootSelectionReusesOneCaptureAndPreservesExactProcessingAndNextHead`: actual document-open work 2 → 1 versus the preserved separate methods; complete invocation/result bytes, state/events/checkpoints/gas, unchanged history during selection, normal publication and fresh next-head selection.
- `pairedRootSelectionKeepsLazyLiveCutoffsAndDropsFailedDecisionCapture`: standalone empty/cutoff LIVE remains zero-capture; paired local preparation remains one capture; exact equal-order exclusion; delayed evidence failure is propagated and retry captures afresh.

The existing real local-history test additionally compares paired/separate exact input bytes, injects temporary unavailability of the selected C/epoch-0 receipt at the authored pending position −1, and verifies the blocked path does not invoke the registered-order supplier. This is an explicit unavailable-evidence negative, not a claim that a complete published store may omit that receipt. The test restores the exact original immutable store image, preserves the cold-storage/full-result comparison, and checks fresh selection plus stale-input rejection after catch-up changes topology.

The first combined 251-case run found a test-only cleanup error in this negative: the corruption helper removes a row but deliberately cannot restore an absent row. Restoring through that helper threw in `finally`. The correction uses the existing test pattern of restoring the original private store image, without changing production or weakening the corruption helper. The new paired-selection tests themselves passed in that run; the complete affected test owner must be rerun after this fixture correction. This is not yet combined acceptance or a full-ring result.

Retain related complete owners for stale/witness/gas/join coverage: `RootedExactInlinePublicSequenceTest`, `RootedBorrowedCycleReadinessTest`, `RootedJoinPrerequisiteViewTest`, `RootedJoinEligibilityTest`, `RootedLocalStepStorageCodecTest`, `RootedEngineStorageTest`, and `RootedTerminalOwnerFenceTest`. Use one combined affected regression group with the other candidate corrections; do not present test-only timing or source review as full POC acceptance.

## Rejected alternatives

- No cross-scan cache keyed only by root/head/closure identity: joins, source completeness, subscriptions, retained progress and publication may change independently.
- No unconditional skip of capture for an apparently empty local-import list: it could omit existing owner/representation checks.
- No new externally supplied verified marker or mutable routing/session cache. The scope of the exact existing capture, not weakened authentication, permits reuse.
