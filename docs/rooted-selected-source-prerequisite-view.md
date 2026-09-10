# A join prerequisite belongs to one selected source view

## Problem and exact witness

`RootedJoinPrerequisites.returnsToOwner` previously walked from the selected
source into each descendant's independent pre-boundary rooted view. Those views
are not interchangeable: rooted processing deliberately permits independent
owners to retain different exact dependency representations.

The actual SDK regression performs X→B, then A→X, then independently detaches
X→B. A still retains its earlier X→B view and has the detach input waiting.
An attempted B→A attachment must first wait for A's earlier input. The old
traversal substituted X's independently detached graph, lost the return path,
and incorrectly returned `APPLIED` instead of `NEEDS_RESOURCES`.

## Correction and reason

Select `source.rootedViewBefore(boundary)` once and traverse only that exact
snapshot. The existing pending-history and earlier-input checks then apply to
the same source-root view that the attachment will acquire. There is no new
selection rule, new public API, cutoff widening, current-head substitution, or
publication-fence change.

This preserves RCP-HISTORY-05's terminal join prerequisite and the rooted
profile's rule that a newly discovered prerequisite before the anchor must be
resolved before joining. Reading each independent descendant's newest graph
would instead change the source input being examined.

## Evidence

`RootedJoinPrerequisiteViewTest` retains the complete actual operations. It
asserts the initial wait, no B catch-up plan or receipt publication, unchanged
A/B/X histories, restart, A's exact earlier detach input, retry of the same B
entry, eventual completion, and no duplicate X receipt. The original test red
was `expected NEEDS_RESOURCES but was APPLIED`; the corrected single-owner gate
passed. It also passed in the source-stable 20-test joined scheduling gate.
The 19 existing source-prerequisite, negative-join-prerequisite and observation
tests subsequently passed unchanged (44-second focused build).

Qualification used Coordination parent `9807ca3901829f9ef965d1c6a8a63bff516081ff`,
Language `7ec0fdaafff41ad8e41387e7e5647d5a800d807c`, and the unchanged immutable
BEX/catalog tuple in `rooted-retarget-historical-development.zE0Rg1`.
This is not a full MyOS baseline or release qualification.

The red archive is `coordination9807-selected-view-prerequisite-red.tar.gz`,
SHA-256 `5455159daf8304c77b7a508a1b5de147ac9d4c55bb72521a5c823aa44b4877bc`.
The joined 20-test archive is `coordination9807-join-focused-20-pass.tar.gz`,
SHA-256 `e5858b65fde8bc0b9a179b8298e89e042cef5656b523289b8c2381bc84a4f911`.
Both are retained outside the checkout in `rooted-terminal-owner-evidence.3RSNqs`.
