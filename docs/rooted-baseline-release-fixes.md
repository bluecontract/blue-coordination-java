# Rooted resident-baseline corrections

This is the fixes-only prerequisite for merging MyOS Simple's rooted baseline.
It starts at Coordination `next` commit `c0a689de8d0ccd89de94bf6316618c7760a68e2a`.
It does **not** contain the external-state POC, PostgreSQL adapters, runtime
serialization, worker recovery, or a replacement processing model.

`A → B` below means that A embeds B. Each row identifies an observed problem,
its correction, and a small example; the linked notes retain the detailed
reproduction and the original focused evidence. Those historical receipts do
not establish a pass for this newly assembled candidate.

## What changes, and why

| Area | Reproduction / previous result | Correction and boundary |
| --- | --- | --- |
| [Transport-only selection](rooted-transport-selection.md) | Remove an operation and then broadcast a request for it. Selection audit returned `NONE` while drain could consume the request, causing `PROCESSING_SELECTION_MISMATCH`. | Use the same completion-eligibility predicate for audit and execution. The request can terminate as `NO_MATCH` without a document epoch or PROCESS gas; blocked PROCESS work is not skipped. |
| [Static admission cutoff](rooted-static-admission-cutoff.md) | A statically embeds an already managed B at epoch 1. Admission selected B1, but later source preparation selected B0 using a different cutoff interpretation. | Retain the authenticated admission position. This is not a blanket change from strict-before to inclusive Timeline selection. |
| [Bounded selection across calls](rooted-sliced-selection.md) | A imports B1 while B2 and independent C work remain. Repeated small drains could repeatedly select a failing owner or disagree with audit. | Retain the scheduling state across calls and permit independent owners to progress. Failed historical work remains blocking for its own root; observation is read-only and physical failure does not consume work. |
| [Source-prerequisite observation](rooted-source-prerequisite-observation.md) | A requests B's earlier history; another worker completes B before A inspects its descriptor. No remaining source actions alone cannot distinguish completion from a stale request. | Add `AdvancedCoordination.observeSourceHistoryPrerequisite(...)` and `SourceHistoryPrerequisiteObservation`: `PENDING` with an exact refreshed descriptor, `SATISFIED`, or `STALE`. Validate the original requesting authority and cutoff. This query neither publishes B nor authorizes stale execution. |
| [Inactive retarget](rooted-inactive-retarget-evidence.md) | A detaches B from a reserved slot, then tries to install C there. The attempt could lose its original input or repeatedly demand evidence instead of reaching Contracts' deterministic rejection. | Preserve the B reservation and frozen C selection; authenticate the historical value needed for the rejection. Legal B reactivation, active retargets, and fresh-path attachments remain supported. Requires the separate Language correction below. |
| [Canonical BEX document values](rooted-canonical-document-view.md) | During A's import of B1, a workflow copies a saved exact C0 from `$document` into a child slot. An expanded representation supplied the wrong exact identity and triggered an unsatisfiable content demand. | Read canonical values from the current working document while keeping resolved lookup separate. Do not substitute a stale pre-step document. No BEX source change. |
| [Retained selector scope](rooted-retained-selector-scope.md) | A → B; B attaches C at `/peers/c` using a LIVE attachment selection. A later imports B's retained result and incorrectly reapplies B's prospective selector in another scope. | Do not reapply the prospective selector to local retained historical work. Invalid LIVE selections are still rejected. |
| [One selected prerequisite view](rooted-selected-source-prerequisite-view.md) | A retains X → B while X's independent head has already detached B. Discovering prerequisites for B → A could mix those two X views and miss A's required earlier input. | Walk one frozen selected source view throughout discovery, without jumping to independently newer heads. |
| [Terminal cyclic joins](rooted-terminal-join-eligibility.md) | A → B, then B → A. Unrelated scheduling of A's later LIVE work between B's historical import and terminal join could invalidate the exact same-epoch representation needed to finish. | Fence the exact prospective cycle/join dependency at its barrier; preserve earlier prerequisites and independent unrelated work. Keep exact publication/CAS checks. The new candidate index is an in-memory derived selection index, not reverse-parent closure expansion or a durable store. |
| [Frozen retained source wire](rooted-retained-source-wire.md) | A reciprocal attachment receives a receipt and frozen source with the same BlueId, but normalizing one operand differently makes historical application reject at the imported-target guard. | Reuse the authenticated frozen wire only after the complete receipt and exact source/successor identity checks match. Older receipts retain their own body. Keep the successful 463-gas case and 462-gas rollback control. |
| [Exact provider admission identity](rooted-exact-provider-source-admission.md) | A provider returns an already authenticated exact authored X. Compiling it again as a new `Source` changes its identity during admission. | Compile that exact authored value without changing its admitted identity. Normal `Source` construction and different-lineage checks are unchanged. |

## Dependency and API impact

Language needs two internal corrections for the inactive-retarget case: reserve
and classify the original occurrence correctly, and resolve an authenticated
historical selected value even when the frozen source head is later. A later
head's hash does not itself prove an earlier value; existing authenticated
resolution rules still apply. These changes live in the separate Language PR.

There are no BEX or Repository source corrections in this set. Local
development exports of those unchanged sources bind the exact Language
candidate; this is not a requirement to republish unchanged packages when
Coordination explicitly owns the final published Language dependency graph.

The observation method/value is an **additive public API change**. The bounded
cross-call scheduling and join eligibility corrections also affect selection
behavior; this package must not be described as documentation-only or solely
private refactoring. It does not choose new attachment, birth, history,
publication, or logical-gas policies.

## Engineering guard update

The new source-shape limits exactly match the measured candidate:

| Guard | Previous | Candidate | Reason |
| --- | ---: | ---: | --- |
| Production source files | 245 | 250 | Four internal helpers plus one observation API value. |
| Production lines | 72,540 | 73,157 | Net 617 lines across the fixes. |
| Public API/SDK source types | 86 | 87 | `SourceHistoryPrerequisiteObservation`. |

The per-retained-source file limit remains 1,166 lines. These are repository
size guards, **not** the managed-document, gas, or other protocol limits. Test
Given/When/Then comments are aligned with the current branch's architecture
gate; existing assertions and semantic fixtures remain mandatory.

## Verification and landing

The complete newly assembled candidate is being verified against clean,
commit-bound immutable development artifacts. Prior focused and MyOS source
suite passes explain why these fixes were selected, but are not a substitute
for this candidate's complete gate. In particular, the published default
Language RC does not yet contain the retarget fix.

Before either library PR is merged, the complete resident MyOS baseline must
pass acceptance against one frozen, coherent set of immutable development
artifacts built from the exact proposed library sources. This includes the
full product acceptance aggregate and the independent public HTTP scenario
suite. Focused corrections and an interrupted run do not satisfy this gate;
the existing assertions and deadlines remain unchanged. GitHub publication
permissions do not prevent this local pre-merge verification.

Only after that gate and the required library checks pass does the landing
order become Language review/release, Coordination's exact published Language
binding and release checks, then MyOS's published-dependency qualification.
The later published-artifact check supplements, rather than replaces, the
pre-merge end-to-end evidence. Maintainer approval owns merging and release.
The persistence branch remains separate until this resident baseline is settled.
